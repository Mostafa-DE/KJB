package app.lyrify.player

import app.lyrify.library.SongRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        book = book.play(listOf(song("Loose")), 0, QueueSource(SourceKind.SEARCH, "search:loose", "loose"), now = 30).book
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
    fun `a current queue that is gone reads as nothing playing`() {
        val text = """{"current":"q8","queues":[{"id":"q1","source":{"kind":"SONGS","key":"songs","name":""},"items":[{"uid":1,"path":"/storage/emulated/0/a.mp3"}]}],"recent":["q1"]}"""
        val book = QueuesJson.decode(text)!!
        assertNull(book.currentId)
        assertEquals(listOf("q1"), book.recent)
    }

    // playback.json

    @Test
    fun `the place reads back, and anything it cannot use reads as no place or its default`() {
        val place = PlaybackPlace("q3", 4, 61_500, Repeat.ONE)
        assertEquals(place, PlaybackJson.decode(PlaybackJson.encode(place)))
        assertEquals(PlaybackPlace(null, 0, 0, Repeat.OFF), PlaybackJson.decode("""{"queueId":null}"""))
        assertEquals(PlaybackPlace("q1", 0, 0, Repeat.OFF), PlaybackJson.decode("""{"queueId":"q1","index":-3,"positionMs":"x","repeat":"SOMETIMES"}"""))
        assertNull(PlaybackJson.decode("[1]"))
        assertNull(PlaybackJson.decode(""))
    }

    @Test
    fun `the place applies to the queue it names, at a song it still has`() {
        val book = QueueBook().play(album, 0, albumSource, now = 1).book
        val id = book.currentId!!
        val placed = withPlace(book, PlaybackPlace(id, 2, 33_000, Repeat.ALL))
        assertEquals(2, placed.current!!.index)
        assertEquals(33_000L, placed.current!!.positionMs)
        assertEquals(book, withPlace(book, PlaybackPlace("q99", 2, 33_000)))
        assertEquals(book, withPlace(book, PlaybackPlace(id, 40, 33_000)))
        assertEquals(book, withPlace(book, null))
    }

    @Test
    fun `the place file is written whole, and one that cannot be read is no place`() {
        val file = File(folder.root, "playback.json")
        val places = PlaceFile(file) { logs += it }
        assertNull(places.load())
        assertTrue(places.save(PlaybackPlace("q2", 1, 9_000, Repeat.ALL)))
        assertEquals(PlaybackPlace("q2", 1, 9_000, Repeat.ALL), places.load())
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
}
