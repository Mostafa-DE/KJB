package app.lyrify.ui

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Folder
import app.lyrify.library.Song
import app.lyrify.library.search.Hit
import app.lyrify.library.search.LibraryHits
import app.lyrify.library.search.LyricHit
import app.lyrify.library.search.Match
import app.lyrify.library.search.MatchKind
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search's results as the page shows them: groups in order with counts, the first four, then View all. Made-up names only. */
class SearchScreenTest {
    private val at = Match(MatchKind.WORD_START, 0, 1)
    private val songs = (1..6).map { song("Music/Harbour Glass/Lantern $it.mp3") }
    private val bell = song("Music/Night Ferry/Tide Bell.mp3")

    private fun songHits(list: List<Song>) = list.map { Hit(it, it.title, at) }

    private fun lyricHits(list: List<Song>) = list.map { LyricHit(it, it.title, "A made-up line", 1_000, at) }

    private val results = SearchResults(
        query = "lantern",
        songs = songHits(songs),
        albums = listOf("Harbour Glass", "Harbour Lights").map { Hit(Album("[\"Kit Calloway\",\"$it\"]", it, "Kit Calloway", songs, null), it, at) },
        folders = listOf(Hit(Folder("Music/Lanterns", "Lanterns", songs, emptyList()), "Lanterns", at)),
        lists = (1..4).map { Hit(ListRow(Page.Playlist("p$it"), "Lantern list $it", 3, 0), "Lantern list $it", at) },
        lyrics = lyricHits(listOf(songs[0], bell, songs[1], songs[2], songs[3])),
    )

    @Test
    fun `groups show in order, each with its count, empty ones left out`() {
        assertEquals(
            listOf(
                ResultGroup(SearchGroup.SONGS, 6),
                ResultGroup(SearchGroup.ALBUMS, 2),
                ResultGroup(SearchGroup.FOLDERS, 1),
                ResultGroup(SearchGroup.PLAYLISTS, 4),
                ResultGroup(SearchGroup.LYRICS, 5),
            ),
            resultGroups(results),
        )
    }

    @Test
    fun `In lyrics shows once the lyrics are searched`() {
        val waiting = results.copy(lyrics = null)

        assertFalse(resultGroups(waiting).any { it.group == SearchGroup.LYRICS })
        assertEquals(0, waiting.count(SearchGroup.LYRICS))
    }

    @Test
    fun `each group shows its first four, then View all`() {
        val viewAll = resultGroups(results).associate { it.group to it.viewAll }

        assertEquals(mapOf(SearchGroup.SONGS to true, SearchGroup.ALBUMS to false, SearchGroup.FOLDERS to false, SearchGroup.PLAYLISTS to false, SearchGroup.LYRICS to true), viewAll)
        assertEquals(4, shownCount(results, SearchGroup.SONGS, all = false))
        assertEquals(6, shownCount(results, SearchGroup.SONGS, all = true))
        assertEquals(2, shownCount(results, SearchGroup.ALBUMS, all = false))
        assertEquals(4, shownCount(results, SearchGroup.PLAYLISTS, all = false))
        assertEquals(0, shownCount(results, SearchGroup.ARTISTS, all = true))
        assertTrue(results.artists.isEmpty() && results.count(SearchGroup.ARTISTS) == 0)
        assertEquals(emptyList<Hit<Artist>>(), results.artists)
    }

    @Test
    fun `a tapped song plays the song results, then the songs found only in lyrics`() {
        assertEquals(songs + bell, results.queue)
    }

    @Test
    fun `In lyrics counts every song found while it holds only the first page`() {
        val page = results.copy(lyrics = lyricHits(songs.take(4)), lyricsCount = 9)

        assertEquals(9, page.count(SearchGroup.LYRICS))
        assertTrue(resultGroups(page).single { it.group == SearchGroup.LYRICS }.viewAll)
        assertEquals(4, shownCount(page, SearchGroup.LYRICS, all = false))
        assertEquals(0, page.copy(lyrics = null).count(SearchGroup.LYRICS))
        assertEquals(9, namedResults("lantern", LibraryHits(), emptyList(), page).lyricsCount)
    }

    @Test
    fun `new words never keep the last words' lyric rows, while the same words keep theirs until new ones are in`() {
        val found = LibraryHits(songs = songHits(songs.take(2)))

        val typed = namedResults("lanterns", found, emptyList(), results)
        assertNull(typed.lyrics)
        assertFalse(resultGroups(typed).any { it.group == SearchGroup.LYRICS })
        assertEquals(songs.take(2), typed.queue)

        val again = namedResults("lantern", found, emptyList(), results)
        assertEquals(results.lyrics, again.lyrics)
    }
}
