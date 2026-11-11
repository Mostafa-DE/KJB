package app.lyrify.backup

import app.lyrify.audio.EqProfile
import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqSound
import app.lyrify.audio.OutputKey
import app.lyrify.audio.OutputKind
import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.bell
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.lantern
import app.lyrify.backup.BackupFixtures.newBell
import app.lyrify.backup.BackupFixtures.newLantern
import app.lyrify.backup.BackupFixtures.newLibrary
import app.lyrify.backup.BackupFixtures.newWater
import app.lyrify.backup.BackupFixtures.water
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.SongResolver
import app.lyrify.player.Repeat
import app.lyrify.tags.CoversTagsMemory
import app.lyrify.tones.TonesState
import app.lyrify.library.song
import app.lyrify.player.Bookmark
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueItem
import app.lyrify.player.QueueSource
import app.lyrify.player.SongState
import app.lyrify.player.SourceKind
import app.lyrify.settings.BackupSettings
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.NavSettings
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.LoggedPlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restore against the stores of a made-up phone: what replaces, what merges, a new phone's other paths, songs not
 * found, a second restore, and a section that fails. Made-up names only.
 */
class BackupRestoreTest {
    private var ids = 0
    private val newId = { "new${++ids}" }
    private val all = Section.entries.toSet()

    private fun plan(library: List<Song> = BackupFixtures.library, contents: BackupContents = contents()) = RestorePlan(contents, library)

    private fun restore(stores: MemoryStores, library: List<Song> = BackupFixtures.library, chosen: Set<Section> = all): RestoreResult =
        runBlocking { plan(library).apply(chosen, stores, newId) }

    // Each section on an empty phone

    @Test
    fun `on an empty phone every section comes back as it was`() {
        val stores = MemoryStores()

        val result = restore(stores)

        assertEquals(Section.entries to emptyList<Section>(), result.restored to result.failed)
        assertEquals(BackupFixtures.playlists, stores.playlists)
        // The queue playing is playing again, at its song and second.
        assertEquals(BackupFixtures.queues, stores.queues)
        assertEquals(Repeat.ALL, stores.repeat)

        assertEquals(BackupFixtures.plays.toSet(), stores.log.plays.toSet())
        assertEquals(mapOf(water.path to 600_000L), stores.songState.places)
        assertEquals(listOf(30_000L to "Chorus", 95_000L to ""), stores.songState.bookmarksOf(lantern.path).map { it.positionMs to it.note })
        assertEquals(BackupFixtures.equalizer, stores.equalizer)
        assertEquals(BackupFixtures.ledger.entries, stores.ledger.entries)
        assertEquals(BackupFixtures.settings.copy(backup = BackupSettings(), nav = NavSettings(setupDone = false)), stores.settings)
        assertEquals(BackupFixtures.songState, stores.songState)
        assertEquals(true, stores.aiLyrics)
        assertEquals(BackupFixtures.duplicates, stores.duplicates)
        assertEquals(BackupFixtures.keptApart, stores.keptApart)
        assertEquals(BackupFixtures.searches, stores.searches)
    }

    // Replace and merge

    @Test
    fun `settings and the equalizer replace what is there, but the phone keeps its own backup folder and setup`() {
        val folder = BackupSettings(automatic = true, folderUri = "content://com.android.externalstorage.documents/tree/primary%3AKJB")
        val mine = Settings(playback = PlaybackSettings(countPlayAfterPercent = 30), library = LibrarySettings(showFileNames = true), backup = folder, nav = NavSettings(setupDone = true))
        val car = EqProfiles(profiles = mapOf(OutputKey(OutputKind.CAR) to EqProfile(EqSound(enabled = true))))
        val stores = MemoryStores(settings = mine, equalizer = car)

        restore(stores, chosen = setOf(Section.SETTINGS, Section.EQUALIZER))

        assertEquals(BackupFixtures.settings.copy(backup = folder, nav = NavSettings(setupDone = true)), stores.settings)
        assertEquals(BackupFixtures.equalizer, stores.equalizer)
    }

    @Test
    fun `playlists, queues, plays, bookmarks and lyrics choices are added to what is there`() {
        val other = song("Music/Salt Road/01 Salt Road.mp3", title = "Salt Road", artist = "Mira Solen", durationMs = 199_000, id = 14)
        val library = BackupFixtures.library + other
        val mix = Playlist("mine", "Kitchen", listOf(other.ref), createdAt = T0 - 5)
        val queue = Queue("q1", QueueSource(SourceKind.SONGS, "songs", "Songs"), listOf(QueueItem(1, other.ref, 0)), updatedAt = T0)
        val myPlay = LoggedPlay(other.ref, T0 - 1_000, 100_000, counted = true)
        val myMark = Bookmark(7, 10_000, "Intro")
        val myEntry = LedgerEntry(other.path, "Salt Road", "Mira Solen", null, 199_000, LyricsStatus.SYNCED, updatedAt = T0)
        val stores = MemoryStores(
            playlists = PlaylistsState(listOf(mix), favouriteSongs = listOf(other.ref)),
            queues = QueueBook(listOf(queue), currentId = "q1", nextId = 2, nextUid = 2),
            log = FakePlayLog(listOf(myPlay)),
            songState = SongState(places = mapOf(other.path to 400_000L), bookmarks = mapOf(lantern.path to listOf(myMark)), nextBookmarkId = 8),
            ledger = LedgerState(entries = mapOf(other.path to myEntry)),
        )

        restore(stores, library)

        assertEquals(listOf("Kitchen", "Road trip", "Night drive"), stores.playlists.playlists.map { it.name })
        assertEquals(listOf(other.ref, water.ref, lantern.ref), stores.playlists.favouriteSongs)
        assertEquals("q1", stores.queues.currentId)
        assertEquals(4, stores.queues.queues.size)
        assertEquals((1L..6L).toList(), stores.queues.queues.flatMap { q -> q.items.map { it.uid } }.sorted())
        assertEquals(4, stores.queues.queues.map { it.id }.distinct().size)
        assertEquals((BackupFixtures.plays + myPlay).toSet(), stores.log.plays.toSet())
        assertEquals(mapOf(other.path to 400_000L, water.path to 600_000L), stores.songState.places)
        assertEquals(listOf(10_000L, 30_000L, 95_000L), stores.songState.bookmarksOf(lantern.path).map { it.positionMs })
        assertEquals(3, stores.songState.bookmarksOf(lantern.path).map { it.id }.distinct().size)
        assertEquals(myEntry, stores.ledger.entries[other.path])
        assertEquals(4, stores.ledger.entries.size)
    }

    @Test
    fun `a playlist with a name already there gets (restored), and the next one a number`() {
        val stores = MemoryStores(
            PlaylistsState(
                listOf(
                    Playlist("a", "Road trip", listOf(water.ref), createdAt = T0 - 2),
                    Playlist("b", "road trip (restored)", emptyList(), createdAt = T0 - 1),
                    Playlist("c", "Night drive", listOf(lantern.ref), createdAt = T0 - 1),
                ),
            ),
        )

        restore(stores, chosen = setOf(Section.PLAYLISTS))

        assertEquals(listOf("Road trip", "road trip (restored)", "Night drive", "Road trip (restored) (2)", "Night drive (restored)"), stores.playlists.playlists.map { it.name })
    }

    @Test
    fun `a lyrics choice the phone has already, the user's own above all, is never replaced`() {
        val mine = LedgerEntry(lantern.path, "Paper Lantern", "Kit Calloway", null, 201_000, LyricsStatus.EXISTING, updatedAt = T0 + 9, mine = true)
        val stores = MemoryStores(ledger = LedgerState(entries = mapOf(lantern.path to mine)))

        restore(stores, chosen = setOf(Section.LYRICS))

        assertEquals(mine, stores.ledger.entries[lantern.path])
        assertEquals(BackupFixtures.ledger.entries[bell.path], stores.ledger.entries[bell.path])
    }

    @Test
    fun `with Recent full, a restore keeps every saved queue, the phone's and the backup's`() {
        val source = QueueSource(SourceKind.SONGS, "songs", "Songs")
        val other = song("Music/Salt Road/01 Salt Road.mp3", title = "Salt Road", id = 14)
        // Ten recent queues, the first holding the songs of the backup's saved queue, and a saved one.
        val recent = (1..10).map { n -> Queue("q$n", source, listOf(QueueItem(n.toLong(), if (n == 1) water.ref else SongRef(VOLUME_PHONE + "Music/r$n.mp3"))), updatedAt = T0) }
        val mine = Queue("q11", source, listOf(QueueItem(11, other.ref)), name = "Mine", updatedAt = T0)
        val stores = MemoryStores(queues = QueueBook(recent + mine, recent = recent.map { it.id }, saved = listOf("q11"), nextId = 12, nextUid = 12))
        // The backup saves a queue of the songs of one of its recent queues.
        val backup = contents().let { it.copy(queues = it.queues!!.let { book -> book.copy(queues = book.queues + book.queue("q1")!!.copy(id = "q9", name = "Kept"), saved = book.saved + "q9") }) }

        runBlocking { plan(BackupFixtures.library + other, backup).apply(setOf(Section.QUEUES), stores, newId) }

        val book = stores.queues
        assertEquals(recent.map { it.id }, book.recent)
        assertEquals(listOf("Mine", "Ferry songs", "Kept"), book.saved.map { book.queue(it)!!.name })
        assertEquals(listOf(listOf(other.ref), listOf(water.ref), listOf(lantern.ref, bell.ref)), book.saved.map { id -> book.queue(id)!!.items.map { it.song } })
    }

    @Test
    fun `places past the cap are left out and counted, never pushing out the phone's own`() {
        val mine = (1..SongState.MAX_PLACES).associate { n -> "${VOLUME_PHONE}Music/p$n.mp3" to n * 1_000L }
        val stores = MemoryStores(songState = SongState(places = mine))

        val result = restore(stores, chosen = setOf(Section.BOOKMARKS))

        assertEquals(mine, stores.songState.places)
        assertEquals(1, result.placesLeftOut)
        assertEquals(2, stores.songState.bookmarksOf(lantern.path).size)
        assertEquals(0, result.bookmarksLeftOut)
    }

    @Test
    fun `bookmarks past a song's cap or the cap of all are left out and counted`() {
        val full = (1..SongState.MAX_BOOKMARKS_PER_SONG).map { Bookmark(it.toLong(), it * 1_000L, "Mine") }
        val perSong = MemoryStores(songState = SongState(bookmarks = mapOf(lantern.path to full), nextBookmarkId = 1_000))
        val all = (1..SongState.MAX_BOOKMARKS / SongState.MAX_BOOKMARKS_PER_SONG).associate { n -> "${VOLUME_PHONE}Music/b$n.mp3" to full }
        val everywhere = MemoryStores(songState = SongState(bookmarks = all, nextBookmarkId = 1_000))

        val one = restore(perSong, chosen = setOf(Section.BOOKMARKS))
        val two = restore(everywhere, chosen = setOf(Section.BOOKMARKS))

        assertEquals(full, perSong.songState.bookmarksOf(lantern.path))
        assertEquals(Triple(listOf(Section.BOOKMARKS), 0, 2), Triple(one.restored, one.placesLeftOut, one.bookmarksLeftOut))
        assertEquals(all, everywhere.songState.bookmarks)
        assertEquals(2, two.bookmarksLeftOut)
    }

    @Test
    fun `a bookmark is compared in the form the phone keeps it, so a second restore adds nothing`() {
        val loose = contents().copy(bookmarks = listOf(BackupMarks(lantern.path, null, listOf(BackupBookmark(30_500, " Chorus "), BackupBookmark(30_200, "Chorus")))))
        val stores = MemoryStores()

        repeat(2) { runBlocking { plan(contents = loose).apply(setOf(Section.BOOKMARKS), stores, newId) } }

        assertEquals(listOf(30_000L to "Chorus"), stores.songState.bookmarksOf(lantern.path).map { it.positionMs to it.note })
    }

    // Twice

    @Test
    fun `restoring the same backup twice adds nothing the second time`() {
        val stores = MemoryStores()
        restore(stores)
        val once = listOf(stores.playlists, stores.queues, stores.log.plays.toSet(), stores.songState, stores.ledger, stores.settings, stores.equalizer)

        restore(stores)

        assertEquals(once, listOf(stores.playlists, stores.queues, stores.log.plays.toSet(), stores.songState, stores.ledger, stores.settings, stores.equalizer))
        assertEquals(BackupFixtures.plays.size, stores.log.plays.size)
    }

    // A new phone

    @Test
    fun `on a new phone songs are found by title, artist and length, under their new paths`() {
        val stores = MemoryStores()

        restore(stores, newLibrary)

        assertEquals(listOf(listOf(newLantern.ref, newBell.ref, newLantern.ref), listOf(newWater.ref)), stores.playlists.playlists.map { it.songs })
        assertEquals(listOf(newWater.ref, newLantern.ref), stores.playlists.favouriteSongs)
        assertEquals(setOf(newLantern.path, newBell.path), stores.log.plays.map { it.song.path }.toSet())
        assertEquals(mapOf(newWater.path to 600_000L), stores.songState.places)
        assertEquals(2, stores.songState.bookmarksOf(newLantern.path).size)
        assertEquals(setOf(newLantern.path, newBell.path, newWater.path), stores.ledger.entries.keys)
        assertEquals(newBell.path, stores.ledger.entries.getValue(newBell.path).path)
        assertEquals(setOf(newBell.path, newWater.path, newLantern.path), stores.queues.queues.flatMap { q -> q.items.map { it.song.path } }.toSet())
        assertEquals(emptyList<MissingSong>(), plan(newLibrary).missing(all))
    }

    @Test
    fun `bookmarks restored where the song has a new path land on that song, known as the phone knows it, and follow it`() {
        val stores = MemoryStores()

        restore(stores, newLibrary, chosen = setOf(Section.BOOKMARKS))

        val state = stores.songState
        assertEquals(listOf(30_000L to "Chorus", 95_000L to ""), state.bookmarksOf(newLantern.path).map { it.positionMs to it.note })
        assertEquals(mapOf(newLantern.path to newLantern.ref.mediaStoreId, newWater.path to newWater.ref.mediaStoreId), state.ids)
        // Renamed in its folder later: found by its id hint, as queues and Favourites are, its bookmarks go with it.
        val renamed = BackupFixtures.moved(newLantern, "/storage/1A2B-3C4D/Songs/Paper Lantern (live).flac", newLantern.durationMs)
            .let { it.copy(ref = it.ref.copy(mediaStoreId = newLantern.ref.mediaStoreId)) }
        val after = state.refresh { ref -> SongResolver(listOf(renamed, newBell, newWater)).find(ref)?.ref }
        assertEquals(2, after.bookmarksOf(renamed.path).size)
    }

    @Test
    fun `songs not on the phone are listed, and left out of what they were in`() {
        val stores = MemoryStores()
        val library = listOf(lantern, bell)

        assertEquals(listOf(MissingSong(water.path, "Low Water", "Mira Solen")), plan(library).missing(all))
        assertEquals(emptyList<MissingSong>(), plan(library).missing(setOf(Section.SETTINGS, Section.EQUALIZER, Section.PLAYS)))

        restore(stores, library)

        assertEquals(listOf(emptyList<SongRef>()), stores.playlists.playlists.filter { it.name == "Night drive" }.map { it.songs })
        assertEquals(listOf(lantern.ref), stores.playlists.favouriteSongs)
        assertEquals(emptyMap<String, Long>(), stores.songState.places)
        assertFalse(water.path in stores.ledger.entries)
        assertFalse(stores.queues.queues.any { q -> q.items.any { it.song.path == water.path } })
        // The saved queue held only the missing song.
        assertEquals(emptyList<String>(), stores.queues.saved)
    }

    @Test
    fun `a song two songs of this phone could be is not guessed`() {
        val twin = song("Music/Copies/Low Water.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry", durationMs = 240_500)

        assertEquals(listOf(water.path), plan(newLibrary - newWater + moved(newWater) + twin).missing(setOf(Section.PLAYLISTS)).map { it.path })
    }

    private companion object {
        const val VOLUME_PHONE: String = "/storage/emulated/0/"
    }

    private fun moved(song: Song) = BackupFixtures.moved(song, "/storage/1A2B-3C4D/Other/Low Water.flac", 239_000)

    // Choices and failures

    @Test
    fun `sections not chosen are left as they were`() {
        val stores = MemoryStores()

        assertEquals(listOf(Section.PLAYS, Section.SETTINGS), restore(stores, chosen = setOf(Section.PLAYS, Section.SETTINGS)).restored)

        assertEquals(PlaylistsState(), stores.playlists)
        assertEquals(QueueBook(), stores.queues)
        assertEquals(SongState(), stores.songState)
        assertEquals(EqProfiles(), stores.equalizer)
        assertEquals(LedgerState(), stores.ledger)
        assertEquals(3, stores.log.plays.size)
    }

    @Test
    fun `a section that fails leaves its store as it was, the others are restored, and the result names it`() {
        val stores = MemoryStores(failing = mutableSetOf(Section.PLAYS, Section.QUEUES))

        val result = restore(stores)

        assertEquals(Section.entries - Section.QUEUES - Section.PLAYS, result.restored)
        assertEquals(listOf(Section.QUEUES, Section.PLAYS), result.failed)
        assertEquals(QueueBook(), stores.queues)
        assertEquals(emptyList<LoggedPlay>(), stores.log.plays)
        assertEquals(2, stores.playlists.playlists.size)
        assertEquals(BackupFixtures.equalizer, stores.equalizer)
    }

    @Test
    fun `a section a newer KJB wrote, or the backup lacks, is never restored`() {
        val stores = MemoryStores()
        val contents = contents().copy(plays = null, tooNew = listOf(Section.PLAYS), equalizer = null)

        val result = runBlocking { plan(contents = contents).apply(all, stores, newId) }

        assertEquals(Section.entries - Section.PLAYS - Section.EQUALIZER, result.restored)
        assertEquals(emptyList<LoggedPlay>(), stores.log.plays)
    }

    @Test
    fun `a backup is read from every store, a store that cannot be read left out`() = runBlocking {
        val stores = MemoryStores(BackupFixtures.playlists, BackupFixtures.queues, FakePlayLog(BackupFixtures.plays), BackupFixtures.songState, BackupFixtures.equalizer, BackupFixtures.ledger, BackupFixtures.settings)

        assertEquals(
            BackupSnapshot(
                BackupFixtures.playlists, BackupFixtures.queues, BackupFixtures.plays, BackupFixtures.songState, BackupFixtures.equalizer, BackupFixtures.ledger, BackupFixtures.settings,
                repeat = Repeat.OFF, aiLyrics = false, tones = TonesState(), duplicates = DuplicateChoices(), coversTags = CoversTagsMemory(), keptApart = emptySet(), searches = emptyList(),
            ),
            stores.snapshot(),
        )
        stores.log.failing = true
        assertEquals(null, stores.snapshot().plays)
    }
}
