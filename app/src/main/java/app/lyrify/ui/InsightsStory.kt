package app.lyrify.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.lyrify.R
import app.lyrify.library.Song
import app.lyrify.stats.InsightsCards
import app.lyrify.stats.InsightsPage
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.phoneCalendar
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Whether the bar runs: animations on ([reduced] false), not paused or held, and not waiting for the quiz's answer. */
    fun runs(reduced: Boolean = false): Boolean = !reduced && !paused && !held && storyMs(slide, at == slides.lastIndex, answer != null) != null
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
    val answered = state.answer != null
    // A new slide, or the quiz answered: its bar starts again (full and still with reduced motion), then runs while
    // nothing holds it, on from where a pause left it.
    val fill = remember(state.at, answered) { Animatable(if (reduced) 1f else 0f) }
    val runs = state.runs(reduced)
    LaunchedEffect(fill, runs) {
        val ms = storyMs(state.slide, state.at == state.slides.lastIndex, answered) ?: return@LaunchedEffect
        if (!runs) return@LaunchedEffect
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
        // Nothing heard in it: there is no story, so it closes.
        if (shown.slides.isEmpty()) {
            LaunchedEffect(Unit) { close() }
            return@Box
        }
        StoryFrame(storyName(page.period), shown, close) { slide -> Slide(slide, page, settings, shown) }
    }
}

/**
 * The slides a page has enough for, in the mockup's order: the intro, then each that has its card, then the picture;
 * none at all without any time heard.
 */
fun storySlides(cards: InsightsCards): List<StorySlide> = buildList {
    if (cards.time == null) return@buildList
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

internal const val QUIZ_SONGS = 3


// Insights' cards of when you listen (the approved mockup, handoff/insights/preview-insights-v1.html): the stories row,
// your time (the hero), every day you listened and your day on a record. Their frame, motion and words are in
// InsightsCards.kt.

// The stories row

/**
 * Your recaps as stories (the mockup's rings): this month so far, the latest month's recap (its ring turning while it is
 * new), the year so far, then the months before, each a ring that opens its story: a recap's covers, or the month's or
 * the year's name for one still going.
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
    val month = java.time.YearMonth.from(today)
    val going = page.cards.time != null
    LazyRow(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (going) {
            item(key = "month so far") {
                StoryRing(stringResource(R.string.insights_ring_so_far, monthText(month, short = true)), new = false, covers = emptyList(), name = monthText(month, short = true)) {
                    nav.show(FullScreen.Story(period = ListeningPeriod.MONTH))
                }
            }
        }
        recaps.forEachIndexed { i, recap ->
            item(key = "recap ${recap.month}") {
                StoryRing(monthText(recap.month), new = i == 0 && due, covers = recap.covers, name = null) {
                    if (i == 0) change { it.copy(nav = it.nav.copy(recapSeen = recap.month)) }
                    nav.show(FullScreen.Story(month = recap.month.toString()))
                }
            }
            if (i == 0 && going) yearRing(today.year) { nav.show(FullScreen.Story(period = ListeningPeriod.YEAR)) }
        }
        if (recaps.isEmpty() && going) yearRing(today.year) { nav.show(FullScreen.Story(period = ListeningPeriod.YEAR)) }
    }
}

/** The year so far's ring, the year in big type on the hero's ground. */
private fun androidx.compose.foundation.lazy.LazyListScope.yearRing(year: Int, onClick: () -> Unit) = item(key = "year so far") {
    StoryRing(stringResource(R.string.insights_ring_so_far, year.toString()), new = false, covers = emptyList(), name = year.toString(), onClick = onClick)
}

/**
 * One story's ring: its covers in a circle (or [name] in big type on the hero's ground), a turning coral, gold and violet
 * ring with a coral dot while it is [new], else a quiet one; its [label] under it. A button for TalkBack ("October, new:
 * play the story").
 */
@Composable
private fun StoryRing(label: String, new: Boolean, covers: List<Song>, name: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val turn = if (new && huddleMoves()) rememberInfiniteTransition(label = "story ring").animateFloat(0f, 360f, infiniteRepeatable(tween(STORY_RING_MS, easing = LinearEasing)), label = "turn") else null
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
                    .background(if (name != null) HuddleEmptyGround else colors.surfaceVariant)
                    .drawBehind {
                        if (name != null) {
                            drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[0], Color.Transparent), Offset(size.width * 0.2f, size.height * 0.15f), size.width * 0.6f))
                            drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[2], Color.Transparent), Offset(size.width * 0.9f, size.height * 0.8f), size.width * 0.6f))
                        }
                    },
            ) {
                if (name != null) {
                    Text(name, style = display(17, 17, -0.02, weight = 700), color = BrandLilac, maxLines = 1)
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

/** A new story's ring turns once in this long (the mockup's spin). */
private const val STORY_RING_MS = 3_200
