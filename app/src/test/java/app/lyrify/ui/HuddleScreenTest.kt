package app.lyrify.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import app.lyrify.R
import app.lyrify.huddle.Card
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.CardSong
import app.lyrify.huddle.Friend
import app.lyrify.huddle.FriendPlay
import app.lyrify.huddle.HuddleState
import app.lyrify.huddle.Match
import app.lyrify.huddle.Message
import app.lyrify.huddle.NowCover
import app.lyrify.huddle.NowState
import app.lyrify.huddle.PendingInvite
import app.lyrify.huddle.Received
import app.lyrify.library.song
import app.lyrify.settings.LibraryTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** A friend's now: [song] in [state] at [positionMs], told [at]. */
    private fun told(song: CardSong, state: NowState, at: Long, positionMs: Long = 0) = Message.Now("n-$at", at, song, state, positionMs)

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
    fun `friends playing now come first, the song begun last first, then everyone else by their latest play, paused or not, then by name`() {
        val now = 10_000_000L
        val state = HuddleState(
            friends = listOf(
                friend("f1", "Wren"), friend("f2", "Juniper"), friend("f3", "Rook"), friend("f4", "Ash"), friend("f5", "Moss"), friend("f6", "Birch"),
                friend("f7", "Fern"), friend("f8", "Sorrel"), friend("f9", "Larch"),
            ),
            plays = listOf(
                FriendPlay("f1", now - 100_000, lantern),
                FriendPlay("f2", now - 30_000, bell),
                FriendPlay("f3", now - 900_000, bell),
                FriendPlay("f4", now - 600_000, lantern),
                FriendPlay("f8", now - 60_000, lantern),
            ),
            live = mapOf(
                "f1" to told(lantern, NowState.PLAYING, now - 40_000, positionMs = 60_000),
                "f2" to told(bell, NowState.PLAYING, now - 20_000, positionMs = 10_000),
                // Paused on a song begun before Sorrel's latest play, and one begun after it.
                "f7" to told(bell, NowState.PAUSED, now - 50_000, positionMs = 120_000),
                "f9" to told(lantern, NowState.PAUSED, now - 5_000, positionMs = 10_000),
            ),
        )
        assertEquals(listOf("f2", "f1", "f9", "f8", "f7", "f4", "f3", "f6", "f5"), friendsNow(state, now).map { it.friend.id })
        assertEquals(listOf(true, true, false, false, false, false, false, false, false), friendsNow(state, now).map { it.playing })
        assertEquals(listOf(false, false, true, false, true, false, false, false, false), friendsNow(state, now).map { it.paused })
    }

    @Test
    fun `the player shows a friend playing the same song first, then one on the same artist, never one who stopped`() {
        val now = 10_000_000L
        val other = CardSong("Low Tide", "Odile Marsh", null, 200_000)
        val state = HuddleState(
            friends = listOf(friend("f1", "Wren"), friend("f2", "Juniper"), friend("f3", "Rook"), friend("f4", "Ash")),
            plays = listOf(FriendPlay("f3", now - 30_000, lantern)),
            live = mapOf(
                "f2" to told(bell, NowState.PLAYING, now - 20_000),
                "f1" to told(lantern, NowState.PLAYING, now - 100_000, positionMs = 20_000),
                "f3" to told(lantern, NowState.STOPPED, now - 10_000),
                "f4" to told(other, NowState.PLAYING, now - 10_000),
                "f5" to told(lantern, NowState.PAUSED, now - 10_000),
            ),
        )
        val on = friendsOn(state.copy(friends = state.friends + friend("f5", "Moss")), now, "Paper Lantern", "Kit Calloway")
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
        assertTrue(huddleLive(HuddleState(friends = listOf(wren), live = mapOf("f1" to told(lantern, NowState.PLAYING, now - 60_000))), now).playing.isNotEmpty())
        assertFalse("paused", huddleLive(HuddleState(friends = listOf(wren), live = mapOf("f1" to told(lantern, NowState.PAUSED, now - 60_000))), now).playing.isNotEmpty())
        assertFalse("a play alone", huddleLive(HuddleState(friends = listOf(wren), plays = listOf(FriendPlay("f1", now - 60_000, lantern))), now).playing.isNotEmpty())
        assertFalse(huddleLive(HuddleState(live = mapOf("f1" to told(lantern, NowState.PLAYING, now - 60_000))), now).playing.isNotEmpty())
    }

    @Test
    fun `the headers' Huddle knows whether a name is picked and who plays now, by the names you see, the latest first`() {
        val now = 10_000_000L
        assertEquals(HuddleLive(joined = false), huddleLive(HuddleState(), now))
        val wren = friend("f1", "Wren", rename = "Wrennie")
        val state = HuddleState(
            name = "Kai",
            friends = listOf(wren, friend("f2", "Juniper"), friend("f3", "Rook")),
            live = mapOf(
                "f1" to told(lantern, NowState.PLAYING, now - 60_000),
                "f2" to told(bell, NowState.PLAYING, now - 30_000),
                "f3" to told(bell, NowState.PLAYING, now - 900_000),
            ),
        )
        assertEquals(HuddleLive(joined = true, playing = listOf("Juniper", "Wrennie")), huddleLive(state, now))
        assertEquals(HuddleLive(joined = true), huddleLive(state, now + 900_000))
        // A now from someone no longer a friend counts for nothing.
        assertEquals(HuddleLive(joined = true), huddleLive(HuddleState(name = "Kai", live = mapOf("f9" to told(lantern, NowState.PLAYING, now - 60_000))), now))
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
    fun `the friends row keeps its full size, 56 dp faces over the name and a short line, room round it and a divider under it`() {
        val everywhere = File("src/main/java/app/lyrify/ui/HuddleEverywhere.kt").readText()
        val row = everywhere.substringAfter("internal fun FriendsRow(").substringBefore("\n}\n")
        // The lock, Invite and every friend at 56 dp: the user put the compact row's 40 dp back to this (2026-11-23).
        listOf(
            "Modifier.size(56.dp).background(chrome.action, CircleShape)",
            "Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = chrome.onAction, modifier = Modifier.size(24.dp))",
            "RowFace(stringResource(R.string.huddle_row_invite), stringResource(R.string.huddle_row_invite_line), strong = false, description = stringResource(R.string.huddle_invite), onClick = onInvite) { SeatFace(56.dp) }",
            "FriendFace(name, friendColor(friend.friend.id), 56.dp, ring = if (friend.playing) FaceRing.LIVE else FaceRing.AWAY, playing = friend.playing, paused = friend.paused, mail = sent, cover = friend.cover?.takeIf { friend.playing }?.picture)",
            "contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)",
            // Under each name: their song while they play, Paused, or "15 min ago"; under Private, until when.
            "friend.playing -> play.song.title",
            "friend.paused -> stringResource(R.string.huddle_paused)",
            "else -> DateUtils.getRelativeTimeSpanString(play.at, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()",
            "description = stringResource(R.string.huddle_private_desc, line)",
        ).forEach { assertTrue(it, row.contains(it)) }
        // "Invite" over "a friend".
        assertTrue("""<string name="huddle_row_invite_line">a friend</string>""" in File("src/main/res/values/strings_huddle.xml").readText())
        val parts = File("src/main/java/app/lyrify/ui/HuddleParts.kt").readText()
        // The bars' badge and a card's badge at the 56 dp face's size, where they sat on it.
        val friendFace = parts.substringAfter("internal fun FriendFace(").substringBefore("\n}\n")
        assertTrue(friendFace.contains(".offset(3.dp, 3.dp)\n                    .size(24.dp)"))
        assertTrue(friendFace.contains("if (mail) MailBadge(gap, Modifier.align(Alignment.TopEnd).offset(6.dp, (-4).dp))"))
        val mail = parts.substringAfter("private fun MailBadge(").substringBefore("\n}\n")
        assertTrue(mail.contains(".size(24.dp)"))
        assertTrue(mail.contains("Icon(painterResource(R.drawable.ic_send), contentDescription = null, tint = brand.onHighlight, modifier = Modifier.size(13.dp))"))
        val face = parts.substringAfter("internal fun RowFace(").substringBefore("\n}\n")
        assertTrue(face.startsWith("name: String, line: String?, strong: Boolean, description: String, onClick: () -> Unit, face: @Composable () -> Unit)"))
        assertTrue(face.contains(".width(74.dp)"))
        assertTrue(face.contains("Box(Modifier.padding(bottom = 6.dp)) { face() }"))
        assertEquals("the name and its line", 2, Regex("""\bText\(""").findAll(face).count())
        // Each face is a 48 dp touch target.
        assertTrue(face.contains(".heightIn(min = 48.dp)"))
        // The divider under the row.
        assertTrue(everywhere.substringAfter("private fun HuddleFriends(").substringBefore("\n}\n").contains(".height(1.dp).background(MaterialTheme.colorScheme.outlineVariant)"))
    }

    @Test
    fun `on the friends row a friend playing now shows their song's cover in the ring, and a paused or quiet one their letter greyed and still`() {
        val everywhere = File("src/main/java/app/lyrify/ui/HuddleEverywhere.kt").readText()
        val row = everywhere.substringAfter("internal fun FriendsRow(").substringBefore("\n}\n")
        // The cover only from what friendsNow says of each friend (their newest now), never read again from the state.
        assertTrue(row.contains("items(friendsNow(state, now), key = { it.friend.id }) { friend ->"))
        assertTrue(row.contains("cover = friend.cover?.takeIf { friend.playing }?.picture"))
        assertFalse(row.contains("state.live"))
        val parts = File("src/main/java/app/lyrify/ui/HuddleParts.kt").readText()
        val face = parts.substringAfter("internal fun FriendFace(").substringBefore("\n}\n")
        // The cover fills the face inside the live ring and its gap, cut round; without one, the letter as before.
        assertTrue(face.contains("cover: ImageBitmap? = null,"))
        assertTrue(face.contains("Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().clip(CircleShape))"))
        // Away: the quiet ring, no motion, the face greyed through but as light as its colour, so the letter reads as well.
        assertTrue(face.contains("FaceRing.IDLE, FaceRing.AWAY -> Box(Modifier.matchParentSize().border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))"))
        assertTrue(face.contains("FaceRing.AWAY -> color.luminance().let { Color(it, it, it, colorSpace = ColorSpaces.LinearSrgb).convert(ColorSpaces.Srgb) }"))
        FriendColors.forEach { color ->
            val away = color.luminance().let { Color(it, it, it, colorSpace = ColorSpaces.LinearSrgb).convert(ColorSpaces.Srgb) }
            assertEquals("$color", 0f, away.red - away.blue, 0.001f)
            assertTrue("$color", contrast(BrandLilac, away) >= 4.5f)
        }
        // Paused: a still pause mark where a playing face has its bars, on the face's own grey.
        assertTrue(face.contains("if (playing) Equaliser(BrandLilac, Modifier.size(12.dp, 10.dp)) else Icon(painterResource(R.drawable.ic_pause_fill), contentDescription = null, tint = BrandLilac, modifier = Modifier.size(13.dp))"))
        assertTrue(face.contains(".background(lerp(ground, BrandBlack, 0.18f), CircleShape)"))
        // The Huddle page, the sheets, the player and the Now bar keep their faces: only the friends row greys one through,
        // pauses one or shows a cover in one.
        val calls = listOf("HuddleParts.kt", "HuddleScreen.kt", "HuddleSheets.kt", "HuddleInvite.kt", "HuddleEverywhere.kt")
            .flatMap { File("src/main/java/app/lyrify/ui/$it").readText().lines() }
            .filter { "FriendFace(" in it && "fun FriendFace(" !in it }
        assertTrue(calls.size >= 15)
        calls.filter { "FaceRing.AWAY" in it || "paused = " in it || "cover = " in it }.let { assertEquals(listOf(row.lines().single { "FriendFace(" in it }), it) }
    }

    @Test
    fun `TalkBack says each face on the friends row as playing a song by its artist, paused, or when they last played`() {
        val strings = File("src/main/res/values/strings_huddle.xml").readText()
        assertTrue("""<string name="huddle_row_playing">%1${'$'}s, playing %2${'$'}s</string>""" in strings)
        assertTrue("""<string name="huddle_row_paused">%1${'$'}s, paused</string>""" in strings)
        assertTrue("""<string name="huddle_row_played">%1${'$'}s, played %2${'$'}s</string>""" in strings)
        val row = File("src/main/java/app/lyrify/ui/HuddleEverywhere.kt").readText().substringAfter("internal fun FriendsRow(").substringBefore("\n}\n")
        listOf(
            "friend.playing -> stringResource(R.string.huddle_row_playing, name, songBy(resources, play.song))",
            "friend.paused -> stringResource(R.string.huddle_row_paused, name)",
            "else -> stringResource(R.string.huddle_row_played, name, ago(play.at, now))",
        ).forEach { assertTrue(it, row.contains(it)) }
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
    fun `a friend plays only while their newest now says so, two heartbeats missed at most, and not past their song's end and 30 s`() {
        val wren = friend("f1", "Wren")
        val now = 10_000_000L
        val playing = told(lantern, NowState.PLAYING, now - 20_000, positionMs = 60_000)
        val state = HuddleState(friends = listOf(wren), plays = listOf(FriendPlay("f1", now - 900_000, bell)), live = mapOf("f1" to playing))
        // Where they are: the place told, and the time since; Play along starts there.
        assertEquals(FriendNow(wren, FriendPlay("f1", now - 80_000, lantern), playing = true), friendsNow(state, now).single())
        assertEquals(80_000L, playAlongMs(friendsNow(state, now).single().play!!, now))
        assertTrue(friendsNow(state, now - 20_000 + 150_000).single().playing)
        assertFalse("two heartbeats missed", friendsNow(state, now - 20_000 + 150_001).single().playing)
        assertEquals("then their latest play, as before", FriendPlay("f1", now - 900_000, bell), friendsNow(state, now - 20_000 + 150_001).single().play)
        val ending = state.copy(live = mapOf("f1" to told(lantern, NowState.PLAYING, now - 20_000, positionMs = 150_000)))
        assertTrue(friendsNow(ending, now + 60_000).single().playing)
        assertFalse("its song ended 30 s ago", friendsNow(ending, now + 60_001).single().playing)
        assertFalse("their latest play alone never is", friendsNow(HuddleState(friends = listOf(wren), plays = listOf(FriendPlay("f1", now - 10_000, lantern))), now).single().playing)
    }

    @Test
    fun `a friend's cover comes with their song while it plays or is paused, and goes with it`() {
        val wren = friend("f1", "Wren")
        val now = 10_000_000L
        val cover = NowCover(byteArrayOf(-1, -40, -1))
        val playing = HuddleState(friends = listOf(wren), live = mapOf("f1" to told(lantern, NowState.PLAYING, now - 20_000).copy(cover = cover)))
        assertSame(cover, friendsNow(playing, now).single().cover)
        assertNull("two heartbeats missed", friendsNow(playing, now - 20_000 + 150_001).single().cover)
        val paused = playing.copy(live = mapOf("f1" to told(lantern, NowState.PAUSED, now - 60_000).copy(cover = cover)))
        assertSame(cover, friendsNow(paused, now).single().cover)
        assertNull("paused 10 minutes ago", friendsNow(paused, now - 60_000 + 600_001).single().cover)
        assertNull("stopped", friendsNow(playing.copy(live = mapOf("f1" to told(lantern, NowState.STOPPED, now - 1_000).copy(cover = cover))), now).single().cover)
    }

    @Test
    fun `What leaves your phone says each song you play goes with a small picture of its cover`() {
        assertTrue(
            "so each song you play goes to them too, with a small picture of its cover, unless you stop sharing with them or listen privately." in
                File("src/main/res/values/strings_huddle.xml").readText(),
        )
    }

    @Test
    fun `a paused friend is paused for 10 minutes after the pause, still, and a stopped one leaves at once`() {
        val wren = friend("f1", "Wren")
        val now = 10_000_000L
        val history = FriendPlay("f1", now - 900_000, bell)
        val paused = HuddleState(friends = listOf(wren), plays = listOf(history), live = mapOf("f1" to told(lantern, NowState.PAUSED, now - 60_000, positionMs = 30_000)))
        assertEquals(FriendNow(wren, FriendPlay("f1", now - 90_000, lantern), playing = false, paused = true), friendsNow(paused, now).single())
        assertTrue(friendsNow(paused, now - 60_000 + 600_000).single().paused)
        assertEquals(FriendNow(wren, history, playing = false), friendsNow(paused, now - 60_000 + 600_001).single())
        val stopped = paused.copy(live = mapOf("f1" to told(lantern, NowState.STOPPED, now - 1_000, positionMs = 30_000)))
        assertEquals(FriendNow(wren, history, playing = false), friendsNow(stopped, now).single())
        // The card, the friends row and their song's sheet say Paused, with a still face: no ring, no bars.
        val parts = File("src/main/java/app/lyrify/ui/HuddleParts.kt").readText()
        val everywhere = File("src/main/java/app/lyrify/ui/HuddleEverywhere.kt").readText()
        val sheets = File("src/main/java/app/lyrify/ui/HuddleSheets.kt").readText()
        assertTrue(parts.substringAfter("internal fun FriendCard(").substringBefore("\n}\n").contains("R.string.huddle_paused"))
        assertTrue(everywhere.substringAfter("internal fun FriendsRow(").substringBefore("\n}\n").contains("R.string.huddle_row_paused"))
        assertTrue(sheets.substringAfter("private fun PeekSheet(").substringBefore("\n}\n").contains("R.string.huddle_paused"))
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
        // A paused friend's card on the Huddle page: Play it, the same way.
        assertTrue(File("src/main/java/app/lyrify/ui/HuddleScreen.kt").readText().contains("friend.paused -> playIt(context, song, play, friend.friend.shownName, player)"))
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
        // Changed on purpose (HY2): neither shows for a past friend's card either ([answerable]).
        assertTrue(sheet.contains("val answerable = !mine && friend != null"))
        assertTrue(sheet.contains("if (answerable) LyrifyButton(stringResource(R.string.huddle_send_back)"))
        assertTrue(sheet.contains("answerable && missing.isNotEmpty()"))
        // Only a friend's card is opened: one you sent writes nothing.
        assertTrue(sheet.contains("LaunchedEffect(id) { if (!mine) huddle.opened(id) }"))
    }

    @Test
    fun `past friends end the Huddle page's friends list when there are any, and open a page of them, each their history`() {
        val page = File("src/main/java/app/lyrify/ui/HuddleScreen.kt").readText().substringAfter("fun HuddlePage(")
        assertTrue(page.contains("if (shown.past.isNotEmpty()) {"))
        // After the friends not playing, before the invites.
        assertTrue(page.indexOf("item(key = \"past\"") in page.indexOf("item(key = \"idle\"")..page.indexOf("item(key = \"invites\""))
        assertTrue(page.substringAfter("item(key = \"past\"").substringBefore("item(key = \"invites\"").contains("nav.open(Page.HuddlePast)"))
        val history = File("src/main/java/app/lyrify/ui/HuddleHistory.kt").readText()
        assertTrue(history.contains("Page.HuddlePast -> PastPage { nav.back() }"))
        val list = history.substringAfter("private fun PastPage(").substringBefore("\n}\n")
        assertTrue(list.contains("stringResource(R.string.huddle_past_title)"))
        assertTrue(list.contains("nav.open(Page.HuddleThread(past.id))"))
    }

    @Test
    fun `a past friend's pages keep the history under the name you saw them by, say they left, and the thread offers Delete history, asked first`() {
        val history = File("src/main/java/app/lyrify/ui/HuddleHistory.kt").readText()
        listOf("private fun ThreadPage(", "private fun PlaysPage(").forEach { name ->
            val page = history.substringAfter(name).substringBefore("\n}\n")
            assertTrue(name, page.contains("val past = state?.past?.firstOrNull { it.id == friendId }"))
            assertTrue(name, page.contains("val name = friend?.shownName ?: past?.name.orEmpty()"))
            assertTrue(name, page.contains("stringResource(R.string.huddle_past_note)"))
        }
        val thread = history.substringAfter("private fun ThreadPage(").substringBefore("\n}\n")
        assertTrue(thread.contains("MenuRow(stringResource(R.string.huddle_delete_history), R.drawable.ic_delete, destructive = true"))
        assertTrue(thread.contains("ConfirmDialog("))
        assertTrue(thread.contains("huddle.deleteHistory(friendId)"))
        assertTrue("nothing to send to a past friend", thread.contains("action = stringResource(R.string.huddle_send_song_to, name).takeIf { past == null }"))
        // View all keeps a past friend's cards, under the name you saw them by, and shows whenever cards are kept, a past
        // friend's alone too; the row itself shows friends still here.
        assertTrue(history.substringAfter("private fun CardsPage(").contains("state?.past.orEmpty().associate { it.id to it.name }"))
        val sent = File("src/main/java/app/lyrify/ui/HuddleScreen.kt").readText().substringAfter("item(key = \"sent\"").substringBefore("item(key = \"postcards\"")
        assertTrue(sent.contains("if (!cards.isNullOrEmpty()) LyrifyButton(stringResource(R.string.lib_view_all)"))
        val strings = File("src/main/res/values/strings_huddle.xml").readText()
        assertTrue(strings.substringAfter("<string name=\"huddle_remove_text\">").substringBefore("</string>").contains("stays on this phone"))
    }
}
