package app.lyrify.lyrics

import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.data.Retry
import app.lyrify.data.SharedWrites
import app.lyrify.library.ArtistSplitter
import app.lyrify.overnight.AiQueue
import app.lyrify.settings.ArtistSplit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * The better lyrics search (rb4): a tag naming several artists asked for by one of them, lyrics in the file's own
 * tags, relevance by score with a band to check, another length timed anew by the AI, and misses looked up again
 * after a week. Made-up names and words only.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LyricsSearchTest {
    @get:Rule
    val folder = TemporaryFolder()

    private object MiddleRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.5
    }

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()

    /** Placeholder words, never real lyrics. */
    private val synced = "[00:01.00]w0001\n[00:02.00]w0002\n[00:03.00]w0003\n[00:04.00]w0004"
    private val plain = "w0001\nw0002\nw0003"

    private fun record(id: Long, track: String = "Glass Orchard", artist: String = "Kel Varo", duration: Double = 200.0, synced: String? = this.synced) =
        LrclibRecord(id, track, artist, null, duration, plainLyrics = plain, syncedLyrics = synced)

    /** The user's "Artists with several names", at its defaults: ";", "/", ",", feat. split; "&" does not. */
    private val splitter = ArtistSplitter(ArtistSplit())

    /** Whether the AI add-on is on the phone. */
    private var aiThere = false

    private fun TestScope.pipeline() = LyricsPipeline(
        client = client,
        ledger = ledger,
        clock = { BASE + testScheduler.currentTime },
        writes = SharedWrites.ANYWHERE,
        random = MiddleRandom,
        artistNames = { splitter.names(it) },
        aiTimes = { aiThere },
    )

    private fun audio(name: String, title: String, artist: String?, bytes: ByteArray = byteArrayOf(1, 2, 3), durationMs: Long = 200_000): LocalTrack {
        val file = File(folder.root, name)
        file.writeBytes(bytes)
        return LocalTrack(file.path, title, artist, null, durationMs)
    }

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    // Several artists in one tag

    @Test
    fun `a song whose tag names several artists is found under its last one, asked for only after the whole tag missed`() = runTest {
        val track = audio("a.mp3", "Glass Orchard", "Kel Varo; Mina Ost")
        client.onGet = { if (it.artist == "Mina Ost") LrclibResult.Found(listOf(record(7, artist = "Mina Ost"))) else LrclibResult.NotFound }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(listOf("Kel Varo; Mina Ost", "Kel Varo", "Mina Ost"), client.gets.map { it.artist })
        assertEquals(7L, ledger[track.path]?.lrclibId)
        assertTrue(sidecar(track).exists())
    }

    @Test
    fun `a hit on the whole tag asks for no artist alone`() = runTest {
        val track = audio("a.mp3", "Glass Orchard", "Kel Varo; Mina Ost")
        client.onGet = { LrclibResult.Found(listOf(record(7, artist = "Kel Varo; Mina Ost"))) }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(1, client.requests)
    }

    @Test
    fun `an artist alone still has to be the record's artist`() = runTest {
        val track = audio("a.mp3", "Glass Orchard", "Kel Varo; Mina Ost")
        // Search finds a record whose artist only starts with the name.
        client.onSearch = { if (it.artist == "Mina Ost") LrclibResult.Found(listOf(record(7, artist = "Mina Ostrova"))) else LrclibResult.Found(emptyList()) }

        val status = pipeline().process(track)?.status

        assertFalse(sidecar(track).exists())
        // Named like the song: the user may check it, as any record of another artist a search turns up.
        assertEquals(LyricsStatus.NEEDS_CHECK, status)
        assertEquals(listOf(7L), ledger[track.path]?.candidates?.map { it.id })
    }

    @Test
    fun `an ampersand splits nothing by default`() = runTest {
        val track = audio("a.mp3", "Dust Lanes", "Lio Marsh & Cass Dunmore")

        pipeline().process(track)

        assertEquals(listOf("Lio Marsh & Cass Dunmore"), client.gets.map { it.artist }.distinct())
    }

    @Test
    fun `a record of one artist alone counts only once that artist is asked for, after the whole tag`() = runTest {
        val track = audio("a.mp3", "Glass Orchard", "Kel Varo feat. Mina Ost")
        // The whole tag's search also turns up the featured artist's record; the main artist's comes a query later.
        client.onSearch = { if (it.artist == "Kel Varo feat. Mina Ost") LrclibResult.Found(listOf(record(8, artist = "Mina Ost"))) else LrclibResult.Found(emptyList()) }
        client.onGet = { if (it.artist == "Kel Varo") LrclibResult.Found(listOf(record(7, artist = "Kel Varo"))) else LrclibResult.NotFound }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(7L, ledger[track.path]?.lrclibId)
        assertEquals(listOf("Kel Varo feat. Mina Ost", "Kel Varo"), client.gets.map { it.artist })
    }

    // Lyrics in the file's own tags

    private val tagLines = listOf(10_000L to "Made-up dawn", 25_000L to "Made-up noon", 40_000L to "Made-up dusk")

    private fun withPlain(text: String = "Made-up rook\nMade-up rye") = LyricsTagFixtures.mp3(LyricsTagFixtures.frame("USLT", LyricsTagFixtures.uslt(text)))

    @Test
    fun `synced lyrics in the file are written before any request, as the file's own`() = runTest {
        val bytes = LyricsTagFixtures.mp3(LyricsTagFixtures.frame("SYLT", LyricsTagFixtures.sylt(tagLines)))
        val track = audio("a.mp3", "Ember Choir", "Nadia Fenn", bytes)
        client.onGet = { LrclibResult.Found(listOf(record(7, track = "Ember Choir", artist = "Nadia Fenn"))) }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(0, client.requests)
        assertEquals("[00:10.000]Made-up dawn\n[00:25.000]Made-up noon\n[00:40.000]Made-up dusk\n", sidecar(track).readText())
        val entry = ledger[track.path]!!
        assertEquals(LyricsSource.TAGS, entry.source)
        assertNull(entry.lrclibId)
        assertTrue(canRemove(entry))
        assertArrayEquals(bytes, File(track.path).readBytes())
    }

    @Test
    fun `plain lyrics in the file still ask LRCLIB for synced ones, which win`() = runTest {
        val track = audio("a.mp3", "Rook and Rye", "Wil Sander", withPlain())
        client.onGet = { LrclibResult.Found(listOf(record(7, track = "Rook and Rye", artist = "Wil Sander"))) }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(7L, ledger[track.path]?.lrclibId)
        assertEquals(LyricsSource.LRCLIB, ledger[track.path]?.source)
    }

    @Test
    fun `plain lyrics in the file are the song's plain lyrics when LRCLIB has none, with nothing to check`() = runTest {
        val track = audio("a.mp3", "Fernlight", "Clea Moss", withPlain())
        // A loose search would offer this one.
        client.onSearch = { if (it.artist == null) LrclibResult.Found(listOf(record(7, track = "Fernlight", artist = "Somebody"))) else LrclibResult.Found(emptyList()) }

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().process(track)?.status)

        val entry = ledger[track.path]!!
        assertTrue(entry.plainInTags)
        assertNull(entry.lrclibId)
        assertEquals(true, entry.durationMatched)
        assertEquals(emptyList<Candidate>(), entry.candidates)
        assertTrue(client.searches.none { it.artist == null })
        assertFalse(sidecar(track).exists())
        assertTrue(AiQueue.job(entry).times)
    }

    @Test
    fun `plain lyrics in the file stand when LRCLIB cannot be asked, and LRCLIB is asked again as after an error`() = runTest {
        val track = audio("a.mp3", "Fernlight", "Clea Moss", withPlain())
        client.onGet = { LrclibResult.Transient("offline") }
        client.onSearch = { LrclibResult.Transient("offline") }

        val processed = pipeline().process(track)

        assertEquals(LyricsStatus.PLAIN_ONLY, processed?.status)
        assertTrue(processed!!.lrclibBusy)
        val entry = ledger[track.path]!!
        assertTrue(entry.plainInTags)
        assertTrue(AiQueue.job(entry).times)
        assertEquals(entry.updatedAt + Retry.errorDelayMs(1), entry.nextAttemptAt)
    }

    @Test
    fun `while LRCLIB stays out of reach the file's plain lyrics stand, and it is asked again on the errors' waits, then as usual once it answers`() = runTest {
        val track = audio("a.mp3", "Fernlight", "Clea Moss", withPlain())
        client.onGet = { LrclibResult.Transient("offline") }
        client.onSearch = { LrclibResult.Transient("offline") }
        val pipeline = pipeline()

        for (failed in 1..8) {
            assertEquals(LyricsStatus.PLAIN_ONLY, pipeline.process(track)?.status)
            val entry = ledger[track.path]!!
            assertTrue(entry.plainInTags)
            // 30 minutes, doubling, at most a day.
            assertEquals(entry.updatedAt + Retry.errorDelayMs(failed), entry.nextAttemptAt)
            // The queue sweep's retries take it once it is due, not before.
            delay(Retry.errorDelayMs(failed) - 1)
            assertEquals(emptyList<LocalTrack>(), pipeline.dueErrors())
            delay(1)
            assertEquals(listOf(track), pipeline.dueErrors())
        }
        assertEquals(Retry.ERROR_MAX_DELAY_MS, Retry.errorDelayMs(8))

        client.onGet = { LrclibResult.NotFound }
        client.onSearch = { LrclibResult.Found(emptyList()) }
        val answered = pipeline.process(track)

        assertEquals(Processed(LyricsStatus.PLAIN_ONLY), answered)
        val entry = ledger[track.path]!!
        assertTrue(entry.plainInTags)
        assertEquals(0, entry.attempts)
        assertEquals(entry.updatedAt + Retry.RECHECK_AFTER_MS, entry.nextAttemptAt)
        assertEquals(emptyList<LocalTrack>(), pipeline.dueErrors())
    }

    @Test
    fun `synced lyrics in the file that Samsung Music could not show are kept as plain`() = runTest {
        val bytes = LyricsTagFixtures.mp3(LyricsTagFixtures.frame("SYLT", LyricsTagFixtures.sylt(tagLines.take(2))))
        val track = audio("a.mp3", "Ember Choir", "Nadia Fenn", bytes)

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().process(track)?.status)

        assertTrue(ledger[track.path]!!.plainInTags)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `a song with no artist keeps the plain lyrics of its file`() = runTest {
        val track = audio("a.mp3", "Voice Memo 12", null, withPlain())

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().process(track)?.status)

        assertTrue(ledger[track.path]!!.plainInTags)
    }

    // Relevance by score

    @Test
    fun `a title with one word written as two is written by itself`() = runTest {
        val track = audio("a.mp3", "Dont Wake the River", "Pia Lund")
        client.onGet = { LrclibResult.Found(listOf(record(7, track = "Don't Wake the River", artist = "Pia Lund"))) }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(7L, ledger[track.path]?.lrclibId)
    }

    @Test
    fun `a maybe the artist's own search found waits in Check these, never written without the user`() = runTest {
        val track = audio("a.mp3", "Ember Road Part 2", "Kasimir Vell")
        client.onSearch = { if (it.artist != null) LrclibResult.Found(listOf(record(7, track = "Ember Road Pt. 2", artist = "Kasimir Vell"))) else LrclibResult.Found(emptyList()) }

        assertEquals(LyricsStatus.NEEDS_CHECK, pipeline().process(track)?.status)

        assertEquals(listOf(7L), ledger[track.path]?.candidates?.map { it.id })
        assertFalse(sidecar(track).exists())
    }

    // Another length

    /** A 250 s song LRCLIB has only at [seconds]: its search finds it, the exact lookup (2 s) does not. */
    private fun anotherLength(seconds: Double, synced: String? = this.synced): LocalTrack {
        client.onSearch = { if (it.artist != null) LrclibResult.Found(listOf(record(7, track = "Blue Static", artist = "Ines Darrow", duration = seconds, synced = synced))) else LrclibResult.Found(emptyList()) }
        return audio("a.mp3", "Blue Static", "Ines Darrow", durationMs = 250_000)
    }

    @Test
    fun `synced lyrics only of another length are left for the AI add-on to time anew`() = runTest {
        aiThere = true
        val track = anotherLength(241.0)

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().process(track)?.status)

        val entry = ledger[track.path]!!
        assertEquals(7L, entry.lrclibId)
        assertTrue(entry.retime)
        assertEquals(false, entry.durationMatched)
        assertEquals(emptyList<Candidate>(), entry.candidates)
        assertTrue(client.searches.none { it.artist == null })
        assertTrue(AiQueue.job(entry).times)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `plain lyrics only of another length are timed anew too`() = runTest {
        aiThere = true
        val track = anotherLength(262.0, synced = null)

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().process(track)?.status)

        assertTrue(ledger[track.path]!!.retime)
    }

    @Test
    fun `without the AI add-on another length is as before`() = runTest {
        val track = anotherLength(241.0)

        assertEquals(LyricsStatus.DURATION_MISMATCH, pipeline().process(track)?.status)

        assertFalse(ledger[track.path]!!.retime)
    }

    @Test
    fun `more than 15 s off is as before, add-on or not`() = runTest {
        aiThere = true
        val track = anotherLength(234.0)

        assertEquals(LyricsStatus.DURATION_MISMATCH, pipeline().process(track)?.status)

        assertFalse(ledger[track.path]!!.retime)
        assertFalse(AiQueue.job(ledger[track.path]!!).times)
    }

    @Test
    fun `a record with synced lyrics only gives their words as its plain lyrics`() = runTest {
        client.onId = { LrclibResult.Found(listOf(record(it, synced = "[00:01.00]w0001\n[00:02.00]\n[00:03.00]w0002").copy(plainLyrics = null))) }

        assertEquals(PlainLyrics.Found("w0001\nw0002"), pipeline().plainLyrics(7))
    }

    // Misses

    @Test
    fun `a miss is looked up again a week later, once, then after 30 days, and never on every scan`() = runTest {
        val track = audio("a.mp3", "Saffron Tide", "Ola Brenn")
        val pipeline = pipeline()
        pipeline.process(track)
        val once = client.requests

        delay(6 * DAY)
        pipeline.process(track)
        assertEquals(once, client.requests)
        delay(DAY)
        pipeline.process(track)
        assertEquals(2 * once, client.requests)
        delay(29 * DAY)
        pipeline.process(track)
        assertEquals(2 * once, client.requests)
        delay(DAY)
        pipeline.process(track)
        assertEquals(3 * once, client.requests)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000L
        const val DAY: Long = 24 * 60 * 60_000L
    }
}
