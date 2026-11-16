package app.lyrify.addon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.text.format.Formatter
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.addon.shared.AddonKeepAlive
import app.lyrify.models.Download
import app.lyrify.models.DownloadPhase
import app.lyrify.models.ModelState
import app.lyrify.models.NpuChips
import app.lyrify.models.inForeground
import app.lyrify.ui.DESTINATION_AI
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The AI's models download in the add-on, which owns them, but KJB runs it ([AddonDownloadWorker]): from KJB's own
 * foreground service, on an unmetered network only, with the progress in KJB's notification, fed by the add-on's
 * callbacks. KJB holds POST_NOTIFICATIONS and asks for it when the download starts. The add-on downloads in its own
 * foreground service, which KJB starts for it as for a song's job, and KJB watches it for a phone's app freezer the same
 * way ([follow]). KJB swiped away or killed, the add-on stops with it, keeps its parts, and the next run goes on from them.
 */
object AddonDownloads {
    private const val WORK = "ai-models"
    private const val DONE = "done"
    private const val TOTAL = "total"
    private const val CHANNEL = "ai_models"
    private const val NOTIFICATION_ID = 3
    private const val RETRY_MINUTES = 1L
    private const val PERCENT = 100

    /**
     * Starts the download anew: on an unmetered network only (Wi-Fi), with storage not low, retried with backoff after a
     * network error that got nowhere. One under way is replaced; the add-on goes on from its parts (HTTP Range). Models
     * already in place are not fetched again.
     */
    fun start(context: Context) {
        _paused.value = false
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    /**
     * The AI add-on was just left out of the phone's battery optimisation: a download the phone paused goes on now, not
     * after WorkManager's backoff.
     */
    fun resumeIfPaused(context: Context) {
        if (_paused.value) start(context)
    }

    /** Starts the download unless one is under way or waiting: an update that pins a new file, at the start. */
    fun startIfIdle(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AddonDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    /** Stops KJB's download (the add-on stops with it; its parts stay): before the models are deleted. */
    fun cancel(context: Context) {
        _paused.value = false
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    private val _paused = MutableStateFlow(false)

    /**
     * The phone froze the add-on during the last download run ([follow]), which goes on at its next run: until then the AI
     * page says Paused by your phone. Kept while KJB's process lives, as the AI's holds are.
     */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** Where KJB's download work stands, for the AI page ([withPhase]). */
    fun phase(context: Context): Flow<DownloadPhase?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK).map { infos -> phaseOf(infos.map { it.state }) }.distinctUntilChanged()

    /**
     * The phase of the unique work's rows, which WorkManager lists in no order (a REPLACE leaves the cancelled or failed
     * row beside the new one): one running, else one waiting, else one that failed; none otherwise.
     */
    fun phaseOf(states: List<WorkInfo.State>): DownloadPhase? = when {
        WorkInfo.State.RUNNING in states -> DownloadPhase.RUNNING
        states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> DownloadPhase.WAITING
        WorkInfo.State.FAILED in states -> DownloadPhase.FAILED
        else -> null
    }

    /**
     * The page's model state: the add-on's ([models], from its files and its own download), with KJB's own [phase] of the
     * download: KJB alone knows that it waits for Wi-Fi (its work's constraint) or that it failed. The add-on's word
     * stands while it downloads, once the models are in place, and when there is no room.
     */
    fun withPhase(models: ModelState?, phase: DownloadPhase?): ModelState? {
        if (models == null || models is ModelState.Ready || models is ModelState.NoRoom) return models
        if (models is ModelState.Downloading && !models.waiting) return models
        val done = when (models) {
            is ModelState.Missing -> models.doneBytes
            is ModelState.Downloading -> models.doneBytes
            is ModelState.Failed -> models.doneBytes
            else -> 0
        }
        return when (phase) {
            DownloadPhase.RUNNING -> ModelState.Downloading(done, models.totalBytes, waiting = false)
            DownloadPhase.WAITING -> ModelState.Downloading(done, models.totalBytes, waiting = true)
            DownloadPhase.FAILED -> ModelState.Failed(done, models.totalBytes)
            null -> models
        }
    }

    /** The add-on's download as it goes. */
    private sealed interface Event {
        data class Progress(val done: Long, val total: Long) : Event

        data class End(val end: Download) : Event

        data object Died : Event

        /** Nothing for [AddonKeepAlive.FROZEN_MS] from an add-on that beat: the phone froze it. */
        data object Frozen : Event
    }

    /**
     * Starts the add-on's download over [session] and follows it to its end, each step to [progress] (KJB's notification).
     * Once the add-on took it, KJB starts the add-on's own foreground service for it ([AddonSession.foreground]), which the
     * add-on leaves at the download's end; a phone that refuses it is told to [notes]. The add-on dying is
     * [AddonDiedException]; cancelled (KJB's work stopped), it tells the add-on to stop, which keeps its parts.
     *
     * Watched as a song's job is ([FreezeWatch]): the add-on beats every few seconds, from a timer no download blocks, so a
     * run that beat and then said nothing for [AddonKeepAlive.FROZEN_MS] is a process the phone froze. Nothing more is
     * asked of it (a call into a frozen process can wait for as long as it stays frozen); it is let go of and its service
     * stopped ([AddonSession.abandon]), [paused] says so until the next run, and it is [AddonFrozenException]: its parts stay
     * for the next run. An add-on that never beat is not watched.
     */
    suspend fun follow(session: AddonSession, notes: (String) -> Unit = {}, progress: suspend (Long, Long) -> Unit): Download {
        _paused.value = false
        val events = Channel<Event>(Channel.UNLIMITED)
        // The end, also kept apart from the channel: told as the watch gives up, it can come behind the watch's word.
        val ended = AtomicReference<Download?>(null)
        // Each word from the add-on, beats included, for the freeze watch, which starts with the first beat.
        val watch = FreezeWatch()
        val run = session.port.download(
            object : DownloadEvents {
                override fun onProgress(done: Long, total: Long) = tell(Event.Progress(done, total))

                override fun onEnd(end: Download) {
                    ended.compareAndSet(null, end)
                    tell(Event.End(end))
                }

                override fun onBeat() = watch.beat()

                private fun tell(event: Event) {
                    watch.heard()
                    events.trySend(event)
                }
            },
        )
        if (!session.foreground()) notes("the phone did not let the AI add-on download in its own foreground service")
        return coroutineScope {
            val death = launch {
                session.death.await()
                events.trySend(Event.Died)
            }
            val watching = launch {
                watch.silence()
                events.trySend(Event.Frozen)
            }
            try {
                var end: Download? = null
                while (end == null) {
                    when (val event = events.receive()) {
                        is Event.Progress -> progress(event.done, event.total)
                        is Event.End -> end = event.end
                        Event.Died -> throw AddonDiedException()
                        // An end told meanwhile follows in the channel: the run ended, the phone froze nothing.
                        Event.Frozen -> if (ended.get() == null) {
                            _paused.value = true
                            session.abandon()
                            throw AddonFrozenException(stage = null, silentMs = AddonKeepAlive.FROZEN_MS)
                        }
                    }
                }
                end
            } catch (e: CancellationException) {
                // This run only: one another worker started goes on.
                withContext(NonCancellable) { runCatching { session.port.cancelDownload(run) } }
                throw e
            } finally {
                death.cancel()
                watching.cancel()
            }
        }
    }

    /** The download's notification: "Downloading the AI models: 42% (412 MB of 981 MB)" over a progress bar. */
    internal fun foregroundInfo(context: Context, done: Long, total: Long): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ai_models_channel), NotificationManager.IMPORTANCE_LOW))
        val percent = (done * PERCENT / total.coerceAtLeast(1)).toInt()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_DESTINATION, DESTINATION_AI),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kjb)
            .setContentTitle(context.getString(R.string.ai_title))
            .setContentText(
                context.getString(
                    R.string.ai_models_downloading,
                    percent,
                    Formatter.formatShortFileSize(context, done),
                    Formatter.formatShortFileSize(context, total),
                ),
            )
            .setProgress(PERCENT, percent, false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** What the worker reports as it goes: bytes done of all. */
    internal fun progressData(done: Long, total: Long) = workDataOf(DONE to done, TOTAL to total)
}

/**
 * KJB's run of the add-on's model download: bound to the add-on for the whole download, in a foreground service of type
 * dataSync (6 hours in 24 from Android 15; opening KJB resets that) whose notification shows the add-on's progress, the
 * add-on in its own ([AddonDownloads.follow]). When the phone does not allow the foreground service, it runs as an
 * ordinary job, which Android stops after 10 minutes: the add-on keeps its parts and the retry goes on from them, as it
 * does after the add-on died or the phone froze it. The handover of KJB 2.0's models comes first.
 */
class AddonDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (NpuChips.supported() == null || withContext(Dispatchers.IO) { ModelHandover.pending(app) }) return Result.success()
        val session = when (val connection = Addon.client(app).connect()) {
            is Connection.Open -> connection.session
            // Not there, not KJB's, or too old: Get AI comes first. One that did not answer is tried again.
            is Connection.Closed -> return if (connection.state == AddonState.Unreachable) Result.retry() else Result.success()
        }
        return try {
            session.use { download(it) }
        } catch (_: AddonDiedException) {
            Log.w(LOG_TAG, "models: the add-on stopped during the download; it goes on later from its parts")
            Result.retry()
        } catch (e: AddonFrozenException) {
            Log.w(LOG_TAG, "models: the phone paused the AI add-on during the download (nothing for ${e.silentMs / 1000} s); it goes on later from its parts")
            // The AI page now says plainly that a locked phone may pause the add-on, unless it is exempt.
            AddonExemptions.store(app).update { it.pausedOnce(KjbAddons.AI) }
            Result.retry()
        }
    }

    private suspend fun download(session: AddonSession): Result {
        val models = session.port.status()?.models ?: return Result.retry()
        // In place: nothing to fetch, no foreground service.
        if (models is ModelState.Ready) return Result.success()
        val done = when (models) {
            is ModelState.Missing -> models.doneBytes
            is ModelState.Downloading -> models.doneBytes
            is ModelState.NoRoom -> models.doneBytes
            is ModelState.Failed -> models.doneBytes
            is ModelState.Ready -> models.totalBytes
        }
        val inForeground = foreground(done, models.totalBytes)
        val end = AddonDownloads.follow(session, notes = { Log.w(LOG_TAG, "models: $it") }) { bytes, total ->
            setProgress(AddonDownloads.progressData(bytes, total))
            if (inForeground) foreground(bytes, total)
        }
        return when (end) {
            Download.Done -> Result.success()
            is Download.Interrupted -> Result.retry()
            is Download.Failed -> Result.failure()
        }
    }

    /** Runs (or keeps running) in the foreground showing [done] of [total] bytes; false when the phone does not allow it now. */
    private suspend fun foreground(done: Long, total: Long): Boolean =
        inForeground({ setForeground(AddonDownloads.foregroundInfo(applicationContext, done, total)) }) { e ->
            // The download goes on as an ordinary job.
            Log.w(LOG_TAG, "models: not allowed to download in the foreground: ${e.javaClass.simpleName}")
        }
}
