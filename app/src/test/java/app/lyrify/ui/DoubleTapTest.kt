package app.lyrify.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Double-tap to favourite (KJB 3.1): the first tap acts at once, and a second soon after on the same item makes what
 * the first was on a favourite. The host takes that second tap's lift, so nothing under it acts on it, and lets a touch
 * that turns out to be a long press or a scroll go to what is under it. Made-up names only.
 */
class DoubleTapTest {
    private val timing = TapTiming(windowMs = 300, minGapMs = 40, touchSlopPx = 8f, holdMs = 400)
    private val lantern = FavouriteTarget.Song(song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 11).ref)
    private val album = FavouriteTarget.album("""["Kit Calloway","Harbour Glass"]""")
    /** A song's row in the window, 80 px tall, and the middle of it. */
    private val row = Rect(0f, 560f, 400f, 640f)
    private val here = row.center
    private val favourited = mutableListOf<FavouriteTarget>()
    private val taps = DoubleTaps(timing) { favourited += it }

    /** A whole touch the host sees, down at [downAt] and up [heldMs] later, at [at] (window px): whether the host took its lift. */
    private fun touch(downAt: Long, at: Offset = here, heldMs: Long = 60): Boolean {
        taps.down(downAt, at)
        return taps.up(downAt + heldMs, at)
    }

    // The host

    @Test
    fun `a tap acts at once, and a second soon after on the same row has its lift taken and favourites what the first was on`() {
        // The first tap: the host leaves it to the row, which plays the song and records the tap as the finger lifts.
        assertFalse(taps.down(1_000, here))
        assertFalse(taps.up(1_060, here))
        taps.tapped(lantern, 1_060, row)
        assertEquals(emptyList<FavouriteTarget>(), favourited)

        // The second, 200 ms after the first lifted and a few px away: the host watches it, takes its lift (so the row
        // never plays again), and the heart pops where it landed.
        val second = here + Offset(6f, -4f)
        assertTrue(taps.down(1_260, second))
        assertTrue(taps.move(here + Offset(8f, -4f)))
        assertTrue(taps.up(1_320, here + Offset(8f, -4f)))
        assertEquals(listOf(lantern), favourited)
        assertEquals(listOf(second), taps.pops.map { it.position })
    }

    @Test
    fun `a tap that opened a page is doubled by a second tap on that page, which never reaches what lies under it`() {
        // An album card opens its page at once. Whatever the page has under the finger, the host takes the second tap's
        // lift before the page sees it, so nothing there acts, and favourites the album the first tap was on.
        taps.tapped(album, 1_060, row)
        assertTrue(touch(1_250))
        assertEquals(listOf(album), favourited)
    }

    @Test
    fun `a second touch too late, too soon after the lift or off the item is a tap of its own, left alone`() {
        taps.tapped(lantern, 1_000, row)
        assertFalse(touch(1_301))
        taps.tapped(lantern, 2_000, row)
        // A bounce of the finger, not a second tap.
        assertFalse(touch(2_030))
        taps.tapped(lantern, 3_000, row)
        assertFalse(touch(3_100, Offset(200f, 700f)))
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a second tap anywhere on the same row is its second, and a quick tap on the next row down plays that one`() {
        taps.tapped(lantern, 1_000, row)
        // The row's far end, 190 px from the first tap.
        assertTrue(touch(1_200, Offset(390f, 600f)))
        assertEquals(listOf(lantern), favourited)

        // The next row starts right under this one: a tap 20 px into it is its own, never swallowed.
        taps.tapped(album, 2_000, row)
        assertFalse(touch(2_200, Offset(200f, 660f)))
        // A finger's wobble over the edge still counts.
        taps.tapped(album, 3_000, row)
        assertTrue(touch(3_200, Offset(200f, 646f)))
        assertEquals(listOf(lantern, album), favourited)
    }

    @Test
    fun `any touch ends the wait for a second tap, so one elsewhere and then one back on the row is no double tap`() {
        taps.tapped(lantern, 1_000, row)
        assertFalse(touch(1_050, here + Offset(400f, 0f)))
        assertFalse(touch(1_200))
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun `a second touch that moves past the touch slop is a scroll, let go whole, and favourites nothing`() {
        taps.tapped(lantern, 1_000, row)
        assertTrue(taps.down(1_100, here))
        // Past 8 px it is a scroll: the host lets it go.
        assertFalse(taps.move(here + Offset(0f, 9f)))
        assertFalse(taps.up(1_150, here + Offset(0f, 30f)))
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a second touch held past a long press is the row's own, so its lift is let through and its long press still selects`() {
        taps.tapped(lantern, 2_000, row)
        assertTrue(taps.down(2_100, here))
        // Held past a long press: the row has had its long press (the selection starts), and the host takes nothing.
        assertFalse(taps.up(2_501, here))
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a third tap soon after a double tap pops another heart but stores nothing again, so the first Undo stays`() {
        taps.tapped(lantern, 1_000, row)
        assertTrue(touch(1_200))
        // 140 ms after the second lifted.
        assertTrue(touch(1_400))
        assertEquals(listOf(lantern), favourited)
        assertEquals(2, taps.pops.size)
    }

    @Test
    fun `what a second tap does besides runs once with the favourite, as the player's cover turning back`() {
        var turned = 0
        taps.tapped(lantern, 1_000, row) { turned++ }
        touch(1_200)
        touch(1_400)
        assertEquals(1, turned)
        assertEquals(listOf(lantern), favourited)
    }

    @Test
    fun `with TalkBack on nothing changes, as no tap is recorded and no touch is taken`() {
        taps.enabled = false
        taps.tapped(lantern, 1_000, row)
        assertFalse(touch(1_200))
        taps.enabled = true
        // Nothing was recorded while it was off.
        assertFalse(touch(1_350))
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun `a touch the host had taken and then lost to the system takes nothing further`() {
        taps.tapped(lantern, 1_000, row)
        assertTrue(taps.down(1_100, here))
        taps.cancel()
        assertFalse(taps.up(1_150, here))
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun `a sheet's double taps store their favourite through the shell's, whose message and Undo outlive the sheet`() {
        val sheet = mutableListOf<FavouriteTarget>()
        favouriteThrough(taps) { sheet += it }(album)
        assertEquals(listOf(album), favourited)
        assertEquals(emptyList<FavouriteTarget>(), sheet)
        // With no shell above (a test, a preview), its own.
        favouriteThrough(null) { sheet += it }(lantern)
        assertEquals(listOf(lantern), sheet)
    }

    @Test
    fun `a heart that has popped goes`() {
        taps.tapped(album, 1_000, row)
        touch(1_200)
        taps.popped(taps.pops.single())
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    // An item's touch

    @Test
    fun `an item's touch is a tap when it lifts in time, within the touch slop, and nothing took its moves`() {
        val watch = TapWatch(timing)
        watch.down(1_000, here)
        assertTrue(watch.up(1_080, here + Offset(5f, 0f)))

        watch.down(2_000, here)
        watch.move(here + Offset(0f, 9f), taken = false)
        assertFalse(watch.up(2_080, here))

        // A list's scroll, or the cover's sideways swipe, took a move.
        watch.down(3_000, here)
        watch.move(here + Offset(0f, 2f), taken = true)
        assertFalse(watch.up(3_080, here))

        // A long press.
        watch.down(4_000, here)
        assertFalse(watch.up(4_401, here))

        assertFalse(watch.up(5_000, here))

        watch.down(6_000, here)
        watch.cancel()
        assertFalse(watch.up(6_050, here))
    }
}
