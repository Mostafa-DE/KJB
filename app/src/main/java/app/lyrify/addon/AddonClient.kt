package app.lyrify.addon

import app.lyrify.addon.shared.AddonKeepAlive
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

/**
 * A song's job in the add-on beat, then said nothing for [silentMs] in [stage] (null: before its first stage, or the
 * models' download): the phone froze the add-on's process, as an app freezer does (Samsung's, others), beats and all
 * ([RemoteSongEngine]'s freeze watch, [AddonDownloads.follow]'s). Nothing it must answer was asked of it (it is told one
 * way to drop the job or the download); it was let go of and its service stopped. Not the song's doing: the song is
 * tried again later, and the night's run ends; a download goes on from its parts at its next run.
 */
class AddonFrozenException(val stage: AiStage?, val silentMs: Long) :
    Exception("the AI add-on was frozen: no word for ${silentMs / 1000} s during ${stage?.name?.lowercase() ?: "its start"}")

/**
 * KJB's watch for an add-on the phone froze (AD9), the same for a song's job and a download: [heard] each word of the job
 * and [beat] each beat (binder threads). An add-on's job beats every few seconds from a timer no step blocks, so
 * [silence] returns once one that beat has said nothing for [AddonKeepAlive.FROZEN_MS]; a job that never beat is not
 * watched.
 */
class FreezeWatch {
    private val alive = Channel<Unit>(Channel.CONFLATED)
    private val beating = AtomicBoolean(false)

    fun heard() {
        alive.trySend(Unit)
    }

    fun beat() {
        beating.set(true)
        alive.trySend(Unit)
    }

    /** Waits for [AddonKeepAlive.FROZEN_MS] without a word from a job that beat. */
    suspend fun silence() {
        while (true) {
            val heard = withTimeoutOrNull(AddonKeepAlive.FROZEN_MS) { alive.receive() } != null
            if (!heard && beating.get()) return
        }
    }
}

/** One song's job as the add-on tells it (IAiRunCallback, as Kotlin). Called on binder threads. */
interface RunEvents {
    /** An AiStage name; a later add-on may name one this KJB does not know. */
    fun onStage(stage: String)

    fun onNote(line: String)

    fun onChunk()

    /** The job's end; null when its result was too long for the callback and waits in the add-on ([AddonPort.result]). */
    fun onResult(result: AiResult?)

    fun onStopped()

    /** [done] of [total] pieces of the stage the job is in (the first of each stage, then every few seconds at most). */
    fun onProgress(done: Int, total: Int)

    /** The job is alive: every few seconds while it runs, whatever its step, from a timer of the add-on's own. */
    fun onBeat()
}

/** The add-on's model download as it goes (IAiDownloadCallback, as Kotlin). Called on binder threads. */
interface DownloadEvents {
    fun onProgress(done: Long, total: Long)

    fun onEnd(end: Download)

    /** The run is alive: every few seconds while it runs, whatever it waits on, from a timer of the add-on's own. */
    fun onBeat()
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

    /**
     * Stops the download run [run] KJB gave up on as frozen ([AddonDownloads.follow]), one way: it never waits, and the
     * add-on hears it once it runs again. An add-on without it drops it.
     */
    fun abandonDownload(run: Long)

    fun deleteModels(): Boolean

    fun discardModel(name: String): Boolean

    /** Starts [song], opened read only here and handed over as a descriptor; IOException when it cannot be opened. */
    @Throws(IOException::class)
    fun run(song: File, durationMs: Long, plainLyrics: String?, events: RunEvents): Long

    fun proceed(job: Long)

    fun cancel(job: Long)

    /**
     * Stops the song's job [job] KJB gave up on as frozen ([RemoteSongEngine]) and closes its engine, one way: it never
     * waits, and the add-on hears it once it runs again. An add-on without it drops it.
     */
    fun abandonJob(job: Long)

    /** The result the add-on keeps of [job], which ended with one; null when it keeps none for that job. */
    fun result(job: Long): AiResult?

    fun closeEngine()

    /** Hands [file] over as the model file [name], with its SHA-256 (hex). */
    @Throws(IOException::class)
    fun importModel(name: String, file: File, sha256: String): ImportReply
}

/** One bind of the add-on's service: its [port] while it lives; [death] completes when its process dies. [close] unbinds. */
interface AddonBinding : AutoCloseable {
    val port: AddonPort
    val death: Deferred<Unit>

    /** Starts the add-on's service for the foreground (Context.startForegroundService); false when Android refuses. */
    fun foreground(): Boolean

    /** Stops the service [foreground] started (Context.stopService); quiet when it does not run. */
    fun stopService()

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

    /**
     * Has the add-on run its job or the models' download in its own foreground service, which a phone's app freezer leaves
     * alone (a service KJB binds counts for nothing there): KJB starts it, and KJB's own foreground (its overnight or
     * download service, or its screen) is what lets it. False when the phone refuses; the work then runs without it.
     */
    fun foreground(): Boolean = binding.foreground()

    /**
     * Lets go of an add-on that stopped answering or that the phone froze: unbinds it and stops the service KJB started
     * for its job ([foreground]), so that nothing of KJB's keeps it. Nothing is asked of the add-on itself.
     */
    fun abandon() {
        close()
        binding.stopService()
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
 * (the heat) is not the add-on's silence. Before a stall is declared, the job's end is looked for where it cannot be
 * lost: the end told meanwhile (kept apart from the channel, which a cancelled receive can drop an event from), then the
 * result the add-on keeps ([AddonPort.result]: its callback lost). A result too long for the callback is read there too.
 *
 * The job runs in the add-on's own foreground service, which KJB starts once the add-on took it
 * ([AddonSession.foreground]): a phone's app freezer leaves alone an app at work in the foreground. Should a phone freeze
 * it all the same, the freeze watch ends the song in seconds instead of minutes: the add-on beats every few seconds, from
 * a timer no step blocks, so [FROZEN_MS] without a word from an add-on that beat is a process the phone stopped. Nothing
 * it must answer is asked of it (such a call could wait for as long as it stays frozen): it is told one way to drop the
 * job ([AddonPort.abandonJob]), then let go of and its service stopped ([AddonSession.abandon]): [AddonFrozenException].
 * The beats are not progress: a step that never ends still meets the limits above. An add-on that never beat is not
 * watched.
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

        /** The job's end, its result too long for the callback: it waits in the add-on ([AddonPort.result]). */
        data object Kept : Event

        data object Died : Event

        /** Nothing for [FROZEN_MS] from an add-on that beat: the phone froze it. */
        data object Frozen : Event
    }

    /** Set once a song's separation moved: the add-on's models are loaded, and each silence gets [STEP_MS]. */
    private var loaded = false

    /** Set by a stall or a freeze: the session was let go of, and nothing more is asked of the add-on. */
    private var stalled = false

    override suspend fun run(track: LocalTrack, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
        val events = Channel<Event>(Channel.UNLIMITED)
        // The job's end, also kept apart from the channel: a receive the silence limit cancels can lose what it took.
        val ended = AtomicReference<Event?>(null)
        // Each word from the add-on, beats included, for the freeze watch, which starts with the first beat.
        val watch = FreezeWatch()
        val heard = object : RunEvents {
            override fun onStage(stage: String) = tell(Event.Stage(stage))

            override fun onNote(line: String) = tell(Event.Note(line))

            override fun onChunk() = tell(Event.Chunk)

            override fun onResult(result: AiResult?) = end(if (result == null) Event.Kept else Event.End(result))

            override fun onStopped() = end(Event.End(null))

            private fun end(event: Event) {
                ended.compareAndSet(null, event)
                tell(event)
            }

            override fun onProgress(done: Int, total: Int) = tell(Event.Progress)

            override fun onBeat() = watch.beat()

            private fun tell(event: Event) {
                watch.heard()
                events.trySend(event)
            }
        }
        val port = session.port
        val job = try {
            port.run(File(track.path), track.durationMs, plainLyrics, heard)
        } catch (e: IOException) {
            // The song's file, not the add-on: a failed run of that song, as the engine would report it.
            return AiResult.Failed(e.javaClass.simpleName)
        }
        if (!session.foreground()) notes("the phone did not let the AI add-on run as a foreground service; it runs without one")
        return coroutineScope {
            val death = launch {
                session.death.await()
                events.trySend(Event.Died)
            }
            val frozen = AtomicBoolean(false)
            val watching = launch {
                watch.silence()
                frozen.set(true)
                events.trySend(Event.Frozen)
            }
            try {
                follow(events, ended, frozen, job, onStage, beforeChunk)
            } catch (e: CancellationException) {
                // The step under way finishes in the add-on; wait for it to stop, as the engine in KJB's process did.
                withContext(NonCancellable) { stop(job, events, ended, stopTimeoutMs) }
                throw e
            } catch (e: AddonStalledException) {
                // Cancelled, then let go of whether it stopped or not (unbound, the service KJB started for the job
                // stopped): with no client left, an add-on whose step never ends ends its own process.
                stalled = true
                withContext(NonCancellable) { stop(job, events, ended, STALL_STOP_MS) }
                session.abandon()
                throw e
            } catch (e: AddonFrozenException) {
                // Nothing it must answer: told one way to drop the job, which the add-on hears once it runs again,
                // even while KJB's AI page binds it and Android keeps its service; it then stops the job and closes its
                // engine. An add-on without the call stops when nothing binds it.
                stalled = true
                runCatching { session.port.abandonJob(job) }
                session.abandon()
                throw e
            } finally {
                death.cancel()
                watching.cancel()
            }
        }
    }

    /** Asks the add-on to stop [job] and waits up to [timeoutMs] for its end or its death. */
    private suspend fun stop(job: Long, events: ReceiveChannel<Event>, ended: AtomicReference<Event?>, timeoutMs: Long) {
        runCatching { session.port.cancel(job) }
        withTimeoutOrNull(timeoutMs) {
            while (ended.get() == null) {
                val event = events.receive()
                if (event is Event.End || event == Event.Kept || event == Event.Died) break
            }
        }
    }

    /**
     * The job's events until its end: stages to the crash guard, notes to the run log, each chunk after beforeChunk. No
     * event within [silenceLimit] is a stall ([AddonStalledException]); the freeze watch's word ([frozen]), a freeze
     * ([AddonFrozenException]).
     */
    private suspend fun follow(
        events: ReceiveChannel<Event>,
        ended: AtomicReference<Event?>,
        frozen: AtomicBoolean,
        job: Long,
        onStage: (AiStage) -> Unit,
        beforeChunk: suspend () -> Unit,
    ): AiResult {
        var stage: AiStage? = null
        while (true) {
            val limit = silenceLimit(stage)
            val event = withTimeoutOrNull(limit) { events.receive() } ?: afterSilence(job, ended, stage, limit)
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
                    // Frozen while KJB held the chunk (the heat): nothing goes into a process the phone stopped.
                    if (frozen.get()) throw AddonFrozenException(stage, FROZEN_MS)
                    session.port.proceed(job)
                }
                // Stopped without KJB asking: no result to record.
                is Event.End -> return event.result ?: AiResult.Failed(STOPPED)
                Event.Kept -> return session.port.result(job) ?: AiResult.Failed(LOST)
                Event.Died -> throw AddonDiedException()
                // An end told meanwhile follows in the channel: the watch's receive, cancelled by its limit, lost the word.
                Event.Frozen -> if (ended.get() == null) throw AddonFrozenException(stage, FROZEN_MS)
            }
        }
    }

    /**
     * Nothing heard for [limit] in [stage]: the end told meanwhile (a receive the limit cancelled can lose the event it
     * took), else the result the add-on keeps (its callback lost), else a stall.
     */
    private fun afterSilence(job: Long, ended: AtomicReference<Event?>, stage: AiStage?, limit: Long): Event =
        ended.get() ?: session.port.result(job)?.let { Event.End(it) } ?: throw AddonStalledException(stage, limit)

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

        /**
         * The silence that is a freeze once the add-on beats: 6 of its beats (every 5 s) missed. A process the phone froze
         * says nothing at all; one at work on a long step still beats.
         */
        const val FROZEN_MS: Long = AddonKeepAlive.FROZEN_MS

        /** After a stall, how long the add-on gets to stop: its own limit for a stopped step (10 s), then a margin. */
        const val STALL_STOP_MS: Long = 15_000

        private const val STOPPED = "Stopped"

        /** The add-on said its result waits for KJB, then kept none: not the song's verdict, tried again later. */
        private const val LOST = "ResultLost"
    }
}
