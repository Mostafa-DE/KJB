package app.lyrify.library

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A collection whose page has a heart: favouriting it is apart from favouriting its songs. */
enum class CollectionKind { ALBUM, ARTIST, FOLDER, PLAYLIST }

/** A favourite collection; [key] is its key in the library (an album's, an artist's name, a folder's path, a playlist's id). */
data class FavouriteCollection(val kind: CollectionKind, val key: String)

/**
 * Favourites, for everything with a heart: the player, the song sheet, Add to, collection pages and the widget.
 * library/Playlists.kt keeps them in `playlists.json` (milestone LC); until then [NoFavourites].
 */
interface FavouritesPort {
    /** The favourite songs, the latest added first. */
    val songs: Flow<List<SongRef>>

    /** The favourite albums, artists, folders and playlists, the latest added first. */
    val collections: Flow<List<FavouriteCollection>>

    /** Makes [songs] favourites (each once) or not; false when that could not be stored, and nothing changed. */
    suspend fun setSongs(songs: List<SongRef>, favourite: Boolean): Boolean

    /** Makes [collection] a favourite or not; false when that could not be stored, and nothing changed. */
    suspend fun setCollection(collection: FavouriteCollection, favourite: Boolean): Boolean
}

/** Until milestone LC: nothing is a favourite, and nothing can be stored. */
object NoFavourites : FavouritesPort {
    override val songs: Flow<List<SongRef>> = flowOf(emptyList())

    override val collections: Flow<List<FavouriteCollection>> = flowOf(emptyList())

    override suspend fun setSongs(songs: List<SongRef>, favourite: Boolean): Boolean = false

    override suspend fun setCollection(collection: FavouriteCollection, favourite: Boolean): Boolean = false
}

/** The library's objects, built by hand (milestone LC builds them; it may move this object to its own file). */
object LibraryGraph {
    @Suppress("UNUSED_PARAMETER") // Until LC: no store to open.
    fun favourites(context: Context): FavouritesPort = NoFavourites
}
