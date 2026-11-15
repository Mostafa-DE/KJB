package app.lyrify.ai

import android.content.pm.ServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileDescriptor

/** How a song's job runs in the add-on ([AddonJobs]); who may call it is :addon-shared's AddonTrustTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiServiceTest {
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

        override fun onProgress(done: Int, total: Int) {
            events += "progress $done/$total"
        }

        /** Counted apart: they come with the virtual clock, not with the job's steps. */
        var beats = 0

        override fun onBeat() {
            beats++
        }
    }

    /** An engine that decodes, then runs [chunks] Whisper chunks, each after the caller's go-ahead; it notes each one. */
    private inner class FakeEngine(private val notes: (String) -> Unit, private val chunks: Int = 2) : JobEngine {
        var closed = false
        var runs = 0

        override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, onProgress: (Int, Int) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
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

        jobs.shutDown()
        runCurrent()

        assertEquals("stopped", heard.events.last())
        assertEquals(1, released.size)
        assertTrue(engines.single().closed)
    }

    /** The service's own scope, which its onDestroy cancels. */
    private fun TestScope.service() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    @Test
    fun `the last client leaving closes the models at once, before the service's scope goes`() = runTest {
        val service = service()
        val jobs = AddonJobs(service, StandardTestDispatcher(testScheduler)) { notes -> FakeEngine(notes, chunks = 0).also { engines += it } }
        jobs.start(song, null, { Heard() }) {}
        runCurrent()
        assertFalse(engines.single().closed)

        // onUnbind and onDestroy, back to back on the main thread: nothing else runs in between.
        jobs.shutDown()
        service.cancel()

        assertTrue("the NPU sessions are let go before onDestroy returns", engines.single().closed)
    }

    @Test
    fun `with a job mid-step the models close once the step ends, even though the service's scope is gone`() = runTest {
        val service = service()
        var closedWhileRunning: Boolean? = null
        var running = false
        val jobs = AddonJobs(service, StandardTestDispatcher(testScheduler)) {
            object : JobEngine {
                override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, onProgress: (Int, Int) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
                    running = true
                    try {
                        awaitCancellation()
                    } finally {
                        running = false
                    }
                }

                override fun close() {
                    closedWhileRunning = running
                }
            }
        }
        val heard = Heard()
        jobs.start(song, null, { heard }) { released += it }
        runCurrent()

        jobs.shutDown()
        service.cancel()
        runCurrent()

        assertEquals(false, closedWhileRunning)
        assertEquals("stopped", heard.events.last())
        assertEquals(1, released.size)
    }

    @Test
    fun `a job started just before the service goes still lets go of its song`() = runTest {
        val service = service()
        val jobs = AddonJobs(service, StandardTestDispatcher(testScheduler)) { notes -> FakeEngine(notes) }

        jobs.start(song, null, { Heard() }) { released += it }
        service.cancel() // before the job's coroutine ever ran
        runCurrent()

        assertEquals(1, released.size)
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
                    override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, onProgress: (Int, Int) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
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

    @Test
    fun `a job's result stays with the add-on until the next job ends with one, to be fetched by its id`() = runTest {
        val jobs = jobs(chunks = 1)
        val first = jobs.start(song, null, { Heard() }) {}
        runCurrent()
        assertNull("still running", jobs.result(first))

        jobs.proceed(first)
        runCurrent()
        assertEquals(done, jobs.result(first))
        assertNull(jobs.result(first + 7))

        val second = jobs.start(song, null, { Heard() }) {}
        runCurrent()
        jobs.cancel(second)
        runCurrent()
        assertNull("stopped: nothing to keep", jobs.result(second))
        assertEquals(done, jobs.result(first))

        val third = jobs.start(song, null, { Heard() }) {}
        runCurrent()
        jobs.proceed(third)
        runCurrent()
        assertEquals(done, jobs.result(third))
        assertNull("replaced by the next job's", jobs.result(first))
    }

    // KJB's watchdog hears the job move; a stop the job's step never takes ends the process

    @Test
    fun `a job's progress reaches KJB, each stage's first piece at once, then at most every 5 s`() = runTest {
        val heard = Heard()
        val jobs = AddonJobs(backgroundScope, StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime }) {
            object : JobEngine {
                override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, onProgress: (Int, Int) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
                    onStage(AiStage.SEPARATE)
                    for (k in 1..10) {
                        delay(2_000) // an MDX segment
                        onProgress(k, 10)
                    }
                    onStage(AiStage.ALIGN)
                    onProgress(1, 3)
                    return done
                }

                override fun close() {}
            }
        }

        jobs.start(song, null, { heard }) {}
        advanceTimeBy(60_000)
        runCurrent()

        // Pieces at 2, 4, ... 20 s: the first at once, then none closer than 5 s to the last one sent.
        assertEquals(
            listOf("stage SEPARATE", "progress 1/10", "progress 4/10", "progress 7/10", "progress 10/10", "stage ALIGN", "progress 1/3", "result Transcribed"),
            heard.events,
        )
    }

    /** The stops' checks, as the main thread's Handler would run them later, and the stages the process ended in. */
    private val checks = mutableListOf<Pair<Long, () -> Unit>>()
    private val ended = mutableListOf<AiStage?>()

    /** An engine stuck in a native step of [stage] that no stop reaches; it returns once [step] completes, if ever. */
    private fun TestScope.stuckJobs(step: CompletableDeferred<Unit>, stage: AiStage = AiStage.SEPARATE) = AddonJobs(
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        later = { ms, check -> checks += ms to check },
        endProcess = { ended += it },
        waitForEnd = waitingFor(),
    ) {
        object : JobEngine {
            override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, onProgress: (Int, Int) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
                onStage(stage)
                withContext(NonCancellable) { step.await() }
                currentCoroutineContext().ensureActive()
                return done
            }

            override fun close() {}
        }
    }

    /** The main thread's wait in onUnbind, in virtual time: what is due runs, then whether the job has ended. */
    private fun TestScope.waitingFor(): (Job, Long) -> Boolean = { job, _ ->
        testScheduler.runCurrent()
        job.isCompleted
    }

    @Test
    fun `the last client leaving while a job's step does not end ends the process before onUnbind returns`() = runTest {
        val step = CompletableDeferred<Unit>()
        val jobs = stuckJobs(step)
        jobs.start(song, null, { Heard() }) {}
        runCurrent()

        jobs.shutDown()

        // Decided there and then: a process cached for 10 s is frozen (Android 14+), and a later check would never run.
        assertEquals(listOf<AiStage?>(AiStage.SEPARATE), ended)
        step.complete(Unit)
    }

    @Test
    fun `the last client leaving while a job waits at a chunk stops it and closes the models, keeping the process`() = runTest {
        val jobs = AddonJobs(backgroundScope, StandardTestDispatcher(testScheduler), endProcess = { ended += it }, waitForEnd = waitingFor()) { notes ->
            FakeEngine(notes).also { engines += it }
        }
        val heard = Heard()
        jobs.start(song, null, { heard }) {}
        runCurrent()

        jobs.shutDown()

        assertEquals("stopped", heard.events.last())
        assertTrue(engines.single().closed)
        assertEquals(emptyList<AiStage?>(), ended)
    }

    @Test
    fun `after a native step was left behind, new songs are refused as the NPU busy until the process restarts`() = runTest {
        val jobs = jobs(chunks = 0)
        jobs.abandoned()
        val heard = Heard()

        val job = jobs.start(song, null, { heard }) { released += it }
        runCurrent()

        val result = jobs.result(job)
        assertTrue("$result", result is AiResult.Failed && result.npu && result.message.startsWith("NpuBusyException"))
        assertEquals(listOf("result Failed"), heard.events)
        assertTrue("no session is made beside the stuck one", engines.isEmpty())
        assertEquals(listOf(job), released)
    }

    @Test
    fun `a native step left behind ends the process when the last client leaves, or soon after while one stays`() = runTest {
        val jobs = AddonJobs(
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            later = { ms, check -> checks += ms to check },
            endProcess = { ended += it },
            waitForEnd = waitingFor(),
        ) { notes -> FakeEngine(notes, chunks = 0) }
        jobs.start(song, null, { Heard() }) {}
        runCurrent()

        jobs.abandoned()
        assertEquals(listOf(AddonJobs.ABANDONED_END_MS), checks.map { it.first })
        checks.single().second()
        assertEquals(1, ended.size)

        jobs.shutDown()
        assertEquals(2, ended.size)
    }

    @Test
    fun `a stop KJB asks for, or closing the engine, ends the process when the step has not ended 10 s later`() = runTest {
        val step = CompletableDeferred<Unit>()
        val jobs = stuckJobs(step, AiStage.TRANSCRIBE)
        val job = jobs.start(song, null, { Heard() }) {}
        runCurrent()

        jobs.cancel(job)
        jobs.closeEngine()
        runCurrent()
        checks.forEach { it.second() }

        assertEquals(listOf(AddonJobs.STEP_END_MS, AddonJobs.STEP_END_MS), checks.map { it.first })
        assertEquals(listOf<AiStage?>(AiStage.TRANSCRIBE, AiStage.TRANSCRIBE), ended)
        step.complete(Unit)
    }

    @Test
    fun `a stopped job whose step ends in time leaves the process alone`() = runTest {
        val step = CompletableDeferred<Unit>()
        val jobs = stuckJobs(step)
        val heard = Heard()
        val job = jobs.start(song, null, { heard }) {}
        runCurrent()

        jobs.cancel(job)
        step.complete(Unit) // the step returns within the 10 s, and the stop takes
        runCurrent()
        assertEquals(listOf(AddonJobs.STEP_END_MS), checks.map { it.first })
        checks.single().second()

        assertEquals("stopped", heard.events.last())
        assertEquals(emptyList<AiStage?>(), ended)
    }

    @Test
    fun `debug lines follow a job's stages, chunks and end, with counts and times only`() = runTest {
        val lines = mutableListOf<String>()
        val jobs = AddonJobs(backgroundScope, StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime }, debug = { lines += it }) { notes ->
            FakeEngine(notes, chunks = 1)
        }

        val job = jobs.start(song, null, { Heard() }) {}
        runCurrent()
        jobs.proceed(job)
        runCurrent()

        assertEquals(
            listOf(
                "job 1: started",
                "job 1: decode at 0 ms",
                "job 1: transcribe at 0 ms",
                "job 1: chunk 1 waits for KJB at 0 ms",
                "job 1: chunk 1 goes on at 0 ms",
                "job 1: transcribed at 0 ms",
            ),
            lines,
        )
    }

    // KJB hears the job beat while it lives

    @Test
    fun `a job beats at once and every 5 s while it lives, through a step of minutes, and stops beating when it ends`() = runTest {
        val step = CompletableDeferred<Unit>()
        val heard = Heard()
        val jobs = AddonJobs(backgroundScope, StandardTestDispatcher(testScheduler), beats = StandardTestDispatcher(testScheduler)) {
            object : JobEngine {
                override suspend fun run(song: AiSong, plainLyrics: String?, onStage: (AiStage) -> Unit, onProgress: (Int, Int) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
                    onStage(AiStage.SEPARATE)
                    withContext(NonCancellable) { step.await() } // creating the MDX session: one native call of minutes
                    return done
                }

                override fun close() {}
            }
        }

        jobs.start(song, null, { heard }) {}
        runCurrent()
        assertEquals(1, heard.beats)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals("one at its start, then one every 5 s", 1 + 5 * 60_000 / AddonJobs.BEAT_EVERY_MS.toInt(), heard.beats)

        step.complete(Unit)
        runCurrent()
        assertEquals("result Transcribed", heard.events.last())
        val beats = heard.beats
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("none once it ended", beats, heard.beats)
    }

    @Test
    fun `busy from a job's start to its end`() = runTest {
        val jobs = jobs(chunks = 1)
        assertFalse(jobs.busy)

        val job = jobs.start(song, null, { Heard() }) {}
        assertTrue(jobs.busy)
        runCurrent()
        assertTrue("waiting at its chunk", jobs.busy)
        jobs.proceed(job)
        runCurrent()

        assertFalse(jobs.busy)
    }

    // Android's time for the add-on's foreground service runs out (onTimeout, Android 15+)

    @Test
    fun `the time running out ends the job after its step as the NPU busy, so KJB tries the song again later`() = runTest {
        val step = CompletableDeferred<Unit>()
        val jobs = stuckJobs(step)
        val heard = Heard()
        val job = jobs.start(song, null, { heard }) { released += it }
        runCurrent()

        jobs.timeUp()
        assertEquals("the process ends if the step has not ended 10 s later", listOf(AddonJobs.STEP_END_MS), checks.map { it.first })
        step.complete(Unit)
        runCurrent()

        val result = jobs.result(job)
        assertTrue("$result", result is AiResult.Failed && result.npu)
        assertEquals("result Failed", heard.events.last())
        assertEquals(listOf(job), released)
        checks.single().second()
        assertEquals("its step ended in time", emptyList<AiStage?>(), ended)
    }

    @Test
    fun `the time running out with no job under way leaves the next job alone`() = runTest {
        val jobs = jobs(chunks = 0)
        jobs.timeUp()

        val heard = Heard()
        jobs.start(song, null, { heard }) {}
        runCurrent()

        assertEquals("result Transcribed", heard.events.last())
    }

    // The add-on's own foreground service while a job works (the logic: :addon-shared's AddonKeepAliveTest)

    @Test
    fun `a job's foreground service is mediaProcessing from Android 15, dataSync before it`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, jobForegroundType(37))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, jobForegroundType(35))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, jobForegroundType(34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, jobForegroundType(30))
    }

    @Test
    fun `the notice says the same for every job, with no room for a song, its lyrics or a path`() {
        val strings = File("src/main/res/values/strings.xml").also { assertTrue("run from the module: ${it.absolutePath}", it.isFile) }.readText()
        fun string(name: String): String? = Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1)

        assertEquals("AI work", string("work_channel"))
        assertEquals("KJB AI is working", string("work_title"))
        // No placeholder: nothing of a job can be put into either.
        assertFalse(listOf("work_channel", "work_title").any { '%' in string(it).orEmpty() })
    }
}
