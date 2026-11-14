package app.lyrify.ui

import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import app.lyrify.player.sourceLabel
import app.lyrify.settings.LibrarySettings
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Queues (P1): the Queue sheet from the player, the Queues page (Saved, then Recent), and a queue's own page. A queue's
// list reorders by dragging its handle and loses a song by a swipe to the start; "Remove from queue" is the visible twin.

/** Queues (Saved and Recent), or one queue's page. */
@Composable
fun QueuePage(page: Page.QueuePages) {
    when (page) {
        Page.Queues -> QueuesPage()
        is Page.Queue -> OneQueuePage(page.id)
    }
}

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

/** The Queues page: Saved, then Recent; each row opens the queue's page, a recent one also has Keep. */
@Composable
private fun QueuesPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val book by connection.book.collectAsStateWithLifecycle()
    val ui by connection.ui.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.queues_title)) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp)) {
            item { SectionTitle(stringResource(R.string.queues_saved), Modifier.padding(vertical = 8.dp)) }
            val saved = book.saved.mapNotNull(book::queue)
            if (saved.isEmpty()) item { Text(stringResource(R.string.queues_saved_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp)) }
            saved.forEach { queue -> item(key = queue.id) { QueueLine(queue, book, ui, onKeep = null) { nav.open(Page.Queue(queue.id)) } } }
            item { SectionTitle(stringResource(R.string.queues_recent), Modifier.padding(top = 20.dp, bottom = 8.dp)) }
            val recent = book.recent.mapNotNull(book::queue)
            if (recent.isEmpty()) item { Text(stringResource(R.string.queue_no_recent), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp)) }
            recent.forEach { queue ->
                item(key = queue.id) {
                    QueueLine(queue, book, ui, onKeep = {
                        connection.keep(queue.id)
                        scope.launch { toasts.show(Toast(resources.getString(R.string.queue_kept), R.drawable.ic_bookmark_add)) }
                    }) { nav.open(Page.Queue(queue.id)) }
                }
            }
        }
    }
}

/** A queue's row: its current song's cover in a ring of how far the queue is, its name, its size and place. */
@Composable
private fun QueueLine(queue: Queue, book: QueueBook, ui: PlayerUi?, onKeep: (() -> Unit)?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val name = queueLabel(context, book, queue)
    val size = pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size)
    val line = stringResource(R.string.queue_line, size, place(context, queue, ui))
    val playing = queue.id == book.currentId
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = if (playing) resources.getString(R.string.queue_playing_now, "$name, $line") else "$name, $line" }
                .padding(8.dp),
        ) {
            val path = queue.current?.song?.path
            SongCover(path?.let(::SongArt), size = 48.dp, shape = CircleShape)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (playing) Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(16.dp))
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onKeep == null) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onKeep != null) {
            LyrifyButton(stringResource(R.string.queue_keep), onKeep, kind = ButtonKind.GHOST, compact = true, modifier = Modifier.semantics { contentDescription = resources.getString(R.string.queue_keep_named, name) })
        }
    }
}

/**
 * A queue's own page: its name and place, Resume (or Play/Pause while it plays) and Play from start, then its songs.
 * Delete sits in the top bar beside Add to; it goes back to where the page was opened from, and its toast's Undo brings
 * the queue back and opens it again.
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
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = {},
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
                actions = {
                    if (queue != null) {
                        QueueActions(
                            queue,
                            book,
                            connection,
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
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tag = when {
                        playing -> R.string.queue_playing_tag
                        id in book.saved -> R.string.queue_saved_tag
                        else -> R.string.queue_recent_tag
                    }
                    StatusPill(stringResource(tag), tone = null)
                    Text(queueLabel(context, book, queue), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(place(context, queue, ui), style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (playing) {
                            val on = ui?.playing == true
                            LyrifyButton(stringResource(if (on) R.string.player_pause else R.string.player_play), connection::playPause, icon = if (on) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill)
                        } else {
                            LyrifyButton(stringResource(R.string.queue_resume), { connection.resume(id) }, icon = R.drawable.ic_play_arrow_fill)
                        }
                        LyrifyButton(stringResource(R.string.queue_from_start), { connection.playFromStart(id) }, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_replay)
                    }
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
 * A queue page's top bar actions: Add all (the Add to sheet, L1's), Delete beside it (not for the queue playing), and
 * "⋯" with Rename, Keep and Save as playlist.
 */
@Composable
private fun QueueActions(
    queue: Queue,
    book: QueueBook,
    connection: PlayerConnection,
    onAddAll: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
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
            if (queue.id !in book.saved) {
                MenuRow(stringResource(R.string.queue_keep), R.drawable.ic_bookmark_add, {
                    menu = false
                    connection.keep(queue.id)
                    scope.launch { toasts.show(Toast(resources.getString(R.string.queue_kept), R.drawable.ic_bookmark_add)) }
                })
            }
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
 * The Queue sheet, from the player: the queue's name and size with Keep, the Recent chips (each opens that queue's page)
 * and "All queues", then the list, the current song marked; "Save as playlist" at the foot. A lazy list in a sheet of its
 * own, as LyrifySheet draws it: a queue may hold the whole library.
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
    val top = WindowInsets.statusBars.getTop(LocalDensity.current)
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
                .background(chrome.sheet, RoundedCornerShape(32.dp)),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).size(36.dp, 4.dp).background(colors.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
            val queue = book.current
            if (queue == null) {
                EmptyState(EmptyArt.Icon(R.drawable.ic_queue_music), stringResource(R.string.queue_nothing))
                return@Column
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Text(
                                stringResource(R.string.queue_from, queueLabel(context, book, queue), pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (queue.id in book.saved) {
                            StatusPill(stringResource(R.string.queue_saved_tag), tone = null, icon = R.drawable.ic_bookmark)
                        } else {
                            LyrifyButton(stringResource(R.string.queue_keep), {
                                connection.keep(queue.id)
                                scope.launch { toasts.show(Toast(resources.getString(R.string.queue_kept), R.drawable.ic_bookmark_add)) }
                            }, kind = ButtonKind.GHOST, icon = R.drawable.ic_bookmark_add, compact = true)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp)) {
                        SectionTitle(stringResource(R.string.queue_recent), Modifier.weight(1f))
                        LyrifyButton(stringResource(R.string.queue_all), { open(Page.Queues) }, kind = ButtonKind.GHOST, compact = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp)) {
                        val recent = book.recent.mapNotNull(book::queue)
                        if (recent.isEmpty()) Text(stringResource(R.string.queue_no_recent), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        recent.forEach { other ->
                            LyrifyChip(selected = false, onClick = { open(Page.Queue(other.id)) }, role = Role.Button, icon = R.drawable.ic_queue_music) {
                                Text(queueLabel(context, book, other), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                queueSongs(queue, playing = true, library, names, connection, reorder, sheetSurface, onRemoved = { undo ->
                    scope.launch { if (toasts.show(Toast(resources.getString(R.string.queue_removed), R.drawable.ic_playlist_remove, action = undo?.let { resources.getString(R.string.player_undo) }))) undo?.undo() }
                })
            }
            ToastSlot(Modifier.padding(horizontal = 12.dp), padding = PaddingValues(bottom = 8.dp))
            LyrifyButton(stringResource(R.string.queue_save_playlist), { saving = true }, Modifier.fillMaxWidth().padding(16.dp), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_playlist_add)
            if (saving) SaveAsPlaylistDialog(queue, connection) { saving = false }
        }
    }
}

/**
 * A queue's songs, the place marked: a tap plays the queue from that song; the handle drags a song to another place, the
 * others sliding out of its way ([Reorder]); a swipe to the start, or "Remove from queue" in the row's menu, takes it
 * out (with Undo through [onRemoved]), the rows under it closing up. Songs not on the phone stay listed, quietly marked,
 * and are skipped.
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
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val held = reorder.held == item.uid
    val reduced = LocalReducedMotion.current
    val lift = animateFloatAsState(if (held) 1f else 0f, motion(Motion.press, reduced), label = "queue lift")
    val lifted = with(LocalDensity.current) { LIFT.toPx() }
    val latest by rememberUpdatedState(queue)
    val scope = rememberCoroutineScope()
    val remove = { onRemoved(connection.remove(queue.id, item.uid)) }
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
                .height(ROW)
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
                    .fillMaxSize()
                    .clickable(role = Role.Button) { connection.jump(queue.id, index) }
                    .semantics(mergeDescendants = true) { contentDescription = "$label, $sub" }
                    .padding(start = ScreenPadding),
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

/** A queue row's height (.qrow, 60 dp); a drag moves a song by whole rows. */
private val ROW = 60.dp

/** How long the order a drag left waits for the queue to say the same before the queue's own order shows. */
private const val CATCH_UP_MS = 1_500L

/** The current row's fill: the brand's container at 70% over the list's colour (.qrow.cur). */
private const val CURRENT_ALPHA = 0.7f

/** The dimming over the cover of the song here (--eqo-bg). */
private val QueueOverlay = Color(0x80141028)

/** A queue row's title (15.5/21) and line (13.5/19). */
private val QueueTitle = body(15.5f, 21)
private val QueueLine = body(13.5f, 19)
