package app.lyrify.ui

import android.content.Intent
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.LibraryState
import app.lyrify.library.song
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** What other apps and the launcher's shortcuts ask of KJB: audio files to play, or one of four shortcuts. */
class LaunchesTest {
    private val song = "content://com.example.files/document/primary%3AMusic%2FPaper%20Lantern.mp3"
    private val other = "content://media/external/audio/media/42"

    @Test
    fun `a file manager's View plays the file it grants`() {
        assertEquals(Launch.Open(listOf(song)), launchOf(LaunchIntent(Intent.ACTION_VIEW, "audio/mpeg", song)))
    }

    @Test
    fun `a View of anything but a content address is not KJB's to play, a file path least of all`() {
        assertNull("KJB's own access would read it", launchOf(LaunchIntent(Intent.ACTION_VIEW, null, "file:///storage/emulated/0/Music/a.flac")))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_VIEW, "audio/mpeg", "FILE:///storage/emulated/0/Music/a.flac")))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_VIEW, "audio/mpeg", "https://example.com/a.mp3")))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_VIEW, "audio/mpeg", null)))
    }

    @Test
    fun `Share with one audio file plays it, and shared text is the downloads' and not opened here`() {
        assertEquals(Launch.Open(listOf(song)), launchOf(LaunchIntent(Intent.ACTION_SEND, "audio/flac", streams = listOf(song))))
        assertEquals(Launch.Open(listOf(song)), launchOf(LaunchIntent(Intent.ACTION_SEND, "application/ogg", streams = listOf(song))))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_SEND, "text/plain", streams = listOf(song))))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_SEND, "audio/mpeg")))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_SEND, "audio/mpeg", streams = listOf("file:///storage/emulated/0/x.mp3"))))
    }

    @Test
    fun `Share with several files plays them as one queue, each once, in their order`() {
        val sent = LaunchIntent(Intent.ACTION_SEND_MULTIPLE, "audio/*", streams = listOf(song, other, song, "https://example.com/x.mp3", "file:///storage/emulated/0/x.mp3"))
        assertEquals(Launch.Open(listOf(song, other)), launchOf(sent))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_SEND_MULTIPLE, "audio/*")))
        assertNull(launchOf(LaunchIntent(Intent.ACTION_SEND_MULTIPLE, "image/*", streams = listOf(song))))
    }

    @Test
    fun `a share of thousands of files is cut to the most a queue takes at once`() {
        val many = (1..5000).map { "content://media/external/audio/media/$it" }
        val open = launchOf(LaunchIntent(Intent.ACTION_SEND_MULTIPLE, "audio/*", streams = many)) as Launch.Open
        assertEquals(MAX_OPENED, open.uris.size)
        assertEquals(many.take(MAX_OPENED), open.uris)
    }

    @Test
    fun `the launcher icon itself and unknown actions ask for nothing`() {
        assertNull(launchOf(LaunchIntent(Intent.ACTION_MAIN)))
        assertNull(launchOf(LaunchIntent("app.lyrify.action.SOMETHING_ELSE")))
        assertNull(launchOf(LaunchIntent(null)))
    }

    // Shortcuts

    @Test
    fun `each shortcut's action asks for its own thing`() {
        assertEquals(Launch.ShuffleAll, launchOf(LaunchIntent("app.lyrify.action.SHUFFLE_ALL")))
        assertEquals(Launch.Resume, launchOf(LaunchIntent("app.lyrify.action.RESUME")))
        assertEquals(Launch.Search, launchOf(LaunchIntent("app.lyrify.action.SEARCH")))
        assertEquals(Launch.Favourites, launchOf(LaunchIntent("app.lyrify.action.FAVOURITES")))
        assertEquals(Shortcut.entries.size, Shortcut.entries.map { it.action }.toSet().size)
    }

    @Test
    fun `the static shortcuts open KJB's own build at each shortcut's action, with an icon and labels`() {
        for ((folder, target) in listOf("src/main/res/xml" to "app.lyrify", "src/debug/res/xml" to "app.lyrify.debug")) {
            val file = File(folder, "shortcuts.xml")
            assertTrue("no ${file.path}", file.isFile)
            val shortcuts = shortcutsIn(file)
            assertEquals(file.path, Shortcut.entries.map { it.id }, shortcuts.map { it.getAttribute("android:shortcutId") })
            shortcuts.forEach { shortcut ->
                val kind = Shortcut.entries.first { it.id == shortcut.getAttribute("android:shortcutId") }
                val intent = shortcut.getElementsByTagName("intent").item(0) as Element
                assertEquals(kind.action, intent.getAttribute("android:action"))
                assertEquals(target, intent.getAttribute("android:targetPackage"))
                assertEquals("app.lyrify.ui.MainActivity", intent.getAttribute("android:targetClass"))
                assertTrue(shortcut.getAttribute("android:icon").startsWith("@drawable/"))
                assertTrue(shortcut.getAttribute("android:shortcutShortLabel").startsWith("@string/"))
                assertTrue(shortcut.getAttribute("android:shortcutLongLabel").startsWith("@string/"))
                assertTrue(File("src/main/res/drawable", shortcut.getAttribute("android:icon").removePrefix("@drawable/") + ".xml").isFile)
            }
        }
    }

    @Test
    fun `the manifest points the launcher at the shortcuts and takes audio files`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest, "android:resource=\"@xml/shortcuts\"" in manifest)
        assertTrue(manifest, "<action android:name=\"android.intent.action.SEND_MULTIPLE\" />" in manifest)
        assertTrue(manifest, "android:mimeType=\"audio/*\"" in manifest)
        assertFalse("no file addresses", "android:scheme=\"file\"" in manifest)
    }

    // Shuffle all on a cold start

    @Test
    fun `Shuffle all plays the library's songs, and never says there are none while it is still reading`() {
        val songs = Library.of(listOf(song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 1)), Settings())
        assertNull(shuffleAllProblem(LibraryState.Ready(songs)))
        assertEquals(R.string.launch_no_songs, shuffleAllProblem(LibraryState.Ready(Library.of(emptyList(), Settings()))))
        assertEquals(R.string.launch_still_reading, shuffleAllProblem(LibraryState.Loading))
        assertEquals("still reading when the wait ran out", R.string.launch_still_reading, shuffleAllProblem(null))
        assertEquals(R.string.launch_unreadable, shuffleAllProblem(LibraryState.Unreadable))
    }

    private fun shortcutsIn(file: File): List<Element> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val list = root.getElementsByTagName("shortcut")
        return (0 until list.length).map { list.item(it) as Element }
    }
}
