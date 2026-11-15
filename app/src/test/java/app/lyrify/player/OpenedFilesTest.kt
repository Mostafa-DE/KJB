package app.lyrify.player

import app.lyrify.auto.DEFAULT_COVER
import app.lyrify.auto.coverPath
import app.lyrify.library.SongRef
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A file another app opens in KJB: only MediaStore's own address of an audio file names a library song (by its id); any
 * other address is read through the opening app's grant, never as a path with KJB's own access. And the copies of opened
 * files stay while a queue holds them.
 */
class OpenedFilesTest {
    @Test
    fun `MediaStore's address of an audio file names its id`() {
        assertEquals(42L, mediaStoreAudioId("content://media/external/audio/media/42"))
        assertEquals(42L, mediaStoreAudioId("content://media/external_primary/audio/media/42?x=1"))
        assertEquals(7L, mediaStoreAudioId("content://media/1a2b-3c4d/audio/media/7"))
    }

    @Test
    fun `no other address names a song, a path or an id`() {
        assertNull("a file path", mediaStoreAudioId("file:///storage/emulated/0/Music/Paper%20Lantern.mp3"))
        assertNull("the storage documents' path", mediaStoreAudioId("content://com.android.externalstorage.documents/document/primary%3AMusic%2FTide.flac"))
        assertNull("the media documents", mediaStoreAudioId("content://com.android.providers.media.documents/document/audio%3A42"))
        assertNull("a raw download path", mediaStoreAudioId("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FTide.mp3"))
        assertNull("any file in MediaStore", mediaStoreAudioId("content://media/external/file/7"))
        assertNull("an album", mediaStoreAudioId("content://media/external/audio/albums/3"))
        assertNull(mediaStoreAudioId("content://media/external/audio/media/notanumber"))
        assertNull("another app's provider", mediaStoreAudioId("content://com.example.mail.attachments/audio/media/42"))
        assertNull(mediaStoreAudioId("not an address"))
    }

    @Test
    fun `an opened file is the library's song only by its MediaStore id`() {
        val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 42)
        val tide = song("Music/Tide/Tide.flac", id = 43)

        assertEquals(tide, matchOpened(43, listOf(lantern, tide)))
        assertNull(matchOpened(99, listOf(lantern, tide)))
        assertNull(matchOpened(null, listOf(lantern, tide)))
        assertNull("two songs with one id: no guess", matchOpened(42, listOf(lantern, song("Music/Copies/01 Paper Lantern.mp3", id = 42))))
    }

    @Test
    fun `a batch of copies stays while any queue holds one of its files, and the newest always stays`() {
        val root = "/data/user/0/app.lyrify/cache/opened"
        val batches = listOf("1000", "2000", "3000")

        assertEquals("nothing queued: all but the newest go", listOf("1000", "2000"), staleBatches(batches, emptyList(), root))
        assertEquals(
            "the first open still plays while the second copies",
            listOf("2000"),
            staleBatches(batches, listOf("$root/1000/0/Paper Lantern.mp3", "/storage/emulated/0/Music/Tide.flac"), root),
        )
        assertEquals(emptyList<String>(), staleBatches(listOf("1000"), emptyList(), root))
        assertEquals("another folder's path holds nothing", listOf("1000", "2000"), staleBatches(batches, listOf("/data/other/1000/0/a.mp3", "${root}x/1000/0/a.mp3"), root))
    }

    /**
     * The notification shows an opened copy's own cover: KJB's session asks its cover provider for it by a key of the
     * copy's open and place, never its name or path (phone session 10: the cover was KJB's default).
     */
    @Test
    fun `an opened copy's cover is asked for by its open and place, never its name or path`() {
        val root = "/data/user/0/app.lyrify/cache/opened"
        assertEquals("opened-1000-0", openedCoverKey("$root/1000/0/Paper Harbour.mp3"))
        assertEquals("1000/0", openedCoverDir("opened-1000-0"))
        assertEquals("opened-1000-3", coverPath(song("Music/x.mp3").copy(ref = SongRef("$root/1000/3/Paper Harbour.mp3"))))
        // A library song's stays its MediaStore id, any other song's the default.
        assertEquals("42", coverPath(song("Music/Paper Harbour.mp3", id = 42)))
        assertEquals(DEFAULT_COVER, coverPath(song("Music/Paper Harbour.mp3")))
        // Nothing else is a copy, or names one.
        listOf(
            "/storage/emulated/0/Music/Paper Harbour.mp3", "/storage/emulated/0/Music/opened/1000/0/Paper Harbour.mp3", "$root/1000/Paper Harbour.mp3",
            "$root/10a0/0/Paper Harbour.mp3", "$root/1000/x/Paper Harbour.mp3", "$root/1000/0/", "cache/opened/1000/0",
        ).forEach { assertNull(it, openedCoverKey(it)) }
        listOf("opened-1000", "opened-1000-0-1", "opened-..-0", "opened--0", "opened-1000-x", "opened-1000/0", "42", DEFAULT_COVER, "").forEach { assertNull(it, openedCoverDir(it)) }
    }
}
