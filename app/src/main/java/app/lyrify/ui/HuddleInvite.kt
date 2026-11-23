package app.lyrify.ui

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lyrify.R
import app.lyrify.huddle.Accepted
import app.lyrify.huddle.Card
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.Friend
import app.lyrify.huddle.Invited
import app.lyrify.huddle.Invites
import app.lyrify.huddle.Match
import app.lyrify.huddle.match
import kotlin.math.abs
import kotlinx.coroutines.launch
import java.util.UUID

// Huddle's invite (the approved mockup's part 3): the ticket you hand a friend, what they see when they open it, and the
// moment they join.

/**
 * Invite a friend, as a ticket for one seat (the mockup's 3.1): "A seat in <your name>'s Huddle", once, for one friend,
 * for 7 days, with the [card] on it when it came from Send to a friend; then what joining means, in plain words, before
 * anything is sent: you see what each other plays, sharing is on for both, and only song details travel. Share link opens
 * your phone's share sheet, Copy link copies it; the invite is made at the first of them, once per ticket.
 */
@Composable
internal fun InviteTicketSheet(card: Card?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    val huddle = rememberHuddle()
    val state = rememberHuddleState() ?: return
    val me = state.name.orEmpty()
    // This ticket's own id, kept across a turn of the phone: Share and Copy hand over the one link made for it
    // (Huddle.ticketLink), which is never saved, as it opens a friendship.
    val ticket = rememberSaveable { UUID.randomUUID().toString() }
    var busy by remember { mutableStateOf(false) }
    // The chat message with the link: the card's song, album or playlist named when there is one.
    fun message(link: String): String = when (card?.kind) {
        null -> resources.getString(R.string.huddle_invite_message, link)
        CardKind.SONG -> resources.getString(R.string.huddle_invite_message_song, songBy(resources, card.songs.first()), link)
        CardKind.ALBUM -> resources.getString(R.string.huddle_invite_message_album, card.name.orEmpty(), link)
        CardKind.PLAYLIST -> resources.getString(R.string.huddle_invite_message_playlist, card.name.orEmpty(), link)
    }
    // The link, made on the server at the first ask; null (and said) when Huddle did not answer.
    fun withLink(use: suspend (String) -> Unit) {
        if (busy) return
        busy = true
        lifecycle.launch {
            try {
                val made = when (val invited = huddle.ticketLink(ticket, card)) {
                    is Invited.Link -> invited.text
                    Invited.NoName, Invited.NoAnswer -> null
                }
                if (made == null) toasts.show(Toast(resources.getString(R.string.huddle_no_answer), R.drawable.ic_error, Tone.ERROR)) else use(made)
            } finally {
                busy = false
            }
        }
    }
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 14.dp,
        padding = 16.dp,
        footer = {
            LyrifyButton(stringResource(R.string.huddle_share_link), onClick = { withLink { toasts.say(resources, shareMessage(context, message(it))) } }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_share, enabled = !busy)
            LyrifyButton(
                stringResource(R.string.huddle_copy_link),
                onClick = {
                    withLink {
                        copy(context.applicationContext, resources.getString(R.string.huddle_title), it)
                        // Android 13 and later say so themselves.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toasts.say(resources, R.string.huddle_link_copied)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                kind = ButtonKind.SECONDARY,
                icon = R.drawable.ic_content_copy,
                enabled = !busy,
            )
        },
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.huddle_invite), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.huddle_ticket_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Ticket(me, card)
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            TicketFact(R.drawable.ic_visibility, stringResource(R.string.huddle_ticket_sees), bold = true)
            TicketFact(R.drawable.ic_tune, stringResource(R.string.huddle_ticket_stop))
            TicketFact(R.drawable.ic_lock, stringResource(R.string.huddle_ticket_details))
        }
    }
}

/**
 * The ticket itself: on the ink-to-pink ground with a light sweeping over it now and then, "Huddle ticket", "A seat in
 * <name>'s Huddle", once for one friend for 7 days, the song on it; and the stub, torn along a dashed line, with your face
 * and "Admit one". Its words are read by TalkBack; the stub is decoration.
 */
@Composable
private fun Ticket(me: String, card: Card?) {
    val sheet = LocalChromeColors.current.sheet
    val shine = if (huddleMoves()) rememberInfiniteTransition(label = "ticket").animateFloat(1.3f, -0.3f, infiniteRepeatable(tween(SHINE_MS)), label = "shine") else null
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .shadow(16.dp, RoundedCornerShape(22.dp), ambientColor = HuddleTicket.first(), spotColor = HuddleTicket.first())
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(HuddleTicket))
            .drawWithContent {
                shine?.let {
                    val at = it.value * size.width
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, BrandLilac.copy(alpha = 0.22f), Color.Transparent), at - size.width * 0.3f, at + size.width * 0.3f))
                }
                drawContent()
                // The stub's notches, cut out in the sheet's colour at the dashed line's ends.
                val x = size.width - STUB.toPx()
                drawCircle(sheet, 9.dp.toPx(), Offset(x, 0f))
                drawCircle(sheet, 9.dp.toPx(), Offset(x, size.height))
            },
    ) {
        Column(Modifier.weight(1f).padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.huddle_ticket_kicker).uppercase(), style = Kicker, color = BrandLilac.copy(alpha = 0.8f))
            Text(stringResource(R.string.huddle_ticket_seat, me), style = TicketTitle, color = BrandLilac)
            Text(stringResource(R.string.huddle_ticket_once), style = MaterialTheme.typography.bodySmall, color = BrandLilac.copy(alpha = 0.88f))
            card?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp).clip(CircleShape).background(BrandLilac.copy(alpha = 0.16f)).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(BrandLilac.copy(alpha = 0.2f), CircleShape)) {
                        Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, tint = BrandLilac, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(stringResource(R.string.huddle_ticket_song), style = MaterialTheme.typography.bodySmall, color = BrandLilac.copy(alpha = 0.8f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(cardLine(it), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = BrandLilac, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (it.kind == CardKind.SONG) SongLength(it.songs.first().durationMs, color = BrandLilac.copy(alpha = 0.8f))
                        }
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            modifier = Modifier
                .width(STUB)
                .fillMaxHeight()
                .clearAndSetSemantics {}
                .drawWithContent {
                    drawLine(BrandLilac.copy(alpha = 0.5f), Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                    drawContent()
                },
        ) {
            FriendFace(me, OwnFaceColor, 40.dp)
            Text(
                stringResource(R.string.huddle_ticket_admit).uppercase(),
                style = Kicker,
                color = BrandLilac.copy(alpha = 0.85f),
                maxLines = 1,
                // Written down the stub: laid out turned, so it takes its turned size.
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE, minHeight = 0))
                        layout(placeable.height, placeable.width) { placeable.place((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2) }
                    }
                    .rotate(90f),
            )
        }
    }
}

/** One plain fact of the ticket: a coral icon and its words ([bold] for the first one, that friends see what you play). */
@Composable
private fun TicketFact(@DrawableRes icon: Int, text: String, bold: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.SemiBold else null), modifier = Modifier.weight(1f))
    }
}

/**
 * An invite opened ([link]: from another app, or pasted), the mockup's 3.2: the inviter's face and yours with a note
 * hopping between them, "<name> invites you to Huddle", the song that came with it (found in your library or not), what
 * joining means in plain words (they see what you play and you see theirs; stop sharing or listen privately any time),
 * and your name the first time. Join and play <song> joins and plays it; Join only joins; Not now keeps the link. Someone
 * else's invite used already, one expired, your own, or no answer from Huddle says so. [onPlay]: the card to open.
 */
@Composable
internal fun AcceptInviteSheet(link: String, onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    val huddle = rememberHuddle()
    val state = rememberHuddleState() ?: return
    val invite = remember(link) { huddle.parse(link) }
    if (invite == null) {
        HelpSheet(stringResource(R.string.huddle_title), stringResource(R.string.huddle_accept_bad), onDismiss = onDismiss)
        return
    }
    val matcher = rememberSongMatcher()
    var typed by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<Accepted?>(null) }
    var busy by remember { mutableStateOf(false) }
    val needsName = state.name == null
    val card = invite.card
    val found = remember(matcher, card) { card?.let { matcher?.match(it.songs.first()) } }
    val failed = answer?.let {
        when (it) {
            Accepted.Taken -> stringResource(R.string.huddle_accept_taken, invite.name)
            Accepted.Expired -> stringResource(R.string.huddle_accept_expired, invite.name)
            Accepted.Own -> stringResource(R.string.huddle_accept_own)
            else -> stringResource(R.string.huddle_no_answer)
        }
    }
    val join = { play: Boolean ->
        busy = true
        lifecycle.launch {
            if (needsName && !huddle.setName(typed)) {
                busy = false
                return@launch
            }
            when (val accepted = huddle.accept(invite)) {
                is Accepted.Joined -> {
                    onDismiss()
                    // The song first; the message shows meanwhile.
                    if (play) accepted.received?.let(onPlay)
                    lifecycle.launch { toasts.show(Toast(resources.getString(R.string.huddle_joined, accepted.friend.shownName), R.drawable.ic_group)) }
                }
                is Accepted.Already -> {
                    onDismiss()
                    lifecycle.launch { toasts.show(Toast(resources.getString(R.string.huddle_already, accepted.friend.shownName), R.drawable.ic_group)) }
                }
                else -> answer = accepted
            }
            busy = false
        }
    }
    val ready = !busy && (!needsName || Invites.cleanName(typed) != null)
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = {
            if (failed != null && answer != Accepted.NoAnswer) {
                LyrifyButton(stringResource(R.string.huddle_done), onClick = onDismiss, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
            } else {
                // A song you have plays as you join; an album or a playlist waits in Sent to you.
                if (card?.kind == CardKind.SONG && found is Match.Found) {
                    LyrifyButton(stringResource(R.string.huddle_join_play, card.songs.first().title), onClick = { join(true) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow_fill, enabled = ready)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LyrifyButton(stringResource(R.string.huddle_not_now), onClick = onDismiss, modifier = Modifier.weight(1f), kind = ButtonKind.GHOST, enabled = !busy)
                    LyrifyButton(
                        stringResource(R.string.huddle_join),
                        onClick = { join(false) },
                        modifier = Modifier.weight(1f),
                        kind = if (card?.kind == CardKind.SONG && found is Match.Found) ButtonKind.SECONDARY else ButtonKind.PRIMARY,
                        enabled = ready,
                    )
                }
            }
        },
    ) {
        Meet(invite.name)
        Text(stringResource(R.string.huddle_accept_title, invite.name), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
        Text(stringResource(R.string.huddle_accept_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        card?.let {
            val color = friendColor(invite.name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
            ) {
                FriendArt((found as? Match.Found)?.song ?: (found as? Match.Maybe)?.song, color, 56.dp, RoundedCornerShape(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.huddle_accept_sent, invite.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(cardLine(it), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (it.kind == CardKind.SONG) SongLength(it.songs.first().durationMs)
                    }
                    if (found != null && it.kind == CardKind.SONG) HaveChip(found, Modifier.padding(top = 4.dp))
                }
            }
            it.note?.let { note -> Fact(R.drawable.ic_notes, stringResource(R.string.huddle_note_quoted, note)) }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            TicketFact(R.drawable.ic_visibility, stringResource(R.string.huddle_join_sees, invite.name), bold = true)
            TicketFact(R.drawable.ic_tune, stringResource(R.string.huddle_join_stop, invite.name))
        }
        if (needsName && failed == null) {
            LyrifyField(typed, { typed = it.take(Invites.NAME_MAX) }, Modifier.fillMaxWidth(), label = stringResource(R.string.huddle_name_title))
            Text(stringResource(R.string.huddle_join_name_hint, invite.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        failed?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
    }
}

/** Two faces meeting (the join screen): the inviter's with its live ring, yours still an empty seat, a gold note hopping between. Decorative. */
@Composable
private fun Meet(inviter: String) {
    val outline = MaterialTheme.colorScheme.outline
    val hop = if (huddleMoves()) rememberInfiniteTransition(label = "meet").animateFloat(
        0f,
        0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = HOP_MS
                -1f at 0
                0f at HOP_MS / 4
                1f at HOP_MS / 2
                0f at HOP_MS * 3 / 4
                -1f at HOP_MS
            },
        ),
        label = "hop",
    ) else null
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).clearAndSetSemantics {}) {
        Canvas(Modifier.width(70.dp).height(3.dp)) {
            drawLine(outline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 6.dp.toPx())), cap = StrokeCap.Round)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(46.dp), verticalAlignment = Alignment.CenterVertically) {
            FriendFace(inviter, friendColor(inviter), 76.dp, ring = FaceRing.LIVE, gap = LocalChromeColors.current.sheet)
            SeatFace(76.dp, word = stringResource(R.string.huddle_you))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    val x = hop?.value ?: 0f
                    translationX = x * 18.dp.toPx()
                    translationY = -(1f - abs(x)) * 10.dp.toPx()
                }
                .size(30.dp)
                .background(HuddleGold, CircleShape),
        ) {
            Icon(painterResource(R.drawable.ic_music_note), contentDescription = null, tint = OnHuddleGold, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * "<name> joined your Huddle!" (the mockup's 3.3), once for each friend who joins your invite: confetti falling down
 * both sides of the card (never over its words), your faces bumping with a spark between them, that you see each other's
 * music now, Share what I play with its switch right there (on, as it is from the join), then Send <name> a song and
 * Done. A tap outside or Back is Done. Still when motion is off.
 */
@Composable
internal fun JoinedDialog(friend: Friend, onSend: () -> Unit, onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val chrome = LocalChromeColors.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    val state = rememberHuddleState()
    val name = friend.shownName
    val sharing = state?.friends?.firstOrNull { it.id == friend.id }?.sharePlays ?: friend.sharePlays
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    val title = stringResource(R.string.huddle_joined_title, name)
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlainDialogWindow()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().background(chrome.scrim).pointerTapOutside(onDone).imePadding().padding(16.dp),
        ) {
            AnimatedVisibility(visibleState = shown, enter = dialogEnter(reduced), exit = dialogExit(reduced)) {
                Box(
                    Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth()
                        .pointerTapOutside {}
                        .shadow(24.dp, RoundedCornerShape(32.dp), ambientColor = chrome.shadow, spotColor = chrome.shadow)
                        .clip(RoundedCornerShape(32.dp))
                        .background(chrome.sheet)
                        .semantics { paneTitle = title },
                ) {
                    Confetti(Modifier.matchParentSize())
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 16.dp)) {
                        JoinedFaces(state?.name.orEmpty(), name, friendColor(friend.id))
                        Text(title, style = JoinedTitle, textAlign = TextAlign.Center, color = colors.onSurface, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).semantics { heading() })
                        Text(stringResource(R.string.huddle_joined_text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.surfaceContainerHigh)
                                .toggleable(value = sharing, role = Role.Switch) { on -> scope.launch { huddle.sharePlays(friend.id, on) } }
                                .heightIn(min = 64.dp)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.huddle_share_plays), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                                Text(stringResource(if (sharing) R.string.huddle_joined_sharing_on else R.string.huddle_joined_sharing_off, name), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            LyrifySwitch(sharing)
                        }
                        LyrifyButton(stringResource(R.string.huddle_send_song_to, name), onClick = onSend, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), icon = R.drawable.ic_send)
                        LyrifyButton(stringResource(R.string.huddle_done), onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.GHOST)
                    }
                }
            }
        }
    }
}

/** A tap that lands here and on nothing nearer: [onTap] (the scrim closes the dialog; the card keeps its taps). */
private fun Modifier.pointerTapOutside(onTap: () -> Unit): Modifier = pointerInput(onTap) { detectTapGestures { onTap() } }

/** Your face and the new friend's, bumping together, with a gold spark popping between them. */
@Composable
private fun JoinedFaces(me: String, friend: String, color: Color) {
    val motion = if (huddleMoves()) rememberInfiniteTransition(label = "pair") else null
    val bump = motion?.animateFloat(
        0f,
        0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = PAIR_MS
                0f at 0
                1f at PAIR_MS * 40 / 100
                1f at PAIR_MS * 52 / 100
                0f at PAIR_MS
            },
        ),
        label = "bump",
    )
    val pop = motion?.animateFloat(
        0f,
        0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = PAIR_MS
                0f at PAIR_MS * 30 / 100
                1.2f at PAIR_MS * 46 / 100
                1f at PAIR_MS * 70 / 100
                0f at PAIR_MS
            },
        ),
        label = "spark",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.height(96.dp).clearAndSetSemantics {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FriendFace(me, OwnFaceColor, 76.dp, gap = LocalChromeColors.current.sheet, modifier = Modifier.graphicsLayer {
                val at = bump?.value ?: 1f
                translationX = (-8 + 10 * at) * density
                rotationZ = -6f * at
            })
            FriendFace(friend, color, 76.dp, gap = LocalChromeColors.current.sheet, modifier = Modifier.offset(x = (-12).dp).graphicsLayer {
                val at = bump?.value ?: 1f
                translationX = (8 - 10 * at) * density
                rotationZ = 6f * at
            })
        }
        Icon(
            painterResource(R.drawable.ic_auto_awesome_fill),
            contentDescription = null,
            tint = HuddleGold,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = (-6).dp).size(28.dp).graphicsLayer {
                val at = pop?.value ?: 1f
                scaleX = 0.4f + 0.6f * at
                scaleY = 0.4f + 0.6f * at
                alpha = at.coerceIn(0f, 1f)
            },
        )
    }
}

/** Confetti down both sides of the joined card: a few dozen bits of the friends' colours and gold, falling and turning. */
@Composable
private fun Confetti(modifier: Modifier) {
    val fall = if (huddleMoves()) rememberInfiniteTransition(label = "confetti").animateFloat(0f, 1f, infiniteRepeatable(tween(FALL_MS, easing = LinearEasing)), label = "fall") else null
    val bits = remember { FriendColors + HuddleGold }
    Canvas(modifier.clearAndSetSemantics {}) {
        repeat(CONFETTI) { at ->
            val x = size.width * if (at % 2 == 1) (86 + (at * 5) % 11) / 100f else (2 + (at * 7) % 11) / 100f
            val start = size.height * (((at * 23) % 70) + 2) / 100f
            // Each bit is a share of the fall behind the one before, so they never move as one sheet.
            val t = ((fall?.value ?: 0.5f) + (at * 0.37f) % 1f) % 1f
            val y = start + (t * 420f - 160f) * density
            val turn = (at * 47) % 180 + t * 560f
            val alpha = if (fall == null) 1f else (if (t < 0.12f) t / 0.12f else 1f - t).coerceIn(0f, 1f)
            rotate(turn, Offset(x, y)) {
                val color = bits[at % bits.size].copy(alpha = alpha)
                if (at % 3 == 0) drawCircle(color, 5.dp.toPx(), Offset(x, y)) else drawRect(color, Offset(x - 4.dp.toPx(), y - 6.5.dp.toPx()), Size(8.dp.toPx(), 13.dp.toPx()))
            }
        }
    }
}

/** The ticket's stub, torn off along its dashed line. */
private val STUB = 64.dp
private const val SHINE_MS = 3_600
private const val HOP_MS = 1_600
private const val PAIR_MS = 2_400
private const val FALL_MS = 3_400
private const val CONFETTI = 22

private val Kicker = body(11f, 16, 700).copy(letterSpacing = body(11f, 16).fontSize * 0.12f)
private val TicketTitle = display(23, 28, -0.01, weight = 700)
private val JoinedTitle = display(27, 32, -0.015, weight = 700)
