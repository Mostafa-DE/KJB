package app.lyrify.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
 * and the dock over Library, Playlists, Search and Tools' root, Tools pages as full screens, and the full-screen views
 * over everything. Every page and view is drawn by its entry composable in its owner's file; this file only routes. Back
 * closes a sheet, dialog or menu first (their own windows), then a view, then a page, then walks back through the tab
 * history. The app reopens on the last tab ([Settings.nav]), or on the page a notification names ([destination]). What
 * [checkAnswers] say shows here, also once Check these has closed or the phone has turned.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val scope = rememberCoroutineScope()
    var bottom by remember { mutableStateOf(0.dp) }
    // The page layer moves as its stack changes; a back swipe seeks it toward the page it goes back to.
    val pages = remember { SeekableTransitionState(pageLayerOf(state)) }
    val swipe = remember { BackSwipe() }

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
    val rows = toolRows(checks, dupesView(dupes), dupes.badge, tones.tones.size, coversTagsRow(), ai, aiWaiting)
    val own = rememberOwnNow()
    val followed by NowPlaying.followed.collectAsStateWithLifecycle()
    val other = rememberOtherSession(followed?.takeIf { grants.access.notifications })
    val otherPlayback = other?.playback
    val bar = nowBarShows(own.now, track, otherPlayback?.playing == true)
    val backup = rememberBackupActions { on -> onSettings { it.copy(backup = it.backup.copy(automatic = on)) } }
    val slot = bottomSlot(nav.selectionBar != null, state.chrome, WindowInsets.isImeVisible)

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
    // The page layer follows every move; while a back swipe drives it, the swipe does (a swipe back into the player
    // leaves it to move here, under the player).
    val layer = pageLayerOf(state)
    LaunchedEffect(layer) { if (swipe.taking !is BackTaking.Page) pages.animateTo(layer) }
    // Pages and the tab history; a page's own back (selection, a page's sheet) is composed later and comes first.
    PredictiveBackHandler(enabled = state.views.isEmpty() && (state.pages.isNotEmpty() || state.history.isNotEmpty())) { events ->
        backFromPages(events, nav, pages, swipe, scope, reduced)
    }

    // The song KJB plays shows as playing in the lists, while the Now bar shows KJB's own song (not another app's).
    CompositionLocalProvider(LocalNavigator provides nav, LocalLyrics provides lyrics, LocalPlayingPath provides (bar as? NowBarShows.Own)?.now?.path) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // The page layer with its Now bar and dock: under the views, but over the views a back swipe from a page
            // goes back to (the player it was opened from), shrinking to show them.
            val overViews = swipe.taking is BackTaking.Pages
            Box(Modifier.fillMaxSize().zIndex(if (overViews) 2f else 0f).backPeek(swipe) { swipe.taking is BackTaking.Pages }) {
                CompositionLocalProvider(LocalShellChrome provides ShellChrome(backdrop, bottom)) {
                    PageLayers(pages, swipe, rtl, reduced) { shown ->
                        // Each page keeps its scroll and its fields while pages are pushed over it, and across a turn of the phone.
                        holder.SaveableStateProvider(shown.key) {
                            when (val page = shown.stack.lastOrNull()) {
                                null -> when (shown.tab) {
                                    DockTab.LIBRARY -> LibraryPage()
                                    DockTab.PLAYLISTS -> PlaylistsPage()
                                    DockTab.SEARCH -> SearchPage()
                                    DockTab.TOOLS -> ToolsScreen(rows, nav::open)
                                }
                                else -> PageContent(page, settings, onSettings, grants, ai, aiActions, aiWaiting, aiDoneToday, counts, backup, versionName, checkActions, checkAnswers)
                            }
                        }
                    }
                }
                BottomChrome(
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottom = with(density) { it.height.toDp() } },
                    slot = slot,
                    selection = nav.selectionBar,
                    bar = {
                        NowBar(
                            shows = bar,
                            ledger = ledger,
                            other = otherPlayback,
                            backdrop = backdrop,
                            onOpen = { nav.show(if (bar is NowBarShows.Own) FullScreen.Player else FullScreen.CompanionNow) },
                            onPlayPause = barPlayPause(bar, own::playPause, otherPlayback, other?.let { session -> session::playPause }),
                        )
                    },
                    dock = { Dock(state.tab, dot = toolsDot(checks, dupes.badge, aiAttention(ai, aiWaiting)), backdrop = backdrop, onSelect = nav::select) },
                )
            }
            // The views a back swipe from a page goes back to dim under it as the page does over a view.
            if (overViews) Box(Modifier.fillMaxSize().zIndex(1.5f).drawBehind { drawRect(Color.Black, alpha = backPeek(swipe.progress, swipe.fromLeft, size.width).dim) })

            // The views over everything, the newest on top. Their back comes before the pages' and after their own.
            if (state.views.isNotEmpty()) {
                key(state.views.size) {
                    PredictiveBackHandler { events -> backFromView(events, nav, swipe, scope, reduced) }
                }
            }
            val preview = (swipe.taking as? BackTaking.Pages)?.views.orEmpty()
            ViewLayers(state.views, preview, barOnScreen = slot == BottomSlot.BAR_AND_DOCK && bar != NowBarShows.Hidden, swipe, reduced) { view ->
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
        RemoveLyricsDialog(removal)
        // The sync editor's "Synced lyrics saved" and its Undo (P3).
        LyricsUndoOffers()
    }
    // A page that left its stack forgets its saved scroll and fields.
    var kept by remember { mutableStateOf(state) }
    LaunchedEffect(state.stacks) {
        (pageKeys(kept) - pageKeys(state)).forEach(holder::removeState)
        kept = state
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
 * Above the gesture area: the toast slot, then (in [slot]) the Now bar and the dock, or a page's [selection] bar in
 * their place, faded through as selection starts and ends; nothing while the keyboard is up or a full screen shows (the
 * message stays). The chrome slides as a whole and never resizes while it moves, so the room it takes at the bottom
 * (what pages pad by) changes once, not on every frame. The Now bar's visibility is one end of the player's container
 * transform ([LocalContainer]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedTransitionScope.BottomChrome(
    modifier: Modifier,
    slot: BottomSlot,
    selection: (@Composable () -> Unit)?,
    bar: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    // The selection bar stays drawn while it fades out after selection ends.
    val lastSelection = remember { Kept(selection) }
    if (selection != null) lastSelection.value = selection
    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
        ToastSlot(Modifier.padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 8.dp))
        AnimatedVisibility(visible = slot != BottomSlot.NONE, enter = chromeEnter(reduced), exit = chromeExit(reduced)) {
            val container = remember(this) { Container(this@BottomChrome, this, bar = true) }
            CompositionLocalProvider(LocalContainer provides container) {
                AnimatedContent(
                    targetState = slot == BottomSlot.SELECTION,
                    transitionSpec = { chromeSwap(reduced) },
                    contentAlignment = Alignment.BottomCenter,
                    label = "bottom chrome",
                ) { selecting ->
                    if (selecting) {
                        lastSelection.value?.invoke()
                    } else {
                        Column {
                            bar()
                            dock()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The page layer: the shown tab's top page, moving as [pageChange] says (a push or a pop along the screen, another tab
 * from its side, a stack emptied fading through). A back swipe ([BackTaking.Page]) seeks it toward the page it goes
 * back to, drawn under the one leaving, which shrinks; the page under it dims and lights up as the swipe goes on.
 */
@Composable
private fun PageLayers(pages: SeekableTransitionState<PageLayer>, swipe: BackSwipe, rtl: Boolean, reduced: Boolean, content: @Composable (PageLayer) -> Unit) {
    val transition = rememberTransition(pages, label = "pages")
    transition.AnimatedContent(
        transitionSpec = {
            val change = pageChange(initialState, targetState)
            if (swipe.taking is BackTaking.Page) backTransition(enterSide(change, rtl), reduced) else pageTransition(change, rtl, reduced)
        },
        contentKey = { it.key },
    ) { layer ->
        val taken = { (swipe.taking as? BackTaking.Page)?.key == layer.key }
        val under = { (swipe.taking as? BackTaking.Page)?.let { it.key != layer.key } == true }
        Box(
            Modifier
                .fillMaxSize()
                .backPeek(swipe, taken)
                .drawWithContent {
                    drawContent()
                    if (under()) drawRect(Color.Black, alpha = backPeek(swipe.progress, swipe.fromLeft, size.width).dim)
                },
        ) { content(layer) }
    }
}

/**
 * The views over everything, the newest on top, each arriving and leaving as [viewMotion] says: the player grows out
 * of the Now bar and goes back into it when the bar is on screen ([barOnScreen]), every other view rises. A view that
 * closes stays drawn until it has left ([drawnViews]); the layer under a view dims; a back swipe shrinks the top one.
 * [preview]: the views a back swipe from a page goes back to, drawn under that page at once, without arriving.
 */
@Composable
private fun SharedTransitionScope.ViewLayers(
    shown: List<FullScreen>,
    preview: List<FullScreen>,
    barOnScreen: Boolean,
    swipe: BackSwipe,
    reduced: Boolean,
    content: @Composable (FullScreen) -> Unit,
) {
    val wanted = shown + preview.filterNot { it in shown }
    val restored = remember { shown }
    var drawn by remember { mutableStateOf(wanted) }
    val all = drawnViews(drawn, wanted)
    SideEffect { if (all != drawn) drawn = all }
    // Whether the bar was on screen as a view was asked for: the composition before this one.
    val barBefore = remember { Kept(barOnScreen) }
    SideEffect { barBefore.value = barOnScreen }
    all.forEach { view ->
        key(view) {
            val visible = remember { MutableTransitionState(view in restored || view in preview) }
            visible.targetState = view in wanted
            val arrives = remember { viewMotion(view, barBefore.value) }
            val leaves = viewMotion(view, barOnScreen)
            val takenBySwipe = swipe.taking == BackTaking.View(view)
            if (visible.isIdle && !visible.currentState) {
                LaunchedEffect(Unit) {
                    drawn = drawn - view
                    if (swipe.taking == BackTaking.View(view)) swipe.clear()
                }
            }
            // Going back into the bar, the player lets go of the swipe's shrink as it shrinks to the bar.
            if (!visible.targetState && takenBySwipe && leaves == ViewMotion.CONTAINER) LaunchedEffect(Unit) { swipe.settle() }
            AnimatedVisibility(visibleState = visible, enter = EnterTransition.None, exit = ExitTransition.None) {
                val end = remember(this) { Container(this@ViewLayers, this, bar = false) }
                val container = end.takeIf { arrives == ViewMotion.CONTAINER || leaves == ViewMotion.CONTAINER }
                Box(Modifier.fillMaxSize()) {
                    // The layer under this one dims as it arrives, and lights up again under a back swipe.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .animateEnterExit(fadeIn(tween(if (reduced) Motion.REDUCED_MS else Motion.OPEN_MS)), fadeOut(tween(if (reduced) Motion.REDUCED_MS else Motion.SETTLE_MS)))
                            .drawBehind {
                                val dim = if (swipe.taking == BackTaking.View(view)) backPeek(swipe.progress, swipe.fromLeft, size.width).dim else DIM_UNDER
                                drawRect(Color.Black, alpha = dim)
                            },
                    )
                    Surface(
                        Modifier
                            .fillMaxSize()
                            .animateEnterExit(viewEnter(arrives, reduced), viewExit(leaves, reduced))
                            .then(containerBounds(container))
                            .backPeek(swipe) { swipe.taking == BackTaking.View(view) },
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        CompositionLocalProvider(LocalContainer provides container) { content(view) }
                    }
                }
            }
        }
    }
}

/**
 * Back from a page or across the tab history, following the finger: the page it goes back to (or the tab) is drawn
 * under the one leaving, which shrinks; let go, the page leaves as a pop does; given up, everything comes back. Back to
 * the player (or another view) a page was opened from draws that view under the page, its bar and dock, which shrink
 * over it and fade away when let go. With reduced motion nothing follows the finger: Back just fades.
 */
private suspend fun backFromPages(events: Flow<BackEventCompat>, nav: Navigator, pages: SeekableTransitionState<PageLayer>, swipe: BackSwipe, scope: CoroutineScope, reduced: Boolean) {
    val from = nav.state
    val to = from.back()
    if (to == null || reduced) {
        events.collect { }
        nav.back()
        return
    }
    val origin = pageLayerOf(from)
    val dest = pageLayerOf(to)
    val intoViews = to.views.isNotEmpty()
    swipe.start(if (intoViews) BackTaking.Pages(to.views) else BackTaking.Page(origin.key))
    try {
        events.collect { event ->
            swipe.follow(event)
            if (!intoViews) pages.seekTo(event.progress, dest)
        }
    } catch (e: CancellationException) {
        scope.launch {
            if (!intoViews) launch { pages.seekBack(origin) }
            swipe.cancel()
        }
        throw e
    }
    scope.launch {
        if (intoViews) {
            swipe.leave()
            nav.back()
        } else {
            nav.back()
            pages.animateTo(dest)
        }
        swipe.clear()
    }
}

/**
 * Back from the top view, following the finger: it shrinks over the layer under it, which lights up; let go, it leaves
 * from there (into the Now bar for the player); given up, it comes back. With reduced motion it just fades.
 */
private suspend fun backFromView(events: Flow<BackEventCompat>, nav: Navigator, swipe: BackSwipe, scope: CoroutineScope, reduced: Boolean) {
    val view = nav.state.views.lastOrNull()
    if (view == null || reduced) {
        events.collect { }
        nav.back()
        return
    }
    swipe.start(BackTaking.View(view))
    try {
        events.collect(swipe::follow)
    } catch (e: CancellationException) {
        scope.launch { swipe.cancel() }
        throw e
    }
    // Its layer lets go of the swipe once it has left.
    nav.back()
}

/** Seeks the page layer back to where a given-up swipe began ([origin]), then settles there. */
private suspend fun SeekableTransitionState<PageLayer>.seekBack(origin: PageLayer) {
    val from = fraction
    val start = withFrameMillis { it }
    var elapsed = 0L
    while (elapsed < Motion.SETTLE_MS) {
        elapsed = withFrameMillis { it } - start
        seekTo(from * (1f - Motion.Standard.transform((elapsed / Motion.SETTLE_MS.toFloat()).coerceIn(0f, 1f))))
    }
    snapTo(origin)
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
    val reduced = LocalReducedMotion.current
    val readable = lyrics.ledger.readable
    val watching = !listener || !lyrics.notWatching
    // Each banner opens and closes its own room, so the page under it moves smoothly, never at once.
    AnimatedVisibility(!readable || !watching, enter = expandIn(reduced), exit = shrinkOut(reduced)) {
        Column(modifier) {
            AnimatedVisibility(!readable, enter = expandIn(reduced), exit = shrinkOut(reduced)) {
                Banner(R.drawable.ic_warning, stringResource(R.string.ledger_unreadable_title), stringResource(R.string.banner_why), colors.errorContainer, colors.onErrorContainer, onAction = { sheet = WHY })
            }
            AnimatedVisibility(!watching, enter = expandIn(reduced), exit = shrinkOut(reduced)) {
                Banner(
                    R.drawable.ic_link_off,
                    stringResource(R.string.listener_not_connected_title),
                    stringResource(R.string.banner_fix),
                    brand.highlightContainer,
                    brand.onHighlightContainer,
                    onAction = { sheet = FIX },
                    modifier = Modifier.padding(top = if (!readable) 8.dp else 0.dp),
                )
            }
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

private const val WHY = "why"
private const val FIX = "fix"
