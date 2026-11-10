package app.lyrify.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The promise and the privacy line (covers-tags-spec, "The promise changes"): KJB changes a song only when you confirm,
 * only its tags and cover, never the music; and what goes to MusicBrainz and where covers come from. Read from the
 * resources and the manifest as they ship.
 */
class PromiseCopyTest {
    private val strings = File("src/main/res/values/strings.xml").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun string(name: String): String =
        checkNotNull(Regex("""<string name="$name">(.*?)</string>""").find(strings)) { "no string $name" }.groupValues[1].replace("\\'", "'")

    @Test
    fun `the onboarding makes the new promise`() {
        val promise = "KJB changes a song file only when you confirm, and only its tags and cover, never the music. You can undo every change."
        assertTrue(string("setup_files_detail"), promise in string("setup_files_detail"))
        assertFalse("the old promise is gone", "never changes your music" in strings)
    }

    @Test
    fun `the privacy line says what goes to MusicBrainz and where covers come from`() {
        val privacy = string("setup_privacy")
        for (part in listOf("MusicBrainz", "album titles", "artists", "song titles", "track counts", "lengths", "Cover Art Archive", "archive.org")) {
            assertTrue("$part: $privacy", part in privacy)
        }
    }

    @Test
    fun `the manifest comments match the promise`() {
        assertFalse("never changes music" in manifest)
        assertTrue("only when the user confirms" in manifest)
        assertTrue("musicbrainz.org" in manifest)
        assertTrue("coverartarchive.org" in manifest)
    }
}
