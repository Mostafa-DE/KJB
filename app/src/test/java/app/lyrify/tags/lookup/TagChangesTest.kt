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

    private fun changes(album: PhoneAlbum, keepMyYear: Boolean = true) = TagChanges.of(album, AlbumMatch.match(album, release), keepMyYear)

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

        assertEquals(listOf(FieldChange(Field.TITLE, "quiet ember", "Quiet Ember")), changesOf(album, "quiet ember"))
        assertEquals(emptyList<FieldChange>(), changesOf(album, "Salt Road"))
        assertEquals(
            listOf(FieldChange(Field.TITLE, "Nachtfahre", "Nachtfähre"), FieldChange(Field.TRACK, null, "3"), FieldChange(Field.DISC, null, "1")),
            changesOf(album, "Nachtfahre"),
        )
        assertEquals(listOf(FieldChange(Field.ALBUM, "lanterns of ulso", "Lanterns of Ulso")), changes.album)
        assertEquals(1 + 1 + 3, changes.count)
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
    fun `the year rule keeps a year, fills an empty one, and the switch writes the original everywhere`() {
        assertNull(TagChanges.yearToWrite(songYear = 2011, originalYear = 1998, keepMyYear = true))
        assertEquals(1998, TagChanges.yearToWrite(songYear = null, originalYear = 1998, keepMyYear = true))
        assertEquals(1998, TagChanges.yearToWrite(songYear = 2011, originalYear = 1998, keepMyYear = false))
        assertEquals(1998, TagChanges.yearToWrite(songYear = null, originalYear = 1998, keepMyYear = false))
        assertNull(TagChanges.yearToWrite(songYear = 1998, originalYear = 1998, keepMyYear = false))
        assertNull(TagChanges.yearToWrite(songYear = null, originalYear = null, keepMyYear = true))
        assertNull(TagChanges.yearToWrite(songYear = 2011, originalYear = null, keepMyYear = false))
    }

    @Test
    fun `the year written is the release group's original, never the edition's`() {
        assertEquals(2011, release.year)
        val mixed = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs { if (it < 2) 2011 else null })

        val kept = changes(mixed).songs.map { it.fields.year }
        assertEquals(listOf(null, null, 1998, 1998, 1998), kept)
        assertEquals(listOf(FieldChange(Field.YEAR, null, "1998")), changes(mixed).album)

        val all = changes(mixed, keepMyYear = false)
        assertEquals(listOf(1998, 1998, 1998, 1998, 1998), all.songs.map { it.fields.year })
        assertEquals(listOf(FieldChange(Field.YEAR, "2011", "1998")), all.album)
    }

    @Test
    fun `no original year known writes no year`() {
        val undated = release.copy(releaseGroup = release.releaseGroup!!.copy(firstReleaseDate = null))
        val album = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs())
        val changes = TagChanges.of(album, AlbumMatch.match(album, undated), keepMyYear = false)
        assertTrue(changes.songs.all { it.fields.year == null })
        assertTrue(changes.album.none { it.field == Field.YEAR })
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
}
