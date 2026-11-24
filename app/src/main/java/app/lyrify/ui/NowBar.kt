package app.lyrify.ui

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.media.CurrentTrack
import app.lyrify.media.NowPlayingListener
import app.lyrify.tags.CoverVersions
import coil3.imageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * KJB's own player as the Now bar shows it: the song loaded, its cover and whether it plays; how far it is comes apart
 * ([OwnNowSource.progress]). Milestone P1 supplies it ([rememberOwnNow] in PlayerScreen.kt); the bar only shows it.
 */
@Immutable
data class OwnNow(val title: String, val artist: String?, val cover: Any?, val playing: Boolean, val path: String? = null, val album: String? = null)

/**
 * KJB's own player for the Now bar: what is loaded ([now], null when nothing is), how far it is (0 to 1, moving on while
 * it plays: read it where the ring is drawn, so a tick redraws the ring alone) and its Play/Pause.
 */
@Stable
interface OwnNowSource {
    val now: OwnNow?

    val progress: Float

    fun playPause()
}

/** Until milestone P1: KJB's own player has nothing loaded. */
object NothingLoaded : OwnNowSource {
    override val now: OwnNow? = null

    override val progress: Float = 0f

    override fun playPause() = Unit
}

/** What the Now bar shows: KJB's own song, another app's song (companion mode), or nothing. */
sealed interface NowBarShows {
    data object Hidden : NowBarShows

    data class Own(val now: OwnNow) : NowBarShows

    data class Companion(val track: CurrentTrack, val playing: Boolean) : NowBarShows
}

/**
 * The bar's one rule: KJB's own song while it plays, or while it is loaded and no other app plays; another app's song
 * when that app plays, or when KJB has nothing loaded; hidden when nothing is loaded anywhere.
 */
fun nowBarShows(own: OwnNow?, other: CurrentTrack?, otherPlaying: Boolean): NowBarShows = when {
    own != null && own.playing -> NowBarShows.Own(own)
    other != null && otherPlaying -> NowBarShows.Companion(other, playing = true)
    own != null -> NowBarShows.Own(own)
    other != null -> NowBarShows.Companion(other, playing = false)
    else -> NowBarShows.Hidden
}

/** What the orb shows for another app's song: its cover, the status of the first copy with one, its title for TalkBack. */
@Immutable
data class NowOrb(val cover: Any?, val status: LyricsStatus?, val title: String)

fun nowOrb(track: CurrentTrack, ledger: LedgerState): NowOrb = NowOrb(
    cover = nowCover(track),
    status = track.paths.firstNotNullOfOrNull { ledger.entries[it]?.status },
    title = track.title,
)

/**
 * Where another app's song is at [now] (elapsed realtime): the [positionMs] its session reported at [reportedAt], moved
 * on at [speed] while [playing], kept between the start and [durationMs]. A session that never said when ([reportedAt]
 * 0) is not moved on.
 */
fun sessionPosition(positionMs: Long, reportedAt: Long, speed: Float, playing: Boolean, now: Long, durationMs: Long?): Long {
    val moved = if (playing && reportedAt > 0) positionMs + ((now - reportedAt) * speed).toLong() else positionMs
    return moved.coerceIn(0, durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE)
}

/** How much of the ring is drawn: the position over the length, none while the length is unknown. */
fun ringShare(positionMs: Long, durationMs: Long?): Float =
    if (durationMs == null || durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

// Another app's session

/** What another app's media session says: whether it plays, its last reported position and speed, and the song's length. */
@Immutable
data class OtherPlayback(val playing: Boolean, val positionMs: Long, val reportedAt: Long, val speed: Float, val durationMs: Long?)

/**
 * The session the listener follows ([followed], NowPlaying.followed), for the Now bar's ring and Play/Pause: its play
 * state as it changes, and its controls. Null without notification access, and from the moment that session ends.
 */
@Composable
fun rememberOtherSession(followed: MediaSession.Token?): OtherSession? {
    val context = LocalContext.current
    var controller by remember(followed) { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context, followed) {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, NowPlayingListener::class.java)
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { sessions -> controller = followedSession(sessions.orEmpty(), followed) { it.sessionToken } }
        if (followed != null) {
            try {
                controller = followedSession(manager.getActiveSessions(listener), followed) { it.sessionToken }
                manager.addOnActiveSessionsChangedListener(changed, listener)
            } catch (e: SecurityException) {
                // Notification access was turned off: the bar shows the song without its controls.
                Log.d(LOG_TAG, "now bar: no session access (${e.javaClass.simpleName})")
            }
        }
        onDispose { manager.removeOnActiveSessionsChangedListener(changed) }
    }
    val session = remember(controller) { controller?.let(::OtherSession) }
    DisposableEffect(session) {
        session?.let { it.controller.registerCallback(it.callback, Handler(Looper.getMainLooper())) }
        onDispose { session?.let { it.controller.unregisterCallback(it.callback) } }
    }
    return session?.takeUnless { it.ended }
}

/** Another app's session while the Now bar shows it: its [playback] as it changes, and whether it [ended]. */
@Stable
class OtherSession internal constructor(internal val controller: MediaController) {
    var playback: OtherPlayback? by mutableStateOf(playbackOf(controller))
        private set

    var ended: Boolean by mutableStateOf(false)
        private set

    internal val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playback = playbackOf(controller)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            playback = playbackOf(controller)
        }

        override fun onSessionDestroyed() {
            ended = true
            playback = null
        }
    }

    /** Pauses the app while it [playing], else plays it, as its own button would; a session that died meanwhile does nothing. */
    fun playPause(playing: Boolean) {
        try {
            if (playing) controller.transportControls.pause() else controller.transportControls.play()
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "now bar: the other app's session did not take the command (${e.javaClass.simpleName})")
        }
    }
}

private fun playbackOf(controller: MediaController): OtherPlayback? {
    val state = controller.playbackState ?: return null
    return OtherPlayback(
        playing = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING,
        positionMs = state.position,
        reportedAt = state.lastPositionUpdateTime,
        speed = state.playbackSpeed,
        durationMs = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
    )
}

/**
 * The session among [sessions] whose [token] is the one the listener follows ([followed], NowPlaying.followed); null
 * when it is gone. Never another session of the same app: that one may be paused, or another player of the app.
 */
fun <C, T> followedSession(sessions: List<C>, followed: T?, token: (C) -> T): C? = followed?.let { sessions.firstOrNull { session -> token(session) == it } }

/**
 * The bar's Play/Pause: KJB's own player's; for another app's song only while its session lives ([otherToggle], which
 * takes whether it plays) and has told its state ([otherPlayback]), so a session that ended gives none.
 */
fun barPlayPause(bar: NowBarShows, ownPlayPause: () -> Unit, otherPlayback: OtherPlayback?, otherToggle: ((Boolean) -> Unit)?): (() -> Unit)? = when (bar) {
    NowBarShows.Hidden -> null
    is NowBarShows.Own -> ownPlayPause
    is NowBarShows.Companion -> if (otherToggle != null && otherPlayback != null) {
        { otherToggle(otherPlayback.playing) }
    } else {
        null
    }
}

// The bar

/**
 * The Now bar: a capsule above the dock with the cover in an orb ringed by the song's progress, the title, "Artist ·
 * Album" (each tag's placeholder when it is missing), or "In Samsung Music" for another app's song, and Play/Pause. A
 * tap or a swipe up opens the player (growing out of the bar), or for another app's song the Companion Now screen.
 * Without the other app's session, its Play/Pause is left out. It rises in when a song loads and fades away when
 * nothing is left, keeping its last song while it leaves. [progress]: how far KJB's own song is ([OwnNowSource.progress]),
 * read only where its ring is drawn. [friend]: before Play/Pause on KJB's own song, a friend playing it or its artist now
 * (Huddle's face, [NowBarFriend]).
 */
@Composable
fun NowBar(
    shows: NowBarShows,
    ledger: LedgerState,
    other: OtherPlayback?,
    onOpen: () -> Unit,
    onPlayPause: (() -> Unit)?,
    modifier: Modifier = Modifier,
    progress: () -> Float = { 0f },
    friend: (@Composable (OwnNow) -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    val kept = remember { Kept(shows) }
    if (shows != NowBarShows.Hidden) kept.value = shows
    AnimatedVisibility(shows != NowBarShows.Hidden, modifier, enter = barEnter(reduced), exit = barExit(reduced)) {
        // The gap above the dock goes with the bar, so nothing is left where it was.
        // The tour's Play a song points at the bar itself, above its gap; a tap there opens the player as the bar does.
        Box(Modifier.padding(bottom = 8.dp).tourTarget(TourStop.NOW_BAR, stringResource(R.string.tour_open_player), onOpen)) { BarFor(kept.value, ledger, other, onOpen, onPlayPause, progress, friend) }
    }
}

@Composable
private fun BarFor(shows: NowBarShows, ledger: LedgerState, other: OtherPlayback?, onOpen: () -> Unit, onPlayPause: (() -> Unit)?, progress: () -> Float, friend: (@Composable (OwnNow) -> Unit)?) {
    val ink = barInk(MaterialTheme.colorScheme)
    when (shows) {
        NowBarShows.Hidden -> Unit
        is NowBarShows.Own -> {
            val now = shows.now
            PrewarmCover(now.cover)
            val meta = metaLine(now.artist, now.album)
            val artist = meta.artist.text ?: stringResource(R.string.player_no_artist)
            val album = meta.album.text ?: stringResource(R.string.player_no_album)
            BarRow(
                cover = now.cover,
                status = null,
                ring = progress,
                title = now.title,
                line = { BarLine(metaText(meta, artist, album, ink.line, MaterialTheme.colorScheme.outline)) },
                playing = now.playing,
                openLabel = stringResource(R.string.nowbar_open_player, listOf(now.title, artist, album).joinToString(", ")),
                playLabel = stringResource(if (now.playing) R.string.nowbar_pause else R.string.nowbar_play),
                onOpen = onOpen,
                onPlayPause = onPlayPause,
                sharedCover = true,
                friend = friend?.let { { it(now) } },
            )
        }
        is NowBarShows.Companion -> {
            val orb = nowOrb(shows.track, ledger)
            val player = shows.track.player
            val app = player?.name ?: stringResource(R.string.nowbar_other_app)
            val clock by produceState(SystemClock.elapsedRealtime(), shows.playing) {
                while (shows.playing) {
                    value = SystemClock.elapsedRealtime()
                    delay(RING_TICK_MS)
                }
            }
            BarRow(
                cover = orb.cover,
                status = orb.status,
                // The clock read where the ring is drawn: a tick redraws the ring, not the bar.
                ring = { other?.let { ringShare(sessionPosition(it.positionMs, it.reportedAt, it.speed, it.playing, clock, it.durationMs), it.durationMs) } ?: 0f },
                title = orb.title,
                line = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rememberAppIcon(player?.packageName, 14.dp)?.let { Image(it, contentDescription = null, modifier = Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))) }
                        BarLine(AnnotatedString(stringResource(R.string.nowbar_in_app, app)))
                    }
                },
                playing = shows.playing,
                openLabel = stringResource(R.string.nowbar_open_other, app, orb.title),
                playLabel = stringResource(if (shows.playing) R.string.nowbar_pause_in else R.string.nowbar_play_in, app),
                onOpen = onOpen,
                onPlayPause = onPlayPause,
                sharedCover = false,
            )
        }
    }
}

/** "Artist · Album" with a missing tag's placeholder ([MetaName.text] null) in the quieter [placeholder] ink. */
fun metaText(meta: MetaLine, artist: String, album: String, ink: Color, placeholder: Color): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = if (meta.artist.tappable) ink else placeholder)) { append(artist) }
    withStyle(SpanStyle(color = ink)) { append(" · ") }
    withStyle(SpanStyle(color = if (meta.album.tappable) ink else placeholder)) { append(album) }
}

/**
 * The player's cover, loaded at the player's size while the bar shows its song: the cover that grows out of the bar
 * is already in memory, under the key the player's own request looks up ([CoverAt.PLAYER], Coil's own for the song), so
 * its first frame is never an empty tile. The window's width, at most the player's largest cover ([COVER_MAX]): a
 * larger one is never drawn (PW1).
 */
@Composable
private fun PrewarmCover(cover: Any?) {
    val context = LocalContext.current
    val side = minOf(LocalWindowInfo.current.containerSize.width, with(LocalDensity.current) { COVER_MAX.roundToPx() })
    val versions by CoverVersions.versions.collectAsState()
    val shown = (cover as? SongArt)?.let { it.copy(version = versions[it.path] ?: 0) } ?: cover
    LaunchedEffect(shown, side) {
        if (shown != null && side > 0) context.imageLoader.execute(ImageRequest.Builder(context).data(shown).size(side, side).build())
    }
}

@Composable
private fun BarRow(
    cover: Any?,
    status: LyricsStatus?,
    ring: () -> Float,
    title: String,
    line: @Composable () -> Unit,
    playing: Boolean,
    openLabel: String,
    playLabel: String,
    onOpen: () -> Unit,
    onPlayPause: (() -> Unit)?,
    sharedCover: Boolean,
    friend: (@Composable () -> Unit)? = null,
) {
    val swipe = with(LocalDensity.current) { SWIPE_UP.toPx() }
    val ink = barInk(MaterialTheme.colorScheme)
    val container = LocalContainer.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // The player's end of the transform is an empty stand-in of the bar's size: the bar itself is never laid out at
        // the bounds on their way, so it stays put while the player grows out of it and shrinks back into it.
        Box(Modifier.matchParentSize().then(containerBounds(container)))
        GlassSurface(null, CircleShape, Modifier.fillMaxWidth(), solid = true) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 64.dp)
                    .pointerInput(onOpen) {
                        var dragged = 0f
                        detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged < -swipe) onOpen() }) { _, dy -> dragged += dy }
                    }
                    .padding(end = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onOpen)
                        .semantics(mergeDescendants = true) { contentDescription = openLabel }
                        .heightIn(min = 64.dp)
                        .padding(start = 8.dp, end = 4.dp),
                ) {
                    Orb(cover, status, ring, if (sharedCover) containerCover(container) else Modifier)
                    Column(Modifier.weight(1f).clearAndSetSemantics {}) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = ink.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        line()
                    }
                }
                friend?.invoke()
                if (onPlayPause != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(LocalChromeColors.current.action, CircleShape)
                            .clickable(interactionSource = null, indication = PressIndication(tint = LocalChromeColors.current.onAction), role = Role.Button, onClickLabel = null, onClick = onPlayPause)
                            .semantics { contentDescription = playLabel },
                    ) {
                        PlayPauseIcon(playing, tint = LocalChromeColors.current.onAction, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

/** The bar's second line (.nowbar .s), in the secondary ink; a placeholder in it keeps its own quieter colour. */
@Composable
private fun BarLine(text: AnnotatedString) {
    val ink = barInk(MaterialTheme.colorScheme)
    Text(text, style = BarLineStyle, color = ink.line, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The old Now orb: the cover in a ring that fills with the song's progress, and its lyrics status. [shared]: the player's
 * cover lands here and grows out of here ([containerCover]), on an empty stand-in of the cover's size, so the orb's own
 * cover stays put under the player's as it goes.
 */
@Composable
private fun Orb(cover: Any?, status: LyricsStatus?, ring: () -> Float, shared: Modifier) {
    val highlight = LocalBrandColors.current.highlight
    Box(
        Modifier.size(48.dp).drawBehind {
            val stroke = RING_STROKE.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(highlight.copy(alpha = RING_TRACK_ALPHA), 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke))
            val share = ring()
            if (share > 0f) drawArc(highlight, -90f, 360f * share, useCenter = false, topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
        },
    ) {
        Box(Modifier.align(Alignment.Center).size(ORB_COVER).then(shared))
        SongCover(cover, Modifier.align(Alignment.Center), size = ORB_COVER, shape = CircleShape, at = CoverAt.BAR)
        status?.let { StatusBadge(it, Modifier.align(Alignment.BottomEnd), size = 16.dp) }
    }
}

/** The bar's second line (.nowbar .s): 13/18. */
private val BarLineStyle = body(13f, 18)

/** The cover in the orb: the size the player's cover shrinks to, round, as it closes into the bar ([CoverShape]). */
internal val ORB_COVER = 38.dp

private val RING_STROKE = 2.5.dp
private const val RING_TRACK_ALPHA = 0.2f
private const val RING_TICK_MS = 500L

/** How far up the bar a swipe must travel to open the player. */
private val SWIPE_UP = 36.dp
