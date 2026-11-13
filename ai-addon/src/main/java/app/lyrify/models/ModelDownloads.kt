package app.lyrify.models

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import app.lyrify.ai.LOG_TAG
import app.lyrify.ai.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What one model download tells KJB (IAiDownloadCallback, as Kotlin). */
interface DownloadEvents {
    /** Bytes done of all the models, at most once per whole percent. */
    fun onProgress(done: Long, total: Long)

    fun onEnd(end: Download)
}

/** Where the add-on's download stands. */
sealed interface RunState {
    data object Idle : RunState

    /** [doneBytes] of all the models so far; null until the download first says. */
    data class Running(val doneBytes: Long?) : RunState

    /** The last one failed for good (a file not as pinned); the next start tries anew. */
    data object Failed : RunState
}

/**
 * The add-on's model download ([download]: the downloader under the store's lock, so runs take the files one after the
 * other), in [scope]: the bound service's. KJB starts it from its own foreground service and shows its notification; the
 * add-on runs no foreground service and posts no notification. Each [start] is its own run with its own id and its own
 * caller ([DownloadEvents]): only that caller hears its progress and its end, and only [cancel] with that id stops it
 * (a run KJB replaced, still winding down, can never end or stop the one after it). Stopped, it ends as stopped and keeps
 * its parts: the next run goes on from them (HTTP Range) and checks every file's SHA-256 at the end.
 */
class DownloadRun(private val scope: CoroutineScope, private val download: suspend (progress: suspend (Long, Long) -> Unit) -> Download) {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)

    /** The newest run's state, for the status. */
    val state: StateFlow<RunState> = _state.asStateFlow()

    private class Run(val id: Long, val events: DownloadEvents) {
        lateinit var job: Job
    }

    private val runs = HashMap<Long, Run>()
    private var newest = 0L
    private var nextId = 1L

    /** Starts a run heard by [events]; its id. */
    @Synchronized
    fun start(events: DownloadEvents): Long {
        val run = Run(nextId++, events)
        newest = run.id
        _state.value = RunState.Running(null)
        // ATOMIC: the body runs, and the run ends to its caller, even when the scope goes before it was dispatched.
        run.job = scope.launch(start = CoroutineStart.ATOMIC) {
            val end = try {
                download { done, total ->
                    if (isNewest(run)) _state.value = RunState.Running(done)
                    events.onProgress(done, total)
                }
            } catch (e: CancellationException) {
                finish(run, Download.Interrupted("stopped"))
                throw e
            }
            finish(run, end)
        }
        runs[run.id] = run
        return run.id
    }

    /** Stops run [id] (its caller asked, or went away), and no other; its parts stay. */
    @Synchronized
    fun cancel(id: Long) {
        runs[id]?.job?.cancel()
    }

    /** Stops every run: KJB left, or the models are about to change (deleted, handed over). */
    @Synchronized
    fun cancelAll() {
        runs.values.forEach { it.job.cancel() }
    }

    @Synchronized
    private fun isNewest(run: Run): Boolean = run.id == newest

    /** [run] ended: only the newest run sets the state, and only [run]'s own caller hears [end]. */
    private fun finish(run: Run, end: Download) {
        synchronized(this) {
            runs.remove(run.id)
            if (run.id == newest) _state.value = if (end is Download.Failed) RunState.Failed else RunState.Idle
        }
        run.events.onEnd(end)
    }
}

/** The add-on's models: where they are, their download, deleting them, and the state KJB's AI page shows. */
object ModelDownloads {
    /** Held while a download runs, so a delete or the handover waits until it has stopped writing. */
    internal val lock = Mutex()

    /** Bumped after a delete or a handover: the files changed without a download. */
    private val changed = MutableStateFlow(0)

    /** The add-on's app-private models folder. */
    fun folder(context: Context): File = File(context.applicationContext.filesDir, MODELS)

    /** The models for this phone's chip; null when the AI does not run on it ([NpuChips.supported]). */
    fun store(context: Context): ModelStore? = NpuChips.supported()?.let { chip ->
        val app = context.applicationContext
        ModelStore(folder(app), ModelSpec.forChip(chip), File(app.cacheDir, MODELS))
    }

    /**
     * One download of the models not in place, from their parts: a dropped connection that got further goes on at once
     * ([retryWhileProgressing]); one that got nowhere ends it (KJB tries again later). A download stopped a moment ago
     * may still be writing its part: this one goes on once it has let go.
     */
    suspend fun download(context: Context, progress: suspend (Long, Long) -> Unit): Download {
        val store = store(context) ?: return Download.Failed("no AI on this phone")
        val downloader = ModelDownloader(store, USER_AGENT, log = { Log.d(LOG_TAG, "models: $it") })
        val end = lock.withLock { retryWhileProgressing(store::bytesOnDisk) { downloader.downloadAll(progress) } }
        when (end) {
            Download.Done -> Log.i(LOG_TAG, "models: all downloaded and verified")
            is Download.Interrupted -> Log.w(LOG_TAG, "models: download stopped, goes on later: ${end.reason}")
            is Download.Failed -> Log.w(LOG_TAG, "models: download failed: ${end.reason}")
        }
        return end
    }

    /**
     * [block] on the models while no download writes them (the handover of KJB 2.0's models), on IO; the card reads the
     * files again after. Null on a phone the AI does not run on, or when a download did not let go within
     * [LOCK_WAIT_MS] (KJB stops the download first, and tries again later).
     */
    suspend fun <T> withStore(context: Context, block: (ModelStore) -> T): T? {
        val store = store(context) ?: return null
        if (withTimeoutOrNull(LOCK_WAIT_MS) { lock.lock() } == null) return null
        return try {
            withContext(Dispatchers.IO) { block(store) }
        } finally {
            lock.unlock()
            changed.update { it + 1 }
        }
    }

    /** Deletes the models and their parts, once a download (stopped first by the caller) has let go; true when none is left. */
    suspend fun delete(context: Context): Boolean {
        val gone = lock.withLock {
            withContext(Dispatchers.IO) { store(context)?.delete() ?: ModelStore.removeRetired(folder(context)) }
        }
        changed.update { it + 1 }
        if (!gone) Log.w(LOG_TAG, "models: not every file could be deleted")
        return gone
    }

    /** A model that did not load: deletes that file only (see [ModelStore.discard]); the models are not ready then. True when it is gone. */
    suspend fun discard(context: Context, model: File): Boolean {
        val gone = lock.withLock { withContext(Dispatchers.IO) { store(context)?.discard(model) ?: false } }
        changed.update { it + 1 }
        if (!gone) Log.w(LOG_TAG, "models: ${model.name} could not be deleted")
        return gone
    }

    /**
     * The card's model state: the files on disk and the free space, read again whenever the [run] moves on or the files
     * change otherwise. Null on a phone whose chip the AI does not run on. Waiting for Wi-Fi is KJB's to say: it runs
     * the download only on the networks it allows.
     */
    fun state(context: Context, run: StateFlow<RunState>): Flow<ModelState?> {
        val store = store(context) ?: return flowOf(null)
        return combine(run, changed) { now, _ ->
            modelState(
                ready = store.isReady(),
                onDisk = store.bytesOnDisk(),
                total = store.totalBytes,
                phase = when (now) {
                    is RunState.Running -> DownloadPhase.RUNNING
                    RunState.Failed -> DownloadPhase.FAILED
                    RunState.Idle -> null
                },
                doneBytes = (now as? RunState.Running)?.doneBytes,
                neededBytes = store.bytesNeeded(),
                // What an earlier version left is deleted before the download checks the room: it counts as free.
                freeBytes = freeBytes(store) + store.retiredBytes(),
            )
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    /**
     * Free space where the models go, as the downloader measures it ([ModelDownloader]'s `File.getUsableSpace`), so the
     * card and the download agree. Not `StorageManager.getAllocatableBytes`: clearing cached data to make room could
     * clear the archive being downloaded into the cache.
     */
    @SuppressLint("UsableSpace")
    private fun freeBytes(store: ModelStore): Long = store.folder.parentFile?.usableSpace ?: Long.MAX_VALUE

    private const val MODELS = "models"
    private const val LOCK_WAIT_MS = 10_000L
}
