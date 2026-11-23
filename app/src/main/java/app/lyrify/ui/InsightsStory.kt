package app.lyrify.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import app.lyrify.R
import app.lyrify.library.Artist
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.settings.Settings
import app.lyrify.stats.InsightsCards
import app.lyrify.stats.InsightsPage
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.Ranked
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.TraitKind
import app.lyrify.stats.phoneCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

// Your story (the approved mockup's part 2, handoff/insights/preview-insights-v1.html): a month, the year so far or the
// period Insights shows as full screen slides. Tap the right side for the next, the left for the one before, hold to
// pause; it ends on a picture to share. With Remove animations the slides stand still and wait for a tap.

/** The story's slides, in the mockup's order. Each shows only when its period has what it needs ([storySlides]). */
enum class StorySlide { INTRO, TIME, QUIZ, TOP_SONG, ARTISTS, HOUR, NEW, FRIEND, TYPE, SHARE }

/**
 * How long [slide] shows before the next one starts: 5.6 s, the artists' race 7 s and the last slide (the picture) 9 s;
 * the quiz waits for an answer, then gives 2.6 s to see it ([answered]). Null: it waits for a tap.
 */
fun storyMs(slide: StorySlide, last: Boolean, answered: Boolean): Int? = when {
    slide == StorySlide.QUIZ -> if (answered) QUIZ_SEEN_MS else null
    last -> LAST_MS
    slide == StorySlide.ARTISTS -> RACE_SLIDE_MS
    else -> SLIDE_MS
}

/**
 * Where a story is: its [slides], the one showing ([at]), whether the user paused it ([paused]) or holds it ([held]), and
 * the quiz's answer once given ([answer]: right or not).
 */
@Stable
class StoryState(val slides: List<StorySlide>) {
    var at by mutableIntStateOf(0)
        private set
    var paused by mutableStateOf(false)
    var held by mutableStateOf(false)
    var answer by mutableStateOf<Boolean?>(null)

    val slide: StorySlide get() = slides[at]

    /** The next slide; false at the last one, where the story closes. */
    fun next(): Boolean {
        if (at >= slides.lastIndex) return false
        at++
        return true
    }

    /** The slide before, or the first again. */
    fun back() {
        at = (at - 1).coerceAtLeast(0)
    }

    /** Whether the bar runs: not paused or held, and not waiting for the quiz's answer. */
    fun runs(): Boolean = !paused && !held && storyMs(slide, at == slides.lastIndex, answer != null) != null
}

/**
 * The story's frame over [slides]' content: the progress bars, KJB's mark and [title], Pause and Close, the two halves to
 * tap (Back and Next, each a button for TalkBack) and holding to pause, each slide fading into the next (360 ms). The bar
 * of the slide showing fills as it runs and the next slide follows; with Remove animations it stands full and the story
 * waits for a tap. [content] draws a slide, its elements arriving by [rise] and [zoom] on the slide's own clock.
 */
@Composable
internal fun StoryFrame(title: String, state: StoryState, onClose: () -> Unit, content: @Composable (StorySlide) -> Unit) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    val answered = state.answer != null
    // A new slide, or the quiz answered: its bar starts again, then runs while nothing holds it.
    LaunchedEffect(state.at, answered) { fill.snapTo(if (reduced) 1f else 0f) }
    val runs = state.runs()
    LaunchedEffect(state.at, answered, runs, reduced) {
        val ms = storyMs(state.slide, state.at == state.slides.lastIndex, answered) ?: return@LaunchedEffect
        if (reduced || !runs) return@LaunchedEffect
        fill.animateTo(1f, tween(((1f - fill.value) * ms).toInt(), easing = LinearEasing))
        if (!state.next()) onClose()
    }
    val next = { if (!state.next()) onClose() }
    val slideOf = stringResource(R.string.insights_story_slide, state.at + 1, state.slides.size)
    Box(Modifier.fillMaxSize().background(HuddleCardShade)) {
        AnimatedContent(
            state.at,
            transitionSpec = { fadeIn(tween(if (reduced) Motion.REDUCED_MS else SLIDE_FADE_MS)) togetherWith fadeOut(tween(if (reduced) Motion.REDUCED_MS else SLIDE_FADE_MS)) },
            label = "slide",
        ) { at ->
            SlideClock { content(state.slides[at]) }
        }
        // The two halves to tap, under the bars and buttons: back a third, next the rest; a press held pauses.
        Row(Modifier.fillMaxSize().padding(top = TAP_TOP)) {
            listOf(true, false).forEach { back ->
                val label = stringResource(if (back) R.string.insights_story_back else R.string.insights_story_next)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(if (back) BACK_SHARE else 1f - BACK_SHARE)
                        .semantics {
                            contentDescription = label
                            onClick(label) {
                                if (back) state.back() else next()
                                true
                            }
                        }
                        .pointerInput(state) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val hold = scope.launch {
                                    delay(HOLD_MS)
                                    state.held = true
                                }
                                val up = waitForUpOrCancellation()
                                hold.cancel()
                                if (state.held) {
                                    state.held = false
                                } else if (up != null && up.uptimeMillis - down.uptimeMillis < TAP_MS) {
                                    if (back) state.back() else next()
                                }
                            }
                        },
                )
            }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp).height(3.dp).clearAndSetSemantics {}) {
                state.slides.indices.forEach { i ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().drawBehind {
                            val corner = CornerRadius(size.height / 2)
                            drawRoundRect(BrandLilac.copy(alpha = 0.3f), cornerRadius = corner)
                            val done = when {
                                i < state.at -> 1f
                                i > state.at -> 0f
                                else -> fill.value
                            }
                            if (done > 0f) drawRoundRect(BrandLilac, size = Size(size.width * done, size.height), cornerRadius = corner)
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 5.dp)) {
                Image(
                    painterResource(R.drawable.kjb_default_cover),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, BrandBlackBorder, RoundedCornerShape(9.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = StoryWho, color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ChromeIconButton(
                    if (state.paused) R.drawable.ic_play_arrow_fill else R.drawable.ic_pause_fill,
                    stringResource(if (state.paused) R.string.insights_story_play else R.string.insights_story_pause),
                    { state.paused = !state.paused },
                    glass = false,
                    tint = BrandLilac,
                )
                ChromeIconButton(R.drawable.ic_close, stringResource(R.string.insights_close), onClose, glass = false, tint = BrandLilac)
            }
            // TalkBack hears where it is as the slides change.
            Text(slideOf, color = BrandLilac, modifier = Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/**
 * The slide showing arrives as it comes: its clock ([LocalArrivalClock], read by [rise] and [zoom]) runs from 0 and its
 * numbers count up ([Counted]); all in place at once with reduced motion.
 */
@Composable
private fun SlideClock(content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val clock = remember { Animatable(if (reduced) SLIDE_CLOCK_MS else 0f) }
    var came by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) {
        came = true
        if (!reduced) clock.animateTo(SLIDE_CLOCK_MS, tween(SLIDE_CLOCK_MS.toInt(), easing = LinearEasing))
    }
    CompositionLocalProvider(LocalArrival provides Arrival(came, null), LocalArrivalClock provides ArrivalClock { clock.value }, content = content)
}

/**
 * Your story as a full screen view: [view]'s month, else the period it names, read as Insights read it. Its slides are
 * the ones the period has enough for ([storySlides]); with nothing to show it closes.
 */
@Composable
fun StoryView(view: FullScreen.Story) {
    val nav = LocalNavigator.current
    val month = remember(view) { view.month?.let { runCatching { YearMonth.parse(it) }.getOrNull() } }
    val page = rememberStats(view) { context ->
        val stats = StatsGraph.stats(context)
        if (month != null) stats.page(month, System.currentTimeMillis(), phoneCalendar()) else stats.page(view.period ?: ListeningPeriod.MONTH, System.currentTimeMillis(), phoneCalendar())
    }
    val settings = rememberSettings()
    val close = { nav.close(view) }
    // Its slides as the story opened: plays written meanwhile change the numbers, never the slides.
    var state by remember { mutableStateOf<StoryState?>(null) }
    if (state == null && page != null) state = StoryState(storySlides(page.cards))
    Box(Modifier.fillMaxSize().background(HuddleCardShade)) {
        val shown = state ?: return@Box
        page ?: return@Box
        StoryFrame(storyName(page.period), shown, close) { slide -> Slide(slide, page, settings, shown) }
    }
}

/** The slides a page has enough for, in the mockup's order: the intro, then each that has its card, then the picture. */
fun storySlides(cards: InsightsCards): List<StorySlide> = buildList {
    add(StorySlide.INTRO)
    if (cards.time != null) add(StorySlide.TIME)
    val songs = cards.songs?.rows.orEmpty()
    if (songs.size >= QUIZ_SONGS) add(StorySlide.QUIZ)
    if (songs.isNotEmpty()) add(StorySlide.TOP_SONG)
    if (cards.artists?.rows.orEmpty().size >= 2) add(StorySlide.ARTISTS)
    if (cards.clock != null) add(StorySlide.HOUR)
    if (cards.found?.artists.orEmpty().isNotEmpty()) add(StorySlide.NEW)
    if (cards.friends?.matches.orEmpty().isNotEmpty()) add(StorySlide.FRIEND)
    if (cards.traits.isNotEmpty()) add(StorySlide.TYPE)
    if (cards.time != null) add(StorySlide.SHARE)
}

/** The order the quiz shows the top three songs in: the second, the first, the third (the mockup's). */
fun <T> quizOrder(songs: List<T>): List<T> = listOf(songs[1], songs[0], songs[2])

/** Something on a slide arriving (the mockup's .r): from 26 dp lower and faded, over 700 ms ease-rise after [delay]. */
private fun Modifier.rise(clock: ArrivalClock, delay: Int = 0): Modifier = graphicsLayer {
    val t = phase(clock.ms(), delay, RISE_MS, Motion.Rise)
    alpha = phase(clock.ms(), delay, RISE_MS)
    translationY = (1f - t) * RISE_DP.toPx()
}

/** Something on a slide zooming in (the mockup's .zoom): from 40%, turned 8 degrees back and faded, 900 ms ease-pop after [delay]. */
private fun Modifier.zoom(clock: ArrivalClock, delay: Int = 0): Modifier = graphicsLayer {
    val t = phase(clock.ms(), delay, ZOOM_MS, Motion.Pop)
    alpha = phase(clock.ms(), delay, ZOOM_MS)
    scaleX = 0.4f + 0.6f * t
    scaleY = 0.4f + 0.6f * t
    rotationZ = -8f * (1f - t)
}

/** One slide on its ground, its words in lilac, its parts arriving on its clock. */
@Composable
private fun Slide(slide: StorySlide, page: InsightsPage, settings: Settings, state: StoryState) {
    val clock = LocalArrivalClock.current
    val cards = page.cards
    val period = page.period
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { slideGround(slide) }
            .systemBarsPadding(),
    ) {
        when (slide) {
            StorySlide.TOP_SONG -> cards.songs?.rows?.firstOrNull()?.let { FriendGlow(it.top.item, HuddleCardGround, 34.dp, Modifier.graphicsLayer { alpha = 0.9f }) }
            StorySlide.ARTISTS -> Beams()
            StorySlide.HOUR -> Stars()
            else -> Unit
        }
        if (slide == StorySlide.TOP_SONG) Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(HuddleCardShade.copy(alpha = 0.35f), HuddleCardShade.copy(alpha = 0.75f)))))
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, top = 92.dp, bottom = 72.dp),
        ) {
            when (slide) {
                StorySlide.INTRO -> {
                    val covers = cards.songs?.rows.orEmpty().take(3).map { it.cover }
                    if (covers.isNotEmpty()) FloatingCovers(covers)
                    Text(stringResource(R.string.insights_story_kicker, storyLabel(period)).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock))
                    Text(storyName(period), style = SlideTitleXl, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 120))
                    Text(stringResource(R.string.insights_story_intro), style = SlideLine, color = BrandLilac.copy(alpha = 0.9f), modifier = Modifier.padding(top = 12.dp).rise(clock, 260))
                }
                StorySlide.TIME -> cards.time?.let { time ->
                    val unit = SpanStyle(fontSize = 30.sp, letterSpacing = (-0.01).sp, color = BrandLilac.copy(alpha = 0.85f))
                    val days = (java.time.temporal.ChronoUnit.DAYS.between(period.firstDay, period.lastDay) + 1).coerceAtLeast(1)
                    val before = time.before
                    Text(stringResource(R.string.insights_story_listened).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock))
                    Counted(time.totals.heardMs, SlideHuge, BrandLilac, Modifier.padding(top = 6.dp).rise(clock, 100)) { bigTime(it, unit) }
                    Text(
                        if (before == null || period.kind == ListeningPeriod.ALL_TIME) {
                            stringResource(R.string.insights_story_a_day, listenedText(time.totals.heardMs / days))
                        } else {
                            val change = percentChange(time.totals.heardMs, before.heardMs)
                            stringResource(
                                if (change < 0) R.string.insights_story_a_day_less else R.string.insights_story_a_day_more,
                                listenedText(time.totals.heardMs / days),
                                formatCount(abs(change).toInt()),
                                beforeName(period),
                            )
                        },
                        style = SlideLine,
                        color = BrandLilac.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 12.dp).rise(clock, 260),
                    )
                    val daily = time.heard.heardMs.map { it ?: 0L }
                    val top = daily.maxOrNull()?.takeIf { it > 0 } ?: 1L
                    Canvas(Modifier.padding(top = 24.dp).fillMaxWidth().height(70.dp).clearAndSetSemantics {}) {
                        val gap = 3.dp.toPx()
                        val width = (size.width - gap * (daily.size - 1)) / daily.size.coerceAtLeast(1)
                        daily.forEachIndexed { i, ms ->
                            val h = size.height * (ms.toFloat() / top).coerceAtLeast(0.04f) * phase(clock.ms(), MINI_DELAY_MS + i * MINI_STEP_MS, MINI_MS, Motion.Rise)
                            drawRoundRect(BrandLilac.copy(alpha = 0.85f), Offset(i * (width + gap), size.height - h), Size(width, h), CornerRadius(minOf(3.dp.toPx(), width / 2)))
                        }
                    }
                }
                StorySlide.QUIZ -> cards.songs?.let { songs -> Quiz(songs.rows, settings, state) }
                StorySlide.TOP_SONG -> cards.songs?.rows?.firstOrNull()?.let { first ->
                    val title = displayTitle(first.top.item, settings.library)
                    val wobble = if (huddleMoves()) rememberInfiniteTransition(label = "wobble").animateFloat(0f, 1f, infiniteRepeatable(tween(WOBBLE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "wob") else null
                    SongCover(
                        SongArt(first.cover.path, first.cover.ref.mediaStoreId),
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 22.dp)
                            .zoom(clock)
                            .graphicsLayer {
                                val w = wobble?.value ?: 0f
                                rotationZ = -4f + 7f * w
                                scaleX = 1f + 0.03f * w
                                scaleY = 1f + 0.03f * w
                            },
                        size = 220.dp,
                        shape = RoundedCornerShape(28.dp),
                    )
                    Text(stringResource(R.string.insights_top_song).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock, 200))
                    Text(title, style = SlideTitle, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 280))
                    val day = cards.songs.firstsDay?.takeIf { it.song == first.top.item }
                    Text(
                        listOfNotNull(
                            stringResource(R.string.stats_row_line, first.top.item.artist.orEmpty(), playsNote(first.top.plays)).takeIf { first.top.item.artist != null } ?: playsNote(first.top.plays),
                            day?.let { pluralStringResource(R.plurals.insights_story_repeat, it.plays, it.plays, dayText(it.day, weekday = true)) },
                        ).joinToString(". "),
                        style = SlideLine,
                        color = BrandLilac.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 12.dp).rise(clock, 360),
                    )
                }
                StorySlide.ARTISTS -> cards.artists?.let { artists -> ArtistsRace(artists.rows.take(RACE), clock) }
                StorySlide.HOUR -> cards.clock?.let { hours ->
                    val owl = cards.traits.firstOrNull { it.kind == TraitKind.NIGHT_OWL }
                    val hour = hourLabel(hours.peak)
                    Dial(
                        hours.hours,
                        hours.peak,
                        BrandLilac.copy(alpha = 0.06f),
                        BrandLilac.copy(alpha = 0.14f),
                        DarkInsights.series[0],
                        HuddleGold,
                        BrandLilac.copy(alpha = 0.75f),
                        BrandLilac,
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp).size(230.dp).zoom(clock),
                    )
                    Text(stringResource(if (owl != null) R.string.insights_story_you_are else R.string.insights_your_hour_title).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock, 200))
                    Text(if (owl != null) traitName(TraitKind.NIGHT_OWL) else hour, style = SlideTitle, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 280))
                    Text(
                        if (owl != null) stringResource(R.string.insights_story_owl, owl.percent, hour) else stringResource(R.string.insights_story_hour, hour),
                        style = SlideLine,
                        color = BrandLilac.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 12.dp).rise(clock, 360),
                    )
                }
                StorySlide.NEW -> cards.found?.let { found ->
                    val kept = found.artists.count { it.kept }
                    Text(stringResource(R.string.insights_found_title).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock))
                    Text(pluralStringResource(R.plurals.insights_story_new_artists, found.artists.size, formatCount(found.artists.size)), style = SlideTitle, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 100))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 22.dp)) {
                        found.artists.take(3).forEachIndexed { i, artist ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                                Box(Modifier.size(84.dp).graphicsLayer {
                                    val pop = phase(clock.ms(), FACE_DELAY_MS + i * FACE_STEP_MS, FACE_MS, Motion.Pop)
                                    alpha = phase(clock.ms(), FACE_DELAY_MS + i * FACE_STEP_MS, FACE_MS)
                                    scaleX = 0.6f + 0.4f * pop
                                    scaleY = 0.6f + 0.4f * pop
                                    rotationZ = -6f * (1f - pop)
                                }) {
                                    SongCover(artist.artist.songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = 84.dp, shape = CircleShape)
                                    if (artist.kept) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(30.dp).border(3.dp, StoryTeal[2], CircleShape).padding(2.dp).background(DarkBrandColors.suggestion, CircleShape),
                                        ) {
                                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = StoryTeal[2], modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                                Text(artist.artist.name, style = body(13f, 17, 600), color = BrandLilac, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (kept > 0 && period.kind != ListeningPeriod.ALL_TIME) {
                        Text(pluralStringResource(R.plurals.insights_story_kept, kept, kept), style = SlideLine, color = BrandLilac.copy(alpha = 0.9f), modifier = Modifier.padding(top = 12.dp).rise(clock, 500))
                    }
                }
                StorySlide.FRIEND -> cards.friends?.let { friends ->
                    val closest = friends.matches.first()
                    Box(Modifier.align(Alignment.CenterHorizontally)) {
                        JoinedFaces(stringResource(R.string.huddle_you), closest.name, friendColor(closest.friend), size = 108.dp, gap = StoryCoral[2])
                    }
                    Text(stringResource(R.string.insights_most_like_you).uppercase(), style = SlideKicker, color = InsightsBarNow[1], modifier = Modifier.padding(top = 12.dp).rise(clock))
                    Text(stringResource(R.string.insights_story_alike, closest.name, closest.percent), style = SlideTitle, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 100))
                    friends.both.firstOrNull()?.let { artist ->
                        Text(stringResource(R.string.insights_story_both, artist.name), style = SlideLine, color = BrandLilac.copy(alpha = 0.9f), modifier = Modifier.padding(top = 12.dp).rise(clock, 220))
                    }
                }
                StorySlide.TYPE -> {
                    val names = cards.traits.map { traitName(it.kind) }
                    Text(stringResource(R.string.insights_story_type).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock))
                    Text(names.mapIndexed { i, name -> if (i == 0) name else name.lowercase(Locale.getDefault()) }.joinToString(", "), style = SlideTitle, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 100))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 20.dp)) {
                        cards.traits.forEachIndexed { i, trait ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().rise(clock, BADGE_DELAY_MS + i * BADGE_STEP_MS).background(BrandLilac.copy(alpha = 0.14f), RoundedCornerShape(20.dp)).padding(10.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp).background(Brush.radialGradient(MedalGold), CircleShape)) {
                                    Icon(painterResource(traitIcon(trait.kind)), contentDescription = null, tint = OnHuddleGold, modifier = Modifier.size(26.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(names[i], style = display(18, 22, weight = 700), color = BrandLilac)
                                    Text(traitWhy(trait), style = body(13.5f, 18), color = BrandLilac.copy(alpha = 0.88f))
                                }
                            }
                        }
                    }
                }
                StorySlide.SHARE -> SharePicture(Picture.SUMMARY, page, settings, Modifier.align(Alignment.CenterHorizontally).zoom(clock), withButton = true)
            }
        }
    }
}

/** The ground of [slide]: the mockup's gradients, behind lilac words. */
private fun DrawScope.slideGround(slide: StorySlide) {
    when (slide) {
        StorySlide.INTRO, StorySlide.SHARE -> introGround()
        StorySlide.TIME, StorySlide.TYPE -> drawRect(Brush.linearGradient(HuddleTicket, Offset(size.width * 0.2f, 0f), Offset(size.width * 0.8f, size.height)))
        StorySlide.QUIZ, StorySlide.HOUR -> {
            drawRect(Brush.verticalGradient(StoryNight.drop(1)))
            drawRect(Brush.radialGradient(listOf(StoryNight[0], Color.Transparent), Offset(size.width * 0.7f, size.height * 0.2f), size.maxDimension * 0.6f))
        }
        StorySlide.TOP_SONG -> drawRect(HuddleCardGround)
        StorySlide.ARTISTS -> drawRect(Brush.verticalGradient(StoryStage))
        StorySlide.NEW -> {
            drawRect(StoryTeal[2])
            drawRect(Brush.radialGradient(listOf(StoryTeal[0], Color.Transparent), Offset(size.width * 0.2f, size.height * 0.15f), size.maxDimension * 0.6f))
            drawRect(Brush.radialGradient(listOf(StoryTeal[1], Color.Transparent), Offset(size.width * 0.9f, size.height * 0.9f), size.maxDimension * 0.55f))
        }
        StorySlide.FRIEND -> {
            drawRect(StoryCoral[2])
            drawRect(Brush.radialGradient(listOf(StoryCoral[0], Color.Transparent), Offset(size.width * 0.8f, size.height * 0.1f), size.maxDimension * 0.5f))
            drawRect(Brush.radialGradient(listOf(StoryCoral[1], Color.Transparent), Offset(size.width * 0.1f, size.height * 0.9f), size.maxDimension * 0.55f))
        }
    }
}

/** The intro's and the hero's ground: coral, violet and teal glowing on the violet night. */
private fun DrawScope.introGround() {
    drawRect(HuddleEmptyGround)
    drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[0], Color.Transparent), Offset(size.width * 0.15f, size.height * 0.1f), size.maxDimension * 0.55f))
    drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[1], Color.Transparent), Offset(size.width * 0.95f, size.height * 0.3f), size.maxDimension * 0.55f))
    drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[2], Color.Transparent), Offset(size.width * 0.4f, size.height * 1.05f), size.maxDimension * 0.6f))
}

/** The intro's three top covers floating, each bobbing on its own beat (the mockup's floaty). */
@Composable
private fun FloatingCovers(covers: List<Song>) {
    val bob = if (huddleMoves()) rememberInfiniteTransition(label = "floaty") else null
    val beats = listOf(BOB_A_MS, BOB_B_MS, BOB_C_MS).map { ms -> bob?.animateFloat(0f, 1f, infiniteRepeatable(tween(ms / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob") }
    BoxWithConstraints(Modifier.fillMaxWidth().height(210.dp).padding(bottom = 18.dp).clearAndSetSemantics {}) {
        val spots = listOf(Triple(maxWidth * 0.06f, 30.dp, -10f), Triple(maxWidth * 0.32f, 0.dp, 0f), Triple(maxWidth * 0.96f - 128.dp, 46.dp, 9f))
        covers.forEachIndexed { i, song ->
            val (x, y, tilt) = spots[i]
            val big = i == 1
            SongCover(
                SongArt(song.path, song.ref.mediaStoreId),
                Modifier.offset(x, y).zIndex(if (big) 1f else 0f).graphicsLayer {
                    val b = beats[i]?.value ?: 0f
                    rotationZ = tilt + if (i == 1) 0f else (if (tilt < 0) -3f else 3f) * b
                    translationY = -(if (big) 14 else if (i == 0) 10 else 8).dp.toPx() * b
                },
                size = if (big) 148.dp else 128.dp,
                shape = RoundedCornerShape(22.dp),
            )
        }
    }
}

/**
 * The quiz: three covers, tap the one you think is your top song. Right, it lights teal and confetti falls; wrong, it
 * shakes and the right one lights. The story waits for the answer, then goes on after 2.6 s.
 */
@Composable
private fun Quiz(rows: List<Ranked<Song>>, settings: Settings, state: StoryState) {
    val clock = LocalArrivalClock.current
    val reduced = LocalReducedMotion.current
    val top = rows.first().top.item
    var picked by remember { mutableStateOf<Song?>(null) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(picked) {
        val wrong = picked?.let { it != top } ?: return@LaunchedEffect
        if (wrong && !reduced) shake.animateTo(0f, keyframes {
            durationMillis = SHAKE_MS
            -6f at SHAKE_MS * 20 / 100
            6f at SHAKE_MS * 40 / 100
            -6f at SHAKE_MS * 60 / 100
            6f at SHAKE_MS * 80 / 100
        })
    }
    Box {
        Column {
            Text(stringResource(R.string.insights_quiz_kicker).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock))
            Text(stringResource(R.string.insights_quiz_title), style = SlideTitle, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 100))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 22.dp)) {
                quizOrder(rows.take(QUIZ_SONGS)).forEachIndexed { i, row ->
                    val song = row.top.item
                    val right = song == top
                    val answered = picked != null
                    val border by animateColorAsState(
                        when {
                            answered && right -> DarkBrandColors.suggestion
                            answered -> BrandLilac.copy(alpha = 0.12f)
                            else -> BrandLilac.copy(alpha = 0.22f)
                        },
                        motion(tween(Motion.SETTLE_MS), reduced),
                        label = "quiz border",
                    )
                    val grow by animateFloatAsState(if (answered && right) 1.03f else 1f, motion(tween(Motion.SETTLE_MS, easing = Motion.Pop), reduced), label = "quiz grow")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .rise(clock, QUIZ_DELAY_MS + i * QUIZ_STEP_MS)
                            .graphicsLayer {
                                scaleX = grow
                                scaleY = grow
                                alpha = if (answered && !right) 0.55f else 1f
                                translationX = if (picked == song) shake.value.dp.toPx() else 0f
                            }
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (answered && right) DarkBrandColors.suggestion.copy(alpha = 0.2f) else BrandLilac.copy(alpha = 0.08f))
                            .border(2.dp, border, RoundedCornerShape(20.dp))
                            .clickable(enabled = !answered) {
                                picked = song
                                state.answer = right
                            }
                            .heightIn(min = 72.dp)
                            .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), size = 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(displayTitle(song, settings.library), style = MaterialTheme.typography.titleSmall, color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            song.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = BrandLilac.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            val title = displayTitle(top, settings.library)
            Text(
                when {
                    picked == null -> ""
                    picked == top -> stringResource(R.string.insights_quiz_right, title)
                    else -> stringResource(R.string.insights_quiz_wrong, title)
                },
                style = display(18, 24, weight = 650),
                color = BrandLilac,
                modifier = Modifier.padding(top = 14.dp).heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (picked == top && !reduced) Confetti(Modifier.matchParentSize())
    }
}

/** Confetti falling once over the quiz (the mockup's: 44 bits of the story's colours, 2.6 s), turning as it falls. */
@Composable
private fun Confetti(modifier: Modifier) {
    val fall = remember { Animatable(0f) }
    LaunchedEffect(Unit) { fall.animateTo(1f, tween(FALL_MS + CONFETTI_SPREAD_MS, easing = LinearEasing)) }
    val bits = listOf(HuddleGold, DarkBrandColors.highlight, DarkBrandColors.suggestion, DarkInsights.series[0], DarkInsights.series[4], BrandLilac)
    Canvas(modifier.clearAndSetSemantics {}) {
        val total = (FALL_MS + CONFETTI_SPREAD_MS).toFloat()
        repeat(CONFETTI) { k ->
            val t = phase(fall.value * total, (k % 11) * 50, FALL_MS, ConfettiFall)
            if (t >= 1f) return@repeat
            val x = size.width * ((k * 23) % 100) / 100f
            val y = -20.dp.toPx() + t * (size.height + 40.dp.toPx())
            rotate(k * 37f + t * 620f, Offset(x, y)) {
                val color = bits[k % bits.size].copy(alpha = 1f - 0.8f * t)
                if (k % 3 == 0) drawCircle(color, 5.dp.toPx(), Offset(x, y)) else drawRect(color, Offset(x - 4.dp.toPx(), y - 6.5.dp.toPx()), Size(8.dp.toPx(), 13.dp.toPx()))
            }
        }
    }
}

/**
 * The top artists' race on a stage: each bar racing out (number 2 leading for a moment), the winner's glowing gold, over
 * a coloured floor; the name and plays of each over its bar.
 */
@Composable
private fun ArtistsRace(rows: List<Ranked<Artist>>, clock: ArrivalClock) {
    val first = rows.first().top
    Text(stringResource(R.string.insights_story_artists).uppercase(), style = SlideKicker, color = StoryKicker, modifier = Modifier.rise(clock))
    Text(stringResource(R.string.insights_story_wins, first.item.name), style = SlideTitle, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).rise(clock, 100))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 20.dp)) {
        rows.forEachIndexed { i, row ->
            val share = row.top.plays.toFloat() / first.plays.coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), size = 44.dp, shape = CircleShape)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(row.top.item.name, style = body(16f, 20, 650), color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(formatCount(row.top.plays), style = display(16, 20, weight = 700).tabular(), color = BrandLilac)
                    }
                    Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(BrandLilac.copy(alpha = 0.12f))) {
                        val t = phase(clock.ms(), RACE_DELAY_MS + i * RACE_STEP_MS, RACE_MS, RaceEasing)
                        // Out to its lead first, then back to its true length (the mockup's racex).
                        val lead = RACE_LEADS[i % RACE_LEADS.size]
                        val width = if (t < RACE_TURN) t / RACE_TURN * lead else lead + (share - lead) * (t - RACE_TURN) / (1f - RACE_TURN)
                        val colors = if (i == 0) InsightsBarNow else InsightsBars
                        if (i == 0) drawRoundRect(HuddleGold.copy(alpha = 0.35f), size = Size(size.width * width, size.height), cornerRadius = CornerRadius(7.dp.toPx()))
                        drawRoundRect(Brush.horizontalGradient(colors, 0f, size.width * width.coerceAtLeast(0.01f)), size = Size(size.width * width, size.height), cornerRadius = CornerRadius(7.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** The stage's two lights, swaying (the mockup's beams). */
@Composable
private fun BoxScope.Beams() {
    val sway = if (huddleMoves()) rememberInfiniteTransition(label = "beams").animateFloat(0f, 1f, infiniteRepeatable(tween(SWAY_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway") else null
    Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
        val s = sway?.value ?: 0.5f
        listOf(-1, 1).forEach { side ->
            val top = Offset(if (side < 0) size.width * 0.12f else size.width * 0.88f, -30.dp.toPx())
            rotate(side * (24f - 34f * s), top) {
                val path = Path().apply {
                    moveTo(top.x - 9.dp.toPx(), top.y)
                    lineTo(top.x + 9.dp.toPx(), top.y)
                    lineTo(top.x + 75.dp.toPx(), top.y + 420.dp.toPx())
                    lineTo(top.x - 75.dp.toPx(), top.y + 420.dp.toPx())
                    close()
                }
                drawPath(path, Brush.verticalGradient(listOf(StoryKicker.copy(alpha = 0.42f), Color.Transparent), top.y, top.y + 420.dp.toPx()))
            }
        }
        drawRect(Brush.horizontalGradient(StoryFloor), Offset(0f, size.height - 10.dp.toPx()), Size(size.width, 10.dp.toPx()))
    }
}

/** The night's stars, twinkling (the mockup's 28). */
@Composable
private fun BoxScope.Stars() {
    val twinkle = if (huddleMoves()) rememberInfiniteTransition(label = "stars").animateFloat(0f, 1f, infiniteRepeatable(tween(TWINKLE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "twinkle") else null
    Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
        repeat(STARS) { k ->
            val beat = twinkle?.value?.let { ((it + (k % 7) * 0.14f) % 1f) } ?: 0.5f
            drawCircle(BrandLilac.copy(alpha = 0.15f + 0.75f * beat), 1.5.dp.toPx(), Offset(size.width * ((k * 37) % 100) / 100f, size.height * ((k * 53) % 70) / 100f))
        }
    }
}

/** The kicker of a story: "November so far", "Week so far", "October". */
@Composable
private fun storyLabel(period: PagePeriod): String = when {
    period.kind == ListeningPeriod.MONTH && !period.going -> monthText(YearMonth.from(period.firstDay))
    period.kind == ListeningPeriod.MONTH -> stringResource(R.string.insights_ring_so_far, monthText(YearMonth.from(period.firstDay)))
    period.kind == ListeningPeriod.YEAR -> stringResource(R.string.insights_ring_so_far, period.firstDay.year.toString())
    else -> periodName(period)
}

// The pictures to share

/** The pictures Insights can share: the summary, the top albums' collage, and the night owl's record. */
enum class Picture { SUMMARY, ALBUMS, CLOCK }

/** The pictures [cards] has enough for: the summary with its time, the collage with 6 albums, the record for a night owl. */
fun picturesOf(cards: InsightsCards): List<Picture> = buildList {
    if (cards.time != null) add(Picture.SUMMARY)
    if (cards.albums?.rows.orEmpty().size >= COLLAGE) add(Picture.ALBUMS)
    if (cards.clock != null && cards.traits.any { it.kind == TraitKind.NIGHT_OWL }) add(Picture.CLOCK)
}

/**
 * The pictures to share, side by side ([picturesOf]), [first] the one asked for, each with Share under it: made on the
 * phone and sent through its share sheet as a PNG, exactly as drawn here. Covers go in them (the user's call, the
 * approved mockup); search words never do.
 */
@Composable
internal fun PictureSheet(first: Picture, page: InsightsPage, settings: Settings, onDismiss: () -> Unit) {
    val pictures = picturesOf(page.cards).let { all -> listOf(first).filter { it in all } + (all - first) }
    LyrifySheet(onDismiss = onDismiss, spacing = 12.dp) {
        Text(stringResource(R.string.insights_share), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(pictures, key = { it.name }) { picture -> SharePicture(picture, page, settings, withButton = true) }
        }
    }
}

/**
 * One picture to share (the mockup's 9:16 cards): KJB's mark and the story's name over its ground, its figures, and
 * "Made with KJB"; drawn on screen as it is shared. [withButton]: Share under it.
 */
@Composable
internal fun SharePicture(picture: Picture, page: InsightsPage, settings: Settings, modifier: Modifier = Modifier, withButton: Boolean = false) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val cards = page.cards
    val period = page.period
    val title = storyName(period)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier) {
        Column(
            Modifier
                .width(PICTURE_WIDTH)
                .aspectRatio(9f / 16f)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .clip(RoundedCornerShape(26.dp))
                .drawBehind {
                    when (picture) {
                        Picture.SUMMARY -> introGround()
                        Picture.ALBUMS -> drawRect(Brush.linearGradient(HuddleTicket, Offset(size.width * 0.2f, 0f), Offset(size.width * 0.8f, size.height)))
                        Picture.CLOCK -> {
                            drawRect(Brush.verticalGradient(StoryNight.drop(1)))
                            drawRect(Brush.radialGradient(listOf(StoryNight[0], Color.Transparent), Offset(size.width * 0.7f, size.height * 0.2f), size.maxDimension * 0.6f))
                        }
                    }
                }
                .semantics(mergeDescendants = true) {}
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.kjb_default_cover), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).border(1.dp, BrandBlackBorder, RoundedCornerShape(7.dp)))
                Text(storyLabel(period).uppercase(), style = CardKicker.copy(fontSize = 11.sp, lineHeight = 14.sp), color = StoryKicker, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (picture) {
                Picture.SUMMARY -> cards.time?.let { time ->
                    Text(title, style = display(30, 31, -0.03, weight = 700), color = BrandLilac, modifier = Modifier.padding(top = 12.dp))
                    Text(bigTime(time.totals.heardMs, SpanStyle(fontSize = 16.sp, color = BrandLilac.copy(alpha = 0.85f))), style = display(38, 38, -0.035, weight = 700).tabular(), color = BrandLilac, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
                    Text(
                        stringResource(R.string.stats_row_line, playsNote(time.totals.plays), pluralStringResource(R.plurals.insights_songs, time.totals.songs, formatCount(time.totals.songs))),
                        style = body(12f, 16, 500),
                        color = BrandLilac.copy(alpha = 0.82f),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 14.dp)) {
                        cards.songs?.rows.orEmpty().take(TOP_SHOWN).forEachIndexed { i, row ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text((i + 1).toString(), style = display(13, 13, weight = 700), color = StoryKicker, modifier = Modifier.width(14.dp))
                                SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), size = 32.dp, shape = RoundedCornerShape(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(displayTitle(row.top.item, settings.library), style = body(12.5f, 15, 600), color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    row.top.item.artist?.let { Text(it, style = body(11f, 14), color = BrandLilac.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                        }
                    }
                }
                Picture.ALBUMS -> cards.albums?.let { albums ->
                    Text(stringResource(R.string.stats_top_albums), style = display(30, 31, -0.03, weight = 700), color = BrandLilac, modifier = Modifier.padding(top = 12.dp))
                    BoxWithConstraints(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                        val gap = 4.dp
                        val cell = (maxWidth - gap * 2) / 3
                        albums.rows.take(COLLAGE).forEachIndexed { i, row ->
                            val x = when (i) {
                                0, 3 -> 0.dp
                                4 -> cell + gap
                                else -> (cell + gap) * 2
                            }
                            val y = when (i) {
                                0, 1 -> 0.dp
                                2 -> cell + gap
                                else -> (cell + gap) * 2
                            }
                            SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), Modifier.offset(x, y), size = if (i == 0) cell * 2 + gap else cell, shape = RoundedCornerShape(if (i == 0) 12.dp else 8.dp))
                        }
                        Spacer(Modifier.height(cell * 3 + gap * 2))
                    }
                    val top = albums.rows.first().top.item
                    Text(
                        stringResource(R.string.insights_picture_album_top, top.title, top.artist.orEmpty()),
                        style = body(12f, 16, 500),
                        color = BrandLilac.copy(alpha = 0.82f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Picture.CLOCK -> cards.clock?.let { clock ->
                    val owl = cards.traits.firstOrNull { it.kind == TraitKind.NIGHT_OWL }
                    Text(traitName(TraitKind.NIGHT_OWL), style = display(30, 31, -0.03, weight = 700), color = BrandLilac, modifier = Modifier.padding(top = 12.dp))
                    Dial(
                        clock.hours,
                        clock.peak,
                        BrandLilac.copy(alpha = 0.06f),
                        BrandLilac.copy(alpha = 0.14f),
                        DarkInsights.series[0],
                        HuddleGold,
                        BrandLilac.copy(alpha = 0.75f),
                        BrandLilac,
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).size(190.dp),
                    )
                    owl?.let { Text(stringResource(R.string.insights_picture_owl, it.percent), style = body(12f, 16, 500), color = BrandLilac.copy(alpha = 0.82f)) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(stringResource(R.string.insights_picture_footer), style = body(11f, 14, 600), color = BrandLilac.copy(alpha = 0.78f), modifier = Modifier.weight(1f))
                val right = when (picture) {
                    Picture.SUMMARY -> cards.artists?.rows?.firstOrNull()?.let { stringResource(R.string.insights_top_artist, it.top.item.name) }
                    Picture.ALBUMS -> cards.time?.let { stringResource(R.string.insights_picture_albums_of, COLLAGE, it.totals.albums) }
                    Picture.CLOCK -> cards.clock?.let { stringResource(R.string.insights_picture_busiest, hourLabel(it.peak)) }
                }
                right?.let { Text(it, style = body(11f, 14, 600), color = BrandLilac.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (withButton) {
            DarkGround {
                LyrifyButton(
                    stringResource(R.string.insights_share),
                    onClick = { scope.launch { toasts.say(resources, sharePicture(context, layer, "${period.kind.name.lowercase(Locale.ROOT)}-${period.firstDay}-${picture.name.lowercase(Locale.ROOT)}", title)) } },
                    icon = R.drawable.ic_share,
                )
            }
        }
    }
}

/**
 * Shares [picture] (as drawn on screen) through the phone's share sheet as a PNG named for [name], with [title] as the
 * subject. Nothing is uploaded: the user picks where it goes.
 */
internal suspend fun sharePicture(context: Context, picture: GraphicsLayer, name: String, title: String): Shared {
    val png = try {
        val drawn = picture.toImageBitmap().asAndroidBitmap()
        withContext(Dispatchers.Default) {
            val bitmap = if (drawn.config == Bitmap.Config.HARDWARE) drawn.copy(Bitmap.Config.ARGB_8888, false) else drawn
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
        }
    } catch (_: IllegalStateException) {
        // The layer was never drawn (the page left before it showed).
        return Shared.NoPicture
    }
    val file = try {
        withContext(Dispatchers.IO) { shareCopy(context.cacheDir, "kjb-$name.png", png, System.currentTimeMillis()) }
    } catch (_: IOException) {
        return Shared.NoPicture
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + SHARE_AUTHORITY, file)
    return launchShare(1) { context.startActivity(shareSheet(recapImageShare(uri.toString(), title), context.packageName)) }
}

/** The story's line of who it is for: KJB's mark, then the title. */
private val StoryWho = body(14f, 18, 650)

/** How long each slide shows ([storyMs]). */
private const val SLIDE_MS = 5_600
private const val RACE_SLIDE_MS = 7_000
private const val LAST_MS = 9_000
private const val QUIZ_SEEN_MS = 2_600

/** A slide fading into the next. */
private const val SLIDE_FADE_MS = 360

/** The slide's clock runs this long: the longest arrival on a slide (the artists' race, 400 ms + 4 x 80 ms + 2.6 s) is in by then. */
private const val SLIDE_CLOCK_MS = 3_400f

/** Held this long, a press pauses; let go sooner than [TAP_MS], it is a tap. */
private const val HOLD_MS = 260L
private const val TAP_MS = 600L

/** The halves to tap start under the bars and buttons; back takes this share of the width. */
private val TAP_TOP = 80.dp
private const val BACK_SHARE = 0.32f

private val SlideKicker = body(12f, 16, 700).copy(letterSpacing = 0.14.em)
private val SlideTitle = display(44, 46, -0.035, weight = 700)
private val SlideTitleXl = display(64, 62, -0.035, weight = 700)
private val SlideLine = body(17f, 24)
private val SlideHuge = display(76, 72, -0.045, weight = 700)

/** A slide's parts rise over this long, 26 dp; a cover zooms in over this long. */
private const val RISE_MS = 700
private val RISE_DP = 26.dp
private const val ZOOM_MS = 900
private const val MINI_MS = 800
private const val MINI_DELAY_MS = 300
private const val MINI_STEP_MS = 20
private const val QUIZ_DELAY_MS = 220
private const val QUIZ_STEP_MS = 90
private const val QUIZ_SONGS = 3
private const val SHAKE_MS = 420
private const val FALL_MS = 2_600
private const val CONFETTI_SPREAD_MS = 500
private const val CONFETTI = 44
private val ConfettiFall = CubicBezierEasing(0.3f, 0.2f, 0.6f, 1f)
private const val WOBBLE_MS = 6_000
private const val RACE = 5
private const val RACE_MS = 2_600
private const val RACE_DELAY_MS = 400
private const val RACE_STEP_MS = 80
private const val RACE_TURN = 0.45f
private val RACE_LEADS = listOf(0.8f, 0.95f, 0.66f, 0.6f, 0.45f)
private val RaceEasing = CubicBezierEasing(0.3f, 0.1f, 0.2f, 1f)
private const val SWAY_MS = 6_000
private const val TWINKLE_MS = 2_600
private const val STARS = 28
private const val FACE_MS = 600
private const val FACE_DELAY_MS = 400
private const val FACE_STEP_MS = 110
private const val BADGE_DELAY_MS = 300
private const val BADGE_STEP_MS = 180
private const val BOB_A_MS = 4_200
private const val BOB_B_MS = 4_800
private const val BOB_C_MS = 4_400
private const val COLLAGE = 6
private val PICTURE_WIDTH = 260.dp

/** PNG ignores the quality; the platform asks for one. */
private const val PNG_QUALITY = 100
