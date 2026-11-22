package app.lyrify.ui

import app.lyrify.settings.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What Your listening shows, as pure logic. */
class ListeningScreenTest {
    @Test
    fun `listening time is whole minutes, rounded, then hours and minutes from an hour on`() {
        assertEquals(ListenedTime(0, 0), listenedTime(0))
        assertEquals(ListenedTime(0, 0), listenedTime(29_999))
        assertEquals(ListenedTime(0, 1), listenedTime(30_000))
        assertEquals(ListenedTime(0, 59), listenedTime(59 * MINUTE + 29_999))
        assertEquals(ListenedTime(1, 0), listenedTime(59 * MINUTE + 30_000))
        assertEquals(ListenedTime(3, 20), listenedTime((3 * 60 + 20) * MINUTE))
        assertEquals(ListenedTime(1_234, 5), listenedTime((1_234 * 60 + 5) * MINUTE))
        assertEquals(ListenedTime(0, 0), listenedTime(-5_000))
    }

    @Test
    fun `a top list shows its first 5, and View all only when there are more`() {
        assertEquals(listOf(1, 2, 3, 4, 5) to true, topPreview((1..6).toList()))
        assertEquals(listOf(1, 2, 3, 4, 5) to false, topPreview((1..5).toList()))
        assertEquals(emptyList<Int>() to false, topPreview(emptyList<Int>()))
    }

    @Test
    fun `the library's pages show plays as Insights says them, and every Insights page with top rows gives Top songs a song sheet`() {
        val pages = source("CollectionScreens.kt")
        val tabs = source("LibraryScreen.kt")
        val sheets = source("SongSheets.kt")
        val listening = source("ListeningScreen.kt")

        listOf("ListeningCard(", "playsNote(", "lastPlayedText(", "StatsGraph.counter(context).written").forEach { assertTrue(it, pages.contains(it)) }
        assertTrue(tabs.contains("StatsGraph.counter(context).written"))
        listOf("songPlaysInfo(song.ref)", "dateText(").forEach { assertTrue(it, sheets.contains(it)) }
        // A Top songs row opening the sheet is ListeningScreenDeviceTest's; the pages read plays.db and the library
        // themselves, with no seam for a test to draw them, so that each passes its rows a sheet stays a source check.
        // Changed on purpose (IN1): Insights, a month's recap and View all, each with its sheet; the first two through
        // their cards, the third through its rows.
        listOf("val sheets = rememberSongSheets()", "SongSheetsHost(sheets)").forEach { assertEquals(it, 3, Regex(Regex.escape(it)).findAll(listening).count()) }
        assertEquals(2, Regex(Regex.escape("settings, onOpen, sheets::openSong, reduced, viewAll = ")).findAll(listening).count())
        assertTrue(listening.contains("TopRow(top, i + 1, settings, onOpen, onSong)"))
        assertTrue(listening.contains("TopRow(top, i + 1, settings, onOpen, sheets::openSong)"))
        listOf(pages, tabs, sheets, listening).forEach { assertFalse(it.contains("S1:")) }
    }

    // Insights (KJB 3.2)

    @Test
    fun `against the period before is a whole percent of it, nothing without it`() {
        assertEquals(40, percentChange(140, 100))
        assertEquals(-25, percentChange(75, 100))
        assertEquals(0, percentChange(100, 100))
        // Rounded: a third more.
        assertEquals(33, percentChange(4, 3))
        assertEquals(0, percentChange(500, 0))
    }

    @Test
    fun `Insights holds all eight cards, For you under the totals, and its nudges and recaps stay on the page itself`() {
        val listening = source("ListeningScreen.kt")
        listOf(
            "TotalsCard(", "ForYouInsights(", "VersusCard(", "TopRow(", "ClockCard(", "MilestonesCard(", "SpokenCard(",
            "NeverPlayedCard(", "DigDeeperCard(", "ForgottenCard(", "RecapRow(", "R.string.insights_private",
        ).forEach { assertTrue(it, listening.contains(it)) }
        // For you right under the totals, on Insights alone (viewAll), as agreed with sg1.
        assertTrue(listening.indexOf("TotalsCard(") < listening.indexOf("ForYouInsights(") && listening.indexOf("ForYouInsights(") < listening.indexOf("VersusCard("))
        assertTrue(listening.contains("if (viewAll) item(key = \"for you\""))
        // A month's recap: its picture, shared as drawn, and its own cards; the nudges only on Insights.
        listOf("RecapPicture(", "shareRecapPicture(context, picture, shown, title)").forEach { assertTrue(it, listening.contains(it)) }
        val recap = listening.substringAfter("private fun RecapPage(").substringBefore("private fun RecapsPage(")
        listOf("NeverPlayedCard(", "DigDeeperCard(", "ForgottenCard(").forEach { card ->
            assertEquals(card, 1, Regex(Regex.escape(card)).findAll(listening).count())
            assertFalse(card, recap.contains(card))
        }
    }

    @Test
    fun `the recap card sits first on every Library tab, only in Library itself, and shows on the one Library opens on`() {
        val library = source("LibraryScreen.kt") + source("BooksScreen.kt")
        val lines = library.lines()
        LibraryTab.entries.forEach { tab ->
            val at = lines.indexOfFirst { "recapCard(LibraryTab.$tab)" in it }
            assertTrue("$tab", at > 0)
            // The first row: right where its list opens.
            assertTrue("$tab: ${lines[at - 1]}", lines[at - 1].trimEnd().let { it.endsWith("{") || it.endsWith("->") })
        }
        // Playlists and Books are bar items of their own too (PlaylistsPage, BooksPage): there the card is not drawn (Grok b-ui).
        listOf(
            "if (inLibrary) recapCard(LibraryTab.PLAYLISTS)", "if (inLibrary) recapCard(LibraryTab.BOOKS)",
            "LibraryTab.PLAYLISTS -> PlaylistsTab(ui, library, padding, reselect, sheets, inLibrary = true)",
            "LibraryTab.BOOKS -> BooksTab(library, padding, reselect, inLibrary = true)",
            "PlaylistsTab(ui, shown, padding, nav.state.reselectsOf(DockTab.PLAYLISTS), sheets, inLibrary = false)",
            "BooksTab(it, padding, nav.state.reselectsOf(DockTab.BOOKS), inLibrary = false)",
        ).forEach { assertTrue(it, library.contains(it)) }
        val cards = source("InsightsCards.kt")
        assertTrue(cards.contains("if (tab != libraryOpensOn(shownLibraryTabs(read.library, (library as? LibraryState.Ready)?.library), read.library.opensOn)) return"))
        // The month turns with the phone's day, so an app kept running across the 1st shows the new month's card (Grok b-ui).
        assertTrue(cards.contains("val month = remember(today) { recapMonthOf(System.currentTimeMillis(), zone) }"))
        // On its own bar item Insights has no back arrow.
        assertTrue(source("AppShell.kt").contains("DockTab.INSIGHTS -> ListeningPage(Page.Listening, root = true)"))
    }

    private fun source(name: String): String = File("src/main/java/app/lyrify/ui", name).readText()

    private companion object {
        const val MINUTE: Long = 60_000
    }
}
