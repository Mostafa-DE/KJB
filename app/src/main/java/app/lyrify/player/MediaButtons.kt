package app.lyrify.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.Player
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
import app.lyrify.books.inBook
import app.lyrify.library.FavouritesPort
import app.lyrify.library.LibraryGraph
import app.lyrify.library.SongRef
import app.lyrify.settings.NotificationButtons
import app.lyrify.settings.Settings
import app.lyrify.ui.heartLabel
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

/**
 * In a book, a headset's two presses go forward 30 s and three go back ([Press.NEXT] true, [Press.PREVIOUS] false); null
 * for a press that does what it always does.
 */
fun bookPress(press: Press): Boolean? = when (press) {
    Press.NEXT -> true
    Press.PREVIOUS -> false
    Press.PLAY_PAUSE -> null
}

/**
 * In a book, a car's or a Bluetooth headset's Next and Fast forward keys go forward 30 s (true), Previous and Rewind go
 * back (false); null for any other key.
 */
fun bookSeek(keyCode: Int): Boolean? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> false
    else -> null
}

/** A button of a book's notification, lock screen and car: a player command, its icon (a CommandButton icon), its name, its slot. */
data class BookButton(val command: Int, val icon: Int, @StringRes val label: Int, val slot: Int)

/** A book's buttons: back 30 s where Previous is, forward 30 s where Next is (Media3 picks the 30 s icons by the jumps). */
@OptIn(UnstableApi::class)
fun bookButtons(): List<BookButton> = listOf(
    BookButton(Player.COMMAND_SEEK_BACK, CommandButton.ICON_SKIP_BACK_30, R.string.book_back_30, CommandButton.SLOT_BACK),
    BookButton(Player.COMMAND_SEEK_FORWARD, CommandButton.ICON_SKIP_FORWARD_30, R.string.book_forward_30, CommandButton.SLOT_FORWARD),
)

/** One extra button of the music notification: its session command, its icon (a CommandButton icon) and its name. */
data class NotificationButton(val command: String, val icon: Int, @StringRes val label: Int)

/** Whether a book's session takes the custom command [action]: never Shuffle or Repeat, as a book does neither. */
fun bookAllows(action: String): Boolean = action != COMMAND_SHUFFLE && action != COMMAND_REPEAT

/** The custom commands of the extra buttons. */
const val COMMAND_FAVOURITE: String = "app.lyrify.player.FAVOURITE"
const val COMMAND_SHUFFLE: String = "app.lyrify.player.SHUFFLE"
const val COMMAND_REPEAT: String = "app.lyrify.player.REPEAT"

/**
 * The notification's extra buttons beside Next ("Notification buttons"): the heart for the song playing, or Shuffle
 * and Repeat as they are now, none of those two in a [book]. The same buttons draw Android 11 and 12's own notification.
 */
@OptIn(UnstableApi::class)
fun notificationButtons(choice: NotificationButtons, favourite: Boolean, shuffled: Boolean, repeat: Repeat, book: Boolean = false): List<NotificationButton> = when {
    choice == NotificationButtons.SHUFFLE_REPEAT && book -> emptyList()
    choice == NotificationButtons.FAVOURITE -> listOf(
        if (favourite) {
            NotificationButton(COMMAND_FAVOURITE, CommandButton.ICON_HEART_FILLED, heartLabel(on = true))
        } else {
            NotificationButton(COMMAND_FAVOURITE, CommandButton.ICON_HEART_UNFILLED, heartLabel(on = false))
        },
    )
    else -> listOf(
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
 * Headset presses on a session's player: media button events through the [PressCounter] while [enabled] ("Headset
 * buttons"). With nothing loaded (KJB was closed) a press goes to the session, which resumes the last queue; the
 * notification's own taps on Android 11 and 12 are not presses and go to the session too. In a [book], skipping goes
 * 30 s instead ([bookPress], [bookSeek]): a book's session offers no Next or Previous. Main thread.
 */
@OptIn(UnstableApi::class)
class HeadsetButtons(scope: CoroutineScope, private val book: () -> Boolean = { false }, private val enabled: () -> Boolean) {
    private var player: Player? = null
    private val counter = PressCounter(scope) { press ->
        val on = player ?: return@PressCounter
        val seek = if (book()) bookPress(press) else null
        when {
            seek != null -> if (seek) on.seekForward() else on.seekBack()
            press == Press.PLAY_PAUSE -> Util.handlePlayPauseButtonAction(on)
            press == Press.NEXT -> on.seekToNext()
            else -> on.seekToPrevious()
        }
    }

    /** [MediaSession.Callback.onMediaButtonEvent]: true when the press was taken. */
    fun onMediaButton(session: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean {
        if (session.isMediaNotificationController(controller)) return false
        val event = keyEvent(intent) ?: return false
        val enabled = enabled()
        if (enabled && session.player.mediaItemCount == 0) return false
        player = session.player
        val seek = if (book()) bookSeek(event.keyCode) else null
        if (seek != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (seek) session.player.seekForward() else session.player.seekBack()
            }
            return true
        }
        return counter.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount, enabled)
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
}

/** The headset buttons ([HeadsetButtons]) and the notification's extra buttons, which follow the setting, the song playing, Shuffle and Repeat. */
@OptIn(UnstableApi::class)
class ButtonsPart : ServicePart {
    override val commands: List<SessionCommand> = listOf(COMMAND_FAVOURITE, COMMAND_SHUFFLE, COMMAND_REPEAT).map { SessionCommand(it, Bundle.EMPTY) }

    private var scope: CoroutineScope? = null
    private var headset: HeadsetButtons? = null
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
        headset = HeadsetButtons(scope, enabled = { settings.value.playback.headsetButtons }, book = { inBook(core.book.value.current) })
        scope.launch {
            combine(settings.map { it.advanced.notificationButtons }.distinctUntilChanged(), favouriteSongs, core.book, core.repeat) { choice, favourite, book, repeat ->
                val path = book.current?.current?.song?.path
                val bookMode = inBook(book.current)
                notificationButtons(choice, favourite = path != null && favourite.any { it.path == path }, shuffled = book.current?.shuffled == true, repeat = repeat, book = bookMode) to bookMode
            }.distinctUntilChanged().collect { (buttons, book) ->
                // A book: 30 s back and forward where Previous and Next are, for the notification, the lock screen and the car.
                val seeks = if (book) {
                    bookButtons().map { button ->
                        CommandButton.Builder(button.icon).setDisplayName(service.getString(button.label)).setPlayerCommand(button.command).setSlots(button.slot).build()
                    }
                } else {
                    emptyList()
                }
                session.setMediaButtonPreferences(
                    seeks + buttons.map { button ->
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
        headset = null
    }

    override fun onCommand(command: SessionCommand, args: Bundle): SessionResult? {
        val core = core ?: return null
        // A book never shuffles or repeats: an old notification's button or another app's command changes nothing.
        if (inBook(core.book.value.current) && !bookAllows(command.customAction)) return SessionResult(SessionError.ERROR_NOT_SUPPORTED)
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

    override fun onMediaButton(session: MediaSession, controller: MediaSession.ControllerInfo, intent: Intent): Boolean =
        headset?.onMediaButton(session, controller, intent) ?: false
}
