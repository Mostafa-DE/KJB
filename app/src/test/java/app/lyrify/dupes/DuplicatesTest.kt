package app.lyrify.dupes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import app.lyrify.tags.AudioFormat
import app.lyrify.tags.EditKind
import app.lyrify.tags.HistoryEntry
import app.lyrify.tags.Payload
import app.lyrify.tags.PayloadHash
import app.lyrify.tags.SongEdit
import app.lyrify.tags.TagFixtures
import app.lyrify.tags.UndoMemory
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler, advanceUntilIdle
class DuplicatesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var songs: LibraryRead = LibraryRead.Files(emptyList())
    private var reads = 0
    /** MediaStore's rows by id now: the library's own, unless a test says otherwise; null while MediaStore cannot be read. */
    private var rows: (Collection<Long>) -> Map<Long, AudioFile>? = { ids -> (songs as? LibraryRead.Files)?.files.orEmpty().filter { it.id in ids }.associateBy { it.id } }
    private val library = object : AudioLibrary {
        override fun read(): LibraryRead {
            reads++
            return songs
        }

        override fun rows(ids: Collection<Long>): Map<Long, AudioFile>? = rows.invoke(ids)
    }
    private val cleaned = mutableListOf<String>()

    /** KJB's History of tag writes, as the engine reads it for a song bigger than when it was listed. */
    private var history = UndoMemory()

    /** The phone's trash: ids in it, with when the phone deletes each; null while MediaStore cannot be read. */
    private var phoneTrash: MutableMap<Long, Long?>? = mutableMapOf()

    /** Runs as MediaStore is asked what is in the phone's trash. */
    private var onTrashRead: () -> Unit = {}
    private val logs = mutableListOf<String>()
    private val storeFile: File get() = File(folder.root, "app/duplicates.json")

    /** Opens files for hashing; a test can step in before a file is read. */
    private var beforeOpen: (Path) -> Unit = {}

    private fun open(path: Path): SeekableByteChannel {
        beforeOpen(path)
        return Files.newByteChannel(path, StandardOpenOption.READ)
    }

    private fun TestScope.engine(): Duplicates = Duplicates(
        library = library,
        store = DuplicatesStore(storeFile) { logs += it },
        cleanup = { cleaned += it },
        phoneTrash = { ids ->
            onTrashRead()
            phoneTrash?.filterKeys { it in ids }
        },
        // Not backgroundScope: advanceUntilIdle runs the engine's work to the end.
        scope = this,
        io = StandardTestDispatcher(testScheduler),
        clock = { BASE + testScheduler.currentTime },
        hasher = ContentHasher(sliceBytes = 4, fullLimit = 32, open = ::open),
        ownWrite = kjbTagWrites { history },
        log = { logs += it },
    )

    /** A song file on disk with its MediaStore row; [content] decides its size and whether it is identical to another. */
    private fun song(
        id: Long,
        content: String,
        title: String = "Lanterns Over Brine",
        artist: String? = "The Quiet Ferns",
        folderName: String = "a",
        durationMs: Long = 200_000,
    ): AudioFile {
        val file = File(folder.root, "Music/$folderName/$id $title.mp3")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return AudioFile(
            id = id, path = file.path, relativePath = "Music/$folderName/", displayName = file.name, title = title, artist = artist,
            album = "Brine", durationMs = durationMs, size = file.length(), mimeType = "audio/mpeg", bitrate = 128_000L + id, addedAt = BASE + id,
        )
    }

    private fun library(vararg files: AudioFile) {
        songs = LibraryRead.Files(files.toList())
    }

    /** As MediaProvider does when it trashes a file: renamed to `.trashed-<expiry>-<name>` in its folder. */
    private fun trashOnDisk(file: AudioFile) {
        val source = File(file.path)
        assertTrue(source.renameTo(File(source.parentFile, ".trashed-1765000000-${source.name}")))
        phoneTrash?.put(file.id, EXPIRES)
    }

    /** As MediaProvider restores a file: back at its path, out of the trash. */
    private fun restoreOnDisk(file: AudioFile) {
        val source = File(file.path)
        assertTrue(File(source.parentFile, ".trashed-1765000000-${source.name}").renameTo(source))
        phoneTrash?.remove(file.id)
    }

    private fun TestScope.scanned(engine: Duplicates): ScanResults {
        engine.scan()
        advanceUntilIdle()
        return engine.state.value.results ?: error("no results")
    }

    private fun assertLogsNameNothing() {
        for (line in logs) {
            assertFalse(line, line.contains("Lanterns") || line.contains("Paper") || line.contains("Ferns") || line.contains(folder.root.path))
        }
    }

    // Scanning

    @Test
    fun `before the first scan there are no results, once the stored memory is read`() = runTest {
        val engine = engine()
        assertFalse(engine.state.value.loaded)
        advanceUntilIdle()
        assertTrue(engine.state.value.loaded)
        assertNull(engine.state.value.results)
        assertEquals(0, engine.state.value.badge)
        assertEquals(ScanStatus.Idle, engine.state.value.scan)
    }

    @Test
    fun `a retagged copy of a song, of another size, is found identical by its audio`() = runTest {
        val audio = TagFixtures.mp3Audio(frames = 40)
        fun copy(id: Long, folderName: String, tag: ByteArray): AudioFile {
            val made = song(id, "", folderName = folderName)
            File(made.path).writeBytes(TagFixtures.mp3(tag, audio))
            return made.copy(size = File(made.path).length())
        }
        val a = copy(1, "a", TagFixtures.id3v2("TIT2 Lanterns Over Brine".encodeToByteArray()))
        val b = copy(2, "b", TagFixtures.id3v2(ByteArray(12_000) { 4 }, version = 4))
        assertNotEquals(a.size, b.size)
        library(a, b)

        val group = scanned(engine()).groups.single()

        assertEquals(Confidence.IDENTICAL, group.confidence)
        assertEquals(setOf(1L, 2L), group.files.map { it.id }.toSet())
    }

    @Test
    fun `a scan counts every file and stores the groups it found`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"), song(3, "Paper Comet", title = "Paper Comet"))
        val engine = engine()
        val seen = mutableListOf<ScanStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.state.map { it.scan }.distinctUntilChanged().collect { seen += it } }
        val results = scanned(engine)
        assertEquals(
            listOf(ScanStatus.Idle, ScanStatus.Running(0, 0), ScanStatus.Running(0, 3), ScanStatus.Running(1, 3), ScanStatus.Running(2, 3), ScanStatus.Running(3, 3), ScanStatus.Idle),
            seen,
        )
        assertEquals(Confidence.IDENTICAL, results.groups.single().confidence)
        assertEquals(setOf(1L, 2L), results.groups.single().files.map { it.id }.toSet())
        assertEquals(3, results.scanned)
        assertEquals(BASE, results.scannedAt)
        assertEquals(results, DuplicatesStore(storeFile).load().results)
        assertLogsNameNothing()
    }

    @Test
    fun `files in a group know whether a lyrics file is next to them`() = runTest {
        val a = song(1, "same bytes")
        library(a, song(2, "same bytes", folderName = "b"))
        File(a.path.replace(".mp3", ".lrc")).writeText("[00:01.00]la")
        val group = scanned(engine()).groups.single()
        assertEquals(mapOf(1L to true, 2L to false), group.files.associate { it.id to it.hasLyrics })
    }

    @Test
    fun `scan again while a scan runs does nothing`() = runTest {
        library(song(1, "x"))
        val engine = engine()
        engine.scan()
        engine.scan()
        advanceUntilIdle()
        assertEquals(1, reads)
    }

    @Test
    fun `cancel stops the scan, keeps the results before, and the next scan starts over`() = runTest {
        val first = listOf(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        library(*first.toTypedArray())
        val engine = engine()
        val before = scanned(engine)
        library(*first.toTypedArray(), song(3, "Paper Comet!", title = "Paper Comet"), song(4, "Paper Comet!", title = "Paper Comet", folderName = "b"))
        beforeOpen = { if (it.fileName.toString().startsWith("3 ")) engine.cancelScan() }
        engine.scan()
        advanceUntilIdle()
        assertEquals(ScanStatus.Idle, engine.state.value.scan)
        assertEquals(before, engine.state.value.results)
        assertEquals(before, DuplicatesStore(storeFile).load().results)
        beforeOpen = {}
        val again = scanned(engine)
        assertEquals(3, reads)
        assertEquals(2, again.groups.size)
    }

    @Test
    fun `songs that cannot be read fail the scan and keep the results before`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        val before = scanned(engine)
        songs = LibraryRead.Unavailable("refused: SecurityException")
        engine.scan()
        advanceUntilIdle()
        assertEquals(ScanStatus.Failed, engine.state.value.scan)
        assertEquals(before, engine.state.value.results)
        assertLogsNameNothing()
    }

    // Review and "Not duplicates"

    @Test
    fun `opened groups no longer count in the badge, also after a restart`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"), song(3, "Paper Comet!", title = "Paper Comet"), song(4, "Paper Comet!", title = "Paper Comet", folderName = "b"))
        val engine = engine()
        val results = scanned(engine)
        assertEquals(2, engine.state.value.badge)
        engine.opened(results.groups[0])
        assertEquals(1, engine.state.value.badge)
        advanceUntilIdle()
        val restarted = engine()
        advanceUntilIdle()
        assertEquals(1, restarted.state.value.badge)
        assertEquals(1, scanned(restarted).unreviewed(restarted.state.value.reviewed))
    }

    @Test
    fun `not duplicates hides a group for good, also after a scan and a restart`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        val group = scanned(engine).groups.single()
        engine.notDuplicates(group)
        assertTrue(engine.state.value.results?.groups.orEmpty().isEmpty())
        assertTrue(scanned(engine).groups.isEmpty())
        val restarted = engine()
        assertTrue(scanned(restarted).groups.isEmpty())
    }

    @Test
    fun `a group not duplicates comes back when another copy appears, and stays hidden through a retag`() = runTest {
        val a = song(1, "same bytes")
        val b = song(2, "same bytes", folderName = "b")
        library(a, b)
        val engine = engine()
        engine.notDuplicates(scanned(engine).groups.single())
        val c = song(3, "same bytes", folderName = "c")
        library(a, b, c)
        assertEquals(setOf(1L, 2L, 3L), scanned(engine).groups.single().files.map { it.id }.toSet())

        engine.notDuplicates(engine.state.value.results?.groups?.single() ?: error("no group"))
        assertTrue(scanned(engine).groups.isEmpty())
        // A retag changes a file's size, never its id: the user's choice stands.
        val changed = song(2, "same bytes, retagged", folderName = "b")
        library(a, changed, c)
        assertTrue(scanned(engine).groups.isEmpty())
    }

    @Test
    fun `a restore's choices that can't be stored change nothing, in memory either`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        val group = scanned(engine).groups.single()
        // Where the file goes is a file now: nothing can be stored.
        val app = storeFile.parentFile!!
        app.deleteRecursively()
        app.writeText("")

        assertFalse(engine.restoreChoices(setOf(group.key), emptySet()))

        assertEquals(listOf(group.key), engine.state.value.results?.groups?.map { it.key })
        assertEquals(1, engine.state.value.badge)
    }

    @Test
    fun `a restore's choices are kept and stored, their groups leaving the results`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        val group = scanned(engine).groups.single()

        assertTrue(engine.restoreChoices(setOf(group.key), emptySet()))

        assertTrue(engine.state.value.results?.groups.orEmpty().isEmpty())
        assertTrue(scanned(engine()).groups.isEmpty())
    }

    // Moving to the trash

    private suspend fun TestScope.trashReady(engine: Duplicates): DuplicateGroup {
        val group = scanned(engine).groups.single()
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(group.files[1].id)))
        return group
    }

    @Test
    fun `KJB's dialog asks first, for any copies of a shown group but never every one`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"), song(3, "same bytes", folderName = "c"))
        val engine = engine()
        val group = scanned(engine).groups.single()
        assertEquals(1L, group.keep.id)
        assertEquals(TrashAsk.NONE_CHECKED, engine.askTrash(group, emptySet()))
        assertEquals(TrashAsk.KEEP_ONE, engine.askTrash(group, setOf(1L, 2L, 3L)))
        assertEquals(TrashAsk.CHANGED, engine.askTrash(group, setOf(2L, 9L)))
        assertEquals(TrashAsk.CHANGED, engine.askTrash(group.copy(files = group.files.take(2)), setOf(2L)))
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(1L, 2L)))
        assertEquals(listOf(1L, 2L), (engine.state.value.trash as TrashStep.Confirm).files.map { it.id })
        engine.cancelTrash()
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(2L, 3L)))
        assertEquals(listOf(2L, 3L), (engine.state.value.trash as TrashStep.Confirm).files.map { it.id })
        engine.cancelTrash()
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertEquals(Confirmation.NotAsked, engine.confirmShown())
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `the files are remembered before the phone's dialog opens`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        trashReady(engine)
        assertEquals(Confirmation.Ask(listOf(2L)), engine.confirmShown())
        assertEquals(listOf(2L), (engine.state.value.trash as TrashStep.System).files.map { it.id })
        assertEquals(listOf(2L), DuplicatesStore(storeFile).load().pendingTrash.map { it.id })
    }

    @Test
    fun `the phone's OK settles the files it moved and drops them from the results`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        library(keep, copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        trashOnDisk(copy)
        assertEquals(Trashed.Moved(1), engine.systemAnswered(approved = true))
        assertEquals(listOf(copy.path), cleaned)
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertTrue(engine.state.value.results?.groups.orEmpty().isEmpty())
        val stored = DuplicatesStore(storeFile).load()
        assertTrue(stored.pendingTrash.isEmpty())
        assertTrue(stored.results?.groups.orEmpty().isEmpty())
        assertTrue(File(keep.path).isFile)
        assertLogsNameNothing()
    }

    @Test
    fun `the suggested keep can go to the trash too, and the copy left stays`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        library(keep, copy)
        val engine = engine()
        val group = scanned(engine).groups.single()
        assertEquals(1L, group.keep.id)
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(1L)))
        engine.confirmShown()
        trashOnDisk(keep)
        assertEquals(Trashed.Moved(1), engine.systemAnswered(approved = true))
        assertEquals(listOf(keep.path), cleaned)
        assertTrue(File(copy.path).isFile)
    }

    @Test
    fun `a group with copies left stays, still reviewed`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"), song(3, "same bytes", folderName = "c"))
        val engine = engine()
        val group = scanned(engine).groups.single()
        engine.opened(group)
        engine.askTrash(group, setOf(3L))
        engine.confirmShown()
        trashOnDisk(group.files.single { it.id == 3L })
        engine.systemAnswered(approved = true)
        val left = engine.state.value.results?.groups?.single() ?: error("no group")
        assertEquals(listOf(1L, 2L), left.files.map { it.id })
        assertEquals(0, engine.state.value.badge)
    }

    @Test
    fun `the phone's cancel changes nothing`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        val group = trashReady(engine)
        engine.confirmShown()
        assertEquals(Trashed.Unchanged, engine.systemAnswered(approved = false))
        assertTrue(cleaned.isEmpty())
        assertTrue(File(copy.path).isFile)
        assertEquals(group, engine.state.value.results?.groups?.single())
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `only files MediaStore shows in its trash are cleaned up, never a file gone for another reason`() = runTest {
        val moved = song(2, "same bytes", folderName = "b")
        val gone = song(3, "same bytes", folderName = "c")
        library(song(1, "same bytes"), moved, gone)
        val engine = engine()
        val group = scanned(engine).groups.single()
        engine.askTrash(group, setOf(2L, 3L))
        engine.confirmShown()
        trashOnDisk(moved)
        // Deleted by another app meanwhile: not at its path, and not in the phone's trash.
        assertTrue(File(gone.path).delete())
        assertEquals(Trashed.Moved(1, of = 2), engine.systemAnswered(approved = true))
        assertEquals(listOf(moved.path), cleaned)
        assertEquals(listOf(2L), engine.state.value.trashed.map { it.file.id })
        assertEquals(setOf(1L, 3L), engine.state.value.results?.groups?.single()?.files?.map { it.id }?.toSet())
    }

    @Test
    fun `when MediaStore cannot say what is in its trash nothing is cleaned up, and the next scan settles it`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        library(keep, copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        trashOnDisk(copy)
        phoneTrash = null
        assertEquals(Trashed.NotConfirmed, engine.systemAnswered(approved = true))
        assertTrue(cleaned.isEmpty())
        assertTrue(engine.state.value.trashed.isEmpty())
        assertEquals(listOf(copy.id), DuplicatesStore(storeFile).load().pendingTrash.map { it.id })
        // While MediaStore still can't say, nothing else is asked: the copy may be gone, and the last one must stay.
        val group = engine.state.value.results?.groups?.single() ?: error("no group")
        assertEquals(TrashAsk.UNAVAILABLE, engine.askTrash(group, setOf(keep.id)))
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertEquals(listOf(copy.id), DuplicatesStore(storeFile).load().pendingTrash.map { it.id })

        phoneTrash = mutableMapOf(copy.id to EXPIRES)
        library(keep)
        scanned(engine)
        assertEquals(listOf(copy.path), cleaned)
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `a Move settles first what an unconfirmed answer left, so a copy the phone did move counts as gone`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        val third = song(3, "same bytes", folderName = "c")
        library(keep, copy, third)
        val engine = engine()
        val group = scanned(engine).groups.single()
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(copy.id)))
        engine.confirmShown()
        trashOnDisk(copy)
        phoneTrash = null
        assertEquals(Trashed.NotConfirmed, engine.systemAnswered(approved = true))

        phoneTrash = mutableMapOf(copy.id to EXPIRES)
        // The sheet still shows three copies, but the phone did move one: of the two left, one stays.
        assertEquals(TrashAsk.KEEP_ONE, engine.askTrash(group, setOf(keep.id, third.id)))
        assertEquals(listOf(copy.path), cleaned)
        assertEquals(listOf(copy.id), engine.state.value.trashed.map { it.file.id })
        assertEquals(TrashAsk.CHANGED, engine.askTrash(group, setOf(copy.id)))
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(third.id)))
        assertEquals(Confirmation.Ask(listOf(third.id)), engine.confirmShown())
    }

    @Test
    fun `a Move whose phone answer never came gives way to the next, which settles what it left`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        val third = song(3, "same bytes", folderName = "c")
        library(keep, copy, third)
        val engine = engine()
        val group = scanned(engine).groups.single()
        engine.askTrash(group, setOf(copy.id))
        engine.confirmShown()
        // The user said OK, but the answer never reached KJB: the dialog step is still open.
        trashOnDisk(copy)
        assertTrue(engine.state.value.trash is TrashStep.System)
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(third.id)))
        assertEquals(listOf(copy.path), cleaned)
        assertEquals(listOf(1L, 3L), engine.state.value.results?.groups?.single()?.files?.map { it.id })
        assertEquals(Confirmation.Ask(listOf(third.id)), engine.confirmShown())
        trashOnDisk(third)
        assertEquals(Trashed.Moved(1), engine.systemAnswered(approved = true))
        assertEquals(listOf(copy.id, third.id), engine.state.value.trashed.map { it.file.id }.sorted())
    }

    @Test
    fun `a Move whose screen went while it settled what was left opens no dialog`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        val third = song(3, "same bytes", folderName = "c")
        library(song(1, "same bytes"), copy, third)
        val engine = engine()
        val group = scanned(engine).groups.single()
        engine.askTrash(group, setOf(copy.id))
        engine.confirmShown()
        // The phone's answer never came; the next Move settles it, and its screen goes meanwhile.
        lateinit var moving: Job
        onTrashRead = { moving.cancel() }
        moving = launch { engine.askTrash(group, setOf(third.id)) }
        advanceUntilIdle()

        assertTrue(moving.isCancelled)
        assertFalse(engine.state.value.trash is TrashStep.Confirm)
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `opening the tab settles an answer the app did not hear`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        library(keep, copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        trashOnDisk(copy)
        val restarted = engine()
        restarted.refreshTrashed()
        assertEquals(listOf(copy.path), cleaned)
        assertEquals(listOf(copy.id), restarted.state.value.trashed.map { it.file.id })
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `a file changed since the scan is not moved, and its group waits for the next scan`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        library(keep, copy)
        val engine = engine()
        for (changed in listOf(copy.copy(size = copy.size + 1), copy.copy(path = copy.path + ".moved"), copy.copy(displayName = "renamed.mp3"), null)) {
            val group = scanned(engine).groups.single()
            assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(2L)))
            rows = { ids -> mapOf(1L to keep).filterKeys { it in ids } + listOfNotNull(changed).associateBy { it.id } }
            assertEquals("$changed", Confirmation.Changed, engine.confirmShown())
            assertEquals(TrashStep.None, engine.state.value.trash)
            assertEquals(setOf(group.key), engine.state.value.stale)
            assertEquals(TrashAsk.CHANGED, engine.askTrash(group, setOf(2L)))
            assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
            rows = { ids -> (songs as LibraryRead.Files).files.filter { it.id in ids }.associateBy { it.id } }
        }
        scanned(engine)
        assertTrue(engine.state.value.stale.isEmpty())
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun `a copy KJB retagged since the scan can still be moved, but not once another app changed it`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        library(keep, copy)
        val engine = engine()
        val group = scanned(engine).groups.single()
        // KJB writes the copy's tags: it is bigger now, and MediaStore says so after KJB's rescan.
        val (lengthBefore, before) = PayloadHash.fileHash(File(copy.path).toPath())
        File(copy.path).appendText(" with new tags")
        val (lengthAfter, after) = PayloadHash.fileHash(File(copy.path).toPath())
        val edit = SongEdit(copy.id, copy.path, Payload(AudioFormat.MP3, "audio"), before, after, emptyList(), lengthBefore = lengthBefore, lengthAfter = lengthAfter)
        history = UndoMemory(listOf(HistoryEntry(1, EditKind.SONG, "Lanterns", BASE, listOf(edit))))
        rows = { ids -> listOf(keep, copy.copy(size = lengthAfter)).filter { it.id in ids }.associateBy { it.id } }

        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(copy.id)))
        assertEquals(Confirmation.Ask(listOf(copy.id)), engine.confirmShown())
        assertEquals(Trashed.Unchanged, engine.systemAnswered(approved = false))

        // Another app rewrote it since, keeping its size.
        File(copy.path).writeText(File(copy.path).readText().reversed())
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(copy.id)))
        assertEquals(Confirmation.Changed, engine.confirmShown())
        assertEquals(setOf(group.key), engine.state.value.stale)
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun `MediaStore unreadable before the phone's dialog asks nothing`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        trashReady(engine)
        rows = { null }
        assertEquals(Confirmation.Unavailable, engine.confirmShown())
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertTrue(engine.state.value.stale.isEmpty())
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `a file the phone did not move keeps its lyrics and its place`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        val group = trashReady(engine)
        engine.confirmShown()
        assertEquals(Trashed.Moved(0, of = 1), engine.systemAnswered(approved = true))
        assertTrue(cleaned.isEmpty())
        assertEquals(group, engine.state.value.results?.groups?.single())
    }

    @Test
    fun `an answer with nothing asked changes nothing`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        scanned(engine)
        assertEquals(Trashed.Unchanged, engine.systemAnswered(approved = true))
        assertTrue(cleaned.isEmpty())
    }

    @Test
    fun `an answer the app did not live to hear is settled by the next scan`() = runTest {
        val keep = song(1, "same bytes")
        val copy = song(2, "same bytes", folderName = "b")
        library(keep, copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        // The app dies with the phone's dialog open; the user said OK.
        trashOnDisk(copy)
        library(keep)
        val restarted = engine()
        val results = scanned(restarted)
        assertEquals(listOf(copy.path), cleaned)
        assertTrue(results.groups.isEmpty())
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `an answer the app did not live to hear that left the files changes nothing`() = runTest {
        library(song(1, "same bytes"), song(2, "same bytes", folderName = "b"))
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        val restarted = engine()
        assertEquals(1, scanned(restarted).groups.size)
        assertTrue(cleaned.isEmpty())
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `confirmed trash is cleaned up even when the original audio path exists`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        phoneTrash?.put(copy.id, EXPIRES)
        assertEquals(Trashed.Moved(1), engine.systemAnswered(approved = true))
        assertEquals(listOf(copy.path), cleaned)
        assertTrue(File(copy.path).exists())
    }

    @Test
    fun `recovery keeps unknown trash pending and never cleans a file missing for another reason`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        assertTrue(File(copy.path).delete())
        phoneTrash = null
        val restarted = engine()
        scanned(restarted)
        assertTrue(cleaned.isEmpty())
        assertEquals(listOf(copy.id), DuplicatesStore(storeFile).load().pendingTrash.map { it.id })
        phoneTrash = mutableMapOf()
        scanned(restarted)
        assertTrue(cleaned.isEmpty())
        assertTrue(DuplicatesStore(storeFile).load().pendingTrash.isEmpty())
    }

    @Test
    fun `recovery confirms trash even while the audio path still exists`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        val keep = song(1, "same bytes")
        library(keep, copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        phoneTrash?.put(copy.id, EXPIRES)
        library(keep)
        scanned(engine())
        assertEquals(listOf(copy.path), cleaned)
        assertTrue(File(copy.path).exists())
    }

    // Restoring from the trash

    /** [copy] moved to the trash through both dialogs; the song as the tab lists it. */
    private suspend fun TestScope.trashedSong(engine: Duplicates, copy: AudioFile): TrashedSong {
        val group = scanned(engine).groups.single()
        assertEquals(TrashAsk.ASKED, engine.askTrash(group, setOf(copy.id)))
        engine.confirmShown()
        trashOnDisk(copy)
        assertEquals(Trashed.Moved(1), engine.systemAnswered(approved = true))
        return engine.state.value.trashed.single()
    }

    @Test
    fun `a song moved to the trash is listed until the phone deletes it`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        assertEquals(TrashedSong(copy, BASE, EXPIRES), trashedSong(engine, copy))
        assertEquals(listOf(TrashedSong(copy, BASE, EXPIRES)), DuplicatesStore(storeFile).load().trashed)
    }

    @Test
    fun `without the phone's expiry a trashed song is listed for 30 days`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        val group = scanned(engine).groups.single()
        engine.askTrash(group, setOf(2L))
        engine.confirmShown()
        trashOnDisk(copy)
        phoneTrash?.put(copy.id, null)
        engine.systemAnswered(approved = true)
        assertEquals(BASE + 30 * DAY, engine.state.value.trashed.single().expiresAt)
    }

    @Test
    fun `an answer the app did not live to hear lists the songs it moved too`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        trashReady(engine)
        engine.confirmShown()
        trashOnDisk(copy)
        val restarted = engine()
        scanned(restarted)
        assertEquals(listOf(copy.id), restarted.state.value.trashed.map { it.file.id })
    }

    @Test
    fun `restore asks the phone for the song, and its OK takes the song off the list`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        val trashed = trashedSong(engine, copy)
        assertEquals(listOf(2L), engine.restore(trashed))
        assertEquals(TrashStep.Restoring(trashed), engine.state.value.trash)
        restoreOnDisk(copy)
        assertEquals(Restored.Back, engine.restoreAnswered(approved = true))
        assertTrue(engine.state.value.trashed.isEmpty())
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertTrue(DuplicatesStore(storeFile).load().trashed.isEmpty())
        assertTrue(File(copy.path).isFile)
        assertLogsNameNothing()
    }

    @Test
    fun `cancel in the phone's restore dialog changes nothing`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        val trashed = trashedSong(engine, copy)
        engine.restore(trashed)
        assertEquals(Restored.Unchanged, engine.restoreAnswered(approved = false))
        assertEquals(listOf(trashed), engine.state.value.trashed)
        assertEquals(TrashStep.None, engine.state.value.trash)
        assertEquals(listOf(trashed), DuplicatesStore(storeFile).load().trashed)
    }

    @Test
    fun `an OK while the song is still in the trash, or MediaStore unreadable, changes nothing`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy)
        val engine = engine()
        val trashed = trashedSong(engine, copy)
        engine.restore(trashed)
        assertEquals(Restored.Unchanged, engine.restoreAnswered(approved = true))
        phoneTrash = null
        engine.restore(trashed)
        assertEquals(Restored.Unchanged, engine.restoreAnswered(approved = true))
        assertEquals(listOf(trashed), engine.state.value.trashed)
    }

    @Test
    fun `restore is only asked for a listed song, with no other dialog open`() = runTest {
        val copy = song(2, "same bytes", folderName = "b")
        library(song(1, "same bytes"), copy, song(3, "same bytes", folderName = "c"))
        val engine = engine()
        val trashed = trashedSong(engine, copy)
        assertEquals(emptyList<Long>(), engine.restore(trashed.copy(file = trashed.file.copy(id = 9))))
        assertEquals(TrashAsk.ASKED, engine.askTrash(engine.state.value.results?.groups?.single() ?: error("no group"), setOf(3L)))
        assertEquals(emptyList<Long>(), engine.restore(trashed))
        assertEquals(Restored.Unchanged, engine.restoreAnswered(approved = true))
    }

    @Test
    fun `songs restored elsewhere, deleted for good or expired drop off the list`() = runTest {
        val a = song(2, "same bytes", folderName = "b")
        val b = song(3, "same bytes", folderName = "c")
        library(song(1, "same bytes"), a, b)
        val engine = engine()
        val group = scanned(engine).groups.single()
        engine.askTrash(group, setOf(2L, 3L))
        engine.confirmShown()
        trashOnDisk(a)
        trashOnDisk(b)
        engine.systemAnswered(approved = true)
        assertEquals(2, engine.state.value.trashed.size)
        phoneTrash?.remove(3L)
        engine.refreshTrashed()
        assertEquals(listOf(2L), engine.state.value.trashed.map { it.file.id })
        assertEquals(listOf(2L), DuplicatesStore(storeFile).load().trashed.map { it.file.id })
        advanceTimeBy(EXPIRES - BASE + 1)
        engine.refreshTrashed()
        assertTrue(engine.state.value.trashed.isEmpty())
    }

    private companion object {
        const val BASE: Long = 1_762_450_000_000
        const val DAY: Long = 24 * 60 * 60_000L
        const val EXPIRES: Long = BASE + 30 * DAY
    }
}

/** "Move to trash" in KJB's dialog, for the files it shows. */
private suspend fun Duplicates.confirmShown(): Confirmation = confirmTrash((state.value.trash as? TrashStep.Confirm)?.files.orEmpty())
