package app.lyrify.dl

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * The add-on's engine on a phone, with the network: yt-dlp, Python and ffmpeg from the APK, one real short download
 * converted to MP3 and streamed out whole, and a Cancel that leaves no process behind. Runs in the add-on's own process
 * (app.lyrify.dl.debug) through [DlRuns], not the binder: the binder serves KJB only (KJB's DownloadsDeviceTest binds it).
 * The clip is Wikimedia Commons' public-domain sample "Example.ogg" (a few seconds, through yt-dlp's generic extractor).
 * Writes only the add-on's own cache.
 */
@RunWith(AndroidJUnit4::class)
class DlDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folder = File(context.cacheDir, "dl-test")
    private val ytdlp = LibraryYtdlp(context, context.cacheDir, log = {})
    private val runs = DlRuns(
        scope = scope,
        ytdlp = ytdlp,
        folder = folder,
        memory = object : UpdateMemory {
            // A fresh yt-dlp as far as the run knows: the test downloads, it does not update.
            override var lastCheck: Long? = System.currentTimeMillis()
        },
        clock = System::currentTimeMillis,
        monotonic = System::nanoTime,
        quickInfo = { null },
    )

    private class Heard : RunEvents {
        val progress: MutableList<DlProgress> = Collections.synchronizedList(mutableListOf())
        val file = CompletableDeferred<DlFile>()
        val end = CompletableDeferred<DlEnd>()

        override fun onProgress(progress: DlProgress) {
            this.progress += progress
        }

        override fun onFile(file: DlFile) {
            this.file.complete(file)
        }

        override fun onEnd(end: DlEnd) {
            this.end.complete(end)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        folder.deleteRecursively()
    }

    @Test
    fun oneShortClipDownloadsAsMp3AndArrivesWhole() = runBlocking {
        val heard = Heard()
        val run = runs.start(DlRequest("device-test-1", SAMPLE, DlKind.AUDIO, AudioQuality.MP3_192), heard)

        val file = withTimeout(TIMEOUT_MS) { heard.file.await() }
        assertTrue(file.name, file.name.endsWith(".mp3"))
        val out = File(context.cacheDir, "delivered.mp3")
        runs.deliver(run, out.outputStream(), null)
        val end = withTimeout(TIMEOUT_MS) { heard.end.await() }

        assertEquals(DlEnd.Delivered(file.bytes, null), end)
        assertEquals(file.bytes, out.length())
        val head = ByteArray(3).also { bytes -> out.inputStream().use { it.read(bytes) } }
        // An ID3 tag (yt-dlp's tags) or an MPEG frame's sync word.
        assertTrue(String(head, Charsets.ISO_8859_1) == "ID3" || (head[0].toInt() and 0xFF == 0xFF))
        assertTrue("${heard.progress}", heard.progress.any { it.stage == DlStage.CONVERTING })
        out.delete()
    }

    @Test
    fun cancelLeavesNoYtdlpOrFfmpegBehind() = runBlocking {
        val heard = Heard()
        val run = runs.start(DlRequest("device-test-2", SAMPLE, DlKind.AUDIO, AudioQuality.MP3_320), heard)
        // Cancel as soon as yt-dlp says anything.
        withTimeout(TIMEOUT_MS) { while (heard.progress.none { it.stage != DlStage.UPDATING && it.stage != DlStage.STARTING } && !heard.end.isCompleted) delay(50) }

        runs.cancel(run, keepParts = false)
        val end = withTimeout(TIMEOUT_MS) { heard.end.await() }

        assertTrue("$end", end == DlEnd.Stopped || end is DlEnd.Failed)
        val marker = File(folder, "device-test-2").path + "/"
        assertEquals(emptyList<Int>(), ProcTable.matching(File("/proc"), marker, android.os.Process.myPid()))
        assertTrue(!File(folder, "device-test-2").exists() || end is DlEnd.Failed)
    }

    private companion object {
        const val SAMPLE = "https://upload.wikimedia.org/wikipedia/commons/c/c8/Example.ogg"
        const val TIMEOUT_MS = 180_000L
    }
}
