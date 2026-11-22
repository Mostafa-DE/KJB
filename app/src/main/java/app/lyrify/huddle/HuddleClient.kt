package app.lyrify.huddle

import app.lyrify.backup.SectionJson.jsonObject
import app.lyrify.backup.SectionJson.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

/**
 * [HuddleServer] over HTTPS on [host], as huddle-server/API.md has it: every call a POST of JSON to `/api/v1/...`, ids
 * only in the body (never in a URL a log could keep), 15 s timeouts, no redirect followed, at most [MAX_ANSWER_BYTES]
 * read. A 502, or a 503 without the server's own error, is Railway restarting: tried once more after [RETRY_MS]. A
 * 5xx or no network is no answer, and the next sync tries again; a 429 is no answer, and that endpoint is not asked again
 * until its Retry-After is over ([MAX_WAIT_MS] at most), the others as before. The log gets the call's name, its status
 * and a reason code only.
 */
class HuddleClient(
    private val host: String,
    private val userAgent: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : HuddleServer {
    override suspend fun createInvite(invite: String, owner: String): Boolean =
        call("invites/create", body(INVITE to JsonPrimitive(invite), OWNER to JsonPrimitive(owner)))?.code == CREATED

    override suspend fun acceptInvite(invite: String, data: String): JoinAnswer = when (call("invites/accept", body(INVITE to JsonPrimitive(invite), DATA to JsonPrimitive(data)))?.code) {
        OK -> JoinAnswer.JOINED
        CONFLICT -> JoinAnswer.TAKEN
        NOT_FOUND -> JoinAnswer.GONE
        else -> JoinAnswer.NO_ANSWER
    }

    override suspend fun inviteStatus(owner: String): InviteStatus? {
        val answer = call("invites/status", body(OWNER to JsonPrimitive(owner))) ?: return null
        if (answer.code == NOT_FOUND) return InviteStatus.Gone
        val json = answer.json?.takeIf { answer.code == OK } ?: return null
        return when (json.text(STATE)) {
            WAITING -> InviteStatus.Waiting
            ACCEPTED -> json.text(DATA)?.let { InviteStatus.Accepted(it) }
            else -> null
        }
    }

    override suspend fun deleteInvite(owner: String): Boolean = call("invites/delete", body(OWNER to JsonPrimitive(owner)))?.code == OK

    override suspend fun send(box: String, kind: Kind, data: String): PostAnswer {
        val answer = call("messages/send", body(MAILBOX to JsonPrimitive(box), KIND to JsonPrimitive(kind.name.lowercase()), DATA to JsonPrimitive(data)))
        return when {
            answer == null -> PostAnswer.NO_ANSWER
            answer.code == CREATED -> PostAnswer.POSTED
            // A full mailbox, a message too big, a request the server cannot take: the same send would fail again soon. A full
            // store is no answer for now: the server takes messages again once it has cleared old ones.
            answer.code in REFUSALS -> PostAnswer.REFUSED
            else -> PostAnswer.NO_ANSWER
        }
    }

    override suspend fun inbox(boxes: List<String>): Map<String, List<Posted>>? {
        val read = LinkedHashMap<String, MutableList<Posted>>()
        // The server lets plays go as it hands them out: once a page came, what was read is kept, and the next sync asks again.
        var answered = false
        for (chunk in boxes.distinct().chunked(MAX_IDS)) {
            // Each box's cursor ("after") while the server has more for it; a box is asked again only then.
            var asking: Map<String, String?> = chunk.associateWith { null }
            repeat(MAX_PAGES) {
                if (asking.isEmpty()) return@repeat
                val ask = JsonArray(asking.map { (box, after) -> JsonObject(buildMap { put(MAILBOX, JsonPrimitive(box)); after?.let { put(AFTER, JsonPrimitive(it)) } }) })
                val json = call("messages/inbox", body(MAILBOXES to ask))?.takeIf { it.code == OK }?.json
                val pages = json?.get(MAILBOXES) as? JsonArray ?: return read.takeIf { answered }
                answered = true
                val more = HashMap<String, String?>()
                for (element in pages) {
                    val one = element as? JsonObject ?: continue
                    val box = one.text(MAILBOX)?.takeIf { it in asking } ?: continue
                    val messages = (one[MESSAGES] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    messages.forEach { message ->
                        val seq = message.text(ID)?.toLongOrNull() ?: return@forEach
                        message.text(DATA)?.let { read.getOrPut(box) { ArrayList() } += Posted(seq, it) }
                    }
                    if ((one[MORE] as? JsonPrimitive)?.content == "true") more[box] = messages.lastOrNull()?.text(ID) ?: asking[box]
                }
                asking = more
            }
        }
        return read
    }

    override suspend fun ack(upTo: Map<String, Long>): Boolean = upTo.entries.chunked(MAX_IDS).all { chunk ->
        val acks = JsonArray(chunk.map { (box, last) -> JsonObject(mapOf(MAILBOX to JsonPrimitive(box), UP_TO to JsonPrimitive(last.toString()))) })
        call("messages/ack", body(ACKS to acks))?.code == OK
    }

    override suspend fun dropBoxes(boxes: List<String>): Boolean = boxes.distinct().chunked(MAX_IDS).all { chunk ->
        call("mailboxes/delete", body(MAILBOXES to JsonArray(chunk.map(::JsonPrimitive))))?.code == OK
    }

    /** By endpoint, no call to it before this time (wall-clock millis): the server asked it to wait (429, Retry-After). */
    private val quietUntil = ConcurrentHashMap<String, Long>()

    /** What one call answered: its status and its JSON, if any. */
    private class Answer(val code: Int, val json: JsonObject?)

    private fun body(vararg fields: Pair<String, JsonElement>): String = JsonObject(mapOf(*fields)).toString()

    /** POSTs [body] to [endpoint] (`/api/v1/<endpoint>`), once more after a restart's 502 or bare 503; null without an answer. */
    private suspend fun call(endpoint: String, body: String): Answer? {
        val left = (quietUntil[endpoint] ?: 0L) - clock()
        if (left > 0) {
            log("huddle: $endpoint not asked (retry_after, ${(left + 999) / 1_000} s left)")
            return null
        }
        val first = withContext(io) { exchange(endpoint, body) }
        if (first == null || !restarting(first)) return first
        delay(RETRY_MS)
        return withContext(io) { exchange(endpoint, body) }
    }

    private fun restarting(answer: Answer): Boolean = answer.code == BAD_GATEWAY || (answer.code == SERVICE_UNAVAILABLE && answer.json?.text(ERROR) == null)

    /** One POST, blocking; null when nothing usable came back (no network, a redirect, an answer too long). */
    private fun exchange(endpoint: String, body: String): Answer? {
        val uri = try {
            URI("https", host, "$API/$endpoint", null)
        } catch (_: URISyntaxException) {
            return null
        }
        val connection = try {
            open(uri)
        } catch (_: IOException) {
            return null
        }
        return try {
            connection.requestMethod = POST
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", JSON_TYPE)
            connection.setRequestProperty("Accept", JSON_TYPE)
            connection.setRequestProperty("User-Agent", userAgent)
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            val code = connection.responseCode
            if (code == TOO_MANY_REQUESTS) {
                connection.getHeaderField(RETRY_AFTER)?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { seconds ->
                    quietUntil[endpoint] = clock() + minOf(seconds, MAX_WAIT_MS / 1_000) * 1_000
                }
            }
            if (code in REDIRECTS) {
                log("huddle: $endpoint redirected, not followed")
                return null
            }
            val stream = if (code >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            val text = stream?.use {
                read(it) ?: run {
                    log("huddle: $endpoint answer too long")
                    return null
                }
            }
            if (code != OK && code != CREATED) log("huddle: $endpoint answered $code")
            Answer(code, text?.let(::jsonObject))
        } catch (e: IOException) {
            log("huddle: $endpoint got no answer (${e.javaClass.simpleName})")
            null
        } finally {
            connection.disconnect()
        }
    }

    /** [input]'s text; null when it is longer than [MAX_ANSWER_BYTES]. */
    private fun read(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_ANSWER_BYTES) return null
            out.write(buffer, 0, count)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        /** The inbox answers about 1 MiB at most (API.md); twice that is not Huddle's server. */
        const val MAX_ANSWER_BYTES: Int = 2 * 1024 * 1024

        /** How long after a restart's 502 or 503 the call is tried again. */
        const val RETRY_MS: Long = 3_000

        /** The longest wait a 429's Retry-After can ask: a day. */
        const val MAX_WAIT_MS: Long = 24 * 60 * 60_000L
        private const val TOO_MANY_REQUESTS = 429
        private const val RETRY_AFTER = "Retry-After"
        private const val API = "/api/v1"
        private const val POST = "POST"
        private const val JSON_TYPE = "application/json"
        private const val TIMEOUT_MS = 15_000
        private const val BUFFER_BYTES = 16 * 1024

        /** Ids one call takes at most (inbox, ack, mailboxes/delete). */
        private const val MAX_IDS = 64

        /** Pages of one inbox read: 100 messages a box each, far more than friends send between two syncs. */
        private const val MAX_PAGES = 10
        private const val OK = HttpURLConnection.HTTP_OK
        private const val CREATED = HttpURLConnection.HTTP_CREATED
        private const val NOT_FOUND = HttpURLConnection.HTTP_NOT_FOUND
        private const val CONFLICT = HttpURLConnection.HTTP_CONFLICT
        private const val BAD_GATEWAY = HttpURLConnection.HTTP_BAD_GATEWAY
        private const val SERVICE_UNAVAILABLE = HttpURLConnection.HTTP_UNAVAILABLE
        private val REDIRECTS = 300..399

        /** Bad request, mailbox full, too large, wrong type: the server's no to this very send. */
        private val REFUSALS = setOf(HttpURLConnection.HTTP_BAD_REQUEST, CONFLICT, HttpURLConnection.HTTP_ENTITY_TOO_LARGE, HttpURLConnection.HTTP_UNSUPPORTED_TYPE)
        private const val INVITE = "invite"
        private const val OWNER = "owner"
        private const val DATA = "data"
        private const val STATE = "state"
        private const val WAITING = "waiting"
        private const val ACCEPTED = "accepted"
        private const val MAILBOX = "mailbox"
        private const val MAILBOXES = "mailboxes"
        private const val KIND = "kind"
        private const val MESSAGES = "messages"
        private const val ID = "id"
        private const val AFTER = "after"
        private const val MORE = "more"
        private const val ACKS = "acks"
        private const val UP_TO = "upTo"
        private const val ERROR = "error"
    }
}
