package app.lyrify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A message takes taps where it is drawn (D1 review): its slot takes no room, and the message floats up from it over the
 * content (the shell's, above the dock) or down from it (the player's, under its top bar), yet its Undo and a swipe reach
 * the message, never the content under it.
 */
@RunWith(AndroidJUnit4::class)
class ToastSlotDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun undoInAMessageFloatingUpAboveTheDockIsTapped() {
        val toasts = ToastState()
        var acted: Boolean? = null
        var under = 0
        compose.setContent {
            LaunchedEffect(Unit) { acted = toasts.show(Toast(MESSAGE, action = UNDO)) }
            OverContent(onTapUnder = { under++ }) {
                // As the shell's: the slot over the dock, in a column at the bottom.
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    ToastSlot(Modifier.padding(horizontal = 16.dp), padding = PaddingValues(bottom = 8.dp), state = toasts)
                    Spacer(Modifier.fillMaxWidth().height(64.dp))
                }
            }
        }
        compose.onNodeWithText(UNDO).performClick()
        compose.runOnIdle {
            assertEquals(true, acted)
            assertEquals(0, under)
            assertNull(toasts.current)
        }
    }

    @Test
    fun undoInAMessageFloatingDownUnderATopBarIsTapped() {
        val toasts = ToastState()
        var acted: Boolean? = null
        var under = 0
        compose.setContent {
            LaunchedEffect(Unit) { acted = toasts.show(Toast(MESSAGE, action = UNDO)) }
            OverContent(onTapUnder = { under++ }) {
                // As the player's: down from under its top bar, over the cover.
                ToastSlot(Modifier.padding(top = 64.dp).padding(horizontal = 12.dp), float = ToastFloat.DOWN, state = toasts)
            }
        }
        compose.onNodeWithText(UNDO).performClick()
        compose.runOnIdle {
            assertEquals(true, acted)
            assertEquals(0, under)
        }
    }

    @Test
    fun aSwipeTakesAMessageFloatingUpAway() {
        val toasts = ToastState()
        var acted: Boolean? = null
        compose.setContent {
            LaunchedEffect(Unit) { acted = toasts.show(Toast(MESSAGE, action = UNDO)) }
            OverContent(onTapUnder = {}) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    ToastSlot(Modifier.padding(horizontal = 16.dp), padding = PaddingValues(bottom = 8.dp), state = toasts)
                    Spacer(Modifier.fillMaxWidth().height(64.dp))
                }
            }
        }
        compose.onNodeWithText(MESSAGE).performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertEquals(false, acted)
            assertNull(toasts.current)
        }
    }

    /** The content a message floats over: the whole window, counting the taps that reach it. */
    @Composable
    private fun OverContent(onTapUnder: () -> Unit, slot: @Composable BoxScope.() -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().clickable(onClick = onTapUnder))
            slot()
        }
    }

    private companion object {
        const val MESSAGE = "Removed from a made-up list"
        const val UNDO = "Undo"
    }
}
