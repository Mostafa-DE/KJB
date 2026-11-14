package app.lyrify.downloads

import app.lyrify.data.SharedWrites
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.isWebLink
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.Confidence
import app.lyrify.dupes.DuplicateGrouping
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Links in text shared to KJB ("Save to KJB"): every http or https link, in order, each once, without the brackets,
 * quotes and punctuation around it. Pure.
 */
object ShareLinks {
    private val LINK = Regex("""(?i)https?://[^\s<>"'`]+""")

    /** What may close a sentence or a bracket around a link, never its last character. */
    private const val TRAILING = ".,;:!?)]}…"

    fun parse(text: String?): List<String> =
        LINK.findAll(text.orEmpty()).map { it.value.trimEnd { char -> char in TRAILING } }.filter(::isWebLink).distinct().toList()
}

/** Why a download did not save, as the list and the notification say it. */
enum class DownloadFailure {
    OFFLINE,
    NOT_FOUND,
    SITE_CHANGED,
    NO_ROOM,

    /** The file did not reach KJB whole. */
    HAND_OFF,

    /** The add-on said nothing for longer than any step takes ([DownloadRunner.STALL_MS]). */
    STALLED,

    /** The add-on stopped (its process died). */
    ADDON_GONE,

    /** The add-on is not there, not KJB's, or too old. */
    NO_ADDON,

    /** KJB may not save there: no All files access, or the folder is outside what this build may change. */
    NOT_ALLOWED,
    UNKNOWN;

    companion object {
        fun of(failure: DlFailure): DownloadFailure = when (failure) {
            DlFailure.OFFLINE -> OFFLINE
            DlFailure.NOT_FOUND -> NOT_FOUND
            DlFailure.SITE_CHANGED -> SITE_CHANGED
            DlFailure.NO_ROOM -> NO_ROOM
            DlFailure.HAND_OFF -> HAND_OFF
            DlFailure.ENGINE -> ADDON_GONE
            DlFailure.REFUSED, DlFailure.UNKNOWN -> UNKNOWN
        }
    }
}

/** Where a download is. Its progress while it runs is kept in memory only ([DownloadsUi]). */
@Serializable
sealed interface DownloadState {
    @Serializable
    @SerialName("queued")
    data object Queued : DownloadState

    /** Read back as [Queued]: KJB stopped (or its process died) while it ran, so it starts again from its parts. */
    @Serializable
    @SerialName("running")
    data object Running : DownloadState

    @Serializable
    @SerialName("failed")
    data class Failed(val reason: DownloadFailure) : DownloadState

    /** Saved at [path]; [copyOf] is a song KJB's Duplicates rules take for the same one. */
    @Serializable
    @SerialName("done")
    data class Done(val path: String, val savedAt: Long, val copyOf: String? = null) : DownloadState
}

/**
 * One download: what was asked ([url], audio at [audio] quality or video up to [height], the original language), what
 * the sheet showed of it, and where it stands. [key] is its id here and in the add-on, the same for every try.
 */
@Serializable
data class DownloadItem(
    val key: String,
    val url: String,
    val kind: DlKind,
    val audio: AudioQuality,
    val height: Int?,
    val originalLanguage: Boolean,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val durationSec: Int,
    val addedAt: Long,
    val state: DownloadState,
) {
    val active: Boolean get() = state == DownloadState.Queued || state == DownloadState.Running
}

/** Settings > Downloads (VidGrab's settings that still mean something in KJB). */
@Serializable
data class DownloadSettings(
    /** "Audio quality": VidGrab's default, MP3 at 320 kbps. */
    val audioQuality: AudioQuality = AudioQuality.MP3_320,
    /** "Original audio": never a dubbed track. */
    val originalLanguage: Boolean = true,
    /** "Audio folder", in the phone's storage. */
    val audioFolder: String = DownloadFolders.AUDIO,
    /** "Video folder". */
    val videoFolder: String = DownloadFolders.VIDEO,
    /** "Warn about copies": the sheet says so when KJB's Duplicates rules find the song on the phone. */
    val warnDuplicates: Boolean = true,
    /** "Check clipboard": Downloads offers a link copied there. */
    val checkClipboard: Boolean = true,
)

/** What `downloads.json` holds: the settings and the downloads, newest first. */
data class DownloadsMemory(val settings: DownloadSettings = DownloadSettings(), val items: List<DownloadItem> = emptyList()) {
    /** Every download under way, and the newest [HISTORY] of the rest (VidGrab kept 200 in its Finished list). */
    fun capped(): DownloadsMemory {
        val kept = items.filterNot { it.active }.sortedByDescending { it.addedAt }.take(HISTORY).toSet()
        return copy(items = items.filter { it.active || it in kept })
    }

    companion object {
        const val HISTORY: Int = 200
    }
}

/** Where downloads go, and what a saved file is called. */
object DownloadFolders {
    const val AUDIO: String = "Music/KJB Downloads"
    const val VIDEO: String = "Movies/KJB Downloads"

    /** The top folders each kind may go in: where the phone's apps look for music, and for videos. */
    private val AUDIO_TOPS = setOf("Music", "Download", "Podcasts", "Audiobooks")
    private val VIDEO_TOPS = setOf("Movies", "Download", "DCIM")
    private val NOT_IN_NAMES = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private const val MAX_DEPTH = 4
    private const val MAX_NAME = 64
    private const val MAX_FILE_NAME = 120
    private const val FALLBACK = "download"

    /** A folder ("Music/KJB Downloads") for [kind]: a top folder it may use, then plain names, at most 4 deep. */
    fun valid(folder: String, kind: DlKind): Boolean {
        val names = folder.split('/')
        val tops = if (kind == DlKind.AUDIO) AUDIO_TOPS else VIDEO_TOPS
        return names.size in 2..MAX_DEPTH && names.first() in tops &&
            names.all { it.isNotBlank() && it == it.trim() && it.length <= MAX_NAME && it != "." && it != ".." && !NOT_IN_NAMES.containsMatchIn(it) }
    }

    /**
     * The folder [settings] give [kind], as an absolute path on the volume at [root]. A build that changes only its test
     * folder ([SharedWrites.TEST_FOLDER]) keeps the same folder inside it.
     */
    fun absolute(settings: DownloadSettings, kind: DlKind, root: String, testFolderOnly: Boolean): String {
        val folder = if (kind == DlKind.AUDIO) settings.audioFolder else settings.videoFolder
        return if (testFolderOnly) "$root/${SharedWrites.TEST_FOLDER}$folder" else "$root/$folder"
    }

    /** [name] (yt-dlp's, its extension [ext]) as a file name: no path or reserved signs, no leading dot, not too long. */
    fun safeName(name: String, ext: String): String {
        val base = name.removeSuffix(".$ext").replace(NOT_IN_NAMES, "_").replace(Regex("\\s+"), " ").trim { it == ' ' || it == '.' }.take(MAX_FILE_NAME).trim()
        return "${base.ifEmpty { FALLBACK }}.$ext"
    }

    /** [name] in [folder], or "Name (2).ext" and so on when a file already has it. */
    fun free(folder: File, name: String): File {
        val first = File(folder, name)
        if (!first.exists()) return first
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        return generateSequence(2) { it + 1 }.map { File(folder, if (ext.isEmpty()) "$base ($it)" else "$base ($it).$ext") }.first { !it.exists() }
    }

    /** The MIME type of a saved file by its extension, for the scan. */
    fun mime(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }
}

/** The tags KJB's tag engine writes into a downloaded song: yt-dlp's (VidGrab's rules), else what the sheet showed. Pure. */
object DownloadTags {
    private val YEAR = Regex("""\d{4}""")

    fun fields(item: DownloadItem, file: DlFile): Map<String, List<String>> {
        val meta = file.meta
        return linkedMapOf(
            "TITLE" to (meta.title ?: item.title.takeIf { it.isNotBlank() }),
            "ARTIST" to (meta.artist ?: item.artist.takeIf { it.isNotBlank() }),
            "ALBUM" to meta.album,
            "ALBUMARTIST" to meta.albumArtist,
            "DATE" to meta.year?.takeIf(YEAR::matches),
            "GENRE" to meta.genre,
        ).mapNotNull { (key, value) -> value?.trim()?.takeIf { it.isNotEmpty() }?.let { key to listOf(it) } }.toMap()
    }
}

/** Whether a song is already on the phone, by KJB's Duplicates rules (DuplicateGrouping): same song, or maybe. Pure. */
object DuplicateCheck {
    /** What the sheet knows of a link before it downloads. */
    data class Candidate(val title: String, val artist: String?, val durationMs: Long)

    /** A song in [library] that [candidate] would be a copy of; null when none, or with no length to compare. */
    fun copyOf(candidate: Candidate, library: List<AudioFile>): AudioFile? {
        if (candidate.durationMs <= 0) return null
        val asked = AudioFile(ASKED, "", null, candidate.title, candidate.title, candidate.artist, null, candidate.durationMs, 0)
        return copyOf(asked, library + asked)
    }

    /** The other song in [file]'s group among [library] (where [file] is too, once saved and scanned); null when none. */
    fun copyOf(file: AudioFile, library: List<AudioFile>): AudioFile? {
        val all = if (library.any { it.id == file.id }) library else library + file
        val group = DuplicateGrouping.groups(all) { false }
            .firstOrNull { group -> group.confidence != Confidence.IDENTICAL && group.files.any { it.id == file.id } }
        return group?.files?.firstOrNull { it.id != file.id }
    }

    /** No MediaStore id is negative. */
    private const val ASKED: Long = -1
}

/**
 * The stored form of [DownloadsMemory]: a JSON object with a version, the settings, and the items. Read leniently: an
 * item that cannot be read is dropped, a setting that cannot be read (or a folder that is not [DownloadFolders.valid])
 * reads as its default, and a download that was running reads as queued.
 */
object DownloadsJson {
    const val VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(memory: DownloadsMemory): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "version" to JsonPrimitive(VERSION),
                "settings" to json.encodeToJsonElement(DownloadSettings.serializer(), memory.settings),
                "items" to JsonArray(memory.items.map { json.encodeToJsonElement(DownloadItem.serializer(), it) }),
            ),
        ),
    )

    fun encodeItem(item: DownloadItem): String = json.encodeToString(DownloadItem.serializer(), item)

    fun decode(text: String): DownloadsMemory {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return DownloadsMemory()
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            try {
                json.decodeFromJsonElement(DownloadItem.serializer(), element)
            } catch (_: IllegalArgumentException) {
                // A SerializationException is one.
                null
            }
        }.map { if (it.state == DownloadState.Running) it.copy(state = DownloadState.Queued) else it }
        return DownloadsMemory(settings((root["settings"] as? JsonObject) ?: JsonObject(emptyMap())), items)
    }

    private fun settings(stored: JsonObject): DownloadSettings {
        val d = DownloadSettings()
        fun text(key: String) = (stored[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(key: String, default: Boolean) = (stored[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: default
        return DownloadSettings(
            audioQuality = AudioQuality.entries.firstOrNull { it.name == text("audioQuality") } ?: d.audioQuality,
            originalLanguage = flag("originalLanguage", d.originalLanguage),
            audioFolder = text("audioFolder")?.takeIf { DownloadFolders.valid(it, DlKind.AUDIO) } ?: d.audioFolder,
            videoFolder = text("videoFolder")?.takeIf { DownloadFolders.valid(it, DlKind.VIDEO) } ?: d.videoFolder,
            warnDuplicates = flag("warnDuplicates", d.warnDuplicates),
            checkClipboard = flag("checkClipboard", d.checkClipboard),
        )
    }
}

/**
 * [DownloadsMemory] in one app-private file, written whole or not at all (written aside, then moved over). One that
 * cannot be read starts over, logged by its size only. Blocking.
 */
class DownloadsStore(private val file: File, private val log: (String) -> Unit = {}) {
    fun load(): DownloadsMemory {
        if (!file.isFile) return DownloadsMemory()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("downloads: not readable (${e.javaClass.simpleName}), starting over")
            return DownloadsMemory()
        }
        return DownloadsJson.decode(text)
    }

    /** False when it could not be written; the file before stays then. */
    fun save(memory: DownloadsMemory): Boolean {
        val aside = File(file.path + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            aside.writeText(DownloadsJson.encode(memory.capped()))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("downloads: not written (${e.javaClass.simpleName})")
            false
        }
    }
}
