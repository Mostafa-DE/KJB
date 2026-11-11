package app.lyrify.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Top tabs that swipe (phone test 2026-11-11, points 2 and 4): the sung line follows the pages under the finger, a tab
 * chosen elsewhere glides there only while the page is on screen, and every tab's page stays composed once the pager
 * has settled in, so going back to a tab finds its list as it was, never rebuilt.
 */
class TabPagerTest {
    /** Tabs 120 px apart, centred at 60, 180, 300 and 420. */
    private val centres: (Int) -> Float? = { at -> if (at in 0..3) 60f + 120f * at else null }

    @Test
    fun `the line sits under a tab when the pages rest and follows them between two tabs`() {
        assertEquals(51f..69f, tabLine(0f, centres, width = 18f))
        assertEquals(171f..189f, tabLine(1f, centres, width = 18f))
        // Half way, it spans between the two tabs, stretched, never past either.
        val half = tabLine(0.5f, centres, width = 18f)!!
        assertTrue(half.start > 51f && half.endInclusive < 189f)
        assertTrue(half.endInclusive - half.start > 18f)
        // Its middle moves with the pages, steadily, all the way across.
        val middles = (0..20).map { step -> tabLine(step / 10f, centres, 18f)!!.let { (it.start + it.endInclusive) / 2 } }
        assertEquals(middles.sorted(), middles)
        assertEquals(180f, middles[10], 0.001f)
        assertEquals(300f, middles[20], 0.001f)
    }

    @Test
    fun `the line leads with the end it travels toward, and waits for a tab that is not laid out`() {
        val early = tabLine(0.2f, centres, width = 18f)!!
        // Its leading end is further along than its trailing end has come from the start.
        assertTrue(early.endInclusive - 69f > early.start - 51f)
        assertNull(tabLine(3.5f, centres, width = 18f))
        assertNull(tabLine(0f, { null }, width = 18f))
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
        // A finger only laid on them as they settle, or a list scrolled meanwhile: they go on to where they were going.
        assertEquals(1, tabFlingPage(position = 0.4f, velocity = 0f, minVelocity = min, heading = 1, dragged = false, count = 5))
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
