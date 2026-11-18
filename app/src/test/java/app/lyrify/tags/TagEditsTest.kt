package app.lyrify.tags

import app.lyrify.library.Album
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.FavouritesPort
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import app.lyrify.library.song
import app.lyrify.settings.ArtistSplit
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.tags.lookup.ImageCodec
import app.lyrify.tags.lookup.ImageSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `several songs - a mixed field left empty keeps each song's own, a typed one goes to every song that differs`() {
        val form = TagForm.of(listOf(lantern, tide))
            .with(TagField.ARTIST, "Kit Calloway")
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
    fun `several songs - a shared field emptied is removed from every song, and the preview shows it`() {
        val form = TagForm.of(listOf(lantern, tide)).with(TagField.GENRE, "").with(TagField.TRACK, "")

        val plans = TagEdits.songs(listOf(lantern, tide), form, CoverEdit.Keep)

        // The track is mixed: left empty, each song keeps its own.
        assertEquals(listOf(mapOf(TagField.GENRE to ""), mapOf(TagField.GENRE to "")), plans.map { it.fields })
        assertEquals(listOf(PreviewLine(TagField.GENRE, "Folk", "")), TagEdits.preview(plans))
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
    fun `an Opus song keeps its cover when the new one would not fit its comment header with everything else in it`() {
        val opus = song("Music/Quiet Breakwater/01 Salt Clock.ogg", title = "Salt Clock", id = 7)
        val big = TagPicture(ByteArray(300 * 1024), "", FRONT_COVER, "image/jpeg")
        val small = TagPicture(ByteArray(2 * 1024), "", FRONT_COVER, "image/jpeg")

        assertEquals(CoverEdit.Keep, TagEdits.coverFor(opus, CoverEdit.Replace(big), opusOther = 100))
        assertEquals(CoverEdit.Replace(small), TagEdits.coverFor(opus, CoverEdit.Replace(small), opusOther = 100))
        // A small cover that fits alone does not fit beside 60 KB of lyrics and other comments.
        assertEquals(CoverEdit.Keep, TagEdits.coverFor(opus, CoverEdit.Replace(small), opusOther = 60_000))
        assertEquals(CoverEdit.Remove, TagEdits.coverFor(opus, CoverEdit.Remove, opusOther = 60_000))
        // Not Opus (no comment header measured): every cover goes in.
        assertEquals(CoverEdit.Replace(big), TagEdits.coverFor(lantern, CoverEdit.Replace(big)))
        // Nothing else to change: the Opus song is left out.
        assertEquals(
            listOf(lantern.path),
            TagEdits.songs(listOf(lantern, opus), TagForm.of(listOf(lantern, opus)), CoverEdit.Replace(small), opus = mapOf(opus.path to 60_000)).map { it.song.path },
        )
    }

    /** An Opus comment header (after "OpusTags"): vendor, then [comments] in order. */
    private fun opusComments(vararg comments: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun int(value: Int) = out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        val vendor = "made-up encoder".encodeToByteArray()
        int(vendor.size)
        out.write(vendor)
        int(comments.size)
        comments.forEach { comment -> comment.encodeToByteArray().let { int(it.size); out.write(it) } }
        return out.toByteArray()
    }

    /** A FLAC picture block of [type] with [bytes] of image, as METADATA_BLOCK_PICTURE holds it in base64. */
    private fun pictureComment(type: Int, bytes: Int): String {
        val mime = "image/jpeg".encodeToByteArray()
        val block = java.nio.ByteBuffer.allocate(32 + mime.size + bytes).putInt(type).putInt(mime.size).put(mime).putInt(0).putInt(1).putInt(1).putInt(24).putInt(0).putInt(bytes)
        return "METADATA_BLOCK_PICTURE=" + java.util.Base64.getEncoder().encodeToString(block.array())
    }

    @Test
    fun `an Opus song is known by its content, and its comment header is measured without its front cover`() {
        val lyrics = "LYRICS=" + "la ".repeat(5_000)
        val back = pictureComment(type = 4, bytes = 900)
        val comments = opusComments("TITLE=Salt Clock", lyrics, pictureComment(type = 3, bytes = 20_000), back)
        val ogg = folder.newFile("Salt Clock.ogg").apply { writeBytes(TagFixtures.ogg(TagFixtures.opusPackets(comments), headers = 2)) }
        val vorbis = folder.newFile("Lamp Oil.ogg").apply { writeBytes(TagFixtures.ogg(TagFixtures.vorbisPackets(), headers = 3)) }
        val mp3 = folder.newFile("Porch Wire.opus").apply { writeBytes(TagFixtures.mp3()) }

        // "OpusTags", the vendor and count, and each comment but the front cover with its length.
        val expected = 8 + 4 + "made-up encoder".length + 4 + listOf("TITLE=Salt Clock", lyrics, back).sumOf { 4 + it.length }
        assertEquals(expected, OpusHeader.otherBytes(ogg))
        assertNull(OpusHeader.otherBytes(vorbis))
        assertNull(OpusHeader.otherBytes(mp3))
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
    fun `Number in this order's preview shows the disc it puts every song on, and the first new numbers`() {
        val songs = albumSongs()

        val preview = TagEdits.preview(TagEdits.album(songs, TagForm.of(songs, TagEdits.ALBUM_FIELDS), CoverEdit.Keep, numberInOrder = true), perSong = setOf(TagField.TRACK))

        assertEquals(
            listOf(
                PreviewLine(TagField.DISC, "2", "1"),
                PreviewLine(TagField.TRACK, "2", "1", songs[0]),
                PreviewLine(TagField.TRACK, "1", "2", songs[1]),
            ),
            preview,
        )
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
        song("Music/c.mp3", title = "Tin Roof", artist = "Mira Okafor; Kit Calloway", albumArtist = null, id = 13),
        song("Music/d.mp3", title = "Lantern Fish", artist = "Kit Calloways", albumArtist = "Kit Calloways", id = 14),
        song("Music/e.mp3", title = "Low Tide", artist = "kit calloway", albumArtist = null, id = 15),
        song("Music/f.mp3", title = "Ferry Bell", artist = "Hollis Brandt", albumArtist = "Kit Calloway", id = 16),
    )

    @Test
    fun `Rename replaces the name in artist and album artist, credits included`() {
        val plans = TagEdits.rename(credits, listOf("Kit Calloway"), "Kit Callaway")

        assertEquals(
            listOf(
                "Lamp Oil" to mapOf(TagField.ARTIST to "Kit Callaway", TagField.ALBUM_ARTIST to "Kit Callaway"),
                "Porch Wire" to mapOf(TagField.ARTIST to "Kit Callaway feat. Mira Okafor"),
                "Tin Roof" to mapOf(TagField.ARTIST to "Mira Okafor; Kit Callaway"),
                "Ferry Bell" to mapOf(TagField.ALBUM_ARTIST to "Kit Callaway"),
            ),
            plans.map { it.song.title to it.fields },
        )
    }

    @Test
    fun `a rename matches the whole artist or a whole credited name, never a word inside another name`() {
        val songs = listOf(
            song("Music/a.mp3", title = "Lamp Oil", artist = "Glass", albumArtist = "Glass", id = 1),
            song("Music/b.mp3", title = "Porch Wire", artist = "Glass Harbour", albumArtist = "Glass Harbour", id = 2),
            song("Music/c.mp3", title = "Tin Roof", artist = "Mira Okafor feat. Glass", id = 3),
            song("Music/d.mp3", title = "Low Tide", artist = "Ann Wilson featuring Glass", id = 4),
            song("Music/e.mp3", title = "Ferry Bell", artist = "Kit Calloway (with Glass)", id = 5),
            song("Music/f.mp3", title = "Moth Signal", artist = "Glass; Mira Okafor", id = 6),
        )

        assertEquals(
            listOf(
                "Lamp Oil" to mapOf(TagField.ARTIST to "Pane", TagField.ALBUM_ARTIST to "Pane"),
                "Tin Roof" to mapOf(TagField.ARTIST to "Mira Okafor feat. Pane"),
                "Low Tide" to mapOf(TagField.ARTIST to "Ann Wilson featuring Pane"),
                "Ferry Bell" to mapOf(TagField.ARTIST to "Kit Calloway (with Pane)"),
                "Moth Signal" to mapOf(TagField.ARTIST to "Pane; Mira Okafor"),
            ),
            TagEdits.rename(songs, listOf("Glass"), "Pane").map { it.song.title to it.fields },
        )
        assertEquals("Ann Wilson featuring Glass", TagEdits.replaceNames("Ann Wilson featuring Glass", listOf("Ann"), "Anna"))
        assertEquals("Glass Harbour", TagEdits.replaceNames("Glass Harbour", listOf("Glass"), "Pane"))
    }

    @Test
    fun `a band whose name holds a comma or an ampersand is one name, never split for a rename`() {
        val songs = listOf(
            song("Music/a.mp3", title = "Lamp Oil", artist = "Rowan", albumArtist = "Rowan", id = 1),
            song("Music/b.mp3", title = "Porch Wire", artist = "Rowan, the Lantern", albumArtist = "Rowan, the Lantern", id = 2),
            song("Music/c.mp3", title = "Tin Roof", artist = "Rowan & Fennick", albumArtist = "Rowan & Fennick", id = 3),
        )

        assertEquals(
            listOf("Lamp Oil" to mapOf(TagField.ARTIST to "Rowan Vale", TagField.ALBUM_ARTIST to "Rowan Vale")),
            TagEdits.rename(songs, listOf("Rowan"), "Rowan Vale").map { it.song.title to it.fields },
        )
        assertEquals("Rowan, the Lantern", TagEdits.replaceNames("Rowan, the Lantern", listOf("Rowan"), "Rowan Vale"))
        assertEquals("Rowan & Fennick", TagEdits.replaceNames("Rowan & Fennick", listOf("Fennick"), "Fen"))
        // Renaming the duo itself is a whole value.
        assertEquals("Rowan and Fennick", TagEdits.replaceNames("Rowan & Fennick", listOf("Rowan & Fennick"), "Rowan and Fennick"))
    }

    @Test
    fun `a rename's songs are the artist's own first, then those that only credit it, for Also credited on`() {
        val plans = TagEdits.rename(credits, listOf("Kit Calloway"), "Kit Callaway")

        val split = TagEdits.credits(plans, listOf("Kit Calloway"))

        // Lamp Oil is by the artist, Ferry Bell on its album; Porch Wire and Tin Roof only credit it.
        assertEquals(listOf("Lamp Oil", "Ferry Bell"), split.own.map { it.song.title })
        assertEquals(listOf("Porch Wire", "Tin Roof"), split.credited.map { it.song.title })
    }

    @Test
    fun `Rename to the same name, or to nothing, changes nothing`() {
        assertEquals(emptyList<SongPlan>(), TagEdits.rename(credits, listOf("Kit Calloway"), "Kit Calloway"))
        assertEquals(emptyList<SongPlan>(), TagEdits.rename(credits, listOf("Kit Calloway"), "  "))
        assertEquals("Mira Okafor; Kit Calloway", TagEdits.replaceNames("Mira Okafor; Kit Calloway", listOf(""), "Kit"))
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
        assertEquals("Kit Calloway; Kit Calloway", TagEdits.replaceNames("Kit; Calloway", listOf("Kit", "Calloway"), "Kit Calloway"))
    }

    @Test
    fun `a merge's preview shows every field each song changes`() {
        val plan = TagEdits.rename(listOf(song("Music/b.mp3", title = "Porch Wire", artist = "Kit Caloway feat. Mira Okafor", albumArtist = "Kit Caloway", id = 2)), listOf("Kit Caloway"), "Kit Calloway").single()

        assertEquals(
            listOf(
                PreviewLine(TagField.ARTIST, "Kit Caloway feat. Mira Okafor", "Kit Calloway feat. Mira Okafor", plan.song),
                PreviewLine(TagField.ALBUM_ARTIST, "Kit Caloway", "Kit Calloway", plan.song),
            ),
            plan.changes,
        )
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
    fun `a photo is made a cover by the cover pipeline, a large or HEIC one through a JPEG first`() = runBlocking {
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

    @Test
    fun `a large or HEIC photo steps its quality and size down until the cover pipeline takes it`() = runBlocking {
        // Too large for the pipeline at quality 100 and 90; a 1000 px JPEG at 80 fits.
        val codec = object : ImageCodec {
            val asked = mutableListOf<Pair<Int, Int>>()

            override fun size(image: ByteArray): ImageSize = ImageSize(1000, 1000)

            override fun jpeg(image: ByteArray, maxSide: Int, quality: Int): ByteArray {
                asked += maxSide to quality
                return JPEG + ByteArray(if (quality > 80) 3 * 1024 * 1024 else 200 * 1024)
            }
        }

        assertNotNull(pickedCover("ftypheic".encodeToByteArray() + ByteArray(50), codec))
        assertEquals(listOf(1000 to 100, 1000 to 90, 1000 to 80), codec.asked.take(3))
    }

    // What a write or an Undo says

    @Test
    fun `a page follows a renamed album or artist only when a song was written`() {
        val target = SongTarget("/storage/emulated/0/Music/a.mp3", 1)

        assertFalse(BatchResult.SessionsUnknown.wroteAny())
        assertFalse(BatchResult.NotEnoughSpace.wroteAny())
        assertFalse(BatchResult.Done(listOf(SongResult(target, SongOutcome.BUSY), SongResult(target, SongOutcome.UNCHANGED)), null).wroteAny())
        assertTrue(BatchResult.Done(listOf(SongResult(target, SongOutcome.BUSY), SongResult(target, SongOutcome.WRITTEN)), 4).wroteAny())
    }

    @Test
    fun `History lists every edit newest first, undone once all its songs are`() {
        fun edit(path: String, undone: Boolean) = SongEdit(1, path, Payload(AudioFormat.MP3, "aa"), "before", "after", emptyList(), undone = undone)
        val memory = UndoMemory(
            listOf(
                HistoryEntry(1, EditKind.SONG, "Tags · Lamp Oil", 100, listOf(edit("/a.mp3", undone = true))),
                HistoryEntry(2, EditKind.MERGE, "Merged Kit Calloway and Kit Caloway", 300, listOf(edit("/a.mp3", undone = true), edit("/b.mp3", undone = false))),
                HistoryEntry(3, EditKind.COVERS_TAGS, "Harbour Glass", 200, listOf(edit("/c.mp3", undone = false))),
            ),
        )

        assertEquals(
            listOf(
                HistoryRow(2, EditKind.MERGE, "Merged Kit Calloway and Kit Caloway", 300, songs = 2, undone = false),
                HistoryRow(3, EditKind.COVERS_TAGS, "Harbour Glass", 200, songs = 1, undone = false),
                HistoryRow(1, EditKind.SONG, "Tags · Lamp Oil", 100, songs = 1, undone = true),
            ),
            historyRows(memory),
        )
    }

    @Test
    fun `a paused write in Edit tags says paused, never not changed`() {
        val first = SongTarget("/storage/emulated/0/Music/a.mp3", 1)
        val second = SongTarget("/storage/emulated/0/Music/b.mp3", 2)
        fun words(vararg outcomes: Pair<SongTarget, SongOutcome>) = BatchResult.Done(outcomes.map { SongResult(it.first, it.second) }, entryId = null).report().writeWords()

        assertEquals(EditWords.PAUSED, words(first to SongOutcome.PAUSED))
        // Any song paused: the toast says so, whatever the others did.
        assertEquals(EditWords.PAUSED, words(first to SongOutcome.WRITTEN, second to SongOutcome.PAUSED))
        assertEquals(EditWords.PAUSED, words(first to SongOutcome.COULD_NOT_CHANGE, second to SongOutcome.PAUSED))
        assertEquals(EditWords.NOT_CHANGED, words(first to SongOutcome.COULD_NOT_CHANGE))
        assertEquals(EditWords.SAVED, words(first to SongOutcome.WRITTEN))
    }

    @Test
    fun `every outcome but unchanged counts in a write's report, so a song set aside is never nothing changed`() {
        val target = SongTarget("/storage/emulated/0/Music/a.mp3", 1)
        for (outcome in SongOutcome.entries - SongOutcome.UNCHANGED) {
            val report = BatchResult.Done(listOf(SongResult(target, outcome)), entryId = null).report()
            assertEquals(outcome.name, 1, report.written + report.busy + report.failed + report.changedSince + report.paused)
        }
        val asideAlone = BatchResult.Done(listOf(SongResult(target, SongOutcome.SET_ASIDE)), entryId = null).report()
        assertEquals(EditReport(written = 0, busy = 0, failed = 1, changedSince = 0, skippedFields = 0), asideAlone)
    }

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

    // Favourites follow their albums and artists

    private val harbourSongs = listOf(
        song("Music/h/1.mp3", title = "Lamp Oil", artist = "Kit Calloway", album = "Harbour Glass", albumArtist = "Kit Calloway", id = 1),
        song("Music/h/2.mp3", title = "Porch Wire", artist = "Kit Calloway feat. Mira Okafor", album = "Harbour Glass", albumArtist = "Kit Calloway", id = 2),
        song("Music/p/3.mp3", title = "Tin Roof", artist = "Mira Okafor", album = "Porch Lights", albumArtist = "Mira Okafor", id = 3),
        song("Music/k/4.mp3", title = "Low Tide", artist = "Kit Caloway", album = "Ferry Bell", albumArtist = "Kit Caloway", id = 4),
    )

    /** Artists with several names on, as it is by default: Porch Wire is on Kit Calloway's page and on Mira Okafor's. */
    private val harbour = Library.of(harbourSongs, Settings())

    /** The same songs with Artists with several names off: Porch Wire's artist is one name. */
    private val harbourWhole = Library.of(harbourSongs, Settings(library = LibrarySettings(artistSplit = ArtistSplit(enabled = false))))

    private fun artist(name: String) = FavouriteCollection(CollectionKind.ARTIST, name)

    private fun album(artist: String, title: String) = FavouriteCollection(CollectionKind.ALBUM, checkNotNull(albumKey(song("Music/x.mp3", artist = artist, album = title))))

    private fun moved(plans: List<SongPlan>, favourites: List<FavouriteCollection>, written: Set<String> = plans.map { it.song.path }.toSet(), library: Library = harbour) =
        FavouriteMoves.of(plans.filter { it.song.path in written }.map { it.song to it.after }, library, favourites).map { it.from to it.to }

    @Test
    fun `a renamed artist keeps its heart under the new name, as do its credits and its albums`() {
        // "Kit Calloway feat. Mira Okafor" is no artist of its own while names are split (TA1, 2026-11-18): its old heart stays put.
        val favourites = listOf(artist("Kit Calloway"), artist("Kit Calloway feat. Mira Okafor"), artist("Mira Okafor"), album("Kit Calloway", "Harbour Glass"), album("Mira Okafor", "Porch Lights"))

        val moves = moved(TagEdits.rename(harbour.songs, listOf("Kit Calloway"), "Kit Callaway"), favourites)

        assertEquals(listOf(artist("Kit Calloway") to artist("Kit Callaway"), album("Kit Calloway", "Harbour Glass") to album("Kit Callaway", "Harbour Glass")), moves)
    }

    @Test
    fun `with Artists with several names off, a renamed artist's credits keep their hearts under the new whole name`() {
        val favourites = listOf(artist("Kit Calloway"), artist("Kit Calloway feat. Mira Okafor"), artist("Mira Okafor"), album("Kit Calloway", "Harbour Glass"), album("Mira Okafor", "Porch Lights"))

        val moves = moved(TagEdits.rename(harbourWhole.songs, listOf("Kit Calloway"), "Kit Callaway"), favourites, library = harbourWhole)

        assertEquals(
            listOf(
                artist("Kit Calloway") to artist("Kit Callaway"),
                artist("Kit Calloway feat. Mira Okafor") to artist("Kit Callaway feat. Mira Okafor"),
                album("Kit Calloway", "Harbour Glass") to album("Kit Callaway", "Harbour Glass"),
            ),
            moves,
        )
    }

    @Test
    fun `a favourite moves only when every one of its songs was written`() {
        val plans = TagEdits.rename(harbour.songs, listOf("Kit Calloway"), "Kit Callaway")

        val moves = moved(plans, listOf(artist("Kit Calloway"), album("Kit Calloway", "Harbour Glass")), written = setOf(harbour.songs[0].path))

        // Porch Wire, on Kit Calloway's page (it credits them) and on the album, was in use: both are split for now, so
        // their hearts stay (TA1: before the split, the artist's page held Lamp Oil alone and its heart moved).
        assertEquals(emptyList<Any>(), moves)
    }

    @Test
    fun `an artist renamed in a tag of several names takes its heart along, and one still credited keeps it`() {
        val plans = TagEdits.rename(harbour.songs, listOf("Mira Okafor"), "Mira Okafor-Lind")

        val moves = moved(plans, listOf(artist("Mira Okafor"), artist("Kit Calloway")))

        // Porch Wire ("Kit Calloway feat. Mira Okafor-Lind") and Tin Roof both name the new spelling; Kit Calloway's
        // Lamp Oil was not written, and Porch Wire still credits them.
        assertEquals(listOf(artist("Mira Okafor") to artist("Mira Okafor-Lind")), moves)
    }

    @Test
    fun `adding a guest to an artist's only song keeps the artist's heart where it is`() {
        val low = harbour.songs.single { it.title == "Low Tide" }
        val plans = TagEdits.songs(listOf(low), TagForm.of(listOf(low)).with(TagField.ARTIST, "Kit Caloway feat. Oren Vale"), CoverEdit.Keep)

        assertEquals(emptyList<Any>(), moved(plans, listOf(artist("Kit Caloway"))))
    }

    @Test
    fun `a merge moves the heart of the name given up, and the kept one stays`() {
        val plans = TagEdits.rename(harbour.songs, listOf("Kit Calloway", "Kit Caloway"), "Kit Calloway")

        val moves = moved(plans, listOf(artist("Kit Calloway"), artist("Kit Caloway"), album("Kit Caloway", "Ferry Bell")))

        assertEquals(listOf(artist("Kit Caloway") to artist("Kit Calloway"), album("Kit Caloway", "Ferry Bell") to album("Kit Calloway", "Ferry Bell")), moves)
    }

    @Test
    fun `Edit album moves the album's heart to its new name, numbering alone moves nothing`() {
        val songs = checkNotNull(harbour.album(album("Kit Calloway", "Harbour Glass").key)).songs
        val form = TagForm.of(songs, TagEdits.ALBUM_FIELDS)
        val favourites = listOf(album("Kit Calloway", "Harbour Glass"))

        assertEquals(
            listOf(album("Kit Calloway", "Harbour Glass") to album("Kit Calloway", "Harbour Glass Nocturnes")),
            moved(TagEdits.album(songs, form.with(TagField.ALBUM, "Harbour Glass Nocturnes"), CoverEdit.Keep, numberInOrder = false), favourites),
        )
        assertEquals(emptyList<Any>(), moved(TagEdits.album(songs, form, CoverEdit.Keep, numberInOrder = true), favourites))
    }

    @Test
    fun `undoing the edit moves the heart back, unless a field changed again since`() {
        val renamed = Library.of(
            listOf(
                song("Music/h/1.mp3", title = "Lamp Oil", artist = "Kit Callaway", album = "Harbour Glass", albumArtist = "Kit Callaway", id = 1),
                song("Music/h/2.mp3", title = "Porch Wire", artist = "Kit Callaway", album = "Harbour Glass", albumArtist = "Kit Callaway", id = 2),
            ),
            Settings(),
        )
        fun edit(path: String, vararg fields: FieldChange) =
            SongEdit(1, path, Payload(AudioFormat.MP3, "aa"), "before", "after", fields.toList())
        val entry = HistoryEntry(
            7,
            EditKind.ARTIST,
            "Renamed",
            0,
            listOf(
                edit(renamed.songs[0].path, FieldChange("ARTIST", listOf("Kit Calloway"), listOf("Kit Callaway")), FieldChange("ALBUMARTIST", listOf("Kit Calloway"), listOf("Kit Callaway"))),
                edit(renamed.songs[1].path, FieldChange("ARTIST", listOf("Kit Calloway"), listOf("Kit Callaway")), FieldChange("ALBUMARTIST", listOf("Kit Calloway"), listOf("Kit Calloway Trio"))),
            ),
        )
        val favourites = listOf(artist("Kit Callaway"), album("Kit Callaway", "Harbour Glass"))

        val back = FavouriteMoves.of(FavouriteMoves.undone(entry, renamed.songs.map { it.path }.toSet(), renamed), renamed, favourites)

        // Porch Wire's album artist is "Kit Callaway" now, not the edit's "Kit Calloway Trio": it stays, so the album
        // splits and keeps its heart; the artist's songs all go back.
        assertEquals(listOf(artist("Kit Callaway") to artist("Kit Calloway")), back.map { it.from to it.to })
        assertEquals(emptyList<Pair<Song, Song>>(), FavouriteMoves.undone(entry, emptySet(), renamed))
    }

    @Test
    fun `a heart moves on at the new key and off at the old one`() {
        val favourites = FakeFavourites(listOf(artist("Mira Okafor"), artist("Kit Caloway"), artist("Kit Calloway")))

        val stored = runBlocking { FavouriteMoves.apply(listOf(FavouriteMove(artist("Kit Caloway"), artist("Kit Calloway"))), favourites) }

        assertTrue(stored)
        assertEquals(listOf(artist("Kit Calloway"), artist("Mira Okafor")), favourites.now.value)
    }

    private companion object {
        /** A baseline JPEG's first bytes (SOF0). */
        val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 0x11, 0)
    }
}

/** Favourites in memory, the latest first. */
class FakeFavourites(start: List<FavouriteCollection>) : FavouritesPort {
    val now = MutableStateFlow(start)
    override val songs: Flow<List<SongRef>> = flowOf(emptyList())
    override val collections: Flow<List<FavouriteCollection>> = now

    override suspend fun setSongs(songs: List<SongRef>, favourite: Boolean): Boolean = true

    override suspend fun setCollection(collection: FavouriteCollection, favourite: Boolean): Boolean {
        now.value = if (favourite) listOf(collection) + (now.value - collection) else now.value - collection
        return true
    }
}
