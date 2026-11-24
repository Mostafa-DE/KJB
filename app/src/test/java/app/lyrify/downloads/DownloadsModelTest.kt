package app.lyrify.downloads

import app.lyrify.books.Book
import app.lyrify.books.inAudiobooksFolder
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlMeta
import app.lyrify.dupes.AudioFile
import app.lyrify.library.Library
import app.lyrify.library.song
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Downloads model in KJB: shared text to links, the folders, the store, the tags KJB writes, and the duplicate check. */
class DownloadsModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    // Shared text

    @Test
    fun `a shared link is found inside the words around it`() {
        assertEquals(listOf("https://youtu.be/abc123?si=x9"), ShareLinks.parse("Listen to this! https://youtu.be/abc123?si=x9"))
        assertEquals(listOf("https://video.example.com/watch?v=abc"), ShareLinks.parse("(https://video.example.com/watch?v=abc)."))
        assertEquals(listOf("https://video.example.com/v/1"), ShareLinks.parse("\"https://video.example.com/v/1\", shared via an app"))
        assertEquals(listOf("http://short.example/x"), ShareLinks.parse("HTTP://short.example/x isn't it great?").map { it.lowercase() })
        assertEquals(listOf("https://video.example.com/v/2"), ShareLinks.parse("Paper Lantern\nhttps://video.example.com/v/2…"))
    }

    @Test
    fun `several links come in order, each once`() {
        assertEquals(
            listOf("https://video.example.com/a", "https://video.example.com/b"),
            ShareLinks.parse("first https://video.example.com/a then https://video.example.com/b and https://video.example.com/a again"),
        )
    }

    @Test
    fun `text without a usable link gives none`() {
        listOf(null, "", "   ", "no link here", "https://", "http:// spaced.example", "ftp://files.example.com/x", "javascript:alert(1)", "www.example.com/v")
            .forEach { assertEquals("$it", emptyList<String>(), ShareLinks.parse(it)) }
        assertEquals(emptyList<String>(), ShareLinks.parse("https://user:pw@video.example.com/v"))
    }

    @Test
    fun `only a share is read, and only its first few kilobytes`() {
        assertEquals(listOf("https://video.example.com/a"), ShareLinks.fromShare("android.intent.action.SEND", "see https://video.example.com/a", null))
        assertEquals(listOf("https://video.example.com/b"), ShareLinks.fromShare("android.intent.action.SEND", null, "https://video.example.com/b"))
        assertEquals(emptyList<String>(), ShareLinks.fromShare("android.intent.action.VIEW", "https://video.example.com/a", null))
        assertEquals(emptyList<String>(), ShareLinks.fromShare(null, "https://video.example.com/a", null))
        val late = "x".repeat(ShareLinks.MAX_SHARED_CHARS) + " https://video.example.com/late"
        assertEquals(emptyList<String>(), ShareLinks.fromShare("android.intent.action.SEND", late, null))
    }

    // Folders

    @Test
    fun `a saved file takes only an extension KJB knows, else its kind's`() {
        assertEquals("mp3", DownloadFolders.extension("Paper Lantern.MP3", DlKind.AUDIO))
        assertEquals("opus", DownloadFolders.extension("Paper Lantern.opus", DlKind.AUDIO))
        assertEquals("m4a", DownloadFolders.extension("Paper Lantern.m4a", DlKind.AUDIO))
        assertEquals("webm", DownloadFolders.extension("Clip.webm", DlKind.VIDEO))
        assertEquals("mkv", DownloadFolders.extension("Clip.mkv", DlKind.VIDEO))
        assertEquals("mp3", DownloadFolders.extension("Paper Lantern.exe", DlKind.AUDIO))
        assertEquals("mp4", DownloadFolders.extension("Clip", DlKind.VIDEO))
        assertEquals("mp3", DownloadFolders.extension("a.mp3/../../b", DlKind.AUDIO))
    }

    @Test
    fun `a download folder is a plain path in a top folder its kind may use`() {
        assertTrue(DownloadFolders.valid("Music/KJB Downloads", DlKind.AUDIO))
        assertTrue(DownloadFolders.valid("Music/VidGrab", DlKind.AUDIO))
        assertTrue(DownloadFolders.valid("Download/KJB Downloads", DlKind.AUDIO))
        assertTrue(DownloadFolders.valid("Movies/KJB Downloads", DlKind.VIDEO))
        listOf("", "/Music/x", "Music/../Android", "Music/./x", "Movies/x", "Android/data/x", "Music//x", "Music/a:b", "Music/" + "x".repeat(70), "Music/a/b/c/d/e")
            .forEach { assertFalse(it, DownloadFolders.valid(it, DlKind.AUDIO)) }
        assertFalse(DownloadFolders.valid("Music/KJB Downloads", DlKind.VIDEO))
    }

    @Test
    fun `songs are never saved where books are found by their folder alone, so a download is never a book by it`() {
        listOf("Audiobooks/KJB Downloads", "Music/Audiobooks", "Download/audiobooks/New", "Podcasts/AUDIOBOOKS")
            .forEach { assertFalse(it, DownloadFolders.valid(it, DlKind.AUDIO)) }
        assertTrue(DownloadFolders.valid("Podcasts/KJB Downloads", DlKind.AUDIO))
        // A folder stored before this rule reads as the usual one.
        assertEquals(DownloadFolders.AUDIO, DownloadsJson.decode("""{"settings":{"audioFolder":"Audiobooks/Saved"}}""").settings.audioFolder)
        // A song saved in the usual folder is music.
        val saved = song("${DownloadFolders.AUDIO}/Paper Lantern.mp3")
        assertFalse(inAudiobooksFolder(saved.folder))
        assertEquals(emptyList<Book>(), Library.of(listOf(saved), Settings()).books)
    }

    @Test
    fun `a build that changes only its test folder saves every download there`() {
        val settings = DownloadSettings()
        assertEquals("/storage/emulated/0/Music/KJB Downloads", DownloadFolders.absolute(settings, DlKind.AUDIO, "/storage/emulated/0", testFolderOnly = false))
        assertEquals("/storage/emulated/0/Movies/KJB Downloads", DownloadFolders.absolute(settings, DlKind.VIDEO, "/storage/emulated/0", testFolderOnly = false))
        assertEquals("/storage/emulated/0/Music/KJB test/Music/KJB Downloads", DownloadFolders.absolute(settings, DlKind.AUDIO, "/storage/emulated/0", testFolderOnly = true))
        assertEquals("/storage/emulated/0/Music/KJB test/Movies/KJB Downloads", DownloadFolders.absolute(settings, DlKind.VIDEO, "/storage/emulated/0", testFolderOnly = true))
    }

    @Test
    fun `a file's name is the add-on's, made safe and free in its folder`() {
        assertEquals("The Quiet Hours - Paper Lantern.mp3", DownloadFolders.safeName("The Quiet Hours - Paper Lantern.mp3", "mp3"))
        assertEquals("AC_DC - Live_ Paper.mp3", DownloadFolders.safeName("AC/DC - Live: Paper.mp3", "mp3"))
        assertEquals("_.hidden.mp4", DownloadFolders.safeName("../.hidden.mp4", "mp4"))
        assertEquals("download.m4a", DownloadFolders.safeName("   .m4a", "m4a"))
        val folder = temp.newFolder("Music", "KJB Downloads")
        folder.resolve("Paper Lantern.mp3").writeText("x")
        folder.resolve("Paper Lantern (2).mp3").writeText("x")
        assertEquals("Paper Lantern (3).mp3", DownloadFolders.free(folder, "Paper Lantern.mp3").name)
        assertEquals("Glass Tide.mp3", DownloadFolders.free(folder, "Glass Tide.mp3").name)
    }

    // The store

    private val item = DownloadItem(
        key = "d1a2b3",
        url = "https://video.example.com/watch?v=abc",
        kind = DlKind.AUDIO,
        audio = AudioQuality.MP3_320,
        height = null,
        originalLanguage = true,
        title = "Paper Lantern",
        artist = "The Quiet Hours",
        thumbnail = null,
        durationSec = 214,
        addedAt = 1_800_000_000_000,
        state = DownloadState.Queued,
    )

    @Test
    fun `downloads and settings read back as written, a running one as queued again`() {
        val memory = DownloadsMemory(
            settings = DownloadSettings(audioQuality = AudioQuality.ORIGINAL, audioFolder = "Music/VidGrab", warnDuplicates = false),
            items = listOf(
                item,
                item.copy(key = "d2", state = DownloadState.Running),
                item.copy(key = "d3", state = DownloadState.Failed(DownloadFailure.OFFLINE)),
                item.copy(key = "d4", state = DownloadState.Done("/storage/emulated/0/Music/KJB Downloads/Paper Lantern.mp3", 1_800_000_100_000, copyOf = "/storage/emulated/0/Music/old.mp3")),
            ),
        )

        val read = DownloadsJson.decode(DownloadsJson.encode(memory))

        assertEquals(memory.settings, read.settings)
        assertEquals(listOf(DownloadState.Queued, DownloadState.Queued, memory.items[2].state, memory.items[3].state), read.items.map { it.state })
    }

    @Test
    fun `an unreadable item is dropped, an unreadable field reads as its default, and junk is an empty store`() {
        val text = """{"version":1,"settings":{"audioQuality":"FLAC","audioFolder":"../etc","videoFolder":"Movies/Mine"},"items":[{"key":"x"},""" +
            DownloadsJson.encodeItem(item) + "]}"

        val read = DownloadsJson.decode(text)

        assertEquals(AudioQuality.MP3_320, read.settings.audioQuality)
        assertEquals(DownloadSettings().audioFolder, read.settings.audioFolder)
        assertEquals("Movies/Mine", read.settings.videoFolder)
        assertEquals(listOf(item), read.items)
        assertEquals(DownloadsMemory(), DownloadsJson.decode("not json"))
    }

    @Test
    fun `the store keeps every download under way and the newest 200 of the rest`() {
        val done = (1..250).map { item.copy(key = "h$it", addedAt = it.toLong(), state = DownloadState.Done("/x/$it.mp3", it.toLong())) }
        val live = listOf(item.copy(key = "q1", addedAt = 0))

        val kept = DownloadsMemory(items = live + done).capped()

        assertEquals(201, kept.items.size)
        assertTrue(kept.items.any { it.key == "q1" })
        assertTrue(kept.items.none { it.key == "h50" })
        assertTrue(kept.items.any { it.key == "h51" })
    }

    @Test
    fun `the store file survives a save and a reload, and a missing file is an empty store`() {
        val store = DownloadsStore(temp.root.resolve("downloads.json"))
        assertEquals(DownloadsMemory(), store.load())

        assertTrue(store.save(DownloadsMemory(items = listOf(item))))

        assertEquals(listOf(item), DownloadsStore(temp.root.resolve("downloads.json")).load().items)
    }

    @Test
    fun `failures from the add-on read as KJB's own, a site that changed by whether the downloader was updated`() {
        assertEquals(DownloadFailure.SITE_CHANGED, DownloadFailure.of(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = true)))
        assertEquals(DownloadFailure.SITE_CHANGED_NEWEST, DownloadFailure.of(DlEnd.Failed(DlFailure.SITE_CHANGED, updated = false)))
        assertEquals(DownloadFailure.BLOCKED, DownloadFailure.of(DlEnd.Failed(DlFailure.BLOCKED)))
        assertEquals(DownloadFailure.BLOCKED, DownloadFailure.of(DlEnd.Failed(DlFailure.BLOCKED, updated = true)))
        assertEquals(DownloadFailure.UNKNOWN, DownloadFailure.of(DlEnd.Failed(DlFailure.REFUSED)))
        assertEquals(DownloadFailure.NO_ROOM, DownloadFailure.of(DlEnd.Failed(DlFailure.NO_ROOM, updated = true)))
    }

    @Test
    fun `a playlist's own link an older KJB's download still holds, private or gone, reads as nothing found`() {
        assertEquals(DownloadFailure.NOT_FOUND, DownloadFailure.of(DlEnd.Failed(DlFailure.PRIVATE_PLAYLIST)))
    }

    @Test
    fun `a download that failed before KJB told a site that changed by the update still reads with the updated words`() {
        val stored = DownloadsJson.encodeItem(item.copy(state = DownloadState.Failed(DownloadFailure.SITE_CHANGED)))

        assertEquals(DownloadState.Failed(DownloadFailure.SITE_CHANGED), DownloadsJson.decode("""{"version":1,"items":[$stored]}""").items.single().state)
    }

    // The tags KJB writes

    @Test
    fun `KJB writes yt-dlp's tags, the sheet's names where yt-dlp made none, and a year only when it is one`() {
        val file = DlFile("x.mp3", 10, meta = DlMeta(title = "Paper Lantern", artist = "The Quiet Hours", album = "Paper Lantern", albumArtist = "The Quiet Hours", year = "2019", genre = "Folk"))

        assertEquals(
            mapOf(
                "TITLE" to listOf("Paper Lantern"),
                "ARTIST" to listOf("The Quiet Hours"),
                "ALBUM" to listOf("Paper Lantern"),
                "ALBUMARTIST" to listOf("The Quiet Hours"),
                "DATE" to listOf("2019"),
                "GENRE" to listOf("Folk"),
            ),
            DownloadTags.fields(item.copy(title = "Other", artist = "Someone"), file),
        )
        assertEquals(
            mapOf("TITLE" to listOf("Glass Tide"), "ARTIST" to listOf("Neon Orchard")),
            DownloadTags.fields(item.copy(title = "Glass Tide", artist = "Neon Orchard"), file.copy(meta = DlMeta(year = "20190314"))),
        )
    }

    // Duplicates

    private fun song(id: Long, title: String, artist: String?, seconds: Int) =
        AudioFile(id, "/storage/emulated/0/Music/$id.mp3", "Music/", "$id.mp3", title, artist, null, seconds * 1000L, 5_000_000)

    @Test
    fun `a song already on the phone is found by KJB's Duplicates rules, length within two seconds`() {
        val library = listOf(song(1, "Paper Lantern", "The Quiet Hours", 214), song(2, "Glass Tide", "Neon Orchard", 180))

        assertEquals(1L, DuplicateCheck.copyOf(DuplicateCheck.Candidate("Paper Lantern", "The Quiet Hours", 215_000), library)?.id)
        assertEquals(1L, DuplicateCheck.copyOf(DuplicateCheck.Candidate("Paper Lantern (Official Video)", "The Quiet Hours", 213_500), library)?.id)
        assertNull(DuplicateCheck.copyOf(DuplicateCheck.Candidate("Paper Lantern", "The Quiet Hours", 300_000), library))
        assertNull(DuplicateCheck.copyOf(DuplicateCheck.Candidate("Paper Lantern (Live)", "The Quiet Hours", 214_000), library))
        // Quick info has no length yet: nothing to compare.
        assertNull(DuplicateCheck.copyOf(DuplicateCheck.Candidate("Paper Lantern", "The Quiet Hours", 0), library))
    }

    @Test
    fun `a saved download is not its own copy`() {
        val saved = song(9, "Paper Lantern", "The Quiet Hours", 214)

        assertNull(DuplicateCheck.copyOf(saved, listOf(saved, song(2, "Glass Tide", "Neon Orchard", 180))))
        assertEquals(1L, DuplicateCheck.copyOf(saved, listOf(saved, song(1, "Paper Lantern", "The Quiet Hours", 213)))?.id)
    }
}
