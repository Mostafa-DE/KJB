package app.lyrify.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import app.lyrify.audio.FadeSlot
import app.lyrify.auto.CoverUris
import app.lyrify.auto.coverUri
import app.lyrify.audio.LiveEq
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import java.io.File

/** What an engine tells the service: a song starting (a new one, or again for Repeat one), seeks, play and pause, the end, errors. */
interface EngineListener {
    /** Anything the session shows changed. */
    fun onChanged() {}

    /**
     * The song [uid] started: after the one before, after a jump, or again ([lap]: Repeat one). [natural] when the song
     * before played to its end (the next one, or a lap of Repeat one), not when the user moved.
     */
    fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {}

    /** The user jumped inside the song playing, from [fromMs] to [toMs]. */
    fun onSeeked(fromMs: Long, toMs: Long) {}

    fun onPlayingChanged(playing: Boolean) {}

    /** The last song ended with Repeat off. */
    fun onEnded() {}

    /**
     * The song could not be played (a file gone or unreadable); [next] is the song the player would play after it for
     * its repeat mode, C.INDEX_UNSET when none follows.
     */
    fun onError(error: PlaybackException, next: Int) {}
}

/**
 * The player behind [LyrifyPlayer]: what the facade shows and the bridge changes. P1 has one, [GaplessEngine] (one
 * ExoPlayer holding the queue); A2 adds a crossfade engine (two ExoPlayers) behind the same facade, so the session's
 * player never changes. Main thread only.
 */
interface PlaybackEngine : PlayerPort {
    /** The media items it holds, in order; each one's media id is its queue item's uid. */
    val items: List<MediaItem>
    val playWhenReady: Boolean
    val playbackState: Int
    val isPlaying: Boolean
    val isLoading: Boolean
    val suppression: Int
    val error: PlaybackException?
    val audioSessionId: Int
    val bufferedMs: Long

    /** The current song's length as decoded; C.TIME_UNSET until known. */
    val durationMs: Long

    /** Speed and pitch (P2's Speed sheet). */
    val playbackParameters: PlaybackParameters

    /** 0 to 1: the sleep timer's fade (P2). */
    var volume: Float

    fun setPlaybackParameters(parameters: PlaybackParameters)

    fun setPlayWhenReady(play: Boolean)

    fun prepare()

    fun stop()

    fun release()

    fun setRepeat(repeat: Repeat)

    /** Advanced "Audio offload", once allowed ([offloadAllowed]). */
    fun setOffload(allowed: Boolean)

    /** Advanced "Gapless playback". */
    fun setGapless(on: Boolean)

    fun addListener(listener: EngineListener)
}

/**
 * One ExoPlayer holding the queue playing: media audio attributes with audio focus (it pauses when another app takes
 * focus and comes back after a short loss), pause on becoming noisy (headphones out), a local wake lock, and gapless
 * playback as ExoPlayer does it (MP3 with LAME or iTunes gapless info, FLAC, M4A with an edit list). Its audio sink runs
 * the audio chain ([AudioChain]) with float output off, so the chain always runs. The crossfade engine (A2) holds one,
 * or two around a fade, each with its own chain.
 */
@OptIn(UnstableApi::class)
class GaplessEngine(context: Context, settings: () -> Settings, levels: SongLevels, private val itemOf: (Projected) -> MediaItem) : FadeSlot {
    private val extractors = DefaultExtractorsFactory()
    private val listeners = mutableListOf<EngineListener>()

    private val player: ExoPlayer = ExoPlayer.Builder(context, ChainRenderers(context, settings, levels), DefaultMediaSourceFactory(context, extractors))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setName("KJB")
        .build()

    init {
        // Media3 makes the session on a background thread and reports 0 until then; KJB's own, now, so the phone's sound
        // effects panel opens for this player from the start.
        player.setAudioSessionId(Util.generateAudioSessionId(context))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // The volume alone (the sleep timer's fade, a crossfade's gains) is nothing the session shows; these
                // events come a moment later, so passing them on would make a fade set its gains again at once.
                if (events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return
                listeners.forEach { it.onChanged() }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uid = mediaItem?.mediaId?.toLongOrNull() ?: return
                val lap = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val natural = lap || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                listeners.forEach { it.onSongStarted(uid, lap, natural) }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                    listeners.forEach { it.onSeeked(oldPosition.positionMs, newPosition.positionMs) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listeners.forEach { it.onPlayingChanged(isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) listeners.forEach { it.onEnded() }
            }

            override fun onPlayerError(error: PlaybackException) {
                val next = player.nextMediaItemIndex
                listeners.forEach { it.onError(error, next) }
            }
        })
    }

    override val loaded: List<Long> get() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId.toLongOrNull() ?: -1 }
    override val index: Int get() = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex
    override val positionMs: Long get() = player.currentPosition
    override val items: List<MediaItem> get() = (0 until player.mediaItemCount).map(player::getMediaItemAt)
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val playbackState: Int get() = player.playbackState
    override val isPlaying: Boolean get() = player.isPlaying
    override val isLoading: Boolean get() = player.isLoading
    override val suppression: Int get() = player.playbackSuppressionReason
    override val error: PlaybackException? get() = player.playerError
    override val audioSessionId: Int get() = player.audioSessionId
    override val bufferedMs: Long get() = player.bufferedPosition
    override val durationMs: Long get() = player.duration
    override val playbackParameters: PlaybackParameters get() = player.playbackParameters
    override var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value
        }

    override fun load(items: List<Projected>, index: Int, positionMs: Long) {
        if (items.isEmpty() || index < 0) {
            player.clearMediaItems()
            return
        }
        loadItems(items.map(itemOf), index, positionMs)
    }

    override fun loadItems(items: List<MediaItem>, index: Int, positionMs: Long) {
        player.setMediaItems(items, index, positionMs)
        player.prepare()
    }

    override fun apply(edit: Edit) {
        when (edit) {
            is Edit.Remove -> player.removeMediaItems(edit.at, edit.at + edit.count)
            is Edit.Insert -> player.addMediaItems(edit.at, edit.items.map(itemOf))
            is Edit.Move -> player.moveMediaItem(edit.from, edit.to)
            is Edit.Replace -> {
                // The song playing goes on from where it was, read from its new path.
                val current = edit.at == player.currentMediaItemIndex
                val position = player.currentPosition
                player.replaceMediaItem(edit.at, itemOf(edit.item))
                if (current) player.seekTo(edit.at, position)
            }
            // The same files with new details (a cover Covers & tags changed): ExoPlayer takes them in place, as their
            // uris are the same (canUpdateMediaItem), so the song playing goes on undisturbed.
            is Edit.Refresh -> player.replaceMediaItems(edit.at, edit.at + edit.items.size, edit.items.map(itemOf))
        }
    }

    override fun seek(index: Int, positionMs: Long) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, positionMs)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    override fun setPlaybackParameters(parameters: PlaybackParameters) {
        player.playbackParameters = parameters
    }

    override fun setPlayWhenReady(play: Boolean) {
        if (play && player.playbackState == Player.STATE_IDLE) player.prepare()
        player.playWhenReady = play
    }

    override fun prepare() = player.prepare()

    override fun stop() = player.stop()

    override fun release() {
        listeners.clear()
        player.release()
    }

    override fun setRepeat(repeat: Repeat) {
        player.repeatMode = when (repeat) {
            Repeat.OFF -> Player.REPEAT_MODE_OFF
            Repeat.ALL -> Player.REPEAT_MODE_ALL
            Repeat.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun setOffload(allowed: Boolean) {
        val mode = if (allowed) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        if (player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode == mode) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(AudioOffloadPreferences.Builder().setAudioOffloadMode(mode).setIsGaplessSupportRequired(true).build())
            .build()
    }

    /**
     * Off: M4A edit lists are ignored, so their encoder delay and padding play as silence. Media3 1.11 has no switch for
     * MP3's LAME or iTunes gapless info (only one that drops every ID3 tag, ReplayGain's too), so MP3 stays gapless.
     * Songs loaded after the change follow it.
     */
    override fun setGapless(on: Boolean) {
        extractors.setMp4ExtractorFlags(if (on) 0 else Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
    }

    override fun setPauseAtEndOfItems(pause: Boolean) {
        player.pauseAtEndOfMediaItems = pause
    }

    override fun setFocusHandled(handled: Boolean) = player.setAudioAttributes(player.audioAttributes, handled)

    override fun setAudioSessionId(id: Int) {
        player.audioSessionId = id
    }

    override fun addListener(listener: EngineListener) {
        listeners += listener
    }

    /**
     * The renderers, with an audio sink that runs the audio chain ([AudioChain]) and an audio renderer that hands the
     * chain each song's ReplayGain tags as it takes the song on.
     */
    private class ChainRenderers(context: Context, private val settings: () -> Settings, private val levels: SongLevels) : DefaultRenderersFactory(context) {
        private val streams = StreamTags()

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(AudioChain.processors(settings, levels, streams, LiveEq.of(context).sound::value).toTypedArray())
                // Float output stays off: with it, Media3 bypasses the processors.
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()

        /** Media3's own audio renderer (no extension decoders are bundled), telling [streams] each song's tags. */
        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>,
        ) {
            out += TaggingAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink, streams)
        }
    }

    /** The audio renderer: as it takes on a song's stream, ahead of the chain playing it, the track's tags go to [streams]. */
    private class TaggingAudioRenderer(
        context: Context,
        codecAdapterFactory: MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        audioSink: AudioSink,
        private val streams: StreamTags,
    ) : MediaCodecAudioRenderer(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink) {
        override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
            super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
            streams.record(mediaPeriodId.periodUid, formats.firstOrNull()?.metadata)
        }
    }
}

/**
 * [projected] as a media item: its uid as the media id (the session, the bridge and the browse tree know it by that), the
 * file, and what the notification, the lock screen, Bluetooth and the car show: the title as every surface shows it
 * ([displayTitle]), the artist, the album, the length, and the cover at its uri from [covers].
 */
@OptIn(UnstableApi::class)
fun mediaItemOf(projected: Projected, settings: LibrarySettings, covers: CoverUris): MediaItem {
    val song = projected.song
    return MediaItem.Builder()
        .setMediaId(projected.uid.toString())
        .setUri(Uri.fromFile(File(song.path)))
        .setMediaMetadata(metadataOf(song, settings, covers).buildUpon().setIsBrowsable(false).setIsPlayable(true).build())
        .build()
}

/**
 * What a song shows on the session and in the browse tree. Its cover is a uri of KJB's cover provider ([covers]): by
 * MediaStore id and signed, never a path, and KJB's default cover for a song without one, so Android Auto (which cannot
 * read MediaStore's audio uris) and the notification always have one; a cover Covers & tags changed is a new uri
 * ([coverUri]). Never artwork bytes.
 */
@OptIn(UnstableApi::class)
fun metadataOf(song: Song, settings: LibrarySettings, covers: CoverUris): MediaMetadata = MediaMetadata.Builder()
    .setTitle(displayTitle(song, settings))
    .setArtist(song.artist)
    .setAlbumTitle(song.album)
    .setAlbumArtist(song.albumArtist)
    .setTrackNumber(song.track)
    .setDiscNumber(song.disc)
    .setReleaseYear(song.year)
    .setGenre(song.genre)
    .setDurationMs(song.durationMs.takeIf { it > 0 })
    .setArtworkUri(coverUri(covers, song))
    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
    .build()
