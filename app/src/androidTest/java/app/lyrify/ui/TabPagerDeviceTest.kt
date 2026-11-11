package app.lyrify.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Top tabs that swipe (phone test 2026-11-11, points 2 and 4), on the phone: a swipe moves to the next tab and the tab
 * takes the chosen look, a tap glides there through the pages between, every tab keeps its list's place and is built
 * once, a sideways drag that belongs to a row stays the row's, and under a hero the hero scrolls away before the tabs'
 * lists do. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class TabPagerDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val tabs = listOf("Songs", "Albums", "Artists")
    private val built = mutableMapOf<String, Int>()
    private var chosen by mutableIntStateOf(0)

    @Test
    fun aSwipeMovesToTheNextTabAndBackAndTheTabsFollow() {
        compose.setContent { Tabs() }
        compose.onNodeWithTag(page(0)).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, chosen)
        compose.onNodeWithText("Albums").assertIsSelected()
        compose.onNodeWithText("Songs").assertIsNotSelected()
        compose.onNodeWithTag(page(1)).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, chosen)
        compose.onNodeWithText("Songs").assertIsSelected()
    }

    @Test
    fun aTapGlidesToItsTabThroughThePagesBetween() {
        compose.setContent { Tabs() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Artists").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS / 2L)
        // On its way: Songs has left its place, Artists is not there yet.
        val songs = compose.onNodeWithTag(page(0)).getBoundsInRoot()
        assertTrue(songs.left.value < 0f)
        compose.onNodeWithTag(page(2)).getBoundsInRoot().let { assertTrue(it.left.value > 0f) }
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS * 2L)
        compose.runOnIdle { assertEquals(2, chosen) }
        compose.onNodeWithText("Artists").assertIsSelected()
    }

    @Test
    fun aTabChosenElsewhereWhileThePagesGlideWins() {
        compose.setContent { Tabs() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Artists").performClick()
        // The tap is recorded at once, before the pages have arrived.
        compose.runOnIdle { assertEquals(2, chosen) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS / 3L)
        // Library's icon tapped meanwhile: back to Songs, from wherever the glide had got to.
        chosen = 0
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS * 3L)
        compose.runOnIdle { assertEquals(0, chosen) }
        compose.onNodeWithText("Songs").assertIsSelected()
    }

    @Test
    fun everyTabKeepsItsPlaceAndIsBuiltOnce() {
        compose.setContent { Tabs() }
        compose.onNodeWithTag(page(0)).performScrollToIndex(40)
        compose.onNodeWithText("Songs row 40").assertIsDisplayed()
        compose.onNodeWithTag(page(0)).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Artists").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Songs").performClick()
        compose.waitForIdle()
        // Back where it was, never built again.
        compose.onNodeWithText("Songs row 40").assertIsDisplayed()
        assertEquals(mapOf("Songs" to 1, "Albums" to 1, "Artists" to 1), built)
    }

    @Test
    fun aSidewaysDragThatBelongsToARowStaysTheRows() {
        var dragged = 0f
        compose.setContent {
            Tabs(row = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(Color.Gray)
                        .testTag(ROW)
                        .draggable(rememberDraggableState { dragged += it }, Orientation.Horizontal),
                )
            })
        }
        compose.onNodeWithTag(ROW).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(dragged < 0f)
        assertEquals(0, chosen)
        compose.onNodeWithText("Songs").assertIsSelected()
    }

    @Test
    fun underAHeroTheHeroScrollsAwayFirstThenTheTabsStay() {
        lateinit var outer: ScrollState
        compose.setContent {
            LyrifyTheme {
                outer = rememberScrollState()
                val pager = rememberTabPager(tabs.size, chosen) { chosen = it }
                CollapsingTabs(
                    outer = outer,
                    padding = PaddingValues(top = 64.dp),
                    header = { Box(Modifier.fillMaxWidth().height(300.dp).testTag(HERO)) },
                    tabs = { PagerTabs(pager, tabs) },
                ) {
                    TabPages(pager, tabs, Modifier.fillMaxSize()) { at -> Rows(at) }
                }
            }
        }
        val tabsTop = compose.onNodeWithText("Songs").getBoundsInRoot().top
        compose.onNodeWithTag(page(0)).performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f) }
        compose.waitForIdle()
        // The hero went first, all of it, and the tabs now sit under the top bar's 64 dp.
        compose.runOnIdle { assertEquals(outer.maxValue, outer.value) }
        val tabsNow = compose.onNodeWithText("Songs").getBoundsInRoot().top
        assertTrue("tabs at ${tabsNow.value}, were at ${tabsTop.value}", tabsNow < tabsTop && tabsNow.value >= 64f - 1f)
    }

    /** The tabs over their pages, each page a long list (or [row] above it), counting how often each is built. */
    @Composable
    private fun Tabs(row: (@Composable () -> Unit)? = null) {
        LyrifyTheme {
            val pager = rememberTabPager(tabs.size, chosen) { chosen = it }
            Column {
                PagerTabs(pager, tabs)
                TabPages(pager, tabs, Modifier.fillMaxSize()) { at ->
                    remember { built.merge(tabs[at], 1, Int::plus) }
                    Column {
                        row?.invoke()
                        Rows(at)
                    }
                }
            }
        }
    }

    @Composable
    private fun Rows(at: Int) {
        LazyColumn(Modifier.fillMaxSize().testTag(page(at))) {
            items((0 until ROWS).toList(), key = { it }) { Text("${tabs[at]} row $it", Modifier.height(48.dp)) }
        }
    }

    private fun page(at: Int) = "page ${tabs[at]}"

    private companion object {
        const val ROW = "row"
        const val HERO = "hero"
        const val ROWS = 100
    }
}
