package app.lyrify.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.Settings
import app.lyrify.stats.ClockCard
import app.lyrify.stats.DaysCard
import app.lyrify.stats.Ends
import app.lyrify.stats.HowCard
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.Loyalty
import app.lyrify.stats.Moment
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.Ranked
import app.lyrify.stats.Step
import app.lyrify.stats.Steps
import app.lyrify.stats.TasteCard
import app.lyrify.stats.TimeCard
import app.lyrify.stats.TopCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

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

/** How much of an item of [size] px at [offset] shows in a viewport from [start] to [end]: 0 to 1. */
fun shownShare(offset: Int, size: Int, start: Int, end: Int): Float =
    if (size <= 0) 0f else ((minOf(offset + size, end) - maxOf(offset, start)).coerceAtLeast(0) / size.toFloat())

/**
 * A card of [list] (its item [key]) arriving as the mockup's do: faded, 26 dp lower and at 98% until a tenth of it shows,
 * then rising into place (its opacity over 520 ms, the move over 640 ms ease-rise). [arrived] keeps the cards that came,
 * so none comes twice. A new [period] swaps an arrived card in from a quarter faded and 8 dp lower (420 ms ease-rise)
 * and starts what moves in it again ([LocalArrival], [LocalArrivalClock]).
 */
@Composable
internal fun Arriving(key: String, list: LazyListState, arrived: MutableSet<String>, period: Any, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced || key in arrived) }
    val rise = remember { Animatable(if (shown) 1f else 0f) }
    val swap = remember { Animatable(1f) }
    val clock = remember { Animatable(if (shown) ARRIVED_MS else 0f) }
    LaunchedEffect(key) {
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
    val first = remember { period }
    LaunchedEffect(period) {
        if (period == first || !shown || reduced) return@LaunchedEffect
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
 * value when it changes (a new period); final at once with Remove animations and on a card already in place. Shrinks to
 * fit its line with large text.
 */
@Composable
internal fun Counted(value: Long, style: TextStyle, color: Color, modifier: Modifier = Modifier, text: @Composable (Long) -> AnnotatedString) {
    val arrival = LocalArrival.current
    val reduced = LocalReducedMotion.current
    val count = remember { Animatable(if (reduced || arrival.shown) value.toFloat() else 0f) }
    LaunchedEffect(value, arrival.shown) {
        when {
            reduced -> count.snapTo(value.toFloat())
            arrival.shown -> count.animateTo(value.toFloat(), tween(COUNT_MS, easing = Motion.Glide))
        }
    }
    val shown = when {
        count.isRunning -> count.value.roundToLong()
        reduced || arrival.shown -> value
        else -> 0L
    }
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
private fun ViewAll(onClick: () -> Unit) {
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

// The stories row

/**
 * Your recaps as stories (the mockup's rings): this month so far, the latest month's recap (its ring turning while it is
 * new), the year so far, then the months before, each a ring of its covers that opens its story.
 */
@Composable
internal fun StoriesRow(page: app.lyrify.stats.InsightsPage, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val settings = rememberSettings()
    val change = rememberSettingsChange()
    val recaps = page.cards.recaps
    val latest = recaps.firstOrNull()?.month
    val due = latest != null && settings.nav.recapSeen?.let { it >= latest } != true
    val today = rememberToday()
    LazyRow(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "" },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (page.cards.time != null) {
            item(key = "month so far") {
                StoryRing(stringResource(R.string.insights_ring_so_far, monthText(java.time.YearMonth.from(today), short = true)), new = false, covers = recaps.firstOrNull()?.covers.orEmpty().takeIf { false }.orEmpty(), year = null) {
                    nav.show(app.lyrify.ui.FullScreen.Story(period = ListeningPeriod.MONTH))
                }
            }
        }
        recaps.forEachIndexed { i, recap ->
            item(key = "recap ${recap.month}") {
                StoryRing(monthText(recap.month), new = i == 0 && due, covers = recap.covers, year = null) {
                    if (i == 0) change { it.copy(nav = it.nav.copy(recapSeen = recap.month)) }
                    nav.show(FullScreen.Story(month = recap.month.toString()))
                }
            }
            if (i == 0 && page.cards.time != null) {
                item(key = "year so far") {
                    StoryRing(stringResource(R.string.insights_ring_so_far, today.year.toString()), new = false, covers = emptyList(), year = today.year) {
                        nav.show(FullScreen.Story(period = ListeningPeriod.YEAR))
                    }
                }
            }
        }
    }
}

/**
 * One story's ring: its covers in a circle (or the year in big type), a turning coral, gold and violet ring with a coral
 * dot while it is [new], else a quiet one; its [label] under it. A button for TalkBack ("October, new: play the story").
 */
@Composable
private fun StoryRing(label: String, new: Boolean, covers: List<Song>, year: Int?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val turn = if (new && huddleMoves()) rememberInfiniteTransition(label = "story ring").animateFloat(0f, 360f, infiniteRepeatable(tween(RING_TURN_MS, easing = LinearEasing)), label = "turn") else null
    val description = stringResource(if (new) R.string.insights_ring_new else R.string.insights_ring, label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(68.dp).drawBehind {
                if (new) {
                    rotate(turn?.value ?: 0f) { drawCircle(Brush.sweepGradient(listOf(brand.highlight, HuddleGold, FriendColors[2], brand.highlight))) }
                } else {
                    drawCircle(colors.outlineVariant)
                }
            },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(3.dp).border(3.dp, colors.background, CircleShape).padding(3.dp).clip(CircleShape).fillMaxWidth().aspectRatio(1f)
                    .background(if (year != null) HuddleEmptyGround else colors.surfaceVariant)
                    .drawBehind {
                        if (year != null) {
                            drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[0], Color.Transparent), Offset(size.width * 0.2f, size.height * 0.15f), size.width * 0.6f))
                            drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[2], Color.Transparent), Offset(size.width * 0.9f, size.height * 0.8f), size.width * 0.6f))
                        }
                    },
            ) {
                if (year != null) {
                    Text(year.toString(), style = display(17, 17, -0.02, weight = 700), color = BrandLilac, maxLines = 1)
                } else {
                    Column {
                        covers.take(4).chunked(2).forEach { pair ->
                            Row(Modifier.weight(1f)) { pair.forEach { song -> SongCover(SongArt(song.path, song.ref.mediaStoreId), Modifier.weight(1f).fillMaxHeight(), size = 32.dp, shape = androidx.compose.ui.graphics.RectangleShape) } }
                        }
                    }
                }
            }
            if (new) Box(Modifier.align(Alignment.TopEnd).offset((-1).dp, 3.dp).size(14.dp).border(3.dp, colors.background, CircleShape).padding(1.dp).background(brand.highlight, CircleShape))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (new) FontWeight.SemiBold else FontWeight.Medium),
            color = if (new) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Your time

/**
 * Your time, the hero (the mockup's): on the dark violet ground with drifting coral, violet and teal glows, the period's
 * time in big type counting up, how it compares with the period before (else about how much a day), a bar for each day
 * or month growing in (the period before as a dashed line, the step of today glowing), the plays, songs, artists and
 * albums with how many more or fewer, then Play your story and Share.
 */
@Composable
internal fun YourTime(time: TimeCard, period: PagePeriod, onShare: () -> Unit) {
    val nav = LocalNavigator.current
    val moves = huddleMoves()
    val drift = if (moves) rememberInfiniteTransition(label = "glows") else null
    val g1 = drift?.animateFloat(0f, 1f, infiniteRepeatable(tween(GLOW_1_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g1")
    val g2 = drift?.animateFloat(0f, 1f, infiniteRepeatable(tween(GLOW_2_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g2")
    val g3 = drift?.animateFloat(1f, 0f, infiniteRepeatable(tween(GLOW_3_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g3")
    val totals = time.totals
    val before = time.before
    val unit = SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).sp, color = BrandLilac.copy(alpha = 0.8f))
    Column(
        Modifier
            .padding(horizontal = ScreenPadding)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(HuddleEmptyGround)
            .drawBehind {
                fun glow(color: Color, x: Float, y: Float, radius: Float, at: Float?, dx: Float, dy: Float, grow: Float) {
                    val t = at ?: 0f
                    val r = radius * (1f + (grow - 1f) * t)
                    drawCircle(Brush.radialGradient(0f to color.copy(alpha = 0.85f), 0.55f to color.copy(alpha = 0.6f), 1f to Color.Transparent, center = Offset(x + dx * t, y + dy * t), radius = r), r, Offset(x + dx * t, y + dy * t))
                }
                val d = density
                glow(HuddleEmptyGlows[0], 50 * d, 30 * d, 144 * d, g1?.value, 40 * d, 30 * d, 1.1f)
                glow(HuddleEmptyGlows[1], size.width - 30 * d, 80 * d, 154 * d, g2?.value, -50 * d, 40 * d, 0.92f)
                glow(HuddleEmptyGlows[2], 190 * d, size.height + 50 * d, 150 * d, g3?.value, 40 * d, 30 * d, 1.1f)
            }
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            Equaliser(HuddleGold, Modifier.size(15.dp, 12.dp))
            Text(stringResource(R.string.insights_time_kicker, periodName(period)).uppercase(), style = CardKicker, color = BrandLilac.copy(alpha = 0.82f))
        }
        Counted(totals.heardMs, HeroTime, BrandLilac, Modifier.padding(top = 6.dp)) { bigTime(it, unit) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) {}) {
            if (before != null) {
                val change = percentChange(totals.heardMs, before.heardMs)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.heightIn(min = 28.dp).background(if (change < 0) BrandLilac.copy(alpha = 0.18f) else HuddleGold, RoundedCornerShape(14.dp)).padding(start = 6.dp, end = 10.dp),
                ) {
                    val ink = if (change < 0) BrandLilac else OnHuddleGold
                    Icon(painterResource(if (change < 0) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.insights_percent, formatCount(abs(change).toInt())), style = PillNumber.copy(fontSize = 13.sp), color = ink)
                }
                Text(
                    stringResource(if (change < 0) R.string.insights_less_than else R.string.insights_more_than, beforeName(period)) +
                        if (period.going) stringResource(R.string.insights_up_to_now) else "",
                    style = HeroLine,
                    color = BrandLilac.copy(alpha = 0.86f),
                )
            } else {
                val days = (ChronoUnit.DAYS.between(period.firstDay, period.lastDay) + 1).coerceAtLeast(1)
                Text(stringResource(R.string.insights_a_day, listenedText(totals.heardMs / days)), style = HeroLine, color = BrandLilac.copy(alpha = 0.86f))
            }
        }
        HeroBars(time, period, Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            listOf(
                Triple(totals.plays, before?.plays, R.plurals.insights_plays_word),
                Triple(totals.songs, before?.songs, R.plurals.insights_songs_word),
                Triple(totals.artists, before?.artists, R.plurals.insights_artists_word),
                Triple(totals.albums, before?.albums, R.plurals.insights_albums_word),
            ).forEach { (now, then, word) ->
                Column(Modifier.weight(1f).background(BrandLilac.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(horizontal = 8.dp, vertical = 9.dp).semantics(mergeDescendants = true) {}) {
                    Counted(now.toLong(), HeroFigure, BrandLilac) { countText(it) }
                    Text(pluralStringResource(word, now), style = HeroSmall, color = BrandLilac.copy(alpha = 0.8f), maxLines = 2)
                    if (then != null && now != then) {
                        Text(
                            stringResource(if (now > then) R.string.insights_plus else R.string.insights_minus, formatCount(abs(now - then))),
                            style = HeroSmall.copy(fontWeight = FontWeight(650)).tabular(),
                            color = if (now > then) HuddleGold else BrandLilac.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        DarkGround {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                LyrifyButton(playStoryText(period), onClick = { nav.show(FullScreen.Story(period = period.kind)) }, modifier = Modifier.weight(1f), icon = R.drawable.ic_play_arrow_fill)
                ChromeIconButton(R.drawable.ic_share, stringResource(R.string.insights_share), onShare, tint = BrandLilac)
            }
        }
    }
}

/**
 * The hero's chart: a bar for each step (day or month) of the period, coral to gold, growing in one after another (760 ms
 * ease-rise, 16 ms apart); a step still to come a dashed outline; today's glowing gold; the period before as a dashed
 * line fading in. Under it the first, middle and last step and the key. TalkBack reads each step's time.
 */
@Composable
private fun HeroBars(time: TimeCard, period: PagePeriod, modifier: Modifier) {
    val clock = LocalArrivalClock.current
    val steps = time.heard
    val ghost = time.heardBefore?.heardMs
    val now = if (period.going) stepOf(steps, period.lastDay) else -1
    val top = (steps.heardMs.filterNotNull() + ghost.orEmpty().filterNotNull()).maxOrNull()?.takeIf { it > 0 } ?: 1L
    val summary = chartSummary(steps)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = summary }) {
            val gap = 3.dp.toPx()
            val count = steps.heardMs.size.coerceAtLeast(1)
            val width = (size.width - gap * (count - 1)) / count
            val t = clock.ms()
            steps.heardMs.forEachIndexed { i, ms ->
                val x = i * (width + gap)
                if (ms == null) {
                    val outline = 1.5.dp.toPx()
                    drawRoundRect(
                        BrandLilac.copy(alpha = 0.35f),
                        Offset(x + outline / 2, size.height - 8.dp.toPx()),
                        Size(width - outline, 8.dp.toPx() - outline / 2),
                        CornerRadius(3.dp.toPx()),
                        style = Stroke(outline, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                    )
                    return@forEachIndexed
                }
                val height = size.height * (ms.toFloat() / top).coerceAtLeast(0.03f) * phase(t, i * BAR_STEP_MS, BAR_MS, Motion.Rise)
                val glows = i == now
                if (glows) drawRoundRect(HuddleGold.copy(alpha = 0.35f), Offset(x - 3.dp.toPx(), size.height - height - 3.dp.toPx()), Size(width + 6.dp.toPx(), height + 3.dp.toPx()), CornerRadius(6.dp.toPx()))
                drawRoundRect(
                    Brush.verticalGradient((if (glows) InsightsBarNow else InsightsBars).reversed(), startY = size.height - height, endY = size.height),
                    Offset(x, size.height - height),
                    Size(width, height),
                    CornerRadius(minOf(4.dp.toPx(), width / 2)),
                )
            }
            if (ghost != null) {
                val path = Path()
                ghost.take(count).forEachIndexed { i, ms ->
                    val px = i * (width + gap) + width / 2
                    val py = size.height - size.height * ((ms ?: 0L).toFloat() / top)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path,
                    BrandLilac.copy(alpha = 0.75f * phase(t, GHOST_DELAY_MS, GHOST_MS, Motion.Ease)),
                    style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics {}) {
            listOf(0, steps.heardMs.size / 2, steps.heardMs.lastIndex).forEachIndexed { at, i ->
                Text(
                    stepText(steps, i, short = true),
                    style = AxisLabel,
                    color = BrandLilac.copy(alpha = 0.72f),
                    textAlign = when (at) {
                        0 -> TextAlign.Start
                        1 -> TextAlign.Center
                        else -> TextAlign.End
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (ghost != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {}) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(14.dp, 8.dp).background(Brush.horizontalGradient(InsightsBars), RoundedCornerShape(2.dp)))
                    Text(periodName(period), style = AxisLabel, color = BrandLilac.copy(alpha = 0.8f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.size(14.dp, 8.dp)) {
                        drawLine(BrandLilac.copy(alpha = 0.8f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
                    }
                    Text(beforeName(period).replaceFirstChar { it.titlecase(Locale.getDefault()) }, style = AxisLabel, color = BrandLilac.copy(alpha = 0.8f))
                }
            }
        }
    }
}

/** Which step of [steps] holds [day]: its day, or its month; -1 outside them. */
fun stepOf(steps: Steps, day: LocalDate): Int {
    val at = when (steps.step) {
        Step.DAY -> ChronoUnit.DAYS.between(steps.first, day)
        Step.MONTH -> ChronoUnit.MONTHS.between(steps.first.withDayOfMonth(1), day.withDayOfMonth(1))
    }
    return at.toInt().takeIf { it in steps.heardMs.indices } ?: -1
}

/** A step's name: "Mon 23 Nov" (short: "Mon" in a week's, "23 Nov" in a month's), or "Nov 2026" (short: "Nov" in a year's). */
@Composable
internal fun stepText(steps: Steps, i: Int, short: Boolean = false): String {
    val context = LocalContext.current
    val at = when (steps.step) {
        Step.DAY -> steps.first.plusDays(i.toLong())
        Step.MONTH -> steps.first.withDayOfMonth(1).plusMonths(i.toLong())
    }
    val millis = at.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val flags = when {
        steps.step == Step.MONTH && short && steps.heardMs.size <= MONTHS_IN_YEAR -> DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
        steps.step == Step.MONTH -> DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR
        short && steps.heardMs.size <= DAYS_IN_WEEK -> DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
        short -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
        else -> DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    }
    return if (short && steps.step == Step.DAY && steps.heardMs.size <= DAYS_IN_WEEK) {
        DateUtils.formatDateTime(context, millis, flags)
    } else {
        DateUtils.formatDateTime(context, millis, flags or DateUtils.FORMAT_SHOW_DATE)
    }
}

/** What TalkBack says of a chart of [steps]: each step that has come and its time, "Mon 23 Nov 2 h 8 min, ...". */
@Composable
private fun chartSummary(steps: Steps): String {
    val parts = steps.heardMs.mapIndexedNotNull { i, ms -> ms?.let { stringResource(R.string.insights_step, stepText(steps, i), listenedText(it)) } }
    return stringResource(if (steps.step == Step.DAY) R.string.insights_chart_days else R.string.insights_chart_months, parts.joinToString(", "))
}

// Every day you listened

/**
 * Every day you listened: a week as seven columns, a month as a calendar, a year as a square a day, all time as hours by
 * month, each shaded from none to most (the heat map's five steps); then the run now (its flame flickering), the longest
 * run and the busiest day. Each day is a TalkBack stop with its time; the year's squares are read as one summary.
 */
@Composable
internal fun EveryDay(days: DaysCard, period: PagePeriod) {
    val heat = if (LocalDarkTheme.current) DarkInsights.heat else LightInsights.heat
    val line = when (period.kind) {
        ListeningPeriod.WEEK -> stringResource(R.string.insights_days_week)
        ListeningPeriod.MONTH -> stringResource(R.string.insights_days_month, monthText(java.time.YearMonth.from(period.firstDay)))
        ListeningPeriod.YEAR -> stringResource(R.string.insights_days_year)
        ListeningPeriod.ALL_TIME -> stringResource(R.string.insights_days_all)
    }
    InsightsCard {
        CardHead(stringResource(R.string.insights_days_title), R.drawable.ic_calendar_month, line)
        when (period.kind) {
            ListeningPeriod.WEEK -> WeekColumns(days, period, heat)
            ListeningPeriod.MONTH -> MonthCalendar(days, period, heat)
            ListeningPeriod.YEAR -> YearSquares(days, heat)
            ListeningPeriod.ALL_TIME -> MonthHours(days, heat)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.End).padding(top = 8.dp).clearAndSetSemantics {}) {
            Text(stringResource(R.string.insights_heat_less), style = AxisLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
            heat.forEach { Box(Modifier.size(11.dp).background(it, RoundedCornerShape(3.dp))) }
            Text(stringResource(R.string.insights_heat_more), style = AxisLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }
        val brand = LocalBrandColors.current
        val flicker = if (huddleMoves()) rememberInfiniteTransition(label = "flame").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(FLAME_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "flick",
        ) else null
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            days.current?.let { run ->
                DayFact(R.drawable.ic_local_fire_department, stringResource(R.string.insights_run_now), run, stringResource(R.string.insights_run_now_line), Modifier.weight(1f), Modifier.graphicsLayer {
                    // The flame flickers on its foot (the mockup's flick).
                    val f = flicker?.value ?: 0f
                    transformOrigin = TransformOrigin(0.5f, 0.9f)
                    scaleX = 1f + 0.12f * sin(f * PI.toFloat()) - 0.04f * f
                    scaleY = 1f - 0.06f * sin(f * PI.toFloat()) + 0.08f * f
                    rotationZ = -3f + 5f * f
                })
            }
            days.longest?.let { run ->
                val ongoing = period.going && days.current != null && run.from.plusDays(run.days - 1L) >= period.lastDay.minusDays(1)
                val whenLine = when {
                    ongoing -> stringResource(R.string.insights_run_ongoing)
                    period.kind == ListeningPeriod.YEAR || period.kind == ListeningPeriod.ALL_TIME -> stringResource(R.string.insights_run_in, monthText(java.time.YearMonth.from(run.from)))
                    else -> stringResource(R.string.insights_run_from, dayText(run.from, weekday = true))
                }
                DayFact(R.drawable.ic_emoji_events, stringResource(R.string.insights_run_longest), run.days, whenLine, Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(10.dp).semantics(mergeDescendants = true) {}) {
                FactKicker(R.drawable.ic_auto_awesome, stringResource(R.string.insights_busiest), brand.highlight)
                Counted(days.busiest.heardMs, FactValue, MaterialTheme.colorScheme.onSurface) { AnnotatedString(listenedText(it)) }
                Text(dayText(days.busiest.day, weekday = true, year = period.kind == ListeningPeriod.YEAR || period.kind == ListeningPeriod.ALL_TIME), style = FigureLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A fact of the days card: its kicker, [days] counted up with "days" small, and its [line]. */
@Composable
private fun DayFact(@DrawableRes icon: Int, kicker: String, days: Int, line: String, modifier: Modifier, iconMotion: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val word = pluralStringResource(R.plurals.insights_days_word, days)
    Column(modifier.background(colors.surface, RoundedCornerShape(18.dp)).padding(10.dp).semantics(mergeDescendants = true) {}) {
        FactKicker(icon, kicker, LocalBrandColors.current.highlight, iconMotion)
        Counted(days.toLong(), FactValue, colors.onSurface) {
            buildAnnotatedString {
                append(formatCount(it.toInt()))
                withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight(650))) { append(" $word") }
            }
        }
        Text(line, style = FigureLabel, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun FactKicker(@DrawableRes icon: Int, text: String, color: Color, iconMotion: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = iconMotion.size(16.dp))
        Text(text, style = FigureLabel.copy(fontWeight = FontWeight(650)), color = color)
    }
}

/** The heat map's step for [ms] heard on one day ([Step.DAY]) or in one month: 0 (none) to 4 (most). */
fun heatLevel(ms: Long, step: Step): Int {
    val minutes = ms / MINUTE
    val steps = if (step == Step.DAY) DAY_HEAT_MINUTES else MONTH_HEAT_MINUTES
    return when {
        ms <= 0 -> 0
        else -> 1 + steps.count { minutes >= it }
    }
}

/** The ink that reads best on a heat map step: the page's dark coral ink or lilac, whichever has the more contrast. */
fun heatInk(ground: Color): Color = if (contrast(LightBrandColors.onHighlightContainer, ground) >= contrast(BrandLilac, ground)) LightBrandColors.onHighlightContainer else BrandLilac

/** A week: a column a day with its bar growing in, its weekday above and its hours under; a day to come dashed. */
@Composable
private fun WeekColumns(days: DaysCard, period: PagePeriod, heat: List<Color>) {
    val clock = LocalArrivalClock.current
    val colors = MaterialTheme.colorScheme
    val top = days.heard.heardMs.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1L
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        days.heard.heardMs.forEachIndexed { i, ms ->
            val day = days.heard.first.plusDays(i.toLong())
            val today = day == period.lastDay && period.going
            val name = stepText(days.heard, i)
            val description = if (ms == null) name else stringResource(R.string.insights_step, name, if (ms > 0) listenedText(ms) else stringResource(R.string.insights_nothing))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).then(if (ms == null) Modifier.clearAndSetSemantics {} else Modifier.clearAndSetSemantics { contentDescription = description }),
            ) {
                Text(stepText(days.heard, i, short = true), style = AxisLabel.copy(fontWeight = FontWeight.SemiBold), color = if (today) colors.onSurface else colors.onSurfaceVariant, maxLines = 1)
                Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    val corner = CornerRadius(14.dp.toPx())
                    if (ms == null) {
                        val outline = 1.5.dp.toPx()
                        drawRoundRect(colors.outlineVariant, Offset(outline / 2, outline / 2), Size(size.width - outline, size.height - outline), corner, Stroke(outline, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                        return@Canvas
                    }
                    drawRoundRect(heat[0], cornerRadius = corner)
                    val height = size.height * (ms.toFloat() / top) * phase(clock.ms(), i * 3 * BAR_STEP_MS, BAR_MS, Motion.Rise)
                    drawRoundRect(heat[heatLevel(ms, Step.DAY).coerceAtLeast(1)], Offset(0f, size.height - height), Size(size.width, height), CornerRadius(12.dp.toPx()))
                }
                Text(if (ms == null) "" else stringResource(R.string.insights_hours_short, String.format(Locale.getDefault(), "%.1f", ms / HOUR.toDouble())), style = AxisLabel.copy(fontWeight = FontWeight.SemiBold).tabular(), color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * A month as a calendar from the phone's first day of the week: each day its number on its heat step (a day to come
 * dashed), today ringed in ink, the busiest day with a gold dot.
 */
@Composable
private fun MonthCalendar(days: DaysCard, period: PagePeriod, heat: List<Color>) {
    val colors = MaterialTheme.colorScheme
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val first = days.heard.first
    val lead = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val weekdays = (0 until 7).map { firstDayOfWeek.plus(it.toLong()).getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()) }
    val cells = List(lead) { null } + days.heard.heardMs.indices.map { it }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.clearAndSetSemantics {}) {
            weekdays.forEach { Text(it, style = AxisLabel.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                week.forEach { i ->
                    if (i == null) {
                        Spacer(Modifier.weight(1f))
                        return@forEach
                    }
                    val ms = days.heard.heardMs[i]
                    val day = first.plusDays(i.toLong())
                    val ground = heat[if (ms == null) 0 else heatLevel(ms, Step.DAY)]
                    val today = day == period.lastDay && period.going
                    val busiest = day == days.busiest.day
                    val description = stringResource(R.string.insights_step, stepText(days.heard, i), if (ms != null && ms > 0) listenedText(ms) else stringResource(R.string.insights_nothing))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .then(if (ms == null) Modifier.clearAndSetSemantics {} else Modifier.clearAndSetSemantics { contentDescription = description })
                            .drawBehind {
                                val corner = CornerRadius(10.dp.toPx())
                                if (ms == null) {
                                    val outline = 1.5.dp.toPx()
                                    drawRoundRect(colors.outlineVariant, Offset(outline / 2, outline / 2), Size(size.width - outline, size.height - outline), corner, Stroke(outline, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                                    return@drawBehind
                                }
                                if (today) drawRoundRect(colors.onSurface, Offset(-4.dp.toPx(), -4.dp.toPx()), Size(size.width + 8.dp.toPx(), size.height + 8.dp.toPx()), CornerRadius(14.dp.toPx()), Stroke(2.dp.toPx()))
                                drawRoundRect(ground, cornerRadius = corner)
                                if (busiest) {
                                    drawCircle(colors.surfaceContainerLow, 7.dp.toPx(), Offset(size.width - 2.dp.toPx(), 2.dp.toPx()))
                                    drawCircle(HuddleGold, 5.dp.toPx(), Offset(size.width - 2.dp.toPx(), 2.dp.toPx()))
                                }
                            },
                    ) {
                        Text(
                            day.dayOfMonth.toString(),
                            style = AxisLabel.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold).tabular(),
                            color = if (ms == null) colors.outline else if (ms > 0) heatInk(ground) else colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A year as a square a day, a column a week (the month's first letter over its first week), read as one summary. */
@Composable
private fun YearSquares(days: DaysCard, heat: List<Color>) {
    val colors = MaterialTheme.colorScheme
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val first = days.heard.first
    val lead = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val total = first.lengthOfYear()
    val columns = (lead + total + 6) / 7
    val summary = pluralStringResource(R.plurals.insights_year_summary, days.heard.heardMs.count { (it ?: 0) > 0 }, first.year, days.heard.heardMs.count { (it ?: 0) > 0 })
    Column(Modifier.semantics { contentDescription = summary }) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            val cell = maxWidth / columns
            (1..MONTHS_IN_YEAR).forEach { month ->
                val at = lead + ChronoUnit.DAYS.between(first, first.withMonth(month)).toInt()
                Text(
                    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                    style = AxisLabel.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.offset(x = cell * (at / 7)).clearAndSetSemantics {},
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().aspectRatio(columns / 7f).clearAndSetSemantics {}) {
            val gap = 1.5.dp.toPx()
            val side = (size.width - gap * (columns - 1)) / columns
            (0 until total).forEach { d ->
                val ms = days.heard.heardMs.getOrNull(d) ?: return@forEach
                val at = lead + d
                drawRoundRect(heat[heatLevel(ms, Step.DAY)], Offset((at / 7) * (side + gap), (at % 7) * (side + gap)), Size(side, side), CornerRadius(1.5.dp.toPx()))
            }
        }
    }
}

/** All time as hours by month: a row a year, a cell a month with its hours on its heat step. */
@Composable
private fun MonthHours(days: DaysCard, heat: List<Color>) {
    val colors = MaterialTheme.colorScheme
    val first = days.heard.first.withDayOfMonth(1)
    val byYear = days.heard.heardMs.indices.groupBy { first.plusMonths(it.toLong()).year }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.clearAndSetSemantics {}) {
            Spacer(Modifier.width(36.dp))
            (1..MONTHS_IN_YEAR).forEach {
                Text(java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()), style = AxisLabel.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        byYear.forEach { (year, steps) ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(year.toString(), style = AxisLabel.copy(fontWeight = FontWeight(650)), color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.width(36.dp).clearAndSetSemantics {})
                (1..MONTHS_IN_YEAR).forEach { month ->
                    val i = steps.firstOrNull { first.plusMonths(it.toLong()).monthValue == month }
                    val ms = i?.let { days.heard.heardMs[it] }
                    if (ms == null) {
                        Spacer(Modifier.weight(1f))
                        return@forEach
                    }
                    val ground = heat[heatLevel(ms, Step.MONTH)]
                    val hours = (ms / HOUR).toInt()
                    val description = stringResource(R.string.insights_step, stepText(days.heard, i), pluralStringResource(R.plurals.insights_hours_long, hours, formatCount(hours)))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).aspectRatio(0.8f).background(ground, RoundedCornerShape(6.dp)).clearAndSetSemantics { contentDescription = description },
                    ) {
                        Text(hours.toString(), style = AxisLabel.copy(fontSize = 10.sp, fontWeight = FontWeight(650)).tabular(), color = if (ms > 0) heatInk(ground) else colors.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

// Your day on a record

/**
 * Your day on a record (the mockup's dial): a record with three grooves and a line for each hour, the longer the more
 * heard, the hour heard most in coral and named in its middle; it spins in as its card arrives and its lines fade in
 * one after another. Then the four parts of the day (the most heard one marked) and weekdays against the weekend.
 */
@Composable
internal fun DayOnARecord(clock: ClockCard) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val total = clock.hours.sum()
    val parts = dayParts(clock.hours)
    val top = parts.withIndex().maxByOrNull { it.value }?.index
    InsightsCard {
        CardHead(stringResource(R.string.insights_record_title), R.drawable.ic_schedule, stringResource(R.string.insights_record_line))
        Dial(clock.hours, clock.peak, colors.surface, colors.outlineVariant, insights.series[0], brand.highlight, colors.onSurfaceVariant, colors.onSurface, Modifier.align(Alignment.CenterHorizontally).size(236.dp))
        val names = listOf(R.string.insights_morning, R.string.insights_afternoon, R.string.insights_evening, R.string.insights_night)
        val ranges = listOf(R.string.insights_morning_hours, R.string.insights_afternoon_hours, R.string.insights_evening_hours, R.string.insights_night_hours)
        val icons = listOf(R.drawable.ic_wb_sunny, R.drawable.ic_light_mode, R.drawable.ic_wb_twilight, R.drawable.ic_dark_mode)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
            parts.indices.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { i ->
                        val most = i == top
                        val ink = if (most) brand.onHighlightContainer else colors.onSurface
                        val line = if (most) brand.onHighlightContainer else colors.onSurfaceVariant
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f).background(if (most) brand.highlightContainer else colors.surface, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
                        ) {
                            Icon(painterResource(icons[i]), contentDescription = null, tint = line, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(names[i]), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight(650)), color = ink)
                                Text(stringResource(ranges[i]), style = FigureLabel, color = line)
                            }
                            Text(stringResource(R.string.insights_percent, formatCount(percentOf(parts[i], total))), style = FactValue.copy(fontSize = 18.sp).tabular(), color = ink)
                        }
                    }
                }
            }
        }
        val week = percentOf(clock.weekdaysMs, clock.weekdaysMs + clock.weekendMs)
        StackBar(listOf(week, 100 - week), listOf(insights.series[0], insights.series[2]), 14.dp, Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(stringResource(R.string.insights_weekdays, formatCount(week)), style = FigureLabel.copy(fontSize = 12.5.sp), color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.insights_weekends, formatCount(100 - week)), style = FigureLabel.copy(fontSize = 12.5.sp), color = colors.onSurfaceVariant)
        }
    }
}

/** The real minutes of the four parts of the day from 24 [hours]: morning 6 to 12, afternoon 12 to 6 pm, evening 6 to 10 pm, night 10 pm to 6 am. */
fun dayParts(hours: List<Long>): List<Long> = listOf(6 until 12, 12 until 18, 18 until 22, (22 until 24) + (0 until 6)).map { range -> range.sumOf { hours.getOrElse(it) { 0L } } }

/**
 * The record of the day: [hours] as 24 lines round a disc, the [peak] hour in [peakColor] and named in the middle;
 * spinning in from a third of a turn back and 60% (1.1 s ease-rise), the lines fading in after (700 ms, 30 ms apart).
 * TalkBack reads the hour heard most and each hour's time.
 */
@Composable
internal fun Dial(
    hours: List<Long>,
    peak: Int,
    disc: Color,
    groove: Color,
    line: Color,
    peakColor: Color,
    label: Color,
    centre: Color,
    modifier: Modifier = Modifier,
) {
    val clock = LocalArrivalClock.current
    val hourWords = (0 until HOURS).map { hourLabel(it) }
    val summary = stringResource(R.string.insights_dial, hourWords[peak], hours.withIndex().filter { it.value > 0 }.map { stringResource(R.string.insights_step, hourWords[it.index], listenedText(it.value)) }.joinToString(", "))
    val your = stringResource(R.string.insights_your_hour)
    val measurer = rememberTextMeasurer()
    val labelStyle = AxisLabel.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = label)
    val centreStyle = display(30, 32, -0.02, weight = 700).copy(color = centre)
    val yourStyle = AxisLabel.copy(fontSize = 11.5.sp, color = label)
    Canvas(modifier.semantics { contentDescription = summary }) {
        val c = Offset(size.width / 2, size.height / 2)
        val s = size.minDimension
        val t = clock.ms()
        val spin = phase(t, 0, DIAL_MS, Motion.Rise)
        val most = hours.maxOrNull()?.takeIf { it > 0 } ?: 1L
        scale(0.6f + 0.4f * spin, c) {
            rotate(-120f * (1f - spin), c) {
                drawCircle(disc.copy(alpha = spin), s * 0.4f, c)
                listOf(0.56f, 0.66f, 0.76f).forEach { drawCircle(groove.copy(alpha = spin), s * it / 2, c, style = Stroke(1.dp.toPx())) }
                hours.forEachIndexed { h, ms ->
                    val angle = (h / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
                    val r0 = s * 0.25f
                    val len = 4.dp.toPx() + (ms.toFloat() / most) * s * 0.2f
                    val fade = phase(t, DIAL_LINES_MS + h * DIAL_LINE_STEP_MS, DIAL_LINE_MS, Motion.Rise)
                    drawLine(
                        (if (h == peak) peakColor else line).copy(alpha = fade),
                        Offset(c.x + cos(angle) * r0, c.y + sin(angle) * r0),
                        Offset(c.x + cos(angle) * (r0 + len), c.y + sin(angle) * (r0 + len)),
                        7.dp.toPx(),
                        StrokeCap.Round,
                    )
                }
            }
        }
        listOf(0, 6, 12, 18).forEach { h ->
            val angle = (h / 24f) * 2 * PI.toFloat() - PI.toFloat() / 2
            val text = measurer.measure(hourWords[h], labelStyle)
            drawText(text, topLeft = Offset(c.x + cos(angle) * s * 0.47f - text.size.width / 2, c.y + sin(angle) * s * 0.47f - text.size.height / 2))
        }
        val middle = measurer.measure(hourWords[peak], centreStyle)
        drawText(middle, topLeft = Offset(c.x - middle.size.width / 2, c.y - middle.size.height / 2 - 6.dp.toPx()))
        val under = measurer.measure(your, yourStyle)
        drawText(under, topLeft = Offset(c.x - under.size.width / 2, c.y + middle.size.height / 2 - 4.dp.toPx()))
    }
}

/** An hour as the phone says a short time: "10 pm", or "22" where the day has 24 hours. */
@Composable
internal fun hourLabel(hour: Int): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "H" else "ha")
    val at = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, 0)
    }
    return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(at.time).lowercase(Locale.getDefault())
}

// The top lists

/**
 * Top artists (the mockup's race): number 1 big in a gold ring with its medal, its plays and time, and how long it has
 * been number 1; then 2 to 5 as bars racing in (900 ms ease-rise, 110 ms apart) with their ups and downs. Each opens its
 * artist.
 */
@Composable
internal fun TopArtists(card: TopCard<Artist>, period: PagePeriod, onOpen: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val (shown, more) = topPreview(card.rows)
    val first = shown.first()
    InsightsCard {
        CardHead(stringResource(R.string.stats_top_artists), action = if (more) ({ ViewAll { onOpen(Page.TopList(TopKind.ARTISTS)) } }) else null)
        val description = stringResource(R.string.stats_row, 1, first.top.item.name, playsNote(first.top.plays)) + placeWords(1, first.before, card.compared)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = stringResource(R.string.stats_open_artist)) { onOpen(Page.Artist(first.top.item.name)) }.semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Box(Modifier.size(112.dp)) {
                Box(Modifier.matchParentSize().padding(5.dp).border(2.dp, HuddleGold.copy(alpha = 0.9f), CircleShape).padding(5.dp)) {
                    SongCover(SongArt(first.cover.path, first.cover.ref.mediaStoreId), Modifier.fillMaxWidth().aspectRatio(1f), size = 92.dp, shape = CircleShape)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.align(Alignment.BottomStart).size(36.dp).border(3.dp, colors.surfaceContainerLow, CircleShape).padding(1.dp).background(HuddleGold, CircleShape)) {
                    Text("1", style = display(18, 18, weight = 700), color = OnHuddleGold)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(first.top.item.name, style = display(26, 30, -0.02, weight = 700), color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Counted(first.top.plays.toLong(), MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), colors.onSurfaceVariant) {
                    AnnotatedString(stringResource(R.string.stats_row_line, playsNote(it.toInt()), listenedText(first.top.heardMs)))
                }
                card.loyalty?.let { loyalty ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 8.dp).background(colors.surface, RoundedCornerShape(12.dp)).padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_emoji_events), contentDescription = null, tint = insights.goldInk, modifier = Modifier.size(16.dp))
                        Text(loyaltyText(loyalty, period), style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = insights.goldInk)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 16.dp)) {
            shown.drop(1).forEachIndexed { n, row ->
                val place = n + 2
                val clock = LocalArrivalClock.current
                val rowDescription = stringResource(R.string.stats_row, place, row.top.item.name, playsNote(row.top.plays)) + placeWords(place, row.before, card.compared)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = stringResource(R.string.stats_open_artist)) { onOpen(Page.Artist(row.top.item.name)) }
                        .semantics(mergeDescendants = true) { contentDescription = rowDescription }
                        .heightIn(min = 52.dp),
                ) {
                    Text(place.toString(), style = RankStyle, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(22.dp))
                    SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), size = 40.dp, shape = CircleShape)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.top.item.name, style = RowTitle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            PlaceChange(place, row.before, card.compared)
                        }
                        val share = row.top.plays.toFloat() / first.top.plays.coerceAtLeast(1)
                        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                            val grown = phase(clock.ms(), RACE_DELAY_MS + n * RACE_STEP_MS, RACE_MS, Motion.Rise)
                            drawRoundRect(if (n == 0) insights.series[1] else insights.series[0], size = Size(size.width * share * grown, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatCount(row.top.plays), style = CountStyle, color = colors.onSurface)
                        Text(pluralStringResource(R.plurals.insights_plays_word, row.top.plays), style = FigureLabel.copy(fontSize = 11.5.sp), color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "Your number 1 for 6 weeks running", "in 8 of 11 months", "since June 2025". */
@Composable
private fun loyaltyText(loyalty: Loyalty, period: PagePeriod): String = when (loyalty) {
    is Loyalty.Running -> pluralStringResource(if (period.kind == ListeningPeriod.WEEK) R.plurals.insights_loyal_weeks else R.plurals.insights_loyal_months_running, loyalty.periods, loyalty.periods)
    is Loyalty.MonthsOf -> stringResource(R.string.insights_loyal_months, loyalty.months, loyalty.of)
    is Loyalty.Since -> stringResource(R.string.insights_loyal_since, monthText(loyalty.month, java.time.YearMonth.of(0, 1)))
}

/**
 * Top songs: the top song's cover big and tilted with its play button, "Your top song", its name, artist and plays
 * counted up; then 2 to 5 with their covers, ups and downs, artist and time, and a short bar of their plays; then Play
 * your top 25. A row opens its song sheet.
 */
@Composable
internal fun TopSongs(card: TopCard<Song>, settings: Settings, onOpen: (Page) -> Unit, onSong: (SongRef) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chrome = LocalChromeColors.current
    val brand = LocalBrandColors.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val playback = rememberPlayback()
    val (shown, more) = topPreview(card.rows)
    val first = shown.first()
    val top25 = card.rows.take(PLAY_TOP).map { it.top.item.ref }
    val source = QueueSource(SourceKind.PICKED, TOP_SONGS_KEY, stringResource(R.string.stats_top_songs))
    InsightsCard {
        CardHead(stringResource(R.string.stats_top_songs), action = if (more) ({ ViewAll { onOpen(Page.TopList(TopKind.SONGS)) } }) else null)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(134.dp).padding(end = 10.dp, bottom = 10.dp)) {
                SongCover(SongArt(first.cover.path, first.cover.ref.mediaStoreId), Modifier.rotate(-3f), size = 124.dp, shape = RoundedCornerShape(22.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(10.dp, 10.dp)
                        .size(56.dp)
                        .border(4.dp, colors.surfaceContainerLow, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(chrome.action)
                        .clickable(onClickLabel = null) { playback.play(top25, 0, source) }
                        .semantics { contentDescription = "" },
                ) {
                    Icon(painterResource(R.drawable.ic_play_arrow_fill), contentDescription = stringResource(R.string.insights_play_song, displayTitle(first.top.item, settings.library)), tint = chrome.onAction, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.stats_song_options)) { onSong(first.top.item.ref) }.semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.insights_top_song).uppercase(), style = CardKicker.copy(fontSize = 11.5.sp), color = brand.highlight)
                Text(displayTitle(first.top.item, settings.library), style = display(24, 28, -0.02, weight = 700), color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                first.top.item.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Counted(first.top.plays.toLong(), BigNumber, colors.onSurface, Modifier.padding(top = 6.dp)) { plays ->
                    buildAnnotatedString {
                        append(formatCount(plays.toInt()))
                        withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)) { append(" " + pluralStringResource(R.plurals.insights_plays_word, plays.toInt())) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 16.dp)) {
            shown.drop(1).forEachIndexed { n, row ->
                val place = n + 2
                val song = row.top.item
                val clock = LocalArrivalClock.current
                val title = displayTitle(song, settings.library)
                val rowDescription = stringResource(R.string.stats_row, place, title, playsNote(row.top.plays)) + placeWords(place, row.before, card.compared)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = stringResource(R.string.stats_song_options)) { onSong(song.ref) }
                        .semantics(mergeDescendants = true) { contentDescription = rowDescription }
                        .heightIn(min = 56.dp),
                ) {
                    Text(place.toString(), style = RankStyle, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(22.dp))
                    SongCover(SongArt(song.path, song.ref.mediaStoreId), size = 44.dp, shape = RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = RowTitle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            PlaceChange(place, row.before, card.compared)
                        }
                        Text(listOfNotNull(song.artist?.takeIf { it.isNotBlank() }, listenedText(row.top.heardMs)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(IntrinsicWidthOfCount)) {
                        Text(formatCount(row.top.plays), style = CountStyle, color = colors.onSurface)
                        Text(pluralStringResource(R.plurals.insights_plays_word, row.top.plays), style = FigureLabel.copy(fontSize = 11.5.sp), color = colors.onSurfaceVariant)
                        val share = row.top.plays.toFloat() / first.top.plays.coerceAtLeast(1)
                        Canvas(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp)) {
                            val grown = phase(clock.ms(), RACE_DELAY_MS + n * RACE_STEP_MS, RACE_MS, Motion.Rise)
                            drawRoundRect(insights.series[1].copy(alpha = 0.85f), size = Size(size.width * share * grown, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
                        }
                    }
                }
            }
        }
        LyrifyButton(
            stringResource(R.string.insights_play_top, top25.size),
            onClick = { playback.play(top25, 0, source) },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            icon = R.drawable.ic_play_arrow_fill,
        )
    }
}

/**
 * Top albums as a collage: the first big with its gold place and its name over a shade, the next five smaller with their
 * places, each popping in (560 ms ease-pop, 60 ms apart) and opening its album; then Share the collage.
 */
@Composable
internal fun TopAlbums(card: TopCard<Album>, onOpen: (Page) -> Unit, onShare: () -> Unit) {
    val (_, more) = topPreview(card.rows)
    val albums = card.rows.take(COLLAGE)
    InsightsCard {
        CardHead(stringResource(R.string.stats_top_albums), action = if (more) ({ ViewAll { onOpen(Page.TopList(TopKind.ALBUMS)) } }) else null)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 6.dp
            val cell = (maxWidth - gap * 2) / 3
            albums.forEachIndexed { i, row ->
                val big = i == 0
                val x = when (i) {
                    0, 3 -> 0.dp
                    1, 2 -> (cell + gap) * 2
                    4 -> cell + gap
                    else -> (cell + gap) * 2
                }
                val y = when (i) {
                    0, 1 -> 0.dp
                    2 -> cell + gap
                    else -> (cell + gap) * 2
                }
                AlbumTile(row, i + 1, big, Modifier.offset(x, y).size(if (big) cell * 2 + gap else cell), onOpen)
            }
            Spacer(Modifier.height(if (albums.size > 3) cell * 3 + gap * 2 else cell * 2 + gap))
        }
        LyrifyButton(stringResource(R.string.insights_share_collage), onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_share)
    }
}

/** One album of the collage: its cover, its place, and on the first its name and artist over a shade. */
@Composable
private fun AlbumTile(row: Ranked<Album>, place: Int, big: Boolean, modifier: Modifier, onOpen: (Page) -> Unit) {
    val clock = LocalArrivalClock.current
    val album = row.top.item
    val description = stringResource(R.string.insights_album_place, place, album.title, album.artist.orEmpty())
    Box(
        modifier
            .graphicsLayer {
                val pop = phase(clock.ms(), (place - 1) * POP_STEP_MS, POP_MS, Motion.Pop)
                alpha = phase(clock.ms(), (place - 1) * POP_STEP_MS, POP_MS)
                scaleX = 0.6f + 0.4f * pop
                scaleY = 0.6f + 0.4f * pop
                rotationZ = -6f * (1f - pop)
            }
            .clip(RoundedCornerShape(if (big) 22.dp else 14.dp))
            .clickable(onClickLabel = stringResource(R.string.stats_open_album)) { onOpen(Page.Album(album.key)) }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), Modifier.fillMaxWidth().aspectRatio(1f), size = if (big) 220.dp else 104.dp, shape = androidx.compose.ui.graphics.RectangleShape)
        if (big) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, HuddleCardShade.copy(alpha = 0.78f)))).padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 10.dp)) {
                Text(album.title, style = display(18, 22, weight = 700), color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis)
                album.artist?.let { Text(it, style = FigureLabel.copy(fontSize = 12.5.sp), color = BrandLilac.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(if (big) 10.dp else 6.dp)
                .heightIn(min = if (big) 30.dp else 24.dp)
                .width(androidx.compose.ui.unit.max(if (big) 30.dp else 24.dp, 0.dp))
                .background(if (big) HuddleGold else HuddleCardShade.copy(alpha = 0.62f), CircleShape),
        ) {
            Text(place.toString(), style = display(if (big) 15 else 13, 15, weight = 700), color = if (big) OnHuddleGold else BrandLilac)
        }
    }
}

// Your taste

/**
 * Your taste: the genres as bubbles sized by their share of the plays, popping in (700 ms ease-pop, 90 ms apart) then
 * floating; the plays by decade as bars growing in, the most played one in coral; and the middle year of your music.
 */
@Composable
internal fun YourTaste(taste: TasteCard) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    InsightsCard {
        CardHead(stringResource(R.string.insights_taste_title), R.drawable.ic_music_note, stringResource(R.string.insights_taste_line))
        val genres = taste.genres.take(BUBBLES)
        val all = taste.genres.sumOf { it.plays.toLong() }
        if (genres.isNotEmpty()) {
            val shares = genres.map { percentOf(it.plays.toLong(), all) }
            val float = if (huddleMoves()) rememberInfiniteTransition(label = "bubbles").animateFloat(0f, 1f, infiniteRepeatable(tween(FLOAT_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float") else null
            val words = genres.zip(shares).map { (top, share) -> stringResource(R.string.insights_share_of, top.item.name, formatCount(share)) }.joinToString(", ")
            BoxWithConstraints(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = words }) {
                val density = LocalDensity.current
                val width = with(density) { maxWidth.toPx() }
                val height = with(density) { maxHeight.toPx() }
                val placed = remember(shares, width) { packBubbles(shares, width, height, with(density) { BUBBLE_MIN.toPx() }, with(density) { BUBBLE_MAX.toPx() }, with(density) { 4.dp.toPx() }) }
                val clock = LocalArrivalClock.current
                placed.forEach { bubble ->
                    val color = FriendColors[bubble.index % BUBBLES]
                    val small = bubble.radius < with(density) { 40.dp.toPx() }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .offset { androidx.compose.ui.unit.IntOffset((bubble.x - bubble.radius).roundToInt(), (bubble.y - bubble.radius).roundToInt()) }
                            .size(with(density) { (bubble.radius * 2).toDp() })
                            .graphicsLayer {
                                val pop = phase(clock.ms(), bubble.index * BUBBLE_STEP_MS, BUBBLE_MS, Motion.Pop)
                                scaleX = pop
                                scaleY = pop
                                // Each floats on its own beat (the mockup's delays 700 ms apart).
                                val beat = float?.let { ((it.value + bubble.index * 0.14f) % 1f).let { f -> if (f < 0.5f) f * 2 else 2 - f * 2 } } ?: 0f
                                translationY = -5.dp.toPx() * beat
                            }
                            .drawBehind {
                                drawCircle(Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(color, BrandLilac, 0.45f), color), Offset(size.width * 0.32f, size.height * 0.26f), size.width * 0.7f))
                                drawCircle(Brush.verticalGradient(listOf(Color.Transparent, HuddleCardShade.copy(alpha = 0.18f))))
                            }
                            .padding(6.dp)
                            .clearAndSetSemantics {},
                    ) {
                        Text(genres[bubble.index].item.name, style = body(if (small) 11f else 13f, if (small) 13 else 16, 700), color = BrandLilac, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.insights_percent, formatCount(shares[bubble.index])), style = display(if (small) 12 else 15, if (small) 15 else 18, weight = 700).tabular(), color = BrandLilac)
                    }
                }
            }
        }
        if (taste.decades.isNotEmpty()) {
            Text(stringResource(R.string.insights_decades), style = display(15, 20, weight = 650), color = colors.onSurface, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp).semantics { heading() })
            val plays = taste.decades.sumOf { it.plays.toLong() }
            val most = taste.decades.maxOf { it.plays }
            val clock = LocalArrivalClock.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(132.dp).padding(top = 10.dp)) {
                taste.decades.takeLast(DECADES).forEachIndexed { k, decade ->
                    val peak = decade.plays == most
                    val share = percentOf(decade.plays.toLong(), plays)
                    val name = stringResource(R.string.insights_decade, String.format(Locale.ROOT, "%02d", decade.from % 100))
                    val description = stringResource(R.string.insights_share_of, name, formatCount(share))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = description },
                    ) {
                        Text(stringResource(R.string.insights_percent, formatCount(share)), style = AxisLabel.copy(fontSize = 12.sp, fontWeight = FontWeight(650)).tabular(), color = if (peak) brand.highlight else colors.onSurfaceVariant, maxLines = 1)
                        Canvas(Modifier.weight(1f).fillMaxWidth()) {
                            val h = size.height * (decade.plays.toFloat() / most) * phase(clock.ms(), k * 3 * BAR_STEP_MS, BAR_MS, Motion.Rise)
                            val color = if (peak) insights.series[1] else insights.series[0].copy(alpha = 0.85f)
                            drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(minOf(10.dp.toPx(), h / 2)))
                        }
                        Text(name, style = AxisLabel.copy(fontSize = 12.sp, fontWeight = FontWeight(650)), color = colors.onSurface, maxLines = 1)
                    }
                }
            }
        }
        taste.middleYear?.let { year ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
            ) {
                Text(year.toString(), style = display(30, 32, -0.02, weight = 700).tabular(), color = brand.highlight)
                Text(stringResource(R.string.insights_middle_year), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp), color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A genre bubble laid out: its [index] in the list, its centre and [radius], px. */
data class Bubble(val index: Int, val x: Float, val y: Float, val radius: Float)

/**
 * Bubbles for [shares] (percent, the largest first) in a [width] by [height] box: each sized by the square root of its
 * share of the first (from [smallest] to [largest] across), placed at the free spot nearest the middle (the box's width
 * counting less), [gap] apart; one with no room left out.
 */
fun packBubbles(shares: List<Int>, width: Float, height: Float, smallest: Float, largest: Float, gap: Float): List<Bubble> {
    if (shares.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val step = 3f
    val spots = buildList {
        var x = 0f
        while (x <= width) {
            var y = 0f
            while (y <= height) {
                add(Triple(x, y, hypot((x - width / 2) / 1.7f, y - height / 2)))
                y += step
            }
            x += step
        }
    }.sortedBy { it.third }
    val top = shares.first().coerceAtLeast(1)
    val placed = mutableListOf<Bubble>()
    shares.forEachIndexed { i, share ->
        val r = max(smallest, sqrt(share.toFloat() / top) * largest)
        spots.firstOrNull { (x, y) -> x - r >= 0 && x + r <= width && y - r >= 0 && y + r <= height && placed.all { hypot(it.x - x, it.y - y) >= it.radius + r + gap } }
            ?.let { (x, y) -> placed += Bubble(i, x, y, r) }
    }
    return placed
}

// How you listen

/**
 * How you listen: what started each song as one bar of five ways in (picked, albums and lists in order, Shuffle,
 * suggestions, Huddle) with its legend; how plays ended as a ring (played to the end, moved on after 30 s, skipped early)
 * drawing itself in; how much more Shuffle's songs were skipped early; and the song skipped most.
 */
@Composable
internal fun HowYouListen(how: HowCard, settings: Settings, onSong: (SongRef) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val starts = listOf(how.started.picked, how.started.inOrder, how.started.shuffle, how.started.suggestions, how.started.huddle)
    val all = starts.sumOf { it.toLong() }
    val shares = starts.map { percentOf(it.toLong(), all) }
    val names = listOf(R.string.insights_start_picked, R.string.insights_start_in_order, R.string.insights_start_shuffle, R.string.insights_start_suggestions, R.string.insights_start_huddle).map { stringResource(it) }
    InsightsCard {
        CardHead(stringResource(R.string.insights_how_listen_title), R.drawable.ic_queue_music, stringResource(R.string.insights_how_listen_line))
        StackBar(shares, insights.series, 40.dp)
        Column(Modifier.padding(top = 12.dp)) {
            names.indices.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { i -> LegendItem(insights.series[i], names[i], stringResource(R.string.insights_percent, formatCount(shares[i])), Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        val ends = how.ends
        val total = (ends.finished + ends.movedOn + ends.early).toLong()
        if (total > 0) {
            val endShares = listOf(ends.finished, ends.movedOn, ends.early).map { percentOf(it.toLong(), total) }
            val endColors = listOf(insights.series[3], insights.series[2], insights.series[1])
            val endNames = listOf(R.string.insights_end_finished, R.string.insights_end_moved_on, R.string.insights_end_early).map { stringResource(it) }
            Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(1.dp).background(colors.outlineVariant))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 16.dp)) {
                val clock = LocalArrivalClock.current
                val ring = colors.surfaceContainerHigh
                val centre = stringResource(R.string.insights_percent, formatCount(endShares[0]))
                val toEnd = stringResource(R.string.insights_to_the_end)
                val measurer = rememberTextMeasurer()
                val big = display(28, 30, -0.02, weight = 700).copy(color = colors.onSurface)
                val small = AxisLabel.copy(fontSize = 11.sp, color = colors.onSurfaceVariant)
                Canvas(Modifier.size(128.dp).clearAndSetSemantics {}) {
                    val stroke = 16.dp.toPx()
                    val r = 48.dp.toPx() * size.minDimension / 128.dp.toPx()
                    val box = Size(r * 2, r * 2)
                    val at = Offset(size.width / 2 - r, size.height / 2 - r)
                    drawArc(ring, 0f, 360f, false, at, box, style = Stroke(stroke))
                    var start = -90f
                    endShares.forEachIndexed { k, share ->
                        val sweep = 360f * share / 100f
                        val drawn = phase(clock.ms(), DONUT_DELAY_MS + k * DONUT_STEP_MS, DONUT_MS, Motion.Rise)
                        if (sweep > 2f) drawArc(endColors[k], start, (sweep - 2f) * drawn, false, at, box, style = Stroke(stroke))
                        start += sweep
                    }
                    val a = measurer.measure(centre, big)
                    drawText(a, topLeft = Offset(size.width / 2 - a.size.width / 2, size.height / 2 - a.size.height / 2 - 6.dp.toPx()))
                    val b = measurer.measure(toEnd, small)
                    drawText(b, topLeft = Offset(size.width / 2 - b.size.width / 2, size.height / 2 + a.size.height / 2 - 6.dp.toPx()))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    endNames.forEachIndexed { k, name ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            Box(Modifier.padding(top = 4.dp).size(10.dp).background(endColors[k], RoundedCornerShape(3.dp)))
                            Column {
                                Text(stringResource(R.string.insights_percent, formatCount(endShares[k])), style = FactValue.copy(fontSize = 17.sp, lineHeight = 20.sp).tabular(), color = colors.onSurface)
                                Text(name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp), color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        skipRatio(how.shuffled, how.inOrder)?.let { ratio ->
            Text(
                buildAnnotatedString {
                    val words = stringResource(R.string.insights_shuffle_skips, String.format(Locale.getDefault(), "%.1f", ratio))
                    val bold = stringResource(R.string.insights_times, String.format(Locale.getDefault(), "%.1f", ratio))
                    val at = words.indexOf(bold)
                    append(words)
                    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight(650), color = colors.onSurface), at, at + bold.length)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        how.mostSkipped?.let { skipped ->
            val title = displayTitle(skipped.song, settings.library)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .clickable(onClickLabel = stringResource(R.string.stats_song_options)) { onSong(skipped.song.ref) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                SongCover(SongArt(skipped.song.path, skipped.song.ref.mediaStoreId), size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.insights_most_skipped, title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.insights_most_skipped_line, skipped.early, skipped.plays), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * How many times more often songs from Shuffle ([shuffled]) were skipped early than songs in order ([inOrder]); null
 * unless both have plays and Shuffle's are skipped clearly more (a fifth more or more), so the line never says the
 * reverse.
 */
fun skipRatio(shuffled: Ends, inOrder: Ends): Float? {
    val shuffledAll = shuffled.finished + shuffled.movedOn + shuffled.early
    val inOrderAll = inOrder.finished + inOrder.movedOn + inOrder.early
    if (shuffledAll == 0 || inOrderAll == 0 || inOrder.early == 0) return null
    val ratio = (shuffled.early.toFloat() / shuffledAll) / (inOrder.early.toFloat() / inOrderAll)
    return ratio.takeIf { it >= MIN_SKIP_RATIO }
}

// Moments

/**
 * Moments as postcards on the paper colour, tilted one way then the other, each with its kicker, its big figure, the
 * song's cover as the stamp and a line; they scroll sideways and straighten a little under a finger.
 */
@Composable
internal fun Moments(moments: List<Moment>, settings: Settings) {
    InsightsCard {
        CardHead(stringResource(R.string.insights_moments_title), R.drawable.ic_auto_awesome, stringResource(R.string.insights_moments_line))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 10.dp, bottom = 18.dp),
            modifier = Modifier.padding(horizontal = (-18).dp.coerceAtLeast(0.dp)),
        ) {
            itemsIndexed(moments, key = { i, moment -> "$i ${moment::class.simpleName}" }) { i, moment ->
                MomentCard(moment, if (i % 2 == 0) -1.6f else 1.4f, settings)
            }
        }
    }
}

/** One moment's postcard: what it is, its figure, its stamp (a cover, else the kicker's icon) and its line. */
@Composable
private fun MomentCard(moment: Moment, tilt: Float, settings: Settings) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val paper = if (LocalDarkTheme.current) DarkPaper else LightPaper
    val m = momentWords(moment, settings)
    Column(
        Modifier
            .width(232.dp)
            .rotate(tilt)
            .background(paper.paper, RoundedCornerShape(18.dp))
            .border(1.dp, paper.line, RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(m.icon), contentDescription = null, tint = brand.highlight, modifier = Modifier.size(15.dp))
            Text(m.kicker.uppercase(), style = CardKicker.copy(fontSize = 11.sp), color = brand.highlight)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
            Text(m.value, style = if (m.value.length > 10) display(22, 26, -0.025, weight = 700) else display(27, 30, -0.025, weight = 700), color = colors.onSurface, modifier = Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .rotate(3f)
                    .size(58.dp, 66.dp)
                    .background(colors.surfaceContainerLowest, RoundedCornerShape(6.dp))
                    .drawBehind {
                        val w = 2.dp.toPx()
                        drawRoundRect(androidx.compose.ui.graphics.lerp(brand.highlight, paper.paper, 0.55f), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(6.dp.toPx()), Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    }
                    .padding(4.dp)
                    .clearAndSetSemantics {},
            ) {
                if (m.cover != null) {
                    SongCover(SongArt(m.cover.path, m.cover.ref.mediaStoreId), Modifier.fillMaxWidth().fillMaxHeight(), size = 50.dp, shape = RoundedCornerShape(3.dp))
                } else {
                    Icon(painterResource(m.icon), contentDescription = null, tint = brand.highlight, modifier = Modifier.size(26.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).drawBehind {
            drawLine(paper.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        })
        Text(m.line, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** What a moment's postcard says: its [icon] and [kicker], its big [value], its [line] (names in bold) and its stamp's [cover]. */
private class MomentWords(@DrawableRes val icon: Int, val kicker: String, val value: String, val line: AnnotatedString, val cover: Song?)

/** A line with [bold]'s words, wherever they fall in [text], in the page's ink. */
@Composable
private fun boldIn(text: String, vararg bold: String): AnnotatedString {
    val ink = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        append(text)
        bold.forEach { word ->
            val at = text.indexOf(word)
            if (word.isNotEmpty() && at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink), at, at + word.length)
        }
    }
}

@Composable
private fun momentWords(moment: Moment, settings: Settings): MomentWords = when (moment) {
    is Moment.OnRepeat -> {
        val title = displayTitle(moment.song, settings.library)
        val day = dayText(moment.day, weekday = true)
        MomentWords(R.drawable.ic_repeat, stringResource(R.string.insights_moment_repeat), playsNote(moment.plays), boldIn(stringResource(R.string.insights_moment_repeat_line, title, day), title), moment.song)
    }
    is Moment.LongestSession -> {
        val day = dayText(moment.start.toLocalDate(), weekday = true)
        MomentWords(
            R.drawable.ic_schedule,
            stringResource(R.string.insights_moment_session),
            listenedText(moment.heardMs),
            boldIn(stringResource(R.string.insights_moment_session_line, day, timeText(moment.start)), day),
            moment.song,
        )
    }
    is Moment.LatestNight -> {
        val title = displayTitle(moment.song, settings.library)
        MomentWords(R.drawable.ic_dark_mode, stringResource(R.string.insights_moment_night), timeText(moment.at), boldIn(stringResource(R.string.insights_moment_night_line, title, dayText(moment.at.toLocalDate(), weekday = true)), title), moment.song)
    }
    is Moment.StartToFinish -> MomentWords(
        R.drawable.ic_album,
        stringResource(R.string.insights_moment_albums),
        pluralStringResource(R.plurals.insights_albums, moment.albums, formatCount(moment.albums)),
        boldIn(stringResource(R.string.insights_moment_albums_line, moment.album.title), moment.album.title),
        moment.album.songs.firstOrNull(),
    )
    is Moment.OnTheMove -> {
        val zones = moment.offsets.map { utcText(it) }
        val line = if (zones.size <= 2) stringResource(R.string.insights_moment_zones_two, zones.first(), zones.last()) else stringResource(R.string.insights_moment_zones_range, zones.minBy { it }, zones.maxBy { it })
        MomentWords(R.drawable.ic_travel_explore, stringResource(R.string.insights_moment_zones), pluralStringResource(R.plurals.insights_time_zones, moment.offsets.size, moment.offsets.size), boldIn(line, *zones.toTypedArray()), null)
    }
    is Moment.Faster -> {
        val what = stringResource(
            when {
                moment.booksMs > 0 && moment.podcastsMs > 0 -> R.string.insights_moment_faster_both
                moment.booksMs > 0 -> R.string.insights_moment_faster_books
                else -> R.string.insights_moment_faster_podcasts
            },
        )
        MomentWords(R.drawable.ic_speed, stringResource(R.string.insights_moment_faster), listenedText(moment.booksMs + moment.podcastsMs), boldIn(stringResource(R.string.insights_moment_faster_line, what), what), null)
    }
    is Moment.YearAgo -> {
        val artist = moment.song.artist.orEmpty()
        MomentWords(
            R.drawable.ic_calendar_month,
            stringResource(R.string.insights_moment_year_ago),
            displayTitle(moment.song, settings.library),
            boldIn(pluralStringResource(R.plurals.insights_moment_year_ago_line, moment.plays, artist, moment.plays, dayText(moment.day, year = true)), artist),
            moment.song,
        )
    }
    is Moment.FirstPlay -> {
        val day = dayText(moment.day, year = true)
        MomentWords(R.drawable.ic_auto_awesome, stringResource(R.string.insights_moment_first), displayTitle(moment.song, settings.library), boldIn(stringResource(R.string.insights_moment_first_line, day), day), moment.song)
    }
}

/** A time of day as the phone writes it: "3:12 am" or "03:12". */
@Composable
private fun timeText(at: LocalDateTime): String =
    android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))

/** A time zone as "UTC+3", "UTC+5:30" or "UTC". */
fun utcText(offsetMinutes: Int): String {
    if (offsetMinutes == 0) return "UTC"
    val sign = if (offsetMinutes > 0) "+" else "-"
    val h = abs(offsetMinutes) / 60
    val m = abs(offsetMinutes) % 60
    return if (m == 0) "UTC$sign$h" else String.format(Locale.ROOT, "UTC%s%d:%02d", sign, h, m)
}

// Words for periods and days

/** "This week", "This month", "This year", "All time"; a month's recap: "October". */
@Composable
internal fun periodName(period: PagePeriod): String = when {
    period.kind == ListeningPeriod.MONTH && !period.going -> monthText(java.time.YearMonth.from(period.firstDay))
    period.kind == ListeningPeriod.WEEK -> stringResource(R.string.stats_this_week)
    period.kind == ListeningPeriod.MONTH -> stringResource(R.string.stats_this_month)
    period.kind == ListeningPeriod.YEAR -> stringResource(R.string.stats_this_year)
    else -> stringResource(R.string.stats_all_time)
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

/** "Play your week so far", and the rest as [storyName]. */
@Composable
private fun playStoryText(period: PagePeriod): String = when (period.kind) {
    ListeningPeriod.WEEK -> stringResource(R.string.insights_play_week)
    ListeningPeriod.MONTH -> stringResource(R.string.insights_play_so_far, monthText(java.time.YearMonth.from(period.firstDay)))
    ListeningPeriod.YEAR -> stringResource(R.string.insights_play_so_far, period.firstDay.year.toString())
    ListeningPeriod.ALL_TIME -> stringResource(R.string.insights_play_all)
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
internal val BigNumber = display(40, 44, -0.03, weight = 700)
private val HeroTime = display(64, 64, -0.04, weight = 700)
private val HeroLine = body(13f, 18, 500)
private val HeroFigure = display(20, 24, -0.02, weight = 700)
private val HeroSmall = body(11.5f, 15, 500)
internal val AxisLabel = body(11.5f, 16, 500)
internal val FigureValue = display(22, 26, -0.02, weight = 700)
internal val FigureLabel = body(12f, 16, 500)
private val FactValue = display(22, 26, -0.02, weight = 700)
private val PillWord = body(10.5f, 16, 650).copy(letterSpacing = 0.04.em)
private val PillNumber = body(12f, 16, 650)
private val LegendValue = display(14, 18, weight = 700)
private val RankStyle = display(15, 15, weight = 700)
private val CountStyle = display(15, 20, weight = 700)
private val RowTitle = body(15f, 19, 600)

/** The smallest a counted number shrinks to with large text. */
private val SMALLEST_NUMBER = 12.sp

/** The plays column of a top song's row: room for "1,048" and the short bar under it. */
private val IntrinsicWidthOfCount = 56.dp

// The motion's numbers (the mockup's CSS)

/** A card's arrival clock is past every arrival by then (the slowest, the rings', is 1.3 s). */
private const val ARRIVED_MS = 2_000f

/** A card arrives once this share of it shows. */
private const val ARRIVE_SHARE = 0.12f
private const val FADE_MS = 520
private const val RISE_MS = 640
private val RISE_DP = 26.dp
private const val RISE_SCALE = 0.98f
private const val SWAP_MS = 420
private const val SWAP_FROM = 0.25f
private val SWAP_DP = 8.dp
private const val COUNT_MS = 900
private const val BAR_MS = 760
private const val BAR_STEP_MS = 16
private const val GHOST_DELAY_MS = 400
private const val GHOST_MS = 1_400
private const val STACK_MS = 800
private const val STACK_STEP_MS = 90
private const val RACE_MS = 900
private const val RACE_DELAY_MS = 200
private const val RACE_STEP_MS = 110
private const val POP_MS = 560
private const val POP_STEP_MS = 60
private const val BUBBLE_MS = 700
private const val BUBBLE_STEP_MS = 90
private const val FLOAT_MS = 5_000
private const val DONUT_MS = 1_100
private const val DONUT_DELAY_MS = 200
private const val DONUT_STEP_MS = 160
private const val DIAL_MS = 1_100
private const val DIAL_LINES_MS = 300
private const val DIAL_LINE_MS = 700
private const val DIAL_LINE_STEP_MS = 30
private const val FLAME_MS = 1_400
private const val RING_TURN_MS = 3_200
private const val GLOW_1_MS = 11_000
private const val GLOW_2_MS = 13_000
private const val GLOW_3_MS = 15_000

// The cards' rules

/** A day's heat steps start at these minutes (45, 90, 150); a month's at these hours (20, 32, 44). */
private val DAY_HEAT_MINUTES = listOf(45L, 90L, 150L)
private val MONTH_HEAT_MINUTES = listOf(20L * 60, 32L * 60, 44L * 60)

/** Shuffle's skips are worth a line from a fifth more than in order's. */
private const val MIN_SKIP_RATIO = 1.2f

private const val COLLAGE = 6
private const val BUBBLES = 6
private const val DECADES = 6
private const val PLAY_TOP = 25
private const val HOURS = 24
private const val DAYS_IN_WEEK = 7
private const val MONTHS_IN_YEAR = 12
private const val MINUTE = 60_000L
private const val HOUR = 3_600_000L
private val BUBBLE_MIN = 29.dp
private val BUBBLE_MAX = 60.dp
private const val TOP_SONGS_KEY = "insights-top-songs"
