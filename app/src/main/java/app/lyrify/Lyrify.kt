package app.lyrify

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import app.lyrify.backup.Imports
import app.lyrify.books.isBookPart
import app.lyrify.data.LedgerRepository
import app.lyrify.data.PathLocks
import app.lyrify.data.SharedWrites
import app.lyrify.library.FavouritesPort
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.lyrics.Checked
import app.lyrify.lyrics.HttpLrclibClient
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.LyricsPreview
import app.lyrify.lyrics.Removal
import app.lyrify.lyrics.Searched
import app.lyrify.addon.Addon
import app.lyrify.addon.AddonDownloads
import app.lyrify.addon.ModelHandover
import app.lyrify.overnight.AiLyrics
import app.lyrify.player.PlaybackCommands
import app.lyrify.player.PlayerGraph
import app.lyrify.settings.SettingsRepository
import app.lyrify.settings.SettingsStore
import app.lyrify.stats.PlayEvents
import app.lyrify.stats.PlaysQuery
import app.lyrify.stats.StatsGraph
import app.lyrify.tags.TagsGraph
import app.lyrify.ui.CheckAnswers
import app.lyrify.ui.songImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Logcat tag (`adb logcat -s KJB`). Bug reports carry logcat, so its lines hold counts, status codes and exception class
 * names only: never a title, artist, album, path, URL or lyrics (LogPrivacyTest). Under [verbose] the lyrics pipeline
 * also names each song's outcome, and exceptions come with their stack traces.
 */
const val LOG_TAG: String = "KJB"

/** The User-Agent of the app's requests (LRCLIB, the AI download): its name, version and repository. */
val USER_AGENT: String = "KJB/${BuildConfig.VERSION_NAME} ( https://github.com/Mostafa-DE/Lyrify )"

/** Extra diagnostics: `adb shell setprop log.tag.KJB VERBOSE`. */
fun verbose(): Boolean = Log.isLoggable(LOG_TAG, Log.VERBOSE)

/** Logs a coroutine failure instead of crashing the process: its class, and only under [verbose] its trace (it can quote a path). */
val logFailures: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
    Log.e(LOG_TAG, "background work failed: ${e.javaClass.simpleName}", e.takeIf { verbose() })
}

/**
 * The process: one image loader for the covers, set before any screen asks for one. Debug builds also log disk work on
 * the main thread (StrictMode; stack traces only), so the player keeps the main thread free.
 */
class LyrifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())
        }
        SingletonImageLoader.setSafe(::songImageLoader)
        // Tag writes the app's death interrupted are settled first, in the background, before any tag write.
        TagsGraph.startRecovery(this)
        // An import from another app the app's death cut short is taken back.
        Imports.startRecovery(this)
    }
}

/**
 * Process-wide objects, built by hand on first use: one pipeline, so one LRCLIB client
 * (one request at a time app-wide) and one lookup per file at a time whoever asks.
 */
object Lyrify {
    private var pipeline: LyricsPipeline? = null

    /** The one checkpoint for changes to the user's files: a debug build changes only its test folder. */
    val writes: SharedWrites = SharedWrites.forThisBuild { Log.w(LOG_TAG, it) }

    /** The per-path lock the lyrics pipeline and the tag writer share: one song is never changed by both at once. */
    val locks: PathLocks = PathLocks()

    /** Work started from the screen that must finish even if the screen closes. */
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

    /** "Check these" answers: they finish, and the snackbar says how, even if the screen closes or the phone turns. */
    val checkAnswers: CheckAnswers = CheckAnswers(background)

    private val _deletingModels = MutableStateFlow(false)

    /** "Delete models" is waiting for the AI to stop, then deleting: the card says "Stopping…" meanwhile. */
    val deletingModels: StateFlow<Boolean> = _deletingModels.asStateFlow()

    /**
     * Built once; on first use it also adopts the files v1 wrote, so they can be removed from the
     * app, and compares the lyrics of the candidates 2.0.0 stored for songs to check. Writes the
     * app's death interrupted are settled by the listener's sweep, on every connect.
     */
    @Synchronized
    fun pipeline(context: Context): LyricsPipeline = pipeline ?: LyricsPipeline(
        client = HttpLrclibClient(userAgent = USER_AGENT, log = { Log.d(LOG_TAG, it) }),
        ledger = LedgerRepository(context),
        clock = System::currentTimeMillis,
        writes = writes,
        log = { Log.d(LOG_TAG, it) },
        warn = { Log.w(LOG_TAG, it) },
        detail = { if (verbose()) Log.v(LOG_TAG, it) },
        locks = locks,
        // Books never get lyrics (books/): known by the library once read, by their path before.
        isBook = context.applicationContext.let { app -> { path -> isBookPart(path, (LibraryGraph.repository(app).state.value as? LibraryState.Ready)?.library) } },
    ).also { created ->
        pipeline = created
        background.launch {
            created.adoptLegacy()
            created.compareStored()
        }
    }

    /** The settings (3.0): `settings.json`, one store per process. */
    fun settings(context: Context): SettingsStore = SettingsRepository(context)

    // 3.0's features: each package builds its own objects by hand (PlayerGraph, StatsGraph, LibraryGraph) and these hooks
    // only point at them, so a milestone replaces its feature's stub without touching this file.

    /** Plays songs from any list: KJB's own player (milestone P1); nothing plays until then. */
    fun playback(context: Context): PlaybackCommands = PlayerGraph.commands(context.applicationContext)

    /** Where the player's play events go: the play counter (milestone S1). */
    fun playEvents(context: Context): PlayEvents = StatsGraph.events(context.applicationContext)

    /** Plays per song, for Most played, Recently played and Song info (milestone S1). */
    fun plays(context: Context): PlaysQuery = StatsGraph.plays(context.applicationContext)

    /** Favourite songs and collections (milestone LC). */
    fun favourites(context: Context): FavouritesPort = LibraryGraph.favourites(context.applicationContext)

    /** "Wrong lyrics" / "Remove lyrics": deletes the `.lrc` KJB wrote next to [audioPath], if unchanged. */
    suspend fun remove(context: Context, audioPath: String): Removal =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).remove(audioPath) }

    /** "This one" on the check list: writes LRCLIB record [id]'s lyrics for [audioPath], or keeps its plain lyrics for the AI. */
    suspend fun choose(context: Context, audioPath: String, id: Long): Checked =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).choose(audioPath, id) }

    /** "None of these" on the check list. */
    suspend fun decline(context: Context, audioPath: String): Checked =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).decline(audioPath) }

    /** "Show lyrics" on the check list: the first lines of record [id], never stored. */
    suspend fun preview(context: Context, id: Long): LyricsPreview =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).preview(id) }

    /** "Search myself" on the check list. */
    suspend fun search(context: Context, track: LocalTrack, text: String): Searched =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).search(track, text) }

    /**
     * "Delete models": turns the AI off and deletes its models once a run in progress has actually stopped and closed its
     * sessions (ONNX Runtime and the NPU may still be reading them), finishing even if the screen closes. A run that does not stop in time
     * keeps them; the user can tap again.
     */
    fun deleteModels(context: Context) {
        val app = context.applicationContext
        if (!_deletingModels.compareAndSet(expect = false, update = true)) return
        background.launch {
            try {
                // The add-on's models, and the copies KJB 2.0 kept that it has not taken yet.
                val deleted = AiLyrics.afterStopping(app) {
                    AddonDownloads.cancel(app)
                    Addon.withPort(app) { it.deleteModels() }
                    ModelHandover.deleteLegacy(app)
                    AiLyrics.modelsDeleted(app)
                }
                if (deleted == null) {
                    Log.w(LOG_TAG, "models: the AI did not stop in time; nothing deleted")
                }
            } finally {
                _deletingModels.value = false
            }
        }
    }
}
