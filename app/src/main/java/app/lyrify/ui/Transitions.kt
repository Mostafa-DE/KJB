package app.lyrify.ui

import kotlinx.coroutines.sync.Mutex
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.State
import androidx.compose.animation.animateColor
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.lyrify.settings.DockTab

// How KJB's screens change, after the motion spec (handoff/motion-spec.md): the pure decisions first (what changed,
// which way it moves, how far a drag or a back swipe has gone), tested on the JVM, then the transitions they pick. The
// durations and curves are Motion's (Theme.kt); with reduced motion every slide, scale and container transform becomes a
// fade of Motion.REDUCED_MS (decision D4).

// States and lists

/**
 * One state of a screen replacing another (its list, its empty state, its loading state): the old one fades out, then the
 * new one fades in, so they never overlap; with [reduced] motion a short cross-fade.
 */
fun fadeThrough(reduced: Boolean): ContentTransform =
    if (reduced) {
        fadeIn(tween(Motion.REDUCED_MS)) togetherWith fadeOut(tween(Motion.REDUCED_MS))
    } else {
        fadeIn(tween(Motion.FADE_IN_MS, delayMillis = Motion.FADE_OUT_MS)) togetherWith fadeOut(tween(Motion.FADE_OUT_MS))
    }

/**
 * [content] for [state], faded through ([fadeThrough]) whenever its [kind] changes: give the kind of state (loading,
 * empty, the list), never the data, so a list that changes stays in place and its rows move on their own.
 */
@Composable
fun <S> FadeThrough(state: S, modifier: Modifier = Modifier, kind: (S) -> Any? = { it }, content: @Composable (S) -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(state, modifier, transitionSpec = { fadeThrough(reduced) }, contentKey = kind, label = "state") { content(it) }
}

/**
 * A list row's motion: it slides to its new place and fades in and out as rows come and go ([LazyItemScope.animateItem]);
 * with [reduced] motion it only fades. The row needs its stable key; read [reduced] once above the list.
 */
fun LazyItemScope.rowMotion(reduced: Boolean): Modifier =
    if (reduced) Modifier.animateItem(fadeInSpec = tween(Motion.REDUCED_MS), placementSpec = null, fadeOutSpec = tween(Motion.REDUCED_MS)) else Modifier.animateItem()

/** [rowMotion] for a grid's cell. */
fun LazyGridItemScope.rowMotion(reduced: Boolean): Modifier =
    if (reduced) Modifier.animateItem(fadeInSpec = tween(Motion.REDUCED_MS), placementSpec = null, fadeOutSpec = tween(Motion.REDUCED_MS)) else Modifier.animateItem()

/** The last value shown, kept while what shows it leaves (a bar or a sheet fading out after its state went away). */
class Kept<T>(var value: T)

/** Play and Pause taking each other's place: the new icon fades in and grows from 80% with a small overshoot. */
fun playPauseSwap(reduced: Boolean): ContentTransform =
    if (reduced) {
        ContentTransform(fadeIn(tween(Motion.REDUCED_MS)), fadeOut(tween(Motion.REDUCED_MS)), sizeTransform = null)
    } else {
        ContentTransform(fadeIn(tween(Motion.QUICK_MS)) + scaleIn(Motion.indicator, initialScale = 0.8f), fadeOut(tween(Motion.FADE_OUT_MS)), sizeTransform = null)
    }

// The bottom chrome

/** The Now bar and the dock (or the selection bar) arriving: up from below while they fade in; with reduced motion a fade. */
fun chromeEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.REDUCED_MS)) else slideInVertically(tween(Motion.OPEN_MS, easing = Motion.Rise)) { it } + fadeIn(tween(Motion.FADE_IN_MS))

/** The chrome leaving: down and out, as a full screen or the keyboard takes its place. */
fun chromeExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.REDUCED_MS)) else slideOutVertically(tween(Motion.SETTLE_MS, easing = Motion.EmphasizedOut)) { it } + fadeOut(tween(Motion.SETTLE_MS))

/**
 * The selection bar and the Now bar with the dock taking each other's place: the old one fades out, the new one rises a
 * fifth of its height as it fades in. The slot takes the new size at once (no size animation), so the pages under it
 * pad once, not on every frame.
 */
fun chromeSwap(reduced: Boolean): ContentTransform {
    if (reduced) return ContentTransform(fadeIn(tween(Motion.REDUCED_MS)), fadeOut(tween(Motion.REDUCED_MS)), sizeTransform = null)
    val enter = fadeIn(tween(Motion.FADE_IN_MS, delayMillis = Motion.FADE_OUT_MS)) + slideInVertically(tween(Motion.OPEN_MS, easing = Motion.Rise)) { it / 5 }
    return ContentTransform(enter, fadeOut(tween(Motion.FADE_OUT_MS)), sizeTransform = null)
}

/** The Now bar arriving when a song loads: it rises half its height as it fades in, into room made at once (no resizing on every frame). */
fun barEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.REDUCED_MS)) else slideInVertically(tween(Motion.OPEN_MS, easing = Motion.Rise)) { it / 2 } + fadeIn(tween(Motion.FADE_IN_MS))

/** The Now bar leaving when nothing is loaded any more. */
fun barExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.REDUCED_MS)) else slideOutVertically(tween(Motion.SETTLE_MS, easing = Motion.EmphasizedOut)) { it / 2 } + fadeOut(tween(Motion.SETTLE_MS))

/** A badge or a dot arriving: it pops from 60% with a small overshoot as it fades in (motion spec 2.7). */
fun badgeIn(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.REDUCED_MS)) else scaleIn(Motion.indicator, initialScale = 0.6f) + fadeIn(tween(Motion.FADE_IN_MS))

/** A badge leaving. */
fun badgeOut(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.REDUCED_MS)) else scaleOut(tween(Motion.FADE_IN_MS), targetScale = 0.6f) + fadeOut(tween(Motion.FADE_IN_MS))

/** A chip arriving in its row: it fades in and grows from 92% as its room opens beside it. */
fun chipIn(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.REDUCED_MS)) else fadeIn(tween(Motion.FADE_IN_MS)) + scaleIn(Motion.settle, initialScale = 0.92f) + expandHorizontally(spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold))

/** A chip leaving its row. */
fun chipOut(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.REDUCED_MS)) else fadeOut(tween(Motion.FADE_OUT_MS)) + scaleOut(tween(Motion.FADE_IN_MS), targetScale = 0.92f) + shrinkHorizontally(spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold))

/** Something that opens its own room (a banner, an expander): it grows and fades in; with reduced motion it only fades. */
fun expandIn(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.REDUCED_MS)) else expandVertically(spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold)) + fadeIn(tween(Motion.FADE_IN_MS))

/** Its room closing. */
fun shrinkOut(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.REDUCED_MS)) else shrinkVertically(spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold)) + fadeOut(tween(Motion.FADE_OUT_MS))

// Touches

/**
 * Whether a layer takes touches: only one arriving or resting ([headed] for the screen), never one on its way out; and a
 * container transform not before it has landed ([settled]), its layout not being where it is drawn.
 */
fun takesInput(headed: Boolean, settled: Boolean, container: Boolean): Boolean = headed && (settled || !container)

/**
 * A layer that takes touches only while [takes] says so (read at each touch): otherwise every pointer change is consumed
 * before its content sees it, so a page, view or sheet step on its way out never acts on a tap.
 */
fun Modifier.inertUnless(takes: () -> Boolean): Modifier = this then InertElement(takes)

private data class InertElement(val takes: () -> Boolean) : ModifierNodeElement<InertNode>() {
    override fun create(): InertNode = InertNode(takes)

    override fun update(node: InertNode) {
        node.takes = takes
    }
}

private class InertNode(var takes: () -> Boolean) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && !takes()) pointerEvent.changes.forEach { it.consume() }
    }

    override fun onCancelPointerInput() = Unit
}

// A back swipe (predictive back, Android 14 and later)

/** What a back swipe takes away: the top [View], the page layer's top [Page] (by its key), or the [Pages] over the views it goes back to. */
sealed interface BackTaking {
    data class View(val view: FullScreen) : BackTaking

    data class Page(val key: String) : BackTaking

    data class Pages(val views: List<FullScreen>) : BackTaking
}

/**
 * The back swipe in progress, shared by the shell's layers: what it [taking]s away, how far it has gone ([progress], 0
 * to 1) and from which edge. The layer taken draws [backPeek] of it and the one under it lights up; both read it while
 * drawing only, so a swipe never recomposes a page. [gone]: the layer fading away once the swipe is let go ([finish]).
 */
@Stable
class BackSwipe {
    // By identity: a new swipe over the same layer is a new taking, even when equal to the last one.
    var taking: BackTaking? by mutableStateOf(null, referentialEqualityPolicy())
        private set

    var progress: Float by mutableFloatStateOf(0f)
        private set

    var fromLeft: Boolean by mutableStateOf(true)
        private set

    var gone: Float by mutableFloatStateOf(0f)
        private set

    /** Swipes one at a time: a new one waits until the last one has finished with the layers. */
    val serial = Mutex()

    fun start(what: BackTaking) {
        taking = what
        progress = 0f
        gone = 0f
    }

    fun follow(event: BackEventCompat) {
        progress = event.progress
        fromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
    }

    /** [owner]'s swipe given up: the layer springs back to rest; a newer swipe is never touched. Clearing is the caller's. */
    suspend fun springBack(owner: BackTaking) {
        animate(progress, 0f, animationSpec = Motion.release) { value, _ -> if (owns(owner)) progress = value }
    }

    /** [owner]'s swipe let go over a container transform: the shrink is let go of while the layer goes into the Now bar. */
    suspend fun settle(owner: BackTaking) {
        animate(progress, 0f, animationSpec = tween(Motion.OPEN_MS, easing = Motion.Rise)) { value, _ -> if (owns(owner)) progress = value }
    }

    /**
     * [owner]'s swipe let go, or a plain Back (three buttons, a key) that never moved at all: the layer carries on from
     * where the finger left it to the end of a whole swipe, shrinking and moving the swipe's way as it fades out, while
     * the layer under it lights up. So Back moves the page away as the gesture does, over a page's move, never cut.
     */
    suspend fun finish(owner: BackTaking) {
        val from = progress
        animate(0f, 1f, animationSpec = tween(Motion.OPEN_MS, easing = LinearEasing)) { time, _ ->
            if (owns(owner)) {
                progress = from + (1f - from) * Motion.Standard.transform(time)
                gone = time
            }
        }
    }

    /** Whether [owner] (the taking a swipe started with, by identity) is still the swipe in progress. */
    fun owns(owner: BackTaking): Boolean = taking === owner

    /** Nothing is taken any more, unless a newer swipe has started meanwhile: a swipe cleans up only after itself. */
    fun clear(owner: BackTaking) {
        if (!owns(owner)) return
        taking = null
        progress = 0f
        gone = 0f
    }
}

/**
 * A layer as a back swipe takes it ([taken], read while drawing): [backPeek]'s shrink, shift and corners, and its fade
 * once the swipe is let go ([BackSwipe.gone]). Untouched while nothing is taken.
 */
fun Modifier.backPeek(swipe: BackSwipe, taken: () -> Boolean): Modifier = graphicsLayer {
    if (!taken()) return@graphicsLayer
    val peek = backPeek(swipe.progress, swipe.fromLeft, size.width)
    scaleX = peek.scale
    scaleY = peek.scale
    translationX = peek.shiftX
    alpha = 1f - swipe.gone
    shape = RoundedCornerShape(PEEK_CORNER * peek.corner)
    clip = peek.corner > 0f
}

/** The corners a layer taken by a back swipe or a drag rounds to. */
val PEEK_CORNER = 28.dp

// The container transform between the Now bar and the player (decision D2)

/**
 * One end of the container transform: the shell's shared transition scope, and the visibility of that end, the Now
 * bar's chrome ([bar]) or the player's layer. Null where nothing grows: outside the shell, and for a view that rises.
 */
@Stable
class Container(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope, val bar: Boolean)

val LocalContainer = compositionLocalOf<Container?> { null }

/** Whether the view layer around this is headed for the screen (arriving or resting) rather than leaving it. */
val LocalLayerHeaded = compositionLocalOf { true }

private const val CONTAINER_SURFACE = "player surface"
private const val CONTAINER_COVER = "player cover"

/**
 * The bounds a Now bar capsule or a player screen ([container]'s end) grow from and into, the content scaled to them
 * (ScaleToBounds, so the player is laid out once, not on every frame), the corners going from the capsule's to none.
 * The Now bar's end is an empty stand-in of the bar's size, out of the overlay ([containerInOverlay]), so the bar never
 * moves or fades, and the player's card, opaque from its first frame ([containerFill]), grows over it and over the dock.
 * Nothing with reduced motion.
 */
@Composable
fun containerBounds(container: Container?): Modifier {
    if (container == null || LocalReducedMotion.current) return Modifier
    // The corner is read only when the overlay is drawn: one clip for the whole transform, nothing recomposed per frame.
    val corner = container.visibility.transition.animateDp(transitionSpec = { tween(containerMs(targetState), easing = Motion.Rise) }, label = "container corner") { state ->
        if ((state == EnterExitState.Visible) == container.bar) CAPSULE_CORNER else 0.dp
    }
    val density = LocalDensity.current
    return with(container.shared) {
        Modifier.sharedBounds(
            rememberSharedContentState(CONTAINER_SURFACE),
            container.visibility,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            boundsTransform = ContainerTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.Center),
            renderInOverlayDuringTransition = containerInOverlay(container.bar),
            clipInOverlayDuringTransition = remember(container) { OverlayClip(CornerShape { with(density) { corner.value.toPx() } }) },
        )
    }
}

/** Whether an end of the container transform is drawn above everything while it runs: the player's, never the Now bar's. */
fun containerInOverlay(bar: Boolean): Boolean = !bar

/**
 * The player's card as it grows out of the Now bar: the bar's own colour at first and the page's once in place, never
 * see-through on the way in, so the dock stays under it; going back into the bar it clears, so the bar shows through
 * as it lands. Read while drawing.
 */
@Composable
fun containerFill(visibility: AnimatedVisibilityScope, page: Color): State<Color> {
    val bar = LocalChromeColors.current.bar
    return visibility.transition.animateColor(transitionSpec = { tween(containerMs(targetState), easing = Motion.Rise) }, label = "container fill") { state ->
        when (state) {
            EnterExitState.PreEnter -> bar
            EnterExitState.Visible -> page
            EnterExitState.PostExit -> page.copy(alpha = 0f)
        }
    }
}

/** The player's content inside its growing card: it fades in once the card has grown a little, and out first on the way back. */
fun containerContentEnter(): EnterTransition = fadeIn(tween(Motion.FADE_IN_MS * 2, delayMillis = Motion.FADE_OUT_MS))

fun containerContentExit(): ExitTransition = fadeOut(tween(Motion.FADE_IN_MS))

/**
 * The cover as it moves between the Now bar's orb and the player: one shared element, clipped by the caller with the
 * player's corners (which at the orb's size round to its circle, so the shape follows the size).
 */
@Composable
fun containerCover(container: Container?): Modifier {
    if (container == null || LocalReducedMotion.current) return Modifier
    return with(container.shared) { Modifier.sharedElement(rememberSharedContentState(CONTAINER_COVER), container.visibility, boundsTransform = ContainerTransform) }
}

/** A rounded rectangle whose corner [radius] (px) is read each time it is drawn, so an animated corner needs one shape. */
class CornerShape(private val radius: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius())))
}

/**
 * Whether the Now bar and the dock stay put as a full screen comes or goes: they do while the bar grows into [view]
 * (or [view] shrinks into it), the bar being one end of the transform; for a view that rises they slide away.
 */
fun chromeStays(view: FullScreen?, barShown: Boolean): Boolean = view != null && viewMotion(view, barShown) == ViewMotion.CONTAINER

/** Opening grows over [Motion.CONTAINER_MS]; closing, the smaller move back, over [Motion.OPEN_MS] (the spec's 0.7). */
private fun containerMs(target: EnterExitState): Int = if (target == EnterExitState.Visible) Motion.CONTAINER_MS else Motion.OPEN_MS

private val ContainerTransform = BoundsTransform { from, to -> tween(if (to.width >= from.width) Motion.CONTAINER_MS else Motion.OPEN_MS, easing = Motion.Rise) }

/** The Now bar's capsule corners. */
private val CAPSULE_CORNER = 32.dp

// The page layer

/**
 * A back swipe over the page layer, as the platform reports it (PredictiveBackHandler): the page that is [current] (on
 * top, taking touches), the page drawn under it while the finger holds the swipe ([under], null at rest), and how far
 * the finger has taken the top page ([progress], 0 at rest). Only the platform's two endings change it: [committed]
 * when the progress ends normally, the page under becoming current; [cancelled] when it is cancelled, everything back at
 * rest as it was, however long the swipe was held and however fast it came back. Nothing moves it once it has ended.
 */
@Immutable
data class PageSwipe(val current: PageLayer, val under: PageLayer? = null, val progress: Float = 0f) {
    val resting: Boolean get() = under == null && progress == 0f

    fun started(back: PageLayer): PageSwipe = PageSwipe(current, back, 0f)

    fun moved(to: Float): PageSwipe = if (under == null) this else copy(progress = to.coerceIn(0f, 1f))

    fun cancelled(): PageSwipe = PageSwipe(current)

    fun committed(): PageSwipe = PageSwipe(under ?: current)
}

/** The page layer under the views: a tab and its pages above the root (the top last), as the shell draws it. */
@Immutable
data class PageLayer(val tab: DockTab, val stack: List<Page>) {
    /** The saved-state key of the page drawn ([pageKey]): the layer's identity while it moves. */
    val key: String get() = pageKey(tab, stack)
}

fun pageLayerOf(state: NavState): PageLayer = PageLayer(state.tab, state.pages)

/** How the page layer changed, which picks how it moves. */
sealed interface PageChange {
    /** Another tab, coming from its [side] of the dock (1 from the end, -1 from the start). */
    data class Tab(val side: Int) : PageChange

    /** A page opened over the one shown. */
    data object Push : PageChange

    /** Back to the page under it. */
    data object Pop : PageChange

    /** More than one page gone at once (the dock tapped again on a deep stack): it fades through to the root. */
    data object Reset : PageChange

    data object None : PageChange
}

/** What changed between the layers [from] and [to]: the stack decides, not the tab alone (motion spec F1). */
fun pageChange(from: PageLayer, to: PageLayer): PageChange = when {
    from.tab != to.tab -> PageChange.Tab(tabSlide(from.tab, to.tab))
    from.stack == to.stack -> PageChange.None
    to.stack.size == from.stack.size + 1 && to.stack.startsWith(from.stack) -> PageChange.Push
    to.stack.size == from.stack.size - 1 && from.stack.startsWith(to.stack) -> PageChange.Pop
    else -> PageChange.Reset
}

private fun List<Page>.startsWith(other: List<Page>): Boolean = size >= other.size && subList(0, other.size) == other

/**
 * The side the new page comes from, on screen: 1 from the right, -1 from the left, 0 for none. A push comes from the
 * end, a pop from the start, another tab from its side; right to left mirrors them.
 */
fun enterSide(change: PageChange, rtl: Boolean): Int {
    val side = when (change) {
        is PageChange.Tab -> change.side
        PageChange.Push -> 1
        PageChange.Pop -> -1
        PageChange.Reset, PageChange.None -> 0
    }
    return if (rtl) -side else side
}

/**
 * How the page layer moves for [change] (shared axis X, motion spec 2.1): a push or a pop slides a tenth of the width
 * from [enterSide] as it fades in, while the old page slides the other way and fades out first, so two pages never
 * show half each; a pop draws the page it goes back to under the one leaving. Another tab is the dock's [tabSwitch];
 * a reset fades through. With [reduced] motion every change is a 100 ms fade (decision D4).
 */
fun pageTransition(change: PageChange, rtl: Boolean, reduced: Boolean): ContentTransform {
    if (change == PageChange.None) return EnterTransition.None togetherWith ExitTransition.None
    if (reduced) return fadeIn(tween(Motion.REDUCED_MS)) togetherWith fadeOut(tween(Motion.REDUCED_MS))
    val side = enterSide(change, rtl)
    return when (change) {
        is PageChange.Tab -> tabSwitch(side, reduced = false)
        PageChange.Reset -> fadeThrough(reduced = false)
        else -> {
            val enter = slideInHorizontally(tween(Motion.OPEN_MS, easing = Motion.Rise)) { it / PAGE_SHIFT_SHARE * side } +
                fadeIn(tween(Motion.OPEN_MS - Motion.FADE_OUT_MS, delayMillis = Motion.FADE_OUT_MS))
            val exit = slideOutHorizontally(tween(Motion.SETTLE_MS, easing = Motion.EmphasizedOut)) { -it / PAGE_SHIFT_SHARE * side } + fadeOut(tween(Motion.FADE_OUT_MS))
            ContentTransform(enter, exit, targetContentZIndex = if (change == PageChange.Pop) -1f else 0f)
        }
    }
}

/**
 * The page layer under a back swipe, which seeks it by the finger's progress: the page gone back to waits under the
 * one leaving, sliding in from [side] a little as the finger moves, and the one leaving (shrunk by [backPeek]) fades
 * only toward the end, so it stays clear while the user decides. Linear in time: the finger is the easing.
 */
fun backTransition(side: Int, reduced: Boolean): ContentTransform {
    if (reduced) return ContentTransform(fadeIn(tween(Motion.REDUCED_MS)), fadeOut(tween(Motion.REDUCED_MS)), targetContentZIndex = -1f)
    val enter = slideInHorizontally(tween(Motion.OPEN_MS, easing = LinearEasing)) { it / PAGE_SHIFT_SHARE * side }
    // The page leaving stays whole while the finger decides: its fade is the swipe's own, and only once it commits.
    return ContentTransform(enter, fadeOut(tween(Motion.OPEN_MS, easing = HoldToEnd)), targetContentZIndex = -1f)
}

/** Nothing until the very end: a layer kept as it is through a seek, then gone. */
private val HoldToEnd = Easing { if (it < 1f) 0f else 1f }

/** A page moves a tenth of the width (the spec's Dist.PageX, the dock's own tab slide). */
private const val PAGE_SHIFT_SHARE = 10

// The views

/** The views drawn: those [drawn] before, in their places (a closing one stays until it has left), then those newly [shown] on top. */
fun drawnViews(drawn: List<FullScreen>, shown: List<FullScreen>): List<FullScreen> = drawn + shown.filterNot { it in drawn }

/** The views drawn that are leaving: drawn, and no longer shown. */
fun leavingViews(drawn: List<FullScreen>, shown: List<FullScreen>): List<FullScreen> = drawn.filterNot { it in shown }

/** How a view arrives and leaves: grown out of the Now bar and back into it, or rising over the screen. */
enum class ViewMotion { CONTAINER, RISE }

/**
 * The player (and the companion's Now screen, the Now bar's other end) grows out of the bar while the bar is on screen
 * ([barShown]); every other view, and the player without the bar (from a Tools page, the widget), rises.
 */
fun viewMotion(view: FullScreen, barShown: Boolean): ViewMotion =
    if (barShown && (view == FullScreen.Player || view == FullScreen.CompanionNow)) ViewMotion.CONTAINER else ViewMotion.RISE

/**
 * A view arriving: rising 18% of its height as it fades in (the prototype's upIn, shared axis Y); grown from the Now
 * bar, the shared bounds move it, so the layer itself does nothing. With [reduced] motion, a 100 ms fade.
 */
fun viewEnter(motion: ViewMotion, reduced: Boolean): EnterTransition = when {
    reduced -> fadeIn(tween(Motion.REDUCED_MS))
    motion == ViewMotion.CONTAINER -> EnterTransition.None
    else -> slideInVertically(tween(Motion.OPEN_MS, easing = Motion.Rise)) { it * RISE_SHARE / 100 } + fadeIn(tween(Motion.OPEN_MS, easing = Motion.Rise))
}

/** A view leaving: down and out, the reverse of [viewEnter], a little quicker. */
fun viewExit(motion: ViewMotion, reduced: Boolean): ExitTransition = when {
    reduced -> fadeOut(tween(Motion.REDUCED_MS))
    motion == ViewMotion.CONTAINER -> ExitTransition.None
    else -> slideOutVertically(tween(Motion.SETTLE_MS, easing = Motion.EmphasizedOut)) { it * RISE_SHARE / 100 } + fadeOut(tween(Motion.SETTLE_MS, easing = Motion.EmphasizedOut))
}

/** How far a view rises, in percent of its height. */
private const val RISE_SHARE = 18

// The bottom slot

/** What the bottom chrome shows: a page's selection bar, the Now bar and the dock, or nothing. */
enum class BottomSlot { SELECTION, BAR_AND_DOCK, NONE }

/** Nothing over the keyboard ([ime]) or where the tab shows no [chrome]; else the selection bar while [selecting], or the Now bar and the dock. */
fun bottomSlot(selecting: Boolean, chrome: Boolean, ime: Boolean): BottomSlot = when {
    !chrome || ime -> BottomSlot.NONE
    selecting -> BottomSlot.SELECTION
    else -> BottomSlot.BAR_AND_DOCK
}

// A finger taking a layer away

/**
 * How a layer looks while a finger takes it away: its [scale], how far it has moved ([shiftX], [shiftY], px), how round
 * its corners are ([corner], 0 to 1 of [PEEK_CORNER]), and how dim the layer under it still is ([dim], black's alpha).
 */
@Immutable
data class Peek(val scale: Float, val shiftX: Float, val shiftY: Float, val corner: Float, val dim: Float)

/** The layer under a view, dimmed (One UI dims the old screen). */
const val DIM_UNDER: Float = 0.24f

/** How small a back swipe makes a layer (the predictive back guidance's 0.9). */
const val BACK_SCALE: Float = 0.9f

/** How small a drag down makes the player. */
const val DRAG_SCALE: Float = 0.92f

/**
 * A back swipe [progress] (0 to 1, clamped) from the left edge ([fromLeft]) or the right: the layer shrinks to
 * [BACK_SCALE], rounds its corners and moves a twentieth of its [width] the finger's way, while the layer under it
 * lights up from [DIM_UNDER].
 */
fun backPeek(progress: Float, fromLeft: Boolean, width: Float): Peek {
    val p = progress.coerceIn(0f, 1f)
    val shift = p * width / BACK_SHIFT_SHARE
    return Peek(scale = 1f - (1f - BACK_SCALE) * p, shiftX = if (fromLeft) shift else -shift, shiftY = 0f, corner = p, dim = DIM_UNDER * (1f - p))
}

private const val BACK_SHIFT_SHARE = 20f

/** The player dragged down [offset] px (never up) over its [height]: it follows the finger, shrinking to [DRAG_SCALE] and rounding by half the height. */
fun dragPeek(offset: Float, height: Float): Peek {
    val y = offset.coerceAtLeast(0f)
    val p = if (height > 0f) (y / (height * DRAG_FULL_SHARE)).coerceIn(0f, 1f) else 0f
    return Peek(scale = 1f - (1f - DRAG_SCALE) * p, shiftX = 0f, shiftY = y, corner = p, dim = DIM_UNDER * (1f - p))
}

private const val DRAG_FULL_SHARE = 0.5f

/**
 * Whether letting go of the player dragged down [offset] px at [velocity] px/s (down positive) closes it: past a quarter
 * of its [height], or flung down faster than [fling] px/s while below where it started. Else it springs back.
 */
fun dragCloses(offset: Float, velocity: Float, height: Float, fling: Float): Boolean = offset > height * DRAG_CLOSE_SHARE || (offset > 0f && velocity > fling)

private const val DRAG_CLOSE_SHARE = 0.25f

/** The fling that closes the player, in dp per second. */
const val DRAG_FLING_DP: Float = 800f

/**
 * Where the player's drag goes once let go: back to 0 when it springs back, or when it closes into the Now bar (the
 * container shrinks it whole); null when it falls away as it closes, leaving from where the finger left it.
 */
fun dragRelease(closes: Boolean, intoBar: Boolean): Float? = if (!closes || intoBar) 0f else null

/** With reduced motion the player does not move under a drag; it fades to this as the drag nears what closes it. */
const val REDUCED_DRAG_ALPHA: Float = 0.4f

/** The player's alpha [offset] px into a drag down over its [height], with reduced motion: so it never closes unseen. */
fun reducedDragAlpha(offset: Float, height: Float): Float {
    val p = if (height > 0f) (offset.coerceAtLeast(0f) / (height * DRAG_FULL_SHARE)).coerceIn(0f, 1f) else 0f
    return 1f - (1f - REDUCED_DRAG_ALPHA) * p
}

// Dialogs

/**
 * A dialog that leaves before its action runs (decision D5): a tap starts the exit and keeps the action, which runs once
 * the exit has ended, about 90 ms later. While it leaves, other taps are ignored, so a double tap never confirms twice
 * and Cancel cannot follow a confirm.
 */
@Stable
class DialogExit {
    var leaving: Boolean by mutableStateOf(false)
        private set

    private var action: (() -> Unit)? = null

    /** Starts the exit with [then] to run after it; false when the dialog is already leaving. */
    fun leave(then: () -> Unit): Boolean {
        if (leaving) return false
        leaving = true
        action = then
        return true
    }

    /** The exit has ended: the action runs, once. */
    fun ended() {
        val then = action ?: return
        action = null
        then()
    }
}

/** A dialog's card arriving: it fades in and grows from 94% with a little overshoot (the prototype's dlgIn). */
fun dialogEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.REDUCED_MS)) else fadeIn(tween(Motion.FADE_IN_MS)) + scaleIn(Motion.snappy, initialScale = DIALOG_FROM)

/** The card leaving, quicker: it fades and shrinks a little. */
fun dialogExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.REDUCED_MS)) else fadeOut(tween(Motion.FADE_OUT_MS)) + scaleOut(tween(Motion.FADE_OUT_MS), targetScale = DIALOG_TO)

private const val DIALOG_FROM = 0.94f
private const val DIALOG_TO = 0.96f
