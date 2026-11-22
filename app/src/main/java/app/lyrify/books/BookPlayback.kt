package app.lyrify.books

import androidx.media3.session.MediaLibraryService
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.EngineListener
import app.lyrify.player.OpenedFiles
import app.lyrify.player.PlaybackService
import app.lyrify.player.PlayerCore
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerPort
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.ServicePart
import app.lyrify.player.SkipAmounts
import app.lyrify.player.SleepTimer
import app.lyrify.player.SourceKind
import app.lyrify.player.Speed
import app.lyrify.player.Switched
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

// A book started from anywhere (BK1, decided 2026-11-22): a list's tap on a book's file (an opened file, Folders, search,
// a playlist, a selection's Play), a queue's song, Resume or Play from start plays the book's own queue, as its card on
// the Books tab does, so a book always plays as a book. A song reached by playing on in a queue of music stays as it is.

/** Where a book started from one of its files plays from: [book] at [place]. */
data class BookStart(val book: Book, val place: BookPlace)

/**
 * Where the book of the file at [path] starts: at its place in `books.json` ([state]) for a book of one file, or for the
 * part that place is in; another part of a folder book from its start. Null for music, and for a file [library] does not
 * know (a copy of an opened file, or the library not read yet).
 */
fun bookStart(path: String, library: Library?, state: BookState): BookStart? {
    val book = library?.bookOf(path) ?: return null
    val part = book.parts.indexOfFirst { it.path == path }
    val kept = state.placeOf(book)
    return BookStart(book, if (part == kept.partIndex) kept else BookPlace(part, 0))
}

/**
 * [start]'s book plays: its own queue ([bookSource]) from [BookStart.place], the queue left kept in Recent. The book's
 * queue playing already on that part plays on where it is; on another part it goes there.
 */
fun QueueBook.playBook(start: BookStart, now: Long): Switched {
    val path = start.book.parts[start.place.partIndex].path
    val playing = current
    if (playing != null && playing.from(bookSource(start.book)) && playing.current?.song?.path == path) return Switched(this, toRecent = false)
    return playBookAt(start, now)
}

/** [start]'s book plays from [BookStart.place] exactly: its own queue too, when that plays already, goes there. */
private fun QueueBook.playBookAt(start: BookStart, now: Long): Switched {
    val played = play(start.book.parts.map { it.ref }, start.place.partIndex, bookSource(start.book), now)
    val index = played.book.current?.index ?: return played
    return played.copy(book = played.book.place(index, start.place.positionMs))
}

/**
 * [switched], what a list's tap or a queue's song, Resume or Play from start made of this book of queues; or, when the song
 * it starts is a book's part ([bookStart], by [library] and [state]) in a queue that is not that book's, the book's own
 * queue instead ([playBook]). A change that changed nothing starts nothing either.
 */
fun QueueBook.asBook(switched: Switched, library: Library?, state: BookState, now: Long): Switched {
    if (switched.book === this) return switched
    val queue = switched.book.current ?: return switched
    val start = queue.current?.song?.path?.let { bookStart(it, library, state) } ?: return switched
    if (playingBook(queue, library)?.book?.id == start.book.id) return switched
    return playBook(start, now)
}

/**
 * The "Opened file" queue playing, left on a book's file, as that book's own queue ([playBook]): the library's file, or the
 * library's song a cache copy is ([copyOf], by its name and size). It goes on where it was listened to last: the queue's
 * own place when it played after the place `books.json` ([state]) keeps (since 3.1.0 an opened book kept nothing there),
 * else the book's place. Null when nothing changes: another queue, music, an unmatched copy, the library not read.
 */
fun QueueBook.openedAsBook(library: Library?, state: BookState, copyOf: (SongRef) -> Song?, now: Long): Switched? =
    current?.let { openedAsBook(it, library, state, copyOf, now) }

/** [openedAsBook] for [queue], playing or not: the book's queue plays, [queue] kept as it was (in Recent when it was left). */
private fun QueueBook.openedAsBook(queue: Queue, library: Library?, state: BookState, copyOf: (SongRef) -> Song?, now: Long): Switched? {
    if (queue.source.kind != SourceKind.OPENED) return null
    val ref = queue.current?.song ?: return null
    val path = ref.path.takeIf { library?.bookOf(it) != null } ?: copyOf(ref)?.path ?: return null
    val book = library?.bookOf(path) ?: return null
    val heardHere = (state.entryOf(book)?.lastPlayedAt ?: Long.MIN_VALUE) < queue.updatedAt
    val place = if (heardHere) BookPlace(book.parts.indexOfFirst { it.path == path }, queue.positionMs) else state.placeOf(book)
    return playBookAt(BookStart(book, place), now)
}

/**
 * "Resume" on queue [queueId] ([QueueBook.resume]): an "Opened file" queue left on a book's file (a cache copy by
 * [copyOf]) goes on as that book where it was listened to last ([openedAsBook]); any other as a list's start would
 * ([asBook]).
 */
fun QueueBook.resumeAsBook(queueId: String, library: Library?, state: BookState, copyOf: (SongRef) -> Song?, now: Long): Switched =
    queue(queueId)?.let { openedAsBook(it, library, state, copyOf, now) } ?: asBook(resume(queueId, now), library, state, now)

/**
 * The "Opened file" queue playing, when it is left on a book's file, made that book's queue ([openedAsBook]) while the
 * player is [paused] (not asked to play): a cache copy is looked up by [copyOf] first (by its name and size, off the main
 * thread), the library's own file needs no lookup. Main thread.
 */
suspend fun PlayerCore.openedIntoBook(library: Library, books: () -> BookState, copyOf: suspend (String) -> Song?, paused: () -> Boolean) {
    val item = book.value.current?.takeIf { it.source.kind == SourceKind.OPENED }?.current?.song ?: return
    val copy = if (library.bookOf(item.path) == null) copyOf(item.path) else null
    // Play pressed meanwhile: it goes on as it is, and becomes the book at its next pause.
    if (!paused()) return
    switch { it.openedAsBook(library, books(), { ref -> copy?.takeIf { ref == item } }, now()) }
}

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

/**
 * Whether song [uid] of [queue] plays as a book's part, for Remember my place and the speeds: once the library is read
 * ([library]), one of the book's parts ([playingBook]); before that, every song of a book's queue, so a book restored
 * while the library loads never jumps to a place Remember my place kept.
 */
fun playsAsBook(queue: Queue?, library: Library?, uid: Long): Boolean = inBook(queue) && (library == null || playingBook(queue, library, uid) != null)

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
 * Whether a play of the file at [path] is a book's, for the play counter ([BookPlayFilter]): a book's part as the lyrics
 * judge it ([isBookPart]: by [library] once it is read, else by what the path says), or, while the library is read, any
 * song of a book's [queue], as [playsAsBook] decides then: a book resumed in its queue on a cold start is no music play.
 */
fun playIsBook(path: String, queue: Queue?, library: Library?): Boolean = isBookPart(path, library) || library == null && inBook(queue)

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

    /** The user jumped inside the part playing, to [toMs] (a chapter, 30 s): kept at once, so nothing after it loses it. */
    fun seeked(toMs: Long) {
        val now = seen() ?: return
        current = now
        lastMs = toMs
        store.change { it.withPlace(now.book, BookPlace(now.partIndex, toMs), clock()) }
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

    /** Notes the part playing and where. */
    private fun look() {
        val now = seen() ?: return
        current = now
        lastMs = port.positionMs
    }

    /** The book's part playing, unless the player is in another one it has not said it started yet. */
    private fun seen(): PlayingBook? {
        val now = playing() ?: return null
        val was = current
        return now.takeIf { was == null || was.book.id == now.book.id && was.partIndex == now.partIndex }
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
        val store = BooksGraph.store(service)
        val made = BookDriver(engine, ::playing, store, System::currentTimeMillis)
        driver = made
        // Left paused by the user, not waiting on a load or a call: what the player is asked to do, not whether it sounds.
        val paused = MutableStateFlow(!engine.playWhenReady)
        engine.addListener(object : EngineListener {
            override fun onChanged() {
                paused.value = !engine.playWhenReady
            }

            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) = made.songStarted(uid, lap, natural)

            override fun onPlayingChanged(playing: Boolean) {
                made.playingChanged(playing)
                paused.value = !engine.playWhenReady
            }

            override fun onSeeked(fromMs: Long, toMs: Long) = made.seeked(toMs)

            // The service parks the queue at its first song first (PlaybackService's Follower).
            override fun onEnded() = made.ended()
        })
        made.playingChanged(engine.isPlaying)
        scope.launch { made.run() }
        // An "Opened file" queue left paused on a book's file goes on as its book (BK1): on a restart or an update, on a
        // pause, and once the library is read, so any Play resumes it as a book.
        scope.launch {
            combine(core.book, library, paused) { queues, state, still -> Triple(queues, state, still) }.collect { (queues, state, still) ->
                val known = (state as? LibraryState.Ready)?.library ?: return@collect
                if (!still || queues.current?.source?.kind != SourceKind.OPENED) return@collect
                core.openedIntoBook(known, store.state::value, { path -> OpenedFiles.libraryCopyOf(service, path, known.all) }) { !engine.playWhenReady }
            }
        }
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
