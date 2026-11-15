package app.lyrify.books

import app.lyrify.library.Library
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What makes a book, how books group, their chapters and where a listener is in one. Made-up names only. */
class BooksTest {
    private val tale = song("Audiobooks/Lantern Keeper/Lantern Keeper.mp3", album = "Lantern Keeper", durationMs = 3_600_000)

    // What makes a song a book

    @Test
    fun `each strong signal alone makes a book`() {
        assertTrue(autoBook(song("Music/Rips/Quiet Orchard.mp3", isAudiobook = true)))
        assertTrue(autoBook(song("Audiobooks/Quiet Orchard.mp3")))
        assertTrue(autoBook(song("Music/Quiet Orchard.m4b")))
        assertTrue(autoBook(song("Music/Quiet Orchard.M4B")))
        listOf("Audiobook", " audiobooks ", "Audio Book", "SPOKEN WORD").forEach { genre ->
            assertTrue(genre, autoBook(song("Music/Quiet Orchard.mp3", genre = genre)))
        }
    }

    @Test
    fun `plain music, podcasts and long files are not books by themselves`() {
        assertFalse(autoBook(song("Music/Harbour Glass/01 Paper Lantern.mp3", genre = "Folk")))
        assertFalse(autoBook(song("Music/Live/Tin Roof Set.mp3", durationMs = 3 * 3_600_000)))
        assertFalse(autoBook(song("Podcasts/Glass Talk/Episode 4.mp3")))
        assertFalse(autoBook(song("Music/Quiet Orchard.m4a")))
        assertFalse(autoBook(song("Music/Audiobooks Mix/Paper Lantern.mp3")))
    }

    @Test
    fun `an Audiobooks folder counts at any depth and in any case`() {
        assertTrue(inAudiobooksFolder("Audiobooks/"))
        assertTrue(inAudiobooksFolder("Music/audiobooks/Lantern Keeper/"))
        assertTrue(inAudiobooksFolder("AUDIOBOOKS"))
        assertFalse(inAudiobooksFolder("Music/Audiobook/"))
        assertFalse(inAudiobooksFolder(""))
    }

    @Test
    fun `the user's own mark wins over the folder's, both ways`() {
        val file = song("Music/Rips/Quiet Orchard.mp3")
        assertEquals(null, userMark(file, BookMarks()))
        assertEquals(true, userMark(file, BookMarks(folders = setOf("Music/Rips"))))
        assertEquals(false, userMark(file, BookMarks(folders = setOf("Music/Rips"), notSongs = setOf(file.path))))
        assertEquals(true, userMark(file, BookMarks(songs = setOf(file.path), notFolders = setOf("Music/Rips"))))
        assertEquals(false, userMark(file, BookMarks(notFolders = setOf("Music/Rips"))))
    }

    // Books from the library's songs

    @Test
    fun `a single file with a signal is a book of its own, titled by its title tag`() {
        val books = booksOf(listOf(tale, song("Music/Harbour Glass/01 Paper Lantern.mp3", album = "Harbour Glass")), BookMarks())
        val book = books.single()
        assertEquals(BookKind.SINGLE, book.kind)
        // Named by its folder and album, as it would be with more parts: its id holds when one is added beside it.
        assertEquals(folderBookId(VOLUME + "Audiobooks/Lantern Keeper", "Lantern Keeper"), book.id)
        assertEquals(listOf(tale), book.parts)
        assertEquals("Lantern Keeper", book.title)
        assertEquals(3_600_000L, book.durationMs)
    }

    @Test
    fun `a folder of parts with one album makes one folder book in natural order`() {
        val parts = listOf("10", "2", "1").map { n -> song("Audiobooks/Folder Book/$n.mp3", title = n, artist = "Ada Quill", album = "Folder Book", durationMs = 60_000) }
        val book = booksOf(parts, BookMarks()).single()
        assertEquals(BookKind.FOLDER, book.kind)
        assertEquals(folderBookId(VOLUME + "Audiobooks/Folder Book", "Folder Book"), book.id)
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), book.parts.map { it.fileName })
        assertEquals("Folder Book", book.title)
        assertEquals("Ada Quill", book.author)
        assertEquals("Audiobooks/Folder Book", book.folder)
        assertEquals(180_000L, book.durationMs)
        assertEquals(120_000L, book.partStartMs(2))
    }

    @Test
    fun `track numbers order a folder book when every part has one, the disc first`() {
        val parts = listOf(
            song("Audiobooks/Folder Book/a.mp3", album = "Folder Book", track = 2, disc = 1),
            song("Audiobooks/Folder Book/b.mp3", album = "Folder Book", track = 1, disc = 2),
            song("Audiobooks/Folder Book/c.mp3", album = "Folder Book", track = 1, disc = 1),
        )
        assertEquals(listOf("c.mp3", "a.mp3", "b.mp3"), partOrder(parts).map { it.fileName })
        // One part without a number: the names decide.
        assertEquals(listOf("a.mp3", "b.mp3", "c.mp3"), partOrder(parts + song("Audiobooks/Folder Book/d.mp3", album = "Folder Book")).map { it.fileName }.take(3))
    }

    @Test
    fun `natural order compares numbers by value, the rest without case`() {
        assertTrue(naturalCompare("2.mp3", "10.mp3") < 0)
        assertTrue(naturalCompare("Part 9", "part 10") < 0)
        assertTrue(naturalCompare("01 Wolf", "1 Wolf") != 0)
        assertEquals(0, naturalCompare("Part 2", "Part 2"))
        assertTrue(naturalCompare("a", "B") < 0)
    }

    @Test
    fun `a folder book without an album tag is named by its folder`() {
        val parts = listOf("1", "2").map { song("Audiobooks/Folder Book/$it.mp3", album = null) }
        assertEquals("Folder Book", booksOf(parts, BookMarks()).single().title)
    }

    @Test
    fun `mixed albums in one folder do not group, one file alone does not group`() {
        val parts = listOf(
            song("Audiobooks/Shelf/1.mp3", album = "Lantern Keeper"),
            song("Audiobooks/Shelf/2.mp3", album = "Lantern Keeper"),
            song("Audiobooks/Shelf/3.mp3", album = "Quiet Orchard"),
        )
        val books = booksOf(parts, BookMarks()).associateBy { it.title }
        assertEquals(BookKind.FOLDER, books.getValue("Lantern Keeper").kind)
        assertEquals(2, books.getValue("Lantern Keeper").parts.size)
        assertEquals(BookKind.SINGLE, books.getValue("3").kind)
    }

    @Test
    fun `an m4b is always a book of its own, never a part`() {
        val parts = listOf("Part 1.m4b", "Part 2.m4b").map { song("Music/Shelf/$it", album = "Lantern Keeper") }
        val books = booksOf(parts, BookMarks())
        assertEquals(listOf(BookKind.SINGLE, BookKind.SINGLE), books.map { it.kind })
    }

    @Test
    fun `a folder and album is a book only when each of its files has a sign, so one spoken track never takes its album`() {
        val tagged = listOf("01", "02").map { song("Music/Rips/Lantern Keeper/$it.mp3", album = "Lantern Keeper", genre = "Audiobook") }
        assertEquals(2, booksOf(tagged, BookMarks()).single().parts.size)
        val album = (1..10).map { n -> song("Music/Harbour Glass/$n.mp3", album = "Harbour Glass", track = n, genre = if (n == 4) "Spoken Word" else "Folk") }
        assertTrue(booksOf(album, BookMarks()).isEmpty())
        val library = Library.of(album, Settings())
        assertEquals(album.map { it.path }, library.album(library.albums.single().key)!!.songs.map { it.path })
        // The user can still make it a book, or that one track alone.
        assertEquals(10, booksOf(album, BookMarks(folders = setOf("Music/Harbour Glass"))).single().parts.size)
        assertEquals(BookKind.SINGLE, booksOf(album, BookMarks(songs = setOf(album[3].path))).single().kind)
    }

    @Test
    fun `music without a signal is no book, until the user marks it`() {
        val parts = listOf("01", "02").map { song("Music/Rips/Lantern Keeper/$it.mp3", album = "Lantern Keeper") }
        assertTrue(booksOf(parts, BookMarks()).isEmpty())
        val folder = booksOf(parts, BookMarks(folders = setOf("Music/Rips/Lantern Keeper"))).single()
        assertEquals(BookKind.FOLDER, folder.kind)
        // A song marked alone is a book of its own and leaves its group.
        val single = booksOf(parts, BookMarks(songs = setOf(parts[0].path))).single()
        assertEquals(BookKind.SINGLE, single.kind)
        assertEquals(parts[0].path, single.id)
    }

    @Test
    fun `Not a book keeps a song or a folder music whatever the signals say`() {
        assertTrue(booksOf(listOf(tale), BookMarks(notSongs = setOf(tale.path))).isEmpty())
        assertTrue(booksOf(listOf(tale), BookMarks(notFolders = setOf("Audiobooks/Lantern Keeper"))).isEmpty())
        val parts = listOf("1", "2", "3").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book") }
        assertEquals(listOf("1.mp3", "3.mp3"), booksOf(parts, BookMarks(notSongs = setOf(parts[1].path))).single().parts.map { it.fileName })
    }

    @Test
    fun `books come by title`() {
        val books = booksOf(listOf(song("Audiobooks/b.m4b", title = "Quiet Orchard"), song("Audiobooks/a.m4b", title = "Lantern Keeper")), BookMarks())
        assertEquals(listOf("Lantern Keeper", "Quiet Orchard"), books.map { it.title })
    }

    // Chapters and places

    @Test
    fun `a folder book's chapters are its parts, titled by tag or by file name`() {
        val parts = listOf(
            song("Audiobooks/Folder Book/01 The Wolf.mp3", album = "Folder Book", durationMs = 60_000),
            song("Audiobooks/Folder Book/02.mp3", album = "Folder Book", durationMs = 30_000),
            song("Audiobooks/Folder Book/03.mp3", title = "Part Three", album = "Folder Book", durationMs = 45_000),
        )
        val book = booksOf(parts, BookMarks()).single()
        assertEquals(
            listOf(Chapter(0, 60_000, "The Wolf"), Chapter(60_000, 90_000, null), Chapter(90_000, 135_000, "Part Three")),
            partChapters(book),
        )
    }

    @Test
    fun `a place reads in the book's own time, within it`() {
        val parts = listOf("1", "2").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 60_000) }
        val book = booksOf(parts, BookMarks()).single()
        assertEquals(75_000L, bookMs(book, BookPlace(1, 15_000)))
        assertEquals(0L, bookMs(book, BookPlace(0, -5)))
        assertEquals(120_000L, bookMs(book, BookPlace(1, 90_000)))
        // A part index past the parts is the last part.
        assertEquals(70_000L, bookMs(book, BookPlace(7, 10_000)))
    }

    @Test
    fun `the chapter playing is the last one started`() {
        val chapters = listOf(Chapter(0, 4_000, "One"), Chapter(4_000, 8_000, "Two"), Chapter(8_000, 12_000, "Three"))
        assertEquals(0, chapterAt(chapters, 0))
        assertEquals(0, chapterAt(chapters, 3_999))
        assertEquals(1, chapterAt(chapters, 4_000))
        assertEquals(2, chapterAt(chapters, 50_000))
        assertEquals(-1, chapterAt(emptyList(), 1_000))
        assertEquals(-1, chapterAt(listOf(Chapter(2_000, 4_000, "Late")), 1_000))
    }

    @Test
    fun `time left is at the book's own speed and whole minutes round up`() {
        assertEquals(3_600_000L, leftMs(7_200_000, 3_600_000, 1f))
        assertEquals(2_400_000L, leftMs(7_200_000, 3_600_000, 1.5f))
        assertEquals(0L, leftMs(7_200_000, 9_000_000, 1f))
        assertEquals(2 to 10, hoursAndMinutes(7_800_000))
        assertEquals(0 to 1, hoursAndMinutes(30_000))
        assertEquals(0 to 0, hoursAndMinutes(0))
        assertEquals(9 to 4, hoursAndMinutes(32_640_000))
    }
}
