package app.lyrify.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One Share for a song (3.1, the user's ask: Share and Share lyrics were two rows): with lyrics to share a tap asks
 * "Song" or "Lyrics" in a small menu under it first, each heard by TalkBack as what it shares; without, the song goes at
 * once. Made-up words only.
 */
@RunWith(AndroidJUnit4::class)
class ShareChoiceDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSongWithLyricsAsksSongOrLyricsFirst() {
        val shared = mutableListOf<String>()
        compose.setContent {
            LyrifyTheme {
                ShareChoice(lyrics = true, onSong = { shared.add("song") }, onLyrics = { shared.add("lyrics") }) { tap -> LinkText(SHARE, tap) }
            }
        }

        compose.onNodeWithText(SHARE).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), shared)
        compose.onNodeWithContentDescription("Share song").assertExists()
        compose.onNodeWithContentDescription("Share lyrics").performClick()
        compose.waitForIdle()
        assertEquals(listOf("lyrics"), shared)
        // The menu has closed; asked again, Song shares the song.
        compose.onAllNodesWithText("Lyrics").assertCountEquals(0)
        compose.onNodeWithText(SHARE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Song").performClick()
        compose.waitForIdle()

        assertEquals(listOf("lyrics", "song"), shared)
    }

    @Test
    fun aSongWithoutLyricsIsSharedAtOnce() {
        val shared = mutableListOf<String>()
        compose.setContent {
            LyrifyTheme {
                ShareChoice(lyrics = false, onSong = { shared.add("song") }, onLyrics = { shared.add("lyrics") }) { tap -> LinkText(SHARE, tap) }
            }
        }

        compose.onNodeWithText(SHARE).performClick()
        compose.waitForIdle()

        assertEquals(listOf("song"), shared)
        compose.onAllNodesWithText("Lyrics").assertCountEquals(0)
    }

    private companion object {
        const val SHARE = "Share"
    }
}
