package app.lyrify.tags

import app.lyrify.library.Library
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.tags.TagSuggestions.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Edit tags' suggestions: the library's own values for the fields that repeat, the closest and most used first.
 * Made-up names only, apart from a default exception.
 */
class TagSuggestionsTest {
    private val songs = listOf(
        song("Music/k/1.mp3", artist = "Kitty Marlowe", album = "Lantern Hours", genre = "Folk"),
        song("Music/k/2.mp3", artist = "Kitty Marlowe", album = "Lantern Hours", genre = "Folk"),
        song("Music/k/3.mp3", artist = "Kitty Marlowe", album = "Lantern Hours", genre = "Folk Rock"),
        song("Music/k/4.mp3", artist = "Kitty Marlowe", album = "Lantern Hours", genre = "Folk Rock"),
        song("Music/h/1.mp3", artist = "Kit Calloway", album = "Harbour Glass", albumArtist = "Kit Calloway", genre = "Folk"),
        song("Music/h/2.mp3", artist = "Kit Calloway feat. Mira Solen", album = "Harbour Glass", albumArtist = "Kit Calloway", genre = "Ambient Folk"),
        song("Music/h/3.mp3", artist = "Kit Calloway", album = "Night Ferry", genre = "Folk Rock"),
        song("Music/s/1.mp3", artist = "Kitson Hale", album = "Night Ferry", albumArtist = "Various Artists"),
        song("Music/s/2.mp3", artist = "Kitson Hale", album = "Skittish"),
        song("Music/n/1.mp3", artist = "Nikita Grove", album = "Kites"),
        song("Music/m/1.mp3", artist = "Mira Solen"),
        song("Music/m/2.mp3", artist = "Søren Vale"),
        song("Music/t/1.mp3", artist = "Tyler, The Creator"),
        song("Music/x/1.mp3", artist = "Hidden Harper", album = "Hidden Hours", genre = "Hidden Folk"),
    )
    private val hidden = SongRef(VOLUME + "Music/x/1.mp3")
    private val library = Library.of(songs, Settings(library = LibrarySettings(hiddenSongs = listOf(hidden))))
    private val suggestions = TagSuggestions.of(library)

    private fun names(field: TagField, typed: String): List<String> = suggestions.of(field, typed).map { it.name }

    @Test
    fun `values that start with the text come first, then those with it inside, each the most used first`() {
        // Kitty Marlowe has 4 songs, Kit Calloway 3 (one a duet), Kitson Hale 2; Nikita Grove has it inside.
        assertEquals(listOf("Kitty Marlowe", "Kit Calloway", "Kitson Hale", "Nikita Grove"), names(TagField.ARTIST, "kit"))
        assertEquals(listOf("Folk", "Folk Rock", "Ambient Folk"), names(TagField.GENRE, "folk"))
    }

    @Test
    fun `case and accents do not matter`() {
        assertEquals(names(TagField.ARTIST, "kit"), names(TagField.ARTIST, "KIT"))
        assertEquals(listOf("Søren Vale"), names(TagField.ARTIST, "soren"))
    }

    @Test
    fun `at most five show`() {
        val many = Library.of((1..8).map { song("Music/r/$it.mp3", artist = "Rowan Band $it") }, Settings())

        assertEquals(TagSuggestions.MAX, TagSuggestions.of(many).of(TagField.ARTIST, "rowan").size)
        assertEquals(2, TagSuggestions.of(many).of(TagField.ARTIST, "rowan", limit = 2).size)
    }

    @Test
    fun `artist fields suggest single artists, Album artist its own names and the artists too`() {
        assertEquals(listOf("Mira Solen"), names(TagField.ARTIST, "mira"))
        assertEquals(emptyList<String>(), names(TagField.ARTIST, "various"))
        assertEquals(listOf("Various Artists"), names(TagField.ALBUM_ARTIST, "various"))
        assertEquals(listOf("Kitty Marlowe", "Kit Calloway", "Kitson Hale", "Nikita Grove"), names(TagField.ALBUM_ARTIST, "kit"))
        assertEquals(listOf("Tyler, The Creator"), names(TagField.ARTIST, "tyler"))
    }

    @Test
    fun `albums and genres suggest their values, the other fields none`() {
        assertEquals(listOf("Night Ferry"), names(TagField.ALBUM, "night"))
        // Kites by prefix; Skittish has it inside.
        assertEquals(listOf("Kites", "Skittish"), names(TagField.ALBUM, "kit"))
        assertEquals(setOf(TagField.ARTIST, TagField.ALBUM_ARTIST, TagField.ALBUM, TagField.GENRE), TagSuggestions.FIELDS)
        listOf(TagField.TITLE, TagField.YEAR, TagField.TRACK, TagField.DISC).forEach { assertEquals(it.name, emptyList<Suggestion>(), suggestions.of(it, "1")) }
    }

    @Test
    fun `what is typed exactly is not suggested again, another spelling is`() {
        assertEquals(listOf("Folk Rock", "Ambient Folk"), names(TagField.GENRE, "Folk"))
        assertEquals(listOf(Suggestion("Kit Calloway", "Kit Calloway")), suggestions.of(TagField.ARTIST, "kit calloway"))
        assertEquals(emptyList<Suggestion>(), suggestions.of(TagField.ARTIST, ""))
        assertEquals(emptyList<Suggestion>(), suggestions.of(TagField.ARTIST, "   "))
    }

    @Test
    fun `after a separator the name being typed is suggested on its own, the names before it kept as typed`() {
        assertEquals(listOf(Suggestion("Mira Solen", "Kit Calloway feat. Mira Solen")), suggestions.of(TagField.ARTIST, "Kit Calloway feat. mi"))
        assertEquals("Mira Solen;Kitty Marlowe", suggestions.of(TagField.ARTIST, "Mira Solen;kit").first().value)
        // Names already typed are not suggested again, and nothing is until a letter follows the separator.
        assertEquals(listOf("Kitty Marlowe", "Kitson Hale", "Nikita Grove"), names(TagField.ARTIST, "Kit Calloway; kit"))
        assertEquals(emptyList<Suggestion>(), suggestions.of(TagField.ARTIST, "Kit Calloway, "))
        // A whole name with a comma of its own is still found.
        assertEquals(listOf(Suggestion("Tyler, The Creator", "Tyler, The Creator")), suggestions.of(TagField.ARTIST, "Tyler, The Cr"))
    }

    @Test
    fun `hidden songs suggest nothing`() {
        assertEquals(emptyList<String>(), names(TagField.ARTIST, "hidden"))
        assertEquals(emptyList<String>(), names(TagField.ALBUM, "hidden"))
        assertEquals(emptyList<String>(), names(TagField.GENRE, "hidden"))
    }
}
