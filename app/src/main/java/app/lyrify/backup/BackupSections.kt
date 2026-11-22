package app.lyrify.backup

import app.lyrify.library.SongRef
import app.lyrify.player.AbPart
import app.lyrify.player.SongState
import app.lyrify.player.Speed
import app.lyrify.stats.Interaction
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.codeOf
import app.lyrify.stats.interactionOf
import app.lyrify.stats.playEndedOf
import app.lyrify.stats.playStartOf
import app.lyrify.stats.zoneOffsetOf
import app.lyrify.tags.CoversTagsMemory
import app.lyrify.tags.NameKind
import app.lyrify.tags.NamePair
import app.lyrify.tags.Settled
import app.lyrify.tags.SongOutcome
import app.lyrify.tags.SongProblem
import app.lyrify.tones.ToneKind
import app.lyrify.tones.TonesState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// The sections a backup writes in its own form, as the stores' forms hold what belongs to one phone (MediaStore ids,
// Undo's copies): what each holds, and its JSON. Each is read leniently: unknown keys are ignored, and each item is read
// on its own, so one this version cannot read is left out and costs nothing else. Null only for text that is not the
// section's JSON object at all.

/** A song's bookmarks (a spot and the user's note), its place (Remember my place) and its own speed, as a backup keeps them. */
data class BackupMarks(val path: String, val placeMs: Long?, val bookmarks: List<BackupBookmark>, val speed: Speed? = null)

data class BackupBookmark(val positionMs: Long, val note: String)

/** The bookmarks section: each song's marks, the speed of every other song, and the part repeating. */
data class BackupSongState(val marks: List<BackupMarks>, val usual: Speed?, val part: AbPart?)

/** A tone in a backup: [app.lyrify.tones.SavedTone] without its MediaStore id, which names a file on the old phone only. */
data class BackupTone(
    val name: String,
    val kind: ToneKind,
    val songPath: String,
    val startMs: Long,
    val lengthMs: Long,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val createdAt: Long,
    val fileName: String,
    val size: Long,
)

/** Duplicates' choices as the phone keeps them: each group by its files' MediaStore ids ([app.lyrify.dupes.DuplicateGroup.key]). */
data class DuplicateChoices(val notDuplicates: Set<String> = emptySet(), val reviewed: Set<String> = emptySet())

/** Duplicates' choices in a backup: each group by its songs' paths, as ids name files on one phone only. */
data class BackupDuplicates(val notDuplicates: List<List<String>>, val reviewed: List<List<String>>)

/** An album Covers & tags settled, by its songs' paths: its Undo stays on the old phone with the copies it needs. */
data class BackupAlbum(
    val state: Settled,
    val title: String,
    val artist: String?,
    val paths: List<String>,
    val at: Long,
    val changed: Int,
    val problems: List<SongProblem>,
)

/** The plays section: every play, the skips among them, and the interaction log kept beside them. */
data class BackupPlays(val plays: List<LoggedPlay>, val interactions: List<Interaction>)

/** Covers & tags in a backup: the albums settled and its two flags. */
data class BackupCovers(val albums: List<BackupAlbum>, val checkedOnce: Boolean, val noted: Boolean)

internal object SectionJson {
    // Plays

    /**
     * Every play with what it keeps, a key for each part it has; the skips ([LoggedPlay.isShort]) apart, under a key of
     * their own that 3.1.0 does not read, so it restores the plays it knew and none it never kept. No key marks an
     * import's play: its shape does (nothing heard, counted), as `plays.db`'s own upgrade finds them. The [interactions]
     * (none when the log could not be read) under a key of their own too, which 3.1.1 and before do not read. The book
     * log's plays (Section.BOOK_PLAYS) are in the same form, without interactions.
     *
     * In parts of at most [partBytes] each (UTF-8; one item bigger alone has a part of its own), each in this same form:
     * the first holds the newest plays, as many as fit, so a KJB that reads only the first (3.1.1 and before) restores
     * those; then the older plays, the skips and the interactions, the newest first. One part when all fit, as before.
     * Each part is made as it is asked for, so a long history never stands in memory whole as JSON.
     */
    fun plays(plays: List<LoggedPlay>, interactions: List<Interaction>? = null, partBytes: Int = Int.MAX_VALUE): List<String> {
        // Each list the newest first, the order the parts are filled in.
        val kept = plays.filterNot { it.isShort }.asReversed()
        val skips = plays.filter { it.isShort }.asReversed()
        val logged = interactions?.asReversed()
        val lists = listOfNotNull(
            Listed(PLAYS_KEY, kept.size) { play(kept[it]) },
            Listed(SKIPS_KEY, skips.size) { play(skips[it]) },
            logged?.let { Listed(INTERACTIONS_KEY, it.size) { at -> interaction(it[at]) } },
        )
        var parts = 1
        var used = 0L
        lists.forEach { list ->
            for (item in 0 until list.size) {
                // Its JSON and the comma before it.
                val size = list.json(item).toString().encodeToByteArray().size + 1L
                if (used > 0 && used + size > partBytes - PART_FRAME_BYTES) {
                    parts++
                    used = 0
                }
                used += size
                list.parts[item] = parts - 1
            }
        }
        // Each part's lists the oldest first, as a section in one part has them.
        return object : AbstractList<String>() {
            override val size: Int = parts

            override fun get(index: Int): String =
                JsonObject(lists.associate { list -> list.key to JsonArray((list.size - 1 downTo 0).filter { list.parts[it] == index }.map(list.json)) }).toString()
        }
    }

    /** One of the plays section's lists, its items by their place ([json]), and the part each goes in ([parts]). */
    private class Listed(val key: String, val size: Int, val json: (Int) -> JsonObject) {
        val parts = IntArray(size)
    }

    private fun play(play: LoggedPlay): JsonObject = JsonObject(
        buildMap {
            put(PATH_KEY, JsonPrimitive(play.song.path))
            put(STARTED_AT_KEY, JsonPrimitive(play.startedAt))
            put(HEARD_KEY, JsonPrimitive(play.heardMs))
            put(COUNTED_KEY, JsonPrimitive(play.counted))
            play.start?.let { start ->
                put(STARTED_BY_KEY, JsonPrimitive(codeOf(start.by)))
                start.source?.let { put(SOURCE_KEY, JsonPrimitive(codeOf(it))) }
                put(SHUFFLED_KEY, JsonPrimitive(start.shuffled))
                start.songKey?.let { put(SONG_KEY_KEY, JsonPrimitive(it)) }
            }
            play.zoneOffsetMinutes?.let { put(ZONE_KEY, JsonPrimitive(it)) }
            play.end?.let { end ->
                put(ENDED_KEY, JsonPrimitive(codeOf(end.how)))
                put(ENDED_AT_KEY, JsonPrimitive(end.atMs))
            }
            play.clockMs?.let { put(CLOCK_KEY, JsonPrimitive(it)) }
        },
    )

    /**
     * The plays, then the skips (none in a backup from before), each read on its own: one without a path or a start is
     * left out, and so is a skip that is none. What a play keeps is read as `plays.db` reads it ([playStartOf],
     * [playEndedOf], [zoneOffsetOf]); one with nothing heard that counted is an import's. The interactions (none in a
     * backup from before) as `plays.db` reads them ([interactionOf]).
     */
    fun plays(text: String): BackupPlays? {
        val root = jsonObject(text) ?: return null
        val plays = (root[PLAYS_KEY] as? JsonArray ?: return null).mapNotNull(::play)
        val skips = (root[SKIPS_KEY] as? JsonArray).orEmpty().mapNotNull(::play).filter { it.isShort }
        return BackupPlays(plays + skips, (root[INTERACTIONS_KEY] as? JsonArray).orEmpty().mapNotNull(::interaction))
    }

    /** An interaction, a key for each part it has, codes as `plays.db` keeps them. */
    private fun interaction(interaction: Interaction): JsonObject = JsonObject(
        buildMap {
            put(AT_KEY, JsonPrimitive(interaction.at))
            put(KIND_KEY, JsonPrimitive(codeOf(interaction.kind)))
            interaction.zoneOffsetMinutes?.let { put(ZONE_KEY, JsonPrimitive(it)) }
            interaction.target?.let { target ->
                put(TARGET_KEY, JsonPrimitive(codeOf(target.kind)))
                put(TARGET_KEY_KEY, JsonPrimitive(target.key))
            }
            interaction.words?.let { put(WORDS_KEY, JsonPrimitive(it)) }
        },
    )

    private fun interaction(element: JsonElement): Interaction? {
        val stored = element as? JsonObject ?: return null
        return interactionOf(stored.long(AT_KEY), stored.text(KIND_KEY), stored.long(ZONE_KEY), stored.text(TARGET_KEY), stored.text(TARGET_KEY_KEY), stored.text(WORDS_KEY))
    }

    private fun play(element: JsonElement): LoggedPlay? {
        val play = element as? JsonObject ?: return null
        val heardMs = play.long(HEARD_KEY)?.coerceAtLeast(0) ?: 0
        val counted = play.flag(COUNTED_KEY) ?: false
        return LoggedPlay(
            song = SongRef(play.text(PATH_KEY)?.takeIf(String::isNotEmpty) ?: return null),
            startedAt = play.long(STARTED_AT_KEY)?.takeIf { it > 0 } ?: return null,
            heardMs = heardMs,
            counted = counted,
            start = playStartOf(play.text(STARTED_BY_KEY), play.text(SOURCE_KEY), play.flag(SHUFFLED_KEY), play.text(SONG_KEY_KEY)),
            zoneOffsetMinutes = zoneOffsetOf(play.long(ZONE_KEY)),
            end = playEndedOf(play.text(ENDED_KEY), play.long(ENDED_AT_KEY)),
            imported = counted && heardMs == 0L,
            clockMs = play.long(CLOCK_KEY)?.coerceAtLeast(0),
        )
    }

    // Places, bookmarks and speeds

    /**
     * Each song's place, bookmarks and own speed, read through the song state's own API: songs with a place first, the
     * oldest first, as it keeps them; then the usual speed and the part repeating. Songs by their paths only.
     */
    fun marks(state: SongState): String {
        val songs = (state.places.keys + state.bookmarks.keys + state.speeds.keys).distinct().map { path ->
            JsonObject(
                buildMap {
                    put(PATH_KEY, JsonPrimitive(path))
                    state.places[path]?.let { put(PLACE_KEY, JsonPrimitive(it)) }
                    put(BOOKMARKS_KEY, JsonArray(state.bookmarksOf(path).map { JsonObject(mapOf(POSITION_KEY to JsonPrimitive(it.positionMs), NOTE_KEY to JsonPrimitive(it.note))) }))
                    state.speeds[path]?.let { put(SPEED_KEY, speed(it)) }
                },
            )
        }
        return JsonObject(
            buildMap {
                put(USUAL_KEY, speed(state.usual))
                state.part?.let { put(PART_KEY, JsonObject(mapOf(PATH_KEY to JsonPrimitive(it.path), START_KEY to JsonPrimitive(it.startMs), END_KEY to JsonPrimitive(it.endMs)))) }
                put(SONGS_KEY, JsonArray(songs))
            },
        ).toString()
    }

    /**
     * Each song read on its own: a song without a path, a place below 0 and a bookmark without a spot are left out, and
     * so is a song left with nothing; a note may be missing. A backup from before speeds were kept has none.
     */
    fun marks(text: String): BackupSongState? {
        val root = jsonObject(text) ?: return null
        val songs = (root[SONGS_KEY] as? JsonArray ?: return null).mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            val bookmarks = (song[BOOKMARKS_KEY] as? JsonArray).orEmpty().mapNotNull { stored ->
                val mark = stored as? JsonObject ?: return@mapNotNull null
                BackupBookmark(mark.long(POSITION_KEY)?.takeIf { it >= 0 } ?: return@mapNotNull null, mark.text(NOTE_KEY).orEmpty())
            }
            BackupMarks(song.text(PATH_KEY)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null, song.long(PLACE_KEY)?.takeIf { it >= 0 }, bookmarks, song[SPEED_KEY]?.let(::speed))
                .takeIf { it.placeMs != null || it.bookmarks.isNotEmpty() || it.speed != null }
        }.distinctBy { it.path }
        val part = (root[PART_KEY] as? JsonObject)?.let { stored ->
            val path = stored.text(PATH_KEY)?.takeIf(String::isNotEmpty)
            val start = stored.long(START_KEY)?.takeIf { it >= 0 }
            val end = stored.long(END_KEY)
            if (path != null && start != null && end != null && end > start) AbPart(path, start, end) else null
        }
        return BackupSongState(songs, root[USUAL_KEY]?.let(::speed), part)
    }

    private fun speed(speed: Speed): JsonObject =
        JsonObject(mapOf(SPEED_KEY to JsonPrimitive(speed.speed), KEEP_PITCH_KEY to JsonPrimitive(speed.keepPitch), SEMITONES_KEY to JsonPrimitive(speed.semitones)))

    /** A speed, each field its default when it cannot be used, held to the player's steps; null when it is not an object. */
    private fun speed(element: JsonElement): Speed? {
        val stored = element as? JsonObject ?: return null
        val d = Speed()
        return Speed(
            speed = (stored[SPEED_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull?.takeIf { it.isFinite() } ?: d.speed,
            keepPitch = stored.flag(KEEP_PITCH_KEY) ?: d.keepPitch,
            semitones = (stored[SEMITONES_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: d.semitones,
        ).normalised()
    }

    // Tones

    fun tones(state: TonesState): String = JsonObject(
        mapOf(
            TONES_KEY to JsonArray(
                state.tones.values.sortedBy { it.createdAt }.map { tone ->
                    JsonObject(
                        mapOf(
                            NAME_KEY to JsonPrimitive(tone.name),
                            KIND_KEY to JsonPrimitive(tone.kind.name),
                            SONG_KEY to JsonPrimitive(tone.songPath),
                            START_KEY to JsonPrimitive(tone.startMs),
                            LENGTH_KEY to JsonPrimitive(tone.lengthMs),
                            FADE_IN_KEY to JsonPrimitive(tone.fadeInMs),
                            FADE_OUT_KEY to JsonPrimitive(tone.fadeOutMs),
                            CREATED_AT_KEY to JsonPrimitive(tone.createdAt),
                            FILE_NAME_KEY to JsonPrimitive(tone.fileName),
                            SIZE_KEY to JsonPrimitive(tone.size),
                        ),
                    )
                },
            ),
        ),
    ).toString()

    /** The tones, each read on its own: one without a name, a kind this version knows, its song, its file or its cut is left out. */
    fun tones(text: String): List<BackupTone>? = (jsonObject(text)?.get(TONES_KEY) as? JsonArray)?.mapNotNull { element ->
        val tone = element as? JsonObject ?: return@mapNotNull null
        BackupTone(
            name = tone.text(NAME_KEY)?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
            kind = ToneKind.entries.firstOrNull { it.name == tone.text(KIND_KEY) } ?: return@mapNotNull null,
            songPath = tone.text(SONG_KEY)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null,
            startMs = tone.long(START_KEY)?.takeIf { it >= 0 } ?: return@mapNotNull null,
            lengthMs = tone.long(LENGTH_KEY)?.takeIf { it > 0 } ?: return@mapNotNull null,
            fadeInMs = tone.long(FADE_IN_KEY)?.coerceAtLeast(0) ?: 0,
            fadeOutMs = tone.long(FADE_OUT_KEY)?.coerceAtLeast(0) ?: 0,
            createdAt = tone.long(CREATED_AT_KEY) ?: 0,
            fileName = tone.text(FILE_NAME_KEY)?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
            size = tone.long(SIZE_KEY)?.takeIf { it > 0 } ?: return@mapNotNull null,
        )
    }

    // Duplicates

    /** Each group by its songs' paths ([pathOf], from the library); a group with a file the library does not list is left out. */
    fun duplicates(choices: DuplicateChoices, pathOf: (Long) -> String?): String {
        fun groups(keys: Set<String>) = JsonArray(
            keys.sorted().mapNotNull { key ->
                key.split(',').map { id -> id.toLongOrNull()?.let(pathOf) ?: return@mapNotNull null }.let { paths -> JsonArray(paths.map(::JsonPrimitive)) }
            },
        )
        return JsonObject(mapOf(NOT_DUPLICATES_KEY to groups(choices.notDuplicates), REVIEWED_KEY to groups(choices.reviewed))).toString()
    }

    fun duplicates(text: String): BackupDuplicates? {
        val root = jsonObject(text) ?: return null
        fun groups(key: String) = (root[key] as? JsonArray).orEmpty().mapNotNull { group ->
            (group as? JsonArray)?.mapNotNull { it.text()?.takeIf(String::isNotEmpty) }?.distinct()?.takeIf { it.isNotEmpty() }
        }
        return BackupDuplicates(groups(NOT_DUPLICATES_KEY), groups(REVIEWED_KEY))
    }

    // Covers & tags

    fun covers(memory: CoversTagsMemory): String = JsonObject(
        mapOf(
            ALBUMS_KEY to JsonArray(
                memory.albums.map { album ->
                    JsonObject(
                        buildMap {
                            put(STATE_KEY, JsonPrimitive(album.state.name))
                            put(TITLE_KEY, JsonPrimitive(album.title))
                            album.artist?.let { put(ARTIST_KEY, JsonPrimitive(it)) }
                            put(PATHS_KEY, JsonArray(album.songs.map { JsonPrimitive(it.path) }))
                            put(AT_KEY, JsonPrimitive(album.at))
                            put(CHANGED_KEY, JsonPrimitive(album.changed))
                            put(PROBLEMS_KEY, JsonArray(album.problems.map { JsonObject(mapOf(PATH_KEY to JsonPrimitive(it.path), OUTCOME_KEY to JsonPrimitive(it.outcome.name))) }))
                        },
                    )
                },
            ),
            CHECKED_KEY to JsonPrimitive(memory.checkedOnce),
            NOTED_KEY to JsonPrimitive(memory.noted),
        ),
    ).toString()

    fun covers(text: String): BackupCovers? {
        val root = jsonObject(text) ?: return null
        val albums = (root[ALBUMS_KEY] as? JsonArray).orEmpty().mapNotNull { element ->
            val album = element as? JsonObject ?: return@mapNotNull null
            BackupAlbum(
                state = Settled.entries.firstOrNull { it.name == album.text(STATE_KEY) } ?: return@mapNotNull null,
                title = album.text(TITLE_KEY) ?: return@mapNotNull null,
                artist = album.text(ARTIST_KEY),
                paths = (album[PATHS_KEY] as? JsonArray)?.mapNotNull { it.text()?.takeIf(String::isNotEmpty) }?.distinct()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                at = album.long(AT_KEY) ?: 0,
                changed = album.long(CHANGED_KEY)?.toInt()?.coerceAtLeast(0) ?: 0,
                problems = (album[PROBLEMS_KEY] as? JsonArray).orEmpty().mapNotNull { stored ->
                    val problem = stored as? JsonObject ?: return@mapNotNull null
                    val outcome = SongOutcome.entries.firstOrNull { it.name == problem.text(OUTCOME_KEY) } ?: return@mapNotNull null
                    SongProblem(problem.text(PATH_KEY)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null, outcome)
                },
            )
        }
        return BackupCovers(albums, root.flag(CHECKED_KEY) ?: false, root.flag(NOTED_KEY) ?: false)
    }

    // Names kept apart

    fun names(apart: Set<NamePair>): String = JsonObject(
        mapOf(APART_KEY to JsonArray(apart.sortedWith(compareBy({ it.kind }, { it.a }, { it.b })).map { JsonObject(mapOf(KIND_KEY to JsonPrimitive(it.kind.name), A_KEY to JsonPrimitive(it.a), B_KEY to JsonPrimitive(it.b))) })),
    ).toString()

    /** The pairs, each read on its own: one of a kind this version does not know, or without both names, is left out. */
    fun names(text: String): Set<NamePair>? = (jsonObject(text)?.get(APART_KEY) as? JsonArray)?.mapNotNull { element ->
        val pair = element as? JsonObject ?: return@mapNotNull null
        val kind = NameKind.entries.firstOrNull { it.name == pair.text(KIND_KEY) } ?: return@mapNotNull null
        NamePair.of(kind, pair.text(A_KEY) ?: return@mapNotNull null, pair.text(B_KEY) ?: return@mapNotNull null)
    }?.toSet()

    // Recent searches

    fun searches(searches: List<String>): String = JsonObject(mapOf(SEARCHES_KEY to JsonArray(searches.map(::JsonPrimitive)))).toString()

    fun searches(text: String): List<String>? = (jsonObject(text)?.get(SEARCHES_KEY) as? JsonArray)?.mapNotNull { it.text()?.takeIf(String::isNotBlank) }?.distinct()

    // Reading

    fun jsonObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    fun JsonObject.text(key: String): String? = this[key]?.text()

    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    const val PATH_KEY: String = "path"
    const val SONGS_KEY: String = "songs"
    const val TITLE_KEY: String = "title"
    const val ARTIST_KEY: String = "artist"
    private const val PLAYS_KEY: String = "plays"
    private const val STARTED_AT_KEY: String = "startedAt"
    private const val HEARD_KEY: String = "heardMs"
    private const val COUNTED_KEY: String = "counted"
    private const val SKIPS_KEY: String = "skips"
    private const val STARTED_BY_KEY: String = "startedBy"
    private const val SOURCE_KEY: String = "source"
    private const val SHUFFLED_KEY: String = "shuffled"
    private const val SONG_KEY_KEY: String = "songKey"
    private const val ZONE_KEY: String = "zoneOffset"
    private const val ENDED_KEY: String = "ended"
    private const val ENDED_AT_KEY: String = "endedAtMs"
    private const val CLOCK_KEY: String = "clockMs"
    private const val INTERACTIONS_KEY: String = "interactions"

    /** Room in a part for its keys and brackets around the lists: `{"plays":[],"skips":[],"interactions":[]}`. */
    private const val PART_FRAME_BYTES: Int = 64
    private const val TARGET_KEY: String = "target"
    private const val TARGET_KEY_KEY: String = "targetKey"
    private const val WORDS_KEY: String = "words"
    private const val PLACE_KEY: String = "placeMs"
    private const val BOOKMARKS_KEY: String = "bookmarks"
    private const val POSITION_KEY: String = "positionMs"
    private const val NOTE_KEY: String = "note"
    private const val SPEED_KEY: String = "speed"
    private const val KEEP_PITCH_KEY: String = "keepPitch"
    private const val SEMITONES_KEY: String = "semitones"
    private const val USUAL_KEY: String = "usual"
    private const val PART_KEY: String = "part"
    private const val START_KEY: String = "startMs"
    private const val END_KEY: String = "endMs"
    private const val TONES_KEY: String = "tones"
    private const val NAME_KEY: String = "name"
    private const val KIND_KEY: String = "kind"
    private const val SONG_KEY: String = "song"
    private const val LENGTH_KEY: String = "lengthMs"
    private const val FADE_IN_KEY: String = "fadeInMs"
    private const val FADE_OUT_KEY: String = "fadeOutMs"
    private const val CREATED_AT_KEY: String = "createdAt"
    private const val FILE_NAME_KEY: String = "fileName"
    private const val SIZE_KEY: String = "size"
    private const val NOT_DUPLICATES_KEY: String = "notDuplicates"
    private const val REVIEWED_KEY: String = "reviewed"
    private const val ALBUMS_KEY: String = "albums"
    private const val STATE_KEY: String = "state"
    private const val PATHS_KEY: String = "paths"
    private const val AT_KEY: String = "at"
    private const val CHANGED_KEY: String = "changed"
    private const val PROBLEMS_KEY: String = "problems"
    private const val OUTCOME_KEY: String = "outcome"
    private const val CHECKED_KEY: String = "checkedOnce"
    private const val NOTED_KEY: String = "noted"
    private const val APART_KEY: String = "apart"
    private const val A_KEY: String = "a"
    private const val B_KEY: String = "b"
    private const val SEARCHES_KEY: String = "searches"
}
