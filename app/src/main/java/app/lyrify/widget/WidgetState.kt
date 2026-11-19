package app.lyrify.widget

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.media3.common.Player
import app.lyrify.R
import app.lyrify.books.skipsFor
import app.lyrify.library.Library
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.player.QueueBook
import app.lyrify.player.openedCoverKey
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlaybackSettings
import app.lyrify.ui.DESTINATION_PLAYER
import app.lyrify.ui.seekIcon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

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
        /** Its cover's version in this process ([app.lyrify.tags.CoverVersions]): a cover Covers & tags changed is read again. */
        val coverVersion: Int = 0,
        /** Its album, for the large layout; null when the song has none, or is not on the phone. */
        val album: String? = null,
        /** The id of the queue playing, which the Queue button opens. */
        val queue: String? = null,
        /** The seek buttons' jumps back and forward, in seconds: the player's own ([skipsFor]). */
        val backSeconds: Int = 10,
        val forwardSeconds: Int = 10,
    ) : WidgetState {
        /** What its cover is read for ([coverToRead]): its [cover], and its [coverVersion] once that is not 0. */
        val coverKey: String? get() = cover?.let { if (coverVersion == 0) it else "$it#$coverVersion" }
    }
}

/**
 * The widgets' state from the player's: the current song of the queue playing as the library has it ("Show file names"
 * applies; a song not on the phone shows its file's name, and a file another app opened its own cover), its cover's
 * version among the covers' [versions] by path, whether the player plays, whether that song is a favourite, the queue,
 * and the seek buttons' jumps as the player's ("Skip amounts" in [playback], 30 s in a book). Nothing is loaded without a
 * queue, or before the library is read (the player waits for it too).
 */
fun widgetState(
    book: QueueBook,
    library: Library?,
    playing: Boolean,
    favourites: List<SongRef>,
    settings: LibrarySettings,
    versions: Map<String, Int> = emptyMap(),
    playback: PlaybackSettings = PlaybackSettings(),
): WidgetState {
    val queue = book.current
    val ref = queue?.current?.song ?: return WidgetState.Empty
    library ?: return WidgetState.Empty
    val song = library.resolver.find(ref)
    val skips = skipsFor(queue, playback)
    return WidgetState.Loaded(
        song = ref,
        title = song?.let { displayTitle(it, settings) } ?: ref.path.substringAfterLast('/'),
        artist = song?.artist,
        cover = song?.ref?.mediaStoreId?.toString() ?: openedCoverKey(ref.path),
        playing = playing,
        favourite = favourites.any { it.path == ref.path },
        coverVersion = song?.let { versions[it.path] } ?: 0,
        album = song?.album?.takeIf { it.isNotBlank() },
        queue = queue.id,
        backSeconds = (skips.backMs / 1_000).toInt(),
        forwardSeconds = (skips.forwardMs / 1_000).toInt(),
    )
}

/**
 * The states to push to the widgets: one each time the song, the play state or the favourite changes (or what the song
 * shows, after a tag edit, "Show file names" or a new cover from Covers & tags, or the seek buttons' jumps), never for the
 * place moving, other queues, another song's cover, other settings, or a library read that changes nothing here. There
 * is no progress bar, so the widgets are not updated while a song plays on.
 */
fun widgetStates(
    book: Flow<QueueBook>,
    library: Flow<Library?>,
    playing: Flow<Boolean>,
    favourites: Flow<List<SongRef>>,
    settings: Flow<LibrarySettings>,
    versions: Flow<Map<String, Int>> = flowOf(emptyMap()),
    playback: Flow<PlaybackSettings> = flowOf(PlaybackSettings()),
): Flow<WidgetState> = combine(book, library, playing, favourites, combine(settings, versions, playback, ::Triple)) { queues, songs, on, hearts, (shown, covers, jumps) ->
    widgetState(queues, songs, on, hearts, shown, covers, jumps)
}.distinctUntilChanged()

/**
 * The Glow widget's layouts (the design the user chose, handoff/preview-widgets.html), by the widget's height: "KJB" and
 * "KJB large" both draw the one their size calls for ([glowLayout]). [fromHeightDp] is the least height it is drawn at,
 * which Glance is given as its size (SizeMode.Responsive); [coverDp] and [coverCornerDp] the sharp cover's slot, and
 * [paddingDp] the margin around it all, as in the preview. [shapeHeightDp] is the height its background is made for, a
 * widget of that layout on a common phone ([GLOW_SHAPE_WIDTH_DP] wide).
 */
enum class GlowLayout(val fromHeightDp: Int, val coverDp: Int, val coverCornerDp: Int, val paddingDp: Int, val shapeHeightDp: Int) {
    /** 4×1: the cover, title and artist, Previous, Play or Pause, Next. */
    SMALL(fromHeightDp = 40, coverDp = 56, coverCornerDp = 14, paddingDp = 12, shapeHeightDp = 96),

    /** 4×2: the cover, title and artist, the heart and Queue, then back, Previous, Play or Pause, Next, forward. */
    MEDIUM(fromHeightDp = 170, coverDp = 76, coverCornerDp = 14, paddingDp = 14, shapeHeightDp = 220),

    /** 4×3: the medium's, with a bigger cover and the album. */
    LARGE(fromHeightDp = 290, coverDp = 128, coverCornerDp = 18, paddingDp = 16, shapeHeightDp = 340),
}

/**
 * The layout a widget [heightDp] tall draws: the largest whose least height it reaches, small below them all. One row of
 * cells is about 80 to 140 dp tall on phones, two about 200 to 280, three 300 and more.
 */
fun glowLayout(heightDp: Float): GlowLayout = GlowLayout.entries.lastOrNull { heightDp >= it.fromHeightDp } ?: GlowLayout.SMALL

/** The least width Glance is given for every layout: no 4-cell widget is narrower, so the height alone picks the layout. */
const val GLOW_FROM_WIDTH_DP: Int = 200

/** Play or Pause, the widget's one filled button, in dp; the other buttons are [GLOW_BUTTON_DP]. */
const val GLOW_PLAY_DP: Int = 46
const val GLOW_BUTTON_DP: Int = 40

/** What a widget button asks of the session, sent through a MediaController (never a broadcast). */
enum class WidgetCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,

    /** The jump back of the player's seek button: the session's seek back, by "Skip amounts" (30 s in a book). */
    REWIND,

    /** The jump forward, the same way. */
    FORWARD;

    /** This command on [player], the session's player through a MediaController; Play on nothing loaded resumes the last queue. */
    fun sendTo(player: Player) = when (this) {
        PLAY -> player.play()
        PAUSE -> player.pause()
        NEXT -> player.seekToNext()
        PREVIOUS -> player.seekToPrevious()
        REWIND -> player.seekBack()
        FORWARD -> player.seekForward()
    }

    /**
     * Whether the session's player shows it took this command: Play once it plays (the service is in the foreground by
     * then), Pause by its play-when-ready change, Next and Previous by a new song or a jump, the seek jumps by a jump.
     * [happened] tells whether an event of the player's (Player.EVENT_*) just happened.
     */
    fun took(playing: Boolean, happened: (Int) -> Boolean): Boolean = when (this) {
        PLAY -> playing
        PAUSE -> happened(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        NEXT, PREVIOUS -> happened(Player.EVENT_MEDIA_ITEM_TRANSITION) || happened(Player.EVENT_POSITION_DISCONTINUITY)
        REWIND, FORWARD -> happened(Player.EVENT_POSITION_DISCONTINUITY)
    }

    companion object {
        /** The command a button's action carries, by its name; null for none this version knows. */
        fun of(name: String?): WidgetCommand? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A transport button on a widget: what it sends, its icon and its label for TalkBack. The seek jumps' [label] is a plurals
 * resource read with their [seconds], as the player's seek buttons say it ("Back 30 seconds"); null for the others.
 */
data class WidgetButton(val command: WidgetCommand, @DrawableRes val icon: Int, @StringRes @PluralsRes val label: Int, val seconds: Int? = null)

/**
 * The transport buttons [layout] shows for [state], left to right: Previous, Play or Pause and Next, with the jumps back
 * and forward on either side in the medium and large layouts, their icons showing the seconds as the player's do; only
 * Play when nothing is loaded. The heart and Queue are apart from these: they do not go to the session.
 */
fun widgetButtons(state: WidgetState, layout: GlowLayout): List<WidgetButton> {
    val play = WidgetButton(WidgetCommand.PLAY, R.drawable.ic_play_arrow_fill, R.string.player_play)
    if (state !is WidgetState.Loaded) return listOf(play)
    val toggle = if (state.playing) WidgetButton(WidgetCommand.PAUSE, R.drawable.ic_pause_fill, R.string.player_pause) else play
    val skips = listOf(
        WidgetButton(WidgetCommand.PREVIOUS, R.drawable.ic_skip_previous_fill, R.string.player_previous),
        toggle,
        WidgetButton(WidgetCommand.NEXT, R.drawable.ic_skip_next_fill, R.string.player_next),
    )
    if (layout == GlowLayout.SMALL) return skips
    val back = WidgetButton(WidgetCommand.REWIND, seekIcon(back = true, state.backSeconds), R.plurals.player_back_seconds, state.backSeconds)
    val forward = WidgetButton(WidgetCommand.FORWARD, seekIcon(back = false, state.forwardSeconds), R.plurals.player_forward_seconds, state.forwardSeconds)
    return listOf(back) + skips + forward
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
 * The path of [state]'s cover to read now, given the cover one was last read for ([readFor]: its
 * [WidgetState.Loaded.coverKey], so a cover Covers & tags changed is read again) and, when that read found no art, when
 * it did ([noArtAt], on [now]'s clock); null when there is nothing to read. A read that failed records no cover, so the
 * next update tries again; no art is read once more from [NO_ART_RECHECK_MS] after.
 */
fun coverToRead(state: WidgetState, readFor: String?, noArtAt: Long?, now: Long): String? {
    val loaded = state as? WidgetState.Loaded ?: return null
    val path = loaded.cover ?: return null
    val recheck = noArtAt != null && now - noArtAt >= NO_ART_RECHECK_MS
    return path.takeIf { loaded.coverKey != readFor || recheck }
}

/**
 * What the widgets draw: [state] while the player's session lives in this process, else the empty state. A process
 * that was killed and started again (for a widget update) has no player, whatever the widgets showed before.
 */
fun onScreen(state: WidgetState, playerAlive: Boolean): WidgetState = if (playerAlive) state else WidgetState.Empty
