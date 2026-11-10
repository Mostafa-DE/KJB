package app.lyrify.tags.lookup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** The User-Agent the tests give the client. */
internal const val TEST_AGENT: String = "KJB/3.0.0 ( https://github.com/Mostafa-DE/Lyrify )"

/** A dispatcher on one thread named [name], to see where work ran. Close it after the test. */
internal fun namedThread(name: String): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { Thread(it, name) }.asCoroutineDispatcher()

/** Hands work to [inner], counting how often work was sent here. */
internal class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
    @Volatile
    var dispatches: Int = 0
        private set

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches++
        inner.dispatch(context, block)
    }
}

/** A recorded answer (made-up names only) from `src/test/resources/tags`. */
internal fun fixture(name: String): ByteArray = File("src/test/resources/tags/$name").readBytes()

/** The first bytes of a baseline JPEG: SOI, then a SOF0 frame. */
internal val JPEG_BYTES: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 0x11, 0)

/**
 * A scripted web: each URI answers with its replies in turn (the last one repeats); anything else is a 404. Every
 * request is recorded with its User-Agent and the time [now] gives when it was sent. No socket is ever opened.
 */
internal class FakeWeb(private val now: () -> Long = { 0L }) {
    class Reply(
        val code: Int,
        val body: ByteArray = ByteArray(0),
        val location: String? = null,
        /** The Content-Length header; the body's size when null. */
        val contentLength: Long? = null,
        /** Thrown when the response code is read: a dropped connection. */
        val error: IOException? = null,
    )

    data class Request(val uri: String, val userAgent: String?, val at: Long)

    val requests = mutableListOf<Request>()
    private val routes = mutableMapOf<String, ArrayDeque<Reply>>()

    fun on(uri: URI, vararg replies: Reply) = on(uri.toString(), *replies)

    fun on(uri: String, vararg replies: Reply) {
        routes[uri] = ArrayDeque(replies.toList())
    }

    fun open(uri: URI): HttpURLConnection {
        val queue = routes[uri.toString()]
        val reply = when {
            queue == null -> Reply(404)
            queue.size > 1 -> queue.removeFirst()
            else -> queue.first()
        }
        return Connection(uri, reply)
    }

    /** Requests sent to [uri]. */
    fun count(uri: URI): Int = requests.count { it.uri == uri.toString() }

    private inner class Connection(private val uri: URI, private val reply: Reply) : HttpURLConnection(uri.toURL()) {
        override fun getResponseCode(): Int {
            requests += Request(uri.toString(), getRequestProperty("User-Agent"), now())
            reply.error?.let { throw it }
            return reply.code
        }

        override fun getHeaderField(name: String?): String? = if (name.equals("Location", ignoreCase = true)) reply.location else null

        override fun getContentLengthLong(): Long = reply.contentLength ?: reply.body.size.toLong()

        override fun getInputStream(): InputStream =
            if (reply.code < HTTP_BAD_REQUEST) ByteArrayInputStream(reply.body) else throw IOException("HTTP ${reply.code}")

        override fun getErrorStream(): InputStream? = if (reply.code >= HTTP_BAD_REQUEST) ByteArrayInputStream(reply.body) else null

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}

internal fun ok(body: ByteArray): FakeWeb.Reply = FakeWeb.Reply(200, body)

internal fun ok(body: String): FakeWeb.Reply = FakeWeb.Reply(200, body.encodeToByteArray())

internal fun redirect(code: Int, location: String): FakeWeb.Reply = FakeWeb.Reply(code, location = location)

internal fun status(code: Int): FakeWeb.Reply = FakeWeb.Reply(code, "{\"error\": \"made-up\"}".encodeToByteArray())

/** One track for [releaseJson]: its title, length in ms (null when MusicBrainz has none), and artist credit. */
internal data class T(val title: String, val lengthMs: Long?, val artist: String? = null)

/**
 * A release lookup answer, as MusicBrainz shapes it (see release.json), with every track, recording and length.
 * [discs] lists each disc's tracks. Track and recording ids are made from [id], so they are unique per release.
 */
internal fun releaseJson(
    id: String,
    title: String = "Lanterns of Ulso",
    artist: String = "Mira Solen",
    group: String = GROUP_A,
    discs: List<List<T>>,
    date: String? = "1998-04-17",
    firstReleaseDate: String? = "1998-04-17",
    front: Boolean = true,
): String {
    val various = artist == "Various Artists"
    fun credit(name: String) =
        """[{"name":${quote(name)},"joinphrase":"","artist":{"id":"${if (name == "Various Artists") Names.VARIOUS_ARTISTS_ID else "a1b2c3d4-0000-4000-8000-00000000aa01"}","name":${quote(name)}}}]"""
    var counter = 0
    val media = discs.mapIndexed { d, tracks ->
        val items = tracks.mapIndexed { i, track ->
            counter++
            val n = counter.toString().padStart(4, '0')
            val length = track.lengthMs?.toString() ?: "null"
            val trackArtist = track.artist ?: if (various) "Session $n" else artist
            """{"id":"${id.dropLast(4)}$n","position":${i + 1},"number":"${i + 1}","title":${quote(track.title)},"length":$length,""" +
                """"artist-credit":${credit(trackArtist)},"recording":{"id":"${id.take(8)}-1111-4111-8111-00000000$n","title":${quote(track.title)},"length":$length}}"""
        }
        """{"position":${d + 1},"format":"CD","track-count":${tracks.size},"track-offset":0,"tracks":[${items.joinToString(",")}]}"""
    }
    val dateField = date?.let { ""","date":"$it"""" }.orEmpty()
    val first = firstReleaseDate?.let { ""","first-release-date":"$it"""" }.orEmpty()
    return """{"id":"$id","title":${quote(title)},"status":"Official","country":"XE"$dateField,""" +
        """"artist-credit":${credit(artist)},"release-group":{"id":"$group","primary-type":"Album"$first},""" +
        """"media":[${media.joinToString(",")}],"cover-art-archive":{"front":$front,"count":1}}"""
}

/** A release search answer listing [releases] as (id, track count, score). */
internal fun searchJson(vararg releases: Triple<String, Int, Int>, title: String = "Lanterns of Ulso", artist: String = "Mira Solen"): String =
    """{"count":${releases.size},"offset":0,"releases":[""" + releases.joinToString(",") { (id, tracks, score) ->
        """{"id":"$id","score":$score,"title":${quote(title)},"artist-credit":[{"name":${quote(artist)}}],""" +
            """"release-group":{"id":"$GROUP_A","primary-type":"Album"},"track-count":$tracks,"media":[{"format":"CD","track-count":$tracks}]}"""
    } + "]}"

private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

internal const val ID_A: String = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e01"
internal const val ID_B: String = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e03"
internal const val ID_C: String = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e04"
internal const val ID_D: String = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e07"
internal const val GROUP_A: String = "6f1e2d3c-4b5a-4968-8776-5a4b3c2d1e02"
internal const val GROUP_B: String = "6f1e2d3c-4b5a-4968-8776-5a4b3c2d1e05"
