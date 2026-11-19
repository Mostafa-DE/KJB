package app.lyrify.ai

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * The mix as the separation reads it: planar stereo at 44.1 kHz, pulled a piece at a time, so a song is never held whole.
 * [expectedFrames] is what the file claims (0 when it does not say), for progress only.
 */
interface MixSource : AutoCloseable {
    val expectedFrames: Int

    /** Up to [count] frames into [left] and [right] from [offset]; 0 once the audio has ended. */
    fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int

    /**
     * Before the first [read]: the next read starts at [frame] (from the audio's start) without decoding what comes
     * before, where the source can land there exactly; false when it cannot (nothing changed: the reader reads its way
     * there). A source that ends before [frame] then reads as ended.
     */
    fun seek(frame: Long): Boolean = false

    override fun close() {}
}

/**
 * The song's audio is not for the AI: a verdict on the song ([reason], recorded and not tried again by this version),
 * not a failure to retry. The message holds numbers only.
 */
sealed class UnfitAudioException(val reason: RejectReason, message: String) : Exception(message)

/** Longer than the AI takes ([PcmToMix.MAX_AUDIO_SEC]). */
class AudioTooLongException(message: String) : UnfitAudioException(RejectReason.TOO_LONG, message)

/** In a form the mix cannot be made from: no rate or channels, a PCM encoding not converted, a rate not resampled. */
class UnsupportedAudioException(message: String) : UnfitAudioException(RejectReason.UNSUPPORTED_AUDIO, message)

/**
 * The first audio track of a file (mp3, m4a/AAC, flac, ogg, wav...) as a [MixSource], decoded with MediaExtractor and
 * MediaCodec in synchronous mode as it is read: each [read] feeds and drains the codec until it has the frames asked for
 * or the stream ends. The file is opened read only ([File]: here, and closed with the decoder; a [FileDescriptor]: by its
 * owner, as the AI add-on gets KJB's songs, which keeps it open until the decoder is closed); [close] releases the codec.
 *
 * Float output is requested with KEY_PCM_ENCODING = ENCODING_PCM_FLOAT at configure time; per the MediaCodec
 * reference it only applies if the output format confirms it, so 16-bit, 32-bit, 24-bit packed and 8-bit output are
 * converted too ([PcmToMix]). Rate and channel count come from the output format, which can differ from the
 * container's (HE-AAC doubles the rate).
 *
 * Nothing in the file is trusted for sizes or time: the claimed duration is only [expectedFrames], decoding stops past
 * [maxAudioSec] of audio ([AudioTooLongException]; the AI's [PcmToMix.MAX_AUDIO_SEC] unless a reader that stops by itself,
 * the audio cutter, opens the file with a longer one), and [DecodeDeadline] ends a decoder that stops moving or never
 * signals end of stream; the time the reader spends between reads is not the decoder's.
 *
 * [seek] lands on a frame far into the file without decoding what comes before, where the container's seeks and times
 * are exact ([MixSeek]); the AI never seeks.
 */
class MixDecoder private constructor(
    fd: FileDescriptor,
    private val owned: ParcelFileDescriptor?,
    private val maxAudioSec: Long,
) : MixSource {
    constructor(file: File, maxAudioSec: Long = PcmToMix.MAX_AUDIO_SEC) :
        this(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), maxAudioSec)

    constructor(fd: FileDescriptor) : this(fd, null, PcmToMix.MAX_AUDIO_SEC)

    private constructor(pfd: ParcelFileDescriptor, maxAudioSec: Long) : this(pfd.fileDescriptor, pfd, maxAudioSec)

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val durationUs: Long
    private val mime: String?
    private val info = MediaCodec.BufferInfo()
    private val deadline = DecodeDeadline(DecodeDeadline.maxMsFor(maxAudioSec), SystemClock::uptimeMillis)
    private var pcm: PcmToMix? = null
    private var inputDone = false
    private var outputDone = false

    /** A [read] or a [seek] has begun: the next seek is too late. */
    private var started = false

    /** The latest output buffer with audio: its presentation time, and the frames decoded before it (a [seek]'s anchor). */
    private var lastPtsUs = -1L
    private var lastBefore = 0L

    init {
        try {
            extractor.setDataSource(fd)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            mime = format.getString(MediaFormat.KEY_MIME)
            codec = MediaCodec.createDecoderByType(requireNotNull(mime))
            try {
                configure(codec, format)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        } catch (e: Exception) {
            extractor.release()
            owned?.close()
            throw e
        }
    }

    override val expectedFrames: Int get() = PcmToMix.expectedFrames(durationUs, maxAudioSec)

    override fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int {
        started = true
        deadline.resume()
        try {
            while (!outputDone && (pcm?.available ?: 0) < count) step()
            val out = checkNotNull(pcm) { "the decoder produced no audio" }
            return out.read(left, right, offset, count)
        } finally {
            deadline.pause()
        }
    }

    /**
     * Lands on [frame] as [MixSeek] tells, when it is at least [MixSeek.FROM_FRAMES] in, the container's seeks and times
     * are exact ([MixSeek.exact]) and nothing was read yet: the extractor jumps to the sync point at or before
     * [MixSeek.PREROLL_US] ahead of it, and the frames before it are dropped, counted from an anchor buffer. A jump that
     * lands after that point, a decoder whose times reach no anchor within [MixSeek.GIVE_UP_US] of audio, no audio at all
     * after the jump, or an anchor that does not fit, is undone: back at the start, false, and the reader reads its way
     * there.
     */
    override fun seek(frame: Long): Boolean {
        if (started || frame < MixSeek.FROM_FRAMES || !MixSeek.exact(mime)) return false
        started = true
        val fromUs = frame * 1_000_000 / Rates.MIX - MixSeek.PREROLL_US
        extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (extractor.sampleTime !in 0..fromUs) return rewind()
        deadline.resume()
        try {
            while (!outputDone) {
                val out = pcm
                if (out != null && lastPtsUs >= fromUs + MixSeek.ANCHOR_US) break
                if (out != null && out.decoded > MixSeek.GIVE_UP_US * out.sampleRate / 1_000_000) return rewind()
                step()
            }
        } finally {
            deadline.pause()
        }
        // No audio after the jump: undone, the reader reads its way there and finds what is there (a read would find no
        // mix to read). The audio ended before an anchor: its last buffer is one.
        val out = pcm ?: return rewind()
        val drop = MixSeek.toDrop(frame, lastPtsUs, lastBefore, out.sampleRate)
        // An anchor past the target, or times the jump cannot explain.
        if (drop !in 0..MixSeek.GIVE_UP_US * Rates.MIX / 1_000_000) return rewind()
        out.drop(drop)
        return true
    }

    /** A [seek] undone: the file from its start, nothing decoded. */
    private fun rewind(): Boolean {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()
        pcm = null
        inputDone = false
        outputDone = false
        lastPtsUs = -1L
        lastBefore = 0L
        return false
    }

    override fun close() {
        try {
            codec.release()
        } finally {
            extractor.release()
            owned?.close()
        }
    }

    /** One turn of the codec: an input buffer queued if one is free, an output buffer taken if one is ready. */
    private fun step() {
        deadline.verify()
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                deadline.progressed()
                val buffer = requireNotNull(codec.getInputBuffer(inIndex))
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            deadline.progressed()
            reformat(codec.outputFormat)
        } else if (outIndex >= 0) {
            deadline.progressed()
            if (info.size > 0) {
                val out = pcm ?: reformat(codec.getOutputFormat(outIndex))
                lastPtsUs = info.presentationTimeUs
                lastBefore = out.decoded
                val buffer = requireNotNull(codec.getOutputBuffer(outIndex))
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                out.append(buffer.slice().order(ByteOrder.nativeOrder()))
            }
            codec.releaseOutputBuffer(outIndex, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                outputDone = true
                pcm?.finish()
            }
        }
    }

    private fun reformat(format: MediaFormat): PcmToMix {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        pcm?.let { out ->
            check(rate == out.sampleRate && channels == out.channelCount) {
                "the output format changed mid-stream: ${out.sampleRate} Hz x ${out.channelCount} -> $rate Hz x $channels"
            }
            out.encoding = encoding
            return out
        }
        return PcmToMix(rate, channels, encoding, maxAudioSec).also { pcm = it }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        fun configure(codec: MediaCodec, format: MediaFormat) {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            try {
                codec.configure(format, null, null, 0)
            } catch (e: Exception) {
                // A decoder that rejects the float request still decodes to its default 16-bit PCM.
                codec.reset()
                format.removeKey(MediaFormat.KEY_PCM_ENCODING)
                codec.configure(format, null, null, 0)
            }
        }
    }
}

/**
 * Decoder output to the mix: interleaved PCM of any encoding MediaCodec gives ([append]) to planar float stereo at
 * 44.1 kHz ([read]). A mono file is repeated on both channels (the separation spec's "mono files: copy the channel"),
 * only the first two of more channels are kept, and another rate is resampled as it arrives ([Resampler.Stream]); the
 * values are those the whole-file path made. Only what has not been read yet is held. Past [maxAudioSec] of audio
 * ([MAX_AUDIO_SEC] unless told otherwise) [append] throws [AudioTooLongException]; a format it cannot use (no rate or
 * channels, an encoding it does not convert, a rate whose ratio to 44.1 kHz needs more filter phases than
 * [Resampler.MAX_PHASES], such as 44056 Hz) throws [UnsupportedAudioException]: the same file would fail the same way
 * every time.
 */
class PcmToMix(val sampleRate: Int, val channelCount: Int, var encoding: Int, maxAudioSec: Long = MAX_AUDIO_SEC) {
    init {
        if (sampleRate <= 0 || channelCount <= 0) throw UnsupportedAudioException("unusable output format: $sampleRate Hz x $channelCount channels")
    }

    private val maxFrames: Long = maxAudioSec.coerceAtMost(Long.MAX_VALUE / sampleRate) * sampleRate

    /** Frames [append]ed, at [sampleRate]. */
    var decoded = 0L
        private set

    private val streams = if (sampleRate == Rates.MIX) {
        null
    } else {
        val r = try {
            Resampler(sampleRate, Rates.MIX)
        } catch (e: IllegalArgumentException) {
            throw UnsupportedAudioException("$sampleRate Hz audio: ${e.message}")
        }
        arrayOf(r.Stream(), r.Stream())
    }
    private val source = arrayOf(Floats(), Floats())
    private val left = Floats()
    private val right = Floats()

    /** Frames still to [drop] before any is handed out. */
    private var dropping = 0L

    /** Frames ready to [read]. */
    val available: Int get() = left.size

    fun append(bytes: ByteBuffer) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> throw UnsupportedAudioException("unsupported PCM encoding $encoding")
        }
        val count = bytes.remaining() / (bytesPerSample * channelCount)
        if (decoded + count > maxFrames) throw AudioTooLongException("too long: over ${maxFrames / sampleRate / 60} minutes of audio")
        val target = if (streams == null) arrayOf(left, right) else source
        for (i in 0 until count) {
            for (c in 0 until channelCount) {
                val v = when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> bytes.getFloat()
                    AudioFormat.ENCODING_PCM_16BIT -> bytes.getShort() / 32768f
                    AudioFormat.ENCODING_PCM_32BIT -> bytes.getInt() / 2147483648f
                    // Little endian, sign-extended from the top byte.
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> ((bytes.get().toInt() and 0xff) or ((bytes.get().toInt() and 0xff) shl 8) or (bytes.get().toInt() shl 16)) / 8388608f
                    else -> ((bytes.get().toInt() and 0xff) - 128) / 128f
                }
                if (c < 2) target[c].add(v)
                if (channelCount == 1) target[1].add(v)
            }
        }
        decoded += count
        resample(end = false)
        dropReady()
    }

    /** The decoder signalled the end: the resampler's last samples. */
    fun finish() {
        resample(end = true)
        dropReady()
    }

    /**
     * The next [frames] frames are never handed out: those ready now, then those to come. After resampling, so the
     * resampler's filter has the audio before the first frame kept, as when the whole file is read.
     */
    fun drop(frames: Long) {
        dropping += frames
        dropReady()
    }

    fun read(outLeft: FloatArray, outRight: FloatArray, offset: Int, count: Int): Int {
        val n = minOf(count, left.size)
        left.data.copyInto(outLeft, offset, 0, n)
        right.data.copyInto(outRight, offset, 0, n)
        left.drop(n)
        right.drop(n)
        return n
    }

    private fun dropReady() {
        val n = minOf(dropping, left.size.toLong()).toInt()
        if (n == 0) return
        left.drop(n)
        right.drop(n)
        dropping -= n
    }

    private fun resample(end: Boolean) {
        val streams = streams ?: return
        streams[0].push(source[0].data, 0, source[0].size, left)
        streams[1].push(source[1].data, 0, source[1].size, right)
        source[0].clear()
        source[1].clear()
        if (end) {
            streams[0].finish(left)
            streams[1].finish(right)
        }
    }

    companion object {
        /** Audio decoded at most: the library's longest songs are about 15 minutes. */
        const val MAX_AUDIO_SEC: Long = 20 * 60

        /**
         * 44.1 kHz frames of a file that claims [durationUs] (any value a header holds), at most [maxAudioSec] and at most
         * what an Int holds (13.5 hours).
         */
        fun expectedFrames(durationUs: Long, maxAudioSec: Long = MAX_AUDIO_SEC): Int {
            val maxUs = maxAudioSec.coerceAtMost(Int.MAX_VALUE.toLong() / Rates.MIX) * 1_000_000L
            return (durationUs.coerceIn(0L, maxUs) * Rates.MIX / 1_000_000L).toInt()
        }
    }
}

/**
 * Ends a decode that hangs: MediaCodec has no timeout of its own, so a decoder that never signals end of stream would
 * keep [MixDecoder]'s loop going forever. [verify] throws when no input or output buffer moved ([progressed]) for
 * [STALL_MS], or when the decoder has worked for [maxMs] ([MAX_MS] for the AI's 20 minutes, [maxMsFor] another cap). Only the decoder's time counts: between [pause] and
 * [resume] the reader works (the separation), which is neither a stall nor decoding time. [now] is a monotonic clock in
 * milliseconds; on the phone `uptimeMillis`, which stops in deep sleep, so a sleeping phone is not a stall.
 */
class DecodeDeadline(private val maxMs: Long = MAX_MS, private val now: () -> Long) {
    private var since = now()
    private var before = 0L
    private var lastProgress = since

    fun progressed() {
        lastProgress = now()
    }

    /** The reader asks for more: the clock runs again. */
    fun resume() {
        since = now()
        lastProgress = since
    }

    /** The reader has what it asked for. */
    fun pause() {
        before += now() - since
    }

    fun verify() {
        val t = now()
        check(t - lastProgress < STALL_MS) { "the decoder made no progress for ${STALL_MS / 1000} s" }
        check(before + t - since < maxMs) { "decoding took over ${maxMs / 60_000} minutes" }
    }

    companion object {
        const val STALL_MS: Long = 30_000

        /** The spike decoded a 4-minute song in 2.9 s on the phone (lyrify-bench HANDOFF.md, run 1). */
        const val MAX_MS: Long = 10 * 60_000

        /**
         * The decoding time allowed for a decoder that stops at [maxAudioSec] of audio: [MAX_MS] for the AI's
         * [PcmToMix.MAX_AUDIO_SEC], in proportion for another cap, and no total for a reader that stops by itself (the
         * audio cutter, which reads only to the cut's end); the stall check still ends a decoder that hangs.
         */
        fun maxMsFor(maxAudioSec: Long): Long =
            if (maxAudioSec > Long.MAX_VALUE / MAX_MS) Long.MAX_VALUE else MAX_MS * maxAudioSec.coerceAtLeast(0) / PcmToMix.MAX_AUDIO_SEC
    }
}

/**
 * How [MixDecoder.seek] lands exactly on a frame far into a file. The extractor jumps to the sync point at or before
 * [PREROLL_US] ahead of the target, so the decoder (an AAC frame's overlap) and the resampler settle on audio that is
 * then dropped. The frames to drop are counted from an anchor, an output buffer presented at least [ANCHOR_US] after
 * the jump's point, by its time ([toDrop]): whatever a decoder does with its first buffers after a jump (an encoder
 * delay trimmed, a frame rebuilt from nothing) cannot move the target.
 *
 * Only where the extractor's seeks and times are exact ([exact]): AAC (an MPEG-4 file's sample table, an ADTS file's
 * frames) and PCM (WAV, and FLAC where the extractor hands it out decoded). Not MP3: its extractor's seeks are
 * estimates (a Xing table, the bit rate) stamped with the time asked for, so a cut could start seconds off; nor Ogg,
 * whose times after a seek are not checked here. Those are read from their start.
 */
internal object MixSeek {
    /** Below this (10 s), reading from the start costs little: no seek. */
    const val FROM_FRAMES: Long = 10L * Rates.MIX

    const val PREROLL_US: Long = 1_000_000
    const val ANCHOR_US: Long = 500_000

    /** A decoder whose times reach no anchor in this much audio after the jump is not trusted with one. */
    const val GIVE_UP_US: Long = PREROLL_US + 2_000_000

    private val EXACT: Set<String> = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_RAW)

    /** Whether a track of [mime] seeks to exact times. */
    fun exact(mime: String?): Boolean = mime in EXACT

    /**
     * The mix frames to drop for the first one handed out to be [target] (44.1 kHz frames from the file's start), when
     * [before] frames at [rate] were decoded ahead of an anchor buffer presented at [ptsUs]. Negative when the anchor is
     * past the target.
     */
    fun toDrop(target: Long, ptsUs: Long, before: Long, rate: Int): Long =
        (target + before.toDouble() * Rates.MIX / rate - ptsUs.toDouble() * Rates.MIX / 1_000_000).roundToLong()
}
