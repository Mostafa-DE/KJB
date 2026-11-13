package app.lyrify.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // A tab it needs not laid out yet: no pill this frame.
        assertNull(pill(2.5f))
    }

    @Test
    fun `a label takes the pill's ink as the pill reaches it and gives it back as it leaves`() {
        assertEquals(1f, tabInkShare(position = 0f, index = 0), 0f)
        assertEquals(0f, tabInkShare(position = 0f, index = 1), 0f)
        assertEquals(0.5f, tabInkShare(position = 0.5f, index = 0), 0.0001f)
        assertEquals(0.5f, tabInkShare(position = 0.5f, index = 1), 0.0001f)
        assertEquals(1f, tabInkShare(position = 1f, index = 1), 0f)
        assertEquals(0f, tabInkShare(position = 1f, index = 0), 0f)
        assertEquals(0f, tabInkShare(position = 3f, index = 0), 0f)
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
}
