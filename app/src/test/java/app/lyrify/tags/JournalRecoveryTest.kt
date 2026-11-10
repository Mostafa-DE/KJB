package app.lyrify.tags

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The app killed at every step of a tag write, then started again: the start-up recovery settles every unfinished
 * intent first, holding each song meanwhile. A song is left either exactly as it was or exactly as written, never half
 * written, and a backup that is not whole is never written back. Made-up names only.
 */
class JournalRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val rig by lazy { WriterRig(folder.root) }

    private val fields = mapOf("TITLE" to listOf("paper lantern"), "LYRICS" to listOf("la la la"), "COMMENT" to listOf("made up"))

    /** A new cover makes the tag bigger, so the write shifts the audio: the case a kill could leave half done. */
    private val edit = TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern")), cover = CoverEdit.Replace(TagPicture(ByteArray(2_000) { 3 }, "", FRONT_COVER, "image/jpeg")))

    private fun killedWhileWriting(song: SongTarget) {
        assertThrows(SimulatedKill::class.java) {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        }
        // A new process over the same files; whatever the dead one held is gone with it.
        rig.locks = app.lyrify.data.PathLocks()
        rig.restart()
    }

    private fun recover(): Recovered = runBlocking { rig.recovery().recover() }

    /** The bytes the write would have left: the same edit on an untouched copy. */
    private fun written(song: SongTarget, original: ByteArray): ByteArray {
        val copy = WriterRig(folder.newFolder())
        val twin = copy.song("Paper Lantern", song.mediaStoreId, fields)
        File(twin.path).writeBytes(original)
        runBlocking { copy.writer.write(listOf(WriteJob(twin, edit)), EditKind.SONG, "Paper Lantern") }
        return copy.bytes(twin)
    }

    @Test
    fun `killed while backing up or making the private copy - the song was never opened, and nothing is left`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        for (target in listOf(rig.dirs.backups, rig.dirs.work)) {
            rig.copy = { from, to ->
                if (to.parentFile == target) {
                    to.parentFile.mkdirs()
                    to.writeBytes(from.readBytes().copyOf(100))
                    throw SimulatedKill()
                }
                durableCopy(from, to, rig.folders::sync)
            }
            killedWhileWriting(song)
            assertEquals(Recovered(), recover())
            assertArrayEquals(original, rig.bytes(song))
            assertEquals(emptyList<String>(), rig.leftovers())
        }
        assertFalse("open rw" in rig.events)
    }

    @Test
    fun `killed while the intent was being written - the song was never opened`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        // As the dead process left it: a backup, a private copy and an intent only half written aside.
        rig.dirs.journal.mkdirs()
        File(rig.dirs.journal, "1800000000000-41-1.json.tmp").writeText("{\"id\":\"1800000000000-41-1\",\"pa")
        rig.dirs.backups.mkdirs()
        File(rig.dirs.backups, "1800000000000-41-1.mp3").writeBytes(original)

        assertEquals(Recovered(), recover())
        assertArrayEquals(original, rig.bytes(song))
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    @Test
    fun `killed at any point of the write in place - the song is put back exactly as it was`() {
        val plans = listOf<WritePlan>(WritePlan.KillAfter(0), WritePlan.KillAfter(5), WritePlan.KillAfter(3_000))
        plans.forEachIndexed { index, plan ->
            val song = rig.song("Song $index", 50L + index, fields)
            val original = rig.bytes(song)
            rig.songs.plans += plan

            killedWhileWriting(song)
            val recovered = recover()

            assertArrayEquals("$plan", original, rig.bytes(song))
            assertEquals("$plan", 0, rig.journal.pending().size)
            assertEquals("$plan", emptyList<String>(), rig.leftovers())
            assertEquals("$plan", 1, recovered.settled)
        }
    }

    @Test
    fun `killed before a song that got shorter was cut to its new length - it is put back as it was`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(TagPicture(ByteArray(5_000) { 9 }, "", FRONT_COVER, "image/jpeg")))
        val original = rig.bytes(song)
        rig.songs.plans += WritePlan.KillOnTruncate
        assertThrows(SimulatedKill::class.java) {
            runBlocking { rig.writer.write(listOf(WriteJob(song, TagEdit(cover = CoverEdit.Remove))), EditKind.SONG, "Paper Lantern") }
        }
        rig.restart()

        assertEquals(Recovered(settled = 1, restored = 1), recover())
        assertArrayEquals(original, rig.bytes(song))
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    @Test
    fun `killed after the whole write - the song keeps its new tags, and the intent is closed`() {
        for (step in listOf("fsync", "scan")) {
            val song = rig.song("Song $step", if (step == "fsync") 61 else 62, fields)
            val original = rig.bytes(song)
            if (step == "fsync") rig.songs.plans += WritePlan.KillOnForce else rig.songs.killOnScan = true

            killedWhileWriting(song)
            val recovered = recover()

            assertEquals(step, Recovered(settled = 1), recovered)
            assertArrayEquals(step, written(song, original), rig.bytes(song))
            assertEquals(step, emptyList<String>(), rig.leftovers())
        }
    }

    @Test
    fun `killed while putting the backup back after a failure - the next start puts it back`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.songs.plans += WritePlan.FailAfter(1_000)
        rig.songs.plans += WritePlan.KillAfter(500)

        killedWhileWriting(song)
        assertEquals(Recovered(settled = 1, restored = 1), recover())
        assertArrayEquals(original, rig.bytes(song))
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    @Test
    fun `a short backup is never written back - the intent and the backup are set aside`() {
        val song = rig.song("Paper Lantern", 41, fields)
        rig.songs.plans += WritePlan.KillAfter(3_000)
        killedWhileWriting(song)
        val broken = rig.bytes(song)
        val backup = rig.dirs.backups.listFiles()!!.single()
        backup.writeBytes(backup.readBytes().copyOf(backup.length().toInt() - 10))

        val recovered = recover()

        assertEquals(Recovered(setAside = 1), recovered)
        // Opened, to be read through, but never written: the song is as the kill left it.
        assertArrayEquals(broken, rig.bytes(song))
        assertEquals(0, rig.journal.pending().size)
        assertTrue(backup.isFile)
        assertEquals(1, File(rig.dirs.journal, WriteJournal.SET_ASIDE).listFiles()!!.size)
    }

    @Test
    fun `the recovery holds every journaled song until it is settled`() {
        val songs = listOf(rig.song("Paper Lantern", 41, fields), rig.song("Tin Roof", 42, fields))
        for (song in songs) {
            rig.songs.plans += WritePlan.KillAfter(3_000)
            killedWhileWriting(song)
        }
        val held = mutableListOf<List<Boolean>>()
        rig.songs.onOpen = { held += songs.map { it.path in rig.locks } }

        assertEquals(2, recover().restored)

        // Both songs were held from the first restore on, each until it was settled; nothing is held afterwards.
        assertEquals(listOf(listOf(true, true), listOf(false, true)), held)
        assertTrue(songs.none { it.path in rig.locks })
    }

    @Test
    fun `an intent that can't be read is set aside, never acted on`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.dirs.journal.mkdirs()
        File(rig.dirs.journal, "1800000000000-41-1.json").writeText("not json")

        assertEquals(Recovered(setAside = 1), recover())
        assertArrayEquals(original, rig.bytes(song))
        assertEquals(1, File(rig.dirs.journal, WriteJournal.SET_ASIDE).listFiles()!!.size)
    }

    @Test
    fun `an intent round-trips through its file, and a closed one is gone`() {
        val journal = WriteJournal(File(folder.root, "journal"), FolderSync()::sync)
        val state = FileState(4_000, "a".repeat(64), Payload(AudioFormat.MP3, "b".repeat(64)))
        val intent = WriteIntent("1-41-1", "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3", 41, "1-41-1.mp3", state, state.copy(length = 6_000))

        assertTrue(journal.open(intent))
        assertEquals(listOf(intent), WriteJournal(File(folder.root, "journal"), FolderSync()::sync).pending())
        assertTrue(journal.close(intent.id))
        assertEquals(emptyList<WriteIntent>(), journal.pending())
    }
}
