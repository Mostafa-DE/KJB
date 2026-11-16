package app.lyrify.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.lyrify.R
import app.lyrify.backup.ImportBundle
import app.lyrify.backup.ImportFormats
import app.lyrify.backup.ImportKind
import app.lyrify.backup.ImportOutcome
import app.lyrify.backup.ImportPick
import app.lyrify.backup.ImportPlan
import app.lyrify.backup.ImportStores
import app.lyrify.backup.ImportUndo
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.song
import app.lyrify.player.QueueBook
import app.lyrify.stats.LoggedPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Playlists tab's Import: every picked file goes to Import from app's sheet, one preview for the pick, and the
 * hand-off between the two never loses a file. Made-up names only.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent, StandardTestDispatcher
class M3uScreensTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", durationMs = 201_000, id = 11)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", durationMs = 185_000, id = 12)
    private val ferry = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", durationMs = 240_000, id = 13)
    private val phone = listOf(lantern, bell, ferry)

    // Every file to Import from app's sheet

    @Test
    fun `a file picked in the Playlists tab's Import goes to Import from app's sheet, its playlist and the songs not found in one preview`() = runTest {
        val preview = pickInPlaylists(ROAD_TRIP)

        assertEquals(ImportSource.FILE, preview.source)
        assertEquals(listOf(ImportRow(ImportKind.PLAYLISTS, 1)), importRows(preview.plan))
        assertEquals(setOf(ImportKind.PLAYLISTS), preview.chosen)
        assertEquals(listOf("Road Trip"), preview.plan.bundle.playlists.map { it.name })
        assertEquals(listOf("Odo Brannick - Glass Steps"), preview.plan.missing(preview.chosen).map { it.label })
        assertEquals(emptyList<Any>(), preview.failed)
    }

    @Test
    fun `a playlist of the file's name gets its new songs at its end, each once, never a second playlist named Road Trip (2)`() = runTest {
        val mine = Playlist("mine", "Road Trip", listOf(lantern.ref), 1)
        val stores = Stores(PlaylistsState(listOf(mine)))

        val outcome = importNow(pickInPlaylists(ROAD_TRIP), stores)

        assertEquals(listOf(mine.copy(songs = listOf(lantern.ref, ferry.ref))), stores.state.playlists)
        assertEquals(SummaryTitle(R.plurals.import_done, 1), summaryTitle(outcome))
        assertEquals(listOf(R.string.import_done_songs to 1), summaryLines(outcome))
    }

    @Test
    fun `picking the same file again is Nothing new and changes nothing`() = runTest {
        val stores = Stores(PlaylistsState())
        importNow(pickInPlaylists(ROAD_TRIP), stores)
        val once = stores.state

        val again = importNow(pickInPlaylists(ROAD_TRIP), stores)

        assertFalse(again.changed)
        assertEquals(SummaryTitle(R.string.import_nothing_new), summaryTitle(again))
        assertEquals(once, stores.state)
        // The playlist the first import made, in the file's order, a song twice too.
        assertEquals(listOf(listOf(lantern.ref, ferry.ref, ferry.ref)), stores.state.playlists.map { it.songs })
    }

    @Test
    fun `Undo takes back what the Playlists tab's import added, and nothing of the user's`() = runTest {
        val before = PlaylistsState(listOf(Playlist("mine", "Road Trip", listOf(bell.ref), 1), Playlist("eve", "Evening", listOf(ferry.ref), 2)))
        val stores = Stores(before)
        val outcome = importNow(pickInPlaylists(ROAD_TRIP), stores)
        assertEquals(listOf(bell.ref, lantern.ref, ferry.ref), stores.state.playlists.first().songs)

        assertTrue(ImportPlan.undo(outcome.undo, stores))

        assertEquals(before, stores.state)
    }

    @Test
    fun `Samsung Music's favourites file picked with a playlist file goes to Favourites, the other to a playlist, in one preview`() = runTest {
        val stores = Stores(PlaylistsState(favouriteSongs = listOf(ferry.ref)))

        val preview = pickInPlaylists(ROAD_TRIP, FAVOURITES)
        val outcome = importNow(preview, stores)

        assertEquals(ImportSource.FILES, preview.source)
        assertEquals(listOf(ImportRow(ImportKind.PLAYLISTS, 1), ImportRow(ImportKind.FAVOURITES, 2)), importRows(preview.plan))
        assertEquals(listOf("Road Trip"), stores.state.playlists.map { it.name })
        assertEquals(listOf(ferry.ref, bell.ref, lantern.ref), stores.state.favouriteSongs)
        assertEquals(2, outcome.favouritesAdded)
    }

    // The picked files handed from this page's pick to the import sheet's (review panel-im3): held by one side at least,
    // in what the activity saves, until the user closes the import sheet.

    @Test
    fun `the page lets go of a picked file only once the import sheet holds it, and closing the sheet while it reads lets go of it`() = runTest {
        val saved = Saved()
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

        backgroundScope.handPicked(listOf(DOC, DOC), saved.pending, handoff)
        runCurrent()
        assertFalse("held by neither side", saved.dropped)
        assertEquals(emptyList<String>(), saved.pending.value)
        assertEquals("the sheet's pick: its kind, then each file once", listOf(DOC), saved.picked.value.drop(1))
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
    fun `a turn of the phone or the process ending at any step of the hand-off never loses a picked file`() = runTest {
        for (sheetHoldsIt in listOf(false, true)) for (processEnds in listOf(false, true)) {
            val saved = Saved()
            val handoff = ImportHandoff()
            // The page's steps run on their own, so the activity can end after the sheet holds the file and before the page hears so.
            val pageSteps = TestCoroutineScheduler()
            val page = CoroutineScope(StandardTestDispatcher(pageSteps))
            val shell = CoroutineScope(StandardTestDispatcher(testScheduler))
            page.handPicked(listOf(DOC), saved.pending, handoff)
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
            // The page hands the picked files it kept on again.
            if (saved.pending.value.isNotEmpty()) backgroundScope.launch { handOver(saved.pending.value, saved.pending, again) }
            runCurrent()

            val case = "sheet holds it: $sheetHoldsIt, process ends: $processEnds"
            assertFalse("$case, held by neither side", saved.dropped)
            assertEquals(case, emptyList<String>(), saved.pending.value)
            assertTrue(case, DOC in saved.picked.value)
            assertEquals(case, read, sheet.value)
        }
    }

    @Test
    fun `labels are three words or fewer, and no text has an em-dash`() {
        listOf("m3u_export", "m3u_export_all", "m3u_import").forEach { assertTrue(it, strings().getValue(it).split(' ').size <= 3) }
        assertFalse(FILE.readText().contains('—'))
    }

    /**
     * The Playlists tab's "⋯ > Import" answered with [picked], and Import from app's sheet there to take them, as the
     * shell has it: the preview the sheet shows once it has read them through [Reader].
     */
    private fun TestScope.pickInPlaylists(vararg picked: String): ImportSheet.Preview {
        val handoff = ImportHandoff()
        val pending = mutableStateOf(emptyList<String>())
        val sheet = mutableStateOf<ImportSheet?>(null)
        val reads = ImportReads(backgroundScope, mutableStateOf(emptyList()), sheet) { what -> readPick(what, Reader()) }
        backgroundScope.launch { handoff.receive(reads) }

        backgroundScope.handPicked(picked.toList(), pending, handoff)
        runCurrent()

        val shown = sheet.value
        assertTrue("Import from app's sheet shows the pick's preview, not $shown", shown is ImportSheet.Preview)
        assertEquals("the page let go of the pick", emptyList<String>(), pending.value)
        return shown as ImportSheet.Preview
    }

    /** Import now on [preview], into [stores], as the sheet's Import now writes it. */
    private suspend fun importNow(preview: ImportSheet.Preview, stores: Stores): ImportOutcome {
        var next = 0
        return checkNotNull(preview.plan.apply(preview.chosen, stores, newId = { "p${++next}" }, now = NOW, untitled = UNTITLED))
    }

    /** Import from app's reading of a pick, the picked documents' text from [FILES], the library [phone]. */
    private inner class Reader : PickReader {
        override suspend fun files(documents: List<String>): ImportPick = ImportFormats.combine(
            documents.map { document -> FILES.getValue(document).let { (name, folder, text) -> ImportFormats.read(text.encodeToByteArray(), name, folder) } },
        )

        override suspend fun folder(tree: String): ImportPick? = null

        override suspend fun phone(): ImportPick = ImportPick(ImportBundle(), 0, emptyList())

        override suspend fun plan(bundle: ImportBundle): ImportPlan = ImportPlan(bundle, phone)
    }

    /** The playlists' store in memory, as an import writes it; these imports hold no queues or plays. */
    private class Stores(var state: PlaylistsState) : ImportStores {
        private var journal: ImportUndo? = null

        override suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState): Boolean {
            state = change(state)
            return true
        }

        override suspend fun updateQueues(change: (QueueBook) -> QueueBook): Boolean = true

        override suspend fun plays(): List<LoggedPlay> = emptyList()

        override suspend fun addPlays(plays: List<LoggedPlay>): Boolean = true

        override suspend fun removePlays(plays: List<LoggedPlay>): Boolean = true

        override suspend fun journal(undo: ImportUndo?): Boolean {
            journal = undo
            return true
        }

        override suspend fun journaled(): ImportUndo? = journal
    }

    /**
     * What the activity saves of both sides, all a turn of the phone or the process ending leaves: this page's picked
     * files not handed yet, and the import sheet's pick. While [held], a write that leaves [DOC] in neither once it was
     * in one is [dropped].
     */
    private class Saved {
        var held: Boolean = true
        var dropped: Boolean = false
        private var seen: Boolean = false
        val pending: MutableState<List<String>> = Checked()
        val picked: MutableState<List<String>> = Checked()

        private inner class Checked : MutableState<List<String>> {
            override var value: List<String> = emptyList()
                set(value) {
                    field = value
                    val kept = DOC in pending.value || DOC in picked.value
                    if (held && seen && !kept) dropped = true
                    seen = seen || kept
                }

            override fun component1(): List<String> = value

            override fun component2(): (List<String>) -> Unit = { value = it }
        }
    }

    private fun strings(): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(FILE.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private companion object {
        /** Unit tests run in the module's folder. */
        val FILE = File("src/main/res/values/strings_backup.xml")

        const val NOW: Long = 1_800_000_000_000L
        const val UNTITLED: String = "Imported playlist"
        const val MUSIC: String = "/storage/emulated/0/Music"

        /** A picked file, as the page and the sheet keep it. */
        const val DOC: String = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FMix.m3u8"
        const val ROAD_TRIP: String = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FPlaylists%2FRoad%20Trip.m3u8"
        const val FAVOURITES: String = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F!%23SamsungMusic_favorites%23!.m3u"

        /**
         * Each picked document's name, folder and text. Road Trip: two songs of the phone by paths relative to its
         * folder, one of them twice, and one song not on the phone. Samsung Music's favourites: two songs.
         */
        val FILES: Map<String, Triple<String, String, String>> = mapOf(
            ROAD_TRIP to Triple(
                "Road Trip.m3u8",
                "$MUSIC/Playlists",
                "#EXTM3U\n#EXTINF:201,Kit Calloway - Paper Lantern\n../Harbour Glass/01 Paper Lantern.mp3\n" +
                    "#EXTINF:240,Mira Solen - Low Water\n../Night Ferry/01 Low Water.mp3\n../Night Ferry/01 Low Water.mp3\n" +
                    "#EXTINF:175,Odo Brannick - Glass Steps\n../Gone/03 Glass Steps.mp3\n",
            ),
            FAVOURITES to Triple("!#SamsungMusic_favorites#!.m3u", MUSIC, "#EXTM3U\nHarbour Glass/02 Tide Bell.mp3\nHarbour Glass/01 Paper Lantern.mp3\n"),
        )
    }
}
