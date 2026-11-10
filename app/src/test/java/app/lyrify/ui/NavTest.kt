package app.lyrify.ui

import app.lyrify.data.LyricsStatus
import app.lyrify.library.SongRef
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(DockTab.TOOLS, start.open(Page.Settings).tab)
        assertEquals(DockTab.TOOLS, start.open(Page.FoldersToSkip).tab)
        assertEquals(DockTab.TOOLS, start.open(Page.Licences).tab)
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

    @Test
    fun `the Library icon opens Library on Songs, never on the last top tab used`() {
        val albums = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.ALBUMS)
        assertEquals(LibraryTab.ALBUMS, albums.libraryTab)
        // From Albums to Search, then the Library icon: Songs.
        assertEquals(LibraryTab.SONGS, albums.select(DockTab.SEARCH).select(DockTab.LIBRARY).libraryTab)
        // On Library's root on Albums, the icon goes to Songs; on Songs, to the top of its list.
        val songs = albums.select(DockTab.LIBRARY)
        assertEquals(LibraryTab.SONGS, songs.libraryTab)
        assertEquals(0, songs.reselectsOf(DockTab.LIBRARY))
        assertEquals(1, songs.select(DockTab.LIBRARY).reselectsOf(DockTab.LIBRARY))
        // A page popped to the root by the icon lands on Songs too.
        assertEquals(LibraryTab.SONGS, albums.open(album).select(DockTab.LIBRARY).libraryTab)
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
    fun `the app launched on Library opens on Songs, and a turn of the phone keeps the top tab shown`() {
        assertEquals(LibraryTab.SONGS, NavState.start(DockTab.LIBRARY).libraryTab)
        assertEquals(LibraryTab.SONGS, restoreNav(null, DockTab.LIBRARY).libraryTab)
        assertEquals(LibraryTab.SONGS, restoreNav(Tab2x.NOW, DockTab.LIBRARY).libraryTab)
        val albums = NavState.start(DockTab.LIBRARY).pickLibraryTab(LibraryTab.ALBUMS).select(DockTab.TOOLS)
        assertEquals(albums, NavState.decode(albums.encode()))
        assertEquals(LibraryTab.ALBUMS, restoreNav(albums.encode(), DockTab.LIBRARY).libraryTab)
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
        assertTrue(Regex("""BackHandler\(enabled = .*state\.pages\.isNotEmpty\(\).*\{ nav\.back\(\) }""").containsMatchIn(shell))
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
        listOf("src/main/java/app/lyrify/overnight/AiWorker.kt", "src/main/java/app/lyrify/models/ModelDownloadWorker.kt").forEach { path ->
            assertTrue(path, java.io.File(path).readText().contains(".putExtra(EXTRA_DESTINATION, DESTINATION_AI)"))
        }
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
            Page.Songs(SongGroup.NO_LYRICS, LyricsStatus.NOT_FOUND), Page.FoldersToSkip, Page.Folder("Music/Rips"), Page.Genre("Folk"),
            Page.Playlist("p3"), Page.BuiltIn(BuiltInList.MOST_PLAYED), Page.UnknownAlbum, Page.FavouritesAll(FavouriteSection.ALBUMS),
            Page.Queue("q1"), Page.Listening, Page.TopList(TopKind.ARTISTS), Page.Tones, Page.Ai, Page.CoversTags, Page.TagHistory, Page.LibraryTabs,
            Page.Licences, Page.Licence("Media3"),
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
    fun `a saved origin this version cannot read is dropped, its page kept`() {
        val saved = """{"tab":"LIBRARY","stacks":{"LIBRARY":[{"type":"artist","name":"Paper Lanterns","origin":{"tab":"SEARCH","views":[{"type":"karaoke"}]}}]}}"""
        val nav = NavState.decode(saved)!!
        assertEquals(listOf<Page>(artist), nav.pages)
        assertNull(nav.back()!!.reopen)
        assertEquals(DockTab.LIBRARY, nav.back()!!.tab)
    }

    @Test
    fun `a saved page or view this version does not know is dropped, the rest kept`() {
        val saved = """{"tab":"LIBRARY","stacks":{"LIBRARY":[{"type":"artist","name":"Paper Lanterns"},{"type":"hologram"},{"type":"album"}]},
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
}
