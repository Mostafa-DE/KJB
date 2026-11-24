package app.lyrify.auto

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
import app.lyrify.books.BooksGraph
import app.lyrify.books.asBook
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.mostPlayed
import app.lyrify.library.notPlayed
import app.lyrify.library.recentlyPlayed
import app.lyrify.player.Found
import app.lyrify.player.LibraryTree
import app.lyrify.player.Move
import app.lyrify.player.NameSearch
import app.lyrify.player.OpenedFiles
import app.lyrify.player.PlayerCore
import app.lyrify.player.TreeData
import app.lyrify.player.TreeNode
import app.lyrify.player.TreePlay
import app.lyrify.player.TreeSearch
import app.lyrify.player.metadataOf
import app.lyrify.player.openedCoverDir
import app.lyrify.player.openedCoverKey
import app.lyrify.player.queueLabel
import app.lyrify.player.queueLabels
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SongList
import app.lyrify.stats.sinceOf
import app.lyrify.tags.CoverVersions
import app.lyrify.tags.TagsGraph
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * KJB in the car, and in the system's other media browsers (System UI, Bluetooth): the playback service's browse tree
 * ([LibraryTree]) as media items, search, and voice play. Who may see the library is decided once per connection
 * ([connect], [ControllerAccess]): KJB itself, the system's trusted controllers and the car; any other app gets plain
 * transport only. The root honours Android Auto's limit on root items; lists show as lists and Albums as a grid; a list
 * a client asks for whole is capped, one it pages is not. Lists are built from a snapshot off the main thread and paged
 * before they become media items. Every song and album carries a cover from [CarCovers] (its own, or KJB's default
 * cover), at a uri only it can read ([CoverUris]). A song or
 * Shuffle all picked in the car, or a voice request, changes KJB's own queue
 * model, which the session then plays. Called on the main thread. The log gets counts and exception names.
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
    private val access: ControllerAccess = ControllerAccess.of(context),
) {
    /** Each connection's access while it is connected. Main thread. */
    private val connected = HashMap<MediaSession.ControllerInfo, Access>()

    /** The car's searches, per app and words. Main thread. */
    private val searches = Searches()

    /** The covers' uris. */
    private val covers = CarCovers.uris(context)

    /** `books.json`: where a book's file picked in the car plays its book from. */
    private val books = BooksGraph.store(context).state

    init {
        // Recent queues follows the queues: a car showing it hears when one comes or goes.
        scope.launch {
            core.book.map { book -> book.listed.map { it.id } }.distinctUntilChanged().drop(1)
                .collect { session.notifyChildrenChanged(LibraryTree.QUEUES, it.size, null) }
        }
        // A cover changed (Covers & tags, Edit tags, an Undo): once the burst settles, each list that shows one hears so,
        // and a car showing it reads it again at the covers' new uris.
        scope.launch { coverBursts(CoverVersions.versions, COVERS_SETTLE_MS, ::coversChanged) }
    }

    /** What [controller] may do, decided as it connects (the session's onConnect). */
    fun connect(controller: MediaSession.ControllerInfo): Access {
        val granted = access.of(Caller(controller.packageName, controller.uid, controller.isTrusted))
        connected[controller] = granted
        return granted
    }

    /** [controller] left. */
    fun disconnect(controller: MediaSession.ControllerInfo) {
        connected.remove(controller)
    }

    /**
     * Whether a car is connected now (Android Auto, a car's media centre): a play that starts then is heard in the car
     * (Insights' Where you listen), as Android Auto's sound goes through its own link, not an output the phone names.
     */
    val inCar: Boolean get() = Access.CAR in connected.values

    /** The root, at once and never null: as many top lists as [params]' root hint allows (Android Auto's, usually 4), and the list style for every level. */
    fun root(params: LibraryParams?): LibraryResult<MediaItem> {
        val limit = params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)
        val styles = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return LibraryResult.ofItem(rootItem(LibraryTree.root(limit)), LibraryParams.Builder().setExtras(styles).build())
    }

    fun children(browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val data = data(browser, plays = needsPlays(parentId))
        val nodes = withContext(Dispatchers.Default) { LibraryTree.children(parentId, data)?.let { LibraryTree.page(it, page, pageSize) } }
            ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItemList(items(nodes), params)
    }

    fun item(mediaId: String): LibraryResult<MediaItem> =
        if (LibraryTree.isRoot(mediaId)) LibraryResult.ofItem(rootItem(mediaId), null) else LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)

    /**
     * [browser] subscribes to list [parentId], so it hears when the list changes (Recent queues, a new cover in it).
     * Media3's own would ask [item], which knows only the root, and drop the subscription to any other list.
     */
    fun subscribe(browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): LibraryResult<Void> {
        if (!LibraryTree.isList(parentId)) return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        // As Media3's own does: a Media3 browser hears at once that the list is there to read (a car reads it as it subscribes).
        if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
        return LibraryResult.ofVoid()
    }

    /** A search typed or said in the car: the songs found, kept for [browser]'s app; then it hears how many, and asks for them. */
    fun search(browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val songs = try {
                find(query, data(browser).library).also { searches.put(browser.packageName, it) }.songs.size
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(LOG_TAG, "car search failed: ${e.javaClass.simpleName}")
                0
            }
            session.notifySearchResultChanged(browser, query, songs, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    fun searchResult(browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        if (searches.of(browser.packageName)[query] == null) searches.put(browser.packageName, find(query, data(browser).library))
        val data = data(browser)
        val nodes = withContext(Dispatchers.Default) { LibraryTree.page(LibraryTree.children(LibraryTree.searchId(query), data).orEmpty(), page, pageSize) }
        LibraryResult.ofItemList(items(nodes), params)
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
            val data = data(controller, plays = words == null && needsPlays(item.mediaId))
            val found = words?.takeIf(String::isNotBlank)?.let { find(it, data.library) }
            val play = withContext(Dispatchers.Default) { if (words == null) LibraryTree.play(item.mediaId, data) else LibraryTree.voice(found, data) }
            when (play) {
                // A book's file plays its book, as in the app (books/BookPlayback.kt).
                is TreePlay.FromList -> core.switch { it.asBook(it.play(play.songs, play.start, play.source, core.now()), data.library, books.value, core.now()) }
                is TreePlay.InQueue -> core.switch { it.asBook(it.jump(play.queueId, play.index, core.now()), data.library, books.value, core.now()) }
                is TreePlay.Shuffle -> core.switch(Move.START) { it.shuffle(play.songs, play.source, core.shuffleRandom(), core.now()) }
                TreePlay.Resume -> Unit
                null -> {
                    if (words != null) session.sendError(controller, SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.auto_nothing_found)))
                    throw IllegalStateException("nothing to play")
                }
            }
            playing()
        }
    }

    /**
     * A snapshot of what the tree is built from, once the queues and the library are read, with [controller]'s app's
     * searches (none without one); Most played, Recently played and Not played only for the lists that show them.
     */
    private suspend fun data(controller: MediaSession.ControllerInfo?, plays: Boolean = false): TreeData {
        core.ready.first { it }
        val settings = settings.value
        val state = LibraryGraph.repository(context).state.first { it !is LibraryState.Loading }
        val library = (state as? LibraryState.Ready)?.library ?: Library.of(emptyList(), settings)
        val lists = LibraryGraph.playlists(context).state.first()
        val (most, recent, unplayed) = if (!plays) {
            Triple(emptyList<Song>(), emptyList(), emptyList())
        } else {
            // Each over the period it was left on in the app, as the app shows it.
            withContext(Dispatchers.IO) {
                Lyrify.plays(context).let {
                    Triple(
                        mostPlayed(it, library, sinceOf(settings.library.periodOf(SongList.MOST_PLAYED), System.currentTimeMillis(), ZoneId.systemDefault())),
                        recentlyPlayed(it, library, sinceOf(settings.library.periodOf(SongList.RECENTLY_PLAYED), System.currentTimeMillis(), ZoneId.systemDefault())),
                        notPlayed(it, library, sinceOf(settings.library.periodOf(SongList.NOT_PLAYED), System.currentTimeMillis(), ZoneId.systemDefault())).map { (song, _) -> song },
                    )
                }
            }
        }
        val book = core.book.value
        val queueNames = queueLabels(context, book)
        return TreeData(
            library = library,
            book = book,
            playlists = lists.playlists,
            settings = settings.library,
            name = ::name,
            favourites = lists.favouriteSongs,
            mostPlayed = most,
            recentlyPlayed = recent,
            notPlayed = unplayed,
            now = System.currentTimeMillis(),
            found = controller?.let { searches.of(it.packageName) }.orEmpty(),
        ) { queueNames[it.id] ?: queueLabel(context, book, it) }
    }

    /** Each list that shows a cover of [paths] told it changed, once, with how many children it has now; false when it could not be. */
    private suspend fun coversChanged(paths: Set<String>): Boolean = try {
        val data = data(null, plays = true)
        val lists = withContext(Dispatchers.Default) { LibraryTree.showing(paths, data) }
        lists.forEach { (id, size) -> session.notifyChildrenChanged(id, size, null) }
        Log.d(LOG_TAG, "car: ${lists.size} lists told of ${paths.size} new covers")
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(LOG_TAG, "car lists not told of new covers: ${e.javaClass.simpleName}")
        false
    }

    private suspend fun find(query: String, library: Library): Found =
        Found(query, withContext(Dispatchers.Default) { search.songs(query, library, settings.value.library) }, search.source(query))

    private fun needsPlays(id: String): Boolean =
        id == LibraryTree.PLAYLISTS || id.startsWith(LibraryTree.MOST_PLAYED) || id.startsWith(LibraryTree.RECENTLY_PLAYED) || id.startsWith(LibraryTree.NOT_PLAYED)

    /** [nodes] as media items, built off the main thread. */
    private suspend fun items(nodes: List<TreeNode>): List<MediaItem> {
        val library = settings.value.library
        return withContext(Dispatchers.Default) { nodes.map { item(it, library) } }
    }

    private fun rootItem(id: String): MediaItem = item(TreeNode(id, context.getString(R.string.app_name)), settings.value.library)

    /**
     * [node] as a media item: a song with its tags, or a list; a song's or an album's cover from [CarCovers] ([coverUri]:
     * its own, or the default; none for a list of lists, which the car shows with its own icons); its heading and grid as
     * style hints.
     */
    private fun item(node: TreeNode, library: LibrarySettings): MediaItem {
        val song = node.song
        val metadata = song?.let { metadataOf(it, library, covers).buildUpon() }
            ?: MediaMetadata.Builder().setTitle(node.title).setSubtitle(node.subtitle)
                .setMediaType(if (node.playable) MediaMetadata.MEDIA_TYPE_MIXED else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        val extras = Bundle()
        node.group?.let { extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        if (node.grid) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        metadata.setIsBrowsable(node.browsable).setIsPlayable(node.playable)
            .setArtworkUri(node.cover?.let { coverUri(covers, it) })
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
            LibraryTree.NOT_PLAYED -> R.string.queue_source_not_played
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

    private companion object {
        /** How long a burst of cover changes may go on before the car's lists hear of it: at most one reload a list in this time. */
        const val COVERS_SETTLE_MS: Long = 2_000
    }
}

/** What a media controller may do with KJB's session. */
enum class Access {
    /** The library, search and Recent queues, and the car's covers: Android Auto or an Automotive media centre. */
    CAR,

    /** The library, search, Recent queues and queue changes: KJB itself and the system's trusted controllers. */
    LIBRARY,

    /** Play, pause, next, previous, seeking in the song and the song playing, as every media notification shows: any other app. */
    TRANSPORT,
}

/** A media controller as [ControllerAccess] sees it: the session's ControllerInfo on the phone, made up in tests. */
data class Caller(val packageName: String, val uid: Int, val trusted: Boolean)

/** What a controller is offered as it connects ([ControllerAccess.offer]): player commands by code, and session commands. */
data class Offer<T>(val player: Set<Int>, val session: Set<T>)

/**
 * Who may see KJB's library through its media session. KJB itself (its package and uid); the controllers the system
 * trusts ([Caller.trusted]: System UI, Bluetooth, apps with notification access); Android Auto signed with Google's
 * release key, and an Automotive media centre signed with the phone's platform key, checked by signing certificate
 * ([signedBy]), never by package name alone. Any other app gets [TRANSPORT_COMMANDS] only.
 */
class ControllerAccess(
    private val ownPackage: String,
    private val ownUid: Int,
    /** Whether the app running as a uid is signed with the certificate of this SHA-256 digest (upper-case hex). */
    private val signedBy: (uid: Int, sha256: String) -> Boolean,
    /** The SHA-256 digests of the phone's platform certificate. */
    private val platform: () -> Set<String>,
) {
    fun of(caller: Caller): Access = when {
        car(caller) -> Access.CAR
        caller.packageName == ownPackage && caller.uid == ownUid -> Access.LIBRARY
        caller.trusted -> Access.LIBRARY
        else -> Access.TRANSPORT
    }

    private fun car(caller: Caller): Boolean = when (caller.packageName) {
        ANDROID_AUTO -> AUTO_CERTIFICATES.any { signedBy(caller.uid, it) }
        in MEDIA_CENTRES -> platform().any { signedBy(caller.uid, it) }
        else -> false
    }

    companion object {
        const val ANDROID_AUTO: String = "com.google.android.projection.gearhead"

        /** Android Automotive's media centre (a platform app). */
        val MEDIA_CENTRES: Set<String> = setOf("com.android.car.media")

        /** Android Auto's release signing certificates, the current and the rotated one (Google's UAMP allowlist). */
        val AUTO_CERTIFICATES: Set<String> = setOf(
            "FDB00C43DBDE8B51CB312AA81D3B5FA17713ADB94B28F598D77F8EB89DACEEDF",
            "1CA8DCC0BED3CBD872D2CB791200C0292CA9975768A82D676B8B424FB65B5295",
        )

        /** Plain transport: play and pause, next and previous, seeking in the song, and the song playing; never the queue or a change to it. */
        val TRANSPORT_COMMANDS: List<Int> = listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
        )

        /**
         * The session commands a controller with [access] is offered: none for plain transport; else the session's own
         * ([base]) and the service parts' custom ones ([own]: Favourite, Shuffle, Repeat).
         */
        fun <T> sessionCommands(access: Access, base: Set<T>, own: List<T>): Set<T> = if (access == Access.TRANSPORT) emptySet() else base + own

        /**
         * What a controller with [access] is offered as it connects, out of [every] player command and the session's own
         * commands ([session], the library's included): KJB, the system and the car get them all, with the parts' ([own]);
         * any other app gets [TRANSPORT_COMMANDS] and no session command. On the phone [every] and [session] are Media3's
         * ConnectionResult defaults, never what its own onConnect returns: since Media3 1.11 that offers no command at all.
         */
        fun <T> offer(access: Access, every: Set<Int>, session: Set<T>, own: List<T>): Offer<T> =
            Offer(if (access == Access.TRANSPORT) every intersect TRANSPORT_COMMANDS.toSet() else every, sessionCommands(access, session, own))

        /** The rule on the phone: signing certificates from the package manager. */
        fun of(context: Context): ControllerAccess {
            val packages = context.packageManager
            return ControllerAccess(
                ownPackage = context.packageName,
                ownUid = Process.myUid(),
                signedBy = { uid, sha256 -> packages.hasSigningCertificate(uid, bytes(sha256), PackageManager.CERT_INPUT_SHA256) },
                platform = { platformCertificates(packages) },
            )
        }

        private fun platformCertificates(packages: PackageManager): Set<String> = try {
            packages.getPackageInfo("android", PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty()
                .map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02X".format(it) } }
                .toSet()
        } catch (_: PackageManager.NameNotFoundException) {
            emptySet()
        }

        private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** The path of KJB's default cover ([R.drawable.kjb_default_cover]) at [CarCovers]. */
const val DEFAULT_COVER: String = "default"

/**
 * The path of [song]'s cover at [CarCovers]: its MediaStore id (no path in the uri), a copy of a file another app opened
 * by its key ([openedCoverKey]), or the default cover without one.
 */
fun coverPath(song: Song): String = song.ref.mediaStoreId?.toString() ?: openedCoverKey(song.path) ?: DEFAULT_COVER

/**
 * The uri of [song]'s cover at [CarCovers] ([coverPath]), at its version in this process ([CoverVersions]): what the
 * session and the car's lists hand out, so a cover Covers & tags changed is a new uri.
 */
fun coverUri(covers: CoverUris, song: Song): Uri = covers.of(coverPath(song), CoverVersions.of(song.path))

/**
 * Follows the covers' [versions] ([CoverVersions]) a burst at a time: from the first change, [settleMs] for more to come
 * (Covers & tags applies album after album, an Undo puts several back), then [changed] hears every song whose version
 * moved since it was last told, once. A change while [changed] runs is the next burst's; songs it could not tell of
 * (false) go with the next burst, never tried again before it. Never returns.
 */
suspend fun coverBursts(versions: StateFlow<Map<String, Int>>, settleMs: Long, changed: suspend (Set<String>) -> Boolean): Nothing {
    var told = versions.value
    var owed = emptySet<String>()
    while (true) {
        versions.first { it != told }
        delay(settleMs)
        val now = versions.value
        val paths = owed + (now.keys + told.keys).filter { now[it] != told[it] }
        told = now
        owed = if (paths.isEmpty() || changed(paths)) emptySet() else paths
    }
}

/**
 * What [CarCovers] serves another app (the car) at [path]: song [path]'s embedded cover ([embedded], by its MediaStore id)
 * when it has one; else the default cover ([fallback]), so a car never shows a gap. No library check, so nothing waits
 * for the library's first read: [path] comes only under KJB's own signature ([CoverUris]), and KJB signs only its own
 * songs' ids. Android Auto starts KJB as the car connects, asks for covers at once and keeps each by its uri: a default
 * served then would stay. An opened copy's key gets the default: the copy's own cover is KJB's surfaces' only ([ownCover]).
 */
fun <T : Any> coverFor(path: String, embedded: (Long) -> T?, fallback: () -> T): T =
    path.toLongOrNull()?.let(embedded) ?: fallback()

/**
 * Cover [path] as KJB's own surfaces read it in the process ([CarCovers.read]): song [path]'s embedded cover ([embedded],
 * by its MediaStore id), else the default cover ([fallback]) when MediaStore has none for it (FileNotFoundException:
 * no embedded art, or the song is gone) or the path is not a song's; the cover of the opened copy [path] names ([opened],
 * never a car's), else the default; null when it could not be read now (any other IOException, a SecurityException or
 * any other RuntimeException, such as a thumbnail that did not decode, or an OutOfMemoryError), told to [failed]: a
 * surface that keeps its cover per song reads it again later, and the session shows the default cover, never a failure.
 */
fun <T : Any> ownCover(path: String, embedded: (Long) -> T, opened: (String) -> T? = { null }, failed: (Throwable) -> Unit = {}, fallback: () -> T): T? {
    val id = path.toLongOrNull()
    return try {
        if (id != null) embedded(id) else path.takeIf { openedCoverDir(it) != null }?.let(opened) ?: fallback()
    } catch (e: FileNotFoundException) {
        fallback()
    } catch (e: IOException) {
        failed(e)
        null
    } catch (e: RuntimeException) {
        failed(e)
        null
    } catch (e: OutOfMemoryError) {
        failed(e)
        null
    }
}

/** The car's searches, per app and words, so two browsers never take each other's results; the latest [KEPT] stay. Not thread-safe. */
class Searches {
    private val kept = object : LinkedHashMap<Pair<String, String>, Found>(KEPT, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Found>?): Boolean = size > KEPT
    }

    fun put(packageName: String, found: Found) {
        kept[packageName to found.query] = found
    }

    /** [packageName]'s searches, by their words. */
    fun of(packageName: String): Map<String, Found> = kept.filterKeys { it.first == packageName }.mapKeys { it.key.second }

    companion object {
        const val KEPT: Int = 8
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * KJB's covers as content uris, for Android Auto (its lists, its queue and the song playing) and KJB's own media session
 * (the notification and the lock screen, through the session's bitmap loader, [bitmap]): a song's cover by its
 * MediaStore id only (no paths in the uri), a JPEG of MediaStore's thumbnail of the file's embedded art at most
 * [COVER_PX] square, or KJB's default cover ([R.drawable.kjb_default_cover]) when the song has none or the path is
 * [DEFAULT_COVER], never waiting for the library ([coverFor]). Android Auto cannot read MediaStore's audio uris (Media3
 * hands it a uri, and the cover of a MediaStore audio uri is not an image), so every item points here. Exported, so
 * whichever of Android Auto's processes reads a cover may, but only at a uri KJB signed ([CoverUris]): a song's id or an
 * opened copy's key and its own signature, nothing else; never a path, never a write. Another app gets the default cover
 * for an opened copy, whose own cover only KJB's surfaces read ([read]). The log gets exception names only.
 */
class CarCovers : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = JPEG

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("covers are read-only")
        val context = context ?: throw FileNotFoundException("not ready")
        val path = uris(context).pathOf(uri.pathSegments) ?: throw FileNotFoundException("not a cover")
        val jpeg = coverFor(path, { id -> embedded(context, id)?.let(::jpeg) }) { defaultJpeg(context) }
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

        /** Covers are at most this size: the notification's and Auto's largest; Auto's grid tiles are smaller. */
        private const val COVER_PX = 512

        /** The default cover as served, made once per process. */
        @Volatile
        private var madeDefault: ByteArray? = null

        /** The provider's authority in this build (the debug build's differs). */
        fun authority(context: Context): String = context.packageName + ".covers"

        @Volatile
        private var made: CoverUris? = null

        /** The covers' uris in this build, under this install's key, at the covers' versions kept between runs. */
        fun uris(context: Context): CoverUris = made ?: run {
            TagsGraph.readCoverVersions(context)
            CoverUris(authority(context), CoverKey.of(context))
        }.also { made = it }

        /**
         * The cover at [uri] for KJB's own surfaces, read in this process at the uri the car reads (the session's bitmap
         * loader for the notification and the lock screen): the song's embedded cover, or an opened copy's
         * ([OpenedFiles.cover]), else the default cover, which a uri KJB did not sign, and a read that failed, also get.
         * No library check: KJB hands out only its own songs' and copies' uris. Blocking.
         */
        fun bitmap(context: Context, uri: Uri): Bitmap = read(context, uri, { it }) { defaultBitmap(context) } ?: defaultBitmap(context)

        /**
         * The cover at [uri] as [bitmap] reads it, for the widgets, which keep it for the song: [own] of the song's
         * embedded cover or the opened copy's; [none] when it has none, or for a uri KJB did not sign; null when it could
         * not be read now ([ownCover]), so they read it again rather than keep the default cover, the error's name
         * logged. Blocking.
         */
        fun <T : Any> read(context: Context, uri: Uri, own: (Bitmap) -> T, none: () -> T): T? =
            ownCover(uris(context).pathOf(uri.pathSegments).orEmpty(), { own(thumbnail(context, it)) }, { opened(context, it)?.let(own) }, ::notRead, none)

        private fun notRead(e: Throwable) = Log.w(LOG_TAG, "cover not read: ${e.javaClass.simpleName}")

        /** The embedded cover of the opened copy [key] names, at most [COVER_PX] square; null when the copy is gone or has none. */
        private fun opened(context: Context, key: String): Bitmap? =
            OpenedFiles.coverCopy(context, key)?.let { OpenedFiles.cover(context, it, COVER_PX) }?.let(::capped)

        /** Song [id]'s embedded cover ([thumbnail]); null without one, or when it cannot be read. */
        private fun embedded(context: Context, id: Long): Bitmap? = try {
            thumbnail(context, id)
        } catch (e: IOException) {
            // No embedded cover, the song is gone, or it could not be read now: the car gets the default cover.
            null
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "cover not read: ${e.javaClass.simpleName}")
            null
        }

        /**
         * MediaStore's thumbnail of song [id]'s embedded art, at most [COVER_PX] square. Throws FileNotFoundException when
         * MediaStore has none for it, another IOException or a SecurityException when it could not be read.
         */
        private fun thumbnail(context: Context, id: Long): Bitmap =
            capped(context.contentResolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), Size(COVER_PX, COVER_PX), null))

        /** MediaStore decodes by a whole sample size, so a thumbnail can come back up to twice as large: scaled down to fit. */
        private fun capped(bitmap: Bitmap): Bitmap {
            val scale = COVER_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            return if (scale >= 1f) bitmap else bitmap.scale((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
        }

        /**
         * [bitmap] as KJB's session keeps and hands it on, made once: immutable, and from Android 12 in shared memory,
         * as Media3 makes every bitmap it passes on (BitmapUtil.makeShared). So a kept cover is never copied again at
         * each update, and nothing drawing into one can change it.
         */
        internal fun sessionCover(bitmap: Bitmap): Bitmap = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> bitmap.asShared()
            bitmap.isMutable -> bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else -> bitmap
        }

        /** KJB's default cover, [COVER_PX] square, made anew at each call. */
        internal fun defaultBitmap(context: Context): Bitmap =
            checkNotNull(ContextCompat.getDrawable(context, R.drawable.kjb_default_cover)).toBitmap(COVER_PX, COVER_PX)

        private fun defaultJpeg(context: Context): ByteArray = madeDefault ?: jpeg(defaultBitmap(context)).also { madeDefault = it }

        private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}
