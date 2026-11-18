package app.lyrify.library

import app.lyrify.settings.ArtistSplit

/**
 * The artists an artist tag names, as "Artists with several names" ([ArtistSplit]) reads it: for KJB's lists, pages and
 * Search only, as the files keep their tags. Made once per library ([Library.of]); safe to share between threads.
 *
 * A separator of marks only (";", "/", ",") splits wherever it is. One with a letter ("feat.", "ft.", "featuring") splits
 * only as a whole word, in any case, with or without a dot after it, and also in brackets: "Kit Calloway (feat. Mira
 * Solen)" names both, its brackets dropped (what follows the closing one stays with the last name). An exception
 * ("AC/DC", "Tyler, The Creator") is a whole name in any case that is never split, alone or inside a longer tag. With
 * the setting off, or nothing to split, the tag is one name.
 */
class ArtistSplitter(rules: ArtistSplit) {
    private val separator: Regex? = rules.separators.takeIf { rules.enabled }?.let(::separatorPattern)
    private val exception: Regex? = rules.exceptions.takeIf { rules.enabled }?.let(::exceptionPattern)

    /**
     * The names in [tag], in its order: each trimmed and kept once (in any case, as first spelled), blanks dropped. None
     * for no tag or a blank one; the whole tag when it is only separators.
     */
    fun names(tag: String?): List<String> {
        val text = tag?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
        val cuts = cuts(text)
        if (cuts.isEmpty()) return listOf(text)
        val names = ArrayList<String>(cuts.size + 1)
        val name = StringBuilder()
        var at = 0
        for (cut in cuts) {
            name.append(text, at, cut.range.first)
            if (cut.splits) {
                names += name.toString()
                name.setLength(0)
            }
            at = cut.range.last + 1
        }
        names += name.append(text, at, text.length).toString()
        val found = names.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        return found.ifEmpty { listOf(text) }
    }

    /**
     * Where the last name in [typed] starts: after its last separator and the spaces after that ("Kit Calloway feat. Mi"
     * at "Mi"), so Edit tags can suggest that name alone; 0 when nothing splits it.
     */
    fun lastNameStart(typed: String): Int {
        var start = cuts(typed).lastOrNull()?.let { it.range.last + 1 } ?: return 0
        while (start < typed.length && typed[start].isWhitespace()) start++
        return start
    }

    /**
     * [tag] as Rename artist and Merge write it ([app.lyrify.tags.TagEdits.rename]): each name [names] reads in it that is
     * one of [renamed] becomes [to], and everything else stays exactly as it was: the separators, brackets and spaces,
     * and the other names, each piece of them given to [rest] (the credits TagEdits finds without the setting), the
     * never-split names in it kept whole ("feat." inside one is no credit). One name spelled again in other letters is
     * its first spelling for [names], so each spelling of a renamed one becomes [to]. A name that goes on past its
     * credit's closing bracket ("Mira Solen (Live)" in "Kit Calloway (feat. Mira Solen) (Live)") becomes [to] inside the
     * brackets, and its text after them goes. With the setting off, all of [tag] goes to [rest].
     */
    fun replace(tag: String, renamed: Set<String>, to: String, rest: (String) -> String): String {
        if (separator == null) return rest(tag)
        // The text between the cuts, by name: the piece after a cut that does not split stays with the name before it.
        val names = mutableListOf(mutableListOf<IntRange>())
        var at = 0
        for (cut in cuts(tag)) {
            names.last() += at until cut.range.first
            if (cut.splits) names += mutableListOf<IntRange>()
            at = cut.range.last + 1
        }
        names.last() += at until tag.length
        val kept = exceptions(tag)
        val texts = names.map { pieces -> pieces.joinToString("") { tag.substring(it) }.trim() }
        val firstSpelling = HashMap<String, String>()
        texts.forEach { if (it.isNotEmpty()) firstSpelling.putIfAbsent(it.lowercase(), it) }
        val out = StringBuilder(tag.length)
        var copied = 0
        names.forEachIndexed { n, pieces ->
            val renaming = texts[n].isNotEmpty() && firstSpelling[texts[n].lowercase()] in renamed
            var placed = false
            for (piece in pieces) {
                out.append(tag, copied, piece.first)
                copied = piece.last + 1
                val raw = tag.substring(piece)
                when {
                    !renaming -> out.append(restOutside(tag, piece, kept, rest))
                    raw.isBlank() -> out.append(raw)
                    !placed -> {
                        placed = true
                        val name = raw.trim()
                        val start = raw.indexOf(name)
                        out.append(raw, 0, start).append(to).append(raw, start + name.length, raw.length)
                    }
                    // The renamed name's text past its credit's closing bracket goes with it.
                }
            }
        }
        return out.append(tag, copied, tag.length).toString()
    }

    /**
     * [tag]'s text at [piece] through [rest], but for the never-split names ([kept], in [tag]) in it, copied as they are;
     * all of it through [rest] when it holds none.
     */
    private fun restOutside(tag: String, piece: IntRange, kept: List<IntRange>, rest: (String) -> String): String {
        val inside = kept.filter { it.first <= piece.last && piece.first <= it.last }
        if (inside.isEmpty()) return rest(tag.substring(piece))
        val out = StringBuilder()
        var at = piece.first
        for (name in inside) {
            val from = maxOf(name.first, at)
            val until = minOf(name.last, piece.last) + 1
            if (from > at) out.append(rest(tag.substring(at, from)))
            out.append(tag, from, until)
            at = until
        }
        if (at <= piece.last) out.append(rest(tag.substring(at, piece.last + 1)))
        return out.toString()
    }

    /** Where [text] holds a never-split name, in order and apart. */
    private fun exceptions(text: String): List<IntRange> = exception?.findAll(text)?.map { it.range }?.toList().orEmpty()

    /** Text cut out of a tag at [range]: a separator, which [splits] it, or a bracketed credit's closing bracket, dropped. */
    private class Cut(val range: IntRange, val splits: Boolean)

    /**
     * What to cut out of [text], in order and apart: the separators, none inside an exception; a bracketed word's closing
     * bracket too ("(feat. Mira Solen)"), past any brackets of its kind opened inside it.
     */
    private fun cuts(text: String): List<Cut> {
        val separator = separator ?: return emptyList()
        val kept = exceptions(text)
        val cuts = ArrayList<Cut>()
        for (match in separator.findAll(text)) {
            if (kept.any { it.first <= match.range.last && match.range.first <= it.last }) continue
            cuts += Cut(match.range, splits = true)
            // Marks alone make a pattern with no bracket group to ask for.
            val bracket = match.groups.takeIf { it.size > BRACKET }?.get(BRACKET)?.value?.single() ?: continue
            val close = closing(text, bracket, match.range.last + 1)
            if (close >= 0) cuts += Cut(close..close, splits = false)
        }
        cuts.sortBy { it.range.first }
        // A closing bracket another separator already took is cut once.
        return cuts.fold(ArrayList<Cut>()) { apart, cut -> apart.apply { if (isEmpty() || cut.range.first > last().range.last) add(cut) } }
    }

    /** Where the bracket [open], opened before [from] in [text], closes: past those of its kind opened inside it; -1 never. */
    private fun closing(text: String, open: Char, from: Int): Int {
        val close = if (open == '(') ')' else ']'
        var inside = 0
        for (at in from until text.length) {
            when (text[at]) {
                open -> inside++
                close -> if (inside == 0) return at else inside--
            }
        }
        return -1
    }

    private companion object {
        /** The group of a word's opening bracket, in [separatorPattern]. */
        const val BRACKET: Int = 1

        /** Not a letter or digit just before: where a word may start. */
        const val WORD_START: String = """(?<![\p{L}\p{N}])"""

        /** Not a letter or digit just after: where a word may end. */
        const val WORD_END: String = """(?![\p{L}\p{N}])"""

        /** Any one of [separators]: the words first (with an opening bracket before them, kept in [BRACKET]), then the marks. */
        fun separatorPattern(separators: List<String>): Regex? {
            val (words, marks) = separators.map(String::trim).filter(String::isNotEmpty).distinct().partition { it.any(Char::isLetterOrDigit) }
            val wordForms = words.map(::wordPattern).sortedByDescending { it.length }
            val markForms = marks.sortedByDescending { it.length }.map(Regex::escape)
            val parts = listOfNotNull(
                wordForms.takeIf { it.isNotEmpty() }?.joinToString("|", prefix = """(?:([(\[])\s*)?(?:""", postfix = ")"),
                markForms.takeIf { it.isNotEmpty() }?.joinToString("|"),
            )
            return parts.takeIf { it.isNotEmpty() }?.let { Regex(it.joinToString("|"), RegexOption.IGNORE_CASE) }
        }

        /**
         * A word separator: whole, any spaces inside it as any run of spaces, a dot after it optional ("feat" or "feat."),
         * and with its dot it may touch the next name ("feat.Mira").
         */
        fun wordPattern(word: String): String {
            val stem = word.removeSuffix(".").trim()
            val body = stem.split(Regex("""\s+""")).joinToString("""\s+""", transform = Regex::escape)
            val start = if (stem.first().isLetterOrDigit()) WORD_START else ""
            val end = if (stem.last().isLetterOrDigit()) """(?:\.|$WORD_END)""" else ""
            return start + body + end
        }

        /** Any one of [exceptions] as a whole name, the longest first. */
        fun exceptionPattern(exceptions: List<String>): Regex? = exceptions.map(String::trim).filter(String::isNotEmpty).distinct()
            .sortedByDescending { it.length }
            .map { name -> (if (name.first().isLetterOrDigit()) WORD_START else "") + Regex.escape(name) + (if (name.last().isLetterOrDigit()) WORD_END else "") }
            .takeIf { it.isNotEmpty() }
            ?.let { Regex(it.joinToString("|"), RegexOption.IGNORE_CASE) }
    }
}
