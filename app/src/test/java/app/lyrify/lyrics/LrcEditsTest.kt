package app.lyrify.lyrics

import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStats
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrites
import app.lyrify.data.recorded
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The sync editor's writes (LrcEdits): Save creates a `.lrc` or replaces one with a private backup, marks the lyrics as
 * the user's in the ledger, and Undo puts back what was there; the pipeline never replaces the user's lyrics; the
 * player's Search uses a record for any song without lyrics. Made-up names and lines only.
 */
class LrcEditsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ledger = MemoryLedger()
    private val client = FakeLrclib()
    private val logs = mutableListOf<String>()
    private val held = mutableListOf<String>()

    /** The pipeline's hold, as a list of the songs held. */
    private val hold = object : PathHold {
        override suspend fun <T> holding(path: String, block: suspend () -> T): T {
            held += path
            return block()
        }
    }

    private val backups: File get() = File(folder.root, "app/files/lrc-backups")

    private fun edits(writes: SharedWrites = SharedWrites.ANYWHERE) = LrcEdits(ledger, writes, hold, backups, clock = { NOW }, log = { logs += it })

    private fun TestScope.pipeline() = LyricsPipeline(client, ledger, clock = { NOW + testScheduler.currentTime }, writes = SharedWrites.ANYWHERE, log = { logs += it })

    private val audioBytes = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 7, 7, 7)

    private fun audio(name: String = "Harbour Lights.mp3"): LocalTrack {
        val dir = File(folder.root, "Music/Tin Roof Choir").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(audioBytes)
        return LocalTrack(file.path, "Harbour Lights", "Tin Roof Choir", "Paper Boats", 200_000)
    }

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val saved = "[00:01.000]Paper lanterns rise\n[00:04.500]Over the harbour glass\n[00:09.000]And the ferry hums\n"
    private val before = "[ar:Tin Roof Choir]\n[00:01.20]Paper lanterns rise\n[00:04.80]Over the harbour glass\n[00:09.30]And the ferry hums\n"

    private fun entry(track: LocalTrack, status: LyricsStatus, lrclibId: Long? = null): LedgerEntry = recorded(null, track, status, lrclibId, NOW - 1_000)

    // Save

    @Test
    fun `a new lrc is created next to the song and marked as the user's`() = runTest {
        val track = audio()
        val plain = entry(track, LyricsStatus.PLAIN_ONLY, lrclibId = 7)
        ledger.put(plain)

        val result = edits().save(track, saved)

        assertTrue(result is LrcSave.Saved)
        assertEquals(saved, sidecar(track).readText())
        val mine = ledger[track.path]!!
        assertEquals(LyricsStatus.SYNCED, mine.status)
        assertTrue(mine.mine)
        assertEquals(sha(saved), mine.sha256)
        assertEquals(saved.toByteArray().size.toLong(), mine.size)
        assertEquals(7L, mine.lrclibId)
        assertNull(mine.nextAttemptAt)
        assertEquals(NOW, mine.updatedAt)
        // Saved under the pipeline's hold on the song, with no backup: there was nothing to keep.
        assertEquals(listOf(track.path), held)
        assertEquals(LrcUndo(track.path, sha(saved), null, plain), (result as LrcSave.Saved).undo)
        assertFalse(backups.exists() && backups.list()!!.isNotEmpty())
        assertArrayEquals(audioBytes, File(track.path).readBytes())
    }

    @Test
    fun `saving over an lrc that was there keeps a private copy, and Undo puts it and its entry back`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val existing = entry(track, LyricsStatus.EXISTING)
        ledger.put(existing)

        val result = edits().save(track, saved) as LrcSave.Saved

        assertEquals(saved, sidecar(track).readText())
        assertTrue(ledger[track.path]!!.mine)
        assertEquals(LyricsStatus.SYNCED, ledger[track.path]!!.status)
        val backup = result.undo.backup ?: error("no backup kept")
        assertEquals(before, backup.readText())
        // The copy is private, and no temporary file is left beside the song.
        assertTrue(backup.toPath().startsWith(backups.toPath()))
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc"), File(track.path).parentFile!!.list()!!.toSet())

        assertEquals(LrcUndone.DONE, edits().undo(result.undo))

        assertEquals(before, sidecar(track).readText())
        assertEquals(existing, ledger[track.path])
        assertFalse(backup.exists())
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc"), File(track.path).parentFile!!.list()!!.toSet())
    }

    @Test
    fun `a second save keeps the copy of what the first one replaced, so Undo goes back one save`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        edits().save(track, saved)
        val again = saved.replace("[00:09.000]", "[00:09.200]")

        val second = edits().save(track, again) as LrcSave.Saved

        assertEquals(saved, second.undo.backup!!.readText())
        assertEquals(LrcUndone.DONE, edits().undo(second.undo))
        assertEquals(saved, sidecar(track).readText())
        assertTrue(ledger[track.path]!!.mine)
    }

    @Test
    fun `Undo of a new lrc deletes it and forgets the mark`() = runTest {
        val track = audio()
        val result = edits().save(track, saved) as LrcSave.Saved

        assertEquals(LrcUndone.DONE, edits().undo(result.undo))

        assertFalse(sidecar(track).exists())
        assertNull(ledger[track.path])
    }

    @Test
    fun `Undo leaves an lrc that changed since the save`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val result = edits().save(track, saved) as LrcSave.Saved
        sidecar(track).writeText("[00:02.000]Someone else's line\n")
        val marked = ledger[track.path]

        assertEquals(LrcUndone.CHANGED, edits().undo(result.undo))

        assertEquals("[00:02.000]Someone else's line\n", sidecar(track).readText())
        assertEquals(marked, ledger[track.path])
        // The copy stays: it is the only one left of the file before.
        assertTrue(result.undo.backup!!.exists())
    }

    @Test
    fun `a debug build saves nothing outside its test folder`() = runTest {
        val debug = SharedWrites(testFolderOnly = true)
        val fresh = audio("Kitchen Radio.mp3")
        val existing = audio()
        sidecar(existing).writeText(before)

        assertEquals(LrcSave.Refused, edits(debug).save(fresh, saved))
        assertEquals(LrcSave.Refused, edits(debug).save(existing, saved))

        assertFalse(sidecar(fresh).exists())
        assertEquals(before, sidecar(existing).readText())
        assertNull(ledger[fresh.path])
        assertNull(ledger[existing.path])
    }

    @Test
    fun `a link where the lrc goes is never written through`() = runTest {
        val track = audio()
        val elsewhere = File(folder.root, "elsewhere.lrc").apply { writeText(before) }
        try {
            Files.createSymbolicLink(sidecar(track).toPath(), elsewhere.toPath())
        } catch (_: IOException) {
            assumeTrue("symbolic links are not supported here", false)
        } catch (_: UnsupportedOperationException) {
            assumeTrue("symbolic links are not supported here", false)
        }

        assertEquals(LrcSave.Failed, edits().save(track, saved))

        assertEquals(before, elsewhere.readText())
        assertTrue(Files.isSymbolicLink(sidecar(track).toPath()))
        assertNull(ledger[track.path])
    }

    @Test
    fun `a ledger that cannot be written still keeps the saved lyrics`() = runTest {
        val track = audio()
        ledger.accepts = { false }

        assertTrue(edits().save(track, saved) is LrcSave.Saved)

        assertEquals(saved, sidecar(track).readText())
    }

    @Test
    fun `the log names no song, file or line`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val result = edits().save(track, saved) as LrcSave.Saved
        edits().undo(result.undo)

        assertTrue(logs.isNotEmpty())
        logs.forEach { line ->
            listOf("Harbour", "Tin Roof", "Paper", "lantern", folder.root.path, ".lrc", ".mp3").forEach { word ->
                assertFalse("$line names $word", line.contains(word, ignoreCase = true))
            }
        }
    }

    // The pipeline and the user's lyrics

    @Test
    fun `the pipeline never replaces the user's lyrics, not even once their file is gone`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(LrclibRecord(3, "Harbour Lights", "Tin Roof Choir", "Paper Boats", 200.0, syncedLyrics = saved))) }
        edits().save(track, saved)
        sidecar(track).delete()

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track, force = true)?.status)

        assertEquals(0, client.requests)
        assertFalse(sidecar(track).exists())
        assertTrue(ledger[track.path]!!.mine)
    }

    @Test
    fun `the AI leaves the user's lyrics alone`() = runTest {
        val track = audio()
        ledger.put(entry(track, LyricsStatus.NOT_FOUND).copy(mine = true, lookupRev = LOOKUP_REV))

        assertEquals(LyricsStatus.NOT_FOUND, pipeline().recordAi(track, AiResult.Transcribed(saved, AiStats(200.0, 0.6, 80.0, 4, 120.0, 3, 3, emptyMap()))))

        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `Search on the player uses a record for a song that waits for no check, and never over the user's lyrics`() = runTest {
        val track = audio()
        val other = audio("Kitchen Radio.mp3")
        ledger.put(entry(track, LyricsStatus.NOT_FOUND))
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, "Harbour Lights", "Tin Roof Choir", null, 200.0, plainLyrics = "a", syncedLyrics = saved))) }
        val pipeline = pipeline()

        // The check list's own answer still needs a song that waits for a check.
        assertEquals(Checked.Changed, pipeline.choose(track.path, 3))
        assertEquals(Checked.Done(LyricsStatus.SYNCED), pipeline.choose(track.path, 3, track))
        assertEquals(saved, sidecar(track).readText())
        assertEquals(LyricsSource.LRCLIB, ledger[track.path]?.source)

        // A song the pipeline never saw.
        assertEquals(Checked.Done(LyricsStatus.SYNCED), pipeline.choose(other.path, 3, other.copy(title = "Kitchen Radio")))
        assertTrue(sidecar(other).exists())

        // Lyrics the user made win, even once their file is gone.
        val mine = audio("Porch Light.mp3")
        ledger.put(entry(mine, LyricsStatus.SYNCED).copy(mine = true))
        assertEquals(Checked.Changed, pipeline.choose(mine.path, 3, mine))
        assertFalse(sidecar(mine).exists())
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000L
    }
}
