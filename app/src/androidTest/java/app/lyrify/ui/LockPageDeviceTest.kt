package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
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
 * a scroll never seeks nor closes, nor does a second finger's tap meanwhile; a tap on a line seeks there; a swipe up or
 * down of 80 dp from where the finger touched, on the header band or the controls band, closes. On a short window the
 * lyrics keep their room and the page scrolls to the controls. Made-up lines only.
 */
@RunWith(AndroidJUnit4::class)
class LockPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = (0 until 60).map { LrcLine(it * 5_000L, "Paper lantern verse ${it + 1}") }
    private val taps = mutableListOf<LrcLine>()
    private var closes = 0

    private fun page(height: Dp? = null) = compose.setContent {
        Box(if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) {
            LockPage(
                onClose = { closes++ },
                top = { Text("Harbour Demo", Modifier.testTag(TOP)) },
                lyrics = { Box(Modifier.fillMaxSize().testTag(LYRICS)) { LockLines(PanelLyrics.Synced(lines), positionMs = 0) { taps += it } } },
                bottom = { Text("Controls Demo", Modifier.fillMaxWidth().height(96.dp).testTag(BOTTOM)) },
            )
        }
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
    fun aSecondFingersTapWhileOneScrollsNeverSeeks() {
        page()
        compose.onNodeWithTag(LYRICS).performTouchInput {
            down(0, Offset(centerX, height * 0.8f))
            moveTo(0, Offset(centerX, height * 0.6f))
            moveTo(0, Offset(centerX, height * 0.4f))
            down(1, Offset(centerX, height * 0.5f))
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(emptyList<LrcLine>(), taps)
            assertEquals(0, closes)
        }
    }

    @Test
    fun aSwipeOfTheCloseDistanceFromTheTouchCloses() {
        page()
        // 81 dp in all from where the finger touched: closes, the touch slop counted in.
        compose.onNodeWithTag(TOP).performTouchInput { swipe(center, center + Offset(0f, 81.dp.toPx()), durationMillis = 300) }
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun onAShortWindowTheLyricsKeepTheirRoomAndThePageScrollsToTheControls() {
        page(height = 320.dp)
        val lyrics = compose.onNodeWithTag(LYRICS).getBoundsInRoot()
        assertTrue(lyrics.bottom - lyrics.top > LYRICS_FADE * 2)
        compose.onNodeWithContentDescription("0:00, Paper lantern verse 1").assertIsDisplayed()
        compose.onNodeWithTag(TOP).assertIsDisplayed()
        compose.onNodeWithTag(BOTTOM).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closes) }
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
