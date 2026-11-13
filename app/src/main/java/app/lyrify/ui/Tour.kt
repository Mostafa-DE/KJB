package app.lyrify.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import app.lyrify.R
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibraryTab
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The tour's rules, pure so they are tested on the JVM

/**
 * The tour's stops in order. [step] is the number on the card ("2 of 5"): Play a song has two stops, a song and then
 * the Now bar. The cut-out is the target grown by [grow] dp, its corners [corner] dp ([PILL]: round ends).
 */
enum class TourStop(val step: Int, @StringRes val title: Int, @StringRes val body: Int, val grow: Int, val corner: Int) {
    TABS(1, R.string.tour_tabs_title, R.string.tour_tabs_body, grow = 0, corner = 24),
    SONG(2, R.string.tour_play_title, R.string.tour_play_body, grow = 0, corner = 18),
    NOW_BAR(2, R.string.tour_play_title, R.string.tour_play_body, grow = 4, corner = PILL),
    LYRICS(3, R.string.tour_lyrics_title, R.string.tour_lyrics_body, grow = 4, corner = 32),
    SEARCH(4, R.string.tour_search_title, R.string.tour_search_body, grow = 4, corner = 22),
    TOOLS(5, R.string.tour_tools_title, R.string.tour_tools_body, grow = 4, corner = 22),
}

/** The steps the card counts. */
const val TOUR_STEPS: Int = 5

/** A corner as round as the cut-out allows. */
const val PILL: Int = Int.MAX_VALUE

/**
 * Where the tour is: the [stop] shown, and whether it got there going [forward], so a stop that can't show is passed the
 * same way.
 */
@Immutable
data class TourState(val stop: TourStop = TourStop.TABS, val forward: Boolean = true) {
    /** Next, or Done on the last stop: null, the tour ends. */
    fun next(): TourState? = TourStop.entries.getOrNull(stop.ordinal + 1)?.let { TourState(it, forward = true) }

    /** Back: the stop before; the first has none. */
    fun back(): TourState = TourStop.entries.getOrNull(stop.ordinal - 1)?.let { TourState(it, forward = false) } ?: this

    /** The stop can't show (its target is not on screen): on the same way, forward again from the first; null past the end. */
    fun pass(): TourState? = if (forward || stop.ordinal == 0) next() else back()

    /** Back shows from step 2 on. */
    val hasBack: Boolean get() = stop.step > 1

    /** The last stop: Done instead of Next. */
    val last: Boolean get() = stop.ordinal == TourStop.entries.lastIndex
}

/**
 * The tour while the app runs: its [state] (null when it is not showing) and whether it [ended] in this session
 * (finished, skipped, or declined on the first run's last screen), so it does not open by itself again. Kept across a
 * turn of the phone and process death ([Saver]).
 */
@Stable
class Tour(state: TourState? = null, ended: Boolean = false) {
    var state: TourState? by mutableStateOf(state)
        private set

    var ended: Boolean by mutableStateOf(ended)
        private set

    /** The controls it can point at, as they are composed. */
    val targets: TourTargets = TourTargets()

    /** The shell it walks round ([TourShell]): its navigator, and whether KJB's player has a song for the lyrics step. */
    internal var navigator: Navigator? by mutableStateOf(null)
    internal var ownSong: Boolean by mutableStateOf(false)

    /** From the first stop: on its own once, or again from Settings. */
    fun start() {
        state = TourState()
    }

    /** "Skip tour" on the first run's last screen. */
    fun decline() {
        ended = true
    }

    fun next() = go(state?.next())

    fun back() {
        state = state?.back()
    }

    /** The stop shown can't point at anything: the next one the same way. */
    fun pass() = go(state?.pass())

    /** Skip, or Back on the phone. */
    fun skip() = go(null)

    private fun go(to: TourState?) {
        state = to
        if (to == null) ended = true
    }

    companion object {
        /** The stop's name and the way, or none; then whether it ended. */
        val Saver: Saver<Tour, Any> = Saver(
            save = { tour -> listOf(tour.state?.stop?.name, tour.state?.forward, tour.ended) },
            restore = { saved ->
                val (stop, forward, ended) = saved as List<*>
                val state = TourStop.entries.firstOrNull { it.name == stop }?.let { TourState(it, forward as? Boolean ?: true) }
                Tour(state, ended as? Boolean ?: false)
            },
        )
    }
}

/** The app's [Tour]; null outside the shell (tests, previews), where targets mark nothing. */
val LocalTour = staticCompositionLocalOf<Tour?> { null }

/**
 * Whether the tour opens by itself: once, on a phone that has not [seen] it (a new install after its first run, or a
 * phone updated from an earlier version), once the library has been read ([libraryRead]), so there is something real to
 * point at; not again once it [ended] in this session, nor while it is [showing].
 */
fun tourStarts(seen: Boolean, ended: Boolean, showing: Boolean, libraryRead: Boolean): Boolean = !seen && !ended && !showing && libraryRead

/** What the tour does with a stop's target: show it, scroll it into view first, or pass the stop. */
enum class Aim { SHOW, SCROLL, PASS }

/**
 * A target's [bounds] whole inside [room]: shown. Not whole yet: scrolled into view once; still not whole after that
 * ([scrolled]), or not there at all: the stop is passed. A card never points at nothing.
 */
fun aim(bounds: Rect?, room: Rect, scrolled: Boolean): Aim = when {
    bounds == null || bounds.width <= 0f || bounds.height <= 0f -> Aim.PASS
    room.holds(bounds) -> Aim.SHOW
    !scrolled -> Aim.SCROLL
    else -> Aim.PASS
}

/** Of several targets for one stop (a list's rows), the one to point at: the topmost whole in [room], else the topmost in part. */
fun <T> pickTarget(marks: List<T>, room: Rect, bounds: (T) -> Rect): T? =
    marks.filter { room.holds(bounds(it)) }.minByOrNull { bounds(it).top } ?: marks.filter { bounds(it).overlaps(room) }.minByOrNull { bounds(it).top }

/** [inner] whole inside this, give or take a pixel of rounding. */
private fun Rect.holds(inner: Rect): Boolean =
    inner.left >= left - EPSILON && inner.top >= top - EPSILON && inner.right <= right + EPSILON && inner.bottom <= bottom + EPSILON

private const val EPSILON = 1f

/**
 * Where a stop's target has to be whole: a song between the bottom of Library's tabs ([tabsBottom]) and the top of the
 * Now bar and the dock ([chromeTop]), which cover the list's ends; anything else anywhere in the [window].
 */
fun targetRoom(stop: TourStop, window: Rect, tabsBottom: Float?, chromeTop: Float?): Rect =
    if (stop == TourStop.SONG) Rect(window.left, tabsBottom ?: window.top, window.right, chromeTop ?: window.bottom) else window

/**
 * Where the card goes beside a target, in px: [x] and [width], at most [maxHeight] tall (its body scrolls), [below] the
 * target or above it. [overlaps]: neither side has room for even a small card (a short screen, very large text), so it
 * sits at the screen's edge on the larger side and may cover part of the target.
 */
@Immutable
data class CardRoom(val x: Float, val width: Float, val maxHeight: Float, val below: Boolean, val overlaps: Boolean)

/**
 * The card's room beside [target] in [area] (the window inside the system bars): on the side with more room, [gap] from
 * the target, never over it while a card of [minHeight] fits, at most [MAX_CARD_SHARE] of the area tall. Normally
 * [narrowMax] wide, centred on the target and [edge] from the sides; with large text ([wide]) the area's width less
 * [wideEdge] each side.
 */
fun cardRoom(target: Rect, area: Rect, wide: Boolean, gap: Float, edge: Float, wideEdge: Float, narrowMax: Float, minHeight: Float): CardRoom {
    val width = if (wide) area.width - 2 * wideEdge else min(narrowMax, area.width - 2 * edge)
    val x = if (wide) area.left + wideEdge else (target.center.x - width / 2).coerceIn(area.left + edge, max(area.left + edge, area.right - edge - width))
    val above = target.top - gap - area.top
    val below = area.bottom - (target.bottom + gap)
    val under = below >= above
    val most = area.height * MAX_CARD_SHARE
    val side = if (under) below else above
    return if (side >= minHeight) {
        CardRoom(x, width, min(side, most), below = under, overlaps = false)
    } else {
        CardRoom(x, width, most, below = under, overlaps = true)
    }
}

/** The card's top once it is measured [height] tall in [room]: beside the target, or at the screen's edge when it overlaps. */
fun cardTop(room: CardRoom, target: Rect, area: Rect, gap: Float, height: Float): Float = when {
    room.overlaps && room.below -> area.bottom - height
    room.overlaps -> area.top
    room.below -> target.bottom + gap
    else -> target.top - gap - height
}

/** The card's share of the area at most. */
const val MAX_CARD_SHARE: Float = 0.6f

/** Large text: the phone's font scale over this. The hero drops its lines, the card goes full width, the halo stops. */
const val LARGE_TEXT: Float = 1.3f

/** The dock tab a stop points at, if any. */
fun dockStop(tab: DockTab): TourStop? = when (tab) {
    DockTab.SEARCH -> TourStop.SEARCH
    DockTab.TOOLS -> TourStop.TOOLS
    DockTab.LIBRARY, DockTab.PLAYLISTS -> null
}

// Marking the controls the tour points at

/**
 * Marks a control the tour can point at for [stop] (null: none, so a list can mark only its own rows). While the tour
 * points here, a tap on the cut-out does what a tap on the control does ([onTap], spoken as [label]): a song plays, the
 * bar opens the player. It only registers the control with the shell's [Tour]; nothing is measured until the tour shows.
 */
fun Modifier.tourTarget(stop: TourStop?, label: String? = null, onTap: (() -> Unit)? = null): Modifier =
    if (stop == null) this else this then TourTargetElement(stop, label, onTap)

private data class TourTargetElement(val stop: TourStop, val label: String?, val onTap: (() -> Unit)?) : ModifierNodeElement<TourTargetNode>() {
    override fun create(): TourTargetNode = TourTargetNode(stop, label, onTap)

    override fun update(node: TourTargetNode) {
        node.stop = stop
        node.label = label
        node.onTap = onTap
    }
}

/** A marked control from its first placement until it is detached: where it is now, and a way to bring it into view. */
class TourTargetNode internal constructor(var stop: TourStop, var label: String?, var onTap: (() -> Unit)?) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    LayoutAwareModifierNode {
    private var targets: TourTargets? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        if (targets == null) targets = currentValueOf(LocalTour)?.targets?.also { it.add(this) }
    }

    override fun onDetach() {
        targets?.remove(this)
        targets = null
    }

    /** Its whole bounds in [overlay]'s space; null while it is not laid out. */
    internal fun bounds(overlay: LayoutCoordinates): Rect? {
        if (!isAttached) return null
        val mine = requireLayoutCoordinates()
        if (!mine.isAttached || !overlay.isAttached) return null
        return overlay.localBoundingBoxOf(mine, clipBounds = false)
    }

    /** Scrolls its list until it shows, [around] it as much room as the bars that cover the list's ends take. */
    internal suspend fun reveal(around: (Size) -> Rect) {
        if (isAttached) bringIntoView { around(requireLayoutCoordinates().size.toSize()) }
    }
}

/** The marked controls while they are attached. Main thread only, read once a frame while the tour shows. */
class TourTargets {
    private val nodes = mutableListOf<TourTargetNode>()

    internal fun add(node: TourTargetNode) {
        nodes += node
    }

    internal fun remove(node: TourTargetNode) {
        nodes -= node
    }

    internal fun of(stop: TourStop): List<TourTargetNode> = nodes.filter { it.stop == stop }
}

// The tour over the shell

/**
 * The shell ([content]) with the tour over it. While the tour shows, the shell takes no touch, TalkBack can't reach
 * anything under the dimmed layer, and the shell's Back handlers stand down: the phone's Back skips the tour.
 */
@Composable
fun TourHost(content: @Composable () -> Unit) {
    val tour = LocalTour.current
    val state = tour?.state
    val back = rememberNavigationEventDispatcherOwner(enabled = state == null)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (state != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back) { content() }
        }
        val nav = tour?.navigator
        if (state != null && nav != null) TourLayer(tour, state, nav, tour.ownSong)
    }
}

/** The shell lends the tour its [nav], and says whether KJB's player has a song ([ownSong]). */
@Composable
fun TourShell(nav: Navigator, ownSong: Boolean) {
    val tour = LocalTour.current ?: return
    SideEffect {
        tour.navigator = nav
        tour.ownSong = ownSong
    }
}

/** Where [stop]'s cut-out is, and the control under it. */
private data class Aimed(val stop: TourStop, val hole: Rect, val node: TourTargetNode)

/**
 * The dimmed layer with the cut-out round the stop's control, its ring, and the card on the far side of it. Each stop
 * first goes where its control is (Library's Songs, the player, the dock), then finds it every frame: a control that
 * moves is followed, one off screen is scrolled into view once, and a stop whose control is not there is passed.
 */
@Composable
private fun TourLayer(tour: Tour, state: TourState, nav: Navigator, ownSong: Boolean) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val reduced = LocalReducedMotion.current
    val large = density.fontScale > LARGE_TEXT
    val insets = WindowInsets.safeDrawing
    val song by rememberUpdatedState(ownSong)
    var overlay by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var aimed by remember { mutableStateOf<Aimed?>(null) }
    val hole = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }
    BackHandler { tour.skip() }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(if (reduced) Motion.REDUCED_MS else Motion.DIALOG_MS)) }

    LaunchedEffect(state.stop) {
        if (!nav.arrive(state.stop, song)) {
            tour.pass()
            return@LaunchedEffect
        }
        var scrolled = false
        var since = -1L
        // A control still moving (the player growing out of the Now bar) is judged only once it rests.
        var last: Rect? = null
        var still = 0
        while (true) {
            val now = withFrameMillis { it }
            if (since < 0) since = now
            val space = overlay ?: continue
            val window = with(density) {
                Rect(
                    insets.getLeft(this, direction).toFloat(),
                    insets.getTop(this).toFloat(),
                    space.size.width - insets.getRight(this, direction).toFloat(),
                    space.size.height - insets.getBottom(this).toFloat(),
                )
            }
            fun top(stop: TourStop) = tour.targets.of(stop).mapNotNull { it.bounds(space) }.minOfOrNull { it.top }
            val chromeTop = listOfNotNull(top(TourStop.NOW_BAR), top(TourStop.SEARCH), top(TourStop.TOOLS)).minOrNull()?.let { it - with(density) { CHROME_CLEAR.toPx() } }
            val tabsBottom = tour.targets.of(TourStop.TABS).mapNotNull { it.bounds(space) }.maxOfOrNull { it.bottom }
            val room = targetRoom(state.stop, window, tabsBottom, chromeTop)
            val marks = tour.targets.of(state.stop).mapNotNull { node -> node.bounds(space)?.let { node to it } }
            val mark = pickTarget(marks, room) { it.second }
            still = if (mark?.second == last) still + 1 else 0
            last = mark?.second
            val resting = still >= STILL_FRAMES || now - since > ARRIVE_WAIT_MS
            when (aim(mark?.second, room, scrolled)) {
                Aim.SHOW -> {
                    val (node, bounds) = checkNotNull(mark)
                    val next = spot(state.stop, bounds, window, density)
                    if (aimed?.stop != state.stop || aimed?.hole != next || aimed?.node !== node) aimed = Aimed(state.stop, next, node)
                    since = now
                }
                Aim.SCROLL -> if (resting) {
                    scrolled = true
                    val above = (room.top - window.top).coerceAtLeast(0f)
                    val below = (window.bottom - room.bottom).coerceAtLeast(0f)
                    checkNotNull(mark).first.reveal { size -> Rect(0f, -above, size.width, size.height + below) }
                }
                // Not there yet (a page still composing, the player still opening): wait a little, then pass the stop.
                Aim.PASS -> if ((mark != null && resting) || now - since > ARRIVE_WAIT_MS) {
                    tour.pass()
                    return@LaunchedEffect
                }
            }
        }
    }
    // The cut-out glides to each new control (it fades instead with reduced motion), and follows one that moves.
    val target = aimed?.hole
    LaunchedEffect(target) {
        target ?: return@LaunchedEffect
        if (!placed || reduced) hole.snapTo(target) else hole.animateTo(target, tween(Motion.OPEN_MS, easing = Motion.Rise))
        placed = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { overlay = it }
            .graphicsLayer { alpha = appear.value },
    ) {
        Scrim(if (placed) hole.value else null, aimed?.stop ?: state.stop, pulse = !reduced && !large)
        // The card and the tap on the cut-out wait until this stop's control is found; the card fades in beside it.
        val shown = aimed?.takeIf { it.stop == state.stop }
        if (placed && shown != null) {
            key(state.stop) {
                val card = remember { Animatable(0f) }
                LaunchedEffect(Unit) { card.animateTo(1f, tween(if (reduced) Motion.REDUCED_MS else Motion.DIALOG_MS, easing = Motion.Rise)) }
                val tap = shown.node.onTap
                if (tap != null) {
                    HitArea(hole.value, shown.node.label) {
                        tap()
                        tour.next()
                    }
                }
                TourCardLayout(hole.value, insets, large, Modifier.graphicsLayer { alpha = card.value }) { TourCard(tour, state) }
            }
        }
    }
}

/**
 * Goes where [stop]'s control shows: Library's Songs with nothing over it, the player for the lyrics, or just the
 * shell's own dock. False when the stop can't show here: the lyrics need a song in KJB's player ([ownSong]).
 */
private fun Navigator.arrive(stop: TourStop, ownSong: Boolean): Boolean {
    if (stop == TourStop.LYRICS) {
        if (!ownSong) return false
        show(FullScreen.Player)
        return true
    }
    state.views.firstOrNull()?.let(::close)
    if (stop == TourStop.TABS || stop == TourStop.SONG) {
        // A tap on Library's icon closes its pages and lands on Songs; once there another would scroll the list up.
        repeat(LIBRARY_STEPS) { if (state.tab != DockTab.LIBRARY || state.pages.isNotEmpty() || state.libraryTab != LibraryTab.SONGS) select(DockTab.LIBRARY) }
    }
    return true
}

/** [stop]'s cut-out round a control at [bounds]: grown by its margin, kept inside the [window]'s sides, rounded. */
private fun spot(stop: TourStop, bounds: Rect, window: Rect, density: Density): Rect = with(density) {
    val grown = bounds.inflate(stop.grow.dp.toPx())
    val margin = SPOT_MARGIN.toPx()
    Rect(max(grown.left, window.left + margin), grown.top, min(grown.right, window.right - margin), grown.bottom)
}

/**
 * The dim over everything but the cut-out [hole], with a ring and a soft band round it, and, when [pulse] is on (motion,
 * and not large text), a halo that breathes. Takes every touch and says nothing to TalkBack.
 */
@Composable
private fun Scrim(hole: Rect?, stop: TourStop, pulse: Boolean) {
    val chrome = LocalChromeColors.current
    val dark = LocalDarkTheme.current
    val dim = chrome.scrim.copy(alpha = if (dark) DARK_DIM else LIGHT_DIM)
    val ring = if (dark) chrome.action else MaterialTheme.colorScheme.surfaceContainerLowest
    val breath = if (pulse) {
        val halo = rememberInfiniteTransition(label = "halo")
        halo.animateFloat(0f, 1f, infiniteRepeatable(tween(HALO_MS / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath").value
    } else {
        0f
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) {
        if (hole == null) {
            drawRect(dim)
            return@Canvas
        }
        val corner = min(stop.corner.toFloat().let { if (stop.corner == PILL) Float.MAX_VALUE else it.dp.toPx() }, min(hole.width, hole.height) / 2)
        val cut = Path().apply { addRoundRect(RoundRect(hole, CornerRadius(corner))) }
        clipPath(cut, ClipOp.Difference) { drawRect(dim) }
        val line = RING.toPx()
        val band = BAND.toPx()
        drawRoundRect(ring, hole.topLeft - Offset(line / 2, line / 2), hole.size.let { Size(it.width + line, it.height + line) }, CornerRadius(corner + line / 2), style = Stroke(line))
        val soft = line + band / 2
        drawRoundRect(ring.copy(alpha = BAND_ALPHA), hole.topLeft - Offset(soft, soft), Size(hole.width + 2 * soft, hole.height + 2 * soft), CornerRadius(corner + soft), style = Stroke(band))
        if (breath > 0f) {
            val out = HALO.toPx() * (HALO_SCALE + (1f - HALO_SCALE) * breath)
            drawRoundRect(ring.copy(alpha = HALO_ALPHA * breath), hole.topLeft - Offset(out, out), Size(hole.width + 2 * out, hole.height + 2 * out), CornerRadius(corner + out), style = Stroke(line))
        }
    }
}

/** The cut-out itself as a button, while the stop's control has its own tap: the song plays, the bar opens the player. */
@Composable
private fun HitArea(hole: Rect, label: String?, onTap: () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(hole.left.roundToInt(), hole.top.roundToInt()) }
            .size(with(density) { hole.width.toDp() }, with(density) { hole.height.toDp() })
            .clickable(role = Role.Button, onClickLabel = label, onClick = onTap)
            .semantics { if (label != null) contentDescription = label },
    )
}

/** The card's place beside the cut-out [hole], inside the window's safe area ([cardRoom]); full width with large text. */
@Composable
private fun TourCardLayout(hole: Rect, insets: WindowInsets, large: Boolean, modifier: Modifier, card: @Composable () -> Unit) {
    val direction = LocalLayoutDirection.current
    Layout(card, modifier.fillMaxSize()) { measurables, constraints ->
        val area = Rect(
            insets.getLeft(this, direction).toFloat(),
            insets.getTop(this).toFloat() + CARD_EDGE_TOP.toPx(),
            constraints.maxWidth - insets.getRight(this, direction).toFloat(),
            constraints.maxHeight - insets.getBottom(this).toFloat() - CARD_EDGE_BOTTOM.toPx(),
        )
        val gap = CARD_GAP.toPx()
        val room = cardRoom(hole, area, large, gap, CARD_EDGE.toPx(), CARD_WIDE_EDGE.toPx(), CARD_WIDTH.toPx(), CARD_MIN_HEIGHT.toPx())
        val width = room.width.roundToInt().coerceAtLeast(0)
        val placeable = measurables.single().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = room.maxHeight.roundToInt().coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.placeRelative(room.x.roundToInt(), cardTop(room, hole, area, gap, placeable.height.toFloat()).roundToInt())
        }
    }
}

/**
 * The step's card: the dots and "2 of 5" (read when they change), Skip ("Skip tour" for TalkBack), the title (which
 * takes the focus on every step, so TalkBack reads it first) and the sentence, then Back from step 2 and Next, or Done on
 * the last. The words scroll when the card is short.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TourCard(tour: Tour, state: TourState) {
    val colors = MaterialTheme.colorScheme
    val chrome = LocalChromeColors.current
    val shape = RoundedCornerShape(28.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .semantics { isTraversalGroup = true }
            .shadow(24.dp, shape, ambientColor = chrome.shadowStrong, spotColor = chrome.shadowStrong)
            .background(chrome.sheet, shape)
            .border(1.dp, colors.onSurface.copy(alpha = CARD_EDGE_ALPHA), shape)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            StepDots(state.stop.step, Modifier.weight(1f))
            val skip = stringResource(R.string.ob_skip_tour)
            LyrifyButton(
                stringResource(R.string.tour_skip),
                onClick = tour::skip,
                modifier = Modifier.semantics { contentDescription = skip },
                kind = ButtonKind.GHOST,
                compact = true,
                contentColor = colors.onSurfaceVariant,
            )
        }
        val title = remember { FocusRequester() }
        LaunchedEffect(Unit) { title.requestFocus() }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(state.stop.title),
                style = display(20, 26, tracking = -0.01, weight = 650),
                modifier = Modifier.focusRequester(title).focusable().semantics {
                    heading()
                    traversalIndex = -1f
                },
            )
            Text(stringResource(state.stop.body), style = body(15f, 22), color = colors.onSurfaceVariant, modifier = Modifier.semantics { traversalIndex = -1f })
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.hasBack) {
                LyrifyButton(stringResource(R.string.tour_back), onClick = tour::back, kind = ButtonKind.SECONDARY, compact = true)
                Spacer(Modifier.weight(1f))
            }
            LyrifyButton(stringResource(if (state.last) R.string.tour_done else R.string.tour_next), onClick = tour::next, compact = true)
        }
    }
}

/** Five dots, the step's one long and in the action colour, and "2 of 5", read when it changes. */
@Composable
private fun StepDots(step: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val action = LocalChromeColors.current.action
    val reduced = LocalReducedMotion.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..TOUR_STEPS).forEach { k ->
                val width by animateDpAsState(if (k == step) 18.dp else 6.dp, motion(tween(Motion.OPEN_MS, easing = Motion.Rise), reduced), label = "dot")
                Box(Modifier.size(width, 6.dp).background(if (k == step) action else colors.onSurface.copy(alpha = DOT_ALPHA), CircleShape))
            }
        }
        Text(
            stringResource(R.string.step_count, step, TOUR_STEPS),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold).tabular(),
            color = colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** How long a stop waits for its control to show (a page composing, the player opening) before it is passed. */
private const val ARRIVE_WAIT_MS = 1200L

/** Frames a control's bounds stay the same before a part of it off screen counts. */
private const val STILL_FRAMES = 3

/** At most this many taps on Library's icon reach Songs at its root, the views closed. */
private const val LIBRARY_STEPS = 3

private const val LIGHT_DIM = 0.6f
private const val DARK_DIM = 0.66f
private val RING: Dp = 2.dp
private val BAND: Dp = 6.dp
private const val BAND_ALPHA = 0.22f
private val HALO: Dp = 10.dp
private const val HALO_SCALE = 0.96f
private const val HALO_ALPHA = 0.45f
private const val HALO_MS = 2200
private val SPOT_MARGIN: Dp = 4.dp

/** A song row clears the Now bar and the dock by this much. */
private val CHROME_CLEAR: Dp = 8.dp

private val CARD_GAP: Dp = 14.dp
private val CARD_EDGE: Dp = 16.dp
private val CARD_WIDE_EDGE: Dp = 12.dp
private val CARD_EDGE_TOP: Dp = 12.dp
private val CARD_EDGE_BOTTOM: Dp = 12.dp
private val CARD_WIDTH: Dp = 300.dp
private val CARD_MIN_HEIGHT: Dp = 160.dp
private const val CARD_EDGE_ALPHA = 0.09f
private const val DOT_ALPHA = 0.18f
