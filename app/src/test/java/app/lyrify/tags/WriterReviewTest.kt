package app.lyrify.tags

import app.lyrify.data.PathLocks
import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import app.lyrify.data.SharedWrites
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        var checks = 0
        // The second busy check comes right before the intent: the song changes in between.
        rig.busy.onCheck = { if (++checks == 3) File(song.path).appendBytes(byteArrayOf(0)) }
        assertEquals(SongOutcome.COULD_NOT_CHANGE, write(song))
        val changed = rig.bytes(song)
        unopened(song, changed)
        assertEquals(0, rig.journal.pending().size)

        checks = 0
        rig.busy.onCheck = { if (++checks == 3) rig.songs.ids[song.path] = 99 }
        assertEquals(SongOutcome.COULD_NOT_CHANGE, write(song))
        unopened(song, changed)
    }

    // 3. A backup goes back only over the same file

    @Test
    fun `a new MediaStore id for the same path is the same song - its backup goes back, now and at the next start`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        // Closing the rw descriptor made MediaStore drop row 41 and index the path again as 77: a failed write.
        rig.songs.scanned = { rig.songs.ids[song.path] = 77; 77 }
        assertEquals(SongOutcome.LEFT_AS_IT_WAS, write(song))
        assertArrayEquals(original, rig.bytes(song))
        assertEquals(0, rig.journal.pending().size)

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
    fun `a song that changes after the intent was written is not written, and the intent is closed`() {
        val song = rig.song("Paper Lantern", 41, fields)
        rig.songs.onOpen = { File(song.path).appendBytes(byteArrayOf(0)) }
        assertEquals(SongOutcome.COULD_NOT_CHANGE, write(song))
        rig.songs.onOpen = {}
        val changed = rig.bytes(song)
        assertEquals(0, rig.journal.pending().size)
        assertEquals(emptyList<String>(), rig.leftovers())
        assertTrue(changed.last() == 0.toByte())
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
        rig.songs.scanned = { null }
        assertEquals(SongOutcome.LEFT_AS_IT_WAS, write(song))
        assertArrayEquals(original, rig.bytes(song))
        assertEquals(1, rig.events.count { it == "open rw" })
    }

    @Test
    fun `another file put at the song's path during its write is never written over - the write is set aside`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val other = FakeTagFile.song(mapOf("TITLE" to listOf("Salt Clock")), audio = TagFixtures.mp3Audio(seed = 77))
        // The rescan fails; meanwhile another app replaced the file at the path (a new inode).
        rig.songs.scanned = {
            // Moved away, not deleted, so the new file can't be given the same inode number.
            File(song.path).renameTo(File(folder.root, "moved.mp3"))
            File(song.path).writeBytes(other)
            null
        }

        assertEquals(SongOutcome.SET_ASIDE, write(song))
        assertArrayEquals(other, rig.bytes(song))
        assertEquals(0, rig.journal.pending().size)
        assertEquals(1, File(rig.dirs.journal, WriteJournal.SET_ASIDE).listFiles()!!.size)
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
        val songId = checkNotNull(rig.songs.fileId(song.path))
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
        val now = checkNotNull(rig.songs.fileId(song.path))
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
