package app.lyrify.tags.lookup

/** The fields Covers & tags shows as "old → new". The MusicBrainz ids and the ARTISTS side field are written, not shown. */
enum class Field { TITLE, ARTIST, ALBUM, ALBUM_ARTIST, TRACK, DISC, YEAR }

data class FieldChange(val field: Field, val old: String?, val new: String)

/** Everything Covers & tags writes into one song. Never genre, comments, lyrics, ReplayGain or anything else. */
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
    /** The year to write; null leaves the song's own year (or none) as it is. */
    val year: Int?,
)

/** One song's new fields, and the shown fields that change (old → new). */
data class SongChange(val song: PhoneSong, val fields: SongFields, val changes: List<FieldChange>)

/**
 * What applying a release does to an album: a change per paired song; [unpaired] songs stay as they are; [album] holds
 * the album sheet's "old → new" lines (album, album artist, year), only those that change.
 */
data class AlbumChanges(val songs: List<SongChange>, val unpaired: List<PhoneSong>, val album: List<FieldChange>) {
    /** "6 changes": the album lines plus each song's title, artist, track and disc changes. */
    val count: Int get() = album.size + songs.sumOf { song -> song.changes.count { it.field != Field.YEAR } }
}

/** The fields a release writes into an album's songs (covers-tags-spec.md, "What is written"). Pure. */
object TagChanges {
    /**
     * The year rule. With "Keep my year" on (the default), a song's own year is kept and only an empty one gets the
     * album's ORIGINAL year (the release group's first release). Off, every song gets the original year. Null: write
     * nothing (kept, already equal, or no original year known).
     */
    fun yearToWrite(songYear: Int?, originalYear: Int?, keepMyYear: Boolean): Int? = when {
        originalYear == null || songYear == originalYear -> null
        keepMyYear && songYear != null -> null
        else -> originalYear
    }

    /** The changes [match] makes to [album]'s songs: every paired song gets its track's fields; the rest stay. */
    fun of(album: PhoneAlbum, match: ReleaseMatch, keepMyYear: Boolean = true): AlbumChanges {
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
                year = yearToWrite(pair.song.year, original, keepMyYear),
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

    /** The song's row in the album sheet: title, artist, numbers and year, only those that change. */
    private fun shown(song: PhoneSong, fields: SongFields): List<FieldChange> = listOfNotNull(
        change(Field.TITLE, song.title, fields.title),
        change(Field.ARTIST, song.artist, fields.artist),
        change(Field.TRACK, song.track?.toString(), fields.track.toString()),
        change(Field.DISC, song.disc?.toString(), fields.disc.toString()),
        fields.year?.let { change(Field.YEAR, song.year?.toString(), it.toString()) },
    )

    private fun change(field: Field, old: String?, new: String): FieldChange? = if (old == new) null else FieldChange(field, old, new)
}
