package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lyrify.data.LyricsStatus
import app.lyrify.library.SongRef
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.SettingsSection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A page on a tab's stack, above the tab's root (Library's tabs, Playlists' list, Search's field, Tools' rows). Every
 * page of 3.0 is declared here once; its entry composable lives in its owner's file and takes the page (AppShell's
 * [PageContent]). Stored by its type name and fields ([NavState.encode]), so a rename here needs a [SerialName] that
 * keeps the old one.
 */
@Serializable
sealed interface Page {
    /** Library > Songs with a lyrics filter: the AI's results and Check these link here (LibraryScreen.kt, L1). */
    @Serializable @SerialName("songs")
    data class Songs(val group: SongGroup? = null, val status: LyricsStatus? = null) : Page

    /** Settings > Folders to skip, which lists the library's folders (LibraryHiding.kt). */
    @Serializable @SerialName("foldersToSkip")
    data object FoldersToSkip : Page

    /** Settings > Hidden songs, with Unhide (LibraryHiding.kt, lane F2). */
    @Serializable @SerialName("hiddenSongs")
    data object HiddenSongs : Page

    /** Settings > Suggestions > Songs you said never to suggest, and Suggestions health (SuggestionScreens.kt, SG1). */
    @Serializable @SerialName("neverSuggest")
    data object NeverSuggest : Page

    @Serializable @SerialName("suggestionsHealth")
    data object SuggestionsHealth : Page

    /** Settings > Split artists: the separators and the names never split (ArtistNames.kt, TA1). */
    @Serializable @SerialName("splitArtists")
    data object SplitArtists : Page

    /** A collection's page (CollectionScreens.kt, L1). */
    sealed interface CollectionPages : Page

    /** An album, by the library's album key (album name and album artist). */
    @Serializable @SerialName("album")
    data class Album(val key: String) : CollectionPages

    @Serializable @SerialName("artist")
    data class Artist(val name: String) : CollectionPages

    /** A folder, relative to its volume ("Music/Rips"). */
    @Serializable @SerialName("folder")
    data class Folder(val path: String) : CollectionPages

    @Serializable @SerialName("genre")
    data class Genre(val name: String) : CollectionPages

    /** One of the user's playlists. */
    @Serializable @SerialName("playlist")
    data class Playlist(val id: String) : CollectionPages

    /** Built for you: Favourites (with its tabs), Most played, Recently played, Recently added. */
    @Serializable @SerialName("builtIn")
    data class BuiltIn(val list: BuiltInList) : CollectionPages

    @Serializable @SerialName("unknownAlbum")
    data object UnknownAlbum : CollectionPages

    /** Queues (QueueScreens.kt, P1). */
    sealed interface QueuePages : Page

    @Serializable @SerialName("queues")
    data object Queues : QueuePages

    @Serializable @SerialName("queue")
    data class Queue(val id: String) : QueuePages

    /** Insights, which grew out of Your listening (ListeningScreen.kt, S1; KJB 3.2). */
    sealed interface ListeningPages : Page

    @Serializable @SerialName("listening")
    data object Listening : ListeningPages

    /** "View all" of a top list. */
    @Serializable @SerialName("topList")
    data class TopList(val kind: TopKind) : ListeningPages

    /** A month's recap, by its month ("2026-10"): from the recap card and Insights' recaps. */
    @Serializable @SerialName("recap")
    data class Recap(val month: String) : ListeningPages

    /** Every month's recap, the latest first: "View all" of Insights' recaps. */
    @Serializable @SerialName("recaps")
    data object Recaps : ListeningPages

    /** Suggestions' lists (SuggestionScreens.kt, SG1). */
    sealed interface SuggestionPages : Page

    /** Today's For you. */
    @Serializable @SerialName("forYou")
    data object ForYou : SuggestionPages

    /** More like this, from a song (its path), an album (its key) or an artist (their name). */
    @Serializable @SerialName("moreLike")
    data class MoreLike(val seed: SeedKind, val key: String) : SuggestionPages

    /** "View all" of one group of results for [query] (SearchScreen.kt, L2). */
    @Serializable @SerialName("searchAll")
    data class SearchAll(val group: SearchGroup, val query: String) : Page

    // Tools: full screens, without the dock and the Now bar.

    /** Check these, at [path] when it was opened from a song. */
    @Serializable @SerialName("check")
    data class Check(val path: String?) : Page

    @Serializable @SerialName("duplicates")
    data object Duplicates : Page

    /** Trash: the songs in the phone's trash, Restore and Delete forever (TrashScreen.kt). */
    @Serializable @SerialName("trash")
    data object Trash : Page

    @Serializable @SerialName("tones")
    data object Tones : Page

    /** Bookmarks: every bookmark and its note, by episode, song or book (BookmarksScreen.kt, BM1). */
    @Serializable @SerialName("bookmarks")
    data object Bookmarks : Page

    @Serializable @SerialName("ai")
    data object Ai : Page

    /** Downloads: links saved with the Downloads add-on (DownloadsScreen.kt, DL1). */
    @Serializable @SerialName("downloads")
    data object Downloads : Page

    /** Huddle: friends, what they sent and play, and invites (HuddleScreen.kt, HA1). */
    @Serializable @SerialName("huddle")
    data object Huddle : Page

    /** Covers & tags (CoversTagsScreen.kt, T2). */
    @Serializable @SerialName("coversTags")
    data object CoversTags : Page

    /** Covers & tags > History, every edit with Undo (EditTagsScreen.kt, T3). */
    @Serializable @SerialName("tagHistory")
    data object TagHistory : Page

    /** Settings; a link into one of its sections opens it there and starts at it (Library's Can't read your songs, Lyrics). */
    @Serializable @SerialName("settings")
    data class Settings(val section: SettingsSection? = null) : Page

    /** Settings > Library tabs. */
    @Serializable @SerialName("libraryTabs")
    data object LibraryTabs : Page

    /** Settings > Display > Bottom bar (BottomBarScreen.kt). */
    @Serializable @SerialName("bottomBar")
    data object BottomBar : Page

    /** Settings > About > Licences (LicencesScreen.kt, R1). */
    @Serializable @SerialName("licences")
    data object Licences : Page

    /** One row of Licences, by its name: what it is and its licence's full text. */
    @Serializable @SerialName("licence")
    data class Licence(val name: String) : Page
}

enum class BuiltInList { FAVOURITES, MOST_PLAYED, RECENTLY_PLAYED, RECENTLY_ADDED }

enum class TopKind { SONGS, ARTISTS, ALBUMS, GENRES }

enum class SearchGroup { SONGS, ARTISTS, ALBUMS, FOLDERS, PLAYLISTS, LYRICS }

/** A full-screen view over the tabs, the dock and the Now bar; each has its entry composable in its owner's file. */
@Serializable
sealed interface FullScreen {
    /** The player (PlayerScreen.kt, P1). */
    @Serializable @SerialName("player")
    data object Player : FullScreen

    /** Full lyrics, from the player (LyricsViews.kt, P3). */
    @Serializable @SerialName("fullLyrics")
    data object FullLyrics : FullScreen

    /** Make synced or Edit timing (SyncEditor.kt, P3). */
    @Serializable @SerialName("syncEditor")
    data class SyncEditor(val song: SongRef) : FullScreen

    /** Edit tags for one song or many (EditTagsScreen.kt, T3). */
    @Serializable @SerialName("editTags")
    data class EditTags(val songs: List<SongRef>) : FullScreen

    /** Edit album, by the library's album key; [byTitle]: its page lists the songs by title (EditTagsScreen.kt, T3). */
    @Serializable @SerialName("editAlbum")
    data class EditAlbum(val key: String, val byTitle: Boolean = false) : FullScreen

    /** The equalizer, from the player's "⋯" (AudioScreens.kt, A1). */
    @Serializable @SerialName("equalizer")
    data object Equalizer : FullScreen

    /** What another app plays, from the Now bar in companion mode (CompanionNowScreen.kt). */
    @Serializable @SerialName("companionNow")
    data object CompanionNow : FullScreen
}

/** A page on a tab's stack, and where Back from it goes when it was opened from a full-screen view or a song's sheet. */
@Immutable
data class StackEntry(val page: Page, val origin: Origin? = null)

/**
 * Where a page opened from the player (or another full-screen view), from a song's sheet or from a header's Huddle came
 * from: the [tab], the tab [history] and the [views] shown then, and the [sheet] to show again. Back from the page goes
 * back there.
 */
@Serializable
data class Origin(
    val tab: DockTab,
    val history: List<DockTab> = emptyList(),
    val views: List<FullScreen> = emptyList(),
    val sheet: SheetReturn? = null,
)

/**
 * The song sheet a page was opened from (Go to album, Go to artist): Back from that page shows [sheet] again, from the
 * sheet host at [at] (its place in the composition, [SongSheetsHost]), over its list as it was.
 */
@Serializable
data class SheetReturn(val at: String, val sheet: OpenSheet.Menu)

/**
 * Where the user is: the [tab] shown, each tab's own page stack ([stacks], the pages above its root, the top last), the
 * tabs to go back to ([history], each once, the latest last), and the full-screen [views] over everything (the top
 * last). [reselects] counts, per tab, taps on it while at its root: its list goes to the top. [libraryTab] is the top
 * tab Library's root shows, null for the one Library opens on ([libraryOpensOn]: the first in the user's order, or the
 * one they chose): null at a launch and whenever Library's icon lands on its root. [reopen] is the song sheet
 * Back has just come back to, until its host shows it ([Navigator.takeSheet]) or the next move. Pure, so the back
 * rules are tested on the JVM; the shell keeps it in a [Navigator].
 */
@Immutable
data class NavState(
    val tab: DockTab,
    val stacks: Map<DockTab, List<StackEntry>> = emptyMap(),
    val history: List<DockTab> = emptyList(),
    val views: List<FullScreen> = emptyList(),
    val reselects: Map<DockTab, Int> = emptyMap(),
    val libraryTab: LibraryTab? = null,
    val reopen: SheetReturn? = null,
) {
    /** The shown tab's pages above its root. */
    val pages: List<Page> get() = pagesOf(tab)

    /** [tab]'s pages above its root, the top last. */
    fun pagesOf(tab: DockTab): List<Page> = stacks[tab].orEmpty().map { it.page }

    /** How often [tab] was tapped while at its root: each new count sends its list to the top. */
    fun reselectsOf(tab: DockTab): Int = reselects[tab] ?: 0

    /** The Now bar and the dock show: over Library, Playlists and Search pages and on Tools' root; Tools pages and views are full screens. */
    val chrome: Boolean get() = views.isEmpty() && (tab != DockTab.TOOLS || pages.isEmpty())

    /**
     * A dock tap: another tab (the one left joins the history), or the tab shown popped to its root, or at its root to
     * the top. Opening Search also counts for Search: its field takes focus whenever the tab opens. Library's icon always
     * lands on the tab Library opens on (KJB 3.1: no longer always Songs): wherever it shows Library's root, that is its
     * top tab, and only a tap while there goes to the top. A page left open on Library keeps the top tab under it.
     */
    fun select(tab: DockTab): NavState = with(copy(views = emptyList(), reopen = null)) {
        when {
            tab == DockTab.SEARCH && tab != this.tab -> visit(tab).copy(reselects = reselects + (tab to reselectsOf(tab) + 1))
            tab != this.tab -> visit(tab).onFirstTab()
            pages.isNotEmpty() -> copy(stacks = stacks - tab).onFirstTab()
            tab == DockTab.LIBRARY && libraryTab != null -> copy(libraryTab = null)
            else -> copy(reselects = reselects + (tab to reselectsOf(tab) + 1))
        }
    }

    /** A tap on one of Library's top tabs; null for the tab Library opens on ([libraryTabPicked]). */
    fun pickLibraryTab(tab: LibraryTab?): NavState = copy(libraryTab = tab, reopen = null)

    /** Library at its root shows the tab it opens on; another tab, or Library with a page open, stays as it is. */
    private fun onFirstTab(): NavState = if (tab == DockTab.LIBRARY && pages.isEmpty()) copy(libraryTab = null) else this

    /**
     * Opens [page] on its tab ([homeOf], by the bar's items now, [docked]), closing the views; the page on top already is
     * not opened twice. Opened from a view (the player), from a song's [sheet] or with [keepOrigin] (the headers' Huddle), it
     * keeps where it came from ([Origin]), and Back from it goes there; the page already on top takes that new origin, so
     * Back still goes back to the player, the sheet or the page.
     */
    fun open(page: Page, sheet: SheetReturn? = null, docked: List<DockTab> = DOCK_TABS, keepOrigin: Boolean = false): NavState {
        val home = homeOf(page, tab, views.isNotEmpty(), docked)
        val origin = Origin(tab, history, views, sheet).takeIf { views.isNotEmpty() || sheet != null || keepOrigin }
        val moved = visit(home).copy(views = emptyList(), reopen = null)
        val stack = moved.stacks[home].orEmpty()
        val top = stack.lastOrNull()
        return when {
            // One Settings at a time: Settings asked for at a section (or from the top) takes the place of the one on top.
            top?.page is Page.Settings && page is Page.Settings && top.page != page -> moved.copy(stacks = stacks + (home to stack.dropLast(1) + StackEntry(page, origin)))
            top?.page != page -> moved.copy(stacks = stacks + (home to stack + StackEntry(page, origin)))
            origin == null -> moved
            else -> moved.copy(stacks = stacks + (home to stack.dropLast(1) + top.copy(origin = origin)))
        }
    }

    /** Shows [view] over everything; the view on top already is not shown twice. */
    fun show(view: FullScreen): NavState = if (views.lastOrNull() == view) copy(reopen = null) else copy(views = views + view, reopen = null)

    /** Closes [view] and the views above it; nothing when it is not shown. */
    fun close(view: FullScreen): NavState {
        val at = views.indexOf(view)
        return if (at < 0) this else copy(views = views.take(at), reopen = null)
    }

    /**
     * Back: the top view, else the top page (back where it was opened from, when that was a view or a song's sheet), else
     * the last tab in the history; null when only leaving the app is left.
     */
    fun back(): NavState? = when {
        views.isNotEmpty() -> copy(views = views.dropLast(1), reopen = null)
        pages.isNotEmpty() -> pop()
        history.isNotEmpty() -> copy(tab = history.last(), history = history.dropLast(1), reopen = null)
        else -> null
    }

    /** The shown tab without its top page, back where that page was opened from when it keeps it. */
    private fun pop(): NavState {
        val stack = stacks.getValue(tab)
        val rest = stack.dropLast(1)
        val popped = copy(stacks = if (rest.isEmpty()) stacks - tab else stacks + (tab to rest), reopen = null)
        val origin = stack.last().origin ?: return popped
        return popped.copy(tab = origin.tab, history = origin.history, views = origin.views, reopen = origin.sheet)
    }

    private fun visit(tab: DockTab): NavState = if (tab == this.tab) this else copy(tab = tab, history = history - tab + this.tab)

    /**
     * The state once only [reachable] tabs can be reached (the bar's items, and Search and Tools, whose buttons Library's
     * top bar shows when the bar leaves them off): a tab out of reach leaves the history, with its pages; shown, Library
     * takes its place. Nothing changes while every tab here is in reach.
     */
    fun within(reachable: Set<DockTab>): NavState {
        val gone = (stacks.keys + history + tab).filterNot { it in reachable }.toSet()
        if (gone.isEmpty()) return this
        val kept = copy(stacks = stacks - gone, history = history - gone, reselects = reselects - gone)
        return if (tab in reachable) kept else kept.copy(tab = DockTab.LIBRARY, history = kept.history - DockTab.LIBRARY, reopen = null)
    }

    /** The state as text, for the saved instance state: tabs and pages by name, each page (with its origin) and view on its own. */
    fun encode(): String = JSON.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "tab" to JsonPrimitive(tab.name),
                "stacks" to JsonObject(stacks.filterValues { it.isNotEmpty() }.entries.associate { (tab, entries) -> tab.name to JsonArray(entries.map(::entryJson)) }),
                "history" to JsonArray(history.map { JsonPrimitive(it.name) }),
                "views" to JsonArray(views.map { JSON.encodeToJsonElement(FullScreen.serializer(), it) }),
                "reselects" to JsonObject(reselects.entries.associate { (tab, count) -> tab.name to JsonPrimitive(count) }),
                "libraryTab" to JsonPrimitive(libraryTab?.name),
            ) + listOfNotNull(reopen?.let { REOPEN to JSON.encodeToJsonElement(SheetReturn.serializer(), it) }),
        ),
    )

    companion object {
        fun start(tab: DockTab): NavState = NavState(tab)

        /**
         * The state in [text], read leniently: a page, view or history tab this version does not know is dropped and the
         * rest kept, as is an origin it cannot read (its page stays); an unknown tab opens Library, and an unknown Library
         * top tab the one Library opens on. Null when [text] is no saved state at all.
         */
        fun decode(text: String): NavState? {
            val root = try {
                JSON.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            val stacks = (root["stacks"] as? JsonObject).orEmpty().mapNotNull { (name, entries) ->
                val tab = dockTab(name) ?: return@mapNotNull null
                tab to (entries as? JsonArray).orEmpty().mapNotNull { entry ->
                    decodeOrNull(entry, Page.serializer())?.let { page -> StackEntry(page, (entry as? JsonObject)?.get(ORIGIN)?.let { decodeOrNull(it, Origin.serializer()) }) }
                }
            }.toMap()
            return NavState(
                tab = (root["tab"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::dockTab) ?: DockTab.LIBRARY,
                stacks = stacks.filterValues { it.isNotEmpty() },
                history = (root["history"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::dockTab) }.distinct(),
                views = (root["views"] as? JsonArray).orEmpty().mapNotNull { decodeOrNull(it, FullScreen.serializer()) },
                reselects = (root["reselects"] as? JsonObject).orEmpty().mapNotNull { (name, count) ->
                    val tab = dockTab(name) ?: return@mapNotNull null
                    (count as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { tab to it }
                }.toMap(),
                libraryTab = (root["libraryTab"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { name -> LibraryTab.entries.firstOrNull { it.name == name } },
                reopen = root[REOPEN]?.let { decodeOrNull(it, SheetReturn.serializer()) },
            )
        }

        /** Keeps a [Navigator] across a turn of the phone and process death; anything else saved there (2.x's tab) starts over. */
        fun saver(lastTab: DockTab): Saver<Navigator, Any> = Saver(save = { it.state.encode() }, restore = { Navigator(restoreNav(it, lastTab)) })

        private fun dockTab(name: String): DockTab? = DockTab.entries.firstOrNull { it.name == name }

        /** A stack entry as its page's object, with its origin under [ORIGIN] when it has one. */
        private fun entryJson(entry: StackEntry): JsonElement {
            val page = JSON.encodeToJsonElement(Page.serializer(), entry.page)
            val origin = entry.origin ?: return page
            return JsonObject(page.jsonObject + (ORIGIN to JSON.encodeToJsonElement(Origin.serializer(), origin)))
        }

        private fun <T> decodeOrNull(element: JsonElement, serializer: KSerializer<T>): T? = try {
            JSON.decodeFromJsonElement(serializer, element)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private const val ORIGIN: String = "origin"
        private const val REOPEN: String = "reopen"
    }
}

/** The extra on a notification's tap that names where KJB opens ([destinationPage]); MainActivity reads it. */
const val EXTRA_DESTINATION: String = "app.lyrify.destination"

/** The AI's notifications (the night's run, the model download) open Tools > AI lyrics. */
const val DESTINATION_AI: String = "ai"

/** The downloads' notifications open Tools > Downloads. */
const val DESTINATION_DOWNLOADS: String = "downloads"

/** The notification of a Covers & tags check (tags/CheckService.kt) opens Tools > Covers & tags. */
const val DESTINATION_COVERS_TAGS: String = "covers-tags"

/** The home-screen widget's Queue button: this, then the id of the queue playing ([queueDestination]). */
private const val DESTINATION_QUEUE: String = "queue/"

/** Where the widget's Queue button opens KJB: the page of queue [id] (widget/, 3.1). */
fun queueDestination(id: String): String = DESTINATION_QUEUE + id

/** The page [destination] names; null for none, or one this version does not know. */
fun destinationPage(destination: String?): Page? = when (destination) {
    DESTINATION_AI -> Page.Ai
    DESTINATION_DOWNLOADS -> Page.Downloads
    DESTINATION_COVERS_TAGS -> Page.CoversTags
    else -> destination?.removePrefix(DESTINATION_QUEUE)?.takeIf { it != destination && it.isNotEmpty() }?.let { Page.Queue(it) }
}

/** The home-screen widget's cover opens the player (widget/, A3). */
const val DESTINATION_PLAYER: String = "player"

/** The full-screen view [destination] names; null for none. */
fun destinationView(destination: String?): FullScreen? = if (destination == DESTINATION_PLAYER) FullScreen.Player else null

/** The saved-state key of the page at the top of [stack] on [tab], or of the tab's root when it is empty. */
fun pageKey(tab: DockTab, stack: List<Page>): String =
    tab.name + "/" + stack.size + stack.lastOrNull()?.let { "/" + JSON.encodeToString(Page.serializer(), it) }.orEmpty()

/** The saved-state keys of every page on [state]'s stacks and of every tab's root. */
fun pageKeys(state: NavState): Set<String> =
    DockTab.entries.flatMap { tab -> state.pagesOf(tab).let { stack -> (0..stack.size).map { pageKey(tab, stack.take(it)) } } }.toSet()

/** The saved state in [saved] when it is KJB's, else a fresh start on [lastTab], the tab kept in settings.json. */
fun restoreNav(saved: Any?, lastTab: DockTab): NavState = (saved as? String)?.let(NavState::decode) ?: NavState.start(lastTab)

/**
 * The items the user can put on the bar besides the four that always were (KJB 3.1; Insights and Huddle in 3.2): each a
 * root with a stack of its own, which keeps what opens on it as Library and Playlists do.
 */
val BAR_ROOTS: Set<DockTab> = setOf(DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.BOOKS, DockTab.INSIGHTS, DockTab.HUDDLE)

/**
 * The tab [page] opens on from [from] ([viewOpen]: from a full-screen view, such as the player, over [from]): Tools
 * pages on Tools; Search's "View all" on Search; Library > Songs with a filter on Library. A playlist, Built for you
 * (Favourites among them), Queues and Insights open inside Library or Playlists when opened there (Insights inside Tools
 * too), else on Playlists. An album, artist, folder or genre opens inside Search or Playlists when opened there, else on
 * Library. Opened on Queues, Favourites, Downloads, Books or Insights ([BAR_ROOTS]), both stay there. With Playlists off the bar
 * ([docked]), what would open on it stays where it was opened, or opens on Library from the player or Tools (whose
 * pages are full screens).
 */
fun homeOf(page: Page, from: DockTab, viewOpen: Boolean, docked: List<DockTab> = DOCK_TABS): DockTab {
    val here = from.takeUnless { viewOpen }
    val own = here?.takeIf { it in BAR_ROOTS }
    val home = when (page) {
        is Page.Playlist, is Page.BuiltIn, is Page.QueuePages ->
            own ?: here?.takeIf { it == DockTab.LIBRARY || it == DockTab.PLAYLISTS } ?: DockTab.PLAYLISTS
        // Insights opened from Tools is a Tools page, as Downloads is.
        is Page.ListeningPages -> own ?: here?.takeIf { it == DockTab.LIBRARY || it == DockTab.PLAYLISTS || it == DockTab.TOOLS } ?: DockTab.PLAYLISTS
        is Page.CollectionPages, is Page.SuggestionPages -> own ?: here?.takeIf { it == DockTab.SEARCH || it == DockTab.PLAYLISTS } ?: DockTab.LIBRARY
        is Page.SearchAll -> DockTab.SEARCH
        is Page.Songs -> DockTab.LIBRARY
        is Page.Check, Page.Duplicates, Page.Trash, Page.Tones, Page.Bookmarks, Page.Ai, Page.Downloads, Page.CoversTags, Page.TagHistory, is Page.Settings, Page.LibraryTabs, Page.FoldersToSkip, Page.HiddenSongs, Page.SplitArtists,
        Page.Licences, is Page.Licence, Page.BottomBar, Page.NeverSuggest, Page.SuggestionsHealth, Page.Huddle -> DockTab.TOOLS
    }
    return if (home == DockTab.PLAYLISTS && DockTab.PLAYLISTS !in docked) here?.takeUnless { it == DockTab.TOOLS } ?: DockTab.LIBRARY else home
}

private val JSON = Json { ignoreUnknownKeys = true }

// In the composition

/** The page last asked for ([Navigator.open]), and how many were asked for before it in this activity. */
@Immutable
data class PageOpened(val page: Page?, val count: Int)

/** The shell's [NavState], one per activity; pages move around through it ([LocalNavigator]). Main thread only. */
@Stable
class Navigator(initial: NavState) {
    var state: NavState by mutableStateOf(initial)
        private set

    fun select(tab: DockTab) {
        state = state.select(tab)
    }

    fun pickLibraryTab(tab: LibraryTab?) {
        state = state.pickLibraryTab(tab)
    }

    /** The bar now holds [items], and the app reaches [reachable] tabs ([NavState.within]). */
    fun dock(items: List<DockTab>, reachable: Set<DockTab>) {
        docked = items
        state = state.within(reachable)
    }

    /** The bar's items now, which pages open by ([homeOf]); the shell keeps them current ([dock]). */
    var docked: List<DockTab> = DOCK_TABS
        private set

    /**
     * The last page asked for, a new value at each ask. A page asked for while it is on top already stays as it is (Settings
     * at Lyrics, from Library's link a second time), so it reads this to do what the ask means again. Not kept when the
     * activity is made again.
     */
    var opened: PageOpened by mutableStateOf(PageOpened(null, 0))
        private set

    fun open(page: Page, sheet: SheetReturn? = null, keepOrigin: Boolean = false) {
        state = state.open(page, sheet, docked, keepOrigin)
        opened = PageOpened(page, opened.count + 1)
    }

    fun show(view: FullScreen) {
        state = state.show(view)
    }

    fun close(view: FullScreen) {
        state = state.close(view)
    }

    /**
     * A page's selection bar while it selects songs (milestone L1): the shell shows it in place of the Now bar and the
     * dock. The page sets it when selection starts and clears it when selection ends or the page leaves.
     */
    var selectionBar: (@Composable () -> Unit)? by mutableStateOf(null)

    /**
     * The song sheet Back has just come back to, for the host at [at] to show; null when it is another host's or there is
     * none. Taken once.
     */
    fun takeSheet(at: String): OpenSheet.Menu? {
        val sheet = state.reopen?.takeIf { it.at == at } ?: return null
        state = state.copy(reopen = null)
        return sheet.sheet
    }

    /** False when there is nothing left to go back to: the phone's back then leaves the app. */
    fun back(): Boolean {
        state = state.back() ?: return false
        return true
    }
}

/** The shell's [Navigator]; the activity's shell provides it. */
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator: AppShell provides it") }

/** What the shell lends a page under its Now bar and dock: the room they take at the bottom. */
@Immutable
data class ShellChrome(val bottom: Dp)

val LocalShellChrome = compositionLocalOf { ShellChrome(bottom = 0.dp) }

/**
 * A page with its own top bar: the bar (given whether content is under it and the glass to blur) over [content], whose
 * padding keeps it clear of the bar and of the shell's Now bar and dock. The content is what the bar's glass and the
 * dock's glass blur. A tab's page in a [TabsFrame] shares the frame's bar instead, and keeps only its own scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageFrame(topBar: @Composable (under: Boolean, backdrop: GlassBackdrop) -> Unit, content: @Composable (PaddingValues) -> Unit) {
    LocalTabPage.current?.let { tab ->
        tab.Frame(content)
        return
    }
    val chrome = LocalShellChrome.current
    val bar = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val own = rememberGlassBackdrop()
    val blur = LocalGlassBlur.current
    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = { topBar(bar.contentUnder(), own) },
    ) { padding ->
        // Only this page's own glass takes its content: two pages drawn at once (a page moving in, a back swipe) never
        // share a layer, so neither can show the other's content.
        Box(Modifier.fillMaxSize().glassSource(own, blur)) {
            content(PaddingValues(top = padding.calculateTopPadding(), bottom = chrome.bottom))
        }
    }
}
