package app.lyrify.backup

import android.annotation.SuppressLint
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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
 * Read as untrusted input, in memory: the zip under [LIMITS] ([BackupBundle.unzip]), nothing trusted before the hash
 * and every md5 agree and every member is listed, the songs table only through [MusicoletDb], the settings and the
 * search history (`SSP`) checked but never read. Anything not as known makes the whole backup unusable.
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
    const val MAX_SONGS: Int = 200_000

    /** A song Musicolet marks as gone from its phone (`COL_VIS_FLAGS`). */
    const val GONE: Long = 64

    /** A backup this big or bigger is no backup: 7,302 songs took 2.3 MB zipped, 5 MB unpacked. */
    val LIMITS: ZipLimits = ZipLimits(entryBytes = 48L * 1024 * 1024, totalBytes = 96L * 1024 * 1024, entries = 1_000)

    /** What the zip [bytes] hold when they are a Musicolet backup; [MusicoletRead.Failed] with why otherwise. */
    fun read(bytes: ByteArray, db: MusicoletDb, limits: ZipLimits = LIMITS): MusicoletRead {
        val files = when (val unzipped = BackupBundle.unzip(ByteArrayInputStream(bytes), limits) { true }) {
            is BackupBundle.Unzipped.Files -> unzipped.files
            is BackupBundle.Unzipped.Refused -> return MusicoletRead.Failed(if (unzipped.why == BackupRead.Why.TOO_BIG) ImportFailure.TOO_BIG else ImportFailure.UNREADABLE)
        }
        val manifestBytes = files[MANIFEST]
        val hash = files[HASH]
        if (manifestBytes == null || hash == null) return MusicoletRead.Failed(ImportFailure.NOT_RECOGNISED)
        val unusable = MusicoletRead.Failed(ImportFailure.MUSICOLET)
        val manifest = decrypt(manifestBytes)?.takeIf { md5(it) == hash.decodeToString().trim().lowercase(Locale.ROOT) } ?: return unusable
        val sums = (jsonObject(manifest)?.get(MD5_KEY) as? JsonObject)?.mapValues { (_, sum) -> sum.text()?.lowercase(Locale.ROOT) ?: return unusable } ?: return unusable
        if (files.keys - MANIFEST - HASH != sums.keys) return unusable
        // Every member checked; only those read are kept.
        val used = HashMap<String, ByteArray>()
        sums.forEach { (name, sum) ->
            val plain = decrypt(files.getValue(name))?.takeIf { md5(it) == sum } ?: return unusable
            if (name == SONGS || name == QUEUES || name == FAVOURITES || name.endsWith(PLAYLIST)) used[name] = plain
        }
        val rows = used[SONGS]?.let(db::songs) ?: return unusable
        val library = rows.associateBy { it.uri }
        val playlists = used.keys.filter { it.endsWith(PLAYLIST) }.sorted().map { name ->
            ImportPlaylist(ImportFormats.cleanName(name.removeSuffix(PLAYLIST)), list(used.getValue(name), library) ?: return unusable)
        }
        val favourites = used[FAVOURITES]?.let { list(it, library) ?: return unusable }.orEmpty()
        val queues = used[QUEUES]?.let { queues(it, library) ?: return unusable }.orEmpty()
        val plays = rows.filter { it.playCount > 0 && it.flags and GONE == 0L }.map { row -> ImportPlays(song(row.uri, library), row.playCount, row.lastPlayedAt?.takeIf { it > 0 }) }
        return MusicoletRead.Read(ImportBundle(playlists, favourites, plays, queues))
    }

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

    /** `0.qstk`: each queue's songs, the one it was at (`S0_PQ_CPS`) and the second in it (`S0_PQ_LKP`). */
    private fun queues(bytes: ByteArray, library: Map<String, MusicoletSong>): List<ImportQueue>? {
        val root = jsonObject(bytes) ?: return null
        val queues = root[QUEUES_KEY] ?: return emptyList()
        if (queues !is JsonArray) return null
        return queues.map { element ->
            val queue = element as? JsonObject ?: return null
            val songs = songs(queue[QUEUE_SONGS_KEY] as? JsonObject ?: return null, library) ?: return null
            ImportQueue(songs, queue.number(QUEUE_INDEX_KEY)?.toInt()?.takeIf { it >= 0 } ?: 0, queue.number(QUEUE_PLACE_KEY)?.takeIf { it >= 0 } ?: 0)
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
     * [data] decrypted with Musicolet's key; null when it is not (a length no block fits, padding that does not check).
     * ECB is Musicolet's choice, not KJB's: it is only ever read here, and protects nothing of KJB's.
     */
    @SuppressLint("GetInstance")
    private fun decrypt(data: ByteArray): ByteArray? {
        if (data.isEmpty() || data.size % BLOCK != 0) return null
        return try {
            Cipher.getInstance(CIPHER).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.encodeToByteArray(), "Blowfish")) }.doFinal(data)
        } catch (_: GeneralSecurityException) {
            // A wrong key's padding, or a phone without Blowfish: unusable either way.
            null
        }
    }

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun jsonObject(bytes: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private const val BLOCK: Int = 8
    private const val UNKNOWN: String = "<unknown>"
    private const val MD5_KEY: String = "md5"
    private const val SONGS_KEY: String = "S_P"
    private const val TITLES_KEY: String = "S_T"
    private const val ARTISTS_KEY: String = "S_AR"
    private const val ALBUMS_KEY: String = "S_AL"
    private const val LENGTHS_KEY: String = "S_D"
    private const val QUEUES_KEY: String = "S0_PQ"
    private const val QUEUE_SONGS_KEY: String = "S0_PQ_OQS"
    private const val QUEUE_INDEX_KEY: String = "S0_PQ_CPS"
    private const val QUEUE_PLACE_KEY: String = "S0_PQ_LKP"
    private const val FOLDER_PARAM: String = "p_rp"
    private const val FILE_PARAM: String = "p_dn"
    private const val ID_PARAM: String = "p_id"
}

/**
 * [MusicoletDb] on the phone: Android's SQLite on a private copy in the app's cache, opened read only and deleted
 * (with any journal beside it) before it returns, whatever happens. Only `TABLE_SONGS` is read, by a fixed query, and
 * only when it has the columns KJB knows; at most [Musicolet.MAX_SONGS] rows. The log gets exception class names only.
 */
class MusicoletSqlite(private val cacheDir: File, private val log: (String) -> Unit) : MusicoletDb {
    override fun songs(bytes: ByteArray): List<MusicoletSong>? {
        if (bytes.size < HEADER.size || !HEADER.indices.all { bytes[it] == HEADER[it] }) return null
        val dir = File(cacheDir, DIR)
        val file = File(dir, "songs-${System.nanoTime()}.db")
        return try {
            dir.mkdirs()
            val copy = bytes.copyOf()
            // A WAL database (header bytes 18 and 19 are 2) opens read only as a rollback one: no -wal file comes with it.
            if (copy[WRITE_VERSION] == WAL && copy[READ_VERSION] == WAL) {
                copy[WRITE_VERSION] = LEGACY
                copy[READ_VERSION] = LEGACY
            }
            file.writeBytes(copy)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use(::rows)
        } catch (e: Exception) {
            // SQLiteException (not a database, damaged), IOException (the cache full).
            log("musicolet: songs not read (${e.javaClass.simpleName})")
            null
        } finally {
            listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
        }
    }

    private fun rows(db: SQLiteDatabase): List<MusicoletSong>? {
        val columns = db.rawQuery(COLUMNS, null).use { rows -> buildSet { while (rows.moveToNext()) rows.getString(1)?.let(::add) } }
        if (!columns.containsAll(REQUIRED)) return null
        val query = if (FLAGS in columns) SONGS_WITH_FLAGS else SONGS
        return db.rawQuery(query, null).use { rows ->
            buildList {
                while (size < Musicolet.MAX_SONGS && rows.moveToNext()) {
                    val uri = rows.getString(0) ?: continue
                    add(
                        MusicoletSong(
                            uri = uri,
                            title = rows.text(1),
                            artist = rows.text(2),
                            album = rows.text(3),
                            durationMs = rows.number(4),
                            playCount = rows.number(5)?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                            lastPlayedAt = rows.number(6),
                            flags = rows.number(7) ?: 0,
                        ),
                    )
                }
            }
        }
    }

    private fun Cursor.text(column: Int): String? = if (isNull(column)) null else getString(column)

    /** A whole number, or null: text where a number belongs (a damaged row) reads as none. */
    private fun Cursor.number(column: Int): Long? = if (getType(column) == Cursor.FIELD_TYPE_INTEGER) getLong(column) else null

    private companion object {
        const val DIR: String = "musicolet-import"
        const val WRITE_VERSION: Int = 18
        const val READ_VERSION: Int = 19
        const val WAL: Byte = 2
        const val LEGACY: Byte = 1
        const val FLAGS: String = "COL_VIS_FLAGS"
        const val COLUMNS: String = "PRAGMA table_info(TABLE_SONGS)"
        const val PICKED: String = "COL_PATH, COL_TITLE, COL_ARTIST, COL_ALBUM, COL_DURATION, COL_NUM_PLAYED, COL_LAST_PLAYED"
        const val SONGS: String = "SELECT $PICKED, 0 FROM TABLE_SONGS"
        const val SONGS_WITH_FLAGS: String = "SELECT $PICKED, COL_VIS_FLAGS FROM TABLE_SONGS"
        val REQUIRED: Set<String> = setOf("COL_PATH", "COL_TITLE", "COL_ARTIST", "COL_ALBUM", "COL_DURATION", "COL_NUM_PLAYED", "COL_LAST_PLAYED")
        val HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()
    }
}
