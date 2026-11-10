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
    /** The phone's stores in memory; a section in [failing] cannot be written, and its store stays as it was. */
    class MemoryStores(
        var playlists: PlaylistsState = PlaylistsState(),
        var queues: QueueBook = QueueBook(),
        val log: FakePlayLog = FakePlayLog(),
        var songState: SongState = SongState(),
        var equalizer: EqProfiles = EqProfiles(),
        var ledger: LedgerState = LedgerState(),
        var settings: Settings = Settings(),
        val failing: MutableSet<Section> = mutableSetOf(),
    ) : BackupStores {
        private fun <T> change(section: Section, now: T, change: (T) -> T, set: (T) -> Unit): Boolean = (section !in failing).also { if (it) set(change(now)) }

        override suspend fun playlists(): PlaylistsState = playlists

        override suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState) = change(Section.PLAYLISTS, playlists, change) { playlists = it }

        override suspend fun queues(): QueueBook = queues

        override suspend fun updateQueues(change: (QueueBook) -> QueueBook) = change(Section.QUEUES, queues, change) { queues = it }

        override suspend fun plays(): List<LoggedPlay>? = log.all()

        override suspend fun addPlays(plays: List<LoggedPlay>): Boolean = Section.PLAYS !in failing && log.putAll(plays)

        override suspend fun songState(): SongState = songState

        override suspend fun updateSongState(change: (SongState) -> SongState) = change(Section.BOOKMARKS, songState, change) { songState = it }

        override suspend fun equalizer(): EqProfiles = equalizer

        override suspend fun replaceEqualizer(profiles: EqProfiles) = change(Section.EQUALIZER, equalizer, { profiles }) { equalizer = it }

        override suspend fun ledger(): LedgerState = ledger

        override suspend fun updateLedger(change: (LedgerState) -> LedgerState) = change(Section.LYRICS, ledger, change) { ledger = it }

        override suspend fun settings(): Settings = settings

        override suspend fun updateSettings(change: (Settings) -> Settings) = change(Section.SETTINGS, settings, change) { settings = it }
    }

    private var ids = 0
    private val newId = { "new${++ids}" }
    private val all = Section.entries.toSet()

    private fun plan(library: List<Song> = BackupFixtures.library, contents: BackupContents = contents()) = RestorePlan(contents, SongResolver(library))

    private fun restore(stores: MemoryStores, library: List<Song> = BackupFixtures.library, chosen: Set<Section> = all): RestoreResult =
        runBlocking { plan(library).apply(chosen, stores, newId) }

    // Each section on an empty phone

    @Test
    fun `on an empty phone every section comes back`() {
        val stores = MemoryStores()

        assertEquals(RestoreResult(Section.entries, emptyList()), restore(stores))

        assertEquals(listOf("Road trip", "Night drive"), stores.playlists.playlists.map { it.name })
        assertEquals(listOf(listOf(lantern.ref, bell.ref, lantern.ref), listOf(water.ref)), stores.playlists.playlists.map { it.songs })
        assertEquals(listOf(water.ref, lantern.ref), stores.playlists.favouriteSongs)
        val roadTrip = stores.playlists.playlists.first().id
        assertEquals(listOf(FavouriteCollection(CollectionKind.ARTIST, "Mira Solen"), FavouriteCollection(CollectionKind.PLAYLIST, roadTrip)), stores.playlists.favouriteCollections)

        val book = stores.queues
        assertEquals(null, book.currentId)
        assertEquals(listOf(listOf(bell.ref, water.ref), listOf(lantern.ref, bell.ref)), book.recent.map { id -> book.queue(id)!!.items.map { it.song } })
        assertEquals(listOf("Ferry songs"), book.saved.map { book.queue(it)!!.name })
        val album = book.queue(book.recent[1])!!
        assertEquals(1 to 30_000L, album.index to album.positionMs)

        assertEquals(BackupFixtures.plays.toSet(), stores.log.plays.toSet())
        assertEquals(mapOf(water.path to 600_000L), stores.songState.places)
        assertEquals(listOf(30_000L to "Chorus", 95_000L to ""), stores.songState.bookmarksOf(lantern.path).map { it.positionMs to it.note })
        assertEquals(BackupFixtures.equalizer, stores.equalizer)
        assertEquals(BackupFixtures.ledger.entries, stores.ledger.entries)
        assertEquals(BackupFixtures.settings.copy(backup = BackupSettings(), nav = NavSettings(setupDone = false)), stores.settings)
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
        val twin = song("Music/Copies/Low Water.mp3", title = "Low Water", artist = "Mira Solen", durationMs = 240_500)

        assertEquals(listOf(water.path), plan(newLibrary - newWater + moved(newWater) + twin).missing(setOf(Section.PLAYLISTS)).map { it.path })
    }

    private fun moved(song: Song) = BackupFixtures.moved(song, "/storage/1A2B-3C4D/Other/Low Water.flac", 239_000)

    // Choices and failures

    @Test
    fun `sections not chosen are left as they were`() {
        val stores = MemoryStores()

        assertEquals(RestoreResult(listOf(Section.PLAYS, Section.SETTINGS), emptyList()), restore(stores, chosen = setOf(Section.PLAYS, Section.SETTINGS)))

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

        assertEquals(RestoreResult(Section.entries - Section.QUEUES - Section.PLAYS, listOf(Section.QUEUES, Section.PLAYS)), result)
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
            BackupSnapshot(BackupFixtures.playlists, BackupFixtures.queues, BackupFixtures.plays, BackupFixtures.songState, BackupFixtures.equalizer, BackupFixtures.ledger, BackupFixtures.settings),
            stores.snapshot(),
        )
        stores.log.failing = true
        assertEquals(null, stores.snapshot().plays)
    }
}
