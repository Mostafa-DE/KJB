package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.overnight.NightStatus
import app.lyrify.settings.DockTab
import kotlinx.coroutines.launch

/** The rows of Tools, in order: KJB's 2.0 features, Covers & tags, then Settings. */
enum class Tool(@StringRes val title: Int, @DrawableRes val icon: Int, val page: Page) {
    CHECK(R.string.check_title, R.drawable.ic_help_fill, Page.Check(null)),
    DUPLICATES(R.string.dupes_title, R.drawable.ic_content_copy, Page.Duplicates),
    TONES(R.string.tones_title, R.drawable.ic_ring_volume, Page.Tones),
    COVERS_TAGS(R.string.tools_covers, R.drawable.ic_sell, Page.CoversTags),
    AI(R.string.ai_title, R.drawable.ic_auto_awesome, Page.Ai),
    SETTINGS(R.string.settings_title, R.drawable.ic_settings, Page.Settings),
}

/**
 * A row's one-line status (on Tools, and the Favourites row on Songs): a string, a plural with its count, or the AI
 * tile's value and label.
 */
sealed interface ToolLine {
    data class Text(@StringRes val res: Int) : ToolLine

    data class Count(@PluralsRes val res: Int, val count: Int) : ToolLine

    data class Ai(val value: TileValue, @StringRes val label: Int) : ToolLine
}

/** A Tools row: its status [line], a count [badge] (songs to check, groups not opened yet, albums to review), and a [dot] (AI needs the user). */
data class ToolRow(val tool: Tool, val line: ToolLine?, val badge: Int? = null, val dot: Boolean = false)

/**
 * The rows of Tools for what each feature says now: [checks] songs to check, Duplicates' [dupes] view and [dupesBadge]
 * groups not opened yet, [tones] made, Covers & tags' line and badge ([coversTags], from its milestone), and the AI's
 * state with [aiWaiting] songs waiting for it.
 */
fun toolRows(checks: Int, dupes: DupesView, dupesBadge: Int, tones: Int, coversTags: EntryRow?, ai: AiState, aiWaiting: Int): List<ToolRow> = listOf(
    ToolRow(Tool.CHECK, if (checks > 0) ToolLine.Count(R.plurals.tools_check, checks) else ToolLine.Text(R.string.tools_check_none), badge = checks.takeIf { it > 0 }),
    ToolRow(Tool.DUPLICATES, dupesLine(dupes), badge = dupesBadge.takeIf { it > 0 }),
    ToolRow(Tool.TONES, if (tones > 0) ToolLine.Count(R.plurals.tools_tones, tones) else ToolLine.Text(R.string.tools_tones_none)),
    ToolRow(Tool.COVERS_TAGS, coversTags?.line, badge = coversTags?.badge),
    ToolRow(Tool.AI, aiLine(ai, aiWaiting), dot = aiAttention(ai, aiWaiting)),
    ToolRow(Tool.SETTINGS, null),
)

/** Nothing while Duplicates loads; else whether it was scanned, is scanning, or how many groups it found. */
private fun dupesLine(dupes: DupesView): ToolLine? = when (dupes) {
    DupesView.Loading -> null
    DupesView.FirstScan -> ToolLine.Text(R.string.tools_dupes_not_scanned)
    DupesView.Failed -> ToolLine.Text(R.string.tools_dupes_failed)
    is DupesView.Scanning -> ToolLine.Text(R.string.tools_dupes_scanning)
    is DupesView.Results -> dupes.results.groups.size.let { if (it > 0) ToolLine.Count(R.plurals.dupes_groups, it) else ToolLine.Text(R.string.tools_dupes_none) }
}

/** The phone it can't run on, off, all done, or what the Companion Now tile would say ("12/40 · AI tonight"). */
private fun aiLine(ai: AiState, waiting: Int): ToolLine {
    val tile = aiTile(ai, waiting)
    return when {
        ai.chip == null -> ToolLine.Text(R.string.tools_ai_no_chip)
        !ai.newEnough -> ToolLine.Text(R.string.tools_ai_update)
        tile == null -> ToolLine.Text(R.string.tools_ai_off)
        ai.night(waiting) == NightStatus.AllDone -> ToolLine.Text(R.string.ai_all_done)
        else -> ToolLine.Ai(tile.value, tile.label)
    }
}

@Composable
internal fun ToolLine.text(): String = when (this) {
    is ToolLine.Text -> stringResource(res)
    is ToolLine.Count -> pluralStringResource(res, count, count)
    is ToolLine.Ai -> stringResource(R.string.tools_ai_line, tileText(value), stringResource(label))
}

// The screens

/**
 * Tools, the third tab: Check these, Duplicates, Tones, Covers & tags and AI lyrics, each with its status and badge,
 * then Settings on its own. The banners say when the song list can't be read or KJB can't see the music app; while
 * lookups failed, "Retry" is here too, whatever plays (2.0 had it on its Now tab).
 */
@Composable
fun ToolsScreen(rows: List<ToolRow>, onOpen: (Page) -> Unit) {
    val lyrics = LocalLyrics.current
    val failed = remember(lyrics.ledger) { lyrics.ledger.counts()[LyricsStatus.ERROR] ?: 0 }
    PageFrame(
        topBar = { under, backdrop ->
            MainTopBar(DockTab.TOOLS, under, backdrop)
        },
    ) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "banners", contentType = "banners") { LyricsBanners(lyrics, Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 12.dp)) }
            if (failed > 0) {
                item(key = "retry", contentType = "retry") {
                    LyrifyCard(rowMotion(reduced).fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, bottom = 16.dp)) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { RetryRow(failed, lyrics.retrying, canRetry = lyrics.connected, lyrics.onRetryFailed) }
                    }
                }
            }
            item(key = "tools", contentType = "group") {
                GroupedRows(rowMotion(reduced)) {
                    rows.filter { it.tool != Tool.SETTINGS }.forEachIndexed { index, row -> ToolRowView(row, first = index == 0) { onOpen(row.tool.page) } }
                }
            }
            item(key = "settings", contentType = "settings") {
                Column(rowMotion(reduced)) {
                    Spacer(Modifier.height(16.dp))
                    GroupedRows { rows.filter { it.tool == Tool.SETTINGS }.forEach { row -> ToolRowView(row, first = true) { onOpen(row.tool.page) } } }
                }
            }
        }
    }
}

@Composable
private fun ToolRowView(row: ToolRow, first: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (tile, onTile) = toolTile(row.tool)
    val line = row.line?.text()
    GroupedRow(
        row.tool.icon,
        stringResource(row.tool.title),
        tile = tile,
        onTile = onTile,
        first = first,
        supporting = line?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        onClick = onClick,
        minHeight = 72.dp,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.badge?.let { CountBadge(it) }
                if (row.dot) {
                    val attention = stringResource(R.string.nav_attention)
                    DotBadge(Modifier.semantics { contentDescription = attention })
                }
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        },
    )
}

/** The squircle behind a row's icon: Check these in its status tone, AI lyrics in the brand's, the rest neutral. */
@Composable
private fun toolTile(tool: Tool): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    return when (tool) {
        Tool.CHECK -> LocalStatusColors.current[Tone.CHECK].let { it.container to it.onContainer }
        Tool.AI -> brand.highlightContainer to brand.onHighlightContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
}

/** Tools > Duplicates: its own top bar with a back arrow over the 2.0 screen. */
@Composable
fun DuplicatesPage(onBack: () -> Unit) {
    PageFrame(topBar = { under, backdrop -> DuplicatesTopBar(under, backdrop, onBack) }) { padding ->
        DuplicatesScreen(contentPadding = padding, toasts = LocalToasts.current)
    }
}

/** Tools > Tones: its own top bar with a back arrow over the 2.0 screen. */
@Composable
fun TonesPage(ledger: LedgerState, onBack: () -> Unit) {
    PageFrame(topBar = { under, backdrop -> TonesTopBar(under, backdrop, onBack) }) { padding ->
        TonesScreen(ledger = ledger, contentPadding = padding, toasts = LocalToasts.current)
    }
}

/**
 * Tools > AI lyrics: the 2.0 screen under its top bar (back, How it works, and More options with the Run log). A result
 * opens Library > Songs filtered to it ([onOpenStatus]).
 */
@Composable
fun AiPage(
    state: AiState,
    counts: Map<LyricsStatus, Int>,
    waiting: Int,
    doneToday: Int,
    actions: AiActions,
    onOpenStatus: (LyricsStatus) -> Unit,
    onBack: () -> Unit,
) {
    val lyrics = LocalLyrics.current
    val scope = rememberCoroutineScope()
    var runLog by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.ai_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
                actions = {
                    ChromeIconButton(R.drawable.ic_info, stringResource(R.string.ai_info_cd), { info = true })
                    if (state.supported) Box {
                        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { menu = true })
                        LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                            MenuRow(
                                stringResource(R.string.menu_run_log),
                                R.drawable.ic_notes,
                                onClick = {
                                    menu = false
                                    scope.launch { runLog = actions.readLog() }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        AiScreen(
            state = state,
            counts = counts,
            waiting = waiting,
            doneToday = doneToday,
            actions = actions,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            onOpenStatus = onOpenStatus,
            banner = { LyricsBanners(lyrics, Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 12.dp), listener = false) },
        )
    }
    runLog?.let { RunLogSheet(it) { runLog = null } }
    if (info) AiInfoSheet { info = false }
}
