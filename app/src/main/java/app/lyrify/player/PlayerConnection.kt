package app.lyrify.player

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
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
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryRepository
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
) {
    /** Where the song is at [now] (elapsed realtime), within its length. */
    fun positionAt(now: Long): Long {
        val moved = if (playing) positionMs + ((now - at) * speed).toLong() else positionMs
        return moved.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}

/**
 * What the screens show for KJB's own player: the queue playing from the model, the song from the library, and the
 * transport from the session's player while the screens are connected to it ([transport] for that song), else the place
 * saved ([place], or the queue's own). Null when nothing is loaded.
 */
fun playerUi(book: QueueBook, repeat: Repeat, place: PlaybackPlace?, transport: Transport, library: Library?, now: Long): PlayerUi? {
    val queue = book.current ?: return null
    val placed = if (place != null && place.queueId == queue.id) withPlace(book, place).current ?: queue else queue
    val item = placed.current ?: return null
    val song = library?.resolver?.find(item.song)
    val live = transport.connected && transport.uid == item.uid
    val length = if (live && transport.durationMs > 0) transport.durationMs else song?.durationMs ?: 0
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
    )
}

/** A queue's name as the app shows it: the user's name, else its source ("Album · Harbour Glass", "Songs"). */
fun queueLabel(context: Context, queue: Queue): String = queue.name ?: sourceLabel(context, queue.source, queue.items.size)

/** A source as the app names it; a picked queue by its size ("Picked · 3 songs"). */
fun sourceLabel(context: Context, source: QueueSource, songs: Int): String {
    val kind = when (source.kind) {
        SourceKind.SONGS -> return context.getString(R.string.queue_source_songs)
        SourceKind.FAVOURITES -> return context.getString(R.string.queue_source_favourites)
        SourceKind.MOST_PLAYED -> return context.getString(R.string.queue_source_most_played)
        SourceKind.RECENTLY_PLAYED -> return context.getString(R.string.queue_source_recently_played)
        SourceKind.RECENTLY_ADDED -> return context.getString(R.string.queue_source_recently_added)
        SourceKind.PICKED -> return context.getString(R.string.queue_source_picked, context.resources.getQuantityString(R.plurals.queue_songs, songs, songs))
        SourceKind.ALBUM -> R.string.queue_source_album
        SourceKind.ARTIST -> R.string.queue_source_artist
        SourceKind.FOLDER -> R.string.queue_source_folder
        SourceKind.GENRE -> R.string.queue_source_genre
        SourceKind.PLAYLIST -> R.string.queue_source_playlist
        SourceKind.SEARCH -> R.string.queue_source_search
    }
    return context.getString(R.string.queue_source_named, context.getString(kind), source.name)
}

/**
 * The screens' side of KJB's player, one per process ([PlayerGraph.connection]): [ui] for the player, the Now bar and
 * the queue pages; transport through a MediaController to [PlaybackService] (which starts the service, and keeps it in
 * the foreground while it plays); queue changes straight into the model ([PlayerCore]), which the service applies.
 * Implements the lists' [PlaybackCommands] and the Add to sheet's [QueueTargets]. Connected while a screen is shown
 * ([attach], [detach]), or on demand for a command. Main thread only.
 */
@OptIn(UnstableApi::class)
class PlayerConnection(
    private val context: Context,
    private val core: PlayerCore,
    library: LibraryRepository,
    private val scope: CoroutineScope,
) : PlaybackCommands, QueueTargets {
    private val transport = MutableStateFlow(Transport())
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private var users = 0
    private val libraryNow: StateFlow<Library?> = library.state.map { (it as? LibraryState.Ready)?.library }.stateIn(scope, SharingStarted.Eagerly, null)

    val ui: StateFlow<PlayerUi?> = combine(core.book, core.repeat, core.place, transport, libraryNow) { book, repeat, place, transport, songs ->
        playerUi(book, repeat, place, transport, songs, SystemClock.elapsedRealtime())
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** A queue just moved to Recent. */
    val toRecent: SharedFlow<Unit> get() = core.toRecent

    override val targets: StateFlow<List<QueueTarget>> = core.book.map { book ->
        (book.saved + book.recent).mapNotNull(book::queue).map { QueueTarget(it.id, it.source, it.name, it.items.size, saved = it.id in book.saved) }
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

    /** Shuffle on the queue playing; true when it is now on. */
    fun toggleShuffle(): Boolean {
        core.change { it.toggleShuffle(core.shuffleRandom()) }
        return core.book.value.current?.shuffled == true
    }

    // The lists' commands

    override fun play(songs: List<SongRef>, start: Int, source: QueueSource) {
        core.switch { it.play(songs, start, source, core.now()) }
        send { it.play() }
    }

    override fun shuffle(songs: List<SongRef>, source: QueueSource) {
        core.switch { it.shuffle(songs, source, core.shuffleRandom(), core.now()) }
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

    override fun addTo(queueId: String, songs: List<SongRef>): AddedTo? = core.edit { book ->
        book.addTo(queueId, songs)?.let { added ->
            val undo = if (added.added > 0) Undo { core.change { it.removeUids(added.queueId, added.uids) } } else null
            added.book to AddedTo(added.added, added.already, undo)
        }
    }

    override fun newQueue(name: String?, songs: List<SongRef>, source: QueueSource): Undo? = core.edit { book ->
        book.newQueue(name, songs, source, core.now())?.let { made ->
            val id = made.book.saved.first()
            made.book to Undo { core.change { it.delete(id) } }
        }
    }

    // The queue pages

    fun keep(queueId: String) = core.change { it.keep(queueId) }

    fun rename(queueId: String, name: String) = core.change { it.rename(queueId, name) }

    fun delete(queueId: String) = core.change { it.delete(queueId) }

    fun resume(queueId: String) {
        core.switch { it.resume(queueId, core.now()) }
        send { it.play() }
    }

    fun playFromStart(queueId: String) {
        core.switch { it.playFromStart(queueId, core.now()) }
        send { it.play() }
    }

    /** A tap on a song of a queue's list: that queue plays from it. */
    fun jump(queueId: String, index: Int) {
        core.switch { it.jump(queueId, index, core.now()) }
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

    private fun picked(): QueueSource = QueueSource(SourceKind.PICKED, "picked:${core.now()}", "")

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
