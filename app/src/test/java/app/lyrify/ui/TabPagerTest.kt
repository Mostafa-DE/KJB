package app.lyrify.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Top tabs that swipe (phone test 2026-11-11, points 2 and 4): the sung line follows the pages under the finger, a tab
 * chosen elsewhere glides there only while the page is on screen, and every tab's page stays composed once the pager
 * has settled in, so going back to a tab finds its list as it was, never rebuilt.
 */
class TabPagerTest {
    // The sliding pill (user decision 2026-11-11, mock handoff/tabs-design/pill-tabs.html)

    /** Songs, Albums and Artists laid out from 12 px, 100, 140 and 120 px wide, 2 px apart; a fourth not laid out. */
    private val starts = listOf(12f, 114f, 256f)
    private val widths = listOf(100f, 140f, 120f)

    private fun pill(position: Float) = tabPill(position, { starts.getOrNull(it) }, { widths.getOrNull(it) })

    @Test
    fun `the pill sits on the tab when the pages rest, and slides and stretches between two tabs with them`() {
        assertEquals(PillSpan(12f, 100f), pill(0f))
        assertEquals(PillSpan(63f, 120f), pill(0.5f))
        assertEquals(PillSpan(114f, 140f), pill(1f))
        assertEquals(PillSpan(185f, 130f), pill(1.5f))
        assertEquals(PillSpan(256f, 120f), pill(2f))
        // Its start moves steadily with the pages.
        val stops = (0..20).map { pill(it / 10f)!!.start }
        assertEquals(stops.sorted(), stops)
        // A tab it needs not measured yet (review of 5b488d2): never a frame without a pill, the nearest known one.
        assertEquals(PillSpan(256f, 120f), pill(2.5f))
        assertEquals(PillSpan(12f, 100f), tabPill(-0.5f, { starts.getOrNull(it) }, { widths.getOrNull(it) }))
    }

    @Test
    fun `each tab starts after the ones before it, every tab measured, so the pill always has both ends`() {
        assertEquals(listOf(12f, 114f, 256f), tabStarts(widths, start = 12f, gap = 2f))
        assertEquals(emptyList<Float>(), tabStarts(emptyList(), start = 12f, gap = 2f))
    }

    @Test
    fun `the row scrolls the pill to its middle, as far as it can scroll`() {
        assertEquals(200, tabRowScroll(centre = 300f, viewport = 200, max = 500))
        assertEquals(0, tabRowScroll(centre = 50f, viewport = 200, max = 500))
        assertEquals(500, tabRowScroll(centre = 900f, viewport = 200, max = 500))
    }

    @Test
    fun `a label's ink reads on what is under most of it all through a swipe, but for the moment it crosses the pill's edge`() {
        listOf(LightColors to chromeColors(LightColors, LightBrandColors, dark = false), DarkColors to chromeColors(DarkColors, DarkBrandColors, dark = true)).forEach { (scheme, chrome) ->
            val colors = tabColors(scheme, chrome)
            (0..100).map { it / 100f }.forEach { position ->
                val share = tabInkShare(position, index = 0)
                val under = if (share >= 0.5f) colors.pill else scheme.background
                val ratio = contrast(tabInk(colors, share), under)
                // Only within a few hundredths of the swipe's midpoint, where the pill's edge crosses the label.
                assertTrue("at $position, share $share: ${"%.2f".format(ratio)}:1", ratio >= 4.5f || abs(position - 0.5f) < CROSSING)
            }
        }
    }

    @Test
    fun `a label takes the pill's ink as the pill reaches it and gives it back as it leaves, crossing over quickly`() {
        assertEquals(1f, tabInkShare(position = 0f, index = 0), 0f)
        assertEquals(0f, tabInkShare(position = 0f, index = 1), 0f)
        assertEquals(0.5f, tabInkShare(position = 0.5f, index = 0), 0.0001f)
        assertEquals(0.5f, tabInkShare(position = 0.5f, index = 1), 0.0001f)
        assertEquals(1f, tabInkShare(position = 1f, index = 1), 0f)
        assertEquals(0f, tabInkShare(position = 1f, index = 0), 0f)
        assertEquals(0f, tabInkShare(position = 3f, index = 0), 0f)
        // Most of the way it keeps one ink or the other; it crosses over near the middle.
        assertTrue(tabInkShare(position = 0.2f, index = 0) > 0.95f)
        assertTrue(tabInkShare(position = 0.8f, index = 0) < 0.05f)
    }

    @Test
    fun `the pill is the filled buttons' ink with its own ink on it, the others the secondary ink, at the mock's sizes`() {
        listOf(LightColors to chromeColors(LightColors, LightBrandColors, dark = false), DarkColors to chromeColors(DarkColors, DarkBrandColors, dark = true)).forEach { (scheme, chrome) ->
            val colors = tabColors(scheme, chrome)
            assertEquals(chrome.action, colors.pill)
            assertEquals(chrome.onAction, colors.on)
            assertEquals(scheme.onSurfaceVariant, colors.off)
            assertEquals(colors.off, tabInk(colors, 0f))
            assertEquals(colors.on, tabInk(colors, 1f))
            val half = tabInk(colors, 0.5f)
            assertTrue(half != colors.on && half != colors.off)
        }
        assertEquals(36f, TAB_PILL_HEIGHT.value, 0f)
        assertEquals(16f, TAB_PADDING.value, 0f)
        assertEquals(15f, TabText.fontSize.value, 0f)
        assertEquals(FontWeight(600), TabText.fontWeight)
    }

    @Test
    fun `a tab chosen elsewhere glides there while the page is on screen, and is simply there when it was not`() {
        assertEquals(TabMove.NONE, tabMove(target = 2, reported = 0, current = 2, onScreen = true))
        assertEquals(TabMove.GLIDE, tabMove(target = 0, reported = 2, current = 2, onScreen = true))
        // Library's icon from another dock destination lands on Songs: the page is not on screen yet, so no glide.
        assertEquals(TabMove.JUMP, tabMove(target = 0, reported = 2, current = 2, onScreen = false))
        assertEquals(TabMove.NONE, tabMove(target = -1, reported = 2, current = 2, onScreen = true))
    }

    // Phone test of build 603c4bf: a fast flick sometimes did not change tabs

    @Test
    fun `the tab the pages themselves reported never pulls them back while a newer flick takes them on`() {
        // A settle on Albums reported, while the next flick already heads for Artists: the report is the pages' own.
        assertEquals(TabMove.NONE, tabMove(target = 1, reported = 1, current = 2, onScreen = true))
    }

    @Test
    fun `a short quick flick always moves one tab, even well under half the width and slowing at the lift`() {
        val min = 300f
        // A third of the way and let go at twice the least fling speed: on to the next tab, or back to the one before.
        assertEquals(1, tabFlingPage(position = 0.3f, velocity = 600f, minVelocity = min, heading = null, dragged = true, count = 5))
        assertEquals(1, tabFlingPage(position = 1.7f, velocity = -600f, minVelocity = min, heading = null, dragged = true, count = 5))
        assertEquals(0, tabFlingPage(position = 0.8f, velocity = -600f, minVelocity = min, heading = null, dragged = true, count = 5))
        // Dragged forward a little, then flicked back: back where it started.
        assertEquals(2, tabFlingPage(position = 2.2f, velocity = -600f, minVelocity = min, heading = null, dragged = true, count = 5))
        // Never past either end.
        assertEquals(4, tabFlingPage(position = 4f, velocity = 900f, minVelocity = min, heading = null, dragged = true, count = 5))
        assertEquals(0, tabFlingPage(position = 0f, velocity = -900f, minVelocity = min, heading = null, dragged = true, count = 5))
    }

    @Test
    fun `a flick while the pages still settle moves on from where they were going, either way`() {
        val min = 300f
        // On their way from Songs to Albums (0.4 of the way), flicked on: Artists; flicked back: Songs.
        assertEquals(2, tabFlingPage(position = 0.4f, velocity = 900f, minVelocity = min, heading = 1, dragged = true, count = 5))
        assertEquals(0, tabFlingPage(position = 0.4f, velocity = -900f, minVelocity = min, heading = 1, dragged = true, count = 5))
        // A finger laid on them as they settle catches them; a tap, or a list scrolled meanwhile, stops them on the
        // nearer tab (review of cef1ddc), as Android's ViewPager does.
        assertEquals(0, tabFlingPage(position = 0.4f, velocity = 0f, minVelocity = min, heading = 1, dragged = false, count = 5))
        assertEquals(1, tabFlingPage(position = 0.6f, velocity = 0f, minVelocity = min, heading = 1, dragged = false, count = 5))
    }

    // Review of cef1ddc

    @Test
    fun `pages restored between two tabs, after a turn of the phone or a restart mid-swipe, land on a whole tab`() {
        assertTrue(tabNeedsLanding(offsetFraction = 0.3f, scrolling = false))
        assertTrue(tabNeedsLanding(offsetFraction = -0.4f, scrolling = false))
        assertFalse(tabNeedsLanding(offsetFraction = 0f, scrolling = false))
        // Pages a finger or a settle is moving are on their way already.
        assertFalse(tabNeedsLanding(offsetFraction = 0.3f, scrolling = true))
    }

    @Test
    fun `a tab tapped while a finger holds the pages wins over the finger, else it glides as usual`() {
        assertEquals(MutatePriority.PreventUserInput, tabGlidePriority(fingerOnPages = true))
        assertEquals(MutatePriority.Default, tabGlidePriority(fingerOnPages = false))
    }

    @Test
    fun `TalkBack's scroll actions move one tab, never past either end`() {
        assertEquals(2, tabStep(forward = true, current = 1, count = 5))
        assertEquals(0, tabStep(forward = false, current = 1, count = 5))
        assertNull(tabStep(forward = true, current = 4, count = 5))
        assertNull(tabStep(forward = false, current = 0, count = 5))
    }

    @Test
    fun `a finger landing in the system's gesture edge is the system's back, never a tab flick`() {
        // A 1080 px window with 72 px gesture edges.
        assertTrue(inGestureEdge(x = 30f, width = 1080f, start = 72f, end = 72f))
        assertTrue(inGestureEdge(x = 1050f, width = 1080f, start = 72f, end = 72f))
        assertFalse(inGestureEdge(x = 540f, width = 1080f, start = 72f, end = 72f))
        // Three-button navigation: no gesture edge.
        assertFalse(inGestureEdge(x = 2f, width = 1080f, start = 0f, end = 0f))
    }

    @Test
    fun `a sideways swipe on a row that scrolls sideways is the row's while it can scroll that way, and the tabs' at its end`() {
        // Left to right: a finger going left scrolls the row on, one going right scrolls it back (user 2026-11-25).
        assertTrue(rowKeeps(dx = -30f, rtl = false, back = false, forward = true))
        assertFalse(rowKeeps(dx = -30f, rtl = false, back = true, forward = false))
        assertTrue(rowKeeps(dx = 30f, rtl = false, back = true, forward = false))
        assertFalse(rowKeeps(dx = 30f, rtl = false, back = false, forward = true))
        // Right to left, the row's start on the right.
        assertTrue(rowKeeps(dx = 30f, rtl = true, back = false, forward = true))
        assertFalse(rowKeeps(dx = -30f, rtl = true, back = false, forward = true))
        assertTrue(rowKeeps(dx = -30f, rtl = true, back = true, forward = false))
        // The pages ask the rows under the finger before they take it, and give every page the rows' list.
        val pager = File("src/main/java/app/lyrify/ui/TabPager.kt").readText()
        val swipes = pager.substringAfter("private suspend fun PointerInputScope.swipes(").substringBefore("\n}\n")
        assertTrue(swipes.contains("TabSwipe.TAKE -> {\n                        // A row under the finger that scrolls that way keeps it (rowKeeps).\n                        if (keeps(down.position, moved.x)) break"))
        val pages = pager.substringAfter("fun <K : Any> TabPages(").substringBefore("\n}\n")
        assertTrue(pages.contains("rows.any { (row, bounds) -> bounds.contains(origin.value + at) && rowKeeps(dx, rtl, row.canScrollBackward, row.canScrollForward) }"))
        assertTrue(pages.contains("LocalSidewaysRows provides rows"))
    }

    @Test
    fun `with reduced motion the pages jump to their tab instead of sliding there`() {
        assertNull(tabSettleSpec(reduced = true))
        assertTrue(tabSettleSpec(reduced = false) != null)
    }

    @Test
    fun `let go slowly, the pages go to the nearer tab`() {
        val min = 300f
        assertEquals(0, tabFlingPage(position = 0.3f, velocity = 100f, minVelocity = min, heading = null, dragged = true, count = 5))
        assertEquals(1, tabFlingPage(position = 0.6f, velocity = 0f, minVelocity = min, heading = null, dragged = true, count = 5))
        assertEquals(1, tabFlingPage(position = 1.4f, velocity = -100f, minVelocity = min, heading = 2, dragged = true, count = 5))
    }

    @Test
    fun `a mostly sideways finger is the pages', even drifting 25 degrees, and a mostly upright one the list's`() {
        val slop = 20f
        fun at(degrees: Double, length: Float) = Offset((length * kotlin.math.cos(Math.toRadians(degrees))).toFloat(), (length * kotlin.math.sin(Math.toRadians(degrees))).toFloat())
        // Past the slop on both axes in one step, as a fast flick's first move is: still the pages'.
        assertEquals(TabSwipe.TAKE, tabSwipe(at(25.0, 60f), slop))
        assertEquals(TabSwipe.TAKE, tabSwipe(at(-25.0, 60f), slop))
        assertEquals(TabSwipe.TAKE, tabSwipe(at(180.0 - 25.0, 60f), slop))
        assertEquals(TabSwipe.LEAVE, tabSwipe(at(65.0, 60f), slop))
        assertEquals(TabSwipe.LEAVE, tabSwipe(at(-90.0, 60f), slop))
        assertEquals(TabSwipe.WAIT, tabSwipe(at(25.0, 10f), slop))
    }

    @Test
    fun `the neighbours are ready from the start and every page joins them one frame at a time, then all stay`() {
        assertEquals(1, keptBeyond(count = 6, frames = 0))
        assertEquals(3, keptBeyond(count = 6, frames = 2))
        assertEquals(5, keptBeyond(count = 6, frames = 4))
        assertEquals(5, keptBeyond(count = 6, frames = 40))
        assertEquals(0, keptBeyond(count = 1, frames = 3))
        assertEquals(1, keptBeyond(count = 2, frames = 0))
    }

    // KJB 3.0.2 crash: turning the Genres tab on (CR3)

    @Test
    fun `a page asked for past the tabs gets a key of its own, never a crash`() {
        val five = listOf("Songs", "Playlists", "Albums", "Artists", "Folders")
        assertEquals("Folders", tabPageKey(five, 4))
        // The pager counting six while the keys it reads are still the five: "Index 5 out of bounds for length 5".
        val sixth = tabPageKey(five, 5)
        assertFalse(sixth in five)
        assertEquals(sixth, tabPageKey(five, 5))
        // One per page, and saveable on the phone as a page's key must be.
        assertTrue(sixth != tabPageKey(five, 6))
        assertTrue(sixth is java.io.Serializable)
    }

    @Test
    fun `a tab turned on, off or moved past the one shown moves nothing, so a swipe goes on`() {
        val five = listOf("Songs", "Playlists", "Albums", "Artists", "Folders")
        // On Artists: Genres turned on after Folders, then off again, or moved up before Folders.
        assertNull(tabAfterChange(before = five, after = five + "Genres", heading = 3, selected = 3))
        assertNull(tabAfterChange(before = five + "Genres", after = five, heading = 3, selected = 3))
        assertNull(tabAfterChange(before = five + "Genres", after = five.take(4) + "Genres" + "Folders", heading = 3, selected = 3))
    }

    @Test
    fun `the tab shown stays shown wherever a change of the tabs moves it, and one turned off gives way to the app's choice`() {
        val five = listOf("Songs", "Playlists", "Albums", "Artists", "Folders")
        val withGenres = listOf("Songs", "Playlists", "Albums", "Artists", "Genres", "Folders")
        // On Folders, Genres turned on just before it: still Folders, a place further on.
        assertEquals(5, tabAfterChange(before = five, after = withGenres, heading = 4, selected = 5))
        // On Folders, Genres turned off again: still Folders, a place back.
        assertEquals(4, tabAfterChange(before = withGenres, after = five, heading = 5, selected = 4))
        // On Genres, Genres turned off: the tab the app shows instead (Library's Songs).
        assertEquals(0, tabAfterChange(before = withGenres, after = five, heading = 4, selected = 0))
        // On Albums, moved up above Playlists: still Albums.
        assertEquals(1, tabAfterChange(before = five, after = listOf("Songs", "Albums", "Playlists", "Artists", "Folders"), heading = 2, selected = 1))
        // Albums tapped, the pages still gliding there when Genres is turned on before it: on Albums at once.
        assertEquals(3, tabAfterChange(before = five, after = listOf("Songs", "Genres", "Playlists", "Albums", "Artists", "Folders"), heading = 2, selected = 3))
    }

    @Test
    fun `a swipe on its way to a tab a change moves lands on that tab, not back on the one it left`() {
        val five = listOf("Songs", "Playlists", "Albums", "Artists", "Folders")
        // Flicked from Songs toward Albums, the app still on Songs until the pages settle, when Books comes in before
        // Albums (the library read again): on Albums, where the finger sent them.
        assertEquals(3, tabAfterChange(before = five, after = listOf("Songs", "Playlists", "Books", "Albums", "Artists", "Folders"), heading = 2, selected = 0))
    }

    @Test
    fun `no tabs left, the pages land nowhere`() {
        assertNull(tabAfterChange(before = listOf("Songs", "Albums"), after = emptyList(), heading = 1, selected = -1))
    }

    @Test
    fun `with no tab of the app's among the new ones, the pages keep their own wherever it went, else go to the first`() {
        val three = listOf("To review", "Done", "Skipped")
        assertEquals(2, tabAfterChange(before = three, after = listOf("To review", "Later", "Done", "Skipped"), heading = 1, selected = -1))
        assertEquals(0, tabAfterChange(before = three, after = listOf("To review", "Done"), heading = 2, selected = -1))
        // Never a page past the new last.
        assertEquals(0, tabAfterChange(before = three, after = listOf("To review", "Done"), heading = 2, selected = 7))
        assertEquals(0, tabAfterChange(before = emptyList(), after = three, heading = 0, selected = -1))
    }

    private companion object {
        /** How close to the midpoint of a swipe a label may be while its ink reads under 4.5:1. */
        const val CROSSING = 0.06f
    }
}
