package app.lyrify.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.lerp
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.PlainLyrics
import app.lyrify.lyrics.readSidecar
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LyricsAlign
import app.lyrify.settings.LyricsSettings
import app.lyrify.settings.LyricsSize
import app.lyrify.settings.LyricsWeight
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import java.io.IOException

// Lyrics on the player (P3): the song's lyrics as the views read them, the lines the lyrics panel (PlayerScreen.kt),
// full lyrics and the lock screen draw with the "Aa" choices, full lyrics over the player, and the sheets behind the
// panel's routes ("Aa", Search). Lyrics text is never logged.

// Rules, as pure functions

/** The "Aa" choices as lyrics are drawn: [scale] on each view's sizes, the weights of the line playing and the others, the alignment. */
data class LyricsLook(val scale: Float, val current: FontWeight, val other: FontWeight, val align: TextAlign, val plain: FontWeight = other)

fun lyricsLook(settings: LyricsSettings): LyricsLook {
    val bold = settings.textWeight == LyricsWeight.BOLD
    return LyricsLook(
        scale = when (settings.textSize) {
            LyricsSize.S -> 0.82f
            LyricsSize.M -> 1f
            LyricsSize.L -> 1.18f
            LyricsSize.XL -> 1.36f
        },
        // The prototype's --lw and --lw2: the line playing stands out by its size and colour, not its weight.
        current = if (bold) FontWeight(650) else FontWeight(440),
        other = if (bold) FontWeight(650) else FontWeight(440),
        align = if (settings.textAlign == LyricsAlign.CENTRE) TextAlign.Center else TextAlign.Start,
        plain = if (bold) FontWeight(560) else FontWeight.Normal,
    )
}

/** [base] (a view's style for the line playing, or for the others, or for [still] plain lyrics) with the size and weight chosen. */
fun LyricsLook.style(base: TextStyle, playing: Boolean, still: Boolean = false): TextStyle = base.copy(
    fontSize = base.fontSize * scale,
    lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * scale else base.lineHeight,
    fontWeight = when {
        still -> plain
        playing -> current
        else -> other
    },
)

/**
 * The LRCLIB record whose plain lyrics stand in for a missing `.lrc`: a song LRCLIB has only plain lyrics for. They are
 * fetched when shown ([LyricsPipeline.plainLyrics]) and kept in memory only. Null for any other song.
 */
fun plainRecord(entry: LedgerEntry?): Long? = entry?.takeIf { it.status == LyricsStatus.PLAIN_ONLY }?.lrclibId

/** Plain lyrics as lines: each trimmed, without empty lines and header tags (`[ar:...]`). */
fun plainLines(text: String): List<String> = text.removePrefix("\uFEFF").lines().map(String::trim).filter { it.isNotEmpty() && !HEADER_LINE.matches(it) }

private val HEADER_LINE = Regex("""^\[[a-zA-Z#]+:.*]$""")

/** Which editor the lyrics' "⋯" opens: Edit timing for synced lyrics, Make synced for plain ones. */
enum class SyncAction { MAKE, EDIT }

fun syncAction(lyrics: PanelLyrics?): SyncAction? = when (lyrics) {
    is PanelLyrics.Synced -> SyncAction.EDIT
    is PanelLyrics.Plain -> SyncAction.MAKE
    else -> null
}

/** A tap on a synced line: the song goes to the line's start, and plays when it was paused. */
data class LineTap(val seekMs: Long, val play: Boolean)

fun lineTap(line: LrcLine, playing: Boolean): LineTap = LineTap(line.timeMs, play = !playing)

// The song's lyrics

/** The `.lrc` next to the song at [path]; null without one, or for a link, a folder or a file too large to be lyrics ([readSidecar]). Blocking. */
fun readLrc(path: String): String? = try {
    LrcFormat.sidecarPath(path)?.let { readSidecar(File(it).toPath()) }?.decodeToString()
} catch (_: IOException) {
    null
}

/** LRCLIB's plain lyrics by record, for the views and the editor: a few songs' worth, in memory for this run only. */
private object PlainCache {
    private val texts = object : LinkedHashMap<Long, String>(PLAIN_KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > PLAIN_KEPT
    }

    @Synchronized
    fun get(id: Long): String? = texts[id]

    @Synchronized
    fun put(id: Long, text: String) {
        texts[id] = text
    }
}

private const val PLAIN_KEPT = 8

/** LRCLIB's plain lyrics of record [id] ([plainRecord]); null when it has none, or cannot be asked now. */
suspend fun plainText(context: Context, id: Long): String? {
    PlainCache.get(id)?.let { return it }
    val found = withContext(Dispatchers.IO) { Lyrify.pipeline(context.applicationContext).plainLyrics(id) }
    return (found as? PlainLyrics.Found)?.text?.also { PlainCache.put(id, it) }
}

/**
 * The lyrics of the song at [path] as the views show them: its `.lrc` read off the main thread, again whenever its
 * ledger [entry] changes; without a file, the plain lyrics LRCLIB has for it ([plainRecord]). Null while reading.
 */
@Composable
fun rememberSongLyrics(path: String, entry: LedgerEntry?): PanelLyrics? {
    val context = LocalContext.current
    val lyrics by produceState<PanelLyrics?>(null, path, entry?.status, entry?.updatedAt) {
        val read = withContext(Dispatchers.IO) { panelLyrics(readLrc(path), entry?.status) }
        val id = plainRecord(entry)
        value = if (read == PanelLyrics.None && id != null) {
            plainText(context, id)?.let(::plainLines)?.takeIf { it.isNotEmpty() }?.let(PanelLyrics::Plain) ?: read
        } else {
            read
        }
    }
    return lyrics
}

/** The "Aa" choices, as stored. */
@Composable
fun rememberLyricsLook(): LyricsLook {
    val context = LocalContext.current
    val store = remember(context) { Lyrify.settings(context) }
    val lyrics = store.settings.collectAsStateWithLifecycle(initialValue = null).value?.lyrics
    return remember(lyrics) { lyricsLook(lyrics ?: LyricsSettings()) }
}

// The lines

/**
 * Where a view's synced lines sit: [sides] margin, room above the first line and below the last ([top], [bottom], shares
 * of the list's height, so any line can reach the anchor), and the [anchor] the line playing is kept at.
 */
@Immutable
data class LyricsFrame(val sides: Dp, val top: Float, val anchor: Float, val bottom: Float)

/** The colours of the lines: the one playing ([now], all of it), those sung before it, and those to come. */
@Immutable
data class LineTones(val now: Color, val past: Color, val next: Color)

/**
 * The lines' colours on [scheme]: the line playing in the accent, the orange of the song playing's title (on light a
 * little darker toward its own ink, so it reads as well as the rest), sung lines in outline as before, and the lines to
 * come at [NEXT_INK] of the ink: clearly dimmer than the lit line, by lightness as well as by hue.
 */
fun lyricTones(scheme: ColorScheme, brand: BrandColors, dark: Boolean): LineTones = LineTones(
    now = if (dark) brand.highlight else lerp(brand.highlight, brand.onHighlightContainer, LIGHT_ACCENT_DEPTH),
    past = scheme.outline,
    next = scheme.onSurface.copy(alpha = NEXT_INK),
)

/** The theme's line colours ([lyricTones]). */
@Composable
fun themeTones(): LineTones = lyricTones(MaterialTheme.colorScheme, LocalBrandColors.current, LocalDarkTheme.current)

/** How much of the ink the lines to come take. */
internal const val NEXT_INK = 0.58f

/** How far the accent goes toward its own ink on light, for the lit line's contrast. */
private const val LIGHT_ACCENT_DEPTH = 0.25f

/** Where a line is in the song: sung, playing (a whole group of lines sharing a time plays together), or to come. */
enum class LineState { PAST, NOW, NEXT }

/** [index]'s place against the line playing [at] (the first of its group, [currentLine]; -1 before the first). */
fun lineState(lines: List<LrcLine>, at: Int, index: Int): LineState {
    if (at < 0) return LineState.NEXT
    val now = lines[at].timeMs
    val time = lines[index].timeMs
    return when {
        time < now -> LineState.PAST
        time == now -> LineState.NOW
        else -> LineState.NEXT
    }
}

/** A line's one colour: the whole line playing in [LineTones.now], never word by word. */
fun lineColor(state: LineState, tones: LineTones): Color = when (state) {
    LineState.PAST -> tones.past
    LineState.NOW -> tones.now
    LineState.NEXT -> tones.next
}

/** A line's words as shown: an enhanced LRC's word times (`<01:02.30>`) are for timing only, never shown. */
fun shownLine(text: String): String = text.replace(WORD_TIME, "").replace(SPACES, " ").trim()

private val WORD_TIME = Regex("""<\d+:\d+(?:[.:]\d+)?>""")
private val SPACES = Regex("""\s{2,}""")

/** The first line after [at]'s group: where the song goes next on its own. 0 before the first line. */
private fun nextStart(lines: List<LrcLine>, at: Int): Int {
    if (at < 0) return 0
    val time = lines[at].timeMs
    return (at + 1 until lines.size).firstOrNull { lines[it].timeMs > time } ?: lines.size
}

/**
 * Synced lines with the "Aa" choices, every one in the same [style] (the line playing never grows, so nothing reflows):
 * the line playing is lit whole by its colour, as Samsung Music does (lines sharing its time with it), never word by
 * word; word times in the file are not shown. Each line fades to its colour. The list follows the song with a smooth
 * scroll that keeps the line playing at the frame's anchor ([followLine]): after a seek or a tap on a line at once, but
 * never while a finger is on it, and for [LEAVE_ALONE_MS] after the user scrolls ([LyricsFollow]). With reduced motion
 * the list moves at once and the colours switch.
 */
@Composable
fun SyncedLyrics(
    lines: List<LrcLine>,
    positionMs: Long,
    enabled: Boolean,
    style: TextStyle,
    frame: LyricsFrame,
    tones: LineTones = themeTones(),
    onLine: (LrcLine) -> Unit,
) {
    val look = rememberLyricsLook()
    val shown = look.style(style, playing = false)
    val list = rememberLazyListState()
    val reduced = LocalReducedMotion.current
    val at = currentLine(lines, positionMs)
    var follow by remember { mutableStateOf(LyricsFollow()) }
    // The line the list last went toward: a jump from it (a seek) ends the wait.
    val last = remember { Kept(at) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = constraints.maxHeight
        val padTop = (viewport * frame.top).roundToInt()
        LaunchedEffect(at, follow, viewport) {
            if (last.value != at) {
                val moved = follow.moved(last.value, at, nextStart(lines, last.value))
                last.value = at
                if (moved != follow) {
                    follow = moved
                    return@LaunchedEffect
                }
            }
            if (at < 0) return@LaunchedEffect
            delay(follow.waitMs(SystemClock.elapsedRealtime()))
            // A finger still on the list: its release starts the wait again.
            // A finger still on the list: its lift starts the wait again.
            if (!follow.follows(SystemClock.elapsedRealtime())) return@LaunchedEffect
            followLine(LazyLines(list), at, padTop, viewport, frame.anchor, smooth = !reduced)
        }
        val density = LocalDensity.current
        val fade = with(density) { LYRICS_FADE.toPx() }
        LazyColumn(
            state = list,
            userScrollEnabled = enabled,
            // The lines dissolve into the top and bottom edges instead of being cut; every frame keeps more room than
            // the fade above its first line and below its last, so each can still rest in full view.
            modifier = Modifier
                .fillMaxSize()
                // Any finger on the list holds it from the moment it touches, scrolling or not; the wait starts as the last one lifts.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                            if (pressed != follow.held) follow = if (pressed) follow.down(SystemClock.elapsedRealtime()) else follow.up(SystemClock.elapsedRealtime())
                        }
                    }
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edge = (fade / size.height).coerceIn(0f, 0.5f)
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
            contentPadding = with(density) { PaddingValues(start = frame.sides, end = frame.sides, top = padTop.toDp(), bottom = (viewport * frame.bottom).toDp()) },
        ) {
            itemsIndexed(lines, contentType = { _, _ -> "line" }) { i, line ->
                LyricLine(line, lineColor(lineState(lines, at, i), tones), shown, look.align, reduced, enabled) {
                    follow = follow.tapped()
                    onLine(line)
                }
            }
        }
    }
}

/** One synced line, all of it in one colour that fades over the token's 300 ms, linear (colour only, never its size). */
@Composable
private fun LyricLine(line: LrcLine, tone: Color, style: TextStyle, align: TextAlign, reduced: Boolean, enabled: Boolean, onTap: () -> Unit) {
    val text = remember(line.text) { shownLine(line.text) }
    val label = stringResource(R.string.player_line_at, clock(line.timeMs), text)
    val color by animateColorAsState(tone, motion(tween(Motion.OPEN_MS, easing = LinearEasing), reduced), label = "line colour")
    Text(
        text,
        style = style,
        textAlign = align,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onTap)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** The lines as [followLine] sees them: where a line is laid out ([laidOut]: its top and height, px), and two ways to scroll. */
interface LineList {
    fun laidOut(index: Int): Pair<Int, Int>?

    /** The height of the first line laid out, to guess an unseen line's by. */
    fun firstHeight(): Int

    suspend fun jumpTo(index: Int, offset: Int, smooth: Boolean)

    suspend fun moveBy(px: Int, smooth: Boolean)
}

/**
 * Scrolls to the line [at] until its middle sits at [anchor] of the [viewport]: from where it is when laid out, else
 * to its row first (its height guessed from the first line laid out), then by what is left once its own height is
 * known. Smooth on the standard curve, or at once.
 */
suspend fun followLine(list: LineList, at: Int, padTop: Int, viewport: Int, anchor: Float, smooth: Boolean) {
    if (list.laidOut(at) == null) list.jumpTo(at, followOffset(padTop, list.firstHeight(), viewport, anchor), smooth)
    val (top, size) = list.laidOut(at) ?: return
    val delta = followDelta(top, size, viewport, anchor)
    if (delta != 0) list.moveBy(delta, smooth)
}

/** A LazyColumn's lines for [followLine]: tops measured from the list's top. */
private class LazyLines(private val list: LazyListState) : LineList {
    override fun laidOut(index: Int): Pair<Int, Int>? {
        val info = list.layoutInfo
        return info.visibleItemsInfo.firstOrNull { it.index == index }?.let { (it.offset - info.viewportStartOffset) to it.size }
    }

    override fun firstHeight(): Int = list.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0

    override suspend fun jumpTo(index: Int, offset: Int, smooth: Boolean) {
        if (smooth) list.animateScrollToItem(index, offset) else list.scrollToItem(index, offset)
    }

    override suspend fun moveBy(px: Int, smooth: Boolean) {
        if (smooth) list.animateScrollBy(px.toFloat(), tween(Motion.OPEN_MS, easing = Motion.Standard)) else list.scrollBy(px.toFloat())
    }
}

/** How far the lyrics fade into the list's top and bottom edges. */
internal val LYRICS_FADE = 28.dp

/** Each view's frame, after the prototype's: the cover's back (.lplines, 38% anchor) and full lyrics (.flines, 40%). */
internal val PanelFrame = LyricsFrame(sides = 12.dp, top = 0.26f, anchor = 0.38f, bottom = 0.52f)
internal val FullFrame = LyricsFrame(sides = 16.dp, top = 0.38f, anchor = 0.4f, bottom = 0.45f)

/** Plain lyrics as still text, with the "Aa" choices. */
@Composable
fun StillLyrics(lines: List<String>, style: TextStyle, padding: PaddingValues) {
    val look = rememberLyricsLook()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
        lines.forEach { Text(it, style = look.style(style, playing = false, still = true), textAlign = look.align, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
    }
}

/**
 * The lyrics text: full screen (.flines: 25 sp lines, 21 sp plain), the cover's back (.lplines: 19, 17 plain). The line
 * playing keeps its size (the user's rule, against the prototype's growing line): only its colour changes.
 */
internal val FullLine = display(25, 33, -0.015)
internal val FullStill = display(21, 31, weight = 560)
internal val PanelLine = display(19, 25, -0.01)
internal val PanelStill = display(17, 26, weight = 560)

/** "Finding lyrics", "No lyrics yet" (.lpstate b), and the "Aa" button's letters. */
internal val LyricsStateTitle = display(19, 24, -0.01)
internal val AaLetters = display(16, 16, -0.02, weight = 650)

/** After the user scrolls the lyrics, the list is left alone this long (the prototype's 3.5 s). */
const val LEAVE_ALONE_MS = 3_500L

/**
 * Whether the synced lines follow the song: not while the user scrolls them, nor for [LEAVE_ALONE_MS] after their
 * last touch ([touchedAt], elapsed realtime); a tap on a line follows again at once (the prototype's rule).
 */
@Immutable
data class LyricsFollow(val touchedAt: Long = NEVER, val held: Boolean = false) {
    /** The list was touched and let go at [now]. */
    fun touched(now: Long): LyricsFollow = copy(touchedAt = now, held = false)

    fun tapped(): LyricsFollow = LyricsFollow()

    /** Never while a finger is on the list ([held]), however long and however still; else once the wait is over. */
    fun follows(now: Long): Boolean = !held && now - touchedAt >= LEAVE_ALONE_MS

    /** A finger touched the list, moving or not: it stays where it is for as long as one is on it. */
    fun down(now: Long): LyricsFollow = copy(touchedAt = now, held = true)

    /** The last finger lifted: the wait starts now. */
    fun up(now: Long): LyricsFollow = copy(touchedAt = now, held = false)

    /** The line playing went [from] one line [to] another: on to the [next] (or nowhere) the wait goes on; a seek ends it. */
    fun moved(from: Int, to: Int, next: Int = from + 1): LyricsFollow = if (to == from || to == next) this else tapped()

    /** How long until it follows again: 0 when it does. */
    fun waitMs(now: Long): Long = (touchedAt + LEAVE_ALONE_MS - now).coerceAtLeast(0)

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
    }
}

/**
 * How far to scroll so the line playing, laid out [itemTop] px from the list's top and [itemSize] px tall, has its
 * middle at [anchor] (a share of the list's [viewport] height): positive scrolls on.
 */
fun followDelta(itemTop: Int, itemSize: Int, viewport: Int, anchor: Float): Int = itemTop - (viewport * anchor - itemSize / 2f).roundToInt()

/**
 * The offset to scroll to a line not laid out yet (LazyListState.scrollToItem): its top lands under the list's top
 * padding ([padTop]) less the offset, so this puts its middle at [anchor], taking [rowSize] for its height.
 */
fun followOffset(padTop: Int, rowSize: Int, viewport: Int, anchor: Float): Int = padTop - (viewport * anchor - rowSize / 2f).roundToInt()

// Full lyrics

/**
 * Full lyrics, over the player: the song, "Aa", and "⋯" with Edit timing (synced lyrics) or Make synced (plain ones);
 * the lines large, a tap on one goes there; "No lyrics yet" with Search; the position and Play/Pause at the bottom. It
 * closes itself when nothing is loaded.
 */
@Composable
fun FullLyricsView() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val store = remember(context) { Lyrify.settings(context) }
    val library = store.settings.collectAsStateWithLifecycle(initialValue = null).value?.library ?: LibrarySettings()
    val ui = connection.ui.collectAsStateWithLifecycle().value
    LaunchedEffect(ui == null) { if (ui == null) nav.close(FullScreen.FullLyrics) }
    if (ui == null) return
    val close = { nav.close(FullScreen.FullLyrics) }
    val path = ui.item.song.path
    val lyrics = rememberSongLyrics(path, LocalLyrics.current.ledger.entries[path])
    var sheet by remember { mutableStateOf<PlayerRoute?>(null) }
    val now = rememberClock(ui.playing)
    val type = MaterialTheme.typography

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LyricsBackdrop(path)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp)) {
                ChromeIconButton(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.lyrics_close), close)
                SongCover(SongArt(path), size = 40.dp, shape = RoundedCornerShape(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.song?.let { displayTitle(it, library) } ?: path.substringAfterLast('/'), style = type.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.song?.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = type.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                AaButton { sheet = PlayerRoute.LYRICS_TEXT }
                syncAction(lyrics)?.let { action -> SyncMenu(action) { nav.show(FullScreen.SyncEditor(ui.item.song)) } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 120.dp)
                when (lyrics) {
                    is PanelLyrics.Synced -> SyncedLyrics(lyrics.lines, ui.positionAt(now), enabled = true, FullLine, FullFrame) { line ->
                        val tap = lineTap(line, ui.playing)
                        connection.seekTo(tap.seekMs)
                        if (tap.play) connection.play()
                    }
                    is PanelLyrics.Plain -> StillLyrics(lyrics.lines, FullStill, padding)
                    PanelLyrics.Finding -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                        BrandGlyph(width = 28.dp, sweeping = true)
                        Text(stringResource(R.string.player_finding_lyrics), style = LyricsStateTitle)
                    }
                    PanelLyrics.None -> EmptyState(
                        EmptyArt.Glyph,
                        stringResource(R.string.player_no_lyrics),
                        Modifier.align(Alignment.Center),
                        action = stringResource(R.string.player_search_lyrics).takeIf { ui.song != null },
                    ) { sheet = PlayerRoute.SEARCH_LYRICS }
                    null -> Unit
                }
            }
            ToastSlot(Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
            GlassSurface(null, RoundedCornerShape(28.dp), Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    Column(Modifier.weight(1f)) { ProgressBar(ui, onSeek = connection::seekTo) }
                    PlayPauseButton(ui.playing, connection::playPause)
                }
            }
        }
    }
    sheet?.let { PlayerLyricsSheet(it, ui) { sheet = null } }
}

/** The cover, blurred and faded, behind a full-screen view. */
@Composable
private fun LyricsBackdrop(path: String) {
    val surface = MaterialTheme.colorScheme.background
    val context = LocalContext.current
    // A small cover: blurred this much, a full-size one would only cost decoding time.
    val request = remember(path) { ImageRequest.Builder(context).data(SongArt(path)).size(BACKDROP_PX).build() }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(52.dp).alpha(0.5f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(surface.copy(alpha = 0.6f), surface.copy(alpha = 0.85f), surface))))
    }
}

/** "Aa", in the surface's ink whatever is around it: the lyrics text sheet. */
@Composable
fun AaButton(enabled: Boolean = true, onClick: () -> Unit) {
    val label = stringResource(R.string.player_lyrics_text)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) { Text("Aa", style = AaLetters, color = MaterialTheme.colorScheme.onSurface) }
}

/** "⋯" with the one editor these lyrics have: Edit timing or Make synced. */
@Composable
private fun SyncMenu(action: SyncAction, onOpen: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lyrics_more), { open = true })
        LyrifyMenu(expanded = open, onDismiss = { open = false }) {
            MenuRow(stringResource(if (action == SyncAction.EDIT) R.string.lyrics_edit_timing else R.string.lyrics_make_synced), R.drawable.ic_edit_note, {
                open = false
                onOpen()
            })
        }
    }
}

/** Play/Pause as a round button. */
@Composable
fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, size: Dp = 52.dp) {
    val brand = LocalBrandColors.current
    val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(brand.highlight, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        PlayPauseIcon(playing, tint = brand.onHighlight, modifier = Modifier.size(size * 0.5f))
    }
}

// The panel's routes

/** The lyrics panel's routes ([PlayerRoute.SEARCH_LYRICS], [PlayerRoute.LYRICS_TEXT]): Search myself for the song, or "Aa". */
@Composable
fun PlayerLyricsSheet(route: PlayerRoute, ui: PlayerUi, onDismiss: () -> Unit) {
    val song = ui.song
    when {
        route == PlayerRoute.LYRICS_TEXT -> LyricsTextSheet(onDismiss)
        route == PlayerRoute.SEARCH_LYRICS && song != null -> LyricsSearchSheet(song, onDismiss)
        else -> LaunchedEffect(route) { onDismiss() }
    }
}

/**
 * "Aa": the size (S, M, L, XL), the weight (Regular, Bold) and the alignment (Left, Centre) of the lyrics, each kept as
 * soon as it is picked, with a line drawn as they make it.
 */
@Composable
fun LyricsTextSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { Lyrify.settings(context) }
    val settings = store.settings.collectAsStateWithLifecycle(initialValue = null).value?.lyrics ?: LyricsSettings()
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    fun set(change: (LyricsSettings) -> LyricsSettings) {
        scope.launch { if (!store.update { it.copy(lyrics = change(it.lyrics)) }) toasts.say(resources, R.string.lyrics_not_saved) }
    }
    val look = lyricsLook(settings)
    val colors = MaterialTheme.colorScheme
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = { LyrifyButton(stringResource(R.string.lyrics_done), onDismiss, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY) },
    ) {
        Text(stringResource(R.string.lyrics_text), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.lyrics_text_sample),
            style = look.style(MaterialTheme.typography.headlineSmall, playing = true),
            textAlign = look.align,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainerHigh).padding(16.dp),
        )
        SheetLabel(R.string.lyrics_size)
        LyrifySegmented(
            listOf(R.string.lyrics_size_s, R.string.lyrics_size_m, R.string.lyrics_size_l, R.string.lyrics_size_xl).map { stringResource(it) },
            settings.textSize.ordinal,
            { i -> set { it.copy(textSize = LyricsSize.entries[i]) } },
        )
        SheetLabel(R.string.lyrics_weight)
        LyrifySegmented(listOf(stringResource(R.string.lyrics_regular), stringResource(R.string.lyrics_bold)), settings.textWeight.ordinal, { i -> set { it.copy(textWeight = LyricsWeight.entries[i]) } })
        SheetLabel(R.string.lyrics_align)
        LyrifySegmented(listOf(stringResource(R.string.lyrics_left), stringResource(R.string.lyrics_centre)), settings.textAlign.ordinal, { i -> set { it.copy(textAlign = LyricsAlign.entries[i]) } })
    }
}

@Composable
private fun SheetLabel(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

/**
 * "Search myself" for the song on the player, which has no lyrics: LRCLIB's results of the song's length, and "Use"
 * writes the lyrics picked as the check list does (the app's message says how, also once the sheet is gone).
 */
@Composable
private fun LyricsSearchSheet(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val timing = LocalLyrics.current.timing
    val answers = Lyrify.checkAnswers
    val busy by answers.busy.collectAsStateWithLifecycle()
    val track = remember(song) { LocalTrack(song.path, song.title, song.artist, song.album, song.durationMs) }
    val actions = remember(context) {
        CheckActions(
            choose = { path, id -> Lyrify.choose(context, path, id) },
            decline = { Lyrify.decline(context, it) },
            preview = { Lyrify.preview(context, it) },
            search = { found, text -> Lyrify.search(context, found, text) },
        )
    }
    SearchMyselfSheet(track, busy = track.path in busy, actions = actions, onDismiss = onDismiss) { id ->
        onDismiss()
        answers.answer(track.path, timing) { withContext(Dispatchers.IO) { Lyrify.pipeline(context.applicationContext).choose(track.path, id, track) } }
    }
}
