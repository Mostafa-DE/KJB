package app.lyrify.player

import app.lyrify.library.Library
import app.lyrify.library.Playlist
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.library.recentlyAdded
import app.lyrify.library.sortSongs
import app.lyrify.library.search.searchSource
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.SortSongs
import java.text.Normalizer
import java.util.Locale

/**
 * One node of the browse tree: a list to open, or something to [playable]: a [song], or Shuffle all. [cover] is the song
 * whose cover it shows (an album's first), [group] the heading it shows under in the car ("Built for you"), and [grid]
 * shows its children as a grid of covers (Albums).
 */
data class TreeNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val song: Song? = null,
    val playable: Boolean = song != null,
    val cover: Song? = song,
    val group: String? = null,
    val grid: Boolean = false,
) {
    val browsable: Boolean get() = !playable
}

/** What playing a node does: play its list from a song, move inside a queue to one, shuffle a list, or go on with the queue there is. */
sealed interface TreePlay {
    data class FromList(val songs: List<SongRef>, val start: Int, val source: QueueSource) : TreePlay

    data class InQueue(val queueId: String, val index: Int) : TreePlay

    /** A new queue of [songs] from [source], shuffled: Shuffle all. */
    data class Shuffle(val songs: List<SongRef>, val source: QueueSource) : TreePlay

    /** The queue there is, where it is: a voice request without words while a queue is loaded. */
    data object Resume : TreePlay
}

/** What a search in the car found for [query]: [songs], best first, which play as [source] ("Search · words"). */
data class Found(val query: String, val songs: List<Song>, val source: QueueSource)

/** What the tree is built from: the library, the queues, the user's playlists and Built for you, "Show file names", and the names to show. */
class TreeData(
    val library: Library,
    val book: QueueBook,
    val playlists: List<Playlist>,
    val settings: LibrarySettings,
    /** The title of a fixed node or a heading, by its id ("Albums", "Built for you"). */
    val name: (String) -> String = { it },
    /** Favourites, the latest added first. */
    val favourites: List<SongRef> = emptyList(),
    /** The play counter's Most played and Recently played (S1), as the app shows them. */
    val mostPlayed: List<Song> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    /** Wall-clock millis, for Recently added. */
    val now: Long = 0,
    /** The car's latest searches by their words, so a song picked in their results plays them. */
    val found: Map<String, Found> = emptyMap(),
    /** A queue's name as the app shows it ("Album · Harbour Glass"). */
    val queueName: (Queue) -> String,
)

/**
 * The browse tree for Bluetooth and Android Auto: Recent queues; Playlists, Built for you first (Shuffle all,
 * Favourites, Most played, Recently played, Recently added, those with songs) then the user's; Albums, shown as a grid;
 * Artists; Songs with Shuffle all at the top. Android Auto shows only so many root items ([root]). Pure: the service
 * turns nodes into media items and pages them ([page]). A song's id is its list's id, a bar, and its place there
 * ("album:<key>|3"), so playing it plays that list from it as the same list in the app does: a list plays as the app's
 * own (the same queue source), so a tap in the car inside the list playing goes on in its queue.
 */
object LibraryTree {
    const val ROOT: String = "root"
    const val QUEUES: String = "queues"
    const val PLAYLISTS: String = "playlists"
    const val ALBUMS: String = "albums"
    const val ARTISTS: String = "artists"
    const val SONGS: String = "songs"

    /** Atop Songs, and first in Built for you, so it stays when a car leaves Songs out. */
    const val SHUFFLE_ALL: String = "shuffle"

    // Built for you, and Playlists' two headings.
    const val FAVOURITES: String = "favourites"
    const val MOST_PLAYED: String = "most_played"
    const val RECENTLY_PLAYED: String = "recently_played"
    const val RECENTLY_ADDED: String = "recently_added"
    const val BUILT_FOR_YOU: String = "built_for_you"
    const val YOUR_PLAYLISTS: String = "your_playlists"

    /** A client that asks for a whole list at once (Android Auto, which does not page) gets at most this many. */
    const val MAX_CHILDREN: Int = 500

    private val TOP = listOf(QUEUES, PLAYLISTS, ALBUMS, ARTISTS, SONGS)
    private val BUILT_IN = listOf(FAVOURITES, MOST_PLAYED, RECENTLY_PLAYED, RECENTLY_ADDED)
    private const val QUEUE = "queue:"
    private const val PLAYLIST = "playlist:"
    private const val ALBUM = "album:"
    private const val ARTIST = "artist:"
    private const val SEARCH = "search:"
    private const val SONG = '|'

    /**
     * The car's Songs: every song by title, never the Songs tab's sort and filter, so a place of its own: a tap in the car
     * never replaces the phone's Songs queue.
     */
    private val CAR_SONGS = QueueSource(SourceKind.SONGS, "car-songs", "")

    /** The lists whose children are songs: these, and those named by one of [SONG_LIST_PREFIXES]. */
    private val SONG_LISTS = setOf(SONGS, FAVOURITES, MOST_PLAYED, RECENTLY_PLAYED, RECENTLY_ADDED)
    private val SONG_LIST_PREFIXES = listOf(QUEUE, PLAYLIST, ALBUM, ARTIST, SEARCH)

    /**
     * The root for a client that shows at most [limit] items there (Android Auto's root hint, usually 4): the top lists
     * from the left, so Songs is the one left out. All five when [limit] is unknown, 0, or not below five.
     */
    fun root(limit: Int?): String = if (limit == null || limit <= 0 || limit >= TOP.size) ROOT else "$ROOT:$limit"

    fun isRoot(id: String): Boolean = rootSize(id) != null

    /** The list of the car's search results for [query] ([TreeData.found]). */
    fun searchId(query: String): String = SEARCH + query

    /** Whether [id] names something to play, a song in a list or Shuffle all; not whether it is still there. */
    fun playable(id: String): Boolean = id == SHUFFLE_ALL || songAt(id) != null

    /** The children of [id], every one; null when there is no such list (a song, or one gone). */
    fun children(id: String, data: TreeData): List<TreeNode>? {
        val library = data.library
        rootSize(id)?.let { size -> return TOP.take(size).map { TreeNode(it, data.name(it), grid = it == ALBUMS) } }
        return when (id) {
            QUEUES -> (listOfNotNull(data.book.currentId) + data.book.saved + data.book.recent).distinct()
                .mapNotNull(data.book::queue).map { TreeNode(QUEUE + it.id, data.queueName(it)) }
            PLAYLISTS -> playlists(data)
            ALBUMS -> library.albums.map { TreeNode(ALBUM + it.key, it.title, it.artist, cover = it.songs.firstOrNull()) }
            ARTISTS -> library.artists.map { TreeNode(ARTIST + it.name, it.name) }
            SONGS -> listOfNotNull(shuffleAll(data)) + songs(id, data).orEmpty()
            else -> songs(id, data)
        }
    }

    /**
     * One page of [nodes]: page [page] of [pageSize] (at most [MAX_CHILDREN]) for a client that pages, so it reaches every
     * item; a client that asks for everything (page 0 of `Int.MAX_VALUE`) gets the first [MAX_CHILDREN].
     */
    fun page(nodes: List<TreeNode>, page: Int, pageSize: Int): List<TreeNode> {
        val size = pageSize.coerceIn(1, MAX_CHILDREN)
        val from = page.coerceAtLeast(0).toLong() * size
        if (from >= nodes.size) return emptyList()
        return nodes.subList(from.toInt(), minOf(nodes.size.toLong(), from + size).toInt())
    }

    /** What playing node [id] does; null when it is nothing to play (a list, or a song gone). */
    fun play(id: String, data: TreeData): TreePlay? {
        if (id == SHUFFLE_ALL) {
            val songs = list(SONGS, data)?.filterNotNull()?.takeIf { it.isNotEmpty() } ?: return null
            return TreePlay.Shuffle(songs.map { it.ref }, source(SONGS, data) ?: return null)
        }
        val (parent, index) = songAt(id) ?: return null
        if (parent.startsWith(QUEUE)) {
            val queue = data.book.queue(parent.removePrefix(QUEUE)) ?: return null
            return if (index in queue.items.indices) TreePlay.InQueue(queue.id, index) else null
        }
        val songs = list(parent, data)?.filterNotNull() ?: return null
        if (index !in songs.indices) return null
        return TreePlay.FromList(songs.map { it.ref }, index, source(parent, data) ?: return null)
    }

    /**
     * What a voice request plays ("Play <words> on KJB"): the songs [found] from the first, as the car's search does.
     * Without words ([found] null) the queue there is, else every song shuffled. Null when there is nothing to play.
     */
    fun voice(found: Found?, data: TreeData): TreePlay? = when {
        found != null -> found.songs.takeIf { it.isNotEmpty() }?.let { songs -> TreePlay.FromList(songs.map { it.ref }, 0, found.source) }
        data.book.current != null -> TreePlay.Resume
        else -> play(SHUFFLE_ALL, data)
    }

    /** A song node's list and place: only under a list that holds songs, at a place that is a whole number. */
    private fun songAt(id: String): Pair<String, Int>? {
        val parent = id.substringBeforeLast(SONG, missingDelimiterValue = "")
        val index = id.substringAfterLast(SONG, missingDelimiterValue = "").toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val songs = parent in SONG_LISTS || SONG_LIST_PREFIXES.any { parent.length > it.length && parent.startsWith(it) }
        return if (songs) parent to index else null
    }

    private fun rootSize(id: String): Int? =
        if (id == ROOT) TOP.size else id.removePrefix("$ROOT:").takeIf { it != id }?.toIntOrNull()?.takeIf { it in 1 until TOP.size }

    /** Built for you (Shuffle all, then the built-in lists that hold songs), then the user's playlists. */
    private fun playlists(data: TreeData): List<TreeNode> {
        val builtIn = listOfNotNull(shuffleAll(data)) + BUILT_IN.filter { !list(it, data).isNullOrEmpty() }.map { TreeNode(it, data.name(it)) }
        val own = data.playlists.map { TreeNode(PLAYLIST + it.id, it.name) }
        return builtIn.map { it.copy(group = data.name(BUILT_FOR_YOU)) } + own.map { it.copy(group = data.name(YOUR_PLAYLISTS)) }
    }

    private fun shuffleAll(data: TreeData): TreeNode? = TreeNode(SHUFFLE_ALL, data.name(SHUFFLE_ALL), playable = true).takeIf { data.library.songs.isNotEmpty() }

    private fun songs(id: String, data: TreeData): List<TreeNode>? =
        list(id, data)?.let { songs -> songs.mapIndexedNotNull { i, song -> song?.let { node("$id$SONG$i", it, data.settings) } } }

    /** The songs of list [id] in order; for a queue, by its items' places, null where a song is not on the phone. */
    private fun list(id: String, data: TreeData): List<Song?>? {
        val library = data.library
        return when {
            id == SONGS -> sortSongs(library.songs, SortSongs.TITLE, data.settings)
            id == FAVOURITES -> library.listed(data.favourites)
            id == MOST_PLAYED -> data.mostPlayed
            id == RECENTLY_PLAYED -> data.recentlyPlayed
            id == RECENTLY_ADDED -> recentlyAdded(library, data.now)
            id.startsWith(QUEUE) -> data.book.queue(id.removePrefix(QUEUE))?.items?.map { library.resolver.find(it.song) }
            id.startsWith(PLAYLIST) -> data.playlists.firstOrNull { it.id == id.removePrefix(PLAYLIST) }?.let { library.listed(it.songs) }
            id.startsWith(ALBUM) -> library.album(id.removePrefix(ALBUM))?.songs
            id.startsWith(ARTIST) -> library.artist(id.removePrefix(ARTIST))?.songs
            id.startsWith(SEARCH) -> data.found[id.removePrefix(SEARCH)]?.songs
            else -> null
        }
    }

    /** The queue list [id] plays as: the kind and key of the same list in the app (its pages and tabs). */
    private fun source(id: String, data: TreeData): QueueSource? = when {
        id == SONGS -> CAR_SONGS
        id == FAVOURITES -> QueueSource(SourceKind.FAVOURITES, "favourites", "")
        id == MOST_PLAYED -> QueueSource(SourceKind.MOST_PLAYED, "MOST_PLAYED", "")
        id == RECENTLY_PLAYED -> QueueSource(SourceKind.RECENTLY_PLAYED, "RECENTLY_PLAYED", "")
        id == RECENTLY_ADDED -> QueueSource(SourceKind.RECENTLY_ADDED, "RECENTLY_ADDED", "")
        id.startsWith(PLAYLIST) -> data.playlists.firstOrNull { it.id == id.removePrefix(PLAYLIST) }?.let { QueueSource(SourceKind.PLAYLIST, it.id, it.name) }
        id.startsWith(ALBUM) -> data.library.album(id.removePrefix(ALBUM))?.let { QueueSource(SourceKind.ALBUM, it.key, it.title) }
        id.startsWith(ARTIST) -> id.removePrefix(ARTIST).let { QueueSource(SourceKind.ARTIST, it, it) }
        id.startsWith(SEARCH) -> data.found[id.removePrefix(SEARCH)]?.source
        else -> null
    }

    private fun node(id: String, song: Song, settings: LibrarySettings) = TreeNode(id, displayTitle(song, settings), song.artist, song)
}

/**
 * The car's search, by name and by voice: what the Search tab finds for some words, as songs. The service hands the car
 * Search's own ([app.lyrify.library.search.SearchSongs]: the names index, then the songs found by their lyrics).
 */
interface TreeSearch {
    /** The songs found for [query], best first: what a tap on a song in Search's results plays. Off the main thread. */
    suspend fun songs(query: String, library: Library, settings: LibrarySettings): List<Song>

    /** The queue they play as ("Search · words"): the same words in another case or with other accents are the same list. */
    fun source(query: String): QueueSource
}

/**
 * A search by names alone, where Search is not at hand (CarBrowse's default, for tests): the songs whose title as shown,
 * artist or album holds the words, ignoring case and accents; those found by title first, then by artist, then by album,
 * each in library order.
 */
object NameSearch : TreeSearch {
    private val MARKS = Regex("""\p{M}+""")
    private val SPACES = Regex("""\s+""")

    override suspend fun songs(query: String, library: Library, settings: LibrarySettings): List<Song> {
        val words = fold(query)
        if (words.isEmpty()) return emptyList()
        val fields = listOf<(Song) -> String?>({ displayTitle(it, settings) }, Song::artist, Song::album)
        return fields.flatMap { field -> library.songs.filter { song -> field(song)?.let(::fold)?.contains(words) == true } }.distinctBy { it.path }
    }

    override fun source(query: String): QueueSource = searchSource(query)

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT).trim().replace(SPACES, " ")
}
