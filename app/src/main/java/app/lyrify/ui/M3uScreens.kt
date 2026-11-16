package app.lyrify.ui

import android.content.res.Resources
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.backup.M3u
import app.lyrify.backup.M3uDocuments
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryRepository
import app.lyrify.library.LibraryState
import app.lyrify.library.Playlist
import app.lyrify.library.SongResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Playlists: m3u import and export (milestone B1). L1's pages call these: the Playlists tab and a playlist's page each
// call rememberM3uActions; a playlist's "⋯" shows PlaylistExportRow, the Playlists tab's "⋯" PlaylistsM3uRows. Import
// hands the picked files to Import from app's sheet (ImportScreens.kt), so a file comes in one way only.

/**
 * "⋯ > Import"'s answer: the [picked] files (their URIs) not on their way already are kept in [pending], the picked
 * files this page keeps, and handed to Import from app's sheet as one Pick file ([handOver]).
 */
internal fun CoroutineScope.handPicked(picked: List<String>, pending: MutableState<List<String>>, handoff: ImportHandoff = ImportHandoff.shared) {
    val added = picked.distinct().filter { it !in pending.value }
    if (added.isEmpty()) return
    pending.value += added
    launch { handOver(added, pending, handoff) }
}

/**
 * Hands [handed] to Import from app's sheet ([handoff]), and only then lets go of them in [pending], the picked files this
 * page keeps: a turn of the phone or the process ending meanwhile leaves them there, and the page hands them again.
 */
internal suspend fun handOver(handed: List<String>, pending: MutableState<List<String>>, handoff: ImportHandoff = ImportHandoff.shared) {
    handoff.hand(handed)
    pending.value -= handed
}

/** Export, Export all and Import for one page ([rememberM3uActions]): the system's dialogs, the files and the toasts. */
@Stable
class M3uActions internal constructor(
    /** A playlist's "⋯" > Export: the save dialog (a debug build: a folder), then `Name.m3u8` there. */
    val export: (Playlist) -> Unit,
    /** The Playlists tab's "⋯" > Export all: a folder, then one file per playlist in it. */
    val exportAll: () -> Unit,
    /** The Playlists tab's "⋯" > Import: one or more `.m3u` or `.m3u8` files, then Import from app's sheet with them. */
    val import: () -> Unit,
)

/**
 * [M3uActions] for a page. Call it on the page, not inside its menu, so the dialogs' answers still find it once the menu
 * has closed. Files are written on the IO dispatcher and finish even if the page closes meanwhile. The picked files
 * not handed to Import from app's sheet yet are kept if the activity is made again (a turn of the phone) and handed
 * then. The log gets counts and exception names only.
 */
@Composable
fun rememberM3uActions(): M3uActions {
    val app = LocalContext.current.applicationContext
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val files = remember(app) { M3uDocuments(app.contentResolver, Lyrify.writes) { Log.w(LOG_TAG, it) } }
    val playlists = remember(app) { LibraryGraph.playlists(app) }
    val library = remember(app) { LibraryGraph.repository(app) }
    // The picked files not handed to Import from app's sheet yet, by URI, in the order picked.
    val pending = rememberSaveable { mutableStateOf(emptyList<String>()) }
    // The playlist the save dialog or the folder is open for (none: Export all), kept if the activity is made again.
    val exporting = rememberSaveable { mutableStateOf<String?>(null) }
    val untitled = { resources.getString(R.string.m3u_untitled) }
    val say = { text: String, failed: Boolean -> scope.launch { toasts.show(if (failed) Toast(text, R.drawable.ic_error, Tone.ERROR) else Toast(text)) } }
    LaunchedEffect(Unit) {
        if (pending.value.isNotEmpty()) handOver(pending.value, pending)
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(files.mimeType)) { document ->
        val id = exporting.value
        exporting.value = null
        if (document != null && id != null) {
            scope.launch {
                val playlist = playlists.state.first().playlist(id)
                val text = playlist?.let { M3u.write(it.songs, library.resolver()) }
                val written = text != null && withContext(Dispatchers.IO + NonCancellable) { files.write(document, text) }
                Log.i(LOG_TAG, "m3u export: ${if (written) "written" else "not written"}")
                say(resources.getString(if (written) R.string.m3u_exported else R.string.m3u_not_exported), !written)
            }
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val one = exporting.value
        exporting.value = null
        if (tree != null) {
            scope.launch {
                val all = playlists.state.first().playlists
                val chosen = if (one == null) all else all.filter { it.id == one }
                val resolver = library.resolver()
                val texts = M3u.fileNames(chosen.map { it.name }, untitled()).zip(chosen) { name, playlist -> name to M3u.write(playlist.songs, resolver) }
                val written = withContext(Dispatchers.IO + NonCancellable) { files.writeAll(tree, texts) }
                Log.i(LOG_TAG, "m3u export to a folder: $written of ${texts.size} written")
                if (one == null) {
                    say(exportedAll(resources, written, texts.size), written < texts.size)
                } else {
                    say(resources.getString(if (written == 1) R.string.m3u_exported else R.string.m3u_not_exported), written != 1)
                }
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        scope.handPicked(picked.map(Uri::toString), pending)
    }

    return remember(save, folder, open) {
        M3uActions(
            export = { playlist ->
                exporting.value = playlist.id
                if (M3u.exportsByFolder(Lyrify.writes)) folder.launch(null) else save.launch(M3u.fileName(playlist.name, untitled()))
            },
            exportAll = {
                scope.launch {
                    exporting.value = null
                    if (playlists.state.first().playlists.isEmpty()) say(resources.getString(R.string.m3u_no_playlists), false) else folder.launch(null)
                }
            },
            import = { open.launch(M3u.MIME_TYPES) },
        )
    }
}

/** A playlist's "⋯" row: Export. [close] closes the menu. */
@Composable
fun PlaylistExportRow(m3u: M3uActions, playlist: Playlist, close: () -> Unit) {
    MenuRow(
        stringResource(R.string.m3u_export),
        R.drawable.ic_upload,
        onClick = {
            close()
            m3u.export(playlist)
        },
    )
}

/** The Playlists tab's "⋯" rows: Export all and Import. [close] closes the menu. */
@Composable
fun PlaylistsM3uRows(m3u: M3uActions, close: () -> Unit) {
    MenuRow(
        stringResource(R.string.m3u_export_all),
        R.drawable.ic_upload,
        onClick = {
            close()
            m3u.exportAll()
        },
    )
    MenuRow(
        stringResource(R.string.m3u_import),
        R.drawable.ic_download,
        onClick = {
            close()
            m3u.import()
        },
    )
}

/** The toast after Export all: how many playlists were written, of how many. */
private fun exportedAll(resources: Resources, written: Int, total: Int): String = when {
    total == 0 -> resources.getString(R.string.m3u_no_playlists)
    written == total -> resources.getQuantityString(R.plurals.m3u_exported_all, written, formatCount(written))
    written == 0 -> resources.getString(R.string.m3u_not_exported)
    else -> resources.getString(R.string.m3u_exported_some, formatCount(written), formatCount(total))
}

/** The library's resolver once the library is read; one that finds nothing when it cannot be read. */
private suspend fun LibraryRepository.resolver(): SongResolver =
    (state.first { it !is LibraryState.Loading } as? LibraryState.Ready)?.library?.resolver ?: SongResolver(emptyList())
