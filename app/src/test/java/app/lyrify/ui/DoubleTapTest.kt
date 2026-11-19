package app.lyrify.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.lyrify.library.song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Double-tap to favourite (KJB 3.1): an album's, artist's or playlist's first tap (and the player cover's) acts at
 * once, while a song row's waits a quarter second for a second and plays only if none comes; a second tap soon after on
 * the same item makes what the first was on a favourite. The host takes that second tap's lift, so nothing under it
 * acts on it, and lets a touch that turns out to be a long press or a scroll go to what is under it. Made-up names
 * only.
 */
@OptIn(ExperimentalCoroutinesApi::class) // the clock's advanceTimeBy, runCurrent
class DoubleTapTest {
    private val timing = TapTiming(windowMs = 300, minGapMs = 40, touchSlopPx = 8f, holdMs = 400)
    private val lantern = FavouriteTarget.Song(song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 11).ref)
    private val ferry = FavouriteTarget.Song(song("Music/Harbour Glass/02 Night Ferry.mp3", id = 12).ref)
    private val album = FavouriteTarget.album("""["Kit Calloway","Harbour Glass"]""")
    /** A row in the window, 80 px tall, and the middle of it; the next row down. */
    private val row = Rect(0f, 560f, 400f, 640f)
    private val here = row.center
    private val next = Rect(0f, 640f, 400f, 720f)
    /** The whole of [row]'s line, its "⋯" and its handle after it, and the middle of each. */
    private val wholeRow = Rect(0f, 560f, 496f, 640f)
    private val more = Offset(424f, 600f)
    private val handle = Offset(472f, 600f)
    private val favourited = mutableListOf<FavouriteTarget>()
    /** The touches' and the waits' one clock: a touch at [now] is one the waits see at the same time. */
    private val clock = TestScope()
    private val taps = DoubleTaps(timing, clock) { favourited += it }

    /** What the song rows played, in turn. */
    private val plays = mutableListOf<FavouriteTarget>()
    private val lanternTap = WaitingTap { plays += lantern }
    private val ferryTap = WaitingTap { plays += ferry }

    private val now: Long get() = clock.testScheduler.currentTime

    /** [ms] pass, and what was due in them runs. */
    private fun pass(ms: Long) {
        clock.testScheduler.advanceTimeBy(ms)
        clock.testScheduler.runCurrent()
    }

    /**
     * A whole tap on a song's row, from now and [heldMs] long: the host sees its finger first, and a lift the host does
     * not take is the row's own tap, told by the row's touch ([doubleTapFavourite]) and then by its click. Whether the
     * host took it.
     */
    private fun tapSong(target: FavouriteTarget = lantern, tap: WaitingTap = lanternTap, area: Rect = row, whole: Rect = area, heldMs: Long = 60): Boolean {
        taps.down(now, area.center)
        pass(heldMs)
        if (taps.up(now, area.center)) return true
        taps.tapped(target, now, area, waiting = tap, whole = whole)
        tap.click()
        return false
    }

    /** A whole touch the host sees, down at [downAt] and up [heldMs] later, at [at] (window px): whether the host took its lift. */
    private fun touch(downAt: Long, at: Offset = here, heldMs: Long = 60): Boolean {
        taps.down(downAt, at)
        return taps.up(downAt + heldMs, at)
    }

    // The host

    @Test
    fun `an album's tap acts at once, and a second soon after on the same card has its lift taken and favourites the album`() {
        // The first tap: the host leaves it to the card, which opens its page and records the tap as the finger lifts.
        assertFalse(taps.down(1_000, here))
        assertFalse(taps.up(1_060, here))
        taps.tapped(album, 1_060, row)
        assertEquals(emptyList<FavouriteTarget>(), favourited)

        // The second, 200 ms after the first lifted and a few px away: the host watches it, takes its lift (so nothing
        // acts on it), and the heart pops where it landed.
        val second = here + Offset(6f, -4f)
        assertTrue(taps.down(1_260, second))
        assertTrue(taps.move(here + Offset(8f, -4f)))
        assertTrue(taps.up(1_320, here + Offset(8f, -4f)))
        assertEquals(listOf(album), favourited)
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
    fun `a second tap anywhere on the same row is its second, and a quick tap on the next row down is that row's own`() {
        taps.tapped(album, 1_000, row)
        // The row's far end, 190 px from the first tap.
        assertTrue(touch(1_200, Offset(390f, 600f)))
        assertEquals(listOf(album), favourited)

        // The next row starts right under this one: a tap 20 px into it is its own, never swallowed.
        taps.tapped(album, 2_000, row)
        assertFalse(touch(2_200, Offset(200f, 660f)))
        // A finger's wobble over the edge still counts.
        taps.tapped(album, 3_000, row)
        assertTrue(touch(3_200, Offset(200f, 646f)))
        assertEquals(listOf(album, album), favourited)
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

    // A song's row: its tap waits a quarter second for a second

    @Test
    fun `a single tap on a song's row plays it once the quarter second is over, not before, and only once`() {
        assertFalse(tapSong())
        pass(TAP_WAIT_MS - 1)
        assertEquals(emptyList<FavouriteTarget>(), plays)
        pass(1)
        assertEquals(listOf(lantern), plays)
        pass(1_000)
        assertEquals(listOf(lantern), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a double tap on a song's row makes it a favourite with a heart and never plays it`() {
        tapSong()
        pass(150)
        // The host takes the second tap's lift: the row never hears of it.
        assertTrue(tapSong())
        pass(1_000)
        assertEquals(emptyList<FavouriteTarget>(), plays)
        assertEquals(listOf(lantern), favourited)
        assertEquals(1, taps.pops.size)
    }

    @Test
    fun `a quick tap on another row inside the wait plays the first at once, before that row sees the finger, then waits on its own`() {
        tapSong()
        pass(100)
        // The finger lands on the next row: the first row's song plays now.
        assertFalse(taps.down(now, next.center))
        assertEquals(listOf(lantern), plays)
        pass(60)
        assertFalse(taps.up(now, next.center))
        taps.tapped(ferry, now, next, waiting = ferryTap)
        ferryTap.click()
        pass(TAP_WAIT_MS - 1)
        assertEquals(listOf(lantern), plays)
        pass(1)
        assertEquals(listOf(lantern, ferry), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun `a double tap on the next row right after a tap on the first favourites the next row's song, never the first's`() {
        tapSong()
        pass(100)
        assertFalse(tapSong(ferry, ferryTap, next))
        pass(100)
        assertTrue(tapSong(ferry, ferryTap, next))
        pass(1_000)
        assertEquals(listOf(lantern), plays)
        assertEquals(listOf(ferry), favourited)
    }

    @Test
    fun `a second touch on the row that turns into a long press, a scroll or a drag drops the waiting tap, so nothing plays`() {
        // Held past a long press: the row's own (it selects), its lift let through.
        tapSong()
        pass(100)
        assertTrue(taps.down(now, here))
        pass(timing.holdMs)
        assertFalse(taps.up(now, here))
        pass(1_000)
        // Moved past the touch slop: a scroll, or the queue's swipe to remove.
        tapSong()
        pass(100)
        assertTrue(taps.down(now, here))
        assertFalse(taps.move(here + Offset(0f, 9f)))
        pass(1_000)
        assertEquals(emptyList<FavouriteTarget>(), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a touch on another row inside the wait that turns into a long press or a scroll plays the song at once, as a tap there does`() {
        // A long press on the next row: the song plays as that finger lands, and the long press is the next row's own.
        tapSong()
        pass(100)
        assertFalse(taps.down(now, next.center))
        assertEquals(listOf(lantern), plays)
        pass(timing.holdMs)
        assertFalse(taps.up(now, next.center))
        // A scroll from the next row.
        tapSong()
        pass(100)
        assertFalse(taps.down(now, next.center))
        assertEquals(listOf(lantern, lantern), plays)
        assertFalse(taps.move(next.center + Offset(0f, -40f)))
        pass(1_000)
        assertEquals(listOf(lantern, lantern), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun `a second tap on a song's row once the wait is over is a tap of its own, so the song plays again and is no favourite`() {
        tapSong()
        pass(TAP_WAIT_MS)
        assertEquals(listOf(lantern), plays)
        // 260 ms after the first lifted: inside the phone's double-tap time (300 ms), but the song already plays.
        pass(10)
        assertFalse(tapSong())
        pass(TAP_WAIT_MS)
        assertEquals(listOf(lantern, lantern), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a touch on the row's own handle or ⋯ inside the wait drops the tap and is left to them, so the song never plays`() {
        // A drag on the handle: the host leaves it whole to the handle, which moves the song.
        tapSong(whole = wholeRow)
        pass(100)
        assertFalse(taps.down(now, handle))
        assertFalse(taps.move(handle + Offset(0f, 40f)))
        pass(60)
        assertFalse(taps.up(now, handle + Offset(0f, 40f)))
        pass(1_000)
        // A tap on "⋯": its lift is its own, and the song's sheet opens.
        tapSong(whole = wholeRow)
        pass(100)
        assertFalse(taps.down(now, more))
        pass(60)
        assertFalse(taps.up(now, more))
        pass(1_000)
        assertEquals(emptyList<FavouriteTarget>(), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `the row's own touch held past a long press or moved is no tap, so nothing waits and nothing plays`() {
        // Its touch tells the host nothing and its clickable never clicks; the next touch finds nothing to act on.
        val watch = TapWatch(timing)
        watch.down(now, here)
        pass(timing.holdMs)
        assertFalse(watch.up(now, here))
        watch.down(now, here)
        watch.move(here + Offset(0f, 2f), taken = true)
        assertFalse(watch.up(now, here))
        assertFalse(taps.down(now, here))
        pass(1_000)
        assertEquals(emptyList<FavouriteTarget>(), plays)
    }

    @Test
    fun `the finger bouncing on the row is that same tap, taken whole by the host, and the song plays once, never a favourite`() {
        tapSong()
        // 20 ms after the lift, sooner than any second tap.
        pass(20)
        assertTrue(taps.down(now, here))
        pass(30)
        assertTrue(taps.up(now, here))
        pass(TAP_WAIT_MS)
        assertEquals(listOf(lantern), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a finger bounce on the row that turns into a long press or a scroll is no tap, so the song never plays`() {
        // Held: the wait runs out while it is down, and the long press is the row's (it selects).
        tapSong()
        pass(timing.minGapMs - 1)
        assertTrue(taps.down(now, here))
        pass(timing.holdMs)
        assertFalse(taps.up(now, here))
        pass(1_000)
        // Moved past the touch slop: a scroll.
        tapSong()
        pass(timing.minGapMs - 1)
        assertTrue(taps.down(now, here))
        assertFalse(taps.move(here + Offset(0f, 9f)))
        pass(1_000)
        assertEquals(emptyList<FavouriteTarget>(), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
        assertEquals(emptyList<HeartPop>(), taps.pops)
    }

    @Test
    fun `a finger bounce still down as the wait runs out plays the song when it lifts as a tap, once`() {
        tapSong()
        pass(20)
        assertTrue(taps.down(now, here))
        pass(TAP_WAIT_MS)
        assertEquals(emptyList<FavouriteTarget>(), plays)
        // Lifted 310 ms after it landed, short of a long press: the host takes it, and the tap acts now.
        pass(60)
        assertTrue(taps.up(now, here))
        assertEquals(listOf(lantern), plays)
        pass(1_000)
        assertEquals(listOf(lantern), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun `a third quick tap after a double tap on a row pops another heart and still plays nothing`() {
        tapSong()
        pass(150)
        assertTrue(tapSong())
        pass(100)
        assertTrue(tapSong())
        pass(1_000)
        assertEquals(emptyList<FavouriteTarget>(), plays)
        assertEquals(listOf(lantern), favourited)
        assertEquals(2, taps.pops.size)
    }

    @Test
    fun `a touch landing after the wait but before its end has run (a busy moment) plays the tap first and is a tap of its own`() {
        tapSong()
        assertFalse(taps.down(now + TAP_WAIT_MS + 10, here))
        assertEquals(listOf(lantern), plays)
        pass(1_000)
        assertEquals(listOf(lantern), plays)
    }

    @Test
    fun `a tap still waiting as its host goes (the Queue sheet closing) plays at once`() {
        tapSong()
        pass(100)
        taps.settle()
        assertEquals(listOf(lantern), plays)
        pass(1_000)
        assertEquals(listOf(lantern), plays)
    }

    @Test
    fun `a click of another kind while the row's own tap waits (a keyboard's) plays the song once, at once, and the wait never does`() {
        tapSong()
        pass(100)
        lanternTap.click()
        assertEquals(listOf(lantern), plays)
        pass(1_000)
        assertEquals(listOf(lantern), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun `a row's click with no tap of its own waiting, a keyboard's, plays at once`() {
        lanternTap.click()
        assertEquals(listOf(lantern), plays)
        // Also after a tap of its own has waited and played.
        tapSong()
        pass(TAP_WAIT_MS)
        lanternTap.click()
        assertEquals(listOf(lantern, lantern, lantern), plays)
    }

    @Test
    fun `with TalkBack on a song's row never waits, its click playing at once each time, and nothing is a favourite`() {
        taps.enabled = false
        assertFalse(tapSong())
        assertEquals(listOf(lantern), plays)
        // TalkBack's double tap activates: the row plays again, at once.
        lanternTap.click()
        assertEquals(listOf(lantern, lantern), plays)
        pass(1_000)
        assertEquals(listOf(lantern, lantern), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
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
