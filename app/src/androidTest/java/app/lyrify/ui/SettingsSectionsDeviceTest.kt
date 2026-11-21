package app.lyrify.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A Settings section on the phone: its title opens and closes it, and TalkBack hears its state and actions. Made-up words only. */
@RunWith(AndroidJUnit4::class)
class SettingsSectionsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aClosedSectionOpensFromItsTitleAndClosesFromTalkBack() {
        var open by mutableStateOf(false)
        val taps = mutableListOf<Boolean>()
        compose.setContent {
            val sections = SettingsSections({ open }) { _, now ->
                taps += now
                open = now
            }
            SettingsSectionCard(SettingsSection.LYRICS, sections) { Text("A made-up row") }
        }
        val title = compose.onNodeWithText(context.getString(R.string.settings_lyrics))
        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        title.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.set_section_collapsed)))
        compose.onNodeWithText("A made-up row").assertDoesNotExist()

        title.performClick()
        compose.onNodeWithText("A made-up row").assertIsDisplayed()
        title.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.set_section_expanded)))

        title.performSemanticsAction(SemanticsActions.Collapse)
        compose.onNodeWithText("A made-up row").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(true, false), taps) }
    }
}
