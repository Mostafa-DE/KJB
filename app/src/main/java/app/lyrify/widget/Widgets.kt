package app.lyrify.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.action.mutableActionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionToken
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.auto.CarCovers
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.SongRef
import app.lyrify.logFailures
import app.lyrify.player.PlaybackService
import app.lyrify.player.PlayerGraph
import app.lyrify.player.ServicePart
import app.lyrify.tags.CoverVersions
import app.lyrify.ui.DarkColors
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.LauncherCoral
import app.lyrify.ui.LightColors
import app.lyrify.ui.MainActivity
import app.lyrify.ui.heartLabel
import app.lyrify.ui.queueDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * What the widgets draw: [state], and its own cover read once: sharp for the cover's slot ([cover]) and its glow behind
 * each layout ([glow]), null without one (KJB's default cover is drawn, on its glow). [coverOf] is the cover it was read
 * for ([WidgetState.Loaded.coverKey]: its path, and its version once Covers & tags changed it), the key both are kept by:
 * null while no widget is placed, or after a read that failed, so the next update reads it again. [noArtAt] is when a read
 * found no art for it (SystemClock.elapsedRealtime), until it is read once more ([NO_ART_RECHECK_MS]); else null.
 */
internal class Shown(val state: WidgetState, val cover: Bitmap? = null, val glow: Map<GlowLayout, Bitmap>? = null, val coverOf: String? = null, val noArtAt: Long? = null)

/** A cover read for the widgets ([Widgets.withCover]): its own, or none; a read that failed gives null. */
internal sealed interface CoverRead {
    /** A song's embedded art, or the cover of a file another app opened: sharp for its slot, and its glow for each layout. */
    class Own(val cover: Bitmap, val glow: Map<GlowLayout, Bitmap>) : CoverRead

    /** None: MediaStore has no art for the song (or the song is gone), or the copy has no cover. The default is drawn. */
    data object NoArt : CoverRead
}

/**
 * The widgets' state in this process, and the updates pushed to them. In memory only: a fresh process (after a restart
 * of the phone, or once KJB was killed) has no player, so the widgets show the empty state until the service runs again.
 */
internal object Widgets {
    // Guards the state and its cover only, never an update: provideGlance takes it too ([reconcile]).
    private val lock = Mutex()
    private val current = MutableStateFlow(Shown(WidgetState.Empty))
    val shown: StateFlow<Shown> = current.asStateFlow()

    /** The player's session lives in this process: the playback service's widget part runs. */
    @Volatile
    var playerAlive: Boolean = false
        private set

    fun started() {
        playerAlive = true
    }

    /** [state] to every KJB widget on the home screen. While none is placed, nothing is drawn and no cover read. */
    suspend fun show(context: Context, state: WidgetState) {
        val placed = placed(context)
        lock.withLock { current.value = if (placed) withCover(state, current.value, SystemClock.elapsedRealtime()) { thumbnails(context, it) } else Shown(state) }
        if (placed) update(context)
    }

    /**
     * The playback service stops: nothing is loaded. The update is handed to Glance before this returns (it waits at
     * most [STOP_WAIT_MS]), as the process may go soon after; a widget session still running redraws from [shown].
     */
    fun stopped(context: Context) {
        playerAlive = false
        current.value = Shown(WidgetState.Empty)
        runBlocking(Dispatchers.Default) { withTimeoutOrNull(STOP_WAIT_MS) { if (placed(context)) update(context) } }
    }

    /**
     * A widget is drawn: without a live player (the process was killed and started again since) the empty state, else
     * the song with its cover, read now if it was not yet (the widget was placed after the song started).
     */
    suspend fun reconcile(context: Context) = lock.withLock {
        val state = onScreen(current.value.state, playerAlive)
        current.value = if (state == WidgetState.Empty) Shown(state) else withCover(state, current.value, SystemClock.elapsedRealtime()) { thumbnails(context, it) }
    }

    /** The widgets drawn again, after a tap that no player answers (the heart on a widget left from before a kill). */
    suspend fun refresh(context: Context) {
        reconcile(context)
        if (placed(context)) update(context)
    }

    private suspend fun update(context: Context) {
        SmallWidget().updateAll(context)
        LargeWidget().updateAll(context)
    }

    private suspend fun placed(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(SmallWidget::class.java).isNotEmpty() || manager.getGlanceIds(LargeWidget::class.java).isNotEmpty()
    }

    /**
     * [state] with its cover: [before]'s when it was read for the same cover (the song's, at the same version), else [read]
     * now ([thumbnails]); a read that failed (null) records no cover, so the next update reads it again. No art is kept
     * for the song with the time it was read ([now]), and read once more at the first update [NO_ART_RECHECK_MS] on; what
     * that read finds stays.
     */
    internal suspend fun withCover(state: WidgetState, before: Shown, now: Long, read: suspend (String) -> CoverRead?): Shown {
        val loaded = state as? WidgetState.Loaded
        val path = loaded?.cover ?: return Shown(state)
        val key = loaded.coverKey
        if (coverToRead(state, before.coverOf, before.noArtAt, now) == null) return Shown(state, before.cover, before.glow, key, before.noArtAt)
        return when (val cover = read(path)) {
            null -> Shown(state)
            is CoverRead.Own -> Shown(state, cover.cover, cover.glow, key)
            // The same cover as before: this was the read once more, so no art stays for the song.
            CoverRead.NoArt -> Shown(state, coverOf = key, noArtAt = now.takeIf { key != before.coverOf })
        }
    }

    /**
     * The own cover at [path] (a song's, or a file another app opened), read once at the uri the session and the car
     * have for it, in this process as the notification reads it ([CarCovers.read]), off the main thread: sharp at the
     * cover's slot on this screen ([sharpCover]), and its glow for each layout ([glowBitmap]). [CoverRead.NoArt] when it
     * has none, null when it could not be read now.
     */
    private suspend fun thumbnails(context: Context, path: String): CoverRead? = withContext(Dispatchers.IO) {
        val px = coverPx(context.resources.displayMetrics.density)
        CarCovers.read(context, CarCovers.uris(context).of(path), { own ->
            CoverRead.Own(sharpCover(own, px), GlowLayout.entries.associateWith { glowBitmap(own, it) })
        }) { CoverRead.NoArt }
    }

    /** Long enough for Glance to take the update, short enough for the service's onDestroy on the main thread. */
    private const val STOP_WAIT_MS: Long = 1_000
}

/**
 * The widgets' part of the playback service: while the service runs, the widgets show the song the player holds,
 * pushed only when the song, the play state or the favourite changes ([widgetStates]); when it stops, the empty state.
 * Playing is what the notification shows: Pause while the player means to play, buffering included. An update that
 * fails is logged by its exception's name, and the next one goes on.
 */
@OptIn(UnstableApi::class)
class WidgetPart : ServicePart {
    private var onStop: () -> Unit = {}

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val context = service.applicationContext
        val player = session.player
        val playing = MutableStateFlow(!Util.shouldShowPlayButton(player))
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing.value = !Util.shouldShowPlayButton(player)
            }
        }
        player.addListener(listener)
        Widgets.started()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
        scope.launch {
            widgetStates(
                book = PlayerGraph.core(context).book,
                library = LibraryGraph.repository(context).state.map { (it as? LibraryState.Ready)?.library },
                playing = playing,
                favourites = LibraryGraph.favourites(context).songs,
                settings = Lyrify.settings(context).settings.map { it.library },
                versions = CoverVersions.versions,
                playback = Lyrify.settings(context).settings.map { it.playback },
            ).collect { state ->
                try {
                    Widgets.show(context, state)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    Log.w(LOG_TAG, "widget: an update failed (${e.javaClass.simpleName})")
                }
            }
        }
        onStop = {
            player.removeListener(listener)
            scope.cancel()
            Widgets.stopped(context)
        }
    }

    override fun stop() = onStop()
}

// The widgets

/**
 * The sizes Glance draws the Glow widget at: each layout from its least height ([GlowLayout.fromHeightDp]). The home
 * screen shows the largest that fits the widget, so a widget resized to more rows gets the next layout at once.
 */
private val GLOW_SIZES = SizeMode.Responsive(GlowLayout.entries.map { DpSize(GLOW_FROM_WIDTH_DP.dp, it.fromHeightDp.dp) }.toSet())

/** "KJB", placed at 4×1: the Glow widget, its layout from its size ([GlowLayout]), as "KJB large"'s. */
class SmallWidget : GlanceAppWidget() {
    // The state lives in memory ([Widgets]), not in a file per widget.
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override val sizeMode: SizeMode = GLOW_SIZES

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideGlow(context)
}

/** "KJB large", placed at 4×3: the same Glow widget; kept apart so widgets placed before 3.1 go on working. */
class LargeWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override val sizeMode: SizeMode = GLOW_SIZES

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideGlow(context)
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallWidget()
}

class LargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LargeWidget()
}

/**
 * The widget drawn from [Widgets.shown], and again whenever it changes while Glance keeps the widget's session, at each
 * of [GLOW_SIZES]. A song without its own cover shows KJB's default cover on the default cover's glow, made off the main
 * thread here once per process ([defaultGlow]).
 */
private suspend fun GlanceAppWidget.provideGlow(context: Context): Nothing {
    Widgets.reconcile(context)
    val fallback = withContext(Dispatchers.IO) { defaultGlow(context) }
    provideContent {
        val shown by Widgets.shown.collectAsState()
        val layout = glowLayout(LocalSize.current.height.value)
        when (val state = shown.state) {
            WidgetState.Empty -> Empty(layout)
            is WidgetState.Loaded -> Playing(shown, state, layout, (shown.glow ?: fallback)[layout])
        }
    }
}

// Glow: white words and icons on the darkened cover, the same in light and dark mode ([GLOW_MAX_LUMINANCE]).

private val OnGlow = ColorProvider(Color.White)
private val OnGlowSoft = ColorProvider(Color.White.copy(alpha = 0.82f))

/** The icon on the white Play or Pause button: the preview's deep ink. */
private val OnPlay = ColorProvider(Color(0xFF1A1426))

/** The heart while the song is a favourite: the coral of the K's note. */
private val HeartOn = ColorProvider(LauncherCoral)

/** Under the glow, seen only if it is missing (Android 12 and later, where the widget's corners clip it). */
private val GlowBase = Color(0xFF1A1426)

/** The widget's frame: the whole cell, its corners the home screen's own on Android 12 and later (the glow draws them on 11). */
private fun frame(): GlanceModifier {
    val modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
    return if (homeRoundsCorners) modifier.cornerRadius(android.R.dimen.system_app_widget_background_radius) else modifier
}

/** The song playing in [layout]: [glow] over the whole widget, the sharp cover on the left, white words and buttons. */
@Composable
private fun Playing(shown: Shown, state: WidgetState.Loaded, layout: GlowLayout, glow: Bitmap?) {
    Box(modifier = if (homeRoundsCorners) frame().background(GlowBase) else frame()) {
        if (glow != null) Image(ImageProvider(glow), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        when (layout) {
            GlowLayout.SMALL -> Row(
                modifier = GlanceModifier.fillMaxSize().padding(start = layout.paddingDp.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SharpCover(shown, layout)
                Words(state, album = false, GlanceModifier.defaultWeight().padding(horizontal = 12.dp))
                widgetButtons(state, layout).forEachIndexed { i, button ->
                    if (i > 0) Spacer(GlanceModifier.width(2.dp))
                    Transport(button)
                }
            }
            GlowLayout.MEDIUM -> Column(modifier = GlanceModifier.fillMaxSize().padding(layout.paddingDp.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SharpCover(shown, layout)
                    Words(state, album = false, GlanceModifier.defaultWeight().padding(start = 12.dp))
                    Box(modifier = GlanceModifier.height(layout.coverDp.dp), contentAlignment = Alignment.TopEnd) {
                        Row { Heart(state); Queue(state) }
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                Controls(state, layout)
            }
            GlowLayout.LARGE -> Column(modifier = GlanceModifier.fillMaxSize().padding(layout.paddingDp.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    SharpCover(shown, layout)
                    Column(modifier = GlanceModifier.defaultWeight().padding(start = 14.dp)) {
                        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End) { Heart(state); Queue(state) }
                        Spacer(GlanceModifier.height(8.dp))
                        Words(state, album = true, GlanceModifier.fillMaxWidth())
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                Controls(state, layout)
            }
        }
    }
}

/** The title, then the artist and (with [album]) the album softer, one line each; a song without them shows its title only. */
@Composable
private fun Words(state: WidgetState.Loaded, album: Boolean, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(state.title, style = TextStyle(color = OnGlow, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        val soft = TextStyle(color = OnGlowSoft, fontSize = 13.sp)
        state.artist?.let { Text(it, style = soft, maxLines = 1) }
        if (album) state.album?.let { Text(it, style = soft, maxLines = 1) }
    }
}

/** The medium and large layouts' five buttons, across the widget's width with even room between them. */
@Composable
private fun Controls(state: WidgetState.Loaded, layout: GlowLayout) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        widgetButtons(state, layout).forEachIndexed { i, button ->
            if (i > 0) Spacer(GlanceModifier.defaultWeight())
            Transport(button)
        }
    }
}

/** The song's cover, sharp, or KJB's default cover for a song without one; a tap opens the player. */
@Composable
private fun SharpCover(shown: Shown, layout: GlowLayout) {
    val context = LocalContext.current
    val image = shown.cover?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.kjb_default_cover)
    Image(
        provider = image,
        contentDescription = context.getString(R.string.widget_open_player),
        modifier = GlanceModifier.size(layout.coverDp.dp).cornerRadius(layout.coverCornerDp.dp).clickable(actionStartActivity(opening(context, coverDestination(shown.state)))),
        contentScale = ContentScale.Crop,
    )
}

/** KJB opened at [destination] (Nav.kt), on top of what it shows, as a notification's tap opens it. */
private fun opening(context: Context, destination: String): Intent = Intent(context, MainActivity::class.java)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    .putExtra(EXTRA_DESTINATION, destination)

/**
 * A transport button: Play or Pause filled ([background] behind [content]), white on the glow by default, the others
 * white with no fill. TalkBack reads its label, the seek jumps' with their seconds.
 */
@Composable
private fun Transport(button: WidgetButton, background: ColorProvider = OnGlow, content: ColorProvider = OnPlay) {
    val resources = LocalContext.current.resources
    val main = button.command == WidgetCommand.PLAY || button.command == WidgetCommand.PAUSE
    CircleIconButton(
        imageProvider = ImageProvider(button.icon),
        contentDescription = button.seconds?.let { resources.getQuantityString(button.label, it, it) } ?: resources.getString(button.label),
        onClick = actionRunCallback<WidgetTransport>(actionParametersOf(COMMAND to button.command.name)),
        modifier = GlanceModifier.size(if (main) GLOW_PLAY_DP.dp else GLOW_BUTTON_DP.dp),
        backgroundColor = if (main) background else null,
        contentColor = if (main) content else OnGlow,
    )
}

/** The heart: the song into Favourites, or out of them; coral while it is in. */
@Composable
private fun Heart(state: WidgetState.Loaded) {
    val parameters = mutableActionParametersOf(SONG_PATH to state.song.path, FAVOURITE to state.favourite)
    state.song.mediaStoreId?.let { parameters[SONG_ID] = it }
    CircleIconButton(
        imageProvider = ImageProvider(if (state.favourite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite),
        contentDescription = LocalContext.current.getString(heartLabel(state.favourite)),
        onClick = actionRunCallback<WidgetHeart>(parameters),
        modifier = GlanceModifier.size(GLOW_BUTTON_DP.dp),
        backgroundColor = null,
        contentColor = if (state.favourite) HeartOn else OnGlow,
    )
}

/** Queue: KJB opened on the page of the queue playing ([queueDestination]). */
@Composable
private fun Queue(state: WidgetState.Loaded) {
    val queue = state.queue ?: return
    val context = LocalContext.current
    CircleIconButton(
        imageProvider = ImageProvider(R.drawable.ic_queues),
        contentDescription = context.getString(R.string.widget_open_queue),
        onClick = actionStartActivity(opening(context, queueDestination(queue))),
        modifier = GlanceModifier.size(GLOW_BUTTON_DP.dp),
        backgroundColor = null,
        contentColor = OnGlow,
    )
}

// Nothing playing: KJB's own colours ([LightColors], [DarkColors]), as the phone's light or dark mode has them.

private val EmptyText = ColorProvider(day = LightColors.onSurface, night = DarkColors.onSurface)
private val EmptyTextSoft = ColorProvider(day = LightColors.onSurfaceVariant, night = DarkColors.onSurfaceVariant)
private val EmptyPlay = ColorProvider(day = LightColors.primary, night = DarkColors.primary)
private val EmptyOnPlay = ColorProvider(day = LightColors.onPrimary, night = DarkColors.onPrimary)

/**
 * Nothing loaded (the player is not running, or it was ended): the K on its black tile, "KJB" and "Nothing playing", and
 * Play, which resumes the last queue; the tile opens the player. Its corners drawn by its background on Android 11.
 */
@Composable
private fun Empty(layout: GlowLayout) {
    val context = LocalContext.current
    Row(
        modifier = frame().background(ImageProvider(R.drawable.widget_empty_background)).padding(horizontal = layout.paddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val size = layout.coverDp.dp
        Box(
            modifier = GlanceModifier.size(size).cornerRadius(layout.coverCornerDp.dp).background(Color.Black)
                .clickable(actionStartActivity(opening(context, coverDestination(WidgetState.Empty)))),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_brand_k), context.getString(R.string.widget_open_player), GlanceModifier.size(size / 2))
        }
        Column(modifier = GlanceModifier.defaultWeight().padding(horizontal = 12.dp)) {
            Text(context.getString(R.string.app_name), style = TextStyle(color = EmptyText, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(context.getString(R.string.playing_idle), style = TextStyle(color = EmptyTextSoft, fontSize = 13.sp), maxLines = 1)
        }
        widgetButtons(WidgetState.Empty, layout).forEach { Transport(it, background = EmptyPlay, content = EmptyOnPlay) }
    }
}

// The buttons' actions

private val COMMAND = ActionParameters.Key<String>("command")
private val SONG_PATH = ActionParameters.Key<String>("path")
private val SONG_ID = ActionParameters.Key<Long>("id")
private val FAVOURITE = ActionParameters.Key<Boolean>("favourite")

/**
 * A transport button: its command to the session through a MediaController, which starts the playback service when it
 * does not run (a tap on a widget may start a foreground service). The controller is let go only once the player
 * shows it took the command, or after a short wait: Play keeps it until the player plays, so on a cold start the
 * service is in the foreground before it is unbound (and on nothing loaded, Play resumes the last queue).
 */
class WidgetTransport : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = WidgetCommand.of(parameters[COMMAND]) ?: return
        withContext(Dispatchers.Main) {
            // A receiver's context may not bind a service; the app's may.
            val controller = connect(context.applicationContext) ?: return@withContext
            try {
                val took = withTimeoutOrNull(if (command == WidgetCommand.PLAY) START_MS else DELIVERED_MS) { send(controller, command) } != null
                if (command == WidgetCommand.PLAY && took) delay(SETTLE_MS)
            } finally {
                controller.release()
            }
        }
    }

    private suspend fun connect(context: Context): MediaController? {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        return suspendCancellableCoroutine<MediaController?> { done ->
            future.addListener({
                // Cancelled meanwhile: releaseFuture lets the controller go.
                if (!done.isActive) return@addListener
                try {
                    done.resume(future.get())
                } catch (e: ExecutionException) {
                    Log.w(LOG_TAG, "widget: no connection to the player (${e.cause?.javaClass?.simpleName})")
                    done.resume(null)
                }
            }, ContextCompat.getMainExecutor(context))
            done.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
    }

    /** [command] sent, until the player shows it took it ([WidgetCommand.took]); the listener is in place before it is sent. */
    private suspend fun send(controller: MediaController, command: WidgetCommand) = suspendCancellableCoroutine<Unit> { done ->
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (command.took(player.isPlaying, events::contains) && done.isActive) {
                    player.removeListener(this)
                    done.resume(Unit)
                }
            }
        }
        controller.addListener(listener)
        command.sendTo(controller)
        // Play on a player that plays already.
        if (command == WidgetCommand.PLAY && controller.isPlaying) {
            controller.removeListener(listener)
            done.resume(Unit)
        }
    }

    private companion object {
        /** Long enough for a cold start: the queues and the library read before the last queue resumes. */
        const val START_MS: Long = 5_000

        /** Pause, Next and Previous: the session answers within this, or there was nothing to do (Pause while paused). */
        const val DELIVERED_MS: Long = 1_000

        /** Media3 puts the service in the foreground just after the player starts, on the main thread: a moment to let it. */
        const val SETTLE_MS: Long = 500
    }
}

/** The heart: the song shown into Favourites, or out of them; the playback service pushes the new heart. */
class WidgetHeart : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val path = parameters[SONG_PATH] ?: return
        val favourite = parameters[FAVOURITE] ?: return
        val stored = LibraryGraph.favourites(context.applicationContext).setSongs(listOf(SongRef(path, parameters[SONG_ID])), !favourite)
        if (!stored) Log.w(LOG_TAG, "widget: the heart could not be stored")
        // A widget left from before a kill: no player pushes, so it is drawn again (the empty state).
        if (!Widgets.playerAlive) Widgets.refresh(context.applicationContext)
    }
}
