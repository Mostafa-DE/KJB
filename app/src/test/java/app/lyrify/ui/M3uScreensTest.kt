package app.lyrify.ui

import app.lyrify.R
import app.lyrify.backup.M3uEntry
import app.lyrify.backup.M3uFound
import app.lyrify.backup.M3uImport
import app.lyrify.backup.M3uRead
import app.lyrify.library.SongResolver
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The import sheet: what each picked file found, and its words. Made-up names only. */
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

    @Test
    fun `the Playlists tab's Import hands those files to the one import sheet, which reads them as Pick file does`() {
        val m3u = File("src/main/java/app/lyrify/ui/M3uScreens.kt").readText()
        val import = File("src/main/java/app/lyrify/ui/ImportScreens.kt").readText()

        assertTrue(m3u.contains("ImportHandoff.files.send(handed.toList())"))
        assertTrue(m3u.contains("val read = picked.filter { it.first !in handed }"))
        assertTrue(import.contains("for (documents in ImportHandoff.files) load(listOf(PICKED_FILES) + documents)"))
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
