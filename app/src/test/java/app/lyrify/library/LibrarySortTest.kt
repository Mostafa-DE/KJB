package app.lyrify.library

import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortAlbums
import app.lyrify.settings.SortArtists
import app.lyrify.settings.SortSongs
import app.lyrify.stats.SongPlays
import app.lyrify.ui.listSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.CollationKey
import java.text.Collator
import java.time.ZoneOffset
import kotlin.math.sign
import kotlin.random.Random

/**
 * The sort keys of every song list, the Albums and the Artists tabs (features 2026-11-15, item 5): each in its first
 * direction and reversed, its ties, songs without a value last either way, and names in natural number order. Made-up
 * names only.
 */
class LibrarySortTest {
    private val settings = LibrarySettings()

    private fun titles(songs: List<Song>): List<String> = songs.map { it.title }

    private fun plays(song: Song, count: Int, last: Long?): Pair<String, SongPlays> = song.path to SongPlays(song.ref, count, last, count * 60_000L)

    // Natural number order

    @Test
    fun `titles and file names go in natural number order, Track 2 before Track 10, either way round`() {
        val songs = listOf("Track 10", "Track 2", "Track 1", "Track 9", "Track 02b").map { song("Music/Mix/$it.mp3", title = it) }

        assertEquals(listOf("Track 1", "Track 2", "Track 02b", "Track 9", "Track 10"), titles(sortSongs(songs, SortSongs.TITLE, settings)))
        assertEquals(listOf("Track 10", "Track 9", "Track 02b", "Track 2", "Track 1"), titles(sortSongs(songs, SortSongs.TITLE, settings, reversed = true)))
        assertEquals(listOf("Track 1", "Track 2", "Track 02b", "Track 9", "Track 10"), titles(sortSongs(songs, SortSongs.FILE_NAME, settings)))
        assertEquals(listOf("Track 10", "Track 9", "Track 02b", "Track 2", "Track 1"), titles(sortSongs(songs, SortSongs.FILE_NAME, settings, reversed = true)))
    }

    @Test
    fun `numbers count by their value in every name order, a folder's files and names with digits first too`() {
        val order = nameOrder(Collator.getInstance())
        assertEquals(listOf("2 Rivers", "10 Rivers", "100 Rivers"), listOf("100 Rivers", "10 Rivers", "2 Rivers").sortedWith(order))
        assertEquals(listOf("Side A 9", "Side A 10", "Side B 1"), listOf("Side B 1", "Side A 10", "Side A 9").sortedWith(order))
        assertEquals(listOf("CD 2", "CD 10"), listOf("CD 10", "CD 2").sortedWith(order))
        val folder = Library.of(listOf(song("Music/Rips/Part 10.mp3"), song("Music/Rips/Part 9.mp3"), song("Music/Rips/Part 1.mp3")), Settings()).folder("Music/Rips")!!
        assertEquals(listOf("Part 1", "Part 9", "Part 10"), titles(folder.songs))
    }

    @Test
    fun `an album's songs without track numbers and an artist's albums of one year go in natural order too`() {
        val ten = song("Music/Vols/b.mp3", title = "Vol. 10", album = "Volumes", artist = "Kit Calloway", year = 2020)
        val two = song("Music/Vols/a.mp3", title = "Vol. 2", album = "Volumes", artist = "Kit Calloway", year = 2020)
        val later = song("Music/Vols 10/a.mp3", title = "Opening", album = "Part 10", artist = "Mira Solen", year = 2021)
        val earlier = song("Music/Vols 2/a.mp3", title = "Closing", album = "Part 2", artist = "Mira Solen", year = 2021)
        val library = Library.of(listOf(ten, two, later, earlier), Settings())

        assertEquals(listOf("Vol. 2", "Vol. 10"), titles(library.album(albumKey(ten)!!)!!.songs))
        assertEquals(listOf("Part 2", "Part 10"), library.artist("Mira Solen")!!.albums.map { it.title })
        assertEquals(listOf("Closing", "Opening"), titles(library.artist("Mira Solen")!!.songs))
    }

    // Each key: first direction, reversed, ties, and no value last

    @Test
    fun `by year the newest first, an album's songs in track order, songs without a year last either way`() {
        val older = song("Music/b/1.mp3", title = "Low Water", album = "Night Ferry", year = 2019, track = 1)
        val olderA = song("Music/a/2.mp3", title = "Tide Bell", album = "Harbour Glass", year = 2019, track = 2)
        val olderB = song("Music/a/1.mp3", title = "Paper Lantern", album = "Harbour Glass", year = 2019, track = 1)
        val newer = song("Music/c/1.mp3", title = "Copper Moon", album = "Singles", year = 2021)
        val none = song("Music/d/1.mp3", title = "Glass Steps", year = null)
        val songs = listOf(none, older, olderA, newer, olderB)

        assertEquals(listOf("Copper Moon", "Paper Lantern", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.YEAR, settings)))
        assertEquals(listOf("Paper Lantern", "Tide Bell", "Low Water", "Copper Moon", "Glass Steps"), titles(sortSongs(songs, SortSongs.YEAR, settings, reversed = true)))
    }

    @Test
    fun `by date added the newest first, songs added together by title, songs without a date last either way`() {
        val songs = listOf(
            song("Music/a.mp3", title = "Tide Bell", addedAt = 300),
            song("Music/b.mp3", title = "Low Water", addedAt = 100),
            song("Music/c.mp3", title = "Glass Steps", addedAt = null),
            song("Music/d.mp3", title = "Copper Moon", addedAt = 300),
        )

        assertEquals(listOf("Copper Moon", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.RECENTLY_ADDED, settings)))
        assertEquals(listOf("Low Water", "Copper Moon", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.RECENTLY_ADDED, settings, reversed = true)))
    }

    @Test
    fun `by play count the most first, equal counts the latest played first, songs never played count none`() {
        val a = song("Music/a.mp3", title = "Tide Bell")
        val b = song("Music/b.mp3", title = "Low Water")
        val c = song("Music/c.mp3", title = "Glass Steps")
        val d = song("Music/d.mp3", title = "Copper Moon")
        val counts = mapOf(plays(a, 5, 10), plays(b, 5, 20), plays(c, 1, 30))
        val songs = listOf(a, b, c, d)

        assertEquals(listOf("Low Water", "Tide Bell", "Glass Steps", "Copper Moon"), titles(sortSongs(songs, SortSongs.PLAY_COUNT, settings, plays = counts)))
        assertEquals(listOf("Copper Moon", "Glass Steps", "Low Water", "Tide Bell"), titles(sortSongs(songs, SortSongs.PLAY_COUNT, settings, reversed = true, plays = counts)))
    }

    @Test
    fun `by last played the latest first, equal times by title, songs never played last either way`() {
        val a = song("Music/a.mp3", title = "Tide Bell")
        val b = song("Music/b.mp3", title = "Low Water")
        val c = song("Music/c.mp3", title = "Glass Steps")
        val d = song("Music/d.mp3", title = "Copper Moon")
        val counts = mapOf(plays(a, 2, 30), plays(b, 1, 10), plays(d, 4, 30))
        val songs = listOf(a, b, c, d)

        assertEquals(listOf("Copper Moon", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.LAST_PLAYED, settings, plays = counts)))
        assertEquals(listOf("Low Water", "Copper Moon", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.LAST_PLAYED, settings, reversed = true, plays = counts)))
    }

    @Test
    fun `by length the longest first, equal lengths by title, a length MediaStore could not read last either way`() {
        val songs = listOf(
            song("Music/a.mp3", title = "Tide Bell", durationMs = 300_000),
            song("Music/b.mp3", title = "Low Water", durationMs = 100_000),
            song("Music/c.mp3", title = "Glass Steps", durationMs = 0),
            song("Music/d.mp3", title = "Copper Moon", durationMs = 300_000),
        )

        assertEquals(listOf("Copper Moon", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.DURATION, settings)))
        assertEquals(listOf("Low Water", "Copper Moon", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.DURATION, settings, reversed = true)))
    }

    @Test
    fun `reversed, artist and album go Z to A with each one's songs still in their order and songs without the tag last`() {
        val lantern = song("Music/a/1.mp3", title = "Paper Lantern", artist = "Kit Calloway", album = "Harbour Glass", year = 2020, track = 1)
        val bell = song("Music/a/2.mp3", title = "Tide Bell", artist = "Kit Calloway", album = "Harbour Glass", year = 2020, track = 2)
        val water = song("Music/b/1.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry", year = 2018, track = 1)
        val steps = song("Music/c/1.mp3", title = "Glass Steps", artist = null, album = null)
        val songs = listOf(steps, bell, water, lantern)

        assertEquals(listOf("Low Water", "Paper Lantern", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.ARTIST, settings, reversed = true)))
        assertEquals(listOf("Low Water", "Paper Lantern", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.ALBUM, settings, reversed = true)))
        assertEquals(listOf("Paper Lantern", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.ALBUM, settings)))
    }

    @Test
    fun `songs with one title keep their path order, either way round`() {
        val first = song("Music/a/Echo.mp3", title = "Echo")
        val second = song("Music/b/Echo.mp3", title = "Echo")
        val other = song("Music/c/Ash.mp3", title = "Ash")

        assertEquals(listOf(other, first, second), sortSongs(listOf(first, other, second), SortSongs.TITLE, settings))
        assertEquals(listOf(first, second, other), sortSongs(listOf(first, other, second), SortSongs.TITLE, settings, reversed = true))
    }

    // Albums and artists

    @Test
    fun `albums sort by when their last song came, their plays, their last play and their length, each either way`() {
        val lantern = song("Music/a/1.mp3", album = "Harbour Glass", artist = "Kit Calloway", addedAt = 100, durationMs = 200_000)
        val bell = song("Music/a/2.mp3", album = "Harbour Glass", artist = "Kit Calloway", addedAt = 500, durationMs = 200_000)
        val water = song("Music/b/1.mp3", album = "Night Ferry", artist = "Mira Solen", addedAt = 300, durationMs = 500_000)
        val moon = song("Music/c/1.mp3", album = "Singles", artist = "Odo Brannick", addedAt = null, durationMs = 0)
        val albums = Library.of(listOf(lantern, bell, water, moon), Settings()).albums
        val counts = mapOf(plays(lantern, 2, 40), plays(bell, 2, 10), plays(water, 3, 20))
        fun names(sort: SortAlbums, reversed: Boolean = false) = sortAlbums(albums, sort, reversed, counts).map { it.title }

        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.RECENTLY_ADDED))
        assertEquals(listOf("Night Ferry", "Harbour Glass", "Singles"), names(SortAlbums.RECENTLY_ADDED, reversed = true))
        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.PLAY_COUNT))
        assertEquals(listOf("Singles", "Night Ferry", "Harbour Glass"), names(SortAlbums.PLAY_COUNT, reversed = true))
        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.LAST_PLAYED))
        assertEquals(listOf("Night Ferry", "Harbour Glass", "Singles"), names(SortAlbums.LAST_PLAYED, reversed = true))
        assertEquals(listOf("Night Ferry", "Harbour Glass", "Singles"), names(SortAlbums.DURATION))
        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.DURATION, reversed = true))
        assertEquals(listOf("Singles", "Night Ferry", "Harbour Glass"), names(SortAlbums.TITLE, reversed = true))
    }

    @Test
    fun `artists sort by when their last song came, their plays and their last play, and the fewest songs first reversed`() {
        val lantern = song("Music/a/1.mp3", artist = "Kit Calloway", addedAt = 100)
        val bell = song("Music/a/2.mp3", artist = "Kit Calloway", addedAt = 500)
        val water = song("Music/b/1.mp3", artist = "Mira Solen", addedAt = 300)
        val moon = song("Music/c/1.mp3", artist = "Odo Brannick", addedAt = null)
        val artists = Library.of(listOf(lantern, bell, water, moon), Settings()).artists
        val counts = mapOf(plays(lantern, 1, 10), plays(bell, 1, 15), plays(water, 3, 20))
        fun names(sort: SortArtists, reversed: Boolean = false) = sortArtists(artists, sort, reversed, counts).map { it.name }

        assertEquals(listOf("Kit Calloway", "Mira Solen", "Odo Brannick"), names(SortArtists.RECENTLY_ADDED))
        assertEquals(listOf("Mira Solen", "Kit Calloway", "Odo Brannick"), names(SortArtists.PLAY_COUNT))
        assertEquals(listOf("Odo Brannick", "Kit Calloway", "Mira Solen"), names(SortArtists.PLAY_COUNT, reversed = true))
        assertEquals(listOf("Mira Solen", "Kit Calloway", "Odo Brannick"), names(SortArtists.LAST_PLAYED))
        assertEquals(listOf("Mira Solen", "Odo Brannick", "Kit Calloway"), names(SortArtists.MOST_SONGS, reversed = true))
        assertEquals(listOf("Odo Brannick", "Mira Solen", "Kit Calloway"), names(SortArtists.NAME, reversed = true))
    }

    // Each name's keys made once (PW1): the same order as before, without the work inside each comparison

    @Test
    fun `a name sort asks the collator for no comparison, and for fewer keys than it makes comparisons`() {
        val names = titles(7_000, Random(42))
        val collator = CountingCollator()
        val order = nameOrder(collator)
        var comparisons = 0

        names.sortedWith { a, b -> comparisons++; order.compare(a, b) }

        assertEquals("collations inside comparisons", 0, collator.compares)
        assertTrue("${collator.keys} keys for $comparisons comparisons of ${names.size} names", collator.keys < comparisons)
    }

    @Test
    fun `every pair of a varied library's names goes in the order it went in before PW1`() {
        val random = Random(7)
        val names = titles(3_000, random) + ODD_NAMES
        val order = nameOrder(Collator.getInstance())
        val before = nameOrderBeforePw1(Collator.getInstance())

        assertEquals(names.sortedWith(before), names.sortedWith(order))
        assertEquals(names.sortedWith(before.reversed()), names.sortedWith(order.reversed()))
        repeat(20_000) {
            val a = names[random.nextInt(names.size)]
            val b = names[random.nextInt(names.size)]
            assertEquals("$a against $b", before.compare(a, b).sign, order.compare(a, b).sign)
        }
    }

    @Test
    fun `songs by title and file name, albums and artists keep their order over a varied library`() {
        val random = Random(11)
        val names = titles(1_500, random)
        val songs = names.mapIndexed { i, name ->
            song("Music/${names[(i * 7) % names.size]}/${name.replace('/', ' ')} $i.mp3", title = name, artist = names[(i * 3) % names.size], album = names[(i * 5) % names.size])
        }
        val before = nameOrderBeforePw1(Collator.getInstance())
        val library = Library.of(songs, Settings())

        assertEquals(songs.sortedWith(compareBy(before) { it.title }), sortSongs(songs, SortSongs.TITLE, settings))
        assertEquals(songs.sortedWith(compareBy(before) { it.fileName }), sortSongs(songs, SortSongs.FILE_NAME, settings))
        assertEquals(library.albums.sortedWith(compareBy(before) { it.title }).map { it.key }, library.albums.map { it.key })
        assertEquals(library.artists.sortedWith(compareBy(before) { it.name }).map { it.name }, library.artists.map { it.name })
    }

    /**
     * 7,000 made-up titles (pf1's SortBench): the name sort as it was before PW1 against the sort now, the A to Z Songs
     * tab and its headings, and the library's grouping. Each time goes to the test's output (pw1-report.md has the
     * numbers); the bound is loose, to catch a step gone quadratic, not to time a phone.
     */
    @Test
    fun `7,000 titles sort in the same order, each step well under a few seconds on the laptop`() {
        val names = titles(7_000, Random(42))
        val songs = names.mapIndexed { i, name -> song("Music/Album ${i / 12}/$i.mp3", title = name, artist = names[(i * 3) % names.size], album = "Album ${i / 12}", track = i % 12 + 1) }
        repeat(3) { names.sortedWith(nameOrderBeforePw1(Collator.getInstance())); sortSongs(songs, SortSongs.TITLE, settings) }
        val times = LinkedHashMap<String, Long>()
        fun median(name: String, work: () -> Unit) {
            val runs = List(5) {
                val start = System.nanoTime()
                work()
                System.nanoTime() - start
            }
            times[name] = runs.sorted()[2] / 1_000_000
        }
        val shuffled = names.shuffled(Random(1))
        median("nameOrder before PW1") { shuffled.sortedWith(nameOrderBeforePw1(Collator.getInstance())) }
        median("nameOrder") { shuffled.sortedWith(nameOrder(Collator.getInstance())) }
        median("Songs by title") { sortSongs(songs.shuffled(Random(2)), SortSongs.TITLE, settings) }
        val sorted = sortSongs(songs, SortSongs.TITLE, settings)
        median("Songs headings") { sorted.forEach { listSection(it, SortSongs.TITLE, settings, 0L, ZoneOffset.UTC) } }
        median("Library.of") { Library.of(songs, Settings()) }
        System.out.println("pw1 name sort (ms, laptop JVM, median of 5): $times")

        assertEquals(shuffled.sortedWith(nameOrderBeforePw1(Collator.getInstance())), shuffled.sortedWith(nameOrder(Collator.getInstance())))
        assertTrue("too slow: $times", times.values.all { it < 5_000 })
    }

    /** The phone's collator, counting what a sort asks of it: comparisons of two names, and keys of one. */
    private class CountingCollator(private val real: Collator = Collator.getInstance()) : Collator() {
        var compares = 0
        var keys = 0

        override fun compare(source: String?, target: String?): Int {
            compares++
            return real.compare(source, target)
        }

        override fun getCollationKey(source: String?): CollationKey? {
            keys++
            return real.getCollationKey(source)
        }

        override fun hashCode(): Int = real.hashCode()
    }

    private companion object {
        /** Names at the edges: blank, only symbols, accents, other scripts, digits of other scripts, leading zeros, case. */
        val ODD_NAMES = listOf(
            "", " ", "!!!", "...", "#1", "#", "(Live)", "'Round Midnight", "\"Quoted\"", "- Dash", "Écho", "echo", "Echo", "ECHO",
            "Ébène 2", "Ebene 10", "Ölüdeniz", "Zebra", "Ångström", "ß", "1", "01", "001", "1a", "10", "2", "02 Side", "2 Side",
            "٣ أغاني", "3 Songs", "١٠ ليال", "أمل", "إيمان", "ا", "باب", "Привет", "Ёлка", "日本", "한국", "Ⅻ Roman", "½ Half", "² Square",
            "Track 2", "Track 02", "Track 10", "Track 2b", "Track 2 b", "track 2", "CD1", "CD 1", "CD 01", "😀 Smile", "Smile 😀", "a1b2c3", "a1b2c3d",
        )

        /** pf1's SortBench titles: Latin and Arabic words, leading punctuation, numbers, "(Live)". */
        fun titles(count: Int, random: Random): List<String> {
            val words = listOf("love", "night", "the", "road", "fire", "heart", "dream", "rain", "city", "light", "home", "blue", "song", "echo", "river", "summer", "time", "wild", "gold", "stone", "Éclat", "über")
            val arabic = listOf("حب", "ليل", "قلب", "حلم", "مطر", "بيت", "نور", "بحر", "وطن", "عيون")
            val lead = listOf("", "", "", "", "(", "'", "\"", "...", "#", "- ", "01 ", "2 ")
            return List(count) {
                val inArabic = random.nextInt(5) == 0
                val title = StringBuilder(lead[random.nextInt(lead.size)])
                repeat(1 + random.nextInt(4)) { w ->
                    if (w > 0) title.append(' ')
                    val word = if (inArabic) arabic[random.nextInt(arabic.size)] else words[random.nextInt(words.size)]
                    title.append(if (w == 0 && !inArabic) word.replaceFirstChar(Char::uppercaseChar) else word)
                }
                if (random.nextInt(6) == 0) title.append(' ').append(1 + random.nextInt(20))
                if (random.nextInt(8) == 0) title.append(" (Live)")
                title.toString()
            }
        }

        /** [nameOrder] as it was before PW1, every comparison working out both names' headings, strip and collation. */
        fun nameOrderBeforePw1(collator: Collator): Comparator<String> = Comparator { a, b ->
            val letterA = nameLetter(a)
            val letterB = nameLetter(b)
            headingRank(letterA).compareTo(headingRank(letterB)).takeIf { it != 0 }
                ?: collator.compare(letterA, letterB).takeIf { it != 0 }
                ?: letterA.compareTo(letterB).takeIf { it != 0 }
                ?: compareNatural(collator, fromFirstLetter(a), fromFirstLetter(b)).takeIf { it != 0 }
                ?: collator.compare(a, b).takeIf { it != 0 }
                ?: a.compareTo(b)
        }

        fun headingRank(letter: String): Int = when {
            letter == "#" -> 0
            Character.UnicodeScript.of(letter.codePointAt(0)) == Character.UnicodeScript.LATIN -> 1
            else -> 2
        }

        fun fromFirstLetter(name: String): String = name.replace(Regex("""^[^\p{L}\p{N}]+"""), "")
    }
}
