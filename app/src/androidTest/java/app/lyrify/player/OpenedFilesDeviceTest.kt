package app.lyrify.player

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.auto.CarCovers
import app.lyrify.auto.coverPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * A file another app opened, as KJB reads its copy on the phone (phone session 10: the player showed the copy's file
 * name, not its tags): its own title, artist, album and cover, read by MediaMetadataRetriever from a copy in KJB's cache;
 * the file name only without a title tag; its cover served to KJB's own session (the notification) by a key, never a path;
 * and nothing read outside the copies. Made-up tag fixtures (app/src/androidTest/assets/tags, tools/make_tag_fixtures.py).
 * Plays nothing and starts no screen.
 */
@RunWith(AndroidJUnit4::class)
class OpenedFilesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    /** A batch of copies of this test's own, as an open makes one ([OpenedFiles]); gone after each test. */
    private val batch = File(context.cacheDir, "opened/${System.currentTimeMillis()}")
    private val outside = File(context.filesDir, "opened-test")

    @After
    fun tearDown() {
        batch.deleteRecursively()
        outside.deleteRecursively()
    }

    private fun fixture(name: String): ByteArray = assets.open("tags/$name").use { it.readBytes() }

    /** [bytes] as the copy at [place] in the batch, under [name]. */
    private fun copy(place: Int, name: String, bytes: ByteArray): File = File(batch, "$place/$name").apply {
        parentFile!!.mkdirs()
        writeBytes(bytes)
    }

    /** [mp3] without its ID3v2 tag (its size is syncsafe, after a 10-byte header): no title, artist, album or cover. */
    private fun untagged(mp3: ByteArray): ByteArray {
        assertEquals("ID3", String(mp3, 0, 3, Charsets.ISO_8859_1))
        val size = (6..9).fold(0) { total, at -> (total shl 7) or (mp3[at].toInt() and 0x7F) }
        return mp3.copyOfRange(10 + size, mp3.size)
    }

    @Test
    fun aCopyIsNamedByItsOwnTagsAndShowsItsOwnCover() {
        val file = copy(0, "Kelp Lighthouse take 2.mp3", fixture("kelp-lighthouse-v24-ape.mp3"))
        val song = OpenedFiles.plainSong(file, readTags = true)
        assertEquals("Kelp Lighthouse", song.title)
        assertEquals("Mira Solen", song.artist)
        assertEquals("Glass Harbour Sessions", song.album)
        assertTrue(song.durationMs > 0)

        // The player's cover (SongCover through the image loader) reads the copy's own front cover, a plain 0xAA6633 square.
        val cover = checkNotNull(OpenedFiles.cover(context, file.path, px = 128)) { "no cover read" }
        assertFront(cover)
        // The notification's: the session asks KJB's cover provider by the copy's key, which names no path, at its signed uri.
        val key = coverPath(song)
        assertEquals(openedCoverKey(file.path), key)
        assertTrue(key, !key.contains("Kelp") && !key.contains('/'))
        assertFront(CarCovers.bitmap(context, CarCovers.uris(context).of(key)))
    }

    @Test
    fun aCopyWithNoTitleTagIsNamedByItsFile() {
        val file = copy(1, "Harbour Note.mp3", untagged(fixture("lanterns-over-kelp-v24.mp3")))
        val song = OpenedFiles.plainSong(file, readTags = true)
        assertEquals("Harbour Note", song.title)
        assertNull(song.artist)
        assertNull(song.album)
        assertNull(OpenedFiles.cover(context, file.path, px = 128))
    }

    @Test
    fun noFileOutsideTheCopiesIsRead() {
        val file = File(outside, "0/0/Kelp Lighthouse.mp3").apply {
            parentFile!!.mkdirs()
            writeBytes(fixture("kelp-lighthouse-v24-ape.mp3"))
        }
        assertNull(OpenedFiles.cover(context, file.path, px = 128))
        assertNull(OpenedFiles.cover(context, File(batch, "../../../files/opened-test/0/0/Kelp Lighthouse.mp3").path, px = 128))
        assertNull(OpenedFiles.coverCopy(context, "opened-0-0"))
    }

    /** The fixture's front cover, 0xAA6633, at the bitmap's centre (JPEG keeps it within a few steps). */
    private fun assertFront(bitmap: android.graphics.Bitmap) {
        val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val near = abs(Color.red(centre) - 0xAA) < 16 && abs(Color.green(centre) - 0x66) < 16 && abs(Color.blue(centre) - 0x33) < 16
        assertTrue("centre ${Integer.toHexString(centre)}", near)
    }
}
