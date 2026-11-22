package app.lyrify.similar

import app.lyrify.suggestions.SimilarArtist
import app.lyrify.suggestions.SimilarArtistSource
import app.lyrify.tags.HistoryEntry
import app.lyrify.tags.SongEdit
import app.lyrify.tags.lookup.Body
import app.lyrify.tags.lookup.MbArtist
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.MetaAnswer
import app.lyrify.tags.lookup.MetaClient
import app.lyrify.tags.lookup.MetaHosts
import app.lyrify.tags.lookup.MusicBrainz
import app.lyrify.tags.lookup.MusicBrainzUrls
import app.lyrify.tags.lookup.Names
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Similar artists (KJB 3.2, lane lb1): for the artists the user plays, the artists ListenBrainz's listeners play in the
// same sessions, looked up slowly in the background and kept on the phone (`similar-artists.json`). Suggestions read
// only this cache, never the network (handoff/suggest/lb-research.md, plan.md 2b).

/** An artist ListenBrainz lists as like another: its MusicBrainz id, its name there, and its score (higher is closer). */
@Serializable
data class Neighbour(val mbid: String, val name: String, val score: Int)

/** How a library artist's name went with MusicBrainz: one artist for sure, none, or several too close to pick from. */
enum class Match { FOUND, NONE, AMBIGUOUS }

/**
 * A library artist as last looked up ([at], wall-clock millis): its [name] as the library showed it, its MusicBrainz id
 * when one was [Match.FOUND] for sure, and its [neighbours], the closest first (empty: ListenBrainz has none). [failures]:
 * its own lookups that failed in a row since, [at] the last one ([SimilarRules.ownFailure]); an artist never looked up
 * before is kept as [Match.NONE] meanwhile.
 */
@Serializable
data class ArtistSimilar(
    val name: String,
    val match: Match,
    val mbid: String? = null,
    val neighbours: List<Neighbour> = emptyList(),
    val at: Long,
    val failures: Int = 0,
)

/**
 * What `similar-artists.json` keeps: each artist looked up, by its name's key ([app.lyrify.tags.lookup.Names.key]); the
 * ListenBrainz [algorithm] the lists came from; and the runs' state: the [failures] in a row and the pause they set
 * ([pausedUntil]), ListenBrainz's "gone" answers in a row ([gone]), and the app version that stopped asking for good
 * ([stoppedIn], the kill flag; a newer version asks again).
 */
@Serializable
data class SimilarMemory(
    val artists: Map<String, ArtistSimilar> = emptyMap(),
    val algorithm: String = ListenBrainzUrls.ALGORITHM,
    val failures: Int = 0,
    val pausedUntil: Long = 0,
    val gone: Int = 0,
    val stoppedIn: Int? = null,
)

/** The MusicBrainz ids KJB wrote into a song (Covers & tags): its release, and its recording when written. */
data class WrittenIds(val release: String, val recording: String?)

/** An artist the user plays, to look up: its name as the library shows it, and the ids KJB wrote into one of its songs. */
data class PlayedArtist(val name: String, val written: WrittenIds? = null)

/** The rules of the lookups and of the cache, pure. */
object SimilarRules {
    /** A list is looked up again after this many days (user, 2026-11-22). */
    const val REFRESH_DAYS: Long = 60

    /** At most this many artists per run: a library's first fill spreads over days. */
    const val PER_RUN: Int = 20

    /** At least this long between two of a run's requests: MusicBrainz answered 503 at 1.2 s in the probe (lb-research.md). */
    const val SPACING_MS: Long = 3_000

    /** The closest neighbours kept per artist. */
    const val KEEP: Int = 50

    /** ListenBrainz "gone" answers in a row that stop the lookups until the next app version (the kill flag). */
    const val GONE_TO_STOP: Int = 3

    /** A name matches for sure only as MusicBrainz's top hit at this score... */
    const val SURE_SCORE: Int = 100

    /** ...with every other hit at this score or lower: a clear gap (user, 2026-11-22). */
    const val OTHERS_AT_MOST: Int = 85

    /** The pause before asking again after each failure in a row: 1 day, 2, 4, 8, then 14 days. */
    val PAUSE_DAYS: List<Long> = listOf(1, 2, 4, 8, 14)

    /** The wait before an artist is asked again after each of its own failures in a row: 1 day, 7, 30, then [REFRESH_DAYS]. */
    val ARTIST_RETRY_DAYS: List<Long> = listOf(1, 7, 30)

    /** Artists failing on their own in a row that end a run and pause the lookups, as a failure: likely not theirs. */
    const val MISSES_TO_PAUSE: Int = 3

    /** Statuses that say the service refuses KJB, timed out or wants fewer requests, never one artist's own failure. */
    private val REFUSING: Set<Int> = setOf(401, 403, 407, 408, 429)

    /**
     * Statuses that, for an artist ListenBrainz listed before, say its endpoint is gone or closed to KJB (removed, a
     * token needed); for one it never listed, that there is no list (user, 2026-11-22).
     */
    val GONE: Set<Int> = setOf(401, 403, 404, 410)

    /**
     * Whether an artist is looked up now: never before, [REFRESH_DAYS] ago or more (after its own failures,
     * [ARTIST_RETRY_DAYS] by their count, then [REFRESH_DAYS]), or at a time after [now] (the clock changed).
     */
    fun due(entry: ArtistSimilar?, now: Long): Boolean =
        entry == null || entry.at > now || now - entry.at >= ARTIST_RETRY_DAYS.getOrElse(entry.failures - 1) { REFRESH_DAYS } * DAY_MS

    /**
     * Whether [answer] failed for its artist alone: the service answered plainly (a 404, another 4xx, or a body that did
     * not read) but not usably for it. Busy, a 5xx, a status that refuses KJB ([REFUSING]) or no answer at all is the
     * service's or the phone's.
     */
    fun ownFailure(answer: MetaAnswer<*>): Boolean = when (answer) {
        MetaAnswer.NotFound -> true
        is MetaAnswer.Failed -> answer.status?.let { it < HttpURLConnection.HTTP_INTERNAL_ERROR && it !in REFUSING } == true
        else -> false
    }

    /** The pause after [failures] failures in a row, in millis. */
    fun pauseAfter(failures: Int): Long = PAUSE_DAYS[(failures - 1).coerceIn(0, PAUSE_DAYS.lastIndex)] * DAY_MS

    /**
     * How [hits] match [name]: the top hit at [SURE_SCORE], named so (its name, sort name or an alias, as
     * [app.lyrify.tags.lookup.Names.key] compares them), with every other hit at [OTHERS_AT_MOST] or lower: found, with
     * its id; another hit above that (a tie included): ambiguous; else none.
     */
    fun match(hits: List<MbArtist>, name: String): Pair<Match, String?> {
        val ranked = hits.sortedByDescending { it.score ?: 0 }
        val top = ranked.firstOrNull()?.takeIf { (it.score ?: 0) >= SURE_SCORE } ?: return Match.NONE to null
        return when {
            ranked.drop(1).any { (it.score ?: 0) > OTHERS_AT_MOST } -> Match.AMBIGUOUS to null
            named(top, Names.key(name)) -> Match.FOUND to top.id
            else -> Match.NONE to null
        }
    }

    /** Whether [hit] goes by the name whose key is [key]: its name, an alias, or its sort name as is or turned round ("Solen, Mira"). */
    private fun named(hit: MbArtist, key: String): Boolean {
        val sortNames = hit.sortName?.let { sort -> listOf(sort, sort.split(", ", limit = 2).reversed().joinToString(" ")) }.orEmpty()
        return key.isNotEmpty() && (listOf(hit.name) + hit.aliases + sortNames).any { Names.key(it) == key }
    }

    /**
     * Each of [artists] (names as the library shows them) with its neighbours in [memory], the closest first, scored
     * against the closest; one with no neighbours is left out. A neighbour that is an artist of the library (one looked
     * up to the same id) carries the library's name.
     */
    fun similar(memory: SimilarMemory, artists: Collection<String>): Map<String, List<SimilarArtist>> {
        val libraryNames = memory.artists.values.mapNotNull { entry -> entry.mbid?.takeIf { entry.match == Match.FOUND }?.let { it to entry.name } }.toMap()
        return artists.mapNotNull { artist ->
            val entry = memory.artists[Names.key(artist)] ?: return@mapNotNull null
            val others = entry.neighbours.filter { it.mbid != entry.mbid }.sortedByDescending { it.score }
            val closest = others.firstOrNull()?.score?.takeIf { it > 0 } ?: return@mapNotNull null
            artist to others.map { SimilarArtist(libraryNames[it.mbid] ?: it.name, it.score.toFloat() / closest) }
        }.toMap()
    }

    /** [memory] for [algorithm]: a change of algorithm drops the lists (each artist found due again) and keeps the ids. */
    fun forAlgorithm(memory: SimilarMemory, algorithm: String): SimilarMemory {
        if (memory.algorithm == algorithm) return memory
        val artists = memory.artists.mapValues { (_, entry) -> if (entry.match == Match.FOUND) entry.copy(neighbours = emptyList(), at = 0) else entry }
        return memory.copy(algorithm = algorithm, artists = artists)
    }

    /** The ids KJB last wrote into each song, by path, from History's [entries]; an edit taken back is left out. */
    fun writtenByPath(entries: List<HistoryEntry>): Map<String, WrittenIds> =
        entries.sortedWith(compareByDescending<HistoryEntry> { it.at }.thenByDescending { it.id })
            .flatMap { it.songs }
            .filterNot { it.undone }
            .mapNotNull { edit -> edit.written(RELEASE_ID)?.let { edit.path to WrittenIds(it, edit.written(RECORDING_ID)) } }
            .distinctBy { it.first }
            .toMap()

    /** The MusicBrainz id of the artist named [name] in [release]'s credit for [recording] (else the release's own); null when none is. */
    fun artistIdIn(release: MbRelease, recording: String?, name: String): String? {
        val credit = recording?.let { id -> release.tracks.firstOrNull { it.track.recordingId == id }?.track?.artist } ?: release.artist
        val key = Names.key(name)
        return credit.parts.firstOrNull { MusicBrainzUrls.isId(it.artistId) && it.artistId != Names.VARIOUS_ARTISTS_ID && Names.key(it.name) == key }?.artistId
    }

    /** The id this edit wrote into [field], when it is one. */
    private fun SongEdit.written(field: String): String? =
        fields.firstOrNull { it.key == field }?.new?.firstOrNull()?.takeIf(MusicBrainzUrls::isId)?.lowercase()

    /** The fields Covers & tags writes the ids in (tags/CoversTags.kt, coversTagsEdit). */
    private const val RELEASE_ID = "MUSICBRAINZ_ALBUMID"
    private const val RECORDING_ID = "MUSICBRAINZ_TRACKID"

    private const val DAY_MS: Long = 24 * 60 * 60 * 1_000
}

/** ListenBrainz's labs similar-artists endpoint (https://labs.api.listenbrainz.org/similar-artists, read 2026-11-22). */
internal object ListenBrainzUrls {
    /** The 9000-day sessions algorithm, pinned (lb-research.md); a change drops the lists ([SimilarRules.forAlgorithm]). */
    const val ALGORITHM: String = "session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30"

    fun similarArtists(mbid: String): URI {
        require(MusicBrainzUrls.isId(mbid)) { "not a MusicBrainz id" }
        return URI("https://${MetaHosts.LISTENBRAINZ_LABS}/similar-artists/json?artist_mbids=${mbid.lowercase()}&algorithm=$ALGORITHM")
    }
}

/**
 * Reads ListenBrainz's answers (shape recorded on 2026-11-22, lb-research.md): a list of rows, each a similar artist
 * with its score. A row without a proper id or a score is dropped; anything unreadable throws [IllegalArgumentException].
 */
internal object ListenBrainzJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** The neighbours, the closest first. */
    fun neighbours(body: ByteArray): List<Neighbour> = json.decodeFromString<List<RowJson>>(body.decodeToString())
        .mapNotNull { row -> row.score?.takeIf { MusicBrainzUrls.isId(row.artistMbid) }?.let { Neighbour(row.artistMbid.lowercase(), row.name, it) } }
        .sortedByDescending { it.score }

    @Serializable
    private class RowJson(@SerialName("artist_mbid") val artistMbid: String = "", val name: String = "", val score: Int? = null)
}

/**
 * `similar-artists.json`, app-private and not in backups (it is rebuilt from the network, like Covers & tags' lookups).
 * A file that can't be read is started over. Written to a temporary file, then moved over; not fsynced (one lost to a
 * crash is only artists asked again). Logs exception class names only. Blocking.
 */
class SimilarStore(private val file: File, private val log: (String) -> Unit = {}) {
    fun load(): SimilarMemory {
        if (!file.isFile) return SimilarMemory()
        return try {
            json.decodeFromString(SimilarMemory.serializer(), file.readText())
        } catch (e: IOException) {
            log("similar: similar-artists.json not readable (${e.javaClass.simpleName})")
            SimilarMemory()
        } catch (e: IllegalArgumentException) {
            // A SerializationException is one.
            log("similar: similar-artists.json unreadable (${e.javaClass.simpleName}), started over")
            SimilarMemory()
        }
    }

    /** False when not kept. */
    fun save(memory: SimilarMemory): Boolean {
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            aside.writeText(json.encodeToString(SimilarMemory.serializer(), memory))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("similar: similar-artists.json not saved (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val TMP = ".tmp"

        /** Defaults written too: a file always says which algorithm its lists came from. */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * The cache as the lookups write it and suggestions read it: [store] read once, on [io], then kept in memory; each
 * change saved, in order. Once read, a read never waits, not even for a save. Never the network.
 */
class SimilarCache(private val store: SimilarStore, private val io: CoroutineDispatcher = Dispatchers.IO) : SimilarArtistSource {
    private val lock = Mutex()

    /** The memory once read; written holding [lock], read without it. */
    @Volatile
    private var kept: SimilarMemory? = null

    /** For each of [artists] (names as the library shows them), its similar artists ([SimilarRules.similar]). */
    override suspend fun similar(artists: Collection<String>): Map<String, List<SimilarArtist>> = SimilarRules.similar(memory(), artists)

    suspend fun memory(): SimilarMemory = kept ?: lock.withLock { loaded() }

    /** Applies [change] and saves the result, which it returns; it holds in memory even when the file could not be written. */
    suspend fun update(change: (SimilarMemory) -> SimilarMemory): SimilarMemory = lock.withLock {
        val next = change(loaded())
        kept = next
        withContext(io) { store.save(next) }
        next
    }

    /** Called holding [lock]. */
    private suspend fun loaded(): SimilarMemory =
        kept ?: withContext(io) { SimilarRules.forAlgorithm(store.load(), ListenBrainzUrls.ALGORITHM) }.also { kept = it }
}

/**
 * One run of lookups, in the background: up to [SimilarRules.PER_RUN] of the artists given that are due, one at a time,
 * each request [SimilarRules.SPACING_MS] after the previous one. An artist's MusicBrainz id comes from the ids KJB wrote
 * into its songs (a release lookup, from Covers & tags' cache when it has it), else from an earlier match, else from an
 * artist search by name, taken only when sure ([SimilarRules.match]); then ListenBrainz lists its neighbours. MusicBrainz
 * requests go through the [client]'s gate, which Covers & tags shares, with its backoff on 503. A busy or failed answer
 * ends the run and pauses the lookups ([SimilarRules.pauseAfter]); one that fails for its artist alone
 * ([SimilarRules.ownFailure]) only puts that artist off ([SimilarRules.ARTIST_RETRY_DAYS]) and the run goes on, unless
 * [SimilarRules.MISSES_TO_PAUSE] do in a row. [SimilarRules.GONE_TO_STOP] "gone" answers in a row for artists
 * ListenBrainz listed before stop the lookups until the next app [version]. Logs counts and status codes only, never a
 * name or id.
 */
class SimilarLookups(
    private val cache: SimilarCache,
    private val musicBrainz: MusicBrainz,
    private val client: MetaClient,
    private val version: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** A request was sent in the run going on: the next one waits [SimilarRules.SPACING_MS]. One run at a time. */
    private var sent = false

    /** Looks up the [artists] due among those given, the most played first. */
    suspend fun run(artists: List<PlayedArtist>) {
        var start = cache.memory()
        if (start.stoppedIn == version) {
            log("similar: ListenBrainz's endpoint is gone; nothing asked until the next version")
            return
        }
        if (start.stoppedIn != null) start = cache.update { it.copy(stoppedIn = null, gone = 0) }
        if (now() < start.pausedUntil) {
            log("similar: paused after ${start.failures} failures in a row")
            return
        }
        val due = artists.filter { Names.usable(it.name) != null && !Names.isVariousName(it.name) }
            .distinctBy { Names.key(it.name) }
            .filter { SimilarRules.due(start.artists[Names.key(it.name)], now()) }
            .take(SimilarRules.PER_RUN)
        sent = false
        var lists = 0
        var missed = 0
        for (artist in due) {
            val key = Names.key(artist.name)
            val step = lookUp(artist, start.artists[key])
            missed = if (step is Step.Missed) missed + 1 else 0
            when (step) {
                is Step.Listed -> {
                    lists++
                    cache.update { it.copy(artists = it.artists + (key to step.entry), failures = 0, gone = 0) }
                }
                is Step.Unlisted -> cache.update { it.copy(artists = it.artists + (key to step.entry), failures = 0) }
                is Step.Gone -> {
                    log("similar: ListenBrainz answered HTTP ${step.status}")
                    val after = cache.update { memory ->
                        val gone = memory.gone + 1
                        memory.copy(gone = gone, stoppedIn = if (gone >= SimilarRules.GONE_TO_STOP) version else memory.stoppedIn)
                    }
                    if (after.stoppedIn == version) {
                        log("similar: ListenBrainz's endpoint is gone; stopped until the next version")
                        return
                    }
                }
                is Step.Missed -> {
                    val at = now()
                    cache.update { memory ->
                        val entry = memory.artists[key] ?: ArtistSimilar(artist.name, Match.NONE, at = at)
                        memory.copy(artists = memory.artists + (key to entry.copy(failures = entry.failures + 1, at = at)))
                    }
                    log("similar: an artist not looked up (${step.reason}), tried again later")
                    if (missed >= SimilarRules.MISSES_TO_PAUSE) return pause("$missed artists in a row not looked up", lists, due.size)
                }
                is Step.Failed -> return pause(step.reason, lists, due.size)
            }
        }
        log("similar: $lists of ${due.size} artists listed")
    }

    /** Ends a run that failed for [reason]: the lookups pause ([SimilarRules.pauseAfter]). */
    private suspend fun pause(reason: String, lists: Int, of: Int) {
        cache.update { it.copy(failures = it.failures + 1, pausedUntil = now() + SimilarRules.pauseAfter(it.failures + 1)) }
        log("similar: $reason; $lists of $of artists listed, paused")
    }

    /**
     * One artist: its id (the ids KJB wrote into its songs, else [before]'s match, else a search by name), then its
     * neighbours on ListenBrainz.
     */
    private suspend fun lookUp(artist: PlayedArtist, before: ArtistSimilar?): Step {
        val tagged = artist.written?.let { written ->
            when (val answer = paced { musicBrainz.release(written.release) }) {
                is MetaAnswer.Ok -> SimilarRules.artistIdIn(answer.value, written.recording, artist.name)
                MetaAnswer.NotFound -> null
                else -> return failed(answer)
            }
        }
        val id = tagged
            ?: before?.mbid?.takeIf { before.match == Match.FOUND }
            ?: when (val answer = paced { musicBrainz.searchArtists(artist.name) }) {
                is MetaAnswer.Ok -> {
                    val (match, found) = SimilarRules.match(answer.value, artist.name)
                    found ?: return Step.Unlisted(ArtistSimilar(artist.name, match, at = now()))
                }
                else -> return failed(answer)
            }
        val answer = paced {
            client.get(ListenBrainzUrls.similarArtists(id), Body.JSON, fresh = true, keep = { false }, read = ListenBrainzJson::neighbours)
        }
        val gone = when (answer) {
            MetaAnswer.NotFound -> HttpURLConnection.HTTP_NOT_FOUND
            is MetaAnswer.Failed -> answer.status?.takeIf { it in SimilarRules.GONE }
            else -> null
        }
        return when {
            answer is MetaAnswer.Ok -> Step.Listed(ArtistSimilar(artist.name, Match.FOUND, id, answer.value.take(SimilarRules.KEEP), now()))
            gone == null -> failed(answer)
            before?.neighbours.isNullOrEmpty() -> Step.Unlisted(ArtistSimilar(artist.name, Match.FOUND, id, at = now()))
            else -> Step.Gone(gone)
        }
    }

    /** [request], [SimilarRules.SPACING_MS] after the run's previous one. */
    private suspend fun <T> paced(request: suspend () -> T): T {
        if (sent) delay(SimilarRules.SPACING_MS)
        sent = true
        return request()
    }

    /** [answer]'s failure: its artist's own ([SimilarRules.ownFailure]), else the run's. */
    private fun failed(answer: MetaAnswer<*>): Step = if (SimilarRules.ownFailure(answer)) Step.Missed(reason(answer)) else Step.Failed(reason(answer))

    /** For the log: a status code or an exception class name, never an address. */
    private fun reason(answer: MetaAnswer<*>): String = when (answer) {
        MetaAnswer.Busy -> "busy (HTTP 503)"
        is MetaAnswer.Failed -> answer.reason
        MetaAnswer.NotFound -> "HTTP 404"
        is MetaAnswer.Ok -> "answered"
    }

    private sealed interface Step {
        /** Found, with its neighbours (maybe none). */
        data class Listed(val entry: ArtistSimilar) : Step

        /**
         * Nothing to list, kept so: no artist or none for sure (ListenBrainz not asked), or a "gone" status for an artist
         * ListenBrainz never listed. The count of "gone" answers stays as it is.
         */
        data class Unlisted(val entry: ArtistSimilar) : Step

        /** For an artist it listed before, ListenBrainz answered a status that says its endpoint is gone ([SimilarRules.GONE]). */
        data class Gone(val status: Int) : Step

        /** Failed for this artist alone ([SimilarRules.ownFailure]): it is put off, the run goes on. */
        data class Missed(val reason: String) : Step

        /** Busy, offline or refused: the run ends and the lookups pause. */
        data class Failed(val reason: String) : Step
    }
}
