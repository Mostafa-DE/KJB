package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.backup.SongMatcher
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.KeptCard
import app.lyrify.huddle.Match
import app.lyrify.huddle.Received
import app.lyrify.huddle.Sent
import app.lyrify.huddle.match
import app.lyrify.library.Song
import coil3.compose.AsyncImage

// Huddle's faces, rings and cards, shared by its page, Library's friends row, the player and the Now bar (the approved
// mockup, handoff/huddle/preview-huddle-v4.html, Mix 2). Every motion here stops when the phone's animations are off.

/** A friend's colour, the same on every screen and every start: from their id, KJB's own random one. */
fun friendColor(id: String): Color = FriendColors[Math.floorMod(id.hashCode(), FriendColors.size)]

/**
 * How a face is ringed: playing now (a turning, glowing ring), not right now (a quiet one), away (the quiet one, the face
 * greyed through: the friends row's "disconnected" face, user 2026-11-23), or not at all.
 */
enum class FaceRing { LIVE, IDLE, AWAY, NONE }

/**
 * A face: the first letter of [name], white on [color], or a [cover] (the song a friend plays now) cut round in its place.
 * [ring] LIVE turns a ring of the friend's colour, gold and coral round it with a soft breathing glow, IDLE draws a quiet
 * ring and greys the face a little, AWAY greys it through, as light as its colour so the letter reads as well; [gap] is
 * the ground it sits on, kept as a thin ring between face and ring. [playing] adds the moving bars at its foot, [paused]
 * a still pause mark there, [mail] a small wiggling badge at its top (a card not opened yet), all sized for the friends
 * row's 56 dp faces. Decorative: the name and what they play are words beside it.
 */
@Composable
internal fun FriendFace(
    name: String,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    ring: FaceRing = FaceRing.NONE,
    gap: Color = MaterialTheme.colorScheme.background,
    playing: Boolean = false,
    paused: Boolean = false,
    mail: Boolean = false,
    cover: ImageBitmap? = null,
) {
    // Not right now: the colour a little greyed, toward the mean of its channels. Away: a grey of the colour's own
    // luminance, so lilac keeps the colour's contrast on it.
    val mean = (color.red + color.green + color.blue) / 3
    val ground = when (ring) {
        FaceRing.IDLE -> lerp(color, Color(mean, mean, mean), IDLE_GREY)
        FaceRing.AWAY -> color.luminance().let { Color(it, it, it, colorSpace = ColorSpaces.LinearSrgb).convert(ColorSpaces.Srgb) }
        else -> color
    }
    val letter = with(LocalDensity.current) { (size * INITIAL_SHARE).toSp() }
    Box(modifier.size(size).clearAndSetSemantics {}) {
        when (ring) {
            FaceRing.LIVE -> LiveRing(color)
            FaceRing.IDLE, FaceRing.AWAY -> Box(Modifier.matchParentSize().border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
            FaceRing.NONE -> Unit
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .matchParentSize()
                .padding(if (ring == FaceRing.NONE) 0.dp else size * RING_SHARE + 1.dp)
                .drawBehind {
                    if (ring != FaceRing.NONE) drawCircle(gap, radius = this.size.minDimension / 2 + 2.dp.toPx())
                    drawCircle(Brush.radialGradient(listOf(lerp(ground, BrandLilac, 0.3f), ground), Offset(this.size.width * 0.32f, this.size.height * 0.26f), this.size.width * 0.64f))
                },
        ) {
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(CircleShape))
            } else {
                Text(
                    name.trim().takeIf(String::isNotEmpty)?.let { String(Character.toChars(it.codePointAt(0))).uppercase() }.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = letter, lineHeight = letter),
                    color = BrandLilac,
                    maxLines = 1,
                )
            }
        }
        if (playing || paused) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(3.dp, 3.dp)
                    .size(24.dp)
                    .drawBehind { drawCircle(gap, radius = this.size.minDimension / 2 + 2.dp.toPx()) }
                    .background(lerp(ground, BrandBlack, 0.18f), CircleShape),
            ) {
                if (playing) Equaliser(BrandLilac, Modifier.size(12.dp, 10.dp)) else Icon(painterResource(R.drawable.ic_pause_fill), contentDescription = null, tint = BrandLilac, modifier = Modifier.size(13.dp))
            }
        }
        if (mail) MailBadge(gap, Modifier.align(Alignment.TopEnd).offset(6.dp, (-4).dp))
    }
}

/** An empty seat: a dashed circle with a plus (Invite), or with [word] in it ("You", on the join screen). Decorative. */
@Composable
internal fun SeatFace(size: Dp, modifier: Modifier = Modifier, word: String? = null) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clearAndSetSemantics {}.drawBehind {
            val width = 2.dp.toPx()
            drawCircle(colors.outline, radius = this.size.minDimension / 2 - width, style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        },
    ) {
        if (word != null) {
            Text(word, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1)
        } else {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(size * 0.46f))
        }
    }
}

/** The live ring: a sweep of the friend's colour, gold and coral turning once in [RING_TURN_MS], over a breathing glow; still when motion is off. */
@Composable
private fun BoxScope.LiveRing(color: Color) {
    val coral = LocalBrandColors.current.highlight
    val motion = if (huddleMoves()) rememberInfiniteTransition(label = "live ring") else null
    val turn = motion?.animateFloat(0f, 360f, infiniteRepeatable(tween(RING_TURN_MS, easing = LinearEasing)), label = "turn")
    val glow = motion?.animateFloat(0f, 1f, infiniteRepeatable(tween(GLOW_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    Canvas(Modifier.matchParentSize()) {
        val breath = glow?.value ?: 0.5f
        val radius = size.minDimension / 2
        val reach = radius * (0.92f + 0.16f * breath) + GLOW_REACH.toPx()
        drawCircle(Brush.radialGradient(0.7f to color.copy(alpha = 0.2f + 0.45f * breath), 1f to Color.Transparent, radius = reach), radius = reach)
        rotate(turn?.value ?: 0f) { drawCircle(Brush.sweepGradient(listOf(color, HuddleGold, coral, color)), radius = radius) }
    }
}

/** The badge of a card not opened yet: a coral capsule with the send icon, ringed in [gap], that wiggles now and then. */
@Composable
private fun MailBadge(gap: Color, modifier: Modifier) {
    val brand = LocalBrandColors.current
    val tilt = if (huddleMoves()) rememberInfiniteTransition(label = "mail").animateFloat(
        0f,
        0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = NUDGE_MS
                0f at NUDGE_MS * 70 / 100
                -14f at NUDGE_MS * 76 / 100
                12f at NUDGE_MS * 84 / 100
                -6f at NUDGE_MS * 92 / 100
            },
        ),
        label = "nudge",
    ) else null
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer { rotationZ = tilt?.value ?: 0f }
            .drawBehind { drawRoundRect(gap, Offset(-2.dp.toPx(), -2.dp.toPx()), Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()), CornerRadius(size.height / 2 + 2.dp.toPx())) }
            .background(brand.highlight, CircleShape)
            .size(24.dp),
    ) {
        Icon(painterResource(R.drawable.ic_send), contentDescription = null, tint = brand.onHighlight, modifier = Modifier.size(13.dp))
    }
}

/**
 * The moving bars that mean "playing now": [count] bars (four, or three in the headers' Huddle) rising and falling each
 * at its own pace, still at their resting heights when motion is off. [gap]: the ground they stand on, kept as a thin
 * ring round each, so they read cut out of what is under them. Decorative.
 */
@Composable
internal fun Equaliser(color: Color, modifier: Modifier = Modifier, count: Int = EQ_MS.size, gap: Color? = null) {
    val motion = if (huddleMoves()) rememberInfiniteTransition(label = "equaliser") else null
    val bars = (0 until count).map { at ->
        motion?.animateFloat(0.2f, 1f, infiniteRepeatable(tween(EQ_MS[at], easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(EQ_OFFSET_MS[at], StartOffsetType.FastForward)), label = "bar")
    }
    Canvas(modifier.clearAndSetSemantics {}) {
        val space = size.width * 0.12f
        val width = (size.width - space * (bars.size - 1)) / bars.size
        val ring = EQ_GAP.toPx()
        bars.forEachIndexed { at, bar ->
            val height = size.height * (bar?.value ?: EQ_STILL[at])
            gap?.let { drawRoundRect(it, Offset(at * (width + space) - ring, size.height - height - ring), Size(width + ring * 2, height + ring * 2), CornerRadius(1.dp.toPx() + ring)) }
            drawRoundRect(color, Offset(at * (width + space), size.height - height), Size(width, height), CornerRadius(1.dp.toPx()))
        }
    }
}

/**
 * Huddle at the end of the headers (user 2026-11-24): the sort chip's tonal pill ([LyrifyChip], as Library's "Title"),
 * with the glyph the user picked (2026-11-23), two friends huddled round a note ([R.drawable.ic_huddle]), the note cut
 * out of them, and the word Huddle; with [word] false, on a line too narrow for it ([huddleWord]), the glyph alone.
 * While a friend plays now the note gives way to three coral bars ([Equaliser]) and a coral ring pulses out round the
 * pill; several playing, their count ([CountBadge]); before a name is picked, a small ink plus. With Remove animations,
 * and on a root that is not showing, the bars rest and the ring sits still round the pill. It reads the shell's one
 * state ([LocalDockBar]), as the bar's dot does; its whole look is here, so another can take its place without touching
 * the pages.
 */
@Composable
internal fun HuddleButton(word: Boolean, onClick: () -> Unit) {
    val live = LocalDockBar.current.huddleLive?.value ?: HuddleLive()
    val reduced = LocalReducedMotion.current
    val brand = LocalBrandColors.current
    val chrome = LocalChromeColors.current
    val colors = MaterialTheme.colorScheme
    // What the glyph's note and bars and the plus are cut out of: the pill's colour, a chip's at rest.
    val ground = colors.surfaceContainerHigh
    // The note gives way to the bars, and the ring comes, on one spring; at once with Remove animations.
    val lit by animateFloatAsState(if (live.playing.isNotEmpty()) 1f else 0f, motion(Motion.indicator, reduced), label = "huddle live")
    // The ring's clock, only while a friend plays (or the ring fades).
    val pulse = if (lit <= 0f) null else if (huddleMoves()) rememberInfiniteTransition(label = "huddle ring").animateFloat(0f, 1f, infiniteRepeatable(tween(RING_PULSE_MS, easing = Motion.Pulse)), label = "pulse") else null
    // The count a leaving badge keeps showing.
    val count = remember { Kept(2) }
    if (live.playing.size > 1) count.value = live.playing.size
    val glyph = ImageVector.vectorResource(R.drawable.ic_huddle)
    val (friends, note) = remember(glyph) { listOf("friends", "note").map { name -> glyph.root.filterIsInstance<VectorPath>().first { it.name == name }.pathData.toPath() } }
    val name = dockItemName(
        stringResource(R.string.huddle_title),
        if (!live.joined) stringResource(R.string.huddle_header_not_joined) else null,
        when (live.playing.size) {
            0 -> null
            1 -> stringResource(R.string.huddle_header_playing, live.playing.single())
            else -> pluralStringResource(R.plurals.huddle_header_playing_count, live.playing.size, live.playing.size)
        },
    )
    Box {
        LyrifyChip(
            selected = false,
            onClick = onClick,
            role = Role.Button,
            modifier = Modifier.semantics { contentDescription = name },
            leading = {
                // The sort chip's icon ink.
                val ink = colors.onSurfaceVariant
                val unit = GLYPH / glyph.viewportWidth
                Box(Modifier.size(GLYPH)) {
                    Canvas(Modifier.matchParentSize()) {
                        scale(size.width / glyph.viewportWidth, pivot = Offset.Zero) {
                            drawPath(friends, ink)
                            val shown = 1f - lit.coerceIn(0f, 1f)
                            if (shown > 0f) {
                                scale(0.3f + 0.7f * shown, pivot = note.getBounds().bottomCenter) {
                                    drawPath(note, ground, shown, Stroke(GLYPH_CUT, join = StrokeJoin.Round))
                                    drawPath(note, ink, shown)
                                }
                            }
                        }
                    }
                    if (lit > 0f) {
                        Equaliser(
                            brand.highlight,
                            Modifier
                                .offset(unit * GLYPH_BARS.left, unit * GLYPH_BARS.top)
                                .size(unit * GLYPH_BARS.width, unit * GLYPH_BARS.height)
                                .graphicsLayer {
                                    scaleY = lit
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                },
                            count = 3,
                            gap = ground,
                        )
                    }
                }
            },
            // The ring round the pill, under it, by its outer edge: still, RING_GAP out; moving, from the pill's edge out to
            // RING_REACH.
            capsule = if (lit > 0f) Modifier.drawBehind {
                val width = 1.5.dp.toPx()
                val out = (pulse?.value?.let { RING_REACH * it } ?: RING_GAP).toPx() - width / 2
                drawRoundRect(
                    brand.highlight,
                    Offset(-out, -out),
                    Size(size.width + out * 2, size.height + out * 2),
                    CornerRadius(size.height / 2 + out),
                    alpha = lit.coerceIn(0f, 1f) * (pulse?.value?.let { 0.8f * (1f - it) } ?: 0.6f),
                    style = Stroke(width),
                )
            } else Modifier,
        ) {
            if (word) Text(stringResource(R.string.huddle_title), maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
        }
        AnimatedVisibility(!live.joined, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-1).dp), enter = badgeIn(reduced), exit = badgeOut(reduced)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.clearAndSetSemantics {}.background(ground, CircleShape).padding(2.dp).background(chrome.action, CircleShape).size(18.dp)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = chrome.onAction, modifier = Modifier.size(12.dp))
            }
        }
        AnimatedVisibility(live.joined && live.playing.size > 1, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-2).dp), enter = badgeIn(reduced), exit = badgeOut(reduced)) {
            CountBadge(count.value)
        }
    }
}

/** A song's art for Huddle: its cover when [song] is in your library, else a pattern in the friend's [color] with a note (covers never travel). */
@Composable
internal fun FriendArt(song: Song?, color: Color, size: Dp, shape: Shape, modifier: Modifier = Modifier) {
    if (song != null) {
        SongCover(SongArt(song.path, song.ref.mediaStoreId), modifier, size = size, shape = shape)
        return
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size).clip(shape).background(Brush.radialGradient(listOf(lerp(color, BrandLilac, 0.4f), color)))) {
        Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, tint = BrandLilac.copy(alpha = 0.85f), modifier = Modifier.size(size * 0.38f))
    }
}

/** The friend's song glowing behind a card: your cover blurred, or their colour's pattern when you do not have it. */
@Composable
internal fun BoxScope.FriendGlow(song: Song?, color: Color, blur: Dp, modifier: Modifier = Modifier) {
    if (song == null) {
        Box(modifier.matchParentSize().background(Brush.radialGradient(listOf(lerp(color, BrandLilac, 0.25f), color, lerp(color, BrandBlack, 0.5f)))))
        return
    }
    val context = LocalContext.current
    val art = backdropArt(song.path)
    val request = remember(art, context) { backdropRequest(context, art) }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.matchParentSize().graphicsLayer {
            scaleX = 1.2f
            scaleY = 1.2f
        }.blur(blur, BlurredEdgeTreatment.Unbounded),
    )
}

/** Whether Huddle's endless motion runs here: the phone's animations are on, and this layer shows (a kept dock root that is not showing stops it). */
@Composable
internal fun huddleMoves(): Boolean = !LocalReducedMotion.current && LocalLayerHeaded.current

/** [content] on a ground that is dark in either theme (a friend's card, the empty card): KJB's dark theme for it, light words. */
@Composable
internal fun DarkGround(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography) {
        CompositionLocalProvider(
            LocalChromeColors provides chromeColors(DarkColors, DarkBrandColors, dark = true),
            LocalBrandColors provides DarkBrandColors,
            LocalContentColor provides BrandLilac,
            content = content,
        )
    }
}

/** "New", on a card not opened yet: a small coral capsule. Read by TalkBack with the card. */
@Composable
internal fun NewMark() {
    val brand = LocalBrandColors.current
    Text(
        stringResource(R.string.huddle_new).uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.9f, fontWeight = FontWeight.Bold, letterSpacing = MaterialTheme.typography.labelSmall.fontSize * 0.05f),
        color = brand.onHighlight,
        maxLines = 1,
        modifier = Modifier.background(brand.highlight, RoundedCornerShape(9.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** Whether a friend's song is here: in your library (teal, as suggestions'), maybe, or not (coral). [some]: "3 of 12" for a list. */
@Composable
internal fun HaveChip(match: Match?, modifier: Modifier = Modifier, some: Pair<Int, Int>? = null) {
    val brand = LocalBrandColors.current
    val colors = MaterialTheme.colorScheme
    val have = haveOf(match, some)
    val (ground, ink, icon) = when (have) {
        Have.ALL -> Triple(brand.suggestionContainer, brand.suggestion, R.drawable.ic_check)
        Have.SOME, Have.MAYBE -> Triple(colors.surfaceContainerHighest, colors.onSurface, R.drawable.ic_help)
        Have.NONE -> Triple(brand.highlightContainer, brand.onHighlightContainer, R.drawable.ic_close)
    }
    @StringRes val words = when (have) {
        Have.ALL -> R.string.huddle_found
        Have.SOME -> null
        Have.MAYBE -> R.string.huddle_maybe
        Have.NONE -> R.string.huddle_not_found
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.background(ground, RoundedCornerShape(10.dp)).padding(start = 4.dp, end = 8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        Text(
            words?.let { stringResource(it) } ?: stringResource(R.string.huddle_found_some, some?.first ?: 0, some?.second ?: 0),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A friend playing now, as a big card (Listening now): their song glowing behind, their face with its live ring, "Playing
 * now" with the bars, the song's cover ([song]: yours, else their colour's pattern), title, artist and length, then Play along
 * and Send. Paused, the face is still with a quiet ring, "Paused" without the bars, and Play it in place of Play along.
 * The card's ground is dark in either theme.
 */
@Composable
internal fun FriendCard(friend: FriendNow, song: Song?, name: String, onPlayAlong: () -> Unit, onSend: () -> Unit, onMore: () -> Unit) {
    val play = friend.play ?: return
    val resources = LocalResources.current
    val color = friendColor(friend.friend.id)
    Box(Modifier.width(FRIEND_CARD_WIDTH).clip(RoundedCornerShape(28.dp)).background(HuddleCardGround)) {
        FriendGlow(song, color, 22.dp)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to HuddleCardShade.copy(alpha = 0.3f), 0.7f to HuddleCardShade.copy(alpha = 0.66f))))
        DarkGround {
            Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 14.dp)) {
                val playing = stringResource(if (friend.paused) R.string.huddle_paused else R.string.huddle_playing_now)
                val length = lengthWords(play.song.durationMs)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FriendFace(name, color, 44.dp, ring = if (friend.paused) FaceRing.IDLE else FaceRing.LIVE, gap = HuddleCardGround)
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, playing, songBy(resources, play.song), length.ifEmpty { null }).joinToString(", ") }) {
                        Text(name, style = CardName, color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!friend.paused) Equaliser(BrandLilac.copy(alpha = 0.85f), Modifier.size(15.dp, 12.dp))
                            Text(playing, style = MaterialTheme.typography.bodyMedium, color = BrandLilac.copy(alpha = 0.85f), maxLines = 1)
                        }
                    }
                    ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.huddle_friend_more, name), onMore, glass = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp, end = 6.dp).clearAndSetSemantics {}) {
                    FriendArt(song, color, 76.dp, RoundedCornerShape(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(play.song.title, style = CardTitle, color = BrandLilac, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            play.song.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = BrandLilac.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                            SongLength(play.song.durationMs, color = BrandLilac.copy(alpha = 0.85f))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp, end = 6.dp)) {
                    LyrifyButton(
                        stringResource(if (friend.paused) R.string.huddle_play_it else R.string.huddle_play_along),
                        onClick = onPlayAlong,
                        modifier = Modifier.weight(1f),
                        icon = if (friend.paused) R.drawable.ic_replay else R.drawable.ic_headphones,
                        compact = true,
                    )
                    ChromeIconButton(R.drawable.ic_send, stringResource(R.string.huddle_send_song_to, name), onSend, tint = BrandLilac)
                }
            }
        }
    }
}

/**
 * A card a friend sent, as a postcard (Sent to you): their note in big type, the song's cover as the stamp (yours, else
 * their colour's pattern), then [from]'s face and who sent it ([line]: "From Wren", "From you"), the song (and under it
 * its length, [lengthMs], on a song's card), whether you have it ([have], or [some] of a list; a card you sent that
 * never got there, [undelivered], says so there instead) and a play button. Tilted a little, [tilt] degrees. A tap
 * opens the card; the play button plays it (or opens it, when it is not here).
 */
@Composable
internal fun Postcard(
    note: String?,
    from: String,
    color: Color,
    title: String,
    song: Song?,
    have: Match?,
    some: Pair<Int, Int>?,
    fresh: Boolean,
    tilt: Float,
    line: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    undelivered: Boolean = false,
    lengthMs: Long = 0,
) {
    val paper = if (LocalDarkTheme.current) DarkPaper else LightPaper
    val colors = MaterialTheme.colorScheme
    val chrome = LocalChromeColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(POSTCARD_WIDTH)
            .rotate(tilt)
            .clip(shape)
            .background(paper.paper)
            .border(1.dp, paper.line, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                note?.let { stringResource(R.string.huddle_note_quoted, it) } ?: title,
                style = PostcardNote,
                color = colors.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .rotate(3f)
                    .size(56.dp, 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(chrome.sheet)
                    .drawBehind {
                        val width = 2.dp.toPx()
                        drawRoundRect(lerp(color, paper.paper, 0.45f), Offset(width / 2, width / 2), Size(size.width - width, size.height - width), CornerRadius(6.dp.toPx()), Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    }
                    .padding(6.dp)
                    .clearAndSetSemantics {},
            ) { FriendArt(song, color, 52.dp, RoundedCornerShape(3.dp)) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .drawBehind {
                    val width = 1.dp.toPx()
                    drawLine(paper.line, Offset(0f, 0f), Offset(size.width, 0f), width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                }
                .padding(top = 6.dp),
        ) {
            FriendFace(from, color, 28.dp, gap = paper.paper)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(line, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = lerp(color, colors.onSurface, 0.38f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (fresh) NewMark()
                }
                Text(title, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SongLength(lengthMs)
                if (undelivered) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                        Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = colors.error, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.huddle_not_delivered), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.error, maxLines = 1)
                    }
                } else {
                    HaveChip(have, Modifier.padding(top = 2.dp), some)
                }
            }
            val playLabel = stringResource(R.string.huddle_play_card, title)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(chrome.action)
                    .clickable(role = Role.Button, onClick = onPlay)
                    .semantics { contentDescription = playLabel },
            ) {
                Icon(painterResource(R.drawable.ic_play_arrow_fill), contentDescription = null, tint = chrome.onAction, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/**
 * A card [kept] in the history as a postcard ([Postcard]), its songs matched in this library ([matcher], null while it is
 * read): with [name]'s face in [color] and "From <name>", or for one you sent "From you" (and Not delivered when it never
 * got there); New until a friend's is opened. The Huddle page's Sent to you, its View all and "You and <name>" show them.
 */
@Composable
internal fun KeptPostcard(kept: KeptCard, name: String, color: Color, matcher: SongMatcher?, tilt: Float, onOpen: () -> Unit, onPlay: () -> Unit) {
    val card = kept.card
    val matches = remember(matcher, card) { matcher?.let { found -> card.songs.map(found::match) } }
    Postcard(
        card.note,
        name,
        color,
        cardLine(card),
        song = matches?.firstNotNullOfOrNull { (it as? Match.Found)?.song ?: (it as? Match.Maybe)?.song },
        have = matches?.first(),
        some = matches?.takeIf { card.kind != CardKind.SONG }?.let { found -> found.count { it is Match.Found } to found.size },
        fresh = (kept as? Received)?.opened == false,
        tilt = tilt,
        line = if (kept is Sent) stringResource(R.string.huddle_from_you) else stringResource(R.string.huddle_from, name),
        onOpen = onOpen,
        onPlay = onPlay,
        undelivered = (kept as? Sent)?.delivered == false,
        lengthMs = if (card.kind == CardKind.SONG) card.songs.first().durationMs else 0,
    )
}

/**
 * One face of Library's friends row: [face] over the [name] and a short [line] (their song while they play, in the
 * page's ink, [strong]); a tap does [onClick]. 74 dp wide, a TalkBack stop of its own named [description].
 */
@Composable
internal fun RowFace(name: String, line: String?, strong: Boolean, description: String, onClick: () -> Unit, face: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.padding(bottom = 6.dp)) { face() }
        Text(name, style = RowName, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {}.padding(horizontal = 2.dp))
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (strong) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {}.padding(horizontal = 2.dp)) }
    }
}

/** A section's head on the Huddle page: its name in display type, a small count or note after it, maybe a button at the end. */
@Composable
internal fun HuddleHeading(title: String, count: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = ScreenPadding + 4.dp, end = 12.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = HeadingStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            count?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        action?.invoke()
    }
}

/**
 * No friend yet (the mockup's empty cards): three empty cards bobbing on a warm gradient, "Your Huddle has room for
 * friends", one line, then Invite a friend and Paste invite. The cards stay still when motion is off.
 */
@Composable
internal fun EmptyCard(onInvite: () -> Unit, onPaste: () -> Unit) {
    val bob = if (huddleMoves()) rememberInfiniteTransition(label = "empty cards").animateFloat(0f, 1f, infiniteRepeatable(tween(BOB_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob") else null
    Column(
        Modifier
            .padding(horizontal = ScreenPadding)
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(HuddleEmptyGround)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[0], Color.Transparent), Offset(size.width * 0.15f, size.height * 0.15f), size.maxDimension * 0.55f))
                drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[1], Color.Transparent), Offset(size.width * 0.9f, size.height * 0.25f), size.maxDimension * 0.55f))
                drawRect(Brush.radialGradient(listOf(HuddleEmptyGlows[2], Color.Transparent), Offset(size.width * 0.5f, size.height * 1.1f), size.maxDimension * 0.6f))
            }
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 18.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(104.dp).clearAndSetSemantics {}) {
            listOf(-1, 1, 0).forEach { side ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    modifier = Modifier
                        .graphicsLayer {
                            val rise = (bob?.value ?: 0f) * if (side == 0) 14.dp.toPx() else 8.dp.toPx()
                            translationX = side * 74.dp.toPx()
                            translationY = -rise - if (side == 0) 6.dp.toPx() else 0f
                            rotationZ = side * (10f + 3f * (bob?.value ?: 0f))
                        }
                        .size(82.dp, 104.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(BrandLilac.copy(alpha = if (side == 0) 0.22f else 0.14f))
                        .drawBehind {
                            val width = 1.5.dp.toPx()
                            drawRoundRect(BrandLilac.copy(alpha = 0.55f), Offset(width / 2, width / 2), Size(size.width - width, size.height - width), CornerRadius(20.dp.toPx()), Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                        },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(BrandLilac.copy(alpha = 0.22f), CircleShape)) {
                        Text("?", style = MaterialTheme.typography.titleLarge, color = BrandLilac)
                    }
                    Box(Modifier.size(44.dp, 6.dp).background(BrandLilac.copy(alpha = 0.4f), RoundedCornerShape(3.dp)))
                }
            }
        }
        Text(stringResource(R.string.huddle_empty_title), style = EmptyTitle, color = BrandLilac, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() })
        Text(stringResource(R.string.huddle_empty_text), style = MaterialTheme.typography.bodyLarge, color = BrandLilac.copy(alpha = 0.9f), modifier = Modifier.padding(bottom = 16.dp))
        DarkGround {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LyrifyButton(stringResource(R.string.huddle_invite), onClick = onInvite, icon = R.drawable.ic_add, compact = true)
                LyrifyButton(stringResource(R.string.huddle_paste), onClick = onPaste, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_content_paste, compact = true)
            }
        }
    }
}

internal val FRIEND_CARD_WIDTH = 268.dp
private val POSTCARD_WIDTH = 236.dp

/** A ring takes this share of a face's size, plus a dp, between its edge and the face. */
private const val RING_SHARE = 0.08f
private const val INITIAL_SHARE = 0.4f
private const val IDLE_GREY = 0.45f
private const val RING_TURN_MS = 2_800
private const val GLOW_MS = 1_600
private val GLOW_REACH = 7.dp
private const val NUDGE_MS = 2_400
private val EQ_MS = intArrayOf(700, 900, 600, 850)
private val EQ_OFFSET_MS = intArrayOf(0, 300, 500, 200)
private val EQ_STILL = floatArrayOf(0.45f, 1f, 0.7f, 0.35f)

/** The ring kept round each bar with a gap: as wide as the headers' glyph keeps its note clear of the friends. */
private val EQ_GAP = 0.65.dp

/** The headers' Huddle glyph's size in its pill: the header's icons' size, not a chip's 18 dp, so its note and bars read. */
private val GLYPH = 22.dp

/** How far the note's cut reaches round it, on the glyph's 24 grid: 0.7 each side (the mockup's 1.4 knock-out stroke). */
private const val GLYPH_CUT = 1.4f

/** Where the three bars stand in place of the note, on the glyph's 24 grid (the mockup's). */
private val GLYPH_BARS = Rect(8.9f, 7f, 15.15f, 21.2f)

/**
 * The live ring round the headers' Huddle pill, by its outer edge: still, [RING_GAP] out at 60%; moving, it pulses from
 * the pill's edge out to [RING_REACH], fading from 80%, every [RING_PULSE_MS] on [Motion.Pulse] (the mockup's). The
 * reach it had round the glass circle, whose ring went from 92% to 136% of 44 dp.
 */
private val RING_GAP = 2.dp
private val RING_REACH = 10.dp
private const val RING_PULSE_MS = 2_200

private val CardName = display(17, 22, weight = 650)
private val CardTitle = display(21, 26, -0.01, weight = 700)
private val PostcardNote = display(18, 23, -0.005, weight = 650)
private val HeadingStyle = display(18, 24, weight = 650)
private val RowName = body(13f, 17, 600)

/** How long a card of the empty state takes to rise and settle back. */
private const val BOB_MS = 3_200


private val EmptyTitle = display(26, 30, -0.01, weight = 700)
