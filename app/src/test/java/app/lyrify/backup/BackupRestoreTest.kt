package app.lyrify.backup

import app.lyrify.audio.EqProfile
import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqSound
import app.lyrify.audio.OutputKey
import app.lyrify.audio.OutputKind
import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.bell
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.archive
import app.lyrify.backup.BackupFixtures.keeperId
import app.lyrify.backup.BackupFixtures.keeperOne
import app.lyrify.backup.BackupFixtures.keeperTwo
import app.lyrify.backup.BackupFixtures.lantern
import app.lyrify.backup.BackupFixtures.newArchive
import app.lyrify.backup.BackupFixtures.newBell
import app.lyrify.backup.BackupFixtures.newKeeperOne
import app.lyrify.backup.BackupFixtures.newLantern
import app.lyrify.backup.BackupFixtures.newLibrary
import app.lyrify.backup.BackupFixtures.newWater
import app.lyrify.backup.BackupFixtures.water
import app.lyrify.books.BookEntry
import app.lyrify.books.BookMarks
import app.lyrify.books.BookPlace
import app.lyrify.books.BookState
import app.lyrify.books.Chapter
import app.lyrify.books.folderBookId
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
        assertEquals(BackupFixtures.booksKept, stores.books)
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
    fun `a restore keeps the phone's own tour and notifications state, so a new phone sees the tour once`() {
        val seen = BackupFixtures.settings.copy(nav = NavSettings(setupDone = true, tourSeen = true, notificationsDenied = true, notificationDenials = 2))
        val backup = BackupFixtures.contents(BackupFixtures.zip(BackupFixtures.snapshot.copy(settings = seen)))
        val fresh = MemoryStores(settings = Settings())
        val toured = MemoryStores(settings = Settings(nav = NavSettings(tourSeen = true, notificationsDenied = true, notificationDenials = 1)))

        runBlocking { plan(contents = backup).apply(setOf(Section.SETTINGS), fresh, newId) }
        restore(toured, chosen = setOf(Section.SETTINGS))

        assertEquals(NavSettings(), fresh.settings.nav)
        assertEquals(NavSettings(setupDone = false, tourSeen = true, notificationsDenied = true, notificationDenials = 1), toured.settings.nav)
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
        // The backup's favourites in its order, the phone's own after them.
        assertEquals(listOf(water.ref, lantern.ref, other.ref), stores.playlists.favouriteSongs)
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
    fun `a custom order made by dragging goes in the backup and comes back in that order, a playlist's and Favourites'`() {
        // Road trip with Tide Bell dragged to the top, and Favourites with Paper Lantern above Low Water.
        val dragged = BackupFixtures.playlists.arrange("p1", listOf(bell.ref, lantern.ref, lantern.ref)).arrangeFavourites(listOf(lantern.ref, water.ref))
        val stores = MemoryStores()

        runBlocking { RestorePlan(contents(BackupFixtures.zip(BackupFixtures.snapshot.copy(playlists = dragged))), BackupFixtures.library).apply(setOf(Section.PLAYLISTS), stores, newId) }

        assertEquals(listOf(bell.ref, lantern.ref, lantern.ref), stores.playlists.playlist("p1")?.songs)
        assertEquals(listOf(lantern.ref, water.ref), stores.playlists.favouriteSongs)
    }

    @Test
    fun `on a phone that has the playlists and Favourites already, the backup's order comes back, songs only on the phone after it`() {
        // The phone moved its songs about since the backup, and added a song to Road trip and to Favourites.
        val other = song("Music/Salt Road/01 Salt Road.mp3", title = "Salt Road", artist = "Mira Solen", durationMs = 199_000, id = 14)
        val library = BackupFixtures.library + other
        val stores = MemoryStores(
            PlaylistsState(
                listOf(BackupFixtures.roadTrip.copy(songs = listOf(other.ref, lantern.ref, lantern.ref, bell.ref)), Playlist("p2", "Night drive", listOf(water.ref), createdAt = T0 + 1)),
                favouriteSongs = listOf(lantern.ref, other.ref, water.ref),
            ),
        )

        restore(stores, library, chosen = setOf(Section.PLAYLISTS))

        // Road trip: Paper Lantern, Tide Bell, Paper Lantern as in the backup, then Salt Road; no second Road trip.
        assertEquals(listOf("Road trip", "Night drive"), stores.playlists.playlists.map { it.name })
        assertEquals(listOf(lantern.ref, bell.ref, lantern.ref, other.ref), stores.playlists.playlist("p1")?.songs)
        // Favourites: Low Water, Paper Lantern as in the backup, then Salt Road.
        assertEquals(listOf(water.ref, lantern.ref, other.ref), stores.playlists.favouriteSongs)
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

    // Books

    @Test
    fun `books are added to the phone's own and counted, the phone's marks kept`() {
        val shelf = "${VOLUME_PHONE}Audiobooks/Quiet Shelf.m4b"
        val mine = BookEntry(shelf, "Quiet Shelf", "Ada Quill", listOf(shelf), BookPlace(0, 90_000), shelf, lastPlayedAt = T0 + 1)
        val stores = MemoryStores(books = BookState(mapOf(shelf to mine), BookMarks(notSongs = setOf(water.path))))

        val result = restore(stores, chosen = setOf(Section.BOOKS))

        assertEquals(listOf(Section.BOOKS), result.restored)
        assertEquals(2, result.counts[Section.BOOKS])
        assertEquals(BackupFixtures.booksKept.books + (shelf to mine), stores.books.books)
        // The phone said Low Water is no book, the backup that it is one: the phone's word stays.
        assertEquals(BookMarks(folders = setOf("Spoken/Ada Quill"), notSongs = setOf(water.path), notFolders = setOf("Music/Night Ferry")), stores.books.marks)
    }

    @Test
    fun `a book whose part playing is not on this phone starts again, never at that place in another file`() {
        val stores = MemoryStores()

        restore(stores, library = BackupFixtures.library - keeperTwo, chosen = setOf(Section.BOOKS))

        val keeper = stores.books.books.values.single { it.title == "Lantern Keeper" }
        assertEquals(listOf(keeperOne.path), keeper.parts)
        assertEquals(BookPlace(), keeper.place)
        assertEquals(null, keeper.partPath)
    }

    @Test
    fun `of a book on both, the one played last wins, with the phone's chapters, and a tie keeps the phone's`() {
        val backup = BackupFixtures.booksKept.books.getValue(archive.path)
        val chapters = listOf(Chapter(0, 36_000_000, "The Whole Archive"))
        val phone = backup.copy(place = BookPlace(0, 1_200_000), partPath = archive.path, speed = null, finished = false, chapters = chapters, chaptersStamp = "300000000:9")
        fun merged(phone: BookEntry, backup: BookEntry) = RestorePlan.mergeBooks(BookState(mapOf(phone.id to phone)), BookState(mapOf(backup.id to backup))).books.getValue(archive.path)

        // The backup's played later, or the phone's never: the backup's place, with the phone's chapters.
        assertEquals(backup.copy(chapters = chapters, chaptersStamp = "300000000:9"), merged(phone.copy(lastPlayedAt = T0 + 2), backup))
        assertEquals(backup.copy(chapters = chapters, chaptersStamp = "300000000:9"), merged(phone.copy(lastPlayedAt = null), backup))
        // The phone's was played after the backup's, at the same time, or the backup's never was: the phone's stays.
        assertEquals(phone.copy(lastPlayedAt = T0 + 9), merged(phone.copy(lastPlayedAt = T0 + 9), backup))
        assertEquals(phone.copy(lastPlayedAt = T0 + 7), merged(phone.copy(lastPlayedAt = T0 + 7), backup))
        assertEquals(phone.copy(lastPlayedAt = T0 + 2), merged(phone.copy(lastPlayedAt = T0 + 2), backup.copy(lastPlayedAt = null)))
    }

    @Test
    fun `the phone's marks stay, the backup's are added for songs and folders the phone has no mark for either way`() {
        val note = "${VOLUME_PHONE}Music/Spoken/Note.mp3"
        val now = BookState(marks = BookMarks(songs = setOf(bell.path), folders = setOf("Music/Night Ferry"), notSongs = setOf(water.path), notFolders = setOf("Talks")))
        val restored = BookState(
            marks = BookMarks(songs = setOf(water.path, lantern.path), folders = setOf("Talks", "Spoken/Ada Quill"), notSongs = setOf(bell.path, note), notFolders = setOf("Music/Night Ferry", "Music/Rips")),
        )

        assertEquals(
            BookMarks(
                songs = setOf(bell.path, lantern.path),
                folders = setOf("Music/Night Ferry", "Spoken/Ada Quill"),
                notSongs = setOf(water.path, note),
                notFolders = setOf("Talks", "Music/Rips"),
            ),
            RestorePlan.mergeBooks(now, restored).marks,
        )
    }

    @Test
    fun `books past the cap are left out, those played longest ago first`() {
        val mine = (1..BookState.MAX_BOOKS).map { n -> "${VOLUME_PHONE}Books/b$n.m4b".let { BookEntry(it, "Book $n", null, listOf(it), lastPlayedAt = T0 + n) } }
        val now = BookState(mine.associateBy { it.id })

        val merged = RestorePlan.mergeBooks(now, BackupFixtures.booksKept)

        assertEquals(BookState.MAX_BOOKS, merged.books.size)
        // The backup's were played at T0 + 7 and T0 + 8: the phone's two oldest go.
        assertEquals(setOf(mine[0].id, mine[1].id), now.books.keys - merged.books.keys)
        assertTrue(archive.path in merged.books && keeperId in merged.books)
    }

    @Test
    fun `when the books can't be written they stay as they were and the part is named as failed`() {
        val mine = BookState(marks = BookMarks(notSongs = setOf(keeperOne.path)))
        val stores = MemoryStores(failing = mutableSetOf(Section.BOOKS), books = mine)

        val result = restore(stores)

        assertEquals(listOf(Section.BOOKS), result.failed)
        assertEquals(mine, stores.books)
    }

    // Twice

    @Test
    fun `restoring the same backup twice adds nothing the second time`() {
        val stores = MemoryStores()
        restore(stores)
        val once = listOf(stores.playlists, stores.queues, stores.log.plays.toSet(), stores.songState, stores.ledger, stores.settings, stores.equalizer, stores.books)

        restore(stores)

        assertEquals(once, listOf(stores.playlists, stores.queues, stores.log.plays.toSet(), stores.songState, stores.ledger, stores.settings, stores.equalizer, stores.books))
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
        assertEquals(setOf(newArchive.path, folderBookId(newKeeperOne.path.substringBeforeLast('/'), "Lantern Keeper")), stores.books.books.keys)
        assertEquals(setOf(newWater.path), stores.books.marks.songs)
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
        val library = BackupFixtures.library - water

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
        assertEquals(BackupFixtures.booksKept.books, stores.books.books)
        assertEquals(emptySet<String>(), stores.books.marks.songs)
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
        assertEquals(BookState(), stores.books)
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
        val stores = MemoryStores(BackupFixtures.playlists, BackupFixtures.queues, FakePlayLog(BackupFixtures.plays), BackupFixtures.songState, BackupFixtures.equalizer, BackupFixtures.ledger, BackupFixtures.settings, books = BackupFixtures.books)

        assertEquals(
            BackupSnapshot(
                BackupFixtures.playlists, BackupFixtures.queues, BackupFixtures.plays, BackupFixtures.songState, BackupFixtures.equalizer, BackupFixtures.ledger, BackupFixtures.settings,
                repeat = Repeat.OFF, aiLyrics = false, tones = TonesState(), duplicates = DuplicateChoices(), coversTags = CoversTagsMemory(), keptApart = emptySet(), searches = emptyList(),
                books = BackupFixtures.books,
            ),
            stores.snapshot(),
        )
        stores.log.failing = true
        assertEquals(null, stores.snapshot().plays)
    }
}
