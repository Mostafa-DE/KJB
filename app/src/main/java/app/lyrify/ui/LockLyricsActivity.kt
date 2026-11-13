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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
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
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.logFailures
import app.lyrify.lyrics.LrcLine
import app.lyrify.player.PlaybackService
import app.lyrify.player.PlayerGraph
import app.lyrify.player.ServicePart
import app.lyrify.settings.Settings
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Lyrics on the lock screen (P3): big synced lyrics and the controls over the lock screen (showWhenLocked), opened by a
// tap on the music notification (the session activity) and, with "Show when locked", when the screen turns on while
// KJB plays ([LockLyricsPart]). The switch's permission is asked in Settings ([rememberLockSwitch]). A tap on a line or a
// drag of the progress bar seeks (user feedback 2026-11-13, point 4).

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
private val LockInk = Color(0xFF0A0A0B)
private val LockText = Color(0xFFF7F5FF)
private val LockMuted = LockText.copy(alpha = 0.55f)

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
    val swipe = with(LocalDensity.current) { SWIPE_CLOSE.toPx() }
    val title = stringResource(R.string.lock_title)
    Box(
        Modifier
            .fillMaxSize()
            .background(LockInk)
            .semantics { paneTitle = title }
            .pointerInput(onClose) {
                var dragged = 0f
                detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (abs(dragged) > swipe) onClose() }) { _, drag -> dragged += drag }
            },
    ) {
        if (ui == null) return@Box
        val path = ui.item.song.path
        AsyncImage(model = SongArt(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(52.dp).alpha(0.7f))
        Box(Modifier.fillMaxSize().background(LockInk.copy(alpha = 0.55f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                ChromeIconButton(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.lock_close), onClose, glass = false, size = 48.dp, iconSize = 28.dp, tint = LockText)
                SongCover(SongArt(path), size = 44.dp, shape = RoundedCornerShape(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.song?.title ?: path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, color = LockText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.song?.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = body(13f, 18), color = LockMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                LockLines(rememberSongLyrics(path, ledger.entries[path]), ui.positionAt(rememberClock(playing))) { line ->
                    val tap = lineTap(line, playing)
                    connection.seekTo(tap.seekMs)
                    if (tap.play) connection.play()
                }
            }
            LockControls(ui.durationMs, playing, ui::positionAt, connection::seekTo, connection::previous, connection::playPause, connection::next)
            Text(stringResource(R.string.lock_hint), style = MaterialTheme.typography.labelMedium, color = LockMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}

/**
 * The synced lines following the song as on the player (one size for every line, the line playing lit by its colour,
 * a smooth scroll to the anchor), a tap on one going there as on the player ([onLine], [lineTap]); plain lyrics still;
 * or "No lyrics yet". The lines never scroll by hand, so a swipe up or down over them closes the screen.
 */
@Composable
private fun LockLines(lyrics: PanelLyrics?, positionMs: Long, onLine: (LrcLine) -> Unit) {
    val look = rememberLyricsLook()
    val type = MaterialTheme.typography
    if (lyrics is PanelLyrics.Synced) {
        val tones = LineTones(now = DarkBrandColors.highlight, past = LockMuted, next = LockText.copy(alpha = NEXT_INK))
        SyncedLyrics(lyrics.lines, positionMs, enabled = true, LockLine, LockFrame, tones, scrolls = false, onLine = onLine)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        when (lyrics) {
            is PanelLyrics.Plain -> lyrics.lines.take(PLAIN_LINES).forEach {
                Text(it, style = look.style(LockStill, playing = false, still = true), textAlign = look.align, color = LockText, modifier = Modifier.fillMaxWidth())
            }
            PanelLyrics.Finding, PanelLyrics.None -> Text(stringResource(R.string.player_no_lyrics), style = type.titleLarge, color = LockMuted, textAlign = look.align, modifier = Modifier.fillMaxWidth())
            is PanelLyrics.Synced, null -> Unit
        }
    }
}

/** The progress bar ([LockSeekBar]), Previous, Play/Pause and Next. */
@Composable
private fun LockControls(
    durationMs: Long,
    playing: Boolean,
    positionAt: (now: Long) -> Long,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    LockSeekBar(durationMs, playing, positionAt, onSeek)
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        ChromeIconButton(R.drawable.ic_skip_previous_fill, stringResource(R.string.player_previous), onPrevious, glass = false, size = 56.dp, iconSize = 32.dp, tint = LockText)
        PlayPauseButton(playing, onPlayPause, size = 72.dp)
        ChromeIconButton(R.drawable.ic_skip_next_fill, stringResource(R.string.player_next), onNext, glass = false, size = 56.dp, iconSize = 32.dp, tint = LockText)
    }
}

/**
 * The progress bar as the player has it (a 6 dp track in a 48 dp touch area, the knob, the times under it), in the lock
 * screen's own dark colours whatever the phone's theme, with the time played and the time left. Only a deliberate
 * sideways drag seeks ([lockSeekStarts]), to where the finger lifts: a tap or a touch in a pocket never does, and a swipe
 * up or down from it still closes the screen. While the song plays it moves on about a pixel at a time
 * ([lockBarTickMs]). TalkBack finds a slider that reads the time and seeks.
 */
@Composable
internal fun LockSeekBar(durationMs: Long, playing: Boolean, positionAt: (now: Long) -> Long, onSeek: (Long) -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val tick = lockBarTickMs(durationMs, width)
    val position = positionAt(key(tick) { rememberClock(playing, everyMs = tick) })
    val shown = dragging?.let { seekMsAt(it, durationMs) } ?: position
    val share = dragging ?: ringShare(position, durationMs)
    // TalkBack hears the bar move on by the second, as the times do, not at every step.
    val second = position - position % 1_000
    val label = stringResource(R.string.player_position)
    val state = stringResource(R.string.lock_position, clock(position), clock(durationMs))
    val seek by rememberUpdatedState(onSeek)
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
                progressBarRangeInfo = ProgressBarRangeInfo(ringShare(second, durationMs), 0f..1f)
                setProgress { value -> seekMsAt(value, durationMs)?.also { seek(it) } != null }
            }
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val start = awaitSeekStart(awaitFirstDown().id, viewConfiguration.touchSlop) ?: return@awaitEachGesture
                    try {
                        dragging = barShare(start.position.x, size.width.toFloat())
                        val lifted = horizontalDrag(start.id) { change ->
                            dragging = barShare(change.position.x, size.width.toFloat())
                            change.consume()
                        }
                        val to = dragging?.let { seekMsAt(it, durationMs) }
                        if (lifted && to != null) seek(to)
                    } finally {
                        dragging = null
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(DarkBrandColors.highlight.copy(alpha = 0.16f)))
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
        Text(clock(shown), style = style, color = LockMuted)
        Box(Modifier.weight(1f))
        Text(remainingClock(shown, durationMs), style = style, color = LockMuted)
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

/** The time left as the bar shows it, "−3:00", in whole seconds so that it and the time played add up to the length. */
fun remainingClock(positionMs: Long, durationMs: Long): String =
    if (durationMs > 0) "−" + clock((durationMs / 1_000 - positionMs / 1_000).coerceAtLeast(0) * 1_000) else clock(0)

/**
 * How often the lock screen's bar moves on while the song plays: about a pixel of a bar [widthPx] wide at a time, so it
 * glides, never more often than every frame, and at least four times a second for the time shown.
 */
fun lockBarTickMs(durationMs: Long, widthPx: Int): Long =
    if (durationMs > 0 && widthPx > 0) (durationMs / widthPx).coerceIn(FRAME_MS, TIME_TICK_MS) else TIME_TICK_MS

private const val PLAIN_LINES = 7
private val SWIPE_CLOSE = 80.dp

/** A seek starts only on a move at least this many times as far sideways as up or down. */
private const val SIDEWAYS = 2f
private const val FRAME_MS = 16L
private const val TIME_TICK_MS = 250L

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
