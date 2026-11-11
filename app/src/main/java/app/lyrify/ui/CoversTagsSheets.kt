package app.lyrify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.Applying
import app.lyrify.tags.CoversTags
import app.lyrify.tags.Preview
import app.lyrify.tags.SearchOutcome
import app.lyrify.tags.Waiting
import app.lyrify.tags.firstTicked
import app.lyrify.tags.lookup.FieldChange
import app.lyrify.tags.lookup.Field
import app.lyrify.tags.lookup.Part
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.ReleaseMatch
import kotlinx.coroutines.launch

// Covers & tags' sheets: the review of one album or single (which one, what to change, your songs), Search myself, and
// what a Done row left as it was.

/**
 * The review of one album or single (the CT2 redesign). "Which one is it" (when not sure): up to 3 candidate cards that
 * only SELECT, then Search myself. "What to change": a checkbox row per field with a new value (the cover only once one
 * loaded), all ticked; Year adds the album's first year only to the songs with none. "Your songs": the album's OWN
 * files, each with its checkbox and its own "old → new"; opened from a song ([fromSong]) only that song is ticked, else
 * none. Apply names the count and is off while nothing is ticked, or the candidate picked is gone; while it writes, the
 * footer shows "Changing 3 of 9" with Cancel ([applying]).
 */
@Composable
internal fun ReviewSheet(
    tags: CoversTags,
    waiting: Waiting,
    fromSong: String?,
    access: Boolean,
    writable: Boolean,
    applying: Applying?,
    onApply: (ReleaseMatch, Set<String>, Set<Part>) -> Unit,
    onStop: () -> Unit,
    onSearch: () -> Unit,
    onNotThisOne: (ReleaseMatch) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val album = waiting.album
    val candidates = waiting.candidates
    // The best candidate is picked when the sheet opens; after that only the user picks (ReviewChoice).
    var choice by remember(album.id, fromSong) { mutableStateOf(ReviewChoice(candidates.firstOrNull()?.release?.id, songs = firstTicked(album, fromSong))) }
    val match = choice.match(candidates)
    val changes = remember(waiting, match) { match?.let { tags.changes(waiting, it) } }
    val preview = rememberPreview(tags, match?.release)
    val loaded = preview.preview is Preview.Loaded
    val parts = changes?.let { pickedParts(it, loaded, choice.overrides) }.orEmpty()
    val paired = changes?.songs?.associateBy { it.song.path }.orEmpty()
    val songs = choice.songs intersect paired.keys
    fun tick(part: Part, on: Boolean) {
        choice = choice.tick(part, on)
    }
    // The candidate picked gets its album's first year when its answer lacked it, so Year can be offered.
    LaunchedEffect(album.id, match?.release?.id) { match?.let { tags.date(album.id, it) } }
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            if (applying != null) {
                ApplyProgress(applying, onStop)
            } else {
                LyrifyButton(
                    applyWords(songs.size).text(),
                    onClick = { match?.let { onApply(it, songs, parts) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = match != null && canApply(songs, parts, writable),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (match != null) LyrifyButton(stringResource(R.string.tags_not_this_one), onClick = { onNotThisOne(match) }, modifier = Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                    LyrifyButton(stringResource(R.string.tags_skip), onClick = onSkip, modifier = Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                }
            }
        },
    ) {
        val own = album.album
        Column {
            Text(own.title?.takeIf(String::isNotBlank) ?: own.songs.firstOrNull()?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val count = pluralStringResource(R.plurals.tags_on_phone, own.songs.size, own.songs.size)
            Text(listOfNotNull(own.artist, count).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!access) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tags_no_access), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                LyrifyButton(stringResource(R.string.tags_turn_on), onClick = { context.startFirst(notificationAccessIntents(context)) }, kind = ButtonKind.SECONDARY, compact = true)
            }
        }
        if (candidates.isEmpty()) {
            Text(stringResource(R.string.tags_no_match), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LyrifyButton(stringResource(R.string.tags_search_myself), onClick = onSearch, kind = ButtonKind.SECONDARY, compact = true, icon = R.drawable.ic_search)
            return@LyrifySheet
        }
        // The cards whenever there is a choice to make, or the one picked is gone: then none is picked until the user does.
        if (waiting.proposal is Proposal.PickOne || match == null) {
            Heading(stringResource(R.string.tags_which_one))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                candidates.forEach { candidate ->
                    CandidateCard(tags, candidate, selected = candidate.release.id == match?.release?.id) { choice = choice.pick(candidate.release.id) }
                }
            }
            LyrifyButton(stringResource(R.string.tags_search_myself), onClick = onSearch, kind = ButtonKind.SECONDARY, compact = true, icon = R.drawable.ic_search)
        } else {
            val brand = LocalBrandColors.current
            SmallPill(R.drawable.ic_star_fill, stringResource(R.string.tags_sure), brand.highlightContainer, brand.onHighlightContainer)
        }
        if (match == null || changes == null) return@LyrifySheet

        Heading(stringResource(R.string.tags_what_to_change))
        val first = own.songs.firstOrNull { it.path in songs } ?: own.songs.firstOrNull()
        when (preview.preview) {
            is Preview.Loaded -> CheckRow(Part.COVER in parts, { tick(Part.COVER, it) }) {
                Text(stringResource(R.string.tags_field_cover), style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SongCover(first?.let { SongArt(it.path, album.ids[it.path]) }, size = 64.dp, shape = MaterialTheme.shapes.medium)
                    Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PreviewTile(preview, 64.dp)
                }
            }
            Preview.Failed -> CoverFailedLine(preview.retry)
            Preview.None, null -> Unit
        }
        fieldRows(changes).forEach { row ->
            CheckRow(row.part in parts, { tick(row.part, it) }) {
                Text(stringResource(row.part.label), style = MaterialTheme.typography.bodyLarge)
                Text(fieldLine(row), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (changes.parts.isEmpty() && preview.preview != null && !loaded) {
            Text(stringResource(R.string.tags_nothing_to_change), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Heading(pluralStringResource(R.plurals.tags_your_songs, own.songs.size, own.songs.size), Modifier.weight(1f))
            if (songs.size < paired.size) {
                LyrifyButton(pluralStringResource(R.plurals.tags_select_all, paired.size, paired.size), onClick = { choice = choice.copy(songs = paired.keys) }, kind = ButtonKind.GHOST, compact = true)
            } else {
                LyrifyButton(stringResource(R.string.tags_select_none), onClick = { choice = choice.copy(songs = emptySet()) }, kind = ButtonKind.GHOST, compact = true)
            }
        }
        own.songs.forEach { song ->
            val change = paired[song.path]
            CheckRow(song.path in songs, { on -> choice = choice.tickSong(song.path, on) }, enabled = change != null) {
                Text(song.title?.takeIf(String::isNotBlank) ?: song.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val lines = change?.let { songLines(it, parts) }
                when {
                    lines == null -> Muted(stringResource(R.string.tags_no_track))
                    lines.isEmpty() -> Muted(stringResource(R.string.tags_nothing_to_change))
                    else -> lines.forEach { Muted(stringResource(R.string.tags_two_parts, stringResource(it.field.label), oldNew(it))) }
                }
            }
        }
    }
}

/** A field's or the cover's name. */
private val Part.label: Int
    get() = when (this) {
        Part.COVER -> R.string.tags_field_cover
        Part.TITLE -> R.string.tags_field_title
        Part.ARTIST -> R.string.tags_field_artist
        Part.ALBUM -> R.string.tags_field_album
        Part.ALBUM_ARTIST -> R.string.tags_field_album_artist
        Part.YEAR -> R.string.tags_field_year
        Part.NUMBERS -> R.string.tags_field_numbers
    }

private val Field.label: Int
    get() = when (this) {
        Field.TITLE -> R.string.tags_field_title
        Field.ARTIST -> R.string.tags_field_artist
        Field.ALBUM -> R.string.tags_field_album
        Field.ALBUM_ARTIST -> R.string.tags_field_album_artist
        Field.TRACK -> R.string.tags_field_track
        Field.DISC -> R.string.tags_field_disc
        Field.YEAR -> R.string.tags_field_year
    }

/**
 * A field row's line: the "old → new" its songs share, or how many songs change; Year with songs keeping their own reads
 * "Adds 1989, first release to 3 songs, keeps 6".
 */
@Composable
private fun fieldLine(row: FieldRow): String {
    val change = row.change
    if (row.part == Part.YEAR && row.kept > 0 && change != null) {
        val adds = pluralStringResource(R.plurals.tags_year_adds, row.songs, row.songs, stringResource(R.string.tags_first_release, change.new))
        return pluralStringResource(R.plurals.tags_year_keeps, row.kept, adds, row.kept)
    }
    return change?.let { oldNew(it) } ?: pluralStringResource(R.plurals.tags_field_songs, row.songs, row.songs)
}

/** "Lanterns → Lanterns of Ulso"; a year reads "None → 1989, first release". */
@Composable
private fun oldNew(change: FieldChange): String {
    val old = change.old?.takeIf(String::isNotBlank) ?: stringResource(R.string.tags_empty_value)
    val new = if (change.field == Field.YEAR) stringResource(R.string.tags_first_release, change.new) else change.new
    return stringResource(R.string.tags_old_new, old, new)
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 4.dp).semantics { heading() })
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** A row with its checkbox at the end: one TalkBack stop with the checkbox's role; dimmed and fixed when not [enabled]. */
@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp), content = content)
        LyrifyCheckbox(checked)
    }
}

/**
 * A candidate: its cover, album, artist and "CD, 2009 · XE". A tap only selects it, and the rows below follow; nothing
 * is written from here.
 */
@Composable
private fun CandidateCard(tags: CoversTags, match: ReleaseMatch, selected: Boolean, onSelect: () -> Unit) {
    val release = match.release
    val brand = LocalBrandColors.current
    val preview = rememberPreview(tags, release)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(CARD_WIDTH)
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) brand.highlight else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(10.dp),
    ) {
        when (preview.preview) {
            Preview.None -> SongCover(null, size = CARD_COVER, shape = MaterialTheme.shapes.medium)
            else -> PreviewTile(preview, CARD_COVER)
        }
        if (preview.preview == Preview.Failed) CoverFailedLine(preview.retry)
        Text(release.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(release.artist.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(releaseLine(release), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Getting the cover", then "Changing 3 of 9" with its bar, and Cancel (the song being written is finished). */
@Composable
private fun ApplyProgress(applying: Applying, onStop: () -> Unit) {
    val fraction = if (applying.cover || applying.total == 0) 0f else applying.done.toFloat() / applying.total
    val brand = LocalBrandColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(progressWords(applying).text(), style = MaterialTheme.typography.titleMedium.tabular())
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(brand.highlight))
        }
        LyrifyButton(stringResource(R.string.tags_cancel), onClick = onStop, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    }
}

private val CARD_WIDTH = 136.dp
private val CARD_COVER = 116.dp
private const val DISABLED_ALPHA = 0.38f

/** "Search myself": the album and artist fields, then the search; what it finds becomes the album's choices. */
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

/** A Done row's songs that were left as they were, each with why. */
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
