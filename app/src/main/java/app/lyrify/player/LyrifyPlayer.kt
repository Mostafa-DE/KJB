package app.lyrify.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import app.lyrify.books.inBook
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * What the media session shows: the songs the player holds (by uid), the current one and its position, and the toggles;
 * [book] while a book's queue plays (book mode, books/BookPlayback.kt).
 */
data class SessionView(val uids: List<Long>, val index: Int, val positionMs: Long, val playing: Boolean, val shuffled: Boolean, val repeat: Repeat, val book: Boolean = false)

/**
 * The session's view of KJB's player: the songs and place come from the player ([port]), Shuffle from the queue playing
 * (KJB shuffles the queue itself, never the player), Repeat from the app's setting. Nothing loaded never plays.
 */
fun sessionView(port: PlayerPort, playing: Boolean, book: QueueBook, repeat: Repeat): SessionView =
    if (port.loaded.isEmpty()) {
        SessionView(emptyList(), -1, 0, playing = false, shuffled = false, repeat = repeat)
    } else {
        // A book never shuffles or repeats (books/BookPlayback.kt): the session says so whatever the queue and Repeat hold.
        inBook(book.current).let { inBook ->
            SessionView(port.loaded, port.index, port.positionMs, playing, !inBook && book.current?.shuffled == true, if (inBook) Repeat.OFF else repeat, book = inBook)
        }
    }

/**
 * A controller's move (Auto's or Bluetooth's queue view): the songs the player holds ([loaded]) from [fromIndex] until
 * [toIndex] move so the first of them is at [newIndex], as `Player.moveMediaItems` does. In the queue playing each one
 * moves in turn to where the song at its new place is, so songs not on the phone stay near where they were. The song
 * playing stays current.
 */
fun controllerMove(book: QueueBook, loaded: List<Long>, fromIndex: Int, toIndex: Int, newIndex: Int): QueueBook {
    val queueId = book.currentId ?: return book
    val count = toIndex - fromIndex
    if (fromIndex < 0 || count <= 0 || toIndex > loaded.size || newIndex !in 0..loaded.size - count || newIndex == fromIndex) return book
    val order = loaded.toMutableList()
    var moved = book
    repeat(count) { k ->
        val from = if (newIndex < fromIndex) fromIndex + k else fromIndex
        val to = if (newIndex < fromIndex) newIndex + k else newIndex + count - 1
        val uid = order[from]
        val target = order[to]
        order.add(to, order.removeAt(from))
        val items = moved.queue(queueId)?.items ?: return moved
        moved = moved.move(queueId, items.indexOfFirst { it.uid == uid }, items.indexOfFirst { it.uid == target })
    }
    return moved
}

/**
 * What the session tells its controllers when the engine says a song started: a song that started by itself (the next
 * one, or Repeat one's lap) is an automatic transition, so a controller's place (the screens, the notification, the car)
 * goes to where the song is now; null for a move by the user, which its seek tells already.
 */
fun startedDiscontinuity(lap: Boolean, natural: Boolean): Int? = if (natural || lap) Player.DISCONTINUITY_REASON_AUTO_TRANSITION else null

/**
 * Where a seek goes, and whether the facade tells controllers of it itself ([tell]): Media3 tells of the seeks it planned
 * (an index it found), not of one that came without an index, the same song again included (Next in a queue of one).
 */
data class SessionSeek(val index: Int, val tell: Boolean)

/**
 * The seek for [mediaItemIndex] and [seekCommand], the song playing being [current]. Next with no song after it (the
 * last song with Repeat off, or with Repeat one, which Media3 reads as off for Next) comes without an index
 * ([C.INDEX_UNSET]) and goes to the first song of the queue, playing on if it played; any other seek without an index
 * stays in the song playing. A queue playing to its end by itself still stops there (PlaybackService's onEnded).
 */
fun sessionSeek(mediaItemIndex: Int, seekCommand: Int, current: Int): SessionSeek {
    val index = when {
        mediaItemIndex != C.INDEX_UNSET -> mediaItemIndex
        seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> 0
        else -> current
    }
    return SessionSeek(index, tell = mediaItemIndex == C.INDEX_UNSET)
}

/**
 * The player commands the session offers for [view] (Player.COMMAND_*): the seeks once a song is loaded, Next on the
 * last song too ([sessionSeek]), so the app, the notification, a headset, the widget and the car all go round to the first.
 * In a book, 30 s back and forward take the place of Previous and Next, and a book never shuffles or repeats.
 */
fun sessionCommands(view: SessionView): List<Int> {
    val offered = ALWAYS_OFFERED + if (view.uids.isNotEmpty()) SEEKS_OFFERED else emptyList()
    return if (view.book) offered - NOT_IN_A_BOOK else offered
}

/** What a book's session leaves out ([sessionCommands]). */
private val NOT_IN_A_BOOK: Set<Int> = setOf(
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    Player.COMMAND_SET_SHUFFLE_MODE,
    Player.COMMAND_SET_REPEAT_MODE,
)

private val ALWAYS_OFFERED: List<Int> = listOf(
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_PREPARE,
    Player.COMMAND_STOP,
    Player.COMMAND_SET_SHUFFLE_MODE,
    Player.COMMAND_SET_REPEAT_MODE,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
    Player.COMMAND_SET_MEDIA_ITEM,
    Player.COMMAND_CHANGE_MEDIA_ITEMS,
    Player.COMMAND_RELEASE,
)

private val SEEKS_OFFERED: List<Int> = listOf(
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_BACK,
    Player.COMMAND_SEEK_FORWARD,
    Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
)

/**
 * The user's move a controller's seek ([seekCommand]) to another song is: Next, Previous, or a song picked (the car's
 * queue); null for a seek inside the song.
 */
fun moveOf(seekCommand: Int): Move? = when (seekCommand) {
    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> Move.NEXT
    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Move.BACK
    Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> Move.PICK
    else -> null
}

/** The jumps of the seek buttons ("Skip amounts"), for the session's seek back and forward. */
data class SkipAmounts(val backMs: Long, val forwardMs: Long)

/**
 * The player the media session sees: a facade KJB owns over a [PlaybackEngine], so the notification, the lock screen,
 * Bluetooth, the widget and Auto all read one player that never changes identity, whichever engine plays (A2 adds a
 * crossfade engine behind it). Transport goes to the engine; Shuffle and Repeat, and the queue edits a controller asks
 * for, go to the queue model ([core]), which the bridge applies to the engine ([sync] applies it at once). Audio focus
 * is the engine's own (ExoPlayer's) in P1; a two-player engine owns one focus for both. Main thread.
 */
@OptIn(UnstableApi::class)
class LyrifyPlayer(
    looper: Looper,
    private val engine: PlaybackEngine,
    private val core: PlayerCore,
    private val skips: () -> SkipAmounts,
    private val sync: () -> Unit,
    /** Before a controller moves the player (Next, Previous, a seek): the play counter hears where it was. */
    private val beforeSeek: () -> Unit = {},
) : SimpleBasePlayer(looper) {
    /**
     * A jump the engine made that the session's own state cannot see, told to the controllers with the next state: the
     * state's position is read live from the engine, so a song starting again (Repeat one, Next in a queue of one), a
     * jump inside the song the engine makes itself (Repeat a part, a kept place) or a wrap to the first song looks to
     * the session like no move at all.
     */
    private var discontinuity: Int? = null

    /** A controller's seek is going on: Media3 tells of it itself. */
    private var seeking = false

    init {
        engine.addListener(object : EngineListener {
            override fun onChanged() = invalidateState()

            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
                discontinuity = startedDiscontinuity(lap, natural) ?: return
                invalidateState()
            }

            override fun onSeeked(fromMs: Long, toMs: Long) {
                if (seeking) return
                discontinuity = Player.DISCONTINUITY_REASON_SEEK
                invalidateState()
            }
        })
    }

    /** The queue model or the settings changed: the session reads the state again. */
    fun changed() = invalidateState()

    override fun getState(): State {
        val view = sessionView(engine, engine.isPlaying, core.book.value, core.repeat.value)
        val items = engine.items
        val skips = skips()
        val state = State.Builder()
            .setAvailableCommands(commands(view))
            .setPlayWhenReady(engine.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(engine.suppression)
            .setPlayerError(engine.error)
            .setRepeatMode(repeatMode(view.repeat))
            .setShuffleModeEnabled(view.shuffled)
            .setIsLoading(engine.isLoading)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build())
            .setAudioSessionId(engine.audioSessionId)
            .setPlaybackParameters(engine.playbackParameters)
            .setSeekBackIncrementMs(skips.backMs)
            .setSeekForwardIncrementMs(skips.forwardMs)
            .setMaxSeekToPreviousPositionMs(MAX_SEEK_TO_PREVIOUS_MS)
        if (items.isEmpty() || view.index < 0) return state.setPlaybackState(Player.STATE_IDLE).build()
        discontinuity?.let { reason -> state.setPositionDiscontinuity(reason, engine.positionMs) }
        discontinuity = null
        val decoded = engine.durationMs
        val playlist = items.mapIndexed { i, item ->
            val length = if (i == view.index && decoded != C.TIME_UNSET) decoded else item.mediaMetadata.durationMs ?: C.TIME_UNSET
            MediaItemData.Builder(item.mediaId)
                .setMediaItem(item)
                .setDurationUs(if (length == C.TIME_UNSET) C.TIME_UNSET else length * 1_000)
                .setIsSeekable(true)
                .build()
        }
        return state
            .setPlaybackState(engine.playbackState)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(view.index)
            .setContentPositionMs { engine.positionMs }
            .setContentBufferedPositionMs { engine.bufferedMs }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        engine.setPlayWhenReady(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        engine.prepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        return Futures.immediateVoidFuture()
    }

    /** The service releases the engine with the session. */
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val seek = sessionSeek(mediaItemIndex, seekCommand, engine.index)
        // Next from the last song (to the first, or the same song again in a queue of one): a jump Media3 did not plan.
        if (seek.tell) discontinuity = Player.DISCONTINUITY_REASON_SEEK
        beforeSeek()
        // A move to another song: the play counter hears how the song before ended (Next, Previous, a pick).
        if (seek.index != engine.index) moveOf(seekCommand)?.let { move -> engine.loaded.getOrNull(seek.index)?.let { core.moved(it, move) } }
        seeking = true
        try {
            engine.seek(seek.index, if (positionMs == C.TIME_UNSET) 0 else positionMs)
        } finally {
            seeking = false
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        core.setRepeat(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> Repeat.ONE
                Player.REPEAT_MODE_ALL -> Repeat.ALL
                else -> Repeat.OFF
            },
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (core.book.value.current?.shuffled != shuffleModeEnabled) core.change { it.toggleShuffle(core.shuffleRandom()) }
        return Futures.immediateVoidFuture()
    }

    /**
     * Items a controller sets: playback resumption and the browse tree hand over the queue playing (the session's
     * callback put it in the model first), so the engine only moves to [startIndex]. Anything else changes nothing.
     */
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        sync()
        val uids = mediaItems.map { it.mediaId.toLongOrNull() }
        if (uids.isNotEmpty() && uids == engine.loaded) {
            val index = if (startIndex == C.INDEX_UNSET) engine.index else startIndex
            val position = if (startPositionMs == C.TIME_UNSET) engine.positionMs.takeIf { index == engine.index } ?: 0 else startPositionMs
            engine.seek(index, position)
        }
        return Futures.immediateVoidFuture()
    }

    /** A controller's remove (Auto's queue view, say): those songs leave the queue playing. */
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val uids = engine.loaded.subList(fromIndex.coerceAtLeast(0), toIndex.coerceAtMost(engine.loaded.size)).toSet()
        core.change { book -> book.currentId?.let { book.removeUids(it, uids) } ?: book }
        return Futures.immediateVoidFuture()
    }

    /** A controller's move: the songs move in the queue playing. */
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val loaded = engine.loaded
        core.change { book -> controllerMove(book, loaded, fromIndex, toIndex, newIndex) }
        return Futures.immediateVoidFuture()
    }

    /** Adding songs from outside the app is not offered: nothing changes. */
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun commands(view: SessionView): Player.Commands = Player.Commands.Builder().apply { sessionCommands(view).forEach(::add) }.build()

    private fun repeatMode(repeat: Repeat): Int = when (repeat) {
        Repeat.OFF -> Player.REPEAT_MODE_OFF
        Repeat.ALL -> Player.REPEAT_MODE_ALL
        Repeat.ONE -> Player.REPEAT_MODE_ONE
    }

    private companion object {
        /** Previous restarts the song once it has played this long, as the player's own Previous does. */
        const val MAX_SEEK_TO_PREVIOUS_MS: Long = 3_000
    }
}
