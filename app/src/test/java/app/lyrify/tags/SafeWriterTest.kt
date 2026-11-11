package app.lyrify.tags

import app.lyrify.data.SharedWrites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The tag writer, one song at a time and crash-safe: busy songs are never written, the original is opened only after a
 * durable backup, a journal intent and a verified private copy, it is written in place ("rw"), verified and rescanned,
 * and every failure after the write began puts the backup back byte for byte. Made-up names only.
 */
class SafeWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val rig by lazy { WriterRig(folder.root) }

    private val fields = mapOf(
        "TITLE" to listOf("paper lantern"),
        "ARTIST" to listOf("Kit Calloway"),
        "LYRICS" to listOf("la la la"),
        "REPLAYGAIN_TRACK_GAIN" to listOf("-6.20 dB"),
        "COMMENT" to listOf("made up"),
    )
    private val back = TagPicture(byteArrayOf(1, 2, 3), "", "Back Cover", "image/jpeg")
    private val cover = TagPicture(ByteArray(300) { 7 }, "", FRONT_COVER, "image/jpeg")
    private val edit = TagEdit(fields = mapOf("TITLE" to listOf("Paper Lantern"), "ALBUM" to listOf("Harbour Glass")), cover = CoverEdit.Replace(cover))

    private fun write(vararg jobs: WriteJob): BatchResult = runBlocking { rig.writer.write(jobs.toList(), EditKind.COVERS_TAGS, "Harbour Glass") }

    private fun outcomes(result: BatchResult): List<SongOutcome> = (result as BatchResult.Done).songs.map { it.outcome }

    @Test
    fun `a write changes only the fields asked for, keeps the audio and the MediaStore id, and records the edit`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(back))
        val payload = PayloadHash.of(File(song.path).toPath())

        val result = write(WriteJob(song, edit)) as BatchResult.Done

        assertEquals(listOf(SongOutcome.WRITTEN), result.songs.map { it.outcome })
        assertEquals(fields + mapOf("TITLE" to listOf("Paper Lantern"), "ALBUM" to listOf("Harbour Glass")), rig.fields(song))
        assertEquals(listOf(cover, back), FakeTagFile().read(File(song.path))!!.pictures)
        assertEquals(payload, PayloadHash.of(File(song.path).toPath()))
        assertEquals(3, rig.bytes(song)[3].toInt())
        assertEquals(1, rig.events.count { it == "scan" })

        val entry = rig.undo.memory().entries.single()
        assertEquals(result.entryId, entry.id)
        assertEquals(EditKind.COVERS_TAGS, entry.kind)
        assertEquals("Harbour Glass", entry.label)
        val songEdit = entry.songs.single()
        assertEquals(41L, songEdit.mediaStoreId)
        assertEquals(setOf("TITLE", "ALBUM"), songEdit.fields.map { it.key }.toSet())
        assertTrue(songEdit.coverChanged)
        assertNull(songEdit.coverBefore)
        // The backup is kept for Undo, as the song's first snapshot; nothing else is left behind.
        assertEquals(41L, rig.undo.memory().snapshots.single().mediaStoreId)
        assertEquals(emptyList<String>(), rig.leftovers())
        // TagLib never saw the user's file.
        assertTrue(rig.tags.given.isNotEmpty())
        assertTrue(rig.tags.given.all { it.canonicalPath.startsWith(rig.dirs.work.canonicalPath) })
    }

    @Test
    fun `the steps come in order - busy check, check, backup, private copy, write, verify, rescan, clean up`() {
        val song = rig.song("Paper Lantern", 41, fields)
        var journalAtOpen = -1
        rig.songs.onOpen = { journalAtOpen = rig.dirs.journal.listFiles().orEmpty().count { it.isFile } }

        write(WriteJob(song, edit))

        val events = rig.events.toList()
        fun at(event: String) = events.indexOf(event).also { assertTrue("$event in $events", it >= 0) }
        // The rescan runs with the descriptor still open: a put-back would go through it.
        val order = listOf("busy check", "mounted", "same file", "copy backup", "copy work", "tags read", "tags write", "open rw", "scan", "closed")
        assertEquals(order, order.sortedBy(::at))
        // A second busy check right before the write, after the private copy was verified.
        assertTrue(events.lastIndexOf("busy check") in at("tags write")..at("open rw"))
        assertEquals("one intent, written before the original was opened", 1, journalAtOpen)
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    @Test
    fun `a song any media session plays or holds, the lyrics pipeline or the AI holds, is never written`() {
        val playing = rig.song("Paper Lantern", 41, fields)
        val locked = rig.song("Tin Roof", 42, fields)
        val free = rig.song("Salt Clock", 43, fields)
        val before = listOf(playing, locked).map(rig::bytes)
        rig.busy.inUse += playing.path
        rig.locks.tryHold(locked.path)

        val result = write(WriteJob(playing, edit), WriteJob(locked, edit), WriteJob(free, edit))

        assertEquals(listOf(SongOutcome.BUSY, SongOutcome.BUSY, SongOutcome.WRITTEN), outcomes(result))
        assertArrayEquals(before[0], rig.bytes(playing))
        assertArrayEquals(before[1], rig.bytes(locked))
        assertTrue(locked.path in rig.locks)
        assertEquals(listOf(free.path), rig.undo.memory().entries.single().songs.map { it.path })
    }

    @Test
    fun `a busy song is tried once more at the end, and written if it is free by then`() {
        val first = rig.song("Paper Lantern", 41, fields)
        val second = rig.song("Tin Roof", 42, fields)
        rig.busy.inUse += first.path
        // The session moves on while the second song is written.
        rig.busy.onCheck = { path -> if (path == second.path) rig.busy.inUse -= first.path }

        val result = write(WriteJob(first, edit), WriteJob(second, edit)) as BatchResult.Done

        assertEquals(listOf(SongOutcome.WRITTEN, SongOutcome.WRITTEN), result.songs.map { it.outcome })
        assertEquals(listOf(first, second), result.songs.map { it.song })
    }

    @Test
    fun `when the media sessions can't be listed, nothing is written`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        rig.busy.unknown = true

        assertEquals(BatchResult.SessionsUnknown, write(WriteJob(song, edit)))
        assertArrayEquals(before, rig.bytes(song))
        assertFalse("open rw" in rig.events)
        assertEquals(emptyList<HistoryEntry>(), rig.undo.memory().entries)
    }

    @Test
    fun `when the sessions stop being listable mid-album, the rest is not tried`() {
        val first = rig.song("Paper Lantern", 41, fields)
        val second = rig.song("Tin Roof", 42, fields)
        val before = rig.bytes(second)
        rig.busy.onCheck = { path -> if (path == second.path) rig.busy.unknown = true }

        assertEquals(listOf(SongOutcome.WRITTEN, SongOutcome.NOT_TRIED), outcomes(write(WriteJob(first, edit), WriteJob(second, edit))))
        assertArrayEquals(before, rig.bytes(second))
    }

    @Test
    fun `without space for the backups, a second copy and a margin, the album is skipped`() {
        val songs = listOf(rig.song("Paper Lantern", 41, fields), rig.song("Tin Roof", 42, fields))
        val sizes = songs.map { File(it.path).length() }
        rig.free = sizes.sum() + sizes.max() + SafeWriter.SPACE_MARGIN_BYTES - 1
        val before = songs.map(rig::bytes)

        assertEquals(BatchResult.NotEnoughSpace, write(*songs.map { WriteJob(it, edit) }.toTypedArray()))
        songs.forEachIndexed { i, song -> assertArrayEquals(before[i], rig.bytes(song)) }

        rig.free += 1
        assertEquals(listOf(SongOutcome.WRITTEN, SongOutcome.WRITTEN), outcomes(write(*songs.map { WriteJob(it, edit) }.toTypedArray())))
    }

    @Test
    fun `an unmounted volume, a moved id, or a file of another format is not changed`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)

        rig.songs.mounted = false
        assertEquals(listOf(SongOutcome.COULD_NOT_CHANGE), outcomes(write(WriteJob(song, edit))))
        rig.songs.mounted = true

        assertEquals(listOf(SongOutcome.COULD_NOT_CHANGE), outcomes(write(WriteJob(song.copy(mediaStoreId = 99), edit))))
        assertArrayEquals(before, rig.bytes(song))

        val wav = File(rig.music, "Tin Roof.wav").apply { writeBytes(before) }
        rig.songs.ids[wav.path] = 42
        val garbage = File(rig.music, "Salt Clock.mp3").apply { writeBytes(FakeTagFile.encode(fields) + ByteArray(5_000) { 1 }) }
        rig.songs.ids[garbage.path] = 43
        assertEquals(
            listOf(SongOutcome.COULD_NOT_CHANGE, SongOutcome.COULD_NOT_CHANGE),
            outcomes(write(WriteJob(SongTarget(wav.path, 42), edit), WriteJob(SongTarget(garbage.path, 43), edit))),
        )
        assertFalse("open rw" in rig.events)
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    @Test
    fun `a debug build changes no song outside its test folder`() {
        rig.writes = SharedWrites(testFolderOnly = true)
        rig.restart()
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)

        assertEquals(listOf(SongOutcome.NOT_ALLOWED), outcomes(write(WriteJob(song, edit))))
        assertArrayEquals(before, rig.bytes(song))
        assertFalse("copy backup" in rig.events)
    }

    @Test
    fun `a failure before the write leaves the original unopened - TagLib failing, a damaged or wrong private copy, a short backup`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        fun check(setUp: () -> Unit, undo: () -> Unit) {
            setUp()
            assertEquals(listOf(SongOutcome.COULD_NOT_CHANGE), outcomes(write(WriteJob(song, edit))))
            undo()
            assertArrayEquals(before, rig.bytes(song))
            assertFalse("open rw" in rig.events)
            assertEquals(emptyList<String>(), rig.leftovers())
        }

        check({ rig.tags.failWrite = true }, { rig.tags.failWrite = false })
        check({ rig.tags.damageAudio = true }, { rig.tags.damageAudio = false })
        check({ rig.tags.dropField = "LYRICS" }, { rig.tags.dropField = null })
        val copy = rig.copy
        check({ rig.copy = { from, to -> copy(from, to); if (to.parentFile == rig.dirs.backups) to.writeBytes(to.readBytes().copyOf(to.length().toInt() - 1)) } }, { rig.copy = copy })
        assertEquals(emptyList<HistoryEntry>(), rig.undo.memory().entries)
    }

    @Test
    fun `every failure after the write began puts the backup back, byte for byte`() {
        val failures = listOf<Pair<String, () -> Unit>>(
            "write fails" to { rig.songs.plans += WritePlan.FailAfter(700) },
            "write comes out wrong" to { rig.songs.plans += WritePlan.Damage },
            "MediaStore refuses" to { rig.songs.plans += WritePlan.Refuse },
        )
        failures.forEachIndexed { index, (name, setUp) ->
            val song = rig.song("Song $index", 50L + index, fields)
            val before = rig.bytes(song)
            setUp()

            assertEquals(name, listOf(if (name == "MediaStore refuses") SongOutcome.COULD_NOT_CHANGE else SongOutcome.LEFT_AS_IT_WAS), outcomes(write(WriteJob(song, edit))))
            assertArrayEquals(name, before, rig.bytes(song))
            assertEquals(name, emptyList<String>(), rig.leftovers())
            rig.songs.scanned = { rig.songs.ids[it] }
        }
        assertEquals(emptyList<HistoryEntry>(), rig.undo.memory().entries)
        assertEquals(emptyList<Snapshot>(), rig.undo.memory().snapshots)
    }

    @Test
    fun `a rescan that does not name the song leaves it as written, for the start-up recovery`() {
        listOf<Long?>(null, 77).forEachIndexed { index, answer ->
            val song = rig.song("Song $index", 60L + index, fields)
            val before = rig.bytes(song)
            rig.songs.scanned = { answer }

            assertEquals("$answer", listOf(SongOutcome.RECOVERY_PENDING), outcomes(write(WriteJob(song, edit))))
            rig.songs.scanned = { rig.songs.ids[it] }
            assertFalse("$answer", before.contentEquals(rig.bytes(song)))
            assertTrue("$answer", rig.journal.pending().single { it.path == song.path }.committed)
        }
    }

    @Test
    fun `a backup that can't be written back now stays for the start-up recovery`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)
        rig.songs.plans += WritePlan.FailAfter(700)
        // The put-back, through the same descriptor, fails too.
        rig.songs.plans += WritePlan.FailAfter(0)

        assertEquals(listOf(SongOutcome.RECOVERY_PENDING), outcomes(write(WriteJob(song, edit))))
        assertEquals(1, rig.journal.pending().size)

        val recovered = runBlocking { rig.recovery().recover() }
        assertEquals(1, recovered.restored)
        assertArrayEquals(before, rig.bytes(song))
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    @Test
    fun `an edit that changes nothing writes nothing`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val before = rig.bytes(song)

        assertEquals(listOf(SongOutcome.UNCHANGED), outcomes(write(WriteJob(song, TagEdit(fields = mapOf("TITLE" to listOf("paper lantern")))))))
        assertArrayEquals(before, rig.bytes(song))
        assertFalse("open rw" in rig.events)
        assertEquals(emptyList<String>(), rig.leftovers())
        assertEquals(null, (write() as BatchResult.Done).entryId)
    }

    @Test
    fun `one file at a time, even when two albums are applied at once`() {
        val albums = (0 until 2).map { album -> (0 until 3).map { rig.song("Song $album$it", 100L + album * 10 + it, fields) } }

        val results = runBlocking(Dispatchers.Default) {
            albums.map { album -> async { rig.writer.write(album.map { WriteJob(it, edit) }, EditKind.ALBUM, "Harbour Glass") } }.awaitAll()
        }

        results.forEach { assertEquals(List(3) { SongOutcome.WRITTEN }, outcomes(it)) }
        assertEquals(1, rig.tags.mostAtOnce)
        assertEquals(1, rig.songs.mostAtOnce)
        assertEquals(2, rig.undo.memory().entries.size)
    }

    @Test
    fun `applying an album again never replaces its first snapshot`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val original = rig.bytes(song)
        write(WriteJob(song, edit))
        rig.now += 1_000
        write(WriteJob(song, TagEdit(fields = mapOf("ALBUM" to listOf("Glass Harbour")))))

        val snapshot = rig.undo.memory().snapshots.single()
        assertArrayEquals(original, File(rig.dirs.backups, snapshot.file).readBytes())
        assertEquals(2, rig.undo.memory().entries.size)
        assertEquals(emptyList<String>(), rig.leftovers())
    }

    @Test
    fun `the writer waits for the start-up recovery before its first write`() {
        val song = rig.song("Paper Lantern", 41, fields)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writer = SafeWriter(
            rig.dirs, rig.tags, rig.songs, rig.busy, rig.locks, SharedWrites.ANYWHERE, rig.journal, rig.undo,
            freeBytes = { rig.free }, clock = { rig.now }, ready = { gate.await() },
        )
        runBlocking {
            val writing = async(Dispatchers.Default) { writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Paper Lantern") }
            Thread.sleep(100)
            assertFalse(writing.isCompleted)
            assertFalse("busy check" in rig.events)
            gate.complete(Unit)
            assertNotNull(writing.await())
        }
    }

    // Nothing is ever taken away (user feedback 2026-11-11)

    @Test
    fun `the writer refuses an empty front cover, and a Covers & tags edit that would take a cover or a field away`() {
        val song = rig.song("Paper Lantern", 41, fields, listOf(cover, back))
        val before = rig.bytes(song)
        fun one(edit: TagEdit, kind: EditKind) = runBlocking { rig.writer.write(listOf(WriteJob(song, edit)), kind, "Harbour Glass") }

        val empty = TagEdit(cover = CoverEdit.Replace(TagPicture(ByteArray(0), "", FRONT_COVER, "image/jpeg")))
        for (kind in listOf(EditKind.COVERS_TAGS, EditKind.SONG)) assertEquals(listOf(SongOutcome.COULD_NOT_CHANGE), outcomes(one(empty, kind)))
        assertEquals(listOf(SongOutcome.COULD_NOT_CHANGE), outcomes(one(TagEdit(cover = CoverEdit.Remove), EditKind.COVERS_TAGS)))
        assertEquals(listOf(SongOutcome.COULD_NOT_CHANGE), outcomes(one(TagEdit(fields = mapOf("ARTIST" to listOf(" "))), EditKind.COVERS_TAGS)))
        assertEquals(listOf(SongOutcome.COULD_NOT_CHANGE), outcomes(one(TagEdit(fields = mapOf("COMMENT" to emptyList())), EditKind.COVERS_TAGS)))
        assertArrayEquals(before, rig.bytes(song))
        assertEquals(emptyList<Any>(), rig.undo.memory().entries)

        // Edit tags' Remove is the user's own choice: the front cover goes, the back cover stays.
        assertEquals(listOf(SongOutcome.WRITTEN), outcomes(one(TagEdit(cover = CoverEdit.Remove), EditKind.SONG)))
        assertEquals(listOf(back), FakeTagFile().read(File(song.path))!!.pictures)
    }

    @Test
    fun `a batch says each song as it is tried, and asked to stop leaves the rest untried and unchanged`() {
        val songs = (1..4).map { rig.song("Kelp Bell $it", 40L + it, fields) }
        val before = songs.map { rig.bytes(it) }
        val seen = mutableListOf<Int>()

        val result = runBlocking {
            rig.writer.write(songs.map { WriteJob(it, edit) }, EditKind.COVERS_TAGS, "Harbour Glass", Progress(song = { seen += it }, stop = { seen.size >= 2 }))
        }

        assertEquals(listOf(1, 2), seen)
        assertEquals(listOf(SongOutcome.WRITTEN, SongOutcome.WRITTEN, SongOutcome.NOT_TRIED, SongOutcome.NOT_TRIED), outcomes(result))
        assertArrayEquals(before[2], rig.bytes(songs[2]))
        assertArrayEquals(before[3], rig.bytes(songs[3]))
    }
}
