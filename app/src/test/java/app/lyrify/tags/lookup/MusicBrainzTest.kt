package app.lyrify.tags.lookup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class MusicBrainzTest {
    @get:Rule
    val folder = TemporaryFolder()

    // Addresses

    @Test
    fun `ids are checked before they go into a path`() {
        assertTrue(MusicBrainzUrls.isId(ID_A))
        assertTrue(MusicBrainzUrls.isId(ID_A.uppercase()))
        listOf(null, "", "not-a-musicbrainz-id", "../../ws/2/x", "$ID_A/front", "${ID_A}0", ID_A.replace('b', 'z'))
            .forEach { assertFalse(it.toString(), MusicBrainzUrls.isId(it)) }
        assertThrows(IllegalArgumentException::class.java) { MusicBrainzUrls.release("../x") }
        assertThrows(IllegalArgumentException::class.java) { MusicBrainzUrls.front("x", CoverSize.FRONT_500) }
    }

    @Test
    fun `the release search asks for album title, artist and track count`() {
        assertEquals(
            "https://musicbrainz.org/ws/2/release/?query=release%3A%28Lanterns+of+Ulso%29+AND+artist%3A%28Mira+Solen%29+AND+tracks%3A12&fmt=json&limit=10",
            MusicBrainzUrls.releaseSearch("Lanterns of Ulso", "Mira Solen", 12).toString(),
        )
        assertEquals(
            "https://musicbrainz.org/ws/2/release/?query=release%3A%28Lanterns+of+Ulso%29+AND+tracks%3A12&fmt=json&limit=10",
            MusicBrainzUrls.releaseSearch("Lanterns of Ulso", null, 12).toString(),
        )
        assertEquals(
            "https://musicbrainz.org/ws/2/release/?query=release%3A%28Lanterns+of+Ulso%29+AND+artist%3A%28Mira+Solen%29&fmt=json&limit=10",
            MusicBrainzUrls.releaseSearch("Lanterns of Ulso", "Mira Solen", null).toString(),
        )
    }

    @Test
    fun `names are searched as words, never as query syntax`() {
        assertEquals("""XQ\/ZV\: Live\! \(Part 2\) and \"More\"""", MusicBrainzUrls.lucene("""XQ/ZV: Live! (Part 2) AND "More""""))
        assertEquals("""a \&\& b \|\| not c\~ \* \? \^ \[x\] \{y\} \+ \- \\""", MusicBrainzUrls.lucene("""a && b || NOT c~ * ? ^ [x] {y} + - \"""))
        assertEquals("Salt or Road", MusicBrainzUrls.lucene("Salt OR Road"))
        assertEquals("ORBIT ANDES NOTE", MusicBrainzUrls.lucene("ORBIT ANDES NOTE"))
    }

    @Test
    fun `a release is asked for with its tracks, recordings, credits and release group`() {
        assertEquals(
            "https://musicbrainz.org/ws/2/release/$ID_A?inc=artists+recordings+artist-credits+release-groups&fmt=json",
            MusicBrainzUrls.release(ID_A).toString(),
        )
        assertEquals("https://musicbrainz.org/ws/2/release-group/$GROUP_A?fmt=json", MusicBrainzUrls.releaseGroup(GROUP_A).toString())
        assertEquals(
            "https://musicbrainz.org/ws/2/release/$ID_A?inc=artists+recordings+artist-credits+release-groups&fmt=json",
            MusicBrainzUrls.release(ID_A.uppercase()).toString(),
        )
    }

    @Test
    fun `a release group's editions are browsed in one request, with their tracks, credits and release group, 100 at most`() {
        assertEquals(browseUri(GROUP_A), MusicBrainzUrls.releasesOfGroup(GROUP_A).toString())
        assertEquals(browseUri(GROUP_A), MusicBrainzUrls.releasesOfGroup(GROUP_A.uppercase()).toString())
        assertThrows(IllegalArgumentException::class.java) { MusicBrainzUrls.releasesOfGroup("../x") }
    }

    @Test
    fun `a browse lists each release whole, as a lookup does`() {
        val tracks = listOf(T("Quiet Ember", 254_000), T("Salt Road", 198_500, artist = "Glass Harbour"))
        val body = browseJson(releaseJson(ID_A, discs = listOf(tracks)), releaseJson(ID_B, discs = listOf(tracks), front = false)).encodeToByteArray()

        val releases = MusicBrainzJson.releases(body)

        assertEquals(listOf(ID_A, ID_B), releases.map { it.id })
        assertEquals(releases.map { MusicBrainzJson.release(releaseJson(it.id, discs = listOf(tracks), front = it.id == ID_A).encodeToByteArray()) }, releases)
        assertTrue(releases.all { it.hasAllTracks })
        assertEquals("Glass Harbour", releases.first().tracks[1].track.artist.text)
        assertEquals(1998, releases.first().releaseGroup?.originalYear)
        assertEquals(listOf(true, false), releases.map { it.hasFront })
    }

    @Test
    fun `a single is searched by title, artist and length within 10 s`() {
        assertEquals(
            "https://musicbrainz.org/ws/2/recording/?query=recording%3A%28Kestrel+Lights%29+AND+artist%3A%28Quillmere%29+AND+dur%3A%5B241428+TO+261428%5D&fmt=json&limit=10",
            MusicBrainzUrls.recordingSearch("Kestrel Lights", "Quillmere", 251_428).toString(),
        )
        assertEquals(
            "https://musicbrainz.org/ws/2/recording/?query=recording%3A%28Kestrel+Lights%29&fmt=json&limit=10",
            MusicBrainzUrls.recordingSearch("Kestrel Lights", null, null).toString(),
        )
        assertEquals(
            "https://musicbrainz.org/ws/2/recording/?query=recording%3A%28Kestrel+Lights%29+AND+dur%3A%5B0+TO+15000%5D&fmt=json&limit=10",
            MusicBrainzUrls.recordingSearch("Kestrel Lights", null, 5_000).toString(),
        )
    }

    @Test
    fun `covers come from the release's front at 1200 or 500 px`() {
        assertEquals("https://coverartarchive.org/release/$ID_A/front-1200", MusicBrainzUrls.front(ID_A, CoverSize.FRONT_1200).toString())
        assertEquals("https://coverartarchive.org/release/$ID_A/front-500", MusicBrainzUrls.front(ID_A, CoverSize.FRONT_500).toString())
    }

    @Test
    fun `years come from the start of a date`() {
        assertEquals(1998, yearOf("1998-04-17"))
        assertEquals(1998, yearOf("1998"))
        assertEquals(1998, yearOf("1998-04"))
        assertNull(yearOf(""))
        assertNull(yearOf(null))
        assertNull(yearOf("unknown"))
    }

    // Recorded answers (names made up)

    @Test
    fun `a release search lists editions, without tracks and without bad ids`() {
        val releases = MusicBrainzJson.releases(fixture("release-search.json"))

        assertEquals(listOf(ID_A, ID_B, ID_C), releases.map { it.id })
        val first = releases[0]
        assertEquals("Lanterns of Ulso", first.title)
        assertEquals("Mira Solen", first.artist.text)
        assertEquals(GROUP_A, first.releaseGroup?.id)
        assertEquals(2011, first.year)
        assertEquals("XE", first.country)
        assertEquals(5, first.trackCount)
        assertEquals(listOf("CD"), first.formats)
        assertEquals(listOf(3, 2), first.media.map { it.trackCount })
        assertEquals(listOf(1, 2), first.media.map { it.position })
        assertEquals(100, first.score)
        assertFalse(first.hasAllTracks)
        assertNull(first.hasFront)
        assertFalse(first.artist.isVarious)
        assertTrue(releases[2].artist.isVarious)
        assertTrue(releases[2].releaseGroup!!.isCompilation)
        assertEquals(listOf("Digital Media"), releases[2].formats)
    }

    @Test
    fun `a release search says how many releases it found in all, beyond those it lists`() {
        val all = MusicBrainzJson.releaseHits(fixture("release-search-all.json"))
        assertEquals(listOf(ID_A, ID_B), all.releases.map { it.id })
        assertEquals(2, all.total)

        val more = MusicBrainzJson.releaseHits(fixture("release-search-more.json"))
        assertEquals(listOf(ID_A, ID_B), more.releases.map { it.id })
        assertEquals(54, more.total)
    }

    @Test
    fun `a release lists its discs, tracks, recordings, lengths and credits`() {
        val release = MusicBrainzJson.release(fixture("release.json"))

        assertEquals(ID_A, release.id)
        assertTrue(release.hasAllTracks)
        assertEquals(true, release.hasFront)
        assertEquals(1998, release.releaseGroup?.originalYear)
        assertEquals(2011, release.year)
        val tracks = release.tracks
        assertEquals(listOf("Quiet Ember", "Salt Road", "Nachtfähre", "Brennmoor", "Vael (Reprise)"), tracks.map { it.track.title })
        assertEquals(listOf(1, 1, 1, 2, 2), tracks.map { it.disc })
        assertEquals(listOf(1, 2, 3, 1, 2), tracks.map { it.track.position })
        assertEquals(listOf(1, 2, 3, 4, 5), tracks.map { it.index })
        assertEquals(listOf(3, 3, 3, 2, 2), tracks.map { it.discTracks })
        assertEquals("Mira Solen feat. Glass Harbour", tracks[1].track.artist.text)
        assertEquals(listOf("Mira Solen", "Glass Harbour"), tracks[1].track.artist.names)
        // A track without a length takes its recording's; one without a credit takes the release's.
        assertEquals(301_200L, tracks[2].track.lengthMs)
        assertEquals("Mira Solen", tracks[4].track.artist.text)
        assertEquals("e0e0e0e0-0000-4000-8000-0000000000b2", tracks[1].track.recordingId)
        assertEquals("f0f0f0f0-0000-4000-8000-0000000000c2", tracks[1].track.id)
    }

    @Test
    fun `a release group gives the original year`() {
        val group = MusicBrainzJson.releaseGroup(fixture("release-group.json"))
        assertEquals(GROUP_A, group.id)
        assertEquals(1998, group.originalYear)
        assertEquals("Album", group.primaryType)
        assertFalse(group.isCompilation)
    }

    @Test
    fun `a recording search lists each release with only the track found`() {
        val recordings = MusicBrainzJson.recordings(fixture("recording-search.json"))

        assertEquals(2, recordings.size)
        val first = recordings[0]
        assertEquals("Kestrel Lights", first.title)
        assertEquals(251_428L, first.lengthMs)
        assertEquals("Quillmere", first.artist.text)
        val (single, compilation) = first.releases
        // Without its own credit, a release has the recording's artist.
        assertEquals("Quillmere", single.artist.text)
        assertEquals("Single", single.releaseGroup?.primaryType)
        assertEquals(first.id, single.tracks.single().track.recordingId)
        assertEquals("Quillmere", single.tracks.single().track.artist.text)
        assertTrue(compilation.artist.isVarious)
        val track = compilation.tracks.single()
        assertEquals(2, track.disc)
        assertEquals(4, track.track.position)
        assertEquals(9, track.discTracks)
        assertEquals(18, compilation.trackCount)
        assertFalse(compilation.hasAllTracks)
    }

    @Test
    fun `an unreadable answer throws`() {
        listOf("<html>busy</html>", "", "[]").forEach { body ->
            assertThrows(body, IllegalArgumentException::class.java) { MusicBrainzJson.release(body.encodeToByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { MusicBrainzJson.release(fixture("busy.json")) }
        assertThrows(IllegalArgumentException::class.java) { MusicBrainzJson.releases("<html>".encodeToByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { MusicBrainzJson.release(fixture("release.json").decodeToString().replace(ID_A, "x").encodeToByteArray()) }
    }

    // Queries through the client

    private fun TestScope.musicBrainz(web: FakeWeb) = MusicBrainz(
        MetaClient(
            MetaCache(folder.newFolder()),
            userAgent = TEST_AGENT,
            gate = MetaGate(now = { testScheduler.currentTime }),
            io = StandardTestDispatcher(testScheduler),
            open = web::open,
        ),
    )

    @Test
    fun `an empty search is not cached, a release is`() = runTest {
        val web = FakeWeb()
        val search = MusicBrainzUrls.releaseSearch("Lanterns of Ulso", "Mira Solen", 5)
        web.on(search, ok("""{"count":0,"releases":[]}"""), ok(fixture("release-search.json")))
        web.on(MusicBrainzUrls.release(ID_A), ok(fixture("release.json")))
        val musicBrainz = musicBrainz(web)

        assertEquals(MetaAnswer.Ok(emptyList<MbRelease>()), musicBrainz.searchReleases("Lanterns of Ulso", "Mira Solen", 5))
        assertEquals(3, (musicBrainz.searchReleases("Lanterns of Ulso", "Mira Solen", 5) as MetaAnswer.Ok).value.size)
        assertTrue((musicBrainz.searchReleases("Lanterns of Ulso", "Mira Solen", 5) as MetaAnswer.Ok).fromCache)
        assertEquals(2, web.count(search))

        assertEquals(ID_A, (musicBrainz.release(ID_A) as MetaAnswer.Ok).value.id)
        assertTrue((musicBrainz.release(ID_A) as MetaAnswer.Ok).fromCache)
        assertTrue((musicBrainz.release(ID_A, fresh = true) as MetaAnswer.Ok).value.hasAllTracks)
        assertEquals(2, web.count(MusicBrainzUrls.release(ID_A)))
    }

    @Test
    fun `a bad id is never sent`() = runTest {
        val web = FakeWeb()
        val musicBrainz = musicBrainz(web)
        assertTrue(musicBrainz.release("../x") is MetaAnswer.Failed)
        assertTrue(musicBrainz.releaseGroup("x") is MetaAnswer.Failed)
        assertTrue(musicBrainz.front("x", CoverSize.FRONT_500) is MetaAnswer.Failed)
        assertEquals(emptyList<FakeWeb.Request>(), web.requests)
    }

    @Test
    fun `a cover that is not an image is refused and not cached`() = runTest {
        val web = FakeWeb()
        val front = MusicBrainzUrls.front(ID_A, CoverSize.FRONT_500)
        web.on(front, ok("<html>moved</html>"), ok(JPEG_BYTES))
        val musicBrainz = musicBrainz(web)

        assertTrue(musicBrainz.front(ID_A, CoverSize.FRONT_500) is MetaAnswer.Failed)
        assertTrue(musicBrainz.front(ID_A, CoverSize.FRONT_500) is MetaAnswer.Ok)
        assertEquals(2, web.count(URI(front.toString())))
    }
}
