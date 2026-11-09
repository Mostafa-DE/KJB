@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.lyrify.settings.Levelling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** ReplayGain: the tags in each format (made-up values), the gain Volume levelling picks, and the first stage of the chain. */
class ReplayGainTest {
    private fun tags(vararg entries: Metadata.Entry) = ReplayGainTags.from(Metadata(*entries))

    private fun txxx(description: String, value: String) = TextInformationFrame("TXXX", description, listOf(value))

    private fun amp(db: Float) = 10f.pow(db / 20f)

    // Reading the tags

    @Test
    fun `MP3 ID3 TXXX frames give gains and peaks, with or without the dB unit`() {
        val read = tags(
            txxx("REPLAYGAIN_TRACK_GAIN", "-7.85 dB"),
            txxx("REPLAYGAIN_TRACK_PEAK", "0.988525"),
            txxx("replaygain_album_gain", "-6.5"),
            txxx("REPLAYGAIN_ALBUM_PEAK", "1.012207"),
        )
        assertEquals(ReplayGainTags(trackGainDb = -7.85f, trackPeak = 0.988525f, albumGainDb = -6.5f, albumPeak = 1.012207f), read)
    }

    @Test
    fun `FLAC and Ogg Vorbis comments give gains and peaks, also with a comma for the decimal point`() {
        val read = tags(
            VorbisComment("REPLAYGAIN_TRACK_GAIN", "+2,40 dB"),
            VorbisComment("REPLAYGAIN_TRACK_PEAK", "0,5"),
            VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-1.10 dB"),
        )
        assertEquals(ReplayGainTags(trackGainDb = 2.4f, trackPeak = 0.5f, albumGainDb = -1.1f), read)
    }

    @Test
    fun `Opus R128 gains are Q7_8 numbers against -23 LUFS, so they get the +5 dB offset`() {
        val read = tags(VorbisComment("R128_TRACK_GAIN", "-2304"), VorbisComment("R128_ALBUM_GAIN", "256"))
        // -2304 / 256 = -9 dB, +5 = -4 dB; 256 / 256 = 1 dB, +5 = 6 dB.
        assertEquals(ReplayGainTags(trackGainDb = -4f, albumGainDb = 6f), read)
    }

    @Test
    fun `M4A freeform atoms in the iTunes domain give gains and peaks, other domains do not`() {
        val read = tags(
            InternalFrame("com.apple.iTunes", "replaygain_track_gain", "-3.50 dB"),
            InternalFrame("com.apple.iTunes", "replaygain_track_peak", "0.75"),
            InternalFrame("org.example.notes", "replaygain_album_gain", "-9.00 dB"),
        )
        assertEquals(ReplayGainTags(trackGainDb = -3.5f, trackPeak = 0.75f), read)
    }

    @Test
    fun `a reference loudness other than -18 LUFS shifts the ReplayGain gains, never R128's`() {
        val read = tags(
            VorbisComment("REPLAYGAIN_REFERENCE_LOUDNESS", "-14 LUFS"),
            VorbisComment("REPLAYGAIN_TRACK_GAIN", "-2.00 dB"),
            VorbisComment("R128_ALBUM_GAIN", "0"),
        )
        // Tagged to reach -14 LUFS: 4 dB less reaches -18.
        assertEquals(ReplayGainTags(trackGainDb = -6f, albumGainDb = 5f), read)
    }

    @Test
    fun `untagged songs, other tags and unreadable values read as no tags`() {
        assertEquals(ReplayGainTags.NONE, ReplayGainTags.from(null))
        assertEquals(ReplayGainTags.NONE, tags())
        assertEquals(ReplayGainTags.NONE, tags(TextInformationFrame("TIT2", null, listOf("Paper Lantern")), VorbisComment("ARTIST", "Kit Calloway")))
        assertEquals(
            ReplayGainTags.NONE,
            tags(txxx("REPLAYGAIN_TRACK_GAIN", "loud"), VorbisComment("REPLAYGAIN_TRACK_PEAK", "NaN"), VorbisComment("R128_TRACK_GAIN", "Infinity")),
        )
    }

    // The gain levelling picks

    private val tagged = ReplayGainTags(trackGainDb = -8f, trackPeak = 0.2f, albumGainDb = -5f, albumPeak = 0.3f)

    @Test
    fun `Off changes nothing, even for a tagged song`() {
        assertEquals(1f, levellingGain(tagged, Levelling.OFF, albumInOrder = true, boostDb = 6), 0f)
    }

    @Test
    fun `Song and Album use their own gain`() {
        assertEquals(amp(-8f), levellingGain(tagged, Levelling.SONG, albumInOrder = true, boostDb = 0), 1e-6f)
        assertEquals(amp(-5f), levellingGain(tagged, Levelling.ALBUM, albumInOrder = false, boostDb = 0), 1e-6f)
    }

    @Test
    fun `Auto picks album gain for an album in track order, else song gain`() {
        assertEquals(amp(-5f), levellingGain(tagged, Levelling.AUTO, albumInOrder = true, boostDb = 0), 1e-6f)
        assertEquals(amp(-8f), levellingGain(tagged, Levelling.AUTO, albumInOrder = false, boostDb = 0), 1e-6f)
    }

    @Test
    fun `a missing gain falls back to the other one, with its peak`() {
        val trackOnly = ReplayGainTags(trackGainDb = -4f, trackPeak = 0.5f)
        val albumOnly = ReplayGainTags(albumGainDb = -3f, albumPeak = 0.5f)
        assertEquals(amp(-4f), levellingGain(trackOnly, Levelling.ALBUM, albumInOrder = false, boostDb = 0), 1e-6f)
        assertEquals(amp(-4f), levellingGain(trackOnly, Levelling.AUTO, albumInOrder = true, boostDb = 0), 1e-6f)
        assertEquals(amp(-3f), levellingGain(albumOnly, Levelling.SONG, albumInOrder = false, boostDb = 0), 1e-6f)
    }

    @Test
    fun `untagged songs play unchanged in every mode, whatever the boost`() {
        for (levelling in Levelling.entries) {
            assertEquals(1f, levellingGain(ReplayGainTags.NONE, levelling, albumInOrder = true, boostDb = 6), 0f)
            assertEquals(1f, levellingGain(ReplayGainTags(trackPeak = 0.5f, albumPeak = 0.5f), levelling, albumInOrder = false, boostDb = -6), 0f)
        }
    }

    @Test
    fun `the levelling boost is added to the tag's gain, from -6 to +6 dB`() {
        assertEquals(amp(-2f), levellingGain(tagged, Levelling.SONG, albumInOrder = false, boostDb = 6), 1e-6f)
        assertEquals(amp(-14f), levellingGain(tagged, Levelling.SONG, albumInOrder = false, boostDb = -6), 1e-6f)
        // Outside the range, the nearest end.
        assertEquals(amp(-2f), levellingGain(tagged, Levelling.SONG, albumInOrder = false, boostDb = 9), 1e-6f)
        assertEquals(amp(-14f), levellingGain(tagged, Levelling.SONG, albumInOrder = false, boostDb = -20), 1e-6f)
    }

    @Test
    fun `the gain is limited so that peak times gain stays at or below full scale`() {
        val quiet = ReplayGainTags(trackGainDb = 9f, trackPeak = 0.5f)
        // +9 dB is 2.82x, but a 0.5 peak allows 2x only.
        assertEquals(2f, levellingGain(quiet, Levelling.SONG, albumInOrder = false, boostDb = 0), 1e-6f)
        // The boost is limited the same way.
        assertEquals(2f, levellingGain(ReplayGainTags(trackGainDb = 3f, trackPeak = 0.5f), Levelling.SONG, albumInOrder = false, boostDb = 6), 1e-6f)
        // A peak above full scale lowers the gain below the tag's.
        assertEquals(1f / 1.25f, levellingGain(ReplayGainTags(trackGainDb = 0f, trackPeak = 1.25f), Levelling.SONG, albumInOrder = false, boostDb = 0), 1e-6f)
        // Cuts below the limit stay as tagged.
        assertEquals(amp(-8f), levellingGain(ReplayGainTags(trackGainDb = -8f, trackPeak = 0.99f), Levelling.SONG, albumInOrder = false, boostDb = 0), 1e-6f)
    }

    @Test
    fun `without a peak tag the song counts as peaking at full scale, so it is never boosted`() {
        assertEquals(1f, levellingGain(ReplayGainTags(trackGainDb = 6f), Levelling.SONG, albumInOrder = false, boostDb = 0), 0f)
        assertEquals(1f, levellingGain(ReplayGainTags(trackGainDb = 6f, trackPeak = 0f), Levelling.SONG, albumInOrder = false, boostDb = 0), 0f)
        assertEquals(amp(-6f), levellingGain(ReplayGainTags(trackGainDb = -6f), Levelling.SONG, albumInOrder = false, boostDb = 0), 1e-6f)
    }

    // The first stage of the chain

    private val sine = Signals.sine(440.0, 0.5f)

    private fun stage(settings: AudioPipelineSettings, levels: SongLevels) = ReplayGainProcessor({ settings }, { levels })

    @Test
    fun `the stage plays a tagged song at its gain from the first frame`() {
        val levels = SongLevels(ReplayGainTags(trackGainDb = -6f, trackPeak = 0.9f), albumInOrder = false)
        val out = Signals.run(listOf(stage(AudioPipelineSettings(Levelling.SONG), levels)), sine, encoding = C.ENCODING_PCM_16BIT)
        assertEquals(-6.0, Signals.gainDb(Signals.quantized16(sine), out, settle = 0.0), 0.01)
    }

    @Test
    fun `the stage hands an untagged song on unchanged`() {
        val input = Signals.quantized16(sine)
        val out = Signals.run(listOf(stage(AudioPipelineSettings(Levelling.AUTO), SongLevels.NONE)), input, encoding = C.ENCODING_PCM_16BIT)
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun `a change of setting mid-song glides to the new gain instead of jumping`() {
        val levels = SongLevels(ReplayGainTags(trackGainDb = -12f, trackPeak = 0.5f), albumInOrder = false)
        var settings = AudioPipelineSettings(Levelling.OFF)
        val change = 256 * 47
        val out = Signals.run(listOf(ReplayGainProcessor({ settings }, { levels })), sine, chunk = 256) { frame ->
            if (frame == change) settings = AudioPipelineSettings(Levelling.SONG)
        }
        fun ratio(from: Int, frames: Int): Double {
            val window = from * 2 until (from + frames) * 2
            return Signals.rms(out.sliceArray(window)) / Signals.rms(sine.sliceArray(window))
        }
        // In the first millisecond the gain is still close to 1; 100 ms later it is -12 dB.
        assertEquals(1.0, ratio(change - 480, 480), 1e-6)
        assertTrue(ratio(change, 48) > 0.8)
        assertEquals(amp(-12f).toDouble(), ratio(change + 4_800, 480), 0.005)
    }

    @Test
    fun `each stream asks for its own song, so a new song starts at its gain`() {
        val streams = mapOf<Any, SongLevels>(
            "first" to SongLevels(ReplayGainTags(trackGainDb = -6f, trackPeak = 0.9f), albumInOrder = false),
            "second" to SongLevels(ReplayGainTags(trackGainDb = -12f, trackPeak = 0.9f), albumInOrder = false),
        )
        val processor = ReplayGainProcessor({ AudioPipelineSettings(Levelling.SONG) }) { stream -> streams.getValue(stream.periodUid ?: "first") }
        val first = Signals.run(listOf(processor), sine)
        val second = Signals.run(listOf(processor), sine, stream = StreamMetadata.DEFAULT.buildUpon().setPeriodUid("second").build())
        assertEquals(-6.0, Signals.gainDb(sine, first, settle = 0.0), 0.01)
        assertEquals(-12.0, Signals.gainDb(sine, second, settle = 0.0), 0.01)
    }
}
