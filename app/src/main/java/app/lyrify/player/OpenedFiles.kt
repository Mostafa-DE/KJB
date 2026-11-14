package app.lyrify.player

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// Audio files other apps open in KJB (features-2026-11-15, item 1): a file manager's View, or its Share of one or several
// files. They play at once as one queue, "Opened file". Only `content:` addresses are taken, and each is read through the
// opening app's grant (ContentResolver), never as a path with KJB's own All files access: a caller cannot make KJB read a
// file the caller could not. MediaStore's own address of an audio file is the library's song with that id, so its plays,
// lyrics and heart apply; anything else plays from a copy in KJB's own cache, never added to the library, and nothing is
// written next to it.

/** MediaStore's id in its own address of an audio file (`content://media/<volume>/audio/media/<id>`); null for any other. */
fun mediaStoreAudioId(uri: String): Long? {
    if (!uri.startsWith(MEDIA_PREFIX, ignoreCase = true)) return null
    val segments = uri.substring(MEDIA_PREFIX.length).substringBefore('?').substringBefore('#').split('/')
    if (segments.size != MEDIA_SEGMENTS || segments[0].isEmpty() || segments[1] != "audio" || segments[2] != "media") return null
    return segments[3].toLongOrNull()?.takeIf { it >= 0 }
}

/** The library's song with MediaStore id [id]; null when none, or more than one has it: a guess between songs is no answer. */
fun matchOpened(id: Long?, songs: List<Song>): Song? = id?.let { songs.filter { song -> song.ref.mediaStoreId == it }.singleOrNull() }

/**
 * The batches of copies under [root] (one folder per open, named by its time) that no queue holds a file of any more
 * ([queued]: every queued song's path), the newest kept anyway: its queue may not be playing yet.
 */
fun staleBatches(batches: List<String>, queued: Collection<String>, root: String): List<String> {
    val held = queued.mapNotNull { path -> path.takeIf { it.startsWith("$root/") }?.removePrefix("$root/")?.substringBefore('/') }.toSet()
    val newest = batches.maxOrNull()
    return batches.filter { it != newest && it !in held }.sorted()
}

private const val MEDIA_PREFIX = "content://" + MediaStore.AUTHORITY + "/"
private const val MEDIA_SEGMENTS = 4

/**
 * Opened files on this phone, one open at a time. A file that is not the library's is read now, through the opening app's
 * grant (it ends when the screen it opened closes), into KJB's own cache (`cache/opened/<open>/`), never into shared
 * storage. A batch of copies goes once no queue holds its files ([watch]); the newest stays.
 */
object OpenedFiles {
    /** The key of the "Opened file" queue: one at a time in Recent. */
    const val SOURCE_KEY: String = "opened"

    /** How long a cold start waits for the library before the files play as copies. */
    private const val LIBRARY_WAIT_MS: Long = 10_000

    /** The most one open copies in all, and the room it leaves free. */
    private const val MAX_COPIED_BYTES: Long = 2L * 1024 * 1024 * 1024
    private const val SPARE_BYTES: Long = 200L * 1024 * 1024

    private const val MAX_NAME = 120

    /** One open at a time, and no batch swept while one is copied. */
    private val one = Mutex()

    /** The tags of the copies the opens still hold, by path. */
    private val opened = ConcurrentHashMap<String, Song>()

    @Volatile
    private var folder: File? = null

    private fun folder(context: Context): File = folder ?: File(context.applicationContext.cacheDir, "opened").also { folder = it }

    /**
     * The player's song for [ref] when it is a copy of an opened file (PlaybackService plays the library's first); null for
     * any other. A copy still in the cache after KJB restarted plays under its name.
     */
    fun song(context: Context, ref: SongRef): Song? =
        opened[ref.path] ?: ref.path.takeIf { it.startsWith(folder(context).path + "/") }?.let { plainSong(File(it), readTags = false) }

    /**
     * Plays [uris] (`content:` addresses) at once as one queue, "Opened file", in their order: each the library's song when
     * it is MediaStore's address of one, else a copy. Returns how many play; 0 when none could be read. Main thread.
     */
    suspend fun play(context: Context, uris: List<String>): Int = one.withLock {
        val app = context.applicationContext
        val state = withTimeoutOrNull(LIBRARY_WAIT_MS) { LibraryGraph.repository(app).state.first { it !is LibraryState.Loading } }
        val songs = (state as? LibraryState.Ready)?.library?.all.orEmpty()
        val refs = withContext(Dispatchers.IO) { prepare(app, uris, songs) }
        if (refs.isNotEmpty()) Lyrify.playback(app).play(refs, 0, QueueSource(SourceKind.OPENED, SOURCE_KEY, ""))
        val queued = PlayerGraph.core(app).book.value.queuedPaths()
        withContext(Dispatchers.IO) { sweep(folder(app), queued) }
        refs.size
    }

    /**
     * Sweeps the copies no queue holds any more, whenever the queues change ([book], the player's), in [scope] (the
     * playback service's): what the player left goes, never what it plays or keeps.
     */
    fun watch(context: Context, book: StateFlow<QueueBook>, scope: CoroutineScope) {
        val root = folder(context)
        scope.launch(Dispatchers.Default) {
            book.map { it.queuedPaths().filterTo(HashSet()) { path -> path.startsWith(root.path + "/") } }.distinctUntilChanged().collect { queued ->
                one.withLock { withContext(Dispatchers.IO) { sweep(root, queued) } }
            }
        }
    }

    private fun QueueBook.queuedPaths(): Set<String> = queues.flatMapTo(HashSet()) { queue -> queue.items.map { it.song.path } }

    /** Deletes the [staleBatches] under [root], and forgets their tags. Holding [one]. */
    private fun sweep(root: File, queued: Collection<String>) {
        val batches = root.list()?.toList().orEmpty()
        staleBatches(batches, queued, root.path).forEach { batch ->
            val gone = File(root, batch)
            if (gone.deleteRecursively()) opened.keys.removeAll { it.startsWith(gone.path + "/") }
        }
    }

    /** Each of [uris] as a song to play, its copies in a new batch. Holding [one]. */
    private fun prepare(context: Context, uris: List<String>, library: List<Song>): List<SongRef> {
        val batch = File(folder(context), System.currentTimeMillis().toString())
        var budget = MAX_COPIED_BYTES
        val refs = uris.mapIndexedNotNull { index, address ->
            matchOpened(mediaStoreAudioId(address), library)?.let { return@mapIndexedNotNull it.ref }
            if (!address.startsWith("content://", ignoreCase = true)) return@mapIndexedNotNull null
            val uri = address.toUri()
            val copy = copy(context.contentResolver, uri, File(batch, index.toString()), displayName(context.contentResolver, uri), budget)
            copy?.also { budget -= it.length() }?.let { plainSong(it, readTags = true) }?.also { opened[it.path] = it }?.ref
        }
        if (refs.isEmpty()) batch.deleteRecursively()
        if (refs.size < uris.size) Log.w(LOG_TAG, "open: ${uris.size - refs.size} of ${uris.size} files not readable")
        return refs
    }

    /** The file's name as the app that opened it gives it, for the copy's name and the player's title. */
    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { row ->
            if (row.moveToFirst() && !row.isNull(0)) row.getString(0)?.substringAfterLast('/') else null
        }
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "open: no name for a file (${e.javaClass.simpleName})")
        null
    }

    /**
     * [uri]'s bytes copied into [into] under its [name], at most [budget] bytes and never into the last [SPARE_BYTES] of
     * room; null, with nothing left behind, when it cannot be read or does not fit.
     */
    private fun copy(resolver: ContentResolver, uri: Uri, into: File, name: String?, budget: Long): File? {
        val room = minOf(budget, into.parentFile?.let { (it.apply { mkdirs() }.usableSpace - SPARE_BYTES) } ?: 0)
        if (room <= 0) return null
        into.mkdirs()
        val target = File(into, safeName(name))
        val part = File(into, target.name + ".part")
        val done = try {
            resolver.openInputStream(uri)?.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > room) return@use false
                        out.write(buffer, 0, read)
                    }
                    true
                }
            } == true
        } catch (e: IOException) {
            Log.w(LOG_TAG, "open: a file not read (${e.javaClass.simpleName})")
            false
        } catch (e: RuntimeException) {
            // A SecurityException when the grant is gone, or whatever another app's provider throws.
            Log.w(LOG_TAG, "open: a file not read (${e.javaClass.simpleName})")
            false
        }
        if (!done || !part.renameTo(target)) {
            into.deleteRecursively()
            return null
        }
        return target
    }

    /** A file name safe to keep: no folders, control characters or leading dots, at most [MAX_NAME] characters. */
    private fun safeName(name: String?): String {
        val clean = name.orEmpty().filterNot { it == '/' || it == '\\' || it.isISOControl() }.trim().trimStart('.')
        if (clean.isEmpty()) return "audio"
        val extension = clean.substringAfterLast('.', missingDelimiterValue = "").takeIf { it.length in 1..5 }?.let { ".$it" }.orEmpty()
        return if (clean.length <= MAX_NAME) clean else clean.take(MAX_NAME - extension.length) + extension
    }

    /** [file] as a song that is in no library: its tags when [readTags] (blocking), else only its name. */
    private fun plainSong(file: File, readTags: Boolean): Song {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        if (readTags) {
            try {
                MediaMetadataRetriever().use { tags ->
                    tags.setDataSource(file.path)
                    title = tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
                    artist = tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
                    album = tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf(String::isNotBlank)
                    durationMs = tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                }
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "open: no tags read (${e.javaClass.simpleName})")
            }
        }
        return Song(
            ref = SongRef(file.path),
            title = title ?: file.nameWithoutExtension,
            artist = artist,
            album = album,
            albumArtist = null,
            durationMs = durationMs,
            track = null,
            disc = null,
            year = null,
            genre = null,
            folder = "",
            addedAt = null,
            modifiedAt = null,
            sizeBytes = 0,
            mimeType = null,
        )
    }

    private const val BUFFER_BYTES = 64 * 1024
}
