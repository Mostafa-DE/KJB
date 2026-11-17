package app.lyrify.addon

import android.content.Context
import androidx.work.WorkInfo
import app.lyrify.addon.shared.AddonKeepAlive
import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.AiStats
import app.lyrify.ai.api.AddonStatus
import app.lyrify.ai.api.ImportReply
import app.lyrify.lyrics.LocalTrack
import app.lyrify.models.Download
import app.lyrify.models.DownloadPhase
import app.lyrify.models.ModelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** KJB's side of the add-on, against a fake add-on: the checks before a bind, the handshake, and one song's run. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonClientTest {
    /** One add-on process: its API, its scripted answers, and what KJB asked of it. */
    private class FakePort(private val api: Int = 1) : AddonPort {
        var dead = false
        val calls = mutableListOf<String>()
        var events: RunEvents? = null
        var status: AddonStatus = AddonStatus(true, ModelState.Ready(980))
        var statusListener: ((AddonStatus) -> Unit)? = null

        private fun call(name: String) {
            if (dead) throw AddonDiedException()
            calls += name
        }

        override fun apiVersion(): Int = api.also { call("apiVersion") }

        override fun status(): AddonStatus = status.also { call("status") }

        override fun watchStatus(listener: (AddonStatus) -> Unit): AutoCloseable {
            call("watchStatus")
            statusListener = listener
            listener(status)
            return AutoCloseable { statusListener = null }
        }

        var downloadEvents: DownloadEvents? = null

        override fun download(events: DownloadEvents): Long {
            call("download")
            downloadEvents = events
            return 21
        }

        override fun cancelDownload(run: Long) = call("cancelDownload $run")

        override fun abandonDownload(run: Long) = call("abandonDownload $run")

        override fun deleteModels(): Boolean = true.also { call("deleteModels") }

        override fun discardModel(name: String): Boolean = true.also { call("discardModel $name") }

        override fun run(song: File, durationMs: Long, plainLyrics: String?, events: RunEvents): Long {
            call("run ${song.name} $durationMs ${plainLyrics != null}")
            this.events = events
            return 7
        }

        override fun proceed(job: Long) = call("proceed $job")

        override fun cancel(job: Long) = call("cancel $job")

        override fun abandonJob(job: Long) = call("abandonJob $job")

        /** The result the add-on keeps of the job that ended last. */
        var kept: AiResult? = null

        override fun result(job: Long): AiResult? = kept.also { call("result $job") }

        override fun closeEngine() = call("closeEngine")

        override fun importModel(name: String, file: File, sha256: String): ImportReply = ImportReply(ImportReply.Outcome.IMPORTED).also { call("import $name") }
    }

    private class FakeBinding(override val port: FakePort) : AddonBinding {
        val died = CompletableDeferred<Unit>()
        var closed = false
        override val death: Deferred<Unit> get() = died

        /** The phone refuses the add-on's foreground service; [stopped]: KJB stopped the service it started. */
        var refusesForeground = false
        var stopped = false

        // Not a call into the add-on: KJB asks Android. Listed with the calls for their order.
        override fun foreground(): Boolean {
            port.calls += "foreground"
            return !refusesForeground
        }

        override fun stopService() {
            stopped = true
        }

        /** The add-on's process dies: Android tells the binding, and every call throws. */
        fun kill() {
            port.dead = true
            died.complete(Unit)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeHost(var installed: Boolean = true, var trusted: Boolean = true, var api: Int = 1, var refuses: Boolean = false) : AddonHost {
        val bindings = mutableListOf<FakeBinding>()

        override fun installed(): Boolean = installed

        override fun trusted(): Boolean = trusted

        override suspend fun bind(): AddonBinding? = if (refuses) null else FakeBinding(FakePort(api)).also { bindings += it }
    }

    private val track = LocalTrack("/music/Placeholder.mp3", "Placeholder", "Artist", null, 200_000)
    private val stats = AiStats(200.0, 0.5, 80.0, 2, 120.0, 10, 9, emptyMap())
    private val transcribed = AiResult.Transcribed("[00:01.000]placeholder\n", stats)

    // Before the bind, and the handshake

    @Test
    fun `no add-on, nothing is bound and KJB offers to get it`() = runTest {
        val host = FakeHost(installed = false)

        assertEquals(Connection.Closed(AddonState.Missing), AddonClient(host).connect())
        assertEquals(AddonState.Missing, AddonClient(host).peek())
        assertTrue(host.bindings.isEmpty())
    }

    @Test
    fun `an add-on not signed with KJB's key is never bound`() = runTest {
        val host = FakeHost(trusted = false)

        assertEquals(Connection.Closed(AddonState.Untrusted), AddonClient(host).connect())
        assertEquals(AddonState.Untrusted, AddonClient(host).peek())
        assertTrue(host.bindings.isEmpty())
    }

    @Test
    fun `an add-on that speaks an older API asks for an update and is let go`() = runTest {
        val host = FakeHost(api = 0)

        assertEquals(Connection.Closed(AddonState.Outdated(0)), AddonClient(host, minApi = 1).connect())
        assertTrue(host.bindings.single().closed)
    }

    @Test
    fun `a newer add-on keeps the methods KJB knows, so it is ready`() = runTest {
        val connection = AddonClient(FakeHost(api = 3), minApi = 1).connect()

        assertTrue(connection is Connection.Open)
        assertEquals(3, (connection as Connection.Open).session.api)
    }

    @Test
    fun `a bind Android refuses, or an add-on that dies during the handshake, is unreachable for now`() = runTest {
        assertEquals(Connection.Closed(AddonState.Unreachable), AddonClient(FakeHost(refuses = true)).connect())

        val host = object : AddonHost {
            val binding = FakeBinding(FakePort()).apply { kill() }
            override fun installed() = true
            override fun trusted() = true
            override suspend fun bind(): AddonBinding = binding
        }
        assertEquals(Connection.Closed(AddonState.Unreachable), AddonClient(host).connect())
        assertTrue(host.binding.closed)
    }

    @Test
    fun `ready, an installed, trusted add-on of a known API is bound once`() = runTest {
        val host = FakeHost()

        val connection = AddonClient(host).connect()

        assertTrue(connection is Connection.Open)
        assertNull(AddonClient(host).peek()) // installed and trusted: only a bind tells more
        assertEquals(listOf("apiVersion"), host.bindings.single().port.calls)
        (connection as Connection.Open).session.close()
        assertTrue(host.bindings.single().closed)
    }

    // One song

    private suspend fun session(host: FakeHost = FakeHost()): AddonSession = (AddonClient(host).connect() as Connection.Open).session

    @Test
    fun `running, stages, notes, a chunk after the go-ahead, then the result`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val port = host.bindings.single().port
        val stages = mutableListOf<AiStage>()
        val notes = mutableListOf<String>()
        var chunks = 0
        val engine = RemoteSongEngine(session, notes::add)

        val result = async { engine.run(track, "placeholder lyrics", stages::add) { chunks++ } }
        runCurrent()
        assertEquals(listOf("run Placeholder.mp3 200000 true", "foreground"), port.calls.takeLast(2))
        val events = checkNotNull(port.events)
        events.onStage("DECODE")
        events.onNote("decode: 5 ms")
        events.onStage("TRANSCRIBE")
        events.onChunk()
        runCurrent()
        assertEquals(1, chunks)
        assertEquals("proceed 7", port.calls.last())
        events.onStage("A LATER STAGE") // one this KJB does not know is not the crash guard's
        events.onResult(transcribed)

        assertEquals(transcribed, result.await())
        assertEquals(listOf(AiStage.DECODE, AiStage.TRANSCRIBE), stages)
        assertEquals(listOf("decode: 5 ms"), notes)
        engine.close()
        assertEquals("closeEngine", port.calls.last())
    }

    @Test
    fun `cancel, the add-on is told, and the run ends once it has stopped`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val port = host.bindings.single().port
        val run = async { engine.run(track, null, {}) { awaitCancellation() } }
        runCurrent()
        port.events!!.onChunk()
        runCurrent()

        run.cancel()
        runCurrent()
        assertEquals("cancel 7", port.calls.last())
        assertFalse("waits for the add-on to stop", run.isCompleted)
        port.events!!.onStopped()
        runCurrent()

        assertTrue(run.isCancelled)
    }

    @Test
    fun `cancel, an add-on that never says it stopped is not waited for forever`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val engine = RemoteSongEngine(session, notes = {}, stopTimeoutMs = 60_000)
        val run = async { engine.run(track, null, {}) {} }
        runCurrent()

        run.cancel()
        advanceTimeBy(60_001)
        runCurrent()

        assertTrue(run.isCancelled)
    }

    @Test
    fun `the add-on dying mid-run is a clean error, and a new bind runs the song again`() = runTest {
        val host = FakeHost()
        val client = AddonClient(host)
        val first = (client.connect() as Connection.Open).session
        val engine = RemoteSongEngine(first, notes = {})
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        host.bindings.single().port.events!!.onStage("SEPARATE")

        host.bindings.single().kill() // DeadObjectException from here on
        runCurrent()

        val error = run.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonDiedException)
        assertFalse(first.alive)
        engine.close() // closing a dead engine is quiet
        first.close()

        // The retry: bound again, the song runs to its end.
        val second = (client.connect() as Connection.Open).session
        val again = RemoteSongEngine(second, notes = {})
        val result = async { again.run(track, null, {}) {} }
        runCurrent()
        host.bindings.last().port.events!!.onResult(transcribed)
        assertEquals(transcribed, result.await())
        assertEquals(2, host.bindings.size)
    }

    @Test
    fun `an add-on already dead when the song starts fails the same clean way`() = runTest {
        val host = FakeHost()
        val session = session(host)
        host.bindings.single().kill()

        val error = runCatching { RemoteSongEngine(session, notes = {}).run(track, null, {}) {} }.exceptionOrNull()

        assertTrue(error.toString(), error is AddonDiedException)
    }

    @Test
    fun `a song file that cannot be opened fails that song, not the add-on`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val port = object : AddonPort by host.bindings.single().port {
            override fun run(song: File, durationMs: Long, plainLyrics: String?, events: RunEvents): Long = throw java.io.FileNotFoundException("gone")
        }
        val binding = object : AddonBinding {
            override val port: AddonPort = port
            override val death: Deferred<Unit> = CompletableDeferred()
            override fun foreground() = true
            override fun stopService() {}
            override fun close() {}
        }

        val result = RemoteSongEngine(AddonSession(binding, 1), notes = {}).run(track, null, {}) {}

        assertEquals(AiResult.Failed("FileNotFoundException"), result)
        session.close()
    }

    // The watchdog: an add-on that goes quiet mid-song is never waited for forever

    @Test
    fun `an add-on silent for 3 minutes once its models are loaded is cancelled and let go of, and the song fails`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val binding = host.bindings.single()
        val engine = RemoteSongEngine(session, notes = {})
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        val events = checkNotNull(binding.port.events)
        events.onStage("DECODE")
        events.onStage("SEPARATE")
        events.onProgress(1, 40) // the MDX model is loaded: its first segment ran
        runCurrent()

        advanceTimeBy(RemoteSongEngine.STEP_MS - 1)
        runCurrent()
        assertFalse("silent for less than a step's limit", run.isCompleted || "cancel 7" in binding.port.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("cancel 7", binding.port.calls.last())
        assertFalse("waits a moment for the job to stop", binding.closed)

        advanceTimeBy(RemoteSongEngine.STALL_STOP_MS)
        runCurrent()

        val error = run.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonStalledException && error.stage == AiStage.SEPARATE && error.silentMs == RemoteSongEngine.STEP_MS)
        assertTrue("let go of", binding.closed && session.closed)
        assertTrue("and the service KJB started for the job stopped", binding.stopped)
        engine.close()
        assertFalse("nothing more is asked of it", "closeEngine" in binding.port.calls)
    }

    @Test
    fun `KJB waits 10 minutes for a first load and 3 for a step, past the add-on's NPU deadlines of 8 and 2`() {
        assertEquals(10 * 60_000L, RemoteSongEngine.FIRST_LOAD_MS)
        assertEquals(3 * 60_000L, RemoteSongEngine.STEP_MS)
        // The add-on's own (ai-addon NpuStages.MDX_CREATE_MS and NPU_STEP_MS, pinned in its NpuContractTest): its NPU busy
        // answer comes first.
        assertTrue(RemoteSongEngine.FIRST_LOAD_MS > 8 * 60_000L)
        assertTrue(RemoteSongEngine.STEP_MS > 2 * 60_000L)
    }

    @Test
    fun `before the models have loaded the add-on gets the first load's longer limit`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.single()
        val port = binding.port
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        port.events!!.onStage("DECODE")
        port.events!!.onStage("SEPARATE")
        runCurrent()

        advanceTimeBy(RemoteSongEngine.STEP_MS + 1)
        runCurrent()
        assertFalse("creating the MDX session takes minutes", "cancel 7" in port.calls)
        advanceTimeBy(RemoteSongEngine.FIRST_LOAD_MS - RemoteSongEngine.STEP_MS)
        runCurrent()
        assertEquals("cancel 7", port.calls.last())
        port.events!!.onStopped() // the job did stop: the add-on is let go of all the same
        runCurrent()

        val error = run.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonStalledException && error.stage == AiStage.SEPARATE && error.silentMs == RemoteSongEngine.FIRST_LOAD_MS)
        assertTrue(binding.closed)
    }

    @Test
    fun `the pieces of a long stage, and KJB's own hold before a chunk, are no silence`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val port = host.bindings.single().port
        val run = async { runCatching { engine.run(track, null, {}) { delay(30 * 60_000L) } } } // the phone is warm for 30 minutes
        runCurrent()
        port.events!!.onStage("SEPARATE")
        port.events!!.onProgress(1, 200)
        runCurrent()
        // A 15-minute separation, a piece told every few minutes.
        for (k in 2..6) {
            advanceTimeBy(RemoteSongEngine.STEP_MS - 1_000)
            port.events!!.onProgress(k, 200)
            runCurrent()
        }
        port.events!!.onStage("TRANSCRIBE")
        port.events!!.onChunk()
        runCurrent()

        advanceTimeBy(30 * 60_000L + 1)
        runCurrent()
        assertEquals("proceed 7", port.calls.last())
        port.events!!.onResult(transcribed)
        runCurrent()

        assertEquals(transcribed, run.await().getOrThrow())
        assertFalse("cancel 7" in port.calls)
    }

    @Test
    fun `a result that arrives just as the silence limit runs out is the song's end, not a stall`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.single()
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        val events = checkNotNull(binding.port.events)
        events.onStage("SEPARATE")
        events.onProgress(1, 40)
        runCurrent()

        advanceTimeBy(RemoteSongEngine.STEP_MS) // the limit is now: its timeout is queued, not yet run
        events.onResult(transcribed) // and the result reaches the receive at the same moment, after it
        runCurrent()

        assertEquals(transcribed, run.await().getOrThrow())
        assertFalse("not cancelled", "cancel 7" in binding.port.calls)
        assertFalse("not let go of", binding.closed)
    }

    @Test
    fun `a result whose callback was lost is read from the add-on before a stall is declared`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.single()
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        binding.port.events!!.onStage("TRANSCRIBE")
        runCurrent()
        binding.port.kept = transcribed // the job ended, its onResult never arrived

        advanceTimeBy(RemoteSongEngine.STEP_MS + 1)
        runCurrent()

        assertEquals(transcribed, run.await().getOrThrow())
        assertEquals("result 7", binding.port.calls.last())
        assertFalse(binding.closed)
    }

    @Test
    fun `a result too long for the callback is read from the add-on`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val port = host.bindings.single().port
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        port.kept = transcribed

        port.events!!.onResult(null)
        runCurrent()

        assertEquals(transcribed, run.await().getOrThrow())
        assertEquals("result 7", port.calls.last())
    }

    @Test
    fun `after a stall the night's next song binds the add-on anew, a dead one is not bound again`() = runTest {
        val host = FakeHost()
        val client = AddonClient(host)
        val lease = AddonLease(client, (client.connect() as Connection.Open).session)
        val first = lease.session()
        assertTrue("kept while it is in use", first === lease.session())

        first.close() // what a stall does
        val second = lease.session()

        assertFalse(first === second)
        assertEquals(2, host.bindings.size)
        host.bindings.last().kill()
        assertTrue("its calls end the run, as before", second === lease.session())
        second.close()
        host.refuses = true
        assertTrue(runCatching { lease.session() }.exceptionOrNull() is AddonDiedException)
        lease.close()
        assertTrue(host.bindings.all { it.closed })
    }

    // The add-on's own foreground service, and the freeze watch: a phone's app freezer stops a process, beats and all

    @Test
    fun `KJB binds an add-on at KJB's own priority, never below it`() {
        assertEquals(Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, ADDON_BIND_FLAGS)
        for (lowers in listOf(Context.BIND_WAIVE_PRIORITY, Context.BIND_NOT_FOREGROUND, Context.BIND_NOT_PERCEPTIBLE, Context.BIND_ALLOW_OOM_MANAGEMENT)) {
            assertEquals(0, ADDON_BIND_FLAGS and lowers)
        }
    }

    @Test
    fun `the job runs in the add-on's own foreground service, which KJB starts once the add-on took the job`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val port = host.bindings.single().port
        val run = async { engine.run(track, null, {}) {} }
        runCurrent()

        assertEquals(listOf("apiVersion", "run Placeholder.mp3 200000 false", "foreground"), port.calls)
        port.events!!.onResult(transcribed)
        assertEquals(transcribed, run.await())
    }

    @Test
    fun `a phone that refuses the add-on's foreground service still runs the song, and the run log says so`() = runTest {
        val host = FakeHost()
        val session = session(host)
        host.bindings.single().refusesForeground = true
        val notes = mutableListOf<String>()
        val engine = RemoteSongEngine(session, notes::add)
        val run = async { engine.run(track, null, {}) {} }
        runCurrent()

        host.bindings.single().port.events!!.onResult(transcribed)

        assertEquals(transcribed, run.await())
        assertTrue(notes.toString(), notes.any { "foreground service" in it })
    }

    @Test
    fun `silent for 30 s once it beats, the add-on is frozen, its job dropped one way, let go of and its service stopped`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val binding = host.bindings.single()
        val engine = RemoteSongEngine(session, notes = {})
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        val events = checkNotNull(binding.port.events)
        events.onBeat()
        events.onStage("DECODE")
        events.onStage("SEPARATE")
        runCurrent()
        advanceTimeBy(BEAT_MS)
        events.onBeat()
        runCurrent()

        advanceTimeBy(RemoteSongEngine.FROZEN_MS - 1)
        runCurrent()
        assertFalse("silent for less than 30 s", run.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        val error = run.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonFrozenException && error.stage == AiStage.SEPARATE && error.silentMs == RemoteSongEngine.FROZEN_MS)
        assertTrue("let go of", binding.closed && session.closed)
        assertTrue("the service KJB started is stopped", binding.stopped)
        // A call the add-on must answer could wait for as long as the phone keeps it frozen: only the one-way word it
        // hears once it runs again, which also closes its engine.
        assertEquals(listOf("apiVersion", "run Placeholder.mp3 200000 false", "foreground", "abandonJob 7"), binding.port.calls)
        engine.close()
        assertFalse("closeEngine" in binding.port.calls)
    }

    @Test
    fun `a frozen job is dropped one way even while the AI page binds the add-on, and the next song's job runs`() = runTest {
        val host = FakeHost()
        // The AI page shows: its own binding keeps the add-on's service, which KJB's letting go then does not destroy.
        val page = launch { AddonClient(host).views(MutableSharedFlow(), retryMs = 30_000, deathPauseMs = 1_000).collect {} }
        runCurrent()
        val screen = host.bindings.single()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.last()
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        binding.port.events!!.onBeat()
        binding.port.events!!.onStage("TRANSCRIBE")
        runCurrent()
        advanceTimeBy(RemoteSongEngine.FROZEN_MS + 1)
        runCurrent()

        assertTrue(run.await().exceptionOrNull() is AddonFrozenException)
        // The add-on stops the job and closes its engine once it runs again, with no onUnbind.
        assertEquals("abandonJob 7", binding.port.calls.last())
        assertTrue("the worker's binding let go of and its service stopped", binding.closed && binding.stopped)
        assertFalse("the page's binding stays", screen.closed)

        // The next night's run binds anew, and its song runs as a job of its own.
        val next = RemoteSongEngine(session(host), notes = {})
        val song = async { next.run(track, null, {}) {} }
        runCurrent()
        assertEquals(listOf("apiVersion", "run Placeholder.mp3 200000 false", "foreground"), host.bindings.last().port.calls)
        host.bindings.last().port.events!!.onResult(transcribed)
        assertEquals(transcribed, song.await())
        page.cancel()
    }

    @Test
    fun `a freeze is its own reason, not the NPU busy the add-on tells once the phone lets it run again`() = runTest {
        // Phone session 10: the add-on, frozen for minutes, woke and ended its MDX step as NpuBusyException.
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.single()
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        binding.port.events!!.onBeat()
        binding.port.events!!.onStage("SEPARATE")
        runCurrent()

        advanceTimeBy(RemoteSongEngine.FROZEN_MS + 1)
        runCurrent()
        binding.port.events!!.onResult(AiResult.Failed("NpuBusyException: MDX session: no answer from the NPU within 480 s", npu = true))
        runCurrent()

        val error = run.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonFrozenException && error.stage == AiStage.SEPARATE)
    }

    @Test
    fun `beats keep a first model load of minutes from looking frozen, and are no progress for the first load's limit`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.single()
        val port = binding.port
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        port.events!!.onBeat()
        port.events!!.onStage("DECODE")
        port.events!!.onStage("SEPARATE")
        runCurrent()

        // Creating the MDX session: 9 minutes of beats and nothing else.
        repeat(9 * 60_000 / BEAT_MS.toInt()) {
            advanceTimeBy(BEAT_MS)
            port.events!!.onBeat()
            runCurrent()
        }
        assertFalse(run.isCompleted || "cancel 7" in port.calls)
        repeat(60_000 / BEAT_MS.toInt() + 1) {
            advanceTimeBy(BEAT_MS)
            port.events!!.onBeat()
            runCurrent()
        }
        assertEquals("cancel 7", port.calls.last())
        port.events!!.onStopped()
        runCurrent()

        val error = run.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonStalledException && error.silentMs == RemoteSongEngine.FIRST_LOAD_MS)
    }

    @Test
    fun `an add-on that does not beat is not watched for freezes, only held to the steps' limits`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val port = host.bindings.single().port
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        port.events!!.onStage("SEPARATE")
        port.events!!.onProgress(1, 40)
        runCurrent()

        advanceTimeBy(4 * RemoteSongEngine.FROZEN_MS)
        runCurrent()
        assertFalse(run.isCompleted)
        port.events!!.onResult(transcribed)

        assertEquals(transcribed, run.await().getOrThrow())
    }

    @Test
    fun `beats while KJB holds a chunk for the heat keep the add-on, and the chunk goes on after`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val port = host.bindings.single().port
        val run = async { runCatching { engine.run(track, null, {}) { delay(2 * 60_000L) } } }
        runCurrent()
        port.events!!.onBeat()
        port.events!!.onStage("TRANSCRIBE")
        port.events!!.onChunk()
        runCurrent()

        repeat(2 * 60_000 / BEAT_MS.toInt() + 1) {
            advanceTimeBy(BEAT_MS)
            port.events!!.onBeat()
            runCurrent()
        }
        assertEquals("proceed 7", port.calls.last())
        port.events!!.onResult(transcribed)

        assertEquals(transcribed, run.await().getOrThrow())
    }

    @Test
    fun `frozen while KJB holds a chunk for the heat, the chunk is not let go into it`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.single()
        val run = async { runCatching { engine.run(track, null, {}) { delay(2 * 60_000L) } } }
        runCurrent()
        binding.port.events!!.onBeat()
        binding.port.events!!.onStage("TRANSCRIBE")
        binding.port.events!!.onChunk()
        runCurrent()

        advanceTimeBy(2 * 60_000L + 1)
        runCurrent()

        val error = run.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonFrozenException && error.stage == AiStage.TRANSCRIBE)
        assertFalse("proceed 7" in binding.port.calls)
        assertEquals("abandonJob 7", binding.port.calls.last())
        assertTrue(binding.closed && binding.stopped)
    }

    @Test
    fun `a result that arrives with the freeze watch's timeout is the song's end, not a freeze`() = runTest {
        val host = FakeHost()
        val engine = RemoteSongEngine(session(host), notes = {})
        val binding = host.bindings.single()
        val run = async { runCatching { engine.run(track, null, {}) {} } }
        runCurrent()
        binding.port.events!!.onBeat()
        binding.port.events!!.onStage("ALIGN")
        runCurrent()

        advanceTimeBy(RemoteSongEngine.FROZEN_MS) // the limit is now: its timeout is queued, not yet run
        binding.port.events!!.onResult(transcribed)
        runCurrent()

        assertEquals(transcribed, run.await().getOrThrow())
        assertFalse(binding.closed || binding.stopped)
    }

    // The models' download, run by the add-on while KJB keeps it bound

    @Test
    fun `the add-on's download progress reaches KJB's notification, and its end ends KJB's worker`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val port = host.bindings.single().port
        val shown = mutableListOf<Pair<Long, Long>>()

        val end = async { AddonDownloads.follow(session) { done, total -> shown += done to total } }
        runCurrent()
        // The add-on took it, then KJB started the add-on's foreground service for it.
        assertEquals(listOf("apiVersion", "download", "foreground"), port.calls)
        port.downloadEvents!!.onProgress(400, 980)
        port.downloadEvents!!.onProgress(980, 980)
        port.downloadEvents!!.onEnd(Download.Done)

        assertEquals(Download.Done, end.await())
        assertEquals(listOf(400L to 980L, 980L to 980L), shown)
    }

    @Test
    fun `the add-on dying mid-download is a clean error, for the worker to try again`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val end = async { runCatching { AddonDownloads.follow(session) { _, _ -> } } }
        runCurrent()

        host.bindings.single().kill()
        runCurrent()

        assertTrue(end.await().exceptionOrNull() is AddonDiedException)
    }

    @Test
    fun `KJB's worker stopped tells the add-on to stop, and what it got stays`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val end = async { AddonDownloads.follow(session) { _, _ -> } }
        runCurrent()

        end.cancel()
        runCurrent()

        assertTrue(end.isCancelled)
        // Its own run only: a run another worker started goes on.
        assertEquals("cancelDownload 21", host.bindings.single().port.calls.last())
    }

    // The download in the add-on's own foreground service, and the same freeze watch as a song's job

    @Test
    fun `the download runs in the add-on's own foreground service, which KJB starts once the add-on took it`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val binding = host.bindings.single()
        val end = async { AddonDownloads.follow(session) { _, _ -> } }
        runCurrent()

        assertEquals(listOf("apiVersion", "download", "foreground"), binding.port.calls)
        binding.port.downloadEvents!!.onEnd(Download.Done)
        assertEquals(Download.Done, end.await())
        // Its end is the add-on's to leave the foreground by, as a job's is; KJB then lets go of it.
        assertFalse(binding.stopped)
    }

    @Test
    fun `a phone that refuses the add-on's foreground service still downloads, and the log says so`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val binding = host.bindings.single().apply { refusesForeground = true }
        val notes = mutableListOf<String>()
        val end = async { AddonDownloads.follow(session, notes::add) { _, _ -> } }
        runCurrent()

        binding.port.downloadEvents!!.onEnd(Download.Done)

        assertEquals(Download.Done, end.await())
        assertTrue(notes.toString(), notes.any { "foreground service" in it })
    }

    @Test
    fun `silent for 30 s once it beats, a download is frozen, dropped one way, let go of and its service stopped`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val binding = host.bindings.single()
        val end = async { runCatching { AddonDownloads.follow(session) { _, _ -> } } }
        runCurrent()
        val events = checkNotNull(binding.port.downloadEvents)
        events.onBeat()
        events.onProgress(100, 980)
        runCurrent()
        advanceTimeBy(BEAT_MS)
        events.onBeat()
        runCurrent()
        assertFalse(AddonDownloads.paused.value)

        advanceTimeBy(AddonKeepAlive.FROZEN_MS - 1)
        runCurrent()
        assertFalse("silent for less than 30 s", end.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        val error = end.await().exceptionOrNull()
        assertTrue(error.toString(), error is AddonFrozenException && error.stage == null && error.silentMs == AddonKeepAlive.FROZEN_MS)
        assertTrue("let go of", binding.closed && session.closed)
        assertTrue("the service KJB started is stopped", binding.stopped)
        // A call the add-on must answer could wait for as long as the phone keeps it frozen: no cancelDownload, only the
        // one-way word it hears once it runs again.
        assertEquals(listOf("apiVersion", "download", "foreground", "abandonDownload 21"), binding.port.calls)
        assertTrue("the AI page says the phone paused it", AddonDownloads.paused.value)
    }

    @Test
    fun `a frozen download is dropped one way even while the AI page binds the add-on, and the next run goes on`() = runTest {
        val host = FakeHost()
        // The AI page shows: its own binding keeps the add-on's service, which KJB's letting go then does not destroy.
        val page = launch { AddonClient(host).views(MutableSharedFlow(), retryMs = 30_000, deathPauseMs = 1_000).collect {} }
        runCurrent()
        val screen = host.bindings.single()
        val worker = session(host)
        val binding = host.bindings.last()
        val end = async { runCatching { AddonDownloads.follow(worker) { _, _ -> } } }
        runCurrent()
        binding.port.downloadEvents!!.onBeat()
        advanceTimeBy(AddonKeepAlive.FROZEN_MS + 1)
        runCurrent()

        assertTrue(end.await().exceptionOrNull() is AddonFrozenException)
        // The add-on stops the run, keeps its parts and leaves the foreground once it runs again, with no onUnbind.
        assertEquals("abandonDownload 21", binding.port.calls.last())
        assertTrue("the worker's binding let go of and its service stopped", binding.closed && binding.stopped)
        assertFalse("the page's binding stays", screen.closed)
        assertTrue("the page says the phone paused it", AddonDownloads.paused.value)

        // Allow, or the worker's retry: a new run, which the add-on takes from its parts, and the page's word goes.
        val next = async { AddonDownloads.follow(session(host)) { _, _ -> } }
        runCurrent()
        assertFalse(AddonDownloads.paused.value)
        assertEquals(listOf("apiVersion", "download", "foreground"), host.bindings.last().port.calls)
        host.bindings.last().port.downloadEvents!!.onEnd(Download.Done)
        assertEquals(Download.Done, next.await())
        page.cancel()
    }

    @Test
    fun `the next run after a freeze starts the add-on's foreground service anew, and the page's word on the pause goes`() = runTest {
        val host = FakeHost()
        val frozen = async { runCatching { AddonDownloads.follow(session(host)) { _, _ -> } } }
        runCurrent()
        host.bindings.single().port.downloadEvents!!.onBeat()
        advanceTimeBy(AddonKeepAlive.FROZEN_MS + 1)
        runCurrent()
        assertTrue(frozen.await().exceptionOrNull() is AddonFrozenException)
        assertTrue(AddonDownloads.paused.value)

        // The worker's retry, which goes on from the add-on's parts.
        val next = async { AddonDownloads.follow(session(host)) { _, _ -> } }
        runCurrent()

        assertFalse(AddonDownloads.paused.value)
        assertEquals(listOf("apiVersion", "download", "foreground"), host.bindings.last().port.calls)
        host.bindings.last().port.downloadEvents!!.onEnd(Download.Done)
        assertEquals(Download.Done, next.await())
    }

    @Test
    fun `after the add-on's process died mid-download, the next run starts its foreground service anew`() = runTest {
        val host = FakeHost()
        val dying = async { runCatching { AddonDownloads.follow(session(host)) { _, _ -> } } }
        runCurrent()
        host.bindings.single().port.downloadEvents!!.onBeat()
        host.bindings.single().kill()
        runCurrent()
        assertTrue(dying.await().exceptionOrNull() is AddonDiedException)
        assertFalse("a death is no freeze", AddonDownloads.paused.value)

        val next = async { AddonDownloads.follow(session(host)) { _, _ -> } }
        runCurrent()

        assertEquals(listOf("apiVersion", "download", "foreground"), host.bindings.last().port.calls)
        host.bindings.last().port.downloadEvents!!.onEnd(Download.Done)
        assertEquals(Download.Done, next.await())
    }

    @Test
    fun `beats keep a download that waits on a slow server from looking frozen`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val port = host.bindings.single().port
        val end = async { AddonDownloads.follow(session) { _, _ -> } }
        runCurrent()
        port.downloadEvents!!.onProgress(100, 980)

        repeat(10 * 60_000 / BEAT_MS.toInt()) {
            port.downloadEvents!!.onBeat()
            advanceTimeBy(BEAT_MS)
            runCurrent()
        }
        assertFalse(end.isCompleted)
        port.downloadEvents!!.onEnd(Download.Done)

        assertEquals(Download.Done, end.await())
    }

    @Test
    fun `a download from an add-on that does not beat is not watched for freezes`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val binding = host.bindings.single()
        val end = async { AddonDownloads.follow(session) { _, _ -> } }
        runCurrent()
        binding.port.downloadEvents!!.onProgress(100, 980)

        advanceTimeBy(4 * AddonKeepAlive.FROZEN_MS)
        runCurrent()
        assertFalse(end.isCompleted)
        binding.port.downloadEvents!!.onEnd(Download.Done)

        assertEquals(Download.Done, end.await())
        assertFalse(binding.closed || binding.stopped)
    }

    @Test
    fun `an end that comes as the freeze watch gives up is the download's end, not a freeze`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val binding = host.bindings.single()
        val end = async { runCatching { AddonDownloads.follow(session) { _, _ -> } } }
        runCurrent()
        val events = binding.port.downloadEvents!!
        events.onBeat()
        runCurrent()
        // The end comes at the very moment the watch gives up: after its word is in, before the download reads it.
        backgroundScope.launch {
            delay(AddonKeepAlive.FROZEN_MS)
            yield() // behind the watch, which wakes at the same moment
            events.onEnd(Download.Done)
        }

        advanceTimeBy(AddonKeepAlive.FROZEN_MS + 1)
        runCurrent()

        assertEquals(Download.Done, end.await().getOrThrow())
        assertFalse("never let go of as frozen", binding.closed || binding.stopped || AddonDownloads.paused.value)
    }

    @Test
    fun `the page's download phase comes from the work that counts, whatever order WorkManager lists its rows in`() {
        val replaced = listOf(WorkInfo.State.CANCELLED, WorkInfo.State.ENQUEUED)
        assertEquals(DownloadPhase.WAITING, AddonDownloads.phaseOf(replaced))
        assertEquals(DownloadPhase.WAITING, AddonDownloads.phaseOf(replaced.reversed()))
        assertEquals(DownloadPhase.RUNNING, AddonDownloads.phaseOf(listOf(WorkInfo.State.FAILED, WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)))
        assertEquals(DownloadPhase.WAITING, AddonDownloads.phaseOf(listOf(WorkInfo.State.FAILED, WorkInfo.State.BLOCKED)))
        assertEquals(DownloadPhase.FAILED, AddonDownloads.phaseOf(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED)))
        assertEquals(null, AddonDownloads.phaseOf(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)))
        assertEquals(null, AddonDownloads.phaseOf(emptyList()))
    }

    @Test
    fun `KJB's own work tells the page when the download waits for Wi-Fi or failed, the add-on tells the rest`() {
        val missing = ModelState.Missing(300, 980)
        assertEquals(ModelState.Downloading(300, 980, waiting = true), AddonDownloads.withPhase(missing, DownloadPhase.WAITING))
        assertEquals(ModelState.Failed(300, 980), AddonDownloads.withPhase(missing, DownloadPhase.FAILED))
        assertEquals(ModelState.Downloading(300, 980, waiting = false), AddonDownloads.withPhase(missing, DownloadPhase.RUNNING))
        // The add-on's own word wins while it downloads, or once the models are in place, or when there is no room.
        val running = ModelState.Downloading(450, 980, waiting = false)
        assertEquals(running, AddonDownloads.withPhase(running, DownloadPhase.RUNNING))
        assertEquals(ModelState.Ready(980), AddonDownloads.withPhase(ModelState.Ready(980), DownloadPhase.FAILED))
        assertEquals(ModelState.NoRoom(0, 980, 1_800), AddonDownloads.withPhase(ModelState.NoRoom(0, 980, 1_800), DownloadPhase.WAITING))
        assertEquals(missing, AddonDownloads.withPhase(missing, null))
        assertEquals(null, AddonDownloads.withPhase(null, DownloadPhase.WAITING))
    }

    // What the AI page shows

    @Test
    fun `the page follows the add-on, missing, then installed and ready with its models, then back after it died`() = runTest {
        val host = FakeHost(installed = false)
        val changes = MutableSharedFlow<Unit>()
        val views = mutableListOf<AddonView>()
        val watching = launch { AddonClient(host).views(changes, retryMs = 30_000, deathPauseMs = 1_000).collect { views += it } }
        runCurrent()
        assertEquals(AddonView(AddonState.Missing), views.last())

        host.installed = true
        changes.emit(Unit)
        runCurrent()
        assertEquals(AddonView(AddonState.Ready, AddonStatus(true, ModelState.Ready(980))), views.last())
        val port = host.bindings.single().port
        port.statusListener!!(AddonStatus(true, ModelState.Downloading(10, 980, waiting = false)))
        runCurrent()
        assertEquals(ModelState.Downloading(10, 980, waiting = false), views.last().status?.models)

        host.bindings.single().kill()
        runCurrent()
        assertTrue(host.bindings.single().closed)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, host.bindings.size)
        assertEquals(AddonState.Ready, views.last().state)

        host.installed = false
        changes.emit(Unit)
        runCurrent()
        assertEquals(AddonView(AddonState.Missing), views.last())
        assertTrue(host.bindings.last().closed)
        watching.cancel()
    }

    @Test
    fun `an unreachable add-on is tried again after a while`() = runTest {
        val host = FakeHost(refuses = true)
        val views = mutableListOf<AddonView>()
        val watching = launch { AddonClient(host).views(MutableSharedFlow(), retryMs = 30_000, deathPauseMs = 1_000).collect { views += it } }
        runCurrent()
        assertEquals(AddonView(AddonState.Unreachable), views.last())

        host.refuses = false
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(AddonState.Ready, views.last().state)
        watching.cancel()
    }

    private companion object {
        /** How often the add-on beats (the add-on's AddonJobs.BEAT_EVERY_MS). */
        const val BEAT_MS: Long = 5_000
    }
}
