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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Writing `queues.json` with a library-sized queue in it (FU1). A change encodes only the song lists it made: a skip or a
 * new place none, a change to one queue that queue's. The file reads and writes as KJB 3.0.2's did. Made-up names only.
 */
class QueueWritesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun stop() = scope.cancel()

    private fun song(n: Int) = SongRef("/storage/emulated/0/Music/Big Shelf/%04d Paper Lantern %d.mp3".format(n, n), n.toLong())

    private val library = (1..LIBRARY).map(::song)
    private val songsTab = QueueSource(SourceKind.SONGS, "songs", "")
    private val picked = QueueSource(SourceKind.PICKED, "picked", "")

    // Encoding

    @Test
    fun `a skip or a new place encodes no song list, and a change to one queue only that queue's`() {
        val encoded = mutableListOf<String>()
        val encoder = QueuesJson.Encoder { encoded += it }
        fun write(book: QueueBook) {
            val text = ByteArrayOutputStream().also { encoder.write(book, it) }.toByteArray().decodeToString()
            assertEquals(QueuesJson.encode(book), text)
            assertEquals(book.copy(jumps = 0), QueuesJson.decode(text))
        }

        var book = QueueBook().play(library, 0, songsTab, now = 1).book
        val big = book.currentId!!
        book = book.newQueue("Car", library.take(3), picked, now = 2)!!.book
        val small = book.saved.single()
        book = book.toggleShuffle(Random(3))
        write(book)
        // The first write encodes every list: the big queue's songs and its order before Shuffle, the small one's songs.
        assertEquals(mapOf(big to 2, small to 1), encoded.groupingBy { it }.eachCount())

        encoded.clear()
        book = book.at(book.current!!.items[7].uid, now = 3).place(7, 42_000, at = 4)
        write(book)
        assertEquals(emptyList<String>(), encoded)

        book = book.addTo(small, listOf(song(LIBRARY + 1)))!!.book
        write(book)
        assertEquals(listOf(small), encoded)

        encoded.clear()
        book = book.move(big, 0, 5)
        write(book)
        assertEquals(listOf(big), encoded)

        encoded.clear()
        book = book.delete(small)
        write(book)
        assertEquals(emptyList<String>(), encoded)
    }

    @Test
    fun `a file written before reads back through the store, and is written again byte for byte`() = runBlocking {
        val book = QueuesJson.decode(OLD_FILE)!!
        assertEquals("Car \"mix\" é", book.current!!.name)
        assertEquals(listOf(3L, 4L, 5L), book.current!!.unshuffled)
        assertEquals(OLD_FILE, QueuesJson.encode(book))

        val file = File(folder.root, "queues.json").apply { writeText(OLD_FILE) }
        val store = queuesStore(file, folder.root, scope, clock = { 7 }) {}
        assertEquals(book, store.data.first())
        val placed = book.place(1, 5_000, at = 300)
        store.updateData { placed }
        assertEquals(QueuesJson.encode(placed), file.readText())
        assertEquals(placed, QueuesJson.decode(file.readText()))
    }

    private companion object {
        const val LIBRARY: Int = 3_000

        /** `queues.json` as KJB 3.0.2 wrote it: a recent album queue, and a named, shuffled queue playing with a Play next song waiting. */
        const val OLD_FILE: String =
            """{"schemaVersion":1,"current":"q2","recent":["q1"],"saved":[],"nextId":3,"nextUid":6,"queues":[""" +
                """{"id":"q1","source":{"kind":"ALBUM","key":"album:harbour glass","name":"Harbour Glass"},"index":1,"positionMs":30000,"updatedAt":100,"startedAt":90,"listSize":2,""" +
                """"items":[{"uid":1,"path":"/storage/emulated/0/Music/Harbour Glass/01 Tin Kite.mp3","mediaStoreId":11,"sourceIndex":0},""" +
                """{"uid":2,"path":"/storage/emulated/0/Music/Harbour Glass/02 Salt Bell.mp3","sourceIndex":1}]},""" +
                """{"id":"q2","source":{"kind":"PICKED","key":"picked","name":""},"name":"Car \"mix\" é","index":0,"positionMs":0,"updatedAt":200,"startedAt":150,"unshuffled":[3,4,5],""" +
                """"items":[{"uid":4,"path":"/storage/emulated/0/Music/Rips/Paper Lantern.flac","sourceIndex":1},""" +
                """{"uid":3,"path":"/storage/emulated/0/Music/Rips/Low Water.flac","sourceIndex":0},""" +
                """{"uid":5,"path":"/storage/emulated/0/Music/Rips/Night Ferry.flac","playNext":true}]}]}"""
    }
}
