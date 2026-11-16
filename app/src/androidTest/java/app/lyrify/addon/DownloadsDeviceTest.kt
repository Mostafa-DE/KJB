package app.lyrify.addon

import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.Lyrify
import app.lyrify.data.SharedWrites
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
import app.lyrify.downloads.DownloadFolders
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
import org.junit.After
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
 * Commons' public-domain sample "Example.ogg", a few seconds long. The lyrics step is recorded, not run. The lookup and the
 * download each check, from `dumpsys activity services`, that the add-on works in its own foreground service (dataSync)
 * while it has the link, that KJB binds it with BIND_IMPORTANT, and that it leaves the foreground after (AD9).
 */
@RunWith(AndroidJUnit4::class)
class DownloadsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** KJB dev's test folder, the debug build's fence, and where it saves a download with the default settings. */
    private val testFolder = File(Environment.getExternalStorageDirectory(), SharedWrites.TEST_FOLDER)
    private val downloads = File(testFolder, DownloadFolders.AUDIO)

    /** What [downloads] held as the test began, and the folders on the way to it, the test folder's own, missing then. */
    private val before = downloads.listFiles()?.map { it.name }.orEmpty().toSet()
    private val missing = generateSequence(downloads) { it.parentFile }.takeWhile { it.startsWith(testFolder) }.filterNot(File::exists).toList()

    /**
     * What a test's download made goes, also when the test fails midway: the files new in [downloads] (the test's run is
     * all that saves there meanwhile: its song, or a part a stopped run left), then the folders it made, deepest first,
     * each only once empty.
     */
    @After
    fun cleanUp() {
        downloads.listFiles()?.filter { it.name !in before }?.forEach(File::delete)
        missing.forEach(File::delete)
    }

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
            val lookup = session.port.info(
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
            // As the save sheet does: the add-on's own foreground service once it took the link.
            assertTrue("the phone let KJB start the add-on's foreground service", session.foreground())
            val during = AddonServices.once(KjbAddons.DOWNLOADS, foreground = true)
            Log.i(TAG, "the add-on during the lookup $lookup: ${AddonServices.summary(during)}")
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (!full.isCompleted && !failed.isCompleted) delay(100)
                }
            }
            assertTrue("$failed", full.isCompleted)
            assertWorkedInTheForeground(during)
        }
    }

    /** [during]: the add-on's service while it had the link; it is out of the foreground once it has none. */
    private fun assertWorkedInTheForeground(during: String) {
        assertTrue(during, "isForeground=true" in during && "types=${AddonServices.DATA_SYNC}" in during)
        assertTrue("bound with BIND_AUTO_CREATE and BIND_IMPORTANT: $during", "CR IMP " in during)
        val after = AddonServices.once(KjbAddons.DOWNLOADS, foreground = false)
        assertFalse("out of the foreground once the work ended: $after", "isForeground=true" in after)
    }

    @Test
    fun downloadsOneClipIntoTheTestFolderTaggedAndScanned() {
        assumeTrue("All files access for KJB dev", Environment.isExternalStorageManager())
        val steps = Steps(PhoneSteps(context))
        connect().use { session ->
            // The add-on's service as the download's first progress comes, once KJB started it for the foreground.
            var during: String? = null
            val runner = DownloadRunner(session.port, session.death, finish(steps), foreground = session::foreground)
            val outcome = runBlocking {
                withTimeout(TIMEOUT_MS) {
                    runner.run(item("devtest${System.nanoTime()}")) { if (during == null) during = AddonServices.once(KjbAddons.DOWNLOADS, foreground = true) }
                }
            }
            Log.i(TAG, "the add-on during the download: ${during?.let(AddonServices::summary)}")
            assertWorkedInTheForeground(checkNotNull(during) { "no progress came: $outcome" })

            assertTrue("$outcome", outcome is DownloadOutcome.Saved)
            val saved = File((outcome as DownloadOutcome.Saved).path)
            assertTrue(saved.path, saved.isFile && "/Music/KJB test/" in saved.path)
            assertTrue(saved.name, saved.name.endsWith(".mp3"))
            assertFalse(File(saved.parentFile, ".${saved.name}.part").exists())
            val scanned: AudioFile? = steps.library()?.firstOrNull { it.path == saved.path }
            assertTrue("the library lists it at once", scanned != null)
            assertEquals(saved.path, steps.lyrics?.path)
        }
    }

    @Test
    fun cancelStopsTheDownloadAndLeavesNoPart() {
        assumeTrue("All files access for KJB dev", Environment.isExternalStorageManager())
        connect().use { session ->
            runBlocking {
                val run = async { DownloadRunner(session.port, session.death, finish(Steps(PhoneSteps(context)))).run(item("devcancel${System.nanoTime()}")) {} }
                delay(CANCEL_AFTER_MS)
                run.cancel()
                run.join()
            }
        }
        val parts = downloads.listFiles()?.map { it.name }.orEmpty().filter { it !in before && it.endsWith(".part") }
        assertEquals(emptyList<String>(), parts)
    }

    private companion object {
        const val TAG = "DownloadsDeviceTest"
        const val SAMPLE = "https://upload.wikimedia.org/wikipedia/commons/c/c8/Example.ogg"
        const val TIMEOUT_MS = 180_000L
        const val CANCEL_AFTER_MS = 1_500L
    }
}
