package app.lyrify.ui

import app.lyrify.backup.BackupFixtures
import app.lyrify.backup.RestoreResult
import app.lyrify.backup.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings > Backup and the restore summary: the rows, what each counts, and their words. Made-up names only. */
class BackupScreensTest {
    @Test
    fun `Settings, Backup has Back up now, Restore and Automatic backup, each wired to its action`() {
        val settings = File("src/main/java/app/lyrify/ui/SettingsScreen.kt").readText()
        val shell = File("src/main/java/app/lyrify/ui/AppShell.kt").readText()
        val screens = File("src/main/java/app/lyrify/ui/BackupScreens.kt").readText()

        listOf(
            "section(R.string.settings_backup)",
            "R.string.set_backup_now, first = true, onClick = backup.backUpNow",
            "R.string.set_restore, onClick = backup.restore",
            "R.string.set_auto_backup, stringResource(R.string.set_auto_backup_detail), settings.backup.automatic, onSwitch = backup.setAutomatic",
        ).forEach { assertTrue(it, settings.contains(it)) }
        assertTrue(shell.contains("rememberBackupActions {"))
        assertEquals("Back up now", setting("set_backup_now"))
        assertEquals("Restore", setting("set_restore"))
        assertEquals("Automatic backup", setting("set_auto_backup"))
        // The stub is gone: the save dialog (or a folder), the open dialog and the folder for automatic backup.
        assertFalse(screens.contains("Milestone B2 replaces this body"))
        listOf("ActivityResultContracts.CreateDocument(", "ActivityResultContracts.OpenDocument(", "ActivityResultContracts.OpenDocumentTree(").forEach { assertTrue(it, screens.contains(it)) }
    }

    @Test
    fun `the summary lists each section the backup holds with what it counts`() {
        assertEquals(
            listOf(
                RestoreRow(Section.PLAYLISTS, 2, 4),
                RestoreRow(Section.QUEUES, 3, null),
                RestoreRow(Section.PLAYS, 3, null),
                RestoreRow(Section.BOOKMARKS, 2, 1),
                RestoreRow(Section.EQUALIZER, 2, 1),
                RestoreRow(Section.LYRICS, 3, null),
                RestoreRow(Section.SETTINGS, null, null),
            ),
            restoreRows(BackupFixtures.contents()),
        )
    }

    @Test
    fun `a section skipped or not in the backup has no row`() {
        val contents = BackupFixtures.contents().copy(plays = null, tooNew = listOf(Section.PLAYS), settings = null)

        assertEquals(Section.entries - Section.PLAYS - Section.SETTINGS, restoreRows(contents).map { it.section })
    }

    @Test
    fun `labels are three words or fewer, the unreadable line is the spec's, and no text has an em-dash`() {
        val labels = listOf(
            "backup_saved", "backup_not_saved", "backup_folder_refused", "restore_title", "restore_button", "restore_cancel",
            "restore_close", "restore_done", "restore_partly", "restore_missing_title",
        ) + Section.entries.map { "restore_section_${it.key}" }
        labels.forEach { assertTrue(it, strings()[it]?.let { label -> label.split(' ').size <= 3 } == true) }
        // The spec's own words, a short sentence.
        assertEquals("Couldn't read this backup", strings()["backup_unreadable"])
        assertEquals("Lyrics choices", strings()["restore_section_lyrics"])
        assertTrue(strings()["restore_undo"]?.contains("Undo history") == true)
        assertFalse(FILE.readText().contains('\u2014'))
        assertFalse(File("src/main/java/app/lyrify/ui/BackupScreens.kt").readText().contains('\u2014'))
    }

    @Test
    fun `a restore that left places or bookmarks out, or failed a part, says so in a sheet, the limit named`() {
        assertTrue(complete(RestoreResult(listOf(Section.PLAYS), emptyList())))
        assertFalse(complete(RestoreResult(listOf(Section.PLAYS), listOf(Section.QUEUES))))
        assertFalse(complete(RestoreResult(listOf(Section.BOOKMARKS), emptyList(), placesLeftOut = 3)))
        assertFalse(complete(RestoreResult(listOf(Section.BOOKMARKS), emptyList(), bookmarksLeftOut = 1)))
        assertEquals("3 places not restored, the limit is reached.", plural("restore_places_full")["other"]?.format("3"))
        assertEquals("1 place not restored, the limit is reached.", plural("restore_places_full")["one"]?.format("1"))
        assertEquals("3 bookmarks not restored, the limit is reached.", plural("restore_bookmarks_full")["other"]?.format("3"))
        assertEquals("1 bookmark not restored, the limit is reached.", plural("restore_bookmarks_full")["one"]?.format("1"))
    }

    private fun plural(name: String): Map<String, String> {
        val body = Regex("""<plurals name="$name">([\s\S]*?)</plurals>""").find(FILE.readText())?.groupValues?.get(1).orEmpty()
        return Regex("""<item quantity="(\w+)">([^<]*)</item>""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `every section has its label`() {
        Section.entries.forEach { assertTrue(it.key, strings().containsKey("restore_section_${it.key}")) }
        Section.entries.forEach { sectionLabel(it) }
    }

    private fun strings(): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(FILE.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private fun setting(name: String): String? =
        Regex("""<string name="$name">([^<]*)</string>""").find(File("src/main/res/values/strings_settings.xml").readText())?.groupValues?.get(1)

    private companion object {
        /** Unit tests run in the module's folder. */
        val FILE = File("src/main/res/values/strings_backup.xml")
    }
}
