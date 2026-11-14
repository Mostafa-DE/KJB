package app.lyrify.ai

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import app.lyrify.addon.shared.CallerCheck
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.coroutines.CoroutineContext

/** Logcat's tag for the add-on. Its lines carry counts, stages and exception classes, never a song, a path or lyrics. */
const val LOG_TAG: String = "KJB-AI"

/** How the add-on's model downloads introduce themselves. */
val USER_AGENT: String = "KJB-AI/${BuildConfig.VERSION_NAME} ( https://github.com/Mostafa-DE/Lyrify )"

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
}

/** The AI for songs ([LyricsFromAudio] on [NpuStages]): its models stay loaded from the first song until [close]. */
interface JobEngine : AutoCloseable {
    suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult
}

/**
 * The songs KJB sends, one at a time, on [context] in [scope]. The engine is opened ([openEngine], with where its notes
 * go) for the first song and kept for the next ones until [closeEngine]: one night's run loads the models once. Before
 * each Whisper chunk a job tells KJB and waits for [proceed] (KJB holds it while the phone is too warm). [cancel] stops a
 * job after the step it is on. When the last client leaves, [shutDown] lets go of the NPU at once (or as the step under
 * way ends), whatever happens to the service's scope after.
 */
class AddonJobs(
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val openEngine: (notes: (String) -> Unit) -> JobEngine,
) {
    private val lock = Any()
    private var engine: JobEngine? = null
    private var running: Running? = null
    private var nextId = 1L

    /** Set by [shutDown] while a job is mid-step: the engine closes as that job ends. */
    private var closeAfterJob = false

    /** Where the engine's notes go: the job running now. */
    @Volatile
    private var notesTo: JobEvents? = null

    /** The job under way, and the chunk it waits at for [proceed]. */
    private class Running(val id: Long) {
        lateinit var job: Job
        var waiting: CompletableDeferred<Unit>? = null
    }

    /**
     * Starts [song]; [events] makes the job's events from its id, [release] lets go of the song once the job has ended.
     * IllegalStateException while another job runs.
     */
    fun start(song: AiSong, plainLyrics: String?, events: (Long) -> JobEvents, release: (Long) -> Unit): Long = synchronized(lock) {
        check(running == null) { "a job is already running" }
        val run = Running(nextId++)
        val heard = events(run.id)
        // ATOMIC: the body runs, and its finally lets go of the song, even when the scope goes before it was dispatched.
        run.job = scope.launch(context, start = CoroutineStart.ATOMIC) {
            try {
                val result = try {
                    val engine = engine()
                    notesTo = heard
                    engine.run(song, plainLyrics, heard::onStage) { atChunk(run, heard) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The engine did not open (its models are gone): a failed run, as one the engine itself reports.
                    AiResult.Failed(e.javaClass.simpleName)
                }
                heard.onResult(result)
            } catch (e: CancellationException) {
                heard.onStopped()
                throw e
            } finally {
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

    /** Before a Whisper chunk: tells KJB and waits for its go-ahead (or for the job to be cancelled). */
    private suspend fun atChunk(run: Running, heard: JobEvents) {
        val gate = CompletableDeferred<Unit>()
        synchronized(lock) { run.waiting = gate }
        heard.onChunk()
        gate.await()
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
        synchronized(lock) { running?.takeIf { it.id == job } }?.job?.cancel()
    }

    /** Stops a job under way, then closes the engine; the returned job ends once it is closed. */
    fun closeEngine(): Job = scope.launch(context) {
        synchronized(lock) { running }?.job?.cancelAndJoin()
        // A job started meanwhile keeps the engine; it closes with the next call.
        synchronized(lock) { if (running == null) closeNow() }
    }

    /**
     * The last client left (onUnbind, onDestroy): the NPU sessions close now, on the caller's thread, with nothing left
     * to a scope the service is about to cancel. A job mid-step is cancelled and closes them as it ends (a native step
     * cannot be cut short; closing under it would free what it uses).
     */
    fun shutDown() {
        synchronized(lock) {
            val run = running
            if (run == null) {
                closeNow()
            } else {
                closeAfterJob = true
                run.job.cancel()
            }
        }
    }

    /** Releases the engine's sessions. Called holding [lock]. */
    private fun closeNow() {
        engine?.let { runCatching { it.close() } }
        engine = null
    }
}

/**
 * The add-on's one service, bound by KJB only ([CallerCheck] on every call, behind the signature permission): the AI
 * ([AddonJobs]), the model download ([ModelDownloads]) and the handover of KJB 2.0's models. KJB binds it while the AI
 * works, from its own foreground service, and while its AI page shows; the add-on has no foreground service of its own
 * for the AI (a bound service runs at least at its client's priority). Nothing here touches ONNX Runtime until a song's
 * engine opens, so the QNN EP registers its library path before any HTP library loads in this process ([QnnEp]).
 */
class AiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val callers by lazy {
        CallerCheck(
            packageName,
            BuildConfig.KJB_PACKAGE,
            BuildConfig.KJB_CERT_SHA256,
            packagesFor = packageManager::getPackagesForUid,
            hasCert = { uid, sha -> packageManager.hasSigningCertificate(uid, sha, PackageManager.CERT_INPUT_SHA256) },
        )
    }

    private val jobs by lazy { AddonJobs(scope, Dispatchers.Default, ::engine) }

    /** The model download, run while KJB keeps the add-on bound from its foreground service (which shows its notification). */
    private val downloads by lazy { DownloadRun(scope) { progress -> ModelDownloads.download(this, progress) } }

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

    /** KJB let go of the add-on: what it started stops, and the NPU sessions close now. */
    override fun onUnbind(intent: Intent): Boolean {
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

        override fun onResult(result: AiResult) = send { callback.onResult(job, Wire.encode(result)) }

        override fun onStopped() = send { callback.onStopped(job) }

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
        val stages = NpuStages(models, applicationInfo.nativeLibraryDir, whisperAssets(), ENGINE_THREADS, log = notes)
        val lyrics = LyricsFromAudio(
            stages,
            SystemClock::elapsedRealtime,
            log = { Log.d(LOG_TAG, it) },
            notes = notes,
            verbose = Log.isLoggable(LOG_TAG, Log.VERBOSE),
        )
        return object : JobEngine {
            override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult =
                lyrics.run(song, plainLyrics, onStage, beforeChunk)

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
}
