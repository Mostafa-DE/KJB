package app.lyrify.library.search

import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Folder
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.logFailures
import app.lyrify.player.LibraryTree
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.player.TreeSearch
import app.lyrify.settings.LibrarySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import java.text.Collator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** How a search matched: at the start of a word, which ranks first, or inside one. */
enum class MatchKind { WORD_START, INSIDE_WORD }

/** Where a search matched a text: [start] until [end] (exclusive) in the text as shown, for the highlight. */
data class Match(val kind: MatchKind, val start: Int, val end: Int)

/** One result: [item], the [text] it was found by (as shown), and where in it. */
data class Hit<out T>(val item: T, val text: String, val match: Match)

/** What the library's names gave for one search, each group ranked. */
data class LibraryHits(
    val songs: List<Hit<Song>> = emptyList(),
    val artists: List<Hit<Artist>> = emptyList(),
    val albums: List<Hit<Album>> = emptyList(),
    val folders: List<Hit<Folder>> = emptyList(),
)

/**
 * [text] as Search compares it: decomposed (NFKD) without its accents and other marks, in lower case, with "ß" as "ss",
 * "&" as "and", a few letters that have no decomposition as their plain letter ("ø" as "o"), invisible format
 * characters dropped, and every run of anything but letters and digits as one space, trimmed. TrackNames.normalize's
 * rules, one character at a time so a match maps back to the text ([match]). The lyrics index stores its lines folded
 * so: a change here needs a new [SqliteLyricsIndexStore.SCHEMA].
 */
fun fold(text: String): String = fold(text, places = null)

/**
 * Where [query] (already [fold]ed) is in [text]: its first place at the start of a word, else its first place inside a
 * word; null when it is in neither, or empty.
 */
fun match(text: String, query: String): Match? {
    if (query.isEmpty()) return null
    val places = Places(text.length)
    val folded = fold(text, places)
    val at = wordStart(folded, query).takeIf { it >= 0 }
    val kind = if (at != null) MatchKind.WORD_START else MatchKind.INSIDE_WORD
    val start = at ?: folded.indexOf(query).takeIf { it >= 0 } ?: return null
    return Match(kind, places.starts[start], places.ends[start + query.length - 1])
}

/** [items] whose [text] matches [query]: those matching at a word's start first, then inside a word, each by text in the phone's language order. */
fun <T> ranked(items: List<T>, query: String, text: (T) -> String): List<Hit<T>> {
    val folded = fold(query)
    if (folded.isEmpty()) return emptyList()
    val collator = Collator.getInstance()
    return items.mapNotNull { item -> text(item).let { shown -> match(shown, folded)?.let { Hit(item, shown, it) } } }
        .sortedWith(compareBy<Hit<T>> { it.match.kind }.thenBy(collator) { it.text })
}

/**
 * The library's names, folded once per library ([of]), so a search is a scan of short strings: song titles as shown
 * ([displayTitle]), artists, albums, and every folder by its own name. Each group is kept in the order its results show
 * within a rank (by name in the phone's language order), so a search only splits word starts from matches inside words.
 */
class SearchIndex private constructor(
    private val songs: List<Entry<Song>>,
    private val artists: List<Entry<Artist>>,
    private val albums: List<Entry<Album>>,
    private val folders: List<Entry<Folder>>,
) {
    private class Entry<T>(val item: T, val text: String, val folded: String)

    /** The songs, artists, albums and folders whose names match [query], each group ranked. */
    fun find(query: String): LibraryHits {
        val folded = fold(query)
        if (folded.isEmpty()) return LibraryHits()
        return LibraryHits(hits(songs, folded), hits(artists, folded), hits(albums, folded), hits(folders, folded))
    }

    private fun <T> hits(entries: List<Entry<T>>, query: String): List<Hit<T>> {
        val starts = mutableListOf<Hit<T>>()
        val inside = mutableListOf<Hit<T>>()
        entries.forEach { entry ->
            if (!entry.folded.contains(query)) return@forEach
            val hit = Hit(entry.item, entry.text, checkNotNull(match(entry.text, query)))
            if (hit.match.kind == MatchKind.WORD_START) starts += hit else inside += hit
        }
        return starts + inside
    }

    companion object {
        /** The index of [library]'s names, titles as [settings] shows them. Built on Dispatchers.Default. */
        fun of(library: Library, settings: LibrarySettings): SearchIndex {
            val collator = Collator.getInstance()
            fun <T> entries(items: List<T>, text: (T) -> String): List<Entry<T>> =
                items.map { text(it).let { shown -> Entry(it, shown, fold(shown)) } }.sortedWith(compareBy(collator) { it.text })
            // Every folder below the root, the same name in two places twice; ties keep path order.
            fun below(folder: Folder): List<Folder> = folder.folders.flatMap { listOf(it) + below(it) }
            return SearchIndex(
                songs = entries(library.songs) { displayTitle(it, settings) },
                artists = entries(library.artists) { it.name },
                albums = entries(library.albums) { it.title },
                folders = entries(below(library.root).sortedBy { it.path }) { it.name },
            )
        }
    }
}

/** What a song tapped in the results plays: the song results, then the songs found only by their lyrics. */
fun searchQueue(songs: List<Song>, lyrics: List<Song>): List<Song> = (songs + lyrics).distinctBy { it.path }

/** The results' queue: "Search · words", with the words as typed. Every search is one place, so Recent keeps one search queue. */
fun searchSource(query: String): QueueSource = QueueSource(SourceKind.SEARCH, "search", query.trim().replace(SPACES, " "))

/**
 * Search's songs for a search typed or said in the car ([TreeSearch], auto/CarBrowse.kt), what a song tapped in Search's
 * results plays: the songs found by their names as Search ranks them, then those found only by their [lyrics]; they play
 * as Search's queue ("Search · words").
 */
class SearchSongs(private val lyrics: suspend (query: String, library: Library, settings: LibrarySettings) -> List<Song>) : TreeSearch {
    override suspend fun songs(query: String, library: Library, settings: LibrarySettings): List<Song> =
        searchQueue(SearchGraph.names(library, settings).find(query).songs.map { it.item }, lyrics(query, library, settings))

    override fun source(query: String): QueueSource = searchSource(query)
}

/** For each folded character, where it came from in the text: its first index, and the index after it. */
private class Places(capacity: Int) {
    var starts = IntArray(capacity + 8)
    var ends = IntArray(capacity + 8)
    var size = 0

    fun add(start: Int, end: Int) {
        if (size == starts.size) {
            starts = starts.copyOf(size * 2)
            ends = ends.copyOf(size * 2)
        }
        starts[size] = start
        ends[size] = end
        size++
    }
}

private fun fold(text: String, places: Places?): String {
    val out = StringBuilder(text.length)
    var at = 0
    while (at < text.length) {
        val point = text.codePointAt(at)
        val next = at + Character.charCount(point)
        for (c in folded(point)) {
            // One space for any run of them, none at the start.
            if (c == ' ' && (out.isEmpty() || out[out.length - 1] == ' ')) continue
            out.append(c)
            places?.add(at, next)
        }
        at = next
    }
    if (out.isNotEmpty() && out[out.length - 1] == ' ') {
        out.setLength(out.length - 1)
        places?.let { it.size-- }
    }
    return out.toString()
}

/** The first place of [query] in [folded] at the start of a word; -1 when there is none. */
private fun wordStart(folded: String, query: String): Int {
    var at = folded.indexOf(query)
    while (at > 0 && folded[at - 1] != ' ') at = folded.indexOf(query, at + 1)
    return at
}

/** What one code point folds to: ASCII from a table, the rest worked out once each. */
private fun folded(point: Int): String = if (point < ASCII.size) ASCII[point] else FOLDED.getOrPut(point) { foldPoint(point) }

private fun foldPoint(point: Int): String = buildString {
    Normalizer.normalize(String(Character.toChars(point)), Normalizer.Form.NFKD).codePoints().forEach { part ->
        when (Character.getType(part).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> Unit
            else -> when {
                part == '&'.code -> append(" and ")
                Character.isLetterOrDigit(part) -> Character.toLowerCase(part).let { lower -> append(PLAIN[lower] ?: String(Character.toChars(lower))) }
                else -> append(' ')
            }
        }
    }
}

private val ASCII: Array<String> = Array(0x80) { code ->
    val c = code.toChar()
    when {
        c.isLetterOrDigit() -> c.lowercaseChar().toString()
        c == '&' -> " and "
        else -> " "
    }
}

/** Lower-case letters that fold to plain ones without an NFKD decomposition. */
private val PLAIN: Map<Int, String> = mapOf(
    'ß'.code to "ss",
    'ς'.code to "σ",
    'ø'.code to "o",
    'ł'.code to "l",
    'đ'.code to "d",
    'ħ'.code to "h",
    'ı'.code to "i",
    'æ'.code to "ae",
    'œ'.code to "oe",
)

/** Folded code points beyond ASCII, as met: a few hundred in a library, a few thousand with CJK. */
private val FOLDED = ConcurrentHashMap<Int, String>()

private val SPACES = Regex("""\s+""")

/** Search's objects, built by hand, one of each per process. */
@OptIn(FlowPreview::class)
object SearchGraph {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)
    private val asks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var lyrics: LyricsIndex? = null
    private var recent: RecentSearches? = null

    /** Read and replaced whole, without the lock: see [names]. */
    @Volatile
    private var names: Triple<Library, Boolean, SearchIndex>? = null

    /**
     * The index of [library]'s names, titles as [settings] shows them: the last one built while it is for the same
     * library and the same "Show file names", so opening Search again or a "View all" does not build it again. On
     * Dispatchers.Default, and without the lock [lyrics] and [recent] take, as Compose asks for those on the main thread:
     * a library's index is built first, then put in place whole. Two built at once both serve; the last one is kept.
     */
    fun names(library: Library, settings: LibrarySettings): SearchIndex {
        names?.let { (built, fileNames, index) -> if (built === library && fileNames == settings.showFileNames) return index }
        return SearchIndex.of(library, settings).also { names = Triple(library, settings.showFileNames, it) }
    }

    /**
     * The lyrics index in app-private `databases/search.db`. From its first use it follows the library: it reads the
     * `.lrc` files that changed each time the library changes, and after [refreshLyrics] asks, one update at a time,
     * in the background.
     */
    @Synchronized
    fun lyrics(context: Context): LyricsIndex = lyrics ?: run {
        val app = context.applicationContext
        val index = LyricsIndex(SqliteLyricsIndexStore(app), FileLrcFiles, log = { Log.i(LOG_TAG, it) })
        val library = LibraryGraph.repository(app).state
        scope.launch {
            // Asks come in bursts (each lyrics write changes the ledger): one update after they stop, and a burst during
            // an update gives one more after it.
            merge(library.filterIsInstance<LibraryState.Ready>().map { }, asks.debounce(ASK_DEBOUNCE_MS)).conflate().collect {
                (library.value as? LibraryState.Ready)?.let { index.sync(it.library.songs) }
            }
        }
        index
    }.also { lyrics = it }

    /** Search for the car ([SearchSongs]): the names index, then the songs the lyrics index finds, as many as a car list holds. */
    fun songs(context: Context): TreeSearch = SearchSongs { query, library, settings ->
        lyrics(context).find(query, library, settings, limit = LibraryTree.MAX_CHILDREN).map { it.song }
    }

    /** Looks at the `.lrc` files again soon: Search opened, or lyrics were written or removed. */
    fun refreshLyrics() {
        asks.tryEmit(Unit)
    }

    /** Recent searches in app-private `files/search-history.json`. */
    @Synchronized
    fun recent(context: Context): RecentSearches = recent ?: RecentSearches(
        RecentSearchesStore(File(context.applicationContext.filesDir, "search-history.json")) { Log.w(LOG_TAG, it) },
        scope,
    ).also { recent = it }

    private const val ASK_DEBOUNCE_MS: Long = 2_000
}
