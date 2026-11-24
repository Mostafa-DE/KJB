package app.lyrify.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lyrify.R
import app.lyrify.stats.ClockCard
import app.lyrify.stats.DaysCard
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.Step
import app.lyrify.stats.Steps
import app.lyrify.stats.TimeCard
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.first

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
    val drift = if (huddleMoves()) rememberInfiniteTransition(label = "glows") else null
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
            // Against the period before; the same as it (or nothing then), only the time a day.
            val change = before?.let { percentChange(totals.heardMs, it.heardMs) } ?: 0L
            if (change != 0L) {
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
                    modifier = Modifier.weight(1f),
                )
            } else {
                val days = (ChronoUnit.DAYS.between(period.firstDay, period.lastDay) + 1).coerceAtLeast(1)
                Text(stringResource(R.string.insights_a_day, heardText(totals.heardMs / days)), style = HeroLine, color = BrandLilac.copy(alpha = 0.86f))
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
                LyrifyButton(
                    when (period.kind) {
                        ListeningPeriod.WEEK -> stringResource(R.string.insights_play_week)
                        ListeningPeriod.MONTH -> stringResource(R.string.insights_play_so_far, monthText(java.time.YearMonth.from(period.firstDay)))
                        ListeningPeriod.YEAR -> stringResource(R.string.insights_play_so_far, period.firstDay.year.toString())
                        ListeningPeriod.ALL_TIME -> stringResource(R.string.insights_play_all)
                    },
                    onClick = { nav.show(FullScreen.Story(period = period.kind)) },
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_play_arrow_fill,
                )
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
    // TalkBack reads each step that has come and its time: "Mon 23 Nov 2 h 8 min, ...".
    val summary = stringResource(
        if (steps.step == Step.DAY) R.string.insights_chart_days else R.string.insights_chart_months,
        steps.heardMs.mapIndexedNotNull { i, ms -> ms?.let { stringResource(R.string.insights_step, stepText(steps, i), heardText(it)) } }.joinToString(", "),
    )
    val count = steps.heardMs.size.coerceAtLeast(1)
    val step = staggerMs(count, BAR_STEP_MS, BAR_MS, ARRIVED_MS)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = summary }) {
            val gap = minOf(3.dp.toPx(), size.width / count / 4)
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
                val height = size.height * (ms.toFloat() / top).coerceAtLeast(0.03f) * phase(t, (i * step).toInt(), BAR_MS, Motion.Rise)
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
                    Text(beforeName(period).replaceFirstChar { it.titlecase(LocalLocale.current.platformLocale) }, style = AxisLabel, color = BrandLilac.copy(alpha = 0.8f))
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
private fun stepText(steps: Steps, i: Int, short: Boolean = false): String {
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
                Counted(days.busiest.heardMs, FigureValue, MaterialTheme.colorScheme.onSurface) { AnnotatedString(heardText(it)) }
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
        FactKicker(icon, kicker, LocalBrandColors.current.highlight, iconMotion = iconMotion)
        Counted(days.toLong(), FigureValue, colors.onSurface) {
            buildAnnotatedString {
                append(formatCount(it.toInt()))
                withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight(650))) { append(" $word") }
            }
        }
        Text(line, style = FigureLabel, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun FactKicker(@DrawableRes icon: Int, text: String, color: Color, modifier: Modifier = Modifier, iconMotion: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
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
            val description = if (ms == null) name else stringResource(R.string.insights_step, name, if (ms > 0) heardText(ms) else stringResource(R.string.insights_nothing))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).then(if (ms == null) Modifier.clearAndSetSemantics {} else Modifier.clearAndSetSemantics { contentDescription = description }),
            ) {
                Text(stepText(days.heard, i, short = true), style = AxisLabel.copy(fontWeight = FontWeight.SemiBold), color = if (today) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = SMALLEST_LABEL, maxFontSize = AxisLabel.fontSize))
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
                Text(
                    when {
                        ms == null -> ""
                        ms < HOUR -> heardText(ms)
                        else -> stringResource(R.string.insights_hours_short, String.format(LocalLocale.current.platformLocale, "%.1f", ms / HOUR.toDouble()))
                    },
                    style = AxisLabel.copy(fontWeight = FontWeight.SemiBold).tabular(), color = colors.onSurfaceVariant, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = SMALLEST_LABEL, maxFontSize = AxisLabel.fontSize))
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
    val firstDayOfWeek = WeekFields.of(LocalLocale.current.platformLocale).firstDayOfWeek
    val first = days.heard.first
    val lead = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val weekdays = (0 until 7).map { firstDayOfWeek.plus(it.toLong()).getDisplayName(java.time.format.TextStyle.NARROW, LocalLocale.current.platformLocale) }
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
                    val description = stringResource(R.string.insights_step, stepText(days.heard, i), if (ms != null && ms > 0) heardText(ms) else stringResource(R.string.insights_nothing))
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
    val firstDayOfWeek = WeekFields.of(LocalLocale.current.platformLocale).firstDayOfWeek
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
                    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.NARROW, LocalLocale.current.platformLocale),
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
                Text(java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.NARROW, LocalLocale.current.platformLocale), style = AxisLabel.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
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
                    val description = stringResource(R.string.insights_step, stepText(days.heard, i), if (ms > 0) heardText(ms) else stringResource(R.string.insights_nothing))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f).aspectRatio(0.8f).background(ground, RoundedCornerShape(6.dp)).clearAndSetSemantics { contentDescription = description },
                    ) {
                        Text(
                            when {
                                ms in 1 until HOUR -> stringResource(R.string.insights_under_an_hour)
                                else -> formatCount((ms / HOUR).toInt())
                            },
                            style = AxisLabel.copy(fontSize = 10.sp, fontWeight = FontWeight(650)).tabular(), color = if (ms > 0) heatInk(ground) else colors.onSurfaceVariant, maxLines = 1)
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
    val parts = listOf(clock.morningMs, clock.afternoonMs, clock.eveningMs, clock.nightMs)
    val total = parts.sum()
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
                            Text(stringResource(R.string.insights_percent, formatCount(percentOf(parts[i], total))), style = FigureValue.copy(fontSize = 18.sp).tabular(), color = ink)
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
    val summary = stringResource(R.string.insights_dial, hourWords[peak], hours.withIndex().filter { it.value > 0 }.map { stringResource(R.string.insights_step, hourWords[it.index], heardText(it.value)) }.joinToString(", "))
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
        val under = measurer.measure(your, yourStyle)
        // The largest text shrinks to fit the ring's hole.
        scale(minOf(1f, s * 0.5f * 0.86f / maxOf(middle.size.width, under.size.width)), c) {
            drawText(middle, topLeft = Offset(c.x - middle.size.width / 2, c.y - middle.size.height / 2 - 6.dp.toPx()))
            drawText(under, topLeft = Offset(c.x - under.size.width / 2, c.y + middle.size.height / 2 - 4.dp.toPx()))
        }
    }
}

/** An hour as the phone says a short time: "10 pm", or "22" where the day has 24 hours. */
@Composable
internal fun hourLabel(hour: Int): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(LocalLocale.current.platformLocale, if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "H" else "ha")
    val at = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, 0)
    }
    return java.text.SimpleDateFormat(pattern, LocalLocale.current.platformLocale).format(at.time).lowercase(LocalLocale.current.platformLocale)
}

private val HeroTime = display(64, 64, -0.04, weight = 700)

private val HeroLine = body(13f, 18, 500)

private val HeroFigure = display(20, 24, -0.02, weight = 700)

private val HeroSmall = body(11.5f, 15, 500)

private const val GHOST_DELAY_MS = 400

private const val GHOST_MS = 1_400

private const val DIAL_MS = 1_100

private const val DIAL_LINES_MS = 300

private const val DIAL_LINE_MS = 700

private const val DIAL_LINE_STEP_MS = 30

private const val FLAME_MS = 1_400


private const val GLOW_1_MS = 11_000

private const val GLOW_2_MS = 13_000

private const val GLOW_3_MS = 15_000

// The cards' rules

/** A day's heat steps start at these minutes (45, 90, 150); a month's at these hours (20, 32, 44). */
private val DAY_HEAT_MINUTES = listOf(45L, 90L, 150L)

private val MONTH_HEAT_MINUTES = listOf(20L * 60, 32L * 60, 44L * 60)

private const val HOURS = 24

private const val DAYS_IN_WEEK = 7

private const val MONTHS_IN_YEAR = 12

private const val MINUTE = 60_000L

internal const val HOUR = 3_600_000L

/** A week's small labels shrink to fit their column with large text, to this at the least. */
private val SMALLEST_LABEL = 8.sp
