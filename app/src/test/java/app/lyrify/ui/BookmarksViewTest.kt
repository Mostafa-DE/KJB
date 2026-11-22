package app.lyrify.ui

import app.lyrify.library.Library
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.player.Bookmark
import app.lyrify.player.SongMark
import app.lyrify.player.SongState
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Bookmarks across the app (BM1): Tools > Bookmarks' groups by book, episode or song, the newest first, and the words
 * the player says. Made-up names only.
 */
class BookmarksViewTest {
    private val parts = listOf("1", "2").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 600_000, id = it.toLong()) }
    private val orchard = song("Download/Quiet Orchard.m4b", durationMs = 900_000, id = 5)
    private val episode = song("Podcasts/Harbour Talk/Episode 12.mp3", album = "Harbour Talk", durationMs = 3_600_000, id = 6, isPodcast = true)
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", album = "Harbour Glass", id = 7)
    private val gone = SongRef(VOLUME + "Music/Rips/Loose Thread.mp3")
    private val library = Library.of(parts + orchard + episode + lantern, Settings())
    private val folderBook = library.bookOf(parts[0].path)!!
    private val oneFile = library.bookOf(orchard.path)!!

    private fun SongState.mark(song: SongRef, positionMs: Long, note: String, addedAt: Long = 0): SongState = withBookmark(song, positionMs, note, addedAt)!!.first

    /** Three made before KJB kept the time (ids 1 to 3), then five made since. */
    private val state = SongState()
        .mark(orchard.ref, 61_000, "Old")
        .mark(gone, 12_000, "")
        .mark(orchard.ref, 5_000, "")
        .mark(lantern.ref, 30_000, "Chorus", addedAt = T + 1)
        .mark(parts[0].ref, 90_000, "Quote", addedAt = T + 2)
        .mark(episode.ref, 600_000, "", addedAt = T + 3)
        .mark(parts[1].ref, 5_000, "", addedAt = T + 4)
        .mark(episode.ref, 60_000, "Recipe", addedAt = T + 5)

    @Test
    fun `every bookmark shows once, by book, episode or song, the group with the newest bookmark first`() {
        val groups = bookmarkGroups(state, library)
        assertEquals(
            listOf(
                BookmarkGroup(episode.path, null, episode, listOf(SongMark(episode.ref, Bookmark(8, 60_000, "Recipe", T + 5)), SongMark(episode.ref, Bookmark(6, 600_000, "", T + 3)))),
                // A book's bookmarks in the book's order, part by part.
                BookmarkGroup(folderBook.id, folderBook, null, listOf(SongMark(parts[0].ref, Bookmark(5, 90_000, "Quote", T + 2)), SongMark(parts[1].ref, Bookmark(7, 5_000, "", T + 4)))),
                BookmarkGroup(lantern.path, null, lantern, listOf(SongMark(lantern.ref, Bookmark(4, 30_000, "Chorus", T + 1)))),
                // Made before KJB kept the time: after the rest, the latest made first.
                BookmarkGroup(oneFile.id, oneFile, null, listOf(SongMark(orchard.ref, Bookmark(3, 5_000, "")), SongMark(orchard.ref, Bookmark(1, 61_000, "Old")))),
                // A file the library does not list: by its path alone.
                BookmarkGroup(gone.path, null, null, listOf(SongMark(gone, Bookmark(2, 12_000, "")))),
            ),
            groups,
        )
        assertEquals(state.bookmarks.values.sumOf { it.size }, groups.sumOf { it.marks.size })
        assertEquals(emptyList<BookmarkGroup>(), bookmarkGroups(SongState(), library))
    }

    @Test
    fun `the words for a bookmark just added are plain, with Add note as its action`() {
        val text = File("src/main/res/values/strings_player.xml").readText()
        val strings = Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("Bookmark added at %1\$s", strings["bookmark_added"])
        assertEquals("Add note", strings["bookmark_add_note"])
    }

    private companion object {
        const val T: Long = 1_800_000_000_000L
    }
}
