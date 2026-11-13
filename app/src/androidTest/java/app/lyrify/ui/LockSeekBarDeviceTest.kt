package app.lyrify.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs

/**
 * The lock screen's progress bar (user feedback 2026-11-13, point 4) under a real finger: only a deliberate sideways drag
 * seeks, to where the finger lifts; a tap, a touch that barely moves, or a swipe up (which closes the screen) never does.
 * TalkBack finds a slider that reads the time and seeks. The bar sits under a swipe that closes, as on the screen.
 */
@RunWith(AndroidJUnit4::class)
class LockSeekBarDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val seeks = mutableListOf<Long>()
    private var swipes = 0

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
        bar().performTouchInput { swipe(center, center + Offset(viewConfiguration.touchSlop, -400.dp.toPx()), durationMillis = 300) }
        compose.runOnIdle {
            assertEquals(emptyList<Long>(), seeks)
            assertEquals(1, swipes)
        }
    }

    @Test
    fun talkBackFindsASliderThatReadsTheTimeAndSeeks() {
        bar()
            .assert(hasContentDescription("Position"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:00 of 4:00"))
            .assert(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals(listOf(LENGTH / 2), seeks) }
    }

    /** The bar at the foot of a page that a swipe up or down of 80 dp closes, as the lock screen lyrics are. */
    @Composable
    private fun OnTheLockScreen() {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (abs(dragged) > 80.dp.toPx()) swipes++ }) { _, drag -> dragged += drag }
                },
        ) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 96.dp)) {
                LockSeekBar(durationMs = LENGTH, playing = false, positionAt = { PLAYED }, onSeek = { seeks += it })
            }
        }
    }

    private companion object {
        const val LENGTH: Long = 240_000
        const val PLAYED: Long = 60_000
    }
}
