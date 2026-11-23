package app.lyrify.huddle

import android.content.Context
import android.util.Log
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.USER_AGENT
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.logFailures
import app.lyrify.stats.HeardTime
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEvent
import app.lyrify.stats.PlayEvents
import app.lyrify.stats.StatsGraph
import app.lyrify.suggestions.FriendsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * The player's plays for Friends sharing ([HuddleGraph.played]): a play is passed on once [SHARE_AFTER_MS] of it was heard
 * (half a shorter song), as the play counter hears it ([HeardTime]: the time played, not where the song stands, so a seek
 * or a play resumed at its place shares nothing yet), so skipping through songs shares nothing; each play once, and a lap
 * of Repeat one is a play of its own. A book's plays never come here (books/BookPlayFilter). The player's thread; never
 * blocks.
 *
 * What the player does now goes to [now] ([Huddle.listening]) as it changes: a play or a resume, a pause, another song,
 * a seek, the end of the play friends see, and every [HEARTBEAT_MS] while it plays, from the place the player reports
 * every few seconds. An end tells friends it stopped: after a skip the next song's state follows in the same instant and
 * replaces it, after a switch to a book (whose plays never come here) or the queue's end it stands. A pause friends did
 * not see playing (a song KJB opened with, the queue's first waiting after it ended) tells nothing.
 */
class SharedPlays(private val share: (path: String, at: Long) -> Unit, private val now: (path: String, state: NowState, positionMs: Long, at: Long) -> Unit) : PlayEvents {
    private class Waiting(val path: String, val at: Long, val afterMs: Long, val heard: HeardTime)

    /** What friends were told last: of which play and song, the state, and when. */
    private class Told(val playId: Long, val path: String, val state: NowState, val at: Long)

    /** Plays begun and not shared yet, by play id. */
    private val waiting = LinkedHashMap<Long, Waiting>()

    private var told: Told? = null

    override fun onEvent(event: PlayEvent) {
        when (event) {
            is PlayEvent.Started -> {
                val afterMs = if (event.durationMs > 0) minOf(SHARE_AFTER_MS, event.durationMs / 2) else SHARE_AFTER_MS
                waiting[event.playId] = Waiting(event.song.path, event.at, afterMs, HeardTime(event.durationMs))
                while (waiting.size > MAX_WAITING) waiting.remove(waiting.keys.first())
            }
            is PlayEvent.Position -> {
                val state = if (event.playing) NowState.PLAYING else NowState.PAUSED
                val last = told
                // Another song, a play or a pause, or a minute playing since friends were told; paused, only while friends see you.
                if ((event.playing || (last != null && last.state != NowState.STOPPED)) &&
                    (last == null || last.playId != event.playId || last.state != state || (event.playing && event.at - last.at >= HEARTBEAT_MS))
                ) {
                    tell(event.playId, event.song.path, state, event.positionMs, event.at)
                }
                val play = waiting[event.playId] ?: return
                play.heard.position(event.positionMs, event.playing, event.at)
                if (play.heard.heardMs >= play.afterMs) {
                    waiting.remove(event.playId)
                    share(play.path, play.at)
                }
            }
            is PlayEvent.Seeked -> {
                waiting[event.playId]?.heard?.seeked(event.fromMs, event.toMs, event.at)
                told?.takeIf { it.playId == event.playId }?.let { tell(it.playId, it.path, it.state, event.toMs, event.at) }
            }
            is PlayEvent.Ended -> {
                waiting.remove(event.playId)
                val last = told
                if (last != null && last.playId == event.playId && last.state != NowState.STOPPED) {
                    // After a skip friends still see you: a pause shows on the next song. After the queue's end or a stop, nothing does.
                    if (event.how == PlayEnd.FINISHED || event.how == PlayEnd.STOPPED) {
                        tell(event.playId, event.song.path, NowState.STOPPED, event.positionMs, event.at)
                    } else {
                        now(event.song.path, NowState.STOPPED, event.positionMs, event.at)
                    }
                }
            }
        }
    }

    private fun tell(playId: Long, path: String, state: NowState, positionMs: Long, at: Long) {
        told = Told(playId, path, state, at)
        now(path, state, positionMs, at)
    }

    companion object {
        /** How long a play sounds before friends see it: past a skip, soon enough for "now playing". */
        const val SHARE_AFTER_MS: Long = 10_000

        /** While a song plays friends are told again this often, so a phone gone quiet stops showing as playing. */
        const val HEARTBEAT_MS: Long = 60_000

        /** Plays the player keeps open at once are two at most (a crossfade); a few more never pile up. */
        private const val MAX_WAITING = 8
    }
}

/** Huddle's objects, built by hand, one of each per process. */
object HuddleGraph {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
    private var huddle: Huddle? = null

    /** The hosts KJB opens invite links of (AndroidManifest.xml): Huddle's Railway address and the user's own domain (plan Q3.2). */
    val hosts: List<String> = listOf(BuildConfig.HUDDLE_HOST, BuildConfig.HUDDLE_DOMAIN)

    /**
     * Huddle with `huddle.json`, its history in `plays.db` (the play log's helper) and the server at
     * [BuildConfig.HUDDLE_HOST], where its new invite links point too.
     */
    @Synchronized
    fun huddle(context: Context): Huddle = huddle ?: run {
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        val server = HuddleClient(BuildConfig.HUDDLE_HOST, USER_AGENT, log = log)
        val history = SqlHuddleLog(StatsGraph.db(context), log = log)
        Huddle(HuddleFiles.of(context), server, BuildConfig.HUDDLE_HOST, hosts, System::currentTimeMillis, SecureRandom(), scope, history, log)
    }.also { huddle = it }

    /** Friends' plays for Suggestions (handoff/sg1-interfaces.md, section 3): the integration wires it in SuggestionsGraph. */
    fun friends(context: Context): FriendsSource = HuddleFriends(huddle(context))

    /**
     * Where the player's plays go for Friends sharing (player/PlaybackService.kt): each play heard long enough, and what the
     * player does now, told on the player's thread so the newest is the last told.
     */
    fun plays(context: Context): PlayEvents {
        val app = context.applicationContext
        return SharedPlays({ path, at -> scope.launch { shared(app, path)?.let { (song, favourite) -> huddle(app).played(song, at, favourite) } } }) { path, state, positionMs, at ->
            huddle(app).listening(state, positionMs, at) { shared(app, path) }
        }
    }

    /**
     * The song at [path] as friends get it, and whether it is a favourite: music only (no audiobook, podcast or book), its
     * tags as the library reads them, once it is read (the player can start first). Null for no song, and when no friend
     * gets your plays (nothing is read then).
     */
    private suspend fun shared(context: Context, path: String): Pair<CardSong, Boolean>? {
        if (huddle(context).state.first().friends.none { it.sharePlays }) return null
        val library = (LibraryGraph.repository(context).state.first { it !is LibraryState.Loading } as? LibraryState.Ready)?.library ?: return null
        val song = library.song(path) ?: return null
        // A book's part the user marked can start before the player had the library: its play is no song's.
        if (song.isAudiobook || song.isPodcast || library.bookOf(path) != null) return null
        return Messages.song(song) to Lyrify.favourites(context).songs.first().any { it.path == path }
    }
}
