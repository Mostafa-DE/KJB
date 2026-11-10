package app.lyrify.tags.lookup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumLookupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val logs = mutableListOf<String>()

    private val tracks = listOf(
        listOf(T("Quiet Ember", 254_000), T("Salt Road", 198_500), T("Nachtfähre", 301_200)),
        listOf(T("Brennmoor", 222_000), T("Vael (Reprise)", 187_000)),
    )

    private fun songs(releaseId: String? = null): List<PhoneSong> {
        var disc = 0
        return tracks.flatMap { list ->
            disc++
            list.mapIndexed { i, t ->
                PhoneSong(
                    "/storage/emulated/0/Music/Lanterns/${t.title}.mp3", t.title, "Mira Solen", "Lanterns of Ulso", "Mira Solen",
                    disc, i + 1, t.lengthMs!!, musicBrainzReleaseId = releaseId,
                )
            }
        }
    }

    private val album = PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs())
    private val search = MusicBrainzUrls.releaseSearch("Lanterns of Ulso", "Mira Solen", 5)

    private fun TestScope.web() = FakeWeb(now = { testScheduler.currentTime })

    private fun TestScope.lookup(web: FakeWeb, compute: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) = AlbumLookup(
        MusicBrainz(
            MetaClient(
                MetaCache(folder.newFolder()),
                userAgent = TEST_AGENT,
                gate = MetaGate(now = { testScheduler.currentTime }, log = { logs += it }),
                io = StandardTestDispatcher(testScheduler),
                open = web::open,
                log = { logs += it },
            ),
        ),
        log = { logs += it },
        compute = compute,
    )

    /** The usual answers: a search finding A and B (one release group, same tracks) and C (6 tracks, another group). */
    private fun FakeWeb.lanterns() {
        on(search, ok(searchJson(Triple(ID_C, 6, 100), Triple(ID_B, 5, 90), Triple(ID_A, 5, 95))))
        on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, discs = tracks)))
        on(MusicBrainzUrls.release(ID_B), ok(releaseJson(ID_B, discs = tracks)))
        on(MusicBrainzUrls.release(ID_C), ok(releaseJson(ID_C, group = GROUP_B, discs = listOf(tracks[0], tracks[1] + T("Kestrel Lights", 240_000)))))
    }

    private fun Lookup.proposal(): Proposal = (this as Lookup.Done).proposal

    @Test
    fun `an album is searched by title, artist and track count, then its best releases in detail`() = runTest {
        val web = web().apply { lanterns() }

        val proposal = lookup(web).check(album).proposal()

        assertEquals(ID_A, (proposal as Proposal.Ready).match.release.id)
        assertEquals(1998, proposal.match.release.releaseGroup?.originalYear)
        // The exact track count first, then MusicBrainz's score.
        assertEquals(
            listOf(search, MusicBrainzUrls.release(ID_A), MusicBrainzUrls.release(ID_B), MusicBrainzUrls.release(ID_C)).map { it.toString() },
            web.requests.map { it.uri },
        )
        assertTrue(web.requests.all { it.userAgent == TEST_AGENT })
    }

    @Test
    fun `every hit with the album's track count is asked for in detail`() = runTest {
        val web = web()
        val ids = (0 until 8).map { "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5f0$it" }
        web.on(search, ok(searchJson(*ids.map { Triple(it, 5, 100 - it.last().digitToInt()) }.toTypedArray())))
        ids.forEach { web.on(MusicBrainzUrls.release(it), ok(releaseJson(it, discs = tracks))) }

        lookup(web).check(album)

        assertEquals(ids.size + 1, web.requests.size)
    }

    @Test
    fun `a rival beyond the first five is never left out`() = runTest {
        val web = web()
        val twins = (0 until 6).map { "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5f0$it" }
        web.on(search, ok(searchJson(*(twins.map { Triple(it, 5, 100 - it.last().digitToInt()) } + Triple(ID_D, 5, 50)).toTypedArray())))
        twins.forEach { web.on(MusicBrainzUrls.release(it), ok(releaseJson(it, discs = tracks))) }
        // The same tracks in another release group: as close as the twins, so no release is sure.
        web.on(MusicBrainzUrls.release(ID_D), ok(releaseJson(ID_D, group = GROUP_B, discs = tracks)))

        val proposal = lookup(web).check(album).proposal()

        assertEquals(1, web.count(MusicBrainzUrls.release(ID_D)))
        assertTrue(Doubt.GAP in (proposal as Proposal.PickOne).doubts)
    }

    @Test
    fun `a search that lists every release with the album's track count can end Ready`() = runTest {
        val web = web()
        web.on(search, ok(fixture("release-search-all.json")))
        web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, discs = tracks)))
        web.on(MusicBrainzUrls.release(ID_B), ok(releaseJson(ID_B, discs = tracks)))

        assertTrue(lookup(web).check(album).proposal() is Proposal.Ready)
    }

    @Test
    fun `more releases with the album's track count than the search lists is Pick one`() = runTest {
        val web = web()
        web.on(search, ok(fixture("release-search-more.json")))
        web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, discs = tracks)))
        web.on(MusicBrainzUrls.release(ID_B), ok(releaseJson(ID_B, discs = tracks)))

        val proposal = lookup(web).check(album).proposal() as Proposal.PickOne

        assertTrue(Doubt.GAP in proposal.doubts)
        assertEquals(ID_A, proposal.candidates.first().release.id)
        assertEquals(listOf(search, MusicBrainzUrls.release(ID_A), MusicBrainzUrls.release(ID_B)).map { it.toString() }, web.requests.map { it.uri })
    }

    @Test
    fun `hits with another track count are asked for up to 5`() = runTest {
        val web = web()
        val anyCount = MusicBrainzUrls.releaseSearch("Lanterns of Ulso", "Mira Solen", null)
        val ids = (0 until 8).map { "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5f0$it" }
        web.on(search, ok("""{"count":0,"releases":[]}"""))
        web.on(anyCount, ok(searchJson(*ids.map { Triple(it, 6, 100) }.toTypedArray())))
        ids.forEach { web.on(MusicBrainzUrls.release(it), ok(releaseJson(it, discs = listOf(tracks[0], tracks[1] + T("Kestrel Lights", 240_000))))) }

        lookup(web).check(album)

        assertEquals(2 + AlbumLookup.MAX_DETAILED, web.requests.size)
    }

    @Test
    fun `matching runs on the compute dispatcher, never the caller's thread`() = runTest {
        val web = web().apply { lanterns() }
        val compute = CountingDispatcher(StandardTestDispatcher(testScheduler))

        lookup(web, compute).check(album)

        assertTrue(compute.dispatches > 0)
    }

    @Test
    fun `a release id already in the files is one more candidate, never trusted blindly`() = runTest {
        val web = web()
        web.on(search, ok(searchJson(Triple(ID_A, 5, 100))))
        web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, discs = tracks)))
        web.on(MusicBrainzUrls.release(ID_D), ok(releaseJson(ID_D, group = GROUP_B, title = "Brennmoor Nights", discs = listOf(List(5) { T("Other $it", 100_000) }))))

        val proposal = lookup(web).check(PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs(releaseId = ID_D))).proposal()

        assertEquals(1, web.count(MusicBrainzUrls.release(ID_D)))
        assertTrue(proposal is Proposal.PickOne)
        assertEquals(listOf(ID_A, ID_D), (proposal as Proposal.PickOne).candidates.map { it.release.id })
    }

    @Test
    fun `an id that is not a MusicBrainz id is never sent`() = runTest {
        val web = web().apply { lanterns() }
        lookup(web).check(PhoneAlbum("Lanterns of Ulso", "Mira Solen", songs(releaseId = "../../ws/2/x")))
        assertEquals(4, web.requests.size)
    }

    @Test
    fun `with no edition of that many tracks, the search goes again without the count`() = runTest {
        val web = web()
        val anyCount = MusicBrainzUrls.releaseSearch("Lanterns of Ulso", "Mira Solen", null)
        web.on(search, ok("""{"count":0,"releases":[]}"""))
        web.on(anyCount, ok(searchJson(Triple(ID_C, 6, 100))))
        web.on(MusicBrainzUrls.release(ID_C), ok(releaseJson(ID_C, discs = listOf(tracks[0], tracks[1] + T("Kestrel Lights", 240_000)))))

        val proposal = lookup(web).check(album).proposal() as Proposal.PickOne

        assertEquals(listOf(search, anyCount, MusicBrainzUrls.release(ID_C)).map { it.toString() }, web.requests.map { it.uri })
        assertTrue(Doubt.TRACK_COUNT in proposal.doubts)
        assertEquals("5 of 5 songs fit", proposal.candidates.single().fit.line)
    }

    @Test
    fun `nothing found is Pick one with nothing to pick`() = runTest {
        val web = web()
        web.on(search, ok("""{"count":0,"releases":[]}"""))
        assertEquals(Proposal.PickOne(emptyList(), setOf(Doubt.NO_CANDIDATE)), lookup(web).check(album).proposal())
    }

    @Test
    fun `a failed request never yields Ready on the answers that came`() = runTest {
        val web = web().apply { lanterns() }
        web.on(MusicBrainzUrls.release(ID_B), status(500))
        assertTrue(lookup(web).check(album) is Lookup.Failed)

        val offline = web()
        offline.on(search, FakeWeb.Reply(200, error = IOException("offline")))
        assertTrue(lookup(offline).check(album) is Lookup.Failed)
    }

    @Test
    fun `MusicBrainz busy through every backoff is busy`() = runTest {
        val web = web()
        web.on(search, status(503))
        assertEquals(Lookup.Busy, lookup(web).check(album))
        assertEquals(MetaGate.BACKOFF_MS.size + 1, web.requests.size)
    }

    @Test
    fun `Check again skips the cache for that album`() = runTest {
        val web = web().apply { lanterns() }
        val lookup = lookup(web)

        lookup.check(album)
        lookup.check(album)
        assertEquals(4, web.requests.size)

        assertTrue(lookup.check(album, fresh = true).proposal() is Proposal.Ready)
        assertEquals(8, web.requests.size)
    }

    @Test
    fun `the original year is asked of the release group only when the release lacks it`() = runTest {
        val web = web()
        web.on(search, ok(searchJson(Triple(ID_A, 5, 100))))
        web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, discs = tracks, firstReleaseDate = null)))
        web.on(MusicBrainzUrls.releaseGroup(GROUP_A), ok(fixture("release-group.json")))

        val proposal = lookup(web).check(album).proposal() as Proposal.Ready

        assertEquals(1998, proposal.match.release.releaseGroup?.originalYear)
        assertEquals(1, web.count(MusicBrainzUrls.releaseGroup(GROUP_A)))
        assertEquals(1998, TagChanges.of(album, proposal.match).songs.first().fields.year)
    }

    @Test
    fun `a release group that cannot be asked for keeps the album Ready, without its original year`() = runTest {
        listOf(status(500), status(503)).forEach { failure ->
            val web = web()
            web.on(search, ok(searchJson(Triple(ID_A, 5, 100))))
            web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, discs = tracks, firstReleaseDate = null)))
            web.on(MusicBrainzUrls.releaseGroup(GROUP_A), failure)

            val proposal = lookup(web).check(album).proposal()

            assertTrue(proposal.toString(), proposal is Proposal.Ready)
            assertEquals(null, (proposal as Proposal.Ready).match.release.releaseGroup?.originalYear)
            assertTrue(TagChanges.of(album, proposal.match).songs.all { it.fields.year == null })
        }
    }

    @Test
    fun `a single is looked up by a recording search and always goes to Pick one`() = runTest {
        val web = web()
        val single = PhoneAlbum(null, null, listOf(PhoneSong("/storage/emulated/0/Download/k.mp3", "Kestrel Lights (Official Video)", "Quillmere", null, null, null, null, 251_400)))
        val recordingSearch = MusicBrainzUrls.recordingSearch("Kestrel Lights", "Quillmere", 251_400)
        web.on(recordingSearch, ok(fixture("recording-search.json")))
        val group = "6f1e2d3c-4b5a-4968-8776-5a4b3c2d1e09"
        web.on(MusicBrainzUrls.releaseGroup(group), ok("""{"id":"$group","first-release-date":"2020-03-06","primary-type":"Single"}"""))
        val lookup = lookup(web)

        val proposal = lookup.check(single).proposal() as Proposal.PickOne

        assertEquals(setOf(Doubt.SINGLE), proposal.doubts)
        assertEquals(3, proposal.candidates.size)
        assertEquals(listOf(recordingSearch.toString()), web.requests.map { it.uri })

        val dated = lookup.withOriginalYear(proposal.candidates.first())
        assertEquals(2020, (dated as MetaAnswer.Ok).value.release.releaseGroup?.originalYear)
        assertEquals(1, web.count(MusicBrainzUrls.releaseGroup(group)))
    }

    @Test
    fun `Search myself uses the user's words and is always Pick one`() = runTest {
        val web = web().apply { lanterns() }
        val untitled = PhoneAlbum("Unknown album", "Unknown artist", songs())
        val proposal = lookup(web).search(untitled, "Lanterns of Ulso", "Mira Solen").proposal() as Proposal.PickOne
        assertTrue(Doubt.SEARCHED in proposal.doubts)
        assertEquals(ID_A, proposal.candidates.first().release.id)
        assertEquals(search.toString(), web.requests.first().uri)
    }

    @Test
    fun `the cover is the 1200 px image, else the 500 px one, and nothing is asked when there is none`() = runTest {
        val web = web()
        val release = MusicBrainzJson.release(fixture("release.json"))
        web.on(MusicBrainzUrls.front(ID_A, CoverSize.FRONT_1200), status(404))
        web.on(MusicBrainzUrls.front(ID_A, CoverSize.FRONT_500), ok(JPEG_BYTES))
        val lookup = lookup(web)

        assertArrayEquals(JPEG_BYTES, (lookup.cover(release) as MetaAnswer.Ok).value)
        assertEquals(
            listOf(MusicBrainzUrls.front(ID_A, CoverSize.FRONT_1200), MusicBrainzUrls.front(ID_A, CoverSize.FRONT_500)).map { it.toString() },
            web.requests.map { it.uri },
        )

        assertEquals(MetaAnswer.NotFound, lookup.cover(release.copy(hasFront = false)))
        assertEquals(2, web.requests.size)

        web.on(MusicBrainzUrls.front(ID_A, CoverSize.FRONT_1200), ok(JPEG_BYTES))
        assertTrue(lookup.cover(release, fresh = true) is MetaAnswer.Ok)
        assertEquals(MusicBrainzUrls.front(ID_A, CoverSize.FRONT_1200).toString(), web.requests.last().uri)
    }

    @Test
    fun `no log line holds a name, a path or an address`() = runTest {
        val web = web().apply { lanterns() }
        web.on(search, status(503), ok(searchJson(Triple(ID_A, 5, 100), Triple(ID_B, 5, 90))))
        web.on(MusicBrainzUrls.release(ID_B), FakeWeb.Reply(200, error = IOException("https://musicbrainz.org/ws/2/release/$ID_B Lanterns of Ulso")))
        web.on(MusicBrainzUrls.front(ID_A, CoverSize.FRONT_1200), redirect(307, "https://example.com/Lanterns%20of%20Ulso.jpg"))
        val lookup = lookup(web)

        lookup.check(album)
        lookup.check(album, fresh = true)
        web.on(MusicBrainzUrls.release(ID_B), ok(releaseJson(ID_B, discs = tracks)))
        lookup.check(album)
        lookup.cover(MusicBrainzJson.release(fixture("release.json")))
        lookup.check(PhoneAlbum(null, null, listOf(PhoneSong("/storage/emulated/0/Download/Kestrel.mp3", "Kestrel Lights", "Quillmere", null, null, null, null, 251_400))))

        assertTrue(logs.size >= 5)
        val forbidden = listOf("Lanterns", "Ulso", "Mira", "Solen", "Kestrel", "Quillmere", "Ember", "/storage", ".mp3", "http", ".org", ".com", ID_A, ID_B, GROUP_A)
        logs.forEach { line -> forbidden.forEach { assertFalse("\"$it\" in: $line", it in line) } }
    }

    @Test
    fun `the app has one lookup and one MusicBrainz client, built in TagsGraph, so one gate spaces every request`() {
        // Unit tests run in the module's folder.
        val graph = java.io.File("src/main/java/app/lyrify/tags/TagsGraph.kt").readText()
        assertTrue(graph.contains("fun lookup(context: Context): AlbumLookup"))
        val built = Regex("""\b(AlbumLookup|MetaClient)\(""")
        fun builds(line: String) = built.containsMatchIn(line) && !line.trimStart().startsWith("class ") && !line.trimStart().startsWith("*")
        val builders = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && it.readLines().any(::builds) }.map { it.name }.toSet()
        assertEquals(setOf("TagsGraph.kt"), builders)
    }
}
