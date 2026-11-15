package app.lyrify.books

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** `books.json` in the app: read once, a change before the read waits for it, every change stored. Made-up names only. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookStoreTest {
    private class MemoryBooks(var stored: BookState = BookState(), val fails: Boolean = false) : BookStorage {
        val saved = mutableListOf<BookState>()

        override suspend fun load(): BookState = if (fails) error("damaged") else stored

        override suspend fun save(state: BookState) {
            saved += state
            stored = state
        }
    }

    private fun TestScope.store(storage: BookStorage): BookStore = BookStore(storage, TestScope(StandardTestDispatcher(testScheduler)))

    @Test
    fun `a change asked for before the read waits for it, and is stored`() = runTest {
        val kept = BookState().markSongs(listOf("/storage/emulated/0/Music/a.mp3"), on = true)
        val storage = MemoryBooks(kept)
        val store = store(storage)
        store.change { it.markFolder("Music/Rips", emptyList(), on = true) }
        runCurrent()

        assertEquals(BookMarks(songs = setOf("/storage/emulated/0/Music/a.mp3"), folders = setOf("Music/Rips")), store.state.value.marks)
        assertEquals(store.state.value, storage.saved.last())
        assertEquals(store.state.value.marks, store.marks.first())
        assertTrue(store.readable)
    }

    @Test
    fun `a file that cannot be read gives no books and is never written over`() = runTest {
        val storage = MemoryBooks(fails = true)
        val store = store(storage)
        runCurrent()
        store.change { it.markSongs(listOf("/storage/emulated/0/Music/a.mp3"), on = true) }
        runCurrent()

        assertTrue(store.ready.value)
        assertFalse(store.readable)
        assertEquals(setOf("/storage/emulated/0/Music/a.mp3"), store.marks.first().songs)
        assertTrue(storage.saved.isEmpty())
    }

    // books.json on disk

    @get:Rule
    val folder = TemporaryFolder()

    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun stop() = io.cancel()

    /** A DataStore whose reads fail as a busy or failing disk does: nothing may be written after such a read. */
    private class FailingRead : DataStore<BookState> {
        var writes = 0
        override val data: Flow<BookState> = flow { throw IOException("read failed") }

        override suspend fun updateData(transform: suspend (t: BookState) -> BookState): BookState {
            writes++
            return transform(BookState())
        }
    }

    @Test
    fun `a read that fails leaves no books in the store and the file is never written over`() = runTest {
        val disk = FailingRead()
        val store = store(BookFiles(disk, StandardTestDispatcher(testScheduler)) {})
        runCurrent()
        store.change { it.markSongs(listOf("/storage/emulated/0/Music/a.mp3"), on = true) }
        runCurrent()

        assertTrue(store.ready.value)
        assertFalse(store.readable)
        assertEquals(0, disk.writes)
    }

    @Test
    fun `a file that is not books is kept aside before the store starts over`() = runBlocking {
        val file = File(folder.root, "books.json")
        file.writeText("[not books]")
        val books = BookFiles(booksStore(file, folder.root, io, clock = { 1234 }) {}, Dispatchers.IO) {}
        assertEquals(BookState(), books.load())
        assertEquals("[not books]", File(folder.root, "books-corrupt-1234.json").readText())
    }

    @Test
    fun `books from a newer KJB are refused and their file left as it is, no copy, nothing written`() = runBlocking {
        val file = File(folder.root, "books.json")
        val newer = """{"schemaVersion": ${BooksJson.SCHEMA_VERSION + 1}, "books": [], "marks": {}}"""
        file.writeText(newer)
        val books = BookFiles(booksStore(file, folder.root, io, clock = { 1234 }) {}, Dispatchers.IO) {}
        try {
            books.load()
            fail("read as books")
        } catch (e: IOException) {
            assertFalse(e is CorruptionException)
        }
        assertEquals(newer, file.readText())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.startsWith("books-corrupt") })
    }

    @Test
    fun `the marks read are capped, and a newer schema is no books`() {
        val many = (1..BookState.MAX_MARKS + 50).joinToString(",") { "\"/storage/emulated/0/Music/$it.mp3\"" }
        val text = """{"schemaVersion": 1, "books": [], "marks": {"songs": [$many], "notSongs": [$many], "folders": [$many], "notFolders": [$many]}}"""
        val read = BooksJson.decode(text)!!
        listOf(read.marks.songs, read.marks.notSongs, read.marks.folders, read.marks.notFolders).forEach { assertEquals(BookState.MAX_MARKS, it.size) }
        assertNull(BooksJson.decode("""{"schemaVersion": ${BooksJson.SCHEMA_VERSION + 1}, "books": []}"""))
    }
}
