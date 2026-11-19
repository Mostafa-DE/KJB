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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Double-tap to favourite (KJB 3.1, user 2026-11-18): a song, album, artist or playlist wherever it is listed, and the
// song on the player's cover. A song's row waits a moment after a tap (TAP_WAIT_MS) to see whether a second comes: if
// none does it plays as before, and a double tap only makes the song a favourite, nothing playing (user 2026-11-18,
// after trying taps that played at once). An album's, artist's or playlist's tap, and the cover's, is never delayed: it
// acts at once. A second tap soon after, on the same item, has its lift taken by the host (the shell, or a sheet with a
// list of its own), so nothing under it acts on it (the page the first tap opened), and makes what the first tap was on
// a favourite, with a heart at the finger and "Added to Favourites" with Undo. It only ever adds. A second touch that
// turns into a long press or a scroll is left to what is under it, and a song's tap waiting then never plays, nor does
// it when the touch lands on the rest of its row (the handle, "⋯").
// TalkBack's double tap is its own: while TalkBack explores nothing here runs or waits, and Favourite stays in each
// item's "⋯" and on each page.

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
 * of the first one's lift, no sooner than [minGapMs] (sooner is the finger bouncing), on the item the first was on (its
 * bounds then, give or take [touchSlopPx]). A tap moves no further than [touchSlopPx] and lifts within [holdMs] (later
 * is a long press). Times in ms, distances in px.
 */
data class TapTiming(val windowMs: Long, val minGapMs: Long, val touchSlopPx: Float, val holdMs: Long)

/** A heart popping where a second tap landed ([position], in the window, px). */
class HeartPop internal constructor(val id: Long, val position: Offset)

/**
 * The double taps over one host ([doubleTapHost]). Items tell it their taps as they lift ([tapped], through
 * [doubleTapFavourite]); the host passes it every touch first ([down], [move], [up]). An item's tap acts at once, but a
 * song row's ([WaitingTap]) waits here [TAP_WAIT_MS] and acts only if no second tap comes. The touch after a tap may be
 * that tap's second when it lands within the double-tap time (for a waiting tap, its wait) where the item was
 * ([TapTiming]), whatever is there now (the page the tap opened): [down] is true, the host watches it, and a tap
 * waiting will not act now. Lifted as a tap, the host takes the lift ([up] is true: nothing under it acts on it), a
 * heart pops where it landed ([pops]) and the first tap's target goes to [onDouble]. A second touch that moves past the
 * touch slop (a scroll) or is held (a long press) is let go whole and does nothing here. A further tap soon after only
 * pops another heart: nothing is stored again, so the message's Undo stays. A touch on the rest of a song's row ends
 * the wait, its tap never acting; any other touch ends it, a tap waiting acting first. Off ([enabled] false: TalkBack
 * explores), it records, waits for and takes nothing. Times are the touches' uptimes, places in the window; the waits
 * run on [scope]. Main thread only.
 */
@Stable
class DoubleTaps(internal val timing: TapTiming, private val scope: CoroutineScope, private val onDouble: (FavouriteTarget) -> Unit) {
    var enabled: Boolean by mutableStateOf(true)

    private val shown = mutableStateListOf<HeartPop>()

    /** The hearts popping now, the newest last; [HeartPops] draws them. */
    val pops: List<HeartPop> get() = shown

    private var first: FirstTap? = null
    private var taken: Taken? = null
    private var wait: Wait? = null
    private var ids = 0L

    /**
     * A tap on [target], in [area], its finger lifted at [at]; [onSecond]: what its second tap does besides. It acts at
     * once, or with [waiting] (a song's row) its click waits here ([WaitingTap.click]), and a touch soon after on the
     * rest of [whole] (all of the row, its handle and "⋯" too) drops it. A tap still waiting acts first.
     */
    fun tapped(target: FavouriteTarget, at: Long, area: Rect, waiting: WaitingTap? = null, whole: Rect = area, onSecond: (() -> Unit)? = null) {
        if (!enabled) return
        play()
        first = FirstTap(target, at, area, whole, onSecond, doubled = false, waits = waiting != null, window = if (waiting != null) TAP_WAIT_MS else timing.windowMs)
        if (waiting == null) return
        waiting.host = this
        wait = Wait(
            waiting,
            scope.launch {
                delay(TAP_WAIT_MS)
                // No second tap came: the tap acts, and the next one is a first. With the finger bouncing on the row,
                // once that touch lifts as a tap ([up]).
                if (taken?.bounce == true) wait?.due = true else finish()
            },
        )
    }

    /**
     * [row]'s click: true when it is the click of the row's tap waiting here, which acts once its wait is over. A further
     * click while it waits (a keyboard's) acts at once instead, and the wait never does.
     */
    internal fun clicked(row: WaitingTap): Boolean {
        val waiting = wait?.takeIf { it.row === row } ?: return false
        if (waiting.clicked) {
            abandon()
            return false
        }
        waiting.clicked = true
        return true
    }

    /**
     * A finger down at [at], [position]: true when it may be a tap's second (or, on a song's row, its bounce), which
     * the host then watches. One on the rest of a song's row (its handle, its "⋯") ends the wait, the tap waiting never
     * acting, and is theirs. Any other touch ends the wait, a tap waiting acting now, before anything under it sees it.
     */
    fun down(at: Long, position: Offset): Boolean {
        val tap = first
        taken = null
        if (!enabled || tap == null) {
            first = null
            play()
            return false
        }
        val gap = at - tap.at
        val onItem = tap.area.inflate(timing.touchSlopPx).contains(position)
        // The finger bouncing on a song's row is that same tap: the host takes it whole, and the wait goes on.
        if (onItem && tap.waits && gap < timing.minGapMs) {
            taken = Taken(tap, at, position, bounce = true)
            return true
        }
        first = null
        // The row's own handle or "⋯" soon after its tap: that tap was on the way to them (a drag, the song's sheet).
        if (!onItem && tap.waits && gap <= tap.window && tap.whole.contains(position)) {
            drop()
            return false
        }
        if (!onItem || gap < timing.minGapMs || gap > tap.window) {
            play()
            return false
        }
        // A second touch on the item: a tap waiting never acts now, whether this one doubles it or is a long press or a
        // scroll of its own.
        drop()
        taken = Taken(tap, at, position)
        return true
    }

    /**
     * The watched finger moved to [position]: false once it is no tap (a scroll), and the host lets it go. A bounce that
     * scrolls makes its tap none, never acting.
     */
    fun move(position: Offset): Boolean {
        val held = taken ?: return false
        if ((position - held.position).getDistance() <= timing.touchSlopPx) return true
        taken = null
        if (held.bounce) abandon()
        return false
    }

    /**
     * The watched finger lifted at [at], [position]: true when it was a tap, which doubles its first tap (or was that
     * tap's bounce, the tap acting now if its wait ran out meanwhile) and whose lift the host takes. One held past a long
     * press belongs to what is under it, and goes on to it untouched; a bounce held so makes its tap none.
     */
    fun up(at: Long, position: Offset): Boolean {
        val held = taken ?: return false
        taken = null
        if (at - held.at >= timing.holdMs || (position - held.position).getDistance() > timing.touchSlopPx) {
            if (held.bounce) abandon()
            return false
        }
        if (held.bounce) {
            if (wait?.due == true) finish()
            return true
        }
        shown += HeartPop(++ids, held.position)
        // A further tap soon after, on the same item, carries on this double tap.
        first = held.tap.copy(at = at, doubled = true)
        if (held.tap.doubled) return true
        held.tap.onSecond?.invoke()
        onDouble(held.tap.target)
        return true
    }

    /** [target] made a favourite as a double tap on this host makes it, with its message and Undo. */
    internal fun favourite(target: FavouriteTarget) = onDouble(target)

    /**
     * The system took the touch away (another window, a gesture of its own, a second finger): the host watches nothing,
     * and a bounce taken away makes its tap none.
     */
    fun cancel() {
        if (taken?.bounce == true) abandon()
        taken = null
    }

    /** The host is going (a sheet closing, its scope with it): a tap still waiting acts now, rather than never. */
    fun settle() = finish()

    /** [pop]'s heart has finished. */
    fun popped(pop: HeartPop) {
        shown -= pop
    }

    /** The tap waiting, if any, acts now (if its click came) and waits no more. */
    private fun play() {
        val ending = wait ?: return
        wait = null
        ending.end.cancel()
        if (ending.clicked) ending.row.act()
    }

    /** The tap waiting, if any, never acts. */
    private fun drop() {
        wait?.end?.cancel()
        wait = null
    }

    /** The wait is over with no second tap: the tap waiting acts, and the next touch is a first. */
    private fun finish() {
        first = null
        play()
    }

    /**
     * The tap waiting never acts (its finger bounced and was then held, moved or taken away, or another click acted for
     * it), and the next touch is a first.
     */
    private fun abandon() {
        first = null
        drop()
    }

    /**
     * A tap that a second may double, on [area] (in [whole], all of its row), within [window] of [at]; [doubled]: it
     * was a second tap already, and a further one only pops a heart; [waits]: a song row's, whose bounce is its own.
     */
    private data class FirstTap(
        val target: FavouriteTarget,
        val at: Long,
        val area: Rect,
        val whole: Rect,
        val onSecond: (() -> Unit)?,
        val doubled: Boolean,
        val waits: Boolean,
        val window: Long,
    )

    /** The finger the host watches after [tap]: its second, or ([bounce]) that same tap's finger bouncing. */
    private class Taken(val tap: FirstTap, val at: Long, val position: Offset, val bounce: Boolean = false)

    /**
     * A song row's tap waiting: [clicked] once the row's click came; [end] runs out the wait; [due] once it ran out with
     * the finger bouncing on the row.
     */
    private class Wait(val row: WaitingTap, val end: Job) {
        var clicked = false
        var due = false
    }
}

/** How long a song row's tap waits for a second before it plays (user 2026-11-18: "wait a moment", about 0.25 s). */
internal const val TAP_WAIT_MS: Long = 250

/**
 * A song row's tap, held back [TAP_WAIT_MS] to see whether a second tap comes ([rememberWaitingTap]). Give it to the
 * row's [doubleTapFavourite] and make [click] the row's onClick: the click of the row's own tap then plays once the
 * wait is over with no second tap on the row, or at once as the next touch lands off the row (never, if on its handle or
 * "⋯"), and a double tap only makes the song a favourite. Its press still shows at once: the row's clickable takes the tap as before. A click with
 * no tap of the row's own waiting (TalkBack's, a keyboard's, a list's while it selects) acts at once.
 */
@Stable
class WaitingTap internal constructor(private val onTap: () -> Unit) {
    /** The host the row's last tap waited in. */
    internal var host: DoubleTaps? = null

    /** The row's onClick. */
    fun click() {
        if (host?.clicked(this) != true) onTap()
    }

    /** The wait is over with no second tap: the row's tap acts. */
    internal fun act() = onTap()
}

/** A song row's [WaitingTap], acting with the latest [onTap]. */
@Composable
fun rememberWaitingTap(onTap: () -> Unit): WaitingTap {
    val latest by rememberUpdatedState(onTap)
    return remember { WaitingTap { latest() } }
}

/**
 * How a host's double tap stores its favourite: through the [shell]'s, when there is one above it (a sheet's host:
 * the sheet's scope ends as it closes, which would take the message, its Undo and even the write with it), else [own].
 */
internal fun favouriteThrough(shell: DoubleTaps?, own: (FavouriteTarget) -> Unit): (FavouriteTarget) -> Unit = shell?.let { it::favourite } ?: own

/** The shell's [DoubleTaps], for a sheet's own host to store its favourites through ([favouriteThrough]). */
val LocalDoubleTaps = staticCompositionLocalOf<DoubleTaps?> { null }

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
 * The app's [DoubleTaps] for a host: the phone's double-tap time and distances, off while TalkBack explores, a song
 * row's tap waiting on this host's scope (and acting at once if the host goes first), and a double tap's favourite
 * stored at once ([SongActions.addFavourite]) with its message and Undo, on this host's scope, or a sheet's through the
 * shell's ([LocalDoubleTaps], [favouriteThrough]).
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
        TapTiming(config.doubleTapTimeoutMillis, config.doubleTapMinTimeMillis, config.touchSlop, config.longPressTimeoutMillis)
    }
    val own: (FavouriteTarget) -> Unit = { target ->
        scope.launch {
            val now = LibraryGraph.playlists(context).state.first()
            val library = (LibraryGraph.repository(context).state.value as? LibraryState.Ready)?.library
            toasts.say(resources, actions.addFavourite(target, now, library))
        }
    }
    val favourite by rememberUpdatedState(favouriteThrough(LocalDoubleTaps.current, own))
    val taps = remember(timing) { DoubleTaps(timing, scope) { favourite(it) }.also { it.enabled = !exploring } }
    SideEffect { taps.enabled = !exploring }
    DisposableEffect(taps) { onDispose { taps.settle() } }
    return taps
}

/**
 * Makes this the host of [taps]' double taps: the items under it ([doubleTapFavourite]) tell it their taps, and it sees
 * every touch here first. A tap's second keeps its press, but its lift is the host's (consumed before anything under it
 * sees it, the page a first tap opened included), so nothing there acts on it; every other touch, and a second one that
 * turns into a long press or a scroll, goes through untouched. Put it on the shell (also providing [LocalDoubleTaps]), or
 * on a sheet's content, as a sheet is a window of its own; with it, [HeartPops] draws the hearts.
 */
fun Modifier.doubleTapHost(taps: DoubleTaps): Modifier = this then DoubleTapHostElement(taps)

/**
 * Double-tap to favourite on an item that acts on a single tap (a song's row plays it, an album's card opens its page):
 * a second tap soon after, on the item, makes [target] a favourite (taken by the host above it, [doubleTapHost]). Its
 * taps act at once, or with [waiting] (a song's row, whose clickable's onClick is [WaitingTap.click]) once a moment has
 * passed with no second tap ([TAP_WAIT_MS]), and never if a touch lands meanwhile on the rest of the row (the item's
 * parent: its handle, its "⋯"). [onSecond]: what that second tap does besides (the player's cover turns back). Put it
 * right before the item's clickable, over the same bounds. Null [target]: no double tap for now (a list
 * while it selects; a waiting row's click then acts at once); the node stays, so the chain keeps its shape as that
 * changes.
 */
fun Modifier.doubleTapFavourite(target: FavouriteTarget?, onSecond: (() -> Unit)? = null, waiting: WaitingTap? = null): Modifier =
    this then DoubleTapElement(target, onSecond, waiting)

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

/** The host's touch: it sees each one before anything under it (the Initial pass) and takes a second tap's lift. */
private class DoubleTapHostNode(var taps: DoubleTaps) : Modifier.Node(), PointerInputModifierNode, TraversableNode {
    override val traverseKey: Any get() = DoubleTapHostKey

    /** The finger watched as a possible second tap, while it is down. */
    private var held: PointerId? = null

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        // A second finger makes it no tap.
        val change = pointerEvent.changes.singleOrNull() ?: return reset()
        when {
            // The press stays the page's, so a long press there still starts.
            change.changedToDownIgnoreConsumed() -> held = change.id.takeIf { taps.down(change.uptimeMillis, window(change.position)) }
            change.id != held -> Unit
            change.changedToUpIgnoreConsumed() -> {
                held = null
                // A tap: its lift is the host's, so nothing under it clicks.
                if (taps.up(change.uptimeMillis, window(change.position))) change.consume()
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

private data class DoubleTapElement(val target: FavouriteTarget?, val onSecond: (() -> Unit)?, val waiting: WaitingTap?) : ModifierNodeElement<DoubleTapNode>() {
    override fun create(): DoubleTapNode = DoubleTapNode(target, onSecond, waiting)

    override fun update(node: DoubleTapNode) {
        node.target = target
        node.onSecond = onSecond
        node.waiting = waiting
    }
}

/**
 * An item's touch, watched and never consumed: a tap ([TapWatch]) is told to the nearest host above it, if any, as its
 * finger lifts and before the item's clickable sees that lift, so a song row's click finds its tap waiting. A lift the
 * host took (consumed before it got here) ends a second tap, never a first. Its moves are checked once everything has
 * had them (the Final pass): one taken by a scroll makes it no tap.
 */
private class DoubleTapNode(var target: FavouriteTarget?, var onSecond: (() -> Unit)?, var waiting: WaitingTap?) : Modifier.Node(), PointerInputModifierNode {
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
                if (target == null || change.isConsumed || !taps.enabled) return
                pointer = change.id
                watch = TapWatch(taps.timing).also { it.down(change.uptimeMillis, change.position) }
            }
            change.id != pointer -> Unit
            change.changedToUpIgnoreConsumed() -> {
                pointer = null
                // The host took this lift: a second tap, never a first.
                if (change.isConsumed) return
                val tapped = target?.takeIf { watch?.up(change.uptimeMillis, change.position) == true } ?: return
                val here = requireLayoutCoordinates()
                val area = here.boundsInWindow()
                // A song's row: all of it, its handle and "⋯" too, is the item's parent.
                val whole = waiting?.let { here.parentLayoutCoordinates?.boundsInWindow() } ?: area
                taps.tapped(tapped, change.uptimeMillis, area, waiting, whole, onSecond)
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
