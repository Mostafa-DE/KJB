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
import app.lyrify.settings.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // The card's Hide puts it away; opening it only takes its New mark off.
        val screen = File("src/main/java/app/lyrify/ui/HuddleEverywhere.kt").readText()
        assertTrue(screen.contains("huddle.opened(received.id, dismissed = true)"))
        assertTrue(screen.contains("if (!received.opened) NewMark()"))
    }

    @Test
    fun `what a friend sent plays in a queue of its own named From and their name, in the player and the queues, an album or a playlist too`() {
        val sheets = File("src/main/java/app/lyrify/ui/HuddleSheets.kt").readText()
        // A card's song, album or playlist: the card's own place, under the name you see. A friend's song (Play it, Play
        // along) plays as theirs too.
        val plays = Regex("""(Lyrify\.playback\(context\)\.play|PlayerGraph\.connection\(context\)\.playFrom)\([^\n]*""").findAll(sheets).map { it.value }.toList()
        assertEquals(3, plays.size)
        assertTrue(plays.all { "QueueSource(SourceKind.HUDDLE, " in it })
        assertTrue(sheets.contains("Lyrify.playback(context).play(songs.map { it.ref }, 0, QueueSource(SourceKind.HUDDLE, received.id, from))"))
        assertTrue(File("src/main/java/app/lyrify/player/PlayerConnection.kt").readText().contains("SourceKind.HUDDLE -> return context.getString(R.string.huddle_from, source.name)"))
        assertTrue(File("src/main/java/app/lyrify/ui/SongActions.kt").readText().contains("SourceKind.HUDDLE -> resources.getString(R.string.huddle_from, source.name)"))
        // The player's own line is "From <name>" already: the friend's name goes in it as it is.
        assertTrue(File("src/main/java/app/lyrify/ui/PlayerScreen.kt").readText().contains("val queueName = ui.queue.name ?: ui.queue.source.name.takeIf { ui.queue.source.kind == SourceKind.HUDDLE } ?: sourceLabel("))
        assertTrue("""<string name="huddle_from">From %1${'$'}s</string>""" in File("src/main/res/values/strings_huddle.xml").readText())
        assertTrue("""<string name="player_from">From %1${'$'}s</string>""" in File("src/main/res/values/strings_player.xml").readText())
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
    fun `friends playing now come first, the newest song first, then the others by their latest play, then by name`() {
        val now = 10_000_000L
        val state = HuddleState(
            friends = listOf(friend("f1", "Wren"), friend("f2", "Juniper"), friend("f3", "Rook"), friend("f4", "Ash"), friend("f5", "Moss"), friend("f6", "Birch")),
            plays = listOf(
                FriendPlay("f1", now - 100_000, lantern),
                FriendPlay("f2", now - 30_000, bell),
                FriendPlay("f3", now - 900_000, bell),
                FriendPlay("f4", now - 600_000, lantern),
            ),
        )
        assertEquals(listOf("f2", "f1", "f4", "f3", "f6", "f5"), friendsNow(state, now).map { it.friend.id })
        assertEquals(listOf(true, true, false, false, false, false), friendsNow(state, now).map { it.playing })
    }

    @Test
    fun `the player shows a friend playing the same song first, then one on the same artist, never one who stopped`() {
        val now = 10_000_000L
        val other = CardSong("Low Tide", "Odile Marsh", null, 200_000)
        val state = HuddleState(
            friends = listOf(friend("f1", "Wren"), friend("f2", "Juniper"), friend("f3", "Rook"), friend("f4", "Ash")),
            plays = listOf(
                FriendPlay("f2", now - 20_000, bell),
                FriendPlay("f1", now - 100_000, lantern),
                FriendPlay("f3", now - 1_200_000, lantern),
                FriendPlay("f4", now - 10_000, other),
            ),
        )
        val on = friendsOn(state, now, "Paper Lantern", "Kit Calloway")
        assertEquals(listOf("f1" to true, "f2" to false), on.map { it.friend.friend.id to it.sameSong })
        // Case, accents and spacing aside.
        assertEquals(on, friendsOn(state, now, "paper  lantern", "KIT CALLOWAY"))
        // Without an artist, only the same song with no artist either.
        assertTrue(friendsOn(state, now, "Paper Lantern", null).isEmpty())
        assertEquals(listOf("f4" to false), friendsOn(state, now, "High Tide", "odile marsh").map { it.friend.friend.id to it.sameSong })
    }

    @Test
    fun `Play along starts where the friend is, from when their song started, and never in its last seconds`() {
        val play = FriendPlay("f1", 1_000_000, lantern)
        assertEquals(72_000L, playAlongMs(play, 1_072_000))
        assertEquals("a clock a little behind theirs", 0L, playAlongMs(play, 999_000))
        assertEquals(lantern.durationMs - PLAY_ALONG_END_MS, playAlongMs(play, 1_000_000 + 250_000))
        assertEquals("no length known: from the top", 0L, playAlongMs(play.copy(song = lantern.copy(durationMs = 0)), 1_072_000))
    }

    @Test
    fun `Huddle asks for your name before anything else, then for another one when a friend says it is taken`() {
        assertEquals(NameGate.NAME, nameGate(HuddleState()))
        assertEquals(NameGate.NAME, nameGate(HuddleState(friends = listOf(friend("f1", "Wren").copy(asksNewName = true)))))
        assertNull(nameGate(HuddleState(name = "Juniper", friends = listOf(friend("f1", "Wren")))))
        assertEquals(NameGate.NEW_NAME, nameGate(HuddleState(name = "Juniper", friends = listOf(friend("f1", "Wren"), friend("f2", "Rook").copy(asksNewName = true)))))
    }

    @Test
    fun `the bar's Huddle has its live dot while a friend still here plays now`() {
        val now = 10_000_000L
        val wren = friend("f1", "Wren")
        assertFalse(huddleDot(HuddleState(friends = listOf(wren)), now))
        assertTrue(huddleDot(HuddleState(friends = listOf(wren), plays = listOf(FriendPlay("f1", now - 60_000, lantern))), now))
        assertFalse(huddleDot(HuddleState(friends = listOf(wren), plays = listOf(FriendPlay("f1", now - 600_000, lantern))), now))
        assertFalse(huddleDot(HuddleState(plays = listOf(FriendPlay("f1", now - 60_000, lantern))), now))
    }

    @Test
    fun `the friends row and a card a friend sent lead the tab Library opens on, any tab, once there is a friend`() {
        val state = HuddleState(friends = listOf(friend("f1", "Wren")))
        assertTrue(friendsRowShows(LibraryTab.ALBUMS, LibraryTab.ALBUMS, state))
        assertFalse(friendsRowShows(LibraryTab.SONGS, LibraryTab.ALBUMS, state))
        assertFalse(friendsRowShows(LibraryTab.ALBUMS, LibraryTab.ALBUMS, HuddleState(invites = listOf(PendingInvite("pk", "sk", "t", "o", 1)))))
        // Each tab's list has it at its top, right under the month's recap card when that shows (in1's first row).
        val lines = (File("src/main/java/app/lyrify/ui/LibraryScreen.kt").readText() + File("src/main/java/app/lyrify/ui/BooksScreen.kt").readText()).lines()
        LibraryTab.entries.forEach { tab ->
            val row = lines.indexOfFirst { "huddleFriends(LibraryTab.$tab)" in it }
            assertTrue("$tab has the row", row > 0)
            assertTrue("$tab: ${lines[row - 1]}", lines[row - 1].contains("recapCard(LibraryTab.$tab)") || lines[row - 2].contains("recapCard(LibraryTab.$tab)"))
        }
    }

    @Test
    fun `each friend keeps one colour of a few, and lilac initials and words read on every one`() {
        assertEquals(friendColor("f1"), friendColor("f1"))
        assertTrue((1..40).map { friendColor("friend $it") }.toSet().size >= 5)
        assertTrue((1..40).all { friendColor("friend $it") in FriendColors })
        // The words on a face, and on Huddle's dark grounds (a friend's card, the empty card, the ticket), are lilac.
        (FriendColors + OwnFaceColor + HuddleCardGround + HuddleEmptyGround + HuddleTicket).forEach { assertTrue("$it", contrast(BrandLilac, it) >= 4.5f) }
        assertTrue(contrast(OnHuddleGold, HuddleGold) >= 4.5f)
    }

    @Test
    fun `a postcard's paper keeps the page's ink readable, light and dark`() {
        listOf(LightPaper to LightColors, DarkPaper to DarkColors).forEach { (paper, scheme) ->
            assertTrue(contrast(scheme.onSurface, paper.paper) >= 4.5f)
            assertTrue(contrast(scheme.onSurfaceVariant, paper.paper) >= 4.5f)
        }
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
                FriendNow(wren, FriendPlay("f1", now - 100_000, lantern), playing = true),
                FriendNow(juniper, FriendPlay("f2", now - 300_000, bell), playing = false),
                FriendNow(rook, null, playing = false),
            ),
            friendsNow(state, now),
        )
        assertEquals(true, friendsNow(state, now - 100_000 + 200_000 + 60_000).first().playing)
        assertEquals(false, friendsNow(state, now - 100_000 + 200_000 + 60_001).single { it.friend == wren }.playing)
    }
}
