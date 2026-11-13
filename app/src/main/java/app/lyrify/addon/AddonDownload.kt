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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The AI's models download in the add-on, which owns them, but KJB runs it ([AddonDownloadWorker]): from KJB's own
 * foreground service, on an unmetered network only, with the progress in KJB's notification, fed by the add-on's
 * callbacks. The add-on runs no foreground service and posts no notification (it could not ask to post one); KJB holds
 * POST_NOTIFICATIONS and asks for it when the download starts. KJB swiped away or killed, the add-on stops with it,
 * keeps its parts, and the next run goes on from them.
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
    fun start(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

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
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

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
    }

    /**
     * Starts the add-on's download over [session] and follows it to its end, each step to [progress] (KJB's notification).
     * The add-on dying is [AddonDiedException]; cancelled (KJB's work stopped), it tells the add-on to stop, which keeps
     * its parts.
     */
    suspend fun follow(session: AddonSession, progress: suspend (Long, Long) -> Unit): Download {
        val events = Channel<Event>(Channel.UNLIMITED)
        val run = session.port.download(
            object : DownloadEvents {
                override fun onProgress(done: Long, total: Long) {
                    events.trySend(Event.Progress(done, total))
                }

                override fun onEnd(end: Download) {
                    events.trySend(Event.End(end))
                }
            },
        )
        return coroutineScope {
            val death = launch {
                session.death.await()
                events.trySend(Event.Died)
            }
            try {
                var end: Download? = null
                while (end == null) {
                    when (val event = events.receive()) {
                        is Event.Progress -> progress(event.done, event.total)
                        is Event.End -> end = event.end
                        Event.Died -> throw AddonDiedException()
                    }
                }
                end
            } catch (e: CancellationException) {
                // This run only: one another worker started goes on.
                withContext(NonCancellable) { runCatching { session.port.cancelDownload(run) } }
                throw e
            } finally {
                death.cancel()
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
 * dataSync (6 hours in 24 from Android 15; opening KJB resets that) whose notification shows the add-on's progress.
 * When the phone does not allow the foreground service, it runs as an ordinary job, which Android stops after 10
 * minutes: the add-on keeps its parts and the retry goes on from them. The handover of KJB 2.0's models comes first.
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
        val end = AddonDownloads.follow(session) { bytes, total ->
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
