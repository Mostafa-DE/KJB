package app.lyrify.auto

import android.content.ContentValues
import android.media.MediaMetadata
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.Projected
import app.lyrify.player.mediaItemOf
import app.lyrify.settings.LibrarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KJB's covers read by another app, as Android Auto reads them (CR1), on the phone (debug build): the song playing's
 * cover uri, as a media session over the service's media item hands it to the platform session that Android Auto
 * reads (the song playing and the queue), opens for the shell (another uid, given no grant) and gives a JPEG; a uri KJB
 * did not sign, one for another cover, or one reaching for a file opens nothing; a cover never opens for writing and
 * lists nothing; KJB's own surfaces read the same uri in the process. A made-up song without a MediaStore id, so the
 * cover is KJB's default cover; nothing on the phone is read, and nothing plays.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CoverDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val covers = CarCovers.uris(context)

    /** What [command] prints, run as the shell: another app, as far as KJB's provider can tell. */
    private fun asShell(command: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    private fun readAsShell(uri: String): ByteArray = asShell("content read --uri $uri")

    private fun isJpeg(bytes: ByteArray): Boolean = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(timeoutMs: Long, read: () -> T?): T? {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }

    private val song = Song(
        ref = SongRef("/storage/emulated/0/Music/KJB test/Paper Lantern.mp3"),
        title = "Paper Lantern",
        artist = "Kit Calloway",
        album = "Harbour Glass",
        albumArtist = null,
        durationMs = 180_000,
        track = 1,
        disc = null,
        year = null,
        genre = null,
        folder = "Music/KJB test/",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = 1_000,
        mimeType = "audio/mpeg",
    )

    @Test
    fun theSongPlayingsCoverOpensForAnotherAppAtItsOwnUriOnly() {
        val item = mediaItemOf(Projected(1, song), LibrarySettings(), covers)
        assertNull("artwork bytes in the media item", item.mediaMetadata.artworkData)
        // The player is never prepared: nothing plays, and audio focus is never asked for.
        val (player, session) = onMain {
            val player = ExoPlayer.Builder(context).build().apply { setMediaItem(item) }
            player to MediaSession.Builder(context, player).setId("kjb-cover-test").build()
        }
        try {
            // What Android Auto reads: the platform session's song playing and queue, which Media3 fills from the item.
            val platform = android.media.session.MediaController(context, session.platformToken)
            val playing = waitFor(5_000) { platform.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) }
            assertEquals(item.mediaMetadata.artworkUri.toString(), playing)
            assertEquals(playing, platform.metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))
            val queued = waitFor(5_000) { platform.queue?.firstOrNull()?.description?.iconUri?.toString() }
            assertEquals(playing, queued)

            val uri = checkNotNull(item.mediaMetadata.artworkUri)
            assertEquals("content", uri.scheme)
            assertEquals(covers.authority, uri.authority)
            assertFalse("a file's path in $uri", uri.toString().contains("Music") || uri.toString().contains("storage"))

            val read = readAsShell(uri.toString())
            assertTrue("another app could not read the cover (${read.size} bytes)", isJpeg(read) && read.size > 1_000)

            // A uri KJB did not make opens nothing: unsigned, another cover's id with this signature, a path, the key file.
            val signature = checkNotNull(uri.lastPathSegment)
            listOf(
                "content://${covers.authority}/$DEFAULT_COVER",
                "content://${covers.authority}/12345/$signature",
                "content://${covers.authority}/$DEFAULT_COVER/${signature.reversed()}",
                "content://${covers.authority}/..%2Ffiles%2Fcovers.key/$signature",
                "content://${covers.authority}/$DEFAULT_COVER/$signature/extra",
            ).forEach { forged -> assertFalse("$forged opened", isJpeg(readAsShell(forged))) }
        } finally {
            onMain {
                session.release()
                player.release()
            }
        }
    }

    @Test
    fun aCoverIsOnlyEverRead() {
        val uri = covers.of(DEFAULT_COVER)
        listOf("w", "rw", "wa", "rwt").forEach { mode ->
            try {
                context.contentResolver.openFileDescriptor(uri, mode)?.close()
                fail("opened for \"$mode\"")
            } catch (_: SecurityException) {
                // Read-only.
            }
        }
        context.contentResolver.openFileDescriptor(uri, "r").use { assertNotNull(it) }
        // Nothing to list, add, change or remove, for KJB or another app.
        assertNull(context.contentResolver.query(uri, null, null, null, null))
        assertNull(context.contentResolver.insert(uri, ContentValues()))
        assertEquals(0, context.contentResolver.update(uri, ContentValues(), null, null))
        assertEquals(0, context.contentResolver.delete(uri, null, null))
        val listed = asShell("content query --uri $uri").decodeToString()
        assertFalse("the shell listed $listed", "Row:" in listed)
    }

    @Test
    fun kjbsOwnSurfacesReadTheSameUriInTheProcess() {
        // The notification's and lock screen's loader, and the widgets, read the uri the car reads.
        val cover = CarCovers.bitmap(context, covers.of(DEFAULT_COVER))
        assertTrue("${cover.width} x ${cover.height}", cover.width in 1..512 && cover.height == cover.width)
        // A uri KJB did not sign gets the default cover, never another song's, never a crash.
        val forged = CarCovers.bitmap(context, Uri.parse("content://${covers.authority}/12345/${"0".repeat(32)}"))
        assertTrue(forged.sameAs(cover))
    }
}
