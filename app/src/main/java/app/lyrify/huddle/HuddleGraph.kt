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
import app.lyrify.stats.PlayEvent
import app.lyrify.stats.PlayEvents
import app.lyrify.suggestions.FriendsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * The player's plays for Friends sharing ([HuddleGraph.played]): a play is passed on once it has sounded
 * [SHARE_AFTER_MS] (half a shorter song), so skipping through songs shares nothing; each play once, and a lap of
 * Repeat one is a play of its own. A book's plays never come here (books/BookPlayFilter). The player's thread; never blocks.
 */
class SharedPlays(private val share: (path: String, at: Long) -> Unit) : PlayEvents {
    private class Waiting(val path: String, val at: Long, val afterMs: Long)

    /** Plays begun and not shared yet, by play id. */
    private val waiting = LinkedHashMap<Long, Waiting>()

    override fun onEvent(event: PlayEvent) {
        when (event) {
            is PlayEvent.Started -> {
                waiting[event.playId] = Waiting(event.song.path, event.at, if (event.durationMs > 0) minOf(SHARE_AFTER_MS, event.durationMs / 2) else SHARE_AFTER_MS)
                while (waiting.size > MAX_WAITING) waiting.remove(waiting.keys.first())
            }
            is PlayEvent.Position -> {
                val play = waiting[event.playId] ?: return
                if (event.playing && event.positionMs >= play.afterMs) {
                    waiting.remove(event.playId)
                    share(play.path, play.at)
                }
            }
            is PlayEvent.Ended -> waiting.remove(event.playId)
            is PlayEvent.Seeked -> Unit
        }
    }

    companion object {
        /** How long a play sounds before friends see it: past a skip, soon enough for "now playing". */
        const val SHARE_AFTER_MS: Long = 10_000

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

    /** Huddle with `huddle.json` and the server at [BuildConfig.HUDDLE_HOST], where its new invite links point too. */
    @Synchronized
    fun huddle(context: Context): Huddle = huddle ?: run {
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        val server = HuddleClient(BuildConfig.HUDDLE_HOST, USER_AGENT, log = log)
        Huddle(HuddleFiles.of(context), server, BuildConfig.HUDDLE_HOST, hosts, System::currentTimeMillis, SecureRandom(), scope, log)
    }.also { huddle = it }

    /** Friends' plays for Suggestions (handoff/sg1-interfaces.md, section 3): the integration wires it in SuggestionsGraph. */
    fun friends(context: Context): FriendsSource = HuddleFriends(HuddleFiles.of(context))

    /** Where the player's plays go for Friends sharing (player/PlaybackService.kt). */
    fun plays(context: Context): PlayEvents {
        val app = context.applicationContext
        return SharedPlays { path, at -> scope.launch { played(app, path, at) } }
    }

    /**
     * The play of the song at [path], begun [at], to the friends who get your plays: music only (no audiobook or podcast),
     * its tags as the library reads them and whether it is a favourite. Reads nothing when no friend gets plays.
     */
    private suspend fun played(context: Context, path: String, at: Long) {
        val huddle = huddle(context)
        if (huddle.state.first().friends.none { it.sharePlays }) return
        val song = (LibraryGraph.repository(context).state.value as? LibraryState.Ready)?.library?.song(path) ?: return
        if (song.isAudiobook || song.isPodcast) return
        val favourite = Lyrify.favourites(context).songs.first().any { it.path == path }
        huddle.played(Messages.song(song), at, favourite)
    }
}
