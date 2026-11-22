package app.lyrify.stats

import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.SongRef
import app.lyrify.logFailures
import app.lyrify.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One song's plays: how many, the last one (wall-clock millis), and how long it was heard in all. */
data class SongPlays(val song: SongRef, val count: Int, val lastPlayedAt: Long?, val heardMs: Long)

/**
 * What the library asks of the play counter: the "Most played" and "Recently played" lists and Song info's plays line.
 * stats/ implements it over `plays.db` ([PlayStats]); [NoPlays] in tests.
 */
interface PlaysQuery {
    /** "Most played": the songs played most, all time, the most first. */
    suspend fun mostPlayed(limit: Int = 50): List<SongPlays>

    /** "Recently played": the songs played last, the latest first. */
    suspend fun recentlyPlayed(limit: Int = 50): List<SongPlays>

    /** [song]'s plays; null when it was never played. */
    suspend fun plays(song: SongRef): SongPlays?

    /**
     * Every song played, hidden ones too, by its path in the library now: what the lists sorted by plays go by. None
     * where a counter keeps no plays per song.
     */
    suspend fun everySong(): Map<String, SongPlays> = emptyMap()
}

/** No song has been played: tests. */
object NoPlays : PlaysQuery {
    override suspend fun mostPlayed(limit: Int): List<SongPlays> = emptyList()

    override suspend fun recentlyPlayed(limit: Int): List<SongPlays> = emptyList()

    override suspend fun plays(song: SongRef): SongPlays? = null
}

/** The play counter's objects, built by hand, one of each per process. */
object StatsGraph {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)
    private var store: PlayLogStore? = null
    private var counter: PlayCounter? = null
    private var stats: PlayStats? = null
    private var share: StateFlow<Int?>? = null
    private var bookLog: PlayLogStore? = null
    private var bookCounter: PlayCounter? = null

    /** `plays.db` in app-private `databases/`, an unreadable one moved aside there. */
    @Synchronized
    private fun store(context: Context): PlayLogStore = store ?: run {
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        SqlPlayLog(PlaysDb(context.applicationContext, log = log), log = log)
    }.also { store = it }

    /** "Count a play after", from the moment the settings are read and as they change; null until then. */
    @Synchronized
    private fun share(context: Context): StateFlow<Int?> = share ?: run {
        Lyrify.settings(context).settings.map<Settings, Int?> { it.playback.countPlayAfterPercent }.stateIn(scope, SharingStarted.Eagerly, null)
    }.also { share = it }

    /** Counts KJB's own plays under "Count a play after". */
    @Synchronized
    fun counter(context: Context): PlayCounter = counter ?: run {
        PlayCounter(store(context), share(context), scope = scope, log = { Log.w(LOG_TAG, it) })
    }.also { counter = it }

    /**
     * The book log: `book-plays.db` beside `plays.db`, the same schema, so a book's plays are kept (for Insights' books)
     * where no music list or count reads them. Not in backups.
     */
    @Synchronized
    private fun bookLog(context: Context): PlayLogStore = bookLog ?: run {
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        SqlPlayLog(PlaysDb(context.applicationContext, name = BOOK_PLAYS, log = log), log = log)
    }.also { bookLog = it }

    /** Counts the time heard in books into the book log, as the play counter counts music. */
    @Synchronized
    private fun bookCounter(context: Context): PlayCounter = bookCounter ?: run {
        PlayCounter(bookLog(context), share(context), scope = scope, log = { Log.w(LOG_TAG, it) })
    }.also { bookCounter = it }

    /** Plays per song, joined with the library of the moment; Insights with the book log and Favourites. */
    @Synchronized
    fun stats(context: Context): PlayStats = stats ?: run {
        val library = LibraryGraph.repository(context).state
        val playlists = LibraryGraph.playlists(context)
        PlayStats(
            store(context),
            library = { (library.value as? LibraryState.Ready)?.library },
            books = bookLog(context),
            favourites = { playlists.state.first().favouriteSongs },
        )
    }.also { stats = it }

    /** Writes to the book log, counted as the play counter's [PlayCounter.written] counts its own: Insights read them again. */
    fun bookWritten(context: Context): StateFlow<Int> = bookCounter(context).written

    fun bookEvents(context: Context): PlayEvents = bookCounter(context)

    /** The play log itself, for a backup and a restore (backup/). */
    fun log(context: Context): PlayLogStore = store(context)

    fun events(context: Context): PlayEvents = counter(context)

    fun plays(context: Context): PlaysQuery = stats(context)

    private const val BOOK_PLAYS: String = "book-plays.db"
}
