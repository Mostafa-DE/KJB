package app.lyrify.library

import app.lyrify.settings.Settings
import app.lyrify.stats.PlaysQuery
import app.lyrify.stats.SongPlays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Playlists, Favourites and the built-in lists: the changes, the stored file of any age or shape, and the store. Made-up names only. */
class PlaylistsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logs = mutableListOf<String>()

    @After
    fun stop() = scope.cancel()

    private val lantern = SongRef(VOLUME + "Music/Harbour Glass/01 Paper Lantern.mp3", 11)
    private val bell = SongRef(VOLUME + "Music/Harbour Glass/02 Tide Bell.mp3", 12)
    private val water = SongRef(VOLUME + "Music/Night Ferry/01 Low Water.mp3", null)

    private val roadTrip = Playlist(id = "p1", name = "Road trip", songs = listOf(lantern, bell, lantern), createdAt = NOW)
    private val album = FavouriteCollection(CollectionKind.ALBUM, """["Kit Calloway","Harbour Glass"]""")
    private val artist = FavouriteCollection(CollectionKind.ARTIST, "Mira Solen")

    // Changes

    @Test
    fun `a playlist may hold a song twice, and songs are added at its end in the given order`() {
        val state = PlaylistsState().put(roadTrip.copy(songs = emptyList())).add("p1", listOf(lantern, bell)).add("p1", listOf(lantern))

        assertEquals(listOf(lantern, bell, lantern), state.playlist("p1")?.songs)
    }

    @Test
    fun `songs leave a playlist by their place in it`() {
        val state = PlaylistsState().put(roadTrip).removeAt("p1", setOf(0, 1, 9))

        assertEquals(listOf(lantern), state.playlist("p1")?.songs)
    }

    @Test
    fun `rename, delete, and put back for Undo`() {
        val state = PlaylistsState().put(roadTrip).put(roadTrip.copy(id = "p2", name = "Night drive", createdAt = NOW + 1))

        assertEquals("Long drive", state.rename("p1", "  Long drive ").playlist("p1")?.name)
        assertEquals(roadTrip, state.rename("p1", "   ").playlist("p1"))
        assertEquals(listOf("p2"), state.delete("p1").playlists.map { it.id })
        // Put back where it was: Undo after a delete or an edit.
        assertEquals(state, state.delete("p1").put(roadTrip))
        assertEquals(state, state.add("p1", listOf(water)).put(roadTrip))
        assertEquals(state, state.rename("nope", "x").add("nope", listOf(water)).removeAt("nope", setOf(0)).delete("nope"))
    }

    @Test
    fun `favourite songs are each there once, the latest first, known by their path`() {
        val state = PlaylistsState()
            .favourite(listOf(lantern), favourite = true)
            .favourite(listOf(bell, water, bell), favourite = true)
            .favourite(listOf(lantern.copy(mediaStoreId = 99)), favourite = true)

        assertEquals(listOf(bell, water, lantern), state.favouriteSongs)
        assertEquals(listOf(bell), state.favourite(listOf(water, lantern.copy(mediaStoreId = null)), favourite = false).favouriteSongs)
    }

    @Test
    fun `favourite albums, artists, folders and playlists are each there once, the latest first`() {
        val state = PlaylistsState().favourite(album, true).favourite(artist, true).favourite(album, true)

        assertEquals(listOf(artist, album), state.favouriteCollections)
        assertEquals(listOf(album), state.favourite(artist, false).favouriteCollections)
    }

    @Test
    fun `a trashed song leaves every playlist and Favourites`() {
        val state = PlaylistsState().put(roadTrip).favourite(listOf(lantern, water), true).forget(setOf(lantern.path))

        assertEquals(listOf(bell), state.playlist("p1")?.songs)
        assertEquals(listOf(water), state.favouriteSongs)
    }

    // The stored file

    private val everything = PlaylistsState(
        playlists = listOf(roadTrip, Playlist("p2", "Empty one", emptyList(), NOW + 1)),
        favouriteSongs = listOf(water, lantern),
        favouriteCollections = listOf(artist, album, FavouriteCollection(CollectionKind.FOLDER, "Music/Rips"), FavouriteCollection(CollectionKind.PLAYLIST, "p1")),
    )

    @Test
    fun `everything stored reads back the same`() {
        val text = PlaylistsJson.encode(everything)

        assertEquals(everything, PlaylistsJson.decode(text))
        assertEquals(PlaylistsJson.SCHEMA_VERSION, Json.parseToJsonElement(text).jsonObject["schemaVersion"].toString().toInt())
    }

    @Test
    fun `missing parts read as empty, unknown keys are ignored, and only text that is no JSON object is not playlists`() {
        assertEquals(PlaylistsState(), PlaylistsJson.decode("""{"schemaVersion":1}"""))
        assertEquals(PlaylistsState(), PlaylistsJson.decode("""{"schemaVersion":9,"playlists":"x","favourites":[],"later":{"a":1}}"""))
        assertNull(PlaylistsJson.decode("[]"))
        assertNull(PlaylistsJson.decode("not json"))
    }

    @Test
    fun `a playlist this version cannot read is kept as stored and written back, and costs no other`() {
        val newer = """{"id":"p9","name":"From later","songs":[],"createdAt":5,"kind":"SMART","rules":[1,2]}"""
        val broken = """{"name":"No id"}"""
        val text = """{"schemaVersion":2,"playlists":[$newer,${PlaylistsJson.encode(PlaylistsState(playlists = listOf(roadTrip))).let { Json.parseToJsonElement(it).jsonObject["playlists"]!!.jsonArray[0] }},$broken]}"""

        val state = checkNotNull(PlaylistsJson.decode(text))
        // The newer one has every field this version needs: read, its extra fields ignored.
        assertEquals(listOf("p9", "p1"), state.playlists.map { it.id })
        assertEquals(1, state.unknownPlaylists.size)
        val written = Json.parseToJsonElement(PlaylistsJson.encode(state)).jsonObject["playlists"]!!.jsonArray
        assertEquals(3, written.size)
        assertEquals(Json.parseToJsonElement(broken), written.last())
    }

    @Test
    fun `a song entry without a path is dropped and counted, the rest of its playlist read`() {
        var dropped = 0
        val text = """{"schemaVersion":1,"playlists":[{"id":"p1","name":"Road trip","createdAt":5,"songs":[{"path":"${lantern.path}"},{"mediaStoreId":4},7,{"path":"${bell.path}","mediaStoreId":12,"extra":true}]}],
            "favourites":{"songs":[{"path":"${water.path}"},{}],"collections":[]}}"""

        val state = checkNotNull(PlaylistsJson.decode(text) { dropped += it })

        assertEquals(listOf(SongRef(lantern.path), bell), state.playlist("p1")?.songs)
        assertEquals(listOf(water), state.favouriteSongs)
        assertEquals(3, dropped)
    }

    @Test
    fun `a favourite collection of a kind this version does not know is kept as stored`() {
        val text = """{"schemaVersion":2,"favourites":{"songs":[],"collections":[{"kind":"GENRE","key":"Folk"},{"kind":"ALBUM","key":"k"}]}}"""

        val state = checkNotNull(PlaylistsJson.decode(text))
        assertEquals(listOf(FavouriteCollection(CollectionKind.ALBUM, "k")), state.favouriteCollections)
        val written = Json.parseToJsonElement(PlaylistsJson.encode(state)).jsonObject["favourites"]!!.jsonObject["collections"]!!.jsonArray
        assertEquals(2, written.size)
        assertEquals("GENRE", (written.last() as JsonObject)["kind"].toString().trim('"'))
    }

    // The store

    private val backupDir: File by lazy { folder.newFolder("files") }
    private var ids = 0

    private fun repository(file: File = File(folder.root, "playlists.json")) =
        PlaylistsRepository(playlistsStore(file, backupDir, scope, clock = { NOW }) { synchronized(logs) { logs += it } }, clock = { NOW }, newId = { "id${++ids}" }) {
            synchronized(logs) { logs += it }
        }

    @Test
    fun `playlists and Favourites are stored in the file`() = runBlocking {
        val file = File(folder.root, "playlists.json")
        val store = repository(file)

        val made = checkNotNull(store.create("  Road trip ", listOf(lantern)))
        assertEquals(Playlist("id1", "Road trip", listOf(lantern), NOW), made)
        assertTrue(store.add(made.id, listOf(bell, lantern)))
        assertTrue(store.setSongs(listOf(water), favourite = true))
        assertTrue(store.setCollection(album, favourite = true))
        assertTrue(store.removeAt(made.id, setOf(0)))
        assertTrue(store.rename(made.id, "Night drive"))

        val stored = checkNotNull(PlaylistsJson.decode(file.readText()))
        assertEquals(listOf(Playlist("id1", "Night drive", listOf(bell, lantern), NOW)), stored.playlists)
        assertEquals(listOf(water), stored.favouriteSongs)
        assertEquals(listOf(album), stored.favouriteCollections)
        assertEquals(listOf(water), store.songs.first())
        assertEquals(listOf(album), store.collections.first())
    }

    @Test
    fun `a blank name makes no playlist, and delete, put and forget are stored`() = runBlocking {
        val store = repository()

        assertNull(store.create("   "))
        assertTrue(store.put(roadTrip))
        assertTrue(store.setSongs(listOf(lantern, bell), favourite = true))
        assertTrue(store.forget(setOf(lantern.path)))
        assertEquals(listOf(bell), store.state.first().playlist("p1")?.songs)
        assertEquals(listOf(bell), store.songs.first())
        assertTrue(store.delete("p1"))
        assertEquals(emptyList<Playlist>(), store.state.first().playlists)
        assertTrue(store.setSongs(listOf(bell), favourite = false))
        assertEquals(emptyList<SongRef>(), store.songs.first())
    }

    @Test
    fun `a file that is not playlists is kept aside before the store starts over`() = runBlocking {
        val file = File(folder.root, "playlists.json").apply { writeText("{ not playlists") }
        val store = repository(file)

        assertEquals(PlaylistsState(), store.state.first())
        val kept = backupDir.listFiles().orEmpty().filter { it.name.startsWith("playlists-corrupt-") }
        assertEquals(1, kept.size)
        assertArrayEquals("{ not playlists".toByteArray(), kept.single().readBytes())
        assertNotNull(store.create("Road trip"))
        assertFalse(logs.toString(), logs.any { "not playlists" in it })
    }

    @Test
    fun `only the newest copies of unreadable files are kept`() = runBlocking {
        repeat(PlaylistsSerializer.MAX_BACKUPS + 1) { n -> File(backupDir, "playlists-corrupt-${n + 1}.json").writeText("old $n") }
        val file = File(folder.root, "playlists.json").apply { writeText("[]") }

        repository(file).state.first()

        val kept = backupDir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("playlists-corrupt-") }.sorted()
        assertEquals(PlaylistsSerializer.MAX_BACKUPS, kept.size)
        assertTrue(kept.toString(), "playlists-corrupt-$NOW.json" in kept)
        assertFalse(kept.toString(), "playlists-corrupt-1.json" in kept)
    }

    // Built for you

    @Test
    fun `Recently added is the library's songs added in the last 30 days, the newest first`() {
        val day = TimeUnit.DAYS.toMillis(1)
        val library = Library.of(
            listOf(
                song("Music/a.mp3", addedAt = NOW - 2 * day),
                song("Music/b.mp3", addedAt = NOW - 31 * day),
                song("Music/c.mp3", addedAt = NOW - 1 * day),
                song("Music/d.mp3", addedAt = null),
                song("Music/e.mp3", addedAt = NOW - day, durationMs = 2_000),
            ),
            Settings(),
        )

        assertEquals(listOf("c", "a"), recentlyAdded(library, NOW).map { it.title })
    }

    @Test
    fun `Most played and Recently played are the play counter's top 50, found in the library in its order`() = runBlocking {
        val a = song("Music/a.mp3", id = 1)
        val b = song("Music/b.mp3", id = 2)
        val clip = song("Music/clip.mp3", id = 3, durationMs = 1_000)
        val library = Library.of(listOf(a, b, clip), Settings())
        val asked = mutableListOf<Int>()
        val plays = object : PlaysQuery {
            override suspend fun mostPlayed(limit: Int): List<SongPlays> =
                listOf(b.ref, clip.ref, SongRef(VOLUME + "Music/Gone/x.mp3"), a.ref).map { SongPlays(it, 3, null, 0) }.also { asked += limit }

            override suspend fun recentlyPlayed(limit: Int): List<SongPlays> = listOf(a.ref, b.ref).map { SongPlays(it, 1, NOW, 0) }.also { asked += limit }

            override suspend fun plays(song: SongRef): SongPlays? = null
        }

        assertEquals(listOf(b, a), mostPlayed(plays, library))
        assertEquals(listOf(a, b), recentlyPlayed(plays, library))
        assertEquals(listOf(50, 50), asked)
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000L
    }
}
