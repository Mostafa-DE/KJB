package app.lyrify.library.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.lyrics.LrcFormat
import app.lyrify.settings.LibrarySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Collator
import kotlin.time.TimeSource

/**
 * One line of a song's lyrics as the index keeps it: its [text] without timestamps or tags, when it is sung ([timeMs];
 * null in plain lyrics), and the text [fold]ed, as it is searched.
 */
data class LyricLine(val timeMs: Long?, val text: String, val folded: String)

/** A song's lyrics as read from its `.lrc`, modified at [modifiedAt] (wall-clock millis). */
data class IndexedSong(val path: String, val modifiedAt: Long, val lines: List<LyricLine>)

/** The line a song's lyrics were found by. */
data class StoredLine(val song: String, val timeMs: Long?, val text: String)

/** A song found by its lyrics: its [title] as shown, the first [line] that matched, when it is sung, and where it matched. */
data class LyricHit(val song: Song, val title: String, val line: String, val timeMs: Long?, val match: Match)

/** The lyrics index's storage: SQLite FTS4 in the app ([SqliteLyricsIndexStore]), in memory in tests. Blocking, except [find]; failures throw an IOException. */
interface LyricsIndexStore {
    /** Every song indexed, by its path, with the modified time of the `.lrc` it was read from. */
    fun indexed(): Map<String, Long>

    /** Stores [songs]' lines in place of the ones they had, all or none. */
    fun put(songs: List<IndexedSong>)

    /** Forgets [songs] (their paths) and their lines. */
    fun remove(songs: Collection<String>)

    /**
     * Per song, its first line in [IndexedSong.lines]' order whose folded text has [words] one after another, each a
     * whole word but the last, which may be a word's start. Stops when the caller is cancelled.
     */
    suspend fun find(words: List<String>): List<StoredLine>
}

/** The `.lrc` files beside the songs. Blocking. */
interface LrcFiles {
    /** The `.lrc` files in [folder] (an absolute path), by name, with their modified times; null when it cannot be listed. */
    fun list(folder: String): Map<String, Long>?

    /** The text of the file at [path]; null when it cannot be read or is too large to be lyrics. */
    fun read(path: String): String?

    /** The modified time of the file at [path] now; null when it is gone. */
    fun modified(path: String): Long?
}

/**
 * The lines of a `.lrc` file's [text], synced or plain, as Search reads them. Line timestamps (`[01:02.30]`, several on
 * one line too) give the line its first time and leave the text, as do word timestamps (`<01:02.30>`) and anything else
 * in brackets (header tags such as `[ar:…]` and `[offset:…]`, "[Chorus]"); lines left with no letter or digit go. In time
 * order, lines without a time after the others in file order, each text once (case and accents aside) at its first
 * time, at most [SqliteLyricsIndexStore.MAX_LINES].
 */
fun lrcLines(text: String): List<LyricLine> {
    val seen = HashSet<String>()
    return text.removePrefix("\uFEFF").lineSequence().mapNotNull(::lrcLine).toList()
        .sortedWith(compareBy(nullsLast()) { it.timeMs })
        .filter { seen.add(it.folded) }
        .take(SqliteLyricsIndexStore.MAX_LINES)
}

private fun lrcLine(line: String): LyricLine? {
    var rest = line
    var time: Long? = null
    while (true) {
        val stamp = LINE_STAMP.find(rest) ?: break
        val (minutes, seconds, fraction) = stamp.destructured
        val at = minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs(fraction)
        time = time?.let { minOf(it, at) } ?: at
        rest = rest.substring(stamp.range.last + 1)
    }
    val shown = rest.replace(WORD_STAMP, " ").replace(BRACKETS, " ").replace(CONTROL, " ").replace(SPACES, " ").trim()
    val folded = fold(shown)
    return if (folded.isEmpty()) null else LyricLine(time, shown, folded)
}

/** `.5` is half a second: LRC means tenths, hundredths or thousandths by the digit count. */
private fun fractionMs(digits: String): Long = when (digits.length) {
    0 -> 0
    1 -> digits.toLong() * 100
    2 -> digits.toLong() * 10
    else -> digits.toLong()
}

/** A line's timestamp at its start: `[mm:ss]`, `[mm:ss.x]` to `[mm:ss.xxx]`, also with `:` before the fraction. */
private val LINE_STAMP = Regex("""^\s*\[(\d{1,4}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val WORD_STAMP = Regex("""<\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?>""")
private val BRACKETS = Regex("""\[[^\[\]]*]""")
private val CONTROL = Regex("""\p{Cc}""")
private val SPACES = Regex("""\s+""")

/**
 * The lyrics of the library's songs, searchable by any line: the `.lrc` next to each song (where Samsung Music looks:
 * the audio path with `.lrc` for its extension), read into [store] and kept up to date by [sync]. Lines are found where
 * the words start a word ([find]), so the index needs no tokenizer of its own. The log gets counts, times and exception
 * names only, never lyrics, titles or paths.
 */
class LyricsIndex(
    private val store: LyricsIndexStore,
    private val files: LrcFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) {
    private val syncing = Mutex()

    private val _version = MutableStateFlow(0)

    /** Moves each time the index changes: results found before may be out of date. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _building = MutableStateFlow(false)

    /** True while `.lrc` files are being read: In lyrics may be missing songs. */
    val building: StateFlow<Boolean> = _building.asStateFlow()

    /**
     * Brings the index up to [songs]: reads the `.lrc` of each song that is new or whose `.lrc` changed since it was
     * read (by its modified time), and forgets songs that left [songs] or lost their `.lrc`. Each folder is listed
     * once; files are read on [io] and stored [BATCH] songs at a time, so a sync cancelled midway keeps what it did.
     * A folder that cannot be listed keeps its songs as they were, and a file that cannot be read, or that changes while
     * it is read, is tried again next time. One sync at a time; a failing store ends it.
     */
    suspend fun sync(songs: List<Song>) = syncing.withLock {
        val started = TimeSource.Monotonic.markNow()
        var read = 0
        var unreadable = 0
        try {
            val (changed, gone) = withContext(io) { plan(songs) }
            if (gone.isNotEmpty()) {
                withContext(io) { store.remove(gone) }
                _version.update { it + 1 }
            }
            if (changed.isNotEmpty()) _building.value = true
            changed.chunked(BATCH).forEach { batch ->
                val lyrics = withContext(io) {
                    batch.mapNotNull { file ->
                        val text = files.read(file.lrc) ?: return@mapNotNull null
                        // Written to while it was read, so it may be half read: stored under its old time, the next sync
                        // would take it as unchanged.
                        if (files.modified(file.lrc) != file.modifiedAt) return@mapNotNull null
                        IndexedSong(file.song, file.modifiedAt, lrcLines(text))
                    }
                }
                read += lyrics.size
                unreadable += batch.size - lyrics.size
                if (lyrics.isNotEmpty()) {
                    withContext(io) { store.put(lyrics) }
                    _version.update { it + 1 }
                }
            }
            if (changed.isNotEmpty() || gone.isNotEmpty()) {
                log("search: lyrics index: $read songs read, ${gone.size} forgotten, $unreadable unreadable, in ${started.elapsedNow().inWholeMilliseconds} ms")
            }
        } catch (e: IOException) {
            log("search: lyrics index not updated (${(e.cause ?: e).javaClass.simpleName})")
        } finally {
            _building.value = false
        }
    }

    /**
     * The songs of [library] with a line where [query]'s words start a word, one after another: each with its first
     * such line and that line's time, by title as [settings] shows it. Empty when the index cannot be read.
     */
    suspend fun find(query: String, library: Library, settings: LibrarySettings): List<LyricHit> {
        val folded = fold(query)
        if (folded.isEmpty()) return emptyList()
        val lines = try {
            store.find(folded.split(' '))
        } catch (e: IOException) {
            log("search: lyrics not searched (${(e.cause ?: e).javaClass.simpleName})")
            return emptyList()
        }
        val collator = Collator.getInstance()
        return lines.mapNotNull { line ->
            val song = library.song(line.song) ?: return@mapNotNull null
            val match = match(line.text, folded)?.takeIf { it.kind == MatchKind.WORD_START } ?: return@mapNotNull null
            LyricHit(song, displayTitle(song, settings), line.text, line.timeMs, match)
        }.sortedWith(compareBy<LyricHit, String>(collator) { it.title }.thenBy { it.song.path })
    }

    /** A song's `.lrc` as it is now. */
    private class LrcFile(val song: String, val lrc: String, val modifiedAt: Long)

    /** The songs to read again, and those to forget. */
    private fun plan(songs: List<Song>): Pair<List<LrcFile>, Set<String>> {
        val stored = store.indexed()
        val listed = HashMap<String, Map<String, Long>?>()
        val present = mutableListOf<LrcFile>()
        // Songs in folders that could not be listed: kept as they are.
        val unknown = HashSet<String>()
        songs.distinctBy { it.path }.forEach { song ->
            val lrc = LrcFormat.sidecarPath(song.path) ?: return@forEach
            val folder = lrc.substringBeforeLast('/')
            if (folder !in listed) listed[folder] = files.list(folder)
            val names = listed[folder]
            if (names == null) {
                unknown += song.path
                return@forEach
            }
            names[lrc.substringAfterLast('/')]?.let { present += LrcFile(song.path, lrc, it) }
        }
        val changed = present.filter { stored[it.song] != it.modifiedAt }
        val gone = stored.keys - present.map { it.song }.toSet() - unknown
        return changed to gone
    }

    private companion object {
        /** Songs read, then stored, at a time. */
        const val BATCH: Int = 100
    }
}

/** The phone's `.lrc` files, through java.io.File (All files access). */
object FileLrcFiles : LrcFiles {
    /** Larger than any lyrics. */
    private const val MAX_BYTES: Long = 1_048_576

    override fun list(folder: String): Map<String, Long>? =
        File(folder).listFiles { file -> file.name.endsWith(LRC) }?.associate { it.name to it.lastModified() }

    override fun read(path: String): String? = try {
        File(path).takeIf { it.length() <= MAX_BYTES }?.readText()
    } catch (e: IOException) {
        null
    }

    override fun modified(path: String): Long? = File(path).lastModified().takeIf { it != 0L }

    private const val LRC: String = ".lrc"
}

/**
 * [LyricsIndexStore] in app-private `databases/search.db`: a `songs` table (path, the `.lrc`'s modified time, how many
 * lines) and an FTS4 table of lines (the folded text indexed, the text and time kept beside it). The simple tokenizer
 * only splits on spaces and folds ASCII case here, since every line is stored [fold]ed. A line's docid is its song's id
 * shifted left 16 bits plus its place, so a song's first match is its lowest docid. Disposable: a new [SCHEMA] drops it
 * and it is read again from the `.lrc` files; never backed up.
 */
class SqliteLyricsIndexStore(
    context: Context,
    name: String = NAME,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SQLiteOpenHelper(context, name, null, SCHEMA), LyricsIndexStore {
    init {
        // Searches read while an update writes.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE songs (id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, modified INTEGER NOT NULL, lines INTEGER NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE lines USING fts4(folded, text, time, notindexed=text, notindexed=time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    private fun recreate(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS lines")
        db.execSQL("DROP TABLE IF EXISTS songs")
        onCreate(db)
    }

    override fun indexed(): Map<String, Long> = guarded {
        readableDatabase.rawQuery("SELECT path, modified FROM songs", null).use { row ->
            buildMap { while (row.moveToNext()) put(row.getString(0), row.getLong(1)) }
        }
    }

    override fun put(songs: List<IndexedSong>) = write { db ->
        db.compileStatement("INSERT INTO lines (docid, folded, text, time) VALUES (?, ?, ?, ?)").use { insert ->
            songs.forEach { song ->
                val values = ContentValues().apply {
                    put("path", song.path)
                    put("modified", song.modifiedAt)
                    put("lines", song.lines.size)
                }
                val id = forgetLines(db, song.path)?.also { db.update("songs", values, "id = ?", arrayOf(it.toString())) }
                    ?: db.insertOrThrow("songs", null, values)
                song.lines.forEachIndexed { at, line ->
                    insert.clearBindings()
                    insert.bindLong(1, docid(id, at))
                    insert.bindString(2, line.folded)
                    insert.bindString(3, line.text)
                    line.timeMs?.let { insert.bindLong(4, it) } ?: insert.bindNull(4)
                    insert.executeInsert()
                }
            }
        }
    }

    override fun remove(songs: Collection<String>) = write { db ->
        songs.forEach { path -> forgetLines(db, path)?.let { db.delete("songs", "id = ?", arrayOf(it.toString())) } }
    }

    override suspend fun find(words: List<String>): List<StoredLine> = coroutineScope {
        val signal = CancellationSignal()
        val query = async(io) {
            try {
                guarded { query(words, signal) }
            } catch (e: OperationCanceledException) {
                throw CancellationException("search cancelled")
            }
        }
        try {
            query.await()
        } catch (e: CancellationException) {
            signal.cancel()
            throw e
        }
    }

    /** One FTS phrase: the words in a row, the last as a prefix. Folded words hold only letters, digits and no quotes. */
    private fun query(words: List<String>, signal: CancellationSignal): List<StoredLine> {
        val phrase = "\"" + words.joinToString(" ") + "*\""
        return readableDatabase.rawQuery(FIND, arrayOf(phrase), signal).use { row ->
            buildList { while (row.moveToNext()) add(StoredLine(row.getString(0), if (row.isNull(1)) null else row.getLong(1), row.getString(2))) }
        }
    }

    /** Deletes the lines of the song at [path]; its id, or null when it is not indexed. */
    private fun forgetLines(db: SQLiteDatabase, path: String): Long? {
        val (id, lines) = db.rawQuery("SELECT id, lines FROM songs WHERE path = ?", arrayOf(path)).use { row ->
            if (row.moveToFirst()) row.getLong(0) to row.getInt(1) else return null
        }
        // By docid, one at a time: FTS4 finds a docid directly, while a range may scan the whole table on older SQLite.
        db.compileStatement("DELETE FROM lines WHERE docid = ?").use { delete ->
            repeat(lines) { at ->
                delete.bindLong(1, docid(id, at))
                delete.executeUpdateDelete()
            }
        }
        return id
    }

    private fun write(change: (SQLiteDatabase) -> Unit) = guarded {
        val db = writableDatabase
        db.beginTransaction()
        try {
            change(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> guarded(work: () -> T): T = try {
        work()
    } catch (e: SQLiteException) {
        throw IOException(e)
    }

    companion object {
        const val NAME: String = "search.db"

        /** Bump on any change to the tables or to [fold]: the index is then read again from the `.lrc` files. */
        const val SCHEMA: Int = 1

        /** Lines a song may have in the index: its place takes the docid's low 16 bits. */
        const val MAX_LINES: Int = 2_000

        private fun docid(song: Long, line: Int): Long = (song shl 16) or line.toLong()

        /** Per song, the line with the lowest docid among those that match, with the song's path. */
        private const val FIND: String =
            "SELECT s.path, l.time, l.text FROM lines AS l " +
                "JOIN (SELECT MIN(docid) AS first FROM lines WHERE lines MATCH ? GROUP BY docid >> 16) AS m ON l.docid = m.first " +
                "JOIN songs AS s ON s.id = l.docid >> 16"
    }
}
