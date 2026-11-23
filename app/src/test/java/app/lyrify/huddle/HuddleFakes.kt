package app.lyrify.huddle

import app.lyrify.stats.JdbcPlayLog
import app.lyrify.stats.PlayRow
import app.lyrify.stats.PlaysSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Huddle's server in memory, as huddle-server/API.md has it: invite slots made by their owner, accepted once, read and
 * deleted by the owner; mailboxes of numbered messages, plays gone once read, cards until acknowledged, a new now in
 * place of the one waiting and read by every read until then (an acknowledgement leaves it); mailboxes deleted on
 * request. A read asks each box after a seq; one with a wait is held until a message after it waits in one of its
 * boxes, or the wait is up. Each message is dated by [clock], the server's, as a phone with that clock reads it.
 * [online] false answers nothing, and the calls named in [silent] answer nothing (one route the server holds back);
 * [requests] names each call. An accept sent again with the same data answers as joined (huddle-server makes it safe
 * to retry); [lostAccepts] accepts are stored but their answer is lost. The inbox answers after [slowMs], as a server
 * KJB waits on until its timeout.
 */
class FakeServer : HuddleServer {
    var online: Boolean = true
    var silent: Set<String> = emptySet()
    var lostAccepts: Int = 0

    /** How long an accept's answer takes (virtual time): two joins of one link can run at once. */
    var acceptDelay: Long = 0
    var slowMs: Long = 0
    var clock: () -> Long = { 0 }
    /** Invite id to its owner id and the accept's data, null while waiting. */
    val invites = HashMap<String, Pair<String, String?>>()
    val boxes = HashMap<String, MutableList<Posted>>()
    val kinds = LinkedHashMap<Long, Kind>()
    val requests = ArrayList<String>()
    private var seq = 0L

    /** The newest message's seq: a held read looks again at each. */
    private val landed = MutableStateFlow(0L)

    /** The inbox reads so far: one per sync that had friends to ask for. */
    val fetches: Int get() = requests.count { it == "inbox" }

    override suspend fun createInvite(invite: String, owner: String): Boolean {
        requests += "createInvite"
        if (!online || invite in invites) return false
        invites[invite] = owner to null
        return true
    }

    override suspend fun acceptInvite(invite: String, data: String): JoinAnswer {
        requests += "acceptInvite"
        if (acceptDelay > 0) delay(acceptDelay)
        if (!online) return JoinAnswer.NO_ANSWER
        val (owner, accepted) = invites[invite] ?: return JoinAnswer.GONE
        if (accepted != null) return if (accepted == data) JoinAnswer.JOINED else JoinAnswer.TAKEN
        invites[invite] = owner to data
        if (lostAccepts > 0) {
            lostAccepts--
            return JoinAnswer.NO_ANSWER
        }
        return JoinAnswer.JOINED
    }

    override suspend fun inviteStatus(owner: String): InviteStatus? {
        requests += "inviteStatus"
        if (!online || "inviteStatus" in silent) return null
        val accepted = invites.values.firstOrNull { it.first == owner } ?: return InviteStatus.Gone
        return accepted.second?.let { InviteStatus.Accepted(it) } ?: InviteStatus.Waiting
    }

    override suspend fun deleteInvite(owner: String): Boolean {
        requests += "deleteInvite"
        if (online) invites.entries.removeAll { it.value.first == owner }
        return online
    }

    override suspend fun send(box: String, kind: Kind, data: String): PostAnswer {
        requests += "send"
        if (!online || "send" in silent) return PostAnswer.NO_ANSWER
        val next = ++seq
        val posted = boxes.getOrPut(box) { ArrayList() }
        if (kind == Kind.NOW) posted.removeAll { kinds[it.seq] == Kind.NOW }
        posted += Posted(next, data, clock())
        kinds[next] = kind
        landed.value = next
        return PostAnswer.POSTED
    }

    override suspend fun inbox(boxes: List<String>, after: Map<String, Long>, waitMs: Long): Map<String, List<Posted>>? {
        requests += "inbox"
        delay(slowMs)
        if (!online || "inbox" in silent) return null
        val newer = { boxes.associateWith { box -> this.boxes[box].orEmpty().filter { it.seq > (after[box] ?: 0) } }.filterValues { it.isNotEmpty() } }
        if (waitMs > 0) withTimeoutOrNull(waitMs) { landed.first { newer().isNotEmpty() } }
        val read = newer()
        // Plays go as they are read.
        read.values.flatten().filter { kinds[it.seq] == Kind.PLAY }.forEach { once -> this.boxes.values.forEach { box -> box.removeAll { it.seq == once.seq } } }
        return read
    }

    override suspend fun ack(upTo: Map<String, Long>): Boolean {
        requests += "ack"
        if (!online || "ack" in silent) return false
        upTo.forEach { (box, last) -> boxes[box]?.removeAll { it.seq <= last && kinds[it.seq] != Kind.NOW } }
        return true
    }

    override suspend fun dropBoxes(boxes: List<String>): Boolean {
        requests += "dropBoxes"
        if (online) boxes.forEach { this.boxes.remove(it) }
        return online
    }

    /** Puts [data] in [box] again as a card, as a server replaying an old message would. */
    fun replay(box: String, data: String) {
        val next = ++seq
        boxes.getOrPut(box) { ArrayList() } += Posted(next, data, clock())
        kinds[next] = Kind.CARD
        landed.value = next
    }

    fun waiting(box: String): List<Posted> = boxes[box].orEmpty()
}

/** Huddle's state in memory; [writable] false fails every change, as a full disk would. */
class MemoryHuddle(initial: HuddleState = HuddleState()) : HuddleStorage {
    private val flow = MutableStateFlow(initial)
    var writable: Boolean = true
    override val state: StateFlow<HuddleState> = flow
    val now: HuddleState get() = flow.value

    override suspend fun change(change: (HuddleState) -> HuddleState): Boolean {
        if (writable) flow.value = change(flow.value)
        return writable
    }
}

val lantern = CardSong("Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000)
val bell = CardSong("Brass Bell", "Kit Calloway", "Harbour Glass", 187_500)

/**
 * Huddle's history on plays.db's own SQL ([PlaysSchema], version 6) on the laptop's SQLite, in memory ([db]): what
 * [SqlHuddleLog] runs on the phone, the same statements with the same values ([keptCardArgs], [friendPlayArgs]), a query's
 * values bound as text as the phone binds them, and rows read the same way ([keptCardOf], [friendPlayOf]). [failing]:
 * every write and read fails, as a full or damaged plays.db would; [forgetFails]: only letting a friend go fails.
 */
class JdbcHuddleLog(val db: JdbcPlayLog = JdbcPlayLog()) : HuddleLog, AutoCloseable {
    var failing: Boolean = false
    var forgetFails: Boolean = false

    override suspend fun keep(cards: List<KeptCard>, plays: List<FriendPlay>, playsFrom: Long): Boolean = write {
        cards.forEach { db.update(PlaysSchema.KEEP_CARD, *keptCardArgs(it)) }
        plays.forEach { db.update(PlaysSchema.KEEP_FRIEND_PLAY, *friendPlayArgs(it)) }
        db.update(PlaysSchema.FRIEND_PLAYS_BEFORE, playsFrom)
        plays.mapTo(HashSet()) { it.friend }.forEach { db.update(PlaysSchema.FRIEND_PLAYS_PAST, it, HuddleLog.PLAYS_PER_FRIEND) }
    }

    override suspend fun card(id: String): KeptCard? = read(PlaysSchema.CARD, id, row = ::keptCardOf)?.firstOrNull()

    override suspend fun thread(friend: String, from: Int, limit: Int): List<KeptCard>? = read(PlaysSchema.THREAD, friend, limit.toString(), from.toString(), row = ::keptCardOf)

    override suspend fun received(from: Int, limit: Int): List<Received>? = read(PlaysSchema.RECEIVED, limit.toString(), from.toString()) { keptCardOf(it) as? Received }

    override suspend fun cards(from: Int, limit: Int): List<KeptCard>? = read(PlaysSchema.CARDS, limit.toString(), from.toString(), row = ::keptCardOf)

    override suspend fun plays(friend: String?, from: Int, limit: Int): List<FriendPlay>? = if (friend == null) {
        read(PlaysSchema.ALL_FRIEND_PLAYS, limit.toString(), from.toString(), row = ::friendPlayOf)
    } else {
        read(PlaysSchema.FRIEND_PLAYS, friend, limit.toString(), from.toString(), row = ::friendPlayOf)
    }

    override suspend fun playsSince(from: Long): List<FriendPlay>? = read(PlaysSchema.FRIEND_PLAYS_SINCE, from.toString(), row = ::friendPlayOf)

    override suspend fun opened(id: String, dismissed: Boolean): Boolean = write { db.update(PlaysSchema.CARD_OPENED, if (dismissed) 1L else 0L, id) }

    override suspend fun forget(friend: String): Boolean = !forgetFails && write {
        db.update(PlaysSchema.FORGET_FRIEND_CARDS, friend)
        db.update(PlaysSchema.FORGET_FRIEND_PLAYS, friend)
    }

    override fun close() = db.close()

    private fun write(work: () -> Unit): Boolean {
        if (failing) return false
        db.transaction(work)
        return true
    }

    private fun <T> read(sql: String, vararg args: String, row: (PlayRow) -> T?): List<T>? = if (failing) {
        null
    } else {
        db.query(sql, *args) { rows ->
            val at = JdbcPlayLog.ResultRow(rows)
            buildList { while (rows.next()) row(at)?.let(::add) }
        }
    }
}
