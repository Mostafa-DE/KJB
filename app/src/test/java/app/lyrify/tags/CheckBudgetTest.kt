package app.lyrify.tags

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.tags.lookup.AlbumLookup
import app.lyrify.tags.lookup.CoverPipeline
import app.lyrify.tags.lookup.FakeWeb
import app.lyrify.tags.lookup.ImageCodec
import app.lyrify.tags.lookup.ImageSize
import app.lyrify.tags.lookup.MetaCache
import app.lyrify.tags.lookup.MetaClient
import app.lyrify.tags.lookup.MetaGate
import app.lyrify.tags.lookup.MusicBrainz
import app.lyrify.tags.lookup.MusicBrainzUrls
import app.lyrify.tags.lookup.T
import app.lyrify.tags.lookup.TEST_AGENT
import app.lyrify.tags.lookup.browseJson
import app.lyrify.tags.lookup.browseUri
import app.lyrify.tags.lookup.fixture
import app.lyrify.tags.lookup.ok
import app.lyrify.tags.lookup.releaseJson
import app.lyrify.tags.lookup.searchJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * How many requests a check of a made-up library sends to MusicBrainz, through the real lookup, client and gate over a
 * scripted web (made-up names only): the first full check, then a check after KJB was closed and opened again with
 * nothing changed. Before KJB 3.1 (106ce32) the first check sent 54 requests for 14 albums (58 s of the gate) and the
 * second 6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckBudgetTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val rig by lazy { WriterRig(folder.root) }
    private val cache by lazy { File(folder.root, "cache/tag-cache") }
    private val storeFile by lazy { File(folder.root, "files/covers-tags.json") }
    private var now: () -> Long = { 0L }
    private val web by lazy { FakeWeb(now = { now() }) }
    private var library: List<Song> = emptyList()
    private var nextId = 0

    /** KJB as it starts: a new client and gate (the answers cached on disk stay), over the same Covers & tags files. */
    private fun TestScope.kjb(): CoversTags {
        val client = MetaClient(
            MetaCache(cache),
            userAgent = TEST_AGENT,
            gate = MetaGate(now = { testScheduler.currentTime }),
            io = StandardTestDispatcher(testScheduler),
            open = web::open,
        )
        val lookup = AlbumLookup(MusicBrainz(client), compute = StandardTestDispatcher(testScheduler))
        return CoversTags(
            songs = { library },
            source = PhoneAlbumSource(lookup, CoverPipeline(NoImages), client.busy),
            write = { _, _, _, _, _ -> error("nothing is written here") },
            undoEntry = { error("nothing is taken back here") },
            undo = rig.undo,
            store = CoversTagsStore(storeFile),
            lyrics = { _, _ -> },
            coversChanged = {},
            access = { true },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
            compute = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
            // The files never change between the checks.
            files = { FileMark(it, 1_000, 1) },
        )
    }

    private fun id(): String = "0b7c1f4e-5a2d-4c3b-9e8f-%012x".format(++nextId)

    private fun group(): String = "6f1e2d3c-4b5a-4968-8776-%012x".format(++nextId)

    private fun tracks(title: String, count: Int): List<T> = List(count) { T("$title Part ${it + 1}", 200_000L + it * 1_000) }

    /** [count] songs of [title] by [artist] in a folder of their own, as the library lists them. */
    private fun album(title: String, artist: String, count: Int): List<Song> = tracks(title, count).mapIndexed { i, t ->
        val path = "/storage/emulated/0/Music/$artist/$title/${i + 1}.mp3"
        Song(SongRef(path, (++nextId).toLong()), t.title, artist, title, null, t.lengthMs!!, i + 1, 1, null, null, "Music/$artist/$title/", null, null, 0, "audio/mpeg")
    }

    /** An edition MusicBrainz has: its id, its release group, its track count. */
    private data class Edition(val id: String, val group: String, val tracks: Int)

    /**
     * An album whose search by its track count finds [editions] (none: the search goes again without the count, and
     * finds [anyCount]); every edition can be looked up alone and browsed with its release group.
     */
    private fun added(title: String, artist: String, count: Int, editions: List<Edition>, anyCount: List<Edition> = emptyList()) {
        library = library + album(title, artist, count)
        fun search(list: List<Edition>) =
            searchJson(*list.mapIndexed { i, e -> Triple(e.id, e.tracks, 100 - i) }.toTypedArray(), title = title, artist = artist, groupOf = { id -> list.first { it.id == id }.group })
        web.on(MusicBrainzUrls.releaseSearch(title, artist, count), ok(search(editions)))
        if (editions.isEmpty()) web.on(MusicBrainzUrls.releaseSearch(title, artist, null), ok(search(anyCount)))
        val all = editions + anyCount
        fun json(e: Edition) = releaseJson(e.id, title = title, artist = artist, group = e.group, discs = listOf(tracks(title, e.tracks)))
        all.forEach { web.on(MusicBrainzUrls.release(it.id), ok(json(it))) }
        all.groupBy { it.group }.forEach { (group, list) -> web.on(browseUri(group), ok(browseJson(*list.map(::json).toTypedArray()))) }
    }

    /** The made-up library: 12 albums of 1 to 8 editions in 1 or 2 release groups (2 with no match at all), and 2 singles. */
    private fun library() {
        repeat(4) { n ->
            val g = group()
            added("Lanterns $n", "Mira Solen", 5, List(4) { Edition(id(), g, 5) })
        }
        repeat(2) { n ->
            val (g1, g2) = group() to group()
            added("Harbour $n", "Kit Calloway", 4, listOf(Edition(id(), g1, 4), Edition(id(), g1, 4), Edition(id(), g2, 4), Edition(id(), g2, 4)))
        }
        repeat(2) { n -> added("Tin Roof $n", "Quillmere", 3, listOf(Edition(id(), group(), 3))) }
        repeat(2) { n -> added("Salt Road Demos $n", "Glass Harbour", 4, emptyList()) }
        val g = group()
        added("Brennmoor", "Vael", 6, emptyList(), anyCount = List(3) { Edition(id(), g, 7) })
        val big = group()
        added("Nachtfaehre", "Mira Solen", 9, List(8) { Edition(id(), big, 9) })
        // Singles: one found by a recording search, one found nowhere.
        val found = "/storage/emulated/0/Download/Kestrel.mp3"
        library = library + Song(SongRef(found, 9_001), "Kestrel Lights", "Quillmere", null, null, 251_400, null, null, null, null, "Download/", null, null, 0, "audio/mpeg")
        web.on(MusicBrainzUrls.recordingSearch("Kestrel Lights", "Quillmere", 251_400), ok(fixture("recording-search.json")))
        val lost = "/storage/emulated/0/Download/Ember.mp3"
        library = library + Song(SongRef(lost, 9_002), "Quiet Ember Sketch", "Vael", null, null, 199_000, null, null, null, null, "Download/", null, null, 0, "audio/mpeg")
        web.on(MusicBrainzUrls.recordingSearch("Quiet Ember Sketch", "Vael", 199_000), ok("""{"count":0,"recordings":[]}"""))
    }

    @Test
    fun `a full check of a made-up library sends about 2 requests per album, and a check after reopening KJB none`() = runTest {
        now = { testScheduler.currentTime }
        library()
        val albums = 14

        val first = kjb()
        advanceUntilIdle()
        first.check()
        advanceUntilIdle()
        val firstCheck = web.requests.size
        val firstSeconds = web.requests.last().at / 1_000

        val reopened = kjb()
        advanceUntilIdle()
        reopened.check()
        advanceUntilIdle()
        val secondCheck = web.requests.size - firstCheck

        val line = "ct1 budget: $albums albums; first check $firstCheck requests (${"%.2f".format(firstCheck.toDouble() / albums)} per album, " +
            "last one at $firstSeconds s), check after reopening $secondCheck requests"
        println(line)
        assertEquals(line, 14, first.state.value.waiting.size)
        assertEquals(line, 29, firstCheck)
        assertEquals(line, 0, secondCheck)
        assertEquals(line, first.state.value.waiting.map { it.album.id }.toSet(), reopened.state.value.waiting.map { it.album.id }.toSet())
    }

    /** No image is ever decoded here. */
    private object NoImages : ImageCodec {
        override fun size(image: ByteArray): ImageSize? = null

        override fun jpeg(image: ByteArray, maxSide: Int, quality: Int): ByteArray? = null
    }
}
