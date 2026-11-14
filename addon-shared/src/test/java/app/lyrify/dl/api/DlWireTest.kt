package app.lyrify.dl.api

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
    fun `the bind permission carries the add-on's own id`() {
        assertEquals("app.lyrify.dl.permission.BIND_DL", DlNames.permission("app.lyrify.dl"))
        assertEquals("app.lyrify.dl.debug.permission.BIND_DL", DlNames.permission("app.lyrify.dl.debug"))
    }
}
