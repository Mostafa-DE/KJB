package app.lyrify.backup

import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqProfilesJson
import app.lyrify.data.LedgerJson
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.library.PlaylistsJson
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.QueueBook
import app.lyrify.player.QueuesJson
import app.lyrify.player.SongState
import app.lyrify.settings.BackupSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import app.lyrify.stats.LoggedPlay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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

    /** Saved and recent queues. */
    QUEUES("queues", 1),

    /** The play log (`plays.db`). */
    PLAYS("plays", 1),

    /** Bookmarks and Remember my place's places (`song-state.json`). */
    BOOKMARKS("bookmarks", 1),

    /** The equalizer's profiles, by output and Bluetooth device name, and the user's own presets. */
    EQUALIZER("equalizer", 1),

    /** The lyrics ledger: what KJB did with each song's lyrics and what the user chose. */
    LYRICS("lyrics", 1),

    /** `settings.json`, without Settings > Backup, which belongs to the phone. */
    SETTINGS("settings", 1),
    ;

    /** Its file in the zip. */
    val file: String get() = "$key.json"
}

/** A song a backup names, as the library had it when the backup was made, so another phone can find it by its tags. */
data class BackupSong(val path: String, val title: String, val artist: String?, val album: String?, val durationMs: Long)

/** A song's bookmarks (a spot and the user's note) and its place (Remember my place), as a backup keeps them. */
data class BackupMarks(val path: String, val placeMs: Long?, val bookmarks: List<BackupBookmark>)

data class BackupBookmark(val positionMs: Long, val note: String)

/** What the stores held when the backup was made; a store that could not be read is null, and its section is left out. */
data class BackupSnapshot(
    val playlists: PlaylistsState? = null,
    val queues: QueueBook? = null,
    val plays: List<LoggedPlay>? = null,
    val songState: SongState? = null,
    val equalizer: EqProfiles? = null,
    val ledger: LedgerState? = null,
    val settings: Settings? = null,
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
    val queues: QueueBook? = null,
    val plays: List<LoggedPlay>? = null,
    /** Places and bookmarks, by song. */
    val bookmarks: List<BackupMarks>? = null,
    val equalizer: EqProfiles? = null,
    val lyrics: LedgerState? = null,
    val settings: Settings? = null,
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
 * The backup zip (spec "Backup and restore"): `manifest.json` (the format version, the KJB version and when it was made,
 * nothing about the phone, and each section with its version), `songs.json` (every song a section names, with its title,
 * artist, album and length, so another phone can find it), then one JSON file per [Section] in each store's own stored
 * form. Never in it: songs, `.lrc` files, models, caches, Covers & tags' Undo backups, MediaStore ids, Bluetooth
 * addresses, the automatic backup's folder.
 *
 * Read as untrusted input: only the known entry names are kept, no entry name ever becomes a path, each entry and all of
 * them together are capped unpacked ([ZipLimits]), two entries of one name make it unreadable, and every section is
 * decoded and checked before a restore writes anything.
 */
object BackupBundle {
    const val FORMAT: Int = 1
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
        val sections = buildList {
            snapshot.playlists?.let { add(Section.PLAYLISTS to PlaylistsJson.encode(bare(it))) }
            snapshot.queues?.let { add(Section.QUEUES to QueuesJson.encode(bare(it))) }
            snapshot.plays?.let { add(Section.PLAYS to plays(it)) }
            snapshot.songState?.let { add(Section.BOOKMARKS to marks(marksOf(it))) }
            snapshot.equalizer?.let { add(Section.EQUALIZER to EqProfilesJson.encode(it.copy(unknownProfiles = emptyMap()))) }
            snapshot.ledger?.let { add(Section.LYRICS to LedgerJson.encode(bare(it))) }
            snapshot.settings?.let { add(Section.SETTINGS to SettingsJson.encode(it.copy(backup = BackupSettings()))) }
        }
        val byPath = library.associateBy { it.path }
        val songs = named(snapshot).mapNotNull(byPath::get).map { BackupSong(it.path, it.title, it.artist, it.album, it.durationMs) }
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

    /** Every song path a section names, in order, each once; the lyrics entries carry their own tags. */
    private fun named(snapshot: BackupSnapshot): List<String> = buildList {
        snapshot.playlists?.let { state -> state.playlists.forEach { playlist -> playlist.songs.forEach { add(it.path) } }; state.favouriteSongs.forEach { add(it.path) } }
        snapshot.queues?.let { book -> bare(book).queues.forEach { queue -> queue.items.forEach { add(it.song.path) } } }
        snapshot.plays?.forEach { add(it.song.path) }
        snapshot.songState?.let { state -> marksOf(state).forEach { add(it.path) } }
    }.distinct()

    /** A song as a backup keeps it: by its path only, as a MediaStore id names a file on this phone alone. */
    private fun bare(ref: SongRef): SongRef = if (ref.mediaStoreId == null) ref else SongRef(ref.path)

    /** The playlists without ids, nor what only a newer version could read. */
    private fun bare(state: PlaylistsState): PlaylistsState = PlaylistsState(
        playlists = state.playlists.map { playlist -> playlist.copy(songs = playlist.songs.map(::bare)) },
        favouriteSongs = state.favouriteSongs.map(::bare),
        favouriteCollections = state.favouriteCollections,
    )

    /**
     * The saved and recent queues without ids: the queue playing first in Recent (unless it is saved), as it is the
     * newest; nothing plays from a backup.
     */
    private fun bare(book: QueueBook): QueueBook {
        val recent = (listOfNotNull(book.currentId) + book.recent).filter { it !in book.saved }.distinct().take(QueueBook.MAX_RECENT)
        val listed = recent.toSet() + book.saved
        return QueueBook(
            queues = book.queues.filter { it.id in listed }.map { queue -> queue.copy(items = queue.items.map { it.copy(song = bare(it.song)) }) },
            recent = recent,
            saved = book.saved,
            nextId = book.nextId,
            nextUid = book.nextUid,
        )
    }

    /**
     * Each song's place and bookmarks, read through the song state's own API (its places, then [SongState.bookmarksOf]):
     * songs with a place first, the oldest first, as it keeps them. The speeds and the part repeating stay on the phone.
     */
    private fun marksOf(state: SongState): List<BackupMarks> = (state.places.keys + state.bookmarks.keys).distinct().map { path ->
        BackupMarks(path, state.places[path], state.bookmarksOf(path).map { BackupBookmark(it.positionMs, it.note) })
    }

    /** The lyrics choices: no write cut short (its entry holds lyrics text), nor what only a newer version could read. */
    private fun bare(state: LedgerState): LedgerState = LedgerState(entries = state.entries.filterValues { it.status != LyricsStatus.WRITING })

    private fun manifest(appVersion: String, createdAt: Long, sections: List<Section>): String = JsonObject(
        mapOf(
            FORMAT_KEY to JsonPrimitive(FORMAT),
            APP_VERSION_KEY to JsonPrimitive(appVersion),
            CREATED_AT_KEY to JsonPrimitive(createdAt),
            SONGS_KEY to version(SONGS_VERSION),
            SECTIONS_KEY to JsonObject(sections.associate { it.key to version(it.version) }),
        ),
    ).toString()

    private fun version(version: Int): JsonObject = JsonObject(mapOf(VERSION_KEY to JsonPrimitive(version)))

    private fun songs(songs: List<BackupSong>): String = JsonObject(
        mapOf(
            SONGS_KEY to JsonArray(
                songs.map { song ->
                    JsonObject(
                        buildMap {
                            put(PATH_KEY, JsonPrimitive(song.path))
                            put(TITLE_KEY, JsonPrimitive(song.title))
                            song.artist?.let { put(ARTIST_KEY, JsonPrimitive(it)) }
                            song.album?.let { put(ALBUM_KEY, JsonPrimitive(it)) }
                            put(DURATION_KEY, JsonPrimitive(song.durationMs))
                        },
                    )
                },
            ),
        ),
    ).toString()

    private fun marks(marks: List<BackupMarks>): String = JsonObject(
        mapOf(
            SONGS_KEY to JsonArray(
                marks.map { song ->
                    JsonObject(
                        buildMap {
                            put(PATH_KEY, JsonPrimitive(song.path))
                            song.placeMs?.let { put(PLACE_KEY, JsonPrimitive(it)) }
                            put(BOOKMARKS_KEY, JsonArray(song.bookmarks.map { JsonObject(mapOf(POSITION_KEY to JsonPrimitive(it.positionMs), NOTE_KEY to JsonPrimitive(it.note))) }))
                        },
                    )
                },
            ),
        ),
    ).toString()

    private fun plays(plays: List<LoggedPlay>): String = JsonObject(
        mapOf(
            PLAYS_KEY to JsonArray(
                plays.map { play ->
                    JsonObject(
                        mapOf(
                            PATH_KEY to JsonPrimitive(play.song.path),
                            STARTED_AT_KEY to JsonPrimitive(play.startedAt),
                            HEARD_KEY to JsonPrimitive(play.heardMs),
                            COUNTED_KEY to JsonPrimitive(play.counted),
                        ),
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
        val songsVersion = (manifest[SONGS_KEY] as? JsonObject)?.int(VERSION_KEY)
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
        Section.QUEUES -> QueuesJson.decode(text)?.let { contents.copy(queues = it) }
        Section.PLAYS -> plays(text)?.let { contents.copy(plays = it) }
        Section.BOOKMARKS -> marks(text)?.let { contents.copy(bookmarks = it) }
        Section.EQUALIZER -> EqProfilesJson.decode(text)?.let { contents.copy(equalizer = it.copy(unknownProfiles = emptyMap())) }
        Section.LYRICS -> ledger(text)?.let { contents.copy(lyrics = bare(it)) }
        Section.SETTINGS -> SettingsJson.decode(text)?.let { contents.copy(settings = it.copy(backup = BackupSettings())) }
    }

    private fun ledger(text: String): LedgerState? = try {
        LedgerJson.decode(text)
    } catch (_: SerializationException) {
        null
    }

    /** The songs, each read on its own: one without a path, a title or a length is left out. */
    private fun songs(text: String): List<BackupSong>? = (jsonObject(text)?.get(SONGS_KEY) as? JsonArray)?.mapNotNull { element ->
        val song = element as? JsonObject ?: return@mapNotNull null
        BackupSong(
            path = song.text(PATH_KEY)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null,
            title = song.text(TITLE_KEY) ?: return@mapNotNull null,
            artist = song.text(ARTIST_KEY),
            album = song.text(ALBUM_KEY),
            durationMs = song.long(DURATION_KEY)?.takeIf { it >= 0 } ?: return@mapNotNull null,
        )
    }?.distinctBy { it.path }

    /**
     * The places and bookmarks, each read on its own: a song without a path, a place below 0 and a bookmark without a
     * spot are left out, and so is a song left with neither; a note may be missing.
     */
    private fun marks(text: String): List<BackupMarks>? = (jsonObject(text)?.get(SONGS_KEY) as? JsonArray)?.mapNotNull { element ->
        val song = element as? JsonObject ?: return@mapNotNull null
        val bookmarks = (song[BOOKMARKS_KEY] as? JsonArray).orEmpty().mapNotNull { stored ->
            val mark = stored as? JsonObject ?: return@mapNotNull null
            BackupBookmark(mark.long(POSITION_KEY)?.takeIf { it >= 0 } ?: return@mapNotNull null, mark.text(NOTE_KEY).orEmpty())
        }
        BackupMarks(song.text(PATH_KEY)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null, song.long(PLACE_KEY)?.takeIf { it >= 0 }, bookmarks)
            .takeIf { it.placeMs != null || it.bookmarks.isNotEmpty() }
    }?.distinctBy { it.path }

    /** The plays, each read on its own: one without a path or a start is left out. */
    private fun plays(text: String): List<LoggedPlay>? = (jsonObject(text)?.get(PLAYS_KEY) as? JsonArray)?.mapNotNull { element ->
        val play = element as? JsonObject ?: return@mapNotNull null
        LoggedPlay(
            song = SongRef(play.text(PATH_KEY)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null),
            startedAt = play.long(STARTED_AT_KEY)?.takeIf { it > 0 } ?: return@mapNotNull null,
            heardMs = play.long(HEARD_KEY)?.coerceAtLeast(0) ?: 0,
            counted = (play[COUNTED_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false,
        )
    }

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

    private fun jsonObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private const val BUFFER: Int = 64 * 1024
    private const val FORMAT_KEY: String = "format"
    private const val APP_VERSION_KEY: String = "appVersion"
    private const val CREATED_AT_KEY: String = "createdAt"
    private const val SONGS_KEY: String = "songs"
    private const val SECTIONS_KEY: String = "sections"
    private const val VERSION_KEY: String = "version"
    private const val PATH_KEY: String = "path"
    private const val TITLE_KEY: String = "title"
    private const val ARTIST_KEY: String = "artist"
    private const val ALBUM_KEY: String = "album"
    private const val DURATION_KEY: String = "durationMs"
    private const val PLAYS_KEY: String = "plays"
    private const val PLACE_KEY: String = "placeMs"
    private const val BOOKMARKS_KEY: String = "bookmarks"
    private const val POSITION_KEY: String = "positionMs"
    private const val NOTE_KEY: String = "note"
    private const val STARTED_AT_KEY: String = "startedAt"
    private const val HEARD_KEY: String = "heardMs"
    private const val COUNTED_KEY: String = "counted"

    /** The only entries read into memory. */
    private val KEPT: Set<String> = setOf(MANIFEST, SONGS) + Section.entries.map { it.file }
}
