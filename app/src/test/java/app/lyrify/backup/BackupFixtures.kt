package app.lyrify.backup

import app.lyrify.audio.BuiltInPreset
import app.lyrify.audio.EQ_BANDS_HZ
import app.lyrify.audio.EqProfile
import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqSound
import app.lyrify.audio.OutputKey
import app.lyrify.audio.OutputKind
import app.lyrify.audio.OwnPreset
import app.lyrify.audio.PresetChoice
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
import app.lyrify.library.song
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
import app.lyrify.settings.AudioSettings
import app.lyrify.settings.BackupSettings
import app.lyrify.settings.Levelling
import app.lyrify.settings.NavSettings
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import app.lyrify.library.search.withSearch
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A made-up phone for the backup tests: three songs, and every store holding something of each kind. Made-up names
 * only.
 */
object BackupFixtures {
    const val T0: Long = 1_800_000_000_000L
    const val VERSION: String = "3.0.0"

    val lantern: Song = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", album = "Harbour Glass", durationMs = 201_000, id = 11)
    val bell: Song = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", album = "Harbour Glass", durationMs = 185_000, id = 12)
    val water: Song = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry", durationMs = 240_000, id = 13)
    val library: List<Song> = listOf(lantern, bell, water)

    /** The same songs on a new phone: another volume and other folders, the same tags, lengths a second apart. */
    val newLantern: Song = moved(lantern, "/storage/1A2B-3C4D/Songs/Paper Lantern.flac", 201_900)
    val newBell: Song = moved(bell, "/storage/1A2B-3C4D/Songs/Tide Bell.flac", 184_200)
    val newWater: Song = moved(water, "/storage/1A2B-3C4D/Songs/Low Water.flac", 240_000)
    val newLibrary: List<Song> = listOf(newLantern, newBell, newWater)

    fun moved(song: Song, path: String, durationMs: Long): Song = song.copy(ref = SongRef(path, song.ref.mediaStoreId?.plus(100)), durationMs = durationMs)

    val roadTrip: Playlist = Playlist("p1", "Road trip", listOf(lantern.ref, bell.ref, lantern.ref), createdAt = T0)
    val playlists: PlaylistsState = PlaylistsState(
        playlists = listOf(roadTrip, Playlist("p2", "Night drive", listOf(water.ref), createdAt = T0 + 1)),
        favouriteSongs = listOf(water.ref, lantern.ref),
        favouriteCollections = listOf(FavouriteCollection(CollectionKind.ARTIST, "Mira Solen"), FavouriteCollection(CollectionKind.PLAYLIST, "p1")),
    )

    private val albumSource = QueueSource(SourceKind.ALBUM, "album:Harbour Glass", "Harbour Glass")
    private val ferrySource = QueueSource(SourceKind.ARTIST, "artist:Mira Solen", "Mira Solen")
    private val songsSource = QueueSource(SourceKind.SONGS, "songs", "Songs")

    /** Playing a songs list; Recent holds an album queue, and Saved a named queue. */
    val queues: QueueBook = QueueBook(
        queues = listOf(
            Queue("q1", albumSource, listOf(QueueItem(1, lantern.ref, 0), QueueItem(2, bell.ref, 1)), index = 1, positionMs = 30_000, updatedAt = T0),
            Queue("q2", ferrySource, listOf(QueueItem(3, water.ref, 0)), name = "Ferry songs", updatedAt = T0 + 1),
            Queue("q3", songsSource, listOf(QueueItem(4, bell.ref, 0), QueueItem(5, water.ref, 1)), updatedAt = T0 + 2),
        ),
        currentId = "q3",
        recent = listOf("q1"),
        saved = listOf("q2"),
        nextId = 4,
        nextUid = 6,
    )

    val plays: List<LoggedPlay> = listOf(
        LoggedPlay(lantern.ref, T0, 150_000, counted = true),
        LoggedPlay(bell.ref, T0 + 600_000, 20_000, counted = false),
        LoggedPlay(lantern.ref, T0 + 1_200_000, 201_000, counted = true),
    )

    val songState: SongState = SongState(
        usual = Speed(1.25f),
        speeds = mapOf(water.path to Speed(0.75f)),
        places = mapOf(water.path to 600_000L),
        bookmarks = mapOf(lantern.path to listOf(Bookmark(1, 30_000, "Chorus"), Bookmark(2, 95_000, ""))),
        part = AbPart(lantern.path, 30_000, 60_000),
        nextBookmarkId = 3,
        ids = mapOf(lantern.path to 11L, water.path to 13L),
    )

    val buds: OutputKey = OutputKey(OutputKind.BLUETOOTH, "Harbour Buds")
    val night: OwnPreset = OwnPreset("Night", List(EQ_BANDS_HZ.size) { if (it < 3) 4f else -2f })
    val equalizer: EqProfiles = EqProfiles(
        profiles = mapOf(
            OutputKey.SPEAKER to EqProfile(EqSound(enabled = true, bandsDb = BuiltInPreset.BASS.bandsDb), PresetChoice.BuiltIn(BuiltInPreset.BASS)),
            buds to EqProfile(EqSound(enabled = true, bandsDb = night.bandsDb, preampDb = -3f, balance = 0.2f), PresetChoice.Own("Night")),
        ),
        presets = listOf(night),
    )

    val ledger: LedgerState = LedgerState(
        entries = listOf(
            LedgerEntry(lantern.path, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, LyricsStatus.SYNCED, lrclibId = 41, updatedAt = T0, sha256 = "ab12", size = 900, source = LyricsSource.LRCLIB),
            LedgerEntry(bell.path, "Tide Bell", "Kit Calloway", "Harbour Glass", 185_000, LyricsStatus.REMOVED, updatedAt = T0, source = LyricsSource.LRCLIB),
            LedgerEntry(water.path, "Low Water", "Mira Solen", "Night Ferry", 240_000, LyricsStatus.NOT_FOUND, updatedAt = T0, declined = true, mine = false),
        ).associateBy { it.path },
    )

    /** A song whose `.lrc` was being written when the app died: its entry holds the text, which never goes in a backup. */
    val writing: LedgerEntry = LedgerEntry(VOLUME_SD + "Music/Gone/Salt Road.mp3", "Salt Road", "Mira Solen", null, 199_000, LyricsStatus.WRITING, updatedAt = T0, pendingText = "[00:01.00]A made-up line")

    val settings: Settings = Settings(
        playback = PlaybackSettings(countPlayAfterPercent = 70, skipBackSeconds = 15),
        audio = AudioSettings(levelling = Levelling.ALBUM, crossfadeSeconds = 4),
        backup = BackupSettings(automatic = true, folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups"),
        nav = NavSettings(setupDone = true),
    )

    /** One tone cut from Low Water, its file in Ringtones. */
    val tone: SavedTone = SavedTone(501, "Low Water (KJB)", ToneKind.RINGTONE, water.path, 30_000, 30_000, 500, 1_000, T0, "Low Water (KJB).m4a", 480_000)

    /** Paper Lantern and Tide Bell said not to be duplicates; the group of Tide Bell and Low Water opened. */
    val duplicates: DuplicateChoices = DuplicateChoices(notDuplicates = setOf("11,12"), reviewed = setOf("12,13"))

    /** Harbour Glass done in Covers & tags (its Undo stays here), Night Ferry skipped. */
    val coversTags: CoversTagsMemory = CoversTagsMemory(
        albums = listOf(
            AlbumRecord(albumId(listOf(lantern.path, bell.path)), Settled.DONE, "Harbour Glass", "Kit Calloway", listOf(FileMark(lantern.path, 4_000_000, T0), FileMark(bell.path, 4_000_000, T0)), at = T0 + 5, entryId = 7, changed = 2),
            AlbumRecord(albumId(listOf(water.path)), Settled.SKIPPED, "Night Ferry", "Mira Solen", listOf(FileMark(water.path, 4_000_000, T0)), at = T0 + 6),
        ),
        checkedOnce = true,
        noted = true,
        historySeen = 7,
    )
    val keptApart: Set<NamePair> = setOf(NamePair.of(NameKind.ARTIST, "Kit Calloway", "Kit Galloway"))
    val searches: List<String> = listOf("harbour", "mira")

    val snapshot: BackupSnapshot = BackupSnapshot(
        playlists, queues, plays, songState, equalizer, ledger.put(writing), settings,
        repeat = Repeat.ALL, aiLyrics = true, tones = TonesState(mapOf(tone.id to tone)), duplicates = duplicates, coversTags = coversTags,
        keptApart = keptApart, searches = searches,
    )

    const val VOLUME_SD: String = "/storage/1A2B-3C4D/"

    /** The backup of [snapshot] as bytes. */
    fun zip(snapshot: BackupSnapshot = this.snapshot, library: List<Song> = this.library): ByteArray =
        ByteArrayOutputStream().also { BackupBundle.write(snapshot, library, VERSION, T0, it) }.toByteArray()

    fun read(bytes: ByteArray, limits: ZipLimits = ZipLimits()): BackupRead = BackupBundle.read(ByteArrayInputStream(bytes), limits)

    /** The contents of a backup that must read. */
    fun contents(bytes: ByteArray = zip()): BackupContents = when (val read = read(bytes)) {
        is BackupRead.Read -> read.contents
        is BackupRead.Unreadable -> throw AssertionError("unreadable: ${read.why}")
    }

    /** Each entry of [bytes], by name, in order. */
    fun entries(bytes: ByteArray): List<Pair<String, String>> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.map { it.name to zip.readBytes().decodeToString() }.toList()
    }

    /** A zip of [entries], as written. */
    fun zipOf(entries: List<Pair<String, ByteArray>>, method: Int = ZipEntry.DEFLATED): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                val entry = ZipEntry(name)
                if (method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}

/**
 * A phone's stores in memory; a section in [failing] cannot be written, and its stores stay as they were. [toneFiles]
 * are the tone files on the phone, by folder, name and size; [files] the songs' files as they are now.
 */
class MemoryStores(
    var playlists: PlaylistsState = PlaylistsState(),
    var queues: QueueBook = QueueBook(),
    val log: FakePlayLog = FakePlayLog(),
    var songState: SongState = SongState(),
    var equalizer: EqProfiles = EqProfiles(),
    var ledger: LedgerState = LedgerState(),
    var settings: Settings = Settings(),
    val failing: MutableSet<Section> = mutableSetOf(),
    var repeat: Repeat = Repeat.OFF,
    var aiLyrics: Boolean = false,
    var tones: TonesState = TonesState(),
    val toneFiles: Map<Triple<String, String, Long>, Long> = emptyMap(),
    var duplicates: DuplicateChoices = DuplicateChoices(),
    var coversTags: CoversTagsMemory = CoversTagsMemory(),
    var keptApart: Set<NamePair> = emptySet(),
    var searches: List<String> = emptyList(),
    val files: Map<String, FileMark> = emptyMap(),
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

    override suspend fun repeat(): Repeat = repeat

    override suspend fun replaceRepeat(repeat: Repeat): Boolean = change(Section.QUEUES, this.repeat, { repeat }) { this.repeat = it }

    override suspend fun aiLyrics(): Boolean = aiLyrics

    override suspend fun replaceAiLyrics(on: Boolean): Boolean = change(Section.SETTINGS, aiLyrics, { on }) { aiLyrics = it }

    override suspend fun tones(): TonesState = tones

    override suspend fun addTones(tones: List<SavedTone>): Boolean = change(Section.TONES, this.tones, { now -> tones.fold(now) { list, tone -> list.put(tone) } }) { this.tones = it }

    override suspend fun findTone(folder: String, fileName: String, size: Long): Long? = toneFiles[Triple(folder, fileName, size)]

    override suspend fun duplicates(): DuplicateChoices = duplicates

    override suspend fun addDuplicates(choices: DuplicateChoices): Boolean = change(Section.DUPLICATES, duplicates, { now ->
        DuplicateChoices(now.notDuplicates + choices.notDuplicates, now.reviewed + choices.reviewed - choices.notDuplicates)
    }) { duplicates = it }

    override suspend fun coversTags(): CoversTagsMemory = coversTags

    override suspend fun addSettledAlbums(albums: List<AlbumRecord>, checkedOnce: Boolean, noted: Boolean): Boolean = change(Section.COVERS, coversTags, { memory ->
        val known = memory.albums.map { it.id }.toSet()
        memory.copy(albums = memory.albums + albums.filterNot { it.id in known }, checkedOnce = memory.checkedOnce || checkedOnce, noted = memory.noted || noted)
    }) { coversTags = it }

    override suspend fun keptApart(): Set<NamePair> = keptApart

    override suspend fun addKeptApart(pairs: Set<NamePair>): Boolean = change(Section.COVERS, keptApart, { it + pairs }) { keptApart = it }

    override suspend fun searches(): List<String> = searches

    override suspend fun addSearches(searches: List<String>): Boolean = change(Section.SEARCHES, this.searches, { now ->
        searches.filterNot { it in now }.asReversed().fold(now, ::withSearch)
    }) { this.searches = it }

    override suspend fun fileMark(path: String): FileMark? = files[path]
}
