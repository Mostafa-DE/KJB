package app.lyrify.ui

import android.content.Intent
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Share: songs as their files, lyrics as text or as the `.lrc`, a crash report as text, as the share sheet gets them.
 * Made-up names and lines only.
 */
class ShareTest {
    private fun shared(id: Long, type: String?, title: String) = SharedSong("content://media/external/audio/media/$id", type, title)

    private val lantern = shared(11, "audio/mpeg", "Paper Lantern")
    private val bell = shared(12, "audio/mpeg", "Tide Bell")
    private val water = shared(13, "audio/flac", "Low Water")
    private val several: (Int) -> String = { "$it songs" }

    private fun entry(status: LyricsStatus, lrclibId: Long? = null) =
        LedgerEntry(path = "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", durationMs = 200_000, status = status, lrclibId = lrclibId, updatedAt = 1)

    // Songs

    @Test
    fun `one song goes as ACTION_SEND with its file, its own type, the read grant and its title as the subject`() {
        val request = songsShare(listOf(lantern), several)
        assertEquals(Intent.ACTION_SEND, request.action)
        assertEquals("audio/mpeg", request.type)
        assertEquals(listOf("content://media/external/audio/media/11"), request.streams)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, request.flags)
        assertEquals("Paper Lantern", request.subject)
        assertNull(request.text)
    }

    @Test
    fun `several songs of one type go as ACTION_SEND_MULTIPLE with that type, every file in order, and the count as the subject`() {
        val request = songsShare(listOf(bell, lantern), several)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, request.action)
        assertEquals("audio/mpeg", request.type)
        assertEquals(listOf("content://media/external/audio/media/12", "content://media/external/audio/media/11"), request.streams)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, request.flags)
        assertEquals("2 songs", request.subject)
    }

    @Test
    fun `a mix of types goes as audio star, and so does a song without a type`() {
        assertEquals("audio/*", songsShare(listOf(lantern, water, bell), several).type)
        assertEquals("audio/*", songsShare(listOf(shared(14, null, "Salt Road")), several).type)
        assertEquals("audio/*", songsShare(listOf(lantern, shared(14, null, "Salt Road")), several).type)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, songsShare(listOf(lantern, water, bell), several).action)
    }

    @Test
    fun `up to 500 songs go in one share, more are refused rather than cut`() {
        assertTrue(fitsOneShare(1))
        assertTrue(fitsOneShare(SHARE_SONGS_MAX))
        assertFalse(fitsOneShare(SHARE_SONGS_MAX + 1))
        assertFalse(fitsOneShare(0))
        val most = (1L..SHARE_SONGS_MAX).map { shared(it, "audio/mpeg", "Song $it") }
        assertEquals(SHARE_SONGS_MAX, songsShare(most, several).streams.size)
        assertThrows(IllegalArgumentException::class.java) { songsShare(most + shared(9_999, "audio/mpeg", "One more"), several) }
        assertThrows(IllegalArgumentException::class.java) { songsShare(emptyList(), several) }
    }

    // Starting the share sheet

    @Test
    fun `a read grant Android rejects ends as a failed share of those songs, never a crash`() {
        // A song deleted, trashed or on a card taken out after it was found: the grant check throws SecurityException.
        val rejected = runCatching { launchShare(3) { throw SecurityException("no read access to a made-up uri") } }
        assertEquals(Result.success(Shared.Failed(3)), rejected)
        assertEquals(Shared.Opened, launchShare(1) {})
    }

    @Test
    fun `any other error while starting is not hidden`() {
        assertThrows(IllegalStateException::class.java) { launchShare(1) { throw IllegalStateException("a made-up bug") } }
    }

    // Which lyrics a song can share

    @Test
    fun `synced lyrics go as text or as the file, plain ones as text only`() {
        val synced = "[00:01.000]First made-up line\n[00:04.000]Second made-up line\n"
        assertEquals(LyricsShare.SYNCED, lyricsShare(synced, entry(LyricsStatus.SYNCED)))
        // A file KJB has not looked up yet counts as well.
        assertEquals(LyricsShare.SYNCED, lyricsShare(synced, null))
        assertEquals(LyricsShare.PLAIN, lyricsShare("First made-up line\nSecond made-up line\n", entry(LyricsStatus.EXISTING_PLAIN)))
        // LRCLIB's plain lyrics, which the player shows without a file.
        assertEquals(LyricsShare.PLAIN, lyricsShare(null, entry(LyricsStatus.PLAIN_ONLY, lrclibId = 42)))
    }

    @Test
    fun `a song without lyrics has nothing to share`() {
        assertNull(lyricsShare(null, null))
        assertNull(lyricsShare(null, entry(LyricsStatus.NOT_FOUND)))
        assertNull(lyricsShare(null, entry(LyricsStatus.INSTRUMENTAL)))
        assertNull(lyricsShare(null, entry(LyricsStatus.PLAIN_ONLY)))
        // Header tags only, or only times without words.
        assertNull(lyricsShare("[ar:Harbour Glass]\n[ti:Paper Lantern]\n", entry(LyricsStatus.EXISTING)))
        assertNull(lyricsShare("[00:01.00]\n[00:05.00]\n", null))
    }

    // The words as text

    @Test
    fun `the text starts with the title and the artist, then the words without times`() {
        val lrc = "[00:01.000]First made-up line\n[00:04.500]Second made-up line\n"
        assertEquals("Paper Lantern · Harbour Glass\n\nFirst made-up line\nSecond made-up line", lyricsText("Paper Lantern", "Harbour Glass", lrc))
    }

    @Test
    fun `without an artist the head is the title alone`() {
        assertEquals("Paper Lantern\n\nFirst made-up line", lyricsText("Paper Lantern", null, "[00:01.00]First made-up line"))
        assertEquals("Paper Lantern\n\nFirst made-up line", lyricsText("Paper Lantern", " ", "[00:01.00]First made-up line"))
    }

    @Test
    fun `enhanced word times go and the words stay`() {
        val lrc = "[00:01.00]<00:01.00>Lantern <00:01.50>light <00:02.10>on\n[00:03.00] <00:03.00> over <00:03.40> the <00:03.90> water <00:04.50>\n"
        assertEquals("Paper Lantern\n\nLantern light on\nover the water", lyricsText("Paper Lantern", null, lrc))
    }

    @Test
    fun `header tags and the offset go, and a line sung twice shows twice in time order`() {
        val lrc = "[ar:Harbour Glass]\n[ti:Paper Lantern]\n[offset:+200]\n[00:05.00][00:20.00]Chorus made-up line\n[00:10.00]Verse made-up line\n"
        assertEquals("Paper Lantern\n\nChorus made-up line\nVerse made-up line\nChorus made-up line", lyricsText("Paper Lantern", null, lrc))
    }

    @Test
    fun `a break between verses stays one blank line, with none at the ends`() {
        val lrc = "[00:01.00]\n[00:02.00]First verse line\n[00:03.00]\n[00:03.50]\n[00:04.00]Second verse line\n[00:05.00]\n"
        assertEquals("Paper Lantern\n\nFirst verse line\n\nSecond verse line", lyricsText("Paper Lantern", null, lrc))
    }

    @Test
    fun `plain lyrics keep their lines and one blank line between verses`() {
        val plain = "\uFEFF[ar:Harbour Glass]\r\nFirst made-up line\r\nSecond made-up line\r\n\r\n\r\nThird made-up line\r\n"
        assertEquals("Paper Lantern\n\nFirst made-up line\nSecond made-up line\n\nThird made-up line", lyricsText("Paper Lantern", null, plain))
    }

    // The lyrics' two shares

    @Test
    fun `As text goes as ACTION_SEND text plain with the words, the title as subject and preview, and no file`() {
        val request = lyricsTextShare("Paper Lantern", "Paper Lantern\n\nFirst made-up line")
        assertEquals(Intent.ACTION_SEND, request.action)
        assertEquals("text/plain", request.type)
        assertEquals("Paper Lantern\n\nFirst made-up line", request.text)
        assertEquals("Paper Lantern", request.subject)
        assertEquals("Paper Lantern", request.title)
        assertEquals(emptyList<String>(), request.streams)
        assertEquals(0, request.flags)
    }

    @Test
    fun `Lyrics file goes as ACTION_SEND of the lrc as a file, with the read grant and the title as the subject`() {
        val uri = "content://app.lyrify.share/lyrics/01%20Paper%20Lantern.lrc"
        val request = lyricsFileShare(uri, "Paper Lantern")
        assertEquals(Intent.ACTION_SEND, request.action)
        assertEquals("application/octet-stream", request.type)
        assertEquals(listOf(uri), request.streams)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, request.flags)
        assertEquals("Paper Lantern", request.subject)
        assertNull(request.text)
    }

    // A crash report

    @Test
    fun `a crash report goes as ACTION_SEND text plain with its text and KJB's subject, and no file`() {
        val request = crashReportShare("java.lang.IllegalStateException\n\tat app.lyrify.Made.up(Made.kt:1)")
        assertEquals(Intent.ACTION_SEND, request.action)
        assertEquals("text/plain", request.type)
        assertEquals("java.lang.IllegalStateException\n\tat app.lyrify.Made.up(Made.kt:1)", request.text)
        assertEquals("KJB crash report", request.subject)
        assertEquals(emptyList<String>(), request.streams)
        assertEquals(0, request.flags)
    }

    // KJB does not offer itself

    /** The activities of KJB's manifests (main and debug) that take a share (SEND or SEND_MULTIPLE), by class name. */
    private fun manifestShareTargets(): Set<String> = listOf("src/main/AndroidManifest.xml", "src/debug/AndroidManifest.xml").map(::File).filter(File::isFile).flatMap { file ->
        val activities = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("activity")
        (0 until activities.length).map { activities.item(it) as Element }.filter { activity ->
            val actions = activity.getElementsByTagName("action")
            (0 until actions.length).any { (actions.item(it) as Element).getAttribute("android:name") in SHARE_ACTIONS }
        }.map { activity -> activity.getAttribute("android:name").let { if (it.startsWith(".")) "app.lyrify$it" else it } }
    }.toSet()

    @Test
    fun `KJB's own share sheets leave out every activity of KJB's that takes a share`() {
        assertEquals(setOf("app.lyrify.ui.MainActivity", "app.lyrify.ui.SaveToKjbActivity"), manifestShareTargets())
        assertEquals(manifestShareTargets(), OWN_SHARE_TARGETS.toSet())
    }

    @Test
    fun `every share sheet KJB opens is the share helper's`() {
        val choosers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && "createChooser(" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("Share.kt"), choosers)
    }

    private companion object {
        val SHARE_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
    }
}
