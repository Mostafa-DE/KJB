package app.lyrify.dl

import app.lyrify.dl.api.DlInfo
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

    @Test
    fun `only YouTube links have quick info`() {
        assertEquals("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fyoutu.be%2Fabc123", Info.oembedUrl("https://youtu.be/abc123"))
        assertEquals("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fmusic.youtube.com%2Fwatch%3Fv%3Dabc", Info.oembedUrl("https://music.youtube.com/watch?v=abc"))
        assertNull(Info.oembedUrl("https://video.example.com/watch?v=abc"))
        assertNull(Info.oembedUrl("https://notyoutube.com.example.org/v"))
    }
}
