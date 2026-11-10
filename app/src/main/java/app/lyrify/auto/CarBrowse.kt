package app.lyrify.auto

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.mostPlayed
import app.lyrify.library.recentlyPlayed
import app.lyrify.player.Found
import app.lyrify.player.LibraryTree
import app.lyrify.player.NameSearch
import app.lyrify.player.PlayerCore
import app.lyrify.player.TreeData
import app.lyrify.player.TreeNode
import app.lyrify.player.TreePlay
import app.lyrify.player.TreeSearch
import app.lyrify.player.metadataOf
import app.lyrify.player.queueLabel
import app.lyrify.settings.Settings
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * KJB in the car, and in any other media browser (Bluetooth, the system's media controls): the playback service's
 * browse tree ([LibraryTree]) as media items, search, and voice play. The root honours Android Auto's limit on root
 * items; lists show as lists and Albums as a grid; a list a client asks for whole is capped, one it pages is not. A song
 * or Shuffle all picked in the car, or a voice request, changes KJB's own queue model, which the session then plays.
 * Main thread; lists are built once the queues and the library are read. The log gets counts and exception names.
 */
@OptIn(UnstableApi::class)
class CarBrowse(
    private val context: Context,
    private val session: MediaLibrarySession,
    private val core: PlayerCore,
    private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
    /** The queue playing as media items at its place: what the session hands the player after a change. */
    private val playing: () -> MediaSession.MediaItemsWithStartPosition,
    private val search: TreeSearch = NameSearch,
) {
    /** The car's last search, so a song picked in its results plays them. */
    private var found: Found? = null

    init {
        // Recent queues follows the queues: a car showing it hears when one comes or goes.
        scope.launch {
            core.book.map { (listOfNotNull(it.currentId) + it.saved + it.recent).distinct() }.distinctUntilChanged().drop(1)
                .collect { session.notifyChildrenChanged(LibraryTree.QUEUES, it.size, null) }
        }
    }

    /**
     * The root, at once and never null: as many top lists as [params]' root hint allows (Android Auto's, usually 4), and
     * the list style for every level. [browser] may read the covers from now on.
     */
    fun root(browser: MediaSession.ControllerInfo, params: LibraryParams?): LibraryResult<MediaItem> {
        CarCovers.allow(context, browser.packageName)
        val limit = params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)
        val styles = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return LibraryResult.ofItem(rootItem(LibraryTree.root(limit)), LibraryParams.Builder().setExtras(styles).build())
    }

    fun children(parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val nodes = LibraryTree.children(parentId, data(plays = needsPlays(parentId)))
        if (nodes == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItemList(LibraryTree.page(nodes, page, pageSize).map(::item), params)
    }

    fun item(mediaId: String): LibraryResult<MediaItem> =
        if (LibraryTree.isRoot(mediaId)) LibraryResult.ofItem(rootItem(mediaId), null) else LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)

    /** A search typed or said in the car: the songs found, then [browser] hears how many, and asks for them. */
    fun search(browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val songs = try {
                find(query, data().library).also { found = it }.songs.size
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(LOG_TAG, "car search failed: ${e.javaClass.simpleName}")
                0
            }
            session.notifySearchResultChanged(browser, query, songs, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    fun searchResult(query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        if (found?.query != query) found = find(query, data().library)
        val nodes = LibraryTree.children(LibraryTree.searchId(query), data()).orEmpty()
        LibraryResult.ofItemList(LibraryTree.page(nodes, page, pageSize).map(::item), params)
    }

    /**
     * A song or Shuffle all picked in the tree, or a voice request ("Play <words> on KJB", or no words): the queue model
     * changes as a tap in the app changes it, and the session gets the queue playing. Null when [items] are none of
     * these. A voice request that finds nothing tells [controller] so.
     */
    fun setMediaItems(controller: MediaSession.ControllerInfo, items: List<MediaItem>): ListenableFuture<MediaSession.MediaItemsWithStartPosition>? {
        val item = items.singleOrNull() ?: return null
        val words = item.requestMetadata.searchQuery
        if (words == null && !LibraryTree.playable(item.mediaId)) return null
        return future {
            val data = data(plays = words == null && needsPlays(item.mediaId))
            val play = if (words == null) LibraryTree.play(item.mediaId, data) else LibraryTree.voice(words.takeIf(String::isNotBlank)?.let { find(it, data.library) }, data)
            when (play) {
                is TreePlay.FromList -> core.switch { it.play(play.songs, play.start, play.source, core.now()) }
                is TreePlay.InQueue -> core.switch { it.jump(play.queueId, play.index, core.now()) }
                is TreePlay.Shuffle -> core.switch { it.shuffle(play.songs, play.source, core.shuffleRandom(), core.now()) }
                TreePlay.Resume -> Unit
                null -> {
                    if (words != null) session.sendError(controller, SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.auto_nothing_found)))
                    throw IllegalStateException("nothing to play")
                }
            }
            playing()
        }
    }

    /** What the tree is built from now: once the queues and the library are read; Most played and Recently played only for the lists that show them. */
    private suspend fun data(plays: Boolean = false): TreeData {
        core.ready.first { it }
        val settings = settings.value
        val state = LibraryGraph.repository(context).state.first { it !is LibraryState.Loading }
        val library = (state as? LibraryState.Ready)?.library ?: Library.of(emptyList(), settings)
        val lists = LibraryGraph.playlists(context).state.first()
        val (most, recent) = if (!plays) {
            emptyList<Song>() to emptyList()
        } else {
            withContext(Dispatchers.IO) { Lyrify.plays(context).let { mostPlayed(it, library) to recentlyPlayed(it, library) } }
        }
        return TreeData(
            library = library,
            book = core.book.value,
            playlists = lists.playlists,
            settings = settings.library,
            name = ::name,
            favourites = lists.favouriteSongs,
            mostPlayed = most,
            recentlyPlayed = recent,
            now = System.currentTimeMillis(),
            found = found,
        ) { queueLabel(context, it) }
    }

    private suspend fun find(query: String, library: Library): Found =
        Found(query, withContext(Dispatchers.Default) { search.songs(query, library, settings.value.library) }, search.source(query))

    private fun needsPlays(id: String): Boolean = id == LibraryTree.PLAYLISTS || id.startsWith(LibraryTree.MOST_PLAYED) || id.startsWith(LibraryTree.RECENTLY_PLAYED)

    private fun rootItem(id: String): MediaItem = item(TreeNode(id, context.getString(R.string.app_name)))

    /** [node] as a media item: a song with its tags, or a list; its cover through [CarCovers]; its heading and grid as style hints. */
    private fun item(node: TreeNode): MediaItem {
        val song = node.song
        val metadata = song?.let { metadataOf(it, settings.value.library).buildUpon() }
            ?: MediaMetadata.Builder().setTitle(node.title).setSubtitle(node.subtitle)
                .setMediaType(if (node.playable) MediaMetadata.MEDIA_TYPE_MIXED else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        val extras = Bundle()
        node.group?.let { extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        if (node.grid) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        metadata.setIsBrowsable(node.browsable).setIsPlayable(node.playable)
            .setArtworkUri(node.cover?.ref?.mediaStoreId?.let { CarCovers.uri(context, it) })
        if (!extras.isEmpty) metadata.setExtras(extras)
        return MediaItem.Builder().setMediaId(node.id).setMediaMetadata(metadata.build()).build()
    }

    private fun name(id: String): String = context.getString(
        when (id) {
            LibraryTree.QUEUES -> R.string.player_tree_queues
            LibraryTree.PLAYLISTS -> R.string.player_tree_playlists
            LibraryTree.ALBUMS -> R.string.player_tree_albums
            LibraryTree.ARTISTS -> R.string.player_tree_artists
            LibraryTree.SONGS -> R.string.player_tree_songs
            LibraryTree.SHUFFLE_ALL -> R.string.auto_shuffle_all
            LibraryTree.FAVOURITES -> R.string.queue_source_favourites
            LibraryTree.MOST_PLAYED -> R.string.queue_source_most_played
            LibraryTree.RECENTLY_PLAYED -> R.string.queue_source_recently_played
            LibraryTree.RECENTLY_ADDED -> R.string.queue_source_recently_added
            LibraryTree.BUILT_FOR_YOU -> R.string.auto_built_for_you
            LibraryTree.YOUR_PLAYLISTS -> R.string.auto_your_playlists
            else -> return id
        },
    )

    /** [block] on the service's scope, as the future a session callback returns; its failure fails the future. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        scope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "car request failed: ${e.javaClass.simpleName}")
                result.setException(e)
            }
        }
        return result
    }
}

/**
 * Covers for the car's lists: a song's cover by its MediaStore id only (no paths in the uri), as a JPEG made from
 * MediaStore's thumbnail of the file's embedded art. Android Auto cannot read MediaStore's audio uris (and Media3 hands
 * a browse item's artwork to it as a uri), so the tree's items point here. Not exported: [allow] lets each app that
 * browses the tree read every cover. Read-only.
 */
class CarCovers : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = JPEG

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("covers are read-only")
        val id = uri.pathSegments.singleOrNull()?.toLongOrNull() ?: throw FileNotFoundException("not a cover")
        val resolver = context?.contentResolver ?: throw FileNotFoundException("not ready")
        val bitmap = try {
            resolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), Size(COVER_PX, COVER_PX), null)
        } catch (_: IOException) {
            // No embedded cover, or the song is gone: the car shows its own.
            throw FileNotFoundException("no cover")
        }
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return openPipeHelper(uri, JPEG, null, jpeg) { output, _, _, _, bytes ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(bytes) }
            } catch (e: IOException) {
                // The car stopped reading (it left the list): nothing to do.
                Log.d(LOG_TAG, "car cover not sent: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val JPEG = "image/jpeg"
        private const val JPEG_QUALITY = 90

        /** The car shows covers at most this size; Auto's grid tiles are smaller. */
        private const val COVER_PX = 512

        fun uri(context: Context, mediaStoreId: Long): Uri =
            Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority(context)).appendPath(mediaStoreId.toString()).build()

        /** [packageName] may read every cover until the phone restarts: a prefix grant, one call per browser. */
        fun allow(context: Context, packageName: String) {
            if (packageName == context.packageName) return
            val all = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority(context)).path("/").build()
            try {
                context.grantUriPermission(packageName, all, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            } catch (e: RuntimeException) {
                // A browser gone, or one the phone does not know: its lists show without covers.
                Log.w(LOG_TAG, "car covers not allowed: ${e.javaClass.simpleName}")
            }
        }

        /** The manifest's `${applicationId}.covers`, so the debug build has its own. */
        private fun authority(context: Context): String = context.packageName + ".covers"
    }
}
