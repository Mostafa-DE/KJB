package app.lyrify.ui

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
    fun `the library's pages show plays as Your listening says them, and both Your listening pages give Top songs a song sheet`() {
        val pages = source("CollectionScreens.kt")
        val tabs = source("LibraryScreen.kt")
        val sheets = source("SongSheets.kt")
        val listening = source("ListeningScreen.kt")

        listOf("ListeningCard(", "playsNote(", "lastPlayedText(", "StatsGraph.counter(context).written").forEach { assertTrue(it, pages.contains(it)) }
        assertTrue(tabs.contains("StatsGraph.counter(context).written"))
        listOf("songPlaysInfo(song.ref)", "dateText(").forEach { assertTrue(it, sheets.contains(it)) }
        // A Top songs row opening the sheet is ListeningScreenDeviceTest's; the two pages read plays.db and the library
        // themselves, with no seam for a test to draw them, so that each passes its rows a sheet stays a source check.
        listOf("val sheets = rememberSongSheets()", "onOpen, sheets::openSong)", "SongSheetsHost(sheets)").forEach { assertEquals(it, 2, Regex(Regex.escape(it)).findAll(listening).count()) }
        listOf(pages, tabs, sheets, listening).forEach { assertFalse(it.contains("S1:")) }
    }

    private fun source(name: String): String = File("src/main/java/app/lyrify/ui", name).readText()

    private companion object {
        const val MINUTE: Long = 60_000
    }
}
