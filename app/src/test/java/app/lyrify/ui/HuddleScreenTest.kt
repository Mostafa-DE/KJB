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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Huddle's rows from what it keeps: the Tools line, the start screen's card, who comes first to send to, now playing, and
 * who sent what plays. Made-up names only.
 */
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
    fun `the start screen keeps the newest card and who sent it once it is opened, until it is dismissed`() {
        val friends = listOf(friend("f1", "Wren"), friend("f2", "Juniper"))
        val played = Received("m1", "f1", 5, card, opened = true)
        assertEquals("m1", newestCard(HuddleState(friends = friends, inbox = listOf(played)))?.id)
        // One not opened yet comes first, older or not.
        assertEquals("m2", newestCard(HuddleState(friends = friends, inbox = listOf(played, Received("m2", "f2", 3, card))))?.id)
        // Dismissed, it goes, and an older card opened before does not take its place.
        val older = Received("m3", "f2", 2, card, opened = true)
        assertNull(newestCard(HuddleState(friends = friends, inbox = listOf(played.copy(dismissed = true), older))))
        // The row's Dismiss puts it away; opening it only changes its look.
        val screen = File("src/main/java/app/lyrify/ui/HuddleScreen.kt").readText()
        assertTrue(screen.contains("huddle.opened(received.id, dismissed = true)"))
        assertTrue(screen.contains("if (received.opened) Tile(R.drawable.ic_send) else Tile(R.drawable.ic_send, brand.highlightContainer, brand.onHighlightContainer)"))
    }

    @Test
    fun `what a friend sent plays in a queue of its own named From and their name, in the player and the queues, an album or a playlist too`() {
        val sheets = File("src/main/java/app/lyrify/ui/HuddleSheets.kt").readText()
        // One play for a song, an album's or a playlist's songs: the card's own place, under the name you see.
        assertEquals(1, Regex("""Lyrify\.playback\(context\)\.play\(""").findAll(sheets).count())
        assertTrue(sheets.contains("Lyrify.playback(context).play(songs.map { it.ref }, 0, QueueSource(SourceKind.HUDDLE, received.id, from))"))
        assertTrue(File("src/main/java/app/lyrify/player/PlayerConnection.kt").readText().contains("SourceKind.HUDDLE -> return context.getString(R.string.huddle_from, source.name)"))
        assertTrue(File("src/main/java/app/lyrify/ui/SongActions.kt").readText().contains("SourceKind.HUDDLE -> resources.getString(R.string.huddle_from, source.name)"))
        // The player's own line is "From <name>" already: the friend's name goes in it as it is.
        val player = File("src/main/java/app/lyrify/ui/PlayerScreen.kt").readText()
        assertTrue(player.contains("val queueName = ui.queue.name ?: ui.queue.source.name.takeIf { ui.queue.source.kind == SourceKind.HUDDLE } ?: sourceLabel("))
        assertTrue(player.contains("fromLine(stringResource(R.string.player_from, name), bold,"))
        assertTrue("""<string name="huddle_from">From %1${'$'}s</string>""" in File("src/main/res/values/strings_huddle.xml").readText())
        assertTrue("""<string name="player_from">From %1${'$'}s</string>""" in File("src/main/res/values/strings_player.xml").readText())
    }

    @Test
    fun `a card not opened yet is New to TalkBack too, not coral alone, on the start screen and the Huddle page`() {
        assertEquals(
            2,
            Regex(Regex.escape("modifier = if (received.opened) Modifier else stringResource(R.string.huddle_new).let { new -> Modifier.semantics { stateDescription = new } },"))
                .findAll(File("src/main/java/app/lyrify/ui/HuddleScreen.kt").readText()).count(),
        )
        assertTrue("""<string name="huddle_new">New</string>""" in File("src/main/res/values/strings_huddle.xml").readText())
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
