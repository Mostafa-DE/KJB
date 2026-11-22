package app.lyrify.downloads

import app.lyrify.addon.AddonDiedException
import app.lyrify.addon.shared.AddonKeepAlive
import app.lyrify.data.SharedWrites
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlMeta
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlSearch
import app.lyrify.dl.api.DlStage
import app.lyrify.dl.api.DlStatus
import app.lyrify.dl.api.DlUpdate
import app.lyrify.dupes.AudioFile
import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    private companion object {
        val TAGS = "TAGS".toByteArray()
    }

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

        override fun search(search: DlSearch, events: DlSearchEvents): Long = error("not a search")

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

    /** Whether the phone lets the add-on's foreground service start; each start is in [FakePort.calls] for its order. */
    private var foregroundAllowed = true

    private fun TestScope.running() = backgroundScope.async {
        DownloadRunner(port, death, saver, foreground = { foregroundAllowed.also { port.calls += "foreground" } }).run(item, heard::add)
    }.also { runCurrent() }

    @Test
    fun `the run goes on in the add-on's own foreground service, which KJB starts once the add-on took it`() = runTest {
        val outcome = running()

        assertEquals(listOf("start d1", "foreground"), port.calls)
        port.events!!.onFile(file)
        runCurrent()
        port.events!!.onEnd(DlEnd.Delivered(1_000, 50))
        assertEquals(DownloadOutcome.Saved(File(temp.root, "x.mp3").path, null), outcome.await())
    }

    @Test
    fun `silent for 30 s once it beats, the add-on is frozen, the run fails as such with nothing asked of it`() = runTest {
        val outcome = running()
        port.events!!.onBeat()
        port.events!!.onProgress(DlProgress(DlStage.DOWNLOADING, 10f))
        runCurrent()

        advanceTimeBy(AddonKeepAlive.FROZEN_MS - 1)
        runCurrent()
        assertFalse("silent for less than 30 s", outcome.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(DownloadOutcome.Failed(DownloadFailure.FROZEN), outcome.await())
        // A call into a frozen process can wait for as long as it stays frozen: none is made.
        assertEquals(listOf("start d1", "foreground"), port.calls)
    }

    @Test
    fun `an end that comes as the freeze watch gives up is the download's end, and its file is kept`() = runTest {
        val outcome = running()
        val events = port.events!!
        events.onFile(file)
        events.onBeat()
        runCurrent()
        // The end comes at the very moment the watch gives up: after its word is in, before the run reads it.
        backgroundScope.launch {
            delay(AddonKeepAlive.FROZEN_MS)
            yield() // behind the watch, which wakes at the same moment
            events.onEnd(DlEnd.Delivered(1_000, 50))
        }

        advanceTimeBy(AddonKeepAlive.FROZEN_MS + 1)
        runCurrent()

        assertEquals(DownloadOutcome.Saved(File(temp.root, "x.mp3").path, null), outcome.await())
        assertEquals("never abandoned", listOf("open", "finish"), saver.calls)
    }

    @Test
    fun `beats keep a quiet step from looking frozen, and are no progress for the stall limit`() = runTest {
        val outcome = running()
        port.events!!.onProgress(DlProgress(DlStage.CONVERTING))
        runCurrent()

        repeat((DownloadRunner.STALL_MS / AddonKeepAlive.BEAT_EVERY_MS).toInt() - 1) {
            advanceTimeBy(AddonKeepAlive.BEAT_EVERY_MS)
            port.events!!.onBeat()
            runCurrent()
        }
        assertFalse(outcome.isCompleted)
        repeat(2) {
            advanceTimeBy(AddonKeepAlive.BEAT_EVERY_MS)
            port.events!!.onBeat()
            runCurrent()
        }

        assertEquals(DownloadOutcome.Failed(DownloadFailure.STALLED), outcome.await())
    }

    @Test
    fun `a run that never beats is not watched for freezes`() = runTest {
        val outcome = running()
        port.events!!.onProgress(DlProgress(DlStage.DOWNLOADING, 10f))
        runCurrent()

        advanceTimeBy(4 * AddonKeepAlive.FROZEN_MS)
        runCurrent()
        assertFalse(outcome.isCompleted)
        port.events!!.onFile(file)
        runCurrent()
        port.events!!.onEnd(DlEnd.Delivered(1_000, 50))

        assertEquals(DownloadOutcome.Saved(File(temp.root, "x.mp3").path, null), outcome.await())
    }

    @Test
    fun `a phone that refuses the add-on's foreground service still downloads`() = runTest {
        foregroundAllowed = false
        val outcome = running()
        port.events!!.onFile(file)
        runCurrent()
        port.events!!.onEnd(DlEnd.Delivered(1_000, 50))

        assertEquals(DownloadOutcome.Saved(File(temp.root, "x.mp3").path, null), outcome.await())
    }

    @Test
    fun `a download goes through - progress, the file opened where it goes, streamed whole, then KJB's own steps`() = runTest {
        val outcome = running()
        val events = port.events!!

        events.onProgress(DlProgress(DlStage.DOWNLOADING, 40f))
        events.onFile(file)
        runCurrent()
        assertEquals(listOf("start d1", "foreground", "deliver 7"), port.calls)
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
    fun `a site that changed with the downloader already the newest, or one that refused the connection, says so`() = runTest {
        val changed = running()
        port.events!!.onEnd(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = false))
        runCurrent()
        assertEquals(DownloadOutcome.Failed(DownloadFailure.SITE_CHANGED_NEWEST), changed.await())

        val refused = running()
        port.events!!.onEnd(DlEnd.Failed(DlFailure.BLOCKED))
        runCurrent()
        assertEquals(DownloadOutcome.Failed(DownloadFailure.BLOCKED), refused.await())
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

        /** Where the tag engine was given the song: KJB's own files, never the shared folder. */
        var taggedAt: File? = null

        override suspend fun tag(song: File, fields: Map<String, List<String>>, cover: File?): Boolean {
            calls += "tag cover=${cover != null}"
            tagged = fields
            taggedAt = song
            if (tags) song.appendBytes(TAGS)
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

    private fun files(
        writes: SharedWrites = SharedWrites.ANYWHERE,
        allowed: Boolean = true,
        copy: (Path, Path) -> Unit = { from, to -> Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING) },
        freeName: (File, String) -> File = DownloadFolders::free,
    ) = DownloadFiles(temp.root.resolve("storage"), File(temp.root, "private"), writes, canWrite = { allowed }, copy = copy, freeName = freeName)

    private val storage: File get() = temp.root.resolve("storage")

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

    /**
     * A site whose yt-dlp extractor gives no thumbnail has no cover to embed (phone session 10: Wikimedia Commons, whose
     * extractor returns none, as of its 2026-06 rework): the song is still tagged and saved, its cover left empty, and
     * KJB's default cover shows for it, as for any song without one.
     */
    @Test
    fun `a song from a site with no thumbnail is tagged and saved without a cover`() = runTest {
        val files = files()
        val bare = file.copy(coverBytes = null)
        val target = (files.open(item, bare, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(1_000))
        val steps = Steps()
        steps.library = listOf(saved(target.final.path, "Paper Lantern", 214, 9))

        val outcome = DownloadFinish(files, steps).finish(item, target, bare)

        assertEquals(DownloadOutcome.Saved(target.final.path, null), outcome)
        assertNull(target.cover)
        assertEquals(listOf("tag cover=false", "scan audio/mpeg", "library", "lyrics"), steps.calls)
        assertEquals(listOf("Paper Lantern"), steps.tagged!!["TITLE"])
        assertTrue(target.final.isFile)
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
    fun `the tag engine works on a private copy, and the tagged song replaces the part whole`() = runTest {
        val files = files()
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(1_000))
        val steps = Steps()

        val outcome = DownloadFinish(files, steps).finish(item, target, file) as DownloadOutcome.Saved

        assertTrue(steps.taggedAt!!.path.startsWith(temp.root.resolve("private").path))
        assertArrayEquals(ByteArray(1_000) + TAGS, File(outcome.path).readBytes())
        assertFalse(steps.taggedAt!!.exists())
    }

    @Test
    fun `a tagged copy that breaks on its way back leaves the original song, which is saved whole`() = runTest {
        // Copying into the shared folder writes half, then the disk fails.
        val breaking: (Path, Path) -> Unit = { from, to ->
            if (to.startsWith(storage.toPath())) {
                Files.write(to, Files.readAllBytes(from).copyOf(300))
                throw IOException("write failed: EIO")
            }
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
        val files = files(copy = breaking)
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        val original = ByteArray(1_000) { (it % 7).toByte() }
        target.part.writeBytes(original)

        val outcome = DownloadFinish(files, Steps()).finish(item, target, file) as DownloadOutcome.Saved

        assertArrayEquals(original, File(outcome.path).readBytes())
        assertEquals(listOf(File(outcome.path).name), target.final.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a cover larger than KJB's covers take is never opened, nor read`() = runTest {
        val files = files()
        val big = file.copy(coverBytes = DownloadFiles.MAX_COVER_BYTES + 1L)
        assertNull((files.open(item, big, DownloadSettings()) as Opened.Ready).target.cover)

        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(1_000))
        target.cover!!.writeBytes(ByteArray(DownloadFiles.MAX_COVER_BYTES + 1))
        val steps = Steps()
        DownloadFinish(files, steps).finish(item, target, file)

        assertEquals("tag cover=false", steps.calls.first())
    }

    @Test
    fun `publishing never replaces a file that took the name after it was found free`() = runTest {
        var asked = 0
        // The first free name is taken by another app right after it was picked.
        val racing: (File, String) -> File = { folder, name ->
            if (asked++ == 0) File(folder, name).also { it.writeText("another app's") } else DownloadFolders.free(folder, name)
        }
        val files = files(freeName = racing)
        val target = (files.open(item, file, DownloadSettings()) as Opened.Ready).target
        target.part.writeBytes(ByteArray(1_000))

        val outcome = DownloadFinish(files, Steps().apply { tags = false }).finish(item, target, file) as DownloadOutcome.Saved

        assertEquals("another app's", target.final.readText())
        assertEquals("The Quiet Hours - Paper Lantern (2).mp3", File(outcome.path).name)
        assertEquals(1_000L, File(outcome.path).length())
    }

    @Test
    fun `a name from the add-on keeps its file in its folder, with an extension KJB knows`() {
        val files = files()
        val folder = storage.resolve("Music/KJB Downloads")

        val sneaky = (files.open(item, file.copy(name = "song.mp3/../../../evil"), DownloadSettings()) as Opened.Ready).target
        val odd = (files.open(item, file.copy(name = "song.exe"), DownloadSettings()) as Opened.Ready).target

        assertEquals(folder.canonicalFile, sneaky.final.canonicalFile.parentFile)
        assertEquals(folder.canonicalFile, sneaky.part.canonicalFile.parentFile)
        assertTrue(sneaky.final.name, sneaky.final.name.endsWith(".mp3"))
        assertEquals("song.mp3", odd.final.name)
    }

    @Test
    fun `the user's Cancel tells the add-on to delete what it got, a pause keeps it`() = runTest {
        var userCancelled = true
        val outcome = backgroundScope.async { DownloadRunner(port, death, saver, keepOnCancel = { !userCancelled }).run(item) {} }
        runCurrent()
        outcome.cancel()
        runCurrent()
        assertEquals("cancel 7 keep=false", port.calls.last())

        userCancelled = false
        val paused = backgroundScope.async { DownloadRunner(port, death, saver, keepOnCancel = { !userCancelled }).run(item) {} }
        runCurrent()
        paused.cancel()
        runCurrent()
        assertEquals("cancel 7 keep=true", port.calls.last())
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
