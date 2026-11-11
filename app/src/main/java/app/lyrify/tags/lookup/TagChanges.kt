package app.lyrify.tags.lookup

/** The rows of the review's "What to change", each with its checkbox: the cover, then the fields; NUMBERS is track and disc. */
enum class Part { COVER, TITLE, ARTIST, ALBUM, ALBUM_ARTIST, YEAR, NUMBERS }

/**
 * The fields Covers & tags shows as "old → new", each under its [part]'s checkbox. The MusicBrainz ids and the ARTISTS
 * side field are written with their part (see [app.lyrify.tags.coversTagsEdit]), not shown.
 */
enum class Field(val part: Part) {
    TITLE(Part.TITLE),
    ARTIST(Part.ARTIST),
    ALBUM(Part.ALBUM),
    ALBUM_ARTIST(Part.ALBUM_ARTIST),
    TRACK(Part.NUMBERS),
    DISC(Part.NUMBERS),
    YEAR(Part.YEAR),
}

data class FieldChange(val field: Field, val old: String?, val new: String)

/** Everything Covers & tags can write into one song. Never genre, comments, lyrics, ReplayGain or anything else. */
data class SongFields(
    val title: String,
    /** ONE credited name, "Mira Solen feat. Glass Harbour", never several values. */
    val artist: String,
    /** The credited artists one by one: only for the side field (TXXX:ARTISTS, or ARTISTS in Vorbis comments). */
    val artists: List<String>,
    val album: String,
    val albumArtist: String,
    val track: Int,
    val trackTotal: Int?,
    val disc: Int,
    val discTotal: Int?,
    val musicBrainzReleaseId: String,
    val musicBrainzRecordingId: String,
    /** The album's ORIGINAL year where it is not the song's own (written only when Year is ticked); null: nothing to write. */
    val year: Int?,
)

/** One song's new fields, and the shown fields that change (old → new). */
data class SongChange(val song: PhoneSong, val fields: SongFields, val changes: List<FieldChange>)

/**
 * What a release could change in an album's songs: a change per paired song; [unpaired] songs stay as they are; [album]
 * holds the album-wide "old → new" lines (album, album artist, year), only those that change.
 */
data class AlbumChanges(val songs: List<SongChange>, val unpaired: List<PhoneSong>, val album: List<FieldChange>) {
    /** The rows of "What to change" with a new value for at least one song (the cover's row is the cover's own). */
    val parts: Set<Part> get() = songs.flatMap { song -> song.changes.map { it.field.part } }.toSet()

    /**
     * The rows ticked when the review opens: every one that changes, and the cover when one was found ([cover]), except
     * Year when a song already has a year of its own (it replaces "Keep my year").
     */
    fun ticked(cover: Boolean): Set<Part> =
        parts - setOfNotNull(Part.YEAR.takeIf { songs.any { it.song.year != null } }) + setOfNotNull(Part.COVER.takeIf { cover })
}

/** What a release could write into an album's songs (covers-tags-spec.md, "What is written"). Pure. */
object TagChanges {
    /** The changes [match] could make to [album]'s songs: every paired song gets its track's fields; the rest stay. */
    fun of(album: PhoneAlbum, match: ReleaseMatch): AlbumChanges {
        val release = match.release
        val original = release.releaseGroup?.originalYear
        // A recording search lists one disc of the release only: its number of discs is unknown then.
        val discTotal = if (release.hasAllTracks) release.media.size else null
        val songs = match.pairing.pairs.map { pair ->
            val track = pair.track.track
            val fields = SongFields(
                title = track.title,
                artist = track.artist.text,
                artists = track.artist.names,
                album = release.title,
                albumArtist = release.artist.text,
                track = track.position,
                trackTotal = pair.track.discTracks.takeIf { it > 0 },
                disc = pair.track.disc,
                discTotal = discTotal,
                musicBrainzReleaseId = release.id,
                musicBrainzRecordingId = track.recordingId,
                year = original?.takeIf { it != pair.song.year },
            )
            SongChange(pair.song, fields, shown(pair.song, fields))
        }
        val dated = songs.filter { it.fields.year != null }
        val albumLines = listOfNotNull(
            change(Field.ALBUM, album.title, release.title),
            change(Field.ALBUM_ARTIST, album.albumArtist, release.artist.text),
            dated.firstOrNull()?.let { FieldChange(Field.YEAR, mostCommon(dated.mapNotNull { it.song.year })?.toString(), it.fields.year.toString()) },
        )
        return AlbumChanges(songs, match.pairing.unpairedSongs, albumLines)
    }

    /** The song's own "old → new" lines: every shown field that changes. */
    private fun shown(song: PhoneSong, fields: SongFields): List<FieldChange> = listOfNotNull(
        change(Field.TITLE, song.title, fields.title),
        change(Field.ARTIST, song.artist, fields.artist),
        change(Field.ALBUM, song.album, fields.album),
        change(Field.ALBUM_ARTIST, song.albumArtist, fields.albumArtist),
        change(Field.TRACK, song.track?.toString(), fields.track.toString()),
        change(Field.DISC, song.disc?.toString(), fields.disc.toString()),
        fields.year?.let { change(Field.YEAR, song.year?.toString(), it.toString()) },
    )

    /** A change only to a new value: none when it is the same, or when there is none (nothing is ever taken away). */
    private fun change(field: Field, old: String?, new: String): FieldChange? = if (old == new || new.isBlank()) null else FieldChange(field, old, new)
}
