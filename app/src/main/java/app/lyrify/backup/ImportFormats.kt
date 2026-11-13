package app.lyrify.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToLong

// Import from another app (lane IM1, research import-players-research.md): the files other players export, read into
// one neutral model. Readers only produce an ImportBundle; matching and writing are ImportPlan's.

/**
 * A song an import names: where the file said it is ([location], as written, for the "not found" list), where that
 * points ([path]: absolute, or below a root this phone does not know, [M3u.matchPath]; null for a stream's URL), and
 * whatever the file said of it. Anything may be missing: a missing clue is skipped when matching, never a mismatch.
 */
data class ImportSong(
    val location: String,
    val path: String?,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    /** "Artist - Title" in one text (m3u's `#EXTINF`, pls's `TitleN`), read two ways when matching. */
    val info: String? = null,
    /** Where it is below its storage root ("Music/Rips/01 Song.mp3"), when the file says so apart from [path]: Musicolet's. */
    val relativePath: String? = null,
    /** Its MediaStore id on the phone the file was made on: Musicolet's, found again only with the same file name. */
    val mediaStoreId: Long? = null,
) {
    /** How the "not found" list shows it: its title and artist, else [info], else the file's name. */
    val label: String get() = title?.let { listOfNotNull(it, artist).joinToString(" · ") } ?: info ?: location.substringAfterLast('/').substringAfterLast('\\')
}

/** A playlist an import holds: its name (null when the file gives none) and its songs in order, a song possibly twice. */
data class ImportPlaylist(val name: String?, val songs: List<ImportSong>)

/** A song's play count in the other app, and when it was last played there (wall-clock millis), when the file says. */
data class ImportPlays(val song: ImportSong, val count: Int, val lastPlayedAt: Long? = null)

/** A queue another app kept: its songs in order, the [index] of the one it was at and the [positionMs] in it. */
data class ImportQueue(val songs: List<ImportSong>, val index: Int = 0, val positionMs: Long = 0)

/** What an import read, in one neutral form whatever the file was. */
data class ImportBundle(
    val playlists: List<ImportPlaylist> = emptyList(),
    val favourites: List<ImportSong> = emptyList(),
    val plays: List<ImportPlays> = emptyList(),
    val queues: List<ImportQueue> = emptyList(),
) {
    /** Every song it names, repeats counted: what the caps count. */
    val size: Int get() = playlists.sumOf { it.songs.size } + favourites.size + plays.size + queues.sumOf { it.songs.size }

    operator fun plus(other: ImportBundle): ImportBundle =
        ImportBundle(playlists + other.playlists, favourites + other.favourites, plays + other.plays, queues + other.queues)
}

/** What a file was found to be by its content, or the playlists other apps saved on the phone. */
enum class ImportFormat { M3U, PLS, XSPF, PHONE, MUSICOLET }

/** Why a file was not read, as the sheet says it. */
enum class ImportFailure {
    /** Not a playlist KJB reads: another kind of file, or a zip that is no Musicolet backup. */
    NOT_RECOGNISED,

    /** Over the caps: bytes, songs, a line, the depth of its XML, or the pick's files and songs together. */
    TOO_BIG,

    /** Damaged: XML that does not close, an unknown entity, a stream that broke. */
    UNREADABLE,

    /** A playlist with no song in it. */
    EMPTY,

    /** A Musicolet backup whose key, checksums or contents are not what KJB knows: nothing in it is used. */
    MUSICOLET,
}

/** One picked file read: its playlists, or why not. [name] is the file's, when its provider says. */
sealed interface ImportRead {
    val name: String?

    data class Read(override val name: String?, val format: ImportFormat, val bundle: ImportBundle) : ImportRead

    data class Failed(override val name: String?, val why: ImportFailure) : ImportRead
}

/** Everything one pick read: every file's songs in one bundle, how many files that is, and the files not read with why. */
data class ImportPick(val bundle: ImportBundle, val read: Int, val failed: List<ImportRead.Failed>)

/**
 * Reads the playlist files other players export, by what they hold rather than their names: extended or plain m3u and
 * m3u8, pls, and xspf. Every file is untrusted: never more than [MAX_BYTES], [MAX_ENTRIES] songs or a line of
 * [MAX_LINE_CHARS]; XML is read by a reader of its own that never processes a DTD or an entity it does not know, at
 * most [MAX_DEPTH] deep; each text is one line without control or direction characters, and names are capped at
 * [MAX_NAME_CHARS]. Paths are strings only, never opened.
 */
object ImportFormats {
    const val MAX_BYTES: Int = M3u.MAX_BYTES
    const val MAX_ENTRIES: Int = M3u.MAX_ENTRIES
    const val MAX_LINE_CHARS: Int = M3u.MAX_LINE_CHARS

    /** Elements inside each other in an xspf: its own need 5. */
    const val MAX_DEPTH: Int = 32

    /** A pick reads at most this many files... */
    const val MAX_FILES: Int = 500

    /** ...holding this many songs together. */
    const val MAX_TOTAL_ENTRIES: Int = 100_000

    const val MAX_NAME_CHARS: Int = 200

    /** A zip (a Musicolet backup) is read up to this size; any other file up to [MAX_BYTES]. */
    const val MAX_ARCHIVE_BYTES: Int = 64 * 1024 * 1024

    /** Any file: the content says what it is, and providers name these types every way. */
    val MIME_TYPES: Array<String> = arrayOf("*/*")

    /**
     * What [bytes] (the file [fileName], in [folder] when its provider says, for relative paths) hold: a playlist of a
     * format KJB reads, or why not. The content decides, the name only when the content cannot: `[playlist]` is pls, XML
     * whose root is `playlist` is xspf, `#EXTM3U` is m3u, and so are lines naming songs, or any text in a file named
     * `.m3u` or `.m3u8`. Binary files (an audio file, a PDF, a database) are not recognised whatever their names.
     */
    fun read(bytes: ByteArray, fileName: String?, folder: String?, musicolet: MusicoletDb? = null): ImportRead {
        val name = clean(fileName, MAX_NAME_CHARS)
        fun failed(why: ImportFailure) = ImportRead.Failed(name, why)
        if (startsWith(bytes, ZIP) || startsWith(bytes, EMPTY_ZIP)) {
            // A zip is a Musicolet backup or nothing KJB reads ([MAX_ARCHIVE_BYTES] at most, read by [musicolet]).
            if (bytes.size > MAX_ARCHIVE_BYTES) return failed(ImportFailure.TOO_BIG)
            return when (val read = musicolet?.let { Musicolet.read(bytes, it) } ?: MusicoletRead.Failed(ImportFailure.NOT_RECOGNISED)) {
                is MusicoletRead.Failed -> failed(read.why)
                is MusicoletRead.Read -> if (read.bundle.size == 0) failed(ImportFailure.EMPTY) else ImportRead.Read(name, ImportFormat.MUSICOLET, read.bundle)
            }
        }
        if (bytes.size > MAX_BYTES) return failed(ImportFailure.TOO_BIG)
        if (BINARY.any { startsWith(bytes, it) } || startsWith(bytes, MP4, at = 4)) return failed(ImportFailure.NOT_RECOGNISED)
        val extension = extension(fileName)
        val text = M3u.decode(bytes, utf8 = extension == M3u.EXTENSION).removePrefix(BOM)
        if (binary(text)) return failed(ImportFailure.NOT_RECOGNISED)
        val parsed = when (sniff(text, extension)) {
            ImportFormat.M3U -> m3u(text, folder)
            ImportFormat.PLS -> pls(text, folder)
            ImportFormat.XSPF -> xspf(text, folder)
            ImportFormat.PHONE, ImportFormat.MUSICOLET, null -> return failed(ImportFailure.NOT_RECOGNISED)
        }
        return when (parsed) {
            is Parsed.Failed -> failed(parsed.why)
            is Parsed.Songs -> if (parsed.songs.isEmpty()) {
                failed(ImportFailure.EMPTY)
            } else {
                ImportRead.Read(name, parsed.format, ImportBundle(listOf(ImportPlaylist(cleanName(parsed.title) ?: cleanName(playlistName(fileName)), parsed.songs))))
            }
        }
    }

    /**
     * [input] read whole into memory, or null past its cap: [MAX_ARCHIVE_BYTES] for a zip (a Musicolet backup), else
     * [MAX_BYTES]. The first bytes decide which.
     */
    fun readBounded(input: InputStream): ByteArray? {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        var cap: Int? = null
        while (true) {
            val read = input.read(chunk)
            if (read < 0) return bytes.toByteArray()
            bytes.write(chunk, 0, read)
            // The cap is set once, as soon as the zip's magic can be seen.
            if (cap == null && bytes.size() >= ZIP.size) cap = if (startsWith(bytes.toByteArray(), ZIP)) MAX_ARCHIVE_BYTES else MAX_BYTES
            if (bytes.size() > (cap ?: MAX_BYTES)) return null
        }
    }

    /** The [reads] of one pick together, the files over [MAX_FILES] and the songs over [MAX_TOTAL_ENTRIES] left out as too big. */
    fun combine(reads: List<ImportRead>): ImportPick {
        var bundle = ImportBundle()
        var read = 0
        val failed = mutableListOf<ImportRead.Failed>()
        reads.forEach { file ->
            when (file) {
                is ImportRead.Failed -> failed += file
                is ImportRead.Read -> if (read >= MAX_FILES || bundle.size + file.bundle.size > MAX_TOTAL_ENTRIES) {
                    failed += ImportRead.Failed(file.name, ImportFailure.TOO_BIG)
                } else {
                    bundle += file.bundle
                    read++
                }
            }
        }
        return ImportPick(bundle, read, failed)
    }

    /** Whether a folder's file [fileName] may be a Musicolet backup: its name ends in `.zip`. */
    fun isArchive(fileName: String?): Boolean = extension(fileName) == ARCHIVE

    /** Whether a folder's file [fileName] is worth reading: its name ends in a playlist's extension. */
    fun isPlaylistFile(fileName: String?): Boolean = extension(fileName) in PLAYLIST_EXTENSIONS

    /** A playlist's name from its file's: a playlist's extension (or `.txt`) taken off; null when that leaves nothing. */
    fun playlistName(fileName: String?): String? {
        val name = fileName?.trim() ?: return null
        return (if (extension(name) in NAME_EXTENSIONS) name.substringBeforeLast('.') else name).trim().takeIf(String::isNotEmpty)
    }

    /** [text] on one line, without control or direction characters, trimmed and capped at [max] chars; null when that leaves nothing. */
    fun clean(text: String?, max: Int = MAX_LINE_CHARS): String? {
        val one = text?.replace(BREAKS, " ")?.filterNot { it.isISOControl() || it in BIDI }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (one.length <= max) return one
        // Never half a character: a cap inside a surrogate pair takes one char less.
        val end = if (one[max - 1].isHighSurrogate()) max - 1 else max
        return one.substring(0, end).trim().takeIf(String::isNotEmpty)
    }

    /** A playlist's name as KJB keeps it: [clean], at most [MAX_NAME_CHARS]. */
    fun cleanName(text: String?): String? = clean(text, MAX_NAME_CHARS)

    // What a file is

    private sealed interface Parsed {
        data class Songs(val format: ImportFormat, val title: String?, val songs: List<ImportSong>) : Parsed

        data class Failed(val why: ImportFailure) : Parsed
    }

    private fun sniff(text: String, extension: String?): ImportFormat? {
        val start = text.trimStart()
        val first = start.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            start.startsWith(PLS_HEADER, ignoreCase = true) -> ImportFormat.PLS
            start.startsWith('<') -> ImportFormat.XSPF.takeIf { XmlReader(start).root() == XSPF_ROOT }
            start.startsWith(M3U_HEADER, ignoreCase = true) -> ImportFormat.M3U
            PLS_LINE.containsMatchIn(first) || extension == PLS -> ImportFormat.PLS
            extension == M3U || extension == M3u.EXTENSION -> ImportFormat.M3U
            namesSongs(text) -> ImportFormat.M3U
            else -> null
        }
    }

    /** Whether every line of [text] that is not blank or a comment names a song file or a stream, and one does. */
    private fun namesSongs(text: String): Boolean {
        val lines = text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }
        return lines.any() && lines.all { AUDIO.containsMatchIn(it) || SCHEME.containsMatchIn(it) }
    }

    /** Text with more than a few control characters, and more than one in 50: a binary file decoded. */
    private fun binary(text: String): Boolean {
        val controls = text.count { it.isISOControl() && it !in TEXT_CONTROLS }
        return controls > 8 && controls * 50 > text.length
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray, at: Int = 0): Boolean =
        bytes.size >= at + magic.size && magic.indices.all { bytes[at + it] == magic[it] }

    private fun extension(fileName: String?): String? = fileName?.trim()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

    // m3u and m3u8: M3u's own reader

    private fun m3u(text: String, folder: String?): Parsed {
        val entries = M3u.parse(text) ?: return Parsed.Failed(ImportFailure.TOO_BIG)
        return Parsed.Songs(ImportFormat.M3U, null, entries.mapNotNull { song(it.location, folder, info = it.info, durationMs = it.durationMs) })
    }

    /** A line's song: its location cleaned, where it points, and what the file said of it; null for an empty line. */
    private fun song(location: String, folder: String?, info: String? = null, durationMs: Long? = null): ImportSong? {
        val written = clean(location) ?: return null
        return ImportSong(written, M3u.matchPath(written, folder), durationMs = durationMs, info = clean(info))
    }

    // pls: an INI file, `FileN`, `TitleN` and `LengthN` in its `[playlist]` section

    private fun pls(text: String, folder: String?): Parsed {
        val files = HashMap<Int, String>()
        val titles = HashMap<Int, String>()
        val lengths = HashMap<Int, String>()
        var section: String? = null
        for (raw in text.lineSequence()) {
            if (raw.length > MAX_LINE_CHARS) return Parsed.Failed(ImportFailure.TOO_BIG)
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith(';') || line.startsWith('#') -> continue
                line.startsWith('[') && line.endsWith(']') -> section = line.substring(1, line.length - 1).trim().lowercase(Locale.ROOT)
                section != null && section != PLS_SECTION -> continue
                else -> {
                    val key = PLS_KEY.matchEntire(line.substringBefore('=', "").trim()) ?: continue
                    val index = key.groupValues[2].toInt()
                    val into = when (key.groupValues[1].lowercase(Locale.ROOT)) {
                        "file" -> files
                        "title" -> titles
                        else -> lengths
                    }
                    into.putIfAbsent(index, line.substringAfter('=').trim())
                    if (files.size > MAX_ENTRIES || titles.size + lengths.size > 2 * MAX_ENTRIES) return Parsed.Failed(ImportFailure.TOO_BIG)
                }
            }
        }
        val songs = files.keys.sorted().mapNotNull { at -> song(files.getValue(at), folder, info = titles[at], durationMs = millis(lengths[at])) }
        return Parsed.Songs(ImportFormat.PLS, null, songs)
    }

    /** Seconds as written ("240", "240.5"); null for -1, 0, or what is no number of milliseconds a Long holds. */
    private fun millis(seconds: String?): Long? = seconds?.trim()?.toDoubleOrNull()?.times(1000)?.takeIf { it > 0 && it < Long.MAX_VALUE.toDouble() }?.roundToLong()

    // xspf: http://xspf.org/ns/0/, read by a reader of its own

    private fun xspf(text: String, folder: String?): Parsed {
        val damaged = Parsed.Failed(ImportFailure.UNREADABLE)
        val xml = XmlReader(text)
        // The open elements as written (their end tags must match) and by their local names.
        val open = ArrayList<String>()
        val names = ArrayList<String>()
        var closed = false
        var title: String? = null
        val songs = mutableListOf<ImportSong>()
        var tracks = 0
        var track: MutableMap<String, String>? = null
        // The text of the field being read (the playlist's title, a track's location...), and its depth.
        var field: StringBuilder? = null
        var fieldDepth = 0
        fun close() {
            val value = field
            if (value != null && names.size == fieldDepth) {
                if (fieldDepth == TITLE.size) title = value.toString() else track?.putIfAbsent(names.last(), value.toString())
                field = null
            }
            if (names == TRACK) {
                track?.let { fields -> xspfSong(fields, folder)?.let(songs::add) }
                track = null
            }
            open.removeAt(open.lastIndex)
            names.removeAt(names.lastIndex)
            closed = open.isEmpty()
        }
        while (true) {
            when (val event = xml.next() ?: return damaged) {
                XmlReader.Event.Done -> return if (closed) Parsed.Songs(ImportFormat.XSPF, title, songs) else damaged
                is XmlReader.Event.Text -> {
                    val value = field
                    if (open.isEmpty() && event.text.isNotBlank()) return damaged
                    if (value != null && names.size == fieldDepth) {
                        value.append(event.text)
                        if (value.length > MAX_LINE_CHARS) return Parsed.Failed(ImportFailure.TOO_BIG)
                    }
                }
                is XmlReader.Event.Start -> {
                    val local = event.name.substringAfterLast(':')
                    if (closed || (open.isEmpty() && local != XSPF_ROOT)) return damaged
                    if (open.size >= MAX_DEPTH) return Parsed.Failed(ImportFailure.TOO_BIG)
                    open += event.name
                    names += local
                    val readsTitle = names == TITLE && title == null
                    val readsField = names.size == TRACK.size + 1 && names.subList(0, TRACK.size) == TRACK && local in TRACK_FIELDS && track?.containsKey(local) == false
                    if (field == null && (readsTitle || readsField)) {
                        field = StringBuilder()
                        fieldDepth = names.size
                    }
                    if (names == TRACK) {
                        if (++tracks > MAX_ENTRIES) return Parsed.Failed(ImportFailure.TOO_BIG)
                        track = HashMap()
                    }
                    if (event.empty) close()
                }
                is XmlReader.Event.End -> {
                    if (open.lastOrNull() != event.name) return damaged
                    close()
                }
            }
        }
    }

    /** A track's song: by its first location, else by its title alone; null with neither. */
    private fun xspfSong(fields: Map<String, String>, folder: String?): ImportSong? {
        val location = clean(fields[LOCATION])
        val title = clean(fields[TITLE_FIELD])
        if (location == null && title == null) return null
        // A location is a URI: a relative one is percent-encoded.
        val path = location?.let { if (SCHEME.containsMatchIn(it)) M3u.matchPath(it, folder) else M3u.matchPath(M3u.percentDecoded(it), folder) }
        return ImportSong(
            location = location.orEmpty(),
            path = path,
            title = title,
            artist = clean(fields[CREATOR]),
            album = clean(fields[ALBUM]),
            durationMs = fields[DURATION]?.trim()?.toLongOrNull()?.takeIf { it > 0 },
        )
    }

    private const val CHUNK: Int = 64 * 1024
    private const val BOM: String = "\uFEFF"
    private const val PLS_HEADER: String = "[playlist]"
    private const val PLS_SECTION: String = "playlist"
    private const val PLS: String = "pls"
    private const val ARCHIVE: String = "zip"
    private const val M3U: String = "m3u"
    private const val M3U_HEADER: String = "#EXTM3U"
    private const val XSPF_ROOT: String = "playlist"
    private const val LOCATION: String = "location"
    private const val TITLE_FIELD: String = "title"
    private const val CREATOR: String = "creator"
    private const val ALBUM: String = "album"
    private const val DURATION: String = "duration"
    private const val TEXT_CONTROLS: String = "\t\n\r\u000C"
    private val TITLE: List<String> = listOf(XSPF_ROOT, TITLE_FIELD)
    private val TRACK: List<String> = listOf(XSPF_ROOT, "trackList", "track")
    private val TRACK_FIELDS: Set<String> = setOf(LOCATION, TITLE_FIELD, CREATOR, ALBUM, DURATION)
    private val PLAYLIST_EXTENSIONS: Set<String> = setOf(M3U, M3u.EXTENSION, PLS, "xspf")
    private val NAME_EXTENSIONS: Set<String> = PLAYLIST_EXTENSIONS + "txt"

    /** `File1`, `title12`, `LENGTH3`: an index an Int holds. */
    private val PLS_KEY = Regex("""(file|title|length)(\d{1,9})""", RegexOption.IGNORE_CASE)
    private val PLS_LINE = Regex("""^file\d{1,9}\s*=""", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]+:""")
    private val AUDIO = Regex("""\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|wma|ape|aiff?|mka|wv|mpc|dsf|alac|mp4|3gp|amr|mid|midi)$""", RegexOption.IGNORE_CASE)

    /** Line and paragraph breaks and tabs, which become spaces. */
    private val BREAKS = Regex("[\\t\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]+")

    /** The marks that turn text around (bidi overrides, isolates and marks): a name could hide what it is with them. */
    private const val BIDI: String = "\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

    private val ZIP: ByteArray = byteArrayOf(0x50, 0x4B, 3, 4)
    private val EMPTY_ZIP: ByteArray = byteArrayOf(0x50, 0x4B, 5, 6)
    private val MP4: ByteArray = "ftyp".encodeToByteArray()

    /** Files that are not text: a database, a PDF, audio (ID3, FLAC, Ogg, WAV), gzip, PNG and JPEG pictures. */
    private val BINARY: List<ByteArray> = listOf(
        "SQLite format 3\u0000".encodeToByteArray(),
        "%PDF".encodeToByteArray(),
        "ID3".encodeToByteArray(),
        "fLaC".encodeToByteArray(),
        "OggS".encodeToByteArray(),
        "RIFF".encodeToByteArray(),
        byteArrayOf(0x1F, 0x8B.toByte()),
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
    )
}

/**
 * A strict, small XML reader, enough for xspf, the same on the phone and in JVM tests. It never processes a DTD: a
 * DOCTYPE before the root is skipped whole, so an entity it declares stays unknown. Only XML's five entities and
 * character references are read; any other entity, a tag that does not close, or markup it does not know is damage.
 * It reads ahead without recursion, so nesting costs no stack (its caller caps the depth).
 */
private class XmlReader(private val text: String) {
    sealed interface Event {
        /** [name] as written, prefix and all; [empty] for `<name/>`. */
        data class Start(val name: String, val empty: Boolean) : Event

        data class End(val name: String) : Event

        /** Text with its references read, or a CDATA section. */
        data class Text(val text: String) : Event

        data object Done : Event
    }

    private var at = 0
    private var started = false

    /** The root element's local name; null when the XML is damaged before it. */
    fun root(): String? = generateSequence { next() }.takeWhile { it != Event.Done }.firstOrNull { it is Event.Start }
        ?.let { (it as Event.Start).name.substringAfterLast(':') }

    /** The next event; null when the XML is damaged. */
    fun next(): Event? {
        while (true) {
            if (at >= text.length) return Event.Done
            if (text[at] != '<') return text()
            when {
                text.startsWith("<?", at) -> at = past("?>", at + 2) ?: return null
                text.startsWith("<!--", at) -> at = past("-->", at + 4) ?: return null
                text.startsWith(CDATA, at) -> {
                    val end = text.indexOf("]]>", at + CDATA.length).takeIf { it >= 0 } ?: return null
                    return Event.Text(text.substring(at + CDATA.length, end)).also { at = end + 3 }
                }
                text.startsWith("<!DOCTYPE", at) && !started -> at = doctype() ?: return null
                text.startsWith("<!", at) -> return null
                text.startsWith("</", at) -> return end()
                else -> return start()?.also { started = true }
            }
        }
    }

    private fun text(): Event? {
        val end = text.indexOf('<', at).takeIf { it >= 0 } ?: text.length
        val raw = text.substring(at, end)
        at = end
        return decoded(raw)?.let(Event::Text)
    }

    private fun start(): Event? {
        var i = at + 1
        val name = name(i) ?: return null
        i += name.length
        var attributes = 0
        while (true) {
            val spaced = space(i)
            val gap = spaced > i
            i = spaced
            when {
                i >= text.length -> return null
                text.startsWith("/>", i) -> return Event.Start(name, empty = true).also { at = i + 2 }
                text[i] == '>' -> return Event.Start(name, empty = false).also { at = i + 1 }
                !gap -> return null
                else -> {
                    if (++attributes > MAX_ATTRIBUTES) return null
                    i += (name(i) ?: return null).length
                    i = space(i)
                    if (i >= text.length || text[i] != '=') return null
                    i = space(i + 1)
                    val quote = text.getOrNull(i)?.takeIf { it == '"' || it == '\'' } ?: return null
                    val end = text.indexOf(quote, i + 1).takeIf { it >= 0 } ?: return null
                    val value = text.substring(i + 1, end)
                    if ('<' in value || decoded(value) == null) return null
                    i = end + 1
                }
            }
        }
    }

    private fun end(): Event? {
        val name = name(at + 2) ?: return null
        val i = space(at + 2 + name.length)
        if (text.getOrNull(i) != '>') return null
        at = i + 1
        return Event.End(name)
    }

    /** Past the DOCTYPE at [at], its internal subset (with its quotes and comments) and all; null when it never ends. */
    private fun doctype(): Int? {
        var i = at + "<!DOCTYPE".length
        var subset = false
        while (i < text.length) {
            val c = text[i]
            when {
                subset && text.startsWith("<!--", i) -> i = past("-->", i + 4) ?: return null
                c == '"' || c == '\'' -> i = (text.indexOf(c, i + 1).takeIf { it >= 0 } ?: return null) + 1
                c == '>' && !subset -> return i + 1
                else -> {
                    if (c == '[') subset = true else if (c == ']') subset = false
                    i++
                }
            }
        }
        return null
    }

    /** The index just past [marker] found from [from]; null when it is not there. */
    private fun past(marker: String, from: Int): Int? = text.indexOf(marker, from).takeIf { it >= 0 }?.plus(marker.length)

    private fun space(from: Int): Int {
        var i = from
        while (i < text.length && text[i] in SPACE) i++
        return i
    }

    /** The XML name at [from]; null when none starts there. */
    private fun name(from: Int): String? {
        if (from >= text.length || !nameStart(text[from])) return null
        var i = from + 1
        while (i < text.length && (nameStart(text[i]) || text[i].isDigit() || text[i] == '-' || text[i] == '.')) i++
        return text.substring(from, i)
    }

    private fun nameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':' || c.code >= 0x80

    /** [raw] with its references read; null for an entity XML does not define or a character it does not allow. */
    private fun decoded(raw: String): String? {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = raw.indexOf(';', i + 1).takeIf { it in (i + 2)..(i + MAX_REFERENCE) } ?: return null
            val reference = raw.substring(i + 1, end)
            when {
                reference.startsWith("#x") -> out.appendCodePoint(character(reference.substring(2).toIntOrNull(16)) ?: return null)
                reference.startsWith("#") -> out.appendCodePoint(character(reference.substring(1).toIntOrNull()) ?: return null)
                else -> out.append(ENTITIES[reference] ?: return null)
            }
            i = end + 1
        }
        return out.toString()
    }

    /** [code] when XML allows it as a character. */
    private fun character(code: Int?): Int? = code?.takeIf {
        it == 0x9 || it == 0xA || it == 0xD || it in 0x20..0xD7FF || it in 0xE000..0xFFFD || it in 0x10000..0x10FFFF
    }

    private companion object {
        const val CDATA: String = "<![CDATA["
        const val SPACE: String = " \t\r\n"
        const val MAX_ATTRIBUTES: Int = 64

        /** "&#x10FFFF;" is the longest reference. */
        const val MAX_REFERENCE: Int = 10
        val ENTITIES: Map<String, Char> = mapOf("amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'')
    }
}
