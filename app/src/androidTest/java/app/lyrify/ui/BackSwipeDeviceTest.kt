package app.lyrify.ui

import android.content.Intent
import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.lyrify.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The back gesture as the system sees it, on KJB as it is set up on the phone (phone test 2026-11-10): on an album page, a
 * swipe from the edge given up, slowly (held, then brought back) or fast, leaves the page current and working; one let
 * go out from the edge goes back. Skipped when KJB is not set up or has no album to open.
 */
@RunWith(AndroidJUnit4::class)
class BackSwipeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val back = context.getString(R.string.check_back)
    private val row get() = device.displayHeight / 2

    @Before
    fun openAnAlbum() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assumeTrue(launch != null)
        context.startActivity(launch!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val library = device.wait(Until.findObject(By.desc(context.getString(R.string.nav_library))), WAIT_MS)
            ?: device.wait(Until.findObject(By.text(context.getString(R.string.nav_library))), WAIT_MS)
        assumeTrue(library != null)
        assumeKjbInFront()
        library!!.click()
        val albums = device.wait(Until.findObject(By.text(context.getString(R.string.library_tab_albums))), WAIT_MS)
        assumeTrue(albums != null)
        assumeKjbInFront()
        albums!!.click()
        device.waitForIdle()
        // The first album card of the grid.
        assumeKjbInFront()
        device.click(device.displayWidth / 4, device.displayHeight / 3)
        assumeTrue(device.wait(Until.hasObject(By.desc(back)), WAIT_MS))
    }

    @Test
    fun aBackSwipeGivenUpSlowlyLeavesTheAlbumCurrentAndWorking() {
        assumeKjbInFront()
        // Out to 300 px, held for a second, back to the edge, lifted.
        val held = Array(HOLD_POINTS) { Point(OUT_PX, row) }
        device.swipe(arrayOf(Point(EDGE_PX, row), Point(OUT_PX, row)) + held + arrayOf(Point(EDGE_PX, row)), STEPS)
        assertAlbumStillWorks()
    }

    @Test
    fun aBackSwipeGivenUpFastLeavesTheAlbumCurrentAndWorking() {
        assumeKjbInFront()
        device.swipe(arrayOf(Point(EDGE_PX, row), Point(OUT_PX, row), Point(EDGE_PX, row)), FAST_STEPS)
        assertAlbumStillWorks()
    }

    @Test
    fun aBackSwipeLetGoOutFromTheEdgeGoesBack() {
        assumeKjbInFront()
        device.swipe(arrayOf(Point(EDGE_PX, row), Point(device.displayWidth / 2, row)), STEPS)
        assertTrue(device.wait(Until.gone(By.desc(back)), WAIT_MS))
    }

    /** The album's own Back still shows, and still works: the page is current and takes touches. */
    private fun assertAlbumStillWorks() {
        device.waitForIdle()
        val button = device.wait(Until.findObject(By.desc(back)), WAIT_MS)
        assertTrue(button != null)
        assumeKjbInFront()
        button!!.click()
        assertTrue(device.wait(Until.gone(By.desc(back)), WAIT_MS))
    }

    /** Never a touch on another app: if KJB is not the app in front, nothing is sent and the test stops. */
    private fun assumeKjbInFront() {
        assumeTrue("KJB is not in front", device.currentPackageName == context.packageName)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val EDGE_PX = 2
        const val OUT_PX = 300
        const val STEPS = 20
        const val FAST_STEPS = 6

        /** Each step of a swipe is about 5 ms: this many points of 20 steps each hold the finger about a second. */
        const val HOLD_POINTS = 10
    }
}
