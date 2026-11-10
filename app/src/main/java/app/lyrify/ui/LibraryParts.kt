package app.lyrify.ui

import androidx.compose.ui.text.font.lerp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.mutableIntStateOf
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
    fun addAll() = sheets.show(OpenSheet.AddTo(songs.map { it.ref }, source, sourceName))

    /** A tap on [song] at [at] in [songs]: it plays from there, or while selecting, it is picked or unpicked. */
    fun tap(song: Song, at: Int) = if (selection.active) onSelection(selection.toggle(song)) else play(at)
}

/**
 * A page of songs under its own top bar ([topBar], or the selection's while it selects): the list, its selection and
 * its sheets. [content] adds the list's items, the header and the rows ([songRows]) among them. [loaded] false: [songs]
 * is empty only because the list is still being read.
 */
@Composable
fun SongListFrame(
    ui: LibraryUi,
    songs: List<Song>,
    source: QueueSource,
    topBar: @Composable (under: Boolean, backdrop: GlassBackdrop) -> Unit,
    state: LazyListState = rememberLazyListState(),
    loaded: Boolean = true,
    content: LazyListScope.(PageSongs) -> Unit,
) {
    val sheets = rememberSongSheets()
    var selection by rememberSaveable(stateSaver = SongSelection.Saver) { mutableStateOf(SongSelection()) }
    val playback = rememberPlayback()
    val name = sourceLabel(LocalResources.current, source)
    val list = PageSongs(ui, songs, source, sheets, selection, { selection = it }, playback, name)
    SelectionHost(selection, songs, loaded, ui, sheets) { selection = it }
    PageFrame(
        topBar = { under, backdrop -> if (selection.active) SelectionTopBar(selection, songs, under, backdrop) { selection = it } else topBar(under, backdrop) },
    ) { padding ->
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            content(list)
        }
    }
    SongSheetsHost(sheets, ui) { selection = SongSelection() }
}

/**
 * The rows of [shown], the page's songs from [offset] on (all of them, a heading's, or a folder's own songs, which come
 * first), each playing [PageSongs.songs] from where it is in them. [place] says where the song sheet opens (a playlist:
 * the song's place in it); [note] adds a word after the artist ("12 plays"); [repeats]: the list may hold a song twice
 * (a playlist).
 */
fun LazyListScope.songRows(
    list: PageSongs,
    shown: List<Song> = list.songs,
    offset: Int = 0,
    place: (Int) -> SheetPlace = { SheetPlace.List },
    note: ((Song) -> String?)? = null,
    repeats: Boolean = false,
) {
    itemsIndexed(shown, key = { at, song -> songRowKey(song, offset + at, repeats) }) { at, song ->
        val index = offset + at
        SongRow(
            song = song,
            ui = list.ui,
            selecting = list.selection.active,
            selected = song.path in list.selection.paths,
            note = note?.invoke(song),
            onTap = { list.tap(song, index) },
            onLongPress = { list.onSelection(SongSelection(setOf(song.path))) },
            onMore = { list.sheets.show(OpenSheet.Menu(song.ref, place(index))) },
            modifier = Modifier.animateItem(),
        )
    }
}

/**
 * A song row's key: its path, so a sort or a song added at the top moves rows instead of making each one new; with its
 * place [at] too in a list that may hold a song twice ([repeats]).
 */
fun songRowKey(song: Song, at: Int, repeats: Boolean): String = if (repeats) "song ${song.path}#$at" else "song ${song.path}"

// The list header

/**
 * Every list's header: the count, Shuffle and Play, then Add all on collection pages ([onAddAll]) and the sort chip
 * ([sort], the order shown). On a narrow screen the chip drops its word first, then Shuffle, then Play, then the chip
 * goes ([headerFit]), so nothing overlaps or scrolls sideways.
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
) {
    val any = songs > 0
    SubcomposeLayout(Modifier.fillMaxWidth().padding(horizontal = gutter).heightIn(min = 60.dp)) { constraints ->
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
        val add = onAddAll?.let { onClick -> measure("add") { AddAllButton(songs, any, onClick) } }.orEmpty()
        val chips = sort?.let { order -> listOf(true, false).associateWith { label -> measure("sort $label") { SortChip(order, label, onSort) } } }
        fun width(parts: List<androidx.compose.ui.layout.Placeable>) = parts.sumOf { it.width }
        fun widths(parts: Map<Boolean, List<androidx.compose.ui.layout.Placeable>>, before: Int = 0) =
            PartWidth(width(parts.getValue(true)) + before, width(parts.getValue(false)) + before)
        val fixed = width(countPart) + width(add) + gap * (2 + (if (add.isEmpty()) 0 else 1))
        val fit = headerFit(constraints.maxWidth, fixed, widths(shuffles), widths(plays), chips?.let { widths(it, before = gap) })
        val shuffle = shuffles.getValue(fit.shuffleLabel)
        val play = plays.getValue(fit.playLabel)
        val chip = chips?.takeIf { fit.chip != ChipFit.NONE }?.getValue(fit.chip == ChipFit.LABEL).orEmpty()
        val start = listOf(countPart, shuffle, play, add).filter { it.isNotEmpty() }
        val height = (start + listOf(chip)).flatten().maxOfOrNull { it.height } ?: 0
        val rowHeight = maxOf(height, constraints.minHeight)
        layout(constraints.maxWidth, rowHeight) {
            var x = 0
            start.forEach { parts ->
                parts.forEach { it.placeRelative(x, (rowHeight - it.height) / 2) }
                x += width(parts) + gap
            }
            chip.forEach { it.placeRelative(constraints.maxWidth - it.width, (rowHeight - it.height) / 2) }
        }
    }
}

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
 * goes, so nothing overlaps at 360 dp. [fixed] is what never shrinks (the count, Add all, the gaps between the parts on
 * the left); [chip] holds the gap before it, null when the header has no chip.
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
private fun HeaderButton(@DrawableRes icon: Int, text: String, enabled: Boolean, primary: Boolean, label: Boolean, onClick: () -> Unit) {
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
private fun AddAllButton(songs: Int, enabled: Boolean, onClick: () -> Unit) {
    ChromeIconButton(R.drawable.ic_playlist_add, pluralStringResource(R.plurals.lib_add_all, songs, songs), onClick, enabled = enabled, glass = false, iconSize = 22.dp)
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
 * A song's row: its cover, title and artist with the lyrics mark; a tap plays, "⋯" opens the song sheet, a long press
 * selects (none without [onLongPress]). Search passes its [shownTitle] with the match highlighted, and for a song found
 * in its lyrics the matching [line] under the artist.
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
    note: String? = null,
    shownTitle: AnnotatedString? = null,
    line: AnnotatedString? = null,
) {
    val colors = MaterialTheme.colorScheme
    val title = shownTitle?.text ?: ui.title(song)
    val artist = song.artist ?: stringResource(R.string.check_no_artist)
    val mark = lyricsMark(ui.status(song))
    val markWords = when (mark) {
        LyricsMark.SYNCED -> stringResource(R.string.lib_mark_synced)
        LyricsMark.PLAIN -> stringResource(R.string.lib_mark_plain)
        LyricsMark.NONE -> null
    }
    val description = listOfNotNull(title, artist, markWords, note, line?.text).joinToString(", ")
    val tapLabel = stringResource(if (selecting) R.string.lib_select else R.string.lib_play)
    val holdLabel = if (onLongPress != null) stringResource(R.string.lib_select) else null
    val brand = LocalBrandColors.current
    val playing = LocalPlayingPath.current == song.path
    val fill by animateColorAsState(if (selected) brand.highlightContainer.copy(alpha = SELECTED_ALPHA) else Color.Transparent, motion(tween(Motion.QUICK_MS), LocalReducedMotion.current), label = "row fill")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(fill)
                .combinedClickable(onClickLabel = tapLabel, onLongClickLabel = holdLabel, onLongClick = onLongPress?.takeUnless { selecting }, onClick = onTap)
                .semantics {
                    contentDescription = description
                    if (selecting) this.selected = selected
                }
                .heightIn(min = 64.dp)
                .padding(8.dp),
        ) {
            SongCover(SongArt(song.path, song.ref.mediaStoreId))
            Column(Modifier.weight(1f).clearAndSetSemantics {}) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The song playing (.srow.playing): the equalizer mark and its title in coral.
                    if (playing) Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, tint = if (selected) brand.onHighlightContainer else brand.highlight, modifier = Modifier.size(18.dp))
                    Text(
                        shownTitle ?: AnnotatedString(title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            playing && selected -> brand.onHighlightContainer
                            playing -> brand.highlight
                            else -> Color.Unspecified
                        },
                        fontWeight = if (playing) FontWeight.SemiBold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LyricsMarkDot(mark)
                    Text(
                        listOfNotNull(artist, note).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (line != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Icon(painterResource(R.drawable.ic_lyrics), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (selecting) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clearAndSetSemantics {}) { LyrifyCheckbox(selected) }
        } else {
            ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_for, title), onMore, glass = false, tint = colors.onSurfaceVariant)
        }
    }
}

/** The path of the song KJB's own player has loaded; the shell provides it, so its row shows it playing. */
val LocalPlayingPath = staticCompositionLocalOf<String?> { null }

/** A selected row's fill: the brand's container at 62% (.srow.sel). */
private const val SELECTED_ALPHA = 0.62f

/** A list header's count (.cnt b): Bricolage 19/22. */
private val CountNumber = display(19, 22, -0.01)

/** The lyrics mark: a filled dot for synced lyrics, a ring for plain ones, nothing for none. Decorative: the row says it. */
@Composable
fun LyricsMarkDot(mark: LyricsMark) {
    val tones = LocalStatusColors.current
    when (mark) {
        LyricsMark.NONE -> Unit
        LyricsMark.SYNCED -> {
            val color = tones[Tone.SYNCED].accent
            Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        }
        LyricsMark.PLAIN -> {
            val color = tones[Tone.PLAIN].accent
            Canvas(Modifier.size(8.dp)) { drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), center = Offset(size.width / 2, size.height / 2), style = Stroke(2.dp.toPx())) }
        }
    }
}

/** A row that opens something (a folder, an artist, a playlist, a queue): its tile, title, line and a chevron. One TalkBack stop. */
@Composable
fun NavRow(
    title: String,
    sub: String?,
    modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified,
    description: String? = null,
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

/** A playlist's tile: its first song's cover, or the playlist icon when it is empty. */
@Composable
fun PlaylistTile(playlist: Playlist, size: Dp = 48.dp) {
    val first = playlist.songs.firstOrNull()
    if (first == null) Tile(R.drawable.ic_queue_music, size = size) else SongCover(SongArt(first.path, first.mediaStoreId), size = size)
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

/** An album as a card: its cover filling the width, its title and a line under it ([sub]: the artist, or the year). */
@Composable
fun AlbumCard(title: String, sub: String?, cover: Song?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val words = listOfNotNull(title, sub).joinToString(", ")
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = words }
            .padding(bottom = 8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            SongCover(cover?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = maxWidth, shape = RoundedCornerShape(20.dp))
        }
        Text(title, style = CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {})
        if (sub != null) Text(sub, style = CardLine, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
    }
}

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

/** The library's text tabs under the top bar, scrolling sideways when they do not fit; the selected one has the sung line. */
@Composable
fun LibraryTabsRow(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val highlight = LocalBrandColors.current.highlight
    val colors = MaterialTheme.colorScheme
    val scroll = rememberLazyListState()
    var shown by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != shown) scroll.animateScrollToItem((selected - 1).coerceAtLeast(0))
        shown = selected
    }
    androidx.compose.foundation.lazy.LazyRow(
        state = scroll,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        itemsIndexed(tabs) { at, name ->
            val on = at == selected
            val reduced = LocalReducedMotion.current
            val lit by animateFloatAsState(if (on) 1f else 0f, motion(tween(Motion.SHORT_MS, easing = Motion.Standard), reduced), label = "tab")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(on, role = Role.Tab) { onSelect(at) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    name,
                    style = TabText,
                    fontWeight = lerp(FontWeight.Medium, FontWeight(650), lit),
                    color = lerp(colors.onSurfaceVariant, colors.onSurface, lit),
                    maxLines = 1,
                )
                // The sung line grows and fades in under the tab chosen as it leaves the one before.
                if (lit > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .size(SUNG_LINE_WIDTH, SUNG_LINE_HEIGHT)
                            .graphicsLayer {
                                alpha = lit
                                scaleX = lit
                            }
                            .background(highlight, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

/** A library tab's name (.lt): 15/20. */
private val TabText = body(15f, 20, 500)
