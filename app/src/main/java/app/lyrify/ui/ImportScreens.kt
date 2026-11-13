package app.lyrify.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lyrify.R
import app.lyrify.backup.ImportFailure
import app.lyrify.backup.ImportFormats
import app.lyrify.backup.ImportKind
import app.lyrify.backup.ImportOutcome
import app.lyrify.backup.ImportPick
import app.lyrify.backup.ImportPlan
import app.lyrify.backup.ImportRead
import app.lyrify.backup.ImportSong
import app.lyrify.backup.Imports
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Import from another app (lane IM1): Settings > Backup > Import from app, and the one sheet it opens (pick, preview,
// import, summary with Undo), shown from here over Settings as the restore sheet is.

/** One check box row of the preview: a kind the import holds, and how many. */
internal data class ImportRow(val kind: ImportKind, val count: Int, val songs: Int? = null)

/** The preview's rows: each kind the import holds, in [ImportKind]'s order. */
internal fun importRows(plan: ImportPlan): List<ImportRow> =
    ImportKind.entries.mapNotNull { kind -> plan.counts[kind]?.let { ImportRow(kind, it, songs = plan.queueSongs.takeIf { kind == ImportKind.QUEUES }) } }

@StringRes
internal fun kindLabel(kind: ImportKind): Int = when (kind) {
    ImportKind.PLAYLISTS -> R.string.import_kind_playlists
    ImportKind.FAVOURITES -> R.string.import_kind_favourites
    ImportKind.PLAYS -> R.string.import_kind_plays
    ImportKind.QUEUES -> R.string.import_kind_queues
}

/** A file not read: its reason in a few words. */
@StringRes
internal fun failureTitle(why: ImportFailure): Int = when (why) {
    ImportFailure.NOT_RECOGNISED -> R.string.import_not_recognised
    ImportFailure.TOO_BIG -> R.string.import_too_big
    ImportFailure.UNREADABLE -> R.string.import_unreadable
    ImportFailure.EMPTY -> R.string.import_empty
    ImportFailure.MUSICOLET -> R.string.import_unreadable
}

/** The only file picked, not read: its reason as a sentence. */
@StringRes
internal fun failureText(why: ImportFailure): Int = when (why) {
    ImportFailure.NOT_RECOGNISED -> R.string.import_not_recognised_text
    ImportFailure.TOO_BIG -> R.string.import_too_big_text
    ImportFailure.UNREADABLE -> R.string.import_unreadable_text
    ImportFailure.EMPTY -> R.string.import_empty_text
    ImportFailure.MUSICOLET -> R.string.import_musicolet_text
}

/** "Copy list": each song not found on its own line, as the sheet lists it. */
internal fun missingList(missing: List<ImportSong>): String = missing.joinToString("\n") { it.label }

/** Where an import's files came from: the preview's title. */
private enum class ImportSource(@StringRes val found: Int) {
    FILE(R.string.import_found_file),
    FILES(R.string.import_found_files),
    FOLDER(R.string.import_found_folder),
    PHONE(R.string.import_found_phone),
}

/** What the import sheet shows. */
private sealed interface ImportSheet {
    /** Pick a file or a folder; [phone] playlists other apps saved on the phone, offered only when there are any. */
    data class Start(val phone: Int = 0) : ImportSheet

    data object Reading : ImportSheet

    /** What the pick holds, a check box per kind, the files not read and the songs not found; then Import now. */
    data class Preview(val source: ImportSource, val plan: ImportPlan, val failed: List<ImportRead.Failed>, val chosen: Set<ImportKind>, val importing: Boolean = false) : ImportSheet

    /** Nothing could be read: each file and why; [none] when a folder held no playlist file at all. */
    data class Failed(val failed: List<ImportRead.Failed>, val none: Boolean) : ImportSheet

    /** What the import did and the songs not found, with Copy list and Undo; [undoFailed] after an Undo that failed. */
    data class Done(val outcome: ImportOutcome, val missing: List<ImportSong>, val undoing: Boolean = false, val undoFailed: Boolean = false) : ImportSheet
}

/**
 * The summary while it is open, kept for the process: a turn of the phone makes the activity again, and the summary
 * holds the only Undo of the import.
 */
private object ShownSummary {
    var done: ImportSheet.Done? = null
}

/** The sheet lists this many songs or files of a kind, then "And 12 more". */
private const val SHOWN: Int = 50

/** What was picked, kept if the activity is made again: the kind, then the documents. */
private const val PICKED_FILES: String = "files"
private const val PICKED_FOLDER: String = "folder"
private const val PICKED_PHONE: String = "phone"

/**
 * Settings > Backup > Import from app ([BackupActions.importFromApp]): opens the import sheet, shown from here over
 * Settings. Pick file (one or more, any type: the content says what it is) or Pick folder, or the playlists on your
 * phone; then the preview, Import now, and the summary with Undo. The pick is read again if the activity is made
 * again; an import or an Undo finishes even if Settings closes. The log gets counts only.
 */
@Composable
fun rememberImportFromApp(): () -> Unit {
    val app = LocalContext.current.applicationContext
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val sheet = remember { mutableStateOf<ImportSheet?>(ShownSummary.done) }
    val picked = rememberSaveable { mutableStateOf(emptyList<String>()) }
    val say = { text: Int, failed: Boolean ->
        scope.launch { toasts.show(if (failed) Toast(resources.getString(text), R.drawable.ic_error, Tone.ERROR) else Toast(resources.getString(text))) }
    }
    val load = { what: List<String> ->
        picked.value = what
        sheet.value = ImportSheet.Reading
        scope.launch {
            val documents = what.drop(1).map(String::toUri)
            val (source, pick) = when (what.first()) {
                PICKED_FOLDER -> ImportSource.FOLDER to Imports.folder(app, documents.single())
                PICKED_PHONE -> ImportSource.PHONE to Imports.phone(app)
                else -> (if (documents.size == 1) ImportSource.FILE else ImportSource.FILES) to Imports.files(app, documents)
            }
            sheet.value = loaded(source, pick, app)
        }
    }
    LaunchedEffect(Unit) {
        if (sheet.value == null && picked.value.isNotEmpty()) load(picked.value)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { documents ->
        if (documents.isNotEmpty()) load(listOf(PICKED_FILES) + documents.map(Uri::toString).distinct())
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { load(listOf(PICKED_FOLDER, it.toString())) }
    }
    val close = {
        sheet.value = null
        ShownSummary.done = null
        picked.value = emptyList()
    }
    val show = { done: ImportSheet.Done ->
        ShownSummary.done = done
        sheet.value = done
    }

    ImportSheetView(
        sheet,
        onPickFile = { files.launch(ImportFormats.MIME_TYPES) },
        onPickFolder = { folder.launch(null) },
        onPhone = { load(listOf(PICKED_PHONE)) },
        onImport = { preview ->
            sheet.value = preview.copy(importing = true)
            scope.launch {
                val reserved = listOf(R.string.lib_favourites, R.string.lib_most_played, R.string.lib_recently_played, R.string.lib_recently_added).map(resources::getString).toSet()
                val named = { name: String? -> name?.let { resources.getString(R.string.import_queue_named, it) } ?: resources.getString(R.string.import_queue_name) }
                val done = withContext(NonCancellable) {
                    // Kept for the process at once, so the summary and its Undo outlive a turn of the phone meanwhile.
                    Imports.apply(app, preview.plan, preview.chosen, resources.getString(R.string.m3u_untitled), named, reserved)
                        ?.let { ImportSheet.Done(it, preview.plan.missing(preview.chosen)).also { done -> ShownSummary.done = done } }
                }
                if (done == null) {
                    sheet.value = preview
                    say(R.string.import_failed, true)
                } else {
                    picked.value = emptyList()
                    show(done)
                    if (!done.outcome.complete) say(R.string.import_failed, true)
                }
            }
        },
        onUndo = { done ->
            sheet.value = done.copy(undoing = true)
            scope.launch {
                val undone = withContext(NonCancellable) { Imports.undo(app, done.outcome.undo) }
                if (undone) close() else show(done.copy(undoFailed = true))
                say(if (undone) R.string.import_undone else R.string.import_not_undone, !undone)
            }
        },
        onCopy = { missing ->
            copy(app, resources.getString(R.string.import_copy), missingList(missing))
            // Android 13 and later show their own note when something is copied.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) say(R.string.import_copied, false)
        },
        onClose = close,
    )

    return remember(files, folder) {
        {
            sheet.value = ImportSheet.Start()
            scope.launch {
                val phone = Imports.phoneCount(app)
                if (phone > 0 && sheet.value == ImportSheet.Start()) sheet.value = ImportSheet.Start(phone)
            }
        }
    }
}

/** What a pick read becomes: the preview when anything was read, else why nothing was. */
private suspend fun loaded(source: ImportSource, pick: ImportPick?, app: Context): ImportSheet {
    if (pick == null) return ImportSheet.Failed(emptyList(), none = true)
    if (pick.read == 0 || pick.bundle.size == 0) return ImportSheet.Failed(pick.failed, none = pick.failed.isEmpty())
    val plan = Imports.plan(app, pick.bundle)
    return ImportSheet.Preview(source, plan, pick.failed, plan.defaultChosen())
}

private fun copy(app: Context, label: String, text: String) {
    app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The import sheet as [state] says, or nothing. */
@Composable
private fun ImportSheetView(
    state: MutableState<ImportSheet?>,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onPhone: () -> Unit,
    onImport: (ImportSheet.Preview) -> Unit,
    onUndo: (ImportSheet.Done) -> Unit,
    onCopy: (List<ImportSong>) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    when (val sheet = state.value ?: return) {
        is ImportSheet.Start -> LyrifySheet(
            onDismiss = onClose,
            footer = {
                LyrifyButton(stringResource(R.string.import_pick_file), onClick = onPickFile, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_description)
                LyrifyButton(stringResource(R.string.import_pick_folder), onClick = onPickFolder, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_folder_open)
            },
        ) {
            Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.import_intro), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            if (sheet.phone > 0) {
                SectionTitle(stringResource(R.string.import_phone_title))
                Text(pluralStringResource(R.plurals.import_phone_text, sheet.phone, formatCount(sheet.phone)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                LyrifyButton(stringResource(R.string.import_phone_button), onClick = onPhone, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_queue_music)
            }
            SectionTitle(stringResource(R.string.import_where_title))
            listOf(R.string.import_where_samsung, R.string.import_where_poweramp, R.string.import_where_musicolet, R.string.import_where_aimp, R.string.import_where_others)
                .forEach { Fact(R.drawable.ic_info, stringResource(it)) }
        }
        ImportSheet.Reading -> LyrifySheet(onDismiss = onClose) {
            Text(stringResource(R.string.import_reading), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        is ImportSheet.Preview -> {
            val rows = remember(sheet.plan) { importRows(sheet.plan) }
            val missing = remember(sheet.plan, sheet.chosen) { sheet.plan.missing(sheet.chosen) }
            LyrifySheet(
                onDismiss = { if (!sheet.importing) onClose() },
                footer = {
                    LyrifyButton(
                        stringResource(R.string.import_now),
                        onClick = { onImport(sheet) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = R.drawable.ic_download,
                        enabled = sheet.chosen.isNotEmpty() && !sheet.importing,
                    )
                    LyrifyButton(stringResource(R.string.import_cancel), onClick = onClose, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = !sheet.importing)
                },
            ) {
                Text(stringResource(sheet.source.found), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                rows.forEach { row ->
                    KindRow(row, checked = row.kind in sheet.chosen, enabled = !sheet.importing) { on ->
                        state.value = sheet.copy(chosen = if (on) sheet.chosen + row.kind else sheet.chosen - row.kind)
                    }
                }
                if (sheet.failed.isNotEmpty()) NotRead(sheet.failed)
                if (missing.isNotEmpty()) NotFound(missing)
                Text(stringResource(R.string.import_merge), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        is ImportSheet.Failed -> LyrifySheet(
            onDismiss = onClose,
            footer = { LyrifyButton(stringResource(R.string.import_close), onClick = onClose, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY) },
        ) {
            val only = sheet.failed.singleOrNull()
            when {
                sheet.none || sheet.failed.isEmpty() -> {
                    Text(stringResource(R.string.import_none), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.import_none_text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                only != null -> {
                    Text(stringResource(failureTitle(only.why)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(failureText(only.why)), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                else -> NotRead(sheet.failed, title = true)
            }
        }
        is ImportSheet.Done -> LyrifySheet(
            onDismiss = { if (!sheet.undoing) onClose() },
            footer = {
                if (sheet.outcome.changed) {
                    LyrifyButton(stringResource(R.string.import_undo), onClick = { onUndo(sheet) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_undo, enabled = !sheet.undoing)
                }
                LyrifyButton(stringResource(R.string.import_close), onClick = onClose, modifier = Modifier.fillMaxWidth(), enabled = !sheet.undoing)
            },
        ) {
            val outcome = sheet.outcome
            val title = when {
                !outcome.complete -> stringResource(R.string.import_failed)
                !outcome.changed -> stringResource(R.string.import_nothing_new)
                outcome.playlists > 0 -> pluralStringResource(R.plurals.import_done, outcome.playlists, formatCount(outcome.playlists))
                else -> stringResource(R.string.import_done_other)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (!outcome.complete) Text(stringResource(R.string.import_partly_text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            if (!outcome.changed) Text(stringResource(R.string.import_nothing_new_text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            if (sheet.undoFailed) Text(stringResource(R.string.import_not_undone_text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            listOf(
                R.string.import_done_created to outcome.created,
                R.string.import_done_songs to outcome.songsAdded,
                R.string.import_done_favourites to outcome.favouritesAdded,
                R.string.import_done_plays to outcome.countsRaised,
                R.string.import_done_queues to outcome.queuesAdded,
            ).filter { it.second > 0 }.forEach { (label, count) -> CountLine(stringResource(label), count) }
            if (sheet.missing.isNotEmpty()) {
                NotFound(sheet.missing)
                LyrifyButton(stringResource(R.string.import_copy), onClick = { onCopy(sheet.missing) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_content_copy)
            }
        }
    }
}

/** A kind's row: its check box, its name and its count, 48 dp tall at least. */
@Composable
private fun KindRow(row: ImportRow, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
    ) {
        LyrifyCheckbox(checked)
        Column(Modifier.weight(1f)) {
            Text(stringResource(kindLabel(row.kind)), style = MaterialTheme.typography.bodyLarge)
            row.songs?.let { songs ->
                Text(pluralStringResource(R.plurals.import_queue_songs, songs, formatCount(songs)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(formatCount(row.count), style = MaterialTheme.typography.bodyLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

/** What the import did of one kind, 48 dp tall at least. */
@Composable
private fun CountLine(label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(formatCount(count), style = MaterialTheme.typography.bodyLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

/** "2 files not read" (as the sheet's [title] when nothing else was read), then each file and why. */
@Composable
private fun NotRead(failed: List<ImportRead.Failed>, title: Boolean = false) {
    val heading = pluralStringResource(R.plurals.import_not_read, failed.size, formatCount(failed.size))
    if (title) Text(heading, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) else SectionTitle(heading)
    val untitled = stringResource(R.string.m3u_untitled)
    failed.take(SHOWN).forEach { file ->
        Fact(R.drawable.ic_error, stringResource(R.string.import_failed_line, file.name ?: untitled, stringResource(failureTitle(file.why))), middleEllipsis = true)
    }
    More(failed.size)
}

/** "12 songs not found on your phone", why, then the first [SHOWN] of them. */
@Composable
private fun NotFound(missing: List<ImportSong>) {
    SectionTitle(pluralStringResource(R.plurals.import_not_found, missing.size, formatCount(missing.size)))
    Text(stringResource(R.string.import_not_found_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    missing.take(SHOWN).forEach { Fact(R.drawable.ic_search_off, it.label, middleEllipsis = true) }
    More(missing.size)
}

/** "And 12 more" past the first [SHOWN] of [count]. */
@Composable
private fun More(count: Int) {
    if (count > SHOWN) Text(stringResource(R.string.import_more, formatCount(count - SHOWN)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
