package app.lyrify.tags

import app.lyrify.library.ArtistSplitter
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.search.fold
import java.text.Collator

/**
 * Edit tags' suggestions (user feedback 2026-11-17, item 8): while a field whose values repeat across the library is
 * typed in ([FIELDS]), the values the library's own songs have that match, never anything from the network. Those that
 * start with the text first, then those with it inside, each the most used (on the most songs) first; case and accents
 * do not matter, as in Search ([fold]). Artist and Album artist suggest single artists, as the lists read the tags
 * ([Library.artistNames]): after a separator, the name being typed on its own, so "Kit Calloway feat. Mi" suggests
 * "Mira Solen" and a tap gives "Kit Calloway feat. Mira Solen". Built once per library off the main thread ([of]); a key
 * press only scans the field's values, folded once.
 */
class TagSuggestions private constructor(private val values: Map<TagField, List<Value>>, private val splitter: ArtistSplitter) {
    /** One value of a field: as written, folded, on how many songs. */
    private class Value(val text: String, val folded: String, val songs: Int)

    /** One suggestion: [name] as the chip shows it, and [value], what a tap puts in the field. */
    data class Suggestion(val name: String, val value: String)

    /**
     * At most [limit] suggestions for [field] while [typed] is in it; none for a field that does not suggest, for nothing
     * typed, or right after a separator. What is typed exactly is never suggested; another spelling of it is.
     */
    fun of(field: TagField, typed: String, limit: Int = MAX): List<Suggestion> {
        val pool = values[field] ?: return emptyList()
        val whole = typed.trim()
        if (whole.isEmpty()) return emptyList()
        val start = if (field in ARTIST_FIELDS) splitter.lastNameStart(typed) else 0
        val wholes = matching(pool, whole).map { Suggestion(it, it) }
        val found = if (start == 0) {
            wholes
        } else {
            val last = typed.substring(start).trim()
            if (last.isEmpty()) return emptyList()
            val before = typed.substring(0, start)
            val named = splitter.names(before).mapTo(HashSet()) { it.lowercase() }
            // A whole name with a separator of its own first ("Tyler, The Cr"), then the last name alone.
            wholes + matching(pool, last).filter { it.lowercase() !in named }.map { Suggestion(it, before + it) }
        }
        return found.distinctBy { it.name }.filter { it.value != whole }.take(limit)
    }

    /** The values of [pool] that [text] matches: those starting with it, then those with it inside, each in [pool]'s order. */
    private fun matching(pool: List<Value>, text: String): List<String> {
        val query = fold(text)
        if (query.isEmpty()) return emptyList()
        val (starts, inside) = pool.filter { query in it.folded }.partition { it.folded.startsWith(query) }
        return (starts + inside).map { it.text }
    }

    companion object {
        /** How many suggestions show at once. */
        const val MAX: Int = 5

        /** The fields whose values repeat across a library. A title is a song's own; a year or a number is quicker typed. */
        val FIELDS: Set<TagField> = setOf(TagField.ARTIST, TagField.ALBUM_ARTIST, TagField.ALBUM, TagField.GENRE)

        private val ARTIST_FIELDS: Set<TagField> = setOf(TagField.ARTIST, TagField.ALBUM_ARTIST)

        /**
         * The values of [library]'s songs (hidden songs and books left out, as in every list), each field's the most
         * used first, then by name: Artist the names artist tags give; Album artist those of album artist tags and of
         * artist tags (an album artist is often one of them); Album and Genre as written.
         */
        fun of(library: Library): TagSuggestions {
            val collator = Collator.getInstance()
            fun pool(each: (Song) -> Collection<String>): List<Value> {
                val songs = HashMap<String, Int>()
                library.songs.forEach { song -> each(song).forEach { songs.merge(it, 1) { a, b -> a + b } } }
                return songs.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(collator) { it.key })
                    .map { Value(it.key, fold(it.key), it.value) }
            }
            fun written(tag: String?): List<String> = listOfNotNull(tag?.trim()?.takeIf(String::isNotEmpty))
            return TagSuggestions(
                mapOf(
                    TagField.ARTIST to pool { library.artistNames(it.artist) },
                    TagField.ALBUM_ARTIST to pool { (library.artistNames(it.albumArtist) + library.artistNames(it.artist)).toSet() },
                    TagField.ALBUM to pool { written(it.album) },
                    TagField.GENRE to pool { written(it.genre) },
                ),
                library.artistSplitter,
            )
        }
    }
}
