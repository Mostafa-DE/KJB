package app.lyrify.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.displayTitle
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueItem
import app.lyrify.player.SourceKind
import app.lyrify.player.Undo
import app.lyrify.player.queueLabel
import app.lyrify.player.queueLabels
import app.lyrify.player.sourceLabel
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.DockTab
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Queues (P1, QF3): the Queue sheet from the player, the Queues page (the queue playing, then Recent; no Saved part
// since the user's first day with 3.0.0), and a queue's own page. The sheet opens with its song in view, a queue's page
// at its top (3.1); a queue's list reorders by dragging its handle and loses a song by a swipe to the start; "Remove
// from queue" is the visible twin.

/** Queues (the queue playing, then Recent), or one queue's page; [root]: Queues as the bar's own item (KJB 3.1). */
@Composable
fun QueuePage(page: Page.QueuePages, root: Boolean = false) {
    when (page) {
        Page.Queues -> QueuesPage(root)
        is Page.Queue -> OneQueuePage(page.id)
    }
}

/**
 * The first row the Queue sheet's list shows as it opens, so the song at [index] is in view with the song before it
 * above it; while it is among the first two, the list opens at its top, its [head] rows (those above the songs)
 * showing.
 */
fun firstShown(index: Int, head: Int): Int = if (index <= 1) 0 else head + index - 1

/** A queue page's list: its head (row 0), then Resume and Play from start (row 1, pinned), then its songs. */
private const val BUTTONS_ROW = 1

/**
 * How a queue page's head shows with its list at [firstIndex] (the first row shown) and [firstOffset] px into it:
 * [folded] once the head (name, size, place) has scrolled away, the top bar naming the queue instead; [edge] while
 * songs run under the pinned buttons, which then show a hairline under them.
 */
data class QueueHeadFold(val folded: Boolean, val edge: Boolean)

fun queueHeadFold(firstIndex: Int, firstOffset: Int): QueueHeadFold =
    QueueHeadFold(folded = firstIndex >= BUTTONS_ROW, edge = firstIndex > BUTTONS_ROW || (firstIndex == BUTTONS_ROW && firstOffset > 0))

/** "Song 4 of 12 · 1:42": where a queue is, for the queue playing at the live [ui] position. */
private fun place(context: Context, queue: Queue, ui: PlayerUi?): String {
    val live = ui?.takeIf { it.queue.id == queue.id }
    val index = live?.queue?.index ?: queue.index
    val position = live?.positionAt(SystemClock.elapsedRealtime()) ?: queue.positionMs
    return context.getString(R.string.queue_place, index + 1, queue.items.size, clock(position))
}

/** A queue's name without its kind, the default name of a playlist saved from it ("Harbour Glass"). */
private fun plainName(context: Context, queue: Queue): String =
    queue.name ?: queue.source.name.takeIf { it.isNotBlank() && queue.source.kind != SourceKind.PICKED } ?: sourceLabel(context, queue.source, queue.items.size)

/**
 * The Queues page: the queue playing, then Recent ([QueuesList]); each opens the queue's page. As the bar's item
 * ([root]) it has the bar's header and no Back, and its icon tapped again goes to the top.
 */
@Composable
private fun QueuesPage(root: Boolean) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val book by connection.book.collectAsStateWithLifecycle()
    val ui by connection.ui.collectAsStateWithLifecycle()
    val library by connection.library.collectAsStateWithLifecycle()
    val names = rememberNames()
    val list = rememberLazyListState()
    if (root) ReselectToTop(nav.state.reselectsOf(DockTab.QUEUES)) { list.animateScrollToItem(0) }
    PageFrame(
        topBar = { under, backdrop ->
            if (root) {
                MainTopBar(DockTab.QUEUES, under, backdrop)
            } else {
                LyrifyTopBar(
                    title = { Text(stringResource(R.string.queues_title)) },
                    under = under,
                    backdrop = backdrop,
                    navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
                )
            }
        },
    ) { padding ->
        QueuesList(book, ui, library, names, padding, list) { nav.open(Page.Queue(it)) }
    }
}

/**
 * The Queues page's list: "Playing now" with the queue playing ([PlayingCard]), then Recent, every other queue
 * ([QueueBook.others]), so no queue shows twice. A part with nothing in it is left out, and with no queue at all the
 * page shows one empty state. [onOpen] opens a queue's page by its id.
 */
@Composable
internal fun QueuesList(
    book: QueueBook,
    ui: PlayerUi?,
    library: Library?,
    names: LibrarySettings,
    padding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
    onOpen: (String) -> Unit,
) {
    val playing = book.current
    val others = book.others
    if (playing == null && others.isEmpty()) {
        // In a scroller of its own, though nothing scrolls: a pull down still reaches the shell, which brings back a bar
        // hidden while scrolling (KJB 3.1); on the bar's own Queues nothing else would.
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EmptyState(EmptyArt.Icon(R.drawable.ic_queue_music), stringResource(R.string.queues_none), Modifier.padding(top = padding.calculateTopPadding()), text = stringResource(R.string.queues_none_text))
        }
        return
    }
    LazyColumn(state = state, contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp)) {
        if (playing != null) {
            item(key = "playing title") { SectionTitle(stringResource(R.string.queues_playing), Modifier.padding(vertical = 8.dp)) }
            item(key = "playing") { PlayingCard(playing, book, ui, library, names) { onOpen(playing.id) } }
        }
        if (others.isNotEmpty()) {
            item(key = "recent title") { SectionTitle(stringResource(R.string.queues_recent), Modifier.padding(top = if (playing != null) 20.dp else 8.dp, bottom = 8.dp)) }
            items(others, key = { it.id }) { queue -> QueueLine(queue, book) { onOpen(queue.id) } }
        }
    }
}

/**
 * The queue playing, first on the Queues page, on the fill its song has in a queue's list: that song's cover, the
 * queue's name, the song, and where it is ("12 of 48"), live while it plays. One TalkBack stop, which says "Playing now"
 * while the song plays (the equalizer; paused, the chevron); a tap opens the queue's page at the song.
 */
@Composable
private fun PlayingCard(queue: Queue, book: QueueBook, ui: PlayerUi?, library: Library?, names: LibrarySettings, onOpen: () -> Unit) {
    val context = LocalContext.current
    val brand = LocalBrandColors.current
    val live = ui?.takeIf { it.queue.id == queue.id }
    val item = live?.item ?: queue.current
    val title = playingTitle(item, live, library, names)
    val name = queueLabel(context, book, queue)
    val at = stringResource(R.string.queue_position, (live?.queue?.index ?: queue.index) + 1, queue.items.size)
    val on = live?.playing == true
    val playingNow = stringResource(R.string.queues_playing)
    val fill = brand.highlightContainer.copy(alpha = CURRENT_ALPHA).compositeOver(MaterialTheme.colorScheme.background)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, title, at, playingNow.takeIf { on }).joinToString(", ") }
            .heightIn(min = 80.dp)
            .padding(12.dp),
    ) {
        SongCover(item?.song?.path?.let(::SongArt), size = 56.dp, shape = RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = brand.onHighlightContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = brand.onHighlightContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(at, style = MaterialTheme.typography.bodySmall.tabular(), color = brand.onHighlightContainer, maxLines = 1)
        }
        Icon(painterResource(if (on) R.drawable.ic_graphic_eq else R.drawable.ic_chevron_right), contentDescription = null, tint = brand.onHighlightContainer)
    }
}

/**
 * The song [PlayingCard] names, [item]: as the player names it while its queue plays ([PlayerUi.named], a file another
 * app opened by its own tags), else the library's, else the file's name.
 */
internal fun playingTitle(item: QueueItem?, live: PlayerUi?, library: Library?, names: LibrarySettings): String =
    (live?.named ?: item?.let { library?.resolver?.find(it.song) })?.let { displayTitle(it, names) } ?: item?.song?.path?.substringAfterLast('/').orEmpty()

/** A queue's row: its current song's cover, its name, its size and place. */
@Composable
private fun QueueLine(queue: Queue, book: QueueBook, onOpen: () -> Unit) {
    val context = LocalContext.current
    val name = queueLabel(context, book, queue)
    val size = pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size)
    val line = stringResource(R.string.queue_line, size, place(context, queue, ui = null))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $line" }
            .heightIn(min = 72.dp)
            .padding(8.dp),
    ) {
        SongCover(queue.current?.song?.path?.let(::SongArt), size = 48.dp, shape = CircleShape)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A queue's own page: its name and place, then Resume (or Play/Pause while it plays) and Play from start, pinned under
 * the top bar while the songs scroll ([QueueButtons]), then its songs. It opens at its top, on those buttons, the song
 * here marked further down (3.1: it opened at that song, the buttons scrolled away); once the head has scrolled away
 * the top bar names the queue ([queueHeadFold]). Delete sits in the top bar beside Add to; it goes back to where the
 * page was opened from, and its toast's Undo brings the queue back and opens it again.
 */
@Composable
private fun OneQueuePage(id: String) {
    // The rows sit on the page colour (.qpage .qin).
    val pageSurface = MaterialTheme.colorScheme.background
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val book by connection.book.collectAsStateWithLifecycle()
    val ui by connection.ui.collectAsStateWithLifecycle()
    val library by connection.library.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // Deleted here: the page keeps drawing it while it leaves.
    var leaving by remember { mutableStateOf<Queue?>(null) }
    val queue = book.queue(id) ?: leaving
    val names = rememberNames()
    val reorder = remember { Reorder() }
    SettleReorder(queue?.items, reorder)
    var dialog by remember { mutableStateOf<QueueDialog?>(null) }
    val sheets = rememberSongSheets()
    val list = rememberLazyListState()
    val fold by remember(list) { derivedStateOf { queueHeadFold(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) } }
    val reduced = LocalReducedMotion.current
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = {
                    AnimatedVisibility(queue != null && fold.folded, enter = fadeIn(motion(tween(Motion.QUICK_MS), reduced)), exit = fadeOut(motion(tween(Motion.QUICK_MS), reduced))) {
                        queue?.let { Text(queueLabel(context, book, it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
                actions = {
                    if (queue != null) {
                        QueueActions(
                            queue,
                            book,
                            onAddAll = { sheets.show(OpenSheet.AddTo(queue.items.map { it.song }, queue.source, plainName(context, queue))) },
                            onRename = { dialog = QueueDialog.RENAME },
                            onSave = { dialog = QueueDialog.SAVE_AS_PLAYLIST },
                            onDelete = { dialog = QueueDialog.DELETE },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (queue == null) {
            EmptyState(EmptyArt.Icon(R.drawable.ic_queue_music), stringResource(R.string.queue_gone), Modifier.padding(top = padding.calculateTopPadding()))
            return@PageFrame
        }
        val playing = id == book.currentId
        // Under the top bar, not behind it: the pinned buttons sit right under the bar, nothing showing above them.
        LazyColumn(Modifier.padding(top = padding.calculateTopPadding()), state = list, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)) {
            item(key = "head", contentType = "head") {
                Column(Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (playing) StatusPill(stringResource(R.string.queues_playing), tone = null, icon = R.drawable.ic_graphic_eq)
                    Text(queueLabel(context, book, queue), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(place(context, queue, ui), style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            stickyHeader(key = "buttons", contentType = "buttons") {
                // A hairline under the pinned buttons only while songs run under them, as Onboarding's pinned bar.
                val line by animateColorAsState(if (fold.edge) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, motion(tween(Motion.FADE_IN_MS), reduced), label = "queue buttons edge")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(pageSurface)
                        .drawBehind { drawRect(line, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
                        .padding(horizontal = ScreenPadding, vertical = 10.dp),
                ) {
                    QueueButtons(
                        playing = playing,
                        on = playing && ui?.playing == true,
                        onResume = { if (playing) connection.playPause() else connection.resume(id) },
                        onFromStart = { connection.playFromStart(id) },
                    )
                }
            }
            queueSongs(queue, playing, library, names, connection, reorder, pageSurface, onRemoved = { undo ->
                scope.launch { if (toasts.show(Toast(resources.getString(R.string.queue_removed), R.drawable.ic_playlist_remove, action = undo?.let { resources.getString(R.string.player_undo) }))) undo?.undo() }
            })
        }
    }
    QueueDialogs(dialog, queue, book, connection, onDone = { dialog = null }, onDeleting = { leaving = it })
    SongSheetsHost(sheets)
}

/**
 * A queue page's Resume (Play or Pause while the queue plays) and Play from start, as a list header's Play and Shuffle
 * ([ListHeader]): on a narrow screen or at a large font, Play from start drops its word first, then Resume
 * ([headerFit]), so both stay on one line; TalkBack hears each by its name.
 */
@Composable
internal fun QueueButtons(playing: Boolean, on: Boolean, onResume: () -> Unit, onFromStart: () -> Unit) {
    val resume = stringResource(
        when {
            !playing -> R.string.queue_resume
            on -> R.string.player_pause
            else -> R.string.player_play
        },
    )
    val resumeIcon = if (on) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill
    val fromStart = stringResource(R.string.queue_from_start)
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun measure(slot: String, content: @Composable () -> Unit) = subcompose(slot, content).map { it.measure(loose) }
        fun width(parts: Map<Boolean, List<Placeable>>) = PartWidth(parts.getValue(true).sumOf { it.width }, parts.getValue(false).sumOf { it.width })
        val gap = 8.dp.roundToPx()
        val resumes = listOf(true, false).associateWith { label ->
            measure("resume $label") { HeaderButton(resumeIcon, resume, enabled = true, primary = true, label = label, onClick = onResume) }
        }
        val starts = listOf(true, false).associateWith { label ->
            measure("start $label") { HeaderButton(R.drawable.ic_replay, fromStart, enabled = true, primary = false, label = label, onClick = onFromStart) }
        }
        // Play from start stands where a list header's Shuffle does: the first to drop its word.
        val fit = headerFit(constraints.maxWidth, fixed = gap, shuffle = width(starts), play = width(resumes), chip = null)
        val first = resumes.getValue(fit.playLabel)
        val second = starts.getValue(fit.shuffleLabel)
        val height = (first + second).maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            first.forEach { it.placeRelative(0, (height - it.height) / 2) }
            val x = first.sumOf { it.width } + gap
            second.forEach { it.placeRelative(x, (height - it.height) / 2) }
        }
    }
}

/**
 * A queue page's top bar actions: Add all (the Add to sheet, L1's), Delete beside it (not for the queue playing), and
 * "⋯" with Rename and Save as playlist.
 */
@Composable
private fun QueueActions(
    queue: Queue,
    book: QueueBook,
    onAddAll: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ChromeIconButton(R.drawable.ic_playlist_add, stringResource(R.string.queue_add_all, pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size)), onAddAll)
    if (queueDeletable(queue, book)) ChromeIconButton(R.drawable.ic_delete, stringResource(R.string.queue_delete_button), onDelete)
    Box {
        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.queue_more), { menu = true })
        LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
            MenuRow(stringResource(R.string.queue_rename), R.drawable.ic_edit, {
                menu = false
                onRename()
            })
            MenuRow(stringResource(R.string.queue_save_playlist), R.drawable.ic_playlist_add, {
                menu = false
                onSave()
            })
        }
    }
}

/** Whether a queue's page offers Delete: every queue but the one playing, which is never deleted. */
fun queueDeletable(queue: Queue, book: QueueBook): Boolean = queue.id != book.currentId && book.queue(queue.id) != null

private enum class QueueDialog { RENAME, DELETE, SAVE_AS_PLAYLIST }

/**
 * Rename, Delete (asks first, removes only the queue) and Save as playlist. Delete leaves the page, [onDeleting] the
 * queue it keeps drawing as it goes; its toast runs in the activity's scope, so it and its Undo outlive the dialog and
 * the page.
 */
@Composable
private fun QueueDialogs(dialog: QueueDialog?, queue: Queue?, book: QueueBook, connection: PlayerConnection, onDone: () -> Unit, onDeleting: (Queue) -> Unit) {
    if (dialog == null || queue == null) return
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    when (dialog) {
        QueueDialog.RENAME -> NameDialog(stringResource(R.string.queue_rename_title), stringResource(R.string.queue_name_field), queueLabel(context, book, queue), stringResource(R.string.queue_rename), R.drawable.ic_edit, onDone) { name ->
            connection.rename(queue.id, name)
            scope.launch { toasts.show(Toast(resources.getString(R.string.queue_renamed), R.drawable.ic_edit)) }
        }
        QueueDialog.SAVE_AS_PLAYLIST -> SaveAsPlaylistDialog(queue, connection, onDone)
        QueueDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.queue_delete_title),
            text = stringResource(R.string.queue_delete_text),
            confirm = stringResource(R.string.queue_delete),
            dismiss = stringResource(R.string.queue_cancel),
            onConfirm = {
                onDone()
                connection.delete(queue.id)?.let { undo ->
                    onDeleting(queue)
                    nav.back()
                    lifecycle.launch {
                        if (toasts.show(Toast(resources.getString(R.string.queue_deleted), R.drawable.ic_delete, action = resources.getString(R.string.player_undo)))) {
                            undo.undo()
                            nav.open(Page.Queue(queue.id))
                        }
                    }
                }
            },
            onDismiss = onDone,
        )
    }
}

@Composable
private fun SaveAsPlaylistDialog(queue: Queue, connection: PlayerConnection, onDone: () -> Unit) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    NameDialog(stringResource(R.string.queue_save_playlist), stringResource(R.string.queue_playlist_field), plainName(context, queue), stringResource(R.string.queue_save), R.drawable.ic_playlist_add, onDone) { name ->
        connection.saveAsPlaylist(queue.id, name) { saved ->
            scope.launch {
                if (saved != null) toasts.show(Toast(resources.getString(R.string.queue_playlist_saved, saved), R.drawable.ic_playlist_add))
                else toasts.show(Toast(resources.getString(R.string.queue_playlist_failed), R.drawable.ic_sync_problem, Tone.ERROR))
            }
        }
    }
}

/** A dialog with one name field, starting at [start]; a blank name cannot be confirmed. */
@Composable
private fun NameDialog(title: String, field: String, start: String, confirm: String, icon: Int, onDismiss: () -> Unit, onName: (String) -> Unit) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(nameField(start)) }
    ConfirmDialog(
        title = title,
        confirm = confirm,
        dismiss = stringResource(R.string.queue_cancel),
        onConfirm = {
            onDismiss()
            onName(name.text.trim())
        },
        onDismiss = onDismiss,
        icon = icon,
        destructive = false,
        confirmEnabled = name.text.isNotBlank(),
        body = { NameField(name, { name = it }, field) },
    )
}

/**
 * The Queue sheet, from the player: the queue's name and where it is ("12 of 48") with "All queues", then the list,
 * opening with the current song marked and in view; at the foot ([QueueSheetFoot]), pinned whatever the list's scroll,
 * "Save as playlist" and the other queues ([OtherQueues], as the Queues page lists them; each opens that queue's page,
 * and none shows without one). A lazy list in a sheet of its own, as LyrifySheet draws it: a queue may hold the whole
 * library. A window of its own, so it hosts its songs' double taps itself ([doubleTapHost]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    // The rows sit on the sheet colour (.qin).
    val sheetSurface = LocalChromeColors.current.sheet
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val book by connection.book.collectAsStateWithLifecycle()
    val library by connection.library.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val chrome = LocalChromeColors.current
    val colors = MaterialTheme.colorScheme
    var saving by remember { mutableStateOf(false) }
    val names = rememberNames()
    val reorder = remember { Reorder() }
    SettleReorder(book.current?.items, reorder)
    val doubleTaps = rememberDoubleTaps()
    val top = WindowInsets.statusBars.getTop(LocalDensity.current)
    val footMax = with(LocalDensity.current) { queueSheetFootMax(LocalWindowInfo.current.containerSize.height.toDp()) }
    val open: (Page) -> Unit = { page ->
        onDismiss()
        nav.open(page)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        scrimColor = chrome.scrim,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        SheetWindowExit()
        Column(
            Modifier
                .padding(top = with(LocalDensity.current) { top.toDp() } + 8.dp)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
                .shadow(16.dp, RoundedCornerShape(32.dp), ambientColor = chrome.shadow, spotColor = chrome.shadow)
                .background(chrome.sheet, RoundedCornerShape(32.dp))
                .doubleTapHost(doubleTaps),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).size(36.dp, 4.dp).background(colors.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
            val queue = book.current
            if (queue == null) {
                EmptyState(EmptyArt.Icon(R.drawable.ic_queue_music), stringResource(R.string.queue_nothing))
                return@Column
            }
            val others = book.others
            val labels = remember(context, book) { queueLabels(context, book) }
            // The sheet opens with the song playing in view, under the head.
            val list = rememberLazyListState(firstShown(queue.index, head = 1))
            LazyColumn(Modifier.weight(1f, fill = false), state = list) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Text(
                                stringResource(R.string.queue_from, labels.getValue(queue.id), stringResource(R.string.queue_position, queue.index + 1, queue.items.size)),
                                style = MaterialTheme.typography.bodyMedium.tabular(),
                                color = colors.onSurfaceVariant,
                            )
                        }
                        LyrifyButton(stringResource(R.string.queue_all), { open(Page.Queues) }, kind = ButtonKind.GHOST, compact = true)
                    }
                }
                queueSongs(queue, playing = true, library, names, connection, reorder, sheetSurface, onRemoved = { undo ->
                    scope.launch { if (toasts.show(Toast(resources.getString(R.string.queue_removed), R.drawable.ic_playlist_remove, action = undo?.let { resources.getString(R.string.player_undo) }))) undo?.undo() }
                })
            }
            ToastSlot(Modifier.padding(horizontal = 12.dp), padding = PaddingValues(bottom = 8.dp))
            QueueSheetFoot(others, labels, footMax, onSave = { saving = true }) { open(Page.Queue(it)) }
            if (saving) SaveAsPlaylistDialog(queue, connection) { saving = false }
            HeartPops(doubleTaps)
        }
    }
}

/**
 * The Queue sheet's other queues (3.1), pinned at its foot under a line: their title, a line saying what they are (the
 * user took the chips under the old "Recent" for lyrics), then a chip for each ([RecentChips]), which opens that
 * queue's page ([onOpen], by its id).
 */
@Composable
internal fun OtherQueues(others: List<Queue>, labels: Map<String, String>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionTitle(stringResource(R.string.queue_others), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
        Text(
            stringResource(R.string.queue_others_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        RecentChips(others, labels, onOpen)
    }
}

/** The most of a window's height ([window]) the Queue sheet's foot takes ([QueueSheetFoot]); the songs get the rest. */
internal fun queueSheetFootMax(window: Dp): Dp = window * FOOT_SHARE

/**
 * The Queue sheet's foot: Save as playlist, then the other queues ([OtherQueues]). It keeps to [maxHeight] and scrolls
 * past it, so in a short window (a phone on its side, split screen, a large font) the songs above it keep their room.
 */
@Composable
internal fun QueueSheetFoot(others: List<Queue>, labels: Map<String, String>, maxHeight: Dp, onSave: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
        LyrifyButton(stringResource(R.string.queue_save_playlist), onSave, Modifier.fillMaxWidth().padding(16.dp), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_playlist_add)
        if (others.isNotEmpty()) OtherQueues(others, labels, onOpen)
    }
}

/**
 * The Queue sheet's other queues as chips: [others] by their names in [labels] ([queueLabels]), each one capped in
 * width. A lazy row, as every kept queue shows here; a tap opens that queue's page by its id ([onOpen]).
 */
@Composable
internal fun RecentChips(others: List<Queue>, labels: Map<String, String>, onOpen: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
        items(others, key = { it.id }, contentType = { "chip" }) { other ->
            LyrifyChip(selected = false, onClick = { onOpen(other.id) }, role = Role.Button, icon = R.drawable.ic_queue_music) {
                Text(labels.getValue(other.id), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = CHIP_NAME_MAX))
            }
        }
    }
}

/**
 * A queue's songs, the place marked: a tap plays the queue from that song once a moment has passed with no second tap
 * ([WaitingTap]), and a double tap only makes the song a favourite ([doubleTapFavourite]); the handle drags a song to
 * another place, the others sliding out of its way ([Reorder]); a swipe to the start, or "Remove from queue" in the
 * row's menu, takes it out (with Undo through [onRemoved]), the rows under it closing up. Songs not on the phone stay
 * listed, quietly marked, and are skipped (and have no double tap; their tap acts at once).
 */
private fun LazyListScope.queueSongs(queue: Queue, playing: Boolean, library: Library?, names: LibrarySettings, connection: PlayerConnection, reorder: Reorder, surface: Color, onRemoved: (Undo?) -> Unit) {
    // Each row acts on its place in the queue itself, also while a drag shows another order.
    val places = queue.items.withIndex().associate { (index, item) -> item.uid to index }
    items(reorder.shown(queue), key = { it.uid }, contentType = { "song" }) { item ->
        val reduced = LocalReducedMotion.current
        // The held row follows the finger above the others; they move by their placement, a removed one fades.
        val motion = Modifier.animateItem(
            fadeInSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.FADE_IN_MS),
            placementSpec = if (reorder.held == item.uid || reduced) null else spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold),
            fadeOutSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.FADE_OUT_MS),
        )
        QueueSongRow(queue, item, places[item.uid] ?: -1, playing, library, names, connection, reorder, surface, motion, onRemoved)
    }
}

/**
 * Where a row held [offset] px from its place at [index] (of [size] rows, each [row] px tall) belongs: past half a
 * neighbour it takes that neighbour's place, as often as it has passed one; the offset left is from its new place. At
 * either end it stays, the finger's offset with it.
 */
fun reorderStep(index: Int, size: Int, offset: Float, row: Float): Pair<Int, Float> {
    var at = index
    var left = offset
    while (left > row / 2 && at < size - 1) {
        at++
        left -= row
    }
    while (left < -row / 2 && at > 0) {
        at--
        left += row
    }
    return at to left
}

/**
 * A queue being reordered by a row's handle: while a row is [held] (by its uid) the list shows [order], in which the row
 * takes its neighbours' places as it passes them ([reorderStep]) while they slide out of its way (their placement
 * animates); the held row follows the finger, [offset] px from its place, lifted. Let go, it settles into its place and
 * the queue moves it; the order shown stays until the queue's own order is the same, or a moment has passed.
 */
@Stable
private class Reorder {
    var order: List<QueueItem>? by mutableStateOf(null)
        private set

    var held: Long? by mutableStateOf(null)
        private set

    var offset: Float by mutableFloatStateOf(0f)
        private set

    private var from = -1

    fun shown(queue: Queue): List<QueueItem> = order ?: queue.items

    fun start(queue: Queue, uid: Long) {
        order = queue.items
        from = queue.items.indexOfFirst { it.uid == uid }
        held = uid
        offset = 0f
    }

    fun drag(dy: Float, row: Float) {
        val list = order ?: return
        val at = list.indexOfFirst { it.uid == held }
        if (at < 0) return
        val (to, left) = reorderStep(at, list.size, offset + dy, row)
        if (to != at) order = list.toMutableList().apply { add(to, removeAt(at)) }
        offset = left
    }

    suspend fun drop(queue: Queue, connection: PlayerConnection) {
        val to = order?.indexOfFirst { it.uid == held } ?: -1
        if (to >= 0 && from >= 0 && to != from) connection.move(queue.id, from, to) else order = null
        animate(offset, 0f, animationSpec = tween(Motion.SHORT_MS, easing = Motion.Standard)) { value, _ -> offset = value }
        held = null
    }

    /** The queue has caught up with the order shown, or will not: its own order shows again. */
    fun settle() {
        if (held == null) order = null
    }
}

/** After a drop, the queue's own order shows again once it says the same, or after a moment if the move did not take. */
@Composable
private fun SettleReorder(items: List<QueueItem>?, reorder: Reorder) {
    LaunchedEffect(items, reorder.held) {
        val shown = reorder.order ?: return@LaunchedEffect
        if (reorder.held != null) return@LaunchedEffect
        if (items?.map { it.uid } != shown.map { it.uid }) delay(CATCH_UP_MS)
        reorder.settle()
    }
}

/** "Show file names", for the songs' titles. */
@Composable
private fun rememberNames(): LibrarySettings {
    val context = LocalContext.current
    val flow = remember(context) { Lyrify.settings(context).settings.map { it.library } }
    return flow.collectAsStateWithLifecycle(initialValue = LibrarySettings()).value
}

@Composable
private fun QueueSongRow(
    queue: Queue,
    item: QueueItem,
    index: Int,
    playing: Boolean,
    library: Library?,
    names: LibrarySettings,
    connection: PlayerConnection,
    reorder: Reorder,
    surface: Color,
    motion: Modifier,
    onRemoved: (Undo?) -> Unit,
) {
    val resources = LocalResources.current
    val song = library?.resolver?.find(item.song)
    val title = song?.let { displayTitle(it, names) } ?: item.song.path.substringAfterLast('/')
    val here = index == queue.index
    val sub = when {
        here && !playing -> stringResource(R.string.queue_left_at, clock(queue.positionMs))
        song == null && library != null -> stringResource(if (queue.source.kind == SourceKind.OPENED) R.string.open_queue else R.string.player_not_on_phone)
        else -> song?.artist ?: ""
    }
    // The row grows with its text at a large font scale, so a drag moves the song by the height the row is laid out at.
    val density = LocalDensity.current
    var rowPx by remember { mutableFloatStateOf(with(density) { ROW.toPx() }) }
    val held = reorder.held == item.uid
    val reduced = LocalReducedMotion.current
    val lift = animateFloatAsState(if (held) 1f else 0f, motion(Motion.press, reduced), label = "queue lift")
    val lifted = with(LocalDensity.current) { LIFT.toPx() }
    val latest by rememberUpdatedState(queue)
    val scope = rememberCoroutineScope()
    val remove = { onRemoved(connection.remove(queue.id, item.uid)) }
    val tap = rememberWaitingTap { connection.jump(queue.id, index) }
    val state = rememberSwipeToDismissBoxState()
    var menu by remember { mutableStateOf(false) }
    val label = when {
        here && playing -> resources.getString(R.string.queue_playing_now, title)
        here -> resources.getString(R.string.queue_your_place, title)
        else -> title
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) remove() },
        modifier = motion
            .onSizeChanged { rowPx = it.height.toFloat() }
            .zIndex(if (held) 1f else 0f)
            .graphicsLayer {
                if (held) translationY = reorder.offset
                val up = lift.value
                scaleX = 1f + LIFT_SCALE * up
                scaleY = 1f + LIFT_SCALE * up
                shadowElevation = lifted * up
                shape = RoundedCornerShape(16.dp)
            },
        backgroundContent = {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
            ) {
                Icon(painterResource(R.drawable.ic_playlist_remove), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.queue_remove_swipe), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        val brand = LocalBrandColors.current
        val colors = MaterialTheme.colorScheme
        val fill by animateColorAsState(if (here) brand.highlightContainer.copy(alpha = CURRENT_ALPHA).compositeOver(surface) else surface, motion(tween(Motion.SETTLE_MS), reduced), label = "queue row")
        val words by animateColorAsState(if (here) brand.onHighlightContainer else colors.onSurface, motion(tween(Motion.SETTLE_MS), reduced), label = "queue words")
        val muted by animateColorAsState(if (here) brand.onHighlightContainer else colors.onSurfaceVariant, motion(tween(Motion.SETTLE_MS), reduced), label = "queue muted")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW)
                .background(surface)
                // The song here has its own rounded fill, inset as a selected song row's ([rowFill]).
                .background(fill, RowFillShape)
                .semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(resources.getString(R.string.queue_remove)) { remove(); true },
                        if (index > 0) CustomAccessibilityAction(resources.getString(R.string.queue_move_up)) { connection.move(queue.id, index, index - 1); true } else null,
                        if (index < queue.items.lastIndex) CustomAccessibilityAction(resources.getString(R.string.queue_move_down)) { connection.move(queue.id, index, index + 1); true } else null,
                    )
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ROW)
                    .doubleTapFavourite(song?.let { FavouriteTarget.Song(item.song) }, waiting = tap)
                    .clickable(role = Role.Button, onClick = tap::click)
                    .semantics(mergeDescendants = true) { contentDescription = "$label, $sub" }
                    .padding(start = ScreenPadding, top = 8.dp, bottom = 8.dp),
            ) {
                Box {
                    SongCover(SongArt(item.song.path), size = 44.dp, shape = RoundedCornerShape(10.dp))
                    // The song here (.eqo): the cover dimmed under a white mark, playing or left at a place; it fades in and out.
                    val shade by animateFloatAsState(if (here) 1f else 0f, motion(tween(Motion.SETTLE_MS), reduced), label = "queue mark")
                    if (shade > 0f) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.matchParentSize().graphicsLayer { alpha = shade }.background(QueueOverlay, RoundedCornerShape(10.dp))) {
                            Icon(painterResource(if (playing) R.drawable.ic_graphic_eq else R.drawable.ic_resume), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = QueueTitle,
                        fontWeight = if (here) FontWeight.SemiBold else null,
                        color = if (!here && index < queue.index) colors.onSurfaceVariant else words,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(sub, style = QueueLine, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.queue_song_more, title), { menu = true }, glass = false, tint = muted)
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow(stringResource(R.string.queue_remove), R.drawable.ic_playlist_remove, {
                        menu = false
                        remove()
                    })
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = resources.getString(R.string.queue_move, title) }
                    .pointerInput(queue.id, item.uid) {
                        val drop = { scope.launch { reorder.drop(latest, connection) } }
                        detectDragGestures(
                            onDragStart = { reorder.start(latest, item.uid) },
                            onDragEnd = { drop() },
                            onDragCancel = { drop() },
                        ) { change, amount ->
                            change.consume()
                            reorder.drag(amount.y, rowPx)
                        }
                    },
            ) { Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = null, tint = muted, modifier = Modifier.size(20.dp)) }
        }
    }
}

/** A queue row's height (.qrow, 60 dp), taller when its two lines need it at a large font scale; a drag moves a song by whole rows. */
private val ROW = 60.dp

/** The share of the window's height the Queue sheet's foot may take before it scrolls. */
private const val FOOT_SHARE = 0.4f

/** A Recent chip's widest name; a longer one ends in an ellipsis. */
private val CHIP_NAME_MAX = 220.dp

/** How long the order a drag left waits for the queue to say the same before the queue's own order shows. */
private const val CATCH_UP_MS = 1_500L

/** The current row's fill: the brand's container at 70% over the list's colour (.qrow.cur). */
private const val CURRENT_ALPHA = 0.7f

/** The dimming over the cover of the song here (--eqo-bg). */
private val QueueOverlay = Color(0x80141028)

/** A queue row's title (15.5/21) and line (13.5/19). */
private val QueueTitle = body(15.5f, 21)
private val QueueLine = body(13.5f, 19)
