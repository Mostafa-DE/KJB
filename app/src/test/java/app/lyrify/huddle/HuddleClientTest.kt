package app.lyrify.huddle

import app.lyrify.backup.SectionJson.jsonObject
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Huddle's HTTP calls as huddle-server/API.md has them: what each sends, how each answer reads, retries and limits. */
class HuddleClientTest {
    private val host = "kjb-huddle.up.railway.app"
    private val box = "b".repeat(43)
    private val other = "c".repeat(43)

    /** One answer: [code], [body] and a Retry-After of [retryAfter] seconds, or a network failure ([fails]). */
    private class Answer(val code: Int, val body: String = "", val fails: Boolean = false, val retryAfter: String? = null)

    private class FakeConnection(uri: URI, private val answer: Answer) : HttpURLConnection(uri.toURL()) {
        val headers = mutableMapOf<String, String>()
        val sent = ByteArrayOutputStream()

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getOutputStream(): OutputStream {
            if (answer.fails) throw IOException("no network")
            return sent
        }

        override fun getResponseCode(): Int = if (answer.fails) throw IOException("no network") else answer.code

        override fun getInputStream(): InputStream = if (answer.code >= 400) throw IOException("an error") else ByteArrayInputStream(answer.body.toByteArray())

        override fun getErrorStream(): InputStream? = if (answer.code >= 400) ByteArrayInputStream(answer.body.toByteArray()) else null

        override fun getContentLengthLong(): Long = answer.body.length.toLong()

        override fun getHeaderField(name: String): String? = answer.retryAfter.takeIf { name.equals("Retry-After", ignoreCase = true) }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit

        fun json(): JsonObject = jsonObject(sent.toString(Charsets.UTF_8.name()))!!
    }

    private val opened = mutableListOf<FakeConnection>()

    /** The wall clock the client reads. */
    private var now = 1_700_000_000_000L

    /** What the client logged. */
    private val logged = mutableListOf<String>()

    private fun TestScope.client(vararg answers: Answer): HuddleClient {
        val queue = answers.toMutableList()
        return HuddleClient(host, "KJB/test", StandardTestDispatcher(testScheduler), log = { logged += it }, clock = { now }) { uri -> FakeConnection(uri, queue.removeAt(0)).also { opened += it } }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `every call is a POST of JSON to the API on Huddle's host, with KJB's name, ids only in the body`() = runTest {
        assertTrue(client(Answer(201, """{"expiresAt":1}""")).createInvite("i".repeat(43), "o".repeat(43)))
        val call = opened.single()
        assertEquals("https://$host/api/v1/invites/create", call.url.toString())
        assertEquals("POST", call.requestMethod)
        assertEquals("application/json", call.headers["Content-Type"])
        assertEquals("KJB/test", call.headers["User-Agent"])
        assertFalse(call.instanceFollowRedirects)
        assertEquals("i".repeat(43), call.json().string("invite"))
        assertEquals("o".repeat(43), call.json().string("owner"))
    }

    @Test
    fun `an accept joins, finds the invite taken or expired, or gets no answer`() = runTest {
        val huddle = client(Answer(200, """{"acceptedAt":1}"""), Answer(409, """{"error":"already_accepted"}"""), Answer(404, """{"error":"not_found"}"""), Answer(500), Answer(0, fails = true))
        assertEquals(JoinAnswer.JOINED, huddle.acceptInvite("i".repeat(43), "ZGF0YQ"))
        assertEquals("ZGF0YQ", opened[0].json().string("data"))
        assertEquals("https://$host/api/v1/invites/accept", opened[0].url.toString())
        assertEquals(JoinAnswer.TAKEN, huddle.acceptInvite("i".repeat(43), "ZGF0YQ"))
        assertEquals(JoinAnswer.GONE, huddle.acceptInvite("i".repeat(43), "ZGF0YQ"))
        assertEquals(JoinAnswer.NO_ANSWER, huddle.acceptInvite("i".repeat(43), "ZGF0YQ"))
        assertEquals(JoinAnswer.NO_ANSWER, huddle.acceptInvite("i".repeat(43), "ZGF0YQ"))
    }

    @Test
    fun `an invite's status is waiting, accepted with its data, or gone`() = runTest {
        val huddle = client(
            Answer(200, """{"state":"waiting","expiresAt":5}"""),
            Answer(200, """{"state":"accepted","data":"ZGF0YQ","acceptedAt":1,"expiresAt":5}"""),
            Answer(404, """{"error":"not_found"}"""),
            Answer(200, """{"state":"later"}"""),
        )
        assertEquals(InviteStatus.Waiting, huddle.inviteStatus("o".repeat(43)))
        assertEquals(InviteStatus.Accepted("ZGF0YQ"), huddle.inviteStatus("o".repeat(43)))
        assertEquals(InviteStatus.Gone, huddle.inviteStatus("o".repeat(43)))
        assertNull(huddle.inviteStatus("o".repeat(43)))
        assertEquals("https://$host/api/v1/invites/status", opened[0].url.toString())
        assertEquals("o".repeat(43), opened[0].json().string("owner"))
    }

    @Test
    fun `a send names its kind, and a full mailbox is a no, a busy server no answer`() = runTest {
        val huddle = client(
            Answer(201, """{"id":"17","sentAt":1,"expiresAt":2}"""),
            Answer(409, """{"error":"mailbox_full"}"""),
            Answer(413, """{"error":"too_large"}"""),
            Answer(429, """{"error":"rate_limited"}"""),
            Answer(0, fails = true),
        )
        assertEquals(PostAnswer.POSTED, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        assertEquals("card", opened[0].json().string("kind"))
        assertEquals(box, opened[0].json().string("mailbox"))
        assertEquals("https://$host/api/v1/messages/send", opened[0].url.toString())
        assertEquals(PostAnswer.REFUSED, huddle.send(box, Kind.PLAY, "ZGF0YQ"))
        assertEquals("play", opened[1].json().string("kind"))
        assertEquals(PostAnswer.REFUSED, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        assertEquals(PostAnswer.NO_ANSWER, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        assertEquals(PostAnswer.NO_ANSWER, huddle.send(box, Kind.CARD, "ZGF0YQ"))
    }

    @Test
    fun `a 502 or a bare 503 while Railway restarts is tried once more, a full store is no answer for now`() = runTest {
        val huddle = client(Answer(502), Answer(201, """{"id":"1"}"""), Answer(503), Answer(503), Answer(503, """{"error":"storage_full"}"""))
        assertEquals(PostAnswer.POSTED, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        assertEquals(PostAnswer.NO_ANSWER, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        // The server takes messages again once it has cleared old ones: what waits to go stays.
        assertEquals(PostAnswer.NO_ANSWER, huddle.send(box, Kind.PLAY, "ZGF0YQ"))
        assertEquals(5, opened.size)
    }

    @Test
    fun `after a 429 its own endpoint waits exactly its Retry-After, at most a day, and the others are asked as before`() = runTest {
        val huddle = client(
            Answer(429, """{"error":"rate_limited"}""", retryAfter = "120"),
            Answer(200, """{"now":1,"mailboxes":[]}"""),
            Answer(201, """{"id":"1"}"""),
            Answer(429, """{"error":"rate_limited"}""", retryAfter = "9999999"),
            Answer(201, """{"id":"2"}"""),
        )
        assertEquals(PostAnswer.NO_ANSWER, huddle.send(box, Kind.PLAY, "ZGF0YQ"))
        assertEquals("another endpoint is asked at once", emptyMap<String, List<Posted>>(), huddle.inbox(listOf(box)))
        now += 119_000
        assertEquals(PostAnswer.NO_ANSWER, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        assertEquals("asked while it waits: no call at all", 2, opened.size)
        assertTrue(logged.toString(), "huddle: messages/send not asked (retry_after, 1 s left)" in logged)
        now += 1_000
        assertEquals(PostAnswer.POSTED, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        assertEquals(PostAnswer.NO_ANSWER, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        now += 24 * 60 * 60_000L
        assertEquals("a wait longer than a day is a day", PostAnswer.POSTED, huddle.send(box, Kind.CARD, "ZGF0YQ"))
        assertEquals(5, opened.size)
    }

    @Test
    fun `the inbox reads every mailbox in one call and follows more with after`() = runTest {
        val huddle = client(
            Answer(200, """{"now":1,"mailboxes":[{"mailbox":"$box","messages":[{"id":"3","kind":"card","data":"YQ","sentAt":1},{"id":"x","kind":"card","data":"Yg","sentAt":1}],"more":true},{"mailbox":"$other","messages":[],"more":false}]}"""),
            Answer(200, """{"now":2,"mailboxes":[{"mailbox":"$box","messages":[{"id":"9","kind":"play","data":"Yw","sentAt":2}],"more":false}]}"""),
        )
        assertEquals(mapOf(box to listOf(Posted(3, "YQ"), Posted(9, "Yw"))), huddle.inbox(listOf(box, other)))
        val first = opened[0].json()["mailboxes"] as JsonArray
        assertEquals(listOf(box, other), first.map { (it as JsonObject).string("mailbox") })
        assertNull((first[0] as JsonObject)["after"])
        val second = (opened[1].json()["mailboxes"] as JsonArray).single() as JsonObject
        assertEquals(box, second.string("mailbox"))
        assertEquals("x", second.string("after"))
    }

    @Test
    fun `a later inbox page that gets no answer keeps the pages read, as the server already let their plays go`() = runTest {
        val huddle = client(
            Answer(200, """{"now":1,"mailboxes":[{"mailbox":"$box","messages":[{"id":"3","kind":"play","data":"YQ","sentAt":1}],"more":true}]}"""),
            Answer(0, fails = true),
        )
        assertEquals(mapOf(box to listOf(Posted(3, "YQ"))), huddle.inbox(listOf(box)))
    }

    @Test
    fun `no answer to the inbox is null, never an empty inbox`() = runTest {
        assertNull(client(Answer(0, fails = true)).inbox(listOf(box)))
        assertNull(client(Answer(200, "not json")).inbox(listOf(box)))
        assertNull(client(Answer(301)).inbox(listOf(box)))
    }

    @Test
    fun `acks, deletes and drops send what the API asks`() = runTest {
        val huddle = client(Answer(200, """{"deleted":2}"""), Answer(200, """{"deleted":1}"""), Answer(200, """{"deleted":4}"""), Answer(500))
        assertTrue(huddle.ack(mapOf(box to 17L)))
        val ack = (opened[0].json()["acks"] as JsonArray).single() as JsonObject
        assertEquals(box, ack.string("mailbox"))
        assertEquals("17", ack.string("upTo"))
        assertEquals("https://$host/api/v1/messages/ack", opened[0].url.toString())
        assertTrue(huddle.deleteInvite("o".repeat(43)))
        assertEquals("https://$host/api/v1/invites/delete", opened[1].url.toString())
        assertTrue(huddle.dropBoxes(listOf(box, other)))
        assertEquals(listOf(box, other), (opened[2].json()["mailboxes"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals("https://$host/api/v1/mailboxes/delete", opened[2].url.toString())
        assertFalse(huddle.dropBoxes(listOf(box)))
    }

    @Test
    fun `nothing to ask is no call at all`() = runTest {
        val huddle = client()
        assertEquals(emptyMap<String, List<Posted>>(), huddle.inbox(emptyList()))
        assertTrue(huddle.ack(emptyMap()))
        assertTrue(huddle.dropBoxes(emptyList()))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an answer longer than any the server gives is no answer, and says so in the log`() = runTest {
        val huge = """{"now":1,"mailboxes":[{"mailbox":"$box","messages":[{"id":"1","kind":"card","data":"${"A".repeat(3 * 1024 * 1024)}","sentAt":1}],"more":false}]}"""
        assertNull(client(Answer(200, huge)).inbox(listOf(box)))
        assertEquals(listOf("huddle: messages/inbox answer too long"), logged)
    }
}
