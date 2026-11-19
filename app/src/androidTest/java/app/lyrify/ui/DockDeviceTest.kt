package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.settings.DockStyle
import app.lyrify.settings.DockTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The bottom bar's items on the phone (user 2026-11-19: on Slim only Library showed, in the bar's middle, with the line
 * under the first place): bars of 2 to 6 items on a 360 dp and a 412 dp screen, Slim and Standard, at a large font
 * and right to left. Every item has an equal slot of its own with its icon in the middle, the sung line sits under the
 * selected one, and holding a Slim item shows its name above it. The screen's width is set here by its density, so the
 * bar is the width it would be on such a phone, whatever phone runs the test.
 */
@RunWith(AndroidJUnit4::class)
class DockDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var bar by mutableStateOf(Bar(360.dp, items = 4, selected = 0))
    private var highlight = Color.Unspecified
    private var pxPerDp = 1f
    private var started = false

    @Test
    fun onA360DpScreenEverySlimItemHasItsOwnSlotItsIconInTheMiddleAndTheLineUnderTheSelectedOne() {
        everyBar(Bar(360.dp, items = 4, selected = 0))
    }

    @Test
    fun onA412DpScreenEverySlimItemHasItsOwnSlotItsIconInTheMiddleAndTheLineUnderTheSelectedOne() {
        everyBar(Bar(412.dp, items = 4, selected = 0))
    }

    @Test
    fun rightToLeftTheSlimItemsRunFromTheRightAndTheLineFollowsItsItem() {
        everyBar(Bar(360.dp, items = 4, selected = 0, rtl = true))
    }

    @Test
    fun atALargeFontBothLooksKeepEveryItemInASlotOfItsOwn() {
        // At twice the size Standard's names may not fit, and its items fall back to the icon alone, as on Slim.
        everyBar(Bar(360.dp, items = 4, selected = 0, fontScale = 2f))
        everyBar(Bar(360.dp, items = 4, selected = 0, style = DockStyle.STANDARD, fontScale = 2f))
    }

    @Test
    fun theStandardBarKeepsItsEqualSlotsWithTheNamesUnderTheIconsOnTheDefaultFour() {
        everyBar(Bar(360.dp, items = 4, selected = 0, style = DockStyle.STANDARD))
        everyBar(Bar(412.dp, items = 4, selected = 0, style = DockStyle.STANDARD))
    }

    @Test
    fun holdingASlimItemShowsItsNameAboveIt() {
        start(Bar(360.dp, items = 6, selected = 0))
        val name = context.getString(DockTab.PLAYLISTS.label)
        val item = compose.onNodeWithContentDescription(name)
        compose.mainClock.autoAdvance = false
        item.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.mainClock.advanceTimeBy(TIP_SHOWN_MS)
        // Above, as on the phone: the bar is at the screen's bottom, so the tooltip has room over it and never flips under.
        val tip = compose.onNodeWithText(name)
        tip.assertIsDisplayed()
        // The tooltip is a window of its own, so both are compared on the screen.
        val anchor = item.fetchSemanticsNode()
        val shown = tip.fetchSemanticsNode()
        val anchorMiddle = anchor.positionOnScreen.x + anchor.size.width / 2f
        val shownMiddle = shown.positionOnScreen.x + shown.size.width / 2f
        assertEquals("the name over its item", anchorMiddle, shownMiddle, 4 * pxPerDp)
        assertTrue("the name above its item", shown.positionOnScreen.y + shown.size.height <= anchor.positionOnScreen.y + 1f)
        // Let the tooltip's own wait run out, so nothing is left waiting on a stopped clock.
        compose.mainClock.advanceTimeBy(TIP_GONE_MS)
        compose.mainClock.autoAdvance = true
    }

    /** [first], then the same bar with 2 to 6 items, the first, a middle and the last one selected. */
    private fun everyBar(first: Bar) {
        if (started) bar = first else start(first)
        (2..DOCK_SIX.size).forEach { items ->
            listOf(0, items / 2, items - 1).forEach { selected ->
                bar = first.copy(items = items, selected = selected)
                compose.waitForIdle()
                assertLaidOut()
            }
        }
    }

    /**
     * The bar alone at the screen's bottom, as in the app, the whole screen wide, the screen [Bar.width] wide by its
     * density; [bar] changes it after.
     */
    private fun start(first: Bar) {
        bar = first
        started = true
        compose.setContent {
            val shown = bar
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val scale = constraints.maxWidth / shown.width.value
                val direction = if (shown.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalDensity provides Density(scale, shown.fontScale), LocalLayoutDirection provides direction) {
                    LyrifyTheme {
                        val brand = LocalBrandColors.current.highlight
                        SideEffect {
                            pxPerDp = scale
                            highlight = brand
                        }
                        val tabs = tabsOf(shown.items)
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            key(shown) {
                                Box(Modifier.fillMaxWidth().testTag(BAR)) {
                                    Dock(tabs, tabs[shown.selected], shown.style, dot = false, onSelect = {})
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Every item in a slot of its own, all alike and side by side, its icon in the middle (on Slim also of the bar's
     * height; on Standard's default four above it, its name under it), the line under the selected one.
     */
    private fun assertLaidOut() {
        val shown = bar
        val tabs = tabsOf(shown.items)
        val slots = tabs.map { placed(compose.onNodeWithContentDescription(context.getString(it.label))) }
        val widths = slots.map { it.width }
        assertTrue("$shown: slots $widths wide", widths.max() - widths.min() <= 1.5f)
        val leftToRight = if (shown.rtl) slots.reversed() else slots
        leftToRight.zipWithNext().forEach { (left, right) -> assertTrue("$shown: $left then $right", right.left >= left.right - 1f) }
        // Together they fill the capsule but its ends: the screen less the bar's 12 dp margins and 6 dp ends.
        val whole = placed(compose.onNodeWithTag(BAR))
        assertEquals("$shown: slots $widths wide", whole.width - 36 * pxPerDp, widths.sum(), 2f)

        tabs.forEachIndexed { index, tab ->
            val name = context.getString(tab.label)
            val icon = placed(compose.onNode(hasTestTag(DOCK_ICON_TAG) and hasAnyAncestor(hasContentDescription(name)), useUnmergedTree = true))
            assertEquals("$shown: $name's icon", 24 * pxPerDp, icon.width, 1f)
            assertEquals("$shown: $name's icon in its slot", slots[index].center.x, icon.center.x, 1f)
            if (shown.style == DockStyle.SLIM) {
                assertEquals("$shown: $name's icon halfway down", slots[index].center.y, icon.center.y, 1f)
            } else if (shown.items == 4 && shown.fontScale == 1f) {
                // Four names always fit at the phone's own size, so each is drawn under its icon, which sits higher.
                assertTrue("$shown: $name's icon at ${icon.center.y}, its slot's middle ${slots[index].center.y}", icon.center.y < slots[index].center.y - 4 * pxPerDp)
            }
        }

        val line = line()
        assertNotNull("$shown: no sung line", line)
        val (from, to) = line!!
        val under = slots[shown.selected].center.x - whole.left
        assertEquals("$shown: the line from $from to $to", under, (from + to) / 2f, 2f)
        assertTrue("$shown: the line from $from to $to", to - from <= SUNG_LINE_WIDTH.value * pxPerDp + 2f)
    }

    /**
     * The sung line's ends, px from the bar's left: the highlight's pixels in the rows the line is drawn on (3 dp to 11 dp
     * above the bar's bottom: Slim's 5 to 8, Standard's 7 to 10, under the icons and names). Null when there is none.
     */
    private fun line(): Pair<Int, Int>? {
        val pixels = compose.onNodeWithTag(BAR).captureToImage().toPixelMap()
        val rows = (pixels.height - 11 * pxPerDp).toInt()..(pixels.height - 3 * pxPerDp).toInt()
        val xs = rows.flatMap { y -> (0 until pixels.width).filter { x -> near(pixels[x, y], highlight) } }
        return if (xs.isEmpty()) null else xs.min() to xs.max()
    }

    private fun near(actual: Color, wanted: Color): Boolean =
        abs(actual.red - wanted.red) < 0.04f && abs(actual.green - wanted.green) < 0.04f && abs(actual.blue - wanted.blue) < 0.04f

    /** Where [node] is laid out, px in the root, unclipped. */
    private fun placed(node: SemanticsNodeInteraction): Rect {
        val laid: SemanticsNode = node.fetchSemanticsNode()
        return Rect(laid.positionInRoot, laid.size.toSize())
    }

    private fun tabsOf(items: Int): List<DockTab> = DOCK_SIX.take(items - 1) + DockTab.TOOLS

    /** A bar to show: the screen's [width], its first [items] (Tools last) with the [selected] one, its look and font. */
    private data class Bar(
        val width: Dp,
        val items: Int,
        val selected: Int,
        val style: DockStyle = DockStyle.SLIM,
        val rtl: Boolean = false,
        val fontScale: Float = 1f,
    )

    private companion object {
        const val BAR = "bar"
        const val TIP_SHOWN_MS = 300L
        const val TIP_GONE_MS = 2_000L

        /** Six items in the order a user might set; a bar of n holds the first n - 1 and Tools. */
        val DOCK_SIX = listOf(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.SEARCH, DockTab.QUEUES, DockTab.FAVOURITES, DockTab.TOOLS)
    }
}
