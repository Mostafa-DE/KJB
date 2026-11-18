package app.lyrify.overnight

import app.lyrify.addon.AddonDiedException
import app.lyrify.addon.AddonFrozenException
import app.lyrify.addon.AddonStalledException
import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LedgerStore
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.AiPaths
import app.lyrify.lyrics.EmbeddedLyrics
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.PlainLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * CPU threads the engine uses in the AI add-on (its ENGINE_THREADS): the MDX STFT and iSTFT (the spike's measured 4) and
 * wav2vec2. The models themselves run on the NPU. For the run log.
 */
const val AI_THREADS: Int = 4

/**
 * The engine of one run, in the AI add-on ([app.lyrify.addon.RemoteSongEngine]): its models stay loaded from the first
 * song until it is closed, at the end of the run.
 */
interface SongEngine : AutoCloseable {
    /**
     * One song (the add-on's LyricsFromAudio.run): [onStage] hears each stage it enters, [beforeChunk] is awaited before
     * each Whisper chunk. Throws [AddonDiedException] when the add-on's process dies meanwhile.
     */
    suspend fun run(track: LocalTrack, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult
}

/**
 * One song for the AI. With [lrclibId], LRCLIB's plain lyrics of this very recording are timed; with [plainInTags], those
 * the song's file holds in its own tags; with neither, the song is transcribed.
 */
data class AiJob(val track: LocalTrack, val lrclibId: Long?, val plainInTags: Boolean = false) {
    /** Known lyrics are timed, not heard. */
    val times: Boolean get() = lrclibId != null || plainInTags
}

/**
 * Which songs the AI takes, in which order: songs whose plain lyrics of this recording are known, LRCLIB's or those in
 * their own tags (they can be timed), first, then songs LRCLIB has nothing for and the other plain-only ones, each most
 * recently played first (by the ledger's last update), then failed runs due again, the longest waiting first.
 */
object AiQueue {
    /**
     * [entry] waits for the AI at [now]: no lyrics and no AI verdict yet, or a failed run whose retry is due. LRCLIB's
     * lyrics beat the AI's, so a song an older lookup found nothing for waits for this version's lookup first
     * ([LedgerEntry.lookupStale]); a song waiting for the user's check is not the AI's either.
     */
    fun wants(entry: LedgerEntry, now: Long): Boolean =
        entry.status.waitsForAi && !entry.lookupStale && entry.aiRetryAt.let { it == null || now >= it }

    /**
     * The songs to run, in order; [canTake] (blocking) says whether a song's audio is there without an `.lrc`.
     * Without [align], songs whose plain lyrics would be timed are left waiting (the crash guard turned timing off).
     */
    fun of(ledger: LedgerState, now: Long, canTake: (String) -> Boolean, align: Boolean = true): List<AiJob> =
        ledger.entries.values.filter { wants(it, now) && (align || !timeable(it)) && canTake(it.path) }.sortedWith(ORDER).map(::job)

    fun job(entry: LedgerEntry): AiJob = AiJob(entry.track, entry.lrclibId.takeIf { timeable(entry) }, plainInTags = entry.plainInTags && timeable(entry))

    /** What counts as done for the AI: lyrics written, or a final verdict. A failed run is not done (it is tried again). */
    val DONE: Set<LyricsStatus> = setOf(LyricsStatus.AI_TRANSCRIBED, LyricsStatus.AI_ALIGNED, LyricsStatus.AI_INSTRUMENTAL, LyricsStatus.AI_REJECTED)

    /** Songs the AI got done ([DONE]) since [since], for the card. */
    fun doneSince(ledger: LedgerState, since: Long): Int = ledger.entries.values.count { it.status in DONE && (it.lastAttemptAt ?: 0) >= since }

    /**
     * Plain lyrics of this recording are known (LRCLIB's, or the song's own in its tags), or LRCLIB's of another length
     * were marked to be timed anew ([LedgerEntry.retime]).
     */
    private fun timeable(entry: LedgerEntry): Boolean =
        entry.durationMatched == true && (entry.lrclibId != null || entry.plainInTags) || entry.retime && entry.lrclibId != null

    private fun group(entry: LedgerEntry): Int = when {
        entry.status == LyricsStatus.AI_FAILED -> 2
        entry.status == LyricsStatus.PLAIN_ONLY && timeable(entry) -> 0
        else -> 1
    }

    private val ORDER: Comparator<LedgerEntry> = compareBy<LedgerEntry> { group(it) }
        .thenBy { if (it.status == LyricsStatus.AI_FAILED) it.aiRetryAt ?: 0 else -it.updatedAt }
        .thenBy { it.path }

}

/** Whether the audio file at [path] is there with nothing (no `.lrc`, no link) where its `.lrc` would go. Blocking. */
fun audioWithoutLyrics(path: String): Boolean {
    val sidecar = LrcFormat.sidecarPath(path) ?: return false
    return File(path).isFile && !Files.exists(File(sidecar).toPath(), LinkOption.NOFOLLOW_LINKS)
}

/**
 * What a run is doing: [done] of [total] songs got lyrics or a final verdict ([AiQueue.DONE]; failed and skipped songs
 * do not count), the one it is on, or waiting for the phone to cool down.
 */
data class AiProgress(val done: Int, val total: Int, val current: String?, val hot: Boolean = false)

/** Why a run ended. */
enum class AiRunEnd {
    /** Every song in the queue was handled. */
    DONE,

    /** The charger came off: the current step finished and nothing was recorded for its song. */
    UNPLUGGED,

    /** The models are gone (deleted meanwhile). */
    NO_MODELS,

    /** A model did not load: it was discarded, to be downloaded again; the song keeps its status. */
    BAD_MODEL,

    /** The same model file did not load again after it was downloaded again: it is kept, and the AI pauses ([ModelLoadFailures]). */
    MODEL_FAILED,

    /**
     * The app died, or the AI add-on died or stopped answering, in the same stage too often in a row ([GuardVerdict.HOLD]):
     * the AI waits for the next version.
     */
    CRASHED,

    /** The AI add-on's process died during a song (counted for the song like a crash); the next run binds it again. */
    ADDON_DIED,

    /** The phone's NPU could not be used at all (no QNN execution provider or NPU device); the next period tries again. */
    NO_NPU,

    /** The phone froze the AI add-on during a song ([AddonFrozenException]); nothing recorded, the next period tries again. */
    FROZEN,
}

/**
 * One night's run of the AI over [AiQueue]. Before each song and before each Whisper chunk it waits while the phone is
 * warmer than LIGHT ([MAX_THERMAL_STATUS]), and it stops when it is unplugged. The engine is opened ([openEngine]) for
 * the first song that runs and closed when the run ends, so its models stay loaded across songs; a song takes under a
 * minute on the NPU. When the charger comes off or the run is cancelled, the engine finishes the step it is on and stops;
 * nothing is recorded for that song (a later run does it again from the start). Every result goes to [record] (the
 * pipeline's write path, which checks the song again under its per-file exclusivity); the engine itself writes no
 * lyrics. From its check to that write a song is in [aiPaths], so the queue sweep leaves it alone. The whole run holds
 * [inUse]: one run at a time, and the models are deleted only once a run has let go of them ([whenStopped]).
 *
 * The crash guard ([CrashGuard]): a song the app died in [CrashGuard.MAX_DEATHS] times is recorded as failed (and tried
 * again after the failed-run wait) instead of killing every run; deaths in a row in the align stage leave the songs to
 * time waiting, in another stage they stop the AI ([AiRunEnd.CRASHED]), until the next version of the app. The engine
 * runs in the AI add-on's process: when that dies during a song (a native crash, out of memory, killed), KJB lives on,
 * counts the death of the song in its stage as the next run would have counted its own, and ends the run
 * ([AiRunEnd.ADDON_DIED]). When it stops answering during a song ([AddonStalledException], the engine's watchdog), that
 * song is recorded as failed with the reason and counted as a death in its stage, and the run goes on with the add-on
 * bound anew. When the phone freezes it during a song ([AddonFrozenException]), as with an NPU another app holds, nothing
 * is recorded or counted and the run ends ([AiRunEnd.FROZEN]): the song is tried again later. A model that does not load
 * counts as a death of the song in its stage too; it is downloaded again once, then the AI pauses ([ModelLoadFailures]).
 * A song whose audio file was replaced during its run is abandoned: nothing is recorded.
 * [runLog] gets one line per event: titles, stages, times, counts, never lyrics. It is the AI's run log ([AiRunLog]), in
 * the app's own files and shown on the phone; logcat repeats it only under VERBOSE, as it names songs.
 */
class AiRunner(
    private val ledger: LedgerStore,
    /** LRCLIB's plain lyrics of a record, fetched when a song is timed. */
    private val plainLyrics: suspend (Long) -> PlainLyrics,
    private val record: suspend (LocalTrack, AiResult) -> LyricsStatus?,
    /** The engine in the add-on, opened for the first song that runs and closed at the end of the run. */
    private val openEngine: suspend () -> SongEngine,
    /** Whether the add-on has every model in place. */
    private val ready: suspend () -> Boolean,
    /** `PowerManager.currentThermalStatus`. */
    private val thermalStatus: () -> Int,
    private val pluggedIn: Flow<Boolean>,
    private val clock: () -> Long,
    /** The crash guard's records. */
    private val guard: CrashGuard,
    /** The models' load failures: a file that does not load is downloaded again once, then the AI pauses. */
    private val loadFailures: ModelLoadFailures,
    /** Where the engine's blocking work runs. */
    private val engineContext: CoroutineContext = Dispatchers.Default,
    private val canTake: (String) -> Boolean = ::audioWithoutLyrics,
    /** The songs the AI is working on, which the queue sweep skips (the pipeline's). */
    private val aiPaths: AiPaths = AiPaths(),
    /** Held by the run while it may use the models (the process's one, so a cancelled run's engine is waited for). */
    private val inUse: Mutex = Mutex(),
    /** A model (its AiModels name) that did not load for the first time ([loadFailures]): deleted, to be downloaded again. */
    private val badModel: suspend (String) -> Unit = {},
    private val onProgress: suspend (AiProgress) -> Unit = {},
    private val runLog: (String) -> Unit = {},
    /**
     * The plain words a song's file holds in its own tags ([EmbeddedLyrics]), read when the song is timed; null for none.
     * Throws an IOException when the file cannot be read now. Blocking.
     */
    private val tagLyrics: (String) -> String? = { path -> EmbeddedLyrics.readOrThrow(File(path))?.words?.takeIf(String::isNotBlank) },
) {
    private var engine: SongEngine? = null

    /** The run. One cancelled a moment ago may still be finishing its step: this one starts once that has let go of the models. */
    suspend fun run(): AiRunEnd = inUse.withLock {
        try {
            songs()
        } finally {
            closeEngine()
        }
    }

    /**
     * The plain lyrics [job] times: LRCLIB's by its record, or those in the song's own tags (None when gone since,
     * Unavailable while its file cannot be read); null to transcribe.
     */
    private suspend fun knownLyrics(job: AiJob): PlainLyrics? = when {
        job.lrclibId != null -> plainLyrics(job.lrclibId)
        job.plainInTags -> try {
            withContext(Dispatchers.IO) { tagLyrics(job.track.path) }?.let(PlainLyrics::Found) ?: PlainLyrics.None
        } catch (e: IOException) {
            PlainLyrics.Unavailable(e.javaClass.simpleName)
        }
        else -> null
    }

    private fun closeEngine() {
        engine?.let { runCatching { it.close() }.onFailure { e -> runLog("AI: the engine did not close cleanly (${e.javaClass.simpleName})") } }
        engine = null
    }

    private suspend fun songs(): AiRunEnd {
        guard.collectDeaths().forEach {
            runLog("AI: ${it.title}: the app died during ${it.stage.label()} (${it.deaths} of ${CrashGuard.MAX_DEATHS} for this song)")
        }
        val verdict = guard.verdict()
        if (verdict == GuardVerdict.HOLD) {
            runLog("AI: held: the app died ${CrashGuard.MAX_DEATHS_IN_A_ROW} times in a row in one stage; waiting for the next version")
            return AiRunEnd.CRASHED
        }
        if (verdict == GuardVerdict.NO_ALIGN) {
            runLog("AI: timing known lyrics is off: it killed the app ${CrashGuard.MAX_DEATHS_IN_A_ROW} times in a row; those songs wait for the next version")
        }
        val state = ledger.ledger.first()
        if (!state.readable) {
            // Nothing is known about any song; the crash guard's records are kept for when it reads again.
            runLog("AI: the song list cannot be read; nothing runs")
            return AiRunEnd.DONE
        }
        // Timing known lyrics can be turned off during the run too (its stage stalled too often in a row).
        var align = verdict != GuardVerdict.NO_ALIGN
        val jobs = AiQueue.of(state, clock(), canTake, align = align)
        guard.prune(state)
        runLog("AI: ${jobs.size} songs waiting")
        var done = 0
        for (job in jobs) {
            if (!pluggedIn.first() || coolDown(AiProgress(done, jobs.size, current = null, hot = true), "before the next song") == null) {
                return AiRunEnd.UNPLUGGED
            }
            if (!ready()) return AiRunEnd.NO_MODELS
            aiPaths.hold(job.track.path)
            try {
                // The song may have changed since the queue was made: lyrics found or removed, the file gone.
                val entry = ledger.entry(job.track.path)?.takeIf { AiQueue.wants(it, clock()) && canTake(it.path) }
                if (entry == null) {
                    runLog("AI: ${job.track.title} skipped, it changed")
                    continue
                }
                val current = AiQueue.job(entry)
                val title = current.track.title
                if (!align && current.times) {
                    runLog("AI: $title waits, timing known lyrics is off")
                    continue
                }
                val song = guard.open(entry)
                if (song.deaths >= CrashGuard.MAX_DEATHS) {
                    val stage = song.diedIn?.label() ?: "a run"
                    val status = record(current.track, AiResult.Failed("the app died during $stage ${song.deaths} times"))
                    guard.drop(entry.path)
                    runLog("AI: $title: given up for now, the app died during it ${song.deaths} times (last during $stage); now $status")
                    continue
                }
                val plain = when (val lyrics = knownLyrics(current)) {
                    null, PlainLyrics.None -> null
                    is PlainLyrics.Found -> lyrics.text
                    is PlainLyrics.Unavailable -> {
                        runLog("AI: $title waits, ${if (current.plainInTags) "its file" else "LRCLIB"} unavailable (${lyrics.cause})")
                        continue
                    }
                }
                val working = AiProgress(done, jobs.size, title)
                onProgress(working)
                runLog("AI: $title: ${if (plain == null) "transcribing" else "timing known lyrics"}")
                val started = clock()
                val songEngine = engine ?: openEngine().also { engine = it }
                val result = try {
                    unlessUnplugged {
                        songEngine.run(current.track, plain, song::entering) { beforeChunk(working) }
                    }
                } catch (e: CancellationException) {
                    runLog("AI: $title: stopped")
                    throw e
                } catch (_: AddonDiedException) {
                    // As if the app had died there before the AI moved out: the song's death in its stage is counted now.
                    song.died()
                    closeEngine()
                    val held = guard.verdict() == GuardVerdict.HOLD
                    runLog("AI: $title: the AI add-on stopped during ${song.diedIn?.label() ?: "a run"} (${song.deaths} of ${CrashGuard.MAX_DEATHS} for this song)")
                    return if (held) AiRunEnd.CRASHED else AiRunEnd.ADDON_DIED
                } catch (e: AddonStalledException) {
                    // The add-on stopped answering: its job was cancelled and the add-on let go of (it ends its own
                    // process if the step never ends). The song fails with the reason, tried again after the failed
                    // runs' wait; it counts as a death in its stage too, so a stage that always hangs holds the AI (or,
                    // timing lyrics, turns timing off). The next song binds the add-on anew. A song whose audio file
                    // was replaced meanwhile is another recording: nothing is recorded or counted for it.
                    closeEngine()
                    val during = e.stage?.label() ?: "its start"
                    if (song.audioChanged()) {
                        song.ended()
                        guard.drop(entry.path)
                        runLog("AI: $title: the AI add-on stopped answering during $during, and its audio file changed; nothing recorded, it starts over in a later run")
                        continue
                    }
                    song.died()
                    val status = record(current.track, AiResult.Failed("${e.javaClass.simpleName}: ${e.message}"))
                    runLog("AI: $title: the AI add-on stopped answering during $during (nothing for ${seconds(e.silentMs)}); now $status")
                    when (guard.verdict()) {
                        GuardVerdict.HOLD -> return AiRunEnd.CRASHED
                        GuardVerdict.NO_ALIGN -> align = false
                        GuardVerdict.NONE -> {}
                    }
                    continue
                } catch (e: AddonFrozenException) {
                    // The phone stopped the add-on's process, not the song nor a crash: nothing is recorded or counted,
                    // and the run ends, the add-on let go of; the next hourly look tries again.
                    closeEngine()
                    runLog("AI: $title: your phone paused the AI add-on during ${e.stage?.label() ?: "its start"} (nothing for ${seconds(e.silentMs)}); nothing recorded, trying again later")
                    return AiRunEnd.FROZEN
                } finally {
                    song.ended()
                }
                if (result == null) {
                    runLog("AI: $title: stopped, unplugged")
                    return AiRunEnd.UNPLUGGED
                }
                if (result is AiResult.Failed && result.npu) {
                    // The phone's NPU, not the song: nothing is recorded, and the next period tries again.
                    runLog("AI: the phone's NPU could not be used (${result.message}); trying again later")
                    return AiRunEnd.NO_NPU
                }
                val model = (result as? AiResult.Failed)?.model
                if (result is AiResult.Failed && model != null) {
                    // Not the song's fault: it keeps its status, and without that model nothing else can run. It counts as
                    // a death in the stage it was in all the same, so a song is not run forever.
                    song.died()
                    if (loadFailures.failed(model)) {
                        runLog("AI: $model did not load (${result.message}); deleted, to be downloaded again")
                        closeEngine()
                        badModel(model)
                        return AiRunEnd.BAD_MODEL
                    }
                    runLog(
                        "AI: $model did not load again after it was downloaded again (${result.message}); kept, " +
                            "and the AI is paused until the next version of KJB or until the models are deleted",
                    )
                    return AiRunEnd.MODEL_FAILED
                }
                // Replaced while the run waited (LRCLIB, the heat) or worked: what it made is of another recording.
                if (song.audioChanged()) {
                    guard.drop(entry.path)
                    runLog("AI: $title: its audio file changed during the run; nothing recorded, it starts over in a later run")
                    continue
                }
                val status = record(current.track, result)
                guard.completed(entry.path)
                if (status in AiQueue.DONE) done++
                runLog("AI: $title: ${describe(result)} after ${seconds(clock() - started)}, now $status")
            } finally {
                aiPaths.release(job.track.path)
            }
        }
        return AiRunEnd.DONE
    }

    /** Before a Whisper chunk: waits while the phone is too warm, showing it; unplugged meanwhile, it waits to be stopped. */
    private suspend fun beforeChunk(working: AiProgress) {
        val waited = coolDown(working.copy(hot = true), "before the next chunk") ?: awaitCancellation()
        if (waited) onProgress(working)
    }

    /**
     * Waits while the phone is warmer than LIGHT, showing [hot] meanwhile, and logs how long ([where] it waited).
     * Whether it waited; null when it was unplugged meanwhile.
     */
    private suspend fun coolDown(hot: AiProgress, where: String): Boolean? {
        val start = clock()
        var hottest = 0
        while (true) {
            val status = thermalStatus()
            if (status <= MAX_THERMAL_STATUS) break
            if (hottest == 0) {
                onProgress(hot)
                runLog("AI: the phone is warm (thermal status $status), waiting $where")
            }
            hottest = maxOf(hottest, status)
            val unplugged = withTimeoutOrNull(THERMAL_POLL_MS) { pluggedIn.first { !it } } != null
            if (unplugged) {
                runLog("AI: unplugged while waiting for the phone to cool down")
                return null
            }
        }
        if (hottest == 0) return false
        runLog("AI: cooled down after ${minutes(clock() - start)} (thermal status up to $hottest) $where")
        return true
    }

    /** [block] on [engineContext], cancelled when the charger comes off; null then, once it has stopped. */
    private suspend fun unlessUnplugged(block: suspend () -> AiResult): AiResult? = coroutineScope {
        val work = async(engineContext) { block() }
        val watch = launch {
            pluggedIn.first { !it }
            runLog("AI: unplugged, stopping after the current step")
            work.cancel()
        }
        try {
            // Waits for the engine to stop, after the chunk it is on, even when it was cancelled.
            work.await()
        } catch (e: CancellationException) {
            // The run itself was cancelled: on up. Else the charger came off.
            currentCoroutineContext().ensureActive()
            null
        } finally {
            watch.cancel()
        }
    }

    /** For the run log: no lyrics, and of a failure only its kind (a message could quote what Whisper heard). */
    private fun describe(result: AiResult): String = when (result) {
        is AiResult.Transcribed -> "transcribed, ${result.stats.lines} lines"
        is AiResult.Aligned -> "timed, agreement %.2f".format(Locale.ROOT, result.agreement)
        is AiResult.Instrumental -> "instrumental, %.1f words per minute".format(Locale.ROOT, result.wordsPerMinute)
        is AiResult.Rejected -> "rejected (${result.reason}${result.agreement?.let { ", agreement %.2f".format(Locale.ROOT, it) }.orEmpty()})"
        is AiResult.Failed -> "failed (${result.kind})"
    }

    private fun AiStage.label(): String = name.lowercase()

    private fun minutes(ms: Long): String = "%d min %02d s".format(Locale.ROOT, ms / 60_000, ms / 1000 % 60)

    private fun seconds(ms: Long): String = "%.1f s".format(Locale.ROOT, ms / 1000.0)

    companion object {
        /** `PowerManager.THERMAL_STATUS_LIGHT`: warmer than this, the next song or chunk waits. */
        const val MAX_THERMAL_STATUS: Int = 1

        /** How often the thermal status is read again while waiting. */
        const val THERMAL_POLL_MS: Long = 60_000
    }
}

/**
 * How often each model file failed to load, per model (its AiModels name) and SHA-256 ([pinned]: a file in place is the
 * pinned one, checked when it was downloaded; the add-on tells the pins), under app version [version], kept across runs
 * in app-private storage ([read] and [write] one string, null for none). A file that does not load is deleted and downloaded again once; the same file failing again
 * would only fail again (the phone cannot run it: its memory, the runtime), so the AI pauses instead ([paused]) until
 * the next version of the app or until the user deletes the models ([clear]).
 */
class ModelLoadFailures(
    private val version: Int,
    private val pinned: (String) -> String?,
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {
    /** Counts a load failure of [model]: true when it is its file's first (download it again), false when not (pause). */
    fun failed(model: String): Boolean {
        val state = state()
        val key = "$model ${pinned(model)}"
        val count = (state.counts[key] ?: 0) + 1
        write(json.encodeToString(State.serializer(), state.copy(counts = state.counts + (key to count))))
        return count < MAX_LOAD_FAILURES
    }

    /** Whether a model file failed to load [MAX_LOAD_FAILURES] times under this version: the AI does not run. */
    fun paused(): Boolean = state().counts.values.any { it >= MAX_LOAD_FAILURES }

    /** The models were deleted: the files downloaded anew start with no failures. */
    fun clear() = write(null)

    private fun state(): State {
        val saved = try {
            read()?.let { json.decodeFromString(State.serializer(), it) }
        } catch (_: IllegalArgumentException) {
            // A SerializationException too: another format.
            null
        }
        return saved?.takeIf { it.version == version } ?: State(version)
    }

    @Serializable
    private data class State(val version: Int, val counts: Map<String, Int> = emptyMap())

    companion object {
        /** Load failures of one file that pause the AI; after each one before, the file is downloaded again. */
        const val MAX_LOAD_FAILURES: Int = 2

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * [block] once a stopped AI run has let go of the models: WorkManager no longer runs it ([running] false; it counts
 * cancelled work as stopped at once) and [inUse] is free (a run holds it until its engine has returned and closed, which
 * a cancelled run's does only after the step it is on). [block] runs holding [inUse], so no run starts meanwhile. Null
 * when that takes longer than [timeoutMs]: [block] did not run. However it ends, it lets go of [inUse].
 */
suspend fun <T> whenStopped(running: Flow<Boolean>, inUse: Mutex, timeoutMs: Long, block: suspend () -> T): T? {
    // This call's own hold: the timeout (asynchronous) or a cancellation can come after the lock was taken, before the
    // wait returns, and the hold must not outlive this call then either.
    val owner = Any()
    try {
        val stopped = withTimeoutOrNull(timeoutMs) {
            running.first { !it }
            inUse.lock(owner)
        } != null
        return if (stopped) block() else null
    } finally {
        if (inUse.holdsLock(owner)) inUse.unlock(owner)
    }
}

/** Why the AI could not start or go on although it is on and the phone is plugged in. */
enum class AiHold {
    /** The phone did not let it start in the background (its foreground service was refused). */
    NOT_ALLOWED,

    /** The daily time limit for long media work is used up; it goes on once the phone allows it again. */
    DAILY_LIMIT,

    /** The app died in the same step too often in a row ([AiRunEnd.CRASHED]); it waits for the next version. */
    CRASHED,

    /** The phone's NPU could not be used ([AiRunEnd.NO_NPU]); it tries again at the next hourly look. */
    NO_NPU,

    /** A model did not load, again after it was downloaded again ([AiRunEnd.MODEL_FAILED]); it waits for the next version or new models. */
    MODEL_FAILED,

    /** The phone froze the AI add-on at work ([AiRunEnd.FROZEN]); it tries again at the next hourly look. */
    FROZEN,
}

/** The AI card's progress line. */
sealed interface NightStatus {
    data object Off : NightStatus

    /** On, but the models are not downloaded yet. */
    data object NeedsModels : NightStatus

    data class Working(val done: Int, val total: Int, val title: String?) : NightStatus

    /** Paused between songs until the phone cools down. */
    data class Hot(val done: Int, val total: Int) : NightStatus

    data class WaitingForCharger(val waiting: Int) : NightStatus

    data class Held(val waiting: Int, val reason: AiHold) : NightStatus

    /** Plugged in, with songs waiting: the run starts at the next hourly look. */
    data class Waiting(val waiting: Int) : NightStatus

    data object AllDone : NightStatus
}

/** The progress line from the switch, the models, the run in progress (if any), a [hold], the charger and the [waiting] songs. */
fun nightStatus(enabled: Boolean, modelsReady: Boolean, progress: AiProgress?, hold: AiHold?, pluggedIn: Boolean, waiting: Int): NightStatus = when {
    !enabled -> NightStatus.Off
    progress != null -> if (progress.hot) NightStatus.Hot(progress.done, progress.total) else NightStatus.Working(progress.done, progress.total, progress.current)
    !modelsReady -> NightStatus.NeedsModels
    waiting == 0 -> NightStatus.AllDone
    // Paused until something changes, plugged in or not.
    hold == AiHold.MODEL_FAILED -> NightStatus.Held(waiting, hold)
    !pluggedIn -> NightStatus.WaitingForCharger(waiting)
    hold != null -> NightStatus.Held(waiting, hold)
    else -> NightStatus.Waiting(waiting)
}
