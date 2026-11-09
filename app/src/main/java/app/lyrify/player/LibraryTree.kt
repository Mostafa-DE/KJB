package app.lyrify.player

import app.lyrify.library.Library
import app.lyrify.library.Playlist
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.library.sortSongs
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.SortSongs

/** One node of the browse tree: a list to open, or a [song] to play. */
data class TreeNode(val id: String, val title: String, val subtitle: String? = null, val song: Song? = null) {
    val browsable: Boolean get() = song == null
}

/** What playing a song node does: play its list from it, or move inside a queue to it. */
sealed interface TreePlay {
    data class FromList(val songs: List<SongRef>, val start: Int, val source: QueueSource) : TreePlay

    data class InQueue(val queueId: String, val index: Int) : TreePlay
}

/** What the tree is built from: the library, the queues, the user's playlists, "Show file names", and the names to show. */
class TreeData(
    val library: Library,
    val book: QueueBook,
    val playlists: List<Playlist>,
    val settings: LibrarySettings,
    /** The title of a top-level node, by its id ("Albums"). */
    val rootName: (String) -> String = { it },
    /** A queue's name as the app shows it ("Album · Harbour Glass"). */
    val queueName: (Queue) -> String,
)

/**
 * The browse tree for Bluetooth and, later, Android Auto (milestone A4 completes it): Recent queues, Playlists, Albums,
 * Artists and Songs, each level capped at [MAX_CHILDREN]. Pure: the service turns nodes into media items. A song's id is
 * its list's id, a bar, and its place there ("album:<key>|3"), so playing it plays that list from it, as a tap in the
 * app does (the same source keys).
 */
object LibraryTree {
    const val ROOT: String = "root"
    const val QUEUES: String = "queues"
    const val PLAYLISTS: String = "playlists"
    const val ALBUMS: String = "albums"
    const val ARTISTS: String = "artists"
    const val SONGS: String = "songs"

    /** No list in the tree holds more; a car or a watch pages through less. */
    const val MAX_CHILDREN: Int = 500

    private const val QUEUE = "queue:"
    private const val PLAYLIST = "playlist:"
    private const val ALBUM = "album:"
    private const val ARTIST = "artist:"
    private const val SONG = '|'

    /** The children of [id]; null when there is no such list (a song, or one gone). */
    fun children(id: String, data: TreeData): List<TreeNode>? {
        val library = data.library
        return when {
            id == ROOT -> listOf(QUEUES, PLAYLISTS, ALBUMS, ARTISTS, SONGS).map { TreeNode(it, data.rootName(it)) }
            id == QUEUES -> (listOfNotNull(data.book.currentId) + data.book.saved + data.book.recent).distinct()
                .mapNotNull(data.book::queue).map { TreeNode(QUEUE + it.id, data.queueName(it)) }
            id == PLAYLISTS -> data.playlists.map { TreeNode(PLAYLIST + it.id, it.name) }
            id == ALBUMS -> library.albums.map { TreeNode(ALBUM + it.key, it.title, it.artist) }
            id == ARTISTS -> library.artists.map { TreeNode(ARTIST + it.name, it.name) }
            else -> list(id, data)?.let { songs -> songs.mapIndexedNotNull { i, song -> song?.let { node("$id$SONG$i", it, data.settings) } } }
        }?.take(MAX_CHILDREN)
    }

    /** What playing song node [id] does; null when it is no song in the tree. */
    fun play(id: String, data: TreeData): TreePlay? {
        val parent = id.substringBeforeLast(SONG, missingDelimiterValue = "")
        val index = id.substringAfterLast(SONG).toIntOrNull() ?: return null
        if (parent.isEmpty()) return null
        if (parent.startsWith(QUEUE)) {
            val queue = data.book.queue(parent.removePrefix(QUEUE)) ?: return null
            return if (index in queue.items.indices) TreePlay.InQueue(queue.id, index) else null
        }
        val songs = list(parent, data)?.filterNotNull() ?: return null
        if (index !in songs.indices) return null
        return TreePlay.FromList(songs.map { it.ref }, index, source(parent, data) ?: return null)
    }

    /** The songs of list [id] in order; for a queue, by its items' places, null where a song is not on the phone. */
    private fun list(id: String, data: TreeData): List<Song?>? {
        val library = data.library
        return when {
            id == SONGS -> sortSongs(library.songs, SortSongs.TITLE, data.settings)
            id.startsWith(QUEUE) -> data.book.queue(id.removePrefix(QUEUE))?.items?.map { library.resolver.find(it.song) }
            id.startsWith(PLAYLIST) -> data.playlists.firstOrNull { it.id == id.removePrefix(PLAYLIST) }?.let { library.listed(it.songs) }
            id.startsWith(ALBUM) -> library.album(id.removePrefix(ALBUM))?.songs
            id.startsWith(ARTIST) -> library.artist(id.removePrefix(ARTIST))?.songs
            else -> null
        }
    }

    private fun source(id: String, data: TreeData): QueueSource? = when {
        id == SONGS -> QueueSource(SourceKind.SONGS, SONGS, "")
        id.startsWith(PLAYLIST) -> data.playlists.firstOrNull { it.id == id.removePrefix(PLAYLIST) }?.let { QueueSource(SourceKind.PLAYLIST, id, it.name) }
        id.startsWith(ALBUM) -> data.library.album(id.removePrefix(ALBUM))?.let { QueueSource(SourceKind.ALBUM, id, it.title) }
        id.startsWith(ARTIST) -> QueueSource(SourceKind.ARTIST, id, id.removePrefix(ARTIST))
        else -> null
    }

    private fun node(id: String, song: Song, settings: LibrarySettings) = TreeNode(id, displayTitle(song, settings), song.artist, song)
}
