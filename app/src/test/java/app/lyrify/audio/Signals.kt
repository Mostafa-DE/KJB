@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Test signals for the sound chain: interleaved float frames, fed through processors as Media3 would. */
internal object Signals {
    const val RATE: Int = 48_000

    /** [seconds] of a sine at [hz] and [amplitude] in every one of [channels]. */
    fun sine(hz: Double, amplitude: Float, seconds: Double = 0.5, channels: Int = 2, rate: Int = RATE): FloatArray {
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * channels) { i -> (amplitude * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }
    }

    /** Interleaved frames from one mono signal per channel, all of the same length. */
    fun interleave(vararg channels: FloatArray): FloatArray = FloatArray(channels[0].size * channels.size) { i -> channels[i % channels.size][i / channels.size] }

    /** Channel [channel] of interleaved [samples]. */
    fun channel(samples: FloatArray, channel: Int, channels: Int = 2): FloatArray = FloatArray(samples.size / channels) { samples[it * channels + channel] }

    fun rms(samples: FloatArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size - from))
    }

    fun peak(samples: FloatArray): Float = samples.maxOf { abs(it) }

    fun db(ratio: Double): Double = 20 * log10(ratio)

    /** The level in dB of channel 0 of [output] against channel 0 of [input], after the first [settle] seconds. */
    fun gainDb(input: FloatArray, output: FloatArray, settle: Double = 0.25, channels: Int = 2, rate: Int = RATE): Double {
        val from = (settle * rate).toInt()
        return db(rms(channel(output, 0, channels), from) / rms(channel(input, 0, channels), from))
    }

    /**
     * [input] through [processors] in [encoding], configured and flushed with [stream] as a sink would, in buffers of
     * [chunk] frames. [beforeChunk] runs before each buffer with the index of its first frame (to change settings
     * mid-stream). The output is read back in float, whatever the last processor hands on.
     */
    fun run(
        processors: List<AudioProcessor>,
        input: FloatArray,
        encoding: Int = C.ENCODING_PCM_FLOAT,
        channels: Int = 2,
        rate: Int = RATE,
        chunk: Int = 1_024,
        stream: StreamMetadata = StreamMetadata.DEFAULT,
        beforeChunk: (Int) -> Unit = {},
    ): FloatArray {
        var format = AudioFormat(rate, channels, encoding)
        for (processor in processors) format = processor.configure(format)
        for (processor in processors) processor.flush(stream)
        val out = ArrayList<Float>(input.size)
        val frames = input.size / channels
        var frame = 0
        while (frame < frames) {
            beforeChunk(frame)
            val count = minOf(chunk, frames - frame)
            var buffer = pcm(input, frame * channels, count * channels, encoding)
            for (processor in processors) {
                processor.queueInput(buffer)
                buffer = processor.getOutput()
            }
            while (buffer.hasRemaining()) out += if (format.encoding == C.ENCODING_PCM_FLOAT) buffer.getFloat() else buffer.getShort() / 32_768f
            frame += count
        }
        return out.toFloatArray()
    }

    /** [samples] as Media3 PCM: native byte order, 16-bit (rounded, held to range) or float. */
    fun pcm(samples: FloatArray, from: Int = 0, count: Int = samples.size, encoding: Int = C.ENCODING_PCM_FLOAT): ByteBuffer {
        val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val buffer = ByteBuffer.allocateDirect(count * bytes).order(ByteOrder.nativeOrder())
        for (i in from until from + count) {
            if (encoding == C.ENCODING_PCM_FLOAT) buffer.putFloat(samples[i]) else buffer.putShort(FloatStage.toInt16(samples[i]))
        }
        buffer.flip()
        return buffer
    }

    /** [samples] as they come back from 16-bit PCM. */
    fun quantized16(samples: FloatArray): FloatArray = FloatArray(samples.size) { FloatStage.toInt16(samples[it]) / 32_768f }
}
