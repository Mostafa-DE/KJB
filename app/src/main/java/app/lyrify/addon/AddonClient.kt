package app.lyrify.addon

import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.api.AddonStatus
import app.lyrify.ai.api.ImportReply
import app.lyrify.lyrics.LocalTrack
import app.lyrify.models.Download
import app.lyrify.overnight.SongEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** The lowest add-on API (IAiAddon.VERSION) this KJB speaks; an add-on that answers less needs an update. */
const val MIN_ADDON_API: Int = 1

/** What KJB knows about the AI add-on (lyrify-bench handoff/ai-addon-design.md), before a bind and after its handshake. */
sealed interface AddonState {
    /** Not installed: "Get AI". */
    data object Missing : AddonState

    /** Installed, but not signed with KJB's key: never bound. */
    data object Untrusted : AddonState

    /** It answers an older [api] than KJB needs ([MIN_ADDON_API]): "Update the AI add-on". */
    data class Outdated(val api: Int) : AddonState

    /** Installed and trusted, but Android did not bind it or it died at once: tried again later. */
    data object Unreachable : AddonState

    data object Ready : AddonState
}

/** The add-on's process is gone (DeadObjectException): what it was doing ended, and a new bind starts it again. */
class AddonDiedException(cause: Throwable? = null) : Exception("the AI add-on is gone", cause)

/**
 * A song's job in the add-on said nothing for [silentMs] in [stage] (null: before its first stage), longer than any step
 * takes ([RemoteSongEngine]'s watchdog): it was cancelled and the add-on let go of, so a step that never ends ends the
 * add-on's process and frees the NPU. The song fails; the next one binds the add-on anew.
 */
class AddonStalledException(val stage: AiStage?, val silentMs: Long) :
    Exception("the AI add-on said nothing for ${silentMs / 1000} s during ${stage?.name?.lowercase() ?: "its start"}")

/** One song's job as the add-on tells it (IAiRunCallback, as Kotlin). Called on binder threads. */
interface RunEvents {
    /** An AiStage name; a later add-on may name one this KJB does not know. */
    fun onStage(stage: String)

    fun onNote(line: String)

    fun onChunk()

    fun onResult(result: AiResult)

    fun onStopped()

    /** [done] of [total] pieces of the stage the job is in (the first of each stage, then every few seconds at most). */
    fun onProgress(done: Int, total: Int)
}

/** The add-on's model download as it goes (IAiDownloadCallback, as Kotlin). Called on binder threads. */
interface DownloadEvents {
    fun onProgress(done: Long, total: Long)

    fun onEnd(end: Download)
}

/**
 * The add-on's calls (IAiAddon), as Kotlin: each throws [AddonDiedException] once its process is gone, and
 * SecurityException when it refuses KJB.
 */
interface AddonPort {
    fun apiVersion(): Int

    /** Null when the add-on's answer cannot be read. */
    fun status(): AddonStatus?

    /** [listener] hears the status now and at each change, on a binder thread, until the returned handle is closed. */
    fun watchStatus(listener: (AddonStatus) -> Unit): AutoCloseable

    /** Starts a run of the add-on's model download, heard by [events]; its id. */
    fun download(events: DownloadEvents): Long

    /** Stops the download run [run], and no other. */
    fun cancelDownload(run: Long)

    fun deleteModels(): Boolean

    fun discardModel(name: String): Boolean

    /** Starts [song], opened read only here and handed over as a descriptor; IOException when it cannot be opened. */
    @Throws(IOException::class)
    fun run(song: File, durationMs: Long, plainLyrics: String?, events: RunEvents): Long

    fun proceed(job: Long)

    fun cancel(job: Long)

    fun closeEngine()

    /** Hands [file] over as the model file [name], with its SHA-256 (hex). */
    @Throws(IOException::class)
    fun importModel(name: String, file: File, sha256: String): ImportReply
}

/** One bind of the add-on's service: its [port] while it lives; [death] completes when its process dies. [close] unbinds. */
interface AddonBinding : AutoCloseable {
    val port: AddonPort
    val death: Deferred<Unit>

    override fun close()
}

/** What only Android knows: whether the add-on is installed, whether it is KJB's, and the bind itself. */
interface AddonHost {
    fun installed(): Boolean

    /** Signed with the certificate pinned in KJB (and KJB's own). */
    fun trusted(): Boolean

    /** Binds the add-on's service; null when Android refuses or it does not connect in time. */
    suspend fun bind(): AddonBinding?
}

/** A bound add-on after the handshake ([api]), until [close] unbinds it. */
class AddonSession internal constructor(private val binding: AddonBinding, val api: Int) : AutoCloseable {
    val port: AddonPort get() = binding.port

    /** Completes when its process dies. */
    val death: Deferred<Unit> get() = binding.death

    /** False once its process died. */
    val alive: Boolean get() = !binding.death.isCompleted

    /** True once let go of ([close]). */
    @Volatile
    var closed: Boolean = false
        private set

    override fun close() {
        closed = true
        binding.close()
    }
}

/**
 * The add-on for one night's run: its [session], bound again once the last one was let go of (a song's job stalled,
 * [AddonStalledException]). A session whose process died is not bound again here: its calls throw [AddonDiedException]
 * and the run ends, as before.
 */
class AddonLease(private val client: AddonClient, private var current: AddonSession) : AutoCloseable {
    /** The session to use now; [AddonDiedException] when the add-on cannot be bound again. */
    suspend fun session(): AddonSession {
        if (!current.closed) return current
        val again = client.connect() as? Connection.Open ?: throw AddonDiedException()
        current = again.session
        return current
    }

    override fun close() = current.close()
}

/** What [AddonClient.connect] got: a [session] to use, or why there is none. */
sealed interface Connection {
    data class Open(val session: AddonSession) : Connection

    data class Closed(val state: AddonState) : Connection
}

/** The add-on as the AI page shows it: its [state] and, while bound, its last [status]. */
data class AddonView(val state: AddonState, val status: AddonStatus? = null)

/**
 * KJB's way to the add-on: the checks before a bind (installed, signed with KJB's key), the bind, and the handshake
 * (an API at least [minApi]). Pure, over [host].
 */
class AddonClient(private val host: AddonHost, private val minApi: Int = MIN_ADDON_API) {
    /** Without a bind: [AddonState.Missing], [AddonState.Untrusted], or null (installed and trusted: only a bind tells more). */
    fun peek(): AddonState? = when {
        !host.installed() -> AddonState.Missing
        !host.trusted() -> AddonState.Untrusted
        else -> null
    }

    /**
     * Checks the add-on, binds it and asks its API: a session for an add-on that speaks at least [minApi] (a newer one
     * keeps every method KJB knows), else why not. Whatever is not used is unbound at once.
     */
    suspend fun connect(): Connection {
        peek()?.let { return Connection.Closed(it) }
        val binding = host.bind() ?: return Connection.Closed(AddonState.Unreachable)
        val api = try {
            binding.port.apiVersion()
        } catch (_: AddonDiedException) {
            binding.close()
            return Connection.Closed(AddonState.Unreachable)
        } catch (_: SecurityException) {
            // The add-on refused KJB: it does not take KJB's certificate for its own.
            binding.close()
            return Connection.Closed(AddonState.Untrusted)
        }
        if (api < minApi) {
            binding.close()
            return Connection.Closed(AddonState.Outdated(api))
        }
        return Connection.Open(AddonSession(binding, api))
    }

    /**
     * The add-on for the AI page, while collected: bound and watched while it is ready, connected again after [changes]
     * (it was installed, updated or removed), [retryMs] after it was unreachable, and [deathPauseMs] after it died.
     */
    fun views(changes: Flow<Unit>, retryMs: Long = RETRY_MS, deathPauseMs: Long = DEATH_PAUSE_MS): Flow<AddonView> = channelFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        launch { changes.collect { changed.trySend(Unit) } }
        while (true) {
            when (val connection = connect()) {
                is Connection.Closed -> {
                    send(AddonView(connection.state))
                    if (connection.state == AddonState.Unreachable) withTimeoutOrNull(retryMs) { changed.receive() } else changed.receive()
                }
                is Connection.Open -> if (watch(connection.session, changed) { trySend(AddonView(AddonState.Ready, it)) }) delay(deathPauseMs)
            }
        }
    }

    /** Watches [session]'s status until its process dies (true) or the package [changed] (false); then lets it go. */
    private suspend fun watch(session: AddonSession, changed: ReceiveChannel<Unit>, onStatus: (AddonStatus) -> Unit): Boolean = session.use {
        try {
            val watching = session.port.watchStatus(onStatus)
            try {
                select {
                    session.death.onAwait { true }
                    changed.onReceive { false }
                }
            } finally {
                runCatching { watching.close() }
            }
        } catch (_: AddonDiedException) {
            true
        }
    }

    companion object {
        const val RETRY_MS: Long = 30_000
        const val DEATH_PAUSE_MS: Long = 2_000
    }
}

/**
 * The night's [SongEngine] in the add-on, over one [session]: a song runs there as a job whose stages go to the crash
 * guard ([run]'s onStage), whose notes go to the run log ([notes]), and whose Whisper chunks each wait for KJB's
 * beforeChunk (the heat). Cancelled, it tells the add-on and waits up to [stopTimeoutMs] for it to stop after the step it
 * is on, as the engine did in KJB's own process. The add-on dying is [AddonDiedException].
 *
 * Never waited for forever: a job that says nothing (no stage, note, piece of a stage, chunk or end) for longer than any
 * step takes is cancelled, given [STALL_STOP_MS] to stop, and the session let go of: [AddonStalledException]. Without a
 * client, an add-on whose step never ends ends its own process, which frees the NPU. The time KJB itself holds a chunk
 * (the heat) is not the add-on's silence.
 */
class RemoteSongEngine(
    private val session: AddonSession,
    private val notes: (String) -> Unit,
    private val stopTimeoutMs: Long = STOP_TIMEOUT_MS,
) : SongEngine {
    /** One job's events, in the order the add-on sent them. */
    private sealed interface Event {
        data class Stage(val stage: String) : Event

        data class Note(val line: String) : Event

        data object Progress : Event

        data object Chunk : Event

        /** The job's end: its result, or null when it stopped. */
        data class End(val result: AiResult?) : Event

        data object Died : Event
    }

    /** Set once a song's separation moved: the add-on's models are loaded, and each silence gets [STEP_MS]. */
    private var loaded = false

    /** Set by a stall: the session was let go of, and nothing more is asked of the add-on. */
    private var stalled = false

    override suspend fun run(track: LocalTrack, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
        val events = Channel<Event>(Channel.UNLIMITED)
        val heard = object : RunEvents {
            override fun onStage(stage: String) {
                events.trySend(Event.Stage(stage))
            }

            override fun onNote(line: String) {
                events.trySend(Event.Note(line))
            }

            override fun onChunk() {
                events.trySend(Event.Chunk)
            }

            override fun onResult(result: AiResult) {
                events.trySend(Event.End(result))
            }

            override fun onStopped() {
                events.trySend(Event.End(null))
            }

            override fun onProgress(done: Int, total: Int) {
                events.trySend(Event.Progress)
            }
        }
        val port = session.port
        val job = try {
            port.run(File(track.path), track.durationMs, plainLyrics, heard)
        } catch (e: IOException) {
            // The song's file, not the add-on: a failed run of that song, as the engine would report it.
            return AiResult.Failed(e.javaClass.simpleName)
        }
        return coroutineScope {
            val death = launch {
                session.death.await()
                events.trySend(Event.Died)
            }
            try {
                follow(events, job, onStage, beforeChunk)
            } catch (e: CancellationException) {
                // The step under way finishes in the add-on; wait for it to stop, as the engine in KJB's process did.
                withContext(NonCancellable) { stop(job, events, stopTimeoutMs) }
                throw e
            } catch (e: AddonStalledException) {
                // Cancelled, then let go of whether it stopped or not: with no client left, an add-on whose step never
                // ends ends its own process.
                stalled = true
                withContext(NonCancellable) { stop(job, events, STALL_STOP_MS) }
                session.close()
                throw e
            } finally {
                death.cancel()
            }
        }
    }

    /** Asks the add-on to stop [job] and waits up to [timeoutMs] for its end or its death. */
    private suspend fun stop(job: Long, events: ReceiveChannel<Event>, timeoutMs: Long) {
        runCatching { session.port.cancel(job) }
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val event = events.receive()
                if (event is Event.End || event == Event.Died) break
            }
        }
    }

    /**
     * The job's events until its end: stages to the crash guard, notes to the run log, each chunk after beforeChunk. No
     * event within [silenceLimit] is a stall ([AddonStalledException]).
     */
    private suspend fun follow(events: ReceiveChannel<Event>, job: Long, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
        var stage: AiStage? = null
        while (true) {
            val limit = silenceLimit(stage)
            val event = withTimeoutOrNull(limit) { events.receive() } ?: throw AddonStalledException(stage, limit)
            when (event) {
                // A stage a later add-on names is not one this crash guard knows.
                is Event.Stage -> AiStage.entries.firstOrNull { it.name == event.stage }?.let {
                    stage = it
                    onStage(it)
                }
                is Event.Note -> notes(event.line)
                // The separation's first piece: the MDX model is loaded.
                Event.Progress -> if (stage == AiStage.SEPARATE) loaded = true
                Event.Chunk -> {
                    beforeChunk()
                    session.port.proceed(job)
                }
                // Stopped without KJB asking: no result to record.
                is Event.End -> return event.result ?: AiResult.Failed(STOPPED)
                Event.Died -> throw AddonDiedException()
            }
        }
    }

    /**
     * How long the add-on may say nothing in [stage]: [FIRST_LOAD_MS] until this engine's first separation moved (the
     * MDX model loads after the decoder starts), else [STEP_MS].
     */
    private fun silenceLimit(stage: AiStage?): Long =
        if (!loaded && (stage == null || stage == AiStage.DECODE || stage == AiStage.SEPARATE)) FIRST_LOAD_MS else STEP_MS

    /** Releases the add-on's NPU sessions; quiet when it is gone. After a stall nothing is asked: it could wait on the step that never ends. */
    override fun close() {
        if (stalled) return
        try {
            session.port.closeEngine()
        } catch (_: AddonDiedException) {
            // Its process is gone, and the sessions with it.
        }
    }

    companion object {
        /** A step (an MDX segment, a Whisper chunk) takes seconds; creating the MDX session, minutes at most. */
        const val STOP_TIMEOUT_MS: Long = 2 * 60_000

        /**
         * The silence allowed while the MDX model may be loading: its session was created in 50 to 83 s in the add-on
         * spike and in 107 to 164 s in KJB 2.0's nights (2026-11-06 run log), on the fastest supported chip; the graph is
         * prepared on the CPU, slower on the older supported chips, so about 3.5 times the longest seen.
         */
        const val FIRST_LOAD_MS: Long = 10 * 60_000

        /**
         * The silence allowed once the models are loaded. With a piece of each long stage told every few seconds, the
         * longest silent steps are a Whisper chunk (3.6 s at most in KJB 2.0's nights), an MDX segment with its decoding
         * (about 1.5 s) and loading Whisper (3 s) or wav2vec2; this is 50 times the longest, for a hot or slower phone.
         */
        const val STEP_MS: Long = 3 * 60_000

        /** After a stall, how long the add-on gets to stop: its own limit for a stopped step (10 s), then a margin. */
        const val STALL_STOP_MS: Long = 15_000

        private const val STOPPED = "Stopped"
    }
}
