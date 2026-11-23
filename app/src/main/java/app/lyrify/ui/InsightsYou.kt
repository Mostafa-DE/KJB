package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lyrify.R
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.settings.Settings
import app.lyrify.stats.Comeback
import app.lyrify.stats.FavouritesCard
import app.lyrify.stats.FoundCard
import app.lyrify.stats.FriendsCard
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.LyricsCard
import app.lyrify.stats.Moment
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.SearchesCard
import app.lyrify.stats.Top
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.first

// Insights' cards about you (the approved mockup, handoff/insights/preview-insights-v1.html): new to you and back
// again, you and your friends, read along with new favourites, your searches, books and podcasts, your library, your
// listening type, milestones and badges; and the monthly recap's card on Library's start tab. The other cards are in
// InsightsCards.kt.

// New to you

/**
 * New to you (all time: every artist you found): the new artists counted up in teal, their songs and their share of the
 * plays, their faces popping in (a tick on the ones you kept), how many you kept and where they came from; then the
 * songs back again after a quiet spell, and the favourites still quiet with Shuffle.
 */
@Composable
internal fun NewToYou(found: FoundCard?, comebacks: List<Comeback>, forgotten: List<Top<Song>>, period: PagePeriod, settings: Settings, onOpen: (Page) -> Unit, onSong: (SongRef) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    val all = period.kind == ListeningPeriod.ALL_TIME
    val context = LocalContext.current
    InsightsCard {
        CardHead(
            stringResource(if (all) R.string.insights_found_all_title else R.string.insights_found_title),
            R.drawable.ic_explore,
            stringResource(if (all) R.string.insights_found_all_line else R.string.insights_found_line),
        )
        if (found != null && found.artists.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Counted(found.artists.size.toLong(), display(56, 56, -0.03, weight = 700), brand.suggestion) { countText(it) }
                Column(Modifier.weight(1f)) {
                    Text(
                        pluralStringResource(if (all) R.plurals.insights_artists_word else R.plurals.insights_new_artists, found.artists.size),
                        style = display(17, 22, weight = 650),
                        color = colors.onSurface,
                    )
                    val songs = pluralStringResource(if (all) R.plurals.insights_found_songs_all else R.plurals.insights_found_songs, found.songs, formatCount(found.songs))
                    Text(
                        if (all) songs else stringResource(R.string.stats_row_line, songs, stringResource(R.string.insights_of_plays, formatCount(percentOf(found.newPlays.toLong(), found.plays.toLong())))),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            val clock = LocalArrivalClock.current
            Row(Modifier.padding(top = 16.dp, start = 6.dp).clearAndSetSemantics {}) {
                found.artists.take(FACES).forEachIndexed { i, artist ->
                    Box(
                        Modifier
                            .offset(x = (-6 * i).dp)
                            .graphicsLayer {
                                val pop = phase(clock.ms(), i * FACE_STEP_MS, FACE_MS, Motion.Pop)
                                alpha = phase(clock.ms(), i * FACE_STEP_MS, FACE_MS)
                                scaleX = 0.6f + 0.4f * pop
                                scaleY = 0.6f + 0.4f * pop
                                rotationZ = -6f * (1f - pop)
                            }
                            .size(54.dp),
                    ) {
                        Box(Modifier.matchParentSize().border(3.dp, colors.surfaceContainerLow, CircleShape).padding(2.dp)) {
                            SongCover(artist.artist.songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = 50.dp, shape = CircleShape)
                        }
                        if (artist.kept) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(24.dp).border(2.dp, colors.surfaceContainerLow, CircleShape).padding(1.dp).background(brand.suggestion, CircleShape),
                            ) {
                                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = brand.suggestionContainer, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                if (found.artists.size > FACES) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.offset(x = (-6 * FACES).dp).size(54.dp).border(3.dp, colors.surfaceContainerLow, CircleShape).padding(2.dp).background(colors.surface, CircleShape)) {
                        Text(stringResource(R.string.insights_plus, formatCount(found.artists.size - FACES)), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = colors.onSurfaceVariant)
                    }
                }
            }
            val kept = found.artists.count { it.kept }
            if (!all && kept > 0) {
                Text(
                    pluralStringResource(R.plurals.insights_kept, kept, kept),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            val from = listOf(found.fromSuggestions, found.fromFriends, found.onYourOwn)
            if (from.sum() > 0) {
                val fromColors = listOf(insights.series[3], insights.series[4], insights.series[0])
                val fromNames = listOf(R.string.insights_from_suggestions, R.string.insights_from_friends, R.string.insights_from_own).map { stringResource(it) }
                Text(stringResource(R.string.insights_found_from), style = FigureLabel.copy(fontSize = 13.sp), color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                StackBar(from.map { percentOf(it.toLong(), from.sum().toLong()) }, fromColors, 28.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                    fromNames.forEachIndexed { k, name -> LegendItem(fromColors[k], name, formatCount(from[k])) }
                }
            }
        }
        if (comebacks.isNotEmpty()) {
            Text(stringResource(R.string.insights_back_again), style = display(15, 20, weight = 650), color = colors.onSurface, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp).semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                comebacks.take(BACK_AGAIN).forEach { back ->
                    val title = displayTitle(back.song, settings.library)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = stringResource(R.string.stats_song_options)) { onSong(back.song.ref) }.semantics(mergeDescendants = true) {},
                    ) {
                        SongCover(SongArt(back.song.path, back.song.ref.mediaStoreId), Modifier.fillMaxWidth().aspectRatio(1f), size = 104.dp, shape = RoundedCornerShape(16.dp))
                        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pluralStringResource(R.plurals.insights_quiet_days, back.quietDays, formatCount(back.quietDays)), style = FigureLabel, color = brand.suggestion, modifier = Modifier.offset(y = (-6).dp))
                    }
                }
                repeat(BACK_AGAIN - comebacks.size.coerceAtMost(BACK_AGAIN)) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (forgotten.isNotEmpty()) {
            val name = stringResource(R.string.insights_forgotten_title)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().background(colors.surface, RoundedCornerShape(22.dp)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(pluralStringResource(R.plurals.insights_quiet_favourites, forgotten.size, formatCount(forgotten.size)), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Text(stringResource(R.string.insights_quiet_line), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = colors.onSurfaceVariant)
                }
                LyrifyButton(stringResource(R.string.lib_shuffle), onClick = { shuffle(context, forgotten.map { it.item }, FORGOTTEN_KEY, name) }, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_shuffle, compact = true)
            }
        }
    }
}

// You and your friends

/**
 * You and your friends (Huddle's history, on this phone): your face and the closest friend's bumping with a spark, how
 * alike you play, the artists you both love, every friend's match as a ring that fills to it, the songs sent, got and
 * kept, the times you played the same song within the hour, and the song a friend sent that you kept playing.
 */
@Composable
internal fun YouAndFriends(friends: FriendsCard, settings: Settings) {
    val colors = MaterialTheme.colorScheme
    val nav = LocalNavigator.current
    val closest = friends.matches.first()
    val paper = if (LocalDarkTheme.current) DarkPaper else LightPaper
    InsightsCard {
        CardHead(stringResource(R.string.insights_friends_title), R.drawable.ic_group, stringResource(R.string.insights_friends_line))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            JoinedFaces(stringResource(R.string.huddle_you), closest.name, friendColor(closest.friend), size = 64.dp, ring = FaceRing.LIVE, gap = colors.surfaceContainerLow)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.insights_most_like_you).uppercase(), style = CardKicker.copy(fontSize = 11.5.sp), color = colors.onSurfaceVariant)
                Counted(closest.percent.toLong(), display(44, 46, -0.03, weight = 700), colors.onSurface) {
                    buildAnnotatedString {
                        append(formatCount(it.toInt()))
                        withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight(650), color = colors.onSurfaceVariant)) { append(stringResource(R.string.insights_percent_sign)) }
                    }
                }
                Text(
                    buildAnnotatedString {
                        val words = stringResource(R.string.insights_alike_line, closest.name)
                        append(words)
                        val at = words.indexOf(closest.name)
                        if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.onSurface), at, at + closest.name.length)
                    },
                    style = FigureLabel.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (friends.both.isNotEmpty()) {
            Text(stringResource(R.string.insights_both_love).uppercase(), style = CardKicker, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                friends.both.forEach { artist ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(min = 32.dp).background(colors.surface, RoundedCornerShape(16.dp)).padding(start = 4.dp, end = 12.dp)) {
                        SongCover(artist.songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = 24.dp, shape = CircleShape)
                        Text(artist.name, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 16.dp)) {
            items(friends.matches, key = { it.friend }) { match ->
                val color = friendColor(match.friend)
                val clock = LocalArrivalClock.current
                val ring = colors.surfaceContainerHigh
                val description = stringResource(R.string.insights_friend_match, match.name, match.percent)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .width(70.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = stringResource(R.string.insights_open_friend)) { nav.open(Page.HuddleThread(match.friend)) }
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(vertical = 4.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(60.dp).drawBehind {
                            drawCircle(ring)
                            val filled = 360f * match.percent / 100f * phase(clock.ms(), 0, RING_MS, Motion.Rise)
                            drawArc(color, -90f, filled, true)
                        },
                    ) {
                        FriendFace(match.name, color, 48.dp, ring = if (match.past) FaceRing.IDLE else FaceRing.NONE)
                    }
                    Text(match.name, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.insights_percent, formatCount(match.percent)), style = display(13, 16, weight = 700).tabular(), color = colors.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 14.dp)) {
            SmallFigure(friends.sent.toLong(), stringResource(R.string.insights_sent), Modifier.weight(1f))
            SmallFigure(friends.got.toLong(), stringResource(R.string.insights_got), Modifier.weight(1f))
            SmallFigure(friends.kept.toLong(), stringResource(R.string.insights_kept_playing), Modifier.weight(1f))
        }
        friends.inSync?.let { sync ->
            val brand = LocalBrandColors.current
            val title = displayTitle(sync.song, settings.library)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().background(brand.suggestionContainer, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
            ) {
                Equaliser(brand.suggestion, Modifier.size(18.dp, 16.dp))
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.insights_in_sync, sync.times, sync.times), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Text(
                        buildAnnotatedString {
                            val words = stringResource(R.string.insights_in_sync_line, sync.name, title)
                            append(words)
                            val at = words.indexOf(title)
                            if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + title.length)
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = colors.onSurface,
                    )
                }
            }
        }
        friends.favourite?.let { kept ->
            val title = displayTitle(kept.song, settings.library)
            val color = friendColor(kept.friend)
            Column(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .rotate(-1f)
                    .background(paper.paper, RoundedCornerShape(18.dp))
                    .border(1.dp, paper.line, RoundedCornerShape(18.dp))
                    .semantics(mergeDescendants = true) {}
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_favorite_fill), contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
                    Text(stringResource(R.string.insights_from_friend_kept, kept.name).uppercase(), style = CardKicker.copy(fontSize = 11.sp), color = color)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(title, style = display(22, 26, -0.025, weight = 700), color = colors.onSurface, modifier = Modifier.weight(1f))
                    SongCover(SongArt(kept.song.path, kept.song.ref.mediaStoreId), Modifier.rotate(3f), size = 58.dp, shape = RoundedCornerShape(6.dp))
                }
                Text(pluralStringResource(R.plurals.insights_from_friend_line, kept.plays, kept.plays), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = colors.onSurfaceVariant)
            }
        }
    }
}

// Read along, new favourites

/**
 * Two tiles side by side (one above the other with large text): Read along, the times you opened lyrics over three
 * lines of lyrics drawn plain, the middle one sung, its coral line sweeping under it; and New favourites, its heart
 * beating.
 */
@Composable
internal fun ReadAlongAndHearts(lyrics: LyricsCard?, favourites: FavouritesCard?, period: PagePeriod, settings: Settings) {
    val wide = LocalDensity.current.fontScale <= STACK_FONT_SCALE
    val tiles: List<@Composable (Modifier) -> Unit> = listOfNotNull(
        lyrics?.let { { modifier: Modifier -> ReadAlong(it, settings, modifier) } },
        favourites?.let { { modifier: Modifier -> NewFavourites(it, period, settings, modifier) } },
    )
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = ScreenPadding).height(androidx.compose.foundation.layout.IntrinsicSize.Max)) {
            tiles.forEach { it(Modifier.weight(1f)) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
            tiles.forEach { it(Modifier.fillMaxWidth()) }
        }
    }
}

/** A tile of the bento (the mockup's): KJB's card, 16 dp in, its coral icon on a small tile and its title. */
@Composable
private fun BentoTile(@DrawableRes icon: Int, title: String, modifier: Modifier, iconMotion: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    LyrifyCard(modifier.fillMaxHeight()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(bottom = 6.dp).size(36.dp).background(colors.surface, RoundedCornerShape(12.dp))) {
                Icon(painterResource(icon), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = iconMotion.size(20.dp))
            }
            Text(title, style = display(15, 20, weight = 650), color = colors.onSurface, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun ReadAlong(lyrics: LyricsCard, settings: Settings, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val sung = if (huddleMoves()) rememberInfiniteTransition(label = "sung").animateFloat(
        0f,
        1f,
        infiniteRepeatable(keyframes {
            durationMillis = SUNG_MS
            0f at 0
            1f at SUNG_MS * 80 / 100
            1f at SUNG_MS
        }),
        label = "line",
    ) else null
    BentoTile(R.drawable.ic_lyrics, stringResource(R.string.insights_read_along), modifier) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Counted(lyrics.opened.toLong(), display(36, 40, -0.03, weight = 700), colors.onSurface) { countText(it) }
            Text(pluralStringResource(R.plurals.insights_lyrics_opened, lyrics.opened), style = FigureLabel.copy(fontSize = 13.sp), color = colors.onSurfaceVariant)
        }
        // Three lines of lyrics, drawn plain (never a song's words): the middle one sung.
        Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp).clearAndSetSemantics {}) {
            val line = 17.dp.toPx()
            listOf(0.78f, 0.62f, 0.86f).forEachIndexed { i, width ->
                val y = i * line + line / 2
                val on = i == 1
                drawLine(if (on) colors.onSurface else colors.outline.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width * width, y), if (on) 5.dp.toPx() else 4.dp.toPx(), StrokeCap.Round)
                if (on) {
                    val under = (sung?.value ?: 1f) * size.width * width
                    drawLine(brand.highlight, Offset(0f, y + 6.dp.toPx()), Offset(under.coerceAtLeast(SUNG_LINE_WIDTH.toPx().takeIf { sung == null } ?: under), y + 6.dp.toPx()), SUNG_LINE_HEIGHT.toPx(), StrokeCap.Round)
                }
            }
        }
        lyrics.most?.let { most ->
            val title = displayTitle(most, settings.library)
            Text(
                buildAnnotatedString {
                    val words = stringResource(R.string.insights_lyrics_most, title, lyrics.mostTimes, percentOf(lyrics.syncedPlays.toLong(), lyrics.plays.toLong()))
                    append(words)
                    val at = words.indexOf(title)
                    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.onSurface), at, at + title.length)
                },
                style = FigureLabel.copy(fontSize = 13.sp),
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun NewFavourites(favourites: FavouritesCard, period: PagePeriod, settings: Settings, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val beat = if (huddleMoves()) rememberInfiniteTransition(label = "heart").animateFloat(
        1f,
        1f,
        infiniteRepeatable(keyframes {
            durationMillis = BEAT_MS
            1f at 0
            1.22f at BEAT_MS * 15 / 100
            0.96f at BEAT_MS * 30 / 100
            1.12f at BEAT_MS * 45 / 100
            1f at BEAT_MS * 60 / 100
        }),
        label = "beat",
    ) else null
    BentoTile(
        R.drawable.ic_favorite_fill,
        stringResource(R.string.insights_new_favourites),
        modifier,
        Modifier.graphicsLayer {
            scaleX = beat?.value ?: 1f
            scaleY = beat?.value ?: 1f
        },
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Counted(favourites.added.toLong(), display(36, 40, -0.03, weight = 700), colors.onSurface) { countText(it) }
            Text(
                pluralStringResource(if (period.kind == ListeningPeriod.ALL_TIME) R.plurals.insights_favourites_all else R.plurals.insights_favourites_new, favourites.added),
                style = FigureLabel.copy(fontSize = 13.sp),
                color = colors.onSurfaceVariant,
            )
        }
        favourites.mostPlayed?.let { most ->
            val title = displayTitle(most, settings.library)
            Text(
                buildAnnotatedString {
                    val words = stringResource(R.string.insights_favourites_most, title)
                    append(words)
                    val at = words.indexOf(title)
                    if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.onSurface), at, at + title.length)
                },
                style = FigureLabel.copy(fontSize = 13.sp),
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// Your searches

/**
 * Your searches, on this phone only: how many, how many ended in a tap, how many a play followed within a minute; the
 * words searched most, hidden until a tap shows them (drawn as blank chips until then, so no phone shows them through a
 * blur it cannot draw) and never in a story or a picture; and what the taps opened.
 */
@Composable
internal fun YourSearches(searches: SearchesCard) {
    val colors = MaterialTheme.colorScheme
    val chrome = LocalChromeColors.current
    val insights = if (LocalDarkTheme.current) DarkInsights else LightInsights
    var shown by remember { mutableStateOf(false) }
    InsightsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(colors.surface, RoundedCornerShape(12.dp))) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(20.dp))
            }
            Text(stringResource(R.string.insights_searches_title), style = display(15, 20, weight = 650), color = colors.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.heightIn(min = 26.dp).background(chrome.action, RoundedCornerShape(13.dp)).padding(start = 7.dp, end = 10.dp)) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = chrome.onAction, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.insights_only_here), style = PillLabel, color = chrome.onAction)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            SmallFigure(searches.searches.toLong(), pluralStringResource(R.plurals.insights_searches, searches.searches), Modifier.weight(1f))
            SmallFigure(percentOf(searches.tapped.toLong(), searches.searches.toLong()).toLong(), stringResource(R.string.insights_ended_in_tap), Modifier.weight(1f)) {
                AnnotatedString(stringResource(R.string.insights_percent, formatCount(it.toInt())))
            }
            SmallFigure(searches.played.toLong(), stringResource(R.string.insights_played_within), Modifier.weight(1f))
        }
        if (searches.words.isNotEmpty()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().then(if (shown) Modifier else Modifier.clearAndSetSemantics {})) {
                    searches.words.forEach { word ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(min = 32.dp).background(colors.surface, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
                        ) {
                            if (shown) {
                                Text(word.word, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), color = colors.onSurface)
                                Text(formatCount(word.times), style = display(12, 16, weight = 700).tabular(), color = colors.onSurfaceVariant)
                            } else {
                                // A blank chip of the word's length: nothing of it shows until the tap.
                                Box(Modifier.width((word.word.length.coerceIn(3, 12) * 7 + 14).dp).height(10.dp).background(colors.outlineVariant, RoundedCornerShape(5.dp)))
                            }
                        }
                    }
                }
                if (!shown) LyrifyButton(stringResource(R.string.insights_show_words), onClick = { shown = true }, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_visibility, compact = true)
            }
        }
        val kinds = listOf(searches.songs, searches.artists, searches.albums, searches.others)
        if (kinds.sum() > 0) {
            val shares = kinds.map { percentOf(it.toLong(), kinds.sum().toLong()) }
            val names = listOf(R.string.library_tab_songs, R.string.library_tab_artists, R.string.library_tab_albums, R.string.insights_other).map { stringResource(it) }
            Text(stringResource(R.string.insights_looked_for), style = FigureLabel.copy(fontSize = 13.sp), color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            StackBar(shares, insights.series, 12.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                names.forEachIndexed { k, name -> if (kinds[k] > 0) LegendItem(insights.series[k], name, stringResource(R.string.insights_percent, formatCount(shares[k]))) }
            }
        }
        Text(stringResource(R.string.insights_searches_private), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

private val PillLabel = body(12f, 16, 650)

private const val FACES = 6

private const val FACE_MS = 520

private const val FACE_STEP_MS = 70

private const val BACK_AGAIN = 3

private const val RING_MS = 900

private const val SUNG_MS = 2_400

private const val BEAT_MS = 1_600

/** From this font scale the bento's tiles stand one above the other. */
private const val STACK_FONT_SCALE = 1.3f

// Moments

/**
 * Moments as postcards on the paper colour, tilted one way then the other, each with its kicker, its big figure, the
 * song's cover as the stamp and a line; they scroll sideways and straighten a little under a finger.
 */
@Composable
internal fun Moments(moments: List<Moment>, settings: Settings) {
    InsightsCard {
        CardHead(stringResource(R.string.insights_moments_title), R.drawable.ic_auto_awesome, stringResource(R.string.insights_moments_line))
        // The postcards reach the card's edges (the mockup's), past its padding.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp),
            modifier = Modifier.layout { measurable, constraints ->
                val bleed = 18.dp.roundToPx()
                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + bleed * 2, minWidth = constraints.maxWidth + bleed * 2))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            },
        ) {
            itemsIndexed(moments, key = { i, moment -> "$i ${moment::class.simpleName}" }) { i, moment ->
                MomentCard(moment, if (i % 2 == 0) -1.6f else 1.4f, settings)
            }
        }
    }
}

/** One moment's postcard: what it is, its figure, its stamp (a cover, else the kicker's icon) and its line. */
@Composable
private fun MomentCard(moment: Moment, tilt: Float, settings: Settings) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val paper = if (LocalDarkTheme.current) DarkPaper else LightPaper
    val m = momentWords(moment, settings)
    Column(
        Modifier
            .width(232.dp)
            .rotate(tilt)
            .background(paper.paper, RoundedCornerShape(18.dp))
            .border(1.dp, paper.line, RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(m.icon), contentDescription = null, tint = brand.highlight, modifier = Modifier.size(15.dp))
            Text(m.kicker.uppercase(), style = CardKicker.copy(fontSize = 11.sp), color = brand.highlight)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
            Text(m.value, style = if (m.value.length > 10) display(22, 26, -0.025, weight = 700) else display(27, 30, -0.025, weight = 700), color = colors.onSurface, modifier = Modifier.weight(1f))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .rotate(3f)
                    .size(58.dp, 66.dp)
                    .background(colors.surfaceContainerLowest, RoundedCornerShape(6.dp))
                    .drawBehind {
                        val w = 2.dp.toPx()
                        drawRoundRect(androidx.compose.ui.graphics.lerp(brand.highlight, paper.paper, 0.55f), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(6.dp.toPx()), Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    }
                    .padding(4.dp)
                    .clearAndSetSemantics {},
            ) {
                if (m.cover != null) {
                    SongCover(SongArt(m.cover.path, m.cover.ref.mediaStoreId), Modifier.fillMaxWidth().fillMaxHeight(), size = 50.dp, shape = RoundedCornerShape(3.dp))
                } else {
                    Icon(painterResource(m.icon), contentDescription = null, tint = brand.highlight, modifier = Modifier.size(26.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).drawBehind {
            drawLine(paper.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        })
        Text(m.line, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** What a moment's postcard says: its [icon] and [kicker], its big [value], its [line] (names in bold) and its stamp's [cover]. */
private class MomentWords(@DrawableRes val icon: Int, val kicker: String, val value: String, val line: AnnotatedString, val cover: Song?)

/** A line with [bold]'s words, wherever they fall in [text], in the page's ink. */
@Composable
private fun boldIn(text: String, vararg bold: String): AnnotatedString {
    val ink = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        append(text)
        bold.forEach { word ->
            val at = text.indexOf(word)
            if (word.isNotEmpty() && at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink), at, at + word.length)
        }
    }
}

@Composable
private fun momentWords(moment: Moment, settings: Settings): MomentWords = when (moment) {
    is Moment.OnRepeat -> {
        val title = displayTitle(moment.song, settings.library)
        val day = dayText(moment.day, weekday = true)
        MomentWords(R.drawable.ic_repeat, stringResource(R.string.insights_moment_repeat), playsNote(moment.plays), boldIn(stringResource(R.string.insights_moment_repeat_line, title, day), title), moment.song)
    }
    is Moment.LongestSession -> {
        val day = dayText(moment.start.toLocalDate(), weekday = true)
        MomentWords(
            R.drawable.ic_schedule,
            stringResource(R.string.insights_moment_session),
            listenedText(moment.heardMs),
            boldIn(stringResource(R.string.insights_moment_session_line, day, timeText(moment.start)), day),
            moment.song,
        )
    }
    is Moment.LatestNight -> {
        val title = displayTitle(moment.song, settings.library)
        MomentWords(R.drawable.ic_dark_mode, stringResource(R.string.insights_moment_night), timeText(moment.at), boldIn(stringResource(R.string.insights_moment_night_line, title, dayText(moment.at.toLocalDate(), weekday = true)), title), moment.song)
    }
    is Moment.StartToFinish -> MomentWords(
        R.drawable.ic_album,
        stringResource(R.string.insights_moment_albums),
        pluralStringResource(R.plurals.insights_albums, moment.albums, formatCount(moment.albums)),
        boldIn(stringResource(R.string.insights_moment_albums_line, moment.album.title), moment.album.title),
        moment.album.songs.firstOrNull(),
    )
    is Moment.OnTheMove -> {
        val zones = moment.offsets.map { utcText(it) }
        val line = if (zones.size <= 2) stringResource(R.string.insights_moment_zones_two, zones.first(), zones.last()) else stringResource(R.string.insights_moment_zones_range, zones.minBy { it }, zones.maxBy { it })
        MomentWords(R.drawable.ic_travel_explore, stringResource(R.string.insights_moment_zones), pluralStringResource(R.plurals.insights_time_zones, moment.offsets.size, moment.offsets.size), boldIn(line, *zones.toTypedArray()), null)
    }
    is Moment.Faster -> {
        val what = stringResource(
            when {
                moment.booksMs > 0 && moment.podcastsMs > 0 -> R.string.insights_moment_faster_both
                moment.booksMs > 0 -> R.string.insights_moment_faster_books
                else -> R.string.insights_moment_faster_podcasts
            },
        )
        MomentWords(R.drawable.ic_speed, stringResource(R.string.insights_moment_faster), listenedText(moment.booksMs + moment.podcastsMs), boldIn(stringResource(R.string.insights_moment_faster_line, what), what), null)
    }
    is Moment.YearAgo -> {
        val artist = moment.song.artist.orEmpty()
        MomentWords(
            R.drawable.ic_calendar_month,
            stringResource(R.string.insights_moment_year_ago),
            displayTitle(moment.song, settings.library),
            boldIn(pluralStringResource(R.plurals.insights_moment_year_ago_line, moment.plays, artist, moment.plays, dayText(moment.day, year = true)), artist),
            moment.song,
        )
    }
    is Moment.FirstPlay -> {
        val day = dayText(moment.day, year = true)
        MomentWords(R.drawable.ic_auto_awesome, stringResource(R.string.insights_moment_first), displayTitle(moment.song, settings.library), boldIn(stringResource(R.string.insights_moment_first_line, day), day), moment.song)
    }
}

/** A time of day as the phone writes it: "3:12 am" or "03:12". */
@Composable
private fun timeText(at: LocalDateTime): String =
    android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))

/** A time zone as "UTC+3", "UTC+5:30" or "UTC". */
fun utcText(offsetMinutes: Int): String {
    if (offsetMinutes == 0) return "UTC"
    val sign = if (offsetMinutes > 0) "+" else "-"
    val h = abs(offsetMinutes) / 60
    val m = abs(offsetMinutes) % 60
    return if (m == 0) "UTC$sign$h" else String.format(Locale.ROOT, "UTC%s%d:%02d", sign, h, m)
}
