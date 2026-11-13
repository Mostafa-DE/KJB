package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.lyrify.R
import app.lyrify.library.Song
import app.lyrify.library.songOrder
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.SortSongs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

// Custom order (user feedback 2026-11-13): Favourites' songs and every playlist list in their custom order by default,
// the order the rows' handles change. A drag on a handle moves the song at once, no long press: the row lifts and
// follows the finger, its neighbours slide out of its way with a light tick per place, and the list scrolls near its
// edges. TalkBack moves a song with "Move up" and "Move down". Each move is stored at once in playlists.json, which the
// backup carries as it is.

/**
 * How Favourites' songs and a playlist are listed: their custom order (the one the handles change), by title, or the
 * songs added to the phone last first. [words] in the sort sheet, [chip] on the list header's chip.
 */
enum class ListOrder(@StringRes val words: Int, @StringRes val chip: Int) {
    CUSTOM(R.string.lib_sort_custom, R.string.lib_sort_custom),
    TITLE(R.string.lib_sort_title, R.string.lib_sort_title),
    RECENTLY_ADDED(R.string.lib_recently_added, R.string.lib_sort_recent_short),
    ;

    /** Only the custom order shows the handles: it is the order they change. */
    val reorders: Boolean get() = this == CUSTOM

    companion object {
        /** Where both lists open, for everyone, those with playlists and Favourites from before too: the custom order. */
        val START: ListOrder = CUSTOM
    }
}

/**
 * [entries] (each a song, [song] of it, with what goes with it, such as its place in a playlist) in [order]: the custom
 * order as they are; else as Library's Songs tab sorts them ([songOrder]), ties in their custom order.
 */
fun <T> inOrder(entries: List<T>, order: ListOrder, settings: LibrarySettings, song: (T) -> Song): List<T> = when (order) {
    ListOrder.CUSTOM -> entries
    ListOrder.TITLE -> entries.sortedWith(compareBy(songOrder(SortSongs.TITLE, settings), song))
    ListOrder.RECENTLY_ADDED -> entries.sortedWith(compareBy(songOrder(SortSongs.RECENTLY_ADDED, settings), song))
}

/** The sort sheet of Favourites' songs and of a playlist. */
@Composable
fun ListOrderSheet(order: ListOrder, onChoice: (ListOrder) -> Unit, onDismiss: () -> Unit) {
    SortSheet(stringResource(R.string.lib_sort), ListOrder.entries.map { it to stringResource(it.words) }, order, onChoice, onDismiss)
}

/** A page's own order (Track, Newest) or by title; [byTitle] is the page's choice, kept across a turn. */
@Composable
fun OrderSheet(@StringRes own: Int, byTitle: Boolean, onChoice: (Boolean) -> Unit, onDismiss: () -> Unit) {
    SortSheet(stringResource(R.string.lib_sort), listOf(false to stringResource(own), true to stringResource(R.string.lib_sort_title)), byTitle, onChoice, onDismiss)
}

/** One of TalkBack's moves on a row in a custom order: its [words], and the place the song goes to. */
data class RowMove(@StringRes val words: Int, val to: Int)

/** TalkBack's moves for the row at [place] of [size]: Move up unless it is the first, Move down unless it is the last. */
fun rowMoves(place: Int, size: Int): List<RowMove> = listOfNotNull(
    RowMove(R.string.lib_move_up, place - 1).takeIf { place > 0 },
    RowMove(R.string.lib_move_down, place + 1).takeIf { place < size - 1 },
)

/**
 * How far a list scrolls this frame while a held row is near its edges: back while the row's [top] is within [edge] px
 * of the list's [start], on while its [bottom] is within [edge] of its [end], faster the closer, up to [most] px. Zero
 * anywhere else.
 */
fun edgeScroll(top: Float, bottom: Float, start: Float, end: Float, edge: Float, most: Float): Float = when {
    top < start + edge -> -most * ((start + edge - top) / edge).coerceAtMost(1f)
    bottom > end - edge -> most * ((bottom - (end - edge)) / edge).coerceAtMost(1f)
    else -> 0f
}

/**
 * A list of songs being put in another order by a row's handle, or by TalkBack ([move]). While a row is [held] the list
 * shows its songs in the drag's order ([shown]): the held row follows the finger, [offset] px from its place, and takes a
 * neighbour's place each time it passes half of it ([reorderStep], the queue's), with a [tick]. Each change of order
 * first [keepPlace]s the list, which would otherwise keep its first row on screen in view and jump. Let go ([drop]), the
 * row settles into its place and [store] gets the move, by places in the list; the order shown stays until the list
 * itself has it, so the row never jumps back while it is stored.
 */
@Stable
class SongReorder(
    private val tick: () -> Unit = {},
    private val keepPlace: () -> Unit = {},
    private val store: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    /** The list as it was when the drag started: [order] and [held] are places in it. */
    private var base: List<Song> by mutableStateOf(emptyList())

    private var order: List<Int>? by mutableStateOf(null)

    /** The held row, by its place in the list when the drag started; null while none is. */
    var held: Int? by mutableStateOf(null)
        private set

    /** The held row's key ([songRowKeys], which a move keeps): it stays lifted until it has settled. */
    var heldKey: Any? by mutableStateOf(null)
        private set

    var offset: Float by mutableFloatStateOf(0f)
        private set

    /** The places of [songs] in the order shown: a drag's, while [songs] is still the list it started on, else theirs. */
    fun shown(songs: List<Song>): List<Int> = order?.takeIf { base == songs } ?: songs.indices.toList()

    /** The handle of the row at [place] of [songs], keyed [key], is held. */
    fun start(songs: List<Song>, place: Int, key: Any) {
        base = songs
        order = songs.indices.toList()
        held = place
        heldKey = key
        offset = 0f
    }

    /** The held row moved [dy] px with the finger, or the list scrolled that far under it; each row is [row] px tall. */
    fun drag(dy: Float, row: Float) {
        val place = held ?: return
        val now = order ?: return
        val at = now.indexOf(place)
        val (to, left) = reorderStep(at, now.size, offset + dy, row)
        if (to != at) {
            keepPlace()
            order = now.toMutableList().apply { add(to, removeAt(at)) }
            repeat(abs(to - at)) { tick() }
        }
        offset = left
    }

    /**
     * The held row let go in [songs], the list as it is now: the move it made (from its place to where it is now), stored,
     * or null when it is back at its place or the list changed under it.
     */
    fun drop(songs: List<Song>): Pair<Int, Int>? {
        val place = held ?: return null
        held = null
        val to = order?.indexOf(place) ?: place
        if (to == place || base != songs) {
            order = null
            return null
        }
        store(place, to)
        return place to to
    }

    /** The row let go eases into its place, then lands. */
    suspend fun settle(spec: AnimationSpec<Float>) {
        val key = heldKey
        try {
            animate(offset, 0f, animationSpec = spec) { value, _ -> if (held == null && heldKey == key) offset = value }
        } finally {
            if (held == null && heldKey == key) {
                offset = 0f
                heldKey = null
            }
        }
    }

    /** TalkBack's Move up or Move down: the song at [from] goes to [to], stored at once. */
    fun move(from: Int, to: Int) = store(from, to)

    /** The move could not be stored: the list's own order shows again. */
    fun forget() {
        order = null
    }
}

/**
 * A page's [SongReorder] for its list [state]: a tick per place passed and, while a row is held, the list scrolling near
 * its edges ([edgeScroll]) with the row staying under the finger. [store] stores a move, by places in the list shown,
 * and gives what to say when it could not be; then the list's own order shows again.
 */
@Composable
fun rememberSongReorder(state: LazyListState, store: suspend (from: Int, to: Int) -> Outcome?): SongReorder {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val latest by rememberUpdatedState(store)
    val reorder = remember(state, haptics) {
        lateinit var made: SongReorder
        SongReorder(
            tick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            keepPlace = { state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
            store = { from, to ->
                scope.launch {
                    latest(from, to)?.let { failed ->
                        made.forget()
                        toasts.say(resources, failed)
                    }
                }
            },
        ).also { made = it }
    }
    val density = LocalDensity.current
    val holding = reorder.held != null
    LaunchedEffect(reorder, holding) {
        if (!holding) return@LaunchedEffect
        val edge = with(density) { SCROLL_EDGE.toPx() }
        val fastest = with(density) { SCROLL_FASTEST.toPx() }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            val info = state.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == reorder.heldKey } ?: continue
            val top = row.offset + reorder.offset
            val step = edgeScroll(top, top + row.size, 0f, (info.viewportEndOffset - info.afterContentPadding).toFloat(), edge, fastest * seconds)
            if (step == 0f) continue
            val scrolled = try {
                state.scrollBy(step)
            } catch (e: CancellationException) {
                // A move's keepPlace stops a scroll under way; the loop goes on unless it is the one stopped.
                coroutineContext.ensureActive()
                0f
            }
            if (scrolled != 0f) reorder.drag(scrolled, row.size.toFloat())
        }
    }
    return reorder
}

/**
 * A row's drag handle (a 48 dp target at its start): a drag on it moves the song, at [place] of [songs], at once, no
 * long press, through [reorder]. [row] is the row's height in px. TalkBack has the row's Move up and Move down instead,
 * so the handle is no stop of its own.
 */
@Composable
fun DragHandle(reorder: SongReorder, songs: List<Song>, place: Int, key: Any, row: () -> Float) {
    val scope = rememberCoroutineScope()
    val list by rememberUpdatedState(songs)
    val at by rememberUpdatedState(place)
    val reduced = LocalReducedMotion.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .testTag(HANDLE_TAG)
            .clearAndSetSemantics {}
            .pointerInput(reorder, key) {
                val letGo = {
                    reorder.drop(list)
                    scope.launch { reorder.settle(motion(tween(Motion.SHORT_MS, easing = Motion.Standard), reduced)) }
                }
                detectDragGestures(
                    onDragStart = { reorder.start(list, at, key) },
                    onDragEnd = { letGo() },
                    onDragCancel = { letGo() },
                ) { change, amount ->
                    change.consume()
                    reorder.drag(amount.y, row())
                }
            },
    ) {
        Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/**
 * How a row of a list being reordered moves: held ([lift] going to 1), it follows the finger above the others, a little
 * larger, on a [raised] fill with a shadow; the others slide out of its way, a held one never (it is under the finger).
 */
fun LazyItemScope.reorderMotion(reorder: SongReorder, key: Any, lift: State<Float>, raised: Color, reduced: Boolean, density: Density): Modifier {
    val held = reorder.heldKey == key
    val shadow = with(density) { LIFT.toPx() }
    val placement = if (held || reduced) null else spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)
    return Modifier
        .animateItem(fadeInSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.FADE_IN_MS), placementSpec = placement, fadeOutSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.FADE_OUT_MS))
        .zIndex(if (held) 1f else 0f)
        .graphicsLayer {
            if (held) translationY = reorder.offset
            val up = lift.value
            scaleX = 1f + LIFT_SCALE * up
            scaleY = 1f + LIFT_SCALE * up
            shadowElevation = shadow * up
            shape = RowFillShape
        }
        .drawBehind {
            val up = lift.value
            if (up > 0f) drawOutline(RowFillShape.createOutline(size, layoutDirection, this), raised, alpha = up)
        }
}

// Row fills: a highlighted row's, and a held row's lift

/**
 * Where a highlighted row's fill (a selected song, the queue's song here) goes in a row of [size]: its own rounded
 * rectangle, [ROW_FILL_INSET] in from the sides and [ROW_FILL_GAP] from the top and the bottom, corners [ROW_FILL_CORNER],
 * so two highlighted neighbours read as separate rows (user feedback 2026-11-13). The row and its touch target keep their size.
 */
fun rowFill(size: Size, density: Density): RoundRect = with(density) {
    val inset = ROW_FILL_INSET.toPx()
    val gap = ROW_FILL_GAP.toPx()
    RoundRect(inset, gap, size.width - inset, size.height - gap, CornerRadius(ROW_FILL_CORNER.toPx()))
}

/** [rowFill] as a shape: the queue's song here, and a held row's lift and shadow. */
val RowFillShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rounded(rowFill(size, density))
}

/** Draws a highlighted row's fill ([rowFill]); a clear [color] draws nothing. */
fun DrawScope.drawRowFill(color: Color) {
    if (color.alpha > 0f) drawOutline(Outline.Rounded(rowFill(size, this)), color)
}

val ROW_FILL_INSET = 8.dp
val ROW_FILL_GAP = 2.dp
val ROW_FILL_CORNER = 12.dp

/** The round check's test tag: TalkBack has it on the row, so device tests find it by this. */
const val CHECK_TAG: String = "song check"

/** A held row is lifted: a little larger, with a shadow (the queue's rows too). */
const val LIFT_SCALE: Float = 0.03f
val LIFT = 8.dp

/** Within this of a list's edge a held row scrolls it, at most this fast a second (deepest in the edge). */
private val SCROLL_EDGE = 64.dp
private val SCROLL_FASTEST = 900.dp

/** The handle's test tag: it is no TalkBack stop, so device tests find it by this. */
const val HANDLE_TAG: String = "song handle"
