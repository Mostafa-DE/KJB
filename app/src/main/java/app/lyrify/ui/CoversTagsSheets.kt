package app.lyrify.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.CoversTags
import app.lyrify.tags.SearchOutcome
import app.lyrify.tags.Waiting
import app.lyrify.tags.coverFits
import app.lyrify.tags.lookup.Cover
import app.lyrify.tags.lookup.Field
import app.lyrify.tags.lookup.FieldChange
import app.lyrify.tags.lookup.ReleaseMatch
import kotlinx.coroutines.launch

// Covers & tags' sheets: the album sheet with its "old → new" lines and "Keep my year", Search myself, and what a Done
// album left as it was.

/**
 * The album sheet: the old and new covers side by side, the album's fields "old → new", one row per song that changes,
 * the "Keep my year" switch, then Apply, Not this one and Skip.
 */
@Composable
internal fun AlbumSheet(
    tags: CoversTags,
    waiting: Waiting,
    match: ReleaseMatch,
    access: Boolean,
    writable: Boolean,
    onApply: () -> Unit,
    onNotThisOne: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val changes = remember(waiting, match) { tags.changes(waiting, match) }
    val release = match.release
    val cover by produceState<Cover?>(null, release.id) { value = tags.cover(release) }
    val first = waiting.album.album.songs.firstOrNull()
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            LyrifyButton(stringResource(R.string.tags_apply), onClick = onApply, modifier = Modifier.fillMaxWidth(), enabled = writable)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LyrifyButton(stringResource(R.string.tags_not_this_one), onClick = onNotThisOne, modifier = Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                LyrifyButton(stringResource(R.string.tags_skip), onClick = onSkip, modifier = Modifier.weight(1f), kind = ButtonKind.SECONDARY)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(R.string.tags_old_cover to first?.let { SongArt(it.path, waiting.album.ids[it.path]) }, R.string.tags_new_cover to cover?.jpeg).forEach { (label, model) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SongCover(model, size = 120.dp, shape = MaterialTheme.shapes.large)
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column {
            Text(release.title, style = MaterialTheme.typography.titleLarge)
            Text(listOf(release.artist.text, releaseLine(release)).filter(String::isNotEmpty).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!access) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tags_no_access), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LyrifyButton(stringResource(R.string.tags_turn_on), onClick = { context.startFirst(notificationAccessIntents(context)) }, kind = ButtonKind.SECONDARY, compact = true)
            }
        }
        changes.album.forEach { ChangeLine(it) }
        // One row per song that changes; its year is the album's line.
        changes.songs.forEach { song ->
            val lines = song.changes.filter { it.field != Field.YEAR }
            if (lines.isEmpty()) return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${song.fields.track}. ${song.fields.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                lines.forEach { ChangeLine(it) }
            }
        }
        if (changes.unpaired.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.tags_unpaired, changes.unpaired.size, changes.unpaired.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val shown = cover
        if (shown != null && changes.songs.any { !coverFits(it.song.path, shown.jpeg.size) }) {
            Text(stringResource(R.string.tags_opus_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        KeepYearRow(waiting.keepMyYear) { tags.keepMyYear(waiting.album.id, it) }
    }
}

/** "Album: Lanterns → Lanterns of Ulso". */
@Composable
private fun ChangeLine(change: FieldChange) {
    val field = stringResource(
        when (change.field) {
            Field.TITLE -> R.string.tags_field_title
            Field.ARTIST -> R.string.tags_field_artist
            Field.ALBUM -> R.string.tags_field_album
            Field.ALBUM_ARTIST -> R.string.tags_field_album_artist
            Field.TRACK -> R.string.tags_field_track
            Field.DISC -> R.string.tags_field_disc
            Field.YEAR -> R.string.tags_field_year
        },
    )
    val old = change.old?.takeIf(String::isNotBlank) ?: stringResource(R.string.tags_empty_value)
    Text(
        stringResource(R.string.tags_two_parts, field, stringResource(R.string.tags_old_new, old, change.new)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The "Keep my year" switch: one TalkBack stop with the switch's role, its sentence under it. */
@Composable
private fun KeepYearRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, interactionSource = source, indication = LocalIndication.current, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tags_keep_year), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.tags_keep_year_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LyrifySwitch(checked, pressed = pressed)
    }
}

/** "Search myself": the album and artist fields, then the search; what it finds takes the album's place in Pick one. */
@Composable
internal fun SearchSheet(tags: CoversTags, waiting: Waiting, onFound: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val album = waiting.album.album
    var title by rememberSaveable { mutableStateOf(album.title.orEmpty()) }
    var artist by rememberSaveable { mutableStateOf(album.artist.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            LyrifyButton(
                stringResource(R.string.tags_search),
                onClick = {
                    searching = true
                    scope.launch {
                        when (val outcome = tags.search(waiting.album.id, title, artist)) {
                            is SearchOutcome.Found -> if (outcome.candidates > 0) onFound() else message = resources.getString(R.string.tags_search_none)
                            SearchOutcome.Busy -> message = resources.getString(R.string.tags_busy)
                            SearchOutcome.Failed -> message = resources.getString(R.string.tags_failed)
                            SearchOutcome.Gone -> onDismiss()
                        }
                        searching = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_search,
                enabled = title.isNotBlank() && !searching,
            )
        },
    ) {
        Text(stringResource(R.string.tags_search_myself), style = MaterialTheme.typography.titleLarge)
        LyrifyField(title, { title = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.tags_field_album))
        LyrifyField(artist, { artist = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.tags_field_artist))
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A Done album's songs that were left as they were, each with why. */
@Composable
internal fun ProblemsSheet(record: AlbumRecord, onDismiss: () -> Unit) {
    LyrifySheet(onDismiss = onDismiss) {
        Text(stringResource(R.string.tags_what_changed), style = MaterialTheme.typography.titleLarge)
        Text(pluralStringResource(R.plurals.tags_songs_changed, record.changed, record.changed), style = MaterialTheme.typography.bodyMedium)
        record.problems.forEach { problem ->
            Fact(R.drawable.ic_error, stringResource(R.string.tags_two_parts, problem.path.substringAfterLast('/'), stringResource(outcomeWords(problem.outcome))))
        }
    }
}
