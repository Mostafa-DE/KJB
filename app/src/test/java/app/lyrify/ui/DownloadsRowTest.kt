package app.lyrify.ui

import app.lyrify.R
import app.lyrify.addon.AddonState
import app.lyrify.addon.InstallFailure
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlKind
import app.lyrify.downloads.DownloadFailure
import app.lyrify.downloads.DownloadItem
import app.lyrify.downloads.DownloadNotices
import app.lyrify.downloads.DownloadState
import app.lyrify.downloads.DownloadsUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Downloads' row on Tools: Get Downloads until the add-on is there, then what downloads now (the badge), failed or was
 * saved. And the add-on card on the Downloads page when getting the add-on failed.
 */
class DownloadsRowTest {
    private fun item(key: String, state: DownloadState) =
        DownloadItem(key, "https://video.example.com/$key", DlKind.AUDIO, AudioQuality.MP3_320, null, true, "Paper Lantern", "The Quiet Hours", null, 0, 1, state)

    private fun ui(vararg items: DownloadItem) = DownloadsUi(items = items.toList(), loaded = true)

    @Test
    fun `no add-on, or one that is not KJB's, says Get Downloads`() {
        assertEquals(EntryRow(ToolLine.Text(R.string.dl_get), null), downloadsEntry(AddonState.Missing, ui()))
        assertEquals(ToolLine.Text(R.string.dl_get), downloadsEntry(AddonState.Untrusted, ui(item("a", DownloadState.Queued))).line)
    }

    @Test
    fun `downloads under way are counted, with a badge`() {
        val row = downloadsEntry(AddonState.Ready, ui(item("a", DownloadState.Running), item("b", DownloadState.Queued), item("c", DownloadState.Done("/x.mp3", 1))))

        assertEquals(EntryRow(ToolLine.Count(R.plurals.dl_active, 2), 2), row)
    }

    @Test
    fun `then the ones not saved, then the ones saved, then nothing yet`() {
        assertEquals(ToolLine.Count(R.plurals.dl_failed_count, 1), downloadsEntry(AddonState.Ready, ui(item("a", DownloadState.Failed(DownloadFailure.OFFLINE)), item("b", DownloadState.Done("/x.mp3", 1)))).line)
        assertEquals(ToolLine.Count(R.plurals.dl_saved_count, 1), downloadsEntry(AddonState.Ready, ui(item("b", DownloadState.Done("/x.mp3", 1)))).line)
        val empty = downloadsEntry(AddonState.Ready, ui())
        assertEquals(ToolLine.Text(R.string.dl_nothing_yet), empty.line)
        assertNull(empty.badge)
    }

    @Test
    fun `a failure's words follow the add-on's kind and whether it really updated the downloader`() {
        fun sentence(failure: DlFailure, updated: Boolean) = failureSentence(DownloadFailure.of(DlEnd.Failed(failure, updated)))
        fun label(failure: DlFailure, updated: Boolean) = DownloadNotices.failureLabel(DownloadFailure.of(DlEnd.Failed(failure, updated)))

        // A VPN's address refused: the same words, updated or not.
        assertEquals(R.string.dl_failed_blocked_detail, sentence(DlFailure.BLOCKED, updated = false))
        assertEquals(R.string.dl_failed_blocked_detail, sentence(DlFailure.BLOCKED, updated = true))
        assertEquals(R.string.dl_failed_blocked, label(DlFailure.BLOCKED, updated = false))
        // "Updated the downloader and tried again" only when it was.
        assertEquals(R.string.dl_failed_site_detail, sentence(DlFailure.SITE_CHANGED, updated = true))
        assertEquals(R.string.dl_failed_site_newest_detail, sentence(DlFailure.SITE_CHANGED, updated = false))
        assertEquals(R.string.dl_failed_site, label(DlFailure.SITE_CHANGED, updated = false))
        assertEquals(R.string.dl_failed_site, label(DlFailure.SITE_CHANGED, updated = true))
    }

    @Test
    fun `a KJB with no add-on to download says so, as the AI page does, and the rest still say to try again`() {
        assertEquals(R.string.dl_addon_not_published, addonFailedText(InstallFailure.NOT_PUBLISHED))
        InstallFailure.entries.filter { it != InstallFailure.NOT_PUBLISHED }.forEach { assertEquals(it.name, R.string.dl_addon_failed, addonFailedText(it)) }
    }
}
