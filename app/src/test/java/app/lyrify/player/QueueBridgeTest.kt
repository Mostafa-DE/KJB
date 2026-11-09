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

    private var elapsed = 0L
    private var wall = 1_000_000L
    private val events = mutableListOf<PlayEvent>()
    private val tracker = PlayTracker({ events += it }, elapsed = { elapsed }, wall = { wall })

    private fun pass(ms: Long) {
        elapsed += ms
        wall += ms
    }

    @Test
    fun `a play tells when it started, what was heard, the seeks and its end`() {
        tracker.start(refs[0], 180_001, playing = true)
        pass(30_000)
        tracker.playing(false)
        pass(60_000)
        tracker.playing(true)
        pass(10_000)
        tracker.seeked(40_000, 100_000)
        pass(5_000)
        tracker.end()
        val id = (events.first() as PlayEvent.Started).playId
        assertEquals(
            listOf(
                PlayEvent.Started(id, refs[0], 1_000_000, 180_001),
                PlayEvent.Heard(id, refs[0], 1_030_000, 30_000),
                PlayEvent.Heard(id, refs[0], 1_100_000, 10_000),
                PlayEvent.Seeked(id, refs[0], 1_100_000, 40_000, 100_000),
                PlayEvent.Heard(id, refs[0], 1_105_000, 5_000),
                PlayEvent.Ended(id, refs[0], 1_105_000),
            ),
            events,
        )
    }

    @Test
    fun `a new play ends the one before, each with its own id, and nothing is heard while paused`() {
        tracker.start(refs[0], 180_001, playing = false)
        pass(20_000)
        tracker.flush()
        tracker.start(refs[0], 180_001, playing = true)
        pass(4_000)
        tracker.flush()
        tracker.flush()
        val starts = events.filterIsInstance<PlayEvent.Started>()
        assertEquals(2, starts.size)
        assertTrue(starts[0].playId != starts[1].playId)
        assertEquals(listOf(PlayEvent.Heard(starts[1].playId, refs[0], wall, 4_000)), events.filterIsInstance<PlayEvent.Heard>())
        assertEquals(PlayEvent.Ended(starts[0].playId, refs[0], 1_020_000), events.filterIsInstance<PlayEvent.Ended>().single())
        tracker.end()
        tracker.end()
        assertEquals(2, events.filterIsInstance<PlayEvent.Ended>().size)
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
}
