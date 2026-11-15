package app.lyrify.library

import app.lyrify.books.Book
import app.lyrify.books.BookMarks
import app.lyrify.books.booksOf
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortAlbums
import app.lyrify.settings.SortArtists
import app.lyrify.settings.SortSongs
import app.lyrify.stats.SongPlays
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
    /** The library's songs, in path order ([Folder.allSongs]): [all] without hidden songs, short clips, skipped folders and books. */
    val songs: List<Song>,
    /** By title. */
    val albums: List<Album>,
    /** "Unknown album": the songs without an album tag, in path order. */
    val unknownAlbum: List<Song>,
    /** By name. */
    val artists: List<Artist>,
    /** By name. */
    val genres: List<Genre>,
    /** The Folders tab: [Folder.rows] of the root. Books' files are here too: folders are file browsing. */
    val root: Folder,
    /** Finds songs again among [all], hidden ones too: a queue or playlist keeps a song the filters hide. */
    val resolver: SongResolver,
    /** The Books tab: every book among the songs ([app.lyrify.books.booksOf]), by title. Their files are in no other list. */
    val books: List<Book> = emptyList(),
) {
    private val byPath: Map<String, Song> = songs.associateBy { it.path }
    private val albumsByKey: Map<String, Album> = albums.associateBy { it.key }
    private val artistsByName: Map<String, Artist> = artists.associateBy { it.name }
    private val genresByName: Map<String, Genre> = genres.associateBy { it.name }
    private val booksById: Map<String, Book> = books.associateBy { it.id }
    private val bookByPath: Map<String, Book> = books.flatMap { book -> book.parts.map { it.path to book } }.toMap()

    fun album(key: String): Album? = albumsByKey[key]

    /** The book whose part is the file at [path]; null for music. */
    fun bookOf(path: String): Book? = bookByPath[path]

    /** The book [id] names ([Book.id]), or the one a lone file's path named before its folder and album did; null when it is gone. */
    fun book(id: String): Book? = booksById[id] ?: bookByPath[id]

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

        /**
         * The library of [all] under [settings]' "Hidden songs", "Skip short clips" and "Folders to skip", with the books
         * [marks] and the automatic signals make ([app.lyrify.books.booksOf]) taken out of the music.
         */
        fun of(all: List<Song>, settings: Settings, marks: BookMarks = BookMarks()): Library {
            val collator = collator()
            val resolver = SongResolver(all)
            // A hidden song is found as a playlist's is: by its path, then by its id on the same volume, so a rename or a
            // move in place keeps it hidden.
            val hidden = settings.library.hiddenSongs.mapNotNullTo(HashSet()) { resolver.find(it)?.path }
            // A book's short parts stay in it: "Skip short clips" is for music. A hidden file is in no book.
            val shown = all.filter { inLibrary(it, settings.copy(playback = settings.playback.copy(skipShortClips = false)), hidden) }
            val books = booksOf(shown, marks)
            val bookPaths = books.flatMapTo(HashSet()) { book -> book.parts.map { it.path } }
            val root = folderOf("", "", shown.filter { it.path in bookPaths || inLibrary(it, settings, hidden) }, collator)
            val songs = root.allSongs.filterNot { it.path in bookPaths }
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
                            .thenBy(nullsLast(nameOrder(collator))) { albumOf[it.path]?.title }
                            .then(trackOrder(collator)),
                    ),
                    albums = albums.filter { it.artist == name }
                        .sortedWith(compareBy<Album, Int?>(nullsLast(reverseOrder())) { it.year }.thenBy(nameOrder(collator)) { it.title }),
                )
            }.let { sortArtists(it, SortArtists.NAME) }
            val genres = songs.groupBy { text(it.genre) }
                .mapNotNull { (name, its) -> name?.let { Genre(it, its) } }
                .sortedWith(compareBy(nameOrder(collator)) { it.name })
            return Library(all, songs, albums, byAlbum[null].orEmpty(), artists, genres, root, resolver, books)
        }

        /** The folder [name] at [path] holding [songs] at or below it ([Song.folder] relative to its volume). */
        private fun folderOf(path: String, name: String, songs: List<Song>, collator: Collator): Folder {
            val depth = if (path.isEmpty()) 0 else path.count { it == '/' } + 1
            val (own, below) = songs.partition { folderNames(it).size == depth }
            // Sorted, not a sorted map: names the collator calls equal must not merge.
            val natural = numericOrder(collator)
            val folders = below.groupBy { folderNames(it)[depth] }.entries
                .sortedWith(compareBy<Map.Entry<String, List<Song>>, String>(natural) { it.key }.thenBy { it.key })
                .map { (child, its) -> folderOf(if (path.isEmpty()) child else "$path/$child", child, its, collator) }
            return Folder(path, name, own.sortedWith(compareBy(natural) { it.fileName }), folders)
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

/**
 * Whether [song] is in the library under [settings]: not hidden ([hidden], "Hidden songs" by path), not a short clip
 * (unless shown) and not in a folder to skip.
 */
fun inLibrary(song: Song, settings: Settings, hidden: Set<String> = settings.library.hiddenSongs.mapTo(HashSet()) { it.path }): Boolean {
    if (song.path in hidden) return false
    // MediaStore gives 0 when it could not read the length: that is not a short clip.
    if (settings.playback.skipShortClips && song.durationMs in 1 until Library.SHORT_CLIP_MS) return false
    val folder = song.folder.trimEnd('/')
    return settings.library.foldersToSkip.none { skips(it, folder) }
}

/** Whether the folder to skip [skip] takes [folder] (both relative to their volume): it or a folder below it, whatever the case. */
private fun skips(skip: String, folder: String): Boolean {
    val skipped = skip.trim('/')
    return folder.equals(skipped, ignoreCase = true) || folder.startsWith("$skipped/", ignoreCase = true)
}

/** [this] with [paths] hidden too, each once, after the songs hidden before. */
fun LibrarySettings.hiding(songs: Collection<SongRef>): LibrarySettings {
    val known = hiddenSongs.mapTo(HashSet()) { it.path }
    val more = songs.filter { it.path !in known }.distinctBy { it.path }
    return if (more.isEmpty()) this else copy(hiddenSongs = hiddenSongs + more)
}

/** [this] with [paths] no longer hidden. */
fun LibrarySettings.unhiding(songs: Collection<SongRef>): LibrarySettings {
    val shown = songs.mapTo(HashSet()) { it.path }
    return if (hiddenSongs.none { it.path in shown }) this else copy(hiddenSongs = hiddenSongs.filterNot { it.path in shown })
}

/** [this] with "Folders to skip" taking [folder] (a [Folder.path]) too, unless it or a folder above it is skipped already. */
fun LibrarySettings.skipping(folder: String): LibrarySettings {
    val path = folder.trim('/')
    if (path.isEmpty() || foldersToSkip.any { skips(it, path) }) return this
    return copy(foldersToSkip = foldersToSkip + path)
}

/**
 * Settings > Hidden songs: each of the [hidden] songs as kept, with the song it is on the phone now ([Library.resolver]:
 * the path, then the id on the same volume), the latest hidden first; those gone from the phone left out.
 */
fun hiddenSongs(library: Library, hidden: List<SongRef>): List<Pair<SongRef, Song>> =
    hidden.asReversed().mapNotNull { kept -> library.resolver.find(kept)?.let { kept to it } }

/** What every surface shows for [song]'s title: its file name under "Show file names", else its title. */
fun displayTitle(song: Song, settings: LibrarySettings): String = if (settings.showFileNames) song.fileName else song.title

/**
 * [songs] in [sort] ([songOrder]), [reversed] or not, with each song's [plays] by path for the play count and last played
 * keys (none: never played).
 */
fun sortSongs(songs: List<Song>, sort: SortSongs, settings: LibrarySettings, reversed: Boolean = false, plays: Map<String, SongPlays> = emptyMap()): List<Song> =
    songs.sortedWith(songOrder(sort, settings, reversed, plays))

/**
 * [sortSongs]'s order, for lists that sort more than the songs (a playlist's songs with their places). Each key first in
 * its own direction ([SortSongs]), [reversed] the other way, songs without a value last either way: by title as shown
 * ([displayTitle]); by artist, then the newest year, album and track; by album, then album artist and track; by year,
 * then album and track; the latest added, the most played (then the latest played), the latest played or the longest
 * first, each then by title; or by file name. Names in [nameOrder]; ties keep the order given (path order).
 */
fun songOrder(sort: SortSongs, settings: LibrarySettings, reversed: Boolean = false, plays: Map<String, SongPlays> = emptyMap()): Comparator<Song> {
    val collator = collator()
    val names = nameOrder(collator)
    val byTitle = compareBy<Song, String>(names) { displayTitle(it, settings) }
    fun <K : Any> first(order: Comparator<K>, key: (Song) -> K?): Comparator<Song> = keyOrder(order, reversed, key)
    fun count(song: Song): Int = plays[song.path]?.count ?: 0
    fun lastPlayed(song: Song): Long? = plays[song.path]?.lastPlayedAt
    return when (sort) {
        SortSongs.TITLE -> first(names) { displayTitle(it, settings) }
        SortSongs.ARTIST -> first(names) { text(it.artist) }
            .thenBy(nullsLast(reverseOrder())) { it.year }
            .thenBy(nullsLast(names)) { text(it.album) }
            .then(trackOrder(collator))
        SortSongs.ALBUM -> first(names) { text(it.album) }
            .thenBy(nullsLast(names)) { albumArtistOf(it) }
            .then(trackOrder(collator))
        SortSongs.YEAR -> first(reverseOrder<Int>()) { it.year }
            .thenBy(nullsLast(names)) { text(it.album) }
            .then(trackOrder(collator))
        SortSongs.RECENTLY_ADDED -> first(reverseOrder<Long>()) { it.addedAt }.then(byTitle)
        SortSongs.PLAY_COUNT -> first(reverseOrder<Int>(), ::count).thenBy(nullsLast(reverseOrder())) { lastPlayed(it) }.then(byTitle)
        SortSongs.LAST_PLAYED -> first(reverseOrder<Long>(), ::lastPlayed).then(byTitle)
        // MediaStore gives 0 when it could not read the length: no length.
        SortSongs.DURATION -> first(reverseOrder<Long>()) { song -> song.durationMs.takeIf { it > 0 } }.then(byTitle)
        SortSongs.FILE_NAME -> first(names) { it.fileName }
    }
}

/** Whether [this] key needs each song's plays ([sortSongs]' plays). */
val SortSongs.needsPlays: Boolean get() = this == SortSongs.PLAY_COUNT || this == SortSongs.LAST_PLAYED

/**
 * [albums] by title (then artist), by artist (then title), the newest year, the latest song added, the most plays of its
 * songs, the latest played or the longest first, each then by title; [reversed] the other way, albums without a value
 * last either way. Names in [nameOrder]; [plays] by song path.
 */
fun sortAlbums(albums: List<Album>, sort: SortAlbums, reversed: Boolean = false, plays: Map<String, SongPlays> = emptyMap()): List<Album> {
    val names = nameOrder(collator())
    val byTitle = compareBy<Album, String>(names) { it.title }.thenBy(nullsLast(names)) { it.artist }
    fun <K : Any> first(order: Comparator<K>, key: (Album) -> K?): Comparator<Album> = keyOrder(order, reversed, key)
    // Worked out once per album, not on each comparison.
    fun <K : Any> summed(order: Comparator<K>, value: (Album) -> K?): Comparator<Album> {
        val values = albums.associate { it.key to value(it) }
        return first(order) { values[it.key] }
    }
    val order = when (sort) {
        SortAlbums.TITLE -> first(names) { it.title }.thenBy(nullsLast(names)) { it.artist }
        SortAlbums.ARTIST -> first(names) { it.artist }.then(byTitle)
        SortAlbums.YEAR -> first(reverseOrder<Int>()) { it.year }.then(byTitle)
        SortAlbums.RECENTLY_ADDED -> summed(reverseOrder<Long>()) { album -> album.songs.mapNotNull { it.addedAt }.maxOrNull() }.then(byTitle)
        SortAlbums.PLAY_COUNT -> summed(reverseOrder<Int>()) { album -> album.songs.sumOf { plays[it.path]?.count ?: 0 } }.then(byTitle)
        SortAlbums.LAST_PLAYED -> summed(reverseOrder<Long>()) { album -> album.songs.mapNotNull { plays[it.path]?.lastPlayedAt }.maxOrNull() }.then(byTitle)
        SortAlbums.DURATION -> summed(reverseOrder<Long>()) { album -> album.durationMs.takeIf { it > 0 } }.then(byTitle)
    }
    return albums.sortedWith(order)
}

/**
 * [artists] by name, the most songs, the latest song added, the most plays of their songs or the latest played first,
 * each then by name; [reversed] the other way, artists without a value last either way. Names in [nameOrder].
 */
fun sortArtists(artists: List<Artist>, sort: SortArtists, reversed: Boolean = false, plays: Map<String, SongPlays> = emptyMap()): List<Artist> {
    val byName = compareBy<Artist, String>(nameOrder(collator())) { it.name }
    fun <K : Any> first(order: Comparator<K>, key: (Artist) -> K?): Comparator<Artist> = keyOrder(order, reversed, key)
    fun <K : Any> summed(order: Comparator<K>, value: (Artist) -> K?): Comparator<Artist> {
        val values = artists.associate { it.name to value(it) }
        return first(order) { values[it.name] }
    }
    val order = when (sort) {
        SortArtists.NAME -> first(nameOrder(collator())) { it.name }
        SortArtists.MOST_SONGS -> first(reverseOrder<Int>()) { it.songs.size }.then(byName)
        SortArtists.RECENTLY_ADDED -> summed(reverseOrder<Long>()) { artist -> artist.songs.mapNotNull { it.addedAt }.maxOrNull() }.then(byName)
        SortArtists.PLAY_COUNT -> summed(reverseOrder<Int>()) { artist -> artist.songs.sumOf { plays[it.path]?.count ?: 0 } }.then(byName)
        SortArtists.LAST_PLAYED -> summed(reverseOrder<Long>()) { artist -> artist.songs.mapNotNull { plays[it.path]?.lastPlayedAt }.maxOrNull() }.then(byName)
    }
    return artists.sortedWith(order)
}

/** By [key] in [order], or the other way when [reversed]; whatever has no key goes last either way. */
private fun <T, K : Any> keyOrder(order: Comparator<K>, reversed: Boolean, key: (T) -> K?): Comparator<T> =
    compareBy(nullsLast(if (reversed) order.reversed() else order), key)

/** Within an album: by disc (none is 1), then folder, then track number (none last), then title. */
private fun trackOrder(collator: Collator): Comparator<Song> =
    compareBy<Song> { it.disc ?: 1 }
        .thenBy(numericOrder(collator)) { it.folder }
        .thenBy { it.track ?: Int.MAX_VALUE }
        .thenBy(nameOrder(collator)) { it.title }

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
        ?: compareNatural(collator, fromFirstLetter(a), fromFirstLetter(b)).takeIf { it != 0 }
        ?: collator.compare(a, b).takeIf { it != 0 }
        ?: a.compareTo(b)
}

/** [collator]'s order with numbers by their value ([compareNatural]): "CD 2" before "CD 10". */
fun numericOrder(collator: Collator): Comparator<String> = Comparator { a, b -> compareNatural(collator, a, b) }

/**
 * [a] and [b] in [collator]'s order, but each run of digits by its value, so "Track 2" comes before "Track 10"; of two
 * runs of one value, the one with fewer leading zeros first ("Track 2" before "Track 02").
 */
fun compareNatural(collator: Collator, a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val digitsA = Character.isDigit(a[i])
        val digitsB = Character.isDigit(b[j])
        val endA = runEnd(a, i, digitsA)
        val endB = runEnd(b, j, digitsB)
        val order = when {
            digitsA && digitsB -> compareNumbers(a, i, endA, b, j, endB)
            // The same words, as most runs are ("Track " and "Track "): equal, without a copy for the collator.
            endA - i == endB - j && a.regionMatches(i, b, j, endA - i) -> 0
            else -> collator.compare(a.substring(i, endA), b.substring(j, endB))
        }
        if (order != 0) return order
        i = endA
        j = endB
    }
    return (a.length - i).compareTo(b.length - j)
}

/** Where the run of digits (or of anything else, as [digits] says) that starts at [from] in [text] ends. */
private fun runEnd(text: String, from: Int, digits: Boolean): Int {
    var at = from
    while (at < text.length && Character.isDigit(text[at]) == digits) at++
    return at
}

/** The digits of [a] from [i] to [endA] and of [b] from [j] to [endB] by their value, then the fewer leading zeros first. */
private fun compareNumbers(a: String, i: Int, endA: Int, b: String, j: Int, endB: Int): Int {
    fun significant(text: String, from: Int, end: Int): Int {
        var at = from
        while (at < end - 1 && Character.digit(text[at], RADIX) == 0) at++
        return at
    }
    val startA = significant(a, i, endA)
    val startB = significant(b, j, endB)
    (endA - startA).compareTo(endB - startB).takeIf { it != 0 }?.let { return it }
    for (k in 0 until endA - startA) {
        Character.digit(a[startA + k], RADIX).compareTo(Character.digit(b[startB + k], RADIX)).takeIf { it != 0 }?.let { return it }
    }
    return (endA - i).compareTo(endB - j)
}

private const val RADIX: Int = 10

/** "#" first, then Latin letters, then every other script's. */
private fun headingRank(letter: String): Int = when {
    letter == "#" -> 0
    Character.UnicodeScript.of(letter.codePointAt(0)) == Character.UnicodeScript.LATIN -> 1
    else -> 2
}

/** [name] from its first letter or digit. */
private fun fromFirstLetter(name: String): String = name.replace(LEADING_NON_ALPHANUMERIC, "")

private val LEADING_NON_ALPHANUMERIC = Regex("""^[^\p{L}\p{N}]+""")
