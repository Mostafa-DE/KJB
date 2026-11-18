package app.lyrify.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.settings.DockItemSetting
import app.lyrify.settings.DockStyle
import app.lyrify.settings.DockTab
import app.lyrify.settings.Settings

/**
 * Settings > Display > Bottom bar (KJB 3.1, user 2026-11-18): its look, Standard or Slim; which items it holds and in
 * what order, up to six, Library always, Downloads and Books offered only while the phone has them ([LocalDockBar]), each
 * moved with arrows and switched on or off as Settings > Library tabs does; and Hide while scrolling. An item the phone
 * can't offer now is not listed and keeps its place and switch for when it can.
 */
@Composable
fun BottomBarScreen(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val offered = LocalDockBar.current::offers
    val display = settings.display
    val listed = display.dock.filter { offered(it.tab) }
    val room = dockHasRoom(display.dock, offered)
    fun setDock(change: (List<DockItemSetting>) -> List<DockItemSetting>) = onChange { it.copy(display = it.display.copy(dock = change(it.display.dock))) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LyrifyTopBar(
                title = { Text(stringResource(R.string.set_bar), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(contentType = "look") {
                Column {
                    BarSectionTitle(R.string.set_bar_look, first = true)
                    Column(Modifier.padding(horizontal = ScreenPadding).selectableGroup()) {
                        DockStyle.entries.forEach { style ->
                            ChoiceRow(stringResource(style.label), stringResource(style.detail), display.dockStyle == style) {
                                onChange { it.copy(display = it.display.copy(dockStyle = style)) }
                            }
                        }
                    }
                }
            }
            item(contentType = "items") {
                Column {
                    BarSectionTitle(R.string.set_bar_icons)
                    Text(
                        stringResource(R.string.set_bar_icons_sheet) + if (room) "" else " " + stringResource(R.string.set_bar_full),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, bottom = 12.dp),
                    )
                    GroupedRows {
                        listed.forEachIndexed { index, item -> ItemRow(item, index, listed.lastIndex, room, ::setDock, offered) }
                    }
                }
            }
            item(contentType = "hide") {
                GroupedRows(Modifier.padding(top = 18.dp)) {
                    SwitchRow(R.drawable.ic_keyboard_arrow_down, R.string.set_bar_hide, stringResource(R.string.set_bar_hide_detail), display.hideDockOnScroll, first = true) { on ->
                        onChange { it.copy(display = it.display.copy(hideDockOnScroll = on)) }
                    }
                }
            }
        }
    }
}

/**
 * One item of the bar: its icon and name, the arrows that move it among the items listed (the first can't go up, the
 * last can't go down), and its switch: Library's always on, the others' off only while the bar is full ([room]).
 */
@Composable
private fun ItemRow(
    item: DockItemSetting,
    index: Int,
    last: Int,
    room: Boolean,
    setDock: ((List<DockItemSetting>) -> List<DockItemSetting>) -> Unit,
    offered: (DockTab) -> Boolean,
) {
    val name = stringResource(item.tab.label)
    val library = item.tab == DockTab.LIBRARY
    val on = item.shown || library
    val enabled = !library && (on || room)
    GroupedRow(
        item.tab.icon,
        name,
        tile = MaterialTheme.colorScheme.surfaceContainerHighest,
        onTile = MaterialTheme.colorScheme.onSurfaceVariant,
        first = index == 0,
        modifier = Modifier.toggleable(on, enabled = enabled, role = Role.Switch) { switched -> setDock { dockSwitched(it, item.tab, switched, offered) } },
        supporting = if (library) ({ Text(stringResource(R.string.set_bar_library)) }) else null,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeIconButton(R.drawable.ic_arrow_upward, stringResource(R.string.set_tab_up, name), { setDock { dockMoved(it, item.tab, up = true, offered) } }, enabled = index > 0, glass = false, size = 36.dp)
                ChromeIconButton(R.drawable.ic_arrow_downward, stringResource(R.string.set_tab_down, name), { setDock { dockMoved(it, item.tab, up = false, offered) } }, enabled = index < last, glass = false, size = 36.dp)
                LyrifySwitch(on, Modifier.padding(start = 4.dp), enabled = enabled)
            }
        },
    )
}

/** A part's title, as Settings' sections have theirs. */
@Composable
private fun BarSectionTitle(@StringRes title: Int, first: Boolean = false) {
    SectionTitle(stringResource(title), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = if (first) 4.dp else 18.dp, bottom = 8.dp))
}

/** The bar's look in words: Standard or Slim. */
@get:StringRes
val DockStyle.label: Int
    get() = when (this) {
        DockStyle.STANDARD -> R.string.set_bar_standard
        DockStyle.SLIM -> R.string.set_bar_slim
    }

@get:StringRes
private val DockStyle.detail: Int
    get() = when (this) {
        DockStyle.STANDARD -> R.string.set_bar_standard_detail
        DockStyle.SLIM -> R.string.set_bar_slim_detail
    }
