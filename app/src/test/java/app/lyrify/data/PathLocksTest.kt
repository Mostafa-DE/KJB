package app.lyrify.data

import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The per-path lock the lyrics pipeline, the tag writer and the journal recovery share. Made-up names only. */
class PathLocksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val song = "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3"
    private val other = "/storage/emulated/0/Music/KJB test/Tin Roof.mp3"

    @Test
    fun `a held path is busy to everyone else until it is released, other paths are not`() {
        val locks = PathLocks()
        assertTrue(locks.tryHold(song))
        assertTrue(song in locks)
        assertFalse(locks.tryHold(song))
        assertTrue(locks.tryHold(other))
        locks.release(song)
        assertFalse(song in locks)
        assertTrue(locks.tryHold(song))
    }

    @Test
    fun `a pinned path stays held for tag writes, the AI and the preview, while the lyrics pipeline goes on`() = runTest {
        val locks = PathLocks()
        locks.pin(song)
        assertTrue(locks.pinned(song))
        assertTrue(song in locks)
        assertFalse(locks.tryHold(song))
        // The pipeline never touches a song's bytes: it is not kept waiting for good.
        assertEquals("ran", locks.exclusive(song) { "ran" })
        assertTrue(song in locks)
        assertFalse(locks.pinned(other))
    }

    @Test
    fun `exclusive waits for the path, then holds it while its block runs`() = runTest {
        val locks = PathLocks()
        locks.tryHold(song)
        val order = mutableListOf<String>()
        val waiting = async { locks.exclusive(song) { order += "ran"; song in locks } }
        runCurrent()
        assertEquals(emptyList<String>(), order)

        locks.release(song)
        assertTrue(waiting.await())
        assertEquals(listOf("ran"), order)
        assertFalse(song in locks)
    }

    @Test
    fun `hold waits for the path and keeps it until released`() = runTest {
        val locks = PathLocks()
        locks.tryHold(song)
        val held = launch { locks.hold(song) }
        runCurrent()
        assertFalse(held.isCompleted)
        locks.release(song)
        held.join()
        assertTrue(song in locks)
        assertFalse(locks.tryHold(song))
    }

    @Test
    fun `the lyrics pipeline waits while the tag writer holds the song`() = runTest {
        val locks = PathLocks()
        val client = FakeLrclib()
        val pipeline = LyricsPipeline(client = client, ledger = MemoryLedger(), clock = { 1_800_000_000_000L }, writes = SharedWrites.ANYWHERE, locks = locks)
        val file = File(folder.root, "Paper Lantern.mp3").apply { writeBytes(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 1, 2)) }
        val track = LocalTrack(file.path, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000)

        assertTrue(locks.tryHold(track.path))
        val processing = async { pipeline.process(track, force = true) }
        runCurrent()
        assertFalse(processing.isCompleted)
        assertEquals(0, client.requests)

        locks.release(track.path)
        processing.await()
        assertTrue(client.requests > 0)
        assertFalse(track.path in locks)
    }
}
