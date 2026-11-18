package app.lyrify.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.library.SongRef
import app.lyrify.player.PlayerGraph
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.Transport
import app.lyrify.player.playerUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's second row and the sleep timer's row in its "⋯" on the phone (3.1, user 2026-11-18: the heart in the
 * middle, Library where the sleep timer was, the timer in the "⋯"), at font scale 2.0, light and dark: on a 360 dp phone's
 * player (24 dp margins) Shuffle, Queue, the heart, Library and Repeat show left to right, each named for TalkBack, 48 dp
 * to touch and whole inside the row, and Library hands over to the shell; the menu row of a running timer keeps its name
 * and what is left whole, and TalkBack says the sentence. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class PlayerRowDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun inTheDarkAtFontScaleTwoTheFiveButtonsFitA360DpPlayerInTheirOrder() = assertRow(dark = true)

    @Test
    fun inTheLightAtFontScaleTwoTheFiveButtonsFitA360DpPlayerInTheirOrder() = assertRow(dark = false)

    @Test
    fun atFontScaleTwoARunningTimersMenuRowKeepsItsNameAndWhatIsLeftWhole() {
        val name = context.getString(R.string.sleep_title)
        val left = context.getString(R.string.sleep_end_of_song)
        val spoken = context.getString(R.string.sleep_chip_end)
        show(dark = true) { Box(Modifier.requiredWidth(MENU)) { MenuRow(name, R.drawable.ic_snooze, {}, detail = left, description = spoken) } }
        for (words in listOf(name, left)) {
            val laid = layoutOf(words)
            assertFalse("$words cut", laid.hasVisualOverflow || laid.isLineEllipsized(laid.lineCount - 1))
        }
        compose.onNodeWithContentDescription(spoken).assertExists()
    }

    private fun assertRow(dark: Boolean) {
        var library = 0
        val book = QueueBook().play(listOf(SongRef(SONG)), 0, QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass"), now = 1).book
        val ui = playerUi(book, Repeat.OFF, place = null, Transport(), library = null, now = 0)!!
        show(dark) {
            Box(Modifier.requiredWidth(ROW).testTag(ROW_TAG)) {
                SecondaryRow(ui, remember { PlayerGraph.connection(context) }, onQueue = {}, onLibrary = { library++ })
            }
        }
        val names = listOf(R.string.player_shuffle_off, R.string.player_queue, heartLabel(false), R.string.nav_library, R.string.player_repeat_off).map(context::getString)
        val row = compose.onNodeWithTag(ROW_TAG).getUnclippedBoundsInRoot()
        val buttons = names.map { compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot() }
        buttons.forEachIndexed { i, button ->
            assertTrue("${names[i]}: $button", button.right - button.left >= 48.dp && button.bottom - button.top >= 48.dp)
            assertTrue("${names[i]}: $button outside $row", button.left >= row.left && button.right <= row.right)
        }
        // Left to right in that order, never on top of each other; the heart in the middle of the row.
        buttons.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", a.right <= b.left) }
        assertEquals(((row.left + row.right) / 2).value, ((buttons[2].left + buttons[2].right) / 2).value, 1f)
        compose.onNodeWithContentDescription(context.getString(R.string.nav_library)).performClick()
        compose.runOnIdle { assertEquals(1, library) }
    }

    /** [content] at font scale 2.0, light or [dark], in KJB's theme. */
    private fun show(dark: Boolean, content: @Composable () -> Unit) = compose.setContent {
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
        CompositionLocalProvider(
            LocalConfiguration provides config,
            LocalDensity provides Density(LocalDensity.current.density, FONT_SCALE),
            LocalToasts provides remember { ToastState() },
        ) {
            LyrifyTheme(content = content)
        }
    }

    private fun layoutOf(words: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private companion object {
        const val SONG = "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3"
        const val ROW_TAG = "row"

        /** A 360 dp phone's player less its 24 dp side margins. */
        val ROW = 312.dp

        /** The widest a menu row grows (MenuRow's sizeIn). */
        val MENU = 280.dp
        const val FONT_SCALE = 2f
    }
}
