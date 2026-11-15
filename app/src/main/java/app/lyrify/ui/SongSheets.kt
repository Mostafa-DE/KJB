package app.lyrify.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.books.Media3Chapters
import app.lyrify.books.looksLikeBook
import app.lyrify.books.readsChaptersFor
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.tones.SavedTone
import app.lyrify.tones.ToneFiles
import app.lyrify.tones.TonePlayer
import java.util.Locale

// The song sheet, Add to, Song info, the selection's More and their dialogs; [SongSheetsHost] opens them.

/**
 * The song sheet: the cover, the title, the artist and the album as links, then [rows] (Play next, Add to, the heart,
 * Lyrics with its status, Make a tone, Share, Share lyrics, Song info, Edit tags and the rows of where it was opened),
 * the [bookmarks] slot, and Move to trash apart at the bottom, in the danger colour.
 */
@Composable
internal fun SongMenuSheet(
    song: Song,
    ui: LibraryUi,
    rows: List<SheetRow>,
    onArtist: (() -> Unit)?,
    onAlbum: (() -> Unit)?,
    bookmarks: (@Composable () -> Unit)?,
    onDismiss: () -> Unit,
    onRow: (SheetRow) -> Unit,
) {
    val favourite = ui.isFavourite(song)
    val status = ui.status(song)
    LyrifySheet(onDismiss = onDismiss, spacing = 14.dp, padding = 12.dp) {
        SongSheetHeader(song, ui.title(song), onArtist, onAlbum, Modifier.padding(horizontal = 8.dp))
        Column {
            rows.forEach { row ->
                when (row) {
                    SheetRow.PLAY_NEXT -> ActionRow(R.drawable.ic_queue_play_next, stringResource(R.string.lib_play_next)) { onRow(row) }
                    SheetRow.ADD_TO -> ActionRow(R.drawable.ic_playlist_add, stringResource(R.string.lib_add_to), chevron = true) { onRow(row) }
                    SheetRow.FAVOURITE -> FavouriteRow(favourite) { onRow(row) }
                    SheetRow.LYRICS -> ActionRow(R.drawable.ic_lyrics, stringResource(R.string.lib_lyrics), chevron = true, trailing = status?.let { { StatusPill(it) } }) { onRow(row) }
                    SheetRow.MAKE_TONE -> ActionRow(R.drawable.ic_ring_volume, stringResource(R.string.lib_make_tone)) { onRow(row) }
                    SheetRow.SHARE -> ActionRow(R.drawable.ic_share, stringResource(R.string.share)) { onRow(row) }
                    SheetRow.SHARE_LYRICS -> ActionRow(R.drawable.ic_share, stringResource(R.string.share_lyrics), chevron = true) { onRow(row) }
                    SheetRow.SONG_INFO -> ActionRow(R.drawable.ic_info, stringResource(R.string.lib_song_info)) { onRow(row) }
                    SheetRow.EDIT_TAGS -> ActionRow(R.drawable.ic_sell, stringResource(R.string.lib_edit_tags)) { onRow(row) }
                    SheetRow.BOOK -> {
                        val library = ui.library
                        val context = LocalContext.current
                        // An .m4a with chapters, a podcast or a long lone file: Mark as book is suggested, never done by
                        // itself (books/Books.kt). The chapters are read once the sheet is open.
                        val chaptered by produceState(false, song.path, library) {
                            if (library != null && readsChaptersFor(song, library)) value = Media3Chapters(context).read(song.path, song.durationMs)?.isNotEmpty() == true
                        }
                        val looks = remember(song, library, chaptered) { library != null && looksLikeBook(song, library, chaptered) }
                        ActionRow(
                            R.drawable.ic_auto_stories,
                            stringResource(if (library?.bookOf(song.path) != null) R.string.book_not else R.string.book_mark),
                            supporting = if (looks) stringResource(R.string.book_looks_like) else null,
                        ) { onRow(row) }
                    }
                    SheetRow.COVERS_TAGS -> ActionRow(R.drawable.ic_album, stringResource(R.string.tools_covers)) { onRow(row) }
                    SheetRow.REMOVE_FROM_QUEUE -> ActionRow(R.drawable.ic_playlist_remove, stringResource(R.string.lib_remove_from_queue)) { onRow(row) }
                    SheetRow.REMOVE_FROM_PLAYLIST -> ActionRow(R.drawable.ic_playlist_remove, stringResource(R.string.lib_remove_from_playlist)) { onRow(row) }
                    SheetRow.MOVE_TO_TRASH -> Unit
                }
            }
        }
        bookmarks?.invoke()
        if (SheetRow.MOVE_TO_TRASH in rows) {
            HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ActionRow(R.drawable.ic_delete, stringResource(R.string.lib_move_to_trash), danger = true) { onRow(SheetRow.MOVE_TO_TRASH) }
        }
    }
}

/**
 * An album's or an artist's "⋯", as the song sheet: [art], [title] and [line] at its head, then [rows] (Play next, Add
 * to, Shuffle, then the album's or the artist's own).
 */
@Composable
internal fun CollectionMenuSheet(title: String, line: String, art: @Composable () -> Unit, rows: List<CollectionRow>, onDismiss: () -> Unit, onRow: (CollectionRow) -> Unit) {
    LyrifySheet(onDismiss = onDismiss, spacing = 14.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
            art()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = SheetSongTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column {
            rows.forEach { row ->
                when (row) {
                    CollectionRow.PLAY_NEXT -> ActionRow(R.drawable.ic_queue_play_next, stringResource(R.string.lib_play_next)) { onRow(row) }
                    CollectionRow.ADD_TO -> ActionRow(R.drawable.ic_playlist_add, stringResource(R.string.lib_add_to), chevron = true) { onRow(row) }
                    CollectionRow.SHUFFLE -> ActionRow(R.drawable.ic_shuffle, stringResource(R.string.lib_shuffle)) { onRow(row) }
                    CollectionRow.GO_TO_ARTIST -> ActionRow(R.drawable.ic_person, stringResource(R.string.player_menu_artist)) { onRow(row) }
                    CollectionRow.COVERS_TAGS -> ActionRow(R.drawable.ic_album, stringResource(R.string.tools_covers)) { onRow(row) }
                    CollectionRow.EDIT_ALBUM -> ActionRow(R.drawable.ic_edit, stringResource(R.string.lib_edit_album)) { onRow(row) }
                    CollectionRow.RENAME_ARTIST -> ActionRow(R.drawable.ic_edit, stringResource(R.string.edit_rename_artist)) { onRow(row) }
                    CollectionRow.MERGE_WITH -> ActionRow(R.drawable.ic_merge, stringResource(R.string.edit_merge_with)) { onRow(row) }
                }
            }
        }
    }
}

/** The sheet's head: the cover, the title, and the artist and album as links when their pages exist, with the length. */
@Composable
private fun SongSheetHeader(song: Song, title: String, onArtist: (() -> Unit)?, onAlbum: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = modifier) {
        SongCover(SongArt(song.path, song.ref.mediaStoreId), size = 72.dp, shape = MaterialTheme.shapes.medium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = SheetSongTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            LinkText(song.artist ?: stringResource(R.string.check_no_artist), onArtist)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkText(song.album ?: stringResource(R.string.lib_unknown_album), onAlbum, Modifier.weight(1f, fill = false))
                Text(
                    stringResource(R.string.lib_after_dot, songLength(song.durationMs)),
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * An artist or album name: the prototype's link (.lnk, on-surface medium with a coral underline) when it opens a page,
 * else plain text. The line keeps its 20 dp; Compose widens a small target's touch to 48 dp.
 */
@Composable
internal fun LinkText(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    if (onClick == null) {
        Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    Text(
        text,
        style = style,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick).linkUnderline(LocalBrandColors.current.highlight),
    )
}

/** The sheet's song title (.shh .t): Bricolage 21/26. */
private val SheetSongTitle = display(21, 26, -0.01)

/** A row of a sheet: its icon, its words (and a [supporting] line under them), maybe a value and a chevron; 52 dp tall; [danger] in red. */
@Composable
private fun ActionRow(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    danger: Boolean = false,
    chevron: Boolean = false,
    iconTint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (danger) colors.error else colors.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = iconTint.takeOrElse { if (danger) colors.error else colors.onSurfaceVariant }, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = content)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        // The tail (.tail): its value and the chevron 6 dp apart.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            trailing?.invoke()
            if (chevron) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** The heart as a sheet row: a filled coral heart and "On" while it is on; TalkBack hears what a tap does. */
@Composable
private fun FavouriteRow(on: Boolean, onToggle: () -> Unit) {
    val brand = LocalBrandColors.current
    val colors = MaterialTheme.colorScheme
    val label = stringResource(heartLabel(on))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label }
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp),
    ) {
        Icon(
            painterResource(if (on) R.drawable.ic_favorite_fill else R.drawable.ic_favorite),
            contentDescription = null,
            tint = if (on) brand.highlight else colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(stringResource(R.string.lib_favourite), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // "On" with a coral check (.tick), fading in and out with the heart.
        AnimatedVisibility(on, enter = fadeIn(tween(Motion.QUICK_MS)), exit = fadeOut(tween(Motion.QUICK_MS))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = brand.highlight, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.lib_on), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            }
        }
    }
}

/**
 * Add to, the one door for one song or many. Queues first: the queue playing by name, with Play next and Add to end
 * (without one, Play next and Add to queue), then the saved and recent queues and New queue; then Favourites, then the
 * user's playlists and New playlist. A place that already holds every song is ticked.
 */
@Composable
internal fun AddToSheet(songs: List<SongRef>, title: String, ui: LibraryUi, onDismiss: () -> Unit, onTarget: (AddTarget) -> Unit) {
    val targets = remember(ui.queues, ui.playlists.playlists) { addTargets(ui.queues, ui.playlists.playlists) }
    val playing = targets.firstNotNullOfOrNull { (it as? AddTarget.AddToQueue)?.playing }
    val paths = remember(songs) { songs.mapTo(HashSet()) { it.path } }
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    LyrifySheet(onDismiss = onDismiss, spacing = 4.dp, padding = 12.dp) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.lib_add_to_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SectionTitle(stringResource(R.string.lib_queues), Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
        playing?.let { queue ->
            NavRow(
                title = queue.name,
                sub = pluralStringResource(R.plurals.lib_queue_playing, queue.songs, queue.songs),
                lead = { Tile(R.drawable.ic_graphic_eq, brand.highlightContainer, brand.highlight) },
                trailing = null,
            ) { onTarget(AddTarget.AddToQueue(queue)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            LyrifyButton(stringResource(R.string.lib_play_next), { onTarget(AddTarget.PlayNext) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY)
            LyrifyButton(
                stringResource(if (playing != null) R.string.lib_add_to_end else R.string.lib_add_to_queue),
                { onTarget(AddTarget.AddToQueue(playing)) },
                Modifier.weight(1f),
                kind = ButtonKind.SECONDARY,
            )
        }
        var heading: Int? = R.string.lib_queues
        targets.forEach { target ->
            val section = when (target) {
                AddTarget.PlayNext, is AddTarget.AddToQueue -> null
                is AddTarget.Queue, AddTarget.NewQueue -> R.string.lib_queues
                AddTarget.Favourites -> R.string.lib_favourites
                is AddTarget.ToPlaylist, AddTarget.NewPlaylist -> R.string.lib_your_playlists
            }
            if (section != null && section != heading) {
                heading = section
                SectionTitle(stringResource(section), Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
            }
            when (target) {
                AddTarget.PlayNext, is AddTarget.AddToQueue -> Unit
                is AddTarget.Queue -> NavRow(
                    title = target.queue.name,
                    sub = pluralStringResource(if (target.queue.saved) R.plurals.lib_queue_saved else R.plurals.lib_queue_recent, target.queue.songs, target.queue.songs),
                    lead = { Tile(R.drawable.ic_queue_music) },
                    trailing = null,
                ) { onTarget(target) }
                AddTarget.NewQueue -> NewRow(stringResource(R.string.lib_new_queue)) { onTarget(target) }
                AddTarget.Favourites -> {
                    val favourites = ui.playlists.favouriteSongs
                    val all = favourites.mapTo(HashSet()) { it.path }.containsAll(paths)
                    NavRow(
                        title = stringResource(R.string.lib_favourites),
                        sub = pluralStringResource(R.plurals.lib_songs, favourites.size, favourites.size),
                        lead = { Tile(R.drawable.ic_favorite_fill, brand.highlightContainer, brand.highlight) },
                        trailing = if (all) ({ AddedTick() }) else null,
                    ) { onTarget(target) }
                }
                is AddTarget.ToPlaylist -> {
                    val all = target.playlist.songs.mapTo(HashSet()) { it.path }.containsAll(paths)
                    NavRow(
                        title = target.playlist.name,
                        sub = pluralStringResource(R.plurals.lib_songs, target.playlist.songs.size, target.playlist.songs.size),
                        lead = { PlaylistTile(target.playlist) },
                        trailing = if (all) ({ AddedTick() }) else null,
                    ) { onTarget(target) }
                }
                AddTarget.NewPlaylist -> NewRow(stringResource(R.string.lib_new_playlist)) { onTarget(target) }
            }
        }
    }
}

/** "Added", on a place that holds every song already. */
@Composable
private fun AddedTick() {
    val brand = LocalBrandColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(end = 8.dp)) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = brand.highlight, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.lib_added), style = MaterialTheme.typography.labelLarge, color = brand.highlight)
    }
}

/** Song info: its tags, length, file, format, plays and last play (KJB's own player's, as Your listening says them), when it was added, and its lyrics. */
@Composable
internal fun SongInfoSheet(song: Song, ui: LibraryUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val plays = songPlaysInfo(song.ref)
    val format = listOfNotNull(
        song.fileName.substringAfterLast('.', "").ifEmpty { song.mimeType?.substringAfterLast('/').orEmpty() }.uppercase(Locale.ROOT).ifEmpty { null },
        song.durationMs.takeIf { it > 0 }?.let { stringResource(R.string.lib_kbps, song.sizeBytes * BITS_PER_BYTE / it) },
        Formatter.formatShortFileSize(context, song.sizeBytes),
    ).joinToString(" · ")
    LyrifySheet(onDismiss = onDismiss, spacing = 0.dp) {
        Text(stringResource(R.string.lib_song_info), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
        InfoRow(R.drawable.ic_music_note, R.string.lib_info_title, song.title)
        InfoRow(R.drawable.ic_person, R.string.lib_info_artist, song.artist ?: stringResource(R.string.check_no_artist))
        InfoRow(R.drawable.ic_album, R.string.lib_info_album, listOfNotNull(song.album ?: stringResource(R.string.lib_unknown_album), song.year?.toString()).joinToString(" · "))
        song.albumArtist?.let { InfoRow(R.drawable.ic_person, R.string.lib_info_album_artist, it) }
        song.genre?.let { InfoRow(R.drawable.ic_category, R.string.lib_info_genre, it) }
        song.track?.let { InfoRow(R.drawable.ic_queue_music, R.string.lib_info_track, listOfNotNull(it.toString(), song.disc?.let { disc -> stringResource(R.string.lib_disc, disc) }).joinToString(" · ")) }
        InfoRow(R.drawable.ic_schedule, R.string.lib_info_length, songLength(song.durationMs))
        InfoRow(R.drawable.ic_folder_open, R.string.lib_info_file, song.folder + song.fileName, middleEllipsis = true)
        InfoRow(R.drawable.ic_graphic_eq, R.string.lib_info_format, format)
        plays?.let {
            InfoRow(R.drawable.ic_bar_chart, R.string.lib_info_plays, it.plays)
            InfoRow(R.drawable.ic_history, R.string.lib_info_last_played, it.lastPlayed)
        }
        song.addedAt?.let { InfoRow(R.drawable.ic_download, R.string.lib_info_added, dateText(it)) }
        InfoRow(R.drawable.ic_lyrics, R.string.lib_lyrics, ui.status(song)?.let { stringResource(it.style.label) } ?: stringResource(R.string.lib_not_looked_up))
    }
}

/** One fact of Song info: its icon, what it is, and its value; one TalkBack stop. */
@Composable
private fun InfoRow(@DrawableRes icon: Int, @StringRes label: Int, value: String, middleEllipsis: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.heightIn(min = 52.dp).padding(vertical = 4.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (middleEllipsis) 1 else 2,
                overflow = if (middleEllipsis) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            )
        }
    }
}

/** The selection's More: Song info (one song only), Edit tags and Move to trash. */
@Composable
internal fun SelectionMoreSheet(count: Int, onInfo: (() -> Unit)?, onEditTags: () -> Unit, onTrash: () -> Unit, onDismiss: () -> Unit) {
    LyrifySheet(onDismiss = onDismiss, spacing = 8.dp, padding = 12.dp) {
        Text(
            pluralStringResource(R.plurals.lib_selected, count, count),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
        )
        Column {
            onInfo?.let { ActionRow(R.drawable.ic_info, stringResource(R.string.lib_song_info), onClick = it) }
            ActionRow(R.drawable.ic_sell, stringResource(R.string.lib_edit_tags), onClick = onEditTags)
            ActionRow(R.drawable.ic_delete, stringResource(R.string.lib_move_to_trash), danger = true, onClick = onTrash)
        }
    }
}

/** A name for something new or renamed: a field that starts at [start] ([NameField]); Create (or [confirm]) waits for a name. */
@Composable
internal fun NameDialog(
    title: String,
    start: String,
    @DrawableRes icon: Int,
    onDismiss: () -> Unit,
    @StringRes confirm: Int = R.string.lib_create,
    onName: (String) -> Unit,
) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(nameField(start)) }
    ConfirmDialog(
        title = title,
        confirm = stringResource(confirm),
        dismiss = stringResource(R.string.remove_cancel),
        onConfirm = { onName(name.text.trim()) },
        onDismiss = onDismiss,
        icon = icon,
        destructive = false,
        confirmEnabled = name.text.isNotBlank(),
    ) {
        NameField(name, { name = it.copy(text = it.text.take(MAX_NAME)) }, stringResource(R.string.lib_name))
    }
}

/**
 * A dialog's name field: focused with the keyboard up when the dialog opens, not again after a turn of the phone
 * ([FocusOnOpen]), so a name is typed at once; a rename's text starts selected ([nameField]).
 */
@Composable
internal fun NameField(name: TextFieldValue, onName: (TextFieldValue) -> Unit, label: String) {
    val focus = remember { FocusRequester() }
    FocusOnOpen(focus, opens = 1)
    LyrifyField(name, onName, Modifier.fillMaxWidth().focusRequester(focus), label = label)
}

/** A name field's start: [start] all selected, so typing replaces it; empty for a new name. */
fun nameField(start: String): TextFieldValue = TextFieldValue(start, TextRange(0, start.length))

/** KJB's own "Move 2 songs to trash?", before the phone's. */
@Composable
internal fun SongTrashDialog(count: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = pluralStringResource(R.plurals.dupes_trash_title, count, count),
        text = stringResource(R.string.lib_trash_text),
        confirm = stringResource(R.string.dupes_trash_confirm),
        dismiss = stringResource(R.string.remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onCancel,
    )
}

/**
 * Today's Tone editor with a library song. The editor reads only the song's path, title and length, so a song KJB has
 * not looked up yet gets an entry made for it here, never stored.
 */
@Composable
internal fun ToneEditorFor(song: Song, entry: LedgerEntry?, onSaved: (SavedTone) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { ToneFiles.of(context.applicationContext) }
    val player = remember(context, scope) { TonePlayer(context, scope) }
    DisposableEffect(player) { onDispose { player.stop() } }
    val tone = entry ?: LedgerEntry(path = song.path, title = song.title, artist = song.artist, album = song.album, durationMs = song.durationMs, status = LyricsStatus.NOT_FOUND, updatedAt = 0)
    ToneEditor(song = tone, files = files, player = player, onSaved = onSaved, onClose = onClose)
}

private const val BITS_PER_BYTE: Long = 8

/** The longest playlist or queue name. */
private const val MAX_NAME = 80
