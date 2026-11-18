package app.lyrify.ui

import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.books.BookNow
import app.lyrify.books.knownBookPart
import app.lyrify.data.LyricsStatus
import app.lyrify.library.albumKey
import app.lyrify.library.displayTitle
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.player.Queue
import app.lyrify.player.QueueItem
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.sourceLabel
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlayerUiSettings
import app.lyrify.settings.Settings
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

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

/**
 * How many jumps a seek button pressed for [heldMs] has made: let go before [HOLD_DELAY_MS], one, at the lift; held, one
 * at [HOLD_DELAY_MS], then one every [HOLD_EVERY_MS] ([seekPress]).
 */
fun jumpsWhileHeld(heldMs: Long): Int = if (heldMs < HOLD_DELAY_MS) 1 else 1 + ((heldMs - HOLD_DELAY_MS) / HOLD_EVERY_MS).toInt()

/** What a finger pressed on a seek button is so far ([seekPress]). */
enum class SeekPress { WAIT, JUMP, NONE }

/**
 * A finger pressed on a seek button, [moved] dp from where it landed: once the player's drag would decide on it
 * ([playerSwipe], past its 12 dp lock), or has taken it first ([consumed]), no press at all ([SeekPress.NONE]: a drag
 * down that minimises, or a finger sliding off); lifted before that, a tap ([SeekPress.JUMP], one jump, a small drift
 * too); else still a press ([SeekPress.WAIT]), which held past [HOLD_DELAY_MS] starts the jumps. Never a jump at the
 * press itself: it may be a drag down's start.
 */
fun seekPress(moved: Offset, lifted: Boolean, consumed: Boolean): SeekPress = when {
    consumed || playerSwipe(moved.x, moved.y, inEdge = false) != SheetDrag.WAIT -> SeekPress.NONE
    lifted -> SeekPress.JUMP
    else -> SeekPress.WAIT
}

/**
 * A drag on the seek bar or an A-B handle, [moved] px from where it landed: past the touch [slop], mostly sideways (45
 * degrees too) it is theirs ([SheetDrag.TAKE]); mostly up or down it is left, unconsumed, to what is under it
 * ([SheetDrag.LEAVE]), so a drag down from the bar minimises the player ([playerSwipe]); until then, wait.
 */
fun sidewaysDrag(moved: Offset, slop: Float): SheetDrag = when {
    moved.getDistance() <= slop -> SheetDrag.WAIT
    abs(moved.x) >= abs(moved.y) -> SheetDrag.TAKE
    else -> SheetDrag.LEAVE
}

// The cover's swipe (the prototype's coverSwipe) and the player's drag down, in dp

/** How far a drag goes before it locks to an axis; how far sideways it skips. */
const val SWIPE_LOCK_DP: Float = 12f
const val SWIPE_SKIP_DP: Float = 70f

/** The cover moves this share of the finger's way, and tilts a degree for each [TILT_DP] of it. */
const val COVER_FOLLOW: Float = 0.6f
const val TILT_DP: Float = 40f

/** The axis a drag on the cover, or anywhere on the player, keeps once it is clear. */
enum class SwipeAxis { SIDEWAYS, DOWN }

/** A swipe to the left plays the next song, to the right the previous one. */
enum class SkipWay { NEXT, PREVIOUS }

/** What a released swipe on the cover does: skip, or spring back. */
sealed interface SwipeOutcome {
    data class Skip(val way: SkipWay) : SwipeOutcome

    data object Return : SwipeOutcome
}

/** The axis a drag of [dx], [dy] (dp) locks to: sideways past 12 dp and more than down, down past 12 dp and more than sideways; null until then. */
fun swipeAxis(dx: Float, dy: Float): SwipeAxis? = when {
    abs(dx) > SWIPE_LOCK_DP && abs(dx) > abs(dy) -> SwipeAxis.SIDEWAYS
    dy > SWIPE_LOCK_DP && dy > abs(dx) -> SwipeAxis.DOWN
    else -> null
}

/**
 * What the player makes of a finger [dx], [dy] dp from where it landed that nothing on it has taken (the seek bar, the
 * cover's sideways swipe, a list scrolling): down first ([swipeAxis]) it drags the player ([SheetDrag.TAKE]); sideways,
 * or up past [SWIPE_LOCK_DP], it is left to what is under it; until then, wait. One that landed in the system's gesture
 * edge ([inEdge]) is the system's back, never the player's.
 */
fun playerSwipe(dx: Float, dy: Float, inEdge: Boolean): SheetDrag {
    if (inEdge) return SheetDrag.LEAVE
    return when (swipeAxis(dx, dy)) {
        SwipeAxis.DOWN -> SheetDrag.TAKE
        SwipeAxis.SIDEWAYS -> SheetDrag.LEAVE
        null -> if (dy < -SWIPE_LOCK_DP) SheetDrag.LEAVE else SheetDrag.WAIT
    }
}

/**
 * How much of a list's scroll the player takes once the list has had it ([available] px left over, down positive): a
 * finger's pull down ([byFinger]; never a fling's) on a list already at its top, one that has not [scrolled] since the
 * finger landed, while that finger is [held] on the player (it landed outside the gesture edges). A list that scrolled
 * keeps the whole drag, as a sheet's content does ([sheetDrag]).
 */
fun listPull(available: Float, scrolled: Boolean, byFinger: Boolean, held: Boolean): Float =
    if (byFinger && held && !scrolled && available > 0f) available else 0f

/**
 * How much of a finger's push up on a list ([available] px, up negative) the player takes before the list does: while a
 * list's pull holds it [pulled] px down, it goes back up first, never past where it rests; the rest scrolls the list.
 */
fun listPush(available: Float, pulled: Float, byFinger: Boolean): Float =
    if (byFinger && available < 0f && pulled > 0f) maxOf(available, -pulled) else 0f

/**
 * Whether the player takes a list's fling, all of it, so the list never flings under it: the fling after a list's pull
 * the player let go of ([letGo], known at the lift), or one that comes before the player has seen that lift (the list
 * handles the same lift first) while the pull still holds it [pulled] px down ([pulling]).
 */
fun listFling(letGo: Boolean, pulling: Boolean, pulled: Float): Boolean = letGo || (pulling && pulled > 0f)

/** Where the cover is when the finger has moved [dx]: [COVER_FOLLOW] of the way. */
fun coverShift(dx: Float): Float = dx * COVER_FOLLOW

/** The cover's tilt, in degrees, when the finger has moved [dx] dp. */
fun coverTilt(dx: Float): Float = dx / TILT_DP

/** What letting go after a drag of [dx] dp on [axis] does to the cover; down is the player's own ([playerSwipe]). */
fun swipeOutcome(axis: SwipeAxis?, dx: Float): SwipeOutcome = when {
    axis == SwipeAxis.SIDEWAYS && dx < -SWIPE_SKIP_DP -> SwipeOutcome.Skip(SkipWay.NEXT)
    axis == SwipeAxis.SIDEWAYS && dx > SWIPE_SKIP_DP -> SwipeOutcome.Skip(SkipWay.PREVIOUS)
    else -> SwipeOutcome.Return
}

/**
 * The song whose cover slides in for a skip [way]: the one Next or Previous plays, as the player decides it (Next on the
 * last song goes to the first whatever Repeat says, LyrifyPlayer's sessionSeek; Previous wraps round only with Repeat all,
 * and past 3 s starts this song over). Null when no other song comes: a queue of one, the first song's Previous, or
 * Previous starting the song over.
 */
fun incoming(queue: Queue, way: SkipWay, repeat: Repeat, positionMs: Long): QueueItem? {
    val items = queue.items
    val at = when (way) {
        SkipWay.NEXT -> (queue.index + 1).let { if (it < items.size) it else 0 }
        SkipWay.PREVIOUS -> if (positionMs > PREVIOUS_RESTARTS_MS) -1 else (queue.index - 1).let { if (it >= 0) it else if (repeat == Repeat.ALL) items.lastIndex else -1 }
    }
    return items.getOrNull(at)?.takeIf { it.uid != queue.current?.uid }
}

/** Previous starts the song over once it has played this long (the player's own rule, LyrifyPlayer's MAX_SEEK_TO_PREVIOUS_MS). */
private const val PREVIOUS_RESTARTS_MS = 3_000L

/**
 * How far the cover travels to make way for the next one: the incoming cover waits with its near edge at the screen's
 * edge (half the flip [area], half the cover's [side], plus the player's side margin [edge]), so it slides in from there.
 */
fun coverTravel(area: Float, side: Float, edge: Float): Float = (area + side) / 2 + edge

/** Where the cover settles after [outcome]: a skip whose song [slides] in moves it a full [travel] aside; anything else springs back. */
fun settleShift(outcome: SwipeOutcome, slides: Boolean, travel: Float): Float = when {
    outcome is SwipeOutcome.Skip && slides -> if (outcome.way == SkipWay.NEXT) -travel else travel
    else -> 0f
}

/** What a finished swipe does once its cover has settled: skip, land on the song already playing, or spring back. */
enum class Settled { SKIP, LANDED, RETURN }

/**
 * A swipe that started on the queue item [startedOn] and has settled while [playingNow] plays: it skips only when
 * nothing moved meanwhile. A skip from elsewhere (a headset, the notification) that reached the song sliding in
 * ([coming]) lands it without a second skip; one that went anywhere else sends the cover back.
 */
fun settledSwipe(startedOn: Long, playingNow: Long, coming: Long?): Settled = when (playingNow) {
    startedOn -> Settled.SKIP
    coming -> Settled.LANDED
    else -> Settled.RETURN
}

/** Which song's cover shows beside the moving one: the next one when the cover moves left, the previous one when right. */
fun comingFrom(shift: Float): SkipWay? = when {
    shift < 0f -> SkipWay.NEXT
    shift > 0f -> SkipWay.PREVIOUS
    else -> null
}

/** One name on the line under a song's title: the tag's [text], or null for a missing tag, shown as its placeholder. */
data class MetaName(val text: String?) {
    /** A tag opens its page; a placeholder ("No artist", "No album") does nothing. */
    val tappable: Boolean get() = text != null
}

/** The line under a song's title: "Artist · Album", each as tagged (the same name twice included), or its placeholder. */
data class MetaLine(val artist: MetaName, val album: MetaName)

fun metaLine(artist: String?, album: String?): MetaLine = MetaLine(MetaName(artist?.trim()?.takeIf(String::isNotEmpty)), MetaName(album?.trim()?.takeIf(String::isNotEmpty)))

/** "Tap the cover for lyrics": once, when the player opens on a song's cover; a [book]'s cover never flips, so not there. */
fun flipHintOnOpen(ui: PlayerUiSettings, book: Boolean): Boolean = !book && !ui.flipHintShown && !ui.showLyrics

/**
 * Whether the flip area shows the lyrics: the side the user left songs on, the cover for a [book]'s part (books have no
 * lyrics), and the lyrics while the tour's lyrics step points at them ([touring]), for a book its calm state. The side
 * kept for songs is never changed by a book.
 */
fun showsLyrics(ui: PlayerUiSettings, book: Boolean, touring: Boolean): Boolean = touring || ui.showLyrics && !book

/**
 * The settings after a flip to the [lyrics] side or the cover. A [book]'s part flips only by the tour's cover chip, and
 * that keeps the side kept for songs.
 */
fun flipped(ui: PlayerUiSettings, lyrics: Boolean, book: Boolean): PlayerUiSettings = if (book) ui else ui.copy(showLyrics = lyrics)

/**
 * Whether the player's "⋯" offers Make a tone: for a song only. The tone editor reads the `.lrc` beside the file, and
 * nothing reads a book's part's, nor one's not known yet ([knownBookPart] null) while the library is read.
 */
fun makesTone(book: Boolean?): Boolean = book == false

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

    /** A book's part: books never have lyrics, so nothing is read or looked up for one, and nothing offered. */
    data object Book : PanelLyrics
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

/**
 * The line playing at [positionMs]: the last one that has started, or with others sharing its time the first of them
 * (they play together, [lineState]); -1 before the first.
 */
fun currentLine(lines: List<LrcLine>, positionMs: Long): Int {
    val last = lines.indexOfLast { it.timeMs <= positionMs }
    if (last < 0) return -1
    val time = lines[last].timeMs
    return lines.indexOfFirst { it.timeMs == time }
}

/** A time as the player shows it: "1:42", or "1:02:03" past an hour. */
fun clock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60) else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/**
 * The song's name on every player surface ([displayTitle]): the library's song's, else the tags of the file another app
 * opened ([PlayerUi.named]), else the file's name.
 */
fun shownTitle(ui: PlayerUi, settings: LibrarySettings): String = ui.named?.let { displayTitle(it, settings) } ?: ui.item.song.path.substringAfterLast('/')

/** KJB's own song for the Now bar: as every surface names it ([displayTitle]), its cover, and how far it is at [now]. */
fun ownNow(ui: PlayerUi?, settings: LibrarySettings, now: Long): OwnNow? {
    if (ui == null) return null
    val path = ui.item.song.path
    return OwnNow(
        title = shownTitle(ui, settings),
        artist = ui.named?.artist,
        album = ui.named?.album,
        cover = SongArt(path),
        playing = ui.playing,
        progress = ringShare(ui.positionAt(now), ui.durationMs),
        path = path,
    )
}

/** Where the player's "⋯", its sleep chip and its lyrics panel lead: P2's extras ([PlayerExtrasUi]) and P3's lyrics. */
enum class PlayerRoute {
    ADD_TO,
    SPEED,
    REPEAT_PART,
    BOOKMARK,
    PLAY_ON,
    MAKE_TONE,
    SHARE,
    SHARE_LYRICS,
    SONG_INFO,
    SLEEP,
    SEARCH_LYRICS,
    LYRICS_TEXT,
    MOVE_TO_TRASH,
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
    ExtrasToasts()
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
    val extras = rememberPlayerExtras()
    // A book's part, however it plays: its cover only, never the lyrics (LyricsViews.kt songLyrics), nor Share lyrics. Not
    // known yet while the library is read (null): no lyrics read, the flip as for a song.
    val book = knownBookPart(ui.item.song.path, ui.library)
    val bookPart = book == true
    val lyricsShare = if (book != false) null else rememberLyricsShare(ui.item.song.path, LocalLyrics.current.ledger.entries[ui.item.song.path])
    val route: (PlayerRoute) -> Unit = { route ->
        when (route) {
            // The lyrics panel's Search and "Aa" (LyricsViews.kt).
            PlayerRoute.SEARCH_LYRICS, PlayerRoute.LYRICS_TEXT -> lyricsRoute = route
            else -> extras.route(route, ui, settings.library, lyricsShare)
        }
    }
    var queueSheet by remember { mutableStateOf(false) }
    // Book mode (BooksScreen.kt): the chapter playing, and the chapter sheet.
    val bookNow = rememberBookNow(ui)
    var chapterSheet by remember { mutableStateOf(false) }

    // "Tap the cover for lyrics", once, when the player first opens on the cover.
    LaunchedEffect(Unit) {
        val first = store.settings.first().playerUi
        if (flipHintOnOpen(first, book != false)) {
            store.update { it.copy(playerUi = it.playerUi.copy(flipHintShown = true)) }
            toasts.show(Toast(resources.getString(R.string.player_flip_hint), R.drawable.ic_lyrics))
        }
    }

    PlayerFrame(onClose = close) {
        Backdrop(ui.item.song.path)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            PlayerTopBar(ui, lyricsShare, tone = makesTone(book), onClose = close, onRoute = route)
            Column(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 4.dp)) {
                FlipArea(
                    ui = ui,
                    // The tour's Synced lyrics step turns the cover to the lyrics while it points at them; the side kept
                    // stays as the user left it.
                    showLyrics = showsLyrics(settings.playerUi, bookPart, touring = LocalTour.current?.state?.stop == TourStop.LYRICS),
                    flips = !bookPart,
                    onFlip = { lyrics -> scope.launch { store.update { it.copy(playerUi = flipped(it.playerUi, lyrics, bookPart)) } } },
                    onSeek = connection::seekTo,
                    onPlay = connection::play,
                    onNext = connection::next,
                    onPrevious = connection::previous,
                    onRoute = route,
                    modifier = Modifier.weight(1f),
                )
                TitleAndLinks(ui, settings.library, bookNow) { chapterSheet = true }
                PlayerChips(ui, extras)
                Spacer(Modifier.height(LINKS_GAP))
                PartHandles(ui, extras)
                ProgressBar(ui, onSeek = connection::seekTo, marks = { BarMarks(ui); ChapterTicks(ui) })
                MainRow(ui, settings, connection, bookNow)
                SecondaryRow(ui, connection, onQueue = { queueSheet = true }, onLibrary = { nav.select(DockTab.LIBRARY) }, book = bookNow, onChapters = { chapterSheet = true })
            }
        }
        // The message floats down from under the top bar, over the cover, as the prototype's does; the controls stay clear.
        ToastSlot(Modifier.statusBarsPadding().padding(top = PLAYER_BAR + 6.dp).padding(horizontal = 12.dp), float = ToastFloat.DOWN)
    }
    if (queueSheet) QueueSheet(onDismiss = { queueSheet = false })
    if (chapterSheet) ChapterSheet(ui) { chapterSheet = false }
    lyricsRoute?.let { PlayerLyricsSheet(it, ui) { lyricsRoute = null } }
    PlayerExtrasHost(extras, ui)
}

/**
 * The player's frame, around all of it: dragged down from anywhere on it, it follows the finger, shrinking and rounding
 * as it goes ([dragPeek]; with reduced motion it stays put and fades instead, so a drag never closes it unseen), and let
 * go it closes ([onClose]) or springs back ([PlayerDrag]). Closing into the Now bar, the drag is held where the finger
 * left it and goes as the card closes ([closeHeld]), so the player carries on from there into the bar. Whatever on the
 * player takes a drag first keeps it: the seek bar, the A-B handles, the cover's sideways swipe, a list scrolling; a list
 * already at its top hands a pull down over. A finger landing in the system's gesture edge is the system's back. For
 * TalkBack it is a pane named Player; its dismiss is on the chevron ([PlayerCloseButton]), the node TalkBack focuses.
 */
@Composable
internal fun PlayerFrame(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val drag = remember(scope) { PlayerDrag(scope) }
    val density = LocalDensity.current
    val fling = with(density) { DRAG_FLING_DP.dp.toPx() }
    val momentum = with(density) { CLOSE_MOMENTUM_DP.dp.toPx() }
    val container = LocalContainer.current
    val close by rememberUpdatedState(onClose)
    val letGo = rememberUpdatedState { drag.lift(fling, momentum, container, reduced, close) }
    // Shown again while it was leaving (opened again at once), the player comes back whole.
    val headed = LocalLayerHeaded.current
    LaunchedEffect(headed) {
        if (!headed) return@LaunchedEffect
        container?.rest(scope)
        drag.reset()
    }
    // The cover, drawn above everything while the transform runs, takes the frame's drag shrink from here (containerCover).
    DisposableEffect(container, drag) {
        if (container != null) container.frameScale = { drag.peek(container).scale }
        onDispose { container?.frameScale = { 1f } }
    }
    // The system's gesture edges (back), in the window, and where the player sits in it: a finger landing there is the system's.
    val direction = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val window = LocalWindowInfo.current
    val origin = remember { Kept(Offset.Zero) }
    val edge = rememberUpdatedState { at: Offset ->
        inGestureEdge(origin.value.x + at.x, window.containerSize.width.toFloat(), gestures.getLeft(density, direction).toFloat(), gestures.getRight(density, direction).toFloat())
    }
    val pane = stringResource(R.string.player_pane)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { drag.height = it.height.toFloat() }
            .onGloballyPositioned { origin.value = it.positionInWindow() }
            .graphicsLayer {
                if (reduced) {
                    alpha = reducedDragAlpha(drag.offset.value, size.height)
                    return@graphicsLayer
                }
                val peek = drag.peek(container)
                translationY = peek.shiftY
                scaleX = peek.scale
                scaleY = peek.scale
                shape = RoundedCornerShape(PEEK_CORNER * peek.corner)
                clip = peek.corner > 0f
            }
            .nestedScroll(drag.lists)
            .pointerInput(drag) { dragsDown(drag, edge = { edge.value(it) }, letGo = { letGo.value() }) }
            .semantics { paneTitle = pane }
            .background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

/**
 * Each finger on the player, seen after everything on it has had it (the Main pass, children first): one that nothing
 * took and that goes down first ([playerSwipe]) drags the player, every move of it the player's from then on; its lift,
 * or that of a finger whose list handed its pull over ([PlayerDrag.lists]), lets go ([letGo]). A finger landing in the
 * system's gesture [edge] is left alone.
 */
private suspend fun PointerInputScope.dragsDown(drag: PlayerDrag, edge: (Offset) -> Boolean, letGo: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val inEdge = edge(down.position)
        drag.land(down, inEdge)
        var moved = Offset.Zero
        var mine = SheetDrag.WAIT
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            drag.track(change)
            if (!change.pressed) break
            if (mine == SheetDrag.WAIT) {
                // Taken first by something on the player (the seek bar, the cover's skip, a list): it is theirs to the lift.
                if (change.isConsumed) {
                    mine = SheetDrag.LEAVE
                } else {
                    moved += change.positionChange()
                    mine = playerSwipe(moved.x / density, moved.y / density, inEdge)
                }
            }
            if (mine == SheetDrag.TAKE) {
                drag.follow(change.positionChangeIgnoreConsumed().y)
                change.consume()
            }
        }
        letGo()
    }
}

/**
 * The player dragged down ([PlayerFrame]): by a finger nothing on it took ([follow]), or by a list at its top pulled
 * further down ([lists]). It follows the finger ([dragPeek]); let go, it closes past a quarter of its [height] or on a
 * fling down ([dragCloses]), else springs back on [Motion.close] at the finger's speed. Closing into the Now bar, it is
 * held where the finger left it and the container transform takes it on with the finger's speed ([Container.letGo]);
 * falling away, it leaves from where the finger left it ([dragLetGo]).
 */
@Stable
private class PlayerDrag(private val scope: CoroutineScope) {
    val offset = Animatable(0f)
    var height = 0f
    private val tracker = VelocityTracker()

    // The finger on the player: whether it landed outside the gesture edges (only then may a list hand its pull over),
    // whether the list under it has scrolled since, whether the player follows it and by a list's pull, and how far down
    // it has taken it (ahead of the frame drawn).
    private var held = false
    private var scrolled = false
    private var dragging = false
    private var byList = false
    private var at = 0f

    // A list's pull let go at the lift: the list's fling, before the lift is seen here or after, is the player's ([listFling]).
    private var flingTaken = false

    /** A finger lands, in the system's gesture edge ([inEdge]) or not: nothing is taken yet. */
    fun land(down: PointerInputChange, inEdge: Boolean) {
        held = !inEdge
        scrolled = false
        flingTaken = false
        tracker.resetTracking()
        tracker.addPointerInputChange(down)
    }

    /** Each move of that finger, whoever takes it: the speed it lets go at. */
    fun track(change: PointerInputChange) = tracker.addPointerInputChange(change)

    /** How the frame draws the drag closing into the bar with [container] ([Container.held]), or as it is with none. */
    fun peek(container: Container?): Peek = container?.held(offset.value, height) ?: dragPeek(offset.value, height)

    /** The player [dy] px further down (up when negative), never above where it rests; returns how far it went. */
    fun follow(dy: Float): Float {
        val from = if (dragging) at else offset.value
        dragging = true
        at = (from + dy).coerceAtLeast(0f)
        val to = at
        scope.launch { offset.snapTo(to) }
        return to - from
    }

    /**
     * The finger lifts: a drag, its own or a list's pull, lets go ([dragLetGo]), handing its speed to the [container]
     * transform when it closes into the Now bar, at most [momentum] px/s.
     */
    fun lift(fling: Float, momentum: Float, container: Container?, reduced: Boolean, close: () -> Unit) {
        held = false
        if (!dragging) return
        val velocity = tracker.calculateVelocity().y
        tracker.resetTracking()
        val closes = dragCloses(at, velocity, height, fling)
        flingTaken = byList && at > 0f
        dragging = false
        byList = false
        when (dragLetGo(closes, intoBar = container != null, reduced = reduced)) {
            DragLetGo.BACK -> scope.launch { offset.animateTo(0f, Motion.close(), initialVelocity = velocity) }
            DragLetGo.INTO_BAR -> {
                container?.letGo(closeVelocity(velocity, momentum), scope)
                close()
            }
            DragLetGo.AWAY -> close()
        }
    }

    /** A list on the player: pulled down at its top, it moves the player ([listPull]); pushed back up, the player goes home first ([listPush]). */
    val lists = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val push = listPush(available.y, if (byList) at else 0f, byFinger = source == NestedScrollSource.UserInput)
            return if (push == 0f) Offset.Zero else Offset(0f, follow(push))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y != 0f) scrolled = true
            val pull = listPull(available.y, scrolled, byFinger = source == NestedScrollSource.UserInput, held = held)
            if (pull == 0f) return Offset.Zero
            byList = true
            return Offset(0f, follow(pull))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!listFling(flingTaken, byList, at)) return Velocity.Zero
            flingTaken = false
            return available
        }
    }

    /** Back to where it rests, as the player shows again. */
    suspend fun reset() {
        dragging = false
        byList = false
        if (offset.value != 0f) offset.animateTo(0f, Motion.close())
    }
}

/**
 * Behind the top of the player: the cover blurred into a glow that fades into the page by 60% of the height, one
 * seamless gradient with no edge anywhere (the glow and its tint are masked to nothing, never cut at a box's edge), light
 * or dark. It cross-fades from song to song and to a cover Covers & tags changed; it is drawn from a small image (the
 * blur hides it) kept apart from the cover's own sizes ([backdropRequest]), and only once the player has finished growing
 * out of the Now bar, so the transform never re-blurs on every frame.
 */
@Composable
private fun Backdrop(path: String) {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.background
    val reduced = LocalReducedMotion.current
    val container = LocalContainer.current
    val arriving = container != null && container.visibility.transition.run { currentState != targetState }
    val shown by animateFloatAsState(if (arriving) 0f else 1f, motion(tween(Motion.FADE_IN_MS * 2), reduced), label = "backdrop")
    // Keyed by the cover's version too: a cover Covers & tags changed cross-fades in, never the old glow from memory.
    Crossfade(backdropArt(path), Modifier.fillMaxWidth().fillMaxHeight(BACKDROP_SHARE).graphicsLayer { alpha = shown }, animationSpec = tween(if (reduced) Motion.REDUCED_MS else Motion.OPEN_MS), label = "backdrop") { art ->
        val request = remember(art, context) { backdropRequest(context, art) }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // The prototype's tint keeps the top bar readable over any cover.
                    drawRect(Brush.verticalGradient(0f to surface.copy(alpha = 0.72f), 0.3f to surface.copy(alpha = 0.24f), 0.66f to surface.copy(alpha = 0.34f), 1f to surface.copy(alpha = 0.6f)))
                    // Then all of it fades to nothing, smoothly, so the page shows through with no line.
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.4f to Color.Black, 0.7f to Color.Black.copy(alpha = 0.55f), 0.88f to Color.Black.copy(alpha = 0.15f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.5f
                        scaleY = 1.5f
                    }
                    .blur(46.dp, BlurredEdgeTreatment.Unbounded)
                    .alpha(0.7f),
            )
        }
    }
}

/** The rows of the player's "⋯", in the order they show; Move to trash sits apart at the bottom, as on the song sheet. */
enum class PlayerMenuRow { ADD_TO, ALBUM, ARTIST, SPEED, SLEEP, REPEAT_PART, BOOKMARK, PLAY_ON, EQUALIZER, TONE, SHARE, SHARE_LYRICS, SONG_INFO, EDIT_TAGS, MOVE_TO_TRASH }

/**
 * The rows the player's "⋯" shows. A [song] of the library has Share, Edit tags and Move to trash, as on its sheet (a file
 * another app opened has none of them), and Share lyrics when it has [lyrics]; Go to album and Go to artist when the
 * song has their pages ([album], [artist]); Make a tone when [tone] ([makesTone]).
 */
fun playerMenuRows(song: Boolean, album: Boolean, artist: Boolean, tone: Boolean, lyrics: Boolean): List<PlayerMenuRow> = PlayerMenuRow.entries.filter { row ->
    when (row) {
        PlayerMenuRow.ALBUM -> album
        PlayerMenuRow.ARTIST -> artist
        PlayerMenuRow.TONE -> tone
        PlayerMenuRow.SHARE, PlayerMenuRow.EDIT_TAGS, PlayerMenuRow.MOVE_TO_TRASH -> song
        PlayerMenuRow.SHARE_LYRICS -> song && lyrics
        else -> true
    }
}

/**
 * The chevron, "From Album · Name", and "⋯" with the rows [playerMenuRows] gives: Share, Share lyrics ([lyricsShare]),
 * Edit tags and Move to trash (apart at the bottom, in red, as on the song sheet) for a song of the library, Make a tone
 * when [tone] ([makesTone]). Dragged down, as anywhere on it, the player follows ([PlayerFrame]).
 */
@Composable
private fun PlayerTopBar(ui: PlayerUi, lyricsShare: LyricsShare?, tone: Boolean, onClose: () -> Unit, onRoute: (PlayerRoute) -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    var menu by remember { mutableStateOf(false) }
    // Go to artist for a song of several artists: which one, under the same button (ArtistNames.kt).
    var artists by remember { mutableStateOf<List<String>?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PLAYER_BAR)
            .padding(horizontal = 8.dp),
    ) {
        PlayerCloseButton(onClose)
        // Where it plays from, without the time the queue started that the queue lists add.
        val name = ui.queue.name ?: sourceLabel(context, ui.queue.source, ui.queue.items.size)
        Text(
            fromLine(stringResource(R.string.player_from, name), name, MaterialTheme.colorScheme.onSurface),
            style = SourceStyle,
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
                val album = song?.let(::albumKey)
                val pages = song?.let { ui.library?.artistsOf(it) }.orEmpty().map { it.name }
                val artist = artistTap(pages, { nav.open(Page.Artist(it)) }) { artists = it }
                playerMenuRows(song != null, album != null, artist != null, tone, lyricsShare != null).forEach { row ->
                    key(row) {
                        when (row) {
                            PlayerMenuRow.ADD_TO -> MenuRow(stringResource(R.string.player_menu_add_to), R.drawable.ic_playlist_add, pick { onRoute(PlayerRoute.ADD_TO) })
                            PlayerMenuRow.ALBUM -> album?.let { at -> MenuRow(stringResource(R.string.player_menu_album), R.drawable.ic_album, pick { nav.open(Page.Album(at)) }) }
                            PlayerMenuRow.ARTIST -> artist?.let { go -> MenuRow(stringResource(R.string.player_menu_artist), R.drawable.ic_person, pick(go)) }
                            PlayerMenuRow.SPEED -> MenuRow(stringResource(R.string.player_menu_speed), R.drawable.ic_speed, pick { onRoute(PlayerRoute.SPEED) })
                            // Out of the second row since 3.1; what a running timer has left shows under its name.
                            PlayerMenuRow.SLEEP -> SleepMenuRow(ui, pick { onRoute(PlayerRoute.SLEEP) })
                            PlayerMenuRow.REPEAT_PART -> MenuRow(stringResource(if (rememberPartOn(ui)) R.string.part_stop else R.string.player_menu_ab), R.drawable.ic_repeat, pick { onRoute(PlayerRoute.REPEAT_PART) })
                            PlayerMenuRow.BOOKMARK -> MenuRow(stringResource(R.string.player_menu_bookmark), R.drawable.ic_bookmark_add, pick { onRoute(PlayerRoute.BOOKMARK) })
                            PlayerMenuRow.PLAY_ON -> MenuRow(stringResource(R.string.player_menu_play_on), R.drawable.ic_speaker, pick { onRoute(PlayerRoute.PLAY_ON) })
                            // KJB's equalizer (AudioScreens.kt); the phone's own sound effects open from inside it.
                            PlayerMenuRow.EQUALIZER -> MenuRow(stringResource(R.string.player_menu_equalizer), R.drawable.ic_equalizer, pick { nav.show(FullScreen.Equalizer) })
                            PlayerMenuRow.TONE -> MenuRow(stringResource(R.string.player_menu_tone), R.drawable.ic_ring_volume, pick { onRoute(PlayerRoute.MAKE_TONE) })
                            PlayerMenuRow.SHARE -> MenuRow(stringResource(R.string.share), R.drawable.ic_share, pick { onRoute(PlayerRoute.SHARE) })
                            PlayerMenuRow.SHARE_LYRICS -> MenuRow(stringResource(R.string.share_lyrics), R.drawable.ic_share, pick { onRoute(PlayerRoute.SHARE_LYRICS) })
                            PlayerMenuRow.SONG_INFO -> MenuRow(stringResource(R.string.player_menu_info), R.drawable.ic_info, pick { onRoute(PlayerRoute.SONG_INFO) })
                            // The tag editor over the player, as from the song's sheet; Back comes back here.
                            PlayerMenuRow.EDIT_TAGS -> song?.let { MenuRow(stringResource(R.string.lib_edit_tags), R.drawable.ic_sell, pick { nav.show(FullScreen.EditTags(listOf(it.ref))) }) }
                            PlayerMenuRow.MOVE_TO_TRASH -> {
                                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                MenuRow(stringResource(R.string.lib_move_to_trash), R.drawable.ic_delete, pick { onRoute(PlayerRoute.MOVE_TO_TRASH) }, destructive = true)
                            }
                        }
                    }
                }
            }
            ArtistsMenu(artists, { artists = null }) { nav.open(Page.Artist(it)) }
        }
    }
}

/**
 * The cover, and the lyrics panel on its back, in the free space: a tap flips it (a short 3D turn; a crossfade under
 * reduced motion), the "Lyrics" chip and the round cover chip are its visible twins; a book's part never [flips], so its
 * cover has neither tap nor chip ([showsLyrics]). A sideways swipe on the cover skips (twins: Previous and Next); a drag
 * down closes the player, as anywhere on it ([PlayerFrame]; twin: the chevron).
 */
@Composable
private fun FlipArea(
    ui: PlayerUi,
    showLyrics: Boolean,
    flips: Boolean,
    onFlip: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRoute: (PlayerRoute) -> Unit,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val turn = remember { Animatable(if (showLyrics) 180f else 0f) }
    LaunchedEffect(showLyrics, reduced) {
        val target = if (showLyrics) 180f else 0f
        if (reduced) turn.snapTo(target) else turn.animateTo(target, tween(Motion.FLIP_MS, easing = Motion.Flip))
    }
    val fade = remember { Animatable(if (showLyrics) 1f else 0f) }
    LaunchedEffect(showLyrics, reduced) { fade.animateTo(if (showLyrics) 1f else 0f, tween(if (reduced) FADE_MS else 0)) }
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 8.dp, maxHeight - 8.dp, 360.dp).coerceAtLeast(72.dp)
        val area = maxWidth
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
            ) { CoverFace(ui, side, area, enabled = !lyricsShown, onFlip = if (flips) { { onFlip(true) } } else null, onNext, onPrevious) }
        }
        if (lyricsShown || (reduced && fade.value > 0f)) {
            Box(
                Modifier.fillMaxSize().tourTarget(TourStop.LYRICS).graphicsLayer {
                    rotationY = if (reduced) 0f else turn.value - 180f
                    cameraDistance = CAMERA * density
                    alpha = backAlpha
                },
            ) { LyricsPanel(ui, enabled = lyricsShown, onFlipBack = { onFlip(false) }, onSeek = onSeek, onPlay = onPlay, onRoute = onRoute) }
        }
    }
}

/** The song sliding in after a completed skip: the cover stays aside until the player plays it. */
private data class Landing(val way: SkipWay, val item: QueueItem)

/**
 * The cover and its swipe ([swipeAxis] to [settleShift]): it follows the finger and tilts; the next or previous song's
 * cover rides in beside it from the screen's edge; past [SWIPE_SKIP_DP] it finishes the way (the prototype's 0.2 s ease)
 * and only then plays that song, with the incoming cover already in place, so nothing jumps. Short of it, or with no
 * song to slide in (Previous starting the song over, the end of the queue), it springs back. A tap flips to the lyrics
 * ([onFlip]; null for a book: no tap, no chip). A drag down is the player's own ([PlayerFrame]): the cover lets it go,
 * and settles back if it was still moving.
 */
@Composable
internal fun CoverFace(
    ui: PlayerUi,
    side: Dp,
    area: Dp,
    enabled: Boolean,
    onFlip: (() -> Unit)?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val chrome = LocalChromeColors.current
    val scope = rememberCoroutineScope()
    val travel = with(density) { coverTravel(area.toPx(), side.toPx(), PLAYER_EDGE.toPx()) }
    val shape = RoundedCornerShape(COVER_CORNER)
    val shadow = with(density) { COVER_SHADOW.toPx() }
    val settle = motion(tween<Float>(Motion.SETTLE_MS, easing = Motion.Ease), reduced)
    // The cover's place in px: the finger's while it drags, then the settling animation's.
    val shift = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var landing by remember { mutableStateOf<Landing?>(null) }
    val arrived = landing?.item?.uid == ui.item.uid
    LaunchedEffect(arrived) {
        if (arrived) {
            shift.snapTo(0f)
            landing = null
        }
    }
    // A song the player did not move to (one it could not play): the cover comes back after a moment.
    LaunchedEffect(landing) {
        if (landing == null) return@LaunchedEffect
        delay(LANDING_WAIT_MS)
        landing = null
        shift.animateTo(0f, settle)
    }
    val latest by rememberUpdatedState(ui)
    val actions by rememberUpdatedState(listOf(onNext, onPrevious))
    val place = { if (arrived) 0f else dragging ?: shift.value }
    // Which side the cover is moving to changes rarely; reading it so does not recompose on every pixel.
    val moving by remember { derivedStateOf { comingFrom(dragging ?: shift.value) } }
    val way = if (arrived) null else landing?.way ?: moving
    val beside = remember(moving, ui.item.uid, ui.queue, ui.repeat) {
        moving?.let { incoming(ui.queue, it, ui.repeat, ui.positionAt(SystemClock.elapsedRealtime())) }
    }
    val neighbour = if (arrived) null else landing?.item ?: beside
    val label = stringResource(if (onFlip != null) R.string.player_cover else R.string.book_cover, ui.named?.title ?: ui.item.song.path.substringAfterLast('/'))
    Box(Modifier.fillMaxSize()) {
        if (neighbour != null && way != null) {
            val from = if (way == SkipWay.NEXT) travel else -travel
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = place() + from
                        shadowElevation = shadow
                        this.shape = shape
                        ambientShadowColor = chrome.shadowStrong
                        spotShadowColor = chrome.shadowStrong
                    },
            ) { SongCover(SongArt(neighbour.song.path), size = side, shape = shape) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val x = place()
                    translationX = x
                    rotationZ = coverTilt(x / COVER_FOLLOW / density.density)
                    shadowElevation = shadow
                    this.shape = shape
                    ambientShadowColor = chrome.shadowStrong
                    spotShadowColor = chrome.shadowStrong
                }
                .clip(shape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedOn = latest.item.uid
                        val start = shift.value
                        scope.launch { shift.stop() }
                        var axis: SwipeAxis? = null
                        var moved = Offset.Zero
                        var lifted = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                lifted = true
                                break
                            }
                            moved += change.positionChange()
                            if (axis == null) axis = swipeAxis(moved.x / density.density, moved.y / density.density)
                            if (axis == SwipeAxis.SIDEWAYS) {
                                change.consume()
                                dragging = start + coverShift(moved.x)
                                continue
                            }
                            if (axis == null) {
                                // Not clear yet: wait and see, unless the player's drag down took this move first.
                                awaitPointerEvent(PointerEventPass.Final)
                                if (!change.isConsumed) continue
                            }
                            // Down: the player's drag ([PlayerFrame]), never a skip. A cover the finger caught on its way
                            // goes back, as after a tap.
                            if (landing == null && shift.value != 0f) scope.launch { shift.animateTo(0f, settle) }
                            return@awaitEachGesture
                        }
                        val outcome = if (lifted) swipeOutcome(axis, moved.x / density.density) else SwipeOutcome.Return
                        val from = dragging ?: shift.value
                        val now = latest
                        val coming = (outcome as? SwipeOutcome.Skip)?.let { incoming(now.queue, it.way, now.repeat, now.positionAt(SystemClock.elapsedRealtime())) }
                        val (next, previous) = actions
                        scope.launch {
                            shift.snapTo(from)
                            dragging = null
                            shift.animateTo(settleShift(outcome, slides = coming != null, travel = travel), settle)
                            if (outcome !is SwipeOutcome.Skip) return@launch
                            // The song changes only now, with its cover in place, and only if nothing else moved it meanwhile.
                            when (settledSwipe(startedOn, latest.item.uid, coming?.uid)) {
                                Settled.SKIP -> {
                                    if (coming != null) landing = Landing(outcome.way, coming)
                                    if (outcome.way == SkipWay.NEXT) next() else previous()
                                }
                                Settled.LANDED -> landing = coming?.let { Landing(outcome.way, it) }
                                Settled.RETURN -> shift.animateTo(0f, settle)
                            }
                        }
                    }
                }
                .then(if (onFlip != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onFlip) else Modifier)
                .semantics { contentDescription = label },
        ) {
            // Its corners follow its size, so growing out of the Now bar's orb or shrinking into it, it rounds smoothly.
            SongCover(SongArt(ui.item.song.path), containerCover(LocalContainer.current), size = side, shape = remember(side) { CoverShape(COVER_CORNER, side) }, at = CoverAt.PLAYER)
        }
        if (onFlip != null) FlipChip(Modifier.align(Alignment.BottomEnd).padding(12.dp), enabled, onFlip)
    }
}

/**
 * The chevron that closes the player ([onClose]), with TalkBack's dismiss on it: the labelled, clickable node TalkBack
 * focuses, so the dismiss reaches it (review 2026-11-17: on the player's unnamed frame it never did).
 */
@Composable
internal fun PlayerCloseButton(onClose: () -> Unit) {
    val label = stringResource(R.string.player_close)
    ChromeIconButton(
        R.drawable.ic_keyboard_arrow_down,
        label,
        onClose,
        modifier = Modifier.semantics {
            dismiss(label) {
                onClose()
                true
            }
        },
    )
}

/** "From [name]" with the name in semibold on-surface, as the prototype's .src b. */
private fun fromLine(text: String, name: String, nameColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val at = text.lastIndexOf(name)
    if (at >= 0) addStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold), at, at + name.length)
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
            .shadow(6.dp, CircleShape, ambientColor = chrome.shadow, spotColor = chrome.shadow)
            .clip(CircleShape)
            .background(chrome.glassSolid, CircleShape)
            .border(1.dp, chrome.glassEdge, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onFlip)
            .semantics { contentDescription = label }
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_lyrics), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.player_lyrics_chip), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The back of the cover: synced lines that scroll with the current one lit (a tap jumps there), plain lyrics as still
 * text, or the state ("Finding lyrics", "No lyrics yet" with Search). Its header holds the status pill, "Aa" and
 * "Full screen"; the round cover chip flips back. The `.lrc` next to the song is read off the main thread, again
 * whenever its ledger entry changes. A book's part (shown only by the tour's lyrics step) has a calm "No lyrics for
 * books", with no pill, "Aa", "Full screen" or Search.
 */
@Composable
private fun LyricsPanel(ui: PlayerUi, enabled: Boolean, onFlipBack: () -> Unit, onSeek: (Long) -> Unit, onPlay: () -> Unit, onRoute: (PlayerRoute) -> Unit) {
    val nav = LocalNavigator.current
    val path = ui.item.song.path
    val entry = LocalLyrics.current.ledger.entries[path]
    val book = knownBookPart(path, ui.library)
    val lyrics = rememberSongLyrics(path, entry, book)
    val now = rememberClock(ui.playing)
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 4.dp)) {
            when {
                book != false -> Unit
                lyrics == PanelLyrics.Finding -> StatusPill(stringResource(R.string.player_finding_lyrics), tone = null, leading = { BrandGlyph(width = 12.dp, sweeping = true) })
                entry != null -> StatusPill(entry.status)
            }
            Spacer(Modifier.weight(1f))
            if (book == false) {
                AaButton(enabled) { onRoute(PlayerRoute.LYRICS_TEXT) }
                LyrifyButton(stringResource(R.string.player_full_screen), { nav.show(FullScreen.FullLyrics) }, kind = ButtonKind.GHOST, icon = R.drawable.ic_open_in_full, compact = true, enabled = enabled)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val shown = lyrics) {
                is PanelLyrics.Synced -> SyncedLyrics(shown.lines, ui.positionAt(now), enabled, PanelLine, PanelFrame) { line ->
                    val tap = lineTap(line, ui.playing)
                    onSeek(tap.seekMs)
                    if (tap.play) onPlay()
                }
                is PanelLyrics.Plain -> StillLyrics(shown.lines, PanelStill, PaddingValues(start = 22.dp, end = 22.dp, bottom = 64.dp))
                PanelLyrics.Finding -> PanelState(stringResource(R.string.player_finding_lyrics), sweeping = true, action = null)
                PanelLyrics.None -> PanelState(stringResource(R.string.player_no_lyrics)) { onRoute(PlayerRoute.SEARCH_LYRICS) }
                PanelLyrics.Book -> PanelState(stringResource(R.string.book_no_lyrics), action = null)
                null -> Unit
            }
            CoverChip(ui, Modifier.align(Alignment.BottomEnd).padding(10.dp), enabled, onFlipBack)
        }
    }
}

/** The panel's state: the brand glyph (sweeping while lyrics are being found), [title], and Search when there is an [action]. */
@Composable
internal fun PanelState(title: String, sweeping: Boolean = false, action: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        BrandGlyph(width = 28.dp, sweeping = sweeping)
        Text(title, style = LyricsStateTitle, textAlign = TextAlign.Center)
        if (action != null) LyrifyButton(stringResource(R.string.player_search_lyrics), action, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_search, compact = true)
    }
}

/** The round cover chip in the panel's corner: the visible twin of the flip back. */
@Composable
private fun CoverChip(ui: PlayerUi, modifier: Modifier, enabled: Boolean, onFlipBack: () -> Unit) {
    val chrome = LocalChromeColors.current
    val label = stringResource(R.string.player_show_cover, ui.named?.title ?: ui.item.song.path.substringAfterLast('/'))
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

/**
 * The title, then "Artist · Album" on one line, 48 dp tall to touch: each tag opens its page with a press tint, in the
 * secondary ink and without an underline; a missing tag shows its placeholder ("No artist", "No album") in the quieter
 * ink and does nothing ([metaLine]). A song that is not on the phone says so.
 */
@Composable
private fun TitleAndLinks(ui: PlayerUi, settings: LibrarySettings, book: BookNow?, onChapters: () -> Unit) {
    val nav = LocalNavigator.current
    val song = ui.song
    Text(
        // A book: the chapter playing, else the book's title (BooksScreen.kt).
        book?.let { now -> if (now.chapter >= 0) chapterTitle(now.chapters, now.chapter) else now.book.title }
            ?: shownTitle(ui, settings),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = TOUCH)) {
        val opened = ui.opened
        if (song == null && opened != null && (opened.artist != null || opened.album != null)) {
            // A file another app opened that the library lacks (player/OpenedFiles.kt): its own tags, which open no page.
            val meta = metaLine(opened.artist, opened.album)
            PlayerLink(meta.artist.text ?: stringResource(R.string.player_no_artist), onClick = null, Modifier.weight(1f, fill = false))
            Text("·", style = MetaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            PlayerLink(meta.album.text ?: stringResource(R.string.player_no_album), onClick = null, Modifier.weight(1f, fill = false))
            return@Row
        }
        if (song == null) {
            // Without tags of its own, it says where it came from.
            PlayerLink(stringResource(if (ui.queue.source.kind == SourceKind.OPENED) R.string.open_queue else R.string.player_not_on_phone), onClick = null, Modifier)
            return@Row
        }
        if (book != null) {
            // "Chapter 7 of 22" opens the chapter sheet; a book without chapters shows its title.
            PlayerLink(chapterOf(book) ?: book.book.title, onChapters.takeIf { book.chapters.isNotEmpty() }, Modifier.weight(1f, fill = false))
            book.book.author?.let { author ->
                Text("·", style = MetaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                PlayerLink(author, onClick = null, Modifier.weight(1f, fill = false))
            }
            return@Row
        }
        val meta = metaLine(song.artist, song.album)
        // A tag of several artists asks which one first (ArtistNames.kt).
        val artists = remember(ui.library, song) { ui.library?.artistsOf(song)?.map { it.name }.orEmpty() }
        ArtistLink(artists, { nav.open(Page.Artist(it)) }, Modifier.weight(1f, fill = false)) { go ->
            PlayerLink(meta.artist.text ?: stringResource(R.string.player_no_artist), go?.takeIf { meta.artist.tappable }, Modifier)
        }
        Text("·", style = MetaStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        val key = albumKey(song)
        PlayerLink(meta.album.text ?: stringResource(R.string.player_no_album), key?.takeIf { meta.album.tappable }?.let { { nav.open(Page.Album(it)) } }, Modifier.weight(1f, fill = false))
    }
}

/** One name of the line under the title: a tappable tag in the secondary ink, or a placeholder in the quieter one. */
@Composable
private fun PlayerLink(text: String, onClick: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        // At least 48 dp to touch whatever room the line is given; the words look the same.
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = TOUCH)
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text,
            style = if (onClick != null) MetaStyle.copy(fontWeight = FontWeight.Medium) else MetaStyle,
            color = if (onClick != null) colors.onSurfaceVariant else colors.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The chips row (speed, A-B) while one is active (P2); it keeps [CHIPS_GAP] above the progress bar's touch area. */
@Composable
private fun PlayerChips(ui: PlayerUi, extras: PlayerExtrasUi) = ExtrasChips(ui, extras)

private const val FADE_MS = 220
private const val CAMERA = 16f

/** The source line (.src, 13/18) and the artist and album line (.pmeta, 15/22). */
private val SourceStyle = body(13f, 18)
private val MetaStyle = body(15f, 22)

/** The player's top bar: the chevron, "From ...", and "⋯". */
private val PLAYER_BAR = 64.dp

/** The backdrop's height, as a share of the player's: by there the glow has faded into the page. */
private const val BACKDROP_SHARE = 0.6f

/** A blurred backdrop's cover, in px: blurred some 50 dp, a larger one would only cost decoding and upload time. */
internal const val BACKDROP_PX = 192

/** The player's side margin, past which the next cover waits. */
private val PLAYER_EDGE = 24.dp
private val COVER_CORNER = 28.dp

/** The prototype's cover shadow (0 10px 18px -10px, the strong shadow). */
private val COVER_SHADOW = 10.dp

/** How long a completed skip waits for the player to play the song before the cover comes back. */
private const val LANDING_WAIT_MS = 1_500L
