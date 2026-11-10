package app.lyrify.tags.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMatchTest {
    // Albums on the phone and releases, names made up

    private val lanterns = listOf(
        listOf(T("Quiet Ember", 254_000), T("Salt Road", 198_500), T("Nachtfähre", 301_200)),
        listOf(T("Brennmoor", 222_000), T("Vael (Reprise)", 187_000)),
    )

    private fun release(
        id: String = ID_A,
        title: String = "Lanterns of Ulso",
        artist: String = "Mira Solen",
        group: String? = GROUP_A,
        date: String? = "1998",
        firstReleaseDate: String? = "1998-04-17",
        discs: List<List<T>> = lanterns,
    ): MbRelease {
        fun credit(name: String) = Credit(listOf(Credit.Part(name, artistId = if (name == "Various Artists") Names.VARIOUS_ARTISTS_ID else null)))
        var n = 0
        val media = discs.mapIndexed { d, tracks ->
            MbMedium(
                position = d + 1,
                format = "CD",
                trackCount = tracks.size,
                tracks = tracks.mapIndexed { i, t ->
                    n++
                    MbTrack("$id-t$n", "$id-r$n", i + 1, t.title, t.lengthMs, credit(t.artist ?: artist))
                },
            )
        }
        return MbRelease(id, title, credit(artist), group?.let { MbReleaseGroup(it, firstReleaseDate) }, date, "XE", discs.sumOf { it.size }, media, hasFront = true)
    }

    private fun song(
        title: String?,
        ms: Long,
        track: Int? = null,
        disc: Int? = null,
        artist: String? = "Mira Solen",
        album: String? = "Lanterns of Ulso",
        albumArtist: String? = "Mira Solen",
        year: Int? = null,
        releaseId: String? = null,
        folder: String = "/storage/emulated/0/Music/Lanterns",
    ) = PhoneSong("$folder/${title ?: "track"}.mp3", title, artist, album, albumArtist, disc, track, ms, year, releaseId)

    /** The phone's copy of [lanterns]: titles and lengths as the release has them, numbered like it. */
    private fun lanternSongs(numbered: Boolean = true, year: Int? = null, releaseId: String? = null): List<PhoneSong> {
        var disc = 0
        return lanterns.flatMap { tracks ->
            disc++
            tracks.mapIndexed { i, t ->
                song(t.title, t.lengthMs!!, track = (i + 1).takeIf { numbered }, disc = disc.takeIf { numbered }, year = year, releaseId = releaseId)
            }
        }
    }

    private fun album(songs: List<PhoneSong>, title: String? = "Lanterns of Ulso", albumArtist: String? = "Mira Solen") = PhoneAlbum(title, albumArtist, songs)

    private fun doubtsOf(album: PhoneAlbum, vararg releases: MbRelease): Set<Doubt> =
        when (val proposal = AlbumMatch.propose(album, releases.toList())) {
            is Proposal.Ready -> emptySet()
            is Proposal.PickOne -> proposal.doubts
        }

    // Ready

    @Test
    fun `a release that holds every rule is Ready`() {
        val proposal = AlbumMatch.propose(album(lanternSongs()), listOf(release()))
        assertTrue(proposal.toString(), proposal is Proposal.Ready)
        val match = (proposal as Proposal.Ready).match
        assertEquals(ID_A, match.release.id)
        assertEquals(0.0, match.distance, 1e-9)
        assertEquals(Fit(5, 5), match.fit)
        assertEquals("5 of 5 songs fit", match.fit.line)
    }

    // Rule 1: pairing

    @Test
    fun `songs pair by title whatever the file order, without numbers`() {
        val songs = lanternSongs(numbered = false).reversed()
        val match = AlbumMatch.match(album(songs), release())
        assertEquals(5, match.pairing.pairs.size)
        match.pairing.pairs.forEach { assertEquals(it.song.title, it.track.track.title) }
        assertTrue(AlbumMatch.propose(album(songs), listOf(release())) is Proposal.Ready)
    }

    @Test
    fun `two songs of similar length on one album never swap titles`() {
        val tracks = listOf(listOf(T("Night Song I", 200_000), T("Night Song II", 201_000), T("Ember", 150_000), T("Embers", 151_000)))
        // Each song's length is closer to the OTHER track's.
        val songs = listOf(song("Night Song II", 200_000), song("Night Song I", 201_000), song("Embers", 150_000), song("Ember", 151_000))

        val pairs = AlbumMatch.match(album(songs), release(discs = tracks)).pairing.pairs

        assertEquals(4, pairs.size)
        pairs.forEach { assertEquals(it.song.title, it.track.track.title) }
    }

    @Test
    fun `songs with the same title pair by length`() {
        val tracks = listOf(listOf(T("Interlude", 60_000), T("Quiet Ember", 254_000), T("Interlude", 95_000)))
        val songs = listOf(song("Interlude", 95_500), song("Quiet Ember", 254_000), song("Interlude", 60_400))

        val pairs = AlbumMatch.match(album(songs), release(discs = tracks)).pairing.pairs.associate { it.song.durationMs to it.track.index }

        assertEquals(mapOf(95_500L to 3, 254_000L to 2, 60_400L to 1), pairs)
    }

    @Test
    fun `pairing never uses length alone`() {
        val songs = lanternSongs().dropLast(1) + song("Unrelated Title", 187_000, track = 2, disc = 2)

        val match = AlbumMatch.match(album(songs), release())

        assertEquals(listOf("Unrelated Title"), match.pairing.unpairedSongs.map { it.title })
        assertEquals(listOf("Vael (Reprise)"), match.pairing.unpairedTracks.map { it.track.title })
        assertTrue(Doubt.PAIRING in doubtsOf(album(songs), release()))
        assertEquals(Fit(4, 5), match.fit)
    }

    @Test
    fun `title similarity, pinned`() {
        assertEquals(1.0, AlbumMatch.titleSimilarity("Quiet Ember", "quiet ember!"), 1e-9)
        assertEquals(1.0, AlbumMatch.titleSimilarity("Nachtfähre", "Nachtfahre"), 1e-9)
        assertEquals(1.0, AlbumMatch.titleSimilarity("Don't Stop", "Dont Stop"), 1e-9)
        assertEquals(1.0, AlbumMatch.titleSimilarity("Salt Road (feat. Glass Harbour)", "Salt Road"), 1e-9)
        assertEquals(1.0, AlbumMatch.titleSimilarity("Salt & Road", "Salt and Road"), 1e-9)
        assertEquals(1.0 - 1.0 / 11, AlbumMatch.titleSimilarity("Night Song I", "Night Song II"), 1e-9)
        assertEquals(0.9, AlbumMatch.titleSimilarity("Abcdefghij", "Abcdefghik"), 1e-9)
        assertEquals(0.875, AlbumMatch.titleSimilarity("Lanterns", "Lantern"), 1e-9)
        assertEquals(0.0, AlbumMatch.titleSimilarity(null, "Lanterns"), 1e-9)
        assertEquals(0.0, AlbumMatch.titleSimilarity("", ""), 1e-9)
        // Version tags count: a remaster's title is another title.
        assertTrue(AlbumMatch.titleSimilarity("Quiet Ember (Remastered 2011)", "Quiet Ember") < AlbumMatch.MIN_SIMILARITY)
    }

    @Test
    fun `a title at 0,9 pairs, below it does not`() {
        val at = AlbumMatch.pair(listOf(song("Abcdefghij", 100_000)), release(discs = listOf(listOf(T("Abcdefghik", 100_000)))).tracks)
        assertEquals(1, at.pairs.size)
        val below = AlbumMatch.pair(listOf(song("Lanterns", 100_000)), release(discs = listOf(listOf(T("Lantern", 100_000)))).tracks)
        assertEquals(0, below.pairs.size)
        assertEquals(0.9, AlbumMatch.MIN_SIMILARITY, 0.0)
    }

    @Test
    fun `another number of tracks is not sure`() {
        val bigger = release(discs = lanterns.dropLast(1) + listOf(lanterns.last() + T("Kestrel Lights", 240_000)))
        assertTrue(Doubt.TRACK_COUNT in doubtsOf(album(lanternSongs()), bigger))
        assertTrue(Doubt.TRACK_COUNT in doubtsOf(album(lanternSongs().dropLast(1)), release()))
    }

    // Rule 2: lengths

    @Test
    fun `every song within 10 s of its track, and the album within 10 s`() {
        val songs = lanternSongs()
        val tenOff = songs.mapIndexed { i, s -> if (i == 0) s.copy(durationMs = s.durationMs + 10_000) else s }
        assertFalse(Doubt.LENGTHS in doubtsOf(album(tenOff), release()))
        assertTrue(doubtsOf(album(tenOff), release()).isEmpty())

        val over = songs.mapIndexed { i, s -> if (i == 0) s.copy(durationMs = s.durationMs + 10_001) else s }
        assertTrue(Doubt.LENGTHS in doubtsOf(album(over), release()))

        val drift = songs.mapIndexed { i, s -> if (i < 3) s.copy(durationMs = s.durationMs + 4_000) else s }
        val doubts = doubtsOf(album(drift), release())
        assertTrue(doubts.toString(), Doubt.ALBUM_LENGTH in doubts)
        assertFalse(doubts.toString(), Doubt.LENGTHS in doubts)
        assertEquals(10_000L, AlbumMatch.LENGTH_TOLERANCE_MS)
    }

    @Test
    fun `an unknown track length is not sure`() {
        val unknown = release(discs = listOf(lanterns[0], listOf(T("Brennmoor", null), T("Vael (Reprise)", 187_000))))
        assertTrue(Doubt.LENGTHS in doubtsOf(album(lanternSongs()), unknown))
    }

    // Rule 3: names

    @Test
    fun `album and album artist match after case, punctuation, accents, The and featured artists`() {
        listOf("the lanterns of ulso", "Lanterns of Úlso", "Lanterns of Ulso!", "LANTERNS OF ULSO").forEach { title ->
            assertTrue(title, doubtsOf(album(lanternSongs(), title = title), release()).isEmpty())
        }
        listOf("Mira Solen feat. Glass Harbour", "mira solen", "The Mira Solen").forEach { artist ->
            assertTrue(artist, doubtsOf(album(lanternSongs(), albumArtist = artist), release()).isEmpty())
        }
        assertTrue(Doubt.ALBUM_NAME in doubtsOf(album(lanternSongs(), title = "Lanterns of Ulso (Deluxe)"), release()))
        assertTrue(Doubt.ALBUM_NAME in doubtsOf(album(lanternSongs(), title = "Lanterns"), release()))
        assertTrue(Doubt.ALBUM_ARTIST in doubtsOf(album(lanternSongs(), albumArtist = "Glass Harbour"), release()))
    }

    @Test
    fun `without an album artist, the one artist of every song stands in`() {
        val songs = lanternSongs().map { it.copy(albumArtist = null) }
        assertTrue(doubtsOf(album(songs, albumArtist = null), release()).isEmpty())
        val unknown = songs.map { it.copy(albumArtist = "Unknown Artist") }
        assertTrue(doubtsOf(album(unknown, albumArtist = "Unknown Artist"), release()).isEmpty())
    }

    @Test
    fun `Various Artists compilations match Various Artists releases only`() {
        val tracks = listOf(listOf(T("Quiet Ember", 254_000, "Mira Solen"), T("Salt Road", 198_500, "Glass Harbour"), T("Kestrel Lights", 251_000, "Quillmere")))
        val compilation = release(artist = "Various Artists", discs = tracks)
        val songs = tracks[0].mapIndexed { i, t -> song(t.title, t.lengthMs!!, track = i + 1, disc = 1, artist = t.artist, albumArtist = "Various Artists") }

        assertTrue(doubtsOf(album(songs, albumArtist = "Various Artists"), compilation).isEmpty())
        val untagged = songs.map { it.copy(albumArtist = null) }
        assertTrue(doubtsOf(album(untagged, albumArtist = null), compilation).isEmpty())
        assertTrue(doubtsOf(album(songs.map { it.copy(albumArtist = "VA") }, albumArtist = "VA"), compilation).isEmpty())

        val byOne = release(artist = "Mira Solen", discs = tracks.map { disc -> disc.map { it.copy(artist = null) } })
        assertTrue(Doubt.ALBUM_ARTIST in doubtsOf(album(songs, albumArtist = "Various Artists"), byOne))
        assertTrue(Doubt.ALBUM_ARTIST in doubtsOf(album(lanternSongs()), release(artist = "Various Artists")))
    }

    @Test
    fun `on a compilation, each song's artist counts in the distance`() {
        val tracks = listOf(listOf(T("Quiet Ember", 254_000, "Mira Solen"), T("Salt Road", 198_500, "Glass Harbour")))
        val compilation = release(artist = "Various Artists", discs = tracks)
        val right = album(tracks[0].map { song(it.title, it.lengthMs!!, artist = it.artist, albumArtist = "Various Artists") }, albumArtist = "Various Artists")
        val wrong = album(right.songs.map { it.copy(artist = "Brennmoor Choir") }, albumArtist = "Various Artists")
        assertEquals(0.0, AlbumMatch.match(right, compilation).distance, 1e-9)
        assertTrue(AlbumMatch.match(wrong, compilation).distance > 0.05)
    }

    // Rule 4: a clear winner

    @Test
    fun `the distance and the gap are pinned`() {
        assertEquals(0.04, AlbumMatch.SURE_DISTANCE, 0.0)
        assertEquals(0.02, AlbumMatch.MIN_GAP, 0.0)
        val songs = album(lanternSongs())
        val best = AlbumMatch.match(songs, release())
        val other = AlbumMatch.match(songs, release(id = ID_C, group = GROUP_B))

        assertFalse(Doubt.DISTANCE in AlbumMatch.doubts(songs, best.copy(distance = 0.04), listOf(other.copy(distance = 0.9))))
        assertTrue(Doubt.DISTANCE in AlbumMatch.doubts(songs, best.copy(distance = 0.0401), listOf(other.copy(distance = 0.9))))

        assertFalse(Doubt.GAP in AlbumMatch.doubts(songs, best.copy(distance = 0.04), listOf(other.copy(distance = 0.06))))
        assertFalse(Doubt.GAP in AlbumMatch.doubts(songs, best.copy(distance = 0.01), listOf(other.copy(distance = 0.03))))
        assertTrue(Doubt.GAP in AlbumMatch.doubts(songs, best.copy(distance = 0.01), listOf(other.copy(distance = 0.0299))))
    }

    @Test
    fun `the distance, pinned on known cases`() {
        val songs = album(lanternSongs(numbered = false))
        // A sixth track on the release: one missing track (0.9) over album 3, artist 3, five tracks 2 each, missing 0.9.
        val bigger = release(discs = lanterns.dropLast(1) + listOf(lanterns.last() + T("Kestrel Lights", 240_000)))
        assertEquals(0.9 / 16.9, AlbumMatch.match(songs, bigger).distance, 1e-9)
        // A song 25 s off: (25 - 10) / 30 = 0.5 on length (2) of title 3 + length 2, so 0.2 on its track (2) over 16.
        val off = album(songs.songs.mapIndexed { i, s -> if (i == 0) s.copy(durationMs = s.durationMs + 25_000) else s })
        assertEquals(0.4 / 16, AlbumMatch.match(off, release()).distance, 1e-9)
    }

    @Test
    fun `beets' string distance, pinned`() {
        assertEquals(0.0, AlbumMatch.stringDistance("Lanterns of Ulso", "lanterns of ulso!"), 1e-9)
        assertEquals(0.0, AlbumMatch.stringDistance("Lanterns, The", "The Lanterns"), 1e-9)
        assertEquals(0.0, AlbumMatch.stringDistance("Salt & Road", "Salt and Road"), 1e-9)
        assertEquals(0.0, AlbumMatch.stringDistance("Nachtfähre", "Nachtfahre"), 1e-9)
        assertEquals(0.1 * 3 / 11, AlbumMatch.stringDistance("The Lanterns", "Lanterns"), 1e-9)
        assertEquals(0.3 * 4 / 12, AlbumMatch.stringDistance("Salt Road (Live)", "Salt Road"), 1e-9)
        assertEquals(0.0, AlbumMatch.stringDistance(null, null), 1e-9)
        assertEquals(1.0, AlbumMatch.stringDistance("Lanterns", null), 1e-9)
        assertEquals(1.0, AlbumMatch.stringDistance("abc", "xyz"), 1e-9)
    }

    @Test
    fun `editions in one release group with the same tracks are a tie that is fine`() {
        val songs = album(lanternSongs())
        val proposal = AlbumMatch.propose(songs, listOf(release(id = ID_A), release(id = ID_B)))
        assertTrue(proposal.toString(), proposal is Proposal.Ready)
        assertTrue(AlbumMatch.sameEdition(release(id = ID_A), release(id = ID_B)))
    }

    @Test
    fun `editions with other lengths or in another group are rivals`() {
        val songs = album(lanternSongs())
        val longer = release(id = ID_B, discs = listOf(lanterns[0], listOf(T("Brennmoor", 225_000), T("Vael (Reprise)", 187_000))))
        assertFalse(AlbumMatch.sameEdition(release(), longer))
        assertTrue(Doubt.GAP in doubtsOf(songs, release(), longer))

        val otherGroup = release(id = ID_B, group = GROUP_B)
        assertFalse(AlbumMatch.sameEdition(release(), otherGroup))
        assertTrue(Doubt.GAP in doubtsOf(songs, release(), otherGroup))

        val retitled = release(id = ID_B, discs = listOf(lanterns[0], listOf(T("Brennmoor", 222_000), T("Vael", 187_000))))
        assertFalse(AlbumMatch.sameEdition(release(), retitled))
    }

    @Test
    fun `a deluxe edition with bonus tracks does not block the standard one`() {
        val deluxe = release(id = ID_B, discs = lanterns.dropLast(1) + listOf(lanterns.last() + T("Kestrel Lights", 240_000) + T("Brennmoor (Demo)", 200_000)))
        val proposal = AlbumMatch.propose(album(lanternSongs()), listOf(deluxe, release(id = ID_A)))
        assertEquals(ID_A, (proposal as Proposal.Ready).match.release.id)
    }

    @Test
    fun `the year in the files picks between two pressings`() {
        val songs = album(lanternSongs(year = 2011))
        val remaster = release(id = ID_A, date = "2011-09-02")
        val original = release(id = ID_B, group = GROUP_B, date = "1998")
        assertTrue(AlbumMatch.match(songs, remaster).distance < AlbumMatch.match(songs, original).distance)
        val proposal = AlbumMatch.propose(songs, listOf(original, remaster))
        assertEquals(ID_A, (proposal as Proposal.Ready).match.release.id)
    }

    @Test
    fun `a lone candidate needs 0,95 on every title`() {
        val tracks = listOf(lanterns[0] + T("Brennmoor", 222_000) + T("Vael Reprise Two", 187_000))
        val songs = album(tracks[0].mapIndexed { i, t -> song(if (i == 4) "Vael Reprise Tw" else t.title, t.lengthMs!!) })
        val only = release(discs = tracks)
        assertEquals(1.0 - 1.0 / 14, AlbumMatch.titleSimilarity("Vael Reprise Tw", "Vael Reprise Two"), 1e-9)

        assertEquals(setOf(Doubt.LONE_TITLES), doubtsOf(songs, only))

        val unrelated = release(id = ID_C, group = GROUP_B, title = "Brennmoor Nights", discs = listOf(List(5) { T("Other Song $it", 100_000) }))
        assertTrue(doubtsOf(songs, only, unrelated).isEmpty())
        assertEquals(0.95, AlbumMatch.LONE_SIMILARITY, 0.0)
    }

    // Rule 5: numbers

    @Test
    fun `disc and track numbers must agree with the release, or be missing`() {
        val songs = lanternSongs()
        assertTrue(Doubt.NUMBERS in doubtsOf(album(songs.mapIndexed { i, s -> if (i == 0) s.copy(track = 2) else s }), release()))
        assertTrue(Doubt.NUMBERS in doubtsOf(album(songs.mapIndexed { i, s -> if (i == 0) s.copy(disc = 2) else s }), release()))
        assertTrue(doubtsOf(album(lanternSongs(numbered = false)), release()).isEmpty())
        // Numbered over the whole release: Brennmoor is track 4.
        val whole = songs.mapIndexed { i, s -> s.copy(track = i + 1, disc = null) }
        assertTrue(doubtsOf(album(whole), release()).isEmpty())
    }

    // The same title on two discs

    /** "Intro" opens both discs; disc 2's is 0.4 s longer. */
    private val twoIntros = listOf(
        listOf(T("Intro", 60_000), T("Quiet Ember", 254_000)),
        listOf(T("Intro", 60_400), T("Salt Road", 198_500)),
    )

    @Test
    fun `with disc numbers, each song pairs on its own disc even when lengths say otherwise`() {
        val songs = listOf(
            song("Intro", 60_400, track = 1, disc = 1),
            song("Quiet Ember", 254_000, track = 2, disc = 1),
            song("Intro", 60_000, track = 1, disc = 2),
            song("Salt Road", 198_500, track = 2, disc = 2),
        )

        val proposal = AlbumMatch.propose(album(songs), listOf(release(discs = twoIntros)))

        assertTrue(proposal.toString(), proposal is Proposal.Ready)
        (proposal as Proposal.Ready).match.pairing.pairs.forEach { assertEquals(it.song.disc, it.track.disc) }
    }

    @Test
    fun `numbered over the whole release, each song pairs on its own disc even when lengths say otherwise`() {
        val songs = listOf(
            song("Intro", 60_400, track = 1),
            song("Quiet Ember", 254_000, track = 2),
            song("Intro", 60_000, track = 3),
            song("Salt Road", 198_500, track = 4),
        )

        val proposal = AlbumMatch.propose(album(songs), listOf(release(discs = twoIntros)))

        assertTrue(proposal.toString(), proposal is Proposal.Ready)
        (proposal as Proposal.Ready).match.pairing.pairs.forEach { assertEquals(it.song.track, it.track.index) }
    }

    @Test
    fun `without disc numbers, numbers counted per disc are never sure on two discs`() {
        val songs = listOf(
            song("Intro", 60_000, track = 1),
            song("Quiet Ember", 254_000, track = 2),
            song("Intro", 60_400, track = 1),
            song("Salt Road", 198_500, track = 2),
        )
        assertTrue(Doubt.NUMBERS in doubtsOf(album(songs), release(discs = twoIntros)))
        // One disc: numbers on the disc are numbers on the release.
        val one = songs.take(2)
        assertTrue(doubtsOf(album(one), release(discs = twoIntros.take(1))).isEmpty())
    }

    // The year, measured from the closer of the edition's year and the original

    @Test
    fun `a year near the original favours no pressing over another`() {
        val songs = album(lanternSongs(year = 1999))
        // Two pressings of a 1998 album, in two release groups: a 2001 remaster and a 2015 reissue.
        val remaster = release(id = ID_A, group = GROUP_A, date = "2001")
        val reissue = release(id = ID_B, group = GROUP_B, date = "2015")

        val proposal = AlbumMatch.propose(songs, listOf(reissue, remaster))

        assertTrue(proposal.toString(), proposal is Proposal.PickOne)
        assertTrue(Doubt.GAP in (proposal as Proposal.PickOne).doubts)
        assertEquals(AlbumMatch.match(songs, remaster).distance, AlbumMatch.match(songs, reissue).distance, 1e-9)
    }

    // A twin of the same edition is no second opinion

    @Test
    fun `a lone candidate and its twin edition still need 0,95 on every title`() {
        val tracks = listOf(lanterns[0] + T("Brennmoor", 222_000) + T("Vael Reprise Two", 187_000))
        val songs = album(tracks[0].mapIndexed { i, t -> song(if (i == 4) "Vael Reprise Tw" else t.title, t.lengthMs!!) })

        val doubts = doubtsOf(songs, release(id = ID_A, discs = tracks), release(id = ID_B, discs = tracks))

        assertEquals(setOf(Doubt.LONE_TITLES), doubts)
    }

    // Various Artists only when the tags say so

    @Test
    fun `one blank or unknown artist tag never makes an album Various Artists`() {
        val songs = lanternSongs().map { it.copy(albumArtist = null) }
        val gaps = songs.mapIndexed { i, s ->
            when (i) {
                0 -> s.copy(artist = null)
                1 -> s.copy(artist = " ")
                else -> if (i == 2) s.copy(artist = "Unknown Artist") else s
            }
        }
        val album = album(gaps, albumArtist = null)

        assertFalse(album.isVarious)
        assertEquals("Mira Solen", album.artist)
        assertTrue(doubtsOf(album, release()).isEmpty())
        assertTrue(Doubt.ALBUM_ARTIST in doubtsOf(album, release(artist = "Various Artists")))
    }

    @Test
    fun `no artist at all is not Various Artists either, and is never sure`() {
        val nobody = album(lanternSongs().map { it.copy(artist = null, albumArtist = null) }, albumArtist = null)
        assertFalse(nobody.isVarious)
        assertEquals(null, nobody.artist)
        assertTrue(Doubt.ALBUM_ARTIST in doubtsOf(nobody, release()))
        assertTrue(Doubt.ALBUM_ARTIST in doubtsOf(nobody, release(artist = "Various Artists")))
    }

    @Test
    fun `songs by artists that really differ, without an album artist, are Various Artists`() {
        val mixed = album(lanternSongs().mapIndexed { i, s -> s.copy(albumArtist = null, artist = if (i == 0) "Glass Harbour" else s.artist) }, albumArtist = null)
        assertTrue(mixed.isVarious)
        assertEquals(null, mixed.artist)
    }

    // Singles and ids

    @Test
    fun `singles always go to Pick one`() {
        val one = song("Quiet Ember", 254_000, track = 1, disc = 1)
        val single = release(discs = listOf(listOf(T("Quiet Ember", 254_000))))
        val proposal = AlbumMatch.propose(PhoneAlbum("Lanterns of Ulso", "Mira Solen", listOf(one)), listOf(single))
        assertTrue(proposal is Proposal.PickOne)
        assertEquals(setOf(Doubt.SINGLE), (proposal as Proposal.PickOne).doubts)

        val untitled = PhoneAlbum(null, null, lanternSongs().map { it.copy(album = null) })
        assertTrue(untitled.isSingle)
        assertTrue(Doubt.SINGLE in doubtsOf(untitled, release()))
    }

    @Test
    fun `a single's candidates come from its recording, one per release group, best first`() {
        val recordings = MusicBrainzJson.recordings(fixture("recording-search.json"))
        val song = song("Kestrel Lights", 251_400, artist = "Quillmere", album = null, albumArtist = null)

        val proposal = AlbumMatch.proposeSingle(song, recordings)

        assertEquals(setOf(Doubt.SINGLE), proposal.doubts)
        assertEquals(
            listOf("0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e09", "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e10", "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e11"),
            proposal.candidates.map { it.release.id },
        )
        assertEquals(listOf("1 of 1 song fits", "1 of 1 song fits", "0 of 1 song fits"), proposal.candidates.map { it.fit.line })
        assertEquals("Kestrel Lights", proposal.candidates[1].pairing.pairs.single().track.track.title)
    }

    @Test
    fun `what downloads add to a title never favours another version`() {
        val recordings = MusicBrainzJson.recordings(fixture("recording-search.json"))
        val song = song("Kestrel Lights (Official Video)", 251_400, artist = "Quillmere", album = null, albumArtist = null)

        val ids = AlbumMatch.proposeSingle(song, recordings).candidates.map { it.release.id.takeLast(3) }

        // The live version, whose title does not pair, comes last.
        assertEquals(listOf("e09", "e10", "e11"), ids)
    }

    @Test
    fun `an id already in the files is a candidate, never trusted blindly`() {
        // The id names the release that fits: Ready, on the rules alone.
        assertTrue(AlbumMatch.propose(album(lanternSongs(releaseId = ID_A)), listOf(release())) is Proposal.Ready)

        // The id names a release that does not fit: the one that fits is offered first, never applied.
        val wrong = release(id = ID_C, group = GROUP_B, title = "Brennmoor Nights", discs = listOf(List(5) { T("Other Song $it", 100_000) }))
        val proposal = AlbumMatch.propose(album(lanternSongs(releaseId = ID_C)), listOf(release(), wrong))
        assertTrue(proposal is Proposal.PickOne)
        assertEquals(ID_A, (proposal as Proposal.PickOne).candidates.first().release.id)
        assertTrue(Doubt.DISTANCE in proposal.doubts)

        // The id names the release, but a song is 30 s off: the rules still decide.
        val off = release(discs = listOf(lanterns[0], listOf(T("Brennmoor", 252_000), T("Vael (Reprise)", 187_000))))
        assertTrue(Doubt.LENGTHS in doubtsOf(album(lanternSongs(releaseId = ID_A)), off))
    }

    @Test
    fun `Pick one shows at most 3 candidates, best first, with their fit`() {
        val songs = album(lanternSongs())
        val releases = listOf(
            release(id = "r1", group = "g1", title = "Brennmoor Nights"),
            release(id = "r2", group = "g2"),
            release(id = "r3", group = "g3", discs = listOf(lanterns[0], listOf(T("Brennmoor", 240_000), T("Vael (Reprise)", 187_000)))),
            release(id = "r4", group = "g4", title = "Something Else", artist = "Glass Harbour"),
            release(id = "r5", group = "g5", title = "Nothing Alike", artist = "Quillmere", discs = listOf(List(5) { T("Other $it", 1_000) })),
        )

        val proposal = AlbumMatch.propose(songs, releases) as Proposal.PickOne

        assertEquals(listOf("r2", "r3", "r1"), proposal.candidates.map { it.release.id })
        assertEquals(proposal.candidates.sortedBy { it.distance }, proposal.candidates)
        assertEquals(listOf("5 of 5 songs fit", "4 of 5 songs fit", "5 of 5 songs fit"), proposal.candidates.map { it.fit.line })
    }

    @Test
    fun `no release at all is Pick one with nothing to pick`() {
        val proposal = AlbumMatch.propose(album(lanternSongs()), emptyList())
        assertEquals(Proposal.PickOne(emptyList(), setOf(Doubt.NO_CANDIDATE)), proposal)
    }

    // Albums from the library

    @Test
    fun `albums group across CD1 and CD2 folders, never across album artists`() {
        val cd1 = listOf(song("Quiet Ember", 1, folder = "/storage/emulated/0/Music/Lanterns/CD1"), song("Salt Road", 1, folder = "/storage/emulated/0/Music/Lanterns/CD1"))
        val cd2 = listOf(song("Brennmoor", 1, folder = "/storage/emulated/0/Music/Lanterns/CD2"), song("Vael", 1, folder = "/storage/emulated/0/Music/Lanterns/CD2"))
        val albums = PhoneAlbums.group(cd1 + cd2)
        assertEquals(1, albums.size)
        assertEquals(4, albums.single().songs.size)
        assertEquals("Lanterns of Ulso", albums.single().title)
        assertEquals("Mira Solen", albums.single().albumArtist)

        val other = cd2.map { it.copy(albumArtist = "Glass Harbour") }
        assertEquals(listOf(2, 2), PhoneAlbums.group(cd1 + other).map { it.songs.size })
    }

    @Test
    fun `without an album artist, sibling disc folders merge and other folders do not`() {
        fun untagged(title: String, folder: String) = song(title, 1, albumArtist = null, folder = folder)
        val discs = listOf(
            untagged("Quiet Ember", "/storage/emulated/0/Music/Lanterns CD1"),
            untagged("Brennmoor", "/storage/emulated/0/Music/Lanterns CD2"),
            untagged("Vael", "/storage/emulated/0/Music/Lanterns Disc 3"),
        )
        assertEquals(listOf(3), PhoneAlbums.group(discs).map { it.songs.size })

        val subfolders = listOf(untagged("Quiet Ember", "/storage/emulated/0/Music/Lanterns/CD1"), untagged("Brennmoor", "/storage/emulated/0/Music/Lanterns/cd 2"))
        assertEquals(listOf(2), PhoneAlbums.group(subfolders).map { it.songs.size })

        // "cd", "disc" or "disk" inside a word is not a disc folder.
        val words = listOf(untagged("Quiet Ember", "/storage/emulated/0/Music/Abcd1"), untagged("Brennmoor", "/storage/emulated/0/Music/Abcd2"))
        assertEquals(2, PhoneAlbums.group(words).size)

        val apart = listOf(
            untagged("Quiet Ember", "/storage/emulated/0/Music/Mira").copy(album = "Greatest Hits"),
            untagged("Salt Road", "/storage/emulated/0/Music/Glass").copy(album = "Greatest Hits"),
        )
        val groups = PhoneAlbums.group(apart)
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.isSingle })
    }

    @Test
    fun `songs without an album tag are singles, and album tags compare loosely`() {
        val loose = listOf(song("Quiet Ember", 1, album = null), song("Salt Road", 1, album = " "))
        assertEquals(listOf(true, true), PhoneAlbums.group(loose).map { it.isSingle })

        val cased = listOf(song("Quiet Ember", 1, album = "Lanterns of Ulso"), song("Salt Road", 1, album = "lanterns of ulso"), song("Vael", 1, album = "Lanterns of Ulso"))
        val album = PhoneAlbums.group(cased).single()
        assertEquals(3, album.songs.size)
        assertEquals("Lanterns of Ulso", album.title)
        assertFalse(album.isSingle)
    }
}
