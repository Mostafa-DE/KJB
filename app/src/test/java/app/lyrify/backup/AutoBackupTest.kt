package app.lyrify.backup

import app.lyrify.settings.BackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Automatic backup: weekly while plugged in, the last five kept, other files never touched. A made-up clock and battery. */
class AutoBackupTest {
    private val on = BackupSettings(automatic = true, folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups")
    private val today: LocalDate = LocalDate.of(2026, 11, 10)

    private fun file(name: String) = FolderFile("id:$name", name)

    private fun backup(daysAgo: Long, copy: Int? = null) = file("KJB backup ${today.minusDays(daysAgo)}${copy?.let { " ($it)" } ?: ""}.zip")

    @Test
    fun `a backup's day is read from its name, any other file has none`() {
        assertEquals(today, AutoBackups.dateOf("KJB backup 2026-11-10.zip"))
        assertEquals(today, AutoBackups.dateOf("KJB backup 2026-11-10 (1).zip"))
        assertNull(AutoBackups.dateOf("KJB backup 2026-13-40.zip"))
        assertNull(AutoBackups.dateOf("Lyrify backup 2026-11-10.zip"))
        assertNull(AutoBackups.dateOf("KJB backup 2026-11-10.zip.txt"))
        assertNull(AutoBackups.dateOf("notes.zip"))
    }

    @Test
    fun `off, or without a folder, nothing runs`() {
        assertEquals(AutoStep.OFF, AutoBackups.step(BackupSettings(), plugged = true, today, emptyList()))
        assertEquals(AutoStep.OFF, AutoBackups.step(on.copy(folderUri = null), plugged = true, today, emptyList()))
        assertEquals(AutoStep.OFF, AutoBackups.step(on.copy(automatic = false), plugged = true, today, emptyList()))
    }

    @Test
    fun `a folder it cannot reach writes nothing`() {
        assertEquals(AutoStep.FOLDER_GONE, AutoBackups.step(on, plugged = true, today, inFolder = null))
    }

    @Test
    fun `it waits for the phone to be plugged in`() {
        assertEquals(AutoStep.WAIT_FOR_POWER, AutoBackups.step(on, plugged = false, today, emptyList()))
        assertEquals(AutoStep.BACK_UP, AutoBackups.step(on, plugged = true, today, emptyList()))
    }

    @Test
    fun `it backs up once a week, counting backups made by hand in the folder too`() {
        assertEquals(AutoStep.NOT_DUE, AutoBackups.step(on, plugged = true, today, listOf(backup(6), file("notes.txt"))))
        assertEquals(AutoStep.NOT_DUE, AutoBackups.step(on, plugged = true, today, listOf(backup(0))))
        assertEquals(AutoStep.BACK_UP, AutoBackups.step(on, plugged = true, today, listOf(backup(7))))
        assertEquals(AutoStep.BACK_UP, AutoBackups.step(on, plugged = true, today, listOf(backup(30), backup(9))))
        assertEquals(AutoStep.BACK_UP, AutoBackups.step(on, plugged = true, today, listOf(file("notes.zip"))))
    }

    @Test
    fun `a backup dated after today, from a clock that was wrong, does not stop the next one`() {
        assertEquals(AutoStep.BACK_UP, AutoBackups.step(on, plugged = true, today, listOf(backup(-40), backup(8))))
    }

    @Test
    fun `the five newest backups are kept, and only backups are ever deleted`() {
        val files = listOf(backup(0), backup(7), backup(14), file("notes.zip"), backup(21), backup(28), backup(35), file("KJB playlist.m3u8"), backup(42))

        assertEquals(listOf(backup(35), backup(42)), AutoBackups.toDelete(files))
        assertEquals(emptyList<FolderFile>(), AutoBackups.toDelete(files.take(5)))
    }

    @Test
    fun `of two backups on one day, the provider's numbered copy is the newer`() {
        val files = listOf(backup(7), backup(7, copy = 1), backup(14), backup(21), backup(28), backup(35))

        assertEquals(listOf(backup(35)), AutoBackups.toDelete(files))
        assertEquals(listOf(backup(7)), AutoBackups.toDelete(listOf(backup(7), backup(7, copy = 1), backup(3), backup(2), backup(1), backup(0))))
    }
}
