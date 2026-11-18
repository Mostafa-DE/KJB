package app.lyrify.cut

import app.lyrify.ai.ArrayMix
import app.lyrify.ai.MixSource
import app.lyrify.data.SharedWrites
import app.lyrify.tags.FRONT_COVER
import app.lyrify.tags.Id3Version
import app.lyrify.tags.SongTags
import app.lyrify.tags.TagFile
import app.lyrify.tags.TagPicture
import app.lyrify.tones.FadeCurve
import app.lyrify.tones.FadeEnvelope
import app.lyrify.tones.ToneAudio
import app.lyrify.tones.ToneEncoder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import kotlin.math.PI
import kotlin.math.sin

/**
 * Saving a cut, on real files in a temporary folder: the song only read, a new file under a free name next to it (or in
 * Music/), its tags from a private copy, nothing left behind by a cancelled or failed save. The audio is a made-up sine
 * wave, the names made up.
 */
class CutFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val rate = ToneAudio.RATE

    /** 3 s of a 440 Hz sine at half volume, both channels. */
    private fun sine(seconds: Int = 3): FloatArray = FloatArray(seconds * rate) { (0.5 * sin(2 * PI * 440 * it / rate)).toFloat() }

    private val cover = TagPicture(byteArrayOf(1, 2, 3), "", FRONT_COVER, "image/jpeg")
    private val original = mapOf(
        "TITLE" to listOf("Paper Lanterns"),
        "ARTIST" to listOf("Kit Calloway"),
        "ALBUM" to listOf("Harbour Glass"),
        "REPLAYGAIN_TRACK_GAIN" to listOf("-6.2 dB"),
    )

    /** TagLib as a list of calls: it reads and writes only files under [work], and marks a written file with "TAGS". */
    private class FakeTags(private val work: File, private val songBytes: ByteArray, private val read: SongTags?) : TagFile {
        val calls = mutableListOf<String>()
        val written = mutableListOf<Pair<Map<String, List<String>>, List<TagPicture>?>>()
        val outside = mutableListOf<File>()
        var results = ArrayDeque(listOf(true))
        var onWrite: () -> Unit = {}

        override fun read(file: File): SongTags? {
            calls += "read ${file.name}"
            if (!file.canonicalPath.startsWith(work.canonicalPath)) outside += file
            // Only a whole copy of the song reads as it.
            return read?.takeIf { file.readBytes().contentEquals(songBytes) }
        }

        override fun write(file: File, fields: Map<String, List<String>>, pictures: List<TagPicture>?, id3: Id3Version): Boolean {
            calls += "write ${file.name}"
            if (!file.canonicalPath.startsWith(work.canonicalPath)) outside += file
            written += fields to pictures
            onWrite()
            val ok = results.removeFirstOrNull() ?: true
            if (ok) file.appendBytes(TAGS)
            return ok
        }
    }

    /** An encoder that writes its PCM to the file it is given, 4 bytes a frame; [failAt] makes that piece throw. */
    private class FileEncoder(fd: FileDescriptor, val bitRate: Int, val codec: String?, private val failAt: Int) : ToneEncoder {
        // Not closed here: the descriptor is the caller's.
        private val out = FileOutputStream(fd)
        val samples = mutableListOf<Short>()
        var pieces = 0
        var onEncode: (Int) -> Unit = {}

        override fun encode(pcm: ShortArray, frames: Int) {
            pieces++
            if (pieces == failAt) throw IOException("disk full")
            val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames * 2) {
                bytes.putShort(pcm[i])
                samples += pcm[i]
            }
            out.write(bytes.array())
            onEncode(pieces)
        }

        override fun finish() = out.flush()

        override fun close() {}
    }

    private inner class Setup(
        val writes: SharedWrites = SharedWrites.ANYWHERE,
        val refused: Set<File> = emptySet(),
        tagsRead: SongTags? = SongTags(original, listOf(cover), 201_000),
        val failAt: Int = 0,
        val source: MixSource = ArrayMix(sine(), piece = 2_000),
        openSong: ((File) -> MixSource)? = null,
        val scanFails: Boolean = false,
        /** Folders that say KJB may write ([refused] aside) but refuse every new file, as a memory card's can. */
        val refusesFiles: Set<File> = emptySet(),
    ) {
        val root: File = folder.root
        val music = File(root, "Music").apply { mkdirs() }
        val album = File(music, "Harbour Glass").apply { mkdirs() }
        val songBytes = ByteArray(4_096) { (it * 31).toByte() }
        val song = File(album, "Paper Lanterns.mp3").apply { writeBytes(songBytes) }
        val work = File(root, "app/files/cut-work")
        val tags = FakeTags(work, songBytes, tagsRead)
        val opened = mutableListOf<File>()
        val encoders = mutableListOf<FileEncoder>()
        val scanned = mutableListOf<File>()
        val logs = mutableListOf<String>()
        var onEncode: (Int) -> Unit = {}
        val files = CutFiles(
            writes = writes,
            work = work,
            tags = tags,
            openSong = openSong ?: { file -> opened += file; source },
            newEncoder = { fd, bitRate, codec -> FileEncoder(fd, bitRate, codec, failAt).also { it.onEncode = onEncode; encoders += it } },
            music = music,
            canWrite = { it.isDirectory && it !in refused },
            scan = { file -> scanned += file; if (scanFails) throw IOException("no scanner") },
            log = { logs += it },
            io = Dispatchers.Unconfined,
            createFile = { file ->
                if (file.parentFile in refusesFiles) throw AccessDeniedException(file.path)
                Files.createFile(file.toPath())
            },
        )

        fun request(range: CutRange = CutRange(1_000, 2_000), fadeIn: Fade = Fade(), fadeOut: Fade = Fade()) = CutRequest(
            songPath = song.path,
            songTitle = "Paper Lanterns",
            songArtist = "Kit Calloway",
            songAlbum = "Harbour Glass",
            range = range,
            fadeIn = fadeIn,
            fadeOut = fadeOut,
            quality = CutQuality.KBPS_256,
            encoder = "c2.android.aac.encoder",
        )

        /** Every file under the root but the song, the cut [kept] and the files a test made before. */
        fun others(vararg kept: File): List<String> = root.walkTopDown().filter { it.isFile && it != song && it !in kept }.map { it.relativeTo(root).path }.toList()
    }

    // Saving

    @Test
    fun `a cut is saved next to its song as a new file, tagged, scanned, and the song is left as it was`() = runBlocking {
        val s = Setup()
        val result = s.files.save(s.request()) {}

        val cut = File(s.album, "Paper Lanterns (cut).m4a")
        assertEquals(CutResult.Saved(cut, tagged = true), result)
        assertArrayEquals(s.songBytes, s.song.readBytes())
        // The window's 44 100 frames, then the tags written into the private copy before it was moved.
        assertEquals(rate * 4L + TAGS.size, cut.length())
        assertArrayEquals(TAGS, cut.readBytes().copyOfRange(rate * 4, rate * 4 + TAGS.size))
        assertEquals(listOf(cut), s.scanned)
        assertEquals(listOf(s.song), s.opened)
        assertEquals(emptyList<String>(), s.others(cut))
    }

    @Test
    fun `the encoder makes the picked quality with the encoder that offers it`() = runBlocking {
        val s = Setup()
        s.files.save(s.request()) {}
        assertEquals(256_000, s.encoders.single().bitRate)
        assertEquals("c2.android.aac.encoder", s.encoders.single().codec)
    }

    @Test
    fun `the cut is the song's part with its fades along their curves`() = runBlocking {
        val s = Setup()
        val range = CutRange(1_000, 2_000)
        val fadeIn = Fade(100, FadeCurve.SMOOTH)
        val fadeOut = Fade(200, FadeCurve.EVEN)
        s.files.save(s.request(range, fadeIn, fadeOut)) {}

        val wave = sine()
        val fade = FadeEnvelope.of(range.window, fadeIn.lengthMs, fadeOut.lengthMs, fadeIn.curve, fadeOut.curve)
        val samples = s.encoders.single().samples
        assertEquals(rate * 2, samples.size)
        for (frame in listOf(0, 1_000, 2_205, 4_410, 20_000, 40_000, 43_000, rate - 1)) {
            val expected = ToneAudio.pcm16(wave[rate + frame] * fade.gain(frame.toLong()))
            assertEquals("frame $frame", expected, samples[2 * frame])
            assertEquals("frame $frame", expected, samples[2 * frame + 1])
        }
        assertEquals(0.toShort(), samples[0])
        assertEquals(0.toShort(), samples[samples.size - 1])
    }

    @Test
    fun `a cut late in a song that can jump there decodes nothing before it, and keeps the song's samples and its fades`() = runBlocking {
        val wave = sine(seconds = 20)
        val source = ArrayMix(wave, piece = 2_000, jumps = true)
        val s = Setup(source = source)
        val range = CutRange(15_000, 17_000)
        val fadeIn = Fade(100, FadeCurve.SMOOTH)
        val fadeOut = Fade(200, FadeCurve.EVEN)
        s.files.save(s.request(range, fadeIn, fadeOut)) {}

        assertEquals(2L * rate, source.handedOut)
        val fade = FadeEnvelope.of(range.window, fadeIn.lengthMs, fadeOut.lengthMs, fadeIn.curve, fadeOut.curve)
        val samples = s.encoders.single().samples
        assertEquals(rate * 4, samples.size)
        for (frame in listOf(0, 1_000, 2_205, 4_410, 40_000, 80_000, 86_000, 2 * rate - 1)) {
            val expected = ToneAudio.pcm16(wave[15 * rate + frame] * fade.gain(frame.toLong()))
            assertEquals("frame $frame", expected, samples[2 * frame])
            assertEquals("frame $frame", expected, samples[2 * frame + 1])
        }
        assertEquals(0.toShort(), samples[0])
        assertEquals(0.toShort(), samples[samples.size - 1])
    }

    @Test
    fun `the cut carries the song's tags and cover, read from a private copy, with its own title`() = runBlocking {
        val s = Setup()
        s.files.save(s.request()) {}

        assertEquals(emptyList<File>(), s.tags.outside)
        assertEquals(listOf("read song.mp3", "write cut.m4a"), s.tags.calls)
        val (fields, pictures) = s.tags.written.single()
        assertEquals(mapOf("TITLE" to listOf("Paper Lanterns (cut)"), "ARTIST" to listOf("Kit Calloway"), "ALBUM" to listOf("Harbour Glass")), fields)
        assertEquals(listOf(cover), pictures)
    }

    @Test
    fun `a song whose tags cannot be read gives the cut the library's title, artist and album`() = runBlocking {
        val s = Setup(tagsRead = null)
        val result = s.files.save(s.request()) {}

        assertTrue(result is CutResult.Saved)
        val (fields, pictures) = s.tags.written.single()
        assertEquals(mapOf("TITLE" to listOf("Paper Lanterns (cut)"), "ARTIST" to listOf("Kit Calloway"), "ALBUM" to listOf("Harbour Glass")), fields)
        assertEquals(emptyList<TagPicture>(), pictures)
    }

    @Test
    fun `tags that do not go in with the cover go in without it, and a cut is saved untagged only when neither does`() = runBlocking {
        val once = Setup().apply { tags.results = ArrayDeque(listOf(false, true)) }
        assertEquals(CutResult.Saved(File(once.album, "Paper Lanterns (cut).m4a"), tagged = true), once.files.save(once.request()) {})
        assertEquals(listOf(listOf(cover), emptyList()), once.tags.written.map { it.second })

        val never = Setup().apply { tags.results = ArrayDeque(listOf(false, false)) }
        File(never.album, "Paper Lanterns (cut).m4a").delete()
        val result = never.files.save(never.request()) {}
        assertEquals(CutResult.Saved(File(never.album, "Paper Lanterns (cut).m4a"), tagged = false), result)
        assertEquals(rate * 4L, File(never.album, "Paper Lanterns (cut).m4a").length())
    }

    @Test
    fun `progress rises to the end`() = runBlocking {
        val s = Setup()
        val heard = mutableListOf<Float>()
        s.files.save(s.request()) { heard += it }
        assertTrue(heard.toString(), heard.isNotEmpty())
        assertEquals(heard.sorted(), heard)
        assertEquals(1f, heard.last())
    }

    // Never over another file

    @Test
    fun `a name already taken is never written over, the next free one is used`() = runBlocking {
        val s = Setup()
        val first = File(s.album, "Paper Lanterns (cut).m4a").apply { writeText("an earlier cut") }
        val second = File(s.album, "Paper Lanterns (cut) (2).m4a").apply { writeText("another") }
        val result = s.files.save(s.request()) {}

        val third = File(s.album, "Paper Lanterns (cut) (3).m4a")
        assertEquals(CutResult.Saved(third, tagged = true), result)
        assertEquals("an earlier cut", first.readText())
        assertEquals("another", second.readText())
        assertEquals(emptyList<String>(), s.others(first, second, third))
    }

    @Test
    fun `a hidden part file someone else left is never replaced or removed`() = runBlocking {
        val s = Setup()
        val stray = File(s.album, ".Paper Lanterns (cut).m4a.part").apply { writeText("not ours") }
        val result = s.files.save(s.request()) {} as CutResult.Saved

        assertEquals("not ours", stray.readText())
        assertEquals(emptyList<String>(), s.others(stray, result.file))
    }

    // What a killed save left

    /** [name] in [dir], last written [daysAgo] days ago. */
    private fun leftOver(dir: File, name: String, daysAgo: Double = 2.0): File = File(dir, name).apply {
        writeText("half a cut")
        setLastModified(System.currentTimeMillis() - (daysAgo * DAY_MS).toLong())
    }

    @Test
    fun `a part file a killed save left over a day ago goes at the next save, beside the song and in Music`() = runBlocking {
        val s = Setup()
        val beside = leftOver(s.album, ".Paper Lanterns (cut).m4a.part")
        val inMusic = leftOver(s.music, ".Night Ferry (cut) (3).m4a.part")
        val result = s.files.save(s.request()) {}

        assertFalse(beside.exists())
        assertFalse(inMusic.exists())
        assertEquals(CutResult.Saved(File(s.album, "Paper Lanterns (cut).m4a"), tagged = true), result)
        assertEquals(emptyList<String>(), s.others((result as CutResult.Saved).file))
    }

    @Test
    fun `opening the cutter sweeps them too`() {
        val s = Setup()
        val beside = leftOver(s.album, ".Tin Roof (cut) (2).m4a.part")
        val inMusic = leftOver(s.music, ".Cut.m4a.part")
        s.files.sweep(s.song.path)

        assertFalse(beside.exists())
        assertFalse(inMusic.exists())
        assertTrue(s.logs.toString(), s.logs.none { "Tin Roof" in it || "/" in it })
    }

    @Test
    fun `only KJB's part names, last written over a day ago, in the cut's folders are swept`() {
        val s = Setup()
        val elsewhere = File(s.music, "Other Album").apply { mkdirs() }
        val kept = listOf(
            // A save writing now, or one from today.
            leftOver(s.album, ".Paper Lanterns (cut).m4a.part", daysAgo = 0.0),
            leftOver(s.album, ".Paper Lanterns (cut) (2).m4a.part", daysAgo = 0.9),
            // Not KJB's part names.
            leftOver(s.album, ".Paper Lanterns.mp3.part"),
            leftOver(s.album, "Paper Lanterns (cut).m4a"),
            leftOver(s.album, ".Paper Lanterns (cut).m4a"),
            leftOver(s.music, ".Paper Lanterns (cut).m4a.part.bak"),
            // A folder no cut of this song goes to.
            leftOver(elsewhere, ".Paper Lanterns (cut).m4a.part"),
        )
        val folderNamedLikeOne = File(s.album, ".Tin Roof (cut).m4a.part").apply { mkdirs(); setLastModified(System.currentTimeMillis() - 2 * DAY_MS) }
        s.files.sweep(s.song.path)

        kept.forEach { assertTrue(it.path, it.exists()) }
        assertTrue(folderNamedLikeOne.isDirectory)
    }

    @Test
    fun `a link named like a part file is never swept, nor what it points to`() {
        val s = Setup()
        val target = leftOver(s.music, "Night Ferry.mp3")
        val link = Files.createSymbolicLink(File(s.album, ".Paper Lanterns (cut).m4a.part").toPath(), target.toPath())
        val old = FileTime.fromMillis(System.currentTimeMillis() - 2 * DAY_MS)
        Files.getFileAttributeView(link, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).setTimes(old, null, null)
        s.files.sweep(s.song.path)

        assertTrue(Files.isSymbolicLink(link))
        assertEquals("half a cut", target.readText())
    }

    @Test
    fun `a debug build sweeps only its test folder`() {
        val s = Setup(writes = SharedWrites(testFolderOnly = true, root = folder.root.toPath()))
        val outside = leftOver(s.album, ".Paper Lanterns (cut).m4a.part")
        s.files.sweep(s.song.path)
        assertTrue(outside.exists())
    }

    // Where it goes

    @Test
    fun `a folder KJB may not write in sends the cut to Music`() = runBlocking {
        val s = Setup(refused = setOf(folder.root.resolve("Music/Harbour Glass")))
        assertEquals(CutFolder.InMusic(s.music), s.files.folder(s.song.path))
        val result = s.files.save(s.request()) {}

        assertEquals(CutResult.Saved(File(s.music, "Paper Lanterns (cut).m4a"), tagged = true), result)
        assertEquals(CutFolder.NextToSong(s.album), Setup().files.folder(s.song.path))
    }

    @Test
    fun `a folder that says KJB may write but refuses the new file sends the cut to Music`() = runBlocking {
        val s = Setup(refusesFiles = setOf(folder.root.resolve("Music/Harbour Glass")))
        assertEquals(CutFolder.NextToSong(s.album), s.files.folder(s.song.path))
        val result = s.files.save(s.request()) {}

        val cut = File(s.music, "Paper Lanterns (cut).m4a")
        assertEquals(CutResult.Saved(cut, tagged = true), result)
        assertArrayEquals(s.songBytes, s.song.readBytes())
        assertEquals(listOf(cut), s.scanned)
        assertEquals(emptyList<String>(), s.others(cut))
        assertFalse(s.logs.toString(), s.logs.any { "Paper" in it || "Harbour" in it || "/" in it })
    }

    @Test
    fun `when Music refuses the new file too, the cut is not saved and nothing is left`() = runBlocking {
        val s = Setup(refusesFiles = setOf(folder.root.resolve("Music/Harbour Glass"), folder.root.resolve("Music")))
        assertEquals(CutResult.Failed(CutFailure.NOT_SAVED), s.files.save(s.request()) {})
        assertEquals(emptyList<String>(), s.others())
        assertEquals(emptyList<File>(), s.scanned)
    }

    @Test
    fun `with nowhere to write, nothing is opened or written`() = runBlocking {
        val s = Setup(refused = setOf(folder.root.resolve("Music/Harbour Glass"), folder.root.resolve("Music")))
        assertEquals(CutFolder.Nowhere, s.files.folder(s.song.path))

        assertEquals(CutResult.Failed(CutFailure.NOWHERE), s.files.save(s.request()) {})
        assertEquals(emptyList<File>(), s.opened)
        assertEquals(emptyList<String>(), s.tags.calls)
        assertEquals(emptyList<String>(), s.others())
    }

    @Test
    fun `a debug build saves a cut only in its test folder`() {
        val fenced = SharedWrites(testFolderOnly = true, root = folder.root.toPath())
        val testMusic = File("/storage/emulated/0/Music/KJB test")
        fun files(music: File) = CutFiles(
            writes = fenced,
            work = File(folder.root, "work"),
            tags = FakeTags(folder.root, ByteArray(0), null),
            openSong = { error("not opened") },
            newEncoder = { _, _, _ -> error("no encoder") },
            music = music,
            canWrite = { true },
            scan = {},
            log = {},
            io = Dispatchers.Unconfined,
        )
        val inTest = "/storage/emulated/0/Music/KJB test/Albums/Paper Lanterns.mp3"
        val outside = "/storage/emulated/0/Music/Harbour Glass/Paper Lanterns.mp3"
        assertEquals(CutFolder.NextToSong(File("/storage/emulated/0/Music/KJB test/Albums")), files(testMusic).folder(inTest))
        assertEquals(CutFolder.InMusic(testMusic), files(testMusic).folder(outside))
        assertEquals(CutFolder.Nowhere, files(File("/storage/emulated/0/Music")).folder(outside))
        runBlocking { assertEquals(CutResult.Failed(CutFailure.NOWHERE), files(File("/storage/emulated/0/Music")).save(Setup().request().copy(songPath = outside)) {}) }
    }

    // Nothing left behind

    @Test
    fun `a cancelled save leaves no file anywhere`() = runBlocking {
        val s = Setup()
        var job: Job? = null
        s.onEncode = { piece -> if (piece == 3) job?.cancel() }
        job = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { s.files.save(s.request(CutRange(0, 3_000))) {} }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), s.others())
        assertEquals(emptyList<File>(), s.scanned)
    }

    @Test
    fun `a save cancelled after its audio is made, while tagging, leaves no file anywhere`() = runBlocking {
        val s = Setup()
        var job: Job? = null
        s.tags.onWrite = { job?.cancel() }
        job = launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) { s.files.save(s.request()) {} }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("read song.mp3", "write cut.m4a"), s.tags.calls)
        assertEquals(emptyList<String>(), s.others())
        assertEquals(emptyList<File>(), s.scanned)
    }

    @Test
    fun `a cut with no free name is not saved, its part file goes and the files there stay`() = runBlocking {
        val s = Setup()
        val taken = (1..100).map { n -> File(s.album, CutNames.fileName("Paper Lanterns (cut)", n)).apply { writeText("taken $n") } }
        assertEquals(CutResult.Failed(CutFailure.NOT_SAVED), s.files.save(s.request()) {})

        taken.forEachIndexed { i, file -> assertEquals("taken ${i + 1}", file.readText()) }
        assertEquals(emptyList<String>(), s.others(*taken.toTypedArray()))
        assertEquals(emptyList<File>(), s.scanned)
    }

    @Test
    fun `a failed encode leaves no file anywhere`() = runBlocking {
        val s = Setup(failAt = 3)
        assertEquals(CutResult.Failed(CutFailure.NOT_SAVED), s.files.save(s.request(CutRange(0, 3_000))) {})
        assertEquals(emptyList<String>(), s.others())
        assertEquals(emptyList<File>(), s.scanned)
    }

    @Test
    fun `a song that cannot be read, or ends before the cut, saves nothing`() = runBlocking {
        val unopened = Setup(openSong = { throw FileNotFoundException("gone") })
        assertEquals(CutResult.Failed(CutFailure.SONG_UNREADABLE), unopened.files.save(unopened.request()) {})
        assertEquals(emptyList<String>(), unopened.others())

        val short = Setup(source = ArrayMix(FloatArray(rate / 2) { 0.5f }))
        File(short.album, "Paper Lanterns (cut).m4a").delete()
        assertEquals(CutResult.Failed(CutFailure.SONG_UNREADABLE), short.files.save(short.request(CutRange(1_000, 2_000))) {})
        assertEquals(emptyList<String>(), short.others())
    }

    @Test
    fun `what an earlier save left in the private folder is cleared first`() = runBlocking {
        val s = Setup()
        val stale = File(s.work.apply { mkdirs() }, "cut.m4a").apply { writeText("from a save the app's death ended") }
        val result = s.files.save(s.request()) {} as CutResult.Saved
        assertFalse(stale.exists())
        assertEquals(emptyList<String>(), s.others(result.file))
    }

    @Test
    fun `a scan that fails still leaves the saved cut, and logs never name the song`() = runBlocking {
        val s = Setup(scanFails = true)
        val result = s.files.save(s.request()) {}
        assertEquals(CutResult.Saved(File(s.album, "Paper Lanterns (cut).m4a"), tagged = true), result)

        val failing = Setup(failAt = 1)
        failing.files.save(failing.request()) {}
        val logs = s.logs + failing.logs
        assertTrue(logs.toString(), logs.isNotEmpty())
        assertFalse(logs.toString(), logs.any { "Paper" in it || "Harbour" in it || "/" in it })
    }

    private companion object {
        val TAGS = "TAGS".toByteArray()
        const val DAY_MS: Long = 24 * 60 * 60 * 1_000L
    }
}
