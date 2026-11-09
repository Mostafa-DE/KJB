package app.lyrify.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Finding a song again from what queues, playlists, m3u files and backups kept of it. Made-up names only. */
class SongResolverTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", id = 11, durationMs = 201_000)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", id = 12, durationMs = 180_000)
    private val ferry = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", id = 13, durationMs = 240_000)
    private val resolver = SongResolver(listOf(lantern, bell, ferry))

    private val all: Set<FindBy> = FindBy.entries.toSet()

    @Test
    fun `the path comes first, before an id hint that names another song`() {
        assertEquals(lantern, resolver.find(SongClue(path = lantern.path, mediaStoreId = 12), all))
        assertEquals(lantern, resolver.find(lantern.ref))
    }

    @Test
    fun `the id hint finds a song renamed in its folder, or moved with its name`() {
        val renamed = SongRef(VOLUME + "Music/Harbour Glass/old name.mp3", 11)
        val moved = SongRef(VOLUME + "Music/Old/01 Paper Lantern.mp3", 11)

        assertEquals(lantern, resolver.find(renamed))
        assertEquals(lantern, resolver.find(moved))
    }

    @Test
    fun `an id hint naming a song with another name in another folder is not taken, as after MediaStore rebuilt its ids`() {
        assertNull(resolver.find(SongRef(VOLUME + "Music/Elsewhere/something else.mp3", 11)))
        assertNull(resolver.find(SongRef(VOLUME + "Music/Gone/x.mp3", 99)))
    }

    @Test
    fun `the id hint stays on its volume, so the same id on the phone and on a card names two songs`() {
        val phone = song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 5)
        val card = phone.copy(ref = SongRef("$CARD/Music/Night Ferry/01 Paper Lantern.mp3", 5))
        val resolver = SongResolver(listOf(phone, card))

        assertEquals(phone, resolver.find(SongRef(VOLUME + "Music/Old/01 Paper Lantern.mp3", 5)))
        assertEquals(card, resolver.find(SongRef("$CARD/Music/Old/01 Paper Lantern.mp3", 5)))
        assertNull(resolver.find(SongRef("/storage/9999-0000/Music/Old/01 Paper Lantern.mp3", 5)))
        // Without a path there is no volume to look on.
        assertNull(resolver.find(SongClue(mediaStoreId = 5), SongResolver.IN_APP))
    }

    @Test
    fun `an id MediaStore gave out again after a rebuild never finds a song on another volume or with another name`() {
        val onCard = song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 7).let { it.copy(ref = SongRef("$CARD/Music/Harbour Glass/01 Paper Lantern.mp3", 7)) }
        val renumbered = song("Music/Night Ferry/04 Low Water.mp3", id = 8)
        val resolver = SongResolver(listOf(onCard, renumbered))

        // Same folder and file name, but the id now belongs to a song on the card.
        assertNull(resolver.find(SongRef(VOLUME + "Music/Harbour Glass/01 Paper Lantern.mp3", 7)))
        // Same volume, but another name in another folder.
        assertNull(resolver.find(SongRef(VOLUME + "Music/Gone/01 Old Song.mp3", 8)))
        // A path in no volume MediaStore gives.
        assertNull(resolver.find(SongRef("/sdcard/Music/Night Ferry/04 Low Water.mp3", 8)))
    }

    @Test
    fun `file names compare the same in any phone language`() {
        val iris = song("Music/Indigo/01 Iris.mp3", id = 41)
        val before = Locale.getDefault()
        try {
            // Turkish lower-cases "I" to a dotless "ı", so "IRIS" would not meet "Iris" there.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(iris, SongResolver(listOf(iris)).find(SongClue(path = "/x/INDIGO/01 IRIS.MP3"), SongResolver.M3U))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `a song is found by its file name in a folder of the same name, anywhere, whatever the case or separators`() {
        assertEquals(lantern, resolver.find(SongClue(path = "/old/phone/Music/Harbour Glass/01 Paper Lantern.mp3"), SongResolver.M3U))
        assertEquals(lantern, resolver.find(SongClue(path = "C:\\Users\\Kit\\harbour glass\\01 PAPER LANTERN.MP3"), SongResolver.M3U))
        // The name alone is not enough.
        assertNull(resolver.find(SongClue(path = "01 Paper Lantern.mp3"), SongResolver.M3U))
        assertNull(resolver.find(SongClue(path = "/x/Other Folder/01 Paper Lantern.mp3"), SongResolver.M3U))
    }

    @Test
    fun `a song is found by title, artist and a length within 2 s, case, accents and punctuation aside`() {
        val clue = SongClue(title = "paper lantern!", artist = "KIT CALLOWAY", durationMs = 199_000)

        assertEquals(lantern, resolver.find(clue, SongResolver.BACKUP))
        assertEquals(lantern, resolver.find(clue.copy(durationMs = 203_000), SongResolver.BACKUP))
        assertNull(resolver.find(clue.copy(durationMs = 203_001), SongResolver.BACKUP))
        assertNull(resolver.find(clue.copy(artist = "Mira Solen"), SongResolver.BACKUP))
        assertNull(resolver.find(clue.copy(durationMs = null), SongResolver.BACKUP))
        assertNull(resolver.find(clue.copy(artist = null), SongResolver.BACKUP))
    }

    @Test
    fun `each caller uses only its own steps`() {
        val moved = SongClue(path = VOLUME + "Music/Old/01 Paper Lantern.mp3", mediaStoreId = 11)
        val inOtherFolder = SongClue(path = "/x/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", durationMs = 201_000)

        // m3u and backups come from another phone or time: no id hints.
        assertNull(resolver.find(moved.copy(path = VOLUME + "Music/Old/renamed.mp3"), SongResolver.M3U))
        // Backups: the path, then title, artist and length; never the file name.
        assertEquals(lantern, resolver.find(inOtherFolder, SongResolver.BACKUP))
        assertNull(resolver.find(inOtherFolder.copy(title = null), SongResolver.BACKUP))
        // In-app data: the path and the id hint only.
        assertNull(resolver.find(inOtherFolder.copy(mediaStoreId = null), SongResolver.IN_APP))
    }

    @Test
    fun `the file name in its folder comes before the tags`() {
        val copy = song("Music/Copies/Paper Lantern.mp3", title = "Paper Lantern", id = 21, durationMs = 201_000)
        val resolver = SongResolver(listOf(lantern, copy))
        val clue = SongClue(path = "/x/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", durationMs = 201_000)

        assertEquals(lantern, resolver.find(clue, SongResolver.M3U))
    }

    @Test
    fun `a step that matches several songs finds none and leaves it to the next step`() {
        val twin = song("Music/Other/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", id = 31, durationMs = 150_000)
        val resolver = SongResolver(listOf(lantern, twin))
        val clue = SongClue(path = "/x/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", durationMs = 150_500)

        assertEquals(twin, resolver.find(clue, SongResolver.M3U))
        assertNull(resolver.find(clue.copy(durationMs = null), SongResolver.M3U))
        // Two copies with the same tags and length: neither is taken.
        assertNull(SongResolver(listOf(lantern, lantern.copy(ref = SongRef(VOLUME + "Music/B/x.mp3")))).find(SongClue(title = "Paper Lantern", artist = "Kit Calloway", durationMs = 201_000), SongResolver.BACKUP))
    }

    private companion object {
        /** A memory card, as MediaStore's paths name it. */
        const val CARD: String = "/storage/1A2B-3C4D"
    }
}
