package app.lyrify.addon

import android.content.Context
import app.lyrify.ai.AiModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    /** Where the handover stands, for the AI page. */
    fun state(context: Context): Flow<HandoverState> = flow { emit(if (pending(context)) HandoverState.Waiting else HandoverState.None) }.flowOn(Dispatchers.IO)

    private const val LEGACY_FOLDER = "models"
}

/** The models KJB 2.0 kept, on their way to the add-on. */
sealed interface HandoverState {
    /** Nothing to hand over: none kept, or handed over already. */
    data object None : HandoverState

    /** KJB holds models for the add-on, which is not there yet. */
    data object Waiting : HandoverState

    data class Moving(val doneBytes: Long, val totalBytes: Long) : HandoverState
}
