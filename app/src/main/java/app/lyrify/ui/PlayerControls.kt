package app.lyrify.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.books.BOOK_SKIP_MS
import app.lyrify.books.BookNow
import app.lyrify.library.LibraryGraph
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerUi
import app.lyrify.player.Repeat
import app.lyrify.player.ShuffleStep
import app.lyrify.settings.DockTab
import app.lyrify.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The player's controls near the thumb (P1): the progress bar, the main row (seek back, Previous, Play/Pause, Next,
// seek forward) and the secondary row (Shuffle, Queue, the heart, Library, Repeat). PlayerScreen.kt lays them out.

/**
 * The progress bar: a 6 dp track in a 48 dp touch area, a tap or a sideways drag seeks (on release), and the times under
 * it. A mostly vertical drag on it is the player's ([detectSidewaysDrag]).
 */
@Composable
internal fun ProgressBar(ui: PlayerUi, onSeek: (Long) -> Unit, marks: @Composable () -> Unit = {}) {
    val now = rememberClock(ui.playing, everyMs = 500)
    val position = ui.positionAt(now)
    val duration = ui.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val share = dragging ?: ringShare(position, duration)
    val highlight = LocalBrandColors.current.highlight
    val label = stringResource(R.string.player_position)
    val state = "${clock(position)} / ${clock(duration)}"
    val seek by rememberUpdatedState(onSeek)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(TOUCH)
            .semantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(share, 0f..1f)
                setProgress { value ->
                    if (duration > 0) seek((value.coerceIn(0f, 1f) * duration).toLong())
                    duration > 0
                }
            }
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) seek((offset.x / size.width).coerceIn(0f, 1f).times(duration).toLong()) }
            }
            .pointerInput(duration) {
                detectSidewaysDrag(
                    onDragEnd = {
                        dragging?.let { if (duration > 0) seek((it * duration).toLong()) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(highlight.copy(alpha = 0.16f)))
        Box(Modifier.fillMaxWidth(share).height(6.dp).clip(CircleShape).background(highlight))
        // P2: the bookmarks' ticks and the part repeating.
        marks()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val knob by animateFloatAsState(if (dragging != null) KNOB_DRAG_SCALE else 1f, motion(tween(Motion.QUICK_MS), LocalReducedMotion.current), label = "knob")
            Box(
                Modifier
                    .offset(x = maxWidth * share - 11.dp)
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = knob
                        scaleY = knob
                    }
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(3.dp)
                    .background(highlight, CircleShape),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.bodySmall.tabular()
        Text(clock(dragging?.let { (it * duration).toLong() } ?: position), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(clock(duration), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A drag along the seek bar or an A-B handle, decided once it has passed the touch slop ([sidewaysDrag]). Mostly
 * sideways, it is taken: every move consumed, [onDragStart], then [onDrag] with the move's x (the first time all of it
 * since the finger landed), and [onDragEnd] at the lift ([onDragCancel] if the finger is lost). Mostly up or down, or
 * taken first by the player's drag (seen on the Final pass), it is left alone, unconsumed, and nothing is called.
 */
internal suspend fun PointerInputScope.detectSidewaysDrag(
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (change: PointerInputChange, dx: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var moved = Offset.Zero
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            moved += change.positionChange()
            when (sidewaysDrag(moved, slop)) {
                SheetDrag.LEAVE -> return@awaitEachGesture
                SheetDrag.TAKE -> {
                    change.consume()
                    onDragStart()
                    onDrag(change, moved.x)
                    break
                }
                SheetDrag.WAIT -> {
                    awaitPointerEvent(PointerEventPass.Final)
                    if (change.isConsumed) return@awaitEachGesture
                }
            }
        }
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            if (change == null) {
                onDragCancel()
                return@awaitEachGesture
            }
            if (!change.pressed) {
                onDragEnd()
                return@awaitEachGesture
            }
            onDrag(change, change.takeMoveX())
        }
    }
}

/** This change's move along x, the change then consumed: read first, as a consumed change reports no move at all. */
internal fun PointerInputChange.takeMoveX(): Float {
    val dx = positionChange().x
    consume()
    return dx
}

/**
 * Back N s, Previous, Play/Pause, Next, Forward N s: the seek buttons smaller, showing the chosen amounts. In a [book],
 * 30 s each way, and Previous and Next go by chapter (BooksScreen.kt), with the chapter icons ([stepIcons]).
 */
@Composable
internal fun MainRow(ui: PlayerUi, settings: Settings, connection: PlayerConnection, book: BookNow? = null) {
    val back = if (book != null) BOOK_SKIP_S else settings.playback.skipBackSeconds
    val forward = if (book != null) BOOK_SKIP_S else settings.playback.skipForwardSeconds
    val icons = stepIcons(book != null)
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SeekJump(seekIcon(back = true, back), pluralStringResource(R.plurals.player_back_seconds, back, back)) { connection.seekBy(-back * 1_000L) }
        if (book != null) {
            RoundButton(icons.first, stringResource(R.string.book_previous_chapter), size = 56.dp, iconSize = 38.dp) { chapterStep(connection, ui, book, forward = false) }
        } else {
            RoundButton(icons.first, stringResource(R.string.player_previous), size = 56.dp, iconSize = 38.dp, onClick = connection::previous)
        }
        val chrome = LocalChromeColors.current
        val label = stringResource(if (ui.playing) R.string.player_pause else R.string.player_play)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .shadow(12.dp, CircleShape, ambientColor = chrome.shadowStrong, spotColor = chrome.shadowStrong)
                .clip(CircleShape)
                .background(chrome.action, CircleShape)
                .clickable(interactionSource = null, indication = PressIndication(tint = chrome.onAction), role = Role.Button, onClick = connection::playPause)
                .semantics { contentDescription = label },
        ) {
            PlayPauseIcon(ui.playing, tint = chrome.onAction, modifier = Modifier.size(40.dp))
        }
        if (book != null) {
            RoundButton(icons.second, stringResource(R.string.book_next_chapter), size = 56.dp, iconSize = 38.dp) { chapterStep(connection, ui, book, forward = true) }
        } else {
            RoundButton(icons.second, stringResource(R.string.player_next), size = 56.dp, iconSize = 38.dp, onClick = connection::next)
        }
        SeekJump(seekIcon(back = false, forward), pluralStringResource(R.plurals.player_forward_seconds, forward, forward)) { connection.seekBy(forward * 1_000L) }
    }
}

/**
 * A seek button: a tap jumps once, at the lift; held still, the jumps start after a short hold and repeat
 * ([jumpsWhileHeld]), and the button keeps that finger to its lift. A finger the player's drag takes first, or that
 * slides off, is no press ([seekPress]): a drag down from the button minimises the player with no jump; a tap that
 * drifts a little short of that still jumps. TalkBack's tap jumps once.
 */
@Composable
internal fun SeekJump(@DrawableRes icon: Int, label: String, jump: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(jump)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TOUCH)
            .clip(CircleShape)
            .semantics {
                contentDescription = label
                onClick {
                    latest()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var moved = Offset.Zero
                    // A press, not yet a jump: nothing is consumed, so a drag down stays the player's.
                    val press = withTimeoutOrNull(HOLD_DELAY_MS) {
                        var now = SeekPress.WAIT
                        while (now == SeekPress.WAIT) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                now = SeekPress.NONE
                            } else {
                                moved += change.positionChange()
                                now = seekPress(moved / density, lifted = !change.pressed, consumed = change.isConsumed)
                                // Still a press: unless the player's drag took this move after the button saw it.
                                if (now == SeekPress.WAIT) {
                                    awaitPointerEvent(PointerEventPass.Final)
                                    if (change.isConsumed) now = SeekPress.NONE
                                }
                            }
                        }
                        now
                    }
                    when (press) {
                        SeekPress.JUMP -> latest()
                        // Held past the short hold: the jumps repeat while the finger stays, and it is the button's.
                        null -> {
                            val repeat = scope.launch {
                                while (true) {
                                    latest()
                                    delay(HOLD_EVERY_MS)
                                }
                            }
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    moved += change.positionChange()
                                    change.consume()
                                    if (seekPress(moved / density, lifted = false, consumed = false) == SeekPress.NONE) repeat.cancel()
                                }
                            } finally {
                                repeat.cancel()
                            }
                        }
                        else -> Unit
                    }
                }
            },
    ) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(27.dp)) }
}

/** A place in the player's second row ([secondaryButtons]); [ROOM] is an empty one, so the others keep their places. */
enum class PlayerButton { SHUFFLE, CHAPTERS, QUEUE, FAVOURITE, LIBRARY, REPEAT, ROOM }

/**
 * The second row, left to right (3.1, the user's: the heart in the middle, Library where the sleep timer was, which
 * moved to the "⋯"): Shuffle, Queue, the heart, Library and Repeat. A [book] never shuffles or repeats: Chapters takes
 * Shuffle's place when it has [chapters], and room is kept for what it lacks.
 */
fun secondaryButtons(book: Boolean, chapters: Boolean): List<PlayerButton> = listOf(
    when {
        !book -> PlayerButton.SHUFFLE
        chapters -> PlayerButton.CHAPTERS
        else -> PlayerButton.ROOM
    },
    PlayerButton.QUEUE,
    PlayerButton.FAVOURITE,
    PlayerButton.LIBRARY,
    if (book) PlayerButton.ROOM else PlayerButton.REPEAT,
)

/**
 * The second row ([secondaryButtons]): Shuffle, Queue, the heart, Library ([onLibrary]: the player closes onto the
 * Library item of the bottom bar) and Repeat, each 48 dp to touch. Toggles that are on show in the highlight colour,
 * with a bar. A [book] has Chapters in Shuffle's place when it has chapters.
 */
@Composable
internal fun SecondaryRow(ui: PlayerUi, connection: PlayerConnection, onQueue: () -> Unit, onLibrary: () -> Unit, book: BookNow? = null, onChapters: () -> Unit = {}) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val favourites = remember(context) { LibraryGraph.favourites(context) }
    val favouriteSongs by favourites.songs.collectAsStateWithLifecycle(initialValue = emptyList())
    val favourite = favouriteSongs.any { it.path == ui.item.song.path }
    val step = ui.queue.shuffleStep
    val suggestions = rememberSuggestions()
    // The Shuffle button's last message: a newer tap cancels it, so a step already left is never announced, nor undone.
    val shuffleSaid = remember { mutableStateOf<Job?>(null) }
    val repeat = ui.repeat
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        secondaryButtons(book != null, book?.chapters?.isNotEmpty() == true).forEach { button ->
            when (button) {
                // Off, Shuffle, Smart shuffle, and round again (Smart shuffle option A, user 2026-11-22); Smart shuffle wears
                // the suggestion mark, and its message's Undo goes back to plain Shuffle.
                PlayerButton.SHUFFLE -> ToggleButton(R.drawable.ic_shuffle, stringResource(shuffleLabel(step)), on = step != ShuffleStep.OFF, badge = step == ShuffleStep.SMART, onColor = shuffleColor(step, LocalBrandColors.current)) {
                    val next = connection.cycleShuffle()
                    shuffleSaid.value?.cancel()
                    shuffleSaid.value = scope.launch {
                        if (next != ShuffleStep.SMART) {
                            toasts.show(Toast(resources.getString(if (next == ShuffleStep.SHUFFLE) R.string.player_shuffled else R.string.player_unshuffled), R.drawable.ic_shuffle))
                        } else if (toasts.show(Toast(resources.getString(R.string.suggest_smart_on, suggestions.mix().oneIn), R.drawable.ic_explore, action = resources.getString(R.string.player_undo)))) {
                            connection.plainShuffle()
                        }
                    }
                }
                PlayerButton.CHAPTERS -> RoundButton(R.drawable.ic_format_list_numbered, stringResource(R.string.books_chapters), onClick = onChapters)
                PlayerButton.QUEUE -> RoundButton(R.drawable.ic_queues, stringResource(R.string.player_queue), onClick = onQueue)
                PlayerButton.FAVOURITE -> ToggleButton(if (favourite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite, stringResource(heartLabel(favourite)), on = favourite, bar = false) {
                    val song = listOf(ui.item.song)
                    scope.launch {
                        if (!favourites.setSongs(song, !favourite)) {
                            toasts.show(Toast(resources.getString(R.string.player_favourite_failed), R.drawable.ic_sync_problem, Tone.ERROR))
                            return@launch
                        }
                        val undo = toasts.show(Toast(resources.getString(if (favourite) R.string.player_unfavourited else R.string.player_favourited), R.drawable.ic_favorite, action = resources.getString(R.string.player_undo)))
                        if (undo) favourites.setSongs(song, favourite)
                    }
                }
                PlayerButton.LIBRARY -> RoundButton(DockTab.LIBRARY.icon, stringResource(DockTab.LIBRARY.label), onClick = onLibrary)
                PlayerButton.REPEAT -> ToggleButton(if (repeat == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, stringResource(repeatLabel(repeat)), on = repeat != Repeat.OFF) {
                    val next = connection.cycleRepeat()
                    scope.launch { toasts.show(Toast(resources.getString(repeatLabel(next)), if (next == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)) }
                }
                PlayerButton.ROOM -> Spacer(Modifier.size(TOUCH))
            }
        }
    }
}

/** The Shuffle button's colour when on: Smart shuffle in suggestions' teal, so it reads apart from Shuffle's coral (user 2026-11-22). */
internal fun shuffleColor(step: ShuffleStep, brand: BrandColors): Color = if (step == ShuffleStep.SMART) brand.suggestion else brand.highlight

private fun shuffleLabel(step: ShuffleStep): Int = when (step) {
    ShuffleStep.OFF -> R.string.player_shuffle_off
    ShuffleStep.SHUFFLE -> R.string.player_shuffle_on
    ShuffleStep.SMART -> R.string.player_shuffle_smart
}

private fun repeatLabel(repeat: Repeat): Int = when (repeat) {
    Repeat.OFF -> R.string.player_repeat_off
    Repeat.ALL -> R.string.player_repeat_all
    Repeat.ONE -> R.string.player_repeat_one
}

@Composable
internal fun RoundButton(@DrawableRes icon: Int, label: String, size: Dp = TOUCH, iconSize: Dp = 26.dp, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(iconSize)) }
}

/** A toggle of the second row: in [onColor] (the highlight) with a bar while [on]; [badge]: the suggestion mark on its corner (Smart shuffle). */
@Composable
private fun ToggleButton(
    @DrawableRes icon: Int,
    label: String,
    on: Boolean,
    bar: Boolean = true,
    badge: Boolean = false,
    onColor: Color = LocalBrandColors.current.highlight,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(TOUCH).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (on) onColor else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
        if (badge) SuggestionBadge(Modifier.align(Alignment.Center).offset(9.dp, 7.dp))
        if (on && bar) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(14.dp, 3.dp).clip(CircleShape).background(onColor))
    }
}

/**
 * The main row's Previous and Next icons: in a [book] the chapter pair, the skip arrow pointing at KJB's book (back and
 * forward), so they read as this book's chapters, not the next song; for music the skip pair.
 */
internal fun stepIcons(book: Boolean): Pair<Int, Int> =
    if (book) R.drawable.ic_chapter_previous to R.drawable.ic_chapter_next else R.drawable.ic_skip_previous_fill to R.drawable.ic_skip_next_fill

/** A book's jumps, in seconds (books/BookPlayback.kt): the player's and the lock screen's. */
internal const val BOOK_SKIP_S: Int = (BOOK_SKIP_MS / 1_000).toInt()

/** The progress knob grows this much under the finger (the prototype's scale(1.3) over 0.12 s). */
internal const val KNOB_DRAG_SCALE = 1.3f
