package app.lyrify.ui

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.addon.AddonState
import app.lyrify.addon.KjbAddons
import app.lyrify.addon.packageChanges
import app.lyrify.downloads.DlAddon
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.settings.DisplaySettings
import app.lyrify.settings.DockItemSetting
import app.lyrify.settings.DockTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

// The bottom bar the user makes (KJB 3.1, user 2026-11-18): which items it holds and in what order, how its names fit,
// and hiding it while a page scrolls. The rules are pure, so they are tested on the JVM; the bar itself is in Chrome.kt.

/** Whether the phone can offer [tab] on the bar now: Downloads while its add-on is installed, Books while the library holds a book. */
fun dockOffers(tab: DockTab, downloads: Boolean, books: Boolean): Boolean = when (tab) {
    DockTab.DOWNLOADS -> downloads
    DockTab.BOOKS -> books
    else -> true
}

/**
 * The bar's items in order: those switched on in [setting] that are [offered] now, at most [DisplaySettings.MAX_DOCK],
 * Library always among them. More than that only once an item the phone could not offer is back (its add-on installed
 * again): then Library and the first others in the order, so the bar never grows past its size.
 */
fun dockItems(setting: List<DockItemSetting>, offered: (DockTab) -> Boolean): List<DockTab> {
    val on = setting.filter { it.on && offered(it.tab) }.map { it.tab }
    if (on.size <= DisplaySettings.MAX_DOCK) return on
    val kept = on.filter { it != DockTab.LIBRARY }.take(DisplaySettings.MAX_DOCK - 1).toSet() + DockTab.LIBRARY
    return on.filter { it in kept }
}

/** Whether one more item fits on the bar: fewer than [DisplaySettings.MAX_DOCK] of those switched on are [offered]. */
fun dockHasRoom(setting: List<DockItemSetting>, offered: (DockTab) -> Boolean): Boolean =
    setting.count { it.on && offered(it.tab) } < DisplaySettings.MAX_DOCK

/** [setting] with [tab] switched [on]; Library never goes off, and nothing goes on once the bar is full ([dockHasRoom]). */
fun dockSwitched(setting: List<DockItemSetting>, tab: DockTab, on: Boolean, offered: (DockTab) -> Boolean): List<DockItemSetting> = when {
    tab == DockTab.LIBRARY -> setting
    on && setting.any { it.tab == tab && it.on } -> setting
    on && !dockHasRoom(setting, offered) -> setting
    else -> setting.map { if (it.tab == tab) it.copy(shown = on) else it }
}

/**
 * [setting] with [tab] moved one place [up] (or down) among the items [offered], as Settings lists them: it swaps with
 * the nearest of them that way, and the items not offered now (not listed) keep their places.
 */
fun dockMoved(setting: List<DockItemSetting>, tab: DockTab, up: Boolean, offered: (DockTab) -> Boolean): List<DockItemSetting> {
    val at = setting.indexOfFirst { it.tab == tab }
    if (at < 0) return setting
    val way = if (up) (at - 1 downTo 0) else (at + 1..setting.lastIndex)
    val other = way.firstOrNull { offered(setting[it].tab) } ?: return setting
    return setting.mapIndexed { index, item ->
        when (index) {
            at -> setting[other]
            other -> setting[at]
            else -> item
        }
    }
}

/**
 * The items switched on and [offered] that the bar has no room for ([dockItems]): only once an item the phone could not
 * offer is back on a full bar. Settings says so on their rows rather than show them as on the bar.
 */
fun dockLeftOff(setting: List<DockItemSetting>, offered: (DockTab) -> Boolean): Set<DockTab> {
    val items = dockItems(setting, offered)
    return setting.filter { it.on && offered(it.tab) && it.tab !in items }.map { it.tab }.toSet()
}

/** Search and Tools when the bar ([items]) leaves them off: Library's top bar shows a button for each, so both stay in reach. */
fun offBar(items: List<DockTab>): List<DockTab> = listOf(DockTab.SEARCH, DockTab.TOOLS).filter { it !in items }

/** The tab the app opens on: the [last] one while it is on the bar ([items]), else Library. */
fun dockStart(last: DockTab, items: List<DockTab>): DockTab = last.takeIf { it in items } ?: DockTab.LIBRARY

/**
 * The share of their full size a Standard bar's names are drawn at, one for all of them: whole while the [widest] fits
 * its item's [room] (px), else just small enough to fit (rounded down to a hundredth), down to [DOCK_LABEL_SMALLEST];
 * null when even that is too wide, and the names are left out (icons only, a hold shows the name), so no name is ever
 * cut or wrapped.
 */
fun dockLabelShare(widest: Float, room: Float): Float? {
    if (room <= 0f) return null
    if (widest <= room) return 1f
    // The hair over the floor keeps a share that is exactly a hundredth (0.9) from falling to the one below.
    val share = floor(room / widest * 100f + 0.001f) / 100f
    return share.takeIf { it >= DOCK_LABEL_SMALLEST }
}

/** How small a Standard bar's names may get: a little (user 2026-11-18, "shrinks slightly"). */
const val DOCK_LABEL_SMALLEST: Float = 0.75f

/**
 * Where the bar's items sit: [count] of them share the [width] (px) between the capsule's ends equally, from its start.
 * The items are laid out in these slots and the sung line is drawn at their middles, both from here, so the line is
 * always under its item. Each slot starts on a whole pixel and together they cover the width exactly, none more than
 * a pixel wider than another.
 */
class DockSlots(count: Int, private val width: Int) {
    private val count = count.coerceAtLeast(1)

    /**
     * Where slot [index] starts, px from the bar's start, at the pixel nearest its share, so its middle is within
     * half a pixel of the line's; slot [count]'s start is the bar's end.
     */
    fun start(index: Int): Int = ((index.toLong() * width * 2 + count) / (2L * count)).toInt()

    /** How wide slot [index] is. */
    fun size(index: Int): Int = start(index + 1) - start(index)

    /** The middle of the place [at], px from the bar's start: a slot's index, or between two while the line travels. */
    fun middle(at: Float): Float = (at + 0.5f) * width / count
}

/**
 * Hide while scrolling: whether the bar is [hidden] after the page's scrolls, and how far the content has moved the same
 * way since it last turned ([travel], px, toward its end when positive). Pure, so its rules are tested on the JVM.
 */
@Immutable
data class DockScroll(val hidden: Boolean = false, val travel: Float = 0f) {
    /**
     * After one scroll of a page: [consumedY] is what the page's list moved (Compose's sign: negative as the content goes
     * toward its end), [availableY] what it left over. Moving [threshold] px toward the end hides the bar, as far back
     * shows it; a finger pulling down with nothing left to scroll (the list at its top, or a page that cannot scroll)
     * shows it at once. A drag the page could not follow changes nothing.
     */
    fun scrolled(consumedY: Float, availableY: Float, threshold: Float): DockScroll {
        if (availableY > PULL_SLOP) return DockScroll()
        val moved = -consumedY
        if (moved == 0f) return this
        val total = if (moved * travel > 0f) travel + moved else moved
        val hide = when {
            total >= threshold -> true
            total <= -threshold -> false
            else -> hidden
        }
        return DockScroll(hide, total)
    }

    private companion object {
        /** Under half a pixel left over is a list's rounding, not a pull past its top. */
        const val PULL_SLOP = 0.5f
    }
}

/** On the bar: Library always is, whatever a hand-made setting says. */
private val DockItemSetting.on: Boolean get() = shown || tab == DockTab.LIBRARY

// In the composition

/**
 * The bar as the shell shows it ([LocalDockBar]): its [items] in order, whether Tools has its dot ([toolsDot]), what
 * the phone offers now ([offered]: KJB Downloads installed, a book in the library), and whether Huddle's dot pings, a
 * friend playing now ([huddleLive]: the shell's one state, which the dock and every header read where the dot shows, so
 * nothing else redraws; null outside the shell).
 */
@Stable
data class DockBar(
    val items: List<DockTab> = DOCK_TABS,
    val toolsDot: Boolean = false,
    val offered: DockOffers = DockOffers(downloads = false, books = false),
    val huddleLive: State<Boolean>? = null,
) {
    fun offers(tab: DockTab): Boolean = offered.offers(tab)
}

/** The shell's [DockBar], for Library's top bar and Settings > Bottom bar. */
val LocalDockBar = compositionLocalOf { DockBar() }

/**
 * What the phone can put on the bar now: whether KJB Downloads is installed (looked at again whenever it is installed or
 * removed, and as the app comes back) and whether the library holds a book; each null until known.
 */
@Immutable
data class DockOffers(val downloads: Boolean?, val books: Boolean?) {
    /** Whether [tab] is on offer: until the phone has said, it is, so the bar never fills in a moment after each start. */
    fun offers(tab: DockTab): Boolean = dockOffers(tab, downloads != false, books != false)
}

@Composable
fun rememberDockOffers(): DockOffers {
    val context = LocalContext.current
    val downloads by remember(context) {
        packageChanges(context.applicationContext, KjbAddons.DOWNLOADS.packageName).onStart { emit(Unit) }
            .map { withContext(Dispatchers.IO) { DlAddon.peek(context) != AddonState.Missing } }
    }.collectAsStateWithLifecycle(initialValue = null)
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
    val books = when (val read = library) {
        is LibraryState.Ready -> read.library.books.isNotEmpty()
        LibraryState.Unreadable -> false
        LibraryState.Loading -> null
    }
    return DockOffers(downloads, books)
}

/**
 * Library's top bar buttons for Search and Tools while the bar leaves them off ([offBar]), so both, and Settings in
 * Tools, stay in reach; Tools' button carries its dot, and TalkBack hears it as on the bar. The tour points at them.
 */
@Composable
fun OffBarButtons(bar: DockBar, onSelect: (DockTab) -> Unit) {
    offBar(bar.items).forEach { tab ->
        val dot = tab == DockTab.TOOLS && bar.toolsDot
        val name = dockItemName(stringResource(tab.label), if (dot) stringResource(R.string.nav_attention) else null)
        Box(Modifier.tourTarget(dockStop(tab))) {
            ChromeIconButton(tab.icon, name, { onSelect(tab) })
            if (dot) DotBadge(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
        }
    }
}

/**
 * Hide while scrolling, once for the whole shell (user 2026-11-18): a nested scroll connection on the page layer, so it
 * sees every page's and list's scrolls without any of them knowing ([DockScroll]). It only watches, never taking a
 * pixel, and sideways scrolls (a pager, a row of chips) are not the page's. [hidden] is what the bar shows; [show]
 * brings it back. While [on] is false (the setting, the tour, TalkBack) it stays shown.
 */
@Stable
class DockHide(private val threshold: Float) : NestedScrollConnection {
    var on: Boolean = false
    private var scroll = DockScroll()

    var hidden: Boolean by mutableStateOf(false)
        private set

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (on && abs(consumed.y) + abs(available.y) > abs(consumed.x) + abs(available.x)) {
            scroll = scroll.scrolled(consumed.y, available.y, threshold)
            hidden = scroll.hidden
        }
        return Offset.Zero
    }

    fun show() {
        scroll = DockScroll()
        hidden = false
    }
}

@Composable
fun rememberDockHide(): DockHide {
    val density = LocalDensity.current
    return remember(density) { DockHide(with(density) { DOCK_HIDE_TRAVEL.toPx() }) }
}

/** Whether TalkBack (or another touch exploration service) is on, as it changes: the bar never hides from it. */
@Composable
fun rememberTouchExploring(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var exploring by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return exploring
}

/** How far a page scrolls one way before the bar hides or comes back: past a finger's wobble. */
private val DOCK_HIDE_TRAVEL = 16.dp
