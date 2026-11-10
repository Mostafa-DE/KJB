package app.lyrify.ui

import app.lyrify.data.LyricsStatus
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LyricsFilter
import app.lyrify.settings.SortSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The Library's lists: the lyrics mark, the Songs tab's sort and filter sheet, and its headings. Made-up names only. */
class LibraryListsTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", album = "Harbour Glass", track = 1, addedAt = 3)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", album = "Harbour Glass", track = 2, addedAt = 1)
    private val water = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry", addedAt = 2)
    private val clock = song("Music/Loose/9 o'clock.mp3", title = "9 o'clock", artist = null)
    private val songs = listOf(lantern, bell, water, clock)

    private val statuses = mapOf(lantern.path to LyricsStatus.SYNCED, bell.path to LyricsStatus.PLAIN_ONLY, water.path to LyricsStatus.NOT_FOUND)
    private val status: (Song) -> LyricsStatus? = { statuses[it.path] }

    @Test
    fun `synced lyrics get the dot, plain the ring, and everything else no mark`() {
        val synced = setOf(LyricsStatus.SYNCED, LyricsStatus.EXISTING, LyricsStatus.AI_ALIGNED, LyricsStatus.AI_TRANSCRIBED)
        for (status in LyricsStatus.entries) {
            val expected = when (status) {
                in synced -> LyricsMark.SYNCED
                LyricsStatus.PLAIN_ONLY -> LyricsMark.PLAIN
                else -> LyricsMark.NONE
            }
            assertEquals("$status", expected, lyricsMark(status))
        }
        assertEquals(LyricsMark.NONE, lyricsMark(null))
    }

    @Test
    fun `the Show filter keeps the songs with its mark, and All keeps every song`() {
        fun shown(filter: LyricsFilter) = songsTab(songs, status, LibrarySettings(songFilter = filter, songSort = SortSongs.TITLE)).toSet()
        assertEquals(songs.toSet(), shown(LyricsFilter.ALL))
        assertEquals(setOf(lantern), shown(LyricsFilter.SYNCED))
        assertEquals(setOf(bell), shown(LyricsFilter.PLAIN))
        assertEquals(setOf(water, clock), shown(LyricsFilter.NONE))
        assertEquals(mapOf(LyricsFilter.ALL to 4, LyricsFilter.SYNCED to 1, LyricsFilter.PLAIN to 1, LyricsFilter.NONE to 2), filterCounts(songs, status))
    }

    @Test
    fun `the Songs tab sorts by its setting, the newest added first for Recently added`() {
        assertEquals(listOf(clock, water, lantern, bell), songsTab(songs, status, LibrarySettings(songSort = SortSongs.TITLE)))
        assertEquals(listOf(lantern, water, bell, clock), songsTab(songs, status, LibrarySettings(songSort = SortSongs.RECENTLY_ADDED)))
        assertEquals(listOf(lantern, bell), songsTab(songs, status, LibrarySettings(songSort = SortSongs.ALBUM, songFilter = LyricsFilter.ALL)).take(2))
        // "Show file names" sorts by what the row shows.
        val alpha = song("Music/Loose/b.mp3", title = "Alpha")
        val beta = song("Music/Loose/a.mp3", title = "Beta")
        assertEquals(listOf(alpha, beta), songsTab(listOf(beta, alpha), status, LibrarySettings(songSort = SortSongs.TITLE)))
        assertEquals(listOf(beta, alpha), songsTab(listOf(alpha, beta), status, LibrarySettings(songSort = SortSongs.TITLE, showFileNames = true)))
    }

    @Test
    fun `headings follow the sort, with digits and symbols under one mark`() {
        val settings = LibrarySettings()
        val now = LocalDateTime.of(2026, 11, 10, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val zone = ZoneId.of("UTC")
        assertEquals(ListSection.Letter("P"), listSection(song("Music/a/x.mp3", title = "paper Lantern"), SortSongs.TITLE, settings, now, zone))
        assertEquals(ListSection.Letter("E"), listSection(song("Music/a/x.mp3", title = "Écume"), SortSongs.TITLE, settings, now, zone))
        assertEquals(ListSection.Letter("#"), listSection(clock, SortSongs.TITLE, settings, now, zone))
        assertEquals(ListSection.Name("Mira Solen"), listSection(water, SortSongs.ARTIST, settings, now, zone))
        assertEquals(ListSection.Name(null), listSection(clock, SortSongs.ARTIST, settings, now, zone))
        assertEquals(ListSection.Name("Night Ferry"), listSection(water, SortSongs.ALBUM, settings, now, zone))

        fun added(daysAgo: Long) = listSection(song("Music/a/x.mp3", addedAt = now - daysAgo * DAY), SortSongs.RECENTLY_ADDED, settings, now, zone)
        assertEquals(ListSection.Added(AddedWhen.TODAY), added(0))
        assertEquals(ListSection.Added(AddedWhen.THIS_WEEK), added(6))
        assertEquals(ListSection.Added(AddedWhen.THIS_MONTH), added(29))
        assertEquals(ListSection.Added(AddedWhen.EARLIER), added(31))
        assertEquals(ListSection.Added(AddedWhen.EARLIER), listSection(clock, SortSongs.RECENTLY_ADDED, settings, now, zone))
    }

    @Test
    fun `the list header drops the chip's word, then Shuffle's, then Play's, then the chip, before anything overlaps`() {
        val shuffle = PartWidth(label = 100, icon = 48)
        val play = PartWidth(label = 90, icon = 48)
        val chip = PartWidth(label = 120, icon = 56)
        fun fit(width: Int, sort: PartWidth? = chip) = headerFit(width, fixed = 100, shuffle, play, sort)

        assertEquals(HeaderFit(true, true, ChipFit.LABEL), fit(410))
        assertEquals(HeaderFit(true, true, ChipFit.ICON), fit(346))
        assertEquals(HeaderFit(false, true, ChipFit.ICON), fit(294))
        assertEquals(HeaderFit(false, false, ChipFit.ICON), fit(252))
        assertEquals(HeaderFit(false, false, ChipFit.NONE), fit(251))
        // Narrower than even that: the fewest parts, never a chip over Play.
        assertEquals(HeaderFit(false, false, ChipFit.NONE), fit(100))
        // No chip: Shuffle, then Play.
        assertEquals(HeaderFit(true, true, ChipFit.NONE), fit(290, sort = null))
        assertEquals(HeaderFit(false, true, ChipFit.NONE), fit(238, sort = null))
        assertEquals(HeaderFit(false, false, ChipFit.NONE), fit(237, sort = null))
    }

    @Test
    fun `song rows are keyed by path, with their place only where a song can be there twice`() {
        fun keys(list: List<Song>, repeats: Boolean = false) = list.mapIndexed { at, song -> songRowKey(song, at, repeats) }

        // Another sort, or a song added at the top, leaves every row's key as it was.
        assertEquals(keys(songs).toSet(), keys(songs.reversed()).toSet())
        assertTrue(keys(listOf(song("Music/Loose/New.mp3")) + songs).containsAll(keys(songs)))
        // A playlist with a song twice: every key is still its own.
        val twice = listOf(lantern, bell, lantern)
        assertEquals(3, keys(twice, repeats = true).toSet().size)
    }

    private companion object {
        const val DAY: Long = 24 * 60 * 60_000L
    }
}
