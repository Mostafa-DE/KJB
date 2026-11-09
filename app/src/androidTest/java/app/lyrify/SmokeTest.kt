package app.lyrify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests run on the debug build, which installs beside the user's release app and never over it: its own package,
 * name and version, and changes to shared storage only in its test folder.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theDebugBuildIsItsOwnApp() {
        assertEquals("app.lyrify.debug", context.packageName)
        assertEquals("KJB dev", context.getString(R.string.app_name))
        assertTrue(BuildConfig.VERSION_NAME, BuildConfig.VERSION_NAME.endsWith("-dev"))
    }

    /** Decisions only, on the phone's real folders: nothing is created. */
    @Test
    fun theDebugBuildChangesOnlyItsTestFolder() {
        val writes = Lyrify.writes
        assertTrue(writes.testFolderOnly)
        assertTrue(writes.allows(SharedWrite.LRC_CREATE, "/storage/emulated/0/${SharedWrites.TEST_FOLDER}x.lrc"))
        assertFalse(writes.allows(SharedWrite.LRC_CREATE, "/storage/emulated/0/Music/X.lrc"))
        assertFalse(writes.allows(SharedWrite.LRC_CREATE, "/sdcard/${SharedWrites.TEST_FOLDER}x.lrc"))
    }
}
