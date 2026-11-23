package app.lyrify.huddle

import app.lyrify.backup.ZipLimits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * `huddle.json` there and back, the history in plays.db (its SQL on the laptop's SQLite), and the backup's Huddle section:
 * sealed with a passphrase, opened only with it, merged on restore.
 */
class HuddleStoreTest {
    private val random = SecureRandom()
    private val card = Card(CardKind.SONG, listOf(lantern), note = "for the drive")

    private fun friend(id: String, name: String = "Juniper") = Friend(
        id = id,
        name = name,
        outBox = "out-$id",
        inBox = "in-$id",
        outKey = "ok-$id",
        inKey = "ik-$id",
        joinId = "join-$id",
        addedAt = 1_000,
        rename = "June",
        sharePlays = true,
        lastSentAt = 2_000,
        seen = listOf("m1", "m2"),
    )

    private val full = HuddleState(
        name = "Wren",
        friends = listOf(friend("f1"), friend("f2", "Rook")),
        invites = listOf(PendingInvite("pub", "private-key-1", "tok", "owner-id-1", 3_000, card)),
        inbox = listOf(Received("m9", "f1", 4_000, card, opened = true)),
        plays = listOf(FriendPlay("f2", 5_000, bell)),
        outbox = listOf(Outgoing("m10", "out-f1", "data", Kind.PLAY, 6_000, 6_500)),
        privateUntil = HuddleState.FOREVER,
        backupKey = BackupKey("salt", 1_000, "key"),
        past = listOf(PastFriend("f3", "Kai", 7_000)),
    )

    @Test
    fun `the whole state goes to huddle json and comes back`() {
        assertEquals(full, HuddleJson.decode(HuddleJson.encode(full)))
        assertEquals(HuddleState(), HuddleJson.decode(HuddleJson.encode(HuddleState())))
    }

    @Test
    fun `a field this version does not know is ignored, and a missing one takes its default`() {
        val text = """{"schemaVersion":1,"name":"Wren","later":{"x":1},"friends":[{"id":"f1","name":"Juniper","outBox":"o","inBox":"i","outKey":"ok","inKey":"ik","joinId":"j","addedAt":1,"mood":"good"}]}"""
        val read = HuddleJson.decode(text)
        assertEquals("Wren", read.name)
        assertTrue(read.inbox.isEmpty())
        assertNull(read.friends.single().taken)
        assertFalse(read.friends.single().asksNewName)
        assertTrue(read.joined.isEmpty())
        assertTrue("a huddle.json from before past friends has none", read.past.isEmpty())
    }

    @Test
    fun `friends made before sharing was on by default get it once, and one turned off since stays off`() {
        fun stored(shares: Boolean) = """{"id":"f1","name":"Juniper","outBox":"o","inBox":"i","outKey":"ok","inKey":"ik","joinId":"j","addedAt":1,"sharePlays":$shares}"""
        // Written by a KJB before this version (schema 1, or none): sharing turns on for every friend, once.
        assertTrue(HuddleJson.decode("""{"schemaVersion":1,"friends":[${stored(false)}]}""").friends.single().sharePlays)
        assertTrue(HuddleJson.decode("""{"friends":[${stored(false)}]}""").friends.single().sharePlays)
        // Written by this version: the user's choice stays as it is.
        val off = HuddleJson.decode(HuddleJson.encode(HuddleState(friends = listOf(friend("f2").copy(sharePlays = false)))))
        assertFalse(off.friends.single().sharePlays)
        assertTrue(HuddleJson.encode(HuddleState()).contains(""""schemaVersion":2"""))
        // A new friend shares by default.
        assertTrue(Friend("f3", "Rook", "o", "i", "ok", "ik", "j", addedAt = 1).sharePlays)
    }

    @Test
    fun `a newer schema is told apart, and what is not Huddle's state throws`() {
        assertTrue(HuddleJson.fromNewer("""{"schemaVersion":3}"""))
        assertFalse(HuddleJson.fromNewer(HuddleJson.encode(full)))
        assertFalse(HuddleJson.fromNewer("not json"))
        assertThrows { HuddleJson.decode("not json") }
        assertThrows { HuddleJson.decode("[1,2]") }
    }

    /** A history holding [cards] and [plays], closed after the test. */
    private suspend fun history(cards: List<KeptCard> = emptyList(), plays: List<FriendPlay> = emptyList()): JdbcHuddleLog =
        JdbcHuddleLog().also(histories::add).also { assertTrue(it.keep(cards, plays, playsFrom = 0)) }

    private val histories = ArrayList<JdbcHuddleLog>()

    @After
    fun close() = histories.forEach(JdbcHuddleLog::close)

    private val cards = listOf(
        Received("m9", "f1", 4_000, card, opened = true),
        Sent("m10", "f1", 4_500, Card(CardKind.PLAYLIST, listOf(lantern, bell), name = "Sunday drive", note = "for the coast")),
        Sent("m11", "f2", 3_000, card, delivered = false),
    )
    private val plays = listOf(FriendPlay("f2", 5_000, bell, favourite = true), FriendPlay("f1", 4_800, lantern), FriendPlay("f2", 2_000, lantern.copy(artist = null)))

    @Test
    fun `with a passphrase the friends and the history go into the backup sealed, and come back only with that passphrase`() = runBlocking {
        val key = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        val section = HuddleBackup.seal(full.copy(backupKey = key), history(cards, plays), random)!!
        val back = HuddleBackup.open(section, "correct horse".toCharArray())
        assertNotNull(back)
        assertEquals(full.friends, back!!.state.friends)
        assertEquals(full.name, back.state.name)
        assertEquals("each friend's latest play, for now playing", full.plays, back.state.plays)
        assertEquals(full.invites, back.state.invites)
        assertEquals("past friends, whose history it holds too", full.past, back.state.past)
        assertEquals("every card both ways, the newest first", cards.sortedByDescending { it.at }, back.cards)
        assertEquals("every play, the newest first", plays, back.plays)
        assertTrue("what was waiting to go is not restored", back.state.outbox.isEmpty())
        assertEquals("opened, it holds the key its passphrase makes, for the next backups", key, back.state.backupKey)
        assertNull(HuddleBackup.open(section, "correct horses".toCharArray()))
        assertNull(HuddleBackup.open(section.replace("\"rounds\":1000", "\"rounds\":1001"), "correct horse".toCharArray()))
        assertNull(HuddleBackup.open("not a section", "correct horse".toCharArray()))
    }

    @Test
    fun `a history that cannot be read keeps Huddle out of that backup, as any store that cannot be read`() = runBlocking {
        val key = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        assertNull(HuddleBackup.seal(full.copy(backupKey = key), history(cards, plays).apply { failing = true }, random))
    }

    @Test
    fun `a section made before the history came opens with its cards and plays as the history`() {
        // KJB v4 test builds (format 1): the whole state sealed as huddle.json keeps it, the newest 100 cards and 30 days of plays in it.
        val passphrase = "correct horse".toCharArray()
        val key = HuddleBackup.newKey(passphrase, random, rounds = 1_000)
        val box = HuddleCrypto.seal(HuddleCrypto.decode(key.key)!!, HuddleJson.encode(full.copy(outbox = emptyList(), backupKey = null)).encodeToByteArray(), "KJB Huddle 1 backup".encodeToByteArray(), random)
        val section = """{"format":1,"kdf":"pbkdf2-sha256","rounds":1000,"salt":"${key.salt}","box":"${HuddleCrypto.encode(box)}"}"""
        val back = HuddleBackup.open(section, passphrase)!!
        assertEquals(full.friends, back.state.friends)
        assertEquals(full.inbox, back.cards)
        assertEquals(full.plays, back.plays)
        assertTrue("the cards are the history's now", back.state.inbox.isEmpty())
    }

    @Test
    fun `a section whose salt is empty or short opens to nothing, never a crash`() = runBlocking {
        val section = HuddleBackup.seal(full.copy(backupKey = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)), history(), random)!!
        val salt = Regex(""""salt":"([^"]*)"""").find(section)!!.groupValues[1]
        assertNull(HuddleBackup.open(section.replace(salt, ""), "correct horse".toCharArray()))
        assertNull(HuddleBackup.open(section.replace(salt, "AA"), "correct horse".toCharArray()))
    }

    @Test
    fun `without a passphrase there is no Huddle section, history or not`() = runBlocking {
        assertNull(HuddleBackup.seal(full.copy(backupKey = null), history(cards, plays), random))
    }

    @Test
    fun `the section holds no name, title, note, mailbox or key in the clear`() = runBlocking {
        val key = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        val section = HuddleBackup.seal(full.copy(backupKey = key), history(cards, plays), random)!!
        listOf("Wren", "Juniper", "Kai", "out-f1", "in-f1", "ok-f1", "ik-f1", "private-key-1", "owner-id-1", key.key, "correct horse", "Paper Lantern", "Kit Calloway", "Sunday drive", "for the coast", "for the drive")
            .forEach { assertFalse(it, section.contains(it)) }
    }

    @Test
    fun `a year of ten friends' plays and every card fit one entry any KJB reads, and all of it comes back`() = runBlocking {
        // Ten friends, each about 50 songs a day for a year (the user's whole Huddle), from 2,000 made-up songs.
        val songs = (0 until 2_000).map { CardSong("Made-up song $it", "Band ${it % 150}", "Record ${it % 400}", 150_000L + it * 37) }
        val year = (0 until 182_500).map { FriendPlay("friend-${it % 10}", 1_700_000_000_000L - it * 172_800L, songs[(it * 7919) % songs.size], favourite = it % 11 == 0) }
        val sent = (0 until 1_000).map { Sent("card-$it", "friend-${it % 10}", 1_700_000_000_000L - it * 3_600_000L, Card(CardKind.SONG, listOf(songs[it]), note = "note number $it")) }
        val key = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        val section = HuddleBackup.seal(full.copy(backupKey = key), history(sent, year), random)!!
        assertTrue("${section.length} characters", section.length < ZipLimits().entryBytes / 2)
        val back = HuddleBackup.open(section, "correct horse".toCharArray())!!
        assertEquals(sent.size, back.cards.size)
        assertEquals(year.size, back.plays.size)
        assertTrue(back.plays.zip(year).all { (a, b) -> a == b })
    }

    @Test
    fun `past its size a section keeps every card first, then the newest plays, never a part of one`() = runBlocking {
        val plays = (0 until 3_000).map { FriendPlay("f1", 1_000_000L - it, CardSong("Song $it ${"x".repeat(it % 40)}", "Band $it", null, it * 1_000L)) }
        val key = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        val section = HuddleBackup.seal(full.copy(backupKey = key), history(cards, plays), random, bytes = 16 * 1024)!!
        val back = HuddleBackup.open(section, "correct horse".toCharArray())!!
        assertEquals(cards.sortedByDescending { it.at }, back.cards)
        assertTrue(back.plays.size in 1 until plays.size)
        assertEquals("the newest", plays.take(back.plays.size), back.plays)
    }

    @Test
    fun `the history keeps a card once by its id, as first kept, and reads it back either way`() = runBlocking {
        val history = history(cards)
        assertTrue(history.keep(listOf(Received("m9", "f1", 4_000, card, opened = false), Sent("m11", "f2", 3_000, card)), playsFrom = 0))
        assertEquals(cards[0], history.card("m9"))
        assertEquals("still not delivered", cards[2], history.card("m11"))
        assertEquals(cards[1], history.card("m10"))
        assertNull(history.card("m404"))
    }

    @Test
    fun `a friend's play sent again is kept once, and other plays at the same time are each kept`() = runBlocking {
        val history = history()
        val play = FriendPlay("f1", 5_000, bell)
        val others = listOf(play.copy(song = lantern), play.copy(song = bell.copy(artist = null)), play.copy(song = bell.copy(album = null)), play.copy(favourite = true), play.copy(song = bell.copy(durationMs = 0)))
        assertTrue(history.keep(plays = listOf(play) + others, playsFrom = 0))
        assertTrue(history.keep(plays = listOf(play) + others, playsFrom = 0))
        assertEquals((listOf(play) + others).toSet(), history.plays("f1", 0, 100)!!.toSet())
        assertEquals(6, history.plays("f1", 0, 100)!!.size)
    }

    @Test
    fun `plays from before a year go, and a friend past the most kept loses their oldest, nobody else's`() = runBlocking {
        val history = history(plays = listOf(FriendPlay("f1", 999, bell), FriendPlay("f1", 1_000, lantern), FriendPlay("f2", 1_000, bell)))
        assertTrue(history.keep(playsFrom = 1_000))
        assertEquals(listOf(FriendPlay("f1", 1_000, lantern)), history.plays("f1", 0, 10))
        val many = (1..HuddleLog.PLAYS_PER_FRIEND + 2).map { FriendPlay("f1", 10_000L + it, bell) }
        assertTrue(history.keep(plays = many, playsFrom = 1_000))
        assertEquals(HuddleLog.PLAYS_PER_FRIEND, history.plays("f1", 0, Int.MAX_VALUE)!!.size)
        assertEquals("the newest kept", many.last(), history.plays("f1", 0, 1)!!.single())
        assertEquals(10_003L, history.plays("f1", HuddleLog.PLAYS_PER_FRIEND - 1, 1)!!.single().at)
        assertEquals(listOf(FriendPlay("f2", 1_000, bell)), history.plays("f2", 0, 10))
    }

    @Test
    fun `a thread is one friend's cards both ways, Sent to you every friend's received, the newest first, in pages`() = runBlocking {
        val history = history(cards + Received("m12", "f2", 4_200, card) + Received("m13", "f1", 4_000, card.copy(note = "same time")))
        assertEquals(listOf("m10", "m9", "m13"), history.thread("f1", 0, 10)!!.map { it.id })
        assertEquals(listOf("m9"), history.thread("f1", 1, 1)!!.map { it.id })
        assertEquals(listOf("m12", "m11"), history.thread("f2", 0, 10)!!.map { it.id })
        assertEquals(listOf("m12", "m9", "m13"), history.received(0, 10)!!.map { it.id })
        assertEquals(listOf("m13"), history.received(2, 5)!!.map { it.id })
        assertEquals(listOf("m10", "m12", "m9", "m13", "m11"), history.cards(0, 10)!!.map { it.id })
        assertTrue(history.received(5, 5)!!.isEmpty())
    }

    @Test
    fun `a friend's plays read the newest first in pages, and Suggestions read everyone's from a time on, the oldest first`() = runBlocking {
        val history = history(plays = plays)
        assertEquals(listOf(plays[0]), history.plays("f2", 0, 1))
        assertEquals(listOf(plays[2]), history.plays("f2", 1, 5))
        assertEquals(plays, history.plays(null, 0, 10))
        assertEquals(listOf(plays[1], plays[0]), history.playsSince(4_800))
        assertEquals(plays.reversed(), history.playsSince(0))
    }

    @Test
    fun `a card opened stays opened, put away stays put away, and a card you sent is never marked`() = runBlocking {
        val history = history(cards)
        assertTrue(history.opened("m9", dismissed = false))
        assertEquals(Received("m9", "f1", 4_000, card, opened = true), history.card("m9"))
        assertTrue(history.opened("m9", dismissed = true))
        assertTrue(history.opened("m9", dismissed = false))
        assertEquals(Received("m9", "f1", 4_000, card, opened = true, dismissed = true), history.card("m9"))
        history.opened("m10", dismissed = true)
        assertEquals(cards[1], history.card("m10"))
    }

    @Test
    fun `forgetting a friend lets go of their cards and plays, both ways, and of nobody else's`() = runBlocking {
        val history = history(cards, plays)
        assertTrue(history.forget("f1"))
        assertEquals(listOf("m11"), history.cards(0, 10)!!.map { it.id })
        assertEquals(listOf(plays[0], plays[2]), history.plays(null, 0, 10))
    }

    @Test
    fun `a damaged row of the history reads leniently, and one without what it needs is dropped`() = runBlocking {
        val history = history()
        history.db.update("INSERT INTO huddle_cards (id, friend, sent, at, card) VALUES ('m1', 'f1', 0, 10, 'not json')")
        history.db.update("INSERT INTO huddle_cards (id, friend, sent, at, card) VALUES ('m2', '', 0, 10, ?)", Messages.cardJson(card).toString())
        history.db.update("INSERT INTO huddle_cards (id, friend, sent, at, card, opened, delivered) VALUES ('m3', 'f1', 1, 'soon', ?, 'x', 0)", Messages.cardJson(card).toString())
        history.db.update("INSERT INTO huddle_cards (id, friend, sent, at, card, opened, dismissed, delivered) VALUES ('m4', 'f1', 0, 20, ?, 'x', 2.0, 'y')", Messages.cardJson(card).toString())
        history.db.update("INSERT INTO friend_plays (friend, at, title, duration_ms, favourite) VALUES ('f1', 'soon', 'Brass Bell', 0, 0)")
        history.db.update("INSERT INTO friend_plays (friend, at, title, duration_ms, favourite) VALUES ('f1', 30, '', 0, 0)")
        history.db.update("INSERT INTO friend_plays (friend, at, title, artist, duration_ms, favourite) VALUES ('f1', 40, 'Brass Bell', 'Kit Calloway', -5, 'x')")
        assertEquals(listOf(Received("m4", "f1", 20, card, opened = false, dismissed = true)), history.cards(0, 10))
        assertEquals(listOf(FriendPlay("f1", 40, CardSong("Brass Bell", "Kit Calloway", null, 0))), history.plays("f1", 0, 10))
    }

    @Test
    fun `a new key is the passphrase's under its own salt, never the passphrase`() {
        val one = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        val two = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        assertFalse(one.salt == two.salt)
        assertFalse(one.key == two.key)
        assertEquals(1_000, one.rounds)
        assertFalse(one.key.contains("correct"))
    }

    @Test
    fun `a restore adds the backup's friends to what the phone has, once each, each friend's latest play, and keeps the key for the next backups`() {
        val key = BackupKey("s", 1_000, "k")
        val older = FriendPlay("f2", 4_000, lantern)
        val newer = FriendPlay("f1", 9_000, lantern)
        val phone = HuddleState(name = "Wren 2", friends = listOf(friend("f1")), inbox = listOf(Received("m1", "f1", 1, card)), plays = listOf(older, newer))
        val backup = full.copy(outbox = emptyList(), backupKey = null)
        val restored = phone.restored(backup, key)
        assertEquals("Wren 2", restored.name)
        assertEquals(listOf("f1", "f2"), restored.friends.map { it.id })
        // Changed on purpose: the backup's cards go to the history (Huddle.restore), not into huddle.json.
        assertEquals(listOf("m1"), restored.inbox.map { it.id })
        assertEquals(setOf(newer, full.plays.single()), restored.plays.toSet())
        assertEquals(full.invites, restored.invites)
        assertEquals(key, restored.backupKey)
        assertEquals("Wren", HuddleState().restored(backup, key).name)
    }

    @Test
    fun `a restore brings the backup's past friends back as past, and a friend who left this phone stays left`() {
        val key = BackupKey("s", 1_000, "k")
        // The backup was made while f1 was a friend, and after f3 left; on this phone f1 left since.
        val phone = HuddleState(name = "Wren", friends = listOf(friend("f2", "Rook")), past = listOf(PastFriend("f1", "June", 8_000)))
        val backup = HuddleState(name = "Wren", friends = listOf(friend("f1"), friend("f2", "Rook")), plays = listOf(FriendPlay("f1", 5_000, bell)), past = listOf(PastFriend("f3", "Kai", 7_000)))
        val restored = phone.restored(backup, key)
        assertEquals(listOf("f2"), restored.friends.map { it.id })
        assertTrue("nor their latest play, for now playing", restored.plays.isEmpty())
        assertEquals(listOf(PastFriend("f1", "June", 8_000), PastFriend("f3", "Kai", 7_000)), restored.past)
        assertEquals("once each", restored, restored.restored(backup, key))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("nothing thrown")
    }
}
