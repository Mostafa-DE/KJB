package app.lyrify.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.SongRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Double-tap to favourite (KJB 3.1, user 2026-11-18): a song, album, artist or playlist wherever it is listed, and the
// song on the player's cover. A single tap is never delayed: it acts at once, as before. A second tap soon after, near
// it, is taken whole by the host (the shell, or a sheet with a list of its own), so it never reaches what lies under it
// (the page the first tap opened, the song's row again), and makes what the first tap was on a favourite, with a heart
// at the finger and "Added to Favourites" with Undo. It only ever adds. TalkBack's double tap is its own: while TalkBack
// explores nothing here runs, and Favourite stays in each item's "⋯" and on each page.

/** What a double tap makes a favourite: a song, or an album, artist or playlist. */
sealed interface FavouriteTarget {
    data class Song(val ref: SongRef) : FavouriteTarget

    data class Collection(val collection: FavouriteCollection) : FavouriteTarget

    companion object {
        /** An album, by its key in the library. */
        fun album(key: String): FavouriteTarget = Collection(FavouriteCollection(CollectionKind.ALBUM, key))

        fun artist(name: String): FavouriteTarget = Collection(FavouriteCollection(CollectionKind.ARTIST, name))

        /** A playlist, by its id. */
        fun playlist(id: String): FavouriteTarget = Collection(FavouriteCollection(CollectionKind.PLAYLIST, id))
    }
}

/**
 * What makes a double tap, as the phone sets it ([rememberDoubleTaps]): the second tap's finger lands within [windowMs]
 * of the first one's lift, no sooner than [minGapMs] (sooner is the finger bouncing) and within [slopPx] of it. A tap
 * moves no further than [touchSlopPx] and lifts within [holdMs] (a long press). Times in ms, distances in px.
 */
data class TapTiming(val windowMs: Long, val minGapMs: Long, val slopPx: Float, val touchSlopPx: Float, val holdMs: Long)

/** A heart popping where a second tap landed ([position], in the window, px). */
class HeartPop internal constructor(val id: Long, val position: Offset)

/**
 * The double taps over one host ([doubleTapHost]). Items tell it their taps as they act on them ([tapped], through
 * [doubleTapFavourite]); the host passes it every touch first ([down], [move], [up]). The touch after a tap is that tap's
 * second when it lands within the double-tap time near it ([TapTiming]): wherever it lands, the host takes it whole
 * ([down] is true), and once it lifts as a tap a heart pops there ([pops]) and the first tap's target goes to
 * [onDouble]. A second touch that moves past the touch slop or is held is let go and does nothing. A further tap soon
 * after only pops another heart: nothing is stored again, so the message's Undo stays. Off ([enabled] false: TalkBack
 * explores), it records and takes nothing. Times are the touches' uptimes, places in the window. Main thread only.
 */
@Stable
class DoubleTaps(internal val timing: TapTiming, private val onDouble: (FavouriteTarget) -> Unit) {
    var enabled: Boolean by mutableStateOf(true)

    private val shown = mutableStateListOf<HeartPop>()

    /** The hearts popping now, the newest last; [HeartPops] draws them. */
    val pops: List<HeartPop> get() = shown

    private var first: FirstTap? = null
    private var taken: Taken? = null
    private var ids = 0L

    /** Whether the host holds a touch now: a second tap that has not lifted yet. */
    val taking: Boolean get() = taken != null

    /** A tap on [target], acted on as its finger lifted at [at], [position]; [onSecond]: what its second tap does besides. */
    fun tapped(target: FavouriteTarget, at: Long, position: Offset, onSecond: (() -> Unit)? = null) {
        if (enabled) first = FirstTap(target, at, position, onSecond, doubled = false)
    }

    /** A finger down at [at], [position]: true when it is a tap's second, which the host then takes. Any touch ends the wait. */
    fun down(at: Long, position: Offset): Boolean {
        val tap = first
        first = null
        taken = null
        if (!enabled || tap == null) return false
        val gap = at - tap.at
        if (gap < timing.minGapMs || gap > timing.windowMs || (position - tap.position).getDistance() > timing.slopPx) return false
        taken = Taken(tap, at, position)
        return true
    }

    /** The taken finger moved to [position]: false once it is no tap (a scroll), and the host lets it go. */
    fun move(position: Offset): Boolean {
        val held = taken ?: return false
        if ((position - held.position).getDistance() <= timing.touchSlopPx) return true
        taken = null
        return false
    }

    /** The taken finger lifted at [at], [position]: a tap doubles its first tap; one held too long does nothing. */
    fun up(at: Long, position: Offset) {
        val held = taken ?: return
        taken = null
        if (at - held.at >= timing.holdMs || (position - held.position).getDistance() > timing.touchSlopPx) return
        shown += HeartPop(++ids, held.position)
        // A further tap soon after, near this one, carries on this double tap.
        first = held.tap.copy(at = at, position = position, doubled = true)
        if (held.tap.doubled) return
        held.tap.onSecond?.invoke()
        onDouble(held.tap.target)
    }

    /** The system took the touch away (another window, a gesture of its own): the host holds nothing. */
    fun cancel() {
        taken = null
    }

    /** [pop]'s heart has finished. */
    fun popped(pop: HeartPop) {
        shown -= pop
    }

    /** A tap that a second may double; [doubled]: it was a second tap already, and a further one only pops a heart. */
    private data class FirstTap(val target: FavouriteTarget, val at: Long, val position: Offset, val onSecond: (() -> Unit)?, val doubled: Boolean)

    private class Taken(val tap: FirstTap, val at: Long, val position: Offset)
}

/**
 * Whether one finger's touch on an item was a tap, as its clickable takes one: it lifts within [TapTiming.holdMs] of
 * landing (later is a long press), within [TapTiming.touchSlopPx] of where it landed, and nothing else took any of its
 * moves (a list's scroll, the cover's sideways swipe, the player's drag down). Places in the item's own space.
 */
class TapWatch(private val timing: TapTiming) {
    private var at = 0L
    private var from: Offset? = null

    fun down(at: Long, position: Offset) {
        this.at = at
        from = position
    }

    fun move(position: Offset, taken: Boolean) {
        val start = from ?: return
        if (taken || (position - start).getDistance() > timing.touchSlopPx) from = null
    }

    fun up(at: Long, position: Offset): Boolean {
        val start = from ?: return false
        from = null
        return at - this.at < timing.holdMs && (position - start).getDistance() <= timing.touchSlopPx
    }

    fun cancel() {
        from = null
    }
}

/**
 * The app's [DoubleTaps] for a host: the phone's double-tap time and distances, off while TalkBack explores, and a
 * double tap's favourite stored at once ([SongActions.addFavourite]) with its message and Undo.
 */
@Composable
fun rememberDoubleTaps(): DoubleTaps {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    val config = LocalViewConfiguration.current
    val exploring = rememberTouchExploring()
    val timing = remember(context, config) {
        val slop = android.view.ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
        TapTiming(config.doubleTapTimeoutMillis, config.doubleTapMinTimeMillis, slop, config.touchSlop, config.longPressTimeoutMillis)
    }
    val favourite by rememberUpdatedState<(FavouriteTarget) -> Unit> { target ->
        scope.launch {
            val now = LibraryGraph.playlists(context).state.first()
            val library = (LibraryGraph.repository(context).state.value as? LibraryState.Ready)?.library
            toasts.say(resources, actions.addFavourite(target, now, library))
        }
    }
    val taps = remember(timing) { DoubleTaps(timing) { favourite(it) }.also { it.enabled = !exploring } }
    SideEffect { taps.enabled = !exploring }
    return taps
}

/**
 * Makes this the host of [taps]' double taps: the items under it ([doubleTapFavourite]) tell it their taps, and it sees
 * every touch here first, taking a tap's second whole (consumed before anything under it sees it, the page a first tap
 * opened included) and letting every other touch through untouched. Put it on the shell, or on a sheet's content, as a
 * sheet is a window of its own; with it, [HeartPops] draws the hearts.
 */
fun Modifier.doubleTapHost(taps: DoubleTaps): Modifier = this then DoubleTapHostElement(taps)

/**
 * Double-tap to favourite on an item that acts on a single tap (a song's row plays it, an album's card opens its page):
 * its taps still act at once, and a second tap soon after, near it, makes [target] a favourite (taken by the host above
 * it, [doubleTapHost]). [onSecond]: what that second tap does besides (the player's cover turns back). Put it next to the
 * item's clickable, over the same bounds. Null [target]: no double tap (a list while it selects).
 */
fun Modifier.doubleTapFavourite(target: FavouriteTarget?, onSecond: (() -> Unit)? = null): Modifier =
    if (target == null) this else this then DoubleTapElement(target, onSecond)

/**
 * Where [taps]' hearts pop: put it last in the host's content. It takes no room and draws each heart at its finger,
 * wherever this sits, over the content before it ([modifier]'s zIndex where its siblings have one). A heart grows in
 * with a small overshoot and floats up as it fades; with reduced motion it only fades in and out. TalkBack never meets
 * one: the message says what happened.
 */
@Composable
fun HeartPops(taps: DoubleTaps, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val pops = taps.pops
    Layout(
        content = { pops.forEach { pop -> key(pop.id) { Heart(reduced, Modifier.layoutId(pop.id)) { taps.popped(pop) } } } },
        modifier = modifier,
    ) { measurables, _ ->
        val hearts = measurables.map { it.measure(Constraints()) to it.layoutId }
        val above = HEART_ABOVE.roundToPx()
        layout(0, 0) {
            val here = coordinates?.takeIf { it.isAttached }
            hearts.forEach { (heart, id) ->
                val at = pops.firstOrNull { it.id == id }?.position ?: return@forEach
                val local = here?.windowToLocal(at) ?: at
                heart.place((local.x - heart.width / 2f).roundToInt(), (local.y - heart.height / 2f).roundToInt() - above)
            }
        }
    }
}

/**
 * One heart, just above the finger so the finger never hides it: the brand's heart on a rim of the surface, so it reads
 * over a cover too. It grows from [HEART_FROM] on the toast's pop curve, then floats up [HEART_RISE] as it fades; with
 * reduced motion it fades in, stays a moment and fades out, never moving. [onDone] once it has gone.
 */
@Composable
private fun Heart(reduced: Boolean, modifier: Modifier, onDone: () -> Unit) {
    val brand = LocalBrandColors.current
    val rim = MaterialTheme.colorScheme.surface
    val grow = remember { Animatable(if (reduced) 1f else HEART_FROM) }
    val shown = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        if (reduced) {
            shown.animateTo(1f, tween(Motion.REDUCED_MS))
            delay(HEART_STAY_MS)
            shown.animateTo(0f, tween(Motion.REDUCED_MS))
        } else {
            shown.snapTo(1f)
            grow.animateTo(1f, tween(Motion.ARRIVE_MS, easing = Motion.Pop))
            launch { rise.animateTo(1f, tween(HEART_OUT_MS, easing = Motion.Standard)) }
            shown.animateTo(0f, tween(HEART_OUT_MS, easing = Motion.EmphasizedOut))
        }
        done()
    }
    val lift = with(LocalDensity.current) { HEART_RISE.toPx() }
    Box(
        modifier.size(HEART_SIZE).graphicsLayer {
            scaleX = grow.value
            scaleY = grow.value
            alpha = shown.value
            translationY = -lift * rise.value
        },
    ) {
        Icon(painterResource(R.drawable.ic_favorite_fill), contentDescription = null, tint = rim, modifier = Modifier.fillMaxSize())
        Icon(painterResource(R.drawable.ic_favorite_fill), contentDescription = null, tint = brand.highlight, modifier = Modifier.fillMaxSize().padding(HEART_RIM))
    }
}

private data class DoubleTapHostElement(val taps: DoubleTaps) : ModifierNodeElement<DoubleTapHostNode>() {
    override fun create(): DoubleTapHostNode = DoubleTapHostNode(taps)

    override fun update(node: DoubleTapHostNode) {
        if (node.taps !== taps) node.reset()
        node.taps = taps
    }
}

/** The host's touch: it sees each one before anything under it (the Initial pass) and takes a second tap whole. */
private class DoubleTapHostNode(var taps: DoubleTaps) : Modifier.Node(), PointerInputModifierNode, TraversableNode {
    override val traverseKey: Any get() = DoubleTapHostKey

    /** The finger taken, while it is down. */
    private var held: PointerId? = null

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        // A second finger makes it no tap.
        val change = pointerEvent.changes.singleOrNull() ?: return reset()
        when {
            change.changedToDownIgnoreConsumed() -> {
                held = null
                if (taps.down(change.uptimeMillis, window(change.position))) {
                    change.consume()
                    held = change.id
                }
            }
            change.id != held -> Unit
            change.changedToUpIgnoreConsumed() -> {
                change.consume()
                held = null
                taps.up(change.uptimeMillis, window(change.position))
            }
            // Its moves stay the page's: past the touch slop it is a scroll, and the host lets it go.
            change.pressed -> if (!taps.move(window(change.position))) held = null
        }
    }

    override fun onCancelPointerInput() = reset()

    fun reset() {
        if (held != null) taps.cancel()
        held = null
    }

    private fun window(position: Offset): Offset = requireLayoutCoordinates().localToWindow(position)
}

private object DoubleTapHostKey

private data class DoubleTapElement(val target: FavouriteTarget, val onSecond: (() -> Unit)?) : ModifierNodeElement<DoubleTapNode>() {
    override fun create(): DoubleTapNode = DoubleTapNode(target, onSecond)

    override fun update(node: DoubleTapNode) {
        node.target = target
        node.onSecond = onSecond
    }
}

/**
 * An item's touch, watched and never consumed: a tap ([TapWatch]) is told to the nearest host above it, if any. A touch
 * the host took (consumed before it got here) is a second tap, never a first. Its moves are checked once everything
 * has had them (the Final pass): one taken by a scroll makes it no tap.
 */
private class DoubleTapNode(var target: FavouriteTarget, var onSecond: (() -> Unit)?) : Modifier.Node(), PointerInputModifierNode {
    private var watch: TapWatch? = null
    private var pointer: PointerId? = null

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        val change = pointerEvent.changes.singleOrNull() ?: return onCancelPointerInput()
        when (pass) {
            PointerEventPass.Initial -> watch(change)
            PointerEventPass.Final -> if (change.id == pointer && change.pressed && change.previousPressed && change.isConsumed) watch?.move(change.position, taken = true)
            else -> Unit
        }
    }

    private fun watch(change: PointerInputChange) {
        val taps = (findNearestAncestor(DoubleTapHostKey) as? DoubleTapHostNode)?.taps ?: return
        when {
            change.changedToDownIgnoreConsumed() -> {
                pointer = null
                if (change.isConsumed || !taps.enabled) return
                pointer = change.id
                watch = TapWatch(taps.timing).also { it.down(change.uptimeMillis, change.position) }
            }
            change.id != pointer -> Unit
            change.changedToUpIgnoreConsumed() -> {
                pointer = null
                val tap = watch?.up(change.uptimeMillis, change.position) == true
                if (tap) taps.tapped(target, change.uptimeMillis, requireLayoutCoordinates().localToWindow(change.position), onSecond)
            }
            change.pressed -> watch?.move(change.position, taken = false)
        }
    }

    override fun onCancelPointerInput() {
        watch?.cancel()
        pointer = null
    }
}

/** A heart's whole size, its rim included, and the rim. */
private val HEART_SIZE = 72.dp
private val HEART_RIM = 4.dp

/** How far above the finger a heart's centre sits, and how far it floats up as it fades. */
private val HEART_ABOVE = 40.dp
private val HEART_RISE = 32.dp

/** A heart's first size, of its whole; it grows to its whole size over Motion.ARRIVE_MS. */
private const val HEART_FROM = 0.4f

/** How long it takes to float up and fade, after growing. */
private const val HEART_OUT_MS = 320

/** With reduced motion, how long it stays between fading in and out. */
private const val HEART_STAY_MS = 300L
