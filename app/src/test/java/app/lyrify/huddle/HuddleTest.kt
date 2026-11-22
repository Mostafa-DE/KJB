package app.lyrify.huddle

import app.lyrify.suggestions.FriendPlay as SuggestionsPlay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

    private fun TestScope.phone(name: String? = null, storage: MemoryHuddle = MemoryHuddle(HuddleState(name = name)), ahead: Long = 0, log: (String) -> Unit = {}): Pair<Huddle, MemoryHuddle> =
        Huddle(storage, server, host, hosts, { now + ahead + testScheduler.currentTime }, random, backgroundScope, log) to storage

    /** [huddle]'s new invite link with [card]. */
    private suspend fun link(huddle: Huddle, card: Card? = null): String = (huddle.invite(card) as Invited.Link).text

    /** Wren invites Juniper with [card], Juniper accepts, Wren's KJB sees the join: both are friends. */
    private suspend fun friends(wren: Huddle, juniper: Huddle, card: Card? = null): Pair<Friend, Friend> {
        val link = link(wren, card)
        val accepted = juniper.accept(checkNotNull(juniper.parse(link))) as Accepted.Joined
        assertTrue(wren.sync())
        return wren.state.first().friends.single() to accepted.friend
    }

    @Test
    fun `an invite accepted on the other phone makes both friends, with each other's names and the first song`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val link = link(wren, song)
        assertTrue(link.startsWith("https://$host/i#"))
        assertEquals(1, wrenState.now.invites.size)
        assertEquals("the invite waits on the server under its owner id", wrenState.now.invites.single().owner, server.invites.values.single().first)
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
        assertTrue("the invite is deleted once read", server.invites.isEmpty())
        assertEquals(juniperOnWren.outBox, accepted.friend.inBox)
        assertEquals(juniperOnWren.inBox, accepted.friend.outBox)
    }

    @Test
    fun `a card too long for the link goes once the friend joins`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val album = Card(CardKind.ALBUM, (1..40).map { lantern.copy(title = "Track $it") }, name = "Harbour Glass")
        val invite = juniper.parse(link(wren, album))!!
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
        assertEquals(Kind.CARD, server.kinds.values.last())

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
        val link = link(wren)
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
        assertEquals(Invited.NoName, wren.invite(song))
        assertTrue(wren.setName("  Wren "))
        assertEquals("Wren", wrenState.now.name)
        assertFalse(wren.setName("   "))
        server.online = false
        assertEquals(Invited.NoAnswer, wren.invite(song))
        assertTrue("no invite the server never heard of", wrenState.now.invites.isEmpty())
        server.online = true
        val invite = juniper.parse(link(wren, song))!!
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
    fun `sharing plays is on for both phones from the join, and a friend you stop sharing with gets nothing`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        assertTrue("the inviter shares", juniperOnWren.sharePlays)
        assertTrue("the friend who joined shares", wrenOnJuniper.sharePlays)
        juniper.played(bell, now - 1, favourite = true)
        assertEquals(1, server.waiting(wrenOnJuniper.outBox).size)
        assertEquals(Kind.PLAY, server.kinds.values.last())
        wren.sync()
        assertEquals(listOf(FriendPlay(juniperOnWren.id, now - 1, bell, favourite = true)), wrenState.now.plays)
        assertTrue("a play is gone from the server once read", server.waiting(wrenOnJuniper.outBox).isEmpty())

        assertTrue(juniper.sharePlays(wrenOnJuniper.id, false))
        juniper.played(lantern, now)
        assertTrue(server.waiting(wrenOnJuniper.outBox).isEmpty())
    }

    @Test
    fun `one ticket is one invite, however often its link is shared or copied, and another ticket is another`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val first = wren.ticketLink("ticket-1", song) as Invited.Link
        assertEquals(first, wren.ticketLink("ticket-1", song))
        assertEquals(1, wrenState.now.invites.size)
        assertEquals(1, server.invites.size)
        val other = wren.ticketLink("ticket-2", null) as Invited.Link
        assertTrue(other != first)
        assertEquals(2, server.invites.size)
    }

    @Test
    fun `the same link joined twice at once, a turn of the phone mid-join, makes one friend`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val invite = checkNotNull(juniper.parse(link(wren)))
        server.acceptDelay = 1_000
        val one = async { juniper.accept(invite) }
        val two = async { juniper.accept(invite) }
        val answers = listOf(one.await(), two.await())
        assertEquals(1, juniperState.now.friends.size)
        assertEquals(1, answers.count { it is Accepted.Joined })
        assertEquals(1, answers.count { it is Accepted.Already })
    }

    @Test
    fun `a play without an answer is sent again after 30 s, 2 min and 10 min from the player, with KJB not in front`() = runTest {
        val logged = ArrayList<String>()
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper", log = { logged += it })
        val (_, wrenOnJuniper) = friends(wren, juniper)
        server.online = false
        val sends = server.requests.count { it == "send" }
        // No syncWhileInFront here: the screen is off, the music plays.
        juniper.played(bell, now)
        assertEquals(sends + 1, server.requests.count { it == "send" })
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(sends + 2, server.requests.count { it == "send" })
        assertTrue(logged.toString(), "huddle: play not sent, next try in 30 s" in logged)
        server.online = true
        advanceTimeBy(2 * 60_000L)
        runCurrent()
        assertEquals(1, server.waiting(wrenOnJuniper.outBox).size)
        assertTrue(juniperState.now.outbox.isEmpty())
        advanceTimeBy(Huddle.HOUR_MS)
        runCurrent()
        assertEquals("sent once, then no more tries", sends + 3, server.requests.count { it == "send" })
    }

    @Test
    fun `a newcomer named as a friend you have shows under a stand-in name and is asked for another, until they pick one`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (other, otherState) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val accepted = other.accept(checkNotNull(other.parse(link(wren)))) as Accepted.Joined
        assertTrue(wren.sync())
        val newcomer = wrenState.now.friends.single { it.id != juniperOnWren.id }
        assertEquals("the name they gave is kept", "Juniper", newcomer.name)
        assertEquals("Juniper 2", newcomer.taken)
        assertEquals("Juniper 2", newcomer.shownName)
        assertEquals("the friend from before is left as they were", juniperOnWren, wrenState.now.friends.single { it.id == juniperOnWren.id })
        assertEquals(Kind.PROFILE, server.kinds.values.last())

        // The newcomer's KJB hears it, and asks them for another name.
        assertTrue(other.sync())
        val wrenOnOther = otherState.now.friends.single()
        assertTrue(wrenOnOther.asksNewName)
        assertEquals(accepted.friend.id, wrenOnOther.id)
        assertEquals("Wren", wrenOnOther.name)

        // Another name that is taken too (case aside) keeps the stand-in, and asks again.
        assertTrue(other.setName("JUNIPER"))
        assertFalse(otherState.now.friends.single().asksNewName)
        wren.sync()
        assertEquals("Juniper 2", wrenState.now.friends.single { it.id == newcomer.id }.shownName)
        other.sync()
        assertTrue(otherState.now.friends.single().asksNewName)

        // A free one is shown as it is, and nobody asks any more.
        assertTrue(other.setName("Juniper Vale"))
        wren.sync()
        val renamed = wrenState.now.friends.single { it.id == newcomer.id }
        assertEquals("Juniper Vale", renamed.name)
        assertNull(renamed.taken)
        assertEquals("Juniper Vale", renamed.shownName)
        other.sync()
        assertFalse(otherState.now.friends.single().asksNewName)
    }

    @Test
    fun `an inviter named as a friend the joiner has is the newcomer on the joiner's phone, and is asked for another`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (rook, rookState) = phone("Rook")
        val (other, _) = phone("wren")
        friends(wren, rook)
        val joined = rook.accept(checkNotNull(rook.parse(link(other)))) as Accepted.Joined
        assertEquals("wren 2", joined.friend.shownName)
        assertEquals(listOf("Wren", "wren 2"), rookState.now.friends.map { it.shownName })
        runCurrent()
        other.sync()
        assertTrue(other.state.first().friends.single().asksNewName)
        assertFalse(wrenState.now.friends.single().asksNewName)
    }

    @Test
    fun `your new name reaches every friend, and a name you gave a friend still wins on your phone`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        assertTrue(wren.setName("  Wren   Calloway "))
        juniper.sync()
        assertEquals("Wren Calloway", juniperState.now.friends.single().name)
        juniper.rename(wrenOnJuniper.id, "W")
        assertTrue(wren.setName("Wren"))
        juniper.sync()
        assertEquals("Wren", juniperState.now.friends.single().name)
        assertEquals("W", juniperState.now.friends.single().shownName)
        // The same name again, or none, changes and sends nothing.
        val sends = server.requests.count { it == "send" }
        assertTrue(wren.setName("Wren"))
        assertFalse(wren.setName("  ‎ "))
        assertEquals(sends, server.requests.count { it == "send" })
    }

    @Test
    fun `a friend who joined your invite is celebrated once, on the inviter's phone only`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        assertEquals(listOf(juniperOnWren.id), wrenState.now.joined)
        assertTrue(juniperState.now.joined.isEmpty())
        assertTrue(wren.celebrated(juniperOnWren.id))
        assertTrue(wrenState.now.joined.isEmpty())
        // Removed before it was shown: nothing is left to show.
        val (rook, _) = phone("Rook")
        rook.accept(checkNotNull(rook.parse(link(wren))))
        wren.sync()
        val rookOnWren = wrenState.now.friends.single { it.name == "Rook" }
        assertEquals(listOf(rookOnWren.id), wrenState.now.joined)
        wren.remove(rookOnWren.id)
        assertTrue(wrenState.now.joined.isEmpty())
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
    fun `an invite nobody joined goes after a week or once the server forgot it, and a card is marked opened`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        link(wren)
        wren.sync()
        assertEquals(1, wrenState.now.invites.size)
        advanceTimeBy(Huddle.INVITE_KEPT_MS + 1)
        val asked = server.requests.size
        wren.sync()
        assertTrue(wrenState.now.invites.isEmpty())
        assertEquals("an invite past its week is not asked about", asked, server.requests.size)

        link(wren)
        server.invites.clear()
        wren.sync()
        assertTrue(wrenState.now.invites.isEmpty())

        juniper.accept(juniper.parse(link(wren, song))!!)
        val received = juniperState.now.inbox.single()
        assertTrue(juniper.opened(received.id))
        assertTrue(juniperState.now.inbox.single().opened)
    }

    @Test
    fun `an invite the server no longer has says it expired`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val invite = juniper.parse(link(wren, song))!!
        server.invites.clear()
        assertEquals(Accepted.Expired, juniper.accept(invite))
        assertTrue(juniperState.now.friends.isEmpty())
    }

    @Test
    fun `a join that does not open ends the invite, and nobody is added`() = runTest {
        val (wren, wrenState) = phone("Wren")
        link(wren)
        val id = server.invites.keys.single()
        assertEquals(JoinAnswer.JOINED, server.acceptInvite(id, HuddleCrypto.encode(ByteArray(200) { 7 })))
        wren.sync()
        assertTrue(wrenState.now.friends.isEmpty())
        assertTrue(wrenState.now.invites.isEmpty())
        assertTrue(server.invites.isEmpty())
    }

    @Test
    fun `a phone without friends or invites never asks the server`() = runTest {
        val (wren, _) = phone("Wren")
        assertTrue(wren.sync())
        wren.played(bell, now)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `in front KJB asks when it opens and every 30 s on any page, never in the background, and stops when it goes`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        friends(wren, juniper)
        val before = server.fetches
        val front = wren.syncWhileInFront()
        runCurrent()
        assertEquals(before + 1, server.fetches)
        for (step in 1..3) {
            advanceTimeBy(Huddle.FAST_MS - 1)
            runCurrent()
            assertEquals(before + step, server.fetches)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(before + step + 1, server.fetches)
        }

        front.cancel()
        advanceTimeBy(3 * Huddle.HOUR_MS)
        runCurrent()
        assertEquals(before + 4, server.fetches)
    }

    @Test
    fun `a sync step without an answer never keeps the others from running`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        link(wren)
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        // The invite waiting gets no answer: the card from Juniper still arrives.
        server.silent = setOf("inviteStatus")
        assertFalse(wren.sync())
        assertEquals(listOf(song), wrenState.now.inbox.map { it.card })
        // Wren's own card gets no answer: Juniper's next one still arrives, and Wren's waits to go.
        server.silent = emptySet()
        val next = Card(CardKind.SONG, listOf(bell))
        juniper.send(wrenOnJuniper.id, next)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        server.silent = setOf("send")
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertFalse(wren.sync())
        assertEquals(listOf(next, song), wrenState.now.inbox.map { it.card })
        assertEquals(1, wrenState.now.outbox.size)
    }

    @Test
    fun `a sync without an answer is tried again after 30 s, 2 min and 10 min, then hourly, and at once when KJB is back in front`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        friends(wren, juniper)
        server.online = false
        val before = server.fetches
        val front = wren.syncWhileInFront()
        runCurrent()
        assertEquals(before + 1, server.fetches)
        for ((at, wait) in listOf(30_000L, 2 * 60_000L, 10 * 60_000L, Huddle.HOUR_MS).withIndex()) {
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals("not before try ${at + 2}", before + 1 + at, server.fetches)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("try ${at + 2}", before + 2 + at, server.fetches)
        }
        advanceTimeBy(Huddle.HOUR_MS)
        runCurrent()
        assertEquals("hourly from then on", before + 6, server.fetches)

        // KJB goes and comes back (the other profile, another app): it asks at once, and with an answer every 30 s again.
        front.cancel()
        server.online = true
        val again = wren.syncWhileInFront()
        runCurrent()
        assertEquals(before + 7, server.fetches)
        advanceTimeBy(Huddle.FAST_MS - 1)
        runCurrent()
        assertEquals(before + 7, server.fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(before + 8, server.fetches)
        again.cancel()
    }

    @Test
    fun `a card that could not go is sent again after 30 s, 2 min and 10 min, without waiting for the next sync`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        server.online = false
        val sends = server.requests.count { it == "send" }
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertEquals(sends + 1, server.requests.count { it == "send" })
        advanceTimeBy(30_000)
        assertEquals(sends + 2, server.requests.count { it == "send" })
        server.online = true
        advanceTimeBy(2 * 60_000L - 1)
        assertTrue(server.waiting(juniperOnWren.outBox).isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, server.waiting(juniperOnWren.outBox).size)
        assertTrue(wrenState.now.outbox.isEmpty())
        advanceTimeBy(Huddle.HOUR_MS)
        assertEquals("sent once, then no more tries", sends + 3, server.requests.count { it == "send" })
    }

    @Test
    fun `a sync without an answer that took long waits its whole time again, from its end`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        friends(wren, juniper)
        server.online = false
        // The inbox keeps KJB waiting 20 s, then gives no answer: a server past its timeouts.
        server.slowMs = 20_000
        val before = server.fetches
        val front = wren.syncWhileInFront()
        runCurrent()
        assertEquals(before + 1, server.fetches)
        advanceTimeBy(20_000 + 30_000 - 1)
        runCurrent()
        assertEquals("30 s after the try ended, not after it began", before + 1, server.fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(before + 2, server.fetches)
        front.cancel()
    }

    @Test
    fun `cards sent while the server does not answer share one set of next tries`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        server.online = false
        wren.send(juniperOnWren.id, song)
        wren.send(juniperOnWren.id, Card(CardKind.SONG, listOf(bell)))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        val sends = server.requests.count { it == "send" }
        advanceTimeBy(30_000)
        assertEquals("one next try for both, not one each", sends + 1, server.requests.count { it == "send" })
        server.online = true
        advanceTimeBy(2 * 60_000L)
        runCurrent()
        assertTrue(wrenState.now.outbox.isEmpty())
        assertEquals(2, server.waiting(juniperOnWren.outBox).size)
    }

    @Test
    fun `a card that joins another card's tries late gets a full set of its own after them`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        server.online = false
        // The first card's tries come at 35 s, 155 s and 755 s; the second gets no answer at 550 s and joins them.
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(545_000)
        wren.send(juniperOnWren.id, Card(CardKind.SONG, listOf(bell)))
        advanceTimeBy(760_000 - testScheduler.currentTime)
        assertEquals(2, wrenState.now.outbox.size)
        server.online = true
        // The tries start over for the second card: 30 s after the last one.
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(wrenState.now.outbox.isEmpty())
        assertEquals(2, server.waiting(juniperOnWren.outBox).size)
    }

    @Test
    fun `an inbox kept but not acknowledged is a sync without an answer, and the next acknowledges it`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        server.silent = setOf("ack")
        assertFalse(wren.sync())
        assertEquals(listOf(song), wrenState.now.inbox.map { it.card })
        assertEquals(1, server.waiting(juniperOnWren.inBox).size)
        server.silent = emptySet()
        assertTrue(wren.sync())
        assertTrue(server.waiting(juniperOnWren.inBox).isEmpty())
        assertEquals("the card came once", listOf(song), wrenState.now.inbox.map { it.card })
    }

    @Test
    fun `a message that does not open is read again 3 times, unacknowledged, then goes, each time with a reason code only`() = runTest {
        val logged = ArrayList<String>()
        val (wren, wrenState) = phone("Wren", log = { logged += it })
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        // Something that does not open waits first (another key), then a card that does.
        server.replay(juniperOnWren.inBox, HuddleCrypto.encode(ByteArray(120) { 7 }))
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        repeat(Huddle.NOT_OPENED_RETRIES) { read ->
            assertTrue(wren.sync())
            assertEquals("read ${read + 1}: neither is acknowledged", 2, server.waiting(juniperOnWren.inBox).size)
        }
        assertEquals(listOf(song), wrenState.now.inbox.map { it.card })
        assertTrue(wren.sync())
        assertTrue("the last read acknowledges both", server.waiting(juniperOnWren.inBox).isEmpty())
        assertEquals("the card came once", listOf(song), wrenState.now.inbox.map { it.card })
        assertEquals(
            List(Huddle.NOT_OPENED_RETRIES) { "huddle: inbox kept 1 that did not open, to read again (not_opened)" } + "huddle: inbox dropped 1 that did not open (not_opened)",
            logged.filter { "not_opened" in it },
        )
        // One that does not open after a card: the card is acknowledged, the other waits, its count started over.
        val next = Card(CardKind.SONG, listOf(bell))
        juniper.send(wrenOnJuniper.id, next)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        server.replay(juniperOnWren.inBox, HuddleCrypto.encode(ByteArray(120) { 9 }))
        assertTrue(wren.sync())
        assertEquals(listOf(next, song), wrenState.now.inbox.map { it.card })
        assertEquals(1, server.waiting(juniperOnWren.inBox).size)
        assertEquals(1, wrenState.now.friends.single().notOpenedReads)
        val secret = listOf("Wren", "Juniper", lantern.title, bell.title, juniperOnWren.id, juniperOnWren.inBox, juniperOnWren.outBox)
        assertTrue(logged.none { line -> secret.any { it in line } })
    }

    @Test
    fun `a read that finds none that does not open starts the count over, the inbox empty or not`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val (rook, rookState) = phone("Rook")
        rook.accept(rook.parse(link(wren))!!)
        assertTrue(wren.sync())
        val count = { wrenState.now.friends.single { it.id == juniperOnWren.id }.notOpenedReads }
        // One that does not open, then gone from the server unacknowledged (a play goes once read, a card after 30 days).
        server.replay(juniperOnWren.inBox, HuddleCrypto.encode(ByteArray(120) { 7 }))
        assertTrue(wren.sync())
        assertEquals(1, count())
        server.boxes.getValue(juniperOnWren.inBox).clear()
        rook.send(rookState.now.friends.single().id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertTrue(wren.sync())
        assertEquals("a read with only another friend's card", 0, count())

        server.replay(juniperOnWren.inBox, HuddleCrypto.encode(ByteArray(120) { 7 }))
        assertTrue(wren.sync())
        assertEquals(1, count())
        server.boxes.getValue(juniperOnWren.inBox).clear()
        assertTrue(wren.sync())
        assertEquals("a read with nothing at all", 0, count())
    }

    @Test
    fun `a message let go whose acknowledgement got no answer is let go again at the next read`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        server.replay(juniperOnWren.inBox, HuddleCrypto.encode(ByteArray(120) { 7 }))
        repeat(Huddle.NOT_OPENED_RETRIES) { assertTrue(wren.sync()) }
        server.silent = setOf("ack")
        assertFalse(wren.sync())
        assertEquals(1, server.waiting(juniperOnWren.inBox).size)
        server.silent = emptySet()
        assertTrue(wren.sync())
        assertTrue("let go at once, not after 3 more reads", server.waiting(juniperOnWren.inBox).isEmpty())
        assertEquals(0, wrenState.now.friends.single().notOpenedReads)
    }

    @Test
    fun `a sync step without an answer is logged with a reason code, never a name, an id or a title`() = runTest {
        val logged = ArrayList<String>()
        val (wren, wrenState) = phone("Wren", log = { logged += it })
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        link(wren)
        server.online = false
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        val front = wren.syncWhileInFront()
        runCurrent()
        front.cancel()
        assertTrue(logged.toString(), "huddle: invites not checked (no_answer)" in logged)
        assertTrue(logged.toString(), "huddle: outbox stopped (no_answer)" in logged)
        assertTrue(logged.toString(), "huddle: inbox not read (no_answer)" in logged)
        assertTrue(logged.toString(), "huddle: card not sent, next try in 30 s" in logged)
        assertTrue(logged.toString(), "huddle: sync failed 1 in a row, next try in 30 s" in logged)
        val secret = listOf("Wren", "Juniper", lantern.title, lantern.artist!!, juniperOnWren.id, juniperOnWren.inBox, juniperOnWren.outBox) + wrenState.now.invites.map { it.owner }
        assertTrue(logged.none { line -> secret.any { it in line } })
    }

    @Test
    fun `a card opened stays a card, and dismissed it is put away too`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        juniper.accept(juniper.parse(link(wren, song))!!)
        val id = juniperState.now.inbox.single().id
        assertTrue(juniper.opened(id))
        assertEquals(true to false, juniperState.now.inbox.single().let { it.opened to it.dismissed })
        assertTrue(juniper.opened(id, dismissed = true))
        assertEquals(true to true, juniperState.now.inbox.single().let { it.opened to it.dismissed })
    }

    @Test
    fun `a card held for a longer toast goes only once it ends, and Undo works until then`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val id = wren.send(juniperOnWren.id, song, holdMs = 3 * Huddle.UNDO_MS)!!
        advanceTimeBy(2 * Huddle.UNDO_MS)
        wren.flush()
        assertTrue("held while its toast shows", server.waiting(juniperOnWren.outBox).isEmpty())
        assertTrue(wren.unsend(id))
        wren.send(juniperOnWren.id, song, holdMs = 3 * Huddle.UNDO_MS)
        advanceTimeBy(3 * Huddle.UNDO_MS + 1)
        assertEquals(1, server.waiting(juniperOnWren.outBox).size)
        assertTrue(wrenState.now.outbox.isEmpty())
    }

    @Test
    fun `friends' plays stay on the phone 30 days, then go`() = runTest {
        assertEquals(30 * 24 * Huddle.HOUR_MS, Huddle.PLAYS_KEPT_MS)
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        juniper.sharePlays(wrenOnJuniper.id, true)
        juniper.played(bell, now + testScheduler.currentTime)
        wren.sync()
        assertEquals(1, wrenState.now.plays.size)
        advanceTimeBy(Huddle.PLAYS_KEPT_MS)
        wren.sync()
        assertEquals("still there on its 30th day", 1, wrenState.now.plays.size)
        advanceTimeBy(1)
        wren.sync()
        assertTrue(wrenState.now.plays.isEmpty())
    }

    @Test
    fun `friends' plays reach Suggestions the oldest first, from the time asked`() = runTest {
        val plays = listOf(FriendPlay("f1", 300, bell, favourite = true), FriendPlay("f2", 200, lantern), FriendPlay("f1", 100, lantern))
        assertEquals(
            listOf(
                SuggestionsPlay("f2", lantern.title, lantern.artist, lantern.album, lantern.durationMs, 200),
                SuggestionsPlay("f1", bell.title, bell.artist, bell.album, bell.durationMs, 300, favourite = true),
            ),
            HuddleFriends(MemoryHuddle(HuddleState(plays = plays))).since(200),
        )
        assertEquals(null, HuddleFriends(MemoryHuddle(HuddleState(plays = listOf(FriendPlay("f1", 1, bell.copy(durationMs = 0)))))).since(0).single().durationMs)
        assertTrue(HuddleFriends(MemoryHuddle()).since(0).isEmpty())
    }

    @Test
    fun `a cancelled invite goes from the phone and the server, and its link no longer works`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val invite = juniper.parse(link(wren, song))!!
        server.online = false
        assertFalse("kept while the server cannot hear it", wren.cancel(wrenState.now.invites.single().token))
        assertEquals(1, wrenState.now.invites.size)
        server.online = true
        assertTrue(wren.cancel(wrenState.now.invites.single().token))
        assertTrue(wrenState.now.invites.isEmpty())
        assertTrue(server.invites.isEmpty())
        assertEquals(Accepted.Expired, juniper.accept(invite))
        assertFalse(wren.cancel("no-such-invite"))
    }

    @Test
    fun `a backup opened with its passphrase brings the friends back on a new phone, and keeps the key for the next backups`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        friends(wren, juniper)
        assertNull("no passphrase, no Huddle in backups", wren.backupSection())
        wrenState.change { it.copy(backupKey = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)) }
        val section = wren.backupSection()!!
        val (newPhone, newState) = phone()
        assertNull(newPhone.restore(section, "wrong horse".toCharArray()))
        assertTrue(newState.now.friends.isEmpty())
        assertEquals(1, newPhone.restore(section, "correct horse".toCharArray()))
        assertEquals(wrenState.now.friends, newState.now.friends)
        assertEquals("Wren", newState.now.name)
        assertEquals(wrenState.now.backupKey, newState.now.backupKey)
        assertNotNull("the next backup needs no typing", newPhone.backupSection())
        newPhone.send(newState.now.friends.single().id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.sync()
        assertEquals("the friend hears from the restored phone", listOf(song), juniperState.now.inbox.map { it.card })
    }

    @Test
    fun `a link whose key was swapped joins no slot, and nobody is added on either side`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val real = juniper.parse(link(wren, song))!!
        val swapped = Invite(HuddleCrypto.keyPair(random).public, real.token, real.name, real.card)
        assertEquals(Accepted.Expired, juniper.accept(swapped))
        assertTrue(juniperState.now.friends.isEmpty())
        assertTrue(juniperState.now.inbox.isEmpty())
        assertTrue(wren.sync())
        assertEquals("the real invite still waits", 1, wrenState.now.invites.size)
        assertTrue(juniper.accept(real) is Accepted.Joined)
    }

    @Test
    fun `an accept whose answer was lost is sent again as it was, and both phones end as one friendship`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val invite = juniper.parse(link(wren))!!
        server.lostAccepts = 1
        assertEquals(Accepted.NoAnswer, juniper.accept(invite))
        assertTrue(juniperState.now.friends.isEmpty())
        val joined = juniper.accept(invite) as Accepted.Joined
        assertTrue(wren.sync())
        val juniperOnWren = wrenState.now.friends.single()
        assertEquals(juniperOnWren.outBox, joined.friend.inBox)
        juniper.send(joined.friend.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        assertEquals("the friendship works both ways", listOf(song), wrenState.now.inbox.map { it.card })
        assertTrue("nothing of the join is left waiting", juniperState.now.joins.isEmpty())
    }

    @Test
    fun `an accept whose answer was lost, read by the inviter before it is sent again, still joins`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val invite = juniper.parse(link(wren))!!
        server.lostAccepts = 1
        assertEquals(Accepted.NoAnswer, juniper.accept(invite))
        // Wren's KJB reads the accept and deletes the slot before Juniper tries again: the server no longer has it.
        assertTrue(wren.sync())
        assertTrue(server.invites.isEmpty())
        val joined = juniper.accept(invite) as Accepted.Joined
        assertEquals(wrenState.now.friends.single().outBox, joined.friend.inBox)
        juniper.send(joined.friend.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        assertEquals(listOf(song), wrenState.now.inbox.map { it.card })
        assertTrue(juniperState.now.joins.isEmpty())
    }

    @Test
    fun `an accept the phone could not write is sent again as it was`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val storage = MemoryHuddle(HuddleState(name = "Juniper"))
        val (juniper, _) = phone(storage = storage)
        val invite = juniper.parse(link(wren))!!
        val sent = server.requests.size
        storage.writable = false
        assertEquals(Accepted.NoAnswer, juniper.accept(invite))
        assertEquals("nothing is sent before the join is kept", sent, server.requests.size)
        storage.writable = true
        val joined = juniper.accept(invite) as Accepted.Joined
        wren.sync()
        assertEquals(wrenState.now.friends.single().outBox, joined.friend.inBox)
    }

    @Test
    fun `Joined names the card that came with the invite`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val joined = juniper.accept(juniper.parse(link(wren, song))!!) as Accepted.Joined
        assertEquals(juniperState.now.inbox.single().id, joined.received)
        val (rook, _) = phone("Rook")
        assertNull((rook.accept(rook.parse(link(wren))!!) as Accepted.Joined).received)
    }

    @Test
    fun `a card the server sends again after many plays is still dropped`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        val data = server.waiting(juniperOnWren.inBox).single().data
        wren.sync()
        juniper.sharePlays(wrenOnJuniper.id, true)
        repeat(250) { juniper.played(bell, now + testScheduler.currentTime + it) }
        wren.sync()
        server.replay(juniperOnWren.inBox, data)
        wren.sync()
        assertEquals(1, wrenState.now.inbox.size)
    }

    @Test
    fun `plays waiting to go are dropped once you listen privately or stop sharing with that friend`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        juniper.sharePlays(wrenOnJuniper.id, true)
        server.online = false
        juniper.played(bell, now)
        juniper.listenPrivately(HuddleState.FOREVER)
        server.online = true
        juniper.sync()
        assertTrue(server.waiting(wrenOnJuniper.outBox).isEmpty())
        assertTrue(juniperState.now.outbox.isEmpty())

        juniper.listenPrivately(null)
        server.online = false
        juniper.played(lantern, now + 1)
        juniper.sharePlays(wrenOnJuniper.id, false)
        server.online = true
        juniper.sync()
        assertTrue(server.waiting(wrenOnJuniper.outBox).isEmpty())
    }

    @Test
    fun `a friend's play or card dated in the future is kept as from now`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper", ahead = 10 * 24 * Huddle.HOUR_MS)
        val (_, wrenOnJuniper) = friends(wren, juniper)
        juniper.sharePlays(wrenOnJuniper.id, true)
        juniper.played(bell, now + 10 * 24 * Huddle.HOUR_MS)
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        val today = now + testScheduler.currentTime
        assertTrue(wrenState.now.plays.single().at <= today)
        assertTrue(wrenState.now.inbox.single().at <= today)
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
