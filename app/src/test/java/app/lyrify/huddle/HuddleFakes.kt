package app.lyrify.huddle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Huddle's server in memory, as huddle-server/API.md has it: one join per join mailbox, mailboxes of numbered sealed
 * boxes, ack deletes up to a number, drop deletes a mailbox. [online] false answers nothing; [requests] counts each call.
 */
class FakeServer : HuddleServer {
    var online: Boolean = true
    val joined = HashMap<String, PostedJoin>()
    val boxes = HashMap<String, MutableList<Posted>>()
    val keeps = LinkedHashMap<Long, Keep>()
    val requests = ArrayList<String>()
    private var seq = 0L

    /** The fetches so far: one per sync that had something to ask. */
    val fetches: Int get() = requests.count { it == "fetch" }

    override suspend fun join(joinId: String, publicKey: String, hello: String): JoinAnswer {
        requests += "join"
        if (!online) return JoinAnswer.NO_ANSWER
        if (joinId in joined) return JoinAnswer.TAKEN
        joined[joinId] = PostedJoin(publicKey, hello)
        return JoinAnswer.JOINED
    }

    override suspend fun joins(joinIds: List<String>): Map<String, PostedJoin>? {
        requests += "joins"
        return if (online) joinIds.mapNotNull { id -> joined[id]?.let { id to it } }.toMap() else null
    }

    override suspend fun forgetJoin(joinId: String): Boolean {
        requests += "forgetJoin"
        if (online) joined.remove(joinId)
        return online
    }

    override suspend fun post(box: String, data: String, keep: Keep): PostAnswer {
        requests += "post"
        if (!online) return PostAnswer.NO_ANSWER
        val next = ++seq
        boxes.getOrPut(box) { ArrayList() } += Posted(next, data)
        keeps[next] = keep
        return PostAnswer.POSTED
    }

    override suspend fun fetch(boxes: List<String>): Map<String, List<Posted>>? {
        requests += "fetch"
        return if (online) boxes.associateWith { this.boxes[it].orEmpty().toList() }.filterValues { it.isNotEmpty() } else null
    }

    override suspend fun ack(upTo: Map<String, Long>): Boolean {
        requests += "ack"
        if (online) upTo.forEach { (box, last) -> boxes[box]?.removeAll { it.seq <= last } }
        return online
    }

    override suspend fun drop(box: String): Boolean {
        requests += "drop"
        if (online) boxes.remove(box)
        return online
    }

    /** Puts [data] in [box] again, as a server replaying an old message would. */
    fun replay(box: String, data: String) {
        boxes.getOrPut(box) { ArrayList() } += Posted(++seq, data)
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
