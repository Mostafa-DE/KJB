package app.lyrify.data

import android.database.Cursor
import android.provider.MediaStore
import app.lyrify.ai.ArrayMix
import app.lyrify.dupes.LibraryRead
import app.lyrify.dupes.MediaStoreLibrary
import app.lyrify.lyrics.Created
import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import app.lyrify.lyrics.Removal
import app.lyrify.lyrics.SidecarFile
import app.lyrify.tones.FakeEncoder
import app.lyrify.tones.FakeMedia
import app.lyrify.tones.MemoryToneList
import app.lyrify.tones.SaveFailure
import app.lyrify.tones.SaveResult
import app.lyrify.tones.SavedTone
import app.lyrify.tones.ToneEdit
import app.lyrify.tones.ToneFiles
import app.lyrify.tones.ToneKind
import app.lyrify.tones.ToneRequest
import app.lyrify.tones.ToneWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * The one checkpoint for changes to the user's files: a debug build changes only `Music/KJB test/`, a release build
 * everything, and every writer in the app asks it first. Made-up names only.
 */
class SharedWritesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val debug = SharedWrites(testFolderOnly = true) { logs += it }

    private val inTestFolder = listOf(
        "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3",
        "/storage/emulated/0/Music/KJB test/Paper Lantern.lrc",
        "/storage/emulated/10/Music/KJB test/Harbour Glass/01 Tin Roof.flac",
        "/storage/1A2B-3C4D/Music/KJB test/Tin Roof.m4a",
        "/storage/emulated/0/Music/KJB test/Harbour Glass/../Tin Roof.opus",
    )

    private val elsewhere = listOf(
        "/storage/emulated/0/Music/Paper Lantern.mp3",
        "/storage/emulated/0/Music/KJB test",
        "/storage/emulated/0/Music/KJB test/",
        "/storage/emulated/0/Music/KJB testing/Paper Lantern.mp3",
        "/storage/emulated/0/Music/KJB test/../Paper Lantern.mp3",
        "/storage/emulated/0/Music/KJB test/../../Ringtones/Paper Lantern.m4a",
        "/storage/emulated/0/Music/Rips/KJB test/Paper Lantern.mp3",
        "/storage/emulated/0/Ringtones/Paper Lantern (KJB).m4a",
        "/storage/emulated/0/music/kjb test/Paper Lantern.mp3",
        "/storage/emulated/Music/KJB test/Paper Lantern.mp3",
        "/storage/self/primary/Music/KJB test/Paper Lantern.mp3",
        "/sdcard/Music/KJB test/Paper Lantern.mp3",
        "/data/user/0/app.lyrify.debug/files/Music/KJB test/Paper Lantern.mp3",
        "Music/KJB test/Paper Lantern.mp3",
        "/storage/emulated/0/Music/KJB test/Paper\u0000Lantern.mp3",
        "",
    )

    // The rule

    @Test
    fun `a debug build changes and lists files in the test folder on any volume`() {
        inTestFolder.forEach { path ->
            SharedWrite.entries.forEach { write -> assertTrue("$write $path", debug.allows(write, path)) }
            assertTrue(path, debug.lists(path))
            assertTrue(path, SharedWrites.inTestFolder(path))
        }
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `a debug build changes and lists nothing outside the test folder`() {
        elsewhere.forEach { path ->
            SharedWrite.entries.forEach { write -> assertFalse("$write $path", debug.allows(write, path)) }
            assertFalse(path, debug.lists(path))
        }
        // Each refusal is logged by its kind, never with a path or a name.
        assertEquals(elsewhere.size * SharedWrite.entries.size, logs.size)
        assertTrue(logs.toString(), logs.all { line -> SharedWrite.entries.any { it.name in line } })
        assertFalse(logs.toString(), logs.any { "Paper" in it || "/storage" in it || "Ringtones" in it })
    }

    @Test
    fun `a release build changes and lists everything, as 2_0 did`() {
        val release = SharedWrites(testFolderOnly = false) { logs += it }
        (inTestFolder + elsewhere).forEach { path ->
            SharedWrite.entries.forEach { write ->
                assertTrue("$write $path", release.allows(write, path))
                assertTrue("$write $path", SharedWrites.ANYWHERE.allows(write, path))
            }
            assertTrue(path, release.lists(path))
        }
        assertFalse(SharedWrites.ANYWHERE.testFolderOnly)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `songs by MediaStore id change only when MediaStore puts every one in the test folder`() {
        val paths = mapOf(1L to inTestFolder[0], 2L to inTestFolder[3], 3L to elsewhere[0])
        val asked = mutableListOf<Collection<Long>>()
        val lookup = { ids: Collection<Long> -> asked += ids; paths }

        assertTrue(debug.allowsIds(SharedWrite.TRASH, listOf(1L, 2L), lookup))
        assertFalse(debug.allowsIds(SharedWrite.TRASH, listOf(1L, 3L), lookup))
        // An id MediaStore no longer lists, or no answer at all: refused.
        assertFalse(debug.allowsIds(SharedWrite.UNTRASH, listOf(1L, 4L), lookup))
        assertFalse(debug.allowsIds(SharedWrite.UNTRASH, listOf(1L)) { null })
        assertEquals(3, asked.size)
        // A release build never asks MediaStore for paths.
        assertTrue(SharedWrites.ANYWHERE.allowsIds(SharedWrite.TRASH, listOf(3L)) { error("not asked") })
    }

    @Test
    fun `a debug build changes nothing it would reach through a link`() {
        // The phone's storage, in a temporary folder.
        val root = folder.newFolder("phone").toPath()
        val writes = SharedWrites(testFolderOnly = true, root = root) { logs += it }
        val volume = Files.createDirectories(root.resolve("storage/emulated/0"))
        val ringtones = Files.createDirectories(volume.resolve("Ringtones"))
        val tone = Files.createFile(ringtones.resolve("Paper Lantern.m4a"))
        val music = Files.createDirectories(volume.resolve("Music"))
        val lrc = "/storage/emulated/0/Music/KJB test/Paper Lantern.lrc"
        val song = "/storage/emulated/0/Music/KJB test/Paper Lantern.m4a"
        val inFolder = "/storage/emulated/0/Music/KJB test/Harbour Glass/Tin Roof.lrc"

        // The test folder itself a link to another folder.
        val linked = Files.createSymbolicLink(music.resolve("KJB test"), ringtones)
        assertFalse(writes.allows(SharedWrite.LRC_CREATE, lrc))
        assertFalse(writes.allows(SharedWrite.LRC_DELETE, song))
        assertFalse(writes.lists(song))
        assertFalse(writes.allowsIds(SharedWrite.TRASH, listOf(1L)) { mapOf(1L to song) })

        // A real test folder: a folder not made yet is fine, a folder in it that is a link is not.
        Files.delete(linked)
        val test = Files.createDirectory(music.resolve("KJB test"))
        assertTrue(writes.allows(SharedWrite.LRC_CREATE, lrc))
        assertTrue(writes.allows(SharedWrite.LRC_CREATE, inFolder))
        Files.createSymbolicLink(test.resolve("Harbour Glass"), ringtones)
        assertFalse(writes.allows(SharedWrite.LRC_CREATE, inFolder))

        // A file in the test folder that is a link to one outside it.
        Files.createSymbolicLink(test.resolve("Paper Lantern.m4a"), tone)
        assertFalse(writes.allows(SharedWrite.LRC_DELETE, song))
        assertFalse(writes.lists(song))

        // A volume that is a link, even to a real test folder.
        Files.createSymbolicLink(root.resolve("storage/1A2B-3C4D"), volume)
        assertTrue(SharedWrites.inTestFolder("/storage/1A2B-3C4D/Music/KJB test/Paper Lantern.lrc"))
        assertFalse(writes.allows(SharedWrite.LRC_CREATE, "/storage/1A2B-3C4D/Music/KJB test/Paper Lantern.lrc"))

        assertTrue(Files.exists(tone))
        assertFalse(logs.toString(), logs.any { "Paper" in it || "/storage" in it })
    }

    // Every writer asks it first

    /** A MediaStore cursor over [rows], each a row's values by column; a column a row leaves out is null. */
    private fun cursor(rows: List<Map<String, Any?>>): Cursor {
        val columns = mutableListOf<String>()
        var at = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            val value = { rows[at][columns[args[0] as Int]] }
            when (method.name) {
                "getColumnIndexOrThrow" -> columns.indexOf(args[0] as String).takeIf { it >= 0 } ?: columns.size.also { columns += args[0] as String }
                "moveToNext" -> ++at < rows.size
                "getString" -> value() as String?
                "getInt" -> (value() as Number?)?.toInt() ?: 0
                "getLong" -> (value() as Number?)?.toLong() ?: 0L
                "isNull" -> value() == null
                "close" -> null
                else -> error("not in the fake: ${method.name}")
            }
        } as Cursor
    }

    private fun audioRow(id: Long, path: String): Map<String, Any?> = mapOf(
        MediaStore.Audio.Media._ID to id,
        "_data" to path,
        MediaStore.Audio.Media.RELATIVE_PATH to path.substringAfter("/0/").substringBeforeLast('/') + "/",
        MediaStore.Audio.Media.TITLE to "Paper Lantern",
        MediaStore.Audio.Media.DURATION to 201_000L,
        MediaStore.Audio.Media.SIZE to 4_000_000L,
        MediaStore.Audio.Media.IS_MUSIC to 1,
    )

    @Test
    fun `a debug build's song library reads and looks up by id only songs in the test folder`() {
        val rows = listOf(audioRow(1, inTestFolder[0]), audioRow(2, elsewhere[0]))
        val queries = mutableListOf<String?>()
        val debugLibrary = MediaStoreLibrary(query = { selection, _ -> queries += selection; cursor(rows) }, writes = debug)

        assertEquals(listOf(1L), (debugLibrary.read() as LibraryRead.Files).files.map { it.id })
        // MediaStore answers the id lookup with a song outside the test folder: it is left out like one MediaStore no longer lists.
        assertEquals(setOf(1L), debugLibrary.rows(listOf(1L, 2L))?.keys)
        assertEquals(2, queries.size)

        val release = MediaStoreLibrary(query = { _, _ -> cursor(rows) }, writes = SharedWrites.ANYWHERE)
        assertEquals(listOf(1L, 2L), (release.read() as LibraryRead.Files).files.map { it.id })
        assertEquals(setOf(1L, 2L), release.rows(listOf(1L, 2L))?.keys)
    }


    private fun song(name: String = "Paper Lantern.mp3"): LocalTrack {
        val file = File(folder.root, name).apply { writeBytes(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 1, 2)) }
        return LocalTrack(file.path, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000)
    }

    private fun sidecar(track: LocalTrack) = File(checkNotNull(LrcFormat.sidecarPath(track.path)))

    @Test
    fun `the pipeline neither looks up nor writes lyrics for a song outside the test folder`() = runTest {
        val client = FakeLrclib()
        val ledger = MemoryLedger()
        val pipeline = LyricsPipeline(client = client, ledger = ledger, clock = { NOW }, writes = debug)
        val track = song()

        assertNull(pipeline.process(track, force = true))

        assertEquals(0, client.requests)
        assertFalse(sidecar(track).exists())
        assertEquals(LedgerState(), ledger.ledger.value)
    }

    @Test
    fun `the pipeline leaves lyrics it wrote outside the test folder when the user removes them`() = runTest {
        val ledger = MemoryLedger()
        val pipeline = LyricsPipeline(client = FakeLrclib(), ledger = ledger, clock = { NOW }, writes = debug)
        val track = song()
        val bytes = "[00:01.000]la la\n".encodeToByteArray()
        sidecar(track).writeBytes(bytes)
        val entry = recorded(null, track, LyricsStatus.SYNCED, 3, NOW, sha256 = SidecarFile.sha256(bytes), size = bytes.size.toLong())
        ledger.put(entry)

        assertEquals(Removal.FAILED, pipeline.remove(track.path))

        assertTrue(sidecar(track).exists())
        assertEquals(entry, ledger[track.path])
    }

    @Test
    fun `lyrics files outside the test folder are never created or deleted`() {
        val path = File(folder.root, "Paper Lantern.lrc").toPath()

        assertEquals(Created.FAILED, SidecarFile.create(path, "[00:01.000]la\n".encodeToByteArray(), debug, log = {}))
        assertFalse(path.toFile().exists())

        path.toFile().writeText("[00:01.000]la\n")
        assertEquals(Removal.FAILED, SidecarFile.delete(path, debug, log = {}))
        assertFalse(SidecarFile.deleteInterrupted(path, debug, log = {}))
        assertTrue(path.toFile().exists())
    }

    @Test
    fun `a debug build saves, renames and deletes no tone`() = runBlocking {
        val media = FakeMedia()
        val list = MemoryToneList()
        val opened = mutableListOf<File>()
        val files = ToneFiles(
            media = media,
            writes = debug,
            openSong = { file -> opened += file; ArrayMix(FloatArray(48_000) { 0.5f }) },
            newEncoder = { FakeEncoder() },
            list = list,
            clock = { NOW },
            log = {},
            io = Dispatchers.Unconfined,
        )
        val request = ToneRequest("/storage/emulated/0/Music/KJB test/Paper Lantern.mp3", "Paper Lantern", ToneWindow(0, 500), 0, 0, ToneKind.RINGTONE)
        val tone = SavedTone(41, "Paper Lantern (KJB)", ToneKind.RINGTONE, request.songPath, 0, 500, fileName = "Paper Lantern (KJB).m4a", size = 48_000)
        list.put(tone)

        assertEquals(SaveResult.Failed(SaveFailure.NOT_SAVED), files.save(request) {})
        assertEquals(ToneEdit.Failed, files.rename(tone, "Tin Roof"))
        assertEquals(ToneEdit.Failed, files.delete(tone))

        assertEquals(emptyList<String>(), media.calls)
        assertEquals(emptyList<File>(), opened)
        assertEquals(mapOf(41L to tone), list.current.tones)
    }

    @Test
    fun `a debug build builds no tone path, so a stored name with dots never steps into the test folder`() = runBlocking {
        val media = FakeMedia()
        val list = MemoryToneList()
        val files = ToneFiles(
            media = media,
            writes = debug,
            openSong = { error("not opened") },
            newEncoder = { FakeEncoder() },
            list = list,
            clock = { NOW },
            log = {},
            io = Dispatchers.Unconfined,
        )
        val fileName = "../Music/KJB test/Paper Lantern (KJB).m4a"
        val tone = SavedTone(41, "Paper Lantern (KJB)", ToneKind.RINGTONE, inTestFolder[0], 0, 500, fileName = fileName, size = 48_000)
        list.put(tone)
        // Ringtones/ and then that name is in the test folder by its text.
        assertTrue(SharedWrites.inTestFolder("/storage/emulated/0/Ringtones/$fileName"))

        assertEquals(ToneEdit.Failed, files.rename(tone, "Tin Roof"))
        assertEquals(ToneEdit.Failed, files.delete(tone))
        assertEquals(SaveResult.Failed(SaveFailure.NOT_SAVED), files.save(ToneRequest(inTestFolder[0], "../Music/KJB test/Paper Lantern", ToneWindow(0, 500), 0, 0, ToneKind.RINGTONE)) {})

        assertEquals(emptyList<String>(), media.calls)
        assertEquals(mapOf(41L to tone), list.current.tones)
    }

    /**
     * Every source file that changes files or MediaStore rows, by what it changes. A new one fails this test until it is
     * listed: a writer to shared storage asks [SharedWrites] (and goes in [SHARED]), anything else writes only the app's
     * own storage (and goes in [APP_PRIVATE]).
     */
    @Test
    fun `every writer is known, and every writer to shared storage asks the checkpoint`() {
        val roots = listOf("src/main/java/app/lyrify", "src/debug/java/app/lyrify").map(::File)
        assertTrue("run from the app module: ${File("").absolutePath}", roots[0].isDirectory)
        val writers = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && WRITE_CALLS.containsMatchIn(it.readText()) }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }.toSet()

        assertEquals(
            "A file that changes files or MediaStore rows is not listed here: route its writes to shared storage through " +
                "SharedWrites and list it in SHARED, or list it in APP_PRIVATE if it writes only the app's own storage.",
            (SHARED + APP_PRIVATE).sorted(),
            writers.sorted(),
        )
        SHARED.forEach { writer ->
            val text = roots.map { File(it, writer) }.first { it.isFile }.readText()
            assertTrue("$writer does not ask SharedWrites", ".allows(" in text || ".allowsIds(" in text)
            assertTrue("$writer names no kind of write", Regex("""SharedWrite\.[A-Z]""").containsMatchIn(text))
        }
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000L

        /** Calls that change a file, a folder or a MediaStore row. */
        val WRITE_CALLS = Regex(
            """Files\.(delete|deleteIfExists|write|writeString|move|copy|createFile|createDirector\w*|newOutputStream|newBufferedWriter)\(""" +
                """|FileChannel\.open\(|FileOutputStream\(|RandomAccessFile\(|FileWriter\(""" +
                """|\.(writeText|writeBytes|appendText|appendBytes|deleteRecursively|renameTo|copyTo|setLastModified)\(|\.(delete|mkdir|mkdirs)\(\)""" +
                """|[rR]esolver\.(insert|update|delete|bulkInsert)\(|MediaStore\.create\w+Request\(|openFileDescriptor\(|openOutputStream\(""",
        )

        /** Change the user's files (shared storage): each asks [SharedWrites] first. */
        val SHARED: Set<String> = setOf(
            "backup/M3u.kt",
            "dupes/Duplicates.kt",
            "lyrics/LyricsPipeline.kt",
            "tones/ToneFiles.kt",
        )

        /** Change only the app's own storage (files/, cache/, DataStore). */
        val APP_PRIVATE: Set<String> = setOf(
            "data/LedgerRepository.kt",
            "dupes/DuplicatesStore.kt",
            "library/Playlists.kt",
            "models/ModelDownloadWorker.kt",
            "models/ModelStore.kt",
            "overnight/AiRunLog.kt",
            "overnight/AiWorker.kt",
            "overnight/CrashGuard.kt",
            "settings/SettingsStore.kt",
            "tones/ToneAudio.kt",
        )
    }
}
