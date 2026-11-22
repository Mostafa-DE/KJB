package app.lyrify.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.media3.session.MediaLibraryService
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.huddle.HuddleGraph
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.PlaybackService
import app.lyrify.player.PlayerGraph
import app.lyrify.player.Queue
import app.lyrify.player.Repeat
import app.lyrify.player.ServicePart
import app.lyrify.player.ShuffleStep
import app.lyrify.player.SourceKind
import app.lyrify.player.Suggested
import app.lyrify.player.partScope
import app.lyrify.settings.HeldSong
import app.lyrify.settings.SettingsStore
import app.lyrify.settings.SuggestionSettings
import app.lyrify.similar.SimilarGraph
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayLogStore
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.dayOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import kotlin.random.Random

// Suggestions for the whole app: the taste kept up to date from the play log (only the new plays read), the songs the
// user held out (Not now, Never), today's For you, Autoplay's and Smart shuffle's songs for the queue playing, More like
// this, Why this? and Suggestions health. The heavy work runs on its own dispatcher, never the main thread.

/** Not now holds a song out of suggestions this long (user, 2026-11-22). */
const val NOT_NOW_MS: Long = 30 * 86_400_000L

/** [song] held out of suggestions until [now] plus [NOT_NOW_MS]; a Not now that ran out leaves the list. */
fun SuggestionSettings.notNow(song: SongRef, now: Long): SuggestionSettings =
    copy(notNow = notNow.filter { it.at > now && it.song.path != song.path } + HeldSong(song, now + NOT_NOW_MS))

/** [song] never suggested from [now] on, till it is allowed again; it leaves Not now. */
fun SuggestionSettings.never(song: SongRef, now: Long): SuggestionSettings =
    copy(never = never.filter { it.song.path != song.path } + HeldSong(song, now), notNow = notNow.filter { it.song.path != song.path })

/** [song] back in suggestions, from Never or Not now. */
fun SuggestionSettings.allow(song: SongRef): SuggestionSettings =
    copy(never = never.filter { it.song.path != song.path }, notNow = notNow.filter { it.song.path != song.path })

/** The paths in [library] of the songs held out at [now]: Never, and Not now till it runs out; a moved song found by its id. */
fun SuggestionSettings.heldPaths(now: Long, library: Library): Set<String> =
    (never + notNow.filter { it.at > now }).mapNotNullTo(HashSet()) { library.resolver.find(it.song)?.path }

/** Songs suggestions put into queue [queueId], each after the item with the uid it names, marked [by]. */
data class Feed(val queueId: String, val by: Suggested, val songs: List<Pair<Long, SongRef>>)

/** Why this? for a song: its reasons and those against it, the mix right now, and the settings it starts from. */
data class WhyNow(val why: Why, val mix: Mix)

/** Suggestions health and when it was worked out (wall-clock millis). */
data class HealthNow(val health: Health, val at: Long)

/**
 * Suggestions over [log], [library] (null while it is read), the favourite songs with the date each became one
 * ([favourites]; 0 unknown) and the user's [settings]; today's For you kept in [forYouFile] (none in tests: memory only).
 * The other sources ([interactions], [similar], [friends]) add small boosts and give nothing when missing or failing.
 *
 * The plays are read whole once a day (a restore or an import may add old ones), and otherwise only from a while before
 * the latest one, so a play still going, written again as it closes, is read as it is now. The taste is built again
 * when the plays, the library or Favourites changed, at most every minute, and every few minutes as recency fades.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class Suggestions(
    private val log: PlayLogStore,
    private val library: () -> Library?,
    private val favourites: suspend () -> Map<SongRef, Long>,
    private val settings: SettingsStore,
    private val forYouFile: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
    private val interactions: InteractionSource = NoInteractions,
    private val similar: SimilarArtistSource = NoSimilarArtists,
    private val friends: FriendsSource = NoFriends,
    private val warn: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private var plays: List<LoggedPlay> = emptyList()
    private var wholeAt: Long? = null
    private var playsChanged = false
    private var built: Taste? = null
    private var builtFrom: Pair<Library, Map<String, Long>>? = null
    private var today: TodayMix? = null
    private var checked: HealthNow? = null

    /** The taste now; null while the library is read or the play log was never read. */
    suspend fun taste(): Taste? = withContext(compute) { lock.withLock { current()?.second } }

    /** What the user held out of suggestions, as the settings have it now. */
    private suspend fun held(): SuggestionSettings = settings.settings.first().suggestions

    /**
     * The library and the taste now, the taste built again when it is due (see the class), or whenever the plays changed
     * when it must hold [exactly] the plays read (Suggestions health replays the plays against it).
     */
    private suspend fun current(exactly: Boolean = false): Pair<Library, Taste>? {
        val library = library() ?: return null
        val now = clock()
        if (!read(now)) return null
        val liked = favourites().mapNotNull { (ref, at) -> library.resolver.find(ref)?.let { it.path to at } }.toMap()
        val old = built
        val same = builtFrom?.let { it.first === library && it.second == liked } == true
        if (old != null && same && now - old.now < STALE_MS && (!playsChanged || (!exactly && now - old.now < REBUILD_MS))) return library to old
        val taste = Taste.of(
            plays, library, liked, now, zone(),
            interactions = quietly { interactions.since(now - INTEREST_MS) }.orEmpty(),
            friendPlays = quietly { friends.since(now - FRIENDS_MS) }.orEmpty(),
            similar = quietly { similar.similar(library.artists.map { it.name }) }.orEmpty(),
        )
        built = taste
        builtFrom = library to liked
        playsChanged = false
        return library to taste
    }

    /** The play log into [plays] ([Suggestions]); false while it was never read. */
    private suspend fun read(now: Long): Boolean {
        if (wholeAt?.let { now - it < WHOLE_EVERY_MS } != true) {
            val all = quietly { log.all() } ?: return wholeAt != null
            if (all != plays) playsChanged = true
            plays = all
            wholeAt = now
            return true
        }
        val from = (plays.lastOrNull()?.startedAt ?: now) - REREAD_MS
        val fresh = quietly { log.since(from) } ?: return true
        val kept = plays.indexOfFirst { it.startedAt >= from }.let { if (it < 0) plays.size else it }
        if (plays.subList(kept, plays.size) != fresh) {
            plays = plays.subList(0, kept) + fresh
            playsChanged = true
        }
        return true
    }

    /** [read]'s answer, or null when it failed: a source that fails gives nothing, and suggestions go on. */
    private suspend fun <T> quietly(read: suspend () -> T?): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        warn("suggestions: a source not read (${e.javaClass.simpleName})")
        null
    }

    /**
     * Today's For you: worked out once a day (the phone's day) and kept, so it stays the same all day whatever is played;
     * songs held out since leave it at once. Each song with its reason as the taste gives it now.
     */
    suspend fun forYou(): List<Pick> = withContext(compute) {
        lock.withLock {
            val (library, taste) = current() ?: return@withLock emptyList()
            val zone = zone()
            val now = clock()
            val settings = held()
            val held = settings.heldPaths(now, library)
            val day = dayOf(now, zone).date.toString()
            val mix = today?.takeIf { it.day == day } ?: loadMix()?.takeIf { it.day == day } ?: run {
                val picks = forYou(taste, library, mixOf(settings.adventure, taste.outcomes), taste.recentPaths + held, random, zone)
                TodayMix(day, picks.map { StoredPick(it.song.ref.path, it.song.ref.mediaStoreId, it.bucket) }).also(::saveMix)
            }
            today = mix
            val songs = mix.songs.mapNotNull { stored ->
                library.resolver.find(SongRef(stored.path, stored.id))?.takeIf { music(library, it) && it.path !in held }?.let { it to stored.bucket }
            }
            songs.mapIndexed { i, (song, bucket) -> Pick(song, bucket, reasonFor(song, bucket, taste, library, zone, songs.getOrNull(i - 1)?.first)) }
        }
    }

    private fun loadMix(): TodayMix? {
        val file = forYouFile?.takeIf { it.isFile } ?: return null
        return try {
            json.decodeFromString(TodayMix.serializer(), file.readText())
        } catch (e: IOException) {
            warn("suggestions: For you not read (${e.javaClass.simpleName})")
            null
        } catch (e: SerializationException) {
            warn("suggestions: For you unreadable (${e.javaClass.simpleName})")
            null
        }
    }

    /** Written aside, then moved over the file; a failure keeps the mix in memory for this run. */
    private fun saveMix(mix: TodayMix) {
        val file = forYouFile ?: return
        val aside = File(file.path + TMP)
        try {
            file.parentFile?.mkdirs()
            aside.writeText(json.encodeToString(TodayMix.serializer(), mix))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            aside.delete()
            warn("suggestions: For you not written (${e.javaClass.simpleName})")
        }
    }

    /** "Never suggest this"; false when the settings could not be written. */
    suspend fun never(song: SongRef): Boolean = settings.update { it.copy(suggestions = it.suggestions.never(song, clock())) }

    /** "Not now": out of suggestions for 30 days. */
    suspend fun notNow(song: SongRef): Boolean = settings.update { it.copy(suggestions = it.suggestions.notNow(song, clock())) }

    /** "Allow again", and Undo after Never or Not now. */
    suspend fun allow(song: SongRef): Boolean = settings.update { it.copy(suggestions = it.suggestions.allow(song)) }

    /** Autoplay's songs to play after [queue], none of [inQueue] (the queue's own), none played lately, none held out. */
    suspend fun autoplay(queue: List<Song>, inQueue: Set<String>): List<Pick> = withContext(compute) {
        lock.withLock {
            val (library, taste) = current() ?: return@withLock emptyList()
            val settings = held()
            val avoid = inQueue + taste.recentPaths + settings.heldPaths(clock(), library)
            autoplay(taste, library, mixOf(settings.adventure, taste.outcomes), queue, avoid, random, zone())
        }
    }

    /** More like this for [seed] from [bases], without the songs held out. */
    suspend fun moreLike(seed: Seed, bases: Set<Basis>): MoreLike = withContext(compute) {
        lock.withLock {
            val (library, taste) = current() ?: return@withLock MoreLike(emptyList(), emptyList(), null)
            moreLike(taste, library, seed, bases, held().heldPaths(clock(), library), zone())
        }
    }

    /**
     * What suggestions add to [queue] now: under Smart shuffle, suggestions among its songs to come; with Autoplay on
     * ([autoplay]) and Repeat off, once its last song plays, Autoplay's songs after it. A book's queue gets none. Off the
     * main thread, even for a queue of thousands of songs.
     */
    suspend fun feed(queue: Queue, repeat: Repeat, autoplay: Boolean): List<Feed> = withContext(compute) feed@{
        if (queue.source.kind == SourceKind.BOOK || queue.items.isEmpty()) return@feed emptyList()
        val library = library() ?: return@feed emptyList()
        val inQueue = queue.items.mapTo(HashSet()) { it.song.path }
        val out = ArrayList<Feed>()
        if (queue.shuffleStep == ShuffleStep.SMART) {
            val playing = queue.current?.let { library.resolver.find(it.song) }
            val upcoming = queue.items.drop(queue.index + 1).mapNotNull { item -> library.resolver.find(item.song)?.let { item to it } }
            val started = queue.source.key.takeIf { queue.source.kind == SourceKind.ALBUM }
            val inserts = smart(playing, upcoming.map { (item, song) -> Upcoming(song, item.suggested != null) }, inQueue, started)
            val after = { at: Int -> if (at < 0) queue.current?.uid else upcoming.getOrNull(at)?.first?.uid }
            val songs = inserts.mapNotNull { insert -> after(insert.after)?.let { it to insert.pick.song.ref } }
            if (songs.isNotEmpty()) out += Feed(queue.id, Suggested.SMART_SHUFFLE, songs)
        }
        if (autoplay && repeat == Repeat.OFF && queue.index == queue.items.lastIndex) {
            val last = queue.items.last().uid
            val picks = autoplay(queue.items.mapNotNull { library.resolver.find(it.song) }, inQueue)
            if (picks.isNotEmpty()) out += Feed(queue.id, Suggested.AUTOPLAY, picks.map { last to it.song.ref })
        }
        out
    }

    private suspend fun smart(playing: Song?, upcoming: List<Upcoming>, inQueue: Set<String>, startedAlbum: String?): List<Insert> = withContext(compute) {
        lock.withLock {
            val (library, taste) = current() ?: return@withLock emptyList()
            val settings = held()
            val avoid = inQueue + taste.recentPaths + settings.heldPaths(clock(), library)
            smartInserts(taste, library, mixOf(settings.adventure, taste.outcomes), playing, upcoming, avoid, random, zone(), startedAlbum)
        }
    }

    /** Each of [songs]' top reason, each right after the song given with it (none for the first of a list). */
    suspend fun reasons(songs: List<Pair<Song, Song?>>): List<Reason> = withContext(compute) {
        lock.withLock {
            val (library, taste) = current() ?: return@withLock emptyList()
            val zone = zone()
            songs.map { (song, after) -> reasonFor(song, bucketOf(taste, song.path), taste, library, zone, after) }
        }
    }

    /** Why this? for [song], right [after] a song when it came after one; null while the library is read. */
    suspend fun why(song: Song, after: Song?): WhyNow? = withContext(compute) {
        lock.withLock {
            val (library, taste) = current() ?: return@withLock null
            WhyNow(why(song, taste, library, zone(), after), mixOf(held().adventure, taste.outcomes))
        }
    }

    /** The mix right now, for Why this? and Settings. */
    suspend fun mix(): Mix = withContext(compute) {
        lock.withLock { current()?.second?.let { mixOf(held().adventure, it.outcomes) } ?: startOf(held().adventure) }
    }

    /** Suggestions health, worked out again when [again] or never yet this run; null while the library is read. */
    suspend fun health(again: Boolean = false): HealthNow? = withContext(compute) {
        lock.withLock {
            checked?.takeUnless { again }?.let { return@withLock it }
            val (library, taste) = current(exactly = true) ?: return@withLock null
            HealthNow(health(plays, taste, library), clock()).also { checked = it }
        }
    }

    /** Today's For you as kept: the day it is for, and its songs by path with their id hint and bucket. */
    @Serializable
    private data class TodayMix(val day: String, val songs: List<StoredPick>)

    @Serializable
    private data class StoredPick(val path: String, val id: Long? = null, val bucket: Bucket)

    private companion object {
        /** The play log is read whole this often; in between only from [REREAD_MS] before the latest play. */
        const val WHOLE_EVERY_MS: Long = 24 * 3_600_000L
        const val REREAD_MS: Long = 6 * 3_600_000L

        /** The taste is built again at most this often for new plays, and at least this often as recency fades. */
        const val REBUILD_MS: Long = 60_000
        const val STALE_MS: Long = 10 * 60_000L

        const val TMP: String = ".tmp"

        val json: Json = Json { ignoreUnknownKeys = true }
    }
}

/** Suggestions' objects, built by hand, one per process. */
object SuggestionsGraph {
    // Holds the application's context only, as every graph object does.
    @SuppressLint("StaticFieldLeak")
    private var suggestions: Suggestions? = null

    /** Suggestions over `plays.db`, the library, Favourites and the settings; today's For you in app-private `files/for-you.json`. */
    @Synchronized
    fun suggestions(context: Context): Suggestions = suggestions ?: run {
        val app = context.applicationContext
        val library = LibraryGraph.repository(app).state
        val playlists = LibraryGraph.playlists(app)
        Suggestions(
            log = StatsGraph.log(app),
            library = { (library.value as? LibraryState.Ready)?.library },
            favourites = { playlists.state.first().let { state -> state.favouriteSongs.associateWith { state.favouriteAdded[it.path] ?: 0L } } },
            settings = Lyrify.settings(app),
            forYouFile = File(app.filesDir, "for-you.json"),
            interactions = StatsGraph.interactions(app),
            similar = SimilarGraph.cache(app),
            friends = HuddleGraph.friends(app),
            warn = { Log.w(LOG_TAG, it) },
        )
    }.also { suggestions = it }
}

/**
 * Suggestions in the playback service: Autoplay's songs after the queue's end and Smart shuffle's among its songs, put in
 * as the queue playing, its song, its length, Shuffle, Repeat or Autoplay change; Autoplay's songs still to come leave
 * when Autoplay is turned off or Repeat on.
 */
class SuggestionsPart : ServicePart {
    private var scope: CoroutineScope? = null

    private data class Moment(val queue: String?, val uid: Long?, val size: Int, val step: ShuffleStep?, val repeat: Repeat, val autoplay: Boolean)

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val scope = partScope().also { scope = it }
        val core = PlayerGraph.core(service)
        val suggestions = SuggestionsGraph.suggestions(service)
        val autoplay = Lyrify.settings(service).settings.map { it.suggestions.autoplay }.distinctUntilChanged()
        scope.launch {
            combine(core.book, core.repeat, autoplay) { book, repeat, on -> Triple(book.current, repeat, on) }
                .distinctUntilChangedBy { (queue, repeat, on) -> Moment(queue?.id, queue?.current?.uid, queue?.items?.size ?: 0, queue?.shuffleStep, repeat, on) }
                .collectLatest { (queue, repeat, on) ->
                    if (queue == null) return@collectLatest
                    val waiting = queue.items.drop(queue.index + 1).any { it.suggested == Suggested.AUTOPLAY }
                    if (waiting && (!on || repeat != Repeat.OFF)) return@collectLatest core.change { it.unsuggest(queue.id, Suggested.AUTOPLAY) }
                    val feeds = suggestions.feed(queue, repeat, on)
                    if (feeds.isNotEmpty()) core.change { book -> feeds.fold(book) { fed, feed -> fed.suggest(feed.queueId, feed.songs, feed.by) } }
                }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
    }
}
