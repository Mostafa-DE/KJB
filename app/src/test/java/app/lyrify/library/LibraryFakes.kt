package app.lyrify.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** The phone's main volume, as MediaStore's paths start. */
const val VOLUME: String = "/storage/emulated/0/"

/**
 * A made-up song at [relativePath] on [VOLUME] ("Music/Harbour Glass/01 Paper Lantern.mp3"); its folder is the path's,
 * and its title the file's name unless given.
 */
fun song(
    relativePath: String,
    title: String = relativePath.substringAfterLast('/').substringBeforeLast('.'),
    artist: String? = "Kit Calloway",
    album: String? = null,
    albumArtist: String? = null,
    durationMs: Long = 200_000,
    track: Int? = null,
    disc: Int? = null,
    year: Int? = null,
    genre: String? = null,
    addedAt: Long? = null,
    id: Long? = null,
    albumId: Long? = null,
    isAudiobook: Boolean = false,
): Song = Song(
    ref = SongRef(VOLUME + relativePath, id),
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    durationMs = durationMs,
    track = track,
    disc = disc,
    year = year,
    genre = genre,
    folder = relativePath.substringBeforeLast('/', missingDelimiterValue = "") + "/",
    addedAt = addedAt,
    modifiedAt = addedAt,
    sizeBytes = 4_000_000,
    mimeType = "audio/mpeg",
    albumId = albumId,
    isAudiobook = isAudiobook,
)

/** MediaStore made up: [result] for every read, [stamp] as its version, [changes] sent by the test. */
class FakeLibrarySource(var result: SongsRead = SongsRead.Songs(emptyList()), var stampNow: String? = "v1:1") : LibrarySource {
    var reads: Int = 0
        private set

    val signals: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 64)

    override val changes: Flow<Unit> = signals

    override fun read(): SongsRead {
        reads++
        return result
    }

    override fun stamp(): String? = stampNow
}
