package app.lyrify.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.Settings
import app.lyrify.stats.LibraryCard
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.MilestoneKind
import app.lyrify.stats.MilestonesCard
import app.lyrify.stats.NextMilestone
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.SpokenCard
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.Trait
import app.lyrify.stats.TraitKind
import app.lyrify.stats.recapDue
import app.lyrify.stats.recapMonthOf
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.first

// Insights' cards of what you keep and reach (the approved mockup, handoff/insights/preview-insights-v1.html): books and
// podcasts, your library, your listening type, milestones and badges; and the monthly recap's card on Library's start
// tab. Their frame, motion and words are in InsightsCards.kt.

// Books and podcasts

/**
 * Books and podcasts in real minutes, each on its own tile with the cover of the book or episode heard most (a book's
 * standing like a book, 42 by 58), then the time faster speeds saved, the bookmarks and the books finished.
 */
@Composable
internal fun BooksAndPodcasts(spoken: SpokenCard) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val unit = SpanStyle(fontSize = 12.sp, fontWeight = FontWeight(650))
    InsightsCard {
        CardHead(stringResource(R.string.insights_spoken_title), R.drawable.ic_auto_stories, stringResource(R.string.insights_spoken_line))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple(spoken.booksMs, spoken.book?.parts?.firstOrNull(), R.string.insights_in_books), Triple(spoken.podcastsMs, spoken.podcast, R.string.insights_in_podcasts))
                .filter { it.first > 0 }
                .forEach { (ms, cover, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f).background(colors.surface, RoundedCornerShape(20.dp)).padding(12.dp).semantics(mergeDescendants = true) {},
                    ) {
                        val book = label == R.string.insights_in_books
                        SongCover(
                            cover?.let { SongArt(it.path, it.ref.mediaStoreId) },
                            if (book) Modifier.padding(horizontal = 5.dp).width(42.dp) else Modifier,
                            size = if (book) 58.dp else 52.dp,
                            shape = if (book) RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp) else RoundedCornerShape(12.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Counted(ms, display(21, 24, -0.02, weight = 700), colors.onSurface) { bigTime(it, unit) }
                            Text(stringResource(label), style = FigureLabel.copy(fontSize = 12.5.sp), color = colors.onSurfaceVariant)
                        }
                    }
                }
        }
        Column(Modifier.padding(top = 12.dp)) {
            listOf(
                Triple(R.drawable.ic_speed, stringResource(R.string.insights_saved), listenedText(spoken.savedMs)),
                Triple(R.drawable.ic_bookmark, stringResource(R.string.insights_bookmarks_made), formatCount(spoken.bookmarks)),
                Triple(R.drawable.ic_check, stringResource(R.string.insights_books_finished), formatCount(spoken.finished)),
            ).forEach { (icon, name, value) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 40.dp).semantics(mergeDescendants = true) {}) {
                    Icon(painterResource(icon), contentDescription = null, tint = brand.highlight, modifier = Modifier.size(20.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                    Text(value, style = display(16, 20, weight = 700).tabular(), color = colors.onSurface)
                }
            }
        }
    }
}

// Your library

/**
 * Your library: how much of it you ever played as a teal ring drawing itself in, its songs and size, how long to play it
 * all, what you added and played already, its file types, then the songs never played (Shuffle them) and an album to dig
 * deeper into, its songs as dots (the ones you played filled).
 */
@Composable
internal fun YourLibrary(library: LibraryCard, period: PagePeriod, settings: Settings, onOpen: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val context = LocalContext.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val played = percentOf(library.played.toLong(), library.songs.toLong())
    InsightsCard {
        CardHead(stringResource(R.string.insights_library_title), R.drawable.ic_library_music, stringResource(R.string.insights_library_line))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressRing(played / 100f, brand.suggestion, stringResource(R.string.insights_percent, formatCount(played)), stringResource(R.string.insights_played_ever), 120, 14, LIBRARY_RING_MS, stringResource(R.string.insights_library_ring, played))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                LibraryFact(pluralStringResource(R.plurals.insights_songs, library.songs, formatCount(library.songs)), stringResource(R.string.insights_on_phone, Formatter.formatShortFileSize(context, library.bytes)))
                LibraryFact(lengthText(library.durationMs), stringResource(R.string.insights_play_it_all))
                val added = when (period.kind) {
                    ListeningPeriod.WEEK -> stringResource(R.string.insights_this_week_lower)
                    ListeningPeriod.MONTH -> stringResource(R.string.insights_this_month_lower)
                    ListeningPeriod.YEAR -> stringResource(R.string.insights_this_year_lower)
                    ListeningPeriod.ALL_TIME -> stringResource(R.string.insights_last_30_days)
                }
                LibraryFact(stringResource(R.string.insights_added, formatCount(library.added)), stringResource(R.string.insights_added_line, added, formatCount(library.addedPlayed)))
            }
        }
        if (library.types.isNotEmpty()) {
            val shown = library.types.take(2)
            val other = library.types.drop(2).sumOf { it.songs }
            val counts = shown.map { it.songs } + listOfNotNull(other.takeIf { it > 0 })
            val total = counts.sum().toLong()
            val shares = counts.map { percentOf(it.toLong(), total) }
            val names = shown.map { typeName(it.mimeType) } + listOfNotNull(stringResource(R.string.insights_other).takeIf { other > 0 })
            val typeColors = listOf(insights.series[0], insights.series[3], insights.series[2])
            StackBar(shares, typeColors, 12.dp, Modifier.padding(top = 14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                names.forEachIndexed { k, name -> LegendItem(typeColors[k], name, stringResource(R.string.insights_percent, formatCount(shares[k]))) }
            }
        }
        if (library.neverPlayed.isNotEmpty()) {
            val name = stringResource(R.string.insights_never_title)
            LibraryRow(
                lead = { Tile(R.drawable.ic_shuffle, colors.surfaceContainerHigh, colors.onSurface, size = 48.dp) },
                title = pluralStringResource(R.plurals.insights_never_count, library.neverPlayed.size, formatCount(library.neverPlayed.size)),
                line = stringResource(R.string.insights_never_line),
            ) { shuffle(context, library.neverPlayed, NEVER_PLAYED_KEY, name) }
        }
        library.digDeeper.firstOrNull()?.let { dig ->
            val songs = dig.album.songs
            LibraryRow(
                lead = { SongCover(songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = 48.dp) },
                title = stringResource(R.string.insights_dig_album, dig.album.title),
                line = stringResource(R.string.insights_dig_line, songs.size - dig.unplayed.size, songs.size),
                dots = songs.map { it !in dig.unplayed },
            ) { onOpen(Page.Album(dig.album.key)) }
        }
    }
}

@Composable
private fun LibraryFact(value: String, line: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = display(19, 23, -0.01, weight = 700).tabular(), color = MaterialTheme.colorScheme.onSurface)
        Text(line, style = FigureLabel.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row of the library card to tap: its [lead], [title] and [line] (and [dots]: an album's songs, the played ones filled), a chevron. */
@Composable
private fun LibraryRow(lead: @Composable () -> Unit, title: String, line: String, dots: List<Boolean> = emptyList(), onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        lead()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(line, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = colors.onSurfaceVariant)
            if (dots.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {}) {
                    dots.forEach { on ->
                        Box(Modifier.size(9.dp).background(if (on) brand.suggestion else colors.surfaceContainerHigh, CircleShape).border(1.5.dp, if (on) brand.suggestion else colors.outline, CircleShape))
                    }
                }
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

/** "6 days 19 h", "19 h 5 min": how long a library plays end to end. */
@Composable
private fun lengthText(ms: Long): String {
    val time = listenedTime(ms)
    val days = time.hours / 24
    return if (days > 0) stringResource(R.string.insights_days_hours, pluralStringResource(R.plurals.insights_days_word_count, days, formatCount(days)), time.hours % 24) else listenedText(ms)
}

/** A file type's short name: MP3, FLAC, M4A, OGG, Opus, WAV, else its subtype in capitals. */
private fun typeName(mime: String?): String = when (mime?.lowercase(Locale.ROOT)) {
    "audio/mpeg", "audio/mp3" -> "MP3"
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "M4A"
    "audio/ogg", "audio/vorbis" -> "OGG"
    "audio/opus" -> "Opus"
    "audio/wav", "audio/x-wav" -> "WAV"
    null -> "?"
    else -> mime.substringAfter('/').removePrefix("x-").uppercase(Locale.ROOT)
}

/**
 * A ring that fills to [done] (0 to 1) in [color], drawing itself in over [ms] as its card arrives, with [value] in big
 * type and [line] under it in its middle; [size] and [stroke] in dp. TalkBack reads [description].
 */
@Composable
private fun ProgressRing(done: Float, color: Color, value: String, line: String, size: Int, stroke: Int, ms: Int, description: String) {
    val colors = MaterialTheme.colorScheme
    val clock = LocalArrivalClock.current
    val measurer = rememberTextMeasurer()
    val big = display(if (size > 110) 30 else 22, 32, -0.02, weight = 700).copy(color = colors.onSurface)
    val small = AxisLabel.copy(fontSize = if (size > 110) 11.sp else 10.5.sp, color = colors.onSurfaceVariant)
    Canvas(Modifier.size(size.dp).semantics { contentDescription = description }) {
        val width = stroke.dp.toPx()
        val r = this.size.minDimension * (if (size > 110) 48f / 120f else 42f / 104f)
        val at = Offset(this.size.width / 2 - r, this.size.height / 2 - r)
        val box = Size(r * 2, r * 2)
        drawArc(colors.surfaceContainerHigh, 0f, 360f, false, at, box, style = Stroke(width))
        drawArc(color, -90f, 360f * done.coerceIn(0f, 1f) * phase(clock.ms(), 0, ms, Motion.Rise), false, at, box, style = Stroke(width, cap = StrokeCap.Round))
        val a = measurer.measure(value, big)
        drawText(a, topLeft = Offset(this.size.width / 2 - a.size.width / 2, this.size.height / 2 - a.size.height / 2 - 6.dp.toPx()))
        val b = measurer.measure(line, small)
        drawText(b, topLeft = Offset(this.size.width / 2 - b.size.width / 2, this.size.height / 2 + a.size.height / 2 - 6.dp.toPx()))
    }
}

// Your listening type

/**
 * Your listening type, a ticket on the ink-to-pink ground with a light sweeping over it: "You listen like a", the three
 * words, then each with the number that earned it.
 */
@Composable
internal fun ListeningType(traits: List<Trait>) {
    val shine = if (huddleMoves()) rememberInfiniteTransition(label = "ticket").animateFloat(1.3f, -0.3f, infiniteRepeatable(tween(SHINE_MS, easing = FastOutSlowInEasing)), label = "shine") else null
    val names = traits.map { traitName(it.kind) }
    Column(
        Modifier
            .padding(horizontal = ScreenPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(HuddleTicket))
            .drawWithContent {
                shine?.let {
                    val at = it.value * size.width
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, BrandLilac.copy(alpha = 0.22f), Color.Transparent), at - size.width * 0.15f, at + size.width * 0.15f))
                }
                drawContent()
            }
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
    ) {
        Text(stringResource(R.string.insights_listen_like).uppercase(), style = CardKicker.copy(fontSize = 11.5.sp, letterSpacing = 0.12.sp * 11.5f), color = BrandLilac.copy(alpha = 0.82f))
        Text(
            buildAnnotatedString {
                names.forEachIndexed { i, name ->
                    if (i > 0) append(", ")
                    val word = if (i == 0) name else name.lowercase(Locale.getDefault())
                    if (i == 1) withStyle(SpanStyle(color = StoryKicker)) { append(word) } else append(word)
                }
            },
            style = display(34, 38, -0.025, weight = 700),
            color = BrandLilac,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 14.dp)) {
            traits.forEachIndexed { i, trait ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().background(BrandLilac.copy(alpha = 0.13f), RoundedCornerShape(16.dp)).padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).semantics(mergeDescendants = true) {},
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(BrandLilac.copy(alpha = 0.18f), RoundedCornerShape(12.dp))) {
                        Icon(painterResource(traitIcon(trait.kind)), contentDescription = null, tint = BrandLilac, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(names[i], style = display(15, 19, weight = 700), color = BrandLilac)
                        Text(traitWhy(trait), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp), color = BrandLilac.copy(alpha = 0.88f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun traitName(kind: TraitKind): String = stringResource(
    when (kind) {
        TraitKind.NIGHT_OWL -> R.string.insights_trait_night_owl
        TraitKind.SHUFFLER -> R.string.insights_trait_shuffler
        TraitKind.ALBUM_LOVER -> R.string.insights_trait_album_lover
        TraitKind.EXPLORER -> R.string.insights_trait_explorer
        TraitKind.LOYAL -> R.string.insights_trait_loyal
    },
)

@DrawableRes
internal fun traitIcon(kind: TraitKind): Int = when (kind) {
    TraitKind.NIGHT_OWL -> R.drawable.ic_dark_mode
    TraitKind.SHUFFLER -> R.drawable.ic_shuffle
    TraitKind.ALBUM_LOVER -> R.drawable.ic_album
    TraitKind.EXPLORER -> R.drawable.ic_explore
    TraitKind.LOYAL -> R.drawable.ic_favorite_fill
}

/** The number behind a trait: "41% of your time was after 9 pm", "Kestrel is 15% of your plays". */
@Composable
internal fun traitWhy(trait: Trait): String = when (trait.kind) {
    TraitKind.NIGHT_OWL -> stringResource(R.string.insights_trait_night_owl_why, trait.percent)
    TraitKind.SHUFFLER -> stringResource(R.string.insights_trait_shuffler_why, trait.percent)
    TraitKind.ALBUM_LOVER -> stringResource(R.string.insights_trait_album_lover_why, trait.percent)
    TraitKind.EXPLORER -> stringResource(R.string.insights_trait_explorer_why, trait.percent)
    TraitKind.LOYAL -> stringResource(R.string.insights_trait_loyal_why, trait.artist?.name.orEmpty(), trait.percent)
}

// Milestones and badges

/**
 * Milestones and badges: the next milestone as a gold ring drawing itself in with how far there is to go and, at this
 * month's pace, about when; those reached in the period with their days; then six medals: the longest run, the plays and
 * hours reached, the most artists in a month, books finished, and the next one locked.
 */
@Composable
internal fun MilestonesAndBadges(milestones: MilestonesCard, period: PagePeriod) {
    val colors = MaterialTheme.colorScheme
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    InsightsCard {
        CardHead(stringResource(R.string.insights_milestones_badges), R.drawable.ic_emoji_events)
        milestones.next?.let { next ->
            val done = doneShare(next)
            val percent = (done * 100).toInt()
            val left = if (next.kind == MilestoneKind.PLAYS) formatCount(next.left.toInt()) else listenedText(next.left)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProgressRing(done, HuddleGold, stringResource(R.string.insights_percent, formatCount(percent)), stringResource(R.string.insights_of_the_way), 104, 10, LIBRARY_RING_MS, stringResource(R.string.insights_next_ring, milestoneText(next.kind, next.count), left))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(stringResource(R.string.insights_next_word), style = FigureLabel.copy(fontSize = 13.sp), color = colors.onSurfaceVariant)
                    Text(milestoneText(next.kind, next.count), style = display(19, 24, weight = 700), color = colors.onSurface)
                    Text(
                        stringResource(R.string.insights_to_go, left) + (milestones.nextAround?.let { stringResource(R.string.insights_pace, monthText(it, YearMonth.of(0, 1))) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        if (milestones.reached.isNotEmpty()) {
            Column(Modifier.padding(top = 12.dp)) {
                milestones.reached.take(REACHED).forEach { reached ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 36.dp).semantics(mergeDescendants = true) {}) {
                        Icon(painterResource(R.drawable.ic_emoji_events), contentDescription = null, tint = insights.goldInk, modifier = Modifier.size(20.dp))
                        Text(milestoneText(reached.kind, reached.count), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.onSurface, modifier = Modifier.weight(1f))
                        Text(dateText(reached.at), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = colors.onSurfaceVariant)
                    }
                }
            }
        } else if (period.going && (period.kind == ListeningPeriod.WEEK || period.kind == ListeningPeriod.MONTH)) {
            Text(
                stringResource(if (period.kind == ListeningPeriod.WEEK) R.string.insights_no_milestone_week else R.string.insights_no_milestone_month),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        val badges = buildList {
            milestones.longestRun?.let { add(Badge(R.drawable.ic_local_fire_department, pluralStringResource(R.plurals.insights_run_badge, it.days, it.days), stringResource(R.string.insights_your_longest), gold = true)) }
            milestones.plays?.let { add(Badge(R.drawable.ic_emoji_events, milestoneText(it.kind, it.count), monthOf(it.at), gold = true)) }
            milestones.hours?.let { add(Badge(R.drawable.ic_schedule, milestoneText(it.kind, it.count), monthOf(it.at), gold = true)) }
            milestones.artistsMonth?.let { add(Badge(R.drawable.ic_group, pluralStringResource(R.plurals.insights_artists, it.artists, formatCount(it.artists)), stringResource(R.string.insights_in_one_month), gold = false)) }
            if (milestones.booksFinished > 0) add(Badge(R.drawable.ic_auto_stories, stringResource(R.string.insights_bookworm), pluralStringResource(R.plurals.insights_books_finished_count, milestones.booksFinished, milestones.booksFinished), gold = false))
            milestones.next?.let { add(Badge(R.drawable.ic_lock, milestoneShort(it), stringResource(R.string.insights_there, (doneShare(it) * 100).toInt()), gold = null)) }
        }
        if (badges.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                badges.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { badge -> Medal(badge, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** A badge: its [icon], [name] and [line]; [gold] for a gold medal, false for violet, null while it is locked. */
private class Badge(@DrawableRes val icon: Int, val name: String, val line: String, val gold: Boolean?)

@Composable
private fun Medal(badge: Badge, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.background(colors.surface, RoundedCornerShape(20.dp)).padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 10.dp).semantics(mergeDescendants = true) {},
    ) {
        val ink = when (badge.gold) {
            true -> OnHuddleGold
            false -> BrandLilac
            null -> colors.onSurfaceVariant
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).drawBehind {
                when (badge.gold) {
                    null -> drawCircle(colors.surfaceContainerHigh)
                    else -> drawCircle(Brush.radialGradient(if (badge.gold) MedalGold else MedalViolet, Offset(size.width * 0.35f, size.height * 0.3f), size.width * 0.75f))
                }
                val ring = 1.5.dp.toPx()
                drawCircle(
                    if (badge.gold == null) colors.outline else ink.copy(alpha = 0.38f),
                    size.minDimension / 2 - 4.dp.toPx(),
                    style = Stroke(ring, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            },
        ) {
            Icon(painterResource(badge.icon), contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        }
        Text(badge.name, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight(650)), color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(badge.line, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** How much of the way to [next] is done: 0 to 1. */
fun doneShare(next: NextMilestone): Float {
    val whole = if (next.kind == MilestoneKind.PLAYS) next.count.toLong() else next.count * HOUR_MS
    return if (whole <= 0) 0f else ((whole - next.left).toFloat() / whole).coerceIn(0f, 1f)
}

/** "1,000 plays", "100 hours heard". */
@Composable
internal fun milestoneText(kind: MilestoneKind, count: Int): String = when (kind) {
    MilestoneKind.PLAYS -> playsNote(count)
    MilestoneKind.HOURS -> pluralStringResource(R.plurals.insights_hours, count, formatCount(count))
}

/** A milestone short, for a locked medal: "1,000 h", "2,500". */
@Composable
private fun milestoneShort(next: NextMilestone): String =
    if (next.kind == MilestoneKind.HOURS) stringResource(R.string.insights_hours_short, formatCount(next.count)) else formatCount(next.count)

/** The month of [at], with its year: "March 2026". */
@Composable
private fun monthOf(at: Long): String = monthText(YearMonth.from(java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())), YearMonth.of(0, 1))

/** A new queue of [songs], shuffled, from Insights ([key]: never played or forgotten favourites, as a list of its own). */
internal fun shuffle(context: Context, songs: List<Song>, key: String, name: String) =
    Lyrify.playback(context).shuffle(songs.map { it.ref }, QueueSource(SourceKind.PICKED, key, name))

// The monthly recap

/** "October", or "October 2025" for a month of another year than [today]'s; [short]: "Oct". */
@Composable
internal fun monthText(month: YearMonth, today: YearMonth = YearMonth.now(), short: Boolean = false): String {
    val context = LocalContext.current
    val at = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val year = if (month.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    val abbrev = if (short) DateUtils.FORMAT_ABBREV_MONTH else 0
    return DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or year or abbrev)
}

/**
 * The start screen's recap card (plan Q1.2), the first row of Library's [tab], shown only on the tab Library opens on
 * (the first in its order, or "Open here first"): from the first open of a month, "Your October" while that month has
 * a recap and the user has not opened or closed it. A tap plays its story; the X, a swipe or TalkBack's action closes
 * it. Either way it is gone for good, and the story stays on Insights' stories row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecapStartCard(tab: LibraryTab, modifier: Modifier) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val change = rememberSettingsChange()
    val settings by remember(context) { Lyrify.settings(context).settings }.collectAsState(null)
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsState()
    // The day, so an app kept running across the 1st (the player keeps it alive) turns to the new month's card.
    val today = rememberToday()
    val read = settings ?: return
    if (tab != libraryOpensOn(shownLibraryTabs(read.library, (library as? LibraryState.Ready)?.library), read.library.opensOn)) return
    val zone = ZoneId.systemDefault()
    val month = remember(today) { recapMonthOf(System.currentTimeMillis(), zone) }
    // Opened or closed already this month: no need to read the plays.
    if (read.nav.recapSeen?.let { it >= month } == true) return
    val months by produceState<List<YearMonth>?>(null, month) { value = StatsGraph.stats(context).recaps(System.currentTimeMillis(), zone) }
    if (!recapDue(month, months ?: return, read.nav.recapSeen)) return
    val done = { change { it.copy(nav = it.nav.copy(recapSeen = month)) } }
    val title = stringResource(R.string.insights_recap_title, monthText(month))
    val close = stringResource(R.string.insights_close)
    SwipeToDismissBox(state = rememberSwipeToDismissBoxState(), backgroundContent = {}, onDismiss = { done() }, modifier = modifier) {
        LyrifyCard(
            Modifier.semantics { customActions = listOf(CustomAccessibilityAction(close) { done(); true }) },
            onClick = {
                done()
                nav.show(FullScreen.Story(month = month.toString()))
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.heightIn(min = 72.dp).padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                val brand = LocalBrandColors.current
                Tile(R.drawable.ic_bar_chart, brand.highlightContainer, brand.onHighlightContainer)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.insights_recap_ready), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ChromeIconButton(R.drawable.ic_close, close, { done() })
            }
        }
    }
}

/** The recap card ([RecapStartCard]) as the first row of Library's [tab] list. */
fun LazyListScope.recapCard(tab: LibraryTab) = item(key = "recap", contentType = "recap") {
    RecapStartCard(tab, rowMotion(LocalReducedMotion.current).padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 4.dp))
}

/** The recap card as the first row of Library's [tab] grid (Albums, Books), whose sides are the grid's. */
fun LazyGridScope.recapCard(tab: LibraryTab) = item(key = "recap", span = { GridItemSpan(maxLineSpan) }, contentType = "recap") {
    RecapStartCard(tab, rowMotion(LocalReducedMotion.current).padding(top = 8.dp, bottom = 4.dp))
}

private const val REACHED = 4

private const val LIBRARY_RING_MS = 1_300

private const val SHINE_MS = 4_000

private const val HOUR_MS = 3_600_000L

private const val NEVER_PLAYED_KEY: String = "insights-never-played"

internal const val FORGOTTEN_KEY: String = "insights-forgotten"
