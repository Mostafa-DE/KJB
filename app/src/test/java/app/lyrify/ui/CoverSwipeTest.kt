package app.lyrify.ui

import app.lyrify.library.SongRef
import app.lyrify.player.Queue
import app.lyrify.player.QueueItem
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The player cover's swipe (the prototype's coverSwipe): it locks to an axis after 12 dp, follows the finger at 0.6 with
 * a tilt of one degree per 40 dp, skips past 70 dp, and otherwise springs back; a drag down is the player's own, from
 * anywhere on it (PlayerDragTest). The song that slides in is the one Next or Previous plays. Made-up paths only.
 */
class CoverSwipeTest {
    private fun queue(size: Int, index: Int) = Queue(
        id = "q",
        source = QueueSource(SourceKind.ALBUM, "album:x", "Paper Harbour"),
        items = (0 until size).map { QueueItem(uid = it.toLong(), song = SongRef("/Music/KJB test/made-up-$it.mp3")) },
        index = index,
    )

    @Test
    fun `the gesture locks to an axis only past 12 dp, sideways or down, never up`() {
        assertNull(swipeAxis(dx = 11f, dy = 0f))
        assertNull(swipeAxis(dx = 0f, dy = -40f))
        assertEquals(SwipeAxis.SIDEWAYS, swipeAxis(dx = -13f, dy = 4f))
        assertEquals(SwipeAxis.SIDEWAYS, swipeAxis(dx = 20f, dy = -19f))
        assertEquals(SwipeAxis.DOWN, swipeAxis(dx = 5f, dy = 13f))
        // As far sideways as down: not sideways (the prototype needs more sideways than down), and down needs more down.
        assertNull(swipeAxis(dx = 20f, dy = 20f))
    }

    @Test
    fun `the cover follows the finger at 0_6 and tilts a degree per 40 dp`() {
        assertEquals(-60f, coverShift(-100f), 0.001f)
        assertEquals(2.5f, coverTilt(100f), 0.001f)
        assertEquals(-1.25f, coverTilt(-50f), 0.001f)
    }

    @Test
    fun `past 70 dp a swipe left plays the next song and a swipe right the previous one, else it springs back`() {
        assertEquals(SwipeOutcome.Skip(SkipWay.NEXT), swipeOutcome(SwipeAxis.SIDEWAYS, dx = -71f))
        assertEquals(SwipeOutcome.Skip(SkipWay.PREVIOUS), swipeOutcome(SwipeAxis.SIDEWAYS, dx = 71f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.SIDEWAYS, dx = -70f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.SIDEWAYS, dx = 69f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(null, dx = 0f))
    }

    @Test
    fun `down never skips nor closes from the cover itself, the player's own drag takes it (DR1)`() {
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.DOWN, dx = 0f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.DOWN, dx = -80f))
    }

    @Test
    fun `the next song slides in, and from the last song the first one, as Next goes there whatever Repeat says`() {
        assertEquals(3L, incoming(queue(5, 2), SkipWay.NEXT, Repeat.OFF, positionMs = 60_000)?.uid)
        assertEquals(0L, incoming(queue(5, 4), SkipWay.NEXT, Repeat.OFF, positionMs = 0)?.uid)
        assertEquals(0L, incoming(queue(5, 4), SkipWay.NEXT, Repeat.ONE, positionMs = 0)?.uid)
        assertEquals(0L, incoming(queue(5, 4), SkipWay.NEXT, Repeat.ALL, positionMs = 0)?.uid)
        // A queue of one song has no other song to slide in.
        assertNull(incoming(queue(1, 0), SkipWay.NEXT, Repeat.ALL, positionMs = 0))
    }

    @Test
    fun `the previous song slides in within 3 s of the start, else Previous starts this song over and none does`() {
        assertEquals(1L, incoming(queue(5, 2), SkipWay.PREVIOUS, Repeat.OFF, positionMs = 3_000)?.uid)
        assertNull(incoming(queue(5, 2), SkipWay.PREVIOUS, Repeat.OFF, positionMs = 3_001))
        assertNull(incoming(queue(5, 0), SkipWay.PREVIOUS, Repeat.OFF, positionMs = 0))
        assertEquals(4L, incoming(queue(5, 0), SkipWay.PREVIOUS, Repeat.ALL, positionMs = 0)?.uid)
    }

    @Test
    fun `a skip with a song sliding in ends with that song centred, one without springs back`() {
        // The incoming cover waits just past the screen's edge: half the free width, half the cover, plus the edge.
        val travel = coverTravel(area = 340f, side = 300f, edge = 24f)
        assertEquals(344f, travel, 0.001f)
        assertEquals(-travel, settleShift(SwipeOutcome.Skip(SkipWay.NEXT), slides = true, travel = travel), 0.001f)
        assertEquals(travel, settleShift(SwipeOutcome.Skip(SkipWay.PREVIOUS), slides = true, travel = travel), 0.001f)
        assertEquals(0f, settleShift(SwipeOutcome.Skip(SkipWay.PREVIOUS), slides = false, travel = travel), 0.001f)
        assertEquals(0f, settleShift(SwipeOutcome.Return, slides = true, travel = travel), 0.001f)
    }

    @Test
    fun `while dragging, the neighbour on the side the cover leaves is the one shown`() {
        assertEquals(SkipWay.NEXT, comingFrom(shift = -1f))
        assertEquals(SkipWay.PREVIOUS, comingFrom(shift = 1f))
        assertNull(comingFrom(shift = 0f))
    }

    // A skip from elsewhere during the settle (review finding C)

    @Test
    fun `a finished swipe skips only when the song playing is still the one it started on`() {
        // Nothing moved meanwhile: the swipe skips.
        assertEquals(Settled.SKIP, settledSwipe(startedOn = 4, playingNow = 4, coming = 5))
        // A headset or the notification already played the song sliding in: no second skip, the cover just lands.
        assertEquals(Settled.LANDED, settledSwipe(startedOn = 4, playingNow = 5, coming = 5))
        // It moved somewhere else: the cover springs back and nothing skips.
        assertEquals(Settled.RETURN, settledSwipe(startedOn = 4, playingNow = 9, coming = 5))
        assertEquals(Settled.RETURN, settledSwipe(startedOn = 4, playingNow = 9, coming = null))
    }
}
