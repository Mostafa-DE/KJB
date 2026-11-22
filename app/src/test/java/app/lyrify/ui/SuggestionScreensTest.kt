package app.lyrify.ui

import app.lyrify.library.SongRef
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.player.Suggested
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where Up next puts Autoplay's card (SuggestionScreens.kt). Made-up names only. */
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
}
