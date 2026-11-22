package app.lyrify.huddle

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** `huddle.json` there and back, and the backup's Huddle section: sealed with a passphrase, opened only with it, merged on restore. */
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

    @Test
    fun `with a passphrase the friends go into the backup sealed, and come back only with that passphrase`() {
        val key = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        val section = HuddleBackup.seal(full.copy(backupKey = key), random)!!
        val back = HuddleBackup.open(section, "correct horse".toCharArray())
        assertNotNull(back)
        assertEquals(full.friends, back!!.friends)
        assertEquals(full.name, back.name)
        assertEquals(full.inbox, back.inbox)
        assertEquals(full.plays, back.plays)
        assertEquals(full.invites, back.invites)
        assertTrue("what was waiting to go is not restored", back.outbox.isEmpty())
        assertEquals("opened, it holds the key its passphrase makes, for the next backups", key, back.backupKey)
        assertNull(HuddleBackup.open(section, "correct horses".toCharArray()))
        assertNull(HuddleBackup.open(section.replace("\"rounds\":1000", "\"rounds\":1001"), "correct horse".toCharArray()))
        assertNull(HuddleBackup.open("not a section", "correct horse".toCharArray()))
    }

    @Test
    fun `a section whose salt is empty or short opens to nothing, never a crash`() {
        val section = HuddleBackup.seal(full.copy(backupKey = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)), random)!!
        val salt = Regex(""""salt":"([^"]*)"""").find(section)!!.groupValues[1]
        assertNull(HuddleBackup.open(section.replace(salt, ""), "correct horse".toCharArray()))
        assertNull(HuddleBackup.open(section.replace(salt, "AA"), "correct horse".toCharArray()))
    }

    @Test
    fun `without a passphrase there is no Huddle section`() {
        assertNull(HuddleBackup.seal(full.copy(backupKey = null), random))
    }

    @Test
    fun `the section holds no name, mailbox or key in the clear`() {
        val key = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)
        val section = HuddleBackup.seal(full.copy(backupKey = key), random)!!
        listOf("Wren", "Juniper", "out-f1", "in-f1", "ok-f1", "ik-f1", "private-key-1", "owner-id-1", key.key, "correct horse").forEach { assertFalse(it, section.contains(it)) }
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
    fun `a restore adds the backup's friends and cards to what the phone has, once each, and keeps the key for the next backups`() {
        val key = BackupKey("s", 1_000, "k")
        val phone = HuddleState(name = "Wren 2", friends = listOf(friend("f1")), inbox = listOf(Received("m1", "f1", 1, card)))
        val backup = full.copy(outbox = emptyList(), backupKey = null)
        val restored = phone.restored(backup, key)
        assertEquals("Wren 2", restored.name)
        assertEquals(listOf("f1", "f2"), restored.friends.map { it.id })
        assertEquals(setOf("m1", "m9"), restored.inbox.map { it.id }.toSet())
        assertEquals(full.plays, restored.plays)
        assertEquals(full.invites, restored.invites)
        assertEquals(key, restored.backupKey)
        assertEquals("Wren", HuddleState().restored(backup, key).name)
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
