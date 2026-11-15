package app.lyrify.ui

import app.lyrify.library.Library
import app.lyrify.library.song
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Books tab's place in Library, and its words. Made-up names only. */
class BooksUiTest {
    private val strings: Map<String, String> by lazy {
        val text = File("src/main/res/values/strings_books.xml").readText()
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the Books tab shows only while the library holds a book, the other tabs always`() {
        val music = Library.of(listOf(song("Music/Harbour Glass/01 Paper Lantern.mp3")), Settings())
        val withBook = Library.of(listOf(song("Audiobooks/Lantern Keeper.m4b")), Settings())
        assertFalse(libraryTabShows(LibraryTab.BOOKS, music))
        assertFalse(libraryTabShows(LibraryTab.BOOKS, null))
        assertTrue(libraryTabShows(LibraryTab.BOOKS, withBook))
        LibraryTab.entries.filter { it != LibraryTab.BOOKS }.forEach { assertTrue(libraryTabShows(it, music)) }
    }

    @Test
    fun `labels are three words or fewer, the status lines are the spec's, and no text has an em-dash`() {
        val lines = setOf("book_chapter_of", "book_left", "book_hours_minutes", "book_minutes", "book_source")
        val sentences = setOf("speed_book_sentence", "book_looks_like")
        strings.filterKeys { it !in lines + sentences }.forEach { (name, text) -> assertTrue("$name: $text", text.split(' ').size <= 3) }
        assertEquals("Chapter 7 of 22", strings.getValue("book_chapter_of").replace("%1\$d", "7").replace("%2\$d", "22"))
        assertEquals("2 h 10 m left", strings.getValue("book_left").replace("%1\$s", strings.getValue("book_hours_minutes").replace("%1\$d", "2").replace("%2\$d", "10")))
        assertTrue(strings.values.none { '\u2014' in it })
        assertFalse(File("src/main/java/app/lyrify/ui/BooksScreen.kt").readText().contains('\u2014'))
        File("src/main/java/app/lyrify/books").listFiles().orEmpty().forEach { assertFalse(it.name, it.readText().contains('\u2014')) }
    }
}
