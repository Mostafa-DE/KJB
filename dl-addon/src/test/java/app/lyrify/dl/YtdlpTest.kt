package app.lyrify.dl

import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        .replace("\\u2013", "\u2013").replace("\\u2014", "\u2014")
        .removeSuffix("\n").split("\n")

    private fun replaced(args: List<String>, from: String, to: List<String>): List<String> {
        val at = args.indexOf(from)
        return args.take(at) + to + args.drop(at + 1)
    }

    /** VidGrab's blind split: every "A - B" became artist A and title B. */
    private val blindSplit = "title:(?P<meta_artist>.+?)\\s+[-\u2013\u2014]\\s+(?P<meta_title>.+)"

    /** KJB's own split, the same "A - B" against the uploader's and the channel's names (compared as [Info] compares them). */
    private val ownSplit: List<String> = run {
        val split = "(?i)^(?P<kjb_key>\\S+) (?P=kjb_key) (?P<meta_artist>.+?)\\s+[-\u2013\u2014]\\s+(?P<meta_title>.+)$"
        listOf(
            "--parse-metadata", "title:(?P<kjb_a>.+?)\\s+[-\u2013\u2014]\\s+.+",
            "--parse-metadata", "%(kjb_a|)s:(?P<kjb_key_a>.*)",
            "--parse-metadata", "%(uploader|)s:(?P<kjb_key_u>.*)",
            "--parse-metadata", "%(channel|)s:(?P<kjb_key_c>.*)",
            "--replace-in-metadata", "kjb_key_a,kjb_key_u,kjb_key_c", "(?i)(\\s*-\\s*topic|\\s*vevo|\\s*official)$|\\s+", "",
            "--parse-metadata", "%(kjb_key_a)s %(kjb_key_u)s %(title)s:$split",
            "--parse-metadata", "%(kjb_key_a)s %(kjb_key_c)s %(title)s:$split",
        )
    }

    /** VidGrab's artist with no uploader, "Unknown" (the sheet's is empty), and its name with an artist always in front. */
    private val unknownArtist = "%(uploader|Unknown)s:%(meta_artist)s"
    private val artistName = "%(meta_artist).80B - %(meta_title).120B.%(ext)s"

    /** KJB's: no uploader leaves the artist empty, and a file without an artist is named by its title alone. */
    private val noArtist = "%(uploader|)s:(?P<meta_artist>.*)"
    private val titleName = "%(meta_artist|).80B%(meta_artist& - |)s%(meta_title).120B.%(ext)s"

    /**
     * VidGrab's tested set with its blind split made KJB's (phone session 10), and no "Unknown" artist (PF10's review).
     * Checked with yt-dlp itself (2025.11.12, the one youtubedl-android ships) on made-up info read with
     * --load-info-json, offline: the cases of InfoTest's naming test come out the same, a name with an artist as before,
     * and one without as its title alone.
     */
    private val kjb: List<String> = tested.indexOf(blindSplit).let { at -> tested.take(at - 1) + ownSplit + tested.drop(at + 1) }
        .map { arg -> mapOf(unknownArtist to noArtist, artistName to titleName)[arg] ?: arg }

    @Test
    fun `MP3 320 with the original language is the arg set VidGrab tested, its "Artist - Title" split made KJB's`() {
        assertEquals(70, tested.size)
        assertEquals("--parse-metadata", tested[tested.indexOf(blindSplit) - 1])
        assertEquals(84, kjb.size)
        assertEquals(kjb, DlArgs.audio(AudioQuality.MP3_320, originalLanguage = true))
    }

    @Test
    fun `with no uploader the artist is left empty, as the sheet leaves it, and the file is named by its title alone`() {
        val audio = DlArgs.audio(AudioQuality.MP3_320, originalLanguage = true)

        assertTrue(audio.none { "Unknown" in it })
        assertEquals(noArtist, audio[audio.indexOf("title:%(meta_title)s") + 2])
        assertEquals(titleName, audio[audio.indexOf("-o") + 1])
    }

    @Test
    fun `the other audio choices change only the format and quality, as VidGrab's do`() {
        val quality = listOf("--audio-format", "mp3", "--audio-quality", "320K", "--ppa", "ExtractAudio+ffmpeg_o:-b:a 320k -ar 44100")
        fun swapped(to: List<String>): List<String> {
            val at = kjb.indexOf("--audio-format")
            assertEquals(quality, kjb.subList(at, at + quality.size))
            return kjb.take(at) + to + kjb.drop(at + quality.size)
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
        assertEquals(replaced(kjb, "lang,abr,asr", listOf("abr,asr")), DlArgs.audio(AudioQuality.MP3_320, originalLanguage = false))
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

        assertEquals("/data/dl/k1/$titleName", audio[audio.indexOf("-o") + 1])
        assertTrue(audio.containsAll(listOf("--write-thumbnail", "--no-mtime", "--newline")))
        val print = audio.indexOf("--print-to-file")
        assertTrue(audio[print + 1].startsWith("after_move:%(.{meta_title,meta_artist,"))
        assertEquals("/data/dl/k1/meta.json", audio[print + 2])
        assertEquals("--", audio.last())
        // The tested set is all there, in its order.
        val start = audio.indexOf("-f")
        assertEquals(kjb.dropLast(1), audio.subList(start, start + kjb.size - 1))

        val video = DlArgs.forRun(DlRequest("k2", "https://video.example.com/v", DlKind.VIDEO, height = 1080), File("/data/dl/k2"))
        assertEquals("/data/dl/k2/%(title).150B.%(ext)s", video[video.indexOf("-o") + 1])
        assertTrue("--write-thumbnail" !in video)
        assertEquals("--", video.last())
    }

    @Test
    fun `a run is one video, so a playlist's link downloads at most its first song, under its own name`() {
        listOf(DlRequest("k1", "https://www.youtube.com/playlist?list=PLq7", DlKind.AUDIO), DlRequest("k2", "https://www.youtube.com/playlist?list=PLq7", DlKind.VIDEO)).forEach { request ->
            val args = DlArgs.forRun(request, File("/data/dl/${request.key}"))
            // A song's link with &list= is the song; any list (a playlist's link, a post of several videos on any site) gives
            // its first item only, so one file is made and its tags are its own. KJB saves a playlist song by song.
            assertTrue(args.toString(), "--no-playlist" in args)
            assertEquals("1", args[args.indexOf("--playlist-items") + 1])
            assertFalse(args.toString(), "--flat-playlist" in args)
            assertEquals("--", args.last())
        }
    }

    @Test
    fun `a lookup lists a playlist's songs without opening them, at most as many as asked, and a song's link is the song or its playlist`() {
        assertEquals(
            listOf("-J", "--no-playlist", "--flat-playlist", "--playlist-items", ":200", "--socket-timeout", "30", "-o", "/data/lookup/4/%(id)s", "--"),
            DlArgs.info("/data/lookup/4/", DlArgs.PLAYLIST_SONGS),
        )
        assertEquals(
            listOf("-J", "--yes-playlist", "--flat-playlist", "--playlist-items", ":50", "--socket-timeout", "30", "-o", "/data/lookup/4/%(id)s", "--"),
            DlArgs.info("/data/lookup/4/", DlArgs.MIX_SONGS, playlist = true),
        )
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
            "ERROR: [youtube] abc: Sign in to confirm you're not a bot" to DlFailure.BLOCKED,
            "ERROR: [youtube] abc: Unable to extract initial player response" to DlFailure.SITE_CHANGED,
            "ERROR: unable to download video data: HTTP Error 403: Forbidden" to DlFailure.SITE_CHANGED,
            "ERROR: Postprocessing: [Errno 28] No space left on device" to DlFailure.NO_ROOM,
            "ERROR: something new" to DlFailure.UNKNOWN,
            "" to DlFailure.UNKNOWN,
        ).forEach { (err, kind) -> assertEquals(err, kind, YtdlpLines.diagnose(err).failure) }
    }

    @Test
    fun `a site that refuses this network or client is blocked, by the pattern that said so and the site's extractor`() {
        mapOf(
            // YouTube's word to a VPN's address, its apostrophe a curly one.
            "ERROR: [youtube] Zq9kXw3Lm0A: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication." to
                YtdlpError(DlFailure.BLOCKED, "sign-in", "youtube"),
            "ERROR: [youtube] Zq9kXw3Lm0A: Please confirm you are not a bot" to YtdlpError(DlFailure.BLOCKED, "not-a-bot", "youtube"),
            "ERROR: [youtube] Zq9kXw3Lm0A: Unable to download API page: HTTP Error 429: Too Many Requests" to YtdlpError(DlFailure.BLOCKED, "429", "youtube"),
            "ERROR: [soundcloud] 1234: too many requests, slow down" to YtdlpError(DlFailure.BLOCKED, "too-many-requests", "soundcloud"),
            "ERROR: [youtube] Zq9kXw3Lm0A: nsig extraction failed: You may experience throttling" to YtdlpError(DlFailure.SITE_CHANGED, "nsig", "youtube"),
            "ERROR: Unsupported URL: https://made-up.example.test/page" to YtdlpError(DlFailure.NOT_FOUND, "unsupported-url", null),
            "ERROR: [generic] something new" to YtdlpError(DlFailure.UNKNOWN, null, "generic"),
        ).forEach { (err, error) -> assertEquals(err, error, YtdlpLines.diagnose(err)) }
    }

    @Test
    fun `a playlist no one but its owner can list is told apart from a video that is gone`() {
        mapOf(
            // Liked music, a private playlist and a deleted one: yt-dlp 2026.11.23's words, YouTube's after "YouTube said".
            "ERROR: [youtube:tab] LM: YouTube said: The playlist does not exist." to YtdlpError(DlFailure.PRIVATE_PLAYLIST, "playlist-missing", "youtube:tab"),
            "ERROR: [youtube:tab] PLq7: YouTube said: This playlist is private." to YtdlpError(DlFailure.PRIVATE_PLAYLIST, "playlist-private", "youtube:tab"),
            "ERROR: [youtube:tab] RDabc123def45: YouTube said: This playlist type is unviewable." to YtdlpError(DlFailure.PRIVATE_PLAYLIST, "playlist-unviewable", "youtube:tab"),
            "ERROR: [youtube:tab] PLq7: The channel/playlist does not exist and the URL redirected to youtube.com home page" to
                YtdlpError(DlFailure.PRIVATE_PLAYLIST, "playlist-missing", "youtube:tab"),
            // A video, still.
            "ERROR: [youtube] abc123def45: Video unavailable. This video does not exist" to YtdlpError(DlFailure.NOT_FOUND, "unavailable", "youtube"),
            "ERROR: [soundcloud] 1234: This track does not exist" to YtdlpError(DlFailure.NOT_FOUND, "not-exist", "soundcloud"),
        ).forEach { (err, error) -> assertEquals(err, error, YtdlpLines.diagnose(err)) }
    }

    @Test
    fun `only ERROR lines say what failed, never a warning before them`() {
        // yt-dlp warns of a slow or refused try, then gives the real reason on its ERROR line.
        val warned = listOf(
            "WARNING: [youtube] Zq9kXw3Lm0A: Sign in to confirm you're not a bot. Retrying (1/3)...",
            "WARNING: [youtube] Zq9kXw3Lm0A: nsig extraction failed: Some formats may be missing",
            "WARNING: [youtube] Unable to download webpage: The read operation timed out. Retrying (2/3)...",
            "WARNING: [youtube] Zq9kXw3Lm0A: HTTP Error 429: Too Many Requests",
        )
        assertEquals(YtdlpError(DlFailure.UNKNOWN), YtdlpLines.diagnose(warned.joinToString("\n")))
        assertEquals(YtdlpError(DlFailure.UNKNOWN, null, "youtube"), YtdlpLines.diagnose((warned + "ERROR: [youtube] Zq9kXw3Lm0A: something new").joinToString("\n")))
        assertEquals(
            YtdlpError(DlFailure.BLOCKED, "429", "youtube"),
            YtdlpLines.diagnose((warned + "ERROR: [youtube] Zq9kXw3Lm0A: HTTP Error 429: Too Many Requests").joinToString("\n")),
        )
        assertEquals(
            YtdlpError(DlFailure.NOT_FOUND, "unavailable", "youtube"),
            YtdlpLines.diagnose((warned + "ERROR: [youtube] Zq9kXw3Lm0A: Video unavailable").joinToString("\r\n")),
        )
        assertEquals(
            YtdlpError(DlFailure.SITE_CHANGED, "format-not-available", "youtube"),
            YtdlpLines.diagnose((warned + "  ERROR: [youtube] Zq9kXw3Lm0A: Requested format is not available").joinToString("\n")),
        )
    }

    @Test
    fun `the log names the kind, the pattern and the extractor only, never the line`() {
        val err = listOf(
            "[youtube] Extracting URL: https://music.example.test/watch?v=Zq9kXw3Lm0A",
            "WARNING: [youtube] Zq9kXw3Lm0A: Lantern Over Quiet Water: nsig extraction failed",
            "ERROR: [youtube] Zq9kXw3Lm0A: Lantern Over Quiet Water: HTTP Error 429: Too Many Requests (https://music.example.test/watch?v=Zq9kXw3Lm0A)",
        ).joinToString("\n")

        val note = YtdlpLines.diagnose(err).note

        assertEquals("BLOCKED, matched 429, extractor youtube", note)
        // An extractor is a name of yt-dlp's own, in small letters: anything else in its place (an id, a path) is left out.
        assertEquals("UNKNOWN", YtdlpLines.diagnose("ERROR: [Zq9kXw3Lm0A] what").note)
        assertEquals("UNKNOWN", YtdlpLines.diagnose("ERROR: [/data/user/0/app.lyrify.dl/cache/dl/k1/Lantern.webm] what").note)
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
