package app.lyrify.dl.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/** The Downloads add-on's bound service and its permission, as KJB binds it (IDlAddon). */
object DlNames {
    /** The bound service's class in the add-on. */
    const val SERVICE: String = "app.lyrify.dl.DlService"

    /** The add-on's request to be left out of the phone's battery optimisation, which KJB starts for its result. */
    const val EXEMPTION: String = "app.lyrify.dl.DlExemptionActivity"

    /** The signature permission only the add-on declares and only KJB, signed with the same key, holds. */
    fun permission(addonPackage: String): String = "$addonPackage.permission.BIND_DL"
}

/**
 * The add-on now (IDlAddon.status): yt-dlp's version, and when it last looked for an update (wall-clock millis).
 * [foregroundRefused]: Android refused the add-on its own foreground service the last time it asked (its few hours a
 * day of background work used up), so its work runs only while KJB keeps it bound; an older add-on never says so.
 */
@Serializable
data class DlStatus(val ytdlpVersion: String? = null, val updatedAt: Long? = null, val foregroundRefused: Boolean = false)

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
 * Whether [text] is one http or https link with a host and nothing else: no spaces or control characters, it cannot be
 * read as one of yt-dlp's options, and its host is not this phone or a private address written out ([PublicAddresses];
 * a name that resolves to one is caught where the link is fetched).
 */
fun isWebLink(text: String): Boolean {
    if (text.length > MAX_LINK || text.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return false
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    return (scheme == "http" || scheme == "https") && !host.isNullOrEmpty() && uri.rawUserInfo == null && !PublicAddresses.namesLocal(host)
}

private const val MAX_LINK = 4_096

/** The playlist a link names: its `list` (YouTube's lists, albums and Mixes); null when it names none. */
fun playlistId(url: String): String? {
    val query = try {
        URI(url).rawQuery
    } catch (_: URISyntaxException) {
        null
    } ?: return null
    return query.split('&').firstNotNullOfOrNull { field -> field.removePrefix("list=").takeIf { it != field && it.isNotEmpty() } }
}

/**
 * Whether a link names a song: a `v=` with an id in its query (watch?v=...&list=...), or a video's id in its path
 * (youtu.be/<id>, /shorts/<id>, /live/<id>, /embed/<id>). A link with a list and no song (/playlist, watch?list=,
 * embed/videoseries) is the playlist's own.
 */
fun namesSong(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return false
    }
    val path = uri.path.orEmpty().split('/')
    // The words before an id are read without case; the id keeps its own.
    val id = if (uri.host?.lowercase() == "youtu.be") {
        path.getOrNull(1)
    } else {
        path.getOrNull(2)?.takeIf { path[1].lowercase() in setOf("shorts", "live", "embed") && !it.equals("videoseries", ignoreCase = true) }
    }
    return !id.isNullOrEmpty() || uri.rawQuery.orEmpty().split('&').any { it.startsWith("v=") && it.length > 2 }
}

/**
 * Whether a link's host is out on the internet, so a shared link never makes the add-on (or KJB, for a cover) reach this
 * phone, the home or office network, or a cloud's metadata address: not loopback, unspecified, private (10/8, 172.16/12,
 * 192.168/16, carrier NAT 100.64/10, fc00::/7), link-local (169.254/16, fe80::/10), multicast or broadcast. An IPv6 form of
 * an IPv4 address counts as that address.
 */
object PublicAddresses {
    enum class Verdict { PUBLIC, NOT_PUBLIC, UNKNOWN }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and BYTE }
        return when (address) {
            is Inet4Address -> !(bytes[0] == 0 || bytes[0] >= MULTICAST_V4 || (bytes[0] == CGNAT_FIRST && bytes[1] and CGNAT_MASK == CGNAT_SECOND))
            // Unique local addresses (fc00::/7), and IPv4 written in IPv6 (::a.b.c.d, ::ffff:a.b.c.d) by its IPv4 address.
            is Inet6Address -> bytes[0] and ULA_MASK != ULA && embeddedV4(bytes)?.let(::isPublic) != false
            else -> false
        }
    }

    /**
     * [url]'s host by every address [resolve] gives it: public only when all are; [Verdict.UNKNOWN] when it does not
     * resolve (offline), so the fetch fails the ordinary way.
     */
    fun of(url: String, resolve: (String) -> List<InetAddress>): Verdict {
        val host = try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        } ?: return Verdict.NOT_PUBLIC
        val addresses = try {
            resolve(host.removePrefix("[").removeSuffix("]"))
        } catch (_: UnknownHostException) {
            return Verdict.UNKNOWN
        } catch (_: SecurityException) {
            return Verdict.UNKNOWN
        }
        return when {
            addresses.isEmpty() -> Verdict.UNKNOWN
            addresses.all(::isPublic) -> Verdict.PUBLIC
            else -> Verdict.NOT_PUBLIC
        }
    }

    /**
     * Whether [host] (a URI's host) names this phone or a non-public address in itself, with no DNS: localhost, an IPv6
     * literal, or an IPv4 address in any form inet_aton reads (127.1, 2130706433, 0x7f.0.0.1), which yt-dlp's Python does.
     */
    internal fun namesLocal(host: String): Boolean {
        val name = host.lowercase().trimEnd('.')
        if (name == "localhost" || name.endsWith(".localhost")) return true
        if (name.startsWith("[")) {
            val address = try {
                InetAddress.getByName(name.removePrefix("[").removeSuffix("]"))
            } catch (_: UnknownHostException) {
                return true
            }
            return !isPublic(address)
        }
        val v4 = inetAton(name) ?: return false
        return !isPublic(InetAddress.getByAddress(v4))
    }

    /** [text] as inet_aton reads an IPv4 address (1 to 4 parts, each decimal, 0x hex or 0 octal); null when it is a name. */
    private fun inetAton(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size !in 1..4 || parts.any { !NUMBER.matches(it) }) return null
        val values = parts.map { part ->
            when {
                part.startsWith("0x") -> part.drop(2).ifEmpty { "0" }.toLongOrNull(HEX)
                part.length > 1 && part.startsWith("0") -> part.toLongOrNull(OCTAL)
                else -> part.toLongOrNull()
            } ?: return null
        }
        // The last part fills the bytes the others left.
        val last = values.last()
        val lastBytes = 4 - (values.size - 1)
        if (values.dropLast(1).any { it > BYTE } || last >= 1L shl (8 * lastBytes)) return null
        return ByteArray(4) { index ->
            if (index < values.size - 1) values[index].toByte() else (last shr (8 * (3 - index))).toByte()
        }
    }

    /** The IPv4 address an IPv6 address holds (::a.b.c.d, ::ffff:a.b.c.d), if it is one of those. */
    private fun embeddedV4(bytes: List<Int>): InetAddress? {
        val head = bytes.take(10)
        val mark = bytes.subList(10, 12)
        if (head.any { it != 0 } || (mark != listOf(0, 0) && mark != listOf(BYTE, BYTE))) return null
        if (mark == listOf(0, 0) && bytes.subList(12, 16).all { it == 0 }) return null
        return InetAddress.getByAddress(bytes.subList(12, 16).map { it.toByte() }.toByteArray())
    }

    private val NUMBER = Regex("0x[0-9a-f]*|[0-9]+")
    private const val BYTE = 0xFF
    private const val HEX = 16
    private const val OCTAL = 8
    private const val MULTICAST_V4 = 224
    private const val CGNAT_FIRST = 100
    private const val CGNAT_SECOND = 64
    private const val CGNAT_MASK = 0xC0
    private const val ULA = 0xFC
    private const val ULA_MASK = 0xFE
}

/** One of a video's qualities: [height] (null: the best there is), its [label] ("1080p", "4K") and its estimated size. */
@Serializable
data class VideoChoice(val height: Int?, val label: String, val bytes: Long? = null)

/**
 * What a link is (IDlAddon.info): [title] and [artist] as KJB shows them, the length, the thumbnail's address, and the
 * video qualities with sizes. [partial]: the quick info (oEmbed), with no length or sizes yet. [playlist]: the playlist the
 * link names (VERSION 2; an older add-on never says).
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
    val playlist: DlPlaylist? = null,
)

/**
 * A playlist a link names (a list, an album or a Mix), its songs listed and none of them opened: its [title], its [songs]
 * in its order (as many as the add-on lists), [more] songs it has past those, and [mix] for a Mix, which has no end.
 * [withSong]: the link names one of its songs too (a song's link with &list=, a Mix started from a song), and the info
 * around it is that song's, so KJB asks which is meant; else the info is the playlist's own (its name and owner, a cover,
 * its songs' length together). A playlist link's quick info has its title only, its songs still to come.
 */
@Serializable
data class DlPlaylist(
    val title: String,
    val songs: List<DlSearchResult> = emptyList(),
    val more: Int = 0,
    val mix: Boolean = false,
    val withSong: Boolean = false,
)

/**
 * A YouTube search (IDlAddon.search, VERSION 2): the words as the user typed them ([query]) and how many results
 * ([limit]). The add-on asks yt-dlp for the list only ("ytsearchN:"), never a download.
 */
@Serializable
data class DlSearch(val query: String, val limit: Int = DEFAULT_LIMIT) {
    /** Words to look for, on one line and not too long, and a sane limit: anything else is refused. */
    val valid: Boolean get() = query.isNotBlank() && query.length <= MAX_QUERY && query.none { it.isISOControl() } && limit in 1..MAX_LIMIT

    companion object {
        const val DEFAULT_LIMIT: Int = 8
        const val MAX_LIMIT: Int = 20
        const val MAX_QUERY: Int = 200

        /**
         * A search for [words] as typed or pasted, one the add-on takes ([valid]) when they are not blank: each control
         * character (a line break, a tab, one of U+0080 to U+009F) becomes a space, and they are cut to [MAX_QUERY].
         */
        fun of(words: String): DlSearch = DlSearch(words.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(MAX_QUERY))
    }
}

/**
 * One video a search found, or a song of a playlist: its link, its [title] and [channel] as YouTube shows them (the title
 * not cleaned up, so two uploads of a song tell apart), its length, and its thumbnail's address when YouTube gave one.
 */
@Serializable
data class DlSearchResult(val url: String, val title: String, val channel: String = "", val durationSec: Int = 0, val thumbnail: String? = null)

/** What a search found (IDlSearchCallback.onResults), in YouTube's order; none found is an answer too. */
@Serializable
data class DlSearchResults(val results: List<DlSearchResult>)

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

    /**
     * A playlist no one but its owner can list: a private one (Liked music is), one that is gone, or a kind YouTube shows
     * no one. An older KJB reads it as [UNKNOWN].
     */
    PRIVATE_PLAYLIST,

    /** The site changed: an update of yt-dlp may fix it. */
    SITE_CHANGED,

    /**
     * The site refused this network or client: a VPN's address, or too many requests ("sign in to confirm you're not a
     * bot", HTTP 429). No update fixes it; another network or a later try may. An older KJB reads it as [UNKNOWN].
     */
    BLOCKED,

    /**
     * A video YouTube shows only to adults who sign in ("sign in to confirm your age"): the add-on never signs in, so no
     * update, network or later try saves it. An older KJB reads it as [UNKNOWN].
     */
    AGE_RESTRICTED,

    /** No room on the phone: in the add-on's cache, or where KJB saves. */
    NO_ROOM,

    /** The file did not reach KJB whole. */
    HAND_OFF,

    /** yt-dlp could not start in the add-on. */
    ENGINE,

    /** The request was not one the add-on takes (KJB's bug), or the key is busy with another run. */
    REFUSED,

    /** The link's host is this phone, the home or office network, or another address not on the internet. */
    LOCAL_ADDRESS,

    UNKNOWN,
}

/** How a run (or a lookup) ended. */
@Serializable
sealed interface DlEnd {
    /** The file (and its cover) went into KJB's descriptors: [bytes] of it. */
    @Serializable
    @SerialName("delivered")
    data class Delivered(val bytes: Long, val coverBytes: Long? = null) : DlEnd

    /**
     * [updated]: a newer yt-dlp was put in place during the run and the run tried with it, so another update will not
     * help now. False when yt-dlp was already the newest, or no update was made (offline, or another run used it).
     */
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

    fun encode(search: DlSearch): String = json.encodeToString(DlSearch.serializer(), search)

    /** Null when it cannot be read, or is not [DlSearch.valid]. */
    fun search(text: String): DlSearch? = read(text, DlSearch.serializer())?.takeIf { it.valid }

    fun encode(results: DlSearchResults): String = json.encodeToString(DlSearchResults.serializer(), results)

    fun results(text: String): DlSearchResults? = read(text, DlSearchResults.serializer())

    fun encode(update: DlUpdate): String = json.encodeToString(DlUpdate.serializer(), update)

    fun update(text: String): DlUpdate = read(text, DlUpdate.serializer()) ?: DlUpdate(DlUpdate.Outcome.FAILED)

    /** SerializationException, malformed JSON included, is an IllegalArgumentException. */
    private fun <T> read(text: String, serializer: KSerializer<T>): T? = try {
        json.decodeFromString(serializer, text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
