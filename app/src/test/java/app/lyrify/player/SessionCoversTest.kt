package app.lyrify.player

import app.lyrify.player.SessionCovers.Companion.NO_ART_KEEP_MS
import app.lyrify.player.SessionCovers.Companion.WAIT_MS
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The covers of KJB's media session (the notification and the lock screen): kept, read ahead, the newest asked for read
 * first, and every answer within its wait. Covers are strings and the readers' threads are run by hand, so a read goes on
 * until the test ends it. Made-up songs only.
 */
class SessionCoversTest {
    private val readers = Readers()
    private val clock = Clock()
    private val reads = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    /** What each song's read finds: its own cover unless named here. */
    private val found = mutableMapOf<String, () -> CoverFound<String>?>()

    private fun covers(maxBytes: Long = SessionCovers.MAX_BYTES, sizeOf: (String) -> Int = { 1 }) = SessionCovers(
        read = { song: String -> reads += song; if (song in found) found.getValue(song)() else CoverFound.Own("cover $song") },
        fallback = { "default" },
        sizeOf = sizeOf,
        run = readers,
        later = clock::later,
        clock = { clock.now },
        log = { logged += it },
        maxBytes = maxBytes,
    )

    @Test
    fun `a cover read once is answered at once after, so Media3 sets the song and its cover in one update`() {
        val covers = covers()
        val first = covers.ask("20")
        assertEquals("not yet", first.now())
        readers.finishAll()
        assertEquals("cover 20", first.now())
        // Next and back, or the notification asking after the lock screen: answered at once, nothing read again.
        assertEquals("cover 20", covers.ask("20").now())
        assertEquals(listOf("20"), reads)

        // The lock screen and the notification asking for one song while it is read share one read.
        val session = covers.ask("21")
        val notification = covers.ask("21")
        assertEquals(1, readers.waiting)
        readers.finishAll()
        assertEquals("cover 21", session.now())
        assertEquals("cover 21", notification.now())
        assertEquals(listOf("20", "21"), reads)
    }

    @Test
    fun `the covers kept fit in their bytes, the one used longest ago going first`() {
        val covers = covers(maxBytes = 10, sizeOf = { cover -> if (cover == "cover big") 11 else 4 })
        fun read(song: String) {
            covers.ask(song)
            readers.finishAll()
        }
        read("a")
        read("b")
        // "a" asked again is kept, and is now used after "b": "c" makes room by letting "b" go.
        assertEquals("cover a", covers.ask("a").now())
        read("c")
        assertEquals(listOf("a", "b", "c"), reads)
        assertEquals("cover a", covers.ask("a").now())
        assertEquals("cover c", covers.ask("c").now())
        assertEquals("not yet", covers.ask("b").now())
        readers.finishAll()
        assertEquals(listOf("a", "b", "c", "b"), reads)
        // A cover larger than all the room is answered, never kept, and lets nothing go.
        read("big")
        assertEquals("not yet", covers.ask("big").now())
        readers.finishAll()
        assertEquals("cover b", covers.ask("b").now())
        assertEquals("cover c", covers.ask("c").now())
        assertEquals(listOf("a", "b", "c", "b", "big", "big"), reads)
    }

    @Test
    fun `a burst of Nexts reads the newest song's cover next, not every song's in turn`() {
        val covers = covers()
        val asked = (1..10).associate { n -> "s$n" to covers.ask("s$n") }
        // Two reads at a time: the first two songs' started at once; the rest wait, and only the newest goes on.
        assertEquals(2, readers.waiting)
        readers.finishAll()
        assertEquals(listOf("s1", "s2", "s10"), reads)
        assertEquals("cover s1", asked.getValue("s1").now())
        assertEquals("cover s10", asked.getValue("s10").now())
        // Previous, back to s9 within its wait: read now, and its first answer gets its cover too.
        val again = covers.ask("s9")
        readers.finishAll()
        assertEquals("cover s9", again.now())
        assertEquals("cover s9", asked.getValue("s9").now())
        // The songs skipped get the default cover within the wait, and are never read.
        assertEquals("not yet", asked.getValue("s5").now())
        clock.advance(WAIT_MS)
        (3..8).forEach { n -> assertEquals("default", asked.getValue("s$n").now()) }
        readers.finishAll()
        assertEquals(listOf("s1", "s2", "s10", "s9"), reads)
    }

    @Test
    fun `the songs either side of the one playing are read ahead, one at a time and after any song asked for`() {
        val covers = covers()
        covers.warm(listOf("next", "previous"))
        assertEquals(1, readers.waiting)
        // A song asked for meanwhile takes the other reader at once.
        val asked = covers.ask("picked")
        assertEquals(2, readers.waiting)
        readers.finishOne()
        assertEquals(listOf("next"), reads)
        readers.finishAll()
        assertEquals("cover picked", asked.now())
        assertEquals(listOf("next", "picked", "previous"), reads)
        // Next then finds its cover read ahead, and nothing is read for it.
        assertEquals("cover next", covers.ask("next").now())
        assertEquals("cover previous", covers.ask("previous").now())
        assertEquals(3, reads.size)

        // A song asked for while it is read ahead shares that read.
        covers.warm(listOf("n2"))
        val n2 = covers.ask("n2")
        readers.finishAll()
        assertEquals("cover n2", n2.now())
        assertEquals(listOf("n2"), reads.drop(3))

        // Another song starting: what was to be read ahead for the last one and has not started is not read; covers kept are not read again.
        covers.warm(listOf("a1", "b1"))
        covers.warm(listOf("a2", "next"))
        readers.finishAll()
        assertEquals(listOf("n2", "a1", "a2"), reads.drop(3))
    }

    @Test
    fun `a read that throws or runs out of memory answers the default cover, logs only the error's name, and is read again next time`() {
        val covers = covers()
        found["30"] = { throw IllegalStateException("made up") }
        found["31"] = { throw OutOfMemoryError("made up") }
        // A read that failed (ownCover's null): the default cover, not kept.
        found["32"] = { null }
        val answers = listOf("30", "31", "32").map { song -> covers.ask(song).also { readers.finishAll() } }
        assertEquals(listOf("default", "default", "default"), answers.map { it.now() })
        assertEquals(listOf("session cover not read: IllegalStateException", "session cover not read: OutOfMemoryError"), logged)
        found.clear()
        listOf("30", "31", "32").forEach { song -> covers.ask(song).also { readers.finishAll() } }
        assertEquals(listOf("30", "31", "32", "30", "31", "32"), reads)
        assertEquals("cover 30", covers.ask("30").now())
    }

    @Test
    fun `a song with no art answers the default cover at once for a minute, then is read again`() {
        val covers = covers()
        found["40"] = { CoverFound.NoArt }
        covers.ask("40")
        readers.finishAll()
        clock.advance(NO_ART_KEEP_MS - 1)
        assertEquals("default", covers.ask("40").now())
        assertEquals(listOf("40"), reads)
        // MediaStore can miss a song's art for a moment (the widget reads it again a minute on too): this time it has some.
        found.clear()
        clock.advance(1)
        val later = covers.ask("40")
        readers.finishAll()
        assertEquals("cover 40", later.now())
        assertEquals(listOf("40", "40"), reads)
    }

    @Test
    fun `songs with no art take no room, yet only so many songs are kept`() {
        val covers = covers()
        val songs = (0..SessionCovers.MAX_KEPT).map { n -> "n$n" }
        songs.forEach { song ->
            found[song] = { CoverFound.NoArt }
            covers.ask(song)
            readers.finishAll()
        }
        assertEquals("default", covers.ask(songs.last()).now())
        assertEquals("default", covers.ask(songs[1]).now())
        assertEquals("not yet", covers.ask(songs.first()).now())
    }

    @Test
    fun `an answer comes within the wait when the read never ends, and the cover read late is kept for the next ask`() {
        val covers = covers()
        val stuck = covers.ask("50")
        clock.advance(WAIT_MS - 1)
        assertEquals("not yet", stuck.now())
        clock.advance(1)
        assertEquals("default", stuck.now())
        // Both readers stuck: a song asked for waits, and still has its answer within the wait.
        covers.ask("51")
        val waits = covers.ask("52")
        clock.advance(WAIT_MS)
        assertEquals("default", waits.now())
        readers.finishAll()
        assertEquals(listOf("50", "51", "52"), reads)
        assertEquals("cover 50", covers.ask("50").now())
        assertEquals("cover 52", covers.ask("52").now())
    }

    @Test
    fun `covers changed in KJB are read again, and a read going on as they change is not kept`() {
        val covers = covers()
        covers.ask("60")
        readers.finishAll()
        val reading = covers.ask("61")
        covers.forget()
        readers.finishAll()
        // The read that started before the change still answers; the next ask reads the new cover.
        assertEquals("cover 61", reading.now())
        assertEquals("not yet", covers.ask("60").now())
        assertEquals("not yet", covers.ask("61").now())
        readers.finishAll()
        assertEquals(listOf("60", "61", "60", "61"), reads)
        assertEquals("cover 61", covers.ask("61").now())
    }

    @Test
    fun `a song asked for while it is read ahead gets that read's cover, or the default within its own wait`() {
        val covers = covers()
        covers.warm(listOf("n"))
        val joined = covers.ask("n")
        // The read ahead never ends in time: the answer still comes, and the read done later is kept.
        clock.advance(WAIT_MS)
        assertEquals("default", joined.now())
        readers.finishAll()
        assertEquals("cover n", covers.ask("n").now())
        assertEquals(listOf("n"), reads)
    }

    @Test
    fun `asking again for a song being read leaves the newer song asked for to be read next`() {
        val covers = covers()
        covers.warm(listOf("ahead"))
        covers.ask("a")
        val b = covers.ask("b")
        // The notification asks for the song before as the lock screen already asked for the next one.
        val again = covers.ask("a")
        readers.finishAll()
        assertEquals(listOf("ahead", "a", "b"), reads)
        assertEquals("cover a", again.now())
        assertEquals("cover b", b.now())
    }

    @Test
    fun `a cancelled answer stays cancelled, and a song set aside with no answer left is let go at once`() {
        val covers = covers()
        covers.ask("s1")
        covers.ask("s2")
        val s3 = covers.ask("s3")
        val s4 = covers.ask("s4")
        assertEquals(4, covers.songsHeld())
        s3.cancel(false)
        assertEquals(3, covers.songsHeld())
        // Cancelled while it waits to be read: still the newest asked for, so it is read and kept, as when its wait ends.
        s4.cancel(false)
        readers.finishAll()
        assertEquals(listOf("s1", "s2", "s4"), reads)
        assertTrue(s3.isCancelled && s4.isCancelled)
        assertEquals(0, covers.songsHeld())
        assertEquals("cover s4", covers.ask("s4").now())
    }

    @Test
    fun `an Error a read throws answers the default, stays in its read, and frees the reader`() {
        val covers = covers()
        found["70"] = { throw StackOverflowError() }
        val answer = covers.ask("70")
        // As 3.0.2's io.submit: whatever a read throws fails that read only, never the reader's thread.
        assertEquals(null, runCatching { readers.finishAll() }.exceptionOrNull()?.javaClass?.simpleName)
        assertEquals("default", answer.now())
        assertEquals(listOf("session cover not read: StackOverflowError"), logged)
        covers.ask("71")
        covers.ask("72")
        assertEquals(2, readers.waiting)
    }

    @Test
    fun `on real threads, with read ahead, forget and cancels, every answer comes within its wait and no song is left held`() {
        val pool = Executors.newFixedThreadPool(SessionCovers.READERS)
        val timers = Executors.newSingleThreadScheduledExecutor()
        val problems = Collections.synchronizedList(mutableListOf<String>())
        val covers = SessionCovers(
            read = { song: String ->
                Thread.sleep(song.length % 3L)
                if (song.endsWith("7")) CoverFound.NoArt else CoverFound.Own("cover $song")
            },
            fallback = { "default" },
            sizeOf = { 1 },
            run = pool,
            later = { delayMs, task ->
                val timer = timers.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                Runnable { timer.cancel(false) }
            },
            clock = { System.nanoTime() / 1_000_000 },
            log = { problems += it },
            maxBytes = 8,
            waitMs = 300,
        )
        val answers = (0 until 400).map { n ->
            if (n % 7 == 0) covers.warm(listOf("s${(n + 1) % 30}", "s${(n + 29) % 30}"))
            if (n % 97 == 0) covers.forget()
            covers.ask("s${n % 30}").also { if (n % 11 == 0) it.cancel(false) }
        }
        val got = answers.map { answer ->
            try {
                answer.get(5, TimeUnit.SECONDS)
            } catch (e: CancellationException) {
                "cancelled"
            }
        }
        assertTrue(got.all { it == "default" || it == "cancelled" || it.startsWith("cover s") })
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        timers.shutdownNow()
        assertEquals(0, covers.songsHeld())
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the songs read ahead are the next one, round to the first as Next goes, and the previous one`() {
        assertEquals(listOf(1, 3), aroundSong(0, 4))
        assertEquals(listOf(2, 0), aroundSong(1, 4))
        assertEquals(listOf(0, 2), aroundSong(3, 4))
        assertEquals(listOf(1), aroundSong(0, 2))
        assertEquals(emptyList<Int>(), aroundSong(0, 1))
        assertEquals(emptyList<Int>(), aroundSong(-1, 4))
        assertEquals(emptyList<Int>(), aroundSong(0, 0))
    }

    /** The answer now: its cover, "not yet", or what it failed with. */
    private fun ListenableFuture<String>.now(): String = when {
        !isDone -> "not yet"
        else -> try {
            Futures.getDone(this)
        } catch (e: ExecutionException) {
            "failed: ${e.cause?.javaClass?.simpleName}"
        }
    }

    /** The readers' threads, by hand: a read started waits here until the test ends it. */
    private class Readers : Executor {
        private val started = ArrayDeque<Runnable>()
        val waiting: Int get() = started.size

        override fun execute(command: Runnable) {
            started += command
        }

        /** Ends the read that started first. */
        fun finishOne() = started.removeFirst().run()

        fun finishAll() {
            while (started.isNotEmpty()) finishOne()
        }
    }

    /** The clock and the waits, by hand. */
    private class Clock {
        var now = 0L
        private val timers = mutableListOf<Pair<Long, Runnable>>()

        fun later(delayMs: Long, task: Runnable): Runnable {
            val timer = now + delayMs to task
            timers += timer
            return Runnable { timers.removeAll { it === timer } }
        }

        fun advance(ms: Long) {
            now += ms
            val due = timers.filter { it.first <= now }
            timers.removeAll { it in due }
            due.forEach { it.second.run() }
        }
    }
}
