package app.lyrify.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import app.lyrify.library.SongRef
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.library.Artist
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.player.PlaybackCommands
import app.lyrify.player.QueueSource
import app.lyrify.player.QueueTarget
import app.lyrify.settings.Settings
import kotlinx.coroutines.launch
import java.io.Serializable

// What every Library page is built from: the state it reads, the list header, rows and tiles, and the song-list frame.

/** What every Library page reads: the library, the settings, playlists and Favourites, the queues, and the ledger's lyrics. */
@Stable
class LibraryUi(val state: LibraryState, val settings: Settings, val playlists: PlaylistsState, val queues: List<QueueTarget>, val ledger: LedgerState) {
    /** Null while the songs are read, or cannot be. */
    val library: Library? get() = (state as? LibraryState.Ready)?.library

    private val favourites: Set<String> by lazy { playlists.favouriteSongs.mapTo(HashSet()) { it.path } }

    fun status(song: Song): LyricsStatus? = ledger.entries[song.path]?.status

    /** What the row shows: the title, or the file name under "Show file names". */
    fun title(song: Song): String = displayTitle(song, settings.library)

    fun isFavourite(song: Song): Boolean = song.path in favourites
}

/** The [LibraryUi] of the app's stores; null until the settings are read. */
@Composable
fun rememberLibraryUi(): LibraryUi? {
    val context = LocalContext.current
    val state by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
    val settings by remember(context) { Lyrify.settings(context).settings }.collectAsStateWithLifecycle(null)
    val playlists by remember(context) { LibraryGraph.playlists(context).state }.collectAsStateWithLifecycle(PlaylistsState())
    val queues by remember(context) { Lyrify.playback(context).queues }.collectAsStateWithLifecycle()
    val ledger = LocalLyrics.current.ledger
    val read = settings ?: return null
    return remember(state, read, playlists, queues, ledger) { LibraryUi(state, read, playlists, queues, ledger) }
}

/** Changes the settings in the background; a change that could not be stored says so. */
@Composable
fun rememberSettingsChange(): ((Settings) -> Settings) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        { change -> scope.launch { if (!Lyrify.settings(context).update(change)) toasts.say(resources, R.string.settings_not_saved) } }
    }
}

/** KJB's own player, for the lists' taps and headers. */
@Composable
fun rememberPlayback(): PlaybackCommands {
    val context = LocalContext.current
    return remember(context) { Lyrify.playback(context) }
}

// A page of songs

/** A page's songs as listed, where they play from, its selection and its sheets: what its header and rows act on. */
@Stable
class PageSongs(
    val ui: LibraryUi,
    val songs: List<Song>,
    val source: QueueSource,
    val sheets: SongSheets,
    val selection: SongSelection,
    val onSelection: (SongSelection) -> Unit,
    private val playback: PlaybackCommands,
    private val sourceName: String,
) {
    fun play(at: Int = 0) = playback.play(songs.map { it.ref }, at, source)

    fun shuffle() = playback.shuffle(songs.map { it.ref }, source)

    /** "Add all": the Add to sheet for every song, in the list's order. */
    fun addAll() = addTo(songs)

    /** The Add to sheet for [these], from this list's source: an album's "⋯" gives its songs in album order. */
    fun addTo(these: List<Song>) = sheets.show(OpenSheet.AddTo(these.map { it.ref }, source, sourceName))

    /** A tap on [song] at [at] in [songs]: it plays from there, or while selecting, it is picked or unpicked. */
    fun tap(song: Song, at: Int) = if (selection.active) onSelection(selection.toggle(song)) else play(at)

    /** A tap on [song]'s round check: it is picked or unpicked, as a tap on its row while selecting; it never plays. */
    fun check(song: Song) = onSelection(selection.toggle(song))
}

/**
 * A page of songs under its own top bar ([topBar], or the selection's while it selects): the list, its selection and
 * its sheets. [content] adds the list's items, the header and the rows ([songRows]) among them. [loaded] false: [songs]
 * is empty only because the list is still being read. [sheet] is the page's own sheet on its songs (an album's or an
 * artist's "⋯"), always in the composition.
 */
@Composable
fun SongListFrame(
    ui: LibraryUi,
    songs: List<Song>,
    source: QueueSource,
    topBar: @Composable (under: Boolean, backdrop: GlassBackdrop) -> Unit,
    state: LazyListState = rememberLazyListState(),
    loaded: Boolean = true,
    sheet: @Composable (PageSongs) -> Unit = {},
    content: LazyListScope.(PageSongs) -> Unit,
) {
    SongPageFrame(ui, songs, source, topBar, loaded, sheet) { list, padding ->
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            content(list)
        }
    }
}

/**
 * [SongListFrame] with a [body] of the page's own (Favourites' tabs under its hero), given the page's songs and the
 * room the bars take. In a [TabsFrame] (Library's Songs tab) the selection's top bar takes the shared bar's place.
 */
@Composable
fun SongPageFrame(
    ui: LibraryUi,
    songs: List<Song>,
    source: QueueSource,
    topBar: @Composable (under: Boolean, backdrop: GlassBackdrop) -> Unit,
    loaded: Boolean = true,
    sheet: @Composable (PageSongs) -> Unit = {},
    body: @Composable (PageSongs, PaddingValues) -> Unit,
) {
    val sheets = rememberSongSheets()
    var selection by rememberSaveable(stateSaver = SongSelection.Saver) { mutableStateOf(SongSelection()) }
    val playback = rememberPlayback()
    val name = sourceLabel(LocalResources.current, source)
    val list = PageSongs(ui, songs, source, sheets, selection, { selection = it }, playback, name)
    SelectionHost(selection, songs, loaded, ui, sheets) { selection = it }
    LocalTabPage.current?.OwnTopBar(if (selection.active) { under, backdrop -> SelectionTopBar(selection, songs, under, backdrop) { selection = it } } else null)
    PageFrame(
        topBar = { under, backdrop -> if (selection.active) SelectionTopBar(selection, songs, under, backdrop) { selection = it } else topBar(under, backdrop) },
    ) { padding -> body(list, padding) }
    SongSheetsHost(sheets, ui) { selection = SongSelection() }
    sheet(list)
}

/**
 * An item of a page's list kept clear of the shell's Now bar and dock: once laid out, the list scrolls just enough for
 * all of it to show above them (an empty playlist's hint, under its hero).
 */
@Composable
fun AboveChrome(content: @Composable () -> Unit) {
    val chrome = with(LocalDensity.current) { LocalShellChrome.current.bottom.toPx() }
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(size, chrome) {
        if (size.height > 0) requester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + chrome))
    }
    Box(Modifier.bringIntoViewRequester(requester).onSizeChanged { size = it }) { content() }
}

/**
 * The rows of [shown], the page's songs from [offset] on (all of them, a heading's, or a folder's own songs, which come
 * first), each playing [PageSongs.songs] from where it is in them. [place] says where the song sheet opens (a playlist:
 * the song's place in it); [note] adds a word after the artist ("12 plays"); [keys]: the rows' own keys, by place (the
 * entries of a list that may hold a song twice, [OrderedEntries]), else each song's path. With [reorder] (a list in its
 * custom order, all of it shown) each row has a drag handle at its end and TalkBack's Move up and Move down. [tour]: the
 * tour's Play a song may point at these rows (Library's Songs). [reason]: a suggestion's reason in grey under a row
 * (For you, More like this).
 */
fun LazyListScope.songRows(
    list: PageSongs,
    shown: List<Song> = list.songs,
    offset: Int = 0,
    place: (Int) -> SheetPlace = { SheetPlace.List },
    note: ((Song) -> String?)? = null,
    keys: List<Any> = songRowKeys(shown),
    reorder: SongReorder? = null,
    tour: Boolean = false,
    reason: ((Song) -> String?)? = null,
) {
    // Places in [shown]: while a row is dragged, in the drag's order.
    val places = reorder?.shown(shown) ?: shown.indices.toList()
    items(places, key = { songRowKey(keys, it) }, contentType = { "song" }) { at ->
        val song = shown[at]
        val index = offset + at
        val reduced = LocalReducedMotion.current
        val key = songRowKey(keys, at)
        val lift = reorder?.let { animateFloatAsState(if (it.heldKey == key) 1f else 0f, motion(Motion.press, reduced), label = "row lift") }
        var height by remember { mutableFloatStateOf(0f) }
        val resources = LocalResources.current
        SongRow(
            song = song,
            ui = list.ui,
            selecting = list.selection.active,
            selected = song.path in list.selection.paths,
            note = note?.invoke(song),
            reason = reason?.invoke(song),
            onTap = { list.tap(song, index) },
            onCheck = { list.check(song) },
            onLongPress = { list.onSelection(SongSelection(setOf(song.path))) },
            onMore = { list.sheets.show(OpenSheet.Menu(song.ref, place(index))) },
            handle = reorder?.let { { DragHandle(it, shown, at, key) { height } } },
            moves = if (reorder == null) emptyList() else rowMoves(at, shown.size).map { move -> CustomAccessibilityAction(resources.getString(move.words)) { reorder.move(at, move.to, shown.size); true } },
            // An extension on the list, not a composable: reduced motion is read in the row.
            modifier = if (reorder != null && lift != null) {
                reorderMotion(reorder, key, lift, MaterialTheme.colorScheme.surfaceContainerHigh, reduced, LocalDensity.current).onSizeChanged { height = it.height.toFloat() }
            } else {
                rowMotion(reduced)
            }.tourTarget(TourStop.SONG.takeIf { tour }, if (tour) stringResource(R.string.tour_play_song, list.ui.title(song)) else null) { list.tap(song, index) },
        )
    }
}

/**
 * The keys of [songs]' rows, by place: a song's path, so a sort or a song added at the top moves rows instead of making
 * each one new. A list that may hold a song twice keys its rows by entry instead ([OrderedEntries]).
 */
fun songRowKeys(songs: List<Song>): List<Any> = songs.map { "song ${it.path}" }

/**
 * The key of the row at [at] of a list's rows, by place ([keys]). A page takes its rows and their keys from one list
 * ([ListedEntry]), so no row is past the keys; should one be asked for all the same, it gets a key of its own, equal to
 * no row's and saveable as a lazy list's key must be, never a crash.
 */
fun songRowKey(keys: List<Any>, at: Int): Any = keys.getOrNull(at) ?: PastTheKeys(at)

/** The key of a song row asked for past its list's keys ([songRowKey]). */
private data class PastTheKeys(val at: Int) : Serializable

// The list header

/**
 * Every list's header: the count, Shuffle and Play, then Add all on collection pages ([onAddAll]) and the sort chip
 * ([sort], the order shown), with [besideSort] before it (Most played's Insights button). On a narrow screen the chip
 * drops its word first, then Shuffle, then Play, then the chip goes ([headerFit]), so nothing overlaps or scrolls
 * sideways; [besideSort] always stays.
 */
@Composable
fun ListHeader(
    count: Int,
    @PluralsRes noun: Int,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    songs: Int = count,
    onAddAll: (() -> Unit)? = null,
    sort: String? = null,
    onSort: () -> Unit = {},
    gutter: Dp = ScreenPadding,
    besideSort: @Composable () -> Unit = {},
) {
    val any = songs > 0
    SubcomposeLayout(Modifier.fillMaxWidth().padding(horizontal = gutter).heightIn(min = LIST_HEADER_HEIGHT)) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun measure(slot: String, content: @Composable () -> Unit) = subcompose(slot, content).map { it.measure(loose) }
        val gap = 8.dp.roundToPx()
        val countPart = measure("count") { CountLabel(count, noun) }
        val plays = listOf(true, false).associateWith { label ->
            measure("play $label") { HeaderButton(R.drawable.ic_play_arrow_fill, stringResource(R.string.lib_play), any, primary = true, label = label, onPlay) }
        }
        val shuffles = listOf(true, false).associateWith { label ->
            measure("shuffle $label") { HeaderButton(R.drawable.ic_shuffle, stringResource(R.string.lib_shuffle), any, primary = false, label = label, onShuffle) }
        }
        // Nothing to add from an empty list: no "Add all 0 songs".
        val add = onAddAll?.takeIf { any }?.let { onClick -> measure("add") { AddAllButton(songs, onClick) } }.orEmpty()
        val chips = sort?.let { order -> listOf(true, false).associateWith { label -> measure("sort $label") { SortChip(order, label, onSort) } } }
        val beside = measure("beside sort", besideSort)
        fun width(parts: List<androidx.compose.ui.layout.Placeable>) = parts.sumOf { it.width }
        fun widths(parts: Map<Boolean, List<androidx.compose.ui.layout.Placeable>>, before: Int = 0) =
            PartWidth(width(parts.getValue(true)) + before, width(parts.getValue(false)) + before)
        val fixed = width(countPart) + width(add) + gap * (2 + (if (add.isEmpty()) 0 else 1)) + (if (beside.isEmpty()) 0 else width(beside) + gap)
        val fit = headerFit(constraints.maxWidth, fixed, widths(shuffles), widths(plays), chips?.let { widths(it, before = gap) })
        val shuffle = shuffles.getValue(fit.shuffleLabel)
        val play = plays.getValue(fit.playLabel)
        val chip = chips?.takeIf { fit.chip != ChipFit.NONE }?.getValue(fit.chip == ChipFit.LABEL).orEmpty()
        val start = listOf(countPart, shuffle, play, add).filter { it.isNotEmpty() }
        val height = (start + listOf(chip, beside)).flatten().maxOfOrNull { it.height } ?: 0
        val rowHeight = maxOf(height, constraints.minHeight)
        layout(constraints.maxWidth, rowHeight) {
            var x = 0
            start.forEach { parts ->
                parts.forEach { it.placeRelative(x, (rowHeight - it.height) / 2) }
                x += width(parts) + gap
            }
            chip.forEach { it.placeRelative(constraints.maxWidth - it.width, (rowHeight - it.height) / 2) }
            beside.forEach { it.placeRelative(constraints.maxWidth - width(chip) - (if (chip.isEmpty()) 0 else gap) - it.width, (rowHeight - it.height) / 2) }
        }
    }
}

/** The list header's least height. */
val LIST_HEADER_HEIGHT = 60.dp

/** A list header part's width in pixels: with its word, and as its icon alone. */
data class PartWidth(val label: Int, val icon: Int) {
    fun of(withLabel: Boolean): Int = if (withLabel) label else icon
}

/** How the sort chip fits: with its word, as its icon, or left out. */
enum class ChipFit { LABEL, ICON, NONE }

/** How the list header fits its width: Shuffle's and Play's words, and the sort chip. */
data class HeaderFit(val shuffleLabel: Boolean, val playLabel: Boolean, val chip: ChipFit)

/**
 * The first way the list header fits [maxWidth]: the sort chip drops its word, then Shuffle, then Play, then the chip
 * goes, so nothing overlaps at 360 dp. [fixed] is what never shrinks (the count, Add all, a button beside the sort, the
 * gaps between the parts); [chip] holds the gap before it, null when the header has no chip.
 */
fun headerFit(maxWidth: Int, fixed: Int, shuffle: PartWidth, play: PartWidth, chip: PartWidth?): HeaderFit {
    val fits = listOf(
        HeaderFit(shuffleLabel = true, playLabel = true, ChipFit.LABEL),
        HeaderFit(shuffleLabel = true, playLabel = true, ChipFit.ICON),
        HeaderFit(shuffleLabel = false, playLabel = true, ChipFit.ICON),
        HeaderFit(shuffleLabel = false, playLabel = false, ChipFit.ICON),
        HeaderFit(shuffleLabel = false, playLabel = false, ChipFit.NONE),
    ).map { if (chip == null) it.copy(chip = ChipFit.NONE) else it }
    fun width(fit: HeaderFit) = fixed + shuffle.of(fit.shuffleLabel) + play.of(fit.playLabel) + when (fit.chip) {
        ChipFit.LABEL -> chip?.label ?: 0
        ChipFit.ICON -> chip?.icon ?: 0
        ChipFit.NONE -> 0
    }
    return fits.firstOrNull { width(it) <= maxWidth } ?: fits.last()
}

/** "1,234" over "songs", one TalkBack stop. */
@Composable
private fun CountLabel(count: Int, @PluralsRes noun: Int) {
    val words = pluralStringResource(noun, count)
    val number = formatCount(count)
    Column(Modifier.clearAndSetSemantics { contentDescription = "$number $words" }) {
        Text(number, style = CountNumber.tabular(), maxLines = 1)
        Text(words, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Shuffle or Play: a 40 dp capsule with its icon and word ([label]), or the icon alone in a circle; a 48 dp tap. */
@Composable
internal fun HeaderButton(@DrawableRes icon: Int, text: String, enabled: Boolean, primary: Boolean, label: Boolean, onClick: () -> Unit) {
    val chrome = LocalChromeColors.current
    val colors = MaterialTheme.colorScheme
    val fill = when {
        !enabled -> colors.onSurface.copy(alpha = 0.08f)
        primary -> chrome.action
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        primary -> chrome.onAction
        else -> colors.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = null, onClick = onClick)
            .semantics { contentDescription = text }
            .minimumInteractiveComponentSize()
            .background(fill, CircleShape)
            .heightIn(min = 40.dp)
            .padding(horizontal = if (label) 16.dp else 11.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        if (label) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = content,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** Add all: an icon button that opens Add to for every song; TalkBack hears "Add all 7 songs". */
@Composable
private fun AddAllButton(songs: Int, onClick: () -> Unit) {
    ChromeIconButton(R.drawable.ic_playlist_add, pluralStringResource(R.plurals.lib_add_all, songs, songs), onClick, glass = false, iconSize = 22.dp)
}

/** The order shown, as a chip that opens the sort sheet; with [label] false only its icon. */
@Composable
private fun SortChip(order: String, label: Boolean, onClick: () -> Unit) {
    val name = stringResource(R.string.lib_sort_by, order)
    LyrifyChip(selected = false, onClick = onClick, role = Role.Button, icon = R.drawable.ic_sort, iconTint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = name }) {
        if (label) Text(order, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** One choice of a few in a sort sheet: the chosen one on the brand's container with a check. */
@Composable
fun <T> SortSheet(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
    LyrifySheet(onDismiss = onDismiss, spacing = 4.dp, footer = { LyrifyButton(stringResource(R.string.lib_done), onDismiss, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY) }) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
        Column(Modifier.selectableGroup()) {
            options.forEach { (value, words) -> MenuRow(words, icon = null, onClick = { onSelect(value) }, selected = value == selected) }
        }
        extra()
    }
}

// Rows and tiles

/**
 * A song's row: its cover, title and artist, the lyrics icon after the artist for a song with lyrics, and for the song
 * playing its coral title, the equalizer mark and the green dot before the artist ([rowMarks]), which fade from row to
 * row as the song changes; a tap plays once a moment has passed with no second tap ([WaitingTap]), a double tap only
 * makes it a favourite ([doubleTapFavourite]; while selecting a tap acts at once), "⋯" opens the song sheet, a long
 * press selects (none without [onLongPress]). Search passes its [shownTitle] with the match highlighted, and for a song
 * found in its lyrics the matching [line] under the artist. While selecting, a tap on the row or on its round check
 * ([onCheck], 48 dp) picks or unpicks it, and a picked row has its own rounded fill ([rowFill]). In a custom order a
 * [handle] comes before "⋯", and TalkBack gets the row's [moves]. A suggested song may carry the suggestion [mark] before
 * its artist and its [reason] in grey under it (suggestions/).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    ui: LibraryUi,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)?,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onCheck: () -> Unit = onTap,
    handle: (@Composable () -> Unit)? = null,
    moves: List<CustomAccessibilityAction> = emptyList(),
    note: String? = null,
    shownTitle: AnnotatedString? = null,
    line: AnnotatedString? = null,
    mark: String? = null,
    reason: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val title = shownTitle?.text ?: ui.title(song)
    val artist = song.artist ?: stringResource(R.string.check_no_artist)
    val marks = rowMarks(ui.status(song), song.path, LocalPlayingPath.current)
    val markWords = when (marks.lyrics) {
        LyricsMark.SYNCED -> stringResource(R.string.lib_mark_synced)
        LyricsMark.PLAIN -> stringResource(R.string.lib_mark_plain)
        LyricsMark.NONE -> null
    }
    val description = songRowWords(title, artist, markWords, note, line?.text, if (marks.playing) stringResource(R.string.lib_playing) else null, mark, reason)
    val tapLabel = stringResource(if (selecting) R.string.lib_select else R.string.lib_play)
    val holdLabel = if (onLongPress != null) stringResource(R.string.lib_select) else null
    val tap = rememberWaitingTap(onTap)
    val brand = LocalBrandColors.current
    val playing = marks.playing
    val reduced = LocalReducedMotion.current
    val fill by animateColorAsState(if (selected) brand.highlightContainer.copy(alpha = SELECTED_ALPHA) else Color.Transparent, motion(tween(Motion.QUICK_MS), reduced), label = "row fill")
    val titleInk by animateColorAsState(
        when {
            playing && selected -> brand.onHighlightContainer
            playing -> brand.highlight
            else -> colors.onSurface
        },
        motion(tween(Motion.OPEN_MS), reduced),
        label = "row title",
    )
    // The fill is the whole row's, inset from its sides and ends ([rowFill]), so neighbours selected read as two rows.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().drawBehind { drawRowFill(fill) }.padding(start = 8.dp, end = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(ROW_FILL_CORNER))
                .doubleTapFavourite(if (selecting) null else FavouriteTarget.Song(song.ref), waiting = tap)
                .combinedClickable(onClickLabel = tapLabel, onLongClickLabel = holdLabel, onLongClick = onLongPress?.takeUnless { selecting }, onClick = tap::click)
                .semantics {
                    contentDescription = description
                    if (selecting) this.selected = selected
                    if (moves.isNotEmpty()) customActions = moves
                }
                .heightIn(min = 64.dp)
                .padding(8.dp),
        ) {
            SongCover(SongArt(song.path, song.ref.mediaStoreId))
            Column(Modifier.weight(1f).clearAndSetSemantics {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The song playing (.srow.playing): the equalizer mark and its title in coral.
                    RowMark(playing, reduced) {
                        Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, tint = if (selected) brand.onHighlightContainer else brand.highlight, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                    }
                    Text(
                        shownTitle ?: AnnotatedString(title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = titleInk,
                        fontWeight = if (playing) FontWeight.SemiBold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Only the song playing has the green dot.
                    RowMark(playing, reduced) { PlayingDot(Modifier.padding(end = 6.dp)) }
                    if (mark != null) SuggestedMark(mark, Modifier.padding(end = 6.dp))
                    Text(
                        listOfNotNull(artist, note).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // A song with lyrics, synced or plain: the lyrics icon right after its artist.
                    if (marks.lyrics != LyricsMark.NONE) LyricsIcon(Modifier.padding(start = 6.dp))
                }
                if (line != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Icon(painterResource(R.drawable.ic_lyrics), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (reason != null) ReasonLine(reason)
            }
        }
        // The handle sits at the end, before "⋯", clear of the system's back gesture at the screen's edges.
        handle?.invoke()
        // Selection starts and ends with "⋯" and the checkbox fading through each other, never swapped at once.
        Crossfade(selecting, animationSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.FADE_IN_MS), label = "row end") { checking ->
            if (checking) {
                // A tap on the check picks or unpicks the song, as a tap on the row does; TalkBack has it on the row.
                val press = remember { MutableInteractionSource() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(CHECK_TAG)
                        .clearAndSetSemantics {}
                        .clickable(interactionSource = press, indication = null, onClick = onCheck),
                ) { LyrifyCheckbox(selected, Modifier.indication(press, PressIndication(CircleShape, 0.9f))) }
            } else {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_for, title), onMore, glass = false, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * What TalkBack says for a song's row: its title, artist, lyrics, note and lyrics line, ([playing]) that it plays, and a
 * suggestion's [mark] and [reason].
 */
fun songRowWords(title: String, artist: String, lyrics: String?, note: String?, line: String?, playing: String?, mark: String? = null, reason: String? = null): String =
    listOfNotNull(title, artist, lyrics, note, line, playing, mark, reason).joinToString(", ")

/** The path of the song KJB's own player has loaded; the shell provides it, so its row shows it playing. */
val LocalPlayingPath = staticCompositionLocalOf<String?> { null }

/** A selected row's fill: the brand's container at 62% (.srow.sel). */
private const val SELECTED_ALPHA = 0.62f


/** A list header's count (.cnt b): Bricolage 19/22. */
private val CountNumber = display(19, 22, -0.01)

/** A mark of the song playing that fades and slides in and out beside the words, never appearing at once. */
@Composable
private fun RowMark(shown: Boolean, reduced: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(shown, enter = chipIn(reduced), exit = chipOut(reduced)) { content() }
}

/** The green dot before the artist of the song playing. Decorative: the row is marked by its coral title. */
@Composable
private fun PlayingDot(modifier: Modifier) {
    val color = LocalStatusColors.current[Tone.SYNCED].accent
    Canvas(modifier.size(8.dp)) { drawCircle(color) }
}

/** The lyrics icon after a song's artist, in the secondary ink. Decorative: the row says "Synced lyrics" or "Lyrics". */
@Composable
fun LyricsIcon(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_lyrics), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.size(15.dp))
}

/**
 * A row that opens something (a folder, an artist, a playlist, a queue): its tile, title, line and a chevron. One
 * TalkBack stop. With [favourite] a second tap soon after the one that opened it makes that a favourite
 * ([doubleTapFavourite]).
 */
@Composable
fun NavRow(
    title: String,
    sub: String?,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
    description: String? = null,
    favourite: FavouriteTarget? = null,
    lead: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
    onClick: () -> Unit,
) {
    val words = description ?: listOfNotNull(title, sub).joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .doubleTapFavourite(favourite)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = words }
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(Modifier.clearAndSetSemantics {}) { lead() }
        Column(Modifier.weight(1f).clearAndSetSemantics {}) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, fontWeight = if (titleColor.isSpecified()) FontWeight.SemiBold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

private fun Color.isSpecified(): Boolean = this != Color.Unspecified

/** "New playlist", "New queue": a row with a plus tile and a coral title. */
@Composable
fun NewRow(title: String, onClick: () -> Unit) {
    val brand = LocalBrandColors.current
    NavRow(title = title, sub = null, titleColor = brand.highlight, lead = { Tile(R.drawable.ic_add, MaterialTheme.colorScheme.surfaceContainerHigh, brand.highlight) }, trailing = null, onClick = onClick)
}

/** An icon on a tinted rounded square, in place of a cover. */
@Composable
fun Tile(
    @DrawableRes icon: Int,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 48.dp,
) {
    // The prototype's .tile48 (14 dp corners, a 24 dp icon) and its 200 dp hero (28 dp corners, a 72 dp icon).
    val hero = size > 64.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(RoundedCornerShape(if (hero) 28.dp else 14.dp)).background(container)) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(if (hero) size * 9 / 25 else size / 2))
    }
}

/**
 * The covers of a list's mosaic (the prototype's .mosaic): the first song of each folder (an album, mostly), the first
 * four; two or three fill the four places in turn; one stays a single cover.
 */
fun mosaicCovers(songs: List<SongRef>): List<SongRef> {
    val firsts = songs.distinctBy { it.path.substringBeforeLast('/') }.take(MOSAIC)
    return if (firsts.size in 2 until MOSAIC) List(MOSAIC) { firsts[it % firsts.size] } else firsts
}

private const val MOSAIC = 4

/** A playlist's tile: a 2x2 mosaic of its covers ([mosaicCovers]), one cover, or the playlist icon when it is empty. */
@Composable
fun PlaylistTile(playlist: Playlist, size: Dp = 48.dp) = MosaicTile(playlist.songs, size)

/** A list's tile: a 2x2 mosaic of [songs]' covers ([mosaicCovers]), one cover, or the playlist icon when there is none. */
@Composable
fun MosaicTile(songs: List<SongRef>, size: Dp = 48.dp) {
    val covers = remember(songs) { mosaicCovers(songs) }
    // The prototype's corners: 14 dp at 48 dp (.mosaic), 28 dp on a page's hero (.mosaic.big).
    val shape = RoundedCornerShape(if (size > 64.dp) 28.dp else 14.dp)
    when (covers.size) {
        0 -> Tile(R.drawable.ic_queue_music, size = size)
        1 -> covers.single().let { SongCover(SongArt(it.path, it.mediaStoreId), size = size, shape = shape) }
        else -> Column(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            covers.chunked(2).forEach { pair ->
                Row {
                    pair.forEach { SongCover(SongArt(it.path, it.mediaStoreId), size = size / 2, shape = RectangleShape) }
                }
            }
        }
    }
}

/** An artist's round image: the cover of their newest album, or their initials. */
@Composable
fun ArtistAvatar(artist: Artist, size: Dp = 48.dp) {
    val cover = artist.albums.firstOrNull()?.songs?.firstOrNull() ?: artist.songs.firstOrNull()?.takeIf { artist.albums.isNotEmpty() }
    if (cover != null) {
        SongCover(SongArt(cover.path, cover.ref.mediaStoreId), size = size, shape = CircleShape)
        return
    }
    val initials = artist.name.split(' ').filter(String::isNotBlank).take(2).joinToString("") { it.take(1) }.uppercase()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
        Text(initials, style = if (size > 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * An album as a card: its cover filling the width, its title and a line under it ([sub]: the artist, or the year). With
 * [favourite] a second tap soon after the one that opened it makes the album a favourite ([doubleTapFavourite]). Its
 * [ALBUM_CARD_TAG] reaches UiAutomator as a resource id, so a device test opens an album without knowing any.
 */
@Composable
fun AlbumCard(title: String, sub: String?, cover: Song?, modifier: Modifier = Modifier, favourite: FavouriteTarget? = null, onClick: () -> Unit) {
    val words = listOfNotNull(title, sub).joinToString(", ")
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .doubleTapFavourite(favourite)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = words
                testTagsAsResourceId = true
            }
            .testTag(ALBUM_CARD_TAG)
            .padding(bottom = 8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            SongCover(cover?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = maxWidth, shape = RoundedCornerShape(20.dp))
        }
        Text(title, style = CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {})
        if (sub != null) Text(sub, style = CardLine, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** An album card's test tag. */
const val ALBUM_CARD_TAG: String = "album card"

/** An album card's title (.acard .at, 15/20 medium) and line (.aa, 13/18). */
private val CardTitle = body(15f, 20, 500)
private val CardLine = body(13f, 18)

/** A section's title over a list, with its count and maybe "View all". */
@Composable
fun ListHeading(title: String, count: Int? = null, onViewAll: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = ScreenPadding, end = 4.dp, top = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (count != null) {
            Text(
                formatCount(count),
                style = MaterialTheme.typography.labelLarge.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(Modifier.weight(1f))
        if (onViewAll != null) LyrifyButton(stringResource(R.string.lib_view_all), onViewAll, kind = ButtonKind.GHOST, compact = true)
    }
}

