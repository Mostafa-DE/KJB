package app.lyrify.backup

import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.LoggedPlay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An import matched, previewed and written: the preview's counts and songs not found, the merge rules (never wipes: a
 * new name makes a playlist, the same name gets only the new songs, favourites are a union, play counts only go up),
 * all or nothing, and one Undo for all of it. Made-up names and paths only.
 */
class ImportPlanTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", album = "Harbour Glass", durationMs = 201_000, id = 11)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", album = "Harbour Glass", durationMs = 185_000, id = 12)
    private val water = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", durationMs = 240_000, id = 13)
    private val road = song("Music/Night Ferry/02 Salt Road.mp3", title = "Salt Road", artist = "Mira Solen", durationMs = 199_000, id = 14)
    private val phone = listOf(lantern, bell, water, road)

    private val gone = ImportSong("/sdcard/Music/Gone/03 Glass Steps.mp3", "/sdcard/Music/Gone/03 Glass Steps.mp3", info = "Odo Brannick - Glass Steps", durationMs = 175_000)
    private val stream = ImportSong("https://radio.example/stream", null)

    // The preview

    @Test
    fun `the preview counts what the files hold, kind by kind, those with none left out`() {
        val bundle = ImportBundle(
            playlists = listOf(ImportPlaylist("Road Trip", listOf(line(lantern), gone)), ImportPlaylist("Evening", listOf(stream))),
            favourites = listOf(line(water), line(water), gone),
            plays = listOf(ImportPlays(line(road), 4), ImportPlays(gone, 2)),
        )

        assertEquals(mapOf(ImportKind.PLAYLISTS to 2, ImportKind.FAVOURITES to 2, ImportKind.PLAYS to 2), ImportPlan(bundle, phone).counts)
        assertEquals(mapOf(ImportKind.PLAYLISTS to 1), ImportPlan(ImportBundle(listOf(ImportPlaylist("Evening", listOf(stream)))), phone).counts)
    }

    @Test
    fun `the songs not found are those of the chosen kinds, each once, in their order`() {
        val bundle = ImportBundle(
            playlists = listOf(ImportPlaylist("Road Trip", listOf(line(lantern), gone, stream, gone))),
            favourites = listOf(gone),
            plays = listOf(ImportPlays(ImportSong("/x/Unknown.mp3", "/x/Unknown.mp3"), 3)),
        )
        val plan = ImportPlan(bundle, phone)

        assertEquals(listOf(gone, stream, ImportSong("/x/Unknown.mp3", "/x/Unknown.mp3")), plan.missing(ImportKind.entries.toSet()))
        assertEquals(listOf(gone), plan.missing(setOf(ImportKind.FAVOURITES)))
        assertEquals(emptyList<ImportSong>(), plan.missing(emptySet()))
    }

    @Test
    fun `songs are found through SongMatcher, from another phone's paths and tags`() = runTest {
        val bundle = ImportBundle(
            listOf(
                ImportPlaylist(
                    "Mix",
                    listOf(
                        ImportSong("/sdcard/Music/Harbour Glass/01 Paper Lantern.mp3", "/sdcard/Music/Harbour Glass/01 Paper Lantern.mp3"),
                        ImportSong("C:\\Music\\Night Ferry\\01 Low Water.mp3", "Music/Night Ferry/01 Low Water.mp3"),
                        ImportSong("", null, title = "Salt Road", artist = "Mira Solen", durationMs = 200_000),
                    ),
                ),
            ),
        )
        val stores = FakeStores()

        ImportPlan(bundle, phone).apply(setOf(ImportKind.PLAYLISTS), stores, ids(), NOW, UNTITLED)

        assertEquals(listOf(lantern.ref, water.ref, road.ref), stores.state.playlists.single().songs)
    }

    // Merge rules: never wipes

    @Test
    fun `a new name makes a playlist with the songs found in the file's order, a song twice too`() {
        val (state, undo) = ImportPlan.merge(PlaylistsState(), listOf("Road Trip" to listOf(lantern.ref, water.ref, lantern.ref)), emptyList(), ids(), NOW)

        assertEquals(listOf(Playlist("p1", "Road Trip", listOf(lantern.ref, water.ref, lantern.ref), NOW)), state.playlists)
        assertEquals(ImportUndo(created = mapOf("p1" to listOf(lantern.ref, water.ref, lantern.ref))), undo)
    }

    @Test
    fun `the same name, case and spaces aside, gets only the songs it does not hold, each once, at its end`() {
        val mine = Playlist("mine", "Road Trip", listOf(water.ref, bell.ref), 1)
        val (state, undo) = ImportPlan.merge(
            PlaylistsState(listOf(mine)),
            listOf(" road trip " to listOf(lantern.ref, water.ref, road.ref, lantern.ref)),
            emptyList(),
            ids(),
            NOW,
        )

        assertEquals(listOf(mine.copy(songs = listOf(water.ref, bell.ref, lantern.ref, road.ref))), state.playlists)
        assertEquals(ImportUndo(added = mapOf("mine" to listOf(lantern.ref, road.ref))), undo)
    }

    @Test
    fun `a playlist that would not change is left as it is, and one with no song found is not made`() {
        val mine = Playlist("mine", "Road Trip", listOf(water.ref, lantern.ref), 1)
        val before = PlaylistsState(listOf(mine))

        val (state, undo) = ImportPlan.merge(before, listOf("Road Trip" to listOf(lantern.ref), "Nothing here" to emptyList()), emptyList(), ids(), NOW)

        assertEquals(before, state)
        assertTrue(undo.isEmpty)
    }

    @Test
    fun `two playlists of one name in one import make one, the second adding its new songs`() {
        val (state, undo) = ImportPlan.merge(PlaylistsState(), listOf("Mix" to listOf(lantern.ref), "MIX" to listOf(lantern.ref, bell.ref)), emptyList(), ids(), NOW)

        assertEquals(listOf(Playlist("p1", "Mix", listOf(lantern.ref, bell.ref), NOW)), state.playlists)
        // Made by the import: every song in it is the import's.
        assertEquals(ImportUndo(created = mapOf("p1" to listOf(lantern.ref, bell.ref))), undo)
    }

    @Test
    fun `a name KJB's own lists use gets (2), and a second import finds it there`() {
        val reserved = setOf("Favourites", "Most played")
        val (first, _) = ImportPlan.merge(PlaylistsState(), listOf("favourites" to listOf(lantern.ref)), emptyList(), ids(), NOW, reserved)
        val (second, undo) = ImportPlan.merge(first, listOf("Favourites" to listOf(lantern.ref, bell.ref)), emptyList(), ids("q"), NOW, reserved)

        assertEquals(listOf(Playlist("p1", "favourites (2)", listOf(lantern.ref, bell.ref), NOW)), second.playlists)
        assertEquals(ImportUndo(added = mapOf("p1" to listOf(bell.ref))), undo)
    }

    @Test
    fun `everything else stays, playlists a newer KJB wrote too`() {
        val other = Playlist("other", "Workout", listOf(road.ref), 1)
        val newer = JsonPrimitive("a newer playlist")
        val before = PlaylistsState(listOf(other), favouriteSongs = listOf(road.ref), unknownPlaylists = listOf(newer))

        val (state, _) = ImportPlan.merge(before, listOf("Road Trip" to listOf(lantern.ref)), listOf(water.ref), ids(), NOW)

        assertEquals(listOf(other, Playlist("p1", "Road Trip", listOf(lantern.ref), NOW)), state.playlists)
        assertEquals(listOf(newer), state.unknownPlaylists)
        assertEquals(listOf(water.ref, road.ref), state.favouriteSongs)
    }

    @Test
    fun `favourites are a union, never unfavouriting`() {
        val before = PlaylistsState(favouriteSongs = listOf(road.ref, water.ref))

        val (state, undo) = ImportPlan.merge(before, emptyList(), listOf(water.ref, lantern.ref, lantern.ref), ids(), NOW)

        assertEquals(listOf(lantern.ref, road.ref, water.ref), state.favouriteSongs)
        assertEquals(ImportUndo(favourites = listOf(lantern.ref)), undo)
    }

    // Play counts only go up

    @Test
    fun `a count lower than the other app's gets the difference as counted plays, at its last play, a millisecond apart`() {
        val log = listOf(LoggedPlay(water.ref, LAST, 240_000, counted = true), LoggedPlay(water.ref, 5, 1_000, counted = false))

        val added = ImportPlan.raised(log, listOf(water to ImportPlays(line(water), 3, lastPlayedAt = LAST)), NOW)

        // KJB counted 1 of them; LAST is taken by its own play, so the new ones go just before it.
        assertEquals(listOf(LoggedPlay(water.ref, LAST - 1, 0, counted = true), LoggedPlay(water.ref, LAST - 2, 0, counted = true)), added)
    }

    @Test
    fun `a count never goes down, an unknown last play is the import's time, and one song named twice takes the higher count`() {
        val log = List(5) { LoggedPlay(road.ref, 100L + it, 1_000, counted = true) }

        val added = ImportPlan.raised(log, listOf(road to ImportPlays(line(road), 2), lantern to ImportPlays(line(lantern), 1), lantern to ImportPlays(gone, 2, lastPlayedAt = NOW + 999_999)), NOW)

        // Road has 5 here against 2 there: nothing. A last play in the future is not believed.
        assertEquals(listOf(LoggedPlay(lantern.ref, NOW, 0, counted = true), LoggedPlay(lantern.ref, NOW - 1, 0, counted = true)), added)
    }

    @Test
    fun `a count past the cap adds no more than the cap`() {
        val added = ImportPlan.raised(emptyList(), listOf(lantern to ImportPlays(line(lantern), Int.MAX_VALUE)), NOW)

        assertEquals(ImportPlan.MAX_PLAYS_PER_SONG, added.size)
    }

    // Writing: all or nothing

    @Test
    fun `an import writes the playlists and favourites in one go, then the plays, and says what it did`() = runTest {
        val stores = FakeStores(PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref), 1))))
        val bundle = ImportBundle(
            playlists = listOf(ImportPlaylist("Evening", listOf(line(water), line(bell))), ImportPlaylist(null, listOf(line(lantern), gone))),
            favourites = listOf(line(road)),
            plays = listOf(ImportPlays(line(road), 2, lastPlayedAt = LAST)),
        )

        val outcome = ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED)!!

        assertEquals(1, stores.writes)
        assertEquals(listOf(Playlist("mine", "Evening", listOf(water.ref, bell.ref), 1), Playlist("p1", UNTITLED, listOf(lantern.ref), NOW)), stores.state.playlists)
        assertEquals(listOf(road.ref), stores.state.favouriteSongs)
        assertEquals(listOf(LoggedPlay(road.ref, LAST - 1, 0, true), LoggedPlay(road.ref, LAST, 0, true)), stores.log.plays.sortedBy { it.startedAt })
        assertEquals(ImportOutcome(created = 1, extended = 1, songsAdded = 1, favouritesAdded = 1, countsRaised = 1, undo = outcome.undo), outcome)
        assertEquals(2, outcome.playlists)
    }

    @Test
    fun `kinds left unticked are not written`() = runTest {
        val stores = FakeStores()
        val bundle = ImportBundle(listOf(ImportPlaylist("Mix", listOf(line(lantern)))), favourites = listOf(line(road)), plays = listOf(ImportPlays(line(road), 2)))

        ImportPlan(bundle, phone).apply(setOf(ImportKind.FAVOURITES), stores, ids(), NOW, UNTITLED)

        assertEquals(emptyList<Playlist>(), stores.state.playlists)
        assertEquals(listOf(road.ref), stores.state.favouriteSongs)
        assertEquals(emptyList<LoggedPlay>(), stores.log.plays)
    }

    @Test
    fun `when the plays cannot be written the playlists are taken back, and when the playlists cannot nothing is written`() = runTest {
        val before = PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref), 1)))
        val bundle = ImportBundle(listOf(ImportPlaylist("Evening", listOf(line(bell))), ImportPlaylist("Mix", listOf(line(lantern)))), plays = listOf(ImportPlays(line(road), 2)))

        val plays = FakeStores(before).apply { addFails = true }
        assertNull(ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), plays, ids(), NOW, UNTITLED))
        assertEquals(before, plays.state)

        val lists = FakeStores(before).apply { playlistsFail = true }
        assertNull(ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), lists, ids(), NOW, UNTITLED))
        assertEquals(emptyList<LoggedPlay>(), lists.log.plays)

        val unread = FakeStores(before).apply { log.failing = true }
        assertNull(ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), unread, ids(), NOW, UNTITLED))
        assertEquals(before, unread.state)
    }

    @Test
    fun `an import of what the phone already has changes nothing`() = runTest {
        val before = PlaylistsState(listOf(Playlist("mine", "Mix", listOf(lantern.ref), 1)), favouriteSongs = listOf(road.ref))
        val stores = FakeStores(before)

        val outcome = ImportPlan(ImportBundle(listOf(ImportPlaylist("Mix", listOf(line(lantern), gone))), favourites = listOf(line(road))), phone)
            .apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED)!!

        assertFalse(outcome.changed)
        assertEquals(before, stores.state)
    }

    // One Undo for all of it

    @Test
    fun `Undo takes back the playlists made, the songs added, the favourites and the plays, and nothing of the user's`() = runTest {
        val mine = Playlist("mine", "Evening", listOf(water.ref, bell.ref), 1)
        val before = PlaylistsState(listOf(mine), favouriteSongs = listOf(water.ref))
        val own = LoggedPlay(road.ref, 50, 199_000, counted = true)
        val stores = FakeStores(before).apply { log.plays += own }
        val bundle = ImportBundle(
            playlists = listOf(ImportPlaylist("Evening", listOf(line(bell), line(lantern), line(road))), ImportPlaylist("Mix", listOf(line(lantern)))),
            favourites = listOf(line(water), line(lantern)),
            plays = listOf(ImportPlays(line(road), 3)),
        )
        val outcome = ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED)!!
        // Meanwhile the user adds a copy of a song the import added.
        stores.state = stores.state.add("mine", listOf(lantern.ref))

        assertTrue(ImportPlan.undo(outcome.undo, stores))

        assertEquals(PlaylistsState(listOf(mine.copy(songs = listOf(water.ref, bell.ref, lantern.ref))), favouriteSongs = listOf(water.ref)), stores.state)
        assertEquals(listOf(own), stores.log.plays)
    }

    @Test
    fun `Undo removes only what is still there, and a failed Undo keeps what is left for a retry or the next start`() = runTest {
        val undo = ImportUndo(created = mapOf("gone" to listOf(bell.ref)), added = mapOf("mine" to listOf(lantern.ref, road.ref)), plays = listOf(LoggedPlay(road.ref, NOW, 0, true)))
        val state = PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref, road.ref), 1)))

        assertEquals(PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref), 1))), ImportPlan.unmerge(state, undo))

        val stores = FakeStores(state).apply { log.plays += undo.plays; playlistsFail = true }
        assertFalse(ImportPlan.undo(undo, stores))
        assertEquals(undo.copy(plays = emptyList()), stores.journal)
        stores.playlistsFail = false
        assertTrue(ImportPlan.recover(stores))
        assertEquals(PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref), 1))), stores.state)
        assertNull(stores.journal)
    }

    // Queues: saved queues, never the one playing

    @Test
    fun `queues come in as saved queues named for the import, at their song and second, never playing, and not twice`() = runTest {
        val stores = FakeStores()
        stores.queues = QueueBook().play(listOf(road.ref), 0, QueueSource(SourceKind.SONGS, "all", "Songs"), 1).book
        val playing = stores.queues.currentId
        val bundle = ImportBundle(queues = listOf(ImportQueue(listOf(line(lantern), gone, line(water)), index = 2, positionMs = 42_000)))

        val outcome = ImportPlan(bundle, phone).apply(setOf(ImportKind.QUEUES), stores, ids(), NOW, UNTITLED, queueName = { QUEUE })!!

        val made = stores.queues.queue(outcome.undo.queues.keys.single())!!
        assertEquals(listOf(lantern.ref, water.ref), made.items.map { it.song })
        assertEquals(QUEUE, made.name)
        assertEquals(1, made.index)
        assertEquals(42_000L, made.positionMs)
        assertTrue(made.id in stores.queues.saved)
        assertEquals(playing, stores.queues.currentId)
        assertEquals(1, outcome.queuesAdded)
        assertEquals(0, ImportPlan(bundle, phone).apply(setOf(ImportKind.QUEUES), stores, ids(), NOW, UNTITLED, queueName = { QUEUE })!!.queuesAdded)

        assertTrue(ImportPlan.undo(outcome.undo, stores))
        assertNull(stores.queues.queue(made.id))
        assertEquals(playing, stores.queues.currentId)
    }

    @Test
    fun `a queue whose song it was at is not here starts at its first song, and a queue with no song here is not made`() {
        val (book, made) = ImportPlan.mergeQueues(QueueBook(), listOf(PlacedQueue(QUEUE, listOf(lantern.ref, water.ref), -1, 9_000L), PlacedQueue(QUEUE, emptyList(), 0, 0L)), NOW)

        val queue = book.queue(made.keys.single())!!
        assertEquals(0, queue.index)
        assertEquals(0L, queue.positionMs)
        assertEquals(book, ImportPlan.unqueue(book, emptyMap()))
        assertEquals(QueueBook(nextId = book.nextId, nextUid = book.nextUid), ImportPlan.unqueue(book, made))
    }

    @Test
    fun `an Undo that fails part way can be tried again without taking a song the user added since`() = runTest {
        val stores = FakeStores(PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref), 1))))
        val bundle = ImportBundle(listOf(ImportPlaylist("Evening", listOf(line(lantern)))), queues = listOf(ImportQueue(listOf(line(road)))))
        val outcome = ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED, queueName = { QUEUE })!!
        stores.state = stores.state.add("mine", listOf(lantern.ref))

        stores.queuesFail = true
        assertFalse(ImportPlan.undo(outcome.undo, stores))
        stores.queuesFail = false
        assertTrue(ImportPlan.undo(outcome.undo, stores))

        assertEquals(listOf(water.ref, lantern.ref), stores.state.playlist("mine")!!.songs)
        assertTrue(stores.queues.queues.isEmpty())
    }

    @Test
    fun `when the queues cannot be written the playlists are taken back`() = runTest {
        val stores = FakeStores().apply { queuesFail = true }
        val bundle = ImportBundle(listOf(ImportPlaylist("Mix", listOf(line(lantern)))), queues = listOf(ImportQueue(listOf(line(water)))))

        assertNull(ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED, queueName = { QUEUE }))
        assertEquals(PlaylistsState(), stores.state)
    }


    // Undo takes back the import's own songs only (review panel-im1, finding 1)

    @Test
    fun `Undo takes off the import's own copy of a song, so the songs the user added since stay in their order`() = runTest {
        val stores = FakeStores(PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref), 1))))
        val outcome = ImportPlan(ImportBundle(listOf(ImportPlaylist("Evening", listOf(line(lantern))))), phone).apply(setOf(ImportKind.PLAYLISTS), stores, ids(), NOW, UNTITLED)!!
        stores.state = stores.state.add("mine", listOf(bell.ref, lantern.ref))

        assertTrue(ImportPlan.undo(outcome.undo, stores))

        assertEquals(listOf(water.ref, bell.ref, lantern.ref), stores.state.playlist("mine")!!.songs)
    }

    @Test
    fun `a playlist the import made keeps what the user put in it since, and goes only when nothing of the user's is left`() = runTest {
        val stores = FakeStores()
        val bundle = ImportBundle(listOf(ImportPlaylist("Mix", listOf(line(lantern), line(water))), ImportPlaylist("Other", listOf(line(road)))))
        val outcome = ImportPlan(bundle, phone).apply(setOf(ImportKind.PLAYLISTS), stores, ids(), NOW, UNTITLED)!!
        val mix = outcome.undo.created.keys.first()
        stores.state = stores.state.add(mix, listOf(bell.ref)).removeAt(mix, setOf(1)).rename(mix, "My mix")

        assertTrue(ImportPlan.undo(outcome.undo, stores))

        assertEquals(listOf(Playlist(mix, "My mix", listOf(bell.ref), NOW)), stores.state.playlists)
    }

    @Test
    fun `a queue the import made keeps the songs the user added to it, an untouched one goes, and one playing stays`() = runTest {
        val stores = FakeStores()
        val bundle = ImportBundle(queues = listOf(ImportQueue(listOf(line(lantern), line(water))), ImportQueue(listOf(line(road))), ImportQueue(listOf(line(bell)))))
        val outcome = ImportPlan(bundle, phone).apply(setOf(ImportKind.QUEUES), stores, ids(), NOW, UNTITLED, queueName = { QUEUE })!!
        val (first, second, third) = outcome.undo.queues.keys.toList()
        stores.queues = stores.queues.addTo(first, listOf(road.ref))!!.book.resume(third, NOW).book

        assertTrue(ImportPlan.undo(outcome.undo, stores))

        assertEquals(listOf(road.ref), stores.queues.queue(first)!!.items.map { it.song })
        assertNull(stores.queues.queue(second))
        assertEquals(listOf(bell.ref), stores.queues.queue(third)!!.items.map { it.song })
    }

    // Play counts: a cap on them all (finding 4)

    @Test
    fun `play counts stop at 200,000 plays in all, a whole song at a time, and never before the first millisecond`() {
        val many = List(30) { at -> song("Music/Many/$at.mp3", title = "Song $at", id = 1_000L + at) }
        val added = ImportPlan.raised(emptyList(), many.map { it to ImportPlays(line(it), 10_000) }, NOW)

        assertEquals(ImportPlan.MAX_IMPORTED_PLAYS, added.size)
        assertEquals(20, added.distinctBy { it.song.path }.size)
        assertTrue(ImportPlan.raised(emptyList(), listOf(lantern to ImportPlays(line(lantern), 10, lastPlayedAt = 4)), NOW).all { it.startedAt >= 1 })
    }

    // All or nothing across the stores, through a journal kept on storage (finding 5)

    @Test
    fun `an import keeps what it wrote in a journal until it is done, so one the app's death cuts short is taken back on the next start`() = runTest {
        val before = PlaylistsState(listOf(Playlist("mine", "Evening", listOf(water.ref), 1)))
        val stores = FakeStores(before).apply { dieAtQueues = true }
        val bundle = ImportBundle(listOf(ImportPlaylist("Evening", listOf(line(bell)))), queues = listOf(ImportQueue(listOf(line(road)))))

        assertTrue(runCatching { ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED, queueName = { QUEUE }) }.isFailure)
        assertEquals(ImportUndo(added = mapOf("mine" to listOf(bell.ref))), stores.journal)

        stores.dieAtQueues = false
        assertTrue(ImportPlan.recover(stores))
        assertEquals(before, stores.state)
        assertNull(stores.journal)
    }

    @Test
    fun `a finished import leaves no journal, and a failed one that cannot be taken back returns what is left to Undo`() = runTest {
        val done = FakeStores()
        ImportPlan(ImportBundle(listOf(ImportPlaylist("Mix", listOf(line(lantern))))), phone).apply(ImportKind.entries.toSet(), done, ids(), NOW, UNTITLED)
        assertNull(done.journal)

        val stores = FakeStores().apply { addFails = true; playlistsFailAfter = 1 }
        val bundle = ImportBundle(listOf(ImportPlaylist("Mix", listOf(line(lantern)))), plays = listOf(ImportPlays(line(road), 2)))
        val outcome = ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED)!!

        assertFalse(outcome.complete)
        assertEquals(ImportUndo(created = mapOf("p1" to listOf(lantern.ref))), outcome.undo)
        assertEquals(outcome.undo, stores.journal)
    }

    @Test
    fun `an Undo the app's death cuts short is finished on the next start`() = runTest {
        val stores = FakeStores()
        val bundle = ImportBundle(listOf(ImportPlaylist("Mix", listOf(line(lantern)))), plays = listOf(ImportPlays(line(road), 2)))
        val outcome = ImportPlan(bundle, phone).apply(ImportKind.entries.toSet(), stores, ids(), NOW, UNTITLED)!!
        stores.dieAtPlaylists = true

        assertTrue(runCatching { ImportPlan.undo(outcome.undo, stores) }.isFailure)
        stores.dieAtPlaylists = false
        assertTrue(ImportPlan.recover(stores))

        assertEquals(PlaylistsState(), stores.state)
        assertEquals(emptyList<LoggedPlay>(), stores.log.plays)
        assertNull(stores.journal)
    }

    @Test
    fun `the journal keeps an Undo whole on storage, and what is not one reads as none`() {
        val undo = ImportUndo(
            created = mapOf("p1" to listOf(lantern.ref, SongRef("/storage/emulated/0/Music/x.mp3"))),
            added = mapOf("mine" to listOf(bell.ref)),
            favourites = listOf(road.ref),
            plays = listOf(LoggedPlay(road.ref, NOW, 0, counted = true)),
            queues = mapOf("q3" to listOf(7L, 8L)),
        )

        assertEquals(undo, ImportJournal.decode(ImportJournal.encode(undo)))
        listOf("", "not json", "[]", """{"created":"x"}""").forEach { assertNull(it, ImportJournal.decode(it)) }
    }

    // Musicolet queue names (finding 7)

    @Test
    fun `a queue keeps its name in the other app when it has one`() = runTest {
        val stores = FakeStores()
        val bundle = ImportBundle(queues = listOf(ImportQueue(listOf(line(lantern)), name = "Morning"), ImportQueue(listOf(line(road)))))

        val outcome = ImportPlan(bundle, phone).apply(setOf(ImportKind.QUEUES), stores, ids(), NOW, UNTITLED, queueName = { name -> name?.let { "Musicolet: $it" } ?: QUEUE })!!

        assertEquals(listOf("Musicolet: Morning", QUEUE), outcome.undo.queues.keys.map { stores.queues.queue(it)!!.name })
    }

    private fun line(song: Song) = ImportSong(song.path, song.path)

    private fun ids(prefix: String = "p"): () -> String {
        var next = 0
        return { "$prefix${++next}" }
    }

    private class FakeStores(var state: PlaylistsState = PlaylistsState(), val log: FakePlayLog = FakePlayLog()) : ImportStores {
        var queues: QueueBook = QueueBook()
        var journal: ImportUndo? = null
        var playlistsFail = false
        /** Playlist writes that work before they fail. */
        var playlistsFailAfter = Int.MAX_VALUE
        var queuesFail = false
        var addFails = false
        /** The app dies at this store's next write. */
        var dieAtQueues = false
        var dieAtPlaylists = false
        var writes = 0

        override suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState): Boolean {
            if (dieAtPlaylists) throw IllegalStateException("the app died")
            if (playlistsFail || writes >= playlistsFailAfter) return false
            writes++
            state = change(state)
            return true
        }

        override suspend fun updateQueues(change: (QueueBook) -> QueueBook): Boolean {
            if (dieAtQueues) throw IllegalStateException("the app died")
            if (queuesFail) return false
            queues = change(queues)
            return true
        }

        override suspend fun journal(undo: ImportUndo?): Boolean {
            journal = undo?.takeUnless { it.isEmpty }
            return true
        }

        override suspend fun journaled(): ImportUndo? = journal

        override suspend fun plays(): List<LoggedPlay>? = log.all()

        override suspend fun addPlays(plays: List<LoggedPlay>): Boolean = !addFails && log.putAll(plays)

        override suspend fun removePlays(plays: List<LoggedPlay>): Boolean = log.removeAll(plays)
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000L
        const val LAST: Long = 1_790_000_000_000L
        const val UNTITLED: String = "Imported playlist"
        const val QUEUE: String = "Musicolet queue"
    }
}
