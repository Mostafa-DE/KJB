package app.lyrify.stats

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.lyrify.library.SongRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where plays are kept: `plays.db`, one row per play. Safe from any thread; a failure is false or an empty answer,
 * never thrown, so counting goes on.
 */
interface PlayLogStore {
    /** Keeps [play], replacing the one of the same song and start (a play is written when it counts, then when it ends). */
    suspend fun put(play: LoggedPlay): Boolean

    /**
     * Each song's totals over the plays that started from [from] (inclusive) until [until] (exclusive), either end open:
     * its plays that counted, the last of those, the time heard in all its plays, and the id hint of its latest play.
     */
    suspend fun totals(from: Long? = null, until: Long? = null): List<SongPlays>
}

/**
 * A song's totals as a row of [PlaysSchema.TOTALS] has them, read leniently, because the file may come from an older
 * KJB or a damaged backup: a missing value is none, a count or time below 0 is 0, a last play at 0 or before (or
 * without a play that counted) is none, a MediaStore id below 1 is no hint. A row without a song is dropped.
 */
fun songPlaysOf(path: String?, mediaId: Long?, plays: Long?, lastPlayedAt: Long?, heardMs: Long?): SongPlays? {
    if (path.isNullOrBlank()) return null
    val count = (plays ?: 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return SongPlays(
        song = SongRef(path, mediaId?.takeIf { it > 0 }),
        count = count,
        lastPlayedAt = lastPlayedAt?.takeIf { it > 0 && count > 0 },
        heardMs = (heardMs ?: 0).coerceAtLeast(0),
    )
}

/**
 * `plays.db`'s schema: [STEPS] take the file from each version to the next, so a new install runs them all and an
 * upgrade the ones after its version. A step only adds (a play is never lost), and runs safely again: a downgrade keeps
 * a newer file as it is, and the next upgrade runs the newer steps once more.
 */
object PlaysSchema {
    const val NAME: String = "plays.db"

    /**
     * Version 1: the play log. A play is its song's path and its start (wall-clock millis); [LoggedPlay]'s other fields,
     * the song's MediaStore id as a hint, and `counted` as 0 or 1. Each song's totals are worked out from it.
     */
    val STEPS: List<List<String>> = listOf(
        listOf(
            "CREATE TABLE IF NOT EXISTS plays (path TEXT NOT NULL, started_at INTEGER NOT NULL, media_id INTEGER, " +
                "heard_ms INTEGER NOT NULL DEFAULT 0, counted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (path, started_at)) WITHOUT ROWID",
            "CREATE INDEX IF NOT EXISTS plays_by_start ON plays (started_at)",
        ),
    )

    val VERSION: Int = STEPS.size

    /** The statements that take the file from version [from] to [to]; none to go down. */
    fun upgrade(from: Int, to: Int): List<String> {
        val start = from.coerceIn(0, STEPS.size)
        val end = to.coerceIn(0, STEPS.size)
        return if (start < end) STEPS.subList(start, end).flatten() else emptyList()
    }

    const val PUT: String = "INSERT OR REPLACE INTO plays (path, started_at, media_id, heard_ms, counted) VALUES (?, ?, ?, ?, ?)"

    /** Per song, between two bounds: path, latest id hint, plays counted, the last of those, time heard ([songPlaysOf]). */
    const val TOTALS: String = "SELECT path, " +
        "(SELECT media_id FROM plays AS latest WHERE latest.path = plays.path ORDER BY started_at DESC LIMIT 1), " +
        "COUNT(CASE WHEN counted != 0 THEN 1 END), MAX(CASE WHEN counted != 0 THEN started_at END), SUM(heard_ms) " +
        "FROM plays WHERE started_at >= CAST(? AS INTEGER) AND started_at < CAST(? AS INTEGER) GROUP BY path"
}

/**
 * `plays.db` in app-private storage ([name] null: in memory, for device tests). A file SQLite can't read is moved aside
 * as `plays-unreadable-<millis>.db` and a new one started, never deleted, so the plays can still be rescued.
 */
class PlaysDb(context: Context, name: String? = PlaysSchema.NAME, clock: () -> Long = System::currentTimeMillis, log: (String) -> Unit = {}) :
    SQLiteOpenHelper(context, name, null, PlaysSchema.VERSION, KeepAside(clock, log)) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = PlaysSchema.upgrade(0, PlaysSchema.VERSION).forEach(db::execSQL)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = PlaysSchema.upgrade(oldVersion, newVersion).forEach(db::execSQL)

    /** A newer KJB's file: kept as it is, since its steps only added to what this version reads. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private class KeepAside(private val clock: () -> Long, private val log: (String) -> Unit) : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase) {
            val path = db.path
            runCatching { db.close() }
            val file = File(path)
            if (!file.exists()) return
            val aside = "plays-unreadable-${clock()}.db"
            listOf("", "-wal", "-shm", "-journal").forEach { end -> File(path + end).takeIf(File::exists)?.renameTo(File(file.parentFile, aside + end)) }
            log("plays: plays.db unreadable, kept aside")
        }
    }
}

/** [PlayLogStore] over [db], on [io]. The log gets exception names only. */
class SqlPlayLog(private val db: SQLiteOpenHelper, private val io: CoroutineDispatcher = Dispatchers.IO, private val log: (String) -> Unit = {}) : PlayLogStore {
    override suspend fun put(play: LoggedPlay): Boolean = withContext(io) {
        try {
            val args = arrayOf<Any?>(play.song.path, play.startedAt, play.song.mediaStoreId, play.heardMs, if (play.counted) 1L else 0L)
            db.writableDatabase.execSQL(PlaysSchema.PUT, args)
            true
        } catch (e: Exception) {
            log("plays: not written (${e.javaClass.simpleName})")
            false
        }
    }

    override suspend fun totals(from: Long?, until: Long?): List<SongPlays> = withContext(io) {
        try {
            val bounds = arrayOf((from ?: Long.MIN_VALUE).toString(), (until ?: Long.MAX_VALUE).toString())
            db.readableDatabase.rawQuery(PlaysSchema.TOTALS, bounds).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        songPlaysOf(rows.text(0), rows.number(1), rows.number(2), rows.number(3), rows.number(4))?.let(::add)
                    }
                }
            }
        } catch (e: Exception) {
            log("plays: not read (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    private fun Cursor.text(column: Int): String? = if (isNull(column)) null else getString(column)

    /** A number, whole or not, or null: a value of another kind (text in a damaged row) reads as none. */
    private fun Cursor.number(column: Int): Long? = when (getType(column)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(column)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(column).toLong()
        else -> null
    }
}
