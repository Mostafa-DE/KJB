package app.lyrify.player

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Every queue keeps its place (its song, and the position in it to the millisecond of the test's clock) and its songs
 * while another one plays: left for a song tapped in any list, edited while away, through several switches, with its
 * Shuffle and the app's Repeat, and across closing and reopening KJB. Run through [PlayerCore], the real [QueueBridge]
 * and a made-up player that moves on with the test's clock, wired as PlaybackService wires them. Made-up names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuePlaceTest {
    private val library: List<Song> = (1..12).map { song("Music/Night Ferry/${"%02d".format(it)} Low Water $it.mp3", album = "Night Ferry", track = it, durationMs = 300_000) }
    private val songs = library.map { it.ref }
    private val songsTab = QueueSource(SourceKind.SONGS, "songs", "")
    private val album = QueueSource(SourceKind.ALBUM, "album:night ferry", "Night Ferry")
    private val search = QueueSource(SourceKind.SEARCH, "search:water", "water")
    private val picked = QueueSource(SourceKind.PICKED, "picked:1", "")

    /** `queues.json` and `playback.json` in memory, kept across a restart and read back as [QueueFiles] reads them. */
    private class Files : QueueStorage {
        var book = QueueBook()
        var place: PlaybackPlace? = null

        override suspend fun load(): Pair<QueueBook, Repeat> = withPlace(book, place) to (place?.repeat ?: Repeat.OFF)

        override suspend fun saveBook(book: QueueBook) {
            this.book = book
        }

        override suspend fun savePlace(place: PlaybackPlace) {
            this.place = place
        }
    }

    /**
     * A made-up player: the songs it holds by uid, the current one, and a position that moves on with [clock] while it
     * plays. Like ExoPlayer, it goes on with the next song from its start when its current one is removed; each song it
     * starts is told to the core and its place saved, as PlaybackService's Follower does.
     */
    private class FakePlayer(private val core: PlayerCore, private val clock: () -> Long) : PlayerPort {
        override var loaded: List<Long> = emptyList()
            private set
        override var index: Int = -1
            private set
        val playing = MutableStateFlow(false)
        private var heldMs = 0L
        private var since = 0L

        override val positionMs: Long get() = heldMs + if (playing.value) clock() - since else 0

        /** Where it is, as the service hands it to the core ([PlayerCore.follow]). */
        val at: PlayerAt? get() = loaded.getOrNull(index)?.let { PlayerAt(it, positionMs) }

        override fun load(items: List<Projected>, index: Int, positionMs: Long) {
            val before = loaded.getOrNull(this.index)
            loaded = items.map { it.uid }
            go(index, positionMs, before)
        }

        override fun apply(edit: Edit) {
            val before = loaded.getOrNull(index)
            val list = loaded.toMutableList()
            when (edit) {
                is Edit.Remove -> list.subList(edit.at, edit.at + edit.count).clear()
                is Edit.Insert -> list.addAll(edit.at, edit.items.map { it.uid })
                is Edit.Move -> list.add(edit.to, list.removeAt(edit.from))
                is Edit.Replace -> list[edit.at] = edit.item.uid
            }
            loaded = list
            if (before != null && before in list) index = list.indexOf(before) else go(index.coerceAtMost(list.lastIndex), 0, before)
        }

        override fun seek(index: Int, positionMs: Long) = go(index, positionMs, loaded.getOrNull(this.index))

        fun play() {
            hold()
            playing.value = true
        }

        fun pause() {
            hold()
            playing.value = false
        }

        private fun hold() {
            heldMs = positionMs
            since = clock()
        }

        private fun go(index: Int, positionMs: Long, before: Long?) {
            this.index = index
            heldMs = positionMs
            since = clock()
            val uid = loaded.getOrNull(index) ?: return
            if (uid != before) {
                core.change { it.at(uid, core.now()) }
                core.savePlace(uid, this.positionMs)
            }
        }
    }

    /**
     * KJB running over [files]: the queues ([core]) and the playback service with its [player], until [close] (the
     * service's last word, then the process ends) or [kill] (the process ends with no last word). The user's actions are
     * the ones the screens send, each followed by the service applying the change.
     */
    private inner class Kjb(private val test: TestScope, files: Files) {
        private val life = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
        val core = PlayerCore(files, life, wall = { test.currentTime })
        val player = FakePlayer(core, clock = { test.currentTime })
        private val bridge = QueueBridge(player, stop = player::pause)
        private val find: (SongRef) -> Song? = { ref -> library.firstOrNull { it.path == ref.path } }

        init {
            core.follow { player.at }
            life.launch {
                core.ready.first { it }
                core.book.collect { bridge.sync(it, find) }
            }
            life.launch { savePlaces(player.playing) { player.at?.let { core.savePlace(it.uid, it.positionMs) } } }
            test.runCurrent()
        }

        val book: QueueBook get() = core.book.value

        /** The path of the song the player holds now. */
        val song: String? get() = book.current?.items?.firstOrNull { it.uid == player.at?.uid }?.song?.path

        /** A tap on [list]`[at]` in a list from [source] (the Songs tab, an album, Search). */
        fun tap(list: List<SongRef>, at: Int, source: QueueSource) = act { core.switch { it.play(list, at, source, core.now()) } }

        fun resume(id: String) = act { core.switch { it.resume(id, core.now()) } }

        /** A tap on the song at [index] of queue [id]'s page. */
        fun jump(id: String, index: Int) = act { core.switch { it.jump(id, index, core.now()) } }

        /** "New queue" in Add to: a saved queue named [name], waiting. */
        fun newQueue(name: String, list: List<SongRef>): String =
            core.edit { book -> book.newQueue(name, list, picked, core.now())?.let { it.book to it.book.saved.first() } }!!.also { test.runCurrent() }

        /** The phone moved the songs at [paths] to its trash, after its dialog's OK (dupes/SongTrash.kt). */
        fun trash(paths: Set<String>) {
            core.trashed(paths)
            test.runCurrent()
        }

        fun listen(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        fun pause() {
            player.pause()
            test.runCurrent()
        }

        fun close() {
            player.at?.let { core.savePlaceNow(it.uid, it.positionMs) }
            core.follow(null)
            kill()
        }

        fun kill() {
            life.cancel()
            test.runCurrent()
        }

        private fun act(change: () -> Unit) {
            change()
            player.play()
            test.runCurrent()
        }
    }

    /** Queue [id] has the songs [items] (uids and paths, in order), the song [index] current, [positionMs] in. */
    private fun assertPlace(book: QueueBook, id: String, items: List<QueueItem>, index: Int, positionMs: Long) {
        val queue = book.queue(id)!!
        assertEquals(items.map { it.uid to it.song.path }, queue.items.map { it.uid to it.song.path })
        assertEquals(index, queue.index)
        assertEquals(positionMs, queue.positionMs)
    }

    /** The player holds queue [id] at the song with [uid], [positionMs] in, and the book has that queue playing there. */
    private fun assertPlaying(kjb: Kjb, id: String, uid: Long, positionMs: Long) {
        assertEquals(id, kjb.book.currentId)
        assertEquals(projection(kjb.book.current) { ref -> library.firstOrNull { it.path == ref.path } }.map { it.uid }, kjb.player.loaded)
        assertEquals(PlayerAt(uid, positionMs), kjb.player.at)
        assertEquals(uid, kjb.book.current!!.current!!.uid)
        assertTrue(kjb.player.playing.value)
    }

    @Test
    fun `a saved queue left for a song tapped in any list keeps its song and position to the millisecond, and Resume goes on from there`() = runTest {
        val kjb = Kjb(this, Files())
        val trip = kjb.newQueue("Road trip", listOf(songs[1], songs[4], songs[6], songs[9]))
        kjb.resume(trip)
        kjb.jump(trip, 2)
        kjb.listen(83_456)
        val items = kjb.book.queue(trip)!!.items

        for ((at, source) in listOf(7 to songsTab, 3 to album, 0 to search)) {
            kjb.tap(songs, at, source)
            // The tapped song plays straight away, from its start, in a new queue.
            assertNotEquals(trip, kjb.book.currentId)
            assertEquals(songs[at].path, kjb.song)
            assertEquals(0L, kjb.player.positionMs)
            assertTrue(kjb.player.playing.value)
            kjb.listen(20_000)
            assertPlace(kjb.book, trip, items, 2, 83_456)
            assertTrue(trip in kjb.book.saved)
            assertFalse(trip in kjb.book.recent)
        }

        kjb.resume(trip)
        assertPlaying(kjb, trip, items[2].uid, 83_456)
    }

    @Test
    fun `a saved queue that came from the Songs tab is left, not jumped inside, by a tap in the Songs tab`() = runTest {
        val kjb = Kjb(this, Files())
        kjb.tap(songs, 0, songsTab)
        kjb.listen(5_000)
        val kept = kjb.book.currentId!!
        kjb.tap(songs, 2, album)
        kjb.core.change { it.keep(kept) }
        kjb.resume(kept)
        kjb.listen(40_000)
        val items = kjb.book.queue(kept)!!.items

        kjb.tap(songs, 7, songsTab)
        assertNotEquals(kept, kjb.book.currentId)
        assertEquals(songs[7].path, kjb.song)
        assertPlace(kjb.book, kept, items, 0, 45_000)
        // The header's Play of the Songs tab is a tap on its first song: the saved queue stays where it was too.
        kjb.listen(30_000)
        kjb.resume(kept)
        kjb.listen(1_000)
        kjb.tap(songs, 0, songsTab)
        assertNotEquals(kept, kjb.book.currentId)
        assertPlace(kjb.book, kept, items, 0, 46_000)

        kjb.resume(kept)
        assertPlaying(kjb, kept, items[0].uid, 46_000)
    }

    @Test
    fun `through several switches each saved queue keeps its own place, A then a song then B then a song then Resume A`() = runTest {
        val kjb = Kjb(this, Files())
        val a = kjb.newQueue("Road trip", listOf(songs[0], songs[2], songs[4], songs[6]))
        val b = kjb.newQueue("Sunday chores", listOf(songs[1], songs[3], songs[5], songs[7]))
        val aItems = kjb.book.queue(a)!!.items
        val bItems = kjb.book.queue(b)!!.items

        kjb.resume(a)
        kjb.jump(a, 1)
        kjb.listen(30_250)
        kjb.tap(songs, 9, songsTab)
        kjb.listen(15_000)
        kjb.resume(b)
        kjb.listen(12_345)
        kjb.tap(songs.take(6), 5, search)
        kjb.listen(8_000)
        assertPlace(kjb.book, a, aItems, 1, 30_250)
        assertPlace(kjb.book, b, bItems, 0, 12_345)

        kjb.resume(a)
        assertPlaying(kjb, a, aItems[1].uid, 30_250)
        kjb.listen(1_000)
        kjb.resume(b)
        assertPlaying(kjb, b, bItems[0].uid, 12_345)
        kjb.resume(a)
        assertPlaying(kjb, a, aItems[1].uid, 31_250)
    }

    @Test
    fun `closing and reopening KJB keeps the place of the queue playing and of every queue left`() = runTest {
        val files = Files()
        var kjb = Kjb(this, files)
        val trip = kjb.newQueue("Road trip", songs.take(6))
        kjb.resume(trip)
        kjb.jump(trip, 3)
        kjb.listen(61_789)
        val items = kjb.book.queue(trip)!!.items
        kjb.tap(songs, 5, songsTab)
        kjb.listen(12_000)
        kjb.pause()
        kjb.listen(30_000)
        val tapped = kjb.book.currentId!!
        val tappedUid = kjb.player.at!!.uid
        kjb.close()

        kjb = Kjb(this, files)
        assertEquals(tapped, kjb.book.currentId)
        assertEquals(PlayerAt(tappedUid, 12_000), kjb.player.at)
        assertPlace(kjb.book, trip, items, 3, 61_789)
        kjb.resume(trip)
        assertPlaying(kjb, trip, items[3].uid, 61_789)

        // Paused, then the process ends with no last word: the pause wrote the place.
        kjb.listen(4_321)
        kjb.pause()
        kjb.kill()
        kjb = Kjb(this, files)
        assertEquals(trip, kjb.book.currentId)
        assertEquals(PlayerAt(items[3].uid, 66_110), kjb.player.at)
        assertPlace(kjb.book, trip, items, 3, 66_110)
    }

    @Test
    fun `Resume brings back the queue's Shuffle with its order and its place, and Repeat as it was`() = runTest {
        val kjb = Kjb(this, Files())
        val trip = kjb.newQueue("Road trip", songs.take(8))
        kjb.resume(trip)
        kjb.core.change { it.toggleShuffle(Random(7)) }
        kjb.core.setRepeat(Repeat.ONE)
        runCurrent()
        kjb.jump(trip, 3)
        kjb.listen(47_500)
        val shuffled = kjb.book.queue(trip)!!
        assertTrue(shuffled.shuffled)

        kjb.tap(songs, 0, songsTab)
        assertFalse(kjb.book.current!!.shuffled)
        kjb.listen(10_000)
        kjb.resume(trip)

        assertTrue(kjb.book.current!!.shuffled)
        assertEquals(shuffled.unshuffled, kjb.book.current!!.unshuffled)
        assertPlaying(kjb, trip, shuffled.items[3].uid, 47_500)
        assertEquals(shuffled.items.map { it.uid }, kjb.player.loaded)
        assertEquals(Repeat.ONE, kjb.core.repeat.value)
        assertTrue(sessionView(kjb.player, playing = true, kjb.book, kjb.core.repeat.value).shuffled)
    }

    @Test
    fun `edits keep a queue's place, while it plays and while another one does, and it never holds a song twice`() = runTest {
        val kjb = Kjb(this, Files())
        val trip = kjb.newQueue("Road trip", songs.take(6))
        kjb.resume(trip)
        kjb.jump(trip, 2)
        kjb.listen(20_000)
        val current = kjb.book.current!!.items[2].uid
        // While it plays: a song added at the end, one moved before the song playing; the song plays on.
        kjb.core.edit { book -> book.addTo(trip, listOf(songs[8]))?.let { it.book to it } }
        kjb.core.change { it.move(trip, 5, 0) }
        runCurrent()
        kjb.listen(13_000)
        assertPlaying(kjb, trip, current, 33_000)

        kjb.tap(songs, 9, songsTab)
        val tapped = kjb.book.currentId!!
        val added = kjb.core.edit { book -> book.addTo(trip, listOf(songs[2], songs[10]))?.let { it.book to it } }!!
        assertEquals(1, added.added)
        assertEquals(1, added.already)
        val first = kjb.book.queue(trip)!!.items.first().uid
        kjb.core.edit { book -> book.remove(trip, first)?.let { it.book to it } }
        kjb.core.change { it.move(trip, 0, 3) }
        runCurrent()
        val queue = kjb.book.queue(trip)!!
        assertEquals(queue.items.map { it.song.path }.distinct(), queue.items.map { it.song.path })
        assertTrue(songs[10].path in queue.items.map { it.song.path })
        assertEquals(current, queue.current!!.uid)
        assertEquals(33_000L, queue.positionMs)
        // The queue playing was not touched.
        assertEquals(tapped, kjb.book.currentId)
        assertEquals(songs[9].path, kjb.song)

        kjb.resume(trip)
        assertPlaying(kjb, trip, current, 33_000)
    }

    @Test
    fun `a song moved to the trash while it plays gives way to the next one, and the last one stops the player at the queue's first song`() = runTest {
        val kjb = Kjb(this, Files())
        kjb.tap(songs.take(4), 1, album)
        kjb.listen(30_000)

        kjb.trash(setOf(songs[1].path, songs[2].path))
        // The next song left plays at once, from its start.
        assertEquals(songs[3].path, kjb.song)
        assertEquals(0L, kjb.player.positionMs)
        assertTrue(kjb.player.playing.value)
        assertEquals(listOf(songs[0].path, songs[3].path), kjb.book.current!!.items.map { it.song.path })
        assertEquals(kjb.book.current!!.items.map { it.uid }, kjb.player.loaded)

        kjb.listen(10_000)
        kjb.trash(setOf(songs[3].path))
        // No song after it: the player stops, at the queue's first song.
        assertEquals(songs[0].path, kjb.song)
        assertEquals(0L, kjb.player.positionMs)
        assertFalse(kjb.player.playing.value)
        assertEquals(0, kjb.book.current!!.index)
        assertEquals(listOf(kjb.book.current!!.items.single().uid), kjb.player.loaded)
    }
}
