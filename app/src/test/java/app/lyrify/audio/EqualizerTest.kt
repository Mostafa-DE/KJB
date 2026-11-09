@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import app.lyrify.settings.Levelling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The equalizer's maths on sine sweeps, balance and mono, the limiter, the chain's order and the presets. */
class EqualizerTest {
    private fun eq(
        bandsDb: List<Float> = FLAT_BANDS,
        bassBoostDb: Float = 0f,
        preampDb: Float = 0f,
        balance: Float = 0f,
        mono: Boolean = false,
        enabled: Boolean = true,
    ) = AudioPipelineSettings(Levelling.OFF, eq = EqSound(enabled, bandsDb, bassBoostDb, preampDb, balance, mono))

    private fun band(index: Int, db: Float) = FLAT_BANDS.toMutableList().also { it[index] = db }

    private fun equalizer(settings: AudioPipelineSettings) = EqualizerProcessor { settings }

    /** The equalizer's gain in dB at [hz], measured on a settled sine. */
    private fun gainAt(hz: Double, settings: AudioPipelineSettings, rate: Int = Signals.RATE): Double {
        val sine = Signals.sine(hz, 0.1f, seconds = 1.0, rate = rate)
        return Signals.gainDb(sine, Signals.run(listOf(equalizer(settings)), sine, rate = rate), settle = 0.5, rate = rate)
    }

    // The bands

    @Test
    fun `each band boosts and cuts by its own gain at its centre, over a sweep of the 10 centres`() {
        EQ_BANDS_HZ.forEachIndexed { index, hz ->
            for (db in listOf(12f, 6f, -12f)) {
                assertEquals("band $hz Hz at $db dB", db.toDouble(), gainAt(hz.toDouble(), eq(band(index, db))), 0.2)
            }
        }
    }

    @Test
    fun `a band leaves frequencies far from its centre alone`() {
        assertEquals(0.0, gainAt(8_000.0, eq(band(0, 12f))), 0.1)
        assertEquals(0.0, gainAt(62.0, eq(band(9, -12f))), 0.1)
    }

    @Test
    fun `Flat and on hands the sound on unchanged, and off ignores every band`() {
        val sine = Signals.sine(1_000.0, 0.5f)
        assertArrayEquals(sine, Signals.run(listOf(equalizer(eq())), sine), 1e-6f)
        val off = eq(BuiltInPreset.ROCK.bandsDb, bassBoostDb = 12f, enabled = false)
        assertArrayEquals(sine, Signals.run(listOf(equalizer(off)), sine), 0f)
    }

    @Test
    fun `a band above what the sample rate can carry is left out, and the rest still works`() {
        // At 22,050 Hz the highest frequency is 11,025 Hz: a 16 kHz band would fold over onto about 6 kHz.
        assertEquals(0.0, gainAt(6_050.0, eq(band(9, 12f)), rate = 22_050), 0.1)
        assertEquals(12.0, gainAt(8_000.0, eq(band(8, 12f)), rate = 22_050), 0.2)
    }

    @Test
    fun `bass boost is a low shelf, full below, half at 100 Hz, nothing in the mids`() {
        val boost = eq(bassBoostDb = 6f)
        val deep = gainAt(25.0, boost)
        assertTrue("$deep dB at 25 Hz", deep in 5.3..6.05)
        assertEquals(3.0, gainAt(100.0, boost), 0.25)
        assertEquals(0.0, gainAt(2_000.0, boost), 0.1)
    }

    @Test
    fun `a change of band glides instead of jumping, so a profile switch does not click`() {
        val sine = Signals.sine(1_000.0, 0.1f)
        var settings = eq()
        val change = 256 * 40
        val out = Signals.run(listOf(EqualizerProcessor { settings }), sine, chunk = 256) { frame ->
            if (frame == change) settings = eq(band(5, 12f))
        }
        fun ratio(from: Int, frames: Int): Double {
            val window = from * 2 until (from + frames) * 2
            return Signals.rms(out.sliceArray(window)) / Signals.rms(sine.sliceArray(window))
        }
        assertEquals(1.0, ratio(change - 480, 480), 1e-4)
        assertTrue(ratio(change, 48) < 1.3)
        assertEquals(10.0.pow(12.0 / 20), ratio(change + 9_600, 480), 0.05)
    }

    // Balance and mono

    private val left = Signals.sine(500.0, 0.5f, channels = 1)
    private val silence = FloatArray(left.size)

    private fun balance(settings: AudioPipelineSettings, input: FloatArray) = Signals.run(listOf(BalanceProcessor { settings }), input)

    @Test
    fun `balance turns the other side down, never this side up`() {
        val both = Signals.stereo(left, left)
        val hardLeft = balance(eq(balance = -1f), both)
        assertArrayEquals(left, Signals.channel(hardLeft, 0), 0f)
        assertEquals(0f, Signals.peak(Signals.channel(hardLeft, 1)), 0f)
        val someRight = balance(eq(balance = 0.5f), both)
        assertArrayEquals(left.map { it * 0.5f }.toFloatArray(), Signals.channel(someRight, 0), 1e-6f)
        assertArrayEquals(left, Signals.channel(someRight, 1), 0f)
    }

    @Test
    fun `mono plays the mix of both channels on each`() {
        val out = balance(eq(mono = true), Signals.stereo(left, silence))
        val half = left.map { it / 2 }.toFloatArray()
        assertArrayEquals(half, Signals.channel(out, 0), 1e-6f)
        assertArrayEquals(half, Signals.channel(out, 1), 1e-6f)
    }

    @Test
    fun `with the equalizer off, balance and mono do nothing`() {
        val input = Signals.stereo(left, silence)
        assertArrayEquals(input, balance(eq(balance = -1f, mono = true, enabled = false), input), 0f)
    }

    // The pre-amp and the limiter

    private val ceiling = 10f.pow(-0.3f / 20f)

    /** The peak over the mean level: 1.414 for a sine, falling towards 1 as its tops are clipped flat. */
    private fun crest(samples: FloatArray) = Signals.peak(samples) / Signals.rms(samples)

    @Test
    fun `a full-scale sine through +12 dB band, +6 dB bass boost and +12 dB pre-amp leaves the chain below full scale, unclipped`() {
        val input = Signals.quantized16(Signals.sine(1_000.0, 1f))
        val loud = eq(band(5, 12f), bassBoostDb = 6f, preampDb = 12f)
        val out = Signals.run(audioChain({ loud }, { SongLevels.NONE }), input, encoding = C.ENCODING_PCM_16BIT)
        val settled = out.sliceArray(out.size / 2 until out.size)
        assertTrue("peak ${Signals.peak(out)}", Signals.peak(out) <= ceiling + 1f / 32_768)
        assertFalse(out.any { it >= 32_767f / 32_768 || it <= -1f })
        assertTrue("crest ${crest(settled)}", crest(settled) > 1.38)
    }

    @Test
    fun `the limiter keeps float input far above full scale at or below it`() {
        val input = Signals.sine(440.0, 4f)
        val out = Signals.run(listOf(LimiterProcessor { eq() }), input)
        val settled = out.sliceArray(out.size / 2 until out.size)
        assertTrue("peak ${Signals.peak(out)}", Signals.peak(out) <= ceiling + 1f / 32_768)
        assertTrue("crest ${crest(settled)}", crest(settled) > 1.38)
    }

    @Test
    fun `the stages pass float on, so a boost does not clip before the limiter`() {
        val input = Signals.quantized16(Signals.sine(1_000.0, 1f))
        val out = Signals.run(listOf(equalizer(eq(band(5, 12f), bassBoostDb = 6f))), input, encoding = C.ENCODING_PCM_16BIT)
        assertTrue("peak ${Signals.peak(out)}", Signals.peak(out) > 3.5f)
    }

    @Test
    fun `the pre-amp sets the level before the limiter`() {
        val input = Signals.sine(1_000.0, 0.1f)
        val out = Signals.run(listOf(LimiterProcessor { eq(preampDb = -6f) }), input)
        assertEquals(-6.0, Signals.gainDb(Signals.quantized16(input), out, settle = 0.1), 0.05)
    }

    @Test
    fun `quiet music passes the limiter unchanged`() {
        val input = Signals.quantized16(Signals.sine(1_000.0, 0.5f))
        assertArrayEquals(input, Signals.run(listOf(LimiterProcessor { eq() }), input), 0f)
    }

    @Test
    fun `with nothing on, the whole chain hands 16-bit music back exactly`() {
        val input = Signals.quantized16(Signals.sine(1_000.0, 1f))
        val off = AudioPipelineSettings(Levelling.OFF)
        assertArrayEquals(input, Signals.run(audioChain({ off }, { SongLevels.NONE }), input, encoding = C.ENCODING_PCM_16BIT), 0f)
    }

    // The chain

    @Test
    fun `the chain runs ReplayGain, the bands and bass boost, balance and mono, then the limiter, handing 16-bit on`() {
        val chain = audioChain({ AudioPipelineSettings() }, { SongLevels.NONE })
        assertEquals(
            listOf(ReplayGainProcessor::class, EqualizerProcessor::class, BalanceProcessor::class, LimiterProcessor::class),
            chain.map { it::class },
        )
        var format = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        val encodings = chain.map { processor -> processor.configure(format).also { format = it }.encoding }
        assertEquals(listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT), encodings)
        assertTrue(chain.all { it.isActive })
    }

    @Test
    fun `audio offload is used only when it is on and no stage changes the sound`() {
        val nothing = AudioPipelineSettings(Levelling.OFF)
        assertTrue(offloadAllowed(audioOffload = true, nothing))
        assertFalse(offloadAllowed(audioOffload = false, nothing))
        assertFalse(offloadAllowed(audioOffload = true, AudioPipelineSettings(Levelling.AUTO)))
        assertFalse(offloadAllowed(audioOffload = true, eq()))
        // Volume levelling is on (Auto) by default.
        assertTrue(AudioPipelineSettings().processing)
    }

    // Presets

    @Test
    fun `the 11 presets come in the spec's order, each a gain per band within 12 dB`() {
        assertEquals(
            listOf("FLAT", "BASS", "BASS_AND_TREBLE", "VOCAL", "ROCK", "POP", "CLASSICAL", "JAZZ", "ELECTRONIC", "ACOUSTIC", "SMALL_SPEAKERS"),
            BuiltInPreset.entries.map { it.name },
        )
        for (preset in BuiltInPreset.entries) {
            assertEquals(10, preset.bandsDb.size)
            assertTrue(preset.name, preset.bandsDb.all { it in -EqSound.BAND_DB..EqSound.BAND_DB })
        }
        assertEquals(FLAT_BANDS, BuiltInPreset.FLAT.bandsDb)
    }
}
