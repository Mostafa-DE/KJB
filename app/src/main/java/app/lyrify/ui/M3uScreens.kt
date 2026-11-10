package app.lyrify.ui

import android.content.res.Resources
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

/** One picked file in the import sheet, by its [document] (its URI): what it found in the library, or why it could not be read. */
internal sealed interface ImportResult {
    val document: String
    val name: String

    data class Found(override val document: String, val import: M3uImport) : ImportResult {
        override val name: String get() = import.name
    }

    data class Failed(override val document: String, override val name: String, @StringRes val reason: Int) : ImportResult
}

/** [read] as the sheet shows it, named after its file ([untitled] when that leaves nothing): matched in [resolver], or why not. */
internal fun importResult(document: String, read: M3uRead, resolver: SongResolver, untitled: String): ImportResult {
    val name = M3u.playlistName(read.name) ?: untitled
    return when (read) {
        is M3uRead.Entries ->
            if (read.entries.isEmpty()) ImportResult.Failed(document, name, R.string.m3u_no_songs) else ImportResult.Found(document, M3u.match(name, read.entries, resolver, read.folder))
        is M3uRead.TooBig -> ImportResult.Failed(document, name, R.string.m3u_too_big)
        is M3uRead.Unreadable -> ImportResult.Failed(document, name, R.string.m3u_unreadable)
        is M3uRead.NotM3u -> ImportResult.Failed(document, name, R.string.m3u_not_m3u)
    }
}

/** The sheet lists this many songs of a kind (missing, or matched by name), then "And 12 more". */
private const val SHOWN: Int = 50

/**
 * Export, Export all and Import for one page ([rememberM3uActions]): the system's dialogs, the files, the toasts, and the
 * picked files' results, which [M3uImportSheet] shows one at a time.
 */
@Stable
class M3uActions internal constructor(
    /** A playlist's "⋯" > Export: the save dialog (a debug build: a folder), then `Name.m3u8` there. */
    val export: (Playlist) -> Unit,
    /** The Playlists tab's "⋯" > Export all: a folder, then one file per playlist in it. */
    val exportAll: () -> Unit,
    /** The Playlists tab's "⋯" > Import: one or more `.m3u` or `.m3u8` files, then [M3uImportSheet]. */
    val import: () -> Unit,
    internal val results: State<List<ImportResult>>,
    internal val creating: State<Boolean>,
    /**
     * "Create playlist" for the first result, with or without the songs matched by name only: named apart from the
     * playlists there are, then the next result.
     */
    internal val create: (ImportResult.Found, Boolean) -> Unit,
    /** Skip, Cancel or Close: the next result, if any. */
    internal val next: () -> Unit,
    /** The sheet swiped away: no more results. */
    internal val close: () -> Unit,
)

/**
 * [M3uActions] for a page. Call it on the page, not inside its menu, so the dialogs' answers still find it once the menu
 * has closed. Files are written on the IO dispatcher and finish even if the page closes meanwhile. The picked files
 * not answered yet are kept if the activity is made again (a turn of the phone) and read again then. The log gets counts
 * and exception names only.
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
    // The picked files not answered yet, by URI, in the order picked; their results, read from them.
    val pending = rememberSaveable { mutableStateOf(emptyList<String>()) }
    val results = remember { mutableStateOf(emptyList<ImportResult>()) }
    val creating = remember { mutableStateOf(false) }
    // The playlist the save dialog or the folder is open for (none: Export all), kept if the activity is made again.
    val exporting = rememberSaveable { mutableStateOf<String?>(null) }
    val untitled = { resources.getString(R.string.m3u_untitled) }
    val say = { text: String, failed: Boolean -> scope.launch { toasts.show(if (failed) Toast(text, R.drawable.ic_error, Tone.ERROR) else Toast(text)) } }
    val load = { documents: List<String> ->
        scope.launch {
            val read = withContext(Dispatchers.IO) { documents.map { it to files.read(Uri.parse(it)) } }
            val resolver = library.resolver()
            val fresh = withContext(Dispatchers.Default) { read.map { (document, file) -> importResult(document, file, resolver, untitled()) } }
            val imports = fresh.filterIsInstance<ImportResult.Found>().map { it.import }
            Log.i(LOG_TAG, "m3u import: ${fresh.size} files, ${imports.sumOf { it.found.size }} of ${imports.sumOf { it.total }} songs found")
            // Once each, in the order picked, and only those not answered meanwhile.
            val known = results.value.map { it.document }.toSet()
            val order = pending.value
            results.value = (results.value + fresh.filter { it.document !in known }).filter { it.document in order }.sortedBy { order.indexOf(it.document) }
        }
    }
    LaunchedEffect(Unit) {
        if (results.value.isEmpty() && pending.value.isNotEmpty()) load(pending.value)
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
        val added = picked.map(Uri::toString).distinct().filter { it !in pending.value }
        if (added.isNotEmpty()) {
            pending.value += added
            load(added)
        }
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
            results = results,
            creating = creating,
            create = { result, includeByName ->
                if (!creating.value) {
                    creating.value = true
                    scope.launch {
                        val made = try {
                            val taken = playlists.state.first().playlists.map { it.name }
                            playlists.create(M3u.uniqueName(result.import.name, taken), result.import.songs(includeByName).map { it.ref })
                        } finally {
                            creating.value = false
                        }
                        // A failure keeps the result in the sheet, to try again.
                        if (made != null) {
                            results.value -= result
                            pending.value -= result.document
                        }
                        say(resources.getString(if (made != null) R.string.m3u_created else R.string.m3u_not_created), made == null)
                    }
                }
            },
            next = {
                results.value.firstOrNull()?.let { pending.value -= it.document }
                results.value = results.value.drop(1)
            },
            close = {
                results.value = emptyList()
                pending.value = emptyList()
            },
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
 * The import result, one picked file at a time: its name, "18 of 20 songs found", the songs matched by name only (each
 * listed, with "Include them" to leave them out), the songs not found, then "Create playlist" (when any song would go
 * in) and Skip (more files wait), Cancel or Close. Shows nothing while there is no result.
 */
@Composable
fun M3uImportSheet(m3u: M3uActions) {
    val results by m3u.results
    val result = results.firstOrNull() ?: return
    val creating by m3u.creating
    var includeByName by rememberSaveable(result.document) { mutableStateOf(true) }
    val colors = MaterialTheme.colorScheme
    LyrifySheet(
        onDismiss = m3u.close,
        scroll = remember(result) { ScrollState(0) },
        footer = {
            if (result is ImportResult.Found) {
                LyrifyButton(
                    stringResource(R.string.m3u_create),
                    onClick = { m3u.create(result, includeByName) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.ic_queue_music,
                    enabled = result.import.songs(includeByName).isNotEmpty() && !creating,
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
                val byName = import.byName
                if (byName.isNotEmpty()) {
                    SectionTitle(pluralStringResource(R.plurals.m3u_by_name, byName.size, formatCount(byName.size)))
                    Text(stringResource(R.string.m3u_by_name_text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    ShownSongs(byName.map { listOfNotNull(it.song.title, it.song.artist).joinToString(" · ") }, R.drawable.ic_music_note)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = includeByName, role = Role.Checkbox, onValueChange = { includeByName = it }),
                    ) {
                        LyrifyCheckbox(includeByName)
                        Text(stringResource(R.string.m3u_by_name_include), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (import.missing.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.m3u_missing))
                    ShownSongs(import.missing.map { it.label }, R.drawable.ic_search_off)
                }
            }
            is ImportResult.Failed -> Text(stringResource(result.reason), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
    }
}

/** The first [SHOWN] of [lines], one row each with [icon], then "And 12 more". */
@Composable
private fun ShownSongs(lines: List<String>, @DrawableRes icon: Int) {
    lines.take(SHOWN).forEach { Fact(icon, it, middleEllipsis = true) }
    if (lines.size > SHOWN) {
        Text(
            stringResource(R.string.m3u_more, formatCount(lines.size - SHOWN)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
