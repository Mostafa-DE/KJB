package app.lyrify.player

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.audio.CrossfadeEngine
import app.lyrify.audio.LiveEq
import app.lyrify.audio.MediaFocus
import app.lyrify.audio.OutputRoutesPart
import app.lyrify.audio.QueueFadeRules
import app.lyrify.auto.CarBrowse
import app.lyrify.auto.CarCovers
import app.lyrify.auto.ControllerAccess
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.search.SearchGraph
import app.lyrify.logFailures
import app.lyrify.media.NowPlaying
import app.lyrify.media.QueueSweep
import app.lyrify.settings.Settings
import app.lyrify.ui.DESTINATION_PLAYER
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.LockLyricsActivity
import app.lyrify.ui.LockLyricsPart
import app.lyrify.ui.MainActivity
import app.lyrify.widget.WidgetPart
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
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

    /** A media button (a headset, Bluetooth, a car); true when this part took it, false to leave it to the session. */
    fun onMediaButton(session: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean = false
}

/**
 * The request code of the session's tap ([PlaybackService]): the AI's notifications open MainActivity with 0, and two
 * PendingIntents for one activity and one code are one, so one tap would carry the other's destination.
 */
private const val OPEN_PLAYER: Int = 1

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
    private val parts: List<ServicePart> = listOf(WidgetPart(), LockLyricsPart(), SleepPart(), ExtrasPart(), ButtonsPart(), ConnectPart(), OutputRoutesPart())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
    private var session: MediaLibrarySession? = null
    private lateinit var core: PlayerCore
    private lateinit var engine: CrossfadeEngine

    /** The engine, for the parts that work on the player itself (speed, the sleep timer's fade). */
    internal val playbackEngine: PlaybackEngine get() = engine
    private lateinit var player: LyrifyPlayer
    private lateinit var bridge: QueueBridge
    private lateinit var settings: StateFlow<Settings>
    private lateinit var library: StateFlow<Library?>
    private lateinit var browse: CarBrowse
    private val playing = MutableStateFlow(false)
    private lateinit var thumbnails: ThumbnailLoader

    /** Songs that could not be played since one last played: the whole queue failing stops the player. */
    private var failedInARow = 0
    private val tracker = PlayTracker({ event -> Lyrify.playEvents(this).onEvent(event) }, System::currentTimeMillis)
    private val offload = OffloadGate { engine.setOffload(it) }

    override fun onCreate() {
        super.onCreate()
        core = PlayerGraph.core(this)
        settings = Lyrify.settings(this).settings.stateIn(scope, SharingStarted.Eagerly, Settings())
        library = LibraryGraph.repository(this).state.map { (it as? LibraryState.Ready)?.library }.stateIn(scope, SharingStarted.Eagerly, null)
        val levels = Levels()
        val main = Handler(mainLooper)
        // One gapless player; with Crossfade on, a second one around each fade (A2), in the same audio session. Focus is
        // the engine's then.
        engine = CrossfadeEngine(
            newSlot = { GaplessEngine(this, settings::value, levels, ::mediaItem) },
            rules = QueueFadeRules(core, PlayerExtras.of(this), settings::value),
            focusOf = { control -> MediaFocus(this, mainLooper, control) },
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            later = { task -> main.post(task) },
        )
        // The phone's sound effects panel needs the player's own session from the start (A1).
        PlayerGraph.audioSessionId = engine.audioSessionId
        bridge = QueueBridge(engine)
        player = LyrifyPlayer(mainLooper, engine, core, skips = ::skips, sync = ::sync, beforeSeek = ::heardSoFar)
        engine.addListener(Follower())
        core.follow { engine.loaded.getOrNull(engine.index)?.let { PlayerAt(it, engine.positionMs) } }
        thumbnails = ThumbnailLoader(this)
        val built = MediaLibrarySession.Builder(this, player, Callback())
            // A tap on the notification or the lock screen's card opens the player, over the tab the app was on, and the
            // lyrics on top of it: over the lock screen they show without unlocking; on an unlocked phone they close at once (P3).
            .setSessionActivity(
                PendingIntent.getActivities(this, OPEN_PLAYER, arrayOf(openPlayer(), Intent(this, LockLyricsActivity::class.java)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .setBitmapLoader(thumbnails)
            .build()
        session = built
        browse = CarBrowse(this, built, core, settings, scope, ::playing, search = SearchGraph.songs(this))
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
                if (book.currentId != queueId) failedInARow = 0
                queueId = book.currentId
                jumps = book.jumps
                if (songs != null) bridge.sync(book, ::find)
                player.changed()
            }
        }
        scope.launch { core.repeat.collect { engine.setRepeat(it); player.changed() } }
        scope.launch {
            settings.collect {
                engine.setCrossfade(it.audio.crossfadeSeconds)
                engine.setGapless(it.advanced.gaplessPlayback)
                player.changed()
            }
        }
        // Offload only while no stage changes the sound (levelling, the equalizer of the output playing), once the
        // equalizer is read; turned back on only at a song start (OffloadGate).
        scope.launch {
            val live = LiveEq.of(this@PlaybackService)
            combine(settings, live.profiles, live.sound) { now, profiles, eq -> offloadWanted(now, eq.takeIf { profiles != null }) }
                .distinctUntilChanged()
                .collect(offload::wanted)
        }
        scope.launch { savePlaces(playing) { savePlace() } }
        scope.launch { repeatWhilePlaying(playing, POSITION_EVERY_MS) { tracker.position(engine.positionMs, playing = true) } }
        ownLyrics()
        parts.forEach { it.start(this, built) }
        Log.d(LOG_TAG, "player service started")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        // Written before the process may go.
        engine.loaded.getOrNull(engine.index)?.let { uid -> core.savePlaceNow(uid, engine.positionMs) }
        core.follow(null)
        heardSoFar()
        tracker.end()
        parts.forEach { it.stop() }
        session?.release()
        session = null
        // Its thread would outlive the service; a cover still loading fails, and the notification is going anyway.
        thumbnails.shutdown()
        engine.release()
        player.release()
        scope.cancel()
        io.cancel()
        super.onDestroy()
    }

    private fun find(ref: SongRef): Song? = library.value?.resolver?.find(ref)

    /** KJB, brought back where it was (onNewIntent when it is open), showing the player. */
    private fun openPlayer(): Intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_DESTINATION, DESTINATION_PLAYER)

    private fun skips(): SkipAmounts = skipAmounts(settings.value.playback)

    private fun mediaItem(projected: Projected): MediaItem = mediaItemOf(projected, settings.value.library, CarCovers.authority(this))

    /** The bridge applied now, before a controller's items are matched against the engine's. */
    private fun sync() {
        if (core.ready.value) library.value?.let { bridge.sync(core.book.value, ::find) }
    }

    /** Where the queue playing is (its song and position), into `playback.json`. */
    private fun savePlace() {
        if (!::engine.isInitialized) return
        val uid = engine.loaded.getOrNull(engine.index) ?: return
        core.savePlace(uid, engine.positionMs)
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
            offload.songStarted()
            val book = core.book.value
            val item = book.current?.items?.firstOrNull { it.uid == uid } ?: return
            if (!lap) core.change { it.at(uid, core.now()) }
            // Every song start, each lap of Repeat one too: the place names the song playing.
            savePlace()
            tracker.start(item.song, find(item.song)?.durationMs ?: C.TIME_UNSET, engine.positionMs, engine.isPlaying, naturalEnd = natural)
        }

        override fun onPlayingChanged(playing: Boolean) {
            if (playing) failedInARow = 0
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

        /**
         * A song that cannot be played is skipped quietly, to the song the player would play next (round to the first
         * under Repeat all); with nothing after it, or every song failed in a row, the player stops, the error showing.
         */
        override fun onError(error: PlaybackException, next: Int) {
            failedInARow++
            val step = skipAfterError(engine, next, failedInARow)
            Log.w(LOG_TAG, "player: a song could not be played (${error.errorCodeName}), ${if (step is AfterError.Skip) "skipped" else "stopped after $failedInARow"}")
        }
    }

    /** What the audio chain asks about the stream playing (A1). */
    private inner class Levels : SongLevels {
        override fun albumInOrder(mediaId: String): Boolean {
            val queue = core.book.value.current ?: return false
            val uid = mediaId.toLongOrNull() ?: return false
            return albumInOrder(queue, queue.items.indexOfFirst { it.uid == uid })
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        /** Every player command by code, and the session's own commands with the library's: what [ControllerAccess.offer] narrows. */
        private val everyPlayer: Set<Int> = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.let { all -> (0 until all.size()).map(all::get).toSet() }
        private val everySession: Set<SessionCommand> = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.commands

        /**
         * Only KJB, the system's trusted controllers and the car get every command, see the library, search and queues,
         * and get the parts' commands (Favourite, Shuffle, Repeat); any other app gets plain transport (auto/CarBrowse.kt).
         * Built from Media3's full defaults: since Media3 1.11 its own onConnect offers no command at all, and its own
         * default here gives any other app read-only commands, without play and pause.
         */
        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
            val offer = ControllerAccess.offer(browse.connect(controller), everyPlayer, everySession, parts.flatMap { it.commands })
            return Futures.immediateFuture(
                MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(SessionCommands.Builder().apply { offer.session.forEach(::add) }.build())
                    .setAvailablePlayerCommands(Player.Commands.Builder().apply { offer.player.forEach(::add) }.build())
                    .build(),
            )
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
            parts.any { it.onMediaButton(session, controllerInfo, intent) } || super.onMediaButtonEvent(session, controllerInfo, intent)

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> =
            Futures.immediateFuture(parts.firstNotNullOfOrNull { it.onCommand(customCommand, args) } ?: SessionResult(SessionError.ERROR_NOT_SUPPORTED))

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) = browse.disconnect(controller)

        // The browse tree, search and voice play: auto/CarBrowse.kt.
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(browse.root(params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = browse.children(browser, parentId, page, pageSize, params)

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(browse.item(mediaId))

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            browse.search(browser, query, params)

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = browse.searchResult(browser, query, page, pageSize, params)

        /** A song picked in the browse tree plays its list from it, as a tap in the app does, and a voice request plays what it names. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            browse.setMediaItems(controller, mediaItems) ?: super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)

        /**
         * Play on a headset or Bluetooth after KJB was closed: the queue playing at its place, once the queues and the
         * library are read; nothing when the library cannot be read (no All files access), or after [RESUME_WAIT_MS].
         */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val none = MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            val songs = LibraryGraph.repository(this@PlaybackService).state
            return resumption(scope, RESUME_WAIT_MS, none, ready = {
                core.ready.first { it }
                // The service's own view of the library is what playing() reads: wait for it too, not only the read.
                songs.first { it !is LibraryState.Loading } is LibraryState.Ready && library.first { it != null } != null
            }) { playing() }
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
}

/**
 * Covers for the notification, the lock screen and Bluetooth: a uri of KJB's cover provider ([metadataOf]'s artwork
 * uri) is read here in the process ([CarCovers.bitmap]): the song's embedded art, else KJB's default cover, at most
 * 512 px. Anything else goes to Media3's own loader.
 */
@OptIn(UnstableApi::class)
private class ThumbnailLoader(private val context: Context) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader.Builder(context).build()
    private val io: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val covers = CarCovers.authority(context)

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != covers) return fallback.loadBitmap(uri)
        return io.submit<Bitmap> { CarCovers.bitmap(context, uri.lastPathSegment) }
    }

    /** Lets the loading thread go, with the service. */
    fun shutdown() = io.shutdown()
}
