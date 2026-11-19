package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import app.lyrify.Lyrify
import app.lyrify.library.needsPlays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.lyrify.settings.SongList
import app.lyrify.stats.StatsGraph
import app.lyrify.R
import app.lyrify.library.RECENTLY_ADDED_DAYS
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.library.nameLetter
import app.lyrify.library.SongRef
import app.lyrify.library.arranged
import app.lyrify.library.songOrder
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.ListSort
import app.lyrify.stats.SongPlays
import app.lyrify.settings.SortSongs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import java.time.Instant
import java.time.ZoneId

// Custom order (user feedback 2026-11-13): Favourites' songs and every playlist list in their custom order by default,
// the order the rows' handles change. A drag on a handle (at the row's end, before "⋯") moves the song at once, no long
// press: the row lifts and follows the finger, its neighbours slide out of its way with a light tick per place, and the
// list scrolls near its edges. TalkBack moves a song with "Move up" and "Move down". Each move is stored at once in
// playlists.json, as the whole order shown, which the backup carries as it is.

// Sort keys (features 2026-11-15, item 5): every list of songs, the Albums and the Artists tabs sort by more keys, each
// either way round, remembered per list in settings.json. Favourites' songs and the playlists keep their custom order
// as their own, first in their sheet; an album page its track order.

/**
 * [entries] (each a song, [song] of it, with what goes with it, such as its place in a playlist) in [sort]: their own
 * order (an album's tracks, the custom order) as they are; else by its key ([songOrder], [plays] by path), ties in
 * their own order.
 */
fun <T> inOrder(entries: List<T>, sort: ListSort, settings: LibrarySettings, plays: Map<String, SongPlays> = emptyMap(), song: (T) -> Song): List<T> {
    val key = sort.key ?: return entries
    return entries.sortedWith(compareBy(songOrder(key, settings, sort.reversed, plays), song))
}

/**
 * [inOrder] once the [plays] its key needs are read; null while they are not (null), so a list sorted by plays is never
 * shown first in the order of a list without any. The own order and the other keys need none.
 */
fun <T> inOrderOnce(entries: List<T>, sort: ListSort, settings: LibrarySettings, plays: Map<String, SongPlays>?, song: (T) -> Song): List<T>? {
    if (sort.key?.needsPlays == true && plays == null) return null
    return inOrder(entries, sort, settings, plays.orEmpty(), song)
}

/**
 * [entries] (each a song, [song] of it) in [sort], worked out off the main thread, as natural order compares names piece
 * by piece. The own order at once; a key's order once it and the plays it needs are ready, the order shown before
 * meanwhile (null before the first).
 */
@Composable
fun <T> rememberInOrder(entries: List<T>, sort: ListSort, settings: LibrarySettings, song: (T) -> Song): List<T>? {
    val plays = rememberPlays(sort.key?.needsPlays == true)
    val sorted by produceState<List<T>?>(null, entries, sort, settings, plays) {
        val ready = withContext(Dispatchers.Default) { inOrderOnce(entries, sort, settings, plays, song) }
        if (ready != null) value = ready
    }
    return if (sort.key == null) entries else sorted
}

/** Only the custom order shows the handles: it is the order they change. */
val ListSort.reorders: Boolean get() = key == null

/** A key's two ways round, each as the sort sheet says it: its first way, then the other ([SortSongs]). */
enum class SortWays(@StringRes val first: Int, @StringRes val other: Int) {
    NAMES(R.string.lib_way_a_z, R.string.lib_way_z_a),
    TIMES(R.string.lib_way_newest, R.string.lib_way_oldest),
    COUNTS(R.string.lib_way_most, R.string.lib_way_fewest),
    LENGTHS(R.string.lib_way_longest, R.string.lib_way_shortest),
}

@get:StringRes
internal val SortSongs.words: Int
    get() = when (this) {
        SortSongs.TITLE -> R.string.lib_sort_title
        SortSongs.ARTIST -> R.string.lib_sort_artist
        SortSongs.ALBUM -> R.string.lib_sort_album
        SortSongs.YEAR -> R.string.lib_sort_year
        SortSongs.RECENTLY_ADDED -> R.string.lib_sort_date_added
        SortSongs.PLAY_COUNT -> R.string.lib_sort_play_count
        SortSongs.LAST_PLAYED -> R.string.lib_sort_last_played
        SortSongs.DURATION -> R.string.lib_sort_length
        SortSongs.FILE_NAME -> R.string.lib_sort_file_name
    }

internal val SortSongs.ways: SortWays
    get() = when (this) {
        SortSongs.TITLE, SortSongs.ARTIST, SortSongs.ALBUM, SortSongs.FILE_NAME -> SortWays.NAMES
        SortSongs.YEAR, SortSongs.RECENTLY_ADDED, SortSongs.LAST_PLAYED -> SortWays.TIMES
        SortSongs.PLAY_COUNT -> SortWays.COUNTS
        SortSongs.DURATION -> SortWays.LENGTHS
    }

/** The list header's chip for [words] [reversed] or not on [ways]: "Year", or "Year · Oldest first". */
@Composable
internal fun sortChip(@StringRes words: Int, ways: SortWays?, reversed: Boolean): String =
    if (ways != null && reversed) stringResource(R.string.lib_after, stringResource(words), stringResource(ways.other)) else stringResource(words)

/** The chip of a song page in [sort], its own order called [own]. */
@Composable
internal fun sortChip(sort: ListSort, @StringRes own: Int): String = sort.key?.let { sortChip(it.words, it.ways, sort.reversed) } ?: stringResource(own)

/** The sort sheet's "Order": the key's two [ways] round, the chosen one [reversed] or not. */
@Composable
internal fun SortWayChips(ways: SortWays, reversed: Boolean, onReversed: (Boolean) -> Unit) {
    SectionTitle(stringResource(R.string.lib_sort_way), Modifier.padding(top = 16.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.selectableGroup()) {
        listOf(false to ways.first, true to ways.other).forEach { (way, words) ->
            LyrifyChip(selected = reversed == way, onClick = { onReversed(way) }, role = Role.RadioButton) { Text(stringResource(words), maxLines = 1) }
        }
    }
}

/**
 * A song page's sort sheet: its own order ([own], when [list] has one) first, then every key, and the way round of the
 * key chosen. A key chosen anew starts its first way round.
 */
@Composable
fun ListSortSheet(list: SongList, sort: ListSort, @StringRes own: Int, onSort: (ListSort) -> Unit, onDismiss: () -> Unit) {
    val keys = (if (list.own) listOf<SortSongs?>(null) else emptyList()) + SortSongs.entries
    SortSheet(
        stringResource(R.string.lib_sort),
        keys.map { key -> key to stringResource(key?.words ?: own) },
        sort.key,
        onSelect = { key -> if (key != sort.key) onSort(ListSort(key)) },
        onDismiss = onDismiss,
    ) {
        sort.key?.let { key -> SortWayChips(key.ways, sort.reversed) { reversed -> onSort(sort.copy(reversed = reversed)) } }
    }
}

/** A song page's order, remembered for its kind of page in settings.json, and how its sheet changes it. */
@Composable
fun rememberListSort(ui: LibraryUi, list: SongList): Pair<ListSort, (ListSort) -> Unit> {
    val change = rememberSettingsChange()
    return ui.settings.library.sortOf(list) to { sort -> change { it.copy(library = it.library.copy(listSorts = it.library.listSorts + (list to sort))) } }
}

/**
 * What [sort] gives once [plays] are ready (null: still read), worked out again when they or [keys] change; the order
 * shown before meanwhile, nothing before the first. For the Albums and Artists tabs, sorted where they are drawn.
 */
@Composable
fun <T> rememberSorted(plays: Map<String, SongPlays>?, vararg keys: Any?, sort: (Map<String, SongPlays>) -> List<T>): List<T>? {
    val sorted by produceState(remember { plays?.let(sort) }, plays, *keys) { plays?.let { value = sort(it) } }
    return sorted
}

/**
 * Each song's plays by its path while [needed] (a list sorted by plays), read again on each play logged; null until the
 * first read is done. None otherwise, so the other lists never wait for them.
 */
@Composable
fun rememberPlays(needed: Boolean): Map<String, SongPlays>? {
    val context = LocalContext.current
    val written by remember(context) { StatsGraph.counter(context).written }.collectAsState()
    val read by produceState<Map<String, SongPlays>?>(null, needed, written) { if (needed) value = Lyrify.plays(context).everySong() }
    return if (needed) read else emptyMap()
}

// Headings: the Songs tab heads each run of songs by its sort.

/** When a song was added, as the Songs tab groups it in "Recently added". */
enum class AddedWhen { TODAY, THIS_WEEK, THIS_MONTH, EARLIER }

/**
 * A heading over the Songs tab's rows: the first letter by title or file name, the artist, the album, the year, when it
 * was added or last played; none by plays or length.
 */
sealed interface ListSection {
    /** A letter, or "#" for digits and nothing ([nameLetter]). */
    data class Letter(val letter: String) : ListSection

    /** An artist or album name; null for songs without one. */
    data class Name(val name: String?) : ListSection

    data class Added(val time: AddedWhen) : ListSection

    /** A year; null for songs without one. */
    data class Year(val year: Int?) : ListSection

    /** When last played; null for songs never played. */
    data class Played(val time: AddedWhen?) : ListSection

    /** No heading: the plays and the lengths run on without one. */
    data object None : ListSection
}

/** [song]'s heading under [sort], with "Recently added" and "Last played" by [now]'s day in [zone], the plays from [plays]. */
fun listSection(song: Song, sort: SortSongs, settings: LibrarySettings, now: Long, zone: ZoneId, plays: Map<String, SongPlays> = emptyMap()): ListSection = when (sort) {
    SortSongs.TITLE -> ListSection.Letter(nameLetter(displayTitle(song, settings)))
    SortSongs.FILE_NAME -> ListSection.Letter(nameLetter(song.fileName))
    SortSongs.ARTIST -> ListSection.Name(song.artist?.trim()?.takeIf(String::isNotEmpty))
    SortSongs.ALBUM -> ListSection.Name(song.album?.trim()?.takeIf(String::isNotEmpty))
    SortSongs.YEAR -> ListSection.Year(song.year)
    SortSongs.RECENTLY_ADDED -> ListSection.Added(song.addedAt?.let { sinceWhen(it, now, zone) } ?: AddedWhen.EARLIER)
    SortSongs.LAST_PLAYED -> ListSection.Played(plays[song.path]?.lastPlayedAt?.let { sinceWhen(it, now, zone) })
    SortSongs.PLAY_COUNT, SortSongs.DURATION -> ListSection.None
}

/** Whether [at] is today, this week, this month (the last 30 days) or earlier, by [now]'s day in [zone]. */
private fun sinceWhen(at: Long, now: Long, zone: ZoneId): AddedWhen {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> AddedWhen.TODAY
        !day.isBefore(today.minusDays(WEEK_DAYS - 1)) -> AddedWhen.THIS_WEEK
        !day.isBefore(today.minusDays(RECENTLY_ADDED_DAYS - 1)) -> AddedWhen.THIS_MONTH
        else -> AddedWhen.EARLIER
    }
}

/** "This week": today and the six days before. */
private const val WEEK_DAYS = 7L

/** One of TalkBack's moves on a row in a custom order: its [words], and the place the song goes to. */
data class RowMove(@StringRes val words: Int, val to: Int)

/** TalkBack's moves for the row at [place] of [size]: Move up unless it is the first, Move down unless it is the last. */
fun rowMoves(place: Int, size: Int): List<RowMove> = listOfNotNull(
    RowMove(R.string.lib_move_up, place - 1).takeIf { place > 0 },
    RowMove(R.string.lib_move_down, place + 1).takeIf { place < size - 1 },
)

/**
 * How far a list scrolls this frame while a held row is near its edges: back while the row's [top] is within [edge] px
 * of the list's [start], on while its [bottom] is within [edge] of its [end], faster the closer, up to [most] px. Zero
 * anywhere else.
 */
fun edgeScroll(top: Float, bottom: Float, start: Float, end: Float, edge: Float, most: Float): Float = when {
    top < start + edge -> -most * ((start + edge - top) / edge).coerceAtMost(1f)
    bottom > end - edge -> most * ((bottom - (end - edge)) / edge).coerceAtMost(1f)
    else -> 0f
}

/**
 * A stored list in its custom order (a playlist's songs, Favourites' songs) as its page shows it. Each entry has a key of
 * its own that stays with it wherever it moves ([of]), so a song there twice never swaps rows with its copy. A move shows
 * at once ([arrange]) and is written in turn ([write]: one at a time, each the whole order shown, so what is stored ends
 * as what shows, whatever was written or removed meanwhile); it shows over the stored list until that changes, and one
 * that could not be stored gives way to the stored list again.
 */
@Stable
class OrderedEntries {
    private var stored: List<SongRef>? = null
    private var entries: List<SongRef> = emptyList()
    private var keys: List<Long> = emptyList()
    private var next = 0L
    private val writes = Mutex()

    /** Changes with each move shown or given up, so the page reading [of] shows it. */
    var version: Int by mutableIntStateOf(0)
        private set

    /** The entries to show while the stored list is [now] (a move not stored yet shows over it), and each one's key. */
    fun of(now: List<SongRef>): Pair<List<SongRef>, List<Long>> {
        if (now != stored) {
            keys = carried(entries, keys, now) { next++ }
            entries = now
            stored = now
        }
        return entries to keys
    }

    /** The entries at [places] (of those [of] gave), in their new order, take that order; it shows at once. Gives the songs to [write]. */
    fun arrange(places: List<Int>): List<SongRef> {
        val order = places.map { entries[it] to keys[it] }
        val now = arranged(entries.zip(keys), order) { it.first.path }
        entries = now.map { it.first }
        keys = now.map { it.second }
        version++
        return order.map { it.first }
    }

    /** Stores [order] once the writes before it are done, through [store] (true once stored); one not stored gives way to the stored list. */
    suspend fun write(order: List<SongRef>, store: suspend (List<SongRef>) -> Boolean): Boolean = writes.withLock {
        store(order).also { stored -> if (!stored) forget() }
    }

    private fun forget() {
        val now = stored ?: return
        keys = carried(entries, keys, now) { next++ }
        entries = now
        version++
    }
}

/** Keys for [now], carried over from [before] and its [keys]: each copy of a song keeps the key of the same copy before (the first the first's), and an entry new to the list gets [fresh]. */
fun carried(before: List<SongRef>, keys: List<Long>, now: List<SongRef>, fresh: () -> Long): List<Long> {
    val copies = HashMap<String, ArrayDeque<Long>>()
    before.forEachIndexed { at, ref -> copies.getOrPut(ref.path) { ArrayDeque() }.addLast(keys[at]) }
    return now.map { ref -> copies[ref.path]?.removeFirstOrNull() ?: fresh() }
}

/**
 * A song of a stored list as its page lists it (a playlist's songs, Favourites'): its [place] among the entries
 * [OrderedEntries.of] gave and its row's [key], that entry's. The key goes with the song wherever a sort puts it, so
 * a page's rows and their keys are always one list ([listedEntries]).
 */
data class ListedEntry(val place: Int, val song: Song, val key: String)

/**
 * The songs [found] among a stored list's entries (each with its place there), each with its row's key: its entry's in
 * [keys], the keys [OrderedEntries.of] gave with those entries. A page sorted by a key shows the songs sorted before
 * while the sort of a new list is worked out ([rememberInOrder]); those rows keep the keys they came with, never the
 * keys of the list now (KJB 3.1 crashed as a favourite was taken out while Favourites was sorted by a key: the songs
 * sorted before were keyed by place among the entries left, one place past them). A place past [keys] (never: the page
 * takes both from one list) keeps its song with a key of its own, never a crash.
 */
fun listedEntries(found: List<Pair<Int, Song>>, keys: List<Long>): List<ListedEntry> =
    found.map { (at, song) -> ListedEntry(at, song, keys.getOrNull(at)?.let { "entry $it" } ?: "place $at") }

/**
 * The place now of the song at [at] of the rows [shown]: its entry's place, found by its key, among [now], the page's
 * songs as the stored list is now; null once its entry has left. Rows sorted before (while the sort of a new list is
 * worked out, [rememberInOrder]) carry the places of the list then; a song taken out moves every later one up a place,
 * so the old place may be another song's now.
 */
fun placeNow(shown: List<ListedEntry>, now: List<ListedEntry>, at: Int): Int? =
    shown.getOrNull(at)?.let { row -> now.firstOrNull { it.key == row.key }?.place }

/**
 * A list of songs being put in another order by a row's handle, or by TalkBack ([move]). While a row is [held] the list
 * shows its songs in the drag's order ([shown]): the held row follows the finger, [offset] px from its place, and takes a
 * neighbour's place each time it passes half of it ([reorderStep], the queue's), with a [tick]. Each change of order
 * first [keepPlace]s the list, which would otherwise keep its first row on screen in view and jump. Let go ([drop]), the
 * row settles into its place and [store] gets the list's new order (places in the list shown), which the page shows from
 * then on ([OrderedEntries]).
 */
@Stable
class SongReorder(
    private val tick: () -> Unit = {},
    private val keepPlace: () -> Unit = {},
    private val store: (order: List<Int>) -> Unit = {},
) {
    /** The list as it was when the drag started: [order] and [held] are places in it. */
    private var base: List<Song> by mutableStateOf(emptyList())

    private var order: List<Int>? by mutableStateOf(null)

    /** The held row, by its place in the list when the drag started; null while none is. */
    var held: Int? by mutableStateOf(null)
        private set

    /** The held row's key (its entry's, which a move keeps): it stays lifted until it has settled. */
    var heldKey: Any? by mutableStateOf(null)
        private set

    var offset: Float by mutableFloatStateOf(0f)
        private set

    /** The places of [songs] in the order shown: a drag's, while [songs] is still the list it started on, else theirs. */
    fun shown(songs: List<Song>): List<Int> = order?.takeIf { base == songs } ?: songs.indices.toList()

    /** The handle of the row at [place] of [songs], keyed [key], is held. */
    fun start(songs: List<Song>, place: Int, key: Any) {
        base = songs
        order = songs.indices.toList()
        held = place
        heldKey = key
        offset = 0f
    }

    /** The held row moved [dy] px with the finger, or the list scrolled that far under it; each row is [row] px tall. */
    fun drag(dy: Float, row: Float) {
        val place = held ?: return
        val now = order ?: return
        val at = now.indexOf(place)
        val (to, left) = reorderStep(at, now.size, offset + dy, row)
        if (to != at) {
            keepPlace()
            order = now.toMutableList().apply { add(to, removeAt(at)) }
            repeat(abs(to - at)) { tick() }
        }
        offset = left
    }

    /**
     * The held row let go in [songs], the list as it is now: the list's new order (places in [songs]), stored, or null
     * when the row is back at its place or the list changed under it.
     */
    fun drop(songs: List<Song>): List<Int>? {
        val place = held ?: return null
        held = null
        val now = order?.takeIf { it.indexOf(place) != place && base == songs }
        order = null
        now?.let(store)
        return now
    }

    /** The row let go eases into its place, then lands. */
    suspend fun settle(spec: AnimationSpec<Float>) {
        val key = heldKey
        try {
            animate(offset, 0f, animationSpec = spec) { value, _ -> if (held == null && heldKey == key) offset = value }
        } finally {
            if (held == null && heldKey == key) {
                offset = 0f
                heldKey = null
            }
        }
    }

    /** TalkBack's Move up or Move down: the song at [from] of [size] goes to [to], stored at once. */
    fun move(from: Int, to: Int, size: Int) = store((0 until size).toMutableList().apply { add(to, removeAt(from)) })
}

/**
 * A page's [SongReorder] for its list [state] of [entries]: a tick per place passed and, while a row is held, the list
 * scrolling near its edges ([edgeStep]) with the row staying under the finger. A new order shows at once: [places] gives
 * each row's place among the entries, read when the row is let go, before anything can change them; then [store] stores
 * the songs shown in their new order, in turn, and gives what to say when it could not.
 */
@Composable
fun rememberSongReorder(state: LazyListState, entries: OrderedEntries, places: () -> List<Int>, store: suspend (order: List<SongRef>) -> Outcome?): SongReorder {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val rows by rememberUpdatedState(places)
    val latest by rememberUpdatedState(store)
    val reorder = remember(state, haptics, entries) {
        SongReorder(
            tick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            keepPlace = { state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
            store = { order ->
                val at = rows()
                val songs = entries.arrange(order.map { at[it] })
                scope.launch {
                    var failed: Outcome? = null
                    entries.write(songs) { shown -> latest(shown).also { failed = it } == null }
                    failed?.let { toasts.say(resources, it) }
                }
            },
        )
    }
    val density = LocalDensity.current
    val holding = reorder.held != null
    LaunchedEffect(reorder, holding) {
        if (!holding) return@LaunchedEffect
        val edge = with(density) { SCROLL_EDGE.toPx() }
        val fastest = with(density) { SCROLL_FASTEST.toPx() }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            val info = state.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == reorder.heldKey } ?: continue
            val step = edgeStep(info, row, reorder.offset, edge, fastest * seconds)
            if (step == 0f) continue
            val scrolled = try {
                state.scrollBy(step)
            } catch (e: CancellationException) {
                // A move's keepPlace stops a scroll under way; the loop goes on unless it is the one stopped.
                coroutineContext.ensureActive()
                0f
            }
            if (scrolled != 0f) reorder.drag(scrolled, row.size.toFloat())
        }
    }
    return reorder
}

/**
 * How far the list of [info] scrolls this frame for its held [row], [offset] px from its place ([edgeScroll]): near the
 * edges of the room the bars leave it. The rows' offsets start where the list's content does, after its padding at the
 * start (the top bar: Compose places them that far down, [LazyListLayoutInfo.viewportStartOffset] being minus it), so
 * that room runs from 0 to the end of the viewport less the padding at the end (the Now bar and the dock).
 */
fun edgeStep(info: LazyListLayoutInfo, row: LazyListItemInfo, offset: Float, edge: Float, most: Float): Float {
    val top = row.offset + offset
    return edgeScroll(top, top + row.size, 0f, (info.viewportEndOffset - info.afterContentPadding).toFloat(), edge, most)
}

/**
 * A row's drag handle (a 48 dp target at its end, before "⋯", clear of the system's back gesture): an upright drag on it
 * moves the song, at [place] of [songs], at once, no long press, through [reorder]; a sideways one is left to the tab
 * pages. [row] is the row's height in px. TalkBack has the row's Move up and Move down instead, so the handle is no stop
 * of its own.
 */
@Composable
fun DragHandle(reorder: SongReorder, songs: List<Song>, place: Int, key: Any, row: () -> Float) {
    val scope = rememberCoroutineScope()
    val list by rememberUpdatedState(songs)
    val at by rememberUpdatedState(place)
    val reduced = LocalReducedMotion.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .testTag(HANDLE_TAG)
            .clearAndSetSemantics {}
            .pointerInput(reorder, key) {
                val letGo = {
                    reorder.drop(list)
                    scope.launch { reorder.settle(motion(tween(Motion.SHORT_MS, easing = Motion.Standard), reduced)) }
                }
                detectVerticalDragGestures(
                    onDragStart = { reorder.start(list, at, key) },
                    onDragEnd = { letGo() },
                    onDragCancel = { letGo() },
                ) { change, amount ->
                    change.consume()
                    reorder.drag(amount, row())
                }
            },
    ) {
        Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/**
 * How a row of a list being reordered moves: held ([lift] going to 1), it follows the finger above the others, a little
 * larger, on a [raised] fill with a shadow; the others slide out of its way, a held one never (it is under the finger).
 */
fun LazyItemScope.reorderMotion(reorder: SongReorder, key: Any, lift: State<Float>, raised: Color, reduced: Boolean, density: Density): Modifier {
    val held = reorder.heldKey == key
    val shadow = with(density) { LIFT.toPx() }
    val placement = if (held || reduced) null else spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)
    return Modifier
        .animateItem(fadeInSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.FADE_IN_MS), placementSpec = placement, fadeOutSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.FADE_OUT_MS))
        .zIndex(if (held) 1f else 0f)
        .graphicsLayer {
            if (held) translationY = reorder.offset
            val up = lift.value
            scaleX = 1f + LIFT_SCALE * up
            scaleY = 1f + LIFT_SCALE * up
            shadowElevation = shadow * up
            shape = RowFillShape
        }
        .drawBehind {
            val up = lift.value
            if (up > 0f) drawOutline(RowFillShape.createOutline(size, layoutDirection, this), raised, alpha = up)
        }
}

// Row fills: a highlighted row's, and a held row's lift

/**
 * Where a highlighted row's fill (a selected song, the queue's song here) goes in a row of [size]: its own rounded
 * rectangle, [ROW_FILL_INSET] in from the sides and [ROW_FILL_GAP] from the top and the bottom, corners [ROW_FILL_CORNER],
 * so two highlighted neighbours read as separate rows (user feedback 2026-11-13). The row and its touch target keep their size.
 */
fun rowFill(size: Size, density: Density): RoundRect = with(density) {
    val inset = ROW_FILL_INSET.toPx()
    val gap = ROW_FILL_GAP.toPx()
    RoundRect(inset, gap, size.width - inset, size.height - gap, CornerRadius(ROW_FILL_CORNER.toPx()))
}

/** [rowFill] as a shape: the queue's song here, and a held row's lift and shadow. */
val RowFillShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rounded(rowFill(size, density))
}

/** Draws a highlighted row's fill ([rowFill]); a clear [color] draws nothing. */
fun DrawScope.drawRowFill(color: Color) {
    if (color.alpha > 0f) drawOutline(Outline.Rounded(rowFill(size, this)), color)
}

val ROW_FILL_INSET = 8.dp
val ROW_FILL_GAP = 2.dp
val ROW_FILL_CORNER = 12.dp

/** The round check's test tag: TalkBack has it on the row, so device tests find it by this. */
const val CHECK_TAG: String = "song check"

/** A held row is lifted: a little larger, with a shadow (the queue's rows too). */
const val LIFT_SCALE: Float = 0.03f
val LIFT = 8.dp

/** Within this of a list's edge a held row scrolls it, at most this fast a second (deepest in the edge). */
private val SCROLL_EDGE = 64.dp
private val SCROLL_FASTEST = 900.dp

/** The handle's test tag: it is no TalkBack stop, so device tests find it by this. */
const val HANDLE_TAG: String = "song handle"
