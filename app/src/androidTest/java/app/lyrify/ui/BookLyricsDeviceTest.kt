package app.lyrify.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A book's calm lyrics state on the phone (AB2: the panel said "Finding lyrics" while a book played), at a size and font
 * scale set here rather than the phone's, light and dark: the player panel's "No lyrics for books" wraps whole inside a
 * small panel at font scale 2.0, with no Search under it, and the lock screen's lyrics say the same, never "No lyrics yet".
 */
@RunWith(AndroidJUnit4::class)
class BookLyricsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val calm = context.getString(R.string.book_no_lyrics)

    @Test
    fun inTheDarkAtFontScaleTwoTheBooksStateWrapsWholeInsideASmallPanelWithNoSearch() = assertPanelHolds(dark = true)

    @Test
    fun inTheLightAtFontScaleTwoTheBooksStateWrapsWholeInsideASmallPanelWithNoSearch() = assertPanelHolds(dark = false)

    @Test
    fun theLockScreensLyricsSayNoLyricsForBooksAtFontScaleTwo() {
        show(dark = true) { Box(Modifier.requiredWidth(320.dp)) { LockLines(PanelLyrics.Book, positionMs = 0) {} } }
        val laid = layoutOf(calm)
        assertFalse("cut", laid.hasVisualOverflow || laid.isLineEllipsized(laid.lineCount - 1))
        compose.onAllNodesWithText(context.getString(R.string.player_no_lyrics)).assertCountEquals(0)
    }

    /** The panel's book state in a panel smaller than the player gives it on a narrow phone: whole, inside it, and alone. */
    private fun assertPanelHolds(dark: Boolean) {
        show(dark) { Box(Modifier.requiredSize(272.dp, 200.dp).testTag(PANEL)) { PanelState(calm, action = null) } }
        val laid = layoutOf(calm)
        assertFalse("cut", laid.hasVisualOverflow || laid.isLineEllipsized(laid.lineCount - 1))
        val panel = compose.onNodeWithTag(PANEL).getUnclippedBoundsInRoot()
        val words = compose.onNodeWithText(calm).getUnclippedBoundsInRoot()
        assertTrue("$words outside $panel", words.top >= panel.top && words.bottom <= panel.bottom && words.left >= panel.left && words.right <= panel.right)
        val search = context.getString(R.string.player_search_lyrics)
        compose.onAllNodesWithContentDescription(search).assertCountEquals(0)
        compose.onAllNodesWithText(search).assertCountEquals(0)
    }

    /** [content] at font scale 2.0, light or [dark], in KJB's theme. */
    private fun show(dark: Boolean, content: @Composable () -> Unit) = compose.setContent {
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
        CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(LocalDensity.current.density, FONT_SCALE)) {
            LyrifyTheme(content = content)
        }
    }

    private fun layoutOf(words: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(words).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val PANEL = "panel"
        const val FONT_SCALE = 2f
    }
}
