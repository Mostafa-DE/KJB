package app.lyrify.tags

import app.lyrify.data.PathLocks
import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The data-safety rules the review asked for: a strict verify of the private copy (fields, pictures, hidden ID3v2
 * frames), the ID3v2 version a song keeps, a last check of the song before its first byte changes, putting a backup
 * back only over the same file, durable folders, Undo that could not be saved, songs still pending, and the start-up
 * gate. Made-up names only.
 */
class WriterReviewTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val rig by lazy { WriterRig(folder.root) }

    private val fields = mapOf("TITLE" to listOf("paper lantern"), "LYRICS" to listOf("la la la"), "COMMENT" to listOf("made up"))
    private val back = TagPicture(byteArrayOf(1, 2, 3), "", "Back Cover", "image/jpeg")
    private val cover = TagPicture(ByteArray(2_000) { 7 }, "", FRONT_COVER, "image/jpeg")
    private val edit = TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern")), cover = CoverEdit.Replace(cover))

    private fun write(song: SongTarget, change: TagEdit = edit): SongOutcome =
        (runBlocking { rig.writer.write(listOf(WriteJob(song, change)), EditKind.SONG, "Paper Lantern") } as BatchResult.Done).songs.single().outcome

    private fun unopened(song: SongTarget, before: ByteArray) {
        assertArrayEquals(before, rig.bytes(song))
        assertFalse("open rw" in rig.events)
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    // 1. The verify

    @Test
    fun `a private copy whose other fields, new keys, pictures or hidden frames changed is never written`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(back), version = 4, frames = listOf("TIT2", "PRIV", "POPM"))
        val before = rig.bytes(song)
        val wrongs = listOf<Pair<() -> Unit, () -> Unit>>(
            { rig.tags.sneakField = "COMMENT" to listOf("made over") } to { rig.tags.sneakField = null },
            { rig.tags.sneakField = "MOOD" to listOf("calm") } to { rig.tags.sneakField = null },
            { rig.tags.alterPicture = { it.copy(type = "Other") } } to { rig.tags.alterPicture = null },
            { rig.tags.alterPicture = { it.copy(description = "x") } } to { rig.tags.alterPicture = null },
            { rig.tags.alterPicture = { it.copy(mime = "image/png") } } to { rig.tags.alterPicture = null },
            { rig.tags.dropFrame = "PRIV" } to { rig.tags.dropFrame = null },
        )
        wrongs.forEachIndexed { index, (setUp, undo) ->
            setUp()
            assertEquals("case $index", SongOutcome.COULD_NOT_CHANGE, write(song))
            undo()
            unopened(song, before)
        }
        assertEquals(SongOutcome.WRITTEN, write(song))
    }

    @Test
    fun `pictures are left alone when the cover does not change`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(back))
        assertEquals(SongOutcome.WRITTEN, write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern")))))
        assertEquals(listOf(back), FakeTagFile().read(File(song.path))!!.pictures)
    }

    // 9. The ID3v2 version

    @Test
    fun `an ID3v2_4 tag with frames v2_3 would drop stays v2_4, any other MP3 is written as v2_3`() {
        val kept = rig.song("Paper Lantern", 41, fields, version = 4, frames = listOf("TIT2", "RVA2", "PRIV"))
        assertEquals(SongOutcome.WRITTEN, write(kept))
        assertEquals(Id3Version.V2_4, rig.tags.lastVersion)
        assertEquals(listOf("TIT2", "RVA2", "PRIV"), FakeTagFile().read(File(kept.path))!!.frames)

        val plain = rig.song("Tin Roof", 42, fields, version = 4, frames = listOf("TIT2", "PRIV"))
        assertEquals(SongOutcome.WRITTEN, write(plain))
        assertEquals(Id3Version.V2_3, rig.tags.lastVersion)

        val old = rig.song("Salt Clock", 43, fields, version = 3, frames = listOf("TIT2"))
        assertEquals(SongOutcome.WRITTEN, write(old))
        assertEquals(Id3Version.V2_3, rig.tags.lastVersion)
        assertEquals(setOf("ASPI", "EQU2", "RVA2", "SEEK", "SIGN", "TDRL", "TDTG", "TMOO", "TPRO", "TSST"), SafeWriter.DROPPED_BY_V23)
    }

    // 2. The last check before the first byte changes

    @Test
    fun `a song that changed or moved after its private copy was made is not written`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        var checks = 0
        // The second busy check comes right before the intent: the song changes in between. Not proven as it was, so
        // its backup stays, set aside.
        rig.busy.onCheck = { if (++checks == 3) File(song.path).appendBytes(byteArrayOf(0)) }
        assertEquals(SongOutcome.SET_ASIDE, write(song))
        val changed = rig.bytes(song)
        assertArrayEquals(original + byteArrayOf(0), changed)
        assertFalse("open rw" in rig.events)
        assertEquals(0, rig.journal.pending().size)
        assertTrue(backupKept(original))

        // MediaStore's row at the path is no longer the song's: not written, and, as a row that moved can't be told from
        // a song replaced through MediaStore, its backup stays, set aside.
        File(rig.dirs.journal, WriteJournal.SET_ASIDE).deleteRecursively()
        rig.dirs.backups.listFiles()!!.forEach { it.delete() }
        checks = 0
        rig.busy.onCheck = { if (++checks == 3) rig.songs.ids[song.path] = 99 }
        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertArrayEquals(changed, rig.bytes(song))
        assertFalse("open rw" in rig.events)
        assertTrue(backupKept(changed))
    }

    // 3. A backup goes back only over the same file

    @Test
    fun `a new MediaStore id for the same path is the same song - its backup goes back, now and at the next start`() {
        val song = rig.song("Paper Lantern", 41, fields)
        // Closing the rw descriptor made MediaStore drop row 41 and index the path again as 77. The song reads back as
        // written, so nothing is put back: the start-up recovery settles it under its new id.
        rig.songs.scanned = { rig.songs.ids[song.path] = 77; 77 }
        assertEquals(SongOutcome.RECOVERY_PENDING, write(song))
        val written = rig.bytes(song)
        assertEquals(1, rig.journal.pending().size)
        rig.restart()
        assertEquals(Recovered(settled = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(written, rig.bytes(song))

        // Killed mid-write; at the next start the path has a new id.
        rig.songs.scanned = { rig.songs.ids[it] }
        val second = rig.song("Tin Roof", 42, fields)
        val secondOriginal = rig.bytes(second)
        rig.songs.plans += WritePlan.KillAfter(3_000)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(second, edit)), EditKind.SONG, "Tin Roof") }
        } catch (_: SimulatedKill) {
        }
        rig.songs.ids[second.path] = 88
        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(secondOriginal, rig.bytes(second))
    }

    @Test
    fun `another file at the song's path is never written over - its intent is set aside`() {
        val song = rig.song("Paper Lantern", 41, fields)
        rig.songs.plans += WritePlan.KillAfter(3_000)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        } catch (_: SimulatedKill) {
        }
        // Neither the song before, nor as written, nor a write of one over the other cut short.
        val other = FakeTagFile.song(mapOf("TITLE" to listOf("Salt Clock")), audio = TagFixtures.mp3Audio(seed = 77))
        File(song.path).writeBytes(other)
        rig.restart()

        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(other, rig.bytes(song))
    }

    @Test
    fun `a torn song is one write's start over the other's rest, in either direction`() {
        val dir = folder.newFolder("torn")
        val a = File(dir, "a.mp3").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val b = File(dir, "b.mp3").apply { writeBytes(ByteArray(80) { (200 - it).toByte() }) }
        fun torn(bytes: ByteArray) = File(dir, "f.mp3").apply { writeBytes(bytes) }.let { tornFrom(it, a, b) }

        val aBytes = a.readBytes()
        val bBytes = b.readBytes()
        for (k in listOf(0, 1, 50, 79, 80, 99, 100)) {
            val cut = aBytes.copyOf(k) + if (k < bBytes.size) bBytes.copyOfRange(k, bBytes.size) else ByteArray(0)
            assertTrue("k=$k", torn(cut))
        }
        assertFalse(torn(aBytes.copyOf(50) + ByteArray(30)))
        assertFalse(torn(bBytes.copyOf(70)))
        assertFalse(torn(aBytes.copyOf(60) + bBytes.copyOfRange(60, 80) + byteArrayOf(1)))
    }

    @Test
    fun `the recovery never follows a link in place of the song or its backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.songs.plans += WritePlan.KillAfter(3_000)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        } catch (_: SimulatedKill) {
        }
        rig.restart()
        val torn = File(song.path).readBytes()
        // Another file, reached only through a link where the song was.
        val elsewhere = File(folder.root, "elsewhere.mp3").apply { writeBytes(torn) }
        File(song.path).delete()
        Files.createSymbolicLink(File(song.path).toPath(), elsewhere.toPath())

        assertEquals(Recovered(waiting = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(torn, elsewhere.readBytes())

        // The song is back: settled.
        File(song.path).delete()
        File(song.path).writeBytes(torn)
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    // 4. Durable folders

    @Test
    fun `a backup or an intent whose folder can't be fsynced is not durable, so the song is never opened`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        for (dir in listOf(rig.dirs.backups, rig.dirs.journal)) {
            rig.folders.failing += dir
            assertEquals("$dir", SongOutcome.COULD_NOT_CHANGE, write(song))
            rig.folders.failing.clear()
            unopened(song, before)
            assertEquals(0, rig.journal.pending().size)
        }
    }

    // 5. Undo that could not be saved

    @Test
    fun `when Undo can't be saved the song stays written, held with its backup, and the next start saves Undo`() {
        // The writer's folders are made, and fsynced, with a first song.
        assertEquals(SongOutcome.WRITTEN, write(rig.song("Tin Roof", 42, fields)))
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.folders.failing += rig.dirs.undo.parentFile

        assertEquals(SongOutcome.UNDO_NOT_SAVED, write(song))
        val written = rig.bytes(song)
        assertFalse(original.contentEquals(written))
        assertEquals(1, rig.journal.pending().size)
        // The first song's snapshot, and this song's backup.
        assertEquals(2, rig.dirs.backups.listFiles()!!.size)
        // 7. Pending: no other edit until it is settled.
        assertEquals(SongOutcome.RECOVERY_PENDING, write(song, TagEdit(fields = mapOf("ALBUM" to listOf("Harbour Glass")))))
        assertArrayEquals(written, rig.bytes(song))

        rig.folders.failing.clear()
        rig.restart()
        assertEquals(Recovered(settled = 1), runBlocking { rig.recovery().recover() })
        assertEquals(0, rig.journal.pending().size)
        val entry = rig.undo.memory().entries.single { it.songs.single().mediaStoreId == 41L }
        assertEquals(setOf(41L, 42L), rig.undo.memory().snapshots.map { it.mediaStoreId }.toSet())
        assertEquals(emptyList<String>(), rig.leftovers())

        val undone = runBlocking { rig.writer.undo(entry.id) } as BatchResult.Done
        assertEquals(SongOutcome.WRITTEN, undone.songs.single().outcome)
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a new process's backup never takes the name of a snapshot an earlier one kept`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        assertEquals(SongOutcome.WRITTEN, write(song))
        // The same clock reading, and the intent counter starts over.
        rig.restart()
        assertEquals(SongOutcome.WRITTEN, write(song, TagEdit(fields = mapOf("ALBUM" to listOf("Harbour Glass")))))

        val snapshot = rig.undo.memory().snapshots.single()
        assertArrayEquals(original, File(rig.dirs.backups, snapshot.file).readBytes())
    }

    @Test
    fun `a write whose Undo was not saved is committed - a song changed since is never put back, it is set aside`() {
        assertEquals(SongOutcome.WRITTEN, write(rig.song("Tin Roof", 42, fields)))
        val song = rig.song("Paper Lantern", 41, fields)
        rig.folders.failing += rig.dirs.undo.parentFile
        assertEquals(SongOutcome.UNDO_NOT_SAVED, write(song))
        assertTrue(rig.journal.pending().single().committed)
        // Another app changes the song before the next start.
        val changed = FakeTagFile.song(mapOf("TITLE" to listOf("Paper Lantern (live)")), audio = TagFixtures.mp3Audio(seed = 41))
        File(song.path).writeBytes(changed)

        rig.folders.failing.clear()
        rig.restart()
        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(changed, rig.bytes(song))
    }

    // 3. The last look, after the intent and right before the first byte

    @Test
    fun `a song that changes after the intent was written is not written, and its intent is set aside with its backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.songs.onOpen = { File(song.path).appendBytes(byteArrayOf(0)) }
        assertEquals(SongOutcome.SET_ASIDE, write(song))
        rig.songs.onOpen = {}
        assertArrayEquals(original + byteArrayOf(0), rig.bytes(song))
        assertEquals(0, rig.journal.pending().size)
        assertEquals(1, File(rig.dirs.journal, WriteJournal.SET_ASIDE).listFiles()!!.size)
        assertTrue(backupKept(original))
    }

    // 4. The writer's folders

    @Test
    fun `when the folders the writer makes can't be fsynced into the app's folder, no song is opened`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        rig.folders.failing += rig.dirs.undo.parentFile
        assertEquals(SongOutcome.COULD_NOT_CHANGE, write(song))
        unopened(song, before)
        rig.folders.failing.clear()
        assertEquals(SongOutcome.WRITTEN, write(song))
    }

    // 5. v2.4 frames v2.3 converts

    @Test
    fun `an ID3v2_4 tag with a date, original date, people or genre frame stays v2_4`() {
        for ((index, frame) in listOf("TDRC", "TDOR", "TIPL", "TMCL", "TCON").withIndex()) {
            val song = rig.song("Song $index", 60L + index, fields, version = 4, frames = listOf("TIT2", frame))
            assertEquals(frame, SongOutcome.WRITTEN, write(song))
            assertEquals(frame, Id3Version.V2_4, rig.tags.lastVersion)
        }
    }

    // 7. U+0000

    @Test
    fun `a value holding U+0000 is refused, not cut short`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        assertEquals(SongOutcome.COULD_NOT_CHANGE, write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper\u0000Lantern")))))
        unopened(song, before)
    }

    // Third review: the descriptor and the file it names

    @Test
    fun `a failed write is put back through its own descriptor`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.songs.plans += WritePlan.FailAfter(3_000)
        assertEquals(SongOutcome.LEFT_AS_IT_WAS, write(song))
        assertArrayEquals(original, rig.bytes(song))
        assertEquals(1, rig.events.count { it == "open rw" })
    }

    @Test
    fun `another file put at the song's path during its write is never written over - the write is set aside`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        val other = FakeTagFile.song(mapOf("TITLE" to listOf("Salt Clock")), audio = TagFixtures.mp3Audio(seed = 77))
        // The rescan fails; meanwhile another app replaced the file at the path (a new inode). The write read back
        // right, so it is left for the start-up recovery, which sets it aside.
        rig.songs.scanned = {
            // Moved away, not deleted, so the new file can't be given the same inode number.
            File(song.path).renameTo(File(folder.root, "moved.mp3"))
            File(song.path).writeBytes(other)
            null
        }

        assertEquals(SongOutcome.RECOVERY_PENDING, write(song))
        rig.songs.scanned = { rig.songs.ids[it] }
        rig.restart()
        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(other, rig.bytes(song))
        assertEquals(0, rig.journal.pending().size)
        assertEquals(1, File(rig.dirs.journal, WriteJournal.SET_ASIDE).listFiles()!!.size)
        assertTrue(backupKept(original))
    }

    @Test
    fun `a file holding exactly the torn song's bytes is the song, whatever its inode, and is put back`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.songs.plans += WritePlan.KillAfter(3_000)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        } catch (_: SimulatedKill) {
        }
        // The same torn bytes in another file (another inode) at the path: what it holds decides, not its inode.
        val torn = rig.bytes(song)
        File(song.path).renameTo(File(folder.root, "moved.mp3"))
        File(song.path).writeBytes(torn)
        rig.restart()

        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a song whose intent stays open is pinned before its lock is let go`() {
        val first = rig.song("Paper Lantern", 41, fields)
        val second = rig.song("Tin Roof", 42, fields)
        for (song in listOf(first, second)) {
            rig.songs.plans += WritePlan.KillAfter(3_000)
            try {
                runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
            } catch (_: SimulatedKill) {
            }
        }
        rig.restart()
        // The first song is in no MediaStore row for now: it waits. While the second is settled, the first is taken.
        rig.songs.ids.remove(first.path)
        var taken: Boolean? = null
        rig.songs.onMounted = { path -> if (path == second.path) taken = rig.locks.tryHold(first.path) }

        assertEquals(1, runBlocking { rig.recovery().recover() }.waiting)
        assertEquals(false, taken)
        assertTrue(rig.locks.pinned(first.path))
    }

    // Fourth review: what the held descriptor reads decides

    /** Kills a write of [song] in the middle, then starts a new "process". */
    private fun killedMidWrite(song: SongTarget) {
        rig.songs.plans += WritePlan.KillAfter(3_000)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        } catch (_: SimulatedKill) {
        }
        rig.restart()
    }

    @Test
    fun `a file made at the song's path in its place, even reusing its inode, is never written over by the recovery`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val songId = rig.songs.fileId(song.path)
        val foreign = FakeTagFile.song(mapOf("TITLE" to listOf("Salt Clock")), audio = TagFixtures.mp3Audio(seed = 77))
        // Right as the recovery opens it: the song deleted, a new file made at its path, given the song's inode.
        rig.songs.onOpen = {
            File(song.path).delete()
            File(song.path).writeBytes(foreign)
            rig.songs.fileIds[song.path] = songId
        }

        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(foreign, rig.bytes(song))
    }

    @Test
    fun `a torn song whose device number changed, as after a remount, is still put back`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        val now = rig.songs.fileId(song.path)
        rig.songs.fileIds[song.path] = now.copy(dev = now.dev + 1)

        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a write whose descriptor names other bytes than the backed-up song stops before the first byte`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        val foreign = FakeTagFile.song(mapOf("TITLE" to listOf("Salt Clock")), audio = TagFixtures.mp3Audio(seed = 77))
        val elsewhere = File(folder.root, "elsewhere.mp3").apply { writeBytes(foreign) }
        rig.songs.redirect[song.path] = elsewhere

        assertEquals(SongOutcome.COULD_NOT_CHANGE, write(song))
        assertArrayEquals(foreign, elsewhere.readBytes())
        assertArrayEquals(before, rig.bytes(song))
        assertEquals(0, rig.journal.pending().size)
    }

    @Test
    fun `an old journal without a file id still recovers, by content`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        val intent = rig.journal.pending().single()
        File(rig.dirs.journal, intent.id + ".json").writeText(kotlinx.serialization.json.Json.encodeToString(WriteIntent.serializer(), intent.copy(fileId = null)))

        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    // Fifth review: a read that fails decides nothing; a backup stays until the path still names the song held

    private val saltClock = FakeTagFile.song(mapOf("TITLE" to listOf("Salt Clock")), audio = TagFixtures.mp3Audio(seed = 77))

    /** Deletes [song]'s file and makes another at its path, as another app would; the old file lives on where held. */
    private fun replace(song: SongTarget) {
        File(song.path).delete()
        File(song.path).writeBytes(saltClock)
    }

    @Test
    fun `a read that fails on a finished song decides nothing, the song stays as written and its intent open`() {
        val song = rig.song("Paper Lantern", 41, fields)
        rig.songs.killOnScan = true
        try {
            write(song)
        } catch (_: SimulatedKill) {
        }
        rig.restart()
        val written = rig.bytes(song)
        rig.songs.failReads = 1

        assertEquals(Recovered(waiting = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(written, rig.bytes(song))
        assertEquals(1, rig.journal.pending().size)

        rig.restart()
        assertEquals(Recovered(settled = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(written, rig.bytes(song))
    }

    @Test
    fun `a read that fails while a torn song is compared decides nothing, the song waits for the next start`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        // Cut past the song's old end: its length is neither the old nor the new content's, so the comparison reads first.
        rig.songs.plans += WritePlan.KillAfter(original.size + 100L)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        } catch (_: SimulatedKill) {
        }
        rig.restart()
        val torn = rig.bytes(song)
        assertTrue(torn.size > original.size && torn.size < checkNotNull(rig.journal.pending().single().expected).length)
        rig.songs.failReads = 1

        assertEquals(Recovered(waiting = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(torn, rig.bytes(song))
        assertEquals(1, rig.journal.pending().size)

        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a song replaced after its torn bytes were read is not written, and its backup is kept`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        val torn = rig.bytes(song)
        val backup = File(rig.dirs.backups, rig.journal.pending().single().backup)
        // A second name for the torn song's file, to see what is written to it once another file is at its path.
        val held = File(folder.root, "held.mp3")
        Files.createLink(held.toPath(), File(song.path).toPath())
        rig.songs.onRead = {
            rig.songs.onRead = {}
            replace(song)
        }

        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(saltClock, rig.bytes(song))
        assertArrayEquals(torn, held.readBytes())
        assertArrayEquals(original, backup.readBytes())
    }

    @Test
    fun `a song replaced while its backup is written back keeps the backup and is not counted as put back`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        val backup = File(rig.dirs.backups, rig.journal.pending().single().backup)
        rig.songs.onWrite = {
            rig.songs.onWrite = {}
            replace(song)
        }

        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(saltClock, rig.bytes(song))
        assertArrayEquals(original, backup.readBytes())
    }

    @Test
    fun `a song replaced right after its write keeps that write's backup, and the write is set aside`() {
        val song = rig.song("Paper Lantern", 41, fields)
        assertEquals(SongOutcome.WRITTEN, write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern")))))
        val before = rig.bytes(song)
        // The first write's backup stays as the song's snapshot; the second write's is the one to keep.
        val snapshots = rig.dirs.backups.listFiles()!!.toSet()
        rig.songs.scanned = { path ->
            rig.songs.scanned = { rig.songs.ids[it] }
            replace(song)
            rig.songs.ids[path]
        }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertArrayEquals(saltClock, rig.bytes(song))
        val kept = rig.dirs.backups.listFiles()!!.toSet() - snapshots
        assertEquals(1, kept.size)
        assertArrayEquals(before, kept.single().readBytes())
    }

    @Test
    fun `a song replaced while a failed write's backup is written back keeps the backup, set aside`() {
        val song = rig.song("Paper Lantern", 41, fields)
        assertEquals(SongOutcome.WRITTEN, write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern")))))
        val before = rig.bytes(song)
        val snapshots = rig.dirs.backups.listFiles()!!.toSet()
        rig.songs.plans += WritePlan.FailAfter(3_000)
        // The second pass through the descriptor is the put-back: the song is replaced as it starts.
        var passes = 0
        rig.songs.onWrite = { if (++passes == 2) replace(song) }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertArrayEquals(saltClock, rig.bytes(song))
        val kept = rig.dirs.backups.listFiles()!!.toSet() - snapshots
        assertEquals(1, kept.size)
        assertArrayEquals(before, kept.single().readBytes())
    }

    // Sixth review: a backup goes only on a fresh proof that the song is at its path; an error is never a decision

    private val titleOnly = TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern")))

    /** Whether a backup holding [bytes] is still kept. */
    private fun backupKept(bytes: ByteArray): Boolean = rig.dirs.backups.listFiles().orEmpty().any { it.readBytes().contentEquals(bytes) }

    /**
     * A second write of [song] (the first made its snapshot, so this one's backup is not kept by Undo), killed in the
     * rescan after its last byte; then a new process. Its backup.
     */
    private fun secondWriteKilledOnScan(song: SongTarget): File {
        assertEquals(SongOutcome.WRITTEN, write(song, titleOnly))
        rig.songs.killOnScan = true
        try {
            write(song)
        } catch (_: SimulatedKill) {
        }
        rig.restart()
        return File(rig.dirs.backups, rig.journal.pending().single().backup)
    }

    @Test
    fun `a song replaced while its new content is made keeps its backup, whichever check then stops the write`() {
        for (unknown in listOf(true, false)) {
            val song = rig.song(if (unknown) "Paper Lantern" else "Tin Roof", if (unknown) 41 else 42, fields)
            val before = rig.bytes(song)
            val copy = rig.copy
            // While the private copy is made: the song replaced, and (once) the media sessions no longer listed.
            rig.copy = { from, to ->
                copy(from, to)
                if (to.parentFile == rig.dirs.work) {
                    replace(song)
                    rig.busy.unknown = unknown
                }
            }
            val outcome = write(song)
            rig.copy = copy
            rig.busy.unknown = false

            assertEquals("sessions unknown: $unknown", SongOutcome.SET_ASIDE, outcome)
            assertArrayEquals(saltClock, rig.bytes(song))
            assertTrue(backupKept(before))
        }
    }

    @Test
    fun `a song replaced while it is read before the first byte keeps its backup, and nothing is written`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        rig.songs.onRead = {
            rig.songs.onRead = {}
            replace(song)
        }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertArrayEquals(saltClock, rig.bytes(song))
        assertTrue(backupKept(before))
    }

    @Test
    fun `a song replaced while a put-back is rescanned keeps its backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        assertEquals(SongOutcome.WRITTEN, write(song, titleOnly))
        val before = rig.bytes(song)
        rig.songs.plans += WritePlan.FailAfter(3_000)
        rig.songs.scanned = { path ->
            rig.songs.scanned = { rig.songs.ids[it] }
            replace(song)
            rig.songs.ids[path]
        }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertArrayEquals(saltClock, rig.bytes(song))
        assertTrue(backupKept(before))
    }

    @Test
    fun `a song replaced while the recovery saves its History keeps its backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val backup = secondWriteKilledOnScan(song)
        val before = backup.readBytes()
        // The first fsync of the recovery is the one saving the History record.
        rig.folders.onSync = {
            rig.folders.onSync = {}
            replace(song)
        }

        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(saltClock, rig.bytes(song))
        assertArrayEquals(before, backup.readBytes())
    }

    @Test
    fun `a read that fails in the verify after a write leaves the song as written, for the recovery`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        val file = File(song.path)
        // Right before the write: the reads after it fail, through the descriptor and by the path.
        rig.songs.onWrite = {
            rig.songs.onWrite = {}
            rig.songs.failReads = 1
            file.setReadable(false)
        }
        val outcome = try {
            write(song)
        } finally {
            file.setReadable(true)
        }

        assertEquals(SongOutcome.RECOVERY_PENDING, outcome)
        assertFalse(before.contentEquals(rig.bytes(song)))
        assertEquals(listOf("Paper Lantern"), rig.fields(song)["TITLE"])
        rig.restart()
        assertEquals(Recovered(settled = 1), runBlocking { rig.recovery().recover() })
        assertEquals(listOf("Paper Lantern"), rig.fields(song)["TITLE"])
    }

    @Test
    fun `a backup that can't be read now decides nothing, the torn song waits and is then put back`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        val backup = File(rig.dirs.backups, rig.journal.pending().single().backup)
        backup.setReadable(false)
        val first = try {
            runBlocking { rig.recovery().recover() }
        } finally {
            backup.setReadable(true)
        }

        assertEquals(Recovered(waiting = 1), first)
        assertEquals(1, rig.journal.pending().size)
        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a song that can't be read at three starts in a row is set aside with its backup, and counted`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val torn = rig.bytes(song)
        val backup = File(rig.dirs.backups, rig.journal.pending().single().backup)
        rig.songs.failReads = Int.MAX_VALUE
        val starts = (1..3).map {
            rig.restart()
            runBlocking { rig.recovery().recover() }
        }
        rig.songs.failReads = 0

        assertEquals(listOf(Recovered(waiting = 1), Recovered(waiting = 1), Recovered(setAside = 1, unreadable = 1)), starts)
        assertArrayEquals(torn, rig.bytes(song))
        assertTrue(backup.isFile)
        assertEquals(0, rig.journal.pending().size)
        // What Covers & tags counts in its line.
        assertEquals(1, rig.journal.uncheckable())
    }

    @Test
    fun `a torn song that ends early while it is compared waits for the next start`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        // Cut past the song's old end: its length is neither the old nor the new content's, so the comparison reads first.
        rig.songs.plans += WritePlan.KillAfter(original.size + 100L)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        } catch (_: SimulatedKill) {
        }
        rig.restart()
        rig.songs.endReads = 1

        assertEquals(Recovered(waiting = 1), runBlocking { rig.recovery().recover() })
        assertEquals(1, rig.journal.pending().size)
        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a song replaced by a file on its recycled inode while the recovery holds it keeps its backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val backup = secondWriteKilledOnScan(song)
        val before = backup.readBytes()
        val songId = rig.songs.fileId(song.path)
        // The path's new file is given the song's inode number, as a file system that recycles it would.
        rig.songs.onRead = {
            rig.songs.onRead = {}
            replace(song)
            rig.songs.fileIds[song.path] = songId
        }

        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(saltClock, rig.bytes(song))
        assertArrayEquals(before, backup.readBytes())
    }

    @Test
    fun `a look at the path that fails while a torn song is put back decides nothing`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        // After the recovery's first read, the next lstat of the path fails.
        rig.songs.onRead = {
            rig.songs.onRead = {}
            rig.songs.failFileIds = 1
        }

        assertEquals(Recovered(waiting = 1), runBlocking { rig.recovery().recover() })
        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a song replaced while the backup's release reads it keeps its backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        assertEquals(SongOutcome.WRITTEN, write(song, titleOnly))
        val before = rig.bytes(song)
        // The release opens the song anew ("open r") and reads it: the song is replaced right after that read.
        rig.songs.onRead = {
            if (rig.events.last() == "open r") {
                rig.songs.onRead = {}
                replace(song)
            }
        }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertArrayEquals(saltClock, rig.bytes(song))
        assertTrue(backupKept(before))
    }

    @Test
    fun `an Undo list that can't be read keeps every backup, and nothing is pruned`() {
        // Unreadable (a read that fails), not Undo's list at all (a parse that fails), or holding a snapshot this version
        // can't read (the rest of the list read, its expired snapshot would be pruned).
        val breaks = listOf<Pair<String, (File) -> Unit>>(
            "read" to { it.setReadable(false) },
            "parse" to { it.writeText("{ not json") },
            "item" to { it.writeText(it.readText().replace("\"snapshots\":[", "\"snapshots\":[{\"file\":5},")) },
        )
        for ((how, breakIt) in breaks) {
            val rig = WriterRig(folder.newFolder(how))
            val song = rig.song("Paper Lantern", 41, fields)
            fun write(change: TagEdit) = (runBlocking { rig.writer.write(listOf(WriteJob(song, change)), EditKind.SONG, "Paper Lantern") } as BatchResult.Done).songs.single().outcome
            assertEquals(SongOutcome.WRITTEN, write(titleOnly))
            // A second write, killed after its last byte: its History record and its backup's release are left.
            rig.songs.killOnScan = true
            try {
                write(edit)
            } catch (_: SimulatedKill) {
            }
            val backups = rig.dirs.backups.listFiles()!!.associate { it.name to it.readBytes() }
            assertEquals(2, backups.size)
            breakIt(rig.dirs.undo)
            val stored = if (how == "read") null else rig.dirs.undo.readBytes()
            rig.restart()

            val recovered = try {
                runBlocking { rig.recovery().recover() }.also { rig.undo.prune(rig.now + 365L * 24 * 60 * 60 * 1000) }
            } finally {
                rig.dirs.undo.setReadable(true)
            }

            assertEquals(how, Recovered(waiting = 1), recovered)
            assertEquals(how, 1, rig.journal.pending().size)
            assertEquals(how, backups.keys, rig.dirs.backups.listFiles()!!.map { it.name }.toSet())
            backups.forEach { (name, bytes) -> assertArrayEquals(how, bytes, File(rig.dirs.backups, name).readBytes()) }
            stored?.let { assertArrayEquals(how, it, rig.dirs.undo.readBytes()) }
            if (how == "read") {
                // Readable again: the next start saves the record, and both edits are in History.
                rig.restart()
                assertEquals(how, Recovered(settled = 1), runBlocking { rig.recovery().recover() })
                assertEquals(how, 2, rig.undo.memory().entries.size)
                assertEquals(how, 1, rig.undo.memory().snapshots.size)
            }
        }
    }

    // Seventh review: an error stays an error down to the stat and the listing

    /** The open intent's file. */
    private fun intentFile(): File = rig.dirs.journal.listFiles()!!.single { it.isFile && it.name.endsWith(".json") }

    @Test
    fun `an intent that can't be read now is left in place and retried`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        val intent = intentFile()
        intent.setReadable(false)
        val recovered = try {
            runBlocking { rig.recovery().recover() }
        } finally {
            intent.setReadable(true)
        }

        assertEquals(Recovered(waiting = 1, paused = true), recovered)
        assertTrue(intent.isFile)
        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `an intent that can't be read at three starts in a row is set aside, and counted`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val intent = intentFile()
        intent.setReadable(false)
        val starts = (1..3).map {
            rig.restart()
            runBlocking { rig.recovery().recover() }
        }

        // While it waits, tag changes are paused: any song may be its song.
        assertEquals(listOf(Recovered(waiting = 1, paused = true), Recovered(waiting = 1, paused = true), Recovered(setAside = 1, unreadable = 1)), starts)
        assertEquals(1, rig.journal.uncheckable())
        assertEquals(0, rig.journal.pending().size)
    }

    @Test
    fun `the writer leaves a song alone while an open intent can't be read`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val torn = rig.bytes(song)
        val intent = intentFile()
        intent.setReadable(false)
        val outcome = try {
            write(song)
        } finally {
            intent.setReadable(true)
        }

        // Any song may be its song: tag changes are paused, and say so.
        assertEquals(SongOutcome.PAUSED, outcome)
        assertArrayEquals(torn, rig.bytes(song))
    }

    @Test
    fun `a journal that can't be listed settles nothing that start, and its private copies stay`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        val copies = rig.dirs.work.listFiles()!!.map { it.name }
        assertEquals(1, copies.size)
        rig.dirs.journal.setReadable(false)
        val recovered = try {
            runBlocking { rig.recovery().recover() }
        } finally {
            rig.dirs.journal.setReadable(true)
        }

        assertEquals(Recovered(paused = true), recovered)
        assertEquals(copies, rig.dirs.work.listFiles()!!.map { it.name })
        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a write whose History can't be saved at three starts is settled as written, Undo not saved, its backup kept`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val backup = secondWriteKilledOnScan(song)
        val written = rig.bytes(song)
        // Undo's file is in the app's folder: its fsync fails, so no History record is saved.
        rig.folders.failing += rig.dirs.root
        val starts = (1..3).map {
            rig.restart()
            runBlocking { rig.recovery().recover() }
        }
        rig.folders.failing.clear()

        assertEquals(listOf(Recovered(waiting = 1), Recovered(waiting = 1), Recovered(settled = 1, setAside = 1, undoNotSaved = 1)), starts)
        assertArrayEquals(written, rig.bytes(song))
        assertTrue(backup.isFile)
        assertEquals(0, rig.journal.uncheckable())
        assertEquals(0, rig.journal.pending().size)
    }

    @Test
    fun `a song that can't be read is set aside at the third start even when its count can't be rewritten`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        rig.songs.failReads = Int.MAX_VALUE
        repeat(2) {
            rig.restart()
            runBlocking { rig.recovery().recover() }
        }
        rig.folders.failing += rig.dirs.journal
        rig.restart()
        val third = runBlocking { rig.recovery().recover() }
        rig.folders.failing.clear()
        rig.songs.failReads = 0

        assertEquals(Recovered(setAside = 1, unreadable = 1), third)
        assertEquals(0, rig.journal.pending().size)
    }

    @Test
    fun `a MediaStore query that fails is counted, and its song set aside after three starts`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        rig.songs.failIdOf = true
        val starts = (1..3).map {
            rig.restart()
            runBlocking { rig.recovery().recover() }
        }
        rig.songs.failIdOf = false

        assertEquals(listOf(Recovered(waiting = 1), Recovered(waiting = 1), Recovered(setAside = 1, unreadable = 1)), starts)
    }

    @Test
    fun `a song written over in place while its backup's release reads it keeps its backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        assertEquals(SongOutcome.WRITTEN, write(song, titleOnly))
        val before = rig.bytes(song)
        // Right after the release's first read, another app writes into the same file: the same inode, other bytes.
        rig.songs.onRead = {
            if (rig.events.last() == "open r") {
                rig.songs.onRead = {}
                java.io.RandomAccessFile(song.path, "rw").use {
                    it.seek(20)
                    it.write(byteArrayOf(1, 2, 3))
                }
            }
        }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertTrue(backupKept(before))
    }

    @Test
    fun `a backup name already taken is never written over, and the write takes another`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val foreign = byteArrayOf(9, 9, 9)
        val taken = File(rig.dirs.backups, "1800000000000-41-1.mp3")
        // Right as the intent is recorded, a file appears under the name its backup was to have.
        rig.folders.onSync = { dir ->
            if (dir == rig.dirs.journal) {
                rig.folders.onSync = {}
                taken.parentFile.mkdirs()
                taken.writeBytes(foreign)
            }
        }

        assertEquals(SongOutcome.WRITTEN, write(song))
        assertArrayEquals(foreign, taken.readBytes())
    }

    @Test
    fun `an Undo whose song can't be hashed now writes nothing, never a field-level Undo`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val result = runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") } as BatchResult.Done
        val written = rig.bytes(song)
        val file = File(song.path)
        var checks = 0
        // The batch's own busy check comes first: from it until the song's own check, the song can't be read.
        rig.busy.onCheck = { file.setReadable(++checks != 1) }
        val undone = try {
            runBlocking { rig.writer.undo(checkNotNull(result.entryId)) } as BatchResult.Done
        } finally {
            file.setReadable(true)
            rig.busy.onCheck = {}
        }

        assertEquals(SongOutcome.COULD_NOT_CHANGE, undone.songs.single().outcome)
        assertArrayEquals(written, rig.bytes(song))
    }

    @Test
    fun `a debug build's checkpoint that can't tell throws for the tag engine, and refuses for the rest`() {
        val root = folder.newFolder("disk")
        val music = File(root, "storage/emulated/0/Music").apply { mkdirs() }
        File(music, "KJB test").mkdirs()
        val path = "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3"
        val writes = SharedWrites(testFolderOnly = true, root = root.toPath())
        assertTrue(writes.allowsOrThrow(SharedWrite.TAG_RESTORE, path))
        music.setExecutable(false)
        try {
            assertFalse(writes.allows(SharedWrite.TAG_RESTORE, path))
            assertThrows(java.io.IOException::class.java) { writes.allowsOrThrow(SharedWrite.TAG_RESTORE, path) }
        } finally {
            music.setExecutable(true)
        }
    }

    /**
     * Every line of the app's sources that deletes something named a backup (`.delete()`, `deleteIfExists`,
     * `deleteRecursively`, `Files.delete`) is in [Backups.releaseBackup], or is Undo's own expiry of a song's snapshot.
     * A line-by-line scan: a deletion of a backup must name it on its line.
     */
    @Test
    fun `a backup is deleted only through releaseBackup`() {
        val roots = listOf("src/main/java/app/lyrify", "src/debug/java/app/lyrify").map(::File)
        assertTrue("run from the app module: ${File("").absolutePath}", roots[0].isDirectory)
        val deletes = Regex("""\.delete\(\)|deleteIfExists\(|deleteRecursively\(|Files\.delete\(""")
        val found = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                val lines = file.readLines()
                val door = body(lines, "fun releaseBackup(")
                lines.withIndex().filter { (index, line) ->
                    val code = line.substringBefore("//").trim()
                    index !in door && !code.startsWith("*") && deletes.containsMatchIn(code) && code.contains("backup", ignoreCase = true)
                }.map { (_, line) -> "${file.relativeTo(root).invariantSeparatorsPath}: ${line.trim()}" }
            }.toList()
        }

        assertEquals(listOf("tags/UndoStore.kt: dropped.forEach { File(dirs.backups, it.file).delete() }"), found)
        assertTrue(roots.any { File(it, "tags/WriteJournal.kt").readText().contains("fun releaseBackup(") })
    }

    // Eighth review: bookkeeping that fails gives up safely, and never silently

    @Test
    fun `an intent that can't be read, whose count can't be read either, is set aside on that start`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val intent = intentFile()
        val count = File(rig.dirs.journal, intent.name.removeSuffix(".json") + ".unread").apply { writeText("1") }
        intent.setReadable(false)
        count.setReadable(false)

        assertEquals(Recovered(setAside = 1, unreadable = 1), runBlocking { rig.recovery().recover() })
        assertEquals(1, rig.journal.uncheckable())
    }

    @Test
    fun `an intent that can't be read, whose count can't be kept, is set aside on that start`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val intent = intentFile()
        // Where the count is written aside before it is moved into place, a folder: it can't be written.
        File(rig.dirs.journal, intent.name.removeSuffix(".json") + ".unread.tmp").mkdirs()
        intent.setReadable(false)

        assertEquals(Recovered(setAside = 1, unreadable = 1), runBlocking { rig.recovery().recover() })
    }

    @Test
    fun `a song that can't be read, whose count can't be rewritten, is set aside on that start`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        rig.songs.failReads = Int.MAX_VALUE
        rig.folders.failing += rig.dirs.journal
        val recovered = runBlocking { rig.recovery().recover() }
        rig.folders.failing.clear()
        rig.songs.failReads = 0

        assertEquals(Recovered(setAside = 1, unreadable = 1), recovered)
    }

    @Test
    fun `a write whose History can't be saved, nor its count rewritten, is settled on that start, Undo not saved`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val backup = secondWriteKilledOnScan(song)
        rig.folders.failing += rig.dirs.root
        rig.folders.failing += rig.dirs.journal
        val recovered = runBlocking { rig.recovery().recover() }
        rig.folders.failing.clear()

        assertEquals(Recovered(settled = 1, setAside = 1, undoNotSaved = 1), recovered)
        assertTrue(backup.isFile)
    }

    @Test
    fun `an intent that can't be set aside pauses tag changes, and says so`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        replace(song)
        // Where intents are set aside, a file: none can be moved there.
        File(rig.dirs.journal, WriteJournal.SET_ASIDE).writeText("in the way")
        val other = rig.song("Tin Roof", 42, fields)
        val before = rig.bytes(other)

        assertEquals(Recovered(waiting = 1, paused = true), runBlocking { rig.recovery().recover() })
        assertEquals(1, rig.journal.pending().size)
        assertEquals(SongOutcome.PAUSED, write(other))
        assertArrayEquals(before, rig.bytes(other))
        assertArrayEquals(saltClock, rig.bytes(song))
    }

    @Test
    fun `a journal entry that can't be looked at goes through the count, and pauses tag changes meanwhile`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        killedMidWrite(song)
        // The folder can be listed, but nothing in it looked at.
        rig.dirs.journal.setExecutable(false)
        val recovered = try {
            runBlocking { rig.recovery().recover() }
        } finally {
            rig.dirs.journal.setExecutable(true)
        }

        assertEquals(Recovered(waiting = 1, paused = true), recovered)
        rig.restart()
        assertEquals(Recovered(settled = 1, restored = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(original, rig.bytes(song))
    }

    @Test
    fun `a start that reads the song starts the count of starts that could not again`() {
        val song = rig.song("Paper Lantern", 41, fields)
        secondWriteKilledOnScan(song)
        fun start(): Recovered {
            rig.restart()
            return runBlocking { rig.recovery().recover() }
        }
        rig.songs.failReads = Int.MAX_VALUE
        repeat(2) { start() }
        // Read as written; only its History can't be saved.
        rig.songs.failReads = 0
        rig.folders.failing += rig.dirs.root
        assertEquals(Recovered(waiting = 1), start())
        rig.folders.failing.clear()
        rig.songs.failReads = Int.MAX_VALUE

        assertEquals(Recovered(waiting = 1), start())
        rig.songs.failReads = 0
    }

    @Test
    fun `the count beside an intent goes once the intent reads, so only starts in a row count`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val intent = intentFile()
        fun start(unreadable: Boolean): Recovered {
            intent.setReadable(!unreadable)
            rig.restart()
            return runBlocking { rig.recovery().recover() }.also { intent.setReadable(true) }
        }
        start(unreadable = true)
        // Read, but its volume is not there: waits, and the count of starts it could not be read goes.
        rig.songs.mounted = false
        start(unreadable = false)
        rig.songs.mounted = true
        start(unreadable = true)

        assertEquals(Recovered(waiting = 1, paused = true), start(unreadable = true))
        assertEquals(1, rig.journal.pending().size)
    }

    @Test
    fun `a close that fails after the backup went is not a failed read, and the next start closes it`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val backup = secondWriteKilledOnScan(song)
        val id = intentFile().name.removeSuffix(".json")
        // A folder where the count beside the intent would be: closing the intent can't delete it.
        val inTheWay = File(rig.dirs.journal, "$id.unread").apply { mkdirs() }
        File(inTheWay, "x").writeText("x")

        assertEquals(Recovered(waiting = 1), runBlocking { rig.recovery().recover() })
        assertFalse(backup.exists())
        assertEquals(0, rig.journal.pending().single().readFailures)
        inTheWay.deleteRecursively()
        rig.restart()
        assertEquals(Recovered(settled = 1), runBlocking { rig.recovery().recover() })
        assertEquals(0, rig.journal.pending().size)
    }

    @Test
    fun `a torn song whose backup is gone is never written back, and is set aside`() {
        val song = rig.song("Paper Lantern", 41, fields)
        killedMidWrite(song)
        val torn = rig.bytes(song)
        File(rig.dirs.backups, rig.journal.pending().single().backup).delete()

        assertEquals(Recovered(setAside = 1), runBlocking { rig.recovery().recover() })
        assertArrayEquals(torn, rig.bytes(song))
        assertTrue(rig.logs.any { "nothing to restore from" in it })
    }

    @Test
    fun `an Undo whose History can't be read changes nothing, and says it couldn't`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val result = runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") } as BatchResult.Done
        val written = rig.bytes(song)
        rig.dirs.undo.setReadable(false)
        rig.restart()
        val undone = try {
            runBlocking { rig.writer.undo(checkNotNull(result.entryId)) }
        } finally {
            rig.dirs.undo.setReadable(true)
        }

        assertEquals(BatchResult.HistoryUnreadable, undone)
        assertArrayEquals(written, rig.bytes(song))
    }

    // The phone: a replacement made through MediaStore that the file system does not show yet

    @Test
    fun `a song MediaStore replaced while its backup's release reads it keeps its backup, though the path still shows the old file`() {
        val song = rig.song("Paper Lantern", 41, fields)
        assertEquals(SongOutcome.WRITTEN, write(song, titleOnly))
        val before = rig.bytes(song)
        // Right after the release's first read, another app deletes the song and makes a new one under its name through
        // MediaStore: a new row at the path. This process's view of the path is not refreshed yet (as on the phone), so the
        // path and the descriptor still show the old file.
        rig.songs.onRead = {
            if (rig.events.last() == "open r") {
                rig.songs.onRead = {}
                rig.songs.ids[song.path] = 77
            }
        }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertTrue(backupKept(before))
    }

    /**
     * The tag engine's files and the shared-storage checkpoint use only calls that throw when they can't tell: no
     * `File.length()`, `exists()`, `isFile`, `isDirectory` or `listFiles()` (each answers 0, false or null on an error),
     * and no catch of an IOException, ErrnoException, SecurityException or FileNotFoundException that answers null,
     * false, 0 or an empty stand-in. A line marked `// safe:` says why it is one, and is allowed.
     */
    @Test
    fun `the tag engine never turns an error into an answer`() {
        val files = listOf("SafeWriter", "WriteJournal", "UndoStore", "PayloadHash", "TagFile", "BusyFiles", "TagsGraph")
            .map { File("src/main/java/app/lyrify/tags/$it.kt") } +
            File("src/main/java/app/lyrify/data/SharedWrites.kt") + File("src/debug/java/app/lyrify/tags/TagKillService.kt")
        assertTrue("run from the app module: ${File("").absolutePath}", files.all { it.isFile })
        val calls = Regex("""\.length\(\)|\.exists\(\)|\.isFile\b|\.isDirectory\b|\.listFiles\(""")
        val caught = Regex("""catch \(\w+: (IOException|ErrnoException|SecurityException|FileNotFoundException)\)""")
        val answer = Regex("""(return\s+)?(null|false|0L?|emptyList\(\)|emptySet\(\)|emptyMap\(\)|\w+Memory\(\))(\.also\b.*)?""")
        val found = files.flatMap { file ->
            val lines = file.readLines()
            lines.withIndex().flatMap { (index, line) ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || "// safe:" in line) return@flatMap emptyList<String>()
                val hits = mutableListOf<String>()
                if (calls.containsMatchIn(code)) hits += "${file.name}:${index + 1}: $code"
                if (caught.containsMatchIn(code)) {
                    // The catch block's lines, on its own line or up to its closing brace.
                    val inline = code.substringAfter("{", "").substringBefore("}").trim()
                    val block = listOf(inline) + lines.drop(index + 1).takeWhile { !it.trim().startsWith("}") }
                    block.filter { "// safe:" !in it }.map { it.substringBefore("//").trim() }.filter(answer::matches)
                        .forEach { hits += "${file.name}:${index + 1}: $code $it" }
                }
                hits
            }
        }

        assertEquals(emptyList<String>(), found)
    }

    /** The line numbers of the body of the function [signature] opens in [lines] (none when it isn't there). */
    private fun body(lines: List<String>, signature: String): IntRange {
        val start = lines.indexOfFirst { signature in it }
        if (start < 0) return IntRange.EMPTY
        var depth = 0
        var opened = false
        for (index in start until lines.size) {
            for (char in lines[index]) {
                if (char == '{') {
                    depth++
                    opened = true
                } else if (char == '}') {
                    depth--
                }
            }
            if (opened && depth == 0) return start..index
        }
        return start until lines.size
    }

    // 7. Pending songs

    @Test
    fun `a song whose write still waits for the recovery is not edited again, and never snapshotted torn`() {
        val song = rig.song("Paper Lantern", 41, fields)
        rig.songs.plans += WritePlan.FailAfter(3_000)
        rig.songs.plans += WritePlan.FailAfter(0)
        assertEquals(SongOutcome.RECOVERY_PENDING, write(song))
        val torn = rig.bytes(song)
        val backups = rig.dirs.backups.listFiles()!!.map { it.name }

        assertEquals(SongOutcome.RECOVERY_PENDING, write(song, TagEdit(fields = mapOf("ALBUM" to listOf("Harbour Glass")))))
        assertArrayEquals(torn, rig.bytes(song))
        assertEquals(backups, rig.dirs.backups.listFiles()!!.map { it.name })
        assertEquals(emptyList<Snapshot>(), rig.undo.memory().snapshots)
    }

    // 6. The start-up gate

    @Test
    fun `the songs of unfinished writes are held from process start until the recovery settles them`() = runTest {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        rig.songs.plans += WritePlan.KillAfter(3_000)
        try {
            runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
        } catch (_: SimulatedKill) {
        }
        // A new process: its locks are empty until the start-up holds the journaled songs, before anything else runs.
        rig.locks = PathLocks()
        rig.restart()
        val recovery = rig.recovery()
        val held = recovery.holdPending()
        assertEquals(listOf(song.path), held.map { it.path })
        assertTrue(song.path in rig.locks)

        // The lyrics pipeline waits for the song meanwhile.
        val client = FakeLrclib()
        val pipeline = LyricsPipeline(client = client, ledger = MemoryLedger(), clock = { 1_800_000_000_000L }, writes = SharedWrites.ANYWHERE, locks = rig.locks)
        val processing = async { pipeline.process(LocalTrack(song.path, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000), force = true) }
        runCurrent()
        assertFalse(processing.isCompleted)

        assertNotNull(recovery.recover(held))
        assertArrayEquals(original, rig.bytes(song))
        assertFalse(song.path in rig.locks)
        processing.await()
        assertTrue(client.requests > 0)
    }
}
