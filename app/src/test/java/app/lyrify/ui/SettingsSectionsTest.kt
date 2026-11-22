package app.lyrify.ui

import app.lyrify.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings' sections on the screen: one each, in their order, and the one link into a section. */
class SettingsSectionsTest {
    private val screen = File("src/main/java/app/lyrify/ui/SettingsScreen.kt").readText()

    @Test
    fun `Settings shows every section once, in their order, so a link starts at its place in the list`() {
        val shown = Regex("""\bsection\(SettingsSection\.(\w+)|(downloads|suggestion)Settings\(""").findAll(screen)
            .map { it.groupValues[1].ifEmpty { if (it.groupValues[2] == "downloads") SettingsSection.DOWNLOADS.name else SettingsSection.SUGGESTIONS.name } }.toList()
        assertEquals(SettingsSection.entries.map { it.name }, shown)
    }

    @Test
    fun `Can't read your songs opens Settings at Lyrics, where All files access is, closed at first`() {
        val library = File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText()
        assertTrue(library.contains("nav.open(Page.Settings(SettingsSection.LYRICS))"))
        val lyrics = screen.substring(screen.indexOf("section(SettingsSection.LYRICS"), screen.indexOf("section(SettingsSection.AI"))
        assertTrue(lyrics.contains("R.string.setup_files_title"))
        assertEquals("Turn on All files access in Settings.", strings("strings_library.xml").getValue("lib_unreadable"))
    }

    @Test
    fun `Settings asked for again at its section while on top opens that section again and goes to it`() {
        assertTrue(screen.contains("val opened = LocalNavigator.current.opened"))
        val again = screen.substring(screen.indexOf("val opened = LocalNavigator.current.opened"))
        assertTrue(again.contains("if (section != null && opened.page == Page.Settings(section))"))
        assertTrue(again.contains("shownHere = shownHere + (section to true)"))
        assertTrue(again.contains("list.scrollToItem(section.ordinal)"))
    }

    @Test
    fun `closing Downloads leaves a yt-dlp update running, and one at a time`() {
        val downloads = File("src/main/java/app/lyrify/ui/DownloadsScreen.kt").readText()
        val item = downloads.substring(downloads.indexOf("internal fun LazyListScope.downloadsSettings("), downloads.indexOf("private enum class DownloadsSheet"))
        val rows = downloads.substring(downloads.indexOf("private fun DownloadsSettings("), downloads.indexOf("/** A Settings row that opens a sheet"))
        assertTrue(item.contains("rememberCoroutineScope()") && item.contains("var updating by remember"))
        assertFalse(rows.contains("rememberCoroutineScope()") || rows.contains("var updating by remember"))
    }

    @Test
    fun `a section's title tells TalkBack whether it is expanded or collapsed`() {
        val words = strings("strings_settings.xml")
        assertEquals(listOf("Expanded", "Collapsed", "Expand", "Collapse"), listOf("set_section_expanded", "set_section_collapsed", "set_section_expand", "set_section_collapse").map { words[it] })
    }

    private fun strings(file: String): Map<String, String> =
        Regex("""<string name="(\w+)">([^<]*)</string>""").findAll(File("src/main/res/values/$file").readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
}
