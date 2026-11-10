package app.lyrify.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.lyrics.TrackNames
import app.lyrify.tags.CreditSplit
import app.lyrify.tags.EditKind
import app.lyrify.tags.NameKind
import app.lyrify.tags.NamePair
import app.lyrify.tags.SameNames
import app.lyrify.tags.SongPlan
import app.lyrify.tags.TagEdits
import app.lyrify.tags.TagsGraph
import app.lyrify.tags.wroteAny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Rename artist, Merge, and "2 may be the same" on the Artists and Albums tabs (milestone T3). KJB never merges by
// itself: a merge is always the user's, through the same confirmation, writer and History as Edit tags.

/** An artist page's edit, opened by its "⋯" rows and drawn by [ArtistEditHost] on that page. */
private sealed interface ArtistEdit {
    val name: String

    data class Rename(override val name: String) : ArtistEdit

    data class Merge(override val name: String) : ArtistEdit
}

/** The menu leaves the screen as its row is tapped, so what the row opens is kept here for the page to draw. */
private var artistEdit: ArtistEdit? by mutableStateOf(null)

/** "Rename artist" in an artist page's "⋯": [ArtistEditHost] on that page draws its dialog. */
internal fun openRename(name: String) {
    artistEdit = ArtistEdit.Rename(name)
}

/** "Merge with…" in an artist page's "⋯": [ArtistEditHost] on that page draws its sheet. */
internal fun openMerge(name: String) {
    artistEdit = ArtistEdit.Merge(name)
}

/** On artist [name]'s page: the rename dialog or the merge sheet its "⋯" opened. */
@Composable
internal fun ArtistEditHost(library: Library, name: String) {
    when (val edit = artistEdit?.takeIf { it.name == name }) {
        is ArtistEdit.Rename -> RenameArtist(library, name) { artistEdit = null }
        is ArtistEdit.Merge -> MergeSheet(NameKind.ARTIST, name, null, library) { artistEdit = null }
        null -> Unit
    }
}

/**
 * Rename artist: a new name; then every song it changes, the artist's own and those that only credit it ("Also
 * credited on"); then one confirmation, and [name] becomes the new name in the artist and album artist of each. The
 * page follows the artist to the new name.
 */
@Composable
private fun RenameArtist(library: Library, name: String, onDone: () -> Unit) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    val plans = remember(library, to) { to?.let { TagEdits.rename(library.songs, listOf(name), it) }.orEmpty() }
    val chosen = to
    if (chosen == null) {
        NameDialog(stringResource(R.string.edit_rename_artist), name, R.drawable.ic_edit, onDismiss = onDone, confirm = R.string.edit_next) { typed ->
            if (TagEdits.rename(library.songs, listOf(name), typed).isEmpty()) {
                onDone()
                toasts.later(Toast(resources.getString(R.string.edit_nothing_changed), R.drawable.ic_person))
            } else {
                to = typed
            }
        }
        return
    }
    var asking by remember { mutableStateOf(false) }
    LyrifySheet(
        onDismiss = onDone,
        spacing = 12.dp,
        footer = {
            LyrifyButton(pluralStringResource(R.plurals.edit_rename_songs, plans.size, plans.size), onClick = { asking = true }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_edit)
        },
    ) {
        Text(stringResource(R.string.edit_rename_artist), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Sentence(stringResource(R.string.edit_rename_preview, name, chosen))
        NameChanges(plans, listOf(name))
    }
    if (asking) {
        ConfirmWrite(plans.size, stringResource(R.string.edit_confirm_rename, name, chosen), onDismiss = { asking = false }) {
            asking = false
            onDone()
            val label = resources.getString(R.string.edit_label_rename, name, chosen)
            startWrite(context, toasts, resources, plans, EditKind.ARTIST, label, resources.getString(R.string.edit_renamed)) { result ->
                // Only once a song has the new name: a failed rename never opens another artist who already had it.
                if (result.wroteAny() && awaitLibrary(context) { it.artist(chosen) != null }) nav.replaceTop(Page.Artist(name), Page.Artist(chosen))
            }
        }
    }
}

/**
 * "Changes 12 songs" and every song a rename or a merge changes: first the songs of the names themselves, then, under
 * "Also credited on", those that only credit one of [names] among other artists, which their pages do not list. An
 * album merge ([names] null) has no credits.
 */
@Composable
private fun NameChanges(plans: List<SongPlan>, names: Collection<String>?) {
    val resources = LocalResources.current
    Text(
        if (plans.isEmpty()) stringResource(R.string.edit_nothing_yet) else pluralStringResource(R.plurals.edit_changes, plans.size, plans.size),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    if (plans.isEmpty()) return
    val split = remember(plans, names) { names?.let { TagEdits.credits(plans, it) } ?: CreditSplit(plans, emptyList()) }
    if (split.own.isNotEmpty()) ChangeList(split.own.flatMap { mergeLines(it, resources) })
    if (split.credited.isNotEmpty()) {
        Text(
            stringResource(R.string.edit_also_credited),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
        ChangeList(split.credited.flatMap { mergeLines(it, resources) })
    }
}

/** An artist or an album as the merge sheet and the suggestions show it. */
private class Named(val id: String, val name: String, val songs: List<Song>, val artist: Artist? = null, val by: String? = null)

private fun Artist.named() = Named(name, name, songs, artist = this)

private fun Album.named() = Named(key, title, songs, by = artist)

/** [kind]'s names in [library], by id: artist names, or album keys. */
private fun names(kind: NameKind, library: Library): Map<String, Named> = when (kind) {
    NameKind.ARTIST -> library.artists.associate { it.name to it.named() }
    NameKind.ALBUM -> library.albums.associate { it.key to it.named() }
}

/** Which name a merge keeps. */
private enum class Keep { FIRST, SECOND, ANOTHER }

/**
 * Merge two artists, or two albums by the same album artist: [first] with [second], or with one picked from a list of
 * the others (similar spellings first). Then which name to keep (either one, or another), what changes, one
 * confirmation, and the write: one History entry, undone as a whole.
 */
@Composable
private fun MergeSheet(kind: NameKind, first: String, second: String?, library: Library, onDone: () -> Unit) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val all = remember(kind, library) { names(kind, library) }
    val a = all[first]
    if (a == null) {
        // Gone from the library meanwhile.
        LaunchedEffect(Unit) { onDone() }
        return
    }
    var other by rememberSaveable { mutableStateOf(second) }
    var keep by rememberSaveable { mutableStateOf<Keep?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val b = other?.let(all::get)
    val kept = keep ?: if (b != null && b.songs.size > a.songs.size) Keep.SECOND else Keep.FIRST
    val keepName = when (kept) {
        Keep.FIRST -> a.name
        Keep.SECOND -> b?.name.orEmpty()
        Keep.ANOTHER -> typed.trim()
    }
    val plans = remember(a, b, keepName) {
        when {
            b == null || keepName.isEmpty() -> emptyList()
            kind == NameKind.ARTIST -> TagEdits.rename(library.songs, listOf(a.name, b.name), keepName)
            else -> TagEdits.mergeAlbums(listOfNotNull(library.album(a.id), library.album(b.id)), keepName)
        }
    }
    LyrifySheet(
        onDismiss = onDone,
        spacing = 12.dp,
        footer = if (b == null) {
            null
        } else {
            {
                LyrifyButton(
                    if (plans.isEmpty()) stringResource(R.string.edit_merge) else pluralStringResource(R.plurals.edit_merge_songs, plans.size, plans.size),
                    onClick = { asking = true },
                    modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.ic_merge,
                    enabled = plans.isNotEmpty(),
                )
            }
        },
    ) {
        Text(stringResource(if (kind == NameKind.ARTIST) R.string.edit_merge_artists else R.string.edit_merge_albums), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (b == null) {
            Sentence(stringResource(if (kind == NameKind.ARTIST) R.string.edit_pick_artist else R.string.edit_pick_album, a.name))
            val others = remember(all, a) { all.values.filter { it.id != a.id && (kind == NameKind.ARTIST || it.by == a.by) } }
            if (others.isEmpty()) {
                Sentence(stringResource(R.string.edit_no_others))
            } else {
                if (others.size > MAX_LISTED) LyrifySearchField(filter, { filter = it }, stringResource(R.string.edit_find_name), Modifier.fillMaxWidth())
                // Ranked away from the main thread: a library may hold thousands of artists.
                val listed by produceState(emptyList<Pair<Named, Boolean>>(), others, filter) { value = withContext(Dispatchers.Default) { mergeList(a, others, filter) } }
                Column {
                    listed.forEach { (named, similar) -> OtherRow(named, similar) { other = named.id } }
                }
            }
        } else {
            Sentence(stringResource(if (kind == NameKind.ARTIST) R.string.edit_keep_artist else R.string.edit_keep_album))
            Column(Modifier.selectableGroup()) {
                KeepRow(a.name, songCount(a), kept == Keep.FIRST) { keep = Keep.FIRST }
                KeepRow(b.name, songCount(b), kept == Keep.SECOND) { keep = Keep.SECOND }
                KeepRow(stringResource(R.string.edit_another_name), null, kept == Keep.ANOTHER) { keep = Keep.ANOTHER }
            }
            if (kept == Keep.ANOTHER) LyrifyField(typed, { typed = it.take(MAX_NAME) }, label = stringResource(R.string.edit_new_name), modifier = Modifier.fillMaxWidth())
            NameChanges(plans, if (kind == NameKind.ARTIST) listOf(a.name, b.name) else null)
        }
    }
    if (asking && b != null) {
        val text = if (kind == NameKind.ARTIST) R.string.edit_confirm_merge_artists else R.string.edit_confirm_merge_albums
        val from = listOf(a.name, b.name).filter { it != keepName }.joinToString("”, “")
        ConfirmWrite(plans.size, stringResource(text, from, keepName), onDismiss = { asking = false }) {
            asking = false
            onDone()
            val label = resources.getString(R.string.edit_label_merge, a.name, b.name)
            startWrite(context, toasts, resources, plans, EditKind.MERGE, label, resources.getString(R.string.edit_merged, keepName)) { result ->
                // Merged from an artist's page: the page follows to the name kept, once a song has it.
                if (kind == NameKind.ARTIST && keepName != a.name && result.wroteAny() && awaitLibrary(context) { it.artist(keepName) != null }) {
                    nav.replaceTop(Page.Artist(a.name), Page.Artist(keepName))
                }
            }
        }
    }
}

/** The others to merge with: those that look like [a] first, then the rest; with [filter], only names holding it. */
private fun mergeList(a: Named, others: List<Named>, filter: String): List<Pair<Named, Boolean>> {
    val words = TrackNames.normalize(filter)
    val byName = others.groupBy { it.name }
    val ranked = SameNames.similarFirst(a.name, byName.keys.toList()).flatMap { byName.getValue(it) }
    val (similar, rest) = ranked.partition { SameNames.lookAlike(a.name, it.name) }
    val shown = rest.filter { words.isEmpty() || TrackNames.normalize(it.name).contains(words) }
    return similar.map { it to true } + shown.take(MAX_LISTED).map { it to false }
}

/** A song's lines in the merge's preview: one per field it changes ("Porch Wire · Album artist"), old → new. */
private fun mergeLines(plan: SongPlan, resources: Resources): List<ChangeLine> = plan.changes.map { change ->
    ChangeLine(resources.getString(R.string.edit_two_parts, plan.song.title, resources.getString(change.field.label)), change.old, change.new)
}

@Composable
private fun songCount(named: Named): String = pluralStringResource(R.plurals.lib_songs, named.songs.size, named.songs.size)

/** One of the others to merge with: its image, name and songs, "Similar" when it looks alike. */
@Composable
private fun OtherRow(named: Named, similar: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .selectable(false, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        NamedImage(named, 40.dp)
        Text(
            stringResource(R.string.edit_two_parts, named.name, songCount(named)),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (similar) StatusPill(stringResource(R.string.edit_similar), Tone.CHECK)
    }
}

/** A choice of the name to keep: the chosen one on the brand's container with a check. */
@Composable
private fun KeepRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val content = if (selected) brand.onHighlightContainer else colors.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) brand.highlightContainer else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = content)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (selected) content else colors.onSurfaceVariant)
        }
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

/** An artist's round image, or an album's cover. */
@Composable
private fun NamedImage(named: Named, size: Dp) {
    val artist = named.artist
    if (artist != null) {
        ArtistAvatar(artist, size)
    } else {
        SongCover(named.songs.firstOrNull()?.let { SongArt(it.path, it.ref.mediaStoreId) }, size = size, shape = RoundedCornerShape(12.dp))
    }
}

// "2 may be the same"

/**
 * The quiet line on the Artists or Albums tab when names there look like spellings of one name: "2 may be the same",
 * which opens the suggestions. Nothing shows when none do. Compared away from the main thread.
 */
@Composable
internal fun SameNamesLine(kind: NameKind, library: Library, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { TagsGraph.keptApart(context) }
    LaunchedEffect(store) { store.load() }
    val apart by store.pairs.collectAsStateWithLifecycle()
    val pairs by produceState(emptyList<Pair<Named, Named>>(), kind, library, apart) {
        value = withContext(Dispatchers.Default) {
            when (kind) {
                NameKind.ARTIST -> SameNames.artists(library.artists, apart).map { (a, b) -> a.named() to b.named() }
                NameKind.ALBUM -> SameNames.albums(library.albums, apart).map { (a, b) -> a.named() to b.named() }
            }
        }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    var merging by remember { mutableStateOf<Pair<String, String>?>(null) }
    if (pairs.isNotEmpty()) {
        val count = pairs.flatMap { listOf(it.first.id, it.second.id) }.distinct().size
        val highlight = LocalBrandColors.current.highlight
        LyrifyCard(modifier.fillMaxWidth(), onClick = { open = true }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_merge), contentDescription = null, tint = highlight, modifier = Modifier.size(20.dp))
                Text(pluralStringResource(R.plurals.edit_may_be_same, count, count), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (open) {
        SuggestSheet(kind, pairs, onDismiss = { open = false }) { a, b ->
            open = false
            merging = a to b
        }
    }
    merging?.let { (a, b) -> MergeSheet(kind, a, b, library) { merging = null } }
}

/** "May be the same": each pair with "Not the same" (kept apart from now on) and "Merge…". */
@Composable
private fun SuggestSheet(kind: NameKind, pairs: List<Pair<Named, Named>>, onDismiss: () -> Unit, onMerge: (String, String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(pairs.isEmpty()) { if (pairs.isEmpty()) onDismiss() }
    LyrifySheet(onDismiss = onDismiss, spacing = 12.dp) {
        Text(stringResource(R.string.edit_suggest_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Sentence(stringResource(if (kind == NameKind.ARTIST) R.string.edit_suggest_artists else R.string.edit_suggest_albums))
        if (pairs.isEmpty()) Sentence(stringResource(R.string.edit_nothing_alike))
        pairs.forEach { (a, b) ->
            LyrifyCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(a, b).forEach { named ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NamedImage(named, 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(named.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val line = songCount(named).let { songs -> named.by?.let { stringResource(R.string.edit_two_parts, songs, it) } ?: songs }
                                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LyrifyButton(
                            stringResource(R.string.edit_not_same),
                            onClick = {
                                scope.launch { TagsGraph.keptApart(context).add(NamePair.of(kind, a.id, b.id)) }
                                toasts.later(Toast(resources.getString(R.string.edit_kept_apart), R.drawable.ic_check))
                            },
                            modifier = Modifier.weight(1f),
                            kind = ButtonKind.SECONDARY,
                            compact = true,
                        )
                        LyrifyButton(stringResource(R.string.edit_merge_ellipsis), onClick = { onMerge(a.id, b.id) }, modifier = Modifier.weight(1f), icon = R.drawable.ic_merge, compact = true)
                    }
                }
            }
        }
    }
}

private const val MAX_NAME = 120
private const val MAX_LISTED = 50
