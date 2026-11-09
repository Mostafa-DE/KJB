package app.lyrify.ui

import android.content.res.Resources
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.backup.M3u
import app.lyrify.backup.M3uDocuments
import app.lyrify.backup.M3uImport
import app.lyrify.backup.M3uRead
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryRepository
import app.lyrify.library.LibraryState
import app.lyrify.library.Playlist
import app.lyrify.library.SongResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Playlists: m3u import and export (milestone B1). L1's pages call these: the Playlists tab and a playlist's page each
// call rememberM3uActions; a playlist's "⋯" shows PlaylistExportRow, the Playlists tab's "⋯" PlaylistsM3uRows, and the
// Playlists tab places M3uImportSheet.

/** One picked file in the import sheet: what it found in the library, or why it could not be read. */
internal sealed interface ImportResult {
    val name: String

    data class Found(val import: M3uImport) : ImportResult {
        override val name: String get() = import.name
    }

    data class Failed(override val name: String, @StringRes val reason: Int) : ImportResult
}

/** [read] as the sheet shows it, named after its file ([untitled] when that leaves nothing): matched in [resolver], or why not. */
internal fun importResult(read: M3uRead, resolver: SongResolver, untitled: String): ImportResult {
    val name = M3u.playlistName(read.name) ?: untitled
    return when (read) {
        is M3uRead.Entries ->
            if (read.entries.isEmpty()) ImportResult.Failed(name, R.string.m3u_no_songs) else ImportResult.Found(M3u.match(name, read.entries, resolver, read.folder))
        is M3uRead.TooBig -> ImportResult.Failed(name, R.string.m3u_too_big)
        is M3uRead.Unreadable -> ImportResult.Failed(name, R.string.m3u_unreadable)
    }
}

/** The sheet lists this many missing songs, then "And 12 more". */
private const val MISSING_SHOWN: Int = 50

/**
 * Export, Export all and Import for one page ([rememberM3uActions]): the system's dialogs, the files, the toasts, and the
 * picked files' results, which [M3uImportSheet] shows one at a time.
 */
@Stable
class M3uActions internal constructor(
    /** A playlist's "⋯" > Export: the save dialog, then `Name.m3u8` there. */
    val export: (Playlist) -> Unit,
    /** The Playlists tab's "⋯" > Export all: a folder, then one file per playlist in it. */
    val exportAll: () -> Unit,
    /** The Playlists tab's "⋯" > Import: one or more `.m3u` or `.m3u8` files, then [M3uImportSheet]. */
    val import: () -> Unit,
    internal val results: State<List<ImportResult>>,
    internal val creating: State<Boolean>,
    /** "Create playlist" for the first result: named apart from the playlists there are, then the next result. */
    internal val create: (ImportResult.Found) -> Unit,
    /** Skip, Cancel or Close: the next result, if any. */
    internal val next: () -> Unit,
    /** The sheet swiped away: no more results. */
    internal val close: () -> Unit,
)

/**
 * [M3uActions] for a page. Call it on the page, not inside its menu, so the dialogs' answers still find it once the menu
 * has closed. Files are written on the IO dispatcher and finish even if the page closes meanwhile; the log gets counts
 * and exception names only.
 */
@Composable
fun rememberM3uActions(): M3uActions {
    val app = LocalContext.current.applicationContext
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val documents = remember(app) { M3uDocuments(app.contentResolver, Lyrify.writes) { Log.w(LOG_TAG, it) } }
    val playlists = remember(app) { LibraryGraph.playlists(app) }
    val library = remember(app) { LibraryGraph.repository(app) }
    val results = remember { mutableStateOf(emptyList<ImportResult>()) }
    val creating = remember { mutableStateOf(false) }
    // The playlist the save dialog is open for, kept if the activity is made again meanwhile.
    val exporting = rememberSaveable { mutableStateOf<String?>(null) }
    val say = { text: String, failed: Boolean -> scope.launch { toasts.show(if (failed) Toast(text, R.drawable.ic_error, Tone.ERROR) else Toast(text)) } }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(documents.mimeType)) { document ->
        val id = exporting.value
        exporting.value = null
        if (document != null && id != null) {
            scope.launch {
                val playlist = playlists.state.first().playlist(id)
                val text = playlist?.let { M3u.write(it.songs, library.resolver()) }
                val written = text != null && withContext(Dispatchers.IO + NonCancellable) { documents.write(document, text) }
                Log.i(LOG_TAG, "m3u export: ${if (written) "written" else "not written"}")
                say(resources.getString(if (written) R.string.m3u_exported else R.string.m3u_not_exported), !written)
            }
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            scope.launch {
                val resolver = library.resolver()
                val untitled = resources.getString(R.string.m3u_untitled)
                val files = playlists.state.first().playlists.map { M3u.fileName(it.name, untitled) to M3u.write(it.songs, resolver) }
                val written = withContext(Dispatchers.IO + NonCancellable) { documents.writeAll(tree, files) }
                Log.i(LOG_TAG, "m3u export all: $written of ${files.size} written")
                say(exportedAll(resources, written, files.size), written < files.size)
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            scope.launch {
                val read = withContext(Dispatchers.IO) { picked.map(documents::read) }
                val resolver = library.resolver()
                val untitled = resources.getString(R.string.m3u_untitled)
                val found = withContext(Dispatchers.Default) { read.map { importResult(it, resolver, untitled) } }
                val imports = found.filterIsInstance<ImportResult.Found>().map { it.import }
                Log.i(LOG_TAG, "m3u import: ${found.size} files, ${imports.sumOf { it.found.size }} of ${imports.sumOf { it.total }} songs found")
                results.value += found
            }
        }
    }

    return remember(save, folder, open) {
        M3uActions(
            export = { playlist ->
                exporting.value = playlist.id
                save.launch(M3u.fileName(playlist.name, resources.getString(R.string.m3u_untitled)))
            },
            exportAll = {
                scope.launch {
                    if (playlists.state.first().playlists.isEmpty()) say(resources.getString(R.string.m3u_no_playlists), false) else folder.launch(null)
                }
            },
            import = { open.launch(M3u.MIME_TYPES) },
            results = results,
            creating = creating,
            create = { result ->
                if (!creating.value) {
                    creating.value = true
                    scope.launch {
                        val made = try {
                            val taken = playlists.state.first().playlists.map { it.name }
                            playlists.create(M3u.uniqueName(result.import.name, taken), result.import.found.map { it.ref })
                        } finally {
                            creating.value = false
                        }
                        // A failure keeps the result in the sheet, to try again.
                        if (made != null) results.value -= result
                        say(resources.getString(if (made != null) R.string.m3u_created else R.string.m3u_not_created), made == null)
                    }
                }
            },
            next = { results.value = results.value.drop(1) },
            close = { results.value = emptyList() },
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

/**
 * The import result, one picked file at a time: its name, "18 of 20 songs found", the songs not found, then "Create
 * playlist" (when any was found) and Skip (more files wait), Cancel or Close. Shows nothing while there is no result.
 */
@Composable
fun M3uImportSheet(m3u: M3uActions) {
    val results by m3u.results
    val result = results.firstOrNull() ?: return
    val creating by m3u.creating
    val colors = MaterialTheme.colorScheme
    LyrifySheet(
        onDismiss = m3u.close,
        scroll = remember(result) { ScrollState(0) },
        footer = {
            if (result is ImportResult.Found) {
                LyrifyButton(
                    stringResource(R.string.m3u_create),
                    onClick = { m3u.create(result) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.ic_queue_music,
                    enabled = result.import.found.isNotEmpty() && !creating,
                )
            }
            val other = when {
                results.size > 1 -> R.string.m3u_skip
                result is ImportResult.Found -> R.string.m3u_cancel
                else -> R.string.m3u_close
            }
            LyrifyButton(stringResource(other), onClick = m3u.next, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        },
    ) {
        Text(result.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        when (result) {
            is ImportResult.Found -> {
                val import = result.import
                Text(
                    pluralStringResource(R.plurals.m3u_found, import.total, formatCount(import.found.size), formatCount(import.total)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
                if (import.missing.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.m3u_missing))
                    import.missing.take(MISSING_SHOWN).forEach { Fact(R.drawable.ic_search_off, it.label, middleEllipsis = true) }
                    if (import.missing.size > MISSING_SHOWN) {
                        Text(
                            stringResource(R.string.m3u_more_missing, formatCount(import.missing.size - MISSING_SHOWN)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            is ImportResult.Failed -> Text(stringResource(result.reason), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
    }
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
