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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.action.mutableActionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
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
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.MainActivity
import app.lyrify.ui.heartLabel
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
import kotlin.math.roundToInt

/**
 * What the widgets draw: [state], and its own cover read once and sized to each widget's slot ([small], [large]), null
 * without one (KJB's default cover is drawn). [coverOf] is the cover it was read for ([WidgetState.Loaded.coverKey]: its
 * path, and its version once Covers & tags changed it): null while no widget is placed, or after a read that failed, so
 * the next update reads it again. [noArtAt] is when a read
 * found no art for it (SystemClock.elapsedRealtime), until it is read once more ([NO_ART_RECHECK_MS]); else null.
 */
internal class Shown(val state: WidgetState, val small: Bitmap? = null, val large: Bitmap? = null, val coverOf: String? = null, val noArtAt: Long? = null)

/** A cover read for the widgets ([Widgets.withCover]): its own, or none; a read that failed gives null. */
internal sealed interface CoverRead {
    /** A song's embedded art, or the cover of a file another app opened, sized to the small and the large slot. */
    class Own(val small: Bitmap, val large: Bitmap) : CoverRead

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
        if (coverToRead(state, before.coverOf, before.noArtAt, now) == null) return Shown(state, before.small, before.large, key, before.noArtAt)
        return when (val cover = read(path)) {
            null -> Shown(state)
            is CoverRead.Own -> Shown(state, cover.small, cover.large, key)
            // The same cover as before: this was the read once more, so no art stays for the song.
            CoverRead.NoArt -> Shown(state, coverOf = key, noArtAt = now.takeIf { key != before.coverOf })
        }
    }

    /**
     * The own cover at [path] (a song's, or a file another app opened), read once at the uri the session and the car
     * have for it, in this process as the notification reads it ([CarCovers.read]); sized to the small and the large
     * slot on this screen. [CoverRead.NoArt] when it has none, null when it could not be read now.
     */
    private suspend fun thumbnails(context: Context, path: String): CoverRead? = withContext(Dispatchers.IO) {
        val density = context.resources.displayMetrics.density
        CarCovers.read(context, CarCovers.uris(context).of(path), { own ->
            CoverRead.Own(fitted(own, coverPx(WidgetSize.SMALL, density)), fitted(own, coverPx(WidgetSize.LARGE, density)))
        }) { CoverRead.NoArt }
    }

    /** [bitmap] scaled down to fit [px] square. */
    private fun fitted(bitmap: Bitmap, px: Int): Bitmap {
        val scale = px.toFloat() / maxOf(bitmap.width, bitmap.height)
        return if (scale >= 1f) bitmap else bitmap.scale((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
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

/** "KJB", the small widget (4×1): cover, title, Play or Pause, Next. */
class SmallWidget : GlanceAppWidget() {
    // The state lives in memory ([Widgets]), not in a file per widget.
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideWidget(context, WidgetSize.SMALL)
}

/** "KJB large", the large widget (4×2): cover, title, artist, Previous, Play or Pause, Next, and the heart. */
class LargeWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideWidget(context, WidgetSize.LARGE)
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallWidget()
}

class LargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LargeWidget()
}

/**
 * The widget drawn from [Widgets.shown], and again whenever it changes while Glance keeps the widget's session. Its
 * colours come from the wallpaper (Android 12 and later).
 */
private suspend fun GlanceAppWidget.provideWidget(context: Context, size: WidgetSize): Nothing {
    Widgets.reconcile(context)
    provideContent {
        val shown by Widgets.shown.collectAsState()
        GlanceTheme {
            when (size) {
                WidgetSize.SMALL -> Small(shown)
                WidgetSize.LARGE -> Large(shown)
            }
        }
    }
}

@Composable
private fun Small(shown: Shown) {
    val state = shown.state
    Row(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(shown, WidgetSize.SMALL)
        Text(
            text = title(state),
            modifier = GlanceModifier.defaultWeight().padding(horizontal = 12.dp),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        widgetButtons(state, WidgetSize.SMALL).forEach { Transport(it) }
    }
}

@Composable
private fun Large(shown: Shown) {
    val state = shown.state
    Row(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(shown, WidgetSize.LARGE)
        Column(modifier = GlanceModifier.defaultWeight().padding(start = 12.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(title(state), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    (state as? WidgetState.Loaded)?.artist?.let {
                        Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp), maxLines = 1)
                    }
                }
                if (state is WidgetState.Loaded) Heart(state)
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                widgetButtons(state, WidgetSize.LARGE).forEach { button ->
                    Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) { Transport(button) }
                }
            }
        }
    }
}

@Composable
private fun title(state: WidgetState): String = when (state) {
    is WidgetState.Loaded -> state.title
    WidgetState.Empty -> LocalContext.current.getString(R.string.app_name)
}

/** The song's cover, KJB's default cover for a song without one, or the K on its black tile with nothing loaded; a tap opens the player. */
@Composable
private fun Cover(shown: Shown, slot: WidgetSize) {
    val context = LocalContext.current
    val label = context.getString(R.string.widget_open_player)
    val open = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_DESTINATION, coverDestination(shown.state))
    val size = slot.coverDp.dp
    val modifier = GlanceModifier.size(size).cornerRadius(12.dp).clickable(actionStartActivity(open))
    val cover = if (slot == WidgetSize.SMALL) shown.small else shown.large
    if (cover != null) {
        Image(ImageProvider(cover), label, modifier, contentScale = ContentScale.Crop)
    } else if (shown.state is WidgetState.Loaded) {
        Image(ImageProvider(R.drawable.kjb_default_cover), label, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_brand_k), label, GlanceModifier.size(size / 2))
        }
    }
}

/** A transport button; Play or Pause stands out in the wallpaper's main colour. */
@Composable
private fun Transport(button: WidgetButton) {
    val main = button.command == WidgetCommand.PLAY || button.command == WidgetCommand.PAUSE
    CircleIconButton(
        imageProvider = ImageProvider(button.icon),
        contentDescription = LocalContext.current.getString(button.label),
        onClick = actionRunCallback<WidgetTransport>(actionParametersOf(COMMAND to button.command.name)),
        modifier = GlanceModifier.size(44.dp),
        backgroundColor = if (main) GlanceTheme.colors.primary else null,
        contentColor = if (main) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onSurface,
    )
}

@Composable
private fun Heart(state: WidgetState.Loaded) {
    val parameters = mutableActionParametersOf(SONG_PATH to state.song.path, FAVOURITE to state.favourite)
    state.song.mediaStoreId?.let { parameters[SONG_ID] = it }
    CircleIconButton(
        imageProvider = ImageProvider(if (state.favourite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite),
        contentDescription = LocalContext.current.getString(heartLabel(state.favourite)),
        onClick = actionRunCallback<WidgetHeart>(parameters),
        modifier = GlanceModifier.size(40.dp),
        backgroundColor = null,
        contentColor = if (state.favourite) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
    )
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

/** The large widget's heart: the song shown into Favourites, or out of them; the playback service pushes the new heart. */
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
