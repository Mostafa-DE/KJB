package app.lyrify.auto

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
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
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.mostPlayed
import app.lyrify.library.recentlyPlayed
import app.lyrify.logFailures
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
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * KJB in the car, and in the system's other media browsers (System UI, Bluetooth): the playback service's browse tree
 * ([LibraryTree]) as media items, search, and voice play. Who may see the library is decided once per connection
 * ([connect], [ControllerAccess]): KJB itself, the system's trusted controllers and the car; any other app gets plain
 * transport only. The root honours Android Auto's limit on root items; lists show as lists and Albums as a grid; a list
 * a client asks for whole is capped, one it pages is not. Lists are built from a snapshot off the main thread and paged
 * before they become media items. The car's covers are granted one by one as it is shown them, and revoked when it
 * disconnects ([CoverGrants]). A song or Shuffle all picked in the car, or a voice request, changes KJB's own queue
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

    /** One thread for the cover grants, outliving the service so a revoke is never dropped. */
    private val grantsThread = Dispatchers.IO.limitedParallelism(1)
    private val grantsScope = CoroutineScope(SupervisorJob() + grantsThread + logFailures)
    private val grants = CoverGrants<MediaSession.ControllerInfo>(
        packageOf = { it.packageName },
        grant = { packageName, id -> CarCovers.grant(context, packageName, id) },
        revoke = { packageName, id -> CarCovers.revoke(context, packageName, id) },
    )

    init {
        // Recent queues follows the queues: a car showing it hears when one comes or goes.
        scope.launch {
            core.book.map { (listOfNotNull(it.currentId) + it.saved + it.recent).distinct() }.distinctUntilChanged().drop(1)
                .collect { session.notifyChildrenChanged(LibraryTree.QUEUES, it.size, null) }
        }
    }

    /** What [controller] may do, decided as it connects (the session's onConnect). */
    fun connect(controller: MediaSession.ControllerInfo): Access {
        val granted = access.of(Caller(controller.packageName, controller.uid, controller.isTrusted))
        connected[controller] = granted
        if (granted == Access.CAR) grantsScope.launch { grants.connected(controller) }
        return granted
    }

    /** [controller] left: a car's cover grants go. */
    fun disconnect(controller: MediaSession.ControllerInfo) {
        if (connected.remove(controller) == Access.CAR) grantsScope.launch { grants.disconnected(controller) }
    }

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
        LibraryResult.ofItemList(items(browser, nodes), params)
    }

    fun item(mediaId: String): LibraryResult<MediaItem> =
        if (LibraryTree.isRoot(mediaId)) LibraryResult.ofItem(rootItem(mediaId), null) else LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)

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
        LibraryResult.ofItemList(items(browser, nodes), params)
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

    /**
     * A snapshot of what the tree is built from, once the queues and the library are read, with [controller]'s app's
     * searches; Most played and Recently played only for the lists that show them.
     */
    private suspend fun data(controller: MediaSession.ControllerInfo, plays: Boolean = false): TreeData {
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
            found = searches.of(controller.packageName),
        ) { queueLabel(context, it) }
    }

    private suspend fun find(query: String, library: Library): Found =
        Found(query, withContext(Dispatchers.Default) { search.songs(query, library, settings.value.library) }, search.source(query))

    private fun needsPlays(id: String): Boolean = id == LibraryTree.PLAYLISTS || id.startsWith(LibraryTree.MOST_PLAYED) || id.startsWith(LibraryTree.RECENTLY_PLAYED)

    /** [nodes] as media items, built off the main thread; a car is granted their covers before it gets them. */
    private suspend fun items(controller: MediaSession.ControllerInfo, nodes: List<TreeNode>): List<MediaItem> {
        val car = connected[controller] == Access.CAR
        val library = settings.value.library
        val items = withContext(Dispatchers.Default) { nodes.map { item(it, library, car) } }
        if (car) withContext(grantsThread) { grants.give(controller, nodes.mapNotNull { it.cover?.ref?.mediaStoreId }) }
        return items
    }

    private fun rootItem(id: String): MediaItem = item(TreeNode(id, context.getString(R.string.app_name)), settings.value.library, car = false)

    /** [node] as a media item: a song with its tags, or a list; its cover ([CarCovers] for a car); its heading and grid as style hints. */
    private fun item(node: TreeNode, library: LibrarySettings, car: Boolean): MediaItem {
        val song = node.song
        val metadata = song?.let { metadataOf(it, library).buildUpon() }
            ?: MediaMetadata.Builder().setTitle(node.title).setSubtitle(node.subtitle)
                .setMediaType(if (node.playable) MediaMetadata.MEDIA_TYPE_MIXED else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        val extras = Bundle()
        node.group?.let { extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        if (node.grid) extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        val cover = node.cover?.ref?.mediaStoreId
        metadata.setIsBrowsable(node.browsable).setIsPlayable(node.playable)
            .setArtworkUri(cover?.let { if (car) CarCovers.uri(context, it) else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) })
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

/**
 * Read grants on single covers for the cars connected: each cover a car is shown is granted to its app once, and a
 * car's go when it disconnects, except those another connection of the same app still shows. Confined to one thread.
 */
class CoverGrants<C : Any>(
    private val packageOf: (C) -> String,
    private val grant: (packageName: String, id: Long) -> Unit,
    private val revoke: (packageName: String, id: Long) -> Unit,
) {
    private val held = HashMap<C, MutableSet<Long>>()

    fun connected(car: C) {
        held.getOrPut(car) { HashSet() }
    }

    /** The covers [ids] for [car]; nothing for a controller not connected as a car. */
    fun give(car: C, ids: Collection<Long>) {
        val mine = held[car] ?: return
        val app = sameApp(car)
        ids.filter { mine.add(it) && it !in app }.distinct().forEach { grant(packageOf(car), it) }
    }

    fun disconnected(car: C) {
        val mine = held.remove(car) ?: return
        val kept = sameApp(car)
        (mine - kept).forEach { revoke(packageOf(car), it) }
    }

    /** The covers other connections of [car]'s app hold. */
    private fun sameApp(car: C): Set<Long> = held.filterKeys { it != car && packageOf(it) == packageOf(car) }.values.flatten().toSet()
}

/** The MediaStore ids of the songs in [library]: the only covers [CarCovers] serves. */
fun coverIds(library: Library): Set<Long> = library.songs.mapNotNull { it.ref.mediaStoreId }.toSet()

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
 * Covers for the car's lists: a song's cover by its MediaStore id only (no paths in the uri), as a JPEG made from
 * MediaStore's thumbnail of the file's embedded art, and only for a song in the library. Android Auto cannot read
 * MediaStore's audio uris (and Media3 hands a browse item's artwork to it as a uri), so the car's items point here. Not
 * exported: each cover is granted to a car as it is shown ([CoverGrants]). Read-only.
 */
class CarCovers : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = JPEG

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("covers are read-only")
        val id = uri.pathSegments.singleOrNull()?.toLongOrNull() ?: throw FileNotFoundException("not a cover")
        val context = context ?: throw FileNotFoundException("not ready")
        val library = (LibraryGraph.repository(context).state.value as? LibraryState.Ready)?.library
        if (!allowed(id, library)) throw FileNotFoundException("not a song in the library")
        val bitmap = try {
            context.contentResolver.loadThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), Size(COVER_PX, COVER_PX), null)
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

        /** The ids of the library last asked about, so a screen of covers reads the library once. */
        @Volatile
        private var index: Pair<Library, Set<Long>>? = null

        /** Whether the cover of MediaStore item [id] may be served: a song in [library]. */
        fun allowed(id: Long, library: Library?): Boolean {
            library ?: return false
            val ids = index?.takeIf { it.first === library }?.second ?: coverIds(library).also { index = library to it }
            return id in ids
        }

        fun uri(context: Context, mediaStoreId: Long): Uri =
            Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(context.packageName + ".covers").appendPath(mediaStoreId.toString()).build()

        /** [packageName] may read the cover of [id], until [revoke] or the phone restarts. */
        fun grant(context: Context, packageName: String, id: Long) {
            try {
                context.grantUriPermission(packageName, uri(context, id), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: RuntimeException) {
                // A car app gone or unknown to the phone: its lists show without that cover.
                Log.w(LOG_TAG, "car cover not granted: ${e.javaClass.simpleName}")
            }
        }

        fun revoke(context: Context, packageName: String, id: Long) {
            context.revokeUriPermission(packageName, uri(context, id), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
