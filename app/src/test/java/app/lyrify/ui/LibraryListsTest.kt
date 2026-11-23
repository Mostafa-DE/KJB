package app.lyrify.ui

import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LyricsFilter
import app.lyrify.settings.SortSongs
import app.lyrify.stats.SongPlays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the new keys head their runs by year, by when last played, by the file's first letter, and length and plays by none`() {
        val settings = LibrarySettings()
        val now = LocalDateTime.of(2026, 11, 10, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val zone = ZoneId.of("UTC")
        assertEquals(ListSection.Year(2019), listSection(song("Music/a/x.mp3", year = 2019), SortSongs.YEAR, settings, now, zone))
        assertEquals(ListSection.Year(null), listSection(clock, SortSongs.YEAR, settings, now, zone))
        assertEquals(ListSection.Letter("T"), listSection(song("Music/a/track 2.mp3", title = "Low Water"), SortSongs.FILE_NAME, settings, now, zone))
        assertEquals(ListSection.None, listSection(water, SortSongs.PLAY_COUNT, settings, now, zone))
        assertEquals(ListSection.None, listSection(water, SortSongs.DURATION, settings, now, zone))

        val played = mapOf(lantern.path to SongPlays(lantern.ref, 3, now - 2 * DAY, 0), bell.path to SongPlays(bell.ref, 1, now - 40 * DAY, 0))
        assertEquals(ListSection.Played(AddedWhen.THIS_WEEK), listSection(lantern, SortSongs.LAST_PLAYED, settings, now, zone, played))
        assertEquals(ListSection.Played(AddedWhen.EARLIER), listSection(bell, SortSongs.LAST_PLAYED, settings, now, zone, played))
        assertEquals(ListSection.Played(null), listSection(water, SortSongs.LAST_PLAYED, settings, now, zone, played))
    }

    @Test
    fun `the Songs tab goes the other way round when reversed, and sorts by plays it is given`() {
        assertEquals(listOf(clock, water, lantern, bell).reversed(), songsTab(songs, status, LibrarySettings(songSort = SortSongs.TITLE, songReversed = true)))
        assertEquals(listOf(bell, water, lantern, clock), songsTab(songs, status, LibrarySettings(songSort = SortSongs.RECENTLY_ADDED, songReversed = true)))
        val plays = mapOf(bell.path to SongPlays(bell.ref, 7, 5, 0), water.path to SongPlays(water.ref, 2, 6, 0))
        assertEquals(listOf(bell, water), songsTab(songs, status, LibrarySettings(songSort = SortSongs.PLAY_COUNT), plays).take(2))
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
    fun `song rows are keyed by path, and a list that may hold a song twice by its entries`() {
        fun keys(list: List<Song>) = songRowKeys(list)

        // Another sort, or a song added at the top, leaves every row's key as it was.
        assertEquals(keys(songs).toSet(), keys(songs.reversed()).toSet())
        assertTrue(keys(listOf(song("Music/Loose/New.mp3")) + songs).containsAll(keys(songs)))
        // A playlist with a song twice keys its rows by entry: every key is still its own.
        val twice = listOf(lantern.ref, bell.ref, lantern.ref)
        assertEquals(3, OrderedEntries().of(twice).second.toSet().size)
    }

    @Test
    fun `Remove from playlist acts on the place of now of the row tapped, also while the rows still show the sort from before`() {
        val ordered = OrderedEntries()
        fun listed(stored: List<Song>): List<ListedEntry> {
            val (entries, keys) = ordered.of(stored.map { it.ref })
            return listedEntries(entries.mapIndexed { at, ref -> at to stored.first { it.ref == ref } }, keys)
        }
        val before = listed(listOf(lantern, bell, water, clock))
        // Sorted by title: 9 o'clock, Low Water, Paper Lantern, Tide Bell.
        val shown = inOrder(before, app.lyrify.settings.ListSort(SortSongs.TITLE), LibrarySettings()) { it.song }
        assertEquals(listOf(clock, water, lantern, bell), shown.map { it.song })
        // Tide Bell (place 1) taken out: until the new sort is ready the rows are the four sorted before, each with its old
        // place. Low Water's was 2, which is now 9 o'clock's: a Remove there took 9 o'clock out instead.
        val now = listed(listOf(lantern, water, clock))
        assertEquals(1, placeNow(shown, now, shown.indexOfFirst { it.song == water }))
        assertEquals(2, placeNow(shown, now, shown.indexOfFirst { it.song == clock }))
        assertEquals(0, placeNow(shown, now, shown.indexOfFirst { it.song == lantern }))
        // Tide Bell's row is the song gone: no place, so its sheet has no Remove from playlist.
        assertEquals(null, placeNow(shown, now, shown.indexOfFirst { it.song == bell }))
        assertEquals(null, placeNow(shown, now, shown.size))
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
    fun `the Songs tab starts with Huddle's friends, then For you's card, then the Favourites row, and Covers and tags is no longer there`() {
        val screen = File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText()
        val songsTab = screen.substringAfter("private fun SongsTab(").substringBefore("\n}\n")
        val items = Regex("""item\(key = "([^"]+)"[,)]""").findAll(songsTab).map { it.groupValues[1] }.toList()
        // Huddle's friends row and a card a friend sent (the tab Library opens on), then For you's card (v4), at the top
        // of Library: each shows nothing while it has nothing (For you: no mix, or hidden).
        assertTrue(songsTab.indexOf("huddleFriends(LibraryTab.SONGS)") in 0 until songsTab.indexOf("item(key = \"for you\""))
        assertEquals(listOf("for you", "favourites"), items.take(2))
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

    @Test
    fun `TalkBack hears that a row's song is the one playing`() {
        assertEquals("Paper Lantern, Tin Roof, Synced lyrics, Playing", songRowWords("Paper Lantern", "Tin Roof", "Synced lyrics", note = null, line = null, playing = "Playing"))
        assertEquals("Paper Lantern, Tin Roof", songRowWords("Paper Lantern", "Tin Roof", null, note = null, line = null, playing = null))
    }

    // A song row's length at its end (LN1)

    @Test
    fun `a row shows its song's length as the player's clock does, and nothing for a length unknown`() {
        assertEquals("5:07", rowLength(307_000))
        assertEquals("0:01", rowLength(1_000))
        assertEquals("59:59", rowLength(3_599_999))
        assertEquals("1:02:13", rowLength(3_733_000))
        // MediaStore's 0 for a length it could not read, and under a second, which would say "0:00".
        assertNull(rowLength(0))
        assertNull(rowLength(-1))
        assertNull(rowLength(999))
    }

    @Test
    fun `TalkBack hears the length in words after the note, each part but those at none`() {
        assertEquals(listOf(R.plurals.lib_length_minutes to 5, R.plurals.tones_seconds to 7), spokenLength(307_000))
        assertEquals(listOf(R.plurals.lib_length_hours to 1, R.plurals.lib_length_minutes to 2, R.plurals.tones_seconds to 13), spokenLength(3_733_000))
        assertEquals(listOf(R.plurals.lib_length_minutes to 3), spokenLength(180_000))
        assertEquals(listOf(R.plurals.lib_length_hours to 1, R.plurals.tones_seconds to 5), spokenLength(3_605_000))
        assertEquals(listOf(R.plurals.tones_seconds to 45), spokenLength(45_900))
        // A length the row hides is not said either.
        assertEquals(emptyList<Pair<Int, Int>>(), spokenLength(0))
        assertEquals(emptyList<Pair<Int, Int>>(), spokenLength(999))
        assertEquals(emptyList<Pair<Int, Int>>(), spokenLength(-61_000))
        val strings = File("src/main/res/values").listFiles().orEmpty().filter { it.name.startsWith("strings") }.joinToString("\n") { it.readText() }
        assertTrue(strings.contains("<item quantity=\"one\">%d hour</item>") && strings.contains("<item quantity=\"other\">%d hours</item>"))
        assertTrue(strings.contains("<item quantity=\"one\">%d minute</item>") && strings.contains("<item quantity=\"other\">%d minutes</item>"))
        assertTrue(strings.contains("<item quantity=\"one\">%d second</item>") && strings.contains("<item quantity=\"other\">%d seconds</item>"))
        assertEquals(
            "Paper Lantern, Tin Roof, Synced lyrics, 5 minutes 7 seconds",
            songRowWords("Paper Lantern", "Tin Roof", "Synced lyrics", note = null, line = null, playing = null, length = "5 minutes 7 seconds"),
        )
        assertEquals(
            "Low Water, Mira Solen, 3 days ago, 1 hour 2 minutes 13 seconds, Playing",
            songRowWords("Low Water", "Mira Solen", null, note = "3 days ago", line = null, playing = "Playing", length = "1 hour 2 minutes 13 seconds"),
        )
    }

    @Test
    fun `the length ends the artist's line before the menu, small and on one line, and the artist gives way first so a note fits too (the wiring, read from the source)`() {
        // No Compose renderer on the JVM: the format and the words are the tests above.
        val row = File("src/main/java/app/lyrify/ui/LibraryParts.kt").readText().substringAfter("fun SongRow(").substringBefore("\n}\n")
        assertTrue(row.contains("val length = rowLength(song.durationMs)"))
        assertTrue(row.contains("spokenLength(song.durationMs).map { (words, count) -> pluralStringResource(words, count, count) }.joinToString(\" \")"))
        assertTrue(row.contains("mark, reason, lengthWords.ifEmpty { null })"))
        // The artist, the note and the lyrics icon share what the length leaves; the artist is cut first, a note never.
        val line = row.substringAfter("Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {")
        assertTrue(line.contains("modifier = Modifier.weight(1f, fill = false)"))
        assertTrue(line.contains("if (note != null) Text(stringResource(R.string.lib_after_dot, note)"))
        assertFalse(row.contains("listOfNotNull(artist, note)"))
        // The length after them, at the row's end before "⋯": the secondary ink, small print, never wrapped.
        assertTrue(line.indexOf("LyricsIcon(") < line.indexOf("if (length != null) Text(length, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant, maxLines = 1, softWrap = false"))
    }

    @Test
    fun `the length is added once, in the shared song row, and rows with a length or progress of their own keep theirs (read from the source)`() {
        val ui = File("src/main/java/app/lyrify/ui").listFiles().orEmpty().filter { it.extension == "kt" }
        val uses = ui.associate { it.name to Regex("""(?<!fun )rowLength\(""").findAll(it.readText()).count() }.filterValues { it > 0 }
        assertEquals(mapOf("LibraryParts.kt" to 1), uses)
        // The queue says where a song was left, books where they stand: neither is the shared row, so neither shows a length
        // on top of its own.
        val shared = Regex("""\bSongRow\(""")
        val queue = File("src/main/java/app/lyrify/ui/QueueScreens.kt").readText()
        assertTrue(queue.contains("R.string.queue_left_at"))
        assertFalse(shared.containsMatchIn(queue))
        val books = File("src/main/java/app/lyrify/ui/BooksScreen.kt").readText()
        assertTrue(books.contains("StatusLines(status)"))
        assertFalse(shared.containsMatchIn(books))
    }

    // Recently added, Recently played and Most played (RP1): their chips, Search in this list, and the songs they show

    @Test
    fun `Search in this list finds a song by its title as shown, its artist or its album, as Search compares words`() {
        val cafe = song("Music/Loose/Corner Cafe.mp3", title = "Café Lights", artist = "Rowan Fennick", album = "Élan Nights")
        val list = songs + cafe
        val names = LibrarySettings()

        assertEquals(list, findInList(list, "", names))
        assertEquals(list, findInList(list, "   ", names))
        assertEquals(listOf(lantern), findInList(list, "lantern", names))
        assertEquals(listOf(water), findInList(list, "MIRA", names))
        assertEquals(listOf(lantern, bell), findInList(list, "harbour glass", names))
        assertEquals(listOf(clock), findInList(list, "o'clock", names))
        // Accents, case and marks aside, as Search: "cafe" finds "Café", "elan" the album "Élan Nights".
        assertEquals(listOf(cafe), findInList(list, "cafe", names))
        assertEquals(listOf(cafe), findInList(list, "elan", names))
        assertEquals(emptyList<Song>(), findInList(list, "nothing like this", names))
        // Under "Show file names" a title is found by the file's name it shows.
        assertEquals(listOf(cafe), findInList(list, "corner", LibrarySettings(showFileNames = true)))
        assertEquals(emptyList<Song>(), findInList(list, "lights", LibrarySettings(showFileNames = true)))
    }

    @Test
    fun `a list with chips hands the songs its search leaves to its hero line, header and frame, and stores the chip picked (the page's wiring, read from its source)`() {
        // No Compose renderer on the JVM: the period lists are ListeningTest's and PlaylistsTest's, the search findInList's above.
        val page = File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText().substringAfter("private fun BuiltInPage(").substringBefore("\n}\n")
        // The songs shown are those the search leaves of the chip's list.
        assertTrue(page.contains("findInList("))
        // The hero's line, the header's count, and Shuffle, Play and Add all (the frame's songs) take them. The line says the
        // length in hours and minutes as Insights do ("83 songs · 7 h 3 min", "42 min" under an hour; user 2026-11-22).
        assertTrue(page.contains("songsMeta(songs, hours = true)"))
        assertTrue(page.contains("ListHeader(songs.size,"))
        assertTrue(page.contains("SongListFrame(ui, songs,"))
        // One chip per period, one picked at a time, kept for the list in settings.json.
        assertTrue(page.contains("ListPeriod.entries"))
        assertTrue(page.contains("Role.RadioButton"))
        assertTrue(page.contains("listPeriods = it.library.listPeriods + (list.songList to"))
        val strings = File("src/main/res/values/strings_library.xml").readText()
        assertTrue(strings.contains("<item quantity=\"other\">Past %d days</item>"))
        assertTrue(strings.contains(">Search in this list<"))
    }

    @Test
    fun `Not played is a list with chips like the others, with its own songs, queue and empty words, in Library and Search after Recently added (the wiring, read from the source)`() {
        // Its songs are notPlayed's (PlaylistsTest, ListeningTest), the page's head, chips and search the other lists' own.
        val page = File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText().substringAfter("private fun BuiltInPage(").substringBefore("\n}\n")
        assertTrue(page.contains("BuiltInList.NOT_PLAYED -> withContext(Dispatchers.Default) { notPlayed(plays, library, since) }"))
        assertTrue(page.contains("BuiltInList.NOT_PLAYED -> SourceKind.NOT_PLAYED"))
        assertTrue(page.contains("R.string.lib_not_played_empty_title"))
        // Its own order is named in the head's chip and the sort sheet (only Not played has one among these lists).
        assertEquals(2, Regex("""R\.string\.lib_sort_never_played_first""").findAll(page).count())
        // Library's Built for you and the lists Search finds count it over its chip, after Recently added.
        val library = File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText()
        assertTrue(library.indexOf("BuiltInRow(BuiltInList.RECENTLY_ADDED, added)") < library.indexOf("BuiltInRow(BuiltInList.NOT_PLAYED, unplayed)"))
        assertTrue(library.contains("notPlayed(Lyrify.plays(context), library, sinceOf(ui.settings.library.periodOf(SongList.NOT_PLAYED)"))
        val search = File("src/main/java/app/lyrify/ui/SearchScreen.kt").readText()
        assertTrue(search.contains("BuiltInList.NOT_PLAYED to notPlayed(plays, library, sinceOf(settings.periodOf(SongList.NOT_PLAYED)"))
        // An imported playlist never takes its name.
        assertTrue(File("src/main/java/app/lyrify/ui/ImportScreens.kt").readText().contains("R.string.lib_recently_added, R.string.lib_not_played)"))
        val strings = File("src/main/res/values/strings_library.xml").readText()
        assertTrue(strings.contains("""<string name="lib_not_played_empty_title">You\'ve played every song</string>"""))
        assertTrue(strings.contains("""<string name="lib_sort_never_played_first">Never played first</string>"""))
        // Grok review: "You've played every song" only where there are songs (else the other lists' "No songs yet"), and a
        // chip's own line, as a song not played in its days shows there too.
        assertTrue(page.contains("list == BuiltInList.NOT_PLAYED && library.songs.any { !it.isPodcast }"))
        assertTrue(page.contains("if (period == ListPeriod.ALL_TIME) R.string.lib_not_played_empty else R.string.lib_not_played_empty_period"))
        assertTrue(page.contains("if (list == BuiltInList.RECENTLY_PLAYED || list == BuiltInList.MOST_PLAYED) R.string.lib_played_empty else R.string.lib_added_empty"))
        assertTrue(strings.contains("""<string name="lib_not_played_empty_period">Songs you haven\'t played in this time show here.</string>"""))
    }

    @Test
    fun `a period list words each row's note as the row is shown, never every song's at each key typed (the wiring, read from the source)`() {
        // Grok review: Not played over a chip holds most of the library, and the notes were all worked out on the main thread
        // at each recomposition, each letter typed in Search in this list among them.
        val page = File("src/main/java/app/lyrify/ui/CollectionScreens.kt").readText().substringAfter("private fun BuiltInPage(").substringBefore("\n}\n")
        assertFalse(page.contains("songs.associate"))
        assertTrue(page.contains("songRows(songList, note = { song -> rowPlays?.get(song.path)?.let { p -> if (list == BuiltInList.MOST_PLAYED) playsNote(p.count) else lastPlayedText(p.lastPlayedAt) } })"))
        assertTrue(File("src/main/java/app/lyrify/ui/LibraryParts.kt").readText().contains("note: (@Composable (Song) -> String?)? = null,"))
    }

    @Test
    fun `album cards carry a tag a device test finds them by, never their album's name`() {
        // UiAutomator sees a Compose test tag only as a resource id, and only where the tree asks for that.
        val card = File("src/main/java/app/lyrify/ui/LibraryParts.kt").readText().substringAfter("fun AlbumCard(").substringBefore("\n}\n")
        assertTrue(card.contains(".testTag(ALBUM_CARD_TAG)"))
        assertTrue(card.contains("testTagsAsResourceId = true"))
    }
}
