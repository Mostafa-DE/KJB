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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * A player that keeps a list of uids, as ExoPlayer keeps media items: removing the current item makes the next one
     * current. [taken] counts the edits that took the song it was on: Media3 1.11.1 then goes on with the song after it,
     * or with none ends there (removeMediaItemsInternal's STATE_ENDED), which the service hears as the queue played to
     * its end.
     */
    private class FakePort : PlayerPort {
        override var loaded: List<Long> = emptyList()
        override var index: Int = -1
        override var positionMs: Long = 0
        val calls = mutableListOf<String>()
        var taken: Int = 0

        /** The path it was handed for each song, by uid. */
        val paths = mutableMapOf<Long, String>()

        /** The size of each batch handed over. */
        val batches = mutableListOf<Int>()

        override fun load(items: List<Projected>, index: Int, positionMs: Long) {
            loaded = items.map { it.uid }
            items.forEach { paths[it.uid] = it.song.path }
            this.index = index
            this.positionMs = positionMs
            calls += "load"
        }

        override fun applyAll(edits: List<Edit>) {
            batches += edits.size
            edits.forEach(::apply)
        }

        override fun apply(edit: Edit) {
            val current = loaded.getOrNull(index)
            val list = loaded.toMutableList()
            when (edit) {
                is Edit.Remove -> {
                    if (current in list.subList(edit.at, edit.at + edit.count)) taken++
                    list.subList(edit.at, edit.at + edit.count).clear()
                }
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

    /**
     * A player as ExoPlayer is one across its two threads (CR2). [loaded] and [index] are what the main thread sees: its
     * own edits show at once, and the song it sees playing stays current through them. [playing] is the song the playback
     * thread plays: at a song's end it goes on by itself ([moveOn]), and the main thread hears of that later. An edit
     * taking that song away makes the playback thread go on, as Media3 1.11.1 does (`resolveSubsequentPeriod`), with the
     * first song after it, in the list as it was before that edit, that the edit left, from its start; with none, playback
     * ends ([playing] null). A song taken out and put in again is another song to it. [beforeBatch] runs as a batch comes
     * in: a song ending between the plan and its edits.
     */
    private class ThreadedPort(private val repeatAll: Boolean = false) : PlayerPort {
        override var loaded: List<Long> = emptyList()
        override var index: Int = -1
        override val positionMs: Long = 0

        /** The song the playback thread plays; null once playback ended. */
        var playing: Long? = null

        /** Whether an edit made the playback thread start a song from its start. */
        var restarted: Boolean = false
        var beforeBatch: () -> Unit = {}

        override fun load(items: List<Projected>, index: Int, positionMs: Long) {
            loaded = items.map { it.uid }
            this.index = index
            playing = loaded.getOrNull(index)
        }

        override fun seek(index: Int, positionMs: Long) {
            this.index = index
            playing = loaded.getOrNull(index)
        }

        /** The song playing ended on the playback thread: the next one plays, the first after the last under Repeat all. */
        fun moveOn() {
            playing = loaded.getOrNull(loaded.indexOf(playing) + 1) ?: loaded.firstOrNull()?.takeIf { repeatAll }
        }

        override fun applyAll(edits: List<Edit>) {
            beforeBatch()
            beforeBatch = {}
            edits.forEach(::apply)
        }

        override fun apply(edit: Edit) {
            val before = loaded
            val seen = before.getOrNull(index)
            val list = before.toMutableList()
            // A Replace puts the new copy in and takes the old one out, as Media3 does for another file.
            val gone = when (edit) {
                is Edit.Remove -> list.subList(edit.at, edit.at + edit.count).toSet().also { list.subList(edit.at, edit.at + edit.count).clear() }
                is Edit.Insert -> emptySet<Long>().also { list.addAll(edit.at, edit.items.map { it.uid }) }
                is Edit.Move -> emptySet<Long>().also { list.add(edit.to, list.removeAt(edit.from)) }
                is Edit.Replace -> setOf(list[edit.at])
            }
            loaded = list
            index = if (seen != null && seen in list) list.indexOf(seen) else index.coerceAtMost(list.lastIndex)
            val now = playing ?: return
            if (now !in gone) return
            restarted = true
            if (edit is Edit.Replace) return
            val at = before.indexOf(now)
            playing = (at + 1 until at + before.size).asSequence()
                .takeWhile { repeatAll || it < before.size }
                .map { before[it % before.size] }
                .firstOrNull { it in list }
        }
    }

    /** A queue long enough that Shuffle takes more than [MAX_EDITS] edits, so the player is rebuilt around the song playing. */
    private val shelf: List<Song> = (1..40).map { song("Music/Long Shelf/%02d Paper Lantern %d.mp3".format(it, it), album = "Long Shelf", track = it) }
    private val findOnShelf: (SongRef) -> Song? = { ref -> shelf.firstOrNull { it.path == ref.path } }

    private val port = FakePort()
    private var repeat = Repeat.OFF
    private val bridge = QueueBridge(port, { repeat }) { port.calls += "stop" }

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

    /** CR2: before CR1 an empty edit list made no engine calls; a batch of none would drop the crossfade's song waiting. */
    @Test
    fun `a change that leaves the player's songs as they are - a rename, a tap inside the queue - hands the player no batch`() {
        var book = QueueBook().play(refs, 0, albumSource, now = 1).book
        bridge.sync(book, find)
        book = book.rename(book.currentId!!, "Evening Set")
        bridge.sync(book, find)
        book = book.play(refs, 5, albumSource, now = 2).book
        bridge.sync(book, find)
        assertEquals(emptyList<Int>(), port.batches)
        assertEquals(listOf("load", "seek"), port.calls)
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
    fun `the song playing moved to the trash with none after it stops the player before it moves, once`() {
        var book = QueueBook().play(refs.take(3), 2, albumSource, now = 1).book
        bridge.sync(book, find)
        port.positionMs = 20_000
        book = book.forget(setOf(refs[2].path))
        bridge.sync(book, find)
        // 3.1 (PL1): the player moves to the queue's song before the edit, never left on the song that goes.
        assertEquals(listOf("load", "stop", "seek", "edit"), port.calls)
        assertEquals(uidsOf(book), port.loaded)
        assertEquals(book.current!!.items.first().uid, port.current)
        assertEquals(0L, port.positionMs)
        // The same book again, or a queue loaded afresh, stops nothing.
        bridge.sync(book.place(1, 5_000), find)
        QueueBridge(port) { port.calls += "stop" }.sync(book, find)
        assertEquals(1, port.calls.count { it == "stop" })
    }

    /**
     * 3.1 (PL1): an edit taking the song the player is on made ExoPlayer go on with the next one, or with none end there
     * (STATE_ENDED), which the service took for the queue played to its end: that song's play heard to its end, a book's
     * last part finishing the book. The player now moves to the queue's song first, and only then the songs change.
     */
    @Test
    fun `the song playing taken out never goes from under the player, which moves to the queue's song first`() {
        var book = QueueBook().play(refs.take(6), 1, albumSource, now = 1).book
        bridge.sync(book, find)
        port.positionMs = 20_000
        // Remove from queue.
        book = book.remove(book.currentId!!, book.current!!.current!!.uid)!!.book
        bridge.sync(book, find)
        assertEquals(listOf("load", "seek", "edit"), port.calls)
        assertEquals(book.current!!.current!!.uid, port.current)
        assertEquals(0L, port.positionMs)
        // The trash, and a controller's remove.
        book = book.forget(setOf(book.current!!.current!!.song.path))
        bridge.sync(book, find)
        assertEquals(book.current!!.current!!.uid, port.current)
        book = book.removeUids(book.currentId!!, setOf(book.current!!.current!!.uid))
        bridge.sync(book, find)
        assertEquals(book.current!!.current!!.uid, port.current)
        assertEquals(uidsOf(book), port.loaded)
        assertEquals(0, port.taken)
        // A song after it each time: playing goes on.
        assertFalse("stop" in port.calls)
    }

    @Test
    fun `the song playing taken out with none after it stops the player at the queue's song, never as if it had played to its end`() {
        // Remove from queue: the song before it waits, paused.
        var book = QueueBook().play(refs.take(4), 3, albumSource, now = 1).book
        bridge.sync(book, find)
        port.positionMs = 20_000
        book = book.remove(book.currentId!!, book.current!!.current!!.uid)!!.book
        bridge.sync(book, find)
        assertEquals(listOf("load", "stop", "seek", "edit"), port.calls)
        assertEquals(book.current!!.current!!.uid, port.current)
        assertEquals(0L, port.positionMs)

        // A controller's remove of the last song, then its file gone from the phone: the queue's first song waits.
        book = book.removeUids(book.currentId!!, setOf(book.current!!.current!!.uid))
        bridge.sync(book, find)
        assertEquals(book.current!!.current!!.uid, port.current)
        missing += book.current!!.current!!.song.path
        bridge.sync(book, find)
        assertEquals(projection(book.current, find).single().uid, port.current)
        assertEquals(3, port.calls.count { it == "stop" })
        assertEquals(0, port.taken)

        // A book's last part: the same, so the book is never finished by it.
        val parts = QueueSource(SourceKind.BOOK, "book:harbour", "Harbour Glass")
        val bookPort = FakePort()
        val bookBridge = QueueBridge(bookPort, { Repeat.ALL }) { bookPort.calls += "stop" }
        var reading = QueueBook().play(refs.take(4), 3, parts, now = 1).book
        bookBridge.sync(reading, find)
        reading = reading.remove(reading.currentId!!, reading.current!!.current!!.uid)!!.book
        bookBridge.sync(reading, find)
        assertEquals(listOf("load", "stop", "seek", "edit"), bookPort.calls)
        assertEquals(0, bookPort.taken)
    }

    @Test
    fun `with nothing after the song playing that leaves, Repeat one stops the player too and Repeat all goes round`() {
        // Repeat one: no next song, as the player's Next reads it.
        repeat = Repeat.ONE
        var book = QueueBook().play(refs.take(6), 5, albumSource, now = 1).book
        bridge.sync(book, find)
        book = book.forget(setOf(refs[5].path))
        bridge.sync(book, find)
        assertEquals(listOf("load", "stop", "seek", "edit"), port.calls)
        // Every song after it moved too.
        book = book.jump(book.currentId!!, 2, now = 2).book
        bridge.sync(book, find)
        book = book.forget(refs.drop(2).map { it.path }.toSet())
        bridge.sync(book, find)
        assertEquals(2, port.calls.count { it == "stop" })

        // Repeat all goes on with the first song, playing.
        repeat = Repeat.ALL
        book = QueueBook().play(refs.take(6), 5, albumSource, now = 3).book
        val round = FakePort()
        val bridge = QueueBridge(round, { repeat }) { round.calls += "stop" }
        bridge.sync(book, find)
        book = book.forget(setOf(refs[5].path))
        bridge.sync(book, find)
        assertEquals(listOf("load", "seek", "edit"), round.calls)
        assertEquals(book.current!!.items.first().uid, round.current)
        assertEquals(0, port.taken + round.taken)
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

    /** 3.1: Shuffle moves the song playing to the top; the player keeps playing it, never loading or seeking. */
    @Test
    fun `Shuffle with a song playing far into a long queue moves it first, and it goes on from where it is`() {
        var book = QueueBook().play(shelf.map { it.ref }, 25, albumSource, now = 1).book
        bridge.sync(book, findOnShelf)
        port.positionMs = 95_000
        val playing = port.current
        book = book.toggleShuffle(Random(9))
        bridge.sync(book, findOnShelf)
        assertEquals(projection(book.current, findOnShelf).map { it.uid }, port.loaded)
        assertEquals(0, port.index)
        assertEquals(playing, port.current)
        assertEquals(95_000L, port.positionMs)
        assertEquals(listOf("load"), port.calls.filter { it != "edit" })
    }

    /** CR2 (Codex review P1 on CR1): the main thread's place is behind the playback thread's at a song's end. */
    @Test
    fun `a Shuffle whose edits come just after the song playing ended keeps the song the player went on to playing`() {
        val threads = ThreadedPort()
        val bridge = QueueBridge(threads)
        var book = QueueBook().play(shelf.map { it.ref }, 9, albumSource, now = 1).book
        bridge.sync(book, findOnShelf)
        val next = threads.loaded[10]
        // Song 10 ends on the playback thread as the edits come in; the main thread still sees it playing.
        threads.beforeBatch = threads::moveOn
        book = book.toggleShuffle(Random(3))
        bridge.sync(book, findOnShelf)
        assertEquals("the song playing", next, threads.playing)
        assertFalse("started again", threads.restarted)
        assertEquals(projection(book.current, findOnShelf).map { it.uid }, threads.loaded)
    }

    @Test
    fun `a large change whose edits come just after the song playing ended never stops the song the player went on to`() {
        val random = Random(23)
        repeat(400) { case ->
            val from = (1L..60L).shuffled(random).take(random.nextInt(30, 60))
            val repeatAll = random.nextBoolean()
            val at = if (random.nextInt(4) == 0) from.lastIndex else random.nextInt(from.size)
            val playing = from[at]
            val threads = ThreadedPort(repeatAll).apply { load(from.map { Projected(it, songs[0]) }, at, 0) }
            threads.moveOn()
            // The last song ended with Repeat off: playback is over, and no edit starts it again.
            val next = threads.playing ?: return@repeat
            val to = ((from - playing).filter { random.nextInt(5) != 0 } + (100L..110L).filter { random.nextBoolean() } + playing).shuffled(random)
            val projected = to.map { Projected(it, songs[0]) }
            assertNull("case $case is a large change", edits(from, projected, MAX_EDITS))
            val plan = plan(from, projected, playing, from.associateWith { songs[0].path })!!
            assertTrue("case $case: ${plan.size} edits", plan.size <= MAX_EDITS)
            threads.applyAll(plan)
            assertEquals("case $case: the player's songs", to, threads.loaded)
            assertEquals("case $case: the song the main thread sees", playing, threads.loaded[threads.index])
            if (next in to) {
                assertEquals("case $case: the song playing", next, threads.playing)
                assertFalse("case $case: started again", threads.restarted)
            } else {
                // The song it went on to left the queue: on with the song after the one playing in the new order.
                val after = to.getOrNull(to.indexOf(playing) + 1) ?: to.first().takeIf { repeatAll }
                assertEquals("case $case: the song after the one that left", after, threads.playing)
            }
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
