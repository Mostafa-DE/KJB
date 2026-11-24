package app.lyrify.dl

import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlPlaylist
import app.lyrify.dl.api.DlSearchResult
import app.lyrify.dl.api.DlSearchResults
import app.lyrify.dl.api.VideoChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A link's info, as VidGrab read it: yt-dlp's full info (-J) and YouTube's quick oEmbed info. Made-up names only. */
class InfoTest {
    /** A JSON object of [fields]: strings quoted, numbers and arrays (a string starting with "[") as they are. */
    private fun json(vararg fields: Pair<String, Any>): String = fields.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":" + if (value is String && !value.startsWith("[")) "\"$value\"" else value.toString()
    }

    private fun names(info: DlInfo) = info.artist to info.title

    /**
     * The site's own artist and track first; else "A - B" is split only when A is the uploader or the channel (phone
     * session 10: VidGrab's rule split every "A - B", so a trailer named "A - B" got artist A); else the whole title, by
     * the uploader. The names are compared without case, spaces, or a trailing " - Topic", VEVO or Official.
     */
    @Test
    fun `artist and title come from the site's tags, else "Artist - Title" by that artist's own channel, else the whole title by the uploader`() {
        val cases = listOf(
            // The site's own artist and track.
            json("title" to "Paper Lantern", "artist" to "The Quiet Hours", "track" to "Paper Lantern (Remastered)", "uploader" to "The Quiet Hours - Topic") to
                ("The Quiet Hours" to "Paper Lantern (Remastered)"),
            json("title" to "Glass Label Mix - Whatever Else", "artist" to "Mira Solen", "track" to "Paper Harbour", "uploader" to "Glass Label") to ("Mira Solen" to "Paper Harbour"),
            // "Artist - Title" by the artist's own channel: as written, in another case, run together, or with VEVO, " - Topic" or Official after it.
            json("title" to "Saffron Coast \u2013 Low Tide Lights [Official Music Video]", "uploader" to "Saffron Coast") to ("Saffron Coast" to "Low Tide Lights"),
            json("title" to "The Quiet Hours - Paper Lantern (Official Video)", "uploader" to "thequiethoursVEVO") to ("The Quiet Hours" to "Paper Lantern"),
            json("title" to "Mira Solen - Paper Harbour", "uploader" to "MIRA SOLEN - Topic") to ("Mira Solen" to "Paper Harbour"),
            json("title" to "Mira Solen - Paper Harbour", "uploader" to "Mira Solen Official") to ("Mira Solen" to "Paper Harbour"),
            // The channel's name when the uploader is someone else.
            json("title" to "Mira Solen - Paper Harbour", "uploader" to "Harbour Uploads", "channel" to "Mira Solen") to ("Mira Solen" to "Paper Harbour"),
            // Anyone else's "A - B" stays whole, by the uploader.
            json("title" to "Neon Orchard - Glass Tide | Deep House | Free Music", "uploader" to "Free Sounds") to ("Free Sounds" to "Neon Orchard - Glass Tide"),
            json("title" to "Tidewater Films - Behind the Scenes Trailer", "uploader" to "Glass Harbour Studio") to ("Glass Harbour Studio" to "Tidewater Films - Behind the Scenes Trailer"),
            json("title" to "Mira - Paper Harbour", "uploader" to "Mira Solen") to ("Mira Solen" to "Mira - Paper Harbour"),
            json("title" to "Paper Comet - Teaser") to ("" to "Paper Comet - Teaser"),
            // No dash: the whole title, by the uploader without its Topic or VEVO.
            json("title" to "Paper Lantern (Lyrics)", "uploader" to "The Quiet Hours - Topic") to ("The Quiet Hours" to "Paper Lantern"),
            json("title" to "Paper Lantern", "uploader" to "thequiethoursVEVO") to ("thequiethours" to "Paper Lantern"),
        )
        cases.forEach { (text, want) -> assertEquals(text, want, names(Info.full("https://video.example.com/v", text)!!)) }
    }

    /** yt-dlp's split (Python) reads a no-break or narrow no-break space as a space: the sheet does too, on any runtime. */
    @Test
    fun `a name with a no-break or narrow no-break space is the same name, as yt-dlp's split reads it`() {
        val cases = listOf(
            json("title" to "Mira\u00a0Solen - Paper Harbour", "uploader" to "Mira Solen") to ("Mira\u00a0Solen" to "Paper Harbour"),
            json("title" to "Mira Solen - Paper Harbour", "uploader" to "Mira\u202fSolen") to ("Mira Solen" to "Paper Harbour"),
        )
        cases.forEach { (text, want) -> assertEquals(text, want, names(Info.full("https://video.example.com/v", text)!!)) }
    }

    @Test
    fun `the video qualities are the best, then 4K to 480p and the top height, each with video and audio sizes added`() {
        val text = json(
            "title" to "Paper Lantern",
            "uploader" to "The Quiet Hours",
            "duration" to 200,
            "thumbnail" to "https://img.example.com/v.jpg",
            "formats" to "[" + listOf(
                json("vcodec" to "none", "acodec" to "opus", "filesize" to 3_000_000),
                json("vcodec" to "vp9", "acodec" to "none", "height" to 2160, "filesize" to 400_000_000),
                json("vcodec" to "avc1", "acodec" to "none", "height" to 1080, "filesize_approx" to 90_000_000),
                json("vcodec" to "avc1", "acodec" to "none", "height" to 720, "tbr" to 2000.0),
                json("vcodec" to "avc1", "acodec" to "none", "height" to 360, "filesize" to 9_000_000),
                json("vcodec" to "avc1", "acodec" to "none", "height" to 1080, "protocol" to "mhtml", "filesize" to 1),
            ).joinToString(",") + "]",
        )

        val info = Info.full("https://video.example.com/v", text)!!

        assertEquals(200, info.durationSec)
        assertEquals("https://img.example.com/v.jpg", info.thumbnail)
        assertFalse(info.partial)
        assertEquals(
            listOf(
                VideoChoice(null, "4K", 403_000_000),
                VideoChoice(2160, "4K", 403_000_000),
                VideoChoice(1080, "1080p", 93_000_000),
                // No size: kbit/s times the length (2000 * 125 * 200 bytes).
                VideoChoice(720, "720p", 53_000_000),
            ),
            info.choices,
        )
    }

    @Test
    fun `info that is not yt-dlp's JSON is no info`() {
        assertNull(Info.full("https://video.example.com/v", "not json"))
        assertNull(Info.full("https://video.example.com/v", "[]"))
    }

    @Test
    fun `YouTube's oEmbed is the quick, partial info, named as yt-dlp's would be`() {
        val quick = Info.oembed(
            "https://youtu.be/abc123?si=x",
            json("title" to "The Quiet Hours - Paper Lantern (Official Video)", "author_name" to "thequiethoursVEVO", "thumbnail_url" to "https://i.ytimg.com/vi/abc123/hqdefault.jpg"),
        )!!

        assertTrue(quick.partial)
        assertEquals("The Quiet Hours" to "Paper Lantern", names(quick))
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", quick.thumbnail)
        assertEquals(0, quick.durationSec)
        assertEquals(listOf(VideoChoice(null, ""), VideoChoice(1080, "1080p"), VideoChoice(720, "720p"), VideoChoice(480, "480p")), quick.choices)
        assertNull(Info.oembed("https://youtu.be/abc123", json("author_name" to "x")))
    }

    @Test
    fun `oEmbed is asked as KJB-Downloads, its version and KJB's repository`() {
        assertEquals("KJB-Downloads/${BuildConfig.VERSION_NAME} ( https://github.com/Mostafa-DE/KJB )", USER_AGENT)
    }

    /**
     * yt-dlp's answer to `-J --flat-playlist "ytsearch8:..."` (2026.10.26), written by hand after its search extractor
     * (YoutubeTabBaseInfoExtractor._extract_video): a playlist whose entries are the videos as YouTube's search lists them,
     * none of them opened. Made-up names, ids and addresses.
     */
    private val search = """
        {"id": "the quiet hours paper lantern", "title": "the quiet hours paper lantern", "_type": "playlist", "entries": [
          {"_type": "url", "ie_key": "Youtube", "id": "abc123def45", "url": "https://www.youtube.com/watch?v=abc123def45",
           "title": "The Quiet Hours - Paper Lantern (Official Video)", "description": "The new single", "duration": 214.0,
           "channel_id": "UCaaaaaaaaaaaaaaaaaaaaaa", "channel": "The Quiet Hours", "channel_url": "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa",
           "uploader": "The Quiet Hours", "uploader_id": "@thequiethours", "uploader_url": "https://www.youtube.com/@thequiethours",
           "thumbnails": [
             {"url": "https://i.ytimg.com/vi/abc123def45/hq720.jpg?sqp=-oaymwEcCOgCEMoBSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLAaaa", "height": 202, "width": 360},
             {"url": "https://i.ytimg.com/vi/abc123def45/hq720.jpg?sqp=-oaymwEcCNAFEJQDSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLBbbb", "height": 404, "width": 720}
           ],
           "view_count": 1204331, "channel_is_verified": true},
          {"_type": "url", "ie_key": "Youtube", "id": "ghi789jkl01", "url": "https://www.youtube.com/watch?v=ghi789jkl01",
           "title": "Paper Lantern (Lyrics)", "duration": 213.6, "channel": "Lyric Harbour", "uploader": "Lyric Harbour",
           "thumbnails": [{"url": "https://i.ytimg.com/vi/ghi789jkl01/default.jpg", "height": 94, "width": 168}], "view_count": 40211},
          {"_type": "url", "ie_key": "Youtube", "id": "mno234pqr56", "url": "https://www.youtube.com/shorts/mno234pqr56",
           "title": "paper lantern on a rainy night", "duration": 41, "uploader": "Saffron Coast", "view_count": 902},
          {"_type": "url", "ie_key": "Youtube", "id": "stu567vwx89", "url": "https://www.youtube.com/watch?v=stu567vwx89",
           "title": "The Quiet Hours radio 24/7", "channel": "Quiet Radio", "live_status": "is_live", "concurrent_view_count": 312},
          {"_type": "url", "ie_key": "Youtube", "id": "yza890bcd12", "url": "https://www.youtube.com/watch?v=yza890bcd12",
           "title": "Paper Lantern premiere", "channel": "The Quiet Hours", "live_status": "is_upcoming", "release_timestamp": 1800000000},
          {"_type": "url", "ie_key": "Youtube", "id": "efg345hij67", "url": "https://www.youtube.com/watch?v=efg345hij67", "duration": 199},
          {"_type": "url", "ie_key": "Youtube", "id": "klm678nop90", "url": "ytsearch1:paper lantern", "title": "Not a video"},
          {"_type": "url", "ie_key": "Youtube", "id": "qrs901tuv23", "url": "https://www.youtube.com/watch?v=qrs901tuv23",
           "title": "The Quiet Hours - Paper Lantern (Live at Glass Hall)", "duration": 251, "channel": "The Quiet Hours", "live_status": "was_live"}
        ],
        "webpage_url": "ytsearch8:the quiet hours paper lantern", "original_url": "ytsearch8:the quiet hours paper lantern",
        "extractor": "youtube:search", "extractor_key": "YoutubeSearch", "epoch": 1800000000,
        "_version": {"version": "2026.10.26", "current_git_head": null, "release_git_head": "c91f8de0bfb3577894abad716eaaa01659e6bd14", "repository": "yt-dlp/yt-dlp"}}
    """.trimIndent()

    @Test
    fun `a search's videos are told in YouTube's order with their own title, channel, length and a small thumbnail`() {
        val found = Info.results(search)?.results

        assertEquals(
            listOf(
                // The title as YouTube shows it: "(Official Video)", "(Lyrics)" and "(Live ...)" tell uploads apart.
                DlSearchResult(
                    "https://www.youtube.com/watch?v=abc123def45",
                    "The Quiet Hours - Paper Lantern (Official Video)",
                    "The Quiet Hours",
                    214,
                    // The smallest at least 320 wide: sharp in the save sheet's cover, without the big one's bytes.
                    "https://i.ytimg.com/vi/abc123def45/hq720.jpg?sqp=-oaymwEcCOgCEMoBSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLAaaa",
                ),
                // None that wide: the widest there is. The length rounded.
                DlSearchResult("https://www.youtube.com/watch?v=ghi789jkl01", "Paper Lantern (Lyrics)", "Lyric Harbour", 214, "https://i.ytimg.com/vi/ghi789jkl01/default.jpg"),
                // A Short, with the uploader for a channel and no thumbnail.
                DlSearchResult("https://www.youtube.com/shorts/mno234pqr56", "paper lantern on a rainy night", "Saffron Coast", 41, null),
                // A stream that ended is a video like any other; one live now or still to come is left out, as are one
                // with no title and one whose link is not a web address.
                DlSearchResult("https://www.youtube.com/watch?v=qrs901tuv23", "The Quiet Hours - Paper Lantern (Live at Glass Hall)", "The Quiet Hours", 251, null),
            ),
            found,
        )
    }

    @Test
    fun `a search that found nothing is an answer, and anything but a search's list is none`() {
        assertEquals(DlSearchResults(emptyList()), Info.results("""{"id": "zzqx", "title": "zzqx", "_type": "playlist", "entries": [], "extractor": "youtube:search"}"""))
        assertNull(Info.results("not json"))
        assertNull(Info.results("[]"))
        // A video's own info (-J of a link) has no entries.
        assertNull(Info.results(json("title" to "Paper Lantern", "uploader" to "The Quiet Hours", "duration" to 214)))
    }

    @Test
    fun `only YouTube links have quick info`() {
        assertEquals("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fyoutu.be%2Fabc123", Info.oembedUrl("https://youtu.be/abc123"))
        assertEquals("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fmusic.youtube.com%2Fwatch%3Fv%3Dabc", Info.oembedUrl("https://music.youtube.com/watch?v=abc"))
        assertNull(Info.oembedUrl("https://video.example.com/watch?v=abc"))
        assertNull(Info.oembedUrl("https://notyoutube.com.example.org/v"))
    }

    // Playlists (IDlAddon VERSION 2)

    /**
     * yt-dlp 2026.11.23's answer to a lookup of a public playlist's link (`-J --no-playlist --flat-playlist
     * --playlist-items :200`), recorded on 2026-11-24, every name, id and address in it then made up (test resources
     * ytdlp/flat-*.json): the album and the Mix recorded with `--playlist-items :3`, so they stay small.
     */
    private fun recorded(name: String): String = javaClass.getResource("/ytdlp/flat-$name-2026.11.23.json")!!.readText()

    private fun cover(id: String, made: Int) = "https://i.ytimg.com/vi/$id/hqdefault.jpg?sqp=madeup$made"

    private val quickChoices = listOf(VideoChoice(null, ""), VideoChoice(1080, "1080p"), VideoChoice(720, "720p"), VideoChoice(480, "480p"))

    @Test
    fun `a playlist's link is the playlist, its songs listed in its order and none of them opened`() {
        val url = "https://www.youtube.com/playlist?list=PLq7LanternHarbourQuietHours3xYzW"
        val songs = listOf(
            DlSearchResult("https://www.youtube.com/watch?v=ghi789jkl01", "Paper Lantern", "Paper Harbour", 517, cover("ghi789jkl01", 8)),
            DlSearchResult("https://www.youtube.com/watch?v=mno234pqr56", "Low Tide Lights", "Paper Harbour", 363, cover("mno234pqr56", 12)),
            DlSearchResult("https://www.youtube.com/watch?v=stu567vwx89", "Glass Tide", "Paper Harbour", 163, cover("stu567vwx89", 16)),
            DlSearchResult("https://www.youtube.com/watch?v=yza890bcd12", "Neon Orchard", "Paper Harbour", 154, cover("yza890bcd12", 20)),
        )

        assertEquals(
            // Its name and owner, its own cover (the smallest at least 320 wide), its songs' length together, and the quick
            // video choices: no video was opened, so there are no sizes.
            DlInfo(url, "Harbour Nights", "Paper Harbour", 1197, cover("ghi789jkl01", 4), partial = false, choices = quickChoices, playlist = DlPlaylist("Harbour Nights", songs)),
            Info.full(url, recorded("playlist")),
        )
    }

    @Test
    fun `an album longer than what was listed says how many more songs it has, and one with no owner has none`() {
        val url = "https://music.youtube.com/playlist?list=OLAK5uy_m4dEuPpAperHarbourAlbum000000000"

        val album = Info.full(url, recorded("album"))!!

        assertEquals("Album - Paper Harbour (50 Songs)", album.title)
        assertEquals("", album.artist)
        assertEquals(268 + 227 + 239, album.durationSec)
        assertEquals("https://i9.ytimg.com/s_p/OLAK5uy_m4dEuPpAperHarbourAlbum000000000/sddefault.jpg?sqp=madeup2", album.thumbnail)
        val playlist = album.playlist!!
        assertEquals(47, playlist.more)
        assertFalse(playlist.mix)
        // YouTube Music's own links, and the channel as YouTube names it.
        assertEquals(
            listOf(
                DlSearchResult("https://music.youtube.com/watch?v=klm678nop90", "Paper Lantern", "The Quiet Hours - Topic", 268, cover("klm678nop90", 6)),
                DlSearchResult("https://music.youtube.com/watch?v=qrs901tuv23", "Low Tide Lights", "The Quiet Hours - Topic", 227, cover("qrs901tuv23", 10)),
                DlSearchResult("https://music.youtube.com/watch?v=wxy234zab56", "Glass Tide", "The Quiet Hours - Topic", 239, cover("wxy234zab56", 14)),
            ),
            playlist.songs,
        )
    }

    @Test
    fun `a Mix is a playlist with no end, its cover its first song's`() {
        val songs = listOf(
            DlSearchResult("https://www.youtube.com/watch?v=abc123def45", "The Quiet Hours - Paper Lantern (Official Video)", "The Quiet Hours", 214, cover("abc123def45", 4)),
            DlSearchResult("https://www.youtube.com/watch?v=efg345hij67", "Mira Solen - Paper Harbour (Live at Glass Hall, 1985)", "Mira Solen Official", 198, cover("efg345hij67", 8)),
            DlSearchResult("https://www.youtube.com/watch?v=cde456fgh78", "Saffron Coast - Low Tide Lights (Harbour Festival 1979)", "Saffron Coast", 270, cover("cde456fgh78", 12)),
        )

        // No count of its own: nothing more to say than its songs.
        assertEquals(DlPlaylist("Mix - The Quiet Hours - Paper Lantern (Official Video)", songs, more = 0, mix = true), Info.playlist(recorded("mix")))
        assertEquals(cover("abc123def45", 4), Info.full("https://www.youtube.com/watch?v=abc123def45&list=RDabc123def45", recorded("mix"))!!.thumbnail)
    }

    @Test
    fun `a video's own info is no playlist`() {
        val video = json("title" to "Paper Lantern", "uploader" to "The Quiet Hours", "duration" to 214)
        assertNull(Info.playlist(video))
        assertNull(Info.full("https://www.youtube.com/watch?v=abc123def45&list=PLq7", video)!!.playlist)
        assertNull(Info.playlist("not json"))
    }

    @Test
    fun `a playlist's own link has quick info that says so, its songs still to come, and a song's link has none`() {
        val answer = json("title" to "Album - Paper Harbour", "author_name" to "The Quiet Hours - Topic", "thumbnail_url" to "https://i.ytimg.com/vi/klm678nop90/hqdefault.jpg")

        val album = Info.oembed("https://music.youtube.com/playlist?list=OLAK5uy_m4dEuPpAperHarbourAlbum000000000&si=x9", answer)!!
        val song = Info.oembed("https://www.youtube.com/watch?v=abc123def45&list=PLq7LanternHarbourQuietHours3xYzW", answer)!!

        assertTrue(album.partial)
        assertEquals(DlPlaylist("Album - Paper Harbour"), album.playlist)
        assertNull(song.playlist)
    }

    @Test
    fun `a Mix's list starts with RD, but YouTube Music's own playlists, which end`() {
        assertTrue(Info.isMix("RDabc123def45"))
        assertTrue(Info.isMix("RDAMVMabc123def45"))
        assertFalse(Info.isMix("RDCLAK5uy_m4dEuPpAperHarbourPlaylist00"))
        assertFalse(Info.isMix("PLq7"))
        assertFalse(Info.isMix(null))
    }
}
