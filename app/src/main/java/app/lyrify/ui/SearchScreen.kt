package app.lyrify.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Folder
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.mostPlayed
import app.lyrify.library.recentlyAdded
import app.lyrify.library.recentlyPlayed
import app.lyrify.library.search.Hit
import app.lyrify.library.search.LibraryHits
import app.lyrify.library.search.LyricHit
import app.lyrify.library.search.Match
import app.lyrify.library.search.SearchGraph
import app.lyrify.library.search.SearchIndex
import app.lyrify.library.search.ranked
import app.lyrify.library.search.searchQueue
import app.lyrify.library.search.searchSource
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibrarySettings
import app.lyrify.stats.PlaysQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Search's pages: the field with recent searches or results, and "View all" of one group.

/** A list Search finds by its name: a built-in list or one of the user's playlists, the [page] it opens, how many [songs]. */
data class ListRow(val page: Page, val name: String, val songs: Int, @DrawableRes val icon: Int)

/**
 * What a search for [query] found, each group ranked. [lyrics] is null while the lyrics are still being searched, else
 * the songs read so far (the first [SEARCH_PREVIEW], or the pages "View all" read), and [lyricsCount] how many there are.
 */
data class SearchResults(
    val query: String,
    val songs: List<Hit<Song>> = emptyList(),
    val artists: List<Hit<Artist>> = emptyList(),
    val albums: List<Hit<Album>> = emptyList(),
    val folders: List<Hit<Folder>> = emptyList(),
    val lists: List<Hit<ListRow>> = emptyList(),
    val lyrics: List<LyricHit>? = null,
    val lyricsCount: Int = lyrics?.size ?: 0,
) {
    /** What a song tapped in the results plays ("Search · words"): the song results, then the [lyrics] read, if not among them. */
    val queue: List<Song> get() = searchQueue(songs.map { it.item }, lyrics.orEmpty().map { it.song })

    fun count(group: SearchGroup): Int = when (group) {
        SearchGroup.SONGS -> songs.size
        SearchGroup.ARTISTS -> artists.size
        SearchGroup.ALBUMS -> albums.size
        SearchGroup.FOLDERS -> folders.size
        SearchGroup.PLAYLISTS -> lists.size
        SearchGroup.LYRICS -> if (lyrics == null) 0 else lyricsCount
    }
}

/** How many results a group shows before "View all". */
const val SEARCH_PREVIEW: Int = 4

/** A group of results with its [count]; "View all" shows when it has more than [SEARCH_PREVIEW]. */
data class ResultGroup(val group: SearchGroup, val count: Int) {
    val viewAll: Boolean get() = count > SEARCH_PREVIEW
}

/** The groups with results, in the order they show: Songs, Artists, Albums, Folders, Playlists, In lyrics. */
fun resultGroups(results: SearchResults): List<ResultGroup> =
    SearchGroup.entries.map { ResultGroup(it, results.count(it)) }.filter { it.count > 0 }

/** How many of [group]'s results show: the first [SEARCH_PREVIEW], or every one in "View all" ([all]). */
fun shownCount(results: SearchResults, group: SearchGroup, all: Boolean): Int =
    results.count(group).let { if (all) it else minOf(it, SEARCH_PREVIEW) }

/**
 * Search, the second tab, at its root: the field at the top, focused when the tab opens and when Search is tapped
 * again, not when the phone turns. With the field empty, the recent searches, each with an "×"; else the results in
 * groups. The search goes among the recent ones when the keyboard's search key is pressed or a result is tapped.
 */
@Composable
fun SearchPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val recentList = rememberLazyListState()
    val resultList = rememberLazyListState()
    val reselects = nav.state.reselectsOf(DockTab.SEARCH)
    // Kept across a turn of the phone, so only opening the tab or tapping it again asks for the keyboard.
    var handled by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(reselects) {
        if (reselects == handled) return@LaunchedEffect
        handled = reselects
        recentList.scrollToItem(0)
        resultList.scrollToItem(0)
        // The field attaches its requester in the frame the tab shows.
        withFrameNanos { }
        try {
            focus.requestFocus()
            keyboard?.show()
        } catch (e: IllegalStateException) {
            // Not attached yet: the user taps the field.
        }
    }
    // New words start at the top; coming back to the tab or turning the phone keeps the place.
    var shownQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != shownQuery) {
            shownQuery = query
            resultList.scrollToItem(0)
        }
    }
    val recent = remember(context) { SearchGraph.recent(context) }
    val searches by recent.state.collectAsStateWithLifecycle()
    // The lyrics index looks again when Search opens and after lyrics were written or removed.
    val ledger = LocalLyrics.current.ledger
    LaunchedEffect(ledger) { SearchGraph.refreshLyrics() }
    val results = rememberSearchResults(query)
    val commit = { recent.add(query) }
    val ui = rememberLibraryUi()
    val sheets = rememberSongSheets()

    PageFrame(
        topBar = { under, backdrop ->
            SearchBar(query, { query = it }, focus, onSearch = {
                commit()
                keyboard?.hide()
            }, under, backdrop)
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()
        if (query.isBlank()) {
            RecentSearches(searches, recentList, onPick = { query = it }, onRemove = recent::remove, modifier)
        } else {
            val play = rememberPlay(commit)
            val reading by remember(context) { SearchGraph.lyrics(context).building }.collectAsStateWithLifecycle()
            LazyColumn(state = resultList, modifier = modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (results == null || ui == null) return@LazyColumn
                val groups = resultGroups(results)
                item(key = "count") { ResultCount(groups.sumOf { it.count }) }
                if (groups.isEmpty() && results.lyrics != null) {
                    item(key = "none") { EmptyState(EmptyArt.Icon(R.drawable.ic_search_off), stringResource(R.string.search_no_matches)) }
                }
                groups.forEach { group ->
                    item(key = "group ${group.group}") {
                        GroupHeader(group) {
                            commit()
                            nav.open(Page.SearchAll(group.group, results.query))
                        }
                    }
                    resultRows(results, group.group, all = false, ui, onSong = { play(results, it) }, onMore = sheets::openSong) {
                        commit()
                        nav.open(it)
                    }
                }
                if (reading) item(key = "reading") { ReadingLyrics() }
            }
        }
    }
    ui?.let { SongSheetsHost(sheets, it) }
}

/** "View all" of one group of results for the page's words: every result, with Shuffle and Play over songs. */
@Composable
fun SearchAllPage(page: Page.SearchAll) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val results = rememberSearchResults(page.query, lyricPages = list.takeIf { page.group == SearchGroup.LYRICS })
    val recent = remember(context) { SearchGraph.recent(context) }
    val commands = remember(context) { Lyrify.playback(context) }
    val play = rememberPlay { recent.add(page.query) }
    val title = stringResource(R.string.search_all_title, groupLabel(page.group), page.query.trim())
    val ui = rememberLibraryUi()
    val sheets = rememberSongSheets()
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
            if (results == null || ui == null) return@LazyColumn
            val songs = when (page.group) {
                SearchGroup.SONGS -> results.songs.map { it.item }
                SearchGroup.LYRICS -> results.lyrics.orEmpty().map { it.song }
                else -> null
            }
            item(key = "head") {
                if (songs == null) {
                    GroupHeader(ResultGroup(page.group, results.count(page.group)), onViewAll = null)
                } else {
                    val source = searchSource(page.query)
                    // In lyrics has read only the pages shown: Shuffle and Play read every song found.
                    fun every(start: (List<SongRef>) -> Unit) {
                        if (page.group == SearchGroup.LYRICS) scope.launch { start(lyricSongs(context, page.query).map { it.ref }) } else start(songs.map { it.ref })
                    }
                    SongsHeader(
                        count = results.count(page.group),
                        onShuffle = { every { commands.shuffle(it, source) } },
                        onPlay = { every { commands.play(it, 0, source) } },
                    )
                }
            }
            resultRows(results, page.group, all = true, ui, onSong = { play(results, it) }, onMore = sheets::openSong, onOpen = nav::open)
        }
    }
    ui?.let { SongSheetsHost(sheets, it) }
}

/**
 * Search's results for [query] as it is typed: the library's names at once, the lyrics a moment later (each key press
 * starts over), again when the library, the playlists or the lyrics index change. In lyrics reads its first
 * [SEARCH_PREVIEW] songs, or with [lyricPages] ("View all" of In lyrics) a page at a time as that list nears the end of
 * those read. Null while [query] is blank or the library is not read yet.
 */
@Composable
private fun rememberSearchResults(query: String, lyricPages: LazyListState? = null): SearchResults? {
    val context = LocalContext.current
    val state by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
    val playlists by remember(context) { LibraryGraph.playlists(context).state }.collectAsStateWithLifecycle(null)
    val settings by remember(context) { Lyrify.settings(context).settings }.collectAsStateWithLifecycle(null)
    val lyrics = remember(context) { SearchGraph.lyrics(context) }
    val version by lyrics.version.collectAsStateWithLifecycle()
    val library = (state as? LibraryState.Ready)?.library
    val shown = settings?.library ?: LibrarySettings()
    val names = builtInNames()
    val index by produceState<Pair<Library, SearchIndex>?>(null, library, shown.showFileNames) {
        value = library?.let { withContext(Dispatchers.Default) { it to SearchGraph.names(it, shown) } }
    }
    val lists by produceState(emptyList<ListRow>(), library, playlists, names) {
        value = library?.let { listRows(it, playlists ?: PlaylistsState(), names, Lyrify.plays(context)) }.orEmpty()
    }
    val results by produceState<SearchResults?>(null, query, index, lists, version) {
        val (searched, byName) = index?.takeIf { query.isNotBlank() } ?: run {
            value = null
            return@produceState
        }
        val before = value
        value = withContext(Dispatchers.Default) { namedResults(query, byName.find(query), ranked(lists, query) { it.name }, before) }
        // New words: the lyrics are searched once typing pauses (each key press starts over).
        if (before?.query != query) delay(LYRICS_DELAY_MS)
        val total = lyrics.count(query)
        if (lyricPages == null) {
            val first = withContext(Dispatchers.Default) { lyrics.find(query, searched, shown, limit = SEARCH_PREVIEW) }
            value = value?.copy(lyrics = first, lyricsCount = total)
            return@produceState
        }
        var read = emptyList<LyricHit>()
        var from = 0
        do {
            read = read + withContext(Dispatchers.Default) { lyrics.find(query, searched, shown, from, LYRICS_PAGE) }
            from += LYRICS_PAGE
            value = value?.copy(lyrics = read, lyricsCount = total)
            // The next page once the list comes within half a page of the rows read (its first item is the header).
            val rows = read.size
            snapshotFlow { lyricPages.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.first { it >= rows - LYRICS_PAGE / 2 }
        } while (from < total)
    }
    return results
}

/**
 * The results for [query] by name, with In lyrics from [before] only when it was for the same words (the index, the
 * library or the playlists changed), so it does not blink. New words wait for their own: the last words' lyric rows never
 * show under them or join their queue.
 */
fun namedResults(query: String, found: LibraryHits, lists: List<Hit<ListRow>>, before: SearchResults?): SearchResults {
    val kept = before?.takeIf { it.query == query }
    return SearchResults(query, found.songs, found.artists, found.albums, found.folders, lists, kept?.lyrics, kept?.lyricsCount ?: 0)
}

/** Every song In lyrics finds for [query], for Shuffle and Play in its "View all", whose list reads only the pages shown. */
private suspend fun lyricSongs(context: Context, query: String): List<Song> {
    val library = (LibraryGraph.repository(context).state.value as? LibraryState.Ready)?.library ?: return emptyList()
    val settings = Lyrify.settings(context).settings.first().library
    return withContext(Dispatchers.Default) { SearchGraph.lyrics(context).find(query, library, settings).map { it.song } }
}

/** The built-in lists, then the user's playlists, each with how many of its songs the library shows. Off the main thread. */
private suspend fun listRows(library: Library, playlists: PlaylistsState, names: Map<BuiltInList, String>, plays: PlaysQuery): List<ListRow> =
    withContext(Dispatchers.Default) {
        val counts = mapOf(
            BuiltInList.FAVOURITES to library.listed(playlists.favouriteSongs).size,
            BuiltInList.MOST_PLAYED to mostPlayed(plays, library).size,
            BuiltInList.RECENTLY_PLAYED to recentlyPlayed(plays, library).size,
            BuiltInList.RECENTLY_ADDED to recentlyAdded(library, System.currentTimeMillis()).size,
        )
        val icons = mapOf(
            BuiltInList.FAVOURITES to R.drawable.ic_queue_music,
            BuiltInList.MOST_PLAYED to R.drawable.ic_bar_chart,
            BuiltInList.RECENTLY_PLAYED to R.drawable.ic_history,
            BuiltInList.RECENTLY_ADDED to R.drawable.ic_schedule,
        )
        BuiltInList.entries.map { ListRow(Page.BuiltIn(it), names.getValue(it), counts.getValue(it), icons.getValue(it)) } +
            playlists.playlists.map { ListRow(Page.Playlist(it.id), it.name, library.listed(it.songs).size, R.drawable.ic_queue_music) }
    }

@Composable
private fun builtInNames(): Map<BuiltInList, String> = BuiltInList.entries.associateWith { stringResource(it.words) }

/** A song tapped in the results: the search goes among the recent ones, and the results' queue plays from the song. */
@Composable
private fun rememberPlay(onCommit: () -> Unit): (SearchResults, Song) -> Unit {
    val context = LocalContext.current
    val commands = remember(context) { Lyrify.playback(context) }
    return { results, song ->
        onCommit()
        val queue = results.queue
        commands.play(queue.map { it.ref }, queue.indexOf(song).coerceAtLeast(0), searchSource(results.query))
    }
}

/**
 * [group]'s rows: songs are the library's song rows (a tap plays from the results' queue, "⋯" opens the song sheet,
 * [onMore]), anything else opens its page.
 */
private fun LazyListScope.resultRows(
    results: SearchResults,
    group: SearchGroup,
    all: Boolean,
    ui: LibraryUi,
    onSong: (Song) -> Unit,
    onMore: (SongRef) -> Unit,
    onOpen: (Page) -> Unit,
) {
    val count = shownCount(results, group, all)
    when (group) {
        SearchGroup.SONGS -> items(results.songs.take(count), key = { "song ${it.item.path}" }) { hit ->
            val song = hit.item
            SongRow(song, ui, selecting = false, selected = false, onTap = { onSong(song) }, onLongPress = null, onMore = { onMore(song.ref) }, shownTitle = highlighted(hit.text, hit.match))
        }
        SearchGroup.ARTISTS -> items(results.artists.take(count), key = { "artist ${it.item.name}" }) { hit ->
            val songs = hit.item.songs.size
            NavResult(highlighted(hit.text, hit.match), pluralStringResource(R.plurals.search_songs, songs, formatCount(songs)), { onOpen(Page.Artist(hit.item.name)) }) {
                SongCover(SongArt(hit.item.songs.first().path), shape = CircleShape)
            }
        }
        SearchGroup.ALBUMS -> items(results.albums.take(count), key = { "album ${it.item.key}" }) { hit ->
            NavResult(highlighted(hit.text, hit.match), hit.item.artist, { onOpen(Page.Album(hit.item.key)) }) {
                SongCover(SongArt(hit.item.songs.first().path))
            }
        }
        SearchGroup.FOLDERS -> items(results.folders.take(count), key = { "folder ${it.item.path}" }) { hit ->
            val songs = hit.item.allSongs.size
            val line = stringResource(R.string.search_folder_line, pluralStringResource(R.plurals.search_songs, songs, formatCount(songs)), hit.item.path)
            NavResult(highlighted(hit.text, hit.match), line, { onOpen(Page.Folder(hit.item.path)) }) { IconTile(R.drawable.ic_folder_open) }
        }
        SearchGroup.PLAYLISTS -> items(results.lists.take(count), key = { "list ${it.item.page}" }) { hit ->
            val songs = hit.item.songs
            NavResult(highlighted(hit.text, hit.match), pluralStringResource(R.plurals.search_songs, songs, formatCount(songs)), { onOpen(hit.item.page) }) {
                IconTile(hit.item.icon)
            }
        }
        SearchGroup.LYRICS -> items(results.lyrics.orEmpty().take(count), key = { "lyrics ${it.song.path}" }) { hit ->
            val song = hit.song
            SongRow(song, ui, selecting = false, selected = false, onTap = { onSong(song) }, onLongPress = null, onMore = { onMore(song.ref) }, line = highlighted(hit.line, hit.match))
        }
    }
}

/** The field in its band: Clear while there is text; the keyboard's search key calls [onSearch]. */
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, focus: FocusRequester, onSearch: () -> Unit, under: Boolean, backdrop: GlassBackdrop) {
    TopBand(under, backdrop) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.statusBarsPadding().heightIn(min = 64.dp).padding(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 8.dp),
        ) {
            LyrifySearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = stringResource(R.string.search_field_hint),
                focus = focus,
                onSearch = onSearch,
                modifier = Modifier.weight(1f),
            ) {
                if (query.isNotEmpty()) {
                    ChromeIconButton(R.drawable.ic_close, stringResource(R.string.search_clear), {
                        onQuery("")
                        focus.requestFocus()
                    }, glass = false, size = 36.dp)
                }
            }
        }
    }
}

/** "Recent", then the recent searches (a tap puts one in the field, its "×" removes it), or "Nothing yet". */
@Composable
private fun RecentSearches(searches: List<String>?, state: LazyListState, onPick: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier) {
    LazyColumn(state = state, modifier = modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "title") {
            SectionTitle(stringResource(R.string.search_recent), Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 4.dp))
        }
        when {
            // Still being read: nothing rather than "Nothing yet".
            searches == null -> Unit
            searches.isEmpty() -> item(key = "none") {
                Text(
                    stringResource(R.string.search_recent_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
                )
            }
            else -> items(searches, key = { "recent $it" }) { search ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem().fillMaxWidth().padding(start = 8.dp, end = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPick(search) }
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 8.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Text(search, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ChromeIconButton(R.drawable.ic_close, stringResource(R.string.search_recent_remove, search), { onRemove(search) }, glass = false, size = 36.dp)
                }
            }
        }
    }
}

/** Said, not shown: TalkBack hears how many results there are as the words change. */
@Composable
private fun ResultCount(total: Int) {
    val text = pluralStringResource(R.plurals.search_results, total, total)
    Spacer(
        Modifier.fillMaxWidth().height(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = text
        },
    )
}

/** A group's name, its count, and "View all" when it has more than it shows ([onViewAll] null: this is "View all"). */
@Composable
private fun GroupHeader(group: ResultGroup, onViewAll: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = ScreenPadding, end = 4.dp, top = 4.dp),
    ) {
        Text(groupLabel(group.group), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(
            formatCount(group.count),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .heightIn(min = 22.dp)
                .wrapContentHeight()
                .padding(horizontal = 7.dp),
        )
        Spacer(Modifier.weight(1f))
        if (onViewAll != null && group.viewAll) LyrifyButton(stringResource(R.string.search_view_all), onClick = onViewAll, kind = ButtonKind.GHOST, compact = true)
    }
}

/** "View all" of songs: their count, Shuffle and Play. */
@Composable
private fun SongsHeader(count: Int, onShuffle: () -> Unit, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        Text(pluralStringResource(R.plurals.search_songs, count, formatCount(count)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        LyrifyButton(stringResource(R.string.search_shuffle), onClick = onShuffle, kind = ButtonKind.SECONDARY, enabled = count > 0, compact = true)
        LyrifyButton(stringResource(R.string.search_play), onClick = onPlay, icon = R.drawable.ic_play_arrow_fill, enabled = count > 0, compact = true)
    }
}

/** An artist, album, folder or list: its [lead], name (matched part highlighted) and one line under it; a tap opens it. */
@Composable
private fun NavResult(title: AnnotatedString, sub: String?, onClick: () -> Unit, lead: @Composable () -> Unit) {
    ResultRow(title, sub, listOfNotNull(title.text, sub).joinToString(", "), stringResource(R.string.search_open), onClick, lead)
}

@Composable
private fun ResultRow(title: AnnotatedString, sub: String?, description: String, clickLabel: String, onClick: () -> Unit, lead: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The press tints a pill inside the gutter, not a band across the screen.
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            .semantics { contentDescription = description }
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            lead()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) {
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A 48 dp tile with an icon: a folder or a list without a cover of its own. Decorative. */
@Composable
private fun IconTile(@DrawableRes icon: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

/** While the lyrics index reads the `.lrc` files for the first time (or many changed): In lyrics is not complete yet. */
@Composable
private fun ReadingLyrics() {
    Fact(R.drawable.ic_lyrics, stringResource(R.string.search_reading_lyrics), Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp))
}

/** [text] with the part that [match]ed on the brand's container. */
@Composable
private fun highlighted(text: String, match: Match): AnnotatedString {
    val brand = LocalBrandColors.current
    return remember(text, match, brand) {
        buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(background = brand.highlightContainer, color = brand.onHighlightContainer), match.start, match.end)
        }
    }
}

@Composable
private fun groupLabel(group: SearchGroup): String = stringResource(
    when (group) {
        SearchGroup.SONGS -> R.string.search_group_songs
        SearchGroup.ARTISTS -> R.string.search_group_artists
        SearchGroup.ALBUMS -> R.string.search_group_albums
        SearchGroup.FOLDERS -> R.string.search_group_folders
        SearchGroup.PLAYLISTS -> R.string.search_group_playlists
        SearchGroup.LYRICS -> R.string.search_group_lyrics
    },
)

/** After the last key press, before the lyrics are searched: the names show at once, the lyrics once typing pauses. */
private const val LYRICS_DELAY_MS: Long = 150

/** Songs "View all" of In lyrics reads at a time. */
private const val LYRICS_PAGE: Int = 50
