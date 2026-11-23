package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.download
import app.lyrify.backup.BackupFixtures.downloads
import app.lyrify.backup.BackupFixtures.entries
import app.lyrify.backup.BackupFixtures.newLantern
import app.lyrify.backup.BackupFixtures.newLibrary
import app.lyrify.backup.BackupFixtures.snapshot
import app.lyrify.backup.BackupFixtures.zip
import app.lyrify.backup.BackupFixtures.zipOf
import app.lyrify.downloads.DownloadFailure
import app.lyrify.downloads.DownloadSettings
import app.lyrify.downloads.DownloadState
import app.lyrify.downloads.DownloadsJson
import app.lyrify.downloads.DownloadsMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Downloads in backups (BK1, user 2026-11-23: a backup holds every single thing): Settings > Downloads and the Downloads
 * page's list, saved and not saved, in a section of their own; never a download waiting or under way. On a new phone the
 * settings replace the phone's, as Settings do, and each download joins the list once, a saved song on its file there.
 * Made-up names only.
 */
class DownloadsBackupTest {
    private val newId = { "new" }

    /** A phone with its own downloads: one waiting, one saved long ago, and Settings > Downloads as they came. */
    private fun phone() = MemoryStores(
        downloads = DownloadsMemory(DownloadSettings(), listOf(download("d9", T0 + 5, DownloadState.Queued), download("d8", T0, DownloadState.Failed(DownloadFailure.BLOCKED)))),
    )

    @Test
    fun `Settings, Downloads and the downloads saved and not saved go into the backup and come back, never one waiting`() {
        val read = contents(zip())

        assertEquals(downloads.settings, read.downloads?.settings)
        assertEquals(listOf("d2", "d1"), read.downloads?.items?.map { it.key })
        assertEquals(downloads.items.drop(1), read.downloads?.items)
    }

    @Test
    fun `a download waiting or under way in a backup is never read, so a restore starts none`() {
        // A backup made by hand with the whole list: what waits would start downloading on this phone.
        val bytes = zipOf(
            entries(zip()).map { (name, text) ->
                name to (if (name == Section.DOWNLOADS.file) DownloadsJson.encode(downloads.copy(items = downloads.items + download("d4", T0 + 4, DownloadState.Running))) else text).encodeToByteArray()
            },
        )

        assertEquals(listOf("d2", "d1"), contents(bytes).downloads?.items?.map { it.key })
    }

    @Test
    fun `on a new phone the settings replace its own, each download joins the list once, the newest first, and a saved song is on its file there`() {
        val phone = phone()
        val plan = RestorePlan(contents(), newLibrary)

        val result = runBlocking { plan.apply(setOf(Section.DOWNLOADS), phone, newId) }

        assertEquals(listOf(Section.DOWNLOADS), result.restored)
        assertEquals(2, result.counts[Section.DOWNLOADS])
        assertEquals(0, result.notFound)
        assertEquals(downloads.settings, phone.downloads.settings)
        assertEquals(listOf("d9", "d2", "d1", "d8"), phone.downloads.items.map { it.key })
        assertEquals(DownloadState.Done(newLantern.path, T0 + 10), phone.downloads.items.single { it.key == "d1" }.state)
        // Restored again: nothing more.
        runBlocking { plan.apply(setOf(Section.DOWNLOADS), phone, newId) }
        assertEquals(listOf("d9", "d2", "d1", "d8"), phone.downloads.items.map { it.key })
    }

    @Test
    fun `downloads that cannot be written leave the phone's as they were, and the summary says so`() {
        val phone = phone().apply { failing += Section.DOWNLOADS }
        val before = phone.downloads

        val result = runBlocking { RestorePlan(contents(), newLibrary).apply(setOf(Section.DOWNLOADS), phone, newId) }

        assertEquals(listOf(Section.DOWNLOADS), result.failed)
        assertEquals(before, phone.downloads)
    }

    @Test
    fun `a backup from before Downloads were kept has no such section, and the phone's downloads stay as they are`() {
        val phone = phone()
        val before = phone.downloads
        val older = contents(zip(snapshot.copy(downloads = null)))

        assertNull(older.downloads)
        runBlocking { RestorePlan(older, newLibrary).apply(Section.entries.toSet(), phone, newId) }
        assertEquals(before, phone.downloads)
    }
}
