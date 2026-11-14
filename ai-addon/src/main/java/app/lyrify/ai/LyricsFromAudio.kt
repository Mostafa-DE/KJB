package app.lyrify.ai

import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.lyrics.LrcProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileDescriptor
import java.util.Locale

/** [model] is there but ONNX Runtime could not load it ([cause]): a damaged file, not the expected model, or the NPU refused it. */
class ModelLoadException(val model: File, cause: Throwable) :
    Exception("${model.name} could not be loaded: ${cause.javaClass.simpleName}: ${cause.message}", cause)

/** The NPU could not be used at all: the QNN execution provider did not register or offers no NPU device. */
class NpuUnavailableException(cause: Throwable) : Exception("the NPU is not available: ${cause.javaClass.simpleName}: ${cause.message}", cause)

/**
 * One song as the add-on gets it from KJB: its [audio], open for reading (KJB's descriptor; the add-on never learns the
 * path), and its length as MediaStore has it.
 */
data class AiSong(val audio: FileDescriptor, val durationMs: Long)

/** Called after each piece of a stage (an MDX segment, an aligner window); throws to stop it. */
fun interface Progress {
    fun step(done: Int, total: Int)
}

/**
 * The heavy steps of [LyricsFromAudio]: on the phone [NpuStages], in tests fakes. Their models stay loaded from first
 * use until the caller closes them (a night's run); a model that does not load throws [ModelLoadException]. All block.
 */
interface AiStages {
    /** The first audio track of [audio] as a mix that decodes while it is read; the caller closes it ([audio] stays open). */
    fun decode(audio: FileDescriptor): MixSource

    /** Separates the vocals of [mix], read to its end, giving them to [vocals] in order (planar stereo at 44.1 kHz). */
    fun separateVocals(mix: MixSource, vocals: VocalSink, progress: Progress)

    /** Whisper on [chunk] of [stem] (16 kHz mono): its segments, in seconds of the song. */
    fun transcribe(stem: FloatArray, chunk: VocalChunk): List<Segment>

    /** wav2vec2 CTC emissions of the whole [stem] (16 kHz mono), as [Ctc.wholeSong] makes them. */
    fun emissions(stem: FloatArray, progress: Progress): Emissions
}

/** The gate's frames and the 16 kHz mono vocal stem: what a run keeps of the separation ([VocalStem]). */
class Vocals(val frames: GateFrames, val stem: FloatArray)

/**
 * The Java heap one song takes at most, by its length, and whether that fits the app's heap. The 44.1 kHz mix and vocals
 * are never held whole (they stream through the separation), so what grows with the song is the 16 kHz stem (4 bytes a
 * sample; 2 while it is made), wav2vec2's emissions (32 doubles every 20 ms, twice while they are joined) and, timing
 * known lyrics, the aligner's back-pointer table, at most [Ctc.MAX_TABLE_CELLS] bytes. The phases do not overlap:
 * - separating: the stem at 16 bits so far, and the separation's own arrays ([SEPARATION_BYTES]);
 * - finishing the stem: its 16-bit and float copies;
 * - timing: the stem, the emissions and the table (or the emissions twice while they are joined).
 * Whisper's side holds the stem and a few MB.
 */
object AiMemory {
    /**
     * The separation's own arrays for Voc_FT, 4 STFT threads: the spectrum in and out and ONNX Runtime's copy of the
     * output (3 x 12.6 MB), seven chunk-long float arrays (7.3 MB), the STFT's threads and overlap-add (about 15 MB) and
     * the decoder's unread frames (about 4 MB): 67 MB, rounded up.
     */
    const val SEPARATION_BYTES: Long = 72L shl 20

    /** The rest of the app's heap while a song runs (the ledger, WorkManager, coroutines), allowed for. */
    const val APP_BYTES: Long = 32L shl 20

    /** The share of the heap a run may plan to fill: the garbage collector needs room to work. */
    const val HEAP_SHARE: Double = 0.8

    fun peakBytes(durationSec: Double): Long {
        val stem = (durationSec * Rates.SPEECH).toLong() * Float.SIZE_BYTES
        val emissions = Math.round(durationSec / Ctc.FRAME_SEC) * Ctc.CLASSES * Double.SIZE_BYTES
        val separating = stem / 2 + SEPARATION_BYTES
        val finishing = stem / 2 + stem
        val timing = maxOf(stem + 2 * emissions, stem + emissions + Ctc.MAX_TABLE_CELLS)
        return maxOf(separating, finishing, timing)
    }

    /** A song of [durationSec] runs within a heap of [maxHeapBytes] (`Runtime.maxMemory()`) and the decoder's limit. */
    fun fits(durationSec: Double, maxHeapBytes: Long): Boolean =
        durationSec <= PcmToMix.MAX_AUDIO_SEC && peakBytes(durationSec) + APP_BYTES <= maxHeapBytes * HEAP_SHARE
}

/**
 * The AI lyrics engine for one song, as measured on the phone (lyrify-npu-spike, full stage) and on the bench: decode,
 * MDX vocals on the NPU, the stem at 16 kHz as the bench makes it, Whisper on the NPU on the vocal chunks (the V2 frame
 * rule makes them), the word-rate gate on what it heard ([VocalGate]: an instrumental stops there), then
 * - with no known lyrics, transcribe: the segments [SegmentFilters] keeps become the LRC ([Transcription]);
 * - with known plain lyrics, align: [PlainLyricsTiming] times the lines and says how confident it is.
 *
 * Writes nothing and goes nowhere: audio is read, the result is returned. Call it from a background dispatcher: the
 * stages block. Cancelling the coroutine stops the run at the next MDX segment, Whisper chunk or aligner window and
 * throws [CancellationException].
 */
class LyricsFromAudio(
    private val stages: AiStages,
    /** Monotonic milliseconds, for the stage timings. */
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    /** One-line notes for the AI's run log: stages, times, counts. Never lyrics, nor error messages but the numbers of unfit audio. */
    private val notes: (String) -> Unit = {},
    /** The timing method's numbers ([PlainLyricsTiming.PARAMS]) and the transcribe job's offset. */
    private val timing: TimingParams = PlainLyricsTiming.PARAMS,
    /** The heap the app may use (`Runtime.maxMemory()`): a song that would not fit is not started ([AiMemory]). */
    private val maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
    /** VERBOSE (`setprop log.tag.KJB VERBOSE`): [log] also gets a failure's whole stack trace (its messages can hold paths). */
    private val verbose: Boolean = false,
) {
    /**
     * [knownPlainLyrics]: LRCLIB's plain lyrics of this recording (duration matched), or null to transcribe. Lyrics with
     * no line to time (see [LyricsText.lines]) are transcribed too. [onStage] hears each stage the run enters (the crash
     * guard keeps it); [onProgress] each piece of a stage done (an MDX segment, a wav2vec2 window, an aligner block);
     * [beforeChunk] is called before each Whisper chunk (the overnight run waits there while the phone is warm).
     */
    suspend fun run(
        track: AiSong,
        knownPlainLyrics: String?,
        onStage: (AiStage) -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        beforeChunk: suspend () -> Unit = {},
    ): AiResult {
        val lyrics = knownPlainLyrics?.let(LyricsText::lines).orEmpty()
        val job = currentCoroutineContext()[Job]
        val seconds = track.durationMs / 1000.0
        if (!AiMemory.fits(seconds, maxHeapBytes)) {
            note("too long for the AI: %.0f s, about %d MB of a %d MB heap".format(Locale.ROOT, seconds, AiMemory.peakBytes(seconds) shr 20, maxHeapBytes shr 20))
            return AiResult.Rejected(RejectReason.TOO_LONG, agreement = null)
        }
        return try {
            Run(track, lyrics, job, onStage, onProgress, beforeChunk).result()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnfitAudioException) {
            // Its message holds numbers only (UnfitAudioException); still, logcat gets the reason alone.
            log("not for the AI (${e.reason})")
            notes("not for the AI (${e.reason}): ${e.message}")
            AiResult.Rejected(e.reason, agreement = null)
        } catch (e: ModelLoadException) {
            log("${e.model.name}: model did not load: ${e.javaClass.simpleName}")
            trace(e)
            failed(e).copy(model = e.model.name)
        } catch (e: NpuUnavailableException) {
            log("the NPU is not available: ${e.javaClass.simpleName}")
            trace(e)
            failed(e).copy(npu = true)
        } catch (e: Exception) {
            log("AI failed: ${e.javaClass.simpleName}")
            trace(e)
            failed(e)
        } catch (e: OutOfMemoryError) {
            log("AI ran out of memory")
            failed(e)
        }
    }

    /** Under VERBOSE ([verbose]), [e]'s whole stack trace after the line that names its class. The run log gets its message ([failed]). */
    private fun trace(e: Throwable) {
        if (verbose) log(e.stackTraceToString())
    }

    /**
     * [e] as a short [AiResult.Failed.message]: its class and message with each absolute path cut to its file name,
     * anything from a "/" to a space, quote or colon (a model's or a native library's path, say). The song comes as a
     * descriptor, so no message names its path.
     */
    private fun failed(e: Throwable): AiResult.Failed {
        val message = e.message?.let { text -> ABSOLUTE_PATH.replace(text) { it.groupValues[1] } }
        return AiResult.Failed(listOfNotNull(e.javaClass.simpleName, message).joinToString(": "))
    }

    /** A line for logcat and the run log alike: nothing in it may come from the lyrics or an error message. */
    private fun note(line: String) {
        log(line)
        notes(line)
    }

    /** One run: its stage timings are its only state. */
    private inner class Run(
        private val track: AiSong,
        private val lyrics: List<String>,
        private val job: Job?,
        private val onStage: (AiStage) -> Unit,
        private val onProgress: (done: Int, total: Int) -> Unit,
        private val beforeChunk: suspend () -> Unit,
    ) {
        private val stageMs = linkedMapOf<AiStage, Long>()

        private val progress = Progress { done, total ->
            job?.ensureActive()
            onProgress(done, total)
        }

        suspend fun result(): AiResult {
            val vocals = listen()
            val gate = vocals.frames.gate()
            note("vocal frames %.3f (%d of %d)".format(Locale.ROOT, gate.share, gate.activeFrames, gate.validFrames))
            val chunks = VocalChunks.of(vocals.frames, vocals.stem)
            // No vocal frame at all: nothing to transcribe.
            if (chunks.isEmpty()) return AiResult.Instrumental(0.0)
            val segments = stage(AiStage.TRANSCRIBE) { transcribe(vocals.stem, chunks) }
            val durationSec = vocals.stem.size.toDouble() / Rates.SPEECH
            val wordsPerMinute = VocalGate.wordsPerMinute(segments, vocals.frames, durationSec)
            note("%.1f words per minute".format(Locale.ROOT, wordsPerMinute))
            if (!VocalGate.isVocal(wordsPerMinute)) return AiResult.Instrumental(wordsPerMinute)
            val stats = { lines: Int ->
                AiStats(
                    audioSec = durationSec,
                    vocalShare = gate.share,
                    wordsPerMinute = wordsPerMinute,
                    chunks = chunks.size,
                    transcribedSec = chunks.sumOf { it.durationSec },
                    segments = segments.size,
                    lines = lines,
                    stageMs = stageMs.toMap(),
                )
            }
            return if (lyrics.isEmpty()) transcribed(segments, vocals.frames, stats) else aligned(segments, vocals.stem, stats)
        }

        /** Whisper on each chunk, after [beforeChunk]; a stop is noticed between chunks. */
        private suspend fun transcribe(stem: FloatArray, chunks: List<VocalChunk>): List<Segment> {
            val segments = ArrayList<Segment>()
            for ((k, chunk) in chunks.withIndex()) {
                beforeChunk()
                job?.ensureActive()
                val start = clock()
                val heard = stages.transcribe(stem, chunk)
                note("chunk ${k + 1} of ${chunks.size} (%.0f s): ${clock() - start} ms, ${heard.size} segments".format(Locale.ROOT, chunk.durationSec))
                segments += heard
                job?.ensureActive()
            }
            return segments
        }

        /**
         * Decode, separate, gate: the mix is decoded as the separation reads it, and the gate's frames and the 16 kHz stem
         * are made as the vocals come out ([VocalStem]), so no 44.1 kHz array of the whole song is ever held; what is
         * left for Whisper and wav2vec2 is the 16 kHz stem.
         */
        private fun listen(): Vocals {
            val vocals = VocalStem()
            stage(AiStage.DECODE) { stages.decode(track.audio) }.use { mix ->
                stage(AiStage.SEPARATE) { stages.separateVocals(vocals.listening(mix), vocals, progress) }
            }
            return stage(AiStage.GATE) { vocals.build() }
        }

        private fun transcribed(segments: List<Segment>, frames: GateFrames, stats: (Int) -> AiStats): AiResult {
            val lines = Transcription.lines(segments, frames, timing.transcribeOffsetSec)
            note("${segments.size} segments, ${lines.size} kept")
            return lrc(lines, agreement = null) { AiResult.Transcribed(it, stats(lines.size)) }
        }

        private fun aligned(segments: List<Segment>, stem: FloatArray, stats: (Int) -> AiStats): AiResult {
            val timed = stage(AiStage.ALIGN) {
                PlainLyricsTiming.time(segments, stem.size.toDouble() / Rates.SPEECH, lyrics, timing, progress = progress) {
                    stages.emissions(stem, progress).also { job?.ensureActive() }
                }
            }
            // Counts only: the alignment's size and the number of lines.
            val size = timed.trellis?.let { ", ${it.cells} cells (${it.frames} frames x ${it.states} states)" }.orEmpty()
            note("timing (${timing.source}): confidence %.2f, ${timed.rejected ?: "accepted"}$size, ${lyrics.size} lines".format(Locale.ROOT, timed.confidence))
            timed.rejected?.let { reason ->
                val measured = reason !in setOf(RejectReason.NO_WORDS, RejectReason.NO_ALIGNMENT, RejectReason.TOO_LONG, RejectReason.UNSUPPORTED_AUDIO)
                return AiResult.Rejected(reason, timed.confidence.takeIf { measured })
            }
            // Sorted by time, as the bench's write_lrc does: Samsung looks lines up by binary search.
            val lines = lyrics.zip(timed.times) { text, t -> LrcLine(lrcMillis(t), text) }.sortedBy { it.timeMs }
            return lrc(lines, timed.confidence) { AiResult.Aligned(it, timed.confidence, stats(lines.size)) }
        }

        private fun lrc(lines: List<LrcLine>, agreement: Double?, accepted: (String) -> AiResult): AiResult {
            val text = LrcFormat.render(lines)
            val problem = LrcFormat.validate(text, track.durationMs) ?: return accepted(text)
            return AiResult.Rejected(RejectReason.INVALID_LRC, agreement, problem)
        }

        /** [body] as [stage]: timed, and told to [onStage] first. */
        private inline fun <T> stage(stage: AiStage, body: () -> T): T {
            job?.ensureActive()
            onStage(stage)
            val start = clock()
            val value = body()
            val ms = clock() - start
            stageMs[stage] = ms
            note("${stage.name.lowercase()}: $ms ms")
            return value
        }
    }

    companion object {
        /** A "/" not inside a word or a relative path, then folders, then the file name (group 1). */
        private val ABSOLUTE_PATH = Regex("""(?<![\w.~/-])/(?:[^/\s:'"]+/)+([^/\s:'"]*)""")
    }
}
