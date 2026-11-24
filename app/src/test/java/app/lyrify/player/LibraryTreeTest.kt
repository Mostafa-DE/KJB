package app.lyrify.player

import app.lyrify.library.Library
import app.lyrify.library.Playlist
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import app.lyrify.library.song
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.ListPeriod
import app.lyrify.settings.Settings
import app.lyrify.settings.SongList
import app.lyrify.ui.BuiltInList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * The browse tree as Android Auto and Bluetooth see it (milestone A4): the root and Auto's limit on it, Songs with
 * Shuffle all, Playlists with Built for you, Albums as a grid, paging, the queues a car shares with the app, and search
 * and voice. Made-up names only.
 */
class LibraryTreeTest {
    private val now = TimeUnit.DAYS.toMillis(400)
    private val harbour: List<Song> = (1..4).map {
        song("Music/Harbour Glass/%02d Paper Lantern %d.flac".format(it, it), title = "Paper Lantern $it", album = "Harbour Glass", artist = "Kit Calloway", track = it, id = it.toLong(), addedAt = now - TimeUnit.DAYS.toMillis(90))
    }
    private val loose = song("Music/Rips/Loose Thread.mp3", title = "Loose Thread", artist = "Mira Solen", album = "Night Ferry", id = 20, addedAt = now - TimeUnit.DAYS.toMillis(2))
    private val ember = song("Music/Rips/Ember Road.mp3", title = "Ember Road", artist = "Élan Varo", album = "Coastline", id = 21, addedAt = now - TimeUnit.DAYS.toMillis(40))
    private val songs = harbour + loose + ember
    private val library = Library.of(songs, Settings())
    private val playlist = Playlist("p1", "Road trip", listOf(loose.ref, harbour[0].ref, SongRef("/storage/emulated/0/Music/Gone.mp3")), createdAt = 1)

    private fun data(
        book: QueueBook = QueueBook(),
        favourites: List<SongRef> = emptyList(),
        mostPlayed: List<Song> = emptyList(),
        recentlyPlayed: List<Song> = emptyList(),
        notPlayed: List<Song> = emptyList(),
        found: Found? = null,
        lib: Library = library,
    ) = TreeData(
        lib,
        book,
        listOf(playlist),
        LibrarySettings(),
        favourites = favourites,
        mostPlayed = mostPlayed,
        recentlyPlayed = recentlyPlayed,
        notPlayed = notPlayed,
        now = now,
        found = listOfNotNull(found).associateBy { it.query },
    ) { it.name ?: it.source.name }

    private fun children(id: String, data: TreeData = data()): List<TreeNode> = LibraryTree.children(id, data)!!

    // The root and Android Auto's limit on it

    @Test
    fun `without a limit the root holds all five lists, every one browsable`() {
        for (limit in listOf(null, 0, -1, 5, 6, 100)) {
            val root = LibraryTree.root(limit)
            assertEquals(LibraryTree.ROOT, root)
            assertEquals(listOf(LibraryTree.QUEUES, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS, LibraryTree.SONGS), children(root).map { it.id })
        }
        assertTrue(children(LibraryTree.ROOT).all { it.browsable && !it.playable })
    }

    @Test
    fun `Android Auto's usual limit of four leaves Songs out, and a smaller one keeps the lists from the left`() {
        val four = LibraryTree.root(4)
        assertFalse(four == LibraryTree.ROOT)
        assertTrue(LibraryTree.isRoot(four))
        assertEquals(listOf(LibraryTree.QUEUES, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS), children(four).map { it.id })
        assertEquals(listOf(LibraryTree.QUEUES, LibraryTree.PLAYLISTS), children(LibraryTree.root(2)).map { it.id })
        assertTrue(LibraryTree.isRoot(LibraryTree.ROOT))
        assertFalse(LibraryTree.isRoot(LibraryTree.ALBUMS))
        assertNull(LibraryTree.children("root:x", data()))
    }

    @Test
    fun `a root node is titled by its name, and only Albums shows its children as a grid`() {
        val names = mapOf(LibraryTree.ALBUMS to "Albums", LibraryTree.SONGS to "Songs")
        val named = TreeData(library, QueueBook(), emptyList(), LibrarySettings(), name = { names[it] ?: it }) { "" }
        val root = LibraryTree.children(LibraryTree.ROOT, named)!!
        assertEquals("Albums", root.single { it.id == LibraryTree.ALBUMS }.title)
        assertEquals(listOf(LibraryTree.ALBUMS), root.filter { it.grid }.map { it.id })
    }

    // Songs, with Shuffle all at the top

    @Test
    fun `Songs starts with Shuffle all, then every song by title, and a song plays the list from it`() {
        val list = children(LibraryTree.SONGS)
        val shuffle = list.first()
        assertEquals(LibraryTree.SHUFFLE_ALL, shuffle.id)
        assertTrue(shuffle.playable)
        assertFalse(shuffle.browsable)
        assertNull(shuffle.song)
        val titles = list.drop(1).map { it.song!!.title }
        assertEquals(listOf("Ember Road", "Loose Thread") + harbour.map { it.title }, titles)
        val play = LibraryTree.play(list[2].id, data()) as TreePlay.FromList
        assertEquals(1, play.start)
        assertEquals(loose.ref, play.songs[play.start])
    }

    @Test
    fun `Shuffle all shuffles every song as the Songs list, and an empty library has none`() {
        val play = LibraryTree.play(LibraryTree.SHUFFLE_ALL, data()) as TreePlay.Shuffle
        assertEquals(songs.map { it.path }.toSet(), play.songs.map { it.path }.toSet())
        assertEquals(SourceKind.SONGS, play.source.kind)
        val empty = data(lib = Library.of(emptyList(), Settings()))
        assertEquals(emptyList<TreeNode>(), children(LibraryTree.SONGS, empty))
        assertNull(LibraryTree.play(LibraryTree.SHUFFLE_ALL, empty))
    }

    // Playlists, Built for you first

    @Test
    fun `Playlists lists Built for you first, then your playlists, each under its heading`() {
        val data = data(favourites = listOf(ember.ref, loose.ref), mostPlayed = listOf(harbour[1], loose), recentlyPlayed = listOf(harbour[2]))
        val lists = children(LibraryTree.PLAYLISTS, data)
        assertEquals(
            listOf(LibraryTree.SHUFFLE_ALL, LibraryTree.FAVOURITES, LibraryTree.MOST_PLAYED, LibraryTree.RECENTLY_PLAYED, LibraryTree.RECENTLY_ADDED, "Road trip"),
            lists.map { if (it.title == "Road trip") it.title else it.id },
        )
        assertEquals(List(5) { LibraryTree.BUILT_FOR_YOU } + LibraryTree.YOUR_PLAYLISTS, lists.map { it.group })
        assertTrue(lists.first().playable)
        assertTrue(lists.drop(1).all { it.browsable })
    }

    @Test
    fun `a Built for you list that is empty is left out, Shuffle all stays`() {
        val lists = children(LibraryTree.PLAYLISTS, data())
        // Recently added: Loose Thread was added 2 days ago, Ember Road 40 days ago.
        assertEquals(listOf(LibraryTree.SHUFFLE_ALL, LibraryTree.RECENTLY_ADDED), lists.filter { it.group == LibraryTree.BUILT_FOR_YOU }.map { it.id })
        assertEquals(listOf(loose.path), children(LibraryTree.RECENTLY_ADDED).map { it.song!!.path })
    }

    @Test
    fun `each Built for you list holds its songs in order and plays as the app's own list, with L1's exact keys`() {
        val data = data(favourites = listOf(ember.ref, loose.ref), mostPlayed = listOf(harbour[1], loose), recentlyPlayed = listOf(harbour[2]))
        assertEquals(listOf(ember.path, loose.path), children(LibraryTree.FAVOURITES, data).map { it.song!!.path })
        assertEquals(listOf(harbour[1].path, loose.path), children(LibraryTree.MOST_PLAYED, data).map { it.song!!.path })
        assertEquals(listOf(harbour[2].path), children(LibraryTree.RECENTLY_PLAYED, data).map { it.song!!.path })
        val sources = listOf(LibraryTree.FAVOURITES, LibraryTree.MOST_PLAYED, LibraryTree.RECENTLY_PLAYED, LibraryTree.RECENTLY_ADDED).map { list ->
            (LibraryTree.play(children(list, data).first().id, data) as TreePlay.FromList).source
        }
        // L1's pages: Favourites plays as "favourites" (ui/CollectionScreens.kt, FavouritesPage), the others by their
        // BuiltInList name (BuiltInPage), each with no name.
        assertEquals(
            listOf(
                QueueSource(SourceKind.FAVOURITES, "favourites", ""),
                QueueSource(SourceKind.MOST_PLAYED, BuiltInList.MOST_PLAYED.name, ""),
                QueueSource(SourceKind.RECENTLY_PLAYED, BuiltInList.RECENTLY_PLAYED.name, ""),
                QueueSource(SourceKind.RECENTLY_ADDED, BuiltInList.RECENTLY_ADDED.name, ""),
            ),
            sources,
        )
    }

    @Test
    fun `the car's Not played comes after Recently added, holds the app's songs in order and plays as the app's own list`() {
        val data = data(notPlayed = listOf(ember, harbour[3]))
        assertEquals(listOf(LibraryTree.SHUFFLE_ALL, LibraryTree.RECENTLY_ADDED, LibraryTree.NOT_PLAYED), children(LibraryTree.PLAYLISTS, data).filter { it.group == LibraryTree.BUILT_FOR_YOU }.map { it.id })
        assertEquals(listOf(ember.path, harbour[3].path), children(LibraryTree.NOT_PLAYED, data).map { it.song!!.path })
        // BuiltInPage plays it as its BuiltInList name, with no name.
        val source = QueueSource(SourceKind.NOT_PLAYED, BuiltInList.NOT_PLAYED.name, "")
        assertEquals(TreePlay.FromList(listOf(ember.ref, harbour[3].ref), 1, source), LibraryTree.play(children(LibraryTree.NOT_PLAYED, data)[1].id, data))
        assertTrue(LibraryTree.isList(LibraryTree.NOT_PLAYED))
        // A car showing it hears when one of its covers changes.
        assertEquals(2, LibraryTree.showing(setOf(ember.path), data)[LibraryTree.NOT_PLAYED])
        // Empty, it is left out, as the other Built for you lists are.
        assertFalse(LibraryTree.NOT_PLAYED in children(LibraryTree.PLAYLISTS, data()).map { it.id })
    }

    // Albums, as a grid

    @Test
    fun `an album shows its artist and its first song's cover`() {
        val albums = children(LibraryTree.ALBUMS)
        val first = albums.single { it.title == "Harbour Glass" }
        assertEquals("Kit Calloway", first.subtitle)
        assertEquals(harbour[0].path, first.cover!!.path)
        assertTrue(first.browsable)
        assertNull(first.song)
    }

    // The queues the car shares with the app

    @Test
    fun `an album, an artist, a playlist and Songs play as the app's own lists do`() {
        val album = children(children(LibraryTree.ALBUMS).single { it.title == "Harbour Glass" }.id)
        assertEquals(QueueSource(SourceKind.ALBUM, albumKey(harbour[0])!!, "Harbour Glass"), (LibraryTree.play(album[0].id, data()) as TreePlay.FromList).source)
        val artist = children(children(LibraryTree.ARTISTS).single { it.title == "Mira Solen" }.id)
        assertEquals(QueueSource(SourceKind.ARTIST, "Mira Solen", "Mira Solen"), (LibraryTree.play(artist[0].id, data()) as TreePlay.FromList).source)
        val inList = children(children(LibraryTree.PLAYLISTS).single { it.title == "Road trip" }.id)
        assertEquals(QueueSource(SourceKind.PLAYLIST, "p1", "Road trip"), (LibraryTree.play(inList[0].id, data()) as TreePlay.FromList).source)
        // The Songs tab's key for the same order (by title) and the same songs (all).
        assertEquals(QueueSource(SourceKind.SONGS, "car-songs", ""), (LibraryTree.play(children(LibraryTree.SONGS)[1].id, data()) as TreePlay.FromList).source)
    }

    @Test
    fun `the car lists the queues as the app does, the one playing first, then the others with the last left first`() {
        val appSource = QueueSource(SourceKind.ALBUM, albumKey(harbour[0])!!, "Harbour Glass")
        var book = QueueBook().play(harbour.map { it.ref }, 0, appSource, now = 1).book
        // Kept with Keep in 3.0.0, then left: it is no Saved part of its own, only a queue left at 2.
        book = book.keep(book.currentId!!).play(listOf(loose.ref), 0, QueueSource(SourceKind.FOLDER, "folder:Music/Rips", "Rips"), now = 2).book
        book = book.play(listOf(ember.ref), 0, QueueSource(SourceKind.SEARCH, "search", "ember"), now = 3).book
        assertEquals(listOf("ember", "Rips", "Harbour Glass"), children(LibraryTree.QUEUES, data(book)).map { it.title })
    }

    @Test
    fun `a song picked in the car inside the album the app is playing starts the album again from it, as a tap in the app does`() {
        val appSource = QueueSource(SourceKind.ALBUM, albumKey(harbour[0])!!, "Harbour Glass")
        val book = QueueBook().play(harbour.map { it.ref }, 0, appSource, now = 1).book
        val tracks = children(children(LibraryTree.ALBUMS).single { it.title == "Harbour Glass" }.id, data(book))
        val play = LibraryTree.play(tracks[2].id, data(book)) as TreePlay.FromList
        val after = book.play(play.songs, play.start, play.source, now = 2).book
        // A new queue, wrapped round from the song (user 2026-11-24): the one before goes to Recent.
        assertEquals(listOf(book.currentId), after.recent)
        assertEquals(2, after.queues.size)
        assertEquals((harbour.drop(2) + harbour.take(2)).map { it.ref }, after.current!!.items.map { it.song })
        assertEquals(harbour[2].ref, after.current!!.current!!.song)
    }

    // Paging

    @Test
    fun `a client that asks for everything gets at most the cap, one that pages reaches every item`() {
        val many = (1..LibraryTree.MAX_CHILDREN + 30).map { song("Music/Many/Song %04d.mp3".format(it), artist = "Artist %04d".format(it)) }
        val big = TreeData(Library.of(many, Settings()), QueueBook(), emptyList(), LibrarySettings()) { "" }
        val artists = LibraryTree.children(LibraryTree.ARTISTS, big)!!
        assertEquals(LibraryTree.MAX_CHILDREN + 30, artists.size)
        assertEquals(artists.take(LibraryTree.MAX_CHILDREN), LibraryTree.page(artists, 0, Int.MAX_VALUE))
        assertEquals(artists.subList(200, 300), LibraryTree.page(artists, 2, 100))
        assertEquals(artists.subList(500, 530), LibraryTree.page(artists, 5, 100))
        assertEquals(emptyList<TreeNode>(), LibraryTree.page(artists, 6, 100))
        assertEquals(artists.take(1), LibraryTree.page(artists, -3, 0))
        assertEquals(emptyList<TreeNode>(), LibraryTree.page(artists, Int.MAX_VALUE, Int.MAX_VALUE))
        // Songs pages as one list with Shuffle all as its first item.
        val songs = LibraryTree.children(LibraryTree.SONGS, big)!!
        assertEquals(LibraryTree.SHUFFLE_ALL, LibraryTree.page(songs, 0, 10).first().id)
        val last = LibraryTree.page(songs, 53, 10).last()
        assertEquals("Song 0530", last.song!!.title)
        assertEquals(529, (LibraryTree.play(last.id, big) as TreePlay.FromList).start)
    }

    @Test
    fun `playable tells songs and Shuffle all from lists`() {
        assertTrue(LibraryTree.playable(LibraryTree.SHUFFLE_ALL))
        assertTrue(LibraryTree.playable(children(LibraryTree.SONGS)[1].id))
        assertFalse(LibraryTree.playable(LibraryTree.ALBUMS))
        assertFalse(LibraryTree.playable(children(LibraryTree.ALBUMS)[0].id))
        assertFalse(LibraryTree.playable(""))
    }

    @Test
    fun `an id is a song only under a list that holds songs, at a place that is a number`() {
        assertTrue(LibraryTree.playable("${LibraryTree.SONGS}|0"))
        assertTrue(LibraryTree.playable("${LibraryTree.MOST_PLAYED}|3"))
        assertTrue(LibraryTree.playable("album:k|2"))
        assertFalse(LibraryTree.playable("${LibraryTree.ALBUMS}|2"))
        assertFalse(LibraryTree.playable("${LibraryTree.ROOT}|0"))
        assertFalse(LibraryTree.playable("nothing here|1"))
        assertFalse(LibraryTree.playable("album:|1"))
        assertFalse(LibraryTree.playable("album:k|-1"))
        assertFalse(LibraryTree.playable("album:k|x"))
        assertNull(LibraryTree.play("${LibraryTree.ALBUMS}|0", data()))
        assertNull(LibraryTree.play("|0", data()))
    }

    // The lists a car subscribes to, and those a changed cover is in

    @Test
    fun `every list of the tree may be subscribed to, never a song, Shuffle all or a heading`() {
        val lists = listOf(
            LibraryTree.ROOT, LibraryTree.root(4), LibraryTree.root(1), LibraryTree.QUEUES, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS,
            LibraryTree.SONGS, LibraryTree.FAVOURITES, LibraryTree.MOST_PLAYED, LibraryTree.RECENTLY_PLAYED, LibraryTree.RECENTLY_ADDED,
            "queue:q1", "playlist:p1", "album:k", "artist:Mira Solen", LibraryTree.searchId("road"),
        )
        lists.forEach { assertTrue(it, LibraryTree.isList(it)) }
        // Every list the tree hands out, down to an album's, an artist's and a playlist's.
        val data = data(favourites = listOf(ember.ref), mostPlayed = listOf(loose), recentlyPlayed = listOf(loose))
        val handed = listOf(LibraryTree.ROOT, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS).flatMap { children(it, data) }.filter { it.browsable }
        assertTrue(handed.size > 10)
        handed.forEach { assertTrue(it.id, LibraryTree.isList(it.id)) }
        val others = listOf(
            LibraryTree.SHUFFLE_ALL, children(LibraryTree.SONGS)[1].id, "album:k|2", LibraryTree.BUILT_FOR_YOU, LibraryTree.YOUR_PLAYLISTS,
            "album:", "queue:", "root:x", "root:9", "", "nothing here",
        )
        others.forEach { assertFalse(it, LibraryTree.isList(it)) }
    }

    @Test
    fun `a changed cover reaches every list that shows it, each once with its size, and no other`() {
        val source = QueueSource(SourceKind.ALBUM, albumKey(harbour[0])!!, "Harbour Glass")
        val book = QueueBook().play(harbour.map { it.ref }, 0, source, now = 1).book
        val found = Found("lantern", listOf(harbour[0], loose), QueueSource(SourceKind.SEARCH, "search:lantern", "lantern"))
        val data = data(book, favourites = listOf(harbour[0].ref, ember.ref), mostPlayed = listOf(harbour[0]), recentlyPlayed = listOf(loose), found = found)
        fun album(title: String) = children(LibraryTree.ALBUMS, data).single { it.title == title }.id
        fun artist(name: String) = children(LibraryTree.ARTISTS, data).single { it.title == name }.id
        val queue = children(LibraryTree.QUEUES, data).single().id
        val road = children(LibraryTree.PLAYLISTS, data).single { it.title == "Road trip" }.id

        // The album's first song: its own lists, and Albums, where it is the album's cover. Songs counts Shuffle all; the
        // playlist leaves out its song that is not on the phone. Search results are never told: a car never hears there.
        val first = mapOf(
            LibraryTree.SONGS to 7, LibraryTree.FAVOURITES to 2, LibraryTree.MOST_PLAYED to 1, LibraryTree.ALBUMS to 3,
            queue to 4, road to 2, album("Harbour Glass") to 4, artist("Kit Calloway") to 4,
        )
        assertEquals(first, LibraryTree.showing(setOf(harbour[0].path), data))
        // A song further on in the album: not Albums, where its album shows the first song's cover.
        assertEquals(
            mapOf(LibraryTree.SONGS to 7, queue to 4, album("Harbour Glass") to 4, artist("Kit Calloway") to 4),
            LibraryTree.showing(setOf(harbour[2].path), data),
        )
        // A burst of changes: each list once.
        val more = mapOf(LibraryTree.RECENTLY_PLAYED to 1, LibraryTree.RECENTLY_ADDED to 1, album("Night Ferry") to 1, artist("Mira Solen") to 1)
        assertEquals(first + more, LibraryTree.showing(setOf(harbour[0].path, harbour[2].path, loose.path), data))
        assertEquals(emptyMap<String, Int>(), LibraryTree.showing(setOf("/made/up/Gone.mp3"), data))
        assertEquals(emptyMap<String, Int>(), LibraryTree.showing(emptySet(), data))
    }

    // Search and voice

    @Test
    fun `the name search finds titles, then artists, then albums, ignoring case and accents`() = runTest {
        assertEquals(listOf(loose.path), NameSearch.songs("loose", library, LibrarySettings()).map { it.path })
        assertEquals(listOf(ember.path), NameSearch.songs("elan", library, LibrarySettings()).map { it.path })
        assertEquals(listOf(ember.path), NameSearch.songs("COAST", library, LibrarySettings()).map { it.path })
        // "Glass" is only in the album's name: its four songs, in track order.
        val glass = NameSearch.songs("  glass ", library, LibrarySettings())
        assertEquals(harbour.map { it.path }, glass.map { it.path })
        assertEquals(emptyList<Song>(), NameSearch.songs("   ", library, LibrarySettings()))
        assertEquals(emptyList<Song>(), NameSearch.songs("nothing like it", library, LibrarySettings()))
        // In library order an album match, an artist match, then a title match; ranked the other way round.
        val quiet = song("Music/A/Quiet Hour.mp3", title = "Quiet Hour", artist = "Nell Arden", album = "Road Atlas")
        val tide = song("Music/A/Tide.mp3", title = "Tide", artist = "Roadside Band")
        val night = song("Music/B/Night Road.mp3", title = "Night Road", artist = "Nell Arden")
        val shelf = Library.of(listOf(quiet, tide, night), Settings())
        assertEquals(listOf(quiet.path, tide.path, night.path), shelf.songs.map { it.path })
        assertEquals(listOf(night.path, tide.path, quiet.path), NameSearch.songs("road", shelf, LibrarySettings()).map { it.path })
    }

    @Test
    fun `the name search follows Show file names, and its words make one list in any case`() = runTest {
        assertEquals(listOf(loose.path), NameSearch.songs("thread.mp3", library, LibrarySettings(showFileNames = true)).map { it.path })
        assertEquals(NameSearch.source("Loose  THREAD"), NameSearch.source("loose thread").copy(name = "Loose THREAD"))
        assertEquals(SourceKind.SEARCH, NameSearch.source("loose").kind)
        assertEquals("Loose THREAD", NameSearch.source(" Loose  THREAD ").name)
    }

    @Test
    fun `search results are the songs found, and one picked plays them from it as a search queue`() {
        val source = QueueSource(SourceKind.SEARCH, "search:road", "road")
        val found = Found("road", listOf(ember, loose), source)
        val results = children(LibraryTree.searchId("road"), data(found = found))
        assertEquals(listOf(ember.path, loose.path), results.map { it.song!!.path })
        assertTrue(results.all { it.playable })
        assertEquals(TreePlay.FromList(listOf(ember.ref, loose.ref), 1, source), LibraryTree.play(results[1].id, data(found = found)))
        // Results of another search are not these.
        assertNull(LibraryTree.children(LibraryTree.searchId("ember"), data(found = found)))
        assertNull(LibraryTree.play(results[1].id, data(found = Found("ember", listOf(ember), source))))
    }

    @Test
    fun `voice plays what it found from the first song, or the queue there is, or every song shuffled`() {
        val source = QueueSource(SourceKind.SEARCH, "search:road", "road")
        assertEquals(TreePlay.FromList(listOf(ember.ref, loose.ref), 0, source), LibraryTree.voice(Found("road", listOf(ember, loose), source), data()))
        assertNull(LibraryTree.voice(Found("nothing", emptyList(), source), data()))
        val book = QueueBook().play(harbour.map { it.ref }, 1, QueueSource(SourceKind.ALBUM, "k", "Harbour Glass"), now = 1).book
        assertEquals(TreePlay.Resume, LibraryTree.voice(null, data(book)))
        assertTrue(LibraryTree.voice(null, data()) is TreePlay.Shuffle)
        assertNull(LibraryTree.voice(null, data(lib = Library.of(emptyList(), Settings()))))
    }

    @Test
    fun `the car's Recently added holds the songs of the period it was left on in the app`() {
        fun added(period: ListPeriod) =
            children(LibraryTree.RECENTLY_ADDED, TreeData(library, QueueBook(), emptyList(), LibrarySettings(listPeriods = mapOf(SongList.RECENTLY_ADDED to period)), now = now, zone = ZoneOffset.UTC) { "" })
                .map { it.song!!.path }

        assertEquals(listOf(loose.path), added(ListPeriod.DAYS_30))
        assertEquals(listOf(loose.path, ember.path), added(ListPeriod.DAYS_60))
        assertEquals(listOf(loose.path, ember.path) + harbour.map { it.path }, added(ListPeriod.ALL_TIME))
    }

}
