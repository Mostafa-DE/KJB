package app.lyrify.backup

import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqProfilesJson
import app.lyrify.backup.SectionJson.flag
import app.lyrify.backup.SectionJson.int
import app.lyrify.backup.SectionJson.jsonObject
import app.lyrify.backup.SectionJson.long
import app.lyrify.backup.SectionJson.text
import app.lyrify.data.LedgerJson
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.library.PlaylistsJson
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.AbPart
import app.lyrify.player.QueueBook
import app.lyrify.player.QueuesJson
import app.lyrify.player.Repeat
import app.lyrify.player.SongState
import app.lyrify.player.Speed
import app.lyrify.settings.BackupSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import app.lyrify.stats.LoggedPlay
import app.lyrify.tags.CoversTagsMemory
import app.lyrify.tags.NamePair
import app.lyrify.tones.TonesState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A part of a backup the user can choose to restore, one JSON file in the zip each, in the restore summary's order. */
enum class Section(val key: String, val version: Int) {
    /** Playlists and Favourites (songs, albums, artists, folders and playlists). */
    PLAYLISTS("playlists", 1),

    /** The queue playing at its song and second, the saved and recent queues, and Repeat. */
    QUEUES("queues", 1),

    /** The play log (`plays.db`): every play, so each song's play count too. */
    PLAYS("plays", 1),

    /** `song-state.json`: bookmarks, Remember my place's places, the speeds and the part repeating. */
    BOOKMARKS("bookmarks", 1),

    /** The equalizer's profiles, by output and Bluetooth device name, and the user's own presets. */
    EQUALIZER("equalizer", 1),

    /** The lyrics ledger: what KJB did with each song's lyrics and what the user chose. */
    LYRICS("lyrics", 1),

    /**
     * `settings.json`, without Settings > Backup and the devices picked for Start when connected (only their names), which
     * belong to the phone; with the AI lyrics switch.
     */
    SETTINGS("settings", 1),

    /** "Your tones": how each was cut, found again on the phone by its file in Ringtones, Notifications or Alarms. */
    TONES("tones", 1),

    /** Duplicates: the groups marked "Not duplicates" and the groups opened, each by its songs. */
    DUPLICATES("duplicates", 1),

    /** Covers & tags: the albums done or skipped, and the names kept apart with "Not the same". */
    COVERS("covers", 1),

    /** Search's recent searches. */
    SEARCHES("searches", 1),
    ;

    /** Its file in the zip. */
    val file: String get() = "$key.json"
}

/**
 * A song a backup names, as the library had it when the backup was made, so another phone can find it ([SongMatcher]):
 * its path below the storage root ([relativePath], "Music/Rips/01 Song.mp3"), then its tags, length and size. Backups
 * from before 3.0's complete backup have neither [relativePath] nor [sizeBytes].
 */
data class BackupSong(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val relativePath: String? = null,
    val sizeBytes: Long? = null,
)

/** What the stores held when the backup was made; a store that could not be read is null, and its section is left out. */
data class BackupSnapshot(
    val playlists: PlaylistsState? = null,
    val queues: QueueBook? = null,
    val plays: List<LoggedPlay>? = null,
    val songState: SongState? = null,
    val equalizer: EqProfiles? = null,
    val ledger: LedgerState? = null,
    val settings: Settings? = null,
    /** Repeat, kept with the queue playing (`playback.json`). */
    val repeat: Repeat? = null,
    /** The AI lyrics switch, kept with the settings. */
    val aiLyrics: Boolean? = null,
    val tones: TonesState? = null,
    val duplicates: DuplicateChoices? = null,
    val coversTags: CoversTagsMemory? = null,
    val keptApart: Set<NamePair>? = null,
    val searches: List<String>? = null,
)

/**
 * A backup read and checked, before anything is written: each section this version can restore (null when the backup
 * has none), the songs they name, and what was skipped.
 */
data class BackupContents(
    /** When it was made, wall-clock millis. */
    val createdAt: Long,
    /** The KJB version that made it. */
    val appVersion: String,
    val songs: List<BackupSong> = emptyList(),
    val playlists: PlaylistsState? = null,
    /** The saved and recent queues, and the one playing ([QueueBook.currentId]) at its song and second. */
    val queues: QueueBook? = null,
    /** Repeat, with the queues; null in a backup from before it was kept. */
    val repeat: Repeat? = null,
    val plays: List<LoggedPlay>? = null,
    /** Places, bookmarks and own speeds, by song. */
    val bookmarks: List<BackupMarks>? = null,
    /** With the bookmarks: the speed of every other song, and the part repeating; null in a backup from before they were kept. */
    val usualSpeed: Speed? = null,
    val part: AbPart? = null,
    val equalizer: EqProfiles? = null,
    val lyrics: LedgerState? = null,
    val settings: Settings? = null,
    /** With the settings: the AI lyrics switch; null in a backup from before it was kept. */
    val aiLyrics: Boolean? = null,
    /** With the settings: the names of the devices picked for Start when connected, to pick again on this phone. */
    val connectNames: List<String> = emptyList(),
    val tones: List<BackupTone>? = null,
    val duplicates: BackupDuplicates? = null,
    val covers: BackupCovers? = null,
    val searches: List<String>? = null,
    /** Sections a newer KJB wrote in a form this version cannot read: skipped, and the summary says so. */
    val tooNew: List<Section> = emptyList(),
    /** How many sections this version does not know at all (a newer KJB's): skipped. */
    val unknown: Int = 0,
) {
    /** The sections it holds, in the summary's order. */
    val sections: List<Section> get() = Section.entries.filter(::has)

    fun has(section: Section): Boolean = when (section) {
        Section.PLAYLISTS -> playlists != null
        Section.QUEUES -> queues != null
        Section.PLAYS -> plays != null
        Section.BOOKMARKS -> bookmarks != null
        Section.EQUALIZER -> equalizer != null
        Section.LYRICS -> lyrics != null
        Section.SETTINGS -> settings != null
        Section.TONES -> tones != null
        Section.DUPLICATES -> duplicates != null
        Section.COVERS -> covers != null
        Section.SEARCHES -> searches != null
    }
}

/** A picked file read as a backup: its contents, or why nothing in it can be restored. */
sealed interface BackupRead {
    data class Read(val contents: BackupContents) : BackupRead

    /** "Couldn't read this backup": nothing is restored. [why] is for the log. */
    data class Unreadable(val why: Why) : BackupRead

    enum class Why {
        /** Not a zip, or cut short, or damaged (an entry's CRC). */
        CORRUPT,

        /** No manifest, or one that is no backup's, or a section that is not what it says. */
        NOT_A_BACKUP,

        /** Bigger unpacked than [ZipLimits] allows: a zip bomb, or not KJB's. */
        TOO_BIG,

        /** Two entries of one name: which one counts would be a guess. */
        DUPLICATE,
    }
}

/** How much a backup may hold once unpacked; reading stops as soon as one is passed, so a zip bomb costs little. */
data class ZipLimits(
    /** One entry, unpacked. */
    val entryBytes: Long = 32L * 1024 * 1024,
    /** Every entry together, unpacked, also those not read. */
    val totalBytes: Long = 96L * 1024 * 1024,
    val entries: Int = 64,
)

/**
 * The backup zip (spec "Backup and restore"; user feedback 2026-11-11, point 23: everything KJB knows, so a new phone
 * shows KJB exactly as the old one did): `manifest.json` (the format version, the KJB version and when it was made,
 * nothing about the phone, and each section with its version), `songs.json` (every song a section names, with its path
 * below the storage root, title, artist, album, length and size, so another phone can find it), then one JSON file per
 * [Section]: the stores' own stored forms where they name songs by path only, else the backup's own form
 * ([SectionJson]). Never in it: the songs and their `.lrc` files (they move with the Music folder), the AI models,
 * caches, Covers & tags' Undo copies, MediaStore ids, Bluetooth addresses and Start when connected's links, the
 * automatic backup's folder.
 *
 * Format 2 added the paths below the root and sizes, the queue playing, Repeat, the speeds and the part repeating, the
 * AI switch, and the tones, Duplicates, Covers & tags and recent searches sections, all as new keys or sections: format
 * 1 backups read as before, and a format 1 KJB reads a format 2 backup's sections it knows.
 *
 * Read as untrusted input: only the known entry names are kept, no entry name ever becomes a path, each entry and all of
 * them together are capped unpacked ([ZipLimits]), two entries of one name make it unreadable, and every section is
 * decoded and checked before a restore writes anything.
 */
object BackupBundle {
    const val FORMAT: Int = 2
    const val MANIFEST: String = "manifest.json"
    const val SONGS: String = "songs.json"
    const val SONGS_VERSION: Int = 1
    const val MIME_TYPE: String = "application/zip"

    /** What the open dialog offers: zip files, as providers name them. */
    val MIME_TYPES: Array<String> = arrayOf(MIME_TYPE, "application/x-zip-compressed", "application/octet-stream")

    /** `KJB backup 2026-11-10.zip`. */
    fun fileName(date: LocalDate): String = "KJB backup $date.zip"

    // Writing

    /**
     * Writes the backup of [snapshot] to [output] and closes it: the manifest, the songs the sections name (their tags
     * from [library], the songs MediaStore lists), then each section a store could be read for.
     */
    fun write(snapshot: BackupSnapshot, library: List<Song>, appVersion: String, createdAt: Long, output: OutputStream) {
        val pathOf = library.mapNotNull { song -> song.ref.mediaStoreId?.let { it to song.path } }.toMap()
        val sections = buildList {
            snapshot.playlists?.let { add(Section.PLAYLISTS to PlaylistsJson.encode(bare(it))) }
            snapshot.queues?.let { add(Section.QUEUES to withKeys(QueuesJson.encode(bare(it)), REPEAT_KEY to snapshot.repeat?.name?.let(::JsonPrimitive))) }
            snapshot.plays?.let { add(Section.PLAYS to SectionJson.plays(it)) }
            snapshot.songState?.let { add(Section.BOOKMARKS to SectionJson.marks(it)) }
            snapshot.equalizer?.let { add(Section.EQUALIZER to EqProfilesJson.encode(it.copy(unknownProfiles = emptyMap()))) }
            snapshot.ledger?.let { add(Section.LYRICS to LedgerJson.encode(bare(it))) }
            snapshot.settings?.let { add(Section.SETTINGS to settings(it, snapshot.aiLyrics)) }
            snapshot.tones?.let { add(Section.TONES to SectionJson.tones(it)) }
            snapshot.duplicates?.let { add(Section.DUPLICATES to SectionJson.duplicates(it, pathOf::get)) }
            if (snapshot.coversTags != null || snapshot.keptApart != null) {
                add(Section.COVERS to SectionJson.covers(snapshot.coversTags ?: CoversTagsMemory(), snapshot.keptApart.orEmpty()))
            }
            snapshot.searches?.let { add(Section.SEARCHES to SectionJson.searches(it)) }
        }
        val byPath = library.associateBy { it.path }
        val songs = named(snapshot, pathOf::get).mapNotNull(byPath::get).map { song ->
            BackupSong(song.path, song.title, song.artist, song.album, song.durationMs, (song.folder + song.fileName).trimStart('/'), song.sizeBytes.takeIf { it > 0 })
        }
        ZipOutputStream(output).use { zip ->
            zip.put(MANIFEST, manifest(appVersion, createdAt, sections.map { it.first }))
            zip.put(SONGS, songs(songs))
            sections.forEach { (section, text) -> zip.put(section.file, text) }
        }
    }

    private fun ZipOutputStream.put(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.encodeToByteArray())
        closeEntry()
    }

    /** Every song path a section names, in order, each once; Duplicates' groups through [pathOf], their MediaStore ids. */
    private fun named(snapshot: BackupSnapshot, pathOf: (Long) -> String?): List<String> = buildList {
        snapshot.playlists?.let { state -> state.playlists.forEach { playlist -> playlist.songs.forEach { add(it.path) } }; state.favouriteSongs.forEach { add(it.path) } }
        snapshot.queues?.let { book -> book.queues.forEach { queue -> queue.items.forEach { add(it.song.path) } } }
        snapshot.plays?.forEach { add(it.song.path) }
        snapshot.songState?.let { state -> addAll(state.places.keys + state.bookmarks.keys + state.speeds.keys + listOfNotNull(state.part?.path)) }
        snapshot.ledger?.let { state -> addAll(bare(state).entries.keys) }
        snapshot.tones?.let { state -> state.tones.values.forEach { add(it.songPath) } }
        snapshot.duplicates?.let { choices -> (choices.notDuplicates + choices.reviewed).forEach { key -> key.split(',').mapNotNull { it.toLongOrNull()?.let(pathOf) }.forEach(::add) } }
        snapshot.coversTags?.let { memory -> memory.albums.forEach { album -> album.songs.forEach { add(it.path) } } }
    }.distinct()

    /** A song as a backup keeps it: by its path only, as a MediaStore id names a file on this phone alone. */
    private fun bare(ref: SongRef): SongRef = if (ref.mediaStoreId == null) ref else SongRef(ref.path)

    /** The playlists without ids, nor what only a newer version could read. */
    private fun bare(state: PlaylistsState): PlaylistsState = PlaylistsState(
        playlists = state.playlists.map { playlist -> playlist.copy(songs = playlist.songs.map(::bare)) },
        favouriteSongs = state.favouriteSongs.map(::bare),
        favouriteCollections = state.favouriteCollections,
    )

    /** Every queue as it is, the one playing at its song and second, without ids. */
    private fun bare(book: QueueBook): QueueBook = book.copy(queues = book.queues.map { queue -> queue.copy(items = queue.items.map { it.copy(song = bare(it.song)) }) })

    /** The lyrics choices: no write cut short (its entry holds lyrics text), nor what only a newer version could read. */
    private fun bare(state: LedgerState): LedgerState = LedgerState(entries = state.entries.filterValues { it.status != LyricsStatus.WRITING })

    /**
     * The settings without Settings > Backup, and Start when connected's devices by their names only: Android links each
     * device to one phone (Companion Device Manager), so the user picks them again there. With the AI switch, when known.
     */
    private fun settings(settings: Settings, ai: Boolean?): String {
        val bare = settings.copy(backup = BackupSettings(), playback = settings.playback.copy(connectDevices = emptyList()))
        return withKeys(
            SettingsJson.encode(bare),
            AI_KEY to ai?.let(::JsonPrimitive),
            CONNECT_KEY to JsonArray(settings.playback.connectDevices.map { JsonPrimitive(it.name) }),
        )
    }

    /** [text], a JSON object, with [extra] keys its store's form does not hold (those null left out). */
    private fun withKeys(text: String, vararg extra: Pair<String, JsonElement?>): String =
        JsonObject(checkNotNull(jsonObject(text)) + extra.mapNotNull { (key, value) -> value?.let { key to it } }).toString()

    private fun manifest(appVersion: String, createdAt: Long, sections: List<Section>): String = JsonObject(
        mapOf(
            FORMAT_KEY to JsonPrimitive(FORMAT),
            APP_VERSION_KEY to JsonPrimitive(appVersion),
            CREATED_AT_KEY to JsonPrimitive(createdAt),
            SectionJson.SONGS_KEY to version(SONGS_VERSION),
            SECTIONS_KEY to JsonObject(sections.associate { it.key to version(it.version) }),
        ),
    ).toString()

    private fun version(version: Int): JsonObject = JsonObject(mapOf(VERSION_KEY to JsonPrimitive(version)))

    private fun songs(songs: List<BackupSong>): String = JsonObject(
        mapOf(
            SectionJson.SONGS_KEY to JsonArray(
                songs.map { song ->
                    JsonObject(
                        buildMap {
                            put(SectionJson.PATH_KEY, JsonPrimitive(song.path))
                            put(SectionJson.TITLE_KEY, JsonPrimitive(song.title))
                            song.artist?.let { put(SectionJson.ARTIST_KEY, JsonPrimitive(it)) }
                            song.album?.let { put(ALBUM_KEY, JsonPrimitive(it)) }
                            put(DURATION_KEY, JsonPrimitive(song.durationMs))
                            song.relativePath?.let { put(RELATIVE_PATH_KEY, JsonPrimitive(it)) }
                            song.sizeBytes?.let { put(SIZE_KEY, JsonPrimitive(it)) }
                        },
                    )
                },
            ),
        ),
    ).toString()

    // Reading

    /**
     * The backup in [input], read whole (and closed) before anything is restored. Only the manifest, the songs and the
     * sections are kept; every entry counts towards [limits] as it is unpacked, read or not. A section the manifest lists
     * must be there and read as what it says; one from a newer KJB ([Section.version]) or one this version does not know
     * is skipped. Unknown keys are ignored throughout, and items a store's own reading leaves out cost nothing else.
     */
    fun read(input: InputStream, limits: ZipLimits = ZipLimits()): BackupRead {
        val files = when (val unzipped = unzip(input, limits)) {
            is Unzipped.Files -> unzipped.files
            is Unzipped.Refused -> return BackupRead.Unreadable(unzipped.why)
        }
        val notABackup = BackupRead.Unreadable(BackupRead.Why.NOT_A_BACKUP)
        val manifest = files[MANIFEST]?.let { jsonObject(it.decodeToString()) } ?: return notABackup
        if (manifest.int(FORMAT_KEY) == null) return notABackup
        val songsVersion = (manifest[SectionJson.SONGS_KEY] as? JsonObject)?.int(VERSION_KEY)
        val songs = when {
            songsVersion == null -> emptyList()
            songsVersion < 1 -> return notABackup
            // A newer form of the songs: the sections are still found by their paths.
            songsVersion > SONGS_VERSION -> emptyList()
            else -> songs(files[SONGS]?.decodeToString() ?: return notABackup) ?: return notABackup
        }
        var contents = BackupContents(
            createdAt = manifest.long(CREATED_AT_KEY) ?: 0,
            appVersion = manifest.text(APP_VERSION_KEY).orEmpty(),
            songs = songs,
        )
        val tooNew = mutableListOf<Section>()
        var unknown = 0
        (manifest[SECTIONS_KEY] as? JsonObject).orEmpty().forEach { (key, about) ->
            val section = Section.entries.firstOrNull { it.key == key }
            val version = (about as? JsonObject)?.int(VERSION_KEY)
            when {
                section == null -> unknown++
                version == null || version < 1 -> return notABackup
                version > section.version -> tooNew += section
                else -> contents = section(contents, section, files[section.file]?.decodeToString() ?: return notABackup) ?: return notABackup
            }
        }
        return BackupRead.Read(contents.copy(tooNew = tooNew.sorted(), unknown = unknown))
    }

    /** [contents] with [section] read from [text]; null when it is not what it says. */
    private fun section(contents: BackupContents, section: Section, text: String): BackupContents? = when (section) {
        Section.PLAYLISTS -> PlaylistsJson.decode(text)?.let { contents.copy(playlists = bare(it)) }
        Section.QUEUES -> QueuesJson.decode(text)?.let { book ->
            contents.copy(queues = book, repeat = jsonObject(text)?.text(REPEAT_KEY)?.let { name -> Repeat.entries.firstOrNull { it.name == name } })
        }
        Section.PLAYS -> SectionJson.plays(text)?.let { contents.copy(plays = it) }
        Section.BOOKMARKS -> SectionJson.marks(text)?.let { contents.copy(bookmarks = it.marks, usualSpeed = it.usual, part = it.part) }
        Section.EQUALIZER -> EqProfilesJson.decode(text)?.let { contents.copy(equalizer = it.copy(unknownProfiles = emptyMap())) }
        Section.LYRICS -> ledger(text)?.let { contents.copy(lyrics = bare(it)) }
        Section.SETTINGS -> SettingsJson.decode(text)?.let { settings ->
            val root = jsonObject(text)
            // A format 1 backup kept the devices with this phone's links: their names only.
            val names = (root?.get(CONNECT_KEY) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { name -> name.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }
                ?: settings.playback.connectDevices.map { it.name }
            contents.copy(
                settings = settings.copy(backup = BackupSettings(), playback = settings.playback.copy(connectDevices = emptyList())),
                aiLyrics = root?.flag(AI_KEY),
                connectNames = names.distinct(),
            )
        }
        Section.TONES -> SectionJson.tones(text)?.let { contents.copy(tones = it) }
        Section.DUPLICATES -> SectionJson.duplicates(text)?.let { contents.copy(duplicates = it) }
        Section.COVERS -> SectionJson.covers(text)?.let { contents.copy(covers = it) }
        Section.SEARCHES -> SectionJson.searches(text)?.let { contents.copy(searches = it) }
    }

    private fun ledger(text: String): LedgerState? = try {
        LedgerJson.decode(text)
    } catch (_: SerializationException) {
        null
    }

    /** The songs, each read on its own: one without a path, a title or a length is left out; the path below the root and the size may be missing. */
    private fun songs(text: String): List<BackupSong>? = (jsonObject(text)?.get(SectionJson.SONGS_KEY) as? JsonArray)?.mapNotNull { element ->
        val song = element as? JsonObject ?: return@mapNotNull null
        BackupSong(
            path = song.text(SectionJson.PATH_KEY)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null,
            title = song.text(SectionJson.TITLE_KEY) ?: return@mapNotNull null,
            artist = song.text(SectionJson.ARTIST_KEY),
            album = song.text(ALBUM_KEY),
            durationMs = song.long(DURATION_KEY)?.takeIf { it >= 0 } ?: return@mapNotNull null,
            relativePath = song.text(RELATIVE_PATH_KEY)?.trim('/')?.takeIf(String::isNotEmpty),
            sizeBytes = song.long(SIZE_KEY)?.takeIf { it > 0 },
        )
    }?.distinctBy { it.path }

    private sealed interface Unzipped {
        data class Files(val files: Map<String, ByteArray>) : Unzipped

        data class Refused(val why: BackupRead.Why) : Unzipped
    }

    /**
     * Every entry of [input] unpacked, counted against [limits] as it goes; only [KEPT] names are kept, in memory, and
     * no name ever becomes a path. A damaged or cut zip is [BackupRead.Why.CORRUPT]: the entries' CRCs are checked, and a
     * cut that loses an entry leaves a section the manifest lists missing.
     */
    private fun unzip(input: InputStream, limits: ZipLimits): Unzipped {
        val kept = mutableMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var total = 0L
        val buffer = ByteArray(BUFFER)
        return try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (seen.size == limits.entries) return Unzipped.Refused(BackupRead.Why.TOO_BIG)
                    if (!seen.add(entry.name)) return Unzipped.Refused(BackupRead.Why.DUPLICATE)
                    val bytes = if (entry.name in KEPT) ByteArrayOutputStream() else null
                    var size = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size += read
                        total += read
                        if (size > limits.entryBytes || total > limits.totalBytes) return Unzipped.Refused(BackupRead.Why.TOO_BIG)
                        bytes?.write(buffer, 0, read)
                    }
                    bytes?.let { kept[entry.name] = it.toByteArray() }
                }
            }
            Unzipped.Files(kept)
        } catch (_: IOException) {
            // A ZipException (a bad CRC, a bad entry name on Android), or an EOFException: cut short.
            Unzipped.Refused(BackupRead.Why.CORRUPT)
        } catch (_: IllegalArgumentException) {
            // An entry name that is not UTF-8.
            Unzipped.Refused(BackupRead.Why.CORRUPT)
        }
    }

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private const val BUFFER: Int = 64 * 1024
    private const val FORMAT_KEY: String = "format"
    private const val APP_VERSION_KEY: String = "appVersion"
    private const val CREATED_AT_KEY: String = "createdAt"
    private const val SECTIONS_KEY: String = "sections"
    private const val VERSION_KEY: String = "version"
    private const val ALBUM_KEY: String = "album"
    private const val DURATION_KEY: String = "durationMs"
    private const val RELATIVE_PATH_KEY: String = "relativePath"
    private const val SIZE_KEY: String = "sizeBytes"
    private const val REPEAT_KEY: String = "repeat"
    private const val AI_KEY: String = "aiLyrics"
    private const val CONNECT_KEY: String = "startWhenConnected"

    /** The only entries read into memory. */
    private val KEPT: Set<String> = setOf(MANIFEST, SONGS) + Section.entries.map { it.file }
}
