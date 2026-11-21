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
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortSongs
import app.lyrify.ui.DESTINATION_AI
import app.lyrify.ui.DESTINATION_PLAYER
import app.lyrify.ui.FullScreen
import app.lyrify.ui.Page
import app.lyrify.ui.destinationPage
import app.lyrify.ui.destinationView
import app.lyrify.ui.queueDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** A 4-cell widget on a common phone, in dp: no button left out. */
    private val WIDE = 344f

    // The jumps of the player's seek buttons by default ("Skip amounts": 30 s back, 10 s forward).
    private fun loaded(index: Int, playing: Boolean, favourite: Boolean = false) = WidgetState.Loaded(
        song = refs[index],
        title = "Paper Lantern ${index + 1}",
        artist = "Kit Calloway",
        cover = "${41 + index}",
        playing = playing,
        favourite = favourite,
        album = "Harbour Glass",
        queue = playingSecond.currentId,
        backSeconds = 30,
        forwardSeconds = 10,
    )

    // The state from the player's

    @Test
    fun `a song playing shows its title, artist, album, cover and Pause, with its queue and the player's jumps`() {
        assertEquals(loaded(1, playing = true), widgetState(playingSecond, library, playing = true, favourites = emptyList(), settings))
        assertEquals("q1", playingSecond.currentId)
    }

    @Test
    fun `the seek buttons jump as the player's do, by Skip amounts, and 30 s each way in a book`() {
        val chosen = widgetState(playingSecond, library, playing = true, favourites = emptyList(), settings, playback = PlaybackSettings(skipBackSeconds = 5, skipForwardSeconds = 15))
        assertEquals(loaded(1, playing = true).copy(backSeconds = 5, forwardSeconds = 15), chosen)
        val book = QueueBook().play(refs, 0, QueueSource(SourceKind.BOOK, "book:harbour", "Harbour Glass"), now = 1).book
        val inBook = widgetState(book, library, playing = true, favourites = emptyList(), settings, playback = PlaybackSettings(skipBackSeconds = 5, skipForwardSeconds = 15)) as WidgetState.Loaded
        assertEquals(30 to 30, inBook.backSeconds to inBook.forwardSeconds)
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
            WidgetState.Loaded(gone, "Lost Signal.mp3", artist = null, cover = null, playing = false, favourite = false, album = null, queue = missing.currentId, backSeconds = 30, forwardSeconds = 10),
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
        val playback = MutableStateFlow(PlaybackSettings())
        val pushed = mutableListOf<WidgetState>()
        val job = launch { widgetStates(book, songs, playing, favourites, librarySettings, playback = playback).collect { pushed += it } }

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
        playback.value = playback.value.copy(countPlayAfterPercent = 80)
        assertEquals(emptyList<WidgetState>(), pushed)

        // A new jump for the seek buttons is drawn at once.
        playback.value = playback.value.copy(skipBackSeconds = 10)
        assertEquals(listOf(loaded(1, playing = true).copy(backSeconds = 10)), pushed)
        playback.value = playback.value.copy(skipBackSeconds = 30)
        pushed.clear()

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

    // The layouts and their buttons

    @Test
    fun `the layout follows the widget's height, small 4x1, medium 4x2, large 4x3`() {
        for (height in listOf(0f, 40f, 56f, 102f, 169.9f)) assertEquals("$height", GlowLayout.SMALL, glowLayout(height))
        for (height in listOf(170f, 220f, 260f, 289.9f)) assertEquals("$height", GlowLayout.MEDIUM, glowLayout(height))
        for (height in listOf(290f, 337f, 420f, 900f)) assertEquals("$height", GlowLayout.LARGE, glowLayout(height))
        // Each layout is drawn from its least height on.
        for (layout in GlowLayout.entries) assertEquals(layout, glowLayout(layout.fromHeightDp.toFloat()))
    }

    @Test
    fun `the medium and large layouts fit their least height, cover, controls and margins`() {
        for (layout in listOf(GlowLayout.MEDIUM, GlowLayout.LARGE)) {
            val needed = 2 * layout.paddingDp + layout.coverDp + GLOW_PLAY_DP
            assertTrue("$layout needs $needed dp", needed <= layout.fromHeightDp)
        }
        // The small row: the cover and the Play button side by side, within the height of a small widget on most phones.
        assertTrue(maxOf(GlowLayout.SMALL.coverDp, GLOW_PLAY_DP) + 2 * GlowLayout.SMALL.paddingDp <= 80)
    }

    @Test
    fun `small shows Previous, Play or Pause and Next, medium and large add the jumps back and forward, empty only Play`() {
        val playing = loaded(1, playing = true)
        val paused = loaded(1, playing = false)
        assertEquals(listOf(WidgetCommand.PREVIOUS, WidgetCommand.PAUSE, WidgetCommand.NEXT), widgetButtons(playing, GlowLayout.SMALL, WIDE).map { it.command })
        assertEquals(listOf(WidgetCommand.PREVIOUS, WidgetCommand.PLAY, WidgetCommand.NEXT), widgetButtons(paused, GlowLayout.SMALL, WIDE).map { it.command })
        for (layout in listOf(GlowLayout.MEDIUM, GlowLayout.LARGE)) {
            assertEquals(
                listOf(WidgetCommand.REWIND, WidgetCommand.PREVIOUS, WidgetCommand.PAUSE, WidgetCommand.NEXT, WidgetCommand.FORWARD),
                widgetButtons(playing, layout, WIDE).map { it.command },
            )
            assertEquals(
                listOf(WidgetCommand.REWIND, WidgetCommand.PREVIOUS, WidgetCommand.PLAY, WidgetCommand.NEXT, WidgetCommand.FORWARD),
                widgetButtons(paused, layout, WIDE).map { it.command },
            )
        }
        for (layout in GlowLayout.entries) assertEquals(listOf(WidgetCommand.PLAY), widgetButtons(WidgetState.Empty, layout, WIDE).map { it.command })
    }

    @Test
    fun `in a book the widget jumps back and forward where Previous and Next are, as the notification does`() {
        // A book's session takes no Previous or Next (LyrifyPlayer's NOT_IN_A_BOOK): its 30 s jumps stand in for them.
        val book = QueueBook().play(refs, 0, QueueSource(SourceKind.BOOK, "book:harbour", "Harbour Glass"), now = 1).book
        val inBook = widgetState(book, library, playing = true, favourites = emptyList(), settings) as WidgetState.Loaded
        assertTrue(inBook.book)
        for (layout in GlowLayout.entries) {
            assertEquals(listOf(WidgetCommand.REWIND, WidgetCommand.PAUSE, WidgetCommand.FORWARD), widgetButtons(inBook, layout, WIDE).map { it.command })
        }
        assertEquals(
            listOf(R.drawable.ic_replay_30, R.drawable.ic_pause_fill, R.drawable.ic_forward_30),
            widgetButtons(inBook, GlowLayout.SMALL, WIDE).map { it.icon },
        )
        assertEquals(false, (widgetState(playingSecond, library, playing = true, favourites = emptyList(), settings) as WidgetState.Loaded).book)
    }

    @Test
    fun `a narrow small widget leaves out its first button so the title keeps room, and the large cover gives way`() {
        val playing = loaded(1, playing = true)
        assertEquals(listOf(WidgetCommand.PAUSE, WidgetCommand.NEXT), widgetButtons(playing, GlowLayout.SMALL, GLOW_NARROW_DP - 1f).map { it.command })
        assertEquals(listOf(WidgetCommand.PREVIOUS, WidgetCommand.PAUSE, WidgetCommand.NEXT), widgetButtons(playing, GlowLayout.SMALL, GLOW_NARROW_DP.toFloat()).map { it.command })
        // Medium and large keep all five at any width.
        for (layout in listOf(GlowLayout.MEDIUM, GlowLayout.LARGE)) assertEquals(5, widgetButtons(playing, layout, 250f).size)
        // The large cover leaves room for the heart and Queue beside it on the narrowest widgets; the others never change.
        assertEquals(128, glowCoverDp(GlowLayout.LARGE, WIDE))
        assertEquals(124, glowCoverDp(GlowLayout.LARGE, 250f))
        assertEquals(56, glowCoverDp(GlowLayout.SMALL, 250f))
        assertEquals(76, glowCoverDp(GlowLayout.MEDIUM, 250f))
    }

    @Test
    fun `a narrow small widget in a book keeps the jump back and leaves out the jump forward`() {
        // Going back to hear a part again is what a book's listener needs most; the title keeps the same room as a song's.
        val book = QueueBook().play(refs, 0, QueueSource(SourceKind.BOOK, "book:harbour", "Harbour Glass"), now = 1).book
        val inBook = widgetState(book, library, playing = true, favourites = emptyList(), settings) as WidgetState.Loaded
        assertEquals(listOf(WidgetCommand.REWIND, WidgetCommand.PAUSE), widgetButtons(inBook, GlowLayout.SMALL, GLOW_NARROW_DP - 1f).map { it.command })
        assertEquals(listOf(WidgetCommand.REWIND, WidgetCommand.PAUSE, WidgetCommand.FORWARD), widgetButtons(inBook, GlowLayout.SMALL, GLOW_NARROW_DP.toFloat()).map { it.command })
        for (layout in listOf(GlowLayout.MEDIUM, GlowLayout.LARGE)) {
            assertEquals(listOf(WidgetCommand.REWIND, WidgetCommand.PAUSE, WidgetCommand.FORWARD), widgetButtons(inBook, layout, 250f).map { it.command })
        }
    }

    @Test
    fun `each button has its icon and its label, the jumps their seconds as the player shows them`() {
        assertEquals(
            listOf(
                WidgetButton(WidgetCommand.REWIND, R.drawable.ic_replay_30, R.plurals.player_back_seconds, seconds = 30),
                WidgetButton(WidgetCommand.PREVIOUS, R.drawable.ic_skip_previous_fill, R.string.player_previous),
                WidgetButton(WidgetCommand.PAUSE, R.drawable.ic_pause_fill, R.string.player_pause),
                WidgetButton(WidgetCommand.NEXT, R.drawable.ic_skip_next_fill, R.string.player_next),
                WidgetButton(WidgetCommand.FORWARD, R.drawable.ic_forward_10, R.plurals.player_forward_seconds, seconds = 10),
            ),
            widgetButtons(loaded(0, playing = true), GlowLayout.MEDIUM, WIDE),
        )
        val chosen = loaded(0, playing = false).copy(backSeconds = 5, forwardSeconds = 15)
        assertEquals(
            listOf(R.drawable.ic_replay_5, R.drawable.ic_skip_previous_fill, R.drawable.ic_play_arrow_fill, R.drawable.ic_skip_next_fill, R.drawable.ic_forward_15),
            widgetButtons(chosen, GlowLayout.LARGE, WIDE).map { it.icon },
        )
        assertEquals(listOf(WidgetButton(WidgetCommand.PLAY, R.drawable.ic_play_arrow_fill, R.string.player_play)), widgetButtons(WidgetState.Empty, GlowLayout.SMALL, WIDE))
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
        // The jumps are the session's own seek back and forward: the player's "Skip amounts", a book's 30 s.
        assertEquals(listOf("play", "pause", "seekToNext", "seekToPrevious", "seekBack", "seekForward"), sent)
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
        for (jump in listOf(WidgetCommand.REWIND, WidgetCommand.FORWARD)) {
            assertEquals(true, jump.took(playing = false, happened = { it == Player.EVENT_POSITION_DISCONTINUITY }))
            assertEquals(false, jump.took(playing = true, happened = { it == Player.EVENT_PLAY_WHEN_READY_CHANGED }))
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
    fun `Queue opens KJB on the page of the queue playing`() {
        val destination = queueDestination(loaded(1, playing = true).queue!!)
        assertEquals(Page.Queue("q1"), destinationPage(destination))
        assertNull(destinationView(destination))
        // A queue's id is taken whole, whatever it holds.
        assertEquals(Page.Queue("q/7"), destinationPage(queueDestination("q/7")))
        assertNull(destinationPage("queue"))
    }

    @Test
    fun `a cover is read once per song, and again after a read that failed`() {
        assertEquals("42", coverToRead(loaded(1, playing = true), readFor = null, noArtAt = null, now = 0))
        assertEquals("42", coverToRead(loaded(1, playing = true), readFor = "41", noArtAt = null, now = 0))
        assertNull(coverToRead(loaded(1, playing = false), readFor = "42", noArtAt = null, now = NO_ART_RECHECK_MS * 10))
        assertNull(coverToRead(WidgetState.Empty, readFor = "42", noArtAt = null, now = 0))
        assertNull(coverToRead(loaded(1, playing = true).copy(cover = null), readFor = null, noArtAt = null, now = 0))
        // No art for the song: kept, and read once more from NO_ART_RECHECK_MS after that read.
        assertNull(coverToRead(loaded(1, playing = true), readFor = "42", noArtAt = 5_000, now = 5_000 + NO_ART_RECHECK_MS - 1))
        assertEquals("42", coverToRead(loaded(1, playing = true), readFor = "42", noArtAt = 5_000, now = 5_000 + NO_ART_RECHECK_MS))
    }

    @Test
    fun `a cover read that failed records no path, so the next update reads it again, and a cover read stays for the song`() = runTest {
        val reads = mutableListOf<String>()
        // The cover could not be read now (CarCovers.read gave none): the default cover is drawn, and no path kept.
        val failed = Widgets.withCover(loaded(1, playing = true), Shown(WidgetState.Empty), now = 1_000) { reads += it; null }
        assertNull(failed.coverOf)
        assertNull(failed.cover)
        // Pause, the next update a moment later: it reads again, without waiting for NO_ART_RECHECK_MS.
        Widgets.withCover(loaded(1, playing = false), failed, now = 1_001) { reads += it; null }
        assertEquals(listOf("42", "42"), reads)
        // A cover read for the song is kept: no read until the song changes.
        val kept = Widgets.withCover(loaded(1, playing = true), Shown(loaded(1, playing = false), coverOf = "42"), now = 1_002) { reads += it; null }
        assertEquals("42", kept.coverOf)
        assertEquals(listOf("42", "42"), reads)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cover Covers & tags changed is read again at once, for the song playing only`() = runTest(UnconfinedTestDispatcher()) {
        // The state carries the version of the song playing's cover (CoverVersions), and only that song's.
        val changed = loaded(1, playing = true).copy(coverVersion = 2)
        assertEquals(changed, widgetState(playingSecond, library, playing = true, favourites = emptyList(), settings, versions = mapOf(refs[1].path to 2, refs[0].path to 5)))
        assertEquals(loaded(1, playing = true), widgetState(playingSecond, library, playing = true, favourites = emptyList(), settings, versions = mapOf(refs[0].path to 5)))

        // The cover read before the change is read again, then kept as any cover read.
        val reads = mutableListOf<String>()
        val read: suspend (String) -> CoverRead? = { reads += it; CoverRead.NoArt }
        val before = Widgets.withCover(loaded(1, playing = true), Shown(WidgetState.Empty), now = 1_000, read)
        val after = Widgets.withCover(changed, before, now = 1_001, read)
        Widgets.withCover(changed.copy(playing = false), after, now = 1_002, read)
        assertEquals(listOf("42", "42"), reads)

        // The widgets are pushed the new state when the song playing's cover changes, not another song's.
        val versions = MutableStateFlow<Map<String, Int>>(emptyMap())
        val pushed = mutableListOf<WidgetState>()
        val job = launch {
            widgetStates(MutableStateFlow(playingSecond), MutableStateFlow(library), MutableStateFlow(true), MutableStateFlow(emptyList()), MutableStateFlow(settings), versions)
                .collect { pushed += it }
        }
        versions.value = mapOf(refs[0].path to 1)
        versions.value = mapOf(refs[0].path to 1, refs[1].path to 2)
        assertEquals(listOf(loaded(1, playing = true), changed), pushed)
        job.cancel()
    }

    @Test
    fun `a song with no art keeps the default cover, and is read once more at the first update a minute on`() = runTest {
        val reads = mutableListOf<String>()
        val noArt: suspend (String) -> CoverRead? = { reads += it; CoverRead.NoArt }
        // MediaStore has no art for the song (FileNotFoundException, which it may also give for a moment's failure).
        val first = Widgets.withCover(loaded(1, playing = true), Shown(WidgetState.Empty), now = 1_000, noArt)
        assertEquals("42", first.coverOf)
        assertNull(first.cover)
        assertNull(first.glow)
        // Updates before the bound draw the default cover again, and read nothing.
        val paused = Widgets.withCover(loaded(1, playing = false), first, now = 1_000 + NO_ART_RECHECK_MS - 1, noArt)
        assertEquals("42", paused.coverOf)
        assertEquals(listOf("42"), reads)
        // The first update from the bound on reads it once more...
        val again = Widgets.withCover(loaded(1, playing = true), paused, now = 1_000 + NO_ART_RECHECK_MS, noArt)
        assertEquals("42", again.coverOf)
        assertEquals(listOf("42", "42"), reads)
        // ...and what it finds stays for the song: no art for good, until the song changes.
        val later = Widgets.withCover(loaded(1, playing = false), again, now = 1_000 + 10 * NO_ART_RECHECK_MS, noArt)
        assertEquals("42", later.coverOf)
        assertEquals(listOf("42", "42"), reads)
        // Another song is read at once.
        Widgets.withCover(loaded(2, playing = true), later, now = 1_000 + 10 * NO_ART_RECHECK_MS, noArt)
        assertEquals(listOf("42", "42", "43"), reads)

        // Another song inside the minute is read at once; back to the first, it is read at once too, with a new minute.
        reads.clear()
        val other = Widgets.withCover(loaded(2, playing = true), first, now = 1_001, noArt)
        val back = Widgets.withCover(loaded(1, playing = true), other, now = 1_002, noArt)
        Widgets.withCover(loaded(1, playing = false), back, now = 1_002 + NO_ART_RECHECK_MS - 1, noArt)
        assertEquals(listOf("43", "42"), reads)

        // A read again that fails keeps no path: the next update reads at once, as after any failed read.
        reads.clear()
        val failedAgain = Widgets.withCover(loaded(1, playing = true), first, now = 1_000 + NO_ART_RECHECK_MS) { reads += it; null }
        assertNull(failedAgain.coverOf)
        Widgets.withCover(loaded(1, playing = false), failedAgain, now = 1_000 + NO_ART_RECHECK_MS + 1, noArt)
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
        val shown = Widgets.withCover(state, Shown(WidgetState.Empty), now = 1_000) { path -> reads += path; CoverRead.NoArt }
        assertEquals(listOf("opened-1763290000000-12"), reads)
        assertEquals("copy's cover", own(reads.single(), "copy's cover"))
        // A copy without a cover: the default cover, kept as for a song with no art.
        assertEquals("default", own(reads.single(), null))
        assertEquals("opened-1763290000000-12", shown.coverOf)
        assertNull(shown.cover)
    }

    // After the player is gone

    @Test
    fun `the player stopping while a cover is read leaves the empty state, never the song it had`() = runTest {
        var alive = true
        val stopped = Widgets.reconciled(Shown(loaded(1, playing = true)), { alive }, now = 1_000) { alive = false; CoverRead.NoArt }
        assertEquals(WidgetState.Empty, stopped.state)
        assertNull(stopped.coverOf)
        // Alive throughout: the song, its cover read.
        val kept = Widgets.reconciled(Shown(loaded(1, playing = true)), { true }, now = 1_000) { CoverRead.NoArt }
        assertEquals(loaded(1, playing = true), kept.state)
        assertEquals("42", kept.coverOf)
        // Gone from the start: the empty state, nothing read.
        var reads = 0
        assertEquals(WidgetState.Empty, Widgets.reconciled(Shown(loaded(1, playing = true)), { false }, now = 1_000) { reads++; null }.state)
        assertEquals(0, reads)
    }

    @Test
    fun `without a live player the widgets show the empty state, whatever they last showed`() {
        assertEquals(loaded(1, playing = true), onScreen(loaded(1, playing = true), playerAlive = true))
        assertEquals(WidgetState.Empty, onScreen(loaded(1, playing = true), playerAlive = false))
        assertEquals(WidgetState.Empty, onScreen(WidgetState.Empty, playerAlive = true))
    }
}
