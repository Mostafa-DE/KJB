package app.lyrify.ui

import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.Stable
import coil3.request.ImageRequest
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.albumKey
import app.lyrify.library.displayTitle
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.player.Queue
import app.lyrify.player.QueueItem
import app.lyrify.player.Repeat
import app.lyrify.player.sourceLabel
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlayerUiSettings
import app.lyrify.settings.Settings
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

// The player (P1): the flip area (the cover, the lyrics panel on its back), the title and links, the chips, the
// progress bar, the main row and the secondary row near the thumb. P2 and P3 fill the routes declared in PlayerRoute
// and the chips slot; their sheets live in their own files.

// Rules, as pure functions

/** How long a seek button is held before the jump repeats, and how often it repeats then. */
const val HOLD_DELAY_MS: Long = 420
const val HOLD_EVERY_MS: Long = 260

/** The seek button's icon for a jump of [seconds] back or forward ("Skip amounts"); another amount shows the default's. */
@DrawableRes
fun seekIcon(back: Boolean, seconds: Int): Int = when (seconds) {
    5 -> if (back) R.drawable.ic_replay_5 else R.drawable.ic_forward_5
    10 -> if (back) R.drawable.ic_replay_10 else R.drawable.ic_forward_10
    15 -> if (back) R.drawable.ic_replay_15 else R.drawable.ic_forward_15
    30 -> if (back) R.drawable.ic_replay_30 else R.drawable.ic_forward_30
    else -> if (back) R.drawable.ic_replay_30 else R.drawable.ic_forward_10
}

/** How many jumps a seek button held for [heldMs] has made: one on the press, then one every [HOLD_EVERY_MS] after [HOLD_DELAY_MS]. */
fun jumpsWhileHeld(heldMs: Long): Int = if (heldMs < HOLD_DELAY_MS) 1 else 2 + ((heldMs - HOLD_DELAY_MS) / HOLD_EVERY_MS).toInt()

// The cover's swipe (the prototype's coverSwipe), in dp

/** How far a drag goes before it locks to an axis; how far sideways it skips; how far down it closes the player. */
const val SWIPE_LOCK_DP: Float = 12f
const val SWIPE_SKIP_DP: Float = 70f
const val SWIPE_CLOSE_DP: Float = 90f

/** The cover moves this share of the finger's way, and tilts a degree for each [TILT_DP] of it. */
const val COVER_FOLLOW: Float = 0.6f
const val TILT_DP: Float = 40f

/** The axis a drag on the cover keeps once it is clear. */
enum class SwipeAxis { SIDEWAYS, DOWN }

/** A swipe to the left plays the next song, to the right the previous one. */
enum class SkipWay { NEXT, PREVIOUS }

/** What a released swipe does: skip, close the player, or spring back. */
sealed interface SwipeOutcome {
    data class Skip(val way: SkipWay) : SwipeOutcome

    data object Close : SwipeOutcome

    data object Return : SwipeOutcome
}

/** The axis a drag of [dx], [dy] (dp) locks to: sideways past 12 dp and more than down, down past 12 dp and more than sideways; null until then. */
fun swipeAxis(dx: Float, dy: Float): SwipeAxis? = when {
    abs(dx) > SWIPE_LOCK_DP && abs(dx) > abs(dy) -> SwipeAxis.SIDEWAYS
    dy > SWIPE_LOCK_DP && dy > abs(dx) -> SwipeAxis.DOWN
    else -> null
}

/** Where the cover is when the finger has moved [dx]: [COVER_FOLLOW] of the way. */
fun coverShift(dx: Float): Float = dx * COVER_FOLLOW

/** The cover's tilt, in degrees, when the finger has moved [dx] dp. */
fun coverTilt(dx: Float): Float = dx / TILT_DP

/** What letting go after a drag of [dx], [dy] (dp) on [axis] does. */
fun swipeOutcome(axis: SwipeAxis?, dx: Float, dy: Float): SwipeOutcome = when {
    axis == SwipeAxis.SIDEWAYS && dx < -SWIPE_SKIP_DP -> SwipeOutcome.Skip(SkipWay.NEXT)
    axis == SwipeAxis.SIDEWAYS && dx > SWIPE_SKIP_DP -> SwipeOutcome.Skip(SkipWay.PREVIOUS)
    axis == SwipeAxis.DOWN && dy > SWIPE_CLOSE_DP -> SwipeOutcome.Close
    else -> SwipeOutcome.Return
}

/**
 * The song whose cover slides in for a skip [way]: the one Next or Previous plays, as the player decides it (Media3's
 * rules: only Repeat all wraps round; Previous past 3 s starts this song over). Null when no other song comes: the end
 * of the queue, a queue of one, or Previous starting the song over.
 */
fun incoming(queue: Queue, way: SkipWay, repeat: Repeat, positionMs: Long): QueueItem? {
    val items = queue.items
    val wrap = repeat == Repeat.ALL
    val at = when (way) {
        SkipWay.NEXT -> (queue.index + 1).let { if (it < items.size) it else if (wrap) 0 else -1 }
        SkipWay.PREVIOUS -> if (positionMs > PREVIOUS_RESTARTS_MS) -1 else (queue.index - 1).let { if (it >= 0) it else if (wrap) items.lastIndex else -1 }
    }
    return items.getOrNull(at)?.takeIf { it.uid != queue.current?.uid }
}

/** Previous starts the song over once it has played this long (the player's own rule, LyrifyPlayer's MAX_SEEK_TO_PREVIOUS_MS). */
private const val PREVIOUS_RESTARTS_MS = 3_000L

/**
 * How far the cover travels to make way for the next one: the incoming cover waits with its near edge at the screen's
 * edge (half the flip [area], half the cover's [side], plus the player's side margin [edge]), so it slides in from there.
 */
fun coverTravel(area: Float, side: Float, edge: Float): Float = (area + side) / 2 + edge

/** Where the cover settles after [outcome]: a skip whose song [slides] in moves it a full [travel] aside; anything else springs back. */
fun settleShift(outcome: SwipeOutcome, slides: Boolean, travel: Float): Float = when {
    outcome is SwipeOutcome.Skip && slides -> if (outcome.way == SkipWay.NEXT) -travel else travel
    else -> 0f
}

/** What a finished swipe does once its cover has settled: skip, land on the song already playing, or spring back. */
enum class Settled { SKIP, LANDED, RETURN }

/**
 * A swipe that started on the queue item [startedOn] and has settled while [playingNow] plays: it skips only when
 * nothing moved meanwhile. A skip from elsewhere (a headset, the notification) that reached the song sliding in
 * ([coming]) lands it without a second skip; one that went anywhere else sends the cover back.
 */
fun settledSwipe(startedOn: Long, playingNow: Long, coming: Long?): Settled = when (playingNow) {
    startedOn -> Settled.SKIP
    coming -> Settled.LANDED
    else -> Settled.RETURN
}

/** Which song's cover shows beside the moving one: the next one when the cover moves left, the previous one when right. */
fun comingFrom(shift: Float): SkipWay? = when {
    shift < 0f -> SkipWay.NEXT
    shift > 0f -> SkipWay.PREVIOUS
    else -> null
}

/** One name on the line under a song's title: the tag's [text], or null for a missing tag, shown as its placeholder. */
data class MetaName(val text: String?) {
    /** A tag opens its page; a placeholder ("No artist", "No album") does nothing. */
    val tappable: Boolean get() = text != null
}

/** The line under a song's title: "Artist · Album", each as tagged (the same name twice included), or its placeholder. */
data class MetaLine(val artist: MetaName, val album: MetaName)

fun metaLine(artist: String?, album: String?): MetaLine = MetaLine(MetaName(artist?.trim()?.takeIf(String::isNotEmpty)), MetaName(album?.trim()?.takeIf(String::isNotEmpty)))

/** "Tap the cover for lyrics": once, when the player opens on the cover. */
fun flipHintOnOpen(ui: PlayerUiSettings): Boolean = !ui.flipHintShown && !ui.showLyrics

/** The links row and the progress bar each take a 48 dp touch area; this much clear space separates them, chips or not. */
private val LINKS_GAP = 16.dp
private val CHIPS_GAP = 16.dp
internal val TOUCH = 48.dp

fun linksToBarClearance(chips: Boolean): Dp = if (chips) minOf(LINKS_GAP, CHIPS_GAP) else LINKS_GAP

/** What the lyrics panel shows. */
sealed interface PanelLyrics {
    /** Timed lines with text, in time order. */
    data class Synced(val lines: List<LrcLine>) : PanelLyrics

    /** Lyrics without times, line by line. */
    data class Plain(val lines: List<String>) : PanelLyrics

    /** No lyrics yet, and a lookup is under way or due. */
    data object Finding : PanelLyrics

    /** "No lyrics yet", with Search. */
    data object None : PanelLyrics
}

/** The panel for a song whose `.lrc` holds [lrc] (null without one), and whose ledger entry has [status] (null without one). */
fun panelLyrics(lrc: String?, status: LyricsStatus?): PanelLyrics {
    if (lrc != null) {
        val timed = LrcFormat.parseLrclib(lrc).filter { it.text.isNotBlank() }
        if (timed.isNotEmpty()) return PanelLyrics.Synced(timed)
        val plain = lrc.removePrefix("\uFEFF").lines().map(String::trim).filter { it.isNotEmpty() && !HEADER_TAG.matches(it) }
        if (plain.isNotEmpty()) return PanelLyrics.Plain(plain)
        return PanelLyrics.None
    }
    return if (status == null || status == LyricsStatus.WRITING) PanelLyrics.Finding else PanelLyrics.None
}

private val HEADER_TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")

/**
 * The line playing at [positionMs]: the last one that has started, or with others sharing its time the first of them
 * (they play together, [lineState]); -1 before the first.
 */
fun currentLine(lines: List<LrcLine>, positionMs: Long): Int {
    val last = lines.indexOfLast { it.timeMs <= positionMs }
    if (last < 0) return -1
    val time = lines[last].timeMs
    return lines.indexOfFirst { it.timeMs == time }
}

/** A time as the player shows it: "1:42", or "1:02:03" past an hour. */
fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60) else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** KJB's own song for the Now bar: as every surface names it ([displayTitle]), its cover, and how far it is at [now]. */
fun ownNow(ui: PlayerUi?, settings: LibrarySettings, now: Long): OwnNow? {
    if (ui == null) return null
    val path = ui.item.song.path
    return OwnNow(
        title = ui.song?.let { displayTitle(it, settings) } ?: path.substringAfterLast('/'),
        artist = ui.song?.artist,
        album = ui.song?.album,
        cover = SongArt(path),
        playing = ui.playing,
        progress = ringShare(ui.positionAt(now), ui.durationMs),
        path = path,
    )
}

/** Where the player's "⋯", its sleep button and its lyrics panel lead: P2's extras ([PlayerExtrasUi]) and P3's lyrics. */
enum class PlayerRoute {
    ADD_TO,
    SPEED,
    REPEAT_PART,
    BOOKMARK,
    PLAY_ON,
    MAKE_TONE,
    SONG_INFO,
    SLEEP,
    SEARCH_LYRICS,
    LYRICS_TEXT,
}

// The Now bar's own player

/** KJB's own player for the Now bar; also keeps the screens connected to the player while the app is in view. */
@Composable
fun rememberOwnNow(): OwnNowSource {
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    LifecycleStartEffect(connection) {
        connection.attach()
        onStopOrDispose { connection.detach() }
    }
    val store = remember(context) { Lyrify.settings(context) }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val ui by connection.ui.collectAsStateWithLifecycle()
    val now = rememberClock(ui?.playing == true)
    ExtrasToasts()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val shown by rememberUpdatedState(settings.playerUi.recentQueueHintShown)
    // "Your last queue is in Recent", the first time a new queue pushes one there.
    LaunchedEffect(connection) {
        connection.toRecent.collect {
            if (shown) return@collect
            store.update { it.copy(playerUi = it.playerUi.copy(recentQueueHintShown = true)) }
            toasts.show(Toast(resources.getString(R.string.player_recent_hint), R.drawable.ic_history))
        }
    }
    return OwnPlayer(ownNow(ui, settings.library, now), connection)
}

private class OwnPlayer(override val now: OwnNow?, private val connection: PlayerConnection) : OwnNowSource {
    override fun playPause() = connection.playPause()
}

/** Elapsed realtime, ticking while [running], for positions that move on. */
@Composable
internal fun rememberClock(running: Boolean, everyMs: Long = 250): Long {
    val now by produceState(SystemClock.elapsedRealtime(), running) {
        value = SystemClock.elapsedRealtime()
        while (running) {
            delay(everyMs)
            value = SystemClock.elapsedRealtime()
        }
    }
    return now
}

// The player

/** The player, over everything; it closes itself when nothing is loaded, once the queues are read. */
@Composable
fun PlayerView() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val store = remember(context) { Lyrify.settings(context) }
    val settings = store.settings.collectAsStateWithLifecycle(initialValue = null).value
    val ui = connection.ui.collectAsStateWithLifecycle().value
    // On a cold start the queues are read after the player opens: it waits for them, and closes only if nothing loads.
    LaunchedEffect(connection) {
        connection.awaitNothingLoaded()
        nav.close(FullScreen.Player)
    }
    if (ui == null || settings == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val close = { nav.close(FullScreen.Player) }
    val reduced = LocalReducedMotion.current
    val drag = remember(scope) { PlayerDrag(scope) }
    val fling = with(LocalDensity.current) { DRAG_FLING_DP.dp.toPx() }
    val intoBar = LocalContainer.current != null
    val letGo = { drag.release(fling, intoBar, close) }
    // Shown again while it was leaving (opened again at once), the player comes back whole.
    val headed = LocalLayerHeaded.current
    LaunchedEffect(headed) { if (headed) drag.reset() }
    var lyricsRoute by remember { mutableStateOf<PlayerRoute?>(null) }
    val extras = rememberPlayerExtras()
    val route: (PlayerRoute) -> Unit = { route ->
        when (route) {
            // The lyrics panel's Search and "Aa" (LyricsViews.kt).
            PlayerRoute.SEARCH_LYRICS, PlayerRoute.LYRICS_TEXT -> lyricsRoute = route
            else -> extras.route(route, ui, settings.library)
        }
    }
    var queueSheet by remember { mutableStateOf(false) }

    // "Tap the cover for lyrics", once, when the player first opens on the cover.
    LaunchedEffect(Unit) {
        val first = store.settings.first().playerUi
        if (flipHintOnOpen(first)) {
            store.update { it.copy(playerUi = it.playerUi.copy(flipHintShown = true)) }
            toasts.show(Toast(resources.getString(R.string.player_flip_hint), R.drawable.ic_lyrics))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { drag.height = it.height.toFloat() }
            .graphicsLayer {
                // Dragged down, the player follows the finger, shrinking and rounding as it goes ([dragPeek]); with
                // reduced motion it stays put and fades instead, so a drag never closes it unseen.
                if (reduced) {
                    alpha = reducedDragAlpha(drag.offset.value, size.height)
                    return@graphicsLayer
                }
                val peek = dragPeek(drag.offset.value, size.height)
                translationY = peek.shiftY
                scaleX = peek.scale
                scaleY = peek.scale
                shape = RoundedCornerShape(PEEK_CORNER * peek.corner)
                clip = peek.corner > 0f
            }
            .background(MaterialTheme.colorScheme.background),
    ) {
        Backdrop(ui.item.song.path)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            PlayerTopBar(ui, onClose = close, onRoute = route, onDrag = drag::follow, onDragEnd = letGo)
            Column(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 4.dp)) {
                FlipArea(
                    ui = ui,
                    showLyrics = settings.playerUi.showLyrics,
                    onFlip = { lyrics -> scope.launch { store.update { it.copy(playerUi = it.playerUi.copy(showLyrics = lyrics)) } } },
                    onSeek = connection::seekTo,
                    onPlay = connection::play,
                    onNext = connection::next,
                    onPrevious = connection::previous,
                    onDownDrag = drag::follow,
                    onDownEnd = letGo,
                    onRoute = route,
                    modifier = Modifier.weight(1f),
                )
                TitleAndLinks(ui, settings.library)
                PlayerChips(ui, extras)
                Spacer(Modifier.height(LINKS_GAP))
                PartHandles(ui, extras)
                ProgressBar(ui, onSeek = connection::seekTo, marks = { BarMarks(ui) })
                MainRow(ui, settings, connection)
                SecondaryRow(ui, connection, onQueue = { queueSheet = true }, onRoute = route)
            }
        }
        // The message floats down from under the top bar, over the cover, as the prototype's does; the controls stay clear.
        ToastSlot(Modifier.statusBarsPadding().padding(top = PLAYER_BAR + 6.dp).padding(horizontal = 12.dp), float = ToastFloat.DOWN)
    }
    if (queueSheet) QueueSheet(onDismiss = { queueSheet = false })
    lyricsRoute?.let { PlayerLyricsSheet(it, ui) { lyricsRoute = null } }
    PlayerExtrasHost(extras, ui)
}

/**
 * The player dragged down by its top bar or its cover: it follows the finger ([dragPeek]); let go, it closes past a
 * quarter of its [height] or on a fling down ([dragCloses]), else springs back ([Motion.release]). Closing into the Now
 * bar, it lets go of the drag as the container shrinks, so the whole player lands in the bar; falling away, it leaves
 * from where the finger left it ([dragRelease]).
 */
@Stable
private class PlayerDrag(private val scope: CoroutineScope) {
    val offset = Animatable(0f)
    var height = 0f
    private val tracker = VelocityTracker()

    fun follow(change: PointerInputChange, dy: Float) {
        tracker.addPointerInputChange(change)
        change.consume()
        scope.launch { offset.snapTo((offset.value + dy).coerceAtLeast(0f)) }
    }

    fun release(fling: Float, intoBar: Boolean, close: () -> Unit) {
        val velocity = tracker.calculateVelocity().y
        tracker.resetTracking()
        val closes = dragCloses(offset.value, velocity, height, fling)
        if (closes) close()
        val to = dragRelease(closes, intoBar) ?: return
        scope.launch { offset.animateTo(to, if (closes) tween(Motion.OPEN_MS, easing = Motion.Rise) else Motion.release) }
    }

    /** Back to where it rests, as the player shows again. */
    suspend fun reset() {
        if (offset.value != 0f) offset.animateTo(0f, Motion.release)
    }
}

/**
 * Behind the top of the player: the cover blurred into a glow that fades into the page by 60% of the height, one
 * seamless gradient with no edge anywhere (the glow and its tint are masked to nothing, never cut at a box's edge), light
 * or dark. It cross-fades from song to song; it is drawn from a small image (the blur hides it), and only once the player
 * has finished growing out of the Now bar, so the transform never re-blurs on every frame.
 */
@Composable
private fun Backdrop(path: String) {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.background
    val reduced = LocalReducedMotion.current
    val container = LocalContainer.current
    val arriving = container != null && container.visibility.transition.run { currentState != targetState }
    val shown by animateFloatAsState(if (arriving) 0f else 1f, motion(tween(Motion.FADE_IN_MS * 2), reduced), label = "backdrop")
    Crossfade(path, Modifier.fillMaxWidth().fillMaxHeight(BACKDROP_SHARE).graphicsLayer { alpha = shown }, animationSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.OPEN_MS), label = "backdrop") { song ->
        val request = remember(song) { ImageRequest.Builder(context).data(SongArt(song)).size(BACKDROP_PX).build() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // The prototype's tint keeps the top bar readable over any cover.
                    drawRect(Brush.verticalGradient(0f to surface.copy(alpha = 0.72f), 0.3f to surface.copy(alpha = 0.24f), 0.66f to surface.copy(alpha = 0.34f), 1f to surface.copy(alpha = 0.6f)))
                    // Then all of it fades to nothing, smoothly, so the page shows through with no line.
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.4f to Color.Black, 0.7f to Color.Black.copy(alpha = 0.55f), 0.88f to Color.Black.copy(alpha = 0.15f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.5f
                        scaleY = 1.5f
                    }
                    .blur(46.dp, BlurredEdgeTreatment.Unbounded)
                    .alpha(0.7f),
            )
        }
    }
}

/** The chevron, "From Album · Name", and "⋯" with every item of the menu. Dragged down, the player follows ([onDrag]). */
@Composable
private fun PlayerTopBar(ui: PlayerUi, onClose: () -> Unit, onRoute: (PlayerRoute) -> Unit, onDrag: (PointerInputChange, Float) -> Unit, onDragEnd: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PLAYER_BAR)
            .padding(horizontal = 8.dp)
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, dy -> onDrag(change, dy) } },
    ) {
        ChromeIconButton(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.player_close), onClose)
        // Where it plays from, without the time the queue started that the queue lists add.
        val name = ui.queue.name ?: sourceLabel(context, ui.queue.source, ui.queue.items.size)
        Text(
            fromLine(stringResource(R.string.player_from, name), name, MaterialTheme.colorScheme.onSurface),
            style = SourceStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box {
            ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.player_more), { menu = true })
            LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                val song = ui.song
                val pick: (() -> Unit) -> () -> Unit = { action -> { menu = false; action() } }
                MenuRow(stringResource(R.string.player_menu_add_to), R.drawable.ic_playlist_add, pick { onRoute(PlayerRoute.ADD_TO) })
                song?.let(::albumKey)?.let { key -> MenuRow(stringResource(R.string.player_menu_album), R.drawable.ic_album, pick { nav.open(Page.Album(key)) }) }
                song?.artist?.takeIf(String::isNotBlank)?.let { artist -> MenuRow(stringResource(R.string.player_menu_artist), R.drawable.ic_person, pick { nav.open(Page.Artist(artist)) }) }
                MenuRow(stringResource(R.string.player_menu_speed), R.drawable.ic_speed, pick { onRoute(PlayerRoute.SPEED) })
                MenuRow(stringResource(if (rememberPartOn(ui)) R.string.part_stop else R.string.player_menu_ab), R.drawable.ic_repeat, pick { onRoute(PlayerRoute.REPEAT_PART) })
                MenuRow(stringResource(R.string.player_menu_bookmark), R.drawable.ic_bookmark_add, pick { onRoute(PlayerRoute.BOOKMARK) })
                MenuRow(stringResource(R.string.player_menu_play_on), R.drawable.ic_speaker, pick { onRoute(PlayerRoute.PLAY_ON) })
                // KJB's equalizer (AudioScreens.kt); the phone's own sound effects open from inside it.
                MenuRow(stringResource(R.string.player_menu_equalizer), R.drawable.ic_equalizer, pick { nav.show(FullScreen.Equalizer) })
                MenuRow(stringResource(R.string.player_menu_tone), R.drawable.ic_ring_volume, pick { onRoute(PlayerRoute.MAKE_TONE) })
                MenuRow(stringResource(R.string.player_menu_info), R.drawable.ic_info, pick { onRoute(PlayerRoute.SONG_INFO) })
            }
        }
    }
}

/**
 * The cover, and the lyrics panel on its back, in the free space: a tap flips it (a short 3D turn; a crossfade under
 * reduced motion), the "Lyrics" chip and the round cover chip are its visible twins. A sideways swipe on the cover skips
 * (twins: Previous and Next), a swipe down closes the player (twin: the chevron).
 */
@Composable
private fun FlipArea(
    ui: PlayerUi,
    showLyrics: Boolean,
    onFlip: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDownDrag: (PointerInputChange, Float) -> Unit,
    onDownEnd: () -> Unit,
    onRoute: (PlayerRoute) -> Unit,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val turn = remember { Animatable(if (showLyrics) 180f else 0f) }
    LaunchedEffect(showLyrics, reduced) {
        val target = if (showLyrics) 180f else 0f
        if (reduced) turn.snapTo(target) else turn.animateTo(target, tween(Motion.FLIP_MS, easing = Motion.Flip))
    }
    val fade = remember { Animatable(if (showLyrics) 1f else 0f) }
    LaunchedEffect(showLyrics, reduced) { fade.animateTo(if (showLyrics) 1f else 0f, tween(if (reduced) FADE_MS else 0)) }
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 8.dp, maxHeight - 8.dp, 360.dp).coerceAtLeast(72.dp)
        val area = maxWidth
        val lyricsShown = if (reduced) fade.value > 0.5f else turn.value > 90f
        val frontAlpha = if (reduced) 1f - fade.value else 1f
        val backAlpha = if (reduced) fade.value else 1f
        if (!lyricsShown || reduced) {
            Box(
                Modifier.size(side).graphicsLayer {
                    rotationY = if (reduced) 0f else turn.value
                    cameraDistance = CAMERA * density
                    alpha = frontAlpha
                },
            ) { CoverFace(ui, side, area, enabled = !lyricsShown, onFlip = { onFlip(true) }, onNext, onPrevious, onDownDrag, onDownEnd) }
        }
        if (lyricsShown || (reduced && fade.value > 0f)) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    rotationY = if (reduced) 0f else turn.value - 180f
                    cameraDistance = CAMERA * density
                    alpha = backAlpha
                },
            ) { LyricsPanel(ui, enabled = lyricsShown, onFlipBack = { onFlip(false) }, onSeek = onSeek, onPlay = onPlay, onRoute = onRoute) }
        }
    }
}

/** The song sliding in after a completed skip: the cover stays aside until the player plays it. */
private data class Landing(val way: SkipWay, val item: QueueItem)

/**
 * The cover and its swipe ([swipeAxis] to [settleShift]): it follows the finger and tilts; the next or previous song's
 * cover rides in beside it from the screen's edge; past [SWIPE_SKIP_DP] it finishes the way (the prototype's 0.2 s ease)
 * and only then plays that song, with the incoming cover already in place, so nothing jumps. Short of it, or with no
 * song to slide in (Previous starting the song over, the end of the queue), it springs back. A tap flips to the lyrics;
 * down past [SWIPE_CLOSE_DP] closes the player.
 */
@Composable
private fun CoverFace(
    ui: PlayerUi,
    side: Dp,
    area: Dp,
    enabled: Boolean,
    onFlip: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDownDrag: (PointerInputChange, Float) -> Unit,
    onDownEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val chrome = LocalChromeColors.current
    val scope = rememberCoroutineScope()
    val travel = with(density) { coverTravel(area.toPx(), side.toPx(), PLAYER_EDGE.toPx()) }
    val shape = RoundedCornerShape(COVER_CORNER)
    val shadow = with(density) { COVER_SHADOW.toPx() }
    val settle = motion(tween<Float>(Motion.SETTLE_MS, easing = Motion.Ease), reduced)
    // The cover's place in px: the finger's while it drags, then the settling animation's.
    val shift = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var landing by remember { mutableStateOf<Landing?>(null) }
    val arrived = landing?.item?.uid == ui.item.uid
    LaunchedEffect(arrived) {
        if (arrived) {
            shift.snapTo(0f)
            landing = null
        }
    }
    // A song the player did not move to (one it could not play): the cover comes back after a moment.
    LaunchedEffect(landing) {
        if (landing == null) return@LaunchedEffect
        delay(LANDING_WAIT_MS)
        landing = null
        shift.animateTo(0f, settle)
    }
    val latest by rememberUpdatedState(ui)
    val actions by rememberUpdatedState(listOf(onNext, onPrevious))
    val place = { if (arrived) 0f else dragging ?: shift.value }
    // Which side the cover is moving to changes rarely; reading it so does not recompose on every pixel.
    val moving by remember { derivedStateOf { comingFrom(dragging ?: shift.value) } }
    val way = if (arrived) null else landing?.way ?: moving
    val beside = remember(moving, ui.item.uid, ui.queue, ui.repeat) {
        moving?.let { incoming(ui.queue, it, ui.repeat, ui.positionAt(SystemClock.elapsedRealtime())) }
    }
    val neighbour = if (arrived) null else landing?.item ?: beside
    val label = stringResource(R.string.player_cover, ui.song?.title ?: ui.item.song.path.substringAfterLast('/'))
    Box(Modifier.fillMaxSize()) {
        if (neighbour != null && way != null) {
            val from = if (way == SkipWay.NEXT) travel else -travel
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = place() + from
                        shadowElevation = shadow
                        this.shape = shape
                        ambientShadowColor = chrome.shadowStrong
                        spotShadowColor = chrome.shadowStrong
                    },
            ) { SongCover(SongArt(neighbour.song.path), size = side, shape = shape) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val x = place()
                    translationX = x
                    rotationZ = coverTilt(x / COVER_FOLLOW / density.density)
                    shadowElevation = shadow
                    this.shape = shape
                    ambientShadowColor = chrome.shadowStrong
                    spotShadowColor = chrome.shadowStrong
                }
                .clip(shape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedOn = latest.item.uid
                        val start = shift.value
                        scope.launch { shift.stop() }
                        var axis: SwipeAxis? = null
                        var moved = Offset.Zero
                        var lifted = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                lifted = true
                                break
                            }
                            moved += change.positionChange()
                            if (axis == null) axis = swipeAxis(moved.x / density.density, moved.y / density.density)
                            if (axis != null) change.consume()
                            if (axis == SwipeAxis.SIDEWAYS) dragging = start + coverShift(moved.x)
                            // Down: the whole player follows the finger.
                            if (axis == SwipeAxis.DOWN) onDownDrag(change, change.positionChange().y)
                        }
                        if (axis == SwipeAxis.DOWN) {
                            onDownEnd()
                            return@awaitEachGesture
                        }
                        val outcome = if (lifted) swipeOutcome(axis, moved.x / density.density, moved.y / density.density) else SwipeOutcome.Return
                        val from = dragging ?: shift.value
                        val now = latest
                        val coming = (outcome as? SwipeOutcome.Skip)?.let { incoming(now.queue, it.way, now.repeat, now.positionAt(SystemClock.elapsedRealtime())) }
                        val (next, previous) = actions
                        scope.launch {
                            shift.snapTo(from)
                            dragging = null
                            shift.animateTo(settleShift(outcome, slides = coming != null, travel = travel), settle)
                            if (outcome !is SwipeOutcome.Skip) return@launch
                            // The song changes only now, with its cover in place, and only if nothing else moved it meanwhile.
                            when (settledSwipe(startedOn, latest.item.uid, coming?.uid)) {
                                Settled.SKIP -> {
                                    if (coming != null) landing = Landing(outcome.way, coming)
                                    if (outcome.way == SkipWay.NEXT) next() else previous()
                                }
                                Settled.LANDED -> landing = coming?.let { Landing(outcome.way, it) }
                                Settled.RETURN -> shift.animateTo(0f, settle)
                            }
                        }
                    }
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onFlip)
                .semantics { contentDescription = label },
        ) {
            SongCover(SongArt(ui.item.song.path), containerCover(LocalContainer.current), size = side, shape = shape)
        }
        FlipChip(Modifier.align(Alignment.BottomEnd).padding(12.dp), enabled, onFlip)
    }
}

/** "From [name]" with the name in semibold on-surface, as the prototype's .src b. */
private fun fromLine(text: String, name: String, nameColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val at = text.lastIndexOf(name)
    if (at >= 0) addStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold), at, at + name.length)
}

/** The "Lyrics" chip on the cover's corner: the visible twin of a tap on the cover. */
@Composable
private fun FlipChip(modifier: Modifier, enabled: Boolean, onFlip: () -> Unit) {
    val chrome = LocalChromeColors.current
    val label = stringResource(R.string.player_show_lyrics)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .heightIn(min = 36.dp)
            .shadow(6.dp, CircleShape, ambientColor = chrome.shadow, spotColor = chrome.shadow)
            .clip(CircleShape)
            .background(chrome.glassSolid, CircleShape)
            .border(1.dp, chrome.glassEdge, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onFlip)
            .semantics { contentDescription = label }
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_lyrics), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.player_lyrics_chip), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The back of the cover: synced lines that scroll with the current one lit (a tap jumps there), plain lyrics as still
 * text, or the state ("Finding lyrics", "No lyrics yet" with Search). Its header holds the status pill, "Aa" and
 * "Full screen"; the round cover chip flips back. The `.lrc` next to the song is read off the main thread, again
 * whenever its ledger entry changes.
 */
@Composable
private fun LyricsPanel(ui: PlayerUi, enabled: Boolean, onFlipBack: () -> Unit, onSeek: (Long) -> Unit, onPlay: () -> Unit, onRoute: (PlayerRoute) -> Unit) {
    val nav = LocalNavigator.current
    val path = ui.item.song.path
    val entry = LocalLyrics.current.ledger.entries[path]
    val lyrics = rememberSongLyrics(path, entry)
    val now = rememberClock(ui.playing)
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp)) {
            when {
                lyrics == PanelLyrics.Finding -> StatusPill(stringResource(R.string.player_finding_lyrics), tone = null, leading = { BrandGlyph(width = 12.dp, sweeping = true) })
                entry != null -> StatusPill(entry.status)
            }
            Spacer(Modifier.weight(1f))
            AaButton(enabled) { onRoute(PlayerRoute.LYRICS_TEXT) }
            LyrifyButton(stringResource(R.string.player_full_screen), { nav.show(FullScreen.FullLyrics) }, kind = ButtonKind.GHOST, icon = R.drawable.ic_open_in_full, compact = true, enabled = enabled)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val shown = lyrics) {
                is PanelLyrics.Synced -> SyncedLyrics(shown.lines, ui.positionAt(now), enabled, PanelLine, PanelFrame) { line ->
                    val tap = lineTap(line, ui.playing)
                    onSeek(tap.seekMs)
                    if (tap.play) onPlay()
                }
                is PanelLyrics.Plain -> StillLyrics(shown.lines, PanelStill, PaddingValues(start = 22.dp, end = 22.dp, bottom = 64.dp))
                PanelLyrics.Finding -> PanelState(stringResource(R.string.player_finding_lyrics), action = null)
                PanelLyrics.None -> PanelState(stringResource(R.string.player_no_lyrics)) { onRoute(PlayerRoute.SEARCH_LYRICS) }
                null -> Unit
            }
            CoverChip(ui, Modifier.align(Alignment.BottomEnd).padding(10.dp), enabled, onFlipBack)
        }
    }
}

@Composable
private fun PanelState(title: String, action: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        BrandGlyph(width = 28.dp, sweeping = action == null)
        Text(title, style = LyricsStateTitle, textAlign = TextAlign.Center)
        if (action != null) LyrifyButton(stringResource(R.string.player_search_lyrics), action, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_search, compact = true)
    }
}

/** The round cover chip in the panel's corner: the visible twin of the flip back. */
@Composable
private fun CoverChip(ui: PlayerUi, modifier: Modifier, enabled: Boolean, onFlipBack: () -> Unit) {
    val chrome = LocalChromeColors.current
    val label = stringResource(R.string.player_show_cover, ui.song?.title ?: ui.item.song.path.substringAfterLast('/'))
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(TOUCH)
            .clip(CircleShape)
            .background(chrome.glassSolid, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onFlipBack)
            .semantics { contentDescription = label },
    ) { SongCover(SongArt(ui.item.song.path), size = 36.dp, shape = CircleShape) }
}

/**
 * The title, then "Artist · Album" on one line, 48 dp tall to touch: each tag opens its page with a press tint, in the
 * secondary ink and without an underline; a missing tag shows its placeholder ("No artist", "No album") in the quieter
 * ink and does nothing ([metaLine]). A song that is not on the phone says so.
 */
@Composable
private fun TitleAndLinks(ui: PlayerUi, settings: LibrarySettings) {
    val nav = LocalNavigator.current
    val song = ui.song
    Text(
        song?.let { displayTitle(it, settings) } ?: ui.item.song.path.substringAfterLast('/'),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH)) {
        if (song == null) {
            PlayerLink(stringResource(R.string.player_not_on_phone), onClick = null, Modifier)
            return@Row
        }
        val meta = metaLine(song.artist, song.album)
        val artist = song.artist
        PlayerLink(meta.artist.text ?: stringResource(R.string.player_no_artist), artist?.takeIf { meta.artist.tappable }?.let { { nav.open(Page.Artist(it)) } }, Modifier.weight(1f, fill = false))
        Text("·", style = MetaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        val key = albumKey(song)
        PlayerLink(meta.album.text ?: stringResource(R.string.player_no_album), key?.takeIf { meta.album.tappable }?.let { { nav.open(Page.Album(it)) } }, Modifier.weight(1f, fill = false))
    }
}

/** One name of the line under the title: a tappable tag in the secondary ink, or a placeholder in the quieter one. */
@Composable
private fun PlayerLink(text: String, onClick: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        // At least 48 dp to touch whatever room the line is given; the words look the same.
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = TOUCH)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text,
            style = if (onClick != null) MetaStyle.copy(fontWeight = FontWeight.Medium) else MetaStyle,
            color = if (onClick != null) colors.onSurfaceVariant else colors.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The chips row (speed, A-B) while one is active (P2); it keeps [CHIPS_GAP] above the progress bar's touch area. */
@Composable
private fun PlayerChips(ui: PlayerUi, extras: PlayerExtrasUi) = ExtrasChips(ui, extras)

private const val FADE_MS = 220
private const val CAMERA = 16f

/** The source line (.src, 13/18) and the artist and album line (.pmeta, 15/22). */
private val SourceStyle = body(13f, 18)
private val MetaStyle = body(15f, 22)

/** The player's top bar: the chevron, "From ...", and "⋯". */
private val PLAYER_BAR = 64.dp

/** The backdrop's height, as a share of the player's: by there the glow has faded into the page. */
private const val BACKDROP_SHARE = 0.6f

/** A blurred backdrop's cover, in px: blurred some 50 dp, a larger one would only cost decoding and upload time. */
internal const val BACKDROP_PX = 192

/** The player's side margin, past which the next cover waits. */
private val PLAYER_EDGE = 24.dp
private val COVER_CORNER = 28.dp

/** The prototype's cover shadow (0 10px 18px -10px, the strong shadow). */
private val COVER_SHADOW = 10.dp

/** How long a completed skip waits for the player to play the song before the cover comes back. */
private const val LANDING_WAIT_MS = 1_500L
