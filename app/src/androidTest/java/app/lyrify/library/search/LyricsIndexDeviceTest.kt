package app.lyrify.library.search

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.LOG_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.random.Random

/** The lyrics index's SQL on the phone's own SQLite, in a test database of its own. Made-up lyrics only. */
@RunWith(AndroidJUnit4::class)
class LyricsIndexDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SqliteLyricsIndexStore(context, NAME)

    @After
    fun deleteTheTestDatabase() {
        store.close()
        context.deleteDatabase(NAME)
    }

    /**
     * The phone's SQLite has FTS4, proved by using it: an FTS4 table made as the index makes its own (unicode61, a kept
     * column) finds a row by a word's start and nothing else. `PRAGMA compile_options` proves nothing: the Galaxy S26's
     * SQLite (Android 17) answers it with no rows at all.
     */
    @Test
    fun ftsFourIsThere() {
        val db = SQLiteDatabase.create(null)
        try {
            val version = db.rawQuery("SELECT sqlite_version()", null).use { row -> row.moveToFirst(); row.getString(0) }
            Log.i(LOG_TAG, "search device test: SQLite $version")
            db.execSQL("CREATE VIRTUAL TABLE temp.probe USING fts4(text, kept, notindexed=kept, tokenize=unicode61 \"remove_diacritics=0\")")
            db.execSQL("INSERT INTO probe (text, kept) VALUES (?, ?)", arrayOf<Any?>("paper boats on the river", "kept one"))
            db.execSQL("INSERT INTO probe (text, kept) VALUES (?, ?)", arrayOf<Any?>("harbour lights are calling", "kept two"))
            fun found(query: String) = db.rawQuery("SELECT kept FROM probe WHERE probe MATCH ?", arrayOf(query)).use { row -> buildList { while (row.moveToNext()) add(row.getString(0)) } }
            assertEquals(listOf("kept one"), found("boat*"))
            assertEquals(listOf("kept two"), found("harbour light*"))
            assertEquals(emptyList<String>(), found("oats"))
            // A column FTS4 keeps without indexing is never matched.
            assertEquals(emptyList<String>(), found("kept"))
        } finally {
            db.close()
        }
    }

    @Test
    fun linesAreFoundAtTheStartOfWordsWithTheirTime() = runBlocking<Unit> {
        store.put(listOf(song("/made/up/Paper Lantern.mp3", 1, "[00:01.50]Paper boats on the river\n[00:07.25]Lanterns low and bright\n[00:12.00]Hold the lantern, hold the line\n")))
        store.put(listOf(song("/made/up/Ember Road.flac", 1, "Ember on the water\nPaper skies above the road\n")))

        assertEquals(
            setOf(StoredLine("/made/up/Paper Lantern.mp3", 1_500, "Paper boats on the river"), StoredLine("/made/up/Ember Road.flac", null, "Paper skies above the road")),
            store.find(listOf("paper")).toSet(),
        )
        // The first matching line of a song, in time order.
        assertEquals(listOf(StoredLine("/made/up/Paper Lantern.mp3", 7_250, "Lanterns low and bright")), store.find(listOf("lantern")))
        assertEquals(listOf(StoredLine("/made/up/Paper Lantern.mp3", 12_000, "Hold the lantern, hold the line")), store.find(listOf("hold", "the", "la")))
        assertEquals(emptyList<StoredLine>(), store.find(listOf("anterns")))
        assertEquals(emptyList<StoredLine>(), store.find(listOf("bright", "hold")))
        assertEquals(mapOf("/made/up/Paper Lantern.mp3" to 1L, "/made/up/Ember Road.flac" to 1L), store.indexed())
    }

    @Test
    fun aSongStoredAgainLosesItsOldLinesAndARemovedOneLeaves() = runBlocking<Unit> {
        store.put(listOf(song("/made/up/Paper Lantern.mp3", 1, "[00:01.50]Paper boats on the river\n")))
        store.put(listOf(song("/made/up/Paper Lantern.mp3", 2, "[00:03.00]Harbour lights are calling\n")))

        assertEquals(emptyList<StoredLine>(), store.find(listOf("paper")))
        assertEquals(listOf(StoredLine("/made/up/Paper Lantern.mp3", 3_000, "Harbour lights are calling")), store.find(listOf("harbour")))
        assertEquals(mapOf("/made/up/Paper Lantern.mp3" to 2L), store.indexed())

        store.remove(listOf("/made/up/Paper Lantern.mp3"))
        assertEquals(emptyList<StoredLine>(), store.find(listOf("harbour")))
        assertEquals(emptyMap<String, Long>(), store.indexed())
    }

    /** A search reads one page of songs, by path, and counts them all with a query of its own. */
    @Test
    fun aSearchReadsOnePageByPathAndCountsApart() = runBlocking<Unit> {
        (9 downTo 1).forEach { n -> store.put(listOf(song("/made/up/Song $n.mp3", 1, "[00:01.00]Paper boats on the river\n"))) }

        assertEquals(9, store.count(listOf("p")))
        assertEquals((1..4).map { "/made/up/Song $it.mp3" }, store.find(listOf("p"), 0, 4).map { it.song })
        assertEquals(listOf("/made/up/Song 9.mp3"), store.find(listOf("p"), 8, 4).map { it.song })
        assertEquals(0, store.count(listOf("harbour")))
        assertEquals(0, store.count(listOf("\"")))
    }

    /** Other scripts are words to the unicode61 tokenizer, and nothing typed reaches MATCH as its syntax. */
    @Test
    fun otherScriptsAreFoundAndQuerySyntaxNeverFails() = runBlocking<Unit> {
        val path = "/made/up/Harbour Light.mp3"
        store.put(listOf(song(path, 1, "[00:01.00]Ο φάρος μετρά τα κύματα\n[00:02.00]灯台が波を数える\n[00:03.00]Near the harbour or the hill\n")))
        val greek = StoredLine(path, 1_000, "Ο φάρος μετρά τα κύματα")
        val near = StoredLine(path, 3_000, "Near the harbour or the hill")

        assertEquals(listOf(greek), store.find(listOf("φαρος", "μετ")))
        assertEquals(listOf(StoredLine(path, 2_000, "灯台が波を数える")), store.find(listOf("灯台")))
        assertEquals(listOf(near), store.find(listOf("OR")))
        assertEquals(listOf(near), store.find(listOf("NEAR")))
        assertEquals(listOf(near), store.find(listOf("\"near", "the\"")))
        for (syntax in listOf("\"", "*", "(", ")", "'", "-", "NEAR/2", "\"\"", "a\"b", "^")) {
            assertEquals(syntax, emptyList<StoredLine>(), store.find(listOf(syntax)))
        }
    }

    /**
     * The cost of the index: [SONGS] made-up songs of about 40 lines (a chorus repeated), built in batches as a sync
     * does, then a one-letter search (the worst case): its count and its first page, as Search reads them. The log gets
     * the time, the size and the counts.
     */
    @Test
    fun buildTimeAndSize() = runBlocking<Unit> {
        val random = Random(7)
        val words = (1..3_000).map { n -> (0..(n % 3)).joinToString("") { SYLLABLES[random.nextInt(SYLLABLES.size)] } }
        fun line() = (1..random.nextInt(4, 10)).joinToString(" ") { words[(random.nextDouble().let { it * it * it } * words.size).toInt()] }
        val songs = (1..SONGS).map { n ->
            val chorus = (1..4).map { line() }
            val lyrics = (0 until 10).flatMap { part -> if (part % 2 == 1) chorus else (1..4).map { line() } }
            IndexedSong("/made/up/Song $n.mp3", 1, lyrics.mapIndexed { at, text -> String.format(Locale.ROOT, "[%02d:%02d.00]%s", at * 4 / 60, at * 4 % 60, text) }.joinToString("\n").let(::lrcLines))
        }
        val started = SystemClock.elapsedRealtime()
        songs.chunked(100).forEach(store::put)
        val built = SystemClock.elapsedRealtime() - started
        val size = context.getDatabasePath(NAME).length() + context.getDatabasePath("$NAME-wal").length()
        val searched = SystemClock.elapsedRealtime()
        val letter = listOf(words.first().take(1))
        val found = store.count(letter)
        val page = store.find(letter, 0, 4)
        val took = SystemClock.elapsedRealtime() - searched
        Log.i(LOG_TAG, "search device test: $SONGS songs, ${songs.sumOf { it.lines.size }} lines, built in $built ms, ${size / 1024} KB; one letter found $found songs, ${page.size} read, in $took ms")

        assertEquals(SONGS, store.indexed().size)
        assertTrue("built in $built ms", built < MAX_BUILD_MS)
        assertTrue("searched in $took ms", took < MAX_SEARCH_MS)
    }

    private fun song(path: String, modifiedAt: Long, lrc: String) = IndexedSong(path, modifiedAt, lrcLines(lrc))

    private companion object {
        const val NAME = "search-device-test.db"
        const val SONGS = 2_000
        val SYLLABLES = listOf("ka", "lo", "mi", "ra", "ven", "tor", "sa", "lu", "fen", "dor", "bri", "ost", "ell", "ane", "quo", "zir")

        /** Far above what a phone should need for 2,000 songs; the log has the real time. */
        const val MAX_BUILD_MS: Long = 60_000
        const val MAX_SEARCH_MS: Long = 2_000
    }
}
