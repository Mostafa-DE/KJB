package app.lyrify.ui

import androidx.compose.runtime.mutableStateOf
import app.lyrify.R
import app.lyrify.backup.ImportBundle
import app.lyrify.backup.ImportFailure
import app.lyrify.backup.ImportKind
import app.lyrify.backup.ImportOutcome
import app.lyrify.backup.ImportPlan
import app.lyrify.backup.ImportPlaylist
import app.lyrify.backup.ImportPlays
import app.lyrify.backup.ImportQueue
import app.lyrify.backup.ImportSong
import app.lyrify.backup.ImportUndo
import app.lyrify.library.song
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Settings > Backup > Import from app: the row, the preview's rows, the reasons, the copied list, and the words. Made-up names only. */
class ImportScreensTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", durationMs = 201_000)

    @Test
    fun `Settings, Backup has Import from app under Restore, opening the import`() {
        val settings = File("src/main/java/app/lyrify/ui/SettingsScreen.kt").readText()
        val backup = File("src/main/java/app/lyrify/ui/BackupScreens.kt").readText()

        val restore = settings.indexOf("R.string.set_restore, onClick = backup.restore")
        val import = settings.indexOf("R.string.import_row, stringResource(R.string.import_row_detail), onClick = backup.importFromApp")
        assertTrue(restore in 0 until import)
        assertTrue(backup.contains("importFromApp = importFromApp"))
        assertEquals("Import from app", strings().getValue("import_row"))
    }

    @Test
    fun `the preview has a row for each kind the import holds, in order, with its count`() {
        val bundle = ImportBundle(
            playlists = listOf(ImportPlaylist("Road Trip", listOf(ImportSong(lantern.path, lantern.path)))),
            plays = listOf(ImportPlays(ImportSong(lantern.path, lantern.path), 3)),
        )

        assertEquals(listOf(ImportRow(ImportKind.PLAYLISTS, 1), ImportRow(ImportKind.PLAYS, 1)), importRows(ImportPlan(bundle, listOf(lantern))))
        val queued = ImportPlan(bundle.copy(queues = listOf(ImportQueue(List(3) { ImportSong(lantern.path, lantern.path) }))), listOf(lantern))
        assertEquals(ImportRow(ImportKind.QUEUES, 1, songs = 3), importRows(queued).last())
        assertEquals(listOf(R.string.import_kind_playlists, R.string.import_kind_favourites, R.string.import_kind_plays, R.string.import_kind_queues), ImportKind.entries.map(::kindLabel))
    }

    @Test
    fun `each reason has its few words and its sentence`() {
        assertEquals(
            listOf(R.string.import_not_recognised, R.string.import_too_big, R.string.import_unreadable, R.string.import_empty, R.string.import_unreadable),
            ImportFailure.entries.map(::failureTitle),
        )
        assertEquals(
            listOf(R.string.import_not_recognised_text, R.string.import_too_big_text, R.string.import_unreadable_text, R.string.import_empty_text, R.string.import_musicolet_text),
            ImportFailure.entries.map(::failureText),
        )
        assertEquals("KJB cannot read this file. Try an m3u, pls or xspf file, or a Musicolet backup.", strings().getValue("import_not_recognised_text"))
        assertEquals("This Musicolet backup can't be read.", strings().getValue("import_musicolet_text"))
    }

    @Test
    fun `Copy list puts each song not found on its own line, as the sheet shows it`() {
        val missing = listOf(
            ImportSong("/sdcard/Music/Gone/03 Glass Steps.mp3", "/sdcard/Music/Gone/03 Glass Steps.mp3", info = "Odo Brannick - Glass Steps"),
            ImportSong("C:\\Music\\Night Ferry\\02 Salt Road.mp3", "Music/Night Ferry/02 Salt Road.mp3"),
            ImportSong("", null, title = "Low Water", artist = "Mira Solen"),
        )

        assertEquals("Odo Brannick - Glass Steps\n02 Salt Road.mp3\nLow Water · Mira Solen", missingList(missing))
    }

    @Test
    fun `the counts read 12 songs not found on your phone, and Imported 1 playlist`() {
        assertEquals("12 songs not found on your phone", plural("import_not_found").getValue("other").format("12"))
        assertEquals("1 song not found on your phone", plural("import_not_found").getValue("one").format("1"))
        assertEquals("Imported 1 playlist", plural("import_done").getValue("one").format("1"))
        assertEquals("Imported 12 playlists", plural("import_done").getValue("other").format("12"))
        assertEquals("3 playlists other apps saved on your phone.", plural("import_phone_text").getValue("other").format("3"))
        assertEquals("6,863 songs", plural("import_queue_songs").getValue("other").format("6,863"))
        assertEquals("Musicolet: Morning", strings().getValue("import_queue_named").format("Morning"))
        assertEquals("Playlists and favourites from other players", strings().getValue("import_row_detail"))
        assertEquals("Undo failed", strings().getValue("import_not_undone"))
        assertTrue(strings().getValue("import_not_undone_text").endsWith("."))
    }

    @Test
    fun `a summary of favourites alone reads Added N songs to Favourites, and says it once`() {
        val favourites = ImportOutcome(created = 0, extended = 0, songsAdded = 0, favouritesAdded = 12, countsRaised = 0, undo = ImportUndo(favourites = List(12) { lantern.ref }))
        val both = favourites.copy(created = 2, undo = favourites.undo.copy(created = mapOf("p1" to listOf(lantern.ref), "p2" to listOf(lantern.ref))))

        assertEquals(SummaryTitle(R.plurals.import_done_to_favourites, 12), summaryTitle(favourites))
        assertEquals(emptyList<Pair<Int, Int>>(), summaryLines(favourites))
        assertEquals("Added 12 songs to Favourites", plural("import_done_to_favourites").getValue("other").format("12"))
        assertEquals("Added 1 song to Favourites", plural("import_done_to_favourites").getValue("one").format("1"))
        // With playlists, the title counts them and a line the favourites.
        assertEquals(SummaryTitle(R.plurals.import_done, 2), summaryTitle(both))
        assertEquals(listOf(R.string.import_done_created to 2, R.string.import_done_favourites to 12), summaryLines(both))
        assertEquals(SummaryTitle(R.string.import_nothing_new), summaryTitle(favourites.copy(favouritesAdded = 0, undo = ImportUndo())))
        assertEquals(SummaryTitle(R.string.import_failed), summaryTitle(favourites.copy(complete = false)))
        assertEquals(listOf(R.string.import_done_favourites to 12), summaryLines(favourites.copy(complete = false)))
    }

    @Test
    fun `the Samsung Music hint says its favourites come in too`() {
        val hint = strings().getValue("import_where_samsung")

        assertTrue(hint, hint.startsWith("Samsung Music: Settings, Manage playlists, Export"))
        assertTrue(hint, hint.contains("favourites", ignoreCase = true))
    }

    // The pick and its read (review panel-im3)

    @Test
    fun `closing the sheet while it reads stops the read and lets go of the pick, so a remade activity reads nothing`() = runTest {
        val picked = mutableStateOf(emptyList<String>())
        val sheet = mutableStateOf<ImportSheet?>(null)
        var stopped = false
        val reads = ImportReads(backgroundScope, picked, sheet) {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }

        reads.load(MIX)
        runCurrent()
        assertEquals(ImportSheet.Reading, sheet.value)
        assertEquals(MIX, picked.value)
        reads.close()
        runCurrent()

        assertTrue(stopped)
        assertNull(sheet.value)
        assertEquals(emptyList<String>(), picked.value)
        reads.resume()
        assertNull(sheet.value)
    }

    @Test
    fun `a read that ends after a newer pick, or after the sheet closed, never shows its sheet`() = runTest {
        val picked = mutableStateOf(emptyList<String>())
        val sheet = mutableStateOf<ImportSheet?>(null)
        // Reads that end even when stopped, as a read on another thread may.
        val waiting = HashMap<List<String>, Continuation<ImportSheet>>()
        val reads = ImportReads(backgroundScope, picked, sheet) { what -> suspendCoroutine { waiting[what] = it } }
        val mix = ImportSheet.Failed(emptyList(), none = true)
        val evening = ImportSheet.Failed(emptyList(), none = false)

        reads.load(MIX)
        runCurrent()
        reads.load(EVENING)
        runCurrent()
        waiting.getValue(EVENING).resume(evening)
        runCurrent()
        waiting.getValue(MIX).resume(mix)
        runCurrent()
        assertEquals(evening, sheet.value)
        assertEquals(EVENING, picked.value)

        reads.load(MIX)
        runCurrent()
        reads.close()
        waiting.getValue(MIX).resume(mix)
        runCurrent()
        assertNull(sheet.value)
        assertEquals(emptyList<String>(), picked.value)
    }

    @Test
    fun `labels are three words or fewer, the copy says your phone, and no text has an em-dash`() {
        val labels = listOf(
            "import_row", "import_title", "import_pick_file", "import_pick_folder", "import_phone_title", "import_phone_button", "import_where_title", "import_reading",
            "import_found_file", "import_found_files", "import_found_folder", "import_found_phone", "import_kind_playlists", "import_kind_favourites", "import_kind_plays",
            "import_now", "import_cancel", "import_not_recognised", "import_too_big", "import_unreadable", "import_empty", "import_none", "import_close",
            "import_done_other", "import_nothing_new", "import_done_created", "import_done_songs", "import_done_favourites", "import_done_plays",
            "import_copy", "import_copied", "import_undo", "import_failed", "import_undone", "import_kind_queues", "import_done_queues", "import_queue_name", "import_not_undone",
        )
        labels.forEach { assertTrue(it, strings().getValue(it).split(' ').size <= 3) }
        val text = FILE.readText()
        assertFalse(text.contains('\u2014'))
        assertFalse(text.contains("device", ignoreCase = true))
        assertFalse(Regex("Android|Galaxy|Samsung phone").containsMatchIn(text))
    }

    private fun strings(): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(FILE.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private fun plural(name: String): Map<String, String> {
        val body = Regex("""<plurals name="$name">([\s\S]*?)</plurals>""").find(FILE.readText())?.groupValues?.get(1).orEmpty()
        return Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(body).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private companion object {
        /** Unit tests run in the module's folder. */
        val FILE = File("src/main/res/values/strings_import.xml")

        /** Picks as the sheet keeps them: the kind, then the documents. */
        val MIX = listOf("files", "content://com.android.externalstorage.documents/document/primary%3AMusic%2FMix.m3u8")
        val EVENING = listOf("files", "content://com.android.externalstorage.documents/document/primary%3AMusic%2FEvening.m3u8")
    }
}
