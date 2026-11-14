package app.lyrify.ai

import app.lyrify.lyrics.LrcProblem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * What the AI made of one song. The engine writes nothing; the caller records it and writes the LRC. The AI add-on sends
 * it to KJB as JSON ([app.lyrify.ai.api.Wire]): the serial names are that format, so they never change.
 */
@Serializable
sealed interface AiResult {
    /** No lyrics were known: Whisper's segments, one LRC line each. [lrc] passed [LrcFormat.validate]. */
    @Serializable
    @SerialName("transcribed")
    data class Transcribed(val lrc: String, val stats: AiStats) : AiResult

    /** The known plain lyrics, timed; [agreement] is the timing's confidence ([TimedLines.confidence]). [lrc] passed validation. */
    @Serializable
    @SerialName("aligned")
    data class Aligned(val lrc: String, val agreement: Double, val stats: AiStats) : AiResult

    /**
     * Not sung ([VocalGate]): no vocal frame at all, or Whisper heard [wordsPerMinute] words per minute of song, at most
     * [VocalGate.VOCAL_ABOVE_WORDS_PER_MINUTE].
     */
    @Serializable
    @SerialName("instrumental")
    data class Instrumental(val wordsPerMinute: Double) : AiResult

    /** Lyrics were made but are not good enough to write; [agreement] for the align job, [problem] for an LRC Samsung would not show. */
    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: RejectReason, val agreement: Double?, val problem: LrcProblem? = null) : AiResult

    /**
     * The engine could not run: an unreadable file, a native error, no memory, a model that did not load. [message] is
     * short: the exception's class and message, every absolute path cut to its file name. It can name the song's file, so
     * it goes to the AI's run log, never to logcat (which has the exception's class only).
     * [model] is the model that did not load ([AiModels.WHISPER], [AiModels.MDX] or [AiModels.WAV2VEC2]), when that was the
     * failure; [npu] is set when the phone's NPU could not be used (no QNN execution provider or NPU device, or no answer
     * from it in time: another app holding it, NpuBusyException). Neither is the song's fault: KJB tries again later.
     */
    @Serializable
    @SerialName("failed")
    data class Failed(val message: String, val model: String? = null, val npu: Boolean = false) : AiResult {
        /** The exception's class alone: all logcat may say of the failure. */
        val kind: String get() = message.substringBefore(':')
    }
}

enum class RejectReason {
    /** Align: the timing's confidence is under its threshold ([TimedLines.confidence]). */
    LOW_AGREEMENT,

    /** Align: too few line times step forward. */
    NOT_MONOTONIC,

    /** Align: Whisper heard nothing to place the lyrics by. */
    NO_WORDS,

    /** Align: the aligner found no path for the lyrics (no letter in them, or a song too short for them). */
    NO_ALIGNMENT,

    /**
     * The song is too long for the AI: longer than the decoder takes ([PcmToMix.MAX_AUDIO_SEC]), than the app's heap
     * allows ([AiMemory]), or, timing known lyrics, its alignment past the aligner's CPU cap ([Ctc.Budget.maxCells]).
     */
    TOO_LONG,

    /** The song's audio is in a form the AI cannot use, such as a sample rate it does not resample ([UnsupportedAudioException]). */
    UNSUPPORTED_AUDIO,

    /** The LRC would not show properly in Samsung Music, for example fewer than 3 lines ([AiResult.Rejected.problem]). */
    INVALID_LRC,
}

/** The steps of one run, for timings and the crash guard. Stored by name in the crash guard's file. */
enum class AiStage {
    /** Open the file and start its decoder; the decoding itself runs as [SEPARATE] reads the mix. */
    DECODE,

    /** Decode the mix to 44.1 kHz stereo as it is read, MDX vocals on the NPU, the gate's frames and the 16 kHz stem made on the way. */
    SEPARATE,

    /** The stem's last samples and the gate's frames. */
    GATE,

    /** Whisper on the NPU, on the vocal chunks. */
    TRANSCRIBE,

    /** Timing known lyrics: wav2vec2 emissions on the CPU and the timing method (align job only). */
    ALIGN,
}

/** How a run went, for logs and the app's progress. */
@Serializable
data class AiStats(
    val audioSec: Double,
    /** The share of 1 s frames the V2 rule calls vocal (it builds the chunks; it does not decide). */
    val vocalShare: Double,
    /** What decides: words Whisper heard per minute of song ([VocalGate.wordsPerMinute]). */
    val wordsPerMinute: Double,
    val chunks: Int,
    /** Seconds of audio Whisper decoded: the chunks only. */
    val transcribedSec: Double,
    val segments: Int,
    val lines: Int,
    val stageMs: Map<AiStage, Long>,
)

/**
 * The model files the AI add-on downloads (the engine only reads them): AI Hub's Whisper for this phone's chip (w8a16, or
 * float), unpacked into the folder [whisper]; the MDX vocal model; wav2vec2 for timing known lyrics. Their names are the
 * same in the folder KJB 2.0 kept them in, which KJB hands over to the add-on (app.lyrify.addon.ModelHandover).
 */
data class AiModels(val whisper: File, val mdx: File, val wav2vec2: File) {
    val encoder: File get() = File(whisper, ENCODER)
    val decoder: File get() = File(whisper, DECODER)

    /** AI Hub's metadata.json of the Whisper export: its I/O contract ([WhisperContract.fromMetadata]). */
    val metadata: File get() = File(whisper, METADATA)

    companion object {
        /** The folder AI Hub's Whisper is unpacked into, whichever export the chip gets. */
        const val WHISPER: String = "whisper-npu"
        const val ENCODER: String = "encoder.onnx"
        const val DECODER: String = "decoder.onnx"
        const val METADATA: String = "metadata.json"

        /** What the Whisper folder holds: the two EPContext models, their QNN context binaries and AI Hub's metadata. */
        val WHISPER_FILES: List<String> = listOf(ENCODER, "encoder.bin", DECODER, "decoder.bin", METADATA)

        const val MDX: String = "UVR-MDX-NET-Voc_FT.onnx"

        /** Xenova's q4 export (see [CtcModel]); its own name, so the fp32 file of earlier builds is retired by name. */
        const val WAV2VEC2: String = "wav2vec2-base-960h_q4.onnx"

        fun inFolder(folder: File): AiModels = AiModels(File(folder, WHISPER), File(folder, MDX), File(folder, WAV2VEC2))
    }
}
