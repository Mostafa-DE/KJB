package app.lyrify.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import app.lyrify.R
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
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.QueueTarget
import app.lyrify.player.SourceKind
import app.lyrify.player.Undo
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

        override suspend fun arrange(id: String, order: List<SongRef>) = write { it.arrange(id, order) }

        override suspend fun arrangeFavourites(order: List<SongRef>) = write { it.arrangeFavourites(order) }

        override suspend fun put(playlist: Playlist) = write { it.put(playlist) }

        override suspend fun edit(id: String, change: (Playlist) -> Playlist) = write { state -> state.playlist(id)?.let { state.put(change(it)) } ?: state }

        override suspend fun update(change: (PlaylistsState) -> PlaylistsState) = write(change)

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
    fun `the song sheet shows every row for a song in a list, and the rows of its place, Hide and Move to trash last`() {
        val common = listOf(SheetRow.PLAY_NEXT, SheetRow.ADD_TO, SheetRow.FAVOURITE, SheetRow.LYRICS, SheetRow.MAKE_TONE, SheetRow.SONG_INFO, SheetRow.EDIT_TAGS)
        assertEquals(common + SheetRow.HIDE + SheetRow.MOVE_TO_TRASH, sheetRows(SheetPlace.List, coversTags = false, removeFromQueue = false))
        assertEquals(common + SheetRow.COVERS_TAGS + SheetRow.HIDE + SheetRow.MOVE_TO_TRASH, sheetRows(SheetPlace.List, coversTags = true, removeFromQueue = true))
        assertEquals(
            common + SheetRow.REMOVE_FROM_PLAYLIST + SheetRow.HIDE + SheetRow.MOVE_TO_TRASH,
            sheetRows(SheetPlace.Playlist("p1", 0), coversTags = false, removeFromQueue = true),
        )
        assertEquals(
            common + SheetRow.COVERS_TAGS + SheetRow.REMOVE_FROM_QUEUE + SheetRow.HIDE + SheetRow.MOVE_TO_TRASH,
            sheetRows(SheetPlace.Queue("q1", 3), coversTags = true, removeFromQueue = true),
        )
        // Until P2 fills its slot, a queue's song has no Remove from queue.
        assertEquals(common + SheetRow.HIDE + SheetRow.MOVE_TO_TRASH, sheetRows(SheetPlace.Queue("q1", 3), coversTags = false, removeFromQueue = false))
    }

    // An album's or an artist's "⋯"

    @Test
    fun `an album's menu has Play next, Add to, Shuffle, Go to artist, Covers and tags, then Edit album`() {
        assertEquals(
            listOf(CollectionRow.PLAY_NEXT, CollectionRow.ADD_TO, CollectionRow.SHUFFLE, CollectionRow.GO_TO_ARTIST, CollectionRow.COVERS_TAGS, CollectionRow.EDIT_ALBUM),
            albumRows(artistPage = true, coversTags = true),
        )
        // An album artist without a page has no Go to artist; until its slot is filled, no Covers & tags.
        assertEquals(listOf(CollectionRow.PLAY_NEXT, CollectionRow.ADD_TO, CollectionRow.SHUFFLE, CollectionRow.EDIT_ALBUM), albumRows(artistPage = false, coversTags = false))
    }

    @Test
    fun `an artist's menu has Play next, Add to, Shuffle, Rename artist and Merge with`() {
        assertEquals(
            listOf(CollectionRow.PLAY_NEXT, CollectionRow.ADD_TO, CollectionRow.SHUFFLE, CollectionRow.RENAME_ARTIST, CollectionRow.MERGE_WITH),
            artistRows(),
        )
    }

    @Test
    fun `each row of an album's or an artist's menu calls its own action, once`() {
        val calls = mutableListOf<String>()
        val actions = object : CollectionActions {
            override fun playNext() { calls += "play next" }

            override fun addTo() { calls += "add to" }

            override fun shuffle() { calls += "shuffle" }

            override fun goToArtist() { calls += "go to artist" }

            override fun coversTags() { calls += "covers and tags" }

            override fun editAlbum() { calls += "edit album" }

            override fun renameArtist() { calls += "rename artist" }

            override fun mergeWith() { calls += "merge with" }
        }
        val expected = mapOf(
            CollectionRow.PLAY_NEXT to "play next",
            CollectionRow.ADD_TO to "add to",
            CollectionRow.SHUFFLE to "shuffle",
            CollectionRow.GO_TO_ARTIST to "go to artist",
            CollectionRow.COVERS_TAGS to "covers and tags",
            CollectionRow.EDIT_ALBUM to "edit album",
            CollectionRow.RENAME_ARTIST to "rename artist",
            CollectionRow.MERGE_WITH to "merge with",
        )
        for (row in CollectionRow.entries) {
            calls.clear()
            actions.act(row)
            assertEquals("$row", listOf(expected.getValue(row)), calls)
        }
    }

    // Add to

    @Test
    fun `Add to offers the queue playing first, then saved and recent queues, Favourites, then the playlists by name`() {
        val playing = QueueTarget("q0", "Album · Harbour Glass", 12, saved = false, playing = true)
        val recent = QueueTarget("q1", "Folder · Rips", 30, saved = false, playing = false)
        val saved = QueueTarget("q2", "Sunday chores", 8, saved = true, playing = false)
        val older = QueueTarget("q3", "Road trip", 20, saved = true, playing = false)
        assertEquals(
            listOf(
                AddTarget.PlayNext,
                AddTarget.AddToQueue(playing),
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
        // A saved queue playing shows once, as the queue playing.
        val savedPlaying = saved.copy(playing = true)
        assertEquals(
            listOf(AddTarget.PlayNext, AddTarget.AddToQueue(savedPlaying), AddTarget.Queue(older), AddTarget.NewQueue, AddTarget.Favourites, AddTarget.NewPlaylist),
            addTargets(listOf(savedPlaying, older), emptyList()),
        )
        // With nothing playing, Play next and Add to queue make a queue of the songs.
        assertEquals(listOf(AddTarget.PlayNext, AddTarget.AddToQueue(null), AddTarget.NewQueue, AddTarget.Favourites, AddTarget.NewPlaylist), addTargets(emptyList(), emptyList()))
    }

    /** The player's queue commands on a real [QueueBook], as player/PlayerConnection.kt runs them on the core. */
    private class BookPlayback(var book: QueueBook) : PlaybackCommands {
        private val picked = QueueSource(SourceKind.PICKED, "picked:9", "")
        override val queues: StateFlow<List<QueueTarget>> = MutableStateFlow(emptyList())

        override fun play(songs: List<SongRef>, start: Int, source: QueueSource) = Unit

        override fun shuffle(songs: List<SongRef>, source: QueueSource) = Unit

        override fun playNext(songs: List<SongRef>): Undo? = book.playNext(songs, picked, now = 9)?.let { edited ->
            book = edited.book
            Undo { book = book.restore(edited.queueId, edited.before) }
        }

        override fun addToQueue(songs: List<SongRef>): Undo? = book.addToCurrent(songs, picked, now = 9)?.takeIf { it.added > 0 }?.let { added ->
            book = added.book
            Undo { book = book.removeUids(added.queueId, added.uids) }
        }

        override fun addToQueue(id: String, songs: List<SongRef>): QueueAdded? = book.addTo(id, songs)?.let { added ->
            book = added.book
            QueueAdded(added.added, added.already, if (added.added > 0) Undo { book = book.removeUids(added.queueId, added.uids) } else null)
        }

        override fun newQueue(name: String, songs: List<SongRef>, source: QueueSource?): Undo? = null
    }

    @Test
    fun `Add to end on the queue playing adds each song once, and says Already in its name for a song it has`() = runBlocking {
        val album = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")
        val start = QueueBook().play(listOf(lantern.ref, bell.ref), 0, album, now = 1).book.place(0, 42_000)
        val player = BookPlayback(start)
        val actions = SongActions(player, MemoryPlaylists())
        val playing = QueueTarget(start.currentId!!, "Harbour Glass", 2, saved = false, playing = true)

        assertEquals(Said.AlreadyIn("Harbour Glass", 1), actions.addToEnd(playing, listOf(bell.ref))?.said)
        assertEquals(start, player.book)

        val added = actions.addToEnd(playing, listOf(lantern.ref, water.ref))
        assertEquals(Said.AddedTo("Harbour Glass", 1, already = 1), added?.said)
        assertEquals(listOf(lantern.path, bell.path, water.path), player.book.current!!.items.map { it.song.path })
        assertEquals(42_000L, player.book.current!!.positionMs)
        assertTrue(added?.undo?.invoke() == true)
        assertEquals(start.current!!.items, player.book.current!!.items)

        // Nothing playing: Add to queue makes a queue of the songs.
        val none = BookPlayback(QueueBook())
        assertEquals(Said.AddedToQueue, SongActions(none, MemoryPlaylists()).addToEnd(null, listOf(water.ref))?.said)
        assertEquals(listOf(water.path), none.book.current!!.items.map { it.song.path })
    }

    @Test
    fun `adding to a saved queue that is not playing changes that queue only`() = runBlocking {
        val album = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")
        val picked = QueueSource(SourceKind.PICKED, "picked:1", "")
        val playingBook = QueueBook().play(listOf(lantern.ref, bell.ref), 1, album, now = 1).book.place(1, 42_000)
        val start = playingBook.newQueue("Road trip", listOf(water.ref), picked, now = 2)!!.book
        val road = start.saved.single()
        val player = BookPlayback(start)
        val actions = SongActions(player, MemoryPlaylists())
        val target = QueueTarget(road, "Road trip", 1, saved = true, playing = false)

        val added = actions.addToQueue(target, listOf(water.ref, lantern.ref))
        assertEquals(Said.AddedTo("Road trip", 1, already = 1), added?.said)
        assertEquals(listOf(water.path, lantern.path), player.book.queue(road)!!.items.map { it.song.path })
        // The queue playing, its place and the player's moves are as they were.
        assertEquals(start.currentId, player.book.currentId)
        assertEquals(start.current, player.book.current)
        assertEquals(start.jumps, player.book.jumps)
        assertEquals(Said.AlreadyIn("Road trip", 1), actions.addToQueue(target, listOf(lantern.ref))?.said)
        assertEquals(2, player.book.queue(road)!!.items.size)
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

    @Test
    fun `a list still loading keeps the selection, and the list once loaded trims it`() {
        val picked = SongSelection(setOf(lantern.path, bell.path))

        assertEquals(picked, picked.within(null))
        assertEquals(SongSelection(setOf(lantern.path)), picked.within(listOf(lantern, water)))
        assertEquals(SongSelection(), picked.within(emptyList()))
    }

    @Test
    fun `a large selection or sheet stays out of the saved state, and a turn of the phone still finds it`() {
        fun <T : Any> roundTrip(saver: Saver<T, Any>, value: T): Pair<Any?, T?> {
            val saved = with(saver) { SaverScope { true }.save(value) }
            return saved to saved?.let(saver::restore)
        }
        val paths = (1..10_000).map { "Music/Made Up/Song $it.mp3" }

        val some = SongSelection(paths.take(SAVED_SONGS_MAX).toSet())
        val (savedSome, someBack) = roundTrip(SongSelection.Saver, some)
        assertEquals(SAVED_SONGS_MAX, (savedSome as List<*>).size)
        assertEquals(some, someBack)
        val all = SongSelection(paths.toSet())
        val (savedAll, allBack) = roundTrip(SongSelection.Saver, all)
        assertFalse(savedAll is Collection<*>)
        assertEquals(all, allBack)

        val menu = OpenSheet.Menu(lantern.ref)
        val (savedMenu, menuBack) = roundTrip(SongSheetsSaver, SongSheets(menu))
        assertTrue(savedMenu is String)
        assertEquals(menu, menuBack?.open)
        val addAll = OpenSheet.AddTo(paths.map { SongRef(it) }, null, "Songs")
        val (savedAddAll, addAllBack) = roundTrip(SongSheetsSaver, SongSheets(addAll))
        assertFalse(savedAddAll is String)
        assertEquals(addAll, addAllBack?.open)
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

    // Hide (features 2026-11-15, item 6)

    /** settings.json in memory; [fail] makes every write fail. */
    private class MemorySettings(var now: Settings = Settings(), var fail: Boolean = false) : SettingsStore {
        override val settings: Flow<Settings> get() = flowOf(now)

        override suspend fun update(change: (Settings) -> Settings): Boolean = !fail && run {
            now = change(now)
            true
        }
    }

    @Test
    fun `Hide hides the songs, says Hidden, and Undo shows again only those it hid`() = runBlocking {
        val store = MemorySettings(Settings(library = LibrarySettings(hiddenSongs = listOf(water.ref))))
        val actions = SongActions(NoPlayback, MemoryPlaylists())

        val hidden = actions.hide(listOf(lantern.ref, water.ref, lantern.ref), store)
        assertEquals(Said.Hidden(2), hidden.said)
        // Each with its MediaStore id, so a rename or a move keeps it hidden.
        assertEquals(listOf(water.ref, lantern.ref), store.now.library.hiddenSongs)
        assertEquals(11L, store.now.library.hiddenSongs.last().mediaStoreId)
        // Another song hidden while the toast is up stays hidden; so does the one hidden before.
        actions.hide(listOf(bell.ref), store)
        assertTrue(hidden.undo?.invoke() == true)
        assertEquals(listOf(water.ref, bell.ref), store.now.library.hiddenSongs)
    }

    @Test
    fun `Unhide in Settings shows the songs again, with Undo, and a write that fails changes nothing`() = runBlocking {
        val store = MemorySettings(Settings(library = LibrarySettings(hiddenSongs = listOf(water.ref, lantern.ref))))
        val actions = SongActions(NoPlayback, MemoryPlaylists())

        val shown = actions.unhide(listOf(lantern.ref), store)
        assertEquals(Said.Unhidden(1), shown.said)
        assertEquals(listOf(water.ref), store.now.library.hiddenSongs)
        assertTrue(shown.undo?.invoke() == true)
        assertEquals(listOf(water.ref, lantern.ref), store.now.library.hiddenSongs)

        store.fail = true
        assertEquals(Said.NotSaved, actions.hide(listOf(bell.ref), store).said)
        assertEquals(listOf(water.ref, lantern.ref), store.now.library.hiddenSongs)
    }

    @Test
    fun `Hide folder adds it to Folders to skip, says so, and Undo takes it out again`() = runBlocking {
        val store = MemorySettings(Settings(library = LibrarySettings(foldersToSkip = listOf("Recordings"))))
        val actions = SongActions(NoPlayback, MemoryPlaylists())

        val hidden = actions.hideFolder("Music/Night Ferry", store)
        assertEquals(Said.FolderHidden, hidden.said)
        assertEquals(listOf("Recordings", "Music/Night Ferry"), store.now.library.foldersToSkip)
        assertTrue(hidden.undo?.invoke() == true)
        assertEquals(listOf("Recordings"), store.now.library.foldersToSkip)
    }

    @Test
    fun `the toasts say Hidden, how many songs, and Hidden songs is a short label`() {
        val strings = File("src/main/res/values/strings_library_extras.xml").readText()
        assertTrue("""<string name="lib_hidden">Hidden</string>""" in strings)
        assertTrue("""<string name="lib_hide">Hide</string>""" in strings)
        assertTrue("""<string name="set_hidden">Hidden songs</string>""" in strings)
        assertTrue("""<string name="lib_hide_folder">Hide folder</string>""" in strings)
        assertTrue("""<string name="lib_unhide">Unhide</string>""" in strings)
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
    fun `a playlist deleted from its page opens again with its Undo, and not when the Undo could not be stored`() = runBlocking {
        val store = MemoryPlaylists(PlaylistsState(listOf(roadTrip)))
        val actions = SongActions(NoPlayback, store)
        var reopened = 0
        val deleted = actions.delete(roadTrip) { reopened++ }
        assertEquals(Said.PlaylistDeleted, deleted.said)
        assertEquals(0, reopened)
        assertEquals(true, deleted.undo?.invoke())
        assertEquals(roadTrip, store.now.playlist("p1"))
        assertEquals(1, reopened)

        val failing = MemoryPlaylists(PlaylistsState(listOf(roadTrip)))
        val undo = SongActions(NoPlayback, failing).delete(roadTrip) { reopened++ }.undo!!
        failing.fail = true
        assertEquals(false, undo())
        assertEquals(1, reopened)
    }

    @Test
    fun `a rename starts with the whole name selected, so typing replaces it, and a new name starts empty`() {
        assertEquals("Road trip", nameField("Road trip").text)
        assertEquals(TextRange(0, "Road trip".length), nameField("Road trip").selection)
        assertEquals(TextRange(0), nameField("").selection)
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

    @Test
    fun `a move in a custom order is stored at once with no toast, and says when it could not be`() = runBlocking {
        val store = MemoryPlaylists(PlaylistsState(listOf(roadTrip.copy(songs = listOf(lantern.ref, bell.ref, water.ref))), favouriteSongs = listOf(water.ref, bell.ref)))
        val actions = SongActions(NoPlayback, store)

        assertNull(actions.arrange(roadTrip, listOf(water.ref, lantern.ref, bell.ref)))
        assertNull(actions.arrangeFavourites(listOf(bell.ref, water.ref)))
        assertEquals(listOf(water.ref, lantern.ref, bell.ref), store.now.playlist("p1")?.songs)
        assertEquals(listOf(bell.ref, water.ref), store.now.favouriteSongs)

        store.fail = true
        assertEquals(Said.NotSaved, actions.arrange(roadTrip, listOf(lantern.ref, water.ref, bell.ref))?.said)
        assertEquals(Said.NotSaved, actions.arrangeFavourites(listOf(water.ref, bell.ref))?.said)
        assertEquals(listOf(water.ref, lantern.ref, bell.ref), store.now.playlist("p1")?.songs)
    }

    // The hearts

    @Test
    fun `every heart tells TalkBack what a tap does, Remove from Favourites while it is on`() {
        assertEquals(R.string.lib_favourite_song, heartLabel(on = false))
        assertEquals(R.string.lib_unfavourite, heartLabel(on = true))
        assertEquals(R.string.lib_favourite_album, heartLabel(CollectionKind.ALBUM, on = false))
        assertEquals(R.string.lib_favourite_artist, heartLabel(CollectionKind.ARTIST, on = false))
        assertEquals(R.string.lib_favourite_folder, heartLabel(CollectionKind.FOLDER, on = false))
        assertEquals(R.string.lib_favourite_playlist, heartLabel(CollectionKind.PLAYLIST, on = false))
        CollectionKind.entries.forEach { assertEquals(R.string.lib_unfavourite, heartLabel(it, on = true)) }
    }
}
