package app.lyrify.stats

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.LOG_TAG
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.ZoneId
import kotlin.system.measureNanoTime

/**
 * The real `plays.db` on the phone, in a folder of its own in the debug app's cache: the same rules as the JVM tests'
 * FakePlayLog (plays written again, plays still going), its versions, damaged rows, an unreadable file, and the top
 * lists' speed. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class PlaysDbDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "plays-device-test")
    private val file = File(dir, PlaysSchema.NAME)
    private val opened = mutableListOf<PlaysDb>()
    private val logs = mutableListOf<String>()

    private fun open(): SqlPlayLog = SqlPlayLog(PlaysDb(context, file.absolutePath, log = logs::add).also(opened::add), log = logs::add)

    @Before
    fun clean() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun tidy() {
        opened.forEach(PlaysDb::close)
        dir.deleteRecursively()
    }

    @Test
    fun aNewFileKeepsPlaysByTheStoreRules() = runBlocking<Unit> {
        val store = open()
        val other = "$MUSIC/Ember Ledger.mp3"
        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0, 50_000, counted = true)))
        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0, 180_000, counted = true)))
        assertTrue(store.put(LoggedPlay(SongRef(PATH, 14), T0 + 1_000, 20_000, counted = false)))
        assertTrue(store.put(LoggedPlay(SongRef(other), T0 + 2_000, 90_000, counted = true)))

        assertEquals(setOf(SongPlays(SongRef(PATH, 14), 1, T0, 200_000), SongPlays(SongRef(other), 1, T0 + 2_000, 90_000)), store.totals().toSet())
        assertEquals(listOf(SongPlays(SongRef(PATH, 14), 0, null, 20_000)), store.totals(T0 + 1_000, T0 + 2_000))
        assertEquals(PlaysSchema.VERSION, opened.single().readableDatabase.version)
    }

    @Test
    fun aPlayWrittenAgainNeverLosesItsCountOrTimeHeard() = runBlocking<Unit> {
        val store = open()
        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0, 120_000, counted = true)))
        assertTrue(store.put(LoggedPlay(SongRef(PATH), T0, 5_000, counted = false)))

        assertEquals(listOf(SongPlays(SongRef(PATH, 12), 1, T0, 120_000)), store.totals())
    }

    @Test
    fun aPlayStillGoingIsKeptWhereItStoodAndLetGoWhenItCloses() = runBlocking<Unit> {
        val store = open()
        val state = OpenState(200_000, 60_000, listOf(HeardSpan(0, 30_000), HeardSpan(40_000, 70_000)))
        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0, 60_000, counted = false), state))
        assertEquals(listOf(StoredPlay(LoggedPlay(SongRef(PATH, 12), T0, 60_000, counted = false), state)), store.openPlays())

        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0, 110_000, counted = true)))
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
        assertEquals(listOf(SongPlays(SongRef(PATH, 12), 1, T0, 110_000)), store.totals())
    }

    @Test
    fun aVersionOneFileUpgradesWithItsPlays() = runBlocking<Unit> {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            PlaysSchema.STEPS[0].forEach(db::execSQL)
            db.execSQL(PlaysSchema.PUT, arrayOf<Any?>(PATH, T0, 12L, 120_000L, 1L))
            db.version = 1
        }
        val store = open()

        assertEquals(listOf(SongPlays(SongRef(PATH, 12), 1, T0, 120_000)), store.totals())
        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0 + 1_000, 60_000, counted = true)))
        assertEquals(listOf(SongPlays(SongRef(PATH, 12), 2, T0 + 1_000, 180_000)), store.totals())
        assertEquals(PlaysSchema.VERSION, opened.single().readableDatabase.version)
        // Version 2's table is there: a play still going is kept.
        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0 + 2_000, 30_000, counted = false), OpenState(200_000, 30_000, listOf(HeardSpan(0, 30_000)))))
        assertEquals(1, store.openPlays().size)
    }

    @Test
    fun aNewerKjbsFileIsKeptAsItIs() = runBlocking<Unit> {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            PlaysSchema.upgrade(0, PlaysSchema.VERSION).forEach(db::execSQL)
            db.execSQL("ALTER TABLE plays ADD COLUMN later INTEGER")
            db.execSQL("CREATE TABLE later (x INTEGER)")
            db.execSQL(PlaysSchema.PUT, arrayOf<Any?>(PATH, T0, 12L, 120_000L, 1L))
            db.version = PlaysSchema.VERSION + 1
        }
        val store = open()

        assertTrue(store.put(LoggedPlay(SongRef(PATH, 12), T0 + 1_000, 60_000, counted = true)))
        assertEquals(listOf(SongPlays(SongRef(PATH, 12), 2, T0 + 1_000, 180_000)), store.totals())
    }

    @Test
    fun damagedRowsReadLeniently() = runBlocking<Unit> {
        val store = open()
        store.put(LoggedPlay(SongRef(PATH), T0, 0, counted = false))
        val db = opened.single().writableDatabase
        db.execSQL("INSERT OR REPLACE INTO plays (path, started_at, media_id, heard_ms, counted) VALUES (?, ?, 'not a number', -5, 1)", arrayOf<Any?>(PATH, T0))
        db.execSQL("INSERT INTO plays (path, started_at, media_id, heard_ms, counted) VALUES ('', ?, 3, 9000, 1)", arrayOf<Any?>(T0))

        assertEquals(listOf(SongPlays(SongRef(PATH), 1, T0, 0)), store.totals())
    }

    /**
     * SQLite finds the junk while the file is being opened: it is kept aside and the same open goes on with a new file,
     * so the very first write lands. (Closing the database there, mid-open, failed that first call on the phone.)
     */
    @Test
    fun anUnreadableFileIsKeptAsideAndANewOneStarted() = runBlocking<Unit> {
        val junk = ByteArray(8_192) { (it * 31 + 7).toByte() }
        file.writeBytes(junk)
        val store = open()

        assertTrue(logs.toString(), store.put(LoggedPlay(SongRef(PATH), T0, 120_000, counted = true)))
        assertEquals(1, store.totals().size)
        val aside = dir.listFiles { it: File -> it.name.startsWith("plays-unreadable-") && it.name.endsWith(".db") }.orEmpty()
        assertEquals(1, aside.size)
        assertArrayEquals(junk, aside.single().readBytes())
        assertEquals(listOf("plays: plays.db unreadable, kept aside"), logs)
        // The new file is a real one: opened again, it has the play.
        assertEquals(listOf(SongPlays(SongRef(PATH), 1, T0, 120_000)), open().totals())
    }

    @Test
    fun theTopListsOnTenThousandPlaysTakeUnder50Ms() = runBlocking<Unit> {
        val songs = (1..SONGS).map { n ->
            Song(SongRef("$MUSIC/Made Up $n.mp3", n.toLong()), "Made Up $n", "Artist ${n % 40}", "Album ${n % 60}", null, 200_000, n % 12, null, 2020, null, "Music/KJB test/", T0, T0, 4_000_000, "audio/mpeg")
        }
        val library = Library.of(songs, Settings())
        val helper = PlaysDb(context, file.absolutePath).also(opened::add)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            repeat(PLAYS) { i ->
                val song = songs[(i * 7919) % SONGS]
                db.execSQL(PlaysSchema.PUT, arrayOf<Any?>(song.path, T0 + i * 60_000L, song.ref.mediaStoreId, 150_000L, if (i % 5 == 0) 0L else 1L))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val stats = PlayStats(SqlPlayLog(helper), { library })
        val now = T0 + PLAYS * 60_000L
        val zone = ZoneId.of("Europe/Berlin")

        val queries = listOf(
            "most played" to suspend { stats.mostPlayed(50) },
            "recently played" to suspend { stats.recentlyPlayed(50) },
            "all time" to suspend { stats.listening(ListeningPeriod.ALL_TIME, now, zone, DayOfWeek.MONDAY) },
            "week" to suspend { stats.listening(ListeningPeriod.WEEK, now, zone, DayOfWeek.MONDAY) },
        )
        queries.forEach { (name, query) ->
            query()
            val millis = List(5) { measureNanoTime { query() } / 1_000_000 }.sorted()[2]
            Log.i(LOG_TAG, "plays device test: $name over $PLAYS plays in $millis ms")
            assertTrue("$name took $millis ms", millis < MAX_MS)
        }
        assertEquals(50, stats.mostPlayed(50).size)
    }

    private companion object {
        const val MUSIC = "/storage/emulated/0/Music/KJB test"
        const val PATH = "$MUSIC/Salt Lattice.mp3"
        const val T0: Long = 1_800_000_000_000L
        const val SONGS = 500
        const val PLAYS = 10_000
        const val MAX_MS: Long = 50
    }
}
