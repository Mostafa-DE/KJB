package app.lyrify.books

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.Openable
import app.lyrify.player.PlaybackPlace
import app.lyrify.player.PlayerCore
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.QueueStorage
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.Switched
import app.lyrify.player.openedSong
import app.lyrify.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A book started from anywhere plays as a book (decided 2026-11-22): a list's tap, a queue's song, Resume or Play from
 * start on a book's file plays the book's own queue, as its card on the Books tab does. Made-up names only.
 */
class BookStartTest {
    private val parts = listOf("1", "2", "3").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 600_000) }
    private val orchard = song("Download/Quiet Orchard.m4b", durationMs = 900_000)
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", album = "Harbour Glass")
    private val tin = song("Music/Harbour Glass/02 Tin Lighthouse.mp3", album = "Harbour Glass")
    private val library = Library.of(parts + orchard + lantern + tin, Settings())
    private val folderBook = library.bookOf(parts[0].path)!!
    private val oneFile = library.bookOf(orchard.path)!!

    /** Played before: the folder book in its second part, 4 min in; the one-file book 7 min in. */
    private val state = BookState().withPlace(folderBook, BookPlace(1, 240_000), now = 5).withPlace(oneFile, BookPlace(0, 420_000), now = 6)

    private val album = QueueSource(SourceKind.ALBUM, "album", "Harbour Glass")
    private val opened = QueueSource(SourceKind.OPENED, "opened", "")
    private val folder = QueueSource(SourceKind.FOLDER, "Audiobooks/Folder Book", "Folder Book")
    private val search = QueueSource(SourceKind.SEARCH, "orchard", "orchard")
    private val playlist = QueueSource(SourceKind.PLAYLIST, "p1", "Road Trip")

    /** Music playing: the album's first song. */
    private val musicPlaying: QueueBook = QueueBook().play(listOf(lantern.ref, tin.ref), 0, album, now = 1).book

    /** [change] of this book as the app makes it: the book's own queue when it starts a book's file. */
    private fun QueueBook.started(change: (QueueBook) -> Switched): QueueBook = asBook(change(this), library, state, now = 10).book

    @Test
    fun `a one-file book started from an opened file, search or a playlist plays its own queue from its place`() {
        listOf(opened to listOf(orchard.ref), search to listOf(lantern.ref, orchard.ref, tin.ref), playlist to listOf(tin.ref, orchard.ref)).forEach { (source, songs) ->
            val start = songs.indexOf(orchard.ref)
            val queue = musicPlaying.started { it.play(songs, start, source, now = 10) }.current!!
            assertEquals(source.kind.name, bookSource(oneFile), queue.source)
            assertTrue(inBook(queue))
            assertEquals(listOf(orchard.path), queue.items.map { it.song.path })
            assertEquals(0, queue.index)
            assertEquals(420_000L, queue.positionMs)
        }
    }

    @Test
    fun `a folder book's part that holds its place, started from its folder or a playlist, plays the book from that place`() {
        listOf(folder to parts.map { it.ref }, playlist to listOf(lantern.ref, parts[1].ref)).forEach { (source, songs) ->
            val start = songs.indexOf(parts[1].ref)
            val queue = musicPlaying.started { it.play(songs, start, source, now = 10) }.current!!
            assertEquals(source.kind.name, bookSource(folderBook), queue.source)
            assertEquals(parts.map { it.path }, queue.items.map { it.song.path })
            assertEquals(1, queue.index)
            assertEquals(240_000L, queue.positionMs)
        }
    }

    @Test
    fun `another part of a folder book starts the book at the start of that part`() {
        listOf(0, 2).forEach { part ->
            val queue = musicPlaying.started { it.play(parts.map { song -> song.ref }, part, folder, now = 10) }.current!!
            assertEquals(bookSource(folderBook), queue.source)
            assertEquals(part, queue.index)
            assertEquals(0L, queue.positionMs)
        }
        assertEquals(BookStart(folderBook, BookPlace(2, 0)), bookStart(parts[2].path, library, state))
        assertEquals(BookStart(folderBook, BookPlace(1, 240_000)), bookStart(parts[1].path, library, state))
        assertEquals(BookStart(oneFile, BookPlace(0, 420_000)), bookStart(orchard.path, library, state))
        // Never played: from its start.
        assertEquals(BookStart(oneFile, BookPlace()), bookStart(orchard.path, library, BookState()))
    }

    @Test
    fun `a book a file manager opens by its own address plays as the book when its name and size are the library file's`() {
        val address = "content://com.example.files.provider/root/Download/Quiet%20Orchard.m4b"
        val all = parts + orchard + lantern + tin
        val found = openedSong(address, all) { Openable(orchard.fileName, orchard.sizeBytes) }!!
        // The library's own file, never a copy: its book, at its place.
        val queue = musicPlaying.started { it.play(listOf(found.ref), 0, opened, now = 10) }.current!!
        assertEquals(bookSource(oneFile), queue.source)
        assertEquals(listOf(orchard.path), queue.items.map { it.song.path })
        assertEquals(420_000L, queue.positionMs)
        // Music found the same way plays as an opened file, the library's own.
        val song = openedSong(address, all) { Openable(tin.fileName, tin.sizeBytes) }!!
        val music = musicPlaying.started { it.play(listOf(song.ref), 0, opened, now = 10) }.current!!
        assertEquals(opened, music.source)
        assertEquals(listOf(tin.path), music.items.map { it.song.path })
    }

    @Test
    fun `music, a copy of an opened file, a book the library has not read yet and a file marked Not a book play as before`() {
        val copy = SongRef("/data/user/0/app.lyrify/cache/opened/1800000000000/0/Quiet Orchard.m4b")
        val notABook = Library.of(parts + orchard + lantern + tin, Settings(), BookMarks(notSongs = setOf(orchard.path)))
        val cases = listOf(
            Triple(listOf(lantern.ref, tin.ref), album, library),
            Triple(listOf(copy), opened, library),
            Triple(listOf(orchard.ref), opened, null),
            Triple(listOf(orchard.ref), opened, notABook),
        )
        cases.forEach { (songs, source, known) ->
            val plain = musicPlaying.play(songs, songs.lastIndex, source, now = 10)
            assertSame(plain, musicPlaying.asBook(plain, known, state, now = 10))
            assertFalse(inBook(plain.book.current))
        }
        assertNull(bookStart(lantern.path, library, state))
        assertNull(bookStart(copy.path, library, state))
        assertNull(bookStart(orchard.path, null, state))
    }

    @Test
    fun `a book's file tapped in a queue, or a queue resumed or played from the start on one, plays the book`() {
        // An "Opened file" queue from before, music first, then the one-file book, left in Recent.
        val mixed = QueueBook().play(listOf(lantern.ref, orchard.ref), 0, opened, now = 1).book
        val mixedId = mixed.currentId!!
        val tapped = mixed.started { it.jump(mixedId, 1, now = 10) }.current!!
        assertEquals(bookSource(oneFile), tapped.source)
        assertEquals(420_000L, tapped.positionMs)

        val left = mixed.jump(mixedId, 1, now = 2).book.play(listOf(tin.ref), 0, album, now = 3).book
        val resumed = left.started { it.resume(mixedId, now = 10) }.current!!
        assertEquals(bookSource(oneFile), resumed.source)
        assertEquals(420_000L, resumed.positionMs)

        val bookFirst = QueueBook().play(listOf(parts[2].ref, lantern.ref), 1, playlist, now = 1).book
        val fromStart = bookFirst.started { it.playFromStart(bookFirst.currentId!!, now = 10) }.current!!
        assertEquals(bookSource(folderBook), fromStart.source)
        assertEquals(2, fromStart.index)
        assertEquals(0L, fromStart.positionMs)

        // A music song of that queue stays music.
        val music = mixed.started { it.jump(mixedId, 0, now = 10) }.current!!
        assertEquals(mixedId, music.id)
        assertFalse(inBook(music))
    }

    @Test
    fun `inside the book's own queue a chapter's part is a plain jump`() {
        val playing = QueueBook().play(parts.map { it.ref }, 1, bookSource(folderBook), now = 1).book.place(1, 100_000)
        val id = playing.currentId!!
        val jumped = playing.started { it.jump(id, 2, now = 10) }
        assertEquals(id, jumped.currentId)
        assertEquals(2, jumped.current!!.index)
        assertEquals(0L, jumped.current!!.positionMs)
        assertEquals(playing.jumps + 1, jumped.jumps)
    }

    @Test
    fun `the book's own queue playing goes on where it is for the part playing, and goes to another part tapped`() {
        val playing = QueueBook().play(parts.map { it.ref }, 1, bookSource(folderBook), now = 1).book.place(1, 100_000)
        val id = playing.currentId!!
        // The part playing, tapped in its folder: it plays on, nothing moved.
        assertSame(playing, playing.started { it.play(parts.map { song -> song.ref }, 1, folder, now = 10) })
        // Another part: the same queue goes there, from its start.
        val other = playing.started { it.play(parts.map { song -> song.ref }, 2, folder, now = 10) }
        assertEquals(id, other.currentId)
        assertEquals(2, other.current!!.index)
        assertEquals(0L, other.current!!.positionMs)
        assertEquals(playing.jumps + 1, other.jumps)
    }

    @Test
    fun `nothing changed is nothing instead, even on an old queue that holds a book's file`() {
        val old = QueueBook().play(listOf(orchard.ref), 0, opened, now = 1).book
        val nothing = old.play(emptyList(), 0, album, now = 10)
        assertSame(nothing, old.asBook(nothing, library, state, now = 10))
        val again = old.resume(old.currentId!!, now = 10)
        assertSame(again, old.asBook(again, library, state, now = 10))
    }

    @Test
    fun `the music left for the book stays in Recent, and the book plays as a book`() {
        val started = musicPlaying.started { it.play(listOf(orchard.ref), 0, opened, now = 10) }
        assertEquals(musicPlaying.currentId, started.recent.first())
        assertEquals(PlayingBook(oneFile, 0), playingBook(started.current, library))
    }

    // A paused "Opened file" queue from before (3.1.1): it goes on as its book

    /** An "Opened file" queue as KJB 3.1.0 left one: [refs], at song [index], [positionMs] in, last played at [at]. */
    private fun openedLeft(refs: List<SongRef>, index: Int, positionMs: Long, at: Long): QueueBook =
        QueueBook().play(refs, index, opened, now = 1).book.place(index, positionMs, at = at)

    private val noCopy: (SongRef) -> Song? = { null }

    /** This book of queues once [openedAsBook] made its Opened file queue its book's; failing when nothing changed. */
    private fun QueueBook.goesOnAsBook(state: BookState, copyOf: (SongRef) -> Song?): QueueBook {
        val switched = openedAsBook(library, state, copyOf, now = 300)
        assertNotNull("nothing changed", switched)
        return switched!!.book
    }

    @Test
    fun `a paused Opened file queue on a library book becomes the book's queue, where it was listened to last`() {
        // Listened to as an opened file after the book's last place in books.json (lastPlayedAt 6): the queue's own place.
        val left = openedLeft(listOf(orchard.ref), 0, 600_000, at = 100)
        val book = left.goesOnAsBook(state, noCopy)
        val queue = book.current!!
        assertEquals(bookSource(oneFile), queue.source)
        assertEquals(listOf(orchard.path), queue.items.map { it.song.path })
        assertEquals(600_000L, queue.positionMs)
        // The Opened file queue is kept in Recent, as any queue left.
        assertEquals(left.currentId, book.recent.first())
        // books.json newer than the queue (played from the Books tab since): the book's own place.
        val later = state.withPlace(oneFile, BookPlace(0, 500_000), now = 200)
        assertEquals(500_000L, left.goesOnAsBook(later, noCopy).current!!.positionMs)
        // Never played as a book: the queue's place.
        assertEquals(600_000L, left.goesOnAsBook(BookState(), noCopy).current!!.positionMs)
    }

    @Test
    fun `a folder book's part goes on at that part, and a cache copy found by name and size goes on as the library's book`() {
        val folderLeft = openedLeft(listOf(lantern.ref, parts[2].ref), 1, 30_000, at = 100)
        val inParts = folderLeft.goesOnAsBook(state, noCopy).current!!
        assertEquals(bookSource(folderBook), inParts.source)
        assertEquals(2, inParts.index)
        assertEquals(30_000L, inParts.positionMs)
        // books.json newer: where the book was left there, its own part (the second), not the part the queue was on.
        val later = state.withPlace(folderBook, BookPlace(1, 90_000), now = 200)
        val kept = folderLeft.goesOnAsBook(later, noCopy).current!!
        assertEquals(1, kept.index)
        assertEquals(90_000L, kept.positionMs)
        val copy = SongRef("/data/user/0/app.lyrify/cache/opened/1800000000000/0/Quiet Orchard.m4b")
        val copyLeft = openedLeft(listOf(copy), 0, 700_000, at = 100)
        val found = copyLeft.goesOnAsBook(state, { ref -> orchard.takeIf { ref == copy } }).current!!
        assertEquals(bookSource(oneFile), found.source)
        assertEquals(listOf(orchard.path), found.items.map { it.song.path })
        assertEquals(700_000L, found.positionMs)
    }

    @Test
    fun `music, an unmatched copy, a music queue, a book's own queue and a library not read change nothing`() {
        val copy = SongRef("/data/user/0/app.lyrify/cache/opened/1800000000000/0/Quiet Orchard.m4b")
        assertNull(openedLeft(listOf(tin.ref, orchard.ref), 0, 5_000, at = 100).openedAsBook(library, state, noCopy, now = 300))
        assertNull(openedLeft(listOf(copy), 0, 5_000, at = 100).openedAsBook(library, state, noCopy, now = 300))
        // A music queue that played on into a book's file: out of scope, as it was.
        val album = QueueBook().play(listOf(tin.ref, orchard.ref), 1, playlist, now = 1).book
        assertNull(album.openedAsBook(library, state, noCopy, now = 300))
        val own = QueueBook().play(listOf(orchard.ref), 0, bookSource(oneFile), now = 1).book
        assertNull(own.openedAsBook(library, state, noCopy, now = 300))
        assertNull(openedLeft(listOf(orchard.ref), 0, 5_000, at = 100).openedAsBook(null, state, noCopy, now = 300))
        assertNull(QueueBook().openedAsBook(library, state, noCopy, now = 300))
    }

    @Test
    fun `an Opened file queue on a book resumed from the queues goes on where it was listened to last`() {
        // Listened to as an opened file at 100, after the book's place in books.json (6), then left for music at 150.
        val left = openedLeft(listOf(orchard.ref), 0, 600_000, at = 100)
        val openedId = left.currentId!!
        val music = left.play(listOf(tin.ref), 0, album, now = 150).book
        val resumed = music.resumeAsBook(openedId, library, state, noCopy, now = 300).book.current!!
        assertEquals(bookSource(oneFile), resumed.source)
        assertEquals(600_000L, resumed.positionMs)
        // Played from the Books tab since (200): the book's own place.
        val later = state.withPlace(oneFile, BookPlace(0, 500_000), now = 200)
        assertEquals(500_000L, music.resumeAsBook(openedId, library, later, noCopy, now = 300).book.current!!.positionMs)
        // Any other queue resumes as Resume does, a book's file in it starting its book (asBook).
        val musicId = music.currentId!!
        val back = music.resumeAsBook(openedId, library, state, noCopy, now = 300).book
        assertEquals(musicId, back.resumeAsBook(musicId, library, state, noCopy, now = 400).book.currentId)
    }

    @Test
    fun `Resume of an Opened file queue goes to its place though the book's queue stands on that part elsewhere, and a copy found goes on as the book`() {
        val left = openedLeft(listOf(orchard.ref), 0, 600_000, at = 100)
        val openedId = left.currentId!!
        // The book started since at its old place in books.json, not played yet.
        val onIt = left.play(listOf(orchard.ref), 0, bookSource(oneFile), now = 150).book.place(0, 100_000)
        val resumed = onIt.resumeAsBook(openedId, library, state, noCopy, now = 300).book
        assertEquals(bookSource(oneFile), resumed.current!!.source)
        assertEquals(600_000L, resumed.current!!.positionMs)
        assertEquals(onIt.jumps + 1, resumed.jumps)
        // A cache copy left in Recent, matched to the library's book.
        val copy = SongRef("/data/user/0/app.lyrify/cache/opened/1800000000000/0/Quiet Orchard.m4b")
        val copyLeft = openedLeft(listOf(copy), 0, 700_000, at = 100)
        val copyId = copyLeft.currentId!!
        val music = copyLeft.play(listOf(tin.ref), 0, album, now = 150).book
        val found = music.resumeAsBook(copyId, library, state, { ref -> orchard.takeIf { ref == copy } }, now = 300).book.current!!
        assertEquals(bookSource(oneFile), found.source)
        assertEquals(700_000L, found.positionMs)
    }

    /** Queues as a restart reads them: [book] as stored. */
    private class Stored(private val book: QueueBook) : QueueStorage {
        override suspend fun load(): Pair<QueueBook, Repeat> = book to Repeat.OFF

        override suspend fun saveBook(book: QueueBook) = Unit

        override suspend fun savePlace(place: PlaybackPlace) = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the service turns a paused Opened file queue into its book once, never while asked to play, and looks up only a copy`() = runTest {
        val left = openedLeft(listOf(orchard.ref), 0, 600_000, at = 100)
        val core = PlayerCore(Stored(left), backgroundScope, wall = { 300 })
        runCurrent()
        val asked = mutableListOf<String>()
        val noneFound: suspend (String) -> Song? = { path -> asked += path; null }
        // Asked to play (a headset, Play pressed meanwhile): as it is, until the next pause.
        core.openedIntoBook(library, { state }, noneFound) { false }
        assertEquals(left.currentId, core.book.value.currentId)
        core.openedIntoBook(library, { state }, noneFound) { true }
        val queue = core.book.value.current!!
        assertEquals(bookSource(oneFile), queue.source)
        assertEquals(600_000L, queue.positionMs)
        // Once: the book's own queue changes nothing more.
        val after = core.book.value
        core.openedIntoBook(library, { state }, noneFound) { true }
        assertSame(after, core.book.value)
        // The library's file is never looked up as a copy.
        assertTrue(asked.isEmpty())
        // A copy is, and goes on as the library's book.
        val copy = SongRef("/data/user/0/app.lyrify/cache/opened/1800000000000/0/Quiet Orchard.m4b")
        val copyCore = PlayerCore(Stored(openedLeft(listOf(copy), 0, 700_000, at = 100)), backgroundScope, wall = { 300 })
        runCurrent()
        copyCore.openedIntoBook(library, { state }, { path -> asked += path; orchard.takeIf { path == copy.path } }) { true }
        assertEquals(listOf(copy.path), asked)
        assertEquals(bookSource(oneFile), copyCore.book.value.current!!.source)
        assertEquals(700_000L, copyCore.book.value.current!!.positionMs)
    }

    @Test
    fun `the service turns it before a headset's play gets the queue, and on every pause and library change`() {
        // Unit tests run in the module's folder.
        val service = File("src/main/java/app/lyrify/player/PlaybackService.kt").readText()
        assertTrue(service.contains("core.openedIntoBook(known, books.state::value, { path -> OpenedFiles.libraryCopyOf(this@PlaybackService, path, known.all) }) { !engine.playWhenReady }"))
        val part = File("src/main/java/app/lyrify/books/BookPlayback.kt").readText()
        assertTrue(part.contains("core.openedIntoBook(known, store.state::value, { path -> OpenedFiles.libraryCopyOf(service, path, known.all) }) { !engine.playWhenReady }"))
    }

    @Test
    fun `every list, queue and car start goes through the book check, the Books tab's own start and Shuffle do not`() {
        // Unit tests run in the module's folder.
        val connection = File("src/main/java/app/lyrify/player/PlayerConnection.kt").readText()
        listOf("it.play(songs, start, source, core.now())", "it.playFromStart(queueId, core.now())", "it.jump(queueId, index, core.now())").forEach { change ->
            assertTrue(change, connection.contains("switchAsBook { $change }"))
        }
        assertTrue(connection.contains("core.switch { it.resumeAsBook(queueId, known, books.value, { ref -> copy?.takeIf { ref == item } }, core.now()) }"))
        assertFalse(connection.contains("core.switch { it.play(songs, start, source"))
        assertTrue(connection.contains("core.switch { it.shuffle(songs, source"))
        assertTrue(connection.contains("core.switch { book -> book.play(songs, start, source, core.now())"))
        val car = File("src/main/java/app/lyrify/auto/CarBrowse.kt").readText()
        assertTrue(car.contains("it.asBook(it.play(play.songs, play.start, play.source, core.now()), data.library, books.value, core.now())"))
        assertTrue(car.contains("it.asBook(it.jump(play.queueId, play.index, core.now()), data.library, books.value, core.now())"))
    }
}
