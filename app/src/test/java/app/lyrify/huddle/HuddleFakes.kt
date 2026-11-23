package app.lyrify.huddle

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Huddle's server in memory, as huddle-server/API.md has it: invite slots made by their owner, accepted once, read and
 * deleted by the owner; mailboxes of numbered messages, plays gone once read, cards until acknowledged; mailboxes
 * deleted on request. [online] false answers nothing, and the calls named in [silent] answer nothing (one route the server
 * holds back); [requests] names each call. An accept sent again with the same data
 * answers as joined (huddle-server makes it safe to retry); [lostAccepts] accepts are stored but their answer is lost.
 * The inbox answers after [slowMs], as a server KJB waits on until its timeout.
 */
class FakeServer : HuddleServer {
    var online: Boolean = true
    var silent: Set<String> = emptySet()
    var lostAccepts: Int = 0

    /** How long an accept's answer takes (virtual time): two joins of one link can run at once. */
    var acceptDelay: Long = 0
    var slowMs: Long = 0
    /** Invite id to its owner id and the accept's data, null while waiting. */
    val invites = HashMap<String, Pair<String, String?>>()
    val boxes = HashMap<String, MutableList<Posted>>()
    val kinds = LinkedHashMap<Long, Kind>()
    val requests = ArrayList<String>()
    private var seq = 0L

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
        boxes.getOrPut(box) { ArrayList() } += Posted(next, data)
        kinds[next] = kind
        return PostAnswer.POSTED
    }

    override suspend fun inbox(boxes: List<String>): Map<String, List<Posted>>? {
        requests += "inbox"
        delay(slowMs)
        if (!online || "inbox" in silent) return null
        val read = boxes.associateWith { this.boxes[it].orEmpty().toList() }.filterValues { it.isNotEmpty() }
        // Plays go as they are read.
        read.values.flatten().filter { kinds[it.seq] == Kind.PLAY }.forEach { play -> this.boxes.values.forEach { box -> box.removeAll { it.seq == play.seq } } }
        return read
    }

    override suspend fun ack(upTo: Map<String, Long>): Boolean {
        requests += "ack"
        if (!online || "ack" in silent) return false
        upTo.forEach { (box, last) -> boxes[box]?.removeAll { it.seq <= last } }
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
        boxes.getOrPut(box) { ArrayList() } += Posted(next, data)
        kinds[next] = Kind.CARD
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
