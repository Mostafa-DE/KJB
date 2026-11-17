package app.lyrify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player dragged down from anywhere on it (DR1, user request 2026-11-17), as pure rules: a finger nothing under it
 * took drags the player once it has gone down first past 12 dp, never sideways, up, or from the system's gesture edge; a
 * list already at its top hands a finger's pull down over, and takes a push back up only once the player is home; let
 * go, the player closes past a quarter of its height or on a fling down, else springs back.
 */
class PlayerDragTest {
    @Test
    fun `a finger anywhere on the player that goes down first past 12 dp drags it`() {
        // Not yet past the lock: wait and see.
        assertEquals(SheetDrag.WAIT, playerSwipe(dx = 3f, dy = 11f, inEdge = false))
        assertEquals(SheetDrag.WAIT, playerSwipe(dx = 0f, dy = -11f, inEdge = false))
        // Down, more than sideways: the player follows.
        assertEquals(SheetDrag.TAKE, playerSwipe(dx = 5f, dy = 13f, inEdge = false))
        assertEquals(SheetDrag.TAKE, playerSwipe(dx = -30f, dy = 31f, inEdge = false))
    }

    @Test
    fun `sideways or up, the finger is left to what is under it, the cover's skip and the seek bar included`() {
        assertEquals(SheetDrag.LEAVE, playerSwipe(dx = -13f, dy = 4f, inEdge = false))
        assertEquals(SheetDrag.LEAVE, playerSwipe(dx = 20f, dy = 19f, inEdge = false))
        assertEquals(SheetDrag.LEAVE, playerSwipe(dx = 0f, dy = -13f, inEdge = false))
    }

    @Test
    fun `a finger landing in the system's gesture edge is the system's back, never the player's drag`() {
        assertEquals(SheetDrag.LEAVE, playerSwipe(dx = 0f, dy = 200f, inEdge = true))
        assertEquals(SheetDrag.LEAVE, playerSwipe(dx = 0f, dy = 0f, inEdge = true))
        // Nor a list's pull under it.
        assertEquals(0f, listPull(available = 40f, scrolled = false, byFinger = true, held = false), 0f)
    }

    @Test
    fun `a list already at its top hands a finger's pull down to the player`() {
        // At its top the list uses none of the pull: all of it moves the player.
        assertEquals(40f, listPull(available = 40f, scrolled = false, byFinger = true, held = true), 0f)
        // Down the lyrics the list uses it all: nothing is left over.
        assertEquals(0f, listPull(available = 0f, scrolled = false, byFinger = true, held = true), 0f)
        // A push up is the list's.
        assertEquals(0f, listPull(available = -40f, scrolled = false, byFinger = true, held = true), 0f)
    }

    @Test
    fun `a list that scrolled since the finger landed keeps the whole drag, and a fling never pulls the player`() {
        // Scrolled back to its top in this drag: what is left goes nowhere, as a sheet's content keeps such a drag.
        assertEquals(0f, listPull(available = 40f, scrolled = true, byFinger = true, held = true), 0f)
        // A fling reaching the top never drags the player down.
        assertEquals(0f, listPull(available = 40f, scrolled = false, byFinger = false, held = true), 0f)
    }

    @Test
    fun `pushed back up, the player goes home first and the list scrolls only with what is left`() {
        // Pulled 30 px down: a push of 50 takes the player home, 20 is left for the list.
        assertEquals(-30f, listPush(available = -50f, pulled = 30f, byFinger = true), 0f)
        assertEquals(-10f, listPush(available = -10f, pulled = 30f, byFinger = true), 0f)
        // Home, or not pulled by a list: the list scrolls as ever.
        assertEquals(0f, listPush(available = -10f, pulled = 0f, byFinger = true), 0f)
        // A pull down goes to the list first (at its top it is left over, listPull).
        assertEquals(0f, listPush(available = 10f, pulled = 30f, byFinger = true), 0f)
        assertEquals(0f, listPush(available = -10f, pulled = 30f, byFinger = false), 0f)
    }

    @Test
    fun `let go, a drag from anywhere or a list's pull closes the player past a quarter of its height or on a fling down`() {
        val height = 2000f
        val fling = 2400f
        assertFalse(dragCloses(offset = 499f, velocity = 0f, height = height, fling = fling))
        assertTrue(dragCloses(offset = 501f, velocity = 0f, height = height, fling = fling))
        assertTrue(dragCloses(offset = 60f, velocity = 2500f, height = height, fling = fling))
        // Pushed back home before the lift, a fling down from there leaves it.
        assertFalse(dragCloses(offset = 0f, velocity = 2500f, height = height, fling = fling))
    }
}
