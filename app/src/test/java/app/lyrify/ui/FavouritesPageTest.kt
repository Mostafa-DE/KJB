package app.lyrify.ui

import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Library
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.albumKey
import app.lyrify.library.song
import app.lyrify.settings.FavouritesTab
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Favourites page's top tabs: which show for each mix of favourites, the tab shown, and the counts, all from the
 * favourites as they are now. Made-up names only.
 */
class FavouritesPageTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", album = "Harbour Glass", track = 1)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", album = "Harbour Glass", track = 2)
    private val water = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry")
    private val library = Library.of(listOf(lantern, bell, water), Settings())
    private val harbour = checkNotNull(albumKey(lantern))
    private val road = Playlist("p1", "Road Mix", listOf(water.ref), createdAt = 1)
    private val mira = FavouriteCollection(CollectionKind.ARTIST, "Mira Solen")

    private fun favourites(state: PlaylistsState) = Favourites.of(state, library)

    @Test
    fun `with nothing a favourite there are no tabs and nothing to show, so the page shows its empty state`() {
        val none = favourites(PlaylistsState())
        assertEquals(emptyList<FavouritesTab>(), none.tabs)
        FavouritesTab.entries.forEach { assertNull("$it", none.shown(it)) }
    }

    @Test
    fun `one kind alone shows without tabs, whichever tab was used last`() {
        val songs = favourites(PlaylistsState(favouriteSongs = listOf(lantern.ref, bell.ref)))
        assertEquals(emptyList<FavouritesTab>(), songs.tabs)
        assertEquals(FavouritesTab.SONGS, songs.shown(FavouritesTab.ALBUMS))

        val albums = favourites(PlaylistsState(favouriteCollections = listOf(FavouriteCollection(CollectionKind.ALBUM, harbour))))
        assertEquals(emptyList<FavouritesTab>(), albums.tabs)
        assertEquals(FavouritesTab.ALBUMS, albums.shown(FavouritesTab.SONGS))
        assertEquals(1, albums.count(FavouritesTab.ALBUMS))
    }

    @Test
    fun `several kinds each get a tab with its count, in order, the empty ones left out`() {
        val state = PlaylistsState(
            playlists = listOf(road),
            favouriteSongs = listOf(lantern.ref, bell.ref, water.ref),
            favouriteCollections = listOf(FavouriteCollection(CollectionKind.PLAYLIST, road.id), mira),
        )
        val mixed = favourites(state)
        assertEquals(listOf(FavouritesTab.SONGS, FavouritesTab.ARTISTS, FavouritesTab.PLAYLISTS), mixed.tabs)
        assertEquals(listOf(3, 1, 1), mixed.tabs.map(mixed::count))
        assertEquals(0, mixed.count(FavouritesTab.ALBUMS))
        assertEquals(0, mixed.count(FavouritesTab.FOLDERS))
    }

    @Test
    fun `the page reopens on the tab used last, or on the first with something when that one is empty now`() {
        val state = PlaylistsState(favouriteSongs = listOf(lantern.ref), favouriteCollections = listOf(mira))
        val mixed = favourites(state)
        assertEquals(FavouritesTab.ARTISTS, mixed.shown(FavouritesTab.ARTISTS))
        assertEquals(FavouritesTab.SONGS, mixed.shown(FavouritesTab.SONGS))
        // Albums was used last, and has nothing now: the first tab with something.
        assertEquals(FavouritesTab.SONGS, mixed.shown(FavouritesTab.ALBUMS))
        assertEquals(FavouritesTab.ARTISTS, favourites(state.copy(favouriteSongs = emptyList())).shown(FavouritesTab.SONGS))
    }

    @Test
    fun `a favourite album added while on another tab shows in its own tab, counted, and the tab shown stays`() {
        val before = PlaylistsState(favouriteSongs = listOf(lantern.ref), favouriteCollections = listOf(mira))
        assertEquals(listOf(FavouritesTab.SONGS, FavouritesTab.ARTISTS), favourites(before).tabs)

        val after = favourites(before.copy(favouriteCollections = before.favouriteCollections + FavouriteCollection(CollectionKind.ALBUM, harbour)))
        assertEquals(listOf(FavouritesTab.SONGS, FavouritesTab.ALBUMS, FavouritesTab.ARTISTS), after.tabs)
        assertEquals(1, after.count(FavouritesTab.ALBUMS))
        assertEquals(listOf(harbour), after.albums.map { it.key })
        assertEquals(FavouritesTab.ARTISTS, after.shown(FavouritesTab.ARTISTS))
    }

    @Test
    fun `favourites gone from the phone or the library are left out`() {
        val state = PlaylistsState(
            favouriteSongs = listOf(lantern.ref, song("Music/Gone/Old Tape.mp3").ref),
            favouriteCollections = listOf(
                FavouriteCollection(CollectionKind.ALBUM, "gone album"),
                FavouriteCollection(CollectionKind.PLAYLIST, "deleted"),
                FavouriteCollection(CollectionKind.FOLDER, "Music/Gone"),
            ),
        )
        val left = favourites(state)
        assertEquals(1, left.count(FavouritesTab.SONGS))
        assertEquals(emptyList<FavouritesTab>(), left.tabs)
        assertEquals(FavouritesTab.SONGS, left.shown(FavouritesTab.PLAYLISTS))
    }

    @Test
    fun `the tabs read Songs 120, the empty state has its own words, and View all is gone`() {
        // Unit tests run in the module's folder.
        val strings = File("src/main/res/values/strings_library.xml").readText()
        assertTrue("""<string name="lib_tab_count">%1${'$'}s %2${'$'}s</string>""" in strings)
        assertTrue("""<string name="lib_fav_empty_title">No favourites yet</string>""" in strings)
        assertFalse("lib_hint_" in strings)
        val page = File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText()
        assertFalse("FavouritesAll" in page)
        // The same swiping top tabs as Library's.
        assertTrue("PagerTabs(" in page && "TabPages(" in page)
    }
}
