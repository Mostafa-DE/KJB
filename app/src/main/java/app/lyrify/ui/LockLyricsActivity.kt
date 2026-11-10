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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

// Lyrics on the lock screen (P3): big synced lyrics and the controls over the lock screen (showWhenLocked), opened by a
// tap on the music notification (the session activity) and, with "Show when locked", when the screen turns on while
// KJB plays ([LockLyricsPart]). The switch's permission is asked in Settings ([rememberLockSwitch]).

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
                LockLines(rememberSongLyrics(path, ledger.entries[path]), ui.positionAt(rememberClock(playing)))
            }
            LockControls(ui.positionAt(rememberClock(playing, everyMs = 500)), ui.durationMs, playing, connection::previous, connection::playPause, connection::next)
            Text(stringResource(R.string.lock_hint), style = MaterialTheme.typography.labelMedium, color = LockMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}

/**
 * The synced lines following the song as on the player (one size for every line, the line playing lit by its colour,
 * a smooth scroll to the anchor); plain lyrics still; or "No lyrics yet". Nothing here is tapped or scrolled.
 */
@Composable
private fun LockLines(lyrics: PanelLyrics?, positionMs: Long) {
    val look = rememberLyricsLook()
    val type = MaterialTheme.typography
    if (lyrics is PanelLyrics.Synced) {
        SyncedLyrics(lyrics.lines, positionMs, enabled = false, LockLine, LockFrame, LineTones(now = LockText, past = LockMuted, next = LockMuted)) { }
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

/** The position (shown only), Previous, Play/Pause and Next. */
@Composable
private fun LockControls(positionMs: Long, durationMs: Long, playing: Boolean, onPrevious: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    val share = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(4.dp).clip(CircleShape).background(LockMuted.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(share).height(4.dp).background(LockText))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val style = MaterialTheme.typography.bodySmall.tabular()
        Text(clock(positionMs), style = style, color = LockMuted)
        Box(Modifier.weight(1f))
        Text(clock(durationMs), style = style, color = LockMuted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        ChromeIconButton(R.drawable.ic_skip_previous_fill, stringResource(R.string.player_previous), onPrevious, glass = false, size = 56.dp, iconSize = 32.dp, tint = LockText)
        PlayPauseButton(playing, onPlayPause, size = 72.dp)
        ChromeIconButton(R.drawable.ic_skip_next_fill, stringResource(R.string.player_next), onNext, glass = false, size = 56.dp, iconSize = 32.dp, tint = LockText)
    }
}

private const val PLAIN_LINES = 7
private val SWIPE_CLOSE = 80.dp

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
