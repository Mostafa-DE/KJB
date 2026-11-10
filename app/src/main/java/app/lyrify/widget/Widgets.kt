package app.lyrify.widget

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
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
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.SongRef
import app.lyrify.logFailures
import app.lyrify.player.PlaybackService
import app.lyrify.player.PlayerGraph
import app.lyrify.player.ServicePart
import app.lyrify.ui.DESTINATION_PLAYER
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.MainActivity
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume

/**
 * What the widgets draw: [state], and its cover decoded once and downsampled, null without one. [coverOf] is the
 * MediaStore id the cover was read for; null while no widget is placed, so none was read.
 */
internal class Shown(val state: WidgetState, val cover: Bitmap? = null, val coverOf: Long? = null)

/**
 * The widgets' state in this process, and the updates pushed to them. In memory only: a fresh process (after a restart
 * of the phone, or once KJB was closed) shows the empty state until the playback service runs again.
 */
internal object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private val lock = Mutex()
    private val current = MutableStateFlow(Shown(WidgetState.Empty))
    val shown: StateFlow<Shown> = current.asStateFlow()

    /** [state] to every KJB widget on the home screen. While none is placed, nothing is drawn and no cover read. */
    suspend fun show(context: Context, state: WidgetState) = lock.withLock {
        val placed = placed(context)
        current.value = if (placed) withCover(context, state, current.value) else Shown(state)
        if (placed) {
            SmallWidget().updateAll(context)
            LargeWidget().updateAll(context)
        }
    }

    /** The playback service stopped: nothing is loaded. */
    fun stopped(context: Context) {
        scope.launch { show(context, WidgetState.Empty) }
    }

    /** A widget is drawn: the cover of the song shown is read if it was not yet (the widget was placed after the song started). */
    suspend fun readCover(context: Context) = lock.withLock {
        current.value = withCover(context, current.value.state, current.value)
    }

    private suspend fun placed(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(SmallWidget::class.java).isNotEmpty() || manager.getGlanceIds(LargeWidget::class.java).isNotEmpty()
    }

    /** [state] with its cover: [before]'s when it was read for the same song, else MediaStore's thumbnail of its embedded art. */
    private suspend fun withCover(context: Context, state: WidgetState, before: Shown): Shown {
        val id = (state as? WidgetState.Loaded)?.cover ?: return Shown(state)
        if (before.coverOf == id) return Shown(state, before.cover, id)
        return Shown(state, thumbnail(context, id), id)
    }

    /** The thumbnail of song [id]'s embedded cover, at most [COVER_PX] square; null without one. */
    private suspend fun thumbnail(context: Context, id: Long): Bitmap? = withContext(Dispatchers.IO) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        try {
            // MediaStore decodes by a whole sample size, so the thumbnail can come back up to twice as large.
            val thumbnail = context.contentResolver.loadThumbnail(uri, Size(COVER_PX, COVER_PX), null)
            val scale = COVER_PX.toFloat() / maxOf(thumbnail.width, thumbnail.height)
            if (scale >= 1f) thumbnail else thumbnail.scale((thumbnail.width * scale).toInt(), (thumbnail.height * scale).toInt())
        } catch (_: IOException) {
            // No embedded cover: the widget shows the K.
            null
        }
    }

    /** The covers are drawn at most 88 dp across; the bitmap goes to the home screen with every update. */
    private const val COVER_PX: Int = 256
}

/**
 * The widgets' part of the playback service: while the service runs, the widgets show the song the player holds,
 * pushed only when the song, the play state or the favourite changes ([widgetStates]); when it stops, the empty state.
 * Playing is what the notification shows: Pause while the player means to play, buffering included.
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
        scope.launch {
            widgetStates(
                book = PlayerGraph.core(context).book,
                library = LibraryGraph.repository(context).state.map { (it as? LibraryState.Ready)?.library },
                playing = playing,
                favourites = LibraryGraph.favourites(context).songs,
                settings = Lyrify.settings(context).settings.map { it.library },
            ).collect { Widgets.show(context, it) }
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
    Widgets.readCover(context)
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
        Cover(shown, 44.dp)
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
        Cover(shown, 88.dp)
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

/** The song's cover, or the K on its black tile; a tap opens the player, or KJB when nothing is loaded. */
@Composable
private fun Cover(shown: Shown, size: Dp) {
    val context = LocalContext.current
    val loaded = shown.state is WidgetState.Loaded
    val label = context.getString(if (loaded) R.string.widget_open_player else R.string.widget_open_app)
    val open = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { if (loaded) putExtra(EXTRA_DESTINATION, DESTINATION_PLAYER) }
    val modifier = GlanceModifier.size(size).cornerRadius(12.dp).clickable(actionStartActivity(open))
    val cover = shown.cover
    if (cover != null) {
        Image(ImageProvider(cover), label, modifier, contentScale = ContentScale.Crop)
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
        contentDescription = LocalContext.current.getString(if (state.favourite) R.string.player_favourite_on else R.string.player_favourite_off),
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
 * does not run (a tap on a widget may start a foreground service). Play keeps the controller until the player plays,
 * so the service is in the foreground before it is unbound; on nothing loaded, Play resumes the last queue.
 */
class WidgetTransport : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = WidgetCommand.of(parameters[COMMAND]) ?: return
        withContext(Dispatchers.Main) {
            // A receiver's context may not bind a service; the app's may.
            val controller = connect(context.applicationContext) ?: return@withContext
            try {
                command.sendTo(controller)
                if (command == WidgetCommand.PLAY && withTimeoutOrNull(START_MS) { started(controller) } != null) delay(SETTLE_MS)
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

    /** Until the player means to play a song it holds: what Media3 waits for before it puts the service in the foreground. */
    private suspend fun started(controller: MediaController) {
        if (playing(controller)) return
        suspendCancellableCoroutine<Unit> { done ->
            controller.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (playing(player) && done.isActive) {
                        player.removeListener(this)
                        done.resume(Unit)
                    }
                }
            })
        }
    }

    private fun playing(player: Player): Boolean =
        player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private companion object {
        /** Long enough for a cold start: the queues and the library read before the last queue resumes. */
        const val START_MS: Long = 5_000

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
    }
}
