package app.lyrify.auto

import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.LibraryResult
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.player.COMMAND_FAVOURITE
import app.lyrify.player.LibraryTree
import app.lyrify.player.PlaybackService
import app.lyrify.tags.CoverVersions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The browse tree as a car asks for it, from the real service (debug build, its test folder's library): Android Auto's
 * root hint, the list and grid hints, a search answered through the session's search callbacks, and a list subscribed to
 * hearing that a cover in it changed; and what KJB's own controller is offered as it connects. Plays nothing.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CarBrowseDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val searched = CountDownLatch(1)

    /** The lists the browser heard changed, in order. */
    private val changed = LinkedBlockingQueue<String>()
    private var browser: MediaBrowser? = null

    @After
    fun stop() {
        instrumentation.runOnMainSync { browser?.release() }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun connect(): MediaBrowser {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val listener = object : MediaBrowser.Listener {
            override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: LibraryParams?) = searched.countDown()

            override fun onChildrenChanged(browser: MediaBrowser, parentId: String, itemCount: Int, params: LibraryParams?) {
                changed += parentId
            }
        }
        return MediaBrowser.Builder(context, token).setListener(listener).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
            .also { browser = it }
    }

    /** This process is KJB's own controller (the debug build's package): every command, the library and the player's own buttons. */
    @Test
    fun kjbsOwnControllerMayPlayBrowseAndUseTheButtons() {
        val browser = connect()
        val player = onMain { browser.availableCommands }
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .forEach { assertTrue("missing player command $it", player.contains(it)) }
        assertTrue(onMain { browser.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT) })
        assertTrue(onMain { browser.isSessionCommandAvailable(SessionCommand(COMMAND_FAVOURITE, Bundle.EMPTY)) })
    }

    @Test
    fun autoRootHintLeavesSongsOutAndTheRootCarriesListHints() {
        val browser = connect()
        val hint = LibraryParams.Builder().setExtras(Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 4) }).build()
        val root = onMain { browser.getLibraryRoot(hint) }.get(10, TimeUnit.SECONDS)
        val id = root.value!!.mediaId
        assertEquals(LibraryTree.root(4), id)
        val styles = root.params!!.extras
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, styles.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM, styles.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
        val tabs = onMain { browser.getChildren(id, 0, Int.MAX_VALUE, null) }.get(10, TimeUnit.SECONDS).value!!
        assertEquals(listOf(LibraryTree.QUEUES, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS), tabs.map { it.mediaId })
        val albums = tabs.single { it.mediaId == LibraryTree.ALBUMS }.mediaMetadata.extras!!
        assertEquals(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM, albums.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
    }

    @Test
    fun aSearchIsAnsweredThroughTheSessionAndItsResultsArePlayable() {
        val browser = connect()
        onMain { browser.search("a", null) }.get(10, TimeUnit.SECONDS)
        assertTrue("no search result came", searched.await(10, TimeUnit.SECONDS))
        val results = onMain { browser.getSearchResult("a", 0, 50, null) }.get(10, TimeUnit.SECONDS).value!!
        assertTrue(results.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })
        assertTrue(results.all { LibraryTree.playable(it.mediaId) })
    }

    /** FX3: an album a car shows stays subscribed, hears when a cover in it changes, and reads it again at the new uri. */
    @Test
    fun aListSubscribedToHearsWhenACoverInItChangesAndReadsTheNewUri() {
        val library = (runBlocking { LibraryGraph.repository(context).state.first { it !is LibraryState.Loading } } as? LibraryState.Ready)?.library
        val album = library?.albums?.firstOrNull()
        assumeTrue("Music/KJB test needs an album", album != null)
        val browser = connect()
        fun children(id: String) = onMain { browser.getChildren(id, 0, 50, null) }.get(10, TimeUnit.SECONDS).value!!
        val id = children(LibraryTree.ALBUMS).first { it.mediaMetadata.title == album!!.title }.mediaId
        val before = children(id).first().mediaMetadata.artworkUri

        assertEquals(LibraryResult.RESULT_SUCCESS, onMain { browser.subscribe(id, null) }.get(10, TimeUnit.SECONDS).resultCode)
        // As Media3's own subscribe does: the browser hears at once that the list is there.
        assertEquals(id, changed.poll(10, TimeUnit.SECONDS))
        CoverVersions.bump(listOf(album!!.songs.first().path))
        assertEquals("the album never heard its cover changed", id, changed.poll(10, TimeUnit.SECONDS))
        assertNotEquals(before, children(id).first().mediaMetadata.artworkUri)
    }
}
