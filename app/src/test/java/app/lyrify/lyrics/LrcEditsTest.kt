package app.lyrify.lyrics

import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStats
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrites
import app.lyrify.data.recorded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The sync editor's writes (LrcEdits): Save writes a `.lrc` in one step (a new one or over one, with a private copy),
 * marks the lyrics as the user's in the ledger or puts everything back, and keeps its Undo on disk until it is used or
 * let go; Undo puts back what was there; the pipeline never replaces the user's lyrics; the player's Search uses a
 * record for any song without lyrics. Made-up names and lines only.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
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

    /** App-private: the pending undos and their copies. */
    private val undos: File get() = File(folder.root, "app/files/lrc-undo")
    private var ids = 0

    private fun edits(writes: SharedWrites = SharedWrites.ANYWHERE, writeBytes: (FileChannel, ByteBuffer) -> Unit = SidecarFile::writeFully) =
        LrcEdits(ledger, writes, hold, undos, clock = { NOW }, log = { logs += it }, writeBytes = writeBytes, newId = { "u${++ids}" })

    private fun copy(undo: LrcUndo): File = File(undos, undo.backup ?: error("no copy kept"))

    private fun songFolder(track: LocalTrack): Set<String> = File(track.path).parentFile!!.list()!!.toSet()

    /** The process killed while the bytes go in, half of them written: no cleanup runs after that. */
    private class Killed : Error("killed")

    /** What the song's `.lrc` held the moment the process died: null when there was none. */
    private var atKill: String? = "not killed"

    private fun dies(lrc: File): (FileChannel, ByteBuffer) -> Unit = { channel, buffer ->
        buffer.limit(buffer.limit() / 2)
        SidecarFile.writeFully(channel, buffer)
        channel.force(true)
        atKill = if (lrc.exists()) lrc.readText() else null
        throw Killed()
    }

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
        val edits = edits()

        val result = edits.save(track, saved)

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
        // Saved under the pipeline's hold on the song, with no copy: there was nothing to keep.
        assertEquals(listOf(track.path), held)
        val undo = LrcUndo("u1", track.path, sha(saved), null, plain)
        assertEquals(undo, (result as LrcSave.Saved).undo)
        assertEquals(listOf(undo), edits.pending.value)
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc"), songFolder(track))
        assertArrayEquals(audioBytes, File(track.path).readBytes())
    }

    @Test
    fun `saving over an lrc that was there keeps a private copy, and Undo puts it and its entry back`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val existing = entry(track, LyricsStatus.EXISTING)
        ledger.put(existing)
        val edits = edits()

        val result = edits.save(track, saved) as LrcSave.Saved

        assertEquals(saved, sidecar(track).readText())
        assertTrue(ledger[track.path]!!.mine)
        assertEquals(LyricsStatus.SYNCED, ledger[track.path]!!.status)
        val backup = copy(result.undo)
        assertEquals(before, backup.readText())
        // The copy is private, and no temporary file is left beside the song.
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc"), songFolder(track))

        assertEquals(LrcUndone.DONE, edits.undo(result.undo))

        assertEquals(before, sidecar(track).readText())
        assertEquals(existing, ledger[track.path])
        assertFalse(backup.exists())
        assertEquals(emptyList<LrcUndo>(), edits.pending.value)
        assertEquals(emptyList<String>(), undos.list()!!.toList())
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc"), songFolder(track))
    }

    @Test
    fun `a second save of the song keeps the copy the first one's Undo still needs`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val edits = edits()
        val first = edits.save(track, saved) as LrcSave.Saved
        val again = saved.replace("[00:09.000]", "[00:09.200]")

        val second = edits.save(track, again) as LrcSave.Saved

        assertEquals(before, copy(first.undo).readText())
        assertEquals(saved, copy(second.undo).readText())
        assertEquals(listOf(first.undo, second.undo), edits.pending.value)
        assertEquals(LrcUndone.DONE, edits.undo(second.undo))
        assertEquals(saved, sidecar(track).readText())
        assertTrue(ledger[track.path]!!.mine)
        assertTrue(copy(first.undo).exists())
    }

    @Test
    fun `Undo waits on disk until it is used or let go, also across a restart of the app`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val existing = entry(track, LyricsStatus.EXISTING)
        ledger.put(existing)
        val undo = (edits().save(track, saved) as LrcSave.Saved).undo

        // The app starts again: a new LrcEdits finds it.
        val restarted = edits()
        assertEquals(emptyList<LrcUndo>(), restarted.pending.value)
        restarted.load()
        assertEquals(listOf(undo), restarted.pending.value)
        assertEquals(LrcUndone.DONE, restarted.undo(undo))
        assertEquals(before, sidecar(track).readText())
        assertEquals(existing, ledger[track.path])

        // Let go: its files go, the lyrics stay.
        val other = (restarted.save(track, saved) as LrcSave.Saved).undo
        restarted.forget(other)
        assertEquals(emptyList<LrcUndo>(), restarted.pending.value)
        assertEquals(emptyList<String>(), undos.list()!!.toList())
        assertEquals(saved, sidecar(track).readText())
        val again = edits()
        again.load()
        assertEquals(emptyList<LrcUndo>(), again.pending.value)
    }

    @Test
    fun `Undo of a new lrc deletes it and forgets the mark`() = runTest {
        val track = audio()
        val edits = edits()
        val result = edits.save(track, saved) as LrcSave.Saved

        assertEquals(LrcUndone.DONE, edits.undo(result.undo))

        assertFalse(sidecar(track).exists())
        assertNull(ledger[track.path])
        assertEquals(emptyList<LrcUndo>(), edits.pending.value)
    }

    @Test
    fun `Undo leaves an lrc that changed since the save, and lets the Undo go`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val edits = edits()
        val result = edits.save(track, saved) as LrcSave.Saved
        sidecar(track).writeText("[00:02.000]Someone else's line\n")
        val marked = ledger[track.path]

        assertEquals(LrcUndone.CHANGED, edits.undo(result.undo))

        assertEquals("[00:02.000]Someone else's line\n", sidecar(track).readText())
        assertEquals(marked, ledger[track.path])
        assertEquals(emptyList<LrcUndo>(), edits.pending.value)
    }

    @Test
    fun `Undo that cannot write the ledger changes nothing and keeps the copy, to be tried again`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val existing = entry(track, LyricsStatus.EXISTING)
        ledger.put(existing)
        val edits = edits()
        val result = edits.save(track, saved) as LrcSave.Saved
        val marked = ledger[track.path]
        ledger.accepts = { false }

        assertEquals(LrcUndone.FAILED, edits.undo(result.undo))

        assertEquals(saved, sidecar(track).readText())
        assertEquals(marked, ledger[track.path])
        assertEquals(before, copy(result.undo).readText())
        assertEquals(listOf(result.undo), edits.pending.value)

        ledger.accepts = { true }
        assertEquals(LrcUndone.DONE, edits.undo(result.undo))
        assertEquals(before, sidecar(track).readText())
        assertEquals(existing, ledger[track.path])
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
    fun `a Save that cannot mark the lyrics as the user's puts the file back and fails`() = runTest {
        val fresh = audio("Kitchen Radio.mp3")
        val existing = audio()
        sidecar(existing).writeText(before)
        ledger.accepts = { false }
        val edits = edits()

        assertEquals(LrcSave.Failed, edits.save(fresh, saved))
        assertEquals(LrcSave.Failed, edits.save(existing, saved))

        assertFalse(sidecar(fresh).exists())
        assertEquals(before, sidecar(existing).readText())
        assertEquals(emptyList<LrcUndo>(), edits.pending.value)
        assertEquals(emptyList<String>(), undos.list().orEmpty().toList())
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc", "Kitchen Radio.mp3"), songFolder(existing))
    }

    @Test
    fun `a Save killed while it writes leaves no partial lrc, and the next one tidies up`() = runTest {
        val fresh = audio("Kitchen Radio.mp3")
        val existing = audio()
        sidecar(existing).writeText(before)

        assertThrows(Killed::class.java) { runBlocking { edits(writeBytes = dies(sidecar(fresh))).save(fresh, saved) } }
        // Up to the kill, no `.lrc` was there: the bytes went to a file beside it.
        assertNull(atKill)
        assertThrows(Killed::class.java) { runBlocking { edits(writeBytes = dies(sidecar(existing))).save(existing, saved) } }
        assertEquals(before, atKill)

        assertFalse(sidecar(fresh).exists())
        assertEquals(before, sidecar(existing).readText())
        assertNull(ledger[fresh.path])
        // Over a `.lrc` that was there, the write's marker waits for the next start: the file before is the user's, as it is.
        assertEquals(LyricsStatus.WRITING, ledger[existing.path]?.status)
        pipeline().finishInterrupted()
        assertEquals(LyricsStatus.EXISTING, ledger[existing.path]?.status)
        assertTrue(ledger[existing.path]?.mine == true)
        assertEquals(before, sidecar(existing).readText())
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc", "Kitchen Radio.mp3", ".Kitchen Radio.lrc.kjb-tmp"), songFolder(existing))

        assertTrue(edits().save(fresh, saved) is LrcSave.Saved)
        assertTrue(edits().save(existing, saved) is LrcSave.Saved)
        assertEquals(setOf("Harbour Lights.mp3", "Harbour Lights.lrc", "Kitchen Radio.mp3", "Kitchen Radio.lrc"), songFolder(existing))
    }

    @Test
    fun `a Save cut off between the rename and the ledger is the user's lyrics at the next start`() = runTest {
        val existing = audio()
        sidecar(existing).writeText(before)
        // Nothing more reaches the ledger once the lines are in place: as if the app died right after the rename.
        ledger.accepts = { it.status != LyricsStatus.SYNCED }

        assertTrue(edits().save(existing, saved) is LrcSave.Saved)
        assertEquals(saved, sidecar(existing).readText())

        ledger.accepts = { true }
        pipeline().finishInterrupted()
        val entry = ledger[existing.path]
        assertEquals(LyricsStatus.SYNCED, entry?.status)
        assertEquals(sha(saved), entry?.sha256)
        assertTrue(entry?.mine == true)
    }

    @Test
    fun `lyrics files are read only as regular files of at most 1 MiB, never through a link`() {
        val dir = folder.newFolder("read")
        val small = File(dir, "a.lrc").apply { writeText(before) }
        assertEquals(before, readSidecar(small.toPath())?.decodeToString())
        val large = File(dir, "b.lrc").apply { writeBytes(ByteArray(1024 * 1024 + 1)) }
        assertNull(readSidecar(large.toPath()))
        assertNull(readSidecar(File(dir, "missing.lrc").toPath()))
        assertNull(readSidecar(dir.toPath()))
        val link = File(dir, "c.lrc")
        try {
            Files.createSymbolicLink(link.toPath(), small.toPath())
        } catch (_: IOException) {
            assumeTrue("symbolic links are not supported here", false)
        } catch (_: UnsupportedOperationException) {
            assumeTrue("symbolic links are not supported here", false)
        }
        assertNull(readSidecar(link.toPath()))
    }

    @Test
    fun `an editor brought back can tell its lyrics were saved`() = runTest {
        val track = audio()
        assertFalse(edits().isOnDisk(track.path, saved))
        edits().save(track, saved)
        assertTrue(edits().isOnDisk(track.path, saved))
        assertFalse(edits().isOnDisk(track.path, before))
    }

    @Test
    fun `the log names no song, file or line`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        val edits = edits()
        val result = edits.save(track, saved) as LrcSave.Saved
        edits.undo(result.undo)

        assertTrue(logs.isNotEmpty())
        logs.forEach { line ->
            listOf("Harbour", "Tin Roof", "Paper", "lantern", folder.root.path, ".lrc", ".mp3").forEach { word ->
                assertFalse("$line names $word", line.contains(word, ignoreCase = true))
            }
        }
    }

    // The pipeline and the user's lyrics

    @Test
    fun `after Save the pipeline leaves the user's lyrics as they are`() = runTest {
        val track = audio()
        sidecar(track).writeText(before)
        ledger.put(entry(track, LyricsStatus.EXISTING))
        client.onGet = { LrclibResult.Found(listOf(LrclibRecord(3, "Harbour Lights", "Tin Roof Choir", "Paper Boats", 200.0, syncedLyrics = saved))) }
        edits().save(track, saved)
        val marked = ledger[track.path]

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track, force = true)?.status)

        assertEquals(0, client.requests)
        assertEquals(saved, sidecar(track).readText())
        assertEquals(marked, ledger[track.path])
    }

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
