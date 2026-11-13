package app.lyrify.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.library.SongRef
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlaysDb
import app.lyrify.stats.PlaysSchema
import app.lyrify.stats.SqlPlayLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Import from app on the phone: an import's Undo takes its plays out of the real `plays.db` and leaves the user's own;
 * MediaStore's deprecated playlists read without throwing on this Android, whatever it returns; and a made-up Musicolet
 * backup (Blowfish on this phone's own crypto, a real SQLite songs table, plain or WAL) reads through the read-only
 * copy, which is gone afterwards. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class ImportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "import-device-test")
    private val opened = mutableListOf<PlaysDb>()

    private fun open(): SqlPlayLog = SqlPlayLog(PlaysDb(context, File(dir, PlaysSchema.NAME).absolutePath).also(opened::add))

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
    fun undoTakesOutOnlyTheImportedPlays() = runBlocking {
        val store = open()
        val own = LoggedPlay(SongRef(LANTERN, 11), T0, 120_000, counted = true)
        val imported = listOf(LoggedPlay(SongRef(LANTERN), T0 - 1, 0, counted = true), LoggedPlay(SongRef(BELL), T0, 0, counted = true))
        assertTrue(store.put(own))
        assertTrue(store.putAll(imported))

        assertTrue(store.removeAll(imported))

        assertEquals(listOf(own), store.all())
    }

    @Test
    fun phonePlaylistsReadWithoutThrowing() {
        val logged = mutableListOf<String>()
        val rows = MediaStorePlaylistRows(context.contentResolver)

        val count = PhonePlaylists.count(rows, logged::add)
        val bundle = PhonePlaylists.bundle(rows, logged::add)

        assertTrue(count >= 0)
        assertTrue(bundle.size <= ImportFormats.MAX_TOTAL_ENTRIES)
    }

    @Test
    fun aMusicoletBackupReadsThroughARealSongsTableAndLeavesNoCopy() {
        listOf(false, true).forEach { wal ->
            val bundle = (Musicolet.read(backup(songsTable(wal)), MusicoletSqlite(dir) {}) as MusicoletRead.Read).bundle

            val lantern = ImportSong(LANTERN_REL, null, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, relativePath = LANTERN_REL, mediaStoreId = 11)
            assertEquals(listOf(ImportPlays(lantern, 4, T0)), bundle.plays)
            assertEquals(listOf(ImportQueue(listOf(lantern, ImportSong(BELL_REL, null, "Tide Bell", durationMs = 185_000, relativePath = BELL_REL, mediaStoreId = 12)), index = 1)), bundle.queues)
            assertTrue(File(dir, "musicolet-import").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun aFileThatIsNoSongsTableIsNotRead() {
        val db = MusicoletSqlite(dir) {}

        assertEquals(null, db.songs("SQLite format 3\u0000 but nothing after".encodeToByteArray()))
        assertEquals(null, db.songs("not a database".encodeToByteArray()))
        assertTrue(File(dir, "musicolet-import").listFiles().orEmpty().isEmpty())
    }

    /** A made-up `DB_SONGS_LOG` with Musicolet's table, two songs, in WAL mode when [wal]. */
    private fun songsTable(wal: Boolean): ByteArray {
        val file = File(dir, "made-${if (wal) "wal" else "plain"}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            if (wal) db.enableWriteAheadLogging()
            db.execSQL(
                "CREATE TABLE TABLE_SONGS ( COL_PATH TEXT primary key, COL_TITLE TEXT COLLATE NOCASE, COL_ARTIST TEXT COLLATE NOCASE, " +
                    "COL_ALBUM TEXT COLLATE NOCASE, COL_GENRE TEXT COLLATE NOCASE, COL_DURATION INTEGER, COL_NUM_PLAYED INTEGER DEFAULT 0, " +
                    "COL_LAST_PLAYED INTEGER DEFAULT 0, COL_VIS_FLAGS INTEGER DEFAULT 0, COL_GAINS TEXT )",
            )
            db.execSQL("INSERT INTO TABLE_SONGS VALUES (?, 'Paper Lantern', 'Kit Calloway', 'Harbour Glass', '<unknown>', 201000, 4, ?, 3073, '')", arrayOf<Any>(LANTERN_URI, T0))
            db.execSQL("INSERT INTO TABLE_SONGS VALUES (?, 'Tide Bell', '<unknown>', '<unknown>', '<unknown>', 185000, 0, 0, 3073, '')", arrayOf<Any>(BELL_URI))
            if (wal) db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        return file.readBytes()
    }

    /** A made-up Musicolet backup around [songs]: each member Blowfish-encrypted, the md5 manifest and its hash. */
    private fun backup(songs: ByteArray): ByteArray {
        val members = mapOf(
            Musicolet.SONGS to songs,
            Musicolet.QUEUES to """{"S0_CPQ":0,"S0_PQ":[{"S0_PQ_T":"q","S0_PQ_CPS":1,"S0_PQ_LKP":0,"S0_PQ_OQS":{"S_P":["$LANTERN_URI","$BELL_URI"]}}]}""".encodeToByteArray(),
        )
        val sums = members.entries.joinToString(",") { (name, plain) -> "\"$name\":\"${md5(plain)}\"" }
        val manifest = """{"v":540,"time":$T0,"md5":{$sums},"db_file":"DB_SONGS_LOG"}""".encodeToByteArray()
        val cipher = Cipher.getInstance(Musicolet.CIPHER).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(Musicolet.KEY.encodeToByteArray(), "Blowfish")) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val files = mapOf(Musicolet.MANIFEST to cipher.doFinal(manifest), Musicolet.HASH to md5(manifest).encodeToByteArray()) + members.mapValues { cipher.doFinal(it.value) }
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LANTERN_REL: String = "Music/KJB test/Paper Lantern.mp3"
        const val BELL_REL: String = "Music/KJB test/Tide Bell.mp3"
        const val LANTERN_URI: String = "musicolet://media-store?p_v=primary&p_rp=Music%2FKJB%20test&p_dn=Paper%20Lantern.mp3&p_id=11&p_mt=1"
        const val BELL_URI: String = "musicolet://media-store?p_v=primary&p_rp=Music%2FKJB%20test&p_dn=Tide%20Bell.mp3&p_id=12&p_mt=1"
        const val T0: Long = 1_800_000_000_000L
        const val LANTERN: String = "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3"
        const val BELL: String = "/storage/emulated/0/Music/KJB test/Tide Bell.mp3"
    }
}
