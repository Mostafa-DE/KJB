package app.lyrify.ui

import app.lyrify.data.LyricsStatus
import app.lyrify.library.SongRef
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavTest {
    private val album = Page.Album("harbour-glass")
    private val artist = Page.Artist("Paper Lanterns")

    // Opening and the per-tab stacks

    @Test
    fun `the app opens on the last tab, at its root`() {
        val nav = NavState.start(DockTab.TOOLS)
        assertEquals(DockTab.TOOLS, nav.tab)
        assertEquals(emptyList<Page>(), nav.pages)
        assertEquals(emptyList<DockTab>(), nav.history)
        assertEquals(emptyList<FullScreen>(), nav.views)
    }

    @Test
    fun `each tab keeps its own stack`() {
        val nav = NavState.start(DockTab.LIBRARY).open(album).select(DockTab.TOOLS).open(Page.Duplicates).select(DockTab.LIBRARY)
        assertEquals(listOf<Page>(album), nav.pages)
        assertEquals(listOf<Page>(Page.Duplicates), nav.select(DockTab.TOOLS).pages)
    }

    @Test
    fun `Tools pages open on Tools, Library pages on Library, Search's own pages on Search`() {
        val start = NavState.start(DockTab.LIBRARY)
        assertEquals(DockTab.TOOLS, start.open(Page.Check(null)).tab)
        assertEquals(DockTab.TOOLS, start.open(Page.Settings()).tab)
        assertEquals(DockTab.TOOLS, start.open(Page.FoldersToSkip).tab)
        assertEquals(DockTab.TOOLS, start.open(Page.HiddenSongs).tab)
        assertEquals(DockTab.TOOLS, start.open(Page.SplitArtists).tab)
        assertEquals(DockTab.TOOLS, start.open(Page.Licences).tab)
        assertEquals(DockTab.TOOLS, NavState.start(DockTab.LIBRARY).open(Page.Bookmarks).tab)
        assertEquals(DockTab.TOOLS, NavState.start(DockTab.LIBRARY).open(Page.Licence("Media3")).tab)
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.TOOLS).open(Page.Songs(status = LyricsStatus.AI_ALIGNED)).tab)
        assertEquals(DockTab.SEARCH, start.open(Page.SearchAll(SearchGroup.SONGS, "lantern")).tab)
    }

    @Test
    fun `an album or artist opened from Search stays in Search, from anywhere else it opens in Library`() {
        val search = NavState.start(DockTab.SEARCH).open(artist)
        assertEquals(DockTab.SEARCH, search.tab)
        assertEquals(listOf<Page>(artist), search.pages)
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.TOOLS).open(album).tab)
        // From the player over Search: Library.
        val fromPlayer = NavState.start(DockTab.SEARCH).show(FullScreen.Player).open(album)
        assertEquals(DockTab.LIBRARY, fromPlayer.tab)
        assertEquals(emptyList<FullScreen>(), fromPlayer.views)
    }

    @Test
    fun `Playlists keeps its own stack beside Library's`() {
        val nav = NavState.start(DockTab.PLAYLISTS).open(Page.Queues).select(DockTab.LIBRARY).open(album).select(DockTab.PLAYLISTS)
        assertEquals(DockTab.PLAYLISTS, nav.tab)
        assertEquals(listOf<Page>(Page.Queues), nav.pages)
        assertEquals(listOf<Page>(album), nav.select(DockTab.LIBRARY).pages)
    }

    // Playlists, Queues and Favourites open inside the tab they were opened on: Library or Playlists, else Playlists

    @Test
    fun `Queues opened on Library's Playlists tab stays inside Library`() {
        val nav = NavState.start(DockTab.LIBRARY).open(Page.Queues)
        assertEquals(DockTab.LIBRARY, nav.tab)
        assertEquals(listOf<Page>(Page.Queues), nav.pages)
    }

    @Test
    fun `Queues opened on the Playlists tab stays inside Playlists`() {
        val nav = NavState.start(DockTab.PLAYLISTS).open(Page.Queues)
        assertEquals(DockTab.PLAYLISTS, nav.tab)
        assertEquals(listOf<Page>(Page.Queues), nav.pages)
    }

    @Test
    fun `All queues in the player's queue sheet opens on Playlists, whatever tab is under the player`() {
        DockTab.entries.forEach { under ->
            val nav = NavState.start(under).show(FullScreen.Player).open(Page.Queues)
            assertEquals("$under", DockTab.PLAYLISTS, nav.tab)
            assertEquals("$under", listOf<Page>(Page.Queues), nav.pages)
        }
    }

    @Test
    fun `a recent queue in the player's queue sheet opens on Playlists`() {
        val nav = NavState.start(DockTab.SEARCH).show(FullScreen.Player).open(Page.Queue("q1"))
        assertEquals(DockTab.PLAYLISTS, nav.tab)
        assertEquals(listOf<Page>(Page.Queue("q1")), nav.pages)
    }

    @Test
    fun `a playlist opened in Library stays in Library, and one opened in Playlists stays in Playlists`() {
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.LIBRARY).open(Page.Playlist("p3")).tab)
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.PLAYLISTS).open(Page.Playlist("p3")).tab)
    }

    @Test
    fun `a playlist opened from Search or Tools opens on Playlists`() {
        val search = NavState.start(DockTab.SEARCH).open(Page.Playlist("p3"))
        assertEquals(DockTab.PLAYLISTS, search.tab)
        assertEquals(listOf<Page>(Page.Playlist("p3")), search.pages)
        assertEquals(listOf(DockTab.SEARCH), search.history)
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.TOOLS).open(Page.Playlist("p3")).tab)
    }

    @Test
    fun `Favourites opened from Library's Songs row stays inside Library`() {
        val nav = NavState.start(DockTab.LIBRARY).open(Page.BuiltIn(BuiltInList.FAVOURITES))
        assertEquals(DockTab.LIBRARY, nav.tab)
        assertEquals(listOf<Page>(Page.BuiltIn(BuiltInList.FAVOURITES)), nav.pages)
    }

    @Test
    fun `Favourites opened from Playlists stays in Playlists, from Search or Tools it opens on Playlists`() {
        val favourites = Page.BuiltIn(BuiltInList.FAVOURITES)
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.PLAYLISTS).open(favourites).tab)
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.SEARCH).open(favourites).tab)
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.TOOLS).open(favourites).tab)
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.LIBRARY).show(FullScreen.Player).open(favourites).tab)
    }

    @Test
    fun `Your listening, from Most played, follows the tab Most played is on`() {
        val library = NavState.start(DockTab.LIBRARY).open(Page.BuiltIn(BuiltInList.MOST_PLAYED)).open(Page.Listening)
        assertEquals(DockTab.LIBRARY, library.tab)
        assertEquals(listOf(Page.BuiltIn(BuiltInList.MOST_PLAYED), Page.Listening), library.pages)
        val playlists = NavState.start(DockTab.PLAYLISTS).open(Page.BuiltIn(BuiltInList.MOST_PLAYED)).open(Page.Listening).open(Page.TopList(TopKind.SONGS))
        assertEquals(DockTab.PLAYLISTS, playlists.tab)
        assertEquals(3, playlists.pages.size)
    }

    @Test
    fun `an album or artist opened inside Playlists stays in Playlists`() {
        val nav = NavState.start(DockTab.PLAYLISTS).open(Page.BuiltIn(BuiltInList.FAVOURITES)).open(album).open(artist)
        assertEquals(DockTab.PLAYLISTS, nav.tab)
        assertEquals(listOf(Page.BuiltIn(BuiltInList.FAVOURITES), album, artist), nav.pages)
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.PLAYLISTS).show(FullScreen.Player).open(album).tab)
    }

    // The Library icon

    // KJB 3.1 (user feedback 2026-11-17, item 1): the icon no longer always lands on Songs but on the tab Library opens on
    // (null here: the first tab in the user's order, or the one they chose), which [libraryOpensOn] resolves.

    @Test
    fun `the Library icon opens Library on the tab it opens on, never on the last top tab used`() {
        val albums = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.ALBUMS)
        assertEquals(LibraryTab.ALBUMS, albums.libraryTab)
        // From Albums to Search, then the Library icon: the tab Library opens on.
        assertNull(albums.select(DockTab.SEARCH).select(DockTab.LIBRARY).libraryTab)
        // On Library's root on Albums, the icon goes to that tab; there, to the top of its list.
        val first = albums.select(DockTab.LIBRARY)
        assertNull(first.libraryTab)
        assertEquals(0, first.reselectsOf(DockTab.LIBRARY))
        assertEquals(1, first.select(DockTab.LIBRARY).reselectsOf(DockTab.LIBRARY))
        // A page popped to the root by the icon lands there too.
        assertNull(albums.open(album).select(DockTab.LIBRARY).libraryTab)
    }

    @Test
    fun `Library opens on its first tab shown, or on the one the user chose while it shows`() {
        val order = listOf(LibraryTab.ALBUMS, LibraryTab.SONGS, LibraryTab.FOLDERS)
        assertEquals(LibraryTab.ALBUMS, libraryOpensOn(order, chosen = null))
        assertEquals(LibraryTab.FOLDERS, libraryOpensOn(order, chosen = LibraryTab.FOLDERS))
        // Chosen but hidden since (or Books with no book left): the first shown again.
        assertEquals(LibraryTab.ALBUMS, libraryOpensOn(order, chosen = LibraryTab.BOOKS))
        assertEquals(LibraryTab.SONGS, libraryOpensOn(listOf(LibraryTab.SONGS), chosen = LibraryTab.GENRES))
    }

    @Test
    fun `swiping Library to the tab it opens on keeps nothing, so the icon goes straight to the top there`() {
        // What Library's pager tells the navigator for the tab a swipe lands on.
        assertNull(libraryTabPicked(LibraryTab.ALBUMS, opensOn = LibraryTab.ALBUMS))
        assertEquals(LibraryTab.SONGS, libraryTabPicked(LibraryTab.SONGS, opensOn = LibraryTab.ALBUMS))
        val back = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.SONGS).pickLibraryTab(libraryTabPicked(LibraryTab.ALBUMS, LibraryTab.ALBUMS))
        assertEquals(1, back.select(DockTab.LIBRARY).reselectsOf(DockTab.LIBRARY))
    }

    @Test
    fun `the Playlists icon opens Playlists, from Library on any top tab and from every other tab`() {
        LibraryTab.entries.forEach { top ->
            val playlists = NavState.start(DockTab.LIBRARY).pickLibraryTab(top).select(DockTab.PLAYLISTS)
            assertEquals(DockTab.PLAYLISTS, playlists.tab)
            assertEquals(emptyList<Page>(), playlists.pages)
            assertEquals(top, playlists.libraryTab)
        }
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.LIBRARY).open(album).select(DockTab.PLAYLISTS).tab)
        DockTab.entries.forEach { from -> assertEquals(DockTab.PLAYLISTS, NavState.start(from).select(DockTab.PLAYLISTS).tab) }
    }

    @Test
    fun `each of Library's top tabs tapped is the one shown, Playlists among them`() {
        LibraryTab.entries.forEach { from ->
            LibraryTab.entries.forEach { top -> assertEquals(top, NavState.start(DockTab.LIBRARY).pickLibraryTab(from).pickLibraryTab(top).libraryTab) }
        }
        assertEquals(LibraryTab.PLAYLISTS, NavState.start(DockTab.LIBRARY).select(DockTab.LIBRARY).pickLibraryTab(LibraryTab.PLAYLISTS).libraryTab)
    }

    @Test
    fun `Library's pages landing on a tab tell the navigator only what changes, the tab it opens on as nothing`() {
        val nav = Navigator(NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.GENRES))
        // Genres switched off in Settings: the pages land on the first tab, the one Library opens on.
        pickLibraryTabAt(nav, LibraryTab.SONGS, opensOn = LibraryTab.SONGS)
        assertNull(nav.state.libraryTab)
        val before = nav.state
        pickLibraryTabAt(nav, LibraryTab.SONGS, opensOn = LibraryTab.SONGS)
        assertTrue(before === nav.state)
        pickLibraryTabAt(nav, LibraryTab.ALBUMS, opensOn = LibraryTab.SONGS)
        assertEquals(LibraryTab.ALBUMS, nav.state.libraryTab)
    }

    @Test
    fun `a kept tab that is the one Library opens on is forgotten, so one tap of the icon goes to the top`() {
        // The tour's Songs, or "Open here first" on the tab shown: the navigator keeps the very tab Library opens on.
        val nav = Navigator(NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.SONGS))
        // What Library's root does with the tab it shows ([LibraryPage]).
        pickLibraryTabAt(nav, LibraryTab.SONGS, opensOn = LibraryTab.SONGS)
        nav.select(DockTab.LIBRARY)
        assertEquals(1, nav.state.reselectsOf(DockTab.LIBRARY))
        assertTrue("LaunchedEffect(tab, opensOn) { pickLibraryTabAt(nav, tab, opensOn) }" in File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText())
    }

    @Test
    fun `only the Library icon takes Library back to the tab it opens on`() {
        val playlists = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.PLAYLISTS)
        val others: List<(NavState) -> NavState> = listOf(
            { it.select(DockTab.PLAYLISTS) },
            { it.select(DockTab.PLAYLISTS).select(DockTab.PLAYLISTS) },
            { it.select(DockTab.SEARCH) },
            { it.select(DockTab.TOOLS) },
            { it.select(DockTab.PLAYLISTS).back()!! },
            { it.select(DockTab.SEARCH).back()!! },
            { it.open(Page.Playlist("p3")).back()!! },
            { it.open(Page.Queues) },
            { it.show(FullScreen.Player).back()!! },
            { it.show(FullScreen.Player).open(album).back()!!.back()!! },
        )
        others.forEach { move -> assertEquals(LibraryTab.PLAYLISTS, move(playlists).libraryTab) }
        assertNull(playlists.select(DockTab.LIBRARY).libraryTab)
        assertNull(playlists.select(DockTab.PLAYLISTS).select(DockTab.LIBRARY).libraryTab)
    }

    @Test
    fun `from the Playlists icon to Search and back stays on Playlists`() {
        val playlists = NavState.start(DockTab.LIBRARY).select(DockTab.PLAYLISTS)
        assertEquals(DockTab.PLAYLISTS, playlists.select(DockTab.SEARCH).select(DockTab.PLAYLISTS).tab)
        assertEquals(DockTab.PLAYLISTS, playlists.select(DockTab.SEARCH).back()!!.tab)
        val page = playlists.open(Page.Queues).select(DockTab.SEARCH).select(DockTab.PLAYLISTS)
        assertEquals(DockTab.PLAYLISTS, page.tab)
        assertEquals(listOf<Page>(Page.Queues), page.pages)
    }

    @Test
    fun `a turn of the phone or process death keeps Playlists, and Library's Playlists top tab`() {
        val top = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.PLAYLISTS)
        assertEquals(top, NavState.decode(top.encode()))
        assertEquals(top, restoreNav(top.encode(), DockTab.LIBRARY))
        val dock = top.select(DockTab.PLAYLISTS)
        assertEquals(dock, restoreNav(dock.encode(), DockTab.LIBRARY))
        assertEquals(DockTab.PLAYLISTS, restoreNav(dock.encode(), DockTab.LIBRARY).tab)
    }

    @Test
    fun `Back to Library keeps its top tab, and so does a page left open on it`() {
        val albums = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.ALBUMS)
        assertEquals(LibraryTab.ALBUMS, albums.select(DockTab.SEARCH).back()!!.libraryTab)
        val page = albums.open(album).select(DockTab.SEARCH).select(DockTab.LIBRARY)
        assertEquals(listOf<Page>(album), page.pages)
        assertEquals(LibraryTab.ALBUMS, page.back()!!.libraryTab)
    }

    @Test
    fun `the app launched on Library opens on the tab Library opens on, and a turn of the phone keeps the top tab shown`() {
        assertNull(NavState.start(DockTab.LIBRARY).libraryTab)
        assertNull(restoreNav(null, DockTab.LIBRARY).libraryTab)
        assertNull(restoreNav(Tab2x.NOW, DockTab.LIBRARY).libraryTab)
        // Saved before 3.1 on Songs, or on a tab this version does not know: the tab Library opens on.
        assertNull(NavState.decode("""{"tab":"LIBRARY","libraryTab":"RADIO"}""")!!.libraryTab)
        assertEquals(LibraryTab.SONGS, NavState.decode("""{"tab":"LIBRARY","libraryTab":"SONGS"}""")!!.libraryTab)
        val first = NavState.start(DockTab.LIBRARY).select(DockTab.TOOLS)
        assertEquals(first, NavState.decode(first.encode()))
        val albums = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.ALBUMS).select(DockTab.TOOLS)
        assertEquals(albums, NavState.decode(albums.encode()))
        assertEquals(LibraryTab.ALBUMS, restoreNav(albums.encode(), DockTab.LIBRARY).libraryTab)
    }

    // The bar the user makes (KJB 3.1): Queues, Favourites, Downloads and Books with stacks of their own

    @Test
    fun `each item on the bar keeps its own stack, the new ones too`() {
        val bar = listOf(DockTab.LIBRARY, DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.BOOKS, DockTab.TOOLS)
        val nav = NavState.start(DockTab.QUEUES).open(Page.Queue("q1"), docked = bar)
            .select(DockTab.FAVOURITES).open(album, docked = bar)
            .select(DockTab.BOOKS).open(Page.Folder("Audiobooks/Lantern Keeper"), docked = bar)
            .select(DockTab.DOWNLOADS).open(artist, docked = bar)
        assertEquals(DockTab.DOWNLOADS, nav.tab)
        assertEquals(listOf<Page>(artist), nav.pages)
        assertEquals(listOf<Page>(Page.Queue("q1")), nav.pagesOf(DockTab.QUEUES))
        assertEquals(listOf<Page>(album), nav.pagesOf(DockTab.FAVOURITES))
        assertEquals(listOf<Page>(Page.Folder("Audiobooks/Lantern Keeper")), nav.pagesOf(DockTab.BOOKS))
        // Their icons on the bar at their roots: the list to the top, as the four's do.
        assertEquals(1, NavState.start(DockTab.FAVOURITES).select(DockTab.FAVOURITES).reselectsOf(DockTab.FAVOURITES))
        assertEquals(emptyList<Page>(), nav.select(DockTab.DOWNLOADS).pages)
    }

    @Test
    fun `from the new items, Tools pages still open on Tools and Library's songs on Library`() {
        DockTab.entries.filter { it in BAR_ROOTS }.forEach { root ->
            assertEquals("$root", DockTab.TOOLS, NavState.start(root).open(Page.Settings()).tab)
            assertEquals("$root", DockTab.LIBRARY, NavState.start(root).open(Page.Songs(status = LyricsStatus.NOT_FOUND)).tab)
            assertEquals("$root", DockTab.SEARCH, NavState.start(root).open(Page.SearchAll(SearchGroup.SONGS, "lantern")).tab)
            // From the player over them, as from anywhere: an album on Library, a playlist on Playlists.
            assertEquals("$root", DockTab.LIBRARY, NavState.start(root).show(FullScreen.Player).open(album).tab)
            assertEquals("$root", DockTab.PLAYLISTS, NavState.start(root).show(FullScreen.Player).open(Page.Playlist("p3")).tab)
        }
        assertEquals(setOf(DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.BOOKS), BAR_ROOTS)
    }

    @Test
    fun `with Playlists off the bar, a playlist opens where it was opened, and from the player or Tools in Library`() {
        val bar = listOf(DockTab.LIBRARY, DockTab.SEARCH, DockTab.TOOLS)
        val search = NavState.start(DockTab.SEARCH).open(Page.Playlist("p3"), docked = bar)
        assertEquals(DockTab.SEARCH, search.tab)
        assertEquals(listOf<Page>(Page.Playlist("p3")), search.pages)
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.TOOLS).open(Page.BuiltIn(BuiltInList.FAVOURITES), docked = bar).tab)
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.SEARCH).show(FullScreen.Player).open(Page.Queues, docked = bar).tab)
        // Library's own Playlists tab keeps them inside Library, as before.
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.LIBRARY).open(Page.Queue("q1"), docked = bar).tab)
        // On the bar, nothing changes.
        assertEquals(DockTab.PLAYLISTS, NavState.start(DockTab.SEARCH).open(Page.Playlist("p3"), docked = DOCK_TABS).tab)
    }

    @Test
    fun `the navigator opens pages by the bar it was last given`() {
        val nav = Navigator(NavState.start(DockTab.SEARCH))
        nav.dock(listOf(DockTab.LIBRARY, DockTab.SEARCH), reachable = setOf(DockTab.LIBRARY, DockTab.SEARCH, DockTab.TOOLS))
        nav.open(Page.Playlist("p3"))
        assertEquals(DockTab.SEARCH, nav.state.tab)
    }

    @Test
    fun `an item that leaves the bar leaves the history too, and the app goes to Library if it was on it`() {
        val nav = NavState.start(DockTab.LIBRARY).select(DockTab.FAVOURITES).select(DockTab.QUEUES).select(DockTab.TOOLS)
        val reachable = setOf(DockTab.LIBRARY, DockTab.QUEUES, DockTab.SEARCH, DockTab.TOOLS)
        val kept = nav.within(reachable)
        assertEquals(DockTab.TOOLS, kept.tab)
        assertEquals(listOf(DockTab.LIBRARY, DockTab.QUEUES), kept.history)
        // On an item that left: Library, whose way back is the rest of the history.
        val gone = NavState.start(DockTab.LIBRARY).select(DockTab.DOWNLOADS).open(album, docked = listOf(DockTab.LIBRARY, DockTab.DOWNLOADS)).within(setOf(DockTab.LIBRARY, DockTab.SEARCH, DockTab.TOOLS))
        assertEquals(DockTab.LIBRARY, gone.tab)
        assertEquals(emptyList<DockTab>(), gone.history)
        // Nothing gone, nothing changes.
        assertEquals(nav, nav.within(DockTab.entries.toSet()))
    }

    @Test
    fun `opening a page closes the full-screen views, and the page on top is not opened twice`() {
        val nav = NavState.start(DockTab.LIBRARY).show(FullScreen.CompanionNow).open(Page.Check("/music/a.mp3"))
        assertEquals(emptyList<FullScreen>(), nav.views)
        assertEquals(listOf<Page>(Page.Check("/music/a.mp3")), nav.pages)
        assertEquals(listOf<Page>(album), NavState.start(DockTab.LIBRARY).open(album).open(album).pages)
    }

    // Back

    @Test
    fun `back closes the full-screen views first, the newest first`() {
        val nav = NavState.start(DockTab.LIBRARY).open(album).show(FullScreen.Player).show(FullScreen.FullLyrics)
        val once = nav.back()!!
        assertEquals(listOf<FullScreen>(FullScreen.Player), once.views)
        assertEquals(listOf<Page>(album), once.pages)
        val twice = once.back()!!
        assertEquals(emptyList<FullScreen>(), twice.views)
        assertEquals(listOf<Page>(album), twice.pages)
    }

    @Test
    fun `then it pops the page stack, then walks back through the tab history, then lets the phone leave`() {
        val nav = NavState.start(DockTab.LIBRARY).select(DockTab.SEARCH).select(DockTab.TOOLS).open(Page.Tones)
        val popped = nav.back()!!
        assertEquals(DockTab.TOOLS, popped.tab)
        assertEquals(emptyList<Page>(), popped.pages)
        val search = popped.back()!!
        assertEquals(DockTab.SEARCH, search.tab)
        val library = search.back()!!
        assertEquals(DockTab.LIBRARY, library.tab)
        assertNull(library.back())
    }

    @Test
    fun `back walks the history across the four tabs, each once, then lets the phone leave`() {
        val nav = NavState.start(DockTab.LIBRARY).select(DockTab.PLAYLISTS).select(DockTab.SEARCH).select(DockTab.TOOLS).select(DockTab.PLAYLISTS)
        assertEquals(listOf(DockTab.LIBRARY, DockTab.SEARCH, DockTab.TOOLS), nav.history)
        assertEquals(listOf(DockTab.PLAYLISTS, DockTab.TOOLS, DockTab.SEARCH, DockTab.LIBRARY), generateSequence(nav) { it.back() }.map { it.tab }.toList())
    }

    @Test
    fun `the tab history holds each tab once, the latest last`() {
        val nav = NavState.start(DockTab.LIBRARY).select(DockTab.TOOLS).select(DockTab.LIBRARY).select(DockTab.SEARCH)
        assertEquals(listOf(DockTab.TOOLS, DockTab.LIBRARY), nav.history)
        // Opening a page on another tab is a visit too.
        assertEquals(listOf(DockTab.LIBRARY, DockTab.SEARCH), nav.open(Page.Ai).history)
    }

    @Test
    fun `closing a view takes the views above it too`() {
        val nav = NavState.start(DockTab.LIBRARY).show(FullScreen.Player).show(FullScreen.FullLyrics).show(FullScreen.Equalizer)
        assertEquals(listOf<FullScreen>(FullScreen.Player), nav.close(FullScreen.FullLyrics).views)
        assertEquals(nav, nav.close(FullScreen.CompanionNow))
        assertEquals(listOf<FullScreen>(FullScreen.Player), NavState.start(DockTab.LIBRARY).show(FullScreen.Player).show(FullScreen.Player).views)
    }

    // Back to where a page was opened from: the player, or a song's sheet

    @Test
    fun `an album opened from the player goes back to the player, over the tab and page it was on`() {
        val player = NavState.start(DockTab.SEARCH).open(Page.SearchAll(SearchGroup.SONGS, "lantern")).show(FullScreen.Player)
        val opened = player.open(album)
        assertEquals(DockTab.LIBRARY, opened.tab)
        assertEquals(listOf<Page>(album), opened.pages)
        assertEquals(emptyList<FullScreen>(), opened.views)
        assertEquals(player, opened.back())
    }

    @Test
    fun `an artist opened from the player over Library goes back to the player there`() {
        val player = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.ALBUMS).show(FullScreen.Player)
        val back = player.open(artist).back()!!
        assertEquals(player, back)
        assertEquals(listOf<FullScreen>(FullScreen.Player), back.views)
        assertEquals(emptyList<Page>(), back.pages)
    }

    @Test
    fun `player, album, artist, then Back lands on the album, and Back again on the player`() {
        val player = NavState.start(DockTab.PLAYLISTS).open(Page.Queues).show(FullScreen.Player)
        val artistPage = player.open(album).open(artist)
        assertEquals(listOf<Page>(album, artist), artistPage.pages)
        val albumPage = artistPage.back()!!
        assertEquals(DockTab.LIBRARY, albumPage.tab)
        assertEquals(listOf<Page>(album), albumPage.pages)
        assertEquals(emptyList<FullScreen>(), albumPage.views)
        assertEquals(player, albumPage.back())
    }

    @Test
    fun `a page opened from a view other than the player goes back to it too`() {
        val companion = NavState.start(DockTab.TOOLS).show(FullScreen.CompanionNow)
        val check = companion.open(Page.Check("/music/a.mp3"))
        assertEquals(emptyList<FullScreen>(), check.views)
        assertEquals(companion, check.back())
    }

    @Test
    fun `a page opened from a song's sheet goes back to its list, with the sheet asked to show again`() {
        val list = NavState.start(DockTab.LIBRARY).open(Page.Playlist("p3"))
        val sheet = SheetReturn("list host", OpenSheet.Menu(SongRef("/music/a.mp3"), SheetPlace.Playlist("p3", 2)))
        val opened = list.open(artist, sheet)
        assertEquals(listOf(Page.Playlist("p3"), artist), opened.pages)
        assertNull(opened.reopen)
        val back = opened.back()!!
        assertEquals(DockTab.LIBRARY, back.tab)
        assertEquals(listOf<Page>(Page.Playlist("p3")), back.pages)
        assertEquals(sheet, back.reopen)
        // Only the host that opened it shows it, and only once.
        val navigator = Navigator(back)
        assertNull(navigator.takeSheet("another host"))
        assertEquals(sheet.sheet, navigator.takeSheet("list host"))
        assertNull(navigator.takeSheet("list host"))
        assertEquals(list, navigator.state)
    }

    @Test
    fun `a sheet over Search goes back to Search's root with the sheet, and the artist's own pages pop first`() {
        val search = NavState.start(DockTab.SEARCH)
        val sheet = SheetReturn("search host", OpenSheet.Menu(SongRef("/music/b.flac")))
        val opened = search.open(artist, sheet).open(album)
        assertEquals(DockTab.SEARCH, opened.tab)
        val artistPage = opened.back()!!
        assertEquals(listOf<Page>(artist), artistPage.pages)
        assertNull(artistPage.reopen)
        val root = artistPage.back()!!
        assertEquals(emptyList<Page>(), root.pages)
        assertEquals(sheet, root.reopen)
    }

    @Test
    fun `leaving the page another way does not bring the sheet back`() {
        val sheet = SheetReturn("list host", OpenSheet.Menu(SongRef("/music/a.mp3")))
        val opened = NavState.start(DockTab.LIBRARY).open(artist, sheet)
        // The Library icon pops the page: no sheet.
        assertNull(opened.select(DockTab.LIBRARY).reopen)
        assertEquals(emptyList<Page>(), opened.select(DockTab.LIBRARY).pages)
        // A sheet not shown before the next move is dropped.
        val back = opened.back()!!
        assertNull(back.select(DockTab.SEARCH).reopen)
        assertNull(back.open(album).reopen)
        assertNull(back.show(FullScreen.Player).reopen)
    }

    @Test
    fun `a page's own Back button is the phone's Back`() {
        // Unit tests run in the module's folder.
        val shell = java.io.File("src/main/java/app/lyrify/ui/AppShell.kt").readText()
        // The shell's back for pages follows the finger (predictive back) and ends in nav.back().
        assertTrue(Regex("""PredictiveBackHandler\(enabled = .*state\.pages\.isNotEmpty\(\).*\{ events ->[\s\S]{0,300}?val back = backFromPages\(nav""").containsMatchIn(shell))
        assertTrue(shell.substringAfter("private class PlainBack(").substringBefore("\n}\n").contains("nav.back()"))
        assertTrue(shell.substringAfter("private fun backFromPages(").substringBefore("\n}\n").contains("nav.back()"))
        val collections = java.io.File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText()
        assertTrue(Regex("""ic_arrow_back[^\n]*\{ nav\.back\(\) }""").containsMatchIn(collections))
        val library = java.io.File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText()
        assertTrue(Regex("""ic_arrow_back[^\n]*onBack\)""").containsMatchIn(library))
        assertTrue("BackTopBar(title, under, backdrop) { nav.back() }" in library)
    }

    // Reselect

    @Test
    fun `tapping the tab shown pops it to its root, and at its root asks it to go to the top`() {
        val nav = NavState.start(DockTab.LIBRARY).open(album).open(artist)
        val root = nav.select(DockTab.LIBRARY)
        assertEquals(emptyList<Page>(), root.pages)
        assertEquals(nav.reselects, root.reselects)
        assertEquals(nav.history, root.history)
        val top = root.select(DockTab.LIBRARY)
        assertEquals(1, top.reselectsOf(DockTab.LIBRARY))
        assertEquals(0, top.reselectsOf(DockTab.SEARCH))
        assertEquals(emptyList<DockTab>(), top.history)
    }

    /** 3.1 (user 2026-11-18): the player's Library button closes the player and does what the bar's Library item does. */
    @Test
    fun `the player's Library button closes the player onto Library, as the bar's Library item does`() {
        // Over another tab: Library as it was left, its page kept.
        val over = NavState.start(DockTab.LIBRARY).open(album).select(DockTab.PLAYLISTS).show(FullScreen.Player)
        val library = over.select(DockTab.LIBRARY)
        assertEquals(emptyList<FullScreen>(), library.views)
        assertEquals(DockTab.LIBRARY, library.tab)
        assertEquals(listOf<Page>(album), library.pages)
        // Over Library itself: its root, as a tap on its item there.
        val home = NavState.start(DockTab.LIBRARY).open(album).show(FullScreen.Player).select(DockTab.LIBRARY)
        assertEquals(emptyList<FullScreen>(), home.views)
        assertEquals(emptyList<Page>(), home.pages)
    }

    @Test
    fun `tapping Playlists at its root asks its list to go to the top`() {
        val nav = NavState.start(DockTab.PLAYLISTS).open(Page.Queues)
        val root = nav.select(DockTab.PLAYLISTS)
        assertEquals(emptyList<Page>(), root.pages)
        assertEquals(0, root.reselectsOf(DockTab.PLAYLISTS))
        assertEquals(1, root.select(DockTab.PLAYLISTS).reselectsOf(DockTab.PLAYLISTS))
    }

    @Test
    fun `opening Search from another tab asks its field for focus, coming back to Library keeps its place`() {
        val search = NavState.start(DockTab.LIBRARY).select(DockTab.SEARCH)
        assertEquals(1, search.reselectsOf(DockTab.SEARCH))
        assertEquals(0, search.select(DockTab.LIBRARY).reselectsOf(DockTab.LIBRARY))
        assertEquals(2, search.select(DockTab.SEARCH).reselectsOf(DockTab.SEARCH))
    }

    // Notifications

    @Test
    fun `a tap on the AI's notifications opens Tools, AI lyrics`() {
        assertEquals(Page.Ai, destinationPage(DESTINATION_AI))
        assertNull(destinationPage(null))
        assertNull(destinationPage("radio"))
        val opened = NavState.start(DockTab.LIBRARY).show(FullScreen.CompanionNow).open(destinationPage(DESTINATION_AI)!!)
        assertEquals(DockTab.TOOLS, opened.tab)
        assertEquals(listOf<Page>(Page.Ai), opened.pages)
        assertEquals(emptyList<FullScreen>(), opened.views)
    }

    @Test
    fun `a tap on the player's notification shows the player over the tab it finds`() {
        assertEquals(FullScreen.Player, destinationView(DESTINATION_PLAYER))
        assertNull(destinationView(DESTINATION_AI))
        assertNull(destinationView(null))
        assertNull(destinationPage(DESTINATION_PLAYER))
        val shown = NavState.start(DockTab.LIBRARY).show(destinationView(DESTINATION_PLAYER)!!)
        assertEquals(DockTab.LIBRARY, shown.tab)
        assertEquals(listOf<FullScreen>(FullScreen.Player), shown.views)
    }

    @Test
    fun `the player's notification carries the player as its destination, apart from the AI's notifications`() {
        val service = java.io.File("src/main/java/app/lyrify/player/PlaybackService.kt").readText()
        assertTrue(service.contains(".putExtra(EXTRA_DESTINATION, DESTINATION_PLAYER)"))
        // The AI's notifications use request code 0 for MainActivity: the session's must differ, or one replaces the other.
        assertFalse(Regex("""PendingIntent\.getActivity\(\s*this,\s*0,""").containsMatchIn(service))
    }

    @Test
    fun `the AI run's and the model download's notifications carry the AI as their destination`() {
        // Unit tests run in the module's folder.
        // The download runs in the AI add-on, but KJB shows its notification.
        listOf("src/main/java/app/lyrify/overnight/AiWorker.kt", "src/main/java/app/lyrify/addon/AddonDownload.kt").forEach { path ->
            assertTrue(path, java.io.File(path).readText().contains(".putExtra(EXTRA_DESTINATION, DESTINATION_AI)"))
        }
    }

    @Test
    fun `a notification's tap, and Save to KJB's Open KJB, open their page also while KJB is open`() {
        // MainActivity is launched as standard: NEW_TASK alone brings KJB's task forward as it was and the page never
        // opens. With SINGLE_TOP the open MainActivity gets the destination in onNewIntent, as the player's notification
        // and Covers & tags' check do (CT1).
        val opens = mapOf(
            "downloads/DownloadService.kt" to "DESTINATION_DOWNLOADS",
            "overnight/AiWorker.kt" to "DESTINATION_AI",
            "addon/AddonDownload.kt" to "DESTINATION_AI",
            "ui/DownloadsScreen.kt" to "DESTINATION_DOWNLOADS",
            "tags/CheckService.kt" to "DESTINATION_COVERS_TAGS",
        )
        opens.forEach { (path, destination) ->
            val source = File("src/main/java/app/lyrify/$path").readText()
            assertTrue(path, source.contains("MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_DESTINATION, $destination)"))
        }
        // Nowhere does KJB open MainActivity from outside its screens with NEW_TASK alone.
        val loose = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), loose)
    }

    @Test
    fun `the night run never starts the models' download from its own foreground service`() {
        // A second foreground type on WorkManager's one service could take mediaProcessing from the run: a model that did
        // not load is fetched again at the next hourly look, which starts the download before it goes into the foreground.
        val worker = java.io.File("src/main/java/app/lyrify/overnight/AiWorker.kt").readText()
        val discard = worker.substringAfter("private fun discard(").substringBefore("\n    }")
        assertFalse(discard, "AddonDownloads.start" in discard)
        val beforeForeground = worker.substringAfter("private suspend fun night(session: AddonSession)").substringBefore("if (!foreground(null))")
        assertTrue("AddonDownloads.startIfIdle" in beforeForeground)
    }

    // What shows

    @Test
    fun `the Now bar and the dock show over Library and Search pages and on Tools' root, never over a Tools page or a view`() {
        assertTrue(NavState.start(DockTab.LIBRARY).open(album).chrome)
        assertTrue(NavState.start(DockTab.PLAYLISTS).open(Page.Queues).chrome)
        assertTrue(NavState.start(DockTab.SEARCH).open(Page.SearchAll(SearchGroup.LYRICS, "rain")).chrome)
        assertTrue(NavState.start(DockTab.TOOLS).chrome)
        assertFalse(NavState.start(DockTab.TOOLS).open(Page.Duplicates).chrome)
        assertFalse(NavState.start(DockTab.LIBRARY).show(FullScreen.Player).chrome)
    }

    // Kept across a turn of the phone and process death

    @Test
    fun `the whole state survives a round trip`() {
        val nav = NavState.start(DockTab.LIBRARY)
            .open(album)
            .select(DockTab.SEARCH)
            .open(Page.SearchAll(SearchGroup.ARTISTS, "lantern"))
            .open(artist)
            .select(DockTab.TOOLS)
            .open(Page.Check("/music/b.flac"))
            .select(DockTab.LIBRARY)
            .select(DockTab.LIBRARY)
            .select(DockTab.LIBRARY)
            .show(FullScreen.EditTags(listOf(SongRef("/music/a.mp3", 7), SongRef("/music/b.flac"))))
        assertEquals(nav, NavState.decode(nav.encode()))
        val every = listOf(
            Page.Songs(SongGroup.NO_LYRICS, LyricsStatus.NOT_FOUND), Page.FoldersToSkip, Page.HiddenSongs, Page.SplitArtists, Page.Folder("Music/Rips"), Page.Genre("Folk"),
            Page.Playlist("p3"), Page.BuiltIn(BuiltInList.MOST_PLAYED), Page.UnknownAlbum,
            Page.Queue("q1"), Page.Listening, Page.TopList(TopKind.ARTISTS), Page.Tones, Page.Bookmarks, Page.Ai, Page.CoversTags, Page.TagHistory, Page.LibraryTabs,
            Page.Licences, Page.Licence("Media3"), Page.Settings(), Page.Settings(SettingsSection.LYRICS),
        ).fold(NavState.start(DockTab.LIBRARY)) { state, page -> state.copy(stacks = state.stacks + (DockTab.LIBRARY to state.stacks[DockTab.LIBRARY].orEmpty() + StackEntry(page))) }
            .show(FullScreen.SyncEditor(SongRef("/music/a.mp3"))).show(FullScreen.EditAlbum("Harbour Glass")).show(FullScreen.CompanionNow)
        assertEquals(every, NavState.decode(every.encode()))
        // Where pages came from, and the sheet Back asks for, too.
        val sheet = SheetReturn("list host", OpenSheet.Menu(SongRef("/music/a.mp3", 7), SheetPlace.Queue("q1", 3)))
        val origins = NavState.start(DockTab.SEARCH).show(FullScreen.Player).open(album).open(artist, sheet).back()!!.open(Page.Genre("Folk"), sheet)
        assertEquals(origins, NavState.decode(origins.encode()))
        val reopened = origins.back()!!
        assertEquals(sheet, reopened.reopen)
        assertEquals(reopened, NavState.decode(reopened.encode()))
    }

    @Test
    fun `Settings opened at a section opens on Tools, and Settings saved before sections reads as Settings from the top`() {
        val linked = NavState.start(DockTab.LIBRARY).open(Page.Settings(SettingsSection.LYRICS))
        assertEquals(DockTab.TOOLS, linked.tab)
        assertEquals(listOf<Page>(Page.Settings(SettingsSection.LYRICS)), linked.pages)
        val saved = """{"tab":"TOOLS","stacks":{"TOOLS":[{"type":"settings"}]}}"""
        assertEquals(listOf<Page>(Page.Settings()), NavState.decode(saved)!!.pages)
    }

    @Test
    fun `Settings on top gives way to Settings asked for at a section, rather than a second one above it`() {
        val settings = NavState.start(DockTab.LIBRARY).open(Page.Settings()).select(DockTab.LIBRARY)
        val linked = settings.open(Page.Settings(SettingsSection.LYRICS))
        assertEquals(listOf<Page>(Page.Settings(SettingsSection.LYRICS)), linked.pages)
        // And the other way, from the gear or Tools while Settings at a section is on top.
        assertEquals(listOf<Page>(Page.Settings()), linked.select(DockTab.LIBRARY).open(Page.Settings()).pages)
    }

    @Test
    fun `a page asked for again while it is on top stays as it is, and the navigator says it was asked for`() {
        val nav = Navigator(NavState.start(DockTab.LIBRARY))
        nav.open(Page.Settings(SettingsSection.LYRICS))
        val first = nav.opened
        nav.select(DockTab.LIBRARY)
        nav.open(Page.Settings(SettingsSection.LYRICS))

        assertEquals(listOf<Page>(Page.Settings(SettingsSection.LYRICS)), nav.state.pages)
        assertEquals(Page.Settings(SettingsSection.LYRICS), nav.opened.page)
        assertNotEquals(first, nav.opened)
    }

    @Test
    fun `a saved origin this version cannot read is dropped, its page kept`() {
        val saved = """{"tab":"LIBRARY","stacks":{"LIBRARY":[{"type":"artist","name":"Paper Lanterns","origin":{"tab":"SEARCH","views":[{"type":"karaoke"}]}}]}}"""
        val nav = NavState.decode(saved)!!
        assertEquals(listOf<Page>(artist), nav.pages)
        assertNull(nav.back()!!.reopen)
        assertEquals(DockTab.LIBRARY, nav.back()!!.tab)
    }

    @Test
    fun `a saved page or view this version does not know is dropped, the rest kept`() {
        // Favourites' "View all" (favouritesAll) is gone since its tabs came.
        val saved = """{"tab":"LIBRARY","stacks":{"LIBRARY":[{"type":"artist","name":"Paper Lanterns"},{"type":"hologram"},{"type":"album"},{"type":"favouritesAll","section":"ALBUMS"}]},
            |"history":["TOOLS","RADIO"],"views":[{"type":"player"},{"type":"karaoke"}],"reselects":{"SEARCH":2,"NOW":1}}""".trimMargin()
        val nav = NavState.decode(saved)!!
        assertEquals(listOf<Page>(artist), nav.pages)
        assertEquals(listOf(DockTab.TOOLS), nav.history)
        assertEquals(listOf<FullScreen>(FullScreen.Player), nav.views)
        assertEquals(mapOf(DockTab.SEARCH to 2), nav.reselects)
    }

    @Test
    fun `an unknown tab opens Library, and anything that is not KJB's saved state starts over on the last tab`() {
        assertEquals(DockTab.LIBRARY, NavState.decode("""{"tab":"NOW"}""")!!.tab)
        assertNull(NavState.decode("NOW"))
        assertNull(NavState.decode(""))
        assertEquals(NavState.start(DockTab.SEARCH), restoreNav("SONGS", DockTab.SEARCH))
        // 2.x kept its tab as an enum, not text.
        assertEquals(NavState.start(DockTab.TOOLS), restoreNav(Tab2x.NOW, DockTab.TOOLS))
        assertEquals(NavState.start(DockTab.TOOLS), restoreNav(null, DockTab.TOOLS))
        val nav = NavState.start(DockTab.SEARCH).open(artist)
        assertEquals(nav, restoreNav(nav.encode(), DockTab.TOOLS))
    }

    @Test
    fun `the app reopens on Playlists when it was the last tab`() {
        assertEquals(NavState.start(DockTab.PLAYLISTS), restoreNav(null, DockTab.PLAYLISTS))
        assertEquals(DockTab.PLAYLISTS, NavState.decode("""{"tab":"PLAYLISTS"}""")!!.tab)
        val nav = NavState.start(DockTab.PLAYLISTS).open(Page.Playlist("p3"))
        assertEquals(nav, restoreNav(nav.encode(), DockTab.LIBRARY))
    }

    /** Stands in for the 2.x shell's saved tab. */
    private enum class Tab2x { NOW }

    // The page already on top, opened again from the player or a song's sheet (review finding D)

    @Test
    fun `opening from the player the page already on top keeps the way back to the player`() {
        val onAlbum = NavState.start(DockTab.LIBRARY).open(album)
        val fromPlayer = onAlbum.show(FullScreen.Player).open(album)
        // Not opened twice, and the player gives way to it.
        assertEquals(listOf<Page>(album), fromPlayer.pages)
        assertEquals(emptyList<FullScreen>(), fromPlayer.views)
        assertEquals(listOf<FullScreen>(FullScreen.Player), fromPlayer.back()!!.views)
    }

    @Test
    fun `Go to album from a song's sheet on that album keeps the sheet to come back to`() {
        val sheet = SheetReturn("host", OpenSheet.Menu(SongRef("/Music/Harbour Glass/01 Paper Lantern.mp3")))
        val again = NavState.start(DockTab.LIBRARY).open(album).open(album, sheet)
        assertEquals(listOf<Page>(album), again.pages)
        assertEquals(sheet, again.back()!!.reopen)
        // Opened again plainly, the page keeps the origin it has.
        assertEquals(sheet, again.open(album).back()!!.reopen)
    }
}
