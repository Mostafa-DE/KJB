package app.lyrify.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lock screen's progress bar (user feedback 2026-11-13, point 4) under a real finger: only a deliberate sideways drag
 * seeks, to where the finger lifts; a tap, a touch that barely moves, or a swipe up (which closes the screen) never does,
 * and a second finger never moves it. TalkBack finds a slider that reads the time and seeks, and hears a drag once, where
 * it lands. The bar sits in the page's controls band, whose swipe closes, and the song goes where it seeks.
 */
@RunWith(AndroidJUnit4::class)
class LockSeekBarDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val seeks = mutableListOf<Long>()
    private var swipes = 0
    private var played by mutableLongStateOf(PLAYED)

    private fun bar(): SemanticsNodeInteraction {
        compose.setContent { OnTheLockScreen() }
        return compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
    }

    @Test
    fun aTapOnTheBarNeverSeeks() {
        bar().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
    }

    @Test
    fun aTouchThatBarelyMovesNeverSeeks() {
        bar().performTouchInput {
            down(center)
            moveBy(Offset(viewConfiguration.touchSlop * 0.8f, 0f))
            moveBy(Offset(-viewConfiguration.touchSlop * 0.5f, viewConfiguration.touchSlop * 0.5f))
            up()
        }
        compose.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
    }

    @Test
    fun aSidewaysDragSeeksWhereTheFingerLifts() {
        bar().performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width * 0.75f, centerY), durationMillis = 400) }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertEquals(LENGTH * 0.75, seeks.single().toDouble(), LENGTH * 0.02)
            assertEquals(0, swipes)
        }
    }

    @Test
    fun aSlantedDragNeverSeeks() {
        bar().performTouchInput { swipe(center, center + Offset(viewConfiguration.touchSlop * 3, -viewConfiguration.touchSlop * 3), durationMillis = 300) }
        compose.runOnIdle { assertEquals(emptyList<Long>(), seeks) }
    }

    @Test
    fun aSwipeUpFromTheBarClosesTheScreenAndNeverSeeks() {
        bar().performTouchInput { swipe(center, center + Offset(viewConfiguration.touchSlop, -200.dp.toPx()), durationMillis = 300) }
        compose.runOnIdle {
            assertEquals(emptyList<Long>(), seeks)
            assertEquals(1, swipes)
        }
    }

    @Test
    fun onlyTheFingerThatStartedTheSeekMovesIt() {
        bar().performTouchInput {
            down(0, Offset(width * 0.1f, centerY))
            moveTo(0, Offset(width * 0.3f, centerY))
            down(1, Offset(width * 0.9f, centerY))
            moveTo(1, Offset(width * 0.95f, centerY))
            up(0)
            moveTo(1, Offset(width * 0.6f, centerY))
            up(1)
        }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertEquals(LENGTH * 0.3, seeks.single().toDouble(), LENGTH * 0.02)
        }
    }

    @Test
    fun aDragIsAnnouncedWhereItLandsNotOnTheWay() {
        val bar = bar()
        bar.performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
            moveTo(Offset(width * 0.75f, centerY))
        }
        bar.assert(state("1:00 of 4:00"))
        bar.performTouchInput { up() }
        bar.assert(state("3:00 of 4:00"))
    }

    @Test
    fun talkBackFindsASliderThatReadsTheTimeAndSeeks() {
        bar()
            .assert(hasContentDescription("Position"))
            .assert(state("1:00 of 4:00"))
            .assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(listOf(LENGTH / 2), seeks) }
    }

    /** The bar in the controls band of the lock screen lyrics' page, where a swipe up or down closes. */
    @Composable
    private fun OnTheLockScreen() {
        LockPage(onClose = { swipes++ }, top = {}, lyrics = {}) {
            LockSeekBar(durationMs = LENGTH, playing = false, positionAt = { played }) {
                seeks += it
                played = it
            }
            Spacer(Modifier.fillMaxWidth().height(96.dp))
        }
    }

    private fun state(time: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, time)

    private companion object {
        const val LENGTH: Long = 240_000
        const val PLAYED: Long = 60_000
    }
}
