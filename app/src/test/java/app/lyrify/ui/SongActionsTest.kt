package app.lyrify.ui

import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistStore
import app.lyrify.library.PlaylistsState
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.NoPlayback
import app.lyrify.player.PlaybackCommands
import app.lyrify.player.QueueAdded
import app.lyrify.player.QueueSource
import app.lyrify.player.QueueTarget
import app.lyrify.player.Undo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one door: the song sheet's rows, Add to, selection, and every change's toast with its Undo. Made-up names only. */
class SongActionsTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 11)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", id = 12)
    private val water = song("Music/Night Ferry/01 Low Water.mp3", id = 13)

    /** playlists.json in memory: every change as PlaylistsState makes it; [fail] makes every write fail. */
    private class MemoryPlaylists(var now: PlaylistsState = PlaylistsState(), var fail: Boolean = false) : PlaylistStore {
        private var ids = 0
        override val state: Flow<PlaylistsState> = MutableStateFlow(now)
        override val songs: Flow<List<SongRef>> = state.map { it.favouriteSongs }
        override val collections: Flow<List<FavouriteCollection>> = state.map { it.favouriteCollections }

        private fun write(change: (PlaylistsState) -> PlaylistsState): Boolean = !fail && run {
            now = change(now)
            true
        }

        override suspend fun setSongs(songs: List<SongRef>, favourite: Boolean) = write { it.favourite(songs, favourite) }

        override suspend fun setCollection(collection: FavouriteCollection, favourite: Boolean) = write { it.favourite(collection, favourite) }

        override suspend fun create(name: String, songs: List<SongRef>): Playlist? {
            val playlist = Playlist("new${++ids}", name.trim(), songs, createdAt = ids.toLong())
            return playlist.takeIf { name.isNotBlank() && write { it.put(playlist) } }
        }

        override suspend fun rename(id: String, name: String) = write { it.rename(id, name) }

        override suspend fun add(id: String, songs: List<SongRef>) = write { it.add(id, songs) }

        override suspend fun removeAt(id: String, positions: Set<Int>) = write { it.removeAt(id, positions) }

        override suspend fun delete(id: String) = write { it.delete(id) }

        override suspend fun put(playlist: Playlist) = write { it.put(playlist) }

        override suspend fun edit(id: String, change: (Playlist) -> Playlist) = write { state -> state.playlist(id)?.let { state.put(change(it)) } ?: state }

        override suspend fun forget(paths: Set<String>) = write { it.forget(paths) }
    }

    /** The player made up: it records what was asked and hands out Undos that record being used. */
    private class FakePlayback : PlaybackCommands {
        val undone = mutableListOf<String>()
        var named: QueueAdded? = null
        override val queues: StateFlow<List<QueueTarget>> = MutableStateFlow(emptyList())

        override fun play(songs: List<SongRef>, start: Int, source: QueueSource) = Unit

        override fun shuffle(songs: List<SongRef>, source: QueueSource) = Unit

        override fun playNext(songs: List<SongRef>): Undo = Undo { undone += "next" }

        override fun addToQueue(songs: List<SongRef>): Undo = Undo { undone += "queue" }

        override fun addToQueue(id: String, songs: List<SongRef>): QueueAdded? = named

        override fun newQueue(name: String, songs: List<SongRef>, source: QueueSource?): Undo = Undo { undone += "new $name" }
    }

    private val roadTrip = Playlist("p1", "Road trip", listOf(lantern.ref), createdAt = 1)
    private val sunday = Playlist("p2", "Sunday chores", emptyList(), createdAt = 2)

    // The song sheet

    @Test
    fun `the song sheet shows every row for a song in a list, and the rows of its place`() {
        val common = listOf(SheetRow.PLAY_NEXT, SheetRow.ADD_TO, SheetRow.FAVOURITE, SheetRow.LYRICS, SheetRow.MAKE_TONE, SheetRow.SONG_INFO, SheetRow.EDIT_TAGS)
        assertEquals(common + SheetRow.MOVE_TO_TRASH, sheetRows(SheetPlace.List, coversTags = false, removeFromQueue = false))
        assertEquals(common + SheetRow.COVERS_TAGS + SheetRow.MOVE_TO_TRASH, sheetRows(SheetPlace.List, coversTags = true, removeFromQueue = true))
        assertEquals(
            common + SheetRow.REMOVE_FROM_PLAYLIST + SheetRow.MOVE_TO_TRASH,
            sheetRows(SheetPlace.Playlist("p1", 0), coversTags = false, removeFromQueue = true),
        )
        assertEquals(
            common + SheetRow.COVERS_TAGS + SheetRow.REMOVE_FROM_QUEUE + SheetRow.MOVE_TO_TRASH,
            sheetRows(SheetPlace.Queue("q1", 3), coversTags = true, removeFromQueue = true),
        )
        // Until P2 fills its slot, a queue's song has no Remove from queue.
        assertEquals(common + SheetRow.MOVE_TO_TRASH, sheetRows(SheetPlace.Queue("q1", 3), coversTags = false, removeFromQueue = false))
    }

    // Add to

    @Test
    fun `Add to offers the queue first, then saved and recent queues, Favourites, then the playlists by name`() {
        val playing = QueueTarget("q0", "Album · Harbour Glass", 12, saved = false, playing = true)
        val recent = QueueTarget("q1", "Folder · Rips", 30, saved = false, playing = false)
        val saved = QueueTarget("q2", "Sunday chores", 8, saved = true, playing = false)
        val older = QueueTarget("q3", "Road trip", 20, saved = true, playing = false)
        assertEquals(
            listOf(
                AddTarget.PlayNext,
                AddTarget.AddToQueue,
                AddTarget.Queue(saved),
                AddTarget.Queue(older),
                AddTarget.Queue(recent),
                AddTarget.NewQueue,
                AddTarget.Favourites,
                AddTarget.ToPlaylist(roadTrip),
                AddTarget.ToPlaylist(sunday),
                AddTarget.NewPlaylist,
            ),
            addTargets(listOf(playing, recent, saved, older), listOf(sunday, roadTrip)),
        )
    }

    @Test
    fun `a new playlist's name is the first one not taken`() {
        assertEquals("New playlist", nextName("New playlist", listOf("Road trip")))
        assertEquals("New playlist 3", nextName("New playlist", listOf("New playlist", "New playlist 2")))
    }

    // Selection

    @Test
    fun `selection keeps the list's order, whatever the order of the taps`() {
        val list = listOf(lantern, bell, water)
        val picked = SongSelection().toggle(water).toggle(lantern)
        assertTrue(picked.active)
        assertEquals(listOf(lantern, water), picked.picked(list))
        assertFalse(picked.allOf(list))
        val none = picked.toggle(water).toggle(lantern)
        assertFalse(none.active)
    }

    @Test
    fun `All selects every song, and again none, and songs that leave the list leave the selection`() {
        val list = listOf(lantern, bell, water)
        val all = SongSelection().toggle(bell).all(list)
        assertTrue(all.allOf(list))
        assertEquals(list, all.picked(list))
        assertFalse(all.all(list).active)
        assertEquals(SongSelection(setOf(lantern.path)), SongSelection(setOf(lantern.path, water.path)).within(listOf(lantern, bell)))
        // A playlist may hold a song twice: it is picked once.
        assertEquals(listOf(lantern), SongSelection(setOf(lantern.path)).picked(listOf(lantern, bell, lantern)))
    }

    // Toasts and Undo

    @Test
    fun `Play next and Add to queue say so and take it back`() = runBlocking {
        val player = FakePlayback()
        val actions = SongActions(player, MemoryPlaylists())
        val next = actions.playNext(listOf(lantern.ref))
        assertEquals(Said.PlaysNext, next?.said)
        assertTrue(next?.undo?.invoke() == true)
        assertEquals(Said.AddedToQueue, actions.addToQueue(listOf(lantern.ref))?.said)
        assertEquals(listOf("next"), player.undone)
        // Nothing to say while the player has no queue.
        assertNull(SongActions(NoPlayback, MemoryPlaylists()).playNext(listOf(lantern.ref)))
    }

    @Test
    fun `a named queue says how many were added and how many were there already`() = runBlocking {
        val player = FakePlayback()
        val actions = SongActions(player, MemoryPlaylists())
        val road = QueueTarget("q3", "Road trip", 20, saved = true, playing = false)
        val songs = listOf(lantern.ref, bell.ref, water.ref)

        player.named = QueueAdded(added = 1, already = 2, undo = Undo { player.undone += "named" })
        val outcome = actions.addToQueue(road, songs)
        assertEquals(Said.AddedTo("Road trip", 1, already = 2), outcome?.said)
        outcome?.undo?.invoke()
        assertEquals(listOf("named"), player.undone)

        player.named = QueueAdded(added = 0, already = 3, undo = null)
        assertEquals(Said.AlreadyIn("Road trip", 3), actions.addToQueue(road, songs)?.said)
        assertNull(actions.addToQueue(road, songs)?.undo)

        val made = actions.newQueue("Picked · 3 songs", songs, null)
        assertEquals(Said.AddedTo("Picked · 3 songs", 3), made?.said)
    }

    @Test
    fun `adding to a playlist keeps doubles, and Undo puts the playlist back as it was`() = runBlocking {
        val store = MemoryPlaylists(PlaylistsState(listOf(roadTrip)))
        val actions = SongActions(NoPlayback, store)
        val outcome = actions.addToPlaylist(roadTrip, listOf(lantern.ref, bell.ref))
        assertEquals(Said.AddedTo("Road trip", 2), outcome.said)
        assertEquals(listOf(lantern.ref, lantern.ref, bell.ref), store.now.playlist("p1")?.songs)
        assertTrue(outcome.undo?.invoke() == true)
        assertEquals(roadTrip, store.now.playlist("p1"))
    }

    @Test
    fun `playlist Undo takes back only its own change, on the playlist as it is now`() = runBlocking {
        val trip = Playlist("p1", "Road trip", listOf(lantern.ref, bell.ref), createdAt = 1)
        val store = MemoryPlaylists(PlaylistsState(listOf(trip)))
        val actions = SongActions(NoPlayback, store)
        fun now() = checkNotNull(store.now.playlist("p1"))

        // Add: only the songs it appended go; a song added and a name given since stay.
        val added = actions.addToPlaylist(trip, listOf(water.ref, lantern.ref))
        store.add("p1", listOf(bell.ref))
        store.rename("p1", "Long road")
        assertTrue(added.undo?.invoke() == true)
        assertEquals(Playlist("p1", "Long road", listOf(lantern.ref, bell.ref, bell.ref), createdAt = 1), now())

        // Remove: the song goes back to its place, with a song added since.
        val removed = actions.removeFromPlaylist(now(), 0)
        store.add("p1", listOf(water.ref))
        assertTrue(removed.undo?.invoke() == true)
        assertEquals(listOf(lantern.ref, bell.ref, bell.ref, water.ref), now().songs)

        // Rename: the old name comes back while the new one stands, and nothing else changes.
        val renamed = actions.rename(now(), "Night drive")
        store.add("p1", listOf(lantern.ref))
        assertTrue(renamed.undo?.invoke() == true)
        assertEquals(Playlist("p1", "Long road", listOf(lantern.ref, bell.ref, bell.ref, water.ref, lantern.ref), createdAt = 1), now())
        val again = actions.rename(now(), "Night drive")
        store.rename("p1", "Dawn drive")
        again.undo?.invoke()
        assertEquals("Dawn drive", now().name)
    }

    @Test
    fun `the heart adds the songs that are not favourites, or takes them all out, each with Undo`() = runBlocking {
        val store = MemoryPlaylists(PlaylistsState(favouriteSongs = listOf(lantern.ref)))
        val actions = SongActions(NoPlayback, store)

        val added = actions.favourite(listOf(lantern.ref, bell.ref), store.now.favouriteSongs)
        assertEquals(Said.Favourites(added = true), added.said)
        assertEquals(setOf(lantern.path, bell.path), store.now.favouriteSongs.map { it.path }.toSet())
        added.undo?.invoke()
        assertEquals(listOf(lantern.ref), store.now.favouriteSongs)

        val removed = actions.favourite(listOf(lantern.ref), store.now.favouriteSongs)
        assertEquals(Said.Favourites(added = false), removed.said)
        assertEquals(emptyList<SongRef>(), store.now.favouriteSongs)
        removed.undo?.invoke()
        assertEquals(listOf(lantern.ref), store.now.favouriteSongs)
    }

    @Test
    fun `a collection's heart, a new playlist, Remove from playlist and Delete each take back`() = runBlocking {
        val store = MemoryPlaylists(PlaylistsState(listOf(roadTrip)))
        val actions = SongActions(NoPlayback, store)
        val album = FavouriteCollection(CollectionKind.ALBUM, """["Kit Calloway","Harbour Glass"]""")

        val hearted = actions.favourite(album, on = true)
        assertEquals(Said.Favourites(added = true), hearted.said)
        hearted.undo?.invoke()
        assertEquals(emptyList<FavouriteCollection>(), store.now.favouriteCollections)

        val made = actions.newPlaylist("Late drive", listOf(water.ref))
        assertEquals(Said.AddedTo("Late drive", 1), made.said)
        made.undo?.invoke()
        assertEquals(listOf("p1"), store.now.playlists.map { it.id })
        assertEquals(Said.Made("Empty one"), actions.newPlaylist("Empty one", emptyList()).said)

        val removed = actions.removeFromPlaylist(roadTrip, 0)
        assertEquals(Said.RemovedFrom("Road trip"), removed.said)
        assertEquals(emptyList<SongRef>(), store.now.playlist("p1")?.songs)
        removed.undo?.invoke()
        assertEquals(roadTrip, store.now.playlist("p1"))

        val deleted = actions.delete(roadTrip)
        assertEquals(Said.PlaylistDeleted, deleted.said)
        assertNull(store.now.playlist("p1"))
        deleted.undo?.invoke()
        assertEquals(roadTrip, store.now.playlist("p1"))
        assertEquals(Said.Renamed, actions.rename(roadTrip, "Long road").said)
        assertEquals("Long road", store.now.playlist("p1")?.name)
    }

    @Test
    fun `a change that could not be stored says so and offers no Undo`() = runBlocking {
        val store = MemoryPlaylists(PlaylistsState(listOf(roadTrip)), fail = true)
        val actions = SongActions(NoPlayback, store)
        listOf(
            actions.addToPlaylist(roadTrip, listOf(bell.ref)),
            actions.favourite(listOf(bell.ref), emptyList()),
            actions.newPlaylist("Late drive", emptyList()),
            actions.delete(roadTrip),
        ).forEach {
            assertEquals(Said.NotSaved, it.said)
            assertNull(it.undo)
        }
    }
}
