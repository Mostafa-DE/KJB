package app.lyrify.ai

import android.content.pm.ServiceInfo
import app.lyrify.addon.shared.ForegroundPort
import app.lyrify.addon.shared.JobForeground
import app.lyrify.models.Download
import app.lyrify.models.DownloadEvents
import app.lyrify.models.DownloadRun
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
import kotlinx.coroutines.plus
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
    // How it introduces itself

    @Test
    fun `the model downloads name the add-on, its version and KJB's repository`() {
        assertEquals("KJB-AI/${BuildConfig.VERSION_NAME} ( https://github.com/Mostafa-DE/KJB )", USER_AGENT)
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
    fun `the models' download runs as dataSync on every Android version, a song's job as its own type`() = runTest {
        // Android 15+ counts 6 hours in 24 for each type apart: the download alone never takes from the jobs' mediaProcessing
        // time (a service in both types at once is timed as mediaProcessing).
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, downloadWork(DownloadRun(backgroundScope) { Download.Done }).type)
        for (sdk in listOf(30, 34, 35, 36, 37)) assertEquals("$sdk", jobForegroundType(sdk), jobWork(sdk, jobs()).type)
    }

    // A song's job and the models' download at once, in the add-on's one foreground service

    /** Android's side of the foreground service: what the service asked of it, the types joined as dumpsys names them. */
    private class Foreground : ForegroundPort<String> {
        val calls = mutableListOf<String>()

        override fun startForeground(type: Int, tap: String?) {
            calls += "start " + listOfNotNull(
                "mediaProcessing".takeIf { type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING != 0 },
                "dataSync".takeIf { type and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0 },
            ).joinToString("|")
        }

        override fun stopForeground() {
            calls += "stopForeground"
        }

        override fun stopSelf(startId: Int?) {
            calls += "stopSelf ${startId ?: "all"}"
        }
    }

    /** What the download's caller heard of its end. */
    private class DownloadHeard : DownloadEvents {
        val ends = mutableListOf<Download>()

        override fun onProgress(done: Long, total: Long) {}

        override fun onEnd(end: Download) {
            ends += end
        }

        override fun onBeat() {}
    }

    /** The service on [sdk], as AiService wires it: a song's jobs (one chunk each), the download (done once [gate] opens). */
    private class Wired(val jobs: AddonJobs, val downloads: DownloadRun, val foreground: JobForeground<String>)

    private fun TestScope.service(sdk: Int, port: Foreground, gate: CompletableDeferred<Unit>): Wired {
        lateinit var foreground: JobForeground<String>
        val jobs = jobs(chunks = 1)
        val downloads = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler)), ended = { foreground.jobEnded() }) {
            gate.await()
            Download.Done
        }
        foreground = JobForeground(aiWork(sdk, jobs, downloads), port, fromKjb = { it == kjb })
        return Wired(jobs, downloads, foreground)
    }

    /** A song's job, then the download beside it, each followed by KJB's start for it; the job's id and the download's caller. */
    private fun TestScope.both(service: Wired): Pair<Long, DownloadHeard> {
        val job = service.jobs.start(song, null, { Heard() }) { service.foreground.jobEnded() }
        runCurrent()
        service.foreground.start(1, kjb)
        val download = DownloadHeard()
        service.downloads.start(download)
        runCurrent()
        service.foreground.start(2, kjb)
        return job to download
    }

    private val kjb = "KJB's AI page"

    @Test
    fun `a job, then the download beside it, in the foreground as both from Android 15, then as the download's alone`() = runTest {
        for (sdk in listOf(36, 34)) {
            val port = Foreground()
            val gate = CompletableDeferred<Unit>()
            val service = service(sdk, port, gate)
            val (job, download) = both(service)

            service.jobs.proceed(job) // its one chunk: the job ends first
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf<Download>(Download.Done), download.ends)
            val expected = if (sdk >= 35) {
                // The download's own type again once the job ended: Android times a service in both types as
                // mediaProcessing alone (AOSP getTimeLimitedFgsType), so the download left alone goes back to dataSync's time.
                listOf("start mediaProcessing", "start mediaProcessing|dataSync", "start dataSync")
            } else {
                listOf("start dataSync") // both dataSync before Android 15: nothing to change
            }
            assertEquals("$sdk", expected + listOf("stopForeground", "stopSelf 2"), port.calls)
        }
    }

    @Test
    fun `a start with no work under way goes in and out as dataSync, whose time a night of jobs never uses`() = runTest {
        for (sdk in listOf(36, 34)) {
            val port = Foreground()
            service(sdk, port, CompletableDeferred()).foreground.start(1, kjb)
            assertEquals("$sdk", listOf("start dataSync", "stopForeground", "stopSelf 1"), port.calls)
        }
    }

    @Test
    fun `the download, then a job beside it, the download ending first, and the job goes on as mediaProcessing`() = runTest {
        val port = Foreground()
        val gate = CompletableDeferred<Unit>()
        val service = service(36, port, gate)
        val download = DownloadHeard()
        service.downloads.start(download)
        runCurrent()
        service.foreground.start(1, kjb)
        val job = service.jobs.start(song, null, { Heard() }) { service.foreground.jobEnded() }
        runCurrent()
        service.foreground.start(2, kjb)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("start dataSync", "start mediaProcessing|dataSync", "start mediaProcessing"), port.calls)
        service.jobs.proceed(job)
        runCurrent()

        assertEquals(listOf("start dataSync", "start mediaProcessing|dataSync", "start mediaProcessing", "stopForeground", "stopSelf 2"), port.calls)
    }

    @Test
    fun `mediaProcessing's time running out ends the job as the NPU busy, and the download goes on as dataSync`() = runTest {
        val port = Foreground()
        val gate = CompletableDeferred<Unit>()
        val service = service(36, port, gate)
        val (job, download) = both(service)
        port.calls.clear()

        service.foreground.timeUp(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        runCurrent()

        val result = service.jobs.result(job)
        assertTrue("$result", result is AiResult.Failed && result.npu)
        assertTrue("the download goes on", download.ends.isEmpty() && service.downloads.busy)
        assertEquals(listOf("start dataSync"), port.calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf<Download>(Download.Done), download.ends)
        assertEquals(listOf("start dataSync", "stopForeground", "stopSelf 2"), port.calls)
    }

    @Test
    fun `dataSync's time running out stops the download for later, and the job goes on as mediaProcessing`() = runTest {
        val port = Foreground()
        val gate = CompletableDeferred<Unit>()
        val service = service(36, port, gate)
        val (job, download) = both(service)
        port.calls.clear()

        service.foreground.timeUp(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        runCurrent()

        assertTrue("${download.ends}", download.ends.single() is Download.Interrupted) // its parts kept, KJB tries again later
        assertTrue("the job goes on", service.jobs.busy)
        assertEquals(listOf("start mediaProcessing"), port.calls)
        service.jobs.proceed(job)
        runCurrent()
        assertTrue(service.jobs.result(job) is AiResult.Transcribed)
        assertEquals(listOf("start mediaProcessing", "stopForeground", "stopSelf 2"), port.calls)
    }

    @Test
    fun `the download's notice says how far it is in whole percents, never past whole`() {
        assertEquals(41, downloadPercent(412, 981))
        assertEquals(0, downloadPercent(0, 981))
        assertEquals(100, downloadPercent(981, 981))
        assertEquals(100, downloadPercent(990, 981))
        assertEquals("no size told yet", 0, downloadPercent(5, 0))
    }

    @Test
    fun `the notice says the same for every job and only numbers for the download, with no room for a song, its lyrics, a path or a link`() {
        val strings = File("src/main/res/values/strings.xml").also { assertTrue("run from the module: ${it.absolutePath}", it.isFile) }.readText()
        fun string(name: String): String? = Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1)

        assertEquals("AI work", string("work_channel"))
        assertEquals("KJB AI is working", string("work_title"))
        // No placeholder: nothing of a job can be put into either.
        assertFalse(listOf("work_channel", "work_title").any { '%' in string(it).orEmpty() })
        // The download's: plain words, then its percent and two sizes, room for no file name or link.
        assertEquals("Downloading the AI models", string("models_title"))
        assertEquals("%1\$d%% (%2\$s of %3\$s)", string("models_progress"))
    }
}
