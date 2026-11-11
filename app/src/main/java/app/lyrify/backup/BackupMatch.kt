package app.lyrify.backup

import app.lyrify.library.Song
import app.lyrify.lyrics.TrackNames
import java.util.Locale
import kotlin.math.abs

/**
 * Finds a backup's songs on this phone, which may be a new one where every MediaStore id and even the folders differ
 * (user feedback 2026-11-11, point 23). Each song the backup names is looked for in three passes, each only among the
 * songs the earlier passes left:
 *
 * 1. the same absolute path (a restore on the same phone);
 * 2. the same path under the storage root ("Music/Harbour Glass/01 Paper Lantern.mp3"), whatever the volume, case aside:
 *    the Music folder copied to the new phone;
 * 3. the same title, artist and album (case, accents and punctuation aside) and a length within [LENGTH_TOLERANCE_MS]:
 *    the songs moved to other folders. When several songs of the phone fit, the one with the same size in bytes is taken
 *    if exactly one has it.
 *
 * The first two passes need the length to agree too, when the backup knows it, so another song at the same path is not
 * taken. Never a guess: a pass that finds several songs, or a song of the phone that two songs of the backup would take,
 * matches none of them, and that song of the phone is out of the later passes too. A song found neither way is listed
 * as not found.
 */
class SongMatcher(phone: List<Song>) {
    private val byPath: Map<String, Song> = phone.associateBy { it.path }
    private val byRelative: Map<String, List<Song>> = phone.groupBy { relativeKey(it.folder + it.fileName) }
    private val byTitle: Map<String, List<Song>> = phone.groupBy { TrackNames.normalize(it.title) }

    /**
     * Each of [paths] (as the backup names its songs) with the song it is on this phone; a path not found is left out.
     * [known] holds what the backup knows of each song: without it, only the same absolute path finds it.
     */
    fun match(paths: Collection<String>, known: Map<String, BackupSong>): Map<String, Song> {
        val wanted = paths.distinct()
        val found = HashMap<String, Song>()
        // Songs of the phone taken, or wanted by two songs of the backup in one pass.
        val out = HashSet<String>()
        fun pass(find: (String, BackupSong?) -> List<Song>) {
            val claims = wanted.filter { it !in found }.mapNotNull { path ->
                find(path, known[path]).filter { it.path !in out }.singleOrNull()?.let { path to it }
            }
            claims.groupBy { it.second.path }.values.forEach { same ->
                out += same.first().second.path
                same.singleOrNull()?.let { (path, song) -> found[path] = song }
            }
        }
        pass { path, song -> listOfNotNull(byPath[path]).filter { song == null || sameLength(song, it) } }
        pass { path, song -> if (song == null) emptyList() else byRelative[relativeKey(song.relativePath ?: relativeToRoot(path) ?: return@pass emptyList())].orEmpty().filter { sameLength(song, it) } }
        pass { _, song -> if (song == null) emptyList() else byTags(song, out) }
        return found
    }

    /** The songs with [song]'s title, artist, album and length; of several, the one of its size when only one has it. */
    private fun byTags(song: BackupSong, out: Set<String>): List<Song> {
        val title = TrackNames.normalize(song.title).takeIf(String::isNotEmpty) ?: return emptyList()
        val fits = byTitle[title].orEmpty().filter { it.path !in out && same(song.artist, it.artist) && same(song.album, it.album) && sameLength(song, it) }
        if (fits.size < 2) return fits
        val size = song.sizeBytes?.takeIf { it > 0 } ?: return fits
        return fits.filter { it.sizeBytes == size }.takeIf { it.size == 1 } ?: fits
    }

    private fun same(a: String?, b: String?): Boolean = a?.let(TrackNames::normalize).orEmpty() == b?.let(TrackNames::normalize).orEmpty()

    private fun sameLength(song: BackupSong, phone: Song): Boolean = abs(song.durationMs - phone.durationMs) <= LENGTH_TOLERANCE_MS

    companion object {
        const val LENGTH_TOLERANCE_MS: Long = 2_000

        /**
         * [path] below its storage root, as MediaStore's RELATIVE_PATH and the file name make it: `/storage/emulated/0/` or
         * `/storage/<volume>/` taken off ("Music/Rips/01 Song.mp3"); null for any other form of path.
         */
        fun relativeToRoot(path: String): String? {
            val names = path.split('/')
            if (names.size < 5 || names[0].isNotEmpty() || names[1] != "storage") return null
            val skip = when (names[2]) {
                "emulated" -> if (names[3].isNotEmpty() && names[3].all(Char::isDigit)) 4 else return null
                "", "self" -> return null
                else -> 3
            }
            return names.drop(skip).joinToString("/").takeIf(String::isNotEmpty)
        }

        private fun relativeKey(path: String): String = path.trim('/').lowercase(Locale.ROOT)
    }
}
