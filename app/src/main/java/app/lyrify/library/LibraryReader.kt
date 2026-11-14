package app.lyrify.library

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.provider.MediaStore
import app.lyrify.books.inAudiobooksFolder
import app.lyrify.data.SharedWrites
import app.lyrify.dupes.MediaStoreLibrary
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.concurrent.TimeUnit

/** What reading the phone's songs gave. */
sealed interface SongsRead {
    data class Songs(val songs: List<Song>) : SongsRead

    /** MediaStore refused or failed; [cause] is for the log (an exception's class name, never a title or path). */
    data class Unavailable(val cause: String) : SongsRead
}

/** Where the library's songs come from: MediaStore in the app ([LibraryReader]), made up in tests. */
interface LibrarySource {
    /** Every song. Blocking. */
    fun read(): SongsRead

    /**
     * A token that changes whenever MediaStore's songs may have changed: its version and generation per volume. Null when
     * MediaStore cannot say, so the songs are read again. Blocking.
     */
    fun stamp(): String?

    /** A signal for each change MediaStore reports to its songs; they come in bursts while it scans. */
    val changes: Flow<Unit>
}

/**
 * The phone's songs from MediaStore, in one query with every column the library needs: the music Duplicates lists
 * ([MediaStoreLibrary.inScope]) and the audiobooks ([inScope]), without trashed and pending files (MediaStore leaves them
 * out by default). A debug build lists only the songs in its test folders ([SharedWrites.lists]).
 */
class LibraryReader(
    /** MediaStore's audio rows with [PROJECTION]'s columns; a fake in tests. */
    private val query: () -> Cursor?,
    private val readStamp: () -> String?,
    override val changes: Flow<Unit>,
    private val writes: SharedWrites,
) : LibrarySource {
    constructor(context: Context, writes: SharedWrites) : this(
        query = { context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION, null, null, null) },
        readStamp = { stampOf(context) },
        changes = changesOf(context.contentResolver),
        writes = writes,
    )

    override fun read(): SongsRead = try {
        val cursor = query()
        if (cursor == null) SongsRead.Unavailable("no cursor") else SongsRead.Songs(cursor.use(::songs))
    } catch (e: SecurityException) {
        SongsRead.Unavailable("refused: ${e.javaClass.simpleName}")
    } catch (e: IllegalArgumentException) {
        SongsRead.Unavailable("rejected: ${e.javaClass.simpleName}")
    }

    override fun stamp(): String? = readStamp()

    private fun songs(cursor: Cursor): List<Song> {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val data = cursor.getColumnIndexOrThrow(DATA)
        val relativePath = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val title = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
        val artist = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ARTIST)
        val album = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ALBUM)
        val albumArtist = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.ALBUM_ARTIST)
        val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM_ID)
        val duration = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val track = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.CD_TRACK_NUMBER)
        val disc = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISC_NUMBER)
        val year = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.YEAR)
        val genre = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENRE)
        val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val dateModified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val mimeType = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val isMusic = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.IS_MUSIC)
        val isAudiobook = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.IS_AUDIOBOOK)
        fun text(column: Int): String? = cursor.getString(column)?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN }
        fun long(column: Int): Long? = if (cursor.isNull(column)) null else cursor.getLong(column)
        return buildList {
            while (cursor.moveToNext()) {
                val path = cursor.getString(data) ?: continue
                val folder = cursor.getString(relativePath)
                val book = cursor.getInt(isAudiobook) != 0
                if (!writes.lists(path) || !inScope(cursor.getInt(isMusic) != 0, book, folder)) continue
                add(
                    Song(
                        ref = SongRef(path, cursor.getLong(id)),
                        title = text(title) ?: path.substringAfterLast('/').substringBeforeLast('.'),
                        artist = text(artist),
                        album = text(album),
                        albumArtist = text(albumArtist),
                        durationMs = cursor.getLong(duration),
                        track = number(cursor.getString(track)),
                        disc = number(cursor.getString(disc)),
                        year = cursor.getInt(year).takeIf { it > 0 },
                        genre = text(genre),
                        folder = folder.orEmpty(),
                        addedAt = millis(long(dateAdded)),
                        modifiedAt = millis(long(dateModified)),
                        sizeBytes = cursor.getLong(size),
                        mimeType = cursor.getString(mimeType),
                        albumId = long(albumId)?.takeIf { it > 0 },
                        isAudiobook = book,
                    ),
                )
            }
        }
    }

    companion object {
        /** The file's absolute path: with All files access it is the real one, which the ledger and the `.lrc` go by. */
        private const val DATA: String = "_data"

        /** MediaStore.UNKNOWN_STRING, MediaStore's artist for a song without one (the stubs give no constant to test with). */
        private const val UNKNOWN: String = "<unknown>"

        val PROJECTION: Array<String> = arrayOf(
            MediaStore.Audio.Media._ID,
            DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.ARTIST,
            MediaStore.MediaColumns.ALBUM,
            MediaStore.MediaColumns.ALBUM_ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM_ID,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.CD_TRACK_NUMBER,
            MediaStore.MediaColumns.DISC_NUMBER,
            MediaStore.MediaColumns.YEAR,
            MediaStore.MediaColumns.GENRE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Audio.AudioColumns.IS_MUSIC,
            MediaStore.Audio.AudioColumns.IS_AUDIOBOOK,
        )

        /**
         * Whether a row is the library's: the music Duplicates lists ([MediaStoreLibrary.inScope]: marked as music, or in a
         * Music folder), and audiobooks, marked by MediaStore ([isAudiobook]) or in an Audiobooks folder. Podcasts, ringtones
         * and recordings stay out.
         */
        fun inScope(isMusic: Boolean, isAudiobook: Boolean, relativePath: String?): Boolean =
            MediaStoreLibrary.inScope(isMusic, relativePath) || isAudiobook || relativePath?.let(::inAudiobooksFolder) == true

        /** A track or disc number as the tag has it ("3", "03", "3/12"); null for none, 0 or anything else. */
        private fun number(tag: String?): Int? = tag?.trim()?.takeWhile(Char::isDigit)?.toIntOrNull()?.takeIf { it > 0 }

        /** MediaStore's seconds since the epoch in millis; null for none. */
        private fun millis(seconds: Long?): Long? = seconds?.takeIf { it > 0 }?.let(TimeUnit.SECONDS::toMillis)

        /** Every external volume's MediaStore version (new when MediaStore rebuilds) and generation (new on every change). */
        private fun stampOf(context: Context): String? = try {
            MediaStore.getExternalVolumeNames(context).sorted().joinToString(";") { volume ->
                "$volume:${MediaStore.getVersion(context, volume)}:${MediaStore.getGeneration(context, volume)}"
            }
        } catch (_: IllegalArgumentException) {
            // A volume went away between the two calls.
            null
        } catch (_: SecurityException) {
            null
        }

        /** MediaStore's notes on its songs, from a ContentObserver while someone listens. */
        private fun changesOf(resolver: ContentResolver): Flow<Unit> = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.conflate()
    }
}
