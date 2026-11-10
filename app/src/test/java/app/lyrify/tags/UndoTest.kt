package app.lyrify.tags

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Undo: the full copy writes the original bytes back while nothing changed since; otherwise each field goes back only
 * where it still holds the edit's value, so a later edit is never wiped; a song whose audio changed is skipped. The
 * store keeps full copies 30 days within 2 GB, one generation, and reads old data leniently. Made-up names only.
 */
class UndoTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val rig by lazy { WriterRig(folder.root) }

    private val fields = mapOf("TITLE" to listOf("paper lantern"), "ARTIST" to listOf("Kit Calloway"), "LYRICS" to listOf("la la la"))
    private val back = TagPicture(byteArrayOf(1, 2, 3), "", "Back Cover", "image/jpeg")
    private val oldFront = TagPicture(ByteArray(200) { 4 }, "", FRONT_COVER, "image/png")
    private val newFront = TagPicture(ByteArray(300) { 7 }, "", FRONT_COVER, "image/jpeg")

    private fun write(song: SongTarget, edit: TagEdit): Long =
        checkNotNull((runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") } as BatchResult.Done).entryId)

    private fun undo(entryId: Long): BatchResult.Done = runBlocking { rig.writer.undo(entryId) } as BatchResult.Done

    @Test
    fun `undo writes the original bytes back when nothing changed since`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(oldFront, back))
        val original = rig.bytes(song)
        val entry = write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern"), "ALBUM" to listOf("Harbour Glass")), cover = CoverEdit.Replace(newFront)))

        val result = undo(entry)

        assertEquals(listOf(SongOutcome.WRITTEN), result.songs.map { it.outcome })
        assertEquals(0, result.skippedFields)
        assertArrayEquals(original, rig.bytes(song))
        assertTrue(rig.undo.memory().entries.single().songs.single().undone)
        assertEquals(emptyList<String>(), rig.leftovers())
        // Undone already: nothing more to do.
        assertEquals(listOf<SongResult>(), undo(entry).songs)
    }

    @Test
    fun `undoing an older edit reverts a field only where it still holds that edit's value`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val first = write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern"), "ALBUM" to listOf("Harbour Glass"))))
        rig.now += 1_000
        write(song, TagEdit(fields = mapOf("ALBUM" to listOf("Glass Harbour"), "DATE" to listOf("2009"))))

        val result = undo(first)

        assertEquals(listOf(SongOutcome.WRITTEN), result.songs.map { it.outcome })
        assertEquals(1, result.skippedFields)
        // The title goes back; the album the later edit changed again stays, as does its year.
        assertEquals(fields + mapOf("ALBUM" to listOf("Glass Harbour"), "DATE" to listOf("2009")), rig.fields(song))
    }

    @Test
    fun `an older edit whose fields all changed since has nothing left to undo`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val first = write(song, TagEdit(fields = mapOf("ALBUM" to listOf("Harbour Glass"))))
        rig.now += 1_000
        write(song, TagEdit(fields = mapOf("ALBUM" to listOf("Glass Harbour"))))
        val before = rig.bytes(song)

        val result = undo(first)

        assertEquals(listOf(SongOutcome.CHANGED_SINCE), result.songs.map { it.outcome })
        assertEquals(1, result.skippedFields)
        assertArrayEquals(before, rig.bytes(song))
    }

    @Test
    fun `the front cover goes back field by field when the full copy can't be used, and the back cover stays`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(oldFront, back))
        val first = write(song, TagEdit(cover = CoverEdit.Replace(newFront)))
        rig.now += 1_000
        write(song, TagEdit(fields = mapOf("COMMENT" to listOf("made up"))))

        undo(first)

        assertEquals(listOf(oldFront, back), FakeTagFile().read(File(song.path))!!.pictures)
        assertEquals(listOf("made up"), rig.fields(song)["COMMENT"])
    }

    @Test
    fun `a cover the edit added is removed by undo`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(back))
        val first = write(song, TagEdit(cover = CoverEdit.Replace(newFront)))
        rig.now += 1_000
        write(song, TagEdit(fields = mapOf("COMMENT" to listOf("made up"))))

        undo(first)

        assertEquals(listOf(back), FakeTagFile().read(File(song.path))!!.pictures)
    }

    @Test
    fun `a song whose audio changed since is skipped, and says so`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val entry = write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern"))))
        // Another file under the same name now.
        File(song.path).writeBytes(FakeTagFile.song(mapOf("TITLE" to listOf("Paper Lantern")), audio = TagFixtures.mp3Audio(seed = 99)))
        val before = rig.bytes(song)

        assertEquals(listOf(SongOutcome.DIFFERENT_FILE), undo(entry).songs.map { it.outcome })
        assertArrayEquals(before, rig.bytes(song))
        assertFalse(rig.undo.memory().entries.single().songs.single().undone)
    }

    @Test
    fun `when the full copy has expired, undo goes field by field`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val entry = write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern"))))
        rig.undo.prune(rig.now + UndoStore.KEEP_MS + 1)
        assertNull(rig.undo.snapshot(41))
        assertFalse(rig.undo.hasFullCopy(entry))

        assertEquals(listOf(SongOutcome.WRITTEN), undo(entry).songs.map { it.outcome })
        assertEquals(fields, rig.fields(song))
    }

    // The store

    @Test
    fun `history and snapshots survive a restart`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(oldFront))
        write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern")), cover = CoverEdit.Replace(newFront)))
        val memory = rig.undo.memory()

        val reloaded = UndoStore(rig.dirs).memory()

        assertEquals(memory, reloaded)
        val cover = reloaded.entries.single().songs.single().coverBefore!!
        assertEquals(oldFront, UndoStore(rig.dirs).picture(cover))
    }

    @Test
    fun `old data is read leniently - unknown keys are ignored and an unreadable entry is left out, not the rest`() {
        val song = rig.song("Paper Lantern", 41, fields)
        write(song, TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern"))))
        val text = rig.dirs.undo.readText()
        val broken = text.replace("\"entries\":[", "\"entries\":[{\"id\":\"not a number\"},").replace("\"version\":1", "\"version\":1,\"future\":true")
        rig.dirs.undo.writeText(broken)

        val memory = UndoStore(rig.dirs).memory()
        assertEquals(1, memory.entries.size)
        assertEquals(1, memory.snapshots.size)
    }

    @Test
    fun `a store that can't be read at all is left as it is, never saved over, and lets no backup go`() {
        rig.dirs.undo.parentFile.mkdirs()
        rig.dirs.undo.writeText("{ not json")
        val store = UndoStore(rig.dirs)

        // History shows nothing for now; the snapshots are not taken for none.
        assertEquals(UndoMemory(), store.memory())
        assertThrows(IOException::class.java) { store.keptBackups() }
        assertFalse(store.markUndone(1, 41))
        assertEquals("{ not json", rig.dirs.undo.readText())
        assertFalse(File(rig.dirs.undo.path + UndoStore.ASIDE).exists())
    }

    @Test
    fun `full copies are kept 30 days within 2 GB, the oldest albums dropped first`() {
        val store = UndoStore(rig.dirs, budgetBytes = 2_500)
        val day = 24 * 60 * 60 * 1000L
        fun snapshotOf(entryId: Long, id: Long, bytes: Int): Snapshot {
            rig.dirs.backups.mkdirs()
            val file = File(rig.dirs.backups, "$entryId-$id.mp3").apply { writeBytes(ByteArray(bytes)) }
            return Snapshot(id, "/storage/emulated/0/Music/KJB test/$id.mp3", file.name, bytes.toLong(), "s$id", Payload(AudioFormat.MP3, "p$id"), entryId, entryId)
        }
        fun record(entryId: Long, id: Long, bytes: Int) {
            val snapshot = snapshotOf(entryId, id, bytes)
            store.record(EntryInfo(entryId, EditKind.ALBUM, "Album $entryId", entryId), SongEdit(id, snapshot.path, snapshot.payload, "s$id", "a$id", emptyList()), snapshot)
        }
        record(1 * day, 1, 1_000)
        record(1 * day, 2, 500)
        record(2 * day, 3, 1_000)
        record(3 * day, 4, 1_000)

        // Over budget: the oldest album goes, both its songs.
        store.prune(now = 3 * day, keep = 3 * day)
        assertEquals(listOf(3L, 4L), store.memory().snapshots.map { it.mediaStoreId })
        assertFalse(File(rig.dirs.backups, "${1 * day}-1.mp3").exists())
        assertTrue(store.hasFullCopy(3 * day))
        assertFalse(store.hasFullCopy(1 * day))
        // The entries stay, for the field-level undo.
        assertEquals(3, store.memory().entries.size)

        // After 30 days a copy goes whatever the budget.
        store.prune(now = 2 * day + UndoStore.KEEP_MS + 1)
        assertEquals(listOf(4L), store.memory().snapshots.map { it.mediaStoreId })
    }

    @Test
    fun `one generation - a second snapshot of the same song is not taken`() {
        val store = UndoStore(rig.dirs)
        val payload = Payload(AudioFormat.MP3, "p")
        val first = Snapshot(41, "/storage/emulated/0/Music/KJB test/a.mp3", "1-41.mp3", 10, "s1", payload, 1, 1)
        store.record(EntryInfo(1, EditKind.SONG, "a", 1), SongEdit(41, first.path, payload, "s1", "s2", emptyList()), first)
        store.record(EntryInfo(2, EditKind.SONG, "a", 2), SongEdit(41, first.path, payload, "s2", "s3", emptyList()), first.copy(file = "2-41.mp3", sha256 = "s2", entryId = 2))

        assertEquals(listOf(first), store.memory().snapshots)
        assertEquals(2, store.memory().entries.size)
    }

    @Test
    fun `entry ids grow even when the clock does not`() {
        val store = UndoStore(rig.dirs)
        val payload = Payload(AudioFormat.MP3, "p")
        store.record(EntryInfo(store.newEntryId(5), EditKind.SONG, "a", 5), SongEdit(41, "/a.mp3", payload, "s1", "s2", emptyList()), null)
        assertEquals(6L, store.newEntryId(5))
        assertEquals(9L, store.newEntryId(9))
    }

    // The field-level revert, pure

    @Test
    fun `the revert takes back only fields still holding the edit's new value`() {
        val edit = SongEdit(
            41, "/a.mp3", Payload(AudioFormat.MP3, "p"), "s1", "s2",
            listOf(
                FieldChange("TITLE", listOf("paper lantern"), listOf("Paper Lantern")),
                FieldChange("ALBUM", emptyList(), listOf("Harbour Glass")),
                FieldChange("COMMENT", listOf("made up"), emptyList()),
            ),
        )
        val current = SongTags(mapOf("TITLE" to listOf("Paper Lantern"), "ALBUM" to listOf("Glass Harbour")), emptyList(), 1_000)

        val revert = edit.revert(current) { null }

        assertEquals(mapOf("TITLE" to listOf("paper lantern"), "COMMENT" to listOf("made up")), revert.edit?.fields)
        assertEquals(2, revert.reverted)
        assertEquals(1, revert.skipped)
        assertNull(edit.revert(current.copy(fields = mapOf("ALBUM" to listOf("Glass Harbour"), "COMMENT" to listOf("other")))) { null }.edit)
    }
}
