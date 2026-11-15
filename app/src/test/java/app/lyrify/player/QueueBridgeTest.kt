package app.lyrify.player

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayCounter
import app.lyrify.stats.PlayEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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

        /** The path it was handed for each song, by uid. */
        val paths = mutableMapOf<Long, String>()

        override fun load(items: List<Projected>, index: Int, positionMs: Long) {
            loaded = items.map { it.uid }
            items.forEach { paths[it.uid] = it.song.path }
            this.index = index
            this.positionMs = positionMs
            calls += "load"
        }

        override fun apply(edit: Edit) {
            val current = loaded.getOrNull(index)
            val list = loaded.toMutableList()
            when (edit) {
                is Edit.Remove -> list.subList(edit.at, edit.at + edit.count).clear()
                is Edit.Insert -> {
                    list.addAll(edit.at, edit.items.map { it.uid })
                    edit.items.forEach { paths[it.uid] = it.song.path }
                }
                is Edit.Move -> list.add(edit.to, list.removeAt(edit.from))
                is Edit.Replace -> {
                    list[edit.at] = edit.item.uid
                    paths[edit.item.uid] = edit.item.song.path
                    calls += "replace ${edit.at} ${edit.item.song.path}"
                    return
                }
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
            edits(fake.loaded, to.map { Projected(it, songs[0]) })!!.forEach(fake::apply)
            assertEquals(to, fake.loaded)
            assertEquals(current, fake.current)
        }
    }

    @Test
    fun `a plan from any order to any other is a few edits, keeps the song playing, and hands moved files their new path`() {
        val random = Random(17)
        val elsewhere = "Music/Elsewhere/Paper Lantern.mp3"
        repeat(400) {
            val from = (1L..60L).shuffled(random).take(random.nextInt(1, 60))
            val playing = from[random.nextInt(from.size)]
            val stays = random.nextInt(4) != 0
            val others = (from - playing).filter { random.nextInt(5) != 0 } + (100L..110L).filter { random.nextBoolean() }
            // Small changes (the old order, a song or two out of place) and large ones (a shuffle).
            val all = others + listOfNotNull(playing.takeIf { stays })
            val rank = all.associateWith { uid -> from.indexOf(uid).takeIf { it >= 0 } ?: random.nextInt(60) }
            val to = if (random.nextBoolean()) all.shuffled(random) else all.sortedBy(rank::getValue)
            val handed = from.associateWith { if (random.nextInt(12) == 0) elsewhere else songs[0].path }
            val fake = FakePort().apply {
                loaded = from
                index = from.indexOf(playing)
                paths += handed
            }
            val plan = plan(from, to.map { Projected(it, songs[0]) }, playing, handed)
            if (plan == null) {
                assertTrue("only a large change without the song playing loads again", !stays)
                return@repeat
            }
            assertTrue("${plan.size} edits", plan.size <= MAX_EDITS)
            plan.forEach(fake::apply)
            assertEquals(to, fake.loaded)
            if (stays) assertEquals(playing, fake.current)
            assertEquals(to.map { songs[0].path }, to.map { fake.paths[it] })
        }
    }

    @Test
    fun `a song dragged from any place to any other is one move`() {
        val from = (1L..30L).toList()
        for (a in from.indices) for (b in from.indices) {
            if (a == b) continue
            val to = from.toMutableList().apply { add(b, removeAt(a)) }
            val fake = FakePort().apply {
                loaded = from
                index = 0
            }
            val edits = edits(from, to.map { Projected(it, songs[0]) })!!
            assertEquals("$a to $b", 1, edits.size)
            edits.forEach(fake::apply)
            assertEquals(to, fake.loaded)
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

    /**
     * What the service sends on a restart: the queue loaded at its place starts a play there, paused ([PlayTracker.start]
     * with the engine's position, a Position right after the Started). The counter goes on with the play it kept, and a
     * restored song never played leaves nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a queue restored after a restart goes on as the play kept before it, and one never played leaves no play`() = runTest {
        val log = FakePlayLog()
        val share = MutableStateFlow<Int?>(50)
        val started = wall
        val before = PlayTracker(PlayCounter(log, share, backgroundScope), wall = { wall })
        before.start(refs[0], 180_000, positionMs = 0, playing = true)
        for (at in 5_000L..60_000L step 5_000L) {
            wall += 5_000
            before.position(at, playing = true)
        }
        before.position(60_000, playing = false)
        runCurrent()

        // The app starts again: the player loads the song at its place, paused, then plays it to its end.
        wall += 600_000
        val after = PlayTracker(PlayCounter(log, share, backgroundScope), wall = { wall })
        after.start(refs[0], 180_000, positionMs = 60_000, playing = false)
        after.position(60_000, playing = true)
        for (at in 65_000L..175_000L step 5_000L) {
            wall += 5_000
            after.position(at, playing = true)
        }
        wall += 5_000
        // On to the next song, which the app stops before it is played: no play of it.
        after.start(refs[1], 180_000, positionMs = 0, playing = false, naturalEnd = true)
        after.end()
        runCurrent()

        assertEquals(listOf(LoggedPlay(refs[0], started, 180_000, counted = true)), log.plays)
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

    @Test
    fun `a song moved on the phone reaches the player at its new path, and only that song is replaced`() {
        val elsewhere = songs[2].copy(ref = SongRef("Music/Moved/03 Paper Lantern 3.mp3", songs[2].ref.mediaStoreId))
        var moved = false
        val findMoved: (SongRef) -> Song? = { ref -> if (moved && ref.path in setOf(songs[2].path, elsewhere.path)) elsewhere else find(ref) }
        val book = QueueBook().play(refs, 0, albumSource, now = 1).book
        bridge.sync(book, findMoved)
        val uids = port.loaded
        port.calls.clear()

        moved = true
        bridge.sync(book, findMoved)
        assertEquals(listOf("replace 2 ${elsewhere.path}"), port.calls)
        assertEquals(uids, port.loaded)
        // The queue taking the new path too (the next library read) changes nothing more.
        port.calls.clear()
        bridge.sync(book.refresh { ref -> if (ref.path == songs[2].path) elsewhere.ref else ref }, findMoved)
        assertEquals(emptyList<String>(), port.calls)
    }

    // Playback resumption (a headset's or a car's play after KJB was closed)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `resumption hands over the queue once the queues and the library are read`() = runTest {
        val answer = resumption(backgroundScope, timeoutMs = 10_000, none = "none", ready = { true }) { "queue" }
        runCurrent()
        assertTrue(answer.isDone)
        assertEquals("queue", answer.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `resumption answers with nothing when the library cannot be read, takes too long, or the service ends`() = runTest {
        val unreadable = resumption(backgroundScope, timeoutMs = 10_000, none = "none", ready = { false }) { "queue" }
        runCurrent()
        assertTrue(unreadable.isDone)
        assertEquals("none", unreadable.get())

        val slow = resumption(backgroundScope, timeoutMs = 10_000, none = "none", ready = { awaitCancellation() }) { "queue" }
        advanceTimeBy(10_001)
        assertTrue(slow.isDone)
        assertEquals("none", slow.get())

        val service = CoroutineScope(coroutineContext + Job())
        val ended = resumption(service, timeoutMs = 10_000, none = "none", ready = { awaitCancellation() }) { "queue" }
        runCurrent()
        service.cancel()
        runCurrent()
        assertTrue(ended.isDone)
        assertEquals("none", ended.get())
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
