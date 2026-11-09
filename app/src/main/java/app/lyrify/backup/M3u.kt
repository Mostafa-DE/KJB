package app.lyrify.backup

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.library.FindBy
import app.lyrify.library.Song
import app.lyrify.library.SongClue
import app.lyrify.library.SongRef
import app.lyrify.library.SongResolver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.roundToLong

/** One song line of an m3u file: where it says the song is, and what the `#EXTINF` line before it said. */
data class M3uEntry(
    /** The path or URL as written: absolute, relative to the playlist's folder, a Windows path or a `file://` URL. */
    val location: String,
    /** `#EXTINF`'s text after the comma, "Artist - Title" by custom; null without one. */
    val info: String? = null,
    /** `#EXTINF`'s length; null without one, or for -1 and 0 (a stream, or not known). */
    val durationMs: Long? = null,
) {
    /** How the import result lists it when it is missing: [info], else the file's name. */
    val label: String get() = info ?: location.substringAfterLast('/').substringAfterLast('\\')
}

/** What one m3u file found in the library: its songs in the file's order (a song possibly twice), and the entries missing. */
data class M3uImport(val name: String, val found: List<Song>, val missing: List<M3uEntry>) {
    /** "18 of 20 songs found": [found] of this many. */
    val total: Int get() = found.size + missing.size
}

/**
 * Playlists as extended M3U files (spec "Playlists: import and export (m3u)"): written as UTF-8 `.m3u8` with LF line
 * ends and no BOM; read from `.m3u` or `.m3u8`, with any BOM, any line ends, and `.m3u` in UTF-8 or Latin-1. Entries are
 * found in the library by their path, then their file name in its folder, then `#EXTINF`'s title, artist and length
 * ([SongResolver.M3U]). KJB never writes MediaStore playlists (deprecated since API 31).
 */
object M3u {
    /** Files larger than this are not playlists (Gramophone's cap). */
    const val MAX_BYTES: Int = 5 * 1024 * 1024

    const val EXTENSION: String = "m3u8"

    /** What Android calls m3u files, for the save dialog when the phone knows no type for [EXTENSION]. */
    const val MIME_TYPE: String = "audio/x-mpegurl"

    /** What the open dialog offers: the types phones and computers give `.m3u` and `.m3u8` files. */
    val MIME_TYPES: Array<String> = arrayOf(MIME_TYPE, "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl")

    // Writing

    /**
     * The playlist of [songs] as extended M3U: each song found on the phone ([SongResolver.IN_APP]) with its `#EXTINF`
     * and its path now, a song no longer there by its stored path alone.
     */
    fun write(songs: List<SongRef>, resolver: SongResolver): String = buildString {
        append(HEADER).append('\n')
        songs.forEach { ref ->
            val song = resolver.find(ref)
            if (song != null) append(EXTINF).append(seconds(song.durationMs)).append(',').append(info(song)).append('\n')
            append(song?.path ?: ref.path).append('\n')
        }
    }

    /** `Name.m3u8` for the playlist [name], with the characters a folder cannot hold replaced; [fallback] for a blank name. */
    fun fileName(name: String, fallback: String): String {
        val safe = name.map { if (it in NOT_IN_FILE_NAMES || it.isISOControl()) '_' else it }.joinToString("").trim()
        return "${safe.ifEmpty { fallback }}.$EXTENSION"
    }

    /** Whole seconds, rounded; -1 when the length is not known. */
    private fun seconds(durationMs: Long): Long = if (durationMs <= 0) -1 else (durationMs + 500) / 1000

    /** "Artist - Title", or the title alone; on one line. */
    private fun info(song: Song): String {
        val artist = song.artist?.let(::oneLine)?.takeIf(String::isNotEmpty)
        val title = oneLine(song.title)
        return if (artist == null) title else "$artist - $title"
    }

    private fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ").trim()

    // Reading

    /** The bytes of [input], or null when it holds more than [MAX_BYTES]. */
    fun readBounded(input: InputStream): ByteArray? {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) return bytes.toByteArray()
            bytes.write(chunk, 0, read)
            if (bytes.size() > MAX_BYTES) return null
        }
    }

    /**
     * The entries of the m3u file [fileName] holding [bytes]. A BOM decides the encoding; otherwise `.m3u8` is UTF-8, and
     * `.m3u` is UTF-8 when it reads as UTF-8, else Latin-1 (read as Windows-1252, which Windows writes for it).
     */
    fun read(bytes: ByteArray, fileName: String?): List<M3uEntry> = parse(decode(bytes, utf8 = fileName?.endsWith(".$EXTENSION", ignoreCase = true) == true))

    /** The playlist's name from its file's: without `.m3u` or `.m3u8`; null when that leaves nothing. */
    fun playlistName(fileName: String?): String? {
        val name = fileName?.trim() ?: return null
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return (if (extension == "m3u" || extension == EXTENSION) name.substringBeforeLast('.') else name).trim().takeIf(String::isNotEmpty)
    }

    private fun decode(bytes: ByteArray, utf8: Boolean): String {
        val bom = BOMS.firstOrNull { (mark, _) -> bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it] } }
        if (bom != null) return String(bytes, bom.first.size, bytes.size - bom.first.size, bom.second)
        if (utf8) return String(bytes, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, WINDOWS_LATIN_1)
        }
    }

    /** Every line not starting with `#` is an entry; an `#EXTINF` names the next one. Blank lines and other comments are skipped. */
    private fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pending: Pair<String?, Long?>? = null
        text.lines().map(String::trim).forEach { line ->
            when {
                line.startsWith(EXTINF, ignoreCase = true) -> pending = extinf(line.substring(EXTINF.length))
                line.isEmpty() || line.startsWith('#') -> Unit
                else -> {
                    entries += M3uEntry(line, pending?.first, pending?.second)
                    pending = null
                }
            }
        }
        return entries
    }

    /** `#EXTINF:<seconds>[ attributes],<info>`: the info and the length. A comma inside a quoted attribute is not the one. */
    private fun extinf(rest: String): Pair<String?, Long?> {
        var quoted = false
        val comma = rest.indices.firstOrNull { at ->
            if (rest[at] == '"') quoted = !quoted
            rest[at] == ',' && !quoted
        }
        val length = (if (comma == null) rest else rest.substring(0, comma)).trim().substringBefore(' ').toDoubleOrNull()
        val info = comma?.let { rest.substring(it + 1).trim() }?.takeIf(String::isNotEmpty)
        return info to length?.takeIf { it > 0 }?.let { (it * 1000).roundToLong() }
    }

    // Finding the songs

    /**
     * Where [location] says the song is on this phone: a `file://` URL decoded, an absolute path tidied, a relative one
     * resolved against [folder] (the playlist's, when its provider says), else kept as written, as a Windows path is;
     * null for any other URL (a stream), which only its `#EXTINF` can find.
     */
    fun localPath(location: String, folder: String?): String? {
        val line = location.trim()
        val scheme = URL_SCHEME.find(line)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        return when {
            scheme == "file" -> try {
                URI(line).path
            } catch (_: URISyntaxException) {
                // Spaces and the like left as they are: the rest is the path.
                line.substring(FILE_URL.length).takeIf { it.startsWith('/') }
            }?.let(::tidy)
            scheme != null -> null
            line.startsWith('/') -> tidy(line)
            line.startsWith("\\\\") || WINDOWS_DRIVE.containsMatchIn(line) -> line
            folder != null -> tidy("$folder/${line.replace('\\', '/')}")
            else -> line
        }
    }

    /**
     * The song [entry] names, by [SongResolver.M3U]'s ways in their order: its path, its file name in its folder, then
     * `#EXTINF`'s title, artist and length within 2 s. "A - B - C" may be the artist "A" or "A - B", or a title without
     * an artist, so each reading is tried; readings that name different songs name none.
     */
    fun find(entry: M3uEntry, resolver: SongResolver, folder: String?): Song? {
        val path = localPath(entry.location, folder)
        resolver.find(SongClue(path = path), SongResolver.M3U - FindBy.TAGS)?.let { return it }
        val length = entry.durationMs ?: return null
        return readings(entry.info).mapNotNull { (artist, title) ->
            resolver.find(SongClue(title = title, artist = artist, durationMs = length), setOf(FindBy.TAGS))
        }.distinct().singleOrNull()
    }

    /** What an import of [entries] (a file read for the playlist [name]) finds in the library. */
    fun match(name: String, entries: List<M3uEntry>, resolver: SongResolver, folder: String?): M3uImport {
        val songs = entries.map { it to find(it, resolver, folder) }
        return M3uImport(name, found = songs.mapNotNull { it.second }, missing = songs.filter { it.second == null }.map { it.first })
    }

    /** "A - B - C" as (A, "B - C"), ("A - B", C) and (no artist, "A - B - C"). */
    private fun readings(info: String?): List<Pair<String?, String>> {
        val text = info ?: return emptyList()
        val dashes = generateSequence(text.indexOf(DASH)) { text.indexOf(DASH, it + 1).takeIf { at -> at >= 0 } }.takeWhile { it >= 0 }
        return dashes.map { text.substring(0, it).trim() to text.substring(it + DASH.length).trim() }.toList() + (null to text)
    }

    /** `.` and `..` resolved, empty names dropped; a path above the root stays at the root. */
    private fun tidy(path: String): String {
        val names = ArrayDeque<String>()
        path.split('/').forEach { name ->
            when (name) {
                "", "." -> Unit
                ".." -> names.removeLastOrNull()
                else -> names.addLast(name)
            }
        }
        return names.joinToString("/", prefix = "/")
    }

    // Naming

    /** [name] trimmed, with " (2)", " (3)"... added while a playlist in [taken] has it, case aside. */
    fun uniqueName(name: String, taken: Collection<String>): String {
        val used = taken.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val base = name.trim()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { it.lowercase(Locale.ROOT) !in used }
    }

    // Where the system's dialogs put the files

    /**
     * The absolute path of the document [documentId] of [authority]: the phone's own storage provider names its documents
     * by volume and path ("primary:Music/Mix.m3u8", "1A2B-3C4D:Music", "home:Mix.m3u8"), the downloads provider some by
     * path ("raw:/storage/..."); [primaryRoot] is where "primary" is. Null for any other document (a cloud provider's, the
     * downloads provider's "msf:" ids): there is no path to know.
     */
    fun documentPath(authority: String?, documentId: String, primaryRoot: String): String? = when (authority) {
        STORAGE_AUTHORITY -> {
            val volume = documentId.substringBefore(':', "")
            val path = documentId.substringAfter(':', "").trim('/')
            val root = when (volume) {
                "" -> null
                "primary" -> primaryRoot
                "home" -> "$primaryRoot/Documents"
                else -> "/storage/$volume"
            }
            root?.let { if (path.isEmpty()) it else "$it/$path" }
        }
        DOWNLOADS_AUTHORITY -> documentId.removePrefix(RAW_PREFIX).takeIf { documentId.startsWith(RAW_PREFIX) && it.startsWith('/') }
        else -> null
    }

    /**
     * Whether an export may write the document at [path] ([SharedWrite.M3U_EXPORT]). The save dialog is the user's own
     * choice, but a debug build still writes only into its test folder, so a document with no path (another provider's)
     * counts as outside it there. A release build writes anywhere.
     */
    fun exportAllowed(writes: SharedWrites, path: String?): Boolean = writes.allows(SharedWrite.M3U_EXPORT, path ?: "")

    private const val HEADER: String = "#EXTM3U"
    private const val EXTINF: String = "#EXTINF:"
    private const val DASH: String = " - "
    private const val NOT_IN_FILE_NAMES: String = "/\\:*?\"<>|"
    private const val STORAGE_AUTHORITY: String = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY: String = "com.android.providers.downloads.documents"
    private const val RAW_PREFIX: String = "raw:"
    private const val FILE_URL: String = "file://"
    private val LINE_BREAKS = Regex("[\r\n]+")

    /** "file://", "https://"; not "C:\". */
    private val URL_SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]+)://""")
    private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:[\\/]""")
    private val WINDOWS_LATIN_1: Charset = Charset.forName("windows-1252")
    private val BOMS: List<Pair<ByteArray, Charset>> = listOf(
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to Charsets.UTF_8,
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE,
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE,
    )
}

/** A picked m3u file: its entries, or why it could not be read. */
sealed interface M3uRead {
    /** [name] is the file's, [folder] the path of its folder when its provider gives one (for relative paths). */
    data class Entries(val name: String?, val folder: String?, val entries: List<M3uEntry>) : M3uRead

    data object TooBig : M3uRead

    data object Unreadable : M3uRead
}

/**
 * m3u files through the Storage Access Framework, in the documents and folders the user picks in the system's dialogs;
 * no permission needed. Each write asks [writes] first ([M3u.exportAllowed]) and never replaces a file the user did not
 * pick: a folder gets new documents, which the provider names apart. The log gets counts and exception names only.
 */
class M3uDocuments(private val resolver: ContentResolver, private val writes: SharedWrites, private val log: (String) -> Unit) {
    /** The type the save dialog and new documents get: the phone's own for `.m3u8`, so no provider adds another extension. */
    val mimeType: String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(M3u.EXTENSION) ?: M3u.MIME_TYPE

    /** Writes [text] into [document], made or picked in the save dialog; false when refused or it failed. */
    fun write(document: Uri, text: String): Boolean {
        val id = try {
            DocumentsContract.getDocumentId(document)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (!M3u.exportAllowed(writes, id?.let { M3u.documentPath(document.authority, it, primaryRoot()) })) return false
        return writeText(document, text)
    }

    /** Writes each of [files] (a file name and its text) as a new document in the folder [tree]; how many were written. */
    fun writeAll(tree: Uri, files: List<Pair<String, String>>): Int {
        val (folder, path) = try {
            val id = DocumentsContract.getTreeDocumentId(tree)
            DocumentsContract.buildDocumentUriUsingTree(tree, id) to M3u.documentPath(tree.authority, id, primaryRoot())
        } catch (_: IllegalArgumentException) {
            log("m3u: export all, not a folder")
            return 0
        }
        return files.count { (name, text) ->
            if (!M3u.exportAllowed(writes, path?.let { "$it/$name" })) return@count false
            val document = try {
                DocumentsContract.createDocument(resolver, folder, mimeType, name)
            } catch (e: Exception) {
                // FileNotFoundException, SecurityException, or the provider's own; providers differ.
                log("m3u: document not made: ${e.javaClass.simpleName}")
                null
            }
            document != null && writeText(document, text)
        }
    }

    /** Reads the m3u file [document], picked in the open dialog. */
    fun read(document: Uri): M3uRead {
        return try {
            val name = resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            val folder = try {
                M3u.documentPath(document.authority, DocumentsContract.getDocumentId(document), primaryRoot())?.substringBeforeLast('/')
            } catch (_: IllegalArgumentException) {
                null
            }
            val input = resolver.openInputStream(document) ?: throw IOException("no stream")
            val bytes = input.use(M3u::readBounded) ?: return M3uRead.TooBig
            M3uRead.Entries(name, folder, M3u.read(bytes, name))
        } catch (e: Exception) {
            // IOException, SecurityException, or the provider's own.
            log("m3u: file not read: ${e.javaClass.simpleName}")
            M3uRead.Unreadable
        }
    }

    private fun writeText(document: Uri, text: String): Boolean = try {
        // "wt": a file picked to be replaced loses its old end.
        val output = resolver.openOutputStream(document, "wt") ?: throw IOException("no stream")
        output.use { it.write(text.encodeToByteArray()) }
        true
    } catch (e: Exception) {
        log("m3u: not written: ${e.javaClass.simpleName}")
        false
    }

    private fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}
