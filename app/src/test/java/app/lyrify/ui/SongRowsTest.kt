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
import app.lyrify.settings.Settings
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
        assertEquals(ListOrder.CUSTOM, ListOrder.START)
        // A file as KJB wrote it before: the playlist in the order its songs were added, Favourites the latest first.
        val before = """{"schemaVersion":1,"playlists":[{"id":"p1","name":"Road trip","createdAt":5,"songs":[{"path":"${water.path}"},{"path":"${lantern.path}"},{"path":"${bell.path}"}]}],
            "favourites":{"songs":[{"path":"${kite.path}"},{"path":"${lantern.path}"}],"collections":[]}}"""
        val state = checkNotNull(PlaylistsJson.decode(before))

        val playlist = library.listed(checkNotNull(state.playlist("p1")).songs)
        assertEquals(listOf(water, lantern, bell), inOrder(playlist, ListOrder.START, LibrarySettings()) { it })
        assertEquals(listOf(kite, lantern), inOrder(Favourites.of(state, library).songs, ListOrder.START, LibrarySettings()) { it })
    }

    @Test
    fun `the other orders sort by title or the latest added first, places going with their songs, and only the custom order has handles`() {
        val entries = songs.mapIndexed { at, song -> at to song }

        assertEquals(listOf(kite, water, lantern, bell), inOrder(entries, ListOrder.TITLE, LibrarySettings()) { it.second }.map { it.second })
        assertEquals(listOf(3, 2, 0, 1), inOrder(entries, ListOrder.TITLE, LibrarySettings()) { it.second }.map { it.first })
        assertEquals(listOf(kite, bell, water, lantern), inOrder(entries, ListOrder.RECENTLY_ADDED, LibrarySettings()) { it.second }.map { it.second })
        assertEquals(entries, inOrder(entries, ListOrder.CUSTOM, LibrarySettings()) { it.second })
        assertEquals(listOf(true, false, false), ListOrder.entries.map { it.reorders })
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
        // Water dragged to the top of what shows.
        val moved = state.moveFavourite(favourites.songPlaces[2], favourites.songPlaces[0])
        assertEquals(listOf(water.ref, kite.ref, gone, lantern.ref), moved.favouriteSongs)
        assertEquals(listOf(water, kite, lantern), Favourites.of(moved, library).songs)
    }

    // Dragging a handle

    @Test
    fun `a drag on a handle moves the song past each row it passes half of, a tick a place, and the move is stored when let go`() {
        var ticks = 0
        var kept = 0
        val stored = mutableListOf<Pair<Int, Int>>()
        val reorder = SongReorder(tick = { ticks++ }, keepPlace = { kept++ }, store = { from, to -> stored += from to to })

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

        assertEquals(0 to 3, reorder.drop(songs))
        assertEquals(listOf(0 to 3), stored)
        assertNull(reorder.held)
        // The order dropped shows until the list itself has it, then the list's own.
        assertEquals(listOf(1, 2, 3, 0), reorder.shown(songs))
        assertEquals(listOf(0, 1, 2, 3), reorder.shown(listOf(bell, water, kite, lantern)))
    }

    @Test
    fun `a song dragged back to its place, or let go on a list that changed, stores nothing, and one not stored shows the list's own order again`() {
        val stored = mutableListOf<Pair<Int, Int>>()
        val reorder = SongReorder(store = { from, to -> stored += from to to })

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

        reorder.start(songs, 0, "song ${lantern.path}")
        reorder.drag(60f, ROW)
        assertEquals(0 to 1, reorder.drop(songs))
        reorder.forget()
        assertEquals(listOf(0, 1, 2, 3), reorder.shown(songs))
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

        val stored = mutableListOf<Pair<Int, Int>>()
        SongReorder(store = { from, to -> stored += from to to }).move(1, 0)
        assertEquals(listOf(1 to 0), stored)
    }

    @Test
    fun `a move keeps every row's key, also in a playlist holding a song twice`() {
        val twice = listOf(lantern, bell, lantern, water)
        val keys = songRowKeys(twice, repeats = true)
        assertEquals(4, keys.toSet().size)

        // Tide Bell dragged to the end: it and every other row keep their keys, the two copies theirs in turn.
        val moved = songRowKeys(listOf(lantern, lantern, water, bell), repeats = true)
        assertEquals(keys.toSet(), moved.toSet())
        assertEquals(keys[1], moved[3])
        assertEquals(keys[3], moved[2])
    }

    private companion object {
        const val ROW: Float = 100f
    }
}
