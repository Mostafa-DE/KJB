@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the equalizer does on one output: its own on/off, the 10 bands, bass boost, pre-amp, balance and mono. Off, it
 * changes nothing at all, so a new output, which starts Flat and off, sounds as it did. Values outside their ranges are
 * held to them where the chain applies them.
 */
data class EqSound(
    val enabled: Boolean = false,
    /** One gain per band of [EQ_BANDS_HZ], in dB, within ±[BAND_DB]. */
    val bandsDb: List<Float> = FLAT_BANDS,
    /** The bass boost low shelf, 0 to [BASS_BOOST_DB] dB. */
    val bassBoostDb: Float = 0f,
    /** The pre-amp before the limiter, within ±[PREAMP_DB] dB. */
    val preampDb: Float = 0f,
    /** -1 is left only, 0 the middle, 1 right only. */
    val balance: Float = 0f,
    /** Both channels play their mix. */
    val mono: Boolean = false,
) {
    init {
        require(bandsDb.size == EQ_BANDS_HZ.size) { "one gain per band" }
    }

    companion object {
        const val BAND_DB: Float = 12f
        const val BASS_BOOST_DB: Float = 12f
        const val PREAMP_DB: Float = 12f
    }
}

/** The centres of the 10 peaking bands, an octave apart. */
val EQ_BANDS_HZ: List<Int> = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

val FLAT_BANDS: List<Float> = List(EQ_BANDS_HZ.size) { 0f }

/** The presets that come with KJB, in the order the equalizer lists them. The UI names them (strings_audio.xml). */
enum class BuiltInPreset(vararg db: Int) {
    FLAT(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
    BASS(6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
    BASS_AND_TREBLE(5, 4, 3, 1, 0, 0, 1, 3, 4, 5),
    VOCAL(-2, -2, -1, 0, 2, 4, 4, 3, 1, 0),
    ROCK(4, 3, 2, 0, -1, -1, 0, 2, 3, 4),
    POP(-1, 0, 2, 3, 4, 3, 1, 0, -1, -1),
    CLASSICAL(3, 2, 1, 0, 0, 0, 0, 1, 2, 3),
    JAZZ(3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
    ELECTRONIC(5, 4, 1, 0, -2, 1, 0, 1, 4, 5),
    ACOUSTIC(4, 3, 2, 1, 1, 1, 2, 3, 3, 2),

    /** A phone or laptop speaker: no deep bass it cannot play, more low mids and presence. */
    SMALL_SPEAKERS(-6, -4, 0, 3, 3, 2, 1, 2, 3, 2),
    ;

    val bandsDb: List<Float> = db.map(Int::toFloat)
}

/** A preset the user saved ("Save preset"): a name and the 10 band gains. */
data class OwnPreset(val name: String, val bandsDb: List<Float>) {
    init {
        require(name.isNotBlank()) { "a preset has a name" }
        require(bandsDb.size == EQ_BANDS_HZ.size) { "one gain per band" }
    }
}

/** The preset a profile's bands come from; a profile whose bands were moved by hand has none. */
sealed interface PresetChoice {
    data class BuiltIn(val preset: BuiltInPreset) : PresetChoice

    data class Own(val name: String) : PresetChoice
}

/**
 * Biquad filter coefficients from Robert Bristow-Johnson's Audio EQ Cookbook, stored as five doubles per filter,
 * normalised by a0: b0, b1, b2, a1, a2.
 */
internal object Biquad {
    const val SIZE: Int = 5

    /** A peaking band: exactly [gainDb] at [hz], fading to nothing an octave or so either side for [q] near 1.4. */
    fun peaking(hz: Double, gainDb: Double, q: Double, rate: Double, out: DoubleArray, at: Int) {
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * hz / rate
        val alpha = sin(w0) / (2 * q)
        val cosW0 = cos(w0)
        set(out, at, 1 + alpha * a, -2 * cosW0, 1 - alpha * a, 1 + alpha / a, -2 * cosW0, 1 - alpha / a)
    }

    /** A low shelf: [gainDb] at the lowest frequencies, half of it at [hz], nothing far above; [slope] 1 is the steepest without a bump. */
    fun lowShelf(hz: Double, gainDb: Double, slope: Double, rate: Double, out: DoubleArray, at: Int) {
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * hz / rate
        val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val cosW0 = cos(w0)
        val twoRootAAlpha = 2 * sqrt(a) * alpha
        set(
            out, at,
            a * ((a + 1) - (a - 1) * cosW0 + twoRootAAlpha),
            2 * a * ((a - 1) - (a + 1) * cosW0),
            a * ((a + 1) - (a - 1) * cosW0 - twoRootAAlpha),
            (a + 1) + (a - 1) * cosW0 + twoRootAAlpha,
            -2 * ((a - 1) + (a + 1) * cosW0),
            (a + 1) + (a - 1) * cosW0 - twoRootAAlpha,
        )
    }

    private fun set(out: DoubleArray, at: Int, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        out[at] = b0 / a0
        out[at + 1] = b1 / a0
        out[at + 2] = b2 / a0
        out[at + 3] = a1 / a0
        out[at + 4] = a2 / a0
    }
}

/**
 * The second stage: the 10 peaking bands, then the bass boost low shelf, in double precision (the 31 Hz band's
 * coefficients sit close to 1). A change of gain glides by [STEP_DB] every [BLOCK] frames, recomputing only the filters
 * that move, so a profile switch or a slider does not click. Flat or off, it passes samples through untouched.
 */
internal class EqualizerProcessor(settings: () -> AudioPipelineSettings) : FloatStage(settings) {
    private val target = FloatArray(FILTERS)
    private val current = FloatArray(FILTERS)
    private val coefficients = DoubleArray(FILTERS * Biquad.SIZE)

    /** Two state values per filter and channel (transposed direct form II). */
    private var state = DoubleArray(0)

    /** Bands whose centre the sample rate can carry; the others stay at 0 dB. */
    private val usable = BooleanArray(BANDS)
    private var running = false
    private var fresh = true
    private var blockLeft = 0

    override fun start(stream: StreamMetadata) {
        val size = channels * FILTERS * 2
        if (state.size != size) state = DoubleArray(size) else state.fill(0.0)
        for (band in 0 until BANDS) usable[band] = EQ_BANDS_HZ[band] < sampleRate * MAX_CENTRE_SHARE
        fresh = true
    }

    override fun begin(settings: AudioPipelineSettings) {
        val eq = settings.eq
        for (band in 0 until BANDS) {
            target[band] = if (eq.enabled && usable[band]) eq.bandsDb[band].coerceIn(-EqSound.BAND_DB, EqSound.BAND_DB) else 0f
        }
        target[SHELF] = if (eq.enabled) eq.bassBoostDb.coerceIn(0f, EqSound.BASS_BOOST_DB) else 0f
        if (fresh) {
            target.copyInto(current)
            for (filter in 0 until FILTERS) design(filter)
            fresh = false
            blockLeft = BLOCK
        }
        val wasRunning = running
        running = current.any { it != 0f } || target.any { it != 0f }
        // Idle filters forget their past, so starting again later begins from silence.
        if (wasRunning && !running) state.fill(0.0)
    }

    override fun process(frame: FloatArray) {
        if (blockLeft == 0) {
            glide()
            blockLeft = BLOCK
        }
        blockLeft--
        if (!running) return
        for (c in frame.indices) {
            var x = frame[c].toDouble()
            var z = c * FILTERS * 2
            var k = 0
            repeat(FILTERS) {
                val y = coefficients[k] * x + state[z]
                state[z] = coefficients[k + 1] * x - coefficients[k + 3] * y + state[z + 1]
                state[z + 1] = coefficients[k + 2] * x - coefficients[k + 4] * y
                x = y
                z += 2
                k += Biquad.SIZE
            }
            frame[c] = x.toFloat()
        }
    }

    /** Moves every filter that is not at its target one step closer. */
    private fun glide() {
        for (filter in 0 until FILTERS) {
            val gap = target[filter] - current[filter]
            if (gap == 0f) continue
            current[filter] = if (abs(gap) <= STEP_DB) target[filter] else current[filter] + STEP_DB * sign(gap)
            design(filter)
        }
    }

    private fun design(filter: Int) {
        val at = filter * Biquad.SIZE
        val rate = sampleRate.toDouble()
        if (filter == SHELF) Biquad.lowShelf(SHELF_HZ, current[filter].toDouble(), SHELF_SLOPE, rate, coefficients, at)
        else Biquad.peaking(EQ_BANDS_HZ[filter].toDouble(), current[filter].toDouble(), BAND_Q, rate, coefficients, at)
    }

    private companion object {
        val BANDS: Int = EQ_BANDS_HZ.size

        /** The bass boost shelf runs after the bands. */
        val SHELF: Int = BANDS
        val FILTERS: Int = BANDS + 1

        /** About one octave wide, so neighbouring bands meet near their -3 dB points. */
        const val BAND_Q: Double = 1.41
        const val SHELF_HZ: Double = 100.0
        const val SHELF_SLOPE: Double = 1.0

        /** A band applies only well below half the sample rate. */
        const val MAX_CENTRE_SHARE: Double = 0.45
        const val BLOCK: Int = 32
        const val STEP_DB: Float = 0.25f
    }
}

/** The third stage: mono, then balance. Stereo only; other layouts pass through. */
internal class BalanceProcessor(settings: () -> AudioPipelineSettings) : FloatStage(settings) {
    /** 0 is stereo as recorded, 1 is mono. */
    private val mix = Glide(0f)
    private val left = Glide()
    private val right = Glide()
    private var mixTarget = 0f
    private var leftTarget = 1f
    private var rightTarget = 1f
    private var fresh = true

    override fun start(stream: StreamMetadata) {
        mix.setRate(sampleRate)
        left.setRate(sampleRate)
        right.setRate(sampleRate)
        fresh = true
    }

    override fun begin(settings: AudioPipelineSettings) {
        val eq = settings.eq
        val balance = if (eq.enabled) eq.balance.coerceIn(-1f, 1f) else 0f
        mixTarget = if (eq.enabled && eq.mono) 1f else 0f
        leftTarget = min(1f, 1f - balance)
        rightTarget = min(1f, 1f + balance)
        if (fresh) {
            mix.value = mixTarget
            left.value = leftTarget
            right.value = rightTarget
            fresh = false
        }
    }

    override fun process(frame: FloatArray) {
        if (frame.size != 2) return
        val share = mix.next(mixTarget) / 2
        val l = frame[0]
        val r = frame[1]
        frame[0] = (if (share == 0f) l else l + (r - l) * share) * left.next(leftTarget)
        frame[1] = (if (share == 0f) r else r + (l - r) * share) * right.next(rightTarget)
    }
}

/**
 * The last stage: the pre-amp, then a soft limiter, handing 16-bit PCM on. The limiter watches the loudest channel of
 * each frame: below [KNEE_START] it does nothing; through a [KNEE_DB] soft knee it eases the level towards [LIMIT_DB],
 * which nothing passes. It turns down at once and back up over [RELEASE_SECONDS], so a sine boosted far past full scale
 * keeps its shape instead of being clipped flat. With no stage changing the sound it stands aside, and 16-bit music
 * leaves the chain exactly as it came.
 */
internal class LimiterProcessor(settings: () -> AudioPipelineSettings) : FloatStage(settings, int16Out = true) {
    private val preamp = Glide()
    private var preampTarget = 1f
    private var engaged = false
    private var gain = 1f
    private var release = 1f
    private var fresh = true

    override fun start(stream: StreamMetadata) {
        preamp.setRate(sampleRate)
        release = 1f - exp(-1f / (RELEASE_SECONDS * sampleRate))
        gain = 1f
        fresh = true
    }

    override fun begin(settings: AudioPipelineSettings) {
        engaged = settings.processing
        preampTarget = if (settings.eq.enabled) dbToGain(settings.eq.preampDb.coerceIn(-EqSound.PREAMP_DB, EqSound.PREAMP_DB)) else 1f
        if (fresh) {
            preamp.value = preampTarget
            fresh = false
        }
    }

    override fun process(frame: FloatArray) {
        val pre = preamp.next(preampTarget)
        if (pre != 1f) for (c in frame.indices) frame[c] *= pre
        if (!engaged && gain == 1f) return
        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample))
        val wanted = if (peak <= KNEE_START) 1f else limit(peak)
        gain = if (wanted < gain) wanted else gain + (wanted - gain) * release
        if (wanted == 1f && 1f - gain < SETTLED) gain = 1f
        if (gain != 1f) for (c in frame.indices) frame[c] *= gain
    }

    /** The gain that brings [peak] onto the soft knee: a quadratic curve from [KNEE_START] up to [LIMIT_DB], then flat. */
    private fun limit(peak: Float): Float {
        val level = 20f * log10(peak)
        val over = level - (LIMIT_DB - KNEE_DB / 2)
        val gainDb = if (over < KNEE_DB) -(over * over) / (2 * KNEE_DB) else LIMIT_DB - level
        return dbToGain(gainDb)
    }

    private companion object {
        const val LIMIT_DB: Float = -0.3f
        const val KNEE_DB: Float = 2f
        val KNEE_START: Float = dbToGain(LIMIT_DB - KNEE_DB / 2)
        const val RELEASE_SECONDS: Float = 0.1f
        const val SETTLED: Float = 1e-6f
    }
}
