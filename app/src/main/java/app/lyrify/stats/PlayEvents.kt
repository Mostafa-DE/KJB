package app.lyrify.stats

import app.lyrify.audio.OutputKind
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.lyrics.TrackNames
import app.lyrify.player.SourceKind
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * What KJB's own player tells the play counter, as it happens: player/ sends them (milestone P1), raw, and the counter
 * (stats/PlayRules.kt) works out the time heard and when a play counts ("Count a play after"). Songs played in other
 * apps send none. [playId] ties the events of one play together; [at] is wall-clock millis; positions are in the song,
 * in millis.
 */
sealed interface PlayEvent {
    val playId: Long
    val song: SongRef
    val at: Long

    /**
     * A play begins: a song starts, or starts again (each lap of Repeat one is a new play). [durationMs]: the song's length.
     * [start]: how it began and from where, as the player knew it; null where it does not say (tests).
     */
    data class Started(override val playId: Long, override val song: SongRef, override val at: Long, val durationMs: Long, val start: PlayStart? = null) : PlayEvent

    /**
     * The player is at [positionMs], and [playing] (sound coming out, not paused or buffering) or not. Sent when the play
     * starts, whenever playing starts or stops, and every few seconds while it plays, so a play counts as soon as its
     * share is heard.
     */
    data class Position(override val playId: Long, override val song: SongRef, override val at: Long, val positionMs: Long, val playing: Boolean) : PlayEvent

    /** The position jumped from [fromMs] to [toMs] (a seek); the part jumped over is not heard. */
    data class Seeked(override val playId: Long, override val song: SongRef, override val at: Long, val fromMs: Long, val toMs: Long) : PlayEvent

    /**
     * The play is over at [positionMs]: the song ended (its [Started.durationMs]), was skipped or replaced, or playback
     * stopped. [how] it ended, and where the song was left ([leftAtMs]): [positionMs], but for a song that crossfaded
     * out, where its fade began, while it counts as heard up to its end.
     */
    data class Ended(
        override val playId: Long,
        override val song: SongRef,
        override val at: Long,
        val positionMs: Long,
        val how: PlayEnd = PlayEnd.UNKNOWN,
        val leftAtMs: Long = positionMs,
    ) : PlayEvent
}

/**
 * How a play ended, as `plays.db` and backups keep it: by its name in lower case ("finished"), so an entry is never
 * renamed. A value a newer KJB wrote and this one does not know reads as [UNKNOWN].
 */
enum class PlayEnd {
    /**
     * The song played to its end by itself: the next one came on, Repeat one went round, or the queue ended. With
     * Crossfade on, the song going out ends as its fade begins, and is left there ([PlayEvent.Ended.leftAtMs]).
     */
    FINISHED,

    /** Next: a button, a swipe, the notification, a headset or the car. */
    NEXT,

    /** The user picked another song or started another list: a tap in a list or a queue, Shuffle, Resume, the car. */
    JUMP,

    /** Previous, to the song before. */
    BACK,

    /** Playback stopped: KJB closed (or Android closed it), or the queue was emptied. */
    STOPPED,

    /** None of those is known: a song that could not be played, the song playing taken out of the queue. */
    UNKNOWN,
}

/**
 * How a play began, as `plays.db` and backups keep it: by its name in lower case, as [PlayEnd]. A value a newer KJB wrote
 * and this one does not know reads as [UNKNOWN].
 */
enum class StartedBy {
    /** The user picked this very song: a tap on it in a list, a queue, Search or the car, a bookmark, or Play next. */
    PICKED,

    /** It came on by itself: the song before played to its end (the next song, or a lap of Repeat one). */
    AUTO,

    /** Next brought it. */
    NEXT,

    /** Previous brought it. */
    BACK,

    /** Kept for KJB's suggestions (a later version): a song they put on. Nothing writes it yet. */
    SUGGESTED,

    /**
     * None of those: the first song of a Shuffle, Resume or Play from start (the user picked the list, not the song), the
     * song loaded when KJB starts, the song after one that could not be played or was taken out of the queue.
     */
    UNKNOWN,
}

/**
 * What the player knew as a play began: how ([by]), the kind of list its queue came from ([source]; null without a
 * queue), whether that queue was shuffled, and the song's [songKey]. Since KJB v4, for Insights' Where you listen and Top
 * playlists (their cards come later), also the kind of [output] the sound came out of (the car while one is connected),
 * never a device's name or address, and the playlist's id when the queue came from one ([playlistId]; never another
 * list's key, which can hold a name or a path); none for a play from before.
 */
data class PlayStart(
    val by: StartedBy,
    val source: SourceKind?,
    val shuffled: Boolean,
    val songKey: String?,
    val output: OutputKind? = null,
    val playlistId: String? = null,
)

/** [value]'s name as `plays.db` and backups keep it: lower case. */
fun codeOf(value: Enum<*>): String = value.name.lowercase(Locale.ROOT)

/** The entry of [E] kept as [code] ([codeOf]); null for none or one this version does not know. */
inline fun <reified E : Enum<E>> enumOf(code: String?): E? = code?.let { enumValues<E>().firstOrNull { entry -> codeOf(entry) == it } }

/** How far the length goes into [songKey]: to the nearest 2 s, the tolerance backups match songs by (backup/BackupMatch.kt). */
const val SONG_KEY_LENGTH_STEP_MS: Long = 2_000

/**
 * [song]'s key, the same after the file is moved or renamed: its title, artist and album as [TrackNames.normalize]
 * compares them, and its length to the nearest [SONG_KEY_LENGTH_STEP_MS], one after the other with "|" between
 * ("harbour glass|kjb test band|tide charts|54"). Nothing reads it yet: a later version joins a moved file's plays by it.
 */
fun songKey(song: Song): String {
    val length = (song.durationMs.coerceAtLeast(0) + SONG_KEY_LENGTH_STEP_MS / 2) / SONG_KEY_LENGTH_STEP_MS
    return listOf(song.title, song.artist.orEmpty(), song.album.orEmpty()).joinToString("|", postfix = "|$length") { TrackNames.normalize(it) }
}

/** How far [zone]'s clock was ahead of UTC at [at] (wall-clock millis), in minutes: 180 in Amman, -300 in New York in winter. */
fun zoneOffsetMinutes(zone: ZoneId, at: Long): Int = zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds / SECONDS_PER_MINUTE

private const val SECONDS_PER_MINUTE = 60

/** Where the player sends [PlayEvent]s: the play counter ([PlayCounter]). Never blocks the player. */
fun interface PlayEvents {
    fun onEvent(event: PlayEvent)
}

/** Plays not counted: tests, and the player before the counter is built. */
object NoPlayEvents : PlayEvents {
    override fun onEvent(event: PlayEvent) = Unit
}
