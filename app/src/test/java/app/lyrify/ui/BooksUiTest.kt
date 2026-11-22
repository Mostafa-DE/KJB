package app.lyrify.ui

import app.lyrify.R
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
    fun `the player's book row shows the chapter icons and music the skip icons, TalkBack hearing chapter words in a book`() {
        assertEquals(R.drawable.ic_chapter_previous to R.drawable.ic_chapter_next, stepIcons(book = true))
        assertEquals(R.drawable.ic_skip_previous_fill to R.drawable.ic_skip_next_fill, stepIcons(book = false))
        // Unit tests run in the module's folder.
        val controls = File("src/main/java/app/lyrify/ui/PlayerControls.kt").readText()
        assertTrue(controls.contains("val icons = stepIcons(book != null)"))
        assertTrue(controls.contains("RoundButton(icons.first, stringResource(R.string.book_previous_chapter)"))
        assertTrue(controls.contains("RoundButton(icons.second, stringResource(R.string.book_next_chapter)"))
        assertTrue(controls.contains("RoundButton(icons.first, stringResource(R.string.player_previous)"))
        assertTrue(controls.contains("RoundButton(icons.second, stringResource(R.string.player_next)"))
        assertEquals("Previous chapter", strings["book_previous_chapter"])
        assertEquals("Next chapter", strings["book_next_chapter"])
    }

    @Test
    fun `labels are three words or fewer, the status lines are the spec's, and no text has an em-dash`() {
        val lines = setOf("book_chapter_of", "book_left", "book_hours_minutes", "book_minutes", "book_source", "book_no_lyrics")
        val sentences = setOf("speed_book_sentence", "book_looks_like", "book_cover")
        strings.filterKeys { it !in lines + sentences }.forEach { (name, text) -> assertTrue("$name: $text", text.split(' ').size <= 3) }
        assertEquals("Chapter 7 of 22", strings.getValue("book_chapter_of").replace("%1\$d", "7").replace("%2\$d", "22"))
        assertEquals("2 h 10 m left", strings.getValue("book_left").replace("%1\$s", strings.getValue("book_hours_minutes").replace("%1\$d", "2").replace("%2\$d", "10")))
        assertEquals("No lyrics for books", strings["book_no_lyrics"])
        // A book's cover never flips: its label offers no lyrics.
        assertFalse(strings.getValue("book_cover").contains("lyrics", ignoreCase = true))
        assertTrue(strings.values.none { '\u2014' in it })
        assertFalse(File("src/main/java/app/lyrify/ui/BooksScreen.kt").readText().contains('\u2014'))
        File("src/main/java/app/lyrify/books").listFiles().orEmpty().forEach { assertFalse(it.name, it.readText().contains('\u2014')) }
    }
}
