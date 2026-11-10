package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Folder
import app.lyrify.library.Library
import app.lyrify.library.PLAYED_LIST_SIZE
import app.lyrify.library.Song
import app.lyrify.library.recentlyAdded
import app.lyrify.library.sortSongs
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.SortSongs
import app.lyrify.stats.SongPlays
import app.lyrify.stats.StatsGraph
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// Collection pages: an album, artist, folder, genre or playlist, Built for you, Unknown album, and Favourites.

/**
 * Menu rows other milestones fill (plan section 4), each with a one-line change here; until then null, and the menu
 * does not show.
 */
internal object CollectionSlots {
    /** T3: "Rename artist" and "Merge with…" in an artist page's "⋯" (its own menu rows); [close] closes the menu. */
    val artistMenu: (@Composable (name: String, close: () -> Unit) -> Unit)? = null
}

/** An album, artist, folder, genre or playlist page, Built for you, Unknown album, or a Favourites section's View all. */
@Composable
fun CollectionPage(page: Page.CollectionPages) {
    val ui = rememberLibraryUi() ?: return
    val library = ui.library ?: return MissingPage(ui)
    when (page) {
        is Page.Album -> AlbumPage(ui, library, page.key)
        is Page.Artist -> ArtistPage(ui, library, page.name)
        is Page.Folder -> FolderPage(ui, library, page.path)
        is Page.Genre -> GenrePage(ui, library, page.name)
        is Page.Playlist -> PlaylistPage(ui, library, page.id)
        is Page.BuiltIn -> if (page.list == BuiltInList.FAVOURITES) FavouritesPage(ui, library) else BuiltInPage(ui, library, page.list)
        Page.UnknownAlbum -> UnknownAlbumPage(ui, library)
        is Page.FavouritesAll -> FavouritesAllPage(ui, library, page.section)
    }
}

/** A page whose collection is gone from the phone (moved, deleted), or not read yet. */
@Composable
private fun MissingPage(ui: LibraryUi) {
    val nav = LocalNavigator.current
    PageFrame(topBar = { under, backdrop -> BackTopBar("", under, backdrop) { nav.back() } }) { padding ->
        if (ui.library != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(EmptyArt.Icon(R.drawable.ic_search_off), stringResource(R.string.lib_gone_title), text = stringResource(R.string.lib_gone))
            }
        }
    }
}

/**
 * A collection's top bar: Back with the [heart] beside it (favouriting the collection itself, apart from its songs),
 * the title once the page scrolls under the bar (or always, [alwaysTitle], for pages without a hero), and [actions].
 */
@Composable
private fun CollectionTopBar(
    title: String,
    under: Boolean,
    backdrop: GlassBackdrop,
    ui: LibraryUi,
    heart: FavouriteCollection? = null,
    alwaysTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalNavigator.current
    LyrifyTopBar(
        title = { if (under || alwaysTitle) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        under = under,
        backdrop = backdrop,
        navigation = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() })
                if (heart != null) HeartButton(heart, ui)
            }
        },
        actions = actions,
    )
}

/** The collection's heart: a switch for TalkBack ("Favourite this album"); Added or Removed with Undo. */
@Composable
private fun HeartButton(collection: FavouriteCollection, ui: LibraryUi) {
    val chrome = LocalChromeColors.current
    val brand = LocalBrandColors.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    val on = collection in ui.playlists.favouriteCollections
    val name = stringResource(
        when (collection.kind) {
            CollectionKind.ALBUM -> R.string.lib_favourite_album
            CollectionKind.ARTIST -> R.string.lib_favourite_artist
            CollectionKind.FOLDER -> R.string.lib_favourite_folder
            CollectionKind.PLAYLIST -> R.string.lib_favourite_playlist
        },
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .toggleable(on, role = Role.Switch) { scope.launch { toasts.say(resources, actions.favourite(collection, it)) } }
            .semantics { contentDescription = name }
            .minimumInteractiveComponentSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).background(chrome.glassSolid, CircleShape).border(1.dp, chrome.glassEdge, CircleShape),
        ) {
            Icon(
                painterResource(if (on) R.drawable.ic_favorite_fill else R.drawable.ic_favorite),
                contentDescription = null,
                tint = if (on) brand.highlight else LocalContentColor.current,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The head of a collection page: its art, its title, maybe a link (the album's artist), and a line of facts. */
@Composable
private fun Hero(title: String, meta: String, art: @Composable () -> Unit, link: (@Composable () -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        Box(Modifier.padding(bottom = 8.dp)) { art() }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        link?.invoke()
        Text(meta, style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A big cover for a hero: the song's art, or the glyph. */
@Composable
private fun BigCover(song: Song?) {
    SongCover(song?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = HERO, shape = RoundedCornerShape(28.dp))
}

/** "12 songs · 48 min", after the [lead] facts. */
@Composable
private fun songsMeta(songs: List<Song>, vararg lead: String?): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(songs.sumOf { it.durationMs.coerceAtLeast(0) }).toInt()
    return (lead.filterNotNull() + pluralStringResource(R.plurals.lib_songs, songs.size, songs.size) + pluralStringResource(R.plurals.lib_minutes, minutes, minutes))
        .joinToString(" · ")
}

/** A page's own order (Track, Your order, Newest) or by title; [byTitle] is the page's choice, kept across a turn. */
@Composable
private fun OrderSheet(@StringRes own: Int, byTitle: Boolean, onChoice: (Boolean) -> Unit, onDismiss: () -> Unit) {
    SortSheet(stringResource(R.string.lib_sort), listOf(false to stringResource(own), true to stringResource(R.string.lib_sort_title)), byTitle, onChoice, onDismiss)
}

/** An album: its cover, title, the artist as a link, year, count and length; then its tracks in disc and track order. */
@Composable
private fun AlbumPage(ui: LibraryUi, library: Library, key: String) {
    val nav = LocalNavigator.current
    val album = library.album(key) ?: return MissingPage(ui)
    var byTitle by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val songs = remember(album, byTitle, ui.settings.library) { if (byTitle) sortSongs(album.songs, SortSongs.TITLE, ui.settings.library) else album.songs }
    val source = QueueSource(SourceKind.ALBUM, key, album.title)
    SongListFrame(ui, songs, source, topBar = { under, backdrop ->
        CollectionTopBar(album.title, under, backdrop, ui, heart = FavouriteCollection(CollectionKind.ALBUM, key)) {
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_album), { menu = true })
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow(stringResource(R.string.lib_edit_album), R.drawable.ic_edit, onClick = {
                        menu = false
                        nav.show(FullScreen.EditAlbum(key))
                    })
                }
            }
        }
    }) { list ->
        item(key = "hero") {
            Hero(
                album.title,
                songsMeta(songs, album.year?.toString()),
                art = { BigCover(album.songs.firstOrNull()) },
                link = album.artist?.let { name -> { LinkText(name, library.artist(name)?.let { { nav.open(Page.Artist(name)) } }) } },
            )
        }
        item(key = "header") {
            ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = stringResource(if (byTitle) R.string.lib_sort_title else R.string.lib_sort_track), onSort = { sorting = true })
        }
        songRows(list)
    }
    if (sorting) OrderSheet(R.string.lib_sort_track, byTitle, { byTitle = it }) { sorting = false }
}

/** An artist: their image, name and counts, a row of their albums, then their songs. */
@Composable
private fun ArtistPage(ui: LibraryUi, library: Library, name: String) {
    val nav = LocalNavigator.current
    val artist = library.artist(name) ?: return MissingPage(ui)
    var byTitle by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val songs = remember(artist, byTitle, ui.settings.library) { if (byTitle) sortSongs(artist.songs, SortSongs.TITLE, ui.settings.library) else artist.songs }
    val source = QueueSource(SourceKind.ARTIST, name, name)
    SongListFrame(ui, songs, source, topBar = { under, backdrop ->
        CollectionTopBar(name, under, backdrop, ui, heart = FavouriteCollection(CollectionKind.ARTIST, name)) {
            CollectionSlots.artistMenu?.let { rows ->
                Box {
                    ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_artist), { menu = true })
                    LyrifyMenu(expanded = menu, onDismiss = { menu = false }) { rows(name) { menu = false } }
                }
            }
        }
    }) { list ->
        item(key = "hero") { Hero(name, artistLine(artist), art = { ArtistAvatar(artist, size = AVATAR) }) }
        if (artist.albums.isNotEmpty()) {
            item(key = "albums heading") { ListHeading(stringResource(R.string.library_tab_albums), artist.albums.size) }
            item(key = "albums") { AlbumsRow(artist.albums) }
        }
        item(key = "header") {
            ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = stringResource(if (byTitle) R.string.lib_sort_title else R.string.lib_sort_album), onSort = { sorting = true })
        }
        songRows(list)
    }
    if (sorting) OrderSheet(R.string.lib_sort_album, byTitle, { byTitle = it }) { sorting = false }
}

/** An artist's albums, the newest first, as a row of cards that scrolls sideways inside the page. */
@Composable
private fun AlbumsRow(albums: List<Album>) {
    val nav = LocalNavigator.current
    LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.key }) { album ->
            AlbumCard(album.title, album.year?.toString(), album.songs.firstOrNull(), Modifier.width(ALBUM_CARD)) { nav.open(Page.Album(album.key)) }
        }
    }
}

/** A folder: where it is, then its subfolders, then its own songs; Play plays it and its subfolders in path order. */
@Composable
private fun FolderPage(ui: LibraryUi, library: Library, path: String) {
    val folder = library.folder(path)?.takeIf { path.isNotEmpty() } ?: return MissingPage(ui)
    val songs = folder.allSongs
    val source = QueueSource(SourceKind.FOLDER, path, folder.name)
    SongListFrame(ui, songs, source, topBar = { under, backdrop ->
        CollectionTopBar(folder.name, under, backdrop, ui, heart = FavouriteCollection(CollectionKind.FOLDER, path), alwaysTitle = true)
    }) { list ->
        item(key = "path") {
            Text(
                path.split('/').joinToString(" › "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(horizontal = ScreenPadding + 4.dp),
            )
        }
        item(key = "header") { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) }
        folderRows(folder.rows)
        songRows(list, folder.songs)
    }
}

/** A genre (when the Genres tab is on): its songs. */
@Composable
private fun GenrePage(ui: LibraryUi, library: Library, name: String) {
    val genre = library.genre(name) ?: return MissingPage(ui)
    SongListFrame(ui, genre.songs, QueueSource(SourceKind.GENRE, name, name), topBar = { under, backdrop ->
        CollectionTopBar(name, under, backdrop, ui, alwaysTitle = true)
    }) { list ->
        item(key = "header") { ListHeader(genre.songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) }
        songRows(list)
    }
}

/** "Unknown album": the songs without an album tag. */
@Composable
private fun UnknownAlbumPage(ui: LibraryUi, library: Library) {
    val title = stringResource(R.string.lib_unknown_album)
    SongListFrame(ui, library.unknownAlbum, QueueSource(SourceKind.ALBUM, "", title), topBar = { under, backdrop ->
        CollectionTopBar(title, under, backdrop, ui, alwaysTitle = true)
    }) { list ->
        item(key = "header") { ListHeader(library.unknownAlbum.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) }
        songRows(list)
    }
}

/**
 * One of the user's playlists, its songs in the user's order (a song possibly twice); "⋯" has Rename and Delete, and the
 * song sheet "Remove from playlist". A deleted playlist's page says so, so its toast's Undo can bring it back here.
 */
@Composable
internal fun PlaylistPage(ui: LibraryUi, library: Library, id: String) {
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    var byTitle by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val m3u = rememberM3uActions()
    val playlist = ui.playlists.playlist(id) ?: return MissingPage(ui)
    // Each song with its place in the playlist, for "Remove from playlist"; songs gone or hidden are left out.
    val entries = remember(playlist, library, byTitle, ui.settings.library) {
        val listed = playlist.songs.mapIndexedNotNull { at, ref -> library.resolver.find(ref)?.takeIf(library::shows)?.let { at to it } }
        if (byTitle) listed.sortedWith(compareBy(java.text.Collator.getInstance()) { ui.title(it.second) }) else listed
    }
    val songs = entries.map { it.second }
    SongListFrame(ui, songs, QueueSource(SourceKind.PLAYLIST, id, playlist.name), topBar = { under, backdrop ->
        CollectionTopBar(playlist.name, under, backdrop, ui, heart = FavouriteCollection(CollectionKind.PLAYLIST, id)) {
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_playlist), { menu = true })
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow(stringResource(R.string.lib_rename), R.drawable.ic_edit, onClick = {
                        menu = false
                        renaming = true
                    })
                    PlaylistExportRow(m3u, playlist, close = { menu = false })
                    MenuRow(stringResource(R.string.lib_delete_playlist), R.drawable.ic_delete, destructive = true, onClick = {
                        menu = false
                        deleting = true
                    })
                }
            }
        }
    }) { list ->
        item(key = "hero") { Hero(playlist.name, songsMeta(songs), art = { PlaylistTile(playlist, HERO) }) }
        item(key = "header") {
            ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = stringResource(if (byTitle) R.string.lib_sort_title else R.string.lib_your_order), onSort = { sorting = true })
        }
        if (songs.isEmpty()) item(key = "empty") { EmptyState(EmptyArt.Glyph, stringResource(R.string.songs_empty_title), text = stringResource(R.string.lib_playlist_empty)) }
        songRows(list, place = { at -> SheetPlace.Playlist(id, entries[at].first) }, repeats = true)
    }
    if (sorting) OrderSheet(R.string.lib_your_order, byTitle, { byTitle = it }) { sorting = false }
    if (renaming) {
        NameDialog(stringResource(R.string.lib_rename_playlist), playlist.name, R.drawable.ic_edit, onDismiss = { renaming = false }, confirm = R.string.lib_rename) { name ->
            renaming = false
            scope.launch { toasts.say(resources, actions.rename(playlist, name)) }
        }
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.lib_delete_title),
            text = stringResource(R.string.lib_delete_text),
            confirm = stringResource(R.string.lib_delete),
            dismiss = stringResource(R.string.remove_cancel),
            onConfirm = {
                deleting = false
                scope.launch { toasts.say(resources, actions.delete(playlist)) }
            },
            onDismiss = { deleting = false },
        )
    }
}

/** Most played (with Your listening at its top), Recently played, or Recently added. */
@Composable
private fun BuiltInPage(ui: LibraryUi, library: Library, list: BuiltInList) {
    val context = LocalContext.current
    val title = stringResource(list.words)
    var byTitle by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    // Each song with its plays, for the note after the artist: "12 plays", or when it was played last. Read again on
    // each play logged, so the list follows it.
    val written by remember(context) { StatsGraph.counter(context).written }.collectAsState()
    val rows by produceState<List<Pair<Song, SongPlays?>>?>(null, library, list, written) {
        val plays = Lyrify.plays(context)
        fun found(played: List<SongPlays>) = played.mapNotNull { p -> library.resolver.find(p.song)?.takeIf(library::shows)?.let { it to p } }
        value = when (list) {
            BuiltInList.MOST_PLAYED -> found(plays.mostPlayed(PLAYED_LIST_SIZE))
            BuiltInList.RECENTLY_PLAYED -> found(plays.recentlyPlayed(PLAYED_LIST_SIZE))
            else -> recentlyAdded(library, System.currentTimeMillis()).map { it to null }
        }
    }
    val ordered = remember(rows, byTitle, ui.settings.library) { rows.orEmpty().let { all -> if (byTitle) all.sortedWith(compareBy(java.text.Collator.getInstance()) { ui.title(it.first) }) else all } }
    val songs = ordered.map { it.first }
    val plays = ordered.associate { it.first.path to it.second }
    val kind = when (list) {
        BuiltInList.MOST_PLAYED -> SourceKind.MOST_PLAYED
        BuiltInList.RECENTLY_PLAYED -> SourceKind.RECENTLY_PLAYED
        else -> SourceKind.RECENTLY_ADDED
    }
    val own = when (list) {
        BuiltInList.MOST_PLAYED -> R.string.lib_order_plays
        BuiltInList.RECENTLY_PLAYED -> R.string.lib_order_latest
        else -> R.string.lib_order_newest
    }
    val notes = plays.mapValues { (_, p) ->
        when {
            p == null -> null
            list == BuiltInList.MOST_PLAYED -> playsNote(p.count)
            else -> lastPlayedText(p.lastPlayedAt)
        }
    }
    SongListFrame(ui, songs, QueueSource(kind, list.name, ""), topBar = { under, backdrop -> CollectionTopBar(title, under, backdrop, ui) }, loaded = rows != null) { songList ->
        item(key = "hero") { Hero(title, songsMeta(songs), art = { BuiltInTile(list, HERO) }) }
        if (list == BuiltInList.MOST_PLAYED) {
            item(key = "listening") { ListeningCard(Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 4.dp)) }
        }
        item(key = "header") {
            ListHeader(songs.size, R.plurals.lib_noun_songs, songList::shuffle, { songList.play() }, onAddAll = songList::addAll, sort = stringResource(if (byTitle) R.string.lib_sort_title else own), onSort = { sorting = true })
        }
        if (rows != null && songs.isEmpty()) {
            item(key = "empty") {
                EmptyState(EmptyArt.Glyph, stringResource(R.string.songs_empty_title), text = stringResource(if (list == BuiltInList.RECENTLY_ADDED) R.string.lib_added_empty else R.string.lib_played_empty))
            }
        }
        songRows(songList, note = { notes[it.path] })
    }
    if (sorting) OrderSheet(own, byTitle, { byTitle = it }) { sorting = false }
}

/** Favourites: its songs (Shuffle and Play use them), then sections for songs, albums, artists, folders and playlists. */
@Composable
private fun FavouritesPage(ui: LibraryUi, library: Library) {
    val nav = LocalNavigator.current
    val title = stringResource(R.string.lib_favourites)
    val favourites = remember(library, ui.playlists) { Favourites.of(ui, library) }
    val songs = favourites.songs
    val others = favourites.albums.size + favourites.artists.size + favourites.folders.size + favourites.playlists.size
    SongListFrame(ui, songs, QueueSource(SourceKind.FAVOURITES, "favourites", ""), topBar = { under, backdrop -> CollectionTopBar(title, under, backdrop, ui) }) { list ->
        item(key = "hero") {
            Hero(title, songsMeta(songs, null).let { if (others > 0) stringResource(R.string.lib_after, it, pluralStringResource(R.plurals.lib_more_count, others, others)) else it }, art = { BuiltInTile(BuiltInList.FAVOURITES, HERO) })
        }
        item(key = "header") { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) }
        FavouriteSection.entries.forEach { section ->
            val count = favourites.count(section)
            val cap = if (section == FavouriteSection.SONGS) SONGS_SHOWN else ITEMS_SHOWN
            item(key = "section $section") {
                ListHeading(stringResource(section.words), count, onViewAll = if (count > cap) ({ nav.open(Page.FavouritesAll(section)) }) else null)
            }
            if (count == 0) {
                item(key = "hint $section") {
                    Text(
                        stringResource(section.hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                    )
                }
            } else if (section == FavouriteSection.SONGS) {
                songRows(list, songs.take(cap))
            } else {
                favouriteRows(section, favourites, library, cap)
            }
        }
    }
}

/** The "View all" of one Favourites section. */
@Composable
private fun FavouritesAllPage(ui: LibraryUi, library: Library, section: FavouriteSection) {
    val nav = LocalNavigator.current
    val favourites = remember(library, ui.playlists) { Favourites.of(ui, library) }
    val title = stringResource(section.words)
    if (section == FavouriteSection.SONGS) {
        SongListFrame(ui, favourites.songs, QueueSource(SourceKind.FAVOURITES, "favourites", ""), topBar = { under, backdrop -> BackTopBar(title, under, backdrop) { nav.back() } }) { list ->
            item(key = "header") { ListHeader(favourites.songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) }
            songRows(list)
        }
        return
    }
    PageFrame(topBar = { under, backdrop -> BackTopBar(title, under, backdrop) { nav.back() } }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            favouriteRows(section, favourites, library, Int.MAX_VALUE)
        }
    }
}

/** What is a favourite now: the songs and the collections, those gone from the phone or the library left out. */
private class Favourites(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<app.lyrify.library.Artist>,
    val folders: List<Folder>,
    val playlists: List<app.lyrify.library.Playlist>,
) {
    fun count(section: FavouriteSection): Int = when (section) {
        FavouriteSection.SONGS -> songs.size
        FavouriteSection.ALBUMS -> albums.size
        FavouriteSection.ARTISTS -> artists.size
        FavouriteSection.FOLDERS -> folders.size
        FavouriteSection.PLAYLISTS -> playlists.size
    }

    companion object {
        fun of(ui: LibraryUi, library: Library): Favourites {
            val collections = ui.playlists.favouriteCollections
            fun keys(kind: CollectionKind) = collections.filter { it.kind == kind }.map { it.key }
            return Favourites(
                songs = library.listed(ui.playlists.favouriteSongs),
                albums = keys(CollectionKind.ALBUM).mapNotNull(library::album),
                artists = keys(CollectionKind.ARTIST).mapNotNull(library::artist),
                folders = keys(CollectionKind.FOLDER).mapNotNull(library::folder),
                playlists = keys(CollectionKind.PLAYLIST).mapNotNull(ui.playlists::playlist),
            )
        }
    }
}

/** The first [cap] favourite albums, artists, folders or playlists, each a row that opens its page. */
private fun LazyListScope.favouriteRows(section: FavouriteSection, favourites: Favourites, library: Library, cap: Int) {
    when (section) {
        FavouriteSection.SONGS -> Unit
        FavouriteSection.ALBUMS -> items(favourites.albums.take(cap), key = { "album ${it.key}" }) { album ->
            val nav = LocalNavigator.current
            val sub = listOfNotNull(album.artist, pluralStringResource(R.plurals.lib_songs, album.songs.size, album.songs.size)).joinToString(" · ")
            NavRow(album.title, sub, lead = { SongCover(album.songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) }) }) { nav.open(Page.Album(album.key)) }
        }
        FavouriteSection.ARTISTS -> items(favourites.artists.take(cap), key = { "artist ${it.name}" }) { artist ->
            val nav = LocalNavigator.current
            NavRow(artist.name, artistLine(artist), lead = { ArtistAvatar(artist) }) { nav.open(Page.Artist(artist.name)) }
        }
        FavouriteSection.FOLDERS -> items(favourites.folders.take(cap), key = { "folder ${it.path}" }) { folder ->
            val nav = LocalNavigator.current
            val sub = stringResource(R.string.lib_after, pluralStringResource(R.plurals.lib_songs, folder.allSongs.size, folder.allSongs.size), folder.path)
            NavRow(folder.name, sub, lead = { Tile(R.drawable.ic_folder_fill) }) { nav.open(Page.Folder(folder.path)) }
        }
        FavouriteSection.PLAYLISTS -> items(favourites.playlists.take(cap), key = { "playlist ${it.id}" }) { playlist ->
            val nav = LocalNavigator.current
            val count = library.listed(playlist.songs).size
            NavRow(playlist.name, pluralStringResource(R.plurals.lib_songs, count, count), lead = { PlaylistTile(playlist) }) { nav.open(Page.Playlist(playlist.id)) }
        }
    }
}

@get:StringRes
private val FavouriteSection.words: Int
    get() = when (this) {
        FavouriteSection.SONGS -> R.string.library_tab_songs
        FavouriteSection.ALBUMS -> R.string.library_tab_albums
        FavouriteSection.ARTISTS -> R.string.library_tab_artists
        FavouriteSection.FOLDERS -> R.string.library_tab_folders
        FavouriteSection.PLAYLISTS -> R.string.library_tab_playlists
    }

/** What an empty section says: where its hearts are. */
@get:StringRes
private val FavouriteSection.hint: Int
    get() = when (this) {
        FavouriteSection.SONGS -> R.string.lib_hint_songs
        FavouriteSection.ALBUMS -> R.string.lib_hint_albums
        FavouriteSection.ARTISTS -> R.string.lib_hint_artists
        FavouriteSection.FOLDERS -> R.string.lib_hint_folders
        FavouriteSection.PLAYLISTS -> R.string.lib_hint_playlists
    }

private val HERO = 200.dp
private val AVATAR = 104.dp
private val ALBUM_CARD = 112.dp

/** Favourites shows the first 5 songs and the first 4 of each other section, then "View all". */
private const val SONGS_SHOWN = 5
private const val ITEMS_SHOWN = 4
