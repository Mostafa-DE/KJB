package app.lyrify.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.FavouritesPort
import app.lyrify.library.LibraryGraph
import app.lyrify.library.SongRef
import app.lyrify.settings.NotificationButtons
import app.lyrify.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/**
 * The headset buttons and the notification's extra buttons in the service. Presses from headsets and Bluetooth go
 * through the [PressCounter] ("Headset buttons"); with nothing loaded (KJB was closed) they go to the session, which
 * resumes the last queue. The notification's own taps on Android 11 and 12 are not presses and go to the session too.
 * The extra buttons follow the setting, the song playing, Shuffle and Repeat.
 */
@OptIn(UnstableApi::class)
class ButtonsPart : ServicePart {
    override val commands: List<SessionCommand> = listOf(COMMAND_FAVOURITE, COMMAND_SHUFFLE, COMMAND_REPEAT).map { SessionCommand(it, Bundle.EMPTY) }

    private var scope: CoroutineScope? = null
    private var counter: PressCounter? = null
    private var core: PlayerCore? = null
    private var favourites: FavouritesPort? = null
    private var favouriteSongs: StateFlow<List<SongRef>> = MutableStateFlow(emptyList())
    private var settings: StateFlow<Settings> = MutableStateFlow(Settings())

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val scope = partScope().also { scope = it }
        val core = PlayerGraph.core(service).also { core = it }
        val favourites = LibraryGraph.favourites(service).also { favourites = it }
        settings = Lyrify.settings(service).settings.stateIn(scope, SharingStarted.Eagerly, Settings())
        favouriteSongs = favourites.songs.stateIn(scope, SharingStarted.Eagerly, emptyList())
        counter = PressCounter(scope) { press ->
            val player = session.player
            when (press) {
                Press.PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
                Press.NEXT -> player.seekToNext()
                Press.PREVIOUS -> player.seekToPrevious()
            }
        }
        scope.launch {
            combine(settings.map { it.advanced.notificationButtons }.distinctUntilChanged(), favouriteSongs, core.book, core.repeat) { choice, favourite, book, repeat ->
                val path = book.current?.current?.song?.path
                notificationButtons(choice, favourite = path != null && favourite.any { it.path == path }, shuffled = book.current?.shuffled == true, repeat = repeat)
            }.distinctUntilChanged().collect { buttons ->
                session.setMediaButtonPreferences(
                    buttons.map { button ->
                        CommandButton.Builder(button.icon)
                            .setDisplayName(service.getString(button.label))
                            .setSessionCommand(SessionCommand(button.command, Bundle.EMPTY))
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build()
                    },
                )
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        counter = null
    }

    override fun onCommand(command: SessionCommand, args: Bundle): SessionResult? {
        val core = core ?: return null
        when (command.customAction) {
            COMMAND_FAVOURITE -> {
                val song = core.book.value.current?.current?.song ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
                val favourite = favouriteSongs.value.any { it.path == song.path }
                scope?.launch { favourites?.setSongs(listOf(song), !favourite) }
            }
            COMMAND_SHUFFLE -> core.change { it.toggleShuffle(core.shuffleRandom()) }
            COMMAND_REPEAT -> core.setRepeat(Repeat.entries[(core.repeat.value.ordinal + 1) % Repeat.entries.size])
            else -> return null
        }
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    override fun onMediaButton(session: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean {
        if (session.isMediaNotificationController(controller)) return false
        val event = keyEvent(intent) ?: return false
        val enabled = settings.value.playback.headsetButtons
        if (enabled && session.player.mediaItemCount == 0) return false
        return counter?.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, enabled) ?: false
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
}
