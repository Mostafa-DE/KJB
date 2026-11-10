package app.lyrify.auto

import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.player.LibraryTree
import app.lyrify.player.PlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The browse tree as a car asks for it, from the real service (debug build, its test folder's library): Android Auto's
 * root hint, the list and grid hints, and a search answered through the session's search callbacks. Plays nothing.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CarBrowseDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val searched = CountDownLatch(1)
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
        }
        return MediaBrowser.Builder(context, token).setListener(listener).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
            .also { browser = it }
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
}
