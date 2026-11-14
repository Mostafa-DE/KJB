package app.lyrify.player

import app.lyrify.library.VOLUME
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A file another app opens in KJB: what its address tells, and the library's song it is, when it is one. */
class OpenedFilesTest {
    private val primary = VOLUME.removeSuffix("/")

    @Test
    fun `a file address and the phone's storage documents name the file's path`() {
        assertEquals(OpenedClue(path = VOLUME + "Music/Harbour Glass/01 Paper Lantern.mp3"), clueOf("file://${VOLUME}Music/Harbour%20Glass/01%20Paper%20Lantern.mp3", primary))
        assertEquals(
            OpenedClue(path = VOLUME + "Music/Harbour Glass/01 Paper Lantern.mp3"),
            clueOf("content://com.android.externalstorage.documents/document/primary%3AMusic%2FHarbour%20Glass%2F01%20Paper%20Lantern.mp3", primary),
        )
        assertEquals(
            OpenedClue(path = "/storage/1A2B-3C4D/Music/Tide.flac"),
            clueOf("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMusic/document/1A2B-3C4D%3AMusic%2FTide.flac", primary),
        )
        assertEquals(
            OpenedClue(path = VOLUME + "Download/Tide.mp3"),
            clueOf("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FTide.mp3", primary),
        )
    }

    @Test
    fun `MediaStore's own addresses and its documents name the song's id`() {
        assertEquals(OpenedClue(mediaStoreId = 42), clueOf("content://media/external/audio/media/42", primary))
        assertEquals(OpenedClue(mediaStoreId = 42), clueOf("content://media/external_primary/audio/media/42?x=1", primary))
        assertEquals(OpenedClue(mediaStoreId = 7), clueOf("content://media/external/file/7", primary))
        assertEquals(OpenedClue(mediaStoreId = 42), clueOf("content://com.android.providers.media.documents/document/audio%3A42", primary))
        assertEquals(OpenedClue(mediaStoreId = 9), clueOf("content://com.android.providers.downloads.documents/document/msf%3A9", primary))
    }

    @Test
    fun `another app's address tells nothing by itself`() {
        assertEquals(OpenedClue(), clueOf("content://com.example.mail.attachments/1/2/Paper%20Lantern.mp3", primary))
        assertEquals(OpenedClue(), clueOf("content://media/external/audio/media/notanumber", primary))
        assertEquals("an album's address names no song", OpenedClue(), clueOf("content://media/external/audio/albums/3", primary))
        assertEquals(OpenedClue(), clueOf("not an address", primary))
    }

    @Test
    fun `an opened file is the library's song by its path, then its MediaStore id, then its name and size`() {
        val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 42)
        val tide = song("Music/Tide/Tide.flac", id = 43)
        val songs = listOf(lantern, tide)

        assertEquals(lantern, matchOpened(OpenedClue(path = lantern.path), songs))
        assertEquals(tide, matchOpened(OpenedClue(mediaStoreId = 43), songs))
        assertEquals(tide, matchOpened(OpenedClue(name = "Tide.flac", bytes = tide.sizeBytes), songs))
        assertEquals(lantern, matchOpened(OpenedClue(path = VOLUME + "elsewhere.mp3", mediaStoreId = 42), songs))
    }

    @Test
    fun `a file that is not one song of the library is played as it is`() {
        val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 42)
        val copy = song("Music/Copies/01 Paper Lantern.mp3", id = 44)
        val songs = listOf(lantern, copy)

        assertNull("two songs share the name and size", matchOpened(OpenedClue(name = "01 Paper Lantern.mp3", bytes = lantern.sizeBytes), songs))
        assertNull("another size", matchOpened(OpenedClue(name = "01 Paper Lantern.mp3", bytes = 1), songs))
        assertNull("a name alone", matchOpened(OpenedClue(name = "01 Paper Lantern.mp3"), songs))
        assertNull(matchOpened(OpenedClue(mediaStoreId = 99), songs))
        assertNull(matchOpened(OpenedClue(), songs))
    }
}
