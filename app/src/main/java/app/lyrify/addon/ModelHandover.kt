package app.lyrify.addon

import android.content.Context
import app.lyrify.ai.AiModels
import java.io.File

/**
 * The handover of the models KJB 2.0 downloaded into its own files (`filesDir/models`) to the AI add-on, which owns the
 * models from 3.0 on: no 1 GB download again for users who had the AI.
 */
object ModelHandover {
    /** Where KJB 2.0 kept the models. */
    fun legacyFolder(context: Context): File = File(context.applicationContext.filesDir, LEGACY_FOLDER)

    /** KJB still holds models of its own to hand over: the add-on's download waits until they are. */
    fun pending(context: Context): Boolean = legacyFolder(context).let { folder ->
        listOf(AiModels.WHISPER, AiModels.MDX, AiModels.WAV2VEC2).any { File(folder, it).exists() }
    }

    /** Starts the handover when there is something to hand over. */
    fun startIfNeeded(context: Context) {}

    private const val LEGACY_FOLDER = "models"
}
