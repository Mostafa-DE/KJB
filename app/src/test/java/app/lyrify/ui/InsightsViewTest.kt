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
        state.answer = false
        assertTrue(state.runs())
        assertTrue(state.next())
        // At the last slide, next closes the story.
        assertFalse(state.next())
        assertEquals(2, state.at)
    }

    @Test
    fun `with Remove animations the story stands still and waits for a tap`() {
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
    fun `a card arrives once a tenth of it shows`() {
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
        assertTrue(cards.contains("if (period == first || !shown || reduced) return@LaunchedEffect"))
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
    fun `the day's parts sum its hours, the night wrapping past midnight`() {
        val hours = List(24) { (it + 1).toLong() }
        assertEquals(listOf((7..12).sum(), (13..18).sum(), (19..22).sum(), (23..24).sum() + (1..6).sum()).map { it.toLong() }, dayParts(hours))
        assertEquals(hours.sum(), dayParts(hours).sum())
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

    // TalkBack and privacy

    @Test
    fun `every chart says its numbers to TalkBack, in words`() {
        val ui = File("src/main/java/app/lyrify/ui")
        val time = File(ui, "InsightsTime.kt").readText()
        // The hero's bars, the dial, the year's squares: one summary each with the real numbers.
        listOf("semantics { contentDescription = summary }", "chartSummary(steps)", "R.string.insights_dial", "R.plurals.insights_year_summary").forEach { assertTrue(it, time.contains(it)) }
        // Each day of a week or a month, each month of all time, is a stop with its time.
        assertTrue(Regex(Regex.escape("clearAndSetSemantics { contentDescription = description }")).findAll(time).count() >= 3)
        val tops = File(ui, "InsightsTops.kt").readText()
        // The bubbles and the decades say each share; a top row says its place, plays and move.
        listOf("semantics { contentDescription = words }", "R.string.insights_share_of", "placeWords(place, row.before, card.compared)").forEach { assertTrue(it, tops.contains(it)) }
        val milestones = File(ui, "InsightsMilestones.kt").readText()
        listOf("R.string.insights_library_ring", "R.string.insights_next_ring").forEach { assertTrue(it, milestones.contains(it)) }
    }

    @Test
    fun `search words stay on the page, hidden until a tap, and never reach a story or a picture`() {
        val ui = File("src/main/java/app/lyrify/ui")
        listOf("InsightsSlides.kt", "InsightsPictures.kt", "InsightsStory.kt").forEach { name ->
            val text = File(ui, name).readText()
            listOf("searches", "WordCount", ".words").forEach { assertFalse("$name: $it", text.contains(it)) }
        }
        val you = File(ui, "InsightsYou.kt").readText()
        assertTrue(you.contains("var shown by remember { mutableStateOf(false) }"))
        // Until the tap no word is drawn at all (a blur cannot be drawn on every phone), nor read by TalkBack.
        assertTrue(you.contains("then(if (shown) Modifier else Modifier.clearAndSetSemantics {})"))
        assertTrue(you.contains("if (shown) {\n                                Text(word.word"))
    }
}
