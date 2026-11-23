package app.lyrify.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.backup.SongMatcher
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.Friend
import app.lyrify.huddle.HuddleState
import app.lyrify.huddle.Match
import app.lyrify.huddle.Received
import app.lyrify.huddle.match
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.settings.LibraryTab
import java.util.Date
import kotlinx.coroutines.launch

// Friends everywhere (the approved mockup's part 1, user 2026-11-22: seeing what friends play must not need the Huddle
// page): Library's friends row with the newest card a friend sent, their faces on the player and the Now bar, and the
// bar's Huddle item's live dot.

/** Huddle's friends row and the newest card a friend sent ([HuddleFriends]), as the first row of Library's [tab] list. */
fun LazyListScope.huddleFriends(tab: LibraryTab) = item(key = "huddle", contentType = "huddle") {
    HuddleFriends(tab, rowMotion(LocalReducedMotion.current))
}

/** The same as the first row of Library's [tab] grid (Albums, Books): the friends row reaches past the grid's sides. */
fun LazyGridScope.huddleFriends(tab: LibraryTab) = item(key = "huddle", span = { GridItemSpan(maxLineSpan) }, contentType = "huddle") {
    HuddleFriends(tab, rowMotion(LocalReducedMotion.current), bleed = ScreenPadding)
}

/**
 * Library's friends (the approved mockup's parts 1 and 4.1), on the tab Library opens on once there is a friend
 * ([friendsRowShows]): the friends row (Invite first, a lock while you listen privately, then friends playing now with
 * their live rings, then the others), and under it the newest card a friend sent ([newestCard]) with Play, Send back and
 * Hide. A face opens that friend's song ([HuddleSheet.Peek]). [bleed]: how far past the list's sides the row reaches.
 */
@Composable
private fun HuddleFriends(tab: LibraryTab, modifier: Modifier, bleed: Dp = 0.dp) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val huddle = rememberHuddle()
    val scope = rememberCoroutineScope()
    val settings by remember(context) { Lyrify.settings(context).settings }.collectAsStateWithLifecycle(null)
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
    var sheet by rememberSaveable(stateSaver = HuddleSheetSaver) { mutableStateOf<HuddleSheet?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var playNow by rememberSaveable { mutableStateOf(false) }
    val player = { nav.show(FullScreen.Player) }
    HuddleSheetsHost(sheet, LocalLyrics.current.ledger, player, nav::open) { sheet = it }
    ReceivedHost(opened, player, onSendBack = { sheet = HuddleSheet.SendTo(it) }, playNow = playNow) { opened = null }
    val state = rememberHuddleState() ?: return
    val read = settings ?: return
    if (!friendsRowShows(tab, libraryOpensOn(shownLibraryTabs(read.library, (library as? LibraryState.Ready)?.library), read.library.opensOn), state)) return
    val now by rememberNow()
    val matcher = rememberSongMatcher()
    Column(modifier.fillMaxWidth()) {
        FriendsRow(
            state,
            now,
            Modifier.layout { measurable, constraints ->
                val side = bleed.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * side, maxWidth = constraints.maxWidth + 2 * side))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-side, 0) }
            },
            onFriend = { sheet = HuddleSheet.Peek(it) },
            onInvite = { sheet = HuddleSheet.Ticket },
            onPrivately = { sheet = HuddleSheet.Privately },
        )
        newestCard(state, rememberHuddleInbox().orEmpty())?.let { received ->
            val friend = state.friends.firstOrNull { it.id == received.friend } ?: return@let
            FromCard(
                received,
                friend,
                now,
                matcher,
                Modifier.padding(horizontal = ScreenPadding - bleed, vertical = 6.dp),
                onOpen = { play ->
                    playNow = play
                    opened = received.id
                },
                onSendBack = { sheet = HuddleSheet.SendTo(friend.id) },
                onHide = { scope.launch { huddle.opened(received.id, dismissed = true) } },
            )
        }
    }
}

/**
 * The friends row, compact (user 2026-11-23): a lock first while you listen privately, Invite, then every friend
 * ([friendsNow]), each a 40 dp face over their name. Playing now has the live ring and the bars, paused or not playing a
 * quiet, still ring; a card not opened yet wiggles a badge on its sender's face. What a face does not say in words (their
 * song, Paused, when they last played, until when you listen privately) TalkBack says.
 */
@Composable
internal fun FriendsRow(state: HuddleState, now: Long, modifier: Modifier, onFriend: (String) -> Unit, onInvite: () -> Unit, onPrivately: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val chrome = LocalChromeColors.current
    val waiting = rememberHuddleInbox().orEmpty().filter { !it.opened }.mapTo(HashSet()) { it.friend }
    val until = state.privateUntil?.takeIf { now < it }
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (until != null) {
            item(key = "private") {
                RowFace(
                    stringResource(R.string.huddle_row_private),
                    description = stringResource(R.string.huddle_private_desc, if (until == HuddleState.FOREVER) stringResource(R.string.huddle_row_private_on) else stringResource(R.string.huddle_row_private_until, DateFormat.getTimeFormat(context).format(Date(until)))),
                    onClick = onPrivately,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(chrome.action, CircleShape)) {
                        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = chrome.onAction, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        item(key = "invite") {
            RowFace(stringResource(R.string.huddle_row_invite), description = stringResource(R.string.huddle_invite), onClick = onInvite) { SeatFace(40.dp) }
        }
        items(friendsNow(state, now), key = { it.friend.id }) { friend ->
            val name = friend.friend.shownName
            val play = friend.play
            val sent = friend.friend.id in waiting
            val said = when {
                play == null -> name
                friend.playing -> stringResource(R.string.huddle_row_playing, name, songBy(resources, play.song))
                friend.paused -> stringResource(R.string.huddle_row_paused, name, songBy(resources, play.song))
                else -> stringResource(R.string.huddle_row_played, name, songBy(resources, play.song), ago(play.at, now))
            }
            RowFace(name, description = if (sent) listOf(said, stringResource(R.string.huddle_sent_you)).joinToString(", ") else said, onClick = { onFriend(friend.friend.id) }) {
                FriendFace(name, friendColor(friend.friend.id), 40.dp, ring = if (friend.playing) FaceRing.LIVE else FaceRing.IDLE, playing = friend.playing, mail = sent)
            }
        }
    }
}

/**
 * A card a friend sent, at the top of Library (the mockup's 4.1): who sent it and when (New until it is opened), its
 * cover tilted like a photo (yours, else their colour's pattern), what it is and the note, then Play (plays a song you
 * have at once, else opens the card), Send back and Hide (it stays on the Huddle page). A tap opens it.
 */
@Composable
internal fun FromCard(received: Received, friend: Friend, now: Long, matcher: SongMatcher?, modifier: Modifier, onOpen: (Boolean) -> Unit, onSendBack: () -> Unit, onHide: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val card = received.card
    val first = card.songs.first()
    val name = friend.shownName
    val color = friendColor(friend.id)
    val found = remember(matcher, first) { matcher?.match(first) }
    val who = stringResource(
        when (card.kind) {
            CardKind.SONG -> R.string.huddle_sent_song
            CardKind.ALBUM -> R.string.huddle_sent_album
            CardKind.PLAYLIST -> R.string.huddle_sent_playlist
        },
        name,
    )
    val title = if (card.kind == CardKind.SONG) first.title else card.name ?: first.album.orEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(lerp(colors.background, color, 0.22f), lerp(colors.background, HuddleGold, 0.2f))))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.huddle_open_card)) { onOpen(false) }
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FriendFace(name, color, 28.dp, gap = Color.Transparent)
            Text(who, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = lerp(color, colors.onSurface, 0.38f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("· " + whenSent(context, received.at, now), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
            if (!received.opened) NewMark()
            Spacer(Modifier.weight(1f))
            LyrifyButton(stringResource(R.string.huddle_hide), onClick = onHide, kind = ButtonKind.GHOST, compact = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp, end = 8.dp)) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .rotate(-5f)
                    .shadow(6.dp, RoundedCornerShape(6.dp), ambientColor = LocalChromeColors.current.shadow, spotColor = LocalChromeColors.current.shadow)
                    .background(BrandLilac, RoundedCornerShape(6.dp))
                    .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 12.dp),
            ) { FriendArt((found as? Match.Found)?.song ?: (found as? Match.Maybe)?.song, color, 56.dp, RoundedCornerShape(3.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = FromTitle, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (card.kind == CardKind.SONG) first.artist.orEmpty() else pluralStringResource(R.plurals.lib_songs, card.songs.size, card.songs.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.note?.let {
                    Text(
                        stringResource(R.string.huddle_note_quoted, it),
                        style = NoteStyle,
                        color = colors.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp).background(colors.background, RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp, end = 8.dp)) {
            LyrifyButton(stringResource(R.string.huddle_play), onClick = { onOpen(true) }, icon = R.drawable.ic_play_arrow_fill, compact = true)
            LyrifyButton(stringResource(R.string.huddle_send_back), onClick = onSendBack, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_send, compact = true)
        }
    }
}


/**
 * The Now bar's friend (the mockup's 1.4): the face, with its live ring, of the first friend playing now your song [title]
 * by [artist], or another of that artist's ([friendsOn]); a tap opens their song. Nothing while nobody is.
 */
@Composable
fun NowBarFriend(title: String, artist: String?) {
    val nav = LocalNavigator.current
    var sheet by rememberSaveable(stateSaver = HuddleSheetSaver) { mutableStateOf<HuddleSheet?>(null) }
    HuddleSheetsHost(sheet, LocalLyrics.current.ledger, { nav.show(FullScreen.Player) }, nav::open) { sheet = it }
    val state = rememberHuddleState() ?: return
    val now by rememberNow()
    val on = friendsOn(state, now, title, artist).firstOrNull() ?: return
    val friend = on.friend.friend
    val name = friend.shownName
    val label = if (on.sameSong) stringResource(R.string.huddle_on_song, name) else stringResource(R.string.huddle_on_artist, name, artist.orEmpty())
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) { sheet = HuddleSheet.Peek(friend.id) }
            .semantics { contentDescription = label },
    ) {
        FriendFace(name, friendColor(friend.id), 36.dp, ring = FaceRing.LIVE, gap = LocalChromeColors.current.bar)
    }
}

/**
 * The player's friends (the mockup's 1.3) under the song's title: "Wren is playing this too", or "Omar is playing Kestrel
 * too", with their face, for the first friend playing now your song [title] by [artist] or another of that artist's; a
 * tap opens their song. Nothing while nobody is.
 */
@Composable
internal fun PlayerFriendChip(title: String, artist: String?) {
    val nav = LocalNavigator.current
    var sheet by rememberSaveable(stateSaver = HuddleSheetSaver) { mutableStateOf<HuddleSheet?>(null) }
    HuddleSheetsHost(sheet, LocalLyrics.current.ledger, { nav.show(FullScreen.Player) }, nav::open) { sheet = it }
    val state = rememberHuddleState() ?: return
    val now by rememberNow()
    val on = friendsOn(state, now, title, artist).firstOrNull() ?: return
    val friend = on.friend.friend
    val name = friend.shownName
    val color = friendColor(friend.id)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(lerp(MaterialTheme.colorScheme.background, color, 0.18f))
                .clickable(role = Role.Button) { sheet = HuddleSheet.Peek(friend.id) }
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        ) {
            FriendFace(name, color, 32.dp, gap = Color.Transparent)
            Text(
                if (on.sameSong) stringResource(R.string.huddle_on_song, name) else stringResource(R.string.huddle_on_artist, name, artist.orEmpty()),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Friends playing this very song now, as faces stuck on the corner of the player's cover (the mockup's 1.3), two at most,
 * their live rings turning. Decorative: the line under the title says so.
 */
@Composable
internal fun CoverFriends(title: String, artist: String?, modifier: Modifier) {
    val state = rememberHuddleState() ?: return
    val now by rememberNow()
    val same = friendsOn(state, now, title, artist).filter { it.sameSong }.take(2)
    if (same.isEmpty()) return
    Row(modifier) {
        same.forEachIndexed { at, on ->
            FriendFace(on.friend.friend.shownName, friendColor(on.friend.friend.id), 52.dp, Modifier.offset(x = (-14 * at).dp), ring = FaceRing.LIVE)
        }
    }
}

/**
 * What the bar's Huddle item and the headers' Huddle show ([huddleLive]), for the shell to read once: a State that
 * changes only when that does; joined with nobody playing until Huddle is read.
 */
@Composable
fun rememberHuddleLive(): State<HuddleLive> {
    val state = rememberHuddle().state.collectAsStateWithLifecycle(initialValue = null)
    val now = rememberNow()
    return remember { derivedStateOf { state.value?.let { huddleLive(it, now.value) } ?: HuddleLive() } }
}

private val FromTitle = display(18, 23, weight = 650)
private val NoteStyle = body(15f, 20, 500)
