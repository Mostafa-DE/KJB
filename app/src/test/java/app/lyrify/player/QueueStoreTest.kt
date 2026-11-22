package app.lyrify.player

import app.lyrify.library.SongRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** `queues.json` and `playback.json`: every field stored reads back, and any old or broken file reads as much as it can. Made-up names only. */
class QueueStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logs = mutableListOf<String>()

    @After
    fun stop() = scope.cancel()

    private fun song(name: String, id: Long? = null) = SongRef("/storage/emulated/0/Music/$name.flac", id)

    private val album = (1..4).map { song("Paper Lantern $it", it.toLong()) }
    private val folderSongs = (1..3).map { song("Rips $it") }
    private val albumSource = QueueSource(SourceKind.ALBUM, "album:lantern", "Paper Lantern")
    private val folderSource = QueueSource(SourceKind.FOLDER, "folder:Music/Rips", "Rips")
    private val picked = QueueSource(SourceKind.PICKED, "picked", "")

    /** A book with a playing queue (shuffled, a Play next song waiting), a saved named queue and a recent one. */
    private fun book(): QueueBook {
        var book = QueueBook().play(album, 1, albumSource, now = 10).book
        book = book.play(folderSongs, 0, folderSource, now = 20).book.place(0, 4_000)
        book = book.keep(book.recent.single()).rename(book.recent.single(), "Sunday chores")
        book = book.play(listOf(song("Loose")), 0, QueueSource(SourceKind.SEARCH, "search", "loose"), now = 30).book
        book = book.resume(book.saved.single(), now = 40).book.toggleShuffle(Random(5))
        return book.playNext(listOf(song("Loose")), picked, now = 50)!!.book.place(1, 75_000)
    }

    @Test
    fun `everything stored reads back the same`() {
        val book = book()
        assertEquals(book.copy(jumps = 0), QueuesJson.decode(QueuesJson.encode(book)))
        assertEquals(QueueBook(), QueuesJson.decode(QueuesJson.encode(QueueBook())))
    }

    @Test
    fun `suggested songs and Smart shuffle read back, and a mark this version does not know reads as a plain song`() {
        val shuffled = book().cycleShuffle(Random(3))
        val queue = shuffled.current!!
        val smart = shuffled.suggest(queue.id, listOf(queue.items[0].uid to song("Glass Steps")), Suggested.SMART_SHUFFLE)
            .let { it.suggest(queue.id, listOf(it.current!!.items.last().uid to song("Copper Moon")), Suggested.AUTOPLAY) }
        assertEquals(smart.copy(jumps = 0), QueuesJson.decode(QueuesJson.encode(smart)))
        val text = QueuesJson.encode(smart).replace("\"SMART_SHUFFLE\"", "\"RADIO\"")
        val read = QueuesJson.decode(text)!!.current!!
        assertEquals(listOf(Suggested.AUTOPLAY), read.items.mapNotNull { it.suggested })
        assertTrue(read.smart)
    }

    @Test
    fun `a list of suggestions is stored as picked songs with a mark, so an older KJB keeps its songs`() {
        val forYou = QueueBook().play(listOf(song("Glass Steps"), song("Copper Moon", 4)), 1, QueueSource(SourceKind.SUGGESTIONS, "for-you", "For you"), now = 5).book
        val text = QueuesJson.encode(forYou)
        assertEquals(forYou.copy(jumps = 0), QueuesJson.decode(text))
        assertFalse("SUGGESTIONS" in text)
        // An older KJB knows no mark: it reads the songs a picked list, never drops them.
        val older = QueuesJson.decode(text.replace(Regex(""","?suggestions"?:true"""), ""))!!.current!!
        assertEquals(SourceKind.PICKED, older.source.kind)
        assertEquals(forYou.current!!.items, older.items)
    }

    @Test
    fun `a card a friend sent is stored as picked songs with a mark, so an older KJB keeps its songs`() {
        val card = QueueBook().play(listOf(song("Glass Steps"), song("Copper Moon", 4)), 0, QueueSource(SourceKind.HUDDLE, "card-1", "Wren"), now = 5).book
        val text = QueuesJson.encode(card)
        assertEquals(card.copy(jumps = 0), QueuesJson.decode(text))
        assertFalse("HUDDLE" in text)
        val older = QueuesJson.decode(text.replace(Regex(""","?huddle"?:true"""), ""))!!.current!!
        assertEquals(SourceKind.PICKED, older.source.kind)
        assertEquals(card.current!!.items, older.items)
    }

    @Test
    fun `a queue stored before start times were kept starts at its last play`() {
        val old = QueuesJson.encode(book()).replace(Regex(""","?startedAt"?:\d+"""), "")
        val read = QueuesJson.decode(old)!!
        assertEquals(book().queues.size, read.queues.size)
        assertTrue(read.queues.all { it.startedAt == it.updatedAt && it.startedAt > 0 })
    }

    @Test
    fun `queues stored with the old keys per sort, per search and per selection read as their one place`() {
        fun queue(id: Int, kind: String, key: String) =
            """{"id":"q$id","source":{"kind":"$kind","key":"$key","name":""},"items":[{"uid":$id,"path":"/storage/emulated/0/Music/$id.mp3","sourceIndex":0}]}"""
        val keys = listOf(
            "SONGS" to "songs:ARTIST:SYNCED", "SEARCH" to "search:paper lantern", "PICKED" to "picked:1731600000000",
            // New queues from Add to keep their own keys, and other lists keep theirs.
            "PICKED" to "picked:1731600000000#q4", "SEARCH" to "search:paper#q5", "SONGS" to "tab:albums", "SONGS" to "status:SYNCED:x",
        )
        val text = """{"schemaVersion":1,"recent":[${keys.indices.joinToString(",") { "\"q${it + 1}\"" }}],"queues":[${keys.mapIndexed { i, (kind, key) -> queue(i + 1, kind, key) }.joinToString(",")}]}"""
        assertEquals(
            listOf("songs", "search", "picked", "picked:1731600000000#q4", "search:paper#q5", "tab:albums", "status:SYNCED:x"),
            QueuesJson.decode(text)!!.queues.map { it.source.key },
        )
    }

    /** F2 kept a Songs tab list the other way round under its own key, "songs:<sort>-reversed:<filter>": the same one place. */
    @Test
    fun `a Songs queue stored under a reversed sort's old key reads as the Songs tab's one place`() {
        val text = """{"schemaVersion":1,"recent":["q1"],"queues":[{"id":"q1","source":{"kind":"SONGS","key":"songs:TITLE-reversed:ALL","name":""},""" +
            """"items":[{"uid":1,"path":"/storage/emulated/0/Music/1.mp3","sourceIndex":0}]}]}"""
        assertEquals(listOf(SongsTabSource.key), QueuesJson.decode(text)!!.queues.map { it.source.key })
    }

    @Test
    fun `missing parts read as empty, unknown keys are ignored, and only text that is no JSON object is not queues`() {
        assertEquals(QueueBook(), QueuesJson.decode("{}"))
        assertEquals(QueueBook(), QueuesJson.decode("""{"schemaVersion":9,"somethingNew":[1,2]}"""))
        assertNull(QueuesJson.decode("[]"))
        assertNull(QueuesJson.decode("not json"))
    }

    @Test
    fun `a song entry or a queue this version cannot read is dropped and counted, the rest read`() {
        val text = """
            {"schemaVersion":1,"current":"q1","recent":["q2","q9","q3"],"saved":["q3"],"nextId":2,"nextUid":3,
             "queues":[
              {"id":"q1","source":{"kind":"ALBUM","key":"album:lantern","name":"Paper Lantern"},"index":7,"positionMs":5000,"newField":true,
               "items":[{"uid":1,"path":"/storage/emulated/0/Music/a.mp3","sourceIndex":0},{"uid":2},{"uid":5,"path":"/storage/emulated/0/Music/a.mp3"},
                        {"uid":6,"path":"/storage/emulated/0/Music/b.mp3","mediaStoreId":12,"playNext":true}]},
              {"id":"q2","source":{"kind":"FUTURE_KIND","key":"x","name":"x"},"items":[{"uid":7,"path":"/storage/emulated/0/Music/c.mp3"}]},
              {"id":"q3","source":{"kind":"FOLDER","key":"folder:Music","name":"Music"},"name":"Road trip","unshuffled":[8,99],
               "items":[{"uid":8,"path":"/storage/emulated/0/Music/d.mp3"}]},
              {"id":"q4","source":{"kind":"SONGS","key":"songs","name":""},"items":[{"uid":9,"path":"/storage/emulated/0/Music/e.mp3"}]},
              {"id":"q5","source":{"kind":"SONGS","key":"songs","name":""},"items":[]},
              "a queue"
             ]}
        """.trimIndent()
        var dropped = 0
        val book = QueuesJson.decode(text) { dropped += it }!!
        // q1: the item without a path and the second copy of a.mp3 go; the index past the end is the last song.
        val q1 = book.queue("q1")!!
        assertEquals(listOf(1L, 6L), q1.items.map { it.uid })
        assertEquals(SongRef("/storage/emulated/0/Music/b.mp3", 12), q1.items[1].song)
        assertTrue(q1.items[1].playNext)
        assertEquals(1, q1.index)
        assertEquals(0L, q1.positionMs)
        // q2 (a kind this version does not know), q4 (in no list), q5 (no songs) and the string go.
        assertEquals(listOf("q1", "q3"), book.queues.map { it.id })
        assertEquals("q1", book.currentId)
        // Saved wins over Recent; names of queues not there are left out.
        assertEquals(listOf("q3"), book.saved)
        assertEquals(emptyList<String>(), book.recent)
        assertEquals("Road trip", book.queue("q3")!!.name)
        assertEquals(listOf(8L), book.queue("q3")!!.unshuffled)
        // Counters never hand out an id or uid already used.
        assertTrue(book.nextId > 4)
        assertTrue(book.nextUid > 9)
        assertTrue(dropped >= 6)
    }

    @Test
    fun `queues saved with Keep before read back whole and show among the others, by when each was left`() {
        val text = """
            {"schemaVersion":1,"current":"q3","recent":["q2"],"saved":["q1","q4"],"nextId":5,"nextUid":9,
             "queues":[
              {"id":"q1","source":{"kind":"FOLDER","key":"folder:Music/Rips","name":"Rips"},"name":"Sunday chores","index":1,"positionMs":42000,"updatedAt":100,
               "items":[{"uid":1,"path":"/storage/emulated/0/Music/a.mp3"},{"uid":2,"path":"/storage/emulated/0/Music/b.mp3"}]},
              {"id":"q2","source":{"kind":"SONGS","key":"songs","name":""},"updatedAt":300,"items":[{"uid":3,"path":"/storage/emulated/0/Music/c.mp3"}]},
              {"id":"q3","source":{"kind":"ALBUM","key":"album:lantern","name":"Paper Lantern"},"updatedAt":400,"items":[{"uid":4,"path":"/storage/emulated/0/Music/d.mp3"}]},
              {"id":"q4","source":{"kind":"PICKED","key":"picked#q4","name":""},"name":"Road trip","updatedAt":200,"items":[{"uid":5,"path":"/storage/emulated/0/Music/e.mp3"}]}
             ]}
        """.trimIndent()
        val book = QueuesJson.decode(text)!!
        assertEquals(listOf("q3", "q2", "q4", "q1"), book.listed.map { it.id })
        assertEquals("Sunday chores", book.queue("q1")!!.name)
        assertEquals(1, book.queue("q1")!!.index)
        assertEquals(42_000L, book.queue("q1")!!.positionMs)
        // Stored again, nothing about them changed: a backup or an older KJB reads them as before.
        assertEquals(listOf("q1", "q4"), book.saved)
        assertEquals(book, QueuesJson.decode(QueuesJson.encode(book)))
    }

    @Test
    fun `a current queue that is gone reads as nothing playing`() {
        val text = """{"current":"q8","queues":[{"id":"q1","source":{"kind":"SONGS","key":"songs","name":""},"items":[{"uid":1,"path":"/storage/emulated/0/a.mp3"}]}],"recent":["q1"]}"""
        val book = QueuesJson.decode(text)!!
        assertNull(book.currentId)
        assertEquals(listOf("q1"), book.recent)
    }

    // playback.json

    @Test
    fun `the place reads back, and anything it cannot use reads as no place or its default`() {
        val place = PlaybackPlace("q3", 7, 61_500, Repeat.ONE, at = 1_000)
        assertEquals(place, PlaybackJson.decode(PlaybackJson.encode(place)))
        assertEquals(PlaybackPlace(null, null, 0, Repeat.OFF), PlaybackJson.decode("""{"queueId":null}"""))
        assertEquals(PlaybackPlace("q1", null, 0, Repeat.OFF), PlaybackJson.decode("""{"queueId":"q1","uid":"x","positionMs":"x","repeat":"SOMETIMES","at":-1}"""))
        assertNull(PlaybackJson.decode("[1]"))
        assertNull(PlaybackJson.decode(""))
    }

    @Test
    fun `the place applies to the queue it names, at the song it names, when it is newer than the queue's own`() {
        val book = QueueBook().play(album, 0, albumSource, now = 10).book
        val id = book.currentId!!
        val third = book.current!!.items[2].uid
        val placed = withPlace(book, PlaybackPlace(id, third, 33_000, Repeat.ALL, at = 15))
        assertEquals(2, placed.current!!.index)
        assertEquals(33_000L, placed.current!!.positionMs)
        assertEquals(15L, placed.current!!.updatedAt)
        assertEquals(book, withPlace(book, PlaybackPlace("q99", third, 33_000, at = 15)))
        assertEquals(book, withPlace(book, PlaybackPlace(id, 999, 33_000, at = 15)))
        // Written before the queue's own place: the queue's wins.
        assertEquals(book, withPlace(book, PlaybackPlace(id, third, 33_000, at = 5)))
        assertEquals(book, withPlace(book, null))
    }

    @Test
    fun `the place file is written whole, and one that cannot be read is no place`() {
        val file = File(folder.root, "playback.json")
        val places = PlaceFile(file) { logs += it }
        assertNull(places.load())
        assertTrue(places.save(PlaybackPlace("q2", 1, 9_000, Repeat.ALL, at = 3)))
        assertEquals(PlaybackPlace("q2", 1, 9_000, Repeat.ALL, at = 3), places.load())
        assertFalse(File(folder.root, "playback.json.tmp").exists())
        file.writeText("{broken")
        assertNull(places.load())
    }

    // The store

    @Test
    fun `the queues are stored in the file and read back`() = runBlocking {
        val file = File(folder.root, "queues.json")
        val store = queuesStore(file, folder.root, scope, clock = { 7 }) { logs += it }
        val book = book()
        store.updateData { book }
        assertEquals(book.copy(jumps = 0), QueuesJson.decode(file.readText()))
        assertEquals(book, store.data.first())
    }

    @Test
    fun `a file that is not queues is kept aside before the store starts over`() = runBlocking {
        val file = File(folder.root, "queues.json")
        file.writeText("[not queues]")
        val store = queuesStore(file, folder.root, scope, clock = { 1234 }) { logs += it }
        assertEquals(QueueBook(), store.data.first())
        assertEquals("[not queues]", File(folder.root, "queues-corrupt-1234.json").readText())
        assertTrue(logs.none { "Lantern" in it || "/storage" in it })
    }

    // Restoring: the place and the queue's own, the newest wins

    /** The two files in memory, kept across a restart (a new [PlayerCore]) and read as [QueueFiles] reads them. */
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

    /** What the service does when the player starts song [uid] by itself: the queue follows, and the place is written. */
    private fun PlayerCore.started(uid: Long, now: Long) {
        change { it.at(uid, now) }
        savePlace(uid, 0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.restart(files: Files): QueueBook {
        val core = PlayerCore(files, backgroundScope, wall = { currentTime })
        runCurrent()
        return core.book.value
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a restart after the song changed opens on that song from its start, even when its place was not written`() = runTest {
        val files = Files()
        val core = PlayerCore(files, backgroundScope, wall = { currentTime })
        runCurrent()
        core.switch { it.play(album, 0, albumSource, currentTime) }
        runCurrent()
        val (first, second) = core.book.value.current!!.items.map { it.uid }
        advanceTimeBy(10_000)
        core.savePlace(first, 10_000)
        runCurrent()
        advanceTimeBy(5_000)
        // The player moved on; the place it would write next never came (the app was killed).
        core.change { it.at(second, currentTime) }
        runCurrent()

        val book = restart(files)
        assertEquals(second, book.current!!.current!!.uid)
        assertEquals(0L, book.current!!.positionMs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a restart opens where the last place written says, the song and the position together`() = runTest {
        val files = Files()
        val core = PlayerCore(files, backgroundScope, wall = { currentTime })
        runCurrent()
        core.switch { it.play(album, 0, albumSource, currentTime) }
        runCurrent()
        val (_, second) = core.book.value.current!!.items.map { it.uid }
        advanceTimeBy(3_000)
        core.started(second, currentTime)
        advanceTimeBy(10_000)
        core.savePlace(second, 10_000)
        runCurrent()

        val book = restart(files)
        assertEquals(second, book.current!!.current!!.uid)
        assertEquals(10_000L, book.current!!.positionMs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a restart after the queue ended opens at its first song, from the start`() = runTest {
        val files = Files()
        val core = PlayerCore(files, backgroundScope, wall = { currentTime })
        runCurrent()
        core.switch { it.play(album, 3, albumSource, currentTime) }
        runCurrent()
        val uids = core.book.value.current!!.items.map { it.uid }
        advanceTimeBy(10_000)
        core.savePlace(uids.last(), 170_000)
        runCurrent()
        // The last song ended: the player waits at the first song, paused.
        advanceTimeBy(4_000)
        core.started(uids.first(), currentTime)
        runCurrent()

        val book = restart(files)
        assertEquals(0, book.current!!.index)
        assertEquals(0L, book.current!!.positionMs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a restart after a switch before the first save opens the new queue, and the old one keeps its place`() = runTest {
        val files = Files()
        val core = PlayerCore(files, backgroundScope, wall = { currentTime })
        runCurrent()
        core.switch { it.play(album, 0, albumSource, currentTime) }
        runCurrent()
        val albumId = core.book.value.currentId!!
        val third = core.book.value.current!!.items[2].uid
        advanceTimeBy(10_000)
        core.savePlace(third, 50_000)
        runCurrent()
        advanceTimeBy(2_000)
        core.switch { it.play(folderSongs, 1, folderSource, currentTime) }
        runCurrent()

        val book = restart(files)
        assertEquals(1, book.current!!.index)
        assertEquals(0L, book.current!!.positionMs)
        assertEquals(2, book.queue(albumId)!!.index)
        assertEquals(50_000L, book.queue(albumId)!!.positionMs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the service's last place is written before it returns`() = runTest {
        val files = Files()
        val core = PlayerCore(files, backgroundScope, wall = { currentTime })
        runCurrent()
        core.switch { it.play(album, 0, albumSource, currentTime) }
        runCurrent()
        val first = core.book.value.current!!.items[0].uid
        advanceTimeBy(4_000)
        core.savePlaceNow(first, 4_000)
        assertEquals(PlaybackPlace(core.book.value.currentId, first, 4_000, Repeat.OFF, at = 4_000), files.place)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a queue left keeps the player's place at the switch, before any save`() = runTest {
        val files = Files()
        val core = PlayerCore(files, backgroundScope, wall = { currentTime })
        runCurrent()
        core.switch { it.play(album, 0, albumSource, currentTime) }
        runCurrent()
        val albumId = core.book.value.currentId!!
        val third = core.book.value.current!!.items[2].uid
        core.follow { PlayerAt(third, 31_000) }
        advanceTimeBy(4_000)
        core.switch { it.play(folderSongs, 1, folderSource, currentTime) }
        runCurrent()

        assertEquals(2, core.book.value.queue(albumId)!!.index)
        assertEquals(31_000L, core.book.value.queue(albumId)!!.positionMs)
        // Until the player loads the new queue it still holds the album's song: the new queue's place stays.
        core.change { it.rename(albumId, "Kept") }
        runCurrent()
        assertEquals(1, core.book.value.current!!.index)
        assertEquals(0L, core.book.value.current!!.positionMs)
    }

    // A cold start: the player screen opens before the queues are read

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `on a cold start the player stays open until the queues have loaded, and closes only when they load empty`() = runTest {
        for (stored in listOf(QueueBook().play(listOf(album[0]), 0, albumSource, now = 1).book, QueueBook())) {
            val read = CompletableDeferred<Unit>()
            val files = object : QueueStorage {
                override suspend fun load(): Pair<QueueBook, Repeat> = read.await().let { stored to Repeat.OFF }

                override suspend fun saveBook(book: QueueBook) = Unit

                override suspend fun savePlace(place: PlaybackPlace) = Unit
            }
            val core = PlayerCore(files, backgroundScope, wall = { currentTime })
            val closes = backgroundScope.async { core.awaitNothingLoaded() }
            runCurrent()
            assertFalse("closed while the queues were read", closes.isCompleted)

            read.complete(Unit)
            runCurrent()
            assertEquals(stored.current == null, closes.isCompleted)
            if (stored.current == null) continue

            // Its one song removed later: nothing is loaded now, and the player closes.
            core.change { it.remove(it.currentId!!, it.current!!.items.single().uid)!!.book }
            runCurrent()
            assertTrue(closes.isCompleted)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `queues that cannot be read close the player, are logged without names, and their file is left as it was`() = runTest {
        var saved = 0
        val files = object : QueueStorage {
            override suspend fun load(): Pair<QueueBook, Repeat> = throw IllegalStateException("a book this version can't read")

            override suspend fun saveBook(book: QueueBook) {
                saved++
            }

            override suspend fun savePlace(place: PlaybackPlace) = Unit
        }
        val logs = mutableListOf<String>()
        val quiet = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job) + CoroutineExceptionHandler { _, _ -> })
        val core = PlayerCore(files, quiet, wall = { currentTime }, log = { logs += it })
        val closes = backgroundScope.async { core.awaitNothingLoaded() }
        runCurrent()

        assertTrue("the player waits for ever", closes.isCompleted)
        assertEquals(QueueBook(), core.book.value)
        assertEquals(listOf("queues not read: IllegalStateException"), logs)

        // Playing something now never writes over the file that could not be read.
        core.switch { it.play(album, 0, albumSource, currentTime) }
        runCurrent()
        assertEquals(0, saved)
    }
}
