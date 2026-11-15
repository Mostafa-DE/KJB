package app.lyrify.books

import androidx.media3.session.MediaLibraryService
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.player.EngineListener
import app.lyrify.player.PlaybackService
import app.lyrify.player.PlayerCore
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerPort
import app.lyrify.player.Queue
import app.lyrify.player.QueueSource
import app.lyrify.player.ServicePart
import app.lyrify.player.SkipAmounts
import app.lyrify.player.SleepTimer
import app.lyrify.player.SourceKind
import app.lyrify.player.Speed
import app.lyrify.player.partScope
import app.lyrify.player.skipAmounts
import app.lyrify.settings.PlaybackSettings
import app.lyrify.stats.PlayEvent
import app.lyrify.stats.PlayEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// Book mode (research section 3): a book's queue (SourceKind.BOOK) plays with 30 s back and forward in place of Previous
// and Next, at the book's own speed, without crossfade or Repeat, its place kept in `books.json` every 15 s and at every
// pause, part change and stop, and its plays never logged.

/** A book's jumps back and forward, whatever the music's "Skip amounts". */
const val BOOK_SKIP_MS: Long = 30_000

/** How often the place of the book playing is kept while it plays: a crash loses at most this much. */
const val BOOK_SAVE_EVERY_MS: Long = 15_000

/** Whether [queue] is a book's: book mode. */
fun inBook(queue: Queue?): Boolean = queue?.source?.kind == SourceKind.BOOK

/** The seek buttons' jumps for [queue]: 30 s in a book, else the music's "Skip amounts" ([playback]). */
fun skipsFor(queue: Queue?, playback: PlaybackSettings): SkipAmounts = if (inBook(queue)) SkipAmounts(BOOK_SKIP_MS, BOOK_SKIP_MS) else skipAmounts(playback)

/** The source of [book]'s own queue: its key is the book's id. */
fun bookSource(book: Book): QueueSource = QueueSource(SourceKind.BOOK, book.id, book.title)

/** The book whose queue plays, and the part playing in it ([partIndex] in [Book.parts]). */
data class PlayingBook(val book: Book, val partIndex: Int)

/**
 * The book [queue] plays, with the part of its song [uid] (the queue's current one unless given); null for music, a book
 * gone from [library], or a song in the queue that is not one of the book's parts (added with Play next).
 */
fun playingBook(queue: Queue?, library: Library?, uid: Long? = queue?.current?.uid): PlayingBook? {
    if (queue == null || !inBook(queue) || library == null) return null
    val book = library.book(queue.source.key) ?: return null
    val path = queue.items.firstOrNull { it.uid == uid }?.song?.path ?: return null
    val part = book.parts.indexOfFirst { it.path == path }
    return if (part < 0) null else PlayingBook(book, part)
}

/** The speed of the book playing, as `books.json` has it; null while music plays: the player's speed follows it (player/PlaybackExtras.kt). */
fun bookSpeeds(core: PlayerCore, library: StateFlow<LibraryState>, store: BookStore): Flow<Speed?> =
    combine(core.book, library, store.state) { queues, state, books ->
        playingBook(queues.current, (state as? LibraryState.Ready)?.library)?.let { books.speedOf(it.book) }
    }.distinctUntilChanged()

/** "End of chapter" in a single file of [chapters] at [positionMs]: stop at the end of the chapter playing; null without chapters. */
fun sleepToChapterEnd(chapters: List<Chapter>, positionMs: Long): SleepTimer.ByChapter? {
    if (chapters.isEmpty()) return null
    val at = chapterAt(chapters, positionMs).coerceAtLeast(0)
    return SleepTimer.ByChapter(chapters[at].endMs, chapters.map { it.endMs })
}

/**
 * The play counter's events ([next]) without a book part's: a play whose song [isBook] when it starts is never logged,
 * none of its events passed on.
 */
class BookPlayFilter(private val next: PlayEvents, private val isBook: (String) -> Boolean) : PlayEvents {
    /** The plays of books going on, by play id. */
    private val books = HashSet<Long>()

    override fun onEvent(event: PlayEvent) {
        if (event is PlayEvent.Started && isBook(event.song.path)) books += event.playId
        if (event.playId in books) {
            if (event is PlayEvent.Ended) books -= event.playId
            return
        }
        next.onEvent(event)
    }
}

/**
 * Keeps the place of the book playing in `books.json` ([store]) from the player ([port]): every [BOOK_SAVE_EVERY_MS]
 * while it plays, at every pause, when a part plays to its end and the next starts, as the service stops, and when
 * music or another book takes over, where it was last seen (to the second); the book's last part played to its end
 * makes it finished. [playing] names the book and part playing now, null for music. Main thread.
 */
class BookDriver(
    private val port: PlayerPort,
    private val playing: () -> PlayingBook?,
    private val store: BookStore,
    private val clock: () -> Long,
) {
    private val sounding = MutableStateFlow(false)

    /** The book's part last seen playing, and where: at a song change the player is in the next song already. */
    private var current: PlayingBook? = null
    private var lastMs: Long = 0

    /** Runs until cancelled. */
    suspend fun run() {
        sounding.collectLatest { on ->
            var looked = 0L
            while (on) {
                delay(LOOK_EVERY_MS)
                look()
                looked += LOOK_EVERY_MS
                if (looked % BOOK_SAVE_EVERY_MS == 0L) keep()
            }
        }
    }

    /**
     * A song started. Music or another book after a book keeps that book where it was last seen, or at its next part
     * when its part played to its end. A part that followed the one before by itself is kept at once. A jump in the
     * book (Continue, a chapter) is kept at the next save, never at once, as the player may not stand at its place yet.
     */
    fun songStarted(uid: Long, lap: Boolean, natural: Boolean) {
        val left = current
        val leftMs = lastMs
        val now = playing()
        current = now
        lastMs = port.positionMs
        if (left != null && left.book.id != now?.book?.id) {
            leave(left, leftMs, natural)
        } else if (natural && !lap) {
            keep()
        }
    }

    fun playingChanged(playing: Boolean) {
        sounding.value = playing
        look()
        if (!playing) keep()
    }

    /** The queue played to its end with Repeat off: a book's last part is done, the book finished. */
    fun ended() {
        val now = playing() ?: return
        store.change { it.finished(now.book, clock()) }
    }

    /** The service stops. */
    fun stopped() = keep()

    private fun keep() {
        val now = playing() ?: return
        val at = BookPlace(now.partIndex, port.positionMs)
        store.change { it.withPlace(now.book, at, clock()) }
    }

    /** Notes the part playing and where, unless the player is in another one it has not said it started yet. */
    private fun look() {
        val now = playing() ?: return
        val was = current
        if (was != null && (was.book.id != now.book.id || was.partIndex != now.partIndex)) return
        current = now
        lastMs = port.positionMs
    }

    /** The player left [left]'s book at [atMs] of its part, or ([natural]) at the end of it: the next part, or finished. */
    private fun leave(left: PlayingBook, atMs: Long, natural: Boolean) {
        val book = left.book
        store.change { state ->
            when {
                !natural -> state.withPlace(book, BookPlace(left.partIndex, atMs), clock())
                left.partIndex < book.parts.lastIndex -> state.withPlace(book, BookPlace(left.partIndex + 1, 0), clock())
                else -> state.finished(book, clock())
            }
        }
    }

    private companion object {
        /** How often the place is noted while a book plays. */
        const val LOOK_EVERY_MS: Long = 1_000
    }
}

/** Book mode in the service: [BookDriver] on the engine, and the chapters of the book playing read. */
class BookPart : ServicePart {
    private var scope: CoroutineScope? = null
    private var driver: BookDriver? = null

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val scope = partScope().also { scope = it }
        val engine = service.playbackEngine
        val core = PlayerGraph.core(service)
        val library = LibraryGraph.repository(service).state
        val chapters = BooksGraph.chapters(service)
        fun playing(): PlayingBook? = playingBook(core.book.value.current, (library.value as? LibraryState.Ready)?.library, engine.loaded.getOrNull(engine.index))
        val made = BookDriver(engine, ::playing, BooksGraph.store(service), System::currentTimeMillis)
        driver = made
        engine.addListener(object : EngineListener {
            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) = made.songStarted(uid, lap, natural)

            override fun onPlayingChanged(playing: Boolean) = made.playingChanged(playing)

            // The service parks the queue at its first song first (PlaybackService's Follower).
            override fun onEnded() = made.ended()
        })
        made.playingChanged(engine.isPlaying)
        scope.launch { made.run() }
        // The chapters of a single file playing, read once for the player and the sleep timer.
        scope.launch {
            combine(core.book, library) { queues, state -> playingBook(queues.current, (state as? LibraryState.Ready)?.library)?.book }
                .distinctUntilChanged()
                .collect { book -> book?.let(chapters::ensure) }
        }
    }

    override fun stop() {
        driver?.stopped()
        driver = null
        scope?.cancel()
        scope = null
    }
}
