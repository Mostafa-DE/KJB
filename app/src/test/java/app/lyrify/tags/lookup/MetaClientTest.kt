package app.lyrify.tags.lookup

import app.lyrify.USER_AGENT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class MetaClientTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val json = URI("https://musicbrainz.org/ws/2/release/$ID_A?fmt=json")
    private val cover = URI("https://coverartarchive.org/release/$ID_A/front-1200")
    private val archive = "https://archive.org/download/mbid-$ID_A/mbid-$ID_A-1_thumb1200.jpg"
    private val server = "https://dn721702.ca.archive.org/0/items/mbid-$ID_A/mbid-$ID_A-1_thumb1200.jpg"

    private fun TestScope.web() = FakeWeb(now = { testScheduler.currentTime })

    private fun TestScope.gate() = MetaGate(now = { testScheduler.currentTime }, log = { logs += it })

    private fun TestScope.client(web: FakeWeb, cache: MetaCache = MetaCache(folder.newFolder()), gate: MetaGate = gate(), agent: String? = TEST_AGENT) =
        if (agent == null) {
            MetaClient(cache, gate = gate, io = StandardTestDispatcher(testScheduler), open = web::open, log = { logs += it })
        } else {
            MetaClient(cache, userAgent = agent, gate = gate, io = StandardTestDispatcher(testScheduler), open = web::open, log = { logs += it })
        }

    private suspend fun MetaClient.text(uri: URI, fresh: Boolean = false, keep: (String) -> Boolean = { true }): MetaAnswer<String> =
        get(uri, Body.JSON, fresh, keep) { bytes -> bytes.decodeToString().also { require(it.startsWith("{")) } }

    private suspend fun MetaClient.image(uri: URI, fresh: Boolean = false): MetaAnswer<ByteArray> = get(uri, Body.IMAGE, fresh) { it }

    // Hosts

    @Test
    fun `requests start only at musicbrainz org, coverartarchive org and ListenBrainz's labs, over https`() {
        listOf(
            "https://musicbrainz.org/ws/2/release/?query=x&fmt=json",
            "https://coverartarchive.org/release/$ID_A/front-500",
            "https://MusicBrainz.org/ws/2/release/$ID_A",
            "https://musicbrainz.org:443/ws/2/",
            // Similar artists (similar/).
            "https://labs.api.listenbrainz.org/similar-artists/json?artist_mbids=$ID_A&algorithm=x",
        ).forEach { assertTrue(it, MetaHosts.isSource(URI(it))) }
        listOf(
            "http://labs.api.listenbrainz.org/similar-artists/json",
            "https://api.listenbrainz.org/1/metadata/lookup/",
            "https://listenbrainz.org/",
            "http://musicbrainz.org/ws/2/",
            "https://musicbrainz.org:8443/ws/2/",
            "https://beta.musicbrainz.org/ws/2/",
            "https://musicbrainz.org.example.com/ws/2/",
            "https://user@musicbrainz.org/ws/2/",
            "https://archive.org/download/a",
            "https://ia800204.us.archive.org/a",
            "https://example.com/",
            "ftp://coverartarchive.org/release/a",
        ).forEach { assertFalse(it, MetaHosts.isSource(URI(it))) }
    }

    @Test
    fun `redirects may lead to the cover art archive, archive org and its servers only`() {
        listOf(
            "https://archive.org/download/mbid-x/mbid-x-1_thumb500.jpg",
            "https://dn721702.ca.archive.org/0/items/mbid-x/a.jpg",
            "https://ia800204.us.archive.org/1/items/mbid-x/a.jpg",
            "https://coverartarchive.org/release/x/front-500",
        ).forEach { assertTrue(it, MetaHosts.isRedirect(URI(it))) }
        listOf(
            "http://archive.org/download/a",
            "https://archive.org.example.com/a",
            "https://notarchive.org/a",
            "https://archive.org:444/a",
            "https://user@archive.org/a",
            "https://example.com/a",
            "/relative",
        ).forEach { assertFalse(it, MetaHosts.isRedirect(URI(it))) }
    }

    // The gate

    @Test
    fun `requests run one at a time, each 1,1 s after the previous one ends`() = runTest {
        val gate = gate()
        val spans = mutableListOf<LongRange>()
        suspend fun call() = gate.send<Int>({ false }) {
            val start = testScheduler.currentTime
            delay(300)
            spans += start..testScheduler.currentTime
            200
        }
        val a = async { call() }
        val b = async { call() }
        val c = async { call() }
        a.await()
        b.await()
        c.await()
        assertEquals(listOf(0L..300L, 1_400L..1_700L, 2_800L..3_100L), spans)
    }

    @Test
    fun `a request long after the last one starts at once`() = runTest {
        val gate = gate()
        gate.send<Int>({ false }) { 200 }
        delay(5_000)
        val before = testScheduler.currentTime
        gate.send<Int>({ false }) { 200 }
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `every request and every redirect hop carries the User-Agent`() = runTest {
        val web = web()
        web.on(cover, redirect(307, archive))
        web.on(archive, redirect(302, server))
        web.on(server, ok(JPEG_BYTES))
        assertTrue(client(web).image(cover) is MetaAnswer.Ok)
        assertEquals(3, web.requests.size)
        assertTrue(web.requests.toString(), web.requests.all { it.userAgent == TEST_AGENT })
    }

    @Test
    fun `the app's User-Agent is the default`() = runTest {
        val web = web()
        web.on(json, ok("{}"))
        client(web, agent = null).text(json)
        assertEquals(USER_AGENT, web.requests.single().userAgent)
        assertTrue(USER_AGENT, Regex("""KJB/\S+ \( https://github\.com/Mostafa-DE/KJB \)""").matches(USER_AGENT))
    }

    @Test
    fun `a 503 waits 2 s then 4 s, holding the gate, and says busy meanwhile`() = runTest {
        val web = web()
        val other = URI("https://musicbrainz.org/ws/2/release-group/$GROUP_A?fmt=json")
        web.on(json, status(503), status(503), ok("{}"))
        web.on(other, ok("{}"))
        val gate = gate()
        val client = client(web, gate = gate)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gate.busy.collect { seen += it } }

        val first = async { client.text(json) }
        val second = async { client.text(other) }

        assertEquals(MetaAnswer.Ok("{}"), first.await())
        assertEquals(MetaAnswer.Ok("{}"), second.await())
        assertEquals(listOf(0L, 2_000L, 6_000L, 7_100L), web.requests.map { it.at })
        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun `still busy after the 60 s wait is busy, and is not cached`() = runTest {
        val web = web()
        web.on(json, status(503))
        val client = client(web)

        assertEquals(MetaAnswer.Busy, client.text(json))
        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 62_000L, 122_000L), web.requests.map { it.at })
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L), MetaGate.BACKOFF_MS)

        web.on(json, ok("{}"))
        assertEquals(MetaAnswer.Ok("{}"), client.text(json))
    }

    // The cache

    @Test
    fun `a success is cached, and Check again asks again`() = runTest {
        val web = web()
        web.on(json, ok("""{"a":1}"""), ok("""{"a":2}"""))
        val client = client(web)

        assertEquals(MetaAnswer.Ok("""{"a":1}"""), client.text(json))
        assertEquals(MetaAnswer.Ok("""{"a":1}""", fromCache = true), client.text(json))
        assertEquals(1, web.requests.size)

        assertEquals(MetaAnswer.Ok("""{"a":2}"""), client.text(json, fresh = true))
        assertEquals(2, web.requests.size)
        assertEquals(MetaAnswer.Ok("""{"a":2}""", fromCache = true), client.text(json))
    }

    @Test
    fun `errors, 404s, unreadable and empty answers are never cached`() = runTest {
        val web = web()
        web.on(json, status(404), status(500), ok("<html>"), ok("{}"), ok("""{"a":1}"""))
        val client = client(web)

        assertEquals(MetaAnswer.NotFound, client.text(json))
        assertTrue(client.text(json) is MetaAnswer.Failed)
        assertTrue(client.text(json) is MetaAnswer.Failed)
        assertEquals(MetaAnswer.Ok("{}"), client.text(json, keep = { it != "{}" }))
        assertEquals(MetaAnswer.Ok("""{"a":1}"""), client.text(json))
        assertEquals(5, web.requests.size)
    }

    @Test
    fun `a failed answer carries the status the server answered with, 200 for a body that did not read, none when it never answered`() = runTest {
        val web = web()
        web.on(json, status(500), ok("<html>"), FakeWeb.Reply(200, error = IOException("made-up")))
        val client = client(web)

        assertEquals(listOf<Int?>(500, 200, null), List(3) { (client.text(json) as MetaAnswer.Failed).status })
    }

    @Test
    fun `an unreadable cached answer is dropped and asked again`() = runTest {
        val web = web()
        web.on(json, ok("""{"a":1}"""))
        val cache = MetaCache(folder.newFolder())
        cache.put(json.toString(), "<html>".encodeToByteArray())

        assertEquals(MetaAnswer.Ok("""{"a":1}"""), client(web, cache).text(json))
        assertEquals(1, web.requests.size)
        assertArrayEquals("""{"a":1}""".encodeToByteArray(), cache.get(json.toString()))
    }

    @Test
    fun `answers are read off the caller's thread, from the network and from the cache`() = runTest {
        val web = web()
        web.on(json, ok("""{"a":1}"""))
        val io = namedThread("meta-io")
        try {
            val client = MetaClient(MetaCache(folder.newFolder()), userAgent = TEST_AGENT, gate = gate(), io = io, open = web::open)
            val readers = mutableListOf<String>()
            val read: (ByteArray) -> String = { bytes -> synchronized(readers) { readers += Thread.currentThread().name }; bytes.decodeToString() }

            client.get(json, Body.JSON, read = read)
            client.get(json, Body.JSON, read = read)

            assertEquals(listOf("meta-io", "meta-io"), readers)
        } finally {
            io.close()
        }
    }

    @Test
    fun `a second write of the same query waits for the first, so the cache never keeps half of each`() {
        val key = "https://musicbrainz.org/ws/2/release/?query=Harbour"
        val ones = ByteArray(256 * 1024) { 1 }
        val twos = ByteArray(256 * 1024) { 2 }
        val halfway = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        // The first write stops halfway through its file until told to go on; the second writes at once.
        val cache = MetaCache(folder.newFolder(), budgetBytes = 1024 * 1024) { file, body ->
            if (body === ones) {
                FileOutputStream(file).use { out ->
                    out.write(body, 0, body.size / 2)
                    halfway.countDown()
                    goOn.await()
                    out.write(body, body.size / 2, body.size - body.size / 2)
                }
            } else {
                file.writeBytes(body)
            }
        }
        // The cache's classes loaded first, so the second write can wait on nothing but the cache's own lock.
        MetaCache(folder.newFolder()).put(key, byteArrayOf(0))
        val first = Thread { cache.put(key, ones) }.apply { isDaemon = true }
        val second = Thread { cache.put(key, twos) }.apply { isDaemon = true }
        try {
            first.start()
            halfway.await()
            second.start()
            // Unlocked, the second write runs to its end over the first one's file; locked, it waits.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (second.state != Thread.State.BLOCKED && second.state != Thread.State.TERMINATED) {
                assertTrue("the second write neither ran nor waited", System.nanoTime() < deadline)
                Thread.yield()
            }
        } finally {
            goOn.countDown()
        }
        first.join()
        second.join()

        assertArrayEquals(twos, cache.get(key))
    }

    @Test
    fun `the cache names its files by hash and drops the oldest past its budget`() {
        val dir = folder.newFolder()
        val cache = MetaCache(dir, budgetBytes = 250)
        cache.put("https://musicbrainz.org/ws/2/release/?query=Lanterns", ByteArray(100) { 1 })
        File(dir.listFiles()!!.single().path).setLastModified(1_000)
        cache.put("https://musicbrainz.org/ws/2/release/?query=Ember", ByteArray(100) { 2 })
        dir.listFiles()!!.first { it.lastModified() > 1_000 }.setLastModified(2_000)

        assertTrue(dir.list()!!.none { "Lanterns" in it || "musicbrainz" in it })
        assertArrayEquals(ByteArray(100) { 1 }, cache.get("https://musicbrainz.org/ws/2/release/?query=Lanterns"))

        cache.put("https://musicbrainz.org/ws/2/release/?query=Vael", ByteArray(100) { 3 })

        assertNull(cache.get("https://musicbrainz.org/ws/2/release/?query=Ember"))
        assertArrayEquals(ByteArray(100) { 3 }, cache.get("https://musicbrainz.org/ws/2/release/?query=Vael"))
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 250)
    }

    // Network rules

    @Test
    fun `an address off the allowlist is never opened`() = runTest {
        val web = web()
        val client = client(web)
        assertTrue(client.text(URI("http://musicbrainz.org/ws/2/release/$ID_A?fmt=json")) is MetaAnswer.Failed)
        assertTrue(client.text(URI("https://example.com/ws/2/")) is MetaAnswer.Failed)
        assertTrue(client.image(URI(archive)) is MetaAnswer.Failed)
        assertEquals(emptyList<FakeWeb.Request>(), web.requests)
    }

    @Test
    fun `cover redirects are followed to archive org, no hop waiting for a gap, and cached under the first address`() = runTest {
        val web = web()
        web.on(cover, redirect(307, archive))
        web.on(archive, redirect(302, server))
        web.on(server, ok(JPEG_BYTES))
        val client = client(web)

        val answer = client.image(cover)

        assertArrayEquals(JPEG_BYTES, (answer as MetaAnswer.Ok).value)
        assertEquals(listOf(cover.toString(), archive, server), web.requests.map { it.uri })
        assertEquals(listOf(0L, 0L, 0L), web.requests.map { it.at })
        assertTrue(client.image(cover) is MetaAnswer.Ok)
        assertEquals(3, web.requests.size)
    }

    @Test
    fun `a relative redirect stays on its host`() = runTest {
        val web = web()
        web.on(cover, redirect(307, "/release/$ID_A/front-500"))
        web.on("https://coverartarchive.org/release/$ID_A/front-500", ok(JPEG_BYTES))
        assertTrue(client(web).image(cover) is MetaAnswer.Ok)
    }

    @Test
    fun `a redirect anywhere else is refused before it is opened`() = runTest {
        val web = web()
        web.on(cover, redirect(307, "https://example.com/x.jpg"))
        web.on("https://example.com/x.jpg", ok(JPEG_BYTES))
        assertTrue(client(web).image(cover) is MetaAnswer.Failed)
        assertEquals(listOf(cover.toString()), web.requests.map { it.uri })

        web.on(cover, redirect(302, "http://archive.org/download/x.jpg"))
        assertTrue(client(web).image(cover) is MetaAnswer.Failed)
        assertEquals(2, web.requests.size)
    }

    @Test
    fun `endless redirects fail`() = runTest {
        val web = web()
        web.on(cover, redirect(307, archive))
        web.on(archive, redirect(302, cover.toString()))
        assertTrue(client(web).image(cover) is MetaAnswer.Failed)
        assertEquals(MetaClient.MAX_REDIRECTS + 1, web.requests.size)
    }

    @Test
    fun `an image over 2 MiB is refused and never cached`() = runTest {
        val web = web()
        val big = ByteArray(2 * 1024 * 1024 + 1) { 7 }
        web.on(cover, ok(big), ok(ByteArray(2 * 1024 * 1024) { 7 }))
        val client = client(web)

        assertTrue(client.image(cover) is MetaAnswer.Failed)
        assertEquals(2 * 1024 * 1024, (client.image(cover) as MetaAnswer.Ok).value.size)
        assertEquals(2, web.requests.size)
    }

    @Test
    fun `a length header over the cap is refused too`() = runTest {
        val web = web()
        web.on(cover, FakeWeb.Reply(200, JPEG_BYTES, contentLength = 3L * 1024 * 1024))
        assertTrue(client(web).image(cover) is MetaAnswer.Failed)
    }

    @Test
    fun `a JSON answer is capped at 4 MiB`() = runTest {
        val web = web()
        web.on(json, ok("{" + " ".repeat(4 * 1024 * 1024) + "}"))
        assertTrue(client(web).text(json) is MetaAnswer.Failed)
        assertEquals(4 * 1024 * 1024, Body.JSON.maxBytes)
        assertEquals(2 * 1024 * 1024, Body.IMAGE.maxBytes)
    }

    @Test
    fun `a dropped connection fails, and only its class name is logged`() = runTest {
        val web = web()
        web.on(json, FakeWeb.Reply(200, error = IOException("connect to https://musicbrainz.org/ws/2/release/?query=Mira+Solen failed")))

        assertTrue(client(web).text(json) is MetaAnswer.Failed)
        assertTrue(logs.toString(), logs.any { "IOException" in it })
        assertTrue(logs.toString(), logs.none { "Mira" in it || "musicbrainz" in it || "http" in it })
    }

    @Test
    fun `log lines carry status codes and counts, never an address`() = runTest {
        val web = web()
        web.on(json, status(503), status(500))
        web.on(cover, redirect(307, "https://example.com/Lanterns.jpg"))
        val client = client(web)
        client.text(json)
        client.image(cover)

        assertTrue(logs.toString(), logs.any { "503" in it })
        assertTrue(logs.toString(), logs.any { "500" in it })
        assertTrue(logs.toString(), logs.none { "http" in it || ".org" in it || ".com" in it || "Lanterns" in it || ID_A in it })
    }

    // Covers on their own (user feedback 2026-11-11): the Cover Art Archive sets no rate limit
    // (https://musicbrainz.org/doc/Cover_Art_Archive/API, "Rate limiting rules"), so covers never wait for MusicBrainz's gate

    @Test
    fun `cover hops never wait for MusicBrainz's gate, and several covers load at once`() = runTest {
        val web = web()
        val other = URI("https://coverartarchive.org/release/$ID_B/front-250")
        web.on(json, ok("{}"))
        web.on(cover, redirect(307, archive))
        web.on(archive, redirect(302, server))
        web.on(server, ok(JPEG_BYTES))
        web.on(other, ok(JPEG_BYTES))
        val client = client(web)
        client.text(json)

        val covers = listOf(async { client.image(cover) }, async { client.image(other) })
        val next = async { client.text(json, fresh = true) }
        covers.forEach { assertTrue(it.await() is MetaAnswer.Ok) }
        next.await()

        assertEquals(listOf(0L, 0L, 0L, 0L), web.requests.filterNot { "musicbrainz" in it.uri }.map { it.at })
        assertEquals(listOf(0L, 1_100L), web.requests.filter { "musicbrainz" in it.uri }.map { it.at })
    }

    @Test
    fun `a cover's 503 is a cover that could not load, never MusicBrainz busy, and is not waited out`() = runTest {
        val web = web()
        web.on(cover, status(503))
        val client = client(web)

        assertTrue(client.image(cover) is MetaAnswer.Failed)

        assertEquals(1, web.count(cover))
        assertFalse(client.busy.value)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `every request gives up connecting after 10 s and reading after 20 s`() = runTest {
        val web = web()
        web.on(json, ok("{}"))
        web.on(cover, ok(JPEG_BYTES))
        val client = client(web)
        client.text(json)
        client.image(cover)

        assertEquals(listOf(10_000 to 20_000, 10_000 to 20_000), web.requests.map { it.connectTimeout to it.readTimeout })
    }

    @Test
    fun `a request that timed out is tried once more, then given up, and other failures are not tried again`() = runTest {
        val web = web()
        val slow = URI("https://musicbrainz.org/ws/2/release/$ID_B?fmt=json")
        val dropped = URI("https://musicbrainz.org/ws/2/release/$ID_C?fmt=json")
        web.on(json, FakeWeb.Reply(200, error = java.net.SocketTimeoutException("timed out")), ok("{}"))
        web.on(slow, FakeWeb.Reply(200, error = java.net.SocketTimeoutException("timed out")))
        web.on(dropped, FakeWeb.Reply(200, error = IOException("reset")))
        web.on(cover, FakeWeb.Reply(200, error = java.net.SocketTimeoutException("timed out")), ok(JPEG_BYTES))
        val client = client(web)

        assertTrue(client.text(json) is MetaAnswer.Ok)
        assertTrue(client.text(slow) is MetaAnswer.Failed)
        assertTrue(client.text(dropped) is MetaAnswer.Failed)
        assertTrue(client.image(cover) is MetaAnswer.Ok)

        assertEquals(listOf(2, 2, 1, 2), listOf(json, slow, dropped, cover).map(web::count))
    }

    @Test
    fun `an answer read to its end leaves its connection for the next request, any other is disconnected`() = runTest {
        val web = web()
        val missing = URI("https://musicbrainz.org/ws/2/release/$ID_B?fmt=json")
        web.on(json, ok("{}"))
        web.on(missing, status(404))
        web.on(cover, redirect(307, archive))
        web.on(archive, redirect(302, server))
        web.on(server, ok(JPEG_BYTES))
        val client = client(web)

        client.text(json)
        client.text(missing)
        client.image(cover)

        assertEquals(listOf(missing.toString(), cover.toString(), archive), web.disconnected.toList())
    }

    @Test
    fun `a cover redirect onto musicbrainz org waits for its gate like any MusicBrainz request`() = runTest {
        val web = web()
        val landing = "https://musicbrainz.org/ws/2/release/$ID_B?fmt=json"
        web.on(json, ok("{}"))
        web.on(cover, redirect(307, landing))
        web.on(landing, ok(JPEG_BYTES))
        val client = client(web)
        client.text(json)

        assertTrue(client.image(cover) is MetaAnswer.Ok)

        assertEquals(listOf(0L, 0L, 1_100L), web.requests.map { it.at })
    }
}
