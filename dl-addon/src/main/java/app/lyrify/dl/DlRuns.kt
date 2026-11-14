package app.lyrify.dl

import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlMeta
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStage
import app.lyrify.dl.api.DlUpdate
import app.lyrify.dl.api.isDownloadKey
import app.lyrify.dl.api.isWebLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** What one run tells its caller: IDlRunCallback, as Kotlin. */
interface RunEvents {
    fun onProgress(progress: DlProgress)

    /** The finished file waits for [DlRuns.deliver]. */
    fun onFile(file: DlFile)

    /** The run's last word. */
    fun onEnd(end: DlEnd)
}

/** What one lookup tells its caller: IDlInfoCallback, as Kotlin. */
interface InfoEvents {
    /** The quick info (partial) first when there is one, then the full info, which ends the lookup. */
    fun onInfo(info: DlInfo)

    fun onFailed(end: DlEnd.Failed)
}

/** When yt-dlp last looked for an update and found it in place or put a newer one there (wall-clock millis). */
interface UpdateMemory {
    var lastCheck: Long?
}

/**
 * When yt-dlp updates itself (the design; VidGrab only updated when its main screen opened): before a run when it has not
 * looked for more than a day, and once after a failure an update can fix, unless that run looked already. Pure.
 */
object UpdatePolicy {
    const val STALE_MS: Long = 24 * 60 * 60_000

    /** Failures a newer yt-dlp may fix: a site that changed, a link it does not know yet, and what it did not say. */
    private val FIXABLE = setOf(DlFailure.SITE_CHANGED, DlFailure.NOT_FOUND, DlFailure.UNKNOWN)

    /** Before a run: never looked, more than a day ago, or a stored time after now (the clock went back). */
    fun beforeRun(lastCheck: Long?, now: Long): Boolean = lastCheck == null || now < lastCheck || now - lastCheck > STALE_MS

    fun afterFailure(failure: DlFailure, checkedThisRun: Boolean): Boolean = !checkedThisRun && failure in FIXABLE
}

/** What a run made in its folder: the media file, its cover (audio), and the tags yt-dlp printed. */
private class Produced(val media: File, val cover: File?, val meta: DlMeta, val durationSec: Int) {
    companion object {
        /** Files of a run that are not the finished media: parts, yt-dlp's notes, the cover and the printed tags. */
        private val NOT_MEDIA = setOf("part", "ytdl", "jpg", "jpeg", "webp", "png", "json", "txt", "temp", "tmp")

        /** The biggest finished file in [dir] and its cover; null when yt-dlp made none. */
        fun find(dir: File): Produced? {
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            val media = files.filter { it.extension.lowercase() !in NOT_MEDIA }.maxByOrNull { it.length() } ?: return null
            val covers = files.filter { it.extension.equals("jpg", ignoreCase = true) }
            val cover = covers.firstOrNull { it.nameWithoutExtension == media.nameWithoutExtension } ?: covers.firstOrNull()
            val (meta, seconds) = YtdlpLines.meta(File(dir, DlArgs.META_FILE))
            return Produced(media, cover, meta, seconds)
        }
    }
}

/**
 * The add-on's downloads and lookups (VidGrab's DownloadWorker and fetchInfo, fixed): each run has its own id and its own
 * events, and works in its key's folder under [folder], where what a stopped or failed run got stays for the next run of
 * that key (yt-dlp goes on from its parts) until KJB discards it or it is a week old ([sweep]). Cancel kills yt-dlp and its
 * ffmpeg ([Ytdlp.kill]): a process does not stop because its coroutine was cancelled. yt-dlp updates itself by
 * [UpdatePolicy], and never while a run or a lookup uses it. A finished file waits for KJB's descriptors ([deliver]) and
 * is streamed into them; the add-on never opens KJB's or the user's storage.
 */
class DlRuns(
    private val scope: CoroutineScope,
    private val ytdlp: Ytdlp,
    private val folder: File,
    private val memory: UpdateMemory,
    /** Wall-clock millis: the update policy's day, and the folders' age. */
    private val clock: () -> Long,
    /** Monotonic millis: how often progress goes to KJB. */
    private val monotonic: () -> Long,
    /** YouTube's quick oEmbed info for a link; null for other sites or when it fails. */
    private val quickInfo: suspend (String) -> DlInfo?,
    /** Where files are read and streamed. */
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var nextId = 1L
    private val runs = HashMap<Long, Run>()
    private val lookups = HashMap<Long, Job>()

    /** Held while yt-dlp updates; a run or lookup takes it briefly to start ([usingYtdlp]). */
    private val updating = Mutex()

    /** yt-dlp processes running now. Guarded by [lock]. */
    private var executing = 0

    private class Run(val id: Long, val request: DlRequest, val events: RunEvents) {
        lateinit var job: Job

        @Volatile
        var keepParts = false
        val delivery = CompletableDeferred<Sinks>()
    }

    /** KJB's descriptors for a run's file and its cover. */
    private class Sinks(val media: OutputStream, val cover: OutputStream?) {
        fun close() {
            runCatching { media.close() }
            runCatching { cover?.close() }
        }
    }

    /**
     * Starts [request] and returns its id at once. A request that is not valid (null), or whose key another run holds,
     * ends at once as refused.
     */
    fun start(request: DlRequest?, events: RunEvents): Long {
        val id: Long
        val run: Run?
        synchronized(lock) {
            id = nextId++
            run = request?.takeIf { asked -> runs.values.none { it.request.key == asked.key } }?.let { Run(id, it, events) }
            if (run != null) {
                runs[id] = run
                // ATOMIC: its body runs and settles the run even when the scope goes before it was dispatched.
                run.job = scope.launch(start = CoroutineStart.ATOMIC) { body(run) }
            }
        }
        if (run == null) events.onEnd(DlEnd.Failed(DlFailure.REFUSED))
        return id
    }

    /** KJB's descriptors for [run]'s finished file and its [cover]; a run not waiting for them closes them. */
    fun deliver(run: Long, media: OutputStream, cover: OutputStream?) {
        val sinks = Sinks(media, cover)
        val waiting = synchronized(lock) { runs[run] }
        if (waiting == null || !waiting.delivery.complete(sinks)) sinks.close()
    }

    /** Stops [run]: its processes are killed; [keepParts]: what it got stays for the next run of its key. */
    fun cancel(run: Long, keepParts: Boolean) {
        synchronized(lock) { runs[run] }?.let {
            it.keepParts = keepParts
            it.job.cancel()
        }
    }

    /** Stops every run, their parts kept: KJB let go of the add-on (it may come back to them). */
    fun cancelAll() {
        val (live, looking) = synchronized(lock) { runs.values.toList() to lookups.values.toList() }
        live.forEach { cancel(it.id, keepParts = true) }
        looking.forEach { it.cancel() }
    }

    /** Deletes what the runs of [key] left, unless one runs now. */
    fun discard(key: String) {
        if (!isDownloadKey(key) || synchronized(lock) { runs.values.any { it.request.key == key } }) return
        File(folder, key).deleteRecursively()
    }

    /** Deletes the folders of runs untouched for a week: a download KJB never came back to. */
    fun sweep() {
        val live = synchronized(lock) { runs.values.map { it.request.key }.toSet() }
        val now = clock()
        folder.listFiles()?.filter { it.isDirectory && it.name !in live && now - it.lastModified() > SWEEP_AFTER_MS }?.forEach { it.deleteRecursively() }
    }

    private suspend fun body(run: Run) {
        val dir = File(folder, run.request.key)
        var end: DlEnd = DlEnd.Stopped
        try {
            end = download(run, dir)
            // A delivered file is KJB's now; what a failed run got stays for its Retry.
            if (end is DlEnd.Delivered) withContext(io) { dir.deleteRecursively() }
        } catch (e: CancellationException) {
            if (!run.keepParts) withContext(NonCancellable + io) { dir.deleteRecursively() }
            throw e
        } catch (e: RuntimeException) {
            // A bug, never a reason to take the add-on down: the run fails, to be tried again.
            log("run ${run.id}: ${e.javaClass.simpleName}")
            end = DlEnd.Failed(DlFailure.UNKNOWN)
        } finally {
            if (run.delivery.isCompleted) run.delivery.getCompleted().close()
            synchronized(lock) { runs.remove(run.id) }
            log("run ${run.id}: ${label(end)}")
            run.events.onEnd(end)
        }
    }

    /** The run's way to its end: update when due, yt-dlp, once more after an update, then the hand-off. */
    private suspend fun download(run: Run, dir: File): DlEnd {
        val events = run.events
        if (!ytdlp.ready()) return DlEnd.Failed(DlFailure.ENGINE)
        var checked = false
        if (UpdatePolicy.beforeRun(memory.lastCheck, clock())) {
            events.onProgress(DlProgress(DlStage.UPDATING))
            checked = check().isCheck
        }
        withContext(io) { dir.mkdirs() }
        events.onProgress(DlProgress(DlStage.STARTING))
        var exit = execute(run, dir, attempt = 1)
        if (exit is YtdlpExit.Failed && UpdatePolicy.afterFailure(YtdlpLines.failure(exit.err), checked)) {
            events.onProgress(DlProgress(DlStage.UPDATING))
            val outcome = check()
            checked = outcome.isCheck
            if (outcome == DlUpdate.Outcome.UPDATED) {
                events.onProgress(DlProgress(DlStage.RETRYING))
                exit = execute(run, dir, attempt = 2)
            }
        }
        when (exit) {
            is YtdlpExit.Failed -> return DlEnd.Failed(YtdlpLines.failure(exit.err), updated = checked)
            // Killed by something other than a Cancel (the phone, low on memory): an end to try again.
            YtdlpExit.Killed -> return DlEnd.Failed(DlFailure.UNKNOWN, updated = checked)
            is YtdlpExit.Done -> Unit
        }
        val produced = withContext(io) { Produced.find(dir) } ?: return DlEnd.Failed(DlFailure.UNKNOWN, updated = checked)
        events.onFile(DlFile(produced.media.name, produced.media.length(), produced.cover?.length(), produced.meta, produced.durationSec))
        val sinks = withTimeoutOrNull(DELIVER_WAIT_MS) { run.delivery.await() } ?: return DlEnd.Failed(DlFailure.HAND_OFF)
        return handOff(produced, sinks)
    }

    /** One yt-dlp process for [run], killed if the run is cancelled; its lines go to KJB as progress, at most every [PROGRESS_EVERY_MS]. */
    private suspend fun execute(run: Run, dir: File, attempt: Int): YtdlpExit = usingYtdlp {
        val id = "run-${run.id}-$attempt"
        killable(id, dir.path + "/") {
            var stage = DlStage.STARTING
            var sentAt = Long.MIN_VALUE
            ytdlp.run(id, DlArgs.forRun(run.request, dir), run.request.url) { line ->
                val progress = YtdlpLines.progress(line, stage)
                val now = monotonic()
                if (progress.stage != stage || now - sentAt >= PROGRESS_EVERY_MS) {
                    stage = progress.stage
                    sentAt = now
                    run.events.onProgress(progress)
                }
            }
        }
    }

    /** Streams [produced]'s file and cover into KJB's [sinks], then closes them. */
    private suspend fun handOff(produced: Produced, sinks: Sinks): DlEnd = try {
        val bytes = copy(produced.media, sinks.media)
        val coverSink = sinks.cover
        val cover = if (produced.cover != null && coverSink != null) copy(produced.cover, coverSink) else null
        DlEnd.Delivered(bytes, cover)
    } catch (e: IOException) {
        log("hand-off: ${e.javaClass.simpleName}")
        DlEnd.Failed(if (noRoom(e)) DlFailure.NO_ROOM else DlFailure.HAND_OFF)
    } finally {
        sinks.close()
    }

    /** [file] into [out] a chunk at a time, stopping between chunks when the run is cancelled; then flushed to disk. */
    private suspend fun copy(file: File, out: OutputStream): Long = withContext(io) {
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        ensureActive()
        out.flush()
        (out as? FileOutputStream)?.fd?.sync()
        total
    }

    /** A full disk, by what Android's write says (ENOSPC). */
    private fun noRoom(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { cause -> cause.message?.let { "ENOSPC" in it || "No space left" in it } == true }

    /**
     * Looks up [url] and returns the lookup's id at once: the quick info from [quickInfo] while yt-dlp gets the full info,
     * which the quick one never follows.
     */
    fun info(url: String, events: InfoEvents): Long {
        val id = synchronized(lock) { nextId++ }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                lookUp(id, url, events)
            } finally {
                synchronized(lock) { lookups.remove(id) }
            }
        }
        synchronized(lock) { lookups[id] = job }
        job.start()
        return id
    }

    /** Stops lookup [lookup]: its yt-dlp is killed; it tells nothing more. */
    fun cancelInfo(lookup: Long) {
        synchronized(lock) { lookups[lookup] }?.cancel()
    }

    private suspend fun lookUp(id: Long, url: String, events: InfoEvents) = coroutineScope {
        if (!isWebLink(url)) {
            events.onFailed(DlEnd.Failed(DlFailure.REFUSED))
            return@coroutineScope
        }
        val told = Any()
        var full = false
        val quick = launch {
            val info = quickInfo(url) ?: return@launch
            synchronized(told) { if (!full) events.onInfo(info) }
        }
        if (!ytdlp.ready()) {
            quick.join()
            events.onFailed(DlEnd.Failed(DlFailure.ENGINE))
            return@coroutineScope
        }
        val marker = File(folder.parentFile, "lookup/$id").path + "/"
        val exit = usingYtdlp { killable("info-$id", marker) { ytdlp.run("info-$id", DlArgs.info(marker), url) {} } }
        quick.cancel()
        val info = (exit as? YtdlpExit.Done)?.let { Info.full(url, it.out) }
        synchronized(told) {
            full = true
            when {
                info != null -> events.onInfo(info)
                exit is YtdlpExit.Failed -> events.onFailed(DlEnd.Failed(YtdlpLines.failure(exit.err)))
                else -> events.onFailed(DlEnd.Failed(DlFailure.UNKNOWN))
            }
        }
    }

    /** Update now (Settings): refused while a download or lookup uses yt-dlp. */
    suspend fun update(): DlUpdate {
        if (!ytdlp.ready()) return DlUpdate(DlUpdate.Outcome.FAILED)
        return DlUpdate(check(), ytdlp.version())
    }

    /** The yt-dlp in place (its release tag). */
    fun version(): String? = ytdlp.version()

    /** Updates yt-dlp unless a process uses it now (BUSY); a look that worked is remembered for [UpdatePolicy]. */
    private suspend fun check(): DlUpdate.Outcome = updating.withLock {
        if (synchronized(lock) { executing } > 0) return@withLock DlUpdate.Outcome.BUSY
        val outcome = ytdlp.update()
        if (outcome.isCheck) memory.lastCheck = clock()
        log("yt-dlp update: $outcome")
        outcome
    }

    private val DlUpdate.Outcome.isCheck: Boolean get() = this == DlUpdate.Outcome.UPDATED || this == DlUpdate.Outcome.CURRENT

    /** [block] as one yt-dlp process: it waits for an update under way, and an update waits for none while it runs. */
    private suspend fun <T> usingYtdlp(block: suspend () -> T): T {
        updating.withLock { synchronized(lock) { executing++ } }
        try {
            return block()
        } finally {
            synchronized(lock) { executing-- }
        }
    }

    /**
     * [block], a yt-dlp process [id], killed ([Ytdlp.kill], with [marker]) as soon as the caller is cancelled: the process
     * would not stop by itself, and the block returns only once it has ended. VidGrab's lookups killed this way; its
     * downloads did not (their Cancel left yt-dlp running).
     */
    private suspend fun <T> killable(id: String, marker: String, block: suspend () -> T): T = coroutineScope {
        val ended = AtomicBoolean(false)
        val killer = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!ended.get()) ytdlp.kill(id, marker)
            }
        }
        try {
            block()
        } finally {
            ended.set(true)
            killer.cancel()
        }
    }

    private fun label(end: DlEnd): String = when (end) {
        is DlEnd.Delivered -> "delivered"
        is DlEnd.Failed -> "failed (${end.failure})"
        DlEnd.Stopped -> "stopped"
    }

    companion object {
        /** How long a finished file waits for KJB's descriptors: KJB opens them at once, or it is gone. */
        const val DELIVER_WAIT_MS: Long = 10 * 60_000

        /** Progress goes to KJB at most this often, a new stage at once: yt-dlp writes many lines a second. */
        const val PROGRESS_EVERY_MS: Long = 500

        /** A run's folder untouched this long goes ([sweep]). */
        const val SWEEP_AFTER_MS: Long = 7 * 24 * 60 * 60_000L

        private const val CHUNK = 256 * 1024
    }
}
