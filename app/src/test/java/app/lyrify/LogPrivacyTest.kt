package app.lyrify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 3.0 log rule, guarded by a scan of KJB's own sources: no log line holds a title, artist, album, path, file name,
 * URL, lyrics, or an exception's message or stack trace (which can hold any of them), because bug reports carry
 * logcat. Counts, status codes, enum names and exception class names go in; detail goes to logcat only under
 * `adb shell setprop log.tag.KJB VERBOSE`.
 *
 * What it reads: every call of `Log.v/d/i/w/e/wtf/println` and of the logging sinks `log(…)` and `warn(…)` in the app's
 * and the TagLib module's Kotlin sources. Of each call it keeps the code and the `$name` and `${…}` parts of its strings
 * (their words are dropped), and fails on:
 * - a name that holds such a value ([NAMED]: `title`, `path`, `url`, `lyrics`, `message`, …), unless only its size or
 *   length is taken (`titles.size`, `text.length`);
 * - a whole object put in a line as a template of its own ([OBJECTS]: `$track`, `${entry}`, `$work`, `$e`), whose
 *   toString can hold all of it;
 * - a throwable handed to Log (its message and trace), unless it is handed only under VERBOSE;
 * - and anywhere: `println`, `System.out` and `System.err`.
 * Not logcat, so not read: `detail(…)`, the lyrics pipeline's song line, which the app passes on only under VERBOSE
 * (checked below), and `runLog(…)` and `notes(…)`, the AI's run log in the app's own files.
 *
 * Its limits: it reads text, not types. A value under an innocent name passes (`log(line)`, or `"$x"` where x holds a
 * title), and so do a function's result (`"${kind(e)}"`), a reason string built from an exception's message somewhere
 * else, an object joined with `+`, and a sink that forwards to logcat under another name. The tests that run the code with made-up names and check every line it logs cover what
 * it cannot see (LyricsPipelineTest, LyricsFromAudioTest, LrclibClientTest, QueueSweepTest, LyricsPipelineTrashTest,
 * ToneFilesTest, BusyFilesTest, AlbumLookupTest and others).
 */
class LogPrivacyTest {
    // The scanner itself

    @Test
    fun `a name, a path or a message in a log line is found`() {
        assertEquals(listOf("title"), problems("""log("${'$'}{track.title}: synced")"""))
        assertEquals(listOf("path"), problems("""log("skip ${'$'}{track.path}: no file extension")"""))
        assertEquals(listOf("path", "fileName"), problems("""log("${'$'}{path.fileName}: removed")"""))
        assertEquals(listOf("message"), problems("""Log.w(LOG_TAG, "refused: ${'$'}{e.message}")"""))
        assertEquals(listOf("title"), problems("""warn("say \"${'$'}title\" again")"""))
        assertEquals(listOf("name"), problems("""log("${'$'}name: AI failed")"""))
        assertEquals(listOf("lyrics"), problems("log(\"\"\"${'$'}{lyrics}\"\"\")"))
        assertEquals(listOf("title"), problems("""log("AI: ${'$'}{if (done) "done" else "on ${'$'}{job.title}"} now")"""))
    }

    @Test
    fun `a whole object or a throwable in a log line is found`() {
        assertEquals(listOf("work"), problems("""log("${'$'}work failed, skipped")"""))
        assertEquals(listOf("e"), problems("""log("not read: ${'$'}e")"""))
        assertEquals(listOf("entry"), problems("""log("kept: ${'$'}{ entry }")"""))
        assertEquals(listOf("throwable"), problems("""Log.e(LOG_TAG, "background work failed", e)"""))
        assertEquals(emptyList<String>(), problems("""Log.e(LOG_TAG, "failed: ${'$'}{e.javaClass.simpleName}", e.takeIf { verbose() })"""))
    }

    @Test
    fun `counts, kinds and the words of a string pass`() {
        assertEquals(emptyList<String>(), problems("""log("tags: ${'$'}{songs.size} songs, ${'$'}{albums.size} albums")"""))
        assertEquals(emptyList<String>(), problems("""log("unreadable (${'$'}{text.length} chars), the path and title kept")"""))
        assertEquals(emptyList<String>(), problems("""log("${'$'}{work.javaClass.simpleName} failed: ${'$'}{(e.cause ?: e).javaClass.simpleName}")"""))
        assertEquals(emptyList<String>(), problems("""log("${'$'}{spec.name}: unpacked ${'$'}{files.size} files")"""))
        assertEquals(emptyList<String>(), problems("log(\n    \"AI: ${'$'}{result.model.name} did not load; \" +\n        \"kept\",\n)"))
        assertEquals(emptyList<String>(), problems("""if (strings.any { '\u0000' in it }) log("tags: a value holds U+0000")"""))
        assertEquals(emptyList<String>(), problems("""detail("${'$'}{track.title}: ${'$'}status")"""))
        assertEquals(emptyList<String>(), problems("""runLog("AI: ${'$'}title: stopped")"""))
        assertEquals(emptyList<String>(), problems("""log("AI failed: ${'$'}{kind(e)}, ${'$'}{items.size} items")"""))
    }

    @Test
    fun `what is not a log call is not read`() {
        assertEquals(emptyList<String>(), problems("""private fun log(line: String) { Log.i(LOG_TAG, line) }"""))
        assertEquals(emptyList<String>(), problems("""val plays = StatsGraph.log(app).all(path)"""))
        assertEquals(emptyList<String>(), problems("""val log: (String) -> Unit = { Log.w(LOG_TAG, it) }"""))
        assertEquals(emptyList<String>(), problems("""// log("${'$'}{track.title}") was here"""))
        assertEquals(emptyList<String>(), problems("""val text = "log(${'$'}{track.title})""""))
    }

    @Test
    fun `println and the standard streams are found anywhere`() {
        assertEquals(listOf("println"), problems("""println(track)"""))
        assertEquals(listOf("System.err"), problems("""System.err.write(bytes)"""))
    }

    // KJB's sources

    @Test
    fun `no log line in KJB names a song, a path, a URL or lyrics`() {
        val sources = SOURCE_ROOTS.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("run from the app module", sources.size > 100)
        val found = sources.flatMap { source -> findings(source.readText()).map { "${source.path}:${it.line}: ${it.problem} in ${it.call}" } }
        assertEquals(found.joinToString("\n"), 0, found.size)
    }

    @Test
    fun `the song line reaches logcat only under VERBOSE`() {
        val wirings = SOURCE_ROOTS.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { source -> DETAIL_WIRING.findAll(source.readText()).map { source.name to it.value }.toList() }
        assertTrue("the lyrics pipeline's detail is wired", wirings.isNotEmpty())
        wirings.forEach { (file, wiring) -> assertTrue("$file: $wiring", "verbose()" in wiring) }
    }

    // The scanner

    private data class Finding(val line: Int, val problem: String, val call: String)

    private fun problems(source: String): List<String> = findings(source).map { it.problem }

    private fun findings(source: String): List<Finding> {
        val plain = blank(source)
        fun lineOf(offset: Int) = source.substring(0, offset).count { it == '\n' } + 1
        val found = mutableListOf<Finding>()
        STREAMS.findAll(plain).forEach { found += Finding(lineOf(it.range.first), it.groupValues[1], it.value) }
        for (call in CALL.findAll(plain)) {
            if (plain.substring(0, call.range.first).trimEnd().endsWith("fun")) continue
            val arguments = argumentsAt(plain, call.range.last)
            val shown = source.substring(call.range.first, minOf(source.length, call.range.last + 1 + arguments.length + 1)).replace(Regex("""\s+"""), " ")
            problemsIn(arguments, isLog = call.value.startsWith("Log.")).forEach { found += Finding(lineOf(call.range.first), it, shown) }
        }
        return found
    }

    /** The problems of one call's [arguments], the words of its strings already dropped. */
    private fun problemsIn(arguments: String, isLog: Boolean): List<String> {
        val parts = topLevel(arguments)
        // Log's own arguments: the tag, the line, the throwable.
        val read = if (isLog) parts.drop(1).take(1) else parts
        val problems = read.flatMap(::namesIn).toMutableList()
        if (isLog && parts.size > 2 && "verbose()" !in parts[2]) problems += "throwable"
        return problems.distinct()
    }

    private fun namesIn(text: String): List<String> = IDENTIFIER.findAll(text).mapNotNull { token ->
        val name = token.value
        val member = text.substring(0, token.range.first).trimEnd().endsWith(".")
        val after = text.substring(token.range.last + 1)
        // `e.cause`, `e?.cause`, and `(e.cause ?: e).javaClass`: a member is read, not the whole object.
        val dotted = MEMBER_NEXT.containsMatchIn(after)
        // A template of its own: `$e`, or `${e}` with nothing else in the braces.
        val before = text.substring(0, token.range.first)
        val whole = before.endsWith(SIMPLE) || (before.trimEnd().endsWith(OPEN) && after.trimStart().startsWith(CLOSE))
        when {
            name == "name" -> name.takeIf { !member }
            name in NAMED -> name.takeUnless { COUNTED.matchesAt(after, 0) }
            name in OBJECTS -> name.takeIf { whole && !dotted }
            else -> null
        }
    }.toList()

    private fun topLevel(arguments: String): List<String> {
        val parts = mutableListOf(StringBuilder())
        var depth = 0
        for (c in arguments) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if (c == ',' && depth == 0) parts += StringBuilder() else parts.last().append(c)
        }
        // A trailing comma leaves an empty last part; a blanked string stays as an empty one in its place.
        return parts.map { it.toString().trim() }.let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    }

    /** The arguments of the call whose `(` is at [open] in [plain]: the text up to its matching `)`. */
    private fun argumentsAt(plain: String, open: Int): String {
        var depth = 0
        for (i in open until plain.length) {
            when (plain[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth == 0) return plain.substring(open + 1, i)
            }
        }
        return plain.substring(open + 1)
    }

    /**
     * [source] with its comments, char literals and the words of its strings blanked (newlines and length kept, so
     * offsets and lines stay), and the code of its string templates kept: `"${a.b} and $c"` reads `a.b` and `c`. A
     * template's `$` becomes [SIMPLE], and `${` and its `}` become [OPEN] and [CLOSE].
     */
    private fun blank(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        fun blankOut(from: Int, to: Int) {
            for (k in from until minOf(to, source.length)) if (out[k] != '\n') out[k] = ' '
        }
        // The open `${` templates: the string each sits in, and the brace depth it opened at.
        val templates = ArrayDeque<Pair<Boolean, Int>>()
        var braces = 0

        /** Reads a string's body from [i] to its end, or to a `${` it then leaves to the code loop. */
        fun body(raw: Boolean) {
            while (i < source.length) {
                if (raw && source.startsWith("\"\"\"", i)) {
                    blankOut(i, i + 3)
                    i += 3
                    return
                }
                val c = source[i]
                when {
                    !raw && c == '\\' -> {
                        blankOut(i, i + 2)
                        i += 2
                    }
                    !raw && c == '"' -> {
                        blankOut(i, i + 1)
                        i++
                        return
                    }
                    c == '$' && source.getOrNull(i + 1) == '{' -> {
                        blankOut(i, i + 2)
                        out[i] = OPEN
                        i += 2
                        templates.addLast(raw to braces)
                        braces++
                        return
                    }
                    c == '$' && source.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                        out[i] = SIMPLE
                        i++
                        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    }
                    else -> {
                        blankOut(i, i + 1)
                        i++
                    }
                }
            }
        }

        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blankOut(i, end)
                    i = end
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    blankOut(i, end)
                    i = end
                }
                source.startsWith("\"\"\"", i) -> {
                    blankOut(i, i + 3)
                    i += 3
                    body(raw = true)
                }
                c == '"' -> {
                    blankOut(i, i + 1)
                    i++
                    body(raw = false)
                }
                c == '\'' -> {
                    var end = i + 1
                    while (end < source.length && source[end] != '\'') end += if (source[end] == '\\') 2 else 1
                    blankOut(i, end + 1)
                    i = end + 1
                }
                c == '{' -> {
                    braces++
                    i++
                }
                c == '}' -> {
                    braces--
                    val open = templates.lastOrNull()
                    if (open != null && open.second == braces) {
                        // The template ends: back into the string it sat in.
                        templates.removeLast()
                        out[i] = CLOSE
                        i++
                        body(open.first)
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        return out.toString()
    }

    private companion object {
        val SOURCE_ROOTS = listOf("src/main/java", "src/debug/java", "../taglib/src/main/java")

        /** A call of Log or of a logging sink; `x.log(` (another object's function) is not one. */
        val CALL = Regex("""(?<![\w.])(?:Log\.(?:v|d|i|w|e|wtf|println)|log|warn)\(""")

        val STREAMS = Regex("""(?<![\w.])(println|System\.out|System\.err)\b""")

        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        val MEMBER_NEXT = Regex("""^\)*\s*\??\.""")

        /** Where a string's templates were: `$name`, and `${` with its `}`. */
        const val SIMPLE: Char = '\u0001'
        const val OPEN: Char = '\u0002'
        const val CLOSE: Char = '\u0003'

        /** Only counted: `.size`, `.length` and the like right after the name. */
        val COUNTED = Regex("""\s*\??\.\s*(size|length|count|lastIndex|isEmpty|isNotEmpty)\b""")

        /** Names whose value is a song's name, a path, an address, lyrics, or an exception's words. */
        val NAMED = setOf(
            "title", "titles", "artist", "artists", "album", "albums", "albumArtist", "displayName", "genre",
            "path", "paths", "fileName", "absolutePath", "canonicalPath", "parentFile",
            "uri", "url", "mediaUri", "mediaId", "location", "query",
            "lyrics", "plainLyrics", "syncedLyrics", "text", "lines",
            "message", "localizedMessage", "stackTraceToString", "printStackTrace", "stackTrace", "toString",
        )

        /** Objects whose toString holds a song or a message: put whole in a line, they leak it. */
        val OBJECTS = setOf(
            "track", "tracks", "entry", "entries", "song", "songs", "item", "items", "work", "row", "rows", "file", "files",
            "record", "candidate", "job", "intent", "metadata", "e",
        )

        /** `detail = { … }`: where the lyrics pipeline's song line is wired. */
        val DETAIL_WIRING = Regex("""detail\s*=\s*\{[^}]*\}""")
    }
}
