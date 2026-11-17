package app.lyrify.backup

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.SongResolver
import java.io.ByteArrayOutputStream
import java.io.IOException
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
    /** `#EXTINF`'s length; null without one, for -1 and 0 (a stream, or not known), or one no Long holds in milliseconds. */
    val durationMs: Long? = null,
)

/**
 * Playlists as extended M3U files (spec "Playlists: import and export (m3u)"): written as UTF-8 `.m3u8` with LF line
 * ends and no BOM. Import from app reads them ([ImportFormats]) with [decode] and [parse]: `.m3u` or `.m3u8`, with any
 * BOM, any line ends, and `.m3u` in UTF-8, UTF-16 or Latin-1; [matchPath] says where a line points. KJB never writes
 * MediaStore playlists (deprecated since API 31).
 */
object M3u {
    /** Files larger than this are not playlists: 5 MB holds [MAX_ENTRIES] entries at about 500 bytes each. */
    const val MAX_BYTES: Int = 5 * 1024 * 1024

    /** Files with more entries than this are not read: a playlist that long is a mistake. */
    const val MAX_ENTRIES: Int = 10_000

    /** Nor are files with a line longer than this (Linux's longest path). */
    const val MAX_LINE_CHARS: Int = 4096

    const val EXTENSION: String = "m3u8"

    /** What Android calls m3u files, for the save dialog when the phone knows no type for [EXTENSION]. */
    const val MIME_TYPE: String = "audio/x-mpegurl"

    /**
     * What the Playlists tab's Import offers: the types phones and computers give `.m3u` and `.m3u8` files, and the plain
     * text and unknown types some providers give them instead; Import from app reads what is picked by its content.
     */
    val MIME_TYPES: Array<String> =
        arrayOf(MIME_TYPE, "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream")

    // Writing

    /**
     * The playlist of [songs] as extended M3U: each song found on the phone ([SongResolver.IN_APP]) with its `#EXTINF`
     * and its path now, a song no longer there by its stored path alone. Every line is kept to one line ([oneLine]).
     */
    fun write(songs: List<SongRef>, resolver: SongResolver): String = buildString {
        append(HEADER).append('\n')
        songs.forEach { ref ->
            val song = resolver.find(ref)
            if (song != null) append(EXTINF).append(seconds(song.durationMs)).append(',').append(info(song)).append('\n')
            append(oneLine(song?.path ?: ref.path)).append('\n')
        }
    }

    /** `Name.m3u8` for the playlist [name], with the characters a folder cannot hold replaced; [fallback] for a blank name. */
    fun fileName(name: String, fallback: String): String = "${baseName(name, fallback)}.$EXTENSION"

    /** Export all: [fileName] for each of [names], with " (2)", " (3)"... so no two in the folder share one, case aside. */
    fun fileNames(names: List<String>, fallback: String): List<String> {
        val taken = mutableListOf<String>()
        return names.map { name -> uniqueName(baseName(name, fallback), taken).also { taken += it } + ".$EXTENSION" }
    }

    /**
     * Whether an export goes through a folder the user picks (checked before any file exists, then a new document in it)
     * rather than the save dialog, which makes the document itself before KJB can check it: a debug build, which writes
     * only into its test folder ([exportAllowed]).
     */
    fun exportsByFolder(writes: SharedWrites): Boolean = writes.testFolderOnly

    private fun baseName(name: String, fallback: String): String =
        name.map { if (it in NOT_IN_FILE_NAMES || it.isISOControl()) '_' else it }.joinToString("").trim().ifEmpty { fallback }

    /** Whole seconds, rounded; -1 when the length is not known. */
    private fun seconds(durationMs: Long): Long = if (durationMs <= 0) -1 else (durationMs + 500) / 1000

    /** "Artist - Title", or the title alone; on one line. */
    private fun info(song: Song): String {
        val artist = song.artist?.let(::oneLine)?.takeIf(String::isNotEmpty)
        val title = oneLine(song.title)
        return if (artist == null) title else "$artist - $title"
    }

    /** [text] with each run of line and paragraph breaks and other control characters made one space, so it stays one line. */
    private fun oneLine(text: String): String = text.replace(NOT_ON_ONE_LINE, " ").trim()

    // Reading

    /** [bytes] as text: a BOM decides; else UTF-8 when [utf8], else UTF-16 by its NUL bytes, UTF-8 when it reads as UTF-8, else Windows-1252. */
    internal fun decode(bytes: ByteArray, utf8: Boolean): String {
        val bom = BOMS.firstOrNull { (mark, _) -> bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it] } }
        if (bom != null) return String(bytes, bom.first.size, bytes.size - bom.first.size, bom.second)
        if (utf8) return String(bytes, Charsets.UTF_8)
        // UTF-16 without a BOM: an ASCII character's other byte is NUL, after it (little-endian) or before it (big-endian).
        val nulsAtEven = bytes.indices.count { it % 2 == 0 && bytes[it] == NUL }
        val nulsAtOdd = bytes.indices.count { it % 2 == 1 && bytes[it] == NUL }
        if (nulsAtEven + nulsAtOdd > 0) return String(bytes, if (nulsAtEven > nulsAtOdd) Charsets.UTF_16BE else Charsets.UTF_16LE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, WINDOWS_LATIN_1)
        }
    }

    /**
     * Every line not starting with `#` is an entry; an `#EXTINF` names the next one. Blank lines and other comments are
     * skipped. Lines are taken one at a time, and reading stops at the first sign the file is too big.
     */
    internal fun parse(text: String): List<M3uEntry>? {
        val entries = mutableListOf<M3uEntry>()
        var pending: Pair<String?, Long?>? = null
        for (raw in text.lineSequence()) {
            if (raw.length > MAX_LINE_CHARS) return null
            val line = raw.trim()
            when {
                line.startsWith(EXTINF, ignoreCase = true) -> pending = extinf(line.substring(EXTINF.length))
                line.isEmpty() || line.startsWith('#') -> Unit
                entries.size == MAX_ENTRIES -> return null
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
        val seconds = (if (comma == null) rest else rest.substring(0, comma)).trim().substringBefore(' ').toDoubleOrNull()
        val info = comma?.let { rest.substring(it + 1).trim() }?.takeIf(String::isNotEmpty)
        // Infinity, NaN and lengths whose milliseconds no Long holds are no length.
        val millis = seconds?.times(1000)?.takeIf { it > 0 && it < Long.MAX_VALUE.toDouble() }
        return info to millis?.roundToLong()
    }

    // Where a line points

    /**
     * Where an import's [location] (an m3u or pls line, an xspf location) points, for [SongMatcher.find]: an absolute
     * path tidied (a `file:` URL's, with one slash or two, percent-decoded; a relative one resolved against [folder]),
     * or, when the root it is under cannot be known here, its folders and name with `/` between them ("Users/Kit/Music/
     * 01 Song.mp3": a Windows path without its drive, a server's share, a relative path without [folder]); null for any
     * other URL (a stream, a `content://` id), which only its tags can find.
     */
    fun matchPath(location: String, folder: String?): String? {
        val line = location.trim()
        val scheme = URL_SCHEME.find(line)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        return when {
            line.isEmpty() -> null
            scheme == "file" -> filePath(percentDecoded(line.substring(FILE_URL.length)))
            scheme != null -> null
            // Java's File.toURI writes "file:/storage/...".
            line.startsWith(FILE_SCHEME, ignoreCase = true) -> filePath(percentDecoded(line.substring(FILE_SCHEME.length)))
            WINDOWS_DRIVE.containsMatchIn(line) -> belowUnknownRoot(line.substring(2))
            line.startsWith('\\') -> belowUnknownRoot(line)
            line.startsWith('/') -> tidy(line)
            folder != null -> tidy("$folder/${line.replace('\\', '/')}")
            else -> belowUnknownRoot(line)
        }
    }

    /** A file URL's path: absolute, or a Windows drive's ("/C:/Music/...") or a server's ("nas/share/...") below an unknown root. */
    private fun filePath(path: String): String? = when {
        WINDOWS_URL_DRIVE.containsMatchIn(path) -> belowUnknownRoot(path.substring(3))
        path.startsWith('/') -> tidy(path)
        else -> belowUnknownRoot(path)
    }

    /** [path]'s folders and name, backslashes as `/`, tidied as [tidy] does, without the leading `/`; null when nothing is left. */
    private fun belowUnknownRoot(path: String): String? = tidy(path.replace('\\', '/')).removePrefix("/").takeIf(String::isNotEmpty)

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

    /** `%XX` escapes as UTF-8 bytes; a `%` that starts no escape stays as it is. */
    internal fun percentDecoded(text: String): String {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream()
        var at = 0
        while (at < text.length) {
            val hex = if (text[at] == '%' && at + 2 < text.length) text.substring(at + 1, at + 3) else null
            val escaped = hex?.takeIf { it.all { digit -> digit.digitToIntOrNull(16) != null } }?.toInt(16)
            if (escaped != null) {
                bytes.write(escaped)
                at += 3
            } else {
                val end = text.indexOf('%', at + 1).takeIf { it >= 0 } ?: text.length
                bytes.write(text.substring(at, end).encodeToByteArray())
                at = end
            }
        }
        return bytes.toByteArray().decodeToString()
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
     * Whether an export may write the document at [path] ([SharedWrite.M3U_EXPORT]). The dialogs are the user's own
     * choice, but a debug build still writes only into its test folder, so a document with no path (another provider's)
     * counts as outside it there. A release build writes anywhere.
     */
    fun exportAllowed(writes: SharedWrites, path: String?): Boolean = writes.allows(SharedWrite.M3U_EXPORT, path ?: "")

    private const val HEADER: String = "#EXTM3U"
    private const val EXTINF: String = "#EXTINF:"
    private const val NOT_IN_FILE_NAMES: String = "/\\:*?\"<>|"
    private const val STORAGE_AUTHORITY: String = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY: String = "com.android.providers.downloads.documents"
    private const val RAW_PREFIX: String = "raw:"
    private const val FILE_URL: String = "file://"
    private const val FILE_SCHEME: String = "file:"
    private const val NUL: Byte = 0

    /** Controls (CR, LF, U+0085 among them) and the line and paragraph separators. */
    private val NOT_ON_ONE_LINE = Regex("[\\p{Cc}\u2028\u2029]+")

    /** "file://", "https://"; not "C:\". */
    private val URL_SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]+)://""")
    private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:[\\/]""")

    /** A Windows drive in a file URL's path: "/C:/". */
    private val WINDOWS_URL_DRIVE = Regex("""^/[A-Za-z]:/""")
    private val WINDOWS_LATIN_1: Charset = Charset.forName("windows-1252")
    private val BOMS: List<Pair<ByteArray, Charset>> = listOf(
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to Charsets.UTF_8,
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to Charsets.UTF_16LE,
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to Charsets.UTF_16BE,
    )
}

/**
 * Exported m3u files through the Storage Access Framework, into the documents and folders the user picks in the
 * system's dialogs; no permission needed. Each write asks [writes] first ([M3u.exportAllowed]) and never replaces a
 * file the user did not pick: a folder gets new documents, which the provider names apart. The log gets counts and
 * exception names only.
 */
class M3uDocuments(private val resolver: ContentResolver, private val writes: SharedWrites, private val log: (String) -> Unit) {
    /** The type the save dialog and new documents get: the phone's own for `.m3u8`, so no provider adds another extension. */
    val mimeType: String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(M3u.EXTENSION) ?: M3u.MIME_TYPE

    /**
     * Writes [text] into [document], made or picked in the save dialog (a release build's way; a debug build exports by
     * folder, [M3u.exportsByFolder]); false when refused or it failed.
     */
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
