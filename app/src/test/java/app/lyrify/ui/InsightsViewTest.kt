package app.lyrify.ui

import androidx.compose.animation.core.LinearEasing
import app.lyrify.stats.Ends
import app.lyrify.stats.InsightsCards
import app.lyrify.stats.MilestoneKind
import app.lyrify.stats.NextMilestone
import app.lyrify.stats.Step
import app.lyrify.stats.Steps
import app.lyrify.stats.Trait
import app.lyrify.stats.TraitKind
import app.lyrify.stats.fakeInsightsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.hypot

/** The redesigned Insights (IN2U): its cards' rules, its motion and its story, as pure logic and where only wiring says it, as source. */
class InsightsViewTest {
    // The story

    @Test
    fun `the story plays the slides its period has, in the mockup's order, and none without any time heard`() {
        val full = fakeInsightsPage().cards
        assertEquals(StorySlide.entries.toList(), storySlides(full))
        // Without friends, new artists or a listening type: those slides are left out, the order kept.
        assertEquals(
            listOf(StorySlide.INTRO, StorySlide.TIME, StorySlide.QUIZ, StorySlide.TOP_SONG, StorySlide.ARTISTS, StorySlide.HOUR, StorySlide.SHARE),
            storySlides(full.copy(friends = null, found = null, traits = emptyList())),
        )
        // Two songs are too few for the quiz, one is still a top song.
        assertFalse(StorySlide.QUIZ in storySlides(full.copy(songs = full.songs?.copy(rows = full.songs!!.rows.take(2)))))
        // Nothing heard: no story at all (it closes), never an intro over nothing.
        assertEquals(emptyList<StorySlide>(), storySlides(InsightsCards()))
        assertEquals(emptyList<StorySlide>(), storySlides(InsightsCards(recaps = full.recaps)))
    }

    @Test
    fun `each slide shows 5,6 s, the race 7 s, the picture 9 s, and the quiz waits for its answer`() {
        assertEquals(5_600, storyMs(StorySlide.TIME, last = false, answered = false))
        assertEquals(7_000, storyMs(StorySlide.ARTISTS, last = false, answered = false))
        assertEquals(9_000, storyMs(StorySlide.SHARE, last = true, answered = false))
        assertNull(storyMs(StorySlide.QUIZ, last = false, answered = false))
        assertEquals(2_600, storyMs(StorySlide.QUIZ, last = false, answered = true))
    }

    @Test
    fun `the story goes on until its last slide, back stops at the first, and a hold or a pause stops its bar`() {
        val state = StoryState(listOf(StorySlide.INTRO, StorySlide.QUIZ, StorySlide.SHARE))
        state.back()
        assertEquals(0, state.at)
        assertTrue(state.runs())
        state.held = true
        assertFalse(state.runs())
        state.held = false
        state.paused = true
        assertFalse(state.runs())
        state.paused = false
        assertTrue(state.next())
        // The quiz waits for its answer.
        assertFalse(state.runs())
        state.picked = fakeInsightsPage().cards.songs!!.rows[1].top.item
        assertTrue(state.runs())
        assertTrue(state.next())
        // At the last slide, next closes the story.
        assertFalse(state.next())
        assertEquals(2, state.at)
    }

    @Test
    fun `with Remove animations the story's bar does not run, so it waits for a tap`() {
        val state = StoryState(listOf(StorySlide.INTRO, StorySlide.TIME))
        assertTrue(state.runs(reduced = false))
        assertFalse(state.runs(reduced = true))
    }

    @Test
    fun `the quiz shows the top song second, between numbers 2 and 3`() {
        assertEquals(listOf("two", "one", "three"), quizOrder(listOf("one", "two", "three", "four")))
    }

    @Test
    fun `a picture is offered only when its card has what it shows`() {
        val full = fakeInsightsPage().cards
        // The fake has 2 albums (a collage needs 6) and a night owl among its traits.
        assertEquals(listOf(Picture.SUMMARY, Picture.CLOCK), picturesOf(full))
        assertEquals(listOf(Picture.SUMMARY), picturesOf(full.copy(traits = listOf(Trait(TraitKind.SHUFFLER, 24)))))
        val six = full.albums!!.rows.let { rows -> List(6) { rows[it % rows.size] } }
        assertEquals(listOf(Picture.SUMMARY, Picture.ALBUMS, Picture.CLOCK), picturesOf(full.copy(albums = full.albums!!.copy(rows = six))))
        assertEquals(emptyList<Picture>(), picturesOf(InsightsCards()))
    }

    // Motion

    @Test
    fun `a part of an arrival waits its delay, then eases over its duration and stays`() {
        assertEquals(0f, phase(100f, 200, 400, LinearEasing))
        assertEquals(0.5f, phase(400f, 200, 400, LinearEasing), 0.0001f)
        assertEquals(1f, phase(5_000f, 200, 400, LinearEasing))
        assertEquals(1f, phase(Float.MAX_VALUE, 0, 900, Motion.Glide))
    }

    @Test
    fun `how much of a card shows in the list, from none of it to all`() {
        assertEquals(0f, shownShare(offset = 1_000, size = 400, start = 0, end = 900))
        assertEquals(0.25f, shownShare(offset = 800, size = 400, start = 0, end = 900), 0.0001f)
        assertEquals(1f, shownShare(offset = 100, size = 400, start = 0, end = 900))
        assertEquals(0.5f, shownShare(offset = -200, size = 400, start = 0, end = 900), 0.0001f)
        assertEquals(0f, shownShare(offset = 0, size = 0, start = 0, end = 900))
    }

    @Test
    fun `everything that moves without end stops with Remove animations and off screen, and numbers and charts land at once`() {
        val ui = File("src/main/java/app/lyrify/ui")
        val files = ui.listFiles { f -> f.name.startsWith("Insights") }.orEmpty()
        assertTrue(files.size >= 8)
        files.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if ("rememberInfiniteTransition(" in line) assertTrue("${file.name}:${i + 1} moves on with animations off", "huddleMoves()" in line)
            }
        }
        val cards = File(ui, "InsightsCards.kt").readText()
        // A card is in place at once, its numbers final, its charts drawn whole.
        assertTrue(cards.contains("var shown by remember { mutableStateOf(reduced || key in arrived) }"))
        assertTrue(cards.contains("reduced -> count.snapTo(value.toFloat())"))
        assertTrue(cards.contains("if (!shown || reduced) return@LaunchedEffect"))
        // The story's bar stands full and nothing follows on its own.
        val story = File(ui, "InsightsStory.kt").readText()
        assertTrue(story.contains("val runs = state.runs(reduced)"))
        assertTrue(story.contains("val fill = remember(state.at, answered) { Animatable(if (reduced) 1f else 0f) }"))
    }

    // The cards' rules

    @Test
    fun `a share is a whole percent of its total, nothing of nothing`() {
        assertEquals(25, percentOf(1, 4))
        assertEquals(67, percentOf(2, 3))
        assertEquals(0, percentOf(5, 0))
    }

    @Test
    fun `the heat map has five steps, days from 45, 90 and 150 minutes, months from 20, 32 and 44 hours`() {
        val minute = 60_000L
        assertEquals(0, heatLevel(0, Step.DAY))
        assertEquals(1, heatLevel(44 * minute, Step.DAY))
        assertEquals(2, heatLevel(45 * minute, Step.DAY))
        assertEquals(3, heatLevel(90 * minute, Step.DAY))
        assertEquals(4, heatLevel(150 * minute, Step.DAY))
        assertEquals(1, heatLevel(19 * 60 * minute, Step.MONTH))
        assertEquals(4, heatLevel(44 * 60 * minute, Step.MONTH))
    }

    @Test
    fun `a day's number reads on every heat step, light and dark`() {
        listOf(LightInsights, DarkInsights).forEach { theme ->
            theme.heat.drop(1).forEach { ground ->
                val ink = heatInk(ground)
                val other = if (ink == BrandLilac) LightBrandColors.onHighlightContainer else BrandLilac
                assertTrue("picks the better ink on $ground", contrast(ink, ground) >= contrast(other, ground))
                // 12 sp, bold enough to count as large: 3:1 at the least (each day is also read whole by TalkBack).
                assertTrue("reads on $ground: ${contrast(ink, ground)}", contrast(ink, ground) >= 3f)
            }
        }
    }

    @Test
    fun `today's bar is the step of its day or month, and none outside`() {
        val week = Steps(Step.DAY, LocalDate.of(2026, 11, 23), List(7) { 1L })
        assertEquals(3, stepOf(week, LocalDate.of(2026, 11, 26)))
        assertEquals(-1, stepOf(week, LocalDate.of(2026, 12, 1)))
        val year = Steps(Step.MONTH, LocalDate.of(2026, 1, 1), List(12) { 1L })
        assertEquals(10, stepOf(year, LocalDate.of(2026, 11, 26)))
    }

    @Test
    fun `genre bubbles sit inside their box, never on each other, the biggest first and in the middle`() {
        val shares = listOf(31, 19, 14, 11, 9, 7)
        val bubbles = packBubbles(shares, 322f, 200f, 29f, 60f, 4f)
        assertEquals(shares.indices.toList(), bubbles.map { it.index })
        bubbles.forEach { b -> assertTrue(b.x - b.radius >= 0f && b.x + b.radius <= 322f && b.y - b.radius >= 0f && b.y + b.radius <= 200f) }
        bubbles.forEach { a -> bubbles.filter { it !== a }.forEach { b -> assertTrue(hypot(a.x - b.x, a.y - b.y) >= a.radius + b.radius + 4f - 0.01f) } }
        // All six fit, smaller than the mockup's 60 px when they must.
        assertTrue(bubbles.first().radius <= 60f)
        assertEquals(6, packBubbles(listOf(40, 30, 10, 10, 5, 5), 280f, 200f, 29f, 60f, 4f).size)
        assertTrue(bubbles.zipWithNext().all { (a, b) -> a.radius >= b.radius })
        assertEquals(emptyList<Bubble>(), packBubbles(emptyList(), 322f, 200f, 29f, 60f, 4f))
    }

    @Test
    fun `the Shuffle line shows only when Shuffle's songs were clearly skipped more`() {
        assertEquals(2f, skipRatio(Ends(finished = 6, movedOn = 0, early = 4), Ends(finished = 8, movedOn = 0, early = 2))!!, 0.0001f)
        assertNull(skipRatio(Ends(9, 0, 1), Ends(9, 0, 1)))
        assertNull(skipRatio(Ends(9, 0, 1), Ends(8, 0, 2)))
        assertNull(skipRatio(Ends(0, 0, 0), Ends(8, 0, 2)))
        assertNull(skipRatio(Ends(5, 0, 5), Ends(10, 0, 0)))
    }

    @Test
    fun `a time zone reads as UTC and its offset`() {
        assertEquals("UTC", utcText(0))
        assertEquals("UTC+3", utcText(180))
        assertEquals("UTC-5", utcText(-300))
        assertEquals("UTC+5:30", utcText(330))
    }

    @Test
    fun `the next milestone's ring fills to how far there is`() {
        assertEquals(0.25f, doneShare(NextMilestone(MilestoneKind.PLAYS, 1_000, 750)), 0.0001f)
        assertEquals(0.88f, doneShare(NextMilestone(MilestoneKind.HOURS, 100, 12 * 3_600_000L)), 0.0001f)
        assertEquals(0f, doneShare(NextMilestone(MilestoneKind.PLAYS, 1_000, 5_000)))
    }

    // Review round (Grok panel a-cards and b-story, 2026-11-23)

    @Test
    fun `the story's halves to tap lie under the slide, so an answer and Share take their own taps`() {
        val story = File("src/main/java/app/lyrify/ui/InsightsStory.kt").readText()
        val taps = story.indexOf("Row(Modifier.fillMaxSize().padding(top = TAP_TOP))")
        assertTrue(taps in 0 until story.indexOf("AnimatedContent("))
    }

    @Test
    fun `a story keeps the page it opened with, and the quiz its answer when you come back to it`() {
        val story = File("src/main/java/app/lyrify/ui/InsightsStory.kt").readText()
        assertTrue(story.contains("if (opened == null && page != null) {"))
        val slides = File("src/main/java/app/lyrify/ui/InsightsSlides.kt").readText()
        assertFalse(slides.contains("var picked by remember"))
        assertTrue(slides.contains("state.picked = song"))
        // Back on the answered quiz: its answer shows and the story goes on, with no shake or confetti again.
        val state = StoryState(listOf(StorySlide.QUIZ, StorySlide.TIME))
        state.picked = fakeInsightsPage().cards.songs!!.rows[1].top.item
        assertTrue(state.next())
        state.back()
        assertTrue(state.runs())
        assertTrue(slides.contains("if (fresh && picked == top && !reduced) Confetti("))
    }

    @Test
    fun `bars of a long all time land whole within their arrival, never narrower than nothing`() {
        assertEquals(16f, staggerMs(7, 16, 760, 2_000f))
        listOf(2, 78, 125, 200, 600).forEach { count ->
            val step = staggerMs(count, 16, 760, 2_000f)
            assertTrue("$count bars", step > 0f && (count - 1) * step + 760 <= 2_000.01f)
        }
        val time = File("src/main/java/app/lyrify/ui/InsightsTime.kt").readText()
        val slides = File("src/main/java/app/lyrify/ui/InsightsSlides.kt").readText()
        assertTrue(time.contains("staggerMs(count, BAR_STEP_MS, BAR_MS, ARRIVED_MS)"))
        assertTrue(slides.contains("staggerMs(daily.size, MINI_STEP_MS, MINI_MS, SLIDE_CLOCK_MS - MINI_DELAY_MS)"))
        // Many bars: the gaps narrow first, so a bar never gets a width below nothing.
        listOf(time, slides).forEach { assertTrue(it.contains("val gap = minOf(3.dp.toPx(), size.width / count / 4)")) }
    }

    @Test
    fun `a count never flashes its final number first, and Remove animations turned on lands every card at once`() {
        val cards = File("src/main/java/app/lyrify/ui/InsightsCards.kt").readText()
        assertTrue(cards.contains("val shown = if (reduced || (!count.isRunning && count.value == value.toFloat())) value else count.value.roundToLong()"))
        assertTrue(cards.contains("LaunchedEffect(value, arrival.shown, reduced)"))
        assertTrue(cards.contains("LaunchedEffect(key, reduced)"))
        // Every new period swaps the cards in, the first one again too.
        assertFalse(cards.contains("period == first"))
    }

    @Test
    fun `an unchanged period says the daily time, never 0 percent more`() {
        val time = File("src/main/java/app/lyrify/ui/InsightsTime.kt").readText()
        assertTrue(time.contains("if (change != 0L) {"))
        val slides = File("src/main/java/app/lyrify/ui/InsightsSlides.kt").readText()
        assertTrue(slides.contains("if (change == 0L) {"))
    }

    @Test
    fun `a short day or month never reads as no hours, and time zones go from west to east`() {
        val time = File("src/main/java/app/lyrify/ui/InsightsTime.kt").readText()
        assertFalse(time.contains("pluralStringResource(R.plurals.insights_hours_long, hours"))
        assertTrue(time.contains("ms < HOUR -> heardText(ms)"))
        assertTrue(time.contains("ms in 1 until HOUR -> stringResource(R.string.insights_under_an_hour)"))
        val you = File("src/main/java/app/lyrify/ui/InsightsYou.kt").readText()
        assertTrue(you.contains("val zones = moment.offsets.sorted().map { utcText(it) }"))
        assertFalse(you.contains("zones.minBy"))
    }

    @Test
    fun `with the largest text the period names, the rings' middles and a slide shrink to fit`() {
        val ui = File("src/main/java/app/lyrify/ui")
        assertTrue(File(ui, "Controls.kt").readText().contains("autoSize = TextAutoSize.StepBased(minFontSize = SEGMENT_SMALLEST, maxFontSize = MaterialTheme.typography.labelLarge.fontSize)"))
        listOf("InsightsMilestones.kt", "InsightsTops.kt", "InsightsTime.kt").forEach { name ->
            assertTrue(name, File(ui, name).readText().contains("// The largest text shrinks to fit the ring's hole."))
        }
        assertTrue(File(ui, "InsightsSlides.kt").readText().contains("measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))"))
    }

    @Test
    fun `no helper is used in one place only, no small bit sits in a vague variable, and existing pieces are reused`() {
        val ui = File("src/main/java/app/lyrify/ui")
        val text = ui.listFiles { f -> f.name.startsWith("Insights") }.orEmpty().joinToString("\n") { it.readText() }
        listOf(
            "fun playStoryText", "fun chartSummary", "fun loyaltyText", "fun lengthText", "fun typeName", "fun milestoneShort",
            "yearRing", "withButton", "val next =", "val tiles", "FactValue", "HOUR_MS", "if (cards.time != null) add(StorySlide",
        ).forEach { assertFalse(it, text.contains(it)) }
        // The picture's end line is written where it shows, not kept in a variable first.
        assertFalse(File(ui, "InsightsPictures.kt").readText().contains("val right ="))
        // The bold words of a line go through boldIn, wherever they are.
        assertEquals(1, Regex(Regex.escape("addStyle(SpanStyle(fontWeight")).findAll(text).count())
        // Sharing a picture sits with the pictures.
        assertTrue(File(ui, "InsightsPictures.kt").readText().contains("internal suspend fun sharePicture("))
        // For you's row and the top song reuse the mosaic and the round button.
        val forYou = File(ui, "SuggestionScreens.kt").readText()
        assertTrue(forYou.contains("MosaicTile(refs, size = 56.dp)"))
        listOf(forYou, File(ui, "InsightsTops.kt").readText()).forEach { assertTrue(Regex("""RoundButton\(\s*R\.drawable\.ic_play_arrow_fill""").containsMatchIn(it)) }
        // The cards name a period with the words the top list already uses.
        assertTrue(File(ui, "InsightsCards.kt").readText().contains("else -> stringResource(period.kind.named)"))
    }

    @Test
    fun `an answered quiz lets taps through to back and next, a moment under a minute never reads 0 min, and a bold word never lands inside another`() {
        val ui = File("src/main/java/app/lyrify/ui")
        // A clickable, even turned off, takes the touch from the halves under the slide: an answered row drops it.
        val slides = File(ui, "InsightsSlides.kt").readText()
        assertFalse(slides.contains("clickable(enabled = !answered)"))
        assertTrue(slides.contains(".then(if (answered) Modifier else Modifier.clickable {"))
        // Every Insights time that may be under half a minute goes through heardText, never listenedText's "0 min".
        val cards = File(ui, "InsightsCards.kt").readText()
        assertTrue(cards.contains("if (ms in 1 until ROUNDS_TO_NOTHING_MS) stringResource(R.string.insights_under_a_minute) else listenedText(ms)"))
        val time = File(ui, "InsightsTime.kt").readText()
        listOf("listenedText(totals.heardMs / days)", "listenedText(ms)", "listenedText(it)").forEach { assertFalse(it, time.contains(it)) }
        assertFalse(slides.contains("listenedText(time.totals.heardMs / days)"))
        // "UTC" bolds the plain "UTC", not the start of "UTC-8" before it.
        assertTrue(File(ui, "InsightsYou.kt").readText().contains("while (at >= 0 && taken.any { at < it.last + 1 && at + word.length > it.first })"))
    }

    // TalkBack and privacy

    @Test
    fun `every chart says its numbers to TalkBack, in words`() {
        val ui = File("src/main/java/app/lyrify/ui")
        val time = File(ui, "InsightsTime.kt").readText()
        // The hero's bars, the dial, the year's squares: one summary each with the real numbers.
        listOf("semantics { contentDescription = summary }", "R.string.insights_chart_days", "R.string.insights_dial", "R.plurals.insights_year_summary").forEach { assertTrue(it, time.contains(it)) }
        // Each day of a week or a month, each month of all time, is a stop with its time.
        assertTrue(Regex(Regex.escape("clearAndSetSemantics { contentDescription = description }")).findAll(time).count() >= 3)
        val tops = File(ui, "InsightsTops.kt").readText()
        // The bubbles and the decades say each share; a top row says its place, plays and move.
        listOf("semantics { contentDescription = words }", "R.string.insights_share_of", "placeWords(place, row.before, card.compared)").forEach { assertTrue(it, tops.contains(it)) }
        val milestones = File(ui, "InsightsMilestones.kt").readText()
        listOf("R.string.insights_library_ring", "R.string.insights_next_ring").forEach { assertTrue(it, milestones.contains(it)) }
    }

    @Test
    fun `Books and podcasts show the book's and the episode's covers, and the record's parts are the page's own`() {
        val ui = File("src/main/java/app/lyrify/ui")
        val milestones = File(ui, "InsightsMilestones.kt").readText()
        listOf("spoken.book?.parts?.firstOrNull()", "spoken.podcast").forEach { assertTrue(it, milestones.contains(it)) }
        val time = File(ui, "InsightsTime.kt").readText()
        assertTrue(time.contains("listOf(clock.morningMs, clock.afternoonMs, clock.eveningMs, clock.nightMs)"))
        assertFalse(time.contains("fun dayParts"))
    }

    @Test
    fun `search words stay on the page, hidden until a tap, and never reach a story or a picture`() {
        val ui = File("src/main/java/app/lyrify/ui")
        listOf("InsightsSlides.kt", "InsightsPictures.kt", "InsightsStory.kt").forEach { name ->
            val text = File(ui, name).readText()
            listOf("searches", "WordCount", ".words").forEach { assertFalse("$name: $it", text.contains(it)) }
        }
        val you = File(ui, "InsightsYou.kt").readText()
        // Hidden again for another period: a tap shows one period's words only.
        assertTrue(you.contains("var shown by remember(searches) { mutableStateOf(false) }"))
        // Until the tap no word is drawn at all (a blur cannot be drawn on every phone), nor read by TalkBack.
        assertTrue(you.contains("then(if (shown) Modifier else Modifier.clearAndSetSemantics {})"))
        assertTrue(you.contains("if (shown) {\n                                Text(word.word"))
    }
}
