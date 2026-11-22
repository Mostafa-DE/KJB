package app.lyrify.ui

import app.lyrify.settings.DisplaySettings
import app.lyrify.settings.DockItemSetting
import app.lyrify.settings.DockTab
import app.lyrify.settings.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom bar the user makes (KJB 3.1): its items, how its names fit, and hiding it while a page scrolls. */
class BottomBarTest {
    private val everything: (DockTab) -> Boolean = { true }
    private val default = DisplaySettings.DEFAULT_DOCK

    private fun on(vararg tabs: DockTab): List<DockItemSetting> =
        tabs.map { DockItemSetting(it, true) } + DockTab.entries.filter { it !in tabs }.map { DockItemSetting(it, false) }

    // Which items, in what order

    @Test
    fun `the default bar is today's, Library, Playlists, Search and Tools`() {
        assertEquals(listOf(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.SEARCH, DockTab.TOOLS), dockItems(default, everything))
        assertEquals(DOCK_TABS, dockItems(default, everything))
    }

    @Test
    fun `Insights is offered for the bar in Settings, but neither a new bar nor an upgraded one holds it`() {
        assertTrue(dockOffers(DockTab.INSIGHTS, downloads = false, books = false))
        assertTrue(DockTab.INSIGHTS in default.map { it.tab })
        assertFalse(DockTab.INSIGHTS in dockItems(default, everything))
        // A 3.1 bar (every item 3.1 had, kept before Insights was one): Insights comes after them, off, and the bar is as it was.
        val items31 = listOf("LIBRARY" to true, "QUEUES" to true, "PLAYLISTS" to false, "FAVOURITES" to false, "DOWNLOADS" to false, "BOOKS" to false, "SEARCH" to true, "TOOLS" to true)
        val stored = """{"display":{"dock":[${items31.joinToString(",") { (tab, shown) -> """{"tab":"$tab","shown":$shown}""" }}]}}"""
        val upgraded = checkNotNull(SettingsJson.decode(stored)).display.dock
        assertEquals(DockItemSetting(DockTab.INSIGHTS, false), upgraded.single { it.tab == DockTab.INSIGHTS })
        assertEquals(listOf(DockTab.LIBRARY, DockTab.QUEUES, DockTab.SEARCH, DockTab.TOOLS), dockItems(upgraded, everything))
        // Put there by the user, it takes its place.
        assertEquals(listOf(DockTab.LIBRARY, DockTab.QUEUES, DockTab.SEARCH, DockTab.TOOLS, DockTab.INSIGHTS), dockItems(dockSwitched(upgraded, DockTab.INSIGHTS, on = true, everything), everything))
    }

    @Test
    fun `the bar keeps the user's order and only the items switched on`() {
        val setting = on(DockTab.FAVOURITES, DockTab.LIBRARY, DockTab.QUEUES, DockTab.TOOLS)
        assertEquals(listOf(DockTab.FAVOURITES, DockTab.LIBRARY, DockTab.QUEUES, DockTab.TOOLS), dockItems(setting, everything))
    }

    @Test
    fun `Downloads shows only with its add-on and Books only with a book, and each stays in the setting meanwhile`() {
        assertTrue(dockOffers(DockTab.DOWNLOADS, downloads = true, books = false))
        assertFalse(dockOffers(DockTab.DOWNLOADS, downloads = false, books = true))
        assertTrue(dockOffers(DockTab.BOOKS, downloads = false, books = true))
        assertFalse(dockOffers(DockTab.BOOKS, downloads = true, books = false))
        DockTab.entries.filter { it != DockTab.DOWNLOADS && it != DockTab.BOOKS }.forEach { assertTrue("$it", dockOffers(it, downloads = false, books = false)) }

        val setting = on(DockTab.LIBRARY, DockTab.DOWNLOADS, DockTab.BOOKS, DockTab.SEARCH)
        assertEquals(listOf(DockTab.LIBRARY, DockTab.SEARCH), dockItems(setting) { dockOffers(it, downloads = false, books = false) })
        // Back as soon as the add-on is installed again, in its place.
        assertEquals(listOf(DockTab.LIBRARY, DockTab.DOWNLOADS, DockTab.SEARCH), dockItems(setting) { dockOffers(it, downloads = true, books = false) })
    }

    @Test
    fun `the bar holds six at most, and Library always, wherever the user put it`() {
        val seven = on(DockTab.PLAYLISTS, DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.BOOKS, DockTab.SEARCH, DockTab.LIBRARY)
        val items = dockItems(seven, everything)
        assertEquals(DisplaySettings.MAX_DOCK, items.size)
        assertEquals(listOf(DockTab.PLAYLISTS, DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.BOOKS, DockTab.LIBRARY), items)
        // Library switched off by a hand-made setting is still there, in its place.
        assertEquals(listOf(DockTab.SEARCH, DockTab.LIBRARY), dockItems(on(DockTab.SEARCH), everything))
    }

    @Test
    fun `a seventh item can't be switched on, an item can always be switched off, and Library never`() {
        val six = on(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.QUEUES, DockTab.FAVOURITES, DockTab.SEARCH, DockTab.TOOLS)
        assertFalse(dockHasRoom(six, everything))
        assertEquals(six, dockSwitched(six, DockTab.BOOKS, on = true, everything))
        val five = dockSwitched(six, DockTab.QUEUES, on = false, everything)
        assertEquals(listOf(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.FAVOURITES, DockTab.SEARCH, DockTab.TOOLS), dockItems(five, everything))
        assertTrue(dockHasRoom(five, everything))
        val books = dockSwitched(five, DockTab.BOOKS, on = true, everything)
        assertTrue(books.single { it.tab == DockTab.BOOKS }.shown)
        // A switch never moves anything.
        assertEquals(five.map { it.tab }, books.map { it.tab })
        assertEquals(five, dockSwitched(five, DockTab.LIBRARY, on = false, everything))
    }

    @Test
    fun `an item the phone can't offer now does not take a place on a full bar`() {
        val withDownloads = on(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.SEARCH)
        val noAddon: (DockTab) -> Boolean = { dockOffers(it, downloads = false, books = true) }
        assertTrue(dockHasRoom(withDownloads, noAddon))
        val seven = dockSwitched(withDownloads, DockTab.TOOLS, on = true, noAddon)
        assertTrue(seven.single { it.tab == DockTab.TOOLS }.shown)
        // The add-on back: still six on the bar, Library among them, Downloads kept on in the setting.
        val back = dockItems(seven) { dockOffers(it, downloads = true, books = true) }
        assertEquals(6, back.size)
        assertTrue(DockTab.LIBRARY in back && DockTab.DOWNLOADS in back)
        assertTrue(seven.single { it.tab == DockTab.DOWNLOADS }.shown)
    }

    @Test
    fun `the arrows move an item past the next one shown, never past one the phone can't offer now`() {
        val setting = on(DockTab.LIBRARY, DockTab.DOWNLOADS, DockTab.PLAYLISTS, DockTab.SEARCH)
        val noAddon: (DockTab) -> Boolean = { dockOffers(it, downloads = false, books = false) }
        // Playlists up: past Downloads (not shown in Settings now) and Library.
        val up = dockMoved(setting, DockTab.PLAYLISTS, up = true, noAddon)
        assertEquals(listOf(DockTab.PLAYLISTS, DockTab.DOWNLOADS, DockTab.LIBRARY), up.take(3).map { it.tab })
        assertEquals(listOf(DockTab.PLAYLISTS, DockTab.LIBRARY, DockTab.SEARCH), dockItems(up, noAddon))
        // Down from Library: Playlists' place.
        assertEquals(listOf(DockTab.PLAYLISTS, DockTab.DOWNLOADS, DockTab.LIBRARY), dockMoved(setting, DockTab.LIBRARY, up = false, noAddon).take(3).map { it.tab })
        // The first can't go up, the last can't go down; the switches stay as they were.
        assertEquals(setting, dockMoved(setting, DockTab.LIBRARY, up = true, noAddon))
        assertEquals(setting, dockMoved(setting, setting.last { noAddon(it.tab) }.tab, up = false, noAddon))
        assertEquals(setting.associate { it.tab to it.shown }, dockMoved(setting, DockTab.SEARCH, up = true, noAddon).associate { it.tab to it.shown })
    }

    @Test
    fun `until the phone has said, Downloads and Books stay on the bar, so nothing pops in at every start`() {
        assertTrue(DockOffers(downloads = null, books = null).offers(DockTab.DOWNLOADS))
        assertTrue(DockOffers(downloads = null, books = null).offers(DockTab.BOOKS))
        assertFalse(DockOffers(downloads = false, books = true).offers(DockTab.DOWNLOADS))
        assertTrue(DockOffers(downloads = false, books = true).offers(DockTab.BOOKS))
        assertFalse(DockOffers(downloads = true, books = false).offers(DockTab.BOOKS))
        assertTrue(DockOffers(downloads = false, books = false).offers(DockTab.QUEUES))
        val setting = on(DockTab.LIBRARY, DockTab.BOOKS, DockTab.DOWNLOADS)
        assertEquals(listOf(DockTab.LIBRARY, DockTab.BOOKS, DockTab.DOWNLOADS), dockItems(setting, DockOffers(null, null)::offers))
    }

    @Test
    fun `an item switched on but left off a full bar is named, so Settings never shows it as on the bar`() {
        val seven = on(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.SEARCH, DockTab.TOOLS)
        assertEquals(setOf(DockTab.TOOLS), dockLeftOff(seven, everything))
        // Its add-on gone, Downloads is not offered: nothing is left off then, and Downloads is not named.
        assertEquals(emptySet<DockTab>(), dockLeftOff(seven) { dockOffers(it, downloads = false, books = true) })
        assertEquals(emptySet<DockTab>(), dockLeftOff(default, everything))
    }

    @Test
    fun `the app opens on the last tab while it is on the bar, else on Library`() {
        assertEquals(DockTab.SEARCH, dockStart(DockTab.SEARCH, DOCK_TABS))
        assertEquals(DockTab.LIBRARY, dockStart(DockTab.FAVOURITES, DOCK_TABS))
        assertEquals(DockTab.FAVOURITES, dockStart(DockTab.FAVOURITES, listOf(DockTab.LIBRARY, DockTab.FAVOURITES)))
        assertEquals(DockTab.LIBRARY, dockStart(DockTab.TOOLS, listOf(DockTab.LIBRARY, DockTab.SEARCH)))
    }

    @Test
    fun `Search and Tools left off the bar get a button in Library's top bar, so both stay in reach`() {
        assertEquals(emptyList<DockTab>(), offBar(DOCK_TABS))
        assertEquals(listOf(DockTab.SEARCH, DockTab.TOOLS), offBar(listOf(DockTab.LIBRARY, DockTab.PLAYLISTS)))
        assertEquals(listOf(DockTab.TOOLS), offBar(listOf(DockTab.LIBRARY, DockTab.SEARCH, DockTab.QUEUES)))
    }

    // The names under the icons (Standard)

    @Test
    fun `a name that does not fit shrinks a little, all of them alike, and below that the bar shows icons only`() {
        assertEquals(1f, dockLabelShare(widest = 50f, room = 60f))
        assertEquals(1f, dockLabelShare(widest = 60f, room = 60f))
        assertEquals(0.9f, dockLabelShare(widest = 60f, room = 54f)!!, 0.001f)
        // Never larger than fits: rounded down.
        assertTrue(dockLabelShare(widest = 61f, room = 54f)!! * 61f <= 54f)
        assertEquals(DOCK_LABEL_SMALLEST, dockLabelShare(widest = 60f, room = 45f)!!, 0.001f)
        assertNull(dockLabelShare(widest = 60f, room = 44f))
        assertNull(dockLabelShare(widest = 60f, room = 0f))
        assertTrue(DOCK_LABEL_SMALLEST in 0.7f..0.85f)
    }

    // Where the items sit (user 2026-11-19: on Slim only Library showed, in the bar's middle, the line at the start)

    @Test
    fun `every item has a slot of its own, all alike, together exactly the bar's width`() {
        CAPSULES.forEach { width ->
            (1..DisplaySettings.MAX_DOCK).forEach { count ->
                val slots = DockSlots(count, width)
                assertEquals("$count in $width", 0, slots.start(0))
                assertEquals("$count in $width", width, slots.start(count))
                (0 until count).forEach { index ->
                    val size = slots.size(index)
                    assertEquals("$count in $width, slot $index", slots.start(index + 1), slots.start(index) + size)
                    assertTrue("$count in $width, slot $index is $size", size == width / count || size == width / count + 1)
                }
            }
        }
    }

    @Test
    fun `the sung line sits at the middle of its item's slot, and between the two while it travels`() {
        CAPSULES.forEach { width ->
            (4..DisplaySettings.MAX_DOCK).forEach { count ->
                val slots = DockSlots(count, width)
                (0 until count).forEach { index ->
                    val middle = slots.start(index) + slots.size(index) / 2f
                    assertEquals("$count in $width, slot $index", middle, slots.middle(index.toFloat()), 0.5f)
                }
                // Each its own place, never the whole bar's middle: evenly spaced, the first as far from the start as the
                // last from the end.
                (0 until count - 1).forEach { index ->
                    assertEquals("$count in $width, after slot $index", width.toFloat() / count, slots.middle(index + 1f) - slots.middle(index.toFloat()), 0.01f)
                }
                assertEquals("$count in $width", width.toFloat(), slots.middle(0f) + slots.middle(count - 1f), 0.01f)
                (0 until count - 1).forEach { index ->
                    val halfway = (slots.middle(index.toFloat()) + slots.middle(index + 1f)) / 2f
                    assertEquals("$count in $width, after slot $index", halfway, slots.middle(index + 0.5f), 0.01f)
                }
            }
        }
    }

    // Hide while scrolling

    @Test
    fun `scrolling down past a few dp hides the bar, back up shows it`() {
        val shown = DockScroll()
        // Compose's sign: the content going toward its end is negative.
        val bit = shown.scrolled(consumedY = -5f, availableY = 0f, threshold = 24f)
        assertFalse(bit.hidden)
        val hidden = bit.scrolled(consumedY = -20f, availableY = 0f, threshold = 24f)
        assertTrue(hidden.hidden)
        assertTrue(hidden.scrolled(-300f, 0f, 24f).hidden)
        val back = hidden.scrolled(consumedY = 10f, availableY = 0f, threshold = 24f).scrolled(consumedY = 15f, availableY = 0f, threshold = 24f)
        assertFalse(back.hidden)
    }

    @Test
    fun `turning round starts the count again`() {
        val hidden = DockScroll(hidden = true)
        val wobble = hidden.scrolled(20f, 0f, 24f).scrolled(-10f, 0f, 24f).scrolled(20f, 0f, 24f)
        assertTrue(wobble.hidden)
        assertFalse(DockScroll().scrolled(-20f, 0f, 24f).scrolled(10f, 0f, 24f).scrolled(-20f, 0f, 24f).hidden)
    }

    @Test
    fun `pulling down with nothing left to scroll shows the bar at once, at the top or on a page that can't scroll`() {
        val hidden = DockScroll(hidden = true, travel = 400f)
        assertEquals(DockScroll(), hidden.scrolled(consumedY = 3f, availableY = 12f, threshold = 24f))
        assertEquals(DockScroll(), hidden.scrolled(consumedY = 0f, availableY = 1f, threshold = 24f))
        // A drag up on a page that can't scroll moves nothing, so nothing changes.
        assertEquals(DockScroll(), DockScroll().scrolled(consumedY = 0f, availableY = -80f, threshold = 24f))
        assertEquals(hidden, hidden.scrolled(consumedY = 0f, availableY = -80f, threshold = 24f))
    }

    private companion object {
        /**
         * The room between the capsule's ends (px): the screen less the bar's 12 dp margins and 6 dp ends, on 360 dp
         * phones at 2x and 3x, a 384 dp one at 2.8125x and a 412 dp one at 2.625x, and an odd width.
         */
        val CAPSULES = listOf(648, 972, 979, 987, 1001)
    }
}
