package app.lyrify.ui

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.settings.DockTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The tour's overlay on the phone, over a made-up shell with the targets a real one marks (Library's tabs, a song row,
 * the dock's Search and Tools), the test clock held (the halo breathes for ever): TalkBack through the semantics tree
 * (focus on the card's title, nothing under the dimmed layer reachable, Skip on every step, the card there while a
 * control is missing), the count of the steps that apply, the card at font scale 2.0 on a short screen, and a song row
 * under the dock scrolled into view; and from Settings, over the shell's own page moves, Library's own content under the
 * cut-outs and after Skip. Made-up names only. Not run in the lane: the orchestrator runs it in a phone session.
 */
@RunWith(AndroidJUnit4::class)
class TourOverlayDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun words(id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * The tour started over [shell] (the whole screen as [phone] sets it, if given), then frames until its first stop has
     * found its control and the card faded in.
     */
    private fun startOver(phone: DeviceConfigurationOverride? = null, songs: Boolean = true, shell: @Composable () -> Unit): Tour {
        val tour = Tour()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val app: @Composable () -> Unit = {
                LyrifyTheme {
                    CompositionLocalProvider(LocalToasts provides remember { ToastState() }, LocalTour provides tour) {
                        TourHost {
                            TourShell(remember { Navigator(NavState.start(DockTab.LIBRARY)) }, songs = songs, bar = false, ownSong = false)
                            shell()
                        }
                    }
                }
            }
            if (phone == null) app() else DeviceConfigurationOverride(phone, app)
        }
        compose.runOnIdle { tour.start() }
        settle()
        return tour
    }

    private fun settle(ms: Long = SETTLE_MS) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun theCardTitleTakesTheFocusOnEveryStep() {
        startOver { Shell() }
        compose.onNodeWithText(words(R.string.tour_tabs_title)).assertIsFocused().assert(isHeading())
        compose.onNodeWithText(words(R.string.tour_next)).performClick()
        // Play a song: the row; its Now bar and the lyrics are not there, so the tour passes on to Search after them.
        settle()
        compose.onNodeWithText(words(R.string.tour_play_title)).assertIsFocused()
        compose.onNodeWithText(words(R.string.tour_next)).performClick()
        settle(PASS_MS)
        compose.onNodeWithText(words(R.string.tour_search_title)).assertIsFocused()
    }

    @Test
    fun nothingUnderTheDimmedLayerIsReachable() {
        startOver { Shell() }
        // The shell's words and buttons are gone from the tree; only the card and the cut-out's own tap remain.
        compose.onNodeWithText(BEHIND).assertDoesNotExist()
        compose.onNodeWithTag(BEHIND_BUTTON).assertDoesNotExist()
        val clickable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        }
        assertEquals(listOf(words(R.string.ob_skip_tour), words(R.string.tour_next)).sorted(), clickable.filterNotNull().sorted())
    }

    @Test
    fun theTitleAndSentenceComeFirstAndTheCardIsOneGroup() {
        startOver { Shell() }
        compose.onNodeWithText(words(R.string.tour_tabs_title)).assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
        compose.onNodeWithText(words(R.string.tour_tabs_body)).assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
        // The card is one group, the one holding its title, Skip and Next (its scrolling words are a group inside it).
        val card = SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true) and
            hasAnyDescendant(hasText(words(R.string.tour_tabs_title))) and hasAnyDescendant(hasText(words(R.string.tour_next)))
        compose.onNode(card).assertExists()
    }

    @Test
    fun skipIsReachableOnEveryStepAndEndsTheTour() {
        val tour = startOver { Shell() }
        // Your music, Play a song (the row), Find anything, then More tools: the Now bar and the lyrics are passed.
        var cards = 0
        while (compose.runOnIdle { tour.state?.last == false }) {
            compose.onNodeWithContentDescription(words(R.string.ob_skip_tour)).assertIsDisplayed().assertHasClickAction()
            compose.onNodeWithText(words(R.string.tour_next)).performClick()
            settle(PASS_MS)
            cards++
        }
        assertEquals(3, cards)
        // Nothing plays: Synced lyrics does not apply, so the last of four.
        compose.onNodeWithText(words(R.string.step_count, 4, 4)).assertIsDisplayed()
        compose.onNodeWithText(words(R.string.tour_done)).assertIsDisplayed()
        compose.onNodeWithContentDescription(words(R.string.ob_skip_tour)).performClick()
        settle()
        compose.runOnIdle {
            assertNull(tour.state)
            assertTrue(tour.ended)
        }
        // The shell is back for TalkBack.
        compose.onNodeWithText(BEHIND).assertExists()
    }

    @Test
    fun atFontScale2OnAShortScreenTheCardFitsBesideItsTarget() {
        startOver(DeviceConfigurationOverride.FontScale(2f) then DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 480.dp))) { Shell() }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val tabs = compose.onNodeWithTag(TABS, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText(words(R.string.tour_tabs_title)).getUnclippedBoundsInRoot()
        val next = compose.onNodeWithText(words(R.string.tour_next)).assertIsDisplayed().getUnclippedBoundsInRoot()
        // Below the tabs, never over them, and inside the screen with its button in reach.
        assertTrue("title ${title.top} under tabs ${tabs.bottom}", title.top >= tabs.bottom)
        assertTrue("Next ${next.bottom} on screen ${root.bottom}", next.bottom <= root.bottom)
        assertTrue(next.right <= root.right)
    }

    @Test
    fun aSongUnderTheDockIsScrolledIntoViewBeforeTheCardPointsAtIt() {
        val tour = startOver { Shell(songsBelowFold = true) }
        compose.onNodeWithText(words(R.string.tour_next)).performClick()
        settle(PASS_MS)
        compose.onNodeWithText(words(R.string.tour_play_title)).assertIsDisplayed()
        val song = compose.onNodeWithTag(SONG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val dock = compose.onNodeWithTag(SEARCH, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("song ${song.bottom} above the dock ${dock.top}", song.bottom <= dock.top)
        compose.runOnIdle { assertEquals(TourStop.SONG, tour.state?.stop) }
    }

    @Test
    fun aPhoneWithNoSongsCountsOnlyTheStepsThatApply() {
        val tour = startOver(songs = false) { Shell(songs = false) }
        compose.onNodeWithText(words(R.string.step_count, 1, 3)).assertIsDisplayed()
        compose.onNodeWithText(words(R.string.tour_next)).performClick()
        settle()
        // No song, no Now bar, no song in the player: from step 1 straight to Search, the second of three.
        compose.runOnIdle { assertEquals(TourStop.SEARCH, tour.state?.stop) }
        compose.onAllNodesWithText(words(R.string.tour_play_title)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithText(words(R.string.step_count, 2, 3)).assertIsDisplayed()
    }

    @Test
    fun whileAControlIsMissingTheCardStaysWithSkipAndTheFocus() {
        // The library has songs, but no row is on screen yet.
        val tour = startOver(songs = true) { Shell(songs = false) }
        compose.onNodeWithText(words(R.string.tour_next)).performClick()
        compose.mainClock.advanceTimeBy(WAITING_MS)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(TourStop.SONG, tour.state?.stop) }
        compose.onNodeWithText(words(R.string.tour_play_title)).assertIsDisplayed().assertIsFocused()
        compose.onNodeWithContentDescription(words(R.string.ob_skip_tour)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(words(R.string.tour_next)).assertIsDisplayed()
        // The card waits in the middle of the screen.
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText(words(R.string.tour_play_title)).getUnclippedBoundsInRoot()
        assertTrue("title ${title.top} in the middle of ${root.bottom}", title.top > root.bottom / 4 && title.top < root.bottom * 3 / 4)
        // The row never comes: the stop is passed, and the Now bar, with no song played, at once.
        settle(PASS_MS)
        compose.runOnIdle { assertEquals(TourStop.SEARCH, tour.state?.stop) }
    }

    @Test
    fun fromSettingsTheTourPointsAtLibrarysOwnTabsAndSongAndLeavesLibraryWholeAfterSkip() {
        val tour = Tour()
        val nav = Navigator(NavState.start(DockTab.LIBRARY))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LyrifyTheme {
                CompositionLocalProvider(LocalToasts provides remember { ToastState() }, LocalTour provides tour) {
                    TourHost { PagedShell(nav) }
                }
            }
        }
        settle()
        // Library shown once (its root kept, as on the phone), then Tools > Settings, each move at rest.
        compose.runOnIdle { nav.select(DockTab.TOOLS) }
        settle()
        compose.runOnIdle { nav.open(Page.Settings) }
        settle()
        // Show tour, as Settings' row does it: Settings closes and the tour starts in the same tap.
        compose.runOnIdle {
            nav.back()
            tour.start()
        }
        settle(PASS_MS)
        compose.runOnIdle { assertEquals(TourStop.TABS, tour.state?.stop) }
        // Library's own tabs show in the cut-out: Library is drawn, not a blank page.
        assertEquals(TABS_FILL.toArgb(), pixelAt(tabsAt))
        compose.onNodeWithText(words(R.string.tour_next)).performClick()
        settle(PASS_MS)
        compose.runOnIdle { assertEquals(TourStop.SONG, tour.state?.stop) }
        assertEquals(SONG_FILL.toArgb(), pixelAt(songAt))
        compose.onNodeWithContentDescription(words(R.string.ob_skip_tour)).performClick()
        settle(PASS_MS)
        compose.runOnIdle { assertNull(tour.state) }
        // Skipped: Library whole, its tabs and its song as they were, without a tab switch.
        assertEquals(TABS_FILL.toArgb(), pixelAt(tabsAt))
        assertEquals(SONG_FILL.toArgb(), pixelAt(songAt))
        assertEquals(LIBRARY_FILL.toArgb(), pixelAt(libraryAt))
    }

    /** Where Library's tabs, its song row and its own ground are on screen, as last placed. */
    private var tabsAt = Rect.Zero
    private var songAt = Rect.Zero
    private var libraryAt = Rect.Zero

    /** The colour on screen at the middle of [at]. */
    private fun pixelAt(at: Rect): Int {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        return shot.getPixel(at.center.x.roundToInt(), at.center.y.roundToInt())
    }

    /**
     * The shell's own page layer and its moves ([PagesBack], [PageLayers]) with made-up pages: Library's root (its tabs and
     * a song row, each of its own colour, on Library's ground), Tools' root and Settings, a different colour each, and the
     * tour lent the layer it rests on, as AppShell does.
     */
    @Composable
    private fun PagedShell(nav: Navigator) {
        val pages = remember { SeekableTransitionState(pageLayerOf(nav.state)) }
        val swipe = remember { BackSwipe() }
        TourShell(nav, songs = true, bar = false, ownSong = false) { pages.currentState.takeIf { it == pages.targetState } }
        CompositionLocalProvider(LocalNavigator provides nav) {
            PagesBack(nav, pages, swipe, reduced = false)
            PageLayers(pages, swipe, rtl = false, reduced = false) { layer ->
                when {
                    layer.tab == DockTab.LIBRARY && layer.stack.isEmpty() -> Column(Modifier.fillMaxSize().background(LIBRARY_FILL).windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.fillMaxWidth().padding(top = 48.dp).height(48.dp).background(TABS_FILL).onGloballyPositioned { tabsAt = it.boundsInRoot() }.tourTarget(TourStop.TABS)) { Text("Songs") }
                        Spacer(Modifier.height(96.dp).fillMaxWidth().onGloballyPositioned { libraryAt = it.boundsInRoot() })
                        Box(Modifier.fillMaxWidth().height(64.dp).background(SONG_FILL).onGloballyPositioned { songAt = it.boundsInRoot() }.tourTarget(TourStop.SONG, "Play Paper Harbour") {})
                    }
                    layer.stack.isEmpty() -> Box(Modifier.fillMaxSize().background(TOOLS_FILL)) { Text(layer.tab.name) }
                    else -> Box(Modifier.fillMaxSize().background(Color.White)) { Text("Settings") }
                }
            }
        }
    }

    /**
     * A made-up shell: Library's tabs at the top, words and a button behind, maybe a song row (or one far down a list,
     * under the dock), and the dock's Search and Tools at the bottom; clear of the system's bars, as the real shell's
     * are, since the tour points only at controls clear of them.
     */
    @Composable
    private fun Shell(songs: Boolean = true, songsBelowFold: Boolean = false) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(top = 48.dp).height(48.dp).testTag(TABS).tourTarget(TourStop.TABS)) { Text("Songs") }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(BEHIND, Modifier.padding(16.dp))
                    LyrifyButton("Shuffle", onClick = {}, modifier = Modifier.testTag(BEHIND_BUTTON))
                    if (songsBelowFold) Spacer(Modifier.height(900.dp))
                    if (songs) Text("Paper Harbour", Modifier.fillMaxWidth().height(64.dp).testTag(SONG).tourTarget(TourStop.SONG, "Play Paper Harbour") {})
                    Spacer(Modifier.height(200.dp))
                }
                Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(80.dp, 56.dp).testTag(SEARCH).tourTarget(TourStop.SEARCH))
                    Box(Modifier.size(80.dp, 56.dp).tourTarget(TourStop.TOOLS))
                }
            }
        }
    }

    private companion object {
        const val BEHIND = "Harbour Glass, 12 songs"
        const val BEHIND_BUTTON = "behind"
        const val TABS = "tabs"
        const val SONG = "song"
        const val SEARCH = "search"

        /** Enough for a stop to find its control and the card to fade in. */
        const val SETTLE_MS = 600L

        /** Enough for stops with nothing to point at to wait and be passed. */
        const val PASS_MS = 4_000L

        /** Well inside a stop's wait for its control. */
        const val WAITING_MS = 400L

        val LIBRARY_FILL = Color(0xFF1B5E20)
        val TABS_FILL = Color(0xFF0D47A1)
        val SONG_FILL = Color(0xFFB71C1C)
        val TOOLS_FILL = Color(0xFF4A148C)
    }
}
