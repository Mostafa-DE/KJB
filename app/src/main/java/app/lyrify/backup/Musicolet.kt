package app.lyrify.backup

import android.annotation.SuppressLint
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Locale
import java.util.Timer
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.timerTask

/** A row of Musicolet's library (`TABLE_SONGS` in `DB_SONGS_LOG`): its song reference, tags, length and plays. */
data class MusicoletSong(
    val uri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val flags: Long,
)

/** Reads `DB_SONGS_LOG`'s song table: Android's SQLite on the phone ([MusicoletSqlite]), a fake in JVM tests. */
fun interface MusicoletDb {
    /** The rows of [bytes]' `TABLE_SONGS`, at most [Musicolet.MAX_SONGS]; null when it is no SQLite file with that table. */
    fun songs(bytes: ByteArray): List<MusicoletSong>?
}

/** A Musicolet backup read: what it holds, or why nothing in it is used. */
sealed interface MusicoletRead {
    data class Read(val bundle: ImportBundle) : MusicoletRead

    data class Failed(val why: ImportFailure) : MusicoletRead
}

/**
 * Musicolet's own backup (handoff musicolet-backup-format.md): a flat zip, every member but `hash` encrypted with
 * Blowfish (ECB, PKCS5 padding) under Musicolet's fixed key; `0.musicolet.backup`, the manifest, lists each member's
 * md5, and `hash` is the manifest's. `DB_SONGS_LOG` (SQLite) is the library with its play counts; `0.qstk` the queues,
 * `<name>.mpl` the playlists and `0.favs` the favourites (JSON). Every song is a reference,
 * `musicolet://media-store?p_v=<volume>&p_rp=<folder>&p_dn=<file>&p_id=<MediaStore id>`, never opened.
 *
 * Read as untrusted input, in memory, one entry at a time under [LIMITS], each decrypted as it is unpacked and kept
 * only when KJB reads it; nothing trusted before the hash and every md5 agree and every member is listed; the songs
 * table only through [MusicoletDb]; the settings and the search history (`SSP`) checked but never held. Anything not
 * as known makes the whole backup unusable.
 */
object Musicolet {
    const val MANIFEST: String = "0.musicolet.backup"
    const val HASH: String = "hash"
    const val SONGS: String = "DB_SONGS_LOG"
    const val QUEUES: String = "0.qstk"
    const val FAVOURITES: String = "0.favs"
    const val PLAYLIST: String = ".mpl"

    /** Musicolet's fixed key, the same in every backup. */
    const val KEY: String = "JSTMUSIC_2"
    const val CIPHER: String = "Blowfish/ECB/PKCS5Padding"
    /** The songs table is read only up to this many rows; past it the backup is not read. */
    const val MAX_SONGS: Int = 100_000

    /** A song Musicolet marks as gone from its phone (`COL_VIS_FLAGS`). */
    const val GONE: Long = 64

    /** A backup this big or bigger is no backup: 7,302 songs took 2.3 MB zipped, 5 MB unpacked. */
    val LIMITS: ZipLimits = ZipLimits(entryBytes = 48L * 1024 * 1024, totalBytes = 96L * 1024 * 1024, entries = 1_000)

    /** What the zip [bytes] hold when they are a Musicolet backup; [MusicoletRead.Failed] with why otherwise. */
    fun read(bytes: ByteArray, db: MusicoletDb, limits: ZipLimits = LIMITS): MusicoletRead {
        val members = when (val read = members(bytes, limits)) {
            is Members.Refused -> return MusicoletRead.Failed(read.why)
            is Members.Read -> read
        }
        val hash = members.kept[HASH]
        if (MANIFEST !in members.names || hash == null) return MusicoletRead.Failed(ImportFailure.NOT_RECOGNISED)
        val unusable = MusicoletRead.Failed(ImportFailure.MUSICOLET)
        val manifest = members.kept[MANIFEST]?.takeIf { md5(it) == hash.decodeToString().trim().lowercase(Locale.ROOT) } ?: return unusable
        val sums = (jsonObject(manifest)?.get(MD5_KEY) as? JsonObject)?.mapValues { (_, sum) -> sum.text()?.lowercase(Locale.ROOT) ?: return unusable } ?: return unusable
        if (members.names - MANIFEST - HASH != sums.keys || sums.any { (name, sum) -> members.sums[name] != sum }) return unusable
        val rows = members.kept[SONGS]?.let(db::songs) ?: return unusable
        val library = rows.associateBy { it.uri }
        val playlists = members.kept.keys.filter { it.endsWith(PLAYLIST) }.sorted().map { name ->
            ImportPlaylist(ImportFormats.cleanName(name.removeSuffix(PLAYLIST)), list(members.kept.getValue(name), library) ?: return unusable)
        }
        val favourites = members.kept[FAVOURITES]?.let { list(it, library) ?: return unusable }.orEmpty()
        val queues = members.kept[QUEUES]?.let { queues(it, library) ?: return unusable }.orEmpty()
        val plays = rows.filter { it.playCount > 0 && it.flags and GONE == 0L }.map { row -> ImportPlays(song(row.uri, library), row.playCount, row.lastPlayedAt?.takeIf { it > 0 }) }
        return MusicoletRead.Read(ImportBundle(playlists, favourites, plays, queues))
    }

    /** A backup's members as read: every name, each decrypted member's md5, and the plain bytes of those KJB reads. */
    private sealed interface Members {
        data class Read(val names: Set<String>, val sums: Map<String, String>, val kept: Map<String, ByteArray>) : Members

        data class Refused(val why: ImportFailure) : Members
    }

    /**
     * The zip [bytes] read one entry at a time, each decrypted as it is unpacked: its md5 taken, its plain bytes kept
     * only when KJB reads it (the manifest, `hash` as it is, the songs table, the queues, playlists and favourites), so
     * the settings and search history are checked and never held. Counted against [limits] as it goes; two entries of
     * one name, or a damaged zip, make it unreadable. A member that does not decrypt has no md5, so it fails its check.
     */
    private fun members(bytes: ByteArray, limits: ZipLimits): Members {
        val names = HashSet<String>()
        val sums = HashMap<String, String>()
        val kept = HashMap<String, ByteArray>()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (names.size == limits.entries) return Members.Refused(ImportFailure.TOO_BIG)
                    if (!names.add(name)) return Members.Refused(ImportFailure.UNREADABLE)
                    val out = if (name == HASH || name == MANIFEST || read(name)) ByteArrayOutputStream() else null
                    val cipher = if (name == HASH) null else decryptor()
                    val digest = MessageDigest.getInstance("MD5")
                    var size = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        size += count
                        total += count
                        if (size > limits.entryBytes || total > limits.totalBytes) return Members.Refused(ImportFailure.TOO_BIG)
                        if (name == HASH) out?.write(buffer, 0, count) else cipher?.update(buffer, 0, count)?.let { plain -> digest.update(plain); out?.write(plain) }
                    }
                    if (name == HASH) {
                        out?.let { kept[name] = it.toByteArray() }
                        continue
                    }
                    val last = try {
                        cipher?.doFinal()
                    } catch (_: GeneralSecurityException) {
                        // Padding that does not check: another key, or not Musicolet's.
                        null
                    } ?: continue
                    digest.update(last)
                    sums[name] = digest.digest().joinToString("") { "%02x".format(it) }
                    out?.let { it.write(last); kept[name] = it.toByteArray() }
                }
            }
            Members.Read(names, sums, kept)
        } catch (_: IOException) {
            // A ZipException (a bad CRC, a bad entry name), or an EOFException: cut short.
            Members.Refused(ImportFailure.UNREADABLE)
        } catch (_: IllegalArgumentException) {
            // An entry name that is not UTF-8.
            Members.Refused(ImportFailure.UNREADABLE)
        }
    }

    /** Whether KJB reads the member [name]: the songs table, the queues, the favourites and the playlists. */
    private fun read(name: String): Boolean = name == SONGS || name == QUEUES || name == FAVOURITES || name.endsWith(PLAYLIST)

    /** A playlist or the favourites: the `S_P` references in order, with their own tags (`S_T`, `S_AR`, `S_AL`, `S_D`) for a song the library does not list. */
    private fun list(bytes: ByteArray, library: Map<String, MusicoletSong>): List<ImportSong>? = songs(jsonObject(bytes) ?: return null, library)

    private fun songs(list: JsonObject, library: Map<String, MusicoletSong>): List<ImportSong>? {
        val uris = list[SONGS_KEY] as? JsonArray ?: return null
        if (uris.size > ImportFormats.MAX_TOTAL_ENTRIES) return null
        fun tag(key: String, at: Int): JsonPrimitive? = ((list[key] as? JsonArray)?.getOrNull(at) as? JsonPrimitive)
        return uris.mapIndexedNotNull { at, element ->
            val uri = element.text() ?: return@mapIndexedNotNull null
            val own = Tags(tag(TITLES_KEY, at)?.text(), tag(ARTISTS_KEY, at)?.text(), tag(ALBUMS_KEY, at)?.text(), tag(LENGTHS_KEY, at)?.takeUnless { it.isString }?.longOrNull)
            song(uri, library, own)
        }
    }

    /** `0.qstk`: each queue's songs, the one it was at (`S0_PQ_CPS`), the second in it (`S0_PQ_LKP`) and its name (`S0_PQ_T`). */
    private fun queues(bytes: ByteArray, library: Map<String, MusicoletSong>): List<ImportQueue>? {
        val root = jsonObject(bytes) ?: return null
        val queues = root[QUEUES_KEY] ?: return emptyList()
        if (queues !is JsonArray) return null
        return queues.map { element ->
            val queue = element as? JsonObject ?: return null
            val songs = songs(queue[QUEUE_SONGS_KEY] as? JsonObject ?: return null, library) ?: return null
            ImportQueue(
                songs,
                queue.number(QUEUE_INDEX_KEY)?.toInt()?.takeIf { it >= 0 } ?: 0,
                queue.number(QUEUE_PLACE_KEY)?.takeIf { it >= 0 } ?: 0,
                ImportFormats.cleanName(queue[QUEUE_NAME_KEY]?.text()),
            )
        }
    }

    private data class Tags(val title: String?, val artist: String?, val album: String?, val durationMs: Long?)

    /**
     * The song [uri] names: by its MediaStore id, its folder and file name below its volume's root, and its tags from
     * the library's row, else [own]. A song Musicolet marks as gone from its phone keeps only what lists it, so it is
     * never found, as the format notes advise.
     */
    private fun song(uri: String, library: Map<String, MusicoletSong>, own: Tags? = null): ImportSong {
        val query = uri.substringAfter('?', "").split('&').associate { it.substringBefore('=') to M3u.percentDecoded(it.substringAfter('=', "")) }
        val name = ImportFormats.clean(query[FILE_PARAM])
        val folder = ImportFormats.clean(query[FOLDER_PARAM])?.trim('/')
        val relative = name?.let { if (folder.isNullOrEmpty()) it else "$folder/$it" }
        val row = library[uri]
        val tags = row?.let { Tags(known(it.title), known(it.artist), known(it.album), it.durationMs?.takeIf { ms -> ms > 0 }) }
            ?: own?.let { Tags(known(it.title), known(it.artist), known(it.album), it.durationMs?.takeIf { ms -> ms > 0 }) }
        val location = relative ?: tags?.title.orEmpty()
        if (row != null && row.flags and GONE != 0L) return ImportSong(location, null, tags?.title, tags?.artist, tags?.album)
        return ImportSong(
            location = location,
            path = null,
            title = tags?.title,
            artist = tags?.artist,
            album = tags?.album,
            durationMs = tags?.durationMs,
            relativePath = relative,
            mediaStoreId = query[ID_PARAM]?.toLongOrNull()?.takeIf { it > 0 },
        )
    }

    /** A tag as KJB keeps it: Musicolet writes "<unknown>" for none. */
    private fun known(text: String?): String? = ImportFormats.clean(text)?.takeUnless { it.equals(UNKNOWN, ignoreCase = true) }

    /**
     * A cipher that decrypts with Musicolet's key; null on a phone without Blowfish, and then nothing decrypts. ECB is
     * Musicolet's choice, not KJB's: it is only ever read here, and protects nothing of KJB's.
     */
    @SuppressLint("GetInstance")
    private fun decryptor(): Cipher? = try {
        Cipher.getInstance(CIPHER).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.encodeToByteArray(), "Blowfish")) }
    } catch (_: GeneralSecurityException) {
        null
    }

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun jsonObject(bytes: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private const val BUFFER: Int = 64 * 1024
    private const val UNKNOWN: String = "<unknown>"
    private const val MD5_KEY: String = "md5"
    private const val SONGS_KEY: String = "S_P"
    private const val TITLES_KEY: String = "S_T"
    private const val ARTISTS_KEY: String = "S_AR"
    private const val ALBUMS_KEY: String = "S_AL"
    private const val LENGTHS_KEY: String = "S_D"
    private const val QUEUES_KEY: String = "S0_PQ"
    private const val QUEUE_SONGS_KEY: String = "S0_PQ_OQS"
    private const val QUEUE_NAME_KEY: String = "S0_PQ_T"
    private const val QUEUE_INDEX_KEY: String = "S0_PQ_CPS"
    private const val QUEUE_PLACE_KEY: String = "S0_PQ_LKP"
    private const val FOLDER_PARAM: String = "p_rp"
    private const val FILE_PARAM: String = "p_dn"
    private const val ID_PARAM: String = "p_id"
}

/** The rules [MusicoletSqlite] reads the songs table by, apart so they are tested on the JVM. */
internal object SongsTable {
    /** Whether `sqlite_master`'s [type] and [sql] for the songs table are a plain table's: no view, virtual table or computed column. */
    fun plain(type: String?, sql: String?): Boolean {
        if (type != "table" || sql == null) return false
        val upper = sql.uppercase(Locale.ROOT)
        return "VIRTUAL" !in upper && "GENERATED" !in upper && !COMPUTED.containsMatchIn(upper)
    }

    /** A computed column, "AS (expression)": its rows are made when read, not stored. */
    private val COMPUTED = Regex("""\bAS\s*\(""")
}

/**
 * What one read of the songs table may take: each text at most [maxField] chars, all of them [maxTotal], and the time
 * until [deadline] on [clock]. Past any of them the file is not read.
 */
internal class TextBudget(private val maxField: Int, private val maxTotal: Long, private val deadline: Long, private val clock: () -> Long) {
    private var total = 0L

    /** Takes [text] from the budget; false when it is spent. */
    fun take(text: String?): Boolean {
        if (clock() > deadline) return false
        val length = text?.length ?: 0
        if (length > maxField) return false
        total += length
        return total <= maxTotal
    }
}

/**
 * The private folders the songs table is read in, under [root] (in the app's cache), empty after every read however
 * it ends (phone run 2026-11-15: a file that was no database left a file there that a list of names did not know).
 * Reads go one at a time, process-wide; each first clears what an earlier one left when the app died, then gets a
 * folder of its own, deleted whole afterwards, whatever is in it. [log] gets counts only.
 */
internal class ScratchDir(private val root: File, private val log: (String) -> Unit = {}) {
    /** Runs [read] in a new, empty folder of its own, and deletes that folder and all in it afterwards. */
    fun <T> use(read: (File) -> T): T = synchronized(LOCK) {
        if (root.exists() && !root.deleteRecursively()) log("musicolet: earlier scratch files not all deleted")
        val dir = File(root, "read-${System.nanoTime()}")
        try {
            if (!dir.mkdirs()) throw IOException("no scratch folder")
            read(dir)
        } finally {
            if (!dir.deleteRecursively()) log("musicolet: scratch files not all deleted")
        }
    }

    private companion object {
        val LOCK: Any = Any()
    }
}

/**
 * [MusicoletDb] on the phone: Android's SQLite on a private copy in the app's cache, opened read only, in a folder of
 * its own deleted whole before it returns, whatever happens ([ScratchDir]). Only `TABLE_SONGS` is read, by a fixed query
 * ([rows]). The log gets exception class names only.
 */
class MusicoletSqlite(private val cacheDir: File, private val log: (String) -> Unit) : MusicoletDb {
    override fun songs(bytes: ByteArray): List<MusicoletSong>? {
        if (bytes.size < HEADER.size || !HEADER.indices.all { bytes[it] == HEADER[it] }) return null
        return try {
            ScratchDir(File(cacheDir, DIR), log).use { dir ->
                val file = File(dir, SONGS_FILE)
                val copy = bytes.copyOf()
                // A WAL database (header bytes 18 and 19 are 2) opens read only as a rollback one: no -wal file comes with it.
                if (copy[WRITE_VERSION] == WAL && copy[READ_VERSION] == WAL) {
                    copy[WRITE_VERSION] = LEGACY
                    copy[READ_VERSION] = LEGACY
                }
                file.writeBytes(copy)
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use(::rows)
            }
        } catch (e: Exception) {
            // SQLiteException (not a database, damaged), IOException (the cache full).
            log("musicolet: songs not read (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * The rows of `TABLE_SONGS`, or null unless it is a plain table ([SongsTable.plain]: never a view, which could
     * make rows far past the zip's caps, nor a virtual table or computed columns) with the columns KJB knows; the
     * connection is query-only. The read stops, and the file is not read, past [Musicolet.MAX_SONGS] rows, a text
     * longer than a line or [MAX_TEXT] chars in all, or [TIMEOUT_MS] ([TextBudget]); the query itself is cancelled at
     * that time too.
     */
    private fun rows(db: SQLiteDatabase): List<MusicoletSong>? {
        val table = db.rawQuery(MASTER, null).use { rows -> if (rows.count == 1 && rows.moveToFirst()) rows.text(0) to rows.text(1) else null }
        if (table == null || !SongsTable.plain(table.first, table.second)) return null
        db.rawQuery(QUERY_ONLY, null).use { it.moveToFirst() }
        val columns = db.rawQuery(COLUMNS, null).use { rows -> buildSet { while (rows.moveToNext()) rows.getString(1)?.let(::add) } }
        if (!columns.containsAll(REQUIRED)) return null
        val clock = { System.nanoTime() / 1_000_000 }
        val budget = TextBudget(ImportFormats.MAX_LINE_CHARS, MAX_TEXT, clock() + TIMEOUT_MS, clock)
        val signal = CancellationSignal()
        val timer = Timer(TIMER, true).apply { schedule(timerTask { signal.cancel() }, TIMEOUT_MS) }
        try {
            return db.rawQuery(if (FLAGS in columns) SONGS_WITH_FLAGS else SONGS, null, signal).use { rows ->
                val songs = ArrayList<MusicoletSong>()
                while (rows.moveToNext()) {
                    if (songs.size == Musicolet.MAX_SONGS) return null
                    val texts = (0..3).map { rows.text(it) }
                    if (!texts.all(budget::take)) return null
                    val uri = texts[0] ?: continue
                    songs += MusicoletSong(
                        uri = uri,
                        title = texts[1],
                        artist = texts[2],
                        album = texts[3],
                        durationMs = rows.number(4),
                        playCount = rows.number(5)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                        lastPlayedAt = rows.number(6),
                        flags = rows.number(7) ?: 0,
                    )
                }
                songs
            }
        } finally {
            timer.cancel()
        }
    }

    private fun Cursor.text(column: Int): String? = if (isNull(column)) null else getString(column)

    /** A whole number, or null: text where a number belongs (a damaged row) reads as none. */
    private fun Cursor.number(column: Int): Long? = if (getType(column) == Cursor.FIELD_TYPE_INTEGER) getLong(column) else null

    private companion object {
        const val DIR: String = "musicolet-import"
        const val SONGS_FILE: String = "songs.db"
        const val WRITE_VERSION: Int = 18
        const val READ_VERSION: Int = 19
        const val WAL: Byte = 2
        const val LEGACY: Byte = 1
        const val FLAGS: String = "COL_VIS_FLAGS"
        const val COLUMNS: String = "PRAGMA table_info(TABLE_SONGS)"
        const val MASTER: String = "SELECT type, sql FROM sqlite_master WHERE name = 'TABLE_SONGS'"
        const val QUERY_ONLY: String = "PRAGMA query_only = 1"
        const val TIMER: String = "musicolet-read"
        const val TIMEOUT_MS: Long = 15_000

        /** All the text a songs table may hold: 7,302 songs held about 1.5 million chars. */
        const val MAX_TEXT: Long = 20_000_000
        const val PICKED: String = "COL_PATH, COL_TITLE, COL_ARTIST, COL_ALBUM, COL_DURATION, COL_NUM_PLAYED, COL_LAST_PLAYED"
        const val SONGS: String = "SELECT $PICKED, 0 FROM TABLE_SONGS"
        const val SONGS_WITH_FLAGS: String = "SELECT $PICKED, COL_VIS_FLAGS FROM TABLE_SONGS"
        val REQUIRED: Set<String> = setOf("COL_PATH", "COL_TITLE", "COL_ARTIST", "COL_ALBUM", "COL_DURATION", "COL_NUM_PLAYED", "COL_LAST_PLAYED")
        val HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()
    }
}
