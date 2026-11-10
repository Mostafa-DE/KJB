package app.lyrify.player

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.stats.PlayEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The bridge from the queue model to the player, through a fake player; play events; place saving. Made-up names only. */
class QueueBridgeTest {
    private val songs: List<Song> = (1..8).map { song("Music/Harbour Glass/0$it Paper Lantern $it.mp3", album = "Harbour Glass", track = it, durationMs = 180_000L + it) }
    private val refs = songs.map { it.ref }
    private val albumSource = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")
    private val folderSource = QueueSource(SourceKind.FOLDER, "folder:Music", "Music")
    private val picked = QueueSource(SourceKind.PICKED, "picked", "")
    private val missing = mutableSetOf<String>()
    private val find: (SongRef) -> Song? = { ref -> songs.firstOrNull { it.path == ref.path }?.takeUnless { it.path in missing } }

    /** A player that keeps a list of uids, as ExoPlayer keeps media items: removing the current item makes the next one current. */
    private class FakePort : PlayerPort {
        override var loaded: List<Long> = emptyList()
        override var index: Int = -1
        override var positionMs: Long = 0
        val calls = mutableListOf<String>()

        override fun load(items: List<Projected>, index: Int, positionMs: Long) {
            loaded = items.map { it.uid }
            this.index = index
            this.positionMs = positionMs
            calls += "load"
        }

        override fun apply(edit: Edit) {
            val current = loaded.getOrNull(index)
            val list = loaded.toMutableList()
            when (edit) {
                is Edit.Remove -> list.removeAt(edit.at)
                is Edit.Insert -> list.addAll(edit.at, edit.items.map { it.uid })
                is Edit.Move -> list.add(edit.to, list.removeAt(edit.from))
            }
            loaded = list
            index = if (current != null && current in list) list.indexOf(current) else index.coerceAtMost(list.lastIndex)
            calls += "edit"
        }

        override fun seek(index: Int, positionMs: Long) {
            this.index = index
            this.positionMs = positionMs
            calls += "seek"
        }

        val current: Long? get() = loaded.getOrNull(index)
    }

    private val port = FakePort()
    private val bridge = QueueBridge(port)

    private fun uidsOf(book: QueueBook) = projection(book.current, find).map { it.uid }

    @Test
    fun `the first sync loads the queue at its current song and position`() {
        val book = QueueBook().play(refs, 2, albumSource, now = 1).book.place(2, 42_000)
        bridge.sync(book, find)
        assertEquals(listOf("load"), port.calls)
        assertEquals(uidsOf(book), port.loaded)
        assertEquals(book.current!!.current!!.uid, port.current)
        assertEquals(42_000L, port.positionMs)
    }

    @Test
    fun `songs not on the phone are left out, and a missing current song starts the next one from its start`() {
        missing += songs[2].path
        missing += songs[5].path
        val book = QueueBook().play(refs, 2, albumSource, now = 1).book.place(2, 42_000)
        val projected = projection(book.current, find)
        assertEquals(6, projected.size)
        assertEquals(songs[3].path, projected[startOf(book.current!!, projected)].song.path)
        bridge.sync(book, find)
        assertEquals(book.current!!.items[3].uid, port.current)
        assertEquals(0L, port.positionMs)
        // A queue with nothing on the phone loads nothing.
        missing += songs.map { it.path }
        assertEquals(-1, startOf(book.current!!, projection(book.current, find)))
    }

    @Test
    fun `queue edits reach the player without touching the song playing`() {
        var book = QueueBook().play(refs.take(5), 1, albumSource, now = 1).book
        bridge.sync(book, find)
        port.positionMs = 50_000
        val playing = port.current
        val id = book.currentId!!
        book = book.playNext(listOf(refs[6], refs[3]), picked, now = 2)!!.book
        bridge.sync(book, find)
        book = book.addTo(id, listOf(refs[7]))!!.book
        bridge.sync(book, find)
        book = book.move(id, 4, 0)
        bridge.sync(book, find)
        book = book.remove(id, book.current!!.items.last().uid)!!.book
        bridge.sync(book, find)
        book = book.toggleShuffle(Random(4))
        bridge.sync(book, find)
        assertEquals(uidsOf(book), port.loaded)
        assertEquals(playing, port.current)
        assertEquals(50_000L, port.positionMs)
        assertEquals(1, port.calls.count { it == "load" })
        assertEquals(0, port.calls.count { it == "seek" })
    }

    @Test
    fun `a tap inside the queue moves the player there, from the start`() {
        var book = QueueBook().play(refs, 0, albumSource, now = 1).book
        bridge.sync(book, find)
        port.positionMs = 9_000
        book = book.play(refs, 5, albumSource, now = 2).book
        bridge.sync(book, find)
        assertEquals(book.current!!.items[5].uid, port.current)
        assertEquals(0L, port.positionMs)
        assertEquals(listOf("load", "seek"), port.calls)
        // The player moving on by itself is followed in the book; nothing goes back to the player.
        book = book.at(book.current!!.items[6].uid, now = 3)
        port.index = 6
        bridge.sync(book, find)
        assertEquals(listOf("load", "seek"), port.calls)
    }

    @Test
    fun `removing the song playing goes on with the next one from its start`() {
        var book = QueueBook().play(refs.take(4), 1, albumSource, now = 1).book
        bridge.sync(book, find)
        port.positionMs = 20_000
        book = book.remove(book.currentId!!, book.current!!.current!!.uid)!!.book
        bridge.sync(book, find)
        assertEquals(uidsOf(book), port.loaded)
        assertEquals(book.current!!.current!!.uid, port.current)
        assertEquals(0L, port.positionMs)
    }

    @Test
    fun `switching queues loads the other one at its place, and nothing playing empties the player`() {
        var book = QueueBook().play(refs.take(3), 0, albumSource, now = 1).book
        bridge.sync(book, find)
        book = book.play(refs.drop(3), 0, folderSource, now = 2).book.place(1, 3_000)
        bridge.sync(book, find)
        val albumQueue = book.recent.single()
        book = book.resume(albumQueue, now = 3).book
        bridge.sync(book, find)
        assertEquals(uidsOf(book), port.loaded)
        assertEquals(listOf("load", "load", "load"), port.calls)

        book = book.remove(albumQueue, book.current!!.items[0].uid)!!.book
        book = book.remove(albumQueue, book.current!!.items[0].uid)!!.book
        book = book.remove(albumQueue, book.current!!.items[0].uid)!!.book
        bridge.sync(book, find)
        assertEquals(emptyList<Long>(), port.loaded)
        assertEquals(-1, port.index)
    }

    @Test
    fun `the edits from any order to any other keep the current song and end in the order wanted`() {
        val random = Random(11)
        repeat(200) {
            val from = (1L..12L).shuffled(random).take(random.nextInt(2, 12))
            val current = from[random.nextInt(from.size)]
            val to = ((from - current).filter { random.nextBoolean() } + (20L..24L).filter { random.nextBoolean() } + current).shuffled(random)
            val fake = FakePort().apply {
                loaded = from
                index = from.indexOf(current)
            }
            edits(fake.loaded, to.map { Projected(it, songs[0]) }).forEach(fake::apply)
            assertEquals(to, fake.loaded)
            assertEquals(current, fake.current)
        }
    }

    // Play events

    private var wall = 1_000_000L
    private val events = mutableListOf<PlayEvent>()
    private val tracker = PlayTracker({ events += it }, wall = { wall })

    @Test
    fun `a play reports its start, its position on play and pause and while playing, its seeks and its end`() {
        tracker.start(refs[0], 180_001, positionMs = 0, playing = true)
        wall += 5_000
        tracker.position(5_000, playing = true)
        wall += 25_000
        tracker.position(30_000, playing = false)
        wall += 60_000
        tracker.position(30_000, playing = true)
        wall += 10_000
        tracker.seeked(40_000, 100_000)
        wall += 5_000
        tracker.end()
        val id = (events.first() as PlayEvent.Started).playId
        assertEquals(
            listOf(
                PlayEvent.Started(id, refs[0], 1_000_000, 180_001),
                PlayEvent.Position(id, refs[0], 1_000_000, 0, playing = true),
                PlayEvent.Position(id, refs[0], 1_005_000, 5_000, playing = true),
                PlayEvent.Position(id, refs[0], 1_030_000, 30_000, playing = false),
                PlayEvent.Position(id, refs[0], 1_090_000, 30_000, playing = true),
                PlayEvent.Seeked(id, refs[0], 1_100_000, 40_000, 100_000),
                PlayEvent.Ended(id, refs[0], 1_105_000, 100_000),
            ),
            events,
        )
    }

    @Test
    fun `a song that played to its end ends at its length, a skipped one where it was, and each play has its own id`() {
        tracker.start(refs[0], 180_001, positionMs = 0, playing = true)
        tracker.position(170_000, playing = true)
        wall += 10_000
        // The next song started by itself: the one before ended at its length.
        tracker.start(refs[1], 180_002, positionMs = 0, playing = true, naturalEnd = true)
        tracker.position(12_000, playing = true)
        // Next was pressed: it ended where it was.
        tracker.start(refs[2], 180_003, positionMs = 0, playing = true)
        tracker.end(naturalEnd = true)
        tracker.end()
        tracker.position(1_000, playing = true)
        val ended = events.filterIsInstance<PlayEvent.Ended>()
        assertEquals(listOf(180_001L, 12_000L, 180_003L), ended.map { it.positionMs })
        assertEquals(listOf(refs[0], refs[1], refs[2]), ended.map { it.song })
        val ids = events.filterIsInstance<PlayEvent.Started>().map { it.playId }
        assertEquals(ids.distinct(), ids)
        assertEquals(3, ids.size)
        // Nothing after the end: no play to report on.
        assertTrue(events.last() is PlayEvent.Ended)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `while playing, something runs every few seconds, and stops on pause`() = runTest {
        val playing = MutableStateFlow(false)
        val ran = mutableListOf<Long>()
        val job = launch { repeatWhilePlaying(playing, 5_000) { ran += testScheduler.currentTime } }
        runCurrent()
        playing.value = true
        runCurrent()
        advanceTimeBy(12_000)
        playing.value = false
        runCurrent()
        advanceTimeBy(20_000)
        assertEquals(listOf(5_000L, 10_000L), ran)
        job.cancel()
    }

    // Saving the place

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the place is saved every 10 s while playing and on every pause`() = runTest {
        val playing = MutableStateFlow(false)
        val saved = mutableListOf<Long>()
        val job = launch { savePlaces(playing) { saved += testScheduler.currentTime } }
        runCurrent()
        assertEquals(emptyList<Long>(), saved)
        playing.value = true
        runCurrent()
        advanceTimeBy(25_000)
        playing.value = false
        runCurrent()
        assertEquals(listOf(10_000L, 20_000L, 25_000L), saved)
        advanceTimeBy(60_000)
        playing.value = true
        runCurrent()
        advanceTimeBy(10_001)
        assertEquals(listOf(10_000L, 20_000L, 25_000L, 95_000L), saved)
        job.cancel()
    }

    // A song that cannot be played

    @Test
    fun `after a song fails the player goes on with the song it would play next, wrapping under Repeat all`() {
        assertEquals(AfterError.Skip(2), afterError(next = 2, failedInARow = 1, size = 4))
        // The last song failed with Repeat all: back to the first, never a stall past the end.
        assertEquals(AfterError.Skip(0), afterError(next = 0, failedInARow = 1, size = 4))
        // The last song failed with Repeat off: nothing follows.
        assertEquals(AfterError.Stop, afterError(next = -1, failedInARow = 1, size = 4))
    }

    @Test
    fun `when every song fails in a row the player stops with the error, instead of going round for ever`() {
        val size = 3
        var at = 0
        val steps = (1..size).map { failed ->
            afterError(next = (at + 1) % size, failedInARow = failed, size = size).also { if (it is AfterError.Skip) at = it.index }
        }
        assertEquals(listOf(AfterError.Skip(1), AfterError.Skip(2), AfterError.Stop), steps)
        assertEquals(AfterError.Stop, afterError(next = 0, failedInARow = 1, size = 1))
    }
}
