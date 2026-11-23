package app.lyrify.huddle

import app.lyrify.backup.ImportSong
import app.lyrify.backup.SectionJson.flag
import app.lyrify.backup.SectionJson.int
import app.lyrify.backup.SectionJson.jsonObject
import app.lyrify.backup.SectionJson.long
import app.lyrify.backup.SectionJson.text
import app.lyrify.backup.SongMatcher
import app.lyrify.library.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A song as Huddle sends it: what a friend's KJB needs to find it in their own library, never the file. */
@Serializable
data class CardSong(val title: String, val artist: String?, val album: String?, val durationMs: Long)

@Serializable
enum class CardKind { SONG, ALBUM, PLAYLIST }

/** What a friend sends: one song, an album or a playlist ([songs] in order), the album's or playlist's [name], and a [note]. */
@Serializable
data class Card(val kind: CardKind, val songs: List<CardSong>, val name: String? = null, val note: String? = null)

/** What a friend's player does now: plays, is paused, or stopped (KJB closed, or the queue emptied). */
enum class NowState { PLAYING, PAUSED, STOPPED }

/** One message between two friends, sealed in its mailbox: [id] is random (a copy the server sends again is dropped), [at] when it was made. */
sealed interface Message {
    val id: String
    val at: Long

    /** A song, an album or a playlist, with its note. */
    data class Share(override val id: String, override val at: Long, val card: Card) : Message

    /** The friend played [song] (from [at]), a [favourite] of theirs or not, shared only when they chose to share their plays with you. */
    data class Play(override val id: String, override val at: Long, val song: CardSong, val favourite: Boolean = false) : Message

    /** The friend removed you: your KJB removes them too. */
    data class Bye(override val id: String, override val at: Long) : Message

    /**
     * The friend's [name], sent whenever they set it, and whether yours is the name of another of their friends
     * ([taken]: pick another, so they can tell you apart). The server keeps only the newest one waiting.
     */
    data class Profile(override val id: String, override val at: Long, val name: String, val taken: Boolean = false) : Message

    /**
     * What the friend's player does now ([state]), at [positionMs] in [song], told [at]: at each play, pause, resume, skip,
     * seek and stop, and every [SharedPlays.HEARTBEAT_MS] while it plays. The server keeps only the newest one waiting,
     * and the phone only in memory.
     */
    data class Now(override val id: String, override val at: Long, val song: CardSong, val state: NowState, val positionMs: Long, val favourite: Boolean = false) : Message
}

/**
 * Messages as their sealed boxes hold them: JSON, then 0x80 and zero bytes up to one of a few sizes ([SIZES]), so the
 * server learns little from a box's length. Keys are short to fit long playlists: "v" format, "i" id, "t" time, "k" kind,
 * "c" card, "s" song or songs, "n" name, "m" note, "f" a favourite, "x" your name taken, "e" a now's state, "p" its place
 * in millis; a song is "t" title, "a" artist, "l" album, "d" length in millis.
 * A message of an unknown kind or format (a newer KJB's) reads as null and is dropped.
 */
object Messages {
    /** The most songs a card carries, as Share's limit ([app.lyrify.ui.SHARE_SONGS_MAX]); fewer when they do not fit [SIZES]'s largest. */
    const val SONGS_MAX: Int = 500

    /** The longest title, artist, album or list name a card keeps, in characters. */
    const val TEXT_MAX: Int = 200

    /** The longest note, in characters. */
    const val NOTE_MAX: Int = 500

    /** A card of [songs] from the library, their tags as they are (a friend's KJB matches by tags, not by KJB's renames), trimmed to the limits. */
    fun card(kind: CardKind, songs: List<Song>, name: String? = null, note: String? = null): Card =
        Card(kind = kind, songs = songs.take(SONGS_MAX).map(::song), name = clip(name, TEXT_MAX), note = clip(note, NOTE_MAX))

    /** [song] as a card or a shared play carries it: its tags, trimmed to [TEXT_MAX], and its length. */
    fun song(song: Song): CardSong = CardSong(clip(song.title, TEXT_MAX).orEmpty(), clip(song.artist, TEXT_MAX), clip(song.album, TEXT_MAX), song.durationMs)

    /**
     * [message] padded to its size; a share too long for the largest keeps its first songs, as many as fit. A play and a now
     * have the smallest size only (the server keeps them of 2 KB at most): long tags in a script of several bytes a
     * character are cut, each half as long again, until it fits.
     */
    fun encode(message: Message): ByteArray {
        var plain = json(message)
        var keep = TEXT_MAX
        while ((message is Message.Play || message is Message.Now) && plain.size >= SIZES.first() && keep > 1) {
            keep /= 2
            plain = json(
                when (message) {
                    is Message.Play -> message.copy(song = message.song.clipped(keep))
                    is Message.Now -> message.copy(song = message.song.clipped(keep))
                },
            )
        }
        if (plain.size > MAX_PLAIN && message is Message.Share) {
            // The most songs that fit: a few tries, as each try writes the whole card.
            var fits = 0
            var over = message.card.songs.size
            while (over - fits > 1) {
                val middle = (fits + over) / 2
                if (json(message.withSongs(middle)).size <= MAX_PLAIN) fits = middle else over = middle
            }
            require(fits > 0) { "one song does not fit a message" }
            plain = json(message.withSongs(fits))
        }
        require(plain.size <= MAX_PLAIN) { "a message of ${plain.size} bytes" }
        val size = SIZES.first { it > plain.size }
        return plain + END + ByteArray(size - plain.size - 1)
    }

    /** The message [bytes] hold; null for anything else. */
    fun decode(bytes: ByteArray): Message? {
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == ZERO) end--
        if (end == 0 || bytes[end - 1] != END) return null
        val root = jsonObject(bytes.decodeToString(0, end - 1)) ?: return null
        if (root.int(FORMAT_KEY) != FORMAT) return null
        val id = root.text(ID_KEY) ?: return null
        val at = root.long(TIME_KEY) ?: return null
        return when (root.text(KIND_KEY)) {
            SHARE -> (root[CARD_KEY] as? JsonObject)?.let(::cardOf)?.let { Message.Share(id, at, it) }
            PLAY -> (root[SONG_KEY] as? JsonObject)?.let(::songOf)?.let { Message.Play(id, at, it, favourite = root.flag(FAVOURITE_KEY) == true) }
            BYE -> Message.Bye(id, at)
            PROFILE -> root.text(NAME_KEY)?.let(Invites::cleanName)?.let { Message.Profile(id, at, it, taken = root.flag(TAKEN_KEY) == true) }
            NOW -> {
                val song = (root[SONG_KEY] as? JsonObject)?.let(::songOf) ?: return null
                // A state a newer KJB tells, or no place: not this version's.
                val state = NowState.entries.firstOrNull { it.name.lowercase() == root.text(STATE_KEY) } ?: return null
                Message.Now(id, at, song, state, root.long(POSITION_KEY)?.takeIf { it >= 0 } ?: return null, favourite = root.flag(FAVOURITE_KEY) == true)
            }
            else -> null
        }
    }

    /** [card] as JSON, as a message and an invite link carry it. */
    internal fun cardJson(card: Card): JsonObject = JsonObject(
        buildMap {
            put(KIND_KEY, JsonPrimitive(card.kind.name.lowercase()))
            card.name?.let { put(NAME_KEY, JsonPrimitive(it)) }
            card.note?.let { put(NOTE_KEY, JsonPrimitive(it)) }
            put(SONG_KEY, JsonArray(card.songs.map(::songJson)))
        },
    )

    /** The card [json] holds: of a known kind, with one song at least; null else. Its texts as [shown]. */
    internal fun cardOf(json: JsonObject): Card? {
        val kind = CardKind.entries.firstOrNull { it.name.lowercase() == json.text(KIND_KEY) } ?: return null
        val songs = (json[SONG_KEY] as? JsonArray)?.map { (it as? JsonObject)?.let(::songOf) ?: return null }?.takeIf { it.isNotEmpty() } ?: return null
        return Card(kind, songs, json.text(NAME_KEY)?.let(::shown), json.text(NOTE_KEY)?.let(::shown))
    }

    private fun json(message: Message): ByteArray = JsonObject(
        buildMap {
            put(FORMAT_KEY, JsonPrimitive(FORMAT))
            put(ID_KEY, JsonPrimitive(message.id))
            put(TIME_KEY, JsonPrimitive(message.at))
            when (message) {
                is Message.Share -> {
                    put(KIND_KEY, JsonPrimitive(SHARE))
                    put(CARD_KEY, cardJson(message.card))
                }
                is Message.Play -> {
                    put(KIND_KEY, JsonPrimitive(PLAY))
                    put(SONG_KEY, songJson(message.song))
                    if (message.favourite) put(FAVOURITE_KEY, JsonPrimitive(true))
                }
                is Message.Bye -> put(KIND_KEY, JsonPrimitive(BYE))
                is Message.Profile -> {
                    put(KIND_KEY, JsonPrimitive(PROFILE))
                    put(NAME_KEY, JsonPrimitive(message.name))
                    if (message.taken) put(TAKEN_KEY, JsonPrimitive(true))
                }
                is Message.Now -> {
                    put(KIND_KEY, JsonPrimitive(NOW))
                    put(SONG_KEY, songJson(message.song))
                    put(STATE_KEY, JsonPrimitive(message.state.name.lowercase()))
                    put(POSITION_KEY, JsonPrimitive(message.positionMs))
                    if (message.favourite) put(FAVOURITE_KEY, JsonPrimitive(true))
                }
            }
        },
    ).toString().encodeToByteArray()

    private fun Message.Share.withSongs(count: Int): Message.Share = copy(card = card.copy(songs = card.songs.take(count)))

    /** This song with its title, artist and album at most [max] characters each. */
    private fun CardSong.clipped(max: Int): CardSong = CardSong(clip(title, max).orEmpty(), clip(artist, max), clip(album, max), durationMs)

    private fun songJson(song: CardSong): JsonElement = JsonObject(
        buildMap {
            put(TITLE_KEY, JsonPrimitive(song.title))
            song.artist?.let { put(ARTIST_KEY, JsonPrimitive(it)) }
            song.album?.let { put(ALBUM_KEY, JsonPrimitive(it)) }
            put(LENGTH_KEY, JsonPrimitive(song.durationMs))
        },
    )

    private fun songOf(json: JsonObject): CardSong? = CardSong(
        json.text(TITLE_KEY)?.let(::shown) ?: return null,
        json.text(ARTIST_KEY)?.let(::shown),
        json.text(ALBUM_KEY)?.let(::shown),
        json.long(LENGTH_KEY)?.takeIf { it >= 0 } ?: return null,
    )

    /**
     * A friend's [text] as KJB shows it: without control characters, nor the marks that reorder what follows (embeddings,
     * overrides, isolates), so a title or a note never shows as another than the one Play and Ask use.
     */
    private fun shown(text: String): String = text.filterNot { it.isISOControl() || it in REORDERING }

    /** The sizes a message is padded to: most are one song (1 KB); a long playlist takes the largest. */
    private val SIZES: IntArray = intArrayOf(1024, 4 * 1024, 16 * 1024, 48 * 1024)

    /** The longest JSON a message holds: the largest size, less the 0x80 that ends it. */
    private val MAX_PLAIN: Int = SIZES.last() - 1
    private const val END: Byte = 0x80.toByte()
    private const val ZERO: Byte = 0
    private const val FORMAT: Int = 1
    private const val FORMAT_KEY = "v"
    private const val ID_KEY = "i"
    private const val TIME_KEY = "t"
    private const val KIND_KEY = "k"
    private const val CARD_KEY = "c"
    private const val SONG_KEY = "s"
    private const val NAME_KEY = "n"
    private const val NOTE_KEY = "m"
    private const val FAVOURITE_KEY = "f"
    private const val TAKEN_KEY = "x"
    private const val STATE_KEY = "e"
    private const val POSITION_KEY = "p"
    private const val TITLE_KEY = "t"
    private const val ARTIST_KEY = "a"
    private const val ALBUM_KEY = "l"
    private const val LENGTH_KEY = "d"
    private const val REORDERING = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"
    private const val SHARE = "share"
    private const val PLAY = "play"
    private const val BYE = "bye"
    private const val PROFILE = "profile"
    private const val NOW = "now"
}

/** [text] trimmed and at most [max] characters, never cutting a character of two halves; null when blank. */
internal fun clip(text: String?, max: Int): String? {
    val trimmed = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (trimmed.length <= max) return trimmed
    val cut = if (Character.isHighSurrogate(trimmed[max - 1])) max - 1 else max
    return trimmed.substring(0, cut).trimEnd()
}

/** How a friend's song was found in this phone's library. */
sealed interface Match {
    /** By its title, artist, album (when the card has one) and length: it plays. */
    data class Found(val song: Song) : Match

    /** Only by its title and length, on another album or by another artist: KJB asks "Is this the song?" first. */
    data class Maybe(val song: Song) : Match

    /** Not in this library, or several songs fit: never a guess. */
    data object Missing : Match
}

/**
 * [song] in this phone's library, through the import matcher ([SongMatcher.find]: title, artist, album as far as known,
 * a length within 2 s, never a guess when several fit): found with all its tags, else a maybe without the album, then
 * without the artist too (hd-research-check.md U7), else missing.
 */
fun SongMatcher.match(song: CardSong): Match {
    fun find(artist: String?, album: String?): Song? = find(ImportSong(location = song.title, path = null, title = song.title, artist = artist, album = album, durationMs = song.durationMs))
    find(song.artist, song.album)?.let { return Match.Found(it) }
    return (find(song.artist, null) ?: find(null, null))?.let { Match.Maybe(it) } ?: Match.Missing
}
