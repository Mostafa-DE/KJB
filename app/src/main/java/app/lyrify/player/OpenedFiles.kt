package app.lyrify.player

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

// Audio files other apps open in KJB (features-2026-11-15, item 1): a file manager's View, or its Share of one or several
// files. They play at once as one queue, "Opened file". A file that is a song of the library plays as that song, so its
// plays, lyrics and heart apply; any other plays as it is, never added to the library, and nothing is written next to it.

/**
 * What KJB can tell about an opened file before it plays it: its absolute [path], MediaStore's id for it, and its file
 * [name] and size as the app that opened it gives them.
 */
data class OpenedClue(val path: String? = null, val mediaStoreId: Long? = null, val name: String? = null, val bytes: Long? = null)

/**
 * What [uri] says by itself: the path of a `file:` address and of the phone's storage documents (on [primaryVolume] or a
 * card), or MediaStore's id in its own addresses and in its documents. Nothing for any other app's address, or for a
 * path that climbs out of its folder.
 */
fun clueOf(uri: String, primaryVolume: String): OpenedClue {
    val scheme = uri.substringBefore("://", missingDelimiterValue = "").lowercase()
    val rest = uri.substringAfter("://").substringBefore('?').substringBefore('#')
    val authority = rest.substringBefore('/')
    val segments = rest.substringAfter('/', missingDelimiterValue = "").split('/').filter { it.isNotEmpty() }
    return when (scheme) {
        "file" -> if (authority.isEmpty()) segments.map { decoded(it) ?: return OpenedClue() }.let { pathClue("/" + it.joinToString("/")) } else OpenedClue()
        "content" -> contentClue(authority, segments, primaryVolume)
        else -> OpenedClue()
    }
}

private fun contentClue(authority: String, segments: List<String>, primaryVolume: String): OpenedClue {
    val document = segments.indexOf("document").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }?.let(::decoded)
    return when (authority) {
        // content://media/<volume>/audio/media/<id>, content://media/<volume>/file/<id>
        MediaStore.AUTHORITY -> when {
            segments.size == 4 && segments[1] == "audio" && segments[2] == "media" -> idClue(segments[3])
            segments.size == 3 && segments[1] == "file" -> idClue(segments[2])
            else -> OpenedClue()
        }
        MEDIA_DOCUMENTS -> document?.takeIf { it.startsWith("audio:") }?.let { idClue(it.removePrefix("audio:")) } ?: OpenedClue()
        DOWNLOAD_DOCUMENTS -> when {
            document == null -> OpenedClue()
            document.startsWith("raw:") -> pathClue(document.removePrefix("raw:"))
            document.startsWith("msf:") -> idClue(document.removePrefix("msf:"))
            else -> OpenedClue()
        }
        STORAGE_DOCUMENTS -> {
            val volume = document?.substringBefore(':', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() && '/' !in it && it != ".." } ?: return OpenedClue()
            val root = if (volume == "primary") primaryVolume else "/storage/$volume"
            pathClue(root.removeSuffix("/") + "/" + document.substringAfter(':'))
        }
        else -> OpenedClue()
    }
}

private fun idClue(id: String): OpenedClue = id.toLongOrNull()?.takeIf { it >= 0 }?.let { OpenedClue(mediaStoreId = it) } ?: OpenedClue()

/** An absolute path, without `.` or `..` parts. */
private fun pathClue(path: String): OpenedClue {
    val parts = path.split('/')
    return if (path.startsWith("/") && parts.none { it == "." || it == ".." }) OpenedClue(path = path) else OpenedClue()
}

/** One part of an address, its %-escapes read (a `+` stays a `+`); null when they are not valid. */
private fun decoded(part: String): String? = try {
    URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8.name())
} catch (_: IllegalArgumentException) {
    null
}

/**
 * The library's song [clue] names: the one at its path, else the one with its MediaStore id, else the one file of its
 * name and size. Null when none, or when more than one song fits a way: a guess between copies is no answer.
 */
fun matchOpened(clue: OpenedClue, songs: List<Song>): Song? {
    clue.path?.let { path -> songs.firstOrNull { it.path == path } }?.let { return it }
    clue.mediaStoreId?.let { id -> songs.filter { it.ref.mediaStoreId == id }.singleOrNull() }?.let { return it }
    val name = clue.name ?: return null
    val bytes = clue.bytes ?: return null
    return songs.filter { it.fileName == name && it.sizeBytes == bytes }.singleOrNull()
}

private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
private const val DOWNLOAD_DOCUMENTS = "com.android.providers.downloads.documents"
private const val STORAGE_DOCUMENTS = "com.android.externalstorage.documents"

/**
 * Opened files on this phone. A file the library lacks plays from where it is when KJB can read it there; any other is
 * read now, while the opening app's grant lasts (it ends when the screen it opened closes), into KJB's own cache
 * (`cache/opened`): never into shared storage. The next open replaces those copies, as its queue replaces theirs in Recent.
 */
object OpenedFiles {
    /** The key of the "Opened file" queue: one at a time in Recent. */
    const val SOURCE_KEY: String = "opened"

    /** How long a cold start waits for the library before the files play unmatched. */
    private const val LIBRARY_WAIT_MS: Long = 10_000

    /** The most one open copies in all, and the room it leaves free. */
    private const val MAX_COPIED_BYTES: Long = 2L * 1024 * 1024 * 1024
    private const val SPARE_BYTES: Long = 200L * 1024 * 1024

    private const val MAX_NAME = 120

    /** The songs the library lacks that the last open plays, by path. */
    private val opened = ConcurrentHashMap<String, Song>()

    @Volatile
    private var folder: File? = null

    private fun folder(context: Context): File = folder ?: File(context.applicationContext.cacheDir, "opened").also { folder = it }

    /**
     * The player's song for [ref] when it is a file opened from another app that the library lacks (PlaybackService
     * plays the library's first); null for any other. A copy still in the cache after KJB restarted plays under its name.
     */
    fun song(context: Context, ref: SongRef): Song? =
        opened[ref.path] ?: ref.path.takeIf { it.startsWith(folder(context).path + "/") }?.let { plainSong(File(it), readTags = false) }

    /**
     * Plays [uris] at once as one queue, "Opened file", in their order: each the library's song when it is one, else the
     * file as it is. Returns how many play; 0 when none could be read. Call on the main thread.
     */
    suspend fun play(context: Context, uris: List<String>): Int {
        val app = context.applicationContext
        val state = withTimeoutOrNull(LIBRARY_WAIT_MS) { LibraryGraph.repository(app).state.first { it !is LibraryState.Loading } }
        val songs = (state as? LibraryState.Ready)?.library?.all.orEmpty()
        val refs = withContext(Dispatchers.IO) { prepare(app, uris, songs) }
        if (refs.isNotEmpty()) Lyrify.playback(app).play(refs, 0, QueueSource(SourceKind.OPENED, SOURCE_KEY, ""))
        return refs.size
    }

    /** Each of [uris] as a song to play; the copies and songs of the last open go once this one has any. */
    private fun prepare(context: Context, uris: List<String>, library: List<Song>): List<SongRef> {
        val root = folder(context)
        val batch = File(root, System.currentTimeMillis().toString())
        val primary = Environment.getExternalStorageDirectory().path
        val own = context.applicationInfo.dataDir
        val found = mutableMapOf<String, Song>()
        var budget = MAX_COPIED_BYTES
        val refs = uris.mapIndexedNotNull { index, uri ->
            val clue = clueOf(uri, primary)
            val facts = if (uri.startsWith("content://", ignoreCase = true)) facts(context.contentResolver, Uri.parse(uri)) else OpenedClue()
            val path = clue.path ?: facts.path
            val full = OpenedClue(path, clue.mediaStoreId, facts.name ?: path?.substringAfterLast('/'), facts.bytes)
            matchOpened(full, library)?.let { return@mapIndexedNotNull it.ref }
            // Never a file of KJB's own data, whatever an address claims.
            val readable = path?.let(::File)?.takeIf { it.isFile && it.canRead() && !inside(it, own) }
            val file = readable ?: if (uri.startsWith("content://", ignoreCase = true)) {
                copy(context.contentResolver, Uri.parse(uri), File(batch, index.toString()), full.name, budget)?.also { budget -= it.length() }
            } else {
                null
            }
            file?.let { plainSong(it, readTags = true) }?.also { found[it.path] = it }?.ref
        }
        if (refs.isEmpty()) {
            batch.deleteRecursively()
            return refs
        }
        opened.clear()
        opened.putAll(found)
        root.listFiles()?.filter { it != batch }?.forEach { it.deleteRecursively() }
        if (refs.size < uris.size) Log.w(LOG_TAG, "open: ${uris.size - refs.size} of ${uris.size} files not readable")
        return refs
    }

    /** [file] is in [folder] or below, links followed; true when that cannot be told. */
    private fun inside(file: File, folder: String): Boolean = try {
        file.canonicalPath.startsWith(File(folder).canonicalPath + "/")
    } catch (_: IOException) {
        true
    }

    /** What the app that opened [uri] says of it: its name and size, and the path when it gives one (MediaStore does). */
    private fun facts(resolver: ContentResolver, uri: Uri): OpenedClue {
        var name: String? = null
        var bytes: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                if (row.moveToFirst()) {
                    name = row.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !row.isNull(it) }?.let(row::getString)
                    bytes = row.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !row.isNull(it) }?.let(row::getLong)?.takeIf { it >= 0 }
                }
            }
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "open: no name for a file (${e.javaClass.simpleName})")
        }
        // Not every app answers for MediaStore's path column; those that do not may throw.
        val path = try {
            @Suppress("DEPRECATION")
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { row ->
                if (row.moveToFirst() && !row.isNull(0)) row.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        }
        return OpenedClue(path = path?.let { pathClue(it).path }, name = name?.substringAfterLast('/'), bytes = bytes)
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
