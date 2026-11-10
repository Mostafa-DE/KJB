package app.lyrify.tags

import android.os.ParcelFileDescriptor
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** TagLib's name for the front cover's picture type (ID3v2 APIC type 3, and FLAC's and Vorbis's picture type 3). */
const val FRONT_COVER: String = "Front Cover"

/** A picture in a song's tag; [type] is TagLib's name for it ("Front Cover", "Back Cover"), empty in M4A. */
data class TagPicture(val data: ByteArray, val description: String, val type: String, val mime: String) {
    /** SHA-256 (hex) of [data]. */
    val sha256: String get() = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        other is TagPicture && data.contentEquals(other.data) && description == other.description && type == other.type && mime == other.mime

    override fun hashCode(): Int = ((data.contentHashCode() * 31 + description.hashCode()) * 31 + type.hashCode()) * 31 + mime.hashCode()

    /** Never the bytes, never the description: a log or a test failure shows the size and type only. */
    override fun toString(): String = "TagPicture(${data.size} bytes, $type, $mime)"
}

/** What TagLib reads from a song: every field by TagLib's property name, every picture, and the audio length. */
data class SongTags(
    val fields: Map<String, List<String>>,
    val pictures: List<TagPicture>,
    val durationMs: Long,
    /** An MP3's ID3v2 major version (3 or 4); 0 for other formats or no ID3v2 tag. */
    val id3v2Version: Int = 0,
    /** An MP3's ID3v2 frame IDs, in order, frames the property map can't show included; empty for other formats. */
    val frames: List<String> = emptyList(),
)

/** The ID3v2 version an MP3's tag is written as. 2.3 until the phone check shows Samsung Music reads 2.4 as well. */
enum class Id3Version(val number: Int) {
    V2_3(3),
    V2_4(4),
}

/**
 * The seam to TagLib: reads and writes the whole tag of a song file. Only ever given KJB's app-private copies of a
 * song, never the user's file (the real one refuses any other). Blocking.
 */
interface TagFile {
    /** The tags of [file], or null when TagLib cannot read it as a song. Throws when the file can't be opened. */
    fun read(file: File): SongTags?

    /**
     * Writes [fields] and [pictures] into [file] as its whole tag: a field or picture left out is removed, and null
     * pictures are left as they are. An MP3 is written as [id3], adding no ID3v1 tag. False when TagLib could not.
     * Throws when the file can't be opened.
     */
    fun write(file: File, fields: Map<String, List<String>>, pictures: List<TagPicture>?, id3: Id3Version): Boolean
}

/**
 * [TagFile] through the vendored TagLib (`:taglib`), on files under [privateRoot] only. Each call opens its own
 * descriptor and hands TagLib a duplicate it closes itself, as the wrapper's own tests do.
 */
class TagLibFile(private val privateRoot: File) : TagFile {
    override fun read(file: File): SongTags? {
        if (!private(file)) return null
        return try {
            val metadata = open(file, ParcelFileDescriptor.MODE_READ_ONLY) { TagLib.getMetadata(it, readPictures = true) } ?: return null
            val audio = open(file, ParcelFileDescriptor.MODE_READ_ONLY) { TagLib.getAudioProperties(it, AudioPropertiesReadStyle.Average) } ?: return null
            // An MP3's ID3v2 version and frame IDs: the first entry is the version.
            val id3 = if (file.extension.equals("mp3", ignoreCase = true)) open(file, ParcelFileDescriptor.MODE_READ_ONLY) { TagLib.getId3v2Frames(it) } else null
            SongTags(
                fields = metadata.propertyMap.mapValues { (_, values) -> values.toList() },
                pictures = metadata.pictures.map { TagPicture(it.data, it.description, it.pictureType, it.mimeType) },
                durationMs = audio.length.toLong(),
                id3v2Version = id3?.firstOrNull()?.toIntOrNull() ?: 0,
                frames = id3?.drop(1).orEmpty(),
            )
        } catch (_: RuntimeException) {
            // A Java exception TagLib's JNI left pending (out of memory for a picture, a bad string).
            null
        }
    }

    override fun write(file: File, fields: Map<String, List<String>>, pictures: List<TagPicture>?, id3: Id3Version): Boolean {
        if (!private(file)) return false
        val map = HashMap<String, Array<String>>().apply { fields.forEach { (key, values) -> put(key, values.toTypedArray()) } }
        val set = pictures?.map { Picture(it.data, it.description, it.type, it.mime) }?.toTypedArray()
        return try {
            open(file, ParcelFileDescriptor.MODE_READ_WRITE) { TagLib.saveTags(it, map, set, id3.number) }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun private(file: File): Boolean = file.canonicalFile.startsWith(privateRoot.canonicalFile)

    /** Runs [call] with a descriptor of [file] that TagLib owns and closes. */
    private fun <T> open(file: File, mode: Int, call: (Int) -> T): T =
        ParcelFileDescriptor.open(file, mode).use { pfd -> call(pfd.dup().detachFd()) }
}

/** What a change does to the front cover. */
sealed interface CoverEdit {
    data object Keep : CoverEdit

    /** The front cover becomes [picture] (whose type is [FRONT_COVER]); every other picture stays. */
    data class Replace(val picture: TagPicture) : CoverEdit

    /** The front cover goes; every other picture stays. */
    data object Remove : CoverEdit
}

/** A change to a song's tags: what Covers & tags, Edit tags and Undo ask the writer to make. */
data class TagEdit(
    /** Fields to write, by TagLib property name (any case): their values in order; an empty list removes the field. */
    val fields: Map<String, List<String>> = emptyMap(),
    /** Fields of [fields] written only where the song has none: the year, while "Keep my year" is on. */
    val onlyWhereEmpty: Set<String> = emptySet(),
    val cover: CoverEdit = CoverEdit.Keep,
)

/** A [TagEdit] applied to a song's tags: the whole tag to write, and what it changes. */
data class AppliedEdit(
    val fields: Map<String, List<String>>,
    val pictures: List<TagPicture>,
    val changes: List<FieldChange>,
    val coverChanged: Boolean,
    val frontBefore: TagPicture?,
    val frontAfter: TagPicture?,
) {
    val changesSomething: Boolean get() = changes.isNotEmpty() || coverChanged
}

/**
 * This edit applied to [tags], the song's own (read from its private copy): every field and picture it does not name
 * stays as it is, so the writer can write the whole tag back. Pure.
 */
fun TagEdit.applyTo(tags: SongTags): AppliedEdit {
    val whereEmpty = onlyWhereEmpty.map(String::uppercase).toSet()
    val fields = tags.fields.toMutableMap()
    val changes = mutableListOf<FieldChange>()
    for ((name, values) in this.fields) {
        val key = name.uppercase()
        val old = fields[key].orEmpty()
        if (key in whereEmpty && old.isNotEmpty()) continue
        if (old == values) continue
        changes += FieldChange(key, old, values)
        if (values.isEmpty()) fields.remove(key) else fields[key] = values
    }

    val front = frontIndex(tags.pictures)
    val before = front?.let(tags.pictures::get)
    val pictures = when (val cover = cover) {
        CoverEdit.Keep -> tags.pictures
        is CoverEdit.Replace -> {
            val rest = tags.pictures.filterIndexed { index, picture -> index != front && picture.type != FRONT_COVER }
            rest.toMutableList().apply { add((front ?: 0).coerceAtMost(rest.size), cover.picture) }
        }
        CoverEdit.Remove -> tags.pictures.filterIndexed { index, picture -> index != front && picture.type != FRONT_COVER }
    }
    val after = frontIndex(pictures)?.let(pictures::get).takeIf { cover !is CoverEdit.Remove }
    return AppliedEdit(fields, pictures, changes, pictures != tags.pictures, before, after)
}

/** Where the front cover is: the first picture typed so, or the first picture when none has a type (M4A). */
private fun frontIndex(pictures: List<TagPicture>): Int? {
    val typed = pictures.indexOfFirst { it.type == FRONT_COVER }
    return when {
        typed >= 0 -> typed
        pictures.isNotEmpty() && pictures.all { it.type.isEmpty() } -> 0
        else -> null
    }
}
