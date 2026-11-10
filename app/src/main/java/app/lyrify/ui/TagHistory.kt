package app.lyrify.ui

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.logFailures
import app.lyrify.tags.BatchResult
import app.lyrify.tags.EditKind
import app.lyrify.tags.EditWords
import app.lyrify.tags.HistoryRow
import app.lyrify.tags.SongPlan
import app.lyrify.tags.TagsGraph
import app.lyrify.tags.historyRows
import app.lyrify.tags.report
import app.lyrify.tags.undoEdit
import app.lyrify.tags.writeWords
import app.lyrify.tags.writeEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// History (milestone T3: player-spec "Edit tags" > History, plan section 12), and how every tag edit is written and
// undone: in the background, to its end, with a message that offers Undo. Covers & tags' own edits show here too.

/** Tools > Covers & tags > History: every edit, the newest first, each with Undo until all its songs are undone. */
@Composable
fun TagHistoryPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val access = rememberNotificationAccess()
    var version by remember { mutableIntStateOf(0) }
    var undoing by remember { mutableStateOf<Long?>(null) }
    val rows by produceState<List<HistoryRow>?>(null, version) { value = withContext(Dispatchers.IO) { historyRows(TagsGraph.undo(context).memory()) } }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.edit_history), under, backdrop) { nav.back() } }) { padding ->
        val shown = rows ?: return@PageFrame
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp)) {
            if (!access) item(key = "access") { AccessLine(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_history), stringResource(R.string.edit_no_edits), Modifier.padding(top = 48.dp), text = stringResource(R.string.edit_history_empty))
                }
            }
            items(shown, key = { it.id }) { row ->
                HistoryLine(row, enabled = access && undoing == null) {
                    undoing = row.id
                    val app = context.applicationContext
                    edits.launch {
                        val result = undoEdit(app, row.id)
                        undoing = null
                        version++
                        toasts.show(undoneToast(resources, result))
                    }
                }
            }
        }
    }
}

/** One edit: its kind's icon, its label, its songs and when; Undo, or "Undone" once every song is. */
@Composable
private fun HistoryLine(row: HistoryRow, enabled: Boolean, onUndo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val songs = pluralStringResource(R.plurals.lib_songs, row.songs, row.songs)
    val time = DateUtils.getRelativeTimeSpanString(row.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh)) {
            Icon(painterResource(row.kind.icon), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.edit_two_parts, songs, time), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
        }
        if (row.undone) {
            StatusPill(stringResource(R.string.edit_undone_pill), null)
        } else {
            val named = stringResource(R.string.edit_undo_named, row.label)
            LyrifyButton(
                stringResource(R.string.edit_undo),
                onClick = onUndo,
                modifier = Modifier.semantics { contentDescription = named },
                kind = ButtonKind.GHOST,
                compact = true,
                enabled = enabled,
            )
        }
    }
}

private val EditKind.icon: Int
    get() = when (this) {
        EditKind.SONG -> R.drawable.ic_sell
        EditKind.ALBUM -> R.drawable.ic_album
        EditKind.ARTIST -> R.drawable.ic_person
        EditKind.MERGE -> R.drawable.ic_merge
        EditKind.COVERS_TAGS -> R.drawable.ic_travel_explore
    }

// Writing

/** Tag edits run here: a write runs to its end, and says how it went, even when the screen that started it closes. */
private val edits = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)

/** Shows [toast] from [edits], so it stays up after the screen that asked for it closes. */
internal fun ToastState.later(toast: Toast) {
    edits.launch { show(toast) }
}

/**
 * Writes [plans] as one History entry of [kind] shown as [label] ([writeEdit]: favourites follow); once done, says how
 * it went ([saved] when every song took it), with Undo, while [after] moves the screen on, given what was written.
 */
internal fun startWrite(
    context: Context,
    toasts: ToastState,
    resources: Resources,
    plans: List<SongPlan>,
    kind: EditKind,
    label: String,
    saved: String,
    after: suspend (BatchResult) -> Unit = {},
) {
    val app = context.applicationContext
    edits.launch {
        val result = writeEdit(app, plans, kind, label)
        edits.launch {
            val entry = (result as? BatchResult.Done)?.entryId
            val toast = writtenToast(resources, result, saved, plans.count { it.job != null })
            if (toasts.show(toast.copy(action = entry?.let { resources.getString(R.string.lib_undo) })) && entry != null) {
                toasts.show(undoneToast(resources, undoEdit(app, entry)))
            }
        }
        after(result)
    }
}

/** What a write says. */
private fun writtenToast(resources: Resources, result: BatchResult, saved: String, asked: Int): Toast = when (result) {
    BatchResult.SessionsUnknown -> Toast(resources.getString(R.string.edit_need_access), R.drawable.ic_error, Tone.ERROR)
    BatchResult.NotEnoughSpace -> Toast(resources.getString(R.string.edit_no_space), R.drawable.ic_error, Tone.ERROR)
    // An Undo's answer only: a write never gives it.
    BatchResult.HistoryUnreadable -> Toast(resources.getString(R.string.edit_not_changed), R.drawable.ic_error, Tone.ERROR)
    is BatchResult.Done -> {
        val report = result.report()
        when (report.writeWords()) {
            EditWords.PAUSED -> Toast(resources.getString(R.string.tags_paused), R.drawable.ic_error, Tone.ERROR)
            EditWords.NOTHING_CHANGED -> Toast(resources.getString(R.string.edit_nothing_changed), R.drawable.ic_sell)
            EditWords.IN_USE -> Toast(resources.getString(R.string.edit_in_use), R.drawable.ic_error, Tone.ERROR)
            EditWords.NOT_CHANGED -> Toast(resources.getString(R.string.edit_not_changed), R.drawable.ic_error, Tone.ERROR)
            EditWords.PARTLY -> Toast(resources.getQuantityString(R.plurals.edit_partly, asked, report.written, asked), R.drawable.ic_sell)
            EditWords.SAVED -> Toast(saved, R.drawable.ic_sell)
        }
    }
}

/** What an Undo says: done, the fields or songs it kept because they changed since, or what it could not do. */
internal fun undoneToast(resources: Resources, result: BatchResult): Toast = when (result) {
    BatchResult.SessionsUnknown -> Toast(resources.getString(R.string.edit_need_access), R.drawable.ic_error, Tone.ERROR)
    BatchResult.NotEnoughSpace -> Toast(resources.getString(R.string.edit_no_space), R.drawable.ic_error, Tone.ERROR)
    BatchResult.HistoryUnreadable -> Toast(resources.getString(R.string.tags_undo_failed), R.drawable.ic_error, Tone.ERROR)
    is BatchResult.Done -> {
        val report = result.report()
        when {
            report.paused > 0 -> Toast(resources.getString(R.string.tags_paused), R.drawable.ic_error, Tone.ERROR)
            report.busy + report.failed > 0 -> Toast(resources.getString(R.string.edit_undo_partly), R.drawable.ic_error, Tone.ERROR)
            report.written == 0 -> Toast(resources.getString(R.string.edit_nothing_to_undo), R.drawable.ic_undo)
            report.changedSince > 0 -> Toast(resources.getQuantityString(R.plurals.edit_undone_skipped, report.changedSince, report.changedSince), R.drawable.ic_undo)
            report.skippedFields > 0 -> Toast(resources.getQuantityString(R.plurals.edit_undone_kept, report.skippedFields, report.skippedFields), R.drawable.ic_undo)
            else -> Toast(resources.getString(R.string.edit_undone), R.drawable.ic_undo)
        }
    }
}

/**
 * Reads the library again now (a write has just changed it), and waits up to [LIBRARY_WAIT_MS] until it is [ready]:
 * then a page can follow its album or artist to the new name. False when it did not get there in time.
 */
internal suspend fun awaitLibrary(context: Context, ready: (Library) -> Boolean): Boolean {
    val repository = LibraryGraph.repository(context)
    repository.refresh()
    return withTimeoutOrNull(LIBRARY_WAIT_MS) { repository.state.first { state -> (state as? LibraryState.Ready)?.library?.let(ready) == true } } != null
}

/** Puts [new] in place of [old] when [old] is the page on top, nothing over it: a renamed album or artist. */
internal fun Navigator.replaceTop(old: Page, new: Page) {
    if (state.views.isNotEmpty() || state.pages.lastOrNull() != old) return
    back()
    open(new)
}

private const val LIBRARY_WAIT_MS = 5_000L
