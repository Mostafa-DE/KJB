package app.lyrify.ui

import android.app.Activity
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.dupes.Confirmation
import app.lyrify.dupes.Deleted
import app.lyrify.dupes.DuplicatesState
import app.lyrify.dupes.MediaStoreTrash
import app.lyrify.dupes.SongTrash
import app.lyrify.dupes.TrashStep
import app.lyrify.dupes.TrashedSong
import app.lyrify.lyrics.TrackNames
import kotlinx.coroutines.launch

// What the page shows

/**
 * Tools > Trash's songs: the ones KJB moved to the phone's trash ([DuplicatesState.trashed]) and the ones other apps moved
 * there that the library would list ([SongTrash.elsewhere]), each once, the latest moved first (the phone keeps each the
 * same time, so the furthest expiry). Null until both are read.
 */
fun trashSongs(state: DuplicatesState, elsewhere: List<TrashedSong>?): List<TrashedSong>? {
    if (!state.loaded || elsewhere == null) return null
    val own = state.trashed
    val ids = own.mapTo(HashSet()) { it.file.id }
    return (own + elsewhere.filter { it.file.id !in ids }).sortedWith(compareByDescending<TrashedSong> { it.expiresAt }.thenByDescending { it.trashedAt })
}

/** The room [songs] take on the phone: what deleting them for good frees. */
fun trashBytes(songs: List<TrashedSong>): Long = songs.sumOf { it.file.size }

/** Trash's line in Tools: nothing until the trash is read, then "Empty" or "3 songs · 24 MB". */
fun trashLine(songs: List<TrashedSong>?): ToolLine? = when {
    songs == null -> null
    songs.isEmpty() -> ToolLine.Text(R.string.tools_trash_none)
    else -> ToolLine.CountBytes(R.plurals.tools_trash, songs.size, trashBytes(songs))
}

/**
 * What the page says once the phone's delete dialog gave [answer], [approved] (its OK) or cancelled: how many went for
 * good, of how many, and the room freed; that none went; or that KJB could not check yet. Null only when nothing waited
 * for this answer.
 */
fun deleteSaid(approved: Boolean, answer: Deleted): TrashSaid? = when {
    !approved -> TrashSaid.Text(R.string.trash_delete_cancelled)
    answer is Deleted.Gone && answer.count == 0 -> TrashSaid.Count(R.plurals.trash_deleted_none, answer.of)
    answer is Deleted.Gone && answer.count < answer.of -> TrashSaid.Freed(R.plurals.trash_deleted_some, answer.of, listOf(answer.count, answer.of), answer.bytes)
    answer is Deleted.Gone -> TrashSaid.Freed(R.plurals.trash_deleted, answer.count, listOf(answer.count), answer.bytes)
    answer == Deleted.NotConfirmed -> TrashSaid.Text(R.string.dupes_trash_unconfirmed)
    else -> null
}

/** The trash's songs for Tools and its page ([trashSongs]), as the Duplicates engine and the phone's trash have them. */
@Composable
fun rememberTrashSongs(): List<TrashedSong>? {
    val engine = rememberDuplicates()
    val state by engine.state.collectAsStateWithLifecycle()
    val elsewhere by engine.trash.elsewhere.collectAsStateWithLifecycle()
    return remember(state.loaded, state.memory.trashed, elsewhere) { trashSongs(state, elsewhere) }
}

/**
 * Opens the phone's own dialog deleting songs for good (MediaStore ids) and hands its answer to the shared trash flow
 * ([SongTrash.deleteAnswered]); what it did shows in [toasts] ([deleteSaid]), and so does a request the phone refused.
 */
@Composable
fun rememberPhoneDelete(songTrash: SongTrash, toasts: ToastState): (List<Long>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val approved = result.resultCode == Activity.RESULT_OK
        scope.launch { deleteSaid(approved, songTrash.deleteAnswered(approved))?.let { toasts.say(context, it) } }
    }
    return remember(launcher, songTrash) {
        { ids ->
            when (val request = MediaStoreTrash(context.contentResolver, Lyrify.writes).deleteRequest(ids)) {
                is MediaStoreTrash.Request.Ready -> launcher.launch(IntentSenderRequest.Builder(request.intent).build())
                is MediaStoreTrash.Request.Unavailable -> scope.launch {
                    Log.w(LOG_TAG, "trash: the phone refused the delete request (${request.cause})")
                    songTrash.deleteAnswered(approved = false)
                    toasts.say(context, TrashSaid.Count(R.plurals.trash_delete_refused, ids.size))
                }
            }
        }
    }
}

// The page

/** Tools > Trash: its own top bar with a back arrow over the songs in the phone's trash. */
@Composable
fun TrashPage(onBack: () -> Unit) {
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.trash_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
    ) { padding -> TrashScreen(padding) }
}

/**
 * The songs in the phone's trash ([trashSongs]), each with Restore and Delete forever, under how much room they take and
 * Empty trash. Restore asks the phone's own dialog; Delete forever and Empty trash ask KJB's first, with the room they
 * free, then the phone's. Nothing else on the page changes a file.
 */
@Composable
private fun TrashScreen(contentPadding: PaddingValues) {
    val engine = rememberDuplicates()
    val trash = engine.trash
    val state by engine.state.collectAsStateWithLifecycle()
    val songs = rememberTrashSongs()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val restore = rememberPhoneTrash(trash, toasts)
    val delete = rememberPhoneDelete(trash, toasts)
    // A trash answer the app did not hear is settled, then both lists are read again.
    LaunchedEffect(engine) { engine.refreshTrashed() }

    FadeThrough(songs, Modifier.fillMaxSize(), kind = { it?.isEmpty() }) { shown ->
        when {
            shown == null -> Unit
            shown.isEmpty() -> CenteredState(contentPadding) {
                EmptyState(EmptyArt.Icon(R.drawable.ic_delete), stringResource(R.string.trash_empty_title), text = stringResource(R.string.trash_empty_text))
            }
            else -> TrashList(
                songs = shown,
                contentPadding = contentPadding,
                onEmpty = { trash.askDelete(shown) },
                onRestore = { song -> trash.restore(song).takeIf { it.isNotEmpty() }?.let { restore(it, false) } },
                onDelete = { song -> trash.askDelete(listOf(song)) },
            )
        }
    }
    (state.trash as? TrashStep.ConfirmDelete)?.let { confirm ->
        DeleteDialog(confirm.songs, onCancel = trash::cancel) {
            scope.launch {
                when (val confirmation = trash.confirmDelete(confirm.songs)) {
                    is Confirmation.Ask -> delete(confirmation.ids)
                    Confirmation.Changed -> {
                        engine.refreshTrashed()
                        toasts.say(resources, R.string.trash_changed)
                    }
                    Confirmation.Unavailable -> toasts.say(resources, R.string.dupes_trash_failed)
                    Confirmation.NotAsked -> Unit
                }
            }
        }
    }
}

@Composable
private fun TrashList(songs: List<TrashedSong>, contentPadding: PaddingValues, onEmpty: () -> Unit, onRestore: (TrashedSong) -> Unit, onDelete: (TrashedSong) -> Unit) {
    val reduced = LocalReducedMotion.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item(key = "summary", contentType = "summary") { Box(rowMotion(reduced)) { TrashSummary(songs, onEmpty) } }
        items(songs, key = { "trashed ${it.file.id}" }, contentType = { "trashed" }) { song ->
            TrashRow(song, rowMotion(reduced), onRestore = { onRestore(song) }, onDelete = { onDelete(song) })
        }
    }
}

/** "3 songs · 24 MB" (a heading for TalkBack), what the phone does with them, and Empty trash with the room it frees. */
@Composable
private fun TrashSummary(songs: List<TrashedSong>, onEmpty: () -> Unit) {
    val size = Formatter.formatShortFileSize(LocalContext.current, trashBytes(songs))
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            pluralStringResource(R.plurals.tools_trash, songs.size, songs.size, size),
            style = MaterialTheme.typography.titleLarge.tabular(),
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.trash_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LyrifyButton(stringResource(R.string.trash_empty_all, size), onClick = onEmpty, kind = ButtonKind.DESTRUCTIVE, icon = R.drawable.ic_delete, compact = true)
    }
}

/**
 * A song in the phone's trash: its title, then its artist, size and the day the phone deletes it (one TalkBack stop),
 * then Restore and Delete forever, each named with the song for TalkBack.
 */
@Composable
private fun TrashRow(song: TrashedSong, modifier: Modifier, onRestore: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val until = DateUtils.formatDateTime(context, song.expiresAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    val artist = song.file.artist?.takeIf { TrackNames.cleanArtist(it) != null } ?: stringResource(R.string.check_no_artist)
    val facts = stringResource(R.string.trash_facts, artist, Formatter.formatShortFileSize(context, song.file.size), until)
    val restore = stringResource(R.string.dupes_restore_cd, song.file.title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = ScreenPadding, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(song.file.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LyrifyButton(stringResource(R.string.dupes_restore), onClick = onRestore, modifier = Modifier.semantics { contentDescription = restore }, kind = ButtonKind.GHOST, compact = true)
        ChromeIconButton(R.drawable.ic_delete, stringResource(R.string.trash_delete_cd, song.file.title), onDelete, glass = false, tint = MaterialTheme.colorScheme.error)
    }
}

/** KJB's own "Delete 2 songs forever?", before the phone's: the room it frees, and that it can't be undone. */
@Composable
private fun DeleteDialog(songs: List<TrashedSong>, onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = pluralStringResource(R.plurals.trash_delete_title, songs.size, songs.size),
        text = stringResource(R.string.trash_delete_text, Formatter.formatShortFileSize(LocalContext.current, trashBytes(songs))),
        confirm = stringResource(R.string.trash_delete_confirm),
        dismiss = stringResource(R.string.remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onCancel,
    )
}
