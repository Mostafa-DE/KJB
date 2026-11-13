package app.lyrify.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

// Top tabs that swipe (phone test 2026-11-11, points 2 and 4), as Samsung Music's: Library's, Favourites', and Covers and
// tags' once it adopts them. The pages follow the finger left and right, the tabs' sung line follows the pages, a tap
// glides there, and every tab's page stays composed once the pager has settled in, so going back to a tab finds its
// list where it was, never read or built again. A drag inside a page that is the page's own (a row's swipe, a list
// scrolling sideways) wins over the pager, as Compose gives a gesture to the innermost one first; the system's back
// gesture starts at the screen's edge, outside the pager's reach.

// The pure rules

/** Where the pill under the top tabs is drawn: from [start] (px from the row's start), [width] px wide. */
@Immutable
data class PillSpan(val start: Float, val width: Float)

/**
 * The pill under top tabs whose pages are at [position] (the page shown plus the fraction of the way to the next): on
 * the tab when the pages rest; between two tabs, its start and its width each the same share of the way from the one's
 * to the other's, so it slides and stretches with the finger. [start] and [width] give each tab's, null while a tab is
 * not laid out, and then there is no pill this frame.
 */
fun tabPill(position: Float, start: (Int) -> Float?, width: (Int) -> Float?): PillSpan? {
    val from = floor(position).toInt()
    val way = position - from
    val fromStart = start(from) ?: return null
    val fromWidth = width(from) ?: return null
    if (way <= 0f) return PillSpan(fromStart, fromWidth)
    val toStart = start(from + 1) ?: return null
    val toWidth = width(from + 1) ?: return null
    return PillSpan(fromStart + (toStart - fromStart) * way, fromWidth + (toWidth - fromWidth) * way)
}

/**
 * How much of the pill's ink the label of the tab at [index] takes with the pages at [position]: all of it under the
 * resting pill, none a whole tab away, by the distance in between; so no frame shows a dark label on the pill.
 */
fun tabInkShare(position: Float, index: Int): Float = (1f - abs(position - index)).coerceIn(0f, 1f)

/** The top tabs' colours: the [pill] (the filled buttons' ink), the label [on] it, and a label [off] it. */
@Immutable
data class TabColors(val pill: Color, val on: Color, val off: Color)

fun tabColors(scheme: ColorScheme, chrome: ChromeColors): TabColors = TabColors(pill = chrome.action, on = chrome.onAction, off = scheme.onSurfaceVariant)

/** A label's ink taking [share] of the pill's ([tabInkShare]). */
fun tabInk(colors: TabColors, share: Float): Color = lerp(colors.off, colors.on, share)

/** The pill's height, and each tab's padding at its sides (the mock's). */
val TAB_PILL_HEIGHT = 36.dp

val TAB_PADDING = 16.dp

/** How the pages go to a tab chosen elsewhere: not at all, gliding there, or there at once. */
enum class TabMove { NONE, GLIDE, JUMP }

/**
 * The pages, showing or going to [current], asked for [target] (-1: a tab not shown): they glide there while the page
 * is [onScreen] (Library's icon tapped again on its root lands on Songs), and are simply there when it was not (the same
 * icon tapped from another dock destination): nothing moves behind the page as it fades in. The tab the pages last
 * [reported] themselves (a settle, a tap) coming back is no move: it never pulls them back from a newer flick.
 */
fun tabMove(target: Int, reported: Int, current: Int, onScreen: Boolean): TabMove = when {
    target < 0 || target == current || target == reported -> TabMove.NONE
    onScreen -> TabMove.GLIDE
    else -> TabMove.JUMP
}

/**
 * Where the tab pages go once a finger lets go of them at [position] (the page shown plus the fraction of the way to the
 * next), moving at [velocity] px/s toward the next tabs (positive) or the previous ones. A flick of at least
 * [minVelocity], however short, moves one tab on from where the pages were, or from the tab a settle still running was
 * going to ([heading]), so a second flick during the settle moves on again; slower, the nearer tab. A finger that
 * caught settling pages and did not drag them ([dragged] false: a tap, or a list scrolled up or down) stops them on the
 * nearer tab, as Android's ViewPager does. Never past either end of [count] tabs.
 */
fun tabFlingPage(position: Float, velocity: Float, minVelocity: Float, heading: Int?, dragged: Boolean, count: Int): Int {
    val page = when {
        !dragged || abs(velocity) < minVelocity -> position.roundToInt()
        velocity > 0f -> (heading ?: floor(position).toInt()) + 1
        else -> (heading ?: ceil(position).toInt()) - 1
    }
    return page.coerceIn(0, (count - 1).coerceAtLeast(0))
}

/**
 * Whether pages restored between two tabs (the phone turned, or KJB restarted, mid-swipe: the pager keeps its fraction)
 * must land on a whole tab: they are [offsetFraction] off one and nothing is moving them ([scrolling]).
 */
fun tabNeedsLanding(offsetFraction: Float, scrolling: Boolean): Boolean = !scrolling && abs(offsetFraction) > LANDING_SLACK

/**
 * How firmly a tab's glide takes the pages: over a finger holding them ([fingerOnPages]), so a tap on a tab wins over
 * the drag and its fling; else as any animation, so a finger can still catch the glide.
 */
fun tabGlidePriority(fingerOnPages: Boolean): MutatePriority = if (fingerOnPages) MutatePriority.PreventUserInput else MutatePriority.Default

/** The tab one step [forward] (toward the next tabs) or back from [current], for TalkBack's scroll actions; null past either end of [count]. */
fun tabStep(forward: Boolean, current: Int, count: Int): Int? = (if (forward) current + 1 else current - 1).takeIf { it in 0 until count }

/**
 * Whether a finger landing at [x] (px in a window [width] px wide) is in the system's gesture edges ([start], [end]
 * px wide; none with three-button navigation): there it is the system's back, never a tab flick.
 */
fun inGestureEdge(x: Float, width: Float, start: Float, end: Float): Boolean = x < start || x > width - end

/** How the pages settle on a tab: one spring without overshoot; with reduced motion they jump there (null). */
fun tabSettleSpec(reduced: Boolean): AnimationSpec<Float>? = if (reduced) null else TabSettle

/** What the tab pages make of a finger on them: [WAIT] until it has moved past the slop, then [TAKE] it, or [LEAVE] it to the list. */
enum class TabSwipe { WAIT, TAKE, LEAVE }

/**
 * A finger [moved] px on the tab pages: past the touch [slop] and more sideways than upright, the pages take it before
 * the list on the page can (as Android's ViewPager decides, parent first), even drifting 25 degrees and even when one
 * fast move crosses the slop on both axes at once; more upright, the list keeps it and scrolls.
 */
fun tabSwipe(moved: Offset, slop: Float): TabSwipe = when {
    abs(moved.x) >= slop && abs(moved.x) > abs(moved.y) -> TabSwipe.TAKE
    abs(moved.y) >= slop && abs(moved.y) >= abs(moved.x) -> TabSwipe.LEAVE
    else -> TabSwipe.WAIT
}

/**
 * How many pages on each side of the one shown the pager keeps composed, [frames] after it first showed with [count]
 * pages: the neighbours from the start, so a swipe has its page ready, then one more each frame (no frame builds them
 * all) until every page is kept.
 */
fun keptBeyond(count: Int, frames: Int): Int = if (count <= 1) 0 else min(count - 1, 1 + frames)

// The tabs and their pages

/**
 * Top tabs over their pages: where the pages are ([pager], [position]) and the tab a tap picks ([pick]). Made by
 * [rememberTabPager], drawn by [PagerTabs] over [TabPages] (in a [TabsFrame], or under a header in [CollapsingTabs]).
 */
@Stable
class TabPager internal constructor(val pager: PagerState, private val scope: CoroutineScope, private val reduced: Boolean, private val chosen: State<(Int) -> Unit>) {
    /** The page shown plus the fraction of the way to the next one; read it while drawing, as it changes with every frame. */
    val position: Float get() = pager.currentPage + pager.currentPageOffsetFraction

    /** The tab the pages last told their owner of, by a settle or a tap: that tab coming back from the owner is no move. */
    internal var reported: Int = pager.currentPage
        private set

    /** The tab a settle of the pages' own is going to, while it runs. */
    private var settling: Int? = null

    /** Whether a finger holds the pages (its drag, before its settle): a tap on a tab then wins over it. */
    private var fingerOnPages = false

    /**
     * The tab at [at] tapped: the app's record of the tab shown has it at once (so the dock tapped while the pages glide
     * still finds the tab they go to), and the pages glide to it, the line with them; with reduced motion they are there.
     */
    fun pick(at: Int) {
        report(at)
        glide(at)
    }

    internal fun report(at: Int) {
        reported = at
        chosen.value(at)
    }

    /**
     * The pages to the tab at [at]: gliding there over a page's move, or with reduced motion there at once. Over a finger
     * holding them the glide wins ([tabGlidePriority]): the finger's drag ends there and its fling never runs.
     */
    internal fun glide(at: Int) {
        scope.launch {
            pager.scroll(tabGlidePriority(fingerOnPages)) {
                with(pager) { updateTargetPage(at) }
                if (reduced) {
                    with(pager) { updateCurrentPage(at) }
                } else {
                    val distance = (at - position) * (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing)
                    var done = 0f
                    animate(0f, distance, animationSpec = tween(Motion.OPEN_MS, easing = Motion.Standard)) { value, _ ->
                        done += scrollBy(value - done)
                    }
                }
            }
        }
    }

    /** Where the pages were going when a finger landed on them: a settle's or a glide's tab; null while they rest. */
    internal fun heading(): Int? = if (pager.isScrollInProgress) settling ?: pager.targetPage else null

    /**
     * A finger's swipe of the pages, as one scroll of the finger's own (it stops a settle at once, and nothing else can
     * take the pages from it): its [TabFinger.Move]s, then its [TabFinger.Lift], after which the pages settle on the
     * tab [tabFlingPage] gives. [heading]: where they were going when the finger landed.
     */
    internal fun swipe(heading: Int?, minVelocity: Float): SendChannel<TabFinger> {
        val finger = Channel<TabFinger>(Channel.UNLIMITED)
        scope.launch {
            var lift: TabFinger.Lift? = null
            pager.scroll(MutatePriority.UserInput) {
                fingerOnPages = true
                try {
                    for (event in finger) {
                        when (event) {
                            is TabFinger.Move -> scrollBy(event.px)
                            is TabFinger.Lift -> {
                                lift = event
                                break
                            }
                        }
                    }
                } finally {
                    fingerOnPages = false
                }
            }
            val end = lift ?: TabFinger.Lift(0f, dragged = false)
            settle(tabFlingPage(position, end.velocity, minVelocity, heading, end.dragged, pager.pageCount), end.velocity)
        }
        return finger
    }

    /** The pages settle on [target] from where the finger left them, at its [velocity]: one spring, no overshoot. */
    private suspend fun settle(target: Int, velocity: Float) {
        settling = target
        try {
            pager.scroll(MutatePriority.Default) {
                with(pager) { updateTargetPage(target) }
                val spec = tabSettleSpec(reduced)
                if (spec == null) {
                    with(pager) { updateCurrentPage(target) }
                } else {
                    val distance = (target - position) * (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing)
                    var done = 0f
                    animate(0f, distance, initialVelocity = velocity, animationSpec = spec) { value, _ ->
                        done += scrollBy(value - done)
                    }
                }
            }
        } finally {
            if (settling == target) settling = null
        }
    }
}

/** A finger on the tab pages: a [Move] of [Move.px] toward the next tabs, then its [Lift] at [Lift.velocity] px/s. */
internal sealed interface TabFinger {
    data class Move(val px: Float) : TabFinger

    data class Lift(val velocity: Float, val dragged: Boolean) : TabFinger
}

/**
 * Top tabs for [count] pages, kept in step both ways with the app's own record of the tab shown: [selected] chosen
 * elsewhere moves the pages ([tabMove], from where they are going), and the tab a tap picks, or the page a swipe
 * settles on, is told to [onChoose].
 */
@Composable
fun rememberTabPager(count: Int, selected: Int, onChoose: (Int) -> Unit): TabPager {
    val pager = rememberPagerState(initialPage = selected.coerceIn(0, (count - 1).coerceAtLeast(0))) { count }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val choose = rememberUpdatedState(onChoose)
    val tabs = remember(pager, scope, reduced) { TabPager(pager, scope, reduced, choose) }
    // On screen in the composition before this one: a kept root behind another dock destination is not.
    val headed = LocalLayerHeaded.current
    val onScreen = remember { Kept(headed) }
    LaunchedEffect(selected) {
        when (tabMove(selected, tabs.reported, pager.targetPage, onScreen.value)) {
            TabMove.NONE -> Unit
            TabMove.GLIDE -> tabs.glide(selected)
            TabMove.JUMP -> pager.scrollToPage(selected)
        }
    }
    SideEffect { onScreen.value = headed }
    LaunchedEffect(tabs) { snapshotFlow { pager.settledPage }.collect { tabs.report(it) } }
    // Restored between two tabs (the phone turned, or KJB restarted, mid-swipe): on the nearer one, whole.
    LaunchedEffect(pager) { if (tabNeedsLanding(pager.currentPageOffsetFraction, pager.isScrollInProgress)) pager.scrollToPage(pager.currentPage) }
    return tabs
}

/**
 * The top tabs ([labels]): the chosen one sits in a filled pill (the filled buttons' ink), the others are plain text in
 * the secondary ink, and a hairline runs under the row (user decision 2026-11-11, the mock's "Sliding pill"). The pill
 * follows the pages under the finger, sliding and stretching between two tabs ([tabPill]); each label's ink crossfades
 * by its distance from it ([tabInkShare]); a tap glides there. Both are read while drawing, so a swipe recomposes
 * nothing. Too many to fit, the row scrolls to keep the pill in view, centred where it can. Each tab is 48 dp tall to
 * touch, keeps the Tab role and says which is chosen. With reduced motion the pill does not slide: it fades in at the
 * tab the pages are nearest, over 100 ms.
 */
@Composable
fun PagerTabs(state: TabPager, labels: List<String>, modifier: Modifier = Modifier) {
    val colors = tabColors(MaterialTheme.colorScheme, LocalChromeColors.current)
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val reduced = LocalReducedMotion.current
    val row = rememberLazyListState()
    val current = state.pager.currentPage
    val nearest by remember(state) { derivedStateOf { state.position.roundToInt() } }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(nearest, reduced) {
        if (!reduced) return@LaunchedEffect fade.snapTo(1f)
        fade.snapTo(0f)
        fade.animateTo(1f, tween(Motion.REDUCED_MS))
    }
    // Where the pill is: with the pages, or with reduced motion at the tab they are nearest.
    val at = { if (reduced) nearest.toFloat() else state.position }
    val span = { laid: LazyListLayoutInfo -> tabPill(at(), { i -> laid.item(i)?.let { it.offset - laid.viewportStartOffset.toFloat() } }, { i -> laid.item(i)?.size?.toFloat() }) }
    // The pill kept in view, centred where the row can scroll that far; a tab not laid out yet is scrolled to first.
    LaunchedEffect(row, state, reduced) {
        snapshotFlow { span(row.layoutInfo) to at() }.collect { (pill, position) ->
            if (pill == null) {
                row.scrollToItem(position.roundToInt().coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
            } else {
                row.dispatchRawDelta(pill.start + pill.width / 2 - row.layoutInfo.viewportSize.width / 2f)
            }
        }
    }
    LazyRow(
        state = row,
        contentPadding = PaddingValues(start = TAB_ROW_SIDE, end = TAB_ROW_SIDE, bottom = TAB_ROW_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(hairline, Offset(0f, size.height - stroke), Size(size.width, stroke))
                val pill = span(row.layoutInfo) ?: return@drawBehind
                val height = TAB_PILL_HEIGHT.toPx()
                val top = (TAB_TOUCH.toPx() - height) / 2
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - pill.start - pill.width else pill.start
                drawRoundRect(colors.pill, Offset(left, top), Size(pill.width, height), CornerRadius(height / 2), alpha = fade.value)
            },
    ) {
        itemsIndexed(labels, contentType = { _, _ -> "tab" }) { index, name ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .height(TAB_PILL_HEIGHT)
                    .clip(CircleShape)
                    .selectable(index == current, role = Role.Tab) { state.pick(index) }
                    .padding(horizontal = TAB_PADDING),
            ) {
                val share = { if (reduced) (if (index == nearest) fade.value else 0f) else tabInkShare(state.position, index) }
                BasicText(name, style = TabText, maxLines = 1, color = { tabInk(colors, share()) })
            }
        }
    }
}

/** The laid-out tab at [index], or null while it is not laid out. */
private fun LazyListLayoutInfo.item(index: Int): LazyListItemInfo? = visibleItemsInfo.firstOrNull { it.index == index }

/**
 * The tabs' pages, one per [keys] (stable and saveable: the tabs themselves), each drawn by [page] and told whether it
 * is the one shown ([LocalTabCurrent]). They follow the finger left and right unless [swipe] is off (a page selecting
 * songs), and stay composed once the pager has settled in ([keptBeyond]): their lists keep their place and their rows.
 */
@Composable
fun TabPages(state: TabPager, keys: List<Any>, modifier: Modifier = Modifier, swipe: Boolean = true, page: @Composable (Int) -> Unit) {
    val count = keys.size
    var frames by remember { mutableIntStateOf(0) }
    LaunchedEffect(count) {
        while (keptBeyond(count, frames) < count - 1) {
            withFrameNanos {}
            frames++
        }
    }
    val direction = LocalLayoutDirection.current
    val rtl = direction == LayoutDirection.Rtl
    // The system's gesture edges (back), in the window, and where the pages sit in it: a finger landing there is the system's.
    val gestures = WindowInsets.systemGestures
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val origin = remember { Kept(Offset.Zero) }
    val edge = rememberUpdatedState { at: Offset ->
        inGestureEdge(origin.value.x + at.x, window.containerSize.width.toFloat(), gestures.getLeft(density, direction).toFloat(), gestures.getRight(density, direction).toFloat())
    }
    HorizontalPager(
        state = state.pager,
        // The pages take a finger themselves, before the list on the page can (tabSwipe); Compose would ask the list first.
        modifier = modifier
            .onGloballyPositioned { origin.value = it.positionInWindow() }
            .then(if (swipe) Modifier.pointerInput(state, rtl) { swipes(state, rtl) { edge.value(it) } } else Modifier)
            // The pager's own scroll actions go with its own touch scrolling: TalkBack's move one tab, as a tap does.
            .semantics {
                val count = keys.size
                fun step(forward: Boolean): Boolean = tabStep(forward, state.pager.currentPage, count)?.let { state.pick(it) } != null
                horizontalScrollAxisRange = ScrollAxisRange(value = { state.position }, maxValue = { (count - 1).coerceAtLeast(0).toFloat() })
                scrollBy { x, _ -> step(if (rtl) x < 0f else x > 0f) }
                pageRight { step(forward = !rtl) }
                pageLeft { step(forward = rtl) }
            },
        beyondViewportPageCount = keptBeyond(count, frames),
        verticalAlignment = Alignment.Top,
        userScrollEnabled = false,
        key = { keys[it] },
    ) { at ->
        CompositionLocalProvider(LocalTabCurrent provides (at == state.pager.currentPage)) { page(at) }
    }
}

/**
 * Each finger on the tab pages, seen before anything on the pages ([PointerEventPass.Initial]): laid on settling pages,
 * it catches them where they are, and a tap there stops them on the nearer tab and taps nothing under it; moved more
 * sideways than upright past the slop ([tabSwipe]), it drags the pages, every finger theirs meanwhile, and at the lift
 * they settle on the tab its speed and place give ([tabFlingPage]); more upright, it is the list's (caught pages settle
 * on the nearer tab). A finger landing in the system's gesture [edge] is left alone. [rtl]: the next tab is to the left.
 */
private suspend fun PointerInputScope.swipes(state: TabPager, rtl: Boolean, edge: (Offset) -> Boolean) {
    val minVelocity = TAB_FLING.toPx()
    val maxVelocity = viewConfiguration.maximumFlingVelocity
    val slop = viewConfiguration.touchSlop
    fun forward(px: Float) = if (rtl) px else -px
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // In the system's gesture edge it is the system's back, never a tab flick.
        if (edge(down.position)) return@awaitEachGesture
        // Settling pages stop under the finger, caught where they are, as ViewPager does; the finger's way decides next.
        // Nothing is consumed yet, so the list, its rows and the header still see the finger.
        var finger = state.heading()?.let { state.swipe(it, minVelocity) }
        val tracker = VelocityTracker().apply { addPointerInputChange(down) }
        var moved = Offset.Zero
        var dragging = false
        var lifted = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPointerInputChange(change)
                if (!change.pressed) {
                    lifted = true
                    // A tap on caught pages only stops them: nothing under it is tapped.
                    if (dragging || finger != null) change.consume()
                    break
                }
                if (dragging) {
                    finger?.trySend(TabFinger.Move(forward(change.positionChange().x)))
                    // While a finger drags the pages every finger is theirs: a second one taps nothing.
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (change.isConsumed) break
                moved += change.positionChange()
                when (tabSwipe(moved, slop)) {
                    TabSwipe.WAIT -> continue
                    // Upright: the list's; caught pages settle on the nearer tab meanwhile.
                    TabSwipe.LEAVE -> break
                    TabSwipe.TAKE -> {
                        dragging = true
                        val pages = finger ?: state.swipe(null, minVelocity).also { finger = it }
                        pages.trySend(TabFinger.Move(forward(moved.x - sign(moved.x) * slop)))
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        } finally {
            // Taken by the system or cut short, the pages settle on the nearer tab, never on a fling's.
            val velocity = if (dragging && lifted) forward(tracker.calculateVelocity().x).coerceIn(-maxVelocity, maxVelocity) else 0f
            finger?.trySend(TabFinger.Lift(velocity, dragging))
        }
    }
}

/**
 * The least speed at the lift that makes a swipe a flick: a quarter of Compose's own pager (400 dp/s, which read a quick
 * flick slowing at the lift as a slow drag and sent the pages back), twice Android's least fling (50 dp/s).
 */
private val TAB_FLING = 100.dp

/** How far off a whole tab restored pages may be and still count as on it. */
private const val LANDING_SLACK = 0.001f

/** The pages settling on a tab: as Compose's pager settles, without overshoot. */
private val TabSettle = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f)

/** Whether the tab page around this is the one shown: only it answers the dock tapped again (it goes to its top). */
val LocalTabCurrent = compositionLocalOf { true }

// A page whose tabs sit under its top bar (Library's)

/**
 * A page whose top tabs sit in its top bar: [topBar] (the page's bar with [PagerTabs], given whether the content of the
 * tab shown is under it, and the glass to blur) over [TabPages], each page drawn by [page]. A page draws its content in
 * a [PageFrame] as any page does: here the frame keeps that page's own scroll and the room under the shared bar
 * ([TabPage]). A page that shows its own top bar (selecting songs) takes the shared bar's place and the pages stay
 * still meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsFrame(state: TabPager, keys: List<Any>, topBar: @Composable (under: Boolean, backdrop: GlassBackdrop) -> Unit, page: @Composable (Int) -> Unit) {
    val host = remember { TabsHost() }
    val own = rememberGlassBackdrop()
    val blur = LocalGlassBlur.current
    val under = host.unders[keys.getOrNull(state.pager.currentPage)] == true
    val bar = host.bar
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { if (bar != null) bar(under, own) else topBar(under, own) },
    ) { padding ->
        val top = padding.calculateTopPadding()
        // One glass for all the pages: the pager is one content, drawn once, whichever pages show.
        Box(Modifier.fillMaxSize().glassSource(own, blur)) {
            TabPages(state, keys, Modifier.fillMaxSize(), swipe = bar == null) { at ->
                val tab = remember(host, keys[at], top) { TabPage(host, keys[at], top) }
                CompositionLocalProvider(LocalTabPage provides tab) { page(at) }
            }
        }
    }
}

/** What a [TabsFrame] knows of its pages: whose content is under the shared bar, and a page's own bar while it shows one. */
internal class TabsHost {
    val unders = mutableStateMapOf<Any, Boolean>()

    var bar: (@Composable (Boolean, GlassBackdrop) -> Unit)? by mutableStateOf(null)
}

/** A page of a [TabsFrame], as its [PageFrame] sees it: the room the shared bar takes above it ([top]). */
@Stable
class TabPage internal constructor(private val host: TabsHost, private val key: Any, val top: Dp) {
    /**
     * While [bar] is not null (this page selects songs), it shows in the shared bar's place, given whether this page's
     * content is under it, and the pages stay still.
     */
    @Composable
    fun OwnTopBar(bar: (@Composable (Boolean, GlassBackdrop) -> Unit)?) {
        val latest by rememberUpdatedState(bar)
        val showing = bar != null
        DisposableEffect(showing) {
            val shown: @Composable (Boolean, GlassBackdrop) -> Unit = { under, backdrop -> latest?.invoke(under, backdrop) }
            if (showing) host.bar = shown
            onDispose { if (host.bar === shown) host.bar = null }
        }
    }

    /** The page's frame inside the [TabsFrame]: its own scroll tells the shared bar when content is under it. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    internal fun Frame(content: @Composable (PaddingValues) -> Unit) {
        val chrome = LocalShellChrome.current
        val scroll = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        val under = scroll.contentUnder()
        SideEffect { host.unders[key] = under }
        DisposableEffect(Unit) { onDispose { host.unders -= key } }
        Box(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
            content(PaddingValues(top = top, bottom = chrome.bottom))
        }
    }
}

/** The [TabsFrame] page around this, for its [PageFrame]; null outside one. */
val LocalTabPage = compositionLocalOf<TabPage?> { null }

// A page whose tabs sit under a header (Favourites')

/**
 * A page's [header] (its hero and list header) over its top [tabs] and their [pages], below [padding]'s top (the top
 * bar): the header scrolls away first ([outer], the page's: its top bar is over content once it has moved), then the
 * tabs stay under the top bar while each tab's own list scrolls on in the room left under them. A drag on the header
 * scrolls it; a list pulled down at its own top brings it back.
 */
@Composable
fun CollapsingTabs(outer: ScrollState, padding: PaddingValues, header: @Composable () -> Unit, tabs: @Composable () -> Unit, pages: @Composable () -> Unit) {
    // A list scrolled up hides the header before it scrolls itself; pulled down, it scrolls back to its top first.
    val collapse = remember(outer) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) Offset(0f, -outer.dispatchRawDelta(-available.y)) else Offset.Zero
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = with(density) { padding.calculateTopPadding().roundToPx() }
        val room = constraints.maxHeight
        Layout(
            content = {
                Column { header() }
                Box { tabs() }
                Box { pages() }
            },
            modifier = Modifier.fillMaxSize().nestedScroll(collapse).verticalScroll(outer),
        ) { (head, row, below), constraints ->
            val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val headPlaced = head.measure(loose)
            val rowPlaced = row.measure(loose)
            // The pages fill what the tabs leave once the header has gone, so they never resize as it goes.
            val pagesPlaced = below.measure(Constraints.fixed(constraints.maxWidth, (room - top - rowPlaced.height).coerceAtLeast(0)))
            layout(constraints.maxWidth, top + headPlaced.height + rowPlaced.height + pagesPlaced.height) {
                headPlaced.place(0, top)
                rowPlaced.place(0, top + headPlaced.height)
                pagesPlaced.place(0, top + headPlaced.height + rowPlaced.height)
            }
        }
    }
}

/** A tab's name (.lt): 15/20. */
internal val TabText = body(15f, 20, 600)

/** The tab row's sides and bottom (the mock's 12 and 10 dp, less the touch room under the pill), the gap between tabs, a tab's touch height. */
private val TAB_ROW_SIDE = 12.dp
private val TAB_ROW_BOTTOM = 4.dp
private val TAB_GAP = 2.dp
private val TAB_TOUCH = 48.dp
