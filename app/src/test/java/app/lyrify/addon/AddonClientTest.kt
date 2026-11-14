package app.lyrify.addon

import androidx.work.WorkInfo
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

    // Whether the installed add-on is KJB's, as Android tells it

    private val pin = "AB".repeat(32)

    private class FakePackages(
        var certificate: String = "AB".repeat(32),
        var signedLikeKjb: Boolean = true,
        var definer: PermissionDefiner? = PermissionDefiner("app.lyrify.ai", signature = true),
    ) : TrustPort {
        override fun hasCertificate(packageName: String, sha256: ByteArray): Boolean =
            certificate.chunked(2).map { it.toInt(16).toByte() }.toByteArray().contentEquals(sha256)

        override fun signedLikeKjb(packageName: String): Boolean = signedLikeKjb

        override fun definer(permission: String): PermissionDefiner? = definer
    }

    private fun trust(packages: FakePackages, pinned: String = pin) = AddonTrust("app.lyrify.ai", pinned, packages)

    @Test
    fun `the add-on is KJB's when signed with the pinned certificate, like KJB, and the bind permission is its own`() {
        assertTrue(trust(FakePackages()).trusted())
    }

    @Test
    fun `another certificate, another signer than KJB, or no pin is not KJB's add-on`() {
        assertFalse(trust(FakePackages(certificate = "CD".repeat(32))).trusted())
        assertFalse(trust(FakePackages(signedLikeKjb = false)).trusted())
        assertFalse(trust(FakePackages(), pinned = "").trusted())
    }

    @Test
    fun `a bind permission another app defined first, or one not at signature level, is never bound through`() {
        assertFalse(trust(FakePackages(definer = PermissionDefiner("com.example.other", signature = true))).trusted())
        assertFalse(trust(FakePackages(definer = PermissionDefiner("app.lyrify.ai", signature = false))).trusted())
        assertFalse(trust(FakePackages(definer = null)).trusted())
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
        engine.close()
        assertFalse("nothing more is asked of it", "closeEngine" in binding.port.calls)
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

    // The models' download, run by the add-on while KJB keeps it bound

    @Test
    fun `the add-on's download progress reaches KJB's notification, and its end ends KJB's worker`() = runTest {
        val host = FakeHost()
        val session = session(host)
        val port = host.bindings.single().port
        val shown = mutableListOf<Pair<Long, Long>>()

        val end = async { AddonDownloads.follow(session) { done, total -> shown += done to total } }
        runCurrent()
        assertEquals("download", port.calls.last())
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
}
