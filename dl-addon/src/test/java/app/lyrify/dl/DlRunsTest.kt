package app.lyrify.dl

import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStage
import app.lyrify.dl.api.DlUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress

/**
 * A download in the add-on ([DlRuns]) against a fake yt-dlp whose "process" runs until it ends or is killed, as the real
 * one: Cancel, the update policy, Retry from a key's parts, and the hand-off of the file into KJB's descriptors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DlRunsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val day = 24 * 60 * 60_000L

    // The fakes

    /** What one yt-dlp run does. */
    private sealed interface Script {
        /** Writes [files] into its folder (and the tags yt-dlp prints), says [lines], ends well. */
        data class Finish(val files: Map<String, ByteArray>, val lines: List<String> = emptyList(), val meta: String? = null) : Script

        data class Fail(val err: String) : Script

        /** Writes [parts], says [lines], then runs until killed: it never stops by itself. */
        data class Hang(val parts: Map<String, ByteArray> = emptyMap(), val lines: List<String> = emptyList()) : Script
    }

    private class FakeYtdlp : Ytdlp {
        var ready = true
        var updateOutcome = DlUpdate.Outcome.CURRENT
        val scripts = ArrayDeque<Script>()
        val calls = mutableListOf<String>()
        val folders = mutableListOf<File>()

        /** Each live process, by id: completed only by [kill]. */
        val live = HashMap<String, CompletableDeferred<YtdlpExit>>()

        override suspend fun ready(): Boolean = ready

        override suspend fun run(id: String, args: List<String>, url: String, line: (String) -> Unit): YtdlpExit {
            calls += "run"
            val output = args.indexOf("-o").takeIf { it >= 0 }?.let { File(args[it + 1]).parentFile }
            output?.let { folders += it }
            return when (val script = scripts.removeFirstOrNull() ?: Script.Fail("ERROR: no script")) {
                is Script.Finish -> {
                    script.files.forEach { (name, bytes) -> File(output, name).writeBytes(bytes) }
                    script.meta?.let { File(output, "meta.json").appendText(it + "\n") }
                    script.lines.forEach(line)
                    YtdlpExit.Done(if ("-J" in args) script.lines.joinToString("\n") else "")
                }
                is Script.Fail -> YtdlpExit.Failed(script.err)
                is Script.Hang -> {
                    script.parts.forEach { (name, bytes) -> File(output, name).writeBytes(bytes) }
                    script.lines.forEach(line)
                    val process = CompletableDeferred<YtdlpExit>().also { live[id] = it }
                    // A process does not stop because its caller was cancelled: only a kill ends it.
                    withContext(NonCancellable) { process.await() }
                }
            }
        }

        override fun kill(id: String, marker: String) {
            calls += "kill $marker"
            live.remove(id)?.complete(YtdlpExit.Killed)
        }

        override suspend fun update(): DlUpdate.Outcome {
            calls += "update"
            return updateOutcome
        }

        override fun version(): String = "2026.11.01"
    }

    private class Memory(override var lastCheck: Long?) : UpdateMemory

    private class Heard : RunEvents {
        val progress = mutableListOf<DlProgress>()
        var file: DlFile? = null
        var end: DlEnd? = null

        override fun onProgress(progress: DlProgress) {
            this.progress += progress
        }

        override fun onFile(file: DlFile) {
            this.file = file
        }

        override fun onEnd(end: DlEnd) {
            check(this.end == null) { "two ends" }
            this.end = end
        }
    }

    private val ytdlp = FakeYtdlp()
    private val now = 1_800_000_000_000L
    private val memory = Memory(now - 2 * day)
    private val root: File get() = File(temp.root, "dl")
    private var quick: suspend (String) -> DlInfo? = { null }

    /** Every host is out on the internet, but those the test gives an address of its own. */
    private val dns = mutableMapOf<String, String>()

    private fun TestScope.runs() = DlRuns(
        scope = backgroundScope,
        ytdlp = ytdlp,
        folder = root,
        memory = memory,
        clock = { now },
        monotonic = { testScheduler.currentTime },
        quickInfo = { quick(it) },
        io = StandardTestDispatcher(testScheduler),
        resolve = { host -> listOf(InetAddress.getByName(dns[host] ?: "93.184.216.34")) },
        ended = { ends++ },
    )

    /** How many times the add-on said a run or a lookup ended: its foreground service is left when it is no longer busy. */
    private var ends = 0

    private val song = DlRequest("k1", "https://video.example.com/watch?v=abc", DlKind.AUDIO, AudioQuality.MP3_320)
    private val audio = ByteArray(600_000) { (it % 251).toByte() }
    private val cover = ByteArray(40_000) { (it % 13).toByte() }
    private val finished = Script.Finish(
        mapOf("The Quiet Hours - Paper Lantern.mp3" to audio, "The Quiet Hours - Paper Lantern.jpg" to cover),
        lines = listOf("[download]  50.0% of 0.57MiB at 1.00MiB/s ETA 00:01", "[ExtractAudio] Destination: x.mp3"),
        meta = "{\"meta_title\":\"Paper Lantern\",\"meta_artist\":\"The Quiet Hours\",\"duration\":214}",
    )

    // A run

    @Test
    fun `a run updates a stale yt-dlp, downloads, hands its file and cover to KJB, and leaves nothing behind`() = runTest {
        ytdlp.scripts += finished
        val runs = runs()
        val heard = Heard()

        val run = runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("update", "run"), ytdlp.calls)
        assertEquals(now, memory.lastCheck)
        assertEquals(listOf(DlStage.UPDATING, DlStage.STARTING, DlStage.DOWNLOADING, DlStage.CONVERTING), heard.progress.map { it.stage })
        val file = heard.file!!
        assertEquals("The Quiet Hours - Paper Lantern.mp3", file.name)
        assertEquals(audio.size.toLong(), file.bytes)
        assertEquals(cover.size.toLong(), file.coverBytes)
        assertEquals("Paper Lantern", file.meta.title)
        assertEquals(214, file.durationSec)
        assertNull(heard.end) // waits for KJB's descriptor

        val media = ByteArrayOutputStream()
        val art = ByteArrayOutputStream()
        runs.deliver(run, media, art)
        runCurrent()

        assertEquals(DlEnd.Delivered(audio.size.toLong(), cover.size.toLong()), heard.end)
        assertArrayEquals(audio, media.toByteArray())
        assertArrayEquals(cover, art.toByteArray())
        assertFalse(File(root, "k1").exists())
    }

    @Test
    fun `a yt-dlp updated within the day is not updated before a run`() = runTest {
        memory.lastCheck = now - day + 60_000
        ytdlp.scripts += finished
        val runs = runs()

        runs.start(song, Heard())
        runCurrent()

        assertEquals(listOf("run"), ytdlp.calls)
    }

    @Test
    fun `Cancel kills yt-dlp and its ffmpeg though yt-dlp would not stop by itself, and deletes what it got`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.webm.part" to ByteArray(10)), listOf("[download]   3.0% of 9.00MiB at 1.00MiB/s ETA 00:09"))
        val runs = runs()
        val heard = Heard()
        val run = runs.start(song, heard)
        runCurrent()
        assertTrue(File(root, "k1/x.webm.part").exists())
        assertNull(heard.end)

        runs.cancel(run, keepParts = false)
        runCurrent()

        assertEquals("kill ${File(root, "k1").path}/", ytdlp.calls.last())
        assertEquals(DlEnd.Stopped, heard.end)
        assertFalse(File(root, "k1").exists())
    }

    @Test
    fun `KJB pausing a run keeps its parts, and the next run of its key finds them`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.webm.part" to ByteArray(10)))
        val runs = runs()
        val first = Heard()
        val run = runs.start(song, first)
        runCurrent()

        runs.cancel(run, keepParts = true)
        runCurrent()

        assertEquals(DlEnd.Stopped, first.end)
        assertTrue(File(root, "k1/x.webm.part").exists())
        ytdlp.scripts += finished
        runs.start(song, Heard())
        runCurrent()
        assertEquals(ytdlp.folders[0], ytdlp.folders[1])
        assertTrue(File(ytdlp.folders[1], "x.webm.part").exists())
    }

    @Test
    fun `a site failure updates yt-dlp once and tries again, and a second failure ends the run`() = runTest {
        memory.lastCheck = now - 60_000
        ytdlp.updateOutcome = DlUpdate.Outcome.UPDATED
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Unable to extract initial player response")
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Unable to extract initial player response")
        val runs = runs()
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("run", "update", "run"), ytdlp.calls)
        assertTrue(DlStage.RETRYING in heard.progress.map { it.stage })
        assertEquals(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = true), heard.end)
    }

    @Test
    fun `a site failure with yt-dlp already the newest is not tried again`() = runTest {
        memory.lastCheck = now - 60_000
        ytdlp.updateOutcome = DlUpdate.Outcome.CURRENT
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Sign in to confirm you're not a bot")
        val runs = runs()
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("run", "update"), ytdlp.calls)
        assertEquals(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = true), heard.end)
    }

    @Test
    fun `a run that updated before it started does not update again after a failure`() = runTest {
        ytdlp.updateOutcome = DlUpdate.Outcome.UPDATED
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Unable to extract initial player response")
        val runs = runs()
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("update", "run"), ytdlp.calls)
        assertEquals(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = true), heard.end)
    }

    @Test
    fun `an offline failure updates nothing, its parts stay for KJB's Retry, and discard deletes them`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.webm.part" to ByteArray(10)))
        val runs = runs()
        val run = runs.start(song, Heard())
        runCurrent()
        runs.cancel(run, keepParts = true)
        runCurrent()
        ytdlp.scripts += Script.Fail("ERROR: Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>")
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.OFFLINE), heard.end)
        assertFalse("update" in ytdlp.calls)
        assertTrue(File(root, "k1/x.webm.part").exists())
        runs.discard("k1")
        assertFalse(File(root, "k1").exists())
    }

    @Test
    fun `an update that could not be made (offline) is tried again at the next run`() = runTest {
        ytdlp.updateOutcome = DlUpdate.Outcome.FAILED
        ytdlp.scripts += finished
        val before = memory.lastCheck

        runs().start(song, Heard())
        runCurrent()

        assertEquals(listOf("update", "run"), ytdlp.calls)
        assertEquals(before, memory.lastCheck)
    }

    // The hand-off into KJB's descriptors

    private fun TestScope.waiting(runs: DlRuns, heard: Heard): Long {
        memory.lastCheck = now
        ytdlp.scripts += finished
        val run = runs.start(song, heard)
        runCurrent()
        assertTrue(heard.file != null)
        return run
    }

    @Test
    fun `a descriptor that breaks part way is a hand-off failure, and the file stays for a Retry`() = runTest {
        val runs = runs()
        val heard = Heard()
        val run = waiting(runs, heard)
        val broken = object : OutputStream() {
            var written = 0

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (written + len > 100_000) throw IOException("write failed: EPIPE (Broken pipe)")
                written += len
            }
        }

        runs.deliver(run, broken, ByteArrayOutputStream())
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.HAND_OFF), heard.end)
        assertTrue(File(root, "k1/The Quiet Hours - Paper Lantern.mp3").exists())
    }

    @Test
    fun `a full disk where KJB saves ends the run as no room`() = runTest {
        val runs = runs()
        val heard = Heard()
        val run = waiting(runs, heard)
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("write failed: ENOSPC (No space left on device)")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
        }

        runs.deliver(run, full, null)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.NO_ROOM), heard.end)
    }

    @Test
    fun `Cancel during the hand-off stops it there and deletes the file`() = runTest {
        val runs = runs()
        val heard = Heard()
        val run = waiting(runs, heard)
        val sink = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                runs.cancel(run, keepParts = false)
            }
        }

        runs.deliver(run, sink, null)
        runCurrent()

        assertEquals(DlEnd.Stopped, heard.end)
        assertTrue(sink.size() < audio.size)
        assertFalse(File(root, "k1").exists())
    }

    @Test
    fun `KJB never taking the file does not hold the run for good`() = runTest {
        val runs = runs()
        val heard = Heard()
        waiting(runs, heard)

        advanceTimeBy(DlRuns.DELIVER_WAIT_MS + 1)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.HAND_OFF), heard.end)
        assertTrue(File(root, "k1/The Quiet Hours - Paper Lantern.mp3").exists())
    }

    // What the add-on refuses

    @Test
    fun `a link whose host resolves to this phone or the home network is refused before yt-dlp runs`() = runTest {
        memory.lastCheck = now
        dns["router.example.com"] = "192.168.1.1"
        dns["meta.example.com"] = "169.254.169.254"
        val runs = runs()
        val looked = Looked()
        runs.info("http://router.example.com/admin", looked)
        val heard = Heard()
        runs.start(song.copy(url = "https://meta.example.com/latest"), heard)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.LOCAL_ADDRESS), looked.failed)
        assertEquals(DlEnd.Failed(DlFailure.LOCAL_ADDRESS), heard.end)
        assertFalse("run" in ytdlp.calls)
        assertFalse(File(root, "k1").exists())
    }

    @Test
    fun `discard while its run is live stops it and deletes its folder once it has stopped`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.webm.part" to ByteArray(10)))
        val runs = runs()
        val heard = Heard()
        val run = runs.start(song, heard)
        runCurrent()
        // KJB's Cancel, as it came before: the run kept its parts, then the key was discarded while it was still stopping.
        runs.cancel(run, keepParts = true)
        runs.discard("k1")
        runCurrent()

        assertEquals(DlEnd.Stopped, heard.end)
        assertFalse(File(root, "k1").exists())
    }

    @Test
    fun `a folder a discard could not delete is swept at the next start, whatever its age`() = runTest {
        val left = File(root, "k9").apply { mkdirs() }
        File(left, "x.part").writeBytes(ByteArray(1))
        File(left, DlRuns.DISCARDED).writeText("")
        val kept = File(root, "k8").apply { mkdirs() }
        File(kept, "x.part").writeBytes(ByteArray(1))
        left.setLastModified(now)
        kept.setLastModified(now)

        runs().sweep()

        assertFalse(left.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun `a request the add-on does not take is refused at once, and so is a second run of a busy key`() = runTest {
        memory.lastCheck = now
        val runs = runs()
        val refused = Heard()
        runs.start(null, refused)
        runCurrent()
        assertEquals(DlEnd.Failed(DlFailure.REFUSED), refused.end)

        ytdlp.scripts += Script.Hang()
        runs.start(song, Heard())
        runCurrent()
        val second = Heard()
        runs.start(song.copy(kind = DlKind.VIDEO), second)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.REFUSED), second.end)
        assertEquals(1, ytdlp.calls.count { it == "run" })
    }

    @Test
    fun `yt-dlp that cannot start in the add-on ends the run as an engine failure`() = runTest {
        ytdlp.ready = false
        val heard = Heard()

        runs().start(song, heard)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.ENGINE), heard.end)
        assertTrue(ytdlp.calls.isEmpty())
    }

    @Test
    fun `progress reaches KJB at most every half second while it downloads, a new stage at once`() = runTest {
        memory.lastCheck = now
        val lines = (1..20).map { "[download]  ${it * 4}.0% of 9.00MiB at 1.00MiB/s ETA 00:09" } + "[ExtractAudio] Destination: x.mp3"
        ytdlp.scripts += finished.copy(lines = lines)
        val heard = Heard()

        runs().start(song, heard)
        runCurrent()

        // The lines all came at one moment: the first download line, then the converting stage.
        assertEquals(listOf(DlStage.STARTING, DlStage.DOWNLOADING, DlStage.CONVERTING), heard.progress.map { it.stage })
        assertEquals(4f, heard.progress[1].percent)
    }

    // Looking up a link

    private class Looked : InfoEvents {
        val infos = mutableListOf<DlInfo>()
        var failed: DlEnd.Failed? = null

        override fun onInfo(info: DlInfo) {
            infos += info
        }

        override fun onFailed(end: DlEnd.Failed) {
            failed = end
        }
    }

    private val fullInfo = """{"title":"The Quiet Hours - Paper Lantern","uploader":"The Quiet Hours","duration":214}"""

    @Test
    fun `a lookup tells the quick info first, then the full info`() = runTest {
        val gate = CompletableDeferred<Unit>()
        quick = { DlInfo(it, "Paper Lantern", "The Quiet Hours", partial = true) }
        ytdlp.scripts += Script.Hang()
        val runs = runs()
        val looked = Looked()

        runs.info("https://youtu.be/abc", looked)
        runCurrent()
        assertEquals(listOf(true), looked.infos.map { it.partial })

        // The full info: the fake's next run answers -J.
        ytdlp.live.values.single().complete(YtdlpExit.Done(fullInfo))
        runCurrent()
        gate.complete(Unit)

        assertEquals(listOf(true, false), looked.infos.map { it.partial })
        assertEquals(214, looked.infos.last().durationSec)
    }

    @Test
    fun `quick info that comes after the full info is not told`() = runTest {
        val late = CompletableDeferred<Unit>()
        quick = {
            late.await()
            DlInfo(it, "Paper Lantern", "The Quiet Hours", partial = true)
        }
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(fullInfo))
        val looked = Looked()

        runs().info("https://youtu.be/abc", looked)
        runCurrent()
        late.complete(Unit)
        runCurrent()

        assertEquals(listOf(false), looked.infos.map { it.partial })
    }

    @Test
    fun `a lookup that fails says why, and a cancelled one kills its yt-dlp`() = runTest {
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Video unavailable")
        val runs = runs()
        val failed = Looked()
        runs.info("https://video.example.com/v", failed)
        runCurrent()
        assertEquals(DlEnd.Failed(DlFailure.NOT_FOUND), failed.failed)

        ytdlp.scripts += Script.Hang()
        val lookup = runs.info("https://video.example.com/w", Looked())
        runCurrent()
        runs.cancelInfo(lookup)
        runCurrent()

        assertTrue(ytdlp.calls.last().startsWith("kill "))
        assertTrue(ytdlp.live.isEmpty())
    }

    // Busy while it works: the add-on's own foreground service is held from a link taken to its last byte handed back

    @Test
    fun `a lookup keeps the add-on busy from the link taken to its full info, and says when it ended`() = runTest {
        ytdlp.scripts += Script.Hang()
        val runs = runs()
        assertFalse(runs.busy)

        runs.info("https://youtu.be/abc", Looked())
        assertTrue("from the moment it takes the link", runs.busy)
        runCurrent()
        assertTrue("while yt-dlp looks it up", runs.busy)
        assertEquals(0, ends)

        ytdlp.live.values.single().complete(YtdlpExit.Done(fullInfo))
        runCurrent()

        assertFalse(runs.busy)
        assertEquals(1, ends)
    }

    @Test
    fun `a run keeps the add-on busy until its file is handed back to KJB, and says when it ended`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += finished
        val runs = runs()

        val run = runs.start(song, Heard())
        assertTrue(runs.busy)
        runCurrent()
        assertTrue("waiting for KJB to take the file", runs.busy)
        assertEquals(0, ends)

        runs.deliver(run, ByteArrayOutputStream(), ByteArrayOutputStream())
        runCurrent()

        assertFalse(runs.busy)
        assertEquals(1, ends)
    }

    @Test
    fun `a cancelled lookup or run ends its busy time too`() = runTest {
        ytdlp.scripts += Script.Hang()
        ytdlp.scripts += Script.Hang()
        memory.lastCheck = now
        val runs = runs()
        val lookup = runs.info("https://video.example.com/w", Looked())
        val run = runs.start(song, Heard())
        runCurrent()

        runs.cancelInfo(lookup)
        runCurrent()
        assertTrue("the run still works", runs.busy)
        runs.cancel(run, keepParts = true)
        runCurrent()

        assertFalse(runs.busy)
        assertEquals(2, ends)
    }

    @Test
    fun `Android's time for the foreground service running out stops the work, its parts kept for later`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.webm.part" to ByteArray(10)))
        val runs = runs()
        val heard = Heard()
        runs.start(song, heard)
        runCurrent()
        ytdlp.scripts += Script.Hang()
        runs.info("https://video.example.com/w", Looked())
        runCurrent()
        assertEquals(2, ytdlp.live.size)

        runs.timeUp()
        runCurrent()

        assertEquals(DlEnd.Stopped, heard.end)
        assertTrue("its parts stay for the next run", File(root, "k1/x.webm.part").exists())
        assertTrue(ytdlp.live.isEmpty())
        assertFalse(runs.busy)
    }

    // Updating yt-dlp

    @Test
    fun `Update now waits for no download, is refused while one runs, and a check records its time`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang()
        val runs = runs()
        val run = runs.start(song, Heard())
        runCurrent()

        assertEquals(DlUpdate.Outcome.BUSY, runs.update().outcome)
        runs.cancel(run, keepParts = false)
        runCurrent()
        memory.lastCheck = null
        assertEquals(DlUpdate(DlUpdate.Outcome.CURRENT, "2026.11.01"), runs.update())
        assertEquals(now, memory.lastCheck)
    }

    @Test
    fun `the update policy - before a run when more than a day old, after a failure once, for what an update can fix`() {
        assertTrue(UpdatePolicy.beforeRun(lastCheck = null, now = now))
        assertTrue(UpdatePolicy.beforeRun(lastCheck = now - day - 1, now = now))
        assertFalse(UpdatePolicy.beforeRun(lastCheck = now - day, now = now))
        assertFalse(UpdatePolicy.beforeRun(lastCheck = now - 60_000, now = now))
        // The clock went back: the stored time is not believed.
        assertTrue(UpdatePolicy.beforeRun(lastCheck = now + day, now = now))

        listOf(DlFailure.SITE_CHANGED, DlFailure.NOT_FOUND, DlFailure.UNKNOWN).forEach { assertTrue(it.name, UpdatePolicy.afterFailure(it, checkedThisRun = false)) }
        listOf(DlFailure.OFFLINE, DlFailure.NO_ROOM, DlFailure.HAND_OFF, DlFailure.ENGINE, DlFailure.REFUSED).forEach {
            assertFalse(it.name, UpdatePolicy.afterFailure(it, checkedThisRun = false))
        }
        assertFalse(UpdatePolicy.afterFailure(DlFailure.SITE_CHANGED, checkedThisRun = true))
    }

    @Test
    fun `folders a week old are swept, newer ones and live runs stay`() = runTest {
        val old = File(root, "old").apply { mkdirs() }.also { File(it, "x.part").writeBytes(ByteArray(1)) }
        val recent = File(root, "recent").apply { mkdirs() }
        old.setLastModified(now - 8 * day)
        recent.setLastModified(now - day)

        runs().sweep()

        assertFalse(old.exists())
        assertTrue(recent.exists())
    }
}
