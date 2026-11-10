package app.lyrify.tags.lookup

import app.lyrify.lyrics.TrackNames
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

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

    /** The album's artist: its album artist tag, else the one artist every song has; null for a compilation. */
    val artist: String?
        get() {
            Names.usable(albumArtist)?.let { return it.takeUnless(Names::isVariousName) }
            val artists = songs.map { Names.usable(it.artist) }
            val first = artists.firstOrNull() ?: return null
            return first.takeIf { artists.all { other -> other != null && Names.key(other) == Names.key(first) } }
        }

    /** "Various Artists" as album artist, or no album artist and songs by different artists. */
    val isVarious: Boolean get() = artist == null

    /** The year most songs carry. */
    internal val year: Int? get() = mostCommon(songs.mapNotNull { it.year })

    /** The MusicBrainz release id most songs carry, when it is one. */
    internal val releaseId: String? get() = mostCommon(songs.mapNotNull { it.musicBrainzReleaseId?.takeIf(MusicBrainzUrls::isId)?.lowercase() })

    /** The discs the songs' numbers say the album has; null when no song has a disc number. */
    internal val discs: Int? get() = songs.mapNotNull { it.disc }.maxOrNull()

    companion object {
        /** [songs] as one album, named by their most common album and album artist tags. */
        fun of(songs: List<PhoneSong>): PhoneAlbum = PhoneAlbum(
            mostCommon(songs.mapNotNull { it.album?.takeIf(String::isNotBlank) }),
            mostCommon(songs.mapNotNull { it.albumArtist?.takeIf(String::isNotBlank) }),
            songs,
        )
    }
}

/** The value seen most often, the first seen on a tie. */
internal fun <T> mostCommon(values: List<T>): T? = values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

/** Builds albums from the whole library, never from the ledger. */
object PhoneAlbums {
    /** "CD1", "Disc 2", "Lanterns CD2": a folder holding one disc of an album. */
    private val DISC_FOLDER = Regex("""^(.*?)[\s._-]*(?:cd|disc|disk)[\s._-]*\d{1,2}$""", RegexOption.IGNORE_CASE)

    /**
     * Songs with the same album tag and album artist form one album across folders, so "CD1" and "CD2" merge into one
     * album with discs; different album artists never merge. Without an album artist, the album tag and the folder
     * (less a "CD1"-style disc folder) decide. A song with no album tag, or alone, is a single.
     */
    fun group(songs: List<PhoneSong>): List<PhoneAlbum> =
        songs.groupBy { song ->
            val album = TrackNames.normalize(song.album.orEmpty())
            when {
                album.isEmpty() -> "song:${song.path}"
                else -> "$album\n" + (Names.usable(song.albumArtist)?.let { "artist:" + Names.key(it) } ?: "folder:" + albumFolder(song.path))
            }
        }.values.map(PhoneAlbum::of)

    /** The folder an album lives in: a disc folder stands for its parent ("Lanterns/CD1", "Lanterns CD1": "Lanterns"). */
    private fun albumFolder(path: String): String {
        val folder = path.substringBeforeLast('/', "")
        val parent = folder.substringBeforeLast('/', "")
        val base = DISC_FOLDER.find(folder.substringAfterLast('/'))?.groupValues?.get(1)?.trim() ?: return folder.lowercase(Locale.ROOT)
        return (if (base.isEmpty()) parent else "$parent/$base").lowercase(Locale.ROOT)
    }
}

/** How names compare: [key] folds case, punctuation, accents, "The" and featured artists. */
internal object Names {
    /** MusicBrainz's "Various Artists" (https://musicbrainz.org/doc/Style/Unknown_and_untitled/Special_purpose_artist). */
    const val VARIOUS_ARTISTS_ID: String = "89ad4ac3-39f7-470e-963a-56509c546377"

    /** As beets reads album artists ("V.A." included); "Unknown" means no name at all here ([usable]). */
    private val VARIOUS = setOf("various artists", "various", "va", "v a")
    private val UNKNOWN = setOf("unknown", "unknown artist", "unknown album")

    fun key(name: String?): String {
        val folded = TrackNames.normalize(TrackNames.withoutFeat(name.orEmpty()))
        return folded.removePrefix("the ").removeSuffix(" the")
    }

    /** "Various Artists", "Various" or "VA" as an album artist. */
    fun isVariousName(name: String?): Boolean = name != null && TrackNames.normalize(name) in VARIOUS

    /** [name] when it names someone: not blank, not "Unknown artist". */
    fun usable(name: String?): String? = name?.takeIf { it.isNotBlank() && TrackNames.normalize(it) !in UNKNOWN }
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
data class SongPair(val song: PhoneSong, val track: ReleaseTrack, val similarity: Double) {
    /** Within [AlbumMatch.LENGTH_TOLERANCE_MS] of a track whose length is known. */
    val fits: Boolean get() = track.track.lengthMs?.let { abs(song.durationMs - it) <= AlbumMatch.LENGTH_TOLERANCE_MS } == true
}

data class Pairing(val pairs: List<SongPair>, val unpairedSongs: List<PhoneSong>, val unpairedTracks: List<ReleaseTrack>)

/** How many of the album's songs pair with a track and are within 10 s of it: "12 of 12 songs fit". */
data class Fit(val fitting: Int, val total: Int) {
    val line: String get() = if (total == 1) "$fitting of 1 song fits" else "$fitting of $total songs fit"
}

/** A release fitted to the songs on the phone: which song is which track, and how far apart they are (0 is equal). */
data class ReleaseMatch(val release: MbRelease, val pairing: Pairing, val distance: Double) {
    val fit: Fit get() = Fit(pairing.pairs.count { it.fits }, pairing.pairs.size + pairing.unpairedSongs.size)
}

/** What Covers & tags proposes for an album. */
sealed interface Proposal {
    /** Every "sure" rule holds: one release, ready to apply. */
    data class Ready(val match: ReleaseMatch) : Proposal

    /** Up to [AlbumMatch.MAX_SHOWN] candidates, best first, and why none is sure. */
    data class PickOne(val candidates: List<ReleaseMatch>, val doubts: Set<Doubt>) : Proposal
}

/**
 * Picks the release for an album, and says whether it is sure (covers-tags-spec.md, "When a match is sure"). The
 * distance follows beets (beets/autotag/distance.py): a weighted average of penalties from 0 to 1, with its weights.
 * Pure: the requests are [AlbumLookup]'s.
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

    /** Thresholds compare with this much room, so 0.06 - 0.04 counts as the 0.02 it is. */
    private const val EPSILON = 1e-9

    /** Ready when the best of [releases] holds every "sure" rule; Pick one with the best [MAX_SHOWN] otherwise. */
    fun propose(album: PhoneAlbum, releases: List<MbRelease>, searched: Boolean = false): Proposal {
        val matches = releases.distinctBy { it.id }.map { match(album, it) }.sortedBy { it.distance }
        val extra = setOfNotNull(Doubt.SEARCHED.takeIf { searched }, Doubt.SINGLE.takeIf { album.isSingle })
        val best = matches.firstOrNull() ?: return Proposal.PickOne(emptyList(), extra + Doubt.NO_CANDIDATE)
        val doubts = doubts(album, best, matches.drop(1)) + extra
        return if (doubts.isEmpty()) Proposal.Ready(best) else Proposal.PickOne(matches.take(MAX_SHOWN), doubts)
    }

    /**
     * A single's candidates from a recording search: always Pick one, best first (those that fit, then the closest
     * title, length and artist, then not a compilation, then the earliest), one per release group.
     */
    fun proposeSingle(song: PhoneSong, recordings: List<MbRecording>): Proposal.PickOne {
        val candidates = recordings.flatMap { it.releases }.distinctBy { it.id }.mapNotNull { release ->
            val track = release.tracks.firstOrNull() ?: return@mapNotNull null
            ReleaseMatch(release, pair(listOf(song), listOf(track)), singleDistance(song, track))
        }
        val ranked = candidates
            .sortedWith(
                compareByDescending<ReleaseMatch> { it.fit.fitting }
                    .thenBy { it.distance }
                    .thenBy { it.release.releaseGroup?.isCompilation == true }
                    .thenBy { it.release.date ?: LAST_DATE },
            )
            .distinctBy { it.release.releaseGroup?.id ?: it.release.id }
        return Proposal.PickOne(ranked.take(MAX_SHOWN), setOf(Doubt.SINGLE))
    }

    fun match(album: PhoneAlbum, release: MbRelease): ReleaseMatch {
        val pairing = pair(album.songs, release.tracks)
        return ReleaseMatch(release, pairing, distance(album, release, pairing))
    }

    /**
     * Pairs songs with tracks: only by title at [MIN_SIMILARITY] or more, the most pairs first, then the most similar
     * titles, then the closest lengths. Never by file order, never by length alone.
     */
    fun pair(songs: List<PhoneSong>, tracks: List<ReleaseTrack>): Pairing {
        if (songs.isEmpty() || tracks.isEmpty() || songs.size > MAX_SONGS || tracks.size > MAX_SONGS) return Pairing(emptyList(), songs, tracks)
        val similarity = Array(songs.size) { i -> DoubleArray(tracks.size) { j -> titleSimilarity(songs[i].title, tracks[j].track.title) } }
        fun cost(i: Int, j: Int): Long {
            if (similarity[i][j] < MIN_SIMILARITY - EPSILON) return FORBIDDEN
            val title = ((1 - similarity[i][j]) * TITLE_STEPS).roundToLong()
            val length = tracks[j].track.lengthMs?.let { (abs(songs[i].durationMs - it) / MS_PER_STEP).coerceAtMost(LENGTH_CAP) } ?: LENGTH_CAP
            return title * TITLE_SCALE + length
        }
        // The assignment wants no more rows than columns.
        val bySong = songs.size <= tracks.size
        val costs = if (bySong) {
            Array(songs.size) { i -> LongArray(tracks.size) { j -> cost(i, j) } }
        } else {
            Array(tracks.size) { j -> LongArray(songs.size) { i -> cost(i, j) } }
        }
        val paired = assign(costs).withIndex()
            .filter { (row, column) -> costs[row][column] < FORBIDDEN }
            .map { (row, column) -> if (bySong) row to column else column to row }
            .sortedBy { (_, j) -> j }
        val songsPaired = paired.map { it.first }.toSet()
        val tracksPaired = paired.map { it.second }.toSet()
        return Pairing(
            paired.map { (i, j) -> SongPair(songs[i], tracks[j], similarity[i][j]) },
            songs.filterIndexed { i, _ -> i !in songsPaired },
            tracks.filterIndexed { j, _ -> j !in tracksPaired },
        )
    }

    /** The "sure" rules [best] breaks, with [others] the rest of the candidates (any order). Empty: Ready. */
    fun doubts(album: PhoneAlbum, best: ReleaseMatch, others: List<ReleaseMatch>): Set<Doubt> {
        val release = best.release
        val pairs = best.pairing.pairs
        val tracks = release.tracks
        val rival = others.filterNot { sameEdition(release, it.release) }.minByOrNull { it.distance }
        val trackTotal = tracks.sumOf { it.track.lengthMs ?: 0 }
        return buildSet {
            if (album.isSingle) add(Doubt.SINGLE)
            if (!release.hasAllTracks || release.trackCount != album.songs.size) add(Doubt.TRACK_COUNT)
            if (pairs.size != album.songs.size) add(Doubt.PAIRING)
            if (pairs.any { !it.fits } || album.songs.any { it.durationMs <= 0 }) add(Doubt.LENGTHS)
            if (abs(album.songs.sumOf { it.durationMs } - trackTotal) > LENGTH_TOLERANCE_MS) add(Doubt.ALBUM_LENGTH)
            if (Names.key(album.title).isEmpty() || Names.key(album.title) != Names.key(release.title)) add(Doubt.ALBUM_NAME)
            if (!sameArtist(album, release)) add(Doubt.ALBUM_ARTIST)
            if (pairs.any { !numbersAgree(it) }) add(Doubt.NUMBERS)
            if (best.distance > SURE_DISTANCE + EPSILON) add(Doubt.DISTANCE)
            if (rival != null && rival.distance - best.distance < MIN_GAP - EPSILON) add(Doubt.GAP)
            if (others.isEmpty() && pairs.any { it.similarity < LONE_SIMILARITY - EPSILON }) add(Doubt.LONE_TITLES)
        }
    }

    /** 1 for the same title, 0 for nothing alike: edit distance over the titles' [titleKey]s. */
    fun titleSimilarity(a: String?, b: String?): Double {
        val x = titleKey(a)
        val y = titleKey(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    /**
     * A title as pairing compares it: what downloads add taken off ([TrackNames.cleanTitle]: "(Official Video)", "04. "),
     * featured artists left to the artist field, then [TrackNames.normalize] without spaces, so "Don't" is "Dont".
     * Version tags stay: a remaster's title is another title.
     */
    private fun titleKey(title: String?): String =
        title?.let { TrackNames.normalize(TrackNames.withoutFeat(TrackNames.cleanTitle(it))).replace(" ", "") }.orEmpty()

    /** beets' string distance, 0 (equal) to 1 (nothing alike), with brackets, "feat." and "The" costing less. */
    fun stringDistance(a: String?, b: String?): Double {
        if (a == null && b == null) return 0.0
        if (a == null || b == null) return 1.0
        var x = endWordsFirst(a.lowercase(Locale.ROOT)).replace("&", "and")
        var y = endWordsFirst(b.lowercase(Locale.ROOT)).replace("&", "and")
        var base = basicDistance(x, y)
        var penalty = 0.0
        for ((pattern, weight) in LESSER_PARTS) {
            val shorterX = pattern.replace(x, "")
            val shorterY = pattern.replace(y, "")
            if (shorterX == x && shorterY == y) continue
            val without = basicDistance(shorterX, shorterY)
            val delta = (base - without).coerceAtLeast(0.0)
            if (delta == 0.0) continue
            // The rest is compared without this part; the part costs only its weight.
            x = shorterX
            y = shorterY
            base = without
            penalty += weight * delta
        }
        return (base + penalty).coerceIn(0.0, 1.0)
    }

    /** The beets-style distance of [release] from [album] with its [pairing], 0 to 1. */
    fun distance(album: PhoneAlbum, release: MbRelease, pairing: Pairing): Double {
        val penalties = Penalties()
        penalties.add(W_ALBUM, stringDistance(album.title, release.title))
        val artist = when {
            album.isVarious != release.artist.isVarious -> 1.0
            album.isVarious -> 0.0
            else -> stringDistance(album.artist, release.artist.text)
        }
        penalties.add(W_ARTIST, artist)
        album.year?.let { year -> yearPenalty(year, release)?.let { penalties.add(W_YEAR, it) } }
        album.discs?.let { discs ->
            val off = abs(discs - release.media.size)
            if (off == 0) penalties.add(W_MEDIUMS, 0.0) else repeat(off) { penalties.add(W_MEDIUMS, 1.0) }
        }
        album.releaseId?.let { penalties.add(W_ALBUM_ID, if (it == release.id) 0.0 else 1.0) }
        pairing.pairs.forEach { penalties.add(W_TRACKS, trackDistance(it, release.artist.isVarious)) }
        repeat(pairing.unpairedTracks.size) { penalties.add(W_MISSING_TRACKS, 1.0) }
        repeat(pairing.unpairedSongs.size) { penalties.add(W_UNMATCHED_TRACKS, 1.0) }
        return penalties.distance
    }

    /** The same release group with the same track list and lengths: a tie that is fine. */
    fun sameEdition(a: MbRelease, b: MbRelease): Boolean {
        val group = a.releaseGroup?.id ?: return false
        if (group != b.releaseGroup?.id || !a.hasAllTracks || !b.hasAllTracks) return false
        val x = a.tracks
        val y = b.tracks
        return x.size == y.size && x.zip(y).all { (s, t) ->
            s.disc == t.disc && titleKey(s.track.title) == titleKey(t.track.title) && sameLength(s.track.lengthMs, t.track.lengthMs)
        }
    }

    private fun sameLength(a: Long?, b: Long?): Boolean = a != null && b != null && abs(a - b) <= EDITION_LENGTH_MS

    private fun sameArtist(album: PhoneAlbum, release: MbRelease): Boolean =
        album.isVarious == release.artist.isVarious && (album.isVarious || Names.key(album.artist) == Names.key(release.artist.text))

    /** The song's numbers, where it has them, are its track's: disc, and position on the disc or over the release. */
    private fun numbersAgree(pair: SongPair): Boolean =
        (pair.song.disc == null || pair.song.disc == pair.track.disc) &&
            (pair.song.track == null || pair.song.track == pair.track.track.position || pair.song.track == pair.track.index)

    private fun trackDistance(pair: SongPair, various: Boolean): Double {
        val penalties = Penalties()
        penalties.add(W_TRACK_TITLE, stringDistance(comparableTitle(pair.song.title), pair.track.track.title))
        pair.track.track.lengthMs?.let { penalties.add(W_TRACK_LENGTH, lengthPenalty(pair.song.durationMs, it)) }
        if (various) {
            Names.usable(pair.song.artist)?.takeUnless(Names::isVariousName)?.let { penalties.add(W_TRACK_ARTIST, stringDistance(it, pair.track.track.artist.text)) }
        }
        pair.song.track?.let { number -> penalties.add(W_TRACK_INDEX, if (number == pair.track.track.position || number == pair.track.index) 0.0 else 1.0) }
        return penalties.distance
    }

    /** A single against one track: its title, length and artist. */
    private fun singleDistance(song: PhoneSong, track: ReleaseTrack): Double {
        val penalties = Penalties()
        penalties.add(W_TRACK_TITLE, stringDistance(comparableTitle(song.title), track.track.title))
        penalties.add(W_TRACK_LENGTH, track.track.lengthMs?.let { lengthPenalty(song.durationMs, it) } ?: 1.0)
        penalties.add(W_ARTIST, Names.usable(song.artist)?.let { stringDistance(it, track.track.artist.text) } ?: 1.0)
        return penalties.distance
    }

    /** A song's title as distances compare it: without what downloads add ("(Official Video)"), as pairing does. */
    private fun comparableTitle(title: String?): String? = title?.let(TrackNames::cleanTitle)

    /** beets: nothing within the 10 s grace, then up to 1 at 30 s past it. */
    private fun lengthPenalty(songMs: Long, trackMs: Long): Double = (abs(songMs - trackMs) - LENGTH_GRACE_MS).toDouble() / LENGTH_MAX_MS

    /**
     * beets: none when the songs' year is the release's or the original one; otherwise more the further off, up to 1 at
     * [YEAR_SPAN] years. Null when the release has no year.
     */
    private fun yearPenalty(year: Int, release: MbRelease): Double? {
        val releaseYear = release.year ?: release.releaseGroup?.originalYear ?: return null
        if (year == releaseYear || year == release.releaseGroup?.originalYear) return 0.0
        return abs(year - releaseYear).coerceAtMost(YEAR_SPAN).toDouble() / YEAR_SPAN
    }

    private fun endWordsFirst(text: String): String =
        END_WORDS.firstOrNull { text.endsWith(", $it") }?.let { "$it ${text.dropLast(it.length + 2)}" } ?: text

    /** Edit distance over lowercase letters and digits only (accents dropped), over the longer one's length. */
    private fun basicDistance(a: String, b: String): Double {
        val x = fold(a)
        val y = fold(b)
        if (x.isEmpty() && y.isEmpty()) return 0.0
        return levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(COMBINING, "").lowercase(Locale.ROOT).replace(NOT_ALPHANUMERIC, "")

    private fun levenshtein(a: String, b: String): Int {
        var above = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1)
            row[0] = i + 1
            for (j in b.indices) {
                row[j + 1] = minOf(above[j + 1] + 1, row[j] + 1, above[j] + if (a[i] == b[j]) 0 else 1)
            }
            above = row
        }
        return above[b.length]
    }

    /**
     * The columns given to each row (rows at most columns) at the least total cost: the Hungarian method with
     * potentials, O(rows² × columns).
     */
    private fun assign(cost: Array<LongArray>): IntArray {
        val rows = cost.size
        val columns = cost[0].size
        val u = LongArray(rows + 1)
        val v = LongArray(columns + 1)
        val owner = IntArray(columns + 1)
        val way = IntArray(columns + 1)
        for (row in 1..rows) {
            owner[0] = row
            var column = 0
            val least = LongArray(columns + 1) { UNREACHED }
            val used = BooleanArray(columns + 1)
            do {
                used[column] = true
                val current = owner[column]
                var delta = UNREACHED
                var next = 0
                for (j in 1..columns) {
                    if (used[j]) continue
                    val reduced = cost[current - 1][j - 1] - u[current] - v[j]
                    if (reduced < least[j]) {
                        least[j] = reduced
                        way[j] = column
                    }
                    if (least[j] < delta) {
                        delta = least[j]
                        next = j
                    }
                }
                for (j in 0..columns) {
                    if (used[j]) {
                        u[owner[j]] += delta
                        v[j] -= delta
                    } else {
                        least[j] -= delta
                    }
                }
                column = next
            } while (owner[column] != 0)
            do {
                val previous = way[column]
                owner[column] = owner[previous]
                column = previous
            } while (column != 0)
        }
        val assigned = IntArray(rows)
        for (j in 1..columns) if (owner[j] != 0) assigned[owner[j] - 1] = j - 1
        return assigned
    }

    /** Penalties and their weights: the distance is the weighted penalties over the weights. */
    private class Penalties {
        private var sum = 0.0
        private var max = 0.0

        fun add(weight: Double, penalty: Double) {
            sum += weight * penalty.coerceIn(0.0, 1.0)
            max += weight
        }

        val distance: Double get() = if (max == 0.0) 0.0 else sum / max
    }

    // beets' weights (config_default.yaml, match.distance_weights).
    private const val W_ALBUM = 3.0
    private const val W_ARTIST = 3.0
    private const val W_YEAR = 1.0
    private const val W_MEDIUMS = 1.0
    private const val W_ALBUM_ID = 5.0
    private const val W_TRACKS = 2.0
    private const val W_MISSING_TRACKS = 0.9
    private const val W_UNMATCHED_TRACKS = 0.6
    private const val W_TRACK_TITLE = 3.0
    private const val W_TRACK_ARTIST = 2.0
    private const val W_TRACK_LENGTH = 2.0
    private const val W_TRACK_INDEX = 1.0
    private const val LENGTH_GRACE_MS = 10_000L
    private const val LENGTH_MAX_MS = 30_000.0
    private const val YEAR_SPAN = 30

    /** Two editions' tracks of "identical" length differ by at most this (disc lengths round differently). */
    private const val EDITION_LENGTH_MS = 1_000L

    /** Pairing costs: titles first (1 - similarity in 10,000 steps), then lengths in tenths of a second, never mixed. */
    private const val TITLE_STEPS = 10_000.0
    private const val MS_PER_STEP = 100L
    private const val LENGTH_CAP = 99_999L

    /** Above every length total of [MAX_SONGS] pairs, so one title step outweighs any lengths. */
    private const val TITLE_SCALE = 100_000_000L

    /** Above every total of allowed pairs, so the most pairs always win. */
    private const val FORBIDDEN = 100_000_000_000_000L
    private const val UNREACHED = Long.MAX_VALUE / 4

    private const val LAST_DATE = "9999"

    private val END_WORDS = listOf("the", "a", "an")

    /** beets' SD_PATTERNS with word boundaries (beets' own "ep" pattern also cuts "deep"), and the weight each costs. */
    private val LESSER_PARTS = listOf(
        Regex("""^the """) to 0.1,
        Regex("""[\[(]?\b(?:ep|single)\b[\])]?""") to 0.0,
        Regex("""[\[(]?\b(?:featuring|feat|ft)[. :].+""") to 0.1,
        Regex("""\(.*?\)""") to 0.3,
        Regex("""\[.*?]""") to 0.3,
        Regex("""(?:, )?\b(?:pt\.|part) .+""") to 0.2,
    )
    private val COMBINING = Regex("""\p{M}+""")
    private val NOT_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
}
