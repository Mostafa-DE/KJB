package app.lyrify.tags.lookup

/**
 * A song on the phone as Covers & tags reads it from its file: every tag as the file has it, null when it has none.
 * Mapped from the library's songs and the file's own tags when the check runs.
 */
data class PhoneSong(
    val path: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val disc: Int?,
    val track: Int?,
    val durationMs: Long,
    /** The year in the file's own tags (MediaStore leaves it empty for some formats): the year rule keeps it. */
    val year: Int? = null,
    /** A MusicBrainz release id already in the file: a candidate, never trusted blindly. */
    val musicBrainzReleaseId: String? = null,
)

/** Songs checked together: an album of 2 or more songs with an album tag, or else a single. */
data class PhoneAlbum(val title: String?, val albumArtist: String?, val songs: List<PhoneSong>) {
    /** A song with no album tag, or alone: it always goes to Pick one. */
    val isSingle: Boolean get() = songs.size < 2 || title.isNullOrBlank()

    companion object {
        /** [songs] as one album, named by their most common album and album artist tags. */
        fun of(songs: List<PhoneSong>): PhoneAlbum = PhoneAlbum(null, null, songs)
    }
}

/** Builds albums from the whole library, never from the ledger. */
object PhoneAlbums {
    /**
     * Songs with the same album tag and album artist form one album across folders, so "CD1" and "CD2" merge into one
     * album with discs; different album artists never merge. Without an album artist, the album tag and the folder
     * (less a "CD1"-style disc folder) decide. A song with no album tag, or alone, is a single.
     */
    fun group(songs: List<PhoneSong>): List<PhoneAlbum> = emptyList()
}

/** How names compare: [key] folds case, punctuation, accents, "The" and featured artists. */
internal object Names {
    /** MusicBrainz's "Various Artists" (https://musicbrainz.org/doc/Style/Unknown_and_untitled/Special_purpose_artist). */
    const val VARIOUS_ARTISTS_ID: String = "89ad4ac3-39f7-470e-963a-56509c546377"

    fun key(name: String?): String = name.orEmpty()

    /** "Various Artists", "Various" or "VA" as an album artist. */
    fun isVariousName(name: String?): Boolean = false
}

/** Why an album is not sure: each one is a "sure" rule that does not hold, or a reason it never goes to Ready. */
enum class Doubt {
    /** A single always goes to Pick one. */
    SINGLE,

    /** "Search myself": what the user searched for is offered, never applied on its own. */
    SEARCHED,

    NO_CANDIDATE,

    /** Rule 1: the release has another number of tracks than the album on the phone. */
    TRACK_COUNT,

    /** Rule 1: a song pairs with no track by title. */
    PAIRING,

    /** Rule 2: a song is more than 10 s off its track, or a length is unknown. */
    LENGTHS,

    /** Rule 2: the album's total length is more than 10 s off. */
    ALBUM_LENGTH,

    /** Rule 3. */
    ALBUM_NAME,

    /** Rule 3, Various Artists included. */
    ALBUM_ARTIST,

    /** Rule 4: the distance is over [AlbumMatch.SURE_DISTANCE]. */
    DISTANCE,

    /** Rule 4: another release is within [AlbumMatch.MIN_GAP] (and is not the same edition). */
    GAP,

    /** Rule 4: the only candidate, and a title pairs below [AlbumMatch.LONE_SIMILARITY]. */
    LONE_TITLES,

    /** Rule 5: a disc or track number in the files disagrees with the release. */
    NUMBERS,
}

/** A song paired with a track by title (similarity at least [AlbumMatch.MIN_SIMILARITY]), then length. */
data class SongPair(val song: PhoneSong, val track: ReleaseTrack, val similarity: Double)

data class Pairing(val pairs: List<SongPair>, val unpairedSongs: List<PhoneSong>, val unpairedTracks: List<ReleaseTrack>)

/** How many of the album's songs pair with a track and are within 10 s of it: "12 of 12 songs fit". */
data class Fit(val fitting: Int, val total: Int) {
    val line: String get() = "$fitting of $total songs fit"
}

/** A release fitted to the songs on the phone: which song is which track, and how far apart they are (0 is equal). */
data class ReleaseMatch(val release: MbRelease, val pairing: Pairing, val distance: Double) {
    val fit: Fit get() = Fit(0, 0)
}

/** What Covers & tags proposes for an album. */
sealed interface Proposal {
    /** Every "sure" rule holds: one release, ready to apply. */
    data class Ready(val match: ReleaseMatch) : Proposal

    /** Up to [AlbumMatch.MAX_SHOWN] candidates, best first, and why none is sure. */
    data class PickOne(val candidates: List<ReleaseMatch>, val doubts: Set<Doubt>) : Proposal
}

/**
 * Picks the release for an album, and says whether it is sure (covers-tags-spec.md, "When a match is sure"). Pure:
 * the requests are [AlbumLookup]'s.
 */
object AlbumMatch {
    /** A song pairs only with a track whose title is at least this similar ([titleSimilarity]). */
    const val MIN_SIMILARITY: Double = 0.9

    /** With no other candidate at all, every title must be at least this similar. */
    const val LONE_SIMILARITY: Double = 0.95

    /** Every song within this of its track, and the album's total within it too. */
    const val LENGTH_TOLERANCE_MS: Long = 10_000

    /** The best release's distance is at most this... */
    const val SURE_DISTANCE: Double = 0.04

    /** ...and the next release's is at least this much more. */
    const val MIN_GAP: Double = 0.02

    /** Pick one shows at most this many candidates. */
    const val MAX_SHOWN: Int = 3

    /** Albums with more songs are never matched (a whole library tagged as one album). */
    const val MAX_SONGS: Int = 200

    /** Ready when the best of [releases] holds every "sure" rule; Pick one with the best [MAX_SHOWN] otherwise. */
    fun propose(album: PhoneAlbum, releases: List<MbRelease>, searched: Boolean = false): Proposal = Proposal.PickOne(emptyList(), emptySet())

    /** A single's candidates from a recording search: always Pick one, one per release group, best first. */
    fun proposeSingle(song: PhoneSong, recordings: List<MbRecording>): Proposal.PickOne = Proposal.PickOne(emptyList(), emptySet())

    fun match(album: PhoneAlbum, release: MbRelease): ReleaseMatch = ReleaseMatch(release, Pairing(emptyList(), album.songs, release.tracks), 1.0)

    /**
     * Pairs songs with tracks: only by title at [MIN_SIMILARITY] or more, the most pairs first, then the most similar
     * titles, then the closest lengths. Never by file order, never by length alone.
     */
    fun pair(songs: List<PhoneSong>, tracks: List<ReleaseTrack>): Pairing = Pairing(emptyList(), songs, tracks)

    /** The "sure" rules [best] breaks, with [others] the rest of the candidates (any order). Empty: Ready. */
    fun doubts(album: PhoneAlbum, best: ReleaseMatch, others: List<ReleaseMatch>): Set<Doubt> = setOf(Doubt.NO_CANDIDATE)

    /** 1 for the same title, 0 for nothing alike: edit distance over the titles' [titleKey]s. */
    fun titleSimilarity(a: String?, b: String?): Double = 0.0

    /** beets' string distance, 0 (equal) to 1 (nothing alike), with brackets, "feat." and "The" costing less. */
    fun stringDistance(a: String?, b: String?): Double = 1.0

    /** The beets-style distance of [release] from [album] with its [pairing], 0 to 1. */
    fun distance(album: PhoneAlbum, release: MbRelease, pairing: Pairing): Double = 1.0

    /** The same release group with the same track list and lengths: a tie that is fine. */
    fun sameEdition(a: MbRelease, b: MbRelease): Boolean = false
}
