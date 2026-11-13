package app.lyrify.ui

import android.content.Intent
import android.graphics.Point
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.lyrify.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The back gesture as the system sees it, on KJB as it is set up on the phone (phone test 2026-11-10): on an album page, a
 * swipe from the edge given up, slowly (held, then brought back) or fast, leaves the page current and working; one let
 * go out from the edge goes back. Skipped when KJB is not set up or has no album to open, and with button navigation,
 * which has no back swipe.
 */
@RunWith(AndroidJUnit4::class)
class BackSwipeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val back = context.getString(R.string.check_back)
    private val row get() = device.displayHeight / 2
    private val density = context.resources.displayMetrics.density
    private val edge get() = (EDGE_DP * density).roundToInt()
    private val out get() = (OUT_DP * density).roundToInt()

    @Before
    fun openAnAlbum() {
        assumeTrue("button navigation: no back swipe", gestureNavigation())
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
        val tabsEnd = albums!!.visibleBounds.bottom
        albums.click()
        val card = firstAlbumCard(under = tabsEnd)
        assumeTrue("no album to open", card != null)
        assumeKjbInFront()
        card!!.click()
        assumeTrue(device.wait(Until.hasObject(By.desc(back)), WAIT_MS))
    }

    @Test
    fun aBackSwipeGivenUpSlowlyLeavesTheAlbumCurrentAndWorking() {
        assumeKjbInFront()
        // Out a thumb's width, held for a second, back to the edge, lifted.
        val held = Array(HOLD_POINTS) { Point(out, row) }
        device.swipe(arrayOf(Point(edge, row), Point(out, row)) + held + arrayOf(Point(edge, row)), STEPS)
        assertAlbumStillWorks()
    }

    @Test
    fun aBackSwipeGivenUpFastLeavesTheAlbumCurrentAndWorking() {
        assumeKjbInFront()
        device.swipe(arrayOf(Point(edge, row), Point(out, row), Point(edge, row)), FAST_STEPS)
        assertAlbumStillWorks()
    }

    @Test
    fun aBackSwipeLetGoOutFromTheEdgeGoesBack() {
        assumeKjbInFront()
        device.swipe(arrayOf(Point(edge, row), Point(device.displayWidth / 2, row)), STEPS)
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

    /**
     * The first album card of the grid, top left, by its test tag ([ALBUM_CARD_TAG], a resource id to UiAutomator),
     * never by an album's name nor by where this phone draws it: wholly on screen across, its top under the tabs ending
     * at [under]. Retried while the pages glide to Albums.
     */
    private fun firstAlbumCard(under: Int): UiObject2? {
        if (!device.wait(Until.hasObject(CARD), WAIT_MS)) return null
        repeat(CARD_TRIES) {
            device.waitForIdle()
            // Each read once: a card moving with the pages can be gone by the next read.
            val card = device.findObjects(CARD)
                .mapNotNull { found -> runCatching { found to found.visibleBounds }.getOrNull() }
                .filter { (_, b) -> !b.isEmpty && b.top >= under && b.left >= 0 && b.right <= device.displayWidth }
                .minWithOrNull(compareBy({ it.second.top }, { it.second.left }))
            if (card != null) return card.first
        }
        return null
    }

    /**
     * Whether the phone uses gesture navigation: the system's navigation mode as KJB's resources see it, or its setting
     * where the system lets an app read it.
     */
    private fun gestureNavigation(): Boolean {
        val id = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        val fromResources = id != 0 && context.resources.getInteger(id) == GESTURAL_NAVIGATION
        val fromSetting = runCatching { Settings.Secure.getInt(context.contentResolver, "navigation_mode") == GESTURAL_NAVIGATION }.getOrDefault(false)
        return fromResources || fromSetting
    }

    /** Never a touch on another app: if KJB is not the app in front, nothing is sent and the test stops. */
    private fun assumeKjbInFront() {
        assumeTrue("KJB is not in front", device.currentPackageName == context.packageName)
    }

    private companion object {
        const val WAIT_MS = 5_000L

        /** Where a back swipe starts, at the very edge, and how far out it goes before it is given up. */
        const val EDGE_DP = 1f
        const val OUT_DP = 110f
        const val STEPS = 20
        const val FAST_STEPS = 6

        /** Each step of a swipe is about 5 ms: this many points of 20 steps each hold the finger about a second. */
        const val HOLD_POINTS = 10

        /** Android's own value for gesture navigation (3-button is 0, 2-button 1). */
        const val GESTURAL_NAVIGATION = 2

        val CARD: BySelector = By.res(ALBUM_CARD_TAG)
        const val CARD_TRIES = 10
    }
}
