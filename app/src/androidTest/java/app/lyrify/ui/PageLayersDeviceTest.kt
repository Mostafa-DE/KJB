package app.lyrify.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.settings.DockTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page layer as the shell draws it (phone test 2026-11-11), frame by frame with the test clock held: a plain Back
 * (the three-button Back, KEYCODE_BACK) moves the album page away as a finished back swipe does, shrinking and fading
 * over the Library root, never a cut; and each dock destination's root is built once, kept through dock switches and
 * through Back from a page over it. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class PageLayersDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val album = Page.Album("harbour-glass")
    private val albumKey = pageKey(DockTab.LIBRARY, listOf(album))
    private val built = mutableMapOf<DockTab, Int>()

    @Test
    fun aPlainBackPressMovesTheAlbumAwayAsTheSwipeDoesNeverACut() {
        val nav = Navigator(NavState.start(DockTab.LIBRARY).open(album))
        compose.setContent { Shell(nav) }
        compose.waitForIdle()
        val whole = compose.onNodeWithTag(albumKey).getBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS / 2L)
        // Half way: the album is still drawn, shrinking as a back swipe shrinks it, over Library's root.
        val half = compose.onNodeWithTag(albumKey).getBoundsInRoot()
        assertTrue("half ${half.width()} whole ${whole.width()}", half.width() < whole.width() * 0.99f)
        compose.onNodeWithText(ROOT).assertExists()
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS * 2L)
        compose.onNodeWithTag(albumKey).assertDoesNotExist()
        compose.onNodeWithText(ROOT).assertExists()
    }

    @Test
    fun aFinishedBackSwipeCarriesOnFromWhereTheFingerLeftIt() {
        val nav = Navigator(NavState.start(DockTab.LIBRARY).open(album))
        compose.setContent { Shell(nav) }
        compose.waitForIdle()
        val whole = compose.onNodeWithTag(albumKey).getBoundsInRoot()
        compose.mainClock.autoAdvance = false
        val back = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            back.dispatchOnBackStarted(BackEventCompat(touchX = 4f, touchY = 900f, progress = 0f, swipeEdge = BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(touchX = 300f, touchY = 900f, progress = 0.5f, swipeEdge = BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        val held = compose.onNodeWithTag(albumKey).getBoundsInRoot()
        assertTrue(held.width() < whole.width())
        compose.runOnUiThread { back.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS / 2L)
        // Let go, it goes on shrinking from there as it fades: the same motion, never back to whole and never a cut.
        val going = compose.onNodeWithTag(albumKey).getBoundsInRoot()
        assertTrue("going ${going.width()} held ${held.width()}", going.width() < held.width())
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS * 2L)
        compose.onNodeWithTag(albumKey).assertDoesNotExist()
    }

    @Test
    fun eachDockDestinationsRootIsBuiltOnceAndKeptThroughSwitchesAndBack() {
        val nav = Navigator(NavState.start(DockTab.LIBRARY))
        compose.setContent { Shell(nav) }
        compose.waitForIdle()
        compose.runOnUiThread { nav.select(DockTab.SEARCH) }
        compose.waitForIdle()
        // Hidden behind Search, Library's root says nothing and takes nothing, but it is still there.
        compose.onNodeWithText(ROOT).assertDoesNotExist()
        compose.runOnUiThread { nav.select(DockTab.LIBRARY) }
        compose.waitForIdle()
        compose.runOnUiThread { nav.open(album) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText(ROOT).assertExists()
        assertEquals(1, built[DockTab.LIBRARY])
        assertEquals(1, built[DockTab.SEARCH])
    }

    /** The shell's page layer and its Back, each layer a page filling the window: its root counts each time it is built. */
    @Composable
    private fun Shell(nav: Navigator) {
        val pages = remember { SeekableTransitionState(pageLayerOf(nav.state)) }
        val swipe = remember { BackSwipe() }
        CompositionLocalProvider(LocalNavigator provides nav) {
            PagesBack(nav, pages, swipe, reduced = false)
            PageLayers(pages, swipe, rtl = false, reduced = false) { layer ->
                Box(Modifier.fillMaxSize().background(Color.White).testTag(layer.key)) {
                    if (layer.stack.isEmpty()) {
                        remember { built.merge(layer.tab, 1, Int::plus) }
                        Text(if (layer.tab == DockTab.LIBRARY) ROOT else layer.tab.name)
                    } else {
                        Text("Harbour Glass")
                    }
                }
            }
        }
    }

    private fun DpRect.width(): Float = (right - left).value

    private companion object {
        const val ROOT = "Library's root"

        /** A few frames, for the swipe to reach the layers. */
        const val FRAMES_MS = 100L
    }
}
