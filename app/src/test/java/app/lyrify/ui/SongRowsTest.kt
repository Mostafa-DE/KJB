package app.lyrify.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.unit.Density
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.library.Library
import app.lyrify.library.LibraryState
import app.lyrify.library.PlaylistsJson
import app.lyrify.library.PlaylistsState
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.NoPlayback
import app.lyrify.player.PlaybackCommands
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.ListSort
import app.lyrify.settings.Settings
import app.lyrify.settings.SongList
import app.lyrify.settings.SortSongs
import app.lyrify.stats.SongPlays
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Song rows (user feedback 2026-11-13): the round check picks a song, each highlighted row has its own fill, and
 * Favourites and playlists list in a custom order that a row's handle changes. Made-up names only.
 */
class SongRowsTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", addedAt = 1)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", addedAt = 3)
    private val water = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", addedAt = 2)
    private val kite = song("Music/Night Ferry/02 Glass Kite.mp3", title = "Glass Kite", addedAt = 4)
    private val songs = listOf(lantern, bell, water, kite)
    private val library = Library.of(songs, Settings())

    // The round check

    /** A page of [songs] selecting [selection], which [onSelection] changes; [plays] hears every song played. */
    private fun page(selection: SongSelection, onSelection: (SongSelection) -> Unit, plays: MutableList<Int>): PageSongs {
        val playback = object : PlaybackCommands by NoPlayback {
            override fun play(songs: List<SongRef>, start: Int, source: QueueSource) {
                plays += start
            }
        }
        val ui = LibraryUi(LibraryState.Ready(library), Settings(), PlaylistsState(), queues = emptyList(), ledger = LedgerState())
        return PageSongs(ui, songs, QueueSource(SourceKind.SONGS, "songs", "Songs"), SongSheets(), selection, onSelection, playback, "Songs")
    }

    @Test
    fun `a tap on a row's round check picks or unpicks that song as a tap on the row does, and never plays`() {
        var selection = SongSelection(setOf(lantern.path))
        val plays = mutableListOf<Int>()
        fun list() = page(selection, { selection = it }, plays)

        list().check(bell)
        assertEquals(setOf(lantern.path, bell.path), selection.paths)
        list().check(lantern)
        assertEquals(setOf(bell.path), selection.paths)
        // A tap on the row while selecting does the same.
        list().tap(water, 2)
        assertEquals(setOf(bell.path, water.path), selection.paths)
        // The last one unpicked ends the selection, as on the row.
        list().check(bell)
        list().check(water)
        assertFalse(selection.active)
        assertEquals(emptyList<Int>(), plays)
    }

    // Highlighted rows

    @Test
    fun `each highlighted row has its own rounded fill, 8 dp in from the sides and 2 dp from the ends, so neighbours never touch`() {
        // 1 dp is 2 px; a 64 dp row on a 360 dp screen.
        val density = Density(2f)
        val row = Size(720f, 128f)

        val fill = rowFill(row, density)

        assertEquals(16f, fill.left)
        assertEquals(704f, fill.right)
        assertEquals(4f, fill.top)
        assertEquals(124f, fill.bottom)
        listOf(fill.topLeftCornerRadius, fill.topRightCornerRadius, fill.bottomLeftCornerRadius, fill.bottomRightCornerRadius).forEach { assertEquals(CornerRadius(24f), it) }
        // The row below, selected too: 4 dp of the page between the two fills.
        val below = rowFill(row, density).translate(Offset(0f, row.height))
        assertEquals(8f, below.top - fill.bottom)
        // A taller row (large text) keeps the same insets.
        assertEquals(196f, rowFill(Size(720f, 200f), density).bottom)
    }

    // The custom order

    @Test
    fun `Favourites and playlists open in their custom order, which shows what was stored before as it was`() {
        assertEquals(ListSort.OWN, SongList.PLAYLIST.default)
        assertEquals(ListSort.OWN, SongList.FAVOURITES.default)
        // A file as KJB wrote it before: the playlist in the order its songs were added, Favourites the latest first.
        val before = """{"schemaVersion":1,"playlists":[{"id":"p1","name":"Road trip","createdAt":5,"songs":[{"path":"${water.path}"},{"path":"${lantern.path}"},{"path":"${bell.path}"}]}],
            "favourites":{"songs":[{"path":"${kite.path}"},{"path":"${lantern.path}"}],"collections":[]}}"""
        val state = checkNotNull(PlaylistsJson.decode(before))

        val playlist = library.listed(checkNotNull(state.playlist("p1")).songs)
        assertEquals(listOf(water, lantern, bell), inOrder(playlist, SongList.PLAYLIST.default, LibrarySettings()) { it })
        assertEquals(listOf(kite, lantern), inOrder(Favourites.of(state, library).songs, SongList.FAVOURITES.default, LibrarySettings()) { it })
    }

    @Test
    fun `a list sorted by plays waits for them, never drawn first in the order of a list without any`() {
        val entries = songs.mapIndexed { at, song -> at to song }
        val plays = mapOf(water.path to SongPlays(water.ref, 4, 9, 0), lantern.path to SongPlays(lantern.ref, 2, 8, 0))

        assertNull(inOrderOnce(entries, ListSort(SortSongs.PLAY_COUNT), LibrarySettings(), plays = null) { it.second })
        assertNull(inOrderOnce(entries, ListSort(SortSongs.LAST_PLAYED, reversed = true), LibrarySettings(), plays = null) { it.second })
        assertEquals(listOf(water, lantern), inOrderOnce(entries, ListSort(SortSongs.PLAY_COUNT), LibrarySettings(), plays) { it.second }!!.map { it.second }.take(2))
        // The other keys and the own order need no plays.
        assertEquals(entries, inOrderOnce(entries, ListSort.OWN, LibrarySettings(), plays = null) { it.second })
        assertEquals(listOf(kite, water, lantern, bell), inOrderOnce(entries, ListSort(SortSongs.TITLE), LibrarySettings(), plays = null) { it.second }!!.map { it.second })
        // The Songs tab and every page sort off the main thread, and only once the plays they need are read.
        val custom = File("src/main/java/app/lyrify/ui/CustomOrder.kt").readText()
        assertTrue(custom.contains("withContext(Dispatchers.Default) { inOrderOnce("))
        val collections = File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText()
        assertTrue("rememberInOrder(" in collections)
        assertFalse(Regex("""(?<![A-Za-z])inOrder\(""").containsMatchIn(collections))
        val library = File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText()
        assertTrue(library.substringAfter("private fun SongsTab(").substringBefore("\n}\n").contains("val known = plays ?: return@produceState"))
    }

    @Test
    fun `an artist's page opens in its album order, the newest album first, which its sheet offers first`() {
        assertEquals(ListSort.OWN, SongList.ARTIST.default)
        assertTrue(SongList.ARTIST.own)
        val strings = File("src/main/res/values/strings_library_extras.xml").readText()
        assertTrue("""<string name="lib_sort_album_order">Album order</string>""" in strings)
    }

    @Test
    fun `the other orders sort by title or the latest added first, places going with their songs, and only the custom order has handles`() {
        val entries = songs.mapIndexed { at, song -> at to song }

        assertEquals(listOf(kite, water, lantern, bell), inOrder(entries, ListSort(SortSongs.TITLE), LibrarySettings()) { it.second }.map { it.second })
        assertEquals(listOf(3, 2, 0, 1), inOrder(entries, ListSort(SortSongs.TITLE), LibrarySettings()) { it.second }.map { it.first })
        assertEquals(listOf(kite, bell, water, lantern), inOrder(entries, ListSort(SortSongs.RECENTLY_ADDED), LibrarySettings()) { it.second }.map { it.second })
        assertEquals(listOf(lantern, water, bell, kite), inOrder(entries, ListSort(SortSongs.RECENTLY_ADDED, reversed = true), LibrarySettings()) { it.second }.map { it.second })
        assertEquals(entries, inOrder(entries, ListSort.OWN, LibrarySettings()) { it.second })
        // Every key a page offers sorts, the plays too; only the custom order has handles.
        val plays = mapOf(water.path to SongPlays(water.ref, 4, 9, 0), lantern.path to SongPlays(lantern.ref, 2, 8, 0))
        assertEquals(listOf(water, lantern), inOrder(entries, ListSort(SortSongs.PLAY_COUNT), LibrarySettings(), plays) { it.second }.map { it.second }.take(2))
        assertEquals(listOf(true, false, false), listOf(ListSort.OWN, ListSort(SortSongs.TITLE), ListSort(SortSongs.TITLE, reversed = true)).map { it.reorders })
        // The words: labels of three words or fewer.
        val strings = File("src/main/res/values/strings_library.xml").readText()
        assertTrue("""<string name="lib_sort_custom">Custom order</string>""" in strings)
        assertTrue("""<string name="lib_move_up">Move up</string>""" in strings)
        assertTrue("""<string name="lib_move_down">Move down</string>""" in strings)
    }

    @Test
    fun `a move in Favourites goes between the stored places of the songs shown, a song hidden between them staying put`() {
        val gone = SongRef("/storage/emulated/0/Music/Gone/Old Pier.mp3")
        val state = PlaylistsState(favouriteSongs = listOf(kite.ref, gone, lantern.ref, water.ref))
        val favourites = Favourites.of(state, library)

        assertEquals(listOf(kite, lantern, water), favourites.songs)
        assertEquals(listOf(0, 2, 3), favourites.songPlaces)
        // Water dragged to the top of what shows: the order shown is stored, the song hidden keeping its place.
        val moved = state.arrangeFavourites(listOf(water.ref, kite.ref, lantern.ref))
        assertEquals(listOf(water.ref, gone, kite.ref, lantern.ref), moved.favouriteSongs)
        assertEquals(listOf(water, kite, lantern), Favourites.of(moved, library).songs)
    }

    // Dragging a handle

    @Test
    fun `a drag on a handle moves the song past each row it passes half of, a tick a place, and the move is stored when let go`() {
        var ticks = 0
        var kept = 0
        val stored = mutableListOf<List<Int>>()
        val reorder = SongReorder(tick = { ticks++ }, keepPlace = { kept++ }, store = { stored += it })

        reorder.start(songs, 0, "song ${lantern.path}")
        assertEquals(0, reorder.held)
        assertEquals("song ${lantern.path}", reorder.heldKey)
        reorder.drag(40f, ROW)
        assertEquals(listOf(0, 1, 2, 3), reorder.shown(songs))
        assertEquals(40f, reorder.offset)
        // Past half of Tide Bell: it takes its place, 40 px on from there.
        reorder.drag(100f, ROW)
        assertEquals(listOf(1, 0, 2, 3), reorder.shown(songs))
        assertEquals(40f, reorder.offset)
        // A fast move passes two rows at once, a tick for each.
        reorder.drag(120f, ROW)
        assertEquals(listOf(1, 2, 3, 0), reorder.shown(songs))
        assertEquals(3, ticks)
        assertTrue(kept >= 2)
        assertTrue(stored.isEmpty())

        // Let go: the list's new order goes to be stored and shown by the page ([OrderedEntries]).
        assertEquals(listOf(1, 2, 3, 0), reorder.drop(songs))
        assertEquals(listOf(listOf(1, 2, 3, 0)), stored)
        assertNull(reorder.held)
    }

    @Test
    fun `a song dragged back to its place, or let go on a list that changed, stores nothing`() {
        val stored = mutableListOf<List<Int>>()
        val reorder = SongReorder(store = { stored += it })

        reorder.start(songs, 2, "song ${water.path}")
        reorder.drag(-60f, ROW)
        assertEquals(listOf(0, 2, 1, 3), reorder.shown(songs))
        reorder.drag(60f, ROW)
        assertNull(reorder.drop(songs))
        assertEquals(listOf(0, 1, 2, 3), reorder.shown(songs))

        reorder.start(songs, 0, "song ${lantern.path}")
        reorder.drag(60f, ROW)
        assertNull(reorder.drop(songs.reversed()))
        assertTrue(stored.isEmpty())

        // Past either end the row stays at the end.
        reorder.start(songs, 3, "song ${kite.path}")
        reorder.drag(500f, ROW)
        assertEquals(listOf(0, 1, 2, 3), reorder.shown(songs))
    }

    @Test
    fun `near an edge a held row scrolls the list that way, faster the closer, and not at all elsewhere`() {
        // A list from 0 to 1000 px, its edges 100 px deep, at most 20 px this frame; rows 100 px tall.
        assertEquals(0f, edgeScroll(400f, 500f, 0f, 1000f, 100f, 20f))
        assertEquals(0f, edgeScroll(100f, 200f, 0f, 1000f, 100f, 20f))
        assertEquals(-10f, edgeScroll(50f, 150f, 0f, 1000f, 100f, 20f))
        assertEquals(-20f, edgeScroll(-30f, 70f, 0f, 1000f, 100f, 20f))
        assertEquals(10f, edgeScroll(850f, 950f, 0f, 1000f, 100f, 20f))
        assertEquals(20f, edgeScroll(950f, 1050f, 0f, 1000f, 100f, 20f))
    }

    @Test
    fun `TalkBack offers Move up and Move down, never past either end, and stores the move at once`() {
        assertEquals(listOf(RowMove(R.string.lib_move_down, 1)), rowMoves(0, 3))
        assertEquals(listOf(RowMove(R.string.lib_move_up, 0), RowMove(R.string.lib_move_down, 2)), rowMoves(1, 3))
        assertEquals(listOf(RowMove(R.string.lib_move_up, 1)), rowMoves(2, 3))
        assertEquals(emptyList<RowMove>(), rowMoves(0, 1))

        val stored = mutableListOf<List<Int>>()
        SongReorder(store = { stored += it }).move(1, 0, 3)
        assertEquals(listOf(listOf(1, 0, 2)), stored)
    }

    @Test
    fun `the auto-scroll's edges are those of the room the bars leave the list, its padding not counted twice`() {
        // A 1000 px list under a 100 px top bar and over 200 px of Now bar and dock: Compose starts the rows' offsets
        // after the top padding (the viewport starts 100 px before them), so the room shown runs from 0 to 700.
        val info = layout(size = 1000, before = 100, after = 200)
        fun step(offset: Int) = edgeStep(info, row(offset), 0f, edge = 50f, most = 10f)

        assertEquals(0f, step(300))
        // Just under the top bar, and just over the Now bar.
        assertEquals(-10f, step(-10))
        assertEquals(-4f, step(30))
        assertEquals(4f, step(610))
        assertEquals(10f, step(640))
    }

    // Stable entries, and the writes in turn

    @Test
    fun `a song there twice keeps its own row when dragged past its copy, before and after the move is stored`() {
        val twice = listOf(lantern.ref, bell.ref, lantern.ref)
        val entries = OrderedEntries()
        val (_, keys) = entries.of(twice)
        assertEquals(3, keys.toSet().size)

        // The first Paper Lantern dragged to the end.
        val written = entries.arrange(listOf(1, 2, 0))
        assertEquals(listOf(bell.ref, lantern.ref, lantern.ref), written)
        val shown = entries.of(twice)
        assertEquals(listOf(bell.ref, lantern.ref, lantern.ref), shown.first)
        assertEquals(listOf(keys[1], keys[2], keys[0]), shown.second)
        // Stored: the same list comes back, each copy keeping the key it had.
        assertEquals(listOf(keys[1], keys[2], keys[0]), entries.of(listOf(bell.ref, lantern.ref, lantern.ref)).second)
        // A song added at the end gets a key of its own, the others keep theirs.
        val added = entries.of(listOf(bell.ref, lantern.ref, lantern.ref, water.ref)).second
        assertEquals(listOf(keys[1], keys[2], keys[0]), added.take(3))
        assertFalse(added[3] in keys)
    }

    @Test
    fun `two quick moves are written in turn, each the whole order shown, so the order stored is the one shown last`() = runBlocking {
        val entries = OrderedEntries()
        entries.of(listOf(lantern.ref, bell.ref, water.ref))
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<List<SongRef>>()
        val store: suspend (List<SongRef>) -> Boolean = { order ->
            writes += order
            if (writes.size == 1) gate.await()
            true
        }

        // Low Water dragged to the top, then, before that is stored, Tide Bell to the end.
        val first = launch(start = CoroutineStart.UNDISPATCHED) { entries.write(entries.arrange(listOf(2, 0, 1)), store) }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { entries.write(entries.arrange(listOf(0, 2, 1)), store) }
        // Both show at once; the second is written only once the first is.
        assertEquals(listOf(water.ref, bell.ref, lantern.ref), entries.of(listOf(lantern.ref, bell.ref, water.ref)).first)
        assertEquals(1, writes.size)
        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf(listOf(water.ref, lantern.ref, bell.ref), listOf(water.ref, bell.ref, lantern.ref)), writes)
        assertEquals(listOf(water.ref, bell.ref, lantern.ref), entries.of(listOf(lantern.ref, bell.ref, water.ref)).first)
    }

    @Test
    fun `a song removed while a move is written leaves the order shown, and a move not stored gives way to the stored list`() = runBlocking {
        val entries = OrderedEntries()
        entries.of(listOf(lantern.ref, bell.ref, water.ref))
        val written = entries.arrange(listOf(2, 0, 1))
        // Tide Bell removed meanwhile: the stored list is what shows, the rows keeping their keys.
        val keys = entries.of(listOf(lantern.ref, bell.ref, water.ref)).second
        val (shown, after) = entries.of(listOf(lantern.ref, water.ref))
        assertEquals(listOf(lantern.ref, water.ref), shown)
        assertEquals(listOf(keys[1], keys[0]), after)
        assertEquals(listOf(water.ref, lantern.ref, bell.ref), written)

        // A move that could not be stored: the stored list shows again.
        entries.arrange(listOf(1, 0))
        assertFalse(entries.write(listOf(water.ref, lantern.ref)) { false })
        assertEquals(listOf(lantern.ref, water.ref), entries.of(listOf(lantern.ref, water.ref)).first)
    }

    /** A list's layout as Compose gives it, [size] px tall with [before] and [after] px of padding. */
    private fun layout(size: Int, before: Int, after: Int): LazyListLayoutInfo = object : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = emptyList()
        override val viewportStartOffset: Int = -before
        override val viewportEndOffset: Int = size - before
        override val totalItemsCount: Int = 10
        override val beforeContentPadding: Int = before
        override val afterContentPadding: Int = after
    }

    /** A held row 60 px tall at [offset] in its list's layout. */
    private fun row(offset: Int): LazyListItemInfo = object : LazyListItemInfo {
        override val index: Int = 3
        override val key: Any = "row"
        override val offset: Int = offset
        override val size: Int = 60
    }

    private companion object {
        const val ROW: Float = 100f
    }
}
