package app.lyrify.ui

import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Library
import app.lyrify.library.song
import app.lyrify.settings.ArtistSplit
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Go to artist for a song of one artist or several, and hearts kept on a whole tag of several artists. Made-up names only. */
class ArtistNamesTest {
    private val songs = listOf(
        song("Music/a/Tin Roof.mp3", artist = "Kit Calloway, Mira Solen"),
        song("Music/a/Low Water.mp3", artist = "Oren Vale"),
    )
    private val split = Library.of(songs, Settings())
    private val whole = Library.of(songs, Settings(library = LibrarySettings(artistSplit = ArtistSplit(enabled = false))))

    private fun artist(name: String) = FavouriteCollection(CollectionKind.ARTIST, name)

    /** A heart kept from 3.0, or from while the setting was off, on the whole tag. */
    private val duet = artist("Kit Calloway, Mira Solen")

    @Test
    fun `Go to artist opens the only artist at once, asks which of several, and does nothing without one`() {
        val opened = mutableListOf<String>()
        val asked = mutableListOf<List<String>>()
        fun tap(names: List<String>) = artistTap(names, { opened.add(it) }, { asked.add(it) })

        val one = tap(listOf("Kit Calloway"))
        val two = tap(listOf("Kit Calloway", "Mira Solen"))
        assertNull(tap(emptyList()))
        assertNotNull(one)
        assertNotNull(two)
        one?.invoke()
        two?.invoke()

        assertEquals(listOf("Kit Calloway"), opened)
        assertEquals(listOf(listOf("Kit Calloway", "Mira Solen")), asked)
    }

    @Test
    fun `a heart kept on a whole tag stands for each artist it names, while that tag is no artist of its own`() {
        assertEquals(listOf("Kit Calloway", "Mira Solen"), heartedArtists(duet.key, split).map { it.name })
        assertEquals(listOf(duet), artistHearts("Mira Solen", listOf(duet, artist("Oren Vale")), split))
        assertEquals(listOf(artist("Oren Vale")), artistHearts("Oren Vale", listOf(duet, artist("Oren Vale")), split))
        // With the setting off the tag is an artist again, and its heart is its own.
        assertEquals(listOf("Kit Calloway, Mira Solen"), heartedArtists(duet.key, whole).map { it.name })
        assertEquals(emptyList<FavouriteCollection>(), artistHearts("Mira Solen", listOf(duet), whole))
        assertEquals(emptyList<String>(), heartedArtists("Nobody Here", split).map { it.name })
    }

    @Test
    fun `taking an artist's heart off takes the whole tag's off too, and its other artists keep theirs`() {
        val change = artistHeartOff("Mira Solen", listOf(artist("Mira Solen"), duet, artist("Oren Vale")), split)

        assertEquals(listOf(artist("Mira Solen"), duet), change.off)
        assertEquals(listOf(artist("Kit Calloway")), change.on)
        // Kit Calloway's own heart already there: nothing more to add.
        assertEquals(emptyList<FavouriteCollection>(), artistHeartOff("Mira Solen", listOf(duet, artist("Kit Calloway")), split).on)
    }
}
