package app.lyrify.tags

import app.lyrify.tags.TagFixtures.audioPackets
import app.lyrify.tags.TagFixtures.flac
import app.lyrify.tags.TagFixtures.flacAudio
import app.lyrify.tags.TagFixtures.id3v1
import app.lyrify.tags.TagFixtures.id3v2
import app.lyrify.tags.TagFixtures.m4a
import app.lyrify.tags.TagFixtures.mp3
import app.lyrify.tags.TagFixtures.mp3Audio
import app.lyrify.tags.TagFixtures.ogg
import app.lyrify.tags.TagFixtures.oggPages
import app.lyrify.tags.TagFixtures.opusPackets
import app.lyrify.tags.TagFixtures.vorbisPackets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * The audio payload hash: one definition per format, unchanged by any retag (a bigger tag, a cover, padding, an Ogg
 * comment header that renumbers the pages) and changed by any change to the audio. Made-up files only (TagFixtures).
 */
class PayloadHashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File = File(folder.root, name).apply { writeBytes(bytes) }

    private fun payload(name: String, bytes: ByteArray): Payload? = PayloadHash.of(file(name, bytes).toPath())

    private fun bigTag(size: Int): ByteArray = Random(9).nextBytes(size)

    // MP3

    @Test
    fun `an MP3 retag keeps the payload, whatever the ID3v2 version, size, padding, or ID3v1 tag`() {
        val audio = mp3Audio()
        val before = payload("Paper Lantern.mp3", mp3(id3v2("TIT2 Paper Lantern".encodeToByteArray(), version = 3), audio))
        assertNotNull(before)
        assertEquals(AudioFormat.MP3, before!!.format)
        assertNull(before.layout)

        assertEquals(before, payload("v24.mp3", mp3(id3v2(bigTag(40_000), version = 4, padding = 1_024), audio)))
        assertEquals(before, payload("footer.mp3", mp3(id3v2(bigTag(300), version = 4, footer = true), audio)))
        assertEquals(before, payload("v1.mp3", mp3(id3v2(bigTag(10), version = 3), audio, v1 = id3v1())))
        assertEquals(before, payload("v1 other title.mp3", mp3(id3v2(bigTag(10), version = 3), audio, v1 = id3v1("Tin Roof"))))
        assertEquals(before, payload("no tag.mp3", mp3(tag = null, audio = audio)))
    }

    @Test
    fun `an MP3 audio change changes the payload`() {
        val audio = mp3Audio()
        val before = payload("a.mp3", mp3(audio = audio))
        val changed = audio.copyOf().also { it[2_000] = (it[2_000] + 1).toByte() }
        assertNotEquals(before, payload("b.mp3", mp3(audio = changed)))
        assertNotEquals(before, payload("c.mp3", mp3(audio = audio + mp3Audio(frames = 1, seed = 7))))
    }

    @Test
    fun `an mp3 file without MPEG audio has no payload, so it is never changed`() {
        assertNull(payload("empty.mp3", ByteArray(0)))
        assertNull(payload("tag only.mp3", id3v2(bigTag(100))))
        assertNull(payload("garbage.mp3", id3v2(bigTag(100)) + Random(1).nextBytes(70_000).map { (it.toInt() and 0x7F).toByte() }.toByteArray()))
        assertNull(payload("short tag.mp3", "ID3".encodeToByteArray() + byteArrayOf(3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F) + mp3Audio()))
    }

    // FLAC

    @Test
    fun `a FLAC retag keeps the payload, with any comment, cover, padding, ID3 prefix or ID3v1 tag`() {
        val audio = flacAudio()
        val before = payload("Paper Lantern.flac", flac(audio = audio))
        assertEquals(AudioFormat.FLAC, before?.format)

        assertEquals(before, payload("cover.flac", flac(comment = bigTag(5_000), picture = bigTag(300_000), padding = 4_096, audio = audio)))
        assertEquals(before, payload("no padding.flac", flac(padding = 0, audio = audio)))
        assertEquals(before, payload("id3.flac", flac(audio = audio, id3Prefix = id3v2(bigTag(50)), v1 = id3v1())))
    }

    @Test
    fun `a FLAC audio change changes the payload, and a broken FLAC has none`() {
        val audio = flacAudio()
        val before = payload("a.flac", flac(audio = audio))
        assertNotEquals(before, payload("b.flac", flac(audio = audio.copyOf().also { it[100] = (it[100] + 1).toByte() })))
        assertNull(payload("no magic.flac", flac(audio = audio).copyOfRange(4, 500)))
        assertNull(payload("no frames.flac", flac(audio = byteArrayOf(1, 2, 3, 4))))
        assertNull(payload("cut.flac", flac(audio = audio, picture = bigTag(10_000)).copyOf(2_000)))
    }

    // M4A

    @Test
    fun `an M4A retag keeps the payload and the chunk layout, moov before or after mdat, 32 or 64-bit offsets`() {
        val audio = Random(3).nextBytes(3_000)
        for (co64 in listOf(false, true)) {
            for (moovFirst in listOf(true, false)) {
                val before = payload("a.m4a", m4a(audio = audio, moovFirst = moovFirst, co64 = co64))
                assertEquals(AudioFormat.M4A, before?.format)
                assertNotNull(before?.layout)
                // A bigger ilst moves mdat when moov comes first; the offsets moved with it.
                assertEquals("co64=$co64 moovFirst=$moovFirst", before, payload("b.m4a", m4a(tags = bigTag(20_000), audio = audio, moovFirst = moovFirst, co64 = co64)))
            }
        }
    }

    @Test
    fun `chunk offsets that no longer point at the same mdat bytes change the layout`() {
        val audio = Random(3).nextBytes(3_000)
        val before = payload("a.m4a", m4a(audio = audio))!!
        val shifted = payload("b.m4a", m4a(audio = audio, offsetShift = 16))
        assertEquals(before.sha256, shifted?.sha256)
        assertNotEquals(before.layout, shifted?.layout)
        // An offset outside mdat: not a file KJB can check, so none.
        assertNull(payload("c.m4a", m4a(audio = audio, offsetShift = 100_000)))
    }

    @Test
    fun `an M4A audio change changes the payload, and a fragmented or broken one has none`() {
        val audio = Random(3).nextBytes(3_000)
        val before = payload("a.m4a", m4a(audio = audio))
        assertNotEquals(before?.sha256, payload("b.m4a", m4a(audio = audio.copyOf().also { it[10] = 0 }.also { it[11] = 1 }))?.sha256)
        assertNull(payload("fragmented.m4a", m4a(audio = audio) + TagFixtures.box("moof", ByteArray(20))))
        assertNull(payload("no moov.m4a", TagFixtures.box("ftyp", ByteArray(8)) + TagFixtures.box("mdat", audio)))
        assertNull(payload("cut.m4a", m4a(audio = audio).copyOf(500)))
    }

    // Ogg Vorbis and Opus

    @Test
    fun `an Ogg Vorbis comment header that grows over several pages renumbers them and keeps the payload`() {
        val audio = audioPackets()
        val small = ogg(vorbisPackets(audio = audio), headers = 3)
        val grown = ogg(vorbisPackets(comment = bigTag(150_000), audio = audio), headers = 3)
        assertTrue("the comment spans more pages", oggPages(grown) > oggPages(small) + 1)

        val before = payload("Paper Lantern.ogg", small)
        assertEquals(AudioFormat.OGG_VORBIS, before?.format)
        assertEquals(before, payload("grown.ogg", grown))
    }

    @Test
    fun `an Opus comment header that grows keeps the payload, an audio change does not`() {
        val audio = audioPackets()
        val before = payload("Paper Lantern.opus", ogg(opusPackets(audio = audio), headers = 2))
        assertEquals(AudioFormat.OPUS, before?.format)
        assertEquals(before, payload("grown.opus", ogg(opusPackets(comment = bigTag(70_000), audio = audio), headers = 2)))

        val changed = audio.map { it.copyOf() }.also { it[5][3] = (it[5][3] + 1).toByte() }
        assertNotEquals(before, payload("changed.opus", ogg(opusPackets(audio = changed), headers = 2)))
        // The same bytes cut into other packets: other audio.
        val joined = listOf(audio[0] + audio[1]) + audio.drop(2)
        assertNotEquals(before, payload("joined.opus", ogg(opusPackets(audio = joined), headers = 2)))
    }

    @Test
    fun `Opus in an ogg file is Opus, and a second stream, no audio or no Ogg pages means no payload`() {
        assertEquals(AudioFormat.OPUS, payload("opus.ogg", ogg(opusPackets(), headers = 2))?.format)
        assertNull(payload("two streams.ogg", ogg(vorbisPackets(), headers = 3, secondSerialAt = 2)))
        assertNull(payload("headers only.opus", ogg(opusPackets(audio = emptyList()), headers = 2)))
        assertNull(payload("unknown codec.ogg", ogg(listOf("Speex   ".encodeToByteArray(), ByteArray(10)) + audioPackets(), headers = 2)))
        assertNull(payload("cut.ogg", ogg(vorbisPackets(), headers = 3).let { it.copyOf(it.size - 30) }))
        assertNull(payload("garbage.ogg", Random(2).nextBytes(2_000)))
    }

    // The allowlist

    @Test
    fun `only MP3, FLAC, M4A, Ogg Vorbis and Opus files have a payload`() {
        assertEquals(setOf("mp3", "flac", "m4a", "ogg", "opus"), AudioFormat.EXTENSIONS)
        assertEquals(AudioFormat.MP3, payload("CAPS.MP3", mp3())?.format)
        // MP3 audio under another name: never changed, even though TagLib could open it.
        for (name in listOf("song.wav", "song.mp2", "song.aac", "song.m4b", "song.mp4", "song.wma", "song.ape", "song", "song.mp3.txt")) {
            assertNull(name, payload(name, mp3()))
        }
        assertNull(PayloadHash.of(File(folder.root, "missing.mp3").toPath()))
    }

    @Test
    fun `the device fixtures, real encoded files, each have a payload of their format`() {
        val fixtures = File("src/androidTest/assets/tags")
        assertTrue("run from the app module: ${File("").absolutePath}", fixtures.isDirectory)
        val formats = fixtures.listFiles()!!.sortedBy { it.name }.associate { it.name to PayloadHash.of(it.toPath())?.format }
        assertEquals(
            mapOf(
                "lanterns-over-kelp-v24.mp3" to AudioFormat.MP3,
                "northbound-tide.m4a" to AudioFormat.M4A,
                "paper-comet-messy.ogg" to AudioFormat.OGG_VORBIS,
                "paper-comet-v23.mp3" to AudioFormat.MP3,
                "quiet-breakwater.opus" to AudioFormat.OPUS,
                "salt-clock.flac" to AudioFormat.FLAC,
            ),
            formats,
        )
    }

    @Test
    fun `the whole-file hash is the SHA-256 of every byte`() {
        val path = file("a.mp3", "abc".encodeToByteArray()).toPath()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PayloadHash.fileSha256(path))
    }
}
