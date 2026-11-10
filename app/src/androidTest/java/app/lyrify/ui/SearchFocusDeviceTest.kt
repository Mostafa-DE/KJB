package app.lyrify.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search's field (D1): it takes the focus the first time Search opens and each time it is tapped again, also when the
 * field attaches a few frames late (the tab sliding in), and never again after a turn of the phone.
 */
@RunWith(AndroidJUnit4::class)
class SearchFocusDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theFieldIsFocusedAfterSearchOpens() {
        var opens by mutableIntStateOf(0)
        compose.setContent { Field(opens) }
        compose.onNodeWithTag(FIELD).assertIsNotFocused()
        opens = 1
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD).assertIsFocused()
    }

    @Test
    fun theFieldIsFocusedWhenItAttachesAfterSearchOpened() {
        var opens by mutableIntStateOf(0)
        var shown by mutableStateOf(false)
        compose.setContent {
            val focus = remember { FocusRequester() }
            FocusOnOpen(focus, opens)
            if (shown) BasicTextField("", {}, Modifier.testTag(FIELD).focusRequester(focus))
        }
        opens = 1
        compose.mainClock.advanceTimeByFrame()
        shown = true
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD).assertIsFocused()
    }

    @Test
    fun aTurnOfThePhoneDoesNotFocusTheFieldAgain() {
        val restore = StateRestorationTester(compose)
        var opens by mutableIntStateOf(1)
        restore.setContent { Field(opens) }
        compose.onNodeWithTag(FIELD).assertIsFocused()
        // The user leaves the field; the phone turns: the count is the same, so the keyboard stays down.
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(FIELD).assertIsNotFocused()
        opens = 2
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD).assertIsFocused()
    }

    @Composable
    private fun Field(opens: Int) {
        val focus = remember { FocusRequester() }
        FocusOnOpen(focus, opens)
        BasicTextField("", {}, Modifier.testTag(FIELD).focusRequester(focus))
    }

    private companion object {
        const val FIELD = "search field"
    }
}
