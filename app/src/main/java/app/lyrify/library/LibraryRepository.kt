package app.lyrify.library

import android.content.Context
import android.util.Log
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.logFailures
import app.lyrify.settings.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.measureTimedValue

/** The library as the app has it now. */
sealed interface LibraryState {
    /** Not read yet. */
    data object Loading : LibraryState

    /** MediaStore could not be read, and has not been since the app started (no All files access, say). */
    data object Unreadable : LibraryState

    data class Ready(val library: Library) : LibraryState
}

/**
 * The current [Library] for every screen, the player and the play counter. Songs are read on [io] once, then again
 * only when MediaStore reports a change to them, after [DEBOUNCE_MS] without another (it reports a burst while it scans),
 * and only if its version and generation moved ([LibrarySource.stamp]). The library is regrouped on [compute] when the
 * songs or the user's filters ("Skip short clips", "Folders to skip") change; other settings change nothing here. A read
 * that fails keeps the library as it was. The log gets counts, times and exception names only.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val source: LibrarySource,
    settings: Flow<Settings>,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
) {
    private val asked = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val force = AtomicBoolean(false)

    /** Every song MediaStore lists, each time that changes; null while it has never been readable. */
    private val songs: Flow<List<Song>?> = flow {
        var stamp: String? = null
        var read = false
        merge(flowOf(Unit), source.changes.debounce(DEBOUNCE_MS), asked).conflate().collect {
            val now = withContext(io) { source.stamp() }
            if (!force.getAndSet(false) && now != null && now == stamp) return@collect
            val (result, took) = measureTimedValue { withContext(io) { source.read() } }
            when (result) {
                is SongsRead.Songs -> {
                    log("library: ${result.songs.size} songs read in ${took.inWholeMilliseconds} ms")
                    stamp = now
                    read = true
                    emit(result.songs)
                }
                is SongsRead.Unavailable -> {
                    log("library: not read (${result.cause})")
                    if (!read) emit(null)
                }
            }
        }
    }

    val state: StateFlow<LibraryState> = combine(
        songs,
        settings.distinctUntilChanged { a, b -> a.playback.skipShortClips == b.playback.skipShortClips && a.library.foldersToSkip == b.library.foldersToSkip },
    ) { all, filters -> all to filters }
        .mapLatest { (all, filters) -> if (all == null) LibraryState.Unreadable else LibraryState.Ready(withContext(compute) { Library.of(all, filters) }) }
        .stateIn(scope, SharingStarted.Eagerly, LibraryState.Loading)

    /** Reads the songs again even if MediaStore says nothing changed: after All files access is granted, say. */
    fun refresh() {
        force.set(true)
        asked.tryEmit(Unit)
    }

    companion object {
        const val DEBOUNCE_MS: Long = 1_000
    }
}

/** The library's objects, built by hand, one of each per process. */
object LibraryGraph {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)
    private var repository: LibraryRepository? = null
    private var playlists: PlaylistsRepository? = null

    /** The current library, read from MediaStore (a debug build: its test folder only). */
    @Synchronized
    fun repository(context: Context): LibraryRepository = repository ?: LibraryRepository(
        source = LibraryReader(context.applicationContext, Lyrify.writes),
        settings = Lyrify.settings(context).settings,
        scope = scope,
        log = { Log.i(LOG_TAG, it) },
    ).also { repository = it }

    /** Playlists and Favourites in app-private `files/datastore/playlists.json`, copies of an unreadable one in `files`. */
    @Synchronized
    fun playlists(context: Context): PlaylistStore = playlists ?: run {
        val app = context.applicationContext
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        val store = playlistsStore(app.dataStoreFile("playlists.json"), app.filesDir, CoroutineScope(Dispatchers.IO + SupervisorJob()), System::currentTimeMillis, log)
        PlaylistsRepository(store, clock = System::currentTimeMillis, newId = { UUID.randomUUID().toString() }, log = log)
    }.also { playlists = it }

    fun favourites(context: Context): FavouritesPort = playlists(context)
}
