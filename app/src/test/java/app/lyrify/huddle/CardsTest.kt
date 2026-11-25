package app.lyrify.huddle

import app.lyrify.backup.SongMatcher
import app.lyrify.library.song
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Locale
import kotlin.random.Random

/** What friends send each other: the messages there and back, padded to a few sizes, and a friend's song found here or not. */
class CardsTest {
    private val lantern = CardSong("Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000)
    private val bell = CardSong("Brass Bell", "Kit Calloway", "Harbour Glass", 187_500)

    @Test
    fun `every kind of message comes back as it was sent`() {
        listOf(
            Message.Share("m1", 1_700_000_000_000, Card(CardKind.SONG, listOf(lantern), note = "for the drive")),
            Message.Share("m2", 1_700_000_000_001, Card(CardKind.ALBUM, listOf(lantern, bell), name = "Harbour Glass")),
            Message.Share("m3", 1_700_000_000_002, Card(CardKind.PLAYLIST, listOf(bell, lantern), name = "Sunday", note = "")),
            Message.Share("m4", 1_700_000_000_003, Card(CardKind.SONG, listOf(CardSong("وردة", null, null, 90_000)))),
            Message.Play("m5", 1_700_000_000_004, bell),
            Message.Play("m7", 1_700_000_000_006, lantern, favourite = true),
            Message.Bye("m6", 1_700_000_000_005),
            Message.Profile("m8", 1_700_000_000_007, "Juniper"),
            Message.Profile("m9", 1_700_000_000_008, "Juniper Vale", taken = true),
            Message.Profile("m10", 1_700_000_000_009, "Juniper", self = "AAECAwQFBgcICQoLDA0ODw"),
        ).forEach { message -> assertEquals(message, Messages.decode(Messages.encode(message))) }
    }

    @Test
    fun `a profile carries a name as KJB keeps names, and one without any is dropped`() {
        assertEquals(1024, Messages.encode(Message.Profile("m1", 1, "Juniper", taken = true)).size)
        val spaced = Messages.decode(Messages.encode(Message.Profile("m1", 1, "  June ‮  Vale ")))
        assertEquals(Message.Profile("m1", 1, "June Vale"), spaced)
        assertNull(Messages.decode(Messages.encode(Message.Profile("m1", 1, " ‎ "))))
    }

    @Test
    fun `every message is padded to one of a few sizes, so its length tells little`() {
        assertEquals(1024, Messages.encode(Message.Play("m1", 1, lantern)).size)
        assertEquals(1024, Messages.encode(Message.Bye("m1", 1)).size)
        assertEquals(1024, Messages.encode(Message.Share("m1", 1, Card(CardKind.SONG, listOf(lantern)))).size)
        val fifty = Card(CardKind.PLAYLIST, (1..50).map { lantern.copy(title = "Song $it") }, name = "Long")
        assertEquals(4 * 1024, Messages.encode(Message.Share("m1", 1, fifty)).size)
        val hundred = Card(CardKind.PLAYLIST, (1..100).map { lantern.copy(title = "Song $it") }, name = "Long")
        assertEquals(16 * 1024, Messages.encode(Message.Share("m1", 1, hundred)).size)
    }

    @Test
    fun `a list too long for one message keeps its first songs, as many as fit`() {
        val long = "x".repeat(Messages.TEXT_MAX)
        val huge = Card(CardKind.PLAYLIST, (1..Messages.SONGS_MAX).map { CardSong("$long $it", long, long, 1_000L * it) }, name = "Huge")
        val bytes = Messages.encode(Message.Share("m1", 1, huge))
        assertEquals(48 * 1024, bytes.size)
        val back = Messages.decode(bytes) as Message.Share
        assertTrue(back.card.songs.size in 1 until Messages.SONGS_MAX)
        assertEquals(huge.songs.take(back.card.songs.size), back.card.songs)
    }

    @Test
    fun `a play with long tags of two bytes a character still fits its kilobyte, its tags cut`() {
        val long = "ن".repeat(Messages.TEXT_MAX)
        val play = Message.Play("m1", 1_700_000_000_000, CardSong(long, long, long, 200_000), favourite = true)
        val bytes = Messages.encode(play)
        assertEquals(1024, bytes.size)
        val back = Messages.decode(bytes) as Message.Play
        assertTrue(long.startsWith(back.song.title))
        assertTrue(back.song.title.isNotEmpty())
        assertEquals(200_000L, back.song.durationMs)
        assertTrue(back.favourite)
    }

    @Test
    fun `a now comes back with its state and place, padded to a kilobyte, its long tags cut, and a state of a newer KJB is dropped`() {
        listOf(
            Message.Now("m1", 1_700_000_000_000, lantern, NowState.PLAYING, 72_000, favourite = true),
            Message.Now("m2", 1_700_000_000_001, bell, NowState.PAUSED, 0),
            Message.Now("m3", 1_700_000_000_002, bell, NowState.STOPPED, 187_000),
        ).forEach { now ->
            assertEquals(1024, Messages.encode(now).size)
            assertEquals(now, Messages.decode(Messages.encode(now)))
        }
        val long = "ن".repeat(Messages.TEXT_MAX)
        val back = Messages.decode(Messages.encode(Message.Now("m4", 1, CardSong(long, long, long, 200_000), NowState.PLAYING, 5_000))) as Message.Now
        assertTrue(long.startsWith(back.song.title))
        assertEquals(5_000L, back.positionMs)
        assertNull(Messages.decode("""{"v":1,"i":"m1","t":1,"k":"now","s":{"t":"Paper Lantern","d":1},"e":"rewinding","p":0}""".toByteArray() + byteArrayOf(0x80.toByte())))
        assertNull("no place", Messages.decode("""{"v":1,"i":"m1","t":1,"k":"now","s":{"t":"Paper Lantern","d":1},"e":"paused"}""".toByteArray() + byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `a now with a cover brings it back, every cover in one size that seals within the server's 12 KiB, and a friend's too large reads as none`() {
        val largest = Random(7).nextBytes(Messages.COVER_MAX_BYTES)
        val long = "ن".repeat(Messages.TEXT_MAX)
        val now = Message.Now("m1", 1_700_000_000_000, CardSong(long, long, long, 200_000), NowState.PLAYING, 0, favourite = true, cover = NowCover(largest))
        val bytes = Messages.encode(now)
        assertEquals(10 * 1024, bytes.size)
        assertEquals("a small cover, the same size: its length tells nothing of the picture", 10 * 1024, Messages.encode(now.copy(song = lantern, cover = NowCover(byteArrayOf(1, 2, 3)))).size)
        val back = Messages.decode(bytes) as Message.Now
        assertArrayEquals(largest, back.cover?.jpeg)
        assertEquals("its tags cut as a now's without a cover", (Messages.decode(Messages.encode(now.copy(cover = null))) as Message.Now).song, back.song)
        assertTrue(back.favourite)
        // huddle-server's limit for a now (src/lib/limits.ts, dataBytes.now).
        val sealed = HuddleCrypto.seal(ByteArray(32), bytes, "box".encodeToByteArray(), SecureRandom())
        assertTrue("${sealed.size} bytes sealed", sealed.size <= 12 * 1024)
        // A friend's KJB that sends a cover over the limit, or one that is not base64url: the now reads, without it.
        for (cover in listOf(HuddleCrypto.encode(ByteArray(Messages.COVER_MAX_BYTES + 1)), "not base64!")) {
            val read = Messages.decode("""{"v":1,"i":"m1","t":1,"k":"now","s":{"t":"Paper Lantern","d":1},"e":"playing","p":0,"j":"$cover"}""".toByteArray() + byteArrayOf(0x80.toByte())) as Message.Now
            assertNull(read.cover)
            assertEquals(NowState.PLAYING, read.state)
        }
    }

    @Test
    fun `a now's state is written the same in any phone language, so a Turkish phone's playing reads on every other`() {
        val now = Message.Now("m1", 1_700_000_000_000, lantern, NowState.PLAYING, 72_000)
        val before = Locale.getDefault()
        val sealed = try {
            // Turkish lower-cases "I" to a dotless "ı": "playıng" would read on no other phone.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            Messages.encode(now)
        } finally {
            Locale.setDefault(before)
        }
        assertEquals(now, Messages.decode(sealed))
    }

    @Test
    fun `a title, artist, album, list name or note loses the controls that reorder or hide text`() {
        val tricky = "Paper\u202E Lantern\u0007\u2066"
        val share = Message.Share("m1", 1, Card(CardKind.PLAYLIST, listOf(CardSong(tricky, tricky, tricky, 1_000)), name = tricky, note = tricky))
        val card = (Messages.decode(Messages.encode(share)) as Message.Share).card
        val clean = "Paper Lantern"
        assertEquals(CardSong(clean, clean, clean, 1_000), card.songs.single())
        assertEquals(clean, card.name)
        assertEquals(clean, card.note)
    }

    @Test
    fun `what is not a message of this version is dropped`() {
        assertNull(Messages.decode(ByteArray(0)))
        assertNull(Messages.decode(ByteArray(1024)))
        assertNull(Messages.decode("not json".toByteArray() + byteArrayOf(0x80.toByte())))
        assertNull(Messages.decode("""{"v":1,"i":"m1","t":1,"k":"wave"}""".toByteArray() + byteArrayOf(0x80.toByte())))
        assertNull(Messages.decode("""{"v":9,"i":"m1","t":1,"k":"bye"}""".toByteArray() + byteArrayOf(0x80.toByte())))
        assertNull(Messages.decode("""{"v":1,"i":"m1","t":1,"k":"share","c":{"k":"song","s":[]}}""".toByteArray() + byteArrayOf(0x80.toByte())))
        assertEquals(Message.Bye("m1", 1), Messages.decode("""{"v":1,"i":"m1","t":1,"k":"bye"}""".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0)))
    }

    @Test
    fun `a card from the library keeps the tags, trims long texts, drops a blank note and stops at the most songs`() {
        val one = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "  Paper Lantern ", album = "Harbour Glass", durationMs = 201_000)
        assertEquals(Card(CardKind.SONG, listOf(lantern)), Messages.card(CardKind.SONG, listOf(one), note = "  "))
        val long = song("Music/x.mp3", title = "y".repeat(300), artist = null, album = null)
        val card = Messages.card(CardKind.PLAYLIST, List(Messages.SONGS_MAX + 20) { long }, name = "n".repeat(300), note = "z".repeat(900))
        assertEquals(Messages.SONGS_MAX, card.songs.size)
        assertEquals(Messages.TEXT_MAX, card.songs.first().title.length)
        assertNull(card.songs.first().artist)
        assertEquals(Messages.TEXT_MAX, card.name!!.length)
        assertEquals(Messages.NOTE_MAX, card.note!!.length)
    }

    @Test
    fun `a friend's song is found by its title, artist, album and length, case and accents aside`() {
        val mine = song("Music/HG/01.mp3", title = "paper lantern", artist = "Kit Callowáy", album = "Harbour Glass", durationMs = 202_500)
        val matcher = SongMatcher(listOf(mine, song("Music/HG/02.mp3", title = "Brass Bell", album = "Harbour Glass", durationMs = 187_000)))
        assertEquals(Match.Found(mine), matcher.match(lantern))
    }

    @Test
    fun `on another album, or by another artist, it is only a maybe the user confirms`() {
        val single = song("Music/Singles/Paper Lantern.mp3", title = "Paper Lantern", album = "Singles", durationMs = 200_500)
        assertEquals(Match.Maybe(single), SongMatcher(listOf(single)).match(lantern))
        val cover = song("Music/Covers/Paper Lantern.mp3", title = "Paper Lantern", artist = "Ida Moss", album = "Harbour Glass", durationMs = 201_000)
        assertEquals(Match.Maybe(cover), SongMatcher(listOf(cover)).match(lantern))
    }

    @Test
    fun `never a guess - two songs by others that fit, or nothing alike is missing`() {
        val one = song("Music/A/Paper Lantern.mp3", title = "Paper Lantern", artist = "Ida Moss", album = "A", durationMs = 201_000)
        val two = song("Music/B/Paper Lantern.mp3", title = "Paper Lantern", artist = "Bo Lane", album = "B", durationMs = 201_500)
        assertEquals(Match.Missing, SongMatcher(listOf(one, two)).match(lantern))
        assertEquals(Match.Missing, SongMatcher(listOf(song("Music/HG/02.mp3", title = "Brass Bell"))).match(lantern))
        assertEquals(Match.Missing, SongMatcher(emptyList()).match(lantern))
    }

    @Test
    fun `the song in your library at another length, or in two copies, is offered as a maybe, never YouTube`() {
        // Changed on purpose (user 2026-11-25): another length by the same artist was missing, so only YouTube was offered.
        val longer = song("Music/HG/01.mp3", title = "Paper Lantern", album = "Harbour Glass", durationMs = 240_000)
        assertEquals(Match.Maybe(longer), SongMatcher(listOf(longer)).match(lantern))
        // Of several, the one nearest its length; title and artist case, accents and punctuation aside.
        val near = song("Music/Rips/Paper Lantern.mp3", title = "paper lantern!", artist = "Kit Callowáy", durationMs = 205_000)
        val far = song("Music/Live/Paper Lantern.mp3", title = "Paper Lantern", durationMs = 300_000)
        assertEquals(Match.Maybe(near), SongMatcher(listOf(far, near)).match(lantern))
        // Two copies that fit it all, which "never a guess" left missing.
        val copy = song("Music/HG/01.mp3", title = "Paper Lantern", album = "Harbour Glass", durationMs = 201_000)
        val twin = song("Music/Copies/01.mp3", title = "Paper Lantern", album = "Harbour Glass", durationMs = 201_000)
        assertEquals(Match.Maybe(copy), SongMatcher(listOf(copy, twin)).match(lantern))
        // By someone else, or a friend's song without an artist: still missing, YouTube as before.
        assertEquals(Match.Missing, SongMatcher(listOf(song("Music/X/Paper Lantern.mp3", title = "Paper Lantern", artist = "Ida Moss", durationMs = 300_000))).match(lantern))
        assertEquals(Match.Missing, SongMatcher(listOf(longer)).match(lantern.copy(artist = null)))
    }
}
