package app.lyrify.books

import app.lyrify.library.song
import app.lyrify.player.Speed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `books.json`: places, speeds, finished, chapters and the user's marks, kept and read back leniently. Made-up names only. */
class BookStateTest {
    private val parts = listOf("1", "2", "3").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 60_000) }
    private val folderBook = booksOf(parts, BookMarks()).single()
    private val single = booksOf(listOf(song("Audiobooks/Lantern Keeper.m4b", title = "Lantern Keeper", durationMs = 3_600_000)), BookMarks()).single()

    @Test
    fun `a place is kept with when it played, and a book never played starts at its start`() {
        assertEquals(BookPlace(), BookState().placeOf(folderBook))
        val state = BookState().withPlace(folderBook, BookPlace(1, 20_000), now = 500)
        val entry = state.entryOf(folderBook)!!
        assertEquals(BookPlace(1, 20_000), state.placeOf(folderBook))
        assertEquals(parts[1].path, entry.partPath)
        assertEquals(500L, entry.lastPlayedAt)
        assertEquals("Folder Book", entry.title)
        assertEquals(parts.map { it.path }, entry.parts)
    }

    @Test
    fun `the place follows its part by path when parts are added before it, and clamps when the part is gone`() {
        val state = BookState().withPlace(folderBook, BookPlace(1, 20_000), now = 1)
        val more = booksOf(listOf(song("Audiobooks/Folder Book/0.mp3", album = "Folder Book")) + parts, BookMarks()).single()
        assertEquals(BookPlace(2, 20_000), state.placeOf(more))
        val fewer = booksOf(parts.take(1), BookMarks(folders = setOf("Audiobooks/Folder Book"))).single()
        assertEquals(BookPlace(0, 0), state.copy(books = state.books.mapKeys { fewer.id }).placeOf(fewer))
    }

    @Test
    fun `played to its end a book is finished and back at its start, until it plays on past the start`() {
        val done = BookState().withPlace(folderBook, BookPlace(2, 50_000), now = 1).finished(folderBook, now = 2)
        assertTrue(done.entryOf(folderBook)!!.finished)
        assertEquals(BookPlace(), done.placeOf(folderBook))
        // The pause that follows the end keeps the start: still finished.
        assertTrue(done.withPlace(folderBook, BookPlace(), now = 3).entryOf(folderBook)!!.finished)
        assertFalse(done.withPlace(folderBook, BookPlace(0, 15_000), now = 4).entryOf(folderBook)!!.finished)
        val over = done.withPlace(folderBook, BookPlace(1, 5_000), now = 5).startOver(folderBook)
        assertEquals(BookPlace(), over.placeOf(folderBook))
        assertFalse(over.entryOf(folderBook)!!.finished)
    }

    @Test
    fun `each book has its own speed, a new one 1x`() {
        val state = BookState().withSpeed(single, Speed(1.5f))
        assertEquals(Speed(1.5f), state.speedOf(single))
        assertEquals(Speed(), state.speedOf(folderBook))
        assertEquals(Speed(2f), state.withSpeed(single, Speed(3.7f)).speedOf(single))
    }

    @Test
    fun `marks on songs and folders, each the other way dropped`() {
        val a = parts[0].path
        val state = BookState().markSongs(listOf(a), on = false)
        assertEquals(setOf(a), state.marks.notSongs)
        val marked = state.markSongs(listOf(a), on = true)
        assertEquals(setOf(a), marked.marks.songs)
        assertTrue(marked.marks.notSongs.isEmpty())
        // A folder's answer holds for each of its own songs.
        val folder = marked.markFolder("Audiobooks/Folder Book", parts.map { it.path }, on = false)
        assertEquals(setOf("Audiobooks/Folder Book"), folder.marks.notFolders)
        assertTrue(folder.marks.songs.isEmpty())
        val back = folder.markFolder("Audiobooks/Folder Book", parts.map { it.path }, on = true)
        assertEquals(BookMarks(folders = setOf("Audiobooks/Folder Book")), back.marks)
    }

    @Test
    fun `Not a book on a book says it of its file or of its folder`() {
        assertEquals(BookMarks(notSongs = setOf(single.id)), BookState().notABook(single).marks)
        assertEquals(BookMarks(notFolders = setOf("Audiobooks/Folder Book")), BookState().notABook(folderBook).marks)
    }

    @Test
    fun `the menus' book row is Mark as book on music, Not a book on a book's part or a folder holding one`() {
        val music = song("Music/Rips/Quiet Orchard.mp3")
        assertEquals(BookMarks(songs = setOf(music.path)), BookState().toggled(music, book = null).marks)
        assertEquals(BookMarks(notFolders = setOf("Audiobooks/Folder Book")), BookState().toggled(parts[1], folderBook).marks)
        assertEquals(BookMarks(notSongs = setOf(single.id)), BookState().toggled(single.parts.single(), single).marks)
        assertEquals(BookMarks(folders = setOf("Music/Rips")), BookState().toggledFolder("Music/Rips", listOf(music.path), holdsBook = false).marks)
        assertEquals(BookMarks(notFolders = setOf("Audiobooks/Folder Book")), BookState().toggledFolder("Audiobooks/Folder Book", parts.map { it.path }, holdsBook = true).marks)
    }

    @Test
    fun `everything kept reads back the same`() {
        val state = BookState()
            .withPlace(folderBook, BookPlace(2, 30_000), now = 1_700)
            .withSpeed(folderBook, Speed(1.25f, keepPitch = true))
            .withChapters(single, "4000:9", listOf(Chapter(0, 4_000, "One"), Chapter(4_000, 8_000, null)))
            .finished(single, now = 2_000)
            .markSongs(listOf("/storage/emulated/0/Music/Quiet Orchard.mp3"), on = true)
            .markFolder("Music/Rips", emptyList(), on = false)
        assertEquals(state, BooksJson.decode(BooksJson.encode(state)))
    }

    @Test
    fun `read leniently, one bad book or chapter is left out and counted`() {
        val text = """
            {"schemaVersion": 1, "future": true,
             "books": [
               {"id": "a", "title": "Lantern Keeper", "partIndex": 0, "positionMs": 5000, "chapters": [{"startMs": 0, "endMs": 10, "title": "One"}, {"startMs": "x"}]},
               {"title": "no id"},
               "not a book",
               {"id": "b", "title": "Quiet Orchard", "speed": {"speed": "fast"}, "finished": true}
             ],
             "marks": {"songs": ["/x.mp3", 3], "notFolders": ["Music/Rips"]}}
        """.trimIndent()
        var dropped = 0
        val state = BooksJson.decode(text) { dropped += it }!!
        assertEquals(setOf("a", "b"), state.books.keys)
        assertEquals(listOf(Chapter(0, 10, "One")), state.books.getValue("a").chapters)
        assertEquals(5_000L, state.books.getValue("a").place.positionMs)
        assertEquals(Speed(), state.books.getValue("b").speed)
        assertTrue(state.books.getValue("b").finished)
        assertEquals(BookMarks(songs = setOf("/x.mp3"), notFolders = setOf("Music/Rips")), state.marks)
        assertEquals(4, dropped)
        assertNull(BooksJson.decode("[]"))
        assertNull(BooksJson.decode("not json"))
    }

    @Test
    fun `the caps hold on reading and on change, the books played longest ago going first`() {
        val many = (0 until BookState.MAX_BOOKS + 5).fold(BookState()) { state, n ->
            val book = booksOf(listOf(song("Audiobooks/$n.m4b", title = "Book $n")), BookMarks()).single()
            state.withPlace(book, BookPlace(0, 10_000), now = n.toLong())
        }
        assertEquals(BookState.MAX_BOOKS, many.books.size)
        assertFalse(many.books.values.any { it.lastPlayedAt!! < 5 })
        val long = BookState().withChapters(single, "1:1", List(BookState.MAX_CHAPTERS + 10) { Chapter(it * 1_000L, it * 1_000L + 1_000, "x".repeat(300)) })
        val chapters = long.entryOf(single)!!.chapters!!
        assertEquals(BookState.MAX_CHAPTERS, chapters.size)
        assertEquals(BookState.MAX_TITLE, chapters.first().title!!.length)
        val stored = Json.parseToJsonElement(BooksJson.encode(many)) as JsonObject
        assertEquals(BookState.MAX_BOOKS, (stored["books"] as JsonArray).size)
    }
}
