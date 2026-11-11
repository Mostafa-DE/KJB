package app.lyrify.backup

import app.lyrify.library.Song
import app.lyrify.lyrics.TrackNames
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Finds a backup's songs on this phone, which may be a new one where every MediaStore id and even the folders differ
 * (user feedback 2026-11-11, point 23). Each song the backup names is looked for in three passes, each only among the
 * songs the earlier passes left:
 *
 * 1. the same absolute path (a restore on the same phone);
 * 2. the same path under the storage root ("Music/Harbour Glass/01 Paper Lantern.mp3"), whatever the volume, case and
 *    Unicode form aside: the Music folder copied to the new phone;
 * 3. the same title, artist and album (case, accents and punctuation aside) and a length within [LENGTH_TOLERANCE_MS]:
 *    the songs moved to other folders. When several songs of the phone fit, the one with the same size in bytes is taken
 *    if exactly one has it.
 *
 * A path only finds a song the backup knows the tags of, and only when the length agrees and so does the title, the
 * artist or the size ([samePlace]): another song put at that path is not taken, and the one that moved is still found
 * by its tags. Never a guess: a pass that finds several songs, or a song of the phone that two songs of the backup would
 * take, matches none of them, and those songs of the phone are out of the later passes too. A song found neither way is
 * listed as not found.
 */
class SongMatcher(phone: List<Song>) {
    private val byPath: Map<String, Song> = phone.associateBy { nfc(it.path) }
    private val byRelative: Map<String, List<Song>> = phone.groupBy { relativeKey(it.folder + it.fileName) }
    private val byTitle: Map<String, List<Song>> = phone.groupBy { TrackNames.normalize(it.title) }

    /**
     * Each of [paths] (as the backup names its songs) with the song it is on this phone; a path not found is left out.
     * [known] holds what the backup knows of each song; a song it knows nothing of is not found.
     */
    fun match(paths: Collection<String>, known: Map<String, BackupSong>): Map<String, Song> {
        val wanted = paths.distinct().mapNotNull { path -> known[path]?.let { path to it } }
        val found = HashMap<String, Song>()
        // Songs of the phone taken, found twice by one pass, or wanted by two songs of the backup in one pass.
        val out = HashSet<String>()
        fun pass(find: (String, BackupSong) -> List<Song>) {
            val finds = wanted.filter { (path, _) -> path !in found }.map { (path, song) -> path to find(path, song).filter { it.path !in out } }
            finds.filter { it.second.size > 1 }.forEach { (_, songs) -> songs.forEach { out += it.path } }
            val claims = finds.mapNotNull { (path, songs) -> songs.singleOrNull()?.takeIf { it.path !in out }?.let { path to it } }
            claims.groupBy { it.second.path }.values.forEach { same ->
                out += same.first().second.path
                same.singleOrNull()?.let { (path, song) -> found[path] = song }
            }
        }
        pass { path, song -> listOfNotNull(byPath[nfc(path)]).filter { samePlace(song, it) } }
        pass { path, song -> byRelative[relativeKey(song.relativePath ?: relativeToRoot(path) ?: return@pass emptyList())].orEmpty().filter { samePlace(song, it) } }
        pass { _, song -> byTags(song, out) }
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

    /**
     * Whether [phone], at [song]'s path, is that song: the same length, and the same title, or artist, or size in bytes,
     * as far as the backup knows them (a retag changes the title or the size, seldom the artist with them).
     */
    private fun samePlace(song: BackupSong, phone: Song): Boolean = sameLength(song, phone) &&
        (same(song.title, phone.title) || (song.artist != null && same(song.artist, phone.artist)) || (song.sizeBytes != null && song.sizeBytes == phone.sizeBytes))

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

        /** A path below the root as compared: in one Unicode form (NFC), case aside. */
        private fun relativeKey(path: String): String = nfc(path).trim('/').lowercase(Locale.ROOT)

        /** [path] in Unicode's composed form: a path copied from another system may come decomposed (NFD). */
        private fun nfc(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC)
    }
}
