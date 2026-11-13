package app.lyrify.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor

/** The add-on's side of KJB's calls: who may call ([CallerCheck]) and how a song's job runs ([AddonJobs]). */
@OptIn(ExperimentalCoroutinesApi::class)
class AiServiceTest {
    // Who may call

    private val kjb = 10_123
    private val pin = "AB".repeat(32)

    /** KJB's uid holds KJB's package and KJB's certificate; [other] another app's, signed with another key. */
    private val other = 10_200

    private fun check(
        pinned: String = pin,
        packages: Map<Int, Array<String>> = mapOf(kjb to arrayOf("app.lyrify"), other to arrayOf("com.example.other")),
        certs: Map<Int, String> = mapOf(kjb to pin, other to "CD".repeat(32)),
    ) = CallerCheck(
        kjbPackage = "app.lyrify",
        certSha256 = pinned,
        packagesFor = { uid -> packages[uid] },
        hasCert = { uid, sha -> certs[uid]?.let { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().contentEquals(sha) } == true },
    )

    @Test
    fun `KJB signed with the pinned certificate may call`() {
        assertTrue(check().allows(kjb))
        check().enforce(kjb)
    }

    @Test
    fun `another app, an unknown uid, or KJB's name with another certificate may not`() {
        assertFalse(check().allows(other))
        assertFalse(check().allows(99_999))
        assertFalse(check(certs = mapOf(kjb to "CD".repeat(32))).allows(kjb))
        assertThrows(SecurityException::class.java) { check().enforce(other) }
    }

    @Test
    fun `an app signed with KJB's key but not KJB may not`() {
        val sameKey = check(packages = mapOf(other to arrayOf("com.example.other")), certs = mapOf(other to pin))

        assertFalse(sameKey.allows(other))
    }

    @Test
    fun `no pin trusts nobody`() {
        assertFalse(check(pinned = "").allows(kjb))
        assertFalse(check(pinned = "not hex").allows(kjb))
    }

    @Test
    fun `a uid KJB shares with other packages is KJB's`() {
        assertTrue(check(packages = mapOf(kjb to arrayOf("com.example.shared", "app.lyrify"))).allows(kjb))
    }

    // A song's job

    private val song = AiSong(FileDescriptor(), durationMs = 200_000)
    private val stats = AiStats(200.0, 0.5, 80.0, 2, 120.0, 10, 9, emptyMap())
    private val done = AiResult.Transcribed("[00:01.000]placeholder\n", stats)

    /** What the caller heard, in order. */
    private class Heard : JobEvents {
        val events = mutableListOf<String>()
        override fun onStage(stage: AiStage) {
            events += "stage ${stage.name}"
        }

        override fun onNote(line: String) {
            events += "note $line"
        }

        override fun onChunk() {
            events += "chunk"
        }

        override fun onResult(result: AiResult) {
            events += "result ${result.javaClass.simpleName}"
        }

        override fun onStopped() {
            events += "stopped"
        }
    }

    /** An engine that decodes, then runs [chunks] Whisper chunks, each after the caller's go-ahead; it notes each one. */
    private inner class FakeEngine(private val notes: (String) -> Unit, private val chunks: Int = 2) : JobEngine {
        var closed = false
        var runs = 0

        override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
            check(!closed) { "the engine was closed" }
            runs++
            onStage(AiStage.DECODE)
            notes("decode: 5 ms")
            onStage(AiStage.TRANSCRIBE)
            repeat(chunks) { k ->
                beforeChunk()
                currentCoroutineContext().ensureActive()
                notes("chunk ${k + 1} of $chunks")
            }
            return done
        }

        override fun close() {
            closed = true
        }
    }

    private val engines = mutableListOf<FakeEngine>()
    private val released = mutableListOf<Long>()

    /** Jobs in the test's background scope: one left waiting at a chunk is cancelled when the test ends. */
    private fun TestScope.jobs(chunks: Int = 2) = AddonJobs(
        scope = backgroundScope,
        context = StandardTestDispatcher(testScheduler),
        openEngine = { notes -> FakeEngine(notes, chunks).also { engines += it } },
    )

    @Test
    fun `a job tells its stages and notes, waits at each chunk for the go-ahead, then gives its result`() = runTest {
        val jobs = jobs()
        val heard = Heard()

        val job = jobs.start(song, plainLyrics = null, { heard }) { released += it }
        runCurrent()
        assertEquals(listOf("stage DECODE", "note decode: 5 ms", "stage TRANSCRIBE", "chunk"), heard.events)

        jobs.proceed(job)
        runCurrent()
        jobs.proceed(job)
        runCurrent()

        assertEquals(
            listOf("stage DECODE", "note decode: 5 ms", "stage TRANSCRIBE", "chunk", "note chunk 1 of 2", "chunk", "note chunk 2 of 2", "result Transcribed"),
            heard.events,
        )
        assertEquals(listOf(job), released) // the song's descriptor is let go once the job ends
        assertFalse(engines.single().closed) // the models stay loaded for the next song
    }

    @Test
    fun `the engine opens for the first job and serves the next ones until it is closed`() = runTest {
        val jobs = jobs(chunks = 0)
        jobs.start(song, null, { Heard() }) {}
        runCurrent()
        jobs.start(song, null, { Heard() }) {}
        runCurrent()
        assertEquals(1, engines.size)
        assertEquals(2, engines.single().runs)

        jobs.closeEngine()
        runCurrent()
        assertTrue(engines.single().closed)
        jobs.start(song, null, { Heard() }) {}
        runCurrent()
        assertEquals(2, engines.size)
    }

    @Test
    fun `a cancelled job stops at its chunk and says so, with nothing to record`() = runTest {
        val jobs = jobs()
        val heard = Heard()
        val job = jobs.start(song, null, { heard }) { released += it }
        runCurrent()

        jobs.cancel(job)
        runCurrent()

        assertEquals("stopped", heard.events.last())
        assertFalse(heard.events.any { it.startsWith("result") })
        assertEquals(listOf(job), released)
        // The next job runs as usual.
        val next = Heard()
        val second = jobs.start(song, null, { next }) {}
        runCurrent()
        jobs.proceed(second) // one go-ahead per chunk, each after the chunk asked
        runCurrent()
        jobs.proceed(second)
        runCurrent()
        assertEquals("result Transcribed", next.events.last())
    }

    @Test
    fun `cancelling a job that is not running does nothing`() = runTest {
        val jobs = jobs(chunks = 0)
        val heard = Heard()
        val job = jobs.start(song, null, { heard }) {}
        runCurrent()

        jobs.cancel(job)
        jobs.cancel(job + 41)
        runCurrent()

        assertEquals("result Transcribed", heard.events.last())
    }

    @Test
    fun `one job at a time`() = runTest {
        val jobs = jobs()
        jobs.start(song, null, { Heard() }) {}
        runCurrent()

        assertThrows(IllegalStateException::class.java) { jobs.start(song, null, { Heard() }) {} }
    }

    @Test
    fun `KJB gone mid-job stops the job and closes the engine`() = runTest {
        val jobs = jobs()
        val heard = Heard()
        jobs.start(song, null, { heard }) { released += it }
        runCurrent()

        jobs.stopAll()
        runCurrent()

        assertEquals("stopped", heard.events.last())
        assertEquals(1, released.size)
        assertTrue(engines.single().closed)
    }

    @Test
    fun `closing the engine during a job waits for the job to stop first`() = runTest {
        val opened = CompletableDeferred<Unit>()
        var closedWhileRunning: Boolean? = null
        val jobs = AddonJobs(
            scope = backgroundScope,
            context = StandardTestDispatcher(testScheduler),
            openEngine = {
                object : JobEngine {
                    var running = false
                    override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
                        running = true
                        opened.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { running = false }
                        }
                    }

                    override fun close() {
                        closedWhileRunning = running
                    }
                }
            },
        )
        val heard = Heard()
        jobs.start(song, null, { heard }) {}
        runCurrent()
        assertTrue(opened.isCompleted)

        jobs.closeEngine()
        runCurrent()

        assertEquals(false, closedWhileRunning)
        assertEquals("stopped", heard.events.last())
    }
}
