package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.VERSION
import app.lyrify.backup.BackupFixtures.archive
import app.lyrify.backup.BackupFixtures.bell
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.entries
import app.lyrify.backup.BackupFixtures.keeperOne
import app.lyrify.backup.BackupFixtures.keeperTwo
import app.lyrify.backup.BackupFixtures.lantern
import app.lyrify.backup.BackupFixtures.read
import app.lyrify.backup.BackupFixtures.water
import app.lyrify.backup.BackupFixtures.zip
import app.lyrify.backup.BackupFixtures.zipOf
import app.lyrify.data.LedgerState
import app.lyrify.library.SongRef
import app.lyrify.player.AbPart
import app.lyrify.player.Repeat
import app.lyrify.player.Speed
import app.lyrify.settings.BackupSettings
import app.lyrify.settings.ConnectDevice
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import app.lyrify.tags.Settled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.zip.ZipEntry

/** The backup zip: each section there and back, what never goes in, and every way a picked file can be wrong. Made-up names only. */
class BackupBundleTest {
    private fun noId(ref: SongRef) = SongRef(ref.path)

    // A round trip per section

    @Test
    fun `playlists and Favourites come back as they were, without MediaStore ids`() {
        val read = checkNotNull(contents().playlists)

        val playlists = BackupFixtures.playlists
        assertEquals(playlists.playlists.map { it.copy(songs = it.songs.map(::noId)) }, read.playlists)
        assertEquals(playlists.favouriteSongs.map(::noId), read.favouriteSongs)
        assertEquals(playlists.favouriteCollections, read.favouriteCollections)
    }

    @Test
    fun `the queues come back as they were, the one playing at its song and second, with Repeat`() {
        val read = checkNotNull(contents().queues)

        val expected = BackupFixtures.queues.copy(queues = BackupFixtures.queues.queues.map { queue -> queue.copy(items = queue.items.map { it.copy(song = noId(it.song)) }) })
        assertEquals(expected, read)
        assertEquals(Repeat.ALL, contents().repeat)
    }

    @Test
    fun `plays come back as they were logged`() {
        assertEquals(BackupFixtures.plays.map { it.copy(song = noId(it.song)) }, contents().plays)
    }

    @Test
    fun `bookmarks, places and speeds come back, with the usual speed and the part repeating`() {
        assertEquals(
            listOf(
                BackupMarks(water.path, 600_000, emptyList(), Speed(0.75f)),
                BackupMarks(lantern.path, null, listOf(BackupBookmark(30_000, "Chorus"), BackupBookmark(95_000, ""))),
            ),
            contents().bookmarks,
        )
        assertEquals(Speed(1.25f), contents().usualSpeed)
        assertEquals(AbPart(lantern.path, 30_000, 60_000), contents().part)
    }

    @Test
    fun `tones, Duplicates choices, Covers & tags and recent searches come back, by paths, never by MediaStore ids`() {
        val read = contents()
        val tone = BackupFixtures.tone

        assertEquals(listOf(BackupTone(tone.name, tone.kind, tone.songPath, tone.startMs, tone.lengthMs, tone.fadeInMs, tone.fadeOutMs, tone.createdAt, tone.fileName, tone.size)), read.tones)
        assertEquals(BackupDuplicates(notDuplicates = listOf(listOf(lantern.path, bell.path)), reviewed = listOf(listOf(bell.path, water.path))), read.duplicates)
        assertEquals(
            BackupCovers(
                albums = listOf(
                    BackupAlbum(Settled.DONE, "Harbour Glass", "Kit Calloway", listOf(lantern.path, bell.path), T0 + 5, changed = 2, problems = emptyList()),
                    BackupAlbum(Settled.SKIPPED, "Night Ferry", "Mira Solen", listOf(water.path), T0 + 6, changed = 0, problems = emptyList()),
                ),
                checkedOnce = true,
                noted = true,
            ),
            read.covers,
        )
        assertEquals(BackupFixtures.searches, read.searches)
        assertEquals(BackupFixtures.keptApart, read.names)
    }

    @Test
    fun `books come back with their places, speeds, finished and marks, never the chapters, which are read again from the files`() {
        val bytes = zip()
        val file = entries(bytes).firstOrNull { it.first == "books.json" }?.second
        val manifest = Json.parseToJsonElement(entries(bytes).first { it.first == BackupBundle.MANIFEST }.second).jsonObject

        assertEquals(BackupFixtures.booksKept, contents(bytes).books)
        assertNotNull("no books.json", file)
        assertFalse(file!!, "chapters" in file)
        assertTrue("books" in (manifest["sections"] as JsonObject).keys)
        // A backup without them (an earlier KJB's) reads as no books, as with the other sections.
        assertNull(contents(zip(BackupFixtures.snapshot.copy(books = null))).books)
    }

    @Test
    fun `equalizer profiles come back by output and device name, with the own presets`() {
        assertEquals(BackupFixtures.equalizer, contents().equalizer)
    }

    @Test
    fun `lyrics choices come back, without a write that was cut short`() {
        assertEquals(BackupFixtures.ledger.entries, contents().lyrics?.entries)
    }

    @Test
    fun `settings come back with the AI switch, without Settings, Backup, which belongs to the phone`() {
        assertEquals(BackupFixtures.settings.copy(backup = BackupSettings()), contents().settings)
        assertEquals(true, contents().aiLyrics)
    }

    @Test
    fun `Start when connected keeps only its devices' names, as Android links each device to one phone`() {
        val devices = listOf(ConnectDevice(4, "Harbour Buds"), ConnectDevice(9, "Car Kit"))
        val settings = BackupFixtures.settings.copy(playback = BackupFixtures.settings.playback.copy(connectDevices = devices))
        val read = contents(zip(BackupFixtures.snapshot.copy(settings = settings)))

        assertEquals(emptyList<ConnectDevice>(), read.settings?.playback?.connectDevices)
        assertEquals(listOf("Harbour Buds", "Car Kit"), read.connectNames)
    }

    @Test
    fun `the songs the sections name come with their tags, so another phone can find them`() {
        val songs = contents().songs.associateBy { it.path }

        // The books' files and the songs marked as books too.
        assertEquals(setOf(lantern.path, bell.path, water.path, archive.path, keeperOne.path, keeperTwo.path), songs.keys)
        assertEquals(BackupSong(water.path, "Low Water", "Mira Solen", "Night Ferry", 240_000, "Music/Night Ferry/01 Low Water.mp3", 4_000_000), songs[water.path])
        assertEquals(T0, contents().createdAt)
        assertEquals(VERSION, contents().appVersion)
    }

    @Test
    fun `a store that could not be read leaves its section out`() {
        val read = contents(zip(BackupFixtures.snapshot.copy(plays = null, equalizer = null)))

        assertEquals(Section.entries - Section.PLAYS - Section.EQUALIZER, read.sections)
        assertFalse(entries(zip(BackupFixtures.snapshot.copy(plays = null))).any { it.first == Section.PLAYS.file })
    }

    @Test
    fun `the file is named after the day it was made`() {
        assertEquals("KJB backup 2026-11-10.zip", BackupBundle.fileName(LocalDate.of(2026, 11, 10)))
    }

    // Never in a backup

    @Test
    fun `the zip holds the manifest, the songs and one file per section, nothing else`() {
        assertEquals(listOf(BackupBundle.MANIFEST, BackupBundle.SONGS) + Section.entries.map { it.file }, entries(zip()).map { it.first })
    }

    @Test
    fun `the manifest says the format, the KJB version and when, and nothing about the phone`() {
        val text = entries(zip()).firstOrNull { it.first == BackupBundle.MANIFEST }?.second
        assertNotNull("no manifest", text)
        val manifest = Json.parseToJsonElement(text!!).jsonObject

        assertEquals(setOf("format", "appVersion", "createdAt", "songs", "sections"), manifest.keys)
        assertEquals(JsonPrimitive(BackupBundle.FORMAT), manifest["format"])
        assertEquals(JsonPrimitive(VERSION), manifest["appVersion"])
        assertEquals(Section.entries.map { it.key }.toSet(), (manifest["sections"] as JsonObject).keys)
    }

    @Test
    fun `no MediaStore id, folder, lyrics text or Bluetooth address goes in`() {
        val text = entries(zip()).joinToString("\n") { it.second }

        assertFalse(text, "mediaStoreId" in text)
        assertFalse(text, "content://" in text)
        // The stores' forms name these fields, empty.
        assertFalse(text, "\"folderUri\":\"" in text)
        assertFalse(text, "A made-up line" in text)
        assertFalse(text, "\"pendingText\":\"" in text)
        assertFalse(text, Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").containsMatchIn(text))
        // Nor a tone's id, nor the Undo Covers & tags keeps on the phone.
        assertFalse(text, "\"id\":501" in text)
        assertFalse(text, "entryId" in text)
        assertTrue(text, "Harbour Buds" in text)
    }

    // Newer and older backups

    @Test
    fun `unknown keys are ignored, in the manifest and in a section`() {
        val changed = entries(zip()).map { (name, text) ->
            val json = Json.parseToJsonElement(text).jsonObject
            name to JsonObject(json + ("addedLater" to JsonPrimitive("x"))).toString().encodeToByteArray()
        }

        val read = contents(zipOf(changed))
        // The settings are kept as stored, to be read over the phone's own: read so, they are the same.
        assertEquals(contents().copy(settingsStored = null), read.copy(settingsStored = null))
        val phone = Settings(library = LibrarySettings(showFileNames = true))
        assertEquals(SettingsJson.decode(contents().settingsStored!!, base = phone), SettingsJson.decode(read.settingsStored!!, base = phone))
    }

    @Test
    fun `a section this version does not know is skipped and counted`() {
        val withWidgets = withManifest(zip()) { sections -> sections + ("widgets" to JsonObject(mapOf("version" to JsonPrimitive(1)))) }
        val read = contents(zipOf(entriesOf(withWidgets) + ("widgets.json" to "{}".encodeToByteArray())))

        assertEquals(1, read.unknown)
        assertEquals(Section.entries, read.sections)
    }

    @Test
    fun `a section newer than this version is skipped with its name, even if it would not read`() {
        val newer = withManifest(zip()) { sections -> sections + ("plays" to JsonObject(mapOf("version" to JsonPrimitive(Section.PLAYS.version + 1)))) }
        val read = contents(zipOf(entriesOf(newer).map { (name, data) -> name to if (name == Section.PLAYS.file) "[not plays".encodeToByteArray() else data }))

        assertEquals(listOf(Section.PLAYS), read.tooNew)
        assertNull(read.plays)
        assertEquals(Section.entries - Section.PLAYS, read.sections)
    }

    @Test
    fun `a newer songs table still gives the songs this version can read`() {
        val newer = zipOf(
            entriesOf(zip()).map { (name, data) ->
                if (name != BackupBundle.MANIFEST) return@map name to data
                val manifest = Json.parseToJsonElement(data.decodeToString()).jsonObject
                name to JsonObject(manifest + ("songs" to JsonObject(mapOf("version" to JsonPrimitive(BackupBundle.SONGS_VERSION + 1))))).toString().encodeToByteArray()
            },
        )

        assertEquals(contents().songs, contents(newer).songs)
    }

    @Test
    fun `JSON nested deeper than any backup's makes it unreadable, never a crash`() {
        val depth = 200_000
        val deep = "{\"plays\":" + "[".repeat(depth) + "]".repeat(depth) + "}"
        val bytes = zipOf(entriesOf(zip()).map { (name, data) -> name to if (name == Section.PLAYS.file) deep.encodeToByteArray() else data })

        assertEquals(BackupRead.Unreadable(BackupRead.Why.NOT_A_BACKUP), read(bytes))
    }

    @Test
    fun `entries the manifest does not list, and entries with odd names, are never read`() {
        val extra = entriesOf(zip()) + ("../../notes.json" to "{}".encodeToByteArray()) + ("/settings.json" to "[]".encodeToByteArray())

        assertEquals(contents(), contents(zipOf(extra)))
    }

    // Files that are not a backup

    @Test
    fun `a file that is no zip, or a zip without a manifest, is unreadable`() {
        assertTrue(read(ByteArray(4_000) { (it * 31).toByte() }) is BackupRead.Unreadable)
        assertTrue(read(ByteArray(0)) is BackupRead.Unreadable)
        assertTrue(read(zipOf(listOf("notes.txt" to "hello".encodeToByteArray()))) is BackupRead.Unreadable)
        assertTrue(read(zipOf(entriesOf(zip()).filterNot { it.first == BackupBundle.MANIFEST })) is BackupRead.Unreadable)
    }

    @Test
    fun `a damaged entry or a section that is not what it says makes it unreadable`() {
        val bytes = zip()
        // A byte inside the lyrics' packed data.
        val name = String(bytes, Charsets.ISO_8859_1).indexOf(Section.LYRICS.file)
        assertTrue("no lyrics entry", name >= 0)
        val at = name + Section.LYRICS.file.length + 20
        val damaged = bytes.copyOf().also { it[at] = (it[at] + 1).toByte() }
        val notPlaylists = entriesOf(bytes).map { (name, data) -> name to if (name == Section.PLAYLISTS.file) "[1, 2]".encodeToByteArray() else data }

        assertTrue(read(damaged) is BackupRead.Unreadable)
        assertEquals(BackupRead.Unreadable(BackupRead.Why.NOT_A_BACKUP), read(zipOf(notPlaylists)))
    }

    @Test
    fun `a section the manifest lists but the zip lacks makes it unreadable`() {
        assertEquals(BackupRead.Unreadable(BackupRead.Why.NOT_A_BACKUP), read(zipOf(entriesOf(zip()).filterNot { it.first == Section.LYRICS.file })))
    }

    @Test
    fun `a zip cut short anywhere before its directory is unreadable`() {
        val bytes = zip()
        val directory = (0 until bytes.size - 3).firstOrNull { bytes[it] == 'P'.code.toByte() && bytes[it + 1] == 'K'.code.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        assertNotNull("no zip directory", directory)

        for (cut in 0 until directory!!) assertTrue("cut at $cut of $directory", read(bytes.copyOf(cut)) is BackupRead.Unreadable)
    }

    @Test
    fun `an entry bigger unpacked than its cap stops the read, packed or stored`() {
        val big = ByteArray(2_048) { ' '.code.toByte() }
        val limits = ZipLimits(entryBytes = 1_024, totalBytes = 100_000)

        // An entry not read counts as much as one read.
        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(zipOf(listOf("notes.bin" to big)), limits))
        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(zipOf(listOf("plays.json" to big)), limits))
        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(zipOf(listOf("plays.json" to big), ZipEntry.STORED), limits))
    }

    @Test
    fun `all entries together past their cap stop the read, those not read too`() {
        val part = ByteArray(800) { ' '.code.toByte() }
        val limits = ZipLimits(entryBytes = 1_024, totalBytes = 2_000)

        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(zipOf(listOf("a.bin" to part, "b.bin" to part, "c.bin" to part)), limits))
        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(zipOf(List(ZipLimits().entries + 1) { "n$it" to ByteArray(0) })))
    }

    @Test
    fun `a zip bomb is stopped at the real caps`() {
        val bomb = zipOf(listOf(Section.PLAYS.file to ByteArray((ZipLimits().entryBytes + 1).toInt())))

        assertTrue("${bomb.size} bytes packed", bomb.size < 200_000)
        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(bomb))
    }

    @Test
    fun `two entries of one name make it unreadable`() {
        val twin = "playsXjson"
        val bytes = zipOf(entriesOf(zip()) + (twin to "{}".encodeToByteArray()))
        val patched = String(bytes, Charsets.ISO_8859_1).replace(twin, Section.PLAYS.file).toByteArray(Charsets.ISO_8859_1)

        assertEquals(BackupRead.Unreadable(BackupRead.Why.DUPLICATE), read(patched))
    }

    @Test
    fun `an empty backup reads as one with nothing in it`() {
        val read = contents(zip(BackupSnapshot()))

        assertEquals(emptyList<Section>(), read.sections)
        assertEquals(emptyList<BackupSong>(), read.songs)
        assertEquals(LedgerState(), contents(zip(BackupSnapshot(ledger = LedgerState()))).lyrics)
    }

    private fun entriesOf(bytes: ByteArray): List<Pair<String, ByteArray>> = entries(bytes).map { (name, text) -> name to text.encodeToByteArray() }

    /** [bytes] with the manifest's sections changed by [change]. */
    private fun withManifest(bytes: ByteArray, change: (Map<String, JsonObject>) -> Map<String, JsonObject>): ByteArray = zipOf(
        entriesOf(bytes).map { (name, data) ->
            if (name != BackupBundle.MANIFEST) return@map name to data
            val manifest = Json.parseToJsonElement(data.decodeToString()).jsonObject
            val sections = (manifest["sections"] as JsonObject).mapValues { it.value.jsonObject }
            name to JsonObject(manifest + ("sections" to JsonObject(change(sections)))).toString().encodeToByteArray()
        },
    )
}
