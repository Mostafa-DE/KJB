package app.lyrify.books

import app.lyrify.library.song
import app.lyrify.player.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Books tab and the player show of a book: its card, the Continue row, the chapter playing. Made-up names only. */
class BookShelfTest {
    private val parts = listOf("1", "2", "3").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 1_200_000) }
    private val folder = booksOf(parts, BookMarks()).single()
    private val single = booksOf(listOf(song("Audiobooks/Lantern Keeper.m4b", title = "Lantern Keeper", durationMs = 7_800_000)), BookMarks()).single()
    private val chapters = listOf(Chapter(0, 600_000, "One"), Chapter(600_000, 3_000_000, "Two"), Chapter(3_000_000, 7_800_000, "Three"))
    private val read = BookState().withChapters(single, chaptersStamp(single.parts.single()), chapters)

    @Test
    fun `a card says Not started, where it is with the time left at its speed, or Finished`() {
        assertEquals(BookStatus.NotStarted(3_600_000), bookStatus(folder, BookState()))
        val listening = BookState().withPlace(folder, BookPlace(1, 600_000), now = 1)
        assertEquals(BookStatus.Listening(2, 3, 1_800_000, 0.5f), bookStatus(folder, listening))
        assertEquals(BookStatus.Listening(2, 3, 1_200_000, 0.5f), bookStatus(folder, listening.withSpeed(folder, Speed(1.5f))))
        assertEquals(BookStatus.Finished, bookStatus(folder, listening.finished(folder, now = 2)))
        // A single file without chapters: the time left only.
        val plain = booksOf(listOf(song("Audiobooks/Quiet Orchard.m4b", durationMs = 1_000_000)), BookMarks()).single()
        assertEquals(BookStatus.Listening(null, 0, 750_000, 0.25f), bookStatus(plain, BookState().withPlace(plain, BookPlace(0, 250_000), now = 1)))
        // Chapters read, not played: still not started.
        assertEquals(BookStatus.NotStarted(7_800_000), bookStatus(single, read))
    }

    @Test
    fun `Continue is the book played last that is not finished, and played books come first, the latest first`() {
        val other = booksOf(listOf(song("Audiobooks/Quiet Orchard.m4b", title = "Quiet Orchard")), BookMarks()).single()
        val books = listOf(folder, single, other)
        assertNull(continueBook(books, BookState()))
        val state = BookState().withPlace(single, BookPlace(0, 1_000), now = 5).withPlace(folder, BookPlace(0, 1_000), now = 9)
        assertEquals(folder, continueBook(books, state))
        assertEquals(single, continueBook(books, state.finished(folder, now = 10)))
        assertEquals(listOf(folder, single, other), booksInOrder(books, state))
        assertEquals(listOf(single, folder, other), booksInOrder(books, state.withPlace(single, BookPlace(0, 2_000), now = 11)))
    }

    @Test
    fun `the player's chapter, ticks and jumps in a single file`() {
        val now = bookNow(single, 0, 700_000, read)
        assertEquals(1, now.chapter)
        assertEquals(700_000L, now.bookMs)
        assertEquals(listOf(600_000f / 7_800_000, 3_000_000f / 7_800_000), chapterTicks(now, 7_800_000))
        assertEquals(BookPlace(0, 3_000_000), chapterPlace(now, 2))
        assertEquals(2, nextChapter(now))
        // More than 3 s in: back to the start of this chapter; within 3 s: the one before.
        assertEquals(1, previousChapter(now))
        assertEquals(0, previousChapter(bookNow(single, 0, 601_000, read)))
        assertEquals(0, previousChapter(bookNow(single, 0, 1_000, read)))
        assertNull(nextChapter(bookNow(single, 0, 4_000_000, read)))
    }

    @Test
    fun `a folder book's chapters are its parts, with no ticks, and a jump goes to a part`() {
        val now = bookNow(folder, 1, 30_000, BookState())
        assertEquals(1, now.chapter)
        assertEquals(1_230_000L, now.bookMs)
        assertEquals(emptyList<Float>(), chapterTicks(now, 1_200_000))
        assertEquals(BookPlace(2, 0), chapterPlace(now, 2))
        assertEquals(1, previousChapter(now))
        // Without chapters nothing jumps.
        val plain = bookNow(booksOf(listOf(song("Audiobooks/Quiet Orchard.m4b")), BookMarks()).single(), 0, 5_000, BookState())
        assertEquals(-1, plain.chapter)
        assertNull(previousChapter(plain))
        assertNull(nextChapter(plain))
        assertEquals(emptyList<Float>(), chapterTicks(plain, 200_000))
    }
}
