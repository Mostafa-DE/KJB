package app.lyrify.player

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
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
import app.lyrify.audio.OutputKind
import app.lyrify.audio.OutputRoutesPart
import app.lyrify.audio.QueueFadeRules
import app.lyrify.audio.readOutput
import app.lyrify.auto.CarBrowse
import app.lyrify.auto.CarCovers
import app.lyrify.auto.ControllerAccess
import app.lyrify.books.BookPart
import app.lyrify.books.BookPlayFilter
import app.lyrify.books.BooksGraph
import app.lyrify.books.inBook
import app.lyrify.books.isBookPart
import app.lyrify.books.openedIntoBook
import app.lyrify.books.playIsBook
import app.lyrify.books.playingBook
import app.lyrify.books.skipsFor
import app.lyrify.huddle.HuddleGraph
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
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEvents
import app.lyrify.stats.PlayStart
import app.lyrify.stats.songKey
import app.lyrify.suggestions.SuggestionsPart
import app.lyrify.tags.CoverVersions
import app.lyrify.ui.DESTINATION_PLAYER
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.LockLyricsActivity
import app.lyrify.ui.LockLyricsPart
import app.lyrify.ui.MainActivity
import app.lyrify.widget.WidgetPart
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
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

    /**
     * The player stopped where the queue waits, as the song playing left it with none after it (Remove from queue, the
     * trash, its file gone; [QueueBridge]): nothing is left to play, as when the queue plays to its end (the engine's
     * onEnded).
     */
    fun queueStopped() {}
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
    private val parts: List<ServicePart> = listOf(WidgetPart(), LockLyricsPart(), SleepPart(), ExtrasPart(), ButtonsPart(), ConnectPart(), OutputRoutesPart(), BookPart(), SuggestionsPart())

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
    /** Friends sharing's plays (huddle/HuddleGraph.kt), made once the service has its context. */
    private val shared: PlayEvents by lazy { HuddleGraph.plays(this) }

    /** The play counter and Friends sharing (Huddle), without a book's plays, which go to the book log (books/BookPlayback.kt). */
    private val plays = BookPlayFilter({ event -> Lyrify.playEvents(this).onEvent(event); shared.onEvent(event) }, { event -> Lyrify.bookEvents(this).onEvent(event) }) { path -> playIsBook(path, core.book.value.current, library.value) }
    private val tracker = PlayTracker(plays::onEvent, System::currentTimeMillis)
    private val offload = OffloadGate { engine.setOffload(it) }

    /** The notification posted once more after a burst of updates, so the phone's media card catches up ([NotificationRepost]). */
    private val repost = NotificationRepost(scope) { triggerNotificationUpdate() }

    override fun onCreate() {
        super.onCreate()
        core = PlayerGraph.core(this)
        // Copies of files other apps opened go once no queue holds them.
        OpenedFiles.watch(this, core.book, scope)
        settings = Lyrify.settings(this).settings.stateIn(scope, SharingStarted.Eagerly, Settings())
        library = LibraryGraph.repository(this).state.map { (it as? LibraryState.Ready)?.library }.stateIn(scope, SharingStarted.Eagerly, null)
        val levels = Levels()
        val main = Handler(mainLooper)
        // One gapless player; with Crossfade on, a second one around each fade (A2), in the same audio session. Focus is
        // the engine's then.
        engine = CrossfadeEngine(
            newSlot = { GaplessEngine(this, settings::value, levels, ::mediaItem) },
            rules = QueueFadeRules(core, PlayerExtras.of(this), settings::value) { bookSpeed() },
            focusOf = { control -> MediaFocus(this, mainLooper, control) },
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            later = { task -> main.post(task) },
        )
        // The phone's sound effects panel needs the player's own session from the start (A1).
        PlayerGraph.audioSessionId = engine.audioSessionId
        // The song playing gone with none after it (Remove from queue, the trash): the player stops where the queue waits,
        // and the parts hear that nothing is left to play (a sleep timer by songs ends).
        bridge = QueueBridge(engine, repeat = { core.repeat.value }, coverVersion = CoverVersions::of) {
            engine.setPlayWhenReady(false)
            parts.forEach(ServicePart::queueStopped)
        }
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
                if (songs != null) bridge.sync(book, ::playable)
                player.changed()
            }
        }
        // A book never repeats (books/BookPlayback.kt): its queue plays to its end whatever Repeat says.
        scope.launch {
            combine(core.repeat, core.book.map { inBook(it.current) }.distinctUntilChanged()) { repeat, book -> if (book) Repeat.OFF else repeat }
                .collect { engine.setRepeat(it); player.changed() }
        }
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
        // Covers & tags changed a song's cover: the session's kept covers are read again, and the songs the player holds
        // are handed to it again at the new cover's uri, so the lock screen, the notification and the car show it now.
        scope.launch {
            CoverVersions.versions.drop(1).collect {
                thumbnails.forget()
                sync()
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

    /** Each update Media3 makes to the media notification; once they stop, [repost] posts it once more. */
    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void> {
        repost.posted()
        return super.onUpdateNotificationAsync(session, startInForegroundRequired)
    }

    override fun onDestroy() {
        // Written before the process may go; a queue change still waiting is written too, off the main thread.
        engine.loaded.getOrNull(engine.index)?.let { uid -> core.savePlaceNow(uid, engine.positionMs) }
        core.flush()
        core.follow(null)
        heardSoFar()
        tracker.end(PlayEnd.STOPPED)
        parts.forEach { it.stop() }
        session?.release()
        session = null
        // Its threads would outlive the service; the notification is going anyway.
        thumbnails.shutdown()
        engine.release()
        player.release()
        scope.cancel()
        io.cancel()
        super.onDestroy()
    }

    private fun find(ref: SongRef): Song? = library.value?.resolver?.find(ref)

    /** A song the player plays: the library's, else a file another app opened that the library lacks (OpenedFiles). */
    private fun playable(ref: SongRef): Song? = find(ref) ?: OpenedFiles.song(this, ref)

    /** KJB, brought back where it was (onNewIntent when it is open), showing the player. */
    private fun openPlayer(): Intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_DESTINATION, DESTINATION_PLAYER)

    /** The seek buttons' jumps: 30 s in a book, else "Skip amounts" (books/BookPlayback.kt). */
    private fun skips(): SkipAmounts = skipsFor(core.book.value.current, settings.value.playback)

    /** The speed of the book playing; null while music plays. */
    private fun bookSpeed(): Speed? = playingBook(core.book.value.current, library.value)?.let { BooksGraph.store(this).state.value.speedOf(it.book) }

    private fun mediaItem(projected: Projected): MediaItem = mediaItemOf(projected, settings.value.library, CarCovers.uris(this))

    /** The bridge applied now, before a controller's items are matched against the engine's. */
    private fun sync() {
        if (core.ready.value) library.value?.let { bridge.sync(core.book.value, ::playable) }
    }

    /** Where the queue playing is (its song and position), into `playback.json`. */
    private fun savePlace() {
        if (!::engine.isInitialized) return
        val uid = engine.loaded.getOrNull(engine.index) ?: return
        core.savePlace(uid, engine.positionMs)
    }

    /**
     * The covers of the songs either side of the one playing, read ahead: Next and Previous then find theirs kept, so the
     * lock screen gets the song's details and its cover in one update ([SessionCovers]).
     */
    private fun warmCovers() {
        val items = engine.items
        thumbnails.warm(aroundSong(engine.index, items.size).mapNotNull { items[it].mediaMetadata.artworkUri })
    }

    /** Where the song playing is now, for the play counter. */
    private fun heardSoFar() {
        if (::engine.isInitialized && engine.loaded.isNotEmpty()) tracker.position(engine.positionMs, engine.isPlaying)
    }

    /** KJB's own queue gets lyrics without notification access: a second sweep on the app's one pipeline. */
    private fun ownLyrics() {
        val pipeline = Lyrify.pipeline(this)
        val sweep = QueueSweep(
            // A book's part (a book's queue gives no snapshot; a part added to music is left out here) never gets lyrics.
            files = { track -> ownFiles(track) { path -> find(SongRef(path))?.takeUnless { isBookPart(it.path, library.value) } } },
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
            warmCovers()
            // Taken at every start, so a move to a song that never started is never taken for a later one.
            val move = core.takeMove(uid)
            val queue = core.book.value.current
            val item = queue?.items?.firstOrNull { it.uid == uid } ?: return
            if (!lap) core.change { it.at(uid, core.now()) }
            // Every song start, each lap of Repeat one too: the place names the song playing.
            savePlace()
            val song = find(item.song)
            val (ended, by) = playTurn(natural, move, item.playNext, suggested = queue.suggests(item))
            // A natural crossfade's song going out is left where its fade began (the engine says so only now). Where it is
            // heard and the playlist it comes from, for Insights: the kind of output only, and a playlist's id only.
            tracker.start(
                item.song, song?.durationMs ?: C.TIME_UNSET, engine.positionMs, engine.isPlaying,
                ended = ended, leftAtMs = engine.fadeFromMs,
                start = PlayStart(
                    by, queue.source.kind, queue.shuffled, song?.let(::songKey),
                    output = if (browse.inCar) OutputKind.CAR else readOutput(getSystemService(AudioManager::class.java)).kind,
                    playlistId = queue.source.key.takeIf { queue.source.kind == SourceKind.PLAYLIST },
                ),
            )
        }

        override fun onPlayingChanged(playing: Boolean) {
            if (playing) failedInARow = 0
            PlayerGraph.audioSessionId = engine.audioSessionId
            tracker.position(engine.positionMs, playing)
            this@PlaybackService.playing.value = playing
            if (!playing) core.flush()
        }

        override fun onSeeked(fromMs: Long, toMs: Long) = tracker.seeked(fromMs, toMs)

        /** The queue ended with Repeat off: KJB waits at its first song, paused. */
        override fun onEnded() {
            // An emptied player (the queue's last song removed) also ends: that play stopped where it was.
            if (engine.loaded.isEmpty()) return tracker.end(PlayEnd.STOPPED)
            tracker.end(PlayEnd.FINISHED)
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

        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            Futures.immediateFuture(browse.subscribe(browser, parentId, params))

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
         * library are read; nothing when the library cannot be read (no All files access), or after [RESUME_WAIT_MS]. An
         * "Opened file" queue left on a book's file is that book's queue first (BK1), so the play resumes a book.
         */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val none = MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            val songs = LibraryGraph.repository(this@PlaybackService).state
            val books = BooksGraph.store(this@PlaybackService)
            return resumption(scope, RESUME_WAIT_MS, none, ready = {
                core.ready.first { it }
                // The service's own view of the library is what playing() reads: wait for it too, not only the read.
                val read = songs.first { it !is LibraryState.Loading } is LibraryState.Ready && library.first { it != null } != null
                library.value?.let { known ->
                    core.openedIntoBook(known, books, { path -> OpenedFiles.libraryCopyOf(this@PlaybackService, path, known.all) }) { !engine.playWhenReady }
                }
                read
            }) { playing() }
        }
    }

    /** The queue playing as media items, from its current song (or the next on the phone) at its place. */
    private fun playing(): MediaSession.MediaItemsWithStartPosition {
        val queue = core.book.value.current
        val projected = projection(queue, ::playable)
        val start = queue?.let { startOf(it, projected) } ?: -1
        if (start < 0) return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val position = if (projected[start].uid == queue?.current?.uid) queue.positionMs else 0
        return MediaSession.MediaItemsWithStartPosition(projected.map(::mediaItem), start, position)
    }
}

/**
 * Covers for the notification, the lock screen and Bluetooth: a uri of KJB's cover provider ([metadataOf]'s artwork
 * uri) is read here in the process ([CarCovers.read]): the song's embedded art, else KJB's default cover, at most
 * 512 px; kept, read ahead, the newest asked for first, and each within its wait ([SessionCovers]). Anything else goes
 * to Media3's own loader.
 */
@OptIn(UnstableApi::class)
private class ThumbnailLoader(private val context: Context) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader.Builder(context).build()
    private val io: ExecutorService = Executors.newFixedThreadPool(SessionCovers.READERS)
    private val main = Handler(context.mainLooper)
    private val uris = CarCovers.uris(context)

    /** KJB's default cover, made once, as kept covers are ([CarCovers.sessionCover]). */
    private val default: Bitmap by lazy { CarCovers.sessionCover(CarCovers.defaultBitmap(context)) }
    private val covers = SessionCovers<Uri, Bitmap>(
        read = { uri -> CarCovers.read<CoverFound<Bitmap>>(context, uri, { CoverFound.Own(CarCovers.sessionCover(it)) }) { CoverFound.NoArt } },
        fallback = { default },
        sizeOf = Bitmap::getAllocationByteCount,
        run = io,
        later = { delayMs, task ->
            main.postDelayed(task, delayMs)
            Runnable { main.removeCallbacks(task) }
        },
        clock = SystemClock::elapsedRealtime,
        log = { line -> Log.w(LOG_TAG, line) },
    )

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = if (ours(uri)) covers.ask(uri) else fallback.loadBitmap(uri)

    /** The covers at [artwork] read ahead: the songs either side of the one playing. */
    fun warm(artwork: List<Uri>) = covers.warm(artwork.filter(::ours))

    /** KJB changed covers: the ones kept are read again. */
    fun forget() = covers.forget()

    private fun ours(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == uris.authority

    /**
     * Lets the reading threads go, with the service: a read going on ends as it would, and a cover not read yet gets the
     * default at once.
     */
    fun shutdown() = io.shutdown()
}

/** How long the media notification's updates must stop before [NotificationRepost] posts it once more. */
const val REPOST_QUIET_MS: Long = 1_500

/**
 * The media notification posted once more when its updates stop. The phone's media card (the shade, quick settings,
 * the lock screen) reads the song only when the notification is posted, and a read can end wrong: Android drops an
 * app's updates above about five a second, and one song change is several posts (the song, buffering, ready, its
 * cover, its buttons), so Nexts in quick succession lose the last ones; a card coming back to life reads at once and
 * can land after a newer read. Media3 never posts again once a song settles, so the card stayed on a song skipped past,
 * often with no cover, until the next song. So once [quietMs] go by with no update ([posted]), [post] posts it again,
 * long enough after the last post for Android to let it through. Its own post is not an update. Main thread.
 */
class NotificationRepost(private val scope: CoroutineScope, private val quietMs: Long = REPOST_QUIET_MS, private val post: () -> Unit) {
    private var waiting: Job? = null
    private var posting = false

    /** Media3 updated the notification: the wait starts again. */
    fun posted() {
        if (posting) return
        waiting?.cancel()
        waiting = scope.launch {
            delay(quietMs)
            posting = true
            try {
                post()
            } finally {
                posting = false
            }
        }
    }
}
