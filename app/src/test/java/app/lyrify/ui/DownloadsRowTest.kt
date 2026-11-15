package app.lyrify.ui

import app.lyrify.R
import app.lyrify.addon.AddonState
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlKind
import app.lyrify.downloads.DownloadFailure
import app.lyrify.downloads.DownloadItem
import app.lyrify.downloads.DownloadState
import app.lyrify.downloads.DownloadsUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Downloads' row on Tools: Get Downloads until the add-on is there, then what downloads now (the badge), failed or was saved. */
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
}
