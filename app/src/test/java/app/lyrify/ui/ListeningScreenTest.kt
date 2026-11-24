package app.lyrify.ui

import app.lyrify.settings.LibraryTab
import app.lyrify.stats.InsightsCards
import app.lyrify.stats.TopCard
import app.lyrify.stats.Totals
import app.lyrify.stats.fakeInsightsPage
import app.lyrify.ui.InsightsCardKind.ALBUMS
import app.lyrify.ui.InsightsCardKind.ARTISTS
import app.lyrify.ui.InsightsCardKind.CLOCK
import app.lyrify.ui.InsightsCardKind.DAYS
import app.lyrify.ui.InsightsCardKind.EMPTY
import app.lyrify.ui.InsightsCardKind.FOR_YOU
import app.lyrify.ui.InsightsCardKind.FOUND
import app.lyrify.ui.InsightsCardKind.FRIENDS
import app.lyrify.ui.InsightsCardKind.HOW
import app.lyrify.ui.InsightsCardKind.LIBRARY
import app.lyrify.ui.InsightsCardKind.LYRICS
import app.lyrify.ui.InsightsCardKind.MILESTONES
import app.lyrify.ui.InsightsCardKind.MOMENTS
import app.lyrify.ui.InsightsCardKind.PRIVATE
import app.lyrify.ui.InsightsCardKind.SEARCHES
import app.lyrify.ui.InsightsCardKind.SONGS
import app.lyrify.ui.InsightsCardKind.SPOKEN
import app.lyrify.ui.InsightsCardKind.STORIES
import app.lyrify.ui.InsightsCardKind.TASTE
import app.lyrify.ui.InsightsCardKind.TIME
import app.lyrify.ui.InsightsCardKind.TRAITS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What Insights shows, as pure logic and, where only the wiring can say it, as its source. */
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

    // The redesign (IN2U): which cards show

    @Test
    fun `every card shows in the mockup's order when the page has it`() {
        assertEquals(
            listOf(STORIES, TIME, FOR_YOU, DAYS, CLOCK, ARTISTS, SONGS, ALBUMS, TASTE, HOW, MOMENTS, FOUND, FRIENDS, LYRICS, SEARCHES, SPOKEN, LIBRARY, TRAITS, MILESTONES, PRIVATE),
            insightsCardsOf(fakeInsightsPage().cards),
        )
    }

    @Test
    fun `a card without its data does not show, so there are no empty boxes`() {
        // Nothing heard: the empty state where your time goes, and the note; no stories, no For you.
        assertEquals(listOf(EMPTY, PRIVATE), insightsCardsOf(InsightsCards()))
        val full = fakeInsightsPage().cards
        // A top list with no rows, no friends to match, nothing that read lyrics or hearted: none of their cards.
        val thin = full.copy(
            artists = TopCard(emptyList(), compared = false),
            friends = full.friends?.copy(matches = emptyList()),
            lyrics = null,
            favourites = null,
            moments = emptyList(),
            traits = emptyList(),
        )
        val shown = insightsCardsOf(thin)
        listOf(ARTISTS, FRIENDS, LYRICS, MOMENTS, TRAITS).forEach { assertFalse("$it", it in shown) }
        // New to you stands for comebacks or quiet favourites alone; read along for new favourites alone.
        assertTrue(FOUND in insightsCardsOf(full.copy(found = null, forgotten = emptyList())))
        assertTrue(LYRICS in insightsCardsOf(full.copy(lyrics = null)))
        assertFalse(FOUND in insightsCardsOf(full.copy(found = null, comebacks = emptyList(), forgotten = emptyList())))
        // The stories row stands for past recaps even in a quiet period.
        assertEquals(listOf(STORIES, EMPTY, PRIVATE), insightsCardsOf(InsightsCards(recaps = full.recaps)))
    }

    @Test
    fun `the stories row follows this month's and this year's own time, so a quiet week keeps it and no ring opens an empty story`() {
        val some = Totals(heardMs = 90 * 60_000L, plays = 30, songs = 20, artists = 8, albums = 10)
        // A quiet week in a month with time: the row stays, the month and the year so far in it.
        assertEquals(listOf(STORIES, EMPTY, PRIVATE), insightsCardsOf(InsightsCards(monthSoFar = some, yearSoFar = some)))
        assertEquals(listOf(STORIES, EMPTY, PRIVATE), insightsCardsOf(InsightsCards(yearSoFar = some)))
        // Your time alone (a week of last month's days, nothing this month or recap yet) makes no row of empty rings.
        assertFalse(STORIES in insightsCardsOf(fakeInsightsPage().cards.copy(recaps = emptyList(), monthSoFar = null, yearSoFar = null)))
        val story = source("InsightsStory.kt")
        assertTrue(story.contains("if (page.cards.monthSoFar != null) {"))
        assertEquals(2, Regex(Regex.escape("page.cards.yearSoFar != null")).findAll(story).count())
        assertFalse(story.contains("val going = page.cards.time != null"))
    }

    @Test
    fun `the page draws the cards insightsCardsOf names, each arriving as it scrolls in`() {
        val listening = source("ListeningScreen.kt")
        val cards = listening.substringAfter("private fun LazyListScope.insightsCards(").substringBefore("\n}\n")
        assertTrue(cards.contains("insightsCardsOf(cards).forEach"))
        // Every card but the stories row, For you, the empty state and the note arrives ([Arriving]).
        assertTrue(cards.contains("Arriving(key.name, list, arrived, period.kind"))
    }

    // Reading

    @Test
    fun `Insights are worked out only while they show, the app on screen, and at most every few seconds as plays are written`() {
        val listening = source("ListeningScreen.kt")
        val reads = listening.substringAfter("internal fun <T> rememberStats(").substringBefore("\n}\n")
        listOf(
            "val shown = LocalLayerHeaded.current",
            "if (!shown) return@produceState",
            "lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)",
            "StatsGraph.writes(context).conflate().collect",
            "delay(REREAD_MS)",
        ).forEach { assertTrue(it, reads.contains(it)) }
        assertTrue(listening.contains("private const val REREAD_MS: Long = 3_000"))
        // The page and the story read in2d's page model, never the cards' own sums.
        assertTrue(listening.contains("StatsGraph.stats(context).page(period, System.currentTimeMillis(), phoneCalendar())"))
        assertTrue(source("InsightsStory.kt").contains("stats.page(month, System.currentTimeMillis(), phoneCalendar())"))
    }

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
    fun `the recap card sits first on every Library tab, only in Library itself, and plays the month's story`() {
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
        ).forEach { assertTrue(it, library.contains(it)) }
        val card = source("InsightsMilestones.kt")
        assertTrue(card.contains("if (tab != libraryOpensOn(shownLibraryTabs(read.library, (library as? LibraryState.Ready)?.library), read.library.opensOn)) return"))
        // The month turns with the phone's day, so an app kept running across the 1st shows the new month's card (Grok b-ui).
        assertTrue(card.contains("val month = remember(today) { recapMonthOf(System.currentTimeMillis(), zone) }"))
        // Changed on purpose (IN2U): a tap plays the month's story; the recap pages are stories now.
        assertTrue(card.contains("nav.show(FullScreen.Story(month = month.toString()))"))
        // On its own bar item Insights has no back arrow.
        assertTrue(source("AppShell.kt").contains("DockTab.INSIGHTS -> ListeningPage(Page.Listening, root = true)"))
    }

    private fun source(name: String): String = File("src/main/java/app/lyrify/ui", name).readText()

    private companion object {
        const val MINUTE: Long = 60_000
    }
}
