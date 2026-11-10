package app.lyrify.tags

import app.lyrify.library.Album
import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.library.Artist
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.FavouritesPort
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.albumKey
import app.lyrify.lyrics.TrackNames
import app.lyrify.tags.lookup.Body
import app.lyrify.tags.lookup.CoverPipeline
import app.lyrify.tags.lookup.ImageCodec
import app.lyrify.tags.lookup.Images
import app.lyrify.tags.lookup.OpusCover
import app.lyrify.tags.lookup.PhoneAlbum
import app.lyrify.tags.lookup.PhoneSong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.util.Base64

// Edit tags (manual), milestone T3: what Edit tags, Edit album, Rename artist and Merge plan to write, the favourites
// that follow a renamed album or artist, the names that may be the same, and what a write or an Undo says. Pure, apart
// from writeEdit and undoEdit and the kept-apart pairs' file; the writer (SafeWriter) does every write, one song at a
// time, and keeps History.

/** The fields Edit tags shows, in its order, with the TagLib property each one is written as. */
enum class TagField(val key: String) {
    TITLE("TITLE"),
    ARTIST("ARTIST"),
    ALBUM("ALBUM"),
    ALBUM_ARTIST("ALBUMARTIST"),
    YEAR("DATE"),
    TRACK("TRACKNUMBER"),
    DISC("DISCNUMBER"),
    GENRE("GENRE"),
    ;

    /** [song]'s value as the library reads it; empty when it has none. */
    fun of(song: Song): String = when (this) {
        TITLE -> song.title
        ARTIST -> song.artist.orEmpty()
        ALBUM -> song.album.orEmpty()
        ALBUM_ARTIST -> song.albumArtist.orEmpty()
        YEAR -> song.year?.toString().orEmpty()
        TRACK -> song.track?.toString().orEmpty()
        DISC -> song.disc?.toString().orEmpty()
        GENRE -> song.genre.orEmpty()
    }

    val isNumber: Boolean get() = this == TRACK || this == DISC

    /** [typed] as it is written and compared: trimmed, a track or disc number without leading zeros. */
    fun clean(typed: String): String {
        val text = typed.trim()
        return if (isNumber) text.toIntOrNull()?.toString() ?: text else text
    }
}

/** Why a field can't be saved as typed. */
enum class FieldProblem { NEEDS_TEXT, FOUR_DIGITS, NUMBER }

/**
 * Edit tags' fields for one song or several: [values] as typed, [start] as they began ([of]). A field the songs do not
 * share starts empty and [mixed] ("Keep as is"). Kept across a turn of the phone as plain text.
 */
@Serializable
data class TagForm(val values: Map<TagField, String>, val start: Map<TagField, String>, val mixed: Set<TagField>) {
    fun value(field: TagField): String = values[field].orEmpty()

    fun with(field: TagField, value: String): TagForm = copy(values = values + (field to value))

    /** Something was typed that changes a field: closing asks first. */
    val edited: Boolean get() = values.any { (field, typed) -> changed(field, typed) }

    /** [typed] differs from where [field] began, as written. */
    fun changed(field: TagField, typed: String = value(field)): Boolean = field.clean(typed) != field.clean(start[field].orEmpty())

    /**
     * What stops Save: an empty field of [needed] (the title of one song, the album's name), a year that is not four
     * digits, a track or disc that is not a number of 1 to 3 digits. An empty year, track or disc is fine: it removes it.
     */
    fun problems(needed: Set<TagField>): Map<TagField, FieldProblem> = values.mapNotNull { (field, typed) ->
        val text = typed.trim()
        val problem = when {
            text.isEmpty() -> FieldProblem.NEEDS_TEXT.takeIf { field in needed }
            field == TagField.YEAR -> FieldProblem.FOUR_DIGITS.takeUnless { YEAR.matches(text) }
            field.isNumber -> FieldProblem.NUMBER.takeUnless { NUMBER.matches(text) }
            else -> null
        }
        problem?.let { field to it }
    }.toMap()

    companion object {
        private val YEAR = Regex("\\d{4}")
        private val NUMBER = Regex("\\d{1,3}")

        /** The form for [songs], showing [fields]: each one's shared value, or empty and mixed where they differ. */
        fun of(songs: List<Song>, fields: List<TagField> = TagField.entries): TagForm {
            val shared = fields.associateWith { field -> songs.map(field::of).distinct().singleOrNull() }
            val start = shared.mapValues { (_, value) -> value.orEmpty() }
            return TagForm(start, start, shared.filterValues { it == null }.keys)
        }
    }
}

/** One song's planned change: the fields it gets (an empty value removes the field), and its cover. */
data class SongPlan(val song: Song, val fields: Map<TagField, String>, val cover: CoverEdit = CoverEdit.Keep) {
    /** The writer's job; null for a song MediaStore gave no id, which the writer can't reach. */
    val job: WriteJob?
        get() = song.ref.mediaStoreId?.let { id ->
            val written = fields.entries.associate { (field, value) -> field.key to listOfNotNull(value.takeIf(String::isNotEmpty)) }
            WriteJob(SongTarget(song.path, id), TagEdit(fields = written, cover = cover))
        }

    val changesSomething: Boolean get() = fields.isNotEmpty() || cover != CoverEdit.Keep

    /** Each field this changes, old and new, in the fields' order: what a merge's preview lists. */
    val changes: List<PreviewLine> get() = fields.map { (field, value) -> PreviewLine(field, field.of(song), value, song) }

    /** The song as the library will read it once this is written: where its album or artist page moves to. */
    val after: Song
        get() = fields.entries.fold(song) { at, (field, value) ->
            val text = value.takeIf(String::isNotEmpty)
            when (field) {
                TagField.TITLE -> at.copy(title = value)
                TagField.ARTIST -> at.copy(artist = text)
                TagField.ALBUM -> at.copy(album = text)
                TagField.ALBUM_ARTIST -> at.copy(albumArtist = text)
                TagField.YEAR -> at.copy(year = text?.toIntOrNull())
                TagField.TRACK -> at.copy(track = text?.toIntOrNull())
                TagField.DISC -> at.copy(disc = text?.toIntOrNull())
                TagField.GENRE -> at.copy(genre = text)
            }
        }
}

/** A rename's songs: the artist's [own], and those [credited] with it among others ([TagEdits.credits]). */
data class CreditSplit(val own: List<SongPlan>, val credited: List<SongPlan>)

/** One line of what an edit changes: [field] from [old] to [new] (empty: none); [song] when it is that song's own line. */
data class PreviewLine(val field: TagField, val old: String, val new: String, val song: Song? = null)

/** The plans of Edit tags, Edit album, Rename artist and Merge (player-spec "Edit tags (manual)"). */
object TagEdits {
    /**
     * What an editor shows before Save: each field the [plans] change, once, as its first song has it; then the
     * [perSong] fields song by song, at most [perSongMax] lines (Number in this order's tracks).
     */
    fun preview(plans: List<SongPlan>, perSong: Set<TagField> = emptySet(), perSongMax: Int = 3): List<PreviewLine> {
        val shared = TagField.entries.filter { it !in perSong }.mapNotNull { field ->
            plans.firstOrNull { field in it.fields }?.let { PreviewLine(field, field.of(it.song), it.fields.getValue(field)) }
        }
        return shared + plans.flatMap { plan -> plan.changes.filter { it.field in perSong } }.take(perSongMax)
    }

    /** The fields Edit album edits for every song of the album at once. */
    val ALBUM_FIELDS: List<TagField> = listOf(TagField.ALBUM, TagField.ALBUM_ARTIST, TagField.YEAR, TagField.GENRE)

    /**
     * Edit tags for [songs]: each field that differs from where the form began goes to every song that differs; an
     * emptied one is removed. A field the songs do not share starts empty ("Keep as is"), so left empty it keeps each
     * song's own. [opus]: the comment header of each Opus song ([OpusHeader]), for its cover.
     */
    fun songs(songs: List<Song>, form: TagForm, cover: CoverEdit, opus: Map<String, Int> = emptyMap()): List<SongPlan> {
        val typed = form.values.filter { (field, value) -> form.changed(field, value) }.mapValues { (field, value) -> field.clean(value) }
        return plans(songs, cover, opus) { song, _ -> typed.filter { (field, value) -> field.of(song) != value } }
    }

    /**
     * Edit album for [songs] in the album page's order: each shared field changed (an emptied one removed, a mixed one
     * left empty kept) in every song that differs; with [numberInOrder], the tracks numbered 1, 2, 3 in that order on
     * disc 1 (a song without a disc number keeps none).
     */
    fun album(songs: List<Song>, form: TagForm, cover: CoverEdit, numberInOrder: Boolean, opus: Map<String, Int> = emptyMap()): List<SongPlan> {
        val shared = ALBUM_FIELDS.filter { form.changed(it) }.associateWith { form.value(it).trim() }
            .filterNot { (field, value) -> value.isEmpty() && field in form.mixed }
        return plans(songs, cover, opus) { song, at ->
            val fields = shared.filter { (field, value) -> field.of(song) != value }.toMutableMap()
            if (numberInOrder) {
                if (song.track != at + 1) fields[TagField.TRACK] = (at + 1).toString()
                if (song.disc != null && song.disc != 1) fields[TagField.DISC] = "1"
            }
            fields
        }
    }

    /**
     * Rename artist ([names] one name) and Merge artists ([names] both): each of [names] becomes [to] in the artist and
     * album artist of [songs] where it is the whole value or one whole credited name ("Kit Calloway feat. Mira Okafor"),
     * never a word inside another name ([replaceNames]). Case counts: a spelling in other letters is another artist,
     * for Merge.
     */
    fun rename(songs: List<Song>, names: Collection<String>, to: String): List<SongPlan> {
        val name = to.trim()
        if (name.isEmpty()) return emptyList()
        return plans(songs, CoverEdit.Keep, emptyMap()) { song, _ ->
            listOfNotNull(
                song.artist?.let { TagField.ARTIST to replaceNames(it, names, name) }?.takeIf { (_, new) -> new != song.artist },
                song.albumArtist?.let { TagField.ALBUM_ARTIST to replaceNames(it, names, name) }?.takeIf { (_, new) -> new != song.albumArtist },
            ).toMap()
        }
    }

    /**
     * A rename's or a merge's [plans] split for its preview: the songs whose whole artist or album artist is one of
     * [names] (the artist's page and albums), then those that only credit one among others ("Also credited on").
     */
    fun credits(plans: List<SongPlan>, names: Collection<String>): CreditSplit {
        val whole = names.map(String::trim).toSet()
        val (own, credited) = plans.partition { it.song.artist?.trim() in whole || it.song.albumArtist?.trim() in whole }
        return CreditSplit(own, credited)
    }

    /** Merge albums: every song of [albums] whose album is not [keep] takes it. */
    fun mergeAlbums(albums: List<Album>, keep: String): List<SongPlan> {
        val name = keep.trim()
        if (name.isEmpty()) return emptyList()
        return plans(albums.flatMap { it.songs }, CoverEdit.Keep, emptyMap()) { song, _ ->
            if (song.album?.trim() == name) emptyMap() else mapOf(TagField.ALBUM to name)
        }
    }

    /**
     * [text], a credit, with each part that is exactly one of [names] replaced by [to], in one pass so [to] is never
     * replaced again: the whole value, or a name between the joins a credit uses ("feat.", "ft.", "featuring", "(with
     * ...)", ";"). A word inside another name ("Glass" in "Glass Harbour") is never one, nor is a name beside "&" or ","
     * ("Rowan & Fennick", "Rowan, the Lantern"): those are mostly one band's name. Blank names and [to] itself are left
     * out.
     */
    fun replaceNames(text: String, names: Collection<String>, to: String): String {
        val replaced = names.filter { it.isNotBlank() && it != to }.toSet()
        if (replaced.isEmpty()) return text
        val out = StringBuilder()
        var at = 0
        fun part(end: Int) {
            val raw = text.substring(at, end)
            val name = raw.trim()
            if (name !in replaced) {
                out.append(raw)
            } else {
                val start = raw.indexOf(name)
                out.append(raw, 0, start).append(to).append(raw, start + name.length, raw.length)
            }
        }
        for (join in CREDIT_JOIN.findAll(text)) {
            part(join.range.first)
            out.append(join.value)
            at = join.range.last + 1
        }
        part(text.length)
        return out.toString()
    }

    /** What joins the names of a credit: TrackNames' "feat." forms, and ";". "&", ",", "and", "x" and "/" stay in names. */
    private val CREDIT_JOIN = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+|\s*[)\]]\s*|\s+(?:feat\.?|ft\.?|featuring)\s+|\s*;\s*""", RegexOption.IGNORE_CASE)

    /**
     * The cover change [song] gets: an Opus song ([opusOther]: the rest of its comment header, from [OpusHeader]) keeps
     * its cover when the new one would take that header past what readers read of it (covers-tags-spec "What is
     * written"). Null [opusOther]: not Opus.
     */
    fun coverFor(song: Song, cover: CoverEdit, opusOther: Int? = null): CoverEdit = when {
        cover is CoverEdit.Replace && opusOther != null && !OpusCover.fits(cover.picture.data.size, opusOther) -> CoverEdit.Keep
        else -> cover
    }

    /** The album Find online looks up: the songs being edited, as Covers & tags groups an album. */
    fun phoneAlbum(songs: List<Song>): PhoneAlbum = PhoneAlbum.of(
        songs.map { PhoneSong(it.path, it.title, it.artist, it.album, it.albumArtist, it.disc, it.track, it.durationMs, it.year) },
    )

    /** Each song's plan from [fields] (given the song and its place), with the cover; songs with nothing to change left out. */
    private fun plans(songs: List<Song>, cover: CoverEdit, opus: Map<String, Int>, fields: (Song, Int) -> Map<TagField, String>): List<SongPlan> =
        songs.mapIndexed { at, song ->
            // In the fields' own order, as History lists them.
            val changed = fields(song, at).entries.sortedBy { it.key.ordinal }.associate { it.key to it.value }
            SongPlan(song, changed, coverFor(song, cover, opus[song.path]))
        }.filter { it.changesSomething }
}

/**
 * A photo from the phone's photo picker as a front cover: one too large for the cover pipeline, or in a format it does
 * not take (HEIC), is first made a JPEG of at most [CoverPipeline.MAX_SIDE] px, its quality and then its size stepped
 * down until the pipeline takes it; then the pipeline makes it what every cover is. Null when it is not an image.
 */
suspend fun pickedCover(image: ByteArray, codec: ImageCodec, pipeline: CoverPipeline = CoverPipeline(codec)): TagPicture? {
    val taken = if (image.size <= Body.IMAGE.maxBytes && Images.isImage(image)) image else takenJpeg(image, codec) ?: return null
    val cover = pipeline.prepare(taken) ?: return null
    return TagPicture(cover.jpeg, "", FRONT_COVER, "image/jpeg")
}

/** [image] as the first JPEG on the ladder of sides and qualities that is within the pipeline's cap. */
private fun takenJpeg(image: ByteArray, codec: ImageCodec): ByteArray? {
    for (side in CoverPipeline.SIDES) {
        for (quality in PICKED_QUALITIES) {
            val jpeg = codec.jpeg(image, side, quality) ?: return null
            if (jpeg.size <= Body.IMAGE.maxBytes) return jpeg
        }
    }
    return null
}

/** Tried in order at each side: the photo is re-encoded once more by the pipeline, so it starts at the best. */
private val PICKED_QUALITIES = listOf(100, 90, 80, 70)

/**
 * The comment header of an Ogg Opus song, which a cover goes into: known by its content (an "OpusHead" first packet),
 * whatever the file's name, as the payload hash knows it ([PayloadHash]).
 */
object OpusHeader {
    private val OGG = "OggS".encodeToByteArray()
    private val HEAD = "OpusHead".encodeToByteArray()
    private val TAGS = "OpusTags".encodeToByteArray()
    private const val PICTURE = "METADATA_BLOCK_PICTURE="
    private const val FRONT = 3

    /**
     * The bytes of [file]'s comment header besides its front cover: "OpusTags", the vendor, the count and every other
     * comment, what [OpusCover.fits] weighs a new cover against. Null when [file] is not Ogg Opus or can't be read.
     */
    fun otherBytes(file: File): Int? = try {
        FileInputStream(file).buffered().use(::comments)
    } catch (_: IOException) {
        null
    }

    /** Reads [input]'s pages up to the second packet: the first must be "OpusHead", the second "OpusTags". */
    private fun comments(input: InputStream): Int? {
        val packet = ByteArrayOutputStream()
        var index = 0
        while (true) {
            val page = input.upTo(PAGE_HEADER)
            if (page.size < PAGE_HEADER || !page.copyOf(OGG.size).contentEquals(OGG)) return null
            val lacing = input.upTo(page[PAGE_HEADER - 1].toInt() and BYTE)
            val body = input.upTo(lacing.sumOf { it.toInt() and BYTE })
            var offset = 0
            for (value in lacing) {
                val length = value.toInt() and BYTE
                packet.write(body, offset, minOf(length, body.size - offset).coerceAtLeast(0))
                offset += length
                if (length == SEGMENT) continue
                val done = packet.toByteArray()
                if (index == 1) return if (done.copyOf(TAGS.size).contentEquals(TAGS)) withoutFront(done) else null
                if (!done.copyOf(HEAD.size).contentEquals(HEAD)) return null
                index++
                packet.reset()
            }
        }
    }

    /** The next [count] bytes of this stream, fewer only at its end (InputStream.readNBytes needs API 33). */
    private fun InputStream.upTo(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val got = read(bytes, read, count - read)
            if (got < 0) return bytes.copyOf(read)
            read += got
        }
        return bytes
    }

    /** [packet]'s size less each front cover comment and its length; all of it when it can't be walked. */
    private fun withoutFront(packet: ByteArray): Int {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            buffer.position(TAGS.size)
            val vendor = buffer.int
            buffer.position(buffer.position() + vendor)
            var front = 0
            repeat(buffer.int) {
                val length = buffer.int
                require(length in 0..buffer.remaining())
                val comment = ByteArray(length).also(buffer::get).decodeToString()
                if (isFront(comment)) front += Int.SIZE_BYTES + length
            }
            packet.size - front
        } catch (_: RuntimeException) {
            // Past the end, or a length that does not fit: counted whole.
            packet.size
        }
    }

    /** A METADATA_BLOCK_PICTURE whose picture type (the block's first 4 bytes) is the front cover. */
    private fun isFront(comment: String): Boolean {
        if (!comment.startsWith(PICTURE, ignoreCase = true)) return false
        val head = try {
            Base64.getDecoder().decode(comment.substring(PICTURE.length).take(BASE64_HEAD))
        } catch (_: IllegalArgumentException) {
            return false
        }
        return head.size >= Int.SIZE_BYTES && ByteBuffer.wrap(head).int == FRONT
    }

    private const val PAGE_HEADER = 27
    private const val SEGMENT = 255
    private const val BYTE = 0xFF

    /** Base64 of the picture block's first 6 bytes: its type and more. */
    private const val BASE64_HEAD = 8
}

/** Whether a write or an Undo changed any song: only then does a page follow its album or artist to the new name. */
fun BatchResult.wroteAny(): Boolean = this is BatchResult.Done && report().written > 0

/** One edit in History: what it was, how many songs, and whether every one of them is undone. */
data class HistoryRow(val id: Long, val kind: EditKind, val label: String, val at: Long, val songs: Int, val undone: Boolean)

/** History's rows, the newest edit first. */
fun historyRows(memory: UndoMemory): List<HistoryRow> =
    memory.entries.sortedWith(compareByDescending<HistoryEntry> { it.at }.thenByDescending { it.id }).map { entry ->
        HistoryRow(entry.id, entry.kind, entry.label, entry.at, entry.songs.size, entry.songs.isNotEmpty() && entry.songs.all { it.undone })
    }

/**
 * How a write or an Undo went, for its message: songs [written] (an Undo: taken back), left alone while in use
 * ([busy]), that could not be changed ([failed]), whose tags or audio [changedSince] (an Undo), and the fields an Undo
 * kept because a later edit changed them ([skippedFields]).
 */
data class EditReport(val written: Int, val busy: Int, val failed: Int, val changedSince: Int, val skippedFields: Int)

fun BatchResult.Done.report(): EditReport {
    var written = 0
    var busy = 0
    var failed = 0
    var changedSince = 0
    // Every outcome has its count, so a new one can't pass for nothing changed.
    for (song in songs) {
        when (song.outcome) {
            // Written, its Undo saved by the start-up recovery.
            SongOutcome.WRITTEN, SongOutcome.UNDO_NOT_SAVED -> written++
            SongOutcome.BUSY -> busy++
            // A song replaced while it was written was set aside, its backup kept: not changed.
            SongOutcome.COULD_NOT_CHANGE, SongOutcome.LEFT_AS_IT_WAS, SongOutcome.RECOVERY_PENDING, SongOutcome.SET_ASIDE, SongOutcome.NOT_ALLOWED, SongOutcome.NOT_TRIED,
            SongOutcome.PAUSED,
            -> failed++
            SongOutcome.CHANGED_SINCE, SongOutcome.DIFFERENT_FILE -> changedSince++
            SongOutcome.UNCHANGED -> Unit
        }
    }
    return EditReport(written, busy, failed, changedSince, skippedFields)
}

// Favourites follow their albums and artists

/** A favourite album or artist, and where its heart goes once an edit moved all its songs ([FavouriteMoves]). */
data class FavouriteMove(val from: FavouriteCollection, val to: FavouriteCollection)

/**
 * A favourite album or artist keeps its heart when an edit gives it a new name or key: a rename, a merge, Edit album,
 * and their Undo. It moves only when every one of its songs was written and all now share one new key; an album or
 * artist an edit split (a song in use, a field changed since) keeps its heart where it is. Pure, apart from [apply].
 */
object FavouriteMoves {
    /** The hearts to move after [changed] songs were written: each as [library] read it before, and as it is now. */
    fun of(changed: List<Pair<Song, Song>>, library: Library, favourites: Collection<FavouriteCollection>): List<FavouriteMove> {
        val now = changed.associate { (before, after) -> before.path to after }
        return favourites.mapNotNull { favourite ->
            val songs = when (favourite.kind) {
                CollectionKind.ARTIST -> library.artist(favourite.key)?.songs
                CollectionKind.ALBUM -> library.album(favourite.key)?.songs
                CollectionKind.FOLDER, CollectionKind.PLAYLIST -> null
            }
            if (songs.isNullOrEmpty() || songs.any { it.path !in now }) return@mapNotNull null
            val keys = songs.map { keyOf(favourite.kind, now.getValue(it.path)) }.distinct()
            val key = keys.singleOrNull()?.takeIf { it != favourite.key } ?: return@mapNotNull null
            FavouriteMove(favourite, FavouriteCollection(favourite.kind, key))
        }
    }

    /**
     * What an Undo of [entry] changed back in the songs it [written], from [library] as it read them before the Undo:
     * each field the edit wrote goes back where the song still held the edit's value.
     */
    fun undone(entry: HistoryEntry, written: Set<String>, library: Library): List<Pair<Song, Song>> =
        entry.songs.filter { it.path in written }.mapNotNull { edit ->
            val song = library.song(edit.path) ?: return@mapNotNull null
            val back = edit.fields.mapNotNull { change ->
                val field = TagField.entries.firstOrNull { it.key == change.key } ?: return@mapNotNull null
                (field to change.old.firstOrNull().orEmpty()).takeIf { field.of(song) == change.new.firstOrNull().orEmpty() }
            }.toMap()
            song to SongPlan(song, back).after
        }

    /** Each heart on at its new key, then off at the old one (kept when the new one could not be stored). */
    suspend fun apply(moves: List<FavouriteMove>, favourites: FavouritesPort): Boolean =
        moves.fold(true) { stored, move -> favourites.setCollection(move.to, true) && favourites.setCollection(move.from, false) && stored }

    private fun keyOf(kind: CollectionKind, song: Song): String? = when (kind) {
        CollectionKind.ARTIST -> song.artist?.trim()?.takeIf(String::isNotEmpty)
        CollectionKind.ALBUM -> albumKey(song)
        CollectionKind.FOLDER, CollectionKind.PLAYLIST -> null
    }
}

/**
 * Writes [plans] as one History entry of [kind] shown as [label], through the one tag writer, then moves the hearts
 * of the favourite albums and artists the edit renamed ([FavouriteMoves]).
 */
suspend fun writeEdit(context: Context, plans: List<SongPlan>, kind: EditKind, label: String): BatchResult =
    writeEdit(context, plans.mapNotNull { it.job }, plans.map { it.song to it.after }, kind, label)

/**
 * Writes [jobs] as one History entry, as [writeEdit] does plans; [changed] is each song as the library reads it now and
 * as it will once written, for the hearts. Covers & tags writes through here, its fields being its own.
 */
suspend fun writeEdit(context: Context, jobs: List<WriteJob>, changed: List<Pair<Song, Song>>, kind: EditKind, label: String): BatchResult {
    val app = context.applicationContext
    val library = (LibraryGraph.repository(app).state.value as? LibraryState.Ready)?.library
    return writeEdit(withContext(Dispatchers.IO) { TagsGraph.writer(app) }, LibraryGraph.favourites(app), library, jobs, changed, kind, label)
}

/** [writeEdit] over its parts: [library] as it read the songs before the write. */
internal suspend fun writeEdit(
    writer: SafeWriter,
    favourites: FavouritesPort,
    library: Library?,
    jobs: List<WriteJob>,
    changed: List<Pair<Song, Song>>,
    kind: EditKind,
    label: String,
): BatchResult {
    val result = writer.write(jobs, kind, label)
    if (library != null && result is BatchResult.Done) {
        val written = result.writtenPaths()
        moveHearts(favourites) { FavouriteMoves.of(changed.filter { it.first.path in written }, library, it) }
    }
    return result
}

/**
 * Undoes History entry [entryId], and moves back the hearts its edit moved. Every Undo of a tag edit comes here: the
 * message's Undo, History's and Covers & tags'.
 */
suspend fun undoEdit(context: Context, entryId: Long): BatchResult {
    val app = context.applicationContext
    // Read again first: an Undo right after its edit must see the names the edit wrote.
    val library = freshLibrary(app)
    val (writer, undo) = withContext(Dispatchers.IO) { TagsGraph.writer(app) to TagsGraph.undo(app) }
    return undoEdit(writer, undo, LibraryGraph.favourites(app), library, entryId)
}

/** [undoEdit] over its parts: [library] as it reads the songs the edit wrote. */
internal suspend fun undoEdit(writer: SafeWriter, undo: UndoStore, favourites: FavouritesPort, library: Library?, entryId: Long): BatchResult {
    val entry = withContext(Dispatchers.IO) { undo.memory() }.entries.firstOrNull { it.id == entryId }
    val result = writer.undo(entryId)
    if (library != null && entry != null && result is BatchResult.Done) {
        moveHearts(favourites) { FavouriteMoves.of(FavouriteMoves.undone(entry, result.writtenPaths(), library), library, it) }
    }
    return result
}

private fun BatchResult.Done.writtenPaths(): Set<String> =
    songs.filter { it.outcome == SongOutcome.WRITTEN || it.outcome == SongOutcome.UNDO_NOT_SAVED }.mapTo(HashSet()) { it.song.path }

private suspend fun moveHearts(favourites: FavouritesPort, moves: (List<FavouriteCollection>) -> List<FavouriteMove>) {
    val planned = moves(favourites.collections.first())
    if (planned.isNotEmpty() && !FavouriteMoves.apply(planned, favourites)) Log.w(LOG_TAG, "tags: ${planned.size} favourites not all moved")
}

/** The library read again now, or as it is when that takes longer than [FRESH_WAIT_MS]. */
private suspend fun freshLibrary(app: Context): Library? {
    val repository = LibraryGraph.repository(app)
    val before = repository.state.value
    repository.refresh()
    val read = withTimeoutOrNull(FRESH_WAIT_MS) { repository.state.first { it !== before && it is LibraryState.Ready } }
    return ((read ?: repository.state.value) as? LibraryState.Ready)?.library
}

private const val FRESH_WAIT_MS = 3_000L

// "2 may be the same"

/** What the suggestions compare: artist names, or album names by the same album artist. */
enum class NameKind { ARTIST, ALBUM }

/** Two names the user said are not the same ("Not the same"): artist names, or album keys. Either way round is one pair. */
@Serializable
data class NamePair(val kind: NameKind, val a: String, val b: String) {
    companion object {
        fun of(kind: NameKind, x: String, y: String): NamePair = if (x <= y) NamePair(kind, x, y) else NamePair(kind, y, x)
    }
}

/**
 * Names that look like spellings of one name: the same after normalising (case, accents, punctuation, a leading "The",
 * "&" and "and"), or one or two letters apart when both are at least [MIN_LENGTH] letters long. An edition ("Deluxe",
 * "Live", "Remaster") is never one. Suggestions only: KJB never merges by itself.
 */
object SameNames {
    private const val MIN_LENGTH = 5
    private const val MAX_APART = 2
    private val EDITION = Regex("\\b(deluxe|live|remaster(ed)?|edition|acoustic|demo|remix|instrumental|bonus)\\b", RegexOption.IGNORE_CASE)

    /** [name] as compared: [TrackNames.normalize] without a leading "the", in letters and digits only. */
    fun key(name: String): String = TrackNames.normalize(name).removePrefix("the ").replace(" ", "")

    fun isEdition(name: String): Boolean = EDITION.containsMatchIn(name)

    fun lookAlike(a: String, b: String): Boolean = !isEdition(a) && !isEdition(b) && keysAlike(key(a), key(b))

    /** Artists that may be the same, not kept apart: in each pair the one with more songs first; pairs by its name. */
    fun artists(artists: List<Artist>, apart: Set<NamePair>): List<Pair<Artist, Artist>> =
        pairs(artists, NameKind.ARTIST, apart, name = { it.name }, id = { it.name }, songs = { it.songs.size })

    /** Albums by the same album artist that may be the same, not kept apart; ordered as [artists]. */
    fun albums(albums: List<Album>, apart: Set<NamePair>): List<Pair<Album, Album>> =
        albums.groupBy { it.artist }.values.flatMap { same -> pairs(same, NameKind.ALBUM, apart, name = { it.title }, id = { it.key }, songs = { it.songs.size }) }
            .sortedWith(compareBy(Collator.getInstance()) { it.first.title })

    /** "Merge with…"'s list: the names that look like [name] first, the closest first, then the rest; each by name. */
    fun similarFirst(name: String, others: List<String>): List<String> {
        val own = key(name)
        val collator = Collator.getInstance()
        return others.sortedWith(compareBy<String> { if (lookAlike(name, it)) distance(own, key(it)) else Int.MAX_VALUE }.thenComparing(collator))
    }

    private fun <T> pairs(items: List<T>, kind: NameKind, apart: Set<NamePair>, name: (T) -> String, id: (T) -> String, songs: (T) -> Int): List<Pair<T, T>> {
        // Editions out, keys once; then only names whose lengths could be two letters apart are compared.
        val keyed = items.filterNot { isEdition(name(it)) }.map { it to key(name(it)) }.sortedBy { it.second.length }
        val found = mutableListOf<Pair<T, T>>()
        for (i in keyed.indices) {
            val (a, keyA) = keyed[i]
            for (j in i + 1 until keyed.size) {
                val (b, keyB) = keyed[j]
                if (keyB.length - keyA.length > MAX_APART) break
                if (!keysAlike(keyA, keyB) || NamePair.of(kind, id(a), id(b)) in apart) continue
                val first = compareByDescending(songs).thenBy(name).compare(a, b) <= 0
                found += if (first) a to b else b to a
            }
        }
        val collator = Collator.getInstance()
        return found.sortedWith(compareBy(collator) { name(it.first) })
    }

    private fun keysAlike(a: String, b: String): Boolean = when {
        a.isEmpty() || b.isEmpty() -> false
        a == b -> true
        minOf(a.length, b.length) < MIN_LENGTH -> false
        else -> distance(a, b) <= MAX_APART
    }

    /** Edit distance, or [MAX_APART] + 1 once it is surely more than that. */
    private fun distance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > MAX_APART) return MAX_APART + 1
        var above = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1)
            row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(above[j + 1] + 1, row[j] + 1, above[j] + if (a[i] == b[j]) 0 else 1)
            if (row.min() > MAX_APART) return MAX_APART + 1
            above = row
        }
        return above[b.length]
    }
}

/**
 * The pairs kept apart with "Not the same", in [file] (`same-names.json`, app-private): read once, leniently (an item
 * this version can't read left out, a file that can't be read at all started over), written whole on each change.
 * Logs counts and exception class names only.
 */
class KeptApart(private val file: File, private val log: (String) -> Unit = {}, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private var loaded = false
    private val current = MutableStateFlow<Set<NamePair>>(emptySet())

    val pairs: StateFlow<Set<NamePair>> = current.asStateFlow()

    /** The pairs, read from [file] the first time. */
    suspend fun load(): Set<NamePair> = mutex.withLock {
        if (!loaded) {
            current.value = withContext(io) { read() }
            loaded = true
        }
        current.value
    }

    /** Keeps [pair] apart from now on. False when it could not be stored (it still counts until KJB restarts). */
    suspend fun add(pair: NamePair): Boolean {
        load()
        return mutex.withLock {
            val now = current.value + pair
            current.value = now
            withContext(io) { write(now) }
        }
    }

    private fun read(): Set<NamePair> {
        if (!file.isFile) return emptySet()
        val root = try {
            json.parseToJsonElement(file.readText()) as? JsonObject
        } catch (e: IOException) {
            log("same names: not readable (${e.javaClass.simpleName})")
            return emptySet()
        } catch (_: SerializationException) {
            null
        }
        val items = (root?.get(APART_KEY) as? JsonArray) ?: return emptySet<NamePair>().also { log("same names: stored file unreadable, started over") }
        val pairs = items.mapNotNull { item ->
            try {
                json.decodeFromJsonElement(NamePair.serializer(), item)
            } catch (_: IllegalArgumentException) {
                // A SerializationException is one.
                null
            }
        }
        if (pairs.size < items.size) log("same names: ${items.size - pairs.size} stored items unreadable, left out")
        return pairs.toSet()
    }

    private fun write(pairs: Set<NamePair>): Boolean {
        val root = JsonObject(mapOf(VERSION_KEY to JsonPrimitive(VERSION), APART_KEY to JsonArray(pairs.map { json.encodeToJsonElement(NamePair.serializer(), it) })))
        val aside = File(file.path + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(aside).use { out ->
                out.write(json.encodeToString(JsonObject.serializer(), root).encodeToByteArray())
                out.fd.sync()
            }
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("same names: not saved (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val VERSION = 1
        const val VERSION_KEY = "version"
        const val APART_KEY = "apart"
        val json = Json { ignoreUnknownKeys = true }
    }
}
