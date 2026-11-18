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
    fun `a heart kept on a whole tag of several artists shows each of them, once`() {
        val duet = song("Music/Duets/Ember Road.mp3", title = "Ember Road", artist = "Kit Calloway feat. Mira Solen")
        val library = Library.of(listOf(lantern, water, duet), Settings())
        val state = PlaylistsState(favouriteCollections = listOf(FavouriteCollection(CollectionKind.ARTIST, "Kit Calloway feat. Mira Solen"), mira))

        assertEquals(listOf("Kit Calloway", "Mira Solen"), Favourites.of(state, library).artists.map { it.name })
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

    // The compact head (user 2026-11-14: "the heart cover is too big and pushes everything down")

    @Test
    fun `the head is the small heart tile beside the title and its counts, not the big hero`() {
        val page = File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText()
        val favourites = page.substringAfter("private fun FavouritesPage(").substringBefore("\n}\n")
        assertTrue("FavouritesHead(" in favourites)
        assertFalse("Hero(" in favourites)
        val head = page.substringAfter("internal fun FavouritesHead(").substringBefore("\n}\n")
        assertTrue("BuiltInTile(BuiltInList.FAVOURITES, FAV_TILE)" in head)
        assertTrue("heading()" in head)
        assertEquals(56f, FAV_TILE.value)
    }

    @Test
    fun `at font scale 1 the head is the tile's height and the whole header stays within 120 dp`() {
        val words = AppTypography.titleLarge.lineHeight.value + FavouritesMeta.lineHeight.value
        assertTrue("title and counts, $words dp, beside a ${FAV_TILE.value} dp tile", words <= FAV_TILE.value)
        val stacked = favouritesHeadHeight(FAV_TILE.value, words, LIST_HEADER_HEIGHT.value, side = false, top = FAV_HEAD_TOP.value)
        assertEquals(FAV_HEAD_TOP.value + FAV_TILE.value + LIST_HEADER_HEIGHT.value, stacked)
        assertTrue("$stacked dp", stacked <= 120f)
        val side = favouritesHeadHeight(FAV_TILE.value, words, LIST_HEADER_HEIGHT.value, side = true, top = FAV_HEAD_TOP.value)
        assertEquals(FAV_HEAD_TOP.value + LIST_HEADER_HEIGHT.value, side)
    }

    @Test
    fun `at font scale 2 the head grows with its words, never cutting them under the tile`() {
        val words = 2f * (AppTypography.titleLarge.lineHeight.value + FavouritesMeta.lineHeight.value)
        val stacked = favouritesHeadHeight(FAV_TILE.value, words, LIST_HEADER_HEIGHT.value, side = false, top = FAV_HEAD_TOP.value)
        assertEquals(FAV_HEAD_TOP.value + words + LIST_HEADER_HEIGHT.value, stacked)
    }

    // Phone session 9: at font scale 2 the title was cut.

    @Test
    fun `a one-word title shrinks onto one line instead of breaking inside the word, longer ones wrap onto two`() {
        assertEquals(1, favouritesTitleLines("Favourites"))
        assertEquals(1, favouritesTitleLines(" Favoriten "))
        assertEquals(2, favouritesTitleLines("Mes favoris"))
    }

    @Test
    fun `the title shrinks to fit its lines rather than being cut, at any font scale or width`() {
        val head = File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText().substringAfter("internal fun FavouritesHead(").substringBefore("\n}\n")
        assertTrue("autoSize = TextAutoSize.StepBased(" in head)
        assertTrue("maxLines = favouritesTitleLines(title)" in head)
    }

    @Test
    fun `Shuffle and Play go beside the head only where what is left of the row holds the list header`() {
        val gap = FAV_HEAD_GAP.value.toInt()
        val beside = FAV_BUTTONS_BESIDE.value.toInt()
        // A phone held upright (360 to 411 dp, less the page's sides): under the head, at either font scale.
        assertFalse(favouritesSideBySide(width = 328, head = 248, gap = gap, beside = beside))
        assertFalse(favouritesSideBySide(width = 379, head = 248, gap = gap, beside = beside))
        assertFalse(favouritesSideBySide(width = 379, head = 379, gap = gap, beside = beside))
        // A wide window: beside it, unless large words leave too little.
        assertTrue(favouritesSideBySide(width = 808, head = 248, gap = gap, beside = beside))
        assertTrue(favouritesSideBySide(width = 620, head = 248, gap = gap, beside = beside))
        assertFalse(favouritesSideBySide(width = 619, head = 248, gap = gap, beside = beside))
        assertFalse(favouritesSideBySide(width = 808, head = 480, gap = gap, beside = beside))
    }
}
