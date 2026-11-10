package app.lyrify.player

import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import app.lyrify.R
import app.lyrify.settings.NotificationButtons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Headset and car buttons, and the notification's extra buttons (milestone P2).

/** What headset presses do: one plays or pauses, two skip, three go back. */
enum class Press { PLAY_PAUSE, NEXT, PREVIOUS }

/** [count] presses in a row. */
fun pressOf(count: Int): Press = when {
    count <= 1 -> Press.PLAY_PAUSE
    count == 2 -> Press.NEXT
    else -> Press.PREVIOUS
}

/** The longest wait between two presses of one row. */
const val PRESS_WINDOW_MS: Long = 400

/**
 * Counts headset presses: the hook button and Play/Pause (a headset's one button sends either), each press within
 * [windowMs] of the one before. Once the row ends, [act] gets what it means. Any other key (a car's or a Bluetooth
 * headset's Next and Previous) is not counted and goes on as it is; a row waiting is acted on first. Main thread.
 */
class PressCounter(private val scope: CoroutineScope, private val windowMs: Long = PRESS_WINDOW_MS, private val act: (Press) -> Unit) {
    private var count = 0
    private var waiting: Job? = null

    /**
     * A key event: [down] or up, [repeat] while held. True when KJB takes it (a counted key; while [enabled] is false,
     * "Headset buttons" off, it is taken and does nothing); false to let the session handle it.
     */
    fun onKey(keyCode: Int, down: Boolean, repeat: Int, enabled: Boolean = true): Boolean {
        if (keyCode != KeyEvent.KEYCODE_HEADSETHOOK && keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (down) flush()
            return false
        }
        if (!enabled || !down || repeat > 0) return true
        count++
        waiting?.cancel()
        waiting = scope.launch {
            delay(windowMs)
            flush()
        }
        return true
    }

    /** The row waiting, acted on now. */
    private fun flush() {
        waiting?.cancel()
        waiting = null
        val presses = count
        count = 0
        if (presses > 0) act(pressOf(presses))
    }
}

/** One extra button of the music notification: its session command, its icon (a CommandButton icon) and its name. */
data class NotificationButton(val command: String, val icon: Int, @StringRes val label: Int)

/** The custom commands of the extra buttons. */
const val COMMAND_FAVOURITE: String = "app.lyrify.player.FAVOURITE"
const val COMMAND_SHUFFLE: String = "app.lyrify.player.SHUFFLE"
const val COMMAND_REPEAT: String = "app.lyrify.player.REPEAT"

/**
 * The notification's extra buttons beside Next ("Notification buttons"): the heart for the song playing, or Shuffle
 * and Repeat as they are now. The same buttons draw Android 11 and 12's own notification.
 */
@OptIn(UnstableApi::class)
fun notificationButtons(choice: NotificationButtons, favourite: Boolean, shuffled: Boolean, repeat: Repeat): List<NotificationButton> = when (choice) {
    NotificationButtons.FAVOURITE -> listOf(
        if (favourite) {
            NotificationButton(COMMAND_FAVOURITE, CommandButton.ICON_HEART_FILLED, R.string.player_favourite_on)
        } else {
            NotificationButton(COMMAND_FAVOURITE, CommandButton.ICON_HEART_UNFILLED, R.string.player_favourite_off)
        },
    )
    NotificationButtons.SHUFFLE_REPEAT -> listOf(
        if (shuffled) {
            NotificationButton(COMMAND_SHUFFLE, CommandButton.ICON_SHUFFLE_ON, R.string.player_shuffle_on)
        } else {
            NotificationButton(COMMAND_SHUFFLE, CommandButton.ICON_SHUFFLE_OFF, R.string.player_shuffle_off)
        },
        when (repeat) {
            Repeat.OFF -> NotificationButton(COMMAND_REPEAT, CommandButton.ICON_REPEAT_OFF, R.string.player_repeat_off)
            Repeat.ALL -> NotificationButton(COMMAND_REPEAT, CommandButton.ICON_REPEAT_ALL, R.string.player_repeat_all)
            Repeat.ONE -> NotificationButton(COMMAND_REPEAT, CommandButton.ICON_REPEAT_ONE, R.string.player_repeat_one)
        },
    )
}
