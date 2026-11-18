package app.lyrify.ui

import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Folder
import app.lyrify.library.Library
import app.lyrify.library.PLAYED_LIST_SIZE
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.recentlyAdded
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.FavouritesTab
import app.lyrify.settings.SongList
import app.lyrify.settings.SortSongs
import app.lyrify.stats.SongPlays
import app.lyrify.stats.StatsGraph
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// Collection pages: an album, artist, folder, genre or playlist, Built for you, Unknown album, and Favourites.

/** An album, artist, folder, genre or playlist page, Built for you (Favourites among them), or Unknown album. */
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

/** The collection's heart: TalkBack hears what a tap does ("Favourite this album", "Remove from Favourites"); Added or Removed with Undo. */
@Composable
private fun HeartButton(collection: FavouriteCollection, ui: LibraryUi) {
    val chrome = LocalChromeColors.current
    val brand = LocalBrandColors.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    val on = collection in ui.playlists.favouriteCollections
    val name = stringResource(heartLabel(collection.kind, on))
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clickable(role = Role.Button) { scope.launch { toasts.say(resources, actions.favourite(collection, !on)) } }
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

/**
 * The head of a collection page: its art (with the strong shadow), its title, maybe a link (the album's artist), and a
 * line of facts; behind it, [glow]'s cover blurred and faded into the page (the prototype's .abg), from under the top bar.
 */
@Composable
private fun Hero(title: String, meta: String, art: @Composable () -> Unit, link: (@Composable () -> Unit)? = null, glow: Any? = null) {
    val chrome = LocalChromeColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
    ) {
        if (glow != null) HeroGlow(glow)
        Box(Modifier.padding(bottom = 12.dp).shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = chrome.shadowStrong, spotColor = chrome.shadowStrong)) { art() }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        link?.invoke()
        Text(meta, style = HeroMeta.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The blurred cover behind a hero: 312 dp from the page's top, wider than the page so its blur has no edge; takes no space. */
@Composable
private fun HeroGlow(model: Any) {
    val surface = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val above = WindowInsets.statusBars.getTop(density) + with(density) { HERO_BAR.roundToPx() }
    Box(
        Modifier.layout { measurable, constraints ->
            val side = ScreenPadding.roundToPx()
            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + 2 * side, GLOW_HEIGHT.roundToPx()))
            layout(constraints.maxWidth, 0) { placeable.place(-side, -above) }
        },
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = GLOW_SCALE
                scaleY = GLOW_SCALE
            }.blur(44.dp).alpha(GLOW_ALPHA),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to surface.copy(alpha = 0.72f), 0.34f to surface.copy(alpha = 0.28f), 0.66f to surface.copy(alpha = 0.4f), 1f to surface)))
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

/** An album: its cover, title, the artist as a link, year, count and length; then its tracks in disc and track order. */
@Composable
private fun AlbumPage(ui: LibraryUi, library: Library, key: String) {
    val nav = LocalNavigator.current
    val album = library.album(key) ?: return MissingPage(ui)
    val (sort, onSort) = rememberListSort(ui, SongList.ALBUM)
    val byTitle = sort.key == SortSongs.TITLE
    var sorting by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    // The album artists to choose from when its album artist tag names several (Go to artist, ArtistNames.kt).
    var artists by remember { mutableStateOf<List<String>?>(null) }
    val sorted = rememberInOrder(album.songs, sort, ui.settings.library) { it }
    val songs = sorted.orEmpty()
    val source = QueueSource(SourceKind.ALBUM, key, album.title)
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, source, loaded = sorted != null, topBar = { under, backdrop ->
        CollectionTopBar(album.title, under, backdrop, ui, heart = FavouriteCollection(CollectionKind.ALBUM, key)) {
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_album), { menu = true })
                ArtistsMenu(artists, { artists = null }) { nav.open(Page.Artist(it)) }
            }
        }
    }, sheet = { list -> AlbumMenu(menu, album, library, list, byTitle, onChooseArtist = { artists = it }) { menu = false } }) { list ->
        item(key = "hero", contentType = "hero") {
            Box(rowMotion(reduced)) {
                Hero(
                    album.title,
                    songsMeta(songs, album.year?.toString()),
                    art = { BigCover(album.songs.firstOrNull()) },
                    link = album.artist?.let { name -> { ArtistLink(library.artistsIn(name).map { it.name }, { nav.open(Page.Artist(it)) }) { go -> LinkText(name, go) } } },
                    glow = album.songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) },
                )
            }
        }
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) {
                ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = sortChip(sort, R.string.lib_sort_track), onSort = { sorting = true })
            }
        }
        songRows(list)
    }
    if (sorting) ListSortSheet(SongList.ALBUM, sort, R.string.lib_sort_track, onSort) { sorting = false }
}

/** An artist: their image, name and counts, a row of their albums, then their songs. */
@Composable
private fun ArtistPage(ui: LibraryUi, library: Library, name: String) {
    val nav = LocalNavigator.current
    val artist = library.artist(name) ?: return MissingPage(ui)
    val (sort, onSort) = rememberListSort(ui, SongList.ARTIST)
    var sorting by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val sorted = rememberInOrder(artist.songs, sort, ui.settings.library) { it }
    val songs = sorted.orEmpty()
    val source = QueueSource(SourceKind.ARTIST, name, name)
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, source, loaded = sorted != null, topBar = { under, backdrop ->
        CollectionTopBar(name, under, backdrop, ui, heart = FavouriteCollection(CollectionKind.ARTIST, name)) {
            ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_artist), { menu = true })
        }
    }, sheet = { list -> ArtistMenu(menu, artist, list) { menu = false } }) { list ->
        item(key = "hero", contentType = "hero") {
            val face = artist.albums.firstOrNull()?.songs?.firstOrNull()
            Box(rowMotion(reduced)) { Hero(name, artistLine(artist), art = { ArtistAvatar(artist, size = AVATAR) }, glow = face?.let { SongArt(it.path, it.ref.mediaStoreId) }) }
        }
        if (artist.albums.isNotEmpty()) {
            item(key = "albums heading", contentType = "heading") { Box(rowMotion(reduced)) { ListHeading(stringResource(R.string.library_tab_albums), artist.albums.size) } }
            item(key = "albums", contentType = "albums") { Box(rowMotion(reduced)) { AlbumsRow(artist.albums) } }
        }
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) {
                ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = sortChip(sort, R.string.lib_sort_album_order), onSort = { sorting = true })
            }
        }
        songRows(list)
    }
    if (sorting) ListSortSheet(SongList.ARTIST, sort, R.string.lib_sort_album_order, onSort) { sorting = false }
    ArtistEditHost(library, name)
}

/**
 * An album's "⋯" while [open]: Play next and Add to for its songs in album order (whatever order the page shows),
 * Shuffle as the header's, Go to artist when the artist has a page (an album artist tag of several names asks which,
 * [onChooseArtist]), Covers & tags at this album's review, Edit album.
 */
@Composable
private fun AlbumMenu(open: Boolean, album: Album, library: Library, list: PageSongs, byTitle: Boolean, onChooseArtist: (List<String>) -> Unit, onClose: () -> Unit) {
    val nav = LocalNavigator.current
    val playSongsNext = rememberPlayNext()
    if (!open) return
    val artists = library.artistsIn(album.artist).map { it.name }
    val first = album.songs.firstOrNull()
    val review = SongSheetSlots.coversTags
    val actions = object : CollectionActions {
        override fun playNext() = playSongsNext(album.songs)

        override fun addTo() = list.addTo(album.songs)

        override fun shuffle() = list.shuffle()

        override fun goToArtist() {
            artistTap(artists, { nav.open(Page.Artist(it)) }, onChooseArtist)?.invoke()
        }

        // The review of this album, through its first song, with no song ticked: "Select all" is one tap.
        override fun coversTags() {
            first?.let { openCoversTagsForAlbum(it, nav) }
        }

        override fun editAlbum() = nav.show(FullScreen.EditAlbum(album.key, byTitle))

        // An artist's rows.
        override fun renameArtist() = Unit

        override fun mergeWith() = Unit
    }
    CollectionMenuSheet(
        title = album.title,
        line = songsMeta(album.songs, album.artist),
        art = { SongCover(first?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = MENU_ART, shape = MaterialTheme.shapes.medium) },
        rows = albumRows(artistPage = artists.isNotEmpty(), coversTags = review != null && first != null),
        onDismiss = onClose,
    ) { row ->
        onClose()
        actions.act(row)
    }
}

/** An artist's "⋯" while [open]: Play next and Add to for their songs by album, Shuffle as the header's, Rename and Merge. */
@Composable
private fun ArtistMenu(open: Boolean, artist: app.lyrify.library.Artist, list: PageSongs, onClose: () -> Unit) {
    val playSongsNext = rememberPlayNext()
    if (!open) return
    val actions = object : CollectionActions {
        override fun playNext() = playSongsNext(artist.songs)

        override fun addTo() = list.addTo(artist.songs)

        override fun shuffle() = list.shuffle()

        override fun renameArtist() = openRename(artist.name)

        override fun mergeWith() = openMerge(artist.name)

        // An album's rows.
        override fun goToArtist() = Unit

        override fun coversTags() = Unit

        override fun editAlbum() = Unit
    }
    CollectionMenuSheet(
        title = artist.name,
        line = artistLine(artist),
        art = { ArtistAvatar(artist, size = MENU_ART) },
        rows = artistRows(),
        onDismiss = onClose,
    ) { row ->
        onClose()
        actions.act(row)
    }
}

/** Play next for a "⋯" sheet's songs; its toast runs on the page's scope, so it outlives the sheet. */
@Composable
private fun rememberPlayNext(): (List<Song>) -> Unit {
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    return { songs -> scope.launch { toasts.say(resources, actions.playNext(songs.map { it.ref })) } }
}

/** An artist's albums, the newest first, as a row of cards that scrolls sideways inside the page. */
@Composable
private fun AlbumsRow(albums: List<Album>) {
    val nav = LocalNavigator.current
    val reduced = LocalReducedMotion.current
    LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.key }, contentType = { "album" }) { album ->
            AlbumCard(album.title, album.year?.toString(), album.songs.firstOrNull(), rowMotion(reduced).width(ALBUM_CARD)) { nav.open(Page.Album(album.key)) }
        }
    }
}

/** A folder: where it is, then its subfolders, then its own songs; Play plays it and its subfolders in path order. */
@Composable
private fun FolderPage(ui: LibraryUi, library: Library, path: String) {
    val nav = LocalNavigator.current
    val folder = library.folder(path)?.takeIf { path.isNotEmpty() } ?: return MissingPage(ui)
    val (sort, onSort) = rememberListSort(ui, SongList.FOLDER)
    var sorting by rememberSaveable { mutableStateOf(false) }
    // Its own songs in the order chosen, first; then its subfolders' in path order, as Play plays them.
    val sorted = rememberInOrder(folder.songs, sort, ui.settings.library) { it }
    val own = sorted.orEmpty()
    val songs = remember(folder, own) { own + folder.folders.flatMap { it.allSongs } }
    val source = QueueSource(SourceKind.FOLDER, path, folder.name)
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, source, loaded = sorted != null, topBar = { under, backdrop ->
        CollectionTopBar(folder.name, under, backdrop, ui, heart = FavouriteCollection(CollectionKind.FOLDER, path), alwaysTitle = true) {
            // Audiobooks: Mark as book and Not a book for the folder's own files (BooksScreen.kt), above Hide folder.
            FolderMenu(folder, onHidden = { nav.back() }) { close -> BookFolderRow(folder, library, close) }
        }
    }) { list ->
        item(key = "path", contentType = "path") {
            Text(
                path.split('/').joinToString(" › "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = rowMotion(reduced).padding(horizontal = ScreenPadding + 4.dp),
            )
        }
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = sortChip(sort, R.string.lib_sort_file_name), onSort = { sorting = true }) }
        }
        folderRows(folder.rows, reduced)
        songRows(list, own)
    }
    if (sorting) ListSortSheet(SongList.FOLDER, sort, R.string.lib_sort_file_name, onSort) { sorting = false }
}

/** A genre (when the Genres tab is on): its songs. */
@Composable
private fun GenrePage(ui: LibraryUi, library: Library, name: String) {
    val genre = library.genre(name) ?: return MissingPage(ui)
    val (sort, onSort) = rememberListSort(ui, SongList.GENRE)
    var sorting by rememberSaveable { mutableStateOf(false) }
    val sorted = rememberInOrder(genre.songs, sort, ui.settings.library) { it }
    val songs = sorted.orEmpty()
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, QueueSource(SourceKind.GENRE, name, name), loaded = sorted != null, topBar = { under, backdrop ->
        CollectionTopBar(name, under, backdrop, ui, alwaysTitle = true)
    }) { list ->
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = sortChip(sort, R.string.lib_sort_title), onSort = { sorting = true }) }
        }
        songRows(list)
    }
    if (sorting) ListSortSheet(SongList.GENRE, sort, R.string.lib_sort_title, onSort) { sorting = false }
}

/** "Unknown album": the songs without an album tag. */
@Composable
private fun UnknownAlbumPage(ui: LibraryUi, library: Library) {
    val title = stringResource(R.string.lib_unknown_album)
    val (sort, onSort) = rememberListSort(ui, SongList.UNKNOWN_ALBUM)
    var sorting by rememberSaveable { mutableStateOf(false) }
    val sorted = rememberInOrder(library.unknownAlbum, sort, ui.settings.library) { it }
    val songs = sorted.orEmpty()
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, QueueSource(SourceKind.ALBUM, "", title), loaded = sorted != null, topBar = { under, backdrop ->
        CollectionTopBar(title, under, backdrop, ui, alwaysTitle = true)
    }) { list ->
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = sortChip(sort, R.string.lib_sort_title), onSort = { sorting = true }) }
        }
        songRows(list)
    }
    if (sorting) ListSortSheet(SongList.UNKNOWN_ALBUM, sort, R.string.lib_sort_title, onSort) { sorting = false }
}

/**
 * One of the user's playlists, its songs in the user's order (a song possibly twice); "⋯" has Rename and Delete, and the
 * song sheet "Remove from playlist". Delete goes back to where the page was opened from, and its toast's Undo brings the
 * playlist back and opens it again. A playlist deleted elsewhere leaves its page saying so.
 */
@Composable
internal fun PlaylistPage(ui: LibraryUi, library: Library, id: String) {
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    val (order, onOrder) = rememberListSort(ui, SongList.PLAYLIST)
    var sorting by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val m3u = rememberM3uActions()
    val nav = LocalNavigator.current
    // The toast outlives the page Delete leaves: it runs in the activity's scope, not the page's.
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    // Deleted here: the page keeps drawing it while it leaves.
    var leaving by remember { mutableStateOf<Playlist?>(null) }
    val playlist = ui.playlists.playlist(id) ?: leaving ?: return MissingPage(ui)
    val ordered = remember { OrderedEntries() }
    val (shownSongs, entryKeys) = remember(playlist.songs, ordered.version) { ordered.of(playlist.songs) }
    // Each song with its place in the playlist, for "Remove from playlist" and moves; songs gone or hidden are left out.
    // The playlist's entries are [ordered]'s: a move not stored yet shows over them, and each has a key that stays with it.
    val listed = remember(shownSongs, library) { shownSongs.mapIndexedNotNull { at, ref -> library.resolver.find(ref)?.takeIf(library::shows)?.let { at to it } } }
    val sorted = rememberInOrder(listed, order, ui.settings.library) { it.second }
    val entries = sorted.orEmpty()
    val songs = remember(entries) { entries.map { it.second } }
    val reduced = LocalReducedMotion.current
    val rows = rememberLazyListState()
    val reorder = rememberSongReorder(rows, ordered, { entries.map { it.first } }) { shown -> actions.arrange(playlist, shown) }
    SongListFrame(ui, songs, QueueSource(SourceKind.PLAYLIST, id, playlist.name), state = rows, loaded = sorted != null, topBar = { under, backdrop ->
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
        item(key = "hero", contentType = "hero") {
            Box(rowMotion(reduced)) { Hero(playlist.name, songsMeta(songs), art = { PlaylistTile(playlist, HERO) }, glow = songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) }) }
        }
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) {
                ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = sortChip(order, R.string.lib_sort_custom), onSort = { sorting = true })
            }
        }
        if (sorted != null && songs.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                AboveChrome { EmptyState(EmptyArt.Glyph, stringResource(R.string.songs_empty_title), rowMotion(reduced), text = stringResource(R.string.lib_playlist_empty)) }
            }
        }
        songRows(list, place = { at -> SheetPlace.Playlist(id, entries[at].first) }, keys = entries.map { "entry ${entryKeys[it.first]}" }, reorder = reorder.takeIf { order.reorders })
    }
    if (sorting) ListSortSheet(SongList.PLAYLIST, order, R.string.lib_sort_custom, onOrder) { sorting = false }
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
                leaving = playlist
                lifecycle.launch {
                    val deleted = actions.delete(playlist) { nav.open(Page.Playlist(id)) }
                    if (deleted.said == Said.PlaylistDeleted) nav.back() else leaving = null
                    toasts.say(resources, deleted)
                }
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
    val (order, onOrder) = rememberListSort(ui, list.songList)
    var sorting by rememberSaveable { mutableStateOf(false) }
    // Most played and Recently played bring each song's plays; Recently added reads them when sorted by them.
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
    val sorted = rememberInOrder(rows.orEmpty(), order, ui.settings.library) { it.first }
    val ordered = sorted.orEmpty()
    val songs = ordered.map { it.first }
    val plays = ordered.associate { it.first.path to it.second }
    val kind = when (list) {
        BuiltInList.MOST_PLAYED -> SourceKind.MOST_PLAYED
        BuiltInList.RECENTLY_PLAYED -> SourceKind.RECENTLY_PLAYED
        else -> SourceKind.RECENTLY_ADDED
    }
    val notes = plays.mapValues { (_, p) ->
        when {
            p == null -> null
            list == BuiltInList.MOST_PLAYED -> playsNote(p.count)
            else -> lastPlayedText(p.lastPlayedAt)
        }
    }
    val reduced = LocalReducedMotion.current
    SongListFrame(ui, songs, QueueSource(kind, list.name, ""), topBar = { under, backdrop -> CollectionTopBar(title, under, backdrop, ui) }, loaded = rows != null && sorted != null) { songList ->
        item(key = "hero", contentType = "hero") { Box(rowMotion(reduced)) { Hero(title, songsMeta(songs), art = { BuiltInTile(list, HERO) }) } }
        if (list == BuiltInList.MOST_PLAYED) {
            item(key = "listening", contentType = "listening") { ListeningCard(rowMotion(reduced).padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 4.dp)) }
        }
        item(key = "header", contentType = "header") {
            Box(rowMotion(reduced)) {
                ListHeader(songs.size, R.plurals.lib_noun_songs, songList::shuffle, { songList.play() }, onAddAll = songList::addAll, sort = sortChip(order, R.string.lib_sort_title), onSort = { sorting = true })
            }
        }
        if (rows != null && sorted != null && songs.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyState(EmptyArt.Glyph, stringResource(R.string.songs_empty_title), rowMotion(reduced), text = stringResource(if (list == BuiltInList.RECENTLY_ADDED) R.string.lib_added_empty else R.string.lib_played_empty))
            }
        }
        songRows(songList, note = { notes[it.path] })
    }
    if (sorting) ListSortSheet(list.songList, order, R.string.lib_sort_title, onOrder) { sorting = false }
}

/**
 * Favourites: its songs (Shuffle and Play use them), then a top tab, as Library's are, for each kind with something in
 * it ("Songs 120"), each its whole list: Songs as song rows, Albums as the grid, Artists, Folders and Playlists as rows.
 * The tabs' pages follow the finger left and right and each keeps its place ([TabPages]); the compact head ([FavouritesHead])
 * scrolls away above them, and then the tabs stay under the top bar ([CollapsingTabs]). One kind alone shows without tabs, and
 * nothing at all the empty state. It reopens on the tab used last (kept in settings.json), or on the first with
 * something while that one is empty.
 */
@Composable
private fun FavouritesPage(ui: LibraryUi, library: Library) {
    val change = rememberSettingsChange()
    val title = stringResource(R.string.lib_favourites)
    val ordered = remember { OrderedEntries() }
    val (shownSongs, entryKeys) = remember(ui.playlists.favouriteSongs, ordered.version) { ordered.of(ui.playlists.favouriteSongs) }
    val favourites = remember(library, ui.playlists, shownSongs) { Favourites.of(ui.playlists.copy(favouriteSongs = shownSongs), library) }
    val (order, onOrder) = rememberListSort(ui, SongList.FAVOURITES)
    var sorting by rememberSaveable { mutableStateOf(false) }
    val placed = remember(favourites) { favourites.songPlaces.zip(favourites.songs) }
    val entries = rememberInOrder(placed, order, ui.settings.library) { it.second }.orEmpty()
    val songs = remember(entries) { entries.map { it.second } }
    val actions = rememberSongActions()
    val rows = rememberLazyListState()
    val reorder = rememberSongReorder(rows, ordered, { entries.map { it.first } }) { shown -> actions.arrangeFavourites(shown) }.takeIf { order.reorders }
    val others = FavouritesTab.entries.filter { it != FavouritesTab.SONGS }.sumOf { favourites.count(it) }
    var picked by rememberSaveable { mutableStateOf<FavouritesTab?>(null) }
    val shown = favourites.shown(picked ?: ui.settings.nav.favouritesTab)
    val reduced = LocalReducedMotion.current
    val tabs = favourites.tabs
    val header = rememberScrollState()
    // The hero gone under the top bar is content under it too.
    val heroUnder by remember(header) { derivedStateOf { header.value > 0 } }
    val pager = rememberTabPager(tabs, tabs.indexOf(shown)) { at ->
        tabs.getOrNull(at)?.takeIf { it != shown }?.let { chosen ->
            picked = chosen
            change { if (it.nav.favouritesTab == chosen) it else it.copy(nav = it.nav.copy(favouritesTab = chosen)) }
        }
    }
    val hero: @Composable (PageSongs) -> Unit = { list ->
        FavouritesHead(title, songsMeta(songs, null).let { if (others > 0) stringResource(R.string.lib_after, it, pluralStringResource(R.plurals.lib_more_count, others, others)) else it }) {
            ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll, sort = if (songs.isEmpty()) null else sortChip(order, R.string.lib_sort_custom), onSort = { sorting = true }, gutter = 0.dp)
        }
    }
    SongPageFrame(ui, songs, QueueSource(SourceKind.FAVOURITES, "favourites", ""), topBar = { under, backdrop -> CollectionTopBar(title, under || heroUnder, backdrop, ui) }) { list, padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)
        if (tabs.isEmpty()) {
            LazyColumn(Modifier.fillMaxSize(), state = rows, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
                item(key = "hero", contentType = "hero") { Column(rowMotion(reduced)) { hero(list) } }
                if (shown != null && shown != FavouritesTab.SONGS) {
                    item(key = "heading", contentType = "heading") { Box(rowMotion(reduced)) { ListHeading(stringResource(shown.words), favourites.count(shown)) } }
                }
                when (shown) {
                    null -> item(key = "empty", contentType = "empty") { EmptyState(EmptyArt.Icon(R.drawable.ic_favorite), stringResource(R.string.lib_fav_empty_title), rowMotion(reduced), text = stringResource(R.string.lib_fav_empty)) }
                    FavouritesTab.SONGS -> songRows(list, keys = entries.map { "entry ${entryKeys[it.first]}" }, reorder = reorder)
                    else -> favouriteRows(shown, favourites, library, reduced)
                }
            }
        } else {
            CollapsingTabs(
                outer = header,
                padding = padding,
                header = { hero(list) },
                tabs = { PagerTabs(pager, tabs.map { stringResource(R.string.lib_tab_count, stringResource(it.words), formatCount(favourites.count(it))) }) },
            ) {
                TabPages(pager, Modifier.fillMaxSize(), swipe = !list.selection.active) { tab ->
                    LazyColumn(Modifier.fillMaxSize(), state = if (tab == FavouritesTab.SONGS) rows else rememberLazyListState(), contentPadding = bottom) {
                        when (tab) {
                            FavouritesTab.SONGS -> songRows(list, keys = entries.map { "entry ${entryKeys[it.first]}" }, reorder = reorder)
                            else -> favouriteRows(tab, favourites, library, reduced)
                        }
                    }
                }
            }
        }
    }
    if (sorting) ListSortSheet(SongList.FAVOURITES, order, R.string.lib_sort_custom, onOrder) { sorting = false }
}

/** What is a favourite now: the songs (with [songPlaces], each one's place among those stored) and the collections, those gone from the phone or the library left out. */
/**
 * Favourites' head (user 2026-11-14: the 200 dp heart pushed everything down): the heart on a 56 dp tile beside the
 * title and its counts line, then [buttons], the list header (Shuffle, Play, Add all, the sort), under them; on the same
 * row where the width leaves it room ([favouritesSideBySide]). Large words wrap and the row grows with them, the tile
 * beside their middle ([favouritesHeadHeight]). TalkBack hears the title as the page's heading; the tile is decorative.
 */
@Composable
internal fun FavouritesHead(title: String, meta: String, buttons: @Composable () -> Unit) {
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BuiltInTile(BuiltInList.FAVOURITES, FAV_TILE)
                Column(Modifier.padding(start = FAV_HEAD_GAP)) {
                    // Shrunk to fit its lines where large words would not (phone session 9: it was cut at font scale 2).
                    val style = MaterialTheme.typography.titleLarge
                    Text(
                        title,
                        style = style,
                        maxLines = favouritesTitleLines(title),
                        autoSize = TextAutoSize.StepBased(minFontSize = FAV_TITLE_SMALLEST, maxFontSize = style.fontSize),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(meta, style = FavouritesMeta.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Box { buttons() }
        },
        modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = FAV_HEAD_TOP),
    ) { (head, row), constraints ->
        val width = constraints.maxWidth
        val gap = FAV_HEAD_GAP.roundToPx()
        val headPart = head.measure(Constraints(maxWidth = width))
        val side = favouritesSideBySide(width, headPart.width, gap, FAV_BUTTONS_BESIDE.roundToPx())
        val buttonsPart = row.measure(Constraints(maxWidth = if (side) width - headPart.width - gap else width))
        // The head's row is already as tall as its words where they outgrow the tile.
        val height = favouritesHeadHeight(FAV_TILE.toPx(), headPart.height.toFloat(), buttonsPart.height.toFloat(), side, top = 0f).roundToInt()
        layout(width, height) {
            if (side) {
                headPart.placeRelative(0, (height - headPart.height) / 2)
                buttonsPart.placeRelative(headPart.width + gap, (height - buttonsPart.height) / 2)
            } else {
                headPart.placeRelative(0, 0)
                buttonsPart.placeRelative(0, headPart.height)
            }
        }
    }
}

internal class Favourites(
    val songs: List<Song>,
    val songPlaces: List<Int>,
    val albums: List<Album>,
    val artists: List<app.lyrify.library.Artist>,
    val folders: List<Folder>,
    val playlists: List<app.lyrify.library.Playlist>,
) {
    /** How many favourites of [tab]'s kind. */
    fun count(tab: FavouritesTab): Int = when (tab) {
        FavouritesTab.SONGS -> songs.size
        FavouritesTab.ALBUMS -> albums.size
        FavouritesTab.ARTISTS -> artists.size
        FavouritesTab.FOLDERS -> folders.size
        FavouritesTab.PLAYLISTS -> playlists.size
    }

    /** The page's top tabs: the kinds with something in them, in order; none when fewer than two have (one shows alone). */
    val tabs: List<FavouritesTab> = FavouritesTab.entries.filter { count(it) > 0 }.takeIf { it.size > 1 }.orEmpty()

    /** The kind the page shows: [remembered] while it has something, else the first that has; null when nothing has. */
    fun shown(remembered: FavouritesTab): FavouritesTab? = remembered.takeIf { count(it) > 0 } ?: FavouritesTab.entries.firstOrNull { count(it) > 0 }

    companion object {
        /** The favourites [state] names, found in [library]. */
        fun of(state: PlaylistsState, library: Library): Favourites {
            fun keys(kind: CollectionKind) = state.favouriteCollections.filter { it.kind == kind }.map { it.key }
            val found = state.favouriteSongs.withIndex().mapNotNull { (at, ref) -> library.resolver.find(ref)?.takeIf(library::shows)?.let { at to it } }
            return Favourites(
                songs = found.map { it.second },
                songPlaces = found.map { it.first },
                albums = keys(CollectionKind.ALBUM).mapNotNull(library::album),
                artists = keys(CollectionKind.ARTIST).mapNotNull(library::artist),
                folders = keys(CollectionKind.FOLDER).mapNotNull(library::folder),
                playlists = keys(CollectionKind.PLAYLIST).mapNotNull(state::playlist),
            )
        }
    }
}

/**
 * One Favourites tab other than Songs, whole, each item opening its page: albums as Library's two-column grid, artists
 * with their round images, folders and playlists as rows.
 */
private fun LazyListScope.favouriteRows(tab: FavouritesTab, favourites: Favourites, library: Library, reduced: Boolean) {
    when (tab) {
        FavouritesTab.SONGS -> Unit
        FavouritesTab.ALBUMS -> items(favourites.albums.chunked(2), key = { "albums ${it.first().key}" }, contentType = { "albums" }) { pair ->
            val nav = LocalNavigator.current
            // Rows fade and move into place when the tab changes or a favourite comes or goes.
            Row(rowMotion(reduced).fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { album -> AlbumCard(album.title, album.artist, album.songs.firstOrNull(), Modifier.weight(1f)) { nav.open(Page.Album(album.key)) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        FavouritesTab.ARTISTS -> items(favourites.artists, key = { "artist ${it.name}" }, contentType = { "row" }) { artist ->
            val nav = LocalNavigator.current
            NavRow(artist.name, artistLine(artist), rowMotion(reduced), lead = { ArtistAvatar(artist) }) { nav.open(Page.Artist(artist.name)) }
        }
        FavouritesTab.FOLDERS -> items(favourites.folders, key = { "folder ${it.path}" }, contentType = { "row" }) { folder ->
            val nav = LocalNavigator.current
            val sub = stringResource(R.string.lib_after, pluralStringResource(R.plurals.lib_songs, folder.allSongs.size, folder.allSongs.size), folder.path)
            NavRow(folder.name, sub, rowMotion(reduced), lead = { Tile(R.drawable.ic_folder_fill) }) { nav.open(Page.Folder(folder.path)) }
        }
        FavouritesTab.PLAYLISTS -> items(favourites.playlists, key = { "playlist ${it.id}" }, contentType = { "row" }) { playlist ->
            val nav = LocalNavigator.current
            val count = library.listed(playlist.songs).size
            NavRow(playlist.name, pluralStringResource(R.plurals.lib_songs, count, count), rowMotion(reduced), lead = { PlaylistTile(playlist) }) { nav.open(Page.Playlist(playlist.id)) }
        }
    }
}

@get:StringRes
private val FavouritesTab.words: Int
    get() = when (this) {
        FavouritesTab.SONGS -> R.string.library_tab_songs
        FavouritesTab.ALBUMS -> R.string.library_tab_albums
        FavouritesTab.ARTISTS -> R.string.library_tab_artists
        FavouritesTab.FOLDERS -> R.string.library_tab_folders
        FavouritesTab.PLAYLISTS -> R.string.library_tab_playlists
    }

private val HERO = 200.dp

/** Favourites' compact head (user 2026-11-14): its heart tile, the space above it, and between it and its words. */
val FAV_TILE = 56.dp
val FAV_HEAD_TOP = 4.dp
val FAV_HEAD_GAP = 12.dp

/** The width left beside Favourites' head that the list header needs to sit on the same row: it fits 360 dp ([headerFit]). */
val FAV_BUTTONS_BESIDE = 360.dp

/** The smallest Favourites' title shrinks to, before the font scale. */
val FAV_TITLE_SMALLEST = 11.sp

/** The counts line under Favourites' title, as a hero's line of facts. */
internal val FavouritesMeta = body(13.5f, 19)

/**
 * Whether Favourites' list header sits on the same row as its head, [head] wide in a row [width] wide with [gap] between:
 * only where what is left holds [beside], so a phone held upright, or large words, put it under the head.
 */
fun favouritesSideBySide(width: Int, head: Int, gap: Int, beside: Int): Boolean = width - head - gap >= beside

/**
 * Favourites' head's height: [top], then the heart [tile] beside the title and counts ([words], however tall the font
 * scale makes them, so they are never cut under the tile), with the list header ([buttons]) under it or beside it ([side]).
 */
/**
 * How many lines Favourites' title may take: one for a single word (it shrinks to fit rather than break inside the word),
 * two for more, so large words wrap between them.
 */
fun favouritesTitleLines(title: String): Int = if (title.trim().contains(' ')) 2 else 1

fun favouritesHeadHeight(tile: Float, words: Float, buttons: Float, side: Boolean, top: Float): Float {
    val head = maxOf(tile, words)
    return top + if (side) maxOf(head, buttons) else head + buttons
}

/** The hero's glow (.abg): 312 dp tall, its blurred cover at 150% and 62%; the page's top bar it starts under. */
private val GLOW_HEIGHT = 312.dp
private const val GLOW_SCALE = 1.5f
private const val GLOW_ALPHA = 0.62f
private val HERO_BAR = 64.dp

/** A hero's line of facts (.hmeta): 13.5/19. */
private val HeroMeta = body(13.5f, 19)
private val AVATAR = 104.dp
private val ALBUM_CARD = 112.dp

/** The art at the head of an album's or an artist's "⋯", as the song sheet's. */
private val MENU_ART = 72.dp
