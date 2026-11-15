package app.lyrify.player

import androidx.media3.common.C
import androidx.media3.common.Player
import app.lyrify.auto.DEFAULT_COVER
import app.lyrify.auto.coverPath
import app.lyrify.auto.nodeCover
import app.lyrify.library.Library
import app.lyrify.library.Playlist
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.lyrics.LocalTrack
import app.lyrify.media.SessionSnapshot
import app.lyrify.media.SessionTrack
import app.lyrify.media.SessionTracks
import app.lyrify.settings.AdvancedSettings
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KJB's own playback beyond the queue model: lyrics for its own queue, the browse tree, the state the media session
 * shows, and when offload may run. Made-up names only.
 */
class OwnPlaybackTest {
    private val songs: List<Song> = (1..10).map {
        song("Music/Harbour Glass/%02d Paper Lantern %d.flac".format(it, it), album = "Harbour Glass", artist = "Kit Calloway", track = it, durationMs = 200_000L + it, id = it.toLong())
    } + song("Music/Rips/Loose Thread.mp3", title = "Loose Thread", artist = "Mira Solen", album = "Night Ferry", durationMs = 240_000)
    private val library = Library.of(songs, Settings())
    private val find: (SongRef) -> Song? = { library.resolver.find(it) }
    private val albumSource = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")

    // Lyrics for KJB's own queue

    @Test
    fun `KJB's own queue is swept as a session's, the current song and the next 5 first, then the rest`() {
        val missing = songs[4].ref
        val refs = songs.take(10).map { it.ref }
        val book = QueueBook().play(refs, 2, albumSource, now = 1).book
        val snapshot = ownSnapshot(book.current, { ref -> if (ref == missing) null else find(ref) })
        val current = songs[2]
        assertEquals(SessionTrack(current.title, current.artist, current.album, current.durationMs, mediaUri = current.path), snapshot.current)
        assertEquals(book.current!!.current!!.uid, snapshot.activeQueueId)
        assertEquals(9, snapshot.queue.size)
        val order = SessionTracks.sweepOrder(snapshot.queue, snapshot.activeQueueId, snapshot.current)
        assertEquals(listOf(2, 3, 5, 6, 7, 8).map { songs[it].path }, order.high.map { it.track.mediaUri })
        assertEquals(listOf(9, 0, 1).map { songs[it].path }, order.low.map { it.track.mediaUri })
        assertEquals(ownSnapshot(null, find), ownSnapshot(QueueBook().current, find))
        assertNull(ownSnapshot(null, find).current)
    }

    @Test
    fun `a book's queue is never swept for lyrics`() {
        val bookSource = QueueSource(SourceKind.BOOK, "/storage/emulated/0/Audiobooks/Folder Book/|Folder Book", "Folder Book")
        val queues = QueueBook().play(songs.take(3).map { it.ref }, 0, bookSource, now = 1).book
        assertEquals(SessionSnapshot.EMPTY, ownSnapshot(queues.current, find))
    }

    @Test
    fun `KJB's own songs are the files they name, with their tags`() {
        val loose = songs.last()
        val track = SessionTrack(loose.title, loose.artist, mediaUri = loose.path)
        assertEquals(listOf(LocalTrack(loose.path, "Loose Thread", "Mira Solen", "Night Ferry", 240_000)), ownFiles(track, library::song))
        assertEquals(emptyList<LocalTrack>(), ownFiles(track.copy(mediaUri = "/storage/emulated/0/Music/Gone.mp3"), library::song))
        assertEquals(emptyList<LocalTrack>(), ownFiles(track.copy(mediaUri = null), library::song))
    }

    // The browse tree

    private val playlist = Playlist("p1", "Road trip", listOf(songs.last().ref, songs[0].ref, SongRef("/storage/emulated/0/Music/Gone.mp3")), createdAt = 1)

    private fun data(book: QueueBook = QueueBook(), settings: LibrarySettings = LibrarySettings()) =
        TreeData(library, book, listOf(playlist), settings) { it.name ?: it.source.name }

    @Test
    fun `the root holds Recent queues, Playlists, Albums, Artists and Songs, in that order`() {
        assertEquals(
            listOf(LibraryTree.QUEUES, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS, LibraryTree.SONGS),
            LibraryTree.children(LibraryTree.ROOT, data())!!.map { it.id },
        )
        assertTrue(LibraryTree.children(LibraryTree.ROOT, data())!!.all { it.browsable })
        assertNull(LibraryTree.children("nothing here", data()))
    }

    @Test
    fun `an album lists its songs in track order, and a song played plays the album from it`() {
        val albums = LibraryTree.children(LibraryTree.ALBUMS, data())!!
        assertEquals(listOf("Harbour Glass", "Night Ferry"), albums.map { it.title })
        val tracks = LibraryTree.children(albums[0].id, data())!!
        assertEquals(songs.take(10).map { it.path }, tracks.map { it.song!!.path })
        val play = LibraryTree.play(tracks[3].id, data()) as TreePlay.FromList
        assertEquals(songs.take(10).map { it.ref }, play.songs)
        assertEquals(3, play.start)
        assertEquals(SourceKind.ALBUM, play.source.kind)
        assertEquals("Harbour Glass", play.source.name)
        assertNull(LibraryTree.play(albums[0].id, data()))
    }

    @Test
    fun `a playlist lists the songs on the phone, and Songs follows Show file names`() {
        val lists = LibraryTree.children(LibraryTree.PLAYLISTS, data())!!.filter { it.group == LibraryTree.YOUR_PLAYLISTS }
        assertEquals(listOf("Road trip"), lists.map { it.title })
        val inList = LibraryTree.children(lists[0].id, data())!!
        assertEquals(listOf(songs.last().path, songs[0].path), inList.map { it.song!!.path })
        val play = LibraryTree.play(inList[1].id, data()) as TreePlay.FromList
        assertEquals(SourceKind.PLAYLIST, play.source.kind)
        assertEquals(1, play.start)

        val named = LibraryTree.children(LibraryTree.SONGS, data(settings = LibrarySettings(showFileNames = true)))!!
        assertTrue("Loose Thread.mp3" in named.map { it.title })
        val artists = LibraryTree.children(LibraryTree.ARTISTS, data())!!
        assertEquals(listOf("Kit Calloway", "Mira Solen"), artists.map { it.title })
    }

    @Test
    fun `Recent queues lists the saved then the recent queues, and a song there plays inside its queue`() {
        var book = QueueBook().play(songs.take(10).map { it.ref }, 0, albumSource, now = 1).book
        book = book.play(listOf(songs.last().ref), 0, QueueSource(SourceKind.SEARCH, "search:loose", "loose"), now = 2).book
        val queues = LibraryTree.children(LibraryTree.QUEUES, data(book))!!
        assertEquals(listOf("loose", "Harbour Glass"), queues.map { it.title })
        val inQueue = LibraryTree.children(queues[1].id, data(book))!!
        assertEquals(10, inQueue.size)
        assertEquals(TreePlay.InQueue(book.recent.single(), 4), LibraryTree.play(inQueue[4].id, data(book)))
    }

    @Test
    fun `every level is capped for a client that asks for everything`() {
        val many = (1..LibraryTree.MAX_CHILDREN + 30).map { song("Music/Many/Song $it.mp3", artist = "Artist $it") }
        val big = TreeData(Library.of(many, Settings()), QueueBook(), emptyList(), LibrarySettings()) { "" }
        assertEquals(LibraryTree.MAX_CHILDREN, LibraryTree.page(LibraryTree.children(LibraryTree.SONGS, big)!!, 0, Int.MAX_VALUE).size)
        assertEquals(LibraryTree.MAX_CHILDREN, LibraryTree.page(LibraryTree.children(LibraryTree.ARTISTS, big)!!, 0, Int.MAX_VALUE).size)
    }

    @Test
    fun `every song and album the car is shown, and every song of the queue playing, carries a cover, the default one without an id`() {
        val book = QueueBook().play(songs.map { it.ref }, 0, albumSource, now = 1).book
        val tree = data(book)
        val shown = mutableListOf<TreeNode>()
        fun walk(id: String, depth: Int) {
            val children = LibraryTree.children(id, tree) ?: return
            shown += children
            if (depth < 3) children.filter { it.browsable }.forEach { walk(it.id, depth + 1) }
        }
        walk(LibraryTree.ROOT, 0)
        val songNodes = shown.filter { it.song != null }
        assertTrue(songNodes.size > 20)
        // "Loose Thread" has no MediaStore id: the default cover.
        assertTrue(songNodes.any { it.song!!.ref.mediaStoreId == null })
        (songNodes + LibraryTree.children(LibraryTree.ALBUMS, tree)!!).forEach { node ->
            assertEquals(node.id, node.cover!!.ref.mediaStoreId?.toString() ?: DEFAULT_COVER, nodeCover(node))
        }
        // The top lists show the car's own icons, never a cover.
        LibraryTree.children(LibraryTree.ROOT, tree)!!.forEach { assertNull(nodeCover(it)) }
        // The queue playing, as the session hands it to the notification and the car.
        val queue = projection(book.current, find)
        assertEquals(songs.size, queue.size)
        assertEquals(songs.map { it.ref.mediaStoreId?.toString() ?: DEFAULT_COVER }, queue.map { coverPath(it.song) })
    }

    // What the session shows

    private class StillPort(override val loaded: List<Long>, override val index: Int, override val positionMs: Long) : PlayerPort {
        override fun load(items: List<Projected>, index: Int, positionMs: Long) = Unit

        override fun apply(edit: Edit) = Unit

        override fun seek(index: Int, positionMs: Long) = Unit
    }

    @Test
    fun `the session shows the player's songs and place, the queue's shuffle and the app's repeat`() {
        val book = QueueBook().play(songs.take(4).map { it.ref }, 1, albumSource, now = 1).book.toggleShuffle(kotlin.random.Random(1))
        val uids = book.current!!.items.map { it.uid }
        val view = sessionView(StillPort(uids, 1, 12_000), playing = true, book = book, repeat = Repeat.ALL)
        assertEquals(SessionView(uids, 1, 12_000, playing = true, shuffled = true, repeat = Repeat.ALL), view)
        assertEquals(SessionView(emptyList(), -1, 0, playing = false, shuffled = false, repeat = Repeat.ONE), sessionView(StillPort(emptyList(), -1, 0), playing = true, book = QueueBook(), repeat = Repeat.ONE))
    }

    @Test
    fun `a song starting by itself, a lap of Repeat one too, moves every controller's place back to where the song is`() {
        // Repeat one: the same song again, so the session's own state sees no change; the screens' progress and lyrics
        // would stay at the song's end.
        assertEquals(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, startedDiscontinuity(lap = true, natural = true))
        assertEquals(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, startedDiscontinuity(lap = false, natural = true))
        // A move by the user is told by its own seek.
        assertNull(startedDiscontinuity(lap = false, natural = false))
    }

    @Test
    fun `Next on the last song is offered and goes to the first song of the queue, whatever Repeat says`() {
        val uids = listOf(11L, 12L, 13L)
        Repeat.entries.forEach { repeat ->
            val last = SessionView(uids, 2, 90_000, playing = true, shuffled = false, repeat = repeat)
            assertTrue("$repeat", Player.COMMAND_SEEK_TO_NEXT in sessionCommands(last))
            assertTrue("$repeat", Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM in sessionCommands(last))
        }
        // Media3 finds no song after the last one (Repeat off, and Repeat one, which it reads as off for Next): no index,
        // and the facade tells controllers of the jump itself.
        assertEquals(SessionSeek(0, tell = true), sessionSeek(C.INDEX_UNSET, Player.COMMAND_SEEK_TO_NEXT, current = 2))
        assertEquals(SessionSeek(0, tell = true), sessionSeek(C.INDEX_UNSET, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, current = 2))
        // A queue of one song: Next starts the same song again, and its progress and lyrics must start again too.
        assertEquals(SessionSeek(0, tell = true), sessionSeek(C.INDEX_UNSET, Player.COMMAND_SEEK_TO_NEXT, current = 0))
        // A seek Media3 planned goes where it asks, and Media3 tells of it; any other seek without an index stays.
        assertEquals(SessionSeek(1, tell = false), sessionSeek(1, Player.COMMAND_SEEK_TO_NEXT, current = 0))
        assertEquals(SessionSeek(2, tell = true), sessionSeek(C.INDEX_UNSET, Player.COMMAND_SEEK_TO_PREVIOUS, current = 2))
        // Nothing loaded: no seeks and no Next.
        val none = sessionCommands(SessionView(emptyList(), -1, 0, playing = false, shuffled = false, repeat = Repeat.OFF))
        assertFalse(Player.COMMAND_SEEK_TO_NEXT in none)
        assertFalse(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM in none)
        assertTrue(Player.COMMAND_PLAY_PAUSE in none)
    }

    @Test
    fun `a book's session offers 30 s back and forward in place of Previous and Next, and no Shuffle or Repeat`() {
        val bookSource = QueueSource(SourceKind.BOOK, "/storage/emulated/0/Audiobooks/Folder Book/|Folder Book", "Folder Book")
        val queues = QueueBook().play(songs.take(3).map { it.ref }, 0, bookSource, now = 1).book
        val uids = queues.current!!.items.map { it.uid }
        val view = sessionView(StillPort(uids, 0, 5_000), playing = true, book = queues, repeat = Repeat.ALL)
        assertTrue(view.book)
        val offered = sessionCommands(view)
        listOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_PLAY_PAUSE)
            .forEach { assertTrue("$it", it in offered) }
        listOf(
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
        ).forEach { assertFalse("$it", it in offered) }
        // Music keeps them all.
        assertFalse(sessionView(StillPort(uids, 0, 5_000), playing = true, book = QueueBook().play(songs.take(3).map { it.ref }, 0, albumSource, now = 1).book, repeat = Repeat.ALL).book)
    }

    // Offload

    @Test
    fun `audio offload runs only when switched on and nothing processes the sound`() {
        assertFalse(offloadAllowed(AdvancedSettings(), processing = false))
        assertTrue(offloadAllowed(AdvancedSettings(audioOffload = true), processing = false))
        assertFalse(offloadAllowed(AdvancedSettings(audioOffload = true), processing = true))
    }
}
