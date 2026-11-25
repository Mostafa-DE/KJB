package app.lyrify.dl

import android.content.Context
import android.os.Process
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlMeta
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStage
import app.lyrify.dl.api.DlUpdate
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_common.SharedPrefsHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** How a yt-dlp run ended: its standard output, its error text, or killed ([Ytdlp.kill]). */
sealed interface YtdlpExit {
    data class Done(val out: String) : YtdlpExit

    data class Failed(val err: String) : YtdlpExit

    data object Killed : YtdlpExit
}

/** yt-dlp as the add-on uses it: youtubedl-android on the phone ([LibraryYtdlp]), a fake in tests. */
interface Ytdlp {
    /** Unpacks Python the first time (about 2 s); false when yt-dlp cannot run on this phone. */
    suspend fun ready(): Boolean

    /**
     * Runs yt-dlp as process [id] with [args] and then [url], until it exits; each line it writes goes to [line]. A run is
     * a process: cancelling the caller does not end it, only [kill] does (VidGrab's Cancel bug).
     */
    suspend fun run(id: String, args: List<String>, url: String, line: (String) -> Unit): YtdlpExit

    /** Kills run [id] and every process of the add-on whose command line holds [marker]: its ffmpeg too. */
    fun kill(id: String, marker: String)

    /** Whether a process of the add-on still has [marker] in its command line: a killed run's yt-dlp or ffmpeg not gone yet. */
    fun running(marker: String): Boolean

    /** Looks for a newer yt-dlp on its GitHub releases and puts it in place. */
    suspend fun update(): DlUpdate.Outcome

    /** The yt-dlp in place (its release tag), null before its first update. */
    fun version(): String?
}

/**
 * VidGrab's yt-dlp arguments (its Ytdl.kt and MetaArgs.kt): the arg set it verified on the desktop with yt-dlp and ffprobe
 * (VidGrab's mp3-args-tested.txt, YtdlpTest), and what a run in the add-on adds to it ([forRun]).
 */
object DlArgs {
    /** An audio file's name: "Artist - Title", from the tags yt-dlp made, or the title alone without an artist. */
    const val AUDIO_NAME: String = "%(meta_artist|).80B%(meta_artist& - |)s%(meta_title).120B.%(ext)s"

    const val VIDEO_NAME: String = "%(title).150B.%(ext)s"

    /** Where a run's audio tags go (yt-dlp prints them there once the file is in place), for KJB's tag engine. */
    const val META_FILE: String = "meta.json"

    /** The tags yt-dlp made, as JSON: what KJB writes into the file with its own tag engine. */
    private const val META = "%(.{meta_title,meta_artist,meta_album,meta_album_artist,meta_date,meta_genre,duration})j"

    /** Seconds before yt-dlp gives up on a stalled connection (and retries it, then fails). */
    private const val SOCKET_TIMEOUT = "30"

    /** "Artist - Title" with a hyphen, an en dash or an em dash: VidGrab's pattern, the two dashes written as escapes. */
    private const val DASHES = "-\u2013\u2014"

    /**
     * "Artist - Title" split only when Artist is the uploader or the channel (phone session 10: VidGrab split every
     * "A - B", so a trailer named "A - B" got artist A), compared as [Info] compares them: the title's Artist and both
     * names copied into fields of KJB's own, without spaces or a trailing " - Topic", VEVO or Official, then matched case
     * blind by a backreference. Checked with yt-dlp itself (2025.11.12) on made-up info, offline (YtdlpTest).
     */
    private val OWN_SPLIT = "(?i)^(?P<kjb_key>\\S+) (?P=kjb_key) (?P<meta_artist>.+?)\\s+[$DASHES]\\s+(?P<meta_title>.+)$"

    /**
     * VidGrab's tags for audio: title and artist from the site's tags, or "Artist - Title" by that artist's own channel
     * ([OWN_SPLIT]), else the title by the uploader, and no artist without one (as [Info]'s sheet has it; VidGrab wrote
     * "Unknown"); junk words, "| ..." tails and "- Topic" or VEVO dropped, "ft." as "(feat. X)", album, album artist, year
     * and genre; description, comment and the link blanked; the cover cropped square as a JPG, when the site's extractor
     * gives a thumbnail (Wikimedia Commons' gives none, so its downloads have no cover and KJB's default cover shows:
     * phone session 10); ID3v2.3.
     */
    private val TAGGING: List<String> = listOf(
        "--embed-metadata",
        "--embed-thumbnail",
        "--convert-thumbnails",
        "jpg",
        "--ppa",
        """ThumbnailsConvertor+ffmpeg_o:-c:v mjpeg -q:v 2 -vf crop="'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'"""",
        "--ppa",
        "EmbedThumbnail+ffmpeg_o:-id3v2_version 3",
        "--ppa",
        "Metadata+ffmpeg_o:-id3v2_version 3",
        "--parse-metadata",
        "title:%(meta_title)s",
        "--parse-metadata",
        "%(uploader|)s:(?P<meta_artist>.*)",
        "--parse-metadata",
        """title:(?P<kjb_a>.+?)\s+[""" + DASHES + """]\s+.+""",
        "--parse-metadata",
        "%(kjb_a|)s:(?P<kjb_key_a>.*)",
        "--parse-metadata",
        "%(uploader|)s:(?P<kjb_key_u>.*)",
        "--parse-metadata",
        "%(channel|)s:(?P<kjb_key_c>.*)",
        "--replace-in-metadata",
        "kjb_key_a,kjb_key_u,kjb_key_c",
        """(?i)(\s*-\s*topic|\s*vevo|\s*official)$|\s+""",
        "",
        "--parse-metadata",
        "%(kjb_key_a)s %(kjb_key_u)s %(title)s:$OWN_SPLIT",
        "--parse-metadata",
        "%(kjb_key_a)s %(kjb_key_c)s %(title)s:$OWN_SPLIT",
        "--parse-metadata",
        "%(track,meta_title)s:%(meta_title)s",
        "--parse-metadata",
        "%(artist,meta_artist)s:%(meta_artist)s",
        "--replace-in-metadata",
        "meta_title",
        """\s*\|.*$""",
        "",
        "--replace-in-metadata",
        "meta_title",
        """(?i)\s*[\(\[][^\)\]]*\b(official|lyrics?|video|audio|visuali[sz]er|hd|hq|4k|mv|m/v|ncs|copyright|music)\b[^\)\]]*[\)\]]""",
        "",
        "--replace-in-metadata",
        "meta_title",
        """(?i)\s+\b(HD|HQ|4K|1080p)$""",
        "",
        "--replace-in-metadata",
        "meta_title,meta_artist",
        """(?i)\s*[\(\[]?\b(?:ft|feat|featuring)\.?\s+([^\)\]]+?)[\)\]]?\s*$""",
        """ (feat. \1)""",
        "--replace-in-metadata",
        "meta_artist",
        """(?i)\s*-\s*Topic$|VEVO$""",
        "",
        "--parse-metadata",
        "%(album,meta_title)s:%(meta_album)s",
        "--parse-metadata",
        "%(album_artist,meta_artist)s:%(meta_album_artist)s",
        "--parse-metadata",
        "%(release_year,upload_date>%Y)s:%(meta_date)s",
        "--parse-metadata",
        ":(?P<meta_description>)",
        "--parse-metadata",
        ":(?P<meta_synopsis>)",
        "--parse-metadata",
        ":(?P<meta_comment>)",
        "--parse-metadata",
        ":(?P<meta_purl>)",
        "--parse-metadata",
        "%(genre|)s:%(meta_genre)s",
    )

    /** The original language first (no dubbed track), then the bitrate. */
    private fun sort(originalLanguage: Boolean) = if (originalLanguage) "lang,abr,asr" else "abr,asr"

    /** Audio as VidGrab downloads it: the best audio, converted to MP3 at [quality] or kept as it is, tagged by yt-dlp. */
    fun audio(quality: AudioQuality, originalLanguage: Boolean): List<String> =
        listOf("-f", "bestaudio/best", "-S", sort(originalLanguage), "--no-playlist", "-x") + format(quality) + TAGGING + listOf("-o", AUDIO_NAME)

    private fun format(quality: AudioQuality): List<String> = when (quality) {
        AudioQuality.MP3_320 -> listOf("--audio-format", "mp3", "--audio-quality", "320K", "--ppa", "ExtractAudio+ffmpeg_o:-b:a 320k -ar 44100")
        AudioQuality.MP3_192 -> listOf("--audio-format", "mp3", "--audio-quality", "192K", "--ppa", "ExtractAudio+ffmpeg_o:-b:a 192k -ar 44100")
        AudioQuality.MP3_V0 -> listOf("--audio-format", "mp3", "--audio-quality", "0", "--ppa", "ExtractAudio+ffmpeg_o:-ar 44100")
        AudioQuality.ORIGINAL -> listOf("--audio-format", "best")
    }

    /** Video as VidGrab downloads it: the best (up to [height]) with the original audio track, merged as MP4. */
    fun video(height: Int?, originalLanguage: Boolean): List<String> {
        val audio = if (originalLanguage) "ba[format_note*=original]/ba" else "ba"
        val format = if (height == null) "bv*+($audio)/b" else "bv*[height<=$height]+($audio)/b[height<=$height]"
        return listOf(
            "-f", format,
            "-S", "res,fps,vcodec:vp9,acodec:m4a" + if (originalLanguage) ",lang" else "",
            "--no-playlist", "--merge-output-format", "mp4", "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
            "-o", VIDEO_NAME,
        )
    }

    /**
     * A run's arguments: [request]'s, writing into [dir] (the key's own folder, also the marker [Ytdlp.kill] finds its
     * processes by). An audio run keeps its cover (the cropped JPG) and prints its tags into [META_FILE] for KJB. A run is
     * one video: of any list (a playlist's link, a post of several videos) only its first item, so it makes one file with
     * its own tags, never one song under another's name (KJB saves a playlist song by song, each its own run). "--" ends
     * the options, so the link can never be read as one.
     */
    fun forRun(request: DlRequest, dir: File): List<String> {
        val base = if (request.kind == DlKind.AUDIO) audio(request.audio, request.originalLanguage) else video(request.height, request.originalLanguage)
        val named = base.mapIndexed { index, arg -> if (index > 0 && base[index - 1] == "-o") "${dir.path}/$arg" else arg }
        val kept = if (request.kind == DlKind.AUDIO) listOf("--write-thumbnail", "--print-to-file", "after_move:$META", "${dir.path}/$META_FILE") else emptyList()
        return named + listOf("--playlist-items", "1", "--no-mtime", "--newline", "--socket-timeout", SOCKET_TIMEOUT) + kept + "--"
    }

    /**
     * A lookup's arguments: the full info as JSON, a playlist's songs listed and none of them opened (--flat-playlist), at
     * most [songs] of them. A song's link that names a playlist too (&list=) is the song, or with [playlist] the
     * playlist. [marker] (in its -o, which -J never writes) names its process.
     */
    fun info(marker: String, songs: Int, playlist: Boolean = false): List<String> = listOf(
        "-J",
        if (playlist) "--yes-playlist" else "--no-playlist",
        "--flat-playlist",
        "--playlist-items",
        ":$songs",
        "--socket-timeout",
        SOCKET_TIMEOUT,
        "-o",
        "$marker%(id)s",
        "--",
    )

    /** The songs a lookup lists of a playlist: about 10 hours of music, and at most what KJB's list of downloads keeps. */
    const val PLAYLIST_SONGS: Int = 200

    /** Of a Mix, which has no end: a few hours of music. */
    const val MIX_SONGS: Int = 50

    /**
     * A search's arguments: YouTube's list as JSON, its videos never opened (--flat-playlist), so only the search itself is
     * fetched; [marker] as [info]'s. The search ("ytsearch8:words") comes after "--", as a link does.
     */
    fun search(marker: String): List<String> = listOf("-J", "--flat-playlist", "--socket-timeout", SOCKET_TIMEOUT, "-o", "$marker%(id)s", "--")
}

/**
 * What a failed yt-dlp run's error text says: its [failure], the key of the [pattern] that told it ("sign-in", "429",
 * "nsig"), and the [extractor] of the site that failed ("youtube"), when there is one. Safe to log ([note]): never the
 * line itself, which may carry the link, the video's id or its title.
 */
data class YtdlpError(val failure: DlFailure, val pattern: String? = null, val extractor: String? = null) {
    val note: String get() = listOfNotNull(failure.name, pattern?.let { "matched $it" }, extractor?.let { "extractor $it" }).joinToString(", ")
}

/** What yt-dlp's lines and files say: progress, failures in plain kinds, and the tags it printed. Pure. */
object YtdlpLines {
    /** `[download]  42.5% of   12.34MiB at    1.20MiB/s ETA 01:15` (the size may start with ~). */
    private val DOWNLOADING = Regex("""^\[download]\s+(\d+(?:\.\d+)?)% of\s+~?\s*\S+\s+at\s+(\S+/s)\s+ETA\s+(\d+):(\d+)(?::(\d+))?""")
    private val PERCENT = Regex("""^\[download]\s+(\d+(?:\.\d+)?)%""")

    private const val SECONDS = 60

    /** The progress a line of yt-dlp's says; a line that says none keeps [stage]. */
    fun progress(line: String, stage: DlStage): DlProgress {
        DOWNLOADING.find(line)?.let { found ->
            val (percent, speed, first, second, third) = found.destructured
            val eta = if (third.isEmpty()) first.toLong() * SECONDS + second.toLong() else (first.toLong() * SECONDS + second.toLong()) * SECONDS + third.toLong()
            return DlProgress(DlStage.DOWNLOADING, percent.toFloat(), eta, speed)
        }
        PERCENT.find(line)?.let { return DlProgress(DlStage.DOWNLOADING, it.groupValues[1].toFloat()) }
        val processor = if (line.startsWith("[")) line.substring(1).substringBefore(']') else ""
        return when (processor) {
            "ExtractAudio" -> DlProgress(DlStage.CONVERTING)
            "Merger", "VideoConvertor", "VideoRemuxer" -> DlProgress(DlStage.MERGING)
            "EmbedThumbnail", "Metadata", "ThumbnailsConvertor", "FixupM4a", "FixupM3u8" -> DlProgress(DlStage.TAGGING)
            else -> DlProgress(stage)
        }
    }

    /** A text a kind of failure is told by, in yt-dlp's ERROR line as lowercase, and the [key] the log names it by. */
    private class Pattern(val key: String, val text: String)

    private fun patterns(vararg pairs: Pair<String, String>): List<Pattern> = pairs.map { (key, text) -> Pattern(key, text) }

    /**
     * Each kind's patterns, in the order the kinds are read: a full phone first, then no network, then a playlist no one
     * but its owner can list (before no video: both say "does not exist"), then no video, then a video only for adults who
     * sign in (before a site that refuses: both say "sign in to confirm"), then a site that refuses this network or client
     * (a VPN's address), then a site that changed.
     */
    private val KINDS: List<Pair<DlFailure, List<Pattern>>> = listOf(
        DlFailure.NO_ROOM to patterns("no-space" to "no space left", "enospc" to "enospc", "errno-28" to "errno 28"),
        DlFailure.OFFLINE to patterns(
            "errno-7" to "errno 7]", "errno-101" to "errno 101]", "errno-110" to "errno 110]", "errno-111" to "errno 111]",
            "unreachable" to "network is unreachable", "no-address" to "no address associated", "name-resolution" to "temporary failure in name resolution",
            "timed-out" to "timed out", "resolve" to "failed to resolve", "reset" to "connection reset", "closed" to "remote end closed connection",
        ),
        DlFailure.PRIVATE_PLAYLIST to patterns(
            "playlist-missing" to "playlist does not exist", "playlist-private" to "playlist is private", "playlist-unviewable" to "playlist type is unviewable",
        ),
        DlFailure.NOT_FOUND to patterns(
            "unavailable" to "video unavailable", "private" to "private video", "unsupported-url" to "unsupported url", "unavailable" to "this video is unavailable",
            "removed" to "has been removed", "not-exist" to "does not exist", "invalid-url" to "is not a valid url", "404" to "http error 404",
            "no-formats" to "no video formats found",
        ),
        DlFailure.AGE_RESTRICTED to patterns("confirm-age" to "confirm your age"),
        DlFailure.BLOCKED to patterns("sign-in" to "sign in to confirm", "not-a-bot" to "not a bot", "429" to "http error 429", "too-many-requests" to "too many requests"),
        DlFailure.SITE_CHANGED to patterns(
            "unable-to-extract" to "unable to extract", "403" to "http error 403", "nsig" to "nsig", "signature" to "signature",
            "format-not-available" to "requested format is not available", "player-response" to "player response", "video-data" to "unable to download video data",
        ),
    )

    /** yt-dlp's own name for a site's extractor, in brackets at the start of its ERROR line: small letters, digits, ":" and ".". */
    private val EXTRACTOR = Regex("""^\[([a-z0-9][a-z0-9:.]{0,31})]""")

    private const val ERROR = "ERROR:"

    /**
     * What a failed run's error text (yt-dlp's standard error, all of it) means, in a kind KJB can say plainly; what it does
     * not know is [DlFailure.UNKNOWN]. Only yt-dlp's ERROR lines are read: its warnings (a slow or refused try it retried,
     * an nsig it could not solve) never say why it failed.
     */
    fun diagnose(err: String): YtdlpError {
        val errors = err.lineSequence().map { it.trim() }.filter { it.startsWith(ERROR) }.map { it.removePrefix(ERROR).trimStart() }.toList()
        for ((failure, patterns) in KINDS) {
            for (pattern in patterns) {
                val line = errors.firstOrNull { pattern.text in it.lowercase() } ?: continue
                return YtdlpError(failure, pattern.key, extractor(line))
            }
        }
        return YtdlpError(DlFailure.UNKNOWN, extractor = errors.firstOrNull()?.let(::extractor))
    }

    private fun extractor(line: String): String? = EXTRACTOR.find(line)?.groupValues?.get(1)

    private val json = Json { ignoreUnknownKeys = true }

    /** The tags yt-dlp printed into [file] ([DlArgs.forRun]), its last line, and the length in seconds; empty when there are none. */
    fun meta(file: File): Pair<DlMeta, Int> {
        val line = try {
            file.readLines().lastOrNull { it.isNotBlank() }
        } catch (_: IOException) {
            null
        } ?: return DlMeta() to 0
        val fields = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return DlMeta() to 0
        fun text(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val seconds = (fields["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.roundToInt() ?: 0
        return DlMeta(
            title = text("meta_title"),
            artist = text("meta_artist"),
            album = text("meta_album"),
            albumArtist = text("meta_album_artist"),
            year = text("meta_date"),
            genre = text("meta_genre"),
        ) to seconds
    }
}

/** The add-on's processes, from /proc: on Android an app sees only its own there. */
object ProcTable {
    /** The processes in [proc] (but [self]) whose command line holds [marker]. Pure file reading; a process gone meanwhile is skipped. */
    fun matching(proc: File, marker: String, self: Int): List<Int> =
        proc.listFiles()?.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull()?.takeIf { it != self } ?: return@mapNotNull null
            val command = try {
                File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            } catch (_: IOException) {
                return@mapNotNull null
            }
            pid.takeIf { marker in command }
        }.orEmpty()
}

/**
 * [Ytdlp] through youtubedl-android (VidGrab's engine): yt-dlp on its Python, ffmpeg for converting, merging and the
 * cover, QuickJS for YouTube. yt-dlp's cache (`--cache-dir`, about 0.3 s a lookup) is kept in [cache]. A kill ends the
 * library's process and then every process of the add-on in the run's folder: the library's own child kill does not
 * reach ffmpeg, which went on converting after VidGrab's Cancel.
 */
class LibraryYtdlp(private val context: Context, private val cache: File, private val io: CoroutineContext = Dispatchers.IO, private val log: (String) -> Unit) : Ytdlp {
    private val started = AtomicBoolean(false)
    private val init = CompletableDeferred<Boolean>()

    override suspend fun ready(): Boolean {
        if (started.compareAndSet(false, true)) {
            init.complete(
                withContext(io) {
                    try {
                        YoutubeDL.getInstance().init(context)
                        FFmpeg.getInstance().init(context)
                        true
                    } catch (e: YoutubeDLException) {
                        log("yt-dlp did not start (${e.javaClass.simpleName})")
                        false
                    }
                },
            )
        }
        return init.await()
    }

    override suspend fun run(id: String, args: List<String>, url: String, line: (String) -> Unit): YtdlpExit = withContext(io) {
        val request = YoutubeDLRequest(url).addOption("--cache-dir", File(cache, "yt-dlp").path).addCommands(args)
        try {
            YtdlpExit.Done(YoutubeDL.getInstance().execute(request, id) { _, _, text -> line(text) }.out)
        } catch (_: YoutubeDL.CanceledException) {
            YtdlpExit.Killed
        } catch (_: InterruptedException) {
            YtdlpExit.Killed
        } catch (e: YoutubeDLException) {
            YtdlpExit.Failed(e.message.orEmpty())
        }
    }

    override fun kill(id: String, marker: String) {
        YoutubeDL.getInstance().destroyProcessById(id)
        val left = ProcTable.matching(File("/proc"), marker, Process.myPid())
        left.forEach { Process.sendSignal(it, Process.SIGNAL_KILL) }
        log("killed a run and ${left.size} of its processes")
    }

    override fun running(marker: String): Boolean = ProcTable.matching(File("/proc"), marker, Process.myPid()).isNotEmpty()

    /**
     * yt-dlp's latest stable release, checked ([YtdlpUpdater]: yt-dlp's signature on its SHA2-256SUMS, then the file's
     * SHA-256) before it replaces the one in place; not the library's own updater, which checks nothing.
     */
    override suspend fun update(): DlUpdate.Outcome = withContext(io) {
        val github = GitHubFetch(userAgent = USER_AGENT)
        YtdlpUpdater(fetch = { uri, max -> github.get(uri, max) }, current = ::version, install = ::install).update()
            .also { if (it == DlUpdate.Outcome.FAILED) log("yt-dlp update not made; the one in place stays") }
    }

    /**
     * Puts a checked [binary] where youtubedl-android runs yt-dlp from, whole or not at all (written beside it, then moved
     * over it), and records its [tag] where the library keeps its version.
     */
    private fun install(tag: String, binary: ByteArray): Boolean {
        val dir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
        val next = File(dir, "${YoutubeDL.ytdlpBin}.new")
        return try {
            dir.mkdirs()
            FileOutputStream(next).use { out ->
                out.write(binary)
                out.fd.sync()
            }
            Files.move(next.toPath(), File(dir, YoutubeDL.ytdlpBin).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            SharedPrefsHelper.update(context, VERSION_KEY, tag)
            SharedPrefsHelper.update(context, VERSION_NAME_KEY, tag)
            true
        } catch (e: IOException) {
            log("yt-dlp not put in place (${e.javaClass.simpleName})")
            next.delete()
            false
        }
    }

    override fun version(): String? = YoutubeDL.getInstance().version(context)

    private companion object {
        /** Where youtubedl-android keeps yt-dlp's release tag and name (its YoutubeDLUpdater). */
        const val VERSION_KEY = "dlpVersion"
        const val VERSION_NAME_KEY = "dlpVersionName"
    }
}
