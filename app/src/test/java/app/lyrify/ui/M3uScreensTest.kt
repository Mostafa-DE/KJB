package app.lyrify.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.lyrify.R
import app.lyrify.backup.M3uEntry
import app.lyrify.backup.M3uFound
import app.lyrify.backup.M3uImport
import app.lyrify.backup.M3uRead
import app.lyrify.library.SongResolver
import app.lyrify.library.song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The import sheet: what each picked file found, and its words. Made-up names only. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, StandardTestDispatcher
class M3uScreensTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", durationMs = 201_000)
    private val ferry = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", durationMs = 240_000)
    private val resolver = SongResolver(listOf(lantern, ferry))

    @Test
    fun `a file's result is named after it and holds the songs found and the ones missing`() {
        val gone = M3uEntry("/storage/emulated/0/Music/Gone/03 Salt Road.mp3", "Mira Solen - Salt Road", 199_000)
        val read = M3uRead.Entries("Road Trip.m3u8", folder = null, entries = listOf(M3uEntry(lantern.path), gone, M3uEntry("Night Ferry/01 Low Water.mp3")))

        assertEquals(
            ImportResult.Found(DOC, M3uImport("Road Trip", found = listOf(M3uFound(lantern, byName = false), M3uFound(ferry, byName = false)), missing = listOf(gone))),
            importResult(DOC, read, resolver, untitled = "Imported playlist"),
        )
    }

    @Test
    fun `relative paths are read against the file's folder`() {
        val read = M3uRead.Entries("Mix.m3u", folder = "/storage/emulated/0/Music/Harbour Glass", entries = listOf(M3uEntry("01 Paper Lantern.mp3")))

        assertEquals(ImportResult.Found(DOC, M3uImport("Mix", listOf(M3uFound(lantern, byName = false)), emptyList())), importResult(DOC, read, resolver, "Imported playlist"))
    }

    @Test
    fun `a file with no songs, too big, unreadable or not an m3u says why, under its name or a stand-in`() {
        assertEquals(ImportResult.Failed(DOC, "Road Trip", R.string.m3u_no_songs), importResult(DOC, M3uRead.Entries("Road Trip.m3u", null, emptyList()), resolver, "Imported playlist"))
        assertEquals(ImportResult.Failed(DOC, "Road Trip", R.string.m3u_too_big), importResult(DOC, M3uRead.TooBig("Road Trip.m3u8"), resolver, "Imported playlist"))
        assertEquals(ImportResult.Failed(DOC, "Imported playlist", R.string.m3u_unreadable), importResult(DOC, M3uRead.Unreadable(null), resolver, "Imported playlist"))
        assertEquals(ImportResult.Failed(DOC, "Notes.txt", R.string.m3u_not_m3u), importResult(DOC, M3uRead.NotM3u("Notes.txt"), resolver, "Imported playlist"))
        assertEquals("Imported playlist", importResult(DOC, M3uRead.Entries(".m3u8", null, listOf(M3uEntry(lantern.path))), resolver, "Imported playlist").name)
    }

    @Test
    fun `Samsung Music's favourites file goes to Import from app, never to a playlist of its name, and the rest of the pick stays here`() {
        val favourites = DOC to M3uRead.Entries("!#SamsungMusic_favorites#!.m3u", null, listOf(M3uEntry(lantern.path)))
        val mix = OTHER to M3uRead.Entries("Mix.m3u8", null, listOf(M3uEntry(ferry.path)))

        assertEquals(listOf(DOC), forImportSheet(listOf(mix, favourites)))
        assertEquals(listOf(DOC), forImportSheet(listOf(DOC to M3uRead.TooBig("!#samsungmusic_FAVORITES#!.M3U8"))))
        // Every other pick stays here, a near name too.
        assertEquals(emptyList<String>(), forImportSheet(listOf(mix, DOC to M3uRead.Entries("!#SamsungMusic_favorites#! (1).m3u", null, emptyList()), DOC to M3uRead.Unreadable(null))))
    }

    // The favourites file handed from this page's pick to the import sheet's (review panel-im3): held by one side at least,
    // in what the activity saves, until the user closes the import sheet.

    @Test
    fun `the page lets go of the favourites file only once the import sheet holds it, and closing the sheet while it reads lets go of it`() = runTest {
        val saved = Saved(pending = listOf(OTHER, DOC))
        val handoff = ImportHandoff()
        val sheet = mutableStateOf<ImportSheet?>(null)
        var stopped = false
        val reads = ImportReads(backgroundScope, saved.picked, sheet) {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        backgroundScope.launch { handoff.receive(reads) }

        backgroundScope.launch { handOver(listOf(DOC), saved.pending, handoff) }
        runCurrent()
        assertFalse("held by neither side", saved.dropped)
        assertEquals(listOf(OTHER), saved.pending.value)
        assertTrue(DOC in saved.picked.value)
        assertEquals(ImportSheet.Reading, sheet.value)

        // The user closes the sheet while it reads: the file is theirs to let go, and no read of it comes back.
        saved.held = false
        reads.close()
        runCurrent()
        assertTrue(stopped)
        assertNull(sheet.value)
        assertEquals(emptyList<String>(), saved.picked.value)
    }

    @Test
    fun `a turn of the phone or the process ending at any step of the hand-off never loses the favourites file`() = runTest {
        for (sheetHoldsIt in listOf(false, true)) for (processEnds in listOf(false, true)) {
            val saved = Saved(pending = listOf(DOC))
            val handoff = ImportHandoff()
            // The page's steps run on their own, so the activity can end after the sheet holds the file and before the page hears so.
            val pageSteps = TestCoroutineScheduler()
            val page = CoroutineScope(StandardTestDispatcher(pageSteps))
            val shell = CoroutineScope(StandardTestDispatcher(testScheduler))
            page.launch { handOver(listOf(DOC), saved.pending, handoff) }
            pageSteps.runCurrent()
            if (sheetHoldsIt) {
                val reads = ImportReads(shell, saved.picked, mutableStateOf(null)) { awaitCancellation() }
                shell.launch { handoff.receive(reads) }
                runCurrent()
                assertTrue(DOC in saved.picked.value)
            }
            page.cancel()
            shell.cancel()

            // Made again from what the activity saved; after the process ended, with a new hand-off.
            val again = if (processEnds) ImportHandoff() else handoff
            val sheet = mutableStateOf<ImportSheet?>(null)
            val read = ImportSheet.Failed(emptyList(), none = true)
            val reads = ImportReads(backgroundScope, saved.picked, sheet) { read }
            reads.resume()
            backgroundScope.launch { again.receive(reads) }
            // The page reads its picked files again and hands the favourites file on.
            if (DOC in saved.pending.value) backgroundScope.launch { handOver(listOf(DOC), saved.pending, again) }
            runCurrent()

            val case = "sheet holds it: $sheetHoldsIt, process ends: $processEnds"
            assertFalse("$case, held by neither side", saved.dropped)
            assertEquals(case, emptyList<String>(), saved.pending.value)
            assertTrue(case, DOC in saved.picked.value)
            assertEquals(case, read, sheet.value)
        }
    }

    @Test
    fun `the summary reads 18 of 20 songs found, and 1 of 1 song found`() {
        val found = plural("m3u_found")

        assertEquals("18 of 20 songs found", found.getValue("other").format("18", "20"))
        assertEquals("1 of 1 song found", found.getValue("one").format("1", "1"))
        assertEquals("And 7 more", strings().getValue("m3u_more").format("7"))
        assertEquals("3 matched by name", plural("m3u_by_name").getValue("other").format("3"))
        assertEquals("1 matched by name", plural("m3u_by_name").getValue("one").format("1"))
    }

    @Test
    fun `labels are three words or fewer, and no text has an em-dash`() {
        val labels = listOf("m3u_export", "m3u_export_all", "m3u_import", "m3u_create", "m3u_skip", "m3u_cancel", "m3u_close", "m3u_missing", "m3u_by_name_include")
        labels.forEach { assertTrue(it, strings().getValue(it).split(' ').size <= 3) }
        assertFalse(FILE.readText().contains('—'))
    }

    /**
     * What the activity saves of both sides, all a turn of the phone or the process ending leaves: this page's picked
     * files not answered yet, and the import sheet's pick. While [held], a write that leaves [DOC] in neither is [dropped].
     */
    private class Saved(pending: List<String>) {
        var held: Boolean = true
        var dropped: Boolean = false
        val pending: MutableState<List<String>> = Checked(pending)
        val picked: MutableState<List<String>> = Checked(emptyList())

        private inner class Checked(initial: List<String>) : MutableState<List<String>> {
            override var value: List<String> = initial
                set(value) {
                    field = value
                    if (held && DOC !in pending.value && DOC !in picked.value) dropped = true
                }

            override fun component1(): List<String> = value

            override fun component2(): (List<String>) -> Unit = { value = it }
        }
    }

    private fun strings(): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(FILE.readText()).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    private fun plural(name: String): Map<String, String> {
        val body = Regex("""<plurals name="$name">([\s\S]*?)</plurals>""").find(FILE.readText())?.groupValues?.get(1).orEmpty()
        return Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(body).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    private fun unescape(text: String): String = text.replace("\\'", "'")

    private companion object {
        /** Unit tests run in the module's folder. */
        val FILE = File("src/main/res/values/strings_backup.xml")

        /** A picked file, as the sheet keeps it. */
        const val DOC: String = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FRoad%20Trip.m3u8"
        const val OTHER: String = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FMix.m3u8"
    }
}
