package app.lyrify.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.library.SongRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real `plays.db`'s seams for a backup and a restore, on the phone (the JVM tests use FakePlayLog's): every play,
 * the oldest first, and a restore's plays written in one go, a play already there (the same song and start) not added
 * twice. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class PlaysBackupDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "plays-backup-device-test")
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
    fun everyPlayComesOutOldestFirst() = runBlocking<Unit> {
        val store = open()
        val late = LoggedPlay(SongRef(LANTERN, 11), T0 + 5_000, 90_000, counted = true)
        val early = LoggedPlay(SongRef(BELL), T0, 20_000, counted = false)
        assertTrue(store.put(late))
        assertTrue(store.put(early))

        assertEquals(listOf(early, late), store.all())
    }

    @Test
    fun aRestoreTwiceAddsNoPlayTheSecondTime() = runBlocking<Unit> {
        val store = open()
        val mine = LoggedPlay(SongRef(LANTERN, 11), T0, 120_000, counted = true)
        assertTrue(store.put(mine))
        val restored = listOf(LoggedPlay(SongRef(LANTERN), T0, 60_000, counted = false), LoggedPlay(SongRef(BELL), T0 + 1_000, 30_000, counted = true))

        assertTrue(store.putAll(restored))
        assertTrue(store.putAll(restored))

        // The play already there keeps its time heard, its count and its id hint.
        assertEquals(listOf(mine, restored[1]), store.all())
        assertEquals(setOf(SongPlays(SongRef(LANTERN, 11), 1, T0, 120_000), SongPlays(SongRef(BELL), 1, T0 + 1_000, 30_000)), store.totals().toSet())
    }

    private companion object {
        const val T0: Long = 1_800_000_000_000L
        const val LANTERN: String = "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3"
        const val BELL: String = "/storage/emulated/0/Music/KJB test/Tide Bell.mp3"
    }
}
