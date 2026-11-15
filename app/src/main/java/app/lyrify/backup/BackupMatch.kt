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
 *
 * An import from another app ([find]) knows less of each song and may name one song twice, so it is matched song by
 * song, see [find].
 */
class SongMatcher(phone: List<Song>) {
    private val byPath: Map<String, Song> = phone.associateBy { nfc(it.path) }
    private val byRelative: Map<String, List<Song>> = phone.groupBy { relativeKey(it.folder + it.fileName) }
    private val byTitle: Map<String, List<Song>> = phone.groupBy { TrackNames.normalize(it.title) }

    /** By MediaStore id: a Musicolet backup's songs, for imports only. */
    private val byId: Map<Long, List<Song>> by lazy { phone.mapNotNull { song -> song.ref.mediaStoreId?.let { it to song } }.groupBy({ it.first }, { it.second }) }

    /** "folder/file", case aside: m3u's rule 2, for imports only. */
    private val byNameInFolder: Map<String, List<Song>> by lazy { phone.mapNotNull { song -> nameInFolder(song.path)?.let { it to song } }.groupBy({ it.first }, { it.second }) }

    /** The file name alone, case aside: [byUniqueName], for imports only. */
    private val byFileName: Map<String, List<Song>> by lazy { phone.groupBy { relativeKey(it.fileName) } }

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

    /**
     * The one song of the phone an import's [song] names (an m3u, pls or xspf line, a phone playlist's song), in four
     * passes; a pass that finds several songs gives way to the next, so it is never a guess. Unlike a backup's, two
     * songs of an import may be the same song of the phone (two files, two forms of one path), and both find it.
     *
     * 0. The same MediaStore id (a Musicolet backup from this phone), only for a file of the same name.
     * 1. The same absolute path: taken as it is when the file says nothing of the song but its length (an m3u line
     *    without `#EXTINF`, as m3u import always did), else only when it agrees ([fits]).
     * 2. The same path below the storage root ([relativeToRoot]); for a root this phone does not know (a computer's, a
     *    Windows drive's), the longest end of the path, two names at least, that is a song's path below its root. A
     *    path below the root the file gives apart ([ImportSong.relativePath], Musicolet's) is taken as it is.
     * 3. The same file name in a folder of the same name, anywhere (m3u's rule 2).
     * 4. The same title, artist and album as far as the file knows them, and a length within [LENGTH_TOLERANCE_MS];
     *    the length is needed. An "Artist - Title" text is read two ways, as m3u import reads it ([M3u.findByName]).
     *    Of several, the one of the file's size when only one has it.
     *
     * Passes 0 and 2 to 4 take a song only when it [fits]. Missing clues are skipped, never a mismatch.
     */
    fun find(song: ImportSong): Song? {
        val path = song.path?.let(::nfc)
        val relative = song.relativePath?.let(::nfc)
        song.mediaStoreId?.let { id ->
            val name = (relative ?: path)?.substringAfterLast('/')
            byId[id].orEmpty().filter { nfc(it.fileName) == name && fits(song, it) }.singleOrNull()?.let { return it }
        }
        path?.let { byPath[it] }?.takeIf { identityKnown(song).not() || fits(song, it) }?.let { return it }
        val below = if (relative != null) byRelative[relativeKey(relative)].orEmpty() else path?.let(::below).orEmpty()
        below.filter { fits(song, it) }.singleOrNull()?.let { return it }
        (path ?: relative)?.let(::nameInFolder)?.let { byNameInFolder[it] }.orEmpty().filter { fits(song, it) }.singleOrNull()?.let { return it }
        return readings(song).mapNotNull { (artist, title) -> byTags(song, title, artist) }.distinct().singleOrNull()
    }

    /**
     * Pass 5, an import's last resort for a song moved to another folder since the file was made: the one song of the
     * phone with [song]'s file name, extension and all, case aside, when it [fits] and an `#EXTINF` text is its title
     * ([titled]); none when two or more songs have that name. A name alone can still be a guess, so [ImportPlan] takes
     * it only for a song no other pass finds, and never for a song another of the import's songs found or wants.
     */
    fun byUniqueName(song: ImportSong): Song? {
        val name = (song.path ?: song.relativePath)?.substringAfterLast('/')?.takeIf(String::isNotEmpty) ?: return null
        return byFileName[relativeKey(name)]?.singleOrNull()?.takeIf { fits(song, it) && (song.title != null || song.info == null || titled(song.info, it)) }
    }

    /** Pass 2: the songs at [path] below the storage root, or at the longest end of a path whose root is unknown. */
    private fun below(path: String): List<Song> {
        val relative = if (path.startsWith('/')) relativeToRoot(path) else null
        if (relative != null) return byRelative[relativeKey(relative)].orEmpty()
        val names = path.split('/').filter(String::isNotEmpty)
        return (names.size downTo 2).asSequence().map { byRelative[relativeKey(names.takeLast(it).joinToString("/"))].orEmpty() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    /** The (artist, title) pairs [song] may mean: its own, else its "Artist - Title" text read two ways. */
    private fun readings(song: ImportSong): List<Pair<String?, String>> {
        song.title?.let { return listOf(song.artist to it) }
        val info = song.info ?: return emptyList()
        val dash = info.indexOf(DASH)
        val split = if (dash < 0) null else info.substring(0, dash).trim() to info.substring(dash + DASH.length).trim()
        return listOfNotNull(split, null to info)
    }

    /** Pass 4 for one reading: the one song with its title, its artist and album when known, and its length; the size settles a tie. */
    private fun byTags(song: ImportSong, title: String, artist: String?): Song? {
        val length = song.durationMs ?: return null
        val key = TrackNames.normalize(title).takeIf(String::isNotEmpty) ?: return null
        val fits = byTitle[key].orEmpty().filter { phone ->
            abs(length - phone.durationMs) <= LENGTH_TOLERANCE_MS && (artist == null || same(artist, phone.artist)) && (song.album == null || same(song.album, phone.album))
        }
        if (fits.size < 2) return fits.singleOrNull()
        val size = song.sizeBytes?.takeIf { it > 0 } ?: return null
        return fits.singleOrNull { it.sizeBytes == size }
    }

    /** Whether the file says who or what [song] is (its title, artist, size, or an `#EXTINF` or `TitleN` text), not only its length. */
    private fun identityKnown(song: ImportSong): Boolean = song.title != null || song.artist != null || song.sizeBytes != null || song.info != null

    /**
     * Whether [phone] can be [song], as far as the file knows it: the same length within [LENGTH_TOLERANCE_MS], and no
     * clue the file gives against it: its title, artist and size, each when known, are [phone]'s, and an "Artist -
     * Title" text without them names [phone]'s title, in whatever order or form ([names]). A path whose line names
     * another song is not that song's (review panel-im1): the tags then decide.
     */
    private fun fits(song: ImportSong, phone: Song): Boolean {
        if (song.durationMs != null && abs(song.durationMs - phone.durationMs) > LENGTH_TOLERANCE_MS) return false
        if (song.title != null && !same(song.title, phone.title)) return false
        if (song.artist != null && !same(song.artist, phone.artist)) return false
        if (song.sizeBytes != null && song.sizeBytes != phone.sizeBytes) return false
        return song.title != null || song.info == null || names(song.info, phone)
    }

    /** Whether [info] ("Artist - Title", "Title - Artist", the title alone) holds [phone]'s title as whole words, case, accents and punctuation aside. */
    private fun names(info: String, phone: Song): Boolean {
        val title = TrackNames.normalize(phone.title).takeIf(String::isNotEmpty) ?: return false
        return " ${TrackNames.normalize(info)} ".contains(" $title ")
    }

    /**
     * Whether [info] is [phone]'s title and no more: the whole text, or one side of its first " - ", case, accents and
     * punctuation aside. Pass 5's check, stricter than [names], as a name alone is a guess: "Ember" is not "Ember Gate"
     * (review panel-im3).
     */
    private fun titled(info: String, phone: Song): Boolean {
        val title = TrackNames.normalize(phone.title).takeIf(String::isNotEmpty) ?: return false
        val dash = info.indexOf(DASH)
        val sides = if (dash < 0) emptyList() else listOf(info.substring(0, dash), info.substring(dash + DASH.length))
        return (sides + info).any { TrackNames.normalize(it) == title }
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
         * [path] below its storage root, as MediaStore's RELATIVE_PATH and the file name make it: `/storage/emulated/0/`
         * (any user), `/storage/<volume>/` (an SD card, whatever its id), or the other names the phone's own storage goes
         * by (`/sdcard/`, `/mnt/sdcard/`, `/storage/self/primary/`, `/storage/emulated/legacy/`) taken off ("Music/Rips/01
         * Song.mp3"); null for any other form of path.
         */
        fun relativeToRoot(path: String): String? {
            val names = path.split('/')
            if (names.size < 3 || names[0].isNotEmpty()) return null
            val skip = when (names[1]) {
                "storage" -> when (names.getOrNull(2)) {
                    "emulated" -> if (names.getOrNull(3)?.let { it == LEGACY || (it.isNotEmpty() && it.all(Char::isDigit)) } == true) 4 else return null
                    "self" -> if (names.getOrNull(3) == PRIMARY) 4 else return null
                    "", null -> return null
                    else -> 3
                }
                // The names older Android and other apps give the phone's own storage.
                SDCARD -> 2
                "mnt" -> if (names[2] == SDCARD) 3 else return null
                else -> return null
            }
            return names.drop(skip).joinToString("/").takeIf { names.size > skip && it.isNotEmpty() }
        }

        /** "folder/file" in lower case, NFC, from a path with `/` separators; null without a folder. */
        private fun nameInFolder(path: String): String? {
            val names = path.split('/').filter(String::isNotEmpty)
            return if (names.size < 2) null else relativeKey("${names[names.size - 2]}/${names.last()}")
        }

        private const val DASH: String = " - "
        private const val SDCARD: String = "sdcard"
        private const val LEGACY: String = "legacy"
        private const val PRIMARY: String = "primary"

        /** A path below the root as compared: in one Unicode form (NFC), case aside. */
        private fun relativeKey(path: String): String = nfc(path).trim('/').lowercase(Locale.ROOT)

        /** [path] in Unicode's composed form: a path copied from another system may come decomposed (NFD). */
        private fun nfc(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC)
    }
}
