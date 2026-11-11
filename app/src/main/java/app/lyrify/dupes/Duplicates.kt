package app.lyrify.dupes

import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.library.LibraryGraph
import app.lyrify.logFailures
import app.lyrify.lyrics.LrcFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Where a scan is. */
sealed interface ScanStatus {
    data object Idle : ScanStatus

    /** [done] of [total] files looked at; [total] is 0 while MediaStore is read. */
    data class Running(val done: Int, val total: Int) : ScanStatus

    /** MediaStore could not be read (All files access off?). The results before stay. */
    data object Failed : ScanStatus
}

/** The Duplicates tab's state. */
data class DuplicatesState(
    /** The stored memory is read; until then the tab shows nothing. */
    val loaded: Boolean = false,
    val scan: ScanStatus = ScanStatus.Idle,
    val trash: TrashStep = TrashStep.None,
    internal val memory: DuplicatesMemory = DuplicatesMemory(),
    /** Groups with a file that changed since the scan ([Confirmation.Changed]): nothing goes to the trash until the next scan. */
    val stale: Set<String> = emptySet(),
) {
    /** The last scan's groups; null before the first scan. */
    val results: ScanResults? get() = memory.results

    val reviewed: Set<String> get() = memory.reviewed

    /** Songs KJB moved to the phone's trash and can still restore, the latest first. */
    val trashed: List<TrashedSong> get() = memory.trashed.sortedByDescending { it.trashedAt }

    /** The tab's badge: groups not opened yet. */
    val badge: Int get() = results?.unreviewed(memory.reviewed) ?: 0
}

/**
 * The Duplicates tab's engine. It finds files that may be one song ([scan]: [identicalKeys], then [DuplicateGrouping])
 * and only ever suggests. Nothing is deleted by it: the only way a file goes is the phone's own trash request
 * ([MediaStoreTrash]), opened after KJB's own dialog ([askTrash], [confirmTrash]) for files the user checked, and
 * settled after the phone's answer ([systemAnswered]). Songs moved there are listed until the phone deletes them, and
 * can be taken back out through the phone's own dialog too ([restore]). Audio files are only read, and only to hash them.
 *
 * A scan runs in [scope] on [io], one file at a time; it is not resumed: one cancelled or cut short by the app's end
 * leaves the results before, and the next one starts over. What the user decided ("Not duplicates", the groups opened)
 * and the last results are kept in [store]. The log gets counts only, never titles or paths.
 */
class Duplicates(
    private val library: AudioLibrary,
    private val store: DuplicatesStore,
    private val cleanup: TrashCleanup,
    private val phoneTrash: PhoneTrash,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val hasher: ContentHasher = ContentHasher(),
    /** Whether a `.lrc` is next to a file; asked for files in groups only. */
    private val hasLyrics: (AudioFile) -> Boolean = ::sidecarExists,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()

    /** One write of [store] at a time, each of the memory as it is then. */
    private val saving = Mutex()

    private val loading: Job = scope.launch(io) {
        val memory = store.load()
        _state.update { it.copy(loaded = true, memory = memory) }
    }

    /**
     * Moving songs to the phone's trash and back, for this tab and the song sheet alike. Its memory is kept with the
     * results, so the files it trashes leave the groups in the same write.
     */
    val trash: SongTrash = SongTrash(
        host = object : TrashHost {
            override val step: TrashStep get() = _state.value.trash

            override val memory: TrashMemory get() = _state.value.memory.let { TrashMemory(it.pendingTrash, it.trashed) }

            override fun step(change: (TrashStep) -> TrashStep) = _state.update { it.copy(trash = change(it.trash)) }

            override suspend fun loaded() = loading.join()

            override suspend fun commit(moved: Set<Long>, change: (TrashMemory) -> TrashMemory) = commit { memory ->
                val kept = change(TrashMemory(memory.pendingTrash, memory.trashed))
                without(memory, moved).copy(pendingTrash = kept.pending, trashed = kept.trashed)
            }

            override fun changed(ids: Set<Long>) = _state.update { state ->
                val stale = state.results?.groups.orEmpty().filter { group -> group.files.any { it.id in ids } }.map { it.key }
                state.copy(stale = state.stale + stale)
            }
        },
        rows = library::rows,
        cleanup = cleanup,
        phoneTrash = phoneTrash,
        io = io,
        clock = clock,
        log = log,
    )

    /** The scan running; null when none is. Only it may change the scan status or store results. */
    @Volatile
    private var scanning: Job? = null

    /** "Scan my songs" / "Scan again": starts a scan unless one is running. */
    fun scan() {
        if (scanning?.isActive == true) return
        _state.update { it.copy(scan = ScanStatus.Running(0, 0)) }
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                loading.join()
                runScan(self)
            } finally {
                status(self, ScanStatus.Idle) { it is ScanStatus.Running }
            }
        }
        scanning = job
        job.start()
    }

    /** "Cancel": the scan stops after the file it is on; the results before stay. */
    fun cancelScan() {
        scanning?.cancel()
        scanning = null
        _state.update { if (it.scan is ScanStatus.Running) it.copy(scan = ScanStatus.Idle) else it }
    }

    /** The user opened [group]'s sheet: it no longer counts in the badge. */
    fun opened(group: DuplicateGroup) {
        if (!state.value.loaded || group.key in state.value.reviewed) return
        commitLater { it.copy(reviewed = it.reviewed + group.key) }
    }

    /** "Not duplicates": [group] is hidden for good, until one of its files changes ([DuplicateGroup.key]). */
    fun notDuplicates(group: DuplicateGroup) {
        if (!state.value.loaded) return
        commitLater { memory ->
            memory.copy(
                results = memory.results?.let { results -> results.copy(groups = results.groups.filter { it.key != group.key }) },
                notDuplicates = memory.notDuplicates + group.key,
                reviewed = memory.reviewed - group.key,
            )
        }
    }

    /**
     * A backup's choices (backup/, a restore) added to the user's: groups marked "Not duplicates" leave the results, as
     * [notDuplicates] does. They count only once stored: false, and nothing changed, when they could not be.
     */
    suspend fun restoreChoices(notDuplicates: Set<String>, reviewed: Set<String>): Boolean {
        loading.join()
        val change = { memory: DuplicatesMemory ->
            memory.copy(
                results = memory.results?.let { results -> results.copy(groups = results.groups.filter { it.key !in notDuplicates }) },
                notDuplicates = memory.notDuplicates + notDuplicates,
                reviewed = memory.reviewed + reviewed - notDuplicates,
            )
        }
        return withContext(io) {
            saving.withLock {
                val stored = store.save(change(state.value.memory))
                // Applied again to the memory as it is now: a change made while it was stored is kept.
                if (stored) _state.update { it.copy(memory = change(it.memory)) }
                stored
            }
        }
    }

    /**
     * "Move to trash" in [group]'s sheet with the files [ids] checked: KJB's own dialog asks first ([TrashStep.Confirm]).
     * Any files of a group shown now, the suggested keep too (it is only a hint), but never all of them, so one copy
     * always stays. False when [ids] are not such files: nothing is asked.
     */
    fun askTrash(group: DuplicateGroup, ids: Set<Long>): Boolean {
        val shown = state.value.results?.groups?.firstOrNull { it.key == group.key } ?: return false
        val files = shown.files.filter { it.id in ids }
        if (files.size != ids.size || files.size >= shown.files.size || shown.key in state.value.stale) return false
        return trash.ask(files)
    }

    /** "Cancel" in KJB's dialog: nothing changes. */
    fun cancelTrash() = trash.cancel()

    /** "Move to trash" in KJB's dialog for the [files] it shows ([SongTrash.confirm]); a changed file's group waits for the next scan. */
    suspend fun confirmTrash(files: List<AudioFile>): Confirmation = trash.confirm(files)

    /** The phone's dialog answered ([SongTrash.answered]); the files it trashed leave the results. */
    suspend fun systemAnswered(approved: Boolean): Trashed = trash.answered(approved)

    /** "Restore" on a song KJB trashed ([SongTrash.restore]). */
    fun restore(song: TrashedSong): List<Long> = trash.restore(song)

    /** The phone's restore dialog answered ([SongTrash.restoreAnswered]). */
    suspend fun restoreAnswered(approved: Boolean): Restored = trash.restoreAnswered(approved)

    /** Drops the trashed songs the phone no longer has in its trash, or that expired ([SongTrash.refresh]). */
    suspend fun refreshTrashed(): Unit = trash.refresh()

    /** Sets the scan status to [status] while [job] is the scan running and the status is one [replaces]. */
    private fun status(job: Job, status: ScanStatus, replaces: (ScanStatus) -> Boolean = { true }) {
        _state.update { if (scanning === job && replaces(it.scan)) it.copy(scan = status) else it }
    }

    private suspend fun runScan(self: Job) {
        // A trash answer the app did not live to hear: MediaStore confirms which files are trashed.
        trash.settlePending()
        refreshTrashed()
        val files = when (val read = library.read()) {
            is LibraryRead.Unavailable -> {
                log("duplicates: songs not readable (${read.cause})")
                status(self, ScanStatus.Failed)
                return
            }
            is LibraryRead.Files -> read.files.sortedBy { it.path }
        }
        status(self, ScanStatus.Running(0, files.size))
        val keys = identicalKeys(files, hasher) { done -> status(self, ScanStatus.Running(done, files.size)) }
        val found = DuplicateGrouping.groups(files, keys, hasLyrics)
        // The results and their file go together; a scan cancelled meanwhile stores nothing.
        withContext(NonCancellable) {
            if (scanning !== self) return@withContext
            val scannedAt = clock()
            commit { memory ->
                if (scanning !== self) return@commit memory
                val shown = found.filter { it.key !in memory.notDuplicates }
                memory.copy(results = ScanResults(scannedAt, files.size, shown), reviewed = memory.reviewed intersect shown.map { it.key }.toSet())
            }
            _state.update { if (scanning === self) it.copy(stale = emptySet()) else it }
            status(self, ScanStatus.Idle)
            log("duplicates: ${files.size} files, ${keys.size} hashed, ${found.size} groups")
        }
    }

    /** Changes the memory now and stores it before returning. */
    private suspend fun commit(change: (DuplicatesMemory) -> DuplicatesMemory) {
        _state.update { it.copy(memory = change(it.memory)) }
        persist()
    }

    /** Changes the memory now and stores it in the background. */
    private fun commitLater(change: (DuplicatesMemory) -> DuplicatesMemory) {
        _state.update { it.copy(memory = change(it.memory)) }
        scope.launch(io) { persist() }
    }

    private suspend fun persist() {
        withContext(io) { saving.withLock { store.save(state.value.memory) } }
    }

    /** [memory] without the files with the MediaStore ids [moved]; a group opened before is still reviewed with fewer files. */
    private fun without(memory: DuplicatesMemory, moved: Set<Long>): DuplicatesMemory {
        if (moved.isEmpty()) return memory
        val after = memory.results?.groups.orEmpty()
            .map { group -> group to group.copy(files = group.files.filter { it.id !in moved }) }
            .filter { it.second.files.size > 1 }
        val reviewed = after.filter { (old, _) -> old.key in memory.reviewed }.map { it.second.key }
        return memory.copy(
            results = memory.results?.copy(groups = after.map { it.second }),
            reviewed = (memory.reviewed + reviewed) intersect after.map { it.second.key }.toSet(),
        )
    }
}

/** Whether a `.lrc` is next to [file] (never through a link). Blocking. */
fun sidecarExists(file: AudioFile): Boolean =
    LrcFormat.sidecarPath(file.path)?.let { Files.exists(File(it).toPath(), LinkOption.NOFOLLOW_LINKS) } == true

/** The app's one Duplicates engine (one per process, like the lyrics pipeline), made on first use. */
private object DuplicatesHolder {
    private var engine: Duplicates? = null

    @Synchronized
    fun engine(context: Context): Duplicates = engine ?: context.applicationContext.let { app ->
        Duplicates(
            library = MediaStoreLibrary(app.contentResolver, Lyrify.writes),
            store = DuplicatesStore(File(app.filesDir, "duplicates.json")) { Log.w(LOG_TAG, it) },
            cleanup = { path ->
                Lyrify.pipeline(app).trashed(path)
                LibraryGraph.playlists(app).forget(setOf(path))
            },
            phoneTrash = MediaStoreTrash(app.contentResolver, Lyrify.writes),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures),
            io = Dispatchers.IO,
            clock = System::currentTimeMillis,
            log = { Log.d(LOG_TAG, it) },
        )
    }.also { engine = it }
}

/** The Duplicates tab's engine: app-private `files/duplicates.json`, never backed up (allowBackup is off). */
fun duplicates(context: Context): Duplicates = DuplicatesHolder.engine(context)
