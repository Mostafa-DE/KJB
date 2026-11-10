package app.lyrify.ui

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.BuildConfig
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.albumKey
import app.lyrify.library.displayTitle
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.player.queueLabel
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlayerUiSettings
import app.lyrify.settings.Settings
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// The player (P1): the flip area (the cover, the lyrics panel on its back), the title and links, the chips, the
// progress bar, the main row and the secondary row near the thumb. P2 and P3 fill the routes declared in PlayerRoute
// and the chips slot; their sheets live in their own files.

// Rules, as pure functions

/** How long a seek button is held before the jump repeats, and how often it repeats then. */
const val HOLD_DELAY_MS: Long = 420
const val HOLD_EVERY_MS: Long = 260

/** The seek button's icon for a jump of [seconds] back or forward ("Skip amounts"); another amount shows the default's. */
@DrawableRes
fun seekIcon(back: Boolean, seconds: Int): Int = when (seconds) {
    5 -> if (back) R.drawable.ic_replay_5 else R.drawable.ic_forward_5
    10 -> if (back) R.drawable.ic_replay_10 else R.drawable.ic_forward_10
    15 -> if (back) R.drawable.ic_replay_15 else R.drawable.ic_forward_15
    30 -> if (back) R.drawable.ic_replay_30 else R.drawable.ic_forward_30
    else -> if (back) R.drawable.ic_replay_30 else R.drawable.ic_forward_10
}

/** How many jumps a seek button held for [heldMs] has made: one on the press, then one every [HOLD_EVERY_MS] after [HOLD_DELAY_MS]. */
fun jumpsWhileHeld(heldMs: Long): Int = if (heldMs < HOLD_DELAY_MS) 1 else 2 + ((heldMs - HOLD_DELAY_MS) / HOLD_EVERY_MS).toInt()

/** "Tap the cover for lyrics": once, when the player opens on the cover. */
fun flipHintOnOpen(ui: PlayerUiSettings): Boolean = !ui.flipHintShown && !ui.showLyrics

/** The links row and the progress bar each take a 48 dp touch area; this much clear space separates them, chips or not. */
private val LINKS_GAP = 16.dp
private val CHIPS_GAP = 16.dp
internal val TOUCH = 48.dp

fun linksToBarClearance(chips: Boolean): Dp = if (chips) minOf(LINKS_GAP, CHIPS_GAP) else LINKS_GAP

/** What the lyrics panel shows. */
sealed interface PanelLyrics {
    /** Timed lines with text, in time order. */
    data class Synced(val lines: List<LrcLine>) : PanelLyrics

    /** Lyrics without times, line by line. */
    data class Plain(val lines: List<String>) : PanelLyrics

    /** No lyrics yet, and a lookup is under way or due. */
    data object Finding : PanelLyrics

    /** "No lyrics yet", with Search. */
    data object None : PanelLyrics
}

/** The panel for a song whose `.lrc` holds [lrc] (null without one), and whose ledger entry has [status] (null without one). */
fun panelLyrics(lrc: String?, status: LyricsStatus?): PanelLyrics {
    if (lrc != null) {
        val timed = LrcFormat.parseLrclib(lrc).filter { it.text.isNotBlank() }
        if (timed.isNotEmpty()) return PanelLyrics.Synced(timed)
        val plain = lrc.removePrefix("\uFEFF").lines().map(String::trim).filter { it.isNotEmpty() && !HEADER_TAG.matches(it) }
        if (plain.isNotEmpty()) return PanelLyrics.Plain(plain)
        return PanelLyrics.None
    }
    return if (status == null || status == LyricsStatus.WRITING) PanelLyrics.Finding else PanelLyrics.None
}

private val HEADER_TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")

/** The line playing at [positionMs]: the last one that has started; -1 before the first. */
fun currentLine(lines: List<LrcLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }

/** A time as the player shows it: "1:42", or "1:02:03" past an hour. */
fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60) else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** KJB's own song for the Now bar: as every surface names it ([displayTitle]), its cover, and how far it is at [now]. */
fun ownNow(ui: PlayerUi?, settings: LibrarySettings, now: Long): OwnNow? {
    if (ui == null) return null
    val path = ui.item.song.path
    return OwnNow(
        title = ui.song?.let { displayTitle(it, settings) } ?: path.substringAfterLast('/'),
        artist = ui.song?.artist,
        cover = SongArt(path),
        playing = ui.playing,
        progress = ringShare(ui.positionAt(now), ui.durationMs),
    )
}

/** The routes later milestones fill: P2 (the menu's library and playback extras, the sleep timer) and P3 (lyrics). */
enum class PlayerRoute(val milestone: String) {
    ADD_TO("P2"),
    SPEED("P2"),
    REPEAT_PART("P2"),
    BOOKMARK("P2"),
    PLAY_ON("P2"),
    MAKE_TONE("P2"),
    SONG_INFO("P2"),
    SLEEP("P2"),
    SEARCH_LYRICS("P3"),
    LYRICS_TEXT("P3"),
}

// The Now bar's own player

/** KJB's own player for the Now bar; also keeps the screens connected to the player while the app is in view. */
@Composable
fun rememberOwnNow(): OwnNowSource {
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    LifecycleStartEffect(connection) {
        connection.attach()
        onStopOrDispose { connection.detach() }
    }
    val store = remember(context) { Lyrify.settings(context) }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val ui by connection.ui.collectAsStateWithLifecycle()
    val now = rememberClock(ui?.playing == true)
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val shown by rememberUpdatedState(settings.playerUi.recentQueueHintShown)
    // "Your last queue is in Recent", the first time a new queue pushes one there.
    LaunchedEffect(connection) {
        connection.toRecent.collect {
            if (shown) return@collect
            store.update { it.copy(playerUi = it.playerUi.copy(recentQueueHintShown = true)) }
            toasts.show(Toast(resources.getString(R.string.player_recent_hint), R.drawable.ic_history))
        }
    }
    return OwnPlayer(ownNow(ui, settings.library, now), connection)
}

private class OwnPlayer(override val now: OwnNow?, private val connection: PlayerConnection) : OwnNowSource {
    override fun playPause() = connection.playPause()
}

/** Elapsed realtime, ticking while [running], for positions that move on. */
@Composable
internal fun rememberClock(running: Boolean, everyMs: Long = 250): Long {
    val now by produceState(SystemClock.elapsedRealtime(), running) {
        value = SystemClock.elapsedRealtime()
        while (running) {
            delay(everyMs)
            value = SystemClock.elapsedRealtime()
        }
    }
    return now
}

// The player

/** The player, over everything; it closes itself when nothing is loaded, once the queues are read. */
@Composable
fun PlayerView() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val store = remember(context) { Lyrify.settings(context) }
    val settings = store.settings.collectAsStateWithLifecycle(initialValue = null).value
    val ui = connection.ui.collectAsStateWithLifecycle().value
    // On a cold start the queues are read after the player opens: it waits for them, and closes only if nothing loads.
    LaunchedEffect(connection) {
        connection.awaitNothingLoaded()
        nav.close(FullScreen.Player)
    }
    if (ui == null || settings == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val close = { nav.close(FullScreen.Player) }
    var lyricsRoute by remember { mutableStateOf<PlayerRoute?>(null) }
    val route: (PlayerRoute) -> Unit = { route ->
        when (route) {
            // The lyrics panel's Search and "Aa" (LyricsViews.kt).
            PlayerRoute.SEARCH_LYRICS, PlayerRoute.LYRICS_TEXT -> lyricsRoute = route
            // Declared now, filled by its milestone; a debug build says which.
            else -> if (BuildConfig.DEBUG) scope.launch { toasts.show(Toast("Coming in ${route.milestone}")) }
        }
    }
    var queueSheet by remember { mutableStateOf(false) }

    // "Tap the cover for lyrics", once, when the player first opens on the cover.
    LaunchedEffect(Unit) {
        val first = store.settings.first().playerUi
        if (flipHintOnOpen(first)) {
            store.update { it.copy(playerUi = it.playerUi.copy(flipHintShown = true)) }
            toasts.show(Toast(resources.getString(R.string.player_flip_hint), R.drawable.ic_lyrics))
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(ui.item.song.path)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            PlayerTopBar(ui, onClose = close, onRoute = route, onSwipeDown = close)
            Column(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 4.dp)) {
                FlipArea(
                    ui = ui,
                    showLyrics = settings.playerUi.showLyrics,
                    onFlip = { lyrics -> scope.launch { store.update { it.copy(playerUi = it.playerUi.copy(showLyrics = lyrics)) } } },
                    onSeek = connection::seekTo,
                    onPlay = connection::play,
                    onNext = connection::next,
                    onPrevious = connection::previous,
                    onClose = close,
                    onRoute = route,
                    modifier = Modifier.weight(1f),
                )
                TitleAndLinks(ui, settings.library)
                PlayerChips(ui)
                Spacer(Modifier.height(LINKS_GAP))
                ProgressBar(ui, onSeek = connection::seekTo)
                MainRow(ui, settings, connection)
                SecondaryRow(ui, connection, onQueue = { queueSheet = true }, onRoute = route)
            }
            ToastSlot(Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
        }
    }
    if (queueSheet) QueueSheet(onDismiss = { queueSheet = false })
    lyricsRoute?.let { PlayerLyricsSheet(it, ui) { lyricsRoute = null } }
}

/** The cover, blurred and faded, behind the top of the player. */
@Composable
private fun Backdrop(path: String) {
    val surface = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        AsyncImage(model = SongArt(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(46.dp).alpha(0.6f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(surface.copy(alpha = 0.72f), surface.copy(alpha = 0.3f), surface))))
    }
}

/** The chevron, "From Album · Name", and "⋯" with every item of the menu. A swipe down closes the player. */
@Composable
private fun PlayerTopBar(ui: PlayerUi, onClose: () -> Unit, onRoute: (PlayerRoute) -> Unit, onSwipeDown: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    val swipe = with(LocalDensity.current) { SWIPE_DOWN.toPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp)
            .pointerInput(onSwipeDown) {
                var dragged = 0f
                detectDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged > swipe) onSwipeDown() }) { _, drag -> dragged += drag.y }
            },
    ) {
        ChromeIconButton(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.player_close), onClose)
        Text(
            stringResource(R.string.player_from, queueLabel(context, ui.queue)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box {
            ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.player_more), { menu = true })
            LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                val song = ui.song
                val pick: (() -> Unit) -> () -> Unit = { action -> { menu = false; action() } }
                MenuRow(stringResource(R.string.player_menu_add_to), R.drawable.ic_playlist_add, pick { onRoute(PlayerRoute.ADD_TO) })
                song?.let(::albumKey)?.let { key -> MenuRow(stringResource(R.string.player_menu_album), R.drawable.ic_album, pick { nav.open(Page.Album(key)) }) }
                song?.artist?.takeIf(String::isNotBlank)?.let { artist -> MenuRow(stringResource(R.string.player_menu_artist), R.drawable.ic_person, pick { nav.open(Page.Artist(artist)) }) }
                MenuRow(stringResource(R.string.player_menu_speed), R.drawable.ic_speed, pick { onRoute(PlayerRoute.SPEED) })
                MenuRow(stringResource(R.string.player_menu_ab), R.drawable.ic_repeat, pick { onRoute(PlayerRoute.REPEAT_PART) })
                MenuRow(stringResource(R.string.player_menu_bookmark), R.drawable.ic_bookmark_add, pick { onRoute(PlayerRoute.BOOKMARK) })
                MenuRow(stringResource(R.string.player_menu_play_on), R.drawable.ic_speaker, pick { onRoute(PlayerRoute.PLAY_ON) })
                // KJB's equalizer (AudioScreens.kt); the phone's own sound effects open from inside it.
                MenuRow(stringResource(R.string.player_menu_equalizer), R.drawable.ic_equalizer, pick { nav.show(FullScreen.Equalizer) })
                MenuRow(stringResource(R.string.player_menu_tone), R.drawable.ic_ring_volume, pick { onRoute(PlayerRoute.MAKE_TONE) })
                MenuRow(stringResource(R.string.player_menu_info), R.drawable.ic_info, pick { onRoute(PlayerRoute.SONG_INFO) })
            }
        }
    }
}

/**
 * The cover, and the lyrics panel on its back, in the free space: a tap flips it (a short 3D turn; a crossfade under
 * reduced motion), the "Lyrics" chip and the round cover chip are its visible twins. A sideways swipe on the cover skips
 * (twins: Previous and Next), a swipe down closes the player (twin: the chevron).
 */
@Composable
private fun FlipArea(
    ui: PlayerUi,
    showLyrics: Boolean,
    onFlip: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    onRoute: (PlayerRoute) -> Unit,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val turn = remember { Animatable(if (showLyrics) 180f else 0f) }
    LaunchedEffect(showLyrics, reduced) {
        val target = if (showLyrics) 180f else 0f
        if (reduced) turn.snapTo(target) else turn.animateTo(target, tween(FLIP_MS))
    }
    val fade = remember { Animatable(if (showLyrics) 1f else 0f) }
    LaunchedEffect(showLyrics, reduced) { fade.animateTo(if (showLyrics) 1f else 0f, tween(if (reduced) FADE_MS else 0)) }
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 8.dp, maxHeight - 8.dp, 360.dp).coerceAtLeast(72.dp)
        val lyricsShown = if (reduced) fade.value > 0.5f else turn.value > 90f
        val frontAlpha = if (reduced) 1f - fade.value else 1f
        val backAlpha = if (reduced) fade.value else 1f
        if (!lyricsShown || reduced) {
            Box(
                Modifier.size(side).graphicsLayer {
                    rotationY = if (reduced) 0f else turn.value
                    cameraDistance = CAMERA * density
                    alpha = frontAlpha
                },
            ) { CoverFace(ui, side, enabled = !lyricsShown, onFlip = { onFlip(true) }, onNext, onPrevious, onClose) }
        }
        if (lyricsShown || (reduced && fade.value > 0f)) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    rotationY = if (reduced) 0f else turn.value - 180f
                    cameraDistance = CAMERA * density
                    alpha = backAlpha
                },
            ) { LyricsPanel(ui, enabled = lyricsShown, onFlipBack = { onFlip(false) }, onSeek = onSeek, onPlay = onPlay, onRoute = onRoute) }
        }
    }
}

@Composable
private fun CoverFace(ui: PlayerUi, side: Dp, enabled: Boolean, onFlip: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onClose: () -> Unit) {
    val density = LocalDensity.current
    val skip = with(density) { SWIPE_SKIP.toPx() }
    val down = with(density) { SWIPE_DOWN.toPx() }
    var dx by remember { mutableStateOf(0f) }
    val label = stringResource(R.string.player_cover, ui.song?.title ?: ui.item.song.path.substringAfterLast('/'))
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((dx * 0.6f).roundToInt(), 0) }
                .graphicsLayer { rotationZ = dx / 40f / density.density }
                .clip(RoundedCornerShape(28.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = {
                            dx = 0f
                            dy = 0f
                        },
                        onDragEnd = {
                            when {
                                abs(dx) > abs(dy) && dx < -skip -> onNext()
                                abs(dx) > abs(dy) && dx > skip -> onPrevious()
                                dy > down && dy > abs(dx) -> onClose()
                            }
                            dx = 0f
                        },
                        onDragCancel = { dx = 0f },
                    ) { _, drag ->
                        dx += drag.x
                        dy += drag.y
                    }
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onFlip)
                .semantics { contentDescription = label },
        ) {
            SongCover(SongArt(ui.item.song.path), size = side, shape = RoundedCornerShape(28.dp))
        }
        FlipChip(Modifier.align(Alignment.BottomEnd).padding(12.dp), enabled, onFlip)
    }
}

/** The "Lyrics" chip on the cover's corner: the visible twin of a tap on the cover. */
@Composable
private fun FlipChip(modifier: Modifier, enabled: Boolean, onFlip: () -> Unit) {
    val chrome = LocalChromeColors.current
    val label = stringResource(R.string.player_show_lyrics)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(chrome.glassSolid, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onFlip)
            .semantics { contentDescription = label }
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_lyrics), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.player_lyrics_chip), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The back of the cover: synced lines that scroll with the current one lit (a tap jumps there), plain lyrics as still
 * text, or the state ("Finding lyrics", "No lyrics yet" with Search). Its header holds the status pill, "Aa" and
 * "Full screen"; the round cover chip flips back. The `.lrc` next to the song is read off the main thread, again
 * whenever its ledger entry changes.
 */
@Composable
private fun LyricsPanel(ui: PlayerUi, enabled: Boolean, onFlipBack: () -> Unit, onSeek: (Long) -> Unit, onPlay: () -> Unit, onRoute: (PlayerRoute) -> Unit) {
    val nav = LocalNavigator.current
    val path = ui.item.song.path
    val entry = LocalLyrics.current.ledger.entries[path]
    val lyrics = rememberSongLyrics(path, entry)
    val now = rememberClock(ui.playing)
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp)) {
            when {
                lyrics == PanelLyrics.Finding -> StatusPill(stringResource(R.string.player_finding_lyrics), tone = null, leading = { BrandGlyph(width = 12.dp, sweeping = true) })
                entry != null -> StatusPill(entry.status)
            }
            Spacer(Modifier.weight(1f))
            val aa = stringResource(R.string.player_lyrics_text)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button) { onRoute(PlayerRoute.LYRICS_TEXT) }.semantics { contentDescription = aa },
            ) { Text("Aa", style = MaterialTheme.typography.titleMedium) }
            LyrifyButton(stringResource(R.string.player_full_screen), { nav.show(FullScreen.FullLyrics) }, kind = ButtonKind.GHOST, icon = R.drawable.ic_open_in_full, compact = true, enabled = enabled)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val shown = lyrics) {
                is PanelLyrics.Synced -> SyncedLyrics(
                    shown.lines,
                    ui.positionAt(now),
                    enabled,
                    MaterialTheme.typography.titleLarge,
                    MaterialTheme.typography.titleMedium,
                    PaddingValues(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 160.dp),
                ) { line ->
                    val tap = lineTap(line, ui.playing)
                    onSeek(tap.seekMs)
                    if (tap.play) onPlay()
                }
                is PanelLyrics.Plain -> StillLyrics(shown.lines, MaterialTheme.typography.titleMedium, PaddingValues(start = 22.dp, end = 22.dp, bottom = 64.dp))
                PanelLyrics.Finding -> PanelState(stringResource(R.string.player_finding_lyrics), action = null)
                PanelLyrics.None -> PanelState(stringResource(R.string.player_no_lyrics)) { onRoute(PlayerRoute.SEARCH_LYRICS) }
                null -> Unit
            }
            CoverChip(ui, Modifier.align(Alignment.BottomEnd).padding(10.dp), enabled, onFlipBack)
        }
    }
}

@Composable
private fun PanelState(title: String, action: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        BrandGlyph(width = 28.dp, sweeping = action == null)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (action != null) LyrifyButton(stringResource(R.string.player_search_lyrics), action, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_search, compact = true)
    }
}

/** The round cover chip in the panel's corner: the visible twin of the flip back. */
@Composable
private fun CoverChip(ui: PlayerUi, modifier: Modifier, enabled: Boolean, onFlipBack: () -> Unit) {
    val chrome = LocalChromeColors.current
    val label = stringResource(R.string.player_show_cover, ui.song?.title ?: ui.item.song.path.substringAfterLast('/'))
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(TOUCH)
            .clip(CircleShape)
            .background(chrome.glassSolid, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onFlipBack)
            .semantics { contentDescription = label },
    ) { SongCover(SongArt(ui.item.song.path), size = 36.dp, shape = CircleShape) }
}

/** The title, then the artist and album as links on one line, 48 dp tall to touch. */
@Composable
private fun TitleAndLinks(ui: PlayerUi, settings: LibrarySettings) {
    val nav = LocalNavigator.current
    val song = ui.song
    Text(
        song?.let { displayTitle(it, settings) } ?: ui.item.song.path.substringAfterLast('/'),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(TOUCH)) {
        val artist = song?.artist?.takeIf(String::isNotBlank)
        PlayerLink(artist ?: stringResource(if (song == null) R.string.player_not_on_phone else R.string.player_no_artist), artist?.let { { nav.open(Page.Artist(it)) } }, Modifier.weight(1f, fill = false))
        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        val key = song?.let(::albumKey)
        PlayerLink(song?.album?.takeIf { key != null } ?: stringResource(R.string.player_unknown_album), key?.let { { nav.open(Page.Album(it)) } }, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun PlayerLink(text: String, onClick: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(TOUCH)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The chips row (speed, A-B) while one is active: P2 fills it; it keeps [CHIPS_GAP] above the progress bar's touch area. */
@Composable
@Suppress("UNUSED_PARAMETER") // Until P2: no chips.
private fun PlayerChips(ui: PlayerUi) = Unit

private const val FLIP_MS = 550
private const val FADE_MS = 220
private const val CAMERA = 16f

/** How far a swipe must travel to skip, and to close the player. */
private val SWIPE_SKIP = 70.dp
private val SWIPE_DOWN = 90.dp
