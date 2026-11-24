package app.lyrify.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lyrify.R
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.PagePeriod
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Insights' cards (the approved mockup, handoff/insights/preview-insights-v1.html, user 2026-11-23): the stories row,
// your time, every day you listened, your day on a record, the top artists, songs and albums, your taste, how you listen
// and moments. Each draws only what its card in the page holds (stats/InsightsPage.kt, lane in2d); the cards about you
// are in InsightsYou.kt, the story and its pictures in InsightsStory.kt, the page itself in ListeningScreen.kt.

// The motion of the approved mockup: a card arrives as it scrolls into view, its numbers count up and its charts grow; a
// new period swaps the cards in and starts them again. All of it is in place at once with Remove animations.

/**
 * A card's arrival: whether it has arrived on screen ([shown]) and the [period] it shows. What moves in it starts as it
 * arrives, and again for a new period; a card that had arrived before it was drawn (scrolled away and back) is in place.
 */
@Immutable
internal data class Arrival(val shown: Boolean, val period: Any?)

internal val LocalArrival = compositionLocalOf { Arrival(shown = true, period = null) }

/**
 * Milliseconds since what is drawn arrived: its card ([Arriving]) or its story slide. Read it where it is drawn (a
 * Canvas, a graphicsLayer), so only the drawing follows it. Past every arrival when nothing moves.
 */
internal fun interface ArrivalClock {
    fun ms(): Float
}

internal val LocalArrivalClock = compositionLocalOf { ArrivalClock { ARRIVED_MS } }

/**
 * How far one thing has come at [ms] on its arrival clock: nothing before [delay], all of it [duration] later, along
 * [easing] between (the mockup's animation-delay and animation-duration).
 */
fun phase(ms: Float, delay: Int, duration: Int, easing: Easing = LinearEasing): Float =
    easing.transform(((ms - delay) / duration).coerceIn(0f, 1f))

/**
 * How far apart [count] things arrive one after another: [step] ms, closer when there are so many that the last one's
 * [duration] would end after [within] ms (its clock's end), so every one lands whole (a long all time's months).
 */
fun staggerMs(count: Int, step: Int, duration: Int, within: Float): Float = minOf(step.toFloat(), (within - duration) / (count - 1).coerceAtLeast(1))

/** How much of an item of [size] px at [offset] shows in a viewport from [start] to [end]: 0 to 1. */
fun shownShare(offset: Int, size: Int, start: Int, end: Int): Float =
    if (size <= 0) 0f else ((minOf(offset + size, end) - maxOf(offset, start)).coerceAtLeast(0) / size.toFloat())

/**
 * A card of [list] (its item [key]) arriving as the mockup's do: faded, 26 dp lower and at 98% until 12% of it shows,
 * then rising into place (its opacity over 520 ms, the move over 640 ms ease-rise). [arrived] keeps the cards that came,
 * so none comes twice. Each new [period] swaps an arrived card in from a quarter faded and 8 dp lower (420 ms ease-rise)
 * and starts what moves in it again ([LocalArrival], [LocalArrivalClock]). With Remove animations, even turned on while
 * the page shows, every card is in place at once.
 */
@Composable
internal fun Arriving(key: String, list: LazyListState, arrived: MutableSet<String>, period: Any, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced || key in arrived) }
    val rise = remember { Animatable(if (shown) 1f else 0f) }
    val swap = remember { Animatable(1f) }
    val clock = remember { Animatable(if (shown) ARRIVED_MS else 0f) }
    LaunchedEffect(key, reduced) {
        if (reduced) {
            arrived += key
            shown = true
            rise.snapTo(1f)
            swap.snapTo(1f)
            clock.snapTo(ARRIVED_MS)
            return@LaunchedEffect
        }
        if (shown) return@LaunchedEffect
        snapshotFlow {
            val info = list.layoutInfo
            info.visibleItemsInfo.firstOrNull { it.key == key }?.let { shownShare(it.offset, it.size, info.viewportStartOffset, info.viewportEndOffset) >= ARRIVE_SHARE } == true
        }.first { it }
        arrived += key
        shown = true
        launch { clock.animateTo(ARRIVED_MS, tween(ARRIVED_MS.toInt(), easing = LinearEasing)) }
        rise.animateTo(1f, tween(RISE_MS, easing = LinearEasing))
    }
    // The period the card last showed: a change swaps it in, back to the first one too.
    var last by remember { mutableStateOf(period) }
    LaunchedEffect(period) {
        if (period == last) return@LaunchedEffect
        last = period
        if (!shown || reduced) return@LaunchedEffect
        launch {
            clock.snapTo(0f)
            clock.animateTo(ARRIVED_MS, tween(ARRIVED_MS.toInt(), easing = LinearEasing))
        }
        swap.snapTo(0f)
        swap.animateTo(1f, tween(SWAP_MS, easing = Motion.Rise))
    }
    val arrival = remember(shown, period) { Arrival(shown, period) }
    Box(
        modifier.graphicsLayer {
            val t = rise.value * RISE_MS
            val fade = phase(t, 0, FADE_MS, Motion.Ease)
            val move = phase(t, 0, RISE_MS, Motion.Rise)
            val swapped = swap.value
            alpha = fade * (SWAP_FROM + (1f - SWAP_FROM) * swapped)
            translationY = (1f - move) * RISE_DP.toPx() + (1f - swapped) * SWAP_DP.toPx()
            val scale = 1f - (1f - move) * (1f - RISE_SCALE)
            scaleX = scale
            scaleY = scale
        },
    ) {
        CompositionLocalProvider(LocalArrival provides arrival, LocalArrivalClock provides ArrivalClock { clock.value }, content = content)
    }
}

/**
 * [value], as [text] says it, counting up over 900 ms (ease-out cubic) as its card arrives, from 0, and from the last
 * value when it changes (a new period); final at once with Remove animations and on a card already in place. Until the
 * count lands it shows the count (never the final number first); then [value] exactly. Shrinks to fit its line with
 * large text.
 */
@Composable
internal fun Counted(value: Long, style: TextStyle, color: Color, modifier: Modifier = Modifier, text: @Composable (Long) -> AnnotatedString) {
    val arrival = LocalArrival.current
    val reduced = LocalReducedMotion.current
    val count = remember { Animatable(if (reduced || arrival.shown) value.toFloat() else 0f) }
    LaunchedEffect(value, arrival.shown, reduced) {
        when {
            reduced -> count.snapTo(value.toFloat())
            arrival.shown -> count.animateTo(value.toFloat(), tween(COUNT_MS, easing = Motion.Glide))
        }
    }
    val shown = if (reduced || (!count.isRunning && count.value == value.toFloat())) value else count.value.roundToLong()
    Text(text(shown), style = style.tabular(), color = color, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = SMALLEST_NUMBER, maxFontSize = style.fontSize), modifier = modifier)
}

/** [count] as a plain counted number ([Counted]'s usual words). */
@Composable
internal fun countText(count: Long): AnnotatedString = AnnotatedString(formatCount(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))

/** Real minutes in big type, the units small: "41h 22min" ([unit]: the units' size). */
@Composable
internal fun bigTime(ms: Long, unit: SpanStyle): AnnotatedString {
    val time = listenedTime(ms)
    val h = stringResource(R.string.insights_unit_h)
    val min = stringResource(R.string.insights_unit_min)
    return buildAnnotatedString {
        if (time.hours > 0) {
            append(formatCount(time.hours))
            withStyle(unit) { append(h) }
        }
        append(formatCount(time.minutes))
        withStyle(unit) { append(min) }
    }
}

/** Real minutes as [listenedText] says them, but a moment under half a minute (which it rounds to "0 min") reads "<1 min". */
@Composable
internal fun heardText(ms: Long): String = if (ms in 1 until ROUNDS_TO_NOTHING_MS) stringResource(R.string.insights_under_a_minute) else listenedText(ms)

/** [part] of [whole] in whole percent; 0 of nothing. */
fun percentOf(part: Long, whole: Long): Int = if (whole <= 0) 0 else (part * 100.0 / whole).roundToInt()

/** How much [now] differs from [before], in whole percent of it. */
internal fun percentChange(now: Long, before: Long): Long = if (before <= 0) 0 else ((now - before) * 100.0 / before).roundToInt().toLong()

// The cards' frame

/** One of Insights' cards: KJB's card 16 dp from the screen's edges, its [content] 18 dp in. */
@Composable
internal fun InsightsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LyrifyCard(modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) { Column(Modifier.padding(18.dp), content = content) }
}

/**
 * A card's head: its coral [icon] on a small tile (none for the top lists), its [title] (a heading for TalkBack) with a
 * [line] under it, and an [action] at its end.
 */
@Composable
internal fun CardHead(title: String, @DrawableRes icon: Int? = null, line: String? = null, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        if (icon != null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(colors.surface, RoundedCornerShape(12.dp))) {
                Icon(painterResource(icon), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = CardTitle, color = colors.onSurface)
            if (line != null) Text(line, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = colors.onSurfaceVariant)
        }
        action?.invoke()
    }
}

/** "View all" at a top list's end, when it has more than it shows. */
@Composable
internal fun ViewAll(onClick: () -> Unit) {
    LyrifyButton(stringResource(R.string.stats_view_all), onClick = onClick, kind = ButtonKind.GHOST, compact = true)
}

/**
 * A row's place against the period before, as a small pill: up (green), down (grey), new (coral), or nothing when it
 * kept its place or the period before is not set against ([compared]). TalkBack reads it with the row.
 */
@Composable
internal fun PlaceChange(place: Int, before: Int?, compared: Boolean) {
    if (!compared || before == place) return
    val status = LocalStatusColors.current
    val brand = LocalBrandColors.current
    val (ground, ink) = when {
        before == null -> brand.highlightContainer to brand.onHighlightContainer
        before > place -> status[Tone.SYNCED].container to status[Tone.SYNCED].onContainer
        else -> status[Tone.NONE].container to status[Tone.NONE].onContainer
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 22.dp).background(ground, RoundedCornerShape(11.dp)).padding(start = if (before == null) 7.dp else 4.dp, end = 7.dp)) {
        if (before == null) {
            Text(stringResource(R.string.insights_new).uppercase(), style = PillWord, color = ink)
        } else {
            Icon(painterResource(if (before > place) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward), contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
            Text(formatCount(abs(before - place)), style = PillNumber, color = ink)
        }
    }
}

/** What TalkBack says of a row's place against the period before: ", up 2 places", ", new", or nothing. */
@Composable
internal fun placeWords(place: Int, before: Int?, compared: Boolean): String = when {
    !compared || before == place -> ""
    before == null -> stringResource(R.string.insights_place_new)
    before > place -> pluralStringResource(R.plurals.insights_place_up, before - place, before - place)
    else -> pluralStringResource(R.plurals.insights_place_down, place - before, place - before)
}

/**
 * A bar that is a share of the whole ([shares], percent, each in its [colors]), its parts growing in one after another from
 * the left (800 ms ease-rise, 90 ms apart). Decorative: its legend says the numbers.
 */
@Composable
internal fun StackBar(shares: List<Int>, colors: List<Color>, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val clock = LocalArrivalClock.current
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).clearAndSetSemantics {}) {
        val gap = 3.dp.toPx()
        val shown = shares.withIndex().filter { it.value > 0 }
        val width = size.width - gap * (shown.size - 1).coerceAtLeast(0)
        val total = shown.sumOf { it.value }.coerceAtLeast(1)
        var x = 0f
        shown.forEachIndexed { at, (i, share) ->
            val part = width * share / total
            val grown = phase(clock.ms(), at * STACK_STEP_MS, STACK_MS, Motion.Rise)
            drawRect(colors[i % colors.size], Offset(x, 0f), Size(part * grown, size.height))
            x += part + gap
        }
    }
}

/** A legend's line: its colour, its name and its share in bold. */
@Composable
internal fun LegendItem(color: Color, name: String, value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.heightIn(min = 30.dp)) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(4.dp)))
        Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
        Text(value, style = LegendValue.tabular(), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A small figure on the card's ground: [value] (counted up) over its [label]. */
@Composable
internal fun SmallFigure(value: Long, label: String, modifier: Modifier = Modifier, text: @Composable (Long) -> AnnotatedString = { countText(it) }) {
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(10.dp).semantics(mergeDescendants = true) {}) {
        Counted(value, FigureValue, MaterialTheme.colorScheme.onSurface, text = text)
        Text(label, style = FigureLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Words for periods and days

/** "This week", "This month", "This year", "All time"; a month's recap: "October". */
@Composable
internal fun periodName(period: PagePeriod): String = when {
    period.kind == ListeningPeriod.MONTH && !period.going -> monthText(java.time.YearMonth.from(period.firstDay))
    else -> stringResource(period.kind.named)
}

/** The period before [period], in words: "last week", "October", "2025"; empty for all time. */
@Composable
internal fun beforeName(period: PagePeriod): String = when (period.kind) {
    ListeningPeriod.WEEK -> stringResource(R.string.insights_last_week)
    ListeningPeriod.MONTH -> monthText(java.time.YearMonth.from(period.firstDay).minusMonths(1))
    ListeningPeriod.YEAR -> (period.firstDay.year - 1).toString()
    ListeningPeriod.ALL_TIME -> ""
}

/** The story's name: "Your week so far", "Your November so far", "Your 2026 so far", "Your KJB so far", or a recap's "Your October". */
@Composable
internal fun storyName(period: PagePeriod): String = when {
    period.kind == ListeningPeriod.MONTH && !period.going -> stringResource(R.string.insights_recap_title, monthText(java.time.YearMonth.from(period.firstDay)))
    period.kind == ListeningPeriod.WEEK -> stringResource(R.string.insights_story_week)
    period.kind == ListeningPeriod.MONTH -> stringResource(R.string.insights_story_so_far, monthText(java.time.YearMonth.from(period.firstDay)))
    period.kind == ListeningPeriod.YEAR -> stringResource(R.string.insights_story_so_far, period.firstDay.year.toString())
    else -> stringResource(R.string.insights_story_all)
}

/** A day as the cards say it: "23 Nov", with its weekday "Mon 23 Nov", with its [year] "23 Nov 2026". */
@Composable
internal fun dayText(day: LocalDate, weekday: Boolean = false, year: Boolean = false): String {
    val millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    flags = flags or if (year) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR
    if (weekday) flags = flags or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    return DateUtils.formatDateTime(LocalContext.current, millis, flags)
}

// The cards' type (the mockup's, on KJB's two families)

private val CardTitle = display(20, 26, -0.01, weight = 650)

internal val CardKicker = body(12f, 16, 700).copy(letterSpacing = 0.08.em)

internal val AxisLabel = body(11.5f, 16, 500)

internal val FigureValue = display(22, 26, -0.02, weight = 700)

internal val FigureLabel = body(12f, 16, 500)

private val PillWord = body(10.5f, 16, 650).copy(letterSpacing = 0.04.em)

internal val PillNumber = body(12f, 16, 650)

private val LegendValue = display(14, 18, weight = 700)

/** Under this, [listenedText] rounds a time to 0 min. */
private const val ROUNDS_TO_NOTHING_MS = 30_000L

/** The smallest a counted number shrinks to with large text. */
private val SMALLEST_NUMBER = 12.sp

// The motion's numbers (the mockup's CSS)

/** A card's arrival clock is past every arrival by then (the slowest, the rings', is 1.3 s). */
internal const val ARRIVED_MS = 2_000f

/** A card arrives once this share of it shows (12%). */
private const val ARRIVE_SHARE = 0.12f

private const val FADE_MS = 520

private const val RISE_MS = 640

private val RISE_DP = 26.dp

private const val RISE_SCALE = 0.98f

private const val SWAP_MS = 420

private const val SWAP_FROM = 0.25f

private val SWAP_DP = 8.dp

private const val COUNT_MS = 900

internal const val BAR_MS = 760

internal const val BAR_STEP_MS = 16

private const val STACK_MS = 800

private const val STACK_STEP_MS = 90
