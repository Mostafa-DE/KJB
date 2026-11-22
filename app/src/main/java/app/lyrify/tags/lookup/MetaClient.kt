package app.lyrify.tags.lookup

import app.lyrify.USER_AGENT
import app.lyrify.lyrics.readCapped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What a MusicBrainz or Cover Art Archive request came back with. Failures that are expected are values. */
sealed interface MetaAnswer<out T> {
    /** [fromCache]: answered by the cache, nothing was sent. */
    data class Ok<T>(val value: T, val fromCache: Boolean = false) : MetaAnswer<T>

    /** 404: no such release, or no cover of that size. */
    data object NotFound : MetaAnswer<Nothing>

    /** Still 503 after every backoff: "MusicBrainz is busy". */
    data object Busy : MetaAnswer<Nothing>

    /**
     * Offline, refused, too large or unreadable. [reason] is for the log: a status code or an exception class name.
     * [status]: the HTTP status the server answered with, when it answered.
     */
    data class Failed(val reason: String, val status: Int? = null) : MetaAnswer<Nothing>
}

/** What a request fetches, and how much of it may come back. */
enum class Body(val maxBytes: Int, val accept: String) {
    /** A release with every track and credit is some hundred KiB; a box set's a few times that. */
    JSON(maxBytes = 4 * 1024 * 1024, accept = "application/json"),

    /** Each image is capped at 2 MiB before it is decoded. */
    IMAGE(maxBytes = 2 * 1024 * 1024, accept = "image/*"),
}

/**
 * The hosts Covers & tags may talk to, always over https on the default port: requests start at musicbrainz.org or
 * coverartarchive.org; the Cover Art Archive answers with a redirect to archive.org, which redirects again to one of its
 * own servers (`dn721702.ca.archive.org`, `ia800204.us.archive.org`, seen on 2026-11-10). Similar artists (similar/)
 * also asks ListenBrainz's labs API ([LISTENBRAINZ_LABS]).
 */
object MetaHosts {
    const val MUSICBRAINZ: String = "musicbrainz.org"
    const val COVER_ART_ARCHIVE: String = "coverartarchive.org"
    const val ARCHIVE: String = "archive.org"
    const val LISTENBRAINZ_LABS: String = "labs.api.listenbrainz.org"

    private val SOURCES = setOf(MUSICBRAINZ, COVER_ART_ARCHIVE, LISTENBRAINZ_LABS)

    /** Where a request may start. */
    fun isSource(uri: URI): Boolean = secure(uri) && host(uri) in SOURCES

    /** A request that starts at the Cover Art Archive: its 503 is a cover that could not load, never "busy". */
    fun isCover(uri: URI): Boolean = host(uri) == COVER_ART_ARCHIVE

    /** A hop to musicbrainz.org, wherever its request started: it waits for MusicBrainz's [MetaGate]. */
    fun isMusicBrainz(uri: URI): Boolean = host(uri) == MUSICBRAINZ

    /** Where a redirect may lead. */
    fun isRedirect(uri: URI): Boolean = secure(uri) && host(uri).let { it in SOURCES || it == ARCHIVE || it.endsWith(".$ARCHIVE") }

    private fun secure(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && (uri.port == -1 || uri.port == HTTPS_PORT) && uri.rawUserInfo == null

    private fun host(uri: URI): String = uri.host?.lowercase().orEmpty()

    private const val HTTPS_PORT = 443
}

/**
 * MusicBrainz's rule (https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting): one request at a time, each starting at
 * least [SPACING_MS] after the previous one ENDED. A 503 holds the gate: nobody asks again until the backoff
 * ([BACKOFF_MS]) is over, and [busy] says so. Covers never pass here ([MetaClient]).
 */
class MetaGate(private val now: () -> Long, private val log: (String) -> Unit = {}) {
    private val mutex = Mutex()

    /** End of the previous request; only read and written while holding [mutex]. */
    private var lastEnd: Long? = null

    private val _busy = MutableStateFlow(false)

    /** True while a 503 is being waited out. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Sends [request] when its turn comes; while its answer [isBusy], waits 2 s, 4 s, ... 60 s and asks again. */
    suspend fun <T> send(isBusy: (T) -> Boolean, request: suspend () -> T): T = mutex.withLock {
        try {
            var answer = inTurn(request)
            for (wait in BACKOFF_MS) {
                if (!isBusy(answer)) break
                _busy.value = true
                log("tags: MusicBrainz busy (HTTP 503), next try in ${wait / MS_PER_SECOND} s")
                delay(wait)
                answer = inTurn(request)
            }
            answer
        } finally {
            _busy.value = false
        }
    }

    /** [request] once its spacing has passed; only called while holding [mutex]. */
    private suspend fun <T> inTurn(request: suspend () -> T): T {
        lastEnd?.let { delay(it + SPACING_MS - now()) }
        return try {
            request()
        } finally {
            lastEnd = now()
        }
    }

    companion object {
        const val SPACING_MS: Long = 1_100

        /** The waits after each 503, in order; still busy after the last one, the request gives up. */
        val BACKOFF_MS: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 32_000, 60_000)

        private const val MS_PER_SECOND = 1_000
    }
}

/**
 * Successful answers, keyed by the query (its URI), in [folder] (`tag-cache/`). Errors, 404s and empty results are never
 * kept. Each entry is a file named by the SHA-256 of its key; reading one marks it used, and the least recently used go
 * first once the folder passes [budgetBytes]. One lock covers every read, write and trim. Blocking: call it off the
 * main thread.
 */
class MetaCache(
    private val folder: File,
    private val budgetBytes: Long = BUDGET_BYTES,
    /** Writes a body into a file; tests pass one that stops part-way. */
    private val write: (File, ByteArray) -> Unit = File::writeBytes,
) {
    private val lock = Any()

    fun get(key: String): ByteArray? = synchronized(lock) {
        val file = file(key)
        try {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: IOException) {
            null
        }
    }

    fun put(key: String, body: ByteArray): Unit = synchronized(lock) {
        try {
            folder.mkdirs()
            val file = file(key)
            val part = File(folder, file.name + PART)
            write(part, body)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            trim(keep = file)
        } catch (_: IOException) {
            // A cache that cannot be written only means asking again next time.
        }
    }

    fun remove(key: String): Unit = synchronized(lock) {
        file(key).delete()
    }

    /** Drops the least recently used entries, never [keep], while the folder is over budget. Called holding [lock]. */
    private fun trim(keep: File) {
        val entries = folder.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = entries.sumOf { it.length() }
        for (entry in entries) {
            if (total <= budgetBytes) break
            if (entry == keep) continue
            val length = entry.length()
            if (entry.delete()) total -= length
        }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
        return File(folder, digest.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') })
    }

    companion object {
        const val BUDGET_BYTES: Long = 64L * 1024 * 1024
        private const val PART = ".part"
        private const val BYTE_MASK = 0xff
        private const val HEX = 16
    }
}

/**
 * MusicBrainz and the Cover Art Archive over [HttpURLConnection]: MusicBrainz through its [gate]; covers and their
 * redirect hops [COVER_REQUESTS] at a time and never behind that gate, as the Cover Art Archive sets no rate limit
 * (https://musicbrainz.org/doc/Cover_Art_Archive/API: "There are currently no rate limiting rules in place"), and a
 * cover's 503 is a cover that could not load. ListenBrainz's labs (similar/) goes outside that gate too, among the
 * covers' requests; Similar artists paces its own. Https to [MetaHosts] only, redirects followed by hand and each one
 * checked, every body capped ([Body]), connect and read timeouts with one more try after a timeout, successes cached
 * ([cache], on disk) unless [fresh][get] ("Check again"). An answer read to its end keeps its connection for the next
 * request. Logs carry only status codes, exception class names and counts, never a URL or a name.
 */
class MetaClient(
    private val cache: MetaCache,
    private val userAgent: String = USER_AGENT,
    private val gate: MetaGate = MetaGate(now = { System.nanoTime() / NANOS_PER_MILLI }),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Makes the connection for a URI without connecting yet; tests pass a fake. */
    private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val log: (String) -> Unit = {},
) {
    /** True while MusicBrainz is busy and a 503 is being waited out. */
    val busy: StateFlow<Boolean> get() = gate.busy

    /** The cover requests running at once. */
    private val covers = Semaphore(COVER_REQUESTS)

    /**
     * The answer at [uri], read by [read] (which throws [IllegalArgumentException] on an unreadable body). It is cached
     * only when [read] took it and [keep] says it is worth keeping (an empty search result is not). [fresh] skips the
     * cache but still stores the new answer. [read] and the cache run on [io], never the caller's thread.
     */
    suspend fun <T> get(uri: URI, body: Body, fresh: Boolean = false, keep: (T) -> Boolean = { true }, read: (ByteArray) -> T): MetaAnswer<T> {
        if (!MetaHosts.isSource(uri)) {
            log("tags: request refused: not an allowed address")
            return MetaAnswer.Failed("not an allowed address")
        }
        val key = uri.toString()
        if (!fresh) withContext(io) { cached(key, read) }?.let { return it }
        val cover = MetaHosts.isCover(uri)
        val musicBrainz = MetaHosts.isMusicBrainz(uri)
        return when (val hop = follow(uri, body)) {
            is Hop.Content -> withContext<MetaAnswer<T>>(io) {
                try {
                    read(hop.bytes).also { if (keep(it)) cache.put(key, hop.bytes) }.let { MetaAnswer.Ok(it) }
                } catch (e: IllegalArgumentException) {
                    log("tags: unreadable answer: ${e.javaClass.simpleName}")
                    MetaAnswer.Failed("unreadable: ${e.javaClass.simpleName}")
                }
            }
            is Hop.Status -> when (hop.code) {
                HttpURLConnection.HTTP_NOT_FOUND -> MetaAnswer.NotFound
                HttpURLConnection.HTTP_UNAVAILABLE -> if (cover) {
                    log("tags: cover answered HTTP 503")
                    MetaAnswer.Failed("HTTP 503")
                } else {
                    log(if (musicBrainz) "tags: MusicBrainz still busy, gave up" else "tags: answered HTTP 503")
                    MetaAnswer.Busy
                }
                else -> {
                    log("tags: answered HTTP ${hop.code}")
                    MetaAnswer.Failed("HTTP ${hop.code}", hop.code)
                }
            }
            is Hop.Stop -> {
                log("tags: request failed: ${hop.reason}")
                MetaAnswer.Failed(hop.reason)
            }
            is Hop.Redirect -> MetaAnswer.Failed("redirect not followed")
        }
    }

    /** The cached answer for [key], read by [read]; one that no longer reads is dropped. Blocking. */
    private fun <T> cached(key: String, read: (ByteArray) -> T): MetaAnswer.Ok<T>? {
        val bytes = cache.get(key) ?: return null
        return try {
            MetaAnswer.Ok(read(bytes), fromCache = true)
        } catch (_: IllegalArgumentException) {
            cache.remove(key)
            null
        }
    }

    /** One answer from [uri], each redirect checked against [MetaHosts] before it is followed, every hop in its turn ([hop]). */
    private suspend fun follow(uri: URI, body: Body): Hop {
        var current = uri
        repeat(MAX_REDIRECTS + 1) {
            when (val hop = hop(current, body)) {
                is Hop.Redirect -> {
                    val next = try {
                        current.resolve(hop.location)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    if (next == null || !MetaHosts.isRedirect(next)) return Hop.Stop("redirect refused")
                    current = next
                }
                else -> return hop
            }
        }
        return Hop.Stop("more than $MAX_REDIRECTS redirects")
    }

    /**
     * One hop in its turn, by the host it goes to: musicbrainz.org through its [gate] (a cover redirect landing there
     * included), the Cover Art Archive and archive.org among [COVER_REQUESTS] at once. A hop that timed out is tried once
     * more ([TIMEOUT_TRIES]); any other failure is the answer.
     */
    private suspend fun hop(uri: URI, body: Body): Hop {
        var tries = 0
        while (true) {
            val hop = if (MetaHosts.isMusicBrainz(uri)) {
                gate.send({ it is Hop.Status && it.code == HttpURLConnection.HTTP_UNAVAILABLE }) { request(uri, body) }
            } else {
                covers.withPermit { request(uri, body) }
            }
            if (hop !is Hop.Stop || !hop.timedOut || ++tries >= TIMEOUT_TRIES) return hop
            log("tags: request timed out, trying once more")
        }
    }

    /**
     * One request. An answer read to its end leaves its connection to the next request to that host (keep-alive): no
     * new TLS handshake. Any other answer, and a cancelled request, is disconnected at once, which also frees the gate.
     */
    private suspend fun request(uri: URI, body: Body): Hop {
        val connection = try {
            open(uri)
        } catch (e: IOException) {
            return Hop.Stop(e.javaClass.simpleName)
        }
        return coroutineScope {
            val answer = async(io) { exchange(connection, body) }
            var reusable = false
            try {
                answer.await().also { reusable = it is Hop.Content }
            } finally {
                if (!reusable) connection.disconnect()
            }
        }
    }

    /** Blocking: sends the request and reads the answer, at most [Body.maxBytes]. */
    private fun exchange(connection: HttpURLConnection, body: Body): Hop = try {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", body.accept)
        val code = connection.responseCode
        when {
            code in REDIRECTS -> connection.getHeaderField("Location")?.let { Hop.Redirect(it) } ?: Hop.Stop("redirect without a location")
            code != HttpURLConnection.HTTP_OK -> {
                // Closed unread: an error page says nothing the status code does not.
                connection.errorStream?.close()
                Hop.Status(code)
            }
            connection.contentLengthLong > body.maxBytes -> Hop.Stop("answer over ${body.maxBytes} bytes")
            else -> connection.inputStream.use { input -> readCapped(input, body.maxBytes) }
                ?.let { Hop.Content(it) }
                ?: Hop.Stop("answer over ${body.maxBytes} bytes")
        }
    } catch (e: IOException) {
        // Never the message: it can hold the address, and the address holds the names.
        Hop.Stop(e.javaClass.simpleName, timedOut = e is SocketTimeoutException)
    }

    private sealed interface Hop {
        class Content(val bytes: ByteArray) : Hop

        data class Redirect(val location: String) : Hop

        data class Status(val code: Int) : Hop

        data class Stop(val reason: String, val timedOut: Boolean = false) : Hop
    }

    companion object {
        const val MAX_REDIRECTS: Int = 5

        /** Cover requests at once: the Cover Art Archive sets no limit, so a few, to be kind to archive.org. */
        const val COVER_REQUESTS: Int = 4

        private const val CONNECT_TIMEOUT_MS: Int = 10_000
        private const val READ_TIMEOUT_MS: Int = 20_000

        /** Tries of a hop that timed out: the first, and one more. */
        private const val TIMEOUT_TRIES: Int = 2
        private const val NANOS_PER_MILLI: Long = 1_000_000

        /** 301, 302, 303, 307 and 308; the Cover Art Archive answers 307, archive.org 302. */
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
