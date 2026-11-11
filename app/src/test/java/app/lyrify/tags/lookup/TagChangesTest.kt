package app.lyrify.tags.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class TagChangesTest {
    /** The recorded release (made-up names): 2 discs, "Salt Road" credited "Mira Solen feat. Glass Harbour", 2011 edition of 1998. */
    private val release = MusicBrainzJson.release(fixture("release.json"))

    private fun song(title: String?, ms: Long, track: Int? = null, disc: Int? = null, artist: String? = "Mira Solen", year: Int? = null) =
        PhoneSong("/storage/emulated/0/Music/Lanterns/${title ?: "x"}.mp3", title, artist, "Lanterns of Ulso", "Mira Solen", disc, track, ms, year)

    private fun songs(year: (Int) -> Int? = { null }) = listOf(
        song("Quiet Ember", 254_000, 1, 1, year = year(0)),
        song("Salt Road", 198_500, 2, 1, year = year(1)),
        song("Nachtfähre", 301_200, 3, 1, year = year(2)),
        song("Brennmoor", 222_000, 1, 2, year = year(3)),
        song("Vael (Reprise)", 187_000, 2, 2, year = year(4)),
    )

    private fun changes(album: PhoneAlbum) = TagChanges.of(album, AlbumMatch.match(album, release))

    @Test
    fun `every field comes from the release, the artist as one credited name`() {
        val album = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs())
        val salt = changes(album).songs.single { it.song.title == "Salt Road" }.fields

        assertEquals(
            SongFields(
                title = "Salt Road",
                artist = "Mira Solen feat. Glass Harbour",
                artists = listOf("Mira Solen", "Glass Harbour"),
                album = "Lanterns of Ulso",
                albumArtist = "Mira Solen",
                track = 2,
                trackTotal = 3,
                disc = 1,
                discTotal = 2,
                musicBrainzReleaseId = ID_A,
                musicBrainzRecordingId = "e0e0e0e0-0000-4000-8000-0000000000b2",
                year = 1998,
            ),
            salt,
        )
        assertEquals(listOf(FieldChange(Field.ARTIST, "Mira Solen", "Mira Solen feat. Glass Harbour"), FieldChange(Field.YEAR, null, "1998")), changesOf(album, "Salt Road"))
    }

    @Test
    fun `genre, comments and anything else are never written`() {
        // Coverage tools add synthetic static fields; the data is what is left.
        val fields = SongFields::class.java.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "title", "artist", "artists", "album", "albumArtist", "track", "trackTotal", "disc", "discTotal",
                "musicBrainzReleaseId", "musicBrainzRecordingId", "year",
            ),
            fields,
        )
    }

    @Test
    fun `only what changes is shown, song by song and on the album`() {
        val messy = listOf(
            song("quiet ember", 254_000, 1, 1, year = 2011),
            song("Salt Road", 198_500, 2, 1, artist = "Mira Solen feat. Glass Harbour", year = 2011),
            song("Nachtfahre", 301_200, null, null, year = 2011),
            song("Brennmoor", 222_000, 1, 2, year = 2011),
            song("Vael (Reprise)", 187_000, 2, 2, year = 2011),
        )
        val album = PhoneAlbum("lanterns of ulso", "Mira Solen", messy.map { it.copy(album = "lanterns of ulso") })
        val changes = changes(album)

        // Every song has a year of its own (2011): it keeps it, so no year is shown.
        val album2 = FieldChange(Field.ALBUM, "lanterns of ulso", "Lanterns of Ulso")
        assertEquals(listOf(FieldChange(Field.TITLE, "quiet ember", "Quiet Ember"), album2), changesOf(album, "quiet ember"))
        assertEquals(listOf(album2), changesOf(album, "Salt Road"))
        assertEquals(
            listOf(FieldChange(Field.TITLE, "Nachtfahre", "Nachtfähre"), album2, FieldChange(Field.TRACK, null, "3"), FieldChange(Field.DISC, null, "1")),
            changesOf(album, "Nachtfahre"),
        )
        assertEquals(listOf(album2), changes.album)
        assertEquals(setOf(Part.TITLE, Part.ALBUM, Part.NUMBERS), changes.parts)
        assertEquals(5, changes.yearsKept)
        assertEquals(emptyList<PhoneSong>(), changes.unpaired)
    }

    @Test
    fun `the album sheet shows album, album artist and year as old to new`() {
        val album = PhoneAlbum("Lanterns", null, songs().map { it.copy(album = "Lanterns", albumArtist = null) })
        assertEquals(
            listOf(
                FieldChange(Field.ALBUM, "Lanterns", "Lanterns of Ulso"),
                FieldChange(Field.ALBUM_ARTIST, null, "Mira Solen"),
                FieldChange(Field.YEAR, null, "1998"),
            ),
            changes(album).album,
        )
    }

    @Test
    fun `the year to write is the album's first year only where a song has none, every song with a year keeps its own`() {
        val mixed = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs { listOf(2011, null, 1998, null, 2011)[it] })
        assertEquals(listOf(null, 1998, null, 1998, null), changes(mixed).songs.map { it.fields.year })
        assertEquals(3, changes(mixed).yearsKept)
    }

    @Test
    fun `the year written is the release group's original, never the edition's`() {
        assertEquals(2011, release.year)
        val mixed = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs { if (it < 2) 2011 else null })

        assertEquals(listOf(null, null, 1998, 1998, 1998), changes(mixed).songs.map { it.fields.year })
        assertEquals(listOf(FieldChange(Field.YEAR, null, "1998")), changes(mixed).album)
    }

    @Test
    fun `no original year known writes no year`() {
        val undated = release.copy(releaseGroup = release.releaseGroup!!.copy(firstReleaseDate = null))
        val album = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs { 2011 })
        val changes = TagChanges.of(album, AlbumMatch.match(album, undated))
        assertTrue(changes.songs.all { it.fields.year == null })
        assertTrue(changes.album.none { it.field == Field.YEAR })
        assertTrue(Part.YEAR !in changes.parts)
    }

    @Test
    fun `songs that pair with no track are left as they are`() {
        val album = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs().dropLast(1) + song("Something Else", 100_000))
        val changes = changes(album)
        assertEquals(listOf("Something Else"), changes.unpaired.map { it.title })
        assertEquals(4, changes.songs.size)
    }

    @Test
    fun `a single takes its track's numbers and its release's names`() {
        val recordings = MusicBrainzJson.recordings(fixture("recording-search.json"))
        val one = PhoneSong("/storage/emulated/0/Download/kestrel.mp3", "kestrel lights", "Quillmere", null, null, null, null, 251_400)
        val compilation = AlbumMatch.proposeSingle(one, recordings).candidates.single { it.release.artist.isVarious }

        val fields = TagChanges.of(PhoneAlbum(null, null, listOf(one)), compilation).songs.single().fields

        assertEquals("Kestrel Lights", fields.title)
        assertEquals("Quillmere", fields.artist)
        assertEquals("Night Ferries, Volume 2", fields.album)
        assertEquals("Various Artists", fields.albumArtist)
        assertEquals(4, fields.track)
        assertEquals(9, fields.trackTotal)
        assertEquals(2, fields.disc)
        assertNull(fields.discTotal)
        assertEquals("e0e0e0e0-0000-4000-8000-0000000000b9", fields.musicBrainzRecordingId)
    }

    private fun changesOf(album: PhoneAlbum, title: String): List<FieldChange> = changes(album).songs.single { it.song.title == title }.changes

    // The review's rows (user feedback 2026-11-11): only what changes, Year unticked when a song has its own

    @Test
    fun `the review offers a row only for what changes, every one ticked, Year adding only to the songs with none`() {
        val dated = PhoneAlbum("Lanterns", "Mira Solen", songs { if (it == 0) 2011 else null }.map { it.copy(album = "Lanterns") })
        val changes = changes(dated)

        assertEquals(setOf(Part.ARTIST, Part.ALBUM, Part.YEAR), changes.parts)
        assertEquals(setOf(Part.ARTIST, Part.ALBUM, Part.YEAR, Part.COVER), changes.ticked(cover = true))
        assertEquals(setOf(Part.ARTIST, Part.ALBUM, Part.YEAR), changes.ticked(cover = false))
        // Year adds the album's first year to the songs with none; the song with its own keeps it.
        assertEquals(listOf(null, 1998, 1998, 1998, 1998), changes.songs.map { it.fields.year })
        assertEquals(1, changes.yearsKept)

        val undated = changes(PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs()))
        assertEquals(setOf(Part.ARTIST, Part.YEAR), undated.parts)
        assertEquals(setOf(Part.ARTIST, Part.YEAR), undated.ticked(cover = false))
        assertEquals(0, undated.yearsKept)
    }
}
