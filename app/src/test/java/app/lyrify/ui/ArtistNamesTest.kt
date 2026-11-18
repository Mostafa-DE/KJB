package app.lyrify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Go to artist for a song of one artist or several. Made-up names only. */
class ArtistNamesTest {
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
}
