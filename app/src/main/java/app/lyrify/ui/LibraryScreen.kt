package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.Artist
import app.lyrify.library.FolderRow
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.RECENTLY_ADDED_DAYS
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.library.mostPlayed
import app.lyrify.library.nameLetter
import app.lyrify.library.recentlyAdded
import app.lyrify.library.recentlyPlayed
import app.lyrify.library.sortAlbums
import app.lyrify.library.sortArtists
import app.lyrify.library.sortSongs
import app.lyrify.player.QueueSource
import app.lyrify.player.SongsTabSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.LyricsFilter
import app.lyrify.settings.SortAlbums
import app.lyrify.settings.SortArtists
import app.lyrify.settings.SortSongs
import app.lyrify.stats.StatsGraph
import app.lyrify.tags.NameKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.time.Instant
import java.time.ZoneId

// Library's pages: its root with the tabs, the Playlists tab's root, Library > Songs with a lyrics filter, and Settings >
// Folders to skip.

/**
 * Library, the first tab, at its root: the text tabs ("Library tabs" in Settings shows, hides and orders them) over
 * Songs, Playlists, Albums, Artists, Folders and Genres, as pages that follow the finger left and right ([TabsFrame]):
 * each keeps its list and its place while another shows. Library's icon always opens it on Songs ([NavState.libraryTab]);
 * tapping it again on Songs goes to the top of its list.
 */
@Composable
fun LibraryPage() {
    val nav = LocalNavigator.current
    val ui = rememberLibraryUi() ?: return
    val tabs = ui.settings.library.tabs.filter { it.shown }.map { it.tab }.ifEmpty { listOf(LibraryTab.SONGS) }
    val tab = nav.state.libraryTab.takeIf { it in tabs } ?: tabs.first()
    val reselect = nav.state.reselectsOf(DockTab.LIBRARY)
    val sheets = rememberSongSheets()
    // The Playlists tab's "⋯" rows and its import sheet (B1).
    val m3u = rememberM3uActions()
    val pager = rememberTabPager(tabs.size, tabs.indexOf(tab)) { at -> tabs.getOrNull(at)?.takeIf { it != nav.state.libraryTab }?.let(nav::pickLibraryTab) }
    val labels = tabs.map { stringResource(it.label) }
    val topBar: @Composable (Boolean, GlassBackdrop) -> Unit = { under, backdrop ->
        Column {
            MainTopBar(DockTab.LIBRARY, under, backdrop, actions = { if (tabs.getOrNull(pager.pager.currentPage) == LibraryTab.PLAYLISTS) PlaylistsMenu(sheets, m3u) })
            TopBand(under, backdrop) { PagerTabs(pager, labels, Modifier.tourTarget(TourStop.TABS)) }
        }
    }
    val library = ui.library
    if (library == null) {
        PageFrame(topBar = topBar) { padding -> LibraryUnavailable(ui.state, Modifier.padding(padding)) }
    } else {
        TabsFrame(pager, tabs, topBar) { at ->
            when (val shown = tabs[at]) {
                LibraryTab.SONGS -> SongsTab(ui, library, reselect, topBar)
                else -> PageFrame(topBar = topBar) { padding ->
                    when (shown) {
                        LibraryTab.PLAYLISTS -> PlaylistsTab(ui, library, padding, reselect, sheets)
                        LibraryTab.ALBUMS -> AlbumsTab(ui, library, padding, reselect)
                        LibraryTab.ARTISTS -> ArtistsTab(ui, library, padding, reselect)
                        LibraryTab.FOLDERS -> FoldersTab(library, padding, reselect)
                        LibraryTab.GENRES -> GenresTab(library, padding, reselect)
                        LibraryTab.SONGS -> Unit
                    }
                }
            }
        }
    }
    SongSheetsHost(sheets, ui)
    M3uImportSheet(m3u)
}

/**
 * Playlists, the second tab, at its root: the same list as Library's Playlists tab ([PlaylistsTab]) and the same "⋯",
 * under its own top bar. Tapping Playlists again goes to the top of the list.
 */
@Composable
fun PlaylistsPage() {
    val nav = LocalNavigator.current
    val ui = rememberLibraryUi() ?: return
    val sheets = rememberSongSheets()
    val m3u = rememberM3uActions()
    val library = ui.library
    PageFrame(
        topBar = { under, backdrop ->
            MainTopBar(DockTab.PLAYLISTS, under, backdrop, actions = { PlaylistsMenu(sheets, m3u) })
        },
    ) { padding ->
        FadeThrough(library, Modifier.fillMaxSize(), kind = { it == null }) { shown ->
            if (shown == null) LibraryUnavailable(ui.state, Modifier.padding(padding)) else PlaylistsTab(ui, shown, padding, nav.state.reselectsOf(DockTab.PLAYLISTS), sheets)
        }
    }
    SongSheetsHost(sheets, ui)
    M3uImportSheet(m3u)
}

/** The Playlists list's "⋯": New playlist, then Export all and Import (B1). */
@Composable
private fun PlaylistsMenu(sheets: SongSheets, m3u: M3uActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { menu = true })
        LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
            MenuRow(stringResource(R.string.lib_new_playlist), R.drawable.ic_playlist_add, onClick = {
                menu = false
                sheets.show(OpenSheet.NewPlaylist(emptyList()))
            })
            PlaylistsM3uRows(m3u, close = { menu = false })
        }
    }
}

/** While the songs are read, nothing; when they cannot be, why, and the way to Settings. */
@Composable
private fun LibraryUnavailable(state: LibraryState, modifier: Modifier) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    LifecycleResumeEffect(state) {
        // All files access may have been turned on meanwhile.
        if (state == LibraryState.Unreadable) LibraryGraph.repository(context).refresh()
        onPauseOrDispose {}
    }
    if (state != LibraryState.Unreadable) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            EmptyArt.Icon(R.drawable.ic_folder_off),
            stringResource(R.string.lib_unreadable_title),
            text = stringResource(R.string.lib_unreadable),
            action = stringResource(R.string.settings_title),
            onAction = { nav.open(Page.Settings) },
        )
    }
}

/**
 * Scrolls [state] to the top whenever Library is tapped again at its root ([reselect] grows), on the tab shown only
 * ([LocalTabCurrent]: the others are kept, not shown); coming back keeps the place.
 */
@Composable
private fun ReselectToTop(reselect: Int, top: suspend () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(reselect) }
    val shown by rememberUpdatedState(LocalTabCurrent.current)
    LaunchedEffect(reselect) {
        if (reselect != handled) {
            handled = reselect
            if (shown) top()
        }
    }
}

/** Songs: every song in the chosen order and "Show" filter, with headings; the sort and filter sheet from the chip. */
@Composable
private fun SongsTab(ui: LibraryUi, library: Library, reselect: Int, topBar: @Composable (Boolean, GlassBackdrop) -> Unit) {
    val settings = ui.settings.library
    val ledger = ui.ledger
    val change = rememberSettingsChange()
    val listed by produceState<List<Song>?>(null, library, ledger, settings) {
        value = withContext(Dispatchers.Default) { songsTab(library.songs, { ledger.entries[it.path]?.status }, settings) }
    }
    val songs = listed.orEmpty()
    val now = remember(library) { System.currentTimeMillis() }
    val sections = remember(songs, settings) { sectionsOf(songs, settings, now, ZoneId.systemDefault()) }
    val state = rememberLazyListState()
    var sorting by rememberSaveable { mutableStateOf(false) }
    ReselectToTop(reselect) { state.animateScrollToItem(0) }
    val source = SongsTabSource
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, source, topBar, state, loaded = listed != null) { list ->
        item(key = "favourites", contentType = "favourites") { Box(rowMotion(reduced)) { FavouritesRowView(ui, library) } }
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, sort = songsOrder(settings), onSort = { sorting = true }) }
        }
        if (listed != null && songs.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                if (library.songs.isEmpty()) {
                    EmptyState(EmptyArt.Glyph, stringResource(R.string.songs_empty_title), rowMotion(reduced), text = stringResource(R.string.lib_no_songs))
                } else {
                    EmptyState(
                        EmptyArt.Icon(R.drawable.ic_search_off),
                        stringResource(R.string.songs_no_match),
                        rowMotion(reduced),
                        action = stringResource(R.string.lib_show_all),
                        onAction = { change { it.copy(library = it.library.copy(songFilter = LyricsFilter.ALL)) } },
                    )
                }
            }
        }
        sections.forEach { (section, start, slice) ->
            stickyHeader(key = "section $start", contentType = "section") { SectionChip(sectionWords(section)) }
            songRows(list, slice, offset = start, tour = true)
        }
    }
    if (sorting) {
        val counts = remember(library, ledger) { filterCounts(library.songs) { ledger.entries[it.path]?.status } }
        SortSheet(
            title = stringResource(R.string.lib_sort_filter),
            options = SortSongs.entries.map { it to stringResource(it.words) },
            selected = settings.songSort,
            onSelect = { sort -> change { it.copy(library = it.library.copy(songSort = sort)) } },
            onDismiss = { sorting = false },
        ) {
            SectionTitle(stringResource(R.string.lib_show), Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LyricsFilter.entries.forEach { filter ->
                    LyrifyChip(
                        selected = settings.songFilter == filter,
                        onClick = { change { it.copy(library = it.library.copy(songFilter = filter)) } },
                        role = Role.RadioButton,
                        count = counts[filter] ?: 0,
                    ) {
                        Text(stringResource(filter.words), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Favourites, pinned at the top of Songs as the Queues row is on Playlists, scrolling with the list: its heart and songs. */
@Composable
private fun FavouritesRowView(ui: LibraryUi, library: Library) {
    val nav = LocalNavigator.current
    val row = favouritesRow(remember(library, ui.playlists) { library.listed(ui.playlists.favouriteSongs).size })
    NavRow(stringResource(R.string.lib_favourites), row.line.text(), lead = { BuiltInTile(BuiltInList.FAVOURITES) }) { nav.open(row.page) }
}

/** The Songs tab's rows by heading: each heading, where its songs start in [songs], and its songs. */
private fun sectionsOf(songs: List<Song>, settings: LibrarySettings, now: Long, zone: ZoneId): List<Triple<ListSection, Int, List<Song>>> {
    val out = ArrayList<Triple<ListSection, Int, List<Song>>>()
    var start = 0
    while (start < songs.size) {
        val section = listSection(songs[start], settings.songSort, settings, now, zone)
        var end = start + 1
        while (end < songs.size && listSection(songs[end], settings.songSort, settings, now, zone) == section) end++
        out += Triple(section, start, songs.subList(start, end))
        start = end
    }
    return out
}

/** A heading's words. */
@Composable
private fun sectionWords(section: ListSection): String = when (section) {
    is ListSection.Letter -> section.letter
    is ListSection.Name -> section.name ?: stringResource(R.string.lib_no_name)
    is ListSection.Added -> stringResource(
        when (section.time) {
            AddedWhen.TODAY -> R.string.header_today
            AddedWhen.THIS_WEEK -> R.string.header_week
            AddedWhen.THIS_MONTH -> R.string.lib_this_month
            AddedWhen.EARLIER -> R.string.header_earlier
        },
    )
}

/** A heading over the rows as they scroll under it: a small capsule at the start. */
@Composable
private fun SectionChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp)) {
        SectionTitle(
            text,
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .heightIn(min = 28.dp)
                .wrapContentHeight()
                .padding(horizontal = 12.dp),
        )
    }
}

/** The Songs tab's chip: "Title", or "Title · Synced" while a filter is on. */
@Composable
private fun songsOrder(settings: LibrarySettings): String {
    val sort = stringResource(if (settings.songSort == SortSongs.RECENTLY_ADDED) R.string.lib_sort_recent_short else settings.songSort.words)
    return if (settings.songFilter == LyricsFilter.ALL) sort else stringResource(R.string.lib_after, sort, stringResource(settings.songFilter.short))
}

@get:StringRes
private val SortSongs.words: Int
    get() = when (this) {
        SortSongs.TITLE -> R.string.lib_sort_title
        SortSongs.ARTIST -> R.string.lib_sort_artist
        SortSongs.ALBUM -> R.string.lib_sort_album
        SortSongs.RECENTLY_ADDED -> R.string.lib_recently_added
    }

@get:StringRes
private val LyricsFilter.words: Int
    get() = when (this) {
        LyricsFilter.ALL -> R.string.lib_filter_all
        LyricsFilter.SYNCED -> R.string.lib_mark_synced_short
        LyricsFilter.PLAIN -> R.string.lib_filter_plain
        LyricsFilter.NONE -> R.string.lib_filter_none
    }

@get:StringRes
private val LyricsFilter.short: Int
    get() = when (this) {
        LyricsFilter.PLAIN -> R.string.lib_filter_plain_short
        else -> words
    }

/** Albums: a two-column grid of covers, "Unknown album" last; Play plays them all, album by album. */
@Composable
private fun AlbumsTab(ui: LibraryUi, library: Library, padding: PaddingValues, reselect: Int) {
    val nav = LocalNavigator.current
    val playback = rememberPlayback()
    val change = rememberSettingsChange()
    val order = ui.settings.library.albumSort
    val albums = remember(library, order) { sortAlbums(library.albums, order) }
    val songs = remember(albums, library) { albums.flatMap { it.songs } + library.unknownAlbum }
    val source = QueueSource(SourceKind.SONGS, "tab:albums", stringResource(R.string.library_tab_albums))
    val state = rememberLazyGridState()
    var sorting by rememberSaveable { mutableStateOf(false) }
    ReselectToTop(reselect) { state.animateScrollToItem(0) }
    val reduced = LocalReducedMotion.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Box(rowMotion(reduced)) {
                ListHeader(
                    albums.size + if (library.unknownAlbum.isEmpty()) 0 else 1,
                    R.plurals.lib_noun_albums,
                    onShuffle = { playback.shuffle(songs.map { it.ref }, source) },
                    onPlay = { playback.play(songs.map { it.ref }, 0, source) },
                    songs = songs.size,
                    sort = stringResource(order.words),
                    onSort = { sorting = true },
                    gutter = 0.dp,
                )
            }
        }
        item(key = "same", span = { GridItemSpan(maxLineSpan) }, contentType = "same") { SameNamesLine(NameKind.ALBUM, library, rowMotion(reduced)) }
        items(albums, key = { it.key }, contentType = { "album" }) { album ->
            AlbumCard(album.title, album.artist, album.songs.firstOrNull(), rowMotion(reduced)) { nav.open(Page.Album(album.key)) }
        }
        if (library.unknownAlbum.isNotEmpty()) {
            item(key = "unknown", contentType = "album") {
                val count = library.unknownAlbum.size
                AlbumCard(stringResource(R.string.lib_unknown_album), pluralStringResource(R.plurals.lib_songs, count, count), null, rowMotion(reduced)) { nav.open(Page.UnknownAlbum) }
            }
        }
    }
    if (sorting) {
        SortSheet(
            stringResource(R.string.lib_sort_albums),
            SortAlbums.entries.map { it to stringResource(it.words) },
            order,
            onSelect = { sort -> change { it.copy(library = it.library.copy(albumSort = sort)) } },
            onDismiss = { sorting = false },
        )
    }
}

@get:StringRes
private val SortAlbums.words: Int
    get() = when (this) {
        SortAlbums.TITLE -> R.string.lib_sort_title
        SortAlbums.ARTIST -> R.string.lib_sort_artist
        SortAlbums.YEAR -> R.string.lib_sort_year
    }

/** Artists: each with a round image, how many albums and songs; by name or the most songs first. */
@Composable
private fun ArtistsTab(ui: LibraryUi, library: Library, padding: PaddingValues, reselect: Int) {
    val nav = LocalNavigator.current
    val playback = rememberPlayback()
    val change = rememberSettingsChange()
    val order = ui.settings.library.artistSort
    val artists = remember(library, order) { sortArtists(library.artists, order) }
    val songs = remember(artists) { artists.flatMap { it.songs }.distinctBy { it.path } }
    val source = QueueSource(SourceKind.SONGS, "tab:artists", stringResource(R.string.library_tab_artists))
    val state = rememberLazyListState()
    var sorting by rememberSaveable { mutableStateOf(false) }
    ReselectToTop(reselect) { state.animateScrollToItem(0) }
    val reduced = LocalReducedMotion.current
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) {
                ListHeader(
                    artists.size,
                    R.plurals.lib_noun_artists,
                    onShuffle = { playback.shuffle(songs.map { it.ref }, source) },
                    onPlay = { playback.play(songs.map { it.ref }, 0, source) },
                    songs = songs.size,
                    sort = stringResource(if (order == SortArtists.NAME) R.string.lib_sort_name else R.string.lib_sort_most_songs),
                    onSort = { sorting = true },
                )
            }
        }
        item(key = "same", contentType = "same") { SameNamesLine(NameKind.ARTIST, library, rowMotion(reduced).padding(horizontal = ScreenPadding)) }
        // Prefixed: an artist named "header" or "same" would take a fixed row's key, and two rows with one key crash the list.
        items(artists, key = { "artist ${it.name}" }, contentType = { "row" }) { artist ->
            NavRow(artist.name, artistLine(artist), rowMotion(reduced), lead = { ArtistAvatar(artist) }) { nav.open(Page.Artist(artist.name)) }
        }
    }
    if (sorting) {
        SortSheet(
            stringResource(R.string.lib_sort_artists),
            listOf(SortArtists.NAME to stringResource(R.string.lib_sort_name), SortArtists.MOST_SONGS to stringResource(R.string.lib_sort_most_songs)),
            order,
            onSelect = { sort -> change { it.copy(library = it.library.copy(artistSort = sort)) } },
            onDismiss = { sorting = false },
        )
    }
}

/** "2 albums · 14 songs". */
@Composable
internal fun artistLine(artist: Artist): String = stringResource(
    R.string.lib_after,
    pluralStringResource(R.plurals.lib_albums, artist.albums.size, artist.albums.size),
    pluralStringResource(R.plurals.lib_songs, artist.songs.size, artist.songs.size),
)

/** Folders: the top folders that hold music, a chain of folders with one subfolder each as one row. */
@Composable
private fun FoldersTab(library: Library, padding: PaddingValues, reselect: Int) {
    val playback = rememberPlayback()
    val rows = remember(library) { library.root.rows }
    val songs = library.root.allSongs
    val source = QueueSource(SourceKind.FOLDER, "", stringResource(R.string.library_tab_folders))
    val state = rememberLazyListState()
    ReselectToTop(reselect) { state.animateScrollToItem(0) }
    val reduced = LocalReducedMotion.current
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) { ListHeader(rows.size, R.plurals.lib_noun_folders, { playback.shuffle(songs.map { it.ref }, source) }, { playback.play(songs.map { it.ref }, 0, source) }, songs = songs.size) }
        }
        folderRows(rows, reduced)
    }
}

/** A folder list's rows: "Music › Rips" for a collapsed chain, with how many songs it holds below it. */
internal fun LazyListScope.folderRows(rows: List<FolderRow>, reduced: Boolean) {
    items(rows, key = { "folder ${it.folder.path}" }, contentType = { "row" }) { row ->
        val nav = LocalNavigator.current
        val count = row.folder.allSongs.size
        NavRow(
            row.names.joinToString(" › "),
            pluralStringResource(R.plurals.lib_songs, count, count),
            rowMotion(reduced),
            lead = { Tile(R.drawable.ic_folder_fill) },
        ) { nav.open(Page.Folder(row.folder.path)) }
    }
}

/**
 * Playlists, in Library's Playlists tab and on the Playlists tab's root: the pinned Queues row ("3 saved"), Built for
 * you (Favourites, Most played, Recently played, Recently added), then the user's playlists by name and New playlist.
 */
@Composable
private fun PlaylistsTab(ui: LibraryUi, library: Library, padding: PaddingValues, reselect: Int, sheets: SongSheets) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val playback = rememberPlayback()
    val brand = LocalBrandColors.current
    val playlists = remember(ui.playlists.playlists) { ui.playlists.playlists.sortedWith(compareBy(Collator.getInstance()) { it.name }) }
    val favourites = remember(library, ui.playlists) { library.listed(ui.playlists.favouriteSongs) }
    val songs = remember(library, ui.playlists) { (favourites + playlists.flatMap { library.listed(it.songs) }).distinctBy { it.path } }
    // Counted again on each play logged.
    val written by remember(context) { StatsGraph.counter(context).written }.collectAsState()
    val most by produceState(0, library, written) { value = mostPlayed(Lyrify.plays(context), library).size }
    val played by produceState(0, library, written) { value = recentlyPlayed(Lyrify.plays(context), library).size }
    val added = remember(library) { recentlyAdded(library, System.currentTimeMillis()).size }
    val saved = ui.queues.count { it.saved }
    val source = QueueSource(SourceKind.SONGS, "tab:playlists", stringResource(R.string.library_tab_playlists))
    val state = rememberLazyListState()
    ReselectToTop(reselect) { state.animateScrollToItem(0) }
    val reduced = LocalReducedMotion.current
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) { ListHeader(playlists.size + BuiltInList.entries.size, R.plurals.lib_noun_playlists, { playback.shuffle(songs.map { it.ref }, source) }, { playback.play(songs.map { it.ref }, 0, source) }, songs = songs.size) }
        }
        item(key = "queues", contentType = "row") {
            NavRow(stringResource(R.string.lib_queues), pluralStringResource(R.plurals.lib_saved, saved, saved), rowMotion(reduced), lead = { Tile(R.drawable.ic_queue_music, brand.highlightContainer, brand.onHighlightContainer) }) {
                nav.open(Page.Queues)
            }
        }
        item(key = "built heading", contentType = "heading") { Box(rowMotion(reduced)) { ListHeading(stringResource(R.string.lib_built_for_you)) } }
        item(key = "favourites", contentType = "built in") { Box(rowMotion(reduced)) { BuiltInRow(BuiltInList.FAVOURITES, favourites.size) } }
        item(key = "most", contentType = "built in") { Box(rowMotion(reduced)) { BuiltInRow(BuiltInList.MOST_PLAYED, most) } }
        item(key = "played", contentType = "built in") { Box(rowMotion(reduced)) { BuiltInRow(BuiltInList.RECENTLY_PLAYED, played) } }
        item(key = "added", contentType = "built in") { Box(rowMotion(reduced)) { BuiltInRow(BuiltInList.RECENTLY_ADDED, added) } }
        item(key = "yours heading", contentType = "heading") { Box(rowMotion(reduced)) { ListHeading(stringResource(R.string.lib_your_playlists)) } }
        items(playlists, key = { "playlist ${it.id}" }, contentType = { "row" }) { playlist ->
            val count = library.listed(playlist.songs).size
            NavRow(playlist.name, pluralStringResource(R.plurals.lib_songs, count, count), rowMotion(reduced), lead = { PlaylistTile(playlist) }) { nav.open(Page.Playlist(playlist.id)) }
        }
        item(key = "new", contentType = "new") { Box(rowMotion(reduced)) { NewRow(stringResource(R.string.lib_new_playlist)) { sheets.show(OpenSheet.NewPlaylist(emptyList())) } } }
    }
}

/** A Built for you row: its tile, its name and how many songs it holds. */
@Composable
private fun BuiltInRow(list: BuiltInList, count: Int) {
    val nav = LocalNavigator.current
    NavRow(stringResource(list.words), pluralStringResource(R.plurals.lib_songs, count, count), lead = { BuiltInTile(list) }) { nav.open(Page.BuiltIn(list)) }
}

/** A Built for you list's tile: the heart on the brand's container for Favourites, an icon for the others. */
@Composable
internal fun BuiltInTile(list: BuiltInList, size: Dp = 48.dp) {
    val brand = LocalBrandColors.current
    when (list) {
        BuiltInList.FAVOURITES -> Tile(R.drawable.ic_favorite_fill, brand.highlightContainer, brand.highlight, size)
        BuiltInList.MOST_PLAYED -> Tile(R.drawable.ic_bar_chart, size = size)
        BuiltInList.RECENTLY_PLAYED -> Tile(R.drawable.ic_history, size = size)
        BuiltInList.RECENTLY_ADDED -> Tile(R.drawable.ic_schedule, size = size)
    }
}

@get:StringRes
internal val BuiltInList.words: Int
    get() = when (this) {
        BuiltInList.FAVOURITES -> R.string.lib_favourites
        BuiltInList.MOST_PLAYED -> R.string.lib_most_played
        BuiltInList.RECENTLY_PLAYED -> R.string.lib_recently_played
        BuiltInList.RECENTLY_ADDED -> R.string.lib_recently_added
    }

/** Genres (off by default): each with how many songs it has. */
@Composable
private fun GenresTab(library: Library, padding: PaddingValues, reselect: Int) {
    val nav = LocalNavigator.current
    val playback = rememberPlayback()
    val songs = remember(library) { library.genres.flatMap { it.songs } }
    val source = QueueSource(SourceKind.SONGS, "tab:genres", stringResource(R.string.library_tab_genres))
    val state = rememberLazyListState()
    ReselectToTop(reselect) { state.animateScrollToItem(0) }
    val reduced = LocalReducedMotion.current
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) { ListHeader(library.genres.size, R.plurals.lib_noun_genres, { playback.shuffle(songs.map { it.ref }, source) }, { playback.play(songs.map { it.ref }, 0, source) }, songs = songs.size) }
        }
        items(library.genres, key = { "genre ${it.name}" }, contentType = { "row" }) { genre ->
            NavRow(genre.name, pluralStringResource(R.plurals.lib_songs, genre.songs.size, genre.songs.size), rowMotion(reduced), lead = { Tile(R.drawable.ic_category) }) { nav.open(Page.Genre(genre.name)) }
        }
    }
}

/** Library > Songs with a lyrics filter, from the AI's results and Check these: the library's songs with that status, by title. */
@Composable
fun SongsPage(page: Page.Songs) {
    val nav = LocalNavigator.current
    val ui = rememberLibraryUi() ?: return
    val library = ui.library
    val title = stringResource(page.status?.style?.label ?: page.group?.label ?: R.string.songs_title)
    val songs = remember(library, ui.ledger, page, ui.settings.library) {
        val matching = library?.songs.orEmpty().filter { song ->
            val status = ui.ledger.entries[song.path]?.status
            (page.group == null || status?.group == page.group) && (page.status == null || status == page.status)
        }
        sortSongs(matching, SortSongs.TITLE, ui.settings.library)
    }
    val source = QueueSource(SourceKind.SONGS, "status:${page.group}:${page.status}", title)
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, source, topBar = { under, backdrop -> BackTopBar(title, under, backdrop) { nav.back() } }, loaded = library != null) { list ->
        item(key = "header", contentType = "header") { Box(rowMotion(reduced)) { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) } }
        if (library != null && songs.isEmpty()) item(key = "empty", contentType = "empty") { EmptyState(EmptyArt.Icon(R.drawable.ic_search_off), stringResource(R.string.songs_no_match), rowMotion(reduced)) }
        songRows(list)
    }
}

/** A top bar with Back and [title]. */
@Composable
internal fun BackTopBar(title: String, under: Boolean, backdrop: GlassBackdrop, actions: @Composable RowScope.() -> Unit = {}, onBack: () -> Unit) {
    LyrifyTopBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        under = under,
        backdrop = backdrop,
        navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
        actions = actions,
    )
}

/**
 * Settings > Folders to skip: every folder of the Folders tree for every song on the phone (skipped ones too, so they
 * can come back), and any other folder skipped, each with a switch ([skipChoices]). Songs in a skipped folder or below
 * it stay on the phone but leave the library.
 */
@Composable
fun FoldersToSkipPage() {
    val nav = LocalNavigator.current
    val ui = rememberLibraryUi() ?: return
    val change = rememberSettingsChange()
    val skipped = ui.settings.library.foldersToSkip
    val folders = remember(ui.library, skipped) { skipChoices(ui.library?.all.orEmpty(), skipped) }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.set_skip), under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "sentence", contentType = "sentence") {
                Text(
                    stringResource(R.string.lib_skip_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp),
                )
            }
            item(key = "folders", contentType = "group") {
                GroupedRows(rowMotion(reduced)) {
                    folders.forEachIndexed { at, (folder, count) ->
                        val on = skipped.any { it.trim('/').equals(folder, ignoreCase = true) }
                        GroupedRow(
                            if (on) R.drawable.ic_folder_off else R.drawable.ic_folder_open,
                            folder,
                            tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                            first = at == 0,
                            supporting = { Text(pluralStringResource(R.plurals.lib_songs, count, count)) },
                            modifier = Modifier.toggleable(on, role = Role.Switch) { skip ->
                                change { current ->
                                    val others = current.library.foldersToSkip.filterNot { it.trim('/').equals(folder, ignoreCase = true) }
                                    current.copy(library = current.library.copy(foldersToSkip = if (skip) others + folder else others))
                                }
                            },
                            trailing = { LyrifySwitch(on) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The folders "Folders to skip" offers, each with how many of [all] songs are in it or below: every folder of the
 * Folders tree, by path, skipped ones too (so they can come back), and any other folder [skipped].
 */
internal fun skipChoices(all: List<Song>, skipped: List<String>): List<Pair<String, Int>> {
    // A song counts in its folder and in each folder above it.
    val counts = all.flatMap { song ->
        val names = song.folder.split('/').filter(String::isNotEmpty)
        names.indices.map { last -> names.subList(0, last + 1).joinToString("/") }
    }.groupingBy { it }.eachCount()
    return (counts.keys + skipped.map { it.trim('/') }).distinctBy { it.lowercase() }.sortedWith(compareBy(Collator.getInstance()) { it }).map { it to (counts[it] ?: 0) }
}

/** 2.0's song list over the ledger, with its status bar and chips, for Library and Search until L1 and L2. */
@Composable
internal fun LedgerSongs(query: SongQuery, onQuery: (SongQuery) -> Unit, reselect: Int, modifier: Modifier) {
    val nav = LocalNavigator.current
    val lyrics = LocalLyrics.current
    Box(modifier.fillMaxSize().imePadding()) {
        SongsScreen(
            ledger = lyrics.ledger,
            query = query,
            onQuery = onQuery,
            timing = lyrics.timing,
            removal = lyrics.removal,
            retrying = lyrics.retrying,
            connected = lyrics.connected,
            reselect = reselect,
            onRetryFailed = lyrics.onRetryFailed,
            onPickLyrics = { nav.open(Page.Check(it)) },
            banner = { LyricsBanners(lyrics, Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 8.dp), listener = false) },
        )
    }
}

// The lists, as pure functions

/** The row pinned at the top of Songs: how many favourite songs ("120 songs", or "None yet"), and the page it opens. */
data class FavouritesRow(val line: ToolLine, val page: Page)

fun favouritesRow(songs: Int): FavouritesRow = FavouritesRow(
    if (songs > 0) ToolLine.Count(R.plurals.lib_songs, songs) else ToolLine.Text(R.string.lib_none_yet),
    Page.BuiltIn(BuiltInList.FAVOURITES),
)

/** Whether a song has lyrics, as its row and the Songs filter see it: synced, plain, or none. */
enum class LyricsMark { SYNCED, PLAIN, NONE }

/** The mark for a song whose ledger status is [status] (null: KJB has not looked it up). */
fun lyricsMark(status: LyricsStatus?): LyricsMark = when (status) {
    LyricsStatus.SYNCED, LyricsStatus.EXISTING, LyricsStatus.AI_ALIGNED, LyricsStatus.AI_TRANSCRIBED -> LyricsMark.SYNCED
    LyricsStatus.PLAIN_ONLY, LyricsStatus.EXISTING_PLAIN -> LyricsMark.PLAIN
    else -> LyricsMark.NONE
}

/**
 * What a song's row marks besides its title: the lyrics icon after the artist ([lyrics]: synced or plain, none without
 * lyrics), and the green dot before it for the song [playing] now, which only one row of a list can have.
 */
@Immutable
data class RowMarks(val lyrics: LyricsMark, val playing: Boolean)

/** The marks of the row of the song at [path], whose ledger status is [status], while the song at [playingPath] plays. */
fun rowMarks(status: LyricsStatus?, path: String, playingPath: String?): RowMarks = RowMarks(lyricsMark(status), playing = path == playingPath)

/** Whether a song with [mark] shows under the Songs tab's "Show" filter. */
fun LyricsFilter.shows(mark: LyricsMark): Boolean = when (this) {
    LyricsFilter.ALL -> true
    LyricsFilter.SYNCED -> mark == LyricsMark.SYNCED
    LyricsFilter.PLAIN -> mark == LyricsMark.PLAIN
    LyricsFilter.NONE -> mark == LyricsMark.NONE
}

/** The Songs tab: [songs] that pass the filter, in the sort ([LibrarySettings.songFilter], [LibrarySettings.songSort]). */
fun songsTab(songs: List<Song>, statuses: (Song) -> LyricsStatus?, settings: LibrarySettings): List<Song> =
    sortSongs(songs.filter { settings.songFilter.shows(lyricsMark(statuses(it))) }, settings.songSort, settings)

/** How many of [songs] each "Show" filter shows, for the sort and filter sheet. */
fun filterCounts(songs: List<Song>, statuses: (Song) -> LyricsStatus?): Map<LyricsFilter, Int> {
    val marks = songs.groupingBy { lyricsMark(statuses(it)) }.eachCount()
    return LyricsFilter.entries.associateWith { filter -> marks.filterKeys(filter::shows).values.sum() }
}

/** When a song was added, as the Songs tab groups it in "Recently added". */
enum class AddedWhen { TODAY, THIS_WEEK, THIS_MONTH, EARLIER }

/** A heading over the Songs tab's rows: the first letter by title, the artist, the album, or when it was added. */
sealed interface ListSection {
    /** A letter, or "#" for digits and nothing ([nameLetter]). */
    data class Letter(val letter: String) : ListSection

    /** An artist or album name; null for songs without one. */
    data class Name(val name: String?) : ListSection

    data class Added(val time: AddedWhen) : ListSection
}

/** [song]'s heading under [sort], with "Recently added" by [now]'s day in [zone]. */
fun listSection(song: Song, sort: SortSongs, settings: LibrarySettings, now: Long, zone: ZoneId): ListSection = when (sort) {
    SortSongs.TITLE -> ListSection.Letter(nameLetter(displayTitle(song, settings)))
    SortSongs.ARTIST -> ListSection.Name(song.artist?.trim()?.takeIf(String::isNotEmpty))
    SortSongs.ALBUM -> ListSection.Name(song.album?.trim()?.takeIf(String::isNotEmpty))
    SortSongs.RECENTLY_ADDED -> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = song.addedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ListSection.Added(
            when {
                day == null -> AddedWhen.EARLIER
                !day.isBefore(today) -> AddedWhen.TODAY
                !day.isBefore(today.minusDays(WEEK_DAYS - 1)) -> AddedWhen.THIS_WEEK
                !day.isBefore(today.minusDays(RECENTLY_ADDED_DAYS - 1)) -> AddedWhen.THIS_MONTH
                else -> AddedWhen.EARLIER
            },
        )
    }
}

/** "This week": today and the six days before. */
private const val WEEK_DAYS = 7L
