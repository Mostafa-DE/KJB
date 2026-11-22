package app.lyrify.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Genre
import app.lyrify.library.LibraryGraph
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.settings.DockTab
import app.lyrify.settings.Settings
import app.lyrify.stats.Insights
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.SongPlays
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.Top
import app.lyrify.stats.WhenPlayed
import app.lyrify.stats.dayOf
import app.lyrify.stats.recapMonthOf
import app.lyrify.stats.whenPlayed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

// Insights, grown out of Your listening (S1; KJB 3.2): its page, a month's recap, the list of recaps, the "View all" of
// its top lists, and what the library's screens show of plays (L1 places them): the card on Most played, the rows'
// notes, and Song info's plays lines. Its cards are in InsightsCards.kt.

/** The period Insights and its top lists show: Month at first, then the one last picked while the app runs. */
private var shownPeriod by mutableStateOf(ListeningPeriod.MONTH)

/** Insights ([root]: on its own bottom bar item, without a back arrow), a month's recap, the recaps, or the "View all" of a top list. */
@Composable
fun ListeningPage(page: Page.ListeningPages, root: Boolean = false) {
    val nav = LocalNavigator.current
    val back: () -> Unit = { nav.back() }
    when (page) {
        Page.Listening -> InsightsPage(back.takeUnless { root }, nav::open)
        is Page.TopList -> TopListPage(page.kind, back, nav::open)
        is Page.Recap -> RecapPage(remember(page.month) { runCatching { YearMonth.parse(page.month) }.getOrNull() }, back, nav::open)
        Page.Recaps -> RecapsPage(back, nav::open)
    }
}

/**
 * The period switch, then the period's cards (plan part 1): its totals, For you (lane sg1), against the period before,
 * its top songs, artists, albums and genres (the first 5 of each with "View all"), when you listen, milestones, books
 * and podcasts, then the nudges, the monthly recaps and where it all comes from. With nothing heard in the period, the
 * empty state where the totals go; the nudges still show.
 */
@Composable
private fun InsightsPage(onBack: (() -> Unit)?, onOpen: (Page) -> Unit) {
    val insights = rememberInsights(shownPeriod)
    val recaps = rememberRecaps()
    val settings = rememberSettings()
    val sheets = rememberSongSheets()
    val periods = listOf(R.string.stats_week, R.string.stats_month, R.string.stats_year, R.string.stats_all_time).map { stringResource(it) }
    val list = rememberLazyListState()
    if (onBack == null) ReselectToTop(LocalNavigator.current.state.reselectsOf(DockTab.INSIGHTS)) { list.animateScrollToItem(0) }
    ListeningFrame(stringResource(R.string.stats_title), onBack) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "period", contentType = "period") {
                LyrifySegmented(periods, ListeningPeriod.entries.indexOf(shownPeriod), { shownPeriod = ListeningPeriod.entries[it] }, rowMotion(reduced).padding(horizontal = ScreenPadding))
            }
            if (insights != null) {
                val period = shownPeriod
                insightsCards(insights, { period.versus?.let { stringResource(it) }.orEmpty() }, settings, onOpen, sheets::openSong, reduced, viewAll = true)
                // The nudges look at all time, so they stay here, never on a month's recap.
                if (insights.neverPlayed.isNotEmpty()) {
                    item(key = "never", contentType = "card") { NeverPlayedCard(insights.neverPlayed, settings, { sheets.openSong(it.ref) }, rowMotion(reduced).padding(top = 12.dp)) }
                }
                if (insights.digDeeper.isNotEmpty()) item(key = "dig", contentType = "card") { DigDeeperCard(insights.digDeeper, settings, onOpen, rowMotion(reduced).padding(top = 12.dp)) }
                if (insights.forgotten.isNotEmpty()) {
                    item(key = "forgotten", contentType = "card") { ForgottenCard(insights.forgotten, settings, { sheets.openSong(it.ref) }, rowMotion(reduced).padding(top = 12.dp)) }
                }
            }
            if (!recaps.isNullOrEmpty()) {
                item(key = "recaps head", contentType = "head") {
                    Box(rowMotion(reduced)) { TopHead(stringResource(R.string.insights_recaps), recaps.size, if (recaps.size > RECAPS_SHOWN) ({ onOpen(Page.Recaps) }) else null) }
                }
                items(recaps.take(RECAPS_SHOWN), key = { "recap $it" }, contentType = { "recap" }) { month -> Box(rowMotion(reduced)) { RecapRow(month) { onOpen(Page.Recap(month.toString())) } } }
            }
            item(key = "private", contentType = "note") {
                Text(
                    stringResource(R.string.insights_private),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 24.dp),
                )
            }
        }
    }
    SongSheetsHost(sheets)
}

/**
 * The period's own cards, as Insights and a month's recap show them: the totals (else the empty state), For you on
 * Insights alone ([viewAll]), against the period before ([against]: its card's title), the top lists (with "View all"
 * on Insights), when you listen, milestones, books and podcasts.
 */
private fun LazyListScope.insightsCards(
    insights: Insights,
    against: @Composable () -> String,
    settings: Settings,
    onOpen: (Page) -> Unit,
    onSong: (SongRef) -> Unit,
    reduced: Boolean,
    viewAll: Boolean,
) {
    val totals = insights.totals
    if (totals == null) {
        item(key = "empty", contentType = "empty") {
            EmptyState(EmptyArt.Icon(R.drawable.ic_bar_chart), stringResource(R.string.stats_empty_title), rowMotion(reduced), text = stringResource(R.string.stats_empty_text))
        }
        return
    }
    item(key = "totals", contentType = "totals") { TotalsCard(totals, rowMotion(reduced)) }
    if (viewAll) item(key = "for you", contentType = "for you") { ForYouInsights(rowMotion(reduced)) }
    insights.versus?.let { versus ->
        item(key = "versus", contentType = "card") { VersusCard(versus, against(), rowMotion(reduced).padding(top = 12.dp)) }
    }
    TopKind.entries.forEach { kind ->
        val tops = topsOf(insights, kind)
        val (shown, more) = topPreview(tops)
        if (shown.isEmpty()) return@forEach
        item(key = "head $kind", contentType = "head") { Box(rowMotion(reduced)) { TopHead(stringResource(kind.title), tops.size, if (more && viewAll) ({ onOpen(Page.TopList(kind)) }) else null) } }
        itemsIndexed(shown, key = { _, top -> "$kind ${keyOf(top)}" }, contentType = { _, _ -> "top" }) { i, top ->
            Box(rowMotion(reduced)) { TopRow(top, i + 1, settings, onOpen, onSong) }
        }
    }
    if (insights.clock != null || insights.streaks != null) item(key = "clock", contentType = "card") { ClockCard(insights.clock, insights.streaks, rowMotion(reduced).padding(top = 12.dp)) }
    insights.milestones?.let { milestones -> item(key = "milestones", contentType = "card") { MilestonesCard(milestones, rowMotion(reduced).padding(top = 12.dp)) } }
    insights.spoken?.let { spoken -> item(key = "spoken", contentType = "card") { SpokenCard(spoken, rowMotion(reduced).padding(top = 12.dp)) } }
}

/**
 * A month's recap ([month]: none for a page kept from a damaged state): its picture with Share, then the month's own
 * cards set against the month before. The nudges stay on Insights.
 */
@Composable
private fun RecapPage(month: YearMonth?, onBack: () -> Unit, onOpen: (Page) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val shown = month ?: recapMonthOf(System.currentTimeMillis(), ZoneId.systemDefault())
    val recap = rememberRecap(shown)
    val settings = rememberSettings()
    val sheets = rememberSongSheets()
    val title = stringResource(R.string.insights_recap_title, monthText(shown))
    val before = monthText(shown.minusMonths(1))
    val picture = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    ListeningFrame(title, onBack) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            if (recap?.totals != null) {
                item(key = "picture", contentType = "picture") {
                    Column(rowMotion(reduced).padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Drawn on the page as it is shared, so what the user sees is what goes.
                        RecapPicture(title, recap, settings, Modifier.drawWithContent {
                            picture.record { this@drawWithContent.drawContent() }
                            drawLayer(picture)
                        })
                        LyrifyButton(
                            stringResource(R.string.insights_share),
                            onClick = { scope.launch { toasts.say(resources, shareRecapPicture(context, picture, shown, title)) } },
                            modifier = Modifier.fillMaxWidth(),
                            kind = ButtonKind.SECONDARY,
                            icon = R.drawable.ic_share,
                        )
                    }
                }
            }
            if (recap != null) insightsCards(recap, { stringResource(R.string.insights_versus_month_name, before) }, settings, onOpen, sheets::openSong, reduced, viewAll = false)
        }
    }
    SongSheetsHost(sheets)
}

/** Every month with a recap, the latest first; each opens its recap. */
@Composable
private fun RecapsPage(onBack: () -> Unit, onOpen: (Page) -> Unit) {
    val recaps = rememberRecaps()
    ListeningFrame(stringResource(R.string.insights_recaps), onBack) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            items(recaps.orEmpty(), key = { "recap $it" }, contentType = { "recap" }) { month -> Box(rowMotion(reduced)) { RecapRow(month) { onOpen(Page.Recap(month.toString())) } } }
        }
    }
}

/** A month's recap as a row: "October 2026", its tile, a chevron. */
@Composable
private fun RecapRow(month: YearMonth, onClick: () -> Unit) {
    val brand = LocalBrandColors.current
    NavRow(monthText(month), null, lead = { Tile(R.drawable.ic_bar_chart, brand.highlightContainer, brand.onHighlightContainer) }, onClick = onClick)
}

/** "View all" of one top list, for the period Insights shows. */
@Composable
private fun TopListPage(kind: TopKind, onBack: () -> Unit, onOpen: (Page) -> Unit) {
    val insights = rememberInsights(shownPeriod)
    val settings = rememberSettings()
    val sheets = rememberSongSheets()
    val tops = insights?.let { topsOf(it, kind) }.orEmpty()
    ListeningFrame(stringResource(kind.title), onBack) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "head", contentType = "head") { Box(rowMotion(reduced)) { TopHead(stringResource(shownPeriod.named), tops.size, onViewAll = null) } }
            if (insights != null && tops.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_bar_chart), stringResource(R.string.stats_empty_title), rowMotion(reduced), text = stringResource(R.string.stats_empty_text))
                }
            }
            // Prefixed: an artist named "head" or "empty" would take a fixed row's key, and two rows with one key crash the list.
            itemsIndexed(tops, key = { _, top -> "top ${keyOf(top)}" }, contentType = { _, _ -> "top" }) { i, top ->
                Box(rowMotion(reduced)) { TopRow(top, i + 1, settings, onOpen, sheets::openSong) }
            }
        }
    }
    SongSheetsHost(sheets)
}

/** A page's frame: its [title] with a back arrow, or Insights' own bar item's top bar without one ([onBack] null). */
@Composable
private fun ListeningFrame(title: String, onBack: (() -> Unit)?, content: @Composable (PaddingValues) -> Unit) {
    PageFrame(
        topBar = { under, backdrop ->
            if (onBack == null) {
                MainTopBar(DockTab.INSIGHTS, under, backdrop)
            } else {
                LyrifyTopBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    under = under,
                    backdrop = backdrop,
                    navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
                )
            }
        },
        content = content,
    )
}

/** A top list's name and length, a heading for TalkBack, with "View all" when it has more than it shows. */
@Composable
private fun TopHead(title: String, count: Int, onViewAll: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding + 4.dp, end = 8.dp, top = 16.dp).heightIn(min = 48.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        Text(formatCount(count), style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (onViewAll != null) LyrifyButton(stringResource(R.string.stats_view_all), onClick = onViewAll, kind = ButtonKind.GHOST, compact = true)
    }
}

/**
 * One row of a top list: a song (opens its song sheet), an artist (opens their page) or an album (opens its page), each
 * with its time heard and its plays.
 */
@Composable
internal fun TopRow(top: Top<*>, rank: Int, settings: Settings, onOpen: (Page) -> Unit, onSong: (SongRef) -> Unit) {
    val time = listenedText(top.heardMs)
    when (val item = top.item) {
        is Song -> TopRowView(rank, displayTitle(item, settings.library), lineOf(item.artist, time), top.plays, SongArt(item.path), MaterialTheme.shapes.small, stringResource(R.string.stats_song_options)) {
            onSong(item.ref)
        }
        is Artist -> TopRowView(rank, item.name, time, top.plays, item.songs.firstOrNull()?.let { SongArt(it.path) }, CircleShape, stringResource(R.string.stats_open_artist)) {
            onOpen(Page.Artist(item.name))
        }
        is Album -> TopRowView(rank, item.title, lineOf(item.artist, time), top.plays, item.songs.firstOrNull()?.let { SongArt(it.path) }, MaterialTheme.shapes.small, stringResource(R.string.stats_open_album)) {
            onOpen(Page.Album(item.key))
        }
        is Genre -> TopRowView(rank, item.name, time, top.plays, item.songs.firstOrNull()?.let { SongArt(it.path) }, MaterialTheme.shapes.small, stringResource(R.string.insights_open_genre)) {
            onOpen(Page.Genre(item.name))
        }
    }
}

/**
 * One TalkBack stop: "1. Name, 12 plays", or without a [rank] (Insights' nudges) "Name, 12 plays", or "Name, its line"
 * without [plays] either; the cover is decorative.
 */
@Composable
internal fun TopRowView(rank: Int?, title: String, line: String, plays: Int?, cover: Any?, shape: Shape, openLabel: String?, onClick: (() -> Unit)?) {
    val description = if (rank != null) {
        stringResource(R.string.stats_row, rank, title, playsNote(plays ?: 0))
    } else {
        stringResource(R.string.insights_row, title, plays?.let { playsNote(it) } ?: line)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            // The press tints a pill inside the gutter, not a band across the screen.
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        SongCover(cover, shape = shape)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (plays != null) Text(formatCount(plays), style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
    }
}

/** "Name · 12 min", or the time alone without a name. */
@Composable
private fun lineOf(name: String?, time: String): String = name?.trim()?.takeIf(String::isNotEmpty)?.let { stringResource(R.string.stats_row_line, it, time) } ?: time

// What the library's screens show of plays (L1 places these)

/** The card at the top of Most played: Insights' totals this month, its time and plays, as Insights shows them; opens Insights. */
@Composable
fun ListeningCard(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val insights = rememberInsights(ListeningPeriod.MONTH)
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    LyrifyCard(modifier, onClick = { nav.open(Page.Listening) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Tile(R.drawable.ic_bar_chart, brand.highlightContainer, brand.onHighlightContainer)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (insights != null) {
                    val totals = insights.totals
                    val line = stringResource(R.string.stats_card_line, stringResource(R.string.stats_this_month), listenedText(totals?.heardMs ?: 0), playsNote(totals?.plays ?: 0))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Song info's plays lines: [plays] "Played 23 times" or "Not played yet", [lastPlayed] as [lastPlayedText] says it. */
@Immutable
data class PlaysInfo(val plays: String, val lastPlayed: String)

/** [song]'s plays as Song info shows them, again whenever a play is written; null while they load. */
@Composable
fun songPlaysInfo(song: SongRef): PlaysInfo? {
    val context = LocalContext.current
    val written by remember(context) { StatsGraph.counter(context).written }.collectAsState()
    val loaded by produceState<Loaded?>(null, song, written) { value = Loaded(song, StatsGraph.stats(context).plays(song)) }
    val plays = loaded?.takeIf { it.song == song } ?: return null
    val count = plays.plays?.count ?: 0
    return PlaysInfo(
        plays = if (count == 0) stringResource(R.string.stats_not_played) else pluralStringResource(R.plurals.stats_played_times, count, formatCount(count)),
        lastPlayed = lastPlayedText(plays.plays?.lastPlayedAt),
    )
}

private class Loaded(val song: SongRef, val plays: SongPlays?)

/** "12 plays": Most played's note on a row. */
@Composable
fun playsNote(count: Int): String = pluralStringResource(R.plurals.stats_plays, count, formatCount(count))

/** When a song was last played: "Today 14:05", "Yesterday", "3 days ago", its date, or "Never" (Song info, Recently played). */
@Composable
fun lastPlayedText(at: Long?): String = when (val played = whenPlayed(at, System.currentTimeMillis(), ZoneId.systemDefault())) {
    WhenPlayed.Never -> stringResource(R.string.stats_never)
    is WhenPlayed.Today -> stringResource(R.string.stats_today, DateFormat.getTimeFormat(LocalContext.current).format(Date(played.at)))
    WhenPlayed.Yesterday -> stringResource(R.string.stats_yesterday)
    is WhenPlayed.DaysAgo -> pluralStringResource(R.plurals.stats_days_ago, played.days, played.days)
    is WhenPlayed.OnDate -> dateText(played.at)
}

/** A day as Song info shows it, in the phone's language ("4 Nov 2026"): the last play's, and the date added. */
@Composable
fun dateText(at: Long): String =
    DateUtils.formatDateTime(LocalContext.current, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)

/** Listening time as the pages say it: "42 min", "3 h 20 min". */
@Composable
fun listenedText(ms: Long): String {
    val time = listenedTime(ms)
    return if (time.hours == 0) {
        stringResource(R.string.stats_minutes, formatCount(time.minutes))
    } else {
        stringResource(R.string.stats_hours, formatCount(time.hours), formatCount(time.minutes))
    }
}

// The data behind them

/**
 * [period]'s Insights as of now, read again whenever a play or a book's is written, the library or Favourites change,
 * or the day turns (a period may end at midnight while the page is open); null until first read.
 */
@Composable
private fun rememberInsights(period: ListeningPeriod): Insights? = rememberStats(period) { context ->
    StatsGraph.stats(context).insights(period, System.currentTimeMillis(), ZoneId.systemDefault(), WeekFields.of(Locale.getDefault()).firstDayOfWeek)
}

/** [month]'s recap, read again as [rememberInsights] reads; null until first read. */
@Composable
private fun rememberRecap(month: YearMonth): Insights? = rememberStats(month) { context -> StatsGraph.stats(context).recap(month, System.currentTimeMillis(), ZoneId.systemDefault()) }

/** The months with a recap, the latest first, read again as [rememberInsights] reads; null until first read. */
@Composable
private fun rememberRecaps(): List<YearMonth>? = rememberStats(Unit) { context -> StatsGraph.stats(context).recaps(System.currentTimeMillis(), ZoneId.systemDefault()) }

/**
 * What [read] gives for [key], read only while it shows (its layer on its way to the screen or on it, the app on
 * screen): at once, then again at most every [REREAD_MS] as plays or a book's are written, and at once when the
 * library or Favourites change or the day turns. PlayStats reads off the main thread, only what changed.
 */
@Composable
private fun <T> rememberStats(key: Any, read: suspend (Context) -> T): T? {
    val context = LocalContext.current
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsState()
    val playlists by remember(context) { LibraryGraph.playlists(context).state }.collectAsState(null)
    val today = rememberToday()
    val shown = LocalLayerHeaded.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val value by produceState<T?>(null, key, library, playlists?.favouriteSongs, today, shown) {
        if (!shown) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            StatsGraph.writes(context).conflate().collect {
                value = read(context)
                delay(REREAD_MS)
            }
        }
    }
    return value
}

/** The phone's date, turning just after its midnight ([dayOf]): Insights' figures and the recap card's month. */
@Composable
internal fun rememberToday(): LocalDate {
    val today by produceState(dayOf(System.currentTimeMillis(), ZoneId.systemDefault()).date) {
        while (true) {
            val now = System.currentTimeMillis()
            delay((dayOf(now, ZoneId.systemDefault()).nextStart - now).coerceAtLeast(0) + MIDNIGHT_MARGIN_MS)
            value = dayOf(System.currentTimeMillis(), ZoneId.systemDefault()).date
        }
    }
    return today
}

@Composable
private fun rememberSettings(): Settings {
    val context = LocalContext.current
    val settings by remember(context) { Lyrify.settings(context).settings }.collectAsState(Settings())
    return settings
}

private fun topsOf(insights: Insights, kind: TopKind): List<Top<*>> = when (kind) {
    TopKind.SONGS -> insights.songs
    TopKind.ARTISTS -> insights.artists
    TopKind.ALBUMS -> insights.albums
    TopKind.GENRES -> insights.genres
}

private fun keyOf(top: Top<*>): String = when (val item = top.item) {
    is Song -> item.path
    is Artist -> item.name
    is Album -> item.key
    is Genre -> item.name
    else -> item.toString()
}

private val TopKind.title: Int
    get() = when (this) {
        TopKind.SONGS -> R.string.stats_top_songs
        TopKind.ARTISTS -> R.string.stats_top_artists
        TopKind.ALBUMS -> R.string.stats_top_albums
        TopKind.GENRES -> R.string.insights_top_genres
    }

/** Insights' card against the period before: "Against last week, up to now"; none for all time. */
private val ListeningPeriod.versus: Int?
    get() = when (this) {
        ListeningPeriod.WEEK -> R.string.insights_versus_week
        ListeningPeriod.MONTH -> R.string.insights_versus_month
        ListeningPeriod.YEAR -> R.string.insights_versus_year
        ListeningPeriod.ALL_TIME -> null
    }

/** "This week", "This month", "This year", "All time". */
private val ListeningPeriod.named: Int
    get() = when (this) {
        ListeningPeriod.WEEK -> R.string.stats_this_week
        ListeningPeriod.MONTH -> R.string.stats_this_month
        ListeningPeriod.YEAR -> R.string.stats_this_year
        ListeningPeriod.ALL_TIME -> R.string.stats_all_time
    }

/** Listening time in whole hours and minutes. */
data class ListenedTime(val hours: Int, val minutes: Int)

/** [ms] rounded to whole minutes, then split into hours and minutes. */
fun listenedTime(ms: Long): ListenedTime {
    val minutes = (ms.coerceIn(0, Long.MAX_VALUE / 2) + MINUTE_MS / 2) / MINUTE_MS
    return ListenedTime((minutes / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), (minutes % 60).toInt())
}

/** Insights read again at most this often while plays are written (one about every 15 s while music plays). */
private const val REREAD_MS: Long = 3_000

/** How many months Insights lists under Monthly recaps before "View all". */
private const val RECAPS_SHOWN: Int = 3

/** How many rows of a top list Insights shows before "View all". */
const val TOP_SHOWN: Int = 5

/** A top list's first [TOP_SHOWN] rows, and whether it has more ("View all"). */
fun <T> topPreview(list: List<T>): Pair<List<T>, Boolean> = list.take(TOP_SHOWN) to (list.size > TOP_SHOWN)

private const val MINUTE_MS: Long = 60_000

/** A moment past midnight, so the new day is surely there. */
private const val MIDNIGHT_MARGIN_MS: Long = 1_000
