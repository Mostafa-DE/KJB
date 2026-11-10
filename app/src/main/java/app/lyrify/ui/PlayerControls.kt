package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.input.pointer.pointerInput
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
import app.lyrify.library.LibraryGraph
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerUi
import app.lyrify.player.Repeat
import app.lyrify.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The player's controls near the thumb (P1): the progress bar, the main row (seek back, Previous, Play/Pause, Next,
// seek forward) and the secondary row (Shuffle, the heart, the sleep timer, Queue, Repeat). PlayerScreen.kt lays them out.

/** The progress bar: a 6 dp track in a 48 dp touch area, a tap or a drag seeks (on release), and the times under it. */
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
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = (offset.x / size.width).coerceIn(0f, 1f) },
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
            Box(Modifier.offset(x = maxWidth * share - 8.dp).size(if (dragging != null) 20.dp else 16.dp).clip(CircleShape).background(highlight))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.bodySmall.tabular()
        Text(clock(dragging?.let { (it * duration).toLong() } ?: position), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(clock(duration), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Back N s, Previous, Play/Pause, Next, Forward N s: the seek buttons smaller, showing the chosen amounts. */
@Composable
internal fun MainRow(ui: PlayerUi, settings: Settings, connection: PlayerConnection) {
    val back = settings.playback.skipBackSeconds
    val forward = settings.playback.skipForwardSeconds
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SeekJump(seekIcon(back = true, back), pluralStringResource(R.plurals.player_back_seconds, back, back)) { connection.seekBy(-back * 1_000L) }
        RoundButton(R.drawable.ic_skip_previous_fill, stringResource(R.string.player_previous), size = 56.dp, iconSize = 38.dp, onClick = connection::previous)
        val colors = MaterialTheme.colorScheme
        val label = stringResource(if (ui.playing) R.string.player_pause else R.string.player_play)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(colors.inverseSurface, CircleShape)
                .clickable(role = Role.Button, onClick = connection::playPause)
                .semantics { contentDescription = label },
        ) {
            Icon(
                painterResource(if (ui.playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill),
                contentDescription = null,
                tint = colors.inverseOnSurface,
                modifier = Modifier.size(40.dp),
            )
        }
        RoundButton(R.drawable.ic_skip_next_fill, stringResource(R.string.player_next), size = 56.dp, iconSize = 38.dp, onClick = connection::next)
        SeekJump(seekIcon(back = false, forward), pluralStringResource(R.plurals.player_forward_seconds, forward, forward)) { connection.seekBy(forward * 1_000L) }
    }
}

/** A seek button: a press jumps once; held, the jump repeats ([jumpsWhileHeld]). TalkBack's tap jumps once. */
@Composable
private fun SeekJump(@DrawableRes icon: Int, label: String, jump: () -> Unit) {
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
                    awaitFirstDown()
                    latest()
                    val repeat = scope.launch {
                        delay(HOLD_DELAY_MS)
                        while (true) {
                            latest()
                            delay(HOLD_EVERY_MS)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                }
            },
    ) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(27.dp)) }
}

/** Shuffle, the heart, the sleep timer (P2), Queue and Repeat. Toggles that are on show in the highlight colour, with a bar. */
@Composable
internal fun SecondaryRow(ui: PlayerUi, connection: PlayerConnection, onQueue: () -> Unit, onRoute: (PlayerRoute) -> Unit) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val favourites = remember(context) { LibraryGraph.favourites(context) }
    val favouriteSongs by favourites.songs.collectAsStateWithLifecycle(initialValue = emptyList())
    val favourite = favouriteSongs.any { it.path == ui.item.song.path }
    val shuffled = ui.queue.shuffled
    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        ToggleButton(R.drawable.ic_shuffle, stringResource(if (shuffled) R.string.player_shuffle_on else R.string.player_shuffle_off), on = shuffled) {
            val on = connection.toggleShuffle()
            scope.launch { toasts.show(Toast(resources.getString(if (on) R.string.player_shuffled else R.string.player_unshuffled), R.drawable.ic_shuffle)) }
        }
        ToggleButton(if (favourite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite, stringResource(if (favourite) R.string.player_favourite_on else R.string.player_favourite_off), on = favourite, bar = false) {
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
        // The sleep timer (P2): its button, or its chip while it runs.
        SleepButton(ui) { onRoute(PlayerRoute.SLEEP) }
        RoundButton(R.drawable.ic_queue_music, stringResource(R.string.player_queue), onClick = onQueue)
        val repeat = ui.repeat
        ToggleButton(if (repeat == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat, stringResource(repeatLabel(repeat)), on = repeat != Repeat.OFF) {
            val next = connection.cycleRepeat()
            scope.launch { toasts.show(Toast(resources.getString(repeatLabel(next)), if (next == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)) }
        }
    }
}

private fun repeatLabel(repeat: Repeat): Int = when (repeat) {
    Repeat.OFF -> R.string.player_repeat_off
    Repeat.ALL -> R.string.player_repeat_all
    Repeat.ONE -> R.string.player_repeat_one
}

@Composable
private fun RoundButton(@DrawableRes icon: Int, label: String, size: Dp = TOUCH, iconSize: Dp = 26.dp, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(iconSize)) }
}

@Composable
private fun ToggleButton(@DrawableRes icon: Int, label: String, on: Boolean, bar: Boolean = true, onClick: () -> Unit) {
    val highlight = LocalBrandColors.current.highlight
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(TOUCH).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (on) highlight else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
        if (on && bar) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(14.dp, 3.dp).clip(CircleShape).background(highlight))
    }
}

