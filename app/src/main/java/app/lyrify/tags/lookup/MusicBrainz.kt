package app.lyrify.tags.lookup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder

/** An artist credit as MusicBrainz gives it: who is credited, under which name, with the join phrases between them. */
data class Credit(val parts: List<Part>) {
    data class Part(val name: String, val joinPhrase: String = "", val artistId: String? = null)

    /** ONE credited name, join phrases included ("Mira Solen feat. Glass Harbour"): what the ARTIST field gets. */
    val text: String get() = parts.joinToString("") { it.name + it.joinPhrase }.trim()

    /** The credited artists one by one, for the ARTISTS side field only. */
    val names: List<String> get() = parts.map { it.name }.filter { it.isNotBlank() }

    /** Credited to MusicBrainz's "Various Artists" (a compilation). */
    val isVarious: Boolean get() = parts.any { it.artistId == Names.VARIOUS_ARTISTS_ID } || Names.isVariousName(text)
}

/** The abstract album every edition belongs to; [firstReleaseDate] is the earliest release in the group. */
data class MbReleaseGroup(
    val id: String,
    val firstReleaseDate: String? = null,
    val primaryType: String? = null,
    val secondaryTypes: List<String> = emptyList(),
) {
    /** The album's ORIGINAL year: the group's first release, never a remaster's. */
    val originalYear: Int? get() = yearOf(firstReleaseDate)

    val isCompilation: Boolean get() = secondaryTypes.any { it.equals("Compilation", ignoreCase = true) }
}

/** A track on a release: [position] on its medium (from 1), the recording it is, and its length here. */
data class MbTrack(
    val id: String,
    val recordingId: String,
    val position: Int,
    val title: String,
    val lengthMs: Long?,
    val artist: Credit,
)

/** One disc of a release: [position] is its disc number, [trackCount] its tracks even when [tracks] lists only some. */
data class MbMedium(val position: Int, val format: String?, val trackCount: Int, val tracks: List<MbTrack>)

/**
 * One edition of an album. A release lookup lists every track; a search lists none, and a recording search lists only
 * the track that was found ([hasAllTracks] tells them apart).
 */
data class MbRelease(
    val id: String,
    val title: String,
    val artist: Credit,
    val releaseGroup: MbReleaseGroup?,
    val date: String? = null,
    val country: String? = null,
    val trackCount: Int,
    val media: List<MbMedium> = emptyList(),
    /** The Cover Art Archive has a front cover for it; null when the answer did not say (search results). */
    val hasFront: Boolean? = null,
    /** MusicBrainz's search score, 0 to 100; null outside a search. */
    val score: Int? = null,
) {
    /** The year of this edition (a remaster's own year), for the candidate card. */
    val year: Int? get() = yearOf(date)

    /** "CD", "Digital Media": the formats of its discs, each once. */
    val formats: List<String> get() = media.mapNotNull { it.format }.distinct()

    /** Every listed track in order, with its disc and its number over the whole release. */
    val tracks: List<ReleaseTrack>
        get() {
            var index = 0
            return media.flatMap { medium -> medium.tracks.map { ReleaseTrack(medium.position, ++index, it, medium.trackCount) } }
        }

    val hasAllTracks: Boolean get() = trackCount > 0 && media.sumOf { it.tracks.size } == trackCount
}

/** A track where it sits: on disc [disc] of a release, [index] over the whole release (from 1), [discTracks] on its disc. */
data class ReleaseTrack(val disc: Int, val index: Int, val track: MbTrack, val discTracks: Int)

/** A recording found by a recording search, with the releases it is on (each listing only this track). */
data class MbRecording(val id: String, val title: String, val lengthMs: Long?, val artist: Credit, val releases: List<MbRelease>)

/** A page of a release search: the [releases] it lists, and how many it found in all ([total], its `count`). */
data class ReleaseHits(val releases: List<MbRelease>, val total: Int)

/** The year a MusicBrainz date ("1998", "1998-04", "1998-04-17") starts with. */
internal fun yearOf(date: String?): Int? = date?.let { YEAR.find(it) }?.value?.toInt()?.takeIf { it > 0 }

private val YEAR = Regex("""^\d{4}(?=-|$)""")

/**
 * The MusicBrainz web service (`/ws/2`, JSON) and Cover Art Archive addresses. Ids from files are checked with [isId]
 * before they go into a path; free text goes into a Lucene query, escaped.
 */
internal object MusicBrainzUrls {
    const val API: String = "https://musicbrainz.org/ws/2"
    const val COVERS: String = "https://coverartarchive.org"

    /** At most this many releases or recordings per search. */
    const val SEARCH_LIMIT: Int = 10

    /** A recording search asks for lengths this close to the song's. */
    const val LENGTH_WINDOW_MS: Long = 10_000

    private val ID = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE)

    /** Lucene's special characters (https://lucene.apache.org/core/9_0_0/queryparser/ syntax), each escaped alone. */
    private const val SPECIAL = "+-&|!(){}[]^\"~*?:\\/"
    private val OPERATOR = Regex("""\b(?:AND|OR|NOT)\b""")

    fun isId(text: String?): Boolean = text != null && ID.matches(text)

    /** A release search by album title, artist (left out when null) and track count (left out when null). */
    fun releaseSearch(album: String, artist: String?, tracks: Int?): URI =
        search("release", listOfNotNull("release:(${lucene(album)})", artist?.let { "artist:(${lucene(it)})" }, tracks?.let { "tracks:$it" }))

    /** A release with its discs, tracks, recordings, lengths, artist credits and release group. */
    fun release(id: String): URI = URI("$API/release/${checked(id)}?inc=artists+recordings+artist-credits+release-groups&fmt=json")

    fun releaseGroup(id: String): URI = URI("$API/release-group/${checked(id)}?fmt=json")

    /** A recording search by title, artist and length (within [LENGTH_WINDOW_MS]), for singles. */
    fun recordingSearch(title: String, artist: String?, durationMs: Long?): URI = search(
        "recording",
        listOfNotNull(
            "recording:(${lucene(title)})",
            artist?.let { "artist:(${lucene(it)})" },
            durationMs?.let { "dur:[${(it - LENGTH_WINDOW_MS).coerceAtLeast(0)} TO ${it + LENGTH_WINDOW_MS}]" },
        ),
    )

    fun front(releaseId: String, size: CoverSize): URI = URI("$COVERS/release/${checked(releaseId)}/${size.path}")

    /** [text] with Lucene's special characters escaped and its operators lowercased, so a name is searched as words. */
    fun lucene(text: String): String =
        buildString { text.forEach { if (it in SPECIAL) append('\\'); append(it) } }.replace(OPERATOR) { it.value.lowercase() }

    private fun search(entity: String, terms: List<String>): URI =
        URI("$API/$entity/?query=" + URLEncoder.encode(terms.joinToString(" AND "), "UTF-8") + "&fmt=json&limit=$SEARCH_LIMIT")

    private fun checked(id: String): String {
        require(isId(id)) { "not a MusicBrainz id" }
        return id.lowercase()
    }
}

/**
 * Reads MusicBrainz's JSON answers (shapes recorded on 2026-11-10, see the test fixtures); anything unreadable throws
 * [IllegalArgumentException]. A release or recording without a proper id is dropped; a track without a credit takes its
 * recording's, then its release's.
 */
internal object MusicBrainzJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun releases(body: ByteArray): List<MbRelease> = decode<ReleaseSearchJson>(body).releases.mapNotNull { it.toRelease() }

    fun releaseHits(body: ByteArray): ReleaseHits {
        val search = decode<ReleaseSearchJson>(body)
        val releases = search.releases.mapNotNull { it.toRelease() }
        return ReleaseHits(releases, search.count ?: releases.size)
    }

    fun release(body: ByteArray): MbRelease = requireNotNull(decode<ReleaseJson>(body).toRelease()) { "not a MusicBrainz id" }

    fun releaseGroup(body: ByteArray): MbReleaseGroup = requireNotNull(decode<GroupJson>(body).toGroup()) { "not a MusicBrainz id" }

    fun recordings(body: ByteArray): List<MbRecording> = decode<RecordingSearchJson>(body).recordings.mapNotNull { recording ->
        if (!MusicBrainzUrls.isId(recording.id)) return@mapNotNull null
        val credit = credit(recording.artistCredit)
        MbRecording(
            recording.id.lowercase(),
            recording.title,
            recording.length,
            credit,
            recording.releases.mapNotNull { it.toRelease(fallback = credit, recording = recording) },
        )
    }

    private inline fun <reified T> decode(body: ByteArray): T = json.decodeFromString<T>(body.decodeToString())

    private fun credit(parts: List<CreditJson>): Credit =
        Credit(parts.map { Credit.Part(it.name.ifEmpty { it.artist?.name.orEmpty() }, it.joinphrase, it.artist?.id?.takeIf(String::isNotEmpty)) })

    private fun GroupJson.toGroup(): MbReleaseGroup? =
        if (MusicBrainzUrls.isId(id)) MbReleaseGroup(id.lowercase(), firstReleaseDate?.takeIf(String::isNotBlank), primaryType, secondaryTypes) else null

    /** [fallback]: the credit a release without its own has (a recording search lists only credits that differ). */
    private fun ReleaseJson.toRelease(fallback: Credit? = null, recording: RecordingJson? = null): MbRelease? {
        if (!MusicBrainzUrls.isId(id)) return null
        val credit = credit(artistCredit).takeIf { it.parts.isNotEmpty() } ?: fallback ?: Credit(emptyList())
        val discs = media.mapIndexed { i, medium -> medium.toMedium(i + 1, credit, recording) }
        return MbRelease(
            id = id.lowercase(),
            title = title,
            artist = credit,
            releaseGroup = releaseGroup?.toGroup(),
            date = date?.takeIf(String::isNotBlank),
            country = country,
            trackCount = trackCount ?: discs.sumOf { it.trackCount },
            media = discs,
            hasFront = coverArtArchive?.front,
            score = score,
        )
    }

    private fun MediumJson.toMedium(defaultPosition: Int, releaseCredit: Credit, recording: RecordingJson?): MbMedium {
        val listed = tracks.ifEmpty { track }
        val offset = trackOffset ?: 0
        val parsed = listed.mapIndexedNotNull { i, item -> item.toTrack(offset + i + 1, releaseCredit, recording) }
        return MbMedium(position ?: defaultPosition, format, trackCount ?: listed.size, parsed)
    }

    /** In a recording search, a track has no recording of its own: it is [searched]. */
    private fun TrackJson.toTrack(defaultPosition: Int, releaseCredit: Credit, searched: RecordingJson?): MbTrack? {
        val recording = this.recording ?: searched ?: return null
        if (!MusicBrainzUrls.isId(recording.id)) return null
        val artist = credit(artistCredit).takeIf { it.parts.isNotEmpty() }
            ?: credit(recording.artistCredit).takeIf { it.parts.isNotEmpty() }
            ?: releaseCredit
        return MbTrack(id, recording.id.lowercase(), position ?: defaultPosition, title.ifEmpty { recording.title }, length ?: recording.length, artist)
    }
}

@Serializable
private class CreditJson(val name: String = "", val joinphrase: String = "", val artist: ArtistJson? = null)

@Serializable
private class ArtistJson(val id: String = "", val name: String = "")

@Serializable
private class GroupJson(
    val id: String = "",
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
)

@Serializable
private class RecordingJson(
    val id: String = "",
    val title: String = "",
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<CreditJson> = emptyList(),
    val releases: List<ReleaseJson> = emptyList(),
)

@Serializable
private class TrackJson(
    val id: String = "",
    val position: Int? = null,
    val title: String = "",
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<CreditJson> = emptyList(),
    val recording: RecordingJson? = null,
)

/** A release lookup lists a disc's tracks as `tracks`, a recording search as `track`. */
@Serializable
private class MediumJson(
    val position: Int? = null,
    val format: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
    @SerialName("track-offset") val trackOffset: Int? = null,
    val tracks: List<TrackJson> = emptyList(),
    val track: List<TrackJson> = emptyList(),
)

@Serializable
private class CoverArtJson(val front: Boolean = false)

@Serializable
private class ReleaseJson(
    val id: String,
    val score: Int? = null,
    val title: String = "",
    @SerialName("artist-credit") val artistCredit: List<CreditJson> = emptyList(),
    @SerialName("release-group") val releaseGroup: GroupJson? = null,
    val date: String? = null,
    val country: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
    val media: List<MediumJson> = emptyList(),
    @SerialName("cover-art-archive") val coverArtArchive: CoverArtJson? = null,
)

@Serializable
private class ReleaseSearchJson(val count: Int? = null, val releases: List<ReleaseJson> = emptyList())

@Serializable
private class RecordingSearchJson(val recordings: List<RecordingJson> = emptyList())

/** The queries Covers & tags sends, all through the one [client] and its gate. [fresh]: "Check again", skip the cache. */
class MusicBrainz(private val client: MetaClient) {
    /** Empty results are never cached. */
    suspend fun searchReleases(album: String, artist: String?, tracks: Int?, fresh: Boolean = false): MetaAnswer<List<MbRelease>> =
        client.get(MusicBrainzUrls.releaseSearch(album, artist, tracks), Body.JSON, fresh, keep = { it.isNotEmpty() }, read = MusicBrainzJson::releases)

    /** [searchReleases] with the number of releases found in all, beyond the [MusicBrainzUrls.SEARCH_LIMIT] listed. */
    suspend fun releaseHits(album: String, artist: String?, tracks: Int?, fresh: Boolean = false): MetaAnswer<ReleaseHits> =
        client.get(MusicBrainzUrls.releaseSearch(album, artist, tracks), Body.JSON, fresh, keep = { it.releases.isNotEmpty() }, read = MusicBrainzJson::releaseHits)

    suspend fun release(id: String, fresh: Boolean = false): MetaAnswer<MbRelease> =
        ifId(id) { client.get(MusicBrainzUrls.release(id), Body.JSON, fresh, read = MusicBrainzJson::release) }

    suspend fun releaseGroup(id: String, fresh: Boolean = false): MetaAnswer<MbReleaseGroup> =
        ifId(id) { client.get(MusicBrainzUrls.releaseGroup(id), Body.JSON, fresh, read = MusicBrainzJson::releaseGroup) }

    suspend fun searchRecordings(title: String, artist: String?, durationMs: Long?, fresh: Boolean = false): MetaAnswer<List<MbRecording>> =
        client.get(MusicBrainzUrls.recordingSearch(title, artist, durationMs), Body.JSON, fresh, keep = { it.isNotEmpty() }, read = MusicBrainzJson::recordings)

    /** The front cover of a release at [size], as the Cover Art Archive has it (JPEG or PNG). */
    suspend fun front(releaseId: String, size: CoverSize, fresh: Boolean = false): MetaAnswer<ByteArray> =
        ifId(releaseId) {
            client.get(MusicBrainzUrls.front(releaseId, size), Body.IMAGE, fresh) { bytes ->
                require(Images.isImage(bytes)) { "not an image" }
                bytes
            }
        }

    private inline fun <T> ifId(id: String, get: () -> MetaAnswer<T>): MetaAnswer<T> =
        if (MusicBrainzUrls.isId(id)) get() else MetaAnswer.Failed("not a MusicBrainz id")
}
