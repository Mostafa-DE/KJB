package app.lyrify.ui

import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.player.AbPart
import app.lyrify.player.PlayerExtras
import app.lyrify.player.PlayerUi
import app.lyrify.player.SLEEP_FADE_MS
import app.lyrify.player.SleepTimer
import app.lyrify.player.bookmarkTicks
import app.lyrify.player.sleepLeftMs
import app.lyrify.player.withEnd
import app.lyrify.player.withStart
import kotlin.math.roundToInt

// The player's extras on the player itself (milestone P2): the sleep button and its chip in the secondary row, the
// chips row (speed, A-B), the A and B handles above the progress bar, and the bar's bookmark ticks and part. Their
// sheets and actions are in PlayerSheets.kt.

// The sleep chip

/** What the sleep chip says. */
sealed interface SleepShown {
    /** "23 min", rounded up. */
    data class Minutes(val minutes: Int) : SleepShown

    /** "0:42": under a minute; [fading] while the music fades. */
    data class Clock(val ms: Long, val fading: Boolean = false) : SleepShown

    data class Songs(val songs: Int) : SleepShown

    data object EndOfSong : SleepShown
}

/** The chip for [timer] with [leftMs] to go ([sleepLeftMs]). */
fun sleepShown(timer: SleepTimer, leftMs: Long?, playing: Boolean): SleepShown = when {
    playing && leftMs != null && leftMs <= SLEEP_FADE_MS -> SleepShown.Clock(leftMs, fading = true)
    timer is SleepTimer.BySongs -> if (timer.left > 1) SleepShown.Songs(timer.left) else SleepShown.EndOfSong
    leftMs != null && leftMs >= MINUTE_MS -> SleepShown.Minutes(((leftMs + MINUTE_MS - 1) / MINUTE_MS).toInt())
    else -> SleepShown.Clock(leftMs ?: 0)
}

private const val MINUTE_MS: Long = 60_000

/** The secondary row's sleep button: the snooze icon while off; a chip with what is left while a timer runs. */
@Composable
internal fun SleepButton(ui: PlayerUi, onOpen: () -> Unit) {
    val context = LocalContext.current
    val extras = remember(context) { PlayerExtras.of(context) }
    val timer = extras.sleep.collectAsStateWithLifecycle().value
    val reduced = LocalReducedMotion.current
    // The icon and the chip fade through each other, the room between them following, never swapped at once.
    val kept = remember { Kept(timer) }
    if (timer != null) kept.value = timer
    AnimatedContent(timer != null, transitionSpec = { fadeThrough(reduced) using SizeTransform(clip = false) }, contentAlignment = Alignment.Center, label = "sleep") { on ->
        val running = kept.value
        if (on && running != null) SleepChip(ui, running, onOpen) else SleepIcon(onOpen)
    }
}

@Composable
private fun SleepIcon(onOpen: () -> Unit) {
    val label = stringResource(R.string.player_sleep_off)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(TOUCH).clip(CircleShape).clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = label },
    ) { Icon(painterResource(R.drawable.ic_snooze), contentDescription = null, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun SleepChip(ui: PlayerUi, timer: SleepTimer, onOpen: () -> Unit) {
    val now = rememberClock(true, everyMs = 500)
    val shown = sleepShown(timer, sleepLeftMs(timer, now, ui.positionAt(now), ui.durationMs, ui.speed), ui.playing)
    val text = when (shown) {
        is SleepShown.Minutes -> stringResource(R.string.sleep_minutes_chip, shown.minutes)
        is SleepShown.Clock -> clock(shown.ms)
        is SleepShown.Songs -> pluralStringResource(R.plurals.sleep_songs_chip, shown.songs, shown.songs)
        SleepShown.EndOfSong -> stringResource(R.string.sleep_end_of_song)
    }
    val spoken = when (shown) {
        is SleepShown.Minutes -> pluralStringResource(R.plurals.sleep_chip_minutes, shown.minutes, shown.minutes)
        is SleepShown.Clock -> if (timer is SleepTimer.BySongs) stringResource(R.string.sleep_chip_end) else pluralStringResource(R.plurals.sleep_chip_minutes, 1, 1)
        is SleepShown.Songs -> pluralStringResource(R.plurals.sleep_chip_songs, shown.songs, shown.songs)
        SleepShown.EndOfSong -> stringResource(R.string.sleep_chip_end)
    }
    val label = if (shown is SleepShown.Clock && shown.fading) stringResource(R.string.sleep_chip_fading, spoken) else spoken
    val brand = LocalBrandColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(brand.highlightContainer, CircleShape)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = label }
            .heightIn(min = 40.dp)
            .padding(start = 12.dp, end = 16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_snooze), contentDescription = null, tint = brand.onHighlightContainer, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.tabular(), color = brand.onHighlightContainer, maxLines = 1)
    }
}

// The chips row

/** The chips row while something is on: the speed (not 1×, or a moved pitch) and the part repeating. */
@Composable
internal fun ExtrasChips(ui: PlayerUi, extras: PlayerExtrasUi) {
    val state = extras.extras.state.collectAsStateWithLifecycle().value
    val path = ui.path()
    val speed = state.speedOf(path)
    val part = partShown(state.part, path)
    val reduced = LocalReducedMotion.current
    // The row opens its room and each chip pops in and out on its own, so the title and the bar never jump.
    AnimatedVisibility(speed.shows || part, enter = expandIn(reduced), exit = shrinkOut(reduced)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            AnimatedVisibility(speed.shows, enter = chipIn(reduced), exit = chipOut(reduced)) {
                val number = speedNumber(speed.speed)
                val text = chipPitch(speed)?.let { stringResource(R.string.speed_value_pitch, number, it) } ?: stringResource(R.string.speed_value, number)
                LyrifyChip(selected = true, onClick = { extras.open = ExtrasSheet.SPEED }, role = Role.Button, icon = R.drawable.ic_speed) { Text(text, style = MaterialTheme.typography.labelLarge.tabular()) }
            }
            AnimatedVisibility(part, enter = chipIn(reduced), exit = chipOut(reduced)) {
                LyrifyChip(selected = true, onClick = extras::stopPart, role = Role.Button, icon = R.drawable.ic_repeat) {
                    Text(stringResource(R.string.part_chip))
                    Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// Repeat a part (A-B)

/** How tall the handles' row is, above the progress bar's touch area; the stems reach down into the bar. */
private val HANDLES = 44.dp

/**
 * The A and B handles above the progress bar while the song's part repeats: drag one (or, with TalkBack, set it, or use
 * Earlier and Later). The part is stored when a handle is let go.
 */
@Composable
internal fun PartHandles(ui: PlayerUi, extras: PlayerExtrasUi) {
    val state = extras.extras.state.collectAsStateWithLifecycle().value
    val part = state.part?.takeIf { partShown(it, ui.path()) } ?: return
    val duration = ui.durationMs.takeIf { it > 0 } ?: return
    // While a handle is dragged the part moves here; it is stored when the handle is let go.
    var dragging by remember { mutableStateOf<AbPart?>(null) }
    val shown = dragging?.takeIf { it.path == part.path } ?: part
    val commit: (AbPart) -> Unit = { moved -> extras.extras.change { it.copy(part = moved) } }
    val done = {
        dragging?.let(commit)
        dragging = null
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(HANDLES)) {
        val width = constraints.maxWidth.toFloat()
        PartHandle(true, shown.startMs, duration, width, onDrag = { ms -> dragging = (dragging ?: part).withStart(ms) }, onDone = done, onSet = { ms -> commit(part.withStart(ms)) })
        PartHandle(false, shown.endMs, duration, width, onDrag = { ms -> dragging = (dragging ?: part).withEnd(ms, duration) }, onDone = done, onSet = { ms -> commit(part.withEnd(ms, duration)) })
    }
}

@Composable
private fun PartHandle(start: Boolean, ms: Long, durationMs: Long, width: Float, onDrag: (Long) -> Unit, onDone: () -> Unit, onSet: (Long) -> Unit) {
    val density = LocalDensity.current
    val brand = LocalBrandColors.current
    val x = width * (ms.toFloat() / durationMs).coerceIn(0f, 1f)
    val half = with(density) { (TOUCH / 2).toPx() }
    val label = stringResource(if (start) R.string.part_start else R.string.part_end)
    val earlier = stringResource(R.string.part_earlier)
    val later = stringResource(R.string.part_later)
    val reach = with(density) { (HANDLES + TOUCH / 2).toPx() }
    var dragX by remember { mutableFloatStateOf(x) }
    // The drag outlives recompositions: it reads where the handle is and what to call now.
    val at by rememberUpdatedState(x)
    val drag by rememberUpdatedState(onDrag)
    val finish by rememberUpdatedState(onDone)
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .offset { IntOffset((x - half).roundToInt(), 0) }
            .size(TOUCH, HANDLES)
            .drawBehind {
                // The stem, down to the middle of the progress bar.
                drawLine(brand.highlight, Offset(size.width / 2, 22.dp.toPx()), Offset(size.width / 2, reach), strokeWidth = 2.dp.toPx())
            }
            .semantics {
                contentDescription = label
                stateDescription = clock(ms)
                progressBarRangeInfo = ProgressBarRangeInfo(ms.toFloat() / durationMs, 0f..1f)
                setProgress { value ->
                    onSet((value.coerceIn(0f, 1f) * durationMs).toLong())
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(earlier) { onSet(ms - 1_000); true },
                    CustomAccessibilityAction(later) { onSet(ms + 1_000); true },
                )
            }
            .pointerInput(width, durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragX = at },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                ) { change, amount ->
                    change.consume()
                    dragX = (dragX + amount).coerceIn(0f, width)
                    drag((dragX / width * durationMs).toLong())
                }
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(x = if (start) (-10).dp else 10.dp)
                .size(28.dp, 26.dp)
                .background(brand.highlight, RoundedCornerShape(9.dp)),
        ) { Text(if (start) "A" else "B", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = brand.onHighlight) }
    }
}

/** On the progress bar: the bookmarks' ticks and the part repeating. Drawn under the thumb; touches go to the bar. */
@Composable
internal fun BarMarks(ui: PlayerUi) {
    val context = LocalContext.current
    val extras = remember(context) { PlayerExtras.of(context) }
    val state = extras.state.collectAsStateWithLifecycle().value
    val path = ui.path()
    val duration = ui.durationMs
    val ticks = bookmarkTicks(state.bookmarksOf(path), duration)
    val part = state.part?.takeIf { partShown(it, path) && duration > 0 }
    if (ticks.isEmpty() && part == null) return
    val highlight = LocalBrandColors.current.highlight
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(TOUCH)) {
        val mid = size.height / 2
        part?.let {
            val a = size.width * it.startMs / duration
            val b = size.width * it.endMs / duration
            val zone = 12.dp.toPx()
            drawRoundRect(highlight.copy(alpha = 0.3f), Offset(a, mid - zone / 2), Size(b - a, zone), CornerRadius(5.dp.toPx()))
            drawRoundRect(highlight, Offset(a, mid - zone / 2), Size(b - a, zone), CornerRadius(5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        val tick = Size(3.dp.toPx(), 14.dp.toPx())
        ticks.forEach { share -> drawRoundRect(ink.copy(alpha = 0.62f), Offset(size.width * share - tick.width / 2, mid - tick.height / 2), tick, CornerRadius(2.dp.toPx())) }
    }
}

/** Whether the song playing has its part repeating: the menu says "Stop the part" then. */
@Composable
internal fun rememberPartOn(ui: PlayerUi): Boolean {
    val context = LocalContext.current
    val extras = remember(context) { PlayerExtras.of(context) }
    return partShown(extras.state.collectAsStateWithLifecycle().value.part, ui.path())
}
