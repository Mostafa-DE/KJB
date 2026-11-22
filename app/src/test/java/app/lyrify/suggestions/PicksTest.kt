package app.lyrify.suggestions

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.albumKey
import app.lyrify.library.song
import app.lyrify.player.SourceKind
import app.lyrify.settings.Adventure
import app.lyrify.settings.Settings
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEnded
import app.lyrify.stats.PlayStart
import app.lyrify.stats.StartedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** Buckets, the mix, reasons and the four surfaces, over a made-up library. Made-up names only. */
class PicksTest {
    private val amman = ZoneId.of("Asia/Amman")
    private val now = ZonedDateTime.parse("2026-11-22T12:00+03:00").toInstant().toEpochMilli()
    private val day = DAY_MS.toLong()

    private val artists = listOf("Halden", "The Lowlands", "Mira Solen", "Mara Quinn", "Kestrel", "Bexley Rowe", "The Quiet Fathoms", "Odile Marsh")
    private val genres = listOf("Indie folk", "Dream pop", "Post rock")

    /** 8 artists, 2 albums each of 5 songs: 80 songs, the genre by artist, added a year ago. */
    private val songs: List<Song> = artists.flatMapIndexed { a, artist ->
        (0 until 2).flatMap { b ->
            (0 until 5).map { n ->
                song(
                    "Music/$artist/Album $b/Song $n.mp3", title = "$artist $b $n", artist = artist, album = "$artist Album $b", track = n + 1,
                    genre = genres[a % 3], year = 2000 + a * 3, addedAt = now - 365 * day, id = (a * 100 + b * 10 + n).toLong() + 1,
                )
            }
        }
    }
    private val library = Library.of(songs, Settings())

    private fun of(artist: Int, album: Int, n: Int): Song = songs[artist * 10 + album * 5 + n]

    private fun play(song: Song, at: Long, by: StartedBy = StartedBy.PICKED, how: PlayEnd = PlayEnd.FINISHED, leftAtMs: Long = song.durationMs, shuffled: Boolean = false) =
        LoggedPlay(song.ref, at, leftAtMs, leftAtMs >= song.durationMs / 2, PlayStart(by, SourceKind.SONGS, shuffled, null), 180, PlayEnded(how, leftAtMs))

    /** Familiar: artists 0 to 3, album 0, each song picked 3 times this month. Rediscover: artists 4 and 5, album 0, 5 plays 200 days ago. */
    private val history: List<LoggedPlay> = buildList {
        for (a in 0..3) for (n in 0 until 5) for (k in 0 until 3) add(play(of(a, 0, n), now - (2 + k * 5 + n) * day - a * 3_600_000L))
        for (a in 4..5) for (n in 0 until 5) for (k in 0 until 5) add(play(of(a, 0, n), now - (200 + k) * day - n * 3_600_000L))
    }.sortedBy { it.startedAt }

    private val taste = Taste.of(history, library, emptyMap(), now, amman)

    // Buckets

    @Test
    fun `a song never or once played is new to you, one quiet for 60 days a rediscovery, else familiar`() {
        assertEquals(Bucket.DISCOVER, bucketAt(0, null, now))
        assertEquals(Bucket.DISCOVER, bucketAt(1, now - 300 * day, now))
        assertEquals(Bucket.FAMILIAR, bucketAt(5, now - 10 * day, now))
        assertEquals(Bucket.REDISCOVER, bucketAt(5, now - 61 * day, now))
    }

    @Test
    fun `the pools hold familiar, quiet loved and new songs, without the songs to avoid`() {
        val avoid = setOf(of(0, 0, 0).path, of(4, 0, 0).path, of(6, 1, 4).path)
        val pools = pools(taste, library, avoid)
        assertEquals((0..3).flatMap { a -> (0 until 5).map { of(a, 0, it).path } }.toSet() - avoid, pools.familiar.map { it.song.path }.toSet())
        assertEquals((4..5).flatMap { a -> (0 until 5).map { of(a, 0, it).path } }.toSet() - avoid, pools.rediscover.map { it.song.path }.toSet())
        assertEquals(80 - 30 - 1, pools.discover.size)
        assertTrue(pools.discover.none { it.song.path in avoid })
        assertTrue(pools.familiar.all { it.bucket == Bucket.FAMILIAR } && pools.rediscover.all { it.bucket == Bucket.REDISCOVER } && pools.discover.all { it.bucket == Bucket.DISCOVER })
    }

    @Test
    fun `a new song of a liked artist weighs more than one of an artist never played`() {
        val discover = pools(taste, library, emptySet()).discover.associate { it.song.path to it.weight }
        assertTrue((discover[of(0, 1, 0).path] ?: 0.0) > (discover[of(6, 0, 0).path] ?: 0.0))
        assertTrue((discover[of(6, 0, 0).path] ?: 0.0) > 0)
    }

    @Test
    fun `a song skipped a lot is in no pool, and friends never rescue it`() {
        val skipped = of(6, 0, 0)
        val skips = List(4) { play(skipped, now - (10 + it) * day, how = PlayEnd.NEXT, leftAtMs = 3_000) }
        val friends = List(5) { FriendPlay("f$it", skipped.title, skipped.artist, null, null, now - day, favourite = true) }
        val withSkips = Taste.of((history + skips).sortedBy { it.startedAt }, library, emptyMap(), now, amman, friendPlays = friends)
        val pools = pools(withSkips, library, emptySet())
        assertFalse((pools.familiar + pools.rediscover + pools.discover).any { it.song.path == skipped.path })
    }

    @Test
    fun `friends add a small, capped boost to a new song, below a liked artist's song`() {
        val friendSong = of(7, 0, 0)
        val friends = List(6) { FriendPlay("f$it", friendSong.title, friendSong.artist, null, null, now - day, favourite = true) }
        val withFriends = Taste.of(history, library, emptyMap(), now, amman, friendPlays = friends)
        val discover = pools(withFriends, library, emptySet()).discover.associate { it.song.path to it.weight }
        assertTrue((discover[friendSong.path] ?: 0.0) > (discover[of(7, 0, 1).path] ?: 0.0))
        assertTrue((discover[friendSong.path] ?: 0.0) < (discover[of(0, 1, 0).path] ?: 0.0))
    }

    // The mix

    @Test
    fun `the mix starts about 1 suggestion in 3 for Mix, fewer for Familiar, more for Discover`() {
        assertEquals(Mix(1.0 / 3, 0.5), startOf(Adventure.MIX))
        assertEquals(Mix(0.2, 0.35), startOf(Adventure.FAMILIAR))
        assertEquals(Mix(0.5, 0.6), startOf(Adventure.DISCOVER))
        // What Smart shuffle's message and card say: about 1 song in 3 for Mix, 1 in 5 for Familiar, 1 in 2 for Discover.
        assertEquals(listOf(5, 3, 2), Adventure.entries.map { startOf(it).oneIn })
        assertEquals(6, Mix(0.01, 0.5).oneIn)
    }

    @Test
    fun `full plays of new picks move the mix toward new songs slowly, and only within bounds`() {
        val good = List(5) { Outcome(now - it, Bucket.DISCOVER, good = true) }
        val five = mixOf(Adventure.MIX, good)
        assertEquals(1.0 / 3 + 0.05, five.explore, 1e-9)
        assertEquals(0.55, five.discover, 1e-9)
        val many = mixOf(Adventure.MIX, List(100) { Outcome(now - it, Bucket.DISCOVER, good = true) })
        assertEquals(1.0 / 3 + 0.15, many.explore, 1e-9)
        assertEquals(0.7, many.discover, 1e-9)
    }

    @Test
    fun `early skips of rediscoveries move the mix back toward songs you love, and familiar picks do not move it`() {
        val skipped = mixOf(Adventure.MIX, List(100) { Outcome(now - it, Bucket.REDISCOVER, good = false) })
        assertEquals(1.0 / 3 - 0.15, skipped.explore, 1e-9)
        assertEquals(0.7, skipped.discover, 1e-9)
        assertEquals(startOf(Adventure.MIX), mixOf(Adventure.MIX, List(10) { Outcome(now - it, Bucket.FAMILIAR, good = false) }))
        assertEquals(startOf(Adventure.MIX), mixOf(Adventure.MIX, List(10) { Outcome(now - it, Bucket.DISCOVER, good = null) }))
    }

    @Test
    fun `a play a suggestion started is an outcome, in the bucket its song was in then`() {
        val newSong = of(6, 0, 0)
        val plays = (history + play(newSong, now - day, by = StartedBy.SUGGESTED) + play(of(4, 0, 0), now - 2 * day, by = StartedBy.SUGGESTED, how = PlayEnd.NEXT, leftAtMs = 2_000))
            .sortedBy { it.startedAt }
        val outcomes = Taste.of(plays, library, emptyMap(), now, amman).outcomes
        assertEquals(listOf(Outcome(now - 2 * day, Bucket.REDISCOVER, false), Outcome(now - day, Bucket.DISCOVER, true)), outcomes)
    }

    // Reasons

    @Test
    fun `a song that often follows the one before says so first`() {
        val a = of(0, 0, 0)
        val b = of(1, 0, 0)
        val plays = (history + List(3) { listOf(play(a, now - (30 + it) * day), play(b, now - (30 + it) * day + a.durationMs)) }.flatten()).sortedBy { it.startedAt }
        val withFollows = Taste.of(plays, library, emptyMap(), now, amman)
        assertEquals(Reason.Follows(a), reasonFor(b, Bucket.FAMILIAR, withFollows, library, amman, after = a))
    }

    @Test
    fun `a rediscovery says when it was last played, a familiar song how often it was picked that month`() {
        val quiet = of(4, 0, 1)
        assertEquals(Reason.NotSince(taste.songs.getValue(quiet.path).lastPlayedAt!!), reasonFor(quiet, Bucket.REDISCOVER, taste, library, amman))
        val loved = of(0, 0, 0)
        val month = YearMonth.of(2026, 11)
        assertEquals(Reason.Picked(taste.songs.getValue(loved.path).picksByMonth.getValue(month), month), reasonFor(loved, Bucket.FAMILIAR, taste, library, amman))
    }

    @Test
    fun `a new song says whose it is, a similar artist's says who it is like, a friends' pick how many love it`() {
        assertEquals(Reason.NewFrom("Halden"), reasonFor(of(0, 1, 0), Bucket.DISCOVER, taste, library, amman))
        val similar = Taste.of(history, library, emptyMap(), now, amman, similar = mapOf("Halden" to listOf(SimilarArtist("Odile Marsh", 0.9f))))
        assertEquals(Reason.LikeArtist("Halden"), reasonFor(of(7, 0, 0), Bucket.DISCOVER, similar, library, amman))
        val friendSong = of(6, 1, 0)
        val friends = List(2) { FriendPlay("f$it", friendSong.title, friendSong.artist, null, null, now - day, favourite = true) }
        val withFriends = Taste.of(history, library, emptyMap(), now, amman, friendPlays = friends)
        assertEquals(Reason.Friends(2), reasonFor(friendSong, Bucket.DISCOVER, withFriends, library, amman))
        assertEquals(Reason.Added(now - 365 * day, 0), reasonFor(of(6, 0, 0), Bucket.DISCOVER, taste, library, amman))
    }

    @Test
    fun `Why this lists the reasons for a song and the skips against it`() {
        val song = of(0, 0, 0)
        val plays = (history + play(song, now - 3 * day, how = PlayEnd.NEXT, leftAtMs = 4_000)).sortedBy { it.startedAt }
        val why = why(song, Taste.of(plays, library, emptyMap(), now, amman), library, amman)
        assertEquals(Reason.Picked(3, YearMonth.of(2026, 11)), why.reasons.firstOrNull())
        assertEquals(listOf(Reason.Skipped(1, now - 3 * day)), why.against)
    }

    // For you

    private fun artistOf(pick: Pick) = pick.song.artist

    private fun assertFresh(picks: List<Pick>, before: List<Song> = emptyList()) {
        val all = before.map { it.artist } + picks.map(::artistOf)
        all.indices.drop(before.size).forEach { i -> assertTrue("artist again at $i", (maxOf(0, i - 3) until i).none { all[it] == all[i] }) }
        val albums = picks.map { albumKey(it.song) }
        albums.indices.drop(2).forEach { i -> assertFalse("3 of an album in a row at $i", albums[i] == albums[i - 1] && albums[i] == albums[i - 2]) }
    }

    @Test
    fun `For you is 20 songs, mostly loved, some rediscovered, a few new, each with a reason, none twice`() {
        val picks = forYou(taste, library, startOf(Adventure.MIX), emptySet(), Random(7), amman)
        assertEquals(20, picks.size)
        assertEquals(20, picks.map { it.song.path }.distinct().size)
        val counts = picks.groupingBy { it.bucket }.eachCount()
        assertEquals(mapOf(Bucket.FAMILIAR to 14, Bucket.REDISCOVER to 3, Bucket.DISCOVER to 3), counts)
        assertFresh(picks)
    }

    @Test
    fun `For you leaves out the songs to avoid, and is the same for the same day's draw`() {
        val avoid = (0..3).map { of(it, 0, 0).path }.toSet()
        val picks = forYou(taste, library, startOf(Adventure.MIX), avoid, Random(7), amman)
        assertTrue(picks.none { it.song.path in avoid })
        assertEquals(picks, forYou(taste, library, startOf(Adventure.MIX), avoid, Random(7), amman))
    }

    @Test
    fun `Discover leans For you toward new songs and rediscoveries`() {
        val picks = forYou(taste, library, startOf(Adventure.DISCOVER), emptySet(), Random(7), amman)
        assertEquals(10, picks.count { it.bucket == Bucket.FAMILIAR })
    }

    // Autoplay

    @Test
    fun `Autoplay goes on with songs like the queue, the first one that often follows its last song`() {
        val last = of(2, 0, 4)
        val next = of(3, 0, 2)
        val plays = (history + List(3) { listOf(play(last, now - (40 + it) * day), play(next, now - (40 + it) * day + last.durationMs)) }.flatten()).sortedBy { it.startedAt }
        val withFollows = Taste.of(plays, library, emptyMap(), now, amman)
        val queue = (0 until 5).map { of(2, 0, it) }
        val picks = autoplay(withFollows, library, startOf(Adventure.MIX), queue, queue.map { it.path }.toSet(), Random(3), amman)
        // A whole batch (user 2026-11-22: 20, was 5).
        assertEquals(AUTOPLAY_SONGS, picks.size)
        assertEquals(Pick(next, Bucket.FAMILIAR, Reason.Follows(last)), picks.firstOrNull())
        assertTrue(picks.none { it.song in queue })
        assertFresh(picks, before = queue)
    }

    // Smart shuffle

    @Test
    fun `Smart shuffle puts about 1 suggestion after every 2 of the queue's songs, never two together`() {
        val upcoming = (0 until 12).map { Upcoming(of(it % 4, 0, it / 4), suggested = false) }
        val playing = of(0, 0, 4)
        val avoid = upcoming.map { it.song.path }.toSet() + playing.path
        val inserts = smartInserts(taste, library, startOf(Adventure.MIX), playing, upcoming, avoid, Random(5), amman)
        assertEquals(listOf(0, 2, 4, 6, 8, 10), inserts.map { it.after })
        assertTrue(inserts.all { it.pick.bucket != Bucket.FAMILIAR && it.pick.song.path !in avoid })
        val played = buildList {
            add(playing)
            upcoming.forEachIndexed { i, item ->
                add(item.song)
                inserts.filter { it.after == i }.forEach { add(it.pick.song) }
            }
        }
        played.indices.forEach { i -> assertTrue("artist again at $i", (maxOf(0, i - 3) until i).none { played[it].artist == played[i].artist } || inserts.none { it.pick.song == played[i] }) }
    }

    /** [inserts] put among [upcoming] after [playing], in play order. */
    private fun played(playing: Song, upcoming: List<Song>, inserts: List<Insert>): List<Song> = buildList {
        add(playing)
        upcoming.forEachIndexed { i, song ->
            add(song)
            inserts.filter { it.after == i }.forEach { add(it.pick.song) }
        }
    }

    @Test
    fun `Smart shuffle never makes a third song of one album in a row, counting the queue's songs after its place too`() {
        val night = (0 until 6).map { n -> song("Music/Mix/Night $n.mp3", artist = "Guest $n", album = "Night Mix", albumArtist = "Various", id = 900L + n) }
        val solo = (0 until 6).map { n -> song("Music/Solo $n/Song.mp3", artist = "Solo $n", album = "Solo $n", id = 950L + n) }
        val small = Library.of(night + solo, Settings())
        val fresh = Taste.of(emptyList(), small, emptyMap(), now, amman)
        val upcoming = listOf(night[0], night[1]) + solo.drop(1)
        // Only the album's other songs are left to suggest.
        val avoid = (upcoming + solo[0]).mapTo(HashSet()) { it.path }
        val inserts = smartInserts(fresh, small, startOf(Adventure.MIX), solo[0], upcoming.map { Upcoming(it, suggested = false) }, avoid, Random(1), amman)
        assertTrue(inserts.isNotEmpty())
        played(solo[0], upcoming, inserts).windowed(3).forEach { three -> assertFalse(three.map { it.path }.toString(), three.all { albumKey(it) == albumKey(night[0]) }) }
    }

    @Test
    fun `Smart shuffle keeps an artist 3 songs apart by the names the library splits a credit into`() {
        val kestrel = song("Music/Kestrel/Low Sun.mp3", artist = "Kestrel", album = "Low Sun", id = 800L)
        val duets = (0 until 4).map { n -> song("Music/Duets/Duet $n.mp3", artist = "Kestrel, Mira Solen", album = "Duets $n", id = 810L + n) }
        val solo = (0 until 6).map { n -> song("Music/Solo $n/Song.mp3", artist = "Solo $n", album = "Solo $n", id = 950L + n) }
        val small = Library.of(listOf(kestrel) + duets + solo, Settings())
        val fresh = Taste.of(emptyList(), small, emptyMap(), now, amman)
        val upcoming = listOf(kestrel) + solo.drop(1)
        val avoid = (upcoming + solo[0]).mapTo(HashSet()) { it.path }
        val inserts = smartInserts(fresh, small, startOf(Adventure.MIX), solo[0], upcoming.map { Upcoming(it, suggested = false) }, avoid, Random(1), amman)
        assertTrue(inserts.isNotEmpty())
        val order = played(solo[0], upcoming, inserts)
        val names = { s: Song -> small.artistsOf(s).map { it.name }.toSet() }
        inserts.map { it.pick.song }.forEach { pick ->
            val at = order.indexOf(pick)
            val near = order.subList(maxOf(0, at - 3), at) + order.subList(at + 1, minOf(order.size, at + 4))
            assertTrue(pick.title, near.none { (names(it) intersect names(pick)).isNotEmpty() })
        }
    }

    @Test
    fun `Smart shuffle counts the suggestions already waiting, and adds only what is missing`() {
        val own = (0 until 12).map { Upcoming(of(it % 4, 0, it / 4), suggested = false) }
        val waiting = own.take(2) + Upcoming(of(4, 0, 0), suggested = true) + own.drop(2)
        val inserts = smartInserts(taste, library, startOf(Adventure.MIX), of(0, 0, 4), waiting, waiting.map { it.song.path }.toSet(), Random(5), amman)
        assertEquals(listOf(4, 6, 8, 10), inserts.map { it.after })
    }

    // More like this

    @Test
    fun `More like a song puts the songs played with it first, then its artist, its genre and its years`() {
        val seed = of(0, 0, 0)
        val together = of(5, 1, 2)
        val plays = (history + List(3) { listOf(play(seed, now - (50 + it) * day), play(together, now - (50 + it) * day + seed.durationMs)) }.flatten()).sortedBy { it.startedAt }
        val withFollows = Taste.of(plays, library, emptyMap(), now, amman)
        val more = moreLike(withFollows, library, Seed.OfSong(seed), Basis.entries.toSet(), emptySet(), amman)
        assertEquals(Pick(together, Bucket.FAMILIAR, Reason.Follows(seed)), more.picks.firstOrNull())
        assertTrue(more.picks.none { it.song == seed })
        assertTrue(more.picks.any { it.reason == Reason.SameArtist(sameAlbum = true) })
        assertEquals(listOf(Basis.TOGETHER, Basis.ARTIST, Basis.GENRE, Basis.YEARS), more.bases)
        assertEquals("Indie folk", more.genre)
    }

    @Test
    fun `More like this uses only the chips left on, and never a song said never to suggest`() {
        val seed = of(0, 0, 0)
        val avoid = setOf(of(0, 0, 1).path)
        val onlyArtist = moreLike(taste, library, Seed.OfSong(seed), setOf(Basis.ARTIST), avoid, amman)
        assertTrue(onlyArtist.picks.isNotEmpty())
        assertTrue(onlyArtist.picks.all { it.song.artist == "Halden" && it.song.path !in avoid })
        val onlyGenre = moreLike(taste, library, Seed.OfSong(seed), setOf(Basis.GENRE), emptySet(), amman)
        assertTrue(onlyGenre.picks.isNotEmpty())
        assertTrue(onlyGenre.picks.all { it.song.genre == "Indie folk" && it.song.artist != "Halden" })
    }

    @Test
    fun `More like an artist is songs of the artists like it and of its genre, never its own`() {
        val artist = library.artist("Halden")!!
        val similar = Taste.of(history, library, emptyMap(), now, amman, similar = mapOf("Halden" to listOf(SimilarArtist("Odile Marsh", 0.9f))))
        val more = moreLike(similar, library, Seed.OfArtist(artist), Basis.entries.toSet(), emptySet(), amman)
        assertTrue(more.picks.none { it.song.artist == "Halden" })
        assertEquals("Odile Marsh", more.picks.firstOrNull()?.song?.artist)
        assertEquals(Reason.LikeArtist("Halden"), more.picks.firstOrNull()?.reason)
    }
}
