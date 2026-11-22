package app.lyrify.ui

import app.lyrify.library.SongRef
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.player.Suggested
import app.lyrify.settings.SuggestionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Where Up next puts Autoplay's card, and when Library's For you card goes (SuggestionScreens.kt). Made-up names only. */
class SuggestionScreensTest {
    private val songs = (1..5).map { SongRef("/storage/emulated/0/Music/Road/Mile $it.mp3") }
    private val trip = QueueSource(SourceKind.PLAYLIST, "p1", "Road trip")

    private fun playing(at: Int, source: QueueSource = trip) = QueueBook().play(songs.take(3), at, source, now = 1).book

    @Test
    fun `Autoplay's card goes before the first song Autoplay put in, or at the end as the last song comes near`() {
        val early = playing(0).current!!
        assertNull(autoplayCut(early, early.items))
        val near = playing(1).current!!
        assertEquals(3, autoplayCut(near, near.items))
        val book = playing(2)
        val queue = book.current!!
        val fed = book.suggest(queue.id, listOf(queue.items.last().uid to songs[3], queue.items.last().uid to songs[4]), Suggested.AUTOPLAY).current!!
        assertEquals(3, autoplayCut(fed, fed.items))
    }

    @Test
    fun `a book's queue has no Autoplay card`() {
        val book = playing(2, QueueSource(SourceKind.BOOK, "b1", "A long book")).current!!
        assertNull(autoplayCut(book, book.items))
    }

    @Test
    fun `the For you card goes until the next day's mix once its Play, Shuffle or Hide is tapped, and For you's page and Insights stay`() {
        // Until the next midnight where the phone is, as Hide had it.
        assertEquals(
            ZonedDateTime.of(2026, 11, 23, 0, 0, 0, 0, ZoneId.of("Asia/Amman")).toInstant().toEpochMilli(),
            SuggestionSettings().hiddenTillTomorrow(ZonedDateTime.of(2026, 11, 22, 21, 30, 0, 0, ZoneId.of("Asia/Amman"))).forYouHiddenUntil,
        )
        val screens = File("src/main/java/app/lyrify/ui/SuggestionScreens.kt").readText()
        // Play, Shuffle and Hide on the card each hide it so, on a scope that outlives the card it hides.
        val card = screens.substringAfter("internal fun ForYouCard(").substringBefore("\n}\n")
        listOf("playback.play(refs, 0, source)", "playback.shuffle(refs, source)", "R.string.suggest_hide").forEach { button ->
            assertTrue(button, card.substringAfter(button).substringBefore("LyrifyButton(").contains("hiddenTillTomorrow(ZonedDateTime.now())"))
        }
        assertTrue(card.contains("val scope = LocalLifecycleOwner.current.lifecycleScope"))
        // For you's page and Insights' For you show either way: the page only clears it as its menu turns the card on or off.
        assertFalse(screens.substringAfter("fun ForYouInsights(").substringBefore("\n}\n").contains("forYouHiddenUntil"))
        assertEquals(
            listOf("forYouHiddenUntil = null"),
            Regex("""forYouHiddenUntil[^,)]*""").findAll(screens.substringAfter("private fun ForYouPage(").substringBefore("\n}\n")).map { it.value }.toList(),
        )
    }
}
