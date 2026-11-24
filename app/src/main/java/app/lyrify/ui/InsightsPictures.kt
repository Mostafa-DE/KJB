package app.lyrify.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.lyrify.R
import app.lyrify.library.displayTitle
import app.lyrify.settings.Settings
import app.lyrify.stats.InsightsCards
import app.lyrify.stats.InsightsPage
import app.lyrify.stats.TraitKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Insights' pictures to share (the approved mockup's part 2): the summary, the top albums' collage and the night owl's
// record, each drawn on screen as it is shared through the phone's share sheet.

// The pictures to share

/** The pictures Insights can share: the summary, the top albums' collage, and the night owl's record. */
enum class Picture { SUMMARY, ALBUMS, CLOCK }

/** The pictures [cards] has enough for: the summary with its time, the collage with 6 albums, the record for a night owl. */
fun picturesOf(cards: InsightsCards): List<Picture> = buildList {
    if (cards.time != null) add(Picture.SUMMARY)
    if (cards.albums?.rows.orEmpty().size >= COLLAGE) add(Picture.ALBUMS)
    if (cards.clock != null && cards.traits.any { it.kind == TraitKind.NIGHT_OWL }) add(Picture.CLOCK)
}

/**
 * The pictures to share, side by side ([picturesOf]), [first] the one asked for, each with Share under it: made on the
 * phone and sent through its share sheet as a PNG, exactly as drawn here. Covers go in them (the user's call, the
 * approved mockup); search words never do.
 */
@Composable
internal fun PictureSheet(first: Picture, page: InsightsPage, settings: Settings, onDismiss: () -> Unit) {
    val pictures = picturesOf(page.cards).let { all -> listOf(first).filter { it in all } + (all - first) }
    LyrifySheet(onDismiss = onDismiss, spacing = 12.dp) {
        Text(stringResource(R.string.insights_share), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(pictures, key = { it.name }) { picture -> SharePicture(picture, page, settings) }
        }
    }
}

/**
 * One picture to share (the mockup's 9:16 cards): KJB's mark and the story's name over its ground, its figures, and
 * "Made with KJB"; drawn on screen as it is shared, with Share under it.
 */
@Composable
internal fun SharePicture(picture: Picture, page: InsightsPage, settings: Settings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val cards = page.cards
    val period = page.period
    val title = storyName(period)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier) {
        Column(
            Modifier
                .width(PICTURE_WIDTH)
                .aspectRatio(9f / 16f)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
                .clip(RoundedCornerShape(26.dp))
                .drawBehind {
                    when (picture) {
                        Picture.SUMMARY -> introGround()
                        Picture.ALBUMS -> drawRect(Brush.linearGradient(HuddleTicket, Offset(size.width * 0.2f, 0f), Offset(size.width * 0.8f, size.height)))
                        Picture.CLOCK -> {
                            drawRect(Brush.verticalGradient(StoryNight.drop(1)))
                            drawRect(Brush.radialGradient(listOf(StoryNight[0], Color.Transparent), Offset(size.width * 0.7f, size.height * 0.2f), size.maxDimension * 0.6f))
                        }
                    }
                }
                .semantics(mergeDescendants = true) {}
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.kjb_default_cover), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).border(1.dp, BrandBlackBorder, RoundedCornerShape(7.dp)))
                Text(storyLabel(period).uppercase(), style = CardKicker.copy(fontSize = 11.sp, lineHeight = 14.sp), color = StoryKicker, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (picture) {
                Picture.SUMMARY -> cards.time?.let { time ->
                    Text(title, style = display(30, 31, -0.03, weight = 700), color = BrandLilac, modifier = Modifier.padding(top = 12.dp))
                    Text(bigTime(time.totals.heardMs, SpanStyle(fontSize = 16.sp, color = BrandLilac.copy(alpha = 0.85f))), style = display(38, 38, -0.035, weight = 700).tabular(), color = BrandLilac, maxLines = 1, modifier = Modifier.padding(top = 10.dp))
                    Text(
                        stringResource(R.string.stats_row_line, playsNote(time.totals.plays), pluralStringResource(R.plurals.insights_songs, time.totals.songs, formatCount(time.totals.songs))),
                        style = body(12f, 16, 500),
                        color = BrandLilac.copy(alpha = 0.82f),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 14.dp)) {
                        cards.songs?.rows.orEmpty().take(TOP_SHOWN).forEachIndexed { i, row ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text((i + 1).toString(), style = display(13, 13, weight = 700), color = StoryKicker, modifier = Modifier.width(14.dp))
                                SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), size = 32.dp, shape = RoundedCornerShape(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(displayTitle(row.top.item, settings.library), style = body(12.5f, 15, 600), color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.top.item.artist?.let { Text(it, style = body(11f, 14), color = BrandLilac.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                                        SongLength(row.top.item.durationMs, color = BrandLilac.copy(alpha = 0.75f))
                                    }
                                }
                            }
                        }
                    }
                }
                Picture.ALBUMS -> cards.albums?.let { albums ->
                    Text(stringResource(R.string.stats_top_albums), style = display(30, 31, -0.03, weight = 700), color = BrandLilac, modifier = Modifier.padding(top = 12.dp))
                    BoxWithConstraints(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                        val gap = 4.dp
                        val cell = (maxWidth - gap * 2) / 3
                        albums.rows.take(COLLAGE).forEachIndexed { i, row ->
                            val x = when (i) {
                                0, 3 -> 0.dp
                                4 -> cell + gap
                                else -> (cell + gap) * 2
                            }
                            val y = when (i) {
                                0, 1 -> 0.dp
                                2 -> cell + gap
                                else -> (cell + gap) * 2
                            }
                            SongCover(SongArt(row.cover.path, row.cover.ref.mediaStoreId), Modifier.offset(x, y), size = if (i == 0) cell * 2 + gap else cell, shape = RoundedCornerShape(if (i == 0) 12.dp else 8.dp))
                        }
                        Spacer(Modifier.height(cell * 3 + gap * 2))
                    }
                    val top = albums.rows.first().top.item
                    Text(
                        stringResource(R.string.insights_picture_album_top, top.title, top.artist.orEmpty()),
                        style = body(12f, 16, 500),
                        color = BrandLilac.copy(alpha = 0.82f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Picture.CLOCK -> cards.clock?.let { clock ->
                    val owl = cards.traits.firstOrNull { it.kind == TraitKind.NIGHT_OWL }
                    Text(traitName(TraitKind.NIGHT_OWL), style = display(30, 31, -0.03, weight = 700), color = BrandLilac, modifier = Modifier.padding(top = 12.dp))
                    Dial(
                        clock.hours,
                        clock.peak,
                        BrandLilac.copy(alpha = 0.06f),
                        BrandLilac.copy(alpha = 0.14f),
                        DarkInsights.series[0],
                        HuddleGold,
                        BrandLilac.copy(alpha = 0.75f),
                        BrandLilac,
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).size(190.dp),
                    )
                    owl?.let { Text(stringResource(R.string.insights_picture_owl, it.percent), style = body(12f, 16, 500), color = BrandLilac.copy(alpha = 0.82f)) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(stringResource(R.string.insights_picture_footer), style = body(11f, 14, 600), color = BrandLilac.copy(alpha = 0.78f), modifier = Modifier.weight(1f))
                when (picture) {
                    Picture.SUMMARY -> cards.artists?.rows?.firstOrNull()?.let { stringResource(R.string.insights_top_artist, it.top.item.name) }
                    Picture.ALBUMS -> cards.time?.let { pluralStringResource(R.plurals.insights_picture_albums_of, it.totals.albums, COLLAGE, it.totals.albums) }
                    Picture.CLOCK -> cards.clock?.let { stringResource(R.string.insights_picture_busiest, hourLabel(it.peak)) }
                }?.let { Text(it, style = body(11f, 14, 600), color = BrandLilac.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        DarkGround {
            LyrifyButton(
                stringResource(R.string.insights_share),
                onClick = { scope.launch { toasts.say(resources, sharePicture(context, layer, "${period.kind.name.lowercase(Locale.ROOT)}-${period.firstDay}-${picture.name.lowercase(Locale.ROOT)}", title)) } },
                icon = R.drawable.ic_share,
            )
        }
    }
}

/**
 * Shares [picture] (as drawn on screen) through the phone's share sheet as a PNG named for [name], with [title] as the
 * subject. Nothing is uploaded: the user picks where it goes.
 */
internal suspend fun sharePicture(context: Context, picture: GraphicsLayer, name: String, title: String): Shared {
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
        withContext(Dispatchers.IO) { shareCopy(context.cacheDir, "kjb-$name.png", png, System.currentTimeMillis()) }
    } catch (_: IOException) {
        return Shared.NoPicture
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + SHARE_AUTHORITY, file)
    return launchShare(1) { context.startActivity(shareSheet(recapImageShare(uri.toString(), title), context.packageName)) }
}

private val PICTURE_WIDTH = 260.dp

/** PNG ignores the quality; the platform asks for one. */
private const val PNG_QUALITY = 100
