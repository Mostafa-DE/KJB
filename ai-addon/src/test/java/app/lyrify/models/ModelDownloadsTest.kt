package app.lyrify.models

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
        assertEquals(RunState.Running(10), run.state.value)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("progress 10/100", "progress 100/100", "end Done"), heard.events)
        assertEquals(RunState.Idle, run.state.value)
    }

    @Test
    fun `asked again while it runs it does not start a second download, and the new caller hears it`() = runTest {
        var started = 0
        val gate = CompletableDeferred<Unit>()
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler))) {
            started++
            gate.await()
            Download.Done
        }
        val first = Heard()
        val second = Heard()

        run.start(first)
        runCurrent()
        run.start(second)
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, started)
        assertEquals(listOf("end Done"), second.events)
    }

    @Test
    fun `stopped, because KJB asked or went away, it ends as stopped`() = runTest {
        val run = DownloadRun(backgroundScope.plus(StandardTestDispatcher(testScheduler))) { progress ->
            progress(10, 100)
            CompletableDeferred<Unit>().await()
            Download.Done
        }
        val heard = Heard()
        run.start(heard)
        runCurrent()

        run.cancel()
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

    // Against a local server: KJB swiped away mid-file, then bound again

    private val body = ByteArray(400_000) { ((it * 31 + 3) % 251).toByte() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var server: HttpServer? = null

    /** The first request stops after half the file until [released]; later ones get the rest as asked (HTTP Range). */
    private val released = CountDownLatch(1)

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
                if (range == null) {
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
        first.start(object : DownloadEvents {
            override fun onProgress(done: Long, total: Long) {
                if (done >= body.size / 2) halfway.complete(Unit)
            }

            override fun onEnd(end: Download) {}
        })
        withTimeout(30_000) { halfway.await() }

        // KJB swiped away: its binding goes, and the add-on stops the download.
        first.cancel()
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
    fun `a part that is not what it should be fails the check at the end and is deleted`() = runBlocking {
        val base = serve()
        val store = store(base)
        val part = store.part(store.specs.single())
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(body.size / 2) { 9 }) // the right size, the wrong bytes

        val heard = Heard()
        run(CoroutineScope(SupervisorJob() + Dispatchers.IO), store).start(heard)

        val end = withTimeout(30_000) { heard.ended.await() }
        assertTrue(end.toString(), end is Download.Failed)
        assertFalse(part.exists())
        assertFalse(store.isReady())
    }

    // The add-on itself shows nothing

    @Test
    fun `the add-on runs no foreground service and posts no notification, KJB shows its download`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf("FOREGROUND_SERVICE", "POST_NOTIFICATIONS", "SystemForegroundService", "foregroundServiceType").forEach { word ->
            assertFalse(word, word in manifest)
        }
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("Notification.Builder", "NotificationManager", "setForeground", "startForeground", "androidx.work").forEach { word ->
            assertFalse(word, word in sources)
        }
    }
}
