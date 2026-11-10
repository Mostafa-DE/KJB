package app.lyrify.lyrics

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrites
import app.lyrify.data.recorded
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

/** What a Covers & tags apply does to a song's lyrics (covers-tags-spec, "Effects on the rest of KJB"). */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsPipelineRetagTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()

    /** Placeholder words, never real lyrics. */
    private val synced = "[00:01.00]w0001\n[00:02.00]w0002\n[00:03.00]w0003"
    private val otherSynced = "[00:01.00]w0101\n[00:02.00]w0102\n[00:03.00]w0103"

    private fun record(id: Long, track: String = "Salt Road", artist: String = "Mira Solen", synced: String? = this.synced) =
        LrclibRecord(id, track, artist, "Lanterns of Ulso", 200.0, plainLyrics = "w0001", syncedLyrics = synced)

    private fun TestScope.pipeline() = LyricsPipeline(
        client = client,
        ledger = ledger,
        clock = { BASE + testScheduler.currentTime },
        writes = SharedWrites.ANYWHERE,
    )

    private fun audio(title: String = "Salt Road", artist: String? = "Mira Solen"): LocalTrack {
        val file = File(folder.root, "Salt Road.mp3")
        file.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 5, 6, 7))
        return LocalTrack(file.path, title, artist, "Lanterns of Ulso", 200_000)
    }

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    private fun entry(track: LocalTrack, status: LyricsStatus, mine: Boolean = false): LedgerEntry =
        recorded(null, track, status, lrclibId = 7, now = BASE).copy(mine = mine)

    /** [track] with KJB's own `.lrc` from LRCLIB record 42, written through the pipeline. */
    private suspend fun LyricsPipeline.withLyrics(track: LocalTrack): String {
        client.onGet = { LrclibResult.Found(listOf(record(42))) }
        assertEquals(LyricsStatus.SYNCED, process(track)?.status)
        client.gets.clear()
        return sidecar(track).readText()
    }

    // The rule

    @Test
    fun `only songs not found or with no artist are looked up again`() {
        val track = LocalTrack("/m/a.mp3", "Salt Road", "Mira Solen", null, 200_000)
        assertEquals(Retag.LOOK_UP, retagEffect(entry(track, LyricsStatus.NOT_FOUND), namesChanged = true))
        assertEquals(Retag.LOOK_UP, retagEffect(entry(track, LyricsStatus.UNIDENTIFIED), namesChanged = false))
        for (status in listOf(LyricsStatus.REMOVED, LyricsStatus.INSTRUMENTAL, LyricsStatus.AI_REJECTED, LyricsStatus.DURATION_MISMATCH, LyricsStatus.EXISTING, LyricsStatus.PLAIN_ONLY, LyricsStatus.ERROR, LyricsStatus.AI_INSTRUMENTAL)) {
            assertEquals(status.name, Retag.NONE, retagEffect(entry(track, status), namesChanged = true))
        }
        assertEquals(Retag.NONE, retagEffect(null, namesChanged = true))
    }

    @Test
    fun `KJB's own lyrics from LRCLIB wait for a check only when the title or artist changed`() {
        val track = LocalTrack("/m/a.mp3", "Salt Road", "Mira Solen", null, 200_000)
        val written = entry(track, LyricsStatus.SYNCED).copy(sha256 = "ab", source = LyricsSource.LRCLIB)
        assertEquals(Retag.NEEDS_CHECK, retagEffect(written, namesChanged = true))
        assertEquals(Retag.NONE, retagEffect(written, namesChanged = false))
        // Plain lyrics the AI timed were found by name too.
        assertEquals(Retag.NEEDS_CHECK, retagEffect(written.copy(status = LyricsStatus.AI_ALIGNED, source = LyricsSource.AI, agreement = 0.9), namesChanged = true))
        // Lyrics the user made or edited, lyrics the AI heard, and a `.lrc` KJB can't vouch for never do.
        assertEquals(Retag.NONE, retagEffect(written.copy(mine = true), namesChanged = true))
        assertEquals(Retag.NONE, retagEffect(written.copy(status = LyricsStatus.AI_TRANSCRIBED, source = LyricsSource.AI, lrclibId = null), namesChanged = true))
        assertEquals(Retag.NONE, retagEffect(written.copy(sha256 = null), namesChanged = true))
    }

    @Test
    fun `names compare after normalising, so case and punctuation are no change`() {
        assertFalse(namesChanged("Salt Road", "Mira Solen", "salt road!", "MIRA SOLEN"))
        assertFalse(namesChanged("Café Lights", "Mira Solen", "Cafe Lights", "Mira Solen"))
        assertTrue(namesChanged("Salt Road", "Mira Solen", "Salt Roads", "Mira Solen"))
        assertTrue(namesChanged("Salt Road", "Mira Solen", "Salt Road", "Glass Harbour"))
        assertTrue(namesChanged("Salt Road", null, "Salt Road", "Mira Solen"))
    }

    // Through the pipeline

    @Test
    fun `a song not found is looked up again under its new names`() = runTest {
        val old = audio(title = "Track 04", artist = "Unknown")
        ledger.put(recorded(null, old, LyricsStatus.NOT_FOUND, null, BASE))
        client.onGet = { LrclibResult.Found(listOf(record(42))) }
        val renamed = old.copy(title = "Salt Road", artist = "Mira Solen")

        assertEquals(Retag.LOOK_UP, pipeline().retagged(renamed, namesChanged = true))

        assertEquals(listOf("Salt Road"), client.gets.map { it.title })
        assertEquals(LyricsStatus.SYNCED, ledger[old.path]?.status)
        assertEquals("Salt Road", ledger[old.path]?.title)
        assertTrue(sidecar(old).isFile)
    }

    @Test
    fun `removed, instrumental and another length are never looked up again`() = runTest {
        for (status in listOf(LyricsStatus.REMOVED, LyricsStatus.INSTRUMENTAL, LyricsStatus.DURATION_MISMATCH, LyricsStatus.AI_REJECTED)) {
            val track = audio()
            ledger.put(recorded(null, track, status, null, BASE))

            assertEquals(status.name, Retag.NONE, pipeline().retagged(track.copy(title = "Salt Roads"), namesChanged = true))

            assertEquals(status.name, 0, client.requests)
            assertEquals(status.name, status, ledger[track.path]?.status)
        }
    }

    @Test
    fun `KJB's own lyrics wait for a check with their lyrics as the one option, and the lrc stays`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        val lrc = pipeline.withLyrics(track)
        val sha = ledger[track.path]?.sha256

        assertEquals(Retag.NEEDS_CHECK, pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true))

        val entry = ledger[track.path]
        assertEquals(LyricsStatus.NEEDS_CHECK, entry?.status)
        assertEquals(listOf(42L), entry?.candidates?.map { it.id })
        assertEquals("Salt Road", entry?.candidates?.single()?.title)
        assertEquals("Salt Roads", entry?.title)
        assertEquals(sha, entry?.sha256)
        assertEquals(lrc, sidecar(track).readText())
        assertEquals(0, client.requests)
    }

    @Test
    fun `lyrics the user made are never marked`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        pipeline.withLyrics(track)
        ledger.put(ledger[track.path]!!.copy(mine = true))

        assertEquals(Retag.NONE, pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true))

        assertEquals(LyricsStatus.SYNCED, ledger[track.path]?.status)
    }

    @Test
    fun `an lrc changed since KJB wrote it is not marked`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        pipeline.withLyrics(track)
        sidecar(track).appendText("[00:09.00]w0009\n")

        assertEquals(Retag.NONE, pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true))

        assertEquals(LyricsStatus.SYNCED, ledger[track.path]?.status)
    }

    @Test
    fun `the check survives the queue sweep instead of turning into a file that was there`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        pipeline.withLyrics(track)
        val renamed = track.copy(title = "Salt Roads")
        pipeline.retagged(renamed, namesChanged = true)

        assertEquals(LyricsStatus.NEEDS_CHECK, pipeline.process(renamed, force = true)?.status)

        assertEquals(LyricsStatus.NEEDS_CHECK, ledger[track.path]?.status)
        assertEquals(0, client.requests)
    }

    @Test
    fun `this one on the lyrics it has keeps them as KJB's own`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        val lrc = pipeline.withLyrics(track)
        pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true)

        assertEquals(Checked.Done(LyricsStatus.SYNCED), pipeline.choose(track.path, 42))

        val entry = ledger[track.path]
        assertEquals(LyricsStatus.SYNCED, entry?.status)
        assertTrue(entry?.removable == true)
        assertEquals(emptyList<Candidate>(), entry?.candidates)
        assertEquals(lrc, sidecar(track).readText())
        assertEquals(0, client.requests)
    }

    @Test
    fun `other lyrics picked replace KJB's own lrc`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        pipeline.withLyrics(track)
        pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true)
        client.onId = { LrclibResult.Found(listOf(record(77, track = "Salt Roads", synced = otherSynced))) }

        assertEquals(Checked.Done(LyricsStatus.SYNCED), pipeline.choose(track.path, 77))

        assertEquals(77L, ledger[track.path]?.lrclibId)
        assertTrue(sidecar(track).readText().contains("w0101"))
    }

    @Test
    fun `none of these takes KJB's lyrics away and the song is not found`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        pipeline.withLyrics(track)
        pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true)

        assertEquals(Checked.Done(LyricsStatus.NOT_FOUND), pipeline.decline(track.path))

        assertFalse(sidecar(track).exists())
        assertEquals(LyricsStatus.NOT_FOUND, ledger[track.path]?.status)
    }

    // Review D: the user's own choice is never touched

    @Test
    fun `lyrics the user picked in Check these are never marked for a check`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        ledger.put(recorded(null, track, LyricsStatus.NEEDS_CHECK, null, BASE, candidates = listOf(Candidate(42, "Salt Road", "Mira Solen", null, 200.0, synced = true))))
        client.onId = { LrclibResult.Found(listOf(record(42))) }
        assertEquals(Checked.Done(LyricsStatus.SYNCED), pipeline.choose(track.path, 42))
        val lrc = sidecar(track).readText()

        assertEquals(Retag.NONE, pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true))

        assertEquals(LyricsStatus.SYNCED, ledger[track.path]?.status)
        assertEquals(lrc, sidecar(track).readText())
    }

    @Test
    fun `None of these keeps the lrc when the ledger can't be written`() = runTest {
        val pipeline = pipeline()
        val track = audio()
        val lrc = pipeline.withLyrics(track)
        pipeline.retagged(track.copy(title = "Salt Roads"), namesChanged = true)
        ledger.accepts = { false }

        assertEquals(Checked.NotSaved, pipeline.decline(track.path))

        assertEquals(lrc, sidecar(track).readText())
        assertEquals(LyricsStatus.NEEDS_CHECK, ledger[track.path]?.status)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
    }
}
