package app.lyrify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.library.Folder
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.hiddenSongs
import app.lyrify.library.hiding
import app.lyrify.library.skipping
import app.lyrify.library.unhiding
import app.lyrify.settings.SettingsStore
import kotlinx.coroutines.launch
import java.text.Collator

// What leaves the library but stays on the phone (features 2026-11-15, item 6): Hide in the song sheet and the selection's
// More (SongActions.kt), Hide folder on the Folders tab, Settings > Folders to skip and Settings > Hidden songs with
// Unhide. All stored in settings.json, which the backup carries; each Undo takes back only what its own change did, so
// songs hidden before, or since while the toast was up, stay hidden.

/** "Hide": [songs] leave every list, search, shuffle and stats view; their files and plays stay. Undo shows again only those this hid. */
suspend fun SongActions.hide(songs: List<SongRef>, settings: SettingsStore): Outcome {
    val asked = songs.distinctBy { it.path }
    var hid = emptyList<SongRef>()
    val stored = settings.update { now ->
        val before = now.library.hiddenSongs.mapTo(HashSet()) { it.path }
        hid = asked.filter { it.path !in before }
        now.copy(library = now.library.hiding(asked))
    }
    if (!stored) return Outcome(Said.NotSaved)
    return Outcome(Said.Hidden(asked.size)) { settings.update { it.copy(library = it.library.unhiding(hid)) } }
}

/** "Unhide" in Settings > Hidden songs: the [songs] as Hidden songs keeps them are back in the library. Undo hides them again. */
suspend fun SongActions.unhide(songs: List<SongRef>, settings: SettingsStore): Outcome {
    val asked = songs.distinctBy { it.path }
    var shown = emptyList<SongRef>()
    val stored = settings.update { now ->
        val kept = asked.mapTo(HashSet()) { it.path }
        shown = now.library.hiddenSongs.filter { it.path in kept }
        now.copy(library = now.library.unhiding(asked))
    }
    if (!stored) return Outcome(Said.NotSaved)
    return Outcome(Said.Unhidden(asked.size)) { settings.update { it.copy(library = it.library.hiding(shown)) } }
}

/** "Hide folder": the folder at [path] (a [app.lyrify.library.Folder.path]) joins Folders to skip. Undo takes it out again. */
suspend fun SongActions.hideFolder(path: String, settings: SettingsStore): Outcome {
    val folder = path.trim('/')
    var added = false
    val stored = settings.update { now ->
        val skipping = now.library.skipping(folder)
        added = skipping !== now.library
        now.copy(library = skipping)
    }
    if (!stored) return Outcome(Said.NotSaved)
    return Outcome(Said.FolderHidden) {
        !added || settings.update { now ->
            now.copy(library = now.library.copy(foldersToSkip = now.library.foldersToSkip.filterNot { it.trim('/').equals(folder, ignoreCase = true) }))
        }
    }
}

/** The app's settings store, for the actions that change settings (Hide, Hide folder, Unhide). */
@Composable
fun rememberSettingsStore(): SettingsStore {
    val context = LocalContext.current
    return remember(context) { Lyrify.settings(context) }
}

/**
 * Settings > Folders to skip: every folder of the Folders tree for every song on the phone (skipped ones too, so they
 * can come back), and any other folder skipped, each with a switch ([skipChoices]). Songs in a skipped folder or below
 * it stay on the phone but leave the library.
 */
@Composable
fun FoldersToSkipPage() {
    val nav = LocalNavigator.current
    val ui = rememberLibraryUi() ?: return
    val change = rememberSettingsChange()
    val skipped = ui.settings.library.foldersToSkip
    val folders = remember(ui.library, skipped) { skipChoices(ui.library?.all.orEmpty(), skipped) }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.set_skip), under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "sentence", contentType = "sentence") {
                Text(
                    stringResource(R.string.lib_skip_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp),
                )
            }
            item(key = "folders", contentType = "group") {
                GroupedRows(rowMotion(reduced)) {
                    folders.forEachIndexed { at, (folder, count) ->
                        val on = skipped.any { it.trim('/').equals(folder, ignoreCase = true) }
                        GroupedRow(
                            if (on) R.drawable.ic_folder_off else R.drawable.ic_folder_open,
                            folder,
                            tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                            first = at == 0,
                            supporting = { Text(pluralStringResource(R.plurals.lib_songs, count, count)) },
                            modifier = Modifier.toggleable(on, role = Role.Switch) { skip ->
                                change { current ->
                                    val others = current.library.foldersToSkip.filterNot { it.trim('/').equals(folder, ignoreCase = true) }
                                    current.copy(library = current.library.copy(foldersToSkip = if (skip) others + folder else others))
                                }
                            },
                            trailing = { LyrifySwitch(on) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The folders "Folders to skip" offers, each with how many of [all] songs are in it or below: every folder of the
 * Folders tree, by path, skipped ones too (so they can come back), and any other folder [skipped].
 */
internal fun skipChoices(all: List<Song>, skipped: List<String>): List<Pair<String, Int>> {
    // A song counts in its folder and in each folder above it.
    val counts = all.flatMap { song ->
        val names = song.folder.split('/').filter(String::isNotEmpty)
        names.indices.map { last -> names.subList(0, last + 1).joinToString("/") }
    }.groupingBy { it }.eachCount()
    return (counts.keys + skipped.map { it.trim('/') }).distinctBy { it.lowercase() }.sortedWith(compareBy(Collator.getInstance()) { it }).map { it to (counts[it] ?: 0) }
}

/**
 * Settings > Hidden songs: the songs hidden from the song sheet, the latest hidden first, each with Unhide, which brings it
 * back to every list at once (with Undo). Songs gone from the phone are not listed.
 */
@Composable
fun HiddenSongsPage() {
    val nav = LocalNavigator.current
    val ui = rememberLibraryUi() ?: return
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    val settings = rememberSettingsStore()
    val hidden = ui.settings.library.hiddenSongs
    val songs = remember(ui.library, hidden) { ui.library?.let { hiddenSongs(it, hidden) }.orEmpty() }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.set_hidden), under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "sentence", contentType = "sentence") {
                Text(
                    stringResource(R.string.lib_hidden_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp),
                )
            }
            if (ui.library != null && songs.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_visibility), stringResource(R.string.lib_hidden_empty_title), rowMotion(reduced), text = stringResource(R.string.lib_hidden_empty))
                }
            }
            items(songs, key = { "hidden ${it.first.path}" }, contentType = { "row" }) { (kept, song) ->
                HiddenRow(song, ui.title(song), rowMotion(reduced)) {
                    scope.launch { toasts.say(resources, actions.unhide(listOf(kept), settings)) }
                }
            }
        }
    }
}

/**
 * A hidden song: its cover, title and [line] (its artist) ended by its length, and Unhide, which TalkBack hears with the
 * song's title. Songs held out of suggestions show the same way, with their own [action] (Allow again) and its [words];
 * songs left out of Insights with a [switch] in the button's place, the row one stop that switches it ([onClick]).
 */
@Composable
internal fun HiddenRow(
    song: Song,
    title: String,
    modifier: Modifier,
    line: String? = song.artist,
    action: String = stringResource(R.string.lib_unhide),
    words: String = stringResource(R.string.lib_unhide_song, title),
    switch: Boolean? = null,
    onClick: () -> Unit,
) {
    val unhide = words
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .then(if (switch != null) Modifier.toggleable(switch, role = Role.Switch) { onClick() } else Modifier)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        SongCover(SongArt(song.path, song.ref.mediaStoreId))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                SongLength(song.durationMs)
            }
        }
        if (switch != null) {
            LyrifySwitch(switch)
        } else {
            Box(Modifier.semantics { contentDescription = unhide }) {
                LyrifyButton(action, onClick = onClick, kind = ButtonKind.GHOST, compact = true, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/**
 * A folder row's "⋯" on the Folders tab and a folder page: Hide folder, which adds it to Folders to skip (with Undo);
 * [onHidden] after it is stored (a folder page goes back, its folder gone from the library). [more]: rows above it, given
 * what shuts the menu (a folder page's Mark as book).
 */
@Composable
fun FolderMenu(folder: Folder, onHidden: () -> Unit = {}, more: @Composable (close: () -> Unit) -> Unit = {}) {
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    // The toast outlives a folder page Hide folder leaves: it runs in the activity's scope, not the page's.
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val actions = rememberSongActions()
    val settings = rememberSettingsStore()
    var open by remember { mutableStateOf(false) }
    Box {
        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.lib_more_folder), { open = true }, glass = false)
        LyrifyMenu(expanded = open, onDismiss = { open = false }) {
            more { open = false }
            MenuRow(stringResource(R.string.lib_hide_folder), R.drawable.ic_visibility_off, onClick = {
                open = false
                scope.launch {
                    val outcome = actions.hideFolder(folder.path, settings)
                    if (outcome.said == Said.FolderHidden) onHidden()
                    toasts.say(resources, outcome)
                }
            })
        }
    }
}
