package app.lyrify.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** What the media session shows: the songs the player holds (by uid), the current one and its position, and the toggles. */
data class SessionView(val uids: List<Long>, val index: Int, val positionMs: Long, val playing: Boolean, val shuffled: Boolean, val repeat: Repeat)

/**
 * The session's view of KJB's player: the songs and place come from the player ([port]), Shuffle from the queue playing
 * (KJB shuffles the queue itself, never the player), Repeat from the app's setting. Nothing loaded never plays.
 */
fun sessionView(port: PlayerPort, playing: Boolean, book: QueueBook, repeat: Repeat): SessionView =
    if (port.loaded.isEmpty()) {
        SessionView(emptyList(), -1, 0, playing = false, shuffled = false, repeat = repeat)
    } else {
        SessionView(port.loaded, port.index, port.positionMs, playing, book.current?.shuffled == true, repeat)
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
    init {
        engine.addListener(object : EngineListener {
            override fun onChanged() = invalidateState()
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
        val index = if (mediaItemIndex == C.INDEX_UNSET) engine.index else mediaItemIndex
        beforeSeek()
        engine.seek(index, if (positionMs == C.TIME_UNSET) 0 else positionMs)
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

    /** A controller's move: the song moves in the queue playing. */
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val uid = engine.loaded.getOrNull(fromIndex)
        val target = engine.loaded.getOrNull(newIndex)
        if (toIndex - fromIndex == 1 && uid != null && target != null) {
            core.change { book ->
                val queue = book.current ?: return@change book
                book.move(queue.id, queue.items.indexOfFirst { it.uid == uid }, queue.items.indexOfFirst { it.uid == target })
            }
        }
        return Futures.immediateVoidFuture()
    }

    /** Adding songs from outside the app is not offered: nothing changes. */
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun commands(view: SessionView): Player.Commands {
        val loaded = view.uids.isNotEmpty()
        val next = loaded && (view.index < view.uids.lastIndex || view.repeat != Repeat.OFF)
        return Player.Commands.Builder()
            .addAll(
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
            .addIf(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, loaded)
            .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, loaded)
            .addIf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, loaded)
            .addIf(Player.COMMAND_SEEK_BACK, loaded)
            .addIf(Player.COMMAND_SEEK_FORWARD, loaded)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, loaded)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, loaded)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, next)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, next)
            .build()
    }

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
