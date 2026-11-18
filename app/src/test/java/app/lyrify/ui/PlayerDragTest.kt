package app.lyrify.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player dragged down from anywhere on it (DR1, user request 2026-11-17), as pure rules: a finger nothing under it
 * took drags the player once it has gone down first past 12 dp, never sideways, up, or from the system's gesture edge; a
 * list already at its top hands a finger's pull down over, and takes a push back up only once the player is home; let
 * go, the player closes past a quarter of its height or on a fling down, else springs back. What on the player only
 * acts once it is clearly not that drag: the seek buttons, the seek bar and the A-B handles (review 2026-11-17).
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
    fun `the fling after a list's pull is the player's, whether it comes before or after the player sees the lift`() {
        // After the lift: the pull was let go of.
        assertTrue(listFling(letGo = true, pulling = false, pulled = 0f))
        // Before the player has seen the lift (the list handles the same lift first): the pull is still on (review 2026-11-17).
        assertTrue(listFling(letGo = false, pulling = true, pulled = 30f))
        // A pull pushed back home, or a plain scroll: the list flings.
        assertFalse(listFling(letGo = false, pulling = true, pulled = 0f))
        assertFalse(listFling(letGo = false, pulling = false, pulled = 0f))
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

    // Review 2026-11-17: a drag down that starts on a seek button, the seek bar or an A-B handle is the player's

    @Test
    fun `a seek button jumps on a tap, never on a drag down or a finger the player took, and waits while pressed`() {
        val slop = 18f
        assertEquals(SeekPress.WAIT, seekPress(Offset(3f, 4f), slop, lifted = false, consumed = false))
        assertEquals(SeekPress.JUMP, seekPress(Offset(3f, 4f), slop, lifted = true, consumed = false))
        // A drag down from it is the player's: no jump, then or at the lift.
        assertEquals(SeekPress.NONE, seekPress(Offset(2f, 19f), slop, lifted = false, consumed = false))
        assertEquals(SeekPress.NONE, seekPress(Offset(2f, 40f), slop, lifted = true, consumed = false))
        // Sliding off sideways is no tap either.
        assertEquals(SeekPress.NONE, seekPress(Offset(-25f, 0f), slop, lifted = true, consumed = false))
        // The player's drag took it first (on a phone whose slop is past the player's 12 dp): nothing.
        assertEquals(SeekPress.NONE, seekPress(Offset(0f, 10f), slop, lifted = false, consumed = true))
    }

    @Test
    fun `the seek bar and the A-B handles take a sideways drag and leave a mostly vertical one to the player`() {
        val slop = 18f
        assertEquals(SheetDrag.WAIT, sidewaysDrag(Offset(10f, 10f), slop))
        assertEquals(SheetDrag.TAKE, sidewaysDrag(Offset(30f, 4f), slop))
        // 45 degrees still seeks.
        assertEquals(SheetDrag.TAKE, sidewaysDrag(Offset(-20f, 20f), slop))
        // Mostly down, even when the sideways wobble alone has passed the slop (the old detector took that one).
        assertEquals(SheetDrag.LEAVE, sidewaysDrag(Offset(19f, 24f), slop))
        assertEquals(SheetDrag.LEAVE, sidewaysDrag(Offset(3f, 30f), slop))
        assertEquals(SheetDrag.LEAVE, sidewaysDrag(Offset(3f, -30f), slop))
    }

    @Test
    fun `once a sideways drag is taken, every move of it counts to the lift, not only the first`() {
        // Review 2026-11-18: the move was read after the change was consumed, and a consumed change reports none, so an
        // A-B handle stopped following the finger once it had passed the slop.
        var x = 100f
        var dragged = 0f
        repeat(4) { frame ->
            val change = PointerInputChange(PointerId(1L), uptimeMillis = 16L * (frame + 1), position = Offset(x + 30f, 52f), pressed = true, previousUptimeMillis = 16L * frame, previousPosition = Offset(x, 50f), previousPressed = true, isInitiallyConsumed = false)
            dragged += change.takeMoveX()
            // Still taken: nothing under the bar or the handle sees it.
            assertTrue(change.isConsumed)
            x += 30f
        }
        assertEquals(120f, dragged, 0f)
    }
}
