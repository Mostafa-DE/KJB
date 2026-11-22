package app.lyrify.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
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
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.backup.AutoBackupWorker
import app.lyrify.backup.BackupBundle
import app.lyrify.backup.BackupContents
import app.lyrify.backup.BackupDocuments
import app.lyrify.backup.BackupRead
import app.lyrify.backup.Backups
import app.lyrify.backup.MissingSong
import app.lyrify.backup.RestorePlan
import app.lyrify.backup.RestoreResult
import app.lyrify.backup.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Backup and restore (milestone B2): Settings > Backup's actions, which Settings calls as M1 declared them, and the
// restore summary they show.

/** One row of the restore summary: a section the backup holds, with its check box; [count] beside its name, [more] under it. */
internal data class RestoreRow(val section: Section, val count: Int?, val more: Int?)

/** The summary's rows: each section the backup holds, in its order, with what it counts ("Playlists 6", "And 120 favourites"). */
internal fun restoreRows(contents: BackupContents): List<RestoreRow> = listOfNotNull(
    contents.playlists?.let { RestoreRow(Section.PLAYLISTS, it.playlists.size, (it.favouriteSongs.size + it.favouriteCollections.size).takeIf { n -> n > 0 }) },
    contents.queues?.let { book -> RestoreRow(Section.QUEUES, book.queues.size, null) },
    contents.plays?.let { plays -> RestoreRow(Section.PLAYS, plays.count { !it.isShort }, null) },
    contents.bookmarks?.let { songs -> RestoreRow(Section.BOOKMARKS, songs.sumOf { it.bookmarks.size }, songs.count { it.placeMs != null }.takeIf { it > 0 }) },
    contents.equalizer?.let { RestoreRow(Section.EQUALIZER, it.profiles.size, it.presets.size.takeIf { n -> n > 0 }) },
    contents.lyrics?.let { RestoreRow(Section.LYRICS, it.entries.size, null) },
    contents.settings?.let { RestoreRow(Section.SETTINGS, null, null) },
    contents.tones?.let { RestoreRow(Section.TONES, it.size, null) },
    contents.duplicates?.let { RestoreRow(Section.DUPLICATES, it.notDuplicates.size, null) },
    contents.covers?.let { RestoreRow(Section.COVERS, it.albums.size, null) },
    contents.names?.let { RestoreRow(Section.NAMES, it.size, null) },
    contents.searches?.let { RestoreRow(Section.SEARCHES, it.size, null) },
    contents.books?.let { RestoreRow(Section.BOOKS, it.books.size, null) },
    // Time in books counts nothing: its count would be of listening sessions.
    contents.bookPlays?.let { RestoreRow(Section.BOOK_PLAYS, null, null) },
)

@StringRes
internal fun sectionLabel(section: Section): Int = when (section) {
    Section.PLAYLISTS -> R.string.restore_section_playlists
    Section.QUEUES -> R.string.restore_section_queues
    Section.PLAYS -> R.string.restore_section_plays
    Section.BOOKMARKS -> R.string.restore_section_bookmarks
    Section.EQUALIZER -> R.string.restore_section_equalizer
    Section.LYRICS -> R.string.restore_section_lyrics
    Section.SETTINGS -> R.string.restore_section_settings
    Section.TONES -> R.string.restore_section_tones
    Section.DUPLICATES -> R.string.restore_section_duplicates
    Section.COVERS -> R.string.restore_section_covers
    Section.NAMES -> R.string.restore_section_names
    Section.SEARCHES -> R.string.restore_section_searches
    Section.BOOKS -> R.string.restore_section_books
    Section.BOOK_PLAYS -> R.string.restore_section_bookPlays
}

/** Whether a restore brought back all it was asked to: else the summary after it is "Partly restored". */
internal fun complete(result: RestoreResult): Boolean = result.failed.isEmpty() && result.placesLeftOut == 0 && result.bookmarksLeftOut == 0

/** The summary's lines after a restore: each section restored with what it brought back (Settings and Time in books count nothing). */
internal fun restoredRows(result: RestoreResult): List<Pair<Section, Int?>> =
    result.restored.map { section -> section to result.counts[section].takeUnless { section == Section.SETTINGS || section == Section.BOOK_PLAYS } }

/** The line under a row: favourites, places to resume, own presets. */
@PluralsRes
private fun sectionMore(section: Section): Int? = when (section) {
    Section.PLAYLISTS -> R.plurals.restore_favourites
    Section.BOOKMARKS -> R.plurals.restore_places
    Section.EQUALIZER -> R.plurals.restore_presets
    else -> null
}

/** The summary lists this many songs not found, then "And 12 more". */
private const val SHOWN: Int = 50

/** What the restore sheet shows: the summary of a picked backup, or, after the restore, what came back and what did not. */
private sealed interface RestoreSheet {
    data class Summary(val plan: RestorePlan, val chosen: Set<Section>, val restoring: Boolean = false) : RestoreSheet

    /** [missing]: the songs of the backup not on this phone; [ai]: the AI lyrics came back on, so their models download again. */
    data class Done(val result: RestoreResult, val missing: List<MissingSong>, val ai: Boolean) : RestoreSheet
}

/**
 * Settings > Backup's actions, and the restore sheet they open (shown from here, over whatever is on screen):
 * - Back up now: the save dialog with `KJB backup <date>.zip`; a debug build picks a folder instead, checked before any
 *   file exists there ([BackupDocuments.byFolder]).
 * - Restore: a zip from the open dialog, read whole and checked, then the summary with a check box per section; a file
 *   that is not a backup says "Couldn't read this backup" and changes nothing.
 * - Automatic backup: on asks for a folder (kept with a persisted permission, [setAutomatic] stores the switch), off lets
 *   it go. A folder KJB can no longer reach turns it off, with a message.
 * Backups and restores finish even if Settings closes; the picked backup is read again if the activity is made again.
 * The log gets counts and section names only.
 */
@Composable
fun rememberBackupActions(setAutomatic: (Boolean) -> Unit): BackupActions {
    val app = LocalContext.current.applicationContext
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val documents = remember(app) { BackupDocuments(app.contentResolver, Lyrify.writes) { Log.w(LOG_TAG, it) } }
    val settings by remember(app) { Lyrify.settings(app).settings }.collectAsStateWithLifecycle(null)
    val picked = rememberSaveable { mutableStateOf<String?>(null) }
    val sheet = remember { mutableStateOf<RestoreSheet?>(null) }
    val say = { text: Int, failed: Boolean ->
        scope.launch { toasts.show(if (failed) Toast(resources.getString(text), R.drawable.ic_error, Tone.ERROR) else Toast(resources.getString(text))) }
    }
    val backUp = { write: suspend (ByteArray) -> Boolean ->
        scope.launch {
            val saved = withContext(Dispatchers.IO + NonCancellable) { write(Backups.make(app)) }
            Log.i(LOG_TAG, "backup: ${if (saved) "saved" else "not saved"}")
            say(if (saved) R.string.backup_saved else R.string.backup_not_saved, !saved)
        }
    }
    val load = { document: String ->
        scope.launch {
            when (val read = Backups.read(app, document.toUri())) {
                is BackupRead.Unreadable -> {
                    picked.value = null
                    say(R.string.backup_unreadable, true)
                }
                is BackupRead.Read -> sheet.value = RestoreSheet.Summary(Backups.plan(app, read.contents), read.contents.sections.toSet())
            }
        }
    }
    val turnOff = {
        setAutomatic(false)
        scope.launch {
            val folder = settings?.backup?.folderUri
            Lyrify.settings(app).update { it.copy(backup = it.backup.copy(automatic = false, folderUri = null)) }
            folder?.let { release(app, it) }
            AutoBackupWorker.cancel(app)
        }
    }
    LaunchedEffect(Unit) {
        val document = picked.value
        if (document != null && sheet.value == null) load(document)
    }
    // A folder KJB can no longer reach (its permission taken back in the phone's settings) turns automatic backup off.
    val backup = settings?.backup
    LaunchedEffect(backup) {
        if (backup != null && backup.automatic && !documents.reachable(backup.folderUri)) {
            Log.w(LOG_TAG, "auto backup: folder out of reach, turned off")
            turnOff()
            say(R.string.backup_auto_lost, true)
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupBundle.MIME_TYPE)) { document ->
        document?.let { backUp { bytes -> documents.write(it, bytes) } }
    }
    val saveIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { backUp { bytes -> documents.create(it, BackupBundle.fileName(LocalDate.now()), bytes) } }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { document ->
        document?.toString()?.let {
            picked.value = it
            load(it)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            scope.launch {
                val held = try {
                    app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    true
                } catch (_: SecurityException) {
                    false
                }
                if (!held || !documents.allowsFolder(tree, BackupBundle.fileName(LocalDate.now()))) {
                    if (held) release(app, tree.toString())
                    say(R.string.backup_folder_refused, true)
                } else if (Lyrify.settings(app).update { it.copy(backup = it.backup.copy(folderUri = tree.toString())) }) {
                    setAutomatic(true)
                    AutoBackupWorker.schedule(app)
                    Log.i(LOG_TAG, "auto backup: on")
                }
            }
        }
    }

    RestoreSheet(
        sheet,
        onRestore = { summary ->
            sheet.value = summary.copy(restoring = true)
            scope.launch {
                val result = withContext(NonCancellable) { Backups.restore(app, summary.plan, summary.chosen) }
                val missing = withContext(Dispatchers.Default) { summary.plan.missing(summary.chosen) }
                val ai = Section.SETTINGS in result.restored && summary.plan.contents.aiLyrics == true
                picked.value = null
                sheet.value = RestoreSheet.Done(result, missing, ai)
            }
        },
        onClose = {
            sheet.value = null
            picked.value = null
        },
    )

    val importFromApp = rememberImportFromApp()
    return remember(save, saveIn, open, folder, importFromApp) {
        BackupActions(
            backUpNow = { if (documents.byFolder) saveIn.launch(null) else save.launch(BackupBundle.fileName(LocalDate.now())) },
            restore = { open.launch(BackupBundle.MIME_TYPES) },
            setAutomatic = { on -> if (on) folder.launch(null) else turnOff() },
            importFromApp = importFromApp,
        )
    }
}

/** Lets go of the persisted permission on the folder [tree]; one already gone is no matter. */
private fun release(app: Context, tree: String) {
    try {
        app.contentResolver.releasePersistableUriPermission(tree.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Not held: nothing to let go.
    }
}

/**
 * The restore sheet: the summary (when the backup was made, a check box per section with what it holds, the parts a
 * newer KJB made, the songs not on this phone, what merges and what replaces, and that Undo history isn't included),
 * then Restore; then, after the restore, one summary of what came back (each part with its count), the songs not found
 * on this phone, the tones whose files aren't here, the devices to pick again, the parts that failed and the places and
 * bookmarks the limits left out. Shows nothing otherwise.
 */
@Composable
private fun RestoreSheet(state: MutableState<RestoreSheet?>, onRestore: (RestoreSheet.Summary) -> Unit, onClose: () -> Unit) {
    val sheet = state.value ?: return
    val colors = MaterialTheme.colorScheme
    when (sheet) {
        is RestoreSheet.Done -> LyrifySheet(
            onDismiss = onClose,
            footer = { LyrifyButton(stringResource(R.string.restore_close), onClick = onClose, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY) },
        ) {
            val result = sheet.result
            Text(
                stringResource(if (complete(result)) R.string.restore_done else R.string.restore_partly),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            restoredRows(result).forEach { (section, count) -> CountRow(stringResource(sectionLabel(section)), count) }
            if (sheet.missing.isNotEmpty()) MissingSongs(sheet.missing, after = true)
            if (result.tonesNotFound > 0) Fact(R.drawable.ic_info, pluralStringResource(R.plurals.restore_tones_missing, result.tonesNotFound, formatCount(result.tonesNotFound)))
            if (result.pickAgain.isNotEmpty()) Fact(R.drawable.ic_info, stringResource(R.string.restore_pick_again, result.pickAgain.joinToString(", ")))
            if (sheet.ai) Fact(R.drawable.ic_info, stringResource(R.string.restore_ai_models))
            if (result.failed.isNotEmpty()) {
                val names = result.failed.map { stringResource(sectionLabel(it)) }.joinToString(", ")
                Text(stringResource(R.string.restore_failed, names), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            if (result.placesLeftOut > 0) {
                Text(pluralStringResource(R.plurals.restore_places_full, result.placesLeftOut, formatCount(result.placesLeftOut)), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            if (result.bookmarksLeftOut > 0) {
                Text(pluralStringResource(R.plurals.restore_bookmarks_full, result.bookmarksLeftOut, formatCount(result.bookmarksLeftOut)), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
            Text(stringResource(R.string.restore_undo), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        is RestoreSheet.Summary -> {
            val contents = sheet.plan.contents
            val rows = remember(contents) { restoreRows(contents) }
            val missing = remember(sheet.plan, sheet.chosen) { sheet.plan.missing(sheet.chosen) }
            LyrifySheet(
                onDismiss = { if (!sheet.restoring) onClose() },
                footer = {
                    if (rows.isNotEmpty()) {
                        LyrifyButton(
                            stringResource(R.string.restore_button),
                            onClick = { onRestore(sheet) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = R.drawable.ic_settings_backup_restore,
                            enabled = sheet.chosen.isNotEmpty() && !sheet.restoring,
                        )
                    }
                    LyrifyButton(stringResource(R.string.restore_cancel), onClick = onClose, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = !sheet.restoring)
                },
            ) {
                Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                val made = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(contents.createdAt).atZone(ZoneId.systemDefault()))
                Text(stringResource(R.string.restore_made, made, contents.appVersion), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (rows.isEmpty()) Text(stringResource(R.string.restore_nothing), style = MaterialTheme.typography.bodyLarge)
                rows.forEach { row ->
                    SectionRow(row, checked = row.section in sheet.chosen, enabled = !sheet.restoring) { on ->
                        state.value = sheet.copy(chosen = if (on) sheet.chosen + row.section else sheet.chosen - row.section)
                    }
                }
                contents.tooNew.forEach { Fact(R.drawable.ic_info, stringResource(R.string.restore_too_new, stringResource(sectionLabel(it)))) }
                if (contents.unknown > 0) Fact(R.drawable.ic_info, pluralStringResource(R.plurals.restore_unknown, contents.unknown, formatCount(contents.unknown)))
                if (missing.isNotEmpty()) MissingSongs(missing)
                Text(stringResource(R.string.restore_merge), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(stringResource(R.string.restore_undo), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A section's row: its check box, its name, what it counts, and the line under it. 48 dp tall at least. */
@Composable
private fun SectionRow(row: RestoreRow, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
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
            Text(stringResource(sectionLabel(row.section)), style = MaterialTheme.typography.bodyLarge)
            val more = sectionMore(row.section)
            if (more != null && row.more != null) {
                Text(pluralStringResource(more, row.more, formatCount(row.more)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        row.count?.let { Text(formatCount(it), style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.onSurfaceVariant, textAlign = TextAlign.End) }
    }
}

/** A section restored and what it brought back, 48 dp tall at least. */
@Composable
private fun CountRow(label: String, count: Int?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        count?.let { Text(formatCount(it), style = MaterialTheme.typography.bodyLarge.tabular(), color = colors.onSurfaceVariant, textAlign = TextAlign.End) }
    }
}

/**
 * "Not found": how many songs of the backup aren't on this phone (before a restore, what it will leave out; [after] it,
 * "12 songs not found on your phone" and how to bring them back), then the first [SHOWN] of them and "And 12 more".
 */
@Composable
private fun MissingSongs(missing: List<MissingSong>, after: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    if (after) {
        SectionTitle(pluralStringResource(R.plurals.restore_not_found, missing.size, formatCount(missing.size)))
        Text(stringResource(R.string.restore_not_found_text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    } else {
        SectionTitle(stringResource(R.string.restore_missing_title))
        Text(pluralStringResource(R.plurals.restore_missing, missing.size, formatCount(missing.size)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
    missing.take(SHOWN).forEach { song ->
        val label = song.title?.let { title -> listOfNotNull(title, song.artist).joinToString(" · ") } ?: song.path.substringAfterLast('/')
        Fact(R.drawable.ic_search_off, label, middleEllipsis = true)
    }
    if (missing.size > SHOWN) Text(stringResource(R.string.restore_more, formatCount(missing.size - SHOWN)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
}
