package app.lyrify.player

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.books.BookState
import app.lyrify.books.BookStore
import app.lyrify.books.asBook
import app.lyrify.books.resumeIntoBook
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryRepository
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ExecutionException

/** What the session's player says, through the screens' MediaController: playing or not, where, and how long the song is. */
@Immutable
data class Transport(
    val connected: Boolean = false,
    val playing: Boolean = false,
    /** The song the player holds now, by its queue item's uid. */
    val uid: Long? = null,
    val positionMs: Long = 0,
    /** When [positionMs] was read, elapsed realtime. */
    val at: Long = 0,
    val speed: Float = 1f,
    val durationMs: Long = C.TIME_UNSET,
)

/**
 * KJB's own player as the screens show it: the queue playing and its current item, the library's song for it (null
 * while the library is read, or when the song is not on the phone), whether it plays, where it is, Repeat, and every
 * queue ([book]) for the queue pages.
 */
@Immutable
data class PlayerUi(
    val book: QueueBook,
    val queue: Queue,
    val item: QueueItem,
    val song: Song?,
    val playing: Boolean,
    /** The position at [at] (elapsed realtime); while [playing] it moves on at [speed]. */
    val positionMs: Long,
    val at: Long,
    val speed: Float,
    val durationMs: Long,
    val repeat: Repeat,
    val library: Library?,
    /** The tags of a file another app opened, while the library lacks it ([OpenedFiles]); [song] stays null: it is in no library. */
    val opened: Song? = null,
) {
    /** The tags the screens name the song by: the library's song, else an opened file's own ([opened]). */
    val named: Song? get() = song ?: opened

    /** Where the song is at [now] (elapsed realtime), within its length. */
    fun positionAt(now: Long): Long {
        val moved = if (playing) positionMs + ((now - at) * speed).toLong() else positionMs
        return moved.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}

/**
 * What the screens show for KJB's own player: the queue playing from the model, the song from the library (else the tags
 * of the [opened] copy it is, by path), and the transport from the session's player while the screens are connected to it
 * ([transport] for that song), else the place saved ([place], or the queue's own). Null when nothing is loaded.
 */
fun playerUi(book: QueueBook, repeat: Repeat, place: PlaybackPlace?, transport: Transport, library: Library?, now: Long, opened: Map<String, Song> = emptyMap()): PlayerUi? {
    val queue = book.current ?: return null
    val placed = if (place != null && place.queueId == queue.id) withPlace(book, place).current ?: queue else queue
    val item = placed.current ?: return null
    val song = library?.resolver?.find(item.song)
    val copy = if (song == null) opened[item.song.path] else null
    val live = transport.connected && transport.uid == item.uid
    val length = if (live && transport.durationMs > 0) transport.durationMs else (song ?: copy)?.durationMs ?: 0
    return PlayerUi(
        book = book,
        queue = placed,
        item = item,
        song = song,
        playing = live && transport.playing,
        positionMs = if (live) transport.positionMs else placed.positionMs,
        at = if (live) transport.at else now,
        speed = if (live) transport.speed else 1f,
        durationMs = length,
        repeat = repeat,
        library = library,
        opened = copy,
    )
}

/**
 * [queue]'s name as the app shows it, the same in every list: the user's name, else its source and the time it started
 * ("Album · Harbour Glass · 21:30", "Songs · 21:30"), numbered when another of [book]'s queues reads the same ([queueNames]).
 */
fun queueLabel(context: Context, book: QueueBook, queue: Queue): String = queueLabels(context, book)[queue.id] ?: madeName(context, queue)

/** Every queue's name in [book], by id ([queueLabel]). */
fun queueLabels(context: Context, book: QueueBook): Map<String, String> = queueNames(book.queues) { madeName(context, it) }

/** An unnamed queue's name: its source and the time it started, in the phone's 12 or 24 hour clock. */
private fun madeName(context: Context, queue: Queue): String =
    context.getString(R.string.queue_name_made, sourceLabel(context, queue.source, queue.items.size), DateFormat.getTimeFormat(context).format(Date(queue.startedAt)))

/** A source as the app names it; a picked queue by its size ("Picked · 3 songs"), a friend's card by its sender ("From Wren"). */
fun sourceLabel(context: Context, source: QueueSource, songs: Int): String {
    val kind = when (source.kind) {
        SourceKind.SONGS -> return context.getString(R.string.queue_source_songs)
        SourceKind.FAVOURITES -> return context.getString(R.string.queue_source_favourites)
        SourceKind.MOST_PLAYED -> return context.getString(R.string.queue_source_most_played)
        SourceKind.RECENTLY_PLAYED -> return context.getString(R.string.queue_source_recently_played)
        SourceKind.RECENTLY_ADDED -> return context.getString(R.string.queue_source_recently_added)
        SourceKind.NOT_PLAYED -> return context.getString(R.string.queue_source_not_played)
        SourceKind.OPENED -> return context.getString(R.string.open_queue)
        SourceKind.SUGGESTIONS -> return source.name
        SourceKind.PICKED -> return context.getString(R.string.queue_source_picked, context.resources.getQuantityString(R.plurals.queue_songs, songs, songs))
        SourceKind.HUDDLE -> return context.getString(R.string.huddle_from, source.name)
        SourceKind.ALBUM -> R.string.queue_source_album
        SourceKind.ARTIST -> R.string.queue_source_artist
        SourceKind.FOLDER -> R.string.queue_source_folder
        SourceKind.GENRE -> R.string.queue_source_genre
        SourceKind.PLAYLIST -> R.string.queue_source_playlist
        SourceKind.SEARCH -> R.string.queue_source_search
        SourceKind.BOOK -> R.string.queue_source_book
    }
    return context.getString(R.string.queue_source_named, context.getString(kind), source.name)
}

/**
 * The screens' side of KJB's player, one per process ([PlayerGraph.connection]): [ui] for the player, the Now bar and
 * the queue pages; transport through a MediaController to [PlaybackService] (which starts the service, and keeps it in
 * the foreground while it plays); queue changes straight into the model ([PlayerCore]), which the service applies.
 * Implements the lists' [PlaybackCommands], the Add to sheet's queues included; a book's file started from a list or a
 * queue plays its book ([asBook], by the [repository]'s library and [books], `books.json`). Connected while a screen is
 * shown ([attach], [detach]), or on demand for a command. Main thread only.
 */
@OptIn(UnstableApi::class)
class PlayerConnection(
    private val context: Context,
    private val core: PlayerCore,
    private val repository: LibraryRepository,
    private val scope: CoroutineScope,
    /** `books.json`: where a book started from a list or a queue plays from, and an Opened file queue's place kept. */
    private val bookStore: BookStore,
) : PlaybackCommands {
    private val books: StateFlow<BookState> get() = bookStore.state
    private val transport = MutableStateFlow(Transport())
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private var users = 0

    /** A Resume waiting on a cache copy's lookup. */
    private var resuming: Job? = null
    /** The library, for the songs of queues that are not playing; null while it is read. */
    val library: StateFlow<Library?> = repository.state.map { (it as? LibraryState.Ready)?.library }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Every queue, for the queue pages. */
    val book: StateFlow<QueueBook> get() = core.book

    val ui: StateFlow<PlayerUi?> = combine(core.book, core.repeat, core.place, transport, combine(this.library, OpenedFiles.songs, ::Pair)) { book, repeat, place, transport, (songs, opened) ->
        playerUi(book, repeat, place, transport, songs, SystemClock.elapsedRealtime(), opened)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** A queue just moved to Recent. */
    val toRecent: SharedFlow<Unit> get() = core.toRecent

    /** Returns once the queues are read and nothing is loaded ([PlayerCore.awaitNothingLoaded]). */
    suspend fun awaitNothingLoaded() = core.awaitNothingLoaded()

    override val queues: StateFlow<List<QueueTarget>> = core.book.map { book ->
        val names = queueLabels(context, book)
        book.listed.map { queue -> QueueTarget(queue.id, names.getValue(queue.id), queue.items.size, playing = queue.id == book.currentId) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = read(player)
    }

    /** A screen that shows the player is visible: the controller connects (the first one), so the service runs. */
    fun attach() {
        users++
        connect()
    }

    /** That screen is gone: the last one lets the controller go; playback goes on in the service. */
    fun detach() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && waiting.isEmpty()) release()
    }

    // Transport

    fun playPause() {
        if (ui.value?.playing == true) send { it.pause() } else send { it.play() }
    }

    fun play() = send { it.play() }

    fun next() = send { it.seekToNext() }

    fun previous() = send { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = send { it.seekTo(positionMs.coerceAtLeast(0)) }

    /** A seek button's jump of [deltaMs] (back when negative), kept inside the song. */
    fun seekBy(deltaMs: Long) {
        val now = ui.value ?: return
        seekTo(seekTarget(now.positionAt(SystemClock.elapsedRealtime()), now.durationMs, deltaMs))
    }

    /** Repeat: off, all, one, and round again. */
    fun cycleRepeat(): Repeat {
        val next = Repeat.entries[(core.repeat.value.ordinal + 1) % Repeat.entries.size]
        core.setRepeat(next)
        return next
    }

    /** The Shuffle button on the queue playing: Off, Shuffle, Smart shuffle, and round again; the step it is on now. */
    fun cycleShuffle(): ShuffleStep {
        core.change { it.cycleShuffle(core.shuffleRandom()) }
        return core.book.value.current?.shuffleStep ?: ShuffleStep.OFF
    }

    /** Smart shuffle's Undo: plain Shuffle again. */
    fun plainShuffle() = core.change { it.plainShuffle() }

    // The lists' commands

    override fun play(songs: List<SongRef>, start: Int, source: QueueSource) {
        switchAsBook { it.play(songs, start, source, core.now()) }
        send { it.play() }
    }

    /**
     * A book's queue ([source], books/BookPlayback.kt) from part [start] at [positionMs]: "Continue" and a tap on a book;
     * and a friend's song from where they are in it (Huddle's Play along). The queue playing when it is that source's
     * already plays on where it is.
     */
    fun playFrom(songs: List<SongRef>, start: Int, positionMs: Long, source: QueueSource) {
        if (core.book.value.current?.from(source) != true) {
            core.switch { book -> book.play(songs, start, source, core.now()).let { it.copy(book = it.book.place(start, positionMs)) } }
        }
        send { it.play() }
    }

    override fun shuffle(songs: List<SongRef>, source: QueueSource) {
        core.switch(Move.START) { it.shuffle(songs, source, core.shuffleRandom(), core.now()) }
        send { it.play() }
    }

    override fun playNext(songs: List<SongRef>): Undo? = core.edit { book ->
        book.playNext(songs, picked(), core.now())?.let { edited ->
            val made = edited.before.isEmpty()
            val uids = edited.book.queue(edited.queueId)?.items?.map { it.uid }.orEmpty()
            edited.book to Undo { core.change { if (made) it.removeUids(edited.queueId, uids) else it.restore(edited.queueId, edited.before) } }
        }
    }

    override fun addToQueue(songs: List<SongRef>): Undo? = core.edit { book ->
        book.addToCurrent(songs, picked(), core.now())?.takeIf { it.added > 0 }?.let { added ->
            added.book to Undo { core.change { it.removeUids(added.queueId, added.uids) } }
        }
    }

    // The Add to sheet's queues

    override fun addToQueue(id: String, songs: List<SongRef>): QueueAdded? = core.edit { book ->
        book.addTo(id, songs)?.let { added ->
            val undo = if (added.added > 0) Undo { core.change { it.removeUids(added.queueId, added.uids) } } else null
            added.book to QueueAdded(added.added, added.already, undo)
        }
    }

    override fun newQueue(name: String, songs: List<SongRef>, source: QueueSource?): Undo? = core.edit { book ->
        val from = source ?: picked()
        // The source's own name as the name keeps the queue named after its source, as Rename can change later.
        val own = name.trim().takeUnless { it == sourceLabel(context, from, songs.distinctBy { song -> song.path }.size) }
        book.newQueue(own, songs, from, core.now())?.let { made ->
            val id = made.book.saved.first()
            made.book to Undo { core.change { it.delete(id) } }
        }
    }

    // The queue pages

    fun rename(queueId: String, name: String) = core.change { it.rename(queueId, name) }

    /** "Delete": only the queue goes, never the one playing; Undo puts it back where it was. Null when nothing went. */
    fun delete(queueId: String): Undo? = core.edit { book ->
        book.deleted(queueId)?.let { gone -> gone.book to Undo { core.change { it.undelete(gone) } } }
    }

    /**
     * "Resume": that queue plays at its place; an "Opened file" queue on a book's file as that book ([resumeIntoBook]), a
     * cache copy looked up off the main thread. A later Resume, or another start meanwhile, wins.
     */
    fun resume(queueId: String) {
        resuming?.cancel()
        resuming = scope.launch {
            if (core.resumeIntoBook(queueId, library(), bookStore) { path -> library()?.let { OpenedFiles.libraryCopyOf(context, path, it.all) } }) send { it.play() }
        }
    }

    fun playFromStart(queueId: String) {
        switchAsBook(Move.START) { it.playFromStart(queueId, core.now()) }
        send { it.play() }
    }

    /** A tap on a song of a queue's list: that queue plays from it (a book's file in another queue: its book). */
    fun jump(queueId: String, index: Int) {
        switchAsBook { it.jump(queueId, index, core.now()) }
        send { it.play() }
    }

    fun move(queueId: String, from: Int, to: Int) = core.change { it.move(queueId, from, to) }

    /** Swipe to remove, or "Remove from queue"; Undo puts the song back (not when the queue went with its last song). */
    fun remove(queueId: String, uid: Long): Undo? = core.edit { book ->
        book.remove(queueId, uid)?.let { removed ->
            removed.book to if (removed.queueGone) null else Undo { core.change { it.putBack(removed.queueId, removed.item, removed.at) } }
        }
    }

    /** "Save as playlist": a new playlist of the queue's songs, in its order; the playlist's name, or null when it could not be stored. */
    fun saveAsPlaylist(queueId: String, name: String, done: (String?) -> Unit) {
        val songs = core.book.value.queue(queueId)?.items?.map { it.song } ?: return done(null)
        scope.launch { done(LibraryGraph.playlists(context).create(name, songs)?.name) }
    }

    private fun picked(): QueueSource = pickedSource("")

    /**
     * [change], or the book's own queue when the song it starts is a book's file ([asBook]): a book always plays as a book.
     * The user's [move] is noted as [PlayerCore.switch] notes it.
     */
    private fun switchAsBook(move: Move = Move.PICK, change: (QueueBook) -> Switched) {
        core.switch(move) { book -> book.asBook(change(book), library(), books.value, core.now()) }
    }

    /** The library as read now, not as [library] last passed it on: a book started right after the read is known. */
    private fun library(): Library? = (repository.state.value as? LibraryState.Ready)?.library

    // The controller

    /** [action] on the controller, now or once it is connected. */
    private fun send(action: (MediaController) -> Unit) {
        controller?.let { return action(it) }
        waiting += action
        connect()
    }

    private fun connect() {
        if (controller != null || connecting != null) return
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val connected = try {
                future.get()
            } catch (e: ExecutionException) {
                Log.w(LOG_TAG, "player: no connection to the service (${e.cause?.javaClass?.simpleName})")
                waiting.clear()
                return@addListener
            }
            controller = connected
            connected.addListener(listener)
            read(connected)
            val actions = waiting.toList()
            waiting.clear()
            actions.forEach { it(connected) }
            if (users == 0) release()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun release() {
        controller?.let {
            it.removeListener(listener)
            it.release()
        }
        controller = null
        transport.value = Transport()
    }

    private fun read(player: Player) {
        transport.value = Transport(
            connected = true,
            playing = player.isPlaying,
            uid = player.currentMediaItem?.mediaId?.toLongOrNull(),
            positionMs = player.currentPosition,
            at = SystemClock.elapsedRealtime(),
            speed = player.playbackParameters.speed,
            durationMs = player.duration,
        )
    }
}

/** Where a seek button's jump of [deltaMs] lands from [positionMs]: never before the start, nor past the end of a known length. */
fun seekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val target = (positionMs + deltaMs).coerceAtLeast(0)
    return if (durationMs > 0) target.coerceAtMost(durationMs) else target
}
