package app.lyrify.stats

import app.lyrify.audio.OutputKind
import app.lyrify.library.Album
import app.lyrify.library.Library
import app.lyrify.library.Playlist
import app.lyrify.library.Song
import app.lyrify.library.albumKey
import app.lyrify.library.search.fold
import app.lyrify.lyrics.TrackNames
import app.lyrify.player.SourceKind
import app.lyrify.settings.ListPeriod
import app.lyrify.suggestions.friendSong
import app.lyrify.suggestions.nameKey
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

// The figures behind each card of the Insights page (stats/InsightsPage.kt): one function per card, and per moment, as
// in1's Insights.kt has one per card. Pure, so the rules are tested on the JVM (InsightsPageTest).

/** Covers on a story's ring. */
private const val RECAP_COVERS = 4

/** Every day you listened: plays that counted on this many days. */
private const val MIN_DAYS = 2

/** The top artist's run: number 1 this many weeks or months. */
private const val MIN_LOYALTY = 2

/** The top song's busiest day: this many plays that counted in it. */
private const val MIN_DAY_PLAYS = 2

private const val DECADE = 10

/** How you listen: this many plays that keep how they began; a skip in the first 30 s is early; a song skipped early this often is named. */
private const val MIN_HOW_PLAYS = 10
private const val EARLY_SKIP_MS: Long = 30_000
private const val MIN_EARLY_SKIPS = 3

/** Moments: a song this many times in a day; a session from this long, broken by this long a gap; night from 9 pm to 6 am. */
private const val MIN_REPEAT = 3
private const val MIN_SESSION_MS: Long = 30 * 60_000
private const val SESSION_GAP_MS: Long = 5 * 60_000
private const val NIGHT_FROM = 21
private const val NIGHT_UNTIL = 6
private const val MINUTES_IN_HOUR = 60

/** Moments: an album played through has this many songs; this many time zones; this much time saved. */
private const val ALBUM_MIN_SONGS = 3
private const val MIN_ZONES = 2
private const val MIN_SAVED_MS: Long = 60_000

/** New to you: an artist played on this many days is kept. Back again: after this many quiet days, this many shown. */
private const val KEPT_DAYS = 3
private const val QUIET_DAYS = 60
private const val COMEBACKS_SHOWN = 3

/** The clock's parts of the day: morning from 6, afternoon from noon, evening from 6 pm, night from 10 pm; late to 4 am. */
private const val MORNING_FROM = 6
private const val NOON = 12
private const val EVENING_FROM = 18
private const val NIGHT_PART_FROM = 22
private const val NIGHT_OWL_UNTIL = 4

/** Your listening type: three words, each past its bar; the night owl's time is the clock's late hours. */
private const val TRAITS_SHOWN = 3
private const val NIGHT_OWL_BAR = 30
private const val SHUFFLER_BAR = 20
private const val ALBUM_LOVER_BAR = 25
private const val EXPLORER_BAR = 15
private const val LOYAL_BAR = 10
private const val PERCENT = 100

/** Your searches: a play this soon after a search followed it; words are split at spaces. */
private const val PLAYED_AFTER_MS: Long = 60_000
private val WHITESPACE = Regex("\\s+")

/** You and your friends: a friend's plays in the period to match tastes; a song sent you played this often is kept; in sync within an hour. */
private const val MIN_FRIEND_PLAYS = 10
private const val KEPT_PLAYS = 2
private const val IN_SYNC_MS: Long = 3_600_000
private const val BOTH_SHOWN = 3

/** Milestones: the next one's month is given up to this many days ahead. */
private const val MAX_AROUND_DAYS = 3_650.0
private const val DAY_MS: Long = 86_400_000

/**
 * One page's working out, each part once for the cards that share it: [heard], the play log's plays in the period (an
 * import's and the short ones left out), and [books], the book log's.
 */
internal class PageWork(
    private val data: InsightsData,
    private val sources: PageSources,
    private val period: PagePeriod,
    private val now: Long,
    private val calendar: PhoneCalendar,
    private val library: Library?,
) {
    private val zone = calendar.zone
    private val kinds = Kinds(library)
    private val heard = data.plays.within(period.bounds)
    private val books = data.books.within(period.bounds)
    private val ledger = ledgerOf(heard, books, kinds)
    private val music by lazy { heard.filter { kinds.of(it) == Kind.MUSIC } }
    private val tops by lazy { listeningOf(songPlaysOf(music), library) }

    /** [play]'s day where it was heard. */
    private fun localDay(play: LoggedPlay): LocalDate = localTimeOf(play, zone).toLocalDate()

    fun cards(): InsightsCards {
        val shows = ledger.heardMs >= MIN_HEARD_MS || ledger.plays > 0
        val time = timeOf().takeIf { shows }
        if (library == null) return InsightsCards(time = time)
        val found = foundOf(library)
        val clock = clockOf()
        // The most played song of each artist and each album in the period: the covers of their rows.
        val artistCovers = tops.songs.flatMap { top -> library.artistsOf(top.item).map { it.name to top.item } }.distinctBy { it.first }.toMap()
        val albumCovers = tops.songs.mapNotNull { top -> albumKey(top.item)?.let { it to top.item } }.distinctBy { it.first }.toMap()
        return InsightsCards(
            recaps = if (period.going) recapsOf(library) else emptyList(),
            monthSoFar = soFarOf(ListeningPeriod.MONTH),
            yearSoFar = soFarOf(ListeningPeriod.YEAR),
            time = time,
            days = daysOf(),
            clock = clock,
            artists = topCardOf(tops.artists, MIN_TOP_OTHERS, placesBefore?.artists, { it.name }) { artistCovers[it.name] }
                ?.let { card -> card.copy(loyalty = loyaltyOf(card.rows.first().top.item.name, library)) },
            songs = topCardOf(tops.songs, MIN_TOP_SONGS, placesBefore?.songs, { it.path }) { it }
                ?.let { card ->
                    val first = card.rows.first().top.item
                    card.copy(firstsDay = mostInADay(musicSongs.filter { it.first.counted && it.second == first })?.takeIf { it.plays >= MIN_DAY_PLAYS })
                },
            albums = topCardOf(tops.albums, MIN_TOP_OTHERS, placesBefore?.albums, { it.key }) { albumCovers[it.key] },
            taste = tasteOf(library),
            how = howOf(),
            moments = momentsOf(library),
            found = found,
            comebacks = comebacksOf(),
            forgotten = if (period.going) forgottenOf(data, now, library, kinds) else emptyList(),
            friends = friendsOf(library),
            lyrics = lyricsOf(),
            favourites = favouritesOf(library),
            searches = searchesOf(),
            outputs = outputsOf(),
            playlists = playlistsOf(),
            spoken = spokenOf(library),
            library = if (period.going) libraryOf(library) else null,
            traits = traitsOf(found, clock),
            milestones = milestonesCardOf(library),
        )
    }

    /** Read along: from one time you turned to lyrics in the period. */
    private fun lyricsOf(): LyricsCard? {
        val opened = sources.interactions.filter { it.kind == InteractionKind.LYRICS }
        if (opened.isEmpty()) return null
        val most = opened.mapNotNull { it.target?.takeIf { target -> target.kind == TargetKind.SONG }?.key }.groupingBy { it }.eachCount()
            .mapNotNull { (path, times) -> library?.song(path)?.let { it to times } }
            .maxWithOrNull(compareBy<Pair<Song, Int>> { it.second }.thenBy { it.first.path })
        val counted = musicSongs.filter { it.first.counted }
        return LyricsCard(opened.size, most?.first, most?.second ?: 0, counted.count { it.second.path in sources.synced }, counted.size)
    }

    /** New favourites: from one added in the period (all time: one favourite), the one of them played most in it. */
    private fun favouritesOf(library: Library): FavouritesCard? {
        val favourites = data.favourites.mapNotNullTo(LinkedHashSet()) { library.resolver.find(it)?.path }
        val added = if (period.bounds == null) favourites else favourites.filterTo(HashSet()) { path -> sources.favouriteAdded[path]?.let { it in period.bounds } == true }
        if (added.isEmpty()) return null
        return FavouritesCard(added.size, tops.songs.firstOrNull { it.item.path in added }?.item)
    }

    /**
     * Your searches, from one in the period: those that ended in a tap, those a play followed within [PLAYED_AFTER_MS],
     * the words (folded, each once a search, words forgotten gone), and what the taps opened.
     */
    private fun searchesOf(): SearchesCard? {
        val searches = sources.interactions.filter { it.kind == InteractionKind.SEARCH }
        if (searches.isEmpty()) return null
        // The play log is the oldest first: the first play from each search's time on.
        val starts = heard.map { it.startedAt }
        val words = searches.flatMap { search -> search.words?.let(::fold)?.split(WHITESPACE)?.filter(String::isNotEmpty)?.distinct().orEmpty() }
            .groupingBy { it }.eachCount()
            .map { (word, times) -> WordCount(word, times) }
            .sortedWith(compareByDescending<WordCount> { it.times }.thenBy { it.word })
        val targets = searches.mapNotNull { it.target?.kind }
        return SearchesCard(
            searches = searches.size,
            tapped = targets.size,
            played = searches.count { search -> starts.getOrNull(starts.binarySearch(search.at).let { if (it < 0) -it - 1 else it })?.let { it - search.at <= PLAYED_AFTER_MS } == true },
            words = words,
            songs = targets.count { it == TargetKind.SONG },
            artists = targets.count { it == TargetKind.ARTIST },
            albums = targets.count { it == TargetKind.ALBUM },
            others = targets.count { it == TargetKind.FOLDER || it == TargetKind.PLAYLIST },
        )
    }

    /**
     * Where you listen: the plays that counted (never a book's) by the kind of output they were heard on, from
     * [MIN_TOP_PLAYS] plays that keep it (KJB v4 on: the plays from before have none); wired and USB are headphones, any
     * other kind (a dock, a screen) none of the four.
     */
    private fun outputsOf(): OutputsCard? {
        val outputs = heard.filter { it.counted && kinds.of(it) != Kind.BOOK }.mapNotNull { it.start?.output }.groupingBy { it }.eachCount()
        return OutputsCard(outputs[OutputKind.SPEAKER] ?: 0, (outputs[OutputKind.WIRED] ?: 0) + (outputs[OutputKind.USB] ?: 0), outputs[OutputKind.BLUETOOTH] ?: 0, outputs[OutputKind.CAR] ?: 0)
            .takeIf { it.speaker + it.headphones + it.bluetooth + it.car >= MIN_TOP_PLAYS }
    }

    /**
     * Top playlists: the music that counted by the playlist it came from (KJB v4 on), each playlist the user has now,
     * the most played first; from [MIN_TOP_PLAYS] such plays of [MIN_TOP_OTHERS] playlists, as the top lists show.
     */
    private fun playlistsOf(): PlaylistsCard? {
        val byId = sources.playlists.associateBy { it.id }
        val rows = music.filter { it.counted }.mapNotNull { play -> play.start?.playlistId?.let(byId::get)?.let { it to play } }
            .groupBy({ it.first }, { it.second })
            .map { (playlist, plays) -> Top(playlist, plays.size, plays.sumOf { it.realMs }, plays.maxOf { it.startedAt }) }
            .sortedWith(topOrder<Playlist>().thenBy { it.item.name }.thenBy { it.item.id })
        return PlaylistsCard(rows).takeIf { rows.size >= MIN_TOP_OTHERS && rows.sumOf { it.plays } >= MIN_TOP_PLAYS }
    }

    /**
     * Books and podcasts, from a minute of either (in1's [spokenOf]): real minutes, the time faster speeds saved, bookmarks
     * and books finished, and the book and the episode heard most.
     */
    private fun spokenOf(library: Library): SpokenCard? {
        val spoken = spokenOf(heard, books, kinds) ?: return null
        val bookPlays = books + heard.filter { kinds.of(it) == Kind.BOOK }
        val podcasts = heard.filter { kinds.of(it) == Kind.PODCAST }
        return SpokenCard(
            booksMs = spoken.booksMs,
            podcastsMs = spoken.podcastsMs,
            savedMs = savedMs(bookPlays + podcasts),
            bookmarks = sources.bookmarks.count { period.bounds == null || it in period.bounds },
            finished = sources.finishedBooks.count { period.bounds == null || it in period.bounds },
            book = bookPlays.groupBy { play -> library.bookOf(songOf(play)?.path ?: play.song.path) }.filterKeys { it != null }
                .maxByOrNull { (_, its) -> its.sumOf { it.realMs } }?.key,
            podcast = podcasts.groupBy(::songOf).filterKeys { it != null }.maxByOrNull { (_, its) -> its.sumOf { it.realMs } }?.key,
        )
    }

    /**
     * You and your friends (past friends too): the taste match with each friend with [MIN_FRIEND_PLAYS] plays in the
     * period (the share of plays you both gave the same artists), the cards both ways, the songs sent you that you went
     * on playing ([KEPT_PLAYS] times), the times you both played a song within [IN_SYNC_MS], and the song sent you that you
     * played most since. Friends' songs are found in the library's music as Suggestions find them ([friendSong]); a card
     * or a play of a friend no longer listed (a restore writes cards before friends, for a moment) is left out.
     */
    private fun friendsOf(library: Library): FriendsCard? {
        if (sources.friendPlays.isEmpty() && sources.shared.isEmpty()) return null
        val names = sources.friends.associateBy { it.id }
        val until = period.bounds?.until ?: Long.MAX_VALUE
        val theirs = sources.friendPlays.filter { it.at < until && it.friend in names }
        val cardSongs = sources.shared.filter { it.friend in names }
        val byName = library.songs.filter { !it.isPodcast }.groupBy { nameKey(it.title, it.artist) }
        // Each side's plays by artist, as a share of all the artists they played.
        fun shares(artists: List<String>): Map<String, Double> = artists.groupingBy { TrackNames.normalize(it) }.eachCount().let { counts ->
            val all = counts.values.sum().toDouble()
            counts.mapValues { it.value / all }
        }
        val counted = musicSongs.filter { it.first.counted }
        val mine = shares(counted.flatMap { (_, song) -> library.artistsOf(song).map { it.name } })
        val matches = theirs.groupBy { it.friend }.filterValues { it.size >= MIN_FRIEND_PLAYS && enough }.map { (friend, plays) ->
            val shares = shares(plays.flatMap { library.artistNames(it.artist) })
            friend to shares.entries.associate { (artist, share) -> artist to minOf(share, mine[artist] ?: 0.0) }
        }
        val closest = matches.maxByOrNull { it.second.values.sum() }
        val artists = library.artists.associateBy { TrackNames.normalize(it.name) }
        val inPeriod = cardSongs.filter { period.bounds == null || it.at in period.bounds }
        val got = inPeriod.filter { !it.sent }.groupBy { it.card }
        // A song sent to you, and your plays of it after it came (up to the period's end).
        val sentToYou = cardSongs.filter { !it.sent && it.at < until }.mapNotNull { shared -> friendSong(byName, shared.title, shared.artist, shared.durationMs)?.let { shared to it } }
        val sentSongs = sentToYou.mapTo(HashSet()) { it.second }
        val playedAt = data.plays.filter { it.counted }.mapNotNull { play -> songOf(play)?.takeIf { it in sentSongs }?.let { it to play.startedAt } }.groupBy({ it.first }, { it.second })
        fun playsAfter(song: Song, at: Long): Int = playedAt[song].orEmpty().count { it in at until until }
        val keptCards = got.count { (_, songs) -> songs.any { shared -> friendSong(byName, shared.title, shared.artist, shared.durationMs)?.let { playsAfter(it, shared.at) >= KEPT_PLAYS } == true } }
        val favourite = sentToYou.map { (shared, song) -> Triple(shared, song, playsAfter(song, shared.at)) }
            .filter { it.third >= KEPT_PLAYS }
            .maxWithOrNull(compareBy<Triple<SharedSong, Song, Int>> { it.third }.thenBy { it.first.at })
        // Friends' plays by the song of yours they are, each found once, the oldest first: in sync with one of yours is the
        // latest of them up to an hour after it (found by halves: the first after that hour, then the one before), from an
        // hour before it.
        val theirSongs = theirs.sortedBy { it.at }.mapNotNull { their -> friendSong(byName, their.title, their.artist, their.durationMs)?.let { it to their } }
            .groupBy({ it.first }, { it.second })
        val inSync = counted.mapNotNull { (play, song) ->
            val plays = theirSongs[song] ?: return@mapNotNull null
            val after = -plays.binarySearch { if (it.at <= play.startedAt + IN_SYNC_MS) -1 else 1 } - 1
            plays.getOrNull(after - 1)?.takeIf { it.at >= play.startedAt - IN_SYNC_MS }?.let { play to it }
        }
        return FriendsCard(
            matches = matches.map { (friend, both) -> checkNotNull(names[friend]).let { FriendMatch(it.id, it.name, it.past, (both.values.sum() * PERCENT).roundToInt()) } }
                .sortedWith(compareByDescending<FriendMatch> { it.percent }.thenBy { it.name }),
            both = closest?.second.orEmpty().entries.filter { it.value > 0 }.sortedByDescending { it.value }.mapNotNull { artists[it.key] }.take(BOTH_SHOWN),
            sent = inPeriod.filter { it.sent }.distinctBy { it.card }.size,
            got = got.size,
            kept = keptCards,
            inSync = inSync.maxByOrNull { it.first.startedAt }?.let { (play, their) ->
                val friend = checkNotNull(names[their.friend])
                InSync(friend.id, friend.name, checkNotNull(songOf(play)), inSync.size)
            },
            favourite = favourite?.let { (shared, song, plays) -> checkNotNull(names[shared.friend]).let { SentKept(it.id, it.name, song, plays) } },
        ).takeIf { it.matches.isNotEmpty() || it.sent + it.got > 0 }
    }

    /**
     * Your library, now (on the period pages only): its music, ever played here or imported, added in the period (all
     * time: the last 30 days, as the Songs tab counts them), by file type, never played and dig deeper (in1's nudges).
     */
    private fun libraryOf(library: Library): LibraryCard? {
        val songs = library.songs.filter { !it.isPodcast }.takeIf { it.isNotEmpty() } ?: return null
        val ever = data.everPlayed.filter { it.count > 0 }
        val played = ever.mapNotNullTo(HashSet()) { library.resolver.find(it.song)?.path }
        val from = period.bounds?.from ?: checkNotNull(sinceOf(ListPeriod.DAYS_30, now, zone))
        val added = songs.filter { song -> song.addedAt?.let { it >= from && (period.bounds == null || it < period.bounds.until) } == true }
        return LibraryCard(
            songs = songs.size,
            bytes = songs.sumOf { it.sizeBytes.coerceAtLeast(0) },
            durationMs = songs.sumOf { it.durationMs.coerceAtLeast(0) },
            played = songs.count { it.path in played },
            added = added.size,
            addedPlayed = added.count { it.path in played },
            types = songs.groupingBy { it.mimeType }.eachCount().map { (type, count) -> FileType(type, count) }.sortedWith(compareByDescending<FileType> { it.songs }.thenBy { it.mimeType }),
            neverPlayed = if (ever.sumOf { it.count.toLong() } < MIN_HISTORY_PLAYS) emptyList() else neverPlayedOf(library, played),
            digDeeper = digDeeperOf(music, library, played),
        )
    }

    /** Your listening type: the [TRAITS_SHOWN] strongest words of those past their bar, from [MIN_TOP_PLAYS] music plays; never "explorer" for all time. */
    private fun traitsOf(found: FoundCard?, clock: ClockCard?): List<Trait> {
        if (!enough) return emptyList()
        val night = clock?.lateMs ?: 0
        val starts = startedPlays.mapNotNull { it.start }
        val top = tops.artists.firstOrNull()
        val shares = listOfNotNull(
            Trait(TraitKind.NIGHT_OWL, percentOf(night, ledger.heardMs)),
            Trait(TraitKind.SHUFFLER, percentOf(starts.count { it.shuffled }.toLong(), starts.size.toLong())),
            Trait(TraitKind.ALBUM_LOVER, percentOf(starts.count { !it.shuffled && it.source == SourceKind.ALBUM }.toLong(), starts.size.toLong())),
            found?.takeIf { period.bounds != null }?.let { Trait(TraitKind.EXPLORER, percentOf(it.newPlays.toLong(), it.plays.toLong())) },
            top?.let { Trait(TraitKind.LOYAL, percentOf(it.plays.toLong(), music.count { play -> play.counted }.toLong()), it.item) },
        )
        return shares.filter { it.percent >= barOf(it.kind) }.sortedByDescending { it.percent.toDouble() / barOf(it.kind) }.take(TRAITS_SHOWN)
    }

    private fun percentOf(part: Long, whole: Long): Int = if (whole <= 0) 0 else ((part * PERCENT + whole / 2) / whole).toInt()

    /** How far past it a word must be: a share of the time (night owl) or of the plays (the others), in percent. */
    private fun barOf(kind: TraitKind): Int = when (kind) {
        TraitKind.NIGHT_OWL -> NIGHT_OWL_BAR
        TraitKind.SHUFFLER -> SHUFFLER_BAR
        TraitKind.ALBUM_LOVER -> ALBUM_LOVER_BAR
        TraitKind.EXPLORER -> EXPLORER_BAR
        TraitKind.LOYAL -> LOYAL_BAR
    }

    /**
     * Milestones in the period (in1's), the next one with the month this month's pace reaches it, and the badges of all time;
     * a month over keeps only its own.
     */
    private fun milestonesCardOf(library: Library): MilestonesCard? {
        val own = milestonesOf(data, period.bounds, period.going, kinds)
        if (!period.going) return own?.let { MilestonesCard(it.reached, null, null, null, null, null, null, 0) }
        val ever = milestonesOf(data, null, going = false, kinds)?.reached.orEmpty()
        val longest = longestRunOf(countedDays(data.plays, zone, kinds))?.takeIf { it.days >= MIN_STREAK_DAYS }
        val artistsMonth = data.plays.filter { it.counted && kinds.of(it) == Kind.MUSIC }
            .mapNotNull { play -> songOf(play)?.let { YearMonth.from(dayOf(play.startedAt, zone).date) to it } }
            .groupBy({ it.first }, { it.second })
            .map { (month, songs) -> MonthArtists(month, songs.flatMapTo(HashSet()) { song -> library.artistsOf(song).map { it.name } }.size) }
            .maxWithOrNull(compareBy<MonthArtists> { it.artists }.thenBy { it.month })
        return MilestonesCard(
            reached = own?.reached.orEmpty(),
            next = own?.next,
            nextAround = own?.next?.let { next ->
                // This month's pace so far, plays that counted or real minutes: none without one, nor past MAX_AROUND_DAYS.
                val monthStart = YearMonth.from(dayOf(now, zone).date).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val done = if (next.kind == MilestoneKind.PLAYS) {
                    data.plays.count { it.startedAt >= monthStart && it.counted && kinds.of(it) != Kind.BOOK }.toDouble()
                } else {
                    (data.plays + data.books).filter { it.startedAt >= monthStart }.sumOf { it.realMs }.toDouble()
                }
                val days = (now - monthStart).coerceAtLeast(DAY_MS).toDouble() / DAY_MS
                (next.left / (done / days)).takeIf { done > 0 && it <= MAX_AROUND_DAYS }?.let { YearMonth.from(Instant.ofEpochMilli(now + (it * DAY_MS).toLong()).atZone(zone)) }
            },
            longestRun = longest,
            plays = ever.filter { it.kind == MilestoneKind.PLAYS }.maxByOrNull { it.count },
            hours = ever.filter { it.kind == MilestoneKind.HOURS }.maxByOrNull { it.count },
            artistsMonth = artistsMonth,
            booksFinished = sources.finishedBooks.size,
        ).takeIf { own != null || longest != null || it.booksFinished > 0 }
    }

    /** [play]'s song in the library now (found again by its path, then its id hint), once per path. */
    private fun songOf(play: LoggedPlay): Song? = songs.getOrPut(play.song.path) { library?.resolver?.find(play.song) }

    private val songs = HashMap<String, Song?>()

    /** The music played in the period whose song the library has, each with its song. */
    private val musicSongs by lazy { music.mapNotNull { play -> songOf(play)?.let { play to it } } }

    /** The period's plays that keep how they began, quick skips too; books left out. */
    private val startedPlays by lazy { (heard + data.skips.within(period.bounds)).filter { it.start != null && kinds.of(it) != Kind.BOOK } }

    /** How you listen: from [MIN_HOW_PLAYS] plays that keep how they began. */
    private fun howOf(): HowCard? {
        val plays = startedPlays
        if (plays.size < MIN_HOW_PLAYS) return null
        val ways = plays.groupingBy { wayOf(checkNotNull(it.start)) }.eachCount()
        val ended = plays.mapNotNull { play -> endOf(play)?.let { end -> songOf(play)?.takeIf { kinds.of(play) == Kind.MUSIC }?.let { it to end } } }
        val mostSkipped = ended.filter { it.second == End.EARLY }.groupingBy { it.first }.eachCount()
            .filter { it.value >= MIN_EARLY_SKIPS }
            .maxWithOrNull(compareBy<Map.Entry<Song, Int>> { it.value }.thenBy { it.key.path })
            ?.let { (song, early) -> Skipped(song, early, ended.count { it.first == song }) }
        return HowCard(
            started = Starts(ways[Way.PICKED] ?: 0, ways[Way.IN_ORDER] ?: 0, ways[Way.SHUFFLE] ?: 0, ways[Way.SUGGESTIONS] ?: 0, ways[Way.HUDDLE] ?: 0),
            ends = endsOf(plays),
            shuffled = endsOf(plays.filter { checkNotNull(it.start).shuffled }),
            inOrder = endsOf(plays.filter { play -> checkNotNull(play.start).let { !it.shuffled && it.source == SourceKind.ALBUM } }),
            mostSkipped = mostSkipped,
        )
    }

    /** What started a play, the first that holds: Huddle, a suggestion, Shuffle, the user's own pick, else the list in order. */
    private fun wayOf(start: PlayStart): Way = when {
        start.source == SourceKind.HUDDLE -> Way.HUDDLE
        start.by == StartedBy.SUGGESTED || start.source == SourceKind.SUGGESTIONS -> Way.SUGGESTIONS
        start.shuffled -> Way.SHUFFLE
        start.by == StartedBy.PICKED -> Way.PICKED
        else -> Way.IN_ORDER
    }

    private enum class Way { PICKED, IN_ORDER, SHUFFLE, SUGGESTIONS, HUDDLE }

    /** How a play ended for the cards: none for a play stopped (KJB closed) or that could not go on. */
    private fun endOf(play: LoggedPlay): End? = when (play.end?.how) {
        PlayEnd.FINISHED -> End.FINISHED
        PlayEnd.NEXT, PlayEnd.JUMP, PlayEnd.BACK -> if (checkNotNull(play.end).atMs < EARLY_SKIP_MS) End.EARLY else End.MOVED_ON
        else -> null
    }

    private enum class End { FINISHED, MOVED_ON, EARLY }

    private fun endsOf(plays: List<LoggedPlay>): Ends {
        val ends = plays.groupingBy(::endOf).eachCount()
        return Ends(ends[End.FINISHED] ?: 0, ends[End.MOVED_ON] ?: 0, ends[End.EARLY] ?: 0)
    }

    /** The postcards, each from its own minimum: where it began (all time), then the period's own, then a year ago today. */
    private fun momentsOf(library: Library): List<Moment> = listOfNotNull(
        firstPlayOf().takeIf { period.bounds == null },
        onRepeatOf(),
        longestSessionOf(),
        latestNightOf(),
        startToFinishOf(library),
        onTheMoveOf(),
        fasterOf(),
        yearAgoOf().takeIf { period.going && period.bounds != null },
    )

    private fun firstPlayOf(): Moment.FirstPlay? = data.plays.firstOrNull { it.counted && kinds.of(it) == Kind.MUSIC && songOf(it) != null }
        ?.let { Moment.FirstPlay(checkNotNull(songOf(it)), localDay(it)) }

    /** The song played most in one day: from [MIN_REPEAT] plays that counted. */
    private fun onRepeatOf(): Moment.OnRepeat? = mostInADay(musicSongs.filter { it.first.counted })
        ?.takeIf { it.plays >= MIN_REPEAT }
        ?.let { Moment.OnRepeat(it.song, it.day, it.plays) }

    /** The song of [plays] played most on one day, the latest day of equals. */
    private fun mostInADay(plays: List<Pair<LoggedPlay, Song>>): DayPlays? = plays.groupingBy { (play, song) -> song to localDay(play) }.eachCount()
        .maxWithOrNull(compareBy<Map.Entry<Pair<Song, LocalDate>, Int>> { it.value }.thenBy { it.key.second })
        ?.let { (key, count) -> DayPlays(key.first, key.second, count) }

    /**
     * The longest listening without a break: plays (books too) with under [SESSION_GAP_MS] from one's end to the next's
     * start, from [MIN_SESSION_MS], its most played music song shown (none when it had none: a book's, say).
     */
    private fun longestSessionOf(): Moment.LongestSession? {
        val sessions = mutableListOf<MutableList<LoggedPlay>>()
        var end = Long.MIN_VALUE
        (heard + books).sortedBy { it.startedAt }.forEach { play ->
            if (sessions.isEmpty() || play.startedAt - end > SESSION_GAP_MS) sessions += mutableListOf<LoggedPlay>()
            sessions.last() += play
            end = maxOf(end, play.startedAt + play.realMs)
        }
        val (plays, ms) = sessions.map { it to it.sumOf { play -> play.realMs } }.maxByOrNull { it.second }?.takeIf { it.second >= MIN_SESSION_MS } ?: return null
        return plays.filter { it.counted && kinds.of(it) == Kind.MUSIC }.mapNotNull(::songOf).groupingBy { it }.eachCount().maxByOrNull { it.value }
            ?.let { Moment.LongestSession(localTimeOf(plays.first(), zone).truncatedTo(ChronoUnit.MINUTES), ms, it.key) }
    }

    /** The latest a music play started at night, [NIGHT_FROM] to [NIGHT_UNTIL] o'clock where it was heard. */
    private fun latestNightOf(): Moment.LatestNight? = musicSongs.map { (play, song) -> localTimeOf(play, zone) to song }
        .filter { (at) -> at.hour >= NIGHT_FROM || at.hour < NIGHT_UNTIL }
        .maxWithOrNull(compareBy { (at) -> (if (at.hour < NIGHT_UNTIL) at.hour + HOURS_IN_DAY else at.hour) * MINUTES_IN_HOUR + at.minute })
        ?.let { (at, song) -> Moment.LatestNight(at.truncatedTo(ChronoUnit.MINUTES), song) }

    /**
     * The albums played start to finish: an album of [ALBUM_MIN_SONGS] songs or more played from its page, not shuffled,
     * every song in its order, one after the other, each to its end.
     */
    private fun startToFinishOf(library: Library): Moment.StartToFinish? {
        var album: Album? = null
        var played = 0
        var through = 0
        var latest: Album? = null
        musicSongs.forEach { (play, song) ->
            val start = play.start
            val own = albumKey(song)?.let(library::album)?.takeIf { it.songs.size >= ALBUM_MIN_SONGS }
            val inOrder = start != null && start.source == SourceKind.ALBUM && !start.shuffled && play.end?.how == PlayEnd.FINISHED
            played = when {
                own == null || !inOrder -> 0
                own.key == album?.key && own.songs.getOrNull(played) == song -> played + 1
                own.songs.first() == song -> 1
                else -> 0
            }
            album = own.takeIf { played > 0 }
            if (own != null && played == own.songs.size) {
                through += 1
                latest = own
                played = 0
            }
        }
        return latest?.let { Moment.StartToFinish(through, it) }
    }

    /**
     * The time zones the period's plays were heard in, from two, the most heard first: the phone's own (a play heard at
     * its offset then) is one however its summer time moved it, shown at the offset heard most there; any other offset is
     * one of its own.
     */
    private fun onTheMoveOf(): Moment.OnTheMove? = (heard + books).filter { it.zoneOffsetMinutes != null }
        .groupBy { play -> checkNotNull(play.zoneOffsetMinutes).takeIf { it != zoneOffsetMinutes(zone, play.startedAt) } }
        .values.map { plays -> plays.groupBy { checkNotNull(it.zoneOffsetMinutes) }.maxBy { (_, its) -> its.sumOf { it.realMs } }.key to plays.sumOf { it.realMs } }
        .sortedByDescending { it.second }.map { it.first }
        .takeIf { it.size >= MIN_ZONES }
        ?.let(Moment::OnTheMove)

    /** The time faster speeds saved in books and podcasts, from a minute. */
    private fun fasterOf(): Moment.Faster? {
        val booksMs = savedMs(books + heard.filter { kinds.of(it) == Kind.BOOK })
        val podcastsMs = savedMs(heard.filter { kinds.of(it) == Kind.PODCAST })
        return Moment.Faster(booksMs, podcastsMs).takeIf { booksMs + podcastsMs >= MIN_SAVED_MS }
    }

    /** The time faster speeds saved in [plays]: the time heard past the real minutes. */
    private fun savedMs(plays: List<LoggedPlay>): Long = plays.sumOf { play -> play.clockMs?.let { (play.heardMs - it).coerceAtLeast(0) } ?: 0 }

    /** The song played most on this day a year ago, where it was heard. */
    private fun yearAgoOf(): Moment.YearAgo? {
        val day = period.lastDay.minusYears(1)
        return mostInADay(data.plays.filter { it.counted && kinds.of(it) == Kind.MUSIC && localDay(it) == day }.mapNotNull { play -> songOf(play)?.let { play to it } })
            ?.let { Moment.YearAgo(it.song, it.day, it.plays) }
    }

    /**
     * New to you: the artists and songs whose first play that counted is in the period, none known before it (played here,
     * or in an app imported from); all time: every one. Kept: played on [KEPT_DAYS] days.
     */
    private fun foundOf(library: Library): FoundCard? {
        if (!enough) return null
        val from = period.bounds?.from
        val known = if (from == null) emptySet() else (data.plays.filter { it.startedAt < from && it.counted } + data.imports).mapNotNullTo(HashSet()) { songOf(it)?.path }
        val knownArtists = known.flatMapTo(HashSet()) { path -> library.song(path)?.let(library::artistsOf).orEmpty().map { it.name } }
        val counted = musicSongs.filter { it.first.counted }
        val daysOf = HashMap<String, MutableSet<LocalDate>>()
        val firstOf = HashMap<String, LoggedPlay>()
        counted.forEach { (play, song) ->
            library.artistsOf(song).forEach { artist ->
                daysOf.getOrPut(artist.name) { HashSet() } += localDay(play)
                firstOf.putIfAbsent(artist.name, play)
            }
        }
        val artists = tops.artists.filter { it.item.name !in knownArtists }.map { FoundArtist(it.item, (daysOf[it.item.name]?.size ?: 0) >= KEPT_DAYS) }
        val songs = tops.songs.filter { it.item.path !in known }
        if (artists.isEmpty() && songs.isEmpty()) return null
        val ways = artists.mapNotNull { firstOf[it.artist.name]?.start }.map(::wayOf)
        return FoundCard(
            artists = artists,
            songs = songs.size,
            newPlays = songs.sumOf { it.plays },
            plays = counted.size,
            fromSuggestions = ways.count { it == Way.SUGGESTIONS },
            fromFriends = ways.count { it == Way.HUDDLE },
            onYourOwn = artists.size - ways.count { it == Way.SUGGESTIONS || it == Way.HUDDLE },
        )
    }

    /** Back again: the songs played in the period after [QUIET_DAYS] days without a play that counted, the longest quiet first. */
    private fun comebacksOf(): List<Comeback> {
        val bySong = data.plays.filter { it.counted && kinds.of(it) == Kind.MUSIC }.mapNotNull { play -> songOf(play)?.let { play to it } }.groupBy({ it.second }, { it.first })
        return bySong.mapNotNull { (song, plays) ->
            plays.zipWithNext().filter { (_, next) -> period.bounds == null || next.startedAt in period.bounds }
                .maxOfOrNull { (before, next) -> ChronoUnit.DAYS.between(localDay(before), localDay(next)) }
                ?.takeIf { it >= QUIET_DAYS }
                ?.let { Comeback(song, it.toInt()) }
        }.sortedByDescending { it.quietDays }.take(COMEBACKS_SHOWN)
    }

    /** The top lists show from [MIN_TOP_PLAYS] music plays that counted. */
    private val enough by lazy { music.count { it.counted } >= MIN_TOP_PLAYS }

    /** All of the period before's top lists, to rank against: none for all time, nor with under [MIN_TOP_PLAYS] music plays that counted. */
    private val placesBefore: Listening? by lazy {
        val plays = period.before?.let { data.plays.within(it) }?.filter { kinds.of(it) == Kind.MUSIC } ?: return@lazy null
        listeningOf(songPlaysOf(plays), library).takeIf { plays.count { it.counted } >= MIN_TOP_PLAYS }
    }

    /** A top list from its [rows] (at least [min] of them), each with its place in [before] (by [key]) and its [cover]. */
    private fun <T> topCardOf(rows: List<Top<T>>, min: Int, before: List<Top<T>>?, key: (T) -> String, cover: (T) -> Song?): TopCard<T>? {
        if (!enough || rows.size < min) return null
        val places = before?.withIndex()?.associate { (at, top) -> key(top.item) to at + 1 }
        return TopCard(rows.mapNotNull { top -> cover(top.item)?.let { Ranked(top, places?.get(key(top.item)), it) } }, compared = places != null)
    }

    /**
     * How long [first] has been number 1: the weeks or months in a row up to this one, a year's months, or all time's
     * month it took the lead and kept it; by the phone's calendar, as the periods are.
     */
    private fun loyaltyOf(first: String, library: Library): Loyalty? {
        val music = data.plays.filter { kinds.of(it) == Kind.MUSIC }
        fun numberOne(plays: List<LoggedPlay>?): String? = plays?.let { listeningOf(songPlaysOf(it), library).artists.firstOrNull()?.item?.name }
        fun monthOf(play: LoggedPlay): YearMonth = YearMonth.from(dayOf(play.startedAt, zone).date)
        return when (period.kind) {
            ListeningPeriod.WEEK -> {
                val weeks = music.groupBy { dayOf(it.startedAt, zone).date.with(TemporalAdjusters.previousOrSame(calendar.firstDayOfWeek)) }
                generateSequence(period.firstDay) { it.minusWeeks(1) }.takeWhile { numberOne(weeks[it]) == first }.count().takeIf { it >= MIN_LOYALTY }?.let(Loyalty::Running)
            }
            ListeningPeriod.MONTH -> {
                val months = music.groupBy(::monthOf)
                generateSequence(YearMonth.from(period.firstDay)) { it.minusMonths(1) }.takeWhile { numberOne(months[it]) == first }.count().takeIf { it >= MIN_LOYALTY }?.let(Loyalty::Running)
            }
            ListeningPeriod.YEAR -> {
                val months = heard.filter { kinds.of(it) == Kind.MUSIC }.groupBy(::monthOf)
                val of = YearMonth.from(period.lastDay).monthValue
                (1..of).count { numberOne(months[YearMonth.of(period.firstDay.year, it)]) == first }.takeIf { it >= MIN_LOYALTY }?.let { Loyalty.MonthsOf(it, of) }
            }
            ListeningPeriod.ALL_TIME -> {
                // Each song's plays so far, month by month: the number 1 of all time at the end of each month.
                val totals = HashMap<String, SongPlays>()
                var leader: String? = null
                var since: YearMonth? = null
                music.groupBy(::monthOf).toSortedMap().forEach { (month, plays) ->
                    songPlaysOf(plays).forEach { added ->
                        totals.merge(added.song.path, added) { kept, new -> SongPlays(new.song, kept.count + new.count, listOfNotNull(kept.lastPlayedAt, new.lastPlayedAt).maxOrNull(), kept.heardMs + new.heardMs) }
                    }
                    val now = listeningOf(totals.values.toList(), library).artists.firstOrNull()?.item?.name
                    if (now != leader) since = month
                    leader = now
                }
                since?.takeIf { leader == first }?.let(Loyalty::Since)
            }
        }
    }


    /** Your taste: the genres from 2 of them, the decades and middle year from [MIN_TOP_PLAYS] plays of songs with a year. */
    private fun tasteOf(library: Library): TasteCard? {
        if (!enough) return null
        val genres = genresOf(tops.songs, library).takeIf { it.size >= MIN_TOP_OTHERS }.orEmpty()
        val years = tops.songs.mapNotNull { top -> top.item.year?.takeIf { it > 0 }?.let { it to top.plays } }.sortedBy { it.first }
        val dated = years.sumOf { it.second }
        if (dated < MIN_TOP_PLAYS) return TasteCard(genres, emptyList(), null).takeIf { genres.isNotEmpty() }
        val byDecade = years.groupBy { it.first / DECADE * DECADE }.mapValues { (_, its) -> its.sumOf { it.second } }
        val decades = (byDecade.keys.min()..byDecade.keys.max() step DECADE).map { Decade(it, byDecade[it] ?: 0) }
        var count = 0
        val middle = years.first { (_, plays) -> count += plays; count * 2 >= dated }.first
        return TasteCard(genres, decades, middle)
    }

    /** Your time: the totals now and the period before's up to the same point, step by step. */
    private fun timeOf(): TimeCard {
        val step = if (period.kind == ListeningPeriod.WEEK || period.kind == ListeningPeriod.MONTH) Step.DAY else Step.MONTH
        val before = period.before?.let { previous -> totalsOf(Period(previous.from, checkNotNull(period.beforeUntil))).takeIf { it.plays >= MIN_VERSUS_PLAYS || it.heardMs >= MIN_VERSUS_HEARD_MS } }
        val heardBefore = period.before?.takeIf { before != null }?.let { previous ->
            val first = dayOf(previous.from, zone).date
            stepsOf(data.plays.within(previous) + data.books.within(previous), step, first, stepsBetween(step, first, dayOf(previous.until, zone).date), last = null)
        }
        return TimeCard(totalsOf(period.bounds), before, stepsOf(heard + books, step), heardBefore)
    }

    /** The totals of [bounds]: the time heard and plays (both ledgers), and the songs, artists and albums of the music. */
    private fun totalsOf(bounds: Period?): Totals {
        val plays = data.plays.within(bounds)
        val ledger = ledgerOf(plays, data.books.within(bounds), kinds)
        if (library == null) return Totals(ledger.heardMs, ledger.plays, 0, 0, 0)
        val tops = if (bounds == period.bounds) tops else listeningOf(songPlaysOf(plays.filter { kinds.of(it) == Kind.MUSIC }), library)
        return Totals(ledger.heardMs, ledger.plays, tops.songs.size, tops.artists.size, tops.albums.size)
    }

    /** [plays]' real minutes by [step] over the period (a step after today has none yet). */
    private fun stepsOf(plays: List<LoggedPlay>, step: Step): Steps {
        val first = if (step == Step.MONTH) period.firstDay.withDayOfMonth(1) else period.firstDay
        val end = period.bounds?.let { dayOf(it.until, zone).date } ?: if (step == Step.MONTH) first.plusMonths(1 + stepsBetween(step, first, period.lastDay).toLong()) else period.lastDay.plusDays(1)
        return stepsOf(plays, step, first, stepsBetween(step, first, end), last = period.lastDay.takeIf { period.going })
    }

    /**
     * [plays]' real minutes by [step], [count] steps from [first], each play in the step of the day it was heard (one a
     * time zone took past either end, or past [last], goes in the first step or the last one shown); a step after [last]
     * has none yet.
     */
    private fun stepsOf(plays: List<LoggedPlay>, step: Step, first: LocalDate, count: Int, last: LocalDate?): Steps {
        val sums = LongArray(count)
        plays.forEach { sums[stepsBetween(step, first, minOf(localDay(it), last ?: LocalDate.MAX)).coerceIn(0, count - 1)] += it.realMs }
        val lastStep = last?.let { stepsBetween(step, first, it) } ?: count
        return Steps(step, first, sums.mapIndexed { at, ms -> ms.takeIf { at <= lastStep } })
    }

    private fun stepsBetween(step: Step, from: LocalDate, to: LocalDate): Int =
        (if (step == Step.DAY) ChronoUnit.DAYS.between(from, to) else ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to))).toInt()

    /** Every day you listened: plays that counted on [MIN_DAYS] days, the heat map a day each but for all time. */
    private fun daysOf(): DaysCard? {
        val days = countedDays(heard, zone, kinds)
        if (days.size < MIN_DAYS) return null
        val current = if (period.going) runUpTo(period.lastDay, countedDays(data.plays, zone, kinds)) else null
        // On the day the steps show it: one a time zone took past either end of the days shown is on the first or the last.
        val byDay = (heard + books).groupBy { localDay(it).coerceIn(period.firstDay, period.lastDay) }.mapValues { (_, its) -> its.sumOf { it.realMs } }
        val busiest = byDay.entries.maxWith(compareBy<Map.Entry<LocalDate, Long>> { it.value }.thenBy { it.key })
        return DaysCard(stepsOf(heard + books, if (period.bounds == null) Step.MONTH else Step.DAY), current, longestRunOf(days)?.takeIf { it.days >= MIN_STREAK_DAYS }, DayHeard(busiest.key, busiest.value))
    }

    /** Your day on a record: from [MIN_CLOCK_PLAYS] plays that counted. */
    private fun clockOf(): ClockCard? {
        if (heard.count { it.counted } < MIN_CLOCK_PLAYS) return null
        val hours = LongArray(HOURS_IN_DAY)
        var weekend = 0L
        (heard + books).forEach { play ->
            val local = localTimeOf(play, zone)
            hours[local.hour] += play.realMs
            if (local.dayOfWeek in calendar.weekend) weekend += play.realMs
        }
        // The hours from one o'clock up to another, round midnight too.
        fun between(from: Int, until: Int): Long = (from until if (until > from) until else until + HOURS_IN_DAY).sumOf { hours[it % HOURS_IN_DAY] }
        return ClockCard(
            hours = hours.toList(),
            peak = hours.indices.maxBy { hours[it] },
            weekdaysMs = hours.sum() - weekend,
            weekendMs = weekend,
            morningMs = between(MORNING_FROM, NOON),
            afternoonMs = between(NOON, EVENING_FROM),
            eveningMs = between(EVENING_FROM, NIGHT_PART_FROM),
            nightMs = between(NIGHT_PART_FROM, MORNING_FROM),
            lateMs = between(NIGHT_FROM, NIGHT_OWL_UNTIL),
        )
    }

    /**
     * This month's or this year's ([kind]) own totals so far, whatever the page's period, for its ring on the stories row
     * (the period pages only): none when its own page's Your time would not show.
     */
    private fun soFarOf(kind: ListeningPeriod): Totals? = if (!period.going) {
        null
    } else {
        totalsOf(periodOf(kind, now, zone, calendar.firstDayOfWeek)).takeIf { it.heardMs >= MIN_HEARD_MS || it.plays > 0 }
    }

    /** The stories row: each month with a recap ([recapMonths]) and its most played songs of different albums. */
    private fun recapsOf(library: Library): List<RecapMonth> {
        val byMonth = data.plays.filter { kinds.of(it) == Kind.MUSIC }.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(zone)) }
        return recapMonths(data.plays, now, zone, library).map { month ->
            val songs = listeningOf(songPlaysOf(byMonth[month].orEmpty()), library).songs.map { it.item }
            RecapMonth(month, songs.distinctBy { albumKey(it) ?: it.path }.take(RECAP_COVERS))
        }
    }
}
