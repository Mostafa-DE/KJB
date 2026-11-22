package app.lyrify.huddle

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

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
) {
    val shownName: String get() = rename ?: taken ?: name
}

/**
 * An invite no friend joined yet: its key pair and token (base64url), its [owner] id on the server (only this phone
 * knows it: it reads and deletes the invite), when it was made, and a [card] to send once joined.
 */
@Serializable
data class PendingInvite(val publicKey: String, val privateKey: String, val token: String, val owner: String, val madeAt: Long, val card: Card? = null)

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
 * A card [friend] (their id) sent, [at] when, under its message [id]; [opened] once you played it, looked at it or put it
 * away, [dismissed] once you put it away from the start screen.
 */
@Serializable
data class Received(val id: String, val friend: String, val at: Long, val card: Card, val opened: Boolean = false, val dismissed: Boolean = false)

/** A play [friend] (their id) shared, started [at]; [favourite] when the song was one of theirs. */
@Serializable
data class FriendPlay(val friend: String, val at: Long, val song: CardSong, val favourite: Boolean = false)

/**
 * A message's kind as the server knows it (huddle-server/API.md): it keeps a card and a profile 30 days until read (a new
 * profile in its place), a play a day.
 */
@Serializable
enum class Kind { CARD, PLAY, PROFILE }

/** A sealed message ([data], base64url) of [kind] waiting to go to [box], made [at], going no earlier than [notBefore] (Undo); [id] is its message's. */
@Serializable
data class Outgoing(val id: String, val box: String, val data: String, val kind: Kind, val at: Long, val notBefore: Long = at)

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
    /** Cards friends sent, newest first. */
    val inbox: List<Received> = emptyList(),
    /** Plays friends shared, newest first: now playing, and the friends source of Suggestions. */
    val plays: List<FriendPlay> = emptyList(),
    val outbox: List<Outgoing> = emptyList(),
    /** Invites this phone is joining, until the server answered ([PendingJoin]). */
    val joins: List<PendingJoin> = emptyList(),
    /** Listen privately: no play is shared before this time (wall-clock millis); [FOREVER] until turned back on. */
    val privateUntil: Long? = null,
    val backupKey: BackupKey? = null,
    /** Friends who joined your invites, by id, not yet greeted ("<name> joined your Huddle!", once each). */
    val joined: List<String> = emptyList(),
) {
    companion object {
        /** Listen privately until turned back on. */
        const val FOREVER: Long = Long.MAX_VALUE
    }
}

/**
 * The stored form of [HuddleState]: JSON with a schema version; unknown fields are ignored, missing ones take their
 * defaults. A state from before [SHARING_ON] (user 2026-11-22) has sharing turned on for every friend, once.
 */
object HuddleJson {
    const val SCHEMA_VERSION: Int = 2

    /** The version from which Share what I play is on by default: friends of an older state get it on as they are read. */
    private const val SHARING_ON: Int = 2

    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"

    private val json = Json {
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

    /** [state]'s section, sealed with its [HuddleState.backupKey]; null without one (no passphrase, so Huddle stays out of backups). */
    fun seal(state: HuddleState, random: SecureRandom): String? {
        val key = state.backupKey ?: return null
        val sealed = HuddleCrypto.seal(HuddleCrypto.decode(key.key) ?: return null, HuddleJson.encode(state.copy(outbox = emptyList(), backupKey = null)).encodeToByteArray(), AAD, random)
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
     * backups; null for another passphrase, a changed section, or none. Slow, as [newKey].
     */
    fun open(section: String, passphrase: CharArray): HuddleState? {
        val root = jsonObject(section)?.takeIf { it.int(FORMAT_KEY) == FORMAT && it.text(KDF_KEY) == KDF } ?: return null
        val rounds = root.int(ROUNDS_KEY)?.takeIf { it in 1..MAX_ROUNDS } ?: return null
        val saltText = root.text(SALT_KEY) ?: return null
        val salt = HuddleCrypto.decode(saltText)?.takeIf { it.size == SALT_BYTES } ?: return null
        val sealed = root.text(BOX_KEY)?.let(HuddleCrypto::decode) ?: return null
        val key = HuddleCrypto.passphraseKey(passphrase, salt, rounds)
        val plain = HuddleCrypto.open(key, sealed, AAD) ?: return null
        return try {
            HuddleJson.decode(plain.decodeToString()).copy(backupKey = BackupKey(saltText, rounds, HuddleCrypto.encode(key)))
        } catch (_: SerializationException) {
            null
        }
    }

    /** The key's salt: 16 random bytes per passphrase set. */
    private const val SALT_BYTES = 16

    /** A section asking for more rounds than this is not KJB's: it would keep the phone busy for minutes. */
    private const val MAX_ROUNDS = 10_000_000
    private const val FORMAT = 1
    private const val KDF = "pbkdf2-sha256"
    private const val FORMAT_KEY = "format"
    private const val KDF_KEY = "kdf"
    private const val ROUNDS_KEY = "rounds"
    private const val SALT_KEY = "salt"
    private const val BOX_KEY = "box"
    private val AAD = "KJB Huddle 1 backup".encodeToByteArray()
}

/**
 * This phone's state with [backup]'s restored into it: friends and invites it does not have yet, cards and plays once
 * each, its own name and Listen privately kept when set, and [key] (the passphrase the restore was opened with) kept
 * for the next backups.
 */
fun HuddleState.restored(backup: HuddleState, key: BackupKey): HuddleState {
    val boxes = friends.map { it.inBox }.toSet()
    val tokens = invites.map { it.token }.toSet()
    val cards = inbox.map { it.id }.toSet()
    return copy(
        name = name ?: backup.name,
        friends = friends + backup.friends.filter { it.inBox !in boxes },
        invites = invites + backup.invites.filter { it.token !in tokens },
        inbox = (inbox + backup.inbox.filter { it.id !in cards }).sortedByDescending { it.at },
        plays = (plays + backup.plays).distinct().sortedByDescending { it.at },
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
