@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.lyrify.settings.Levelling
import app.lyrify.settings.Settings
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What the sound chain does right now, for every player at once: Volume levelling and its boost (Settings), and the
 * equalizer profile of the output that plays ([EqSound]). Immutable: a change is a new snapshot, which every stage of
 * every chain picks up at its next buffer, so crossfade's two players can each run their own chain from the one source.
 */
data class AudioPipelineSettings(
    val levelling: Levelling = Levelling.AUTO,
    /** Advanced "Levelling boost", -6 to +6 dB, added to the gain of tagged songs. */
    val levellingBoostDb: Int = 0,
    val eq: EqSound = EqSound(),
) {
    /** Some stage is on: audio offload must stay off. */
    val processing: Boolean get() = levelling != Levelling.OFF || eq.enabled

    companion object {
        fun of(settings: Settings, eq: EqSound): AudioPipelineSettings =
            AudioPipelineSettings(settings.audio.levelling, settings.advanced.levellingBoostDb, eq)
    }
}

/** Audio offload skips every processor, so it is used only when the user turned it on and no stage changes the sound. */
fun offloadAllowed(audioOffload: Boolean, pipeline: AudioPipelineSettings): Boolean = audioOffload && !pipeline.processing

/**
 * A new chain for one player, in the spec's order ("One audio chain" in v3-extras-spec.md): ReplayGain, the equalizer's
 * bands then bass boost, balance and mono, then the pre-amp with a soft limiter. For `DefaultAudioSink.Builder
 * .setAudioProcessors`; processors are never shared between players (a Media3 rule), so each player gets its own chain.
 *
 * [settings] is read once per buffer on the playback thread, so it must be a plain read of a volatile snapshot.
 * [songLevels] is asked once per stream (a new song or a seek), also on the playback thread: a map lookup, no I/O.
 *
 * The stages hand float PCM to each other, so a +12 dB band cannot clip before the limiter, and the last one hands 16-bit
 * PCM on: DefaultAudioSink runs its silence skipping (16-bit only) and Sonic (speed) after this chain, and skips the chain
 * altogether when its float output is on.
 */
fun audioChain(settings: () -> AudioPipelineSettings, songLevels: (StreamMetadata) -> SongLevels): List<AudioProcessor> {
    val chain = ChainState()
    return listOf(
        ReplayGainProcessor(settings, songLevels, chain),
        EqualizerProcessor(settings),
        BalanceProcessor(settings),
        LimiterProcessor(settings, chain),
    )
}

/**
 * What the stages of one chain tell each other: the levelling gain of the moment, so the limiter knows whether levelling
 * can push the music past full scale. Only the playback thread touches it, one stage after another.
 */
internal class ChainState {
    var levellingGain: Float = 1f
}

/**
 * A stage of the chain: takes 16-bit or float PCM, hands every frame to [process] in float, and writes float, or 16-bit
 * when [int16Out] (the last stage). Always active, so a stage switched on mid-song works without the sink reconfiguring;
 * an idle stage passes samples through. Reads the settings once per buffer ([begin]) and allocates only when the format
 * changes.
 */
internal abstract class FloatStage(private val settings: () -> AudioPipelineSettings, private val int16Out: Boolean = false) : BaseAudioProcessor() {
    protected var sampleRate: Int = 0
        private set
    protected var channels: Int = 0
        private set
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, if (int16Out) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT)
    }

    /** A new stream, a seek or a new format: the stage forgets what it played. */
    override fun onFlush(streamMetadata: StreamMetadata) {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        if (frame.size != channels) frame = FloatArray(channels)
        start(streamMetadata)
    }

    override fun onReset() {
        sampleRate = 0
        channels = 0
        frame = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        if (frames == 0) return
        val floatIn = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val output = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
        begin(settings())
        repeat(frames) {
            for (c in 0 until channels) frame[c] = if (floatIn) inputBuffer.getFloat() else inputBuffer.getShort() / FULL_SCALE_16
            process(frame)
            for (c in 0 until channels) if (int16Out) output.putShort(toInt16(frame[c])) else output.putFloat(frame[c])
        }
        output.flip()
    }

    /** After every flush, before the stream's first buffer. */
    protected abstract fun start(stream: StreamMetadata)

    /** Once per buffer, with the settings of the moment. */
    protected abstract fun begin(settings: AudioPipelineSettings)

    /** One frame, one sample per channel, changed in place. */
    protected abstract fun process(frame: FloatArray)

    internal companion object {
        const val FULL_SCALE_16: Float = 32_768f

        /** Rounded and held to the 16-bit range; 16-bit input comes back exactly as it was. */
        fun toInt16(sample: Float): Short = (sample * FULL_SCALE_16).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/** A level change in dB as a linear gain. */
internal fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

/** A gain that glides to its target over a few milliseconds instead of jumping, so no change clicks. */
internal class Glide(var value: Float = 1f) {
    private var step = 1f

    fun setRate(sampleRate: Int) {
        step = 1f - exp(-1f / (GLIDE_SECONDS * sampleRate))
    }

    fun next(target: Float): Float {
        value += (target - value) * step
        if (abs(target - value) < SETTLED) value = target
        return value
    }

    private companion object {
        /** The time constant: about five of them, 50 ms, to settle. */
        const val GLIDE_SECONDS: Float = 0.010f
        const val SETTLED: Float = 1e-5f
    }
}
