package app.lyrify.ui

import app.lyrify.data.LyricsStatus
import app.lyrify.library.SongRef
import app.lyrify.settings.DockTab
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
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.TOOLS).open(Page.Songs(status = LyricsStatus.AI_ALIGNED)).tab)
        assertEquals(DockTab.LIBRARY, NavState.start(DockTab.SEARCH).open(Page.Queues).tab)
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
        ).fold(NavState.start(DockTab.LIBRARY)) { state, page -> state.copy(stacks = state.stacks + (DockTab.LIBRARY to state.pages + page)) }
            .show(FullScreen.SyncEditor(SongRef("/music/a.mp3"))).show(FullScreen.EditAlbum("Harbour Glass")).show(FullScreen.CompanionNow)
        assertEquals(every, NavState.decode(every.encode()))
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

    /** Stands in for the 2.x shell's saved tab. */
    private enum class Tab2x { NOW }
}
