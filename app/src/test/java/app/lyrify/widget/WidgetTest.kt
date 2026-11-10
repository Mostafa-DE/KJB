package app.lyrify.widget

import androidx.media3.common.Player
import app.lyrify.R
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
        cover = 41L + index,
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
    fun `the cover opens the player`() {
        assertEquals(FullScreen.Player, destinationView(DESTINATION_PLAYER))
        assertNull(destinationView(DESTINATION_AI))
        assertNull(destinationView(null))
    }
}
