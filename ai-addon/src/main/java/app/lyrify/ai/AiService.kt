package app.lyrify.ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import app.lyrify.addon.shared.AddonKeepAlive
import app.lyrify.addon.shared.CallerCheck
import app.lyrify.addon.shared.ExemptionActivity
import app.lyrify.addon.shared.ForegroundPort
import app.lyrify.addon.shared.JobForeground
import app.lyrify.addon.shared.beating
import app.lyrify.ai.api.AddonNames
import app.lyrify.ai.api.AddonStatus
import app.lyrify.ai.api.IAiAddon
import app.lyrify.ai.api.IAiDownloadCallback
import app.lyrify.ai.api.IAiRunCallback
import app.lyrify.ai.api.IAiStatusCallback
import app.lyrify.ai.api.ImportReply
import app.lyrify.ai.api.Wire
import app.lyrify.models.Download
import app.lyrify.models.DownloadEvents
import app.lyrify.models.DownloadRun
import app.lyrify.models.ModelDownloads
import app.lyrify.models.ModelImport
import app.lyrify.models.ModelState
import app.lyrify.models.RunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Logcat's tag for the add-on. Its lines carry counts, stages and exception classes, never a song, a path or lyrics. */
const val LOG_TAG: String = "KJB-AI"

/** How the add-on's model downloads introduce themselves. */
val USER_AGENT: String = "KJB-AI/${BuildConfig.VERSION_NAME} ( ${BuildConfig.REPOSITORY} )"

/** CPU threads for the engine: the MDX STFT and iSTFT (the spike's measured 4) and wav2vec2. The models run on the NPU. */
const val ENGINE_THREADS: Int = 4

/** What one song's job tells its caller: IAiRunCallback, as Kotlin. */
interface JobEvents {
    fun onStage(stage: AiStage)

    fun onNote(line: String)

    /** The next Whisper chunk waits for [AddonJobs.proceed]. */
    fun onChunk()

    fun onResult(result: AiResult)

    /** Stopped by [AddonJobs.cancel]: nothing to record. */
    fun onStopped()

    /** [done] of [total] pieces of the stage it is in: the first of each stage, then at most every [AddonJobs.PROGRESS_EVERY_MS]. */
    fun onProgress(done: Int, total: Int)

    /** The job is alive: at its start, then every [AddonJobs.BEAT_EVERY_MS] until it ends, whatever its step. */
    fun onBeat()
}

/** The AI for songs ([LyricsFromAudio] on [NpuStages]): its models stay loaded from the first song until [close]. */
interface JobEngine : AutoCloseable {
    suspend fun run(
        song: AiSong,
        plainLyrics: String?,
        onStage: (AiStage) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        beforeChunk: suspend () -> Unit,
    ): AiResult
}

/**
 * The songs KJB sends, one at a time, on [context] in [scope]. The engine is opened ([openEngine], with where its notes
 * go) for the first song and kept for the next ones until [closeEngine]: one night's run loads the models once. A job
 * tells KJB each stage it enters and the pieces of a stage done (the first at once, then at most every
 * [PROGRESS_EVERY_MS]), so KJB's watchdog hears it move, and it beats every [BEAT_EVERY_MS] on [beats], apart from its own
 * thread, so that KJB tells a process a phone froze (no beat) from a step that takes minutes (beats, no progress). Before
 * each Whisper chunk a job tells KJB and waits for [proceed] (KJB holds it while the phone is too warm). [cancel] stops a
 * job after the step it is on. When the last client leaves, [shutDown] lets go of the NPU at once (or as the step under
 * way ends), whatever happens to the service's scope after. A stopped job whose step has not ended [STEP_END_MS] later is
 * stuck in native work no stop reaches (the NPU, a codec): the add-on's process ends ([endProcess]), the only way to free
 * what that step holds.
 */
class AddonJobs(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    /** Monotonic milliseconds: the progress's pace and the debug lines' times. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Runs [check] [delayMs] later, outside [scope] (the service's, which onDestroy cancels): the main thread on the phone. */
    private val later: (delayMs: Long, check: () -> Unit) -> Unit = { _, _ -> },
    /** Ends the add-on's process, a job's step stuck in [stage]: the only way to free a native step that never returns. */
    private val endProcess: (stage: AiStage?) -> Unit = {},
    /** Debug builds' logcat: jobs, stages, counts and times, never a song, a path or lyrics. */
    private val debug: (String) -> Unit = {},
    /** Waits up to [ms] for [job] to end, blocking (the main thread, in onUnbind); whether it did. */
    private val waitForEnd: (job: Job, ms: Long) -> Boolean = { _, _ -> false },
    /** Where a job's beats run (the main thread on the phone): apart from [context], whose thread a native step blocks. */
    private val beats: CoroutineContext = context,
    private val openEngine: (notes: (String) -> Unit) -> JobEngine,
) {
    private val lock = Any()
    private var engine: JobEngine? = null
    private var running: Running? = null
    private var nextId = 1L

    /** Set by [shutDown] while a job is mid-step: the engine closes as that job ends. */
    private var closeAfterJob = false

    /** Set by [abandoned]: a native step was left on its thread, holding the NPU until the process ends. */
    @Volatile
    private var stepLeftBehind = false

    /** The last job that ended with a result, and that result ([result]): for a callback lost or too long. */
    private var kept: Pair<Long, AiResult>? = null

    /** Where the engine's notes go: the job running now. */
    @Volatile
    private var notesTo: JobEvents? = null

    /**
     * The job under way: the chunk it waits at for [proceed], the stage it is in, when it last told its progress, and what
     * it ends with if it is stopped ([endAs], set by [timeUp]; else it ends as stopped).
     */
    private class Running(val id: Long, val started: Long) {
        lateinit var job: Job
        var waiting: CompletableDeferred<Unit>? = null

        @Volatile
        var endAs: AiResult? = null

        @Volatile
        var stage: AiStage? = null
        var progressAt: Long? = null
        var chunks = 0

        val where: String get() = stage?.name?.lowercase() ?: "its start"
    }

    /**
     * Starts [song]; [events] makes the job's events from its id, [release] lets go of the song once the job has ended.
     * IllegalStateException while another job runs.
     */
    fun start(song: AiSong, plainLyrics: String?, events: (Long) -> JobEvents, release: (Long) -> Unit): Long = synchronized(lock) {
        check(running == null) { "a job is already running" }
        val run = Running(nextId++, clock())
        val heard = events(run.id)
        debug("job ${run.id}: started")
        // ATOMIC: the body runs, and its finally lets go of the song, even when the scope goes before it was dispatched.
        run.job = scope.launch(context, start = CoroutineStart.ATOMIC) {
            // The job's beats, apart from it: they go on while a native step blocks the job's thread for minutes.
            val beating = scope.beating(beats) { heard.onBeat() }
            try {
                val result = if (stepLeftBehind) {
                    // A step left behind holds the NPU until the process ends: no session is made beside it, and the
                    // song is tried again later, as for any NPU another app holds.
                    AiResult.Failed(STEP_LEFT_BEHIND, npu = true)
                } else try {
                    val engine = engine()
                    notesTo = heard
                    engine.run(song, plainLyrics, { entered(run, heard, it) }, { done, total -> progressed(run, heard, done, total) }) {
                        atChunk(run, heard)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The engine did not open (its models are gone): a failed run, as one the engine itself reports.
                    AiResult.Failed(e.javaClass.simpleName)
                }
                debug("job ${run.id}: ${label(result)} at ${run.at()} ms")
                synchronized(lock) { kept = run.id to result }
                heard.onResult(result)
            } catch (e: CancellationException) {
                val instead = run.endAs
                if (instead == null) {
                    debug("job ${run.id}: stopped in ${run.where} at ${run.at()} ms")
                    heard.onStopped()
                } else {
                    debug("job ${run.id}: ${label(instead)} in ${run.where} at ${run.at()} ms")
                    synchronized(lock) { kept = run.id to instead }
                    heard.onResult(instead)
                }
                throw e
            } finally {
                beating.cancel()
                notesTo = null
                synchronized(lock) {
                    if (running === run) running = null
                    if (closeAfterJob) {
                        closeAfterJob = false
                        closeNow()
                    }
                }
                release(run.id)
            }
        }
        running = run
        run.id
    }

    private fun entered(run: Running, heard: JobEvents, stage: AiStage) {
        run.stage = stage
        run.progressAt = null
        debug("job ${run.id}: ${run.where} at ${run.at()} ms")
        heard.onStage(stage)
    }

    /** A piece of the stage done: told to KJB when it is the stage's first, or [PROGRESS_EVERY_MS] after the last one told. */
    private fun progressed(run: Running, heard: JobEvents, done: Int, total: Int) {
        val now = clock()
        val last = run.progressAt
        if (last != null && now - last < PROGRESS_EVERY_MS) return
        run.progressAt = now
        debug("job ${run.id}: ${run.where} $done of $total at ${now - run.started} ms")
        heard.onProgress(done, total)
    }

    /** Before a Whisper chunk: tells KJB and waits for its go-ahead (or for the job to be cancelled). */
    private suspend fun atChunk(run: Running, heard: JobEvents) {
        val gate = CompletableDeferred<Unit>()
        synchronized(lock) { run.waiting = gate }
        val chunk = ++run.chunks
        debug("job ${run.id}: chunk $chunk waits for KJB at ${run.at()} ms")
        heard.onChunk()
        gate.await()
        debug("job ${run.id}: chunk $chunk goes on at ${run.at()} ms")
    }

    private fun engine(): JobEngine = synchronized(lock) {
        engine ?: openEngine { line -> notesTo?.onNote(line) }.also { engine = it }
    }

    fun proceed(job: Long) {
        synchronized(lock) {
            val run = running?.takeIf { it.id == job } ?: return
            run.waiting?.complete(Unit)
            run.waiting = null
        }
    }

    fun cancel(job: Long) {
        synchronized(lock) { running?.takeIf { it.id == job } }?.let { stop(it, "KJB cancelled it") }
    }

    /** The result of [job], kept from its end until the next job's; null for a job unknown, running, stopped or replaced. */
    fun result(job: Long): AiResult? = synchronized(lock) { kept?.takeIf { it.first == job }?.second }

    /** Whether a job runs now: from [start] until its end. */
    val busy: Boolean get() = synchronized(lock) { running != null }

    /**
     * Android's time for the add-on's foreground service ran out (onTimeout, Android 15+: 6 hours in 24 for its type):
     * the job under way stops after the step it is on, or the process ends ([stop]), and ends as the NPU busy, so that
     * KJB records nothing and tries the song again later. The jobs after it run without the foreground service until
     * Android gives the time back.
     */
    fun timeUp() {
        val run = synchronized(lock) { running } ?: return
        run.endAs = AiResult.Failed(TIME_UP, npu = true)
        stop(run, "Android's time for the foreground service ran out")
    }

    /** Stops a job under way, then closes the engine; the returned job ends once it is closed. */
    fun closeEngine(): Job = scope.launch(context) {
        synchronized(lock) { running }?.let { run ->
            stop(run, "closing the engine")
            run.job.join()
        }
        // A job started meanwhile keeps the engine; it closes with the next call.
        synchronized(lock) { if (running == null) closeNow() }
    }

    /**
     * The last client left (onUnbind, onDestroy): the NPU sessions close now, on the caller's thread, with nothing left
     * to a scope the service is about to cancel. A job mid-step is cancelled and closes them as it ends (a native step
     * cannot be cut short; closing under it would free what it uses), waited for up to [SHUTDOWN_WAIT_MS]; a step that
     * has not ended by then, or a native step left behind ([abandoned]), ends the process. Decided here, during the
     * unbind: an add-on no client binds is cached, and Android 14+ freezes a cached process 10 s later, so a check made
     * later might never run.
     */
    fun shutDown() {
        val run = synchronized(lock) {
            running.also { if (it == null) closeNow() else closeAfterJob = true }
        }
        if (run != null) {
            stop(run, "the last client left")
            if (!waitForEnd(run.job, SHUTDOWN_WAIT_MS)) {
                endProcess(run.stage)
                return
            }
        }
        if (stepLeftBehind) endProcess(run?.stage)
    }

    /**
     * Cancels [run]'s job, which ends after the step it is on. A step that has not ended [STEP_END_MS] later is native
     * work no stop reaches, which would hold the NPU and the memory for good: the process ends instead ([endProcess]).
     */
    private fun stop(run: Running, why: String) {
        debug("job ${run.id}: $why in ${run.where} at ${run.at()} ms")
        run.job.cancel()
        later(STEP_END_MS) {
            if (!run.job.isCompleted) endProcess(run.stage)
        }
    }

    /**
     * A native step was left on its thread past its deadline ([NativeDeadline]: an NPU that did not answer): it holds the
     * NPU and its memory until the process ends. The job goes on and tells KJB (the NPU busy, try later), and every song
     * started after it is refused the same way; the process ends when the last client leaves ([shutDown]), or
     * [ABANDONED_END_MS] later while one stays bound.
     */
    fun abandoned() {
        stepLeftBehind = true
        val stage = synchronized(lock) { running?.stage }
        debug("a native step in ${stage?.name?.lowercase() ?: "no job"} was left behind: the process ends when KJB lets go")
        later(ABANDONED_END_MS) { endProcess(stage) }
    }

    /** Releases the engine's sessions. Called holding [lock]. */
    private fun closeNow() {
        engine?.let { runCatching { it.close() } }
        engine = null
    }

    private fun Running.at(): Long = clock() - started

    /** A result for the debug lines: its kind, and a failure's exception class or a rejection's reason. */
    private fun label(result: AiResult): String = when (result) {
        is AiResult.Failed -> "failed (${result.kind})"
        is AiResult.Rejected -> "rejected (${result.reason})"
        else -> result.javaClass.simpleName.lowercase()
    }

    companion object {
        /** A job's progress goes to KJB at most this often: the aligner moves every few milliseconds. */
        const val PROGRESS_EVERY_MS: Long = 5_000

        /** How often a job tells KJB it is alive ([JobEvents.onBeat]); KJB takes 6 missed beats for a freeze. */
        const val BEAT_EVERY_MS: Long = AddonKeepAlive.BEAT_EVERY_MS

        /**
         * How long a stopped job's step may take to end before the process ends instead. Every step but one takes
         * seconds at most (an MDX segment, a Whisper chunk, a wav2vec2 window); creating the MDX session takes minutes,
         * and a job stopped then has nothing to keep: its process ending sooner only frees the NPU sooner.
         */
        const val STEP_END_MS: Long = 10_000

        /**
         * How long the last client leaving waits (on the main thread, in onUnbind) for a stopped job's step to end before
         * the process ends instead: an MDX segment or a Whisper chunk (3.6 s at most seen) ends within it.
         */
        const val SHUTDOWN_WAIT_MS: Long = 5_000

        /**
         * How long a process with a native step left behind lives on while a client stays bound: KJB has the job's end
         * (the NPU busy) long before, and ends its run and lets go at once.
         */
        const val ABANDONED_END_MS: Long = 30_000

        /** What a song started after a step was left behind ([abandoned]) ends with: the NPU busy, until the process restarts. */
        private const val STEP_LEFT_BEHIND = "NpuBusyException: an earlier step still holds the NPU until the add-on restarts"

        /** What the job under way ends with when Android's time for the foreground service ran out ([timeUp]). */
        private const val TIME_UP = "ForegroundServiceTimeout: the phone's daily time for the AI add-on's work ran out"
    }
}

/**
 * The type of the add-on's foreground service while a song's job works ([JobForeground]): mediaProcessing from Android 15
 * (long work on media files), dataSync before it, on [sdk] (Build.VERSION.SDK_INT on the phone).
 */
@SuppressLint("InlinedApi")
fun jobForegroundType(sdk: Int): Int =
    if (sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

/**
 * The type of the add-on's foreground service for the work under way ([JobForeground]): a song's job's
 * ([jobForegroundType]) while one runs, else the models' download's, dataSync on every Android version (a download, which
 * Android 15+ gives its own 6 hours in 24, apart from the jobs' mediaProcessing time).
 */
fun foregroundType(sdk: Int, job: Boolean): Int = if (job) jobForegroundType(sdk) else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

/** How far the models' download is, for its notice: whole percents of [total] bytes, 0 until the size is told, never past 100. */
fun downloadPercent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * PERCENT / total).toInt().coerceIn(0, PERCENT)

private const val PERCENT = 100

/** KJB of this build type, signed with its pinned certificate: the one caller of the add-on's service and activity. */
fun Context.kjbCallers(): CallerCheck = CallerCheck(
    packageName,
    BuildConfig.KJB_PACKAGE,
    BuildConfig.KJB_CERT_SHA256,
    packagesFor = packageManager::getPackagesForUid,
    hasCert = { uid, sha -> packageManager.hasSigningCertificate(uid, sha, PackageManager.CERT_INPUT_SHA256) },
)

/** The add-on's own request to be left out of the phone's battery optimisation ([ExemptionActivity]), started by KJB only. */
class AiExemptionActivity : ExemptionActivity() {
    override val callers: CallerCheck by lazy { kjbCallers() }
}

/**
 * The add-on's one service, bound by KJB only ([CallerCheck] on every call, behind the signature permission): the AI
 * ([AddonJobs]), the model download ([ModelDownloads]) and the handover of KJB 2.0's models. KJB binds it while the AI
 * works or the models download, from its own foreground service, and while its AI page shows. While a song's job runs or
 * the models download, KJB also starts it, and it runs in the foreground with its own notification ([JobForeground], of
 * [foregroundType]): a phone's app freezer leaves alone an app at work in the foreground, not a service another app binds.
 * Nothing here touches ONNX Runtime until a song's engine opens, so the QNN EP registers its library path before any HTP
 * library loads in this process ([QnnEp]).
 */
class AiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val callers by lazy { kjbCallers() }

    /** Debug builds only: each step of a job in logcat (stages, counts, times), so a phone run shows where a job stands. */
    private val debug: (String) -> Unit = if (BuildConfig.DEBUG) { line -> Log.d(LOG_TAG, line) } else { _ -> }

    private val jobs by lazy {
        val main = Handler(Looper.getMainLooper())
        AddonJobs(
            scope,
            Dispatchers.Default,
            clock = SystemClock::elapsedRealtime,
            // The main thread outlives the service's scope (onDestroy cancels it) for as long as the process lives.
            later = { ms, check -> main.postDelayed(check, ms) },
            endProcess = { stage ->
                Log.w(LOG_TAG, "a stopped job's step (${stage?.name?.lowercase() ?: "its start"}) did not end within ${AddonJobs.STEP_END_MS} ms: the process ends to free the NPU")
                Process.killProcess(Process.myPid())
            },
            debug = debug,
            // In onUnbind, on the main thread: the job runs on Dispatchers.Default.
            waitForEnd = { job, ms -> runBlocking { withTimeoutOrNull(ms) { job.join() } } != null },
            // The main thread: no job's step runs there.
            beats = Dispatchers.Main,
            openEngine = ::engine,
        )
    }

    private val foreground: JobForeground<PendingIntent> by lazy {
        JobForeground(
            // Each start goes into the foreground as the work under way: a song's job, else the download (KJB downloads
            // while the models are not in place, which a job needs, so the two seldom meet).
            { foregroundType(Build.VERSION.SDK_INT, job = jobs.busy) },
            object : ForegroundPort<PendingIntent> {
                override fun startForeground(type: Int, tap: PendingIntent?) = this@AiService.startForeground(NOTICE_ID, notice(tap), type)

                override fun stopForeground() {
                    this@AiService.stopForeground(STOP_FOREGROUND_REMOVE)
                    // A notice posted again just after Android let the service go (KJB stopped it, the add-on frozen
                    // until now) is a notice of its own, which the line above leaves: it goes too.
                    getSystemService(NotificationManager::class.java).cancel(NOTICE_ID)
                }

                override fun stopSelf(startId: Int?) {
                    if (startId == null) this@AiService.stopSelf() else this@AiService.stopSelfResult(startId)
                }

                // Under the service's id, Android takes it for the service's own notice (NotificationManagerService,
                // applyForegroundServiceNotification). Without the notification permission no notice of the add-on
                // shows, the first one included, and the service is in the phone's list of apps at work all the same.
                @SuppressLint("NotificationPermission")
                override fun updateNotice(tap: PendingIntent?) = getSystemService(NotificationManager::class.java).notify(NOTICE_ID, notice(tap))
            },
            busy = { jobs.busy || downloads.busy },
            fromKjb = { tap -> AddonKeepAlive.fromKjb(tap, callers) },
            endJob = {
                jobs.timeUp()
                downloads.timeUp()
            },
            log = { Log.i(LOG_TAG, it) },
        )
    }

    /**
     * The model download, run while KJB keeps the add-on bound from its foreground service (which shows its notification),
     * in the add-on's own foreground service, which KJB starts once the add-on took it ([foreground]): its notice follows
     * the progress. Its beats run on the main thread, which no download blocks.
     */
    private val downloads by lazy {
        DownloadRun(scope, beats = Dispatchers.Main, moved = { foreground.noticeChanged() }, ended = { foreground.jobEnded() }) { progress ->
            ModelDownloads.download(this, progress)
        }
    }

    /** KJB's status watchers ([IAiAddon.watchStatus]); the status is collected only while there is one. */
    private val watchers = object : RemoteCallbackList<IAiStatusCallback>() {
        override fun onCallbackDied(callback: IAiStatusCallback) = watchedLess()
    }
    private var watching: Job? = null

    /**
     * Served only while the bind permission is this add-on's own, at signature level ([CallerCheck.guardsBind]); the
     * caller is not known here (onBind runs outside its transaction), so each call checks it.
     */
    override fun onBind(intent: Intent): IBinder? {
        val permission = try {
            packageManager.getPermissionInfo(AddonNames.permission(packageName), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (!callers.guardsBind(permission?.packageName, permission?.protection == PermissionInfo.PROTECTION_SIGNATURE)) {
            Log.w(LOG_TAG, "bind refused: the bind permission is not this add-on's own")
            return null
        }
        return binder
    }

    /**
     * KJB's start for a song's job or the models' download, Context.startForegroundService ([JobForeground]): into the
     * foreground at once, as Android requires within seconds. Never started again by Android on its own.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground.start(startId, intent?.let(AddonKeepAlive::tap))
        return START_NOT_STICKY
    }

    /** Android's time for the foreground service ran out (Android 15+): out of it within seconds, or Android ends the add-on. */
    override fun onTimeout(startId: Int, fgsType: Int) = foreground.timeUp()

    /**
     * The notice while the add-on works, in a quiet channel. A job: the same words for every job, nothing of the song
     * (KJB's own notification names it). The models' download: how far it is, a percent and two sizes over a bar, never a
     * file or a link. A tap opens KJB's AI page through [tap], KJB's own. Without the notification permission, which the
     * add-on never asks for, it shows in the phone's list of apps at work only, and the service runs all the same.
     */
    private fun notice(tap: PendingIntent?): Notification {
        val channel = NotificationChannel(NOTICE_CHANNEL, getString(R.string.work_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notice = Notification.Builder(this, NOTICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kjb)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val download = downloads.state.value as? RunState.Running
        if (jobs.busy || download == null) return notice.setContentTitle(getString(R.string.work_title)).build()
        notice.setContentTitle(getString(R.string.models_title))
        val done = download.doneBytes
        val total = download.totalBytes
        if (done == null || total == null) return notice.setProgress(0, 0, true).build()
        val percent = downloadPercent(done, total)
        return notice
            .setContentText(getString(R.string.models_progress, percent, Formatter.formatShortFileSize(this, done), Formatter.formatShortFileSize(this, total)))
            .setProgress(PERCENT, percent, false)
            .build()
    }

    /** KJB let go of the add-on: what it started stops, the NPU sessions close now, and it leaves the foreground. */
    override fun onUnbind(intent: Intent): Boolean {
        foreground.unbound()
        jobs.shutDown()
        // KJB went away (swiped, killed): the download pauses, and goes on from its parts at the next bind.
        downloads.cancelAll()
        return false
    }

    /** The sessions close before the service's scope goes ([AddonJobs.shutDown]), not in a coroutine it would cancel. */
    override fun onDestroy() {
        jobs.shutDown()
        watchers.kill()
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : IAiAddon.Stub() {
        override fun apiVersion(): Int = checked { IAiAddon.VERSION }

        override fun status(): String = checked { Wire.encode(runBlocking { statusOf(ModelDownloads.state(this@AiService, downloads.state).first()) }) }

        override fun watchStatus(callback: IAiStatusCallback) = checked {
            watchers.register(callback)
            watch()
        }

        override fun unwatchStatus(callback: IAiStatusCallback) = checked {
            watchers.unregister(callback)
            watchedLess()
        }

        override fun download(callback: IAiDownloadCallback): Long = checked {
            val events = DownloadCallback(callback)
            events.run = downloads.start(events)
            events
        }.run

        override fun cancelDownload(run: Long) = checked { downloads.cancel(run) }

        override fun deleteModels(): Boolean = checked {
            downloads.cancelAll()
            runBlocking {
                jobs.closeEngine().join()
                ModelDownloads.delete(this@AiService)
            }
        }

        override fun discardModel(name: String): Boolean = checked {
            downloads.cancelAll()
            runBlocking {
                jobs.closeEngine().join()
                ModelDownloads.discard(this@AiService, File(ModelDownloads.folder(this@AiService), name))
            }
        }

        override fun run(song: ParcelFileDescriptor, durationMs: Long, plainLyrics: String?, callback: IAiRunCallback): Long = checked(song) {
            // The add-on's own descriptor of the song, from the call: closed once the job has ended.
            try {
                jobs.start(AiSong(song.fileDescriptor, durationMs), plainLyrics, { job -> RunCallback(job, callback) }) { job ->
                    runCatching { song.close() }
                    Log.d(LOG_TAG, "job $job ended")
                    foreground.jobEnded()
                }
            } catch (e: IllegalStateException) {
                song.close()
                throw e
            }
        }

        override fun proceed(job: Long) = checked { jobs.proceed(job) }

        override fun cancel(job: Long) = checked { jobs.cancel(job) }

        // Returns once the sessions are closed, so KJB's unbind right after cannot outrun it.
        override fun closeEngine() = checked { runBlocking { jobs.closeEngine().join() } }

        override fun result(job: Long, part: Int): String? = checked { jobs.result(job)?.let { Wire.part(Wire.encode(it), part) } }

        override fun importModel(name: String, file: ParcelFileDescriptor, bytes: Long, sha256: String): String = checked(file) {
            // The handover beats a download: KJB starts none meanwhile, and one left over stops (its parts stay).
            downloads.cancelAll()
            val reply = ParcelFileDescriptor.AutoCloseInputStream(file).use { input ->
                runBlocking { ModelDownloads.withStore(this@AiService) { store -> ModelImport(store).import(name, input, bytes, sha256) } }
            } ?: ImportReply(ImportReply.Outcome.RETRY, "a download is writing the models")
            Log.i(LOG_TAG, "handover: ${reply.outcome}")
            Wire.encode(reply)
        }
    }

    /**
     * [block] for KJB only: the calling uid is checked first ([CallerCheck]), on the binder thread that carries it. A
     * refused call closes the descriptors it brought ([owned]), so none is left open.
     */
    private inline fun <T> checked(vararg owned: ParcelFileDescriptor, block: () -> T): T {
        try {
            callers.enforce(Binder.getCallingUid())
        } catch (e: SecurityException) {
            owned.forEach { runCatching { it.close() } }
            throw e
        }
        return block()
    }

    /**
     * One download run's progress and end to its caller, by binder. That caller's process dying stops that run (its death
     * link is made before the run starts, and let go when the run ends), never another one.
     */
    private inner class DownloadCallback(private val callback: IAiDownloadCallback) : DownloadEvents {
        /** The run's id, once it has one; until then its caller dying has nothing to stop yet. */
        @Volatile
        var run: Long = 0

        private val death = IBinder.DeathRecipient { downloads.cancel(run) }
        private val linked = try {
            callback.asBinder().linkToDeath(death, 0)
            true
        } catch (_: RemoteException) {
            // Already gone: the run's first report fails and stops it.
            false
        }

        override fun onProgress(done: Long, total: Long) = send { callback.onProgress(done, total) }

        override fun onBeat() = send { callback.onBeat() }

        override fun onEnd(end: Download) {
            send { callback.onEnd(Wire.encode(end)) }
            if (linked) runCatching { callback.asBinder().unlinkToDeath(death, 0) }
        }

        private inline fun send(call: () -> Unit) {
            try {
                call()
            } catch (e: RemoteException) {
                Log.w(LOG_TAG, "KJB is gone (${e.javaClass.simpleName}); stopping its download")
                downloads.cancel(run)
            }
        }
    }

    /** A job's events to KJB, by binder. KJB gone (its process died) stops the job. */
    private inner class RunCallback(private val job: Long, private val callback: IAiRunCallback) : JobEvents {
        override fun onStage(stage: AiStage) = send { callback.onStage(job, stage.name) }

        override fun onNote(line: String) = send { callback.onNote(job, line) }

        override fun onChunk() = send { callback.onChunk(job) }

        // One too long for a oneway call waits in the add-on: KJB reads it in parts (IAiAddon.result).
        override fun onResult(result: AiResult) = send { callback.onResult(job, Wire.encode(result).takeIf { it.length <= Wire.RESULT_PART_CHARS }) }

        override fun onStopped() = send { callback.onStopped(job) }

        override fun onProgress(done: Int, total: Int) = send { callback.onProgress(job, done, total) }

        override fun onBeat() = send { callback.onBeat(job) }

        private inline fun send(call: () -> Unit) {
            try {
                call()
            } catch (e: RemoteException) {
                Log.w(LOG_TAG, "KJB is gone (${e.javaClass.simpleName}); stopping job $job")
                jobs.cancel(job)
            }
        }
    }

    /** The engine on the NPU with this add-on's models; its notes go to KJB's run log. */
    private fun engine(notes: (String) -> Unit): JobEngine {
        val models = checkNotNull(ModelDownloads.store(this)?.models()) { "the models are not in place" }
        val stages = NpuStages(
            models,
            applicationInfo.nativeLibraryDir,
            whisperAssets(),
            ENGINE_THREADS,
            log = notes,
            debug = debug,
            deadline = NativeDeadline(abandoned = jobs::abandoned),
        )
        val lyrics = LyricsFromAudio(
            stages,
            SystemClock::elapsedRealtime,
            log = { Log.d(LOG_TAG, it) },
            notes = notes,
            verbose = Log.isLoggable(LOG_TAG, Log.VERBOSE),
        )
        return object : JobEngine {
            override suspend fun run(
                song: AiSong,
                plainLyrics: String?,
                onStage: (AiStage) -> Unit,
                onProgress: (done: Int, total: Int) -> Unit,
                beforeChunk: suspend () -> Unit,
            ): AiResult = lyrics.run(song, plainLyrics, onStage, onProgress, beforeChunk)

            override fun close() = stages.close()
        }
    }

    /** The Whisper tokenizer's files from the add-on's assets. */
    private fun whisperAssets(): WhisperAssets {
        fun asset(name: String) = assets.open("whisper/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        return WhisperAssets(asset("vocab.json"), asset("added_tokens.json"), asset("generation_config.json"))
    }

    private fun statusOf(models: ModelState?): AddonStatus {
        val store = ModelDownloads.store(this)
        return AddonStatus(
            supported = store != null,
            models = models,
            pins = store?.specs?.associate { it.name to it.sha256 }.orEmpty(),
            runtime = "ONNX Runtime ${BuildConfig.ORT_VERSION}, QNN EP ${BuildConfig.QNN_EP_VERSION}, QNN ${BuildConfig.QNN_RUNTIME_VERSION}, " +
                "KJB AI ${BuildConfig.VERSION_NAME}",
        )
    }

    /** (Re)starts collecting the status for the watchers: each one hears it now, then at each change. */
    @Synchronized
    private fun watch() {
        watching?.cancel()
        watching = scope.launch {
            ModelDownloads.state(this@AiService, downloads.state).collect { models -> broadcast(Wire.encode(statusOf(models))) }
        }
    }

    /** A watcher left: with none left, the status is no longer collected. */
    @Synchronized
    private fun watchedLess() {
        if (watchers.registeredCallbackCount == 0) {
            watching?.cancel()
            watching = null
        }
    }

    private fun broadcast(status: String) = synchronized(watchers) {
        val count = watchers.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    watchers.getBroadcastItem(i).onStatus(status)
                } catch (_: RemoteException) {
                    // A watcher that died: RemoteCallbackList drops it.
                }
            }
        } finally {
            watchers.finishBroadcast()
        }
    }

    private companion object {
        /** The notice while the add-on works (a job, the models' download): its id, and its channel, "AI work". */
        const val NOTICE_ID = 1
        const val NOTICE_CHANNEL = "ai_work"
    }
}
