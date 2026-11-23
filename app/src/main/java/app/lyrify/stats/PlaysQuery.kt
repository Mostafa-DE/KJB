package app.lyrify.stats

import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.books.BooksGraph
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LyricsStatus
import app.lyrify.huddle.HuddleGraph
import app.lyrify.huddle.Sent
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.SongRef
import app.lyrify.logFailures
import app.lyrify.player.PlayerExtras
import app.lyrify.settings.Settings
import app.lyrify.suggestions.FriendPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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
    /** "Most played": the songs played most since [since] (wall-clock millis; null: all time), the most first. */
    suspend fun mostPlayed(limit: Int = 50, since: Long? = null): List<SongPlays>

    /** "Recently played": the songs played last since [since] (null: all time), the latest first. */
    suspend fun recentlyPlayed(limit: Int = 50, since: Long? = null): List<SongPlays>

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
    override suspend fun mostPlayed(limit: Int, since: Long?): List<SongPlays> = emptyList()

    override suspend fun recentlyPlayed(limit: Int, since: Long?): List<SongPlays> = emptyList()

    override suspend fun plays(song: SongRef): SongPlays? = null
}

/** The play counter's objects, built by hand, one of each per process. */
object StatsGraph {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)
    private var db: PlaysDb? = null
    private var store: PlayLogStore? = null
    private var counter: PlayCounter? = null
    @Volatile
    private var stats: PlayStats? = null
    private var interactions: InteractionLog? = null
    private var share: StateFlow<Int?>? = null
    private var bookLog: PlayLogStore? = null
    private var bookCounter: PlayCounter? = null

    /**
     * `plays.db` in app-private `databases/`, an unreadable one moved aside there: one helper for the play log and Huddle's
     * history (huddle/HuddleGraph.kt), which keeps its tables there too.
     */
    @Synchronized
    fun db(context: Context): PlaysDb = db ?: PlaysDb(context.applicationContext, log = { Log.w(LOG_TAG, it) }).also { db = it }

    /** The play log in [db]; what is written tells Insights. */
    @Synchronized
    private fun store(context: Context): PlayLogStore = store ?: run {
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        WatchedPlayLog(SqlPlayLog(db(context), log = log)) { startedAt -> stats?.changed(startedAt) }
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
     * where no music list or count reads them; in backups as a section of its own (backup/, Section.BOOK_PLAYS).
     */
    @Synchronized
    fun bookLog(context: Context): PlayLogStore = bookLog ?: run {
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        WatchedPlayLog(SqlPlayLog(PlaysDb(context.applicationContext, name = BOOK_PLAYS, log = log), log = log)) { startedAt -> stats?.booksChanged(startedAt) }
    }.also { bookLog = it }

    /** Counts the time heard in books into the book log, as the play counter counts music. */
    @Synchronized
    private fun bookCounter(context: Context): PlayCounter = bookCounter ?: run {
        PlayCounter(bookLog(context), share(context), scope = scope, log = { Log.w(LOG_TAG, it) })
    }.also { bookCounter = it }

    /** Plays per song, joined with the library of the moment; Insights with the book log, Favourites and the page's other sources. */
    @Synchronized
    fun stats(context: Context): PlayStats = stats ?: run {
        val app = context.applicationContext
        val library = LibraryGraph.repository(context).state
        val playlists = LibraryGraph.playlists(context)
        val ledger = LedgerRepository(app)
        PlayStats(
            store(context),
            library = { (library.value as? LibraryState.Ready)?.library },
            books = bookLog(context),
            favourites = { playlists.state.first().favouriteSongs },
            sources = { from ->
                // Huddle's history (past friends too), Favourites' dates, the lyrics written or found, bookmarks and books.
                val huddle = HuddleGraph.huddle(app)
                val friends = huddle.state.first()
                PageSources(
                    favouriteAdded = playlists.state.first().favouriteAdded,
                    synced = ledger.ledger.first().entries.filterValues { it.status.written || it.status == LyricsStatus.EXISTING }.keys,
                    bookmarks = PlayerExtras.of(app).state.value.bookmarks.values.flatten().map { it.addedAt }.filter { it > 0 },
                    finishedBooks = BooksGraph.store(app).state.value.books.values.filter { it.finished }.mapNotNull { it.lastPlayedAt },
                    friends = friends.friends.map { FriendName(it.id, it.shownName, past = false) } + friends.past.map { FriendName(it.id, it.name, past = true) },
                    friendPlays = huddle.historySince(from ?: 0).map { FriendPlay(it.friend, it.song.title, it.song.artist, it.song.album, it.song.durationMs, it.at, it.favourite) },
                    shared = huddle.everyCard().filter { (it as? Sent)?.delivered != false }.flatMap { kept ->
                        kept.card.songs.map { SharedSong(kept.id, kept.friend, kept.at, kept is Sent, it.title, it.artist, it.durationMs) }
                    },
                )
            },
        )
    }.also { stats = it }

    /** The interaction log, beside the plays in `plays.db`: the screens note into it, Suggestions reads it. */
    @Synchronized
    fun interactions(context: Context): InteractionLog = interactions ?: InteractionLog(store(context), scope, log = { Log.w(LOG_TAG, it) }).also { interactions = it }

    /** Writes to the play log and the book log, counted ([PlayCounter.written]): Insights read again after them. */
    fun writes(context: Context): Flow<Int> = combine(counter(context).written, bookCounter(context).written) { music, books -> music + books }

    fun bookEvents(context: Context): PlayEvents = bookCounter(context)

    /** The play log itself, for a backup and a restore (backup/). */
    fun log(context: Context): PlayLogStore = store(context)

    fun events(context: Context): PlayEvents = counter(context)

    fun plays(context: Context): PlaysQuery = stats(context)

    private const val BOOK_PLAYS: String = "book-plays.db"
}
