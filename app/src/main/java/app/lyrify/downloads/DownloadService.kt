package app.lyrify.downloads

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.text.format.DateUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.addon.AddonDiedException
import app.lyrify.addon.AddonExemptions
import app.lyrify.addon.AddonState
import app.lyrify.addon.KjbAddons
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlSearchResult
import app.lyrify.dl.api.DlStage
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.LibraryRead
import app.lyrify.dupes.MediaStoreLibrary
import app.lyrify.library.LibraryGraph
import app.lyrify.logFailures
import app.lyrify.lyrics.LocalTrack
import app.lyrify.tags.CoverEdit
import app.lyrify.tags.FRONT_COVER
import app.lyrify.tags.Id3Version
import app.lyrify.tags.TagEdit
import app.lyrify.tags.TagLibFile
import app.lyrify.tags.TagPicture
import app.lyrify.tags.applyTo
import app.lyrify.tags.lookup.AndroidImageCodec
import app.lyrify.tags.lookup.CoverPipeline
import app.lyrify.ui.DESTINATION_DOWNLOADS
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * What the Downloads page, the share sheet and Settings show: the settings, the downloads (newest first), the progress of
 * the one under way. [backgroundLimit]: Android refuses the add-on its own foreground service (its few hours a day used
 * up), as the add-on said at the last download's start, so its downloads go on only while KJB is kept open.
 */
data class DownloadsUi(
    val settings: DownloadSettings = DownloadSettings(),
    val items: List<DownloadItem> = emptyList(),
    val progress: Map<String, DlProgress> = emptyMap(),
    val loaded: Boolean = false,
    val backgroundLimit: Boolean = false,
) {
    /** The page asks to keep KJB open: Android refuses the add-on's foreground service and downloads are still to come. */
    val keepOpen: Boolean get() = backgroundLimit && items.any { it.active }
}

/**
 * The downloads, process-wide: `downloads.json` in KJB's files ([DownloadsStore]), the state the screens show, and the
 * queue KJB's own foreground service ([DownloadService]) works through, one at a time. Cancel stops the one under way at
 * once (the add-on kills its processes) and takes it off the list; Retry queues a failed one again, going on from the parts
 * the add-on kept.
 */
object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
    private val lock = Mutex()
    private val _state = MutableStateFlow(DownloadsUi())
    val state: StateFlow<DownloadsUi> = _state.asStateFlow()

    @Volatile
    private var store: DownloadsStore? = null

    /** The download the service runs now and its job: Cancel stops it. */
    @Volatile
    private var current: Pair<String, Job>? = null

    /** Downloads the user cancelled while they ran: the service takes them off, not back to the queue. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Reads the store once, in the background, for the screens. */
    fun load(context: Context) {
        if (!_state.value.loaded) scope.launch { lock.withLock { memory(context) } }
    }

    /** The settings and downloads, read from the store the first time. Called holding [lock]. */
    private fun memory(context: Context): DownloadsMemory {
        val opened = store ?: DownloadsStore(File(context.applicationContext.filesDir, "downloads.json")) { Log.w(LOG_TAG, it) }.also { store = it }
        if (_state.value.loaded) return DownloadsMemory(_state.value.settings, _state.value.items)
        return opened.load().also { memory -> _state.update { it.copy(settings = memory.settings, items = memory.items, loaded = true) } }
    }

    /**
     * Changes the memory and saves it; the screens see the change at once. False when `downloads.json` could not be
     * written: the change stays in memory all the same, and the next save writes it (a restore, BackupStores, says so).
     */
    internal suspend fun change(context: Context, block: (DownloadsMemory) -> DownloadsMemory): Boolean = lock.withLock {
        val next = block(memory(context)).capped()
        val saved = store?.save(next) == true
        _state.update { it.copy(settings = next.settings, items = next.items, progress = it.progress.filterKeys { key -> next.items.any { item -> item.key == key && item.active } }) }
        saved
    }

    /**
     * New downloads, one for each of [songs] (a song, or the songs of a playlist the sheet ticked), queued and started from
     * KJB's foreground service. [audio] is the quality the sheet picked, else Settings' ([DownloadSettings.audioQuality]).
     * [playlist]: the playlist's title, when they are its songs, counted together ([DownloadPlaylist]).
     */
    fun add(context: Context, songs: List<DlSearchResult>, kind: DlKind, height: Int?, audio: AudioQuality? = null, playlist: String? = null) {
        val app = context.applicationContext
        scope.launch {
            change(app) { it.queue(songs, kind, height, audio, playlist?.let { title -> DownloadPlaylist(key(), title) }, System.currentTimeMillis(), ::key) }
            DownloadService.start(app)
        }
    }

    private fun key(): String = "d" + UUID.randomUUID().toString().replace("-", "").take(KEY_CHARS)

    /**
     * Cancel: stops it if it runs (its processes killed), takes it off the list, and lets the add-on delete its parts.
     * [rest]: the songs of its playlist still to come go with it (the playlist's Cancel, the notification's).
     */
    fun cancel(context: Context, key: String, rest: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            // Chosen holding the store's lock ([DownloadsMemory.cancelling]): one that ended meanwhile stays on the list.
            var keys = emptySet<String>()
            change(app) { memory ->
                keys = memory.cancelling(key, rest)
                memory.copy(items = memory.items.filterNot { it.key in keys })
            }
            cancelled += keys
            current?.takeIf { it.first in keys }?.second?.cancel()
            // The one that ran is discarded by the service once it has stopped.
            val gone = keys - setOfNotNull(current?.first)
            if (gone.isNotEmpty()) DlAddon.withPort(app) { port -> gone.forEach(port::discard) }
        }
    }

    /** Retry: a failed download queued again; the add-on goes on from what it kept. */
    fun retry(context: Context, key: String) {
        val app = context.applicationContext
        scope.launch {
            change(app) { memory -> memory.copy(items = memory.items.map { if (it.key == key && it.state is DownloadState.Failed) it.copy(state = DownloadState.Queued) else it }) }
            DownloadService.start(app)
        }
    }

    /** Remove from list: only the row goes (a saved file stays); a failed one's parts go too. */
    fun remove(context: Context, key: String) {
        val app = context.applicationContext
        scope.launch {
            val failed = _state.value.items.any { it.key == key && it.state is DownloadState.Failed }
            change(app) { memory -> memory.copy(items = memory.items.filterNot { it.key == key && !it.active }) }
            if (failed) DlAddon.withPort(app) { it.discard(key) }
        }
    }

    fun settings(context: Context, edit: (DownloadSettings) -> DownloadSettings) {
        val app = context.applicationContext
        scope.launch { change(app) { it.copy(settings = edit(it.settings)) } }
    }

    /**
     * KJB is in front: downloads still queued (stopped by Android's daily limit for long work, or by KJB's process going)
     * start again. Only from the foreground, where KJB may start its foreground service.
     */
    fun resume(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val queued = lock.withLock { memory(app).items.any { it.state == DownloadState.Queued } }
            if (queued && current == null) DownloadService.start(app)
        }
    }

    // The service's side

    /** The queued download to run next ([DownloadsMemory.next]), now marked as running; null when none is left. */
    internal suspend fun next(context: Context): DownloadItem? {
        var picked: DownloadItem? = null
        change(context) { memory ->
            picked = memory.next()
            memory.copy(items = memory.items.map { if (it.key == picked?.key) it.copy(state = DownloadState.Running) else it })
        }
        return picked
    }

    internal fun running(key: String?, job: Job?) {
        current = if (key != null && job != null) key to job else null
    }

    internal fun progress(key: String, progress: DlProgress) = _state.update { it.copy(progress = it.progress + (key to progress)) }

    internal fun wasCancelled(key: String): Boolean = cancelled.remove(key)

    /** Cancelled before the service got to run it (Cancel came as it was picked): it is stopped at once. */
    internal fun isCancelled(key: String): Boolean = key in cancelled

    internal suspend fun ended(context: Context, key: String, outcome: DownloadOutcome) = change(context) { memory ->
        val state = when (outcome) {
            is DownloadOutcome.Saved -> DownloadState.Done(outcome.path, System.currentTimeMillis(), outcome.copyOf)
            is DownloadOutcome.Failed -> DownloadState.Failed(outcome.reason)
        }
        memory.copy(items = memory.items.map { if (it.key == key) it.copy(state = state) else it })
    }

    /** Back in the queue, from its parts: the service was stopped (Android's limit, or KJB's process going). */
    internal suspend fun paused(context: Context, key: String) = change(context) { memory ->
        memory.copy(items = memory.items.map { if (it.key == key && it.state == DownloadState.Running) it.copy(state = DownloadState.Queued) else it })
    }

    /** Whether Android refuses the add-on its own foreground service, as the add-on said ([DownloadsUi.backgroundLimit]). */
    fun backgroundLimit(refused: Boolean) = _state.update { it.copy(backgroundLimit = refused) }

    /** No add-on to download with: every queued download fails with [reason], for the page to say why. */
    internal suspend fun failQueued(context: Context, reason: DownloadFailure) = change(context) { memory ->
        memory.copy(items = memory.items.map { if (it.active) it.copy(state = DownloadState.Failed(reason)) else it })
    }

    private const val KEY_CHARS = 20
}

/**
 * KJB's own foreground service for downloads (type dataSync; the add-on runs none): it works through the queue one at a
 * time, binding the Downloads add-on for each ([DlAddon]), and shows the progress with a Cancel that really stops the
 * download. From Android 15 dataSync may run 6 hours in 24 while KJB is in the background: at the limit Android calls
 * [onTimeout], the download under way stops (the add-on keeps its parts) and goes back to the queue, and the queue goes on
 * the next time KJB is opened ([Downloads.resume]). Started only while KJB is in front (the share sheet, Downloads,
 * opening KJB).
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
    private var work: Job? = null

    /** The latest start: the queue stops the service only when no start came in after it looked ([queue]). */
    @Volatile
    private var lastStart = 0

    /** What the notification shows now, kept when another start puts the service in the foreground again. */
    @Volatile
    private var notice: Notification? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        if (!foreground(notice ?: DownloadNotices.working(this, null, null))) {
            // Android did not let it run in the foreground now: the queue waits for the next time KJB is opened.
            stopSelf()
            return START_NOT_STICKY
        }
        if (work?.isActive != true) work = scope.launch { queue() }
        return START_NOT_STICKY
    }

    /** Android 15+: the 6 hours of dataSync in 24 are used up. The download stops after a word to the add-on; it goes on later. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "downloads: the daily limit for long work is used up; paused")
        work?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The queue, oldest first, one at a time. With none left it stops the service, unless a start came in meanwhile
     * (a download added just then): it looks again.
     */
    private suspend fun queue() {
        try {
            val steps = PhoneSteps(this)
            val files = DownloadFiles(Environment.getExternalStorageDirectory(), File(filesDir, "downloads"), Lyrify.writes, Environment::isExternalStorageManager)
            while (true) {
                val item = Downloads.next(this)
                if (item == null) {
                    if (withContext(Dispatchers.Main) { stopSelfResult(lastStart) }) return else continue
                }
                val outcome = one(item, DownloadFinish(files, steps) { Log.d(LOG_TAG, it) }.apply { settings = Downloads.state.value.settings })
                if (outcome != null) {
                    Downloads.ended(this, item.key, outcome)
                    DownloadNotices.ended(this, item, outcome)
                    // The phone froze the add-on, or a run said nothing for minutes (most likely the same): the Downloads
                    // page says plainly that a locked phone may pause it, unless the add-on is exempt.
                    if ((outcome as? DownloadOutcome.Failed)?.reason in setOf(DownloadFailure.FROZEN, DownloadFailure.STALLED)) {
                        AddonExemptions.store(this).update { it.pausedOnce(KjbAddons.DOWNLOADS) }
                    }
                }
            }
        } finally {
            Downloads.backgroundLimit(false)
            withContext(NonCancellable + Dispatchers.Main) {
                notice = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** One download through the add-on; null when the user cancelled it. Paused (the service stopped), it goes back to the queue. */
    private suspend fun one(item: DownloadItem, saver: DownloadFinish): DownloadOutcome? = try {
        when (val connection = DlAddon.connect(this)) {
            is DlConnection.Open -> connection.session.use { session -> run(item, session, saver) }
            is DlConnection.Closed -> {
                val reason = if (connection.state == AddonState.Unreachable) DownloadFailure.ADDON_GONE else DownloadFailure.NO_ADDON
                Downloads.failQueued(this, reason)
                DownloadOutcome.Failed(reason)
            }
        }
    } catch (e: CancellationException) {
        // Stopped while it bound the add-on or ran (Android's limit): back to the queue, never left running.
        withContext(NonCancellable) { Downloads.paused(this@DownloadService, item.key) }
        throw e
    }

    /**
     * [item] in the add-on over [session]; null when the user cancelled it, and then the add-on deletes what it got (its
     * Cancel says so, and the discard after it covers a run that was still stopping).
     */
    private suspend fun run(item: DownloadItem, session: DlSession, saver: DownloadFinish): DownloadOutcome? = coroutineScope {
        askForeground(session.port)
        val keepOnCancel = { !Downloads.isCancelled(item.key) }
        val run = async {
            DownloadRunner(session.port, session.death, saver, keepOnCancel = keepOnCancel, foreground = session::foreground).run(item) { progress -> shown(item, progress) }
                // Frozen: let go of it at once, its service stopped; it keeps its parts for a Retry once it runs again.
                .also { if ((it as? DownloadOutcome.Failed)?.reason == DownloadFailure.FROZEN) session.abandon() }
        }
        Downloads.running(item.key, run)
        if (Downloads.isCancelled(item.key)) run.cancel()
        try {
            run.await()
        } catch (e: CancellationException) {
            // Only this download was cancelled (the user): the service goes on with the next.
            if (currentCoroutineContext().isActive) null else throw e
        } finally {
            Downloads.running(null, null)
        }
    }.also { if (it == null && Downloads.wasCancelled(item.key)) discard(session, item.key) }

    /**
     * Whether Android refuses the add-on its own foreground service, asked of the add-on just bound, before the download
     * starts: the page then asks the user to keep KJB open. An older add-on says not refused; a gone one says nothing.
     */
    private fun askForeground(port: DlPort) {
        val status = try {
            port.status()
        } catch (_: AddonDiedException) {
            null
        } ?: return
        Downloads.backgroundLimit(status.foregroundRefused)
    }

    private fun discard(session: DlSession, key: String) {
        try {
            session.port.discard(key)
        } catch (e: AddonDiedException) {
            Log.w(LOG_TAG, "downloads: the add-on stopped before it discarded (${e.javaClass.simpleName})")
        }
    }

    private fun shown(item: DownloadItem, progress: DlProgress) {
        Downloads.progress(item.key, progress)
        val shown = DownloadNotices.working(this, item, progress)
        notice = shown
        DownloadNotices.update(this, shown)
    }

    /** Runs in the foreground with [notification]; false when Android does not allow it now. */
    private fun foreground(notification: Notification): Boolean = try {
        startForeground(DownloadNotices.WORKING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (Android 12+) is one.
        Log.w(LOG_TAG, "downloads: not allowed in the foreground now (${e.javaClass.simpleName})")
        false
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "downloads: not allowed in the foreground now (${e.javaClass.simpleName})")
        false
    }

    companion object {
        /** Starts the service from KJB in front; quiet when Android refuses (the queue waits for the next time KJB opens). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(LOG_TAG, "downloads: service not started (${e.javaClass.simpleName})")
            }
        }
    }
}

/** The notification's Cancel (not exported; KJB's own pending intent reaches it): stops that download, and the rest of its playlist. */
class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_KEY)?.let { Downloads.cancel(context, it, rest = true) }
    }

    companion object {
        const val EXTRA_KEY: String = "app.lyrify.downloads.key"
    }
}

/** The downloads' notifications: the one under way (with Cancel), and how each ended. They open Tools > Downloads. */
internal object DownloadNotices {
    const val WORKING_ID: Int = 4
    private const val CHANNEL = "downloads"
    private const val PERCENT = 100

    private fun channel(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW))
    }

    /** KJB's Downloads page, as a notification's tap opens it: KJB's downloads' own, and the Downloads add-on's notice. */
    fun page(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        WORKING_ID,
        // SINGLE_TOP: with KJB open already, MainActivity gets the destination in onNewIntent (as the player's does).
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_DESTINATION, DESTINATION_DOWNLOADS),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * "Paper Lantern", "42% · 2.1MiB/s · 0:31 left" over a bar, and Cancel; getting ready while [item] is null. A
     * playlist's song says how far its playlist got too, and its Cancel stops the rest of it.
     */
    fun working(context: Context, item: DownloadItem?, progress: DlProgress?): Notification {
        channel(context)
        val percent = progress?.percent?.toInt()
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kjb)
            .setContentTitle(item?.title?.ifBlank { null } ?: context.getString(R.string.dl_title))
            .setContentText(stageText(context, progress))
            .setProgress(PERCENT, percent ?: 0, percent == null)
            .setContentIntent(page(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        item?.playlist?.let { builder.setSubText(playlistText(context, it.count(Downloads.state.value.items))) }
        if (item != null) {
            val cancel = PendingIntent.getBroadcast(
                context,
                item.key.hashCode(),
                Intent(context, DownloadCancelReceiver::class.java).putExtra(DownloadCancelReceiver.EXTRA_KEY, item.key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, context.getString(if (item.playlist == null) R.string.dl_cancel else R.string.dl_cancel_rest), cancel).build())
        }
        return builder.build()
    }

    /** What a download is doing, in a few words: its stage, or its percent, speed and time left while it downloads. */
    fun stageText(context: Context, progress: DlProgress?): String {
        val stage = progress?.stage ?: return context.getString(R.string.dl_starting)
        val percent = progress.percent
        if (stage == DlStage.DOWNLOADING && percent != null) {
            val parts = listOfNotNull(
                context.getString(R.string.dl_percent, percent.toInt()),
                progress.speed,
                progress.etaSec?.let { context.getString(R.string.dl_left, DateUtils.formatElapsedTime(it)) },
            )
            return parts.joinToString(" · ")
        }
        return context.getString(stageLabel(stage))
    }

    fun stageLabel(stage: DlStage): Int = when (stage) {
        DlStage.UPDATING -> R.string.dl_updating
        DlStage.STARTING -> R.string.dl_starting
        DlStage.DOWNLOADING -> R.string.dl_downloading
        DlStage.CONVERTING -> R.string.dl_converting
        DlStage.MERGING -> R.string.dl_merging
        DlStage.TAGGING -> R.string.dl_tagging
        DlStage.RETRYING -> R.string.dl_retrying
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, notification: Notification) {
        // Without POST_NOTIFICATIONS Android keeps it to the Task Manager; the service runs all the same.
        runCatching { channel(context).notify(WORKING_ID, notification) }
    }

    /**
     * "Saved" or "Not saved" with the reason, once per download, under the song's title. A playlist's songs share one,
     * under the playlist's name, changed as each ends: how many were saved, and how many could not be.
     */
    fun ended(context: Context, item: DownloadItem, outcome: DownloadOutcome) {
        val playlist = item.playlist
        val text = playlist?.let { playlistText(context, it.count(Downloads.state.value.items)) } ?: when (outcome) {
            is DownloadOutcome.Saved -> context.getString(R.string.dl_saved)
            is DownloadOutcome.Failed -> context.getString(R.string.dl_not_saved, context.getString(failureLabel(outcome.reason)))
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kjb)
            .setContentTitle(playlist?.title ?: item.title.ifBlank { context.getString(R.string.dl_title) })
            .setContentText(text)
            .setContentIntent(page(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { channel(context).notify((playlist?.key ?: item.key).hashCode(), notification) }
    }

    /** "3 of 24 saved", and "2 songs couldn't be saved" after it once some could not be. */
    fun playlistText(context: Context, count: DownloadPlaylist.Count): String {
        val saved = context.resources.getQuantityString(R.plurals.dl_playlist_saved, count.total, count.saved, count.total)
        return if (count.failed == 0) saved else saved + " · " + context.resources.getQuantityString(R.plurals.dl_playlist_failed, count.failed, count.failed)
    }

    fun failureLabel(reason: DownloadFailure): Int = when (reason) {
        DownloadFailure.OFFLINE -> R.string.dl_failed_offline
        DownloadFailure.NOT_FOUND -> R.string.dl_failed_not_found
        DownloadFailure.SITE_CHANGED, DownloadFailure.SITE_CHANGED_NEWEST -> R.string.dl_failed_site
        DownloadFailure.BLOCKED -> R.string.dl_failed_blocked
        DownloadFailure.AGE_RESTRICTED -> R.string.dl_failed_age
        DownloadFailure.NO_ROOM -> R.string.dl_failed_room
        DownloadFailure.HAND_OFF -> R.string.dl_failed_hand_off
        DownloadFailure.STALLED -> R.string.dl_failed_stalled
        DownloadFailure.FROZEN -> R.string.dl_failed_frozen
        DownloadFailure.ADDON_GONE -> R.string.dl_failed_addon
        DownloadFailure.NO_ADDON -> R.string.dl_failed_no_addon
        DownloadFailure.NOT_ALLOWED -> R.string.dl_failed_not_allowed
        DownloadFailure.LOCAL_ADDRESS -> R.string.dl_failed_local
        DownloadFailure.UNKNOWN -> R.string.dl_failed_unknown
    }
}

/**
 * KJB's own steps for a finished download on the phone: its tag engine (TagLib on a private copy, the cover made by Covers
 * & tags' pipeline), the media scan, MediaStore's songs for the Duplicates check, and the lyrics pipeline.
 */
internal class PhoneSteps(private val context: Context) : FinishSteps {
    private val tags = TagLibFile(context.filesDir)
    private val covers = CoverPipeline(AndroidImageCodec)

    /**
     * Writes [fields] and the cover into [song], KJB's private copy of a download (TagLib writes only those): an MP3 as
     * ID3v2.3. The cover is read only when it is within what Covers & tags' pipeline takes ([DownloadFiles.MAX_COVER_BYTES]).
     * False leaves yt-dlp's own tags.
     */
    override suspend fun tag(song: File, fields: Map<String, List<String>>, cover: File?): Boolean = withContext(Dispatchers.IO) {
        try {
            val read = tags.read(song) ?: return@withContext false
            val bytes = cover?.takeIf { it.length() in 1..DownloadFiles.MAX_COVER_BYTES.toLong() }?.readBytes()
            val image = bytes?.let { if (song.extension.equals("opus", ignoreCase = true)) covers.prepareOpus(it) else covers.prepare(it) }
            val front = image?.let { CoverEdit.Replace(TagPicture(it.jpeg, "", FRONT_COVER, "image/jpeg")) } ?: CoverEdit.Keep
            val applied = TagEdit(fields, cover = front).applyTo(read)
            tags.write(song, applied.fields, applied.pictures, Id3Version.V2_3)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "downloads: tags not written (${e.javaClass.simpleName})")
            false
        }
    }

    override suspend fun scan(saved: File, mime: String) {
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { done -> MediaScannerConnection.scanFile(context, arrayOf(saved.path), arrayOf(mime)) { _, _ -> done.resume(Unit) } }
        }
        LibraryGraph.repository(context).refresh()
    }

    override fun library(): List<AudioFile>? = (MediaStoreLibrary(context.contentResolver, Lyrify.writes).read() as? LibraryRead.Files)?.files

    override suspend fun lyrics(track: LocalTrack) {
        Lyrify.pipeline(context).process(track)
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 30_000L
    }
}
