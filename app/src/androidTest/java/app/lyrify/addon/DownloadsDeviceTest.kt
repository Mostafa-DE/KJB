package app.lyrify.addon

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.Lyrify
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.IDlAddon
import app.lyrify.downloads.DlAddon
import app.lyrify.downloads.DlConnection
import app.lyrify.downloads.DlLookupEvents
import app.lyrify.downloads.DlSession
import app.lyrify.downloads.DownloadFiles
import app.lyrify.downloads.DownloadFinish
import app.lyrify.downloads.DownloadItem
import app.lyrify.downloads.DownloadOutcome
import app.lyrify.downloads.DownloadRunner
import app.lyrify.downloads.DownloadState
import app.lyrify.downloads.FinishSteps
import app.lyrify.downloads.PhoneSteps
import app.lyrify.dupes.AudioFile
import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * KJB dev and the Downloads add-on (app.lyrify.dl.debug, signed with the same debug key) on a phone, with the network: the
 * bind with its checks and handshake, a lookup, one real short download streamed into KJB dev's test folder (Music/KJB
 * test/, the debug build's fence), tagged by KJB's tag engine and scanned, and a Cancel that leaves no part behind. Needs
 * both installed and All files access for KJB dev (each download test is skipped without it). The clip is Wikimedia
 * Commons' public-domain sample "Example.ogg", a few seconds long. The lyrics step is recorded, not run.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun connect(): DlSession = runBlocking {
        when (val connection = DlAddon.connect(context)) {
            is DlConnection.Open -> connection.session
            is DlConnection.Closed -> throw AssertionError("the add-on is ${connection.state}: install app.lyrify.dl.debug, signed with the debug key")
        }
    }

    /** KJB's own steps, the lyrics recorded instead of looked up. */
    private class Steps(private val phone: PhoneSteps) : FinishSteps by phone {
        var lyrics: LocalTrack? = null

        override suspend fun lyrics(track: LocalTrack) {
            lyrics = track
        }
    }

    private fun item(key: String) = DownloadItem(key, SAMPLE, DlKind.AUDIO, AudioQuality.MP3_192, null, true, "Paper Lantern", "The Quiet Hours", null, 0, System.currentTimeMillis(), DownloadState.Queued)

    private fun finish(steps: FinishSteps) = DownloadFinish(
        DownloadFiles(Environment.getExternalStorageDirectory(), File(context.filesDir, "download-covers"), Lyrify.writes, Environment::isExternalStorageManager),
        steps,
    )

    @Test
    fun bindsTheAddonAfterItsChecksAndSpeaksItsApi() {
        assertEquals(null, DlAddon.peek(context))
        connect().use { session ->
            assertEquals(IDlAddon.VERSION, session.port.apiVersion())
            checkNotNull(session.port.status())
        }
    }

    @Test
    fun looksALinkUp() {
        val full = CompletableDeferred<DlInfo>()
        val failed = CompletableDeferred<DlEnd.Failed>()
        connect().use { session ->
            session.port.info(
                SAMPLE,
                object : DlLookupEvents {
                    override fun onInfo(info: DlInfo) {
                        if (!info.partial) full.complete(info)
                    }

                    override fun onFailed(end: DlEnd.Failed) {
                        failed.complete(end)
                    }
                },
            )
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (!full.isCompleted && !failed.isCompleted) delay(100)
                }
            }
            assertTrue("$failed", full.isCompleted)
        }
    }

    @Test
    fun downloadsOneClipIntoTheTestFolderTaggedAndScanned() {
        assumeTrue("All files access for KJB dev", Environment.isExternalStorageManager())
        val steps = Steps(PhoneSteps(context))
        connect().use { session ->
            val outcome = runBlocking { withTimeout(TIMEOUT_MS) { DownloadRunner(session.port, session.death, finish(steps)).run(item("devtest${System.nanoTime()}")) {} } }

            assertTrue("$outcome", outcome is DownloadOutcome.Saved)
            val saved = File((outcome as DownloadOutcome.Saved).path)
            try {
                assertTrue(saved.path, saved.isFile && "/Music/KJB test/" in saved.path)
                assertTrue(saved.name, saved.name.endsWith(".mp3"))
                assertFalse(File(saved.parentFile, ".${saved.name}.part").exists())
                val scanned: AudioFile? = steps.library()?.firstOrNull { it.path == saved.path }
                assertTrue("the library lists it at once", scanned != null)
                assertEquals(saved.path, steps.lyrics?.path)
            } finally {
                saved.delete()
            }
        }
    }

    @Test
    fun cancelStopsTheDownloadAndLeavesNoPart() {
        assumeTrue("All files access for KJB dev", Environment.isExternalStorageManager())
        val folder = File(Environment.getExternalStorageDirectory(), "Music/KJB test/Music/KJB Downloads")
        val before = folder.listFiles()?.map { it.name }.orEmpty().toSet()
        connect().use { session ->
            runBlocking {
                val run = async { DownloadRunner(session.port, session.death, finish(Steps(PhoneSteps(context)))).run(item("devcancel${System.nanoTime()}")) {} }
                delay(CANCEL_AFTER_MS)
                run.cancel()
                run.join()
            }
        }
        val parts = folder.listFiles()?.map { it.name }.orEmpty().filter { it !in before && it.endsWith(".part") }
        assertEquals(emptyList<String>(), parts)
    }

    private companion object {
        const val SAMPLE = "https://upload.wikimedia.org/wikipedia/commons/c/c8/Example.ogg"
        const val TIMEOUT_MS = 180_000L
        const val CANCEL_AFTER_MS = 1_500L
    }
}
