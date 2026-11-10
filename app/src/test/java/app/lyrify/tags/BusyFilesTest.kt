package app.lyrify.tags

import app.lyrify.lyrics.AiPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which songs are busy: the current item of every media session (playing or paused), KJB's own open songs (the tone
 * preview, the player) and the AI's; and nothing at all is known while the sessions can't be listed. No log line ever
 * names a song. Made-up names only.
 */
class BusyFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val song = "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3"
    private val other = "/storage/emulated/0/Music/KJB test/Tin Roof.mp3"

    @Test
    fun `a song in any session, held by KJB or worked on by the AI is in use, others are free`() {
        var sessions: Set<String>? = emptySet()
        val held = HeldPaths()
        val ai = AiPaths()
        val busy = PhoneBusyFiles(sessions = { sessions }, held = held, ai = ai)

        assertEquals(Busy.FREE, busy.check(song))
        sessions = setOf(song)
        assertEquals(Busy.IN_USE, busy.check(song))
        assertEquals(Busy.FREE, busy.check(other))

        sessions = emptySet()
        held.hold(song)
        assertEquals(Busy.IN_USE, busy.check(song))
        held.release(song)
        assertEquals(Busy.FREE, busy.check(song))

        ai.hold(other)
        assertEquals(Busy.IN_USE, busy.check(other))

        sessions = null
        assertEquals(Busy.UNKNOWN, busy.check(song))
    }

    @Test
    fun `a song held twice, by the preview and the player, stays held until both let go`() {
        val held = HeldPaths()
        held.hold(song)
        held.hold(song)
        held.release(song)
        assertTrue(song in held)
        held.release(song)
        assertFalse(song in held)
        // Letting go of a song not held changes nothing.
        held.release(song)
        assertFalse(song in held)
    }

    @Test
    fun `no log line of a write, a failure, an undo or a recovery names a song, an artist, a folder or a URL`() {
        val rig = WriterRig(folder.root)
        val fields = mapOf("TITLE" to listOf("Paper Lantern"), "ARTIST" to listOf("Kit Calloway"), "COMMENT" to listOf("https://example.org/song"))
        val songs = (1..5).map { rig.song("Paper Lantern $it", it.toLong(), fields) }
        val edit = TagEdit(fields = mapOf("ALBUM" to listOf("Harbour Glass")))

        runBlocking {
            val written = rig.writer.write(listOf(WriteJob(songs[0], edit)), EditKind.ALBUM, "Harbour Glass") as BatchResult.Done
            rig.songs.plans += WritePlan.FailAfter(500)
            rig.writer.write(listOf(WriteJob(songs[1], edit)), EditKind.ALBUM, "Harbour Glass")
            rig.busy.inUse += songs[2].path
            rig.writer.write(listOf(WriteJob(songs[2], edit)), EditKind.ALBUM, "Harbour Glass")
            rig.songs.plans += WritePlan.FailAfter(500)
            rig.songs.plans += WritePlan.Refuse
            rig.writer.write(listOf(WriteJob(songs[3], edit)), EditKind.ALBUM, "Harbour Glass")
            rig.writer.undo(checkNotNull(written.entryId))
            rig.recovery().recover()
            rig.dirs.undo.writeText("{ broken")
            UndoStore(rig.dirs, rig.logs::add).memory()
        }

        val logs = rig.logs.toList()
        assertTrue("the run logged something", logs.isNotEmpty())
        val secrets = listOf("Paper", "Lantern", "Kit", "Calloway", "Harbour", "Glass", "Music", "KJB test", "storage", "http", ".mp3", folder.root.path)
        logs.forEach { line -> secrets.forEach { assertFalse("\"$line\" holds \"$it\"", it in line) } }
    }
}
