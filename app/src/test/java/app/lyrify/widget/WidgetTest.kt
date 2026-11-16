package app.lyrify.widget

import androidx.media3.common.Player
import app.lyrify.R
import app.lyrify.auto.CoverUris
import app.lyrify.auto.ownCover
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.PlaybackPlace
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.player.withPlace
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortSongs
import app.lyrify.ui.DESTINATION_AI
import app.lyrify.ui.DESTINATION_PLAYER
import app.lyrify.ui.FullScreen
import app.lyrify.ui.destinationView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The home-screen widgets: what they show from the player's state, when they are updated, and what their buttons send
 * to the session. Made-up names only.
 */
class WidgetTest {
    private val songs: List<Song> = (1..3).map {
        song("Music/Harbour Glass/0$it Paper Lantern $it.flac", title = "Paper Lantern $it", album = "Harbour Glass", artist = "Kit Calloway", track = it, id = 40L + it)
    }
    private val library = Library.of(songs, Settings())
    private val refs = songs.map { it.ref }
    private val album = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")
    private val playingSecond = QueueBook().play(refs, 1, album, now = 1).book
    private val settings = LibrarySettings()

    private fun loaded(index: Int, playing: Boolean, favourite: Boolean = false) = WidgetState.Loaded(
        song = refs[index],
        title = "Paper Lantern ${index + 1}",
        artist = "Kit Calloway",
        cover = "${41 + index}",
        playing = playing,
        favourite = favourite,
    )

    // The state from the player's

    @Test
    fun `a song playing shows its title, artist, cover and Pause`() {
        assertEquals(loaded(1, playing = true), widgetState(playingSecond, library, playing = true, favourites = emptyList(), settings))
    }

    @Test
    fun `a song paused shows Play`() {
        assertEquals(loaded(1, playing = false), widgetState(playingSecond, library, playing = false, favourites = emptyList(), settings))
    }

    @Test
    fun `nothing loaded is the empty state, with no queue or the library not read yet`() {
        assertEquals(WidgetState.Empty, widgetState(QueueBook(), library, playing = false, favourites = emptyList(), settings))
        assertEquals(WidgetState.Empty, widgetState(QueueBook(), library, playing = true, favourites = refs, settings))
        assertEquals(WidgetState.Empty, widgetState(playingSecond, library = null, playing = false, favourites = emptyList(), settings))
    }

    @Test
    fun `the heart is on when the song playing is a favourite, by its path`() {
        val byPath = listOf(SongRef(refs[1].path, mediaStoreId = 999))
        assertEquals(loaded(1, playing = true, favourite = true), widgetState(playingSecond, library, playing = true, favourites = byPath, settings))
        assertEquals(loaded(1, playing = true), widgetState(playingSecond, library, playing = true, favourites = listOf(refs[0], refs[2]), settings))
    }

    @Test
    fun `Show file names shows the file's name, and a song not on the phone its file's name without artist or cover`() {
        val named = widgetState(playingSecond, library, playing = false, favourites = emptyList(), settings.copy(showFileNames = true)) as WidgetState.Loaded
        assertEquals("02 Paper Lantern 2.flac", named.title)
        val gone = SongRef("/storage/emulated/0/Music/Gone/Lost Signal.mp3")
        val missing = QueueBook().play(listOf(gone), 0, album, now = 1).book
        assertEquals(
            WidgetState.Loaded(gone, "Lost Signal.mp3", artist = null, cover = null, playing = false, favourite = false),
            widgetState(missing, library, playing = false, favourites = emptyList(), settings),
        )
    }

    // The update triggers

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the widgets are updated only when the song, the play state or the favourite changes`() = runTest(UnconfinedTestDispatcher()) {
        val book = MutableStateFlow(QueueBook())
        val songs = MutableStateFlow<Library?>(null)
        val playing = MutableStateFlow(false)
        val favourites = MutableStateFlow<List<SongRef>>(emptyList())
        val librarySettings = MutableStateFlow(settings)
        val pushed = mutableListOf<WidgetState>()
        val job = launch { widgetStates(book, songs, playing, favourites, librarySettings).collect { pushed += it } }

        assertEquals(listOf<WidgetState>(WidgetState.Empty), pushed)
        songs.value = library
        book.value = playingSecond
        assertEquals(listOf(WidgetState.Empty, loaded(1, playing = false)), pushed)
        pushed.clear()

        playing.value = true
        assertEquals(listOf(loaded(1, playing = true)), pushed)
        pushed.clear()

        // Not a trigger: the place moving, other queues, a library read again, another song's heart, other settings.
        book.value = withPlace(book.value, PlaybackPlace(book.value.currentId, 1, 61_000))
        book.value = book.value.addTo(book.value.currentId!!, listOf(SongRef("/storage/emulated/0/Music/Night Ferry/Loose Thread.mp3")))!!.book
        songs.value = Library.of(this@WidgetTest.songs, Settings())
        favourites.value = listOf(refs[2])
        librarySettings.value = settings.copy(songSort = SortSongs.entries.last())
        assertEquals(emptyList<WidgetState>(), pushed)

        favourites.value = listOf(refs[2], refs[1])
        assertEquals(listOf(loaded(1, playing = true, favourite = true)), pushed)
        pushed.clear()

        val next = book.value.current!!.items[2].uid
        book.value = book.value.at(next, now = 2)
        playing.value = false
        assertEquals(listOf(loaded(2, playing = true, favourite = true), loaded(2, playing = false, favourite = true)), pushed)
        pushed.clear()

        book.value = QueueBook()
        assertEquals(listOf<WidgetState>(WidgetState.Empty), pushed)
        job.cancel()
    }

    // The buttons

    @Test
    fun `Small shows Play or Pause and Next, Large adds Previous first, the empty state only Play`() {
        val playing = loaded(1, playing = true)
        val paused = loaded(1, playing = false)
        assertEquals(listOf(WidgetCommand.PAUSE, WidgetCommand.NEXT), widgetButtons(playing, WidgetSize.SMALL).map { it.command })
        assertEquals(listOf(WidgetCommand.PLAY, WidgetCommand.NEXT), widgetButtons(paused, WidgetSize.SMALL).map { it.command })
        assertEquals(listOf(WidgetCommand.PREVIOUS, WidgetCommand.PAUSE, WidgetCommand.NEXT), widgetButtons(playing, WidgetSize.LARGE).map { it.command })
        assertEquals(listOf(WidgetCommand.PREVIOUS, WidgetCommand.PLAY, WidgetCommand.NEXT), widgetButtons(paused, WidgetSize.LARGE).map { it.command })
        for (size in WidgetSize.entries) assertEquals(listOf(WidgetCommand.PLAY), widgetButtons(WidgetState.Empty, size).map { it.command })
    }

    @Test
    fun `each button has its icon and its label`() {
        assertEquals(
            listOf(
                WidgetButton(WidgetCommand.PREVIOUS, R.drawable.ic_skip_previous_fill, R.string.player_previous),
                WidgetButton(WidgetCommand.PAUSE, R.drawable.ic_pause_fill, R.string.player_pause),
                WidgetButton(WidgetCommand.NEXT, R.drawable.ic_skip_next_fill, R.string.player_next),
            ),
            widgetButtons(loaded(0, playing = true), WidgetSize.LARGE),
        )
        assertEquals(listOf(WidgetButton(WidgetCommand.PLAY, R.drawable.ic_play_arrow_fill, R.string.player_play)), widgetButtons(WidgetState.Empty, WidgetSize.SMALL))
    }

    @Test
    fun `a button's command comes back from its action, and an unknown one sends nothing`() {
        for (command in WidgetCommand.entries) assertEquals(command, WidgetCommand.of(command.name))
        assertNull(WidgetCommand.of(null))
        assertNull(WidgetCommand.of("SHUFFLE"))
    }

    @Test
    fun `each command is one call on the session's player`() {
        val sent = mutableListOf<String>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ -> sent += method.name; null } as Player
        for (command in WidgetCommand.entries) command.sendTo(player)
        assertEquals(listOf("play", "pause", "seekToNext", "seekToPrevious"), sent)
    }

    @Test
    fun `the command was taken once the player shows it, Play only once it plays`() {
        val none: (Int) -> Boolean = { false }
        assertEquals(true, WidgetCommand.PLAY.took(playing = true, happened = none))
        assertEquals(false, WidgetCommand.PLAY.took(playing = false, happened = { it == Player.EVENT_PLAY_WHEN_READY_CHANGED }))
        assertEquals(true, WidgetCommand.PAUSE.took(playing = false, happened = { it == Player.EVENT_PLAY_WHEN_READY_CHANGED }))
        assertEquals(false, WidgetCommand.PAUSE.took(playing = false, happened = none))
        for (skip in listOf(WidgetCommand.NEXT, WidgetCommand.PREVIOUS)) {
            assertEquals(true, skip.took(playing = true, happened = { it == Player.EVENT_MEDIA_ITEM_TRANSITION }))
            assertEquals(true, skip.took(playing = true, happened = { it == Player.EVENT_POSITION_DISCONTINUITY }))
            assertEquals(false, skip.took(playing = true, happened = { it == Player.EVENT_IS_PLAYING_CHANGED }))
        }
    }

    // The cover

    @Test
    fun `the cover opens the player, in the empty state too`() {
        assertEquals(DESTINATION_PLAYER, coverDestination(loaded(0, playing = true)))
        assertEquals(DESTINATION_PLAYER, coverDestination(WidgetState.Empty))
        assertEquals(FullScreen.Player, destinationView(DESTINATION_PLAYER))
        assertNull(destinationView(DESTINATION_AI))
        assertNull(destinationView(null))
    }

    @Test
    fun `a cover is read once per song, and again after a read that failed`() {
        assertEquals("42", coverToRead(loaded(1, playing = true), readFor = null))
        assertEquals("42", coverToRead(loaded(1, playing = true), readFor = "41"))
        assertNull(coverToRead(loaded(1, playing = false), readFor = "42"))
        assertNull(coverToRead(WidgetState.Empty, readFor = "42"))
        assertNull(coverToRead(loaded(1, playing = true).copy(cover = null), readFor = null))
    }

    @Test
    fun `a cover read that failed records no path, so the next update reads it again, and a cover read stays for the song`() = runTest {
        val reads = mutableListOf<String>()
        // The cover could not be read now (CarCovers.read gave none): the default cover is drawn, and no path kept.
        val failed = Widgets.withCover(loaded(1, playing = true), Shown(WidgetState.Empty)) { reads += it; null }
        assertNull(failed.coverOf)
        assertNull(failed.large)
        // Pause, the next update: it reads again.
        Widgets.withCover(loaded(1, playing = false), failed) { reads += it; null }
        assertEquals(listOf("42", "42"), reads)
        // A cover read for the song (its own, or the default when it has none) is kept: no read until the song changes.
        val kept = Widgets.withCover(loaded(1, playing = true), Shown(loaded(1, playing = false), coverOf = "42")) { reads += it; null }
        assertEquals("42", kept.coverOf)
        assertEquals(listOf("42", "42"), reads)
    }

    @Test
    fun `a file another app opened shows its own cover, read in KJB's process, or the default cover without one`() = runTest {
        // A copy the library does not know: its file's name, and its cover by the copy's key, never its path.
        val copy = SongRef("/data/user/0/app.lyrify/cache/opened/1763290000000/12/Paper Harbour.mp3")
        val opened = QueueBook().play(listOf(copy), 0, QueueSource(SourceKind.OPENED, "opened", ""), now = 1).book
        val state = widgetState(opened, library, playing = true, favourites = emptyList(), settings) as WidgetState.Loaded
        assertEquals("Paper Harbour.mp3", state.title)
        assertEquals("opened-1763290000000-12", state.cover)

        // The widget reads that key at its signed uri, as the notification does (CarCovers.read): the copy's own cover,
        // else the default. A song's MediaStore id is never read for it.
        val uris = CoverUris("app.lyrify.covers", ByteArray(32) { 7 })
        fun own(path: String, cover: String?) =
            ownCover(uris.pathOf(listOf(path, uris.sign(path))).orEmpty(), { error("not a song") }, { key -> cover.takeIf { key == state.cover } }) { "default" }
        val reads = mutableListOf<String>()
        Widgets.withCover(state, Shown(WidgetState.Empty)) { path -> reads += path; null }
        assertEquals(listOf("opened-1763290000000-12"), reads)
        assertEquals("copy's cover", own(reads.single(), "copy's cover"))
        // A copy without a cover: the default cover.
        assertEquals("default", own(reads.single(), null))
    }

    @Test
    fun `covers are read at the size of their slot on the phone's screen`() {
        assertEquals(154, coverPx(WidgetSize.SMALL, density = 3.5f))
        assertEquals(308, coverPx(WidgetSize.LARGE, density = 3.5f))
        assertEquals(44, coverPx(WidgetSize.SMALL, density = 1f))
        assertEquals(132, coverPx(WidgetSize.LARGE, density = 1.5f))
    }

    // After the player is gone

    @Test
    fun `without a live player the widgets show the empty state, whatever they last showed`() {
        assertEquals(loaded(1, playing = true), onScreen(loaded(1, playing = true), playerAlive = true))
        assertEquals(WidgetState.Empty, onScreen(loaded(1, playing = true), playerAlive = false))
        assertEquals(WidgetState.Empty, onScreen(WidgetState.Empty, playerAlive = true))
    }
}
