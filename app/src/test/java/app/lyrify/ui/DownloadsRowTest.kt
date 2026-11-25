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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `the page asks to keep KJB open only while downloads run and Android refuses the add-on's foreground service`() {
        val running = item("a", DownloadState.Running)
        val waiting = item("b", DownloadState.Queued)
        val saved = item("c", DownloadState.Done("/x.mp3", 1))

        assertTrue(DownloadsUi(items = listOf(running, saved), backgroundLimit = true).keepOpen)
        assertTrue(DownloadsUi(items = listOf(waiting), backgroundLimit = true).keepOpen)
        assertFalse("nothing left to download", DownloadsUi(items = listOf(saved), backgroundLimit = true).keepOpen)
        assertFalse("the add-on works in its own foreground service", DownloadsUi(items = listOf(running), backgroundLimit = false).keepOpen)
    }

    @Test
    fun `a failure's words follow the add-on's kind and whether it really updated the downloader`() {
        fun sentence(failure: DlFailure, updated: Boolean) = failureSentence(DownloadFailure.of(DlEnd.Failed(failure, updated)), vpn = false)
        fun label(failure: DlFailure, updated: Boolean) = DownloadNotices.failureLabel(DownloadFailure.of(DlEnd.Failed(failure, updated)))

        // A VPN's address refused: the same words, updated or not.
        assertEquals(R.string.dl_failed_blocked_detail, sentence(DlFailure.BLOCKED, updated = false))
        assertEquals(R.string.dl_failed_blocked_detail, sentence(DlFailure.BLOCKED, updated = true))
        assertEquals(R.string.dl_failed_blocked, label(DlFailure.BLOCKED, updated = false))
        // An age-restricted video: never the VPN's words, which would send the user to turn off a VPN they do not have.
        assertEquals(R.string.dl_failed_age_detail, sentence(DlFailure.AGE_RESTRICTED, updated = false))
        assertEquals(R.string.dl_failed_age, label(DlFailure.AGE_RESTRICTED, updated = false))
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

    @Test
    fun `a search that failed says why in dl1's words where they fit, else to try again or update the downloader`() {
        assertEquals(R.string.dl_failed_offline_detail, searchFailureText(DlFailure.OFFLINE, vpn = false))
        assertEquals(R.string.dl_failed_blocked_detail, searchFailureText(DlFailure.BLOCKED, vpn = false))
        // A search makes no update of its own, so "KJB updated the downloader" or "already the newest" would not be true.
        DlFailure.entries.filter { it != DlFailure.OFFLINE && it != DlFailure.BLOCKED }.forEach {
            assertEquals(it.name, R.string.dl_failed_unknown_detail, searchFailureText(it, vpn = false))
        }
    }

    @Test
    fun `with a VPN on, a failed search, lookup or download says to turn it off, but not for what no VPN changes`() {
        // The owner's VPN made YouTube refuse a search and an album (user feedback 81, 82): the words said neither.
        val vpnMayCause = listOf(
            DownloadFailure.OFFLINE, DownloadFailure.NOT_FOUND, DownloadFailure.SITE_CHANGED, DownloadFailure.SITE_CHANGED_NEWEST,
            DownloadFailure.BLOCKED, DownloadFailure.STALLED, DownloadFailure.UNKNOWN,
        )
        vpnMayCause.forEach { assertEquals(it.name, R.string.dl_failed_vpn_detail, failureSentence(it, vpn = true)) }
        (DownloadFailure.entries - vpnMayCause.toSet()).forEach { assertEquals(it.name, failureSentence(it, vpn = false), failureSentence(it, vpn = true)) }
        DownloadFailure.entries.forEach { assertTrue(it.name, failureSentence(it, vpn = false) != R.string.dl_failed_vpn_detail) }

        // A refused search reads as an unknown failure, as everywhere in KJB (DownloadFailure.of).
        listOf(DlFailure.OFFLINE, DlFailure.NOT_FOUND, DlFailure.SITE_CHANGED, DlFailure.BLOCKED, DlFailure.REFUSED, DlFailure.UNKNOWN)
            .forEach { assertEquals(it.name, R.string.dl_failed_vpn_detail, searchFailureText(it, vpn = true)) }
        // An add-on that died or a yt-dlp that never started: no VPN does that (the second review of this lane).
        listOf(DlFailure.ENGINE, DlFailure.AGE_RESTRICTED, DlFailure.NO_ROOM, DlFailure.HAND_OFF, DlFailure.LOCAL_ADDRESS)
            .forEach { assertEquals(it.name, searchFailureText(it, vpn = false), searchFailureText(it, vpn = true)) }
    }

    @Test
    fun `the search fields always have a name TalkBack reads, also once their words fill them`() {
        // No Compose renderer in these tests (ThemeTokensTest), so it is read from the source. The placeholder shows only
        // while a field is empty, and the search sheet opens with the words in it: the label is what names it. The page's
        // one box for a link or words (4.1) takes its label as a String; the sheet's field is labelled.
        val screen = File("src/main/java/app/lyrify/ui/DownloadsScreen.kt").readText()
        assertTrue(Regex("""internal fun LinkOrSearchField\((?:\n[^\n]*){0,5}\n\s+label: String,""").containsMatchIn(screen))
        val box = screen.substringAfter("internal fun LinkOrSearchField(").substringBefore("\n}\n")
        assertTrue("the box hands its label to its field", Regex("""LyrifyField\((?:\n[^\n]*){0,5}\n\s+label = label,""").containsMatchIn(box))
        assertTrue(Regex("""value = words,\n[^\n]*\n[^\n]*\n\s+label = stringResource\(R\.string\.dl_search_hint\),""").containsMatchIn(screen))
    }
}
