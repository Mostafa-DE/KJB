package app.lyrify.huddle

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.sqlite.transaction
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import app.lyrify.backup.SectionJson.int
import app.lyrify.backup.SectionJson.jsonObject
import app.lyrify.backup.SectionJson.text
import app.lyrify.data.writeWithRetries
import app.lyrify.stats.PlayRow
import app.lyrify.stats.PlaysSchema
import app.lyrify.stats.SqlPlayLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A friend: the [name] they gave (and changed since, through their profile), the one you gave them ([rename]), and the
 * friendship's mailboxes and keys (base64url). [id] is KJB's own, random. [joinId]: the join mailbox of the invite that
 * made the friendship.
 */
@Serializable
data class Friend(
    val id: String,
    val name: String,
    val outBox: String,
    val inBox: String,
    val outKey: String,
    val inKey: String,
    val joinId: String,
    val addedAt: Long,
    val rename: String? = null,
    /** You share each play with them (Friends sharing): on from the join, until you stop sharing with them. */
    val sharePlays: Boolean = true,
    val lastSentAt: Long? = null,
    /** The ids of their latest messages: a copy the server sends again is dropped. */
    val seen: List<String> = emptyList(),
    /**
     * While their [name] is the name of another of your friends (names are one per friend list): the name KJB shows
     * instead ("Juniper 2"), as they were asked to pick another; null once they did.
     */
    val taken: String? = null,
    /** They told you your name is the name of another of their friends: KJB asks you to pick another. */
    val asksNewName: Boolean = false,
    /**
     * Reads of their mailbox in a row that found a message that does not open: it waits on the server, unacknowledged, for
     * [Huddle.NOT_OPENED_RETRIES] more reads, then is let go. 0 again once the server took that acknowledgement, or after
     * a read that found none.
     */
    val notOpenedReads: Int = 0,
) {
    val shownName: String get() = rename ?: taken ?: name
}

/**
 * A friend who left (their goodbye, or you removed them) [leftAt], under the [name] you saw them by then
 * ([Friend.shownName]). Their keys and mailboxes went; your history with them stays on the phone ([HuddleLog]) until you
 * delete it.
 */
@Serializable
data class PastFriend(val id: String, val name: String, val leftAt: Long)

/**
 * An invite no friend joined yet: its key pair and token (base64url), its [owner] id on the server (only this phone
 * knows it: it reads and deletes the invite), when it was made, and a [card] to send once joined; [linked]: the card the
 * link itself carries, kept in the history as sent once a friend joins.
 */
@Serializable
data class PendingInvite(val publicKey: String, val privateKey: String, val token: String, val owner: String, val madeAt: Long, val card: Card? = null, val linked: Card? = null)

/**
 * An invite this phone is joining: its join mailbox [joinId], the exact accept [data] posted there (sent again as it is
 * when the answer was lost, so the server knows it), the friendship's keys from this side, and the inviter's [name] and
 * first [card]. Kept before the accept goes, gone once it is answered.
 */
@Serializable
data class PendingJoin(
    val joinId: String,
    val data: String,
    val outBox: String,
    val inBox: String,
    val outKey: String,
    val inKey: String,
    val name: String,
    val card: Card? = null,
)

/**
 * A card between you and a friend, kept on the phone for good (Huddle's history in plays.db, [HuddleLog]): one they sent
 * you ([Received]) or one you sent them ([Sent]).
 */
sealed interface KeptCard {
    /** Its message's id: a card is kept once. */
    val id: String

    /** The friend (their id) who sent it, or to whom it went. */
    val friend: String
    val at: Long
    val card: Card
}

/**
 * A card [friend] (their id) sent, [at] when, under its message [id]; [opened] once you played it, looked at it or put it
 * away, [dismissed] once you put it away from the start screen.
 */
@Serializable
data class Received(override val id: String, override val friend: String, override val at: Long, override val card: Card, val opened: Boolean = false, val dismissed: Boolean = false) : KeptCard

/**
 * A card you sent [friend] (their id), [at] when, under its message [id], kept once the server took it; [delivered] false
 * when it never got there (no answer, or refused, until the server would have dropped it).
 */
@Serializable
data class Sent(override val id: String, override val friend: String, override val at: Long, override val card: Card, val delivered: Boolean = true) : KeptCard

/** A play [friend] (their id) shared, started [at]; [favourite] when the song was one of theirs. */
@Serializable
data class FriendPlay(val friend: String, val at: Long, val song: CardSong, val favourite: Boolean = false)

/**
 * A message's kind as the server knows it (huddle-server/API.md): it keeps a card and a profile 30 days until read (a new
 * profile in its place), a play a day.
 */
@Serializable
enum class Kind { CARD, PLAY, PROFILE }

/**
 * A sealed message ([data], base64url) of [kind] waiting to go to [box], made [at], going no earlier than [notBefore] (Undo);
 * [id] is its message's. [card]: a card's own, kept in the history as [Sent] once it went (or was let go).
 */
@Serializable
data class Outgoing(val id: String, val box: String, val data: String, val kind: Kind, val at: Long, val notBefore: Long = at, val card: Card? = null)

/** The backup passphrase's key, never the passphrase: its [salt] and [rounds] go into each backup beside the sealed friends. */
@Serializable
data class BackupKey(val salt: String, val rounds: Int, val key: String)

/** Everything Huddle keeps on the phone (`huddle.json`). */
@Serializable
data class HuddleState(
    /** Your name, as friends see it: typed once. */
    val name: String? = null,
    val friends: List<Friend> = emptyList(),
    val invites: List<PendingInvite> = emptyList(),
    /**
     * Cards friends sent that the history ([HuddleLog]) does not hold yet: what KJB kept here before it, and the card an
     * accepted invite brought, written with the friend. Moved there at its next read (Huddle.moved).
     */
    val inbox: List<Received> = emptyList(),
    /**
     * Each friend's latest play: now playing, and what they played last. Every play of the last year is in the history
     * ([HuddleLog]); KJB kept them all here before, and moves them there once.
     */
    val plays: List<FriendPlay> = emptyList(),
    val outbox: List<Outgoing> = emptyList(),
    /** Invites this phone is joining, until the server answered ([PendingJoin]). */
    val joins: List<PendingJoin> = emptyList(),
    /** Listen privately: no play is shared before this time (wall-clock millis); [FOREVER] until turned back on. */
    val privateUntil: Long? = null,
    val backupKey: BackupKey? = null,
    /** Friends who joined your invites, by id, not yet greeted ("<name> joined your Huddle!", once each). */
    val joined: List<String> = emptyList(),
    /** Friends who left, the oldest first: the Huddle page's Past friends, each with their history kept ([PastFriend]). */
    val past: List<PastFriend> = emptyList(),
) {
    companion object {
        /** Listen privately until turned back on. */
        const val FOREVER: Long = Long.MAX_VALUE
    }
}

/** Each friend's latest of [plays], as [HuddleState.plays] keeps them: the first of two at the same time. */
internal fun latestPlays(plays: List<FriendPlay>): List<FriendPlay> = plays.groupBy { it.friend }.map { (_, its) -> its.maxBy { it.at } }

/**
 * The stored form of [HuddleState]: JSON with a schema version; unknown fields are ignored, missing ones take their
 * defaults. A state from before [SHARING_ON] (user 2026-11-22) has sharing turned on for every friend, once.
 */
object HuddleJson {
    const val SCHEMA_VERSION: Int = 2

    /** The version from which Share what I play is on by default: friends of an older state get it on as they are read. */
    private const val SHARING_ON: Int = 2

    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"

    /** Huddle's JSON: unknown fields ignored, every field written. The backup's history lines are written with it too. */
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: HuddleState): String {
        val body = json.encodeToJsonElement(HuddleState.serializer(), state) as JsonObject
        return JsonObject(mapOf(SCHEMA_VERSION_KEY to JsonPrimitive(SCHEMA_VERSION)) + body).toString()
    }

    /** Throws a SerializationException for text that is not Huddle's state. */
    fun decode(text: String): HuddleState {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not Huddle's state: no JSON object")
        val state = json.decodeFromJsonElement(HuddleState.serializer(), root)
        return if ((root.int(SCHEMA_VERSION_KEY) ?: 1) < SHARING_ON) state.copy(friends = state.friends.map { it.copy(sharePlays = true) }) else state
    }

    /** Written by a newer KJB, whose state this one must leave as it is. */
    fun fromNewer(text: String): Boolean = (jsonObject(text)?.int(SCHEMA_VERSION_KEY) ?: 0) > SCHEMA_VERSION
}

/**
 * The backup's Huddle section (plan Q3.3): everything Huddle keeps but what waits to go, sealed with the key of a
 * passphrase the user chose ([BackupKey], PBKDF2), beside that key's salt and rounds. The passphrase is never stored;
 * its key stays on the phone, so automatic backups need no typing. Restore on a new phone with the passphrase and the
 * friends come back; without it the rest of the backup restores and Huddle starts over.
 *
 * Format 2 holds the history too (user 2026-11-23): sealed, gzipped lines, the state first, then every card and then
 * the plays, the newest first, packed into [HISTORY_BYTES] at most (one zip entry any KJB reads; a year of ten friends'
 * plays and every card take a fraction of it). Sealed bytes do not shrink in the zip and count in full towards what a
 * backup may hold unpacked, so the history is packed before it is sealed, and a friend sending far more than anyone
 * plays only loses their oldest plays from the backup, never from the phone. Format 1 (KJB v4 test builds) sealed the
 * state alone, with its newest cards and plays in it, and still opens.
 */
object HuddleBackup {
    /** PBKDF2 rounds for a new passphrase: OWASP's 600,000 for HMAC-SHA256, under a second on a phone. */
    const val ROUNDS: Int = 600_000

    /** The shortest passphrase, in characters. */
    const val PASSPHRASE_MIN: Int = 8

    /** [passphrase]'s key under a new random salt; slow on purpose ([ROUNDS]), so off the main thread. */
    fun newKey(passphrase: CharArray, random: SecureRandom, rounds: Int = ROUNDS): BackupKey {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return BackupKey(HuddleCrypto.encode(salt), rounds, HuddleCrypto.encode(HuddleCrypto.passphraseKey(passphrase, salt, rounds)))
    }

    /** What a section holds, opened: Huddle's state, and its history ([HuddleLog]): cards both ways and friends' plays. */
    data class Opened(val state: HuddleState, val cards: List<KeptCard>, val plays: List<FriendPlay>)

    /**
     * [state]'s section with its [history] (whole lines, the newest first, every card before any play, within [bytes]
     * packed), sealed with its [HuddleState.backupKey]; null without one (no passphrase, so Huddle stays out of backups),
     * and null when the history cannot be read, as a backup leaves out any store that cannot be (BackupStores.snapshot).
     */
    suspend fun seal(state: HuddleState, history: HuddleLog, random: SecureRandom, bytes: Int = HISTORY_BYTES): String? {
        val key = state.backupKey ?: return null
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).bufferedWriter().use { out ->
            out.appendLine(HuddleJson.encode(state.copy(inbox = emptyList(), outbox = emptyList(), backupKey = null)))
            // The plays only while there is room after every card.
            val room = pages({ packed.size() >= bytes }, { history.cards(it, PAGE) }) { card ->
                out.appendLine(
                    when (card) {
                        is Received -> JsonObject(mapOf(RECEIVED_LINE to HuddleJson.json.encodeToJsonElement(Received.serializer(), card))).toString()
                        is Sent -> JsonObject(mapOf(SENT_LINE to HuddleJson.json.encodeToJsonElement(Sent.serializer(), card))).toString()
                    },
                )
            } ?: return null
            if (room) {
                pages({ packed.size() >= bytes }, { history.plays(null, it, PAGE) }) { play ->
                    out.appendLine(JsonObject(mapOf(PLAY_LINE to HuddleJson.json.encodeToJsonElement(FriendPlay.serializer(), play))).toString())
                } ?: return null
            }
        }
        val sealed = HuddleCrypto.seal(HuddleCrypto.decode(key.key) ?: return null, packed.toByteArray(), aad(FORMAT), random)
        return JsonObject(
            mapOf(
                FORMAT_KEY to JsonPrimitive(FORMAT),
                KDF_KEY to JsonPrimitive(KDF),
                ROUNDS_KEY to JsonPrimitive(key.rounds),
                SALT_KEY to JsonPrimitive(key.salt),
                BOX_KEY to JsonPrimitive(HuddleCrypto.encode(sealed)),
            ),
        ).toString()
    }

    /**
     * What [section] holds, opened with [passphrase], with the key that opened it ([HuddleState.backupKey]) for the next
     * backups; null for another passphrase, a changed section, or none. A line of the history this version cannot read (a
     * newer KJB's) is left out. Slow, as [newKey].
     */
    fun open(section: String, passphrase: CharArray): Opened? {
        val root = jsonObject(section)?.takeIf { it.text(KDF_KEY) == KDF } ?: return null
        val format = root.int(FORMAT_KEY)?.takeIf { it in 1..FORMAT } ?: return null
        val rounds = root.int(ROUNDS_KEY)?.takeIf { it in 1..MAX_ROUNDS } ?: return null
        val saltText = root.text(SALT_KEY) ?: return null
        val salt = HuddleCrypto.decode(saltText)?.takeIf { it.size == SALT_BYTES } ?: return null
        val sealed = root.text(BOX_KEY)?.let(HuddleCrypto::decode) ?: return null
        val key = HuddleCrypto.passphraseKey(passphrase, salt, rounds)
        val plain = HuddleCrypto.open(key, sealed, aad(format)) ?: return null
        val backupKey = BackupKey(saltText, rounds, HuddleCrypto.encode(key))
        return try {
            if (format == 1) {
                HuddleJson.decode(plain.decodeToString()).let { Opened(it.copy(inbox = emptyList(), backupKey = backupKey), it.inbox, it.plays) }
            } else {
                GZIPInputStream(plain.inputStream()).bufferedReader().useLines { text ->
                    val lines = text.iterator()
                    if (!lines.hasNext()) return null
                    val state = HuddleJson.decode(lines.next()).copy(backupKey = backupKey)
                    val cards = ArrayList<KeptCard>()
                    val plays = ArrayList<FriendPlay>()
                    lines.forEach { line ->
                        val item = jsonObject(line) ?: return@forEach
                        try {
                            item[RECEIVED_LINE]?.let { cards += HuddleJson.json.decodeFromJsonElement(Received.serializer(), it) }
                            item[SENT_LINE]?.let { cards += HuddleJson.json.decodeFromJsonElement(Sent.serializer(), it) }
                            item[PLAY_LINE]?.let { plays += HuddleJson.json.decodeFromJsonElement(FriendPlay.serializer(), it) }
                        } catch (_: SerializationException) {
                            // Not what this version reads in that line: left out, the rest restores.
                        }
                    }
                    Opened(state, cards, plays)
                }
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Writes each of [read]'s pages (from the first, by where the next starts) with [write], until a page comes empty or
     * the section is [full] before an item: false when it was full, null when a page could not be read.
     */
    private suspend fun <T> pages(full: () -> Boolean, read: suspend (from: Int) -> List<T>?, write: (T) -> Unit): Boolean? {
        var from = 0
        while (true) {
            val page = read(from) ?: return null
            if (page.isEmpty()) return true
            for (item in page) {
                if (full()) return false
                write(item)
            }
            from += page.size
        }
    }

    /** What each format's box is bound to: a box of one format never opens as another. */
    private fun aad(format: Int): ByteArray = "KJB Huddle $format backup".encodeToByteArray()

    /**
     * How much of the history a section holds, packed before it is sealed: half of what any KJB reads of one entry, its
     * text a third more once sealed (base64), so the whole backup stays well within what a backup may hold unpacked.
     */
    const val HISTORY_BYTES: Int = 16 * 1024 * 1024

    /** Rows of the history read at a time while a section is made. */
    private const val PAGE = 2_000

    /** The key's salt: 16 random bytes per passphrase set. */
    private const val SALT_BYTES = 16

    /** A section asking for more rounds than this is not KJB's: it would keep the phone busy for minutes. */
    private const val MAX_ROUNDS = 10_000_000
    private const val FORMAT = 2
    private const val KDF = "pbkdf2-sha256"
    private const val FORMAT_KEY = "format"
    private const val KDF_KEY = "kdf"
    private const val ROUNDS_KEY = "rounds"
    private const val SALT_KEY = "salt"
    private const val BOX_KEY = "box"
    private const val RECEIVED_LINE = "received"
    private const val SENT_LINE = "sent"
    private const val PLAY_LINE = "play"
}

/**
 * This phone's state with [backup]'s restored into it: friends and invites it does not have yet, each friend's latest
 * play of the two, its own name and Listen privately kept when set, and [key] (the passphrase the restore was opened with)
 * kept for the next backups. The backup's past friends it does not know come back as past friends, and a friend who left
 * this phone stays left, their latest play too (a backup made before). The backup's cards and plays go to the history
 * (Huddle.restore).
 */
fun HuddleState.restored(backup: HuddleState, key: BackupKey): HuddleState {
    val boxes = friends.map { it.inBox }.toSet()
    val tokens = invites.map { it.token }.toSet()
    val left = past.map { it.id }.toSet()
    return copy(
        name = name ?: backup.name,
        friends = friends + backup.friends.filter { it.inBox !in boxes && it.id !in left },
        past = past + backup.past.filter { gone -> gone.id !in left && friends.none { it.id == gone.id } },
        invites = invites + backup.invites.filter { it.token !in tokens },
        plays = latestPlays(plays + backup.plays.filter { it.friend !in left }),
        privateUntil = privateUntil ?: backup.privateUntil,
        backupKey = key,
    )
}

/** Where Huddle's state is kept: `huddle.json` ([HuddleFiles]), memory in tests. */
interface HuddleStorage {
    val state: Flow<HuddleState>

    /** [change] applied and written; false when it could not be written, never throws for that. [change] may run more than once. */
    suspend fun change(change: (HuddleState) -> HuddleState): Boolean
}

/**
 * Reads and writes [HuddleState]. A file that is not Huddle's state at all is first copied to [backupDir] as
 * `huddle-corrupt-<epochMillis>.json` (the newest copy only, in app-private files like the file itself), then reported,
 * so Huddle starts over without losing it; one from a newer KJB is left as it is. The log gets the copy's name and size only.
 */
internal class HuddleSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<HuddleState> {
    override val defaultValue: HuddleState = HuddleState()

    override suspend fun readFrom(input: InputStream): HuddleState {
        val bytes = input.readBytes()
        val text = bytes.decodeToString()
        // Not corrupt: left as it is for the newer KJB, never started over (an IOException, not a CorruptionException).
        if (HuddleJson.fromNewer(text)) throw IOException("huddle from a newer schema")
        return try {
            HuddleJson.decode(text)
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable huddle, kept as ${keep(bytes)}", e)
        }
    }

    override suspend fun writeTo(t: HuddleState, output: OutputStream) {
        output.write(HuddleJson.encode(t).encodeToByteArray())
    }

    private fun keep(bytes: ByteArray): String {
        val copies = backupDir.listFiles().orEmpty().filter { it.name.startsWith(BACKUP_PREFIX) && it.name.endsWith(BACKUP_SUFFIX) }
        copies.firstOrNull { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }?.let { return it.name }
        val copy = File(backupDir, "$BACKUP_PREFIX${clock()}$BACKUP_SUFFIX")
        // On storage before Huddle starts over.
        Files.write(copy.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        log("huddle unreadable: kept as ${copy.name} (${bytes.size} bytes), starting over")
        copies.forEach { old ->
            try {
                Files.deleteIfExists(old.toPath())
            } catch (e: IOException) {
                log("huddle: old copy ${old.name} not removed: ${e.javaClass.simpleName}")
            }
        }
        return copy.name
    }

    private companion object {
        const val BACKUP_PREFIX: String = "huddle-corrupt-"
        const val BACKUP_SUFFIX: String = ".json"
    }
}

/** Huddle's DataStore at [file]: one per file and process. It starts over only once [HuddleSerializer] kept a copy in [backupDir]. */
internal fun huddleStore(file: File, backupDir: File, scope: CoroutineScope, clock: () -> Long, log: (String) -> Unit): DataStore<HuddleState> =
    DataStoreFactory.create(
        serializer = HuddleSerializer(backupDir, clock, log),
        corruptionHandler = ReplaceFileCorruptionHandler { HuddleState() },
        scope = scope,
        produceFile = { file },
    )

/** [HuddleStorage] on a DataStore; a failed write is retried a few times, then reported. */
class HuddleFiles internal constructor(private val store: DataStore<HuddleState>, private val log: (String) -> Unit) : HuddleStorage {
    override val state: Flow<HuddleState> = store.data.catch { e ->
        if (e !is IOException) throw e
        log("huddle unreadable: ${e.javaClass.simpleName}")
        emit(HuddleState())
    }

    override suspend fun change(change: (HuddleState) -> HuddleState): Boolean =
        writeWithRetries(onFailure = { log("huddle not written: ${it.javaClass.simpleName}") }) { store.updateData(change) }

    companion object {
        private var shared: HuddleFiles? = null

        /**
         * The app's: app-private `files/datastore/huddle.json`, a copy of an unreadable one in `files`; never in Android's
         * backups (allowBackup is off), only in KJB's own, sealed ([HuddleBackup]). One per process.
         */
        @Synchronized
        fun of(context: Context): HuddleFiles = shared ?: run {
            val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
            val file = context.applicationContext.dataStoreFile("huddle.json")
            HuddleFiles(huddleStore(file, context.applicationContext.filesDir, CoroutineScope(Dispatchers.IO + SupervisorJob()), System::currentTimeMillis, log), log)
        }.also { shared = it }
    }
}

/**
 * Huddle's history, kept on the phone only, in `plays.db` beside the plays ([PlaysSchema] version 6): every card between
 * you and your friends, both ways, for good, and friends' plays of the last year. In backups only in Huddle's own
 * section, sealed ([HuddleBackup]). Never throws: a write that fails is false, a read that fails null.
 */
interface HuddleLog {
    /**
     * Keeps [cards] (each once, by its id: one there already stays as it is) and [plays] (each once: a play with all the
     * same values is the same play, sent again), then lets go of the plays from before [playsFrom] and of a friend's
     * oldest past [PLAYS_PER_FRIEND]; all in one go or none.
     */
    suspend fun keep(cards: List<KeptCard> = emptyList(), plays: List<FriendPlay> = emptyList(), playsFrom: Long): Boolean

    /** The card [id], either way; null for none, or when the history cannot be read. */
    suspend fun card(id: String): KeptCard?

    /** The cards between you and [friend], both ways, the newest first: [limit] of them from the [from]th. */
    suspend fun thread(friend: String, from: Int, limit: Int): List<KeptCard>?

    /** The cards friends sent you, the newest first: [limit] of them from the [from]th. */
    suspend fun received(from: Int, limit: Int): List<Received>?

    /** Every card, both ways, the newest first: [limit] of them from the [from]th (a backup). */
    suspend fun cards(from: Int, limit: Int): List<KeptCard>?

    /** [friend]'s plays (every friend's for null: a backup), the newest first: [limit] of them from the [from]th. */
    suspend fun plays(friend: String?, from: Int, limit: Int): List<FriendPlay>?

    /** Every friend's plays from [from] (wall-clock millis, inclusive), the oldest first: what Suggestions read. */
    suspend fun playsSince(from: Long): List<FriendPlay>?

    /** The card [id] a friend sent was played or looked at; [dismissed]: put away from the start screen too. */
    suspend fun opened(id: String, dismissed: Boolean): Boolean

    /** Every card and play of [friend] let go: Delete history on a past friend's page, or a friend who could not be written. */
    suspend fun forget(friend: String): Boolean

    companion object {
        /**
         * The most plays kept of one friend: a year of 12 hours a day of 3-minute songs is about 90,000, so a year of
         * anyone's listening fits; only a friend's KJB gone wrong (the server takes 240 messages an hour from it) reaches
         * it, and then their oldest plays go, never another friend's.
         */
        const val PLAYS_PER_FRIEND: Int = 100_000
    }
}

/** [card]'s values in [PlaysSchema.KEEP_CARD]'s order: the card itself as a message holds it ([Messages.cardJson]). */
internal fun keptCardArgs(card: KeptCard): Array<Any?> = arrayOf(
    card.id,
    card.friend,
    if (card is Sent) 1L else 0L,
    card.at,
    Messages.cardJson(card.card).toString(),
    if ((card as? Received)?.opened == true) 1L else 0L,
    if ((card as? Received)?.dismissed == true) 1L else 0L,
    if ((card as? Sent)?.delivered == false) 0L else 1L,
)

/** [play]'s values in [PlaysSchema.KEEP_FRIEND_PLAY]'s order. */
internal fun friendPlayArgs(play: FriendPlay): Array<Any?> =
    arrayOf(play.friend, play.at, play.song.title, play.song.artist, play.song.album, play.song.durationMs, if (play.favourite) 1L else 0L)

/**
 * A card as a row of [PlaysSchema.CARD] has it, read leniently: one without its id, friend or time, or whose card does not
 * read ([Messages.cardOf]), is dropped; a mark that is not a number reads as not opened, not put away, and delivered.
 */
internal fun keptCardOf(row: PlayRow): KeptCard? {
    val id = row.text("id")?.takeIf(String::isNotEmpty) ?: return null
    val friend = row.text("friend")?.takeIf(String::isNotEmpty) ?: return null
    val at = row.number("at") ?: return null
    val card = row.text("card")?.let(::jsonObject)?.let(Messages::cardOf) ?: return null
    return if ((row.number("sent") ?: 0) != 0L) {
        Sent(id, friend, at, card, delivered = row.number("delivered") != 0L)
    } else {
        Received(id, friend, at, card, opened = (row.number("opened") ?: 0) != 0L, dismissed = (row.number("dismissed") ?: 0) != 0L)
    }
}

/**
 * A friend's play as a row of [PlaysSchema.FRIEND_PLAYS] has it, read leniently: one without a friend, a time or a title is
 * dropped; a length below 0 (or none) is 0, a favourite mark that is not a number is not one.
 */
internal fun friendPlayOf(row: PlayRow): FriendPlay? {
    val friend = row.text("friend")?.takeIf(String::isNotEmpty) ?: return null
    val at = row.number("at") ?: return null
    val title = row.text("title")?.takeIf(String::isNotEmpty) ?: return null
    val song = CardSong(title, row.text("artist"), row.text("album"), (row.number("duration_ms") ?: 0).coerceAtLeast(0))
    return FriendPlay(friend, at, song, favourite = (row.number("favourite") ?: 0) != 0L)
}

/** [HuddleLog] over `plays.db` ([db]: the play log's own helper, StatsGraph.db), on [io]. The log gets counts and exception names only. */
class SqlHuddleLog(private val db: SQLiteOpenHelper, private val io: CoroutineDispatcher = Dispatchers.IO, private val log: (String) -> Unit = {}) : HuddleLog {
    override suspend fun keep(cards: List<KeptCard>, plays: List<FriendPlay>, playsFrom: Long): Boolean = withContext(io) {
        try {
            db.writableDatabase.transaction {
                cards.forEach { execSQL(PlaysSchema.KEEP_CARD, keptCardArgs(it)) }
                plays.forEach { execSQL(PlaysSchema.KEEP_FRIEND_PLAY, friendPlayArgs(it)) }
                execSQL(PlaysSchema.FRIEND_PLAYS_BEFORE, arrayOf<Any?>(playsFrom))
                plays.mapTo(HashSet()) { it.friend }.forEach { friend -> execSQL(PlaysSchema.FRIEND_PLAYS_PAST, arrayOf<Any?>(friend, HuddleLog.PLAYS_PER_FRIEND)) }
            }
            true
        } catch (e: Exception) {
            log("huddle: ${cards.size} cards and ${plays.size} plays not kept (${e.javaClass.simpleName})")
            false
        }
    }

    override suspend fun card(id: String): KeptCard? = read("card", PlaysSchema.CARD, id, row = ::keptCardOf)?.firstOrNull()

    override suspend fun thread(friend: String, from: Int, limit: Int): List<KeptCard>? =
        read("thread", PlaysSchema.THREAD, friend, limit.toString(), from.toString(), row = ::keptCardOf)

    override suspend fun received(from: Int, limit: Int): List<Received>? =
        read("cards", PlaysSchema.RECEIVED, limit.toString(), from.toString()) { keptCardOf(it) as? Received }

    override suspend fun cards(from: Int, limit: Int): List<KeptCard>? = read("cards", PlaysSchema.CARDS, limit.toString(), from.toString(), row = ::keptCardOf)

    override suspend fun plays(friend: String?, from: Int, limit: Int): List<FriendPlay>? = if (friend == null) {
        read("plays", PlaysSchema.ALL_FRIEND_PLAYS, limit.toString(), from.toString(), row = ::friendPlayOf)
    } else {
        read("plays", PlaysSchema.FRIEND_PLAYS, friend, limit.toString(), from.toString(), row = ::friendPlayOf)
    }

    override suspend fun playsSince(from: Long): List<FriendPlay>? = read("plays", PlaysSchema.FRIEND_PLAYS_SINCE, from.toString(), row = ::friendPlayOf)

    override suspend fun opened(id: String, dismissed: Boolean): Boolean = write("opened") { execSQL(PlaysSchema.CARD_OPENED, arrayOf<Any?>(if (dismissed) 1L else 0L, id)) }

    override suspend fun forget(friend: String): Boolean = write("forget") {
        execSQL(PlaysSchema.FORGET_FRIEND_CARDS, arrayOf<Any?>(friend))
        execSQL(PlaysSchema.FORGET_FRIEND_PLAYS, arrayOf<Any?>(friend))
    }

    /** [sql]'s rows with [args] (text, as the platform binds a query's), each read by [row]; null when [what] could not be read. */
    private suspend fun <T> read(what: String, sql: String, vararg args: String, row: (PlayRow) -> T?): List<T>? = withContext(io) {
        try {
            db.readableDatabase.rawQuery(sql, args).use { rows ->
                val at = SqlPlayLog.CursorRow(rows)
                buildList { while (rows.moveToNext()) row(at)?.let(::add) }
            }
        } catch (e: Exception) {
            log("huddle: $what not read (${e.javaClass.simpleName})")
            null
        }
    }

    /** [work] in one transaction; false when [what] could not be written. */
    private suspend fun write(what: String, work: SQLiteDatabase.() -> Unit): Boolean = withContext(io) {
        try {
            db.writableDatabase.transaction(body = work)
            true
        } catch (e: Exception) {
            log("huddle: $what not written (${e.javaClass.simpleName})")
            false
        }
    }
}
