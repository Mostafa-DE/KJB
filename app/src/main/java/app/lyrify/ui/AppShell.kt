package app.lyrify.ui

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import androidx.compose.animation.EnterExitState
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
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
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
    val holder = rememberSaveableStateHolder()
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
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
    val barOnScreen = slot == BottomSlot.BAR_AND_DOCK && bar != NowBarShows.Hidden
    val chromeStill = rememberChromeStill(slot != BottomSlot.NONE, state.views, barOnScreen, reduced)

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
    // The page layer follows every move, and Back over it (pages and the tab history).
    PagesBack(nav, pages, swipe, reduced)

    // The song KJB plays shows as playing in the lists, while the Now bar shows KJB's own song (not another app's).
    CompositionLocalProvider(LocalNavigator provides nav, LocalLyrics provides lyrics, LocalPlayingPath provides (bar as? NowBarShows.Own)?.now?.path) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // The page layer with its Now bar and dock: under the views, but over the views a back swipe from a page
            // goes back to (the player it was opened from), shrinking to show them.
            val overViews = swipe.taking is BackTaking.Pages
            Box(Modifier.fillMaxSize().zIndex(if (overViews) 2f else 0f).backPeek(swipe) { swipe.taking is BackTaking.Pages }) {
                CompositionLocalProvider(LocalShellChrome provides ShellChrome(bottom)) {
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
                    still = chromeStill,
                    selection = nav.selectionBar,
                    bar = {
                        NowBar(
                            shows = bar,
                            ledger = ledger,
                            other = otherPlayback,
                            onOpen = { nav.show(if (bar is NowBarShows.Own) FullScreen.Player else FullScreen.CompanionNow) },
                            onPlayPause = barPlayPause(bar, own::playPause, otherPlayback, other?.let { session -> session::playPause }),
                        )
                    },
                    dock = { Dock(state.tab, dot = toolsDot(checks, dupes.badge, aiAttention(ai, aiWaiting)), onSelect = nav::select) },
                )
            }
            // The views a back swipe from a page goes back to dim under it as the page does over a view.
            if (overViews) Box(Modifier.fillMaxSize().zIndex(1.5f).drawBehind { drawRect(Color.Black, alpha = backPeek(swipe.progress, swipe.fromLeft, size.width).dim) })

            // The views over everything, the newest on top. Their back comes before the pages' and after their own.
            if (state.views.isNotEmpty()) {
                key(state.views.size) {
                    PredictiveBackHandler { events ->
                        swipe.serial.withLock {
                            val back = backFromView(nav, swipe, reduced)
                            try {
                                events.collect { back.follow(it) }
                            } catch (e: CancellationException) {
                                withContext(NonCancellable) { back.cancel() }
                                throw e
                            }
                            withContext(NonCancellable) { back.commit() }
                        }
                    }
                }
            }
            val preview = (swipe.taking as? BackTaking.Pages)?.views.orEmpty()
            ViewLayers(state.views, preview, barOnScreen, swipe, reduced) { view ->
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
    still: Boolean,
    selection: (@Composable () -> Unit)?,
    bar: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    // The selection bar stays drawn while it fades out after selection ends.
    val lastSelection = remember { Kept(selection) }
    if (selection != null) lastSelection.value = selection
    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
        ToastSlot(Modifier.padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = TOAST_GAP))
        // While the Now bar grows into the player or the player shrinks into it, the chrome stays where it is: the bar is
        // one end of the transform, so it must not slide or fade under it; the player covers the dock meanwhile.
        AnimatedVisibility(
            visible = slot != BottomSlot.NONE,
            enter = if (still) EnterTransition.None else chromeEnter(reduced),
            // No exit of its own: the shared bounds keep the chrome composed, as it was, until the transform has ended.
            exit = if (still) ExitTransition.None else chromeExit(reduced),
        ) {
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
 * Whether the bottom chrome stays put for its current change ([chromeStays]): decided when it starts showing or hiding,
 * from the view opening over it (with the bar on screen just before) or the view that has just closed (with the bar on
 * screen now), and kept until the next change. Never with reduced motion: there everything fades.
 */
@Composable
private fun rememberChromeStill(shown: Boolean, views: List<FullScreen>, barOnScreen: Boolean, reduced: Boolean): Boolean {
    val before = remember { Kept(ChromeMoment(shown, views, barOnScreen)) }
    val still = remember { Kept(false) }
    val was = before.value
    if (shown != was.shown) {
        still.value = !reduced && if (shown) chromeStays(was.views.lastOrNull(), barOnScreen) else chromeStays(views.lastOrNull(), was.barOnScreen)
    }
    SideEffect { before.value = ChromeMoment(shown, views, barOnScreen) }
    return still.value
}

/** What the bottom chrome saw in the composition before: whether it showed, the views, and whether the bar was on screen. */
private data class ChromeMoment(val shown: Boolean, val views: List<FullScreen>, val barOnScreen: Boolean)

/**
 * The page layer's moves and Back over it: the layer follows every move of [nav]; while a back swipe drives it, the
 * swipe does (a swipe back into the player leaves it to move here, under the player). Whenever no swipe drives it, it
 * converges on the page that is current, whatever a swipe left behind: a page can never stay drawn half way, nor
 * current but hidden under another. Back takes the pages and then the tab history ([backFromPages]); a page's own back
 * (selection, a page's sheet) is composed later and comes first.
 */
@Composable
internal fun PagesBack(nav: Navigator, pages: SeekableTransitionState<PageLayer>, swipe: BackSwipe, reduced: Boolean) {
    val state = nav.state
    val resting = pageLayerOf(state).takeIf { swipe.taking !is BackTaking.Page }
    LaunchedEffect(resting) {
        val target = resting ?: return@LaunchedEffect
        if (pages.currentState != target || pages.targetState != target) pages.animateTo(target)
    }
    PredictiveBackHandler(enabled = state.views.isEmpty() && (state.pages.isNotEmpty() || state.history.isNotEmpty())) { events ->
        // One swipe at a time: a new one waits for the last one's ending, so two never pull the layers two ways.
        swipe.serial.withLock {
            val back = backFromPages(nav, pages, swipe, reduced)
            try {
                events.collect { back.follow(it) }
            } catch (e: CancellationException) {
                // The platform cancelled it (given up, slow or fast): everything goes back to rest, nothing moves.
                withContext(NonCancellable) { back.cancel() }
                throw e
            }
            // Only the progress ending normally commits; a plain Back (three buttons, a key) ends with no move at all.
            withContext(NonCancellable) { back.commit() }
        }
    }
}

/**
 * The page layer: the shown tab's top page, moving as [pageChange] says (a push or a pop along the screen, another
 * dock destination or a stack emptied fading through). A back swipe ([BackTaking.Page]) seeks it toward the page it
 * goes back to, drawn under the one leaving, which shrinks; the page under it dims and lights up as the swipe goes on.
 * Each dock destination's root is drawn by [KeptRoots], under the pages, and only holds its place in the change here.
 */
@Composable
internal fun PageLayers(pages: SeekableTransitionState<PageLayer>, swipe: BackSwipe, rtl: Boolean, reduced: Boolean, content: @Composable (PageLayer) -> Unit) {
    val moves = rememberTransition(pages, label = "pages")
    Box(Modifier.fillMaxSize()) {
        // The roots in a box of their own, so their order among themselves never puts one over the pages.
        Box(Modifier.fillMaxSize()) { KeptRoots(moves, swipe, rtl, reduced, content) }
        moves.AnimatedContent(
            transitionSpec = {
                val change = pageChange(initialState, targetState)
                if (swipe.taking is BackTaking.Page) backTransition(enterSide(change, rtl), reduced) else pageTransition(change, rtl, reduced)
            },
            contentKey = { it.key },
        ) { layer ->
            // A root is drawn by KeptRoots: here an empty layer as large as a page, which takes no touch.
            if (layer.stack.isEmpty()) {
                Box(Modifier.fillMaxSize())
                return@AnimatedContent
            }
            val taken = { (swipe.taking as? BackTaking.Page)?.key == layer.key }
            val under = { (swipe.taking as? BackTaking.Page)?.let { it.key != layer.key } == true }
            Box(
                Modifier
                    .fillMaxSize()
                    // Only the page arriving or resting takes touches; the one leaving is gone for the finger at once.
                    .inertUnless { takesInput(headed = transition.targetState == EnterExitState.Visible, settled = true, container = false) }
                    .backPeek(swipe, taken)
                    .drawWithContent {
                        drawContent()
                        if (under()) drawRect(Color.Black, alpha = backPeek(swipe.progress, swipe.fromLeft, size.width).dim)
                    },
            ) { content(layer) }
        }
    }
}

/**
 * Each dock destination's root (Library with its tabs, Playlists, Search, Tools), composed once it has shown and kept
 * so: a dock switch, or Back to a root from a page over it, never builds it again, so its lists keep their place and
 * their rows, with nothing to load or fade in again (phone test 2026-11-11). A root is drawn and placed only while it
 * shows or moves; it moves as a page would under the same change ([rootFade], [rootSlide]), seeks with a back swipe,
 * and shrinks when one takes it. Hidden, it takes no touch, says nothing to TalkBack, lets go of the focus, its Back
 * handlers stand down, and its selection leaves the bottom chrome ([LocalLayerHeaded]).
 */
@Composable
private fun KeptRoots(moves: Transition<PageLayer>, swipe: BackSwipe, rtl: Boolean, reduced: Boolean, content: @Composable (PageLayer) -> Unit) {
    val now = listOf(moves.currentState, moves.targetState).filter { it.stack.isEmpty() }.map { it.tab }
    val shown = remember { mutableSetOf<DockTab>() }
    SideEffect { shown += now }
    DOCK_TABS.filter { it in shown || it in now }.forEach { tab ->
        key(tab) { KeptRoot(PageLayer(tab, emptyList()), moves, swipe, rtl, reduced, content) }
    }
}

@Composable
private fun KeptRoot(root: PageLayer, moves: Transition<PageLayer>, swipe: BackSwipe, rtl: Boolean, reduced: Boolean, content: @Composable (PageLayer) -> Unit) {
    val swiping = swipe.taking is BackTaking.Page
    val alpha = moves.animateFloat(transitionSpec = { rootFade(root, initialState, targetState, swiping, reduced) }, label = "root fade") { rootAlpha(root, it) }
    val shift = moves.animateFloat(transitionSpec = { rootSlide(root, initialState, targetState, swiping, reduced) }, label = "root slide") { rootShift(root, it, rtl) }
    val headed = moves.targetState == root
    val drawn = headed || moves.currentState == root
    // Its Back handlers (a selection's) answer only while it is the root on its way to the screen or on it.
    val back = rememberNavigationEventDispatcherOwner(enabled = headed)
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(headed) { if (!headed && focused) focus.clearFocus(force = true) }
    Box(
        Modifier
            // The root coming over the one leaving, but under the one a back swipe takes away.
            .zIndex(if (!headed) 0f else if (swiping) -1f else 1f)
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { if (drawn) placeable.place(0, 0) }
            }
            .then(if (drawn) Modifier else Modifier.clearAndSetSemantics {})
            .graphicsLayer {
                this.alpha = alpha.value
                translationX = shift.value * size.width
            }
            .inertUnless { moves.targetState == root }
            .onFocusChanged { focused = it.hasFocus }
            .backPeek(swipe) { (swipe.taking as? BackTaking.Page)?.key == root.key }
            .drawWithContent {
                drawContent()
                val under = (swipe.taking as? BackTaking.Page)?.let { it.key != root.key } == true
                if (under) drawRect(Color.Black, alpha = backPeek(swipe.progress, swipe.fromLeft, size.width).dim)
            },
    ) {
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalLayerHeaded provides headed) { content(root) }
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
            // The swipe that is taking this view, if one is: the layer lets go of it, and only of it, once the view has left.
            val swiped = swipe.taking?.takeIf { it == BackTaking.View(view) }
            if (visible.isIdle && !visible.currentState) {
                LaunchedEffect(Unit) {
                    drawn = drawn - view
                    swiped?.let(swipe::clear)
                }
            }
            // Going back into the bar, the player lets go of the swipe's shrink as it shrinks to the bar.
            if (!visible.targetState && swiped != null && leaves == ViewMotion.CONTAINER) LaunchedEffect(swiped) { swipe.settle(swiped) }
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
                    val page = MaterialTheme.colorScheme.background
                    val growing = container != null && !reduced
                    val fill = if (growing) containerFill(this@AnimatedVisibility, page) else null
                    Surface(
                        Modifier
                            .fillMaxSize()
                            // Leaving, or growing out of the bar or into it (drawn where its layout is not), it takes no touches.
                            .inertUnless { takesInput(headed = visible.targetState, settled = transition.currentState == transition.targetState, container = container != null) }
                            .animateEnterExit(viewEnter(arrives, reduced), viewExit(leaves, reduced))
                            .then(containerBounds(container))
                            .backPeek(swipe) { swipe.taking == BackTaking.View(view) }
                            // The card the player grows in: opaque from its first frame, so the dock stays under it.
                            .drawBehind { drawRect(fill?.value ?: page) },
                        color = Color.Transparent,
                        // The card is drawn behind, so the Surface cannot tell the ink from it: the page's, light or dark.
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ) {
                        // Inside the growing card, the player's own content fades in, and out first on the way back.
                        val content = if (growing) Modifier.animateEnterExit(containerContentEnter(), containerContentExit()) else Modifier
                        Box(content) {
                            CompositionLocalProvider(LocalContainer provides container, LocalLayerHeaded provides visible.targetState) { content(view) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One back swipe of the shell: each move of the finger it [follow]s, then it [commit]s when let go or [cancel]s when
 * given up, both run to their end in the swipe's own handler. Each cleans up only after itself ([BackSwipe.owns]).
 */
private interface ShellBack {
    suspend fun follow(event: BackEventCompat)

    suspend fun commit()

    suspend fun cancel()
}

/** Back with nothing following the finger (reduced motion): it just goes back, with the move's own fade. */
private class PlainBack(private val nav: Navigator) : ShellBack {
    override suspend fun follow(event: BackEventCompat) = Unit

    override suspend fun commit() {
        nav.back()
    }

    override suspend fun cancel() = Unit
}

/**
 * Back from a page or across the tab history, following the finger ([PageSwipe]): the page it goes back to (or the tab)
 * is drawn under the one leaving, which shrinks and stays whole. Committed (the platform's progress ended normally), the
 * move happens at once and the page carries on shrinking as it fades away ([BackSwipe.finish]) while the one under it
 * settles and lights up. A plain Back (three buttons, a key) is a swipe committed before any move: the same motion from
 * its start, never a cut (phone test 2026-11-11). Cancelled (given up, slow or fast), the
 * page springs back to rest, covering the screen again, while the page layer runs back to it, and only then is the page
 * under it let go of, so every layer is as it was: its offset, scale, alpha, which page is drawn, and the order they are
 * drawn in. Back to the player (or another view) a page was opened from draws that view under the page, its bar and
 * dock instead. With reduced motion nothing follows the finger: Back just fades.
 */
private fun backFromPages(nav: Navigator, pages: SeekableTransitionState<PageLayer>, swipe: BackSwipe, reduced: Boolean): ShellBack {
    val from = nav.state
    val to = from.back()
    if (to == null || reduced) return PlainBack(nav)
    val origin = pageLayerOf(from)
    val dest = pageLayerOf(to)
    val intoViews = to.views.isNotEmpty()
    val taking = if (intoViews) BackTaking.Pages(to.views) else BackTaking.Page(origin.key)
    var layers = PageSwipe(origin).started(dest)
    swipe.start(taking)
    return object : ShellBack {
        override suspend fun follow(event: BackEventCompat) {
            swipe.follow(event)
            layers = layers.moved(event.progress)
            if (!intoViews) pages.seekTo(layers.progress, dest)
        }

        override suspend fun commit() {
            layers = layers.committed()
            nav.back()
            try {
                coroutineScope {
                    launch { swipe.finish(taking) }
                    if (!intoViews) pages.animateTo(layers.current)
                }
            } finally {
                swipe.clear(taking)
            }
        }

        override suspend fun cancel() {
            layers = layers.cancelled()
            try {
                coroutineScope {
                    // Run back, never snapped: a snap after a seek leaves the page current, taking touches, but drawn
                    // as it was leaving (gone), with the page under it showing through.
                    launch { swipe.springBack(taking) }
                    if (!intoViews) pages.animateTo(layers.current)
                }
            } finally {
                swipe.clear(taking)
            }
        }
    }
}

/**
 * Back from the top view, following the finger: it shrinks over the layer under it, which lights up; let go, it leaves
 * from there (into the Now bar for the player), and its layer lets go of the swipe once it has left; given up, it comes
 * back. With reduced motion it just fades.
 */
private fun backFromView(nav: Navigator, swipe: BackSwipe, reduced: Boolean): ShellBack {
    val view = nav.state.views.lastOrNull()
    if (view == null || reduced) return PlainBack(nav)
    val taking = BackTaking.View(view)
    swipe.start(taking)
    return object : ShellBack {
        override suspend fun follow(event: BackEventCompat) = swipe.follow(event)

        override suspend fun commit() {
            nav.back()
        }

        override suspend fun cancel() {
            try {
                swipe.springBack(taking)
            } finally {
                swipe.clear(taking)
            }
        }
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
