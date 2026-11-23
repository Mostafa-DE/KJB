package app.lyrify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.data.LedgerState
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Library
import app.lyrify.library.LibraryState
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.settings.DockTab
import app.lyrify.settings.ListSort
import app.lyrify.settings.Settings
import app.lyrify.settings.SongList
import app.lyrify.settings.SortSongs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Favourites and a playlist sorted by title on the phone as a song leaves them (CR5, KJB 3.1's crash): the page keeps
 * showing the songs sorted before until the sort of those left is ready, each row keyed by its own entry, then the
 * songs left. The pages are the real ones on made-up songs; nothing is written to playlists.json and nothing plays.
 * Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class SortedListsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val lantern = made("01 Paper Lantern", "Paper Lantern")
    private val bell = made("02 Tide Bell", "Tide Bell")
    private val water = made("03 Low Water", "Low Water")
    private val library = Library.of(listOf(lantern, bell, water), Settings())
    private val byTitle = Settings().let { it.copy(library = it.library.copy(listSorts = mapOf(SongList.FAVOURITES to ListSort(SortSongs.TITLE), SongList.PLAYLIST to ListSort(SortSongs.TITLE)))) }

    @Test
    fun theLastFavouriteTakenOutWhileFavouritesIsSortedByTitleLeavesTheOthersNeverACrash() = takeOutLowWater(collections = emptyList())

    @Test
    fun theLastFavouriteTakenOutWhileFavouritesShowsItsTabsSortedByTitleLeavesTheOthersNeverACrash() =
        takeOutLowWater(collections = listOf(FavouriteCollection(CollectionKind.ARTIST, "Kit Calloway")))

    @Test
    fun theLastSongTakenOutOfAPlaylistSortedByTitleLeavesTheOthersNeverACrash() {
        val road = Playlist("road-trip", "Road Trip", songs = listOf(lantern.ref, bell.ref, water.ref), createdAt = 1_000)
        var ui by mutableStateOf(ui(PlaylistsState(listOf(road))))
        compose.setContent { Shell { PlaylistPage(ui, library, road.id) } }
        compose.waitUntil(TIMEOUT_MS) { shows(water) }

        // On KJB 3.1 this keyed the three songs still sorted by the two entries left, by place: an
        // IndexOutOfBoundsException on the UI thread, failing the test here.
        compose.runOnIdle { ui = ui(PlaylistsState(listOf(road.copy(songs = listOf(lantern.ref, bell.ref))))) }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { !shows(water) }
        assertTrue(shows(lantern) && shows(bell))
    }

    /**
     * Favourites (its songs alone, or with tabs for [collections] too) sorted by title, then Low Water, the last entry,
     * taken out as Undo after a double tap or Remove from Favourites would.
     */
    private fun takeOutLowWater(collections: List<FavouriteCollection>) {
        var ui by mutableStateOf(ui(PlaylistsState(favouriteSongs = listOf(lantern.ref, bell.ref, water.ref), favouriteCollections = collections)))
        compose.setContent { Shell { FavouritesPage(ui, library, root = false) } }
        compose.waitUntil(TIMEOUT_MS) { shows(water) }

        // On KJB 3.1 this keyed the three songs still sorted by the two entries left, by place: the
        // IndexOutOfBoundsException of the user's phone, on the UI thread, failing the test here.
        compose.runOnIdle { ui = ui(PlaylistsState(favouriteSongs = listOf(lantern.ref, bell.ref), favouriteCollections = collections)) }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) { !shows(water) }
        assertTrue(shows(lantern) && shows(bell))
    }

    private fun ui(playlists: PlaylistsState) = LibraryUi(LibraryState.Ready(library), byTitle, playlists, queues = emptyList(), ledger = LedgerState())

    /** Whether [song]'s row is there, as TalkBack reads it with its length in words. */
    private fun shows(song: Song): Boolean = compose.onAllNodesWithContentDescription("${song.title}, Kit Calloway, 3 minutes 20 seconds").fetchSemanticsNodes().isNotEmpty()

    /** The shell's locals a page needs: a navigator, lyrics and messages. */
    @Composable
    private fun Shell(content: @Composable () -> Unit) {
        val nav = remember { Navigator(NavState.start(DockTab.LIBRARY)) }
        val toasts = remember { ToastState() }
        CompositionLocalProvider(LocalNavigator provides nav, LocalLyrics provides shellLyrics(), LocalToasts provides toasts, content = content)
    }

    /** A made-up song in the test folder, which nothing on the phone has. */
    private fun made(file: String, title: String): Song = Song(
        ref = SongRef("/storage/emulated/0/Music/Lyrify test/Road Trip/$file.mp3"),
        title = title,
        artist = "Kit Calloway",
        album = "Road Trip",
        albumArtist = null,
        durationMs = 200_000,
        track = null,
        disc = null,
        year = null,
        genre = null,
        folder = "Music/Lyrify test/Road Trip/",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = 4_000_000,
        mimeType = "audio/mpeg",
        albumId = null,
    )

    private companion object {
        const val TIMEOUT_MS: Long = 5_000
    }
}
