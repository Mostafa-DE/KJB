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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.test.assertIsNotDisplayed
import kotlin.math.tan
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import kotlin.math.abs
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assume.assumeTrue
import androidx.compose.foundation.clickable
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
    fun midSwipeThePillSitsBetweenTheTwoTabsAndAtRestOnOne() {
        var pill = Color.Unspecified
        compose.setContent {
            pill = LocalChromeColors.current.action
            Tabs()
        }
        compose.waitForIdle()
        val songs = compose.onNodeWithText("Songs").getBoundsInRoot()
        val albums = compose.onNodeWithText("Albums").getBoundsInRoot()
        val density = compose.density
        // The 2 dp between the two tabs, at the pill's middle height, and the middle of Songs.
        val gap = with(density) { Offset(((songs.right + albums.left) / 2).toPx(), ((songs.top + songs.bottom) / 2).toPx()) }
        val onSongs = with(density) { Offset(((songs.left + songs.right) / 2).toPx(), ((songs.top + songs.bottom) / 2).toPx()) }
        fun colourAt(at: Offset): Color = compose.onRoot().captureToImage().toPixelMap()[at.x.toInt(), at.y.toInt()]
        // At rest the pill is on Songs, the gap is the page's.
        assertNear(pill, colourAt(onSongs), expect = true)
        assertNear(pill, colourAt(gap), expect = false)
        // Half a page under the finger, held: the pill spans the gap between Songs and Albums.
        compose.onRoot().performTouchInput {
            down(Offset(width * 0.8f, height * 0.6f))
            moveBy(Offset(-width * 0.25f, 0f))
            moveBy(Offset(-width * 0.25f, 0f))
        }
        compose.waitForIdle()
        assertNear(pill, colourAt(gap), expect = true)
        compose.onRoot().performTouchInput { up() }
    }

    /** Whether [actual] is the pill's colour, within what antialiasing changes. */
    private fun assertNear(pill: Color, actual: Color, expect: Boolean) {
        val near = abs(pill.red - actual.red) < 0.03f && abs(pill.green - actual.green) < 0.03f && abs(pill.blue - actual.blue) < 0.03f
        assertEquals("pill ${pill.toArgb().toUInt().toString(16)}, found ${actual.toArgb().toUInt().toString(16)}", expect, near)
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

    // Phone test of build 603c4bf: a fast flick sometimes did not change tabs, and had to be done twice

    @Test
    fun aShortQuickFlickAlwaysMovesOneTab() {
        compose.setContent { Tabs() }
        flick(toNext = true)
        compose.runOnIdle { assertEquals(1, chosen) }
        flick(toNext = true)
        compose.runOnIdle { assertEquals(2, chosen) }
        flick(toNext = false)
        compose.runOnIdle { assertEquals(1, chosen) }
        compose.onNodeWithText("Albums").assertIsSelected()
    }

    @Test
    fun aQuickFlickSlowingBeforeTheLiftStillMovesOneTab() {
        compose.setContent { Tabs() }
        compose.onRoot().performTouchInput {
            val y = height * 0.6f
            // A third of the width, the finger slowing to a speed Compose's own pager read as a drag (under 400 dp/s).
            swipeWithVelocity(Offset(width * 0.65f, y), Offset(width * 0.35f, y), endVelocity = SLOWING_DP_S.dp.toPx(), durationMillis = SLOWING_MS)
        }
        compose.waitForIdle()
        assertEquals(1, chosen)
    }

    @Test
    fun aSecondFlickDuringTheSettleMovesOnAgain() {
        compose.setContent { Tabs() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        flick(toNext = true)
        // The pages are still on their way to Albums when the second flick lands.
        compose.mainClock.advanceTimeBy(SETTLING_MS)
        flick(toNext = true)
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        compose.runOnIdle { assertEquals(2, chosen) }
        compose.onNodeWithText("Artists").assertIsSelected()
    }

    @Test
    fun aFlickDriftingTwentyFiveDegreesMovesTheTabsNotTheList() {
        compose.setContent { Tabs() }
        compose.onRoot().performTouchInput {
            val start = Offset(width * 0.65f, height * 0.45f)
            val across = width * 0.3f
            swipe(start, Offset(start.x - across, start.y + across * tan(Math.toRadians(DRIFT_DEGREES)).toFloat()), durationMillis = FLICK_MS)
        }
        compose.waitForIdle()
        assertEquals(1, chosen)
        flick(toNext = false)
        compose.waitForIdle()
        // Back on Songs: its list never scrolled, its first row still at the top.
        val list = compose.onNodeWithTag(page(0)).getBoundsInRoot()
        assertEquals(list.top.value, compose.onNodeWithText("Songs row 0").getBoundsInRoot().top.value, 1f)
    }

    @Test
    fun anUprightDragStillScrollsTheListAndLeavesTheTabs() {
        compose.setContent { Tabs() }
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.5f, height * 0.85f), Offset(width * 0.55f, height * 0.3f), durationMillis = UPRIGHT_MS) }
        compose.waitForIdle()
        assertEquals(0, chosen)
        compose.onNodeWithText("Songs row 0").assertIsNotDisplayed()
    }

    // Review of cef1ddc

    @Test
    fun anUprightDragDuringTheSettleScrollsTheListAndThePagesStopOnTheNearerTab() {
        compose.setContent { Tabs() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        flick(toNext = true)
        compose.mainClock.advanceTimeBy(SETTLING_MS)
        compose.onRoot().performTouchInput { swipe(Offset(width * 0.5f, height * 0.85f), Offset(width * 0.5f, height * 0.35f), durationMillis = UPRIGHT_MS) }
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // Caught, then left to the list: the pages rest on a whole tab, and that tab's list scrolled.
        val shown = chosen
        assertEquals(0f, compose.onNodeWithTag(page(shown)).getBoundsInRoot().left.value, 1f)
        compose.onNodeWithText("${tabs[shown]} row 0").assertIsNotDisplayed()
    }

    @Test
    fun aTapDuringTheSettleStopsItOnTheNearerTabAndTapsNothingUnderIt() {
        var tapped = 0
        compose.setContent { Tabs(row = { Box(Modifier.fillMaxWidth().height(64.dp).testTag(ROW).clickable { tapped++ }) }) }
        compose.waitForIdle()
        val rowY = compose.onAllNodesWithTag(ROW)[0].getBoundsInRoot().let { (it.top + it.bottom) / 2 }
        compose.mainClock.autoAdvance = false
        flick(toNext = true)
        compose.mainClock.advanceTimeBy(SETTLING_MS)
        compose.onRoot().performTouchInput { click(Offset(width * 0.5f, rowY.toPx())) }
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, tapped)
        assertEquals(0f, compose.onNodeWithTag(page(chosen)).getBoundsInRoot().left.value, 1f)
    }

    @Test
    fun pagesRestoredBetweenTwoTabsLandOnAWholeTab() {
        val restore = StateRestorationTester(compose)
        restore.setContent { Tabs() }
        compose.waitForIdle()
        // Held a third of the way to Albums, then the phone turns.
        compose.onRoot().performTouchInput {
            down(Offset(width * 0.8f, height * 0.6f))
            moveBy(Offset(-width * 0.17f, 0f))
            moveBy(Offset(-width * 0.17f, 0f))
        }
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(0f, compose.onNodeWithTag(page(0)).getBoundsInRoot().left.value, 1f)
        compose.onNodeWithText("Songs").assertIsSelected()
    }

    @Test
    fun aTabTappedWhileAFingerDragsThePagesWinsOverTheFinger() {
        compose.setContent { Tabs() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(0, Offset(width * 0.8f, height * 0.6f))
            moveBy(0, Offset(-width * 0.2f, 0f))
        }
        compose.onNodeWithText("Artists").performTouchInput {
            down(1, center)
            up(1)
        }
        compose.onRoot().performTouchInput {
            moveBy(0, Offset(-width * 0.1f, 0f))
            up(0)
        }
        compose.waitForIdle()
        assertEquals(2, chosen)
        compose.onNodeWithText("Artists").assertIsSelected()
    }

    @Test
    fun talkBackPagesTheTabsOneAtATime() {
        var pill = Color.Unspecified
        compose.setContent {
            pill = LocalChromeColors.current.action
            Tabs()
        }
        val pages = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.PageRight))
        pages.performSemanticsAction(SemanticsActions.PageRight)
        compose.waitForIdle()
        assertEquals(1, chosen)
        // The pill went with it, as for any other change of tab: it sits on Albums now.
        val albums = compose.onNodeWithText("Albums").getBoundsInRoot()
        val onAlbums = with(compose.density) { Offset((albums.left + 6.dp).toPx(), ((albums.top + albums.bottom) / 2).toPx()) }
        assertNear(pill, compose.onRoot().captureToImage().toPixelMap()[onAlbums.x.toInt(), onAlbums.y.toInt()], expect = true)
        pages.performSemanticsAction(SemanticsActions.ScrollBy) { it(500f, 0f) }
        compose.waitForIdle()
        assertEquals(2, chosen)
        pages.performSemanticsAction(SemanticsActions.PageLeft)
        compose.waitForIdle()
        assertEquals(1, chosen)
    }

    @Test
    fun aSecondFingerTapsNothingWhileTheFirstDragsThePages() {
        var tapped = 0
        compose.setContent { Tabs(row = { Box(Modifier.fillMaxWidth().height(64.dp).testTag(ROW).clickable { tapped++ }) }) }
        compose.waitForIdle()
        val row = compose.onAllNodesWithTag(ROW)[0].getBoundsInRoot()
        compose.onRoot().performTouchInput {
            down(0, Offset(width * 0.8f, height * 0.7f))
            moveBy(0, Offset(-width * 0.2f, 0f))
            down(1, Offset(width * 0.2f, ((row.top + row.bottom) / 2).toPx()))
            up(1)
            moveBy(0, Offset(-width * 0.2f, 0f))
            up(0)
        }
        compose.waitForIdle()
        assertEquals(0, tapped)
        assertEquals(1, chosen)
    }

    @Test
    fun aSwipeFromTheSystemsGestureEdgeIsNoTabFlick() {
        var edge = 0
        chosen = 1
        compose.setContent {
            edge = WindowInsets.systemGestures.getLeft(LocalDensity.current, LayoutDirection.Ltr)
            Tabs()
        }
        compose.waitForIdle()
        // Only with gesture navigation, where the edge belongs to the system's back.
        assumeTrue(edge > 0)
        compose.onRoot().performTouchInput { swipe(Offset(edge / 2f, height * 0.6f), Offset(width * 0.6f, height * 0.6f), durationMillis = FLICK_MS) }
        compose.waitForIdle()
        assertEquals(1, chosen)
    }

    @Test
    fun aSidewaysFlickStartingOnARowMovesTheTabsAsAndroidsPagersDo() {
        // The pages decide before what is on them, parent first, as ViewPager does: a row with a sideways action of
        // its own would have to keep its gesture from them. No row in KJB's tabs has one.
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
        compose.onNodeWithTag(ROW).performTouchInput { swipe(Offset(width * 0.65f, centerY), Offset(width * 0.35f, centerY), durationMillis = FLICK_MS) }
        compose.waitForIdle()
        assertEquals(1, chosen)
        assertEquals(0f, dragged, 0f)
    }

    @Test
    fun aTabRowScrolledByHandStaysThereUntilThePagesMove() {
        compose.setContent { Tabs(MANY) }
        compose.waitForIdle()
        val atRest = compose.onNodeWithText("Songs").getBoundsInRoot().left
        // The row scrolled by hand toward its end, then left alone: it stays where the finger left it.
        compose.onNodeWithTag(TAB_ROW).performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.3f) }
        compose.waitForIdle()
        val scrolled = compose.onNodeWithText("Songs").getBoundsInRoot().left
        assertTrue("Songs at ${scrolled.value}, was at ${atRest.value}", scrolled < atRest)
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        compose.waitForIdle()
        assertEquals(scrolled, compose.onNodeWithText("Songs").getBoundsInRoot().left)
        // The pages move: the row brings the pill back to the middle, as far as it goes.
        flick(toNext = true)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, chosen) }
        assertEquals(atRest, compose.onNodeWithText("Songs").getBoundsInRoot().left)
    }

    @Test
    fun tabsAddedLaterStillBringThePillIntoView() {
        var names by mutableStateOf(tabs)
        compose.setContent { Tabs(names) }
        compose.waitForIdle()
        names = MANY
        compose.waitForIdle()
        chosen = MANY.lastIndex
        compose.waitForIdle()
        val row = compose.onNodeWithTag(TAB_ROW).getBoundsInRoot()
        val last = compose.onNodeWithText(MANY.last()).getBoundsInRoot()
        assertTrue("${MANY.last()} from ${last.left.value} to ${last.right.value}, row to ${row.right.value}", last.left >= row.left && last.right <= row.right)
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
                    TabPages(pager, tabs, Modifier.fillMaxSize()) { at -> Rows(tabs[at], page(at)) }
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

    /** A quick flick across the pages, a third of the width in 80 ms, well away from the screen's edges. */
    private fun flick(toNext: Boolean) {
        compose.onRoot().performTouchInput {
            val y = height * 0.6f
            val near = width * 0.35f
            val far = width * 0.65f
            swipe(Offset(if (toNext) far else near, y), Offset(if (toNext) near else far, y), durationMillis = FLICK_MS)
        }
    }

    /** The tabs ([names]) over their pages, each page a long list (or [row] above it), counting how often each is built. */
    @Composable
    private fun Tabs(names: List<String> = tabs, row: (@Composable () -> Unit)? = null) {
        LyrifyTheme {
            val pager = rememberTabPager(names.size, chosen) { chosen = it }
            Column {
                PagerTabs(pager, names, Modifier.testTag(TAB_ROW))
                TabPages(pager, names, Modifier.fillMaxSize()) { at ->
                    remember { built.merge(names[at], 1, Int::plus) }
                    Column {
                        row?.invoke()
                        Rows(names[at], page(at))
                    }
                }
            }
        }
    }

    @Composable
    private fun Rows(name: String, tag: String) {
        LazyColumn(Modifier.fillMaxSize().testTag(tag)) {
            items((0 until ROWS).toList(), key = { it }) { Text("$name row $it", Modifier.height(48.dp)) }
        }
    }

    private fun page(at: Int) = "page $at"

    private companion object {
        const val ROW = "row"
        const val TAB_ROW = "tab row"

        /** More tabs than fit across a phone. */
        val MANY = listOf("Songs", "Albums", "Artists", "Folders", "Genres", "Years", "Composers", "Playlists")
        const val HERO = "hero"
        const val ROWS = 100

        /** A quick flick: a third of the width in this long. */
        const val FLICK_MS = 80L

        /** A flick slowing to this speed at the lift, over this long. */
        const val SLOWING_DP_S = 150f
        const val SLOWING_MS = 120L

        /** Into the first flick's settle, and well past the second's. */
        const val SETTLING_MS = 60L
        const val SETTLED_MS = 2_000L

        const val DRIFT_DEGREES = 25.0
        const val UPRIGHT_MS = 300L
    }
}
