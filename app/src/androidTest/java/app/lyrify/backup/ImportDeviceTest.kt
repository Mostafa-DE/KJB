package app.lyrify.backup

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
import java.io.File

/**
 * Import from app on the phone: an import's Undo takes its plays out of the real `plays.db` and leaves the user's own,
 * and MediaStore's deprecated playlists read without throwing on this Android, whatever it returns. Made-up names only.
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

    private companion object {
        const val T0: Long = 1_800_000_000_000L
        const val LANTERN: String = "/storage/emulated/0/Music/KJB test/Paper Lantern.mp3"
        const val BELL: String = "/storage/emulated/0/Music/KJB test/Tide Bell.mp3"
    }
}
