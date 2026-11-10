package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lyrify.BuildConfig
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.SongRef
import app.lyrify.settings.DockTab
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

/**
 * A page on a tab's stack, above the tab's root (Library's tabs, Search's field, Tools' rows). Every page of 3.0 is
 * declared here once; its entry composable lives in its owner's file and takes the page (AppShell's [PageContent]).
 * Stored by its type name and fields ([NavState.encode]), so a rename here needs a [SerialName] that keeps the old one.
 */
@Serializable
sealed interface Page {
    /** Library > Songs with a lyrics filter: the AI's results and Check these link here (LibraryScreen.kt, L1). */
    @Serializable @SerialName("songs")
    data class Songs(val group: SongGroup? = null, val status: LyricsStatus? = null) : Page

    /** Settings > Folders to skip, which lists the library's folders (LibraryScreen.kt, L1). */
    @Serializable @SerialName("foldersToSkip")
    data object FoldersToSkip : Page

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

    /** Built for you: Favourites (with its sections), Most played, Recently played, Recently added. */
    @Serializable @SerialName("builtIn")
    data class BuiltIn(val list: BuiltInList) : CollectionPages

    @Serializable @SerialName("unknownAlbum")
    data object UnknownAlbum : CollectionPages

    /** "View all" of one section of Favourites. */
    @Serializable @SerialName("favouritesAll")
    data class FavouritesAll(val section: FavouriteSection) : CollectionPages

    /** Queues (QueueScreens.kt, P1). */
    sealed interface QueuePages : Page

    @Serializable @SerialName("queues")
    data object Queues : QueuePages

    @Serializable @SerialName("queue")
    data class Queue(val id: String) : QueuePages

    /** Your listening (ListeningScreen.kt, S1). */
    sealed interface ListeningPages : Page

    @Serializable @SerialName("listening")
    data object Listening : ListeningPages

    /** "View all" of a top list. */
    @Serializable @SerialName("topList")
    data class TopList(val kind: TopKind) : ListeningPages

    /** "View all" of one group of results for [query] (SearchScreen.kt, L2). */
    @Serializable @SerialName("searchAll")
    data class SearchAll(val group: SearchGroup, val query: String) : Page

    // Tools: full screens, without the dock and the Now bar.

    /** Check these, at [path] when it was opened from a song. */
    @Serializable @SerialName("check")
    data class Check(val path: String?) : Page

    @Serializable @SerialName("duplicates")
    data object Duplicates : Page

    @Serializable @SerialName("tones")
    data object Tones : Page

    @Serializable @SerialName("ai")
    data object Ai : Page

    /** Covers & tags (CoversTagsScreen.kt, T2). */
    @Serializable @SerialName("coversTags")
    data object CoversTags : Page

    /** Covers & tags > History, every edit with Undo (EditTagsScreen.kt, T3). */
    @Serializable @SerialName("tagHistory")
    data object TagHistory : Page

    @Serializable @SerialName("settings")
    data object Settings : Page

    /** Settings > Library tabs. */
    @Serializable @SerialName("libraryTabs")
    data object LibraryTabs : Page
}

enum class BuiltInList { FAVOURITES, MOST_PLAYED, RECENTLY_PLAYED, RECENTLY_ADDED }

enum class FavouriteSection { SONGS, ALBUMS, ARTISTS, FOLDERS, PLAYLISTS }

enum class TopKind { SONGS, ARTISTS, ALBUMS }

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

/**
 * Where the user is: the [tab] shown, each tab's own page stack ([stacks], the pages above its root, the top last), the
 * tabs to go back to ([history], each once, the latest last), and the full-screen [views] over everything (the top
 * last). [reselects] counts, per tab, taps on it while at its root: its list goes to the top. Pure, so the back rules
 * are tested on the JVM; the shell keeps it in a [Navigator].
 */
@Immutable
data class NavState(
    val tab: DockTab,
    val stacks: Map<DockTab, List<Page>> = emptyMap(),
    val history: List<DockTab> = emptyList(),
    val views: List<FullScreen> = emptyList(),
    val reselects: Map<DockTab, Int> = emptyMap(),
) {
    /** The shown tab's pages above its root. */
    val pages: List<Page> get() = stacks[tab].orEmpty()

    /** How often [tab] was tapped while at its root: each new count sends its list to the top. */
    fun reselectsOf(tab: DockTab): Int = reselects[tab] ?: 0

    /** The Now bar and the dock show: over Library and Search pages and on Tools' root; Tools pages and views are full screens. */
    val chrome: Boolean get() = views.isEmpty() && (tab != DockTab.TOOLS || pages.isEmpty())

    /**
     * A dock tap: another tab (the one left joins the history), or the tab shown popped to its root, or at its root to
     * the top. Opening Search also counts for Search: its field takes focus whenever the tab opens.
     */
    fun select(tab: DockTab): NavState = when {
        tab == DockTab.SEARCH && tab != this.tab -> visit(tab).copy(views = emptyList(), reselects = reselects + (tab to reselectsOf(tab) + 1))
        tab != this.tab -> visit(tab).copy(views = emptyList())
        pages.isNotEmpty() -> copy(stacks = stacks - tab, views = emptyList())
        else -> copy(reselects = reselects + (tab to reselectsOf(tab) + 1), views = emptyList())
    }

    /** Opens [page] on its tab ([homeOf]), closing the views; the page on top already is not opened twice. */
    fun open(page: Page): NavState {
        val home = homeOf(page, tab, views.isNotEmpty())
        val moved = visit(home).copy(views = emptyList())
        val stack = moved.pages
        return if (stack.lastOrNull() == page) moved else moved.copy(stacks = stacks + (home to stack + page))
    }

    /** Shows [view] over everything; the view on top already is not shown twice. */
    fun show(view: FullScreen): NavState = if (views.lastOrNull() == view) this else copy(views = views + view)

    /** Closes [view] and the views above it; nothing when it is not shown. */
    fun close(view: FullScreen): NavState {
        val at = views.indexOf(view)
        return if (at < 0) this else copy(views = views.take(at))
    }

    /** Back: the top view, else the top page, else the last tab in the history; null when only leaving the app is left. */
    fun back(): NavState? = when {
        views.isNotEmpty() -> copy(views = views.dropLast(1))
        pages.isNotEmpty() -> copy(stacks = stacks + (tab to pages.dropLast(1)))
        history.isNotEmpty() -> copy(tab = history.last(), history = history.dropLast(1))
        else -> null
    }

    private fun visit(tab: DockTab): NavState = if (tab == this.tab) this else copy(tab = tab, history = history - tab + this.tab)

    /** The state as text, for the saved instance state: tabs and pages by name, each page and view on its own. */
    fun encode(): String = JSON.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "tab" to JsonPrimitive(tab.name),
                "stacks" to JsonObject(stacks.filterValues { it.isNotEmpty() }.entries.associate { (tab, pages) -> tab.name to JsonArray(pages.map { JSON.encodeToJsonElement(Page.serializer(), it) }) }),
                "history" to JsonArray(history.map { JsonPrimitive(it.name) }),
                "views" to JsonArray(views.map { JSON.encodeToJsonElement(FullScreen.serializer(), it) }),
                "reselects" to JsonObject(reselects.entries.associate { (tab, count) -> tab.name to JsonPrimitive(count) }),
            ),
        ),
    )

    companion object {
        fun start(tab: DockTab): NavState = NavState(tab)

        /**
         * The state in [text], read leniently: a page, view or history tab this version does not know is dropped and the
         * rest kept; an unknown tab opens Library. Null when [text] is no saved state at all.
         */
        fun decode(text: String): NavState? {
            val root = try {
                JSON.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            val stacks = (root["stacks"] as? JsonObject).orEmpty().mapNotNull { (name, pages) ->
                val tab = dockTab(name) ?: return@mapNotNull null
                tab to (pages as? JsonArray).orEmpty().mapNotNull { decodeOrNull(it, Page.serializer()) }
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
            )
        }

        /** Keeps a [Navigator] across a turn of the phone and process death; anything else saved there (2.x's tab) starts over. */
        fun saver(lastTab: DockTab): Saver<Navigator, Any> = Saver(save = { it.state.encode() }, restore = { Navigator(restoreNav(it, lastTab)) })

        private fun dockTab(name: String): DockTab? = DockTab.entries.firstOrNull { it.name == name }

        private fun <T> decodeOrNull(element: JsonElement, serializer: KSerializer<T>): T? = try {
            JSON.decodeFromJsonElement(serializer, element)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** The extra on a notification's tap that names where KJB opens ([destinationPage]); MainActivity reads it. */
const val EXTRA_DESTINATION: String = "app.lyrify.destination"

/** The AI's notifications (the night's run, the model download) open Tools > AI lyrics. */
const val DESTINATION_AI: String = "ai"

/** The page [destination] names; null for none, or one this version does not know. */
fun destinationPage(destination: String?): Page? = when (destination) {
    DESTINATION_AI -> Page.Ai
    else -> null
}

/** The saved-state key of the page at the top of [stack] on [tab], or of the tab's root when it is empty. */
fun pageKey(tab: DockTab, stack: List<Page>): String =
    tab.name + "/" + stack.size + stack.lastOrNull()?.let { "/" + JSON.encodeToString(Page.serializer(), it) }.orEmpty()

/** The saved-state keys of every page on [stacks] and of every tab's root. */
fun pageKeys(stacks: Map<DockTab, List<Page>>): Set<String> =
    DockTab.entries.flatMap { tab -> stacks[tab].orEmpty().let { stack -> (0..stack.size).map { pageKey(tab, stack.take(it)) } } }.toSet()

/** The saved state in [saved] when it is KJB's, else a fresh start on [lastTab], the tab kept in settings.json. */
fun restoreNav(saved: Any?, lastTab: DockTab): NavState = (saved as? String)?.let(NavState::decode) ?: NavState.start(lastTab)

/**
 * The tab [page] opens on from [from]: Tools pages on Tools; Search's "View all" on Search; a collection opened from
 * Search stays there unless a view (the player) is open; everything else on Library.
 */
fun homeOf(page: Page, from: DockTab, viewOpen: Boolean): DockTab = when (page) {
    is Page.CollectionPages -> if (from == DockTab.SEARCH && !viewOpen) DockTab.SEARCH else DockTab.LIBRARY
    is Page.SearchAll -> DockTab.SEARCH
    is Page.Songs, is Page.QueuePages, is Page.ListeningPages -> DockTab.LIBRARY
    is Page.Check, Page.Duplicates, Page.Tones, Page.Ai, Page.CoversTags, Page.TagHistory, Page.Settings, Page.LibraryTabs, Page.FoldersToSkip -> DockTab.TOOLS
}

private val JSON = Json { ignoreUnknownKeys = true }

// In the composition

/** The shell's [NavState], one per activity; pages move around through it ([LocalNavigator]). Main thread only. */
@Stable
class Navigator(initial: NavState) {
    var state: NavState by mutableStateOf(initial)
        private set

    fun select(tab: DockTab) {
        state = state.select(tab)
    }

    fun open(page: Page) {
        state = state.open(page)
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

    /** False when there is nothing left to go back to: the phone's back then leaves the app. */
    fun back(): Boolean {
        state = state.back() ?: return false
        return true
    }
}

/** The shell's [Navigator]; the activity's shell provides it. */
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator: AppShell provides it") }

/** What the shell lends a page under its Now bar and dock: the glass they blur, and the room they take at the bottom. */
@Immutable
data class ShellChrome(val backdrop: GlassBackdrop?, val bottom: Dp)

val LocalShellChrome = compositionLocalOf { ShellChrome(backdrop = null, bottom = 0.dp) }

/**
 * A page with its own top bar: the bar (given whether content is under it and the glass to blur) over [content], whose
 * padding keeps it clear of the bar and of the shell's Now bar and dock. The content is what the bar's glass and the
 * dock's glass blur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageFrame(topBar: @Composable (under: Boolean, backdrop: GlassBackdrop) -> Unit, content: @Composable (PaddingValues) -> Unit) {
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
        Box(Modifier.fillMaxSize().glassSource(own, blur).then(chrome.backdrop?.let { Modifier.glassSource(it, blur) } ?: Modifier)) {
            content(PaddingValues(top = padding.calculateTopPadding(), bottom = chrome.bottom))
        }
    }
}

/**
 * A page or view whose milestone has not landed: a back arrow, and in debug builds the milestone that fills it. R1
 * fails the build while one is left.
 */
@Composable
fun PageStub(milestone: String, onBack: () -> Unit) {
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(if (BuildConfig.DEBUG) "Coming in $milestone" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {}
    }
}
