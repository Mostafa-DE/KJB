package app.lyrify.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.Settings
import app.lyrify.stats.DigDeeper
import app.lyrify.stats.Insights
import app.lyrify.stats.ListeningClock
import app.lyrify.stats.MilestoneKind
import app.lyrify.stats.Milestones
import app.lyrify.stats.Spoken
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.Streaks
import app.lyrify.stats.Top
import app.lyrify.stats.Totals
import app.lyrify.stats.Versus
import app.lyrify.stats.recapDue
import app.lyrify.stats.recapMonthOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar
import kotlin.math.roundToInt

// Insights' cards (KJB 3.2, handoff/suggest/plan.md part 1): this period against the one before, the listening clock and
// streaks, milestones, books and podcasts, the nudges (never played, dig deeper, forgotten favourites), the recap card
// on the start screen and the recap's picture. The page and its top lists are in ListeningScreen.kt.

/** One of Insights' cards: its [title] (a heading for TalkBack), an [action] at its end, then what it holds. */
@Composable
internal fun InsightsCard(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    LyrifyCard(modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            action?.invoke()
        }
        Column(Modifier.padding(bottom = 12.dp)) { content() }
    }
}

/** A line of a card, 16 dp in. */
@Composable
private fun CardLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

/** The period's time heard and plays, side by side, then the songs, artists and albums played. */
@Composable
internal fun TotalsCard(totals: Totals, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Total(stringResource(R.string.stats_time), listenedText(totals.heardMs), Modifier.weight(1f))
            Total(stringResource(R.string.stats_plays_label), formatCount(totals.plays), Modifier.weight(1f))
        }
        if (totals.songs > 0) {
            val line = listOf(
                pluralStringResource(R.plurals.insights_songs, totals.songs, formatCount(totals.songs)),
                pluralStringResource(R.plurals.insights_artists, totals.artists, formatCount(totals.artists)),
                pluralStringResource(R.plurals.insights_albums, totals.albums, formatCount(totals.albums)),
            ).joinToString(" · ")
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** A labelled value on a small card of its own: the totals' and this period against the one before. */
@Composable
internal fun Total(label: String, value: String, modifier: Modifier) {
    LyrifyCard(modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleLarge.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** This period against the one before ([title]: "Against last week, up to now", "Against September"): how much more or less of each. */
@Composable
internal fun VersusCard(versus: Versus, title: String, modifier: Modifier = Modifier) {
    InsightsCard(title, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            Total(stringResource(R.string.stats_time), changeText(percentChange(versus.heardMs, versus.beforeHeardMs), percent = true), Modifier.weight(1f))
            Total(stringResource(R.string.stats_plays_label), changeText((versus.plays - versus.beforePlays).toLong(), percent = false), Modifier.weight(1f))
        }
    }
}

/** How much [now] differs from [before], in whole percent of it. */
internal fun percentChange(now: Long, before: Long): Long = if (before <= 0) 0 else ((now - before) * 100.0 / before).roundToInt().toLong()

/** "40% more", "12 less", "The same". */
@Composable
private fun changeText(change: Long, percent: Boolean): String {
    val amount = if (percent) stringResource(R.string.insights_percent, formatCount(kotlin.math.abs(change).toInt())) else formatCount(kotlin.math.abs(change).toInt())
    return when {
        change > 0 -> stringResource(R.string.insights_more, amount)
        change < 0 -> stringResource(R.string.insights_less, amount)
        else -> stringResource(R.string.insights_same)
    }
}

/** When you listen: the time heard by hour of the day, the hour heard most, and the streaks. Nothing without either. */
@Composable
internal fun ClockCard(clock: ListeningClock?, streaks: Streaks?, modifier: Modifier = Modifier) {
    if (clock == null && streaks == null) return
    InsightsCard(stringResource(R.string.insights_clock_title), modifier) {
        if (clock != null) {
            val peak = hourText(clock.peak)
            HourBars(clock, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            CardLine(stringResource(R.string.insights_clock_peak, peak))
        }
        if (streaks != null) {
            if (streaks.longest >= 2) CardLine(pluralStringResource(R.plurals.insights_streak_longest, streaks.longest, streaks.longest))
            streaks.current?.takeIf { it >= 2 }?.let { CardLine(pluralStringResource(R.plurals.insights_streak_now, it, it)) }
        }
    }
}

/** 24 bars, midnight to 23:00, the hour heard most in the brand's colour; decorative, as the line under it says it. */
@Composable
private fun HourBars(clock: ListeningClock, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val most = clock.hours.maxOrNull()?.takeIf { it > 0 } ?: 1
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val gap = 3.dp.toPx()
            val width = (size.width - gap * (clock.hours.size - 1)) / clock.hours.size
            clock.hours.forEachIndexed { hour, ms ->
                val height = (size.height * ms / most).coerceAtLeast(2.dp.toPx())
                drawRoundRect(
                    color = if (hour == clock.peak) brand.highlight else colors.onSurfaceVariant.copy(alpha = 0.35f),
                    topLeft = Offset(hour * (width + gap), size.height - height),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2, width / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf(0, 6, 12, 18).forEach { hour ->
                Text(hourText(hour), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** [hour] o'clock as the phone writes the time: "22:00" or "10:00 PM". */
@Composable
private fun hourText(hour: Int): String {
    val context = LocalContext.current
    val at = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
    }
    return DateFormat.getTimeFormat(context).format(at.time)
}

/** The milestones reached in the period, the latest first, each with its day, and the next one while the period goes on. */
@Composable
internal fun MilestonesCard(milestones: Milestones, modifier: Modifier = Modifier) {
    InsightsCard(stringResource(R.string.insights_milestones_title), modifier) {
        milestones.reached.forEach { reached ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.heightIn(min = 48.dp).padding(horizontal = 16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_flag), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(20.dp))
                Text(milestoneText(reached.kind, reached.count), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(dateText(reached.at), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        milestones.next?.let { next ->
            val left = if (next.kind == MilestoneKind.PLAYS) formatCount(next.left.toInt()) else listenedText(next.left)
            CardLine(stringResource(R.string.insights_next, milestoneText(next.kind, next.count), left))
        }
    }
}

/** "1,000 plays", "100 hours heard". */
@Composable
private fun milestoneText(kind: MilestoneKind, count: Int): String = when (kind) {
    MilestoneKind.PLAYS -> playsNote(count)
    MilestoneKind.HOURS -> pluralStringResource(R.plurals.insights_hours, count, formatCount(count))
}

/** Books and podcasts: the time heard of each, each only when there is some. */
@Composable
internal fun SpokenCard(spoken: Spoken, modifier: Modifier = Modifier) {
    InsightsCard(stringResource(R.string.insights_spoken_title), modifier) {
        if (spoken.booksMs > 0) CardLine(stringResource(R.string.insights_books_time, listenedText(spoken.booksMs)))
        if (spoken.podcastsMs > 0) CardLine(stringResource(R.string.insights_podcasts_time, listenedText(spoken.podcastsMs)))
    }
}

/** How many songs no play counted for, the newest added three, and Shuffle for all of them. */
@Composable
internal fun NeverPlayedCard(songs: List<Song>, settings: Settings, onSong: (Song) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name = stringResource(R.string.insights_never_title)
    InsightsCard(name, modifier, action = { ShuffleButton { shuffle(context, songs, NEVER_PLAYED_KEY, name) } }) {
        CardLine(pluralStringResource(R.plurals.insights_never_count, songs.size, formatCount(songs.size)))
        songs.take(NUDGE_ROWS).forEach { song -> NudgeRow(SongArt(song.path), displayTitle(song, settings.library), song.artist.orEmpty(), null, stringResource(R.string.stats_song_options)) { onSong(song) } }
    }
}

/** Albums played in the period with songs never played: each opens its album. */
@Composable
internal fun DigDeeperCard(digs: List<DigDeeper>, settings: Settings, onOpen: (Page) -> Unit, modifier: Modifier = Modifier) {
    InsightsCard(stringResource(R.string.insights_dig_title), modifier) {
        CardLine(stringResource(R.string.insights_dig_text))
        digs.forEach { dig ->
            val line = stringResource(
                R.string.stats_row_line,
                stringResource(R.string.insights_dig_played, displayTitle(dig.played, settings.library)),
                pluralStringResource(R.plurals.insights_dig_unplayed, dig.unplayed.size, formatCount(dig.unplayed.size)),
            )
            NudgeRow(dig.album.songs.firstOrNull()?.let { SongArt(it.path) }, dig.album.title, line, null, stringResource(R.string.stats_open_album)) { onOpen(Page.Album(dig.album.key)) }
        }
    }
}

/** Songs played a lot (or hearted) that went quiet, with their plays and when they last counted, and Shuffle for them. */
@Composable
internal fun ForgottenCard(tops: List<Top<Song>>, settings: Settings, onSong: (Song) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name = stringResource(R.string.insights_forgotten_title)
    InsightsCard(name, modifier, action = { ShuffleButton { shuffle(context, tops.map { it.item }, FORGOTTEN_KEY, name) } }) {
        CardLine(stringResource(R.string.insights_forgotten_text))
        tops.forEach { top ->
            val line = stringResource(R.string.insights_last_played, top.lastPlayedAt?.let { dateText(it) }.orEmpty())
            NudgeRow(SongArt(top.item.path), displayTitle(top.item, settings.library), line, top.plays, stringResource(R.string.stats_song_options)) { onSong(top.item) }
        }
    }
}

@Composable
private fun ShuffleButton(onClick: () -> Unit) {
    LyrifyButton(stringResource(R.string.lib_shuffle), onClick = onClick, kind = ButtonKind.GHOST, icon = R.drawable.ic_shuffle, compact = true)
}

/** A row of a nudge: its cover, name and line, its plays at the end when it has some; one TalkBack stop. */
@Composable
private fun NudgeRow(cover: Any?, title: String, line: String, plays: Int?, openLabel: String, onClick: () -> Unit) {
    TopRowView(rank = null, title = title, line = line, plays = plays, cover = cover, shape = MaterialTheme.shapes.small, openLabel = openLabel, onClick = onClick)
}

/** A new queue of [songs], shuffled, from Insights ([key]: never played or forgotten favourites, as a list of its own). */
private fun shuffle(context: Context, songs: List<Song>, key: String, name: String) =
    Lyrify.playback(context).shuffle(songs.map { it.ref }, QueueSource(SourceKind.PICKED, key, name))

private const val NEVER_PLAYED_KEY: String = "insights-never-played"
private const val FORGOTTEN_KEY: String = "insights-forgotten"

/** How many songs a nudge card shows. */
private const val NUDGE_ROWS = 3

// The monthly recap

/** "October", or "October 2025" for a month of another year than [today]'s. */
@Composable
internal fun monthText(month: YearMonth, today: YearMonth = YearMonth.now()): String {
    val context = LocalContext.current
    val at = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val year = if (month.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
    return DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or year)
}

/**
 * The start screen's recap card (plan Q1.2), the first row of Library's [tab], shown only on the tab Library opens on
 * (the first in its order, or "Open here first"): from the first open of a month, "Your October" while that month has
 * a recap and the user has not opened or closed it. A tap opens the recap; the X, a swipe or TalkBack's action closes
 * it. Either way it is gone for good, and the recap stays on the Insights page.
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
                nav.open(Page.Recap(month.toString()))
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

/**
 * The recap's picture: a clean card of the month (its time heard and plays, the songs, artists and albums played, the
 * top five songs and the top artist), drawn on the page as it is shared, so what the user sees is what goes. No covers:
 * the picture holds only words KJB writes.
 */
@Composable
internal fun RecapPicture(title: String, insights: Insights, settings: Settings, modifier: Modifier = Modifier) {
    val totals = insights.totals ?: return
    val brand = LocalBrandColors.current
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerLow).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PictureFigure(listenedText(totals.heardMs), stringResource(R.string.stats_time))
            PictureFigure(formatCount(totals.plays), stringResource(R.string.stats_plays_label))
        }
        if (totals.songs > 0) {
            Text(
                listOf(
                    pluralStringResource(R.plurals.insights_songs, totals.songs, formatCount(totals.songs)),
                    pluralStringResource(R.plurals.insights_artists, totals.artists, formatCount(totals.artists)),
                    pluralStringResource(R.plurals.insights_albums, totals.albums, formatCount(totals.albums)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        if (insights.songs.isNotEmpty()) {
            Text(stringResource(R.string.stats_top_songs), style = MaterialTheme.typography.titleSmall, color = brand.highlight)
            insights.songs.take(TOP_SHOWN).forEachIndexed { i, top ->
                val name = displayTitle(top.item, settings.library)
                Text(
                    stringResource(R.string.stats_row, i + 1, name, top.item.artist?.takeIf { it.isNotBlank() } ?: playsNote(top.plays)),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        insights.artists.firstOrNull()?.let { top ->
            Text(stringResource(R.string.insights_top_artist, top.item.name), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.insights_picture_footer), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun PictureFigure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium.tabular(), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * Shares [picture] (the recap's, as drawn on the page) through the phone's share sheet as a PNG named for [month], with
 * [title] as the subject. Nothing is uploaded: the user picks where it goes.
 */
internal suspend fun shareRecapPicture(context: Context, picture: GraphicsLayer, month: YearMonth, title: String): Shared {
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
        withContext(Dispatchers.IO) { shareCopy(context.cacheDir, "kjb-recap-$month.png", png, System.currentTimeMillis()) }
    } catch (_: IOException) {
        return Shared.NoPicture
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + SHARE_AUTHORITY, file)
    return launchShare(1) { context.startActivity(shareSheet(recapImageShare(uri.toString(), title), context.packageName)) }
}

/** PNG ignores the quality; the platform asks for one. */
private const val PNG_QUALITY = 100
