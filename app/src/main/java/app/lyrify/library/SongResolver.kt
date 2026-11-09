package app.lyrify.library

import app.lyrify.lyrics.TrackNames
import java.util.Locale
import kotlin.math.abs

/**
 * What is known about a song to find it again: a [SongRef]'s path and id hint, an m3u line (its path and `#EXTINF`), or a
 * backup's entry (its path, title, artist and length). [path] may come from another phone or computer.
 */
data class SongClue(
    val path: String? = null,
    val mediaStoreId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
)

/** The ways to find a song again, always tried in this order; each caller takes the ones its spec allows. */
enum class FindBy {
    /** The same absolute path. */
    PATH,

    /**
     * The same MediaStore id on the same volume, for a song renamed in its folder or moved with its name. This phone's
     * own data only: ids differ between phones and volumes, and MediaStore gives them out again when it rebuilds, so a
     * song on another volume, or with another name in another folder, is not taken.
     */
    ID_HINT,

    /** The same file name in a folder of the same name, anywhere ("Harbour Glass/01 Paper Lantern.mp3"), case aside. */
    NAME_IN_FOLDER,

    /** The same title and artist, case, accents and punctuation aside, and a length within [SongResolver.LENGTH_TOLERANCE_MS]. */
    TAGS,
}

/**
 * Finds songs again among [songs] (the library's [Library.all]) for queues, playlists, Favourites, m3u import and backup
 * restore. A way that finds several songs finds none, and the next way is tried: a guess between copies is no answer.
 */
class SongResolver(private val songs: List<Song>) {
    private val byPath: Map<String, Song> by lazy { songs.associateBy { it.path } }
    /** By volume and MediaStore id: an id names a file on one volume only. */
    private val byId: Map<Pair<String, Long>, List<Song>> by lazy {
        songs.mapNotNull { song -> song.ref.mediaStoreId?.let { id -> volumeOf(song.path)?.let { (it to id) to song } } }
            .groupBy({ it.first }, { it.second })
    }
    private val byNameInFolder: Map<String, List<Song>> by lazy {
        songs.mapNotNull { song -> nameInFolder(song.path)?.let { it to song } }.groupBy({ it.first }, { it.second })
    }
    private val byTitle: Map<String, List<Song>> by lazy { songs.groupBy { TrackNames.normalize(it.title) } }

    /** A song a queue, a playlist or Favourites kept: by its path, then its id hint ([IN_APP]). */
    fun find(ref: SongRef): Song? = find(SongClue(ref.path, ref.mediaStoreId), IN_APP)

    /** The one song [clue] names, trying the ways in [by] in [FindBy]'s order; null when none finds exactly one. */
    fun find(clue: SongClue, by: Set<FindBy>): Song? = FindBy.entries.filter { it in by }.firstNotNullOfOrNull { step ->
        when (step) {
            FindBy.PATH -> clue.path?.let(byPath::get)
            FindBy.ID_HINT -> byIdHint(clue)
            FindBy.NAME_IN_FOLDER -> clue.path?.let(::nameInFolder)?.let { byNameInFolder[it] }?.singleOrNull()
            FindBy.TAGS -> byTags(clue)
        }
    }

    /** Without a path there is no volume to look on, so nothing. */
    private fun byIdHint(clue: SongClue): Song? {
        val path = clue.path ?: return null
        val id = clue.mediaStoreId ?: return null
        val volume = volumeOf(path) ?: return null
        return byId[volume to id].orEmpty().filter { renamedOrMoved(path, it.path) }.singleOrNull()
    }

    private fun byTags(clue: SongClue): Song? {
        val title = clue.title?.let(TrackNames::normalize)?.takeIf(String::isNotEmpty) ?: return null
        val duration = clue.durationMs ?: return null
        val artist = clue.artist?.let(TrackNames::normalize)
        return byTitle[title].orEmpty().filter { song ->
            song.artist?.let(TrackNames::normalize) == artist && abs(song.durationMs - duration) <= LENGTH_TOLERANCE_MS
        }.singleOrNull()
    }

    companion object {
        const val LENGTH_TOLERANCE_MS: Long = 2_000

        /** Queues, playlists and Favourites: this phone's own data. */
        val IN_APP: Set<FindBy> = setOf(FindBy.PATH, FindBy.ID_HINT)

        /** m3u import: the path, the file name in its folder, then `#EXTINF`'s title, artist and length. */
        val M3U: Set<FindBy> = setOf(FindBy.PATH, FindBy.NAME_IN_FOLDER, FindBy.TAGS)

        /** Backup restore: the path, then title, artist and length. */
        val BACKUP: Set<FindBy> = setOf(FindBy.PATH, FindBy.TAGS)

        /** On the same volume: the same name in another folder, or another name in the same folder; not both. */
        private fun renamedOrMoved(old: String, now: String): Boolean {
            val volume = volumeOf(old) ?: return false
            if (volume != volumeOf(now)) return false
            return old.substringAfterLast('/') == now.substringAfterLast('/') || old.substringBeforeLast('/') == now.substringBeforeLast('/')
        }

        /**
         * The volume [path] is on, as MediaStore's paths name it: `/storage/emulated/<user>` or `/storage/<volume id>`;
         * null for any other form of path.
         */
        private fun volumeOf(path: String): String? {
            val names = path.split('/')
            if (names.size < 4 || names[0].isNotEmpty() || names[1] != "storage") return null
            return when (names[2]) {
                "emulated" -> names[3].takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { "/storage/emulated/$it" }
                "", "self" -> null
                else -> "/storage/${names[2]}"
            }
        }

        /** "folder/file" in lower case, from a path with `/` or `\` separators; null without a folder. */
        private fun nameInFolder(path: String): String? {
            val names = path.split('/', '\\').filter(String::isNotEmpty)
            return if (names.size < 2) null else "${names[names.size - 2]}/${names.last()}".lowercase(Locale.ROOT)
        }
    }
}
