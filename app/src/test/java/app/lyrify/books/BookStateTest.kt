package app.lyrify.books

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.player.Speed
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
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
    fun `the place follows its part by path when parts are added before it, and stands at the end when no part is left after it`() {
        val state = BookState().withPlace(folderBook, BookPlace(1, 20_000), now = 1)
        val more = booksOf(listOf(song("Audiobooks/Folder Book/0.mp3", album = "Folder Book")) + parts, BookMarks()).single()
        assertEquals(BookPlace(2, 20_000), state.placeOf(more))
        val fewer = booksOf(parts.take(1), BookMarks(folders = setOf("Audiobooks/Folder Book"))).single()
        assertEquals(BookPlace(0, 60_000), state.placeOf(fewer))
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

    // A book's id and place as its parts change (phone session 11)

    private val road = (1..3).map { n -> song("Audiobooks/Lantern Road/0$n Part $n.mp3", title = "Part $n", album = "Lantern Road", track = n, durationMs = 60_000, id = 40L + n) }

    private fun shelf(songs: List<Song>, hidden: List<Song> = emptyList()): Library =
        Library.of(songs, Settings(library = LibrarySettings(hiddenSongs = hidden.map { it.ref })))

    @Test
    fun `a folder book keeps its id as its parts are hidden, unhidden, added or removed, down to one file`() {
        val two = road.take(2)
        val id = shelf(two).books.single().id
        val one = shelf(two, hidden = listOf(two[0])).books.single()
        assertEquals(id, one.id)
        // One file left: its chapters are in it.
        assertEquals(BookKind.SINGLE, one.kind)
        assertEquals(id, shelf(two).books.single().id)
        assertEquals(id, shelf(road).books.single().id)
        assertEquals(id, shelf(road.take(1)).books.single().id)
        // Not a book on it names its file, never its id.
        assertEquals(BookMarks(notSongs = setOf(two[1].path)), BookState().notABook(one).marks)
    }

    @Test
    fun `the place stays on its part by path, and when the part playing is hidden it goes to the start of the next one`() {
        val book = shelf(road).books.single()
        val inSecond = BookState().withPlace(book, BookPlace(1, 41_000), now = 1)
        assertEquals(BookPlace(0, 41_000), inSecond.placeOf(shelf(road, hidden = listOf(road[0])).books.single()))
        val noSecond = shelf(road, hidden = listOf(road[1])).books.single()
        assertEquals(BookPlace(1, 0), inSecond.placeOf(noSecond))
        // It stays there through a change made while the part is hidden (its speed; a lone file's chapters read).
        assertEquals(BookPlace(1, 0), inSecond.withSpeed(noSecond, Speed(1.5f)).placeOf(noSecond))
        // The last part playing hidden: nothing is left after it, so the book stands at its end.
        val inLast = BookState().withPlace(book, BookPlace(2, 30_000), now = 2)
        assertEquals(BookPlace(1, 60_000), inLast.placeOf(shelf(road, hidden = listOf(road[2])).books.single()))
        // A two-file book down to one file: the same second in the file left, or its start when the other one played.
        val two = shelf(road.take(2)).books.single()
        val lone = shelf(road.take(2), hidden = listOf(road[0])).books.single()
        assertEquals(BookPlace(0, 41_000), BookState().withPlace(two, BookPlace(1, 41_000), now = 3).placeOf(lone))
        assertEquals(BookPlace(0, 0), BookState().withPlace(two, BookPlace(0, 41_000), now = 3).placeOf(lone))
    }

    @Test
    fun `a book kept under its file's path, as books json had a lone file, is found and moved to the book's id at its next change`() {
        val path = road[0].path
        val old = BooksJson.decode(
            """{"schemaVersion": 1, "books": [{"id": "$path", "title": "Part 1", "parts": ["$path"], "partIndex": 0, "positionMs": 41000, "partPath": "$path", "speed": {"speed": 1.5}, "lastPlayedAt": 9}]}""",
        )!!
        // A part added beside it since: one folder book now, at the same second of its first part.
        val grown = shelf(road).books.single()
        assertEquals(BookPlace(0, 41_000), old.placeOf(grown))
        assertEquals(Speed(1.5f), old.speedOf(grown))
        val moved = old.withPlace(grown, BookPlace(1, 5_000), now = 10)
        assertEquals(setOf(grown.id), moved.books.keys)
        assertEquals(Speed(1.5f), moved.speedOf(grown))
        // A book's queue keeps the id it was played with: the old one still names the book.
        assertEquals(grown, shelf(road).book(path))
    }

    @Test
    fun `a book reads the place, speed and finished it played with last, whichever of its ids kept them, and its next change keeps its id alone`() {
        val book = shelf(road).books.single()
        fun lone(part: Int, at: Long, played: Long, speed: Float) =
            BookEntry(road[part].path, "Part ${part + 1}", parts = listOf(road[part].path), place = BookPlace(0, at), partPath = road[part].path, speed = Speed(speed), lastPlayedAt = played)
        // Its folder's id while it had three parts (finished then), and its first file's path once that file was left alone, newer.
        val folder = BookEntry(book.id, "Lantern Road", parts = road.map { it.path }, place = BookPlace(), speed = Speed(1.25f), lastPlayedAt = 5, finished = true)
        val stale = BookState(books = mapOf(book.id to folder, road[0].path to lone(0, 41_000, played = 9, speed = 1.5f)))
        assertEquals(BookPlace(0, 41_000), stale.placeOf(book))
        assertEquals(Speed(1.5f), stale.speedOf(book))
        assertFalse(stale.entryOf(book)!!.finished)
        // No folder id: a later part played more recently wins over the first part in play order.
        val parts = BookState(books = mapOf(road[0].path to lone(0, 1_000, played = 3, speed = 1f), road[1].path to lone(1, 2_000, played = 7, speed = 2f)))
        assertEquals(BookPlace(1, 2_000), parts.placeOf(book))
        assertEquals(Speed(2f), parts.speedOf(book))
        // Its next change keeps it under its id alone, at the place it read.
        val changed = stale.withSpeed(book, Speed(1.75f))
        assertEquals(setOf(book.id), changed.books.keys)
        assertEquals(BookPlace(0, 41_000), changed.placeOf(book))
        assertEquals(setOf(book.id), parts.withPlace(book, BookPlace(2, 500), now = 10).books.keys)
    }

    @Test
    fun `a book kept under its folder id as written before PF10 is found and moved, never by a book whose files it does not hold`() {
        val book = shelf(road).books.single()
        val old = road[0].path.substringBeforeLast('/') + "/|Lantern Road"
        val state = BookState(books = mapOf(old to BookEntry(old, "Lantern Road", parts = road.map { it.path }, place = BookPlace(1, 5_000), partPath = road[1].path, lastPlayedAt = 9)))
        assertEquals(BookPlace(1, 5_000), state.placeOf(book))
        assertEquals(setOf(book.id), state.withSpeed(book, Speed(1.5f)).books.keys)
        // Its queue, kept with that id, still names it.
        assertEquals(book, shelf(road).book(old))
        // Two books that had one id then: folder A with album "B/|C", and folder A/|B with album "C". The entry is the one whose files it holds.
        val first = (1..2).map { song("Audiobooks/A/0$it.mp3", album = "B/|C", durationMs = 60_000, id = 60L + it) }
        val second = (1..2).map { song("Audiobooks/A/|B/0$it.mp3", album = "C", durationMs = 60_000, id = 70L + it) }
        val both = shelf(first + second)
        val shared = first[0].path.substringBeforeLast('/') + "/|B/|C"
        val kept = BookState(books = mapOf(shared to BookEntry(shared, "C", parts = second.map { it.path }, place = BookPlace(1, 7_000), partPath = second[1].path, lastPlayedAt = 4)))
        assertEquals(BookPlace(), kept.placeOf(both.bookOf(first[0].path)!!))
        assertEquals(BookPlace(1, 7_000), kept.placeOf(both.bookOf(second[0].path)!!))
        assertEquals(setOf(shared), kept.withSpeed(both.bookOf(first[0].path)!!, Speed(2f)).books.keys - both.bookOf(first[0].path)!!.id)
        // A queue kept with it named both: it names neither now.
        assertNull(both.book(shared))
    }

    @Test
    fun `a book down to one file never shows another file's chapters`() {
        val two = road.take(2)
        val first = shelf(two, hidden = listOf(two[1])).books.single()
        val state = BookState().withChapters(first, chaptersStamp(two[0]), listOf(Chapter(0, 30_000, "One")))
        assertEquals(listOf(Chapter(0, 30_000, "One")), bookChapters(first, state))
        val second = shelf(two, hidden = listOf(two[0])).books.single()
        assertEquals(emptyList<Chapter>(), bookChapters(second, state))
        assertNull(state.entryOf(second)?.chaptersStamp)
    }
}
