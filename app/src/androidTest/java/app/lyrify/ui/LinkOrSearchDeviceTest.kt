package app.lyrify.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Downloads page's one box (4.1, the user's ask: a Link field and a Search YouTube field were two): a link in it is
 * looked up, words are searched for on YouTube, by its button and by the keyboard's key. With an add-on too old to
 * search, only a link goes. Made-up words and links only.
 */
@RunWith(AndroidJUnit4::class)
class LinkOrSearchDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String>()

    private fun box(searches: Boolean) {
        compose.setContent {
            LyrifyTheme {
                var text by remember { mutableStateOf("") }
                LinkOrSearchField(
                    text,
                    { text = it },
                    searches,
                    label = "Link or search",
                    placeholder = null,
                    onPaste = {},
                    onLookUp = { asked += "look up $it" },
                    onSearch = { asked += "search $it" },
                )
            }
        }
    }

    @Test
    fun wordsAreSearchedForAndALinkIsLookedUp() {
        box(searches = true)

        compose.onNode(hasSetTextAction()).performTextInput("paper lantern")
        compose.onNodeWithText(SEARCH).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Listen to this! https://video.example.com/v/2")
        compose.onNodeWithText(LOOK_UP).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("quiet hours")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).performTextReplacement("https://video.example.com/v/3")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals(listOf("search paper lantern", "look up https://video.example.com/v/2", "search quiet hours", "look up https://video.example.com/v/3"), asked)
    }

    @Test
    fun wordsLongerThanASearchTakesAreCutAsTheSearchCutsThem() {
        box(searches = true)

        compose.onNode(hasSetTextAction()).performTextInput("  " + "paper lantern ".repeat(20))
        compose.onNodeWithText(SEARCH).performClick()
        compose.waitForIdle()

        assertEquals(listOf("search " + ("paper lantern ".repeat(20)).trim().take(200)), asked)
    }

    @Test
    fun anAddOnTooOldToSearchTakesOnlyALink() {
        box(searches = false)

        compose.onNode(hasSetTextAction()).performTextInput("paper lantern")
        compose.onNodeWithText(LOOK_UP).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).performTextReplacement("https://video.example.com/v/2")
        compose.onNodeWithText(LOOK_UP).assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf("look up https://video.example.com/v/2"), asked)
    }

    @Test
    fun anEmptyBoxAsksNothing() {
        box(searches = true)

        compose.onNodeWithText(SEARCH).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("   ")
        compose.onNodeWithText(SEARCH).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), asked)
    }

    private companion object {
        const val SEARCH = "Search"
        const val LOOK_UP = "Look up"
    }
}
