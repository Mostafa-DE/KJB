package app.lyrify.huddle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/** A message waiting in a mailbox: the server's [seq] in it, and the sealed [data] (base64url). */
data class Posted(val seq: Long, val data: String)

/** A join posted to an invite's join mailbox: the friend's [publicKey] and sealed [hello] (base64url). */
data class PostedJoin(val publicKey: String, val hello: String)

enum class JoinAnswer { JOINED, TAKEN, NO_ANSWER }

/** How a post went: [REFUSED] is the server's no (the box is full, or the message too big), not a missing answer. */
enum class PostAnswer { POSTED, REFUSED, NO_ANSWER }

/**
 * Huddle's server as KJB uses it (huddle-server/API.md): join mailboxes and mailboxes of sealed boxes, no accounts.
 * Knowing a mailbox's id is the right to use it. Never throws: no answer reads as null, false or NO_ANSWER.
 */
interface HuddleServer {
    /** Posts a join to [joinId]: JOINED, or TAKEN when someone joined first. */
    suspend fun join(joinId: String, publicKey: String, hello: String): JoinAnswer

    /** The joins posted to [joinIds], by id (an id without one is left out). */
    suspend fun joins(joinIds: List<String>): Map<String, PostedJoin>?

    suspend fun forgetJoin(joinId: String): Boolean

    suspend fun post(box: String, data: String, keep: Keep): PostAnswer

    /** The messages waiting in [boxes], by box (a box without any is left out). */
    suspend fun fetch(boxes: List<String>): Map<String, List<Posted>>?

    /** Deletes each box's messages up to its seq. */
    suspend fun ack(upTo: Map<String, Long>): Boolean

    /** Deletes the box and what waits in it. */
    suspend fun drop(box: String): Boolean
}

/** How accepting an invite went. */
sealed interface Accepted {
    data class Joined(val friend: Friend) : Accepted

    /** Already friends through this invite: a second tap on the link. */
    data class Already(val friend: Friend) : Accepted

    /** Someone else used the invite first. */
    data object Taken : Accepted

    /** Your own invite. */
    data object Own : Accepted

    /** Your name is not set yet: KJB asks for it first. */
    data object NoName : Accepted

    /** Huddle's server did not answer: nothing was made, the link can be tried again. */
    data object NoAnswer : Accepted
}

/**
 * Huddle (plan part 3, 3b): friends by invite, cards and plays through the server's mailboxes, all sealed on the phone.
 *
 * - Cards go after [UNDO_MS] (the toast's Undo), plays at once, each sealed with the friend's key for their mailbox;
 *   what cannot go waits in [HuddleState.outbox] and goes at the next [sync], a play within a day only.
 * - A play goes only to friends you share plays with ([Friend.sharePlays]), never while you listen privately.
 * - [sync] asks the server only when there is something to ask: invites waiting for a join, friends' mailboxes, the outbox.
 *   Each message is read once ([Friend.seen]) and acknowledged so the server deletes it.
 * - [syncWhileInFront]: when KJB comes to the front, then every hour; every [FAST_MS] while the Huddle page is open ([pageOpen]).
 *
 * Nothing here logs a name, a title, a note, a key or a mailbox. One sync or send at a time.
 */
class Huddle(
    private val storage: HuddleStorage,
    private val server: HuddleServer,
    /** The host new invite links name: Huddle's address. */
    private val host: String,
    /** The hosts KJB opens invite links of (AndroidManifest.xml). */
    private val hosts: List<String>,
    private val clock: () -> Long,
    private val random: SecureRandom,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    val state: Flow<HuddleState> = storage.state

    /** The Huddle page is open: friends' plays refresh every [FAST_MS] instead of every hour. */
    val pageOpen: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val one = Mutex()

    @Volatile
    private var lastSync: Long? = null

    /** Sets your name as friends see it; false when nothing is left of [name] ([Invites.cleanName]). */
    suspend fun setName(name: String): Boolean {
        val clean = Invites.cleanName(name) ?: return false
        return storage.change { it.copy(name = clean) }
    }

    /**
     * A new invite's link, with [card] in it when it fits ([LINK_CARD_MAX_BYTES]), else sent once a friend joins; null
     * before your name is set or when the invite could not be kept.
     */
    suspend fun invite(card: Card?): String? {
        val name = now().name ?: return null
        val pair = HuddleCrypto.keyPair(random)
        val token = ByteArray(Invites.TOKEN_BYTES).also(random::nextBytes)
        val inLink = card?.takeIf { Messages.cardJson(it).toString().encodeToByteArray().size <= LINK_CARD_MAX_BYTES }
        val pending = PendingInvite(HuddleCrypto.encode(pair.public), HuddleCrypto.encode(pair.private), HuddleCrypto.encode(token), clock(), card.takeIf { inLink == null })
        if (!storage.change { it.copy(invites = (it.invites + pending).takeLast(INVITES_MAX)) }) return null
        return Invites.link(host, Invite(pair.public, token, name, inLink), random)
    }

    /** The invite in [text] (a link on Huddle's hosts, or a pasted code); null for anything else. */
    fun parse(text: String): Invite? = Invites.parse(text, hosts)

    /** Joins [invite]: the inviter becomes your friend, and the song in it waits in your inbox. Needs the server's answer. */
    suspend fun accept(invite: Invite): Accepted {
        val state = now()
        val joinId = Invites.joinId(invite.token)
        state.friends.firstOrNull { it.joinId == joinId }?.let { return Accepted.Already(it) }
        if (state.invites.any { it.token == HuddleCrypto.encode(invite.token) }) return Accepted.Own
        val name = state.name ?: return Accepted.NoName
        // A parsed invite's key is a point of the curve (Invites.parse), so this joins; one that is not cannot be joined by anyone.
        val joining = Invites.join(invite, name, random) ?: return Accepted.Taken
        when (server.join(joinId, HuddleCrypto.encode(joining.publicKey), HuddleCrypto.encode(joining.hello))) {
            JoinAnswer.TAKEN -> return Accepted.Taken
            JoinAnswer.NO_ANSWER -> return Accepted.NoAnswer
            JoinAnswer.JOINED -> Unit
        }
        val friend = friendOf(invite.name, joining.keys, joinId)
        val card = invite.card?.let { Received(newId(MESSAGE_ID_BYTES), friend.id, clock(), it) }
        if (!storage.change { it.copy(friends = it.friends + friend, inbox = listOfNotNull(card) + it.inbox) }) return Accepted.NoAnswer
        return Accepted.Joined(friend)
    }

    /** Sends [card] to the friend [friendId] after [UNDO_MS]: its message's id (for [unsend]), or null for a friend gone. */
    suspend fun send(friendId: String, card: Card): String? {
        val now = clock()
        val friend = now().friends.firstOrNull { it.id == friendId } ?: return null
        val message = Message.Share(newId(MESSAGE_ID_BYTES), now, card)
        val out = outgoing(friend, message, Keep.MONTH, notBefore = now + UNDO_MS) ?: return null
        val kept = storage.change { state ->
            if (state.friends.none { it.id == friendId }) state else state.copy(outbox = state.outbox + out, friends = state.friends.map { if (it.id == friendId) it.copy(lastSentAt = now) else it })
        }
        if (!kept) return null
        scope.launch {
            delay(UNDO_MS)
            flush()
        }
        return message.id
    }

    /** Undo: the card [messageId] stays on the phone and is forgotten, when it has not gone yet. */
    suspend fun unsend(messageId: String): Boolean = one.withLock {
        if (now().outbox.none { it.id == messageId }) return@withLock false
        storage.change { state -> state.copy(outbox = state.outbox.filterNot { it.id == messageId }) }
    }

    /** Posts what waits to go and is due; false when the server did not answer. */
    suspend fun flush(): Boolean = one.withLock { flushNow() }

    /** Joins, the outbox, then friends' mailboxes; false when the server did not answer. Asks nothing when there is nothing to ask. */
    suspend fun sync(): Boolean = one.withLock {
        lastSync = clock()
        joins() && flushNow() && fetch()
    }

    /** You played [song], from [at]: each friend you share plays with gets it, unless you listen privately. */
    suspend fun played(song: CardSong, at: Long) {
        val now = clock()
        val state = now()
        if (state.privateUntil?.let { now < it } == true) return
        val outs = state.friends.filter { it.sharePlays }.mapNotNull { outgoing(it, Message.Play(newId(MESSAGE_ID_BYTES), at, song), Keep.DAY, notBefore = now) }
        if (outs.isNotEmpty() && storage.change { it.copy(outbox = it.outbox + outs) }) flush()
    }

    /** Your own name for the friend [friendId], on this phone only; null takes it back. */
    suspend fun rename(friendId: String, name: String?): Boolean = changeFriend(friendId) { it.copy(rename = name?.let(Invites::cleanName)) }

    /** Friends sharing, per friend: [on] sends them each play from now. */
    suspend fun sharePlays(friendId: String, on: Boolean): Boolean = changeFriend(friendId) { it.copy(sharePlays = on) }

    /**
     * Removes the friend [friendId]: they are told (and their KJB removes you too), your mailbox from them is dropped,
     * and their cards and plays go from this phone.
     */
    suspend fun remove(friendId: String): Boolean {
        val friend = now().friends.firstOrNull { it.id == friendId } ?: return false
        val bye = outgoing(friend, Message.Bye(newId(MESSAGE_ID_BYTES), clock()), Keep.MONTH, notBefore = clock())
        val kept = storage.change { state ->
            state.copy(
                friends = state.friends.filterNot { it.id == friendId },
                inbox = state.inbox.filterNot { it.friend == friendId },
                plays = state.plays.filterNot { it.friend == friendId },
                outbox = state.outbox.filterNot { it.box == friend.outBox } + listOfNotNull(bye),
            )
        }
        if (!kept) return false
        flush()
        server.drop(friend.inBox)
        return true
    }

    /** Listen privately until [until] (wall-clock millis; [HuddleState.FOREVER] until turned back on), or no longer (null). */
    suspend fun listenPrivately(until: Long?): Boolean = storage.change { it.copy(privateUntil = until) }

    /** The card [receivedId] was played, looked at or put away: it leaves the start screen. */
    suspend fun opened(receivedId: String): Boolean = storage.change { state -> state.copy(inbox = state.inbox.map { if (it.id == receivedId) it.copy(opened = true) else it }) }

    /**
     * The backup passphrase: kept as its key only ([HuddleBackup.newKey]), so each backup from now holds your friends,
     * sealed. False for one shorter than [HuddleBackup.PASSPHRASE_MIN]. Slow on purpose: off the main thread.
     */
    suspend fun setPassphrase(passphrase: CharArray): Boolean {
        if (passphrase.size < HuddleBackup.PASSPHRASE_MIN) return false
        val key = HuddleBackup.newKey(passphrase, random)
        return storage.change { it.copy(backupKey = key) }
    }

    /**
     * KJB is in front, until the job returned is cancelled: syncs now, then an hour after the last sync; while the Huddle
     * page is open ([pageOpen]), at once and then every [FAST_MS]. Each sync is a job of its own in [scope], so one under
     * way runs on when KJB goes.
     */
    fun syncWhileInFront(): Job = scope.launch {
        var first = true
        pageOpen.collectLatest { open ->
            val every = if (open) FAST_MS else HOUR_MS
            if (first || open) {
                first = false
                scope.launch { sync() }.join()
            }
            while (true) {
                delay(((lastSync ?: clock()) + every - clock()).coerceIn(1, every))
                scope.launch { sync() }.join()
            }
        }
    }

    private suspend fun now(): HuddleState = storage.state.first()

    private suspend fun changeFriend(friendId: String, change: (Friend) -> Friend): Boolean {
        if (now().friends.none { it.id == friendId }) return false
        return storage.change { state -> state.copy(friends = state.friends.map { if (it.id == friendId) change(it) else it }) }
    }

    private fun friendOf(name: String, keys: PairKeys, joinId: String): Friend = Friend(
        id = newId(FRIEND_ID_BYTES),
        name = name,
        outBox = keys.outBox,
        inBox = keys.inBox,
        outKey = HuddleCrypto.encode(keys.outKey),
        inKey = HuddleCrypto.encode(keys.inKey),
        joinId = joinId,
        addedAt = clock(),
    )

    /** [message] sealed for [friend]'s mailbox, bound to it; null when the friend's key is not readable. */
    private fun outgoing(friend: Friend, message: Message, keep: Keep, notBefore: Long): Outgoing? {
        val key = HuddleCrypto.decode(friend.outKey) ?: return null
        val sealed = HuddleCrypto.seal(key, Messages.encode(message), friend.outBox.encodeToByteArray(), random)
        return Outgoing(message.id, friend.outBox, HuddleCrypto.encode(sealed), keep, message.at, notBefore)
    }

    /** Pending invites: expired ones go, joined ones become friends (and get their card), the join mailbox is forgotten. */
    private suspend fun joins(): Boolean {
        val now = clock()
        val invites = now().invites
        val expired = invites.filter { now - it.madeAt > INVITE_KEPT_MS }.map { it.token }.toSet()
        if (expired.isNotEmpty()) storage.change { state -> state.copy(invites = state.invites.filterNot { it.token in expired }) }
        val waiting = invites.filterNot { it.token in expired }.mapNotNull { invite -> HuddleCrypto.decode(invite.token)?.let { Invites.joinId(it) to invite } }.toMap()
        if (waiting.isEmpty()) return true
        val posted = server.joins(waiting.keys.toList()) ?: return false
        for ((joinId, join) in posted) {
            val invite = waiting[joinId] ?: continue
            val joined = joinedOf(invite, join)
            if (joined == null) {
                // Not a hello for this invite: forgotten, so the friend it was meant for can still join.
                log("huddle: a join that does not open, forgotten")
                server.forgetJoin(joinId)
                continue
            }
            val friend = friendOf(joined.name, joined.keys, joinId)
            val card = invite.card?.let { outgoing(friend, Message.Share(newId(MESSAGE_ID_BYTES), now, it), Keep.MONTH, notBefore = now) }
            val kept = storage.change { state ->
                state.copy(friends = state.friends + friend, invites = state.invites.filterNot { it.token == invite.token }, outbox = state.outbox + listOfNotNull(card))
            }
            if (kept) server.forgetJoin(joinId)
        }
        return true
    }

    private fun joinedOf(invite: PendingInvite, join: PostedJoin): Joined? {
        val own = KeyPairBytes(HuddleCrypto.decode(invite.publicKey) ?: return null, HuddleCrypto.decode(invite.privateKey) ?: return null)
        val token = HuddleCrypto.decode(invite.token) ?: return null
        return Invites.joined(own, token, HuddleCrypto.decode(join.publicKey) ?: return null, HuddleCrypto.decode(join.hello) ?: return null)
    }

    /** Posts the outbox's due messages in order, dropping those older than the server would keep; stops at the first unanswered. */
    private suspend fun flushNow(): Boolean {
        val now = clock()
        val outbox = now().outbox
        val gone = outbox.filter { now - it.at > keptMs(it.keep) }.mapTo(HashSet()) { it.id }
        var answered = true
        for (out in outbox.filter { it.id !in gone && it.notBefore <= now }) {
            when (server.post(out.box, out.data, out.keep)) {
                PostAnswer.POSTED -> gone += out.id
                // A play the friend's full mailbox refused is not tried again; a card is, until the server would have dropped it.
                PostAnswer.REFUSED -> if (out.keep == Keep.DAY) gone += out.id
                PostAnswer.NO_ANSWER -> {
                    answered = false
                    break
                }
            }
        }
        if (gone.isNotEmpty()) storage.change { state -> state.copy(outbox = state.outbox.filterNot { it.id in gone }) }
        return answered
    }

    /** Friends' mailboxes: each message opened with its friend's key, kept once, then acknowledged; a goodbye removes the friend. */
    private suspend fun fetch(): Boolean {
        val friends = now().friends
        if (friends.isEmpty()) return true
        val posted = server.fetch(friends.map { it.inBox }) ?: return false
        if (posted.isEmpty()) return true
        val upTo = HashMap<String, Long>()
        val read = ArrayList<Pair<String, Message>>()
        for (friend in friends) {
            val box = posted[friend.inBox] ?: continue
            val key = HuddleCrypto.decode(friend.inKey) ?: continue
            for (one in box.sortedBy { it.seq }) {
                upTo[friend.inBox] = maxOf(upTo[friend.inBox] ?: one.seq, one.seq)
                // A box that does not open (another key, sealed for the other way, changed) is dropped with the rest.
                val message = HuddleCrypto.decode(one.data)?.let { HuddleCrypto.open(key, it, friend.inBox.encodeToByteArray()) }?.let(Messages::decode) ?: continue
                read += friend.id to message
            }
        }
        val now = clock()
        if (!storage.change { it.received(read, now) }) return false
        server.ack(upTo)
        read.filter { it.second is Message.Bye }.mapNotNull { (id, _) -> friends.firstOrNull { it.id == id } }.forEach { gone ->
            server.drop(gone.inBox)
            server.drop(gone.outBox)
        }
        return true
    }

    /** This state with [read] messages kept: each once, from friends still here; cards and plays kept within their limits. */
    private fun HuddleState.received(read: List<Pair<String, Message>>, now: Long): HuddleState {
        var state = this
        for ((friendId, message) in read) {
            val friend = state.friends.firstOrNull { it.id == friendId } ?: continue
            if (message.id in friend.seen) continue
            val seen = friend.copy(seen = (friend.seen + message.id).takeLast(SEEN_MAX))
            state = state.copy(friends = state.friends.map { if (it.id == friendId) seen else it })
            state = when (message) {
                is Message.Share -> state.copy(inbox = listOf(Received(message.id, friendId, message.at, message.card)) + state.inbox)
                is Message.Play -> state.copy(plays = listOf(FriendPlay(friendId, message.at, message.song)) + state.plays)
                is Message.Bye -> state.copy(
                    friends = state.friends.filterNot { it.id == friendId },
                    inbox = state.inbox.filterNot { it.friend == friendId },
                    plays = state.plays.filterNot { it.friend == friendId },
                    outbox = state.outbox.filterNot { it.box == friend.outBox },
                )
            }
        }
        return state.copy(
            inbox = state.inbox.sortedByDescending { it.at }.take(INBOX_MAX),
            plays = state.plays.filter { now - it.at <= PLAYS_KEPT_MS }.sortedByDescending { it.at }.take(PLAYS_MAX),
        )
    }

    private fun newId(bytes: Int): String = HuddleCrypto.encode(ByteArray(bytes).also(random::nextBytes))

    private fun keptMs(keep: Keep): Long = when (keep) {
        Keep.DAY -> DAY_MS
        Keep.MONTH -> MONTH_MS
    }

    companion object {
        /** How often friends' plays refresh while the Huddle page is open (plan 3b). */
        const val FAST_MS: Long = 30_000
        const val HOUR_MS: Long = 60 * 60_000L
        private const val DAY_MS: Long = 24 * HOUR_MS
        private const val MONTH_MS: Long = 30 * DAY_MS

        /** How long a sent card waits for Undo before it goes: the toast's time. */
        const val UNDO_MS: Long = 5_000

        /** An invite nobody joined goes after two weeks. */
        const val INVITE_KEPT_MS: Long = 14 * DAY_MS

        /** Friends' plays are kept 60 days: "this week" and "love" need no more. */
        const val PLAYS_KEPT_MS: Long = 60 * DAY_MS

        /** A card fits the link when its JSON is at most this long: about one song with a note. */
        private const val LINK_CARD_MAX_BYTES: Int = 600
        private const val INVITES_MAX: Int = 20
        private const val INBOX_MAX: Int = 100
        private const val PLAYS_MAX: Int = 5_000
        private const val SEEN_MAX: Int = 200
        private const val MESSAGE_ID_BYTES: Int = 16
        private const val FRIEND_ID_BYTES: Int = 8
    }
}
