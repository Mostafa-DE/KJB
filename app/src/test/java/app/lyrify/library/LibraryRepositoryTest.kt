package app.lyrify.library

import app.lyrify.books.BookMarks
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortSongs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library kept current: read once, again only when MediaStore says its songs changed, regrouped when the filters change. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", album = "Harbour Glass")
    private val clip = song("Music/Harbour Glass/Intro.mp3", durationMs = 5_000)
    private val bell = song("Music/Night Ferry/Tide Bell.mp3")

    private val source = FakeLibrarySource(SongsRead.Songs(listOf(lantern, clip)))
    private val settings = MutableStateFlow(Settings())
    private val logs = mutableListOf<String>()

    private fun TestScope.repository(): LibraryRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LibraryRepository(source, settings, backgroundScope, io = dispatcher, compute = dispatcher) { logs += it }
    }

    private fun LibraryRepository.library(): Library = (state.value as LibraryState.Ready).library

    @Test
    fun `the library is loading, then read once`() = runTest {
        val repository = repository()
        assertSame(LibraryState.Loading, repository.state.value)

        runCurrent()
        assertEquals(listOf(lantern), repository.library().songs)
        assertEquals(listOf(lantern, clip), repository.library().all)
        assertEquals(1, source.reads)
    }

    @Test
    fun `a burst of changes is read once, after the changes stop`() = runTest {
        val repository = repository()
        runCurrent()
        source.result = SongsRead.Songs(listOf(lantern, clip, bell))
        source.stampNow = "v1:2"

        repeat(5) {
            source.signals.emit(Unit)
            advanceTimeBy(LibraryRepository.DEBOUNCE_MS / 2)
        }
        assertEquals(1, source.reads)

        advanceTimeBy(LibraryRepository.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(2, source.reads)
        assertEquals(listOf(lantern, bell), repository.library().songs)
    }

    @Test
    fun `a change MediaStore's version and generation do not show reads nothing`() = runTest {
        val repository = repository()
        runCurrent()
        val before = repository.library()

        source.signals.emit(Unit)
        advanceTimeBy(LibraryRepository.DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(1, source.reads)
        assertSame(before, repository.library())
    }

    @Test
    fun `without a version from MediaStore, every change is read`() = runTest {
        source.stampNow = null
        repository()
        runCurrent()

        source.signals.emit(Unit)
        advanceTimeBy(LibraryRepository.DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(2, source.reads)
    }

    @Test
    fun `changing the filters regroups the songs without reading them again, other settings change nothing`() = runTest {
        val repository = repository()
        runCurrent()
        val before = repository.library()

        settings.value = Settings(library = LibrarySettings(songSort = SortSongs.ARTIST, showFileNames = true))
        runCurrent()
        assertSame(before, repository.library())

        settings.value = Settings(playback = PlaybackSettings(skipShortClips = false))
        runCurrent()
        assertEquals(listOf(lantern, clip), repository.library().songs)

        settings.value = Settings(library = LibrarySettings(foldersToSkip = listOf("Music/Harbour Glass")))
        runCurrent()
        assertEquals(emptyList<Song>(), repository.library().songs)
        assertEquals(1, source.reads)
    }

    @Test
    fun `a library never read is unreadable until a read works`() = runTest {
        source.result = SongsRead.Unavailable("refused: SecurityException")
        val repository = repository()
        runCurrent()
        assertSame(LibraryState.Unreadable, repository.state.value)

        source.result = SongsRead.Songs(listOf(lantern))
        repository.refresh()
        runCurrent()
        assertEquals(listOf(lantern), repository.library().songs)
    }

    @Test
    fun `a read that fails later keeps the library as it was`() = runTest {
        val repository = repository()
        runCurrent()
        val before = repository.library()

        source.result = SongsRead.Unavailable("refused: SecurityException")
        source.stampNow = "v1:2"
        source.signals.emit(Unit)
        advanceTimeBy(LibraryRepository.DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(2, source.reads)
        assertSame(before, repository.library())
    }

    @Test
    fun `refresh reads again even when MediaStore says nothing changed, as after All files access is granted`() = runTest {
        val repository = repository()
        runCurrent()
        source.result = SongsRead.Songs(listOf(lantern, bell))

        repository.refresh()
        runCurrent()

        assertEquals(2, source.reads)
        assertEquals(listOf(lantern, bell), repository.library().songs)
    }

    @Test
    fun `the log has counts and causes only, never names or paths`() = runTest {
        repository()
        runCurrent()
        source.result = SongsRead.Unavailable("refused: SecurityException")
        source.stampNow = "v1:2"
        source.signals.emit(Unit)
        advanceTimeBy(LibraryRepository.DEBOUNCE_MS + 1)
        runCurrent()

        assertTrue(logs.toString(), logs.any { "2 songs" in it })
        assertTrue(logs.toString(), logs.any { "SecurityException" in it })
        assertFalse(logs.toString(), logs.any { "Lantern" in it || "Harbour" in it || "/storage" in it || "Kit" in it })
    }

    @Test
    fun `the user's book marks regroup the library without reading MediaStore again`() = runTest {
        val parts = listOf("01", "02").map { song("Music/Rips/Lantern Keeper/$it.mp3", album = "Lantern Keeper") }
        source.result = SongsRead.Songs(parts + lantern)
        val marks = MutableStateFlow(BookMarks())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = LibraryRepository(source, settings, backgroundScope, io = dispatcher, compute = dispatcher, marks = marks) { logs += it }
        runCurrent()
        assertTrue(repository.library().books.isEmpty())

        marks.value = BookMarks(folders = setOf("Music/Rips/Lantern Keeper"))
        runCurrent()
        assertEquals(parts, repository.library().books.single().parts)
        assertEquals(listOf(lantern), repository.library().songs)
        assertEquals(1, source.reads)
    }
}
