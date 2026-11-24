package app.lyrify.dl

import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlPlaylist
import app.lyrify.dl.api.DlSearchResult
import app.lyrify.dl.api.DlSearchResults
import app.lyrify.dl.api.VideoChoice
import app.lyrify.dl.api.isWebLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A link's info as VidGrab read it (its MediaInfo): yt-dlp's full info (`-J`, 7 to 14 s on the phone) and YouTube's quick
 * oEmbed info (about 0.3 s), named the same way, so the sheet can show the quick one at once and its buttons work on it.
 * And what a YouTube search found ([results]) and a playlist's songs ([playlist]), as YouTube lists them.
 */
object Info {
    private val json = Json { ignoreUnknownKeys = true }

    /** Junk in a title's brackets: "(Official Video)", "[Lyrics]", "(HD)". */
    private val JUNK = Regex("""(?i)\s*[\(\[][^\)\]]*\b(official|lyrics?|video|audio|visuali[sz]er|hd|hq|4k|mv|ncs|copyright|music)\b[^\)\]]*[\)\]]""")

    /** "Artist - Title", with a hyphen, an en dash or an em dash (written as escapes: KJB's copy rule). */
    private val ARTIST_TITLE = Regex("^(.+?)\\s+[-\u2013\u2014]\\s+(.+)$")

    /**
     * What a name is compared without: spaces, and a trailing " - Topic", VEVO or Official (as DlArgs' split does). A space
     * is any of Unicode's, a no-break space too, as yt-dlp's Python reads `\s`; `\p{Z}` says so on the JVM as on the phone,
     * whose regex (ICU) already reads `\s` that way and refuses the JVM's own Unicode flag (?U).
     */
    private val NAME_NOISE = Regex("""(?i)([\s\p{Z}]*-[\s\p{Z}]*topic|[\s\p{Z}]*vevo|[\s\p{Z}]*official)$|[\s\p{Z}]+""")

    /** An artist's channel words, dropped from the artist (as DlArgs' tagging does). */
    private val CHANNEL_WORDS = Regex("""(?i)\s*-\s*Topic$|VEVO$""")
    private val TAIL = Regex("""\s*\|.*$""")

    /** The heights a video offers, besides its top one. */
    private val HEIGHTS = setOf(2160, 1440, 1080, 720, 480)

    /** The quick info's qualities: no sizes and no top height yet. */
    private val QUICK_CHOICES = listOf(VideoChoice(null, ""), VideoChoice(1080, "1080p"), VideoChoice(720, "720p"), VideoChoice(480, "480p"))

    private const val BYTES_PER_KBIT = 125

    /** A search result's thumbnail width, in pixels: sharp in the save sheet's 72 dp cover, without the 720 px one's bytes. */
    private const val SMALL_THUMBNAIL = 320

    /** yt-dlp's live_status of a stream that is live now, or still to come. */
    private val LIVE = setOf("is_live", "is_upcoming")
    private const val QUICK_TIMEOUT_MS = 3_000

    /**
     * Artist and title from the site's tags; else from "Artist - Title" when Artist is the [uploader] or the [channel]
     * ([sameName]); else the whole title, by the uploader. Junk dropped, and " - Topic" or VEVO after the artist.
     */
    private fun names(rawTitle: String, artistTag: String?, trackTag: String?, uploader: String?, channel: String?): Pair<String, String> {
        val split = ARTIST_TITLE.find(rawTitle)?.takeIf { found -> listOfNotNull(uploader, channel).any { sameName(found.groupValues[1], it) } }
        val artist = artistTag?.takeIf { it.isNotBlank() } ?: split?.groupValues?.get(1) ?: uploader.orEmpty()
        val title = (trackTag?.takeIf { it.isNotBlank() } ?: split?.groupValues?.get(2) ?: rawTitle).replace(TAIL, "").replace(JUNK, "").trim()
        return artist.replace(CHANNEL_WORDS, "").trim() to title
    }

    /** Whether a title's [artist] is the [channel]'s own name: without case, spaces, or a trailing " - Topic", VEVO or Official. */
    private fun sameName(artist: String, channel: String): Boolean {
        val key = artist.replace(NAME_NOISE, "")
        return key.isNotEmpty() && key.equals(channel.replace(NAME_NOISE, ""), ignoreCase = true)
    }

    /**
     * yt-dlp's full info ([text], its -J JSON) for [url]: a video's, or a playlist's, its songs listed ([playlist]); null
     * when it is not that. A playlist is its name and owner, its cover (else its first song's), its songs' length together
     * and the quick video choices: none of its videos was opened, so there are no sizes.
     */
    fun full(url: String, text: String): DlInfo? {
        val info = parse(text) ?: return null
        playlist(info)?.let { list ->
            val owner = (info.text("channel") ?: info.text("uploader")).orEmpty()
            return DlInfo(url, list.title, owner, list.songs.sumOf { it.durationSec }, thumbnail(info) ?: list.songs.firstOrNull()?.thumbnail, partial = false, choices = QUICK_CHOICES, playlist = list)
        }
        val rawTitle = info.text("title") ?: return null
        val (artist, title) = names(rawTitle, info.text("artist"), info.text("track"), info.text("uploader"), info.text("channel"))
        val duration = info.number("duration") ?: 0.0
        var audio = 0.0
        val best = HashMap<Int, Double>()
        (info["formats"] as? JsonArray)?.forEach { element ->
            val format = element as? JsonObject ?: return@forEach
            val size = format.number("filesize") ?: format.number("filesize_approx") ?: ((format.number("tbr") ?: 0.0) * BYTES_PER_KBIT * duration)
            val video = format.text("vcodec") ?: "none"
            val height = format.number("height")?.toInt() ?: 0
            if (video == "none" && (format.text("acodec") ?: "none") != "none") {
                audio = max(audio, size)
            } else if (video != "none" && height > 0 && format.text("protocol") != "mhtml") {
                best[height] = max(best[height] ?: 0.0, size)
            }
        }
        val heights = best.keys.sortedDescending()
        fun label(height: Int) = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            else -> "${height}p"
        }
        fun bytes(height: Int) = (best.getValue(height) + audio).toLong().takeIf { it > 0 }
        val choices = heights.firstOrNull()?.let { top -> listOf(VideoChoice(null, label(top), bytes(top))) }.orEmpty() +
            heights.filter { it in HEIGHTS || it == heights.first() }.distinctBy(::label).map { VideoChoice(it, label(it), bytes(it)) }
        return DlInfo(url, title, artist, duration.toInt(), info.text("thumbnail"), partial = false, choices = choices)
    }

    /**
     * What a YouTube search found ([text]: yt-dlp's -J of a flat "ytsearchN:", a playlist whose entries are the videos as
     * the search lists them, none opened), in its order ([songs]). Null when [text] is not that.
     */
    fun results(text: String): DlSearchResults? = (parse(text)?.get("entries") as? JsonArray)?.let { DlSearchResults(songs(it)) }

    /**
     * A playlist's songs ([text]: yt-dlp's -J of a playlist, a list, an album or a Mix, its entries listed and none of
     * them opened, `--flat-playlist`); null when it is not a playlist (a video's own info).
     */
    fun playlist(text: String): DlPlaylist? = parse(text)?.let(::playlist)

    /**
     * A playlist's [title], its [songs] and how many more it has than were listed (its count, when YouTube gives one: a
     * Mix's has none); [DlPlaylist.withSong] is the lookup's to say. Null when [answer] is not a playlist.
     */
    private fun playlist(answer: JsonObject): DlPlaylist? {
        val entries = answer["entries"] as? JsonArray ?: return null
        if (answer.text("_type") != "playlist") return null
        val count = answer.number("playlist_count")?.toInt() ?: entries.size
        return DlPlaylist(answer.text("title").orEmpty(), songs(entries), more = (count - entries.size).coerceAtLeast(0), mix = isMix(answer.text("id")))
    }

    /**
     * The videos of a flat list (a search's or a playlist's entries), in its order. A video needs a web link and a title;
     * one live now or still to come is left out, as it never ends as a file.
     */
    private fun songs(entries: JsonArray): List<DlSearchResult> = entries.mapNotNull { element ->
        val video = element as? JsonObject ?: return@mapNotNull null
        val url = video.text("url")?.takeIf(::isWebLink) ?: return@mapNotNull null
        val title = video.text("title")?.trim() ?: return@mapNotNull null
        if (video.text("live_status") in LIVE) return@mapNotNull null
        val channel = (video.text("channel") ?: video.text("uploader")).orEmpty().trim()
        DlSearchResult(url, title, channel, video.number("duration")?.roundToInt() ?: 0, thumbnail(video))
    }

    /** The playlist a link names: its `list` (YouTube's lists, albums and Mixes); null when it names none. */
    fun playlistId(url: String): String? {
        val query = try {
            URI(url).rawQuery
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        return query.split('&').firstNotNullOfOrNull { field -> field.removePrefix("list=").takeIf { it != field && it.isNotEmpty() } }
    }

    /** Whether [playlistId] is a Mix's, which has no end: YouTube starts those with RD (a song's RD<id>, YouTube Music's RDAMVM<id>). */
    fun isMix(playlistId: String?): Boolean = playlistId?.startsWith("RD") == true

    /** A video's or a playlist's thumbnail ([SMALL_THUMBNAIL]): the smallest at least that wide, else the widest; null without one. */
    private fun thumbnail(video: JsonObject): String? {
        val sized = (video["thumbnails"] as? JsonArray).orEmpty().mapNotNull { element ->
            val thumbnail = element as? JsonObject ?: return@mapNotNull null
            thumbnail.text("url")?.takeIf(::isWebLink)?.let { it to (thumbnail.number("width")?.toInt() ?: 0) }
        }
        return (sized.filter { it.second >= SMALL_THUMBNAIL }.minByOrNull { it.second } ?: sized.maxByOrNull { it.second })?.first
    }

    /** YouTube's oEmbed answer ([text]: title, author_name, thumbnail_url) as the quick, partial info; null without a title. */
    fun oembed(url: String, text: String): DlInfo? {
        val answer = parse(text) ?: return null
        val rawTitle = answer.text("title") ?: return null
        val (artist, title) = names(rawTitle, null, null, answer.text("author_name"), null)
        // A playlist's own link (/playlist?list=...) is a playlist from the start, its songs still to come: the sheet never
        // offers to download its link as one song meanwhile.
        return DlInfo(
            url,
            title,
            artist,
            0,
            answer.text("thumbnail_url"),
            partial = true,
            choices = QUICK_CHOICES,
            playlist = DlPlaylist(rawTitle).takeIf { url.substringBefore('?').endsWith("/playlist") && playlistId(url) != null },
        )
    }

    /** YouTube's oEmbed address for [url]; null for any other site, which has no quick info. */
    fun oembedUrl(url: String): String? {
        val host = try {
            URI(url).host?.lowercase()
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        val youtube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        return if (youtube) "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, Charsets.UTF_8.name()) else null
    }

    /** The quick info for [url] from YouTube's oEmbed, within 3 s; null when it fails or the link is not YouTube's. */
    suspend fun quick(url: String, userAgent: String): DlInfo? {
        val address = oembedUrl(url) ?: return null
        return withContext(Dispatchers.IO) {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = QUICK_TIMEOUT_MS
                connection.readTimeout = QUICK_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                oembed(url, connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } catch (_: IOException) {
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** A number at [key]; null for a string, null or nothing. */
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}
