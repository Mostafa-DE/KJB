package app.lyrify.books

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
