package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.VERSION
import app.lyrify.backup.BackupFixtures.zipOf
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import app.lyrify.player.AbPart
import app.lyrify.player.Bookmark
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueItem
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SongState
import app.lyrify.player.SourceKind
import app.lyrify.player.Speed
import app.lyrify.settings.AdvancedSettings
import app.lyrify.settings.AudioSettings
import app.lyrify.settings.BackupSettings
import app.lyrify.settings.ConnectDevice
import app.lyrify.settings.DockTab
import app.lyrify.settings.FavouritesTab
import app.lyrify.settings.Levelling
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.LyricsAlign
import app.lyrify.settings.LyricsFilter
import app.lyrify.settings.LyricsSettings
import app.lyrify.settings.LyricsSize
import app.lyrify.settings.LyricsWeight
import app.lyrify.settings.NavSettings
import app.lyrify.settings.NotificationButtons
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.PlayerUiSettings
import app.lyrify.settings.RememberPlace
import app.lyrify.settings.Settings
import app.lyrify.settings.SortAlbums
import app.lyrify.settings.SortArtists
import app.lyrify.settings.SortSongs
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.LoggedPlay
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.CoversTagsMemory
import app.lyrify.tags.FileMark
import app.lyrify.tags.NameKind
import app.lyrify.tags.NamePair
import app.lyrify.tags.Settled
import app.lyrify.tags.albumId
import app.lyrify.tones.SavedTone
import app.lyrify.tones.ToneKind
import app.lyrify.tones.TonesState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The user's case (feedback 2026-11-11, point 23): everything KJB knows is backed up on the old phone, the Music folder
 * is copied to a new phone, and the backup restored there shows KJB exactly as it was. On the new phone every MediaStore
 * id differs and the songs sit under another storage root, one in another folder, one with other tags. Made-up names only.
 */
class BackupNewPhoneTest {
    // The old phone

    private val lantern = at(OLD, "Music/Harbour Glass/01 Paper Lantern.mp3", "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, 11, 4_100_000)
    private val bell = at(OLD, "Music/Harbour Glass/02 Tide Bell.mp3", "Tide Bell", "Kit Calloway", "Harbour Glass", 185_000, 12, 3_900_000)
    private val water = at(OLD, "Music/Night Ferry/01 Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_000, 13, 5_000_000)
    private val road = at(OLD, "Music/Night Ferry/02 Salt Road.mp3", "Salt Road", "Mira Solen", "Night Ferry", 199_000, 14, 4_400_000)
    private val steps = at(OLD, "Download/Glass Steps.mp3", "Glass Steps", "Odo Brannick", null, 175_000, 15, 3_000_000)
    private val moon = at(OLD, "Music/Singles/Copper Moon.mp3", "Copper Moon", "Odo Brannick", "Singles", 222_000, 16, 4_800_000)
    private val oldLibrary = listOf(lantern, bell, water, road, steps, moon)

    // The new phone: the Music folder on a card, ids all new, lengths a little off; Glass Steps in another folder (found
    // by its tags), Copper Moon retagged (found by its path below the root).

    private val newLantern = at(NEW, "Music/Harbour Glass/01 Paper Lantern.mp3", "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_400, 901, 4_100_000)
    private val newBell = at(NEW, "Music/Harbour Glass/02 Tide Bell.mp3", "Tide Bell", "Kit Calloway", "Harbour Glass", 184_600, 902, 3_900_000)
    private val newWater = at(NEW, "Music/Night Ferry/01 Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_000, 903, 5_000_000)
    private val newRoad = at(NEW, "Music/Night Ferry/02 Salt Road.mp3", "Salt Road", "Mira Solen", "Night Ferry", 199_000, 904, 4_400_000)
    private val newSteps = at(OLD, "Music/Odds/Glass Steps.mp3", "Glass Steps", "Odo Brannick", null, 175_900, 905, 3_000_000)
    private val newMoon = at(NEW, "Music/Singles/Copper Moon.mp3", "Copper Moon (Remaster)", "Odo Brannick", "Singles", 222_000, 906, 4_900_000)
    private val stranger = at(NEW, "Music/Other/Night Bus.mp3", "Night Bus", "Ada Querne", null, 210_000, 907, 4_000_000)
    private val newLibrary = listOf(newLantern, newBell, newWater, newRoad, newSteps, newMoon, stranger)

    private val moved: Map<String, Song> = oldLibrary.zip(newLibrary).associate { (old, new) -> old.path to new }

    private fun remap(ref: SongRef): SongRef = moved.getValue(ref.path).ref

    private fun remap(path: String): String = moved.getValue(path).path

    // Everything the old phone keeps

    private val playlists = PlaylistsState(
        playlists = listOf(
            Playlist("p-road", "Road trip", listOf(lantern.ref, bell.ref, lantern.ref, steps.ref), createdAt = T0 - 9),
            Playlist("p-night", "Night drive", listOf(water.ref, road.ref), createdAt = T0 - 8),
        ),
        favouriteSongs = listOf(water.ref, lantern.ref),
        favouriteCollections = listOf(
            FavouriteCollection(CollectionKind.ARTIST, "Mira Solen"),
            FavouriteCollection(CollectionKind.ALBUM, albumKey(lantern)!!),
            FavouriteCollection(CollectionKind.FOLDER, "Music/Night Ferry"),
            FavouriteCollection(CollectionKind.PLAYLIST, "p-road"),
        ),
    )

    /** Playing a shuffled songs list, at Copper Moon 1:23 in, with a Play next song waiting; an album in Recent, a named queue saved. */
    private val queues = QueueBook(
        queues = listOf(
            Queue("q3", QueueSource(SourceKind.ALBUM, "album:Harbour Glass", "Harbour Glass"), listOf(QueueItem(10, lantern.ref, 0), QueueItem(11, bell.ref, 1)), index = 1, positionMs = 30_000, updatedAt = T0, startedAt = T0 - 100),
            Queue("q4", QueueSource(SourceKind.ARTIST, "artist:Mira Solen", "Mira Solen"), listOf(QueueItem(12, water.ref, 0), QueueItem(13, road.ref, 1)), index = 1, positionMs = 12_000, name = "Ferry songs", updatedAt = T0 + 1, startedAt = T0 - 50),
            Queue(
                "q5",
                QueueSource(SourceKind.SONGS, "songs", "Songs"),
                listOf(QueueItem(23, steps.ref, 3), QueueItem(22, road.ref, 2), QueueItem(21, moon.ref, 1), QueueItem(24, water.ref, playNext = true), QueueItem(20, bell.ref, 0)),
                index = 2,
                positionMs = 83_000,
                unshuffled = listOf(20, 21, 22, 23, 24),
                updatedAt = T0 + 2,
                startedAt = T0,
            ),
        ),
        currentId = "q5",
        recent = listOf("q3"),
        saved = listOf("q4"),
        nextId = 6,
        nextUid = 25,
    )

    private val plays = listOf(
        LoggedPlay(lantern.ref, T0 - 9_000_000, 201_000, counted = true),
        LoggedPlay(lantern.ref, T0 - 5_000_000, 150_000, counted = true),
        LoggedPlay(bell.ref, T0 - 4_000_000, 20_000, counted = false),
        LoggedPlay(moon.ref, T0 - 3_000_000, 222_000, counted = true),
        LoggedPlay(steps.ref, T0 - 2_000_000, 100_000, counted = true),
    )

    private val songState = SongState(
        usual = Speed(1.1f),
        speeds = mapOf(water.path to Speed(0.8f, keepPitch = false, semitones = -2)),
        places = linkedMapOf(road.path to 1_200_000L, moon.path to 700_000L),
        bookmarks = mapOf(lantern.path to listOf(Bookmark(1, 30_000, "Chorus"), Bookmark(2, 95_000, ""))),
        part = AbPart(bell.path, 10_000, 40_000),
        nextBookmarkId = 3,
        ids = mapOf(lantern.path to 11L, bell.path to 12L, water.path to 13L, road.path to 14L, moon.path to 16L),
    )

    private val ledger = LedgerState(
        entries = listOf(
            LedgerEntry(lantern.path, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, LyricsStatus.SYNCED, lrclibId = 41, updatedAt = T0, sha256 = "ab12", size = 900, source = LyricsSource.LRCLIB),
            LedgerEntry(bell.path, "Tide Bell", "Kit Calloway", "Harbour Glass", 185_000, LyricsStatus.EXISTING, updatedAt = T0, mine = true),
            LedgerEntry(moon.path, "Copper Moon", "Odo Brannick", "Singles", 222_000, LyricsStatus.NOT_FOUND, updatedAt = T0, declined = true),
        ).associateBy { it.path },
    )

    private val settings = Settings(
        playback = PlaybackSettings(RememberPlace.ALL_SONGS, headsetButtons = false, skipShortClips = false, countPlayAfterPercent = 70, skipBackSeconds = 15, skipForwardSeconds = 30, connectDevices = listOf(ConnectDevice(4, "Harbour Buds"))),
        audio = AudioSettings(Levelling.ALBUM, crossfadeSeconds = 4),
        library = LibrarySettings(
            tabs = LibrarySettings.DEFAULT_TABS.reversed().map { it.copy(shown = true) },
            foldersToSkip = listOf("WhatsApp/Media/WhatsApp Audio"),
            showFileNames = true,
            songSort = SortSongs.ARTIST,
            songFilter = LyricsFilter.SYNCED,
            albumSort = SortAlbums.YEAR,
            artistSort = SortArtists.MOST_SONGS,
        ),
        lyrics = LyricsSettings(showWhenLocked = true, textSize = LyricsSize.L, textWeight = LyricsWeight.REGULAR, textAlign = LyricsAlign.CENTRE),
        playerUi = PlayerUiSettings(showLyrics = true, flipHintShown = true, recentQueueHintShown = true),
        backup = BackupSettings(automatic = true, folderUri = "content://com.android.externalstorage.documents/tree/primary%3AOld"),
        advanced = AdvancedSettings(gaplessPlayback = false, audioOffload = true, notificationButtons = NotificationButtons.SHUFFLE_REPEAT, levellingBoostDb = 3, keepAlbumsGapless = false),
        nav = NavSettings(DockTab.TOOLS, FavouritesTab.ALBUMS, setupDone = true),
    )

    private val ringtone = SavedTone(501, "Low Water (KJB)", ToneKind.RINGTONE, water.path, 30_000, 30_000, 500, 1_000, T0, "Low Water (KJB).m4a", 480_000)

    /** Its file was not copied to the new phone. */
    private val ping = SavedTone(502, "Paper Lantern (KJB)", ToneKind.NOTIFICATION, lantern.path, 0, 15_000, 0, 0, T0 + 1, "Paper Lantern (KJB).m4a", 240_000)

    private val coversTags = CoversTagsMemory(
        albums = listOf(
            AlbumRecord(albumId(listOf(lantern.path, bell.path)), Settled.DONE, "Harbour Glass", "Kit Calloway", listOf(FileMark(lantern.path, 4_100_000, T0), FileMark(bell.path, 3_900_000, T0)), at = T0 + 5, entryId = 7, changed = 2),
            AlbumRecord(albumId(listOf(water.path, road.path)), Settled.SKIPPED, "Night Ferry", "Mira Solen", listOf(FileMark(water.path, 5_000_000, T0), FileMark(road.path, 4_400_000, T0)), at = T0 + 6),
        ),
        checkedOnce = true,
        noted = true,
        historySeen = 7,
    )

    private fun oldPhone() = MemoryStores(
        playlists = playlists,
        queues = queues,
        log = FakePlayLog(plays),
        songState = songState,
        equalizer = BackupFixtures.equalizer,
        ledger = ledger,
        settings = settings,
        repeat = Repeat.ONE,
        aiLyrics = true,
        tones = TonesState(mapOf(ringtone.id to ringtone, ping.id to ping)),
        duplicates = DuplicateChoices(notDuplicates = setOf("11,12"), reviewed = setOf("13,14")),
        coversTags = coversTags,
        keptApart = setOf(NamePair.of(NameKind.ARTIST, "Odo Brannick", "Odo Branick")),
        searches = listOf("harbour", "mira solen"),
    )

    /** A new phone after its first run: nothing in KJB yet, the ringtone's file copied, the songs' files as they are there. */
    private fun newPhone() = MemoryStores(
        settings = Settings(nav = NavSettings(setupDone = true)),
        toneFiles = mapOf(Triple("Ringtones/", ringtone.fileName, ringtone.size) to 7001L),
        files = newLibrary.associate { it.path to FileMark(it.path, it.sizeBytes, T0 + 99) },
    )

    private var ids = 0
    private val newId = { "new${++ids}" }

    /** Backs up [old] (the old phone's library [library]), then restores every section on [phone] with [phoneLibrary]. */
    private fun move(old: MemoryStores = oldPhone(), library: List<Song> = oldLibrary, phone: MemoryStores = newPhone(), phoneLibrary: List<Song> = newLibrary): RestoreResult {
        val bytes = ByteArrayOutputStream().also { BackupBundle.write(runBlocking { old.snapshot() }, library, VERSION, T0, it) }.toByteArray()
        return runBlocking { RestorePlan(BackupFixtures.contents(bytes), phoneLibrary).apply(Section.entries.toSet(), phone, newId) }
    }

    // The new phone shows KJB as it was

    @Test
    fun `every part comes back, and every song of the backup is found`() {
        val result = move()

        assertEquals(Section.entries, result.restored)
        assertEquals(emptyList<Section>(), result.failed)
        assertEquals(0, result.notFound)
    }

    @Test
    fun `playlists keep their songs in order, and Favourites their songs and collections`() {
        val phone = newPhone()

        move(phone = phone)

        val expected = playlists.copy(
            playlists = playlists.playlists.map { it.copy(songs = it.songs.map(::remap)) },
            favouriteSongs = playlists.favouriteSongs.map(::remap),
        )
        assertEquals(expected, phone.playlists)
    }

    @Test
    fun `the queue playing comes back playing at the same song and second, with Recent, Saved and Repeat`() {
        val phone = newPhone()

        move(phone = phone)

        val expected = queues.copy(queues = queues.queues.map { queue -> queue.copy(items = queue.items.map { it.copy(song = remap(it.song)) }) })
        assertEquals(expected, phone.queues)
        val playing = phone.queues.current!!
        assertEquals(newMoon.ref to 83_000L, playing.current!!.song to playing.positionMs)
        assertEquals(Repeat.ONE, phone.repeat)
    }

    @Test
    fun `plays come back, and with them each song's play count`() {
        val phone = newPhone()

        move(phone = phone)

        assertEquals(plays.map { it.copy(song = remap(it.song)) }.toSet(), phone.log.plays.toSet())
        val counts = runBlocking { phone.log.totals() }.associate { it.song.path to it.count }
        assertEquals(mapOf(newLantern.path to 2, newBell.path to 0, newMoon.path to 1, newSteps.path to 1), counts)
    }

    @Test
    fun `places, bookmarks, own speeds, the usual speed and the part repeating come back on the songs' new paths`() {
        val phone = newPhone()

        move(phone = phone)

        val expected = SongState(
            usual = Speed(1.1f),
            speeds = mapOf(newWater.path to Speed(0.8f, keepPitch = false, semitones = -2)),
            places = mapOf(newRoad.path to 1_200_000L, newMoon.path to 700_000L),
            bookmarks = mapOf(newLantern.path to listOf(Bookmark(1, 30_000, "Chorus"), Bookmark(2, 95_000, ""))),
            part = AbPart(newBell.path, 10_000, 40_000),
            nextBookmarkId = 3,
            ids = mapOf(newLantern.path to 901L, newBell.path to 902L, newWater.path to 903L, newRoad.path to 904L, newMoon.path to 906L),
        )
        assertEquals(expected, phone.songState)
    }

    @Test
    fun `the equalizer and the lyrics choices come back, the user's own lyrics marked so`() {
        val phone = newPhone()

        move(phone = phone)

        assertEquals(BackupFixtures.equalizer, phone.equalizer)
        assertEquals(ledger.entries.values.map { it.copy(path = remap(it.path)) }.associateBy { it.path }, phone.ledger.entries)
    }

    @Test
    fun `settings and the AI switch come back, and the phone keeps its backup folder, its setup and its own Start when connected devices`() {
        val phone = newPhone()

        val result = move(phone = phone)

        val expected = settings.copy(backup = BackupSettings(), playback = settings.playback.copy(connectDevices = emptyList()), nav = settings.nav.copy(setupDone = true))
        assertEquals(expected, phone.settings)
        assertEquals(true, phone.aiLyrics)
        assertEquals(listOf("Harbour Buds"), result.pickAgain)
    }

    @Test
    fun `a tone comes back when its file is on the phone, the others are counted`() {
        val phone = newPhone()

        val result = move(phone = phone)

        assertEquals(mapOf(7001L to ringtone.copy(id = 7001, songPath = newWater.path)), phone.tones.tones)
        assertEquals(1, result.tonesNotFound)
    }

    @Test
    fun `Duplicates choices follow the songs to their new ids`() {
        val phone = newPhone()

        move(phone = phone)

        assertEquals(DuplicateChoices(notDuplicates = setOf("901,902"), reviewed = setOf("903,904")), phone.duplicates)
    }

    @Test
    fun `Covers & tags keeps its albums done and skipped, as the files are now, without the Undo left on the old phone`() {
        val phone = newPhone()

        move(phone = phone)

        val mark = { song: Song -> FileMark(song.path, song.sizeBytes, T0 + 99) }
        assertEquals(
            CoversTagsMemory(
                albums = listOf(
                    AlbumRecord(albumId(listOf(newLantern.path, newBell.path)), Settled.DONE, "Harbour Glass", "Kit Calloway", listOf(mark(newLantern), mark(newBell)), at = T0 + 5, entryId = null, changed = 2),
                    AlbumRecord(albumId(listOf(newWater.path, newRoad.path)), Settled.SKIPPED, "Night Ferry", "Mira Solen", listOf(mark(newWater), mark(newRoad)), at = T0 + 6),
                ),
                checkedOnce = true,
                noted = true,
            ),
            phone.coversTags,
        )
        assertEquals(setOf(NamePair.of(NameKind.ARTIST, "Odo Brannick", "Odo Branick")), phone.keptApart)
    }

    @Test
    fun `recent searches come back in their order`() {
        val phone = newPhone()

        move(phone = phone)

        assertEquals(listOf("harbour", "mira solen"), phone.searches)
    }

    @Test
    fun `the summary counts what came back and the songs not found on the new phone`() {
        val result = move(phoneLibrary = newLibrary - newRoad)

        assertEquals(1, result.notFound)
        assertEquals(2, result.counts[Section.PLAYLISTS])
        assertEquals(3, result.counts[Section.QUEUES])
        assertEquals(5, result.counts[Section.PLAYS])
        assertEquals(2, result.counts[Section.BOOKMARKS])
        assertEquals(1, result.counts[Section.TONES])
        assertEquals(1, result.counts[Section.COVERS])
        assertEquals(2, result.counts[Section.SEARCHES])
    }

    // An older KJB's backup

    @Test
    fun `a backup an earlier KJB made still restores on a new phone, its songs found by their paths below the root`() {
        val phone = newPhone()
        // Tide Bell was retagged on the new phone: only its path below the storage root finds it.
        val retagged = newBell.copy(title = "Tide Bell (Live)")
        val library = newLibrary - newBell + retagged

        val result = runBlocking { RestorePlan(BackupFixtures.contents(OldBackup.zip()), library).apply(Section.entries.toSet(), phone, newId) }

        assertEquals(listOf(Section.PLAYLISTS, Section.QUEUES, Section.PLAYS, Section.BOOKMARKS, Section.SETTINGS), result.restored)
        assertEquals(0, result.notFound)
        assertEquals(listOf(listOf(newLantern.ref, retagged.ref)), phone.playlists.playlists.map { it.songs })
        assertEquals(listOf(listOf(newLantern.ref, retagged.ref)), phone.queues.recent.map { id -> phone.queues.queue(id)!!.items.map { it.song } })
        assertNull(phone.queues.currentId)
        assertEquals(setOf(LoggedPlay(retagged.ref, T0 - 1_000, 185_000, counted = true)), phone.log.plays.toSet())
        assertEquals(mapOf(newLantern.path to 90_000L), phone.songState.places)
        assertEquals(70, phone.settings.playback.countPlayAfterPercent)
        assertEquals(Repeat.OFF, phone.repeat)
        assertEquals(false, phone.aiLyrics)
    }

    /** A backup as KJB 3.0.0's first backup wrote it: no paths below the root, sizes, Repeat, speeds or AI switch. */
    private object OldBackup {
        private const val LANTERN = OLD + "Music/Harbour Glass/01 Paper Lantern.mp3"
        private const val BELL = OLD + "Music/Harbour Glass/02 Tide Bell.mp3"

        fun zip(): ByteArray = zipOf(
            listOf(
                "manifest.json" to """{"format":1,"appVersion":"3.0.0","createdAt":$T0,"songs":{"version":1},"sections":{"playlists":{"version":1},"queues":{"version":1},"plays":{"version":1},"bookmarks":{"version":1},"settings":{"version":1}}}""",
                "songs.json" to """{"songs":[{"path":"$LANTERN","title":"Paper Lantern","artist":"Kit Calloway","album":"Harbour Glass","durationMs":201000},{"path":"$BELL","title":"Tide Bell","artist":"Kit Calloway","album":"Harbour Glass","durationMs":185000}]}""",
                "playlists.json" to """{"schemaVersion":1,"playlists":[{"id":"p1","name":"Road trip","createdAt":$T0,"songs":[{"path":"$LANTERN"},{"path":"$BELL"}]}],"favourites":{"songs":[],"collections":[]}}""",
                "queues.json" to """{"schemaVersion":1,"current":null,"recent":["q1"],"saved":[],"nextId":2,"nextUid":3,"queues":[{"id":"q1","source":{"kind":"ALBUM","key":"album:Harbour Glass","name":"Harbour Glass"},"index":1,"positionMs":30000,"updatedAt":$T0,"startedAt":$T0,"items":[{"uid":1,"path":"$LANTERN","sourceIndex":0},{"uid":2,"path":"$BELL","sourceIndex":1}]}]}""",
                "plays.json" to """{"plays":[{"path":"$BELL","startedAt":${T0 - 1_000},"heardMs":185000,"counted":true}]}""",
                "bookmarks.json" to """{"songs":[{"path":"$LANTERN","placeMs":90000,"bookmarks":[]}]}""",
                "settings.json" to """{"version":2,"playback":{"countPlayAfterPercent":70}}""",
            ).map { (name, text) -> name to text.encodeToByteArray() },
        )
    }

    // Never a guess

    @Test
    fun `a song two songs of the new phone could be is not matched, and is listed as not found`() {
        val twinA = at(NEW, "Music/Copies A/Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_500, 950, 5_100_000)
        val twinB = at(NEW, "Music/Copies B/Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 239_500, 951, 5_200_000)
        val phone = newPhone()

        val result = move(phone = phone, phoneLibrary = newLibrary - newWater + twinA + twinB)

        assertEquals(1, result.notFound)
        assertEquals(listOf(newRoad.ref), phone.playlists.playlists.first { it.name == "Night drive" }.songs)
        assertEquals(listOf(newLantern.ref), phone.playlists.favouriteSongs)
    }

    @Test
    fun `of two songs that fit, the one of the same size is taken`() {
        val copy = at(NEW, "Music/Copies/Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_500, 950, 5_000_000)
        val other = at(NEW, "Music/Others/Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 239_500, 951, 5_200_000)
        val phone = newPhone()

        move(phone = phone, phoneLibrary = newLibrary - newWater + copy + other)

        assertEquals(listOf(copy.ref, newLantern.ref), phone.playlists.favouriteSongs)
    }

    @Test
    fun `two songs of the backup that one song of the new phone could be are neither matched`() {
        // On the old phone the same song twice, in two folders; on the new phone one copy, in a third.
        val copy = at(OLD, "Download/Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_000, 17, 5_000_000)
        val old = oldPhone().apply { playlists = PlaylistsState(listOf(Playlist("p", "Both", listOf(water.ref, copy.ref), createdAt = T0))) }
        val single = at(NEW, "Music/Ferry/Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_000, 950, 5_000_000)
        val phone = newPhone()

        move(old = old, library = oldLibrary + copy, phone = phone, phoneLibrary = newLibrary - newWater + single)

        assertEquals(listOf(emptyList<SongRef>()), phone.playlists.playlists.map { it.songs })
    }

    @Test
    fun `another song at the same path below the root is not taken for it`() {
        // Another song, of another length, where Salt Road was.
        val impostor = at(NEW, "Music/Night Ferry/02 Salt Road.mp3", "Harbour Lights", "Ada Querne", "Night Ferry", 150_000, 950, 4_400_000)
        val phone = newPhone()

        val result = move(phone = phone, phoneLibrary = newLibrary - newRoad + impostor)

        assertEquals(1, result.notFound)
        assertEquals(listOf(newWater.ref), phone.playlists.playlists.first { it.name == "Night drive" }.songs)
    }

    @Test
    fun `a path below the storage root is the path without its volume`() {
        assertEquals("Music/Rips/01 Song.mp3", SongMatcher.relativeToRoot("/storage/emulated/0/Music/Rips/01 Song.mp3"))
        assertEquals("Music/Rips/01 Song.mp3", SongMatcher.relativeToRoot("/storage/7F3A-11C2/Music/Rips/01 Song.mp3"))
        assertEquals("Song.mp3", SongMatcher.relativeToRoot("/storage/emulated/10/Song.mp3"))
        assertNull(SongMatcher.relativeToRoot("/storage/emulated/x/Song.mp3"))
        assertNull(SongMatcher.relativeToRoot("/sdcard/Music/Song.mp3"))
        assertNull(SongMatcher.relativeToRoot("Music/Song.mp3"))
    }

    // Review of 2026-11-11 (panel-bk): never a guess at a path, all or nothing per part, settings over the phone's own

    @Test
    fun `another song put at the same path, of about the same length, is not taken for the one that moved`() {
        // Where Salt Road was, another song a second shorter; Salt Road itself moved to another folder.
        val impostor = at(NEW, "Music/Night Ferry/02 Salt Road.mp3", "Harbour Lights", "Ada Querne", "Night Ferry", 198_500, 950, 4_000_000)
        val movedRoad = at(NEW, "Music/Moved/02 Salt Road.mp3", "Salt Road", "Mira Solen", "Night Ferry", 199_000, 951, 4_400_000)
        val phone = newPhone()

        move(phone = phone, phoneLibrary = newLibrary - newRoad + impostor + movedRoad)

        assertEquals(listOf(newWater.ref, movedRoad.ref), phone.playlists.playlists.first { it.name == "Night drive" }.songs)
    }

    @Test
    fun `a song the backup has no tags for is not taken by its path alone`() {
        // Gone from the old phone's library, still named by a playlist; another file sits at that path on the new phone.
        val gone = SongRef(OLD + "Music/Gone/Lost Road.mp3")
        val there = at(OLD, "Music/Gone/Lost Road.mp3", "Night Bus", "Ada Querne", null, 210_000, 950, 4_000_000)
        val old = oldPhone().apply { playlists = PlaylistsState(listOf(Playlist("p", "Gone", listOf(gone, lantern.ref), createdAt = T0))) }
        val phone = newPhone()

        val result = move(old = old, phone = phone, phoneLibrary = newLibrary + there)

        assertEquals(listOf(newLantern.ref), phone.playlists.playlists.single().songs)
        assertEquals(1, result.notFound)
    }

    @Test
    fun `two songs at one path, case aside, are neither taken, not even by their tags`() {
        val twin = at(NEW, "Music/Night Ferry/01 low water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_000, 950, 5_200_000)
        val phone = newPhone()

        move(phone = phone, phoneLibrary = newLibrary + twin)

        assertEquals(listOf(newRoad.ref), phone.playlists.playlists.first { it.name == "Night drive" }.songs)
    }

    @Test
    fun `a path or a title in another Unicode form is the same`() {
        val rue = at(OLD, "Music/Caf\u00e9 Nights/01 Rue.mp3", "Rue", "Ada Querne", "Caf\u00e9 Nights", 180_000, 20, 3_000_000)
        val bleu = at(OLD, "Music/Bleu/Caf\u00e9.mp3", "Caf\u00e9", "Ada Querne", "Bleu", 170_000, 21, 2_900_000)
        // The same folder, written decomposed on the new phone, and the song retagged: only its path finds it.
        val newRue = at(NEW, "Music/Cafe\u0301 Nights/01 Rue.mp3", "Rue (Live)", "Ada Querne", "Cafe\u0301 Nights", 180_400, 960, 3_000_000)
        // Moved, its title decomposed: its tags find it.
        val newBleu = at(NEW, "Music/Elsewhere/Cafe\u0301.mp3", "Cafe\u0301", "Ada Querne", "Bleu", 170_000, 961, 2_900_000)
        val old = oldPhone().apply { playlists = PlaylistsState(listOf(Playlist("p", "Nights", listOf(rue.ref, bleu.ref), createdAt = T0))) }
        val phone = newPhone()

        move(old = old, library = oldLibrary + rue + bleu, phone = phone, phoneLibrary = newLibrary + newRue + newBleu)

        assertEquals(listOf(newRue.ref, newBleu.ref), phone.playlists.playlists.single().songs)
    }

    @Test
    fun `a tone whose song is not on the phone is left out and counted, never kept on the old path`() {
        val phone = newPhone()

        val result = move(phone = phone, phoneLibrary = newLibrary - newWater)

        assertEquals(emptyMap<Long, SavedTone>(), phone.tones.tones)
        assertEquals(2, result.tonesNotFound)
    }

    @Test
    fun `when Repeat can't be kept, the queues stay as they were and the part is named as failed`() {
        val phone = newPhone().apply { repeatFails = true }

        val result = move(phone = phone)

        assertEquals(QueueBook(), phone.queues)
        assertEquals(listOf(Section.QUEUES), result.failed)
    }

    @Test
    fun `when the AI switch can't be set, the settings stay as they were and the part is named as failed`() {
        val phone = newPhone().apply { aiFails = true }

        val result = move(phone = phone)

        assertEquals(Settings(nav = NavSettings(setupDone = true)), phone.settings)
        assertEquals(listOf(Section.SETTINGS), result.failed)
    }

    @Test
    fun `Covers & tags and the names kept apart are restored apart, so one failing leaves the other`() {
        val noAlbums = newPhone().apply { failing += Section.COVERS }
        val noNames = newPhone().apply { failing += Section.NAMES }

        val one = move(phone = noAlbums)
        val two = move(phone = noNames)

        assertEquals(CoversTagsMemory() to setOf(NamePair.of(NameKind.ARTIST, "Odo Brannick", "Odo Branick")), noAlbums.coversTags to noAlbums.keptApart)
        assertEquals(listOf(Section.COVERS), one.failed)
        assertEquals(2 to emptySet<NamePair>(), noNames.coversTags.albums.size to noNames.keptApart)
        assertEquals(listOf(Section.NAMES), two.failed)
    }

    // Settings over the phone's own

    /** The phone's own settings, none of them the defaults. */
    private val mine = Settings(
        playback = PlaybackSettings(rememberPlace = RememberPlace.OFF, skipBackSeconds = 5),
        library = LibrarySettings(foldersToSkip = listOf("Podcasts"), showFileNames = true),
        lyrics = LyricsSettings(textSize = LyricsSize.XL),
        nav = NavSettings(setupDone = true),
    )

    /** [settings] (a `settings.json` as some KJB wrote it) restored over [phone]'s own. */
    private fun restoreSettings(settings: String, phone: Settings = mine): Settings {
        val stores = MemoryStores(settings = phone)
        val zip = zipOf(
            listOf(
                "manifest.json" to """{"format":2,"appVersion":"3.0.0","createdAt":$T0,"sections":{"settings":{"version":1}}}""",
                "settings.json" to settings,
            ).map { (name, text) -> name to text.encodeToByteArray() },
        )
        runBlocking { RestorePlan(BackupFixtures.contents(zip), newLibrary).apply(setOf(Section.SETTINGS), stores, newId) }
        return stores.settings
    }

    @Test
    fun `a backup's settings change only what they carry, the phone keeps the rest`() {
        assertEquals(mine.copy(playback = mine.playback.copy(countPlayAfterPercent = 70)), restoreSettings("""{"version":3,"playback":{"countPlayAfterPercent":70}}"""))
        assertEquals(mine.copy(lyrics = mine.lyrics.copy(textAlign = LyricsAlign.CENTRE)), restoreSettings("""{"version":3,"lyrics":{"textAlign":"CENTRE","textSize":"HUGE"}}"""))
    }

    @Test
    fun `an older backup's settings go through the same steps as an older settings file`() {
        // Before version 3 every file wrote Long songs, the old default, so it is no choice: the phone keeps its own.
        assertEquals(RememberPlace.OFF, restoreSettings("""{"version":2,"playback":{"rememberPlace":"LONG_SONGS"}}""").playback.rememberPlace)
        assertEquals(RememberPlace.ALL_SONGS, restoreSettings("""{"version":2,"playback":{"rememberPlace":"ALL_SONGS"}}""").playback.rememberPlace)
        assertEquals(RememberPlace.LONG_SONGS, restoreSettings("""{"version":3,"playback":{"rememberPlace":"LONG_SONGS"}}""").playback.rememberPlace)
        // Version 1 had Playlists last among the Library tabs: it moves right after Songs, as on disk.
        val tabs = restoreSettings("""{"version":1,"library":{"tabs":[{"tab":"SONGS","shown":true},{"tab":"ALBUMS","shown":true},{"tab":"PLAYLISTS","shown":true}]}}""").library.tabs
        assertEquals(listOf(LibraryTab.SONGS, LibraryTab.PLAYLISTS, LibraryTab.ALBUMS), tabs.take(3).map { it.tab })
    }

    @Test
    fun `a newer KJB's settings are read for what this one knows, the phone keeping the rest`() {
        val read = restoreSettings("""{"version":9,"playback":{"rememberPlace":"LONG_SONGS","laterChoice":true},"laterSection":{"x":1}}""")

        assertEquals(mine.copy(playback = mine.playback.copy(rememberPlace = RememberPlace.LONG_SONGS)), read)
    }

    private companion object {
        const val OLD: String = "/storage/emulated/0/"
        const val NEW: String = "/storage/7F3A-11C2/"

        /** A made-up song at [relative] below [root]. */
        fun at(root: String, relative: String, title: String, artist: String?, album: String?, durationMs: Long, id: Long, size: Long): Song = Song(
            ref = SongRef(root + relative, id),
            title = title,
            artist = artist,
            album = album,
            albumArtist = null,
            durationMs = durationMs,
            track = null,
            disc = null,
            year = null,
            genre = null,
            folder = relative.substringBeforeLast('/', missingDelimiterValue = "") + "/",
            addedAt = T0,
            modifiedAt = T0,
            sizeBytes = size,
            mimeType = "audio/mpeg",
        )
    }
}
