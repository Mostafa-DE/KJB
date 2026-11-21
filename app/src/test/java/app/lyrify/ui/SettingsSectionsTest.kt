package app.lyrify.ui

import app.lyrify.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings' sections on the screen: one each, in their order, and the one link into a section. */
class SettingsSectionsTest {
    private val screen = File("src/main/java/app/lyrify/ui/SettingsScreen.kt").readText()

    @Test
    fun `Settings shows every section once, in their order, so a link starts at its place in the list`() {
        val shown = Regex("""\bsection\(SettingsSection\.(\w+)|downloadsSettings\(""").findAll(screen).map { it.groupValues[1].ifEmpty { SettingsSection.DOWNLOADS.name } }.toList()
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
    fun `a section's title tells TalkBack whether it is expanded or collapsed`() {
        val words = strings("strings_settings.xml")
        assertEquals(listOf("Expanded", "Collapsed", "Expand", "Collapse"), listOf("set_section_expanded", "set_section_collapsed", "set_section_expand", "set_section_collapse").map { words[it] })
    }

    private fun strings(file: String): Map<String, String> =
        Regex("""<string name="(\w+)">([^<]*)</string>""").findAll(File("src/main/res/values/$file").readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
}
