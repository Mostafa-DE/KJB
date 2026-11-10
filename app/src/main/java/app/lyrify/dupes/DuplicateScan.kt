package app.lyrify.dupes

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore
import app.lyrify.data.SharedWrites
import app.lyrify.tags.PayloadHash
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Content hashes of audio files, read-only. A song in a format the tag engine knows is hashed by its audio payload
 * alone ([PayloadHash]), so two copies with other tags hash the same: [partial] covers the payload's first [sliceBytes],
 * [full] all of it. Any other file by its bytes: the first and last [sliceBytes] ([partial]), or every byte ([full]) for
 * a file under [fullLimit]. A file is only ever opened for reading.
 */
class ContentHasher(
    private val sliceBytes: Int = SLICE_BYTES,
    private val fullLimit: Long = FULL_LIMIT,
    private val open: (Path) -> SeekableByteChannel = { Files.newByteChannel(it, StandardOpenOption.READ) },
) {
    /** A first look at a file: its [key], and [final] when a full hash would add nothing (it read everything) or is not taken. */
    data class Slice(val key: String, val final: Boolean)

    /** The two slices hold every byte of a file of [size]: its partial hash is its full one. */
    fun partialIsWhole(size: Long): Boolean = size <= 2L * sliceBytes

    /** Equal partial hashes of two files of [size] still leave their middles unknown, and the file is small enough to read whole. */
    fun needsFull(size: Long): Boolean = !partialIsWhole(size) && size < fullLimit

    /** The start of [file]'s audio, or else its first and last slices (all of it, when that is no more); null when it cannot be read. */
    suspend fun partial(file: AudioFile): Slice? {
        PayloadHash.slice(File(file.path).toPath(), sliceBytes.toLong())?.let { return Slice("$AUDIO${it.format}:${it.sha256}", it.whole) }
        val hash = hash(file) { channel, digest, buffer ->
            if (partialIsWhole(file.size)) {
                digestRange(channel, 0, file.size, digest, buffer)
            } else {
                digestRange(channel, 0, sliceBytes.toLong(), digest, buffer)
                digestRange(channel, file.size - sliceBytes, sliceBytes.toLong(), digest, buffer)
            }
        } ?: return null
        return Slice("$BYTES${file.size}:$hash", !needsFull(file.size))
    }

    /** All of [file]'s audio, or else every byte of it; null when it cannot be read or is not [AudioFile.size] long. */
    suspend fun full(file: AudioFile): String? {
        PayloadHash.of(File(file.path).toPath())?.let { return "$AUDIO${it.format}:${it.sha256}" }
        return hash(file) { channel, digest, buffer -> digestRange(channel, 0, file.size, digest, buffer) }?.let { "$BYTES${file.size}:$it" }
    }

    private suspend fun hash(file: AudioFile, read: suspend (SeekableByteChannel, MessageDigest, ByteBuffer) -> Boolean): String? = try {
        open(File(file.path).toPath()).use { channel ->
            val digest = MessageDigest.getInstance("SHA-256")
            val complete = channel.size() == file.size && read(channel, digest, ByteBuffer.allocate(BUFFER_BYTES))
            if (complete) digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } else null
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Feeds [length] bytes from [start] to [digest]; false when the file ends before them (it shrank meanwhile). */
    private suspend fun digestRange(channel: SeekableByteChannel, start: Long, length: Long, digest: MessageDigest, buffer: ByteBuffer): Boolean {
        channel.position(start)
        var left = length
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), left).toInt())
            val read = channel.read(buffer)
            if (read <= 0) return false
            digest.update(buffer.array(), 0, read)
            left -= read
        }
        return true
    }

    companion object {
        const val SLICE_BYTES: Int = 1024 * 1024
        const val FULL_LIMIT: Long = 64L * 1024 * 1024
        private const val BUFFER_BYTES: Int = 64 * 1024
        private const val AUDIO = "audio:"
        private const val BYTES = "bytes:"
    }
}

/**
 * Content keys of the [files] that share a length with another: equal keys are identical audio, whatever the tags. A file
 * is hashed only when another one has its exact length (MediaStore's, which a retag keeps), first by the start of its
 * audio ([ContentHasher.partial]); the full hash is taken only when another file of that length starts the same and
 * the start leaves the rest unknown. A file that cannot be read gets no key. [onFile] hears how many files are done,
 * after each one, in order.
 */
suspend fun identicalKeys(files: List<AudioFile>, hasher: ContentHasher, onFile: (Int) -> Unit = {}): Map<Long, String> {
    val shared = files.filter { it.size > 0 && it.durationMs > 0 }.groupingBy { it.durationMs }.eachCount().filterValues { it > 1 }.keys
    val slices = HashMap<Long, ContentHasher.Slice>()
    val fulls = HashMap<Long, String?>()
    val seen = HashMap<Pair<Long, String>, MutableList<AudioFile>>()
    files.forEachIndexed { index, file ->
        currentCoroutineContext().ensureActive()
        val slice = if (file.size > 0 && file.durationMs in shared) hasher.partial(file) else null
        if (slice != null) {
            slices[file.id] = slice
            val peers = seen.getOrPut(file.durationMs to slice.key) { ArrayList() }
            if (peers.isNotEmpty() && !slice.final) {
                for (peer in peers + file) if (peer.id !in fulls) fulls[peer.id] = hasher.full(peer)
            }
            peers += file
        }
        onFile(index + 1)
    }
    return files.mapNotNull { file ->
        val slice = slices[file.id] ?: return@mapNotNull null
        if (file.id !in fulls) file.id to slice.key else fulls[file.id]?.let { file.id to it }
    }.toMap()
}

/** What reading the phone's songs gave. */
sealed interface LibraryRead {
    data class Files(val files: List<AudioFile>) : LibraryRead

    /** MediaStore refused or failed; [cause] is for the log (no titles or paths). */
    data class Unavailable(val cause: String) : LibraryRead
}

/** Every song on the phone, as MediaStore knows it: in MediaStore in the app, made up in tests. */
interface AudioLibrary {
    /** Blocking. */
    fun read(): LibraryRead

    /**
     * The files with the MediaStore [ids] as MediaStore has them now, by id; a file it no longer lists is left out. Null
     * when MediaStore could not be read. Blocking.
     */
    fun rows(ids: Collection<Long>): Map<Long, AudioFile>?
}

/**
 * The phone's songs from MediaStore: every audio file it marks as music, and any other audio file in a Music folder.
 * Trashed and pending files are not listed (MediaStore leaves them out by default). Reads tags and sizes only. A debug
 * build lists only the songs [writes] lets it change (its test folder).
 */
class MediaStoreLibrary(
    /** MediaStore's audio rows, with [PROJECTION]'s columns, that match a selection and its arguments; a fake in tests. */
    private val query: (selection: String?, args: Array<String>?) -> Cursor?,
    private val writes: SharedWrites,
) : AudioLibrary {
    constructor(resolver: ContentResolver, writes: SharedWrites) : this(
        query = { selection, args -> resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION, selection, args, null) },
        writes = writes,
    )

    override fun read(): LibraryRead = try {
        val cursor = query(null, null)
        if (cursor == null) LibraryRead.Unavailable("no cursor") else LibraryRead.Files(cursor.use { files(it, musicOnly = true) })
    } catch (e: SecurityException) {
        LibraryRead.Unavailable("refused: ${e.javaClass.simpleName}")
    } catch (e: IllegalArgumentException) {
        LibraryRead.Unavailable("rejected: ${e.javaClass.simpleName}")
    }

    override fun rows(ids: Collection<Long>): Map<Long, AudioFile>? = try {
        val found = HashMap<Long, AudioFile>()
        val read = ids.distinct().chunked(MAX_IDS_PER_QUERY).all { chunk ->
            val selection = "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",") { "?" }})"
            val cursor = query(selection, chunk.map(Long::toString).toTypedArray()) ?: return@all false
            cursor.use { files(it, musicOnly = false).forEach { file -> found[file.id] = file } }
            true
        }
        if (read) found else null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * The files of [cursor]'s rows that [writes] lists (a debug build: its test folder only, for every read and lookup);
     * with [musicOnly], only those also [inScope].
     */
    private fun files(cursor: Cursor, musicOnly: Boolean): List<AudioFile> {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val data = cursor.getColumnIndexOrThrow(DATA)
        val relativePath = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
        val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val mimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        val bitrate = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE)
        val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val isMusic = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
        return buildList {
            while (cursor.moveToNext()) {
                val path = cursor.getString(data) ?: continue
                val folder = cursor.getString(relativePath)
                if (!writes.lists(path) || musicOnly && !inScope(cursor.getInt(isMusic) != 0, folder)) continue
                add(
                    AudioFile(
                        id = cursor.getLong(id),
                        path = path,
                        relativePath = folder,
                        displayName = cursor.getString(displayName) ?: File(path).name,
                        title = cursor.getString(title).orEmpty(),
                        artist = cursor.getString(artist),
                        album = cursor.getString(album),
                        durationMs = cursor.getLong(duration),
                        size = cursor.getLong(size),
                        mimeType = cursor.getString(mimeType),
                        bitrate = if (cursor.isNull(bitrate)) null else cursor.getLong(bitrate),
                        addedAt = addedAt(if (cursor.isNull(dateAdded)) null else cursor.getLong(dateAdded)),
                    ),
                )
            }
        }
    }

    companion object {
        /** Marked as music, or anywhere in a Music folder ("Music/…", on any volume). */
        fun inScope(isMusic: Boolean, relativePath: String?): Boolean =
            isMusic || relativePath?.startsWith(MUSIC_FOLDER, ignoreCase = true) == true

        /** DATE_ADDED ([seconds] since the epoch) in millis. */
        fun addedAt(seconds: Long?): Long? = seconds?.takeIf { it > 0 }?.let(TimeUnit.SECONDS::toMillis)

        private const val MUSIC_FOLDER: String = "Music/"

        /** Well under SQLite's limit of query arguments. */
        private const val MAX_IDS_PER_QUERY: Int = 500

        /** The file's absolute path: with All files access it is the real one, which the ledger and the `.lrc` go by. */
        private const val DATA: String = "_data"

        private val PROJECTION: Array<String> = arrayOf(
            MediaStore.Audio.Media._ID,
            DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.IS_MUSIC,
        )
    }
}
