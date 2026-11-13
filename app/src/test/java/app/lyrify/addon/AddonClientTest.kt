package app.lyrify.addon

import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.AiStats
import app.lyrify.ai.api.AddonStatus
import app.lyrify.ai.api.ImportReply
import app.lyrify.lyrics.LocalTrack
import app.lyrify.models.ModelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

        override fun startDownload(restart: Boolean) = call("startDownload $restart")

        override fun cancelDownload() = call("cancelDownload")

        override fun deleteModels(): Boolean = true.also { call("deleteModels") }

        override fun discardModel(name: String): Boolean = true.also { call("discardModel $name") }

        override fun run(song: File, durationMs: Long, plainLyrics: String?, events: RunEvents): Long {
            call("run ${song.name} $durationMs ${plainLyrics != null}")
            this.events = events
            return 7
        }

        override fun proceed(job: Long) = call("proceed $job")

        override fun cancel(job: Long) = call("cancel $job")

        override fun closeEngine() = call("closeEngine")

        override fun importModel(name: String, file: File, sha256: String): ImportReply = ImportReply(ImportReply.Outcome.IMPORTED).also { call("import $name") }
    }

    private class FakeBinding(override val port: FakePort) : AddonBinding {
        val died = CompletableDeferred<Unit>()
        var closed = false
        override val death: Deferred<Unit> get() = died

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
        assertEquals("run Placeholder.mp3 200000 true", port.calls.last())
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
            override fun close() {}
        }

        val result = RemoteSongEngine(AddonSession(binding, 1), notes = {}).run(track, null, {}) {}

        assertEquals(AiResult.Failed("FileNotFoundException"), result)
        session.close()
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
}
