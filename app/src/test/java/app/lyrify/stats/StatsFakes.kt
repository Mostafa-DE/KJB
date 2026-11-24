package app.lyrify.stats

import app.lyrify.library.Library
import app.lyrify.library.Playlist
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.settings.Settings
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** The play as 3.1.0 kept it: its song, start, time heard and count, and nothing more, for tests of those alone. */
fun LoggedPlay.as310(): LoggedPlay = LoggedPlay(song, startedAt, heardMs, counted)

fun List<LoggedPlay>.as310(): List<LoggedPlay> = map(LoggedPlay::as310)

/** The kept plays as 3.1.0 kept them ([as310]). */
@JvmName("storedAs310")
fun List<StoredPlay>.as310(): List<StoredPlay> = map { it.copy(play = it.play.as310()) }

/**
 * `plays.db` made up, in memory, with its rules (PlaysDbDeviceTest checks the real one against the same, and
 * PlaysSqlTest its SQL on the laptop): a play written again is the one of the same song and start, and keeps the most
 * time heard, its count once counted, its id hint unless a newer one is given, how it began and its time zone as first
 * written (a part the first write lacked filled in by a later one), how it ended as last written, its import mark
 * once marked, and the most real minutes written (none until a write has them); with an [OpenState] it is kept as still going,
 * without one it is let go. A song's totals count its plays that counted, the last of those, and the time heard in all
 * its plays, the short ones ([LoggedPlay.isShort]) left out; its id hint is its latest play's of those. The plays heard
 * here leave out an import's and the short ones. An interaction noted again (the same time and kind) replaces the one
 * there, one restored leaves it as it is. [failing]: every write and every read of the interactions fails.
 */
class FakePlayLog(plays: List<LoggedPlay> = emptyList(), interactions: List<Interaction> = emptyList()) : PlayLogStore {
    val plays: MutableList<LoggedPlay> = plays.toMutableList()
    val interactions: MutableList<Interaction> = interactions.toMutableList()
    private val open = LinkedHashMap<Pair<String, Long>, OpenState>()
    var failing: Boolean = false

    override suspend fun put(play: LoggedPlay, open: OpenState?): Boolean {
        if (failing) return false
        val key = play.song.path to play.startedAt
        val old = plays.firstOrNull { it.song.path == play.song.path && it.startedAt == play.startedAt }
        val row = if (old == null) {
            play
        } else {
            LoggedPlay(
                SongRef(play.song.path, play.song.mediaStoreId ?: old.song.mediaStoreId),
                play.startedAt,
                maxOf(old.heardMs, play.heardMs),
                old.counted || play.counted,
                start = firstStart(old.start, play.start),
                zoneOffsetMinutes = old.zoneOffsetMinutes ?: play.zoneOffsetMinutes,
                end = play.end ?: old.end,
                imported = old.imported || play.imported,
                clockMs = listOfNotNull(old.clockMs, play.clockMs).maxOrNull(),
            )
        }
        plays.remove(old)
        plays += row
        if (open != null) this.open[key] = open else this.open.remove(key)
        return true
    }

    /** How a play began, as plays.db merges it: each part as first written, one the first write lacked filled in later. */
    private fun firstStart(old: PlayStart?, new: PlayStart?): PlayStart? = if (old == null || new == null) {
        old ?: new
    } else {
        old.copy(source = old.source ?: new.source, songKey = old.songKey ?: new.songKey, output = old.output ?: new.output, playlistId = old.playlistId ?: new.playlistId)
    }

    override suspend fun all(): List<LoggedPlay>? = if (failing) null else plays.sortedBy { it.startedAt }

    /** Each as [put] writes it, and each interaction not there yet, or none when [failing]. */
    override suspend fun putAll(plays: List<LoggedPlay>, interactions: List<Interaction>): Boolean {
        if (failing) return false
        plays.forEach { put(it) }
        interactions.filter { new -> this.interactions.none { it.at == new.at && it.kind == new.kind } }.forEach { this.interactions += it }
        return true
    }

    override suspend fun note(interaction: Interaction): Boolean {
        if (failing) return false
        interactions.removeAll { it.at == interaction.at && it.kind == interaction.kind }
        interactions += interaction
        return true
    }

    override suspend fun interactions(from: Long?, until: Long?): List<Interaction>? = if (failing) {
        null
    } else {
        interactions.filter { (from == null || it.at >= from) && (until == null || it.at < until) }.sortedWith(compareBy({ it.at }, { codeOf(it.kind) }))
    }

    override suspend fun forget(times: List<Long>): Boolean {
        if (failing) return false
        interactions.replaceAll { if (it.kind == InteractionKind.SEARCH && it.at in times) it.copy(words = null) else it }
        return true
    }

    /** Each by its song and start, or none when [failing]. */
    override suspend fun removeAll(plays: List<LoggedPlay>): Boolean {
        if (failing) return false
        val gone = plays.map { it.song.path to it.startedAt }.toSet()
        this.plays.removeAll { (it.song.path to it.startedAt) in gone }
        gone.forEach(open::remove)
        return true
    }

    override suspend fun openPlays(): List<StoredPlay> = open.map { (key, state) ->
        StoredPlay(plays.first { it.song.path == key.first && it.startedAt == key.second }, state)
    }

    override suspend fun totals(from: Long?, until: Long?): List<SongPlays> {
        val logged = plays.filterNot { it.isShort }
        val hints = logged.groupBy { it.song.path }.mapValues { (_, its) -> its.maxBy { it.startedAt }.song.mediaStoreId }
        return logged.filter { (from == null || it.startedAt >= from) && (until == null || it.startedAt < until) }
            .groupBy { it.song.path }
            .map { (path, its) ->
                SongPlays(SongRef(path, hints[path]), its.count { it.counted }, its.filter { it.counted }.maxOfOrNull { it.startedAt }, its.sumOf { it.heardMs })
            }
    }
}

/**
 * `plays.db`'s own SQL ([PlaysSchema]) on the laptop's SQLite, in memory: what [SqlPlayLog] runs on the phone, the same
 * statements with the same values ([PlaysSchema.putArgs], [PlaysSchema.noteArgs]) and rows read the same way
 * ([loggedPlayOf], [songPlaysOf], [storedPlayOf], [interactionOf]). Made at [version] (3.1.0's file is version 2); [upgrade] runs the steps after it as PlaysDb does.
 */
class JdbcPlayLog(version: Int = PlaysSchema.VERSION) : PlayLogStore, AutoCloseable {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        upgrade(0, version)
    }

    /** PlaysDb's onCreate (from 0) and onUpgrade. */
    fun upgrade(from: Int, to: Int) = PlaysSchema.upgrade(from, to, ::columns).forEach { update(it) }

    fun columns(table: String): Set<String> = query("PRAGMA table_info($table)") { rows -> buildSet { while (rows.next()) add(rows.getString("name")) } }

    fun update(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { at, value -> statement.setObject(at + 1, value) }
            statement.executeUpdate()
        }
    }

    fun <T> query(sql: String, vararg args: Any?, read: (ResultSet) -> T): T = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { at, value -> statement.setObject(at + 1, value) }
        statement.executeQuery().use(read)
    }

    override suspend fun put(play: LoggedPlay, open: OpenState?): Boolean {
        transaction {
            update(PlaysSchema.PUT, *PlaysSchema.putArgs(play))
            if (open == null) {
                update(PlaysSchema.LET_GO, play.song.path, play.startedAt)
            } else {
                update(PlaysSchema.KEEP_OPEN, play.song.path, play.startedAt, open.durationMs, open.positionMs, spansText(open.spans))
            }
        }
        return true
    }

    override suspend fun openPlays(): List<StoredPlay> = query(PlaysSchema.OPEN_PLAYS) { rows ->
        buildList {
            while (rows.next()) {
                val row = ResultRow(rows)
                storedPlayOf(
                    rows.getString(1), row.at(2), row.at(3), row.at(4), row.at(5), row.at(6), row.at(7), rows.getString(8), row.number("clock_ms"),
                )?.let(::add)
            }
        }
    }

    override suspend fun totals(from: Long?, until: Long?): List<SongPlays> =
        query(PlaysSchema.TOTALS, (from ?: Long.MIN_VALUE).toString(), (until ?: Long.MAX_VALUE).toString()) { rows ->
            buildList {
                while (rows.next()) {
                    val row = ResultRow(rows)
                    songPlaysOf(rows.getString(1), row.at(2), row.at(3), row.at(4), row.at(5))?.let(::add)
                }
            }
        }

    override suspend fun all(): List<LoggedPlay> = query(PlaysSchema.ALL) { rows ->
        val row = ResultRow(rows)
        buildList { while (rows.next()) loggedPlayOf(row)?.let(::add) }
    }

    override suspend fun since(from: Long): List<LoggedPlay> = query(PlaysSchema.SINCE, from) { rows ->
        val row = ResultRow(rows)
        buildList { while (rows.next()) loggedPlayOf(row)?.let(::add) }
    }

    override suspend fun putAll(plays: List<LoggedPlay>, interactions: List<Interaction>): Boolean {
        transaction {
            plays.forEach { update(PlaysSchema.PUT, *PlaysSchema.putArgs(it)) }
            interactions.forEach { update(PlaysSchema.RESTORE_INTERACTION, *PlaysSchema.noteArgs(it)) }
        }
        return true
    }

    override suspend fun note(interaction: Interaction): Boolean {
        update(PlaysSchema.NOTE, *PlaysSchema.noteArgs(interaction))
        return true
    }

    override suspend fun interactions(from: Long?, until: Long?): List<Interaction> =
        query(PlaysSchema.INTERACTIONS, (from ?: Long.MIN_VALUE).toString(), (until ?: Long.MAX_VALUE).toString()) { rows ->
            val row = ResultRow(rows)
            buildList { while (rows.next()) interactionOf(row)?.let(::add) }
        }

    override suspend fun forget(times: List<Long>): Boolean {
        transaction { times.forEach { update(PlaysSchema.FORGET, it) } }
        return true
    }

    override suspend fun removeAll(plays: List<LoggedPlay>): Boolean {
        transaction {
            plays.forEach { play ->
                update(PlaysSchema.LET_GO, play.song.path, play.startedAt)
                update(PlaysSchema.REMOVE, play.song.path, play.startedAt)
            }
        }
        return true
    }

    override fun close() = connection.close()

    fun transaction(work: () -> Unit) {
        connection.autoCommit = false
        try {
            work()
            connection.commit()
        } catch (e: SQLException) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /** The row [rows] is at, as the phone's Cursor gives it: a column the query lacks, or a value of another kind, is none. */
    class ResultRow(private val rows: ResultSet) : PlayRow {
        override fun text(column: String): String? = index(column)?.let(rows::getString)

        override fun number(column: String): Long? = index(column)?.let(::at)

        fun at(index: Int): Long? = when (val value = rows.getObject(index)) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            else -> null
        }

        private fun index(column: String): Int? = (1..rows.metaData.columnCount).firstOrNull { rows.metaData.getColumnName(it) == column }
    }
}

/**
 * An Insights page with every card filled with made-up figures, for the screens' tests: this week (Monday 23 to Thursday
 * 26 November 2026, Amman), set against last week. Made-up names only.
 */
fun fakeInsightsPage(): InsightsPage {
    val copper = song("Music/Low Sun/01 Copper Sky.mp3", artist = "Kestrel", album = "Low Sun", track = 1, year = 2014, genre = "Indie", id = 1)
    val tide = song("Music/Low Sun/02 Tidewater.mp3", artist = "Kestrel", album = "Low Sun", track = 2, year = 2014, genre = "Indie", id = 2)
    val harbour = song("Music/Tidelines/Paper Harbour.mp3", artist = "Mira Solen", album = "Tidelines", year = 2019, genre = "Folk", id = 3)
    val drive = song("Music/Night Drive/Northbound.mp3", artist = "The Lowlands", album = "Night Drive", year = 2008, genre = "Electronic", id = 4)
    val library = Library.of(listOf(copper, tide, harbour, drive), Settings())
    fun <T> ranked(item: T, plays: Int, minutes: Long, before: Int?, cover: app.lyrify.library.Song) = Ranked(Top(item, plays, minutes * 60_000, 0), before, cover)
    val kestrel = checkNotNull(library.artist("Kestrel"))
    val mira = checkNotNull(library.artist("Mira Solen"))
    val lowlands = checkNotNull(library.artist("The Lowlands"))
    val lowSun = checkNotNull(library.albums.firstOrNull { it.title == "Low Sun" })
    val tidelines = checkNotNull(library.albums.firstOrNull { it.title == "Tidelines" })
    val monday = LocalDate.of(2026, 11, 23)
    val days = listOf(128L, 171L, 104L, 185L).map { it * 60_000 } + listOf(null, null, null)
    val period = PagePeriod(
        kind = ListeningPeriod.WEEK,
        bounds = Period(1_795_381_200_000, 1_795_986_000_000),
        firstDay = monday,
        lastDay = monday.plusDays(3),
        going = true,
        before = Period(1_794_776_400_000, 1_795_381_200_000),
        beforeUntil = 1_795_113_600_000,
    )
    val cards = InsightsCards(
        recaps = listOf(RecapMonth(YearMonth.of(2026, 10), listOf(copper, harbour, drive))),
        monthSoFar = Totals(heardMs = 2_140 * 60_000, plays = 702, songs = 311, artists = 96, albums = 140),
        yearSoFar = Totals(heardMs = 24_610 * 60_000, plays = 8_066, songs = 1_904, artists = 402, albums = 655),
        time = TimeCard(
            totals = Totals(heardMs = 588 * 60_000, plays = 193, songs = 128, artists = 47, albums = 61),
            before = Totals(heardMs = 493 * 60_000, plays = 162, songs = 117, artists = 44, albums = 58),
            heard = Steps(Step.DAY, monday, days),
            heardBefore = Steps(Step.DAY, monday.minusWeeks(1), listOf(102L, 131L, 88L, 172L, 0L, 140L, 96L).map { it * 60_000 }),
        ),
        days = DaysCard(Steps(Step.DAY, monday, days), current = 19, longest = Run(19, LocalDate.of(2026, 11, 8)), busiest = DayHeard(monday.plusDays(3), 185 * 60_000)),
        clock = ClockCard(
            List(24) { (it + 1) * 600_000L },
            peak = 21,
            weekdaysMs = 420 * 60_000,
            weekendMs = 168 * 60_000,
            morningMs = 150 * 60_000,
            afternoonMs = 120 * 60_000,
            eveningMs = 180 * 60_000,
            nightMs = 138 * 60_000,
            lateMs = 241 * 60_000,
        ),
        artists = TopCard(
            listOf(ranked(kestrel, 31, 101, 1, copper), ranked(mira, 24, 79, 3, harbour), ranked(lowlands, 17, 58, null, drive)),
            compared = true,
            loyalty = Loyalty.Running(6),
        ),
        songs = TopCard(
            listOf(ranked(harbour, 11, 34, 2, harbour), ranked(copper, 10, 47, 1, copper), ranked(drive, 7, 26, null, drive)),
            compared = true,
            firstsDay = DayPlays(harbour, monday.plusDays(1), 6),
        ),
        albums = TopCard(listOf(ranked(tidelines, 11, 34, 2, harbour), ranked(lowSun, 10, 47, 1, copper)), compared = true),
        taste = TasteCard(
            genres = library.genres.map { Top(it, 10, 600_000, 0) },
            decades = listOf(Decade(2000, 7), Decade(2010, 21)),
            middleYear = 2014,
        ),
        how = HowCard(Starts(picked = 56, inOrder = 42, shuffle = 46, suggestions = 33, huddle = 16), Ends(133, 35, 25), Ends(30, 8, 12), Ends(60, 9, 4), Skipped(tide, 4, 5)),
        moments = listOf(
            Moment.OnRepeat(harbour, monday.plusDays(1), 6),
            Moment.LongestSession(LocalDateTime.of(2026, 11, 24, 20, 5), 161 * 60_000, drive),
            Moment.LatestNight(LocalDateTime.of(2026, 11, 25, 1, 48), harbour),
            Moment.StartToFinish(2, lowSun),
            Moment.OnTheMove(listOf(180, 60)),
            Moment.Faster(booksMs = 0, podcastsMs = 18 * 60_000),
            Moment.YearAgo(copper, LocalDate.of(2025, 11, 26), 4),
        ),
        found = FoundCard(listOf(FoundArtist(lowlands, kept = true)), songs = 13, newPlays = 35, plays = 193, fromSuggestions = 2, fromFriends = 1, onYourOwn = 1),
        comebacks = listOf(Comeback(tide, 94)),
        forgotten = listOf(Top(drive, 12, 2_400_000, 0), Top(tide, 9, 1_800_000, 0), Top(copper, 7, 1_400_000, 0)),
        friends = FriendsCard(
            matches = listOf(FriendMatch("f-wren", "Wren", past = false, percent = 74), FriendMatch("f-omar", "Omar", past = true, percent = 61)),
            both = listOf(kestrel, mira),
            sent = 3,
            got = 5,
            kept = 1,
            inSync = InSync("f-omar", "Omar", copper, 1),
            favourite = SentKept("f-wren", "Wren", drive, 5),
        ),
        lyrics = LyricsCard(opened = 21, most = harbour, mostTimes = 6, syncedPlays = 147, plays = 193),
        favourites = FavouritesCard(added = 4, mostPlayed = harbour),
        searches = SearchesCard(17, tapped = 14, played = 12, words = listOf(WordCount("harbour", 3), WordCount("kite", 1)), songs = 9, artists = 4, albums = 1, others = 0),
        spoken = SpokenCard(booksMs = 65 * 60_000, podcastsMs = 40 * 60_000, savedMs = 18 * 60_000, bookmarks = 2, finished = 0, book = null, podcast = null),
        outputs = OutputsCard(speaker = 30, headphones = 110, bluetooth = 40, car = 13),
        playlists = PlaylistsCard(listOf(Top(Playlist("p-late", "Late Walks", listOf(harbour.ref, copper.ref), createdAt = 1_000), 22, 66 * 60_000, 0))),
        library = LibraryCard(
            songs = 4,
            bytes = 41_600_000,
            durationMs = 800_000,
            played = 3,
            added = 2,
            addedPlayed = 1,
            types = listOf(FileType("audio/mpeg", 3), FileType("audio/flac", 1)),
            neverPlayed = listOf(tide),
            digDeeper = listOf(DigDeeper(lowSun, copper, listOf(tide))),
        ),
        traits = listOf(Trait(TraitKind.NIGHT_OWL, 41), Trait(TraitKind.SHUFFLER, 24), Trait(TraitKind.LOYAL, 16, kestrel)),
        milestones = MilestonesCard(
            reached = listOf(Milestone(MilestoneKind.PLAYS, 1_000, 1_795_503_600_000)),
            next = NextMilestone(MilestoneKind.HOURS, 100, 12 * 3_600_000),
            nextAround = YearMonth.of(2026, 12),
            longestRun = Run(27, LocalDate.of(2026, 3, 1)),
            plays = Milestone(MilestoneKind.PLAYS, 1_000, 1_795_503_600_000),
            hours = Milestone(MilestoneKind.HOURS, 50, 1_790_000_000_000),
            artistsMonth = MonthArtists(YearMonth.of(2026, 10), 96),
            booksFinished = 1,
        ),
    )
    return InsightsPage(period, cards)
}

/** The phone's calendar in the tests: Amman, the week from Monday, the weekend Friday and Saturday (a phone in Jordan). */
val ammanCalendar: PhoneCalendar = PhoneCalendar(ZoneId.of("Asia/Amman"), DayOfWeek.MONDAY, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
