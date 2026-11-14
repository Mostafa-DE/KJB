package app.lyrify.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Favourites' compact head on the phone (user 2026-11-14: the big heart pushed everything down), at a width and font
 * scale set here rather than the phone's, light and dark: the heart tile beside the title and its counts, Shuffle and
 * Play under them on a narrow screen and beside them on a wide one, within 120 dp at font scale 1, and large words
 * wrapped, never cut. Made-up counts only.
 */
@RunWith(AndroidJUnit4::class)
class FavouritesHeadDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val title = context.getString(R.string.lib_favourites)
    private val play = context.getString(R.string.lib_play)
    private val shuffle = context.getString(R.string.lib_shuffle)

    @Test
    fun onANarrowScreenAtFontScaleOneTheHeadStaysWithin120DpWithShuffleAndPlayUnderIt() {
        head(width = 320.dp, fontScale = 1f, dark = false)
        val page = compose.onNodeWithTag(PAGE).getUnclippedBoundsInRoot()
        // Within a pixel: each part is laid out on whole pixels (120 dp can be 337.5 px, laid out as 338).
        val pixel = with(compose.density) { 1.toDp() }
        assertTrue("head ${page.bottom - page.top} tall", page.bottom - page.top <= 120.dp + pixel)
        assertBesideTheTile()
        assertUnderTheWords(placed(play))
        assertUnderTheWords(placed(shuffle))
    }

    @Test
    fun atFontScaleTwoInTheDarkTheWordsWrapAndGrowTheHeadNeverCutNorUnderTheButtons() {
        head(width = 360.dp, fontScale = 2f, dark = true)
        assertBesideTheTile()
        listOf(title, META).forEach { words ->
            val laid = layoutOf(words)
            assertFalse("$words cut", laid.hasVisualOverflow || laid.isLineEllipsized(laid.lineCount - 1))
        }
        assertUnderTheWords(placed(play))
    }

    @Test
    fun onAWideScreenShuffleAndPlaySitOnTheHeadsRow() {
        head(width = 840.dp, fontScale = 1f, dark = true)
        val words = compose.onNodeWithText(META).getUnclippedBoundsInRoot()
        with(compose.density) {
            val button = placed(play)
            assertTrue("Play from ${button.left.toDp()}, the words end at ${words.right}", button.left.toDp() >= words.right)
            assertTrue("Play at ${button.top.toDp()} to ${button.bottom.toDp()}, the words to ${words.bottom}", button.top.toDp() < words.bottom)
        }
        val page = compose.onNodeWithTag(PAGE).getUnclippedBoundsInRoot()
        assertTrue("head ${page.bottom - page.top} tall", page.bottom - page.top <= 80.dp)
    }

    /** The head alone, [width] wide (beyond the phone's own if need be), at [fontScale], light or [dark]. */
    private fun head(width: Dp, fontScale: Float, dark: Boolean) = compose.setContent {
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
        CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            LyrifyTheme {
                Box(Modifier.requiredWidth(width).testTag(PAGE)) {
                    FavouritesHead(title, META) {
                        ListHeader(COUNT, R.plurals.lib_noun_songs, {}, {}, onAddAll = {}, sort = context.getString(ListOrder.START.chip), gutter = 0.dp)
                    }
                }
            }
        }
    }

    /** The title and its counts start after the page's side, the 56 dp tile and the gap beside it. */
    private fun assertBesideTheTile() {
        val page = compose.onNodeWithTag(PAGE).getUnclippedBoundsInRoot()
        val after = page.left + ScreenPadding + FAV_TILE + FAV_HEAD_GAP
        listOf(title, META).forEach { words ->
            val left = compose.onNodeWithText(words).getUnclippedBoundsInRoot().left
            assertTrue("$words from $left, the tile ends at $after", left >= after - 1.dp)
        }
    }

    /** [button] lies wholly under the counts line. */
    private fun assertUnderTheWords(button: Rect) {
        val words = compose.onNodeWithText(META).getUnclippedBoundsInRoot()
        with(compose.density) { assertTrue("button at ${button.top.toDp()}, the words end at ${words.bottom}", button.top.toDp() >= words.bottom - 1.dp) }
    }

    /** The list header's [words] button as placed, in pixels: the header measures each button with and without its word. */
    private fun placed(words: String): Rect {
        val node: SemanticsNode = compose.onAllNodesWithContentDescription(words).fetchSemanticsNodes().single { it.layoutInfo.isPlaced }
        // Unclipped: on a phone narrower than the head is set here, part of it lies past the screen's edge.
        return Rect(node.positionInRoot, node.size.toSize())
    }

    private fun layoutOf(words: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(words).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val PAGE = "page"
        const val COUNT = 120
        const val META = "120 songs · 480 min · 5 more"
    }
}
