package app.lyrify.stats

import app.lyrify.library.SongRef
import app.lyrify.player.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `plays.db`'s own SQL ([PlaysSchema]) on the laptop's SQLite ([JdbcPlayLog]): a new file, a 3.1.0 file brought up to
 * date, the step run again after a downgrade, what a play keeps when written again, what a backup reads, the totals
 * every list and count reads, which leave the short plays out as 3.1.0 had none, and the interaction log. Made-up
 * names only.
 */
class PlaysSqlTest {
    private val stores = mutableListOf<JdbcPlayLog>()

    private fun store(version: Int = PlaysSchema.VERSION): JdbcPlayLog = JdbcPlayLog(version).also(stores::add)

    @After
    fun close() = stores.forEach(JdbcPlayLog::close)

    private val salt = SongRef("$MUSIC/Salt Lattice.mp3", 12)
    private val ember = SongRef("$MUSIC/Ember Ledger.mp3", 14)
    private val alder = SongRef("$MUSIC/Alder Smoke.mp3")

    private val picked = PlayStart(StartedBy.PICKED, SourceKind.ALBUM, shuffled = false, songKey = "salt lattice|kjb test band|harbour glass|105")

    /** A play as 3.1.0 wrote it: its statement, word for word. */
    private fun JdbcPlayLog.put310(play: LoggedPlay) =
        update(PUT_310, play.song.path, play.startedAt, play.song.mediaStoreId, play.heardMs, if (play.counted) 1L else 0L)

    /** 3.1.0's totals of this file, word for word. */
    private fun JdbcPlayLog.totals310(from: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): Set<SongPlays> =
        query(TOTALS_310, from.toString(), until.toString()) { rows ->
            buildSet {
                while (rows.next()) {
                    val number = { at: Int -> (rows.getObject(at) as Number?)?.toLong() }
                    songPlaysOf(rows.getString(1), number(2), number(3), number(4), number(5))?.let(::add)
                }
            }
        }

    // Versions

    @Test
    fun `a new file has every column a play keeps`() {
        assertEquals(PLAYS_310 + NEW_COLUMNS, store().columns("plays"))
        assertEquals(OPEN_PLAYS_310, store().columns("open_plays"))
    }

    @Test
    fun `a 3_1_0 file keeps every play as it was, and gains the new columns empty`() = runBlocking {
        val store = store(version = 2)
        val heard = LoggedPlay(salt, T0, 200_000, counted = true)
        val skipped = LoggedPlay(ember, T0 + 1, 20_000, counted = false)
        store.put310(heard)
        store.put310(skipped)

        store.upgrade(2, PlaysSchema.VERSION)

        assertEquals(PLAYS_310 + NEW_COLUMNS, store.columns("plays"))
        assertEquals(listOf(heard, skipped), store.all())
    }

    @Test
    fun `the upgrade marks the plays an import made, nothing heard and counted, and only those`() = runBlocking {
        val store = store(version = 2)
        // ImportPlan's shape: counted, nothing heard, a millisecond apart.
        val imports = listOf(LoggedPlay(salt, T0 - 1, 0, counted = true), LoggedPlay(salt, T0, 0, counted = true))
        val heard = LoggedPlay(salt, T0 + 1, 180_000, counted = true)
        val uncounted = LoggedPlay(ember, T0 + 2, 0, counted = false)
        (imports + heard + uncounted).forEach { store.put310(it) }

        store.upgrade(2, PlaysSchema.VERSION)

        assertEquals(imports.map { it.copy(imported = true) } + heard + uncounted, store.all())
    }

    @Test
    fun `the step runs again after a downgrade without harm, and marks the imports 3_1_0 made meanwhile`() = runBlocking {
        val store = store()
        val kept = LoggedPlay(salt, T0, 200_000, counted = true, start = picked, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.FINISHED, 210_000))
        store.put(kept)
        // 3.1.0 keeps the file as it is (its version set back to 2) and writes an import's plays as it always did.
        val imported = LoggedPlay(ember, T0 + 5, 0, counted = true)
        store.put310(imported)

        store.upgrade(2, PlaysSchema.VERSION)

        assertEquals(PLAYS_310 + NEW_COLUMNS, store.columns("plays"))
        assertEquals(listOf(kept, imported.copy(imported = true)), store.all())
    }

    // Writing a play again

    @Test
    fun `a play keeps how it began and its time zone as first written, and how it ended as last written`() = runBlocking {
        val store = store()
        val begun = LoggedPlay(salt, T0, 15_000, counted = false, start = picked, zoneOffsetMinutes = 120)
        store.put(begun, OpenState(210_000, 15_000, listOf(HeardSpan(0, 15_000))))
        // After a restart it goes on as the play kept, though the player now says less of how it began, and it ends.
        val ended = begun.copy(heardMs = 200_000, counted = true, end = PlayEnded(PlayEnd.FINISHED, 210_000))
        store.put(ended.copy(start = PlayStart(StartedBy.UNKNOWN, null, shuffled = true, songKey = null), zoneOffsetMinutes = 180))
        assertEquals(listOf(ended), store.all())

        // A restore of a backup that kept less (3.1.0's) takes nothing away; a later end is the one kept.
        store.putAll(listOf(LoggedPlay(SongRef(salt.path), T0, 10_000, counted = false)))
        assertEquals(listOf(ended), store.all())
        store.put(ended.copy(end = PlayEnded(PlayEnd.STOPPED, 1_000)))
        assertEquals(listOf(ended.copy(end = PlayEnded(PlayEnd.STOPPED, 1_000))), store.all())
    }

    @Test
    fun `a song key the first write lacked is filled in by a later one, the rest of how the play began kept`() = runBlocking {
        val store = store()
        // Kept before the library was read, so without the song's key; after a restart the play goes on, the key known.
        store.put(LoggedPlay(salt, T0, 15_000, counted = false, start = picked.copy(songKey = null), zoneOffsetMinutes = 120))
        store.put(LoggedPlay(salt, T0, 200_000, counted = true, start = PlayStart(StartedBy.UNKNOWN, SourceKind.SEARCH, shuffled = true, songKey = picked.songKey)))

        assertEquals(listOf(picked), store.all().map { it.start })
    }

    @Test
    fun `a play an import made stays marked when written again`() = runBlocking {
        val store = store()
        store.put(LoggedPlay(salt, T0, 0, counted = true, imported = true))
        store.put(LoggedPlay(salt, T0, 0, counted = true))
        assertEquals(listOf(LoggedPlay(salt, T0, 0, counted = true, imported = true)), store.all())
    }

    @Test
    fun `a backup reads every play with all it keeps, the short ones too, the oldest first`() = runBlocking {
        val store = store()
        val plays = listOf(
            LoggedPlay(salt, T0, 200_000, counted = true, start = picked, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.FINISHED, 210_000)),
            LoggedPlay(
                ember, T0 + 1_000, 400, counted = false,
                start = PlayStart(StartedBy.AUTO, SourceKind.PLAYLIST, shuffled = true, songKey = "ember ledger||night ferry|90"),
                zoneOffsetMinutes = -300, end = PlayEnded(PlayEnd.NEXT, 400),
            ),
            LoggedPlay(alder, T0 + 2_000, 0, counted = false, start = PlayStart(StartedBy.SUGGESTED, null, shuffled = false, songKey = null), zoneOffsetMinutes = 0, end = PlayEnded(PlayEnd.JUMP, 0)),
            LoggedPlay(alder, T0 + 3_000, 0, counted = true, imported = true),
            LoggedPlay(salt, T0 + 4_000, 30_000, counted = false),
        )
        store.putAll(plays.reversed())
        assertEquals(plays, store.all())
    }

    @Test
    fun `a damaged row reads leniently`() = runBlocking {
        val store = store()
        store.update(
            "INSERT INTO plays (path, started_at, media_id, heard_ms, counted, started_by, source, shuffled, zone_offset_min, song_key, ended_how, ended_at_ms, imported) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            salt.path, T0, 12, 5_000, 0, "sideways", "NOWHERE", "yes", 99_999, "k", "later", -40, 7,
        )
        store.update("INSERT INTO plays (path, started_at, heard_ms, counted, ended_how) VALUES (?, ?, ?, ?, ?)", ember.path, T0 + 1, 5_000, 0, "next")

        assertEquals(
            listOf(
                // A code this version does not know is unknown, a list's kind none, a time zone past 18 hours none, and
                // an end before the song's start its start.
                LoggedPlay(salt, T0, 5_000, counted = false, start = PlayStart(StartedBy.UNKNOWN, null, shuffled = false, songKey = "k"), end = PlayEnded(PlayEnd.UNKNOWN, 0), imported = true),
                // How it ended without where: no end.
                LoggedPlay(SongRef(ember.path), T0 + 1, 5_000, counted = false),
            ),
            store.all(),
        )
    }

    // What reads the log

    @Test
    fun `the totals leave out the short plays, so every list and count reads as 3_1_0's did`() = runBlocking {
        val logged = listOf(
            LoggedPlay(salt, T0, 200_000, counted = true, end = PlayEnded(PlayEnd.FINISHED, 210_000)),
            LoggedPlay(salt, T0 + 1_000, 2_000, counted = false, end = PlayEnded(PlayEnd.NEXT, 2_000)),
            LoggedPlay(ember, T0 + 2_000, 0, counted = true, imported = true),
            LoggedPlay(ember, T0 + 3_000, 1_000, counted = false),
        )
        val short = listOf(
            // The latest play of a song, with another id hint: the hint stays the one of its last logged play.
            LoggedPlay(SongRef(salt.path, 99), T0 + 4_000, 999, counted = false, end = PlayEnded(PlayEnd.NEXT, 999)),
            LoggedPlay(ember, T0 + 5_000, 0, counted = false, end = PlayEnded(PlayEnd.JUMP, 0)),
            // A song only ever skipped has no totals at all.
            LoggedPlay(alder, T0 + 6_000, 300, counted = false, end = PlayEnded(PlayEnd.BACK, 300)),
        )
        val now = store()
        now.putAll(logged + short)
        val before = store(version = 2)
        logged.forEach { before.put310(it) }

        assertEquals(before.totals310(), now.totals().toSet())
        assertEquals(before.totals310(T0 + 1_000, T0 + 4_000), now.totals(T0 + 1_000, T0 + 4_000).toSet())
        assertEquals(setOf(salt.path, ember.path), now.totals().map { it.song.path }.toSet())
    }

    @Test
    fun `the fake log keeps plays as plays_db does`() = runBlocking {
        val writes = listOf(
            LoggedPlay(salt, T0, 15_000, counted = false, start = picked, zoneOffsetMinutes = 120) to OpenState(210_000, 15_000, emptyList()),
            LoggedPlay(SongRef(salt.path), T0, 200_000, counted = true, start = PlayStart(StartedBy.UNKNOWN, null, true, null), zoneOffsetMinutes = 60, end = PlayEnded(PlayEnd.FINISHED, 210_000)) to null,
            LoggedPlay(ember, T0 + 1_000, 500, counted = false, end = PlayEnded(PlayEnd.NEXT, 500)) to null,
            LoggedPlay(ember, T0 + 2_000, 0, counted = true, imported = true) to null,
            LoggedPlay(ember, T0 + 2_000, 0, counted = true) to null,
            LoggedPlay(SongRef(alder.path, 5), T0 + 3_000, 300, counted = false) to null,
            // The song's key, missing at first, is filled in by a later write; how it began is not.
            LoggedPlay(alder, T0 + 4_000, 20_000, counted = false, start = PlayStart(StartedBy.NEXT, SourceKind.FOLDER, false, null)) to OpenState(200_000, 20_000, emptyList()),
            LoggedPlay(alder, T0 + 4_000, 30_000, counted = false, start = PlayStart(StartedBy.UNKNOWN, null, true, "alder smoke||harbour glass|100")) to null,
        )
        val fake = FakePlayLog()
        val sql = store()
        writes.forEach { (play, open) ->
            fake.put(play, open)
            sql.put(play, open)
        }

        assertEquals(sql.all(), fake.all())
        assertEquals(sql.totals().toSet(), fake.totals().toSet())
        assertEquals(sql.totals(T0 + 1_000, T0 + 3_000).toSet(), fake.totals(T0 + 1_000, T0 + 3_000).toSet())
    }

    // The interaction log (version 5)

    private val searched = Interaction(T0, InteractionKind.SEARCH, 180, words = "Harbour Glass")
    private val album = Interaction(T0 + 1_000, InteractionKind.OPEN, 180, Target(TargetKind.ALBUM, """["KJB Test Band","Harbour Glass"]"""))
    private val lyrics = Interaction(T0 + 2_000, InteractionKind.LYRICS, -300, Target(TargetKind.SONG, salt.path))

    @Test
    fun `a 3_1_1 file gains the interaction log empty, every play kept`() = runBlocking {
        val store = store(version = 3)
        val play = LoggedPlay(salt, T0, 200_000, counted = true, start = picked, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.FINISHED, 210_000))
        store.put(play)

        store.upgrade(3, PlaysSchema.VERSION)

        assertEquals(INTERACTION_COLUMNS, store.columns("interactions"))
        assertEquals(listOf(play), store.all())
        assertEquals(emptyList<Interaction>(), store.interactions())
        // A downgrade keeps the file; the next upgrade runs the step again without harm.
        store.note(album)
        store.upgrade(3, PlaysSchema.VERSION)
        assertEquals(listOf(album), store.interactions())
    }

    @Test
    fun `the interaction log is a step of its own after version 4, so a file at version 4 gains it too`() = runBlocking {
        val store = store(version = 4)
        assertEquals(emptySet<String>(), store.columns("interactions"))

        store.upgrade(4, PlaysSchema.VERSION)

        assertEquals(INTERACTION_COLUMNS, store.columns("interactions"))
    }

    @Test
    fun `an interaction reads back as noted, and a search noted again at its time takes the result tapped`() = runBlocking {
        val store = store()
        listOf(lyrics, searched, album).forEach { store.note(it) }
        assertEquals(listOf(searched, album, lyrics), store.interactions())

        val tapped = searched.copy(target = Target(TargetKind.SONG, ember.path))
        store.note(tapped)
        // Another kind at the same time is another interaction.
        val opened = Interaction(T0, InteractionKind.OPEN, 180, Target(TargetKind.PLAYLIST, "list-7"))
        store.note(opened)

        assertEquals(listOf(opened, tapped, album, lyrics), store.interactions())
    }

    @Test
    fun `the interactions between two bounds, from inclusive until exclusive`() = runBlocking {
        val store = store()
        listOf(searched, album, lyrics).forEach { store.note(it) }

        assertEquals(listOf(album), store.interactions(T0 + 1_000, T0 + 2_000))
        assertEquals(listOf(album, lyrics), store.interactions(from = T0 + 1))
        assertEquals(listOf(searched), store.interactions(until = T0 + 1_000))
    }

    @Test
    fun `forgetting a search's words keeps the search and its result, and touches nothing else`() = runBlocking {
        val store = store()
        val tapped = searched.copy(target = Target(TargetKind.ARTIST, "Kit Calloway"))
        val other = Interaction(T0 + 5_000, InteractionKind.SEARCH, 180, words = "Salt Lattice")
        val opened = Interaction(T0 + 5_000, InteractionKind.OPEN, 180, Target(TargetKind.ARTIST, "Kit Calloway"))
        listOf(tapped, other, opened).forEach { store.note(it) }

        store.forget(listOf(T0, T0 + 5_000))

        assertEquals(listOf(tapped.copy(words = null), opened, other.copy(words = null)), store.interactions())
    }

    @Test
    fun `a restore adds the interactions not there, one there kept as the phone has it`() = runBlocking {
        val store = store()
        // Removed from Recent searches on this phone since the backup was made.
        store.note(searched.copy(words = null))

        store.putAll(emptyList(), listOf(searched, album))
        store.putAll(emptyList(), listOf(searched, album))

        assertEquals(listOf(searched.copy(words = null), album), store.interactions())
    }

    @Test
    fun `a damaged interaction reads leniently, and one without a time or of an unknown kind is dropped`() = runBlocking {
        val store = store()
        val insert = "INSERT INTO interactions (at, kind, zone_offset_min, words, target, target_key) VALUES (?, ?, ?, ?, ?, ?)"
        store.update(insert, T0, "search", 99_999, "   ", "shelf", "x")
        store.update(insert, T0, "open", "east", null, "album", null)
        store.update(insert, T0 + 1, "lyrics", 60, null, "song", salt.path)
        store.update(insert, T0 + 2, "share", 60, null, "song", salt.path)
        store.update(insert, 0, "open", 60, null, "album", "a")
        store.update(insert, -5, "open", 60, null, "album", "a")

        assertEquals(
            listOf(
                // Blank words and a time zone past 18 hours are none; a target of a kind unknown here, or without its key, is none.
                Interaction(T0, InteractionKind.OPEN, null),
                Interaction(T0, InteractionKind.SEARCH, null),
                Interaction(T0 + 1, InteractionKind.LYRICS, 60, Target(TargetKind.SONG, salt.path)),
            ),
            store.interactions(),
        )
    }

    @Test
    fun `the fake log keeps interactions as plays_db does`() = runBlocking {
        val tapped = searched.copy(target = Target(TargetKind.FOLDER, "Music/Rips"))
        val fake = FakePlayLog()
        val sql = store()
        listOf(fake, sql).forEach { store ->
            listOf(lyrics, searched, album, tapped).forEach { store.note(it) }
            store.forget(listOf(T0, T0 + 1_000))
            store.putAll(emptyList(), listOf(searched, Interaction(T0 + 9_000, InteractionKind.OPEN, 0, Target(TargetKind.SONG, ember.path))))
        }

        assertEquals(sql.interactions(), fake.interactions())
        assertEquals(sql.interactions(T0 + 1_000, T0 + 9_000), fake.interactions(T0 + 1_000, T0 + 9_000))
    }

    private companion object {
        const val MUSIC: String = "/storage/emulated/0/Music/KJB test"
        const val T0: Long = 1_800_000_000_000L

        val PLAYS_310: Set<String> = setOf("path", "started_at", "media_id", "heard_ms", "counted")
        val OPEN_PLAYS_310: Set<String> = setOf("path", "started_at", "duration_ms", "position_ms", "spans")
        val NEW_COLUMNS: Set<String> = setOf("started_by", "source", "shuffled", "zone_offset_min", "song_key", "ended_how", "ended_at_ms", "imported")
        val INTERACTION_COLUMNS: Set<String> = setOf("at", "kind", "zone_offset_min", "words", "target", "target_key")

        /** 3.1.0's PlaysSchema.PUT. */
        const val PUT_310: String = "INSERT INTO plays (path, started_at, media_id, heard_ms, counted) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (path, started_at) DO UPDATE SET media_id = coalesce(excluded.media_id, media_id), " +
            "heard_ms = max(heard_ms, excluded.heard_ms), counted = max(counted, excluded.counted)"

        /** 3.1.0's PlaysSchema.TOTALS. */
        const val TOTALS_310: String = "SELECT path, " +
            "(SELECT media_id FROM plays AS latest WHERE latest.path = plays.path ORDER BY started_at DESC LIMIT 1), " +
            "COUNT(CASE WHEN counted != 0 THEN 1 END), MAX(CASE WHEN counted != 0 THEN started_at END), SUM(heard_ms) " +
            "FROM plays WHERE started_at >= CAST(? AS INTEGER) AND started_at < CAST(? AS INTEGER) GROUP BY path"
    }
}
