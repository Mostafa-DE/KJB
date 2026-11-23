package app.lyrify.stats

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import app.lyrify.library.SongRef
import app.lyrify.player.SourceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where plays are kept: `plays.db`, one row per play. Safe from any thread; a failure is false or an empty answer,
 * never thrown, so counting goes on.
 */
interface PlayLogStore {
    /**
     * Writes [play] as it stands. A play of the same song and start is the same play: it keeps the most time heard, its
     * count once counted, and its id hint unless [play] has one. With [open], the play is kept as still going, with where
     * it stands, so a restart of the app resumes it; without, it is let go.
     */
    suspend fun put(play: LoggedPlay, open: OpenState? = null): Boolean

    /** The plays still going when the app last stopped, as they were last kept. */
    suspend fun openPlays(): List<StoredPlay>

    /**
     * Each song's totals over the plays that started from [from] (inclusive) until [until] (exclusive), either end open:
     * its plays that counted, the last of those, the time heard in all its plays, and the id hint of its latest play.
     */
    suspend fun totals(from: Long? = null, until: Long? = null): List<SongPlays>

    /** Every play in the log, the oldest first, for a backup (backup/); null when the log cannot be read. */
    suspend fun all(): List<LoggedPlay>?

    /**
     * The plays that started from [from] (wall-clock millis, inclusive), the short ones too, the oldest first, with all
     * they keep: what suggestions read as plays come (suggestions/), and Insights the plays written since they last read
     * (stats/Listening.kt, PlayStats); null when the log cannot be read.
     */
    suspend fun since(from: Long): List<LoggedPlay>? = all()?.filter { it.startedAt >= from }

    /**
     * A restore's [plays], each written as [put] writes it without an [OpenState], and its [interactions], all in one go
     * or none: a play of the same song and start is the same play, and an interaction of the same time and kind the same
     * one (kept as the phone has it, so words forgotten stay forgotten), so restoring a backup twice adds nothing. False
     * when none was written.
     */
    suspend fun putAll(plays: List<LoggedPlay>, interactions: List<Interaction> = emptyList()): Boolean

    /**
     * An import's Undo (backup/ImportPlan.kt): [plays] taken out of the log, each by its song and start, all in one go
     * or none. False when none was taken out.
     */
    suspend fun removeAll(plays: List<LoggedPlay>): Boolean

    /** Notes [interaction] (stats/Interactions.kt): one of the same time and kind is the same one, which it replaces. */
    suspend fun note(interaction: Interaction): Boolean

    /**
     * The interactions from [from] (inclusive) until [until] (exclusive), either end open, the oldest first; null when
     * the log cannot be read.
     */
    suspend fun interactions(from: Long? = null, until: Long? = null): List<Interaction>?

    /** Forgets the words of the searches made at [times], the searches kept, all in one go or none. */
    suspend fun forget(times: List<Long>): Boolean
}

/** Where a play still going stands: the song's length, its position, and the parts of it heard. */
data class OpenState(val durationMs: Long, val positionMs: Long, val spans: List<HeardSpan>)

/** A play kept as still going: its row so far and where it stood. */
data class StoredPlay(val play: LoggedPlay, val state: OpenState)

/** [spans] as `plays.db` keeps them: "0-5000,8000-12000". */
fun spansText(spans: List<HeardSpan>): String = spans.joinToString(",") { "${it.startMs}-${it.endMs}" }

/** The parts heard that [text] holds; a damaged part (not two numbers from 0 up, the second past the first) is dropped. */
fun spansOf(text: String?): List<HeardSpan> = text.orEmpty().split(',').mapNotNull { part ->
    val ends = part.trim().split('-')
    val start = ends.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
    val end = ends.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
    HeardSpan(start, end).takeIf { ends.size == 2 && start >= 0 && end > start }
}

/**
 * A kept play as a row of [PlaysSchema.OPEN_PLAYS] has it, read as leniently as [songPlaysOf]: a value missing or below
 * 0 is 0, a damaged part heard is dropped, real minutes missing none (a play from before they were kept). A row without
 * a song or a start is dropped.
 */
@Suppress("LongParameterList")
fun storedPlayOf(path: String?, startedAt: Long?, mediaId: Long?, heardMs: Long?, counted: Long?, durationMs: Long?, positionMs: Long?, spans: String?, clockMs: Long? = null): StoredPlay? {
    if (path.isNullOrBlank() || startedAt == null || startedAt <= 0) return null
    return StoredPlay(
        LoggedPlay(SongRef(path, mediaId?.takeIf { it > 0 }), startedAt, (heardMs ?: 0).coerceAtLeast(0), (counted ?: 0) != 0L, clockMs = clockMs?.coerceAtLeast(0)),
        OpenState((durationMs ?: 0).coerceAtLeast(0), (positionMs ?: 0).coerceAtLeast(0), spansOf(spans)),
    )
}

/** A row of `plays.db` as a query gives it: a column's value by its name, null when it is NULL or of another kind. */
interface PlayRow {
    fun text(column: String): String?

    fun number(column: String): Long?
}

/**
 * A play as a row of [PlaysSchema.ALL] has it, read as leniently as [storedPlayOf]: a code this version does not know is
 * UNKNOWN (a kind of list: none), a time-zone offset out of range none; a row without how it began has no
 * [LoggedPlay.start], one without how or where it ended no [LoggedPlay.end]. A row without a song or a start is dropped.
 */
fun loggedPlayOf(row: PlayRow): LoggedPlay? {
    val play = storedPlayOf(row.text("path"), row.number("started_at"), row.number("media_id"), row.number("heard_ms"), row.number("counted"), null, null, null, row.number("clock_ms"))?.play
        ?: return null
    return play.copy(
        start = playStartOf(row.text("started_by"), row.text("source"), row.number("shuffled")?.let { it != 0L }, row.text("song_key")),
        zoneOffsetMinutes = zoneOffsetOf(row.number("zone_offset_min")),
        end = playEndedOf(row.text("ended_how"), row.number("ended_at_ms")),
        imported = (row.number("imported") ?: 0) != 0L,
    )
}

/**
 * How a play began as `plays.db` or a backup keeps it, read leniently: none without how ([by]); a code this version
 * does not know is [StartedBy.UNKNOWN], a kind of list it does not know none, Shuffle unknown off.
 */
fun playStartOf(by: String?, source: String?, shuffled: Boolean?, songKey: String?): PlayStart? =
    by?.let { PlayStart(enumOf<StartedBy>(it) ?: StartedBy.UNKNOWN, enumOf<SourceKind>(source), shuffled ?: false, songKey) }

/** How a play ended as kept, read leniently: none without how or where; a code unknown here is [PlayEnd.UNKNOWN], a place below 0 the start. */
fun playEndedOf(how: String?, atMs: Long?): PlayEnded? =
    if (how == null || atMs == null) null else PlayEnded(enumOf<PlayEnd>(how) ?: PlayEnd.UNKNOWN, atMs.coerceAtLeast(0))

/** A time-zone offset as kept, in minutes; none past the farthest a zone is from UTC (18 hours). */
fun zoneOffsetOf(minutes: Long?): Int? = minutes?.takeIf { it in -MAX_ZONE_OFFSET_MINUTES..MAX_ZONE_OFFSET_MINUTES }?.toInt()

private const val MAX_ZONE_OFFSET_MINUTES: Long = 18 * 60

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
     * Version 2: the plays still going ([StoredPlay]), each beside its row in the log.
     * Version 3: what each play keeps since the log keeps more ([LoggedPlay.start], [LoggedPlay.zoneOffsetMinutes],
     * [LoggedPlay.end]), codes by name in lower case ([codeOf]); the plays from before keep NULL there. And `imported`:
     * the plays an import made are marked, found by their shape (nothing heard and counted, which a play heard here never
     * is), so a file 3.1.0 kept after a downgrade gets the imports it made marked on the next upgrade too.
     * Version 4: each play's real minutes ([LoggedPlay.clockMs], Insights, KJB 3.2); the plays from before keep NULL.
     * Version 5: the interaction log ([Interaction]), one row per time and kind, codes as in version 3.
     * Version 6: Huddle's history (huddle/HuddleStore.kt, HuddleLog): every card between the user and their friends, both
     * ways, one row per message id with the card as Huddle's JSON, and friends' plays, one row each. Never in the plays
     * section of a backup: only in Huddle's, sealed with its passphrase.
     */
    val STEPS: List<List<String>> = listOf(
        listOf(
            "CREATE TABLE IF NOT EXISTS plays (path TEXT NOT NULL, started_at INTEGER NOT NULL, media_id INTEGER, " +
                "heard_ms INTEGER NOT NULL DEFAULT 0, counted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (path, started_at)) WITHOUT ROWID",
            "CREATE INDEX IF NOT EXISTS plays_by_start ON plays (started_at)",
        ),
        listOf(
            "CREATE TABLE IF NOT EXISTS open_plays (path TEXT NOT NULL, started_at INTEGER NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "position_ms INTEGER NOT NULL DEFAULT 0, spans TEXT NOT NULL DEFAULT '', PRIMARY KEY (path, started_at)) WITHOUT ROWID",
        ),
        listOf(
            "ALTER TABLE plays ADD COLUMN started_by TEXT",
            "ALTER TABLE plays ADD COLUMN source TEXT",
            "ALTER TABLE plays ADD COLUMN shuffled INTEGER",
            "ALTER TABLE plays ADD COLUMN zone_offset_min INTEGER",
            "ALTER TABLE plays ADD COLUMN song_key TEXT",
            "ALTER TABLE plays ADD COLUMN ended_how TEXT",
            "ALTER TABLE plays ADD COLUMN ended_at_ms INTEGER",
            "ALTER TABLE plays ADD COLUMN imported INTEGER NOT NULL DEFAULT 0",
            "UPDATE plays SET imported = 1 WHERE heard_ms = 0 AND counted != 0",
        ),
        listOf("ALTER TABLE plays ADD COLUMN clock_ms INTEGER"),
        listOf(
            "CREATE TABLE IF NOT EXISTS interactions (at INTEGER NOT NULL, kind TEXT NOT NULL, zone_offset_min INTEGER, words TEXT, " +
                "target TEXT, target_key TEXT, PRIMARY KEY (at, kind)) WITHOUT ROWID",
        ),
        listOf(
            "CREATE TABLE IF NOT EXISTS huddle_cards (id TEXT NOT NULL PRIMARY KEY, friend TEXT NOT NULL, sent INTEGER NOT NULL, at INTEGER NOT NULL, " +
                "card TEXT NOT NULL, opened INTEGER NOT NULL DEFAULT 0, dismissed INTEGER NOT NULL DEFAULT 0, delivered INTEGER NOT NULL DEFAULT 1) WITHOUT ROWID",
            "CREATE INDEX IF NOT EXISTS huddle_cards_by_friend ON huddle_cards (friend, at)",
            "CREATE INDEX IF NOT EXISTS huddle_cards_by_time ON huddle_cards (sent, at)",
            "CREATE TABLE IF NOT EXISTS friend_plays (friend TEXT NOT NULL, at INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT, album TEXT, " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, favourite INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS friend_plays_by_friend ON friend_plays (friend, at)",
            "CREATE INDEX IF NOT EXISTS friend_plays_by_time ON friend_plays (at)",
        ),
    )

    val VERSION: Int = STEPS.size

    /** The statements that take the file from version [from] to [to]; none to go down. */
    fun upgrade(from: Int, to: Int): List<String> {
        val start = from.coerceIn(0, STEPS.size)
        val end = to.coerceIn(0, STEPS.size)
        return if (start < end) STEPS.subList(start, end).flatten() else emptyList()
    }

    /**
     * [upgrade]'s statements for a file whose tables have the columns [has] gives (none for a table not there), without
     * the columns a step adds that a table has already: SQLite has no ADD COLUMN IF NOT EXISTS, and a file a newer KJB
     * left goes through the same steps again after a downgrade.
     */
    fun upgrade(from: Int, to: Int, has: (table: String) -> Set<String>): List<String> {
        val columns = HashMap<String, Set<String>>()
        return upgrade(from, to).filterNot { sql ->
            val (table, column) = ADD_COLUMN.matchEntire(sql)?.destructured ?: return@filterNot false
            column in columns.getOrPut(table) { has(table) }
        }
    }

    /** A step's column: "ALTER TABLE plays ADD COLUMN song_key TEXT", its table and its name. */
    private val ADD_COLUMN = Regex("""ALTER TABLE (\w+) ADD COLUMN (\w+) .+""")

    /** [play]'s values in [PUT]'s order. */
    fun putArgs(play: LoggedPlay): Array<Any?> = arrayOf(
        play.song.path,
        play.startedAt,
        play.song.mediaStoreId,
        play.heardMs,
        flag(play.counted),
        play.start?.by?.let(::codeOf),
        play.start?.source?.let(::codeOf),
        play.start?.shuffled?.let(::flag),
        play.zoneOffsetMinutes?.toLong(),
        play.start?.songKey,
        play.end?.how?.let(::codeOf),
        play.end?.atMs,
        flag(play.imported),
        play.clockMs,
    )

    private fun flag(on: Boolean): Long = if (on) 1L else 0L

    /**
     * The same play again keeps the most heard, its count once counted, its id hint unless a new one is given, how it
     * began and its time zone as first written (a play that goes on after a restart began as it began; a part the first
     * write lacked, such as the song's key before the library was read, filled in by a later one), how it ended as last
     * written, its import mark once marked, and the most real minutes written (none until a write has them: NULL is no
     * value to SQLite's max()). Its values: [putArgs].
     */
    const val PUT: String = "INSERT INTO plays (path, started_at, media_id, heard_ms, counted, started_by, source, shuffled, zone_offset_min, song_key, " +
        "ended_how, ended_at_ms, imported, clock_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT (path, started_at) DO UPDATE SET media_id = coalesce(excluded.media_id, media_id), " +
        "heard_ms = max(heard_ms, excluded.heard_ms), counted = max(counted, excluded.counted), " +
        "started_by = coalesce(started_by, excluded.started_by), source = coalesce(source, excluded.source), " +
        "shuffled = coalesce(shuffled, excluded.shuffled), zone_offset_min = coalesce(zone_offset_min, excluded.zone_offset_min), " +
        "song_key = coalesce(song_key, excluded.song_key), ended_how = coalesce(excluded.ended_how, ended_how), " +
        "ended_at_ms = coalesce(excluded.ended_at_ms, ended_at_ms), imported = max(imported, excluded.imported), " +
        "clock_ms = coalesce(max(clock_ms, excluded.clock_ms), clock_ms, excluded.clock_ms)"

    /** Every play, the short ones too, the oldest first, with all it keeps (a backup; [loggedPlayOf] reads them). */
    const val ALL: String = "SELECT path, started_at, media_id, heard_ms, counted, started_by, source, shuffled, zone_offset_min, song_key, " +
        "ended_how, ended_at_ms, imported, clock_ms FROM plays ORDER BY started_at, path"

    /** [ALL] from a start on (inclusive), by plays_by_start: suggestions read only the plays since they last read. */
    const val SINCE: String = "SELECT path, started_at, media_id, heard_ms, counted, started_by, source, shuffled, zone_offset_min, song_key, " +
        "ended_how, ended_at_ms, imported, clock_ms FROM plays WHERE started_at >= CAST(? AS INTEGER) ORDER BY started_at, path"

    const val KEEP_OPEN: String = "INSERT OR REPLACE INTO open_plays (path, started_at, duration_ms, position_ms, spans) VALUES (?, ?, ?, ?, ?)"

    const val LET_GO: String = "DELETE FROM open_plays WHERE path = ? AND started_at = ?"

    /** An import's Undo: its play taken out of the log ([PlayLogStore.removeAll]). */
    const val REMOVE: String = "DELETE FROM plays WHERE path = ? AND started_at = ?"

    /** [interaction]'s values in [NOTE]'s order. */
    fun noteArgs(interaction: Interaction): Array<Any?> = arrayOf(
        interaction.at,
        codeOf(interaction.kind),
        interaction.zoneOffsetMinutes?.toLong(),
        interaction.words,
        interaction.target?.kind?.let(::codeOf),
        interaction.target?.key,
    )

    /** An interaction, replacing the one of the same time and kind (a search's result tapped after it). Its values: [noteArgs]. */
    const val NOTE: String = "INSERT OR REPLACE INTO interactions (at, kind, zone_offset_min, words, target, target_key) VALUES (?, ?, ?, ?, ?, ?)"

    /** A restore's interaction: one of the same time and kind there already stays as the phone has it. Its values: [noteArgs]. */
    const val RESTORE_INTERACTION: String = "INSERT OR IGNORE INTO interactions (at, kind, zone_offset_min, words, target, target_key) VALUES (?, ?, ?, ?, ?, ?)"

    /** The interactions between two bounds, the oldest first ([interactionOf] reads them). */
    const val INTERACTIONS: String = "SELECT at, kind, zone_offset_min, words, target, target_key FROM interactions " +
        "WHERE at >= CAST(? AS INTEGER) AND at < CAST(? AS INTEGER) ORDER BY at, kind"

    /** A search's words forgotten (Recent searches' "×"), the search kept. */
    const val FORGET: String = "UPDATE interactions SET words = NULL WHERE at = ? AND kind = 'search'"

    /** A Huddle card, kept once: one of the same message id there already stays as it is (opened, delivered). */
    const val KEEP_CARD: String = "INSERT OR IGNORE INTO huddle_cards (id, friend, sent, at, card, opened, dismissed, delivered) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

    /** A friend's play, kept once: one with all the same values there already is the same play, sent again. */
    const val KEEP_FRIEND_PLAY: String = "INSERT INTO friend_plays (friend, at, title, artist, album, duration_ms, favourite) SELECT ?1, ?2, ?3, ?4, ?5, ?6, ?7 " +
        "WHERE NOT EXISTS (SELECT 1 FROM friend_plays WHERE friend = ?1 AND at = ?2 AND title = ?3 AND artist IS ?4 AND album IS ?5 AND duration_ms = ?6 AND favourite = ?7)"

    /** Friends' plays from before a time let go. */
    const val FRIEND_PLAYS_BEFORE: String = "DELETE FROM friend_plays WHERE at < ?"

    /** A friend's plays past the newest so many ([friend], then how many) let go. */
    const val FRIEND_PLAYS_PAST: String = "DELETE FROM friend_plays WHERE rowid IN " +
        "(SELECT rowid FROM friend_plays WHERE friend = ?1 ORDER BY at DESC, rowid DESC LIMIT -1 OFFSET ?2)"

    /** A received card opened, and put away too when asked (1); a put away one stays so. */
    const val CARD_OPENED: String = "UPDATE huddle_cards SET opened = 1, dismissed = max(dismissed, ?) WHERE id = ? AND sent = 0"

    const val FORGET_FRIEND_CARDS: String = "DELETE FROM huddle_cards WHERE friend = ?"

    const val FORGET_FRIEND_PLAYS: String = "DELETE FROM friend_plays WHERE friend = ?"

    private const val CARD_COLUMNS: String = "SELECT id, friend, sent, at, card, opened, dismissed, delivered FROM huddle_cards"

    /** One card by its message id. */
    const val CARD: String = "$CARD_COLUMNS WHERE id = ?"

    /** A friend's cards, both ways, the newest first: a page, by how many and from which. */
    const val THREAD: String = "$CARD_COLUMNS WHERE friend = ? ORDER BY at DESC, id DESC LIMIT CAST(? AS INTEGER) OFFSET CAST(? AS INTEGER)"

    /** The cards friends sent, the newest first: a page. */
    const val RECEIVED: String = "$CARD_COLUMNS WHERE sent = 0 ORDER BY at DESC, id DESC LIMIT CAST(? AS INTEGER) OFFSET CAST(? AS INTEGER)"

    /** Every card, both ways, the newest first: a page (a backup). */
    const val CARDS: String = "$CARD_COLUMNS ORDER BY at DESC, id DESC LIMIT CAST(? AS INTEGER) OFFSET CAST(? AS INTEGER)"

    private const val FRIEND_PLAY_COLUMNS: String = "SELECT friend, at, title, artist, album, duration_ms, favourite FROM friend_plays"

    /** A friend's plays, the newest first: a page. */
    const val FRIEND_PLAYS: String = "$FRIEND_PLAY_COLUMNS WHERE friend = ? ORDER BY at DESC, rowid DESC LIMIT CAST(? AS INTEGER) OFFSET CAST(? AS INTEGER)"

    /** Every friend's plays, the newest first: a page (a backup). */
    const val ALL_FRIEND_PLAYS: String = "$FRIEND_PLAY_COLUMNS ORDER BY at DESC, rowid DESC LIMIT CAST(? AS INTEGER) OFFSET CAST(? AS INTEGER)"

    /** Every friend's plays from a start on (inclusive), the oldest first (Suggestions). */
    const val FRIEND_PLAYS_SINCE: String = "$FRIEND_PLAY_COLUMNS WHERE at >= CAST(? AS INTEGER) ORDER BY at, rowid"

    /** Per play still going: path, start, id hint, time heard, counted, length, position, parts heard, real minutes ([storedPlayOf]). */
    const val OPEN_PLAYS: String = "SELECT o.path, o.started_at, p.media_id, p.heard_ms, p.counted, o.duration_ms, o.position_ms, o.spans, p.clock_ms AS clock_ms " +
        "FROM open_plays AS o JOIN plays AS p ON p.path = o.path AND p.started_at = o.started_at"

    /**
     * Per song, between two bounds: path, latest id hint, plays counted, the last of those, time heard ([songPlaysOf]).
     * Without the short plays ([LoggedPlay.isShort]), which the log keeps only since it keeps how plays end: every list,
     * count and total read from here reads as it did before.
     */
    const val TOTALS: String = "SELECT path, " +
        "(SELECT media_id FROM plays AS latest WHERE latest.path = plays.path AND (latest.counted != 0 OR latest.heard_ms >= ${PlayCounter.MIN_LOGGED_MS}) " +
        "ORDER BY started_at DESC LIMIT 1), " +
        "COUNT(CASE WHEN counted != 0 THEN 1 END), MAX(CASE WHEN counted != 0 THEN started_at END), SUM(heard_ms) " +
        "FROM plays WHERE started_at >= CAST(? AS INTEGER) AND started_at < CAST(? AS INTEGER) AND (counted != 0 OR heard_ms >= ${PlayCounter.MIN_LOGGED_MS}) GROUP BY path"
}

/**
 * `plays.db` in app-private storage ([name] null: in memory, for device tests). A file SQLite can't read is moved aside
 * ([keepAside]) and a new one started, never deleted, so the plays can still be rescued.
 */
class PlaysDb(context: Context, name: String? = PlaysSchema.NAME, clock: () -> Long = System::currentTimeMillis, log: (String) -> Unit = {}) :
    SQLiteOpenHelper(context, name, null, PlaysSchema.VERSION, KeepAside(clock, log)) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = PlaysSchema.upgrade(0, PlaysSchema.VERSION) { columns(db, it) }.forEach(db::execSQL)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = PlaysSchema.upgrade(oldVersion, newVersion) { columns(db, it) }.forEach(db::execSQL)

    /** A newer KJB's file: kept as it is, since its steps only added to what this version reads. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** [table]'s columns now; none when it is not there. */
    private fun columns(db: SQLiteDatabase, table: String): Set<String> = db.rawQuery("PRAGMA table_info($table)", null).use { rows ->
        val name = rows.getColumnIndexOrThrow("name")
        buildSet { while (rows.moveToNext()) add(rows.getString(name)) }
    }

    private class KeepAside(private val clock: () -> Long, private val log: (String) -> Unit) : DatabaseErrorHandler {
        override fun onCorruption(db: SQLiteDatabase) {
            // A file that is no database at all is found while SQLiteDatabase.open() is still opening it: the database is
            // not open yet, and open() starts again on a new file once this returns. Closing it then dropped its last
            // reference, so the helper's first query threw IllegalStateException and the first read or write failed.
            if (db.isOpen) runCatching { db.close() }
            val file = File(db.path)
            if (!file.exists()) return
            val kept = keepAside(file, clock()) != null
            log(if (kept) "plays: plays.db unreadable, kept aside" else "plays: plays.db unreadable, could not be kept aside")
        }
    }
}

/**
 * Moves [file], an unreadable `plays.db`, aside with its journal files as `plays-unreadable-<now>.db` (and `-wal`, `-shm`,
 * `-journal`) beside it, so the next open starts a new file. Never deletes, and never overwrites: when a name is taken,
 * the next millisecond's is used. Null when there is no file, or it could not be moved.
 */
fun keepAside(file: File, now: Long): File? {
    if (!file.exists()) return null
    val ends = listOf("", "-wal", "-shm", "-journal")
    val dir = file.absoluteFile.parentFile
    val aside = generateSequence(now) { it + 1 }.map { File(dir, "plays-unreadable-$it.db") }.first { taken -> ends.none { File(taken.path + it).exists() } }
    if (!file.renameTo(aside)) return null
    ends.drop(1).forEach { end -> File(file.path + end).takeIf(File::exists)?.renameTo(File(aside.path + end)) }
    return aside
}

/**
 * [next], telling [changed] the start of each play written, or null for many at once (a restore, an import or its
 * Undo), once it is written: so Insights read again only what changed ([PlayStats.changed]).
 */
class WatchedPlayLog(private val next: PlayLogStore, private val changed: (Long?) -> Unit) : PlayLogStore by next {
    override suspend fun put(play: LoggedPlay, open: OpenState?): Boolean = next.put(play, open).also { if (it) changed(play.startedAt) }

    override suspend fun putAll(plays: List<LoggedPlay>, interactions: List<Interaction>): Boolean = next.putAll(plays, interactions).also { if (it) changed(null) }

    override suspend fun removeAll(plays: List<LoggedPlay>): Boolean = next.removeAll(plays).also { if (it) changed(null) }
}

/** [PlayLogStore] over [db], on [io]. The log gets exception names only. */
class SqlPlayLog(private val db: SQLiteOpenHelper, private val io: CoroutineDispatcher = Dispatchers.IO, private val log: (String) -> Unit = {}) : PlayLogStore {
    override suspend fun put(play: LoggedPlay, open: OpenState?): Boolean = withContext(io) {
        try {
            val path = play.song.path
            db.writableDatabase.transaction {
                execSQL(PlaysSchema.PUT, PlaysSchema.putArgs(play))
                if (open == null) {
                    execSQL(PlaysSchema.LET_GO, arrayOf<Any?>(path, play.startedAt))
                } else {
                    execSQL(PlaysSchema.KEEP_OPEN, arrayOf<Any?>(path, play.startedAt, open.durationMs, open.positionMs, spansText(open.spans)))
                }
            }
            true
        } catch (e: Exception) {
            log("plays: not written (${e.javaClass.simpleName})")
            false
        }
    }

    override suspend fun openPlays(): List<StoredPlay> = withContext(io) {
        try {
            db.readableDatabase.rawQuery(PlaysSchema.OPEN_PLAYS, null).use { rows ->
                val row = CursorRow(rows)
                buildList {
                    while (rows.moveToNext()) {
                        storedPlayOf(rows.text(0), rows.number(1), rows.number(2), rows.number(3), rows.number(4), rows.number(5), rows.number(6), rows.text(7), row.number("clock_ms"))?.let(::add)
                    }
                }
            }
        } catch (e: Exception) {
            log("plays: open plays not read (${e.javaClass.simpleName})")
            emptyList()
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

    override suspend fun all(): List<LoggedPlay>? = withContext(io) {
        try {
            db.readableDatabase.rawQuery(PlaysSchema.ALL, null).use { rows ->
                val row = CursorRow(rows)
                buildList {
                    while (rows.moveToNext()) loggedPlayOf(row)?.let(::add)
                }
            }
        } catch (e: Exception) {
            log("plays: not read for a backup (${e.javaClass.simpleName})")
            null
        }
    }

    override suspend fun since(from: Long): List<LoggedPlay>? = withContext(io) {
        try {
            db.readableDatabase.rawQuery(PlaysSchema.SINCE, arrayOf(from.toString())).use { rows ->
                val row = CursorRow(rows)
                buildList {
                    while (rows.moveToNext()) loggedPlayOf(row)?.let(::add)
                }
            }
        } catch (e: Exception) {
            log("plays: not read for suggestions (${e.javaClass.simpleName})")
            null
        }
    }

    override suspend fun putAll(plays: List<LoggedPlay>, interactions: List<Interaction>): Boolean = withContext(io) {
        try {
            db.writableDatabase.transaction {
                plays.forEach { play -> execSQL(PlaysSchema.PUT, PlaysSchema.putArgs(play)) }
                interactions.forEach { interaction -> execSQL(PlaysSchema.RESTORE_INTERACTION, PlaysSchema.noteArgs(interaction)) }
            }
            true
        } catch (e: Exception) {
            log("plays: ${plays.size} not restored, with ${interactions.size} interactions (${e.javaClass.simpleName})")
            false
        }
    }

    override suspend fun removeAll(plays: List<LoggedPlay>): Boolean = withContext(io) {
        try {
            db.writableDatabase.transaction {
                plays.forEach { play ->
                    val key = arrayOf<Any?>(play.song.path, play.startedAt)
                    execSQL(PlaysSchema.LET_GO, key)
                    execSQL(PlaysSchema.REMOVE, key)
                }
            }
            true
        } catch (e: Exception) {
            log("plays: ${plays.size} not removed (${e.javaClass.simpleName})")
            false
        }
    }

    override suspend fun note(interaction: Interaction): Boolean = withContext(io) {
        try {
            db.writableDatabase.execSQL(PlaysSchema.NOTE, PlaysSchema.noteArgs(interaction))
            true
        } catch (e: Exception) {
            log("interactions: not written (${e.javaClass.simpleName})")
            false
        }
    }

    override suspend fun interactions(from: Long?, until: Long?): List<Interaction>? = withContext(io) {
        try {
            val bounds = arrayOf((from ?: Long.MIN_VALUE).toString(), (until ?: Long.MAX_VALUE).toString())
            db.readableDatabase.rawQuery(PlaysSchema.INTERACTIONS, bounds).use { rows ->
                val row = CursorRow(rows)
                buildList {
                    while (rows.moveToNext()) interactionOf(row)?.let(::add)
                }
            }
        } catch (e: Exception) {
            log("interactions: not read (${e.javaClass.simpleName})")
            null
        }
    }

    override suspend fun forget(times: List<Long>): Boolean = withContext(io) {
        try {
            db.writableDatabase.transaction {
                times.forEach { at -> execSQL(PlaysSchema.FORGET, arrayOf<Any?>(at)) }
            }
            true
        } catch (e: Exception) {
            log("interactions: ${times.size} searches' words not forgotten (${e.javaClass.simpleName})")
            false
        }
    }

    /** The row [rows] is at, its columns by name; a column the query lacks is none. Huddle's history reads its rows so too. */
    internal class CursorRow(private val rows: Cursor) : PlayRow {
        override fun text(column: String): String? = rows.getColumnIndex(column).takeIf { it >= 0 }?.let(rows::text)

        override fun number(column: String): Long? = rows.getColumnIndex(column).takeIf { it >= 0 }?.let(rows::number)
    }
}

private fun Cursor.text(column: Int): String? = if (isNull(column)) null else getString(column)

/** A number, whole or not, or null: a value of another kind (text in a damaged row) reads as none. */
private fun Cursor.number(column: Int): Long? = when (getType(column)) {
    Cursor.FIELD_TYPE_INTEGER -> getLong(column)
    Cursor.FIELD_TYPE_FLOAT -> getDouble(column).toLong()
    else -> null
}
