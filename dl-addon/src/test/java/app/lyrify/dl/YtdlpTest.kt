package app.lyrify.dl

import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * yt-dlp's side of a run: the arguments of each quality and format (VidGrab's, checked against the arg set VidGrab verified
 * on the desktop, `mp3-args-tested.txt`), the progress read from its lines, its failures in plain kinds, and the processes
 * a Cancel kills.
 */
class YtdlpTest {
    @get:Rule
    val temp = TemporaryFolder()

    /**
     * VidGrab's mp3-args-tested.txt, one argument per line (empty lines are empty arguments), as the test resource holds it:
     * its en and em dash are written there as \u escapes (KJB's copy rule), read back as the characters here.
     */
    private val tested: List<String> = javaClass.getResource("/mp3-args-tested.txt")!!.readText()
        .replace("\\u2013", "–").replace("\\u2014", "—")
        .removeSuffix("\n").split("\n")

    private fun replaced(args: List<String>, from: String, to: List<String>): List<String> {
        val at = args.indexOf(from)
        return args.take(at) + to + args.drop(at + 1)
    }

    @Test
    fun `MP3 320 with the original language is exactly the arg set VidGrab tested`() {
        assertEquals(70, tested.size)
        assertEquals(tested, DlArgs.audio(AudioQuality.MP3_320, originalLanguage = true))
    }

    @Test
    fun `the other audio choices change only the format and quality, as VidGrab's do`() {
        val quality = listOf("--audio-format", "mp3", "--audio-quality", "320K", "--ppa", "ExtractAudio+ffmpeg_o:-b:a 320k -ar 44100")
        fun swapped(to: List<String>): List<String> {
            val at = tested.indexOf("--audio-format")
            assertEquals(quality, tested.subList(at, at + quality.size))
            return tested.take(at) + to + tested.drop(at + quality.size)
        }

        assertEquals(
            swapped(listOf("--audio-format", "mp3", "--audio-quality", "192K", "--ppa", "ExtractAudio+ffmpeg_o:-b:a 192k -ar 44100")),
            DlArgs.audio(AudioQuality.MP3_192, originalLanguage = true),
        )
        assertEquals(swapped(listOf("--audio-format", "mp3", "--audio-quality", "0", "--ppa", "ExtractAudio+ffmpeg_o:-ar 44100")), DlArgs.audio(AudioQuality.MP3_V0, originalLanguage = true))
        assertEquals(swapped(listOf("--audio-format", "best")), DlArgs.audio(AudioQuality.ORIGINAL, originalLanguage = true))
    }

    @Test
    fun `without the original language, audio sorts by bitrate alone`() {
        assertEquals(replaced(tested, "lang,abr,asr", listOf("abr,asr")), DlArgs.audio(AudioQuality.MP3_320, originalLanguage = false))
    }

    @Test
    fun `video picks the best, or the best up to a height, merged as MP4, preferring the original audio track`() {
        val common = listOf("--no-playlist", "--merge-output-format", "mp4", "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg", "-o", "%(title).150B.%(ext)s")
        assertEquals(
            listOf("-f", "bv*+(ba[format_note*=original]/ba)/b", "-S", "res,fps,vcodec:vp9,acodec:m4a,lang") + common,
            DlArgs.video(height = null, originalLanguage = true),
        )
        assertEquals(
            listOf("-f", "bv*[height<=720]+(ba)/b[height<=720]", "-S", "res,fps,vcodec:vp9,acodec:m4a") + common,
            DlArgs.video(height = 720, originalLanguage = false),
        )
    }

    @Test
    fun `a run writes into its own folder, keeps the cover and the tags it made, and ends its options before the link`() {
        val dir = File("/data/dl/k1")
        val audio = DlArgs.forRun(DlRequest("k1", "https://video.example.com/v", DlKind.AUDIO), dir)

        assertEquals("/data/dl/k1/%(meta_artist).80B - %(meta_title).120B.%(ext)s", audio[audio.indexOf("-o") + 1])
        assertTrue(audio.containsAll(listOf("--write-thumbnail", "--no-mtime", "--newline")))
        val print = audio.indexOf("--print-to-file")
        assertTrue(audio[print + 1].startsWith("after_move:%(.{meta_title,meta_artist,"))
        assertEquals("/data/dl/k1/meta.json", audio[print + 2])
        assertEquals("--", audio.last())
        // The tested set is all there, in its order.
        val start = audio.indexOf("-f")
        assertEquals(tested.dropLast(1), audio.subList(start, start + tested.size - 1))

        val video = DlArgs.forRun(DlRequest("k2", "https://video.example.com/v", DlKind.VIDEO, height = 1080), File("/data/dl/k2"))
        assertEquals("/data/dl/k2/%(title).150B.%(ext)s", video[video.indexOf("-o") + 1])
        assertTrue("--write-thumbnail" !in video)
        assertEquals("--", video.last())
    }

    // Its lines

    @Test
    fun `a download line gives the percent, the time left and the speed`() {
        assertEquals(
            DlProgress(DlStage.DOWNLOADING, 42.5f, 75, "1.20MiB/s"),
            YtdlpLines.progress("[download]  42.5% of   12.34MiB at    1.20MiB/s ETA 01:15", DlStage.STARTING),
        )
        assertEquals(DlProgress(DlStage.DOWNLOADING, 100f), YtdlpLines.progress("[download] 100% of   12.34MiB in 00:00:09 at 1.36MiB/s", DlStage.DOWNLOADING))
    }

    @Test
    fun `each post-processor's line names its stage, and other lines keep the one before`() {
        assertEquals(DlProgress(DlStage.CONVERTING), YtdlpLines.progress("[ExtractAudio] Destination: /data/dl/k1/x.mp3", DlStage.DOWNLOADING))
        assertEquals(DlProgress(DlStage.MERGING), YtdlpLines.progress("[Merger] Merging formats into \"/data/dl/k2/x.mp4\"", DlStage.DOWNLOADING))
        assertEquals(DlProgress(DlStage.TAGGING), YtdlpLines.progress("[EmbedThumbnail] ffmpeg: Adding thumbnail", DlStage.CONVERTING))
        assertEquals(DlProgress(DlStage.TAGGING), YtdlpLines.progress("[Metadata] Adding metadata", DlStage.CONVERTING))
        assertEquals(DlProgress(DlStage.TAGGING), YtdlpLines.progress("[ThumbnailsConvertor] Converting thumbnail", DlStage.DOWNLOADING))
        assertEquals(DlProgress(DlStage.MERGING), YtdlpLines.progress("[info] something else", DlStage.MERGING))
    }

    @Test
    fun `yt-dlp's errors become plain kinds`() {
        mapOf(
            "ERROR: [youtube] abc: Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>" to DlFailure.OFFLINE,
            "ERROR: Unable to download webpage: <urlopen error [Errno 101] Network is unreachable>" to DlFailure.OFFLINE,
            "ERROR: [generic] Unable to download webpage: The read operation timed out" to DlFailure.OFFLINE,
            "ERROR: [youtube] abc: Video unavailable. This video has been removed by the uploader" to DlFailure.NOT_FOUND,
            "ERROR: [youtube] abc: Private video. Sign in if you've been granted access" to DlFailure.NOT_FOUND,
            "ERROR: Unsupported URL: https://example.com/page" to DlFailure.NOT_FOUND,
            "ERROR: [youtube] abc: Sign in to confirm you're not a bot" to DlFailure.SITE_CHANGED,
            "ERROR: [youtube] abc: Unable to extract initial player response" to DlFailure.SITE_CHANGED,
            "ERROR: unable to download video data: HTTP Error 403: Forbidden" to DlFailure.SITE_CHANGED,
            "ERROR: Postprocessing: [Errno 28] No space left on device" to DlFailure.NO_ROOM,
            "ERROR: something new" to DlFailure.UNKNOWN,
            "" to DlFailure.UNKNOWN,
        ).forEach { (err, kind) -> assertEquals(err, kind, YtdlpLines.failure(err)) }
    }

    // The processes a Cancel kills

    private fun process(proc: File, pid: Int, vararg args: String) {
        File(proc, "$pid").apply { mkdirs() }.resolve("cmdline").writeBytes(args.joinToString("\u0000", postfix = "\u0000").toByteArray())
    }

    @Test
    fun `every process whose command line names the run's folder is found, its ffmpeg too, and nothing else`() {
        val proc = temp.newFolder("proc")
        process(proc, 4100, "/data/app/lib/libpython.so", "/data/yt-dlp", "-o", "/data/cache/dl/k1/%(title)s.%(ext)s", "--", "https://video.example.com/v")
        process(proc, 4107, "/data/app/lib/libffmpeg.so", "-i", "/data/cache/dl/k1/x.webm", "/data/cache/dl/k1/x.mp3")
        process(proc, 4200, "/data/app/lib/libpython.so", "/data/yt-dlp", "-o", "/data/cache/dl/k10/%(title)s.%(ext)s")
        process(proc, 4300, "/system/bin/sh")
        File(proc, "self").mkdirs()
        File(proc, "4400").mkdirs() // gone between the listing and the read: no cmdline

        assertEquals(listOf(4100, 4107), ProcTable.matching(proc, "/data/cache/dl/k1/", self = 4300).sorted())
        assertEquals(emptyList<Int>(), ProcTable.matching(proc, "/data/cache/dl/k1/", self = 4100).filter { it == 4100 })
    }

    @Test
    fun `an unreadable proc finds nothing`() {
        assertEquals(emptyList<Int>(), ProcTable.matching(File(temp.root, "missing"), "/dl/k1/", self = 1))
    }

    @Test
    fun `the meta yt-dlp printed reads back as tags, its last line winning`() {
        val meta = temp.newFile("meta.json")
        meta.writeText(
            "{\"meta_title\": \"Old\"}\n" +
                "{\"meta_title\": \"Paper Lantern\", \"meta_artist\": \"The Quiet Hours\", \"meta_album\": \"Paper Lantern\", " +
                "\"meta_album_artist\": \"The Quiet Hours\", \"meta_date\": \"2019\", \"meta_genre\": \"\", \"duration\": 214.6}\n",
        )

        val read = YtdlpLines.meta(meta)

        assertEquals("Paper Lantern", read.first.title)
        assertEquals("The Quiet Hours", read.first.artist)
        assertEquals("2019", read.first.year)
        assertNull(read.first.genre)
        assertEquals(215, read.second)
        assertEquals(null to 0, YtdlpLines.meta(File(temp.root, "none.json")).let { it.first.title to it.second })
    }
}
