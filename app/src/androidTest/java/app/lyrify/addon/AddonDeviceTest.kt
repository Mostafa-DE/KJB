package app.lyrify.addon

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.ai.AiModels
import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.api.IAiAddon
import app.lyrify.ai.api.ImportReply
import app.lyrify.data.SharedWrites
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.models.ModelState
import app.lyrify.tags.TagLibFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * KJB dev and the AI add-on (app.lyrify.ai.debug, signed with the same debug key) on a phone: the bind with its checks and
 * handshake, one song through the add-on, the add-on's process dying, and the handover. Needs both installed; the run
 * also needs the add-on's models and a song in Music/KJB test/, and the real handover KJB dev's own copies of the models
 * (each is skipped without them). Writes nothing outside KJB dev's own cache.
 *
 * The run needs the add-on's models, which the real handover gives it: in the class the handover runs first (by name),
 * and the run, run alone, hands KJB dev's models over itself while they are still KJB dev's. The run takes the song
 * `-e song "<path in Music/KJB test>"` names, else the first one with lyrics (a `.txt`, a synced `.lrc`, a tag), so that
 * Whisper and the timing run ([pick]); logcat says how it was picked, never its name or path.
 *
 * The run also checks, from `dumpsys activity services`, that the add-on works in its own foreground service while the
 * job runs (mediaProcessing from Android 15, dataSync before it), that KJB binds it with BIND_IMPORTANT, and that it
 * leaves the foreground once the job ended: a phone's app freezer stopped it mid-job without them (phone session 10).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AddonDeviceTest {
    /**
     * A hang fails the test instead of holding a phone session, with the stuck thread's stack. Longer than the run's own
     * limits, so that they are what ends a blocked run (session 9: the MDX session blocked while another app held the
     * NPU): the add-on's 8 minutes for the MDX session (NpuStages.MDX_CREATE_MS) and KJB's 10 minutes for a first load
     * (RemoteSongEngine.FIRST_LOAD_MS). The slowest first song seen took about 5.6 minutes; the other tests take seconds.
     */
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(12, TimeUnit.MINUTES).withLookingForStuckThread(true).build()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun connect(): AddonSession = runBlocking {
        when (val connection = Addon.client(context).connect()) {
            is Connection.Open -> connection.session
            is Connection.Closed -> throw AssertionError("the add-on is ${connection.state}: install app.lyrify.ai.debug, signed with the debug key")
        }
    }

    @Test
    fun bindsTheAddonAfterItsChecksAndSpeaksItsApi() {
        assertEquals(null, Addon.client(context).peek())
        connect().use { session ->
            assertEquals(IAiAddon.VERSION, session.api)
            val status = checkNotNull(session.port.status())
            assertTrue(status.runtime, status.runtime.startsWith("ONNX Runtime"))
            assertTrue(session.alive)
        }
    }

    @Test
    fun runsOneSongInTheAddon() {
        val song = pick(File(Environment.getExternalStorageDirectory(), SharedWrites.TEST_FOLDER))
        assumeTrue("a song in ${SharedWrites.TEST_FOLDER}", song != null)
        checkNotNull(song)
        Log.i(TAG, "song: ${song.how}, ${song.lyrics?.lines()?.size ?: 0} lines of plain lyrics")
        connect().use { session ->
            if (File(ModelHandover.legacyFolder(context), AiModels.MDX).isFile) handOverKjbsModels(session)
            assumeTrue("the add-on's models", session.port.status()?.models is ModelState.Ready)
            val stages = mutableListOf<AiStage>()
            var chunks = 0
            // The add-on's service as the separation starts: the step (creating the MDX session) a phone froze it in.
            var duringJob: String? = null
            // The add-on's notes (stages, times, counts; no lyrics) in logcat: a run that stalls shows its last step.
            val result = RemoteSongEngine(session, notes = { Log.i(TAG, it) }).use { engine ->
                runBlocking {
                    engine.run(LocalTrack(song.file.path, "Test", null, null, song.durationMs), song.lyrics, { stage ->
                        stages += stage
                        if (stage == AiStage.SEPARATE) duringJob = AddonServices.once(KjbAddons.AI, foreground = true)
                    }) { chunks++ }
                }
            }
            Log.i(TAG, "result: ${result.javaClass.simpleName}, stages $stages, $chunks chunks")
            val during = checkNotNull(duringJob) { "the job never reached the separation: $stages, ${result.javaClass.simpleName}" }
            Log.i(TAG, "the add-on during the job: ${AddonServices.summary(during)}")
            assertTrue(during, "isForeground=true" in during)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) AddonServices.MEDIA_PROCESSING else AddonServices.DATA_SYNC
            assertTrue("$type: $during", "types=$type" in during)
            assertTrue("bound with BIND_AUTO_CREATE and BIND_IMPORTANT: $during", "CR IMP " in during)
            val after = AddonServices.once(KjbAddons.AI, foreground = false)
            assertFalse("out of the foreground once the job ended: $after", "isForeground=true" in after)
            if (result is AiResult.Failed && result.npu) {
                // The NPU did not answer in time (another app holding it): the add-on's deadline ended the run cleanly,
                // an NPU failure KJB records nothing for (the song is tried again later), and once KJB lets go the add-on
                // ends its process, the step it left behind with it.
                assertTrue(result.message, result.message.startsWith("NpuBusyException"))
                session.close()
                runBlocking { withTimeout(15_000) { session.death.await() } }
                return
            }
            // A failure's message holds no lyrics; a result's kind and the stages are all said of the others.
            assertTrue(result.toString(), result !is AiResult.Failed)
            assertEquals(AiStage.DECODE, stages.first())
            assertTrue("$stages", AiStage.SEPARATE in stages)
            if (song.lyrics != null) {
                // A song with lyrics is sung: Whisper runs on its vocal chunks, and the lyrics are timed unless it is heard as an instrumental.
                assertTrue("$stages, $chunks chunks", AiStage.TRANSCRIBE in stages && chunks > 0)
                assertTrue("${result.javaClass.simpleName}, $stages", result is AiResult.Instrumental || AiStage.ALIGN in stages)
            }
        }
    }

    /** The song the run takes, its length, and its plain lyrics if it has any. [how] it was picked is all logcat gets. */
    private class Pick(val file: File, val durationMs: Long, val lyrics: String?, val how: String)

    /**
     * The song `-e song "<path in [music]>"` names (refused when it is not a song file inside [music]), else the first
     * song by name with lyrics (a `.txt` or a synced `.lrc` beside it, or a lyrics tag), so that the vocal path runs, else
     * the first song; null when there is none.
     */
    private fun pick(music: File): Pick? {
        val named = InstrumentationRegistry.getArguments().getString("song")
        if (named != null) {
            val file = File(music, named).canonicalFile
            check(file.isFile && file.path.startsWith(music.canonicalPath + File.separator)) { "-e song names no song file inside the test folder" }
            return Pick(file, durationMs(file), plainLyrics(file, music), "named by -e song")
        }
        val songs = music.listFiles { file -> file.isFile && file.extension.lowercase() in SONGS }?.sortedBy { it.name }.orEmpty()
        songs.firstNotNullOfOrNull { file -> plainLyrics(file, music)?.let { Pick(file, durationMs(file), it, "the first with lyrics") } }?.let { return it }
        return songs.firstOrNull()?.let { Pick(it, durationMs(it), null, "the first song, none has plain lyrics") }
    }

    /**
     * [song]'s plain lyrics: a `.txt` beside it, else a synced `.lrc` beside it, else its lyrics tag. Synced lyrics give
     * one line per time, in playing order ([LrcFormat.parseLrclib]), so a line with several times (a chorus) is there
     * each time it is sung; word times and tag lines (`[ar:...]`) are taken off. Null when it has none.
     */
    private fun plainLyrics(song: File, music: File): String? {
        fun beside(extension: String) = File(song.parentFile, "${song.nameWithoutExtension}.$extension").takeIf { it.isFile }?.readText()
        val text = beside("txt") ?: beside("lrc")
            ?: runCatching { TagLibFile(music).read(song)?.fields }.getOrNull()?.let { it["LYRICS"] ?: it["UNSYNCEDLYRICS"] }?.firstOrNull()
            ?: return null
        val lines = if (LrcFormat.timed(text)) LrcFormat.parseLrclib(text).map { it.text } else text.lines().filterNot { LRC_TAG.matches(it.trim()) }
        return lines.map { it.replace(LRC_WORD_TIME, "").trim() }.filter { it.isNotEmpty() }.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    /** [song]'s length as its file says, for the LRC's check; 3 minutes when it does not say. */
    private fun durationMs(song: File): Long = MediaMetadataRetriever().use { retriever ->
        runCatching {
            retriever.setDataSource(song.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull()
    } ?: 180_000L

    @Test
    fun theAddonDyingIsACleanErrorAndANewBindWorks() {
        val session = connect()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am force-stop app.lyrify.ai.debug").close()
        runBlocking { withTimeout(10_000) { session.death.await() } }
        assertFalse(session.alive)
        assertTrue(runCatching { session.port.apiVersion() }.exceptionOrNull() is AddonDiedException)
        session.close()

        connect().use { again -> assertEquals(IAiAddon.VERSION, again.api) }
    }

    /** A made-up file under a model's name: the add-on refuses it before it writes a byte, and KJB keeps its copy. */
    @Test
    fun aFileThatIsNotThePinnedModelIsRefusedAndKjbKeepsItsCopy() {
        val folder = File(context.cacheDir, "handover-test").apply { deleteRecursively(); mkdirs() }
        File(folder, AiModels.MDX).writeBytes(ByteArray(1024) { 7 })
        connect().use { session ->
            val end = runBlocking {
                Handover(folder, send = { file, sha -> session.port.importModel(file.name, file.file, sha) }, sha256 = ::sha256, markDone = {}).run()
            }
            assertTrue(end.toString(), end is HandoverEnd.GaveUp)
            assertTrue(File(folder, AiModels.MDX).exists())
            assertEquals(ImportReply.Outcome.REFUSED, session.port.importModel("../notes.txt", File(folder, AiModels.MDX), "00").outcome)
        }
        folder.deleteRecursively()
    }

    /**
     * The real handover: KJB dev's own copies of the models (pushed into its files/models by the phone session) go to the
     * add-on, which then has them in place, and only then are KJB dev's copies gone.
     */
    @Test
    fun kjbsOwnModelsGoOverAndOnlyThenAreDeleted() {
        assumeTrue("KJB dev's own models in files/models", File(ModelHandover.legacyFolder(context), AiModels.MDX).isFile)
        connect().use(::handOverKjbsModels)
    }

    /** KJB dev's own models to the add-on: KJB's copies stay while the files go over, and go once all are taken. */
    private fun handOverKjbsModels(session: AddonSession) {
        val legacy = ModelHandover.legacyFolder(context)
        val end = runBlocking {
            Handover(legacy, send = { file, sha ->
                assertTrue("KJB's copies are there while files go over", legacy.exists())
                session.port.importModel(file.name, file.file, sha)
            }, sha256 = ::sha256, markDone = {}).run()
        }
        assertEquals(HandoverEnd.Done, end)
        assertFalse(legacy.exists())
        assertTrue(session.port.status()?.models is ModelState.Ready)
    }

    /** Streamed: Whisper's encoder is about 0.8 GB. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "AddonDeviceTest"

        val SONGS = setOf("mp3", "m4a", "flac", "ogg")

        /** An enhanced LRC's word times, `<mm:ss.xx>`. */
        val LRC_WORD_TIME = Regex("""<\d+:\d+(?:[.:]\d+)?>""")

        /** An LRC tag line, `[ar:...]`, `[offset:...]`: not lyrics. */
        val LRC_TAG = Regex("""\[[A-Za-z#]+:[^\]]*]""")
    }
}
