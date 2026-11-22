package app.lyrify.huddle

import app.lyrify.suggestions.FriendsSource
import app.lyrify.suggestions.FriendPlay as SuggestionsPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** A message waiting in a mailbox: the server's [seq] in it (its message id), and the sealed [data] (base64url). */
data class Posted(val seq: Long, val data: String)

/** How an invite stands on the server. */
sealed interface InviteStatus {
    data object Waiting : InviteStatus

    /** A friend accepted it, with [data]: their public key and sealed hello. */
    data class Accepted(val data: String) : InviteStatus

    /** Expired, deleted, or never made. */
    data object Gone : InviteStatus
}

/** How accepting an invite on the server went: [TAKEN] someone accepted first, [GONE] it expired. */
enum class JoinAnswer { JOINED, TAKEN, GONE, NO_ANSWER }

/** How a send went: [REFUSED] is the server's no (the friend's mailbox is full, or the message too big), not a missing answer. */
enum class PostAnswer { POSTED, REFUSED, NO_ANSWER }

/**
 * Huddle's server as KJB uses it (huddle-server/API.md, lane hs1): invite slots and mailboxes of sealed messages, no
 * accounts. Knowing an id is the right to use it. Never throws: no answer reads as null, false or NO_ANSWER.
 */
interface HuddleServer {
    /** Makes the invite slot [invite], which only [owner] reads and deletes; false without it. */
    suspend fun createInvite(invite: String, owner: String): Boolean

    suspend fun acceptInvite(invite: String, data: String): JoinAnswer

    suspend fun inviteStatus(owner: String): InviteStatus?

    suspend fun deleteInvite(owner: String): Boolean

    suspend fun send(box: String, kind: Kind, data: String): PostAnswer

    /** The messages waiting in [boxes], by box (a box without any is left out), oldest first. */
    suspend fun inbox(boxes: List<String>): Map<String, List<Posted>>?

    /** Deletes each box's messages up to its seq. */
    suspend fun ack(upTo: Map<String, Long>): Boolean

    /** Deletes [boxes] and what waits in them. */
    suspend fun dropBoxes(boxes: List<String>): Boolean
}

/** How making an invite went. */
sealed interface Invited {
    data class Link(val text: String) : Invited

    /** Your name is not set yet: KJB asks for it first. */
    data object NoName : Invited

    /** Huddle's server did not answer: no invite was made. */
    data object NoAnswer : Invited
}

/** How accepting an invite went. */
sealed interface Accepted {
    /** Friends now; [received]: the id of the card that came with the invite, in your inbox. */
    data class Joined(val friend: Friend, val received: String? = null) : Accepted

    /** Already friends through this invite: a second tap on the link. */
    data class Already(val friend: Friend) : Accepted

    /** Someone else used the invite first. */
    data object Taken : Accepted

    /** The invite is older than a week, or its maker took it back. */
    data object Expired : Accepted

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
 *   what cannot go waits in [HuddleState.outbox] and goes at the next [sync], a play within a day only. A card without an
 *   answer is tried again after each of [RETRY_MS]'s waits.
 * - A play goes only to friends you share plays with ([Friend.sharePlays], on from the join), never while you listen
 *   privately.
 * - Names are one per friend list: a newcomer whose name another friend goes by shows under a stand-in ([Friend.taken])
 *   and is asked for another through a [Message.Profile], the message that also carries each new name of yours.
 * - [sync] asks the server only when there is something to ask: invites waiting for a join, friends' mailboxes, the outbox,
 *   each whatever the others answered. Each message is read once ([Friend.seen]) and acknowledged so the server deletes it.
 * - [syncWhileInFront]: when KJB comes to the front, then every [FAST_MS] on any page; after a sync without an answer,
 *   after each of [RETRY_MS]'s waits, then hourly. Nothing while KJB is not in front.
 *
 * Nothing here logs a name, a title, a note, a key or a mailbox: a step that did not go logs a reason code. One sync or
 * send at a time.
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

    private val one = Mutex()

    /**
     * Sets your name as friends see it; false when nothing is left of [name] ([Invites.cleanName]). A new one goes to
     * every friend ([Message.Profile]), and nobody is waiting for another any more: a friend whose list it is taken in
     * says so again.
     */
    suspend fun setName(name: String): Boolean {
        val clean = Invites.cleanName(name) ?: return false
        val now = clock()
        var changed = false
        val kept = storage.change { state ->
            changed = clean != state.name
            if (!changed) {
                state
            } else {
                state.copy(
                    name = clean,
                    friends = state.friends.map { it.copy(asksNewName = false) },
                    outbox = state.outbox.filterNot { it.kind == Kind.PROFILE } + state.friends.mapNotNull { profile(it, clean, now) },
                )
            }
        }
        if (kept && changed) flush()
        return kept
    }

    /**
     * A new invite's link, with [card] in it when it fits ([LINK_CARD_MAX_BYTES]), else sent once a friend joins. The
     * server gets the invite's slot first, so a friend can accept it.
     */
    suspend fun invite(card: Card?): Invited {
        val name = now().name ?: return Invited.NoName
        val pair = HuddleCrypto.keyPair(random)
        val token = ByteArray(Invites.TOKEN_BYTES).also(random::nextBytes)
        val inLink = card?.takeIf { Messages.cardJson(it).toString().encodeToByteArray().size <= LINK_CARD_MAX_BYTES }
        val pending = PendingInvite(HuddleCrypto.encode(pair.public), HuddleCrypto.encode(pair.private), HuddleCrypto.encode(token), newId(OWNER_ID_BYTES), clock(), card.takeIf { inLink == null })
        if (!server.createInvite(Invites.joinId(token, pair.public), pending.owner)) return Invited.NoAnswer
        if (!storage.change { it.copy(invites = (it.invites + pending).takeLast(INVITES_MAX)) }) {
            server.deleteInvite(pending.owner)
            return Invited.NoAnswer
        }
        return Invited.Link(Invites.link(host, Invite(pair.public, token, name, inLink), random))
    }

    /** The invite in [text] (a link on Huddle's hosts, or a pasted code); null for anything else. */
    fun parse(text: String): Invite? = Invites.parse(text, hosts)

    /**
     * Joins [invite]: the inviter becomes your friend, and the song in it waits in your inbox. Needs the server's answer.
     * The join ([PendingJoin]) is kept before its accept goes, and sent again as it was when the answer was lost or the
     * friend could not be written, so the server knows it and both phones keep the same keys. Sent again and gone from the
     * server, it most likely reached the inviter, whose KJB deleted the slot once it read it: this phone joins too.
     */
    suspend fun accept(invite: Invite): Accepted {
        val state = now()
        val joinId = Invites.joinId(invite.token, invite.publicKey)
        state.friends.firstOrNull { it.joinId == joinId }?.let { return Accepted.Already(it) }
        if (state.invites.any { it.token == HuddleCrypto.encode(invite.token) }) return Accepted.Own
        val name = state.name ?: return Accepted.NoName
        val earlier = state.joins.firstOrNull { it.joinId == joinId }
        val join = earlier ?: run {
            // A parsed invite's key is a point of the curve (Invites.parse), so this joins; one that is not cannot be joined by anyone.
            val joining = Invites.join(invite, name, random) ?: return Accepted.Taken
            val keys = joining.keys
            val made = PendingJoin(joinId, HuddleCrypto.encode(joining.publicKey + joining.hello), keys.outBox, keys.inBox, HuddleCrypto.encode(keys.outKey), HuddleCrypto.encode(keys.inKey), invite.name, invite.card)
            if (!storage.change { it.copy(joins = (it.joins + made).takeLast(JOINS_MAX)) }) return Accepted.NoAnswer
            made
        }
        when (server.acceptInvite(joinId, join.data)) {
            JoinAnswer.NO_ANSWER -> return Accepted.NoAnswer
            JoinAnswer.TAKEN -> {
                endJoin(joinId)
                return Accepted.Taken
            }
            JoinAnswer.GONE -> if (earlier == null) {
                endJoin(joinId)
                return Accepted.Expired
            }
            JoinAnswer.JOINED -> Unit
        }
        val now = clock()
        var friend = friendOf(join.name, join.outBox, join.inBox, join.outKey, join.inKey, joinId)
        val card = join.card?.let { Received(newId(MESSAGE_ID_BYTES), friend.id, now, it) }
        var asked = false
        val kept = storage.change { state ->
            // The inviter is the newcomer to this phone's list: a name taken here is asked for again, as on theirs.
            friend = newcomer(friend, state.friends)
            val ask = friend.taken?.let { state.name?.let { name -> profile(friend, name, now) } }
            asked = ask != null
            state.copy(
                friends = state.friends + friend,
                inbox = listOfNotNull(card) + state.inbox,
                joins = state.joins.filterNot { it.joinId == joinId },
                outbox = state.outbox + listOfNotNull(ask),
            )
        }
        if (kept && asked) scope.launch { flush() }
        return if (kept) Accepted.Joined(friend, card?.id) else Accepted.NoAnswer
    }

    /**
     * Sends [card] to the friend [friendId] once [holdMs] is over (the toast's time, its Undo; [UNDO_MS] at least): its
     * message's id (for [unsend]), or null for a friend gone.
     */
    suspend fun send(friendId: String, card: Card, holdMs: Long = UNDO_MS): String? {
        val now = clock()
        val friend = now().friends.firstOrNull { it.id == friendId } ?: return null
        val message = Message.Share(newId(MESSAGE_ID_BYTES), now, card)
        val hold = holdMs.coerceAtLeast(UNDO_MS)
        val out = outgoing(friend, message, Kind.CARD, notBefore = now + hold) ?: return null
        val kept = storage.change { state ->
            if (state.friends.none { it.id == friendId }) state else state.copy(outbox = state.outbox + out, friends = state.friends.map { if (it.id == friendId) it.copy(lastSentAt = now) else it })
        }
        if (!kept) return null
        scope.launch {
            delay(hold)
            if (flush()) return@launch
            for (wait in RETRY_MS) {
                log("huddle: card not sent, next try in ${wait / 1_000} s")
                delay(wait)
                if (flush()) return@launch
            }
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

    /**
     * Joins, the outbox, then friends' mailboxes, each whatever the others answered; false when the server did not answer
     * one. Asks nothing when there is nothing to ask.
     */
    suspend fun sync(): Boolean = one.withLock {
        // `and`, not `&&`: a step without an answer never keeps the next from running.
        joins() and flushNow() and fetch()
    }

    /** You played [song], from [at], a [favourite] of yours or not: each friend you share plays with gets it, unless you listen privately. */
    suspend fun played(song: CardSong, at: Long, favourite: Boolean = false) {
        val now = clock()
        val state = now()
        if (state.privateUntil?.let { now < it } == true) return
        val outs = state.friends.filter { it.sharePlays }.mapNotNull { outgoing(it, Message.Play(newId(MESSAGE_ID_BYTES), at, song, favourite), Kind.PLAY, notBefore = now) }
        if (outs.isNotEmpty() && storage.change { it.copy(outbox = it.outbox + outs) }) flush()
    }

    /** Your own name for the friend [friendId], on this phone only; null takes it back. */
    suspend fun rename(friendId: String, name: String?): Boolean = changeFriend(friendId) { it.copy(rename = name?.let(Invites::cleanName)) }

    /** Friends sharing, per friend: [on] sends them each play from now. */
    suspend fun sharePlays(friendId: String, on: Boolean): Boolean = withContext(NonCancellable) { changeFriend(friendId) { it.copy(sharePlays = on) } }

    /**
     * Removes the friend [friendId]: they are told (and their KJB removes you too), your mailbox from them is dropped,
     * and their cards and plays go from this phone.
     */
    suspend fun remove(friendId: String): Boolean {
        val friend = now().friends.firstOrNull { it.id == friendId } ?: return false
        val bye = outgoing(friend, Message.Bye(newId(MESSAGE_ID_BYTES), clock()), Kind.CARD, notBefore = clock())
        val kept = storage.change { state ->
            state.copy(
                friends = state.friends.filterNot { it.id == friendId },
                inbox = state.inbox.filterNot { it.friend == friendId },
                plays = state.plays.filterNot { it.friend == friendId },
                outbox = state.outbox.filterNot { it.box == friend.outBox } + listOfNotNull(bye),
                joined = state.joined - friendId,
            )
        }
        if (!kept) return false
        flush()
        server.dropBoxes(listOf(friend.inBox))
        return true
    }

    /** Cancels your invite with [token] (base64url): it goes from the phone and the server, so the link no longer works. */
    suspend fun cancel(token: String): Boolean = one.withLock {
        val invite = now().invites.firstOrNull { it.token == token } ?: return@withLock false
        server.deleteInvite(invite.owner) && forget(listOf(invite))
    }

    /** The backup's Huddle section now ([HuddleBackup.seal]); null without a backup passphrase. */
    suspend fun backupSection(): String? = HuddleBackup.seal(now(), random)

    /**
     * Restores a backup's Huddle [section] opened with [passphrase] into what this phone has ([restored]), its key kept
     * for the next backups: how many friends it holds, or null when the passphrase does not open it (or it could not be
     * written). Slow, as [HuddleBackup.open]: off the main thread.
     */
    suspend fun restore(section: String, passphrase: CharArray): Int? {
        val backup = HuddleBackup.open(section, passphrase) ?: return null
        val key = backup.backupKey ?: return null
        return backup.friends.size.takeIf { storage.change { it.restored(backup, key) } }
    }

    /** Listen privately until [until] (wall-clock millis; [HuddleState.FOREVER] until turned back on), or no longer (null). */
    suspend fun listenPrivately(until: Long?): Boolean = withContext(NonCancellable) { storage.change { it.copy(privateUntil = until) } }

    /** The friend [friendId] who joined your invite was greeted ("<name> joined your Huddle!"): never again. */
    suspend fun celebrated(friendId: String): Boolean = withContext(NonCancellable) { storage.change { it.copy(joined = it.joined - friendId) } }

    /** The card [receivedId] was played or looked at; [dismissed]: put away, it also leaves the start screen. */
    suspend fun opened(receivedId: String, dismissed: Boolean = false): Boolean = withContext(NonCancellable) {
        storage.change { state -> state.copy(inbox = state.inbox.map { if (it.id == receivedId) it.copy(opened = true, dismissed = it.dismissed || dismissed) else it }) }
    }

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
     * KJB is in front, until the job returned is cancelled: syncs now, then every [FAST_MS] on any page (friends' plays
     * show in Library, on the player and the Now bar too). A sync without an answer is tried again after each of
     * [RETRY_MS]'s waits, then each hour until one answers. Each sync is a job of its own in [scope], so one under way
     * runs on when KJB goes.
     */
    fun syncWhileInFront(): Job = scope.launch {
        // Syncs in a row without an answer (or that broke off): the next waits the step of RETRY_MS they reached.
        var failed = 0
        while (true) {
            var answered = false
            scope.launch { answered = sync() }.join()
            failed = if (answered) 0 else failed + 1
            val wait = if (failed == 0) FAST_MS else RETRY_MS.getOrNull(failed - 1) ?: HOUR_MS
            if (failed > 0) log("huddle: sync failed $failed in a row, next try in ${wait / 1_000} s")
            delay(wait)
        }
    }

    private suspend fun now(): HuddleState = storage.state.first()

    private suspend fun changeFriend(friendId: String, change: (Friend) -> Friend): Boolean {
        if (now().friends.none { it.id == friendId }) return false
        return storage.change { state -> state.copy(friends = state.friends.map { if (it.id == friendId) change(it) else it }) }
    }

    /** A new friend [name], with the friendship's mailboxes and keys (base64url) and the [joinId] that made it. */
    private fun friendOf(name: String, outBox: String, inBox: String, outKey: String, inKey: String, joinId: String): Friend =
        Friend(id = newId(FRIEND_ID_BYTES), name = name, outBox = outBox, inBox = inBox, outKey = outKey, inKey = inKey, joinId = joinId, addedAt = clock())

    /** [friend] as a newcomer to [friends]: under a stand-in name ([Friend.taken]) when one of them goes by theirs. */
    private fun newcomer(friend: Friend, friends: List<Friend>): Friend =
        if (friends.any { sameName(it.shownName, friend.name) }) friend.copy(taken = standIn(friend.name, friends)) else friend

    /** Your [name] for [friend], and whether theirs is taken in your list ([Friend.taken]), sealed for their mailbox. */
    private fun profile(friend: Friend, name: String, now: Long): Outgoing? =
        outgoing(friend, Message.Profile(newId(MESSAGE_ID_BYTES), now, name, taken = friend.taken != null), Kind.PROFILE, notBefore = now)

    /** The join [joinId] answered for good (taken, or gone): nothing is left to send again. */
    private suspend fun endJoin(joinId: String) {
        storage.change { state -> state.copy(joins = state.joins.filterNot { it.joinId == joinId }) }
    }

    /** [message] sealed for [friend]'s mailbox, bound to it; null when the friend's key is not readable. */
    private fun outgoing(friend: Friend, message: Message, kind: Kind, notBefore: Long): Outgoing? {
        val key = HuddleCrypto.decode(friend.outKey) ?: return null
        val sealed = HuddleCrypto.seal(key, Messages.encode(message), friend.outBox.encodeToByteArray(), random)
        return Outgoing(message.id, friend.outBox, HuddleCrypto.encode(sealed), kind, message.at, notBefore)
    }

    /**
     * Pending invites: those past their week go without asking; for the others the server says waiting, accepted (the
     * friend is added, with the card that did not fit the link, and the slot deleted) or gone (it expired there).
     */
    private suspend fun joins(): Boolean {
        val now = clock()
        val invites = now().invites
        // Gone from the server too, so it is not asked.
        val expired = invites.filter { now - it.madeAt > INVITE_KEPT_MS }
        forget(expired)
        for (invite in invites - expired.toSet()) {
            val status = server.inviteStatus(invite.owner) ?: run {
                log("huddle: invites not checked (no_answer)")
                return false
            }
            when (status) {
                InviteStatus.Waiting -> Unit
                InviteStatus.Gone -> forget(listOf(invite))
                is InviteStatus.Accepted -> {
                    val joined = joinedOf(invite, status.data)
                    if (joined == null) {
                        // Not a hello for this invite: the slot is used up, so the invite ends and nobody is added.
                        log("huddle: an accept that does not open, invite ended")
                        if (forget(listOf(invite))) server.deleteInvite(invite.owner)
                    } else {
                        val joinId = Invites.joinId(HuddleCrypto.decode(invite.token) ?: continue, HuddleCrypto.decode(invite.publicKey) ?: continue)
                        val keys = joined.keys
                        val friend = friendOf(joined.name, keys.outBox, keys.inBox, HuddleCrypto.encode(keys.outKey), HuddleCrypto.encode(keys.inKey), joinId)
                        val card = invite.card?.let { outgoing(friend, Message.Share(newId(MESSAGE_ID_BYTES), now, it), Kind.CARD, notBefore = now) }
                        val kept = storage.change { state ->
                            val named = newcomer(friend, state.friends)
                            val ask = named.taken?.let { state.name?.let { name -> profile(named, name, now) } }
                            state.copy(
                                friends = state.friends + named,
                                invites = state.invites.filterNot { it.token == invite.token },
                                outbox = state.outbox + listOfNotNull(card, ask),
                                joined = state.joined + named.id,
                            )
                        }
                        if (kept) server.deleteInvite(invite.owner)
                    }
                }
            }
        }
        return true
    }

    /** [invites] forgotten on this phone; false when that could not be written. */
    private suspend fun forget(invites: List<PendingInvite>): Boolean {
        if (invites.isEmpty()) return true
        val tokens = invites.map { it.token }.toSet()
        return storage.change { state -> state.copy(invites = state.invites.filterNot { it.token in tokens }) }
    }

    /** The friend who accepted [invite] with [data] (their public key, then their sealed hello); null when it does not open. */
    private fun joinedOf(invite: PendingInvite, data: String): Joined? {
        val own = KeyPairBytes(HuddleCrypto.decode(invite.publicKey) ?: return null, HuddleCrypto.decode(invite.privateKey) ?: return null)
        val token = HuddleCrypto.decode(invite.token) ?: return null
        val bytes = HuddleCrypto.decode(data)?.takeIf { it.size > HuddleCrypto.PUBLIC_KEY_BYTES } ?: return null
        val key = bytes.copyOfRange(0, HuddleCrypto.PUBLIC_KEY_BYTES)
        return Invites.joined(own, token, key, bytes.copyOfRange(HuddleCrypto.PUBLIC_KEY_BYTES, bytes.size))
    }

    /** Posts the outbox's due messages in order, dropping those older than the server would keep; stops at the first unanswered. */
    private suspend fun flushNow(): Boolean {
        val now = clock()
        val state = now()
        val outbox = state.outbox
        // A play still waiting goes only while you share with that friend and do not listen privately.
        val private = state.privateUntil?.let { now < it } == true
        val sharing = state.friends.filter { it.sharePlays }.mapTo(HashSet()) { it.outBox }
        val gone = outbox.filter { now - it.at > keptMs(it.kind) || (it.kind == Kind.PLAY && (private || it.box !in sharing)) }.mapTo(HashSet()) { it.id }
        var answered = true
        for (out in outbox.filter { it.id !in gone && it.notBefore <= now }) {
            when (server.send(out.box, out.kind, out.data)) {
                PostAnswer.POSTED -> gone += out.id
                // A play the friend's full mailbox refused is not tried again; a card is, until the server would have dropped it.
                PostAnswer.REFUSED -> if (out.kind == Kind.PLAY) gone += out.id
                PostAnswer.NO_ANSWER -> {
                    log("huddle: outbox stopped (no_answer)")
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
        val posted = server.inbox(friends.map { it.inBox }) ?: run {
            log("huddle: inbox not read (no_answer)")
            return false
        }
        val now = clock()
        // Nothing new: only plays past their time go.
        if (posted.isEmpty()) return now().plays.none { now - it.at > PLAYS_KEPT_MS } || storage.change { it.received(emptyList(), now) }
        val upTo = HashMap<String, Long>()
        val read = ArrayList<Pair<String, Message>>()
        var closed = 0
        for (friend in friends) {
            val box = posted[friend.inBox] ?: continue
            val key = HuddleCrypto.decode(friend.inKey) ?: continue
            for (one in box.sortedBy { it.seq }) {
                upTo[friend.inBox] = maxOf(upTo[friend.inBox] ?: one.seq, one.seq)
                // A box that does not open (another key, sealed for the other way, changed) is dropped with the rest.
                val message = HuddleCrypto.decode(one.data)?.let { HuddleCrypto.open(key, it, friend.inBox.encodeToByteArray()) }?.let(Messages::decode)
                if (message == null) closed++ else read += friend.id to message
            }
        }
        if (closed > 0) log("huddle: inbox dropped $closed that did not open (not_opened)")
        // Whether what came asks for an answer (a name taken in your list, or free again): it goes in this sync.
        var answers = false
        val kept = storage.change { state ->
            state.received(read, now).also { next -> answers = next.outbox.any { out -> state.outbox.none { it.id == out.id } } }
        }
        if (!kept) {
            log("huddle: inbox not kept (not_written)")
            return false
        }
        if (!server.ack(upTo)) log("huddle: inbox not acknowledged (no_answer)")
        read.filter { it.second is Message.Bye }.mapNotNull { (id, _) -> friends.firstOrNull { it.id == id } }.forEach { gone ->
            server.dropBoxes(listOf(gone.inBox, gone.outBox))
        }
        if (answers) flushNow()
        return true
    }

    /** This state with [read] messages kept: each once, from friends still here; cards and plays kept within their limits. */
    private fun HuddleState.received(read: List<Pair<String, Message>>, now: Long): HuddleState {
        var state = this
        for ((friendId, message) in read) {
            val friend = state.friends.firstOrNull { it.id == friendId } ?: continue
            // A friend's clock may be ahead: nothing is kept as from later than now.
            val at = minOf(message.at, now)
            if (message is Message.Play) {
                // Plays are many: told apart by what they are, so the ids kept are those of cards and goodbyes only.
                val play = FriendPlay(friendId, at, message.song, message.favourite)
                if (play !in state.plays) state = state.copy(plays = listOf(play) + state.plays)
                continue
            }
            if (message.id in friend.seen) continue
            val seen = friend.copy(seen = (friend.seen + message.id).takeLast(SEEN_MAX))
            state = state.copy(friends = state.friends.map { if (it.id == friendId) seen else it })
            state = when (message) {
                is Message.Share -> state.copy(inbox = listOf(Received(message.id, friendId, at, message.card)) + state.inbox)
                is Message.Play -> state
                is Message.Profile -> state.profiled(seen, message, now)
                is Message.Bye -> state.copy(
                    friends = state.friends.filterNot { it.id == friendId },
                    inbox = state.inbox.filterNot { it.friend == friendId },
                    plays = state.plays.filterNot { it.friend == friendId },
                    outbox = state.outbox.filterNot { it.box == friend.outBox },
                    joined = state.joined - friendId,
                )
            }
        }
        return state.copy(
            inbox = state.inbox.sortedByDescending { it.at }.take(INBOX_MAX),
            plays = state.plays.filter { now - it.at <= PLAYS_KEPT_MS }.sortedByDescending { it.at }.take(PLAYS_MAX),
        )
    }

    /**
     * This state with [friend]'s [profile]: their new name, under a stand-in while another friend goes by it, and asked for
     * another (again) when it changed to a taken one; told once it is free again; and whether they ask you for another.
     */
    private fun HuddleState.profiled(friend: Friend, profile: Message.Profile, now: Long): HuddleState {
        val others = friends.filter { it.id != friend.id }
        val renamed = profile.name != friend.name
        val taken = when {
            others.none { sameName(it.shownName, profile.name) } -> null
            // The same name again: as it was, never a newcomer's stand-in for a name they had before you named another so.
            !renamed -> friend.taken
            else -> friend.taken ?: standIn(profile.name, others)
        }
        val updated = friend.copy(name = profile.name, taken = taken, asksNewName = profile.taken)
        val tell = (renamed && taken != null) || (friend.taken != null && taken == null)
        val answer = name?.takeIf { tell }?.let { profile(updated, it, now) }
        return copy(
            friends = friends.map { if (it.id == friend.id) updated else it },
            outbox = if (answer == null) outbox else outbox.filterNot { it.kind == Kind.PROFILE && it.box == friend.outBox } + answer,
        )
    }

    private fun newId(bytes: Int): String = HuddleCrypto.encode(ByteArray(bytes).also(random::nextBytes))

    private fun keptMs(kind: Kind): Long = when (kind) {
        Kind.PLAY -> DAY_MS
        Kind.CARD, Kind.PROFILE -> MONTH_MS
    }

    companion object {
        /** How often friends' plays refresh while KJB is in front, on any page (user 2026-11-22). */
        const val FAST_MS: Long = 30_000
        const val HOUR_MS: Long = 60 * 60_000L
        private const val DAY_MS: Long = 24 * HOUR_MS
        private const val MONTH_MS: Long = 30 * DAY_MS

        /** How long a sent card waits for Undo before it goes: the toast's time. */
        const val UNDO_MS: Long = 5_000

        /** After a sync or a card without an answer, the waits before each new try: 30 s, 2 min, 10 min; then as usual. */
        val RETRY_MS: List<Long> = listOf(30_000L, 2 * 60_000L, 10 * 60_000L)

        /** An invite nobody joined goes after a week, as the server's does. */
        const val INVITE_KEPT_MS: Long = 7 * DAY_MS

        /** Friends' plays are kept 30 days, what Suggestions reads ("this week", "a friend loves this"), and no more. */
        const val PLAYS_KEPT_MS: Long = 30 * DAY_MS

        /** A card fits the link when its JSON is at most this long: about one song with a note. */
        private const val LINK_CARD_MAX_BYTES: Int = 600
        private const val INVITES_MAX: Int = 20

        /** Joins waiting for the server's answer: a few at most, the oldest let go. */
        private const val JOINS_MAX: Int = 20
        private const val INBOX_MAX: Int = 100
        private const val PLAYS_MAX: Int = 5_000
        private const val SEEN_MAX: Int = 200
        private const val MESSAGE_ID_BYTES: Int = 16
        private const val FRIEND_ID_BYTES: Int = 8

        /** An invite's owner id: random, as the server's ids are 32 bytes (43 characters). */
        private const val OWNER_ID_BYTES: Int = 32
    }
}

/** Whether two names are one in a friend list: case aside (both are clean already, [Invites.cleanName]). */
private fun sameName(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

/** The name KJB shows for a newcomer whose [name] one of [friends] goes by: "<name> 2", or the first number free. */
private fun standIn(name: String, friends: List<Friend>): String =
    generateSequence(2) { it + 1 }.map { "$name $it" }.first { candidate -> friends.none { sameName(it.shownName, candidate) } }

/** Friends' plays for Suggestions (handoff/sg1-interfaces.md, section 3): what [storage] keeps, mapped to Suggestions' own form. */
class HuddleFriends(private val storage: HuddleStorage) : FriendsSource {
    override suspend fun since(from: Long): List<SuggestionsPlay> = storage.state.first().plays
        .filter { it.at >= from }
        .sortedBy { it.at }
        .map { play -> SuggestionsPlay(play.friend, play.song.title, play.song.artist, play.song.album, play.song.durationMs.takeIf { it > 0 }, play.at, play.favourite) }
}
