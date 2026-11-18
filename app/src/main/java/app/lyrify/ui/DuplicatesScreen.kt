package app.lyrify.ui

import android.app.Activity
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.Confidence
import app.lyrify.dupes.Confirmation
import app.lyrify.dupes.DuplicateGroup
import app.lyrify.dupes.Duplicates
import app.lyrify.dupes.DuplicatesState
import app.lyrify.dupes.MediaStoreTrash
import app.lyrify.dupes.Restored
import app.lyrify.dupes.ScanResults
import app.lyrify.dupes.ScanStatus
import app.lyrify.dupes.SongTrash
import app.lyrify.dupes.TrashAsk
import app.lyrify.dupes.TrashStep
import app.lyrify.dupes.Trashed
import app.lyrify.dupes.duplicates
import app.lyrify.lyrics.TrackNames
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

// What the tab shows

/** The Duplicates tab's one view of its engine's state. */
sealed interface DupesView {
    /** The stored results are being read: nothing shows yet. */
    data object Loading : DupesView

    /** Before the first scan: "Find duplicates". */
    data object FirstScan : DupesView

    /** The ring and "Scanning 412/1,358"; [total] is 0 while the list of songs is read. */
    data class Scanning(val done: Int, val total: Int) : DupesView {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    /** The songs could not be read, and there are no results to show. */
    data object Failed : DupesView

    /** The last scan's [results]; [failed]: a scan since could not read the songs. */
    data class Results(val results: ScanResults, val failed: Boolean) : DupesView
}

fun dupesView(state: DuplicatesState): DupesView {
    val scan = state.scan
    val results = state.results
    return when {
        !state.loaded -> DupesView.Loading
        scan is ScanStatus.Running -> DupesView.Scanning(scan.done, scan.total)
        results == null -> if (scan == ScanStatus.Failed) DupesView.Failed else DupesView.FirstScan
        else -> DupesView.Results(results, failed = scan == ScanStatus.Failed)
    }
}

/** When the last scan was, for "Scanned today 18:40". */
enum class ScannedDay(@StringRes val label: Int) {
    TODAY(R.string.dupes_scanned_today),
    YESTERDAY(R.string.dupes_scanned_yesterday),
    EARLIER(R.string.dupes_scanned_on),
}

fun scannedDay(scannedAt: Long, now: Long, zone: ZoneId): ScannedDay {
    val day = Instant.ofEpochMilli(scannedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> ScannedDay.TODAY
        today.minusDays(1) -> ScannedDay.YESTERDAY
        else -> ScannedDay.EARLIER
    }
}

/** The last quarter a scan of [total] files passed at [done]: 25, 50 or 75 (percent), announced once each; null before. */
fun scanMilestone(done: Int, total: Int): Int? {
    if (total <= 0) return null
    val quarters = (done.toLong() * QUARTERS / total).toInt()
    return (quarters * PERCENT_PER_QUARTER).takeIf { quarters in 1 until QUARTERS }
}

@StringRes
fun sectionTitle(confidence: Confidence): Int = when (confidence) {
    Confidence.IDENTICAL -> R.string.dupes_section_identical
    Confidence.SAME_SONG -> R.string.dupes_section_same
    Confidence.MAYBE -> R.string.dupes_section_maybe
}

/** The last words of a group card's TalkBack description. */
@StringRes
fun levelDescription(confidence: Confidence): Int = when (confidence) {
    Confidence.IDENTICAL -> R.string.dupes_level_identical
    Confidence.SAME_SONG -> R.string.dupes_level_same
    Confidence.MAYBE -> R.string.dupes_level_maybe
}

/** The group sheet's sentence: why these files are together, and for a maybe, to check first. */
@StringRes
fun groupSentence(confidence: Confidence): Int = when (confidence) {
    Confidence.IDENTICAL -> R.string.dupes_sheet_identical
    Confidence.SAME_SONG -> R.string.dupes_sheet_same
    Confidence.MAYBE -> R.string.dupes_sheet_maybe
}

/** Where a file is, as the user knows it: its folder and name ("kjb/03 Song.mp3"), or the name alone in the top folder. */
fun fileLocation(file: AudioFile): String {
    val folder = file.relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    return if (folder != null) "$folder/${file.displayName}" else file.displayName
}

/** The file's format as a short name: its extension ("MP3", "FLAC"), else the MIME type's ("audio/ogg": "OGG"). */
fun fileFormat(file: AudioFile): String =
    (file.displayName.substringAfterLast('.', "").ifEmpty { null } ?: file.mimeType?.substringAfterLast('/').orEmpty()).uppercase(Locale.ROOT)

/** The bitrate MediaStore knows, in whole kbps; null when it has none. */
fun fileKbps(file: AudioFile): Int? = file.bitrate?.takeIf { it > 0 }?.let { ((it + BPS_PER_KBPS / 2) / BPS_PER_KBPS).toInt() }

/** "Move to trash" with [checked] of a group's [copies] checked: at least one, and never every copy. */
fun canMove(checked: Int, copies: Int): Boolean = checked in 1 until copies

/** Every copy is checked: "KJB keeps one copy of each song." shows, and "Move to trash" waits. */
fun mustKeepOne(checked: Int, copies: Int): Boolean = copies > 0 && checked >= copies

/** Why "Move to trash" in a group's sheet asked nothing ([Duplicates.askTrash]); null when KJB's dialog opens or nothing is checked. */
@StringRes
fun moveRefused(ask: TrashAsk): Int? = when (ask) {
    TrashAsk.KEEP_ONE -> R.string.dupes_keep_one
    TrashAsk.CHANGED -> R.string.dupes_changed
    TrashAsk.UNAVAILABLE -> R.string.dupes_trash_failed
    TrashAsk.ASKED, TrashAsk.NONE_CHECKED -> null
}

/**
 * A message about the trash: a string, or a plural whose form [Count.count] picks, showing [Count.numbers]; [Freed] shows
 * the room freed after its numbers.
 */
sealed interface TrashSaid {
    data class Text(@StringRes val res: Int) : TrashSaid

    data class Count(@PluralsRes val res: Int, val count: Int, val numbers: List<Int> = listOf(count)) : TrashSaid

    data class Freed(@PluralsRes val res: Int, val count: Int, val numbers: List<Int>, val bytes: Long) : TrashSaid
}

/**
 * What the screen says once the phone's trash dialog gave [answer], [approved] (its OK) or cancelled: how many moved,
 * of how many; that nothing moved; or that KJB could not check yet. Null only when nothing waited for this answer.
 */
fun trashAnswered(approved: Boolean, answer: Trashed): TrashSaid? = when {
    !approved -> TrashSaid.Text(R.string.dupes_trash_cancelled)
    answer is Trashed.Moved && answer.count == 0 -> TrashSaid.Count(R.plurals.dupes_moved_none, answer.of)
    answer is Trashed.Moved && answer.count < answer.of -> TrashSaid.Count(R.plurals.dupes_moved_some, answer.of, listOf(answer.count, answer.of))
    answer is Trashed.Moved -> TrashSaid.Count(R.plurals.dupes_moved, answer.count)
    answer == Trashed.NotConfirmed -> TrashSaid.Text(R.string.dupes_trash_unconfirmed)
    else -> null
}

/** Shows [said] with the icon and tone that fit it ([messageToast]). */
internal suspend fun ToastState.say(context: Context, said: TrashSaid): Boolean {
    val resources = context.resources
    return when (said) {
        is TrashSaid.Text -> say(resources, said.res)
        is TrashSaid.Count -> show(messageToast(said.res, resources.getQuantityString(said.res, said.count, *said.numbers.toTypedArray())))
        is TrashSaid.Freed -> {
            val words = resources.getQuantityString(said.res, said.count, *said.numbers.toTypedArray(), Formatter.formatShortFileSize(context, said.bytes))
            show(messageToast(said.res, words))
        }
    }
}

// The tab

/** The app's Duplicates engine, for the screens. */
@Composable
fun rememberDuplicates(): Duplicates {
    val context = LocalContext.current
    return remember(context) { duplicates(context) }
}

/** The dock's badge: groups not opened yet. */
@Composable
fun duplicatesBadge(): Int {
    val state by rememberDuplicates().state.collectAsStateWithLifecycle()
    return state.badge
}

/**
 * Opens the phone's own trash dialog for MediaStore ids (to the trash, or with trash false back out of it) and hands the
 * answer to the shared trash flow ([SongTrash]: Duplicates and the song sheet), whichever of its dialogs was open; what
 * it did shows in [toasts] ([trashAnswered]), and so does a request the phone refused.
 */
@Composable
fun rememberPhoneTrash(songTrash: SongTrash, toasts: ToastState): (List<Long>, Boolean) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val approved = result.resultCode == Activity.RESULT_OK
        scope.launch {
            if (songTrash.step is TrashStep.Restoring) {
                if (songTrash.restoreAnswered(approved) == Restored.Back) toasts.say(resources, R.string.dupes_restored)
            } else {
                trashAnswered(approved, songTrash.answered(approved))?.let { toasts.say(context, it) }
            }
        }
    }
    return remember(launcher, songTrash) {
        { ids, trash ->
            when (val request = MediaStoreTrash(context.contentResolver, Lyrify.writes).request(ids, trash)) {
                is MediaStoreTrash.Request.Ready -> launcher.launch(IntentSenderRequest.Builder(request.intent).build())
                is MediaStoreTrash.Request.Unavailable -> scope.launch {
                    Log.w(LOG_TAG, "trash: the phone refused the request (${request.cause})")
                    if (trash) {
                        songTrash.answered(approved = false)
                        toasts.say(context, TrashSaid.Count(R.plurals.dupes_trash_refused, ids.size))
                    } else {
                        songTrash.restoreAnswered(approved = false)
                        toasts.say(resources, R.string.dupes_trash_failed)
                    }
                }
            }
        }
    }
}

/** Duplicates' top bar in Tools: back, its name and More options (Scan again, and Trashed songs, which opens Tools > Trash). */
@Composable
fun DuplicatesTopBar(under: Boolean, backdrop: GlassBackdrop, onBack: () -> Unit) {
    val nav = LocalNavigator.current
    val engine = rememberDuplicates()
    val state by engine.state.collectAsStateWithLifecycle()
    val trashed = rememberTrashSongs().orEmpty()
    var menu by remember { mutableStateOf(false) }
    LyrifyTopBar(
        title = { Text(stringResource(R.string.dupes_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        under = under,
        backdrop = backdrop,
        navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
        actions = {
            val scanAgain = state.results != null && state.scan !is ScanStatus.Running
            if (scanAgain || trashed.isNotEmpty()) Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { menu = true })
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    if (scanAgain) {
                        MenuRow(
                            stringResource(R.string.dupes_scan_again),
                            R.drawable.ic_refresh,
                            onClick = {
                                menu = false
                                engine.scan()
                            },
                        )
                    }
                    if (trashed.isNotEmpty()) {
                        MenuRow(
                            stringResource(R.string.dupes_menu_trashed),
                            R.drawable.ic_restore_from_trash,
                            onClick = {
                                menu = false
                                nav.open(Page.Trash)
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * The Duplicates tab: before the first scan, the scan's ring, or the groups found by confidence. A group opens its sheet;
 * moving copies to the trash asks twice, KJB's dialog and then the phone's. The songs in the trash are in Tools > Trash.
 */
@Composable
fun DuplicatesScreen(contentPadding: PaddingValues, toasts: ToastState) {
    val engine = rememberDuplicates()
    val state by engine.state.collectAsStateWithLifecycle()
    val phoneTrash = rememberPhoneTrash(engine.trash, toasts)
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(engine) { engine.refreshTrashed() }

    Box(Modifier.fillMaxSize()) {
        // Each kind of view fades through to the next; a scan's progress or new results stay in place.
        FadeThrough(dupesView(state), Modifier.fillMaxSize(), kind = { it::class }) { view ->
            when (view) {
                DupesView.Loading -> Unit
                DupesView.FirstScan -> CenteredState(contentPadding) {
                    EmptyState(EmptyArt.Glyph, stringResource(R.string.dupes_empty_title), text = stringResource(R.string.dupes_empty), action = stringResource(R.string.dupes_scan), onAction = engine::scan)
                }
                is DupesView.Scanning -> CenteredState(contentPadding) { ScanningView(view, engine::cancelScan) }
                DupesView.Failed -> CenteredState(contentPadding) {
                    EmptyState(
                        EmptyArt.Icon(R.drawable.ic_error, Tone.ERROR),
                        stringResource(R.string.dupes_failed_title),
                        text = stringResource(R.string.dupes_failed),
                        action = stringResource(R.string.dupes_try_again),
                        onAction = engine::scan,
                    )
                }
                is DupesView.Results -> ResultsList(
                    view = view,
                    contentPadding = contentPadding,
                    onScan = engine::scan,
                    onOpen = { group ->
                        engine.opened(group)
                        open = group.key
                    },
                )
            }
        }
        ScanAnnouncer(state)
    }

    val group = open?.let { key -> state.results?.groups?.firstOrNull { it.key == key } }
    // A group gone meanwhile (moved to the trash, rescanned): its sheet closes.
    LaunchedEffect(open, group == null, state.loaded) { if (group == null && state.loaded) open = null }
    group?.let {
        GroupSheet(
            group = it,
            stale = it.key in state.stale,
            onMove = { ids ->
                // KJB's dialog opens, or a message says why it did not.
                scope.launch { moveRefused(engine.askTrash(it, ids))?.let { message -> toasts.say(resources, message) } }
            },
            onNotDuplicates = {
                engine.notDuplicates(it)
                open = null
            },
            onDismiss = { open = null },
        )
    }
    (state.trash as? TrashStep.Confirm)?.let { confirm ->
        SongTrashDialog(
            count = confirm.files.size,
            onCancel = engine::cancelTrash,
            onConfirm = {
                // The group shows again with what is left; the message shows above the dock.
                open = null
                scope.launch {
                    when (val confirmation = engine.confirmTrash(confirm.files)) {
                        is Confirmation.Ask -> phoneTrash(confirmation.ids, true)
                        Confirmation.Changed -> toasts.say(resources, R.string.dupes_changed)
                        Confirmation.Unavailable -> toasts.say(resources, R.string.dupes_trash_failed)
                        Confirmation.NotAsked -> Unit
                    }
                }
            },
        )
    }
}

/** A state in the middle of a page, scrolling when the phone's text is large. */
@Composable
internal fun CenteredState(contentPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()),
    ) { content() }
}

/** The AI's ring, filling as files are looked at, "Scanning 412/1,358" and Cancel. */
@Composable
private fun ScanningView(view: DupesView.Scanning, onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 32.dp),
    ) {
        AiRing(Ring(view.fraction, RingColor.HIGHLIGHT), Centre.Value(CentreValue.Percent((view.fraction * 100).toInt())), size = 200.dp)
        Text(
            if (view.total > 0) stringResource(R.string.dupes_scanning, formatCount(view.done), formatCount(view.total)) else stringResource(R.string.dupes_scanning_start),
            style = MaterialTheme.typography.titleMedium.tabular(),
            textAlign = TextAlign.Center,
        )
        LyrifyButton(stringResource(R.string.dupes_cancel), onClick = onCancel, kind = ButtonKind.SECONDARY, compact = true)
    }
}

/**
 * Says the scan's progress to TalkBack at each quarter and once it is done, without a word for every file: a polite live
 * region that only changes at those moments.
 */
@Composable
private fun ScanAnnouncer(state: DuplicatesState) {
    val scan = state.scan
    var wasRunning by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val milestone = (scan as? ScanStatus.Running)?.let { scanMilestone(it.done, it.total) }
    val groups = state.results?.groups?.size ?: 0
    val doneText = if (groups > 0) pluralStringResource(R.plurals.dupes_scan_done, groups, groups) else stringResource(R.string.dupes_scan_done_none)
    LaunchedEffect(scan is ScanStatus.Running) {
        val running = scan is ScanStatus.Running
        if (wasRunning && !running && scan == ScanStatus.Idle) done = doneText
        if (running) done = null
        wasRunning = running
    }
    val text = milestone?.let { stringResource(R.string.dupes_scan_milestone, it) } ?: done ?: return
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text })
}

@Composable
private fun ResultsList(
    view: DupesView.Results,
    contentPadding: PaddingValues,
    onScan: () -> Unit,
    onOpen: (DuplicateGroup) -> Unit,
) {
    val results = view.results
    val reduced = LocalReducedMotion.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item(key = "summary", contentType = "summary") { Box(rowMotion(reduced)) { Summary(results, view.failed, onScan) } }
        if (results.groups.isEmpty()) {
            item(key = "none", contentType = "empty") {
                EmptyState(
                    EmptyArt.Icon(R.drawable.ic_check_circle, Tone.SYNCED),
                    stringResource(R.string.dupes_none_title),
                    rowMotion(reduced),
                    text = stringResource(R.string.dupes_none),
                    action = stringResource(R.string.dupes_scan_again),
                    onAction = onScan,
                )
            }
        }
        Confidence.entries.forEach { confidence ->
            val groups = results.section(confidence)
            if (groups.isNotEmpty()) {
                item(key = "section $confidence", contentType = "section") { Box(rowMotion(reduced)) { SectionHeader(stringResource(sectionTitle(confidence)), groups.size) } }
                items(groups, key = { it.key }, contentType = { "group" }) { group -> GroupCard(group, rowMotion(reduced)) { onOpen(group) } }
            }
        }
    }
}

/** "12 groups · 1.4 GB" and "Scanned today 18:40"; above them, when the last scan failed, a banner to try again. */
@Composable
private fun Summary(results: ScanResults, failed: Boolean, onScan: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (failed) {
            Banner(
                R.drawable.ic_warning,
                stringResource(R.string.dupes_failed_title),
                stringResource(R.string.dupes_try_again),
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                onAction = onScan,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (results.groups.isNotEmpty()) {
            val groups = pluralStringResource(R.plurals.dupes_groups, results.groups.size, results.groups.size)
            Text(
                stringResource(R.string.dupes_summary, groups, Formatter.formatShortFileSize(context, results.extraBytes)),
                style = MaterialTheme.typography.titleLarge.tabular(),
            )
        }
        val day = scannedDay(results.scannedAt, System.currentTimeMillis(), ZoneId.systemDefault())
        val time = if (day == ScannedDay.EARLIER) {
            DateUtils.formatDateTime(context, results.scannedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        } else {
            DateFormat.getTimeFormat(context).format(Date(results.scannedAt))
        }
        Text(stringResource(day.label, time), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section's name and its count, a heading for TalkBack. */
@Composable
private fun SectionHeader(title: String, count: Int) {
    SectionTitle(
        stringResource(R.string.dupes_section, title, formatCount(count)),
        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 8.dp),
    )
}

/** One TalkBack stop: "title, artist, 2 copies, identical"; the cover is decorative. */
@Composable
private fun GroupCard(group: DuplicateGroup, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val keep = group.keep
    val artist = keep.artist?.takeIf { TrackNames.cleanArtist(it) != null } ?: stringResource(R.string.check_no_artist)
    val copies = pluralStringResource(R.plurals.dupes_copies, group.copies, group.copies, Formatter.formatShortFileSize(context, group.extraBytes))
    val description = stringResource(
        R.string.dupes_group_cd,
        keep.title,
        artist,
        pluralStringResource(R.plurals.dupes_copies_cd, group.copies, group.copies),
        stringResource(levelDescription(group.confidence)),
    )
    LyrifyCard(
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp).semantics { contentDescription = description },
        onClick = onOpen,
        onClickLabel = stringResource(R.string.dupes_open_group),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            // Copies at a glance: a second cover tile behind the first, turned a little.
            Box {
                Box(
                    Modifier
                        .offset(x = 6.dp)
                        .size(56.dp)
                        .graphicsLayer { rotationZ = 6f }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
                )
                SongCover(SongArt(keep.path), size = 56.dp)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(keep.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(copies, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val QUARTERS = 4
private const val PERCENT_PER_QUARTER = 25
private const val BPS_PER_KBPS = 1_000L
