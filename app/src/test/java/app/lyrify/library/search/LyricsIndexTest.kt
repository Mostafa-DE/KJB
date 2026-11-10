package app.lyrify.library.search

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The lyrics index: `.lrc` lines without their timestamps and tags, lines found with their time, kept up to date by modified time. Made-up lyrics only. */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsIndexTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern")
    private val tide = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell")
    private val ember = song("Music/Night Ferry/Ember Road.flac", title = "Ember Road", artist = "Mira Okafor")

    private val files = FakeLrcFiles()
    private val store = FakeLyricsIndexStore()
    private val logs = mutableListOf<String>()
    private val settings = LibrarySettings()

    private fun TestScope.index(): LyricsIndex = LyricsIndex(store, files, io = StandardTestDispatcher(testScheduler)) { logs += it }

    private fun library(vararg songs: Song): Library = Library.of(songs.toList(), Settings())

    private fun lrc(song: Song): String = song.path.substringBeforeLast('.') + ".lrc"

    private val lanternLyrics = """
        [ar:Kit Calloway]
        [ti:Paper Lantern]
        [00:01.50]Paper boats on the river
        [00:05.00]
        [00:07.25]Lanterns low and bright
        [00:12.00]Hold the lantern, hold the line
    """.trimIndent()

    private val emberLyrics = "[00:02.00]Ember on the water\n[00:04.00]Paper skies above the road\n"

    // Reading a .lrc

    @Test
    fun `timestamps and tags are taken off, each line keeping its first time`() {
        val lines = lrcLines(
            "﻿[ar:Kit Calloway]\r\n[offset:+120]\r\n[00:07.25][01:10.5]Lanterns <00:08.00>low and bright\r\n" +
                "[00:01.50]Paper boats on the river\r\n[00:05.00]\r\n[00:03.5]Hold the line\r\n[00:20.00]LANTERNS low, and bright!\r\n",
        )

        assertEquals(
            listOf(1_500L to "Paper boats on the river", 3_500L to "Hold the line", 7_250L to "Lanterns low and bright"),
            lines.map { it.timeMs to it.text },
        )
        assertEquals("paper boats on the river", lines.first().folded)
    }

    @Test
    fun `a timestamp's fraction is read as LRC means it`() {
        val lines = lrcLines("[00:01]First\n[00:02.5]Second\n[00:03.05]Third\n[00:04.005]Fourth\n[00:05:50]Fifth\n[02:03.4]Sixth\n")

        assertEquals(listOf(1_000L, 2_500L, 3_050L, 4_005L, 5_500L, 123_400L), lines.map { it.timeMs })
    }

    @Test
    fun `plain lyrics keep their order and have no time`() {
        val lines = lrcLines("First made-up line\n\n[Chorus]\nSecond made-up line\nfirst made up line\n♪ ♪\n")

        assertEquals(listOf(null to "First made-up line", null to "Second made-up line"), lines.map { it.timeMs to it.text })
    }

    // Finding lines

    @Test
    fun `a song is found by a line, with the line's time`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        index.sync(listOf(lantern, tide))

        val hits = index.find("lanterns LO", library(lantern, tide), settings)

        assertEquals(listOf(LyricHit(lantern, "Paper Lantern", "Lanterns low and bright", 7_250, Match(MatchKind.WORD_START, 0, 11))), hits)
    }

    @Test
    fun `the first line that matches is the one shown`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        index.sync(listOf(lantern))

        val hit = index.find("lantern", library(lantern), settings).single()

        assertEquals("Lanterns low and bright", hit.line)
        assertEquals(7_250L, hit.timeMs)
    }

    @Test
    fun `lyrics match at the start of words, and a phrase within one line`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        index.sync(listOf(lantern))
        val library = library(lantern)

        assertTrue(index.find("anterns", library, settings).isEmpty())
        assertTrue(index.find("bright hold", library, settings).isEmpty())
        assertEquals("Paper boats on the river", index.find("the riv", library, settings).single().line)
        assertTrue(index.find(" ?! ", library, settings).isEmpty())
    }

    @Test
    fun `only songs the library shows are found, by their titles as shown`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        files.put(lrc(ember), 100, emberLyrics)
        val index = index()
        index.sync(listOf(lantern, ember))

        assertEquals(listOf(ember, lantern), index.find("paper", library(lantern, ember), settings).map { it.song })
        assertEquals(listOf(lantern), index.find("paper", library(lantern), settings).map { it.song })
        val names = index.find("paper", library(lantern, ember), LibrarySettings(showFileNames = true))
        assertEquals(listOf("01 Paper Lantern.mp3", "Ember Road.flac"), names.map { it.title })
    }

    @Test
    fun `lines in other scripts are found, and words MATCH reads as its syntax find a line or nothing`() = runTest {
        files.put(lrc(lantern), 100, "[00:01.00]Ο φάρος μετρά τα κύματα\n[00:02.00]灯台が波を数える\n[00:03.00]Near the harbour or the hill\n")
        val index = index()
        index.sync(listOf(lantern))
        val library = library(lantern)

        assertEquals("Ο φάρος μετρά τα κύματα", index.find("φαρος μετ", library, settings).single().line)
        assertEquals("灯台が波を数える", index.find("灯台", library, settings).single().line)
        assertEquals("Near the harbour or the hill", index.find("OR", library, settings).single().line)
        assertEquals("Near the harbour or the hill", index.find("NEAR", library, settings).single().line)
        for (syntax in listOf("\"", "*", "(", ")", "'", "-", "\"hold", "NEAR/2", "\"\"")) {
            assertEquals(syntax, emptyList<LyricHit>(), index.find(syntax, library, settings))
        }
    }

    // Keeping up to date

    @Test
    fun `an unchanged lrc is not read again`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        index.sync(listOf(lantern, tide))
        index.sync(listOf(lantern, tide))

        assertEquals(listOf(lrc(lantern)), files.reads)
    }

    @Test
    fun `a changed lrc is read again`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        index.sync(listOf(lantern))
        files.put(lrc(lantern), 200, "[00:03.00]Harbour lights are calling\n")
        index.sync(listOf(lantern))

        assertTrue(index.find("lantern", library(lantern), settings).isEmpty())
        assertEquals(3_000L, index.find("harbour lights", library(lantern), settings).single().timeMs)
        assertEquals(mapOf(lantern.path to 200L), store.indexed())
    }

    @Test
    fun `a removed lrc leaves the index`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        index.sync(listOf(lantern))
        files.remove(lrc(lantern))
        index.sync(listOf(lantern))

        assertTrue(index.find("paper", library(lantern), settings).isEmpty())
        assertEquals(emptyMap<String, Long>(), store.indexed())
    }

    @Test
    fun `a song that left the library leaves the index`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        files.put(lrc(ember), 100, emberLyrics)
        val index = index()
        index.sync(listOf(lantern, ember))
        index.sync(listOf(ember))

        assertEquals(setOf(ember.path), store.indexed().keys)
    }

    @Test
    fun `a folder that cannot be listed keeps its songs as they were`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        index.sync(listOf(lantern))
        files.unlistable += VOLUME + "Music/Harbour Glass"
        files.put(lrc(lantern), 200, "[00:03.00]Harbour lights are calling\n")
        index.sync(listOf(lantern))

        assertEquals(mapOf(lantern.path to 100L), store.indexed())
        assertEquals(1, files.reads.size)
    }

    @Test
    fun `an lrc that cannot be read is tried again the next time`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        files.unreadable += lrc(lantern)
        val index = index()
        index.sync(listOf(lantern))
        assertEquals(emptyMap<String, Long>(), store.indexed())

        files.unreadable.clear()
        index.sync(listOf(lantern))
        assertEquals(mapOf(lantern.path to 100L), store.indexed())
    }

    @Test
    fun `an lrc that changes while it is read waits for the next sync`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        // Written again while it is read: what was read may be half of either.
        files.onRead = { files.put(lrc(lantern), 200, "[00:03.00]Harbour lights are calling\n") }
        val index = index()
        index.sync(listOf(lantern))
        assertEquals(emptyMap<String, Long>(), store.indexed())

        files.onRead = {}
        index.sync(listOf(lantern))
        assertEquals(mapOf(lantern.path to 200L), store.indexed())
        assertEquals(3_000L, index.find("harbour lights", library(lantern), settings).single().timeMs)
    }

    @Test
    fun `the version moves when the index changes, and it is building while files are read`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        var buildingWhileRead = false
        files.onRead = { buildingWhileRead = index.building.value }
        assertEquals(0, index.version.value)

        index.sync(listOf(lantern))
        val changed = index.version.value
        assertTrue(changed > 0)
        assertTrue(buildingWhileRead)
        assertFalse(index.building.value)

        index.sync(listOf(lantern))
        assertEquals(changed, index.version.value)
        index.sync(emptyList())
        assertTrue(index.version.value > changed)
    }

    @Test
    fun `a failing store finds nothing, and the log names the exception only`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        val index = index()
        store.failing = true

        index.sync(listOf(lantern))
        assertTrue(index.find("paper", library(lantern), settings).isEmpty())
        assertFalse(index.building.value)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.toString(), logs.all { "IOException" in it && "disk" !in it })
    }

    @Test
    fun `the log never holds lyrics, titles or paths`() = runTest {
        files.put(lrc(lantern), 100, lanternLyrics)
        files.put(lrc(ember), 100, emberLyrics)
        val index = index()
        index.sync(listOf(lantern, ember, tide))
        index.find("paper", library(lantern, ember, tide), settings)
        files.remove(lrc(ember))
        index.sync(listOf(lantern, tide))

        assertTrue(logs.isNotEmpty())
        val words = listOf("paper", "lantern", "river", "ember", "harbour", "tide", "music", "storage", ".lrc", "kit", "mira")
        assertTrue(logs.toString(), logs.none { line -> words.any { line.contains(it, ignoreCase = true) } })
    }
}

/** `.lrc` files made up, by path with a modified time; a folder can be made unlistable and a file unreadable. Records the reads. */
private class FakeLrcFiles : LrcFiles {
    private val texts = mutableMapOf<String, Pair<Long, String>>()
    val unlistable = mutableSetOf<String>()
    val unreadable = mutableSetOf<String>()
    val reads = mutableListOf<String>()
    var onRead: () -> Unit = {}

    fun put(path: String, modified: Long, text: String) {
        texts[path] = modified to text
    }

    fun remove(path: String) {
        texts.remove(path)
    }

    override fun list(folder: String): Map<String, Long>? = if (folder in unlistable) {
        null
    } else {
        texts.filterKeys { it.substringBeforeLast('/') == folder }.entries.associate { (path, file) -> path.substringAfterLast('/') to file.first }
    }

    override fun read(path: String): String? {
        reads += path
        onRead()
        return if (path in unreadable) null else texts[path]?.second
    }

    override fun modified(path: String): Long? = texts[path]?.first
}

/**
 * The index in memory: a line matches as FTS4's phrase query does, whole words in a row with the last as a prefix, and
 * the words are folded again first, as the SQLite store does.
 */
private class FakeLyricsIndexStore : LyricsIndexStore {
    private val songs = linkedMapOf<String, IndexedSong>()
    var failing = false

    override fun indexed(): Map<String, Long> = checked { songs.mapValues { it.value.modifiedAt } }

    override fun put(songs: List<IndexedSong>) = checked { songs.forEach { this.songs[it.path] = it } }

    override fun remove(songs: Collection<String>) = checked { songs.forEach { this.songs.remove(it) } }

    override suspend fun find(words: List<String>): List<StoredLine> = checked {
        val phrase = " " + fold(words.joinToString(" "))
        if (phrase.isBlank()) return@checked emptyList()
        songs.values.mapNotNull { song ->
            song.lines.firstOrNull { (" " + it.folded).contains(phrase) }?.let { StoredLine(song.path, it.timeMs, it.text) }
        }
    }

    private fun <T> checked(work: () -> T): T {
        if (failing) throw IOException("disk full")
        return work()
    }
}
