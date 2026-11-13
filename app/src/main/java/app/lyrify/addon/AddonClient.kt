package app.lyrify.addon

import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.api.AddonStatus
import app.lyrify.ai.api.ImportReply
import app.lyrify.lyrics.LocalTrack
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

/** One song's job as the add-on tells it (IAiRunCallback, as Kotlin). Called on binder threads. */
interface RunEvents {
    /** An AiStage name; a later add-on may name one this KJB does not know. */
    fun onStage(stage: String)

    fun onNote(line: String)

    fun onChunk()

    fun onResult(result: AiResult)

    fun onStopped()
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

    fun startDownload(restart: Boolean)

    fun cancelDownload()

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

    override fun close() = binding.close()
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

        data object Chunk : Event

        /** The job's end: its result, or null when it stopped. */
        data class End(val result: AiResult?) : Event

        data object Died : Event
    }

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
                withContext(NonCancellable) {
                    runCatching { port.cancel(job) }
                    withTimeoutOrNull(stopTimeoutMs) {
                        while (true) {
                            val event = events.receive()
                            if (event is Event.End || event == Event.Died) break
                        }
                    }
                }
                throw e
            } finally {
                death.cancel()
            }
        }
    }

    /** The job's events until its end: stages to the crash guard, notes to the run log, each chunk after beforeChunk. */
    private suspend fun follow(events: ReceiveChannel<Event>, job: Long, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
        while (true) {
            when (val event = events.receive()) {
                // A stage a later add-on names is not one this crash guard knows.
                is Event.Stage -> AiStage.entries.firstOrNull { it.name == event.stage }?.let(onStage)
                is Event.Note -> notes(event.line)
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

    /** Releases the add-on's NPU sessions; quiet when it is gone. */
    override fun close() {
        try {
            session.port.closeEngine()
        } catch (_: AddonDiedException) {
            // Its process is gone, and the sessions with it.
        }
    }

    companion object {
        /** A step (an MDX segment, a Whisper chunk) takes seconds; creating the MDX session, about 30 s. */
        const val STOP_TIMEOUT_MS: Long = 2 * 60_000

        private const val STOPPED = "Stopped"
    }
}
