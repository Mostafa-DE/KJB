package app.lyrify.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.Removal
import app.lyrify.media.CurrentTrack
import app.lyrify.media.NowPlaying
import app.lyrify.media.SweepProgress
import app.lyrify.settings.DockTab
import app.lyrify.settings.Settings
import app.lyrify.tones.ToneListStore
import app.lyrify.tones.TonesState

/**
 * Lyrics as every page sees them (2.0's state, gathered by the shell): the ledger, how plain lyrics get timed, Remove
 * lyrics, and Retry failed ([connected]: the listener runs the retries). [notWatching]: notification access is on but
 * KJB can't see the music app.
 */
@Stable
data class LyricsUi(
    val ledger: LedgerState,
    val timing: PlainTiming,
    val removal: RemovalState,
    val connected: Boolean,
    val retrying: Boolean,
    val onRetryFailed: () -> Unit,
    val notWatching: Boolean,
    val onOpenNotificationAccess: () -> Unit,
)

/** The shell's [LyricsUi]. */
val LocalLyrics = compositionLocalOf<LyricsUi> { error("No LyricsUi: AppShell provides it") }

/**
 * The app once set up: each dock tab with its own page stack ([Navigator], kept across a turn of the phone), the Now bar
 * and the dock over Library and Search and Tools' root, Tools pages as full screens, and the full-screen views over
 * everything. Every page and view is drawn by its entry composable in its owner's file; this file only routes. Back
 * closes a sheet, dialog or menu first (their own windows), then a view, then a page, then walks back through the tab
 * history. The app reopens on the last tab ([Settings.nav]), or on the page a notification names ([destination]). What
 * [checkAnswers] say shows here, also once Check these has closed or the phone has turned.
 */
@Composable
fun AppShell(
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    ledger: LedgerState,
    track: CurrentTrack?,
    sweep: SweepProgress?,
    connected: Boolean,
    ai: AiState,
    aiActions: AiActions,
    aiWaiting: Int,
    aiDoneToday: Int,
    grants: Grants,
    versionName: String,
    checkActions: CheckActions,
    checkAnswers: CheckAnswers,
    onRetryFailed: () -> Unit,
    onRemove: suspend (String) -> Removal,
    destination: Page?,
    onDestinationOpened: () -> Unit,
    view: FullScreen? = null,
    onViewOpened: () -> Unit = {},
) {
    val lastTab = settings.nav.lastTab
    val nav = rememberSaveable(saver = NavState.saver(lastTab)) { Navigator(NavState.start(lastTab)) }
    val state = nav.state
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val density = LocalDensity.current
    val backdrop = rememberGlassBackdrop()
    val holder = rememberSaveableStateHolder()
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var bottom by remember { mutableStateOf(0.dp) }

    val timing = PlainTiming.of(ai.supported, ai.enabled)
    val counts = remember(ledger) { ledger.counts() }
    val checks = remember(ledger) { checkList(ledger).size }
    val removal = rememberRemoval(ledger, track?.player, onRemove, toasts)
    val lyrics = LyricsUi(
        ledger = ledger,
        timing = timing,
        removal = removal,
        connected = connected,
        retrying = sweep?.retrying == true,
        onRetryFailed = onRetryFailed,
        notWatching = grants.access.notifications && !connected,
        onOpenNotificationAccess = grants.onOpenNotificationAccess,
    )
    val dupes by rememberDuplicates().state.collectAsStateWithLifecycle()
    val tones by remember(context) { ToneListStore.of(context).state }.collectAsStateWithLifecycle(TonesState())
    val rows = toolRows(checks, dupesView(dupes), dupes.badge, tones.tones.size, coversTagsLine(), ai, aiWaiting)
    val own = rememberOwnNow()
    val followed by NowPlaying.followed.collectAsStateWithLifecycle()
    val other = rememberOtherSession(followed?.takeIf { grants.access.notifications })
    val otherPlayback = other?.playback
    val bar = nowBarShows(own.now, track, otherPlayback?.playing == true)
    val backup = rememberBackupActions { on -> onSettings { it.copy(backup = it.backup.copy(automatic = on)) } }

    LaunchedEffect(state.tab) {
        val tab = state.tab
        onSettings { if (it.nav.lastTab == tab) it else it.copy(nav = it.nav.copy(lastTab = tab)) }
    }
    // A notification's tap (the AI's: Tools > AI lyrics), also on a cold start.
    LaunchedEffect(destination) {
        destination?.let {
            nav.open(it)
            onDestinationOpened()
        }
    }
    // The widget's cover: the player, also on a cold start.
    LaunchedEffect(view) {
        view?.let {
            nav.show(it)
            onViewOpened()
        }
    }
    LaunchedEffect(checkAnswers) {
        checkAnswers.messages.collect { toasts.say(resources, it) }
    }
    // Pages and the tab history; a page's own back (selection, a page's sheet) is composed later and comes first.
    BackHandler(enabled = state.views.isEmpty() && (state.pages.isNotEmpty() || state.history.isNotEmpty())) { nav.back() }

    CompositionLocalProvider(LocalNavigator provides nav, LocalLyrics provides lyrics) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalShellChrome provides ShellChrome(backdrop, bottom)) {
                AnimatedContent(
                    targetState = state.tab,
                    transitionSpec = {
                        // The new tab comes from the side of the tab tapped; with reduced motion it only fades.
                        val side = tabSlide(initialState, targetState) * if (rtl) -1 else 1
                        val enter = if (reduced) {
                            fadeIn(tween(FADE_IN_MS))
                        } else {
                            slideInHorizontally(TAB_SLIDE) { it / SLIDE_SHARE * side } + fadeIn(tween(FADE_IN_MS, delayMillis = FADE_DELAY_MS))
                        }
                        enter togetherWith fadeOut(tween(FADE_OUT_MS))
                    },
                    label = "tab",
                ) { tab ->
                    val stack = nav.state.stacks[tab].orEmpty()
                    // Each page keeps its scroll and its fields while pages are pushed over it, and across a turn of the phone.
                    holder.SaveableStateProvider(pageKey(tab, stack)) {
                        when (val page = stack.lastOrNull()) {
                            null -> when (tab) {
                                DockTab.LIBRARY -> LibraryPage()
                                DockTab.SEARCH -> SearchPage()
                                DockTab.TOOLS -> ToolsScreen(rows, nav::open)
                            }
                            else -> PageContent(page, settings, onSettings, grants, ai, aiActions, aiWaiting, aiDoneToday, counts, backup, versionName, checkActions, checkAnswers)
                        }
                    }
                }
            }
            BottomChrome(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottom = with(density) { it.height.toDp() } }, chrome = state.chrome) {
                val selection = nav.selectionBar
                if (selection != null) selection() else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NowBar(
                        shows = bar,
                        ledger = ledger,
                        other = otherPlayback,
                        backdrop = backdrop,
                        onOpen = { nav.show(if (bar is NowBarShows.Own) FullScreen.Player else FullScreen.CompanionNow) },
                        onPlayPause = barPlayPause(bar, own::playPause, otherPlayback, other?.let { session -> session::playPause }),
                    )
                    Dock(state.tab, dot = toolsDot(checks, dupes.badge, aiAttention(ai, aiWaiting)), backdrop = backdrop, onSelect = nav::select)
                }
            }

            // The views over everything, the newest on top. Their back comes before the pages' and after their own.
            if (state.views.isNotEmpty()) key(state.views.size) { BackHandler { nav.back() } }
            state.views.forEach { view ->
                key(view) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (view) {
                            FullScreen.Player -> PlayerView()
                            FullScreen.FullLyrics -> FullLyricsView()
                            is FullScreen.SyncEditor -> SyncEditorView(view)
                            is FullScreen.EditTags -> EditTagsView(view)
                            is FullScreen.EditAlbum -> EditAlbumView(view)
                            FullScreen.Equalizer -> EqualizerView()
                            FullScreen.CompanionNow -> CompanionNowScreen(
                                ledger = ledger,
                                track = track,
                                sweep = sweep,
                                connected = connected,
                                timing = timing,
                                checks = checks,
                                aiTile = aiTile(ai, aiWaiting),
                                removal = removal,
                                banner = { LyricsBanners(lyrics, Modifier) },
                                onRetryFailed = onRetryFailed,
                                onOpenChecks = { nav.open(Page.Check(it)) },
                                onOpenAi = { nav.open(Page.Ai) },
                                onClose = { nav.close(FullScreen.CompanionNow) },
                            )
                        }
                    }
                }
            }
        }
        RemoveLyricsDialog(removal)
        // The sync editor's "Synced lyrics saved" and its Undo (P3).
        LyricsUndoOffers()
    }
    // A page that left its stack forgets its saved scroll and fields.
    var keptStacks by remember { mutableStateOf(state.stacks) }
    LaunchedEffect(state.stacks) {
        (pageKeys(keptStacks) - pageKeys(state.stacks)).forEach(holder::removeState)
        keptStacks = state.stacks
    }
}

/** A page above a tab's root, drawn by its owner's entry composable. */
@Composable
private fun PageContent(
    page: Page,
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    grants: Grants,
    ai: AiState,
    aiActions: AiActions,
    aiWaiting: Int,
    aiDoneToday: Int,
    counts: Map<LyricsStatus, Int>,
    backup: BackupActions,
    versionName: String,
    checkActions: CheckActions,
    checkAnswers: CheckAnswers,
) {
    val nav = LocalNavigator.current
    val lyrics = LocalLyrics.current
    val back = { nav.back() }
    when (page) {
        is Page.Songs -> SongsPage(page)
        Page.FoldersToSkip -> FoldersToSkipPage()
        is Page.CollectionPages -> CollectionPage(page)
        is Page.QueuePages -> QueuePage(page)
        is Page.ListeningPages -> ListeningPage(page)
        is Page.SearchAll -> SearchAllPage(page)
        is Page.Check -> CheckScreen(lyrics.ledger, lyrics.timing, checkActions, checkAnswers, page.path) { back() }
        Page.Duplicates -> DuplicatesPage { back() }
        Page.Tones -> TonesPage(lyrics.ledger) { back() }
        Page.Ai -> AiPage(ai, counts, aiWaiting, aiDoneToday, aiActions, onOpenStatus = { nav.open(Page.Songs(it.group, it)) }) { back() }
        Page.CoversTags -> CoversTagsPage()
        Page.TagHistory -> TagHistoryPage()
        Page.Settings -> SettingsScreen(settings, onSettings, grants, ai, aiActions, backup, versionName, onOpen = nav::open) { back() }
        Page.LibraryTabs -> LibraryTabsScreen(settings, onSettings) { back() }
        Page.Licences -> LicencesPage()
        is Page.Licence -> LicencePage(page)
    }
}

/**
 * Above the gesture area: the toast slot, then (where the tab shows [chrome]) the Now bar and the dock, or a page's
 * selection bar in their place. They hide while the keyboard is up; the message stays above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomChrome(modifier: Modifier, chrome: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
        ToastSlot(Modifier.padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 8.dp))
        AnimatedVisibility(
            visible = chrome && !WindowInsets.isImeVisible,
            enter = if (reduced) fadeIn() else slideInVertically { it } + fadeIn(),
            exit = if (reduced) fadeOut() else slideOutVertically { it } + fadeOut(),
        ) { content() }
    }
}

/**
 * The one-line banners a page shows under its top bar, each with a button that opens its sheet: the song list
 * unreadable, and (with [listener]) KJB not seeing the music app while notification access is on.
 */
@Composable
fun LyricsBanners(lyrics: LyricsUi, modifier: Modifier = Modifier, listener: Boolean = true) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val readable = lyrics.ledger.readable
    val watching = !listener || !lyrics.notWatching
    if (readable && watching && sheet == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!readable) {
            Banner(R.drawable.ic_warning, stringResource(R.string.ledger_unreadable_title), stringResource(R.string.banner_why), colors.errorContainer, colors.onErrorContainer, onAction = { sheet = WHY })
        }
        if (!watching) {
            Banner(
                R.drawable.ic_link_off,
                stringResource(R.string.listener_not_connected_title),
                stringResource(R.string.banner_fix),
                brand.highlightContainer,
                brand.onHighlightContainer,
                onAction = { sheet = FIX },
            )
        }
    }
    when (sheet) {
        WHY -> HelpSheet(stringResource(R.string.ledger_unreadable_title), stringResource(R.string.ledger_unreadable)) { sheet = null }
        FIX -> HelpSheet(
            stringResource(R.string.listener_not_connected_title),
            stringResource(R.string.listener_not_connected),
            action = stringResource(R.string.setup_notifications_action),
            onAction = lyrics.onOpenNotificationAccess,
        ) { sheet = null }
    }
}

private const val FADE_IN_MS = 180
private const val FADE_DELAY_MS = 40
private const val FADE_OUT_MS = 90

/** A new tab slides in from a tenth of the width, on the settle spring (Motion.settle). */
private const val SLIDE_SHARE = 10
private val TAB_SLIDE = spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)
private const val WHY = "why"
private const val FIX = "fix"
