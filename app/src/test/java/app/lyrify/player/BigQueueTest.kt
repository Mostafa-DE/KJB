package app.lyrify.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A library-sized queue through the bridge (CR1). KJB ran out of memory when Shuffle reached a queue of 6,906 songs as
 * one move per song: each edit makes ExoPlayer build its whole list again (a timeline of every song it holds) on its
 * playback thread, and each of those waits on the main thread until the change is done. [CostPort] counts those lists;
 * a change may cost at most [LISTS] of them, whatever it is and however long the queue. Made-up names only.
 */
class BigQueueTest {
    /** The library: [QUEUED] songs the queue is made of, and more that Add to queue brings in. */
    private val songs: List<Song> = (1..LIBRARY).map { song("Music/Big Shelf/Artist %d/%04d Paper Lantern %d.mp3".format(it % 300, it, it), album = "Shelf ${it / 12}", track = it % 12 + 1) }
    private val byPath: Map<String, Song> = songs.associateBy { it.path }
    private val find: (SongRef) -> Song? = { ref -> byPath[ref.path] }
    private val source = QueueSource(SourceKind.SONGS, "songs", "Songs")
    private val picked = QueueSource(SourceKind.PICKED, "picked", "")

    /**
     * A player as ExoPlayer is one: removing its current song goes on with the next one from its start. Each load and
     * each edit builds the list of every song it then holds: [built] counts their songs, [edits] the edits.
     */
    private class CostPort : PlayerPort {
        override var loaded: List<Long> = emptyList()
        override var index: Int = -1
        override var positionMs: Long = 0
        var built = 0L
        var edits = 0
        val calls = mutableListOf<String>()

        override fun load(items: List<Projected>, index: Int, positionMs: Long) {
            loaded = items.map { it.uid }
            this.index = index
            this.positionMs = positionMs
            built += loaded.size
            calls += "load"
        }

        override fun apply(edit: Edit) {
            val current = loaded.getOrNull(index)
            val list = ArrayList(loaded)
            when (edit) {
                is Edit.Remove -> list.subList(edit.at, edit.at + edit.count).clear()
                is Edit.Insert -> list.addAll(edit.at, edit.items.map { it.uid })
                is Edit.Move -> list.add(edit.to, list.removeAt(edit.from))
                is Edit.Replace -> list[edit.at] = edit.item.uid
                is Edit.Refresh -> Unit
            }
            loaded = list
            val at = if (current == null) -1 else list.indexOf(current)
            if (at >= 0) {
                index = at
            } else {
                index = index.coerceAtMost(list.lastIndex)
                positionMs = 0
            }
            built += list.size
            edits++
            if (calls.size < 50) calls += if (edit is Edit.Insert) "Insert(at=${edit.at}, songs=${edit.items.size})" else edit.toString()
        }

        override fun seek(index: Int, positionMs: Long) {
            this.index = index
            this.positionMs = positionMs
            calls += "seek"
        }

        val current: Long? get() = loaded.getOrNull(index)
    }

    private var port = CostPort()
    private var bridge = QueueBridge(port)
    private var book = QueueBook()

    /** The longest one sync took, in ms. */
    private var slowestMs = 0L

    private fun sync(find: (SongRef) -> Song? = this.find) {
        val started = System.nanoTime()
        bridge.sync(book, find)
        slowestMs = maxOf(slowestMs, (System.nanoTime() - started) / 1_000_000)
    }

    /** The queue of the first [QUEUED] songs playing from song [start], [positionMs] in, loaded on the player. */
    private fun playing(start: Int = 3_000, positionMs: Long = 61_000) {
        book = book.play(songs.take(QUEUED).map { it.ref }, start, source, now = 1).book.place(start, positionMs)
        bridge.sync(book, find)
        port.calls.clear()
    }

    /**
     * [change] made to the book and synced: it costs the player at most [LISTS] lists of the queue, the player ends up
     * holding the queue's songs in order, and unless the song playing left the queue or the user moved in it (a jump), it
     * plays on where it was. What it cost, in lists of the queue.
     */
    private fun change(name: String, change: (QueueBook) -> QueueBook): Double {
        val playing = port.current
        val position = port.positionMs
        val builtBefore = port.built
        val jumps = book.jumps
        book = change(book)
        sync()
        val queue = book.current!!
        val size = maxOf(queue.items.size, 1)
        val lists = (port.built - builtBefore).toDouble() / size
        assertTrue("$name cost the player %.1f lists of the queue (${port.edits} edits so far)".format(lists), lists <= LISTS)
        assertEquals("$name: the player's songs", projection(queue, find).map { it.uid }, port.loaded)
        if (book.jumps != jumps) {
            assertEquals("$name: the song jumped to", queue.current!!.uid, port.current)
            assertEquals("$name: the place jumped to", queue.positionMs, port.positionMs)
        } else if (queue.items.any { it.uid == playing }) {
            assertEquals("$name: the song playing", playing, port.current)
            assertEquals("$name: the place in it", position, port.positionMs)
        } else {
            assertEquals("$name: the queue's song after the one that went", queue.current!!.uid, port.current)
        }
        return lists
    }

    private fun measured(name: String, change: (QueueBook) -> QueueBook) {
        val edits = port.edits
        val lists = change(name, change)
        println("CR1 measure: $name cost the player %.2f lists of the queue in ${port.edits - edits} edits".format(lists))
    }

    private val id: String get() = book.currentId!!

    /**
     * The crash as the user made it: a queue playing, a song tapped in the 7,000-song list while it plays (a new queue),
     * Shuffle on; then KJB starting again and loading that shuffled queue from `queues.json` at its place; then Shuffle
     * off and on 100 times, a Play next of 2,000 songs and its Undo. Each step costs the player a few lists of the queue
     * and little time, the song playing never changes or starts again, and the heap stays flat.
     */
    @Test
    fun `the crash - a song tapped in a 7,000-song list while one plays, then Shuffle, then a restart - stays small`() {
        book = book.play(songs.drop(LIBRARY - QUEUED).map { it.ref }, 100, picked, now = 1).book.place(100, 30_000)
        sync()
        port.calls.clear()
        val list = songs.take(QUEUED).map { it.ref }
        change("a song tapped in the list while another plays") { it.play(list, 3_000, source, now = 2).book.place(3_000, 12_000) }
        assertEquals(listOf("load"), port.calls)
        measured("Shuffle on") { it.toggleShuffle(Random(11)) }

        // KJB starts again: the queues and the place as stored, a new player and bridge.
        val stored = QueuesJson.decode(QueuesJson.encode(book.place(book.current!!.index, 95_000)))!!
        val playing = book.current!!.current!!.uid
        port = CostPort()
        bridge = QueueBridge(port)
        book = stored
        sync()
        assertEquals("a restart loads once", listOf("load"), port.calls)
        assertEquals("a restart builds one list of the queue", QUEUED.toLong(), port.built)
        assertEquals(projection(book.current, find).map { it.uid }, port.loaded)
        assertEquals(playing, port.current)
        assertEquals(95_000L, port.positionMs)

        val random = Random(13)
        var used = 0L
        repeat(100) { round ->
            change("Shuffle ${if (book.current!!.shuffled) "off" else "on"}, round $round") { it.toggleShuffle(random) }
            if (round == 9) used = usedAfterGc()
        }
        var before: List<QueueItem> = emptyList()
        measured("Play next of 2,000 songs") { book -> book.playNext(list.takeLast(2_000), picked, now = 3)!!.also { before = it.before }.book }
        measured("its Undo") { it.restore(id, before) }
        val grown = usedAfterGc() - used
        println("CR1 measure: the crash sequence: the heap grew by ${grown / 1024} KB over 90 Shuffles, the slowest sync took $slowestMs ms")
        assertTrue("the heap grew by ${grown / 1024} KB over 90 Shuffles", grown < 4L * 1024 * 1024)
        assertTrue("a sync took $slowestMs ms", slowestMs < 2_000)
        assertEquals(playing, port.current)
        assertEquals(95_000L, port.positionMs)
        assertEquals(listOf("load"), port.calls.filter { it == "load" || it == "seek" })
    }

    @Test
    fun `Shuffle on and off for a library-sized queue costs the player a few lists of it, never one per song`() {
        playing()
        measured("Shuffle on") { it.toggleShuffle(Random(7)) }
        measured("Shuffle off") { it.toggleShuffle(Random(7)) }
        assertEquals(listOf<String>(), port.calls.filter { it == "load" || it == "seek" })
    }

    @Test
    fun `every kind of change to a library-sized queue costs the player a few lists of it, and the song playing goes on`() {
        playing()
        val queued = songs.take(QUEUED).map { it.ref }
        val more = songs.drop(QUEUED).map { it.ref }
        var before: List<QueueItem> = emptyList()
        measured("Play next of 2,000 songs played already") { book -> book.playNext(queued.take(2_000), picked, now = 2)!!.also { before = it.before }.book }
        measured("its Undo") { it.restore(id, before) }
        var added: List<Long> = emptyList()
        measured("Add to queue of 2,000 songs") { book -> book.addTo(id, more)!!.also { added = it.uids }.book }
        measured("its Undo") { it.removeUids(id, added) }
        measured("a song dragged from the top to the end") { it.move(id, 0, QUEUED - 1) }
        measured("every other song after the one playing taken out") { book -> book.removeUids(id, book.current!!.items.drop(book.current!!.index + 1).filterIndexed { i, _ -> i % 2 == 0 }.map { it.uid }) }
        measured("Shuffle on") { it.toggleShuffle(Random(3)) }
        measured("Play next of every song") { book -> book.playNext(book.current!!.items.map { it.song }, picked, now = 3)!!.book }
    }

    @Test
    fun `every file of a library-sized queue moved on the phone reaches the player at its new path in a few lists`() {
        playing()
        val moved = songs.associate { it.path to it.copy(ref = SongRef(it.path.replace("Music/Big Shelf/", "Music/Shelf Moved/"), it.ref.mediaStoreId)) }
        val findMoved: (SongRef) -> Song? = { ref -> moved[ref.path] ?: byPath[ref.path]?.let { moved.getValue(it.path) } }
        val builtBefore = port.built
        bridge.sync(book, findMoved)
        assertTrue("moving every file cost ${(port.built - builtBefore) / QUEUED} lists", port.built - builtBefore <= LISTS * QUEUED)
        assertEquals(projection(book.current, findMoved).map { it.uid }, port.loaded)
        assertEquals(book.current!!.current!!.uid, port.current)
        assertEquals(61_000L, port.positionMs)
    }

    @Test
    fun `a song dragged far down the queue is one move, and songs side by side leaving it are one edit`() {
        playing()
        book = book.move(id, 10, QUEUED - 10)
        bridge.sync(book, find)
        assertEquals(listOf("Move(from=10, to=${QUEUED - 10})"), port.calls)
        port.calls.clear()
        val block = book.current!!.items.subList(4_000, 6_500).map { it.uid }
        book = book.removeUids(id, block)
        bridge.sync(book, find)
        assertEquals(listOf("Remove(at=4000, count=2500)"), port.calls)
    }

    @Test
    fun `when the song playing leaves in a large change, the player loads the queue at its new current song`() {
        playing()
        change("the song playing and every song after it but the last taken out") { book ->
            book.removeUids(id, book.current!!.items.drop(book.current!!.index).dropLast(1).filterIndexed { i, _ -> i % 3 != 1 }.map { it.uid })
        }
        assertEquals(0L, port.positionMs)
    }

    @Test
    fun `the heap stays flat across 100 changes of a library-sized queue`() {
        playing()
        val queued = songs.take(QUEUED).map { it.ref }
        val random = Random(5)
        var used = 0L
        repeat(100) { round ->
            when (round % 5) {
                0 -> change("Shuffle on, round $round") { it.toggleShuffle(random) }
                1 -> change("Shuffle off, round $round") { it.toggleShuffle(random) }
                2 -> {
                    var before: List<QueueItem> = emptyList()
                    change("Play next of 500, round $round") { book -> book.playNext(queued.shuffled(random).take(500), picked, now = round.toLong())!!.also { before = it.before }.book }
                    change("its Undo, round $round") { it.restore(id, before) }
                }
                3 -> change("a drag, round $round") { it.move(id, random.nextInt(QUEUED), random.nextInt(QUEUED)) }
                else -> change("Play from start, round $round") { it.playFromStart(id, now = round.toLong()).book }
            }
            if (round == 9) used = usedAfterGc()
        }
        val grown = usedAfterGc() - used
        println("CR1 measure: the heap grew by ${grown / 1024} KB over 90 changes of a $QUEUED-song queue")
        assertTrue("the heap grew by ${grown / 1024} KB over 90 changes", grown < 4L * 1024 * 1024)
    }

    /**
     * Next and Previous in a library-sized queue, as the service runs them (PW2): the player moves, and the book follows it
     * (QueueBook.at, the service's Follower). A book that only moved its place ([movedOnly]) goes neither to the bridge nor
     * to the session, the own lyrics' sweep keeps its songs and picks the current one again ([ownSnapshot]), and every song
     * is as long to the session as the library says ([sessionLengthMs]), so the lengths its timeline is made of stay the
     * same and Media3 sends no queue. That the facade hands the session the very timeline it built before needs a Looper:
     * BigQueueDeviceTest checks it. Before PW2 each Next looked every song of the queue up twice, on the main thread, and
     * made a new timeline.
     */
    @Test
    fun `Next and Previous in a library-sized queue look up no song and keep the lengths of the session's timeline`() {
        playing()
        var finds = 0
        val counted: (SongRef) -> Song? = { ref ->
            finds++
            find(ref)
        }
        // The session's songs, each as long as the library says; decoded, each is a few ms longer.
        val held = projection(book.current, find).map { item ->
            MediaItem.Builder().setMediaId(item.uid.toString()).setMediaMetadata(MediaMetadata.Builder().setDurationMs(item.song.durationMs).build()).build()
        }
        val timeline = { held.mapIndexed { i, item -> sessionLengthMs(item, i == port.index, decodedMs = item.mediaMetadata.durationMs!! + 7) } }
        var snapshot = ownSnapshot(book.current, find)
        var shown = timeline()
        var timelines = 0
        val builtBefore = port.built
        val times = mutableListOf<Long>()
        repeat(SKIPS) { step ->
            val started = System.nanoTime()
            // Next, and every fourth time Previous.
            port.seek(if (step % 4 == 3) port.index - 1 else port.index + 1, 0)
            val before = book
            book = book.at(port.current!!, now = 10L + step)
            val moved = movedOnly(before, book)
            if (!moved) bridge.sync(book, counted)
            snapshot = ownSnapshot(book.current, counted, before = snapshot.takeIf { moved })
            times += System.nanoTime() - started
            val now = timeline()
            if (now != shown) timelines++
            shown = now
        }
        println(
            "PW2 measure: a Next in a $QUEUED-song queue looked up ${finds / SKIPS} songs and built ${(port.built - builtBefore) / SKIPS} list entries; " +
                "$timelines new timelines in $SKIPS skips; median %.3f ms for the steps the book and the sweep take".format(times.sorted()[SKIPS / 2] / 1e6),
        )
        assertEquals("songs looked up", 0, finds)
        assertEquals("list entries built", 0L, port.built - builtBefore)
        assertEquals("new timelines", 0, timelines)
        assertEquals(ownSnapshot(book.current, find), snapshot)
        assertEquals(book.current!!.current!!.uid, port.current)
    }

    /**
     * What a queued song costs while it waits, measured: its queue item, its place before Shuffle, what the player is
     * handed ([Projected]) and the media item the player holds, built as [mediaItemOf] builds it with its title, tags
     * and length (its two uris, the file's and the cover's, are measured on the phone: BigQueueDeviceTest). Never
     * artwork bytes: a cover is a uri the session reads one at a time.
     */
    @Test
    fun `a queued song costs the app well under 2 KB while it waits, and its media item holds no artwork bytes`() {
        val refs = songs.take(QUEUED).map { it.ref }
        val start = usedAfterGc()
        val queues = QueueBook().play(refs, 0, source, now = 1).book.toggleShuffle(Random(1))
        val projected = projection(queues.current, find)
        val items = projected.map { item ->
            val song = item.song
            MediaItem.Builder()
                .setMediaId(item.uid.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album).setAlbumArtist(song.albumArtist)
                        .setTrackNumber(song.track).setDiscNumber(song.disc).setReleaseYear(song.year).setGenre(song.genre).setDurationMs(song.durationMs)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsBrowsable(false).setIsPlayable(true).build(),
                )
                .build()
        }
        val perSong = (usedAfterGc() - start) / QUEUED
        println("CR1 measure: a queued song costs $perSong bytes (queue item, shuffle order, projection, media item; uris aside)")
        assertTrue("a queued song costs $perSong bytes", perSong < 2_048)
        assertTrue(items.none { it.mediaMetadata.artworkData != null })
        assertEquals(QUEUED, queues.current!!.unshuffled!!.size)
        assertEquals(QUEUED, projected.size)
    }

    private fun usedAfterGc(): Long {
        val runtime = Runtime.getRuntime()
        var used = Long.MAX_VALUE
        repeat(4) {
            System.gc()
            Thread.sleep(30)
            used = minOf(used, runtime.totalMemory() - runtime.freeMemory())
        }
        return used
    }

    private companion object {
        /** A library-sized queue: the crash's had 6,906 songs. */
        const val QUEUED = 7_000
        const val LIBRARY = 9_000

        /** What one change may cost the player, in lists of the queue's songs. */
        const val LISTS = 8.0

        /** Next and Previous presses measured. */
        const val SKIPS = 41
    }
}
