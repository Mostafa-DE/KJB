package app.lyrify.ui

import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LyricsFilter
import app.lyrify.settings.SortSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
                LyricsStatus.PLAIN_ONLY, LyricsStatus.EXISTING_PLAIN -> LyricsMark.PLAIN
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
    fun `title order and headings skip leading punctuation and brackets, digits under hash first, Arabic after Z`() {
        val settings = LibrarySettings(songSort = SortSongs.TITLE)
        val zone = ZoneId.of("UTC")
        val titles = listOf(
            "سفينة الملح", "Dune Lights", "'Til Morning", "(Dear Tide) Harbour", "Amber Field", "باب الضباب", "12 Paper Moons",
            "Écume", "& Copper Wings", "Dawn Engines", "...Ashen Bell", "أمواج نحاسية",
        )
        val sorted = songsTab(titles.mapIndexed { at, title -> song("Music/t/$at.mp3", title = title) }, status, settings)
        fun heading(song: Song) = (listSection(song, SortSongs.TITLE, settings, 0, zone) as ListSection.Letter).letter

        assertEquals(
            listOf(
                "12 Paper Moons", "Amber Field", "...Ashen Bell", "& Copper Wings", "Dawn Engines", "(Dear Tide) Harbour", "Dune Lights",
                "Écume", "'Til Morning", "أمواج نحاسية", "باب الضباب", "سفينة الملح",
            ),
            sorted.map { it.title },
        )
        // One heading per run of songs: "(Dear Tide)" sits among the other D titles, never above "#" and "A".
        assertEquals(listOf("#", "A", "A", "C", "D", "D", "D", "E", "T", "ا", "ب", "س"), sorted.map(::heading))
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

    @Test
    fun `Folders to skip offers every folder of the tree, with the songs in it or below, and any other folder skipped`() {
        val live = song("Music/Rips/Live/Harbour Night.mp3")
        val rip = song("Music/Rips/Low Tide.mp3")
        val talk = song("Podcasts/Talk/Episode 1.mp3")
        val loose = song("Music/Loose.mp3")

        assertEquals(
            listOf("Music" to 3, "Music/Rips" to 2, "Music/Rips/Live" to 1, "Old/Tapes" to 0, "Podcasts" to 1, "Podcasts/Talk" to 1),
            skipChoices(listOf(live, rip, talk, loose), skipped = listOf("/Old/Tapes/", "music/rips")),
        )
    }

    @Test
    fun `the Songs tab's Favourites row counts the favourite songs, or says None yet, and opens Favourites inside Library`() {
        assertEquals(FavouritesRow(ToolLine.Count(R.plurals.lib_songs, 120), Page.BuiltIn(BuiltInList.FAVOURITES)), favouritesRow(120))
        assertEquals(ToolLine.Count(R.plurals.lib_songs, 1), favouritesRow(1).line)
        assertEquals(ToolLine.Text(R.string.lib_none_yet), favouritesRow(0).line)
        val opened = NavState.start(DockTab.LIBRARY).open(favouritesRow(3).page)
        assertEquals(DockTab.LIBRARY, opened.tab)
        assertEquals(listOf<Page>(Page.BuiltIn(BuiltInList.FAVOURITES)), opened.pages)
        // Unit tests run in the module's folder.
        assertTrue("""<string name="lib_none_yet">None yet</string>""" in File("src/main/res/values/strings_library.xml").readText())
    }

    @Test
    fun `the Songs tab starts with the Favourites row, and Covers and tags is no longer there`() {
        val screen = File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText()
        val songsTab = screen.substringAfter("private fun SongsTab(").substringBefore("\n}\n")
        val firstItem = Regex("""item\(key = "([^"]+)"[,)]""").find(songsTab)?.groupValues?.get(1)
        assertEquals("favourites", firstItem)
        assertFalse("CoversTagsEntry" in screen)
        assertFalse("CoversTagsEntry" in File("src/main/java/app/lyrify/ui/CoversTagsScreen.kt").readText())
    }

    private companion object {
        const val DAY: Long = 24 * 60 * 60_000L
    }

    @Test
    fun `a playlist's mosaic takes one cover per folder, the first four, filling four places when it has two or three`() {
        fun ref(path: String) = app.lyrify.library.SongRef("/Music/KJB test/$path")
        assertEquals(emptyList<app.lyrify.library.SongRef>(), mosaicCovers(emptyList()))
        // One folder (an album, mostly): one cover, shown alone rather than four times.
        assertEquals(listOf(ref("Paper Harbour/01.mp3")), mosaicCovers(listOf(ref("Paper Harbour/01.mp3"), ref("Paper Harbour/02.mp3"))))
        // Two folders: four places, taken in turn.
        val two = mosaicCovers(listOf(ref("Paper Harbour/01.mp3"), ref("Paper Harbour/02.mp3"), ref("Glass Kites/01.mp3")))
        assertEquals(listOf("Paper Harbour/01.mp3", "Glass Kites/01.mp3", "Paper Harbour/01.mp3", "Glass Kites/01.mp3"), two.map { it.path.removePrefix("/Music/KJB test/") })
        val many = mosaicCovers((1..6).map { ref("Folder $it/01.mp3") })
        assertEquals((1..4).map { "/Music/KJB test/Folder $it/01.mp3" }, many.map { it.path })
    }

    // A song row's marks: the lyrics icon after the artist, the playing dot before it

    @Test
    fun `a row shows the lyrics icon for synced and plain lyrics, and none without lyrics`() {
        assertEquals(LyricsMark.SYNCED, rowMarks(LyricsStatus.SYNCED, lantern.path, playingPath = null).lyrics)
        assertEquals(LyricsMark.SYNCED, rowMarks(LyricsStatus.AI_ALIGNED, lantern.path, playingPath = null).lyrics)
        assertEquals(LyricsMark.PLAIN, rowMarks(LyricsStatus.PLAIN_ONLY, bell.path, playingPath = null).lyrics)
        assertEquals(LyricsMark.NONE, rowMarks(LyricsStatus.NOT_FOUND, water.path, playingPath = null).lyrics)
        assertEquals(LyricsMark.NONE, rowMarks(null, clock.path, playingPath = null).lyrics)
    }

    @Test
    fun `only the song playing has the dot, so one row of a list at most, and it moves with the song`() {
        fun dots(playing: String?) = songs.filter { rowMarks(status(it), it.path, playing).playing }
        assertEquals(listOf(bell), dots(bell.path))
        assertEquals(listOf(water), dots(water.path))
        assertEquals(emptyList<Song>(), dots(null))
        assertEquals(emptyList<Song>(), dots("Music/Elsewhere/Not Here.mp3"))
        // Lyrics or not, the dot is the playing song's alone.
        assertFalse(rowMarks(LyricsStatus.SYNCED, lantern.path, playingPath = bell.path).playing)
    }

    @Test
    fun `no lyrics dot is left in the rows, and no help text explains one`() {
        val parts = File("src/main/java/app/lyrify/ui/LibraryParts.kt").readText()
        assertFalse(parts.contains("LyricsMarkDot"))
        val strings = File("src/main/res/values").listFiles().orEmpty().filter { it.name.startsWith("strings") }.joinToString("\n") { it.readText() }
        // The one dot left in the copy is Android's own "three dots" menu.
        assertFalse(Regex("(?i)\\bdots?\\b").containsMatchIn(strings.replace("tap the three dots", "")))
    }
}
