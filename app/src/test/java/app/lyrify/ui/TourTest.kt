package app.lyrify.ui

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Rect
import app.lyrify.settings.DockTab
import app.lyrify.settings.NavSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour (onboarding-research.md 4.5 to 4.8): five steps with Skip on each, Back, Done, the replay from Settings, when
 * it opens by itself (a phone updated from an earlier version once, a new install after its first run), and where it
 * points and puts its card on any phone: a target off screen is scrolled into view or its stop passed, and the card fits
 * a short screen at font scale 2.0. Sizes are in px at density 1, so 1 px reads as 1 dp.
 */
class TourTest {
    // The steps

    @Test
    fun `five steps in the spec's order, Play a song with two stops`() {
        assertEquals(listOf(1, 2, 2, 3, 4, 5), TourStop.entries.map { it.step })
        assertEquals(5, TOUR_STEPS)
        assertEquals(TourStop.TOOLS, TourStop.entries.last())
    }

    @Test
    fun `Next walks every stop, and Done on the last ends the tour`() {
        val walked = generateSequence(TourState()) { it.next() }.map { it.stop }.toList()
        assertEquals(TourStop.entries, walked)
        assertTrue(TourState(TourStop.TOOLS).last)
        assertFalse(TourState(TourStop.SEARCH).last)
        assertNull(TourState(TourStop.TOOLS).next())
    }

    @Test
    fun `Back goes one stop back, and shows from step 2 on`() {
        assertEquals(TourState(TourStop.NOW_BAR, forward = false), TourState(TourStop.LYRICS).back())
        assertEquals(TourState(TourStop.TABS, forward = false), TourState(TourStop.SONG).back())
        assertEquals(TourState(TourStop.TABS), TourState(TourStop.TABS).back())
        assertFalse(TourState(TourStop.TABS).hasBack)
        assertTrue(TourStop.entries.drop(1).all { TourState(it).hasBack })
    }

    @Test
    fun `Skip on every step ends the tour`() {
        for (stop in TourStop.entries) {
            val tour = Tour(TourState(stop))
            tour.skip()
            assertNull(stop.name, tour.state)
            assertTrue(stop.name, tour.ended)
        }
    }

    @Test
    fun `Back on the tour moves back, Next moves on`() {
        val tour = Tour(TourState(TourStop.SEARCH))
        tour.back()
        assertEquals(TourState(TourStop.LYRICS, forward = false), tour.state)
        tour.next()
        assertEquals(TourState(TourStop.SEARCH), tour.state)
        assertFalse(tour.ended)
    }

    @Test
    fun `finishing ends the tour, and Settings shows it again from the start`() {
        val tour = Tour()
        tour.start()
        assertEquals(TourState(TourStop.TABS), tour.state)
        repeat(TourStop.entries.size) { tour.next() }
        assertNull(tour.state)
        assertTrue(tour.ended)
        // Show tour in Settings: from step 1 again; it still never opens by itself in this session.
        tour.start()
        assertEquals(TourState(TourStop.TABS), tour.state)
        assertFalse(tourStarts(seen = true, ended = tour.ended, showing = false, libraryRead = true))
    }

    @Test
    fun `a stop that can't show is passed the way the tour was going`() {
        assertEquals(TourState(TourStop.SEARCH), TourState(TourStop.LYRICS).pass())
        assertEquals(TourState(TourStop.NOW_BAR, forward = false), TourState(TourStop.LYRICS, forward = false).pass())
        // Nothing before the first stop: on forward again.
        assertEquals(TourState(TourStop.SONG), TourState(TourStop.TABS, forward = false).pass())
        assertNull(TourState(TourStop.TOOLS).pass())
        val tour = Tour(TourState(TourStop.TOOLS))
        tour.pass()
        assertNull(tour.state)
        assertTrue(tour.ended)
    }

    @Test
    fun `the tour is kept across a turn of the phone`() {
        val scope = SaverScope { true }
        for (tour in listOf(Tour(TourState(TourStop.NOW_BAR, forward = false)), Tour(TourState(TourStop.TABS)), Tour(null, ended = true), Tour())) {
            val saved = with(Tour.Saver) { scope.save(tour) }!!
            val back = Tour.Saver.restore(saved)!!
            assertEquals(tour.state, back.state)
            assertEquals(tour.ended, back.ended)
        }
    }

    // When it opens by itself

    @Test
    fun `a phone updated from an earlier version sees the tour once, its setup left as it was`() {
        val updated = SettingsJson.decode("""{"version":4,"nav":{"lastTab":"TOOLS","setupDone":true}}""")!!
        assertTrue(updated.nav.setupDone)
        assertFalse(updated.nav.tourSeen)
        assertFalse(setupShows(Access(notifications = false, allFiles = true), updated.nav.setupDone, was = null))
        assertTrue(tourStarts(seen = updated.nav.tourSeen, ended = false, showing = false, libraryRead = true))
        // Finished or skipped once: kept, and never again.
        val seen = SettingsJson.decode(SettingsJson.encode(updated.copy(nav = updated.nav.copy(tourSeen = true))))!!
        assertTrue(seen.nav.tourSeen)
        assertTrue(seen.nav.setupDone)
        assertFalse(tourStarts(seen = seen.nav.tourSeen, ended = false, showing = false, libraryRead = true))
    }

    @Test
    fun `a new install sees the tour after its first run, and not after Skip tour`() {
        val fresh = SettingsJson.decode("{}")!!
        assertFalse(fresh.nav.tourSeen)
        // The first run shows first; the shell, and the tour with it, only after it.
        assertTrue(setupShows(Access(notifications = false, allFiles = false), fresh.nav.setupDone, was = null))
        val screens = setupScreens(fresh.nav.setupDone)
        val taken = setupPrimary(screens, SetupScreen.READY, Access(notifications = false, allFiles = true))
        assertEquals(SetupMove.Finish(declinesTour = false), taken)
        assertTrue(tourStarts(seen = fresh.nav.tourSeen, ended = Tour().ended, showing = false, libraryRead = true))
        assertEquals(SetupMove.Finish(declinesTour = true), setupSecondary(screens, SetupScreen.READY))
        val declined = Tour().apply { decline() }
        assertNull(declined.state)
        assertFalse(tourStarts(seen = fresh.nav.tourSeen, ended = declined.ended, showing = false, libraryRead = true))
    }

    @Test
    fun `the tour waits for the library, and never opens twice`() {
        assertFalse(tourStarts(seen = false, ended = false, showing = false, libraryRead = false))
        assertFalse(tourStarts(seen = false, ended = false, showing = true, libraryRead = true))
        assertFalse(tourStarts(seen = false, ended = true, showing = false, libraryRead = true))
        assertFalse(tourStarts(seen = true, ended = false, showing = false, libraryRead = true))
    }

    @Test
    fun `whether the tour was seen is kept in settings, and older settings read as not seen`() {
        val seen = Settings(nav = NavSettings(setupDone = true, tourSeen = true, notificationsDenied = true))
        assertEquals(seen, SettingsJson.decode(SettingsJson.encode(seen)))
        val old = SettingsJson.decode("""{"version":1,"nav":{"lastTab":"TOOLS"}}""")!!.nav
        assertFalse(old.tourSeen)
        assertFalse(old.notificationsDenied)
        assertFalse(SettingsJson.decode("""{"nav":{"tourSeen":"yes"}}""")!!.nav.tourSeen)
    }

    // Pointing at a target

    private val window = Rect(0f, 24f, 360f, 716f)

    @Test
    fun `a target whole on screen is shown`() {
        assertEquals(Aim.SHOW, aim(Rect(0f, 100f, 360f, 164f), window, scrolled = false))
        assertEquals(Aim.SHOW, aim(window, window, scrolled = false))
    }

    @Test
    fun `a target off screen is scrolled into view once, and its stop passed if it still is not whole`() {
        val partly = Rect(0f, 690f, 360f, 754f)
        val below = Rect(0f, 800f, 360f, 864f)
        assertEquals(Aim.SCROLL, aim(partly, window, scrolled = false))
        assertEquals(Aim.SCROLL, aim(below, window, scrolled = false))
        assertEquals(Aim.PASS, aim(partly, window, scrolled = true))
        assertEquals(Aim.SHOW, aim(Rect(0f, 600f, 360f, 664f), window, scrolled = true))
    }

    @Test
    fun `a target that is not there, or has no size, never gets a card`() {
        assertEquals(Aim.PASS, aim(null, window, scrolled = false))
        assertEquals(Aim.PASS, aim(Rect(10f, 10f, 10f, 74f), window, scrolled = false))
        assertEquals(Aim.PASS, aim(Rect.Zero, window, scrolled = false))
    }

    @Test
    fun `of a list's rows, the topmost whole one is pointed at`() {
        val room = Rect(0f, 200f, 360f, 600f)
        val rows = listOf(Rect(0f, 160f, 360f, 224f), Rect(0f, 288f, 360f, 352f), Rect(0f, 224f, 360f, 288f), Rect(0f, 580f, 360f, 644f))
        assertEquals(rows[2], pickTarget(rows, room) { it })
        // None whole: the topmost in part, to scroll into view.
        val tall = listOf(Rect(0f, 560f, 360f, 760f), Rect(0f, 150f, 360f, 610f))
        assertEquals(tall[1], pickTarget(tall, room) { it })
        assertNull(pickTarget(listOf(Rect(0f, 700f, 360f, 760f)), room) { it })
        assertNull(pickTarget(emptyList<Rect>(), room) { it })
    }

    @Test
    fun `a song has to be clear of the tabs above and the Now bar and dock below`() {
        assertEquals(Rect(0f, 180f, 360f, 590f), targetRoom(TourStop.SONG, window, tabsBottom = 180f, chromeTop = 590f))
        assertEquals(window, targetRoom(TourStop.SONG, window, tabsBottom = null, chromeTop = null))
        assertEquals(window, targetRoom(TourStop.SEARCH, window, tabsBottom = 180f, chromeTop = 590f))
        assertEquals(window, targetRoom(TourStop.TABS, window, tabsBottom = 180f, chromeTop = 590f))
    }

    // The card, on small screens and with large text

    private fun card(target: Rect, area: Rect, wide: Boolean = false) =
        cardRoom(target, area, wide = wide, gap = 14f, edge = 16f, wideEdge = 12f, narrowMax = 300f, minHeight = 160f)

    @Test
    fun `the card sits on the side with more room, never over its target`() {
        val area = Rect(0f, 24f, 360f, 716f)
        val dockTab = Rect(196f, 640f, 284f, 700f)
        val above = card(dockTab, area)
        assertFalse(above.below)
        assertFalse(above.overlaps)
        val top = cardTop(above, dockTab, area, gap = 14f, height = 220f)
        assertEquals(dockTab.top - 14f - 220f, top, 0.01f)
        assertTrue(top >= area.top)

        val tabs = Rect(0f, 80f, 360f, 128f)
        val below = card(tabs, area)
        assertTrue(below.below)
        assertEquals(tabs.bottom + 14f, cardTop(below, tabs, area, gap = 14f, height = 220f), 0.01f)
    }

    @Test
    fun `the card keeps to the screen, centred on its target when it can be`() {
        val area = Rect(0f, 24f, 360f, 716f)
        val search = card(Rect(196f, 640f, 284f, 700f), area)
        assertEquals(300f, search.width, 0.01f)
        assertTrue(search.x >= 16f)
        assertTrue(search.x + search.width <= 360f - 16f + 0.01f)
        // A narrow window: the card is the window less its margins.
        val narrow = card(Rect(0f, 80f, 280f, 128f), Rect(0f, 0f, 280f, 600f))
        assertEquals(280f - 32f, narrow.width, 0.01f)
        assertEquals(16f, narrow.x, 0.01f)
    }

    @Test
    fun `at font scale 2_0 the card is full width less its margins`() {
        val area = Rect(0f, 24f, 360f, 716f)
        val wide = card(Rect(196f, 640f, 284f, 700f), area, wide = true)
        assertEquals(360f - 24f, wide.width, 0.01f)
        assertEquals(12f, wide.x, 0.01f)
        assertTrue(2.0f > LARGE_TEXT)
        assertFalse(1.0f > LARGE_TEXT)
    }

    @Test
    fun `on a short screen the card is at most 60 percent of it, and of its side's room`() {
        val short = Rect(0f, 24f, 320f, 504f)
        val middle = card(Rect(0f, 200f, 320f, 260f), short, wide = true)
        assertTrue(middle.below)
        assertEquals(504f - 260f - 14f, middle.maxHeight, 0.01f)
        val tabs = card(Rect(0f, 40f, 320f, 90f), short, wide = true)
        assertEquals(short.height * MAX_CARD_SHARE, tabs.maxHeight, 0.01f)
        val top = cardTop(tabs, Rect(0f, 40f, 320f, 90f), short, gap = 14f, height = tabs.maxHeight)
        assertTrue(top + tabs.maxHeight <= short.bottom)
    }

    @Test
    fun `with no room for a card on either side, it keeps to the screen's edge`() {
        val short = Rect(0f, 24f, 320f, 504f)
        val player = Rect(0f, 80f, 320f, 450f)
        val room = card(player, short, wide = true)
        assertTrue(room.overlaps)
        assertTrue(room.maxHeight <= short.height * MAX_CARD_SHARE + 0.01f)
        assertTrue(room.maxHeight >= 160f)
        val top = cardTop(room, player, short, gap = 14f, height = 200f)
        assertTrue(top >= short.top)
        assertTrue(top + 200f <= short.bottom + 0.01f)
    }

    @Test
    fun `the dock's Search and Tools are steps 4 and 5`() {
        assertEquals(TourStop.SEARCH, dockStop(DockTab.SEARCH))
        assertEquals(TourStop.TOOLS, dockStop(DockTab.TOOLS))
        assertNull(dockStop(DockTab.LIBRARY))
        assertNull(dockStop(DockTab.PLAYLISTS))
    }
}
