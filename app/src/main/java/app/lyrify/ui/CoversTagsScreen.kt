package app.lyrify.ui

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.library.Song
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.Applied
import app.lyrify.tags.Applying
import app.lyrify.tags.CoversTags
import app.lyrify.tags.CoversTagsState
import app.lyrify.tags.Preview
import app.lyrify.tags.SongOutcome
import app.lyrify.tags.TagsGraph
import app.lyrify.tags.Undone
import app.lyrify.tags.Waiting
import app.lyrify.tags.undoBlocked
import app.lyrify.tags.lookup.AlbumChanges
import app.lyrify.tags.lookup.FieldChange
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.Part
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.ReleaseMatch
import app.lyrify.tags.lookup.SongChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat

// Covers & tags (covers-tags-spec.md "Where it lives", and the CT2 redesign in handoff/user-feedback-2026-11-11.md): its
// page in Tools with the top tabs To review, Done and Skipped, its row on Tools, and its row to History (T3's page).

// The page's logic, pure

/** A message as the resources say it: a string, or a plural for [count]; [args] fill it in. */
data class Words(val res: Int, val count: Int? = null, val args: List<Any> = emptyList())

/** The page's top tabs, in order: the same swiping tabs as Library's and Favourites' ([PagerTabs] over [TabPages]). */
enum class ReviewTab(@StringRes val title: Int) {
    TO_REVIEW(R.string.tags_to_review),
    DONE(R.string.tags_done),
    SKIPPED(R.string.tags_skipped),
}

/** Every tab, always, with its count: what has a suggestion, one row per apply, and the albums skipped. */
fun reviewTabs(state: CoversTagsState): List<Pair<ReviewTab, Int>> =
    listOf(ReviewTab.TO_REVIEW to state.toReview.size, ReviewTab.DONE to state.done.size, ReviewTab.SKIPPED to state.skipped.size)

/** The Covers & tags row on Tools: its line, and its badge. */
data class EntryRow(val line: ToolLine, val badge: Int?)

/** Where names and covers come from before the first check; then what waits (the badge), what changed, or nothing to review. */
fun coversTagsEntry(state: CoversTagsState): EntryRow {
    val first = !state.memory.checkedOnce && state.waiting.isEmpty() && state.memory.albums.isEmpty()
    val line = when {
        state.checking != null || state.checkingAlbums.isNotEmpty() -> ToolLine.Text(R.string.tags_line_checking)
        state.toReview.isNotEmpty() -> ToolLine.Count(R.plurals.tags_to_review, state.toReview.size)
        first -> ToolLine.Text(R.string.tags_line_start)
        state.done.isNotEmpty() -> ToolLine.Count(R.plurals.tags_albums_changed, state.done.size)
        else -> ToolLine.Text(R.string.tags_line_none)
    }
    return EntryRow(line, state.badge.takeIf { it > 0 })
}

/** Writing needs notification access (plan decision 1), and one apply at a time. */
fun canWrite(access: Boolean, state: CoversTagsState): Boolean = access && state.applying == null

/** Apply is on only with a song and a field ticked, while writing is possible. */
fun canApply(songs: Set<String>, parts: Set<Part>, writable: Boolean): Boolean = writable && songs.isNotEmpty() && parts.isNotEmpty()

/** Apply names the count: "Change 1 song", "Change 9 songs". */
fun applyWords(songs: Int): Words = Words(R.plurals.tags_change_songs, songs, listOf(songs))

/** More than one song asks once before writing; so does the very first apply, for its backup and sync note. */
fun asksFirst(songs: Int, noted: Boolean): Boolean = songs > 1 || !noted

/** A card's chip: "Sure match", or how many choices. */
fun choiceWords(proposal: Proposal): Words = when (proposal) {
    is Proposal.Ready -> Words(R.string.tags_sure)
    is Proposal.PickOne -> Words(R.plurals.tags_choices, proposal.candidates.size, listOf(proposal.candidates.size))
}

/** "Getting the cover" while it is fetched, then "Changing 3 of 9": the song being written of all of them. */
fun progressWords(applying: Applying): Words =
    if (applying.cover) Words(R.string.tags_getting_cover) else Words(R.plurals.tags_changing, applying.total, listOf(minOf(applying.done + 1, applying.total), applying.total))

/**
 * One row of "What to change": its [part], the "old → new" its songs share (null when they differ), how many [songs]
 * change, and for Year how many songs keep a year of their own ([kept]).
 */
data class FieldRow(val part: Part, val change: FieldChange?, val songs: Int, val kept: Int = 0)

/** The field rows, in the spec's order, only for what changes: the cover's row is the review's own. */
fun fieldRows(changes: AlbumChanges): List<FieldRow> = Part.entries.filter { it != Part.COVER && it in changes.parts }.map { part ->
    val lines = changes.songs.flatMap { song -> song.changes.filter { it.field.part == part } }
    val kept = if (part == Part.YEAR) changes.yearsKept else 0
    FieldRow(part, lines.firstOrNull()?.takeIf { lines.map { it.old to it.new }.distinct().size == 1 }, changes.songs.count { song -> song.changes.any { it.field.part == part } }, kept)
}

/**
 * The rows ticked now: the defaults ([AlbumChanges.ticked], the cover once it [loaded]) with the user's own ticks
 * ([overrides]) over them, among what is offered.
 */
fun pickedParts(changes: AlbumChanges, loaded: Boolean, overrides: Map<Part, Boolean>): Set<Part> {
    val offered = changes.parts + setOfNotNull(Part.COVER.takeIf { loaded })
    val defaults = changes.ticked(cover = loaded)
    return offered.filter { overrides[it] ?: (it in defaults) }.toSet()
}

/**
 * What the user chose in the review sheet: the candidate [picked] (by release id), their own field ticks ([overrides],
 * over the defaults) and the [songs] ticked. Nothing here changes behind their back: a candidate that goes away (a check
 * placed others) is never swapped for another, and the field ticks stay through a new pick.
 */
data class ReviewChoice(val picked: String?, val overrides: Map<Part, Boolean> = emptyMap(), val songs: Set<String> = emptySet()) {
    /** The candidate picked, among [candidates]; null when it is gone: none is picked, and Apply waits for a pick. */
    fun match(candidates: List<ReleaseMatch>): ReleaseMatch? = candidates.firstOrNull { it.release.id == picked }

    fun pick(id: String): ReviewChoice = copy(picked = id)

    fun tick(part: Part, on: Boolean): ReviewChoice = copy(overrides = overrides + (part to on))

    fun tickSong(path: String, on: Boolean): ReviewChoice = copy(songs = if (on) songs + path else songs - path)
}

/** A song's own "old → new" lines for the [parts] ticked. */
fun songLines(change: SongChange, parts: Set<Part>): List<FieldChange> = change.changes.filter { it.field.part in parts }

/**
 * The line for the songs whose interrupted write could not be checked at several starts (their originals kept), or
 * null when there are none.
 */
fun uncheckableWords(count: Int): Words? = if (count > 0) Words(R.plurals.tags_uncheckable, count, listOf(count)) else null

/** The line saying tag changes are paused (no song is written), or null when they are not. */
fun pausedWords(paused: Boolean): Words? = if (paused) Words(R.string.tags_paused) else null

/** What happened to one song, in words. */
fun outcomeWords(outcome: SongOutcome): Int = when (outcome) {
    SongOutcome.WRITTEN -> R.string.tags_outcome_written
    SongOutcome.UNCHANGED -> R.string.tags_outcome_unchanged
    SongOutcome.CHANGED_SINCE -> R.string.tags_outcome_changed_since
    SongOutcome.DIFFERENT_FILE -> R.string.tags_outcome_different
    SongOutcome.BUSY -> R.string.tags_outcome_busy
    SongOutcome.NOT_ALLOWED -> R.string.tags_outcome_not_allowed
    SongOutcome.COULD_NOT_CHANGE -> R.string.tags_outcome_could_not
    SongOutcome.LEFT_AS_IT_WAS -> R.string.tags_outcome_left
    SongOutcome.RECOVERY_PENDING -> R.string.tags_outcome_recovery
    SongOutcome.SET_ASIDE -> R.string.tags_outcome_set_aside
    SongOutcome.UNDO_NOT_SAVED -> R.string.tags_outcome_undo_not_saved
    SongOutcome.NOT_TRIED -> R.string.tags_outcome_not_tried
    SongOutcome.PAUSED -> R.string.tags_paused
}

/** The toast after an apply: songs left as they were come first, then the songs changed ("Changed 9 songs"). */
fun appliedWords(applied: Applied): Words = when (applied) {
    is Applied.Done -> when {
        applied.problems.isNotEmpty() -> Words(R.plurals.tags_songs_left, applied.problems.size, listOf(applied.problems.size))
        applied.songs > 0 -> Words(R.plurals.tags_changed_songs, applied.songs, listOf(applied.songs))
        else -> Words(R.string.tags_nothing_to_change)
    }
    Applied.NoAccess -> Words(R.string.tags_need_access)
    Applied.NotEnoughSpace -> Words(R.string.tags_no_space)
    Applied.CoverFailed -> Words(R.string.tags_cover_failed)
    Applied.Stopped -> Words(R.string.tags_stopped)
    Applied.Gone -> Words(R.string.tags_gone)
    Applied.InProgress -> Words(R.string.tags_in_progress)
    Applied.ChangedSince -> Words(R.string.tags_changed_since)
}

/** The toast after Undo: "Restored 9 songs"; fields changed since are kept, and it says so. */
fun undoneWords(undone: Undone): Words = when (undone) {
    is Undone.Done -> when {
        undone.problems.isNotEmpty() -> Words(R.plurals.tags_songs_left, undone.problems.size, listOf(undone.problems.size))
        undone.changedSince > 0 -> Words(R.plurals.tags_undone_skipped, undone.changedSince, listOf(undone.changedSince))
        undone.skippedFields > 0 -> Words(R.plurals.tags_undone_kept, undone.skippedFields, listOf(undone.skippedFields))
        else -> Words(R.plurals.tags_restored, undone.songs, listOf(undone.songs))
    }
    Undone.NoAccess -> Words(R.string.tags_need_access)
    Undone.NotEnoughSpace -> Words(R.string.tags_no_space)
    Undone.InProgress -> Words(R.string.tags_in_progress)
    Undone.Gone -> Words(R.string.tags_gone)
    Undone.Failed -> Words(R.string.tags_undo_failed)
    Undone.Blocked -> Words(R.string.tags_undo_newer_first)
}

/** A candidate's line: its formats and year ("CD, 2009"), then its country. */
fun releaseLine(release: MbRelease): String {
    val made = (release.formats + listOfNotNull(release.year?.toString())).joinToString(", ")
    return listOf(made, release.country.orEmpty()).filter(String::isNotEmpty).joinToString(" · ")
}

/**
 * A Done row's line before its time: the songs it changed, those that could not, "Undo has expired" once the full copies
 * are gone, and "Undo the newer change first" while a newer change to one of its songs stands ([blocked]). A restored
 * row's Undo stayed on the other phone: it says what the apply did there, and "Restored".
 */
fun doneLine(record: AlbumRecord, fullCopy: Boolean, blocked: Boolean = false): List<Words> {
    if (record.restored) {
        return listOfNotNull(
            if (record.changed > 0) Words(R.plurals.tags_changed_songs, record.changed, listOf(record.changed)) else Words(R.string.tags_nothing_to_change),
            record.problems.size.takeIf { it > 0 }?.let { Words(R.plurals.tags_songs_left, it, listOf(it)) },
            Words(R.string.tags_restored_row),
        )
    }
    if (record.entryId == null) return listOf(Words(R.string.tags_nothing_to_change))
    return listOfNotNull(
        Words(R.plurals.tags_songs, record.changed, listOf(record.changed)),
        record.problems.size.takeIf { it > 0 }?.let { Words(R.plurals.tags_songs_left, it, listOf(it)) },
        Words(R.string.tags_undo_expired).takeUnless { fullCopy },
        Words(R.string.tags_undo_newer_first).takeIf { blocked },
    )
}

/**
 * Each row's own key, [prefix] and its id, or the list crashes: a second row of one id (a Done row an old KJB kept twice)
 * gets "#2", so the first keeps its key as the second comes.
 */
internal fun rowKeys(prefix: String, ids: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return ids.map { id ->
        val nth = (seen[id] ?: 0) + 1
        seen[id] = nth
        if (nth == 1) "$prefix $id" else "$prefix $id#$nth"
    }
}

// Entry points

/** Covers & tags asked for this song's album: the path, and whether it came from the song's own sheet. */
private data class Asked(val path: String, val fromSong: Boolean)

private val asked = MutableStateFlow<Asked?>(null)

/** The song sheet's "Covers & tags" ([SongSheetSlots.coversTags]): the review of [song]'s album, with only [song] ticked. */
fun openCoversTags(song: Song, nav: Navigator) {
    asked.value = Asked(song.path, fromSong = true)
    nav.open(Page.CoversTags)
}

/** An album's "⋯" Covers & tags: the review of the album [song] is in, nothing ticked ("Select all" is one tap). */
fun openCoversTagsForAlbum(song: Song, nav: Navigator) {
    asked.value = Asked(song.path, fromSong = false)
    nav.open(Page.CoversTags)
}

@Composable
private fun rememberCoversTags(): CoversTags {
    val context = LocalContext.current
    return remember(context) { TagsGraph.coversTags(context) }
}

/** Covers & tags' status line and badge on its Tools row; null until its state is read. */
@Composable
fun coversTagsRow(): EntryRow? {
    val state by rememberCoversTags().state.collectAsStateWithLifecycle()
    return if (state.loaded) coversTagsEntry(state) else null
}

private fun Words.text(resources: Resources): String =
    if (count != null) resources.getQuantityString(res, count, *args.toTypedArray()) else resources.getString(res, *args.toTypedArray())

@Composable
internal fun Words.text(): String = text(LocalResources.current)

/** A release's preview as it loads (null meanwhile), and Try again. */
@Stable
internal class PreviewState(val preview: Preview?, val retry: () -> Unit)

@Composable
internal fun rememberPreview(tags: CoversTags, release: MbRelease?): PreviewState {
    var tries by remember(release?.id) { mutableIntStateOf(0) }
    val preview by produceState<Preview?>(null, release?.id, tries) {
        value = null
        value = release?.let { tags.preview(it) } ?: Preview.None
    }
    return PreviewState(preview) { tries++ }
}

// The page

/** The review sheet open on an album, from a song ([song]) or not. */
private data class OpenReview(val albumId: String, val song: String?)

/** An apply waiting for its confirmation. */
private data class PendingApply(val albumId: String, val match: ReleaseMatch, val songs: Set<String>, val parts: Set<Part>)

/**
 * Tools > Covers & tags: the top tabs To review, Done and Skipped with their counts, as Library's and Favourites' are:
 * their pages follow the finger left and right, the tabs' line with them, and each keeps its own list and its place
 * ([TabPages]); what sits above the tabs scrolls away first, then the tabs stay under the top bar ([CollapsingTabs]).
 * To review: "Check my albums" (its ring, "Checking 12/84" and Cancel), then a card per album with a suggestion, each
 * opening the review sheet. Done: one row per apply with its Undo, then History. Skipped: each with Check again.
 * Writing is off without notification access, with a one-line reason and a button to turn it on.
 */
@Composable
fun CoversTagsPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val tags = rememberCoversTags()
    val state by tags.state.collectAsStateWithLifecycle()
    val busy by tags.busy.collectAsStateWithLifecycle()
    val access = rememberNotificationAccess()
    var tab by rememberSaveable { mutableStateOf(ReviewTab.TO_REVIEW) }
    val tabs = reviewTabs(state)
    val pager = rememberTabPager(tabs.size, tabs.indexOfFirst { it.first == tab }) { at -> tabs.getOrNull(at)?.let { tab = it.first } }
    val header = rememberScrollState()
    // The lines above the tabs gone under the top bar are content under it too.
    val headerUnder by remember(header) { derivedStateOf { header.value > 0 } }
    var open by remember { mutableStateOf<OpenReview?>(null) }
    var confirm by remember { mutableStateOf<PendingApply?>(null) }
    var searching by rememberSaveable { mutableStateOf<String?>(null) }
    var problems by rememberSaveable { mutableStateOf<String?>(null) }
    val uncheckable by produceState(0) { value = TagsGraph.uncheckable(context) }
    val paused by produceState(false) { value = TagsGraph.paused(context) }
    val writable = canWrite(access, state)

    // Writes with the sheet and its progress in sight, then closes it and says what changed, with Undo.
    fun apply(pending: PendingApply) {
        scope.launch {
            val applied = tags.apply(pending.albumId, pending.match, pending.songs, pending.parts)
            open = null
            val words = appliedWords(applied).text(resources)
            val entry = (applied as? Applied.Done)?.takeIf { it.songs > 0 }?.entryId
            if (entry == null) {
                toasts.show(words)
            } else if (toasts.show(Toast(words, action = resources.getString(R.string.tags_undo)))) {
                toasts.show(undoneWords(tags.undo(entry)).text(resources))
            }
        }
    }

    LaunchedEffect(tags) { tags.refreshHistory() }
    LaunchedEffect(tags) {
        asked.collect { ask ->
            if (ask != null) {
                asked.value = null
                tags.open(ask.path, ask.fromSong)
            }
        }
    }
    // The song sheet's album, once checked: To review, and its review sheet.
    LaunchedEffect(state.focus, state.waiting, state.checkingAlbums) {
        val focus = state.focus ?: return@LaunchedEffect
        if (focus.albumId in state.checkingAlbums) return@LaunchedEffect
        if (state.waiting.any { it.album.id == focus.albumId }) {
            tab = ReviewTab.TO_REVIEW
            open = OpenReview(focus.albumId, focus.song)
        }
        tags.focused()
    }

    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.tools_covers), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under || headerUnder,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
            )
        },
    ) { padding ->
        val reduced = LocalReducedMotion.current
        CollapsingTabs(
            outer = header,
            padding = padding,
            header = {
                Spacer(Modifier.height(8.dp))
                if (!access) {
                    val brand = LocalBrandColors.current
                    Banner(
                        R.drawable.ic_notifications,
                        stringResource(R.string.tags_no_access),
                        stringResource(R.string.tags_turn_on),
                        brand.highlightContainer,
                        brand.onHighlightContainer,
                        onAction = { context.startFirst(notificationAccessIntents(context)) },
                        modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 12.dp),
                    )
                }
                listOfNotNull(pausedWords(paused), uncheckableWords(uncheckable)).forEach { words -> PageLine(words.text(), error = words.res == R.string.tags_paused) }
            },
            tabs = { PagerTabs(pager, tabs.map { (it, count) -> stringResource(R.string.lib_tab_count, stringResource(it.title), formatCount(count)) }) },
        ) {
            TabPages(pager, tabs.map { it.first }, Modifier.fillMaxSize()) { page ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
                    when (tabs[page].first) {
                        ReviewTab.TO_REVIEW -> {
                            item(key = "start", contentType = "start") { StartRow(state, busy, onCheck = tags::check, onCancel = tags::cancel) }
                            items(state.toReview, key = { "review ${it.album.id}" }, contentType = { "card" }) { waiting ->
                                Column(rowMotion(reduced)) { ReviewCard(tags, waiting) { open = OpenReview(waiting.album.id, null) } }
                            }
                        }
                        ReviewTab.DONE -> {
                            if (state.done.isNotEmpty()) item(key = "note", contentType = "text") { PageLine(stringResource(R.string.tags_samsung_note)) }
                            val keys = rowKeys("done", state.done.map { it.id })
                            itemsIndexed(state.done, key = { at, _ -> keys[at] }, contentType = { _, _ -> "record" }) { _, record ->
                                Column(rowMotion(reduced)) {
                                    val blocked = record.entryId?.let { undoBlocked(it, state.history) } == true
                                    DoneRow(
                                        record,
                                        fullCopy = record.entryId in state.fullCopies,
                                        blocked = blocked,
                                        enabled = writable && !blocked,
                                        onUndo = { scope.launch { toasts.show(undoneWords(tags.undoAlbum(record.id)).text(resources)) } },
                                        onOpen = { problems = record.id }.takeIf { record.problems.isNotEmpty() },
                                    )
                                }
                            }
                            item(key = "history", contentType = "history") { HistoryRow(state.history.size) { nav.open(Page.TagHistory) } }
                        }
                        ReviewTab.SKIPPED -> {
                            val keys = rowKeys("skipped", state.skipped.map { it.id })
                            itemsIndexed(state.skipped, key = { at, _ -> keys[at] }, contentType = { _, _ -> "record" }) { _, record ->
                                Column(rowMotion(reduced)) { SkippedRow(record) { tags.checkAgain(record.id) } }
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { at ->
        val waiting = state.waiting.firstOrNull { it.album.id == at.albumId }
        // Applied, skipped or checked again meanwhile: the sheet closes, unless its apply still runs.
        if (waiting == null) {
            if (state.applying == null) LaunchedEffect(at) { open = null }
            return@let
        }
        ReviewSheet(
            tags = tags,
            waiting = waiting,
            fromSong = at.song,
            access = access,
            writable = writable,
            applying = state.applying,
            onApply = { match, songs, parts ->
                val pending = PendingApply(waiting.album.id, match, songs, parts)
                if (asksFirst(songs.size, state.memory.noted)) confirm = pending else apply(pending)
            },
            onStop = tags::stopApplying,
            onSearch = {
                searching = waiting.album.id
                open = null
            },
            onNotThisOne = { match -> tags.notThisOne(waiting.album.id, match) },
            onSkip = {
                open = null
                scope.launch { tags.skip(waiting.album.id) }
            },
            onDismiss = { if (state.applying == null) open = null },
        )
    }
    confirm?.let { pending ->
        val songs = pending.songs.size
        val note = !state.memory.noted
        ConfirmDialog(
            title = pluralStringResource(R.plurals.tags_confirm_title, songs, songs),
            confirm = pluralStringResource(R.plurals.tags_change_songs, songs, songs),
            dismiss = stringResource(R.string.tags_cancel),
            text = pluralStringResource(R.plurals.tags_confirm_text, songs, songs) + if (note) "\n\n" + stringResource(R.string.tags_sync_note) else "",
            icon = R.drawable.ic_sell,
            destructive = false,
            onConfirm = {
                confirm = null
                if (note) scope.launch { tags.noted() }
                apply(pending)
            },
            onDismiss = { confirm = null },
        )
    }
    searching?.let { albumId ->
        val waiting = state.waiting.firstOrNull { it.album.id == albumId }
        if (waiting == null) {
            LaunchedEffect(albumId) { searching = null }
            return@let
        }
        SearchSheet(
            tags,
            waiting,
            onFound = {
                searching = null
                open = OpenReview(albumId, null)
            },
            onDismiss = { searching = null },
        )
    }
    problems?.let { id ->
        val record = state.done.firstOrNull { it.id == id }
        if (record == null) {
            LaunchedEffect(id) { problems = null }
            return@let
        }
        ProblemsSheet(record) { problems = null }
    }
}

@Composable
private fun PageLine(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
    )
}

/** Before the first check, what the page does and "Check my albums"; then the ring while checking, or the button again. */
@Composable
private fun StartRow(state: CoversTagsState, busy: Boolean, onCheck: () -> Unit, onCancel: () -> Unit) {
    val checking = state.checking
    val first = !state.memory.checkedOnce && state.waiting.isEmpty() && state.memory.albums.isEmpty()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        when {
            checking != null -> {
                val fraction = if (checking.total > 0) checking.done.toFloat() / checking.total else 0f
                AiRing(Ring(fraction, RingColor.HIGHLIGHT), Centre.Value(CentreValue.Percent((fraction * 100).toInt())), size = 160.dp)
                Text(
                    if (checking.total > 0) stringResource(R.string.tags_checking, formatCount(checking.done), formatCount(checking.total)) else stringResource(R.string.tags_checking_start),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    textAlign = TextAlign.Center,
                )
                LyrifyButton(stringResource(R.string.tags_cancel), onClick = onCancel, kind = ButtonKind.SECONDARY, compact = true)
            }
            first -> EmptyState(EmptyArt.Icon(R.drawable.ic_sell), stringResource(R.string.tags_find), text = stringResource(R.string.tags_intro), action = stringResource(R.string.tags_check), onAction = onCheck)
            else -> LyrifyButton(stringResource(R.string.tags_check), onClick = onCheck, kind = ButtonKind.SECONDARY, compact = true)
        }
        val lines = listOfNotNull(
            stringResource(R.string.tags_busy).takeIf { busy || (state.stoppedBusy && checking == null) },
            state.checkingAlbums.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tags_checking_albums, it, it) },
            state.notChecked.takeIf { it > 0 && checking == null }?.let { pluralStringResource(R.plurals.tags_not_checked, it, it) },
            stringResource(R.string.tags_unreadable).takeIf { state.unreadable },
            stringResource(R.string.tags_line_none).takeIf { !first && checking == null && state.checkingAlbums.isEmpty() && state.toReview.isEmpty() },
        )
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/** A row that shows an album or an edit: its tile, its title and line, and its button; a tap does something only with [onClick]. */
@Composable
private fun ItemRow(title: String, sub: String?, lead: @Composable () -> Unit, trailing: @Composable () -> Unit, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        lead()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** A suggested cover as it loads: the glyph meanwhile (and when it failed), the thumbnail once loaded; none when the release has none. */
@Composable
internal fun PreviewTile(state: PreviewState, size: Dp) {
    when (val preview = state.preview) {
        is Preview.Loaded -> SongCover(preview.image, size = size, shape = MaterialTheme.shapes.medium)
        null, Preview.Failed -> SongCover(null, size = size, shape = MaterialTheme.shapes.medium)
        Preview.None -> Unit
    }
}

/** "Couldn't load cover" with Try again. */
@Composable
internal fun CoverFailedLine(onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tags_cover_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        LyrifyButton(stringResource(R.string.tags_try_again), onClick = onRetry, kind = ButtonKind.GHOST, compact = true)
    }
}

/**
 * An album to review: your current cover, the suggested one if found, the album and artist, "9 songs on your phone",
 * and "Sure match" or "3 choices". A tap opens the review sheet; nothing is applied from here.
 */
@Composable
private fun ReviewCard(tags: CoversTags, waiting: Waiting, onOpen: () -> Unit) {
    val album = waiting.album.album
    val first = album.songs.firstOrNull()
    val preview = rememberPreview(tags, waiting.candidates.firstOrNull()?.release)
    LyrifyCard(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp)) {
        Column(Modifier.clickable(role = Role.Button, onClick = onOpen).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SongCover(first?.let { SongArt(it.path, waiting.album.ids[it.path]) }, size = 56.dp, shape = MaterialTheme.shapes.medium)
                PreviewTile(preview, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(album.title?.takeIf(String::isNotBlank) ?: first?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val songs = pluralStringResource(R.plurals.tags_on_phone, album.songs.size, album.songs.size)
                    Text(listOfNotNull(album.artist, songs).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val brand = LocalBrandColors.current
            SmallPill(if (waiting.proposal is Proposal.Ready) R.drawable.ic_star_fill else R.drawable.ic_sell, choiceWords(waiting.proposal).text(), brand.highlightContainer, brand.onHighlightContainer)
            if (preview.preview == Preview.Failed) CoverFailedLine(preview.retry)
        }
    }
}

/**
 * A Done row, one per apply: "Lanterns of Ulso", "1 song · 03:21", and Undo (Undo fields once its full copies are gone),
 * off with its reason while a newer change to one of its songs stands.
 */
@Composable
private fun DoneRow(record: AlbumRecord, fullCopy: Boolean, blocked: Boolean, enabled: Boolean, onUndo: () -> Unit, onOpen: (() -> Unit)?) {
    val time = DateUtils.formatSameDayTime(record.at, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT).toString()
    ItemRow(
        title = record.title,
        sub = (doneLine(record, fullCopy, blocked).map { it.text() } + time).joinToString(" · "),
        lead = { SongCover(record.songs.firstOrNull()?.let { SongArt(it.path) }, size = 48.dp) },
        trailing = {
            if (record.entryId != null) {
                LyrifyButton(stringResource(if (fullCopy) R.string.tags_undo else R.string.tags_undo_fields), onClick = onUndo, kind = ButtonKind.GHOST, compact = true, enabled = enabled)
            }
        },
        onClick = onOpen,
    )
}

/** A skipped album, with Check again. */
@Composable
private fun SkippedRow(record: AlbumRecord, onCheckAgain: () -> Unit) {
    ItemRow(
        title = record.title,
        sub = listOfNotNull(record.artist, pluralStringResource(R.plurals.tags_songs, record.songs.size, record.songs.size)).joinToString(" · "),
        lead = { SongCover(record.songs.firstOrNull()?.let { SongArt(it.path) }, size = 48.dp) },
        trailing = { LyrifyButton(stringResource(R.string.tags_check_again), onClick = onCheckAgain, kind = ButtonKind.GHOST, compact = true) },
    )
}

/** History: every tag edit, Edit tags' too, with its Undo (T3's page). */
@Composable
private fun HistoryRow(count: Int, onOpen: () -> Unit) {
    Column {
        Spacer(Modifier.height(16.dp))
        GroupedRows {
            GroupedRow(
                R.drawable.ic_history,
                stringResource(R.string.tags_history),
                tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                first = true,
                supporting = { Text(if (count > 0) pluralStringResource(R.plurals.tags_edits, count, count) else stringResource(R.string.tags_no_edits)) },
                onClick = onOpen,
            )
        }
    }
}
