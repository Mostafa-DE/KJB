package app.lyrify.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNamesTest {
    private fun first(artist: String?, title: String, album: String? = null): TrackQuery =
        TrackNames.queries(artist, title, album).first()

    // Titles

    @Test
    fun `a bitrate suffix is removed`() {
        assertEquals("Song of the Kettle", TrackNames.cleanTitle("Song of the Kettle (MP3_160K)"))
        assertEquals("Song of the Kettle", TrackNames.cleanTitle("Song of the Kettle (MP3_320K)"))
        assertEquals("Song of the Kettle", TrackNames.cleanTitle("Song of the Kettle [128kbps]"))
        assertEquals("Song of the Kettle", TrackNames.cleanTitle("Song of the Kettle(MP3_160K)"))
    }

    @Test
    fun `a track number prefix is removed`() {
        assertEquals("Title", TrackNames.cleanTitle("04. Title"))
        assertEquals("Title", TrackNames.cleanTitle("4. Title"))
        assertEquals("Title", TrackNames.cleanTitle("04 - Title"))
        assertEquals("Title", TrackNames.cleanTitle("12_Title"))
        assertEquals("Title", TrackNames.cleanTitle("7) Title"))
    }

    @Test
    fun `numbers that belong to the title stay`() {
        assertEquals("21 Kettles", TrackNames.cleanTitle("21 Kettles"))
        assertEquals("99 Laternen", TrackNames.cleanTitle("99 Laternen"))
        assertEquals("2031", TrackNames.cleanTitle("2031"))
        assertEquals("2031", TrackNames.cleanTitle("01 - 2031"))
        assertEquals("2 Minutes to Moonrise", TrackNames.cleanTitle("2 Minutes to Moonrise"))
    }

    @Test
    fun `a track number with a hyphen right after it is removed`() {
        assertEquals("Song Title", TrackNames.cleanTitle("05- Song Title"))
        assertEquals("Song Title", TrackNames.cleanTitle("5- Song Title"))
        assertEquals("Song Title", TrackNames.cleanTitle("05-Song Title"))
        assertEquals("Word-Word-Word", TrackNames.cleanTitle("05-Word-Word-Word"))
        assertEquals("9 Paper Lanterns", TrackNames.cleanTitle("03- 9 Paper Lanterns"))
    }

    @Test
    fun `numbers joined by hyphens or followed by a space are part of the title`() {
        assertEquals("9 Paper Lanterns", TrackNames.cleanTitle("9 Paper Lanterns"))
        assertEquals("1-555-010-0199", TrackNames.cleanTitle("1-555-010-0199"))
        assertEquals("3-5-7-9 Avenue", TrackNames.cleanTitle("3-5-7-9 Avenue"))
        assertEquals("1234-Song", TrackNames.cleanTitle("1234-Song"))
    }

    @Test
    fun `a title tag keeps a number that may be part of its name`() {
        // Same shapes as "05 Minutes", "007 Kettles", "10 Tides Gone", "2-Step", "3-D", "4-Ever": only a file name loses them.
        listOf("05 Lanterns", "007 Lanterns", "2031", "99 Paper Boats", "10 Paper Days", "2-Lantern", "3-Q", "4-Always", "5-Song Title").forEach {
            assertEquals(it, TrackNames.cleanTitle(it))
        }
    }

    @Test
    fun `junk suffixes are removed`() {
        assertEquals("Vael", TrackNames.cleanTitle("Vael (Official Video)"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael (Official Music Video) [HD]"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael (Lyrics)"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael [HQ]"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael (Lyric Video)"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael (Official Audio)"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael - Official Video"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael | Lyrics"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael (4K)"))
        assertEquals("Vael", TrackNames.cleanTitle("Vael (www.mp3-site.com)"))
    }

    @Test
    fun `version tags are kept in the first variant`() {
        assertEquals("Vael (Live)", TrackNames.cleanTitle("Vael (Live)"))
        assertEquals("Vael (Remastered 2011)", TrackNames.cleanTitle("Vael (Remastered 2011)"))
    }

    @Test
    fun `unbalanced parentheses are closed and stray closers dropped`() {
        assertEquals(
            "World Of Lanterns (Demo) (Bonus Track)",
            TrackNames.cleanTitle("World Of Lanterns (Demo) (Bonus Track"),
        )
        assertEquals("Title", TrackNames.cleanTitle("Title)"))
        assertEquals("World Of Lanterns", TrackNames.withoutVersionTags("World Of Lanterns (Demo) (Bonus Track"))
    }

    @Test
    fun `a leading bracket tag is removed`() {
        assertEquals("K Hymn - Holm", TrackNames.cleanTitle("[Velvo Voice] K Hymn - Holm"))
        assertEquals("Song", TrackNames.cleanTitle("【MV】 Song"))
    }

    @Test
    fun `a leading parenthesis that is part of the title stays`() {
        assertEquals("(I Can't Find My) Lantern", TrackNames.cleanTitle("(I Can't Find My) Lantern"))
    }

    @Test
    fun `stray quotes are removed`() {
        assertEquals("I Lanterni", TrackNames.cleanTitle("\"I Lanterni\""))
        assertEquals("Harbours", TrackNames.cleanTitle("“Harbours”"))
        assertEquals("Don't Wake Me Now", TrackNames.cleanTitle("Don't Wake Me Now"))
        assertEquals("Song", TrackNames.cleanTitle("'Song'"))
        assertEquals("Song", TrackNames.cleanTitle("Song\""))
    }

    @Test
    fun `uploader handles are removed`() {
        assertEquals("Song", TrackNames.cleanTitle("Song @MetalChannel"))
        assertEquals("Song", TrackNames.cleanTitle("@MetalChannel - Song"))
        assertEquals("Song", TrackNames.cleanTitle("Song | @music_bot"))
    }

    @Test
    fun `whitespace and dangling separators are tidied`() {
        assertEquals("Song Name", TrackNames.cleanTitle("  Song   Name  - "))
        assertEquals("Song", TrackNames.cleanTitle("\uFEFFSong"))
    }

    @Test
    fun `a title that is only junk keeps its text`() {
        assertEquals("(Official Video)", TrackNames.cleanTitle("(Official Video)"))
    }

    @Test
    fun `version tags are removed for the second variant`() {
        assertEquals("Vael", TrackNames.withoutVersionTags("Vael (Live)"))
        assertEquals("Vael", TrackNames.withoutVersionTags("Vael [Remastered]"))
        assertEquals("Vael", TrackNames.withoutVersionTags("Vael - 2011 Remaster"))
        assertEquals("Vael", TrackNames.withoutVersionTags("Vael - Live at the Pier"))
        assertEquals("Vael", TrackNames.withoutVersionTags("Vael - Radio Edit"))
        assertEquals("Vael - Part II", TrackNames.withoutVersionTags("Vael - Part II"))
    }

    @Test
    fun `instrumental tags are never removed`() {
        assertEquals("Vael (Instrumental)", TrackNames.withoutVersionTags("Vael (Instrumental)"))
        assertEquals("Vael - Karaoke Version", TrackNames.withoutVersionTags("Vael - Karaoke Version"))
    }

    @Test
    fun `instrumental and karaoke titles are recognised`() {
        assertTrue(TrackNames.isInstrumental("Vael (Instrumental)"))
        assertTrue(TrackNames.isInstrumental("Vael - Instrumental Version"))
        assertTrue(TrackNames.isInstrumental("Vael [Karaoke]"))
        assertFalse(TrackNames.isInstrumental("Vael"))
        assertFalse(TrackNames.isInstrumental("Vael (Live)"))
    }

    @Test
    fun `featured artists are removed`() {
        assertEquals("Song", TrackNames.withoutFeat("Song (feat. Someone)"))
        assertEquals("Song", TrackNames.withoutFeat("Song [ft. Someone]"))
        assertEquals("Song", TrackNames.withoutFeat("Song feat. Someone"))
        assertEquals("Song", TrackNames.withoutFeat("Song Ft. Someone & Other"))
        assertEquals("Song", TrackNames.withoutFeat("Song featuring Someone"))
        assertEquals("Artist", TrackNames.withoutFeat("Artist feat. Someone"))
        assertEquals("Artist", TrackNames.withoutFeat("Artist ft Someone"))
        assertEquals("Left Behind", TrackNames.withoutFeat("Left Behind"))
        assertEquals("Soft Feather", TrackNames.withoutFeat("Soft Feather"))
    }

    // Artists and albums

    @Test
    fun `a YouTube topic channel suffix is removed`() {
        assertEquals("Quillmere", TrackNames.cleanArtist("Quillmere - Topic"))
        assertEquals("Quillmere", TrackNames.cleanArtist("Quillmere - topic"))
        assertEquals("Quillmere", TrackNames.cleanArtist("Quillmere – Topic"))
        assertEquals("Quillmere", TrackNames.cleanArtist("Quillmere — Topic"))
    }

    @Test
    fun `unknown artists are null`() {
        assertNull(TrackNames.cleanArtist(null))
        assertNull(TrackNames.cleanArtist(""))
        assertNull(TrackNames.cleanArtist("  "))
        assertNull(TrackNames.cleanArtist("<unknown>"))
        assertNull(TrackNames.cleanArtist("Unknown Artist"))
        assertNull(TrackNames.cleanArtist("unknown"))
    }

    @Test
    fun `artist quotes, handles and VEVO are removed`() {
        assertEquals("I Lanterni", TrackNames.cleanArtist("\"I Lanterni\""))
        assertEquals("Quillmere", TrackNames.cleanArtist("QuillmereVEVO"))
        assertEquals("Quillmere", TrackNames.cleanArtist("Quillmere @metal_bot"))
    }

    @Test
    fun `an album equal to the artist is ignored`() {
        assertNull(TrackNames.cleanAlbum("Quillmere", "Quillmere"))
        assertNull(TrackNames.cleanAlbum("quillmere ", "Quillmere - Topic"))
        assertNull(TrackNames.cleanAlbum("<unknown>", "Quillmere"))
        assertNull(TrackNames.cleanAlbum("Telegram", "Quillmere"))
        assertNull(TrackNames.cleanAlbum("snaptube", "Quillmere"))
        assertNull(TrackNames.cleanAlbum("Music", "Quillmere"))
        assertEquals("Ulso", TrackNames.cleanAlbum("Ulso", "Quillmere"))
    }

    // Query variants

    @Test
    fun `a clean tag gives one query with the album`() {
        assertEquals(
            listOf(TrackQuery("Quillmere", "Vael", "Ulso")),
            TrackNames.queries("Quillmere", "Vael", "Ulso"),
        )
    }

    @Test
    fun `variants go from cleaned to without version tags to without feat`() {
        assertEquals(
            listOf(
                TrackQuery("Kestrevon feat. Someone", "Day Two (feat. Someone) (Live)", "01100110"),
                TrackQuery("Kestrevon feat. Someone", "Day Two"),
                TrackQuery("Kestrevon", "Day Two"),
            ),
            TrackNames.queries("Kestrevon feat. Someone", "04. Day Two (feat. Someone) (Live) (MP3_160K)", "01100110"),
        )
    }

    @Test
    fun `the album is dropped when it equals the artist`() {
        assertEquals(TrackQuery("Garrow Lune", "The Driftwood", null), first("Garrow Lune - Topic", "04. The Driftwood", "Garrow Lune"))
    }

    @Test
    fun `artist and title crammed into the title are split both ways`() {
        assertEquals(
            listOf(
                TrackQuery("Brennmoor", "Ten"),
                TrackQuery("Ten", "Brennmoor"),
                TrackQuery(null, "Brennmoor - Ten"),
            ),
            TrackNames.queries("<unknown>", "Brennmoor - Ten (Official Video)", null),
        )
    }

    @Test
    fun `an en dash also splits artist and title`() {
        assertEquals(TrackQuery("Brennmoor", "Ten"), first(null, "Brennmoor – Ten"))
    }

    @Test
    fun `a bracket tag before an artist title pair is dropped before splitting`() {
        val queries = TrackNames.queries(null, "[Velvo Voice] K Hymn - Holm", null)
        assertEquals(TrackQuery("K Hymn", "Holm"), queries[0])
        assertEquals(TrackQuery("Holm", "K Hymn"), queries[1])
    }

    @Test
    fun `an artist repeated at the start of the title is removed`() {
        assertEquals(TrackQuery("Brennmoor", "Ten"), first("Brennmoor", "Brennmoor - Ten"))
    }

    @Test
    fun `a title without an artist anywhere gives no query`() {
        assertEquals(emptyList<TrackQuery>(), TrackNames.queries(null, "Some Song", null))
        assertEquals(emptyList<TrackQuery>(), TrackNames.queries("<unknown>", "Intro (Live)", "Live"))
    }

    @Test
    fun `stray quotes around the artist do not reach the query`() {
        assertEquals(TrackQuery("I Lanterni", "Lucerna"), first("\"I Lanterni\"", "Lucerna"))
    }

    @Test
    fun `queries are distinct and capped`() {
        val queries = TrackNames.queries(null, "A feat. B - C (Live) (feat. D)", null)
        assertTrue(queries.size <= TrackNames.MAX_QUERIES)
        assertEquals(queries.size, queries.map { (it.artist?.lowercase() to it.title.lowercase()) }.distinct().size)
        val withFile = TrackNames.queries(null, "A feat. B - C (Live) (feat. D)", null, "E feat. F - G (Live).mp3")
        assertEquals(TrackNames.MAX_QUERIES, withFile.size)
        assertEquals(queries, withFile.take(queries.size))
    }

    // Several artists in one tag

    @Test
    fun `a tag naming several artists is also asked for by its first and its last artist, after the tag's own queries`() {
        val tag = "Kel Varo; Mina Ost; Oda Venn"
        val queries = TrackNames.queries(tag, "Glass Orchard", null, artists = listOf("Kel Varo", "Mina Ost", "Oda Venn"))

        assertEquals(TrackNames.queries(tag, "Glass Orchard", null), queries.dropLast(2))
        assertEquals(listOf(TrackQuery("Kel Varo", "Glass Orchard"), TrackQuery("Oda Venn", "Glass Orchard")), queries.takeLast(2))
    }

    @Test
    fun `an artist alone is asked with the title without version tags or feat`() {
        val queries = TrackNames.queries("Pell Drummond, Sora Vey", "Night Ferry (Live) (feat. Ilo Brand)", "Copper Days", artists = listOf("Pell Drummond", "Sora Vey"))

        assertEquals(listOf(TrackQuery("Pell Drummond", "Night Ferry"), TrackQuery("Sora Vey", "Night Ferry")), queries.takeLast(2))
    }

    @Test
    fun `one artist adds nothing, nor an artist the tag's own queries ask for already`() {
        val alone = TrackNames.queries("Kel Varo", "Glass Orchard", null)
        assertEquals(alone, TrackNames.queries("Kel Varo", "Glass Orchard", null, artists = listOf("Kel Varo")))

        // "Without feat." already asks for the first artist: only the last one is new.
        val featuring = TrackNames.queries("Vesna Kroll feat. Arlo Pike", "Saltwater Hymn", null, artists = listOf("Vesna Kroll", "Arlo Pike"))
        assertEquals(TrackNames.queries("Vesna Kroll feat. Arlo Pike", "Saltwater Hymn", null) + TrackQuery("Arlo Pike", "Saltwater Hymn"), featuring)
    }

    @Test
    fun `an artist alone is cleaned like a tag, and an unknown one is never asked for`() {
        val queries = TrackNames.queries("Kel Varo - Topic; Unknown Artist", "Glass Orchard", null, artists = listOf("Kel Varo - Topic", "Unknown Artist"))

        assertEquals(TrackQuery("Kel Varo", "Glass Orchard"), queries.last())
        assertTrue(queries.none { it.artist == "Unknown Artist" })
    }

    @Test
    fun `the artists' queries come on top of the cap, and so do their requests`() {
        assertEquals(2, TrackNames.MAX_ARTIST_QUERIES)
        assertEquals(2 * (TrackNames.MAX_QUERIES + TrackNames.MAX_ARTIST_QUERIES) + TrackNames.MAX_LOOSE_QUERIES, TrackNames.MAX_REQUESTS)
    }

    // File names

    @Test
    fun `a file name loses its extension, track number and bitrate tag`() {
        assertEquals("Song Title", TrackNames.fileTitle("05- Song Title (MP3_320K).mp3"))
        assertEquals("Song Title", TrackNames.fileTitle("12_Song Title.m4a"))
        assertEquals("Song Title", TrackNames.fileTitle("07. Song Title(MP3_160K).mp3"))
        assertEquals("Mr. Song", TrackNames.fileTitle("Mr. Song.mp3"))
        assertEquals("Song", TrackNames.fileTitle("Song"))
    }

    @Test
    fun `a file name also loses a number followed by a space or joined to the title`() {
        assertEquals("Lanterns", TrackNames.fileTitle("05 Lanterns.mp3"))
        assertEquals("Song Title", TrackNames.fileTitle("5-Song Title.mp3"))
        assertEquals("Song Title", TrackNames.fileTitle("103-Song Title.mp3"))
        assertEquals("21 Paper Boats", TrackNames.fileTitle("21 Paper Boats.mp3"))
        // A title equal to the file name is read as the file name: the artist tag's queries get the bare title too.
        assertEquals(
            listOf(TrackQuery("Band A", "05 Lanterns"), TrackQuery("Band A", "Lanterns")),
            TrackNames.queries("Band A", "05 Lanterns", null, "05 Lanterns.mp3"),
        )
    }

    @Test
    fun `a file name without a letter names nothing`() {
        assertNull(TrackNames.fileTitle("01.mp3"))
        assertNull(TrackNames.fileTitle(".mp3"))
        assertNull(TrackNames.fileTitle("  (320K).mp3"))
    }

    @Test
    fun `an underscore in a file name is a lost character, never part of a word`() {
        // Snaptube writes ' and & as _.
        assertEquals("Band s Song", TrackNames.fileTitle("Band_s Song.mp3"))
        assertEquals("Band A & Band B - Song", TrackNames.fileTitle("Band A _ Band B - Song.mp3"))
        assertEquals("Band A - Song", TrackNames.fileTitle("Band_A_-_Song_(Official_Video).mp3"))
    }

    @Test
    fun `without an artist tag, the file name gives the queries`() {
        assertEquals(
            listOf(TrackQuery("Band A", "Song A"), TrackQuery("Song A", "Band A"), TrackQuery(null, "Band A - Song A")),
            TrackNames.queries("<unknown>", "Song A", null, "07 - Band A - Song A (Official Video).mp3"),
        )
    }

    @Test
    fun `a title tag that is only the file name also gives the file name's queries`() {
        // MediaStore uses the file name as the title when the file has none; in the file name "_" is a space.
        assertEquals(
            listOf(TrackQuery("Band A", "Song_Title_-_Official_Video"), TrackQuery("Band A", "Song Title")),
            TrackNames.queries("Band A", "Song_Title_-_Official_Video", null, "Song_Title_-_Official_Video.mp3"),
        )
    }

    @Test
    fun `with an artist tag, an artist named only in the file name is never queried`() {
        // An uploader as the artist: "Band A" would match automatically, so it is left to the check list.
        assertEquals(
            listOf(TrackQuery("Uploader", "Band A - Song A")),
            TrackNames.queries("Uploader", "Band A - Song A", null, "Band A - Song A.mp3"),
        )
    }

    @Test
    fun `tags with an artist and a title of their own leave the file name aside`() {
        assertEquals(listOf(TrackQuery("Band A", "Song A")), TrackNames.queries("Band A", "Song A", null, "07 - Band B - Song B.mp3"))
    }

    // Loose searches

    @Test
    fun `a loose search uses the title's words, then the file name's when they differ`() {
        assertEquals(listOf("Song A", "Band A Song A"), TrackNames.looseQueries("05- Song A (Live)", "Band A - Song A.mp3"))
        assertEquals(listOf("Song A"), TrackNames.looseQueries("Song A", "01. Song A.mp3"))
        assertEquals(listOf("Song A"), TrackNames.looseQueries("Song A", null))
        assertEquals(listOf("Song A"), TrackNames.looseQueries("Song A", "01.mp3"))
    }

    @Test
    fun `a loose search keeps artist and title words, without feat, brackets or punctuation`() {
        assertEquals(listOf("Band A Song A"), TrackNames.looseQueries("Band A feat. Singer B - Song A (Official Video) [Remastered]", null))
        assertEquals(listOf("Band A Band B Song"), TrackNames.looseQueries("Band A & Band B - Song", null))
        // LRCLIB finds "Don't" from "Don t" (checked live on 2026-11-04).
        assertEquals(listOf("Don t Feed Llamas"), TrackNames.looseQueries("Don't Feed Llamas", null))
        assertEquals(listOf("Band s Song"), TrackNames.looseQueries("Band_s Song", null))
    }

    @Test
    fun `a song's names are its title, its halves and a file name that differs`() {
        assertEquals(listOf("Band A - Song A", "Band A", "Song A", "Other Name"), TrackNames.names("Band A - Song A", "Other Name.mp3"))
        assertEquals(listOf("Song A"), TrackNames.names("04. Song A", "04. Song A.mp3"))
    }

    // Comparison

    @Test
    fun `normalize folds case, accents, punctuation and ampersands`() {
        assertEquals("sala de ux", TrackNames.normalize("Säla de Ux"))
        assertEquals("odile and brandt", TrackNames.normalize("Odile & Brandt"))
        assertEquals("strasse", TrackNames.normalize("Straße"))
        assertEquals("don t stop me now", TrackNames.normalize("Don't  Stop, Me Now!"))
    }
}
