package app.lyrify.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.media3.common.Player
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.player.QueueBook
import app.lyrify.player.openedCoverKey
import app.lyrify.settings.LibrarySettings
import app.lyrify.ui.DESTINATION_PLAYER
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * What the home-screen widgets show. The playback service pushes it while it runs ([WidgetPart]), and it is kept in
 * memory only, so a phone that restarted, or a KJB that was closed, shows [Empty].
 */
sealed interface WidgetState {
    /** Nothing loaded: "KJB" with Play, which resumes the last queue. */
    data object Empty : WidgetState

    /** The song the player holds, playing or paused. */
    data class Loaded(
        val song: SongRef,
        val title: String,
        /** Null when the song has none, or is not on the phone. */
        val artist: String?,
        /**
         * The path of its cover at KJB's cover provider ([app.lyrify.auto.coverPath]): its MediaStore id, or the key of a
         * file another app opened; null when it is neither (KJB's default cover is drawn, and nothing read).
         */
        val cover: String?,
        val playing: Boolean,
        val favourite: Boolean,
    ) : WidgetState
}

/**
 * The widgets' state from the player's: the current song of the queue playing as the library has it ("Show file names"
 * applies; a song not on the phone shows its file's name, and a file another app opened its own cover), whether the
 * player plays, and whether that song is a favourite. Nothing is loaded without a queue, or before the library is read
 * (the player waits for it too).
 */
fun widgetState(book: QueueBook, library: Library?, playing: Boolean, favourites: List<SongRef>, settings: LibrarySettings): WidgetState {
    val ref = book.current?.current?.song ?: return WidgetState.Empty
    library ?: return WidgetState.Empty
    val song = library.resolver.find(ref)
    return WidgetState.Loaded(
        song = ref,
        title = song?.let { displayTitle(it, settings) } ?: ref.path.substringAfterLast('/'),
        artist = song?.artist,
        cover = song?.ref?.mediaStoreId?.toString() ?: openedCoverKey(ref.path),
        playing = playing,
        favourite = favourites.any { it.path == ref.path },
    )
}

/**
 * The states to push to the widgets: one each time the song, the play state or the favourite changes (or what the song
 * shows, after a tag edit or "Show file names"), never for the place moving, other queues, or a library read that
 * changes nothing here. There is no progress bar, so the widgets are not updated while a song plays on.
 */
fun widgetStates(
    book: Flow<QueueBook>,
    library: Flow<Library?>,
    playing: Flow<Boolean>,
    favourites: Flow<List<SongRef>>,
    settings: Flow<LibrarySettings>,
): Flow<WidgetState> = combine(book, library, playing, favourites, settings, ::widgetState).distinctUntilChanged()

/** The two widgets: "KJB", 4×1, and "KJB large", 4×2, with the width of their cover's slot. */
enum class WidgetSize(val coverDp: Int) {
    SMALL(44),
    LARGE(88),
}

/** What a widget button asks of the session, sent through a MediaController (never a broadcast). */
enum class WidgetCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS;

    /** This command on [player], the session's player through a MediaController; Play on nothing loaded resumes the last queue. */
    fun sendTo(player: Player) = when (this) {
        PLAY -> player.play()
        PAUSE -> player.pause()
        NEXT -> player.seekToNext()
        PREVIOUS -> player.seekToPrevious()
    }

    /**
     * Whether the session's player shows it took this command: Play once it plays (the service is in the foreground by
     * then), Pause by its play-when-ready change, Next and Previous by a new song or a jump. [happened] tells whether an
     * event of the player's (Player.EVENT_*) just happened.
     */
    fun took(playing: Boolean, happened: (Int) -> Boolean): Boolean = when (this) {
        PLAY -> playing
        PAUSE -> happened(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        NEXT, PREVIOUS -> happened(Player.EVENT_MEDIA_ITEM_TRANSITION) || happened(Player.EVENT_POSITION_DISCONTINUITY)
    }

    companion object {
        /** The command a button's action carries, by its name; null for none this version knows. */
        fun of(name: String?): WidgetCommand? = entries.firstOrNull { it.name == name }
    }
}

/** A transport button on a widget: what it sends, its icon and its label. */
data class WidgetButton(val command: WidgetCommand, @DrawableRes val icon: Int, @StringRes val label: Int)

/**
 * The transport buttons [size] shows for [state], left to right: Play or Pause and Next, with Previous first on the large
 * widget; only Play when nothing is loaded. The large widget's heart is apart from these: it goes to Favourites.
 */
fun widgetButtons(state: WidgetState, size: WidgetSize): List<WidgetButton> {
    val play = WidgetButton(WidgetCommand.PLAY, R.drawable.ic_play_arrow_fill, R.string.player_play)
    if (state !is WidgetState.Loaded) return listOf(play)
    val toggle = if (state.playing) WidgetButton(WidgetCommand.PAUSE, R.drawable.ic_pause_fill, R.string.player_pause) else play
    val next = WidgetButton(WidgetCommand.NEXT, R.drawable.ic_skip_next_fill, R.string.player_next)
    return when (size) {
        WidgetSize.SMALL -> listOf(toggle, next)
        WidgetSize.LARGE -> listOf(WidgetButton(WidgetCommand.PREVIOUS, R.drawable.ic_skip_previous_fill, R.string.player_previous), toggle, next)
    }
}

/** Where a tap on the cover opens KJB: the player, also from the empty state (it closes when nothing is loaded). */
@Suppress("UNUSED_PARAMETER")
fun coverDestination(state: WidgetState): String = DESTINATION_PLAYER

/**
 * How long the widgets keep "no art" for a song before they read its cover once more ([coverToRead]). MediaStore says
 * "no art" (FileNotFoundException) for a song without embedded art, but also for some failures of a moment, so a song
 * with art may be given the default cover: one read a minute on mends that, while a song that has none is read twice in
 * all, never at every update. No timer: the read comes with the first update from then on (Play, Pause, the heart, the
 * widget drawn again), and a new process reads every cover afresh, as the widgets' state is in memory only.
 */
const val NO_ART_RECHECK_MS: Long = 60_000

/**
 * The path of [state]'s cover to read now, given the path a cover was last read for ([readFor]) and, when that read
 * found no art, when it did ([noArtAt], on [now]'s clock); null when there is nothing to read. A read that failed
 * records no path, so the next update tries again; no art is read once more from [NO_ART_RECHECK_MS] after.
 */
fun coverToRead(state: WidgetState, readFor: String?, noArtAt: Long?, now: Long): String? {
    val path = (state as? WidgetState.Loaded)?.cover ?: return null
    val recheck = noArtAt != null && now - noArtAt >= NO_ART_RECHECK_MS
    return path.takeIf { it != readFor || recheck }
}

/** The cover's size in pixels for [size]'s slot on a screen of [density]. */
fun coverPx(size: WidgetSize, density: Float): Int = (size.coverDp * density).roundToInt()

/**
 * What the widgets draw: [state] while the player's session lives in this process, else the empty state. A process
 * that was killed and started again (for a widget update) has no player, whatever the widgets showed before.
 */
fun onScreen(state: WidgetState, playerAlive: Boolean): WidgetState = if (playerAlive) state else WidgetState.Empty
