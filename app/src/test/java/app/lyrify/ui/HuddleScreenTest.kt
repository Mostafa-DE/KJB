package app.lyrify.ui

import app.lyrify.R
import app.lyrify.huddle.Card
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.CardSong
import app.lyrify.huddle.Friend
import app.lyrify.huddle.FriendPlay
import app.lyrify.huddle.HuddleState
import app.lyrify.huddle.Match
import app.lyrify.huddle.PendingInvite
import app.lyrify.huddle.Received
import app.lyrify.library.song
import app.lyrify.settings.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

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
        assertEquals(EntryRow(ToolLine.Text(R.string.huddle_tool_none), null), huddleEntry(HuddleState(), emptyList()))
        val waiting = HuddleState(invites = listOf(PendingInvite("pk", "sk", "t", "o", 1)))
        assertEquals(EntryRow(ToolLine.Text(R.string.huddle_tool_waiting), null), huddleEntry(waiting, emptyList()))
        val friends = HuddleState(friends = listOf(friend("f1", "Wren"), friend("f2", "Juniper")))
        // Changed on purpose: the cards come from the history (Huddle.inbox), not huddle.json.
        val inbox = listOf(Received("m1", "f1", 3, card), Received("m2", "f2", 2, card, opened = true), Received("m3", "gone", 1, card))
        assertEquals(EntryRow(ToolLine.Count(R.plurals.huddle_friends, 2), 1), huddleEntry(friends, inbox))
        assertEquals("cards left in huddle.json from before count no more", EntryRow(ToolLine.Count(R.plurals.huddle_friends, 2), null), huddleEntry(friends.copy(inbox = inbox), emptyList()))
    }

    @Test
    fun `the start screen shows the newest card not opened yet, from a friend still here`() {
        val friends = listOf(friend("f1", "Wren"))
        assertNull(newestCard(HuddleState(friends = friends), emptyList()))
        val inbox = listOf(Received("m1", "f1", 5, card, opened = true), Received("m2", "gone", 4, card), Received("m3", "f1", 2, card), Received("m4", "f1", 3, card))
        assertEquals("m4", newestCard(HuddleState(friends = friends), inbox)?.id)
    }

    @Test
    fun `the start screen keeps the newest card and who sent it once it is opened, until it is dismissed`() {
        val friends = listOf(friend("f1", "Wren"), friend("f2", "Juniper"))
        val played = Received("m1", "f1", 5, card, opened = true)
        assertEquals("m1", newestCard(HuddleState(friends = friends), listOf(played))?.id)
        // One not opened yet comes first, older or not.
        assertEquals("m2", newestCard(HuddleState(friends = friends), listOf(played, Received("m2", "f2", 3, card)))?.id)
        // Dismissed, it goes, and an older card opened before does not take its place.
        val older = Received("m3", "f2", 2, card, opened = true)
        assertNull(newestCard(HuddleState(friends = friends), listOf(played.copy(dismissed = true), older)))
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
        val plays = Regex("""Lyrify\.playback\(context\)\.play\([^\n]*""").findAll(sheets).map { it.value }.toList()
        assertEquals(2, plays.size)
        assertTrue(plays.all { "QueueSource(SourceKind.HUDDLE, " in it })
        assertTrue(sheets.contains("val source = QueueSource(SourceKind.HUDDLE, \"\${play.friend}:\${play.at}\", name)"))
        // A card you sent ("You and <name>") plays as yours: From and your name.
        assertTrue(sheets.contains("Lyrify.playback(context).play(songs.map { it.ref }, 0, QueueSource(SourceKind.HUDDLE, kept.id, sender))"))
        assertTrue(sheets.contains("val sender = if (mine) state.name ?: from else from"))
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
    fun `a card not opened yet is New in words too, not colour alone, in Library and on the Huddle page`() {
        // The redesign (HD3) says it with a visible "New" mark, which TalkBack reads with the card.
        assertTrue(File("src/main/java/app/lyrify/ui/HuddleEverywhere.kt").readText().contains("if (!received.opened) NewMark()"))
        assertTrue(File("src/main/java/app/lyrify/ui/HuddleParts.kt").readText().contains("if (fresh) NewMark()"))
        // The page's postcards, its View all and "You and <name>" (HY1) show the history's cards through KeptPostcard.
        assertTrue(File("src/main/java/app/lyrify/ui/HuddleParts.kt").readText().contains("fresh = (kept as? Received)?.opened == false,"))
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
    fun `a friend's song plays only when it is yours, asks Is this the song for a loose match, and asks the friend only once matched`() {
        val mine = song("Music/Kit Calloway/Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway")
        assertEquals(PeekOffer.Waiting, peekOffer(null, null))
        assertEquals(PeekOffer.Play(mine), peekOffer(Match.Found(mine), null))
        assertEquals(PeekOffer.Ask(mine), peekOffer(Match.Maybe(mine), null))
        assertEquals(PeekOffer.Play(mine), peekOffer(Match.Maybe(mine), true))
        assertEquals(PeekOffer.Missing, peekOffer(Match.Maybe(mine), false))
        assertEquals(PeekOffer.Missing, peekOffer(Match.Missing, null))
    }

    @Test
    fun `a card says you have it all, some of it, maybe, or none, an album by its songs and not by its first`() {
        val mine = song("Music/Kit Calloway/Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway")
        assertEquals(Have.ALL, haveOf(Match.Found(mine), null))
        assertEquals(Have.MAYBE, haveOf(Match.Maybe(mine), null))
        assertEquals(Have.NONE, haveOf(Match.Missing, null))
        assertEquals(Have.SOME, haveOf(Match.Found(mine), 3 to 12))
        assertEquals(Have.ALL, haveOf(Match.Found(mine), 12 to 12))
        assertEquals(Have.NONE, haveOf(Match.Missing, 0 to 12))
        assertEquals(Have.SOME, haveOf(Match.Missing, 1 to 12))
    }

    @Test
    fun `Huddle's endless motion stops when animations are off or its layer is hidden, and so do its clocks`() {
        val files = listOf("HuddleParts.kt", "HuddleInvite.kt", "HuddleScreen.kt", "HuddleSheets.kt", "HuddleEverywhere.kt").associateWith { File("src/main/java/app/lyrify/ui/$it").readText() }
        val endless = files.flatMap { (name, text) -> text.lines().filter { "rememberInfiniteTransition(" in it }.map { name to it } }
        assertTrue(endless.size >= 7)
        endless.forEach { (name, line) -> assertTrue("$name: $line", "if (huddleMoves()) rememberInfiniteTransition(" in line) }
        assertTrue(files.getValue("HuddleScreen.kt").contains("        while (shown) {"))
    }

    @Test
    fun `Play along moves the player to the friend's place even when their song's queue plays already, and Join and play is for a song`() {
        val sheets = File("src/main/java/app/lyrify/ui/HuddleSheets.kt").readText()
        assertTrue(sheets.contains("if (connection.ui.value?.queue?.from(source) == true) {"))
        assertTrue(sheets.contains("connection.seekTo(at)"))
        val invite = File("src/main/java/app/lyrify/ui/HuddleInvite.kt").readText()
        assertTrue(invite.contains("if (card?.kind == CardKind.SONG && found is Match.Found) {"))
        assertTrue(invite.contains("val ticket = rememberSaveable { UUID.randomUUID().toString() }"))
        assertTrue(invite.contains("huddle.ticketLink(ticket, card)"))
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
        assertFalse(huddleLive(HuddleState(friends = listOf(wren)), now).playing.isNotEmpty())
        assertTrue(huddleLive(HuddleState(friends = listOf(wren), plays = listOf(FriendPlay("f1", now - 60_000, lantern))), now).playing.isNotEmpty())
        assertFalse(huddleLive(HuddleState(friends = listOf(wren), plays = listOf(FriendPlay("f1", now - 600_000, lantern))), now).playing.isNotEmpty())
        assertFalse(huddleLive(HuddleState(plays = listOf(FriendPlay("f1", now - 60_000, lantern))), now).playing.isNotEmpty())
    }

    @Test
    fun `the headers' Huddle knows whether a name is picked and who plays now, by the names you see, the latest first`() {
        val now = 10_000_000L
        assertEquals(HuddleLive(joined = false), huddleLive(HuddleState(), now))
        val wren = friend("f1", "Wren", rename = "Wrennie")
        val state = HuddleState(
            name = "Kai",
            friends = listOf(wren, friend("f2", "Juniper"), friend("f3", "Rook")),
            plays = listOf(FriendPlay("f1", now - 60_000, lantern), FriendPlay("f2", now - 30_000, bell), FriendPlay("f3", now - 900_000, bell)),
        )
        assertEquals(HuddleLive(joined = true, playing = listOf("Juniper", "Wrennie")), huddleLive(state, now))
        assertEquals(HuddleLive(joined = true), huddleLive(state, now + 900_000))
        // A play from someone no longer a friend counts for nothing.
        assertEquals(HuddleLive(joined = true), huddleLive(HuddleState(name = "Kai", plays = listOf(FriendPlay("f9", now - 60_000, lantern))), now))
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

    @Test
    fun `a friend's plays read by day, the newest day first, each play on the day the phone's clock says`() {
        val zone = ZoneOffset.ofHours(3)
        val midnight = LocalDate.of(2026, 11, 23).atStartOfDay(zone).toInstant().toEpochMilli()
        val plays = listOf(
            FriendPlay("f1", midnight + 60_000, lantern),
            FriendPlay("f1", midnight, bell),
            FriendPlay("f1", midnight - 1, lantern),
            // A day without plays has no heading.
            FriendPlay("f1", midnight - 2 * 86_400_000L - 1, bell),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 11, 23) to plays.take(2),
                LocalDate.of(2026, 11, 22) to listOf(plays[2]),
                LocalDate.of(2026, 11, 20) to listOf(plays[3]),
            ),
            playDays(plays, zone),
        )
        assertEquals("another zone, another day", LocalDate.of(2026, 11, 22), playDays(plays.take(1), ZoneOffset.UTC).single().first)
        assertTrue(playDays(emptyList(), zone).isEmpty())
    }

    @Test
    fun `a friend's song and their menu open the history, and Sent to you has View all`() {
        val sheets = File("src/main/java/app/lyrify/ui/HuddleSheets.kt").readText()
        val peek = sheets.substringAfter("private fun PeekSheet(").substringBefore("private fun RowScope.PeekTile(")
        assertTrue(peek.contains("Page.HuddleThread(friend.id)"))
        assertTrue(peek.contains("Page.HuddlePlays(friend.id)"))
        val menu = sheets.substringAfter("private fun FriendMenuSheet(").substringBefore("private fun PrivatelySheet(")
        assertTrue(menu.contains("Page.HuddleThread(friend.id)"))
        // Outside the shell (HuddleHost) no page opens, so neither row shows there.
        assertTrue(File("src/main/java/app/lyrify/ui/HuddleScreen.kt").readText().contains("HuddleSheetsHost(sheet, ledger, onPlayer, onPage = null)"))
        val page = File("src/main/java/app/lyrify/ui/HuddleScreen.kt").readText().substringAfter("fun HuddlePage(")
        val sent = page.substringAfter("item(key = \"sent\"").substringBefore("item(key = \"postcards\"")
        assertTrue(sent.contains("nav.open(Page.HuddleCards)"))
        // KJB's headings all say View all.
        assertTrue(sent.contains("LyrifyButton(stringResource(R.string.lib_view_all)"))
        assertTrue(File("src/main/java/app/lyrify/ui/AppShell.kt").readText().contains("is Page.HuddlePages -> HuddleHistoryPage(page)"))
    }

    @Test
    fun `the history's lists read plays_db in pages, and a friend's play plays as the peek's Play it does`() {
        val history = File("src/main/java/app/lyrify/ui/HuddleHistory.kt").readText()
        listOf("huddle.thread(friendId, from, limit)", "huddle.received(from, limit)", "huddle.friendPlays(friendId, from, limit)").forEach { assertTrue(it, history.contains(it)) }
        assertTrue(history.contains("playIt(context, "))
        // A card or play kept at the top between two pages moves the next page down: what was read already shows once,
        // sorted out off the main thread.
        assertTrue(history.contains("rows = withContext(Dispatchers.Default) { (rows + page).distinct() }"))
        // After a turn of the phone as many rows as were read come back at once, so the list is where it was.
        assertTrue(history.contains("var reached by rememberSaveable { mutableIntStateOf(0) }"))
        assertTrue(history.contains("val asked = maxOf(PAGE, value?.size ?: reached)"))
        val peek = File("src/main/java/app/lyrify/ui/HuddleSheets.kt").readText().substringAfter("private fun PeekSheet(").substringBefore("private fun RowScope.PeekTile(")
        assertTrue(peek.contains("playIt(context, "))
    }

    @Test
    fun `a friend gone while their page is open shows they left, under Back, and closes nothing by itself`() {
        val history = File("src/main/java/app/lyrify/ui/HuddleHistory.kt").readText()
        // Back would close the player first when it is up over the page (NavState.back), and leave the page blank.
        assertFalse(history.contains("onBack() }"))
        listOf("private fun ThreadPage(", "private fun PlaysPage(").forEach { page ->
            assertTrue(page, history.substringAfter(page).substringBefore("\n}\n").contains("stringResource(R.string.huddle_gone_title)"))
        }
    }

    @Test
    fun `a card you sent opens as yours, to whom it went, with Play and neither Send back nor Ask`() {
        val sheet = File("src/main/java/app/lyrify/ui/HuddleSheets.kt").readText().substringAfter("private fun ReceivedSheet(").substringBefore("private fun SentBy(")
        assertTrue(sheet.contains("val mine = kept is Sent"))
        assertTrue(sheet.contains("if (!mine) LyrifyButton(stringResource(R.string.huddle_send_back)"))
        assertTrue(sheet.contains("!mine && missing.isNotEmpty()"))
        // Only a friend's card is opened: one you sent writes nothing.
        assertTrue(sheet.contains("LaunchedEffect(id) { if (!mine) huddle.opened(id) }"))
    }
}
