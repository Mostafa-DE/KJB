package app.lyrify.tags

import android.content.Context
import android.os.StrictMode
import android.os.storage.StorageManager
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.logFailures
import app.lyrify.media.TrackResolver
import app.lyrify.tags.lookup.AlbumLookup
import app.lyrify.tags.lookup.MetaCache
import app.lyrify.tags.lookup.MetaClient
import app.lyrify.tags.lookup.MusicBrainz
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * The tag engine's objects, built by hand once per process: one writer (so one tag write at a time app-wide, on one
 * thread), its journal and Undo store, the start-up recovery that every write waits for, and the one album lookup.
 * Covers & tags (T2) and Edit tags (T3) get the writer, the lookup and History from here.
 */
object TagsGraph {
    /** KJB's own open songs: the tone preview holds its song here, and KJB's player its current song (milestone P1). */
    val held: HeldPaths = HeldPaths()

    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)
    private val recovered = CompletableDeferred<Unit>()
    private var parts: Parts? = null
    private var writer: SafeWriter? = null
    private var lookup: AlbumLookup? = null
    private var recovering = false

    private class Parts(context: Context) {
        val dirs = TagDirs(context.filesDir, ::fsyncDirectory)
        val journal = WriteJournal(dirs.journal, dirs.syncDir, ::log)
        val undo = UndoStore(dirs, ::log)
        val songs = PhoneSongFiles(context)
    }

    /**
     * Settles the tag writes the app's death interrupted (see [JournalRecovery]). Called once from
     * LyrifyApplication.onCreate: the songs of unfinished writes are held in [Lyrify.locks] right there, before anything
     * else runs, so the lyrics pipeline waits for them and the AI and the tone preview skip them; the rest runs in the
     * background, and no tag write starts before it is done.
     */
    @Synchronized
    fun startRecovery(context: Context) {
        if (recovering) return
        recovering = true
        val parts = parts(context.applicationContext)
        val recovery = JournalRecovery(parts.dirs, parts.journal, parts.undo, parts.songs, Lyrify.locks, Lyrify.writes, ::log)
        // A few small files, read on the main thread on purpose: the songs must be held before any other part starts.
        val policy = StrictMode.allowThreadDiskReads()
        val held = try {
            StrictMode.allowThreadDiskWrites()
            recovery.holdPending()
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
        background.launch {
            try {
                recovery.recover(held)
            } finally {
                recovered.complete(Unit)
            }
        }
    }

    /** The one tag writer. */
    @Synchronized
    fun writer(context: Context): SafeWriter = writer ?: run {
        val app = context.applicationContext
        val parts = parts(app)
        SafeWriter(
            dirs = parts.dirs,
            tags = TagLibFile(app.filesDir),
            songs = parts.songs,
            busy = PhoneBusyFiles(
                sessions = { sessionFiles(app, TrackResolver(app.contentResolver)) },
                held = held,
                ai = Lyrify.pipeline(app).aiPaths,
            ),
            locks = Lyrify.locks,
            writes = Lyrify.writes,
            journal = parts.journal,
            undo = parts.undo,
            freeBytes = { allocatable(app) },
            clock = System::currentTimeMillis,
            log = ::log,
            io = Dispatchers.IO.limitedParallelism(1),
            ready = { recovered.await() },
        ).also { writer = it }
    }

    /**
     * The one album lookup (Covers & tags): one MusicBrainz and Cover Art Archive client for the app, so its gate (one
     * request at a time, 1.1 s apart, the 503 backoff) and its answer cache in `cache/tag-cache/` hold app-wide.
     */
    @Synchronized
    fun lookup(context: Context): AlbumLookup = lookup ?: run {
        val app = context.applicationContext
        AlbumLookup(MusicBrainz(MetaClient(MetaCache(File(app.cacheDir, "tag-cache")), log = ::log)), ::log)
    }.also { lookup = it }

    /** History and Undo's records, for the History list. */
    fun undo(context: Context): UndoStore = parts(context.applicationContext).undo

    @Synchronized
    private fun parts(context: Context): Parts = parts ?: Parts(context).also { parts = it }

    /** Bytes the app may still write in its own storage, counting cache the system would clear for it; 0 when unknown. */
    private fun allocatable(context: Context): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(context.filesDir))
    } catch (_: IOException) {
        0
    }

    private fun log(line: String) {
        Log.i(LOG_TAG, line)
    }
}
