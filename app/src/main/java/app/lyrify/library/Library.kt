package app.lyrify.library

import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortAlbums
import app.lyrify.settings.SortArtists
import app.lyrify.settings.SortSongs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.text.Collator
import java.text.Normalizer

/**
 * An album: the songs with one album tag and one album artist (a song without an album artist counts its artist), in
 * whatever folders they are, so sibling "CD1" and "CD2" folders make one album with two discs. Different album artists
 * never merge. MediaStore's ALBUM_ID plays no part ([Song.albumId]).
 */
data class Album(
    /** Its key in the library and in Favourites ([albumKey]). */
    val key: String,
    val title: String,
    /** The album artist, or the songs' artist when they have none; null when they have neither. */
    val artist: String?,
    /** In disc order, then by folder (an untagged "CD2" folder after "CD1"), then by track number, then by title. */
    val songs: List<Song>,
    /** The year most of its songs have, the latest of equals; null when none has one. */
    val year: Int?,
) {
    val durationMs: Long get() = songs.sumOf { it.durationMs.coerceAtLeast(0) }
}

/**
 * An artist, by the artist tag as written: its [songs] (the newest album first, each in track order, songs without an
 * album last) and the [albums] it is the album artist of (the newest first).
 */
data class Artist(val name: String, val songs: List<Song>, val albums: List<Album>)

/** A genre, by its tag as written, with its songs in path order. */
data class Genre(val name: String, val songs: List<Song>)

/**
 * A folder that holds music, itself or below it. [path] is relative to its volume without the last slash ("Music/Rips",
 * as "Folders to skip" has it), so the phone's and a card's "Music" are one folder; the root's is "". Its [songs] are by
 * file name and its [folders] by name.
 */
class Folder(val path: String, val name: String, val songs: List<Song>, val folders: List<Folder>) {
    /** What Play plays: in path order, its own songs, then each subfolder's in turn. */
    val allSongs: List<Song> by lazy { songs + folders.flatMap { it.allSongs } }

    /** Its subfolders as the list shows them: a chain of folders with no songs of their own and one subfolder each is one row. */
    val rows: List<FolderRow> get() = folders.map(::collapse)

    private fun collapse(start: Folder): FolderRow {
        val names = mutableListOf(start.name)
        var at = start
        while (at.songs.isEmpty() && at.folders.size == 1) {
            at = at.folders.single()
            names += at.name
        }
        return FolderRow(names, at)
    }
}

/** One row of a folder list: the [names] from the folder shown to [folder], the one it opens ("Music › Rips"). */
data class FolderRow(val names: List<String>, val folder: Folder)

/**
 * The phone's songs as the library shows them, built on Dispatchers.Default by [of] whenever MediaStore's songs or the
 * user's filters change. Everything here is in a fixed order; lists re-sort with [sortSongs], [sortAlbums] and
 * [sortArtists].
 */
class Library private constructor(
    /** Every song MediaStore lists (a debug build: its test folder only), short clips and skipped folders too. */
    val all: List<Song>,
    /** The library's songs, in path order ([Folder.allSongs]): [all] without short clips and skipped folders. */
    val songs: List<Song>,
    /** By title. */
    val albums: List<Album>,
    /** "Unknown album": the songs without an album tag, in path order. */
    val unknownAlbum: List<Song>,
    /** By name. */
    val artists: List<Artist>,
    /** By name. */
    val genres: List<Genre>,
    /** The Folders tab: [Folder.rows] of the root. */
    val root: Folder,
) {
    private val byPath: Map<String, Song> = songs.associateBy { it.path }
    private val albumsByKey: Map<String, Album> = albums.associateBy { it.key }
    private val artistsByName: Map<String, Artist> = artists.associateBy { it.name }
    private val genresByName: Map<String, Genre> = genres.associateBy { it.name }

    /** Finds songs again among [all], hidden ones too: a queue or playlist keeps a song the filters hide. */
    val resolver: SongResolver by lazy { SongResolver(all) }

    fun album(key: String): Album? = albumsByKey[key]

    fun artist(name: String): Artist? = artistsByName[name]

    fun genre(name: String): Genre? = genresByName[name]

    /** The folder at [path] (as [Folder.path] has it); null when it holds no song of the library. */
    fun folder(path: String): Folder? = path.split('/').filter(String::isNotEmpty).fold(root as Folder?) { at, name ->
        at?.folders?.firstOrNull { it.name == name }
    }

    /** The library's song at [path]; null when there is none, or the filters hide it. */
    fun song(path: String): Song? = byPath[path]

    fun shows(song: Song): Boolean = byPath[song.path] == song

    /** The songs [refs] name, found again in their order: those missing from the phone or hidden by the filters left out. */
    fun listed(refs: List<SongRef>): List<Song> = refs.mapNotNull { ref -> resolver.find(ref)?.takeIf(::shows) }

    companion object {
        /** "Skip short clips": songs shorter than this leave the library. */
        const val SHORT_CLIP_MS: Long = 30_000

        /** The library of [all] under [settings]' "Skip short clips" and "Folders to skip". */
        fun of(all: List<Song>, settings: Settings): Library {
            val collator = collator()
            val root = folderOf("", "", all.filter { inLibrary(it, settings) }, collator)
            val songs = root.allSongs
            val byAlbum = songs.groupBy(::albumKey)
            val albums = byAlbum.mapNotNull { (key, its) ->
                if (key == null) return@mapNotNull null
                val first = its.first()
                Album(
                    key = key,
                    title = checkNotNull(text(first.album)),
                    artist = albumArtistOf(first),
                    songs = its.sortedWith(trackOrder(collator)),
                    year = its.mapNotNull { it.year }.groupingBy { it }.eachCount().entries
                        .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key,
                )
            }.let { sortAlbums(it, SortAlbums.TITLE) }
            val albumOf = albums.flatMap { album -> album.songs.map { it.path to album } }.toMap()
            val artists = songs.groupBy { text(it.artist) }.mapNotNull { (name, its) ->
                if (name == null) return@mapNotNull null
                Artist(
                    name = name,
                    songs = its.sortedWith(
                        compareBy<Song, Int?>(nullsLast(reverseOrder())) { albumOf[it.path]?.year ?: it.year }
                            .thenBy(nullsLast(collator)) { albumOf[it.path]?.title }
                            .then(trackOrder(collator)),
                    ),
                    albums = albums.filter { it.artist == name }
                        .sortedWith(compareBy<Album, Int?>(nullsLast(reverseOrder())) { it.year }.thenBy(collator) { it.title }),
                )
            }.let { sortArtists(it, SortArtists.NAME) }
            val genres = songs.groupBy { text(it.genre) }
                .mapNotNull { (name, its) -> name?.let { Genre(it, its) } }
                .sortedWith(compareBy(nameOrder(collator)) { it.name })
            return Library(all, songs, albums, byAlbum[null].orEmpty(), artists, genres, root)
        }

        /** The folder [name] at [path] holding [songs] at or below it ([Song.folder] relative to its volume). */
        private fun folderOf(path: String, name: String, songs: List<Song>, collator: Collator): Folder {
            val depth = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
            val (own, below) = songs.partition { folderNames(it).size == depth }
            // Sorted, not a sorted map: names the collator calls equal must not merge.
            val folders = below.groupBy { folderNames(it)[depth] }.entries
                .sortedWith(compareBy<Map.Entry<String, List<Song>>, String>(collator) { it.key }.thenBy { it.key })
                .map { (child, its) -> folderOf(if (path.isEmpty()) child else "$path/$child", child, its, collator) }
            return Folder(path, name, own.sortedWith(compareBy(collator) { it.fileName }), folders)
        }

        private fun folderNames(song: Song): List<String> = song.folder.split('/').filter(String::isNotEmpty)
    }
}

/**
 * The key of [song]'s album in the library and in Favourites: its album and album artist (its artist without one), as
 * written but trimmed. Null for a song without an album tag ("Unknown album").
 */
fun albumKey(song: Song): String? {
    val title = text(song.album) ?: return null
    return JsonArray(listOf(JsonPrimitive(albumArtistOf(song)), JsonPrimitive(title))).toString()
}

/** Whether [song] is in the library under [settings]: not a short clip (unless shown) and not in a folder to skip. */
fun inLibrary(song: Song, settings: Settings): Boolean {
    // MediaStore gives 0 when it could not read the length: that is not a short clip.
    if (settings.playback.skipShortClips && song.durationMs in 1 until Library.SHORT_CLIP_MS) return false
    val folder = song.folder.trimEnd('/')
    return settings.library.foldersToSkip.none { skip ->
        val skipped = skip.trim('/')
        folder.equals(skipped, ignoreCase = true) || folder.startsWith("$skipped/", ignoreCase = true)
    }
}

/** What every surface shows for [song]'s title: its file name under "Show file names", else its title. */
fun displayTitle(song: Song, settings: LibrarySettings): String = if (settings.showFileNames) song.fileName else song.title

/**
 * [songs] in the Songs tab's [sort]: by title as shown ([displayTitle]); by artist, then the newest year, album and track;
 * by album, then album artist and track; or the latest added first. Names in [nameOrder]; missing tags go last; ties keep
 * path order.
 */
fun sortSongs(songs: List<Song>, sort: SortSongs, settings: LibrarySettings): List<Song> {
    val collator = collator()
    val names = nameOrder(collator)
    val byTitle = compareBy<Song, String>(names) { displayTitle(it, settings) }
    val order = when (sort) {
        SortSongs.TITLE -> byTitle
        SortSongs.ARTIST -> compareBy<Song, String?>(nullsLast(names)) { text(it.artist) }
            .thenBy(nullsLast(reverseOrder())) { it.year }
            .thenBy(nullsLast(names)) { text(it.album) }
            .then(trackOrder(collator))
        SortSongs.ALBUM -> compareBy<Song, String?>(nullsLast(names)) { text(it.album) }
            .thenBy(nullsLast(names)) { albumArtistOf(it) }
            .then(trackOrder(collator))
        SortSongs.RECENTLY_ADDED -> compareBy<Song, Long?>(nullsLast(reverseOrder())) { it.addedAt }.then(byTitle)
    }
    return songs.sortedWith(order)
}

/** [albums] by title, by artist (then title), or the newest year first (then title), names in [nameOrder]; missing ones go last. */
fun sortAlbums(albums: List<Album>, sort: SortAlbums): List<Album> {
    val names = nameOrder(collator())
    val byTitle = compareBy<Album, String>(names) { it.title }.thenBy(nullsLast(names)) { it.artist }
    val order = when (sort) {
        SortAlbums.TITLE -> byTitle
        SortAlbums.ARTIST -> compareBy<Album, String?>(nullsLast(names)) { it.artist }.then(byTitle)
        SortAlbums.YEAR -> compareBy<Album, Int?>(nullsLast(reverseOrder())) { it.year }.then(byTitle)
    }
    return albums.sortedWith(order)
}

/** [artists] by name, or the most songs first (then name), names in [nameOrder]. */
fun sortArtists(artists: List<Artist>, sort: SortArtists): List<Artist> {
    val byName = compareBy<Artist, String>(nameOrder(collator())) { it.name }
    val order = when (sort) {
        SortArtists.NAME -> byName
        SortArtists.MOST_SONGS -> compareByDescending<Artist> { it.songs.size }.then(byName)
    }
    return artists.sortedWith(order)
}

/** Within an album: by disc (none is 1), then folder, then track number (none last), then title. */
private fun trackOrder(collator: Collator): Comparator<Song> =
    compareBy<Song> { it.disc ?: 1 }
        .thenBy(collator) { it.folder }
        .thenBy { it.track ?: Int.MAX_VALUE }
        .thenBy(collator) { it.title }

/** The album artist, or the artist without one. */
private fun albumArtistOf(song: Song): String? = text(song.albumArtist) ?: text(song.artist)

/** A tag trimmed; null when blank. */
private fun text(tag: String?): String? = tag?.trim()?.takeIf(String::isNotEmpty)

/** Names in the phone's language order, case and accents second. A new one per call, so none is shared between threads. */
private fun collator(): Collator = Collator.getInstance()

/**
 * The heading [name] files under in an A to Z list, from its first letter or digit (leading punctuation, brackets and
 * symbols skipped, so "(Don't Go) Home" is under "D"): "#" for a digit or no letter at all, else that letter's capital
 * without accents ("É" is "E", Arabic "أ" is "ا").
 */
fun nameLetter(name: String): String {
    val from = fromFirstLetter(name)
    if (from.isEmpty() || !Character.isLetter(from.codePointAt(0))) return "#"
    val first = from.substring(0, Character.charCount(from.codePointAt(0)))
    return String(Character.toChars(Character.toUpperCase(Normalizer.normalize(first, Normalizer.Form.NFKD).codePointAt(0))))
}

/**
 * The A to Z order of names, by their [nameLetter] headings so a heading never shows twice: "#" first, then Latin
 * letters, then other scripts' (Arabic after Z), each heading in [collator]'s order; under one heading by [collator]
 * from the first letter or digit, then by the whole name, so names alike past their punctuation never mix.
 */
fun nameOrder(collator: Collator): Comparator<String> = Comparator { a, b ->
    val letterA = nameLetter(a)
    val letterB = nameLetter(b)
    headingRank(letterA).compareTo(headingRank(letterB)).takeIf { it != 0 }
        ?: collator.compare(letterA, letterB).takeIf { it != 0 }
        ?: letterA.compareTo(letterB).takeIf { it != 0 }
        ?: collator.compare(fromFirstLetter(a), fromFirstLetter(b)).takeIf { it != 0 }
        ?: collator.compare(a, b).takeIf { it != 0 }
        ?: a.compareTo(b)
}

/** "#" first, then Latin letters, then every other script's. */
private fun headingRank(letter: String): Int = when {
    letter == "#" -> 0
    Character.UnicodeScript.of(letter.codePointAt(0)) == Character.UnicodeScript.LATIN -> 1
    else -> 2
}

/** [name] from its first letter or digit. */
private fun fromFirstLetter(name: String): String = name.replace(LEADING_NON_ALPHANUMERIC, "")

private val LEADING_NON_ALPHANUMERIC = Regex("""^[^\p{L}\p{N}]+""")
