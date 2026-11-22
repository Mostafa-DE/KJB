package app.lyrify.stats

import app.lyrify.library.SongRef
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/** The play as 3.1.0 kept it: its song, start, time heard and count, and nothing more, for tests of those alone. */
fun LoggedPlay.as310(): LoggedPlay = LoggedPlay(song, startedAt, heardMs, counted)

fun List<LoggedPlay>.as310(): List<LoggedPlay> = map(LoggedPlay::as310)

/** The kept plays as 3.1.0 kept them ([as310]). */
@JvmName("storedAs310")
fun List<StoredPlay>.as310(): List<StoredPlay> = map { it.copy(play = it.play.as310()) }

/**
 * `plays.db` made up, in memory, with its rules (PlaysDbDeviceTest checks the real one against the same, and
 * PlaysSqlTest its SQL on the laptop): a play written again is the one of the same song and start, and keeps the most
 * time heard, its count once counted, its id hint unless a newer one is given, how it began and its time zone as first
 * written (a part the first write lacked filled in by a later one), how it ended as last written, its import mark
 * once marked, and the most real minutes written (none until a write has them); with an [OpenState] it is kept as still going,
 * without one it is let go. A song's totals count its plays that counted, the last of those, and the time heard in all
 * its plays, the short ones ([LoggedPlay.isShort]) left out; its id hint is its latest play's of those. The plays heard
 * here leave out an import's and the short ones. [failing]: every write fails.
 */
class FakePlayLog(plays: List<LoggedPlay> = emptyList()) : PlayLogStore {
    val plays: MutableList<LoggedPlay> = plays.toMutableList()
    private val open = LinkedHashMap<Pair<String, Long>, OpenState>()
    var failing: Boolean = false

    override suspend fun put(play: LoggedPlay, open: OpenState?): Boolean {
        if (failing) return false
        val key = play.song.path to play.startedAt
        val old = plays.firstOrNull { it.song.path == play.song.path && it.startedAt == play.startedAt }
        val row = if (old == null) {
            play
        } else {
            LoggedPlay(
                SongRef(play.song.path, play.song.mediaStoreId ?: old.song.mediaStoreId),
                play.startedAt,
                maxOf(old.heardMs, play.heardMs),
                old.counted || play.counted,
                start = firstStart(old.start, play.start),
                zoneOffsetMinutes = old.zoneOffsetMinutes ?: play.zoneOffsetMinutes,
                end = play.end ?: old.end,
                imported = old.imported || play.imported,
                clockMs = listOfNotNull(old.clockMs, play.clockMs).maxOrNull(),
            )
        }
        plays.remove(old)
        plays += row
        if (open != null) this.open[key] = open else this.open.remove(key)
        return true
    }

    /** How a play began, as plays.db merges it: each part as first written, one the first write lacked filled in later. */
    private fun firstStart(old: PlayStart?, new: PlayStart?): PlayStart? =
        if (old == null || new == null) old ?: new else old.copy(source = old.source ?: new.source, songKey = old.songKey ?: new.songKey)

    override suspend fun all(): List<LoggedPlay>? = if (failing) null else plays.sortedBy { it.startedAt }

    override suspend fun listened(from: Long?, until: Long?): List<LoggedPlay> =
        plays.filter { !it.imported && !it.isShort && (from == null || it.startedAt >= from) && (until == null || it.startedAt < until) }
            .sortedWith(compareBy({ it.startedAt }, { it.song.path }))

    /** Each as [put] writes it, or none when [failing]. */
    override suspend fun putAll(plays: List<LoggedPlay>): Boolean = !failing && plays.all { put(it) }

    /** Each by its song and start, or none when [failing]. */
    override suspend fun removeAll(plays: List<LoggedPlay>): Boolean {
        if (failing) return false
        val gone = plays.map { it.song.path to it.startedAt }.toSet()
        this.plays.removeAll { (it.song.path to it.startedAt) in gone }
        gone.forEach(open::remove)
        return true
    }

    override suspend fun openPlays(): List<StoredPlay> = open.map { (key, state) ->
        StoredPlay(plays.first { it.song.path == key.first && it.startedAt == key.second }, state)
    }

    override suspend fun totals(from: Long?, until: Long?): List<SongPlays> {
        val logged = plays.filterNot { it.isShort }
        val hints = logged.groupBy { it.song.path }.mapValues { (_, its) -> its.maxBy { it.startedAt }.song.mediaStoreId }
        return logged.filter { (from == null || it.startedAt >= from) && (until == null || it.startedAt < until) }
            .groupBy { it.song.path }
            .map { (path, its) ->
                SongPlays(SongRef(path, hints[path]), its.count { it.counted }, its.filter { it.counted }.maxOfOrNull { it.startedAt }, its.sumOf { it.heardMs })
            }
    }
}

/**
 * `plays.db`'s own SQL ([PlaysSchema]) on the laptop's SQLite, in memory: what [SqlPlayLog] runs on the phone, the same
 * statements with the same values ([PlaysSchema.putArgs]) and rows read the same way ([loggedPlayOf], [songPlaysOf],
 * [storedPlayOf]). Made at [version] (3.1.0's file is version 2); [upgrade] runs the steps after it as PlaysDb does.
 */
class JdbcPlayLog(version: Int = PlaysSchema.VERSION) : PlayLogStore, AutoCloseable {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        upgrade(0, version)
    }

    /** PlaysDb's onCreate (from 0) and onUpgrade. */
    fun upgrade(from: Int, to: Int) = PlaysSchema.upgrade(from, to, ::columns).forEach { update(it) }

    fun columns(table: String): Set<String> = query("PRAGMA table_info($table)") { rows -> buildSet { while (rows.next()) add(rows.getString("name")) } }

    fun update(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { at, value -> statement.setObject(at + 1, value) }
            statement.executeUpdate()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): T = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { at, value -> statement.setObject(at + 1, value) }
        statement.executeQuery().use(read)
    }

    override suspend fun put(play: LoggedPlay, open: OpenState?): Boolean {
        transaction {
            update(PlaysSchema.PUT, *PlaysSchema.putArgs(play))
            if (open == null) {
                update(PlaysSchema.LET_GO, play.song.path, play.startedAt)
            } else {
                update(PlaysSchema.KEEP_OPEN, play.song.path, play.startedAt, open.durationMs, open.positionMs, spansText(open.spans))
            }
        }
        return true
    }

    override suspend fun openPlays(): List<StoredPlay> = query(PlaysSchema.OPEN_PLAYS) { rows ->
        buildList {
            while (rows.next()) {
                val row = ResultRow(rows)
                storedPlayOf(
                    rows.getString(1), row.at(2), row.at(3), row.at(4), row.at(5), row.at(6), row.at(7), rows.getString(8), row.number("clock_ms"),
                )?.let(::add)
            }
        }
    }

    override suspend fun totals(from: Long?, until: Long?): List<SongPlays> =
        query(PlaysSchema.TOTALS, (from ?: Long.MIN_VALUE).toString(), (until ?: Long.MAX_VALUE).toString()) { rows ->
            buildList {
                while (rows.next()) {
                    val row = ResultRow(rows)
                    songPlaysOf(rows.getString(1), row.at(2), row.at(3), row.at(4), row.at(5))?.let(::add)
                }
            }
        }

    override suspend fun all(): List<LoggedPlay> = query(PlaysSchema.ALL) { rows ->
        val row = ResultRow(rows)
        buildList { while (rows.next()) loggedPlayOf(row)?.let(::add) }
    }

    override suspend fun listened(from: Long?, until: Long?): List<LoggedPlay> =
        query(PlaysSchema.LISTENED, (from ?: Long.MIN_VALUE).toString(), (until ?: Long.MAX_VALUE).toString()) { rows ->
            val row = ResultRow(rows)
            buildList { while (rows.next()) loggedPlayOf(row)?.let(::add) }
        }

    override suspend fun putAll(plays: List<LoggedPlay>): Boolean {
        transaction { plays.forEach { update(PlaysSchema.PUT, *PlaysSchema.putArgs(it)) } }
        return true
    }

    override suspend fun removeAll(plays: List<LoggedPlay>): Boolean {
        transaction {
            plays.forEach { play ->
                update(PlaysSchema.LET_GO, play.song.path, play.startedAt)
                update(PlaysSchema.REMOVE, play.song.path, play.startedAt)
            }
        }
        return true
    }

    override fun close() = connection.close()

    private fun transaction(work: () -> Unit) {
        connection.autoCommit = false
        try {
            work()
            connection.commit()
        } catch (e: SQLException) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /** The row [rows] is at, as the phone's Cursor gives it: a column the query lacks, or a value of another kind, is none. */
    private class ResultRow(private val rows: ResultSet) : PlayRow {
        override fun text(column: String): String? = index(column)?.let(rows::getString)

        override fun number(column: String): Long? = index(column)?.let(::at)

        fun at(index: Int): Long? = when (val value = rows.getObject(index)) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            else -> null
        }

        private fun index(column: String): Int? = (1..rows.metaData.columnCount).firstOrNull { rows.metaData.getColumnName(it) == column }
    }
}
