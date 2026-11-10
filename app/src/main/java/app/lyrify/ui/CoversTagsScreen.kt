package app.lyrify.ui

import android.content.res.Resources
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.library.Song
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.Applied
import app.lyrify.tags.CoversTags
import app.lyrify.tags.CoversTagsState
import app.lyrify.tags.Settled
import app.lyrify.tags.Stopped
import app.lyrify.tags.SongOutcome
import app.lyrify.tags.TagsGraph
import app.lyrify.tags.Undone
import app.lyrify.tags.Waiting
import app.lyrify.tags.lookup.AlbumChanges
import app.lyrify.tags.lookup.Cover
import app.lyrify.tags.lookup.Fit
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.ReleaseMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// Covers & tags (covers-tags-spec.md, "Where it lives" and "Reviewing"): its page in Tools, its row on Tools, and its
// row to History (T3's page, Page.TagHistory, where each Undo goes through undoEdit).

// The page's logic, pure

/** A message as the resources say it: a string, or a plural for [count]; [args] fill it in. */
data class Words(val res: Int, val count: Int? = null, val args: List<Any> = emptyList())

/** The page's sections, in order. */
enum class ReviewSection(@StringRes val title: Int) {
    READY(R.string.tags_ready),
    PICK_ONE(R.string.tags_pick_one),
    DONE(R.string.tags_done),
    SKIPPED(R.string.tags_skipped),
}

/** The sections that have albums, in order, with their counts. */
fun reviewSections(state: CoversTagsState): List<Pair<ReviewSection, Int>> = listOf(
    ReviewSection.READY to state.ready.size,
    ReviewSection.PICK_ONE to state.pickOne.size,
    ReviewSection.DONE to state.done.size,
    ReviewSection.SKIPPED to state.skipped.size,
).filter { it.second > 0 }

/** The Covers & tags row on Tools: its line, and its badge. */
data class EntryRow(val line: ToolLine, val badge: Int?)

/** Where names and covers come from before the first check; then what waits (the badge), what changed, or nothing to review. */
fun coversTagsEntry(state: CoversTagsState): EntryRow {
    val first = !state.memory.checkedOnce && state.waiting.isEmpty() && state.memory.albums.isEmpty()
    val line = when {
        state.checking != null || state.checkingAlbums.isNotEmpty() -> ToolLine.Text(R.string.tags_line_checking)
        state.waiting.isNotEmpty() -> ToolLine.Count(R.plurals.tags_to_review, state.waiting.size)
        first -> ToolLine.Text(R.string.tags_line_start)
        state.done.isNotEmpty() -> ToolLine.Count(R.plurals.tags_albums_changed, state.done.size)
        else -> ToolLine.Text(R.string.tags_line_none)
    }
    return EntryRow(line, state.badge.takeIf { it > 0 })
}

/** What an apply's dialog says: [albums] changing [songs] songs, and the backup and sync [note] on the first apply only. */
data class ApplyCopy(val albums: Int, val songs: Int, val note: Boolean)

fun applyCopy(albums: List<AlbumChanges>, noted: Boolean): ApplyCopy = ApplyCopy(albums.size, albums.sumOf { it.songs.size }, !noted)

/** Writing needs notification access (plan decision 1), and one apply at a time. */
fun canWrite(access: Boolean, state: CoversTagsState): Boolean = access && state.applying == null

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

/**
 * The toast after Apply or Apply all: why Apply all stopped (with the albums it changed) comes first, then songs left as
 * they were, then albums with no answer.
 */
fun appliedWords(applied: Applied): Words = when (applied) {
    is Applied.Done -> when {
        applied.stopped != null -> Words(
            when (applied.stopped) {
                Stopped.BUSY -> R.plurals.tags_applied_then_busy
                Stopped.NOT_ENOUGH_SPACE -> R.plurals.tags_applied_then_space
                Stopped.NO_ACCESS -> R.plurals.tags_applied_then_access
            },
            applied.albums,
            listOf(applied.albums),
        )
        applied.problems.isNotEmpty() -> Words(R.plurals.tags_songs_left, applied.problems.size, listOf(applied.problems.size))
        applied.failed > 0 -> Words(R.plurals.tags_not_applied, applied.failed, listOf(applied.failed))
        applied.albums > 0 -> Words(R.plurals.tags_applied, applied.albums, listOf(applied.albums))
        else -> Words(R.string.tags_nothing_to_change)
    }
    Applied.NoAccess -> Words(R.string.tags_need_access)
    Applied.NotEnoughSpace -> Words(R.string.tags_no_space)
    Applied.Busy -> Words(R.string.tags_busy)
    Applied.Failed -> Words(R.string.tags_failed)
    Applied.Gone -> Words(R.string.tags_gone)
    Applied.InProgress -> Words(R.string.tags_in_progress)
    Applied.ChangedSince -> Words(R.string.tags_changed_since)
}

/** The toast after Undo: fields changed since are kept, and it says so. */
fun undoneWords(undone: Undone): Words = when (undone) {
    is Undone.Done -> when {
        undone.problems.isNotEmpty() -> Words(R.plurals.tags_songs_left, undone.problems.size, listOf(undone.problems.size))
        undone.changedSince > 0 -> Words(R.plurals.tags_undone_skipped, undone.changedSince, listOf(undone.changedSince))
        undone.skippedFields > 0 -> Words(R.plurals.tags_undone_kept, undone.skippedFields, listOf(undone.skippedFields))
        else -> Words(R.string.tags_undone)
    }
    Undone.NoAccess -> Words(R.string.tags_need_access)
    Undone.NotEnoughSpace -> Words(R.string.tags_no_space)
    Undone.InProgress -> Words(R.string.tags_in_progress)
    Undone.Gone -> Words(R.string.tags_gone)
    Undone.Failed -> Words(R.string.tags_undo_failed)
}

/** A Ready card's summary: "6 changes", or the cover only. */
fun summaryWords(changes: AlbumChanges): Words =
    if (changes.count > 0) Words(R.plurals.tags_changes, changes.count, listOf(changes.count)) else Words(R.string.tags_cover_only)

/** "12 of 12 songs fit". */
fun fitWords(fit: Fit): Words = Words(R.plurals.tags_fit, fit.total, listOf(fit.fitting, fit.total))

/** A candidate's line: its formats and year ("CD, 2009"), then its country. */
fun releaseLine(release: MbRelease): String {
    val made = (release.formats + listOfNotNull(release.year?.toString())).joinToString(", ")
    return listOf(made, release.country.orEmpty()).filter(String::isNotEmpty).joinToString(" · ")
}

/** A Done row's line: the songs changed, those that could not, and "Undo has expired" once the full copies are gone. */
fun doneLine(record: AlbumRecord, fullCopy: Boolean): List<Words> {
    if (record.entryId == null) return listOf(Words(R.string.tags_nothing_to_change))
    return listOfNotNull(
        Words(R.plurals.tags_songs_changed, record.changed, listOf(record.changed)),
        record.problems.size.takeIf { it > 0 }?.let { Words(R.plurals.tags_songs_left, it, listOf(it)) },
        Words(R.string.tags_undo_expired).takeUnless { fullCopy },
    )
}

// Entry points

/** The song sheet's "Covers & tags" asked for this song's album: the page opens its review. */
private val asked = MutableStateFlow<String?>(null)

/** The song sheet's "Covers & tags" ([SongSheetSlots.coversTags]): Covers & tags, at the review of [song]'s album. */
fun openCoversTags(song: Song, nav: Navigator) {
    asked.value = song.path
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
private fun Words.text(): String = text(LocalResources.current)

// The page

/**
 * What the page lists, row by row. Each row's [key] is its own on the page, or the list crashes: an album's row is keyed
 * by its section and its id ([app.lyrify.tags.TagAlbum.id], KJB's own, from its files' paths), so it keeps its key as
 * rows come and go, and a second row of one album in one section gets "#2" ([pageRows]). An album can be in two sections
 * at once ("Check again" places it before its Skipped record goes) or twice in one (History can rebuild two Done
 * records of it).
 */
internal sealed interface PageRow {
    val key: String

    /** The album the row shows, by its id: the song sheet's album is scrolled to by it. */
    val albumId: String? get() = null

    data object Start : PageRow {
        override val key = "start"
    }

    data class Section(val section: ReviewSection, val count: Int) : PageRow {
        override val key = "section ${section.name}"
    }

    data class Ready(val waiting: Waiting, override val key: String) : PageRow {
        override val albumId: String get() = waiting.album.id
    }

    data class Pick(val waiting: Waiting, override val key: String) : PageRow {
        override val albumId: String get() = waiting.album.id
    }

    data object SamsungNote : PageRow {
        override val key = "note"
    }

    data class Uncheckable(val words: Words) : PageRow {
        override val key = "uncheckable"
    }

    data class Paused(val words: Words) : PageRow {
        override val key = "paused"
    }

    data class Record(val record: AlbumRecord, override val key: String) : PageRow {
        override val albumId: String get() = record.id
    }

    data object History : PageRow {
        override val key = "history"
    }
}

internal fun pageRows(state: CoversTagsState, uncheckable: Int, paused: Boolean): List<PageRow> = buildList {
    val seen = HashMap<String, Int>()
    fun key(section: ReviewSection, albumId: String): String {
        val key = "${section.name} $albumId"
        val nth = (seen[key] ?: 0) + 1
        seen[key] = nth
        return if (nth == 1) key else "$key#$nth"
    }
    add(PageRow.Start)
    pausedWords(paused)?.let { add(PageRow.Paused(it)) }
    uncheckableWords(uncheckable)?.let { add(PageRow.Uncheckable(it)) }
    for ((section, count) in reviewSections(state)) {
        add(PageRow.Section(section, count))
        when (section) {
            ReviewSection.READY -> state.ready.forEach { add(PageRow.Ready(it, key(section, it.album.id))) }
            ReviewSection.PICK_ONE -> state.pickOne.forEach { add(PageRow.Pick(it, key(section, it.album.id))) }
            ReviewSection.DONE -> {
                add(PageRow.SamsungNote)
                state.done.forEach { add(PageRow.Record(it, key(section, it.id))) }
            }
            ReviewSection.SKIPPED -> state.skipped.forEach { add(PageRow.Record(it, key(section, it.id))) }
        }
    }
    add(PageRow.History)
}

/** An album sheet open on [albumId] with release [releaseId]. */
private data class OpenAlbum(val albumId: String, val releaseId: String)

/**
 * Tools > Covers & tags: "Check my albums" (its ring, "Checking 12/84" and Cancel), then Ready (with Apply all), Pick
 * one, Done (each with Undo) and Skipped (each with Check again), and History. Writing is off without notification
 * access, with a one-line reason and a button to turn it on.
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
    val list = rememberLazyListState()
    var open by remember { mutableStateOf<OpenAlbum?>(null) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<OpenAlbum?>(null) }
    var searching by rememberSaveable { mutableStateOf<String?>(null) }
    var problems by rememberSaveable { mutableStateOf<String?>(null) }
    val uncheckable by produceState(0) { value = TagsGraph.uncheckable(context) }
    val paused by produceState(false) { value = TagsGraph.paused(context) }
    val rows = remember(state, uncheckable, paused) { pageRows(state, uncheckable, paused) }
    val writable = canWrite(access, state)

    fun apply(albumId: String, match: ReleaseMatch) {
        open = null
        scope.launch { toasts.show(appliedWords(tags.apply(albumId, match)).text(resources)) }
    }

    LaunchedEffect(tags) { tags.refreshHistory() }
    LaunchedEffect(tags) {
        asked.collect { path ->
            if (path != null) {
                asked.value = null
                tags.open(path)
            }
        }
    }
    // The song sheet's album: a Ready one opens in its sheet, any other is scrolled to.
    LaunchedEffect(state.focus, rows) {
        val focus = state.focus ?: return@LaunchedEffect
        if (focus in state.checkingAlbums) return@LaunchedEffect
        val ready = state.ready.firstOrNull { it.album.id == focus }
        if (ready != null) open = OpenAlbum(focus, (ready.proposal as Proposal.Ready).match.release.id)
        val at = rows.indexOfFirst { it.albumId == focus }
        if (at >= 0) list.animateScrollToItem(at)
        tags.focused()
    }

    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.tools_covers), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
            )
        },
    ) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            if (!access) {
                item(key = "access", contentType = "access") {
                    val brand = LocalBrandColors.current
                    Banner(
                        R.drawable.ic_notifications,
                        stringResource(R.string.tags_no_access),
                        stringResource(R.string.tags_turn_on),
                        brand.highlightContainer,
                        brand.onHighlightContainer,
                        onAction = { context.startFirst(notificationAccessIntents(context)) },
                        modifier = rowMotion(reduced).padding(start = ScreenPadding, end = ScreenPadding, bottom = 12.dp),
                    )
                }
            }
            items(
                rows,
                key = { it.key },
                contentType = { row ->
                    when (row) {
                        PageRow.Start -> "start"
                        is PageRow.Section -> "section"
                        is PageRow.Ready -> "ready"
                        is PageRow.Pick -> "pick"
                        PageRow.SamsungNote, is PageRow.Paused, is PageRow.Uncheckable -> "text"
                        is PageRow.Record -> "record"
                        PageRow.History -> "history"
                    }
                },
            ) { row ->
                Column(rowMotion(reduced)) {
                    when (row) {
                        PageRow.Start -> StartRow(state, busy, onCheck = tags::check, onCancel = tags::cancel)
                        is PageRow.Section -> SectionHead(row, onApplyAll = { confirmAll = true }.takeIf { row.section == ReviewSection.READY && writable })
                        is PageRow.Ready -> ReadyCard(tags, row.waiting) { open = OpenAlbum(row.waiting.album.id, (row.waiting.proposal as Proposal.Ready).match.release.id) }
                        is PageRow.Pick -> PickOneAlbum(
                            tags,
                            row.waiting,
                            onUse = { match -> open = OpenAlbum(row.waiting.album.id, match.release.id) },
                            onSearch = { searching = row.waiting.album.id },
                            onSkip = { scope.launch { tags.skip(row.waiting.album.id) } },
                        )
                        PageRow.SamsungNote -> Text(
                            stringResource(R.string.tags_samsung_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                        )
                        is PageRow.Paused -> Text(
                            row.words.text(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                        )
                        is PageRow.Uncheckable -> Text(
                            row.words.text(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                        )
                        is PageRow.Record -> SettledRow(
                            row.record,
                            fullCopy = row.record.entryId in state.fullCopies,
                            enabled = writable,
                            onUndo = { scope.launch { toasts.show(undoneWords(tags.undoAlbum(row.record.id)).text(resources)) } },
                            onCheckAgain = { tags.checkAgain(row.record.id) },
                            onOpen = { problems = row.record.id }.takeIf { row.record.problems.isNotEmpty() },
                        )
                        PageRow.History -> {
                            Spacer(Modifier.height(16.dp))
                            GroupedRows {
                                GroupedRow(
                                    R.drawable.ic_history,
                                    stringResource(R.string.tags_history),
                                    tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                                    first = true,
                                    supporting = {
                                        val count = state.history.size
                                        Text(if (count > 0) pluralStringResource(R.plurals.tags_edits, count, count) else stringResource(R.string.tags_no_edits))
                                    },
                                    onClick = { nav.open(Page.TagHistory) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    open?.let { at ->
        val waiting = state.waiting.firstOrNull { it.album.id == at.albumId }
        val match = waiting?.let { matchOf(it, at.releaseId) }
        // Applied, skipped or checked again meanwhile: the sheet closes.
        if (waiting == null || match == null) {
            LaunchedEffect(at) { open = null }
            return@let
        }
        AlbumSheet(
            tags = tags,
            waiting = waiting,
            match = match,
            access = access,
            writable = writable,
            onApply = {
                if (state.memory.noted) apply(waiting.album.id, match) else confirmOne = at
            },
            onNotThisOne = {
                tags.notThisOne(waiting.album.id, match)
                open = null
            },
            onSkip = {
                open = null
                scope.launch { tags.skip(waiting.album.id) }
            },
            onDismiss = { open = null },
        )
    }
    confirmOne?.let { at ->
        val waiting = state.waiting.firstOrNull { it.album.id == at.albumId }
        val match = waiting?.let { matchOf(it, at.releaseId) }
        if (waiting == null || match == null) {
            LaunchedEffect(at) { confirmOne = null }
            return@let
        }
        val songs = tags.changes(waiting, match).songs.size
        ConfirmDialog(
            title = stringResource(R.string.tags_apply_one_title),
            confirm = stringResource(R.string.tags_apply),
            dismiss = stringResource(R.string.tags_cancel),
            text = pluralStringResource(R.plurals.tags_apply_one_text, songs, songs) + "\n\n" + stringResource(R.string.tags_sync_note),
            icon = R.drawable.ic_sell,
            destructive = false,
            onConfirm = {
                confirmOne = null
                scope.launch { tags.noted() }
                apply(waiting.album.id, match)
            },
            onDismiss = { confirmOne = null },
        )
    }
    if (confirmAll) {
        val copy = applyCopy(state.ready.map { tags.changes(it, (it.proposal as Proposal.Ready).match) }, state.memory.noted)
        ConfirmDialog(
            title = pluralStringResource(R.plurals.tags_apply_all_title, copy.albums, copy.albums),
            confirm = stringResource(R.string.tags_apply_all),
            dismiss = stringResource(R.string.tags_cancel),
            text = pluralStringResource(R.plurals.tags_apply_all_text, copy.songs, copy.songs) + if (copy.note) "\n\n" + stringResource(R.string.tags_sync_note) else "",
            icon = R.drawable.ic_sell,
            destructive = false,
            confirmEnabled = copy.albums > 0 && writable,
            onConfirm = {
                confirmAll = false
                scope.launch {
                    if (copy.note) tags.noted()
                    toasts.show(appliedWords(tags.applyAll()).text(resources))
                }
            },
            onDismiss = { confirmAll = false },
        )
    }
    searching?.let { albumId ->
        val waiting = state.waiting.firstOrNull { it.album.id == albumId }
        if (waiting == null) {
            LaunchedEffect(albumId) { searching = null }
            return@let
        }
        SearchSheet(tags, waiting, onFound = { searching = null }, onDismiss = { searching = null })
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

/** The release [releaseId] among [waiting]'s proposal. */
private fun matchOf(waiting: Waiting, releaseId: String): ReleaseMatch? = when (val proposal = waiting.proposal) {
    is Proposal.Ready -> proposal.match.takeIf { it.release.id == releaseId }
    is Proposal.PickOne -> proposal.candidates.firstOrNull { it.release.id == releaseId }
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
        )
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun SectionHead(row: PageRow.Section, onApplyAll: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = ScreenPadding, end = 8.dp, top = 12.dp),
    ) {
        Text(stringResource(row.section.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(formatCount(row.count), style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (row.section == ReviewSection.READY) {
            LyrifyButton(stringResource(R.string.tags_apply_all), onClick = { onApplyAll?.invoke() }, compact = true, enabled = onApplyAll != null)
        }
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

/** A release's front cover from the Cover Art Archive, fetched once it shows; the glyph until then or without one. */
@Composable
private fun NewCover(tags: CoversTags, release: MbRelease, size: Dp) {
    val cover by produceState<Cover?>(null, release.id) { value = tags.cover(release) }
    SongCover(cover?.jpeg, size = size, shape = MaterialTheme.shapes.medium)
}

/** A Ready album: the new cover, the album and artist, and how many changes; a tap opens its sheet. */
@Composable
private fun ReadyCard(tags: CoversTags, waiting: Waiting, onOpen: () -> Unit) {
    val match = (waiting.proposal as Proposal.Ready).match
    val changes = remember(waiting, match) { tags.changes(waiting, match) }
    NavRow(
        title = match.release.title,
        sub = listOf(match.release.artist.text, summaryWords(changes).text()).filter(String::isNotEmpty).joinToString(" · "),
        lead = { NewCover(tags, match.release, 56.dp) },
        onClick = onOpen,
    )
}

/** A Pick one album: up to 3 candidates, each with Use; then Search myself and Skip. */
@Composable
private fun PickOneAlbum(tags: CoversTags, waiting: Waiting, onUse: (ReleaseMatch) -> Unit, onSearch: () -> Unit, onSkip: () -> Unit) {
    val album = waiting.album.album
    val candidates = (waiting.proposal as Proposal.PickOne).candidates
    LyrifyCard(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(album.title?.takeIf(String::isNotBlank) ?: album.songs.firstOrNull()?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val songs = pluralStringResource(R.plurals.tags_songs, album.songs.size, album.songs.size)
                Text(listOfNotNull(album.artist, songs).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.tags_no_match), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            candidates.forEach { match -> CandidateRow(tags, match) { onUse(match) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LyrifyButton(stringResource(R.string.tags_search_myself), onClick = onSearch, kind = ButtonKind.SECONDARY, compact = true, icon = R.drawable.ic_search)
                LyrifyButton(stringResource(R.string.tags_skip), onClick = onSkip, kind = ButtonKind.GHOST, compact = true)
            }
        }
    }
}

/** A candidate: cover, album, artist, "CD, 2009 · XE", "12 of 12 songs fit", and Use. */
@Composable
private fun CandidateRow(tags: CoversTags, match: ReleaseMatch, onUse: () -> Unit) {
    val release = match.release
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NewCover(tags, release, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(release.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(release.artist.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOf(releaseLine(release), fitWords(match.fit).text()).filter(String::isNotEmpty).joinToString(" · ")
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LyrifyButton(stringResource(R.string.tags_use), onClick = onUse, kind = ButtonKind.SECONDARY, compact = true)
    }
}

/** A Done album (Undo, or Undo fields once its full copies are gone) or a Skipped one (Check again). */
@Composable
private fun SettledRow(record: AlbumRecord, fullCopy: Boolean, enabled: Boolean, onUndo: () -> Unit, onCheckAgain: () -> Unit, onOpen: (() -> Unit)?) {
    val done = record.state == Settled.DONE
    val sub = if (done) doneLine(record, fullCopy).map { it.text() }.joinToString(" · ") else pluralStringResource(R.plurals.tags_songs, record.songs.size, record.songs.size)
    ItemRow(
        title = record.title,
        sub = listOfNotNull(record.artist, sub).joinToString(" · "),
        lead = { SongCover(record.songs.firstOrNull()?.let { SongArt(it.path) }, size = 48.dp) },
        trailing = {
            when {
                !done -> LyrifyButton(stringResource(R.string.tags_check_again), onClick = onCheckAgain, kind = ButtonKind.GHOST, compact = true)
                record.entryId != null -> LyrifyButton(
                    stringResource(if (fullCopy) R.string.tags_undo else R.string.tags_undo_fields),
                    onClick = onUndo,
                    kind = ButtonKind.GHOST,
                    compact = true,
                    enabled = enabled,
                )
            }
        },
        onClick = onOpen,
    )
}
