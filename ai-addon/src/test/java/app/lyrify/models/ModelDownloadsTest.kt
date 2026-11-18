package app.lyrify.models

import android.content.pm.ServiceInfo
import app.lyrify.addon.shared.ForegroundPort
import app.lyrify.addon.shared.JobForeground
import app.lyrify.ai.downloadWork
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The add-on's model download, run only while KJB keeps it bound and hears it: its progress and end go to KJB (which
 * shows the notification), it stops when KJB goes, and the next bind goes on from the parts and checks every file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadsTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** What KJB heard, in order. */
    private class Heard : DownloadEvents {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val ended = CompletableDeferred<Download>()

        override fun onProgress(done: Long, total: Long) {
            events += "progress $done/$total"
        }

        override fun onEnd(end: Download) {
            events += "end $end"
            ended.complete(end)
        }

        /** Counted apart: they come with the virtual clock, not with the download's steps. */
        var beats = 0

        override fun onBeat() {
            beats++
        }
    }

    // The run, with a scripted download

    @Test
    fun `a download tells KJB its progress and its end, and the status follows it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler))) { progress ->
            progress(10, 100)
            gate.await()
            progress(100, 100)
            Download.Done
        }
        val heard = Heard()

        run.start(heard)
        runCurrent()
        assertEquals(RunState.Running(10, 100), run.state.value)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("progress 10/100", "progress 100/100", "end Done"), heard.events)
        assertEquals(RunState.Idle, run.state.value)
    }

    /** A download that waits at its own gate, one per run, after saying it got 1 byte. */
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val gated: suspend (suspend (Long, Long) -> Unit) -> Download = { progress ->
        val gate = CompletableDeferred<Unit>().also { gates += it }
        progress(1, 100)
        gate.await()
        Download.Done
    }

    @Test
    fun `a run cancelled as the next one starts tells only its own caller, and leaves the next run alone`() = runTest {
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler)), download = gated)
        val first = Heard()
        val second = Heard()
        val a = run.start(first)
        runCurrent()

        // KJB's worker replaced: the old one stops its run, the new one starts before the old run has wound down.
        run.cancel(a)
        val b = run.start(second)
        runCurrent()

        assertTrue(a != b)
        assertEquals(1, first.events.count { it.startsWith("end Interrupted") })
        assertFalse(second.events.toString(), second.events.any { it.startsWith("end") })
        assertTrue(run.state.value is RunState.Running)
        gates.last().complete(Unit)
        runCurrent()
        assertEquals("end Done", second.events.last())
        assertEquals(1, first.events.count { it.startsWith("end") })
        assertEquals(RunState.Idle, run.state.value)
    }

    @Test
    fun `each start is its own run, and cancelling one leaves the other going`() = runTest {
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler)), download = gated)
        val first = Heard()
        val second = Heard()
        val a = run.start(first)
        run.start(second)
        runCurrent()

        run.cancel(a)
        runCurrent()

        assertTrue(first.events.last().startsWith("end Interrupted"))
        assertFalse(second.events.any { it.startsWith("end") })
        gates.last().complete(Unit)
        runCurrent()
        assertEquals("end Done", second.events.last())
    }

    @Test
    fun `stopped, because KJB asked or went away, it ends as stopped`() = runTest {
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler))) { progress ->
            progress(10, 100)
            CompletableDeferred<Unit>().await()
            Download.Done
        }
        val heard = Heard()
        val id = run.start(heard)
        runCurrent()

        run.cancel(id)
        runCurrent()

        assertTrue(heard.events.toString(), heard.events.last().startsWith("end Interrupted"))
        assertEquals(RunState.Idle, run.state.value)
    }

    @Test
    fun `a download that failed for good says so until the next start`() = runTest {
        var result: Download = Download.Failed("checksum mismatch")
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler))) { result }

        run.start(Heard())
        runCurrent()
        assertEquals(RunState.Failed, run.state.value)

        result = Download.Done
        run.start(Heard())
        runCurrent()
        assertEquals(RunState.Idle, run.state.value)
    }

    // Its beats, and the add-on's foreground service while it runs (the logic: :addon-shared's AddonKeepAliveTest)

    @Test
    fun `a run beats at once and every 5 s until its end, whatever it waits on, and not after`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler)), beats = StandardTestDispatcher(testScheduler)) { progress ->
            progress(10, 100)
            gate.await() // a slow server, or the files of a run stopped a moment ago
            Download.Done
        }
        val heard = Heard()
        run.start(heard)
        runCurrent()
        assertEquals(1, heard.beats)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("one at once, then one every 5 s", 13, heard.beats)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("end Done", heard.events.last())
        assertEquals("none once it ended", 13, heard.beats)
    }

    /** The add-on's foreground service as Android sees it: what the service asked of it, in order. */
    private class Foreground : ForegroundPort<String> {
        val calls = mutableListOf<String>()

        override fun startForeground(type: Int, tap: String?) {
            calls += if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) "start dataSync" else "start $type"
        }

        override fun stopForeground() {
            calls += "stopForeground"
        }

        override fun stopSelf(startId: Int?) {
            calls += "stopSelf ${startId ?: "all"}"
        }

        override fun updateNotice(tap: String?) {
            calls += "notice"
        }
    }

    /**
     * The download and the add-on's one foreground service, wired as AiService wires them, no song's job running
     * (AiServiceTest runs one beside it).
     */
    private fun TestScope.wired(port: Foreground, download: suspend (suspend (Long, Long) -> Unit) -> Download): Pair<DownloadRun, JobForeground<String>> {
        lateinit var foreground: JobForeground<String>
        val run = DownloadRun(
            backgroundScope.plus(StandardTestDispatcher(testScheduler)),
            moved = { foreground.noticeChanged() },
            ended = { foreground.jobEnded() },
            download = download,
        )
        foreground = JobForeground(listOf(downloadWork(run)), port, fromKjb = { it == KJB })
        return run to foreground
    }

    @Test
    fun `the add-on's foreground service holds from KJB's start to the download's end, its notice following the progress`() = runTest {
        val port = Foreground()
        val halfway = CompletableDeferred<Unit>()
        val whole = CompletableDeferred<Unit>()
        val (run, foreground) = wired(port) { progress ->
            progress(10, 100) // before KJB's start: nothing to post the notice into yet
            halfway.await()
            progress(50, 100)
            whole.await()
            progress(100, 100)
            Download.Done
        }
        val heard = Heard()
        run.start(heard)
        runCurrent()
        assertTrue(run.busy)

        foreground.start(1, KJB) // KJB's start, once the add-on took the download
        halfway.complete(Unit)
        runCurrent()
        assertEquals(RunState.Running(50, 100), run.state.value)
        whole.complete(Unit)
        runCurrent()

        assertEquals("end Done", heard.events.last())
        assertFalse(run.busy)
        assertEquals(listOf("start dataSync", "notice", "notice", "stopForeground", "stopSelf 1"), port.calls)
    }

    @Test
    fun `every end of a download leaves the foreground, done, failed, stopped for later, cancelled or KJB gone`() = runTest {
        val ends = listOf<Pair<String, (DownloadRun, JobForeground<String>, Long) -> Unit>>(
            "done" to { _, _, _ -> gates.last().complete(Unit) },
            "KJB's cancel" to { run, _, id -> run.cancel(id) },
            "KJB gone" to { run, _, _ -> run.cancelAll() },
        )
        for ((way, end) in ends) {
            val port = Foreground()
            val (run, foreground) = wired(port, gated)
            val heard = Heard()
            val id = run.start(heard)
            runCurrent()
            foreground.start(1, KJB)

            end(run, foreground, id)
            runCurrent()

            assertEquals(way, 1, heard.events.count { it.startsWith("end") })
            assertFalse(way, run.busy)
            assertEquals(way, listOf("start dataSync", "stopForeground", "stopSelf 1"), port.calls)
        }
        for (told in listOf(Download.Failed("checksum mismatch"), Download.Interrupted("network"))) {
            val port = Foreground()
            val gate = CompletableDeferred<Unit>()
            val (run, foreground) = wired(port) { gate.await(); told }
            val heard = Heard()
            run.start(heard)
            runCurrent()
            foreground.start(1, KJB)

            gate.complete(Unit)
            runCurrent()

            assertEquals("end $told", heard.events.single())
            assertEquals("$told", listOf("start dataSync", "stopForeground", "stopSelf 1"), port.calls)
        }
    }

    @Test
    fun `Android's time running out stops the download for later and leaves the foreground there and then`() = runTest {
        val port = Foreground()
        val (run, foreground) = wired(port, gated)
        val heard = Heard()
        run.start(heard)
        runCurrent()
        foreground.start(1, KJB)

        foreground.timeUp(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        runCurrent()

        assertTrue(heard.events.toString(), heard.events.last().startsWith("end Interrupted"))
        assertFalse(run.busy)
        assertEquals(listOf("start dataSync", "stopForeground", "stopSelf all"), port.calls)
    }

    @Test
    fun `abandonDownload heard late stops only the run it names, and KJB's next run goes on in the foreground`() = runTest {
        val port = Foreground()
        val (run, foreground) = wired(port, gated)
        val first = Heard()
        val abandoned = run.start(first)
        runCurrent()
        foreground.start(1, KJB)
        // The phone froze the add-on: KJB gave up on that run (abandonDownload, one way, which waits in the binder until
        // the add-on runs again), then Allow started the next run, and the add-on's service for it, before the word came.
        val second = Heard()
        run.start(second)
        runCurrent()
        foreground.start(2, KJB)
        port.calls.clear()

        // The word comes in: AiService's abandonDownload, a binder call no JVM test reaches, is downloads.cancel(run).
        run.cancel(abandoned)
        runCurrent()

        assertTrue(first.events.toString(), first.events.last().startsWith("end Interrupted"))
        assertFalse(second.events.toString(), second.events.any { it.startsWith("end") })
        assertTrue(run.busy)
        assertEquals("the status follows the next run", RunState.Running(1, 100), run.state.value)
        assertEquals("still in the foreground for the next run", emptyList<String>(), port.calls)

        // Said again (KJB froze it twice), or for no run at all: nothing more.
        run.cancel(abandoned)
        run.cancel(0)
        runCurrent()
        assertEquals(1, first.events.count { it.startsWith("end") })
        assertFalse(second.events.any { it.startsWith("end") })

        gates.last().complete(Unit)
        runCurrent()
        assertEquals("end Done", second.events.last())
        assertEquals(listOf("stopForeground", "stopSelf 2"), port.calls)
    }

    @Test
    fun `abandonDownload for a run that ended before the word came leaves the foreground and the next run as they are`() = runTest {
        val port = Foreground()
        val (run, foreground) = wired(port, gated)
        val first = Heard()
        val ended = run.start(first)
        runCurrent()
        foreground.start(1, KJB)
        // The run ended just as the phone froze the add-on, its end not heard by KJB, which gave up on it.
        gates.last().complete(Unit)
        runCurrent()
        assertEquals("end Done", first.events.last())
        val second = Heard()
        run.start(second)
        runCurrent()
        foreground.start(2, KJB)
        port.calls.clear()

        run.cancel(ended)
        runCurrent()

        assertEquals(1, first.events.count { it.startsWith("end") })
        assertFalse(second.events.any { it.startsWith("end") })
        assertTrue(run.busy)
        assertEquals(emptyList<String>(), port.calls)
        gates.last().complete(Unit)
        runCurrent()
        assertEquals("end Done", second.events.last())
    }

    @Test
    fun `after the add-on's process restarts, a start with no download goes in and out, and the next download holds it anew`() = runTest {
        // A new process: nothing of the one that died, whose foreground went with it.
        val port = Foreground()
        val (run, foreground) = wired(port, gated)
        foreground.start(1, KJB)
        assertEquals("no download yet: in and out at once, as Android requires", listOf("start dataSync", "stopForeground", "stopSelf 1"), port.calls)

        port.calls.clear()
        val heard = Heard()
        run.start(heard) // KJB's retry, going on from the parts
        runCurrent()
        foreground.start(2, KJB)
        gates.last().complete(Unit)
        runCurrent()

        assertEquals("end Done", heard.events.last())
        assertEquals(listOf("start dataSync", "stopForeground", "stopSelf 2"), port.calls)
    }

    // Against a local server: KJB swiped away mid-file, then bound again

    private val body = ByteArray(400_000) { ((it * 31 + 3) % 251).toByte() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var server: HttpServer? = null

    /** With [cutFirst], the first whole-file request stops after half the file until [released]; others get what they ask. */
    private val released = CountDownLatch(1)
    @Volatile
    private var cutFirst = true

    private fun serve(): String {
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.executor = java.util.concurrent.Executors.newCachedThreadPool()
        created.createContext("/") { exchange: HttpExchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                requests += range.orEmpty()
                val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                exchange.sendResponseHeaders(if (range != null) 206 else 200, (body.size - from).toLong())
                if (range == null && cutFirst) {
                    cutFirst = false
                    exchange.responseBody.write(body, 0, body.size / 2)
                    exchange.responseBody.flush()
                    released.await(30, TimeUnit.SECONDS)
                } else {
                    exchange.responseBody.write(body, from, body.size - from)
                }
            } catch (_: java.io.IOException) {
                // The client went away.
            } finally {
                exchange.close()
            }
        }
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}"
    }

    @After
    fun stop() {
        released.countDown()
        server?.stop(0)
    }

    private val loopback = object : ModelHosts {
        override fun isSource(uri: URI): Boolean = uri.host == "127.0.0.1"

        override fun isRedirect(uri: URI): Boolean = uri.host == "127.0.0.1"
    }

    private fun store(base: String, sha: String = sha256(body)) =
        ModelStore(File(folder.root, "models"), listOf(ModelSpec("model.onnx", "$base/model.onnx", sha, body.size.toLong())), File(folder.root, "cache"))

    private fun run(scope: CoroutineScope, store: ModelStore) = DownloadRun(scope) { progress ->
        retryWhileProgressing(store::bytesOnDisk) {
            ModelDownloader(store, userAgent = "KJB-AI/test", hosts = loopback, io = Dispatchers.IO, freeBytes = { Long.MAX_VALUE }).downloadAll(progress)
        }
    }

    @Test
    fun `cut off when KJB goes away, the next bind goes on from the part and checks the SHA-256 at the end`() = runBlocking {
        val base = serve()
        val store = store(base)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val halfway = CompletableDeferred<Unit>()
        val first = run(scope, store)
        val id = first.start(object : DownloadEvents {
            override fun onProgress(done: Long, total: Long) {
                if (done >= body.size / 2) halfway.complete(Unit)
            }

            override fun onEnd(end: Download) {}

            override fun onBeat() {}
        })
        withTimeout(30_000) { halfway.await() }

        // KJB swiped away: its binding goes, and the add-on stops the download.
        first.cancel(id)
        scope.cancel()
        released.countDown()
        withTimeout(30_000) { first.state.first { it == RunState.Idle } }
        val part = store.part(store.specs.single())
        assertEquals((body.size / 2).toLong(), part.length())
        assertFalse(store.isReady())

        // Bound again: from the part on, then the whole file checked.
        val heard = Heard()
        run(CoroutineScope(SupervisorJob() + Dispatchers.IO), store).start(heard)
        assertEquals(Download.Done, withTimeout(30_000) { heard.ended.await() })
        assertEquals(listOf("", "bytes=${body.size / 2}-"), requests)
        assertTrue(store.isReady())
        assertArrayEquals(body, store.file(store.specs.single()).readBytes())
    }

    @Test
    fun `a run KJB gave up on as frozen stops once the add-on runs again, though KJB's page keeps it bound, and the next run goes on from its part`() = runBlocking {
        val base = serve()
        val store = store(base)
        // The service KJB's AI page keeps bound: no onUnbind, no onDestroy, its scope lives on.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val port = Foreground()
        val left = CompletableDeferred<Unit>()
        lateinit var foreground: JobForeground<String>
        val run = DownloadRun(
            scope,
            ended = {
                foreground.jobEnded()
                left.complete(Unit)
            },
        ) { progress ->
            retryWhileProgressing(store::bytesOnDisk) {
                ModelDownloader(store, userAgent = "KJB-AI/test", hosts = loopback, io = Dispatchers.IO, freeBytes = { Long.MAX_VALUE }).downloadAll(progress)
            }
        }
        foreground = JobForeground(listOf(downloadWork(run)), port, fromKjb = { it == KJB })
        val halfway = CompletableDeferred<Unit>()
        val first = object : DownloadEvents {
            val ended = CompletableDeferred<Download>()

            override fun onProgress(done: Long, total: Long) {
                if (done >= body.size / 2) halfway.complete(Unit)
            }

            override fun onEnd(end: Download) {
                ended.complete(end)
            }

            override fun onBeat() {}
        }
        val id = run.start(first)
        foreground.start(1, KJB)
        withTimeout(30_000) { halfway.await() }

        // The phone froze the add-on; KJB heard no beat for 30 s, unbound it, stopped its service and said abandonDownload
        // one way, which the add-on hears once it runs again.
        run.cancel(id)
        released.countDown()
        assertTrue(withTimeout(30_000) { first.ended.await() } is Download.Interrupted)
        withTimeout(30_000) { left.await() }
        assertFalse(run.busy)
        assertEquals(listOf("start dataSync", "stopForeground", "stopSelf 1"), port.calls)
        assertEquals((body.size / 2).toLong(), store.part(store.specs.single()).length())

        // Allow, or KJB's retry: the same service goes on from the part, then checks the whole file.
        val second = Heard()
        run.start(second)
        assertEquals(Download.Done, withTimeout(30_000) { second.ended.await() })
        assertEquals(listOf("", "bytes=${body.size / 2}-"), requests)
        assertTrue(store.isReady())
        scope.cancel()
    }

    @Test
    fun `a part torn when KJB was killed fails the check at the end, and is fetched once whole before giving up`() = runBlocking {
        val base = serve()
        cutFirst = false
        val store = store(base)
        val part = store.part(store.specs.single())
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(body.size / 2) { 9 }) // the right size, the wrong bytes

        val heard = Heard()
        run(CoroutineScope(SupervisorJob() + Dispatchers.IO), store).start(heard)

        assertEquals(Download.Done, withTimeout(30_000) { heard.ended.await() })
        assertEquals(listOf("bytes=${body.size / 2}-", ""), requests)
        assertArrayEquals(body, store.file(store.specs.single()).readBytes())
    }

    // The download's foreground service and notice are the add-on's one service's, its schedule KJB's

    @Test
    fun `the download holds the add-on's one foreground service and notice, never one of its own, and KJB schedules it`() {
        // Its foreground service and notice are the song's jobs' (ai/AiService.kt, JobForeground), and it asks for no
        // notification permission; WorkManager, and the download's schedule and own notification, are KJB's.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf("POST_NOTIFICATIONS", "SystemForegroundService").forEach { word -> assertFalse(word, word in manifest) }
        val download = File("src/main/java/app/lyrify/models").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("Notification.Builder", "NotificationManager", "setForeground", "startForeground").forEach { word ->
            assertFalse(word, word in download)
        }
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("androidx.work" in sources)
    }

    private companion object {
        /** KJB's tap on the notice, the proof that a start is KJB's. */
        const val KJB = "KJB's AI page"
    }
}
