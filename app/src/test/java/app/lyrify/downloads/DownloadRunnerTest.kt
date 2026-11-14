package app.lyrify.downloads

import app.lyrify.addon.AddonDiedException
import app.lyrify.data.SharedWrites
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlMeta
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStage
import app.lyrify.dl.api.DlStatus
import app.lyrify.dl.api.DlUpdate
import app.lyrify.dupes.AudioFile
import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * One download from KJB's side: the run in the add-on followed with a watchdog, the file streamed into the part file KJB
 * opened where it goes, then KJB's own steps (tags, the final name, the scan, Duplicates, lyrics), against fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRunnerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val item = DownloadItem(
        key = "d1",
        url = "https://video.example.com/watch?v=abc",
        kind = DlKind.AUDIO,
        audio = AudioQuality.MP3_320,
        height = null,
        originalLanguage = true,
        title = "Paper Lantern",
        artist = "The Quiet Hours",
        thumbnail = null,
        durationSec = 214,
        addedAt = 1,
        state = DownloadState.Queued,
    )
    private val file = DlFile("The Quiet Hours - Paper Lantern.mp3", 1_000, 50, DlMeta(title = "Paper Lantern", artist = "The Quiet Hours", album = "Paper Lantern"), 214)

    // The fakes

    private class FakePort : DlPort {
        val calls = mutableListOf<String>()
        var events: DlRunEvents? = null
        var delivered: Pair<File, File?>? = null

        override fun apiVersion(): Int = 1

        override fun status(): DlStatus = DlStatus("2026.11.01", 1)

        override fun info(url: String, events: DlLookupEvents): Long = 1

        override fun cancelInfo(lookup: Long) = Unit

        override fun start(request: DlRequest, events: DlRunEvents): Long {
            calls += "start ${request.key}"
            this.events = events
            return 7
        }

        override fun deliver(run: Long, media: File, cover: File?) {
            calls += "deliver $run"
            delivered = media to cover
        }

        override fun cancel(run: Long, keepParts: Boolean) {
            calls += "cancel $run keep=$keepParts"
        }

        override fun discard(key: String) {
            calls += "discard $key"
        }

        override fun update(): DlUpdate = DlUpdate(DlUpdate.Outcome.CURRENT)
    }

    private class FakeSaver(private val root: File) : DownloadSaver {
        val calls = mutableListOf<String>()
        var refuse: DownloadFailure? = null

        override fun open(item: DownloadItem, file: DlFile): Opened {
            calls += "open"
            refuse?.let { return Opened.Refused(it) }
            return Opened.Ready(Target(File(root, ".x.part"), File(root, "cover.jpg"), File(root, "x.mp3")))
        }

        override suspend fun finish(item: DownloadItem, target: Target, file: DlFile): DownloadOutcome {
            calls += "finish"
            return DownloadOutcome.Saved(target.final.path, null)
        }

        override fun abandon(target: Target) {
            calls += "abandon"
        }
    }

    private val port = FakePort()
    private val death = CompletableDeferred<Unit>()
    private val saver by lazy { FakeSaver(temp.root) }
    private val heard = mutableListOf<DlProgress>()

    private fun TestScope.running() = backgroundScope.async { DownloadRunner(port, death, saver).run(item, heard::add) }.also { runCurrent() }

    @Test
    fun `a download goes through - progress, the file opened where it goes, streamed whole, then KJB's own steps`() = runTest {
        val outcome = running()
        val events = port.events!!

        events.onProgress(DlProgress(DlStage.DOWNLOADING, 40f))
        events.onFile(file)
        runCurrent()
        assertEquals(listOf("start d1", "deliver 7"), port.calls)
        assertEquals(File(temp.root, ".x.part") to File(temp.root, "cover.jpg"), port.delivered)
        events.onEnd(DlEnd.Delivered(1_000, 50))
        runCurrent()

        assertEquals(DownloadOutcome.Saved(File(temp.root, "x.mp3").path, null), outcome.await())
        assertEquals(listOf("open", "finish"), saver.calls)
        assertEquals(listOf(DlProgress(DlStage.DOWNLOADING, 40f)), heard)
    }

    @Test
    fun `a hand-off that came short deletes the part and fails`() = runTest {
        val outcome = running()
        port.events!!.onFile(file)
        port.events!!.onEnd(DlEnd.Delivered(600, 50))
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.HAND_OFF), outcome.await())
        assertEquals(listOf("open", "abandon"), saver.calls)
    }

    @Test
    fun `a full disk while streaming deletes the part and says so`() = runTest {
        val outcome = running()
        port.events!!.onFile(file)
        port.events!!.onEnd(DlEnd.Failed(DlFailure.NO_ROOM))
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.NO_ROOM), outcome.await())
        assertEquals(listOf("open", "abandon"), saver.calls)
    }

    @Test
    fun `the add-on's failure before any file carries its kind`() = runTest {
        val outcome = running()
        port.events!!.onEnd(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = true))
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.SITE_CHANGED), outcome.await())
        assertTrue(saver.calls.isEmpty())
    }

    @Test
    fun `Cancel during the hand-off tells the add-on, keeps its parts, and deletes the part file`() = runTest {
        val outcome = running()
        port.events!!.onFile(file)
        runCurrent()

        outcome.cancel()
        runCurrent()

        assertEquals("cancel 7 keep=true", port.calls.last())
        assertEquals(listOf("open", "abandon"), saver.calls)
        assertTrue(outcome.isCancelled)
    }

    @Test
    fun `a run that says nothing for longer than any step is stopped and fails as stalled`() = runTest {
        val outcome = running()
        port.events!!.onProgress(DlProgress(DlStage.CONVERTING))
        advanceTimeBy(DownloadRunner.STALL_MS - 1_000)
        port.events!!.onProgress(DlProgress(DlStage.TAGGING))
        advanceTimeBy(DownloadRunner.STALL_MS - 1_000)
        runCurrent()
        assertFalse("each word from the add-on starts the watch again", outcome.isCompleted)

        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.STALLED), outcome.await())
        assertEquals("cancel 7 keep=true", port.calls.last())
    }

    @Test
    fun `the add-on dying mid-run fails cleanly and deletes the part`() = runTest {
        val outcome = running()
        port.events!!.onFile(file)
        runCurrent()

        death.complete(Unit)
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.ADDON_GONE), outcome.await())
        assertEquals(listOf("open", "abandon"), saver.calls)
    }

    @Test
    fun `a place KJB may not save to stops the run, the add-on's parts kept for a Retry`() = runTest {
        saver.refuse = DownloadFailure.NOT_ALLOWED
        val outcome = running()
        port.events!!.onFile(file)
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.NOT_ALLOWED), outcome.await())
        assertEquals("cancel 7 keep=true", port.calls.last())
        assertFalse(port.calls.any { it.startsWith("deliver") })
    }

    @Test
    fun `an add-on gone before the start is a clean failure`() = runTest {
        val dead = object : DlPort by port {
            override fun start(request: DlRequest, events: DlRunEvents): Long = throw AddonDiedException()
        }

        val outcome = backgroundScope.async { DownloadRunner(dead, death, saver).run(item) {} }
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.ADDON_GONE), outcome.await())
    }

    // KJB's own steps once the file is whole

    private class Steps : FinishSteps {
        val calls = mutableListOf<String>()
        var tagged: Map<String, List<String>>? = null
        var tags = true
        var library: List<AudioFile>? = emptyList()
        var lyrics: LocalTrack? = null

        override suspend fun tag(part: File, fields: Map<String, List<String>>, cover: File?): Boolean {
            calls += "tag cover=${cover != null}"
            tagged = fields
            return tags
        }

        override suspend fun scan(saved: File, mime: String) {
            calls += "scan $mime"
        }

        override fun library(): List<AudioFile>? {
            calls += "library"
            return library
        }

        override suspend fun lyrics(track: LocalTrack) {
            calls += "lyrics"
            lyrics = track
        }
    }

    private fun files(writes: SharedWrites = SharedWrites.ANYWHERE, allowed: Boolean = true) =
        DownloadFiles(temp.root.resolve("storage"), File(temp.root, "private"), writes, canWrite = { allowed })

    private fun saved(path: String, title: String, seconds: Int, id: Long) = AudioFile(id, path, "Music/KJB Downloads/", File(path).name, title, "The Quiet Hours", null, seconds * 1000L, 1_000)

    @Test
    fun `the part opens hidden in its folder, under a free name, with the cover kept privately`() {
        val files = files()

        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target

        val folder = temp.root.resolve("storage/Music/KJB Downloads")
        assertEquals(File(folder, ".The Quiet Hours - Paper Lantern.mp3.part"), target.part)
        assertEquals(File(folder, "The Quiet Hours - Paper Lantern.mp3"), target.final)
        assertTrue(target.part.isFile)
        assertTrue(target.cover!!.path.startsWith(temp.root.resolve("private").path))
        assertNull((files.open(item, file.copy(coverBytes = null), DownloadSettings()) as Opened.Ready).target.cover)
    }

    @Test
    fun `without All files access, or outside what this build may change, nothing is opened`() {
        assertEquals(Opened.Refused(DownloadFailure.NOT_ALLOWED), files(allowed = false).open(item, file, DownloadSettings()))
        val fenced = SharedWrites(testFolderOnly = true, root = temp.root.toPath())
        assertEquals(Opened.Refused(DownloadFailure.NOT_ALLOWED), files(fenced).open(item, file, DownloadSettings()))
        assertFalse(temp.root.resolve("storage/Music").exists())
    }

    @Test
    fun `a song is tagged with yt-dlp's tags and its cover, named, scanned, checked for a copy, and its lyrics looked up`() = runTest {
        val files = files()
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(1_000))
        target.cover!!.writeBytes(ByteArray(50))
        val steps = Steps()
        steps.library = listOf(saved(target.final.path, "Paper Lantern", 214, 9), saved("/storage/emulated/0/Music/old.mp3", "Paper Lantern", 213, 3))

        val outcome = DownloadFinish(files, steps).finish(item, target, file)

        assertEquals(DownloadOutcome.Saved(target.final.path, "/storage/emulated/0/Music/old.mp3"), outcome)
        assertEquals(listOf("tag cover=true", "scan audio/mpeg", "library", "lyrics"), steps.calls)
        assertEquals(listOf("Paper Lantern"), steps.tagged!!["TITLE"])
        assertEquals(LocalTrack(target.final.path, "Paper Lantern", "The Quiet Hours", "Paper Lantern", 214_000), steps.lyrics)
        assertTrue(target.final.isFile)
        assertFalse(target.part.exists())
        assertFalse(target.cover.exists())
    }

    @Test
    fun `a video is only named and scanned, never tagged or looked up for lyrics`() = runTest {
        val video = item.copy(kind = DlKind.VIDEO, height = 720)
        val videoFile = DlFile("Paper Lantern.mp4", 2_000)
        val files = files()
        val target = (files.open(video, videoFile, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(2_000))
        val steps = Steps()

        val outcome = DownloadFinish(files, steps).finish(video, target, videoFile)

        assertEquals(DownloadOutcome.Saved(temp.root.resolve("storage/Movies/KJB Downloads/Paper Lantern.mp4").path, null), outcome)
        assertEquals(listOf("scan video/mp4"), steps.calls)
    }

    @Test
    fun `a part that is not the size the add-on said is deleted and never published`() = runTest {
        val files = files()
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(999))
        val steps = Steps()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.HAND_OFF), DownloadFinish(files, steps).finish(item, target, file))
        assertFalse(target.part.exists())
        assertFalse(target.final.exists())
        assertTrue(steps.calls.isEmpty())
    }

    @Test
    fun `tags that cannot be written keep yt-dlp's, and the song is still saved`() = runTest {
        val files = files()
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(1_000))
        val steps = Steps().apply { tags = false; library = null }

        val outcome = DownloadFinish(files, steps).finish(item, target, file)

        assertEquals(DownloadOutcome.Saved(target.final.path, null), outcome)
        assertTrue(target.final.isFile)
        // No library to read: no copy to name, and the lyrics go by the length the add-on gave.
        assertEquals(214_000L, steps.lyrics!!.durationMs)
    }

    @Test
    fun `a name taken while it downloaded gets the next free one`() = runTest {
        val files = files()
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(1_000))
        target.final.writeText("another app saved this meanwhile")

        val outcome = DownloadFinish(files, Steps()).finish(item, target, file) as DownloadOutcome.Saved

        assertEquals("The Quiet Hours - Paper Lantern (2).mp3", File(outcome.path).name)
    }

    @Test
    fun `abandon deletes the part and the cover`() {
        val files = files()
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.cover!!.writeBytes(ByteArray(5))

        files.abandon(target)

        assertFalse(target.part.exists())
        assertFalse(target.cover.exists())
    }
}
