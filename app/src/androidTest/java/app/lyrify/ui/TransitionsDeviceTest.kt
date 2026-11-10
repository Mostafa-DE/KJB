package app.lyrify.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transitions on the phone, frame by frame with the test clock held: a dialog's action waits for its exit (decision D5),
 * a state fades through instead of cutting, and the song sheets change step inside one window, both steps drawn while
 * they cross. Made-up names only.
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

    /** What the activity gives every screen: the theme and the app's messages, which a sheet's [ToastSlot] reads. */
    @Composable
    private fun Host(content: @Composable () -> Unit) {
        LyrifyTheme { CompositionLocalProvider(LocalToasts provides remember { ToastState() }, content = content) }
    }
}
