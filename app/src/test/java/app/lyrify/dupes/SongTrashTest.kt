package app.lyrify.dupes

import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrites
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrclibRecord
import app.lyrify.lyrics.LrclibResult
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "Move to trash" from the song sheet, through the trash flow Duplicates shares ([Duplicates.trash]): one library song,
 * no duplicate group. The real lyrics pipeline and ledger; the phone faked (its trash renames the file as MediaProvider
 * does, and its dialog's answer is what the test says). Made-up names and words only.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler, advanceUntilIdle
class SongTrashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()
    private val phoneTrash = mutableMapOf<Long, Long?>()

    /** The paths the playlists and Favourites were told to forget. */
    private val forgotten = mutableListOf<String>()

    /** Placeholder words, never real lyrics. */
    private val synced = "[00:01.00]w0001\n[00:02.00]w0002\n[00:03.00]w0003\n[00:04.00]w0004\n[00:05.00]w0005\n[00:06.00]w0006"

    private fun TestScope.pipeline() =
        LyricsPipeline(client = client, ledger = ledger, clock = { BASE + testScheduler.currentTime }, writes = SharedWrites.ANYWHERE)

    /** A library song on disk, as LibraryReader lists it. */
    private fun song(id: Long?, name: String): Song {
        val file = File(folder.root, "Music/Harbour Glass/$name.mp3")
        file.parentFile?.mkdirs()
        file.writeText("audio bytes of $name")
        return Song(
            ref = SongRef(file.path, id), title = name, artist = "Kit Calloway", album = "Harbour Glass", albumArtist = null,
            durationMs = 200_000, track = 1, disc = null, year = 2021, genre = null, folder = "Music/Harbour Glass/",
            addedAt = BASE, modifiedAt = BASE, sizeBytes = file.length(), mimeType = "audio/mpeg",
        )
    }

    private fun lrc(path: String) = File(LrcFormat.sidecarPath(path) ?: error("no sidecar"))

    /** The engine the app has: MediaStore's rows are the files on disk; trashing cleans up lyrics and playlists. */
    private fun TestScope.engine(files: List<AudioFile>, pipeline: LyricsPipeline, queues: suspend (Set<String>) -> Unit = {}) = Duplicates(
        library = object : AudioLibrary {
            override fun read(): LibraryRead = LibraryRead.Files(files)

            override fun rows(ids: Collection<Long>): Map<Long, AudioFile> = files.filter { it.id in ids && File(it.path).isFile }.associateBy { it.id }
        },
        store = DuplicatesStore(File(folder.root, "app/duplicates.json")),
        cleanup = { path ->
            pipeline.trashed(path)
            forgotten += path
        },
        phoneTrash = { ids -> phoneTrash.filterKeys { it in ids } },
        scope = this,
        io = StandardTestDispatcher(testScheduler),
        clock = { BASE + testScheduler.currentTime },
        leaveQueues = queues,
    )

    private fun trashOnDisk(path: String, id: Long) {
        val audio = File(path)
        assertTrue(audio.renameTo(File(audio.parentFile, ".trashed-1765000000-${audio.name}")))
        phoneTrash[id] = BASE + 30 * DAY
    }

    private suspend fun TestScope.lyricsFor(song: Song, pipeline: LyricsPipeline) {
        client.onGet = { LrclibResult.Found(listOf(LrclibRecord(1, song.title, "Kit Calloway", null, 200.0, plainLyrics = "w0001", syncedLyrics = synced))) }
        assertEquals(LyricsStatus.SYNCED, pipeline.process(LocalTrack(song.path, song.title, song.artist, song.album, song.durationMs))?.status)
        advanceUntilIdle()
    }

    @Test
    fun `a library song is named by its MediaStore id, path, file name and size`() {
        val lantern = song(11, "Paper Lantern")
        val file = trashFile(lantern)
        assertEquals(11L, file?.id)
        assertEquals(lantern.path, file?.path)
        assertEquals("Paper Lantern.mp3", file?.displayName)
        assertEquals(lantern.sizeBytes, file?.size)
        assertEquals("Music/Harbour Glass/", file?.relativePath)
        assertEquals("Paper Lantern", file?.title)
        assertNull(trashFile(song(null, "Tide Bell")))
    }

    @Test
    fun `KJB's dialog, then the phone's OK, and the song goes, with the lyrics KJB wrote, its ledger entry and its playlists`() = runTest {
        val lantern = song(11, "Paper Lantern")
        val file = trashFile(lantern) ?: error("no file")
        val pipeline = pipeline()
        lyricsFor(lantern, pipeline)
        val trash = engine(listOf(file), pipeline).trash
        advanceUntilIdle()

        assertTrue(trash.ask(listOf(file)))
        assertEquals(TrashStep.Confirm(listOf(file)), trash.step)
        assertEquals(Confirmation.Ask(listOf(11L)), trash.confirm(listOf(file)))
        assertEquals(TrashStep.System(listOf(file)), trash.step)
        trashOnDisk(lantern.path, 11)
        // Nothing is cleaned up before the phone's answer.
        assertTrue(lrc(lantern.path).isFile)

        assertEquals(Trashed.Moved(1), trash.answered(approved = true))
        assertFalse(lrc(lantern.path).exists())
        assertNull(ledger[lantern.path])
        assertEquals(listOf(lantern.path), forgotten)
        assertEquals(listOf(11L), trash.trashed.map { it.file.id })
        assertEquals(TrashStep.None, trash.step)
    }

    @Test
    fun `the songs the phone moved leave the queues together, before their lyrics and playlists, and only after its OK`() = runTest {
        val lantern = song(11, "Paper Lantern")
        val bell = song(12, "Tide Bell")
        val files = listOfNotNull(trashFile(lantern), trashFile(bell))
        // Each time the queues heard, with what the playlists had been told by then.
        val left = mutableListOf<Pair<Set<String>, List<String>>>()
        val trash = engine(files, pipeline(), queues = { left += it to forgotten.toList() }).trash
        advanceUntilIdle()

        assertTrue(trash.ask(files))
        assertEquals(Confirmation.Ask(listOf(11L, 12L)), trash.confirm(files))
        assertEquals(Trashed.Unchanged, trash.answered(approved = false))
        assertEquals(emptyList<Pair<Set<String>, List<String>>>(), left)

        assertTrue(trash.ask(files))
        assertEquals(Confirmation.Ask(listOf(11L, 12L)), trash.confirm(files))
        trashOnDisk(lantern.path, 11)
        trashOnDisk(bell.path, 12)
        assertEquals(Trashed.Moved(2), trash.answered(approved = true))
        assertEquals(listOf(setOf(lantern.path, bell.path) to emptyList<String>()), left)
        assertEquals(listOf(lantern.path, bell.path), forgotten)
    }

    @Test
    fun `cancel in KJB's dialog, or the phone's, changes nothing`() = runTest {
        val lantern = song(11, "Paper Lantern")
        val file = trashFile(lantern) ?: error("no file")
        val pipeline = pipeline()
        lyricsFor(lantern, pipeline)
        val trash = engine(listOf(file), pipeline).trash
        advanceUntilIdle()

        trash.ask(listOf(file))
        trash.cancel()
        assertEquals(Confirmation.NotAsked, trash.confirm(listOf(file)))

        trash.ask(listOf(file))
        trash.confirm(listOf(file))
        assertEquals(Trashed.Unchanged, trash.answered(approved = false))
        assertTrue(lrc(lantern.path).isFile)
        assertEquals(LyricsStatus.SYNCED, ledger[lantern.path]?.status)
        assertEquals(emptyList<String>(), forgotten)
        assertEquals(emptyList<TrashedSong>(), trash.trashed)
    }

    @Test
    fun `a song changed since the library listed it is not asked about`() = runTest {
        val lantern = song(11, "Paper Lantern")
        val listed = trashFile(lantern)?.copy(size = 1) ?: error("no file")
        val trash = engine(listOf(trashFile(lantern) ?: error("no file")), pipeline()).trash
        advanceUntilIdle()

        trash.ask(listOf(listed))
        assertEquals(Confirmation.Changed, trash.confirm(listOf(listed)))
        assertEquals(TrashStep.None, trash.step)
    }

    @Test
    fun `a Move while the phone's answer is still awaited means it is not coming, and the songs it left are settled first`() = runTest {
        val lantern = song(11, "Paper Lantern")
        val bell = song(12, "Tide Bell")
        val files = listOfNotNull(trashFile(lantern), trashFile(bell))
        val pipeline = pipeline()
        lyricsFor(lantern, pipeline)
        val trash = engine(files, pipeline).trash
        advanceUntilIdle()

        trash.ask(files.take(1))
        trash.confirm(files.take(1))
        // The phone moved the song, but its answer never reached KJB (the screen went with the dialog open).
        trashOnDisk(lantern.path, 11)
        assertFalse(trash.ask(emptyList()))
        assertTrue(trash.ask(files.drop(1)))
        assertEquals(Confirmation.Ask(listOf(12L)), trash.confirm(files.drop(1)))
        assertFalse(lrc(lantern.path).exists())
        assertEquals(listOf(lantern.path), forgotten)
        assertEquals(listOf(11L), trash.trashed.map { it.file.id })
        assertEquals(TrashStep.System(files.drop(1)), trash.step)
    }

    @Test
    fun `songs an answer KJB never heard left waiting never block the next Move`() = runTest {
        val files = listOfNotNull(trashFile(song(11, "Paper Lantern")), trashFile(song(12, "Tide Bell")))
        // The app died with the phone's dialog open, and the user cancelled it: the song is not in the phone's trash.
        val host = FakeTrashHost().apply { memory = TrashMemory(pending = files.take(1)) }
        val trash = trash(host, files)

        assertTrue(trash.ask(files.drop(1)))
        assertEquals(Confirmation.Ask(listOf(12L)), trash.confirm(files.drop(1)))
        assertEquals(TrashMemory(pending = files.drop(1)), host.memory)
        assertEquals(TrashStep.System(files.drop(1)), trash.step)
    }

    @Test
    fun `songs left waiting that MediaStore still can't settle are kept, and the Move asks nothing`() = runTest {
        val files = listOfNotNull(trashFile(song(11, "Paper Lantern")), trashFile(song(12, "Tide Bell")))
        val host = FakeTrashHost().apply { memory = TrashMemory(pending = files.take(1)) }
        val trash = trash(host, files, inTrash = { null })

        assertTrue(trash.ask(files.drop(1)))
        assertEquals(Confirmation.Unavailable, trash.confirm(files.drop(1)))
        assertEquals(TrashStep.None, trash.step)
        assertEquals(TrashMemory(pending = files.take(1)), host.memory)
        assertFalse(trash.settleLeftover())
    }

    @Test
    fun `a late answer of the phone never closes KJB's dialog open for a newer Move`() = runTest {
        val files = listOfNotNull(trashFile(song(11, "Paper Lantern")))
        val host = FakeTrashHost()
        val trash = trash(host, files)
        assertTrue(trash.ask(files))
        assertEquals(Trashed.Unchanged, trash.answered(approved = true))
        assertEquals(TrashStep.Confirm(files), trash.step)
    }

    @Test
    fun `a song whose size only KJB's own tag writes changed is still asked about, any other change is not`() = runTest {
        val lantern = trashFile(song(11, "Paper Lantern")) ?: error("no file")
        val bell = trashFile(song(12, "Tide Bell")) ?: error("no file")
        // Both are bigger now; only the first by KJB's own writes.
        val rows = listOf(lantern.copy(size = lantern.size + 10), bell.copy(size = bell.size + 10))
        val ownWrites = mutableListOf<Pair<AudioFile, AudioFile>>()
        val trash = trash(FakeTrashHost(), rows, ownWrite = { listed, now -> ownWrites += listed to now; listed.id == 11L })

        assertTrue(trash.ask(listOf(lantern)))
        assertEquals(Confirmation.Ask(listOf(11L)), trash.confirm(listOf(lantern)))
        assertEquals(listOf(lantern to rows[0]), ownWrites)
        trash.answered(approved = false)
        assertTrue(trash.ask(listOf(bell)))
        assertEquals(Confirmation.Changed, trash.confirm(listOf(bell)))
        // KJB's own writes never excuse another place or name.
        val moved = trash(FakeTrashHost(), listOf(lantern.copy(size = lantern.size + 10, displayName = "other.mp3")), ownWrite = { _, _ -> true })
        assertTrue(moved.ask(listOf(lantern)))
        assertEquals(Confirmation.Changed, moved.confirm(listOf(lantern)))
    }

    @Test
    fun `a song trashed from the song sheet comes back through the phone's restore`() = runTest {
        val lantern = song(11, "Paper Lantern")
        val file = trashFile(lantern) ?: error("no file")
        val pipeline = pipeline()
        lyricsFor(lantern, pipeline)
        val trash = engine(listOf(file), pipeline).trash
        advanceUntilIdle()
        trash.ask(listOf(file))
        trash.confirm(listOf(file))
        trashOnDisk(lantern.path, 11)
        trash.answered(approved = true)

        val listed = trash.trashed.single()
        assertEquals(listOf(11L), trash.restore(listed))
        val audio = File(lantern.path)
        assertTrue(File(audio.parentFile, ".trashed-1765000000-${audio.name}").renameTo(audio))
        phoneTrash.remove(11L)
        assertEquals(Restored.Back, trash.restoreAnswered(approved = true))
        assertEquals(emptyList<TrashedSong>(), trash.trashed)
        // KJB sees it as a new song: its lyrics come back with the next lookup.
        assertEquals(LyricsStatus.SYNCED, pipeline.process(LocalTrack(lantern.path, lantern.title, lantern.artist, lantern.album, lantern.durationMs))?.status)
    }

    @Test
    fun `a new Move takes the place of a dialog left open, and a dialog confirms only its own files`() = runTest {
        val files = listOfNotNull(trashFile(song(11, "Paper Lantern")), trashFile(song(12, "Tide Bell")))
        val host = FakeTrashHost()
        val trash = trash(host, files)

        assertTrue(trash.ask(files.take(1)))
        assertTrue(trash.ask(files.drop(1)))
        assertEquals(TrashStep.Confirm(files.drop(1)), trash.step)
        assertEquals(Confirmation.NotAsked, trash.confirm(files.take(1)))
        assertEquals(TrashStep.Confirm(files.drop(1)), trash.step)
        assertEquals(TrashMemory(), host.memory)

        // A restore whose answer never came.
        host.step = TrashStep.Restoring(TrashedSong(files[1], BASE, BASE + DAY))
        assertTrue(trash.ask(files.take(1)))
        assertEquals(TrashStep.Confirm(files.take(1)), trash.step)
    }

    @Test
    fun `a confirm cancelled before the phone's dialog opens leaves nothing waiting, so a later trash can ask`() = runTest {
        val files = listOfNotNull(trashFile(song(11, "Paper Lantern")))
        for (cancelIn in listOf("rows", "commit")) {
            val host = FakeTrashHost()
            lateinit var confirming: Job
            val trash = trash(host, files, onRows = { if (cancelIn == "rows") confirming.cancel() })
            if (cancelIn == "commit") host.onCommit = { confirming.cancel() }
            assertTrue(trash.ask(files))
            confirming = launch { trash.confirm(files) }
            advanceUntilIdle()

            assertTrue(cancelIn, confirming.isCancelled)
            assertEquals(cancelIn, TrashMemory(), host.memory)
            assertEquals(cancelIn, TrashStep.None, trash.step)
            assertTrue(cancelIn, trash.ask(files))
        }
    }

    @Test
    fun `a confirm cancelled before it remembered anything never drops what an earlier request left waiting`() = runTest {
        val files = listOfNotNull(trashFile(song(11, "Paper Lantern")))
        lateinit var confirming: Job
        // The same song, left by a request whose answer never came; the retry's confirm goes before it settles anything.
        val host = FakeTrashHost().apply {
            memory = TrashMemory(pending = files)
            onLoaded = { confirming.cancel() }
        }
        val trash = trash(host, files)
        assertTrue(trash.ask(files))
        confirming = launch { trash.confirm(files) }
        advanceUntilIdle()

        assertTrue(confirming.isCancelled)
        assertEquals(TrashMemory(pending = files), host.memory)
        assertEquals(TrashStep.None, trash.step)
    }

    // Delete forever and the phone's whole trash (Tools > Trash)

    /** Three songs in the phone's trash, KJB's own; [inTrash] and [live] say what MediaStore has of them now. */
    private inner class Bin {
        val files = listOfNotNull(trashFile(song(11, "Paper Lantern")), trashFile(song(12, "Tide Bell")), trashFile(song(13, "Salt Road")))
        val songs = files.map { TrashedSong(it, BASE, BASE + DAY) }
        val inTrash = files.associateTo(mutableMapOf<Long, Long?>()) { it.id to BASE + DAY }

        /** Songs MediaStore lists out of its trash: restored ones. */
        val live = mutableListOf<AudioFile>()
        val host = FakeTrashHost().apply { memory = TrashMemory(trashed = songs) }
    }

    @Test
    fun `Delete forever asks KJB's dialog, then the phone's, and its OK says how many went for good and the room they freed`() = runTest {
        val bin = Bin()
        val trash = trash(bin.host, bin.live, PhoneTrash { ids -> bin.inTrash.filterKeys { it in ids } })
        val two = bin.songs.take(2)

        assertFalse(trash.askDelete(emptyList()))
        assertTrue(trash.askDelete(two))
        assertEquals(TrashStep.ConfirmDelete(two), trash.step)
        trash.cancel()
        assertEquals(TrashStep.None, trash.step)
        // KJB's dialog is not open: nothing is asked.
        assertEquals(Confirmation.NotAsked, trash.confirmDelete(two))

        assertTrue(trash.askDelete(two))
        assertEquals(Confirmation.NotAsked, trash.confirmDelete(bin.songs))
        assertEquals(Confirmation.Ask(listOf(11L, 12L)), trash.confirmDelete(two))
        assertEquals(TrashStep.Deleting(two), trash.step)
        // The phone deleted the first; the second was restored meanwhile, so it is not counted, though it leaves the list.
        bin.inTrash.remove(11L)
        bin.inTrash.remove(12L)
        bin.live += bin.files[1]
        assertEquals(Deleted.Gone(1, of = 2, bytes = bin.files[0].size), trash.deleteAnswered(approved = true))
        assertEquals(TrashStep.None, trash.step)
        assertEquals(listOf(13L), bin.host.memory.trashed.map { it.file.id })
        // No dialog waits for an answer now.
        assertEquals(Deleted.Unchanged, trash.deleteAnswered(approved = true))
    }

    @Test
    fun `cancel in the phone's delete dialog, or an OK MediaStore can't check, changes nothing`() = runTest {
        val bin = Bin()
        var readable = true
        val trash = trash(bin.host, bin.live, PhoneTrash { ids -> bin.inTrash.filterKeys { it in ids }.takeIf { readable } })

        assertTrue(trash.askDelete(bin.songs))
        assertEquals(Confirmation.Ask(listOf(11L, 12L, 13L)), trash.confirmDelete(bin.songs))
        assertEquals(Deleted.Unchanged, trash.deleteAnswered(approved = false))
        assertEquals(TrashStep.None, trash.step)
        assertEquals(bin.songs, bin.host.memory.trashed)

        assertTrue(trash.askDelete(bin.songs))
        assertEquals(Confirmation.Ask(listOf(11L, 12L, 13L)), trash.confirmDelete(bin.songs))
        readable = false
        assertEquals(Deleted.NotConfirmed, trash.deleteAnswered(approved = true))
        assertEquals(TrashStep.None, trash.step)
        assertEquals(bin.songs, bin.host.memory.trashed)
    }

    @Test
    fun `Delete forever never asks for a song out of the trash, nor when MediaStore can't say`() = runTest {
        val bin = Bin()
        var readable = true
        val trash = trash(bin.host, bin.live, PhoneTrash { ids -> bin.inTrash.filterKeys { it in ids }.takeIf { readable } })

        // Restored elsewhere after the list was read.
        bin.inTrash.remove(13L)
        assertTrue(trash.askDelete(bin.songs))
        assertEquals(Confirmation.Changed, trash.confirmDelete(bin.songs))
        assertEquals(TrashStep.None, trash.step)

        readable = false
        assertTrue(trash.askDelete(bin.songs.take(2)))
        assertEquals(Confirmation.Unavailable, trash.confirmDelete(bin.songs.take(2)))
        assertEquals(TrashStep.None, trash.step)
        assertEquals(bin.songs, bin.host.memory.trashed)
    }

    @Test
    fun `songs another app moved to the trash are listed beside KJB's, come back through the phone's restore, and go for good too`() = runTest {
        val bin = Bin()
        val other = TrashedSong(bin.files[1], BASE - DAY, BASE + 29 * DAY)
        val gone = TrashedSong(bin.files[2], BASE - 31 * DAY, BASE - 1)
        val kjb = bin.songs[0]
        bin.host.memory = TrashMemory(trashed = listOf(kjb))
        val phone = object : PhoneTrash {
            override fun inTrash(ids: Collection<Long>): Map<Long, Long?> = bin.inTrash.filterKeys { it in ids }

            // KJB's own song as MediaStore has it too, and one past its expiry the phone has not deleted yet.
            override fun songs(): List<TrashedSong> = listOf(kjb.copy(trashedAt = 0), other, gone).filter { it.file.id in bin.inTrash }
        }
        val trash = trash(bin.host, bin.live, phone)
        assertNull(trash.elsewhere.value)

        trash.refresh()
        assertEquals(listOf(other), trash.elsewhere.value)
        assertEquals(listOf(kjb), bin.host.memory.trashed)

        assertEquals(listOf(12L), trash.restore(other))
        bin.inTrash.remove(12L)
        bin.live += bin.files[1]
        assertEquals(Restored.Back, trash.restoreAnswered(approved = true))
        assertEquals(emptyList<TrashedSong>(), trash.elsewhere.value)
        assertEquals(listOf(kjb), bin.host.memory.trashed)

        bin.inTrash[12L] = BASE + 29 * DAY
        bin.live.clear()
        trash.refresh()
        assertTrue(trash.askDelete(listOf(other, kjb)))
        assertEquals(Confirmation.Ask(listOf(12L, 11L)), trash.confirmDelete(listOf(other, kjb)))
        bin.inTrash.remove(11L)
        bin.inTrash.remove(12L)
        assertEquals(Deleted.Gone(2, of = 2, bytes = bin.files[0].size + bin.files[1].size), trash.deleteAnswered(approved = true))
        assertEquals(emptyList<TrashedSong>(), trash.elsewhere.value)
        assertEquals(emptyList<TrashedSong>(), bin.host.memory.trashed)
    }

    /**
     * The flow over [host], with MediaStore's rows as [files] say (each read first runs [onRows]), the phone's trash as
     * [inTrash] says (empty unless told), and KJB's own writes as [ownWrite] says (none unless told).
     */
    private fun TestScope.trash(
        host: TrashHost,
        files: List<AudioFile>,
        inTrash: PhoneTrash = PhoneTrash { emptyMap() },
        ownWrite: (AudioFile, AudioFile) -> Boolean = { _, _ -> false },
        onRows: () -> Unit = {},
    ) = SongTrash(
        host = host,
        rows = { ids ->
            onRows()
            files.filter { it.id in ids }.associateBy { it.id }
        },
        cleanup = {},
        phoneTrash = inTrash,
        io = StandardTestDispatcher(testScheduler),
        clock = { BASE },
        ownWrite = ownWrite,
    )

    /**
     * The Duplicates engine's part made up: the step and the memory in fields, each commit first running [onCommit], each
     * wait for the stored memory first running [onLoaded].
     */
    private class FakeTrashHost : TrashHost {
        override var step: TrashStep = TrashStep.None
        override var memory: TrashMemory = TrashMemory()
        var onCommit: () -> Unit = {}
        var onLoaded: () -> Unit = {}

        override fun step(change: (TrashStep) -> TrashStep) {
            step = change(step)
        }

        override suspend fun loaded() {
            onLoaded()
            // Read from disk: a suspension, during which the page may go.
            yield()
        }

        override suspend fun commit(moved: Set<Long>, change: (TrashMemory) -> TrashMemory) {
            onCommit()
            // Stored on disk: a suspension, during which the page may go.
            yield()
            memory = change(memory)
        }

        override fun changed(ids: Set<Long>) = Unit
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
        const val DAY: Long = 24 * 60 * 60_000L
    }
}
