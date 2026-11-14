package app.lyrify.dl.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException

/** The Downloads add-on's bound service and its permission, as KJB binds it (IDlAddon). */
object DlNames {
    /** The bound service's class in the add-on. */
    const val SERVICE: String = "app.lyrify.dl.DlService"

    /** The signature permission only the add-on declares and only KJB, signed with the same key, holds. */
    fun permission(addonPackage: String): String = "$addonPackage.permission.BIND_DL"
}

/** The add-on now (IDlAddon.status): yt-dlp's version, and when it last looked for an update (wall-clock millis). */
@Serializable
data class DlStatus(val ytdlpVersion: String? = null, val updatedAt: Long? = null)

@Serializable
enum class DlKind { AUDIO, VIDEO }

/** VidGrab's four audio choices: MP3 at 320 kbps, V0 or 192 kbps, or the site's own audio, not re-encoded (M4A or Opus). */
@Serializable
enum class AudioQuality { MP3_320, MP3_V0, MP3_192, ORIGINAL }

/**
 * One download as KJB asks for it (IDlAddon.start). [key] is KJB's id for the download, the same for every try of it: the
 * add-on keeps a key's parts in a folder of that name, so a Retry goes on from them. [height] is the video's highest
 * height (null: the best there is). [originalLanguage]: the original audio track, never a dubbed one.
 */
@Serializable
data class DlRequest(
    val key: String,
    val url: String,
    val kind: DlKind,
    val audio: AudioQuality = AudioQuality.MP3_320,
    val height: Int? = null,
    val originalLanguage: Boolean = true,
) {
    /** A key the add-on may name a folder after, a web link, and a sane height: anything else is refused. */
    val valid: Boolean get() = isDownloadKey(key) && isWebLink(url) && (height == null || height in 1..MAX_HEIGHT)
}

/** A download's key: letters, digits and dashes, at most 64, so the add-on can name a folder after it and nothing else. */
fun isDownloadKey(key: String): Boolean = DOWNLOAD_KEY.matches(key)

private val DOWNLOAD_KEY = Regex("[A-Za-z0-9-]{1,64}")
private const val MAX_HEIGHT = 10_000

/**
 * Whether [text] is one http or https link with a host and nothing else: no spaces or control characters, and it cannot
 * be read as one of yt-dlp's options.
 */
fun isWebLink(text: String): Boolean {
    if (text.length > MAX_LINK || text.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return false
    }
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
}

private const val MAX_LINK = 4_096

/** One of a video's qualities: [height] (null: the best there is), its [label] ("1080p", "4K") and its estimated size. */
@Serializable
data class VideoChoice(val height: Int?, val label: String, val bytes: Long? = null)

/**
 * What a link is (IDlAddon.info): [title] and [artist] as KJB shows them, the length, the thumbnail's address, and the
 * video qualities with sizes. [partial]: the quick info (oEmbed), with no length or sizes yet.
 */
@Serializable
data class DlInfo(
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Int = 0,
    val thumbnail: String? = null,
    val partial: Boolean = false,
    val choices: List<VideoChoice> = emptyList(),
)

/** Where a run is. */
@Serializable
enum class DlStage {
    /** Updating yt-dlp before the run, or after a failure. */
    UPDATING,
    STARTING,
    DOWNLOADING,
    CONVERTING,
    MERGING,
    TAGGING,

    /** Trying once more after an update. */
    RETRYING,
}

/** A run's progress: its [stage] and, while downloading, its [percent], time left and speed ("2.4MiB/s", yt-dlp's). */
@Serializable
data class DlProgress(val stage: DlStage, val percent: Float? = null, val etaSec: Long? = null, val speed: String? = null)

/** The tags yt-dlp made for an audio file (VidGrab's rules: "Artist - Title", "- Topic" and junk words dropped, feat.). */
@Serializable
data class DlMeta(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
)

/**
 * A run's finished file (IDlRunCallback.onFile): the [name] yt-dlp gave it (its extension says the format), its size,
 * its cover's size when there is one (audio), and its tags.
 */
@Serializable
data class DlFile(val name: String, val bytes: Long, val coverBytes: Long? = null, val meta: DlMeta = DlMeta(), val durationSec: Int = 0)

/** Why a lookup or a run failed, in words KJB can say plainly. */
@Serializable
enum class DlFailure {
    /** No network, or the site could not be reached. */
    OFFLINE,

    /** No video at that link: removed, private, or a site yt-dlp does not know. */
    NOT_FOUND,

    /** The site changed, or refused yt-dlp: an update of yt-dlp may fix it. */
    SITE_CHANGED,

    /** No room on the phone: in the add-on's cache, or where KJB saves. */
    NO_ROOM,

    /** The file did not reach KJB whole. */
    HAND_OFF,

    /** yt-dlp could not start in the add-on. */
    ENGINE,

    /** The request was not one the add-on takes (KJB's bug), or the key is busy with another run. */
    REFUSED,

    UNKNOWN,
}

/** How a run (or a lookup) ended. */
@Serializable
sealed interface DlEnd {
    /** The file (and its cover) went into KJB's descriptors: [bytes] of it. */
    @Serializable
    @SerialName("delivered")
    data class Delivered(val bytes: Long, val coverBytes: Long? = null) : DlEnd

    /** [updated]: yt-dlp was updated during the run, so an update will not help again now. */
    @Serializable
    @SerialName("failed")
    data class Failed(val failure: DlFailure, val updated: Boolean = false) : DlEnd

    @Serializable
    @SerialName("stopped")
    data object Stopped : DlEnd
}

/**
 * An update of yt-dlp (IDlAddon.update): a newer one in place, already the newest, not done (offline), or not now (a
 * download is using yt-dlp: it is never swapped under a running one).
 */
@Serializable
data class DlUpdate(val outcome: Outcome, val version: String? = null) {
    enum class Outcome { UPDATED, CURRENT, FAILED, BUSY }
}

/**
 * The JSON the strings of IDlAddon and its callbacks carry, a sealed type's kind under "type". Fields a later version
 * adds are ignored, so a newer add-on still talks to an older KJB and the other way round; what cannot be read at all
 * reads as the safe answer (null, or a failure).
 */
object DlWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(status: DlStatus): String = json.encodeToString(DlStatus.serializer(), status)

    fun status(text: String): DlStatus? = read(text, DlStatus.serializer())

    fun encode(request: DlRequest): String = json.encodeToString(DlRequest.serializer(), request)

    /** Null when it cannot be read, or is not [DlRequest.valid]. */
    fun request(text: String): DlRequest? = read(text, DlRequest.serializer())?.takeIf { it.valid }

    fun encode(info: DlInfo): String = json.encodeToString(DlInfo.serializer(), info)

    fun info(text: String): DlInfo? = read(text, DlInfo.serializer())

    fun encode(progress: DlProgress): String = json.encodeToString(DlProgress.serializer(), progress)

    fun progress(text: String): DlProgress? = read(text, DlProgress.serializer())

    fun encode(file: DlFile): String = json.encodeToString(DlFile.serializer(), file)

    fun file(text: String): DlFile? = read(text, DlFile.serializer())

    fun encode(end: DlEnd): String = json.encodeToString(DlEnd.serializer(), end)

    /** An end that cannot be read is a failure of an unknown kind. */
    fun end(text: String): DlEnd = read(text, DlEnd.serializer()) ?: DlEnd.Failed(DlFailure.UNKNOWN)

    fun encode(update: DlUpdate): String = json.encodeToString(DlUpdate.serializer(), update)

    fun update(text: String): DlUpdate = read(text, DlUpdate.serializer()) ?: DlUpdate(DlUpdate.Outcome.FAILED)

    /** SerializationException, malformed JSON included, is an IllegalArgumentException. */
    private fun <T> read(text: String, serializer: KSerializer<T>): T? = try {
        json.decodeFromString(serializer, text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
