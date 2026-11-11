package app.lyrify.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.lerp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Top tabs that swipe (phone test 2026-11-11, points 2 and 4), as Samsung Music's: Library's, Favourites', and Covers and
// tags' once it adopts them. The pages follow the finger left and right, the tabs' sung line follows the pages, a tap
// glides there, and every tab's page stays composed once the pager has settled in, so going back to a tab finds its
// list where it was, never read or built again. A drag inside a page that is the page's own (a row's swipe, a list
// scrolling sideways) wins over the pager, as Compose gives a gesture to the innermost one first; the system's back
// gesture starts at the screen's edge, outside the pager's reach.

// The pure rules

/**
 * The sung line under top tabs whose pages are at [position] (the page shown plus the fraction of the way to the next):
 * [width] wide and centred under the tab at a whole position; between two tabs it travels between their [centres], its
 * leading end ahead of its trailing one, so it stretches on the way and lands whole (the dock's line does the same).
 * Null while a tab it needs is not laid out.
 */
fun tabLine(position: Float, centres: (Int) -> Float?, width: Float): ClosedFloatingPointRange<Float>? {
    val from = floor(position).toInt()
    val way = position - from
    val start = centres(from) ?: return null
    if (way <= 0f) return (start - width / 2)..(start + width / 2)
    val end = centres(from + 1) ?: return null
    val lead = start + (end - start) * (1f - (1f - way) * (1f - way))
    val trail = start + (end - start) * way * way
    return (min(lead, trail) - width / 2)..(max(lead, trail) + width / 2)
}

/** How the pages go to a tab chosen elsewhere: not at all, gliding there, or there at once. */
enum class TabMove { NONE, GLIDE, JUMP }

/**
 * The pages, showing or going to [current], asked for [target] (-1: a tab not shown): they glide there while the page
 * is [onScreen] (Library's icon tapped again on its root lands on Songs), and are simply there when it was not (the same
 * icon tapped from another dock destination): nothing moves behind the page as it fades in.
 */
fun tabMove(target: Int, current: Int, onScreen: Boolean): TabMove = when {
    target < 0 || target == current -> TabMove.NONE
    onScreen -> TabMove.GLIDE
    else -> TabMove.JUMP
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

    /**
     * The tab at [at] tapped: the app's record of the tab shown has it at once (so the dock tapped while the pages glide
     * still finds the tab they go to), and the pages glide to it, the line with them; with reduced motion they are there.
     */
    fun pick(at: Int) {
        chosen.value(at)
        glide(at)
    }

    internal fun glide(at: Int) {
        scope.launch { if (reduced) pager.scrollToPage(at) else pager.animateScrollToPage(at, animationSpec = tween(Motion.OPEN_MS, easing = Motion.Standard)) }
    }
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
        when (tabMove(selected, pager.targetPage, onScreen.value)) {
            TabMove.NONE -> Unit
            TabMove.GLIDE -> tabs.glide(selected)
            TabMove.JUMP -> pager.scrollToPage(selected)
        }
    }
    SideEffect { onScreen.value = headed }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { choose.value(it) } }
    return tabs
}

/**
 * The text tabs ([labels]) under a top bar, scrolling sideways when they do not fit. The sung line follows the pages
 * under the finger ([tabLine], read while drawing, so a swipe recomposes nothing); a tab takes the chosen look as the
 * pages pass half way, and a tap glides there.
 */
@Composable
fun PagerTabs(state: TabPager, labels: List<String>, modifier: Modifier = Modifier) {
    val highlight = LocalBrandColors.current.highlight
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val row = rememberLazyListState()
    val current = state.pager.currentPage
    // The tab shown stays in view, with the one before it.
    val kept = remember { Kept(current) }
    LaunchedEffect(current) {
        if (current != kept.value) row.animateScrollToItem((current - 1).coerceAtLeast(0))
        kept.value = current
    }
    LazyRow(
        state = row,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .drawWithContent {
                drawContent()
                val laid = row.layoutInfo
                val centre: (Int) -> Float? = { at -> laid.visibleItemsInfo.firstOrNull { it.index == at }?.let { it.offset - laid.viewportStartOffset + it.size / 2f } }
                val line = tabLine(state.position, centre, SUNG_LINE_WIDTH.toPx()) ?: return@drawWithContent
                val height = SUNG_LINE_HEIGHT.toPx()
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - line.endInclusive else line.start
                drawRoundRect(highlight, Offset(left, size.height - TAB_LINE_BOTTOM.toPx() - height), Size(line.endInclusive - line.start, height), CornerRadius(height / 2))
            },
    ) {
        itemsIndexed(labels, contentType = { _, _ -> "tab" }) { at, name ->
            val on = at == current
            val lit by animateFloatAsState(if (on) 1f else 0f, motion(tween(Motion.SHORT_MS, easing = Motion.Standard), reduced), label = "tab")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(on, role = Role.Tab) { state.pick(at) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
            ) {
                Text(name, style = TabText, fontWeight = lerp(FontWeight.Medium, FontWeight(650), lit), color = lerp(colors.onSurfaceVariant, colors.onSurface, lit), maxLines = 1)
            }
        }
    }
}

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
    HorizontalPager(
        state = state.pager,
        modifier = modifier,
        beyondViewportPageCount = keptBeyond(count, frames),
        verticalAlignment = Alignment.Top,
        userScrollEnabled = swipe,
        key = { keys[it] },
    ) { at ->
        CompositionLocalProvider(LocalTabCurrent provides (at == state.pager.currentPage)) { page(at) }
    }
}

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
private val TabText = body(15f, 20, 500)

/** The sung line sits this far above the tabs' bottom edge. */
private val TAB_LINE_BOTTOM = 6.dp
