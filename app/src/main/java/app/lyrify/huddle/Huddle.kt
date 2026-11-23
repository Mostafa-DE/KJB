package app.lyrify.huddle

import app.lyrify.player.CoverFound
import app.lyrify.suggestions.FriendsSource
import app.lyrify.suggestions.FriendPlay as SuggestionsPlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A message waiting in a mailbox: the server's [seq] in it (its message id), the sealed [data] (base64url), and [at] when
 * the server got it, on this phone's clock (from the server's own clock, so a phone clock that is off changes nothing).
 */
data class Posted(val seq: Long, val data: String, val at: Long)

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

    /**
     * The messages waiting in [boxes], by box (a box without any is left out), oldest first, each box's only [after] its
     * seq there. With [waitMs] and none waiting, it answers once one lands in a box, or empty after [waitMs].
     */
    suspend fun inbox(boxes: List<String>, after: Map<String, Long> = emptyMap(), waitMs: Long = 0): Map<String, List<Posted>>?

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
 * - Cards go after [UNDO_MS] (the toast's Undo), plays at once from the player (screen off or not), each sealed with
 *   the friend's key for their mailbox; what cannot go waits in [HuddleState.outbox] and goes at the next [sync], a play
 *   within a day only. A card or a play without an answer is tried again after each of [RETRY_MS]'s waits, one set of
 *   tries at a time, started over for one that joined them.
 * - What your player does now ([listening]) goes to each friend at once, never stored, a song's cover with the first that
 *   plays it: one without an answer goes again after each of [NOW_RETRY_MS]'s waits while it is the newest. Friends' nows
 *   are kept in memory only ([HuddleState.live]); each friend's mailbox is read on from where this run of KJB got to
 *   ([readTo]).
 * - A play or a now goes only to friends you share plays with ([Friend.sharePlays], on from the join), never while you
 *   listen privately.
 * - Names are one per friend list: a newcomer whose name another friend goes by shows under a stand-in ([Friend.taken])
 *   and is asked for another through a [Message.Profile], the message that also carries each new name of yours.
 * - [sync] asks the server only when there is something to ask: invites waiting for a join, friends' mailboxes, the outbox,
 *   each whatever the others answered. Each message is read once ([Friend.seen]) and acknowledged so the server deletes it.
 * - [syncWhileInFront]: when KJB comes to the front, then one inbox read always held ([WAIT_MS]), the next asked as soon as
 *   one answers; after a sync without an answer, after each of [RETRY_MS]'s waits, then hourly. Nothing while KJB is not
 *   in front.
 * - The history stays on the phone (user 2026-11-23, the server keeps nothing once delivered): every card both ways, for
 *   good, and friends' plays of [PLAYS_KEPT_MS], in plays.db ([HuddleLog]); huddle.json keeps each friend's latest play.
 *
 * Nothing here logs a name, a title, a note, a key or a mailbox: a step that did not go logs a reason code. One sync step
 * or send at a time; a held inbox read waits outside that, and your nows go one at a time apart from it.
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
    /** Every card both ways and friends' plays of the last year, in plays.db: read through [moved]. */
    private val history: HuddleLog,
    private val log: (String) -> Unit = {},
) {
    /** Friends' newest nows, by friend id: in memory only. */
    private val live = MutableStateFlow<Map<String, Message.Now>>(emptyMap())

    /** What Huddle keeps, with friends' nows from memory ([HuddleState.live]). */
    val state: Flow<HuddleState> = combine(storage.state, live) { kept, nows -> kept.copy(live = nows) }

    private val _cardsWritten = MutableStateFlow(0)

    /** Cards kept in the history or opened, counted: what shows cards reads them again. */
    val cardsWritten: StateFlow<Int> = _cardsWritten.asStateFlow()

    private val _playsWritten = MutableStateFlow(0)

    /** Friends' plays kept in the history, counted: "What <name> played" reads them again. */
    val playsWritten: StateFlow<Int> = _playsWritten.asStateFlow()

    /**
     * The newest cards friends sent ([INBOX_MAX]), read again as cards are kept or opened: the page's Sent to you, its
     * badges and the start screen's card.
     */
    val inbox: Flow<List<Received>> = cardsWritten.map { received(0, INBOX_MAX) }

    /** Whether what huddle.json kept before the history ([moved]) is in it. */
    @Volatile
    private var inHistory = false
    private val moving = Mutex()

    private val one = Mutex()

    /** Your nows told so far ([listening]): one goes only while it is the newest. */
    private val nows = AtomicLong(0)

    /** One now at a time, so a friend's server keeps the newest. */
    private val nowLock = Mutex()

    /** The newest now told ([listening]): a stop takes it back when there is none of yours to share. Under [nowLock]. */
    private var shown: Message.Now? = null

    /**
     * The song your nows play and its cover's read ([listening]): a read of none once a now carried it, so the song's next
     * nows carry none; none after a stop, so the song playing again brings it again, and after a read that found nothing
     * now, so the song's next now that plays reads it again. Under [nowLock].
     */
    private var songCover: Pair<CardSong, Deferred<CoverFound<ByteArray>?>>? = null

    /**
     * Each friend's mailbox as far as this run of KJB read it, by box: its last seq. A read asks only what came after, so
     * a friend's now (on the server until a newer one) answers a held read once; KJB started again reads all that waits.
     */
    private val readTo = ConcurrentHashMap<String, Long>()

    /** The sync [syncWhileInFront] runs, its inbox read held: KJB back in front waits for it, never holding a second. */
    @Volatile
    private var holding: Job? = null

    /** The links of the invite tickets shown in this run ([ticketLink]), by the ticket's id. */
    private val tickets = ConcurrentHashMap<String, String>()

    /**
     * Cards and plays without an answer whose tries ([tryAgain]) are under way: the first one's job tries for them all; 0
     * for none.
     */
    private val waiting = AtomicInteger(0)

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
        val pending = PendingInvite(HuddleCrypto.encode(pair.public), HuddleCrypto.encode(pair.private), HuddleCrypto.encode(token), newId(OWNER_ID_BYTES), clock(), card.takeIf { inLink == null }, inLink)
        if (!server.createInvite(Invites.joinId(token, pair.public), pending.owner)) return Invited.NoAnswer
        if (!storage.change { it.copy(invites = (it.invites + pending).takeLast(INVITES_MAX)) }) {
            server.deleteInvite(pending.owner)
            return Invited.NoAnswer
        }
        return Invited.Link(Invites.link(host, Invite(pair.public, token, name, inLink), random))
    }

    /**
     * The link of the invite ticket [ticket] (the sheet's own id, kept across a turn of the phone): made with [card] at the
     * first Share or Copy ([invite]), the same link after, so one ticket is one seat. Kept in memory only: the link opens a
     * friendship, so it is never in saved state or on disk.
     */
    suspend fun ticketLink(ticket: String, card: Card?): Invited =
        tickets[ticket]?.let(Invited::Link) ?: invite(card).also { if (it is Invited.Link) tickets[ticket] = it.text }

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
        // The same join answered twice (a second tap, the sheet again after a turn of the phone): one friend.
        var already: Friend? = null
        val kept = storage.change { state ->
            already = state.friends.firstOrNull { it.joinId == joinId }
            if (already != null) return@change state.copy(joins = state.joins.filterNot { it.joinId == joinId })
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
        already?.let { return if (kept) Accepted.Already(it) else Accepted.NoAnswer }
        if (kept && card != null) {
            // Written with the friend, so neither is kept without the other: into the history now, or at its next read.
            moving.withLock { inHistory = false }
            moved()
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
            if (!flush()) tryAgain()
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
     * one. Asks nothing when there is nothing to ask. With [waitMs] the inbox read is held until a message lands
     * ([HuddleServer.inbox]).
     */
    suspend fun sync(waitMs: Long = 0): Boolean {
        // `and`, not `&&`: a step without an answer never keeps the next from running.
        val asked = one.withLock { joins() and flushNow() }
        return fetch(waitMs) and asked
    }

    /** You played [song], from [at], a [favourite] of yours or not: each friend you share plays with gets it, unless you listen privately. */
    suspend fun played(song: CardSong, at: Long, favourite: Boolean = false) {
        val now = clock()
        val state = now()
        if (state.privateUntil?.let { now < it } == true) return
        val outs = state.friends.filter { it.sharePlays }.mapNotNull { outgoing(it, Message.Play(newId(MESSAGE_ID_BYTES), at, song, favourite), Kind.PLAY, notBefore = now) }
        // From the player, KJB in front or not (music mostly plays with the screen off): one without an answer is tried
        // again as a card is.
        if (outs.isNotEmpty() && storage.change { it.copy(outbox = it.outbox + outs) } && !flush()) scope.launch { tryAgain() }
    }

    /**
     * Your player is in [state] at [positionMs], [at] (a play, a pause, a resume, a skip, a seek, a stop, or a minute
     * playing): after [NOW_GAP_MS], when nothing newer came (a burst of skips goes as its last), each friend you share
     * plays with gets it at once, side by side. [songOf] gives what plays and whether it is a favourite, read only then;
     * null for none to share (a book, a podcast): then, as while you listen privately, a friend who saw your last song
     * sees it stop, and nothing more. Never stored: one without an answer goes again to the friends it missed after each
     * of [NOW_RETRY_MS]'s waits while it is the newest, then the next change or heartbeat replaces it (and a friend lets
     * a "playing" go 150 s after the last). A song that starts playing brings its cover ([Message.Now.cover]), read once
     * with [coverOf] ([CoverFound.NoArt] for none; null when it could not be read now, as for a read that throws, and read
     * again with the song's next now that plays); its now waits [COVER_WAIT_MS] for it at most, and one read later goes
     * with the song's next now that plays. Called in the player's order.
     */
    fun listening(state: NowState, positionMs: Long, at: Long, coverOf: suspend () -> CoverFound<ByteArray>? = { CoverFound.NoArt }, songOf: suspend () -> Pair<CardSong, Boolean>?) {
        val told = nows.incrementAndGet()
        scope.launch {
            delay(NOW_GAP_MS)
            if (nows.get() != told) return@launch
            val now = clock()
            // Read outside the lock: a song read late (the library still being read) never holds up a newer now.
            val yours = if (now().privateUntil?.let { now < it } == true) null else songOf()
            // The song's cover, read once whatever nows come meanwhile, and waited for outside the lock too. A now told since
            // reads its own song's: this one starts none.
            val read = yours?.takeIf { state == NowState.PLAYING }?.let { (song) ->
                nowLock.withLock {
                    if (nows.get() != told) return@launch
                    songCover?.takeIf { it.first == song } ?: (
                        song to scope.async {
                            // Whatever a read throws fails that read only, never the song's nows: as one not read now.
                            try {
                                coverOf()
                            } catch (e: Throwable) {
                                log("huddle: cover not read (${e.javaClass.simpleName})")
                                null
                            }
                        }
                        ).also { songCover = it }
                }.second
            }
            val found = read?.let { withTimeoutOrNull(COVER_WAIT_MS) { it.await() } }
            val message = nowLock.withLock {
                if (nows.get() != told) return@launch
                val next = yours?.let { (song, favourite) -> Message.Now(newId(MESSAGE_ID_BYTES), at, song, state, positionMs, favourite, (found as? CoverFound.Own)?.cover?.let(::NowCover)) }
                    ?: shown?.takeIf { it.state != NowState.STOPPED }?.copy(id = newId(MESSAGE_ID_BYTES), at = at, state = NowState.STOPPED, cover = null)
                    ?: return@launch
                shown = next
                when {
                    next.state == NowState.STOPPED -> songCover = null
                    // Carried: the song's next nows carry none.
                    next.cover != null -> songCover = next.song to CompletableDeferred<CoverFound<ByteArray>?>(CoverFound.NoArt)
                    // Not read now (not a song without one): the song's next now that plays reads it again.
                    read?.isCompleted == true && read.await() == null -> songCover = null
                }
                next
            }
            // The friends it reached, by id: a try again goes to the others.
            val reached = HashSet<String>()
            for (wait in listOf(0L) + NOW_RETRY_MS) {
                delay(wait)
                val missed = nowLock.withLock {
                    // A newer one replaces it.
                    if (nows.get() != told) return@launch
                    val unreached = now().friends.filter { it.sharePlays && it.id !in reached }
                    val answers = coroutineScope { unreached.map { friend -> async { outgoing(friend, message, Kind.NOW, notBefore = clock())?.let { server.send(it.box, Kind.NOW, it.data) } } }.awaitAll() }
                    unreached.zip(answers).forEach { (friend, answer) -> if (answer != PostAnswer.NO_ANSWER) reached += friend.id }
                    answers.count { it == PostAnswer.NO_ANSWER }
                }
                if (missed == 0) return@launch
                log("huddle: now not sent to $missed (no_answer)")
            }
        }
    }

    /**
     * After a card or a play without an answer: the outbox is posted again after each of [RETRY_MS]'s waits, one set of
     * tries at a time. One without an answer while they run joins them (a flush posts the whole outbox), and they start
     * over for it once they are through.
     */
    private suspend fun tryAgain() {
        if (waiting.getAndIncrement() > 0) return
        try {
            do {
                val joined = waiting.get()
                for (wait in RETRY_MS) {
                    // Sent by a try or a sync, taken back, or let go (a play past its day, or not to share): nothing left to try.
                    val left = now().outbox
                    if (left.isEmpty()) break
                    log("huddle: ${if (left.any { it.kind == Kind.CARD }) "card" else "play"} not sent, next try in ${wait / 1_000} s")
                    delay(wait)
                    if (flush()) break
                }
            } while (!waiting.compareAndSet(joined, 0))
        } catch (e: Throwable) {
            // Stopped by an error, or with the process: the next one without an answer starts the tries again.
            waiting.set(0)
            throw e
        }
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
        // Their cards and plays go first, after a sync under way (which may still keep what they sent last): a removal whose
        // history cannot be let go does not happen, so it can be asked again.
        val kept = one.withLock {
            forgetHistory(friendId) && storage.change { state ->
                state.copy(
                    friends = state.friends.filterNot { it.id == friendId },
                    inbox = state.inbox.filterNot { it.friend == friendId },
                    plays = state.plays.filterNot { it.friend == friendId },
                    outbox = state.outbox.filterNot { it.box == friend.outBox } + listOfNotNull(bye),
                    joined = state.joined - friendId,
                )
            }
        }
        if (!kept) return false
        live.update { it - friendId }
        flush()
        server.dropBoxes(listOf(friend.inBox))
        return true
    }

    /** Cancels your invite with [token] (base64url): it goes from the phone and the server, so the link no longer works. */
    suspend fun cancel(token: String): Boolean = one.withLock {
        val invite = now().invites.firstOrNull { it.token == token } ?: return@withLock false
        server.deleteInvite(invite.owner) && forget(listOf(invite))
    }

    /** The backup's Huddle section now, the history with it ([HuddleBackup.seal]); null without a backup passphrase. */
    suspend fun backupSection(): String? = HuddleBackup.seal(now(), moved(), random)

    /**
     * Restores a backup's Huddle [section] opened with [passphrase] into what this phone has: its history first (each card
     * and play once, so a restore made twice adds nothing), then the rest ([restored]), its key kept for the next backups.
     * How many friends it holds, or null when the passphrase does not open it (or it could not be written). Slow, as
     * [HuddleBackup.open]: off the main thread.
     */
    suspend fun restore(section: String, passphrase: CharArray): Int? {
        val backup = HuddleBackup.open(section, passphrase) ?: return null
        val key = backup.state.backupKey ?: return null
        if (!keep(backup.cards, backup.plays)) return null
        return backup.state.friends.size.takeIf { storage.change { it.restored(backup.state, key) } }
    }

    /**
     * Listen privately until [until] (wall-clock millis; [HuddleState.FOREVER] until turned back on), or no longer (null).
     * Turned on, a friend who saw your last song sees it stop ([listening]).
     */
    suspend fun listenPrivately(until: Long?): Boolean = withContext(NonCancellable) {
        storage.change { it.copy(privateUntil = until) }.also { kept -> if (kept && until != null) listening(NowState.STOPPED, 0, clock()) { null } }
    }

    /** The friend [friendId] who joined your invite was greeted ("<name> joined your Huddle!"): never again. */
    suspend fun celebrated(friendId: String): Boolean = withContext(NonCancellable) { storage.change { it.copy(joined = it.joined - friendId) } }

    /** The card [receivedId] was played or looked at; [dismissed]: put away, it also leaves the start screen. */
    suspend fun opened(receivedId: String, dismissed: Boolean = false): Boolean = withContext(NonCancellable) {
        moved().opened(receivedId, dismissed).also { if (it) _cardsWritten.update { count -> count + 1 } }
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
     * KJB is in front, until the job returned is cancelled: syncs now, its inbox read held up to [WAIT_MS], and again as
     * soon as one answers, so a friend's play or pause shows in a second (in Library, on the player and the Now bar too);
     * without friends every [FAST_MS]. A sync without an answer is tried again after each of [RETRY_MS]'s waits from its
     * end, then each hour until one answers. Each sync is a job of its own in [scope], so one under way runs on when KJB
     * goes, and KJB back in front waits for its answer before asking again ([holding]).
     */
    fun syncWhileInFront(): Job = scope.launch {
        holding?.join()
        // Syncs in a row without an answer (or that broke off): the next waits the step of RETRY_MS they reached.
        var failed = 0
        while (true) {
            var answered = false
            scope.launch { answered = sync(WAIT_MS) }.also { holding = it }.join()
            failed = if (answered) 0 else failed + 1
            val wait = when {
                failed > 0 -> RETRY_MS.getOrNull(failed - 1) ?: HOUR_MS
                now().friends.isEmpty() -> FAST_MS
                else -> 0
            }
            if (failed > 0) log("huddle: sync failed $failed in a row, next try in ${wait / 1_000} s")
            delay(wait)
        }
    }

    /** The cards between you and the friend [friendId], both ways, the newest first: [limit] from the [from]th ("You and <name>"). */
    suspend fun thread(friendId: String, from: Int, limit: Int): List<KeptCard> = moved().thread(friendId, from, limit).orEmpty()

    /** Every card friends sent you, the newest first: [limit] from the [from]th (Sent to you's View all). */
    suspend fun received(from: Int, limit: Int): List<Received> = moved().received(from, limit).orEmpty()

    /** The card [id], either way: the card opened from a list; null for none. */
    suspend fun card(id: String): KeptCard? = moved().card(id)

    /** The friend [friendId]'s plays of the last year, the newest first: [limit] from the [from]th ("What <name> played"). */
    suspend fun friendPlays(friendId: String, from: Int, limit: Int): List<FriendPlay> = moved().plays(friendId, from, limit).orEmpty()

    /** Every friend's plays from [from] on, the oldest first: Suggestions' friends source ([HuddleFriends]). */
    suspend fun playsSince(from: Long): List<FriendPlay> = moved().playsSince(from).orEmpty()

    private suspend fun now(): HuddleState = storage.state.first()

    /**
     * The history, once what huddle.json holds outside it is in it: what KJB kept there before the history (KJB v4 test
     * builds: the newest 100 cards, every play of 30 days), and the card an accepted invite brought ([accept]). Written to
     * plays.db first, then the cards leave huddle.json and its plays keep each friend's latest, so a write that fails loses
     * nothing and is tried again at the next read, and a second run adds nothing (each card and play is kept once).
     */
    private suspend fun moved(): HuddleLog {
        if (!inHistory) {
            moving.withLock {
                if (inHistory) return@withLock
                val old = now()
                if (!history.keep(old.inbox, old.plays, clock() - PLAYS_KEPT_MS)) return@withLock
                inHistory = (old.inbox.isEmpty() && latestPlays(old.plays).size == old.plays.size) ||
                    storage.change { state -> state.copy(inbox = state.inbox - old.inbox.toSet(), plays = latestPlays(state.plays)) }
                if (old.inbox.isNotEmpty()) _cardsWritten.update { it + 1 }
            }
        }
        return history
    }

    /** [cards] and [plays] kept in the history ([HuddleLog.keep]: plays past [PLAYS_KEPT_MS] let go), told to what shows them. */
    private suspend fun keep(cards: List<KeptCard> = emptyList(), plays: List<FriendPlay> = emptyList()): Boolean =
        moved().keep(cards, plays, clock() - PLAYS_KEPT_MS).also { kept ->
            if (kept && cards.isNotEmpty()) _cardsWritten.update { it + 1 }
            if (kept && plays.isNotEmpty()) _playsWritten.update { it + 1 }
        }

    /**
     * The friend [friendId]'s cards and plays let go from the history (you removed them, or they removed you); false when
     * they could not be.
     */
    private suspend fun forgetHistory(friendId: String): Boolean = moved().forget(friendId).also { forgot ->
        if (forgot) {
            _cardsWritten.update { it + 1 }
            _playsWritten.update { it + 1 }
        }
    }

    private suspend fun changeFriend(friendId: String, change: (Friend) -> Friend): Boolean {
        if (now().friends.none { it.id == friendId }) return false
        return storage.change { state -> state.copy(friends = state.friends.map { if (it.id == friendId) change(it) else it }) }
    }

    /** A new friend [name], with the friendship's mailboxes and keys (base64url) and the [joinId] that made it. */
    private fun friendOf(name: String, outBox: String, inBox: String, outKey: String, inKey: String, joinId: String): Friend =
        Friend(id = newId(FRIEND_ID_BYTES), name = name, outBox = outBox, inBox = inBox, outKey = outKey, inKey = inKey, joinId = joinId, addedAt = clock())

    /**
     * [friend] as a newcomer to [friends]: under a stand-in name ([Friend.taken]) when one of them goes by theirs, else under
     * their own, also when a write runs this again ([HuddleStorage.change]) after that friend went.
     */
    private fun newcomer(friend: Friend, friends: List<Friend>): Friend =
        friend.copy(taken = if (friends.any { sameName(it.shownName, friend.name) }) standIn(friend.name, friends) else null)

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
        return Outgoing(message.id, friend.outBox, HuddleCrypto.encode(sealed), kind, message.at, notBefore, (message as? Message.Share)?.card)
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
                        // The card the link carried reached them with it: kept first, so the invite goes only once it is (or
                        // at the next sync); a friend who could not be written takes it back.
                        val linked = invite.linked?.let { Sent(newId(MESSAGE_ID_BYTES), friend.id, invite.madeAt, it) }
                        if (linked != null && !keep(listOf(linked))) continue
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
                        if (kept) server.deleteInvite(invite.owner) else if (linked != null) forgetHistory(friend.id)
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
        val posted = HashSet<String>()
        for (out in outbox.filter { it.id !in gone && it.notBefore <= now }) {
            when (server.send(out.box, out.kind, out.data)) {
                PostAnswer.POSTED -> {
                    gone += out.id
                    posted += out.id
                }
                // A play the friend's full mailbox refused is not tried again; a card is, until the server would have dropped it.
                PostAnswer.REFUSED -> if (out.kind == Kind.PLAY) gone += out.id
                PostAnswer.NO_ANSWER -> {
                    log("huddle: outbox stopped (no_answer)")
                    answered = false
                    break
                }
            }
        }
        // A card that went is kept in the history as sent; one let go (no answer, or refused, as long as the server would
        // have kept it) as not delivered. One the history could not keep stays, to be kept at the next go (sent again if it
        // went: the friend keeps a card once, by its id).
        val sent = outbox.filter { it.id in gone }.mapNotNull { out ->
            val card = out.card ?: return@mapNotNull null
            state.friends.firstOrNull { it.outBox == out.box }?.let { Sent(out.id, it.id, out.at, card, delivered = out.id in posted) }
        }
        val leaving = if (sent.isEmpty() || keep(sent)) gone else gone - sent.mapTo(HashSet()) { it.id }
        if (leaving.isNotEmpty()) storage.change { state -> state.copy(outbox = state.outbox.filterNot { it.id in leaving }) }
        return answered
    }

    /**
     * Friends' mailboxes, the read held up to [waitMs]: each message opened with its friend's key, kept once, then
     * acknowledged; a goodbye removes the friend, a now goes to [live] only. One that does not open is read again at
     * [NOT_OPENED_RETRIES] syncs, unacknowledged with what came after it, then acknowledged too. A play goes from the
     * server once read and a now stays until a newer one: a box of only those asks no acknowledgement. Each box is read
     * after where this run got to ([readTo]), once kept. False when the server did not answer, the acknowledgement
     * included.
     */
    private suspend fun fetch(waitMs: Long): Boolean {
        val friends = now().friends
        if (friends.isEmpty()) return true
        // Held outside the lock: a card, a play or a now of yours goes meanwhile, and Undo answers at once.
        val posted = server.inbox(friends.map { it.inBox }, readTo, waitMs) ?: run {
            log("huddle: inbox not read (no_answer)")
            return false
        }
        return one.withLock {
            val now = clock()
            // Nothing new and no count of messages that did not open to start over: only plays past their time go.
            if (posted.isEmpty() && friends.none { it.notOpenedReads > 0 }) {
                keep()
                return now().plays.none { now - it.at > PLAYS_KEPT_MS } || storage.change { it.received(emptyList(), now) }
            }
            // The friends still here once the read is back: what it brings from one removed meanwhile is dropped unread.
            val stillHere = now().friends.filter { friend -> friends.any { it.id == friend.id } }
            val upTo = HashMap<String, Long>()
            // By box: the seq this read got to, the next read's cursor once kept.
            val through = HashMap<String, Long>()
            val read = ArrayList<Pair<String, Message>>()
            // By friend: their Friend.notOpenedReads after this read.
            val notOpened = HashMap<String, Int>()
            var kept = 0
            var dropped = 0
            for (friend in stillHere) {
                // A mailbox with nothing in it is read too: it found none that does not open.
                val box = posted[friend.inBox]?.sortedBy { it.seq }.orEmpty()
                // A key that does not read opens nothing: its box is let go as any that does not open, never read again and again.
                val key = HuddleCrypto.decode(friend.inKey)
                // A box that does not open (another key, sealed for the other way, changed, a newer KJB's): its seq.
                val closed = ArrayList<Long>()
                // A play goes from the server once read, a now when a newer one comes: only a box with anything else is acknowledged.
                var acks = false
                for (one in box) {
                    val message = key?.let { HuddleCrypto.decode(one.data)?.let { data -> HuddleCrypto.open(key, data, friend.inBox.encodeToByteArray()) } }?.let(Messages::decode)
                    // A now as of when the server got it: as old as the server says, whatever either phone's clock says.
                    if (message == null) closed += one.seq else read += friend.id to ((message as? Message.Now)?.copy(at = one.at) ?: message)
                    if (message !is Message.Play && message !is Message.Now) acks = true
                }
                val reads = if (closed.isEmpty()) 0 else friend.notOpenedReads + 1
                val again = reads in 1..NOT_OPENED_RETRIES
                // Let go, it stays past NOT_OPENED_RETRIES until the server takes the acknowledgement, so a lost one goes again.
                notOpened[friend.id] = reads
                if (again) kept += closed.size else dropped += closed.size
                // Read again: acknowledged and read on only up to the first that did not open, so it and what came after it wait.
                val last = (if (again) box.lastOrNull { it.seq < closed.first() } else box.lastOrNull())?.seq ?: continue
                through[friend.inBox] = last
                if (acks) upTo[friend.inBox] = last
            }
            if (kept > 0) log("huddle: inbox kept $kept that did not open, to read again (not_opened)")
            if (dropped > 0) log("huddle: inbox dropped $dropped that did not open (not_opened)")
            // The cards and plays go to the history first, each once: a huddle.json write that fails after it reads them again.
            val cards = read.mapNotNull { (friendId, message) -> (message as? Message.Share)?.let { Received(it.id, friendId, minOf(it.at, now), it.card) } }
            val plays = read.mapNotNull { (friendId, message) -> (message as? Message.Play)?.let { FriendPlay(friendId, minOf(it.at, now), it.song, it.favourite) } }
            if (!keep(cards, plays)) {
                log("huddle: inbox not kept (not_written)")
                return false
            }
            // A goodbye's friend: their cards and plays go before huddle.json lets them go, so one that cannot be let go reads
            // the goodbye again at the next sync.
            val byes = read.filter { it.second is Message.Bye }.mapTo(HashSet()) { it.first }
            if (!byes.all { forgetHistory(it) }) {
                log("huddle: inbox not kept (not_written)")
                return false
            }
            // Whether what came asks for an answer (a name taken in your list, or free again): it goes in this sync.
            var answers = false
            val written = storage.change { state ->
                state.copy(friends = state.friends.map { it.copy(notOpenedReads = notOpened[it.id] ?: it.notOpenedReads) })
                    .received(read, now)
                    .also { next -> answers = next.outbox.any { out -> state.outbox.none { it.id == out.id } } }
            }
            if (!written) {
                log("huddle: inbox not kept (not_written)")
                return false
            }
            // A friend's now shows live, in memory only; one who left (a goodbye in this read, or removed meanwhile) goes.
            // Only a song's first now brings its cover: the next ones keep the one held for the same song.
            val here = now().friends.mapTo(HashSet()) { it.id }
            live.update { held ->
                (held + read.mapNotNull { (id, message) -> (message as? Message.Now)?.let { id to it.copy(cover = it.cover ?: held[id]?.takeIf { old -> old.song == it.song }?.cover) } })
                    .filterKeys { it in here }
            }
            val acknowledged = upTo.isEmpty() || server.ack(upTo)
            if (!acknowledged) log("huddle: inbox not acknowledged (no_answer)")
            // Read on past what was kept, and acknowledged where it had to be: one whose acknowledgement got no answer is read again.
            through.filterKeys { acknowledged || it !in upTo }.forEach { (box, seq) -> readTo.merge(box, seq) { old, new -> maxOf(old, new) } }
            // What was let go is gone: the next one that does not open gets its reads again.
            if (acknowledged && dropped > 0) storage.change { state -> state.copy(friends = state.friends.map { if (it.notOpenedReads > NOT_OPENED_RETRIES) it.copy(notOpenedReads = 0) else it }) }
            byes.mapNotNull { id -> stillHere.firstOrNull { it.id == id } }.forEach { gone -> server.dropBoxes(listOf(gone.inBox, gone.outBox)) }
            if (answers) flushNow()
            return acknowledged
        }
    }

    /**
     * This state with [read] messages kept: each once, from friends still here; of plays, each friend's latest within
     * [PLAYS_KEPT_MS] (the cards and every play are in the history).
     */
    private fun HuddleState.received(read: List<Pair<String, Message>>, now: Long): HuddleState {
        var state = this
        for ((friendId, message) in read) {
            val friend = state.friends.firstOrNull { it.id == friendId } ?: continue
            // A friend's clock may be ahead: nothing is kept as from later than now.
            val at = minOf(message.at, now)
            // A now is live only ([live]), never kept.
            if (message is Message.Now) continue
            if (message is Message.Play) {
                // Each friend's latest play only (every play is in the history, [fetch]); the one just read first, so it wins
                // a tie (two cut to now from a clock ahead: read in order, the later is the newer). Plays are many: the ids
                // kept are those of cards and goodbyes only.
                state = state.copy(plays = latestPlays(listOf(FriendPlay(friendId, at, message.song, message.favourite)) + state.plays))
                continue
            }
            if (message.id in friend.seen) continue
            val seen = friend.copy(seen = (friend.seen + message.id).takeLast(SEEN_MAX))
            state = state.copy(friends = state.friends.map { if (it.id == friendId) seen else it })
            state = when (message) {
                // In the history already ([fetch]).
                is Message.Share -> state
                is Message.Play, is Message.Now -> state
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
        return state.copy(plays = state.plays.filter { now - it.at <= PLAYS_KEPT_MS })
    }

    /**
     * This state with [friend]'s [profile]: their new name, under a stand-in while another friend goes by it, and asked for
     * another (again) when it changed to a taken one; told once it is free again; and whether they ask you for another.
     */
    private fun HuddleState.profiled(friend: Friend, profile: Message.Profile, now: Long): HuddleState {
        val others = friends.filter { it.id != friend.id }
        val taken = when {
            others.none { sameName(it.shownName, profile.name) } -> null
            // The same name again (case aside): as it was, never a newcomer's stand-in for a name they had before you named
            // another so.
            sameName(profile.name, friend.name) -> friend.taken
            // Another friend's name: a stand-in of that one, not of the name they had.
            else -> standIn(profile.name, others)
        }
        val updated = friend.copy(name = profile.name, taken = taken, asksNewName = profile.taken)
        val answer = name?.takeIf { (profile.name != friend.name && taken != null) || (friend.taken != null && taken == null) }?.let { profile(updated, it, now) }
        return copy(
            friends = friends.map { if (it.id == friend.id) updated else it },
            outbox = if (answer == null) outbox else outbox.filterNot { it.kind == Kind.PROFILE && it.box == friend.outBox } + answer,
        )
    }

    private fun newId(bytes: Int): String = HuddleCrypto.encode(ByteArray(bytes).also(random::nextBytes))

    private fun keptMs(kind: Kind): Long = when (kind) {
        // A now never waits in the outbox ([listening]).
        Kind.PLAY, Kind.NOW -> DAY_MS
        Kind.CARD, Kind.PROFILE -> MONTH_MS
    }

    companion object {
        /** How often KJB asks while in front without a read to hold: no friends yet (user 2026-11-22). */
        const val FAST_MS: Long = 30_000

        /** How long an inbox read is held while KJB is in front, at most (huddle-server holds up to 25 s). */
        const val WAIT_MS: Long = 25_000

        /** Your nows within this of each other go as the last of them: a burst of skips, a pause and a play. */
        const val NOW_GAP_MS: Long = 500

        /** How long a now that plays a new song waits for its cover; one read later goes with the song's next now that plays. */
        const val COVER_WAIT_MS: Long = 300

        /**
         * After a now without an answer, the waits before each new try to the friends it missed: 2 s, 5 s, 15 s, 30 s, 60 s,
         * the last under 2 minutes after it, so a pause still lands before a friend lets the "playing" before it go (150 s).
         */
        private val NOW_RETRY_MS: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
        const val HOUR_MS: Long = 60 * 60_000L
        private const val DAY_MS: Long = 24 * HOUR_MS
        private const val MONTH_MS: Long = 30 * DAY_MS

        /** How long a sent card waits for Undo before it goes: the toast's time. */
        const val UNDO_MS: Long = 5_000

        /** After a sync or a card without an answer, the waits before each new try: 30 s, 2 min, 10 min; then as usual. */
        val RETRY_MS: List<Long> = listOf(30_000L, 2 * 60_000L, 10 * 60_000L)

        /** A message that does not open is read again at this many syncs, unacknowledged, before it goes. */
        const val NOT_OPENED_RETRIES: Int = 3

        /** An invite nobody joined goes after a week, as the server's does. */
        const val INVITE_KEPT_MS: Long = 7 * DAY_MS

        /**
         * Friends' plays are kept a year in the history (user 2026-11-23, "What <name> played"); Suggestions read the last
         * 30 days of them ("this week", "a friend loves this").
         */
        const val PLAYS_KEPT_MS: Long = 365 * DAY_MS

        /** A card fits the link when its JSON is at most this long: about one song with a note. */
        private const val LINK_CARD_MAX_BYTES: Int = 600
        private const val INVITES_MAX: Int = 20

        /** Joins waiting for the server's answer: a few at most, the oldest let go. */
        private const val JOINS_MAX: Int = 20

        /** The newest cards friends sent that [inbox] reads: the page's Sent to you, its badges and the start screen's card. */
        private const val INBOX_MAX: Int = 100
        private const val SEEN_MAX: Int = 200
        private const val MESSAGE_ID_BYTES: Int = 16
        private const val FRIEND_ID_BYTES: Int = 8

        /** An invite's owner id: random, as the server's ids are 32 bytes (43 characters). */
        private const val OWNER_ID_BYTES: Int = 32
    }
}

/** Whether two names are one in a friend list: case aside (both are clean already, [Invites.cleanName]). */
private fun sameName(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

/**
 * The name KJB shows for a newcomer whose [name] one of [friends] goes by: "<name> 2", or the first number free, also of
 * a stand-in under the name you gave a friend (shown again once you take that back).
 */
private fun standIn(name: String, friends: List<Friend>): String =
    generateSequence(2) { it + 1 }.map { "$name $it" }.first { candidate ->
        friends.none { friend -> listOfNotNull(friend.shownName, friend.taken).any { sameName(it, candidate) } }
    }

/**
 * Friends' plays for Suggestions (handoff/sg1-interfaces.md, section 3): what [huddle]'s history keeps, mapped to
 * Suggestions' own form.
 */
class HuddleFriends(private val huddle: Huddle) : FriendsSource {
    override suspend fun since(from: Long): List<SuggestionsPlay> = huddle.playsSince(from)
        .map { play -> SuggestionsPlay(play.friend, play.song.title, play.song.artist, play.song.album, play.song.durationMs.takeIf { it > 0 }, play.at, play.favourite) }
}
