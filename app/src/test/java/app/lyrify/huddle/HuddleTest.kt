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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Huddle between made-up phones on one fake server: invites, cards there and back once, Undo, offline, plays shared
 * only by the rules (per friend, Listen privately, a day at most), what a friend's player does now, removing a friend,
 * and how often KJB asks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HuddleTest {
    private val server = FakeServer()
    private val host = "kjb-huddle.up.railway.app"
    private val hosts = listOf(host, "huddle.example.org")
    private var now = 1_700_000_000_000L
    private val random = SecureRandom()
    private val song = Card(CardKind.SONG, listOf(lantern), note = "for the drive")

    /** Each phone's history, closed after the test. */
    private val histories = ArrayList<JdbcHuddleLog>()

    @After
    fun close() = histories.forEach(JdbcHuddleLog::close)

    private fun history(): JdbcHuddleLog = JdbcHuddleLog().also(histories::add)

    /** A phone whose clock is [ahead] of the server's. */
    private fun TestScope.phone(
        name: String? = null,
        storage: MemoryHuddle = MemoryHuddle(HuddleState(name = name)),
        ahead: Long = 0,
        history: JdbcHuddleLog = history(),
        log: (String) -> Unit = {},
    ): Pair<Huddle, MemoryHuddle> {
        server.clock = { now + testScheduler.currentTime }
        return Huddle(storage, server, host, hosts, { now + ahead + testScheduler.currentTime }, random, backgroundScope, history, log) to storage
    }

    /** The cards friends sent [this] phone, the newest first, as Sent to you's See all lists them. */
    private suspend fun Huddle.cards(): List<Received> = received(0, 100)

    /** Made-up friends [ids] as huddle.json holds them: Suggestions read the plays of friends still here. */
    private fun friendsOf(vararg ids: String): List<Friend> = ids.map { Friend(it, "Friend $it", "out-$it", "in-$it", "ok", "ik", "join-$it", addedAt = 0) }

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
        val (juniper, _) = phone("Juniper")
        val link = link(wren, song)
        assertTrue(link.startsWith("https://$host/i#"))
        assertEquals(1, wrenState.now.invites.size)
        assertEquals("the invite waits on the server under its owner id", wrenState.now.invites.single().owner, server.invites.values.single().first)
        val invite = juniper.parse(link)!!
        assertEquals("Wren", invite.name)
        val accepted = juniper.accept(invite) as Accepted.Joined
        assertEquals("Wren", accepted.friend.name)
        // The first song is there the moment KJB opens the link, from Wren.
        assertEquals(listOf(song), juniper.cards().map { it.card })
        assertEquals(accepted.friend.id, juniper.cards().single().friend)

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
        val (juniper, _) = phone("Juniper")
        val album = Card(CardKind.ALBUM, (1..40).map { lantern.copy(title = "Track $it") }, name = "Harbour Glass")
        val invite = juniper.parse(link(wren, album))!!
        assertNull(invite.card)
        juniper.accept(invite)
        wren.sync()
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.sync()
        assertEquals(listOf(album), juniper.cards().map { it.card })
    }

    @Test
    fun `a card sent arrives once, from the right friend, and leaves the server`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        val card = Card(CardKind.PLAYLIST, listOf(lantern, bell), name = "Sunday", note = "try these")
        assertNotNull(wren.send(juniperOnWren.id, card))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertEquals(1, server.waiting(juniperOnWren.outBox).size)
        assertEquals(Kind.CARD, server.kinds.values.last())

        assertTrue(juniper.sync())
        val received = juniper.cards().first()
        assertEquals(card, received.card)
        assertEquals(wrenOnJuniper.id, received.friend)
        assertFalse(received.opened)
        assertTrue("acknowledged", server.waiting(juniperOnWren.outBox).isEmpty())
        juniper.sync()
        assertEquals(1, juniper.cards().count { it.card == card })
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
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        val data = server.waiting(juniperOnWren.outBox).single().data
        juniper.sync()
        server.replay(juniperOnWren.outBox, data)
        juniper.sync()
        assertEquals(1, juniper.cards().size)
        assertTrue("the replay is acknowledged too", server.waiting(juniperOnWren.outBox).isEmpty())
        // The server hands Wren's own card back to Wren: it was sealed for the other way, so it never opens.
        server.replay(juniperOnWren.inBox, data)
        wren.sync()
        assertTrue(wren.cards().isEmpty())
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
        assertTrue(juniper.cards().isEmpty())
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
        advanceTimeBy(2 * 60_000L)
        runCurrent()
        assertEquals(sends + 3, server.requests.count { it == "send" })
        server.online = true
        advanceTimeBy(10 * 60_000L - 1)
        runCurrent()
        assertTrue(server.waiting(wrenOnJuniper.outBox).isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, server.waiting(wrenOnJuniper.outBox).size)
        assertTrue(juniperState.now.outbox.isEmpty())
        advanceTimeBy(Huddle.HOUR_MS)
        runCurrent()
        assertEquals("sent once, then no more tries", sends + 4, server.requests.count { it == "send" })
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
    fun `a newcomer who changes to another friend's name shows under a stand-in of that name, and is asked again`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (rook, _) = phone("Rook")
        val (other, otherState) = phone("Juniper")
        friends(wren, juniper)
        rook.accept(checkNotNull(rook.parse(link(wren))))
        other.accept(checkNotNull(other.parse(link(wren))))
        wren.sync()
        val newcomer = wrenState.now.friends.single { it.taken != null }
        assertEquals("Juniper 2", newcomer.shownName)
        other.sync()
        assertTrue(other.setName("rook"))
        wren.sync()
        assertEquals("rook 2", wrenState.now.friends.single { it.id == newcomer.id }.shownName)
        other.sync()
        assertTrue(otherState.now.friends.single().asksNewName)
    }

    @Test
    fun `a stand-in under a name you gave the friend stays taken, so the next newcomer gets another`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (second, _) = phone("Juniper")
        val (third, _) = phone("Juniper")
        friends(wren, juniper)
        second.accept(checkNotNull(second.parse(link(wren))))
        wren.sync()
        val renamed = wrenState.now.friends.single { it.taken != null }
        assertTrue(wren.rename(renamed.id, "June"))
        third.accept(checkNotNull(third.parse(link(wren))))
        wren.sync()
        assertEquals("Juniper 3", wrenState.now.friends.single { it.id != renamed.id && it.taken != null }.taken)
        // Your name for them taken back: every friend shows a name of their own.
        assertTrue(wren.rename(renamed.id, null))
        assertEquals(3, wrenState.now.friends.map { it.shownName.lowercase() }.distinct().size)
    }

    @Test
    fun `a join written on a second try, after the friend of the same name left, gets no stand-in and asks nothing`() = runTest {
        val (wren, _) = phone("Wren")
        val (other, otherState) = phone("wren")
        val rookState = MemoryHuddle(HuddleState(name = "Rook"))
        // The friend who leaves while the first write of a new friend fails (a full disk): the change runs again.
        var leaves: String? = null
        val storage = object : HuddleStorage {
            override val state = rookState.state

            override suspend fun change(change: (HuddleState) -> HuddleState): Boolean {
                val gone = leaves
                if (gone != null && change(rookState.now).friends.size > rookState.now.friends.size) {
                    leaves = null
                    rookState.change { state -> state.copy(friends = state.friends.filterNot { it.id == gone }) }
                }
                return rookState.change(change)
            }
        }
        val rook = Huddle(storage, server, host, hosts, { now + testScheduler.currentTime }, random, backgroundScope, history())
        leaves = friends(wren, rook).second.id
        val joined = rook.accept(checkNotNull(rook.parse(link(other)))) as Accepted.Joined
        assertNull(joined.friend.taken)
        assertEquals(listOf("wren"), rookState.now.friends.map { it.shownName })
        runCurrent()
        other.sync()
        assertFalse(otherState.now.friends.single().asksNewName)
    }

    @Test
    fun `your new name reaches every friend, and a name you gave a friend still wins on your phone`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (rook, rookState) = phone("Rook")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        rook.accept(checkNotNull(rook.parse(link(wren))))
        wren.sync()
        assertTrue(wren.setName("  Wren   Calloway "))
        juniper.sync()
        rook.sync()
        assertEquals("Wren Calloway", juniperState.now.friends.single().name)
        assertEquals("Wren Calloway", rookState.now.friends.single().name)
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
    fun `removing a friend tells them, the friendship goes from both phones and the server, and each keeps the history as a past friend`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        wren.send(juniperOnWren.id, song)
        wren.played(bell, now + testScheduler.currentTime)
        juniper.played(lantern, now + testScheduler.currentTime)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.sync()
        wren.sync()
        assertEquals(1, juniper.friendPlays(wrenOnJuniper.id, 0, 10).size)
        assertEquals(1, wren.friendPlays(juniperOnWren.id, 0, 10).size)
        assertTrue(wren.remove(juniperOnWren.id))
        assertTrue(wrenState.now.friends.isEmpty())
        assertFalse(juniperOnWren.inBox in server.boxes)
        // Changed on purpose (user 2026-11-23, item 59): what went between you stays, under the name you saw them by.
        assertEquals(listOf(juniperOnWren.id to "Juniper"), wrenState.now.past.map { it.id to it.name })
        assertEquals("what went between you stays", listOf(song), wren.thread(juniperOnWren.id, 0, 10).map { it.card })
        assertEquals(listOf(lantern), wren.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        juniper.sync()
        assertTrue(juniperState.now.friends.isEmpty())
        assertEquals("their goodbye: a past friend on their phone too", listOf(wrenOnJuniper.id to "Wren"), juniperState.now.past.map { it.id to it.name })
        assertEquals("their cards stay", listOf(song), juniper.cards().map { it.card })
        assertEquals("and their plays", listOf(bell), juniper.friendPlays(wrenOnJuniper.id, 0, 10).map { it.song })
        assertTrue(server.boxes.values.all { it.isEmpty() })
        assertTrue("nothing waits to go to them", wrenState.now.outbox.isEmpty() && juniperState.now.outbox.isEmpty())
    }

    @Test
    fun `a goodbye keeps the history under the name you saw them by, drops what came after it unkept, and their mailbox is never read again`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper, song)
        assertTrue(wren.rename(juniperOnWren.id, "June"))
        juniper.played(lantern, now + testScheduler.currentTime)
        // Later than the invite's card, so the thread's order is known.
        advanceTimeBy(1_000)
        juniper.send(wrenOnJuniper.id, Card(CardKind.SONG, listOf(bell), note = "one more"))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertTrue(juniper.remove(wrenOnJuniper.id))
        // After their goodbye, in the same mailbox (a second phone of theirs, a KJB gone wrong): sealed as their KJB seals.
        server.replay(wrenOnJuniper.outBox, HuddleCrypto.encode(HuddleCrypto.seal(HuddleCrypto.decode(wrenOnJuniper.outKey)!!, Messages.encode(Message.Share("late-card", now + testScheduler.currentTime, Card(CardKind.SONG, listOf(bell), note = "after the goodbye"))), wrenOnJuniper.outBox.encodeToByteArray(), random)))
        server.replay(wrenOnJuniper.outBox, HuddleCrypto.encode(HuddleCrypto.seal(HuddleCrypto.decode(wrenOnJuniper.outKey)!!, Messages.encode(Message.Play("late-play", now + testScheduler.currentTime, bell)), wrenOnJuniper.outBox.encodeToByteArray(), random)))
        assertTrue(wren.sync())
        assertTrue(wrenState.now.friends.isEmpty())
        assertEquals(listOf(juniperOnWren.id to "June"), wrenState.now.past.map { it.id to it.name })
        assertEquals("both ways, the newest first", listOf("one more", "for the drive"), wren.thread(juniperOnWren.id, 0, 10).map { it.card.note })
        assertEquals(listOf(lantern), wren.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        assertNull("a card after their goodbye is never kept", wren.card("late-card"))
        server.replay(wrenOnJuniper.outBox, HuddleCrypto.encode(HuddleCrypto.seal(HuddleCrypto.decode(wrenOnJuniper.outKey)!!, Messages.encode(Message.Share("later-card", now + testScheduler.currentTime, Card(CardKind.SONG, listOf(bell), note = "later still"))), wrenOnJuniper.outBox.encodeToByteArray(), random)))
        val reads = server.fetches
        assertTrue(wren.sync())
        assertEquals("a past friend's mailbox is not asked for", reads, server.fetches)
        assertNull(wren.card("later-card"))
        assertEquals(1, wrenState.now.past.size)
    }

    @Test
    fun `only friends still here reach Suggestions, never the plays of a restore whose friends could not be written`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        friends(wren, juniper)
        juniper.played(lantern, now + testScheduler.currentTime)
        wren.sync()
        wrenState.change { it.copy(backupKey = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)) }
        val section = wren.backupSection()!!
        val (newPhone, newState) = phone()
        // The history takes the backup's plays first, then huddle.json cannot be written: nobody is a friend here.
        newState.writable = false
        assertNull(newPhone.restore(section, "correct horse".toCharArray()))
        newState.writable = true
        assertTrue(newState.now.friends.isEmpty())
        assertTrue(HuddleFriends(newPhone).since(0).isEmpty())
    }

    @Test
    fun `Delete history lets a past friend's cards and plays go from the phone and them from Past friends, nobody else's`() = runTest {
        val history = history()
        val (wren, wrenState) = phone("Wren", history = history)
        val (juniper, _) = phone("Juniper")
        val (rook, _) = phone("Rook")
        val (juniperOnWren, _) = friends(wren, juniper, song)
        val (wrenOnRook, rookOnWren) = friends(rook, wren)
        juniper.played(lantern, now + testScheduler.currentTime)
        rook.played(bell, now + testScheduler.currentTime)
        rook.send(wrenOnRook.id, Card(CardKind.SONG, listOf(bell), note = "from Rook"))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        assertFalse("a friend still here has no Delete history", wren.deleteHistory(rookOnWren.id))
        assertTrue(wren.remove(juniperOnWren.id))
        history.forgetFails = true
        assertFalse("nothing changes while the history cannot let go", wren.deleteHistory(juniperOnWren.id))
        assertEquals(listOf(juniperOnWren.id), wrenState.now.past.map { it.id })
        assertEquals(1, wren.thread(juniperOnWren.id, 0, 10).size)
        history.forgetFails = false
        val written = wren.cardsWritten.value to wren.playsWritten.value
        assertTrue(wren.deleteHistory(juniperOnWren.id))
        assertTrue(wrenState.now.past.isEmpty())
        assertTrue(wren.thread(juniperOnWren.id, 0, 10).isEmpty())
        assertTrue(wren.friendPlays(juniperOnWren.id, 0, 10).isEmpty())
        assertTrue("the open lists read again", wren.cardsWritten.value > written.first && wren.playsWritten.value > written.second)
        assertEquals("Rook's stay", listOf("from Rook"), wren.thread(rookOnWren.id, 0, 10).map { it.card.note })
        assertEquals(listOf(bell), wren.friendPlays(rookOnWren.id, 0, 10).map { it.song })
        assertFalse("once is enough", wren.deleteHistory(juniperOnWren.id))
        // The rows go before Past friends is written: one that cannot be written keeps the person listed, with nothing
        // left to show, and Delete history again finishes.
        assertTrue(wren.remove(rookOnWren.id))
        wrenState.writable = false
        assertFalse(wren.deleteHistory(rookOnWren.id))
        wrenState.writable = true
        assertEquals(listOf(rookOnWren.id), wrenState.now.past.map { it.id })
        assertTrue(wren.thread(rookOnWren.id, 0, 10).isEmpty())
        assertTrue(wren.deleteHistory(rookOnWren.id))
        assertTrue(wrenState.now.past.isEmpty())
    }

    @Test
    fun `a past friend's plays stay for What they played but no longer reach Suggestions`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (rook, _) = phone("Rook")
        val (juniperOnWren, _) = friends(wren, juniper)
        val (_, rookOnWren) = friends(rook, wren)
        juniper.played(lantern, now + testScheduler.currentTime)
        rook.played(bell, now + testScheduler.currentTime)
        wren.sync()
        assertEquals(setOf(juniperOnWren.id, rookOnWren.id), HuddleFriends(wren).since(0).map { it.friend }.toSet())
        assertTrue(wren.remove(juniperOnWren.id))
        assertEquals(listOf(lantern), wren.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        assertEquals(listOf(rookOnWren.id), HuddleFriends(wren).since(0).map { it.friend })
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
        val (juniper, _) = phone("Juniper")
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
        val received = juniper.cards().single()
        assertTrue(juniper.opened(received.id))
        assertTrue(juniper.cards().single().opened)
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
    fun `in front KJB keeps one read held, the next asked as soon as one answers, so a friend's pause shows at once, and never in the background`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val before = server.fetches
        val front = wren.syncWhileInFront()
        runCurrent()
        assertEquals(before + 1, server.fetches)
        // Nothing lands: the read is held its whole wait, then the next one is asked at once.
        advanceTimeBy(Huddle.WAIT_MS - 1)
        runCurrent()
        assertEquals(before + 1, server.fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(before + 2, server.fetches)
        // Juniper pauses: Wren's held read answers with it, once the burst's half second is over.
        juniper.listening(NowState.PAUSED, 30_000, now + testScheduler.currentTime) { bell to false }
        advanceTimeBy(Huddle.NOW_GAP_MS)
        runCurrent()
        assertEquals(NowState.PAUSED, wren.state.first().live[juniperOnWren.id]?.state)
        assertEquals(before + 3, server.fetches)

        front.cancel()
        advanceTimeBy(3 * Huddle.HOUR_MS)
        runCurrent()
        assertEquals(before + 3, server.fetches)
    }

    @Test
    fun `a now goes to each friend you share with, the newest of a burst only, never kept on the phone`() = runTest {
        val (wren, _) = phone("Wren")
        val (rook, _) = phone("Rook")
        val (juniper, juniperState) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        val (_, rookOnJuniper) = friends(rook, juniper)
        juniper.sharePlays(rookOnJuniper.id, false)
        val sends = server.requests.count { it == "send" }
        juniper.listening(NowState.PLAYING, 0, now) { lantern to false }
        advanceTimeBy(100)
        // A skip right after: only the newest goes.
        juniper.listening(NowState.PLAYING, 0, now + 100) { bell to false }
        advanceTimeBy(Huddle.NOW_GAP_MS - 1)
        runCurrent()
        assertEquals(sends, server.requests.count { it == "send" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(sends + 1, server.requests.count { it == "send" })
        assertEquals(listOf(Kind.NOW), server.waiting(wrenOnJuniper.outBox).map { server.kinds[it.seq] })
        assertTrue("not to a friend you stopped sharing with", server.waiting(rookOnJuniper.outBox).isEmpty())
        assertTrue("never kept on the phone", juniperState.now.outbox.isEmpty())
        wren.sync()
        assertEquals(listOf(bell), wren.state.first().live.values.map { it.song })
    }

    @Test
    fun `a now without an answer goes again after 2 s, 5 s, 15 s, 30 s and 60 s while it is the newest, never kept on the phone`() = runTest {
        val (wren, _) = phone("Wren")
        val (rook, _) = phone("Rook")
        val (juniper, juniperState) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        friends(rook, juniper)
        val sends = { server.requests.count { it == "send" } }
        val before = sends()
        server.online = false
        juniper.listening(NowState.PAUSED, 9_000, now) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS)
        runCurrent()
        assertEquals("one to each friend", before + 2, sends())
        // The last try is under 2 minutes after the pause: before a friend lets the "playing" it replaces go (150 s).
        for ((tries, wait) in listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L).withIndex()) {
            advanceTimeBy(wait - 1)
            runCurrent()
            assertEquals(before + 2 + 2 * tries, sends())
            advanceTimeBy(1)
            runCurrent()
            assertEquals("try ${tries + 2}", before + 4 + 2 * tries, sends())
        }
        advanceTimeBy(Huddle.HOUR_MS)
        runCurrent()
        assertEquals("then the next change or heartbeat replaces it", before + 12, sends())
        assertTrue("never kept on the phone", juniperState.now.outbox.isEmpty())

        // Back after the first try: the second reaches both, and no more go.
        juniper.listening(NowState.PLAYING, 9_000, now + testScheduler.currentTime) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 2_000 - 1)
        runCurrent()
        server.online = true
        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(Huddle.HOUR_MS)
        runCurrent()
        assertEquals(before + 16, sends())
        assertEquals(listOf(Kind.NOW), server.waiting(wrenOnJuniper.outBox).map { server.kinds[it.seq] })

        // A newer one in the meantime: only the newer is tried again.
        server.online = false
        juniper.listening(NowState.PAUSED, 20_000, now + testScheduler.currentTime) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1_000)
        juniper.listening(NowState.PLAYING, 20_000, now + testScheduler.currentTime) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1_000)
        runCurrent()
        assertEquals("the first's try at 2 s did not go", before + 20, sends())
    }

    @Test
    fun `what a held read brings from a friend removed meanwhile is dropped, never kept or acknowledged`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        val reading = async { wren.sync(Huddle.WAIT_MS) }
        runCurrent()
        assertTrue(wren.remove(juniperOnWren.id))
        val acks = server.requests.count { it == "ack" }
        // Juniper has not read the goodbye yet: a card lands in the box the held read asked for.
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        runCurrent()
        assertTrue(reading.isCompleted)
        assertTrue(wrenState.now.inbox.isEmpty())
        // Nor in the history, which keeps a read's cards before huddle.json.
        assertTrue(wren.cards().isEmpty())
        assertEquals("nothing of theirs read on: no acknowledgement", acks, server.requests.count { it == "ack" })
    }

    @Test
    fun `with no song of yours to share, a book or a podcast, friends who saw your last one see it stop`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        juniper.listening(NowState.PLAYING, 0, now) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        wren.sync()
        assertEquals(NowState.PLAYING, wren.state.first().live[juniperOnWren.id]?.state)
        juniper.listening(NowState.PLAYING, 0, now + 1_000) { null }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        wren.sync()
        assertEquals(Message.Now("-", now + testScheduler.currentTime - 1, lantern, NowState.STOPPED, 0), wren.state.first().live[juniperOnWren.id]?.copy(id = "-"))
        val sends = server.requests.count { it == "send" }
        juniper.listening(NowState.PLAYING, 0, now + 2_000) { null }
        advanceTimeBy(Huddle.HOUR_MS)
        assertEquals("told once", sends, server.requests.count { it == "send" })
    }

    @Test
    fun `Listen privately tells friends who saw your song that it stopped, and nothing more until it is off`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        juniper.listening(NowState.PLAYING, 0, now) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        juniper.listenPrivately(now + Huddle.HOUR_MS)
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        wren.sync()
        assertEquals(NowState.STOPPED, wren.state.first().live[juniperOnWren.id]?.state)
        val sends = server.requests.count { it == "send" }
        juniper.listening(NowState.PLAYING, 0, now + 5_000) { bell to false }
        advanceTimeBy(Huddle.HOUR_MS)
        assertEquals(sends, server.requests.count { it == "send" })
        juniper.listenPrivately(null)
        juniper.listening(NowState.PLAYING, 0, now + testScheduler.currentTime) { bell to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        wren.sync()
        assertEquals(bell, wren.state.first().live[juniperOnWren.id]?.song)
        assertEquals(NowState.PLAYING, wren.state.first().live[juniperOnWren.id]?.state)
    }

    @Test
    fun `KJB opened finds each friend's newest now at once, and a read held after it waits for a newer one`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        juniper.listening(NowState.PLAYING, 30_000, now) { bell to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        assertTrue(wren.sync())
        // Wren's KJB starts again: nothing in memory, Juniper's now still on the server.
        val (opened, _) = phone(storage = wrenState)
        assertTrue(opened.sync())
        assertEquals(NowState.PLAYING, opened.state.first().live[juniperOnWren.id]?.state)
        val reading = async { opened.sync(Huddle.WAIT_MS) }
        advanceTimeBy(Huddle.WAIT_MS / 2)
        runCurrent()
        assertFalse("held past the now it has", reading.isCompleted)
        juniper.listening(NowState.PAUSED, 40_000, now + testScheduler.currentTime) { bell to false }
        advanceTimeBy(Huddle.NOW_GAP_MS)
        runCurrent()
        assertTrue(reading.isCompleted)
        assertEquals(NowState.PAUSED, opened.state.first().live[juniperOnWren.id]?.state)
    }

    @Test
    fun `KJB back in front while a read is held waits for its answer, never holding a second`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val before = server.fetches
        val front = wren.syncWhileInFront()
        runCurrent()
        front.cancel()
        val again = wren.syncWhileInFront()
        runCurrent()
        assertEquals("the read held from before answers first", before + 1, server.fetches)
        juniper.listening(NowState.PAUSED, 9_000, now + testScheduler.currentTime) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS)
        runCurrent()
        assertEquals(NowState.PAUSED, wren.state.first().live[juniperOnWren.id]?.state)
        assertEquals("then one read held again", before + 2, server.fetches)
        advanceTimeBy(Huddle.WAIT_MS - 1)
        runCurrent()
        assertEquals(before + 2, server.fetches)
        again.cancel()
    }

    @Test
    fun `a friend's now is as old as the server says, whatever their phone's clock says`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper", ahead = -5 * 60_000L)
        val (juniperOnWren, _) = friends(wren, juniper)
        val sent = now + testScheduler.currentTime + Huddle.NOW_GAP_MS
        juniper.listening(NowState.PLAYING, 30_000, now - 5 * 60_000L + testScheduler.currentTime) { bell to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        wren.sync()
        assertEquals(sent, wren.state.first().live[juniperOnWren.id]?.at)
    }

    @Test
    fun `a removed friend's now goes from memory with them`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        juniper.listening(NowState.PLAYING, 0, now) { bell to false }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        wren.sync()
        assertEquals(setOf(juniperOnWren.id), wren.state.first().live.keys)
        assertTrue(wren.remove(juniperOnWren.id))
        assertTrue(wren.state.first().live.isEmpty())
    }

    @Test
    fun `a friend's now shows on the other phone in memory only, and a read of only plays and nows asks no acknowledgement`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        juniper.listening(NowState.PLAYING, 30_000, now) { bell to true }
        advanceTimeBy(Huddle.NOW_GAP_MS + 1)
        juniper.played(bell, now)
        val acks = server.requests.count { it == "ack" }
        assertTrue(wren.sync())
        val live = wren.state.first().live
        assertEquals(setOf(juniperOnWren.id), live.keys)
        assertEquals("as of when the server got it", listOf(Message.Now("-", now + Huddle.NOW_GAP_MS, bell, NowState.PLAYING, 30_000, favourite = true)), live.values.map { it.copy(id = "-") })
        assertTrue("in memory only, never in huddle.json", wrenState.now.live.isEmpty())
        assertFalse("never in huddle.json", HuddleJson.encode(wren.state.first()).contains("\"live\""))
        assertEquals("the play is kept as before", listOf(FriendPlay(juniperOnWren.id, now, bell)), wrenState.now.plays)
        assertEquals("a play goes once read, a now stays the newest: nothing to acknowledge", acks, server.requests.count { it == "ack" })
        assertEquals(listOf(Kind.NOW), server.waiting(wrenOnJuniper.outBox).map { server.kinds[it.seq] })
        // A card still is acknowledged.
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        assertEquals(acks + 1, server.requests.count { it == "ack" })
    }

    @Test
    fun `a now, a card and its Undo go while a read is held, never after it`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val front = wren.syncWhileInFront()
        runCurrent()
        wren.listening(NowState.PLAYING, 0, now) { lantern to false }
        advanceTimeBy(Huddle.NOW_GAP_MS)
        runCurrent()
        assertEquals(listOf(Kind.NOW), server.waiting(juniperOnWren.outBox).map { server.kinds[it.seq] })
        val id = wren.send(juniperOnWren.id, song)!!
        val asked = testScheduler.currentTime
        assertTrue(wren.unsend(id))
        assertEquals("Undo never waits for a held read", asked, testScheduler.currentTime)
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        runCurrent()
        assertEquals(listOf(Kind.NOW, Kind.CARD), server.waiting(juniperOnWren.outBox).map { server.kinds[it.seq] })
        front.cancel()
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
        assertEquals(listOf(song), wren.cards().map { it.card })
        // Wren's own card gets no answer: Juniper's next one still arrives, and Wren's waits to go.
        server.silent = emptySet()
        val next = Card(CardKind.SONG, listOf(bell))
        juniper.send(wrenOnJuniper.id, next)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        server.silent = setOf("send")
        wren.send(juniperOnWren.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertFalse(wren.sync())
        assertEquals(listOf(next, song), wren.cards().map { it.card })
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

        // KJB goes and comes back (the other profile, another app): it asks at once, and with an answer holds a read again.
        front.cancel()
        server.online = true
        val again = wren.syncWhileInFront()
        runCurrent()
        assertEquals(before + 7, server.fetches)
        advanceTimeBy(Huddle.WAIT_MS - 1)
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
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        server.silent = setOf("ack")
        assertFalse(wren.sync())
        assertEquals(listOf(song), wren.cards().map { it.card })
        assertEquals(1, server.waiting(juniperOnWren.inBox).size)
        server.silent = emptySet()
        assertTrue(wren.sync())
        assertTrue(server.waiting(juniperOnWren.inBox).isEmpty())
        assertEquals("the card came once", listOf(song), wren.cards().map { it.card })
    }

    @Test
    fun `a box whose key does not read is let go as one that does not open, so a held read is not answered at once for ever`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        wrenState.change { state -> state.copy(friends = state.friends.map { it.copy(inKey = "not a key") }) }
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        repeat(Huddle.NOT_OPENED_RETRIES + 1) { assertTrue(wren.sync(Huddle.WAIT_MS)) }
        assertTrue("let go after its reads", server.waiting(juniperOnWren.inBox).isEmpty())
        val before = server.fetches
        val front = wren.syncWhileInFront()
        advanceTimeBy(Huddle.WAIT_MS - 1)
        runCurrent()
        assertEquals("the next read is held its whole wait", before + 1, server.fetches)
        front.cancel()
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
        assertEquals(listOf(song), wren.cards().map { it.card })
        assertTrue(wren.sync())
        assertTrue("the last read acknowledges both", server.waiting(juniperOnWren.inBox).isEmpty())
        assertEquals("the card came once", listOf(song), wren.cards().map { it.card })
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
        assertEquals(listOf(next, song), wren.cards().map { it.card })
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
        val (juniper, _) = phone("Juniper")
        juniper.accept(juniper.parse(link(wren, song))!!)
        val id = juniper.cards().single().id
        assertTrue(juniper.opened(id))
        assertEquals(true to false, juniper.cards().single().let { it.opened to it.dismissed })
        assertTrue(juniper.opened(id, dismissed = true))
        assertEquals(true to true, juniper.cards().single().let { it.opened to it.dismissed })
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
    fun `friends' plays stay on the phone a year, then go, while each friend's latest stays in huddle_json for now playing`() = runTest {
        // Changed on purpose (user 2026-11-23, item 55): a year, in the history, where it was 30 days in huddle.json.
        assertEquals(365 * 24 * Huddle.HOUR_MS, Huddle.PLAYS_KEPT_MS)
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        juniper.played(bell, now + testScheduler.currentTime - 1)
        juniper.played(lantern, now + testScheduler.currentTime)
        wren.sync()
        assertEquals(listOf(lantern, bell), wren.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        assertEquals("huddle.json keeps their latest only", listOf(lantern), wrenState.now.plays.map { it.song })
        advanceTimeBy(Huddle.PLAYS_KEPT_MS - 1)
        wren.sync()
        assertEquals("still there on its 365th day", 2, wren.friendPlays(juniperOnWren.id, 0, 10).size)
        advanceTimeBy(1)
        wren.sync()
        assertEquals(listOf(lantern), wren.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        advanceTimeBy(1)
        wren.sync()
        assertTrue(wren.friendPlays(juniperOnWren.id, 0, 10).isEmpty())
        assertTrue(wrenState.now.plays.isEmpty())
    }

    @Test
    fun `cards stay on the phone for good, both ways`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        repeat(101) { wren.send(juniperOnWren.id, Card(CardKind.SONG, listOf(bell), note = "song $it")) }
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.sync()
        advanceTimeBy(5 * 365 * 24 * Huddle.HOUR_MS)
        wren.sync()
        juniper.sync()
        assertEquals(listOf(song), wren.cards().map { it.card })
        assertEquals(101, juniper.received(0, 200).size)
        assertEquals("all 101 sent and the one received", 102, wren.thread(juniperOnWren.id, 0, 200).size)
    }

    @Test
    fun `friends' plays reach Suggestions the oldest first, from the time asked`() = runTest {
        val plays = listOf(FriendPlay("f1", now - 100, bell, favourite = true), FriendPlay("f2", now - 200, lantern), FriendPlay("f1", now - 300, lantern))
        // Changed on purpose: the plays come from the history, here as huddle.json kept them before and moved once; and
        // (HY2) only friends still here reach Suggestions, so the state names them.
        val (wren, _) = phone(storage = MemoryHuddle(HuddleState(name = "Wren", friends = friendsOf("f1", "f2"), plays = plays)))
        assertEquals(
            listOf(
                SuggestionsPlay("f2", lantern.title, lantern.artist, lantern.album, lantern.durationMs, now - 200),
                SuggestionsPlay("f1", bell.title, bell.artist, bell.album, bell.durationMs, now - 100, favourite = true),
            ),
            HuddleFriends(wren).since(now - 200),
        )
        val (rook, _) = phone(storage = MemoryHuddle(HuddleState(name = "Rook", friends = friendsOf("f1"), plays = listOf(FriendPlay("f1", now, bell.copy(durationMs = 0))))))
        assertEquals(null, HuddleFriends(rook).since(0).single().durationMs)
        assertTrue(HuddleFriends(phone("Kai").first).since(0).isEmpty())
    }

    @Test
    fun `Suggestions get the same friends' plays from the history as from huddle_json before, ties in the same order`() = runTest {
        val songs = listOf(bell, lantern, bell.copy(artist = null), lantern.copy(album = null, durationMs = 0))
        // Newest first, as huddle.json kept them; some share a time (a friend's clock ahead, kept as from now).
        val plays = (0 until 300).map { FriendPlay("f${it % 3}", now - (it / 2) * 1_000L, songs[it % songs.size], favourite = it % 7 == 0) }.distinct()
        val from = now - 120 * 1_000L
        // What HuddleFriends gave over huddle.json before the history.
        val before = plays.filter { it.at >= from }.sortedBy { it.at }.map { play ->
            SuggestionsPlay(play.friend, play.song.title, play.song.artist, play.song.album, play.song.durationMs.takeIf { it > 0 }, play.at, play.favourite)
        }
        val (wren, _) = phone(storage = MemoryHuddle(HuddleState(name = "Wren", friends = friendsOf("f0", "f1", "f2"), plays = plays)))
        assertEquals(before, HuddleFriends(wren).since(from))
    }

    @Test
    fun `cards and plays huddle_json kept before the history move there once, nothing lost, and a second run adds nothing`() = runTest {
        val opened = Received("m1", "f1", 30, song, opened = true, dismissed = true)
        val inbox = listOf(opened, Received("m2", "f2", 20, Card(CardKind.ALBUM, listOf(bell, lantern), name = "Harbour Glass")), Received("m3", "f1", 10, song))
        val plays = (1..5_000L).map { FriendPlay(if (it % 2 == 0L) "f1" else "f2", now - it * 60_000, if (it % 3 == 0L) bell else lantern, favourite = it % 5 == 0L) }
        val storage = MemoryHuddle(HuddleState(name = "Wren", inbox = inbox, plays = plays))
        val history = history()
        val (wren, wrenState) = phone(storage = storage, history = history)
        assertEquals(inbox, wren.cards())
        assertEquals(plays.filter { it.friend == "f1" }, wren.friendPlays("f1", 0, 5_000))
        assertEquals(plays.filter { it.friend == "f2" }, wren.friendPlays("f2", 0, 5_000))
        assertTrue("the cards left huddle.json", wrenState.now.inbox.isEmpty())
        assertEquals("each friend's latest stays", setOf(plays[0], plays[1]), wrenState.now.plays.toSet())
        // KJB again (a new run over the same files), and a run whose huddle.json write was lost after the history's.
        val (again, _) = phone(storage = storage, history = history)
        assertEquals(inbox, again.cards())
        // (HY2) Suggestions read friends still here: this state names them.
        val (lost, _) = phone(storage = MemoryHuddle(HuddleState(name = "Wren", friends = friendsOf("f1", "f2"), inbox = inbox, plays = plays)), history = history)
        assertEquals(inbox, lost.cards())
        assertEquals(2_500, lost.friendPlays("f1", 0, 10_000).size)
        assertEquals(plays.size, HuddleFriends(lost).since(0).size)
    }

    @Test
    fun `while the history cannot be written, huddle_json keeps its cards and plays as they were, and they move once it can`() = runTest {
        val inbox = listOf(Received("m1", "f1", 30, song))
        val plays = listOf(FriendPlay("f1", now - 10, bell), FriendPlay("f1", now - 20, lantern))
        val history = history().apply { failing = true }
        val (wren, wrenState) = phone(storage = MemoryHuddle(HuddleState(name = "Wren", inbox = inbox, plays = plays)), history = history)
        assertTrue(wren.cards().isEmpty())
        assertEquals(inbox, wrenState.now.inbox)
        assertEquals(plays, wrenState.now.plays)
        history.failing = false
        assertEquals(inbox, wren.cards())
        assertEquals(plays, wren.friendPlays("f1", 0, 10))
        assertTrue(wrenState.now.inbox.isEmpty())
        assertEquals(listOf(plays[0]), wrenState.now.plays)
    }

    @Test
    fun `a card you send is kept as yours once it is posted, and one that never got there as not delivered`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        val sentAt = now + testScheduler.currentTime
        val id = wren.send(juniperOnWren.id, song)!!
        assertTrue("not before it goes", wren.thread(juniperOnWren.id, 0, 10).isEmpty())
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertEquals(listOf(Sent(id, juniperOnWren.id, sentAt, song)), wren.thread(juniperOnWren.id, 0, 10))
        assertEquals(Sent(id, juniperOnWren.id, sentAt, song), wren.card(id))
        assertTrue("your own cards are not in Sent to you", wren.cards().isEmpty())

        server.online = false
        val lost = wren.send(juniperOnWren.id, Card(CardKind.SONG, listOf(bell)))!!
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertEquals("waiting to go: not kept yet", 1, wren.thread(juniperOnWren.id, 0, 10).size)
        advanceTimeBy(30 * 24 * Huddle.HOUR_MS)
        server.online = true
        wren.flush()
        val kept = wren.card(lost) as Sent
        assertFalse(kept.delivered)
        assertEquals(listOf(lost, id), wren.thread(juniperOnWren.id, 0, 10).map { it.id })
    }

    @Test
    fun `a card taken back with Undo never reaches the history, and the card that came with an invite is the friend's`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper, song)
        assertEquals("the invite's card, as Juniper got it", listOf(song), juniper.thread(wrenOnJuniper.id, 0, 10).map { it.card })
        assertEquals("and as Wren sent it", listOf(Sent::class), wren.thread(juniperOnWren.id, 0, 10).map { it::class })
        val id = wren.send(juniperOnWren.id, Card(CardKind.SONG, listOf(bell)))!!
        assertTrue(wren.unsend(id))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.flush()
        assertNull(wren.card(id))
        assertEquals(1, wren.thread(juniperOnWren.id, 0, 10).size)
    }

    @Test
    fun `a thread holds both ways the newest first, in pages, and Sent to you every friend's cards`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (rook, _) = phone("Rook")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper)
        val (wrenOnRook, _) = friends(rook, wren)
        val cards = (1..5).map { Card(CardKind.SONG, listOf(bell), note = "note $it") }
        juniper.send(wrenOnJuniper.id, cards[0])
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.send(juniperOnWren.id, cards[1])
        advanceTimeBy(Huddle.UNDO_MS + 1)
        rook.send(wrenOnRook.id, cards[2])
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.send(wrenOnJuniper.id, cards[3])
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        assertEquals(listOf(cards[3], cards[1]), wren.thread(juniperOnWren.id, 0, 2).map { it.card })
        assertEquals(listOf(cards[0]), wren.thread(juniperOnWren.id, 2, 2).map { it.card })
        assertEquals(listOf(true, false, true), wren.thread(juniperOnWren.id, 0, 10).map { it is Received })
        assertEquals(listOf(cards[3], cards[2], cards[0]), wren.cards().map { it.card })
        assertEquals(listOf(cards[2]), wren.received(1, 1).map { it.card })
    }

    @Test
    fun `the inbox shows the newest cards friends sent, and opening one marks it there`() = runTest {
        val (wren, _) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (_, wrenOnJuniper) = friends(wren, juniper)
        assertTrue(wren.inbox.first().isEmpty())
        val written = wren.cardsWritten.value
        juniper.send(wrenOnJuniper.id, song)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        assertTrue(wren.cardsWritten.value > written)
        val card = wren.inbox.first().single()
        assertFalse(card.opened)
        assertTrue(wren.opened(card.id))
        assertTrue(wren.inbox.first().single().opened)
    }

    @Test
    fun `a backup brings the history back on a new phone, each card and play once however often it is restored`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper, song)
        juniper.played(bell, now + testScheduler.currentTime)
        wren.send(juniperOnWren.id, Card(CardKind.SONG, listOf(lantern), note = "back at you"))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        wren.sync()
        wrenState.change { it.copy(backupKey = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)) }
        val section = wren.backupSection()!!
        val (newPhone, _) = phone()
        assertEquals(1, newPhone.restore(section, "correct horse".toCharArray()))
        assertEquals(1, newPhone.restore(section, "correct horse".toCharArray()))
        assertEquals(wren.thread(juniperOnWren.id, 0, 10), newPhone.thread(juniperOnWren.id, 0, 10))
        assertEquals(2, newPhone.thread(juniperOnWren.id, 0, 10).size)
        assertEquals(listOf(bell), newPhone.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
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
        val (juniper, _) = phone("Juniper")
        friends(wren, juniper)
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
        assertEquals("the friend hears from the restored phone", listOf(song), juniper.cards().map { it.card })
    }

    @Test
    fun `a backup made without a passphrase brings the friends and the history back on a new phone, no passphrase asked, and the friend hears from it`() = runTest {
        // Changed on purpose (user 2026-11-23, item 60): before, a phone without a passphrase kept Huddle out of its backups.
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper, song)
        juniper.played(bell, now + testScheduler.currentTime)
        wren.sync()
        val section = wren.backupSection()
        assertNotNull("Huddle is always in the backup", section)
        assertFalse(HuddleBackup.locked(section!!))
        val (newPhone, newState) = phone()
        assertEquals(1, newPhone.restore(section, CharArray(0)))
        assertEquals(wrenState.now.friends, newState.now.friends)
        assertEquals("Wren", newState.now.name)
        assertNull("no passphrase came with it", newState.now.backupKey)
        assertEquals(wren.thread(juniperOnWren.id, 0, 10), newPhone.thread(juniperOnWren.id, 0, 10))
        assertEquals(listOf(bell), newPhone.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        val hello = Card(CardKind.SONG, listOf(bell), note = "from the new phone")
        newPhone.send(newState.now.friends.single().id, hello)
        advanceTimeBy(Huddle.UNDO_MS + 1)
        juniper.sync()
        // Beside the invite's card, sent at the same test time.
        assertEquals("the friend hears from the restored phone", setOf(song, hello), juniper.cards().map { it.card }.toSet())
    }

    @Test
    fun `a link whose key was swapped joins no slot, and nobody is added on either side`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, juniperState) = phone("Juniper")
        val real = juniper.parse(link(wren, song))!!
        val swapped = Invite(HuddleCrypto.keyPair(random).public, real.token, real.name, real.card)
        assertEquals(Accepted.Expired, juniper.accept(swapped))
        assertTrue(juniperState.now.friends.isEmpty())
        assertTrue(juniper.cards().isEmpty())
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
        assertEquals("the friendship works both ways", listOf(song), wren.cards().map { it.card })
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
        assertEquals(listOf(song), wren.cards().map { it.card })
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
        val (juniper, _) = phone("Juniper")
        val joined = juniper.accept(juniper.parse(link(wren, song))!!) as Accepted.Joined
        assertEquals(juniper.cards().single().id, joined.received)
        val (rook, _) = phone("Rook")
        assertNull((rook.accept(rook.parse(link(wren))!!) as Accepted.Joined).received)
    }

    @Test
    fun `a card the server sends again after many plays is still dropped`() = runTest {
        val (wren, _) = phone("Wren")
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
        assertEquals(1, wren.cards().size)
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
        assertTrue(wren.cards().single().at <= today)
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

    @Test
    fun `of two plays read at once from a friend whose clock runs ahead, the later one is what they played last`() = runTest {
        val (wren, _) = phone("Wren", ahead = Huddle.HOUR_MS)
        val (juniper, juniperState) = phone("Juniper")
        friends(wren, juniper)
        val at = now + Huddle.HOUR_MS + testScheduler.currentTime
        wren.played(bell, at)
        wren.played(lantern, at + 1_000)
        assertTrue(juniper.sync())
        // Both are cut to Juniper's now: the one read last is the newer.
        assertEquals(lantern, juniperState.now.plays.single().song)
    }

    @Test
    fun `a card that went while the history could not be written stays on the phone, and is kept once it can, and arrives once`() = runTest {
        val history = history()
        val (wren, wrenState) = phone("Wren", history = history)
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper)
        history.failing = true
        val id = wren.send(juniperOnWren.id, song)!!
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertEquals("waits on the phone to be kept", listOf(id), wrenState.now.outbox.map { it.id })
        history.failing = false
        assertTrue(wren.flush())
        assertEquals(listOf(id), wren.thread(juniperOnWren.id, 0, 10).map { it.id })
        assertTrue("it went", (wren.card(id) as Sent).delivered)
        assertTrue(wrenState.now.outbox.isEmpty())
        assertTrue(juniper.sync())
        assertEquals("sent twice, one card", listOf(id), juniper.cards().map { it.id })
    }

    @Test
    fun `the card an invite brought waits with the friend in huddle_json while the history cannot be written, and moves there once it can`() = runTest {
        val (wren, _) = phone("Wren")
        val history = history().apply { failing = true }
        val (juniper, juniperState) = phone("Juniper", history = history)
        val accepted = juniper.accept(checkNotNull(juniper.parse(link(wren, song)))) as Accepted.Joined
        assertEquals(listOf(accepted.received), juniperState.now.inbox.map { it.id })
        assertEquals(listOf(accepted.friend.id), juniperState.now.inbox.map { it.friend })
        history.failing = false
        assertEquals(listOf(accepted.received), juniper.cards().map { it.id })
        assertTrue(juniperState.now.inbox.isEmpty())
    }

    @Test
    fun `the card your link carried is kept before the invite leaves the phone, and a friend that could not be written leaves no card behind`() = runTest {
        val history = history()
        val (wren, wrenState) = phone("Wren", history = history)
        val (juniper, _) = phone("Juniper")
        juniper.accept(checkNotNull(juniper.parse(link(wren, song))))
        history.failing = true
        wren.sync()
        assertTrue("the invite waits for the history", wrenState.now.friends.isEmpty())
        assertEquals(1, wrenState.now.invites.size)
        history.failing = false
        wrenState.writable = false
        wren.sync()
        assertTrue(wrenState.now.friends.isEmpty())
        assertTrue("no card for a friend who is not there", history.cards(0, 10).orEmpty().isEmpty())
        wrenState.writable = true
        wren.sync()
        val juniperOnWren = wrenState.now.friends.single()
        assertEquals(listOf(song), wren.thread(juniperOnWren.id, 0, 10).filterIsInstance<Sent>().map { it.card })
        assertEquals(1, history.cards(0, 10)?.size)
        assertTrue(wrenState.now.invites.isEmpty())
    }

    @Test
    fun `a friend is removed while plays_db cannot be written, and the history is there once it can be read`() = runTest {
        // Changed on purpose (user 2026-11-23, item 59): removing a friend no longer lets their history go, so it needs none of it.
        val history = history()
        val (wren, wrenState) = phone("Wren", history = history)
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper, song)
        history.failing = true
        assertTrue(wren.remove(juniperOnWren.id))
        assertTrue(wrenState.now.friends.isEmpty())
        assertEquals(listOf(juniperOnWren.id), wrenState.now.past.map { it.id })
        history.failing = false
        assertEquals(listOf(song), wren.thread(juniperOnWren.id, 0, 10).map { it.card })
    }

    @Test
    fun `a goodbye read while plays_db cannot be written is read again, and the friend is past with all they sent once it can`() = runTest {
        // Changed on purpose (user 2026-11-23, item 59): a goodbye keeps the history; what came with it is kept first.
        val history = history()
        val (wren, wrenState) = phone("Wren", history = history)
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, wrenOnJuniper) = friends(wren, juniper, song)
        advanceTimeBy(1_000)
        juniper.send(wrenOnJuniper.id, Card(CardKind.SONG, listOf(bell), note = "last one"))
        advanceTimeBy(Huddle.UNDO_MS + 1)
        assertTrue(juniper.remove(wrenOnJuniper.id))
        history.failing = true
        assertFalse(wren.sync())
        assertEquals("a friend until what came is kept", 1, wrenState.now.friends.size)
        history.failing = false
        assertTrue(wren.sync())
        assertTrue(wrenState.now.friends.isEmpty())
        assertEquals(listOf(juniperOnWren.id), wrenState.now.past.map { it.id })
        assertEquals(listOf("last one", "for the drive"), wren.thread(juniperOnWren.id, 0, 10).map { it.card.note })
    }

    @Test
    fun `a backup keeps past friends and their history, and a restore brings them back as past friends, never as friends again`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper, song)
        juniper.played(lantern, now + testScheduler.currentTime)
        wren.sync()
        wrenState.change { it.copy(backupKey = HuddleBackup.newKey("correct horse".toCharArray(), random, rounds = 1_000)) }
        // Made while they were friends.
        val older = wren.backupSection()!!
        assertTrue(wren.remove(juniperOnWren.id))
        val section = wren.backupSection()!!
        val (newPhone, newState) = phone()
        assertEquals(0, newPhone.restore(section, "correct horse".toCharArray()))
        assertEquals(listOf(juniperOnWren.id), newState.now.past.map { it.id })
        assertEquals(wrenState.now.past, newState.now.past)
        assertTrue(newState.now.friends.isEmpty())
        assertEquals(wren.thread(juniperOnWren.id, 0, 10), newPhone.thread(juniperOnWren.id, 0, 10))
        assertEquals(listOf(lantern), newPhone.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        assertEquals(1, wren.restore(older, "correct horse".toCharArray()))
        assertTrue("left on this phone, they stay left", wrenState.now.friends.isEmpty())
        assertEquals(listOf(juniperOnWren.id), wrenState.now.past.map { it.id })
    }

    @Test
    fun `a backup made without a passphrase keeps past friends and their history too, and a restore brings them back as past friends`() = runTest {
        val (wren, wrenState) = phone("Wren")
        val (juniper, _) = phone("Juniper")
        val (juniperOnWren, _) = friends(wren, juniper, song)
        juniper.played(lantern, now + testScheduler.currentTime)
        wren.sync()
        // Made while they were friends.
        val older = wren.backupSection()!!
        assertTrue(wren.remove(juniperOnWren.id))
        val section = wren.backupSection()!!
        assertFalse(HuddleBackup.locked(section))
        val thread = wren.thread(juniperOnWren.id, 0, 10)
        assertTrue("the history kept on this phone", thread.isNotEmpty())
        val (newPhone, newState) = phone()
        assertEquals(0, newPhone.restore(section, CharArray(0)))
        assertEquals(wrenState.now.past, newState.now.past)
        assertTrue(newState.now.friends.isEmpty())
        assertEquals(thread, newPhone.thread(juniperOnWren.id, 0, 10))
        assertEquals(listOf(lantern), newPhone.friendPlays(juniperOnWren.id, 0, 10).map { it.song })
        assertEquals(1, wren.restore(older, CharArray(0)))
        assertTrue("left on this phone, they stay left", wrenState.now.friends.isEmpty())
        assertEquals(listOf(juniperOnWren.id), wrenState.now.past.map { it.id })
    }
}
