package app.lyrify.library

import kotlinx.serialization.Serializable

/**
 * A song as queues, playlists, Favourites and plays keep it: by its absolute [path], with MediaStore's id as a hint only,
 * because ids change when a file moves or MediaStore rebuilds. library/SongResolver.kt (milestone LC) finds the song
 * again from one.
 */
@Serializable
data class SongRef(val path: String, val mediaStoreId: Long? = null)

/** A song in the library, as library/LibraryReader.kt (milestone LC) reads it from MediaStore; a tag the file lacks is null. */
data class Song(
    val ref: SongRef,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long,
    /** The track number within its disc. */
    val track: Int?,
    val disc: Int?,
    val year: Int?,
    val genre: String?,
    /** The folder relative to its volume, as MediaStore's RELATIVE_PATH has it ("Music/Rips/"). */
    val folder: String,
    /** When MediaStore added the file, wall-clock millis: "Recently added". */
    val addedAt: Long?,
    /** When the file last changed, wall-clock millis: covers are cached by it. */
    val modifiedAt: Long?,
    val sizeBytes: Long,
    val mimeType: String?,
) {
    val path: String get() = ref.path

    /** The file's name with its extension, for "Show file names". */
    val fileName: String get() = path.substringAfterLast('/')
}
