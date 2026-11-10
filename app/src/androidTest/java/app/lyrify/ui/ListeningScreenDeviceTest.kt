package app.lyrify.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.settings.Settings
import app.lyrify.stats.Top
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Your listening's top rows on the phone, drawn as its pages draw them: a Top songs row opens the song's sheet (S1
 * review, c051211). The pages themselves read plays.db and the library, so the row is drawn alone. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class ListeningScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val lantern = Song(
        ref = SongRef("/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3", mediaStoreId = 41),
        title = "Paper Lantern",
        artist = "Mira Solen",
        album = "Harbour Glass",
        albumArtist = null,
        durationMs = 201_000,
        track = 1,
        disc = null,
        year = null,
        genre = null,
        folder = "Music/Harbour Glass/",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = 4_000_000,
        mimeType = "audio/mpeg",
    )

    @Test
    fun aTopSongsRowOpensTheSongsSheet() {
        lateinit var sheets: SongSheets
        val pages = mutableListOf<Page>()
        compose.setContent {
            sheets = rememberSongSheets()
            TopRow(Top(lantern, plays = 12, heardMs = 42 * 60_000L, lastPlayedAt = null), rank = 1, Settings(), onOpen = { pages += it }, onSong = sheets::openSong)
        }

        compose.onNodeWithContentDescription("Paper Lantern", substring = true).assertHasClickAction().performClick()

        compose.runOnIdle {
            assertEquals(OpenSheet.Menu(lantern.ref), sheets.open)
            assertEquals(emptyList<Page>(), pages)
        }
    }
}
