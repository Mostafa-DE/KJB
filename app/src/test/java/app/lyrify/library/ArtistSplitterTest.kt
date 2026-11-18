package app.lyrify.library

import app.lyrify.settings.ArtistSplit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Artists with several names": which artists a tag names, by the default separators and exceptions and the user's own.
 * Made-up names only, apart from the two default exceptions.
 */
class ArtistSplitterTest {
    private val split = ArtistSplitter(ArtistSplit())

    private fun of(tag: String?, rules: ArtistSplit = ArtistSplit()): List<String> = ArtistSplitter(rules).names(tag)

    @Test
    fun `each default separator splits a tag, the names in its order and as spelled`() {
        val two = listOf("Kit Calloway", "Mira Solen")
        listOf("Kit Calloway; Mira Solen", "Kit Calloway/Mira Solen", "Kit Calloway, Mira Solen", "Kit Calloway feat. Mira Solen", "Kit Calloway ft. Mira Solen", "Kit Calloway featuring Mira Solen")
            .forEach { assertEquals(it, two, split.names(it)) }
        assertEquals(listOf("Oren Vale", "Kit Calloway", "Mira Solen"), split.names("Oren Vale, Kit Calloway, Mira Solen"))
        assertEquals(listOf("Oren Vale", "Kit Calloway", "Mira Solen"), split.names("Oren Vale;Kit Calloway / Mira Solen"))
    }

    @Test
    fun `feat words split in any case, with or without their dot, and in brackets`() {
        val two = listOf("Kit Calloway", "Mira Solen")
        listOf(
            "Kit Calloway FEAT. Mira Solen", "Kit Calloway Feat Mira Solen", "Kit Calloway ft Mira Solen", "Kit Calloway FT. Mira Solen",
            "Kit Calloway Featuring Mira Solen", "Kit Calloway (feat. Mira Solen)", "Kit Calloway [ft. Mira Solen]", "Kit Calloway (Featuring Mira Solen)",
            "Kit Calloway(feat Mira Solen)",
        ).forEach { assertEquals(it, two, split.names(it)) }
        assertEquals(listOf("Kit Calloway", "Mira Solen", "Oren Vale"), split.names("Kit Calloway (feat. Mira Solen, Oren Vale)"))
        assertEquals(listOf("Kit Calloway", "Mira Solen", "Oren Vale"), split.names("Kit Calloway (feat. Mira Solen) [ft. Oren Vale]"))
    }

    @Test
    fun `feat words split only as whole words`() {
        listOf("Featherwick", "The Feats of Loftridge", "Craftsmen of Ftora", "Kit Calloway Featurings").forEach { assertEquals(it, listOf(it), split.names(it)) }
    }

    @Test
    fun `and, with, an ampersand or an x never split by default`() {
        listOf("Rowan & Fennick", "Kit Calloway x Mira Solen", "Kit Calloway X Mira Solen", "Kit Calloway and Mira Solen", "Kit Calloway with Mira Solen", "Kit Calloway + Mira Solen")
            .forEach { assertEquals(it, listOf(it), split.names(it)) }
        assertEquals(listOf("Rowan & Fennick", "Mira Solen"), split.names("Rowan & Fennick feat. Mira Solen"))
    }

    @Test
    fun `the default exceptions never split, alone or inside a longer tag, in any case`() {
        assertEquals(listOf("AC/DC"), split.names("AC/DC"))
        assertEquals(listOf("ac/dc"), split.names("ac/dc"))
        assertEquals(listOf("Tyler, The Creator"), split.names("Tyler, The Creator"))
        assertEquals(listOf("tyler, the creator", "Mira Solen"), split.names("tyler, the creator feat. Mira Solen"))
        assertEquals(listOf("AC/DC", "Kit Calloway"), split.names("AC/DC; Kit Calloway"))
        assertEquals(listOf("Kit Calloway", "Tyler, The Creator", "AC/DC"), split.names("Kit Calloway, Tyler, The Creator/AC/DC"))
    }

    @Test
    fun `an exception is a whole name, never part of a longer word`() {
        val rules = ArtistSplit(exceptions = listOf("Kit, Cal"))

        assertEquals(listOf("Kit, Cal", "Mira Solen"), of("Kit, Cal; Mira Solen", rules))
        assertEquals(listOf("Kit", "Calloway"), of("Kit, Calloway", rules))
    }

    @Test
    fun `the user's own separators split as the defaults do, and their exceptions keep a name whole`() {
        val rules = ArtistSplit(separators = ArtistSplit.DEFAULT_SEPARATORS + listOf("&", "with", "vs."), exceptions = ArtistSplit.DEFAULT_EXCEPTIONS + "Rowan & Fennick")

        assertEquals(listOf("Kit Calloway", "Mira Solen"), of("Kit Calloway & Mira Solen", rules))
        assertEquals(listOf("Rowan & Fennick", "Oren Vale"), of("Rowan & Fennick & Oren Vale", rules))
        assertEquals(listOf("Kit Calloway", "Mira Solen"), of("Kit Calloway WITH Mira Solen", rules))
        assertEquals(listOf("Kit Calloway", "Mira Solen"), of("Kit Calloway (with Mira Solen)", rules))
        assertEquals(listOf("Kit Calloway", "Mira Solen"), of("Kit Calloway vs Mira Solen", rules))
        assertEquals(listOf("Withering Oaks"), of("Withering Oaks", rules))
    }

    @Test
    fun `names are trimmed, blanks dropped, each kept once in any case as first spelled`() {
        assertEquals(listOf("Kit Calloway", "mira solen"), split.names("  Kit Calloway ;; mira solen; Mira Solen ;  "))
        assertEquals(listOf("Mira Solen"), split.names("feat. Mira Solen"))
    }

    @Test
    fun `no tag or a blank one names no one, and a tag of separators only is one name`() {
        assertEquals(emptyList<String>(), split.names(null))
        assertEquals(emptyList<String>(), split.names("   "))
        assertEquals(listOf(";"), split.names(" ; "))
    }

    @Test
    fun `the last name typed starts after the last separator and its spaces`() {
        assertEquals(0, split.lastNameStart("Kit Callo"))
        assertEquals("Kit Calloway feat. ".length, split.lastNameStart("Kit Calloway feat. Mi"))
        assertEquals("Kit Calloway;".length, split.lastNameStart("Kit Calloway;Mi"))
        assertEquals("Kit Calloway, ".length, split.lastNameStart("Kit Calloway, "))
        assertEquals("Kit Calloway (feat. Mira Solen)".length, split.lastNameStart("Kit Calloway (feat. Mira Solen)"))
        // A comma inside an exception is part of its name.
        assertEquals(0, split.lastNameStart("Tyler, The Creator"))
        assertEquals(0, ArtistSplitter(ArtistSplit(enabled = false)).lastNameStart("Kit Calloway; Mi"))
    }

    @Test
    fun `with the setting off, or no separators, a tag is one name`() {
        assertEquals(listOf("Kit Calloway; Mira Solen"), of(" Kit Calloway; Mira Solen ", ArtistSplit(enabled = false)))
        assertEquals(listOf("Kit Calloway feat. Mira Solen"), of("Kit Calloway feat. Mira Solen", ArtistSplit(separators = emptyList())))
    }
}
