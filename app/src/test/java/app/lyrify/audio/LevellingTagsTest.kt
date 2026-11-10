@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.VorbisUtil
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.ogg.OggExtractor
import app.lyrify.settings.Levelling
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.pow

/**
 * Volume levelling's tags as the public standards define them (the sources head LevellingTags.kt), read from what
 * Media3's own readers hand on: ID3v2.3 and v2.4 tags through its ID3 decoder, Vorbis comments through its comment
 * parser, Ogg Opus streams through its Ogg extractor. Made-up values only.
 */
class LevellingTagsTest {
    private fun amp(db: Float) = 10f.pow(db / 20f)

    // Media3's byte reader checks its bounds strictly under tests, and finds out whether it runs under one from
    // Build.FINGERPRINT, which is null on the JVM; say it outright.
    @Before
    fun strictReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)

    @After
    fun defaultReads() = ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(null)

    // ID3v2 (id3.org): a header "ID3", version, revision, flags and a synchsafe size, then frames of "TXXX", a size, two
    // flag bytes, and the body: an encoding byte, the description, a terminator, the value. v2.3 writes the frame size
    // as a plain 32-bit number and knows ISO-8859-1 and UTF-16 with a BOM; v2.4 writes it synchsafe, adds UTF-8, and
    // lets a text frame hold several values, each ended by the terminator.

    private class Txxx(val description: String, vararg val values: String)

    private fun id3(version: Int, encoding: Int, vararg frames: Txxx): Metadata {
        val body = ByteArrayOutputStream()
        for (frame in frames) {
            val data = ByteArrayOutputStream()
            data.write(encoding)
            (listOf(frame.description) + frame.values).forEachIndexed { at, text ->
                if (at > 0) data.write(if (encoding == UTF_16) byteArrayOf(0, 0) else byteArrayOf(0))
                data.write(text.toByteArray(if (encoding == UTF_16) Charsets.UTF_16 else if (encoding == UTF_8) Charsets.UTF_8 else Charsets.ISO_8859_1))
            }
            val bytes = data.toByteArray()
            body.write("TXXX".toByteArray(Charsets.ISO_8859_1))
            body.write(if (version == 4) synchsafe(bytes.size) else bigEndian(bytes.size))
            body.write(byteArrayOf(0, 0))
            body.write(bytes)
        }
        val tag = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(version.toByte(), 0, 0) + synchsafe(body.size()) + body.toByteArray()
        return checkNotNull(Id3Decoder().decode(tag, tag.size))
    }

    private fun bigEndian(value: Int) = ByteArray(4) { (value shr (8 * (3 - it))).toByte() }

    private fun synchsafe(value: Int) = ByteArray(4) { ((value shr (7 * (3 - it))) and 0x7F).toByte() }

    @Test
    fun `ID3v2_3 TXXX frames, in ISO-8859-1 or UTF-16, give gains and peaks`() {
        val latin = id3(3, ISO_8859_1, Txxx("REPLAYGAIN_TRACK_GAIN", "-7.85 dB"), Txxx("REPLAYGAIN_TRACK_PEAK", "0.988525"))
        assertEquals(ReplayGainTags(trackGainDb = -7.85f, trackPeak = 0.988525f), ReplayGainTags.from(latin))
        val unicode = id3(3, UTF_16, Txxx("REPLAYGAIN_ALBUM_GAIN", "+1.20 dB"), Txxx("REPLAYGAIN_ALBUM_PEAK", "1.012207"))
        assertEquals(ReplayGainTags(albumGainDb = 1.2f, albumPeak = 1.012207f), ReplayGainTags.from(unicode))
    }

    @Test
    fun `ID3v2_4 TXXX frames in UTF-8 give gains and peaks, and only a frame's first value counts`() {
        val read = ReplayGainTags.from(
            id3(
                4, UTF_8,
                Txxx("REPLAYGAIN_TRACK_GAIN", "-3.50 dB", "+9.00 dB"),
                Txxx("replaygain_track_peak", "0.500000"),
                Txxx("ReplayGain_Album_Gain", "-2.25 dB"),
            ),
        )
        assertEquals(ReplayGainTags(trackGainDb = -3.5f, trackPeak = 0.5f, albumGainDb = -2.25f), read)
    }

    @Test
    fun `other ID3 text frames are not ReplayGain tags`() {
        val read = ReplayGainTags.from(Metadata(TextInformationFrame("TIT2", null, listOf("-6.00 dB")), TextInformationFrame("TCOM", "REPLAYGAIN_TRACK_GAIN", listOf("-6.00 dB"))))
        assertEquals(ReplayGainTags.NONE, read)
    }

    // Vorbis comments (xiph.org): FIELD=value, the field name case-insensitive. FLAC and Ogg Vorbis carry them.

    @Test
    fun `Vorbis comments give gains and peaks, whatever the field name's case`() {
        val comments = checkNotNull(
            VorbisUtil.parseVorbisComments(listOf("replaygain_track_gain=-4.20 dB", "Replaygain_Track_Peak=0.700000", "REPLAYGAIN_ALBUM_GAIN=-3.10 dB", "TITLE=Lantern Hours")),
        )
        assertEquals(ReplayGainTags(trackGainDb = -4.2f, trackPeak = 0.7f, albumGainDb = -3.1f), ReplayGainTags.from(comments))
    }

    // MP4 freeform atoms: "----" with a mean (the domain), a name and a data atom; Media3 hands them on as InternalFrame
    // (domain, name, text).

    @Test
    fun `MP4 freeform atoms count in the com_apple_iTunes domain only, with names in any case`() {
        val read = ReplayGainTags.from(
            Metadata(
                InternalFrame("com.apple.iTunes", "REPLAYGAIN_ALBUM_GAIN", "-2.10 dB"),
                InternalFrame("com.apple.iTunes", "replaygain_album_peak", "0.812000"),
                InternalFrame("org.example.notes", "replaygain_track_gain", "-9.00 dB"),
                InternalFrame("org.example.notes", "replaygain_track_peak", "0.100000"),
            ),
        )
        assertEquals(ReplayGainTags(albumGainDb = -2.1f, albumPeak = 0.812f), read)
    }

    // Ogg Opus (RFC 7845) in Ogg pages (RFC 3533): the ID header "OpusHead" carries the output gain, the comment header
    // "OpusTags" the tags, then the audio.

    private fun opus(outputGainQ78: Int, vararg comments: String): Format {
        val head = ascii("OpusHead") + byteArrayOf(1, 2) + little(312, 2) + little(48_000, 4) + little(outputGainQ78.toLong(), 2) + byteArrayOf(0)
        val vendor = ascii("Quillmere encoder 1.0")
        val tags = ByteArrayOutputStream().apply {
            write(ascii("OpusTags"))
            write(little(vendor.size.toLong(), 4))
            write(vendor)
            write(little(comments.size.toLong(), 4))
            for (comment in comments) {
                val bytes = comment.toByteArray(Charsets.UTF_8)
                write(little(bytes.size.toLong(), 4))
                write(bytes)
            }
        }.toByteArray()
        // One packet of CELT fullband 20 ms stereo (TOC 0xFC), one frame.
        val audio = byteArrayOf(0xFC.toByte(), 0x10, 0x20)
        return formatOf(OggExtractor(), page(0, FIRST_PAGE, 0, head) + page(1, 0, 0, tags) + page(2, LAST_PAGE, 960, audio))
    }

    private fun page(sequence: Int, type: Int, granule: Long, packet: ByteArray): ByteArray {
        val lacing = ByteArrayOutputStream()
        var left = packet.size
        while (left >= 255) {
            lacing.write(255)
            left -= 255
        }
        lacing.write(left)
        return ascii("OggS") + byteArrayOf(0, type.toByte()) + little(granule, 8) + little(0x5157, 4) + little(sequence.toLong(), 4) +
            little(0, 4) + byteArrayOf(lacing.size().toByte()) + lacing.toByteArray() + packet
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun little(value: Long, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }

    /** The format [extractor] gives the first track of [bytes]. */
    private fun formatOf(extractor: Extractor, bytes: ByteArray): Format {
        val formats = ArrayList<Format>()
        val track = object : TrackOutput {
            override fun format(format: Format) {
                formats += format
            }

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = input.read(ByteArray(length), 0, length)

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
        }
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = track
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        var at = 0
        val reader = DataReader { buffer, offset, length ->
            if (at == bytes.size) {
                C.RESULT_END_OF_INPUT
            } else {
                minOf(length, bytes.size - at).also { count ->
                    bytes.copyInto(buffer, offset, at, at + count)
                    at += count
                }
            }
        }
        val input = DefaultExtractorInput(reader, 0, C.LENGTH_UNSET.toLong())
        do {
            val result = extractor.read(input, PositionHolder())
        } while (formats.isEmpty() && result == Extractor.RESULT_CONTINUE)
        return formats.single()
    }

    @Test
    fun `Opus R128 gains are Q7_8 dB against -23 LUFS, so they get +5 dB to reach ReplayGain's -18 LUFS`() {
        val format = opus(0, "R128_TRACK_GAIN=-573", "R128_ALBUM_GAIN=111", "TITLE=Lantern Hours")
        assertEquals(ReplayGainTags(trackGainDb = -573 / 256f + 5f, albumGainDb = 111 / 256f + 5f), ReplayGainTags.from(format.metadata))
    }

    @Test
    fun `the Opus header's output gain is the decoder's, so levelling adds only the R128 gain on top of it`() {
        // RFC 7845 asks for the R128 gain in addition to the output gain. Android's Opus decoder applies the output gain
        // itself (it reads OpusHead from the codec data), so the gain KJB applies leaves it out.
        val louder = opus(3 * 256, "R128_TRACK_GAIN=-2304")
        val quieter = opus(-2 * 256, "R128_TRACK_GAIN=-2304")
        assertEquals(ReplayGainTags(trackGainDb = -4f), ReplayGainTags.from(louder.metadata))
        assertEquals(ReplayGainTags(trackGainDb = -4f), ReplayGainTags.from(quieter.metadata))
        // The output gain reaches the decoder: bytes 16 and 17 of OpusHead, little endian.
        val head = louder.initializationData.first()
        assertEquals(3 * 256, (head[16].toInt() and 0xFF) or (head[17].toInt() shl 8))
    }

    private fun r128(value: String) = ReplayGainTags.from(Metadata(VorbisComment("R128_TRACK_GAIN", value))).trackGainDb

    @Test
    fun `R128 values follow RFC 7845, a base-10 integer of at most 6 characters from -32768 to 32767`() {
        assertEquals(111 / 256f + 5f, r128("+111"))
        assertEquals(-573 / 256f + 5f, r128("-0573"))
        assertEquals(-128f + 5f, r128("-32768"))
        assertEquals(32_767 / 256f + 5f, r128("32767"))
        for (wrong in listOf("1.5", " 256", "256 ", "32768", "-32769", "-000573", "0x100", "", "-", "256 dB", "Infinity")) assertNull(wrong, r128(wrong))
    }

    @Test
    fun `R128 gains win over REPLAYGAIN gains in an Opus file, and count only in Vorbis comments`() {
        val both = ReplayGainTags.from(
            Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-3.00 dB"), VorbisComment("R128_TRACK_GAIN", "-2304"), VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-1.00 dB")),
        )
        assertEquals(ReplayGainTags(trackGainDb = -4f, albumGainDb = -1f), both)
        val elsewhere = Metadata(TextInformationFrame("TXXX", "R128_TRACK_GAIN", listOf("-2304")), InternalFrame("com.apple.iTunes", "R128_ALBUM_GAIN", "256"))
        assertEquals(ReplayGainTags.NONE, ReplayGainTags.from(elsewhere))
    }

    // Value formats (ReplayGain 2.0, "Metadata format"): gains "[-]a.bb dB", peaks "c.dddddd", and the variations a
    // robust player reads. "Other formatting errors" drop the value as if the tag were not there.

    private fun gain(value: String) = ReplayGainTags.from(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", value))).trackGainDb

    private fun peak(value: String) = ReplayGainTags.from(Metadata(VorbisComment("REPLAYGAIN_TRACK_PEAK", value))).trackPeak

    @Test
    fun `gains are read with a leading plus, more or fewer digits, leading zeros or spaces, and a missing or odd dB`() {
        val read = mapOf(
            "-7.85 dB" to -7.85f, "+3.50 dB" to 3.5f, "3.5" to 3.5f, "-6" to -6f, "3.5dB" to 3.5f, "3.50 DB" to 3.5f, "-2.25 db" to -2.25f,
            " 03.50 dB" to 3.5f, "-07.850 dB" to -7.85f, "- 4.00 dB" to -4f, "0.125 dB" to 0.125f, ".5 dB" to 0.5f, "12 dB " to 12f,
        )
        read.forEach { (text, db) -> assertEquals(text, db, gain(text)) }
    }

    @Test
    fun `other formatting errors drop the gain, and a well-formed duplicate is read instead`() {
        for (wrong in listOf("3,50 dB", "+2,40 dB", "3.5 LU", "loud", "", " dB", "NaN", "Infinity", "1e1 dB", "3.5 dB dB", "--3.5", "+-3.5", "3.5.0", "3.5 dBFS")) {
            assertNull(wrong, gain(wrong))
        }
        val duplicate = Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "3,5 dB"), VorbisComment("REPLAYGAIN_TRACK_GAIN", "-1.50 dB"))
        assertEquals(-1.5f, ReplayGainTags.from(duplicate).trackGainDb)
    }

    @Test
    fun `peaks are positive decimals without a unit, 1 being full scale, and anything else counts as missing`() {
        val read = mapOf("0.988525" to 0.988525f, "1.000000" to 1f, "1.25" to 1.25f, "+0.5" to 0.5f, " 0.250000 " to 0.25f)
        read.forEach { (text, value) -> assertEquals(text, value, peak(text)) }
        for (wrong in listOf("-0.5", "0", "0.000000", "0,5", "0.5 dB", "loud", "NaN")) assertNull(wrong, peak(wrong))
    }

    @Test
    fun `a reference loudness in LUFS moves ReplayGain gains to the -18 LUFS reference, and other units are not LUFS`() {
        fun read(reference: String) = ReplayGainTags.from(
            Metadata(
                VorbisComment("REPLAYGAIN_REFERENCE_LOUDNESS", reference),
                VorbisComment("REPLAYGAIN_TRACK_GAIN", "-2.00 dB"),
                VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.500000"),
                VorbisComment("REPLAYGAIN_ALBUM_GAIN", "+1.00 dB"),
            ),
        )
        // Tagged to reach -14 LUFS, so 4 dB less reaches -18; tagged for -23 LUFS, 5 dB more. Peaks never move.
        assertEquals(ReplayGainTags(trackGainDb = -6f, trackPeak = 0.5f, albumGainDb = -3f), read("-14.00 LUFS"))
        assertEquals(ReplayGainTags(trackGainDb = -6f, trackPeak = 0.5f, albumGainDb = -3f), read("-14LUFS"))
        assertEquals(ReplayGainTags(trackGainDb = 3f, trackPeak = 0.5f, albumGainDb = 6f), read("-23 lufs"))
        // ReplayGain 1's "89.0 dB" is a sound pressure level, and a bare number has no unit: neither moves anything.
        for (other in listOf("89.0 dB", "-14", "loud")) assertEquals(other, ReplayGainTags(trackGainDb = -2f, trackPeak = 0.5f, albumGainDb = 1f), read(other))
    }

    // Player requirements (ReplayGain 2.0): the gain is min(10^((gain + pre-amp) / 20), 1 / peak); a missing peak is 1.0.

    @Test
    fun `the gain is the tag's plus the boost, held to 1 over the peak`() {
        assertEquals(amp(-3f), levellingGain(ReplayGainTags(trackGainDb = -5f, trackPeak = 0.9f), Levelling.SONG, albumInOrder = false, boostDb = 2), 1e-6f)
        assertEquals(1f / 0.7f, levellingGain(ReplayGainTags(trackGainDb = 2f, trackPeak = 0.7f), Levelling.SONG, albumInOrder = false, boostDb = 4), 1e-6f)
        assertEquals(1f / 0.9f, levellingGain(ReplayGainTags(albumGainDb = 4f, albumPeak = 0.9f), Levelling.ALBUM, albumInOrder = true, boostDb = 0), 1e-6f)
        // A peak over full scale (lossy decoding) cuts below the tag's gain.
        assertEquals(1f / 1.5f, levellingGain(ReplayGainTags(trackGainDb = -1f, trackPeak = 1.5f), Levelling.SONG, albumInOrder = false, boostDb = 0), 1e-6f)
    }

    @Test
    fun `a missing peak is taken from the other kind, which keeps this song at or below full scale, else 1_0`() {
        // Album gain with the song's own peak: the song peaks at 0.5, so 2x brings it to full scale exactly.
        assertEquals(2f, levellingGain(ReplayGainTags(albumGainDb = 9f, trackPeak = 0.5f), Levelling.ALBUM, albumInOrder = false, boostDb = 0), 1e-6f)
        // Song gain with the album's peak: the album's is at least the song's.
        assertEquals(2.5f, levellingGain(ReplayGainTags(trackGainDb = 9f, albumPeak = 0.4f), Levelling.SONG, albumInOrder = false, boostDb = 0), 1e-6f)
        assertEquals(1f, levellingGain(ReplayGainTags(trackGainDb = 9f, albumGainDb = 9f), Levelling.AUTO, albumInOrder = true, boostDb = 0), 0f)
    }

    @Test
    fun `songs with no gain tag play unchanged, whatever else they carry`() {
        val peaksOnly = ReplayGainTags.from(
            id3(4, UTF_8, Txxx("REPLAYGAIN_TRACK_PEAK", "0.300000"), Txxx("REPLAYGAIN_ALBUM_PEAK", "0.400000"), Txxx("REPLAYGAIN_REFERENCE_LOUDNESS", "-14.00 LUFS")),
        )
        assertEquals(ReplayGainTags(trackPeak = 0.3f, albumPeak = 0.4f), peaksOnly)
        for (levelling in Levelling.entries) {
            assertEquals(1f, levellingGain(peaksOnly, levelling, albumInOrder = true, boostDb = 6), 0f)
            assertEquals(1f, levellingGain(ReplayGainTags.from(Metadata()), levelling, albumInOrder = false, boostDb = -6), 0f)
        }
    }

    @Test
    fun `Auto takes the album gain for an album in track order, and the song gain otherwise`() {
        val tags = ReplayGainTags.from(
            id3(
                3, ISO_8859_1,
                Txxx("REPLAYGAIN_TRACK_GAIN", "-9.00 dB"), Txxx("REPLAYGAIN_TRACK_PEAK", "0.600000"),
                Txxx("REPLAYGAIN_ALBUM_GAIN", "-4.00 dB"), Txxx("REPLAYGAIN_ALBUM_PEAK", "0.800000"),
            ),
        )
        assertEquals(amp(-4f), levellingGain(tags, Levelling.AUTO, albumInOrder = true, boostDb = 0), 1e-6f)
        assertEquals(amp(-9f), levellingGain(tags, Levelling.AUTO, albumInOrder = false, boostDb = 0), 1e-6f)
        assertEquals(1f / 0.8f, levellingGain(tags, Levelling.AUTO, albumInOrder = true, boostDb = 6), 1e-6f)
    }

    @Test
    fun `the stage tells the limiter when levelling boosts, so it watches for overs`() {
        val chain = ChainState()
        fun play(levels: SongLevels): Float {
            Signals.run(listOf(ReplayGainProcessor({ AudioPipelineSettings(Levelling.SONG) }, { levels }, chain)), Signals.sine(440.0, 0.2f, seconds = 0.05))
            return chain.levellingGain
        }
        assertTrue(play(SongLevels(ReplayGainTags(trackGainDb = 6f, trackPeak = 0.25f), albumInOrder = false)) > 1f)
        assertTrue(play(SongLevels(ReplayGainTags(trackGainDb = -6f, trackPeak = 0.25f), albumInOrder = false)) <= 1f)
        assertEquals(1f, play(SongLevels.NONE), 0f)
    }

    private companion object {
        const val ISO_8859_1: Int = 0
        const val UTF_16: Int = 1
        const val UTF_8: Int = 3
        const val FIRST_PAGE: Int = 0x02
        const val LAST_PAGE: Int = 0x04
    }
}
