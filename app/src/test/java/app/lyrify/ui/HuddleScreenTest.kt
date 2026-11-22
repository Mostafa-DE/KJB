package app.lyrify.ui

import app.lyrify.R
import app.lyrify.huddle.Card
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.CardSong
import app.lyrify.huddle.Friend
import app.lyrify.huddle.FriendPlay
import app.lyrify.huddle.HuddleState
import app.lyrify.huddle.PendingInvite
import app.lyrify.huddle.Received
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Huddle's rows from what it keeps: the Tools line, the start screen's card, who comes first to send to, and now playing. Made-up names only. */
class HuddleScreenTest {
    private val lantern = CardSong("Paper Lantern", "Kit Calloway", "Harbour Glass", 200_000)
    private val bell = CardSong("Brass Bell", "Kit Calloway", "Harbour Glass", 180_000)
    private val card = Card(CardKind.SONG, listOf(lantern))

    private fun friend(id: String, name: String, lastSentAt: Long? = null, rename: String? = null) =
        Friend(id, name, "out-$id", "in-$id", "ok", "ik", "join-$id", addedAt = 1, rename = rename, lastSentAt = lastSentAt)

    @Test
    fun `Tools says what Huddle is for, then who waits, then how many friends with the cards not opened as the badge`() {
        assertEquals(EntryRow(ToolLine.Text(R.string.huddle_tool_none), null), huddleEntry(HuddleState()))
        val waiting = HuddleState(invites = listOf(PendingInvite("pk", "sk", "t", "o", 1)))
        assertEquals(EntryRow(ToolLine.Text(R.string.huddle_tool_waiting), null), huddleEntry(waiting))
        val friends = HuddleState(
            friends = listOf(friend("f1", "Wren"), friend("f2", "Juniper")),
            inbox = listOf(Received("m1", "f1", 3, card), Received("m2", "f2", 2, card, opened = true), Received("m3", "gone", 1, card)),
        )
        assertEquals(EntryRow(ToolLine.Count(R.plurals.huddle_friends, 2), 1), huddleEntry(friends))
    }

    @Test
    fun `the start screen shows the newest card not opened yet, from a friend still here`() {
        val friends = listOf(friend("f1", "Wren"))
        assertNull(newestCard(HuddleState(friends = friends)))
        val inbox = listOf(Received("m1", "f1", 5, card, opened = true), Received("m2", "gone", 4, card), Received("m3", "f1", 2, card), Received("m4", "f1", 3, card))
        assertEquals("m4", newestCard(HuddleState(friends = friends, inbox = inbox))?.id)
    }

    @Test
    fun `Send to a friend lists the one you sent to last first, then the rest by the name you see`() {
        val rook = friend("f1", "rook")
        val juniper = friend("f2", "Juniper", lastSentAt = 10)
        val wren = friend("f3", "Zed", rename = "Wren", lastSentAt = 20)
        val ash = friend("f4", "Ash")
        assertEquals(listOf(wren, juniper, ash, rook), sendOrder(listOf(rook, juniper, wren, ash)))
    }

    @Test
    fun `each friend shows their latest play, playing until its length and a minute have passed`() {
        val wren = friend("f1", "Wren")
        val juniper = friend("f2", "Juniper")
        val rook = friend("f3", "Rook")
        val now = 10_000_000L
        val state = HuddleState(
            friends = listOf(wren, juniper, rook),
            plays = listOf(FriendPlay("f1", now - 100_000, lantern), FriendPlay("f2", now - 300_000, bell), FriendPlay("f1", now - 900_000, bell)),
        )
        assertEquals(
            listOf(
                FriendNow(juniper, FriendPlay("f2", now - 300_000, bell), playing = false),
                FriendNow(rook, null, playing = false),
                FriendNow(wren, FriendPlay("f1", now - 100_000, lantern), playing = true),
            ),
            friendsNow(state, now),
        )
        assertEquals(true, friendsNow(state, now - 100_000 + 200_000 + 60_000).last().playing)
        assertEquals(false, friendsNow(state, now - 100_000 + 200_000 + 60_001).last().playing)
    }
}
