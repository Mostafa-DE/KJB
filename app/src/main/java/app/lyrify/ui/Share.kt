package app.lyrify.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.os.TransactionTooLargeException
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.library.Song
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.readSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// Share: songs as their files, and lyrics as text or as the `.lrc`, through your phone's share sheet. Songs go as
// MediaStore's content URIs: KJB may hand on read access to them because it can read them itself (MediaProvider's
// checkUriPermission), they work on every storage volume, and the app picked gets the file's real name, size and type.
// No song is copied. A `.lrc` is not a MediaStore audio row, so it goes as a byte copy in KJB's cache, served by a
// FileProvider limited to that one folder (res/xml/share_paths.xml).

/**
 * The most songs one share carries: each costs about 0.3 KB in the intent and its ClipData, so 500 stay well inside the
 * 1 MB Binder buffer the share passes through. A larger selection is refused with the limit in a toast, never cut short.
 */
const val SHARE_SONGS_MAX: Int = 500

/**
 * The type a `.lrc` goes as: no MIME type is registered for `.lrc`, so this is what KJB's FileProvider answers for it, and
 * what developer.android.com advises for data without a specific type. The apps that take any file (mail, chats, Quick
 * Share, Files) take it as a file named `<song>.lrc`; text/plain would offer it to apps that read only the text extra.
 */
const val LRC_TYPE: String = "application/octet-stream"

/** Songs of different types, or one MediaStore gives no type. */
private const val ANY_AUDIO: String = "audio/*"

private const val TEXT_TYPE: String = "text/plain"

/** One song as a share carries it: its MediaStore content URI, the MIME type MediaStore gives it (null for none), and its title. */
data class SharedSong(val uri: String, val mimeType: String?, val title: String)

/**
 * A share for your phone's share sheet before it is an [Intent] ([toIntent]). [streams]: content URIs of the files;
 * [subject]: an email's subject; [title]: the share sheet's preview title for text.
 */
data class ShareRequest(
    val action: String,
    val type: String,
    val streams: List<String> = emptyList(),
    val subject: String? = null,
    val title: String? = null,
    val text: String? = null,
) {
    /** Read access to [streams] for the app the user picks, and to nothing else. */
    val flags: Int get() = if (streams.isEmpty()) 0 else Intent.FLAG_GRANT_READ_URI_PERMISSION
}

/** Whether [count] songs go in one share: one to [SHARE_SONGS_MAX]. */
fun fitsOneShare(count: Int): Boolean = count in 1..SHARE_SONGS_MAX

/**
 * [songs]' files: one as ACTION_SEND, several as ACTION_SEND_MULTIPLE, with their one type, or any audio ([ANY_AUDIO])
 * for a mix or a song without a type. The subject is the song's title, or for several what [several] says ("3 songs"). Takes one to
 * [SHARE_SONGS_MAX] songs ([fitsOneShare]).
 */
fun songsShare(songs: List<SharedSong>, several: (Int) -> String): ShareRequest {
    require(fitsOneShare(songs.size)) { "${songs.size} songs in one share" }
    return ShareRequest(
        action = if (songs.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
        type = songs.map { it.mimeType }.distinct().singleOrNull() ?: ANY_AUDIO,
        streams = songs.map { it.uri },
        subject = songs.singleOrNull()?.title ?: several(songs.size),
    )
}

// Lyrics

/** What a song's lyrics can be shared as: plain ones as text, synced ones as text or as their `.lrc`. */
enum class LyricsShare { PLAIN, SYNCED }

/**
 * What the song with the `.lrc` text [lrc] (null without one) and the ledger [entry] can share: SYNCED for timed lines
 * with words, PLAIN for untimed words or LRCLIB's plain lyrics the player shows without a file ([plainRecord]); null
 * without words.
 */
fun lyricsShare(lrc: String?, entry: LedgerEntry?): LyricsShare? = when {
    lrc != null && LrcFormat.timed(lrc) -> LyricsShare.SYNCED
    lrc != null && words(lrc).isNotEmpty() -> LyricsShare.PLAIN
    plainRecord(entry) != null -> LyricsShare.PLAIN
    else -> null
}

/**
 * "As text": "Title · Artist", a blank line, then [lyrics]' words line by line, without times, header tags or the word
 * times of enhanced LRC; a line sung twice shows twice, in time order; one blank line between verses. Null without words.
 */
fun lyricsText(title: String, artist: String?, lyrics: String): String? {
    val lines = words(lyrics).takeIf { it.isNotEmpty() } ?: return null
    val head = listOfNotNull(title.trim(), artist?.trim()?.takeIf(String::isNotEmpty)).joinToString(" · ")
    return (listOf(head, "") + lines).joinToString("\n")
}

/** The words of [lyrics] as lines, "" between verses and none at the ends: synced lyrics in time order, plain ones as written. */
private fun words(lyrics: String): List<String> {
    val raw = if (LrcFormat.timed(lyrics)) {
        LrcFormat.parseLrclib(lyrics).map { it.text }
    } else {
        lyrics.removePrefix("\uFEFF").lines().filterNot { HEADER_TAG.matches(it.trim()) }.map { it.replace(LINE_TIMES, "") }
    }
    val lines = raw.map { it.replace(WORD_TIME, "").replace(SPACES, " ").trim() }
    return lines.fold(ArrayList<String>()) { kept, line ->
        if (line.isNotEmpty() || kept.lastOrNull()?.isNotEmpty() == true) kept += line
        kept
    }.dropLastWhile(String::isEmpty)
}

private val HEADER_TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")
private val LINE_TIMES = Regex("""^\s*(?:\[\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?]\s*)+""")
private val WORD_TIME = Regex("""<\d{1,4}:\d{1,2}(?:[.:]\d{1,3})?>""")
private val SPACES = Regex("""\s+""")

/** "As text" as a share: ACTION_SEND text/plain with the words, the title as the subject and the preview's title. */
fun lyricsTextShare(title: String, text: String): ShareRequest =
    ShareRequest(Intent.ACTION_SEND, TEXT_TYPE, subject = title, title = title, text = text)

/** "Lyrics file" as a share: ACTION_SEND of the `.lrc` at [uri] as a file ([LRC_TYPE]), the title as the subject. */
fun lyricsFileShare(uri: String, title: String): ShareRequest = ShareRequest(Intent.ACTION_SEND, LRC_TYPE, listOf(uri), subject = title)

// Your phone's share sheet

/**
 * The intent the share sheet gets: the files in EXTRA_STREAM and in its ClipData, which carries the read grant through
 * the chooser to the app the user picks (Intent.createChooser copies it and the grant flag onto the chooser).
 */
fun ShareRequest.toIntent(): Intent {
    val uris = streams.map(Uri::parse)
    return Intent(action).also { intent ->
        intent.type = type
        if (action == Intent.ACTION_SEND_MULTIPLE) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        else uris.singleOrNull()?.let { intent.putExtra(Intent.EXTRA_STREAM, it) }
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        title?.let { intent.putExtra(Intent.EXTRA_TITLE, it) }
        text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        uris.firstOrNull()?.let { first ->
            intent.clipData = ClipData(subject.orEmpty(), arrayOf(type), ClipData.Item(first)).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        }
        intent.addFlags(flags)
    }
}

/** Your phone's share sheet for [request]. */
fun shareSheet(request: ShareRequest): Intent = Intent.createChooser(request.toIntent(), null)

/** How a share went; all but [Opened] say so in a toast. */
sealed interface Shared {
    data object Opened : Shared

    /** More than [SHARE_SONGS_MAX] songs, or more than one share can carry. */
    data object TooMany : Shared

    /** [songs] of them are no longer in MediaStore: nothing was shared. */
    data class NotFound(val songs: Int) : Shared

    /** The lyrics could not be read (the `.lrc` changed, or LRCLIB could not be asked). */
    data object NoLyrics : Shared

    data object Failed : Shared
}

suspend fun ToastState.say(resources: Resources, shared: Shared) {
    val text = when (shared) {
        Shared.Opened -> return
        Shared.TooMany -> resources.getQuantityString(R.plurals.share_too_many, SHARE_SONGS_MAX, SHARE_SONGS_MAX)
        is Shared.NotFound -> resources.getQuantityString(R.plurals.share_not_found, shared.songs, shared.songs)
        Shared.NoLyrics -> resources.getString(R.string.share_no_lyrics)
        Shared.Failed -> resources.getString(R.string.share_failed)
    }
    show(Toast(text, R.drawable.ic_error, Tone.ERROR))
}

/**
 * Opens the share sheet with [songs]' files, each found again in MediaStore by its path now, so an id that changed
 * meanwhile never shares another file, and a song moved to the trash is not shared. [title]: each song's name.
 */
suspend fun shareSongs(context: Context, songs: List<Song>, title: (Song) -> String): Shared {
    if (!fitsOneShare(songs.size)) return if (songs.isEmpty()) Shared.Failed else Shared.TooMany
    val found = withContext(Dispatchers.IO) { audioFiles(context.contentResolver, songs.map(Song::path)) } ?: return Shared.Failed
    val missing = songs.count { it.path !in found }
    if (missing > 0) return Shared.NotFound(missing)
    val shared = songs.map { song -> found.getValue(song.path).let { SharedSong(it.uri, it.mimeType, title(song)) } }
    return startShare(context, songsShare(shared) { count -> context.resources.getQuantityString(R.plurals.lib_songs, count, count) })
}

/** "As text": the song's `.lrc` as words ([lyricsText]), else LRCLIB's plain lyrics the player shows ([plainRecord]). */
suspend fun shareLyricsText(context: Context, song: Song, title: String, entry: LedgerEntry?): Shared {
    val lrc = withContext(Dispatchers.IO) { readLrc(song.path) }
    val text = lrc?.let { lyricsText(title, song.artist, it) }
        ?: plainRecord(entry)?.let { plainText(context, it) }?.let { lyricsText(title, song.artist, it) }
        ?: return Shared.NoLyrics
    return startShare(context, lyricsTextShare(title, text))
}

/** "Lyrics file": the song's synced `.lrc` as it is on disk, through a copy in KJB's cache ([lrcCopy]). */
suspend fun shareLyricsFile(context: Context, song: Song, title: String): Shared {
    val file = withContext(Dispatchers.IO) { lrcCopy(context.cacheDir, song.path, System.currentTimeMillis()) } ?: return Shared.NoLyrics
    val uri = FileProvider.getUriForFile(context, context.packageName + SHARE_AUTHORITY, file)
    return startShare(context, lyricsFileShare(uri.toString(), title))
}

private fun startShare(context: Context, request: ShareRequest): Shared = try {
    context.startActivity(shareSheet(request))
    Shared.Opened
} catch (_: ActivityNotFoundException) {
    Shared.Failed
} catch (e: RuntimeException) {
    // More than one Binder transaction can carry: Android throws it wrapped (RemoteException.rethrowFromSystemServer).
    if (e.cause !is TransactionTooLargeException) throw e
    Shared.TooMany
}

private class AudioFile(val uri: String, val mimeType: String?)

/** MediaStore's audio rows at [paths], trashed ones left out: each path's content URI and type. Null when MediaStore gives no answer. */
private fun audioFiles(resolver: ContentResolver, paths: List<String>): Map<String, AudioFile>? = try {
    val found = HashMap<String, AudioFile>()
    for (chunk in paths.distinct().chunked(SHARE_SONGS_MAX)) {
        val selection = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "$DATA IN (${chunk.joinToString(",") { "?" }})")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, chunk.toTypedArray())
        }
        val projection = arrayOf(MediaStore.Audio.Media._ID, DATA, MediaStore.Audio.Media.MIME_TYPE)
        val cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val path = it.getString(1) ?: continue
                found[path] = AudioFile(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.getLong(0)).toString(), it.getString(2))
            }
        }
    }
    found
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * A byte copy of the synced `.lrc` beside [songPath] in [cache]'s share folder, under the `.lrc`'s own name, in a folder
 * of its own so two songs' files of one name never meet; null without a synced `.lrc`. Copies older than a day go: an
 * app may read its copy a while after the share (a mail sent later).
 */
internal fun lrcCopy(cache: File, songPath: String, now: Long): File? = try {
    val sidecar = LrcFormat.sidecarPath(songPath)
    val bytes = sidecar?.let { readSidecar(File(it).toPath()) }?.takeIf { LrcFormat.timed(it.decodeToString()) }
    if (sidecar == null || bytes == null) {
        null
    } else {
        val shares = File(cache, SHARE_FOLDER)
        shares.listFiles()?.filter { now - it.lastModified() > COPY_KEPT_MS }?.forEach(File::deleteRecursively)
        val folder = File(shares, now.toString()).apply { mkdirs() }
        File(folder, sidecar.substringAfterLast('/')).apply { writeBytes(bytes) }
    }
} catch (_: IOException) {
    null
}

/** The FileProvider's authority after the package name (AndroidManifest.xml, "share"). */
private const val SHARE_AUTHORITY: String = ".share"

/** KJB's cache folder that FileProvider serves (res/xml/share_paths.xml). */
private const val SHARE_FOLDER: String = "share"

private val COPY_KEPT_MS: Long = TimeUnit.DAYS.toMillis(1)

/** A file's absolute path (MediaStore's `_data`): All files access reads it. */
private const val DATA: String = "_data"

// The sheet

/**
 * What the song at [path] can share of its lyrics ([lyricsShare]): its `.lrc` read off the main thread, again whenever
 * its ledger [entry] changes. Null while reading and for a song without lyrics. Nothing is asked of LRCLIB here.
 */
@Composable
fun rememberLyricsShare(path: String, entry: LedgerEntry?): LyricsShare? {
    val share by produceState<LyricsShare?>(null, path, entry?.status, entry?.updatedAt) {
        value = withContext(Dispatchers.IO) { lyricsShare(readLrc(path), entry) }
    }
    return share
}

/** Share lyrics for the song [title]: "As text" always, "Lyrics file" for [synced] lyrics only, each with what goes. */
@Composable
internal fun ShareLyricsSheet(title: String, synced: Boolean, onText: () -> Unit, onFile: () -> Unit, onDismiss: () -> Unit) {
    LyrifySheet(onDismiss = onDismiss, spacing = 4.dp, padding = 12.dp) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            Text(stringResource(R.string.share_lyrics), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        NavRow(title = stringResource(R.string.share_as_text), sub = stringResource(R.string.share_as_text_line), lead = { Tile(R.drawable.ic_notes) }, trailing = null, onClick = onText)
        if (synced) {
            NavRow(title = stringResource(R.string.share_lyrics_file), sub = stringResource(R.string.share_lyrics_file_line), lead = { Tile(R.drawable.ic_description) }, trailing = null, onClick = onFile)
        }
    }
}
