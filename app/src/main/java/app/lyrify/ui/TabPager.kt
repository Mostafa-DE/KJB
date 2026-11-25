package app.lyrify.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.semantics.selected
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import java.io.Serializable

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
 * to the other's, so it slides and stretches with the finger. [start] and [width] give each tab's, null for one not
 * measured yet: then the pill sits on the one that is, never missing a frame; null only while neither is.
 */
fun tabPill(position: Float, start: (Int) -> Float?, width: (Int) -> Float?): PillSpan? {
    val from = floor(position).toInt()
    val way = position - from
    fun at(index: Int): PillSpan? = start(index)?.let { left -> width(index)?.let { PillSpan(left, it) } }
    val first = at(from)
    val second = if (way > 0f) at(from + 1) else null
    return when {
        first != null && second != null -> PillSpan(first.start + (second.start - first.start) * way, first.width + (second.width - first.width) * way)
        else -> first ?: second
    }
}

/**
 * How much of the pill's ink the label of the tab at [index] takes with the pages at [position]: all of it under the
 * resting pill, none a whole tab away, crossing over quickly about half way (a smoothstep over the middle [INK_CROSSING]
 * of the way), so for nearly all of a swipe each label keeps an ink that reads on what is under most of it (review of
 * 5b488d2: a straight crossfade read under 4.5:1 for a fifth of it).
 */
fun tabInkShare(position: Float, index: Int): Float {
    val near = (1f - abs(position - index)).coerceIn(0f, 1f)
    val way = ((near - (1f - INK_CROSSING) / 2) / INK_CROSSING).coerceIn(0f, 1f)
    return way * way * (3f - 2f * way)
}

/** The share of a swipe over which a label's ink crosses from one to the other. */
private const val INK_CROSSING = 0.3f

/** Where each tab starts in the row, from the [widths] of every tab, the row's [start] padding and the [gap] between tabs. */
fun tabStarts(widths: List<Float>, start: Float, gap: Float): List<Float> = widths.runningFold(start) { at, width -> at + width + gap }.dropLast(1)

/** How far the tab row scrolls to bring a pill centred at [centre] to the middle of its [viewport], as far as it can ([max]). */
fun tabRowScroll(centre: Float, viewport: Int, max: Int): Int = (centre - viewport / 2f).roundToInt().coerceIn(0, max.coerceAtLeast(0))

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

/**
 * The key of the tab page at [at] of the tabs' [keys]. The pager reads its page count from the same tabs, so no page is
 * past them; should one be asked for all the same, it gets a key of its own, equal to no tab's and saveable as a page's
 * key must be, never a crash (KJB 3.0.2 crashed as Genres was turned on: the pager counted six pages while the keys it
 * read were still the five).
 */
fun tabPageKey(keys: List<Any>, at: Int): Any = keys.getOrNull(at) ?: PastTheTabs(at)

/** The key of a tab page asked for past the tabs ([tabPageKey]). */
private data class PastTheTabs(val at: Int) : Serializable

/**
 * Where the pages land, at once, when their tabs change from [before] to [after] (one turned on, turned off or moved in
 * Settings > Library tabs, or the library's Books coming or going) while they show, or go to, the page at [heading] of
 * [before]: nowhere (null) while that page's tab keeps its place, so a change past it stops no swipe, and nowhere with
 * no tabs left; on that tab wherever the change moved it, the one a swipe was going to even before the app hears of
 * it; that tab gone, on the one the app has as shown instead ([selected], of [after]: Library's Songs), else the first.
 * Never where the pager would find its own way to by a page's index or by a key gone.
 */
fun tabAfterChange(before: List<Any>, after: List<Any>, heading: Int, selected: Int): Int? {
    val moved = before.getOrNull(heading)?.let(after::indexOf) ?: -1
    return when {
        after.isEmpty() || moved == heading -> null
        moved >= 0 -> moved
        selected in after.indices -> selected
        else -> 0
    }
}

// The tabs and their pages

/**
 * Top tabs ([keys]) over their pages: where the pages are ([pager], [position]) and the tab a tap picks ([pick]). Made
 * by [rememberTabPager], drawn by [PagerTabs] over [TabPages] (in a [TabsFrame], or under a header in [CollapsingTabs]).
 */
@Stable
class TabPager<K : Any> internal constructor(val pager: PagerState, private val tabs: State<List<K>>, private val scope: CoroutineScope, private val reduced: Boolean, private val chosen: State<(Int) -> Unit>) {
    /** The tabs, a page each in their order: the pager's page count and every page's key are read from these alone. */
    val keys: List<K> get() = tabs.value

    /** The tab of the page shown. */
    val current: K? get() = keys.getOrNull(pager.currentPage)

    /** The page shown plus the fraction of the way to the next one; read it while drawing, as it changes with every frame. */
    val position: Float get() = pager.currentPage + pager.currentPageOffsetFraction

    /** The tab the pages last told their owner of, by a settle or a tap: that tab coming back from the owner is no move. */
    internal var reported: Int = pager.currentPage
        private set

    /** The tab a settle of the pages' own is going to, while it runs. */
    private var settling: Int? = null

    /** Whether a finger holds the pages (its drag, before its settle): a tap on a tab then wins over it. */
    private var fingerOnPages = false

    /** The pages' own move under way, a tap's glide or a finger's swipe and its settle: a landing stops it ([land]). */
    private var moving: Job? = null

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
     * The pages at once on the tab at [at] of tabs that have just changed ([tabAfterChange]), by the next time they are
     * laid out: never on a tab the pager found by itself, not even for a frame. A glide or a swipe under way stops
     * first, so none goes on by its old distance or settles where it was heading among the old tabs (the pager's own
     * stop comes later, and never before a finger's). The owner is told, as of a settle.
     */
    internal fun land(at: Int) {
        moving?.cancel()
        pager.requestScrollToPage(at)
        report(at)
    }

    /**
     * The pages to the tab at [at]: gliding there over a page's move, or with reduced motion there at once. Over a finger
     * holding them the glide wins ([tabGlidePriority]): the finger's drag ends there and its fling never runs.
     */
    internal fun glide(at: Int) {
        moving = scope.launch {
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
        moving = scope.launch {
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
 * Top tabs over a page for each of [keys] (stable and saveable: the tabs themselves), kept in step both ways with the
 * app's own record of the tab shown: [selected] chosen elsewhere moves the pages ([tabMove], from where they are
 * going), and the tab a tap picks, or the page a swipe settles on, is told to [onChoose]. The pager counts its pages and
 * keys each one from the same state the tabs are kept in, so a change of the tabs never shows it a count its keys do not
 * have (KJB 3.0.2 crashed so as Genres was turned on); and a change that moves the tab under the pages or takes it away
 * lands them at once on the one the app has as shown ([tabAfterChange]).
 */
@Composable
fun <K : Any> rememberTabPager(keys: List<K>, selected: Int, onChoose: (Int) -> Unit): TabPager<K> {
    val tabs = rememberUpdatedState(keys)
    val pager = rememberPagerState(initialPage = selected.coerceIn(0, (keys.size - 1).coerceAtLeast(0))) { tabs.value.size }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val choose = rememberUpdatedState(onChoose)
    val state = remember(pager, scope, reduced) { TabPager(pager, tabs, scope, reduced, choose) }
    // On screen in the composition before this one: a kept root behind another dock destination is not. Read as this one
    // composes: the effect starts only after the side effect below has stored this composition's (Library's icon tapped
    // on another dock destination glided to Songs under the page's fade-in).
    val headed = LocalLayerHeaded.current
    val onScreen = remember { Kept(headed) }
    val wasOnScreen = onScreen.value
    LaunchedEffect(selected) {
        when (tabMove(selected, state.reported, pager.targetPage, wasOnScreen)) {
            TabMove.NONE -> Unit
            TabMove.GLIDE -> state.glide(selected)
            TabMove.JUMP -> pager.scrollToPage(selected)
        }
    }
    SideEffect { onScreen.value = headed }
    // The tabs changed (Settings > Library tabs, shown or kept behind a page): landed before the pages are laid out
    // again, and before the effect above reads where they are going.
    val landed = remember { Kept(keys) }
    SideEffect {
        if (landed.value != keys) {
            tabAfterChange(landed.value, keys, pager.targetPage, selected)?.let(state::land)
            landed.value = keys
        }
    }
    LaunchedEffect(state) { snapshotFlow { pager.settledPage }.collect { state.report(it) } }
    // Restored between two tabs (the phone turned, or KJB restarted, mid-swipe): on the nearer one, whole.
    LaunchedEffect(pager) { if (tabNeedsLanding(pager.currentPageOffsetFraction, pager.isScrollInProgress)) pager.scrollToPage(pager.currentPage) }
    return state
}

/**
 * The top tabs ([labels]): the chosen one sits in a filled pill (the filled buttons' ink), the others are plain text in
 * the secondary ink, and a hairline runs under the row (user decision 2026-11-11, the mock's "Sliding pill"). The pill
 * follows the pages under the finger, sliding and stretching between two tabs ([tabPill]); each label's ink crossfades
 * by its distance from it ([tabInkShare]); a tap glides there. Both are read while drawing, so a swipe recomposes
 * nothing. Too many to fit, the row scrolls to keep the pill in view, centred where it can, whenever the pages move;
 * scrolled by hand, it stays where the finger left it until they move again. Each tab is 48 dp tall to
 * touch, keeps the Tab role and says which is chosen. With reduced motion the pill does not slide: it fades in at the
 * tab the pages are nearest, over 100 ms. With [hold], holding a tab opens its small menu beside it (TalkBack offers
 * the hold as an action, named by [TabHold.label]).
 */
@Composable
fun PagerTabs(state: TabPager<*>, labels: List<String>, modifier: Modifier = Modifier, hold: TabHold? = null) {
    val colors = tabColors(MaterialTheme.colorScheme, LocalChromeColors.current)
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val current = state.pager.currentPage
    // Every tab is laid out (a handful at most, review of 5b488d2), so the pill always knows both tabs it is between.
    val widths = remember(labels.size) { mutableStateListOf<Float>().apply { repeat(labels.size) { add(0f) } } }
    val nearest by remember(state) { derivedStateOf { state.position.roundToInt() } }
    // The tab whose menu is open, by its place; none while no tab is held.
    var held by remember { mutableStateOf<Int?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(nearest, reduced) {
        if (!reduced) return@LaunchedEffect fade.snapTo(1f)
        fade.snapTo(0f)
        fade.animateTo(1f, tween(Motion.REDUCED_MS))
    }
    // Where the pill is: with the pages, or with reduced motion at the tab they are nearest.
    val at = { if (reduced) nearest.toFloat() else state.position }
    // Whole pixels, as the row lays them out.
    val side = with(density) { TAB_ROW_SIDE.roundToPx().toFloat() }
    val gap = with(density) { TAB_GAP.roundToPx().toFloat() }
    val span = {
        val starts = tabStarts(widths, side, gap)
        tabPill(at(), { i -> starts.getOrNull(i)?.takeIf { widths[i] > 0f } }, { i -> widths.getOrNull(i)?.takeIf { it > 0f } })
    }
    // The pill brought to the middle of the row whenever it moves with the pages (a swipe, a tap's glide, a page chosen
    // elsewhere) or the row's tabs or width change, never because the row scrolled: scrolled by hand, the row stays as
    // it is until the pages move again, and not while a finger is on it. Read through [pillNow], so tabs that change
    // later are followed too.
    val pillNow by rememberUpdatedState(span)
    LaunchedEffect(scroll) {
        snapshotFlow { pillNow() to scroll.viewportSize }.collect { (pill, viewport) ->
            if (pill == null || viewport == 0 || scroll.isScrollInProgress) return@collect
            val target = tabRowScroll(pill.start + pill.width / 2, viewport, scroll.maxValue)
            if (target != scroll.value) scroll.dispatchRawDelta((target - scroll.value).toFloat())
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(TAB_GAP),
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(hairline, Offset(0f, size.height - stroke), Size(size.width, stroke))
            }
            .horizontalScroll(scroll)
            // Drawn in the scrolled row itself, so the pill scrolls with its tabs.
            .drawBehind {
                val pill = span() ?: return@drawBehind
                val height = TAB_PILL_HEIGHT.toPx()
                val top = (TAB_TOUCH.toPx() - height) / 2
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - pill.start - pill.width else pill.start
                drawRoundRect(colors.pill, Offset(left, top), Size(pill.width, height), CornerRadius(height / 2), alpha = fade.value)
            }
            .padding(start = TAB_ROW_SIDE, end = TAB_ROW_SIDE, bottom = TAB_ROW_BOTTOM)
            .selectableGroup(),
    ) {
        labels.forEachIndexed { index, name ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .onSizeChanged { widths[index] = it.width.toFloat() }
                    .minimumInteractiveComponentSize()
                    .height(TAB_PILL_HEIGHT)
                    .clip(CircleShape)
                    .then(
                        if (hold == null) {
                            Modifier.selectable(index == current, role = Role.Tab) { state.pick(index) }
                        } else {
                            Modifier
                                .semantics { selected = index == current }
                                .combinedClickable(role = Role.Tab, onLongClickLabel = hold.label, onLongClick = { held = index }) { state.pick(index) }
                        },
                    )
                    .padding(horizontal = TAB_PADDING),
            ) {
                val share = { if (reduced) (if (index == nearest) fade.value else 0f) else tabInkShare(state.position, index) }
                BasicText(name, style = TabText, maxLines = 1, color = { tabInk(colors, share()) })
                // A popup, so the chip's clip leaves it whole; it opens from the chip held.
                if (hold != null) LyrifyMenu(expanded = held == index, onDismiss = { held = null }) { hold.menu(this, index) { held = null } }
            }
        }
    }
}

/** What holding one of [PagerTabs]' tabs offers: its [menu] for the tab at an index, which closes it with `close`; [label] names the hold for TalkBack. */
class TabHold(val label: String, val menu: @Composable ColumnScope.(index: Int, close: () -> Unit) -> Unit)

/**
 * The tabs' pages, one per tab of [state] ([TabPager.keys]), each drawn by [page] for its tab and told whether it is the
 * one shown ([LocalTabCurrent]). They follow the finger left and right unless [swipe] is off (a page selecting songs),
 * and stay composed once the pager has settled in ([keptBeyond]): their lists keep their place and their rows.
 */
@Composable
fun <K : Any> TabPages(state: TabPager<K>, modifier: Modifier = Modifier, swipe: Boolean = true, page: @Composable (K) -> Unit) {
    val count = state.keys.size
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
    val rows = remember { HashMap<ScrollableState, Rect>() }
    val edge = rememberUpdatedState { at: Offset ->
        inGestureEdge(origin.value.x + at.x, window.containerSize.width.toFloat(), gestures.getLeft(density, direction).toFloat(), gestures.getRight(density, direction).toFloat())
    }
    HorizontalPager(
        state = state.pager,
        // The pages take a finger themselves, before the list on the page can (tabSwipe); Compose would ask the list first.
        modifier = modifier
            .onGloballyPositioned { origin.value = it.positionInWindow() }
            .then(
                if (swipe) {
                    Modifier.pointerInput(state, rtl) {
                        swipes(state, rtl, { edge.value(it) }) { at, dx -> rows.any { (row, bounds) -> bounds.contains(origin.value + at) && rowKeeps(dx, rtl, row.canScrollBackward, row.canScrollForward) } }
                    }
                } else {
                    Modifier
                },
            )
            // The pager's own scroll actions go with its own touch scrolling: TalkBack's move one tab, as a tap does.
            .semantics {
                fun step(forward: Boolean): Boolean = tabStep(forward, state.pager.currentPage, state.keys.size)?.let { state.pick(it) } != null
                horizontalScrollAxisRange = ScrollAxisRange(value = { state.position }, maxValue = { (state.keys.size - 1).coerceAtLeast(0).toFloat() })
                scrollBy { x, _ -> step(if (rtl) x < 0f else x > 0f) }
                pageRight { step(forward = !rtl) }
                pageLeft { step(forward = rtl) }
            },
        beyondViewportPageCount = keptBeyond(count, frames),
        verticalAlignment = Alignment.Top,
        userScrollEnabled = false,
        // Read where the pager reads its page count, so in the same snapshot: never a count the keys do not have.
        key = { at -> tabPageKey(state.keys, at) },
    ) { at ->
        // The page's tab read there too, never from a list handed down a moment before (a page's content can be the
        // previous composition's for a frame, its index already the new tabs').
        state.keys.getOrNull(at)?.let { tab -> CompositionLocalProvider(LocalTabCurrent provides (at == state.pager.currentPage), LocalSidewaysRows provides rows) { page(tab) } }
    }
}

/**
 * Each finger on the tab pages, seen before anything on the pages ([PointerEventPass.Initial]): laid on settling pages,
 * it catches them where they are, and a tap there stops them on the nearer tab and taps nothing under it; moved more
 * sideways than upright past the slop ([tabSwipe]), it drags the pages, every finger theirs meanwhile, and at the lift
 * they settle on the tab its speed and place give ([tabFlingPage]); more upright, it is the list's (caught pages settle
 * on the nearer tab). A finger landing in the system's gesture [edge] is left alone, and one a row under it [keeps] (it
 * scrolls that way, [rowKeeps]) is the row's. [rtl]: the next tab is to the left.
 */
private suspend fun PointerInputScope.swipes(state: TabPager<*>, rtl: Boolean, edge: (Offset) -> Boolean, keeps: (Offset, Float) -> Boolean) {
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
                        // A row under the finger that scrolls that way keeps it (rowKeeps).
                        if (keeps(down.position, moved.x)) break
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

/**
 * The rows on [TabPages]' pages that scroll sideways themselves (Library's friends row), each with where it is in the
 * window: a sideways finger on one is the row's while it can scroll that way ([rowKeeps]). Null off the tab pages.
 */
val LocalSidewaysRows = staticCompositionLocalOf<MutableMap<ScrollableState, Rect>?> { null }

/**
 * Whether a row that scrolls sideways keeps a finger moved [dx] px across it, as ViewPager asks its children before it
 * swipes: while the row can still scroll that way ([back] towards its start, [forward] towards its end; [rtl]: its start
 * on the right), else the tabs take it, at the row's end (user 2026-11-25: Library's friends row never scrolled).
 */
fun rowKeeps(dx: Float, rtl: Boolean, back: Boolean, forward: Boolean): Boolean = if ((dx < 0f) != rtl) forward else back

/** Whether the tab page around this is the one shown: only it answers the dock tapped again (it goes to its top). */
val LocalTabCurrent = compositionLocalOf { true }

// A page whose tabs sit under its top bar (Library's)

/**
 * A page whose top tabs sit in its top bar: [topBar] (the page's bar with [PagerTabs], given whether the content of the
 * tab shown is under it, and the glass to blur) over [TabPages], each tab's page drawn by [page]. A page draws its
 * content in a [PageFrame] as any page does: here the frame keeps that page's own scroll and the room under the shared
 * bar ([TabPage]). A page that shows its own top bar (selecting songs) takes the shared bar's place and the pages stay
 * still meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <K : Any> TabsFrame(state: TabPager<K>, topBar: @Composable (under: Boolean, backdrop: GlassBackdrop) -> Unit, page: @Composable (K) -> Unit) {
    val host = remember { TabsHost() }
    val own = rememberGlassBackdrop()
    val blur = LocalGlassBlur.current
    val under = state.current?.let { host.unders[it] } == true
    val bar = host.bar
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { if (bar != null) bar(under, own) else topBar(under, own) },
    ) { padding ->
        val top = padding.calculateTopPadding()
        // One glass for all the pages: the pager is one content, drawn once, whichever pages show.
        Box(Modifier.fillMaxSize().glassSource(own, blur)) {
            TabPages(state, Modifier.fillMaxSize(), swipe = bar == null) { tab ->
                val frame = remember(host, tab, top) { TabPage(host, tab, top) }
                CompositionLocalProvider(LocalTabPage provides frame) { page(tab) }
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
