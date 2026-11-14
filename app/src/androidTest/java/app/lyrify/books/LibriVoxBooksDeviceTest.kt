package app.lyrify.books

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.data.SharedWrites
import app.lyrify.library.Library
import app.lyrify.library.LibraryReader
import app.lyrify.library.Song
import app.lyrify.library.SongsRead
import app.lyrify.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Real public-domain audiobooks on the phone, put there by tools/push-librivox-books.sh in Audiobooks/KJB test/ (which
 * a debug build lists, never changes), in folders with neutral names: Book A (an m4b with one QuickTime chapter and an
 * mp3 without chapters), Book B (25 MP3 parts), Book C (an m4b with QuickTime chapters), Book D (optional: an m4b of
 * 285 chapters), Fixtures (two generated files), and one more m4b in Music/KJB test/Books outside/. Each test is skipped
 * when its files are not there. The log gets counts and times only.
 */
@RunWith(AndroidJUnit4::class)
class LibriVoxBooksDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var library: Library

    @Before
    fun read() {
        val read = LibraryReader(context, Lyrify.writes).read()
        assumeTrue("the library could not be read: grant KJB dev All files access", read is SongsRead.Songs)
        library = Library.of((read as SongsRead.Songs).songs, Settings())
    }

    private fun songsIn(folder: String): List<Song> = library.all.filter { it.folder == folder }

    private fun chapters(book: Book): List<Chapter> {
        val file = book.parts.single()
        val started = System.nanoTime()
        val read = runBlocking { Media3Chapters(context).read(file.path, file.durationMs) }
        Log.i(LOG_TAG, "books device test: ${read?.size} chapters read in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms")
        return checkNotNull(read) { "not read" }
    }

    /** Chapters in time order, inside the file. */
    private fun assertOrdered(chapters: List<Chapter>, durationMs: Long) {
        assertTrue(chapters.zipWithNext().all { (a, b) -> b.startMs >= a.startMs && a.endMs <= b.startMs + 1 })
        assertTrue(chapters.all { it.endMs >= it.startMs && it.startMs <= durationMs })
    }

    @Test
    fun bookAIsAnM4bOfOneChapterAndAnMp3WithNone() {
        val songs = songsIn(BOOKS + "Book A/")
        assumeTrue("Book A is not on the phone", songs.isNotEmpty())
        val books = songs.mapNotNull { library.bookOf(it.path) }.distinct()
        assertEquals(2, books.size)
        assertTrue(books.all { it.kind == BookKind.SINGLE })
        assertTrue(songs.none { library.song(it.path) != null })
        val m4b = books.single { it.id.endsWith(".m4b") }
        assertEquals(1, chapters(m4b).size)
        assertEquals(emptyList<Chapter>(), chapters(books.single { it != m4b }))
    }

    @Test
    fun bookBIsOneFolderBookOf25PartsInOrder() {
        val songs = songsIn(BOOKS + "Book B/")
        assumeTrue("Book B is not on the phone", songs.size == 25)
        val book = library.bookOf(songs.first().path)!!
        assertEquals(BookKind.FOLDER, book.kind)
        assertEquals(songs.map { it.path }.sorted(), book.parts.map { it.path })
        assertEquals(25, partChapters(book).size)
        assertTrue(library.songs.none { it.folder == BOOKS + "Book B/" })
    }

    @Test
    fun bookCHasItsChaptersInOrder() {
        val songs = songsIn(BOOKS + "Book C/")
        assumeTrue("Book C is not on the phone", songs.isNotEmpty())
        val book = library.bookOf(songs.single().path)!!
        val chapters = chapters(book)
        assertTrue(chapters.isNotEmpty())
        assertOrdered(chapters, book.durationMs)
    }

    @Test
    fun bookDsHundredsOfChaptersReadQuickly() {
        val songs = songsIn(BOOKS + "Book D/")
        assumeTrue("Book D is not on the phone", songs.isNotEmpty())
        val book = library.bookOf(songs.single().path)!!
        val started = System.nanoTime()
        val chapters = chapters(book)
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(285, chapters.size)
        assertOrdered(chapters, book.durationMs)
        assertTrue("took $took ms", took < BIG_READ_MS)
    }

    @Test
    fun theFixturesInAudiobooksAreBooksWithTheirChapters() {
        val songs = songsIn(BOOKS + "Fixtures/")
        assumeTrue("the fixtures are not on the phone", songs.size == 2)
        songs.forEach { song ->
            val book = library.bookOf(song.path)!!
            assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three"), chapters(book).map { it.title })
        }
    }

    @Test
    fun anM4bOutsideAudiobooksIsStillABook() {
        val songs = songsIn(SharedWrites.TEST_FOLDER + "Books outside/")
        assumeTrue("the m4b outside Audiobooks is not on the phone", songs.isNotEmpty())
        assertEquals(BookKind.SINGLE, library.bookOf(songs.single().path)?.kind)
        assertNull(library.song(songs.single().path))
    }

    private companion object {
        const val BOOKS: String = SharedWrites.TEST_BOOKS_FOLDER

        /** A 93 MB file of 285 chapters: well under this on any phone that plays it. */
        const val BIG_READ_MS: Long = 10_000
    }
}
