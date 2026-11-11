package app.lyrify.tags

import app.lyrify.library.Library
import app.lyrify.library.song
import app.lyrify.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * "2 may be the same": names that look like spellings of one name (the same once normalised, or one or two letters
 * apart, never editions), the pairs the Artists and Albums tabs suggest, the Merge list's order, and the pairs kept
 * apart. Made-up names only.
 */
class SameNamesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `names the same once case, accents, punctuation, The and the ampersand are set aside look alike`() {
        assertTrue(SameNames.lookAlike("Kit Calloway", "kit calloway"))
        assertTrue(SameNames.lookAlike("Inès Corvo", "Ines Corvo"))
        assertTrue(SameNames.lookAlike("The Glass Harbour", "Glass Harbour"))
        assertTrue(SameNames.lookAlike("Juniper & Coil", "Juniper and Coil"))
        assertTrue(SameNames.lookAlike("Kit Calloway.", "Kit-Calloway"))
        assertTrue(SameNames.lookAlike("Mira", "MIRA"))
        assertEquals(SameNames.key("The Glass Harbour"), SameNames.key("glass harbour!"))
    }

    @Test
    fun `names one or two letters apart look alike, three apart or short ones do not`() {
        assertTrue(SameNames.lookAlike("Kit Calloway", "Kit Caloway"))
        assertTrue(SameNames.lookAlike("Kit Calloway", "Kat Callowey"))
        assertFalse(SameNames.lookAlike("Kit Calloway", "Kat Kallowey"))
        // Short names one letter apart are mostly other names.
        assertFalse(SameNames.lookAlike("Mira", "Kira"))
        assertFalse(SameNames.lookAlike("Hollis Brandt", "Ines Corvo"))
    }

    @Test
    fun `editions are never suggested`() {
        assertTrue(SameNames.isEdition("Harbour Glass (Deluxe)"))
        assertTrue(SameNames.isEdition("Harbour Glass Live"))
        assertTrue(SameNames.isEdition("Harbour Glass (2019 Remaster)"))
        assertTrue(SameNames.isEdition("Harbour Glass - Remastered"))
        assertFalse(SameNames.isEdition("Harbour Glass"))
        assertFalse(SameNames.isEdition("Oliver Lantern"))
        assertFalse(SameNames.lookAlike("Harbour Glass", "Harbour Glass (Deluxe)"))
        assertFalse(SameNames.lookAlike("Harbour Glass (Remaster)", "Harbour Glass (Remastered)"))
        assertFalse(SameNames.lookAlike("Harbour Glass Live", "Harbour Glass live"))
    }

    private val library = Library.of(
        listOf(
            song("Music/a/1.mp3", artist = "Kit Calloway", album = "Switchyard Lullabies", albumArtist = "Kit Calloway"),
            song("Music/a/2.mp3", artist = "Kit Calloway", album = "Switchyard Lullabies", albumArtist = "Kit Calloway"),
            song("Music/a/3.mp3", artist = "Kit Calloway", album = "Switchyard Lullabys", albumArtist = "Kit Calloway"),
            song("Music/a/4.mp3", artist = "Kit Caloway", album = "Switchyard Lullabies (Deluxe)", albumArtist = "Kit Calloway"),
            song("Music/b/1.mp3", artist = "Kit Calloway feat. Mira Okafor", album = "Porch Wire", albumArtist = "Mira Okafor"),
            song("Music/b/2.mp3", artist = "Mira Okafor", album = "Porchwire", albumArtist = "Hollis Brandt"),
            song("Music/c/1.mp3", artist = "Hollis Brandt", album = "Ferry Bell", albumArtist = "Hollis Brandt"),
            song("Music/c/2.mp3", artist = "hollis brandt", album = "Ferry Bells", albumArtist = "Hollis Brandt"),
        ),
        Settings(),
    )

    @Test
    fun `the Artists tab suggests artists that look alike, the one with more songs first`() {
        val pairs = SameNames.artists(library.artists, emptySet())

        assertEquals(listOf("Hollis Brandt" to "hollis brandt", "Kit Calloway" to "Kit Caloway"), pairs.map { (a, b) -> a.name to b.name })
    }

    @Test
    fun `the Albums tab suggests albums by the same album artist only, never an edition`() {
        val pairs = SameNames.albums(library.albums, emptySet())

        // "Porch Wire" and "Porchwire" have other album artists; the Deluxe edition is left out.
        assertEquals(listOf("Ferry Bell" to "Ferry Bells", "Switchyard Lullabies" to "Switchyard Lullabys"), pairs.map { (a, b) -> a.title to b.title })
    }

    @Test
    fun `pairs kept apart are not suggested again, whichever way round`() {
        val apart = setOf(NamePair.of(NameKind.ARTIST, "Kit Caloway", "Kit Calloway"), NamePair.of(NameKind.ALBUM, "x", "y"))

        assertEquals(listOf("Hollis Brandt" to "hollis brandt"), SameNames.artists(library.artists, apart).map { (a, b) -> a.name to b.name })
        assertEquals(NamePair(NameKind.ARTIST, "Kit Calloway", "Kit Caloway"), NamePair.of(NameKind.ARTIST, "Kit Caloway", "Kit Calloway"))
        val album = library.albums.first { it.title == "Ferry Bell" }
        val other = library.albums.first { it.title == "Ferry Bells" }
        val kept = setOf(NamePair.of(NameKind.ALBUM, other.key, album.key))
        assertEquals(listOf("Switchyard Lullabies"), SameNames.albums(library.albums, kept).map { it.first.title })
    }

    @Test
    fun `Merge with lists similar spellings first, then the rest, each by name`() {
        assertEquals(
            listOf("kit calloway", "Kit Caloway", "Hollis Brandt", "Mira Okafor"),
            SameNames.similarFirst("Kit Calloway", listOf("Mira Okafor", "kit calloway", "Hollis Brandt", "Kit Caloway")),
        )
    }

    @Test
    fun `a restore's names kept apart are stored in one write, or not at all`() {
        val io = Dispatchers.Unconfined
        val pairs = setOf(NamePair.of(NameKind.ARTIST, "Kit Calloway", "Kit Caloway"), NamePair.of(NameKind.ALBUM, "Harbour Glass", "Harbor Glass"))
        val file = File(folder.root, "same-names.json")
        // Its folder is a file: nothing can be stored.
        val broken = File(folder.root, "not-a-folder").apply { writeText("") }

        runBlocking {
            assertTrue(KeptApart(file, io = io).addAll(pairs))
            assertEquals(pairs, KeptApart(file, io = io).load())
            val failing = KeptApart(File(broken, "same-names.json"), io = io)
            assertFalse(failing.addAll(pairs))
            assertEquals(emptySet<NamePair>(), failing.pairs.value)
        }
    }

    @Test
    fun `kept-apart pairs are stored, read back, and an unreadable item is left out`() {
        val file = File(folder.root, "same-names.json")
        val io = Dispatchers.Unconfined
        val pair = NamePair.of(NameKind.ARTIST, "Kit Calloway", "Kit Caloway")

        runBlocking {
            val store = KeptApart(file, io = io)
            assertEquals(emptySet<NamePair>(), store.load())
            assertTrue(store.add(pair))
            assertEquals(setOf(pair), store.pairs.value)
            assertEquals(setOf(pair), KeptApart(file, io = io).load())
        }

        file.writeText("""{"version":1,"apart":[{"kind":"ARTIST","a":"Hollis Brandt","b":"hollis brandt"},{"kind":"SONG","a":"x","b":"y"},7]}""")
        val logs = mutableListOf<String>()
        val read = runBlocking { KeptApart(file, logs::add, io).load() }
        assertEquals(setOf(NamePair(NameKind.ARTIST, "Hollis Brandt", "hollis brandt")), read)
        assertEquals(listOf("same names: 2 stored items unreadable, left out"), logs)

        file.writeText("not json")
        assertEquals(emptySet<NamePair>(), runBlocking { KeptApart(file, logs::add, io).load() })
    }
}
