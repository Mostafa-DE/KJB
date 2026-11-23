package app.lyrify.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.Settings
import app.lyrify.stats.Comeback
import app.lyrify.stats.FavouritesCard
import app.lyrify.stats.FoundCard
import app.lyrify.stats.FriendsCard
import app.lyrify.stats.LibraryCard
import app.lyrify.stats.ListeningPeriod
import app.lyrify.stats.LyricsCard
import app.lyrify.stats.MilestoneKind
import app.lyrify.stats.MilestonesCard
import app.lyrify.stats.NextMilestone
import app.lyrify.stats.PagePeriod
import app.lyrify.stats.SearchesCard
import app.lyrify.stats.SpokenCard
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.Top
import app.lyrify.stats.Trait
import app.lyrify.stats.TraitKind
import app.lyrify.stats.recapDue
import app.lyrify.stats.recapMonthOf
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

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

// Books and podcasts

/** Books and podcasts in real minutes, each on its own tile, then the time faster speeds saved, the bookmarks and the books finished. */
@Composable
internal fun BooksAndPodcasts(spoken: SpokenCard) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val unit = SpanStyle(fontSize = 12.sp, fontWeight = FontWeight(650))
    InsightsCard {
        CardHead(stringResource(R.string.insights_spoken_title), R.drawable.ic_auto_stories, stringResource(R.string.insights_spoken_line))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple(spoken.booksMs, R.drawable.ic_auto_stories, R.string.insights_in_books), Triple(spoken.podcastsMs, R.drawable.ic_headset_mic, R.string.insights_in_podcasts))
                .filter { it.first > 0 }
                .forEach { (ms, icon, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f).background(colors.surface, RoundedCornerShape(20.dp)).padding(12.dp).semantics(mergeDescendants = true) {},
                    ) {
                        Tile(icon, brand.highlightContainer, brand.onHighlightContainer, size = 44.dp)
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
private fun shuffle(context: Context, songs: List<Song>, key: String, name: String) =
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

private val PillLabel = body(12f, 16, 650)

private const val FACES = 6
private const val FACE_MS = 520
private const val FACE_STEP_MS = 70
private const val BACK_AGAIN = 3
private const val REACHED = 4
private const val RING_MS = 900
private const val LIBRARY_RING_MS = 1_300
private const val SUNG_MS = 2_400
private const val BEAT_MS = 1_600
private const val SHINE_MS = 4_000
private const val HOUR_MS = 3_600_000L

/** From this font scale the bento's tiles stand one above the other. */
private const val STACK_FONT_SCALE = 1.3f

private const val NEVER_PLAYED_KEY: String = "insights-never-played"
private const val FORGOTTEN_KEY: String = "insights-forgotten"
