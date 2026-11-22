package app.lyrify.player

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.max

// Audio files other apps open in KJB (features-2026-11-15, item 1): a file manager's View, or its Share of one or several
// files. They play at once as one queue, "Opened file". Only `content:` addresses are taken, and each is read through the
// opening app's grant (ContentResolver), never as a path with KJB's own All files access: a caller cannot make KJB read a
// file the caller could not. MediaStore's own address of an audio file is the library's song with that id, so its plays,
// lyrics and heart apply (a book's file plays its book); any other address is the library's song when exactly one has
// the file name and size the opening app tells (BK1), which plays only what KJB plays already and reads nothing of the
// caller's; anything else plays from a copy in KJB's own cache, never added to the library, and nothing is written next
// to it.

/** MediaStore's id in its own address of an audio file (`content://media/<volume>/audio/media/<id>`); null for any other. */
fun mediaStoreAudioId(uri: String): Long? {
    if (!uri.startsWith(MEDIA_PREFIX, ignoreCase = true)) return null
    val segments = uri.substring(MEDIA_PREFIX.length).substringBefore('?').substringBefore('#').split('/')
    if (segments.size != MEDIA_SEGMENTS || segments[0].isEmpty() || segments[1] != "audio" || segments[2] != "media") return null
    return segments[3].toLongOrNull()?.takeIf { it >= 0 }
}

/** The library's song with MediaStore id [id]; null when none, or more than one has it: a guess between songs is no answer. */
fun matchOpened(id: Long?, songs: List<Song>): Song? = id?.let { songs.filter { song -> song.ref.mediaStoreId == it }.singleOrNull() }

/** What the app that opened a file tells of it (OpenableColumns): the file's [name] and [sizeBytes], each null when unsaid. */
data class Openable(val name: String?, val sizeBytes: Long?)

/**
 * The library's song an opened file is by what the opening app tells of it ([told]): the one song of [songs] with that
 * file name and that size in bytes. Null when either is unknown, or no song or several have both: a guess between songs
 * is no answer.
 */
fun matchNamed(told: Openable, songs: List<Song>): Song? {
    val name = told.name?.takeIf(String::isNotEmpty) ?: return null
    val size = told.sizeBytes?.takeIf { it > 0 } ?: return null
    return songs.filter { it.fileName == name && it.sizeBytes == size }.singleOrNull()
}

/**
 * The library's song the file opened at [address] is: by the id in MediaStore's own address ([matchOpened]); any other
 * address (a file manager's own) by its name and size ([matchNamed]), which [told] reads from the opening app only then.
 * Null: it plays from a copy.
 */
fun openedSong(address: String, songs: List<Song>, told: () -> Openable): Song? {
    val id = mediaStoreAudioId(address) ?: return matchNamed(told(), songs)
    return matchOpened(id, songs)
}

/**
 * What the file opened at [address] plays: the library's song ([openedSong]); else, for a `content:` address, its copy
 * ([copy], under the name the opening app gives); else nothing. [tell] asks the opening app for the file's name and size
 * once, and only for a `content:` address that needs them.
 */
fun openedRef(address: String, songs: List<Song>, tell: (String) -> Openable, copy: (String, String?) -> SongRef?): SongRef? {
    val content = address.startsWith("content://", ignoreCase = true)
    val told = lazy { if (content) tell(address) else Openable(null, null) }
    openedSong(address, songs) { told.value }?.let { return it.ref }
    return if (content) copy(address, told.value.name) else null
}

/** [column] of the first row of [row] as a number, found by its name; null without a row, or with the column missing or empty. */
internal fun firstLong(row: Cursor, column: String): Long? {
    val at = row.getColumnIndex(column)
    return if (at >= 0 && row.moveToFirst() && !row.isNull(at)) row.getLong(at) else null
}

/** [column] of the first row of [row] as text, found by its name; null without a row, or with the column missing or empty. */
internal fun firstString(row: Cursor, column: String): String? {
    val at = row.getColumnIndex(column)
    return if (at >= 0 && row.moveToFirst() && !row.isNull(at)) row.getString(at) else null
}

/**
 * The batches of copies under [root] (one folder per open, named by its time) that no queue holds a file of any more
 * ([queued]: every queued song's path), the newest kept anyway: its queue may not be playing yet.
 */
fun staleBatches(batches: List<String>, queued: Collection<String>, root: String): List<String> {
    val held = queued.mapNotNull { path -> path.takeIf { it.startsWith("$root/") }?.removePrefix("$root/")?.substringBefore('/') }.toSet()
    val newest = batches.maxOrNull()
    return batches.filter { it != newest && it !in held }.sorted()
}

/**
 * The key of an opened copy's cover at KJB's cover provider (`auto/CarBrowse.kt`, CarCovers): its open and its place,
 * `opened-<open>-<place>` for `.../cache/opened/<open>/<place>/<name>`, never its name or path; null for any other path.
 */
fun openedCoverKey(path: String): String? {
    val parts = path.split('/')
    if (parts.size < OPENED_DEPTH) return null
    val (cache, folder, open, place) = parts.subList(parts.size - OPENED_DEPTH, parts.size - 1)
    if (cache != "cache" || folder != OPENED_FOLDER || !open.isNumber() || !place.isNumber() || parts.last().isEmpty()) return null
    return "$OPENED_FOLDER-$open-$place"
}

/** The folder of the copy that cover [key] ([openedCoverKey]) names, `<open>/<place>` under the copies; null for any other key. */
fun openedCoverDir(key: String): String? {
    val parts = key.split('-')
    if (parts.size != 3 || parts[0] != OPENED_FOLDER || !parts[1].isNumber() || !parts[2].isNumber()) return null
    return "${parts[1]}/${parts[2]}"
}

private fun String.isNumber(): Boolean = isNotEmpty() && all { it in '0'..'9' }

/** The largest embedded picture read from an opened copy ([embeddedPictureBytes]): with a bigger one KJB's default cover shows. */
const val MAX_COVER_BYTES: Long = 8L * 1024 * 1024

/** The most characters of an opened copy's title, artist or album that are kept and shown ([tagText]). */
const val MAX_TAG_TEXT: Int = 300

/**
 * The most bytes an embedded picture of [file] can take as Android's retriever reads it, by its tags' own sizes, read
 * before any picture is (the retriever hands a picture over whole, whatever its size): an ID3v2 tag (an MP3's, or one
 * before a FLAC's marker), a FLAC's picture and comment blocks, an MP4's metadata items, an Ogg's comment header. Each way
 * the retriever may read the file counts, the largest kept; 0 when they hold none. Null for any other format, and for tags
 * cut short, broken or past what is looked at: no picture is read from those. Blocking.
 */
fun embeddedPictureBytes(file: File): Long? = try {
    RandomAccessFile(file, "r").use { it.pictureBytes() }
} catch (e: IOException) {
    null
}

private fun RandomAccessFile.pictureBytes(): Long? {
    val bounds = ArrayList<Long>()
    // ID3v2 tags one after another at the start, each whole: an MP3's, and those Android skips before a FLAC's marker.
    var at = 0L
    var tags = 0
    while (holds(at, "ID3")) {
        if (++tags > MAX_LOOKS) return null
        val size = id3Bytes(at)
        bounds += size
        at += size
    }
    if (holds(at, "fLaC")) bounds += flacBytes(at + 4) ?: return null
    if (holds(0, "OggS")) bounds += oggCommentBytes() ?: return null
    if (holds(4, "ftyp")) bounds += mp4Bytes() ?: return null
    return bounds.maxOrNull()
}

private fun RandomAccessFile.holds(at: Long, magic: String): Boolean {
    if (at + magic.length > length()) return false
    seek(at)
    return ByteArray(magic.length).also(::readFully).contentEquals(magic.toByteArray(Charsets.ISO_8859_1))
}

private fun RandomAccessFile.uint(): Long = readInt().toLong() and 0xffff_ffffL

/** The ID3v2 tag at [at] whole: its header, then the size it says, 7 bits a byte. */
private fun RandomAccessFile.id3Bytes(at: Long): Long {
    seek(at + 6)
    return ID3_HEADER + ByteArray(4).also(::readFully).fold(0L) { size, byte -> size shl 7 or (byte.toLong() and 0x7f) }
}

/**
 * The largest of the FLAC metadata blocks from [start] a picture is read from: a picture block, or more when its picture
 * says so; a comment block whole, where a picture may be written as text.
 */
private fun RandomAccessFile.flacBytes(start: Long): Long? {
    var at = start
    var most = 0L
    repeat(MAX_LOOKS) {
        seek(at)
        val head = readInt()
        val length = (head and 0xff_ffff).toLong()
        when (head ushr 24 and 0x7f) {
            FLAC_PICTURE -> most = maxOf(most, length, flacPictureBytes(at + 4))
            FLAC_COMMENT -> most = maxOf(most, length)
        }
        if (head < 0) return most
        at += 4 + length
    }
    return null
}

/** What the FLAC picture block at [at] says its picture holds: after its type, its MIME type, its description and four numbers. */
private fun RandomAccessFile.flacPictureBytes(at: Long): Long {
    seek(at + 4)
    val mime = uint()
    seek(at + 8 + mime)
    val description = uint()
    seek(at + 12 + mime + description + 16)
    return uint()
}

/** The length of an Ogg stream's second packet, its comment header (Vorbis's and Opus's), where a picture is written as text. */
private fun RandomAccessFile.oggCommentBytes(): Long? {
    var at = 0L
    var packet = 0
    var size = 0L
    repeat(MAX_LOOKS) {
        if (!holds(at, "OggS")) return null
        seek(at + OGG_SEGMENTS)
        val laces = ByteArray(readUnsignedByte()).also(::readFully).map { it.toInt() and 0xff }
        laces.forEach { lace ->
            if (packet == 1) size += lace
            // A lace under 255 ends its packet.
            if (lace < 255) {
                if (packet == 1) return size
                packet++
            }
        }
        at += OGG_SEGMENTS + 1 + laces.size + laces.sum()
    }
    return null
}

/**
 * The largest MP4 metadata item: an `ilst` item whole, and an `ID32` with the ID3v2 tag it holds, looked for in the atoms
 * metadata sits in (`moov`, `trak`, `udta`, `meta`, `ilst`), never in the sample tables; null for a broken atom, or
 * atoms nested or numbered past what is looked at.
 */
private fun RandomAccessFile.mp4Bytes(): Long? {
    var looks = MAX_LOOKS
    fun walk(start: Long, end: Long, depth: Int, items: Boolean): Long? {
        if (depth > MP4_DEPTH) return null
        var at = start
        var most = 0L
        while (at + 8 <= end) {
            if (--looks < 0) return null
            seek(at)
            val small = uint()
            val type = ByteArray(4).also(::readFully).toString(Charsets.ISO_8859_1)
            val header = if (small == 1L) 16L else 8L
            val size = when (small) {
                0L -> end - at
                1L -> readLong()
                else -> small
            }
            if (size < header) return null
            val inside = at + header
            most = maxOf(
                most,
                when {
                    items -> size
                    type in MP4_BOXES -> walk(inside, at + size, depth + 1, items = false) ?: return null
                    // ISO's meta has a version and flags (0) before its atoms, QuickTime's none.
                    type == "meta" -> {
                        seek(inside)
                        walk(if (inside + 4 <= at + size && readInt() == 0) inside + 4 else inside, at + size, depth + 1, items = false) ?: return null
                    }
                    type == "ilst" -> walk(inside, at + size, depth + 1, items = true) ?: return null
                    type == "ID32" -> maxOf(size, if (holds(inside + 6, "ID3")) id3Bytes(inside + 6) else 0)
                    else -> 0
                },
            )
            at += size
        }
        return most
    }
    return walk(0, length(), 0, items = false)
}

/** The largest halving of a [width] by [height] picture still at least [px] on its longer side; null for no size or no picture. */
fun coverSample(width: Int, height: Int, px: Int): Int? {
    val longest = max(width, height)
    if (px <= 0 || longest <= 0) return null
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2 && longest / (sample * 2) >= px) sample *= 2
    return sample
}

/** An opened copy's title, artist or album as its tag has it: none when blank, at most [MAX_TAG_TEXT] characters. */
fun tagText(value: String?): String? = value?.takeIf(String::isNotBlank)?.take(MAX_TAG_TEXT)

private const val ID3_HEADER = 10L
private const val FLAC_COMMENT = 4
private const val FLAC_PICTURE = 6

/** Where an Ogg page says how many segments it has; its segments' lengths follow, then its data. */
private const val OGG_SEGMENTS = 26L
private val MP4_BOXES = setOf("moov", "trak", "udta")
private const val MP4_DEPTH = 8

/** The most ID3v2 tags, FLAC blocks, Ogg pages or MP4 atoms looked at for a picture's size. */
private const val MAX_LOOKS = 4_096

/** KJB's copies of opened files, in its cache: `cache/opened/<open>/<place>/<name>`. */
private const val OPENED_FOLDER = "opened"

/** The last path segments that place a copy: the cache, its folder, the open, the place, and its name. */
private const val OPENED_DEPTH = 5

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
    private val held = MutableStateFlow<Map<String, Song>>(emptyMap())

    /**
     * The tags of the copies the opens still hold, by path: the player, the Now bar and the lyrics name a copy the library
     * lacks by them (PlayerUi.opened), the notification too ([song]).
     */
    val songs: StateFlow<Map<String, Song>> = held.asStateFlow()

    @Volatile
    private var folder: File? = null

    private fun folder(context: Context): File = folder ?: File(context.applicationContext.cacheDir, OPENED_FOLDER).also { folder = it }

    /**
     * The player's song for [ref] when it is a copy of an opened file (PlaybackService plays the library's first); null for
     * any other. A copy still in the cache after KJB restarted plays under its name until [watch] has read its tags again.
     */
    fun song(context: Context, ref: SongRef): Song? =
        held.value[ref.path] ?: ref.path.takeIf { it.startsWith(folder(context).path + "/") }?.let { plainSong(File(it), readTags = false) }

    /**
     * The embedded cover of the copy at [path], decoded at the largest halving still at least [px] on its longer side
     * ([coverSample]); null for a path outside KJB's copies (nothing else is read), a copy without one, or one whose tags
     * do not keep it within [MAX_COVER_BYTES] ([embeddedPictureBytes]): the retriever would hand it over whole. Blocking.
     */
    fun cover(context: Context, path: String, px: Int): Bitmap? {
        if (px <= 0) return null
        val file = File(path)
        val inside = try {
            file.canonicalPath.startsWith(folder(context).canonicalPath + "/")
        } catch (e: IOException) {
            false
        }
        if (!inside || !file.isFile) return null
        val room = embeddedPictureBytes(file) ?: return null
        if (room !in 1..MAX_COVER_BYTES) return null
        return try {
            val bytes = MediaMetadataRetriever().use { tags ->
                tags.setDataSource(file.path)
                tags.embeddedPicture
            }?.takeIf { it.size <= MAX_COVER_BYTES } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = coverSample(bounds.outWidth, bounds.outHeight, px) ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "open: no cover read (${e.javaClass.simpleName})")
            null
        } catch (e: OutOfMemoryError) {
            // The default cover rather than the app gone: the picture or its pixels did not fit now.
            Log.w(LOG_TAG, "open: no cover read (${e.javaClass.simpleName})")
            null
        }
    }

    /** The copy whose cover [key] ([openedCoverKey]) names, while it is in the cache; null for any other key. */
    fun coverCopy(context: Context, key: String): String? {
        val dir = openedCoverDir(key)?.let { File(folder(context), it) } ?: return null
        return dir.listFiles()?.singleOrNull { it.isFile && it.extension != "part" }?.path
    }

    /**
     * Plays [uris] (`content:` addresses) at once as one queue, "Opened file", in their order: each the library's song when
     * it is MediaStore's address of one, or the one song with the name and size the opening app tells ([openedSong]), else
     * a copy. The first one a book's file of the library, its book plays instead (books/BookPlayback.kt, asBook). Returns
     * how many play; 0 when none could be read. Main thread.
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
                one.withLock {
                    withContext(Dispatchers.IO) {
                        sweep(root, queued)
                        recall(queued)
                    }
                }
            }
        }
    }

    private fun QueueBook.queuedPaths(): Set<String> = queues.flatMapTo(HashSet()) { queue -> queue.items.map { it.song.path } }

    /** Deletes the [staleBatches] under [root], and forgets their tags. Holding [one]. */
    private fun sweep(root: File, queued: Collection<String>) {
        val batches = root.list()?.toList().orEmpty()
        staleBatches(batches, queued, root.path).forEach { batch ->
            val gone = File(root, batch)
            if (gone.deleteRecursively()) held.update { songs -> songs.filterKeys { !it.startsWith(gone.path + "/") } }
        }
    }

    /** Reads again the tags of the [queued] copies this process has not read (KJB restarted since they were opened). Holding [one]. */
    private fun recall(queued: Collection<String>) {
        queued.filter { it !in held.value && File(it).isFile }.forEach { path ->
            val song = plainSong(File(path), readTags = true)
            held.update { it + (path to song) }
        }
    }

    /** Each of [uris] as a song to play, its copies in a new batch. Holding [one]. */
    private fun prepare(context: Context, uris: List<String>, library: List<Song>): List<SongRef> {
        val batch = File(folder(context), System.currentTimeMillis().toString())
        var budget = MAX_COPIED_BYTES
        val refs = uris.mapIndexedNotNull { index, address ->
            openedRef(address, library, tell = { openable(context.contentResolver, it.toUri()) }) { at, name ->
                val copy = copy(context.contentResolver, at.toUri(), File(batch, index.toString()), name, budget)
                copy?.also { budget -= it.length() }?.let { plainSong(it, readTags = true) }?.also { song -> held.update { it + (song.path to song) } }?.ref
            }
        }
        if (refs.isEmpty()) batch.deleteRecursively()
        if (refs.size < uris.size) Log.w(LOG_TAG, "open: ${uris.size - refs.size} of ${uris.size} files not readable")
        return refs
    }

    /** What the app that opened [uri] tells of the file: its name ([displayName]) and its size ([size]). */
    private fun openable(resolver: ContentResolver, uri: Uri): Openable = Openable(displayName(resolver, uri), size(resolver, uri))

    /** The file's size in bytes as the app that opened it gives it; null when it does not say. */
    private fun size(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { firstLong(it, OpenableColumns.SIZE) }
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "open: no size for a file (${e.javaClass.simpleName})")
        null
    }

    /** The file's name as the app that opened it gives it, for the copy's name and the player's title. */
    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { firstString(it, OpenableColumns.DISPLAY_NAME) }?.substringAfterLast('/')
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

    /**
     * [file] as a song that is in no library: its tags when [readTags] (blocking), each kept to [MAX_TAG_TEXT] characters
     * ([tagText]), else only its name; its name too without a title tag.
     */
    internal fun plainSong(file: File, readTags: Boolean): Song {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationMs = 0L
        if (readTags) {
            try {
                MediaMetadataRetriever().use { tags ->
                    tags.setDataSource(file.path)
                    title = tagText(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                    artist = tagText(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                    album = tagText(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
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
