package app.lyrify.auto

import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.Projected
import app.lyrify.player.mediaItemOf
import app.lyrify.settings.LibrarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KJB's covers read by another app, as Android Auto reads them (CR1), on the phone (debug build): the song playing's
 * cover uri, as the service hands it to the session, opens for the shell (another uid, given no grant) and gives a
 * JPEG; a uri KJB did not sign, one for another cover, or one reaching for a file opens nothing; and a cover never opens
 * for writing. A made-up song without a MediaStore id, so the cover is KJB's default cover; nothing on the phone is read.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CoverDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val covers = CarCovers.uris(context)

    /** What `content read` prints for [uri], run as the shell: another app, as far as KJB's provider can tell. */
    private fun readAsShell(uri: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("content read --uri $uri")).use { it.readBytes() }

    private fun isJpeg(bytes: ByteArray): Boolean = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

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
        val metadata = mediaItemOf(Projected(1, song), LibrarySettings(), covers).mediaMetadata
        assertNull("artwork bytes in the media item", metadata.artworkData)
        val uri = checkNotNull(metadata.artworkUri)
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
    }

    @Test
    fun aCoverNeverOpensForWriting() {
        val uri = covers.of(DEFAULT_COVER)
        listOf("w", "rw", "wa", "rwt").forEach { mode ->
            try {
                context.contentResolver.openFileDescriptor(uri, mode)?.close()
                fail("opened for \"$mode\"")
            } catch (_: SecurityException) {
                // Read-only.
            }
        }
        context.contentResolver.openFileDescriptor(uri, "r").use { assertTrue(it != null) }
    }
}
