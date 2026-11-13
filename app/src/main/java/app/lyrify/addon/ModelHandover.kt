package app.lyrify.addon

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lyrify.LOG_TAG
import app.lyrify.ai.AiModels
import app.lyrify.ai.api.ImportReply
import app.lyrify.models.ModelState
import app.lyrify.models.ModelStore
import app.lyrify.overnight.AiLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The handover of the models KJB 2.0 downloaded into its own files (`filesDir/models`) to the AI add-on, which owns the
 * models from 3.0 on: no 1 GB download again for users who had the AI. It runs as a worker ([HandoverWorker]) once the
 * add-on is there, and goes on after a kill (the add-on keeps what it took; KJB deletes nothing before the add-on took
 * every file, [Handover]). Until then the add-on's own download waits ([pending]); a file the add-on will not take ends
 * it, and the add-on downloads instead. KJB's copies then go once the add-on has its models.
 */
object ModelHandover {
    private const val WORK = "ai-handover"
    private const val PREFERENCES = "ai_handover"
    private const val OUTCOME = "outcome"
    private const val DONE = "done"
    private const val GAVE_UP = "gave_up"
    internal const val DONE_BYTES = "done_bytes"
    internal const val TOTAL_BYTES = "total_bytes"
    private const val RETRY_MINUTES = 1L
    private const val LEGACY_FOLDER = "models"

    /** Bumped when KJB's copies or the outcome changed without the work changing. */
    private val changed = MutableStateFlow(0)

    /** Where KJB 2.0 kept the models. */
    fun legacyFolder(context: Context): File = File(context.applicationContext.filesDir, LEGACY_FOLDER)

    /** Bytes of KJB 2.0's models still in its folder (parts included). Blocking. */
    private fun legacyBytes(context: Context): Long {
        val folder = legacyFolder(context)
        return listOf(AiModels.WHISPER, AiModels.MDX, AiModels.WAV2VEC2).map { File(folder, it) }
            .sumOf { file -> if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else file.length() }
    }

    /** KJB still holds models of its own the add-on has not taken, and has not given up on: the add-on's download waits. Blocking. */
    fun pending(context: Context): Boolean = outcome(context) == null && legacyBytes(context) > 0

    /**
     * At the start and after the add-on's install: a handover that ended is tidied up (KJB's copies go after a done one
     * whose delete was cut short, or after one given up once the add-on has its own models), and one still to do starts
     * once the add-on is installed and KJB's own.
     */
    suspend fun startIfNeeded(context: Context) {
        val app = context.applicationContext
        val outcome = outcome(app)
        withContext(Dispatchers.IO) {
            if (outcome == DONE && legacyFolder(app).exists()) deleteLegacy(app)
        }
        if (outcome == GAVE_UP && withContext(Dispatchers.IO) { legacyBytes(app) } > 0) {
            if (Addon.withPort(app) { it.status()?.models is ModelState.Ready } == true) withContext(Dispatchers.IO) { deleteLegacy(app) }
        }
        if (!withContext(Dispatchers.IO) { pending(app) } || Addon.client(app).peek() != null) return
        val request = OneTimeWorkRequestBuilder<HandoverWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_MINUTES, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Where the handover stands, for the AI page: moving (with its bytes), waiting for the add-on, or nothing to do. */
    fun state(context: Context): Flow<HandoverState> {
        val app = context.applicationContext
        return combine(WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(WORK), changed) { infos, _ ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val total = running?.progress?.getLong(TOTAL_BYTES, 0) ?: 0
            when {
                running != null && total > 0 -> HandoverState.Moving(running.progress.getLong(DONE_BYTES, 0), total)
                pending(app) -> HandoverState.Waiting(legacyBytes(app))
                else -> HandoverState.None
            }
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    /** "Delete download", or the add-on has its own models: KJB 2.0's copies go. True when none is left. Blocking. */
    fun deleteLegacy(context: Context): Boolean {
        val folder = legacyFolder(context)
        val gone = !folder.exists() || folder.deleteRecursively()
        changed.update { it + 1 }
        if (!gone) Log.w(LOG_TAG, "AI: KJB's own copies of the models could not all be deleted")
        return gone
    }

    /** The add-on took every file: written at once (commit), before KJB's copies are deleted. */
    internal fun markDone(context: Context) = mark(context, DONE)

    /** A file the add-on would not take: KJB's copies stay until the add-on has its own, which it downloads. */
    internal fun markGaveUp(context: Context) = mark(context, GAVE_UP)

    private fun mark(context: Context, outcome: String) {
        preferences(context).edit(commit = true) { putString(OUTCOME, outcome) }
        changed.update { it + 1 }
    }

    private fun outcome(context: Context): String? = preferences(context).getString(OUTCOME, null)

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

/**
 * The handover ([Handover]) with the add-on bound for it. Its download is stopped first (it keeps its parts); after the
 * handover, with the AI on, the add-on downloads what KJB did not have. Logs carry outcomes and counts only.
 */
class HandoverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!withContext(Dispatchers.IO) { ModelHandover.pending(app) }) return Result.success()
        val session = (Addon.client(app).connect() as? Connection.Open)?.session ?: return Result.retry()
        return try {
            session.use { hand(it.port) }
        } catch (_: AddonDiedException) {
            Log.w(LOG_TAG, "AI: the add-on stopped during the handover; trying again later")
            Result.retry()
        }
    }

    private suspend fun hand(addon: AddonPort): Result {
        val app = applicationContext
        val handover = Handover(
            ModelHandover.legacyFolder(app),
            send = { file, sha -> addon.importModel(file.name, file.file, sha) },
            sha256 = ::sha256,
            progress = { done, total -> setProgress(workDataOf(ModelHandover.DONE_BYTES to done, ModelHandover.TOTAL_BYTES to total)) },
            markDone = { ModelHandover.markDone(app) },
        )
        val end = withContext(Dispatchers.IO) { handover.run() }
        return when (end) {
            HandoverEnd.Done -> {
                Log.i(LOG_TAG, "AI: the models KJB kept are the add-on's now")
                if (AiLyrics.enabled(app).value) AddonDownloads.startIfIdle(app)
                Result.success()
            }
            is HandoverEnd.Retry -> {
                Log.i(LOG_TAG, "AI: handover not finished (${end.reason}); trying again later")
                Result.retry()
            }
            is HandoverEnd.GaveUp -> {
                Log.w(LOG_TAG, "AI: the add-on did not take a model KJB kept (${end.reason}); it downloads the models instead")
                ModelHandover.markGaveUp(app)
                if (AiLyrics.enabled(app).value) AddonDownloads.start(app)
                Result.success()
            }
        }
    }

    /** The SHA-256 of [file] as it is on the phone now, as hex. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        const val BUFFER_BYTES = 1024 * 1024
    }
}

/** The models KJB 2.0 kept, on their way to the add-on. */
sealed interface HandoverState {
    /** Nothing to hand over: none kept, or handed over already. */
    data object None : HandoverState

    /** KJB holds [bytes] of models for the add-on, which is not there yet. */
    data class Waiting(val bytes: Long) : HandoverState

    data class Moving(val doneBytes: Long, val totalBytes: Long) : HandoverState
}

/** One model file KJB 2.0 kept: its [name] in the models folder ("UVR-MDX-NET-Voc_FT.onnx", "whisper-npu/encoder.bin"). */
data class LegacyFile(val name: String, val file: File)

/** How a handover ended. */
sealed interface HandoverEnd {
    /** The add-on took every file; KJB's copies are deleted. */
    data object Done : HandoverEnd

    /** Not this time (the add-on busy, gone, or short of room): nothing deleted, tried again later. */
    data class Retry(val reason: String) : HandoverEnd

    /** [name] is not as it should be: nothing deleted, and the add-on downloads the models instead. */
    data class GaveUp(val name: String, val reason: String) : HandoverEnd
}

/**
 * The handover of KJB 2.0's models in [folder] to the add-on: each file goes over ([send]) with its SHA-256 ([sha256],
 * taken here from the file itself), the single models first, Whisper's files, then its manifest last (which puts the
 * add-on's folder in place). A Whisper folder KJB never finished unpacking (no manifest) is not sent. KJB's copies are
 * deleted only once the add-on took every file ([markDone] first, so a kill during the delete is finished at the next
 * start); before that nothing is deleted, whatever happens: a retry, a kill, the add-on dying, a file it will not take.
 * Sending again is cheap: the add-on does not read again a file it already holds as given.
 */
class Handover(
    private val folder: File,
    private val send: (LegacyFile, String) -> ImportReply,
    private val sha256: (File) -> String,
    private val progress: suspend (Long, Long) -> Unit = { _, _ -> },
    private val markDone: () -> Unit,
) {
    /** KJB 2.0's model files to hand over, in the order they go. */
    fun files(): List<LegacyFile> {
        val singles = listOf(AiModels.MDX, AiModels.WAV2VEC2).map { LegacyFile(it, File(folder, it)) }.filter { it.file.isFile }
        val whisper = File(folder, AiModels.WHISPER)
        val manifest = File(whisper, ModelStore.MANIFEST)
        val complete = manifest.isFile && AiModels.WHISPER_FILES.all { File(whisper, it).isFile }
        val unpacked = if (!complete) {
            emptyList()
        } else {
            AiModels.WHISPER_FILES.map { LegacyFile("${AiModels.WHISPER}/$it", File(whisper, it)) } +
                LegacyFile("${AiModels.WHISPER}/${ModelStore.MANIFEST}", manifest)
        }
        return singles + unpacked
    }

    suspend fun run(): HandoverEnd {
        val files = files()
        val total = files.sumOf { it.file.length() }
        var done = 0L
        progress(0, total)
        for (file in files) {
            val reply = try {
                send(file, sha256(file.file))
            } catch (_: AddonDiedException) {
                return HandoverEnd.Retry("the add-on stopped")
            } catch (e: IOException) {
                return HandoverEnd.Retry(e.javaClass.simpleName)
            }
            when (reply.outcome) {
                ImportReply.Outcome.IMPORTED -> {
                    done += file.file.length()
                    progress(done, total)
                }
                ImportReply.Outcome.RETRY -> return HandoverEnd.Retry(reply.detail)
                ImportReply.Outcome.MISMATCH, ImportReply.Outcome.REFUSED -> return HandoverEnd.GaveUp(file.name, reply.detail)
            }
        }
        // Every file is the add-on's now: only now do KJB's copies go (with what never finished, parts included).
        markDone()
        if (folder.exists()) folder.deleteRecursively()
        return HandoverEnd.Done
    }
}
