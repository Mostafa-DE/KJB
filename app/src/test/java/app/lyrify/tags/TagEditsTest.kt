package app.lyrify.tags

import app.lyrify.library.Album
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import app.lyrify.library.song
import app.lyrify.tags.lookup.ImageCodec
import app.lyrify.tags.lookup.ImageSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Edit tags, Edit album, Rename artist and Merge: the changes each one plans for the writer, what a write or an Undo
 * says, a photo made a cover, and History's Undo through the writer. Made-up names only.
 */
class TagEditsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val lantern = song(
        "Music/Harbour Glass/03 Paper Lantern.mp3",
        title = "Paper Lantern",
        artist = "Kit Calloway",
        album = "Harbour Glass",
        albumArtist = "Kit Calloway",
        track = 3,
        disc = 1,
        year = 2019,
        genre = "Folk",
        id = 41,
    )
    private val tide = song(
        "Music/Harbour Glass/05 Northbound Tide.mp3",
        title = "Northbound Tide",
        artist = "Kit Caloway",
        album = "Harbour Glass",
        albumArtist = "Kit Calloway",
        track = 5,
        disc = 1,
        year = 2019,
        genre = "Folk",
        id = 42,
    )

    // One song

    @Test
    fun `one song's form starts from its tags`() {
        val form = TagForm.of(listOf(lantern))

        assertEquals("Paper Lantern", form.value(TagField.TITLE))
        assertEquals("Kit Calloway", form.value(TagField.ARTIST))
        assertEquals("Harbour Glass", form.value(TagField.ALBUM))
        assertEquals("2019", form.value(TagField.YEAR))
        assertEquals("3", form.value(TagField.TRACK))
        assertEquals("1", form.value(TagField.DISC))
        assertEquals("Folk", form.value(TagField.GENRE))
        assertEquals(emptySet<TagField>(), form.mixed)
        assertEquals(false, form.edited)
    }

    @Test
    fun `one song's plan writes only the fields that changed, and an emptied one is removed`() {
        val form = TagForm.of(listOf(lantern))
            .with(TagField.TITLE, "  Paper Lanterns ")
            .with(TagField.YEAR, "2020")
            .with(TagField.GENRE, "")

        val plans = TagEdits.songs(listOf(lantern), form, CoverEdit.Keep)

        assertEquals(true, form.edited)
        assertEquals(listOf(mapOf(TagField.TITLE to "Paper Lanterns", TagField.YEAR to "2020", TagField.GENRE to "")), plans.map { it.fields })
        val job = checkNotNull(plans.single().job)
        assertEquals(SongTarget(lantern.path, 41), job.song)
        assertEquals(mapOf("TITLE" to listOf("Paper Lanterns"), "DATE" to listOf("2020"), "GENRE" to emptyList()), job.edit.fields)
        assertEquals(CoverEdit.Keep, job.edit.cover)
    }

    @Test
    fun `a form left as it was plans nothing, and numbers count as the numbers they are`() {
        val form = TagForm.of(listOf(lantern)).with(TagField.TRACK, "03").with(TagField.TITLE, "Paper Lantern ")

        assertEquals(emptyList<SongPlan>(), TagEdits.songs(listOf(lantern), form, CoverEdit.Keep))
        assertEquals(mapOf(TagField.TRACK to "7"), TagEdits.songs(listOf(lantern), form.with(TagField.TRACK, "007"), CoverEdit.Keep).single().fields)
    }

    @Test
    fun `a song without a MediaStore id gets no write job`() {
        val loose = song("Music/Loose/Moth Signal.mp3", title = "Moth Signal")
        val plan = TagEdits.songs(listOf(loose), TagForm.of(listOf(loose)).with(TagField.GENRE, "Folk"), CoverEdit.Keep).single()

        assertNull(plan.job)
    }

    @Test
    fun `the fields are checked before saving`() {
        val form = TagForm.of(listOf(lantern))

        assertEquals(emptyMap<TagField, FieldProblem>(), form.problems(setOf(TagField.TITLE)))
        assertEquals(emptyMap<TagField, FieldProblem>(), form.with(TagField.YEAR, "").with(TagField.TRACK, " ").problems(setOf(TagField.TITLE)))
        assertEquals(
            mapOf(TagField.TITLE to FieldProblem.NEEDS_TEXT, TagField.YEAR to FieldProblem.FOUR_DIGITS, TagField.TRACK to FieldProblem.NUMBER, TagField.DISC to FieldProblem.NUMBER),
            form.with(TagField.TITLE, "  ").with(TagField.YEAR, "19").with(TagField.TRACK, "x2").with(TagField.DISC, "1000").problems(setOf(TagField.TITLE)),
        )
        assertEquals(mapOf(TagField.YEAR to FieldProblem.FOUR_DIGITS), form.with(TagField.YEAR, "2019-05").problems(emptySet()))
        // A title is needed only where the caller says so (several songs keep their own).
        assertEquals(emptyMap<TagField, FieldProblem>(), form.with(TagField.TITLE, "").problems(emptySet()))
    }

    // Several songs

    @Test
    fun `several songs show the fields they share, the others empty and marked mixed`() {
        val form = TagForm.of(listOf(lantern, tide))

        assertEquals("Harbour Glass", form.value(TagField.ALBUM))
        assertEquals("Kit Calloway", form.value(TagField.ALBUM_ARTIST))
        assertEquals("2019", form.value(TagField.YEAR))
        assertEquals("", form.value(TagField.ARTIST))
        assertEquals("", form.value(TagField.TRACK))
        assertEquals(setOf(TagField.TITLE, TagField.ARTIST, TagField.TRACK), form.mixed)
    }

    @Test
    fun `several songs - an empty field keeps each song's own, a typed one goes to every song that differs`() {
        val form = TagForm.of(listOf(lantern, tide))
            .with(TagField.ARTIST, "Kit Calloway")
            .with(TagField.ALBUM, "")
            .with(TagField.GENRE, "Chamber Folk")

        val plans = TagEdits.songs(listOf(lantern, tide), form, CoverEdit.Keep)

        assertEquals(
            listOf(
                lantern.path to mapOf(TagField.GENRE to "Chamber Folk"),
                tide.path to mapOf(TagField.ARTIST to "Kit Calloway", TagField.GENRE to "Chamber Folk"),
            ),
            plans.map { it.song.path to it.fields },
        )
    }

    @Test
    fun `a new cover goes to every song, even one whose fields stay`() {
        val front = TagPicture(byteArrayOf(1, 2, 3), "", FRONT_COVER, "image/jpeg")

        val plans = TagEdits.songs(listOf(lantern, tide), TagForm.of(listOf(lantern, tide)), CoverEdit.Replace(front))

        assertEquals(listOf(CoverEdit.Replace(front), CoverEdit.Replace(front)), plans.map { it.cover })
        assertEquals(listOf(emptyMap<TagField, String>(), emptyMap()), plans.map { it.fields })
        assertEquals(listOf(CoverEdit.Remove), TagEdits.songs(listOf(lantern), TagForm.of(listOf(lantern)), CoverEdit.Remove).map { it.cover })
    }

    @Test
    fun `an Opus song keeps its cover when the new one would not fit its comment header`() {
        val opus = song("Music/Quiet Breakwater/01 Salt Clock.opus", title = "Salt Clock", id = 7)
        val big = TagPicture(ByteArray(300 * 1024), "", FRONT_COVER, "image/jpeg")
        val small = TagPicture(ByteArray(2 * 1024), "", FRONT_COVER, "image/jpeg")

        assertEquals(CoverEdit.Keep, TagEdits.coverFor(opus, CoverEdit.Replace(big)))
        assertEquals(CoverEdit.Replace(small), TagEdits.coverFor(opus, CoverEdit.Replace(small)))
        assertEquals(CoverEdit.Remove, TagEdits.coverFor(opus, CoverEdit.Remove))
        assertEquals(CoverEdit.Replace(big), TagEdits.coverFor(lantern, CoverEdit.Replace(big)))
        // Nothing else to change: the Opus song is left out.
        assertEquals(listOf(lantern.path), TagEdits.songs(listOf(lantern, opus), TagForm.of(listOf(lantern, opus)), CoverEdit.Replace(big)).map { it.song.path })
    }

    // Edit album

    private fun albumSongs(): List<Song> = listOf(
        song("Music/Switchyard/a.mp3", title = "Dust on the Dial", artist = "Kit Calloway", album = "Switchyard Lullabies", albumArtist = "Kit Calloway", track = 2, disc = 1, year = 2024, genre = "Folk", id = 1),
        song("Music/Switchyard/b.mp3", title = "Moths Keep Count", artist = "Kit Calloway", album = "Switchyard Lullabies", albumArtist = "Kit Calloway", track = 1, disc = 2, year = 2024, genre = "Folk", id = 2),
        song("Music/Switchyard/c.mp3", title = "Porchlight Sums", artist = "Kit Calloway", album = "Switchyard Lullabies", albumArtist = "Kit Calloway", track = 3, disc = null, year = 2023, genre = "Folk", id = 3),
    )

    @Test
    fun `Number in this order numbers the songs as the page lists them, on one disc`() {
        val songs = albumSongs()
        val form = TagForm.of(songs, TagEdits.ALBUM_FIELDS)

        val plans = TagEdits.album(songs, form, CoverEdit.Keep, numberInOrder = true)

        assertEquals(
            listOf(
                songs[0].path to mapOf(TagField.TRACK to "1"),
                songs[1].path to mapOf(TagField.TRACK to "2", TagField.DISC to "1"),
            ),
            plans.map { it.song.path to it.fields },
        )
        // Keep leaves every number as it is.
        assertEquals(emptyList<SongPlan>(), TagEdits.album(songs, form, CoverEdit.Keep, numberInOrder = false))
    }

    @Test
    fun `Edit album changes each shared field only in the songs that differ`() {
        val songs = albumSongs()
        val form = TagForm.of(songs, TagEdits.ALBUM_FIELDS)
        assertEquals(setOf(TagField.YEAR), form.mixed)

        val plans = TagEdits.album(songs, form.with(TagField.ALBUM, "Switchyard Nocturnes").with(TagField.YEAR, "2024"), CoverEdit.Keep, numberInOrder = false)

        assertEquals(
            listOf(
                songs[0].path to mapOf(TagField.ALBUM to "Switchyard Nocturnes"),
                songs[1].path to mapOf(TagField.ALBUM to "Switchyard Nocturnes"),
                songs[2].path to mapOf(TagField.ALBUM to "Switchyard Nocturnes", TagField.YEAR to "2024"),
            ),
            plans.map { it.song.path to it.fields },
        )
    }

    @Test
    fun `Edit album - a shared field emptied is removed, a mixed one left empty stays, and the album needs a name`() {
        val songs = albumSongs()
        val form = TagForm.of(songs, TagEdits.ALBUM_FIELDS).with(TagField.GENRE, "")

        assertEquals(List(3) { mapOf(TagField.GENRE to "") }, TagEdits.album(songs, form, CoverEdit.Keep, numberInOrder = false).map { it.fields })
        assertEquals(mapOf(TagField.ALBUM to FieldProblem.NEEDS_TEXT), form.with(TagField.ALBUM, " ").problems(setOf(TagField.ALBUM)))
    }

    @Test
    fun `a plan says how the library will read the song once written, so its album page can follow`() {
        val songs = albumSongs()
        val form = TagForm.of(songs, TagEdits.ALBUM_FIELDS).with(TagField.ALBUM, "Switchyard Nocturnes").with(TagField.ALBUM_ARTIST, "").with(TagField.YEAR, "2025")

        val after = TagEdits.album(songs, form, CoverEdit.Keep, numberInOrder = true).first().after

        assertEquals("Switchyard Nocturnes", after.album)
        assertNull(after.albumArtist)
        assertEquals(2025, after.year)
        assertEquals(1, after.track)
        assertEquals(albumKey(song("Music/x.mp3", artist = "Kit Calloway", album = "Switchyard Nocturnes")), albumKey(after))
    }

    // Rename and merge

    private val credits = listOf(
        song("Music/a.mp3", title = "Lamp Oil", artist = "Kit Calloway", albumArtist = "Kit Calloway", id = 11),
        song("Music/b.mp3", title = "Porch Wire", artist = "Kit Calloway feat. Mira Okafor", albumArtist = "Mira Okafor", id = 12),
        song("Music/c.mp3", title = "Tin Roof", artist = "Mira Okafor & Kit Calloway", albumArtist = null, id = 13),
        song("Music/d.mp3", title = "Lantern Fish", artist = "Kit Calloways", albumArtist = "Kit Calloways", id = 14),
        song("Music/e.mp3", title = "Low Tide", artist = "kit calloway", albumArtist = null, id = 15),
        song("Music/f.mp3", title = "Ferry Bell", artist = "Hollis Brandt", albumArtist = "Kit Calloway", id = 16),
    )

    @Test
    fun `Rename replaces the name as a whole word in artist and album artist, credits included`() {
        val plans = TagEdits.rename(credits, listOf("Kit Calloway"), "Kit Callaway")

        assertEquals(
            listOf(
                "Lamp Oil" to mapOf(TagField.ARTIST to "Kit Callaway", TagField.ALBUM_ARTIST to "Kit Callaway"),
                "Porch Wire" to mapOf(TagField.ARTIST to "Kit Callaway feat. Mira Okafor"),
                "Tin Roof" to mapOf(TagField.ARTIST to "Mira Okafor & Kit Callaway"),
                "Ferry Bell" to mapOf(TagField.ALBUM_ARTIST to "Kit Callaway"),
            ),
            plans.map { it.song.title to it.fields },
        )
    }

    @Test
    fun `Rename to the same name, or to nothing, changes nothing`() {
        assertEquals(emptyList<SongPlan>(), TagEdits.rename(credits, listOf("Kit Calloway"), "Kit Calloway"))
        assertEquals(emptyList<SongPlan>(), TagEdits.rename(credits, listOf("Kit Calloway"), "  "))
        assertEquals("Mira Okafor & Kit Calloway", TagEdits.replaceNames("Mira Okafor & Kit Calloway", listOf(""), "Kit"))
    }

    @Test
    fun `a merge keeps one spelling in one pass, so a name inside the kept one is not replaced twice`() {
        val songs = listOf(
            song("Music/a.mp3", title = "Lamp Oil", artist = "Kit Calloway", id = 1),
            song("Music/b.mp3", title = "Porch Wire", artist = "Kit Caloway feat. Mira Okafor", id = 2),
        )

        assertEquals(
            listOf("Porch Wire" to mapOf(TagField.ARTIST to "Kit Calloway feat. Mira Okafor")),
            TagEdits.rename(songs, listOf("Kit Calloway", "Kit Caloway"), "Kit Calloway").map { it.song.title to it.fields },
        )
        assertEquals(
            listOf("K. Calloway", "K. Calloway feat. Mira Okafor"),
            TagEdits.rename(songs, listOf("Kit Calloway", "Kit Caloway"), "K. Calloway").map { it.fields.getValue(TagField.ARTIST) },
        )
        assertEquals("Kit Calloway & Kit Calloway", TagEdits.replaceNames("Kit & Calloway", listOf("Kit", "Calloway"), "Kit Calloway"))
    }

    @Test
    fun `merging albums gives every song of both the album name kept`() {
        val a = listOf(
            song("Music/x/a.mp3", title = "Lamp Oil", album = "Harbour Glass", albumArtist = "Kit Calloway", id = 1),
            song("Music/x/b.mp3", title = "Porch Wire", album = "Harbour Glass", albumArtist = "Kit Calloway", id = 2),
        )
        val b = listOf(song("Music/y/c.mp3", title = "Tin Roof", album = "Harbor Glass", albumArtist = "Kit Calloway", id = 3))
        val albums = listOf(Album(checkNotNull(albumKey(a[0])), "Harbour Glass", "Kit Calloway", a, null), Album(checkNotNull(albumKey(b[0])), "Harbor Glass", "Kit Calloway", b, null))

        assertEquals(listOf("Tin Roof" to mapOf(TagField.ALBUM to "Harbour Glass")), TagEdits.mergeAlbums(albums, "Harbour Glass").map { it.song.title to it.fields })
        assertEquals(3, TagEdits.mergeAlbums(albums, "Harbour Glass (Ferry)").size)
    }

    // Find online and the photo picker

    @Test
    fun `Find online looks up the songs being edited as one album`() {
        val album = TagEdits.phoneAlbum(listOf(lantern, tide))

        assertEquals("Harbour Glass", album.title)
        assertEquals("Kit Calloway", album.albumArtist)
        assertEquals(listOf("Paper Lantern", "Northbound Tide"), album.songs.map { it.title })
        assertEquals(listOf(3, 5), album.songs.map { it.track })
        assertEquals(listOf(2019, 2019), album.songs.map { it.year })
    }

    /** Makes a baseline "JPEG" of [bytes] bytes for any image it is given, and records what it was asked. */
    private class FakeCodec(private val bytes: Int) : ImageCodec {
        val asked = mutableListOf<Int>()

        override fun size(image: ByteArray): ImageSize? = ImageSize(800, 800)

        override fun jpeg(image: ByteArray, maxSide: Int, quality: Int): ByteArray {
            asked += maxSide
            return JPEG + ByteArray(bytes)
        }
    }

    @Test
    fun `a photo is made a cover by the cover pipeline, a large or HEIC one through a JPEG first`() {
        val codec = FakeCodec(bytes = 1_000)

        val small = pickedCover(JPEG + ByteArray(50), codec)
        assertEquals(listOf(800), codec.asked)
        assertEquals(FRONT_COVER, small?.type)
        assertEquals("image/jpeg", small?.mime)

        codec.asked.clear()
        assertNotNull(pickedCover("ftypheic".encodeToByteArray() + ByteArray(50), codec))
        assertEquals(listOf(1000, 800), codec.asked)

        codec.asked.clear()
        assertNotNull(pickedCover(JPEG + ByteArray(3 * 1024 * 1024), codec))
        assertEquals(listOf(1000, 800), codec.asked)
    }

    // What a write or an Undo says

    @Test
    fun `a write's report counts what was written, in use, failed and changed since`() {
        val target = SongTarget("/storage/emulated/0/Music/a.mp3", 1)
        val result = BatchResult.Done(
            listOf(
                SongResult(target, SongOutcome.WRITTEN),
                SongResult(target, SongOutcome.UNDO_NOT_SAVED),
                SongResult(target, SongOutcome.UNCHANGED),
                SongResult(target, SongOutcome.BUSY),
                SongResult(target, SongOutcome.COULD_NOT_CHANGE),
                SongResult(target, SongOutcome.LEFT_AS_IT_WAS),
                SongResult(target, SongOutcome.CHANGED_SINCE),
                SongResult(target, SongOutcome.DIFFERENT_FILE),
            ),
            entryId = 5,
            skippedFields = 2,
        )

        assertEquals(EditReport(written = 2, busy = 1, failed = 2, changedSince = 2, skippedFields = 2), result.report())
    }

    // History

    private val rig by lazy { WriterRig(folder.root) }

    private fun libraryOf(target: SongTarget, title: String, artist: String, album: String, year: Int?): Song = Song(
        ref = SongRef(target.path, target.mediaStoreId),
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        durationMs = 200_000,
        track = null,
        disc = null,
        year = year,
        genre = null,
        folder = "Music/KJB test/Harbour Glass/",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = 0,
        mimeType = "audio/mpeg",
    )

    private fun write(plans: List<SongPlan>, kind: EditKind): Long =
        checkNotNull((runBlocking { rig.writer.write(plans.mapNotNull { it.job }, kind, "Paper Lantern") } as BatchResult.Done).entryId)

    @Test
    fun `History's Undo of an older edit skips the fields edited again since, and says so`() {
        val target = rig.song("Paper Lantern", 41, mapOf("TITLE" to listOf("paper lantern"), "ARTIST" to listOf("Kit Caloway"), "ALBUM" to listOf("Harbour Glass")))
        val before = libraryOf(target, "paper lantern", "Kit Caloway", "Harbour Glass", null)
        val songEdit = write(
            TagEdits.songs(listOf(before), TagForm.of(listOf(before)).with(TagField.TITLE, "Paper Lantern").with(TagField.ALBUM, "Harbour Glas"), CoverEdit.Keep),
            EditKind.SONG,
        )
        rig.now += 1_000
        val after = libraryOf(target, "Paper Lantern", "Kit Caloway", "Harbour Glas", null)
        write(TagEdits.album(listOf(after), TagForm.of(listOf(after), TagEdits.ALBUM_FIELDS).with(TagField.ALBUM, "Harbour Glass").with(TagField.YEAR, "2019"), CoverEdit.Keep, false), EditKind.ALBUM)

        val undone = runBlocking { rig.writer.undo(songEdit) } as BatchResult.Done

        // The title goes back; the album the later edit changed again stays, and so does its year.
        assertEquals(mapOf("TITLE" to listOf("paper lantern"), "ARTIST" to listOf("Kit Caloway"), "ALBUM" to listOf("Harbour Glass"), "DATE" to listOf("2019")), rig.fields(target))
        assertEquals(EditReport(written = 1, busy = 0, failed = 0, changedSince = 0, skippedFields = 1), undone.report())
        assertEquals(listOf(EditKind.SONG, EditKind.ALBUM), rig.undo.memory().entries.map { it.kind })
        assertTrue(rig.undo.memory().entries.first().songs.single().undone)
        assertEquals(listOf("TITLE", "ALBUM"), rig.undo.memory().entries.first().songs.single().fields.map { it.key })
    }

    @Test
    fun `a rename and a merge are each one History entry holding only the fields they changed`() {
        val one = rig.song("Lamp Oil", 51, mapOf("TITLE" to listOf("Lamp Oil"), "ARTIST" to listOf("Kit Caloway feat. Mira Okafor"), "GENRE" to listOf("Folk")))
        val two = rig.song("Porch Wire", 52, mapOf("TITLE" to listOf("Porch Wire"), "ARTIST" to listOf("Kit Caloway")))
        val songs = listOf(libraryOf(one, "Lamp Oil", "Kit Caloway feat. Mira Okafor", "", null), libraryOf(two, "Porch Wire", "Kit Caloway", "", null))
            .map { it.copy(album = null, albumArtist = null) }

        val entry = write(TagEdits.rename(songs, listOf("Kit Caloway"), "Kit Calloway"), EditKind.MERGE)

        val recorded = rig.undo.memory().entries.single { it.id == entry }
        assertEquals(EditKind.MERGE, recorded.kind)
        assertEquals(listOf(listOf("ARTIST"), listOf("ARTIST")), recorded.songs.map { song -> song.fields.map { it.key } })
        assertEquals(listOf("Kit Calloway feat. Mira Okafor"), rig.fields(one)["ARTIST"])
        assertEquals(listOf("Folk"), rig.fields(one)["GENRE"])
    }

    private companion object {
        /** A baseline JPEG's first bytes (SOF0). */
        val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 0x11, 0)
    }
}
