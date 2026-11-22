package app.lyrify.books

import app.lyrify.library.Library
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.FakeExtrasPort
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.SkipAmounts
import app.lyrify.player.SleepTimer
import app.lyrify.player.SourceKind
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import app.lyrify.stats.PlayEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book mode: its seek jumps, the book playing, the place kept as it plays, and plays never logged. Made-up names only. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookPlaybackTest {
    private val parts = listOf("1", "2", "3").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 600_000) }
    private val music = song("Music/Harbour Glass/01 Paper Lantern.mp3", album = "Harbour Glass")
    private val library = Library.of(parts + music, Settings())
    private val book = library.books.single()

    private fun bookQueue(start: Int = 0): QueueBook = QueueBook().play(parts.map { it.ref }, start, bookSource(book), now = 1).book
    private fun musicQueue(): QueueBook = QueueBook().play(listOf(music.ref), 0, QueueSource(SourceKind.ALBUM, "album", "Harbour Glass"), now = 1).book

    @Test
    fun `a book's queue is book mode, with 30 s jumps whatever the music's Skip amounts`() {
        val playback = PlaybackSettings(skipBackSeconds = 5, skipForwardSeconds = 10)
        assertTrue(inBook(bookQueue().current))
        assertFalse(inBook(musicQueue().current))
        assertFalse(inBook(null))
        assertEquals(SkipAmounts(30_000, 30_000), skipsFor(bookQueue().current, playback))
        assertEquals(SkipAmounts(5_000, 10_000), skipsFor(musicQueue().current, playback))
        assertEquals(QueueSource(SourceKind.BOOK, book.id, "Folder Book"), bookSource(book))
    }

    @Test
    fun `the book playing is the queue's book with the part playing, never music or a song added to it`() {
        val queue = bookQueue(start = 1).current!!
        assertEquals(PlayingBook(book, 1), playingBook(queue, library))
        assertEquals(PlayingBook(book, 2), playingBook(queue, library, uid = queue.items[2].uid))
        assertNull(playingBook(musicQueue().current, library))
        assertNull(playingBook(queue, null))
        val added = QueueBook().play(parts.map { it.ref }, 0, bookSource(book), now = 1).book.playNext(listOf(music.ref), bookSource(book), now = 2)!!.book.current!!
        assertNull(playingBook(added, library, uid = added.items[1].uid))
        // A book gone from the library (its files marked Not a book) plays as music.
        assertNull(playingBook(queue, Library.of(parts, Settings(), BookMarks(notFolders = setOf("Audiobooks/Folder Book")))))
    }

    @Test
    fun `a book's queue plays as a book while the library loads, and once it is read a song added to it does not`() {
        val queue = bookQueue().current!!
        assertTrue(playsAsBook(queue, null, queue.items[0].uid))
        assertTrue(playsAsBook(queue, library, queue.items[0].uid))
        val added = QueueBook().play(parts.map { it.ref }, 0, bookSource(book), now = 1).book.playNext(listOf(music.ref), bookSource(book), now = 2)!!.book.current!!
        assertTrue(playsAsBook(added, null, added.items[1].uid))
        assertFalse(playsAsBook(added, library, added.items[1].uid))
        val songs = musicQueue().current!!
        assertFalse(playsAsBook(songs, null, songs.items[0].uid))
        assertFalse(playsAsBook(songs, library, songs.items[0].uid))
    }

    @Test
    fun `an m4b another app opens plays as its book when it is the library's, a copy as a plain file that keeps nothing`() = runTest {
        val orchard = song("Audiobooks/Quiet Orchard.m4b", durationMs = 900_000)
        val withOrchard = Library.of(parts + music + orchard, Settings())
        // MediaStore's own address opens the library's file; any other (Files) a copy in KJB's cache (player/OpenedFiles.kt).
        val copy = SongRef("/data/user/0/app.lyrify/cache/opened/1800000000000/Quiet Orchard.m4b")
        val opened = QueueSource(SourceKind.OPENED, "opened", "")
        assertEquals(orchard.path, withOrchard.bookOf(orchard.path)?.id)
        // The library's file plays as its book (BK1, BookStartTest), as the Books tab plays it.
        val own = QueueBook().let { it.asBook(it.play(listOf(orchard.ref), 0, opened, now = 1), withOrchard, BookState(), now = 1) }.book.current!!
        assertTrue(inBook(own))
        assertEquals(PlayingBook(withOrchard.bookOf(orchard.path)!!, 0), playingBook(own, withOrchard))
        // A copy is in no library: a plain file.
        val queue = QueueBook().let { it.asBook(it.play(listOf(copy), 0, opened, now = 1), withOrchard, BookState(), now = 1) }.book.current!!
        assertFalse(inBook(queue))
        assertNull(playingBook(queue, withOrchard))
        assertFalse(playsAsBook(queue, null, queue.items[0].uid))
        assertFalse(playsAsBook(queue, withOrchard, queue.items[0].uid))
        assertNull(withOrchard.bookOf(copy.path))
        // The copy played, paused, stopped and played to its end: books.json gets nothing.
        val run = driving()
        run.playing = playingBook(queue, withOrchard)
        run.driver.songStarted(uid = 1, lap = false, natural = false)
        run.driver.playingChanged(true)
        run.port.positionMs = 40_000
        advanceTimeBy(30_000)
        run.driver.seeked(60_000)
        run.driver.playingChanged(false)
        run.driver.ended()
        run.driver.stopped()
        runCurrent()
        assertTrue(run.store.state.value.books.isEmpty())
    }

    @Test
    fun `End of chapter stops at the end of the chapter playing, in a file with chapters only`() {
        val chapters = listOf(Chapter(0, 4_000, "One"), Chapter(4_000, 8_000, "Two"), Chapter(8_000, 12_000, "Three"))
        assertEquals(SleepTimer.ByChapter(8_000, listOf(4_000, 8_000, 12_000)), sleepToChapterEnd(chapters, 5_000))
        assertEquals(4_000L, sleepToChapterEnd(chapters, 0)?.endMs)
        assertNull(sleepToChapterEnd(emptyList(), 5_000))
    }

    @Test
    fun `a play of a book's part is never logged, music plays are`() {
        val passed = mutableListOf<PlayEvent>()
        val filter = BookPlayFilter({ passed += it }) { path -> library.bookOf(path) != null }
        val part = parts[0].ref
        filter.onEvent(PlayEvent.Started(1, part, 10, 600_000))
        filter.onEvent(PlayEvent.Position(1, part, 20, 5_000, playing = true))
        filter.onEvent(PlayEvent.Seeked(1, part, 30, 5_000, 60_000))
        filter.onEvent(PlayEvent.Ended(1, part, 40, 61_000))
        filter.onEvent(PlayEvent.Started(2, music.ref, 50, 200_000))
        filter.onEvent(PlayEvent.Ended(2, music.ref, 60, 200_000))
        assertEquals(listOf(2L, 2L), passed.map { it.playId })
    }

    @Test
    fun `while the library is read a play is a book's by its path or its book's queue, once read by the library`() {
        val orchard = song("Download/Quiet Orchard.m4b", durationMs = 900_000)
        val tagged = song("Music/Night Readings/01 Lantern Keeper.mp3", album = "Night Readings", genre = "Audiobook", durationMs = 900_000)
        val withBooks = Library.of(parts + music + orchard + tagged, Settings())
        val inBookQueue = QueueBook().play(listOf(tagged.ref), 0, bookSource(withBooks.bookOf(tagged.path)!!), now = 1).book.current
        val inMusicQueue = musicQueue().current
        // Not read yet (a cold start): an .m4b or a file in an Audiobooks folder by its path, a book's queue by its source.
        assertTrue(playIsBook(orchard.path, inMusicQueue, null))
        assertTrue(playIsBook(parts[0].path, inMusicQueue, null))
        assertTrue(playIsBook(tagged.path, inBookQueue, null))
        assertFalse(playIsBook(music.path, inMusicQueue, null))
        // Read: the library says, whatever the queue.
        assertTrue(playIsBook(tagged.path, inMusicQueue, withBooks))
        assertTrue(playIsBook(orchard.path, inMusicQueue, withBooks))
        assertFalse(playIsBook(music.path, inBookQueue, withBooks))
        assertFalse(playIsBook(music.path, inMusicQueue, withBooks))
        // A file the library does not have goes by its path, as the lyrics judge it: a cache copy of an .m4b is a book's.
        assertTrue(playIsBook("/data/user/0/app.lyrify/cache/opened/1800000000000/0/Quiet Orchard.m4b", inMusicQueue, withBooks))
        // The play counter and Friends sharing (Huddle) wired to it in the service.
        val service = java.io.File("src/main/java/app/lyrify/player/PlaybackService.kt").readText()
        assertTrue(service.contains("BookPlayFilter({ event -> Lyrify.playEvents(this).onEvent(event); shared.onEvent(event) }) { path -> playIsBook(path, core.book.value.current, library.value) }"))
    }

    // The place, kept as the book plays

    private class Run(val port: FakeExtrasPort, val store: BookStore, val driver: BookDriver, var playing: PlayingBook?)

    private fun TestScope.driving(): Run {
        val store = BookStore(object : BookStorage {
            override suspend fun load(): BookState = BookState()
            override suspend fun save(state: BookState) = Unit
        }, TestScope(StandardTestDispatcher(testScheduler)))
        val port = FakeExtrasPort().apply { positionMs = 0 }
        lateinit var run: Run
        val driver = BookDriver(port, { run.playing }, store) { testScheduler.currentTime }
        run = Run(port, store, driver, PlayingBook(book, 0))
        backgroundScope.launch { driver.run() }
        runCurrent()
        return run
    }

    @Test
    fun `while a book plays its place is kept every 15 s, and at once on a pause`() = runTest {
        val run = driving()
        run.driver.playingChanged(true)
        run.port.positionMs = 14_000
        advanceTimeBy(14_000)
        runCurrent()
        assertNull(run.store.state.value.entryOf(book))
        run.port.positionMs = 15_000
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(BookPlace(0, 15_000), run.store.state.value.placeOf(book))
        run.port.positionMs = 21_000
        run.driver.playingChanged(false)
        runCurrent()
        assertEquals(BookPlace(0, 21_000), run.store.state.value.placeOf(book))
        assertEquals(testScheduler.currentTime, run.store.state.value.entryOf(book)?.lastPlayedAt)
        // Paused: no more saves.
        run.port.positionMs = 99_000
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(21_000L, run.store.state.value.placeOf(book).positionMs)
    }

    @Test
    fun `the next part starting by itself is kept, a jump waits for the next save, and the stop keeps it`() = runTest {
        val run = driving()
        run.playing = PlayingBook(book, 1)
        run.port.positionMs = 0
        run.driver.songStarted(uid = 2, lap = false, natural = true)
        runCurrent()
        assertEquals(BookPlace(1, 0), run.store.state.value.placeOf(book))
        run.playing = PlayingBook(book, 2)
        run.driver.songStarted(uid = 3, lap = false, natural = false)
        runCurrent()
        assertEquals(BookPlace(1, 0), run.store.state.value.placeOf(book))
        run.port.positionMs = 33_000
        run.driver.stopped()
        runCurrent()
        assertEquals(BookPlace(2, 33_000), run.store.state.value.placeOf(book))
    }

    @Test
    fun `the book's end makes it finished, back at its start, and music keeps nothing`() = runTest {
        val run = driving()
        run.playing = PlayingBook(book, 2)
        run.port.positionMs = 500_000
        run.driver.playingChanged(false)
        run.playing = PlayingBook(book, 0)
        run.port.positionMs = 0
        run.driver.ended()
        run.driver.playingChanged(false)
        runCurrent()
        assertTrue(run.store.state.value.entryOf(book)!!.finished)
        assertEquals(BookPlace(), run.store.state.value.placeOf(book))

        val other = driving()
        other.playing = null
        other.driver.playingChanged(true)
        other.driver.playingChanged(false)
        other.driver.ended()
        other.driver.stopped()
        runCurrent()
        assertTrue(other.store.state.value.books.isEmpty())
    }

    @Test
    fun `music started while a book plays keeps the book where it was left, to the second`() = runTest {
        val run = driving()
        run.driver.playingChanged(true)
        run.port.positionMs = 37_000
        advanceTimeBy(7_100)
        runCurrent()
        // A tap on a song: the player is in the music already when it says so.
        run.playing = null
        run.port.positionMs = 0
        run.driver.songStarted(uid = 9, lap = false, natural = false)
        runCurrent()
        assertEquals(BookPlace(0, 37_000), run.store.state.value.placeOf(book))
    }

    @Test
    fun `another book started keeps the first where it was left, and the new one waits for its own save`() = runTest {
        val other = booksOf(listOf(song("Audiobooks/Quiet Orchard.m4b", durationMs = 900_000)), BookMarks()).single()
        val run = driving()
        run.playing = PlayingBook(book, 1)
        run.driver.playingChanged(true)
        run.port.positionMs = 52_000
        advanceTimeBy(3_100)
        runCurrent()
        run.playing = PlayingBook(other, 0)
        run.port.positionMs = 0
        run.driver.songStarted(uid = 7, lap = false, natural = false)
        runCurrent()
        assertEquals(BookPlace(1, 52_000), run.store.state.value.placeOf(book))
        assertNull(run.store.state.value.entryOf(other))
    }

    @Test
    fun `a part played to its end into a song added after it keeps the book at its next part, or finished after the last`() = runTest {
        val run = driving()
        run.playing = PlayingBook(book, 1)
        run.driver.playingChanged(true)
        run.port.positionMs = 599_000
        advanceTimeBy(1_100)
        runCurrent()
        run.playing = null
        run.port.positionMs = 0
        run.driver.songStarted(uid = 9, lap = false, natural = true)
        runCurrent()
        assertEquals(BookPlace(2, 0), run.store.state.value.placeOf(book))
        assertFalse(run.store.state.value.entryOf(book)!!.finished)

        val last = driving()
        last.playing = PlayingBook(book, 2)
        last.driver.playingChanged(true)
        last.port.positionMs = 599_000
        advanceTimeBy(1_100)
        runCurrent()
        last.playing = null
        last.driver.songStarted(uid = 9, lap = false, natural = true)
        runCurrent()
        assertTrue(last.store.state.value.entryOf(book)!!.finished)
        assertEquals(BookPlace(), last.store.state.value.placeOf(book))
    }

    @Test
    fun `a seek is kept at once, and music started right after it keeps the place it jumped to`() = runTest {
        val run = driving()
        run.driver.playingChanged(true)
        run.port.positionMs = 10_000
        advanceTimeBy(1_100)
        runCurrent()
        run.port.positionMs = 70_000
        run.driver.seeked(70_000)
        runCurrent()
        assertEquals(BookPlace(0, 70_000), run.store.state.value.placeOf(book))
        // Back 30 s, then a song at once, before the next look.
        run.port.positionMs = 40_000
        run.driver.seeked(40_000)
        run.playing = null
        run.port.positionMs = 0
        run.driver.songStarted(uid = 9, lap = false, natural = false)
        runCurrent()
        assertEquals(BookPlace(0, 40_000), run.store.state.value.placeOf(book))
    }

    @Test
    fun `a song of the queue that is not the book's never moves the book's place`() = runTest {
        val run = driving()
        run.port.positionMs = 42_000
        run.driver.playingChanged(false)
        runCurrent()
        run.playing = null
        run.port.positionMs = 7_000
        run.driver.playingChanged(false)
        runCurrent()
        assertEquals(BookPlace(0, 42_000), run.store.state.value.placeOf(book))
        assertEquals(SongRef(parts[0].path, null), parts[0].ref)
    }
}
