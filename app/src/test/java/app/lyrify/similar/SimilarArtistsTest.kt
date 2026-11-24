package app.lyrify.similar

import app.lyrify.suggestions.SimilarArtist
import app.lyrify.tags.AudioFormat
import app.lyrify.tags.EditKind
import app.lyrify.tags.FieldChange
import app.lyrify.tags.HistoryEntry
import app.lyrify.tags.Payload
import app.lyrify.tags.SongEdit
import app.lyrify.tags.lookup.FakeWeb
import app.lyrify.tags.lookup.ID_A
import app.lyrify.tags.lookup.ID_B
import app.lyrify.tags.lookup.MbArtist
import app.lyrify.tags.lookup.MetaAnswer
import app.lyrify.tags.lookup.MetaCache
import app.lyrify.tags.lookup.MetaClient
import app.lyrify.tags.lookup.MetaGate
import app.lyrify.tags.lookup.MusicBrainz
import app.lyrify.tags.lookup.MusicBrainzJson
import app.lyrify.tags.lookup.MusicBrainzUrls
import app.lyrify.tags.lookup.Names
import app.lyrify.tags.lookup.T
import app.lyrify.tags.lookup.TEST_AGENT
import app.lyrify.tags.lookup.ok
import app.lyrify.tags.lookup.releaseJson
import app.lyrify.tags.lookup.status
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.Executor

/**
 * Similar artists: the lookups' pacing, backoff, exact-match rule, ageing, kill flag and offline behaviour, through the
 * real MetaClient over a scripted web (no socket is ever opened), and the cache suggestions read. Made-up names only.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class SimilarArtistsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val logs = mutableListOf<String>()

    /** Added to the clock between runs: days passing. */
    private var later = 0L

    private val file: File by lazy { File(folder.root, "similar-artists.json") }

    private fun TestScope.now(): Long = BASE + later + testScheduler.currentTime

    private fun TestScope.web() = FakeWeb(now = { testScheduler.currentTime })

    private fun TestScope.client(web: FakeWeb) = MetaClient(
        MetaCache(folder.newFolder()),
        userAgent = TEST_AGENT,
        gate = MetaGate(now = { testScheduler.currentTime }, log = { logs += it }),
        io = StandardTestDispatcher(testScheduler),
        open = web::open,
        log = { logs += it },
    )

    private fun TestScope.cache() = SimilarCache(SimilarStore(file) { logs += it }, io = StandardTestDispatcher(testScheduler))

    private fun TestScope.lookups(cache: SimilarCache, client: MetaClient, version: Int = VERSION) =
        SimilarLookups(cache, MusicBrainz(client), client, version, now = { now() }, log = { logs += it })

    private fun TestScope.lookups(web: FakeWeb, cache: SimilarCache, version: Int = VERSION) = lookups(cache, client(web), version)

    private fun played(vararg names: String) = names.map { PlayedArtist(it) }

    /** [name] found for sure by its search, with [neighbours] on ListenBrainz. */
    private fun FakeWeb.artist(name: String, id: String, vararg neighbours: Neighbour) {
        on(MusicBrainzUrls.artistSearch(name), ok(artistsJson(Hit(id, name, 100))))
        on(ListenBrainzUrls.similarArtists(id), ok(neighboursJson(id, *neighbours)))
    }

    private fun day(days: Long): Long = days * 24 * 60 * 60 * 1_000

    // A run

    @Test
    fun `an artist is searched by its name as plain words, then ListenBrainz lists its neighbours, kept on the phone`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), ok(artistsJson(Hit(MIRA, "Mira Solen", 100), Hit(OTHER, "Mira Solenne", 61))))
        web.on(ListenBrainzUrls.similarArtists(MIRA), ok(neighboursJson(MIRA, Neighbour(GLASS, "Glass Harbour", 900), Neighbour(KIT, "Kit Calloway", 450))))
        val cache = cache()

        lookups(web, cache).run(played("Mira Solen"))

        assertEquals(
            listOf(
                "https://musicbrainz.org/ws/2/artist/?query=Mira+Solen&fmt=json&limit=10",
                "https://labs.api.listenbrainz.org/similar-artists/json?artist_mbids=$MIRA" +
                    "&algorithm=session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30",
            ),
            web.requests.map { it.uri },
        )
        assertTrue(web.requests.all { it.userAgent == TEST_AGENT })
        val expected = mapOf("Mira Solen" to listOf(SimilarArtist("Glass Harbour", 1f), SimilarArtist("Kit Calloway", 0.5f)))
        assertEquals(expected, cache.similar(listOf("Mira Solen")))
        // Kept on the phone: read again after KJB closes.
        assertEquals(expected, cache().similar(listOf("Mira Solen")))
    }

    @Test
    fun `a run sends one request at a time, each 3 s after the previous one`() = runTest {
        val web = web()
        web.artist("Mira Solen", MIRA, Neighbour(GLASS, "Glass Harbour", 10))
        web.artist("Glass Harbour", GLASS, Neighbour(MIRA, "Mira Solen", 10))
        web.artist("Kit Calloway", KIT, Neighbour(MIRA, "Mira Solen", 10))

        lookups(web, cache()).run(played("Mira Solen", "Glass Harbour", "Kit Calloway"))

        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L, 15_000L), web.requests.map { it.at })
    }

    @Test
    fun `at most 20 artists are looked up in one run, the most played first`() = runTest {
        val web = web()
        val names = (1..25).map { "Made-up Artist $it" }
        names.forEach { web.on(MusicBrainzUrls.artistSearch(it), ok(artistsJson())) }

        lookups(web, cache()).run(played(*names.toTypedArray()))

        assertEquals(names.take(20).map { MusicBrainzUrls.artistSearch(it).toString() }, web.requests.map { it.uri })
    }

    // Busy, down, offline

    @Test
    fun `a MusicBrainz 503 is waited out with the shared gate's backoff, then the run goes on`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), status(503), ok(artistsJson(Hit(MIRA, "Mira Solen", 100))))
        web.on(ListenBrainzUrls.similarArtists(MIRA), ok(neighboursJson(MIRA, Neighbour(GLASS, "Glass Harbour", 10))))
        val cache = cache()

        lookups(web, cache).run(played("Mira Solen"))

        assertEquals(listOf(0L, 2_000L, 5_000L), web.requests.map { it.at })
        assertEquals(1, cache.similar(listOf("Mira Solen")).size)
        assertEquals(0, cache.memory().failures)
    }

    @Test
    fun `MusicBrainz still busy after the gate's backoff pauses the lookups for a day, and nothing is asked meanwhile`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), status(503))
        web.artist("Glass Harbour", GLASS)
        val cache = cache()
        val lookups = lookups(web, cache)

        lookups.run(played("Mira Solen", "Glass Harbour"))

        // The gate asked 7 times (2 s up to 60 s apart), then the run stopped: Glass Harbour waits.
        assertEquals(7, web.requests.size)
        assertEquals(1, cache.memory().failures)
        assertEquals(now() + day(1), cache.memory().pausedUntil)
        later += day(1) - 1
        lookups.run(played("Mira Solen", "Glass Harbour"))
        assertEquals(7, web.requests.size)
        later += 1
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), ok(artistsJson()))
        lookups.run(played("Mira Solen", "Glass Harbour"))
        assertEquals(10, web.requests.size)
        assertEquals(0, cache.memory().failures)
    }

    @Test
    fun `ListenBrainz busy or refusing pauses for a day, then two, and a list fetched ends the pauses`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), ok(artistsJson(Hit(MIRA, "Mira Solen", 100))))
        val similar = ListenBrainzUrls.similarArtists(MIRA)
        web.on(similar, status(503), status(429), ok(neighboursJson(MIRA, Neighbour(GLASS, "Glass Harbour", 10))))
        val cache = cache()
        val lookups = lookups(web, cache)

        lookups.run(played("Mira Solen"))
        assertEquals(1, web.count(similar))
        assertEquals(now() + day(1), cache.memory().pausedUntil)
        later += day(1)
        lookups.run(played("Mira Solen"))
        assertEquals(2, web.count(similar))
        assertEquals(2, cache.memory().failures)
        assertEquals(now() + day(2), cache.memory().pausedUntil)
        later += day(2)
        lookups.run(played("Mira Solen"))

        assertEquals(3, web.count(similar))
        assertEquals(0, cache.memory().failures)
        assertEquals(listOf(SimilarArtist("Glass Harbour", 1f)), cache.similar(listOf("Mira Solen"))["Mira Solen"])
    }

    @Test
    fun `the pauses double from 1 day up to 14 days`() {
        assertEquals((1..7).map { day(listOf(1L, 2, 4, 8, 14, 14, 14)[it - 1]) }, (1..7).map(SimilarRules::pauseAfter))
    }

    @Test
    fun `offline, a run fails quietly and pauses, and suggestions still read the cache`() = runTest {
        val web = web()
        web.artist("Mira Solen", MIRA, Neighbour(GLASS, "Glass Harbour", 10))
        val cache = cache()
        lookups(web, cache).run(played("Mira Solen"))
        later += day(SimilarRules.REFRESH_DAYS)
        val offline = FakeWeb.Reply(200, error = UnknownHostException("made-up"))
        web.on(ListenBrainzUrls.similarArtists(MIRA), offline)
        web.on(MusicBrainzUrls.artistSearch("Glass Harbour"), offline)

        lookups(web, cache).run(played("Mira Solen", "Glass Harbour"))

        assertEquals(1, cache.memory().failures)
        assertEquals(mapOf("Mira Solen" to listOf(SimilarArtist("Glass Harbour", 1f))), cache.similar(listOf("Mira Solen", "Glass Harbour")))
    }

    // The kill flag

    @Test
    fun `ListenBrainz gone for 3 artists in a row that had lists stops the lookups until the next app version, the cache still read`() = runTest {
        val web = web()
        web.artist("Rowan Vale", ROWAN, Neighbour(OTHER, "Glass Harbour", 10))
        val names = listOf(MIRA to 404, GLASS to 410, KIT to 401).mapIndexed { i, (id, code) ->
            web.on(MusicBrainzUrls.artistSearch("Made-up Artist $i"), ok(artistsJson(Hit(id, "Made-up Artist $i", 100))))
            web.on(ListenBrainzUrls.similarArtists(id), ok(neighboursJson(id, Neighbour(ROWAN, "Rowan Vale", 10))), status(code))
            "Made-up Artist $i"
        }
        web.artist("Tamsin Reed", TAMSIN, Neighbour(ROWAN, "Rowan Vale", 10))
        val cache = cache()
        lookups(web, cache).run(played("Rowan Vale", *names.toTypedArray()))
        later += day(SimilarRules.REFRESH_DAYS)

        lookups(web, cache).run(played(*names.toTypedArray(), "Tamsin Reed"))

        assertEquals(VERSION, cache.memory().stoppedIn)
        assertEquals(0, web.count(MusicBrainzUrls.artistSearch("Tamsin Reed")))
        val asked = web.requests.size
        later += day(30)
        lookups(web, cache).run(played("Tamsin Reed"))
        assertEquals(asked, web.requests.size)
        assertEquals(mapOf("Rowan Vale" to listOf(SimilarArtist("Glass Harbour", 1f))), cache.similar(listOf("Rowan Vale", "Tamsin Reed")))
        // The next version tries again.
        lookups(web, cache, version = VERSION + 1).run(played("Tamsin Reed"))
        assertEquals(asked + 2, web.requests.size)
        assertNull(cache.memory().stoppedIn)
    }

    @Test
    fun `a list fetched between two gone answers starts the count again`() = runTest {
        val web = web()
        val names = listOf(MIRA to 404, GLASS to 200, KIT to 403, ROWAN to 404).mapIndexed { i, (id, code) ->
            web.on(MusicBrainzUrls.artistSearch("Made-up Artist $i"), ok(artistsJson(Hit(id, "Made-up Artist $i", 100))))
            val first = ok(neighboursJson(id, Neighbour(TAMSIN, "Tamsin Reed", 10)))
            web.on(ListenBrainzUrls.similarArtists(id), first, if (code == 200) ok(neighboursJson(id)) else status(code))
            "Made-up Artist $i"
        }
        val cache = cache()
        lookups(web, cache).run(played(*names.toTypedArray()))
        later += day(SimilarRules.REFRESH_DAYS)

        lookups(web, cache).run(played(*names.toTypedArray()))

        assertNull(cache.memory().stoppedIn)
        assertEquals(2, cache.memory().gone)
        assertEquals(12, web.requests.size)
    }

    @Test
    fun `a gone answer for an artist that never had a list is no list for it, kept as such, and neither counts nor resets the count`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Rowan Vale"), ok(artistsJson(Hit(ROWAN, "Rowan Vale", 100))))
        web.on(ListenBrainzUrls.similarArtists(ROWAN), ok(neighboursJson(ROWAN, Neighbour(GLASS, "Glass Harbour", 10))), status(404))
        listOf(MIRA to 404, GLASS to 410, KIT to 403).forEachIndexed { i, (id, code) ->
            web.on(MusicBrainzUrls.artistSearch("Made-up Artist $i"), ok(artistsJson(Hit(id, "Made-up Artist $i", 100))))
            web.on(ListenBrainzUrls.similarArtists(id), status(code))
        }
        val cache = cache()
        lookups(web, cache).run(played("Rowan Vale"))
        later += day(SimilarRules.REFRESH_DAYS)

        lookups(web, cache).run(played("Rowan Vale", "Made-up Artist 0", "Made-up Artist 1", "Made-up Artist 2"))

        val memory = cache.memory()
        assertEquals(1, memory.gone)
        assertNull(memory.stoppedIn)
        assertEquals(0, memory.failures)
        assertEquals(9, web.requests.size)
        val kept = (0..2).map { memory.artists[Names.key("Made-up Artist $it")] }
        assertEquals(listOf(MIRA, GLASS, KIT), kept.map { it?.mbid })
        assertTrue(kept.all { it != null && it.match == Match.FOUND && it.neighbours.isEmpty() && !SimilarRules.due(it, now()) })
        assertEquals(emptyMap<String, List<SimilarArtist>>(), cache.similar(listOf("Made-up Artist 0", "Made-up Artist 1", "Made-up Artist 2")))
    }

    @Test
    fun `ListenBrainz's 400 for an artist is that artist's own failure, its list kept, never a step towards the stop`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), ok(artistsJson(Hit(MIRA, "Mira Solen", 100))))
        web.on(ListenBrainzUrls.similarArtists(MIRA), ok(neighboursJson(MIRA, Neighbour(GLASS, "Glass Harbour", 10))), status(400))
        val cache = cache()
        val lookups = lookups(web, cache)
        lookups.run(played("Mira Solen"))
        later += day(SimilarRules.REFRESH_DAYS)

        lookups.run(played("Mira Solen"))

        val memory = cache.memory()
        assertEquals(0, memory.gone)
        assertEquals(0, memory.failures)
        assertEquals(1, memory.artists[Names.key("Mira Solen")]?.failures)
        assertEquals(listOf(SimilarArtist("Glass Harbour", 1f)), cache.similar(listOf("Mira Solen"))["Mira Solen"])
    }

    // An artist that keeps failing

    @Test
    fun `an artist whose lookup fails on its own is tried again later on its own, its list kept, and the run goes on`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), status(400))
        web.artist("Glass Harbour", GLASS, Neighbour(ROWAN, "Rowan Vale", 10))
        web.on(MusicBrainzUrls.artistSearch("Kit Calloway"), ok(artistsJson(Hit(KIT, "Kit Calloway", 100))))
        // Then an answer that does not read: a score that is not a whole number.
        val unreadable = ok("""[{"artist_mbid":"$ROWAN","name":"Rowan Vale","score":1.5,"reference_mbid":"$KIT"}]""")
        web.on(ListenBrainzUrls.similarArtists(KIT), ok(neighboursJson(KIT, Neighbour(ROWAN, "Rowan Vale", 10))), unreadable)
        val cache = cache()
        val lookups = lookups(web, cache)
        val everyone = played("Mira Solen", "Kit Calloway", "Glass Harbour")
        lookups.run(played("Kit Calloway"))
        later += day(SimilarRules.REFRESH_DAYS)

        lookups.run(everyone)

        val memory = cache.memory()
        assertEquals(listOf(1, 1, 0), everyone.map { memory.artists[Names.key(it.name)]?.failures })
        assertEquals(0, memory.failures)
        assertEquals(0L, memory.pausedUntil)
        assertEquals(0, memory.gone)
        val rowan = listOf(SimilarArtist("Rowan Vale", 1f))
        assertEquals(mapOf("Kit Calloway" to rowan, "Glass Harbour" to rowan), cache.similar(everyone.map { it.name }))
        val asked = web.requests.size
        later += day(1)
        lookups.run(everyone)
        assertEquals(
            listOf(MusicBrainzUrls.artistSearch("Mira Solen"), ListenBrainzUrls.similarArtists(KIT)).map { it.toString() },
            web.requests.drop(asked).map { it.uri },
        )
    }

    @Test
    fun `an artist's own failures wait 1 day, then 7, then 30, then its 60-day refresh`() {
        val entry = ArtistSimilar("Mira Solen", Match.NONE, at = BASE)
        listOf(1 to 1L, 2 to 7L, 3 to 30L, 4 to 60L, 9 to 60L).forEach { (failures, days) ->
            val failed = entry.copy(failures = failures)
            assertEquals("$failures failures", false, SimilarRules.due(failed, BASE + day(days) - 1))
            assertTrue("$failures failures", SimilarRules.due(failed, BASE + day(days)))
        }
    }

    @Test
    fun `three artists failing on their own in a row end the run and pause it, never a step towards the stop`() = runTest {
        val web = web()
        val names = (0..2).map { "Made-up Artist $it" }
        names.forEach { web.on(MusicBrainzUrls.artistSearch(it), status(400)) }
        web.artist("Tamsin Reed", TAMSIN, Neighbour(ROWAN, "Rowan Vale", 10))
        val cache = cache()

        lookups(web, cache).run(played(*names.toTypedArray(), "Tamsin Reed"))

        val memory = cache.memory()
        assertEquals(3, web.requests.size)
        assertEquals(1, memory.failures)
        assertEquals(now() + day(1), memory.pausedUntil)
        assertEquals(0, memory.gone)
        assertNull(memory.stoppedIn)
        assertEquals(listOf(1, 1, 1), names.map { memory.artists[Names.key(it)]?.failures })
    }

    @Test
    fun `a failure is an artist's own only when the service answered it plainly, never busy, refusing KJB or offline`() {
        val own = listOf(MetaAnswer.NotFound) + listOf(400, 414, 422, 200).map { MetaAnswer.Failed("HTTP $it", it) }
        val service = listOf(MetaAnswer.Busy, MetaAnswer.Failed("UnknownHostException"), MetaAnswer.Failed("redirect refused")) +
            listOf(401, 403, 407, 408, 429, 500, 502).map { MetaAnswer.Failed("HTTP $it", it) }

        assertEquals(own.map { true }, own.map(SimilarRules::ownFailure))
        assertEquals(service.map { false }, service.map(SimilarRules::ownFailure))
    }

    // The exact-match rule

    @Test
    fun `a name matches only as the top hit at score 100, by name, sort name or alias, with every other hit at 85 or lower`() {
        fun match(vararg hits: MbArtist) = SimilarRules.match(hits.toList(), "Mira Solen")
        assertEquals(Match.FOUND to MIRA, match(artist(MIRA, "Mira Solen", 100), artist(OTHER, "Mira Sol", 80)))
        assertEquals(Match.FOUND to MIRA, match(artist(MIRA, "ميرا سولن", 100, aliases = listOf("Mira Solen"))))
        assertEquals(Match.FOUND to MIRA, match(artist(MIRA, "ميرا سولن", 100, sortName = "Mira Solen")))
        assertEquals(Match.FOUND to MIRA, match(artist(MIRA, "ميرا سولن", 100, sortName = "Solen, Mira")))
        assertEquals(Match.FOUND to MIRA, match(artist(MIRA, "Mira Solen", 100), artist(OTHER, "Mira Solen", 85)))
        assertEquals(Match.AMBIGUOUS to null, match(artist(MIRA, "Mira Solen", 100), artist(OTHER, "Mira Solen", 86)))
        assertEquals(Match.AMBIGUOUS to null, match(artist(MIRA, "Mira Solen", 100), artist(OTHER, "Mira Sol", 94)))
        assertEquals(Match.AMBIGUOUS to null, match(artist(MIRA, "Mira Solen", 100), artist(OTHER, "Mira Solen", 100)))
        assertEquals(Match.NONE to null, match(artist(MIRA, "Mira Solen", 98)))
        assertEquals(Match.NONE to null, match(artist(MIRA, "Mira Solenne", 100)))
        assertEquals(Match.NONE to null, match(artist(MIRA, "Mira Solen", null)))
        assertEquals(Match.NONE to null, match())
    }

    @Test
    fun `an ambiguous or unmatched name never goes to ListenBrainz and is searched again only after 60 days`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), ok(artistsJson(Hit(MIRA, "Mira Solen", 100), Hit(OTHER, "Mira Solen", 98))))
        web.on(MusicBrainzUrls.artistSearch("Glass Harbour"), ok(artistsJson(Hit(GLASS, "Glass Harbor Band", 100))))
        val cache = cache()
        val lookups = lookups(web, cache)

        lookups.run(played("Mira Solen", "Glass Harbour"))
        assertEquals(2, web.requests.size)
        assertEquals(Match.AMBIGUOUS, cache.memory().artists.values.firstOrNull { it.name == "Mira Solen" }?.match)
        assertEquals(Match.NONE, cache.memory().artists.values.firstOrNull { it.name == "Glass Harbour" }?.match)
        later += day(SimilarRules.REFRESH_DAYS - 1)
        lookups.run(played("Mira Solen", "Glass Harbour"))
        assertEquals(2, web.requests.size)
        later += day(1)
        lookups.run(played("Mira Solen", "Glass Harbour"))

        assertEquals(4, web.requests.size)
        assertTrue(web.requests.none { "listenbrainz" in it.uri })
    }

    // Ageing

    @Test
    fun `a list is fetched again after 60 days with the id already found, never before`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.artistSearch("Mira Solen"), ok(artistsJson(Hit(MIRA, "Mira Solen", 100))))
        val similar = ListenBrainzUrls.similarArtists(MIRA)
        web.on(similar, ok(neighboursJson(MIRA, Neighbour(GLASS, "Glass Harbour", 10))), ok(neighboursJson(MIRA, Neighbour(KIT, "Kit Calloway", 7))))
        val cache = cache()
        val lookups = lookups(web, cache)

        lookups.run(played("Mira Solen"))
        later += day(SimilarRules.REFRESH_DAYS) - 1
        lookups.run(played("Mira Solen"))
        assertEquals(2, web.requests.size)
        later += 1
        lookups.run(played("Mira Solen"))

        assertEquals(listOf(MusicBrainzUrls.artistSearch("Mira Solen"), similar, similar).map { it.toString() }, web.requests.map { it.uri })
        assertEquals(listOf(SimilarArtist("Kit Calloway", 1f)), cache.similar(listOf("Mira Solen"))["Mira Solen"])
    }

    @Test
    fun `an artist is due when never looked up, 60 days on, or looked up at a time still to come`() {
        val at = BASE
        val entry = ArtistSimilar("Mira Solen", Match.FOUND, MIRA, at = at)
        assertTrue(SimilarRules.due(null, at))
        assertEquals(false, SimilarRules.due(entry, at + day(SimilarRules.REFRESH_DAYS) - 1))
        assertTrue(SimilarRules.due(entry, at + day(SimilarRules.REFRESH_DAYS)))
        assertTrue(SimilarRules.due(entry, at - day(1)))
    }

    @Test
    fun `a new algorithm drops the lists, keeps the ids, and every artist is due again`() {
        val memory = SimilarMemory(
            artists = mapOf("mira solen" to ArtistSimilar("Mira Solen", Match.FOUND, MIRA, listOf(Neighbour(GLASS, "Glass Harbour", 10)), at = BASE)),
            algorithm = "an_older_algorithm",
        )

        val now = SimilarRules.forAlgorithm(memory, ListenBrainzUrls.ALGORITHM)

        assertEquals(ListenBrainzUrls.ALGORITHM, now.algorithm)
        val entry = now.artists.getValue("mira solen")
        assertEquals(MIRA, entry.mbid)
        assertEquals(emptyList<Neighbour>(), entry.neighbours)
        assertTrue(SimilarRules.due(entry, BASE))
        val current = memory.copy(algorithm = ListenBrainzUrls.ALGORITHM)
        assertEquals(current, SimilarRules.forAlgorithm(current, ListenBrainzUrls.ALGORITHM))
    }

    // Ids KJB wrote

    @Test
    fun `an artist whose songs Covers & tags tagged is matched by their ids, from its cache, without a search`() = runTest {
        val web = web()
        val client = client(web)
        val tracks = listOf(listOf(T("Quiet Ember", 254_000), T("Salt Road", 198_500)))
        web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, artist = "Mira Solen", discs = tracks)))
        // Covers & tags looked the release up when it tagged the album.
        assertTrue(MusicBrainz(client).release(ID_A) is MetaAnswer.Ok)
        web.on(ListenBrainzUrls.similarArtists(CREDITED), ok(neighboursJson(CREDITED, Neighbour(GLASS, "Glass Harbour", 10))))
        val cache = cache()
        val before = web.requests.size

        lookups(cache, client).run(listOf(PlayedArtist("Mira Solen", WrittenIds(ID_A, recording = null))))

        assertEquals(listOf(ListenBrainzUrls.similarArtists(CREDITED).toString()), web.requests.drop(before).map { it.uri })
        assertEquals(CREDITED, cache.memory().artists.values.singleOrNull()?.mbid)
    }

    @Test
    fun `the ids KJB wrote win over an earlier match by name`() = runTest {
        val web = web()
        web.artist("Mira Solen", MIRA, Neighbour(GLASS, "Glass Harbour", 10))
        web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, artist = "Mira Solen", discs = listOf(listOf(T("Quiet Ember", 254_000))))))
        web.on(ListenBrainzUrls.similarArtists(CREDITED), ok(neighboursJson(CREDITED, Neighbour(KIT, "Kit Calloway", 10))))
        val cache = cache()
        lookups(web, cache).run(played("Mira Solen"))
        later += day(SimilarRules.REFRESH_DAYS)

        lookups(web, cache).run(listOf(PlayedArtist("Mira Solen", WrittenIds(ID_A, recording = null))))

        assertEquals(1, web.count(ListenBrainzUrls.similarArtists(CREDITED)))
        assertEquals(CREDITED, cache.memory().artists.values.singleOrNull()?.mbid)
        assertEquals(listOf(SimilarArtist("Kit Calloway", 1f)), cache.similar(listOf("Mira Solen"))["Mira Solen"])
    }

    @Test
    fun `ids that name another artist are passed over for a search`() = runTest {
        val web = web()
        web.on(MusicBrainzUrls.release(ID_A), ok(releaseJson(ID_A, artist = "Glass Harbour", discs = listOf(listOf(T("Quiet Ember", 254_000))))))
        web.artist("Mira Solen", MIRA, Neighbour(GLASS, "Glass Harbour", 10))
        val cache = cache()

        lookups(web, cache).run(listOf(PlayedArtist("Mira Solen", WrittenIds(ID_A, recording = null))))

        assertEquals(MIRA, cache.memory().artists.values.singleOrNull()?.mbid)
    }

    @Test
    fun `the ids last written into each song come from History, edits taken back left out`() {
        fun edit(path: String, release: String?, recording: String? = null, undone: Boolean = false) = SongEdit(
            1, path, Payload(AudioFormat.MP3, "audio"), "before", "after",
            listOfNotNull(
                release?.let { FieldChange("MUSICBRAINZ_ALBUMID", emptyList(), listOf(it)) },
                recording?.let { FieldChange("MUSICBRAINZ_TRACKID", emptyList(), listOf(it)) },
                FieldChange("TITLE", listOf("Old Ember"), listOf("Quiet Ember")),
            ),
            undone = undone,
        )
        val older = HistoryEntry(1, EditKind.COVERS_TAGS, "Lanterns of Ulso", 100, listOf(edit(P1, ID_A, REC), edit(P2, ID_A), edit(P4, "not an id")))
        val newer = HistoryEntry(2, EditKind.COVERS_TAGS, "Lanterns of Ulso", 200, listOf(edit(P1, ID_B), edit(P3, ID_B, undone = true)))

        val written = SimilarRules.writtenByPath(listOf(older, newer))

        assertEquals(mapOf(P1 to WrittenIds(ID_B, null), P2 to WrittenIds(ID_A, null)), written)
    }

    @Test
    fun `the artist's id is read from the credit of the song's recording, else the release's`() {
        val tracks = listOf(listOf(T("Quiet Ember", 254_000), T("Salt Road", 198_500, artist = "Glass Harbour")))
        val release = MusicBrainzJson.release(releaseJson(ID_A, artist = "Mira Solen", discs = tracks).encodeToByteArray())
        val second = release.tracks[1].track.recordingId

        assertEquals(CREDITED, SimilarRules.artistIdIn(release, recording = null, name = "Mira Solen"))
        assertEquals(CREDITED, SimilarRules.artistIdIn(release, recording = second, name = "Glass Harbour"))
        assertNull(SimilarRules.artistIdIn(release, recording = second, name = "Mira Solen"))
        assertNull(SimilarRules.artistIdIn(release, recording = null, name = "Kit Calloway"))
    }

    // What suggestions read

    @Test
    fun `suggestions get the neighbours closest first, scored against the closest, library names where known, the artist itself never`() {
        val memory = SimilarMemory(
            artists = mapOf(
                "mira solen" to ArtistSimilar(
                    "Mira Solen", Match.FOUND, MIRA,
                    listOf(Neighbour(MIRA, "Mira Solen", 5_000), Neighbour(GLASS, "زجاج هاربر", 800), Neighbour(KIT, "Kit Calloway", 200)),
                    at = BASE,
                ),
                "glass harbour" to ArtistSimilar("Glass Harbour", Match.FOUND, GLASS, at = BASE),
                "rowan vale" to ArtistSimilar("Rowan Vale", Match.AMBIGUOUS, at = BASE),
            ),
        )

        val similar = SimilarRules.similar(memory, listOf("Mira Solen", "Glass Harbour", "Rowan Vale", "Tamsin Reed"))

        assertEquals(mapOf("Mira Solen" to listOf(SimilarArtist("Glass Harbour", 1f), SimilarArtist("Kit Calloway", 0.25f))), similar)
    }

    @Test
    fun `a file that can't be read starts the cache over`() = runTest {
        file.writeText("{not json")
        val cache = cache()

        assertEquals(emptyMap<String, List<SimilarArtist>>(), cache.similar(listOf("Mira Solen")))
        cache.update { it.copy(failures = 1) }
        assertEquals(1, cache().memory().failures)
    }

    @Test
    fun `suggestions read the lists at once, even while a change is still being saved`() = runTest {
        // A disk that does its work only when told.
        val held = mutableListOf<Runnable>()
        fun disk() = generateSequence { held.removeFirstOrNull() }.forEach { it.run() }
        val cache = SimilarCache(SimilarStore(file) { logs += it }, io = Executor { held += it }.asCoroutineDispatcher())
        val first = async { cache.similar(listOf("Mira Solen")) }
        runCurrent()
        disk()
        runCurrent()
        assertEquals(emptyMap<String, List<SimilarArtist>>(), first.await())
        val listed = ArtistSimilar("Mira Solen", Match.FOUND, MIRA, listOf(Neighbour(GLASS, "Glass Harbour", 10)), at = BASE)

        val saving = async { cache.update { it.copy(artists = mapOf("mira solen" to listed)) } }
        runCurrent()
        val read = async { cache.similar(listOf("Mira Solen")) }
        runCurrent()
        val readWhileSaving = read.isCompleted
        val savedThen = saving.isCompleted
        // The disk finishes before any assertion, so a failing one never leaves the save waiting.
        disk()
        runCurrent()

        assertTrue(readWhileSaving)
        assertEquals(false, savedThen)
        assertEquals(mapOf("Mira Solen" to listOf(SimilarArtist("Glass Harbour", 1f))), read.await())
        assertTrue(saving.isCompleted)
        assertEquals(read.await(), cache().similar(listOf("Mira Solen")))
    }

    // Privacy

    @Test
    fun `the log holds no names, ids or addresses`() = runTest {
        val web = web()
        web.artist("Mira Solen", MIRA, Neighbour(GLASS, "Glass Harbour", 10))
        web.on(MusicBrainzUrls.artistSearch("Kit Calloway"), ok(artistsJson(Hit(KIT, "Kit Calloway", 100), Hit(OTHER, "Kit Calloway", 100))))
        web.on(MusicBrainzUrls.artistSearch("Rowan Vale"), ok(artistsJson(Hit(ROWAN, "Rowan Vale", 100))))
        web.on(ListenBrainzUrls.similarArtists(ROWAN), status(503))

        lookups(web, cache()).run(played("Mira Solen", "Kit Calloway", "Rowan Vale"))

        assertTrue(logs.toString(), logs.isNotEmpty())
        val secrets = listOf("Mira", "Glass", "Kit", "Rowan", MIRA, KIT, ROWAN, "http", "listenbrainz", "musicbrainz.org")
        assertTrue(logs.toString(), logs.none { line -> secrets.any { it in line } })
    }

    // MusicBrainz's artist search and ListenBrainz's answer

    @Test
    fun `the artist search reads ids, names, sort names, aliases and scores, a hit without a proper id dropped`() {
        val body = """{"created":"2026-11-22T10:00:00.000Z","count":3,"offset":0,"artists":[""" +
            """{"id":"$MIRA","type":"Person","score":100,"name":"ميرا سولن","sort-name":"Solen, Mira","aliases":[{"sort-name":"Solen, Mira","name":"Mira Solen","locale":null,"type":"Artist name","primary":null}]},""" +
            """{"id":"not-an-id","score":90,"name":"Mira Solenne","sort-name":"Solenne, Mira"},""" +
            """{"id":"$OTHER","score":61,"name":"Mira Sol"}]}"""

        val hits = MusicBrainzJson.artists(body.encodeToByteArray())

        assertEquals(
            listOf(MbArtist(MIRA, "ميرا سولن", "Solen, Mira", listOf("Mira Solen"), 100), MbArtist(OTHER, "Mira Sol", null, emptyList(), 61)),
            hits,
        )
    }

    @Test
    fun `ListenBrainz's list is read best first, rows without a proper id or score dropped`() {
        val body = """[{"artist_mbid":"$GLASS","name":"Glass Harbour","comment":"made-up band","type":"Group","gender":null,"score":120,"reference_mbid":"$MIRA"},""" +
            """{"artist_mbid":"nope","name":"Nobody","score":90,"reference_mbid":"$MIRA"},""" +
            """{"artist_mbid":"$KIT","name":"Kit Calloway","score":null,"reference_mbid":"$MIRA"},""" +
            """{"artist_mbid":"$ROWAN","name":"Rowan Vale","comment":"","type":"Person","gender":"Female","score":900,"reference_mbid":"$MIRA"}]"""

        assertEquals(listOf(Neighbour(ROWAN, "Rowan Vale", 900), Neighbour(GLASS, "Glass Harbour", 120)), ListenBrainzJson.neighbours(body.encodeToByteArray()))
    }

    /**
     * KJB's start (PW1): WorkManager starts on its first use, which is this schedule's, off the main thread, not on the
     * main thread before KJB's first line; and a start keeps the schedule that is there, writing nothing.
     */
    @Test
    fun `a start keeps the schedule that is there, and WorkManager starts with it, not before KJB's first line`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val follow = File("src/main/java/app/lyrify/similar/SimilarWork.kt").readText()

        assertTrue(Regex("""android:name="androidx\.work\.WorkManagerInitializer"\s+android:value="androidx\.startup"\s+tools:node="remove"""").containsMatchIn(manifest))
        assertTrue(File("src/main/java/app/lyrify/Lyrify.kt").readText().contains("class LyrifyApplication : Application(), Configuration.Provider"))
        assertTrue(follow.contains("enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)"))
        assertTrue(follow.contains("scope.launch {"))
    }

    private data class Hit(val id: String, val name: String, val score: Int)

    private fun artistsJson(vararg hits: Hit): String =
        """{"created":"2026-11-22T10:00:00.000Z","count":${hits.size},"offset":0,"artists":[""" +
            hits.joinToString(",") { """{"id":"${it.id}","type":"Person","score":${it.score},"name":"${it.name}","sort-name":"${it.name}"}""" } + "]}"

    private fun neighboursJson(reference: String, vararg neighbours: Neighbour): String =
        "[" + neighbours.joinToString(",") {
            """{"artist_mbid":"${it.mbid}","name":"${it.name}","comment":"","type":"Person","gender":null,"score":${it.score},"reference_mbid":"$reference"}"""
        } + "]"

    private fun artist(id: String, name: String, score: Int?, sortName: String? = null, aliases: List<String> = emptyList()) = MbArtist(id, name, sortName, aliases, score)

    private companion object {
        const val BASE = 1_795_000_000_000L
        const val VERSION = 17
        const val MIRA = "a1b2c3d4-0000-4000-8000-0000000000a1"
        const val GLASS = "a1b2c3d4-0000-4000-8000-0000000000a2"
        const val KIT = "a1b2c3d4-0000-4000-8000-0000000000a3"
        const val ROWAN = "a1b2c3d4-0000-4000-8000-0000000000a4"
        const val TAMSIN = "a1b2c3d4-0000-4000-8000-0000000000a5"
        const val OTHER = "a1b2c3d4-0000-4000-8000-0000000000ff"

        /** The artist id [releaseJson] credits every made-up artist with. */
        const val CREDITED = "a1b2c3d4-0000-4000-8000-00000000aa01"
        const val REC = "0b7c1f4e-1111-4111-8111-000000000001"
        const val P1 = "/storage/emulated/0/Music/Lanterns/Quiet Ember.mp3"
        const val P2 = "/storage/emulated/0/Music/Lanterns/Salt Road.mp3"
        const val P3 = "/storage/emulated/0/Music/Lanterns/Brennmoor.mp3"
        const val P4 = "/storage/emulated/0/Music/Lanterns/Kestrel Lights.mp3"
    }
}
