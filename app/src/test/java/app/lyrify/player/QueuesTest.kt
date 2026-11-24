package app.lyrify.player

import app.lyrify.library.SongRef
import app.lyrify.library.search.searchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class QueuesTest {
    private fun song(name: String) = SongRef("/storage/emulated/0/Music/$name.mp3", mediaStoreId = name.hashCode().toLong())

    private val album = (1..6).map { song("Harbour Glass $it") }
    private val folder = (1..4).map { song("Rips $it") }
    private val albumSource = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")
    private val playlistSource = QueueSource(SourceKind.PLAYLIST, "playlist:harbour", "Harbour Glass")
    private val folderSource = QueueSource(SourceKind.FOLDER, "folder:Music/Rips", "Rips")
    private val picked = QueueSource(SourceKind.PICKED, "picked", "")

    private fun paths(queue: Queue?) = queue!!.items.map { it.song.path.substringAfterLast('/').removeSuffix(".mp3") }

    private fun currentName(book: QueueBook) = book.current!!.current!!.song.path.substringAfterLast('/').removeSuffix(".mp3")

    /** The album's songs as a playlist, playing at song [start] (an album's own queue starts there, wrapped round). */
    private fun playing(start: Int = 0): QueueBook = QueueBook().play(album, start, playlistSource, now = 1).book

    // Tapping a song

    @Test
    fun `a tap in the list the queue came from jumps inside it, and Play next songs stay`() {
        val book = playing(0).playNext(listOf(folder[0]), picked, now = 2)!!.book
        val tapped = book.play(album, 3, playlistSource, now = 3)
        assertEquals(book.currentId, tapped.book.currentId)
        assertEquals("Harbour Glass 4", currentName(tapped.book))
        assertTrue("Rips 1" in paths(tapped.book.current))
        assertFalse(tapped.toRecent)
        assertEquals(book.jumps + 1, tapped.book.jumps)
        assertEquals(listOf<String>(), tapped.book.recent)
    }

    @Test
    fun `a tap in another list starts a new queue there, and the old one goes to Recent with its place`() {
        val book = playing(2).place(2, 61_000)
        val old = book.currentId!!
        val tapped = book.play(folder, 1, folderSource, now = 5)
        assertTrue(tapped.toRecent)
        assertNotEquals(old, tapped.book.currentId)
        assertEquals("Rips 2", currentName(tapped.book))
        assertEquals(listOf(old), tapped.book.recent)
        assertEquals(2, tapped.book.queue(old)!!.index)
        assertEquals(61_000L, tapped.book.queue(old)!!.positionMs)
        assertEquals(5L, tapped.book.queue(old)!!.updatedAt)
    }

    @Test
    fun `a tap in the list a saved queue came from starts a new queue there, and the saved one keeps its place`() {
        var book = playing(0).place(2, 61_000).play(folder, 0, folderSource, now = 2).book
        val kept = book.recent.single()
        book = book.keep(kept).resume(kept, now = 3).book.playNext(listOf(folder[1]), picked, now = 4)!!.book.place(2, 75_000)
        val before = book.queue(kept)!!
        val tapped = book.play(album, 4, playlistSource, now = 5)
        assertNotEquals(kept, tapped.book.currentId)
        assertEquals("Harbour Glass 5", currentName(tapped.book))
        assertFalse(tapped.toRecent)
        assertEquals(before.copy(updatedAt = 5), tapped.book.queue(kept))
        assertEquals(listOf(kept), tapped.book.saved)
        assertFalse(kept in tapped.book.recent)
    }

    // Tapping a song of an album (user 2026-11-24)

    @Test
    fun `a tap on an album's song starts its queue there, the album's songs after it next, then the songs before it`() {
        val queue = QueueBook().play(album, 2, albumSource, now = 1).book.current!!
        assertEquals(listOf("Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6", "Harbour Glass 1", "Harbour Glass 2"), paths(queue))
        assertEquals(0, queue.index)
        // Each song keeps its place in the album's list, and the queue stays the album's, in order: the play log's source.
        assertEquals(listOf(2, 3, 4, 5, 0, 1), queue.items.map { it.sourceIndex })
        assertEquals(albumSource, queue.source)
        assertFalse(queue.shuffled)
        assertEquals(6, queue.listSize)
    }

    @Test
    fun `an album tapped at its first song plays in track order, at its last song wraps round, and a one-song album plays its song`() {
        val first = QueueBook().play(album, 0, albumSource, now = 1).book.current!!
        assertEquals((1..6).map { "Harbour Glass $it" }, paths(first))
        assertEquals(0, first.index)
        val last = QueueBook().play(album, 5, albumSource, now = 1).book.current!!
        assertEquals(listOf("Harbour Glass 6", "Harbour Glass 1", "Harbour Glass 2", "Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 5"), paths(last))
        assertEquals(0, last.index)
        val one = QueueBook().play(listOf(song("Lone Pier")), 0, albumSource, now = 1).book.current!!
        assertEquals(listOf("Lone Pier"), paths(one))
        assertEquals(0, one.index)
    }

    @Test
    fun `a tap on any other list keeps the list's order and plays from the tapped song`() {
        SourceKind.entries.filter { it != SourceKind.ALBUM }.forEach { kind ->
            val queue = QueueBook().play(folder, 2, QueueSource(kind, "list", "Rips"), now = 1).book.current!!
            assertEquals("$kind", listOf("Rips 1", "Rips 2", "Rips 3", "Rips 4"), paths(queue))
            assertEquals("$kind", 2, queue.index)
        }
    }

    @Test
    fun `an album's Shuffle shuffles as any list's does, and Shuffle off on a tapped album brings its wrapped order back`() {
        val shuffled = QueueBook().shuffle(album, albumSource, Random(3), now = 1).book.current!!
        assertEquals(paths(QueueBook().shuffle(album, folderSource, Random(3), now = 1).book.current), paths(shuffled))
        assertEquals(0, shuffled.index)
        val round = QueueBook().play(album, 2, albumSource, now = 1).book.toggleShuffle(Random(1)).toggleShuffle(Random(1)).current!!
        assertEquals(listOf("Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6", "Harbour Glass 1", "Harbour Glass 2"), paths(round))
        assertEquals(0, round.index)
    }

    @Test
    fun `a second tap on the album playing starts it again from that song, wrapped round, in place of the queue before`() {
        val book = QueueBook().play(album, 2, albumSource, now = 1).book.playNext(listOf(folder[0]), picked, now = 2)!!.book
        val tapped = book.play(album, 4, albumSource, now = 3)
        assertEquals(listOf("Harbour Glass 5", "Harbour Glass 6", "Harbour Glass 1", "Harbour Glass 2", "Harbour Glass 3", "Harbour Glass 4"), paths(tapped.book.current))
        assertEquals(0, tapped.book.current!!.index)
        // One queue per place: the album's queue before goes, the Play next song it held with it.
        assertEquals(listOf(tapped.book.currentId), tapped.book.queues.map { it.id })
        assertFalse(tapped.toRecent)
        // A song taken out of the album's queue and tapped again starts the album from it too.
        val removed = book.remove(book.currentId!!, uidOf(book, album[4]))!!.book
        assertEquals(paths(tapped.book.current), paths(removed.play(album, 4, albumSource, now = 3).book.current))
    }

    // One recent queue per place

    /** The songs of each queue in Recent from a list of [kind], newest first. */
    private fun recentFrom(book: QueueBook, kind: SourceKind) = book.recent.mapNotNull(book::queue).filter { it.source.kind == kind }.map(::paths)

    @Test
    fun `a new sort of the Songs tab starts a new queue in the order shown, and it replaces the old one`() {
        val byArtist = album.reversed()
        val sorted = QueueBook().play(album, 0, SongsTabSource, now = 1).book.play(byArtist, 2, SongsTabSource, now = 2)
        assertEquals(byArtist.map { it.path }, sorted.book.current!!.items.map { it.song.path })
        assertEquals("Harbour Glass 4", currentName(sorted.book))
        assertFalse(sorted.toRecent)
        assertEquals(1, sorted.book.queues.size)
        val later = sorted.book.play(folder, 0, folderSource, now = 3).book
        assertEquals(listOf(paths(sorted.book.current)), recentFrom(later, SourceKind.SONGS))
    }

    @Test
    fun `a new filter of the Songs tab starts a new queue of the songs shown, and it replaces the old one`() {
        val synced = album.filterIndexed { i, _ -> i % 2 == 0 }
        val filtered = QueueBook().play(album, 1, SongsTabSource, now = 1).book.play(synced, 1, SongsTabSource, now = 2).book
        assertEquals(listOf("Harbour Glass 1", "Harbour Glass 3", "Harbour Glass 5"), paths(filtered.current))
        assertEquals("Harbour Glass 3", currentName(filtered))
        val later = filtered.play(folder, 0, folderSource, now = 3).book
        assertEquals(listOf(listOf("Harbour Glass 1", "Harbour Glass 3", "Harbour Glass 5")), recentFrom(later, SourceKind.SONGS))
    }

    @Test
    fun `two different searches leave one search queue in Recent, the newest`() {
        var book = QueueBook().play(album.take(2), 0, searchSource("harbour"), now = 1).book
        book = book.play(folder.take(3), 1, searchSource("rips"), now = 2).book
        assertEquals(listOf("Rips 1", "Rips 2", "Rips 3"), paths(book.current))
        book = book.play(album, 0, albumSource, now = 3).book
        assertEquals(listOf(listOf("Rips 1", "Rips 2", "Rips 3")), recentFrom(book, SourceKind.SEARCH))
    }

    @Test
    fun `two multi-select Plays leave one picked queue in Recent, the newest`() {
        var book = QueueBook().play(listOf(album[0], album[3]), 0, pickedSource("2 songs"), now = 1).book
        book = book.play(listOf(folder[2], folder[0], album[1]), 0, pickedSource("3 songs"), now = 2).book
        assertEquals(listOf("Rips 3", "Rips 1", "Harbour Glass 2"), paths(book.current))
        book = book.play(album, 0, albumSource, now = 3).book
        assertEquals(listOf(listOf("Rips 3", "Rips 1", "Harbour Glass 2")), recentFrom(book, SourceKind.PICKED))
    }

    @Test
    fun `a tap in the Songs tab in the same order still jumps inside its queue, whatever the queue's own changes`() {
        var book = QueueBook().play(album, 0, SongsTabSource, now = 1).book
        book = book.playNext(listOf(folder[0]), picked, now = 2)!!.book.toggleShuffle(Random(7)).move(book.currentId!!, 5, 4)
        val id = book.currentId
        val tapped = book.play(album, 3, SongsTabSource, now = 3)
        assertEquals(id, tapped.book.currentId)
        assertEquals("Harbour Glass 4", currentName(tapped.book))
        assertTrue("Rips 1" in paths(tapped.book.current))
        assertEquals(book.jumps + 1, tapped.book.jumps)
        // The same songs with one more (a song added to the library) are another list: a new queue.
        val grown = tapped.book.play(album + song("Harbour Glass 7"), 0, SongsTabSource, now = 4)
        assertNotEquals(id, grown.book.currentId)
        assertEquals(7, grown.book.current!!.items.size)
    }

    @Test
    fun `songs taken out of the queue keep it the list's own, so a tap there still jumps and Play next songs stay`() {
        var book = QueueBook().play(album, 0, SongsTabSource, now = 1).book.playNext(listOf(folder[0]), picked, now = 2)!!.book
        val id = book.currentId!!
        fun uid(song: SongRef) = book.queue(id)!!.items.first { it.song.path == song.path }.uid
        // One from the middle, then the last one.
        book = book.remove(id, uid(album[2]))!!.book
        book = book.remove(id, uid(album[5]))!!.book
        val tapped = book.play(album, 4, SongsTabSource, now = 3)
        assertEquals(id, tapped.book.currentId)
        assertEquals("Harbour Glass 5", currentName(tapped.book))
        assertTrue("Rips 1" in paths(tapped.book.current))
        assertFalse("Harbour Glass 3" in paths(tapped.book.current))
        // A song gone from the list itself (the library changed) makes it another list: a new queue.
        val shrunk = tapped.book.play(album - album[1], 0, SongsTabSource, now = 4)
        assertNotEquals(id, shrunk.book.currentId)
    }

    /** The uid of [song] in the current queue of [book]. */
    private fun uidOf(book: QueueBook, song: SongRef) = book.current!!.items.first { it.song.path == song.path }.uid

    @Test
    fun `a tap on a song taken out of the queue puts it back at its place in the list and plays it, keeping the rest`() {
        var book = QueueBook().play(album, 0, SongsTabSource, now = 1).book.playNext(listOf(folder[0]), picked, now = 2)!!.book
        val id = book.currentId!!
        book = book.remove(id, uidOf(book, album[3]))!!.book
        val tapped = book.play(album, 3, SongsTabSource, now = 3)
        assertEquals(id, tapped.book.currentId)
        assertEquals("Harbour Glass 4", currentName(tapped.book))
        assertEquals(
            listOf("Harbour Glass 1", "Rips 1", "Harbour Glass 2", "Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6"),
            paths(tapped.book.current),
        )
        assertEquals(book.jumps + 1, tapped.book.jumps)
    }

    @Test
    fun `in a shuffled queue the song put back keeps its list place for when Shuffle goes off`() {
        var book = QueueBook().play(album, 0, SongsTabSource, now = 1).book.toggleShuffle(Random(3))
        val id = book.currentId!!
        book = book.remove(id, uidOf(book, album[4]))!!.book
        val tapped = book.play(album, 4, SongsTabSource, now = 2).book
        assertEquals(id, tapped.currentId)
        assertEquals("Harbour Glass 5", currentName(tapped))
        assertEquals(album.map { it.path }, tapped.toggleShuffle(Random(3)).current!!.items.map { it.song.path })
    }

    @Test
    fun `a queue stored before the list's length was kept still jumps after its last song is taken out, and learns the length`() {
        val made = QueueBook().play(album, 0, playlistSource, now = 1).book
        var book = made.copy(queues = made.queues.map { it.copy(listSize = null) })
        val id = book.currentId!!
        book = book.remove(id, uidOf(book, album[5]))!!.book
        val tapped = book.play(album, 2, playlistSource, now = 2).book
        assertEquals(id, tapped.currentId)
        assertEquals("Harbour Glass 3", currentName(tapped))
        assertEquals(6, tapped.current!!.listSize)
        // The length known, a song added to the list (the library changed) makes it another list.
        assertNotEquals(id, tapped.play(album + song("Harbour Glass 7"), 0, playlistSource, now = 3).book.currentId)
    }

    @Test
    fun `no place mints its own picked key, so songs picked by hand are one place`() {
        // Unit tests run in the module's folder.
        val minted = File("src/main/java").walk().filter { it.extension == "kt" && it.readText().contains("\"picked:") }.map { it.name }.toList()
        assertEquals(emptyList<String>(), minted)
    }

    @Test
    fun `the keys name the place, not its sort, filter, words or time`() {
        assertEquals(searchSource("harbour").key, searchSource("rips").key)
        assertEquals(pickedSource("2 songs").key, pickedSource("3 songs").key)
        assertEquals(QueueSource(SourceKind.SONGS, "songs", ""), SongsTabSource)
    }

    @Test
    fun `a queue never holds a song twice, so a list with repeats keeps the first of each`() {
        val list = listOf(album[0], album[1], album[0], album[2])
        val book = QueueBook().play(list, 2, albumSource, now = 1).book
        assertEquals(listOf("Harbour Glass 1", "Harbour Glass 2", "Harbour Glass 3"), paths(book.current))
        assertEquals("Harbour Glass 1", currentName(book))
    }

    // Play next and Add to queue

    @Test
    fun `Play next goes after the current song and the Play next songs still waiting, in the order added`() {
        val first = playing(0).playNext(listOf(folder[0]), picked, now = 2)!!.book
        val second = first.playNext(listOf(folder[1], folder[2]), picked, now = 3)!!.book
        assertEquals(
            listOf("Harbour Glass 1", "Rips 1", "Rips 2", "Rips 3", "Harbour Glass 2", "Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6"),
            paths(second.current),
        )
        // Once a Play next song plays, it no longer waits: the next one goes after the two still waiting.
        val moved = second.at(second.current!!.items[1].uid, now = 4)
        val third = moved.playNext(listOf(folder[3]), picked, now = 5)!!.book
        assertEquals(listOf("Harbour Glass 1", "Rips 1", "Rips 2", "Rips 3", "Rips 4", "Harbour Glass 2"), paths(third.current).take(6))
    }

    @Test
    fun `Play next moves a song already in the queue instead of adding it twice, and leaves the current song alone`() {
        val book = playing(1)
        val edited = book.playNext(listOf(album[4], album[1]), picked, now = 2)!!
        assertEquals(listOf("Harbour Glass 1", "Harbour Glass 2", "Harbour Glass 5", "Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 6"), paths(edited.book.current))
        assertEquals("Harbour Glass 2", currentName(edited.book))
        assertNull(book.playNext(listOf(album[1]), picked, now = 3))
        // Undo puts the songs back as they were.
        assertEquals(paths(book.current), paths(edited.book.restore(edited.queueId, edited.before).current))
    }

    @Test
    fun `Play next and Add to queue without a queue make one of the songs`() {
        val next = QueueBook().playNext(listOf(folder[0], folder[1]), picked, now = 1)!!
        assertEquals(listOf("Rips 1", "Rips 2"), paths(next.book.current))
        val added = QueueBook().addToCurrent(listOf(folder[2]), picked, now = 1)!!
        assertEquals(listOf("Rips 3"), paths(added.book.current))
        assertEquals(1, added.added)
    }

    @Test
    fun `adding to a queue skips the songs already there and says how many`() {
        val book = playing(0)
        val added = book.addTo(book.currentId!!, listOf(album[1], folder[0], folder[1], album[3], folder[0]))!!
        assertEquals(2, added.added)
        assertEquals(2, added.already)
        assertEquals(listOf("Rips 1", "Rips 2"), paths(added.book.current).takeLast(2))
        assertEquals(paths(book.current), paths(added.book.removeUids(added.queueId, added.uids).current))
        assertEquals(0, book.addTo(book.currentId!!, listOf(album[0]))!!.added)
    }

    @Test
    fun `editing a queue that is not playing changes it and nothing else`() {
        val book = playing(0).play(folder, 0, folderSource, now = 2).book
        val other = book.recent.single()
        val added = book.addTo(other, listOf(folder[3]))!!.book
        val moved = added.move(other, 0, 2)
        val removed = moved.remove(other, moved.queue(other)!!.items.last().uid)!!.book
        assertEquals(book.currentId, removed.currentId)
        assertEquals(book.current, removed.current)
        assertEquals(book.jumps, removed.jumps)
        assertEquals(listOf("Harbour Glass 2", "Harbour Glass 3", "Harbour Glass 1", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6"), paths(removed.queue(other)))
    }

    @Test
    fun `a new queue from Add to is saved and waits, under the name given`() {
        val book = playing(0)
        val made = book.newQueue("Road trip", folder, folderSource, now = 3)!!.book
        val queue = made.queue(made.saved.single())!!
        assertEquals("Road trip", queue.name)
        assertEquals(book.currentId, made.currentId)
        assertEquals(book.jumps, made.jumps)
        // Its own source: a tap in the folder starts a queue of its own, and the saved one stays.
        val tapped = made.play(folder, 0, folderSource, now = 4).book
        assertNotEquals(queue.id, tapped.currentId)
        assertTrue(queue.id in tapped.saved)
    }

    // Removing and moving

    @Test
    fun `removing the current song makes the next one current from its start, and Undo puts it back before it`() {
        val book = playing(2).place(2, 30_000)
        val removed = book.remove(book.currentId!!, book.current!!.current!!.uid)!!
        assertEquals("Harbour Glass 4", currentName(removed.book))
        assertEquals(0L, removed.book.current!!.positionMs)
        assertEquals(book.jumps + 1, removed.book.jumps)
        val back = removed.book.putBack(removed.queueId, removed.item, removed.at)
        assertEquals(paths(book.current), paths(back.current))
        assertEquals("Harbour Glass 4", currentName(back))
    }

    @Test
    fun `removing the last song takes the queue with it`() {
        val book = QueueBook().play(listOf(folder[0]), 0, folderSource, now = 1).book
        val removed = book.remove(book.currentId!!, book.current!!.items.single().uid)!!
        assertTrue(removed.queueGone)
        assertNull(removed.book.currentId)
        assertTrue(removed.book.queues.isEmpty())
    }

    @Test
    fun `moving a song keeps the current song current`() {
        val book = playing(1)
        val moved = book.move(book.currentId!!, 1, 4)
        assertEquals("Harbour Glass 2", currentName(moved))
        assertEquals(4, moved.current!!.index)
    }

    @Test
    fun `a controller moving several songs at once moves them all, and the current song stays current`() {
        val book = playing(1)
        val numbers = { queue: Queue? -> paths(queue).map { it.removePrefix("Harbour Glass ").toInt() } }
        val up = controllerMove(book, book.current!!.items.map { it.uid }, fromIndex = 3, toIndex = 5, newIndex = 1)
        assertEquals(listOf(1, 4, 5, 2, 3, 6), numbers(up.current))
        assertEquals("Harbour Glass 2", currentName(up))
        val down = controllerMove(up, up.current!!.items.map { it.uid }, fromIndex = 0, toIndex = 2, newIndex = 4)
        assertEquals(listOf(5, 2, 3, 6, 1, 4), numbers(down.current))
        assertEquals("Harbour Glass 2", currentName(down))
        // One song, as before.
        val one = controllerMove(down, down.current!!.items.map { it.uid }, fromIndex = 5, toIndex = 6, newIndex = 0)
        assertEquals(listOf(4, 5, 2, 3, 6, 1), numbers(one.current))
    }

    // Recent and Saved

    @Test
    fun `Recent keeps the 10 newest, and a new queue from the same list replaces the older one`() {
        var book = QueueBook()
        (1..13).forEach { n -> book = book.play(listOf(song("Loose $n")), 0, QueueSource(SourceKind.SEARCH, "search:$n", "$n"), now = n.toLong()).book }
        assertEquals(10, book.recent.size)
        assertEquals("search:12", book.queue(book.recent.first())!!.source.key)
        assertEquals(11, book.queues.size)
        // The same search again: the recent queue from it goes.
        book = book.play(listOf(song("Loose 5")), 0, QueueSource(SourceKind.SEARCH, "search:5", "5"), now = 20).book
        val searched = book.recent.map { book.queue(it)!!.source.key }
        assertFalse("search:5" in searched)
        assertEquals(searched.distinct(), searched)
    }

    @Test
    fun `a book's queue left for music or another book is never kept in Recent, the music queue before it is`() {
        val parts = (1..3).map { song("Lantern Road $it") }
        val lanternRoad = QueueSource(SourceKind.BOOK, "/storage/emulated/0/Audiobooks/Lantern Road/|Lantern Road", "Lantern Road")
        val tideChart = QueueSource(SourceKind.BOOK, "/storage/emulated/0/Audiobooks/Tide Chart.m4b", "Tide Chart")
        val music = playing(0)
        val musicId = music.currentId!!
        val reading = music.play(parts, 1, lanternRoad, now = 2).book
        assertEquals(listOf(musicId), reading.recent)

        // Another book, then music: neither book waits in Recent; books.json keeps their places for the Continue row.
        val other = reading.play(listOf(song("Tide Chart")), 0, tideChart, now = 3)
        val back = other.book.play(folder, 0, folderSource, now = 4)

        assertFalse(other.toRecent)
        assertFalse(back.toRecent)
        assertEquals(listOf(musicId), back.book.recent)
        assertEquals(listOf(playlistSource.key, folderSource.key), back.book.queues.map { it.source.key })
        // Resume of a recent queue from a book leaves no book behind either.
        val resumed = back.book.play(parts, 0, lanternRoad, now = 5).book.resume(musicId, now = 6).book
        assertEquals(listOf(SourceKind.FOLDER), resumed.recent.map { resumed.queue(it)!!.source.kind })
    }

    @Test
    fun `Keep moves a recent queue to Saved, where it never drops off and never goes to Recent`() {
        var book = playing(0).play(folder, 0, folderSource, now = 2).book
        val kept = book.recent.single()
        book = book.keep(kept)
        assertEquals(listOf(kept), book.saved)
        assertTrue(book.recent.isEmpty())
        // Resumed then left again: it stays in Saved, its place updated.
        book = book.resume(kept, now = 3).book.place(3, 12_000)
        book = book.play(folder, 2, folderSource, now = 4).book
        assertEquals(listOf(kept), book.saved)
        assertFalse(kept in book.recent)
        assertEquals(3, book.queue(kept)!!.index)
        assertEquals(12_000L, book.queue(kept)!!.positionMs)
        (1..15).forEach { n -> book = book.play(listOf(song("Loose $n")), 0, QueueSource(SourceKind.SEARCH, "search:$n", "$n"), now = 10L + n).book }
        assertTrue(kept in book.saved)
        assertTrue(book.queue(kept) != null)
    }

    @Test
    fun `Rename names a queue, and a blank name changes nothing`() {
        val book = playing(0)
        val id = book.currentId!!
        assertEquals("Sunday chores", book.rename(id, "  Sunday chores ").queue(id)!!.name)
        assertEquals(book, book.rename(id, "   "))
    }

    @Test
    fun `Delete removes only the queue, never the one playing`() {
        val book = playing(0).play(folder, 0, folderSource, now = 2).book
        val other = book.recent.single()
        val deleted = book.delete(other)
        assertNull(deleted.queue(other))
        assertTrue(deleted.recent.isEmpty())
        assertEquals(book, book.delete(book.currentId!!))
        val saved = book.keep(other).delete(other)
        assertTrue(saved.saved.isEmpty())
    }

    @Test
    fun `Undo of Delete puts the queue back where it was, in Saved or in Recent, and only once`() {
        var book = playing(0).play(folder, 0, folderSource, now = 2).book
        val recent = book.recent.single()
        book = book.play(listOf(song("Loose 1")), 0, QueueSource(SourceKind.SEARCH, "search:loose", "loose"), now = 3).book
        assertEquals(2, book.recent.size)
        val kept = book.recent.first()
        book = book.keep(kept)
        val before = book

        val fromRecent = book.deleted(recent)!!
        assertNull(fromRecent.book.queue(recent))
        assertEquals(before, fromRecent.book.undelete(fromRecent))
        val fromSaved = book.deleted(kept)!!
        assertTrue(fromSaved.book.saved.isEmpty())
        assertEquals(before, fromSaved.book.undelete(fromSaved))
        // A second Undo, or one after the queue came back, changes nothing.
        assertEquals(before, before.undelete(fromSaved))
        // The queue playing is never deleted.
        assertNull(book.deleted(book.currentId!!))
        assertNull(book.deleted("q999"))
    }

    @Test
    fun `Undo of Delete brings back exactly the queue deleted, never taking another, even once Recent has filled again`() {
        var book = playing(0)
        (1..QueueBook.MAX_RECENT).forEach { n -> book = book.play(listOf(song("Loose $n")), 0, QueueSource(SourceKind.SEARCH, "search:$n", "$n"), now = 1L + n).book }
        assertEquals(QueueBook.MAX_RECENT, book.recent.size)
        val first = book.recent.first()
        val gone = book.deleted(first)!!
        // Another queue joins Recent before Undo: Recent is full again.
        val refilled = gone.book.play(listOf(song("Late")), 0, QueueSource(SourceKind.SEARCH, "search:late", "late"), now = 50).book
        assertEquals(QueueBook.MAX_RECENT, refilled.recent.size)
        val undone = refilled.undelete(gone)
        assertEquals(gone.queue, undone.queue(first))
        assertEquals(first, undone.recent.first())
        // Every queue there was is still there, as it was.
        refilled.queues.forEach { assertEquals(it, undone.queue(it.id)) }
        assertEquals(refilled.recent, undone.recent - first)
        assertEquals(refilled.saved, undone.saved)
        assertEquals(refilled.currentId, undone.currentId)
    }

    // The lists the screens show (QF3): the queue playing first, then the others, with no Saved part

    @Test
    fun `the queue playing is listed first and only there, the others under it with the last left first`() {
        val first = QueueBook().play(album, 0, albumSource, now = 1).book
        val albumId = first.currentId!!
        val second = first.play(folder, 0, folderSource, now = 2).book
        val ripsId = second.currentId!!
        val book = second.play(listOf(song("Loose 1")), 0, QueueSource(SourceKind.SEARCH, "search:loose", "loose"), now = 3).book
        assertEquals(listOf(book.currentId, ripsId, albumId), book.listed.map { it.id })
        assertEquals(listOf(ripsId, albumId), book.others.map { it.id })
        // Kept while it plays (a New queue played, or Keep in 3.0.0), it still shows once, first.
        val kept = book.keep(book.currentId!!)
        assertEquals(listOf(kept.currentId), kept.saved)
        assertEquals(listOf(kept.currentId, ripsId, albumId), kept.listed.map { it.id })
        assertFalse(kept.currentId in kept.others.map { it.id })
        // One recent queue per place still: the album played again shows once.
        val again = book.play(album, 3, albumSource, now = 4).book
        assertEquals(listOf(albumSource.key, "search:loose", folderSource.key), again.listed.map { it.source.key })
    }

    @Test
    fun `a queue kept with Keep before stays listed among the others by when it was left, and Recent still keeps 10`() {
        var book = playing(0)
        val kept = book.currentId!!
        book = book.keep(kept).play(folder, 0, folderSource, now = 2).book
        (1..12).forEach { n -> book = book.play(listOf(song("Loose $n")), 0, QueueSource(SourceKind.SEARCH, "search:$n", "$n"), now = 2L + n).book }
        assertEquals(QueueBook.MAX_RECENT, book.recent.size)
        val others = book.others
        assertEquals(QueueBook.MAX_RECENT + 1, others.size)
        // Left first (at 2), it shows last, under Recent's ten, the newest first.
        assertEquals(kept, others.last().id)
        assertEquals(book.recent, others.dropLast(1).map { it.id })
        // A queue made with New queue is kept too, and shows first, as the newest.
        val made = book.newQueue("Road trip", folder, folderSource, now = 50)!!.book
        assertEquals("Road trip", made.others.first().name)
        assertEquals(made.others.map { it.id }.distinct(), made.others.map { it.id })
    }

    @Test
    fun `with nothing playing the others are listed alone, and with no queue at all nothing is`() {
        assertEquals(emptyList<Queue>(), QueueBook().listed)
        val book = playing(0).play(folder, 0, folderSource, now = 2).book
        val albumId = book.recent.single()
        // Every song taken out of the queue playing takes the queue with it: nothing plays.
        val id = book.currentId!!
        val emptied = book.queue(id)!!.items.fold(book) { left, item -> left.remove(id, item.uid)!!.book }
        assertNull(emptied.current)
        assertEquals(listOf(albumId), emptied.listed.map { it.id })
        assertEquals(emptied.listed, emptied.others)
    }

    // Resume and Play from start

    @Test
    fun `Resume switches at the saved song and position, Play from start at the first song`() {
        val book = playing(3).place(3, 90_000).play(folder, 1, folderSource, now = 2).book
        val other = book.recent.single()
        val resumed = book.resume(other, now = 3)
        assertEquals(other, resumed.book.currentId)
        assertEquals(3, resumed.book.current!!.index)
        assertEquals(90_000L, resumed.book.current!!.positionMs)
        assertEquals(book.jumps + 1, resumed.book.jumps)
        // The folder queue went to Recent in its place, with its own place kept.
        assertEquals(1, resumed.book.queue(resumed.book.recent.single())!!.index)

        val fromStart = book.playFromStart(other, now = 3).book
        assertEquals(other, fromStart.currentId)
        assertEquals(0, fromStart.current!!.index)
        assertEquals(0L, fromStart.current!!.positionMs)
    }

    @Test
    fun `a tap in a queue that is not playing switches to it at that song`() {
        val book = playing(0).play(folder, 0, folderSource, now = 2).book
        val other = book.recent.single()
        val jumped = book.jump(other, 4, now = 3)
        assertEquals(other, jumped.book.currentId)
        assertEquals("Harbour Glass 5", currentName(jumped.book))
        assertTrue(jumped.toRecent)
    }

    // Shuffle

    // 3.1 (user 2026-11-18): Shuffle with a song playing puts that song first and shuffles every other one after it, the
    // songs before it too; before, only the songs after it were shuffled and the ones before stayed above it.
    @Test
    fun `Shuffle puts the song playing first, where it is in it, and every other song after it, and off restores the order`() {
        val book = playing(2).place(2, 61_000)
        val on = book.toggleShuffle(Random(7))
        assertTrue(on.current!!.shuffled)
        assertEquals(0, on.current!!.index)
        assertEquals(book.current!!.current!!.uid, on.current!!.current!!.uid)
        assertEquals(61_000L, on.current!!.positionMs)
        assertEquals(paths(book.current).toSet() - "Harbour Glass 3", paths(on.current).drop(1).toSet())
        // The songs before it mix in with the ones after it, rather than following them.
        assertTrue((1..20).any { seed -> paths(book.toggleShuffle(Random(seed)).current)[1] in setOf("Harbour Glass 1", "Harbour Glass 2") })
        val off = on.toggleShuffle(Random(7))
        assertEquals(paths(book.current), paths(off.current))
        assertEquals("Harbour Glass 3", currentName(off))
        assertEquals(2, off.current!!.index)
        assertEquals(61_000L, off.current!!.positionMs)
        assertFalse(off.current!!.shuffled)
    }

    @Test
    fun `Shuffle keeps the Play next songs waiting right after the song playing, in their order`() {
        val book = playing(2).playNext(listOf(folder[0], folder[1]), picked, now = 2)!!.book
        val before = listOf("Harbour Glass 1", "Harbour Glass 2", "Harbour Glass 3", "Rips 1", "Rips 2", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6")
        assertEquals(before, paths(book.current))
        (1..20).forEach { seed ->
            val on = book.toggleShuffle(Random(seed))
            assertEquals(listOf("Harbour Glass 3", "Rips 1", "Rips 2"), paths(on.current).take(3))
            assertEquals(listOf(false, true, true), on.current!!.items.take(3).map { it.playNext })
            assertEquals(setOf("Harbour Glass 1", "Harbour Glass 2", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6"), paths(on.current).drop(3).toSet())
            assertEquals(before, paths(on.toggleShuffle(Random(seed)).current))
        }
        // Still waiting: another Play next goes after them.
        val more = book.toggleShuffle(Random(1)).playNext(listOf(folder[2]), picked, now = 3)!!.book
        assertEquals(listOf("Harbour Glass 3", "Rips 1", "Rips 2", "Rips 3"), paths(more.current).take(4))
    }

    @Test
    fun `turning Shuffle off keeps added songs at the end and waiting Play next songs after the current one`() {
        val on = playing(0).toggleShuffle(Random(3))
        val added = on.addTo(on.currentId!!, listOf(folder[0]))!!.book
        val next = added.playNext(listOf(folder[1]), picked, now = 2)!!.book
        val off = next.toggleShuffle(Random(3))
        assertEquals(listOf("Harbour Glass 1", "Rips 2", "Harbour Glass 2", "Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 5", "Harbour Glass 6", "Rips 1"), paths(off.current))
    }

    @Test
    fun `Shuffle also brings a Play next song the queue jumped past right after the song playing`() {
        // Rips 1 waits after the first song; a tap further down the list plays Harbour Glass 4 and leaves it waiting.
        val book = playing(0).playNext(listOf(folder[0]), picked, now = 2)!!.book.play(album, 3, playlistSource, now = 3).book
        assertEquals("Harbour Glass 4", currentName(book))
        assertEquals(listOf("Harbour Glass 1", "Rips 1"), paths(book.current).take(2))
        assertTrue(book.current!!.items[1].playNext)
        val on = book.toggleShuffle(Random(5))
        assertEquals(listOf("Harbour Glass 4", "Rips 1"), paths(on.current).take(2))
        val off = on.toggleShuffle(Random(5))
        assertEquals(paths(book.current), paths(off.current))
        assertEquals(4, off.current!!.index)
    }

    @Test
    fun `a list header's Shuffle plays a new queue in a random order`() {
        val book = QueueBook().shuffle(album, albumSource, Random(1), now = 1).book
        assertTrue(book.current!!.shuffled)
        assertEquals(album.map { it.path }.toSet(), book.current!!.items.map { it.song.path }.toSet())
        assertEquals(0, book.current!!.index)
    }

    // The player moving on, and missing songs

    @Test
    fun `the player moving on makes that song current from its start, and ends its Play next wait`() {
        val book = playing(0).playNext(listOf(folder[0]), picked, now = 2)!!.book.place(0, 40_000)
        val next = book.current!!.items[1]
        val moved = book.at(next.uid, now = 3)
        assertEquals(1, moved.current!!.index)
        assertEquals(0L, moved.current!!.positionMs)
        assertFalse(moved.current!!.current!!.playNext)
        assertEquals(book.jumps, moved.jumps)
        assertEquals(book, book.at(999, now = 3))
    }

    @Test
    fun `a controller removing the song playing makes the next one current from its start`() {
        val book = playing(2).place(2, 61_000)
        val queue = book.current!!
        val removed = book.removeUids(queue.id, setOf(queue.items[2].uid))
        assertEquals(queue.items[3].uid, removed.current!!.current!!.uid)
        assertEquals(0L, removed.current!!.positionMs)
        // Removing another song keeps the place.
        assertEquals(61_000L, book.removeUids(queue.id, setOf(queue.items[0].uid)).current!!.positionMs)
    }

    @Test
    fun `the player loading the song already current keeps its place, only its Play next wait ends`() {
        val book = playing(2).place(2, 61_000)
        assertEquals(book, book.at(book.current!!.current!!.uid, now = 9))

        val waiting = playing(0).playNext(listOf(folder[0]), picked, now = 2)!!.book
        val next = waiting.current!!.items[1]
        val jumped = waiting.place(1, 12_000)
        val loaded = jumped.at(next.uid, now = 9)
        assertEquals(12_000L, loaded.current!!.positionMs)
        assertFalse(loaded.current!!.current!!.playNext)
    }

    @Test
    fun `songs gone from the phone leave saved queues on the next load, moved ones take their new path`() {
        var book = playing(0).play(folder, 0, folderSource, now = 2).book
        book = book.keep(book.recent.single())
        val savedId = book.saved.single()
        val gone = album[1].path
        val moved = folder[2]
        val movedTo = SongRef("/storage/emulated/0/Music/Elsewhere/Rips 3.mp3", moved.mediaStoreId)
        val refreshed = book.refresh { ref ->
            when (ref.path) {
                gone -> null
                moved.path -> movedTo
                else -> ref
            }
        }
        assertFalse(gone in refreshed.queue(savedId)!!.items.map { it.song.path })
        assertTrue(movedTo.path in refreshed.current!!.items.map { it.song.path })
        // A queue that is not saved skips them quietly instead.
        val notSaved = playing(0).refresh { if (it.path == gone) null else it }
        assertTrue(gone in notSaved.current!!.items.map { it.song.path })
        assertEquals(book.refresh { it }, book)
    }

    // Songs moved to the trash

    @Test
    fun `songs moved to the trash leave every queue, Recent and Saved too, and a queue left without songs goes`() {
        var book = QueueBook().play(listOf(folder[3]), 0, picked, now = 1).book
        val lone = book.currentId!!
        book = book.play(album, 2, playlistSource, now = 2).book.place(2, 40_000)
        val albumId = book.currentId!!
        book = book.play(folder, 1, folderSource, now = 3).book.place(1, 12_000)
        val folderId = book.currentId!!
        book = book.keep(albumId)

        val forgotten = book.forget(setOf(album[0].path, album[4].path, folder[0].path, folder[3].path))
        assertNull(forgotten.queue(lone))
        assertFalse(lone in forgotten.recent)
        assertEquals(listOf("Harbour Glass 2", "Harbour Glass 3", "Harbour Glass 4", "Harbour Glass 6"), paths(forgotten.queue(albumId)))
        // A queue whose current song stays keeps it, and its place in it.
        assertEquals("Harbour Glass 3", forgotten.queue(albumId)!!.current!!.song.path.substringAfterLast('/').removeSuffix(".mp3"))
        assertEquals(40_000L, forgotten.queue(albumId)!!.positionMs)
        assertTrue(albumId in forgotten.saved)
        assertEquals(folderId, forgotten.currentId)
        assertEquals(listOf("Rips 2", "Rips 3"), paths(forgotten.current))
        assertEquals("Rips 2", currentName(forgotten))
        assertEquals(12_000L, forgotten.current!!.positionMs)
        // The song playing stayed: the player is not moved.
        assertEquals(book.jumps, forgotten.jumps)
        assertSame(book, book.forget(setOf("/storage/emulated/0/Music/Elsewhere.mp3")))
    }

    @Test
    fun `the song playing moved to the trash gives way to the next song left, from its start`() {
        val book = playing(2).place(2, 30_000)
        val forgotten = book.forget(setOf(album[2].path, album[3].path))
        assertEquals(listOf("Harbour Glass 1", "Harbour Glass 2", "Harbour Glass 5", "Harbour Glass 6"), paths(forgotten.current))
        assertEquals("Harbour Glass 5", currentName(forgotten))
        assertEquals(0L, forgotten.current!!.positionMs)
        assertEquals(book.jumps + 1, forgotten.jumps)

        // Shuffled, the next song is the one that would play next, and the order before Shuffle loses the song too.
        val shuffled = playing(0).toggleShuffle(Random(7))
        val next = shuffled.current!!.items[1]
        val left = shuffled.forget(setOf(album[0].path))
        assertEquals(next.uid, left.current!!.current!!.uid)
        assertEquals(left.current!!.items.map { it.uid }.sorted(), left.current!!.unshuffled!!.sorted())
    }

    @Test
    fun `the song playing moved to the trash with none after it leaves the queue at its first song`() {
        val book = playing(5).place(5, 10_000)
        val stopped = book.forget(setOf(album[5].path))
        assertEquals("Harbour Glass 1", currentName(stopped))
        assertEquals(0L, stopped.current!!.positionMs)
        assertEquals(book.jumps + 1, stopped.jumps)
        // Every song after it moved too.
        assertEquals("Harbour Glass 1", currentName(playing(3).forget(album.drop(3).map { it.path }.toSet())))
    }

    @Test
    fun `a queue playing that loses every song to the trash goes, and nothing is left playing`() {
        val book = QueueBook().play(folder.take(2), 1, folderSource, now = 1).book
        val gone = book.forget(folder.take(2).map { it.path }.toSet())
        assertNull(gone.currentId)
        assertTrue(gone.queues.isEmpty())
    }

    // Names

    /** The made name as the app builds it: the list's name and the minute the queue started. */
    private fun made(queue: Queue) = "${queue.source.name.ifEmpty { "Picked" }} · ${queue.startedAt / 60_000}"

    @Test
    fun `a queue starts at the time it was made, and playing, leaving or keeping it later does not change that`() {
        var book = playing(0)
        val first = book.currentId!!
        book = book.play(folder, 0, folderSource, now = 120_000).book
        val second = book.currentId!!
        book = book.keep(first).resume(first, now = 300_000).book.place(2, 5_000)
        assertEquals(1L, book.queue(first)!!.startedAt)
        assertEquals(120_000L, book.queue(second)!!.startedAt)
        val made = book.newQueue(null, folder, folderSource, now = 400_000)!!.book
        assertEquals(400_000L, made.queue(made.saved.first())!!.startedAt)
    }

    @Test
    fun `two queues from the same list show their start times, and the same minute twice takes a number, the older plain`() {
        // The phone: a saved queue and a recent one, both from Songs, both read "Songs".
        val songs = QueueSource(SourceKind.SONGS, "songs", "Songs")
        var book = QueueBook().play(album, 0, songs, now = 60_000).book
        val first = book.currentId!!
        book = book.keep(first).play(album, 1, songs, now = 30 * 60_000).book
        val second = book.currentId!!
        assertEquals(mapOf(first to "Songs · 1", second to "Songs · 30"), queueNames(book.queues, ::made))

        book = book.keep(second).resume(first, now = 30 * 60_000 + 10_000).book.play(album, 2, songs, now = 30 * 60_000 + 20_000).book
        val third = book.currentId!!
        val names = queueNames(book.queues, ::made)
        assertEquals("Songs · 30", names[second])
        assertEquals("Songs · 30 2", names[third])
        assertEquals(3, names.values.toSet().size)
    }

    @Test
    fun `the user's name stays as given, and a made name it already takes gets the next number`() {
        var book = playing(0).play(folder, 0, folderSource, now = 120_000).book
        val named = book.currentId!!
        book = book.keep(named).rename(named, "Rips · 2")
        book = book.play(folder, 1, QueueSource(SourceKind.FOLDER, "folder:Music/Rips/2", "Rips"), now = 150_000).book
        val names = queueNames(book.queues, ::made)
        assertEquals("Rips · 2", names[named])
        assertEquals("Rips · 2 2", names[book.currentId])
        assertEquals("Harbour Glass · 0", names[book.recent.single()])
    }

    // albumInOrder

    @Test
    fun `an album plays in track order until shuffle, a Play next song or another list breaks it`() {
        val book = QueueBook().play(album, 0, albumSource, now = 1).book
        val queue = book.current!!
        assertTrue((0 until 6).all { albumInOrder(queue, it) })
        assertFalse(albumInOrder(book.toggleShuffle(Random(2)).current!!, 1))

        val withNext = book.playNext(listOf(folder[0]), picked, now = 2)!!.book.current!!
        assertTrue(albumInOrder(withNext, 0))
        assertFalse(albumInOrder(withNext, 1))
        assertFalse(albumInOrder(withNext, 2))
        assertTrue(albumInOrder(withNext, 3))

        val moved = book.move(book.currentId!!, 4, 1).current!!
        assertFalse(albumInOrder(moved, 1))
        assertFalse(albumInOrder(moved, 2))

        val fromFolder = QueueBook().play(folder, 0, folderSource, now = 1).book.current!!
        assertFalse(albumInOrder(fromFolder, 1))
        assertFalse(albumInOrder(queue, 6))
    }

    @Test
    fun `an album tapped at a song plays in track order throughout, its first song right after its last`() {
        val items = listOf(2, 3, 4, 5, 0, 1).mapIndexed { i, place -> QueueItem(i + 1L, album[place], sourceIndex = place) }
        val wrapped = Queue("q1", albumSource, items, listSize = 6)
        assertTrue((0 until 6).all { albumInOrder(wrapped, it) })
        // Only the album's first song counts after its last; not another song, nor after a Play next song, nor in a queue
        // stored before the list's length was kept.
        assertFalse(albumInOrder(wrapped.copy(items = items.filter { it.sourceIndex != 0 }), 4))
        assertFalse(albumInOrder(wrapped.copy(items = listOf(QueueItem(9, folder[0]), items[4])), 1))
        assertFalse(albumInOrder(wrapped.copy(items = listOf(QueueItem(9, folder[0]), items[4]), listSize = null), 1))
        assertFalse(albumInOrder(wrapped.copy(listSize = null), 4))
    }

    // Suggestions in the queue (v4)

    private fun suggestedPaths(queue: Queue?) = queue!!.items.filter { it.suggested != null }.map { it.song.path.substringAfterLast('/').removeSuffix(".mp3") }

    @Test
    fun `the Shuffle button steps Off, Shuffle, Smart shuffle, then Off, and Smart shuffle keeps the order`() {
        val book = playing(1)
        assertEquals(ShuffleStep.OFF, book.current!!.shuffleStep)
        val shuffled = book.cycleShuffle(Random(4))
        assertEquals(ShuffleStep.SHUFFLE, shuffled.current!!.shuffleStep)
        assertEquals(paths(book.current).toSet(), paths(shuffled.current).toSet())
        val smart = shuffled.cycleShuffle(Random(4))
        assertEquals(ShuffleStep.SMART, smart.current!!.shuffleStep)
        assertEquals(paths(shuffled.current), paths(smart.current))
        val off = smart.cycleShuffle(Random(4))
        assertEquals(ShuffleStep.OFF, off.current!!.shuffleStep)
        assertEquals(paths(book.current), paths(off.current))
        assertFalse(off.current!!.smart)
    }

    @Test
    fun `suggestions go right after the songs they name, marked, never a song the queue holds, never after one gone`() {
        val book = playing(0).cycleShuffle(Random(2)).cycleShuffle(Random(2))
        val queue = book.current!!
        val first = queue.items[0].uid
        val third = queue.items[2].uid
        val suggested = book.suggest(queue.id, listOf(first to folder[0], first to folder[1], third to folder[2], third to album[4], 999L to folder[3]), Suggested.SMART_SHUFFLE)
        val after = suggested.current!!
        assertEquals(listOf("Rips 1", "Rips 2", "Rips 3"), suggestedPaths(after))
        assertEquals(paths(queue).take(1) + listOf("Rips 1", "Rips 2") + paths(queue).subList(1, 3) + "Rips 3" + paths(queue).drop(3), paths(after))
        assertTrue(after.items.filter { it.suggested != null }.all { it.suggested == Suggested.SMART_SHUFFLE && it.uid >= book.nextUid && !it.playNext && it.sourceIndex == null })
        assertEquals(book.nextUid + 3, suggested.nextUid)
        assertEquals(queue.current!!.uid, after.current!!.uid)
        assertEquals(queue.unshuffled, after.unshuffled)
    }

    @Test
    fun `Autoplay's songs go after the queue's last song, and leave again when it is turned off`() {
        val book = playing(5)
        val queue = book.current!!
        val withAutoplay = book.suggest(queue.id, listOf(queue.items.last().uid to folder[0], queue.items.last().uid to folder[1]), Suggested.AUTOPLAY)
        assertEquals(paths(queue) + listOf("Rips 1", "Rips 2"), paths(withAutoplay.current))
        val off = withAutoplay.unsuggest(queue.id, Suggested.AUTOPLAY)
        assertEquals(paths(queue), paths(off.current))
    }

    @Test
    fun `suggestions of one kind leave only after the song playing, which stays even when it is one`() {
        val book = playing(0).cycleShuffle(Random(2)).cycleShuffle(Random(2))
        val queue = book.current!!
        val withBoth = book.suggest(queue.id, listOf(queue.items[0].uid to folder[0], queue.items[3].uid to folder[1]), Suggested.SMART_SHUFFLE)
            .let { it.suggest(queue.id, listOf(it.current!!.items.last().uid to folder[2]), Suggested.AUTOPLAY) }
        val onFirst = withBoth.at(withBoth.current!!.items[1].uid, now = 9)
        assertEquals("Rips 1", currentName(onFirst))
        val gone = onFirst.unsuggest(queue.id, Suggested.SMART_SHUFFLE)
        assertEquals(listOf("Rips 1", "Rips 3"), suggestedPaths(gone.current))
        assertEquals("Rips 1", currentName(gone))
    }

    @Test
    fun `a suggestion is a song a suggestion put in, or a song of a suggested list, never one the user added to it`() {
        val book = playing(0).suggest(playing(0).current!!.id, listOf(playing(0).current!!.items.last().uid to folder[0]), Suggested.AUTOPLAY)
        val queue = book.current!!
        assertEquals(listOf("Rips 1"), queue.items.filter(queue::suggests).map { it.song.path.substringAfterLast('/').removeSuffix(".mp3") })
        val forYou = QueueBook().play(album.take(3), 0, QueueSource(SourceKind.SUGGESTIONS, "for-you", "For you"), now = 1).book
        val withPlayNext = forYou.playNext(listOf(folder[1]), picked, now = 2)!!.book
        val list = withPlayNext.current!!
        assertEquals(3, list.items.count(list::suggests))
        assertFalse(list.suggests(list.items.single { it.song == folder[1] }))
    }

    @Test
    fun `Smart shuffle's Undo goes back to plain Shuffle, its suggestions to come gone and the order kept`() {
        val book = playing(0).cycleShuffle(Random(2)).cycleShuffle(Random(2))
        val queue = book.current!!
        val withSuggestions = book.suggest(queue.id, listOf(queue.items[0].uid to folder[0], queue.items[2].uid to folder[1]), Suggested.SMART_SHUFFLE)
        val plain = withSuggestions.plainShuffle()
        assertEquals(ShuffleStep.SHUFFLE, plain.current!!.shuffleStep)
        assertEquals(paths(queue), paths(plain.current))
        assertEquals(queue.unshuffled, plain.current!!.unshuffled)
        assertEquals(playing(0), playing(0).plainShuffle())
    }

    @Test
    fun `Smart shuffle off takes the suggestions still to come out with it, from the button or the notification`() {
        val book = playing(0).cycleShuffle(Random(2)).cycleShuffle(Random(2))
        val queue = book.current!!
        val withSuggestions = book.suggest(queue.id, listOf(queue.items[0].uid to folder[0], queue.items[2].uid to folder[1]), Suggested.SMART_SHUFFLE)
        val off = withSuggestions.cycleShuffle(Random(2))
        assertEquals(paths(playing(0).current), paths(off.current))
        val toggled = withSuggestions.toggleShuffle(Random(2))
        assertEquals(paths(playing(0).current), paths(toggled.current))
        assertEquals(ShuffleStep.OFF, toggled.current!!.shuffleStep)
    }
}
