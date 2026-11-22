package app.lyrify.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
import android.provider.Settings.canDrawOverlays
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaLibraryService
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.books.BOOK_SKIP_MS
import app.lyrify.books.inBook
import app.lyrify.books.knownBookPart
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.logFailures
import app.lyrify.lyrics.LrcLine
import app.lyrify.player.PlaybackService
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerGraph
import app.lyrify.player.Queue
import app.lyrify.player.ServicePart
import app.lyrify.settings.Settings
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Lyrics on the lock screen (P3): big synced lyrics and the controls over the lock screen (showWhenLocked), opened by a
// tap on the music notification (the session activity) and, with "Show when locked", when the screen turns on while
// KJB plays ([LockLyricsPart]). The switch's permission is asked in Settings ([rememberLockSwitch]). A tap on a line or a
// drag of the progress bar seeks (user feedback 2026-11-13, point 4); the lyrics scroll by finger, and a swipe on the
// bands above and below them closes ([LockPage]).

/** How the lock screen lyrics were asked for. */
enum class LockOrigin { NOTIFICATION, SCREEN_ON }

/** What opening the lock screen lyrics does. */
enum class LockEntry {
    LYRICS,

    /** Over the lock screen before the queues are read (also after a restart of the app): it shows, then decides. */
    WAIT,

    /** The phone is unlocked: the player below it (the notification's tap opened it too) shows. */
    APP,
    NOTHING,
}

/**
 * Over the lock screen, the lyrics while a song is loaded ([loaded]: null while the queues are not read yet), never when
 * nothing plays. A tap on the notification of an unlocked phone shows the player; the screen turning on never opens the
 * app.
 */
fun lockEntry(origin: LockOrigin, locked: Boolean, loaded: Boolean?): LockEntry = when {
    locked && loaded == true -> LockEntry.LYRICS
    locked && loaded == null -> LockEntry.WAIT
    !locked && origin == LockOrigin.NOTIFICATION -> LockEntry.APP
    else -> LockEntry.NOTHING
}

/**
 * "Show when locked": the lyrics open by themselves when the screen turns on over the lock screen, only while KJB plays,
 * with the switch on and "Display over other apps" allowed (Android lets no app start a screen from the background
 * otherwise).
 */
fun opensOnScreenOn(setting: Boolean, overlayAllowed: Boolean, playing: Boolean, locked: Boolean): Boolean = setting && overlayAllowed && playing && locked

/** "Show when locked" as it stays: off once "Display over other apps" was taken back in the phone's settings. */
fun showWhenLockedNow(setting: Boolean, overlayAllowed: Boolean): Boolean = setting && overlayAllowed

/** What turning "Show when locked" on or off does. */
enum class LockSwitch {
    STORE_OFF,
    STORE_ON,

    /** Ask for "Display over other apps" first, with its reason; on only once it is allowed. */
    ASK,
}

fun lockSwitch(on: Boolean, overlayAllowed: Boolean): LockSwitch = when {
    !on -> LockSwitch.STORE_OFF
    overlayAllowed -> LockSwitch.STORE_ON
    else -> LockSwitch.ASK
}

/** On the activity's intent: started by [LockLyricsPart] when the screen turned on. */
private const val EXTRA_SCREEN_ON = "app.lyrify.lock.screenOn"

/**
 * The lock screen lyrics. The music notification's tap opens KJB's player with these on top, in KJB's task (the
 * session's activities, PlaybackService): over the lock screen they show; on an unlocked phone they close at once and
 * the player shows ([lockEntry]), without starting anything. Opened by the screen turning on, they are a task of their
 * own, never in recents and removed when they close. Over the lock screen they wait for the queues to be read, then
 * close when nothing is loaded, on a swipe, back or the chevron, when the screen turns off, and once the phone is
 * unlocked; they keep the screen on while the song plays.
 */
class LockLyricsActivity : ComponentActivity() {
    private val closer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = close()
    }
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = PlayerGraph.core(this)
        val loaded = if (core.ready.value) core.book.value.current != null else null
        val origin = if (intent.getBooleanExtra(EXTRA_SCREEN_ON, false)) LockOrigin.SCREEN_ON else LockOrigin.NOTIFICATION
        when (lockEntry(origin, getSystemService(KeyguardManager::class.java).isKeyguardLocked, loaded)) {
            LockEntry.APP, LockEntry.NOTHING -> {
                close()
                return
            }
            LockEntry.LYRICS, LockEntry.WAIT -> Unit
        }
        enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        ContextCompat.registerReceiver(
            this,
            closer,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        listening = true
        val ledger = LedgerRepository(this)
        setContent { LyrifyTheme { LockLyricsScreen(ledger.ledger.collectAsStateWithLifecycle(initialValue = LedgerState()).value, onClose = ::close) } }
    }

    /** Its own task (opened by the screen turning on) goes with it; in KJB's task, the player below stays. */
    private fun close() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    override fun onDestroy() {
        if (listening) unregisterReceiver(closer)
        super.onDestroy()
    }
}

/** Light text on the cover's dark blur, whatever the phone's theme: it sits over the lock screen. */
internal val LockInk = Color(0xFF0A0A0B)
internal val LockText = Color(0xFFF7F5FF)
internal val LockMuted = LockText.copy(alpha = 0.55f)

/**
 * The cover under the lock screen, blurred at this opacity, and the ink over it at [LOCK_SCRIM_ALPHA]: dark enough that
 * the muted words and the lines read 4.5:1 over a white cover (ContrastTest).
 */
internal const val LOCK_COVER_ALPHA = 0.7f
internal const val LOCK_SCRIM_ALPHA = 0.75f

/**
 * The progress bar's empty track, on a band of [LockInk] so it reads 3:1 over any cover with the coral played part 3:1
 * on it, and its times, which read over the lightest cover too (ContrastTest).
 */
internal val LockTrack = DarkBrandColors.highlight.copy(alpha = 0.5f)
internal val LockTimes = LockText

@Composable
private fun LockLyricsScreen(ledger: LedgerState, onClose: () -> Unit) {
    val context = LocalContext.current
    val core = remember(context) { PlayerGraph.core(context) }
    val connection = remember(context) { PlayerGraph.connection(context) }
    LifecycleStartEffect(connection) {
        connection.attach()
        onStopOrDispose { connection.detach() }
    }
    // The player's own rule: once the queues are read and nothing is loaded, never while they are read (a cold start).
    LaunchedEffect(core) {
        core.awaitNothingLoaded()
        onClose()
    }
    val ui = connection.ui.collectAsStateWithLifecycle().value
    val screen = LocalView.current
    val playing = ui?.playing == true
    DisposableEffect(screen, playing) {
        screen.keepScreenOn = playing
        onDispose { screen.keepScreenOn = false }
    }
    val title = stringResource(R.string.lock_title)
    Box(Modifier.fillMaxSize().background(LockInk).semantics { paneTitle = title }) {
        if (ui == null) return@Box
        val path = ui.item.song.path
        AsyncImage(model = SongArt(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(52.dp).alpha(LOCK_COVER_ALPHA))
        Box(Modifier.fillMaxSize().background(LockInk.copy(alpha = LOCK_SCRIM_ALPHA)))
        LockPage(
            onClose = onClose,
            top = {
                ChromeIconButton(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.lock_close), onClose, glass = false, size = 48.dp, iconSize = 28.dp, tint = LockText)
                SongCover(SongArt(path), size = 44.dp, shape = RoundedCornerShape(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.named?.title ?: path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, color = LockText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.named?.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = body(13f, 18), color = LockMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            lyrics = {
                LockLines(rememberSongLyrics(path, ledger.entries[path], knownBookPart(path, ui.library)), ui.positionAt(rememberClock(playing))) { line ->
                    val tap = lineTap(line, playing)
                    connection.seekTo(tap.seekMs)
                    if (tap.play) connection.play()
                }
            },
            bottom = {
                LockControls(ui.durationMs, playing, ui::positionAt, connection::seekTo, lockSkips(ui.queue), connection)
                Text(stringResource(R.string.lock_hint), style = MaterialTheme.typography.labelMedium, color = LockMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            },
        )
    }
}

/**
 * The page of the lock screen lyrics, over the cover: the [top] band (Back to the lock screen, the song), the [lyrics]
 * between, which scroll by finger as the player's do, and the [bottom] band (the progress bar, the controls, the hint).
 * A swipe up or down of [SWIPE_CLOSE] on either band closes ([onClose]), as do the chevron, Back and unlocking; over the
 * lyrics it scrolls them. The page keeps clear of the system bars and the cutout, and at the top and the bottom of the
 * system's gesture edges too, whatever the navigation mode, so a swipe there stays the system's; the controls band
 * keeps clear of the side edges as well (Back on gesture navigation), so a back swipe never drags the bar. On a short
 * window (a small phone, split screen, a large font) the header gives up its room first, the lyrics keep at least one
 * line clear of their fades ([lockLyricsMinPx]), and the page scrolls.
 */
@Composable
internal fun LockPage(onClose: () -> Unit, top: @Composable RowScope.() -> Unit, lyrics: @Composable BoxScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    val insets = WindowInsets.safeDrawing.union(WindowInsets.systemGestures.only(WindowInsetsSides.Vertical))
    // Beyond the page's own margin: none with 3 buttons, the back gesture's edge on gesture navigation.
    val barSides = WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal).exclude(WindowInsets(left = PAGE_MARGIN, right = PAGE_MARGIN))
    val leastLyrics = lockLyricsMinPx(rememberLockLinePx(), with(LocalDensity.current) { LYRICS_FADE.roundToPx() })
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        val height = constraints.maxHeight
        Layout(
            contents = listOf(
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().swipeToClose(onClose),
                        content = top,
                    )
                },
                { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart, content = lyrics) },
                { Column(Modifier.fillMaxWidth().windowInsetsPadding(barSides).swipeToClose(onClose), content = bottom) },
            ),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = PAGE_MARGIN),
        ) { (header, words, controls), constraints ->
            val width = constraints.maxWidth
            val band = controls.single().measure(Constraints.fixedWidth(width))
            val least = lockHeaderMin(height, HEADER_ROOM.roundToPx(), band.height, leastLyrics)
            val head = header.single().measure(Constraints(minWidth = width, maxWidth = width, minHeight = least))
            val lyricsHeight = lockLyricsPx(height, head.height, band.height, leastLyrics)
            val lines = words.single().measure(Constraints.fixed(width, lyricsHeight))
            layout(width, head.height + lyricsHeight + band.height) {
                head.place(0, 0)
                lines.place(0, head.height)
                band.place(0, head.height + lyricsHeight)
            }
        }
    }
}

/**
 * A swipe up or down here closes the lock screen lyrics as it lifts, once it went [SWIPE_CLOSE] from where the finger
 * touched ([swipeCloses]); a sideways drag (the progress bar's) is not one.
 */
private fun Modifier.swipeToClose(onClose: () -> Unit): Modifier = pointerInput(onClose) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        var last = start.position
        val lifted = verticalDrag(start.id) { change ->
            last = change.position
            change.consume()
        }
        if (lifted && swipeCloses(last.y - down.position.y, SWIPE_CLOSE.toPx())) onClose()
    }
}

/** The height of one line of the lock screen's lyrics in the "Aa" size, with its own room above and below, px. */
@Composable
private fun rememberLockLinePx(): Int {
    val style = rememberLyricsLook().style(LockLine, playing = false)
    return with(LocalDensity.current) { (style.lineHeight.toPx() + 2 * LINE_PADDING_V.toPx()).roundToInt() }
}

/**
 * The lyrics as on the player: synced lines following the song (one size for every line, the line playing lit by its
 * colour, a smooth scroll to the anchor), which scroll by finger, the follow waiting while a finger is on them and a
 * while after, and a tap on one going there ([onLine], [lineTap]), a scroll never; plain lyrics still, scrolling whole;
 * or "No lyrics yet"; for a book's part, "No lyrics for books".
 */
@Composable
internal fun LockLines(lyrics: PanelLyrics?, positionMs: Long, onLine: (LrcLine) -> Unit) {
    val look = rememberLyricsLook()
    when (lyrics) {
        is PanelLyrics.Synced -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val frame = lockFrame(constraints.maxHeight, rememberLockLinePx(), with(LocalDensity.current) { LYRICS_FADE.roundToPx() })
            SyncedLyrics(lyrics.lines, positionMs, enabled = true, LockLine, frame, LockTones, onLine)
        }
        is PanelLyrics.Plain -> Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            lyrics.lines.forEach {
                Text(it, style = look.style(LockStill, playing = false, still = true), textAlign = look.align, color = LockText, modifier = Modifier.fillMaxWidth())
            }
        }
        PanelLyrics.Finding, PanelLyrics.None ->
            Text(stringResource(R.string.player_no_lyrics), style = MaterialTheme.typography.titleLarge, color = LockMuted, textAlign = look.align, modifier = Modifier.fillMaxWidth())
        PanelLyrics.Book ->
            Text(stringResource(R.string.book_no_lyrics), style = MaterialTheme.typography.titleLarge, color = LockMuted, textAlign = look.align, modifier = Modifier.fillMaxWidth())
        null -> Unit
    }
}

/** A button beside Play/Pause on the lock screen: its icon, and in a book its jump ([jumpMs], back when negative); null for Previous and Next. */
internal data class LockSkip(@DrawableRes val icon: Int, val jumpMs: Long? = null)

/**
 * The lock screen's buttons before and after Play/Pause for [queue]: Previous and Next; in a book ([inBook]) back and
 * forward 30 s ([BOOK_SKIP_MS]) with the player's 30 s icons, as the notification, the car and the widget have them.
 */
internal fun lockSkips(queue: Queue?): Pair<LockSkip, LockSkip> = if (inBook(queue)) {
    LockSkip(seekIcon(back = true, BOOK_SKIP_S), -BOOK_SKIP_MS) to LockSkip(seekIcon(back = false, BOOK_SKIP_S), BOOK_SKIP_MS)
} else {
    LockSkip(R.drawable.ic_skip_previous_fill) to LockSkip(R.drawable.ic_skip_next_fill)
}

/** The progress bar ([LockSeekBar]), then [skips] around Play/Pause: Previous and Next, or a book's jumps ([lockSkips]). */
@Composable
private fun LockControls(
    durationMs: Long,
    playing: Boolean,
    positionAt: (now: Long) -> Long,
    onSeek: (Long) -> Unit,
    skips: Pair<LockSkip, LockSkip>,
    connection: PlayerConnection,
) {
    LockSeekBar(durationMs, playing, positionAt, onSeek)
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LockSkipButton(skips.first, stringResource(R.string.player_previous), connection::previous, connection::seekBy)
        PlayPauseButton(playing, connection::playPause, size = 72.dp)
        LockSkipButton(skips.second, stringResource(R.string.player_next), connection::next, connection::seekBy)
    }
}

/** [skip]: [onTrack] under [trackLabel] (Previous, Next), or its jump ([onJump]) named as the player's seek buttons are. */
@Composable
private fun LockSkipButton(skip: LockSkip, trackLabel: String, onTrack: () -> Unit, onJump: (Long) -> Unit) {
    val jump = skip.jumpMs
    if (jump == null) {
        ChromeIconButton(skip.icon, trackLabel, onTrack, glass = false, size = 56.dp, iconSize = 32.dp, tint = LockText)
        return
    }
    val seconds = (abs(jump) / 1_000).toInt()
    val label = pluralStringResource(if (jump < 0) R.plurals.player_back_seconds else R.plurals.player_forward_seconds, seconds, seconds)
    ChromeIconButton(skip.icon, label, { onJump(jump) }, glass = false, size = 56.dp, iconSize = 32.dp, tint = LockText)
}

/**
 * The progress bar as the player has it (a 6 dp track in a 48 dp touch area, the knob, the time played and the song's
 * length under it), in the lock screen's own dark colours whatever the phone's theme, the track on a band of ink as the
 * knob has its ring. Only a deliberate sideways drag seeks ([lockSeekStarts]), to where the finger that started it lifts:
 * a tap or a touch in a pocket never does, nor a second finger, and a swipe up or down from it still closes the screen.
 * While the song plays it moves on about a pixel at a time ([lockBarTickMs]). TalkBack finds a slider that reads the
 * time and seeks, and hears a drag once, where it lands.
 */
@Composable
internal fun LockSeekBar(durationMs: Long, playing: Boolean, positionAt: (now: Long) -> Long, onSeek: (Long) -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // The time TalkBack hears while a finger drags (the time before it), then where the drag landed, for a moment while
    // the player takes the seek: a drag is announced once.
    var held by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(held, dragging != null) {
        if (held != null && dragging == null) {
            delay(LANDING_MS)
            held = null
        }
    }
    val tick = lockBarTickMs(durationMs, width)
    val position = positionAt(key(tick) { rememberClock(playing, everyMs = tick) })
    val shown = dragging?.let { seekMsAt(it, durationMs) } ?: position
    val share = dragging ?: ringShare(position, durationMs)
    // TalkBack hears the bar move on by the second, as the times do, not at every step.
    val announced = (held ?: position).let { it - it % 1_000 }
    val label = stringResource(R.string.player_position)
    val state = stringResource(R.string.lock_position, clock(announced), clock(durationMs))
    val seek by rememberUpdatedState(onSeek)
    val now by rememberUpdatedState(position)
    val knob by animateFloatAsState(if (dragging != null) KNOB_DRAG_SCALE else 1f, motion(tween(Motion.QUICK_MS), LocalReducedMotion.current), label = "knob")
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(TOUCH)
            .onSizeChanged { width = it.width }
            .semantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(ringShare(announced, durationMs), 0f..1f)
                setProgress { value -> seekMsAt(value, durationMs)?.also { seek(it) } != null }
            }
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val start = awaitSeekStart(awaitFirstDown().id, viewConfiguration.touchSlop) ?: return@awaitEachGesture
                    var to: Long? = null
                    try {
                        held = now
                        dragging = barShare(start.position.x, size.width.toFloat())
                        val lifted = awaitSeekLift(start.id) { dragging = barShare(it.position.x, size.width.toFloat()) }
                        to = dragging?.takeIf { lifted }?.let { seekMsAt(it, durationMs) }
                        to?.let(seek)
                    } finally {
                        held = to
                        dragging = null
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).background(LockInk, CircleShape))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(LockTrack))
        Box(Modifier.fillMaxWidth(share).height(6.dp).clip(CircleShape).background(DarkBrandColors.highlight))
        Box(
            Modifier
                .offset { IntOffset((width * share - 11.dp.toPx()).roundToInt(), 0) }
                .size(22.dp)
                .graphicsLayer {
                    scaleX = knob
                    scaleY = knob
                }
                .background(LockInk, CircleShape)
                .padding(3.dp)
                .background(DarkBrandColors.highlight, CircleShape),
        )
    }
    // The slider says the time to TalkBack.
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
        val style = MaterialTheme.typography.bodySmall.tabular()
        Text(clock(shown), style = style, color = LockTimes)
        Box(Modifier.weight(1f))
        Text(clock(durationMs), style = style, color = LockTimes)
    }
}

/**
 * Waits for the finger [id] to start a seek ([lockSeekStarts]) and takes that move; null once it lifts first, or once
 * something around the bar (the swipe that closes the screen) takes the move.
 */
private suspend fun AwaitPointerEventScope.awaitSeekStart(id: PointerId, slop: Float): PointerInputChange? {
    var moved = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return null
        if (!change.pressed || change.isConsumed) return null
        moved += change.positionChange()
        if (lockSeekStarts(moved.x, moved.y, slop)) return change.also { it.consume() }
        awaitPointerEvent(PointerEventPass.Final)
        if (change.isConsumed) return null
    }
}

/**
 * Follows the finger [id] that started a seek, [onMove] for each of its moves, until it lifts: true then, false once
 * something else took its move or the gesture was cancelled. Only that finger counts: another one on the bar is taken
 * and moves nothing, and never carries the seek on once the first lifts.
 */
private suspend fun AwaitPointerEventScope.awaitSeekLift(id: PointerId, onMove: (PointerInputChange) -> Unit): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return false
        if (change.isConsumed) return false
        event.changes.forEach { it.consume() }
        if (!change.pressed) return true
        onMove(change)
    }
}

/** Where a finger at [x] lies along a bar [width] wide: its share, from 0 at the start to 1 at the end. */
fun barShare(x: Float, width: Float): Float = if (width > 0f) (x / width).coerceIn(0f, 1f) else 0f

/** Where [share] of a song [durationMs] long is, to the ms and within the song; null without a length to seek in. */
fun seekMsAt(share: Float, durationMs: Long): Long? = if (durationMs > 0) (share.coerceIn(0f, 1f).toDouble() * durationMs).roundToLong() else null

/**
 * Whether a finger that moved [dx] and [dy] (px) since it touched the lock screen's bar starts a seek: only a deliberate
 * sideways drag, past the touch [slop] and at least twice as far sideways as up or down. A touch that barely moves, as
 * one in a pocket, never seeks, and one up or down is the swipe that closes the screen.
 */
fun lockSeekStarts(dx: Float, dy: Float, slop: Float): Boolean = abs(dx) > slop && abs(dx) >= SIDEWAYS * abs(dy)

/**
 * How often the lock screen's bar moves on while the song plays: about a pixel of a bar [widthPx] wide at a time, so it
 * glides, never more often than every frame, and at least four times a second for the time shown.
 */
fun lockBarTickMs(durationMs: Long, widthPx: Int): Long =
    if (durationMs > 0 && widthPx > 0) (durationMs / widthPx).coerceIn(FRAME_MS, TIME_TICK_MS) else TIME_TICK_MS

private val SWIPE_CLOSE = 80.dp

/** The lock screen's lines: the one playing in the coral, those sung muted, those to come dimmer than the text. */
internal val LockTones = LineTones(now = DarkBrandColors.highlight, past = LockMuted, next = LockText.copy(alpha = NEXT_INK))

/** The page's side margin, and the header's room while the window has it (the prototype's .ll-top). */
private val PAGE_MARGIN = 16.dp
private val HEADER_ROOM = 64.dp

/** A swipe up or down that went [dy] px from where the finger touched closes once it reaches [closePx]. */
fun swipeCloses(dy: Float, closePx: Float): Boolean = abs(dy) >= closePx

/** The least height of the lock screen's lyrics: one line ([linePx]) clear of the fades ([fadePx]) above and below it. */
fun lockLyricsMinPx(linePx: Int, fadePx: Int): Int = linePx + 2 * fadePx

/**
 * The least height of the page's header in a window [heightPx] tall: its [roomPx] while the lyrics keep their least
 * ([leastLyricsPx]) between it and the controls band ([bottomPx]); none on a shorter window, so it shrinks to its own
 * height before the lyrics shrink.
 */
fun lockHeaderMin(heightPx: Int, roomPx: Int, bottomPx: Int, leastLyricsPx: Int): Int =
    if (heightPx - roomPx - bottomPx >= leastLyricsPx) roomPx else 0

/** The lyrics' height: what the header ([topPx]) and the controls band ([bottomPx]) leave, never under [leastLyricsPx]. */
fun lockLyricsPx(heightPx: Int, topPx: Int, bottomPx: Int, leastLyricsPx: Int): Int = max(leastLyricsPx, heightPx - topPx - bottomPx)

/**
 * [LockFrame] fitted to lyrics [viewportPx] tall whose lines are [linePx] tall and fade [fadePx] at the edges: as it is
 * while its lines rest clear of the fades; on a shorter window the line playing moves to the middle, and the room above
 * the first line and below the last shrinks to let them rest there too.
 */
fun lockFrame(viewportPx: Int, linePx: Int, fadePx: Int): LyricsFrame {
    val height = viewportPx.toFloat()
    val half = linePx / 2f
    val anchor = LockFrame.anchor * height
    val fits = anchor - half >= fadePx && anchor + half <= height - fadePx && LockFrame.top * height >= fadePx && LockFrame.bottom * height >= fadePx
    if (fits) return LockFrame
    val edge = ((height / 2 - half) / height).coerceAtLeast(0f)
    return LockFrame.copy(top = edge, anchor = 0.5f, bottom = edge)
}

/** A seek starts only on a move at least this many times as far sideways as up or down. */
private const val SIDEWAYS = 2f
private const val FRAME_MS = 16L
private const val TIME_TICK_MS = 250L

/** How long TalkBack's time stays where a drag landed while the player takes the seek. */
private const val LANDING_MS = 1_000L

/**
 * "Show when locked" in the playback service: while the session runs, the screen turning on over the lock screen opens
 * the lyrics there when [opensOnScreenOn] says so. Started and stopped with the session.
 */
class LockLyricsPart : ServicePart {
    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    private var service: PlaybackService? = null

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures).also { this.scope = it }
        val settings: StateFlow<Settings?> = Lyrify.settings(service).settings.stateIn(scope, SharingStarted.Eagerly, null)
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        val screenOn = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = settings.value?.lyrics?.showWhenLocked == true
                val overlay = canDrawOverlays(service)
                if (on && !showWhenLockedNow(on, overlay)) {
                    // "Display over other apps" was taken back: the switch goes off, as it would in Settings.
                    scope.launch { Lyrify.settings(service).update { it.copy(lyrics = it.lyrics.copy(showWhenLocked = false)) } }
                    return
                }
                if (!opensOnScreenOn(on, overlay, session.player.isPlaying, keyguard.isKeyguardLocked)) return
                try {
                    // A task of its own, so KJB's task is not brought up behind the lock screen; removed when it closes.
                    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    service.startActivity(Intent(service, LockLyricsActivity::class.java).addFlags(flags).putExtra(EXTRA_SCREEN_ON, true))
                } catch (e: RuntimeException) {
                    Log.w(LOG_TAG, "lock lyrics: not opened (${e.javaClass.simpleName})")
                }
            }
        }
        // The screen turning on reaches only receivers registered at run time.
        ContextCompat.registerReceiver(service, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = screenOn
        this.service = service
    }

    override fun stop() {
        receiver?.let { service?.unregisterReceiver(it) }
        receiver = null
        service = null
        scope?.cancel()
        scope = null
    }
}

/**
 * Settings > Lyrics > "Show when locked" ([on] as stored): switched on, it first asks for "Display over other apps" when
 * your phone has not allowed it ([lockSwitch]), with its one-line reason, then opens the phone's page for it; back from
 * there, it is on only if allowed, and says so. Should the permission be taken back later, it goes off
 * ([showWhenLockedNow]). Returns what the switch does; its dialog shows where this is called.
 */
@Composable
fun rememberLockSwitch(on: Boolean, onChange: ((Settings) -> Settings) -> Unit): (Boolean) -> Unit {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var asking by rememberSaveable { mutableStateOf(false) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    val stored by rememberUpdatedState(on)
    fun store(value: Boolean, message: Int?) {
        onChange { it.copy(lyrics = it.lyrics.copy(showWhenLocked = value)) }
        if (message != null) scope.launch { toasts.say(resources, message) }
    }
    // Back from the phone's page, or from its settings with the permission taken back.
    LifecycleResumeEffect(Unit) {
        val allowed = canDrawOverlays(context)
        if (waiting) {
            waiting = false
            store(allowed, if (allowed) R.string.lock_on else R.string.lock_denied)
        } else if (stored && !showWhenLockedNow(stored, allowed)) {
            store(false, null)
        }
        onPauseOrDispose { }
    }
    if (asking) {
        ConfirmDialog(
            title = stringResource(R.string.lock_permission_title),
            confirm = stringResource(R.string.lock_continue),
            dismiss = stringResource(R.string.lock_cancel),
            onConfirm = {
                asking = false
                waiting = true
                context.startFirst(listOf(Intent(ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()), Intent(ACTION_MANAGE_OVERLAY_PERMISSION)))
            },
            onDismiss = { asking = false },
            text = stringResource(R.string.lock_permission_text),
            icon = R.drawable.ic_lock,
            destructive = false,
        )
    }
    return { switched ->
        when (lockSwitch(switched, canDrawOverlays(context))) {
            LockSwitch.STORE_OFF -> store(false, null)
            LockSwitch.STORE_ON -> store(true, R.string.lock_on)
            LockSwitch.ASK -> asking = true
        }
    }
}

/** The lock-screen lyrics (.ll-lines): every line at 22 sp, the one playing lit by its colour only; plain lyrics at 22. */
internal val LockLine = display(22, 29, -0.015)

/** Where the lock screen's lines sit: the line playing a little above the middle, those sung above it. */
internal val LockFrame = LyricsFrame(sides = 0.dp, top = 0.3f, anchor = 0.4f, bottom = 0.5f)
private val LockStill = display(22, 33)
