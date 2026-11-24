package app.lyrify.dl

import android.content.pm.ServiceInfo
import app.lyrify.addon.shared.ForegroundPort
import app.lyrify.addon.shared.JobForeground
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlPlaylist
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlSearch
import app.lyrify.dl.api.DlSearchResult
import app.lyrify.dl.api.DlSearchResults
import app.lyrify.dl.api.DlStage
import app.lyrify.dl.api.DlUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
import org.junit.rules.Timeout
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
    @get:Rule(order = 0)
    val temp = TemporaryFolder()

    /**
     * Each test ends within this or fails, its tear-down included: a stopped run's settle waits NonCancellable on the
     * virtual clock, so a run that never lets go loops on in runTest's tear-down, past runTest's own timeout, and would
     * hold the gate for good. Inside [temp], whose folder still goes when a test times out.
     */
    @get:Rule(order = 1)
    val timeout: Timeout = Timeout.seconds(10)

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

        /** What each run was given: its arguments, and the link (or the search) after them. */
        val argLists = mutableListOf<List<String>>()
        val targets = mutableListOf<String>()

        /** Each live process, by id: completed only by [kill]. */
        val live = HashMap<String, CompletableDeferred<YtdlpExit>>()

        override suspend fun ready(): Boolean = ready

        override suspend fun run(id: String, args: List<String>, url: String, line: (String) -> Unit): YtdlpExit {
            calls += "run"
            argLists += args
            targets += url
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

        /**
         * A killed process still in a folder, by its marker: how many more looks see it (it goes after them, or when the
         * test ends: [runs]).
         */
        val lingering = HashMap<String, Int>()

        override fun running(marker: String): Boolean {
            val left = lingering[marker] ?: 0
            if (left > 0) lingering[marker] = left - 1
            return left > 0
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

        /** Counted apart: they come with the virtual clock, not with the run's steps. */
        var beats = 0

        override fun onBeat() {
            beats++
        }
    }

    private val ytdlp = FakeYtdlp()
    private val now = 1_800_000_000_000L
    private val memory = Memory(now - 2 * day)
    private val root: File get() = File(temp.root, "dl")
    private var quick: suspend (String) -> DlInfo? = { null }

    /** Every host is out on the internet, but those the test gives an address of its own. */
    private val dns = mutableMapOf<String, String>()

    private fun TestScope.runs(): DlRuns {
        // The test's killed processes go when it ends: a run that waits for one ends in the tear-down, so a test that
        // fails while one lingers fails with its own message rather than looping on there.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                ytdlp.lingering.clear()
            }
        }
        return DlRuns(
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
            log = { logs += it },
        )
    }

    /** What the add-on logged: never a link, an id, a title or a path. */
    private val logs = mutableListOf<String>()

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
    fun `a site failure with yt-dlp already the newest is not tried again, and says it was not updated`() = runTest {
        memory.lastCheck = now - 60_000
        ytdlp.updateOutcome = DlUpdate.Outcome.CURRENT
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Unable to extract initial player response")
        val runs = runs()
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("run", "update"), ytdlp.calls)
        assertEquals(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = false), heard.end)
    }

    @Test
    fun `a site failure whose update could not be made says it was not updated`() = runTest {
        memory.lastCheck = now - 60_000
        ytdlp.updateOutcome = DlUpdate.Outcome.FAILED
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Unable to extract initial player response")
        val runs = runs()
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("run", "update"), ytdlp.calls)
        assertEquals(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = false), heard.end)
    }

    @Test
    fun `a run that found yt-dlp the newest before it started says it was not updated after a site failure`() = runTest {
        ytdlp.updateOutcome = DlUpdate.Outcome.CURRENT
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Unable to extract initial player response")
        val runs = runs()
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("update", "run"), ytdlp.calls)
        assertEquals(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = false), heard.end)
    }

    @Test
    fun `a site that refuses this connection is blocked, and no update or second try is made for it`() = runTest {
        memory.lastCheck = now - 60_000
        ytdlp.updateOutcome = DlUpdate.Outcome.UPDATED
        ytdlp.scripts += Script.Fail("ERROR: [youtube] abc: Sign in to confirm you're not a bot")
        val runs = runs()
        val heard = Heard()

        runs.start(song, heard)
        runCurrent()

        assertEquals(listOf("run"), ytdlp.calls)
        assertEquals(DlEnd.Failed(DlFailure.BLOCKED, updated = false), heard.end)
    }

    @Test
    fun `a failed run or lookup logs its kind, pattern and extractor, never the link, the id or the title`() = runTest {
        memory.lastCheck = now
        val link = "https://music.example.test/watch?v=Zq9kXw3Lm0A"
        val err = listOf(
            "[youtube] Extracting URL: $link",
            "WARNING: [youtube] Zq9kXw3Lm0A: Lantern Over Quiet Water: nsig extraction failed",
            "ERROR: [youtube] Zq9kXw3Lm0A: Lantern Over Quiet Water: HTTP Error 429: Too Many Requests ($link)",
        ).joinToString("\n")
        ytdlp.scripts += Script.Fail(err)
        ytdlp.scripts += Script.Fail(err)
        val runs = runs()

        val run = runs.start(song.copy(url = link), Heard())
        runCurrent()
        val lookup = runs.info(link, Looked())
        runCurrent()

        assertTrue(logs.toString(), "run $run: yt-dlp failed (BLOCKED, matched 429, extractor youtube)" in logs)
        assertTrue(logs.toString(), "lookup $lookup: yt-dlp failed (BLOCKED, matched 429, extractor youtube)" in logs)
        listOf("Zq9kXw3Lm0A", "music.example.test", "Lantern", "k1").forEach { secret ->
            assertTrue("$secret in ${logs.filter { secret in it }}", logs.none { secret in it })
        }
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
    fun `Cancel whose closed descriptor fails the hand-off first still deletes the file`() = runTest {
        val runs = runs()
        val heard = Heard()
        val run = waiting(runs, heard)
        // KJB's Cancel, then KJB's descriptor closed under the copy: the write fails before the run sees the Cancel.
        val closed = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                runs.cancel(run, keepParts = false)
                throw IOException("write failed: EPIPE (Broken pipe)")
            }
        }

        runs.deliver(run, closed, null)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.HAND_OFF), heard.end)
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
    fun `KJB's Cancel deletes the run's parts as it stops, though KJB lets go of the add-on meanwhile, and nothing else`() = runTest {
        memory.lastCheck = now
        // In the merge: both streams whole, the merged file part way.
        ytdlp.scripts += Script.Hang(mapOf("x.f315.webm" to ByteArray(10), "x.f258.m4a" to ByteArray(10), "x.temp.mp4" to ByteArray(10)))
        val other = File(root, "k2").apply { mkdirs() }
        File(other, "y.webm.part").writeBytes(ByteArray(1))
        val outside = File(temp.root, "x.temp.mp4").apply { writeBytes(ByteArray(1)) }
        val runs = runs()
        val heard = Heard()
        val run = runs.start(song, heard)
        runCurrent()

        // KJB's Cancel, its discard, then KJB letting go of the add-on (every run stopped, parts kept): all while it stops.
        runs.cancel(run, keepParts = false)
        runs.discard("k1")
        runs.cancelAll()
        runCurrent()

        assertEquals(DlEnd.Stopped, heard.end)
        assertFalse(File(root, "k1").exists())
        assertTrue(File(other, "y.webm.part").exists())
        assertTrue(outside.exists())
    }

    @Test
    fun `a stopped run's folder goes once its killed processes are gone, however long one stays, and its key stays taken meanwhile`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.temp.mp4" to ByteArray(10)))
        val runs = runs()
        val heard = Heard()
        val run = runs.start(song, heard)
        runCurrent()
        val marker = File(root, "k1").path + "/"
        // Its ffmpeg, killed, still there a minute on.
        ytdlp.lingering[marker] = Int.MAX_VALUE

        runs.cancel(run, keepParts = false)
        advanceTimeBy(60_000)
        runCurrent()

        // Its files untouched and its run not over: no second run of its key starts, and no sweep deletes it.
        assertTrue(File(root, "k1/x.temp.mp4").exists())
        assertNull(heard.end)
        val second = Heard()
        runs.start(song, second)
        runCurrent()
        assertEquals(DlEnd.Failed(DlFailure.REFUSED), second.end)
        runs.discard("k1")
        runs().sweep()
        assertTrue(File(root, "k1/x.temp.mp4").exists())

        // Gone: the folder goes, the run ends, and the key is free again.
        ytdlp.lingering.clear()
        advanceTimeBy(DlRuns.GONE_WAIT_MS)
        runCurrent()
        assertFalse(File(root, "k1").exists())
        assertEquals(DlEnd.Stopped, heard.end)
        ytdlp.scripts += Script.Hang()
        val third = Heard()
        runs.start(song, third)
        runCurrent()
        assertNull(third.end)
    }

    @Test
    fun `a run KJB lets go of keeps its key while a killed process is in its folder, and a discard meanwhile deletes it once gone`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.temp.mp4" to ByteArray(10)))
        val runs = runs()
        val heard = Heard()
        runs.start(song, heard)
        runCurrent()
        // Its ffmpeg, killed, still seen for 3 s.
        ytdlp.lingering[File(root, "k1").path + "/"] = 30

        // KJB lets go of the add-on (parts kept), starts the key again, then discards it, all while the ffmpeg is there.
        runs.cancelAll()
        runCurrent()
        val second = Heard()
        runs.start(song, second)
        runCurrent()
        assertEquals(DlEnd.Failed(DlFailure.REFUSED), second.end)
        runs.discard("k1")
        runCurrent()
        assertTrue(File(root, "k1/x.temp.mp4").exists())

        advanceTimeBy(DlRuns.GONE_WAIT_MS)
        runCurrent()
        assertFalse(File(root, "k1").exists())
        assertEquals(DlEnd.Stopped, heard.end)
    }

    @Test
    fun `a discard of a key no run holds deletes its folder only once no process is in it, else marks it for the sweep`() = runTest {
        val left = File(root, "k9").apply { mkdirs() }
        File(left, "x.part").writeBytes(ByteArray(1))
        ytdlp.lingering[left.path + "/"] = Int.MAX_VALUE

        runs().discard("k9")

        assertTrue(File(left, "x.part").exists())
        assertTrue(File(left, DlRuns.DISCARDED).exists())
        ytdlp.lingering.clear()
        runs().sweep()
        assertFalse(left.exists())
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

    // A playlist's link (IDlAddon VERSION 2)

    /** yt-dlp's -J of a flat playlist (InfoTest has a recorded one): two songs of a list, named [id]. */
    private fun listed(id: String) = """{"id":"$id","title":"Harbour Nights","_type":"playlist","playlist_count":2,"uploader":"Paper Harbour","entries":[""" +
        """{"_type":"url","ie_key":"Youtube","id":"abc123def45","url":"https://www.youtube.com/watch?v=abc123def45","title":"Paper Lantern","duration":214,"channel":"Paper Harbour"},""" +
        """{"_type":"url","ie_key":"Youtube","id":"ghi789jkl01","url":"https://www.youtube.com/watch?v=ghi789jkl01","title":"Glass Tide","duration":163,"channel":"Paper Harbour"}],"extractor":"youtube:tab"}"""

    private val twoSongs = listOf(
        DlSearchResult("https://www.youtube.com/watch?v=abc123def45", "Paper Lantern", "Paper Harbour", 214),
        DlSearchResult("https://www.youtube.com/watch?v=ghi789jkl01", "Glass Tide", "Paper Harbour", 163),
    )

    @Test
    fun `a playlist's link is looked up once, its songs listed and none of them opened`() = runTest {
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("PLq7")))
        val looked = Looked()

        runs().info("https://www.youtube.com/playlist?list=PLq7", looked)
        runCurrent()

        assertEquals(DlPlaylist("Harbour Nights", twoSongs), looked.infos.single().playlist)
        assertEquals(377, looked.infos.single().durationSec)
        val args = ytdlp.argLists.single()
        assertTrue(args.toString(), args.containsAll(listOf("-J", "--no-playlist", "--flat-playlist")))
        assertEquals(":200", args[args.indexOf("--playlist-items") + 1])
        // A lookup never updates yt-dlp, more than a day old here.
        assertEquals(listOf("run"), ytdlp.calls)
    }

    @Test
    fun `a link that names a list and no song is the playlist's own, looked up once and never asked about`() = runTest {
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("PLq7")))
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("PLq7")))
        val runs = runs()
        val watch = Looked()
        val embed = Looked()

        runs.info("https://www.youtube.com/watch?list=PLq7", watch)
        runCurrent()
        runs.info("https://www.youtube.com/embed/videoseries?list=PLq7", embed)
        runCurrent()

        // No "Just this song" for a link with no song: it would save the playlist's link as one download.
        listOf(watch, embed).forEach { looked -> assertEquals(listOf(DlPlaylist("Harbour Nights", twoSongs)), looked.infos.map { it.playlist }) }
        assertEquals(2, ytdlp.argLists.size)
        assertTrue(ytdlp.argLists.toString(), ytdlp.argLists.all { "--no-playlist" in it })
    }

    @Test
    fun `a song's short link that names a playlist is the song, its playlist listed first`() = runTest {
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("PLq7")))
        ytdlp.scripts += Script.Hang()
        val looked = Looked()

        runs().info("https://youtu.be/abc123def45?list=PLq7&si=x9", looked)
        runCurrent()

        assertEquals(listOf(DlPlaylist("Harbour Nights", twoSongs, withSong = true)), looked.infos.map { it.playlist })
        assertTrue(ytdlp.argLists.first().toString(), "--yes-playlist" in ytdlp.argLists.first())
    }

    @Test
    fun `a song's link that names a playlist has it listed first, told with the quick info while the song is looked up`() = runTest {
        quick = { DlInfo(it, "Paper Lantern", "The Quiet Hours", partial = true) }
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("PLq7")))
        ytdlp.scripts += Script.Hang()
        val looked = Looked()
        val url = "https://www.youtube.com/watch?v=abc123def45&list=PLq7&index=2"

        runs().info(url, looked)
        runCurrent()

        // KJB can ask which is meant before the song's own info is in.
        val playlist = DlPlaylist("Harbour Nights", twoSongs, withSong = true)
        assertEquals(listOf(null, playlist), looked.infos.map { it.playlist })
        assertEquals("Paper Lantern", looked.infos.last().title)
        assertTrue(looked.infos.all { it.partial })
        val list = ytdlp.argLists.first()
        assertTrue(list.toString(), "--yes-playlist" in list && "--flat-playlist" in list)
        assertEquals(":200", list[list.indexOf("--playlist-items") + 1])

        ytdlp.live.values.single().complete(YtdlpExit.Done(fullInfo))
        runCurrent()

        val info = looked.infos.last()
        assertEquals("The Quiet Hours" to "Paper Lantern", info.artist to info.title)
        assertEquals(214, info.durationSec)
        assertFalse(info.partial)
        assertEquals(playlist, info.playlist)
        assertTrue(ytdlp.argLists.last().toString(), "--no-playlist" in ytdlp.argLists.last())
        assertEquals(listOf(url, url), ytdlp.targets)
    }

    @Test
    fun `a song's playlist listed before any quick info is told with the link alone, and quick info after it keeps it`() = runTest {
        val late = CompletableDeferred<Unit>()
        quick = {
            late.await()
            DlInfo(it, "Paper Lantern", "The Quiet Hours", partial = true)
        }
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("PLq7")))
        ytdlp.scripts += Script.Hang()
        val looked = Looked()

        runs().info("https://www.youtube.com/watch?v=abc123def45&list=PLq7", looked)
        runCurrent()
        late.complete(Unit)
        runCurrent()

        val playlist = DlPlaylist("Harbour Nights", twoSongs, withSong = true)
        assertEquals(listOf(playlist, playlist), looked.infos.map { it.playlist })
        assertEquals(listOf("", "Paper Lantern"), looked.infos.map { it.title })
        assertTrue(looked.infos.all { it.partial })
    }

    @Test
    fun `a Mix is listed to its first 50 songs, from a song's link or its own`() = runTest {
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("RDabc123def45")))
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(fullInfo))
        // YouTube today calls a Mix's own link "unviewable" (a private playlist's sentence); one listed is a Mix all the same.
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(listed("RDabc123def45")))
        val runs = runs()
        val looked = Looked()
        val own = Looked()

        runs.info("https://music.youtube.com/watch?v=abc123def45&list=RDAMVMabc123def45", looked)
        runCurrent()
        runs.info("https://www.youtube.com/playlist?list=RDabc123def45", own)
        runCurrent()

        assertEquals(DlPlaylist("Harbour Nights", twoSongs, mix = true, withSong = true), looked.infos.last().playlist)
        assertEquals(DlPlaylist("Harbour Nights", twoSongs, mix = true), own.infos.single().playlist)
        assertEquals(listOf(":50", ":50", ":50"), ytdlp.argLists.map { it[it.indexOf("--playlist-items") + 1] })
    }

    @Test
    fun `a song's playlist that cannot be listed leaves the song as it is, and the log names only why`() = runTest {
        ytdlp.scripts += Script.Fail("ERROR: [youtube:tab] PLq7secret: YouTube said: The playlist does not exist.")
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(fullInfo))
        // yt-dlp's own way with a Mix it cannot list on a song's link: the song's own info, so it is looked up once.
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(fullInfo))
        val runs = runs()
        val failed = Looked()
        val song = Looked()

        val lookup = runs.info("https://www.youtube.com/watch?v=abc123def45&list=PLq7secret", failed)
        runCurrent()
        runs.info("https://www.youtube.com/watch?v=abc123def45&list=RDabc123def45", song)
        runCurrent()

        listOf(failed, song).forEach { looked ->
            assertNull(looked.failed)
            assertEquals(214, looked.infos.single().durationSec)
            assertNull(looked.infos.single().playlist)
        }
        assertEquals(3, ytdlp.argLists.size)
        assertTrue(logs.toString(), "lookup $lookup: yt-dlp failed (PRIVATE_PLAYLIST, matched playlist-missing, extractor youtube:tab)" in logs)
        assertTrue(logs.toString(), logs.none { "PLq7secret" in it || "abc123def45" in it })
    }

    @Test
    fun `a playlist's own link that no one but its owner can list fails as such, with no update or second look`() = runTest {
        ytdlp.scripts += Script.Fail("ERROR: [youtube:tab] LM: YouTube said: The playlist does not exist.")
        val looked = Looked()

        runs().info("https://music.youtube.com/playlist?list=LM", looked)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.PRIVATE_PLAYLIST), looked.failed)
        assertEquals(listOf("run"), ytdlp.calls)
    }

    // Searching YouTube (IDlAddon VERSION 2)

    private class Found : SearchEvents {
        var results: DlSearchResults? = null
        var failed: DlEnd.Failed? = null

        override fun onResults(results: DlSearchResults) {
            check(this.results == null && failed == null) { "two answers" }
            this.results = results
        }

        override fun onFailed(end: DlEnd.Failed) {
            check(results == null && failed == null) { "two answers" }
            failed = end
        }
    }

    /** yt-dlp's -J of a flat "ytsearch" (InfoTest has the long one): one video. */
    private val searchAnswer = """{"id":"paper lantern","title":"paper lantern","_type":"playlist","entries":[""" +
        """{"_type":"url","ie_key":"Youtube","id":"abc123def45","url":"https://www.youtube.com/watch?v=abc123def45",""" +
        """"title":"The Quiet Hours - Paper Lantern","duration":214.0,"channel":"The Quiet Hours"}],"extractor":"youtube:search"}"""

    @Test
    fun `a search asks yt-dlp for YouTube's list only, never a download or an update, and tells what it found`() = runTest {
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(searchAnswer))
        val found = Found()

        runs().search(DlSearch("  the quiet hours paper lantern ", limit = 5), found)
        runCurrent()

        assertEquals(DlSearchResults(listOf(DlSearchResult("https://www.youtube.com/watch?v=abc123def45", "The Quiet Hours - Paper Lantern", "The Quiet Hours", 214))), found.results)
        assertEquals(listOf("ytsearch5:the quiet hours paper lantern"), ytdlp.targets)
        val args = ytdlp.argLists.single()
        assertTrue(args.toString(), args.containsAll(listOf("-J", "--flat-playlist")))
        // "--" last: the search, like a link, is never read as an option.
        assertEquals("--", args.last())
        listOf("-f", "-x", "--write-thumbnail", "--print-to-file", "--no-playlist").forEach { assertFalse(it, it in args) }
        // yt-dlp is more than a day old here: a run would update it first, a search never does.
        assertEquals(listOf("run"), ytdlp.calls)
    }

    @Test
    fun `a search that fails says why, and its log names the kind, never the words`() = runTest {
        ytdlp.scripts += Script.Fail("WARNING: [youtube:search] the quiet hours paper lantern: retrying\nERROR: [youtube:search] the quiet hours paper lantern: Unable to download API page: HTTP Error 429: Too Many Requests")
        ytdlp.scripts += Script.Fail("ERROR: [youtube:search] mira solen: Unable to download API page: <urlopen error [Errno 7] No address associated with hostname>")
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf("""{"title":"Paper Harbour","uploader":"Mira Solen"}"""))
        val runs = runs()
        val blocked = Found()
        val offline = Found()
        val odd = Found()

        val first = runs.search(DlSearch("the quiet hours paper lantern"), blocked)
        runCurrent()
        runs.search(DlSearch("mira solen"), offline)
        runCurrent()
        runs.search(DlSearch("paper harbour"), odd)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.BLOCKED), blocked.failed)
        assertEquals(DlEnd.Failed(DlFailure.OFFLINE), offline.failed)
        assertEquals("an answer that is not a search's list", DlEnd.Failed(DlFailure.UNKNOWN), odd.failed)
        assertTrue(logs.toString(), "lookup $first: yt-dlp failed (BLOCKED, matched 429, extractor youtube:search)" in logs)
        listOf("quiet", "lantern", "mira", "solen", "harbour").forEach { word -> assertTrue(word, logs.none { word in it.lowercase() }) }
    }

    @Test
    fun `a search's own words never decide why it failed, though yt-dlp names the search by them`() = runTest {
        // yt-dlp 2026.10.26 names a search 'query "<words>"' in its ERROR lines (YoutubeTabBaseInfoExtractor._search_results).
        ytdlp.scripts += Script.Fail("ERROR: [youtube:search] query \"timed out signature\" page 1: Unable to download API JSON: HTTP Error 429: Too Many Requests")
        ytdlp.scripts += Script.Fail("ERROR: [youtube:search] query \"timed out\": Unable to download webpage: <urlopen error timed out> (caused by TimeoutError('timed out'))")
        val runs = runs()
        val blocked = Found()
        val offline = Found()

        runs.search(DlSearch("timed out signature"), blocked)
        runCurrent()
        runs.search(DlSearch("timed out"), offline)
        runCurrent()

        assertEquals("the reason, not the words", DlEnd.Failed(DlFailure.BLOCKED), blocked.failed)
        assertEquals("the same words in the reason still count", DlEnd.Failed(DlFailure.OFFLINE), offline.failed)
    }

    @Test
    fun `a search keeps the add-on busy until its answer, and cancelInfo kills its yt-dlp and ends its busy time`() = runTest {
        ytdlp.scripts += Script.Hang()
        ytdlp.scripts += Script.Finish(emptyMap(), lines = listOf(searchAnswer))
        val runs = runs()
        val stopped = Found()

        val search = runs.search(DlSearch("paper lantern"), stopped)
        assertTrue("from the moment it takes the words", runs.busy)
        runCurrent()
        assertTrue("while yt-dlp searches", runs.busy)
        runs.cancelInfo(search)
        runCurrent()

        assertTrue(ytdlp.calls.last().startsWith("kill "))
        assertTrue(ytdlp.live.isEmpty())
        assertFalse(runs.busy)
        assertEquals(1, ends)
        assertNull("a stopped search tells nothing", stopped.results ?: stopped.failed)

        runs.search(DlSearch("paper lantern"), Found())
        assertTrue(runs.busy)
        runCurrent()
        assertFalse(runs.busy)
        assertEquals(2, ends)
    }

    @Test
    fun `a search the add-on does not take is refused at once, and one yt-dlp cannot start for fails as the engine`() = runTest {
        val runs = runs()
        val refused = Found()
        val engine = Found()

        runs.search(null, refused)
        runCurrent()
        ytdlp.ready = false
        runs.search(DlSearch("paper lantern"), engine)
        runCurrent()

        assertEquals(DlEnd.Failed(DlFailure.REFUSED), refused.failed)
        assertEquals(DlEnd.Failed(DlFailure.ENGINE), engine.failed)
        assertTrue("yt-dlp never ran", ytdlp.calls.isEmpty())
        assertFalse(runs.busy)
        assertEquals(2, ends)
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
    fun `a run beats at once and every 5 s until it ends, through a yt-dlp that runs for minutes`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang()
        val runs = runs()
        val heard = Heard()

        val run = runs.start(song, heard)
        runCurrent()
        assertEquals(1, heard.beats)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals("one at its start, then one every 5 s", 61, heard.beats)

        runs.cancel(run, keepParts = true)
        runCurrent()
        val beats = heard.beats
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("none once it ended", beats, heard.beats)
    }

    @Test
    fun `a stopped run beats on while a killed process keeps it from its end, and stops at its end`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang()
        val runs = runs()
        val heard = Heard()
        val run = runs.start(song, heard)
        runCurrent()
        // Its ffmpeg, killed, still there a minute on.
        ytdlp.lingering[File(root, "k1").path + "/"] = Int.MAX_VALUE

        runs.cancel(run, keepParts = false)
        runCurrent()
        val stopped = heard.beats
        advanceTimeBy(60_000)
        runCurrent()
        val endWhileThere = heard.end
        val beatsWhileThere = heard.beats - stopped
        // Gone, so the run ends before anything is asserted: a run left waiting would hold the test.
        ytdlp.lingering.clear()
        advanceTimeBy(DlRuns.GONE_WAIT_MS)
        runCurrent()

        // KJB still hears from it every 5 s, so its freeze watch never takes the wait for a frozen add-on.
        assertNull(endWhileThere)
        assertEquals("one every 5 s while it waits", 12, beatsWhileThere)
        assertEquals(DlEnd.Stopped, heard.end)
        val ended = heard.beats
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("none once it ended", ended, heard.beats)
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

    /** Android's side of the add-on's foreground service, as JobForeground asks it. */
    private class FakeForeground : ForegroundPort<String> {
        val calls = mutableListOf<String>()

        override fun startForeground(type: Int, tap: String?) {
            calls += "startForeground"
        }

        override fun stopForeground() {
            calls += "stopForeground"
        }

        override fun stopSelf(startId: Int?) {
            calls += "stopSelf ${startId ?: "all"}"
        }
    }

    @Test
    fun `Android's time for the foreground service running out leaves it at once, and the work goes on without it`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.webm.part" to ByteArray(10)))
        val runs = runs()
        val heard = Heard()
        runs.start(song, heard)
        runCurrent()
        val android = FakeForeground()
        val foreground = JobForeground(listOf(runs.foregroundWork()), android, fromKjb = { it == "kjb" })
        foreground.start(1, "kjb")

        foreground.timeUp(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        runCurrent()

        // Out of it within seconds, as Android requires (onTimeout), while KJB keeps the add-on bound for the run.
        assertEquals(listOf("startForeground", "stopForeground", "stopSelf all"), android.calls)
        assertNull("the run goes on", heard.end)
        assertTrue(runs.busy)
        assertEquals(1, ytdlp.live.size)
    }

    @Test
    fun `after the time ran out, KJB's next start while the work goes on asks for the foreground again, and holds it when let in`() = runTest {
        memory.lastCheck = now
        ytdlp.scripts += Script.Hang(mapOf("x.webm.part" to ByteArray(10)))
        val runs = runs()
        runs.start(song, Heard())
        runCurrent()
        val android = FakeForeground()
        val answers = mutableListOf<Boolean>()
        val foreground = JobForeground(listOf(runs.foregroundWork()), android, fromKjb = { it == "kjb" }, answered = { answers += it })
        foreground.start(1, "kjb")
        foreground.timeUp(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        // A lookup joins the run that goes on, and Android lets the add-on in again: it holds the foreground for both, and
        // the refusal KJB is told about is over.
        foreground.start(2, "kjb")

        assertEquals(listOf("startForeground", "stopForeground", "stopSelf all", "startForeground"), android.calls)
        assertEquals(listOf(false, true, false), answers)
    }

    @Test
    fun `a lookup stopped before it started still ends its busy time, so the foreground service is left`() = runTest {
        ytdlp.scripts += Script.Hang()
        val runs = runs()

        val lookup = runs.info("https://video.example.com/w", Looked())
        runs.cancelInfo(lookup)
        runCurrent()

        assertFalse(runs.busy)
        assertEquals(1, ends)
        assertTrue("yt-dlp never ran", ytdlp.calls.isEmpty())
    }

    @Test
    fun `a refusal of the foreground service is told for a day, and a time after now is not believed`() {
        assertFalse(RefusalPolicy.standing(refusedAt = null, now = now))
        assertTrue(RefusalPolicy.standing(refusedAt = now - 60_000, now = now))
        assertTrue(RefusalPolicy.standing(refusedAt = now - day + 1, now = now))
        assertFalse("Android's count has started over by then", RefusalPolicy.standing(refusedAt = now - day, now = now))
        assertFalse(RefusalPolicy.standing(refusedAt = now + 60_000, now = now))
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
        listOf(DlFailure.OFFLINE, DlFailure.BLOCKED, DlFailure.NO_ROOM, DlFailure.HAND_OFF, DlFailure.ENGINE, DlFailure.REFUSED).forEach {
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
