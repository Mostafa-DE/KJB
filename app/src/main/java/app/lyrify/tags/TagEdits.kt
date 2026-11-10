package app.lyrify.tags

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Song
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Collator

// Edit tags (manual), milestone T3: what Edit tags, Edit album, Rename artist and Merge plan to write, the names that
// may be the same, and what a write or an Undo says. Pure, apart from the kept-apart pairs' file; the writer
// (SafeWriter) does every write, one song at a time, and keeps History.

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
}

/** The plans of Edit tags, Edit album, Rename artist and Merge (player-spec "Edit tags (manual)"). */
object TagEdits {
    /** The fields Edit album edits for every song of the album at once. */
    val ALBUM_FIELDS: List<TagField> = listOf(TagField.ALBUM, TagField.ALBUM_ARTIST, TagField.YEAR, TagField.GENRE)

    /**
     * Edit tags for [songs]. One song gets each field that differs from where the form began (an emptied one is
     * removed). Several get each field typed into, in every song that differs; a field left empty keeps each song's own.
     */
    fun songs(songs: List<Song>, form: TagForm, cover: CoverEdit): List<SongPlan> {
        val one = songs.size == 1
        val typed = form.values.filter { (field, value) -> form.changed(field, value) }
            .mapValues { (field, value) -> field.clean(value) }
            .filter { (_, value) -> one || value.isNotEmpty() }
        return plans(songs, cover) { song, _ -> typed.filter { (field, value) -> field.of(song) != value } }
    }

    /**
     * Edit album for [songs] in the album page's order: each shared field changed (an emptied one removed, a mixed one
     * left empty kept) in every song that differs; with [numberInOrder], the tracks numbered 1, 2, 3 in that order on
     * disc 1 (a song without a disc number keeps none).
     */
    fun album(songs: List<Song>, form: TagForm, cover: CoverEdit, numberInOrder: Boolean): List<SongPlan> {
        val shared = ALBUM_FIELDS.filter { form.changed(it) }.associateWith { form.value(it).trim() }
            .filterNot { (field, value) -> value.isEmpty() && field in form.mixed }
        return plans(songs, cover) { song, at ->
            val fields = shared.filter { (field, value) -> field.of(song) != value }.toMutableMap()
            if (numberInOrder) {
                if (song.track != at + 1) fields[TagField.TRACK] = (at + 1).toString()
                if (song.disc != null && song.disc != 1) fields[TagField.DISC] = "1"
            }
            fields
        }
    }

    /**
     * Rename artist ([names] one name) and Merge artists ([names] both): each of [names] becomes [to] as a whole word in
     * the artist and album artist of [songs], inside credits too ("Kit Calloway feat. Mira Okafor"). Case counts: a
     * spelling in other letters is another artist, for Merge.
     */
    fun rename(songs: List<Song>, names: Collection<String>, to: String): List<SongPlan> {
        val name = to.trim()
        if (name.isEmpty()) return emptyList()
        return plans(songs, CoverEdit.Keep) { song, _ ->
            listOfNotNull(
                song.artist?.let { TagField.ARTIST to replaceNames(it, names, name) }?.takeIf { (_, new) -> new != song.artist },
                song.albumArtist?.let { TagField.ALBUM_ARTIST to replaceNames(it, names, name) }?.takeIf { (_, new) -> new != song.albumArtist },
            ).toMap()
        }
    }

    /** Merge albums: every song of [albums] whose album is not [keep] takes it. */
    fun mergeAlbums(albums: List<Album>, keep: String): List<SongPlan> {
        val name = keep.trim()
        if (name.isEmpty()) return emptyList()
        return plans(albums.flatMap { it.songs }, CoverEdit.Keep) { song, _ ->
            if (song.album?.trim() == name) emptyMap() else mapOf(TagField.ALBUM to name)
        }
    }

    /**
     * [text] with each of [names] replaced by [to] where it stands as a whole word (no letter or digit touching it), in
     * one pass, so [to] is never replaced again. Blank names and [to] itself are left out.
     */
    fun replaceNames(text: String, names: Collection<String>, to: String): String {
        val replaced = names.filter { it.isNotBlank() && it != to }.distinct().sortedByDescending { it.length }
        if (replaced.isEmpty()) return text
        val pattern = Regex("(?<![\\p{L}\\p{N}])(?:" + replaced.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])")
        return pattern.replace(text, Regex.escapeReplacement(to))
    }

    /**
     * The cover change [song] gets: an Opus song keeps its cover when the new one would pass what readers take of its
     * comment header even alone (covers-tags-spec "What is written").
     */
    fun coverFor(song: Song, cover: CoverEdit): CoverEdit = when {
        cover is CoverEdit.Replace && song.path.endsWith(".opus", ignoreCase = true) && !OpusCover.fits(cover.picture.data.size, 0) -> CoverEdit.Keep
        else -> cover
    }

    /** The album Find online looks up: the songs being edited, as Covers & tags groups an album. */
    fun phoneAlbum(songs: List<Song>): PhoneAlbum = PhoneAlbum.of(
        songs.map { PhoneSong(it.path, it.title, it.artist, it.album, it.albumArtist, it.disc, it.track, it.durationMs, it.year) },
    )

    /** Each song's plan from [fields] (given the song and its place), with the cover; songs with nothing to change left out. */
    private fun plans(songs: List<Song>, cover: CoverEdit, fields: (Song, Int) -> Map<TagField, String>): List<SongPlan> =
        songs.mapIndexed { at, song ->
            // In the fields' own order, as History lists them.
            val changed = fields(song, at).entries.sortedBy { it.key.ordinal }.associate { it.key to it.value }
            SongPlan(song, changed, coverFor(song, cover))
        }.filter { it.changesSomething }
}

/**
 * A photo from the phone's photo picker as a front cover: one too large for the cover pipeline, or in a format it does
 * not take (HEIC), is first made a JPEG of at most [CoverPipeline.MAX_SIDE] px; then the pipeline makes it what every
 * cover is. Null when it is not an image.
 */
fun pickedCover(image: ByteArray, codec: ImageCodec, pipeline: CoverPipeline = CoverPipeline(codec)): TagPicture? {
    val taken = if (image.size <= Body.IMAGE.maxBytes && Images.isImage(image)) image else codec.jpeg(image, CoverPipeline.MAX_SIDE, BEST_QUALITY) ?: return null
    val cover = pipeline.prepare(taken) ?: return null
    return TagPicture(cover.jpeg, "", FRONT_COVER, "image/jpeg")
}

private const val BEST_QUALITY = 100

/**
 * How a write or an Undo went, for its message: songs [written] (an Undo: taken back), left alone while in use
 * ([busy]), that could not be changed ([failed]), whose tags or audio [changedSince] (an Undo), and the fields an Undo
 * kept because a later edit changed them ([skippedFields]).
 */
data class EditReport(val written: Int, val busy: Int, val failed: Int, val changedSince: Int, val skippedFields: Int)

fun BatchResult.Done.report(): EditReport {
    val counts = songs.groupingBy { it.outcome }.eachCount()
    fun count(vararg outcomes: SongOutcome) = outcomes.sumOf { counts[it] ?: 0 }
    return EditReport(
        // Written, its Undo saved by the start-up recovery.
        written = count(SongOutcome.WRITTEN, SongOutcome.UNDO_NOT_SAVED),
        busy = count(SongOutcome.BUSY),
        failed = count(SongOutcome.COULD_NOT_CHANGE, SongOutcome.LEFT_AS_IT_WAS, SongOutcome.RECOVERY_PENDING, SongOutcome.NOT_ALLOWED, SongOutcome.NOT_TRIED),
        changedSince = count(SongOutcome.CHANGED_SINCE, SongOutcome.DIFFERENT_FILE),
        skippedFields = skippedFields,
    )
}

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
