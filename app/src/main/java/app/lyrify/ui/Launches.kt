package app.lyrify.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.player.OpenedFiles
import app.lyrify.player.PlayerGraph
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.LyricsFilter
import app.lyrify.settings.SortSongs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// What other apps and the launcher ask of KJB through MainActivity's intent (features-2026-11-15, items 1 and 4): audio
// files to play (a file manager's View, or its Share of one or several files), or one of the launcher shortcuts (a long
// press on KJB's icon: Shuffle all, Resume, Search, Favourites; res/xml/shortcuts.xml). Shared text is not KJB's to
// open here: the Downloads lane's "Save to KJB" takes it, through its own text/plain filter.

/** The parts of an intent that say what it asks of KJB, as plain values ([takeLaunch] reads them from the intent). */
data class LaunchIntent(val action: String?, val type: String? = null, val data: String? = null, val streams: List<String> = emptyList())

/** What an intent asks of KJB. */
sealed interface Launch {
    /** What starts playing, then shows the player ([LaunchWork]). */
    sealed interface Plays : Launch

    /** Play these files at once, in this order, as one queue ("Opened file"): `content:` or `file:` addresses. */
    data class Open(val uris: List<String>) : Plays

    /** All the library's songs, shuffled. */
    data object ShuffleAll : Plays

    /** The queue playing, from where it was. */
    data object Resume : Plays

    /** The Search tab, its field focused. */
    data object Search : Launch

    /** The Favourites page. */
    data object Favourites : Launch
}

/**
 * The launcher's shortcuts, static in res/xml/shortcuts.xml (and the debug build's copy, for its own package): [id] there,
 * and the action of its intent. Shortcuts open MainActivity by its class, so the actions need no intent filter.
 */
enum class Shortcut(val id: String, val action: String, val launch: Launch) {
    SHUFFLE_ALL("shuffle_all", "app.lyrify.action.SHUFFLE_ALL", Launch.ShuffleAll),
    RESUME("resume", "app.lyrify.action.RESUME", Launch.Resume),
    SEARCH("search", "app.lyrify.action.SEARCH", Launch.Search),
    FAVOURITES("favourites", "app.lyrify.action.FAVOURITES", Launch.Favourites),
}

/** The most files one open plays: a file manager's "select all" can share thousands. */
const val MAX_OPENED: Int = 500

/** Types a Share of audio may carry besides `audio/…`. */
private val AUDIO_TYPES = setOf("application/ogg", "application/x-ogg", "application/flac", "application/x-flac")

/**
 * What [intent] asks: a View of a `content:` or `file:` address (its type is the filter's to check: Android resolves it
 * without putting it in the intent), a Share of audio files (each once, in their order, at most [MAX_OPENED]), or a
 * shortcut. Null for anything else, the launcher icon itself and shared text included.
 */
fun launchOf(intent: LaunchIntent): Launch? = when (intent.action) {
    Intent.ACTION_VIEW -> intent.data?.takeIf(::openable)?.let { Launch.Open(listOf(it)) }
    Intent.ACTION_SEND -> intent.streams.takeIf { audio(intent.type) }?.firstOrNull(::openable)?.let { Launch.Open(listOf(it)) }
    Intent.ACTION_SEND_MULTIPLE ->
        intent.streams.takeIf { audio(intent.type) }?.filter(::openable)?.distinct()?.take(MAX_OPENED)?.takeIf { it.isNotEmpty() }?.let(Launch::Open)
    else -> Shortcut.entries.firstOrNull { it.action == intent.action }?.launch
}

private fun openable(uri: String): Boolean = uri.startsWith("content://", ignoreCase = true) || uri.startsWith("file://", ignoreCase = true)

private fun audio(type: String?): Boolean = type != null && (type.startsWith("audio/", ignoreCase = true) || type.lowercase() in AUDIO_TYPES)

/** What MainActivity's [intent] asks ([launchOf]), read once: a relaunch from Recents, or a turn of the phone, asks nothing again. */
fun takeLaunch(intent: Intent): Launch? {
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
    val launch = launchOf(LaunchIntent(intent.action, intent.type, intent.dataString, streams(intent))) ?: return null
    intent.action = TAKEN
    return launch
}

/** An intent's action once KJB has done what it asked. */
private const val TAKEN = "app.lyrify.action.TAKEN"

/** The files a Share carries: its stream extra (one, or a list), and its clip data, which some apps fill instead. */
private fun streams(intent: Intent): List<String> = try {
    val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    } else {
        @Suppress("DEPRECATION")
        when (val stream = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> listOf(stream)
            is ArrayList<*> -> stream.filterIsInstance<Uri>()
            else -> emptyList()
        }
    }
    val clips = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    (extra + clips).map(Uri::toString)
} catch (e: RuntimeException) {
    // Another app's extras: one that cannot be read (BadParcelableException) carries nothing for KJB.
    Log.w(LOG_TAG, "open: a share not read (${e.javaClass.simpleName})")
    emptyList()
}

/**
 * Does what [launch] asks once the shell shows (after the first-run screen), also on a cold start: plays the opened
 * files, all songs shuffled, or the queue playing, then shows the player ([onPlayer]); a toast says why when nothing can
 * play. (Search and Favourites only open a tab or a page, which MainActivity hands the shell itself.) The music starts
 * even when the phone turns meanwhile; [onDone] once it is done.
 */
@Composable
fun LaunchWork(launch: Launch.Plays?, onDone: () -> Unit, onPlayer: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val player by rememberUpdatedState(onPlayer)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(launch) {
        val asked = launch ?: return@LaunchedEffect
        try {
            when (val problem = withContext(NonCancellable) { play(context.applicationContext, asked) }) {
                null -> player()
                else -> toasts.say(resources, problem)
            }
        } finally {
            done()
        }
    }
}

/** The key of a Songs queue in the default order: a tap in Library > Songs then plays inside the shuffled queue. */
private val ALL_SONGS = QueueSource(SourceKind.SONGS, "songs:${SortSongs.TITLE}:${LyricsFilter.ALL}", "")

/** How long a cold start waits for the library or the queues. */
private const val READ_WAIT_MS: Long = 10_000

/** Starts what [launch] asks; null once it plays, else why it cannot. Main thread. */
@StringRes
private suspend fun play(context: Context, launch: Launch.Plays): Int? = when (launch) {
    is Launch.Open -> if (OpenedFiles.play(context, launch.uris) > 0) null else R.string.open_failed
    Launch.ShuffleAll -> {
        val state = withTimeoutOrNull(READ_WAIT_MS) { LibraryGraph.repository(context).state.first { it !is LibraryState.Loading } }
        val songs = (state as? LibraryState.Ready)?.library?.songs.orEmpty()
        if (songs.isEmpty()) {
            R.string.launch_no_songs
        } else {
            Lyrify.playback(context).shuffle(songs.map { it.ref }, ALL_SONGS)
            null
        }
    }
    Launch.Resume -> {
        val core = PlayerGraph.core(context)
        withTimeoutOrNull(READ_WAIT_MS) { core.ready.first { it } }
        if (core.book.value.current?.current == null) {
            R.string.launch_no_queue
        } else {
            // Play on a player with nothing loaded resumes the queue playing, at its place (PlaybackService).
            PlayerGraph.connection(context).play()
            null
        }
    }
}
