package app.lyrify.dl

import android.content.pm.ServiceInfo
import app.lyrify.addon.shared.ForegroundWork
import app.lyrify.addon.shared.beating
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlMeta
import app.lyrify.dl.api.DlPlaylist
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlSearch
import app.lyrify.dl.api.DlSearchResults
import app.lyrify.dl.api.DlStage
import app.lyrify.dl.api.DlUpdate
import app.lyrify.dl.api.PublicAddresses
import app.lyrify.dl.api.isDownloadKey
import app.lyrify.dl.api.isWebLink
import app.lyrify.dl.api.namesSong
import app.lyrify.dl.api.playlistId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** What one run tells its caller: IDlRunCallback, as Kotlin. */
interface RunEvents {
    fun onProgress(progress: DlProgress)

    /** The finished file waits for [DlRuns.deliver]. */
    fun onFile(file: DlFile)

    /** The run's last word. */
    fun onEnd(end: DlEnd)

    /** The run is alive: at its start, then every AddonKeepAlive.BEAT_EVERY_MS until its end. */
    fun onBeat()
}

/** What one lookup tells its caller: IDlInfoCallback, as Kotlin. */
interface InfoEvents {
    /** The quick info (partial) first when there is one, then the full info, which ends the lookup. */
    fun onInfo(info: DlInfo)

    fun onFailed(end: DlEnd.Failed)
}

/** What one search tells its caller: IDlSearchCallback, as Kotlin. One call, its last. */
interface SearchEvents {
    fun onResults(results: DlSearchResults)

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

    /**
     * Failures a newer yt-dlp may fix: a site that changed, a link it does not know yet, and what it did not say. Never a
     * site that refuses this network ([DlFailure.BLOCKED]): an update does not change who asks; nor a video only for adults
     * who sign in ([DlFailure.AGE_RESTRICTED]).
     */
    private val FIXABLE = setOf(DlFailure.SITE_CHANGED, DlFailure.NOT_FOUND, DlFailure.UNKNOWN)

    /** Before a run: never looked, more than a day ago, or a stored time after now (the clock went back). */
    fun beforeRun(lastCheck: Long?, now: Long): Boolean = lastCheck == null || now < lastCheck || now - lastCheck > STALE_MS

    fun afterFailure(failure: DlFailure, checkedThisRun: Boolean): Boolean = !checkedThisRun && failure in FIXABLE
}

/** When Android last refused the add-on its own foreground service (wall-clock millis); null once it let it in again. */
interface RefusalMemory {
    var refusedAt: Long?
}

/**
 * Whether the add-on tells KJB that Android refuses its foreground service (DlStatus.foregroundRefused): its last ask
 * was refused (a start, or its time ran out), less than a day ago. Android's count of dataSync time starts over at the
 * first start a day after the session that used it up began, so an older refusal says nothing; a stored time after now
 * (the clock went back) is not believed. Pure.
 */
object RefusalPolicy {
    fun standing(refusedAt: Long?, now: Long): Boolean = refusedAt != null && now >= refusedAt && now - refusedAt < UpdatePolicy.STALE_MS
}

/**
 * The add-on's work for its own foreground service ([JobForeground]), dataSync: held while a lookup or a run is under
 * way ([DlRuns.busy]). When Android's time for it runs out (onTimeout, Android 15+) the service leaves the foreground and
 * stops at once, as Android requires, and the work goes on without it while KJB keeps the add-on bound: KJB, told by
 * DlStatus, asks the user to keep it open. KJB's next start asks for the foreground again ([ForegroundWork.goesOn]).
 */
fun DlRuns.foregroundWork(): ForegroundWork = ForegroundWork(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, busy = { busy }, timeUp = {}, goesOn = true)

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
 * The add-on's downloads and lookups (VidGrab's DownloadWorker and fetchInfo, fixed), and YouTube searches, which are
 * lookups of words: each run has its own id and its own events, and works in its key's folder under [folder], where what
 * a stopped or failed run got stays for the next run of that key (yt-dlp goes on from its parts) until KJB discards it or
 * it is a week old ([sweep]). Cancel kills yt-dlp and its ffmpeg ([Ytdlp.kill]): a process does not stop because its
 * coroutine was cancelled. yt-dlp updates itself by [UpdatePolicy], and never while a run or a lookup uses it. A finished
 * file waits for KJB's descriptors ([deliver]) and is streamed into them; the add-on never opens KJB's or the user's
 * storage. It is [busy] from a link taken (a lookup or a run) until the last of them ends, each end told to [ended]: the
 * add-on's own foreground service is held meanwhile. A run beats from its start to its end ([RunEvents.onBeat]), so KJB
 * tells a frozen add-on from a quiet step.
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
    /** A host's addresses (DNS); a fake in tests. */
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /** A run or a lookup ended (after [busy] says so). */
    private val ended: () -> Unit = {},
    /** Where a run's beats run (the main thread on the phone): apart from yt-dlp's work. */
    private val beats: CoroutineContext = EmptyCoroutineContext,
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

        /** A stop said what it got goes (KJB's Cancel, a discard): no later stop keeps it (KJB letting go of the add-on). */
        @Volatile
        var dropped = false
            private set
        val delivery = CompletableDeferred<Sinks>()

        /** A stop that [keep]s what it got, or not. Under [lock]. */
        fun stopping(keep: Boolean) {
            dropped = dropped || !keep
        }
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

    /**
     * Stops [run]: its processes are killed; [keepParts]: what it got stays for the next run of its key, unless a stop
     * before said it goes.
     */
    fun cancel(run: Long, keepParts: Boolean) {
        synchronized(lock) { runs[run]?.also { it.stopping(keepParts) } }?.job?.cancel()
    }

    /** A run or a lookup is under way: from the link taken until its end, the run's file handed back included. */
    val busy: Boolean get() = synchronized(lock) { runs.isNotEmpty() || lookups.isNotEmpty() }

    /** Stops every run, their parts kept: KJB let go of the add-on (it may come back to them). */
    fun cancelAll() {
        val (live, looking) = synchronized(lock) { runs.values.toList() to lookups.values.toList() }
        live.forEach { cancel(it.id, keepParts = true) }
        looking.forEach { it.cancel() }
    }

    /**
     * Deletes what the runs of [key] left. A run of it still under way (or still stopping, a killed process still in its
     * folder) is stopped and deletes its folder once its processes are gone; the folder is marked ([DISCARDED]) too, for
     * the next start's [sweep] should the add-on go first. A folder no run holds goes at once, unless a process of the
     * add-on is still in it: it is marked then.
     */
    fun discard(key: String) {
        if (!isDownloadKey(key)) return
        val dir = File(folder, key)
        val live = synchronized(lock) { runs.values.firstOrNull { it.request.key == key }?.also { it.stopping(keep = false) } }
        if (live == null) {
            if (ytdlp.running(dir.path + "/")) mark(dir) else dir.deleteRecursively()
            return
        }
        live.job.cancel()
        mark(dir)
    }

    /** Marks [dir] for the next [sweep] ([DISCARDED]). */
    private fun mark(dir: File) {
        try {
            if (dir.isDirectory) File(dir, DISCARDED).createNewFile()
        } catch (e: IOException) {
            log("discard: not marked (${e.javaClass.simpleName})")
        }
    }

    /**
     * Lets go of [run]'s key once no process of the add-on is left in its folder [dir] (its killed yt-dlp and ffmpeg go at
     * once, but one may take longer, and is waited for however long): until then no run of the key starts, and nothing
     * deletes the folder under it. The folder goes first when its file was [delivered] or a stop said what it got goes,
     * a discard heard meanwhile too.
     */
    private suspend fun settle(run: Run, dir: File, delivered: Boolean) {
        val marker = dir.path + "/"
        var waited = 0L
        while (ytdlp.running(marker)) {
            if (waited == GONE_WAIT_MS) log("run ${run.id}: waits for a killed process still in its folder")
            val look = if (waited < GONE_WAIT_MS) GONE_LOOK_MS else GONE_LOOK_SLOW_MS
            delay(look)
            waited += look
        }
        var cleared = false
        do {
            if (!cleared && (delivered || run.dropped)) {
                dir.deleteRecursively()
                cleared = true
            }
            // The key goes under the lock, so a discard that came after the look above still deletes the folder first.
            val free = synchronized(lock) { (cleared || !run.dropped).also { if (it) runs.remove(run.id) } }
        } while (!free)
    }

    /**
     * Deletes the folders no run will come back to, at the add-on's start: those a discard marked, and those untouched for a
     * week (a download KJB never came back to). A folder a process of the add-on is still in stays.
     */
    fun sweep() {
        val live = synchronized(lock) { runs.values.map { it.request.key }.toSet() }
        val now = clock()
        folder.listFiles()
            ?.filter { it.isDirectory && it.name !in live && (File(it, DISCARDED).exists() || now - it.lastModified() > SWEEP_AFTER_MS) && !ytdlp.running(it.path + "/") }
            ?.forEach { it.deleteRecursively() }
    }

    private suspend fun body(run: Run) {
        val dir = File(folder, run.request.key)
        var end: DlEnd = DlEnd.Stopped
        // The run's beats, apart from it: they go on while yt-dlp or ffmpeg works for minutes without a line.
        val beating = scope.beating(beats) { run.events.onBeat() }
        try {
            end = download(run, dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A bug, never a reason to take the add-on down: the run fails, to be tried again.
            log("run ${run.id}: ${e.javaClass.simpleName}")
            end = DlEnd.Failed(DlFailure.UNKNOWN)
        } finally {
            // A delivered file is KJB's now; what a stopped or failed run got stays for its next run, unless a stop said it goes.
            val delivered = end is DlEnd.Delivered
            // NonCancellable on the run's own dispatcher first: a cancelled run coming back from io would throw, never ending.
            withContext(NonCancellable) { withContext(io) { settle(run, dir, delivered) } }
            // After it: KJB's descriptors for it may come while it settles, the run still holding its key.
            if (run.delivery.isCompleted) run.delivery.getCompleted().close()
            // It beats until its end, its settle included: a wait for its processes is never taken for a freeze.
            beating.cancel()
            log("run ${run.id}: ${label(end)}")
            run.events.onEnd(end)
            ended()
        }
    }

    /** The run's way to its end: update when due, yt-dlp, once more after an update, then the hand-off. */
    private suspend fun download(run: Run, dir: File): DlEnd {
        val events = run.events
        if (notPublic(run.request.url)) return DlEnd.Failed(DlFailure.LOCAL_ADDRESS)
        if (!ytdlp.ready()) return DlEnd.Failed(DlFailure.ENGINE)
        val before = if (UpdatePolicy.beforeRun(memory.lastCheck, clock())) {
            events.onProgress(DlProgress(DlStage.UPDATING))
            check()
        } else {
            null
        }
        // Only a newer yt-dlp put in place during the run lets KJB say it updated the downloader and tried again: not one
        // already the newest, nor an update that was not made.
        var updated = before == DlUpdate.Outcome.UPDATED
        withContext(io) { dir.mkdirs() }
        events.onProgress(DlProgress(DlStage.STARTING))
        var exit = execute(run, dir, attempt = 1)
        var failed = (exit as? YtdlpExit.Failed)?.let { failure("run ${run.id}", it) }
        if (failed != null && UpdatePolicy.afterFailure(failed, checkedThisRun = before?.isCheck == true)) {
            events.onProgress(DlProgress(DlStage.UPDATING))
            if (check() == DlUpdate.Outcome.UPDATED) {
                updated = true
                events.onProgress(DlProgress(DlStage.RETRYING))
                exit = execute(run, dir, attempt = 2)
                failed = (exit as? YtdlpExit.Failed)?.let { failure("run ${run.id}", it) }
            }
        }
        failed?.let { return DlEnd.Failed(it, updated = updated) }
        // Killed by something other than a Cancel (the phone, low on memory): an end to try again.
        if (exit == YtdlpExit.Killed) return DlEnd.Failed(DlFailure.UNKNOWN, updated = updated)
        val produced = withContext(io) { Produced.find(dir) } ?: return DlEnd.Failed(DlFailure.UNKNOWN, updated = updated)
        events.onFile(DlFile(produced.media.name, produced.media.length(), produced.cover?.length(), produced.meta, produced.durationSec))
        val sinks = withTimeoutOrNull(DELIVER_WAIT_MS) { run.delivery.await() } ?: return DlEnd.Failed(DlFailure.HAND_OFF)
        return handOff(produced, sinks)
    }

    /**
     * What a failed yt-dlp process of [what] ("run 3", "lookup 4") says, logged by its kind, the pattern that told it and
     * the site's extractor ([YtdlpError.note]): never its lines, which carry the link, the id and the title.
     */
    private fun failure(what: String, exit: YtdlpExit.Failed): DlFailure {
        val error = YtdlpLines.diagnose(exit.err)
        log("$what: yt-dlp failed (${error.note})")
        return error.failure
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
     * which the quick one never follows. A playlist's link has its songs listed, none of them opened; a song's link that
     * names a playlist too (&list=, a Mix started from it) is the song, its playlist listed first and told with the quick
     * info (a partial info) before the song's full info, which has it too.
     */
    fun info(url: String, events: InfoEvents): Long = lookup { id -> lookUp(id, url, events) }

    /**
     * Searches YouTube for [search] and returns its id at once: a lookup of words, which yt-dlp answers with YouTube's
     * list and opens none of it. One that is not valid (null) is refused. It never updates yt-dlp: the download picked
     * from it does when that is due.
     */
    fun search(search: DlSearch?, events: SearchEvents): Long = lookup { id -> find(id, search, events) }

    /** Starts a lookup's (or a search's) [body] and returns its id at once: [busy] from now until its end. */
    private fun lookup(body: suspend (id: Long) -> Unit): Long {
        val id = synchronized(lock) { nextId++ }
        val job = scope.launch(start = CoroutineStart.LAZY) { body(id) }
        // Its end however it comes, also for a lookup stopped before its body ran: one left in the map would hold the
        // foreground service for as long as the add-on runs.
        job.invokeOnCompletion {
            synchronized(lock) { lookups.remove(id) }
            ended()
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
        if (notPublic(url)) {
            events.onFailed(DlEnd.Failed(DlFailure.LOCAL_ADDRESS))
            return@coroutineScope
        }
        val told = Any()
        var full = false
        // The quick info and the playlist a song's link names, each told with the other once both are in.
        var said: DlInfo? = null
        var listed: DlPlaylist? = null
        val quick = launch {
            val info = quickInfo(url) ?: return@launch
            synchronized(told) {
                said = info
                if (!full) events.onInfo(info.copy(playlist = listed ?: info.playlist))
            }
        }
        if (!ytdlp.ready()) {
            quick.join()
            events.onFailed(DlEnd.Failed(DlFailure.ENGINE))
            return@coroutineScope
        }
        val list = playlistId(url)
        val songs = if (Info.isMix(list)) DlArgs.MIX_SONGS else DlArgs.PLAYLIST_SONGS
        // A song's link that names a playlist too (watch?v=...&list=, youtu.be/<id>?list=, a Mix started from it) has the
        // playlist listed first, so KJB can ask which is meant while the song's own info comes. One that cannot be listed (a
        // private one, Liked music) leaves the song as it is, logged by its kind; a Mix yt-dlp cannot list answers with the
        // song's own info. A link with a list and no song (/playlist, watch?list=, embed/videoseries) is the playlist's own.
        var found: DlInfo? = null
        if (list != null && namesSong(url)) {
            val listing = answer(id, { DlArgs.info(it, songs, playlist = true) }, url)
            if (listing is YtdlpExit.Failed) failure("lookup $id", listing)
            val out = (listing as? YtdlpExit.Done)?.out
            val playlist = out?.let(Info::playlist)?.copy(withSong = true)
            if (playlist == null) {
                found = out?.let { Info.full(url, it) }
            } else {
                synchronized(told) {
                    listed = playlist
                    events.onInfo((said ?: DlInfo(url, "", "", partial = true)).copy(playlist = playlist))
                }
            }
        }
        val exit = if (found == null) answer(id, { DlArgs.info(it, songs) }, url) else null
        quick.cancel()
        val info = (found ?: (exit as? YtdlpExit.Done)?.let { Info.full(url, it.out) })?.let { it.copy(playlist = it.playlist ?: listed) }
        synchronized(told) {
            full = true
            when {
                info != null -> events.onInfo(info)
                exit is YtdlpExit.Failed -> events.onFailed(DlEnd.Failed(failure("lookup $id", exit)))
                else -> events.onFailed(DlEnd.Failed(DlFailure.UNKNOWN))
            }
        }
    }

    private suspend fun find(id: Long, search: DlSearch?, events: SearchEvents) {
        if (search == null) return events.onFailed(DlEnd.Failed(DlFailure.REFUSED))
        if (!ytdlp.ready()) return events.onFailed(DlEnd.Failed(DlFailure.ENGINE))
        val words = search.query.trim()
        val exit = answer(id, DlArgs::search, "ytsearch${search.limit}:$words")
        val results = (exit as? YtdlpExit.Done)?.let { Info.results(it.out) }
        when {
            results != null -> events.onResults(results)
            // yt-dlp names the search by its words in an ERROR line, before the reason ('[youtube:search] query "<words>"
            // page 1: <why>'): taken out first, so words like "timed out" never decide why it failed.
            exit is YtdlpExit.Failed -> {
                val reasons = exit.err.lines().joinToString("\n") { it.replaceFirst(words, "") }
                events.onFailed(DlEnd.Failed(failure("lookup $id", exit.copy(err = reasons))))
            }
            else -> events.onFailed(DlEnd.Failed(DlFailure.UNKNOWN))
        }
    }

    /**
     * yt-dlp's answer for lookup [id]: its [args] (given the marker its process is known by) then [target], a link or a
     * search; killed as soon as the lookup is stopped.
     */
    private suspend fun answer(id: Long, args: (marker: String) -> List<String>, target: String): YtdlpExit {
        val marker = File(folder.parentFile, "lookup/$id").path + "/"
        return usingYtdlp { killable("info-$id", marker) { ytdlp.run("info-$id", args(marker), target) {} } }
    }

    /**
     * Whether [url]'s host resolves to an address not on the internet (this phone, the home network, a cloud's metadata
     * address): a shared link must never make yt-dlp reach those. One that does not resolve goes on, to fail as offline.
     * yt-dlp follows a site's redirects itself, past this check; only the link's own host is checked here.
     */
    private suspend fun notPublic(url: String): Boolean = withContext(io) { PublicAddresses.of(url, resolve) } == PublicAddresses.Verdict.NOT_PUBLIC

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

        /** The mark of a folder a discard could not delete yet, because its run was still stopping ([sweep] deletes it). */
        const val DISCARDED: String = ".discarded"

        /** A run's folder untouched this long goes ([sweep]). */
        const val SWEEP_AFTER_MS: Long = 7 * 24 * 60 * 60_000L

        /** How long a stopped run looks often for its killed processes to be gone ([settle]); then less often, however long. */
        const val GONE_WAIT_MS: Long = 5_000

        /** How often [settle] looks for them, at first and after [GONE_WAIT_MS]. */
        private const val GONE_LOOK_MS: Long = 100
        private const val GONE_LOOK_SLOW_MS: Long = 1_000

        private const val CHUNK = 256 * 1024
    }
}
