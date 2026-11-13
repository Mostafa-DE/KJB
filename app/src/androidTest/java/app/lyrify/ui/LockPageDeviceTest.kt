package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.lyrics.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lock screen lyrics' page (user feedback 2026-11-13, lane LK2): the lyrics scroll by finger as the player's do, and
 * a scroll never seeks nor closes; a tap on a line seeks there; a swipe up or down on the header band or the controls
 * band closes. Made-up lines only.
 */
@RunWith(AndroidJUnit4::class)
class LockPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = (0 until 60).map { LrcLine(it * 5_000L, "Paper lantern verse ${it + 1}") }
    private val taps = mutableListOf<LrcLine>()
    private var closes = 0

    private fun page() = compose.setContent {
        LockPage(
            onClose = { closes++ },
            top = { Text("Harbour Demo", Modifier.testTag(TOP)) },
            lyrics = { Box(Modifier.fillMaxSize().testTag(LYRICS)) { LockLines(PanelLyrics.Synced(lines), positionMs = 0) { taps += it } } },
            bottom = { Text("Controls Demo", Modifier.fillMaxWidth().height(96.dp).testTag(BOTTOM)) },
        )
    }

    @Test
    fun aSwipeOverTheLyricsScrollsThemAndNeverClosesNorSeeks() {
        page()
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        assertEquals(0f, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        compose.onNodeWithTag(LYRICS).performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertEquals(0, closes)
            assertEquals(emptyList<LrcLine>(), taps)
        }
        compose.onNodeWithTag(LYRICS).performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(0, closes)
            assertEquals(emptyList<LrcLine>(), taps)
        }
    }

    @Test
    fun aTapOnALineSeeksThere() {
        page()
        compose.onNodeWithContentDescription("0:10, Paper lantern verse 3").performClick()
        compose.runOnIdle { assertEquals(listOf(lines[2]), taps) }
    }

    @Test
    fun aSwipeUpOrDownOnEitherBandCloses() {
        page()
        compose.onNodeWithTag(TOP).performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, closes) }
        compose.onNodeWithTag(BOTTOM).performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(2, closes)
            assertEquals(emptyList<LrcLine>(), taps)
        }
    }

    private companion object {
        const val TOP = "top"
        const val LYRICS = "lyrics"
        const val BOTTOM = "bottom"
    }
}
