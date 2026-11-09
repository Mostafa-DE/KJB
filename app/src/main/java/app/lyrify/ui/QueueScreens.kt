package app.lyrify.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.BuildConfig
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
private fun plainName(context: Context, queue: Queue): String = queue.name ?: queue.source.name.takeIf { it.isNotBlank() && queue.source.kind != SourceKind.PICKED } ?: queueLabel(context, queue)

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
    val name = queueLabel(context, queue)
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
                .semantics(mergeDescendants = true) { contentDescription = if (playing) context.getString(R.string.queue_playing_now, "$name, $line") else "$name, $line" }
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
            LyrifyButton(stringResource(R.string.queue_keep), onKeep, kind = ButtonKind.GHOST, compact = true, modifier = Modifier.semantics { contentDescription = context.getString(R.string.queue_keep_named, name) })
        }
    }
}

/** A queue's own page: its name and place, Resume (or Play/Pause while it plays) and Play from start, then its songs. */
@Composable
private fun OneQueuePage(id: String) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val book by connection.book.collectAsStateWithLifecycle()
    val ui by connection.ui.collectAsStateWithLifecycle()
    val library by connection.library.collectAsStateWithLifecycle()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val queue = book.queue(id)
    val names = rememberNames()
    val drag = remember { DragState() }
    var dialog by remember { mutableStateOf<QueueDialog?>(null) }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = {},
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
                actions = {
                    if (queue != null) QueueActions(queue, book, connection, onRename = { dialog = QueueDialog.RENAME }, onSave = { dialog = QueueDialog.SAVE_AS_PLAYLIST }, onDelete = { dialog = QueueDialog.DELETE })
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
                    Text(queueLabel(context, queue), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
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
            queueSongs(queue, playing, library, names, connection, drag, onRemoved = { undo ->
                scope.launch { if (toasts.show(Toast(resources.getString(R.string.queue_removed), R.drawable.ic_playlist_remove, action = undo?.let { resources.getString(R.string.player_undo) }))) undo?.undo() }
            })
        }
    }
    QueueDialogs(dialog, queue, connection, onDone = { dialog = null }, onDeleted = { nav.back() })
}

/** A queue page's top bar actions: Add all (the Add to sheet, L1's), and "⋯" with Rename, Keep, Save as playlist and Delete. */
@Composable
private fun QueueActions(queue: Queue, book: QueueBook, connection: PlayerConnection, onRename: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    // "Add all": the Add to sheet is L1's; it plugs in here.
    ChromeIconButton(R.drawable.ic_playlist_add, stringResource(R.string.queue_add_all, pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size)), {
        if (BuildConfig.DEBUG) scope.launch { toasts.show(Toast("Coming in L1")) }
    })
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
            if (queue.id != book.currentId) {
                MenuRow(stringResource(R.string.queue_delete), R.drawable.ic_delete, {
                    menu = false
                    onDelete()
                }, destructive = true)
            }
        }
    }
}

private enum class QueueDialog { RENAME, DELETE, SAVE_AS_PLAYLIST }

/** Rename, Delete (asks first, removes only the queue) and Save as playlist. */
@Composable
private fun QueueDialogs(dialog: QueueDialog?, queue: Queue?, connection: PlayerConnection, onDone: () -> Unit, onDeleted: () -> Unit) {
    if (dialog == null || queue == null) return
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    when (dialog) {
        QueueDialog.RENAME -> NameDialog(stringResource(R.string.queue_rename_title), stringResource(R.string.queue_name_field), queueLabel(context, queue), stringResource(R.string.queue_rename), R.drawable.ic_edit, onDone) { name ->
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
                connection.delete(queue.id)
                onDeleted()
                scope.launch { toasts.show(Toast(resources.getString(R.string.queue_deleted), R.drawable.ic_delete)) }
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
    var name by remember { mutableStateOf(start) }
    ConfirmDialog(
        title = title,
        confirm = confirm,
        dismiss = stringResource(R.string.queue_cancel),
        onConfirm = {
            onDismiss()
            onName(name.trim())
        },
        onDismiss = onDismiss,
        icon = icon,
        destructive = false,
        confirmEnabled = name.isNotBlank(),
        body = { LyrifyField(name, { name = it }, Modifier.fillMaxWidth(), label = field) },
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
    val drag = remember { DragState() }
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
                                stringResource(R.string.queue_from, queueLabel(context, queue), pluralStringResource(R.plurals.queue_songs, queue.items.size, queue.items.size)),
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
                                Text(queueLabel(context, other), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                queueSongs(queue, playing = true, library, names, connection, drag, onRemoved = { undo ->
                    scope.launch { if (toasts.show(Toast(resources.getString(R.string.queue_removed), R.drawable.ic_playlist_remove, action = undo?.let { resources.getString(R.string.player_undo) }))) undo?.undo() }
                })
            }
            ToastSlot(Modifier.padding(horizontal = 12.dp), padding = PaddingValues(top = 4.dp))
            LyrifyButton(stringResource(R.string.queue_save_playlist), { saving = true }, Modifier.fillMaxWidth().padding(16.dp), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_playlist_add)
            if (saving) SaveAsPlaylistDialog(queue, connection) { saving = false }
        }
    }
}

/**
 * A queue's songs, the place marked: a tap plays the queue from that song; the handle drags a song to another place;
 * a swipe to the start, or "Remove from queue" in the row's menu, takes it out (with Undo through [onRemoved]). Songs
 * not on the phone stay listed, quietly marked, and are skipped.
 */
private fun LazyListScope.queueSongs(queue: Queue, playing: Boolean, library: Library?, names: LibrarySettings, connection: PlayerConnection, drag: DragState, onRemoved: (Undo?) -> Unit) {
    itemsIndexed(queue.items, key = { _, item -> item.uid }) { index, item ->
        QueueSongRow(queue, item, index, playing, library, names, connection, drag, onRemoved)
    }
}

/** "Show file names", for the songs' titles. */
@Composable
private fun rememberNames(): LibrarySettings {
    val context = LocalContext.current
    val flow = remember(context) { Lyrify.settings(context).settings.map { it.library } }
    return flow.collectAsStateWithLifecycle(initialValue = LibrarySettings()).value
}

/** Which row is being dragged, and how far. */
private class DragState {
    var from by mutableStateOf(-1)
    var offset by mutableStateOf(0f)
}

@Composable
private fun QueueSongRow(queue: Queue, item: QueueItem, index: Int, playing: Boolean, library: Library?, names: LibrarySettings, connection: PlayerConnection, drag: DragState, onRemoved: (Undo?) -> Unit) {
    val context = LocalContext.current
    val song = library?.resolver?.find(item.song)
    val title = song?.let { displayTitle(it, names) } ?: item.song.path.substringAfterLast('/')
    val here = index == queue.index
    val sub = when {
        here && !playing -> stringResource(R.string.queue_left_at, clock(queue.positionMs))
        song == null && library != null -> stringResource(R.string.player_not_on_phone)
        else -> song?.artist ?: ""
    }
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val target = if (drag.from >= 0) (drag.from + (drag.offset / rowPx).roundToInt()).coerceIn(0, queue.items.lastIndex) else -1
    val shift = when {
        drag.from < 0 || index == drag.from -> 0f
        drag.from < target && index in (drag.from + 1)..target -> -rowPx
        drag.from > target && index in target until drag.from -> rowPx
        else -> 0f
    }
    val remove = { onRemoved(connection.remove(queue.id, item.uid)) }
    val state = rememberSwipeToDismissBoxState()
    var menu by remember { mutableStateOf(false) }
    val label = when {
        here && playing -> context.getString(R.string.queue_playing_now, title)
        here -> context.getString(R.string.queue_your_place, title)
        else -> title
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) remove() },
        modifier = Modifier
            .zIndex(if (index == drag.from) 1f else 0f)
            .graphicsLayer { translationY = if (index == drag.from) drag.offset else shift },
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW)
                .background(if (here) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
                .semantics {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(context.getString(R.string.queue_remove)) { remove(); true },
                        if (index > 0) CustomAccessibilityAction(context.getString(R.string.queue_move_up)) { connection.move(queue.id, index, index - 1); true } else null,
                        if (index < queue.items.lastIndex) CustomAccessibilityAction(context.getString(R.string.queue_move_down)) { connection.move(queue.id, index, index + 1); true } else null,
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
                    SongCover(SongArt(item.song.path), size = 44.dp)
                    if (here) {
                        Icon(
                            painterResource(if (playing) R.drawable.ic_graphic_eq else R.drawable.ic_resume),
                            contentDescription = null,
                            tint = LocalBrandColors.current.highlight,
                            modifier = Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(4.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = if (index < queue.index) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.queue_song_more, title), { menu = true }, glass = false)
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
                    .semantics { contentDescription = context.getString(R.string.queue_move, title) }
                    .pointerInput(queue.id, index) {
                        detectDragGestures(
                            onDragStart = {
                                drag.from = index
                                drag.offset = 0f
                            },
                            onDragEnd = {
                                val to = (drag.from + (drag.offset / rowPx).roundToInt()).coerceIn(0, queue.items.lastIndex)
                                if (drag.from >= 0 && to != drag.from) connection.move(queue.id, drag.from, to)
                                drag.from = -1
                                drag.offset = 0f
                            },
                            onDragCancel = {
                                drag.from = -1
                                drag.offset = 0f
                            },
                        ) { _, amount -> drag.offset += amount.y }
                    },
            ) { Icon(painterResource(R.drawable.ic_drag_indicator), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A queue row's height; a drag moves a song by whole rows. */
private val ROW = 64.dp
