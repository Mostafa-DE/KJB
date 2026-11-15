package app.lyrify.ui

import android.content.Intent
import android.net.Uri
import android.os.Parcel
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Share on the phone, with no other app launched: the chooser intent each share builds (the files in EXTRA_STREAM and in
 * ClipData with the read grant, which the chooser carries on), 500 songs inside the Binder buffer, and KJB's FileProvider
 * serving a `.lrc` copy from its cache as a file: the copy goes where a real share puts it, the provider's one folder
 * (cache/share/), and only the test's own copy is removed. Made-up names and lines only; nothing goes to shared storage.
 */
@RunWith(AndroidJUnit4::class)
class ShareDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "share-device-test")

    /** The folders this test's copies went to, each its own under cache/share/ ([lrcCopy]). */
    private val copies = mutableListOf<File>()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        copies.forEach(File::deleteRecursively)
    }

    private fun song(id: Long, type: String?, title: String) = SharedSong("content://media/external/audio/media/$id", type, title)

    private val several: (Int) -> String = { "$it songs" }

    /** The share intent inside the chooser [chooser]. */
    private fun target(chooser: Intent): Intent {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)) { "no share intent in the chooser" }
    }

    private fun clipUris(intent: Intent): List<Uri> = intent.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }.orEmpty()

    @Test
    fun oneSongGoesAsSendWithItsFileTheGrantAndItsTitle() {
        val chooser = shareSheet(songsShare(listOf(song(11, "audio/mpeg", "Paper Lantern")), several))
        val send = target(chooser)
        val uri = Uri.parse("content://media/external/audio/media/11")
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("audio/mpeg", send.type)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(listOf(uri), clipUris(send))
        assertEquals("Paper Lantern", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // The chooser takes the grant and the files along to the app the user picks (Intent.createChooser).
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(listOf(uri), clipUris(chooser))
    }

    @Test
    fun severalSongsOfMixedTypesGoAsSendMultipleOfAnyAudio() {
        val songs = listOf(song(12, "audio/mpeg", "Tide Bell"), song(13, "audio/flac", "Low Water"), song(11, "audio/mpeg", "Paper Lantern"))
        val chooser = shareSheet(songsShare(songs, several))
        val send = target(chooser)
        val uris = songs.map { Uri.parse(it.uri) }
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("audio/*", send.type)
        assertEquals(uris, IntentCompat.getParcelableArrayListExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uris, clipUris(send))
        assertEquals("3 songs", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uris, clipUris(chooser))
    }

    @Test
    fun fiveHundredSongsStayWellInsideTheBinderBuffer() {
        val songs = (1..SHARE_SONGS_MAX).map { song(1_000_000L + it, "audio/mpeg", "Made-up song $it") }
        val parcel = Parcel.obtain()
        try {
            shareSheet(songsShare(songs, several)).writeToParcel(parcel, 0)
            // The Binder buffer is 1 MB for all of the app's calls in progress; one share keeps to a quarter of it.
            assertTrue("${parcel.dataSize()} bytes", parcel.dataSize() < 256 * 1024)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun lyricsAsTextGoAsPlainTextWithoutAFileOrAGrant() {
        val text = lyricsText("Paper Lantern", "Harbour Glass", "[00:01.00]<00:01.00>First <00:01.40>made-up line\n")
        val send = target(shareSheet(lyricsTextShare("Paper Lantern", checkNotNull(text) { "no words" })))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Paper Lantern · Harbour Glass\n\nFirst made-up line", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Paper Lantern", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Paper Lantern", send.getStringExtra(Intent.EXTRA_TITLE))
        assertNull(send.clipData)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun aCrashReportGoesAsPlainTextWithKjbsSubjectAndNoFile() {
        val report = "java.lang.IllegalStateException\n\tat app.lyrify.Made.up(Made.kt:1)"
        val send = target(shareSheet(crashReportShare(report)))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(report, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("KJB crash report", send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(send.clipData)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun theLyricsFileIsServedFromTheCacheAsAFileWithItsOwnName() {
        dir.mkdirs()
        val bytes = "[00:01.000]First made-up line\n[00:04.000]Second made-up line\n".toByteArray()
        File(dir, "01 Paper Lantern.lrc").writeBytes(bytes)
        val copy = checkNotNull(lrcCopy(context.cacheDir, File(dir, "01 Paper Lantern.mp3").path, System.currentTimeMillis())) { "no copy" }
        copies += copy.parentFile!!
        assertEquals("01 Paper Lantern.lrc", copy.name)
        assertEquals(File(context.cacheDir, "share").canonicalPath, copy.parentFile!!.parentFile!!.canonicalPath)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", copy)
        assertEquals(LRC_TYPE, context.contentResolver.getType(uri))
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })

        val send = target(shareSheet(lyricsFileShare(uri.toString(), "Paper Lantern")))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(LRC_TYPE, send.type)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(listOf(uri), clipUris(send))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun plainLyricsHaveNoFileToShare() {
        dir.mkdirs()
        File(dir, "02 Tide Bell.lrc").writeText("First made-up line\nSecond made-up line\n")
        assertNull(lrcCopy(context.cacheDir, File(dir, "02 Tide Bell.mp3").path, System.currentTimeMillis()))
        assertNull(lrcCopy(context.cacheDir, File(dir, "03 Low Water.mp3").path, System.currentTimeMillis()))
    }
}
