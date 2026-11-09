package app.lyrify.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.LibraryGraph
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.settings.Settings
import app.lyrify.stats.Listening
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.SongPlays
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.Top
import app.lyrify.stats.WhenPlayed
import app.lyrify.stats.whenPlayed
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

// Your listening: its page, the "View all" of its top lists, and what the library's screens show of plays (L1 places
// them): the card on Most played, the rows' notes, and Song info's plays lines.

/** The period both pages show: Month at first, then the one last picked while the app runs. */
private var shownPeriod by mutableStateOf(ListeningPeriod.MONTH)

/** Your listening, or the "View all" of one of its top lists. */
@Composable
fun ListeningPage(page: Page.ListeningPages) {
    val nav = LocalNavigator.current
    when (page) {
        Page.Listening -> YourListening(onBack = { nav.back() }, onOpen = nav::open)
        is Page.TopList -> TopListPage(page.kind, onBack = { nav.back() }, onOpen = nav::open)
    }
}

/**
 * The period switch, then the period's listening time and plays, then its top songs, artists and albums, the first 5
 * of each with "View all". With no plays in the period, the empty state.
 */
@Composable
private fun YourListening(onBack: () -> Unit, onOpen: (Page) -> Unit) {
    val listening = rememberListening(shownPeriod)
    val settings = rememberSettings()
    val periods = listOf(R.string.stats_week, R.string.stats_month, R.string.stats_year, R.string.stats_all_time).map { stringResource(it) }
    ListeningFrame(stringResource(R.string.stats_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "period") {
                LyrifySegmented(periods, ListeningPeriod.entries.indexOf(shownPeriod), { shownPeriod = ListeningPeriod.entries[it] }, Modifier.padding(horizontal = ScreenPadding))
            }
            when {
                listening == null -> Unit
                listening.plays == 0 -> item(key = "empty") {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_bar_chart), stringResource(R.string.stats_empty_title), text = stringResource(R.string.stats_empty_text))
                }
                else -> {
                    item(key = "totals") { Totals(listening) }
                    TopKind.entries.forEach { kind ->
                        val tops = topsOf(listening, kind)
                        val (shown, more) = topPreview(tops)
                        if (shown.isEmpty()) return@forEach
                        item(key = "head $kind") { TopHead(stringResource(kind.title), tops.size, if (more) ({ onOpen(Page.TopList(kind)) }) else null) }
                        itemsIndexed(shown, key = { _, top -> "$kind ${keyOf(top)}" }) { i, top -> TopRow(top, i + 1, settings, onOpen) }
                    }
                }
            }
        }
    }
}

/** "View all" of one top list, for the period Your listening shows. */
@Composable
private fun TopListPage(kind: TopKind, onBack: () -> Unit, onOpen: (Page) -> Unit) {
    val listening = rememberListening(shownPeriod)
    val settings = rememberSettings()
    val tops = listening?.let { topsOf(it, kind) }.orEmpty()
    ListeningFrame(stringResource(kind.title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "head") { TopHead(stringResource(shownPeriod.named), tops.size, onViewAll = null) }
            if (listening != null && tops.isEmpty()) {
                item(key = "empty") {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_bar_chart), stringResource(R.string.stats_empty_title), text = stringResource(R.string.stats_empty_text))
                }
            }
            itemsIndexed(tops, key = { _, top -> keyOf(top) }) { i, top -> TopRow(top, i + 1, settings, onOpen) }
        }
    }
}

@Composable
private fun ListeningFrame(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
        content = content,
    )
}

/** The period's listening time and plays, side by side. */
@Composable
private fun Totals(listening: Listening) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 4.dp),
    ) {
        Total(stringResource(R.string.stats_time), listenedText(listening.heardMs), Modifier.weight(1f))
        Total(stringResource(R.string.stats_plays_label), formatCount(listening.plays), Modifier.weight(1f))
    }
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier) {
    LyrifyCard(modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleLarge.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
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
 * One row of a top list: a song (its sheet opens here once L1's song sheet lands), an artist (opens their page) or an
 * album (opens its page), each with its time heard and its plays.
 */
@Composable
private fun TopRow(top: Top<*>, rank: Int, settings: Settings, onOpen: (Page) -> Unit) {
    val time = listenedText(top.heardMs)
    when (val item = top.item) {
        is Song -> TopRowView(rank, displayTitle(item, settings.library), lineOf(item.artist, time), top.plays, SongArt(item.path), MaterialTheme.shapes.small, null, null)
        is Artist -> TopRowView(rank, item.name, time, top.plays, item.songs.firstOrNull()?.let { SongArt(it.path) }, CircleShape, stringResource(R.string.stats_open_artist)) {
            onOpen(Page.Artist(item.name))
        }
        is Album -> TopRowView(rank, item.title, lineOf(item.artist, time), top.plays, item.songs.firstOrNull()?.let { SongArt(it.path) }, MaterialTheme.shapes.small, stringResource(R.string.stats_open_album)) {
            onOpen(Page.Album(item.key))
        }
    }
}

/** One TalkBack stop: "1. Name, 12 plays"; the cover is decorative. */
@Composable
private fun TopRowView(rank: Int, title: String, line: String, plays: Int, cover: Any?, shape: Shape, openLabel: String?, onClick: (() -> Unit)?) {
    val description = stringResource(R.string.stats_row, rank, title, playsNote(plays))
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
        Text(formatCount(plays), style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
    }
}

/** "Name · 12 min", or the time alone without a name. */
@Composable
private fun lineOf(name: String?, time: String): String = name?.trim()?.takeIf(String::isNotEmpty)?.let { stringResource(R.string.stats_row_line, it, time) } ?: time

// What the library's screens show of plays (L1 places these)

/** The card at the top of Most played: Your listening this month, its time and plays; opens Your listening. */
@Composable
fun ListeningCard(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val listening = rememberListening(ListeningPeriod.MONTH)
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    LyrifyCard(modifier, onClick = { nav.open(Page.Listening) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(brand.highlightContainer)) {
                Icon(painterResource(R.drawable.ic_bar_chart), contentDescription = null, tint = brand.onHighlightContainer, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (listening != null) {
                    val line = stringResource(R.string.stats_card_line, stringResource(R.string.stats_this_month), listenedText(listening.heardMs), playsNote(listening.plays))
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

/** [period]'s listening as of now, read again whenever a play is written or the library changes; null until first read. */
@Composable
private fun rememberListening(period: ListeningPeriod): Listening? {
    val context = LocalContext.current
    val written by remember(context) { StatsGraph.counter(context).written }.collectAsState()
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsState()
    val listening by produceState<Listening?>(null, period, written, library) {
        value = StatsGraph.stats(context).listening(period, System.currentTimeMillis(), ZoneId.systemDefault(), WeekFields.of(Locale.getDefault()).firstDayOfWeek)
    }
    return listening
}

@Composable
private fun rememberSettings(): Settings {
    val context = LocalContext.current
    val settings by remember(context) { Lyrify.settings(context).settings }.collectAsState(Settings())
    return settings
}

private fun topsOf(listening: Listening, kind: TopKind): List<Top<*>> = when (kind) {
    TopKind.SONGS -> listening.songs
    TopKind.ARTISTS -> listening.artists
    TopKind.ALBUMS -> listening.albums
}

private fun keyOf(top: Top<*>): String = when (val item = top.item) {
    is Song -> item.path
    is Artist -> item.name
    is Album -> item.key
    else -> item.toString()
}

private val TopKind.title: Int
    get() = when (this) {
        TopKind.SONGS -> R.string.stats_top_songs
        TopKind.ARTISTS -> R.string.stats_top_artists
        TopKind.ALBUMS -> R.string.stats_top_albums
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

/** How many rows of a top list Your listening shows before "View all". */
const val TOP_SHOWN: Int = 5

/** A top list's first [TOP_SHOWN] rows, and whether it has more ("View all"). */
fun <T> topPreview(list: List<T>): Pair<List<T>, Boolean> = list.take(TOP_SHOWN) to (list.size > TOP_SHOWN)

private const val MINUTE_MS: Long = 60_000
