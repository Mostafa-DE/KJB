package app.lyrify.lyrics

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LrclibClientTest {
    private val vaelJson = """
        {"id":42,"name":"Vael","trackName":"Vael","artistName":"Quillmere","albumName":"Ulso",
         "duration":276.0,"instrumental":false,"plainLyrics":"a\nb","syncedLyrics":"[00:01.00]a","future":1}
    """.trimIndent()

    @Test
    fun `a record is read and unknown fields are ignored`() {
        val record = LrclibJson.record(vaelJson)
        assertEquals(42L, record.id)
        assertEquals("Vael", record.trackName)
        assertEquals("Quillmere", record.artistName)
        assertEquals(276.0, record.duration ?: 0.0, 0.0)
        assertEquals("[00:01.00]a", record.syncedLyrics)
    }

    @Test
    fun `missing and null fields take their defaults`() {
        val record = LrclibJson.record("""{"id":7,"trackName":null,"instrumental":null,"duration":null}""")
        assertEquals(LrclibRecord(id = 7), record)
    }

    @Test
    fun `a search result is a list`() {
        assertEquals(listOf(42L, 42L), LrclibJson.records("[$vaelJson,$vaelJson]").map { it.id })
        assertEquals(emptyList<LrclibRecord>(), LrclibJson.records("[]"))
    }

    @Test
    fun `200 with a record is found`() {
        assertEquals(LrclibResult.Found(listOf(LrclibJson.record(vaelJson))), LrclibResponses.get(200, vaelJson))
        assertEquals(LrclibResult.Found(emptyList()), LrclibResponses.search(200, "[]"))
    }

    @Test
    fun `404 on get is not found`() {
        assertEquals(LrclibResult.NotFound, LrclibResponses.get(404, """{"code":404,"name":"TrackNotFound"}"""))
    }

    @Test
    fun `a request LRCLIB refuses (400, 404, 410, 414, 422) is not found for good, never busy`() {
        assertEquals(LrclibResult.NotFound, LrclibResponses.get(400, """{"code":400,"name":"BadRequest"}"""))
        assertEquals(LrclibResult.NotFound, LrclibResponses.search(400, null))
        for (code in listOf(404, 410, 414, 422)) {
            assertEquals("get $code", LrclibResult.NotFound, LrclibResponses.get(code, null))
            assertEquals("search $code", LrclibResult.NotFound, LrclibResponses.search(code, ""))
        }
    }

    @Test
    fun `any other 4xx is busy, a CDN's 403 for bots or too many requests included`() {
        for (code in listOf(401, 403, 405, 408, 409, 418, 429, 451)) {
            assertTrue("get $code", LrclibResponses.get(code, null) is LrclibResult.Transient)
            assertTrue("search $code", LrclibResponses.search(code, "") is LrclibResult.Transient)
        }
    }

    @Test
    fun `redirects are transient`() {
        assertTrue(LrclibResponses.get(301, null) is LrclibResult.Transient)
        assertTrue(LrclibResponses.search(302, "[]") is LrclibResult.Transient)
    }

    @Test
    fun `anything else is transient`() {
        assertTrue(LrclibResponses.get(503, "<html>busy</html>") is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(500, null) is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(429, "") is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(408, null) is LrclibResult.Transient)
        assertTrue(LrclibResponses.search(429, null) is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(200, "<html>overloaded</html>") is LrclibResult.Transient)
        assertTrue(LrclibResponses.search(200, "{\"not\":\"a list\"}") is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(200, null) is LrclibResult.Transient)
    }

    @Test
    fun `the get url carries only artist, title, album and duration`() {
        assertEquals(
            "https://lrclib.net/api/get?artist_name=Sala+de+Ux&track_name=Fiesta+lunar+%282021%29&album_name=Finmarca&duration=276",
            LrclibUrls.get("https://lrclib.net", "Sala de Ux", "Fiesta lunar (2021)", "Finmarca", 276),
        )
        assertEquals(
            "https://lrclib.net/api/get?artist_name=XQ%2FZV&track_name=K.L.M.&duration=215",
            LrclibUrls.get("https://lrclib.net", "XQ/ZV", "K.L.M.", null, 215),
        )
    }

    @Test
    fun `a record is fetched by its id alone`() {
        assertEquals("https://lrclib.net/api/get/9061", LrclibUrls.byId("https://lrclib.net", 9061))
    }

    @Test
    fun `search uses names, or the title alone without an artist`() {
        assertEquals(
            "https://lrclib.net/api/search?track_name=Nachtf%C3%A4hre&artist_name=Kalvaron",
            LrclibUrls.search("https://lrclib.net", "Nachtfähre", "Kalvaron"),
        )
        assertEquals("https://lrclib.net/api/search?q=Brennmoor+-+Ten", LrclibUrls.search("https://lrclib.net", "Brennmoor - Ten", null))
    }

    @Test
    fun `requests run one at a time at least the spacing apart`() = runTest {
        val gate = RequestGate(now = { testScheduler.currentTime })
        val spans = mutableListOf<LongRange>()
        suspend fun call() = gate.run(spacingMs = 500) {
            val start = testScheduler.currentTime
            delay(300)
            spans += start..testScheduler.currentTime
        }
        val a = async { call() }
        val b = async { call() }
        val c = async { call() }
        a.await()
        b.await()
        c.await()
        assertEquals(listOf(0L..300L, 800L..1_100L, 1_600L..1_900L), spans)
    }

    @Test
    fun `a request long after the last one starts at once`() = runTest {
        val gate = RequestGate(now = { testScheduler.currentTime })
        gate.run(spacingMs = 500) { }
        delay(2_000)
        val before = testScheduler.currentTime
        gate.run(spacingMs = 500) { }
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `low lane requests start 1,5 s after the previous one, high lane 500 ms`() = runTest {
        val gate = RequestGate(now = { testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        suspend fun call(lane: Lane) = gate.run(lane.spacingMs) {
            starts += testScheduler.currentTime
            delay(100)
        }
        call(Lane.LOW)
        call(Lane.LOW)
        call(Lane.HIGH)
        call(Lane.LOW)
        assertEquals(listOf(0L, 1_600L, 2_200L, 3_800L), starts)
    }

    // Response bodies

    @Test
    fun `a body up to the cap is read whole, a larger one not at all`() {
        assertArrayEquals(ByteArray(10) { it.toByte() }, readCapped(ByteArrayInputStream(ByteArray(10) { it.toByte() }), maxBytes = 10))
        assertNull(readCapped(ByteArrayInputStream(ByteArray(11)), maxBytes = 10))
        assertArrayEquals(ByteArray(0), readCapped(ByteArrayInputStream(ByteArray(0)), maxBytes = 10))
    }

    @Test
    fun `bodies are capped at 2 MiB`() {
        assertEquals(2 * 1024 * 1024, HttpLrclibClient.MAX_BODY_BYTES)
    }

    // Against a local server

    private fun serve(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            }
            start()
        }

    private fun HttpServer.client() = HttpLrclibClient(userAgent = "KJB/test", base = "http://127.0.0.1:${address.port}")

    private fun HttpExchange.reply(code: Int, body: ByteArray) {
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    @Test
    fun `a record from the server is found`() = runTest {
        val server = serve { it.reply(200, "[$vaelJson]".encodeToByteArray()) }
        try {
            assertEquals(LrclibResult.Found(listOf(LrclibJson.record(vaelJson))), server.client().search("Vael", "Quillmere", Lane.HIGH))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `server errors are transient`() = runTest {
        val server = serve { it.reply(503, "<html>busy</html>".encodeToByteArray()) }
        try {
            val client = server.client()
            assertTrue(client.get("Quillmere", "Vael", null, 276, Lane.HIGH) is LrclibResult.Transient)
            assertTrue(client.search("Vael", null, Lane.HIGH) is LrclibResult.Transient)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a refused request is not found, and the log names its status only`() = runTest {
        val server = serve { it.reply(400, """{"code":400,"name":"BadRequest"}""".encodeToByteArray()) }
        val logs = mutableListOf<String>()
        try {
            val client = HttpLrclibClient(userAgent = "KJB/test", base = "http://127.0.0.1:${server.address.port}", log = { logs += it })
            assertEquals(LrclibResult.NotFound, client.get("Quillmere", "Vael", "Ulso", 276, Lane.HIGH))
            assertEquals(LrclibResult.NotFound, client.search("Vael", "Quillmere", Lane.HIGH))
            assertEquals(2, logs.size)
            assertTrue(logs.all { "HTTP 400" in it })
            assertTrue(logs.none { "Vael" in it || "Quillmere" in it || "Ulso" in it || "127.0.0.1" in it || "/api" in it })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a record by id comes from get by id, and a missing one is not found`() = runTest {
        val paths = mutableListOf<String>()
        val server = serve { exchange ->
            paths += exchange.requestURI.path
            if (exchange.requestURI.path == "/api/get/42") exchange.reply(200, vaelJson.encodeToByteArray()) else exchange.reply(404, ByteArray(0))
        }
        try {
            val client = server.client()
            assertEquals(LrclibResult.Found(listOf(LrclibJson.record(vaelJson))), client.byId(42, Lane.LOW))
            assertEquals(LrclibResult.NotFound, client.byId(43, Lane.LOW))
            assertEquals(listOf("/api/get/42", "/api/get/43"), paths)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a 404 from get is not found`() = runTest {
        val server = serve { it.reply(404, """{"code":404}""".encodeToByteArray()) }
        try {
            assertEquals(LrclibResult.NotFound, server.client().get("Quillmere", "Vael", null, 276, Lane.HIGH))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `redirects are not followed`() = runTest {
        var elsewhere = 0
        val server = serve { exchange ->
            if (exchange.requestURI.path == "/elsewhere") {
                elsewhere++
                exchange.reply(200, "[$vaelJson]".encodeToByteArray())
            } else {
                exchange.responseHeaders.add("Location", "/elsewhere")
                exchange.reply(302, ByteArray(0))
            }
        }
        try {
            assertTrue(server.client().search("Vael", "Quillmere", Lane.HIGH) is LrclibResult.Transient)
            assertEquals(0, elsewhere)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a body over the cap is transient`() = runTest {
        val huge = "[" + List(30_000) { vaelJson }.joinToString(",") + "]"
        assertTrue(huge.length > HttpLrclibClient.MAX_BODY_BYTES)
        val server = serve { it.reply(200, huge.encodeToByteArray()) }
        try {
            assertTrue(server.client().search("Vael", "Quillmere", Lane.HIGH) is LrclibResult.Transient)
        } finally {
            server.stop(0)
        }
    }

    // What a failure says

    /** A connection whose answer fails with a message that quotes the address, as HttpURLConnection's often do. */
    private class FailingConnection(private val address: String) : HttpURLConnection(URI("http://127.0.0.1/").toURL()) {
        override fun getResponseCode(): Int = throw SocketException("Connection reset by peer: $address")

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }

    @Test
    fun `a failed request says only the exception's kind, never the address with the song in it`() = runTest {
        val unopened = HttpLrclibClient(
            userAgent = "KJB/test",
            gate = RequestGate(now = { testScheduler.currentTime }),
            open = { url -> throw IOException("no route to $url") },
        )
        assertEquals(LrclibResult.Transient("IOException"), unopened.get("Quillmere", "Vael", "Ulso", 276, Lane.HIGH))
        val unanswered = HttpLrclibClient(
            userAgent = "KJB/test",
            gate = RequestGate(now = { testScheduler.currentTime }),
            open = { url -> FailingConnection(url) },
        )
        assertEquals(LrclibResult.Transient("SocketException"), unanswered.search("Vael", "Quillmere", Lane.HIGH))
    }

    // Cancelling a request

    /** A connection whose answer never comes: its read only ends when the connection is closed (or after 30 s). */
    private class StuckConnection : HttpURLConnection(URI("http://127.0.0.1/").toURL()) {
        val reading = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        @Volatile
        var timedOut = false

        override fun getResponseCode(): Int {
            reading.countDown()
            if (!closed.await(30, TimeUnit.SECONDS)) timedOut = true
            throw SocketException("Socket closed")
        }

        override fun disconnect() = closed.countDown()

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }

    private class AnsweringConnection(private val body: String) : HttpURLConnection(URI("http://127.0.0.1/").toURL()) {
        override fun getResponseCode(): Int = HTTP_OK

        override fun getInputStream(): InputStream = ByteArrayInputStream(body.encodeToByteArray())

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }

    @Test
    fun `a cancelled request is disconnected and frees the gate at once`() = runTest {
        val stuck = StuckConnection()
        val connections = ArrayDeque(listOf(stuck, AnsweringConnection("[$vaelJson]")))
        val client = HttpLrclibClient(
            userAgent = "KJB/test",
            gate = RequestGate(now = { testScheduler.currentTime }),
            open = { connections.removeFirst() },
        )
        val dropped = launch { client.search("Vael", "Quillmere", Lane.HIGH) }
        runCurrent()
        assertTrue(stuck.reading.await(10, TimeUnit.SECONDS))

        dropped.cancelAndJoin()

        assertFalse(stuck.timedOut)
        assertEquals(LrclibResult.Found(listOf(LrclibJson.record(vaelJson))), client.search("Vael", "Quillmere", Lane.HIGH))
    }
}
