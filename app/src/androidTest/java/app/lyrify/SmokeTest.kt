package app.lyrify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests run on the debug build, which installs beside the user's release app and never over it: its own package,
 * name and version.
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
}
