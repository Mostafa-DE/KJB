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
import kotlin.math.abs
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
    fun `each band boosts and cuts by its own gain at its centre, over a sweep of the 10 centres at 44,1, 48 and 96 kHz`() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            EQ_BANDS_HZ.forEachIndexed { index, hz ->
                for (db in listOf(12f, 6f, -12f)) {
                    assertEquals("band $hz Hz at $db dB, $rate Hz", db.toDouble(), gainAt(hz.toDouble(), eq(band(index, db)), rate), 0.2)
                }
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

    /**
     * The largest fourth difference of [samples] in [range]: for a smooth wave it stays at the level of its own bend and
     * the float rounding, while a step or a kink, even far below hearing, stands out.
     */
    private fun roughness(samples: FloatArray, range: IntRange): Double =
        range.maxOf { i -> abs(samples[i - 2].toDouble() - 4 * samples[i - 1] + 6 * samples[i] - 4 * samples[i + 1] + samples[i + 2]) }

    @Test
    fun `a band glides to a new gain without any step in the wave, even at 31 Hz`() {
        for ((hz, index) in listOf(31.0 to 0, 1_000.0 to 5)) {
            val sine = Signals.sine(hz, 0.2f, seconds = 1.0)
            var settings = eq()
            val change = 256 * 80
            val out = Signals.channel(
                Signals.run(listOf(EqualizerProcessor { settings }), sine, chunk = 256) { frame ->
                    if (frame == change) settings = eq(band(index, 12f))
                },
                0,
            )
            // Once settled the wave is four times as loud; while it glides there, it is never rougher than then.
            val settled = roughness(out, out.size - 4_800 until out.size - 2)
            val gliding = roughness(out, change - 10 until change + 9_600)
            assertTrue("$hz Hz: $gliding while gliding, $settled settled", gliding <= settled * 1.5)
        }
    }

    @Test
    fun `a long silence after a tone leaves every filter state at exactly zero, never a denormal`() {
        val processor = EqualizerProcessor { eq(BuiltInPreset.BASS_AND_TREBLE.bandsDb, bassBoostDb = 12f) }
        val tone = Signals.sine(31.0, 0.5f, seconds = 0.5)
        Signals.run(listOf(processor), tone + FloatArray(Signals.RATE * 2 * 5))
        assertTrue(processor.state.isNotEmpty())
        assertTrue(processor.state.all { it == 0.0 })
    }

    // Balance and mono

    private val left = Signals.sine(500.0, 0.5f, channels = 1)
    private val silence = FloatArray(left.size)

    private fun balance(settings: AudioPipelineSettings, input: FloatArray) = Signals.run(listOf(BalanceProcessor { settings }), input)

    @Test
    fun `balance turns the other side down, never this side up`() {
        val both = Signals.interleave(left, left)
        val hardLeft = balance(eq(balance = -1f), both)
        assertArrayEquals(left, Signals.channel(hardLeft, 0), 0f)
        assertEquals(0f, Signals.peak(Signals.channel(hardLeft, 1)), 0f)
        val someRight = balance(eq(balance = 0.5f), both)
        assertArrayEquals(left.map { it * 0.5f }.toFloatArray(), Signals.channel(someRight, 0), 1e-6f)
        assertArrayEquals(left, Signals.channel(someRight, 1), 0f)
    }

    @Test
    fun `mono plays the mix of both channels on each`() {
        val out = balance(eq(mono = true), Signals.interleave(left, silence))
        val half = left.map { it / 2 }.toFloatArray()
        assertArrayEquals(half, Signals.channel(out, 0), 1e-6f)
        assertArrayEquals(half, Signals.channel(out, 1), 1e-6f)
    }

    @Test
    fun `with the equalizer off, balance and mono do nothing`() {
        val input = Signals.interleave(left, silence)
        assertArrayEquals(input, balance(eq(balance = -1f, mono = true, enabled = false), input), 0f)
    }

    private val other = Signals.sine(700.0, 0.25f, channels = 1)

    @Test
    fun `mono with more than two channels plays the mix of all of them on each`() {
        val channels = arrayOf(left, silence, other, left, other, silence)
        val out = Signals.run(listOf(BalanceProcessor { eq(mono = true) }), Signals.interleave(*channels), channels = 6)
        val mid = FloatArray(left.size) { i -> channels.sumOf { it[i].toDouble() }.toFloat() / 6 }
        for (c in 0 until 6) assertArrayEquals("channel $c", mid, Signals.channel(out, c, 6), 1e-6f)
    }

    @Test
    fun `balance with more than two channels turns down each left or right pair, never the centre`() {
        // 5.1: front left, front right, centre, low frequencies, back left, back right.
        val surround = Signals.run(listOf(BalanceProcessor { eq(balance = -1f) }), Signals.interleave(left, left, left, left, left, left), channels = 6)
        for (c in listOf(0, 2, 3, 4)) assertArrayEquals("channel $c", left, Signals.channel(surround, c, 6), 0f)
        for (c in listOf(1, 5)) assertEquals("channel $c", 0f, Signals.peak(Signals.channel(surround, c, 6)), 0f)
        // Quad: front left, front right, back left, back right.
        val quad = Signals.run(listOf(BalanceProcessor { eq(balance = 0.5f) }), Signals.interleave(left, left, left, left), channels = 4)
        for (c in listOf(0, 2)) assertArrayEquals("channel $c", left.map { it * 0.5f }.toFloatArray(), Signals.channel(quad, c, 4), 1e-6f)
        for (c in listOf(1, 3)) assertArrayEquals("channel $c", left, Signals.channel(quad, c, 4), 0f)
    }

    @Test
    fun `a mono song passes balance and mono unchanged`() {
        assertArrayEquals(left, Signals.run(listOf(BalanceProcessor { eq(balance = -1f, mono = true) }), left, channels = 1), 0f)
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
        val out = Signals.run(listOf(LimiterProcessor({ eq() })), input)
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
        val out = Signals.run(listOf(LimiterProcessor({ eq(preampDb = -6f) })), input)
        assertEquals(-6.0, Signals.gainDb(Signals.quantized16(input), out, settle = 0.1), 0.05)
    }

    @Test
    fun `with the defaults, an untagged full-scale song leaves the chain bit-identical`() {
        val input = Signals.quantized16(Signals.interleave(Signals.sine(1_000.0, 1f, channels = 1), Signals.sine(60.0, 0.99f, channels = 1)))
        assertTrue(AudioPipelineSettings().processing)
        assertArrayEquals(input, Signals.run(audioChain({ AudioPipelineSettings() }, { SongLevels.NONE }), input, encoding = C.ENCODING_PCM_16BIT), 0f)
    }

    @Test
    fun `cuts alone leave loud music to the limiter untouched`() {
        val input = Signals.quantized16(Signals.sine(1_000.0, 0.99f))
        val cuts = AudioPipelineSettings(Levelling.SONG, eq = EqSound(enabled = true, band(2, -6f), preampDb = -0.5f))
        // Levelling only cuts this song: what reaches the limiter is never louder than full scale, so it stands aside.
        val levels = SongLevels(ReplayGainTags(trackGainDb = -0.2f, trackPeak = 1f), albumInOrder = false)
        val chain = audioChain({ cuts }, { levels })
        val expected = Signals.run(audioChain({ cuts.copy(eq = cuts.eq.copy(preampDb = 0f)) }, { levels }).take(3), input, encoding = C.ENCODING_PCM_16BIT)
            .map { it * dbToGain(-0.5f) }.toFloatArray()
        assertArrayEquals(Signals.quantized16(expected), Signals.run(chain, input, encoding = C.ENCODING_PCM_16BIT), 0f)
    }

    @Test
    fun `a levelling boost from wrong peak tags is caught by the limiter`() {
        val input = Signals.quantized16(Signals.sine(1_000.0, 1f))
        // The tags claim a peak of a quarter, so +12 dB looks safe; the song really peaks at full scale.
        val wrong = SongLevels(ReplayGainTags(trackGainDb = 12f, trackPeak = 0.25f), albumInOrder = false)
        val out = Signals.run(audioChain({ AudioPipelineSettings(Levelling.SONG) }, { wrong }), input, encoding = C.ENCODING_PCM_16BIT)
        assertTrue("peak ${Signals.peak(out)}", Signals.peak(out) <= ceiling + 1f / 32_768)
        assertTrue("crest ${crest(out.sliceArray(out.size / 2 until out.size))}", crest(out.sliceArray(out.size / 2 until out.size)) > 1.38)
    }

    @Test
    fun `quiet music passes the limiter unchanged`() {
        val input = Signals.quantized16(Signals.sine(1_000.0, 0.5f))
        assertArrayEquals(input, Signals.run(listOf(LimiterProcessor({ eq() })), input), 0f)
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
