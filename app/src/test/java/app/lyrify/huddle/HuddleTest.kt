package app.lyrify.huddle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Huddle between made-up phones on one fake server: invites, cards there and back once, Undo, offline, plays shared
 * only by the rules (per friend, Listen privately, a day at most), removing a friend, and how often KJB asks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HuddleTest {
    private val server = FakeServer()
    private val host = "kjb-huddle.up.railway.app"
    private val hosts = listOf(host, "huddle.example.org")
    private var now = 1_700_000_000_000L
    private val random = SecureRandom()
    private val song = Card(CardKind.SONG, listOf(lantern), note = "for the drive")

    private fun TestScope.phone(name: String? = null, storage: MemoryHuddle = MemoryHuddle(HuddleState(name = name))): Pair<Huddle, MemoryHuddle> =
        Huddle(storage, server, host, hosts, { now + testScheduler.currentTime }, random, backgroundScope) to storage

    /** Wren invites Juniper with [card], Juniper accepts, Wren's KJB sees the join: both are friends. */
    private suspend fun friends(wren: Huddle, juniper: Huddle, card: Card? = null): Pair<Friend, Friend> {
        val link = checkNotNull(wren.invite(card))
        val accepted = juniper.accept(checkNotNull(juniper.parse(link))) as Accepted.Joined
        assertTrue(wren.sync())
        return wren.state.first().friends.single() to accepted.friend
    }

    @Test
    fun `an invite accepted on the other phone makes both friends, with each other's names and the first song`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val link = wren.invite(song)!!
        assertTrue(link.startsWith("https://$host/i#"))
        assertEquals(1, wrenState.now.invites.size)
        val invite = juniper.parse(link)!!
        assertEquals("Wren", invite.name)
        val accepted = juniper.accept(invite) as Accepted.Joined
        assertEquals("Wren", accepted.friend.name)
        // The first song is there the moment KJB opens the link, from Wren.
        assertEquals(listOf(song), juniperState.now.inbox.map { it.card })
        assertEquals(accepted.friend.id, juniperState.now.inbox.single().friend)

        assertTrue(wren.sync())
        val juniperOnWren = wrenState.now.friends.single()
        assertEquals("Juniper", juniperOnWren.name)
        assertTrue(wrenState.now.invites.isEmpty())
        assertTrue("the join mailbox is forgotten", server.joined.isEmpty())
        assertEquals(juniperOnWren.outBox, accepted.friend.inBox)
        assertEquals(juniperOnWren.inBox, accepted.friend.outBox)
    }

    @Test
    fun `a card too long for the link goes once the friend joins`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val album = Card(CardKind.ALBUM, (1..40).map { lantern.copy(title = "Track $it") }, name = "Harbour Glass")
        val invite = juniper.parse(wren.invite(album)!!)!!
        assertNull(invite.card)
        juniper.accept(invite)
        wren.sync()
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.sync()
        assertEquals(listOf(album), juniperState.now.inbox.map { it.card })
    }

    @Test
    fun `a card sent arrives once, from the right friend, and leaves the server`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        val card = Card(CardKind.PLAYLIST, listOf(lantern, bell), name = "Sunday", note = "try these")
        assertNotNull(wren.send(juniperOnWren.id, card))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertEquals(1, server.waiting(juniperOnWren.outBox).size)
        assertEquals(Keep.MONTH, server.keeps.values.last())

        assertTrue(juniper.sync())
        val received = juniperState.now.inbox.first()
        assertEquals(card, received.card)
        assertEquals(wrenOnJuniper.id, received.friend)
        assertFalse(received.opened)
        assertTrue("acknowledged", server.waiting(juniperOnWren.outBox).isEmpty())
        juniper.sync()
        assertEquals(1, juniperState.now.inbox.count { it.card == card })
    }

    @Test
    fun `Undo before the card goes leaves nothing on the server`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val id = wren.send(juniperOnWren.id, song)!!
        assertTrue(wren.unsend(id))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.flush()
        assertTrue(server.waiting(juniperOnWren.outBox).isEmpty())
        assertTrue(wrenState.now.outbox.isEmpty())
        assertFalse("too late once it went", wren.unsend(id))
    }

    @Test
    fun `a card sent without network waits on the phone and goes at the next sync`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        server.online = false
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertEquals(1, wrenState.now.outbox.size)
        server.online = true
        assertTrue(wren.sync())
        assertTrue(wrenState.now.outbox.isEmpty())
        assertEquals(1, server.waiting(juniperOnWren.outBox).size)
    }

    @Test
    fun `a copy the server sends again, or a card sent back to its sender, is dropped`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        val data = server.waiting(juniperOnWren.outBox).single().data
        juniper.sync()
        server.replay(juniperOnWren.outBox, data)
        juniper.sync()
        assertEquals(1, juniperState.now.inbox.size)
        assertTrue("the replay is acknowledged too", server.waiting(juniperOnWren.outBox).isEmpty())
        // The server hands Wren's own card back to Wren: it was sealed for the other way, so it never opens.
        server.replay(juniperOnWren.inBox, data)
        wren.sync()
        assertTrue(wrenState.now.inbox.isEmpty())
    }

    @Test
    fun `an invite someone used first says so, your own is told apart, and a second tap is not a second friend`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (rook, rookState) = phone("Rook")
        val link = wren.invite(null)!!
        assertTrue(juniper.accept(juniper.parse(link)!!) is Accepted.Joined)
        assertEquals(Accepted.Taken, rook.accept(rook.parse(link)!!))
        assertTrue(rookState.now.friends.isEmpty())
        assertEquals(Accepted.Own, wren.accept(wren.parse(link)!!))
        assertTrue(juniper.accept(juniper.parse(link)!!) is Accepted.Already)
        assertEquals(1, juniperState.now.friends.size)
    }

    @Test
    fun `without a name nothing is invited or accepted, and without network nothing is half made`() = runTest {
        val (wren, wrenState) = phone()
        val (juniper, juniperState) = phone()
        assertNull(wren.invite(song))
        assertTrue(wren.setName("  Wren "))
        assertEquals("Wren", wrenState.now.name)
        assertFalse(wren.setName("   "))
        val invite = juniper.parse(wren.invite(song)!!)!!
        assertEquals(Accepted.NoName, juniper.accept(invite))
        juniper.setName("Juniper")
        server.online = false
        assertEquals(Accepted.NoAnswer, juniper.accept(invite))
        assertTrue(juniperState.now.friends.isEmpty())
        assertTrue(juniperState.now.inbox.isEmpty())
        server.online = true
        assertTrue(juniper.accept(invite) is Accepted.Joined)
    }

    @Test
    fun `plays go only to the friends you share them with`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        juniper.played(bell, now)
        assertTrue(server.waiting(wrenOnJuniper.outBox).isEmpty())

        assertTrue(juniper.sharePlays(wrenOnJuniper.id, true))
        juniper.played(bell, now + 1)
        assertEquals(1, server.waiting(wrenOnJuniper.outBox).size)
        assertEquals(Keep.DAY, server.keeps.values.last())
        wren.sync()
        assertEquals(listOf(FriendPlay(juniperOnWren.id, now + 1, bell)), wrenState.now.plays)
    }

    @Test
    fun `Listen privately shares nothing until its time is up, or until it is turned off`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        juniper.sharePlays(wrenOnJuniper.id, true)
        assertTrue(juniper.listenPrivately(now + Huddle.HOUR_MS))
        juniper.played(bell, now)
        assertTrue(server.waiting(wrenOnJuniper.outBox).isEmpty())
        advanceTimeBy(Huddle.HOUR_MS)
        juniper.played(bell, now + Huddle.HOUR_MS)
        assertEquals(1, server.waiting(wrenOnJuniper.outBox).size)

        juniper.listenPrivately(HuddleState.FOREVER)
        advanceTimeBy(100 * Huddle.HOUR_MS)
        juniper.played(lantern, now + 101 * Huddle.HOUR_MS)
        assertEquals(1, server.waiting(wrenOnJuniper.outBox).size)
        juniper.listenPrivately(null)
        juniper.played(lantern, now + 101 * Huddle.HOUR_MS)
        assertEquals(2, server.waiting(wrenOnJuniper.outBox).size)
    }

    @Test
    fun `a play that could not go within a day is dropped, never sent late`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        juniper.sharePlays(wrenOnJuniper.id, true)
        server.online = false
        juniper.played(bell, now)
        assertEquals(1, juniperState.now.outbox.size)
        advanceTimeBy(24 * Huddle.HOUR_MS + 1)
        server.online = true
        juniper.sync()
        assertTrue(juniperState.now.outbox.isEmpty())
        assertTrue(server.waiting(wrenOnJuniper.outBox).isEmpty())
    }

    @Test
    fun `removing a friend tells them, and the friendship goes from both phones and the server`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.sync()
        assertTrue(wren.remove(juniperOnWren.id))
        assertTrue(wrenState.now.friends.isEmpty())
        assertFalse(juniperOnWren.inBox in server.boxes)
        juniper.sync()
        assertTrue(juniperState.now.friends.isEmpty())
        assertTrue("their cards go with them", juniperState.now.inbox.isEmpty())
        assertTrue(server.boxes.values.all { it.isEmpty() })
    }

    @Test
    fun `a friend you rename keeps the name you gave, until you take it back`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        assertTrue(wren.rename(juniperOnWren.id, "  June "))
        assertEquals("June", wrenState.now.friends.single().shownName)
        assertEquals("Juniper", wrenState.now.friends.single().name)
        wren.rename(juniperOnWren.id, null)
        assertEquals("Juniper", wrenState.now.friends.single().shownName)
    }

    @Test
    fun `an invite nobody joined goes after two weeks, and a card is marked opened`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        wren.invite(null)
        wren.sync()
        assertEquals(1, wrenState.now.invites.size)
        advanceTimeBy(Huddle.INVITE_KEPT_MS + 1)
        wren.sync()
        assertTrue(wrenState.now.invites.isEmpty())

        juniper.accept(juniper.parse(wren.invite(song)!!)!!)
        val received = juniperState.now.inbox.single()
        assertTrue(juniper.opened(received.id))
        assertTrue(juniperState.now.inbox.single().opened)
    }

    @Test
    fun `a phone without friends or invites never asks the server`() = runTest {
        val (wren, _) = phone("Wren")
        assertTrue(wren.sync())
        wren.played(bell, now)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `in front KJB asks when it opens and each hour, every 30 s while the Huddle page is open, and stops when it goes`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        friends(wren, juniper)
        val before = server.fetches
        val front = wren.syncWhileInFront()
        runCurrent()
        assertEquals(before + 1, server.fetches)
        advanceTimeBy(Huddle.HOUR_MS - 1)
        runCurrent()
        assertEquals(before + 1, server.fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(before + 2, server.fetches)

        wren.pageOpen.value = true
        runCurrent()
        assertEquals("opening the page asks at once", before + 3, server.fetches)
        advanceTimeBy(Huddle.FAST_MS)
        runCurrent()
        assertEquals(before + 4, server.fetches)
        wren.pageOpen.value = false
        runCurrent()
        assertEquals(before + 4, server.fetches)
        advanceTimeBy(Huddle.HOUR_MS - 1)
        runCurrent()
        assertEquals(before + 4, server.fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(before + 5, server.fetches)

        front.cancel()
        advanceTimeBy(3 * Huddle.HOUR_MS)
        runCurrent()
        assertEquals(before + 5, server.fetches)
    }

    @Test
    fun `a backup passphrase is kept only as its key, and a short one is refused`() = runTest {
        val (wren, wrenState) = phone("Wren")
        assertFalse(wren.setPassphrase("short".toCharArray()))
        assertNull(wrenState.now.backupKey)
        assertTrue(wren.setPassphrase("correct horse".toCharArray()))
        val key = wrenState.now.backupKey!!
        assertEquals(HuddleBackup.ROUNDS, key.rounds)
        assertFalse(HuddleJson.encode(wrenState.now).contains("correct horse"))
    }
}
