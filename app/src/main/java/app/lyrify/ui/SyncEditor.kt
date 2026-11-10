package app.lyrify.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.logFailures
import app.lyrify.library.Song
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcEdits
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.lyrics.LrcProblem
import app.lyrify.lyrics.LrcSave
import app.lyrify.lyrics.LrcUndone
import app.lyrify.player.PlayerGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

// The synced lyrics editor (P3): its lines as a pure model, then the screen over everything, with its own player.

/** One line in the sync editor: its words, and its time once stamped (null before). */
@Serializable
data class EditLine(val text: String, val timeMs: Long? = null)

/** A stamp Undo can take back: line [index] had the time [before] (null: none) until it was stamped. */
@Serializable
data class Stamp(val index: Int, val before: Long?)

/** Make synced (plain lyrics, no times yet) or Edit timing (synced lyrics, their times to change). */
enum class SyncMode { MAKE, EDIT }

/** A stamp as the editor shows it: "1:02.3" (minutes, seconds and tenths). */
fun stampText(ms: Long): String = clock(ms) + "." + (ms.coerceAtLeast(0) % 1_000 / 100)

/** Where the editor's player is: right after a jump, the place jumped to, which it may not report until the jump is done. */
fun playerPosition(seekingTo: Long?, reported: Long): Long = seekingTo ?: reported

/** A song's length for Save's check: the library's, else the one the player read (0 when neither knows). */
fun knownLength(library: Long, player: Long): Long = if (library > 0) library else player

/** Whether the lines can be saved, and as what. */
sealed interface SaveCheck {
    /** Samsung-safe `.lrc` text. */
    data class Ready(val lrc: String) : SaveCheck

    /** [count] lines have no time yet. */
    data class Unstamped(val count: Int) : SaveCheck

    /** Line [index] starts before the line above it. */
    data class OutOfOrder(val index: Int) : SaveCheck

    /** Your phone's music app would not show these lyrics properly. */
    data class Rejected(val problem: LrcProblem) : SaveCheck
}

/**
 * The editor's lines, pure: Stamp sets the time of the line at [cursor] and moves on, Undo takes back the last of
 * [stamps], a line can be nudged by [NUDGE_MS] and its words fixed. [changed] once anything was, so leaving asks first.
 * Save writes the lines in their order, each with its time, once every line has one and none starts before the line
 * above ([check]). Kept across a turn of the phone ([EDIT_SAVER]).
 */
@Serializable
data class SyncEdit(
    val mode: SyncMode,
    val lines: List<EditLine>,
    val cursor: Int = 0,
    val stamps: List<Stamp> = emptyList(),
    val changed: Boolean = false,
) {
    val stamped: Int get() = lines.count { it.timeMs != null }

    val complete: Boolean get() = lines.isNotEmpty() && stamped == lines.size

    /** Past the last line: nothing left to stamp. */
    val atEnd: Boolean get() = cursor >= lines.size

    /** The line at [cursor] starts at [positionMs]; the next line is up. */
    fun stamp(positionMs: Long): SyncEdit {
        if (atEnd) return this
        val time = positionMs.coerceAtLeast(0)
        return copy(lines = at(cursor) { it.copy(timeMs = time) }, cursor = cursor + 1, stamps = stamps + Stamp(cursor, lines[cursor].timeMs), changed = true)
    }

    /** The last stamp taken back: its line gets its time before, and is up again. */
    fun undo(): SyncEdit {
        val last = stamps.lastOrNull() ?: return this
        return copy(lines = at(last.index) { it.copy(timeMs = last.before) }, cursor = last.index, stamps = stamps.dropLast(1))
    }

    /** Line [index] [deltaMs] later (earlier when negative), never before the start; a line without a time stays. */
    fun nudge(index: Int, deltaMs: Long): SyncEdit {
        val time = lines.getOrNull(index)?.timeMs ?: return this
        return copy(lines = at(index) { it.copy(timeMs = (time + deltaMs).coerceAtLeast(0)) }, changed = true)
    }

    /** Line [index] reads [text]: one line, without control characters. */
    fun fix(index: Int, text: String): SyncEdit {
        if (index !in lines.indices) return this
        return copy(lines = at(index) { it.copy(text = text.replace(UNPRINTABLE, "").trim()) }, changed = true)
    }

    /** Stamp goes on from line [index]. */
    fun select(index: Int): SyncEdit = if (index in lines.indices) copy(cursor = index) else this

    /** Where the song goes when line [index] is picked: just before its time, so it can be stamped again; null without one. */
    fun seekFor(index: Int): Long? = lines.getOrNull(index)?.timeMs?.let { (it - LEAD_MS).coerceAtLeast(0) }

    /** Where Preview starts: just before the first time; null while nothing is stamped. */
    fun previewFrom(): Long? = lines.mapNotNull { it.timeMs }.minOrNull()?.let { (it - PREVIEW_LEAD_MS).coerceAtLeast(0) }

    /** The line the times say plays at [positionMs] (the latest start at or before it, the later line on a tie); -1 before any. */
    fun lineAt(positionMs: Long): Int {
        var best = -1
        lines.forEachIndexed { i, line ->
            val time = line.timeMs ?: return@forEachIndexed
            if (time <= positionMs && (best < 0 || time >= lines[best].timeMs!!)) best = i
        }
        return best
    }

    /** Whether Save can write the lines for a song of [durationMs] (0 when not known), and the `.lrc` text it writes. */
    fun check(durationMs: Long): SaveCheck {
        val missing = lines.count { it.timeMs == null }
        if (missing > 0 || lines.isEmpty()) return SaveCheck.Unstamped(missing)
        val times = lines.map { it.timeMs!! }
        val early = (1 until times.size).firstOrNull { times[it] < times[it - 1] }
        if (early != null) return SaveCheck.OutOfOrder(early)
        val lrc = LrcFormat.render(lines.map { LrcLine(it.timeMs!!, it.text) })
        return LrcFormat.validate(lrc, if (durationMs > 0) durationMs else UNKNOWN_LENGTH)?.let(SaveCheck::Rejected) ?: SaveCheck.Ready(lrc)
    }

    private fun at(index: Int, change: (EditLine) -> EditLine): List<EditLine> = lines.mapIndexed { i, line -> if (i == index) change(line) else line }

    companion object {
        /** A nudge: a tenth of a second. */
        const val NUDGE_MS: Long = 100

        /** A picked line plays from this much before its time. */
        const val LEAD_MS: Long = 1_500

        /** Preview starts this much before the first line. */
        const val PREVIEW_LEAD_MS: Long = 2_000

        /** Long enough that no stamp is past it. */
        private const val UNKNOWN_LENGTH: Long = Long.MAX_VALUE / 2

        private val UNPRINTABLE = Regex("""[\p{Cc}\p{Cf}]""")

        /**
         * The editor for a song whose `.lrc` holds [lrc] (null without one): its timed lines for Edit timing (a break
         * without words stays; an `[offset:]` tag applied, so Save writes the times meant), or its plain text for Make synced; without a file, LRCLIB's [plain] lyrics for Make
         * synced. Null when there are no lines.
         */
        fun from(lrc: String?, plain: String?): SyncEdit? {
            if (lrc != null) {
                val offset = LrcFormat.offsetMs(lrc)
                val timed = LrcFormat.parseLrclib(lrc)
                if (timed.any { it.text.isNotBlank() }) return SyncEdit(SyncMode.EDIT, timed.map { EditLine(it.text, (it.timeMs - offset).coerceAtLeast(0)) })
                plainLines(lrc).takeIf { it.isNotEmpty() }?.let { return SyncEdit(SyncMode.MAKE, it.map(::EditLine)) }
            }
            return plain?.let(::plainLines)?.takeIf { it.isNotEmpty() }?.let { lines -> SyncEdit(SyncMode.MAKE, lines.map(::EditLine)) }
        }
    }
}

/** The editor's lines across a turn of the phone: as JSON in the saved state (the lines stay in memory, never in a log). */
private val EDIT_SAVER: Saver<SyncEdit?, String> = Saver(
    save = { edit -> edit?.let { Json.encodeToString(SyncEdit.serializer(), it) } },
    restore = { text ->
        try {
            Json.decodeFromString(SyncEdit.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    },
)

/** Save and its Undo message go on once the editor has closed. */
private val editorScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures) }

/**
 * Make synced or Edit timing, over everything: the song plays in the editor's own player (KJB's queue, or another app,
 * pauses), with its lines listed. Stamp sets the line up to where the song is and moves on; a line picked plays from just
 * before its time, which can be nudged, and its words fixed; Undo takes back the last stamp; Preview plays the result
 * with the lines lit by their new times. Save, once every line has a time, writes the `.lrc` ([LrcEdits]) and closes,
 * its message offering Undo. Leaving with changes asks first. The screen stays on meanwhile.
 */
@Composable
fun SyncEditorView(view: FullScreen.SyncEditor) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val close = { nav.close(view) }
    val library by remember(context) { PlayerGraph.connection(context) }.library.collectAsStateWithLifecycle()
    val song = library?.resolver?.find(view.song)
    val entry = song?.let { LocalLyrics.current.ledger.entries[it.path] }
    var edit by rememberSaveable(view, stateSaver = EDIT_SAVER) { mutableStateOf(null) }
    var nothing by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf<Int?>(null) }
    var leaving by remember { mutableStateOf(false) }
    // Kept across a turn of the phone and the app's death: a Save under way is not started twice, the song not again.
    var saving by rememberSaveable { mutableStateOf(false) }
    val resumedSave = remember { saving }
    var started by rememberSaveable { mutableStateOf(false) }
    var position by rememberSaveable { mutableLongStateOf(0L) }
    val player = remember(context) { EditorPlayer(context) }
    var ready by remember { mutableStateOf(false) }

    // The lines, once: what the file or the ledger do later never replaces the user's work here.
    LaunchedEffect(song?.path) {
        val path = song?.path ?: return@LaunchedEffect
        if (edit == null) {
            val lrc = withContext(Dispatchers.IO) { readLrc(path) }
            val plain = if (lrc == null) plainRecord(entry)?.let { plainText(context, it) } else null
            edit = SyncEdit.from(lrc, plain)
            nothing = edit == null
        }
        if (edit != null && player.open(path)) {
            ready = true
            // The first time it plays; brought back, it waits where it was.
            if (started) {
                player.seekTo(position)
            } else {
                started = true
                player.play()
            }
        } else if (edit != null) {
            toasts.say(resources, R.string.sync_unplayable)
        }
    }
    // Brought back while a Save was under way: once it is done, closed when the lyrics are on disk, else Save again.
    LaunchedEffect(Unit) {
        val path = song?.path ?: view.song.path
        val lrc = (edit?.check(0) as? SaveCheck.Ready)?.lrc
        if (!resumedSave) return@LaunchedEffect
        val edits = LrcEdits.of(context.applicationContext)
        edits.saving.first { path !in it }
        if (lrc != null && withContext(Dispatchers.IO) { edits.isOnDisk(path, lrc) }) close() else saving = false
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing) {
        position = player.positionMs
        if (!player.playing) previewing = false
        while (player.playing) {
            delay(TICK_MS)
            position = player.positionMs
        }
    }
    val screen = LocalView.current
    DisposableEffect(screen) {
        screen.keepScreenOn = true
        onDispose { screen.keepScreenOn = false }
    }
    BackHandler(enabled = edit?.changed == true) { leaving = true }

    fun seek(ms: Long) {
        player.seekTo(ms)
        position = ms
    }

    fun save(lines: SyncEdit, found: Song) {
        when (val check = lines.check(knownLength(found.durationMs, player.durationMs))) {
            is SaveCheck.Ready -> {
                if (saving) return
                saving = true
                val app = context.applicationContext
                val track = LocalTrack(found.path, found.title, found.artist, found.album, found.durationMs)
                // Its message and Undo come from the shell ([LyricsUndoOffers]), also once this view is gone.
                editorScope.launch {
                    val result = withContext(Dispatchers.IO) { LrcEdits.of(app).save(track, check.lrc) }
                    saving = false
                    if (result !is LrcSave.Saved) {
                        toasts.say(resources, R.string.sync_not_saved)
                        return@launch
                    }
                    player.pause()
                    close()
                }
            }
            is SaveCheck.OutOfOrder -> {
                edit = lines.select(check.index)
                scope.launch { toasts.say(resources, R.string.sync_out_of_order) }
            }
            is SaveCheck.Rejected -> scope.launch { toasts.say(resources, if (check.problem == LrcProblem.PAST_DURATION) R.string.sync_past_end else R.string.sync_too_few) }
            is SaveCheck.Unstamped -> Unit
        }
    }

    val current = edit
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, end = 12.dp)) {
            ChromeIconButton(R.drawable.ic_close, stringResource(R.string.sync_close), { if (current?.changed == true) leaving = true else close() })
            Text(
                stringResource(if (current?.mode == SyncMode.EDIT) R.string.lyrics_edit_timing else R.string.lyrics_make_synced),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LyrifyButton(
                stringResource(R.string.sync_save),
                { if (current != null && song != null) save(current, song) },
                icon = R.drawable.ic_save,
                enabled = current?.complete == true && song != null && !saving,
                compact = true,
            )
        }
        when {
            library != null && song == null -> EmptyState(EmptyArt.Glyph, stringResource(R.string.player_not_on_phone))
            nothing -> EmptyState(EmptyArt.Icon(R.drawable.ic_lyrics, Tone.NONE), stringResource(R.string.sync_nothing), text = stringResource(R.string.sync_nothing_text))
            current == null || song == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { BrandGlyph(width = 28.dp, sweeping = true) }
            else -> {
                SongCard(song, current, previewing, player.playing, position, player.durationMs, onBack = { seek((position - BACK_MS).coerceAtLeast(0)) }, onPlay = { if (player.playing) player.pause() else player.play() }, onSeek = ::seek)
                EditorLines(
                    current,
                    highlight = if (previewing) current.lineAt(position) else current.cursor,
                    previewing = previewing,
                    onSelect = { i ->
                        edit = current.select(i)
                        previewing = false
                        current.seekFor(i)?.let(::seek)
                    },
                    onNudge = { i, delta -> edit = current.nudge(i, delta) },
                    onFix = { fixing = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ToastSlot(Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
        if (current != null && song != null) {
            GlassSurface(null, RoundedCornerShape(32.dp), Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyrifyButton(
                        stringResource(if (current.atEnd) R.string.sync_all_stamped else R.string.sync_stamp),
                        { edit = current.stamp(player.positionMs) },
                        Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        icon = R.drawable.ic_touch_app,
                        enabled = ready && !previewing && !current.atEnd,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LyrifyButton(stringResource(R.string.sync_undo), { edit = current.undo() }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_undo, enabled = current.stamps.isNotEmpty())
                        LyrifyButton(
                            stringResource(if (previewing) R.string.sync_stop else R.string.sync_preview),
                            {
                                if (previewing) {
                                    previewing = false
                                    player.pause()
                                } else {
                                    current.previewFrom()?.let { from ->
                                        seek(from)
                                        player.play()
                                        previewing = player.playing
                                    }
                                }
                            },
                            Modifier.weight(1f),
                            kind = ButtonKind.SECONDARY,
                            icon = if (previewing) R.drawable.ic_stop_circle else R.drawable.ic_play_circle,
                            enabled = ready && current.stamped > 0,
                        )
                    }
                }
            }
        }
    }

    val fixed = fixing
    if (current != null && fixed != null && fixed in current.lines.indices) {
        var text by rememberSaveable(fixed) { mutableStateOf(current.lines[fixed].text) }
        ConfirmDialog(
            title = stringResource(R.string.sync_fix_title),
            confirm = stringResource(R.string.sync_fix_done),
            dismiss = stringResource(R.string.sync_cancel),
            onConfirm = {
                edit = current.fix(fixed, text)
                fixing = null
            },
            onDismiss = { fixing = null },
            icon = R.drawable.ic_edit,
            destructive = false,
        ) { LyrifyField(text, { text = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.sync_fix_field)) }
    }
    if (leaving) {
        ConfirmDialog(
            title = stringResource(R.string.sync_leave_title),
            confirm = stringResource(R.string.sync_leave),
            dismiss = stringResource(R.string.sync_cancel),
            onConfirm = {
                leaving = false
                close()
            },
            onDismiss = { leaving = false },
            text = stringResource(R.string.sync_leave_text),
            icon = R.drawable.ic_edit,
        )
    }
}

/**
 * "Synced lyrics saved" with Undo, for each Save whose Undo is neither used nor let go ([LrcEdits.pending]), one at a
 * time: also after a turn of the phone or a restart of the app, so an Undo is never lost while its message is due. The
 * message gone, the Undo is let go, its copy of the lyrics before with it: a newer message takes its place at once, but
 * the Undo is let go only at the message's own time ([ToastState.show]), never sooner. The shell shows it.
 */
@Composable
fun LyricsUndoOffers() {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    LaunchedEffect(toasts) {
        val edits = LrcEdits.of(context.applicationContext)
        withContext(Dispatchers.IO) { edits.load() }
        edits.pending.collect { pending ->
            val undo = pending.firstOrNull() ?: return@collect
            val asked = toasts.show(Toast(resources.getString(R.string.sync_saved), R.drawable.ic_lyrics, Tone.SYNCED, action = resources.getString(R.string.sync_undo)))
            if (asked) {
                val undone = withContext(Dispatchers.IO) { edits.undo(undo) }
                toasts.say(
                    resources,
                    when (undone) {
                        LrcUndone.DONE -> R.string.sync_undone
                        LrcUndone.CHANGED -> R.string.sync_undo_changed
                        LrcUndone.FAILED -> R.string.sync_undo_failed
                    },
                )
            } else {
                withContext(Dispatchers.IO) { edits.forget(undo) }
            }
        }
    }
}

/** The song, how far the stamping is (or "Preview"), Back 5 s and Play/Pause, and the position. */
@Composable
private fun SongCard(song: Song, edit: SyncEdit, previewing: Boolean, playing: Boolean, positionMs: Long, durationMs: Long, onBack: () -> Unit, onPlay: () -> Unit, onSeek: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SongCover(SongArt(song.path), size = 44.dp, shape = RoundedCornerShape(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (previewing) stringResource(R.string.sync_previewing) else pluralStringResource(R.plurals.sync_stamped, edit.lines.size, edit.stamped, edit.lines.size),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ChromeIconButton(R.drawable.ic_replay_5, stringResource(R.string.sync_back5), onBack, glass = false, size = 48.dp, iconSize = 24.dp)
            PlayPauseButton(playing, onPlay, size = 48.dp)
        }
        EditorSeekBar(positionMs, durationMs, onSeek)
    }
}

/** The position: a tap or a drag (on release) seeks; the times under it. */
@Composable
private fun EditorSeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val share = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val highlight = LocalBrandColors.current.highlight
    val label = stringResource(R.string.player_position)
    val state = "${clock(positionMs)} / ${clock(durationMs)}"
    val seek by rememberUpdatedState(onSeek)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(TOUCH)
            .semantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(share, 0f..1f)
                setProgress { value ->
                    if (durationMs > 0) seek((value.coerceIn(0f, 1f) * durationMs).toLong())
                    durationMs > 0
                }
            }
            .pointerInput(durationMs) { detectTapGestures { at -> if (durationMs > 0) seek((at.x / size.width).coerceIn(0f, 1f).times(durationMs).toLong()) } }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { at -> dragging = (at.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let { if (durationMs > 0) seek((it * durationMs).toLong()) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(highlight.copy(alpha = 0.16f)))
        Box(Modifier.fillMaxWidth(share).height(4.dp).clip(CircleShape).background(highlight))
        BoxWithConstraints(Modifier.fillMaxWidth()) { Box(Modifier.offset(x = maxWidth * share - 7.dp).size(14.dp).clip(CircleShape).background(highlight)) }
    }
    Row(Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.bodySmall.tabular()
        Text(clock(dragging?.let { (it * durationMs).toLong() } ?: positionMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(clock(durationMs), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The lines, each with its time ("-:--.-" before it has one); the line up ([highlight]: the one Stamp sets, or in Preview
 * the one its time says plays) is lit and kept in view, and gets the nudges and Fix text. A tap picks a line.
 */
@Composable
private fun EditorLines(
    edit: SyncEdit,
    highlight: Int,
    previewing: Boolean,
    onSelect: (Int) -> Unit,
    onNudge: (Int, Long) -> Unit,
    onFix: (Int) -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    val offset = with(LocalDensity.current) { 96.dp.roundToPx() }
    LaunchedEffect(highlight, edit.lines.size) {
        val at = highlight.coerceAtMost(edit.lines.lastIndex)
        if (at < 0) return@LaunchedEffect
        if (reduced) list.scrollToItem(at, -offset) else list.animateScrollToItem(at, -offset)
    }
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    LazyColumn(state = list, modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(edit.lines) { i, line ->
            val on = i == highlight
            val time = line.timeMs
            val words = line.text.ifBlank { stringResource(R.string.sync_break) }
            val label = stringResource(R.string.sync_line_cd, time?.let(::stampText) ?: stringResource(R.string.sync_no_time_cd), words)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .then(if (on) Modifier.background(brand.highlightContainer).border(1.5.dp, brand.highlight, RoundedCornerShape(18.dp)) else Modifier),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button) { onSelect(i) }
                        .semantics { contentDescription = label }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                ) {
                    Text(
                        time?.let(::stampText) ?: stringResource(R.string.sync_no_time),
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = when {
                            on -> brand.onHighlightContainer
                            time == null -> colors.outline
                            else -> colors.onSurfaceVariant
                        },
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        words,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            on -> brand.onHighlightContainer
                            time == null || line.text.isBlank() -> colors.onSurfaceVariant
                            else -> colors.onSurface
                        },
                    )
                }
                if (on && !previewing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 88.dp, end = 12.dp, bottom = 8.dp)) {
                        if (time != null) {
                            NudgeChip(R.string.sync_earlier, R.string.sync_earlier_cd) { onNudge(i, -SyncEdit.NUDGE_MS) }
                            NudgeChip(R.string.sync_later, R.string.sync_later_cd) { onNudge(i, SyncEdit.NUDGE_MS) }
                        }
                        LyrifyChip(selected = false, onClick = { onFix(i) }, role = Role.Button, icon = R.drawable.ic_edit) { Text(stringResource(R.string.sync_fix)) }
                    }
                }
            }
        }
        if (edit.atEnd && !previewing) {
            item {
                val left = edit.lines.size - edit.stamped
                Text(
                    if (left == 0) stringResource(R.string.sync_every_line) else pluralStringResource(R.plurals.sync_to_go, left, left),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun NudgeChip(text: Int, description: Int, onClick: () -> Unit) {
    val label = stringResource(description)
    LyrifyChip(selected = false, onClick = onClick, role = Role.Button, modifier = Modifier.semantics { contentDescription = label }) {
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge.tabular())
    }
}

/**
 * The editor's own player: the song alone (MediaPlayer; the file is only read), holding audio focus while it plays, so
 * KJB's queue, or any other app, pauses and stays paused. [playing] and [durationMs] are state for the screen. Main
 * thread, but for the file's opening.
 */
private class EditorPlayer(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()
    private var player: MediaPlayer? = null
    private var released = false

    /** A jump not done yet: until it is, the player may still report where it was. */
    private var seekingTo: Long? = null

    var playing: Boolean by mutableStateOf(false)
        private set

    var durationMs: Long by mutableLongStateOf(0L)
        private set

    val positionMs: Long get() = playerPosition(seekingTo, player?.currentPosition?.toLong() ?: 0)

    /** Opens the song at [path]; false when it cannot be played. */
    suspend fun open(path: String): Boolean {
        val opened = withContext(Dispatchers.IO + NonCancellable) {
            val media = MediaPlayer()
            try {
                media.setAudioAttributes(attributes)
                media.setDataSource(path)
                media.prepare()
                media
            } catch (e: IOException) {
                media.release()
                Log.w(LOG_TAG, "sync editor: song not opened: ${e.javaClass.simpleName}")
                null
            } catch (e: RuntimeException) {
                // IllegalStateException, IllegalArgumentException, SecurityException.
                media.release()
                Log.w(LOG_TAG, "sync editor: song not opened: ${e.javaClass.simpleName}")
                null
            }
        } ?: return false
        if (released || !currentCoroutineContext().isActive) {
            opened.release()
            return false
        }
        player?.release()
        player = opened
        durationMs = opened.duration.toLong().coerceAtLeast(0)
        opened.setOnCompletionListener { stopped() }
        opened.setOnSeekCompleteListener { seekingTo = null }
        return true
    }

    fun play() {
        val media = player ?: return
        if (playing) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        media.start()
        playing = true
    }

    fun pause() {
        if (playing) player?.pause()
        stopped()
    }

    fun seekTo(ms: Long) {
        val media = player ?: return
        val target = ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        seekingTo = target
        media.seekTo(target, MediaPlayer.SEEK_CLOSEST)
    }

    fun release() {
        released = true
        pause()
        player?.release()
        player = null
    }

    private fun stopped() {
        playing = false
        audio.abandonAudioFocusRequest(focus)
    }
}

/** How often the position on screen moves on while the song plays. */
private const val TICK_MS: Long = 100

/** The back button's jump. */
private const val BACK_MS: Long = 5_000
