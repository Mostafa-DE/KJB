package app.lyrify.player

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** What a cover read found ([SessionCovers]): the song's own cover, or none, so the default cover shows. */
sealed interface CoverFound<out V : Any> {
    class Own<V : Any>(val cover: V) : CoverFound<V>

    data object NoArt : CoverFound<Nothing>
}

/**
 * The covers of KJB's media session (the notification and the lock screen), by their uri ([K]), as the session's bitmap
 * loader hands them to Media3. Media3 1.11 sets a song's details at once, and its cover in the same update only when the
 * cover's future is done by then; else in a second update once it is (CV1).
 * - Kept: a song's own cover is kept while the covers kept fit in [maxBytes] (and [MAX_KEPT] songs), the one used longest
 *   ago going first, and is answered at once. No art is kept [NO_ART_KEEP_MS] only: MediaStore can miss a song's art for
 *   a moment (a tag write), and the widget reads such a song again a minute on too. A read that failed is not kept.
 * - Read ahead: [warm] reads the songs either side of the one playing, so Next and Previous find theirs kept.
 * - The newest asked first: at most [READERS] reads at a time, at most one of them a read ahead. A song asked for and not
 *   read yet when another song is asked for (a burst of Nexts) is set aside: read only if asked for again, so it never
 *   holds up the song playing now.
 * - Within [waitMs]: every answer comes by then, the cover or else the default ([fallback]); a read done later still is
 *   kept for the next ask. A read that throws (an Exception, or OutOfMemoryError) answers the default too, its error's
 *   name logged.
 * [forget] lets every cover kept go, as KJB changes covers. Thread-safe; each read is a task on [run], and [later] runs
 * the waits.
 */
class SessionCovers<K : Any, V : Any>(
    private val read: (K) -> CoverFound<V>?,
    private val fallback: () -> V,
    private val sizeOf: (V) -> Int,
    private val run: Executor,
    /** Runs a task after a delay in ms; the Runnable it gives cancels it. */
    private val later: (Long, Runnable) -> Runnable,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val maxBytes: Long = MAX_BYTES,
    private val waitMs: Long = WAIT_MS,
) {
    private val lock = Any()

    /** The covers kept, the one used longest ago first; a null cover is no art, read at [Kept.atMs]. */
    private val kept = LinkedHashMap<K, Kept<V>>(16, 0.75f, true)
    private var keptBytes = 0L

    /** The songs to read, being read, or set aside, in the order they came. */
    private val jobs = LinkedHashMap<K, Job<K, V>>()
    private var reading = 0
    private var readingAhead = 0

    /** Moved on by [forget]: a read started before it is not kept. */
    private var generation = 0

    /** Song [key]'s cover: at once when kept, else once read, and the default cover at the latest after [waitMs]. */
    fun ask(key: K): ListenableFuture<V> {
        synchronized(lock) { keptNow(key) }?.let { found -> return answered(found.cover) }
        val answer = SettableFuture.create<V>()
        val start = synchronized(lock) {
            val job = jobs.getOrPut(key) { Job(key) }
            job.answers += answer
            if (job.step != Step.READING) job.step = Step.ASKED
            jobs.values.filter { it !== job && it.step == Step.ASKED }.forEach(::setAside)
            startable()
        }
        val wait = later(waitMs) { timedOut(key, answer) }
        answer.addListener(wait, MoreExecutors.directExecutor())
        start(start)
        return answer
    }

    /** Reads [keys] ahead (the songs either side of the one playing), after any song asked for; earlier ones not read yet are dropped. */
    fun warm(keys: List<K>) {
        val start = synchronized(lock) {
            val wanted = keys.distinct()
            jobs.values.filter { it.step == Step.AHEAD && it.key !in wanted }.forEach(::setAside)
            for (key in wanted) {
                if (keptNow(key) != null) continue
                val job = jobs[key]
                if (job == null || job.step == Step.ASIDE) {
                    // Last in the order: read after the songs already to be read ahead.
                    jobs.remove(key)
                    jobs[key] = (job ?: Job(key)).also { it.step = Step.AHEAD }
                }
            }
            startable()
        }
        start(start)
    }

    /** Lets every cover kept go: KJB changed covers. A read going on now still answers, and is not kept. */
    fun forget() = synchronized(lock) {
        generation++
        kept.clear()
        keptBytes = 0
    }

    /** Song [key]'s cover kept, used now; null when none is, or its no art is [NO_ART_KEEP_MS] old. Under [lock]. */
    private fun keptNow(key: K): Kept<V>? {
        val found = kept[key] ?: return null
        if (found.cover != null || clock() - found.atMs < NO_ART_KEEP_MS) return found
        kept.remove(key)
        return null
    }

    /** [job] is not read now: kept aside for its answers, else dropped. Under [lock]. */
    private fun setAside(job: Job<K, V>) {
        if (job.answers.isEmpty()) jobs.remove(job.key) else job.step = Step.ASIDE
    }

    /** The reads to start now: the song asked for first, then one read ahead at most. Under [lock]. */
    private fun startable(): List<Job<K, V>> = buildList {
        while (reading < READERS) {
            val asked = jobs.values.lastOrNull { it.step == Step.ASKED }
            val job = asked ?: jobs.values.firstOrNull { it.step == Step.AHEAD }?.takeIf { readingAhead == 0 } ?: break
            job.ahead = asked == null
            job.step = Step.READING
            job.generation = generation
            reading++
            if (job.ahead) readingAhead++
            add(job)
        }
    }

    private fun start(started: List<Job<K, V>>) = started.forEach { job ->
        try {
            run.execute { finish(job, guarded { read(job.key) }.getOrNull()) }
        } catch (e: RejectedExecutionException) {
            // The service is gone: the default cover, at once.
            finish(job, null)
        }
    }

    private fun finish(job: Job<K, V>, found: CoverFound<V>?) {
        val (answers, next) = synchronized(lock) {
            reading--
            if (job.ahead) readingAhead--
            if (jobs[job.key] === job) jobs.remove(job.key)
            if (found != null && job.generation == generation) keep(job.key, found)
            job.answers.toList() to startable()
        }
        val cover = (found as? CoverFound.Own)?.cover
        answers.forEach { settle(it, cover) }
        start(next)
    }

    /** Keeps what was [found] for [key], letting the covers used longest ago go to make room. Under [lock]. */
    private fun keep(key: K, found: CoverFound<V>) {
        kept.remove(key)?.let { keptBytes -= it.bytes }
        val cover = (found as? CoverFound.Own)?.cover
        val bytes = cover?.let(sizeOf) ?: 0
        if (bytes > maxBytes) return
        kept[key] = Kept(cover, bytes, clock())
        keptBytes += bytes
        val eldest = kept.values.iterator()
        while ((keptBytes > maxBytes || kept.size > MAX_KEPT) && eldest.hasNext()) {
            keptBytes -= eldest.next().bytes
            eldest.remove()
        }
    }

    /** [answer]'s wait is over: the default cover. Its song, set aside with no answer left, is not read. */
    private fun timedOut(key: K, answer: SettableFuture<V>) {
        if (answer.isDone) return
        synchronized(lock) {
            jobs[key]?.let { job ->
                job.answers.remove(answer)
                if (job.step == Step.ASIDE) setAside(job)
            }
        }
        settle(answer, null)
    }

    private fun answered(cover: V?): ListenableFuture<V> = SettableFuture.create<V>().also { settle(it, cover) }

    /** [answer] gets [cover], or the default cover; failing to make that, the error, so no answer waits for ever. */
    private fun settle(answer: SettableFuture<V>, cover: V?) {
        if (cover != null) {
            answer.set(cover)
            return
        }
        guarded(fallback).fold({ answer.set(it) }, { answer.setException(it) })
    }

    /** What [block] gives, or the Exception or OutOfMemoryError it threw, its name logged. */
    private inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        notRead(e)
    } catch (e: OutOfMemoryError) {
        notRead(e)
    }

    private fun <T> notRead(e: Throwable): Result<T> {
        log("session cover not read: ${e.javaClass.simpleName}")
        return Result.failure(e)
    }

    private class Kept<V>(val cover: V?, val bytes: Int, val atMs: Long)

    private enum class Step { ASKED, AHEAD, ASIDE, READING }

    private class Job<K, V>(val key: K) {
        var step = Step.ASKED
        val answers = mutableListOf<SettableFuture<V>>()

        /** Started as a read ahead. */
        var ahead = false
        var generation = 0
    }

    companion object {
        /** Reads at a time: a song asked for waits behind one other read at most, and MediaStore is asked little. */
        const val READERS: Int = 2

        /** Six covers of 512 px (CarCovers): the song playing, the next and the previous, and the last few played. */
        const val MAX_BYTES: Long = 6L * 1024 * 1024

        /** Songs kept at most, those with no art (which take no room) too. */
        const val MAX_KEPT: Int = 32

        /** The longest an answer waits for its read; MediaStore's thumbnail of a song takes a few hundred ms at most. */
        const val WAIT_MS: Long = 5_000

        /** How long no art is kept: the widget reads such a song again a minute on too (NO_ART_RECHECK_MS). */
        const val NO_ART_KEEP_MS: Long = 60_000
    }
}

/**
 * The songs whose covers are read ahead as song [index] of [count] starts: the next one, round to the first as Next
 * goes on the last song ([sessionSeek]), and the previous one, round to the last; never the song itself.
 */
fun aroundSong(index: Int, count: Int): List<Int> {
    if (index !in 0 until count) return emptyList()
    return listOf((index + 1) % count, (index - 1 + count) % count).distinct().filter { it != index }
}
