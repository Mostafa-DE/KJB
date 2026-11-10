package app.lyrify.player

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Playlist
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.logFailures
import app.lyrify.media.NowPlaying
import app.lyrify.media.QueueSweep
import app.lyrify.settings.Settings
import app.lyrify.ui.LockLyricsActivity
import app.lyrify.ui.LockLyricsPart
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * A feature that lives in the playback service: P2's sleep timer and headset buttons, A1's output routes, A3's widget,
 * A4's car. Each in its own file, registered by one line in [PlaybackService.parts]; started with the session, stopped
 * with it, and offered the session commands it declares.
 */
interface ServicePart {
    /** The session commands this part answers ([onCommand]); controllers are offered them. */
    val commands: List<SessionCommand> get() = emptyList()

    fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession)

    fun stop() {}

    /** A command this part declared; null when it is not this part's. */
    fun onCommand(command: SessionCommand, args: Bundle): SessionResult? = null
}

/**
 * KJB's own player, as a media library service: the session (notification, lock screen, Bluetooth, headset buttons,
 * Assistant), the facade player over the engine, and the browse tree. A thin host: the queue model lives in
 * [PlayerCore], applied to the engine by [QueueBridge]; the engine's songs and plays come back into the model and the
 * play counter; the place is saved every 10 s while playing and on every pause; KJB's own queue gets lyrics through a
 * second [QueueSweep] on the one pipeline. Features plug in as [ServicePart]s. The log gets counts and exception names.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    /** The service's parts: P2, A1, A3 and A4 each add one line here, in merge order. */
    private val parts: List<ServicePart> = listOf(LockLyricsPart())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
    private var session: MediaLibrarySession? = null
    private lateinit var core: PlayerCore
    private lateinit var engine: GaplessEngine
    private lateinit var player: LyrifyPlayer
    private lateinit var bridge: QueueBridge
    private lateinit var settings: StateFlow<Settings>
    private lateinit var library: StateFlow<Library?>
    private lateinit var playlists: StateFlow<List<Playlist>>
    private val playing = MutableStateFlow(false)
    private val tracker = PlayTracker({ event -> Lyrify.playEvents(this).onEvent(event) }, System::currentTimeMillis)

    override fun onCreate() {
        super.onCreate()
        core = PlayerGraph.core(this)
        settings = Lyrify.settings(this).settings.stateIn(scope, SharingStarted.Eagerly, Settings())
        library = LibraryGraph.repository(this).state.map { (it as? LibraryState.Ready)?.library }.stateIn(scope, SharingStarted.Eagerly, null)
        playlists = LibraryGraph.playlists(this).state.map { it.playlists }.stateIn(scope, SharingStarted.Eagerly, emptyList())
        engine = GaplessEngine(this, settings::value, Levels(), ::mediaItem)
        bridge = QueueBridge(engine)
        player = LyrifyPlayer(mainLooper, engine, core, skips = ::skips, sync = ::sync, beforeSeek = ::heardSoFar)
        engine.addListener(Follower())
        val built = MediaLibrarySession.Builder(this, player, Callback())
            // A tap on the notification: the lyrics over the lock screen, or the app once unlocked (P3).
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, LockLyricsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setBitmapLoader(ThumbnailLoader(this))
            .build()
        session = built
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).setChannelName(R.string.player_channel).build().apply { setSmallIcon(R.drawable.ic_stat_kjb) },
        )

        // The queue model, the library and the settings, applied to the engine; the session reads them again.
        scope.launch {
            var queueId: String? = null
            var jumps = -1L
            combine(core.ready.filter { it }, core.book, library) { _, book, songs -> book to songs }.collect { (book, songs) ->
                // Another queue or a jump moves the player: the play counter hears where the song playing was.
                if (book.currentId != queueId || book.jumps != jumps) heardSoFar()
                queueId = book.currentId
                jumps = book.jumps
                if (songs != null) bridge.sync(book, ::find)
                player.changed()
            }
        }
        scope.launch { core.repeat.collect { engine.setRepeat(it); player.changed() } }
        scope.launch {
            settings.collect {
                engine.setOffload(offloadAllowed(it.advanced, AudioChain.processing(it)))
                engine.setGapless(it.advanced.gaplessPlayback)
                player.changed()
            }
        }
        scope.launch { savePlaces(playing) { savePlace() } }
        scope.launch { repeatWhilePlaying(playing, POSITION_EVERY_MS) { tracker.position(engine.positionMs, playing = true) } }
        ownLyrics()
        parts.forEach { it.start(this, built) }
        Log.d(LOG_TAG, "player service started")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        savePlace()
        heardSoFar()
        tracker.end()
        parts.forEach { it.stop() }
        session?.release()
        session = null
        engine.release()
        player.release()
        scope.cancel()
        io.cancel()
        super.onDestroy()
    }

    private fun find(ref: SongRef): Song? = library.value?.resolver?.find(ref)

    private fun skips(): SkipAmounts = settings.value.playback.let { SkipAmounts(it.skipBackSeconds * 1_000L, it.skipForwardSeconds * 1_000L) }

    private fun mediaItem(projected: Projected): MediaItem = mediaItemOf(projected, settings.value.library)

    /** The bridge applied now, before a controller's items are matched against the engine's. */
    private fun sync() {
        if (core.ready.value) library.value?.let { bridge.sync(core.book.value, ::find) }
    }

    /** Where the queue playing is, into `playback.json`. */
    private fun savePlace() {
        if (!::engine.isInitialized) return
        val uid = engine.loaded.getOrNull(engine.index) ?: return
        val queue = core.book.value.current ?: return
        val index = queue.items.indexOfFirst { it.uid == uid }
        if (index >= 0) core.savePlace(index, engine.positionMs)
    }

    /** Where the song playing is now, for the play counter. */
    private fun heardSoFar() {
        if (::engine.isInitialized && engine.loaded.isNotEmpty()) tracker.position(engine.positionMs, engine.isPlaying)
    }

    /** KJB's own queue gets lyrics without notification access: a second sweep on the app's one pipeline. */
    private fun ownLyrics() {
        val pipeline = Lyrify.pipeline(this)
        val sweep = QueueSweep(
            files = { track -> ownFiles(track) { path -> find(SongRef(path)) } },
            process = pipeline::process,
            // The listener's sweep retries failed lookups and settles interrupted writes while it runs; without it, this one does.
            dueErrors = { if (NowPlaying.connected.value) emptyList() else pipeline.dueErrors() + pipeline.staleLookups() },
            settleInterrupted = { if (!NowPlaying.connected.value && Environment.isExternalStorageManager()) pipeline.finishInterrupted() },
            heldByAi = pipeline.aiPaths::contains,
            log = { Log.d(LOG_TAG, "own sweep: 1 diagnostic event") },
        )
        io.launch { sweep.run() }
        var warned = false
        scope.launch {
            combine(core.book, library) { book, songs -> if (songs == null) null else ownSnapshot(book.current, ::find) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot == null) return@collect
                    if (Environment.isExternalStorageManager()) {
                        sweep.submit(snapshot)
                    } else if (!warned) {
                        warned = true
                        Log.w(LOG_TAG, "All files access is off: no lookups for own songs")
                    }
                }
        }
    }

    /** The engine's songs and plays, back into the queue model and the play counter. */
    private inner class Follower : EngineListener {
        override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
            val book = core.book.value
            val item = book.current?.items?.firstOrNull { it.uid == uid } ?: return
            if (!lap) core.change { it.at(uid, core.now()) }
            tracker.start(item.song, find(item.song)?.durationMs ?: C.TIME_UNSET, engine.positionMs, engine.isPlaying, naturalEnd = natural)
        }

        override fun onPlayingChanged(playing: Boolean) {
            PlayerGraph.audioSessionId = engine.audioSessionId
            tracker.position(engine.positionMs, playing)
            this@PlaybackService.playing.value = playing
        }

        override fun onSeeked(fromMs: Long, toMs: Long) = tracker.seeked(fromMs, toMs)

        /** The queue ended with Repeat off: KJB waits at its first song, paused. */
        override fun onEnded() {
            // An emptied player (the queue's last song removed) also ends: that play did not reach its end.
            if (engine.loaded.isEmpty()) return tracker.end()
            tracker.end(naturalEnd = true)
            engine.setPlayWhenReady(false)
            engine.seek(0, 0)
        }

        /** A song that cannot be played is skipped quietly; the last one stops. */
        override fun onError(error: PlaybackException, hasNext: Boolean) {
            Log.w(LOG_TAG, "player: a song could not be played (${error.errorCodeName}), ${if (hasNext) "skipped" else "stopped"}")
            if (hasNext) engine.seek(engine.index + 1, 0) else engine.setPlayWhenReady(false)
        }
    }

    /** What the audio chain asks about the stream playing (A1). */
    private inner class Levels : SongLevels {
        override fun song(mediaId: String): SongRef? = item(mediaId)?.song

        override fun albumInOrder(mediaId: String): Boolean {
            val queue = core.book.value.current ?: return false
            val uid = mediaId.toLongOrNull() ?: return false
            return albumInOrder(queue, queue.items.indexOfFirst { it.uid == uid })
        }

        private fun item(mediaId: String): QueueItem? = mediaId.toLongOrNull()?.let { uid -> core.book.value.current?.items?.firstOrNull { it.uid == uid } }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val own = parts.flatMap { it.commands }
            if (!base.isAccepted || own.isEmpty()) return base
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().apply { own.forEach(::add) }.build())
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> =
            Futures.immediateFuture(parts.firstNotNullOfOrNull { it.onCommand(customCommand, args) } ?: SessionResult(SessionError.ERROR_NOT_SUPPORTED))

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(LibraryTree.ROOT, getString(R.string.app_name)), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val data = treeData() ?: return Futures.immediateFuture(LibraryResult.ofItemList(emptyList(), params))
            val nodes = LibraryTree.children(parentId, data) ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val size = pageSize.coerceAtLeast(1)
            val paged = nodes.drop(page.coerceAtLeast(0) * size).take(size)
            return Futures.immediateFuture(LibraryResult.ofItemList(paged.map(::browseItem), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                if (mediaId == LibraryTree.ROOT) LibraryResult.ofItem(folder(LibraryTree.ROOT, getString(R.string.app_name)), null) else LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED),
            )

        /** A song picked in the browse tree plays its list from it, as a tap in the app does; the queue's songs go to the player. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val data = treeData()
            val play = mediaItems.singleOrNull()?.mediaId?.let { id -> data?.let { LibraryTree.play(id, it) } }
                ?: return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            when (play) {
                is TreePlay.FromList -> core.switch { it.play(play.songs, play.start, play.source, core.now()) }
                is TreePlay.InQueue -> core.switch { it.jump(play.queueId, play.index, core.now()) }
            }
            return Futures.immediateFuture(playing())
        }

        /** Play on a headset or Bluetooth after KJB was closed: the queue playing at its place, once the queues and the library are read. */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                core.ready.first { it }
                library.first { it != null }
                result.set(playing())
            }
            return result
        }
    }

    /** The queue playing as media items, from its current song (or the next on the phone) at its place. */
    private fun playing(): MediaSession.MediaItemsWithStartPosition {
        val queue = core.book.value.current
        val projected = projection(queue, ::find)
        val start = queue?.let { startOf(it, projected) } ?: -1
        if (start < 0) return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val position = if (projected[start].uid == queue?.current?.uid) queue.positionMs else 0
        return MediaSession.MediaItemsWithStartPosition(projected.map(::mediaItem), start, position)
    }

    private fun treeData(): TreeData? {
        val songs = library.value ?: return null
        return TreeData(
            library = songs,
            book = core.book.value,
            playlists = playlists.value,
            settings = settings.value.library,
            rootName = ::rootName,
            queueName = { queueLabel(this, it) },
        )
    }

    private fun rootName(id: String): String = getString(
        when (id) {
            LibraryTree.QUEUES -> R.string.player_tree_queues
            LibraryTree.PLAYLISTS -> R.string.player_tree_playlists
            LibraryTree.ALBUMS -> R.string.player_tree_albums
            LibraryTree.ARTISTS -> R.string.player_tree_artists
            else -> R.string.player_tree_songs
        },
    )

    private fun browseItem(node: TreeNode): MediaItem {
        val song = node.song ?: return folder(node.id, node.title, node.subtitle)
        return MediaItem.Builder()
            .setMediaId(node.id)
            .setMediaMetadata(metadataOf(song, settings.value.library).buildUpon().setIsBrowsable(false).setIsPlayable(true).build())
            .build()
    }

    private fun folder(id: String, title: String, subtitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        )
        .build()
}

/**
 * Covers for the notification, the lock screen and Bluetooth: an audio file's MediaStore uri ([metadataOf]'s artwork
 * uri) gives MediaStore's thumbnail of its embedded art; anything else goes to Media3's own loader. A song without a
 * cover fails, and the session shows none.
 */
@OptIn(UnstableApi::class)
private class ThumbnailLoader(private val context: Context) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader.Builder(context).build()
    private val io: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != MediaStore.AUTHORITY) return fallback.loadBitmap(uri)
        // No embedded cover: loadThumbnail throws, and the future fails.
        return io.submit<Bitmap> { context.contentResolver.loadThumbnail(uri, Size(COVER_PX, COVER_PX), null) }
    }

    private companion object {
        /** The notification and the lock screen show covers at most this size. */
        const val COVER_PX: Int = 512
    }
}
