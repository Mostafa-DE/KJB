package app.lyrify.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.Settings
import app.lyrify.stats.Ends
import app.lyrify.stats.HowCard
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.Loyalty
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.Ranked
import app.lyrify.stats.TasteCard
import app.lyrify.stats.Top
import app.lyrify.stats.TopCard
import java.time.YearMonth
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// Insights' cards of what you listen to (the approved mockup, handoff/insights/preview-insights-v1.html): the top
// artists, songs and albums, your taste, how you listen and moments. Their frame, motion and words are in
// InsightsCards.kt.

// The top lists

/**
 * Top artists (the mockup's race): number 1 big in a gold ring with its medal, its plays and time, and how long it has
 * been number 1; then 2 to 5 as bars racing in (900 ms ease-rise, 110 ms apart) with their ups and downs. Each opens its
 * artist.
 */
@Composable
internal fun TopArtists(card: TopCard<Artist>, period: PagePeriod, onOpen: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val (shown, more) = topPreview(card.rows)
    val first = shown.first()
    InsightsCard {
        CardHead(stringResource(R.string.stats_top_artists), action = if (more) ({ ViewAll { onOpen(Page.TopList(TopKind.ARTISTS)) } }) else null)
        val description = stringResource(R.string.stats_row, 1, first.top.item.name, playsNote(first.top.plays)) + placeWords(1, first.before, card.compared)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = stringResource(R.string.stats_open_artist)) { onOpen(Page.Artist(first.top.item.name)) }.semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Box(Modifier.size(112.dp).clearAndSetSemantics {}) {
                Box(Modifier.matchParentSize().padding(5.dp).border(2.dp, HuddleGold.copy(alpha = 0.9f), CircleShape).padding(5.dp)) {
                    SongCover(SongArt(first.cover.path, first.cover.ref.mediaStoreId), Modifier.fillMaxWidth().aspectRatio(1f), size = 92.dp, shape = CircleShape)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.align(Alignment.BottomStart).size(36.dp).border(3.dp, colors.surfaceContainerLow, CircleShape).padding(1.dp).background(HuddleGold, CircleShape)) {
                    Text("1", style = display(18, 18, weight = 700), color = OnHuddleGold)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(first.top.item.name, style = display(26, 30, -0.02, weight = 700), color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                Counted(first.top.plays.toLong(), MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), colors.onSurfaceVariant, Modifier.clearAndSetSemantics {}) {
                    AnnotatedString(stringResource(R.string.stats_row_line, playsNote(it.toInt()), listenedText(first.top.heardMs)))
                }
                card.loyalty?.let { loyalty ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 8.dp).background(colors.surface, RoundedCornerShape(12.dp)).padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_emoji_events), contentDescription = null, tint = insights.goldInk, modifier = Modifier.size(16.dp))
                        // "Your number 1 for 6 weeks running", "in 8 of 11 months", "since June 2025".
                        Text(
                            when (loyalty) {
                                is Loyalty.Running -> pluralStringResource(if (period.kind == ListeningPeriod.WEEK) R.plurals.insights_loyal_weeks else R.plurals.insights_loyal_months_running, loyalty.periods, loyalty.periods)
                                is Loyalty.MonthsOf -> pluralStringResource(R.plurals.insights_loyal_months, loyalty.of, loyalty.months, loyalty.of)
                                is Loyalty.Since -> stringResource(R.string.insights_loyal_since, monthText(loyalty.month, java.time.YearMonth.of(0, 1)))
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = insights.goldInk)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 16.dp)) {
            shown.drop(1).forEachIndexed { n, row ->
                val place = n + 2
                val clock = LocalArrivalClock.current
                val rowDescription = stringResource(R.string.stats_row, place, row.top.item.name, playsNote(row.top.plays)) + placeWords(place, row.before, card.compared)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = stringResource(R.string.stats_open_artist)) { onOpen(Page.Artist(row.top.item.name)) }
                        .semantics(mergeDescendants = true) { contentDescription = rowDescription }
                        .heightIn(min = 52.dp),
                ) {
                    Text(place.toString(), style = RankStyle, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(22.dp).clearAndSetSemantics {})
                    SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), size = 40.dp, shape = CircleShape)
                    Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(row.top.item.name, style = RowTitle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            PlaceChange(place, row.before, card.compared)
                        }
                        val share = row.top.plays.toFloat() / first.top.plays.coerceAtLeast(1)
                        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                            val grown = phase(clock.ms(), RACE_DELAY_MS + n * RACE_STEP_MS, RACE_MS, Motion.Rise)
                            drawRoundRect(if (n == 0) insights.series[1] else insights.series[0], size = Size(size.width * share * grown, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.clearAndSetSemantics {}) {
                        Text(formatCount(row.top.plays), style = CountStyle, color = colors.onSurface)
                        Text(pluralStringResource(R.plurals.insights_plays_word, row.top.plays), style = FigureLabel.copy(fontSize = 11.5.sp), color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Top songs: the top song's cover big and tilted with its play button, "Your top song", its name, artist and plays
 * counted up; then 2 to 5 with their covers, ups and downs, artist and time, and a short bar of their plays; then Play
 * your top 25. A row opens its song sheet.
 */
@Composable
internal fun TopSongs(card: TopCard<Song>, settings: Settings, onOpen: (Page) -> Unit, onSong: (SongRef) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chrome = LocalChromeColors.current
    val brand = LocalBrandColors.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val playback = rememberPlayback()
    val (shown, more) = topPreview(card.rows)
    val first = shown.first()
    val top25 = card.rows.take(PLAY_TOP).map { it.top.item.ref }
    val source = QueueSource(SourceKind.PICKED, TOP_SONGS_KEY, stringResource(R.string.stats_top_songs))
    InsightsCard {
        CardHead(stringResource(R.string.stats_top_songs), action = if (more) ({ ViewAll { onOpen(Page.TopList(TopKind.SONGS)) } }) else null)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(134.dp).padding(end = 10.dp, bottom = 10.dp)) {
                SongCover(SongArt(first.cover.path, first.cover.ref.mediaStoreId), Modifier.rotate(-3f), size = 124.dp, shape = RoundedCornerShape(22.dp))
                // Play on the cover's corner, ringed in the card's colour.
                RoundButton(
                    R.drawable.ic_play_arrow_fill,
                    stringResource(R.string.insights_play_song, displayTitle(first.top.item, settings.library)),
                    modifier = Modifier.align(Alignment.BottomEnd).offset(10.dp, 10.dp).border(4.dp, colors.surfaceContainerLow, CircleShape).padding(4.dp),
                    container = chrome.action,
                    tint = chrome.onAction,
                ) { playback.play(top25, 0, source) }
            }
            Column(Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.stats_song_options)) { onSong(first.top.item.ref) }.semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.insights_top_song).uppercase(), style = CardKicker.copy(fontSize = 11.5.sp), color = brand.highlight)
                Text(displayTitle(first.top.item, settings.library), style = display(24, 28, -0.02, weight = 700), color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    first.top.item.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                    SongLength(first.top.item.durationMs)
                }
                Counted(first.top.plays.toLong(), BigNumber, colors.onSurface, Modifier.padding(top = 6.dp)) { plays ->
                    buildAnnotatedString {
                        append(formatCount(plays.toInt()))
                        withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)) { append(" " + pluralStringResource(R.plurals.insights_plays_word, plays.toInt())) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 16.dp)) {
            shown.drop(1).forEachIndexed { n, row ->
                val place = n + 2
                val song = row.top.item
                val clock = LocalArrivalClock.current
                val title = displayTitle(song, settings.library)
                val rowDescription = listOfNotNull(
                    stringResource(R.string.stats_row, place, title, playsNote(row.top.plays)) + placeWords(place, row.before, card.compared),
                    lengthWords(song.durationMs).ifEmpty { null },
                ).joinToString(", ")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = stringResource(R.string.stats_song_options)) { onSong(song.ref) }
                        .semantics(mergeDescendants = true) { contentDescription = rowDescription }
                        .heightIn(min = 56.dp),
                ) {
                    // One stop: the row's words only, its content cleared (as NavRow's), so nothing is said twice.
                    Text(place.toString(), style = RankStyle, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(22.dp).clearAndSetSemantics {})
                    SongCover(SongArt(song.path, song.ref.mediaStoreId), size = 44.dp, shape = RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f).clearAndSetSemantics {}) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = RowTitle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            PlaceChange(place, row.before, card.compared)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(listOfNotNull(song.artist?.takeIf { it.isNotBlank() }, listenedText(row.top.heardMs)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            SongLength(song.durationMs, Modifier.padding(start = 8.dp))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(CountColumn).clearAndSetSemantics {}) {
                        Text(formatCount(row.top.plays), style = CountStyle, color = colors.onSurface)
                        Text(pluralStringResource(R.plurals.insights_plays_word, row.top.plays), style = FigureLabel.copy(fontSize = 11.5.sp), color = colors.onSurfaceVariant)
                        val share = row.top.plays.toFloat() / first.top.plays.coerceAtLeast(1)
                        Canvas(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp)) {
                            val grown = phase(clock.ms(), RACE_DELAY_MS + n * RACE_STEP_MS, RACE_MS, Motion.Rise)
                            drawRoundRect(insights.series[1].copy(alpha = 0.85f), size = Size(size.width * share * grown, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
                        }
                    }
                }
            }
        }
        LyrifyButton(
            stringResource(R.string.insights_play_top, top25.size),
            onClick = { playback.play(top25, 0, source) },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            icon = R.drawable.ic_play_arrow_fill,
        )
    }
}

/**
 * Top albums as a collage: the first big with its gold place and its name over a shade, the next five smaller with their
 * places, each popping in (560 ms ease-pop, 60 ms apart) and opening its album; then Share the collage.
 */
@Composable
internal fun TopAlbums(card: TopCard<Album>, onOpen: (Page) -> Unit, onShare: () -> Unit) {
    val (_, more) = topPreview(card.rows)
    val albums = card.rows.take(COLLAGE)
    InsightsCard {
        CardHead(stringResource(R.string.stats_top_albums), action = if (more) ({ ViewAll { onOpen(Page.TopList(TopKind.ALBUMS)) } }) else null)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 6.dp
            val cell = (maxWidth - gap * 2) / 3
            albums.forEachIndexed { i, row ->
                val big = i == 0
                val x = when (i) {
                    0, 3 -> 0.dp
                    1, 2 -> (cell + gap) * 2
                    4 -> cell + gap
                    else -> (cell + gap) * 2
                }
                val y = when (i) {
                    0, 1 -> 0.dp
                    2 -> cell + gap
                    else -> (cell + gap) * 2
                }
                AlbumTile(row, i + 1, big, Modifier.offset(x, y).size(if (big) cell * 2 + gap else cell), onOpen)
            }
            Spacer(Modifier.height(if (albums.size > 3) cell * 3 + gap * 2 else cell * 2 + gap))
        }
        LyrifyButton(stringResource(R.string.insights_share_collage), onClick = onShare, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_share)
    }
}

/** One album of the collage: its cover, its place, and on the first its name and artist over a shade. */
@Composable
private fun AlbumTile(row: Ranked<Album>, place: Int, big: Boolean, modifier: Modifier, onOpen: (Page) -> Unit) {
    val clock = LocalArrivalClock.current
    val album = row.top.item
    val description = stringResource(R.string.insights_album_place, place, album.title, album.artist.orEmpty())
    Box(
        modifier
            .graphicsLayer {
                val pop = phase(clock.ms(), (place - 1) * POP_STEP_MS, POP_MS, Motion.Pop)
                alpha = phase(clock.ms(), (place - 1) * POP_STEP_MS, POP_MS)
                scaleX = 0.6f + 0.4f * pop
                scaleY = 0.6f + 0.4f * pop
                rotationZ = -6f * (1f - pop)
            }
            .clip(RoundedCornerShape(if (big) 22.dp else 14.dp))
            .clickable(onClickLabel = stringResource(R.string.stats_open_album)) { onOpen(Page.Album(album.key)) }
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), Modifier.fillMaxWidth().aspectRatio(1f), size = if (big) 220.dp else 104.dp, shape = androidx.compose.ui.graphics.RectangleShape)
        if (big) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, HuddleCardShade.copy(alpha = 0.78f)))).padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 10.dp).clearAndSetSemantics {}) {
                Text(album.title, style = display(18, 22, weight = 700), color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis)
                album.artist?.let { Text(it, style = FigureLabel.copy(fontSize = 12.5.sp), color = BrandLilac.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(if (big) 10.dp else 6.dp)
                .sizeIn(minWidth = if (big) 30.dp else 24.dp, minHeight = if (big) 30.dp else 24.dp)
                .background(if (big) HuddleGold else HuddleCardShade.copy(alpha = 0.62f), CircleShape)
                .padding(horizontal = 6.dp)
                .clearAndSetSemantics {},
        ) {
            Text(place.toString(), style = display(if (big) 15 else 13, 15, weight = 700), color = if (big) OnHuddleGold else BrandLilac)
        }
    }
}

// Your taste

/**
 * Your taste: the genres as bubbles sized by their share of the plays, popping in (700 ms ease-pop, 90 ms apart) then
 * floating; the plays by decade as bars growing in, the most played one in coral; and the middle year of your music.
 */
@Composable
internal fun YourTaste(taste: TasteCard) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    InsightsCard {
        CardHead(stringResource(R.string.insights_taste_title), R.drawable.ic_music_note, stringResource(R.string.insights_taste_line))
        val genres = taste.genres.take(BUBBLES)
        val all = taste.genres.sumOf { it.plays.toLong() }
        if (genres.isNotEmpty()) {
            val shares = genres.map { percentOf(it.plays.toLong(), all) }
            val float = if (huddleMoves()) rememberInfiniteTransition(label = "bubbles").animateFloat(0f, 1f, infiniteRepeatable(tween(FLOAT_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float") else null
            val words = genres.zip(shares).map { (top, share) -> stringResource(R.string.insights_share_of, top.item.name, formatCount(share)) }.joinToString(", ")
            BoxWithConstraints(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = words }) {
                val density = LocalDensity.current
                val width = with(density) { maxWidth.toPx() }
                val height = with(density) { maxHeight.toPx() }
                val sizes = with(density) { Triple(BUBBLE_MIN.toPx(), BUBBLE_MAX.toPx(), 4.dp.toPx()) }
                // Laid out off the main thread (a few thousand spots tried); the bubbles pop in once placed.
                val placed by produceState(emptyList<Bubble>(), shares, width, height) {
                    value = withContext(Dispatchers.Default) { packBubbles(shares, width, height, sizes.first, sizes.second, sizes.third) }
                }
                val clock = LocalArrivalClock.current
                placed.forEach { bubble ->
                    val color = FriendColors[bubble.index % BUBBLES]
                    val small = bubble.radius < with(density) { 40.dp.toPx() }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .offset { androidx.compose.ui.unit.IntOffset((bubble.x - bubble.radius).roundToInt(), (bubble.y - bubble.radius).roundToInt()) }
                            .size(with(density) { (bubble.radius * 2).toDp() })
                            .graphicsLayer {
                                val pop = phase(clock.ms(), bubble.index * BUBBLE_STEP_MS, BUBBLE_MS, Motion.Pop)
                                scaleX = pop
                                scaleY = pop
                                // Each floats on its own beat (the mockup's delays 700 ms apart).
                                val beat = float?.let { ((it.value + bubble.index * 0.14f) % 1f).let { f -> if (f < 0.5f) f * 2 else 2 - f * 2 } } ?: 0f
                                translationY = -5.dp.toPx() * beat
                            }
                            .drawBehind {
                                drawCircle(Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(color, BrandLilac, 0.45f), color), Offset(size.width * 0.32f, size.height * 0.26f), size.width * 0.7f))
                                drawCircle(Brush.verticalGradient(listOf(Color.Transparent, HuddleCardShade.copy(alpha = 0.18f))))
                            }
                            .padding(6.dp)
                            .clearAndSetSemantics {},
                    ) {
                        Text(genres[bubble.index].item.name, style = body(if (small) 11f else 13f, if (small) 13 else 16, 700), color = BrandLilac, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.insights_percent, formatCount(shares[bubble.index])), style = display(if (small) 12 else 15, if (small) 15 else 18, weight = 700).tabular(), color = BrandLilac)
                    }
                }
            }
        }
        if (taste.decades.isNotEmpty()) {
            Text(stringResource(R.string.insights_decades), style = display(15, 20, weight = 650), color = colors.onSurface, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp).semantics { heading() })
            val plays = taste.decades.sumOf { it.plays.toLong() }
            val most = taste.decades.maxOf { it.plays }
            val clock = LocalArrivalClock.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(132.dp).padding(top = 10.dp)) {
                taste.decades.takeLast(DECADES).forEachIndexed { k, decade ->
                    val peak = decade.plays == most
                    val share = percentOf(decade.plays.toLong(), plays)
                    val name = stringResource(R.string.insights_decade, String.format(Locale.ROOT, "%02d", decade.from % 100))
                    val description = stringResource(R.string.insights_share_of, name, formatCount(share))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics { contentDescription = description },
                    ) {
                        Text(stringResource(R.string.insights_percent, formatCount(share)), style = AxisLabel.copy(fontSize = 12.sp, fontWeight = FontWeight(650)).tabular(), color = if (peak) brand.highlight else colors.onSurfaceVariant, maxLines = 1)
                        Canvas(Modifier.weight(1f).fillMaxWidth()) {
                            val h = size.height * (decade.plays.toFloat() / most) * phase(clock.ms(), k * 3 * BAR_STEP_MS, BAR_MS, Motion.Rise)
                            val color = if (peak) insights.series[1] else insights.series[0].copy(alpha = 0.85f)
                            drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(minOf(10.dp.toPx(), h / 2)))
                        }
                        Text(name, style = AxisLabel.copy(fontSize = 12.sp, fontWeight = FontWeight(650)), color = colors.onSurface, maxLines = 1)
                    }
                }
            }
        }
        taste.middleYear?.let { year ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
            ) {
                Text(year.toString(), style = display(30, 32, -0.02, weight = 700).tabular(), color = brand.highlight)
                Text(stringResource(R.string.insights_middle_year), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp), color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A genre bubble laid out: its [index] in the list, its centre and [radius], px. */
data class Bubble(val index: Int, val x: Float, val y: Float, val radius: Float)

/**
 * Bubbles for [shares] (percent, the largest first) in a [width] by [height] box: each sized by the square root of its
 * share of the first (from [smallest] to [largest] across), placed at the free spot nearest the middle (the box's width
 * counting less), [gap] apart. When one finds no room, all of them are drawn a little smaller and placed again, so
 * every genre shows (the mockup's packing left some out at a phone's width); at the smallest, one with no room is left
 * out (TalkBack still reads it).
 */
fun packBubbles(shares: List<Int>, width: Float, height: Float, smallest: Float, largest: Float, gap: Float): List<Bubble> {
    if (shares.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val step = 3f
    val spots = buildList {
        var x = 0f
        while (x <= width) {
            var y = 0f
            while (y <= height) {
                add(Triple(x, y, hypot((x - width / 2) / 1.7f, y - height / 2)))
                y += step
            }
            x += step
        }
    }.sortedBy { it.third }
    val top = shares.first().coerceAtLeast(1)
    fun place(scale: Float): List<Bubble> {
        val placed = mutableListOf<Bubble>()
        shares.forEachIndexed { i, share ->
            val r = max(smallest, sqrt(share.toFloat() / top) * largest) * scale
            spots.firstOrNull { (x, y) -> x - r >= 0 && x + r <= width && y - r >= 0 && y + r <= height && placed.all { hypot(it.x - x, it.y - y) >= it.radius + r + gap } }
                ?.let { (x, y) -> placed += Bubble(i, x, y, r) }
        }
        return placed
    }
    return generateSequence(1f) { it - BUBBLE_SHRINK }.takeWhile { it >= BUBBLE_SMALLEST_SCALE - 0.001f }.map(::place).firstOrNull { it.size == shares.size } ?: place(BUBBLE_SMALLEST_SCALE)
}

/** Bubbles that do not all fit shrink by a tenth at a time, to 60% at the least. */
private const val BUBBLE_SHRINK = 0.1f
private const val BUBBLE_SMALLEST_SCALE = 0.6f

// How you listen

/**
 * How you listen: what started each song as one bar of five ways in (picked, albums and lists in order, Shuffle,
 * suggestions, Huddle) with its legend; how plays ended as a ring (played to the end, moved on after 30 s, skipped early)
 * drawing itself in; how much more Shuffle's songs were skipped early; and the song skipped most.
 */
@Composable
internal fun HowYouListen(how: HowCard, settings: Settings, onSong: (SongRef) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val starts = listOf(how.started.picked, how.started.inOrder, how.started.shuffle, how.started.suggestions, how.started.huddle)
    val all = starts.sumOf { it.toLong() }
    val shares = starts.map { percentOf(it.toLong(), all) }
    val names = listOf(R.string.insights_start_picked, R.string.insights_start_in_order, R.string.insights_start_shuffle, R.string.insights_start_suggestions, R.string.insights_start_huddle).map { stringResource(it) }
    InsightsCard {
        CardHead(stringResource(R.string.insights_how_listen_title), R.drawable.ic_queue_music, stringResource(R.string.insights_how_listen_line))
        StackBar(shares, insights.series, 40.dp)
        Column(Modifier.padding(top = 12.dp)) {
            names.indices.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { i -> LegendItem(insights.series[i], names[i], stringResource(R.string.insights_percent, formatCount(shares[i])), Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        val ends = how.ends
        val total = (ends.finished + ends.movedOn + ends.early).toLong()
        if (total > 0) {
            val endShares = listOf(ends.finished, ends.movedOn, ends.early).map { percentOf(it.toLong(), total) }
            val endColors = listOf(insights.series[3], insights.series[2], insights.series[1])
            val endNames = listOf(R.string.insights_end_finished, R.string.insights_end_moved_on, R.string.insights_end_early).map { stringResource(it) }
            Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(1.dp).background(colors.outlineVariant))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 16.dp)) {
                val clock = LocalArrivalClock.current
                val ring = colors.surfaceContainerHigh
                val centre = stringResource(R.string.insights_percent, formatCount(endShares[0]))
                val toEnd = stringResource(R.string.insights_to_the_end)
                val measurer = rememberTextMeasurer()
                val big = display(28, 30, -0.02, weight = 700).copy(color = colors.onSurface)
                val small = AxisLabel.copy(fontSize = 11.sp, color = colors.onSurfaceVariant)
                Canvas(Modifier.size(128.dp).clearAndSetSemantics {}) {
                    val stroke = 16.dp.toPx()
                    val r = 48.dp.toPx() * size.minDimension / 128.dp.toPx()
                    val box = Size(r * 2, r * 2)
                    val at = Offset(size.width / 2 - r, size.height / 2 - r)
                    drawArc(ring, 0f, 360f, false, at, box, style = Stroke(stroke))
                    var start = -90f
                    endShares.forEachIndexed { k, share ->
                        val sweep = 360f * share / 100f
                        val drawn = phase(clock.ms(), DONUT_DELAY_MS + k * DONUT_STEP_MS, DONUT_MS, Motion.Rise)
                        if (sweep > 2f) drawArc(endColors[k], start, (sweep - 2f) * drawn, false, at, box, style = Stroke(stroke))
                        start += sweep
                    }
                    val a = measurer.measure(centre, big)
                    val b = measurer.measure(toEnd, small)
                    // The largest text shrinks to fit the ring's hole.
                    scale(minOf(1f, (2 * r - stroke) * 0.86f / maxOf(a.size.width, b.size.width)), center) {
                        drawText(a, topLeft = Offset(size.width / 2 - a.size.width / 2, size.height / 2 - a.size.height / 2 - 6.dp.toPx()))
                        drawText(b, topLeft = Offset(size.width / 2 - b.size.width / 2, size.height / 2 + a.size.height / 2 - 6.dp.toPx()))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    endNames.forEachIndexed { k, name ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            Box(Modifier.padding(top = 4.dp).size(10.dp).background(endColors[k], RoundedCornerShape(3.dp)))
                            Column {
                                Text(stringResource(R.string.insights_percent, formatCount(endShares[k])), style = FigureValue.copy(fontSize = 17.sp, lineHeight = 20.sp).tabular(), color = colors.onSurface)
                                Text(name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp), color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        skipRatio(how.shuffled, how.inOrder)?.let { ratio ->
            Text(
                boldIn(stringResource(R.string.insights_shuffle_skips, String.format(LocalLocale.current.platformLocale, "%.1f", ratio)), stringResource(R.string.insights_times, String.format(LocalLocale.current.platformLocale, "%.1f", ratio))),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        how.mostSkipped?.let { skipped ->
            val title = displayTitle(skipped.song, settings.library)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .clickable(onClickLabel = stringResource(R.string.stats_song_options)) { onSong(skipped.song.ref) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                SongCover(SongArt(skipped.song.path, skipped.song.ref.mediaStoreId), size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.insights_most_skipped, title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(R.plurals.insights_most_skipped_line, skipped.plays, skipped.early, skipped.plays), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                        SongLength(skipped.song.durationMs, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * How many times more often songs from Shuffle ([shuffled]) were skipped early than songs in order ([inOrder]); null
 * unless both have plays and Shuffle's are skipped clearly more (a fifth more or more), so the line never says the
 * reverse.
 */
fun skipRatio(shuffled: Ends, inOrder: Ends): Float? {
    val shuffledAll = shuffled.finished + shuffled.movedOn + shuffled.early
    val inOrderAll = inOrder.finished + inOrder.movedOn + inOrder.early
    if (shuffledAll == 0 || inOrderAll == 0 || inOrder.early == 0) return null
    val ratio = (shuffled.early.toFloat() / shuffledAll) / (inOrder.early.toFloat() / inOrderAll)
    return ratio.takeIf { it >= MIN_SKIP_RATIO }
}

private val BigNumber = display(40, 44, -0.03, weight = 700)

private val RankStyle = display(15, 15, weight = 700)

private val CountStyle = display(15, 20, weight = 700)

private val RowTitle = body(15f, 19, 600)

/** The plays column of a top song's row: room for "1,048" and the short bar under it. */
private val CountColumn = 56.dp

private const val RACE_MS = 900

private const val RACE_DELAY_MS = 200

private const val RACE_STEP_MS = 110

private const val POP_MS = 560

private const val POP_STEP_MS = 60

private const val BUBBLE_MS = 700

private const val BUBBLE_STEP_MS = 90

private const val FLOAT_MS = 5_000

private const val DONUT_MS = 1_100

private const val DONUT_DELAY_MS = 200

private const val DONUT_STEP_MS = 160

/** Shuffle's skips are worth a line from a fifth more than in order's. */
private const val MIN_SKIP_RATIO = 1.2f

/** The top albums' collage: the first big, the next five round it (the card and its picture). */
internal const val COLLAGE = 6

private const val BUBBLES = 6

private const val DECADES = 6

private const val PLAY_TOP = 25

private val BUBBLE_MIN = 29.dp

private val BUBBLE_MAX = 60.dp

private const val TOP_SONGS_KEY = "insights-top-songs"
