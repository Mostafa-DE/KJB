package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.settings.DockTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers & tags' top tabs on the phone, the real page on the debug build's own Covers & tags store (only read here): a
 * swipe across the pages moves To review, Done and Skipped along as Library's and Favourites' tabs do, the tab taking
 * the chosen look, and To review's own list is still there when the pages come back to it.
 */
@RunWith(AndroidJUnit4::class)
class CoversTagsPagerDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val edges = GestureEdges()

    @Test
    fun aSwipeMovesBetweenToReviewDoneAndSkippedAndToReviewKeepsItsList() {
        compose.setContent {
            LyrifyTheme {
                ReadGestureEdges(edges)
                CompositionLocalProvider(
                    LocalToasts provides remember { ToastState() },
                    LocalNavigator provides remember { Navigator(NavState.start(DockTab.TOOLS).open(Page.CoversTags)) },
                ) { CoversTagsPage() }
            }
        }
        compose.waitForIdle()
        tab(R.string.tags_to_review).assertIsSelected()
        swipePages(toNext = true)
        tab(R.string.tags_done).assertIsSelected()
        tab(R.string.tags_to_review).assertIsNotSelected()
        swipePages(toNext = true)
        tab(R.string.tags_skipped).assertIsSelected()
        swipePages(toNext = false)
        swipePages(toNext = false)
        tab(R.string.tags_to_review).assertIsSelected()
        // Its own first row, kept while the other tabs showed.
        compose.onNodeWithText(context.getString(R.string.tags_check)).assertExists()
    }

    /** The top tab named [title], whatever its count. */
    private fun tab(@StringRes title: Int): SemanticsNodeInteraction = compose.onNode(hasText(context.getString(title), substring = true) and isSelectable())

    /** A finger across the pages, low on the screen, well under the tabs, clear of the system's gesture edges. */
    private fun swipePages(toNext: Boolean) {
        compose.onRoot().performTouchInput { swipeAcross(edges, toNext, y = height * 0.75f, durationMillis = SWIPE_MS) }
        compose.waitForIdle()
    }

    private companion object {
        const val SWIPE_MS = 300L
    }
}
