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
        // The length and its words as every row shows them (SongLength, lengthWords: LN2).
        assertTrue(row.contains("mark, reason, lengthWords(song.durationMs).ifEmpty { null })"))
        // The artist, the note and the lyrics icon share, in a row of their own, what the length leaves; the artist is cut
        // first, a note never.
        val shrinking = row.substringAfter("Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {").substringBefore("\n                    }\n")
        assertTrue(shrinking.contains("modifier = Modifier.weight(1f, fill = false)"))
        assertTrue(shrinking.contains("if (note != null) Text(stringResource(R.string.lib_after_dot, note)"))
        assertTrue(shrinking.contains("LyricsIcon(Modifier.padding(start = 6.dp))"))
        assertFalse(row.contains("listOfNotNull(artist, note)"))
        // The length after that row, at the line's end before "⋯", unweighted so it is measured first and never cut: the
        // secondary ink, small print, never wrapped (Grok review: a check by order alone let it slip into the row above).
        assertFalse(shrinking.contains("SongLength("))
        val length = row.substringAfter(shrinking).lines().single { "SongLength(" in it }
        assertTrue(length.contains("SongLength(song.durationMs, Modifier.padding(start = 8.dp))"))
        assertFalse(length.contains("weight("))
    }

    // Every song's length, wherever a song shows (LN2): the rows that are not the shared one, Huddle's cards, one format

    @Test
    fun `a length shows as on the song rows, small in the secondary ink and on one line, and TalkBack hears it in words (the wiring, read from the source)`() {
        // No Compose renderer on the JVM: rowLength and spokenLength, above, are the format and the words.
        val parts = File("src/main/java/app/lyrify/ui/LibraryParts.kt").readText()
        assertTrue(parts.contains("fun SongLength(ms: Long, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {"))
        val shown = parts.substringAfter("fun SongLength(").substringBefore("\n}\n")
        // A length unknown (0, or under a second) shows nothing, and is not said either.
        assertTrue(shown.contains("val length = rowLength(ms) ?: return"))
        assertTrue(shown.contains("Text(length, style = MaterialTheme.typography.bodySmall.tabular(), color = color, maxLines = 1, softWrap = false, modifier = modifier.semantics { contentDescription = words })"))
        assertTrue(parts.substringAfter("fun lengthWords(").substringBefore("\n\n").contains("spokenLength(ms).map { (words, count) -> pluralStringResource(words, count, count) }.joinToString(\" \")"))
        // A row that opens something shows it at the end of its line when given one (What <name> played), and says it.
        val nav = parts.substringAfter("fun NavRow(").substringBefore("\n}\n")
        assertTrue(nav.contains("lengthMs: Long = 0,"))
        assertTrue(nav.contains("SongLength(lengthMs, Modifier.padding(start = 8.dp))"))
        assertTrue(nav.contains("description ?: listOfNotNull(title, sub, lengthWords(lengthMs).ifEmpty { null }).joinToString(\", \")"))
    }

    @Test
    fun `the queue's rows show each song's length at the end of the artist's line, Left at, the handle and the title kept (read from the source)`() {
        // On purpose (LN2): the queue shows the length too; LN1 kept it to the shared row.
        val row = File("src/main/java/app/lyrify/ui/QueueScreens.kt").readText().substringAfter("private fun QueueSongRow(").substringBefore("\n}\n")
        assertTrue(row.contains("R.string.queue_left_at"))
        assertTrue(row.contains("listOfNotNull(label, sub, lengthWords(song?.durationMs ?: 0).ifEmpty { null }, mark, reason)"))
        // The line under the title: the artist (or where the song was left) takes what is left, the length ends it.
        val line = row.substringAfter("Text(sub, ").substringBefore("if (reason != null) ReasonLine(reason)")
        assertTrue(line.startsWith("style = QueueLine, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))"))
        assertTrue(line.contains("song?.let { SongLength(it.durationMs, Modifier.padding(start = 8.dp), muted) }"))
        assertEquals(1, Regex("""SongLength\(""").findAll(row).count())
        // The handle keeps its 48 dp.
        assertTrue(row.substringAfterLast("Box(").contains(".size(48.dp)"))
        // Books keep where they stand, and no length.
        val books = File("src/main/java/app/lyrify/ui/BooksScreen.kt").readText()
        assertTrue(books.contains("StatusLines(status)"))
        assertFalse(books.contains("SongLength("))
    }

    @Test
    fun `Huddle's song cards and rows show the song's length under the song's line or after its artist, said in words (read from the source)`() {
        fun body(file: String, start: String, end: String = "\n}\n") = File("src/main/java/app/lyrify/ui/$file").readText().substringAfter(start).substringBefore(end)
        // Where the song's name is one line ("title · artist"), that line keeps its whole width: the length is the next
        // line of the same column, never beside it in a row (Grok review: beside it, it cut the song's name short).
        fun under(body: String, line: String, length: String) {
            assertTrue(line, body.contains(line) && body.contains(length))
            assertFalse(line, listOf("Row(", "weight(", "Text(").any { it in body.substringAfter(line).substringBefore(length) })
            val before = body.substringBefore(line).let { if (line.startsWith("Text(")) it else it.substringBeforeLast("Text(") }
            assertFalse(line, before.trimEnd().lines().last().trim().startsWith("Row("))
        }
        // The postcards (Sent to you, its View all, "You and <name>"): a song's card, small, under the song's line.
        under(body("HuddleParts.kt", "internal fun Postcard("), "Text(title, style = MaterialTheme.typography.bodySmall", "SongLength(lengthMs)")
        assertTrue(body("HuddleParts.kt", "internal fun KeptPostcard(").contains("lengthMs = if (card.kind == CardKind.SONG) card.songs.first().durationMs else 0,"))
        // Listening now's card, and Library's card from a friend: after the artist. Listening now's song lines are
        // cleared for TalkBack, so the card's words above say it; elsewhere SongLength says it itself.
        val friend = body("HuddleParts.kt", "internal fun FriendCard(")
        assertTrue(friend.contains("SongLength(play.song.durationMs, color = BrandLilac.copy(alpha = 0.85f))"))
        assertTrue(friend.contains("listOfNotNull(name, playing, songBy(resources, play.song), length.ifEmpty { null })"))
        assertTrue(body("HuddleEverywhere.kt", "internal fun FromCard(").contains("if (card.kind == CardKind.SONG) SongLength(first.durationMs)"))
        // The card opened: a song's after its artist; an album's or a playlist's songs each under their line, and in
        // the row's words.
        assertTrue(body("HuddleSheets.kt", "private fun ReceivedSheet(", "private fun SentBy(").contains("SongLength(song.durationMs)"))
        val cardRow = body("HuddleSheets.kt", "private fun CardSongRow(")
        under(cardRow, "Text(line, style = MaterialTheme.typography.bodyLarge", "SongLength(song.durationMs)")
        assertTrue(cardRow.contains("listOfNotNull(line, lengthWords(song.durationMs).ifEmpty { null }, words)"))
        // A friend's song: after the artist; while they play, the bar under it ends with it instead.
        assertTrue(body("HuddleSheets.kt", "private fun PeekSheet(", "private fun RowScope.PeekTile(").contains("if (!playing) SongLength(play.song.durationMs)"))
        // What <name> played: each play's line ends with it.
        val plays = body("HuddleHistory.kt", "private fun PlaysPage(")
        assertTrue(plays.contains("lengthMs = play.song.durationMs,"))
        assertTrue(plays.contains("lengthWords(play.song.durationMs).ifEmpty { null },"))
        // The song sent: Send to's head, the invite's ticket and the invite's card, each under the song's line.
        under(
            body("HuddleSheets.kt", "internal fun SendToFriendSheet("),
            "CardKind.SONG -> listOfNotNull(first.title, first.artist).joinToString(\" · \")",
            "if (kind == CardKind.SONG) SongLength(first.durationMs)",
        )
        under(
            body("HuddleInvite.kt", "private fun Ticket("),
            "Text(cardLine(it), style = MaterialTheme.typography.bodyMedium",
            "if (it.kind == CardKind.SONG) SongLength(it.songs.first().durationMs, color = BrandLilac.copy(alpha = 0.8f))",
        )
        under(body("HuddleInvite.kt", "internal fun AcceptInviteSheet("), "Text(cardLine(it), style = MaterialTheme.typography.bodyLarge", "if (it.kind == CardKind.SONG) SongLength(it.songs.first().durationMs)")
    }

    @Test
    fun `the other rows and heads that show a song end the line under its title with the length, said in words (read from the source)`() {
        fun body(file: String, start: String) = File("src/main/java/app/lyrify/ui/$file").readText().substringAfter(start).substringBefore("\n}\n")
        // The line under the title ends with the length: the line's text takes what the length leaves (weighted), the
        // length right after it and unweighted, so it is measured first and never cut or wrapped. SongLength says it in
        // words itself; a row with words of its own says it there too.
        fun ends(body: String, line: String, length: String) {
            assertTrue(line, body.contains(line) && body.contains(length))
            val between = body.substringAfter(line).substringBefore(length)
            assertTrue(line, between.contains("weight(1f") && !between.contains("Text("))
            assertFalse(length, body.substringAfter(length).lineSequence().first().contains("weight("))
        }
        // The lyrics' Songs list, which the song pickers show too (a tone's song, a song for a friend).
        val ledger = body("SongsScreen.kt", "private fun SongRow(entry: LedgerEntry")
        ends(ledger, "Text(artist, ", "SongLength(entry.durationMs, Modifier.padding(start = 8.dp))")
        assertTrue(ledger.contains("lengthWords(entry.durationMs).ifEmpty { null }"))
        // Hidden songs and those kept out of suggestions, the phone's trash, Duplicates' groups.
        ends(body("LibraryHiding.kt", "internal fun HiddenRow("), "line?.let { Text(it, ", "SongLength(song.durationMs)")
        ends(body("TrashScreen.kt", "private fun TrashRow("), "Text(facts, ", "SongLength(song.file.durationMs, Modifier.padding(start = 8.dp))")
        val group = body("DuplicatesScreen.kt", "private fun GroupCard(")
        ends(group, "Text(artist, ", "SongLength(keep.durationMs, Modifier.padding(start = 8.dp))")
        assertTrue(group.contains("listOfNotNull(artist, lengthWords(keep.durationMs).ifEmpty { null }).joinToString(\", \")"))
        // Your listening's top songs. (Insights' redesign has no For you song rows, only a mosaic, and no never played or
        // forgotten cards; its own song rows are checked in InsightsViewTest.)
        val top = body("ListeningScreen.kt", "internal fun TopRowView(")
        ends(top, "Text(line, ", "SongLength(lengthMs, Modifier.padding(start = 8.dp))")
        assertTrue(top.contains("lengthWords(lengthMs).ifEmpty { null }"))
        assertTrue(body("ListeningScreen.kt", "internal fun TopRow(").contains("lengthMs = item.durationMs"))
        // A song's bookmarks, Downloads' rows, and the videos a YouTube search found (Grok review: the length was
        // inside the channel's line, where it could wrap and was read as digits).
        ends(body("BookmarksScreen.kt", "fun BookmarksPage("), "line?.let { Text(it, ", "group.song?.let { SongLength(it.durationMs) }")
        ends(body("DownloadsScreen.kt", "private fun DownloadRow("), "Text(rowLine(context, item, progress), ", "SongLength(item.durationSec * 1_000L, Modifier.padding(start = 8.dp))")
        ends(body("DownloadsScreen.kt", "private fun ChoiceLine("), "if (detail.isNotBlank()) Text(detail, ", "SongLength(lengthMs)")
        val found = body("DownloadsScreen.kt", "private fun SearchFound(")
        assertTrue(found.contains("lengthMs = result.durationSec * 1_000L,"))
        assertFalse(found.contains("rowLength("))
        // Heads that show one song (Grok review): More like this's song, and the tone and cut editors' song.
        ends(body("SuggestionScreens.kt", "private fun MoreLikePage("), "if (line.isNotEmpty()) Text(line, ", "if (seed is Seed.OfSong) SongLength(seed.song.durationMs)")
        ends(body("ToneEditor.kt", "internal fun EditorSongHeader("), "Text(artist, ", "SongLength(durationMs, Modifier.padding(start = 8.dp))")
        assertTrue(File("src/main/java/app/lyrify/ui/ToneEditor.kt").readText().contains("EditorSongHeader(song.path, song.title, artistOf(song), song.durationMs)"))
        assertTrue(File("src/main/java/app/lyrify/ui/CutEditor.kt").readText().contains("?: stringResource(R.string.check_no_artist), song.durationMs)"))
    }

    @Test
    fun `the song sheet's head and Song info say the length in words to TalkBack, as the rows do (read from the source)`() {
        // Grok review: they showed "5:07" and TalkBack read the digits.
        val sheets = File("src/main/java/app/lyrify/ui/SongSheets.kt").readText()
        val head = sheets.substringAfter("private fun SongSheetHeader(").substringBefore("\n}\n")
        assertTrue(head.contains("val words = lengthWords(song.durationMs)"))
        assertTrue(head.substringAfter("stringResource(R.string.lib_after_dot, length),").contains("modifier = Modifier.semantics { contentDescription = words },"))
        assertTrue(sheets.contains("rowLength(song.durationMs)?.let { InfoRow(R.drawable.ic_schedule, R.string.lib_info_length, it, words = lengthWords(song.durationMs)) }"))
        val info = sheets.substringAfter("private fun InfoRow(").substringBefore("\n}\n")
        assertTrue(info.contains("words: String? = null"))
        assertTrue(info.contains("modifier = Modifier.semantics { words?.let { contentDescription = it } },"))
    }

    @Test
    fun `every song's length in the app is written as the player's clock, and the other formatter is gone (read from the source)`() {
        val ui = File("src/main/java/app/lyrify/ui").listFiles().orEmpty().filter { it.extension == "kt" }.associate { it.name to it.readText() }
        // The other one went through DateUtils.formatElapsedTime: "05:07" where the rows say "5:07".
        ui.forEach { (name, text) ->
            assertFalse(name, Regex("""\bsongLength\(""").containsMatchIn(text))
            assertFalse(name, text.contains("formatElapsedTime"))
        }
        // The song sheet's head and Song info show nothing for a length unknown, as the rows do.
        val sheets = ui.getValue("SongSheets.kt")
        assertTrue(sheets.contains("rowLength(song.durationMs)?.let { length ->"))
        assertTrue(sheets.contains("rowLength(song.durationMs)?.let { InfoRow(R.drawable.ic_schedule, R.string.lib_info_length, it, "))
        // A length inside a sentence keeps its place there, in the same format.
        assertTrue(ui.getValue("LyricsSheet.kt").contains("val length = clock(song?.durationMs ?: entry?.durationMs ?: 0)"))
        assertTrue(ui.getValue("CheckScreen.kt").contains("R.string.check_artist_length, artistOf(entry), clock(entry.durationMs)"))
        assertTrue(ui.getValue("CheckScreen.kt").contains("val length = clock(candidate.duration.roundToLong() * MS_PER_SECOND)"))
        assertTrue(ui.getValue("DuplicateSheets.kt").contains("val length = clock(file.durationMs)"))
        // Huddle's "Is this the song?" and a friend's place in their song.
        val huddle = ui.getValue("HuddleSheets.kt")
        assertTrue(huddle.contains("listOfNotNull(song.title, song.artist, song.album, rowLength(song.durationMs))"))
        assertTrue(huddle.contains("Text(clock(at), ") && huddle.contains("Text(clock(play.song.durationMs), "))
        assertTrue(huddle.contains("R.string.huddle_play_along_foot, clock(playAlongMs(play, now)), name"))
        // A video found on YouTube, and a link's.
        val downloads = ui.getValue("DownloadsScreen.kt")
        assertTrue(downloads.contains("lengthMs = result.durationSec * 1_000L,"))
        assertTrue(downloads.contains("info?.let { SongLength(it.durationSec * 1_000L) }"))
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
