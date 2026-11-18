package app.lyrify.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.data.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transitions on the phone, frame by frame with the test clock held: a dialog's action waits for its exit (decision D5),
 * a state fades through instead of cutting, the song sheets change step inside one window, both steps drawn while they
 * cross, and the Now bar stays where it is while the player grows out of it and back into it. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class TransitionsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aDialogsActionRunsOnlyOnceItHasLeft() {
        var confirmed = 0
        compose.setContent {
            Host { ConfirmDialog(title = "Delete Harbour Glass?", confirm = "Delete", dismiss = "Keep", onConfirm = { confirmed++ }, onDismiss = {}) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeByFrame()
        // The card is still on its way out: nothing has run yet.
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.mainClock.advanceTimeBy(Motion.DIALOG_MS.toLong() * 2)
        compose.runOnIdle { assertEquals(1, confirmed) }
        // And the card is gone.
        compose.onNodeWithText("Delete Harbour Glass?").assertDoesNotExist()
    }

    @Test
    fun aStateFadesThroughTheOtherInsteadOfCutting() {
        var state by mutableStateOf("Finding lyrics")
        compose.setContent { Host { FadeThrough(state) { Text(it) } } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        state = "No lyrics yet"
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.FADE_OUT_MS / 2L)
        // Both are drawn while one fades out and the other waits to fade in.
        compose.onNodeWithText("Finding lyrics").assertExists()
        compose.onNodeWithText("No lyrics yet").assertExists()
        compose.mainClock.advanceTimeBy((Motion.FADE_IN_MS + Motion.FADE_OUT_MS).toLong() * 2)
        compose.onNodeWithText("Finding lyrics").assertDoesNotExist()
        compose.onNodeWithText("No lyrics yet").assertExists()
    }

    @Test
    fun theSongSheetsChangeStepInOneWindow() {
        var step by mutableStateOf("Paper Lantern")
        compose.setContent {
            Host { SheetFlow(step, onDismiss = {}) { shown -> LyrifySheet(onDismiss = {}) { Text(shown) } } }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        step = "Add to"
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.FADE_OUT_MS / 2L)
        // One window, both steps in it while they cross: no window closed under the user.
        compose.onNodeWithText("Paper Lantern").assertExists()
        compose.onNodeWithText("Add to").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Paper Lantern").assertDoesNotExist()
        compose.onNodeWithText("Add to").assertExists()
    }

    @Test
    fun aSheetStepOnItsWayOutTakesNoTap() {
        var step by mutableStateOf("Paper Lantern")
        var tapped = 0
        compose.setContent {
            Host {
                SheetFlow(step, onDismiss = {}) { shown ->
                    LyrifySheet(onDismiss = {}) { Text(shown, Modifier.clickable { if (shown == "Paper Lantern") tapped++ }) }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        step = "Add to"
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.FADE_OUT_MS / 2L)
        // Still drawn while it fades, the old step is gone for the finger from the first frame of its exit.
        compose.onNodeWithText("Paper Lantern").performClick()
        compose.runOnIdle { assertEquals(0, tapped) }
    }

    @Test
    fun aDragDownFromTheSheetsCoverClosesIt() {
        var dismissed = 0
        compose.setContent {
            Host {
                // As the song sheet: the cover and its words at the head, then rows that take taps.
                LyrifySheet(onDismiss = { dismissed++ }) {
                    Box(Modifier.size(72.dp).background(Color.Gray).testTag(COVER))
                    Text("Harbour Glass")
                    repeat(4) { at -> Text("Row $at", Modifier.clickable {}) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(COVER).performTouchInput { swipeDown(startY = centerY, endY = centerY + DRAG_PX, durationMillis = DRAG_MS) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun aDragDownFromARowClosesTheSheetAndATapOnItStillLands() {
        var dismissed = 0
        var tapped = 0
        compose.setContent {
            Host {
                LyrifySheet(onDismiss = { dismissed++ }) {
                    Text("Harbour Glass")
                    Text("Play next", Modifier.clickable { tapped++ })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Play next").performClick()
        compose.runOnIdle { assertEquals(1, tapped) }
        compose.onNodeWithText("Play next").performTouchInput { swipeDown(startY = centerY, endY = centerY + DRAG_PX, durationMillis = DRAG_MS) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            // The drag never counted as a tap.
            assertEquals(1, tapped)
        }
    }

    @Test
    fun theNowBarStaysPutWhileThePlayerGrowsOutOfItAndBackIntoIt() {
        var open by mutableStateOf(false)
        compose.setContent {
            Host {
                CompositionLocalProvider(LocalReducedMotion provides false) {
                    SharedTransitionLayout {
                        val barTop = remember { Kept(Float.NaN) }
                        Box(Modifier.fillMaxSize()) {
                            // The shell's two ends: the Now bar over the dock, and the player's card.
                            AnimatedVisibility(!open, Modifier.align(Alignment.BottomCenter), enter = EnterTransition.None, exit = ExitTransition.None) {
                                val bar = remember(this) { Container(this@SharedTransitionLayout, this, bar = true, barTop) }
                                CompositionLocalProvider(LocalContainer provides bar) {
                                    NowBar(NowBarShows.Own(OwnNow("Quiet Engines", "The Lamplighters", cover = null, playing = false, progress = 0.25f)), LedgerState(), other = null, onOpen = {}, onPlayPause = {})
                                }
                            }
                            AnimatedVisibility(open, enter = EnterTransition.None, exit = ExitTransition.None) {
                                val player = remember(this) { Container(this@SharedTransitionLayout, this, bar = false, barTop) }
                                Box(Modifier.fillMaxSize().then(containerBounds(player)).background(Color.DarkGray))
                            }
                        }
                    }
                }
            }
        }
        val bar = compose.onNode(hasContentDescription("Quiet Engines", substring = true))
        compose.waitForIdle()
        val resting = bar.getBoundsInRoot()
        compose.mainClock.autoAdvance = false
        open = true
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.CONTAINER_MS / 3L)
        // The player grows over the bar, which neither moves nor grows with it.
        assertEquals(resting, bar.getBoundsInRoot())
        compose.mainClock.advanceTimeBy(Motion.CONTAINER_MS * 2L)
        open = false
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(Motion.OPEN_MS / 3L)
        // Shrinking back, the player lands on the bar, already in its place: it never slides down into it.
        assertEquals(resting, bar.getBoundsInRoot())
    }

    /** What the activity gives every screen: the theme and the app's messages, which a sheet's [ToastSlot] reads. */
    @Composable
    private fun Host(content: @Composable () -> Unit) {
        LyrifyTheme { CompositionLocalProvider(LocalToasts provides remember { ToastState() }, content = content) }
    }

    private companion object {
        const val COVER = "cover"

        /** A drag down well past the sheet's threshold, at a finger's pace. */
        const val DRAG_PX = 700f
        const val DRAG_MS = 250L
    }
}
