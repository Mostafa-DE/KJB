package app.lyrify.library

import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.data.SharedWrites
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The library reader against the phone's own MediaStore, on the debug build: it reads only the test folder. */
@RunWith(AndroidJUnit4::class)
class LibraryReaderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** MediaStore rejects a query naming a column it does not have, so every column the reader asks for must be there. */
    @Test
    fun everyColumnTheReaderAsksForIsThere() {
        val cursor = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, LibraryReader.PROJECTION, null, null, null)
        assertNotNull(cursor)
        checkNotNull(cursor).use { assertEquals(LibraryReader.PROJECTION.toList(), it.columnNames.toList()) }
    }

    /** One read and one grouping of the test folder; the log gets the count and the time only. */
    @Test
    fun theTestFolderIsReadAndGroupedQuickly() {
        val reader = LibraryReader(context, Lyrify.writes)
        val started = SystemClock.elapsedRealtime()
        val read = reader.read()
        assertTrue(read.toString(), read is SongsRead.Songs)
        val library = Library.of((read as SongsRead.Songs).songs, Settings())
        val took = SystemClock.elapsedRealtime() - started
        Log.i(LOG_TAG, "library device test: ${library.all.size} songs read and grouped in $took ms")

        assertTrue(library.all.all { SharedWrites.inTestFolder(it.path) })
        assertTrue("took $took ms", took < MAX_MS)
        assertNotNull(reader.stamp())
    }

    private companion object {
        /** The test folder holds a handful of songs; anything near this is a fault, not a slow phone. */
        const val MAX_MS: Long = 2_000
    }
}
