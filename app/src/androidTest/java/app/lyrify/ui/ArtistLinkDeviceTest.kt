package app.lyrify.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Go to artist from a name link (the song sheet's, the player's, an album's): a tag of one artist opens their page at
 * once, a tag of several asks which first, in a small menu under the link (TA1). Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class ArtistLinkDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aTagOfSeveralArtistsAsksWhichOneFirst() {
        val opened = mutableListOf<String>()
        compose.setContent { ArtistLink(listOf(KIT, MIRA), { opened.add(it) }) { go -> LinkText("$KIT feat. $MIRA", go) } }

        compose.onNodeWithText("$KIT feat. $MIRA").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithText(MIRA).performClick()
        compose.waitForIdle()

        assertEquals(listOf(MIRA), opened)
    }

    @Test
    fun aTagOfOneArtistOpensThemAtOnce() {
        val opened = mutableListOf<String>()
        compose.setContent { ArtistLink(listOf(KIT), { opened.add(it) }) { go -> LinkText(KIT, go) } }

        compose.onNodeWithText(KIT).performClick()
        compose.waitForIdle()

        assertEquals(listOf(KIT), opened)
    }

    private companion object {
        const val KIT = "Kit Calloway"
        const val MIRA = "Mira Solen"
    }
}
