package app.lyrify.ui

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
        assertEquals(TabMove.NONE, tabMove(target = 2, current = 2, onScreen = true))
        assertEquals(TabMove.GLIDE, tabMove(target = 0, current = 2, onScreen = true))
        // Library's icon from another dock destination lands on Songs: the page is not on screen yet, so no glide.
        assertEquals(TabMove.JUMP, tabMove(target = 0, current = 2, onScreen = false))
        assertEquals(TabMove.NONE, tabMove(target = -1, current = 2, onScreen = true))
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
