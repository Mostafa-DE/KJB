package app.lyrify.lyrics

import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrites
import app.lyrify.library.ArtistSplitter
import app.lyrify.settings.ArtistSplit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun TestScope.pipeline() = LyricsPipeline(
        client = client,
        ledger = ledger,
        clock = { BASE + testScheduler.currentTime },
        writes = SharedWrites.ANYWHERE,
        random = MiddleRandom,
        artistNames = { splitter.names(it) },
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

    private companion object {
        const val BASE: Long = 1_800_000_000_000L
    }
}
