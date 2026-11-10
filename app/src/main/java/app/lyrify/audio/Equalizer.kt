@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

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
 * The second stage: the 10 bands, then the bass boost shelf, in double precision. Each is a linear trapezoidal state
 * variable filter (Andrew Simper, Cytomic, "Solving the continuous SVF equations using trapezoidal integration and
 * equivalent currents", 2013; his formulas, no code copied): the same bell and low shelf responses as the Audio EQ
 * Cookbook's peaking and shelf filters, but in a form whose gain may change on every sample without a click, because its
 * state is the circuit's own and not tied to the coefficients.
 *
 * A new gain is reached through three smoothing steps of [GLIDE_SECONDS] each, so the gain bends smoothly into and out
 * of every change (a slider drag, a profile switch) and the wave never steps or kinks. A filter whose two states both
 * fall below [DENORMAL_FLOOR] is set to zero, so a long silence never leaves them as slow denormal numbers. Flat or off,
 * it passes samples through untouched.
 */
internal class EqualizerProcessor(settings: () -> AudioPipelineSettings) : FloatStage(settings) {
    /** Per filter, the gain it heads for, as the cookbook's A: 10^(dB / 40). */
    private val target = DoubleArray(FILTERS) { 1.0 }

    /** Per filter, its three smoothing steps; the last is the gain in use. */
    private val smoothed = DoubleArray(FILTERS * STEPS) { 1.0 }

    /** Per filter, tan(pi f / rate); 0 for a band the sample rate cannot carry, which then does nothing. */
    private val warp = DoubleArray(FILTERS)

    /** Per filter: a1, a2, a3 (the filter), m1, m2 (how much band and low pass are added to the input). */
    private val coefficients = DoubleArray(FILTERS * SIZE)

    /** Two state values per filter and channel. */
    internal var state = DoubleArray(0)
        private set
    private var smoothing = 1.0
    private var moving = false
    private var running = false
    private var fresh = true

    override fun start(stream: StreamMetadata) {
        val size = channels * FILTERS * 2
        if (state.size != size) state = DoubleArray(size) else state.fill(0.0)
        val rate = sampleRate.toDouble()
        for (band in 0 until BANDS) {
            val hz = EQ_BANDS_HZ[band]
            warp[band] = if (hz < rate * MAX_CENTRE_SHARE) tan(PI * hz / rate) else 0.0
        }
        warp[SHELF] = tan(PI * SHELF_HZ / rate)
        smoothing = 1.0 - exp(-1.0 / (GLIDE_SECONDS * rate))
        fresh = true
    }

    override fun begin(settings: AudioPipelineSettings) {
        val eq = settings.eq
        for (band in 0 until BANDS) {
            target[band] = if (eq.enabled && warp[band] != 0.0) amplitude(eq.bandsDb[band].coerceIn(-EqSound.BAND_DB, EqSound.BAND_DB)) else 1.0
        }
        target[SHELF] = if (eq.enabled) amplitude(eq.bassBoostDb.coerceIn(0f, EqSound.BASS_BOOST_DB)) else 1.0
        if (fresh) {
            for (filter in 0 until FILTERS) {
                smoothed.fill(target[filter], filter * STEPS, (filter + 1) * STEPS)
                design(filter)
            }
            fresh = false
        }
        moving = false
        var unity = true
        for (filter in 0 until FILTERS) {
            for (step in 0 until STEPS) if (smoothed[filter * STEPS + step] != target[filter]) moving = true
            if (smoothed[filter * STEPS + STEPS - 1] != 1.0) unity = false
        }
        val wasRunning = running
        running = moving || !unity
        // Idle filters forget their past, so starting again later begins from silence.
        if (wasRunning && !running) state.fill(0.0)
    }

    override fun process(frame: FloatArray) {
        if (moving) glide()
        if (!running) return
        for (c in frame.indices) {
            var v0 = frame[c].toDouble()
            var s = c * FILTERS * 2
            var k = 0
            repeat(FILTERS) {
                val ic1 = state[s]
                val ic2 = state[s + 1]
                val v3 = v0 - ic2
                val v1 = coefficients[k] * ic1 + coefficients[k + 1] * v3
                val v2 = ic2 + coefficients[k + 1] * ic1 + coefficients[k + 2] * v3
                val next1 = 2 * v1 - ic1
                val next2 = 2 * v2 - ic2
                // Both at once: zeroing one alone would leave the other to fade far more slowly.
                if (abs(next1) < DENORMAL_FLOOR && abs(next2) < DENORMAL_FLOOR) {
                    state[s] = 0.0
                    state[s + 1] = 0.0
                } else {
                    state[s] = next1
                    state[s + 1] = next2
                }
                v0 += coefficients[k + 3] * v1 + coefficients[k + 4] * v2
                s += 2
                k += SIZE
            }
            frame[c] = v0.toFloat()
        }
    }

    /** Moves every filter that is not at its target one sample closer, through its three smoothing steps. */
    private fun glide() {
        moving = false
        for (filter in 0 until FILTERS) {
            val goal = target[filter]
            val at = filter * STEPS
            if (smoothed[at] == goal && smoothed[at + 1] == goal && smoothed[at + 2] == goal) continue
            smoothed[at] += (goal - smoothed[at]) * smoothing
            smoothed[at + 1] += (smoothed[at] - smoothed[at + 1]) * smoothing
            smoothed[at + 2] += (smoothed[at + 1] - smoothed[at + 2]) * smoothing
            if (abs(smoothed[at] - goal) < SETTLED && abs(smoothed[at + 1] - goal) < SETTLED && abs(smoothed[at + 2] - goal) < SETTLED) {
                smoothed.fill(goal, at, at + STEPS)
            } else {
                moving = true
            }
            design(filter)
        }
    }

    private fun design(filter: Int) {
        val a = smoothed[filter * STEPS + STEPS - 1]
        val g: Double
        val k: Double
        val at = filter * SIZE
        if (filter == SHELF) {
            g = warp[filter] / sqrt(a)
            k = SHELF_K
            coefficients[at + 3] = k * (a - 1)
            coefficients[at + 4] = a * a - 1
        } else {
            g = warp[filter]
            k = 1 / (BAND_Q * a)
            coefficients[at + 3] = k * (a * a - 1)
            coefficients[at + 4] = 0.0
        }
        val a1 = 1 / (1 + g * (g + k))
        coefficients[at] = a1
        coefficients[at + 1] = g * a1
        coefficients[at + 2] = g * g * a1
    }

    private companion object {
        val BANDS: Int = EQ_BANDS_HZ.size

        /** The bass boost shelf runs after the bands. */
        val SHELF: Int = BANDS
        val FILTERS: Int = BANDS + 1
        const val SIZE: Int = 5
        const val STEPS: Int = 3

        /** About one octave wide, so neighbouring bands meet near their -3 dB points. */
        const val BAND_Q: Double = 1.41
        const val SHELF_HZ: Double = 100.0

        /** 1 / Q for Q = 1 / sqrt(2): the cookbook's shelf slope 1, the steepest without a bump. */
        val SHELF_K: Double = sqrt(2.0)

        /** A band applies only well below half the sample rate. */
        const val MAX_CENTRE_SHARE: Double = 0.45
        const val GLIDE_SECONDS: Double = 0.005
        const val SETTLED: Double = 1e-9
        const val DENORMAL_FLOOR: Double = 1e-20

        /** The cookbook's A for a gain in dB: the square root of the linear gain. */
        fun amplitude(db: Float): Double = 10.0.pow(db / 40.0)
    }
}

/**
 * The third stage: mono, then balance. Mono mixes every channel into each; balance turns down the left channels or the
 * right ones, in Android's channel order for 2 to 8 channels (front, then back, then side pairs), never a centre or the
 * low frequencies. A mono song passes through.
 */
internal class BalanceProcessor(settings: () -> AudioPipelineSettings) : FloatStage(settings) {
    /** 0 is the channels as recorded, 1 is mono. */
    private val mix = Glide(0f)
    private val left = Glide()
    private val right = Glide()
    private var mixTarget = 0f
    private var leftTarget = 1f
    private var rightTarget = 1f
    private var sides = IntArray(0)
    private var fresh = true

    override fun start(stream: StreamMetadata) {
        mix.setRate(sampleRate)
        left.setRate(sampleRate)
        right.setRate(sampleRate)
        if (sides.size != channels) sides = sidesOf(channels)
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
        if (frame.size < 2) return
        val share = mix.next(mixTarget)
        if (share != 0f) {
            var sum = 0f
            for (sample in frame) sum += sample
            val mid = sum / frame.size
            for (c in frame.indices) frame[c] += (mid - frame[c]) * share
        }
        val l = left.next(leftTarget)
        val r = right.next(rightTarget)
        for (c in frame.indices) {
            when (sides[c]) {
                LEFT -> frame[c] *= l
                RIGHT -> frame[c] *= r
            }
        }
    }

    private companion object {
        const val LEFT: Int = -1
        const val CENTRE: Int = 0
        const val RIGHT: Int = 1

        /**
         * Which side each of [count] channels is on. Media3 lays 3 to 8 channels out as Android's channel masks
         * (Util.getAudioTrackChannelConfig): front left and right, centre, low frequencies, back left and right, then
         * back centre (7) or side left and right (8); 4 is quad and 5 is quad with a centre.
         */
        fun sidesOf(count: Int): IntArray = when (count) {
            3 -> intArrayOf(LEFT, RIGHT, CENTRE)
            4 -> intArrayOf(LEFT, RIGHT, LEFT, RIGHT)
            5 -> intArrayOf(LEFT, RIGHT, CENTRE, LEFT, RIGHT)
            6 -> intArrayOf(LEFT, RIGHT, CENTRE, CENTRE, LEFT, RIGHT)
            7 -> intArrayOf(LEFT, RIGHT, CENTRE, CENTRE, LEFT, RIGHT, CENTRE)
            8 -> intArrayOf(LEFT, RIGHT, CENTRE, CENTRE, LEFT, RIGHT, LEFT, RIGHT)
            else -> IntArray(count) { if (it == 0) LEFT else if (it == 1) RIGHT else CENTRE }
        }
    }
}

/**
 * The last stage: the pre-amp, then a soft limiter, handing 16-bit PCM on. The limiter watches the loudest channel of
 * each frame: below [KNEE_START] it does nothing; through a [KNEE_DB] soft knee it eases the level towards [LIMIT_DB],
 * which nothing passes. It turns down at once and back up over [RELEASE_SECONDS], so a sine boosted far past full scale
 * keeps its shape instead of being clipped flat.
 *
 * It works only while some stage can push the music past full scale: a levelling gain above 1 (told by [chain]), a band
 * or bass boost, or a pre-amp above 0 dB; and on any frame that did go past it all the same (a cut can overshoot a
 * little). Otherwise it stands aside, so an untagged song with the equalizer off leaves the chain exactly as it came.
 */
internal class LimiterProcessor(settings: () -> AudioPipelineSettings, private val chain: ChainState = ChainState()) :
    FloatStage(settings, int16Out = true) {
    private val preamp = Glide()
    private var preampTarget = 1f
    private var boosting = false
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
        val eq = settings.eq
        preampTarget = if (eq.enabled) dbToGain(eq.preampDb.coerceIn(-EqSound.PREAMP_DB, EqSound.PREAMP_DB)) else 1f
        var eqBoosts = eq.preampDb > 0f || eq.bassBoostDb > 0f
        for (band in eq.bandsDb.indices) if (eq.bandsDb[band] > 0f) eqBoosts = true
        boosting = chain.levellingGain > 1f || (eq.enabled && eqBoosts)
        if (fresh) {
            preamp.value = preampTarget
            fresh = false
        }
    }

    override fun process(frame: FloatArray) {
        val pre = preamp.next(preampTarget)
        if (pre != 1f) for (c in frame.indices) frame[c] *= pre
        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample))
        if (!boosting && gain == 1f && peak <= 1f) return
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
