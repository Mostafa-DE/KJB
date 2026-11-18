package app.lyrify.lyrics

import app.lyrify.lyrics.LyricsTagFixtures.LATIN1
import app.lyrify.lyrics.LyricsTagFixtures.UTF16
import app.lyrify.lyrics.LyricsTagFixtures.UTF16BE
import app.lyrify.lyrics.LyricsTagFixtures.frame
import app.lyrify.lyrics.LyricsTagFixtures.frame22
import app.lyrify.lyrics.LyricsTagFixtures.sylt
import app.lyrify.lyrics.LyricsTagFixtures.uslt
import app.lyrify.tags.TagFixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/** Lyrics read from a song file's own tags, in each format KJB knows. Made-up words only. */
class EmbeddedLyricsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val plain = "Made-up harbour line\nMade-up second line"
    private val lines = listOf(10_000L to "Made-up dawn", 25_500L to "Made-up noon", 40_250L to "Made-up dusk")
    private val lrc = "[00:10.000]Made-up dawn\n[00:25.500]Made-up noon\n[00:40.250]Made-up dusk\n"

    /** [lrc] as a text field holds it, read back trimmed. */
    private val lrcText = lrc.trimEnd()

    private fun read(name: String, bytes: ByteArray): TagLyrics? = EmbeddedLyrics.read(File(folder.root, name).apply { writeBytes(bytes) })

    // ID3v2

    @Test
    fun `an MP3's USLT frame is plain lyrics, in each text encoding`() {
        assertEquals(TagLyrics.Plain(plain), read("a.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt(plain)))))
        assertEquals(TagLyrics.Plain(plain), read("b.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt(plain, UTF16, description = "Made-up")), version = 3)))
        assertEquals(TagLyrics.Plain(plain), read("c.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt(plain, UTF16BE)))))
        assertEquals(TagLyrics.Plain("Made-up café line"), read("d.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt("Made-up café line", LATIN1)), version = 3)))
    }

    @Test
    fun `a USLT frame holding timed lines is synced lyrics`() {
        val text = "[00:10.00]Made-up dawn\r\n[00:25.50]Made-up noon\r\n[00:40.25]Made-up dusk"
        val read = read("a.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt(text))))
        assertEquals(TagLyrics.Synced("[00:10.00]Made-up dawn\n[00:25.50]Made-up noon\n[00:40.25]Made-up dusk"), read)
        assertEquals("Made-up dawn\nMade-up noon\nMade-up dusk", read?.words)
    }

    @Test
    fun `a SYLT frame in milliseconds is synced lyrics, a line each`() {
        assertEquals(TagLyrics.Synced(lrc), read("a.mp3", LyricsTagFixtures.mp3(frame("SYLT", sylt(lines)))))
        assertEquals(TagLyrics.Synced(lrc), read("b.mp3", LyricsTagFixtures.mp3(frame("SYLT", sylt(lines, UTF16)), version = 3)))
    }

    @Test
    fun `a SYLT frame of words joins them into lines at each new line`() {
        val words = listOf(10_000L to "Made-", 10_400L to "up ", 10_800L to "dawn", 25_500L to "\nMade-up ", 26_000L to "noon", 40_250L to "\nMade-up dusk")
        assertEquals(TagLyrics.Synced(lrc), read("a.mp3", LyricsTagFixtures.mp3(frame("SYLT", sylt(words)))))
    }

    @Test
    fun `a SYLT frame timed in MPEG frames, or not of words, is left out`() {
        assertNull(read("a.mp3", LyricsTagFixtures.mp3(frame("SYLT", sylt(lines, format = 1)))))
        assertNull(read("b.mp3", LyricsTagFixtures.mp3(frame("SYLT", sylt(lines, contentType = 5)))))
        assertEquals(TagLyrics.Plain(plain), read("c.mp3", LyricsTagFixtures.mp3(frame("SYLT", sylt(lines, format = 1)), frame("USLT", uslt(plain)))))
    }

    @Test
    fun `synced lyrics win over plain ones, wherever they are in the tag`() {
        assertEquals(TagLyrics.Synced(lrc), read("a.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt(plain)), frame("SYLT", sylt(lines)))))
    }

    @Test
    fun `other frames, a cover among them, are stepped over`() {
        val cover = frame("APIC", byteArrayOf(0) + "image/jpeg".encodeToByteArray() + byteArrayOf(0, 3, 0) + ByteArray(300_000) { 7 })
        val title = frame("TIT2", byteArrayOf(3) + "Made-up Title".encodeToByteArray())
        assertEquals(TagLyrics.Plain(plain), read("a.mp3", LyricsTagFixtures.mp3(title, cover, frame("USLT", uslt(plain)))))
    }

    @Test
    fun `ID3v2_2's ULT and SLT frames are read too`() {
        assertEquals(TagLyrics.Plain(plain), read("a.mp3", LyricsTagFixtures.mp3(frame22("ULT", uslt(plain, LATIN1)), version = 2)))
        assertEquals(TagLyrics.Synced(lrc), read("b.mp3", LyricsTagFixtures.mp3(frame22("SLT", sylt(lines, LATIN1)), version = 2)))
    }

    @Test
    fun `an unsynchronised frame, one with a data length, and a compressed one are read`() {
        // 0xFF before a 0x00 in the text: unsynchronisation adds a 0x00 after it.
        val body = uslt("Made-up ÿ line", LATIN1)
        val unsynced = ByteArrayOutputStream().apply { body.forEach { write(it.toInt()); if (it == 0xFF.toByte()) write(0) } }.toByteArray()
        val withLength = LyricsTagFixtures.syncsafe(body.size) + unsynced
        assertEquals(TagLyrics.Plain("Made-up ÿ line"), read("a.mp3", LyricsTagFixtures.mp3(frame("USLT", withLength, flags = 0x03))))

        val deflater = Deflater().apply { setInput(uslt(plain)); finish() }
        val packed = ByteArray(4_096).let { it.copyOf(deflater.deflate(it)) }
        val compressed = java.nio.ByteBuffer.allocate(4).putInt(uslt(plain).size).array() + packed
        assertEquals(TagLyrics.Plain(plain), read("b.mp3", LyricsTagFixtures.mp3(frame("USLT", compressed, version = 3, flags = 0x80), version = 3)))
    }

    @Test
    fun `a 2_3 tag unsynchronised as a whole is read`() {
        val frames = frame("USLT", uslt("Made-up ÿ line", LATIN1), version = 3)
        val unsynced = ByteArrayOutputStream().apply { frames.forEach { write(it.toInt()); if (it == 0xFF.toByte()) write(0) } }.toByteArray()
        val tag = TagFixtures.id3v2(unsynced, version = 3).also { it[5] = 0x80.toByte() }
        assertEquals(TagLyrics.Plain("Made-up ÿ line"), read("a.mp3", TagFixtures.mp3(tag = tag)))
    }

    @Test
    fun `an encrypted frame, a frame that runs past the tag and blank lyrics are nothing`() {
        assertNull(read("a.mp3", LyricsTagFixtures.mp3(frame("USLT", byteArrayOf(1) + uslt(plain), flags = 0x04))))
        val tooLong = frame("USLT", uslt(plain)).also { it[7] = 0x7F }
        assertNull(read("b.mp3", LyricsTagFixtures.mp3(tooLong)))
        assertNull(read("c.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt(" \n ")))))
    }

    // Vorbis comments and M4A

    @Test
    fun `FLAC's LYRICS and UNSYNCEDLYRICS comments are read, synced first`() {
        assertEquals(TagLyrics.Plain(plain), read("a.flac", LyricsTagFixtures.flac("TITLE" to "Made-up", "UNSYNCEDLYRICS" to plain)))
        assertEquals(TagLyrics.Synced(lrcText), read("b.flac", LyricsTagFixtures.flac("lyrics" to lrc)))
        assertEquals(TagLyrics.Synced(lrcText), read("c.flac", LyricsTagFixtures.flac("UNSYNCED LYRICS" to plain, "LYRICS" to lrc)))
        val behindId3 = TagFixtures.flac(comment = LyricsTagFixtures.vorbisComment("LYRICS" to plain), id3Prefix = TagFixtures.id3v2(ByteArray(0), padding = 64))
        assertEquals(TagLyrics.Plain(plain), read("d.flac", behindId3))
    }

    @Test
    fun `Ogg Vorbis and Opus comments are read`() {
        assertEquals(TagLyrics.Plain(plain), read("a.ogg", LyricsTagFixtures.oggVorbis("LYRICS" to plain)))
        assertEquals(TagLyrics.Synced(lrcText), read("b.opus", LyricsTagFixtures.opus("ARTIST" to "Made-up", "LYRICS" to lrc)))
        // A comment header longer than one page.
        assertEquals(TagLyrics.Plain(plain), read("c.ogg", LyricsTagFixtures.oggVorbis("DESCRIPTION" to "x".repeat(70_000), "LYRICS" to plain)))
    }

    @Test
    fun `an M4A's lyrics item is read, its moov after the audio`() {
        assertEquals(TagLyrics.Plain(plain), read("a.m4a", LyricsTagFixtures.m4a(plain)))
        assertEquals(TagLyrics.Synced(lrcText), read("b.m4a", LyricsTagFixtures.m4a(lrc)))
    }

    // Nothing to read

    @Test
    fun `no lyrics, another format, a broken file or no file at all is nothing`() {
        assertNull(read("a.mp3", TagFixtures.mp3()))
        assertNull(read("b.mp3", TagFixtures.mp3(tag = null)))
        assertNull(read("c.flac", TagFixtures.flac()))
        assertNull(read("d.m4a", TagFixtures.m4a()))
        assertNull(read("e.ogg", TagFixtures.ogg(TagFixtures.vorbisPackets(), headers = 3)))
        assertNull(read("f.wav", LyricsTagFixtures.mp3(frame("USLT", uslt(plain)))))
        assertNull(read("g.mp3", LyricsTagFixtures.mp3(frame("USLT", uslt(plain))).copyOf(30)))
        assertNull(read("h.m4a", LyricsTagFixtures.m4a(plain).copyOf(2_100)))
        assertNull(read("i.flac", byteArrayOf(1, 2, 3)))
        assertNull(EmbeddedLyrics.read(File(folder.root, "missing.mp3")))
    }

    @Test
    fun `reading never changes the file`() {
        val bytes = LyricsTagFixtures.mp3(frame("USLT", uslt(plain)), frame("SYLT", sylt(lines)))
        val file = File(folder.root, "a.mp3").apply { writeBytes(bytes) }
        file.setLastModified(1_700_000_000_000L)

        EmbeddedLyrics.read(file)

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1_700_000_000_000L, file.lastModified())
    }
}
