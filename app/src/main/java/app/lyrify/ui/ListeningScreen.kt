package app.lyrify.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import app.lyrify.stats.InsightsCards
import app.lyrify.stats.InsightsPage
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.SongPlays
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.Top
import app.lyrify.stats.WhenPlayed
import app.lyrify.stats.dayOf
import app.lyrify.stats.phoneCalendar
import app.lyrify.stats.whenPlayed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

// Insights, grown out of Your listening (S1; KJB 3.2; redone in v4 after the approved mockup): its page, the "View all"
// of its top lists, and what the library's screens show of plays (L1 places them): the rows' notes and Song info's plays
// lines. Its cards are in InsightsCards.kt and InsightsYou.kt, its story and pictures in InsightsStory.kt.

/** The period Insights and its top lists show: Month at first, then the one last picked while the app runs. */
private var shownPeriod by mutableStateOf(ListeningPeriod.MONTH)

/** Insights ([root]: on its own bottom bar item, without a back arrow), or the "View all" of a top list. */
@Composable
fun ListeningPage(page: Page.ListeningPages, root: Boolean = false) {
    val nav = LocalNavigator.current
    val back: () -> Unit = { nav.back() }
    when (page) {
        Page.Listening -> InsightsPage(back.takeUnless { root }, nav::open)
        is Page.TopList -> TopListPage(page.kind, back, nav::open)
    }
}

/**
 * Insights (the approved mockup, handoff/insights/preview-insights-v1.html): your story at the top and the period switch
 * under it, then the stories row, your time, For you (lane sg1), and a card for each thing the period has enough of
 * ([insightsCards]); each card arrives as it scrolls into view, and a new period swaps them in. With nothing heard in
 * the period, the empty state.
 */
@Composable
private fun InsightsPage(onBack: (() -> Unit)?, onOpen: (Page) -> Unit) {
    val period = shownPeriod
    val page = rememberPage(period)
    val settings = rememberSettings()
    val sheets = rememberSongSheets()
    val nav = LocalNavigator.current
    val list = rememberLazyListState()
    // The cards that came on screen, so none arrives twice; kept while the page is.
    val arrived = remember { HashSet<String>() }
    var picture by rememberSaveable { mutableStateOf<Picture?>(null) }
    var counting by rememberSaveable { mutableStateOf(false) }
    if (onBack == null) ReselectToTop(nav.state.reselectsOf(DockTab.INSIGHTS)) { list.animateScrollToItem(0) }
    val story = { nav.show(FullScreen.Story(period = period)) }
    ListeningFrame(
        stringResource(R.string.stats_title),
        onBack,
        actions = {
            StoryButton(story)
            var menu by remember { mutableStateOf(false) }
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { menu = true })
                LyrifyMenu(menu, { menu = false }) {
                    MenuRow(stringResource(R.string.insights_story_play_yours), R.drawable.ic_play_arrow_fill, {
                        menu = false
                        story()
                    })
                    if (page?.cards?.time != null) {
                        MenuRow(stringResource(R.string.insights_share), R.drawable.ic_share, {
                            menu = false
                            picture = Picture.SUMMARY
                        })
                    }
                    MenuRow(stringResource(R.string.insights_how_title), R.drawable.ic_info, {
                        menu = false
                        counting = true
                    })
                }
            }
        },
        below = { PeriodSwitch(period, page?.period) { shownPeriod = it } },
    ) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 28.dp)) {
            if (page == null) return@LazyColumn
            insightsCards(page, list, arrived, settings, onOpen, sheets::openSong, { picture = it }, reduced)
        }
    }
    picture?.let { kind -> page?.let { PictureSheet(kind, it, settings) { picture = null } } }
    if (counting) {
        HelpSheet(stringResource(R.string.insights_how_title), stringResource(R.string.insights_how_plays), stringResource(R.string.insights_how_time), stringResource(R.string.insights_private)) { counting = false }
    }
    SongSheetsHost(sheets)
}

/** A card of the Insights page, in the page's order ([insightsCardsOf]). */
enum class InsightsCardKind { STORIES, TIME, EMPTY, FOR_YOU, DAYS, CLOCK, ARTISTS, SONGS, ALBUMS, TASTE, HOW, MOMENTS, FOUND, FRIENDS, LYRICS, SEARCHES, SPOKEN, LIBRARY, TRAITS, MILESTONES, PRIVATE }

/**
 * The cards [cards] shows, in the page's order (the approved mockup's), each only with enough behind it: no empty boxes.
 * With no time in the period, the empty state stands in for your time and For you.
 */
fun insightsCardsOf(cards: InsightsCards): List<InsightsCardKind> = buildList {
    if (cards.time != null || cards.recaps.isNotEmpty()) add(InsightsCardKind.STORIES)
    if (cards.time != null) addAll(listOf(InsightsCardKind.TIME, InsightsCardKind.FOR_YOU)) else add(InsightsCardKind.EMPTY)
    if (cards.days != null) add(InsightsCardKind.DAYS)
    if (cards.clock != null) add(InsightsCardKind.CLOCK)
    if (cards.artists?.rows.orEmpty().isNotEmpty()) add(InsightsCardKind.ARTISTS)
    if (cards.songs?.rows.orEmpty().isNotEmpty()) add(InsightsCardKind.SONGS)
    if (cards.albums?.rows.orEmpty().isNotEmpty()) add(InsightsCardKind.ALBUMS)
    if (cards.taste != null) add(InsightsCardKind.TASTE)
    if (cards.how != null) add(InsightsCardKind.HOW)
    if (cards.moments.isNotEmpty()) add(InsightsCardKind.MOMENTS)
    if (cards.found != null || cards.comebacks.isNotEmpty() || cards.forgotten.isNotEmpty()) add(InsightsCardKind.FOUND)
    if (cards.friends?.matches.orEmpty().isNotEmpty()) add(InsightsCardKind.FRIENDS)
    if (cards.lyrics != null || cards.favourites != null) add(InsightsCardKind.LYRICS)
    if (cards.searches != null) add(InsightsCardKind.SEARCHES)
    if (cards.spoken != null) add(InsightsCardKind.SPOKEN)
    if (cards.library != null) add(InsightsCardKind.LIBRARY)
    if (cards.traits.isNotEmpty()) add(InsightsCardKind.TRAITS)
    if (cards.milestones != null) add(InsightsCardKind.MILESTONES)
    add(InsightsCardKind.PRIVATE)
}

/**
 * The page's cards in the mockup's order, each only when its period has enough of it, each arriving as it scrolls in
 * ([Arriving]): the stories row, your time, For you, every day you listened, your day on a record, the top artists,
 * songs and albums, your taste, how you listen, moments, new to you, you and your friends, read along with new
 * favourites and your searches, books and podcasts, your library, your listening type, milestones and badges, and where
 * it all comes from. With nothing heard in the period, the empty state in place of the time.
 */
@Suppress("LongParameterList")
private fun LazyListScope.insightsCards(
    page: InsightsPage,
    list: LazyListState,
    arrived: MutableSet<String>,
    settings: Settings,
    onOpen: (Page) -> Unit,
    onSong: (SongRef) -> Unit,
    onPicture: (Picture) -> Unit,
    reduced: Boolean,
) {
    val cards = page.cards
    val period = page.period
    insightsCardsOf(cards).forEach { key ->
        item(key = key.name, contentType = key.name) {
            when (key) {
                InsightsCardKind.STORIES -> StoriesRow(page, rowMotion(reduced))
                InsightsCardKind.EMPTY -> EmptyState(EmptyArt.Icon(R.drawable.ic_bar_chart), stringResource(R.string.stats_empty_title), rowMotion(reduced), text = stringResource(R.string.stats_empty_text))
                InsightsCardKind.FOR_YOU -> ForYouInsights(rowMotion(reduced))
                InsightsCardKind.PRIVATE -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 8.dp, end = ScreenPadding + 8.dp, top = 20.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                    Text(stringResource(R.string.insights_private), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Each card arrives as it scrolls in; insightsCardsOf showed it only with its data.
                else -> Arriving(key.name, list, arrived, period.kind, rowMotion(reduced).padding(top = 12.dp)) {
                    when (key) {
                        InsightsCardKind.TIME -> cards.time?.let { YourTime(it, period) { onPicture(Picture.SUMMARY) } }
                        InsightsCardKind.DAYS -> cards.days?.let { EveryDay(it, period) }
                        InsightsCardKind.CLOCK -> cards.clock?.let { DayOnARecord(it) }
                        InsightsCardKind.ARTISTS -> cards.artists?.let { TopArtists(it, period, onOpen) }
                        InsightsCardKind.SONGS -> cards.songs?.let { TopSongs(it, settings, onOpen, onSong) }
                        InsightsCardKind.ALBUMS -> cards.albums?.let { TopAlbums(it, onOpen) { onPicture(Picture.ALBUMS) } }
                        InsightsCardKind.TASTE -> cards.taste?.let { YourTaste(it) }
                        InsightsCardKind.HOW -> cards.how?.let { HowYouListen(it, settings, onSong) }
                        InsightsCardKind.MOMENTS -> Moments(cards.moments, settings)
                        InsightsCardKind.FOUND -> NewToYou(cards.found, cards.comebacks, cards.forgotten, period, settings, onSong)
                        InsightsCardKind.FRIENDS -> cards.friends?.let { YouAndFriends(it, settings) }
                        InsightsCardKind.LYRICS -> ReadAlongAndHearts(cards.lyrics, cards.favourites, period, settings)
                        InsightsCardKind.SEARCHES -> cards.searches?.let { YourSearches(it) }
                        InsightsCardKind.SPOKEN -> cards.spoken?.let { BooksAndPodcasts(it) }
                        InsightsCardKind.LIBRARY -> cards.library?.let { YourLibrary(it, period, settings, onOpen) }
                        InsightsCardKind.TRAITS -> ListeningType(cards.traits)
                        InsightsCardKind.MILESTONES -> cards.milestones?.let { MilestonesAndBadges(it, period) }
                        InsightsCardKind.STORIES, InsightsCardKind.EMPTY, InsightsCardKind.FOR_YOU, InsightsCardKind.PRIVATE -> Unit
                    }
                }
            }
        }
    }
}

/** Play your story, in the top bar: a play icon in a ring of coral, gold and violet (the mockup's). */
@Composable
private fun StoryButton(onClick: () -> Unit) {
    val brand = LocalBrandColors.current
    ChromeIconButton(stringResource(R.string.insights_story_play_yours), onClick) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(Brush.sweepGradient(listOf(brand.highlight, HuddleGold, FriendColors[2], brand.highlight)), radius = size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
        }
        Icon(painterResource(R.drawable.ic_play_arrow_fill), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
    }
}

/**
 * The period switch under the title (the mockup's: an ink thumb that slides with a small overshoot), then the period's
 * days and what it is set against ([rangeText]), said again by TalkBack when it changes.
 */
@Composable
private fun PeriodSwitch(period: ListeningPeriod, shown: PagePeriod?, onPick: (ListeningPeriod) -> Unit) {
    val periods = listOf(R.string.stats_week, R.string.stats_month, R.string.stats_year, R.string.stats_all_time).map { stringResource(it) }
    Column(Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 10.dp)) {
        LyrifySegmented(periods, ListeningPeriod.entries.indexOf(period), { onPick(ListeningPeriod.entries[it]) }, ink = true)
        Text(
            shown?.takeIf { it.kind == period }?.let { rangeText(it) }.orEmpty(),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * "Mon 23 Nov to Thu 26 Nov · against last week, up to now", "1 Jan to 26 Nov · against 2025, up to 26 Nov", "Since
 * 14 Feb 2025": the days [period] covers so far and what it is set against.
 */
@Composable
private fun rangeText(period: PagePeriod): String {
    val week = period.kind == ListeningPeriod.WEEK
    if (period.bounds == null) return stringResource(R.string.insights_range_all, dayText(period.firstDay, year = true))
    val days = stringResource(R.string.insights_range, dayText(period.firstDay, weekday = week), dayText(period.lastDay, weekday = week))
    if (period.before == null) return days
    val until = period.beforeUntil?.takeIf { period.going && !week }?.let { dayText(dayOf(it - 1, ZoneId.systemDefault()).date) }
    val against = when {
        until != null -> stringResource(R.string.insights_against_until, beforeName(period), until)
        period.going -> stringResource(R.string.insights_against_now, beforeName(period))
        else -> stringResource(R.string.insights_against, beforeName(period))
    }
    return stringResource(R.string.stats_row_line, days, against)
}

/** "View all" of one top list, for the period Insights shows. */
@Composable
private fun TopListPage(kind: TopKind, onBack: () -> Unit, onOpen: (Page) -> Unit) {
    val page = rememberPage(shownPeriod)
    val settings = rememberSettings()
    val sheets = rememberSongSheets()
    val tops = page?.let { topsOf(it.cards, kind) }.orEmpty()
    ListeningFrame(stringResource(kind.title), onBack) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "head", contentType = "head") { Box(rowMotion(reduced)) { TopHead(stringResource(shownPeriod.named), tops.size, onViewAll = null) } }
            if (page != null && tops.isEmpty()) {
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

/**
 * A page's frame: its [title] with a back arrow, or Insights' own bar item's top bar without one ([onBack] null), with
 * its [actions] at the end and [below] under the line (Insights' period switch).
 */
@Composable
private fun ListeningFrame(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    PageFrame(
        topBar = { under, backdrop ->
            if (onBack == null) {
                MainTopBar(DockTab.INSIGHTS, under, backdrop, actions, below)
            } else {
                LyrifyTopBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    under = under,
                    backdrop = backdrop,
                    navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
                    actions = actions,
                    below = below,
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
 * [period]'s Insights page as of now, read again whenever a play or a book's is written, the library or Favourites
 * change, or the day turns (a period may end at midnight while the page is open); the last one read meanwhile, null
 * until the first.
 */
@Composable
private fun rememberPage(period: ListeningPeriod): InsightsPage? = rememberStats(period) { context ->
    StatsGraph.stats(context).page(period, System.currentTimeMillis(), phoneCalendar())
}

/**
 * What [read] gives for [key], read only while it shows (its layer on its way to the screen or on it, the app on
 * screen): at once, then again at most every [REREAD_MS] as plays or a book's are written, and at once when the
 * library or Favourites change or the day turns. PlayStats reads off the main thread, only what changed.
 */
@Composable
internal fun <T> rememberStats(key: Any, read: suspend (Context) -> T): T? {
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

/** The settings now; the defaults while they are read (here, and Suggestions' screens and sheets). */
@Composable
internal fun rememberSettings(): Settings {
    val context = LocalContext.current
    val settings by remember(context) { Lyrify.settings(context).settings }.collectAsState(Settings())
    return settings
}

private fun topsOf(cards: InsightsCards, kind: TopKind): List<Top<*>> = when (kind) {
    TopKind.SONGS -> cards.songs?.rows.orEmpty().map { it.top }
    TopKind.ARTISTS -> cards.artists?.rows.orEmpty().map { it.top }
    TopKind.ALBUMS -> cards.albums?.rows.orEmpty().map { it.top }
    TopKind.GENRES -> cards.taste?.genres.orEmpty()
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

/** How many rows of a top list Insights shows before "View all". */
const val TOP_SHOWN: Int = 5

/** A top list's first [TOP_SHOWN] rows, and whether it has more ("View all"). */
fun <T> topPreview(list: List<T>): Pair<List<T>, Boolean> = list.take(TOP_SHOWN) to (list.size > TOP_SHOWN)

private const val MINUTE_MS: Long = 60_000

/** A moment past midnight, so the new day is surely there. */
private const val MIDNIGHT_MARGIN_MS: Long = 1_000
