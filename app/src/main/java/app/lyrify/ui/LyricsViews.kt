package app.lyrify.ui

import androidx.compose.ui.text.lerp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
 * Synced lines with the "Aa" choices, the current one lit and kept in view ([lead] below the top); after the user
 * scrolls, the list is left alone for a moment. [current] and [other] are the view's styles for the line playing and the
 * rest. A tap on a line goes there ([onLine]).
 */
@Composable
fun SyncedLyrics(
    lines: List<LrcLine>,
    positionMs: Long,
    enabled: Boolean,
    current: TextStyle,
    other: TextStyle,
    padding: PaddingValues,
    lead: Dp = 120.dp,
    onLine: (LrcLine) -> Unit,
) {
    val look = rememberLyricsLook()
    val list = rememberLazyListState()
    val at = currentLine(lines, positionMs)
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged) touchedAt = SystemClock.elapsedRealtime() }
    val reduced = LocalReducedMotion.current
    val offset = with(LocalDensity.current) { lead.roundToPx() }
    LaunchedEffect(at) {
        if (at < 0 || SystemClock.elapsedRealtime() - touchedAt < LEAVE_ALONE_MS) return@LaunchedEffect
        if (reduced) list.scrollToItem(at, -offset) else list.animateScrollToItem(at, -offset)
    }
    val colors = MaterialTheme.colorScheme
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
        itemsIndexed(lines) { i, line ->
            val label = stringResource(R.string.player_line_at, clock(line.timeMs), line.text)
            // A line takes its colour and size over the prototype's 0.3 s, so the lit line moves on smoothly.
            val lit by animateFloatAsState(if (i == at) 1f else 0f, motion(tween(Motion.OPEN_MS, easing = Motion.Ease), reduced), label = "lit")
            val tone by animateColorAsState(
                when {
                    i == at -> colors.onSurface
                    i < at -> colors.outline
                    else -> colors.onSurfaceVariant
                },
                motion(tween(Motion.OPEN_MS, easing = Motion.Ease), reduced),
                label = "line colour",
            )
            Text(
                line.text,
                style = lerp(look.style(other, playing = false), look.style(current, playing = true), lit),
                textAlign = look.align,
                color = tone,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = enabled, role = Role.Button) { onLine(line) }
                    .semantics { contentDescription = label }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** Plain lyrics as still text, with the "Aa" choices. */
@Composable
fun StillLyrics(lines: List<String>, style: TextStyle, padding: PaddingValues) {
    val look = rememberLyricsLook()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
        lines.forEach { Text(it, style = look.style(style, playing = false, still = true), textAlign = look.align, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
    }
}

/** The prototype's lyrics text: full screen (.flines: 29 and 25 sp lines, 21 sp plain), the cover's back (.lplines: 22 and 19, 17 plain). */
internal val FullLineNow = display(29, 37, -0.015)
internal val FullLine = display(25, 33, -0.015)
internal val FullStill = display(21, 31, weight = 560)
internal val PanelLineNow = display(22, 29, -0.01)
internal val PanelLine = display(19, 25, -0.01)
internal val PanelStill = display(17, 26, weight = 560)

/** "Finding lyrics", "No lyrics yet" (.lpstate b), and the "Aa" button's letters. */
internal val LyricsStateTitle = display(19, 24, -0.01)
internal val AaLetters = display(16, 16, -0.02, weight = 650)

/** After the user scrolls the lyrics, the list is left alone this long. */
private const val LEAVE_ALONE_MS = 3_500L

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
                    is PanelLyrics.Synced -> SyncedLyrics(lyrics.lines, ui.positionAt(now), enabled = true, FullLineNow, FullLine, padding, lead = 160.dp) { line ->
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
    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = SongArt(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(52.dp).alpha(0.5f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(surface.copy(alpha = 0.6f), surface.copy(alpha = 0.85f), surface))))
    }
}

/** "Aa": the lyrics text sheet. */
@Composable
fun AaButton(enabled: Boolean = true, onClick: () -> Unit) {
    val label = stringResource(R.string.player_lyrics_text)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) { Text("Aa", style = AaLetters) }
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
        Icon(painterResource(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill), contentDescription = null, tint = brand.onHighlight, modifier = Modifier.size(size * 0.5f))
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
