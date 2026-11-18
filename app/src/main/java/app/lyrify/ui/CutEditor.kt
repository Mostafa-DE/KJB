package app.lyrify.ui

import android.os.SystemClock
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.cut.CutDraft
import app.lyrify.cut.CutEncoders
import app.lyrify.cut.CutEnd
import app.lyrify.cut.CutFailure
import app.lyrify.cut.CutFiles
import app.lyrify.cut.CutFolder
import app.lyrify.cut.CutQuality
import app.lyrify.cut.CutRange
import app.lyrify.cut.CutRequest
import app.lyrify.cut.CutResult
import app.lyrify.cut.CutSize
import app.lyrify.cut.Fade
import app.lyrify.cut.cutTime
import app.lyrify.library.Song
import app.lyrify.lyrics.TrackNames
import app.lyrify.player.embeddedPictureBytes
import app.lyrify.tones.FadeCurve
import app.lyrify.tones.FadeEnvelope
import app.lyrify.tones.PreviewCommand
import app.lyrify.tones.PreviewState
import app.lyrify.tones.PreviewStep
import app.lyrify.tones.ToneAudio
import app.lyrify.tones.TonePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

// The cutter: a part of a song, with fades, saved as a new M4A beside it. Opened from the song sheet's Cut.

/** What a finger on the waveform takes: one end of the cut, or the whole cut. */
enum class CutGrab { START, END, WHOLE }

/**
 * What a finger put down at [x] takes, the cut's ends drawn at [startX] and [endX]: an end within [slop] of it (the nearer
 * one when both are), else the whole cut when it is inside, else the nearer end (a drag from outside pulls that end out).
 */
fun cutGrab(x: Float, startX: Float, endX: Float, slop: Float): CutGrab {
    val toStart = abs(x - startX)
    val toEnd = abs(x - endX)
    if (minOf(toStart, toEnd) >= slop && x in startX..endX) return CutGrab.WHOLE
    return when {
        toStart < toEnd -> CutGrab.START
        toEnd < toStart -> CutGrab.END
        // As near to both (the ends meet, or the finger is halfway): the side it is on.
        x <= (startX + endX) / 2 -> CutGrab.START
        else -> CutGrab.END
    }
}

/** A fade's length in seconds as the chips show it: "0.5", "2", in [locale]'s digits. */
fun fadeSeconds(ms: Long, locale: Locale = Locale.getDefault()): String =
    DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale)).format(ms / 1_000.0)

/** A fade curve's chip. */
@StringRes
fun curveLabel(curve: FadeCurve): Int = when (curve) {
    FadeCurve.LINEAR -> R.string.cut_curve_linear
    FadeCurve.SMOOTH -> R.string.cut_curve_smooth
    FadeCurve.EVEN -> R.string.cut_curve_even
}

/** What the message says when a save failed. */
@StringRes
fun cutFailureMessage(failure: CutFailure): Int = when (failure) {
    CutFailure.SONG_UNREADABLE -> R.string.tones_song_unreadable
    CutFailure.NOWHERE -> R.string.cut_nowhere
    CutFailure.NOT_SAVED -> R.string.cut_not_saved
}

/** A turn of the phone keeps the draft. */
val CutDraftSaver: Saver<CutDraft, Any> = listSaver(
    save = {
        listOf(
            it.songDurationMs,
            it.range.startMs,
            it.range.endMs,
            it.fadeIn.lengthMs,
            it.fadeIn.curve.name,
            it.fadeOut.lengthMs,
            it.fadeOut.curve.name,
            it.quality.name,
            it.focus.name,
        )
    },
    restore = {
        CutDraft(
            songDurationMs = it[0] as Long,
            range = CutRange(it[1] as Long, it[2] as Long),
            fadeIn = Fade(it[3] as Long, FadeCurve.valueOf(it[4] as String)),
            fadeOut = Fade(it[5] as Long, FadeCurve.valueOf(it[6] as String)),
            quality = CutQuality.valueOf(it[7] as String),
            focus = CutEnd.valueOf(it[8] as String),
        )
    },
)

/** Where a cut save is: kept by the process, so turning the phone neither stops it nor loses its result. */
sealed interface CutSaveState {
    data object Idle : CutSaveState

    data class Saving(val progress: Float) : CutSaveState

    data class Finished(val result: CutResult) : CutSaveState
}

/** The one cut save that can run at a time, outside any screen's lifetime; cancelling leaves no file. */
object CutSaving {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val current = MutableStateFlow<CutSaveState>(CutSaveState.Idle)
    private var job: Job? = null

    val state: StateFlow<CutSaveState> = current.asStateFlow()

    fun start(files: CutFiles, request: CutRequest) {
        if (current.value is CutSaveState.Saving) return
        current.value = CutSaveState.Saving(0f)
        job = scope.launch {
            val result = files.save(request) { current.value = CutSaveState.Saving(it) }
            current.value = CutSaveState.Finished(result)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        current.value = CutSaveState.Idle
    }

    /** The screen showed the result. */
    fun consume() {
        if (current.value is CutSaveState.Finished) current.value = CutSaveState.Idle
    }
}

/** The cutter for a library song, with its own player and files; [onSaved] hears the new file. */
@Composable
internal fun CutEditorFor(song: Song, onSaved: (CutResult.Saved) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { CutFiles.of(context.applicationContext) }
    val player = remember(context, scope) { TonePlayer(context, scope) }
    DisposableEffect(player) { onDispose { player.stop() } }
    CutEditor(song, files, player, onSaved, onClose)
}

/**
 * The cutter, full screen over the tabs: the song, its waveform with the cut's two ends (drag an end, or the cut, or tap
 * to bring the nearer end there; TalkBack actions move each end a quarter second), each end's time with its nudges, the
 * preview (the whole cut, or its end), the fades with their curves, the quality, where the cut goes, and Save cut. While
 * the preview plays, a change plays on from the end it changed ([CutDraft.focus]). Leaving stops the preview and cancels
 * a save.
 */
@Composable
private fun CutEditor(song: Song, files: CutFiles, player: TonePlayer, onSaved: (CutResult.Saved) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    var draft by rememberSaveable(song.path, stateSaver = CutDraftSaver) { mutableStateOf(CutDraft(songDurationMs = song.durationMs)) }
    val peaks by produceState<FloatArray?>(null, song.path) { value = loadPeaks(context, song.path, draft.durationMs) }
    val encoders by produceState<Map<CutQuality, String>?>(null) { value = withContext(Dispatchers.IO) { CutQuality.offered(CutEncoders.phone()) } }
    val folder by produceState<CutFolder?>(null, song.path) { value = withContext(Dispatchers.IO) { files.folder(song.path) } }
    val tagBytes by produceState(0L, song.path) { value = withContext(Dispatchers.IO) { embeddedPictureBytes(File(song.path)) ?: 0L } }
    val saving by CutSaving.state.collectAsStateWithLifecycle()
    val playing by player.playing.collectAsStateWithLifecycle()
    val quality = encoders?.let { CutQuality.pick(draft.quality, it.keys) }
    val preview = draft.preview(song.path)
    val previewing = playing?.takeIf { it.key == preview.key }
    var previewState by remember { mutableStateOf(PreviewState()) }
    var dragging by remember { mutableStateOf(false) }
    fun follow(step: PreviewStep) {
        previewState = step.state
        when (val command = step.command) {
            is PreviewCommand.Play -> player.play(command.source) { scope.launch { toasts.say(resources, R.string.tones_not_played) } }
            is PreviewCommand.Jump -> player.jump(command.source)
            PreviewCommand.Stop -> player.stop()
            null -> Unit
        }
    }
    fun play(from: CutEnd) {
        draft = draft.heard(from)
        follow(previewState.onPlay(draft.preview(song.path)))
    }
    val close = {
        if (saving is CutSaveState.Saving) CutSaving.cancel()
        player.stop()
        onClose()
    }
    val writable = folder is CutFolder.NextToSong || folder is CutFolder.InMusic
    val readable = peaks?.isNotEmpty() != false
    val canSave = draft.range.lengthMs > 0 && quality != null && writable && readable

    // While the preview plays, a change plays on from the end it changed: during a drag every 150 ms, and where it is let go.
    LaunchedEffect(preview, dragging) { follow(previewState.onPartChanged(preview, SystemClock.uptimeMillis(), dragging)) }
    // The player stopped by itself: the cut's end, another app's sound, a file it could not play.
    LaunchedEffect(previewing == null) { if (previewing == null) follow(previewState.onEnded()) }
    // Opening the cutter stops another preview; leaving it stops this one.
    DisposableEffect(Unit) {
        player.stop()
        onDispose { player.stop() }
    }
    LaunchedEffect(saving) {
        val shown = saving
        if (shown !is CutSaveState.Finished) return@LaunchedEffect
        CutSaving.consume()
        when (val result = shown.result) {
            is CutResult.Saved -> onSaved(result)
            is CutResult.Failed -> toasts.say(resources, cutFailureMessage(result.reason))
        }
    }

    FullScreen(onDismiss = close) {
        Scaffold(
            topBar = {
                LyrifyTopBar(
                    title = { Text(stringResource(R.string.cut_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigation = { ChromeIconButton(R.drawable.ic_close, stringResource(R.string.tones_close), close) },
                )
            },
            bottomBar = {
                // The message floats above the save dock, so it never covers Save.
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                    ToastSlot(Modifier.padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 8.dp))
                    SaveDock(
                        progress = (saving as? CutSaveState.Saving)?.progress,
                        saveLabel = stringResource(R.string.cut_save),
                        enabled = canSave,
                        onSave = {
                            val picked = quality ?: return@SaveDock
                            player.stop()
                            CutSaving.start(files, request(song, draft, picked, encoders?.get(picked)))
                        },
                        onCancel = CutSaving::cancel,
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            ) {
                EditorSongHeader(song.path, song.title, song.artist?.takeIf { TrackNames.cleanArtist(it) != null } ?: stringResource(R.string.check_no_artist))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    CutWaveform(
                        peaks = peaks,
                        draft = draft,
                        progress = previewing?.progress,
                        onEnd = { end, ms -> draft = if (end == CutEnd.START) draft.withStart(ms) else draft.withEnd(ms) },
                        onMove = { draft = draft.movedTo(it) },
                        onNudge = { end, steps -> draft = draft.nudged(end, steps) },
                        onDrag = { dragging = it },
                    )
                    if (draft.limited) Fact(R.drawable.ic_info, stringResource(R.string.tones_first_20_minutes))
                    if (!readable) Fact(R.drawable.ic_error, stringResource(R.string.tones_song_unreadable))
                    PreviewRow(
                        playing = previewing != null,
                        length = cutTime(draft.range.lengthMs),
                        size = quality?.let { Formatter.formatShortFileSize(context, CutSize.estimateBytes(draft.range.lengthMs, it, tagBytes)) },
                        onPlay = { if (previewing != null) follow(previewState.onStop()) else play(CutEnd.START) },
                        onPlayEnd = { play(CutEnd.END) },
                    )
                }
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    EndRow(CutEnd.START, draft) { draft = draft.nudged(CutEnd.START, it) }
                    EndRow(CutEnd.END, draft) { draft = draft.nudged(CutEnd.END, it) }
                }
                FadeSection(stringResource(R.string.tones_fade_in), draft.fadeIn) { draft = draft.withFadeIn(it) }
                FadeSection(stringResource(R.string.tones_fade_out), draft.fadeOut) { draft = draft.withFadeOut(it) }
                QualitySection(encoders, quality) { draft = draft.copy(quality = it) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    when (val place = folder) {
                        is CutFolder.NextToSong -> Fact(R.drawable.ic_folder_open, stringResource(R.string.cut_next_to_song))
                        is CutFolder.InMusic -> Fact(R.drawable.ic_folder_open, stringResource(R.string.cut_in_folder, place.folder.name))
                        CutFolder.Nowhere -> Fact(R.drawable.ic_error, stringResource(R.string.cut_nowhere))
                        null -> Unit
                    }
                }
            }
        }
    }
}

/** What Save cut hands the saver: the draft at the quality the phone makes, by the encoder that offered it. */
private fun request(song: Song, draft: CutDraft, quality: CutQuality, encoder: String?): CutRequest = CutRequest(
    songPath = song.path,
    songTitle = song.title,
    songArtist = song.artist,
    songAlbum = song.album,
    range = draft.range,
    fadeIn = draft.fadeIn,
    fadeOut = draft.fadeOut,
    quality = quality,
    encoder = encoder,
)

/**
 * The song's waveform with the cut over it: the cut in the highlight, its bars lowered by its fades (so their shape
 * shows), its start a handle with a knob at the top and its end one with a knob at the foot, the preview's place a thin
 * line. A drag takes what [cutGrab] says; a tap brings the nearer end there. Time runs left to right in every language
 * (a media timeline). TalkBack hears "Cut part, From 0:12.25 to 1:24.50" and has actions moving each end a quarter
 * second.
 */
@Composable
private fun CutWaveform(
    peaks: FloatArray?,
    draft: CutDraft,
    progress: Float?,
    onEnd: (CutEnd, Long) -> Unit,
    onMove: (Long) -> Unit,
    onNudge: (CutEnd, Int) -> Unit,
    onDrag: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    val range = draft.range
    val durationMs = draft.durationMs
    val shownRange by rememberUpdatedState(range)
    val name = stringResource(R.string.cut_part_cd)
    val state = stringResource(R.string.cut_part_state, cutTime(range.startMs), cutTime(range.endMs))
    val actions = listOf(
        stringResource(R.string.cut_start_earlier) to (CutEnd.START to -1),
        stringResource(R.string.cut_start_later) to (CutEnd.START to 1),
        stringResource(R.string.cut_end_earlier) to (CutEnd.END to -1),
        stringResource(R.string.cut_end_later) to (CutEnd.END to 1),
    ).map { (label, nudge) ->
        CustomAccessibilityAction(label) {
            onNudge(nudge.first, nudge.second)
            true
        }
    }
    val fade = remember(range, draft.fadeIn, draft.fadeOut) {
        FadeEnvelope.of(range.window, draft.fadeIn.lengthMs, draft.fadeOut.lengthMs, draft.fadeIn.curve, draft.fadeOut.curve)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = name
                stateDescription = state
                customActions = actions
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val ms = (offset.x / size.width * durationMs).toLong()
                    val nearer = if (abs(ms - shownRange.startMs) <= abs(ms - shownRange.endMs)) CutEnd.START else CutEnd.END
                    onEnd(nearer, ms)
                }
            }
            .pointerInput(durationMs) {
                var grab = CutGrab.WHOLE
                var from = 0L
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val total = durationMs.coerceAtLeast(1).toFloat()
                        val startX = shownRange.startMs / total * size.width
                        val endX = shownRange.endMs / total * size.width
                        grab = cutGrab(offset.x, startX, endX, HANDLE_SLOP.toPx())
                        from = if (grab == CutGrab.END) shownRange.endMs else shownRange.startMs
                        dragged = 0f
                        onDrag(true)
                    },
                    onDragEnd = { onDrag(false) },
                    onDragCancel = { onDrag(false) },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                        val ms = from + (dragged / size.width * durationMs).toLong()
                        when (grab) {
                            CutGrab.START -> onEnd(CutEnd.START, ms)
                            CutGrab.END -> onEnd(CutEnd.END, ms)
                            CutGrab.WHOLE -> onMove(ms)
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(colors.surfaceContainerLow)
            val total = durationMs.coerceAtLeast(1).toFloat()
            val startX = range.startMs / total * size.width
            val endX = range.endMs / total * size.width
            drawRect(highlight.copy(alpha = 0.16f), Offset(startX, 0f), Size((endX - startX).coerceAtLeast(1f), size.height))
            val shown = peaks
            if (shown != null && shown.isNotEmpty()) {
                val loudest = (shown.maxOrNull() ?: 0f).coerceAtLeast(MIN_PEAK)
                val middle = size.height / 2
                val bar = 2.dp.toPx()
                shown.forEachIndexed { i, peak ->
                    val x = (i + 0.5f) / shown.size * size.width
                    val inside = x in startX..endX
                    val gain = if (inside) fade.gain(ToneAudio.frames((x / size.width * durationMs).toLong() - range.startMs)) else 1f
                    val half = (peak / loudest * gain).coerceIn(0.02f, 1f) * (middle - 6.dp.toPx())
                    drawLine(if (inside) highlight else colors.onSurfaceVariant.copy(alpha = 0.45f), Offset(x, middle - half), Offset(x, middle + half), strokeWidth = bar)
                }
            }
            val handleWidth = 3.dp.toPx()
            val knob = 7.dp.toPx()
            drawRect(highlight, Offset(startX - handleWidth / 2, 0f), Size(handleWidth, size.height))
            drawRect(highlight, Offset(endX - handleWidth / 2, 0f), Size(handleWidth, size.height))
            drawCircle(highlight, radius = knob, center = Offset(startX, knob))
            drawCircle(highlight, radius = knob, center = Offset(endX, size.height - knob))
            progress?.let {
                val x = startX + it * (endX - startX)
                drawRoundRect(colors.onSurface, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
            }
        }
        if (peaks == null) {
            Text(stringResource(R.string.tones_reading_song), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
    }
}

/** The big preview button, the cut's length and estimated [size] (once the quality is known), and Play the end. */
@Composable
private fun PreviewRow(playing: Boolean, length: String, size: String?, onPlay: () -> Unit, onPlayEnd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PreviewButton(playing, onPlay)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (size != null) stringResource(R.string.cut_length_size, length, size) else length,
                style = MaterialTheme.typography.titleMedium.tabular(),
            )
            LyrifyButton(stringResource(R.string.cut_play_end), onClick = onPlayEnd, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_play_arrow_fill, compact = true)
        }
    }
}

/**
 * One end of the cut: its name and time (one TalkBack stop), and its nudges a quarter second earlier and later, each
 * off where it can go no further.
 */
@Composable
private fun EndRow(end: CutEnd, draft: CutDraft, onNudge: (Int) -> Unit) {
    val start = end == CutEnd.START
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(stringResource(if (start) R.string.cut_start else R.string.cut_end), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(cutTime(draft.range.at(end)), style = MaterialTheme.typography.titleLarge.tabular())
        }
        ChromeIconButton(
            R.drawable.ic_remove,
            stringResource(if (start) R.string.cut_start_earlier else R.string.cut_end_earlier),
            { onNudge(-1) },
            enabled = draft.nudged(end, -1).range != draft.range,
            glass = false,
        )
        ChromeIconButton(
            R.drawable.ic_add,
            stringResource(if (start) R.string.cut_start_later else R.string.cut_end_later),
            { onNudge(1) },
            enabled = draft.nudged(end, 1).range != draft.range,
            glass = false,
        )
    }
}

/** A fade: its length (Off to 10 s) and, while it is on, its curve. Chips wrap, so a large font never cuts them. */
@Composable
private fun FadeSection(title: String, fade: Fade, onFade: (Fade) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
        SectionTitle(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
            Fade.LENGTHS_MS.forEach { ms ->
                LyrifyChip(selected = fade.lengthMs == ms, onClick = { onFade(fade.copy(lengthMs = ms)) }, role = Role.RadioButton) {
                    Text(if (ms == 0L) stringResource(R.string.cut_fade_off) else stringResource(R.string.cut_seconds, fadeSeconds(ms)))
                }
            }
        }
        if (fade.lengthMs > 0) {
            Text(stringResource(R.string.cut_curve), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                FadeCurve.entries.forEach { curve ->
                    LyrifyChip(selected = fade.curve == curve, onClick = { onFade(fade.copy(curve = curve)) }, role = Role.RadioButton) {
                        Text(stringResource(curveLabel(curve)))
                    }
                }
            }
        }
    }
}

/** 128, 256 or 320 kbps, those the phone makes ([offered]; nothing while it is asked), or why none can be saved. */
@Composable
private fun QualitySection(offered: Map<CutQuality, String>?, quality: CutQuality?, onQuality: (CutQuality) -> Unit) {
    if (offered == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
        SectionTitle(stringResource(R.string.cut_quality))
        if (offered.isEmpty()) {
            Fact(R.drawable.ic_error, stringResource(R.string.cut_no_encoder))
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
            CutQuality.entries.filter { it in offered }.forEach { option ->
                LyrifyChip(selected = option == quality, onClick = { onQuality(option) }, role = Role.RadioButton) {
                    Text(stringResource(R.string.cut_kbps, option.kbps))
                }
            }
        }
        Fact(R.drawable.ic_info, stringResource(R.string.cut_format))
    }
}
