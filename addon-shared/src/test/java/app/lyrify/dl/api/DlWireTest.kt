package app.lyrify.dl.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Downloads add-on's JSON wire: what KJB and the add-on read of each other, also across versions. */
class DlWireTest {
    private val request = DlRequest("dl-7f3a", "https://video.example.com/watch?v=abc123", DlKind.AUDIO, AudioQuality.MP3_V0)

    @Test
    fun `every message reads back as it was written`() {
        assertEquals(request, DlWire.request(DlWire.encode(request)))
        val info = DlInfo("https://video.example.com/watch?v=abc123", "Paper Lantern", "The Quiet Hours", 214, "https://img.example.com/t.jpg", false, listOf(VideoChoice(null, "1080p", 52_000_000), VideoChoice(720, "720p", 30_000_000)))
        assertEquals(info, DlWire.info(DlWire.encode(info)))
        val progress = DlProgress(DlStage.DOWNLOADING, 42.5f, 31, "2.1MiB/s")
        assertEquals(progress, DlWire.progress(DlWire.encode(progress)))
        val file = DlFile("The Quiet Hours - Paper Lantern.mp3", 8_123_456, 61_000, DlMeta("Paper Lantern", "The Quiet Hours", year = "2019"), 214)
        assertEquals(file, DlWire.file(DlWire.encode(file)))
        listOf(DlEnd.Delivered(8_123_456, 61_000), DlEnd.Failed(DlFailure.SITE_CHANGED, updated = true), DlEnd.Stopped).forEach { end ->
            assertEquals(end, DlWire.end(DlWire.encode(end)))
        }
        assertEquals(DlUpdate(DlUpdate.Outcome.UPDATED, "2026.11.01"), DlWire.update(DlWire.encode(DlUpdate(DlUpdate.Outcome.UPDATED, "2026.11.01"))))
        assertEquals(DlStatus("2026.11.01", 1_800_000_000_000), DlWire.status(DlWire.encode(DlStatus("2026.11.01", 1_800_000_000_000))))
    }

    @Test
    fun `fields a later version adds are ignored`() {
        assertEquals(DlProgress(DlStage.MERGING), DlWire.progress("""{"stage":"MERGING","fps":30}"""))
        assertEquals(DlEnd.Stopped, DlWire.end("""{"type":"stopped","why":"later"}"""))
    }

    /** The add-on's status as KJB 3.0 reads it, with DlWire's Json of then: before the add-on said whether Android refused it. */
    @Serializable
    private data class StatusIn30(val ytdlpVersion: String? = null, val updatedAt: Long? = null)

    @Test
    fun `whether Android refused the add-on its foreground service reads both ways across versions`() {
        val refused = DlWire.encode(DlStatus("2026.11.01", 1_800_000_000_000, foregroundRefused = true))

        // A newer add-on to an older KJB: the field is left out, the rest read as before.
        assertEquals(StatusIn30("2026.11.01", 1_800_000_000_000), Json { ignoreUnknownKeys = true }.decodeFromString(StatusIn30.serializer(), refused))
        // An older add-on to this KJB: not refused.
        assertFalse(DlWire.status("""{"ytdlpVersion":"2026.10.26","updatedAt":1800000000000}""")!!.foregroundRefused)
        assertTrue(DlWire.status(refused)!!.foregroundRefused)
    }

    @Test
    fun `a failure kind a later add-on adds reads as unknown in an older KJB, and BLOCKED reads here`() {
        // KJB 3.0's DlWire.end, the same as this one: a kind it does not know fails the read, a failure of an unknown kind.
        assertEquals(DlEnd.Failed(DlFailure.UNKNOWN), DlWire.end("""{"type":"failed","failure":"SOMETHING_LATER","updated":false}"""))
        assertEquals(DlEnd.Failed(DlFailure.BLOCKED), DlWire.end(DlWire.encode(DlEnd.Failed(DlFailure.BLOCKED))))
    }

    @Test
    fun `what cannot be read is the safe answer`() {
        assertEquals(DlEnd.Failed(DlFailure.UNKNOWN), DlWire.end("""{"type":"teleported"}"""))
        assertEquals(DlEnd.Failed(DlFailure.UNKNOWN), DlWire.end("not json"))
        assertEquals(DlUpdate(DlUpdate.Outcome.FAILED), DlWire.update(""))
        assertNull(DlWire.info("""{"title":"no link"}"""))
        assertNull(DlWire.progress("""{"stage":"BEAMING"}"""))
    }

    @Test
    fun `a request the add-on would turn into a path or an option is refused`() {
        listOf(
            request.copy(key = "../files"),
            request.copy(key = "a/b"),
            request.copy(key = ""),
            request.copy(key = "k".repeat(65)),
            request.copy(url = "--exec=rm -rf x"),
            request.copy(url = "-o/tmp/x https://video.example.com"),
            request.copy(url = "file:///data/data/app.lyrify/files"),
            request.copy(url = "https://video.example.com/watch?v=a b"),
            request.copy(url = "https://"),
            request.copy(url = "https://user:secret@video.example.com/v"),
            request.copy(kind = DlKind.VIDEO, height = 0),
            request.copy(kind = DlKind.VIDEO, height = 1_000_000),
        ).forEach { refused ->
            assertFalse(refused.toString(), refused.valid)
            assertNull(refused.toString(), DlWire.request(DlWire.encode(refused)))
        }
        assertTrue(request.copy(kind = DlKind.VIDEO, height = 1080).valid)
    }

    @Test
    fun `a web link is one http or https address with a host`() {
        assertTrue(isWebLink("https://video.example.com/watch?v=abc123&t=42"))
        assertTrue(isWebLink("http://short.example/abc"))
        assertTrue(isWebLink("HTTPS://VIDEO.EXAMPLE.COM/x"))
        assertFalse(isWebLink("ftp://video.example.com/x"))
        assertFalse(isWebLink("video.example.com/x"))
        assertFalse(isWebLink("https://video.example.com/x\nhttps://other.example.com"))
        assertFalse(isWebLink("https://video.example.com/" + "x".repeat(5_000)))
    }

    @Test
    fun `a link to this phone or a literal private address is never a web link`() {
        listOf(
            "http://localhost/x",
            "https://LOCALHOST:8080/",
            "http://app.localhost/",
            "http://127.0.0.1/",
            "http://127.8.9.10/x",
            "https://10.0.0.5/",
            "http://172.20.1.1/",
            "http://192.168.1.1/admin",
            "http://169.254.169.254/latest",
            "http://0.0.0.0/",
            "http://224.0.0.1/",
            "http://[::1]/",
            "http://[fe80::1]/",
            "http://[fd00::1]/",
            "http://[::ffff:127.0.0.1]/",
            // The forms inet_aton reads, which yt-dlp's Python resolves to the same addresses.
            "http://127.1/",
            "http://2130706433/",
            "http://0x7f.0.0.1/",
            "http://0177.0.0.1/",
            "http://0xc0.0xa8.1.1/",
        ).forEach { assertFalse(it, isWebLink(it)) }
        assertTrue(isWebLink("https://93.184.216.34/v"))
        assertTrue(isWebLink("https://[2606:4700::1111]/v"))
    }

    @Test
    fun `an address is public unless it is this phone, a home or office network, link-local or multicast`() {
        fun address(text: String) = java.net.InetAddress.getByName(text)
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.10", "169.254.1.1", "0.0.0.0", "224.0.0.251", "255.255.255.255", "100.64.0.1", "::1", "::", "fe80::1", "fc00::1", "fd12::1", "ff02::1", "::ffff:10.0.0.1")
            .forEach { assertFalse(it, PublicAddresses.isPublic(address(it))) }
        listOf("93.184.216.34", "172.15.0.1", "172.32.0.1", "8.8.8.8", "2606:4700::1111").forEach { assertTrue(it, PublicAddresses.isPublic(address(it))) }
    }

    @Test
    fun `a host is public only when every address it resolves to is, and one that does not resolve is not known`() {
        val dns = mapOf(
            "video.example.com" to listOf("93.184.216.34"),
            "mixed.example.com" to listOf("93.184.216.34", "192.168.1.1"),
            "rebind.example.com" to listOf("127.0.0.1"),
        ).mapValues { (_, list) -> list.map { java.net.InetAddress.getByName(it) } }
        val resolve: (String) -> List<java.net.InetAddress> = { host -> dns[host] ?: throw java.net.UnknownHostException(host) }

        assertEquals(PublicAddresses.Verdict.PUBLIC, PublicAddresses.of("https://video.example.com/v", resolve))
        assertEquals(PublicAddresses.Verdict.NOT_PUBLIC, PublicAddresses.of("https://mixed.example.com/v", resolve))
        assertEquals(PublicAddresses.Verdict.NOT_PUBLIC, PublicAddresses.of("https://rebind.example.com/v", resolve))
        assertEquals(PublicAddresses.Verdict.UNKNOWN, PublicAddresses.of("https://offline.example.com/v", resolve))
        assertEquals(PublicAddresses.Verdict.NOT_PUBLIC, PublicAddresses.of("not a link", resolve))
    }

    // YouTube search (VERSION 2)

    @Test
    fun `a search and what it found read back as they were written`() {
        val search = DlSearch("the quiet hours paper lantern", limit = 5)
        assertEquals(search, DlWire.search(DlWire.encode(search)))
        val results = DlSearchResults(
            listOf(
                DlSearchResult("https://www.youtube.com/watch?v=abc123", "The Quiet Hours - Paper Lantern (Official Video)", "The Quiet Hours", 214, "https://i.ytimg.com/vi/abc123/hq720.jpg"),
                DlSearchResult("https://www.youtube.com/shorts/def456", "Paper Lantern, live", "Glass Harbour Sessions"),
            ),
        )
        assertEquals(results, DlWire.results(DlWire.encode(results)))
        assertEquals(DlSearchResults(emptyList()), DlWire.results(DlWire.encode(DlSearchResults(emptyList()))))
    }

    @Test
    fun `a search asks for 8 results unless it says otherwise`() {
        assertEquals(8, DlSearch("paper lantern").limit)
        assertEquals(DlSearch("paper lantern"), DlWire.search("""{"query":"paper lantern"}"""))
    }

    @Test
    fun `a search the add-on would not take is refused`() {
        listOf(
            DlSearch(""),
            DlSearch("   "),
            DlSearch("paper\nlantern"),
            DlSearch("paper\u0000lantern"),
            DlSearch("x".repeat(201)),
            DlSearch("paper lantern", limit = 0),
            DlSearch("paper lantern", limit = 21),
        ).forEach { refused ->
            assertFalse(refused.toString(), refused.valid)
            assertNull(refused.toString(), DlWire.search(DlWire.encode(refused)))
        }
        assertTrue(DlSearch("x".repeat(200), limit = 20).valid)
        assertTrue(DlSearch("Mira Solen – Paper Harbour", limit = 1).valid)
    }

    @Test
    fun `search results a later add-on adds fields to still read, and ones that cannot be read are no answer`() {
        assertEquals(
            DlSearchResults(listOf(DlSearchResult("https://www.youtube.com/watch?v=abc123", "Paper Lantern", "The Quiet Hours", 214))),
            DlWire.results("""{"results":[{"url":"https://www.youtube.com/watch?v=abc123","title":"Paper Lantern","channel":"The Quiet Hours","durationSec":214,"views":12}],"more":true}"""),
        )
        assertNull(DlWire.results("not json"))
        assertNull(DlWire.results("""{"results":[{"title":"no link"}]}"""))
    }

    @Test
    fun `the bind permission carries the add-on's own id`() {
        assertEquals("app.lyrify.dl.permission.BIND_DL", DlNames.permission("app.lyrify.dl"))
        assertEquals("app.lyrify.dl.debug.permission.BIND_DL", DlNames.permission("app.lyrify.dl.debug"))
    }
}
