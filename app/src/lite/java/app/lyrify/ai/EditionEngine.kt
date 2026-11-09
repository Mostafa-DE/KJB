package app.lyrify.ai

import android.content.Context

/**
 * KJB's engine: none. This edition ships no ONNX Runtime and no QNN, so it has no AI lyrics; they come with KJB + AI.
 * The AI screen says so, the overnight run and the model download do not start, and [open] refuses as an NPU that
 * cannot be used would, failing no song.
 */
object EditionEngine : AiEngine {
    override val included: Boolean = false

    override val versions: String = ""

    override fun open(context: Context, models: AiModels, threads: Int, log: (String) -> Unit): AiStages =
        throw NpuUnavailableException(UnsupportedOperationException("KJB has no AI engine; AI lyrics come with KJB + AI"))
}
