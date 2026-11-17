package app.lyrify.ui

import android.Manifest
import android.app.UiModeManager
import androidx.core.graphics.drawable.toDrawable
import android.os.Build
import android.content.Intent
import android.content.Context
import android.os.StrictMode
import android.view.Window
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import app.lyrify.BuildConfig
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.data.LedgerStore
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.logFailures
import app.lyrify.media.NowPlaying
import app.lyrify.player.PlayerGraph
import app.lyrify.addon.Addon
import app.lyrify.addon.AddonDownloads
import app.lyrify.addon.AddonExemptions
import app.lyrify.addon.AddonInstalls
import app.lyrify.addon.AddonState
import app.lyrify.addon.HandoverState
import app.lyrify.addon.KjbAddons
import app.lyrify.addon.ModelHandover
import app.lyrify.downloads.Downloads
import app.lyrify.models.NpuChips
import app.lyrify.overnight.AiLyrics
import app.lyrify.overnight.AiQueue
import app.lyrify.overnight.audioWithoutLyrics
import app.lyrify.settings.DisplaySettings
import app.lyrify.settings.DockTab
import app.lyrify.settings.ThemeChoice
import app.lyrify.update.Updates
import app.lyrify.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Where a notification's tap asked KJB to open, until the shell has opened it. */
    private val destination = MutableStateFlow<Page?>(null)

    /** The view an intent asked for (the widget's cover: the player), until the shell has shown it. */
    private val view = MutableStateFlow<FullScreen?>(null)

    /** The tab a launcher shortcut asked for (Search), until the shell has opened it. */
    private val tab = MutableStateFlow<DockTab?>(null)

    /** What another app or a shortcut asked to play (Launches.kt), until it plays. */
    private val launch = MutableStateFlow<Launch.Plays?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Brought back after Android ended the process, what the intent asked to play was done then: not again.
        open(intent, fresh = savedInstanceState == null)
        // The stored AI switch is the truth: a schedule that got lost is made again.
        lifecycleScope.launch(logFailures) { AiLyrics.reconcile(applicationContext) }
        val ledger = LedgerRepository(this)
        val display = Lyrify.settings(this).settings.map { it.display }.distinctUntilChanged()
        // The window before the first frame: the last theme drawn (Pure black too), as the phone's dark setting is now,
        // from the small copy kept for it, read at once; settings.json takes over as soon as it is read.
        val drawnBefore = ThemeCopy.read(this)
        paintWindow(window, themeVariant(drawnBefore, resources.configuration.isNightModeActive))
        setContent {
            // Nothing is drawn until the stored theme is read (the app waits for the settings anyway): KJB's first frame
            // is in the chosen theme, never the phone's first, and the window behind it is that theme's page.
            val chosen = display.collectAsStateWithLifecycle(initialValue = null).value ?: return@setContent
            val variant = themeVariant(chosen, isSystemInDarkTheme())
            ThemeWindow(variant, chosen)
            NightMode(chosen.theme, drawnBefore.theme)
            LyrifyTheme(display = chosen) {
                // The app's one message queue: the shell shows it above the dock, a sheet or full screen above its buttons.
                val a11y = LocalAccessibilityManager.current
                CompositionLocalProvider(LocalToasts provides remember { ToastState(a11y) }) {
                    App(
                        ledger,
                        destination.collectAsStateWithLifecycle().value,
                        view.collectAsStateWithLifecycle().value,
                        { view.value = null },
                        { destination.value = null },
                        Opened(tab.collectAsStateWithLifecycle().value, { tab.value = null }, launch.collectAsStateWithLifecycle().value, { launch.value = null }) {
                            view.value = FullScreen.Player
                        },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // In the background Android may end the process: a queue change still waiting is written now.
        PlayerGraph.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /**
     * Where [intent] asks KJB to open, also after the activity was made again. Read once: the extra is taken out, so a
     * turn of the phone does not open it again; a relaunch from Recents opens where the user left instead.
     */
    private fun open(intent: Intent, fresh: Boolean = true) {
        // Audio files from another app, or a launcher shortcut (Launches.kt): read once too.
        when (val asked = takeLaunch(intent)?.takeIf { fresh }) {
            null -> Unit
            Launch.Search -> tab.value = DockTab.SEARCH
            Launch.Favourites -> destination.value = Page.BuiltIn(BuiltInList.FAVOURITES)
            is Launch.Plays -> launch.value = asked
        }
        val name = intent.getStringExtra(EXTRA_DESTINATION) ?: return
        intent.removeExtra(EXTRA_DESTINATION)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        destinationPage(name)?.let { destination.value = it }
        destinationView(name)?.let { view.value = it }
    }
}

/**
 * What another app or a launcher shortcut asked, for the shell: a [tab] to open, something to play ([launch], then
 * [onPlayer]), each cleared once done.
 */
private class Opened(val tab: DockTab?, val onTabOpened: () -> Unit, val launch: Launch.Plays?, val onLaunchDone: () -> Unit, val onPlayer: () -> Unit)

/**
 * The first-run screen while [setupShows] says so, then the app's shell, opened at [destination] when a notification
 * asked for a page, or where [opened] asks; collects what every screen shows.
 */
@Composable
private fun App(ledger: LedgerStore, destination: Page?, view: FullScreen?, onViewOpened: () -> Unit, onDestinationOpened: () -> Unit, opened: Opened) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { Lyrify.settings(context) }
    var access by remember { mutableStateOf(Access.of(context)) }
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    var notifications by remember { mutableStateOf(notificationsAllowed(context)) }
    var aiExempt by remember { mutableStateOf(AddonExemptions.exempt(context, KjbAddons.AI)) }

    // The grants are switched in Settings; check again whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        access = Access.of(context)
        unrestricted = isUnrestricted(context)
        notifications = notificationsAllowed(context)
        aiExempt = AddonExemptions.exempt(context, KjbAddons.AI)
        // An app in the foreground gets its daily time for long media work back.
        AiLyrics.appOpened(context)
        // Back from the phone's "Install unknown apps" switch: the add-on's install goes on.
        AddonInstalls.installer(context).resume()
        // Downloads Android paused (its daily limit for long work) or KJB's process left go on.
        Downloads.resume(context)
        onPauseOrDispose { }
    }

    // Setup and the shell's last tab both come from the settings: wait for their first read.
    val settings = store.settings.collectAsStateWithLifecycle(initialValue = null).value ?: return
    // Null until decided on opening; then the setup screen stays until Continue, and comes back without All files access.
    var setup by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showsSetup = setupShows(access, settings.nav.setupDone, setup)
    SideEffect { setup = showsSetup }
    // Finished, or skipped by a phone that already had both grants (an update from 2.0): not shown again.
    LaunchedEffect(showsSetup, settings.nav.setupDone) {
        if (!showsSetup && !settings.nav.setupDone) store.update { it.copy(nav = it.nav.copy(setupDone = true)) }
    }
    // The tour, once: finished, skipped or declined on the first run, it is stored as seen.
    val tour = rememberSaveable(saver = Tour.Saver) { Tour() }
    LaunchedEffect(tour.ended, settings.nav.tourSeen) {
        if (tour.ended && !settings.nav.tourSeen) store.update { it.copy(nav = it.nav.copy(tourSeen = true)) }
    }
    if (showsSetup) {
        OnboardingScreen(
            access = access,
            setupDone = settings.nav.setupDone,
            onOpenAllFilesAccess = { context.startFirst(allFilesAccessIntents(context)) },
            onOpenNotificationAccess = { context.startFirst(notificationAccessIntents(context)) },
            onOpenAppInfo = { context.startFirst(appInfoIntents(context)) },
            onFinish = { declinesTour ->
                if (declinesTour) tour.decline()
                setup = false
            },
        )
        return
    }
    // It opens by itself once the library has been read, so it points at real songs.
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
    val libraryRead = library !is LibraryState.Loading
    LaunchedEffect(settings.nav.tourSeen, tour.ended, libraryRead) {
        if (tourStarts(settings.nav.tourSeen, tour.ended, tour.state != null, libraryRead)) tour.start()
    }
    // What the AI asks for once it is switched on or its download starts: Show progress, then Keep running, then Run
    // when locked.
    val activity = LocalActivity.current
    val denied by rememberUpdatedState(settings.nav.notificationsDenied)
    val denials by rememberUpdatedState(settings.nav.notificationDenials)
    val grant = notificationGrant(Build.VERSION.SDK_INT, notifications, settings.nav.notificationsDenied)
    var asks by remember { mutableStateOf(emptyList<AiAsk>()) }
    var asking by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<SentRequest?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications = granted
        val request = sent
        // Every no is counted: the second, or one Android gave at once, is for good, and Show progress is asked no more.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && request != null && !granted) {
            val after = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } == true
            val forGood = deniedForGood(granted = false, request.explained, after, denials, SystemClock.elapsedRealtime() - request.at)
            scope.launch {
                store.update { it.copy(nav = it.nav.copy(notificationDenials = it.nav.notificationDenials + 1, notificationsDenied = it.nav.notificationsDenied || forGood)) }
            }
        }
        sent = null
        asking = false
        asks = asks.drop(1)
    }
    val stored by ledger.ledger.collectAsStateWithLifecycle(initialValue = LedgerState())
    // The lyrics screens, counts and the AI's never list a book's part (books/).
    val state = remember(stored, library) { ledgerWithoutBooks(stored, (library as? LibraryState.Ready)?.library) }
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val sweep by NowPlaying.sweep.collectAsStateWithLifecycle()
    val connected by NowPlaying.connected.collectAsStateWithLifecycle()
    // The AI add-on and its models, bound while KJB shows (on a phone the AI runs on only).
    val addon by remember(context) { if (NpuChips.supported() != null) Addon.views(context) else flowOf(null) }.collectAsStateWithLifecycle(initialValue = null)
    val install by remember(context) { AddonInstalls.installer(context).state }.collectAsStateWithLifecycle()
    // KJB runs the add-on's download: whether it waits for Wi-Fi, or failed, is KJB's to say.
    val downloadPhase by remember(context) { AddonDownloads.phase(context) }.collectAsStateWithLifecycle(initialValue = null)
    // A download the phone froze, which goes on at its next run (AD9's freeze watch).
    val downloadPaused by AddonDownloads.paused.collectAsStateWithLifecycle()
    val handover by remember(context) { ModelHandover.state(context) }.collectAsStateWithLifecycle(initialValue = HandoverState.None)
    val aiEnabled by remember(context) { AiLyrics.enabled(context) }.collectAsStateWithLifecycle()
    // The AI add-on left out of the phone's battery optimisation (AD9): offered with the asks, its row and hint on the AI tab.
    val exemptions = remember(context) { AddonExemptions.store(context) }
    val exemptionChoices by exemptions.choices.collectAsStateWithLifecycle()
    val aiLocked = lockedPhone(KjbAddons.AI, addon?.state == AddonState.Ready, aiExempt, exemptionChoices)
    val addonOffer by rememberUpdatedState(aiLocked.offer)
    val requestAiExemption = rememberExemptionRequest(KjbAddons.AI) {
        aiExempt = AddonExemptions.exempt(context, KjbAddons.AI)
        if (aiExempt) AddonDownloads.resumeIfPaused(context)
    }
    val askFor: (Boolean) -> Unit = { switchedOn ->
        asks = aiAsks(switchedOn, notificationGrant(Build.VERSION.SDK_INT, notifications, denied), unrestricted, addonOffer)
    }
    // The add-on ready while the AI is on and Run when locked still due (its install just finished, or KJB opened so):
    // offered once a run, after any other ask. Switching the AI on offers it with the asks.
    var addonOffered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(aiEnabled, aiLocked.offer) {
        if (aiEnabled && aiLocked.offer && !addonOffered) {
            addonOffered = true
            if (AiAsk.ADDON !in asks) asks = asks + AiAsk.ADDON
        }
    }
    val deletingModels by Lyrify.deletingModels.collectAsStateWithLifecycle()
    val aiProgress by AiLyrics.progress.collectAsStateWithLifecycle()
    val aiHold by AiLyrics.hold.collectAsStateWithLifecycle()
    val pluggedIn by remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AiLyrics.pluggedIn(context) else flowOf(false)
    }.collectAsStateWithLifecycle(initialValue = false)
    // Songs waiting for the AI need a look at their files: off the main thread, and only while the AI is on.
    val aiCounts by produceState(0 to 0, state, aiEnabled) {
        if (aiEnabled) {
            value = withContext(Dispatchers.IO) {
                val at = System.currentTimeMillis()
                AiQueue.of(state, at, ::audioWithoutLyrics).size to AiQueue.doneSince(state, at - DAY_MS)
            }
        }
    }
    CompositionLocalProvider(LocalTour provides tour) {
        TourHost {
            AppShell(
                settings = settings,
                onSettings = { change -> scope.launch { if (!store.update(change)) toasts.say(resources, R.string.settings_not_saved) } },
                ledger = state,
                track = track,
                sweep = sweep,
                connected = connected,
                ai = AiState(
                    chip = remember { NpuChips.device() },
                    newEnough = Build.VERSION.SDK_INT >= NpuChips.MIN_SDK,
                    models = AddonDownloads.withPhase(addon?.status?.models, downloadPhase),
                    addon = addon?.state,
                    install = install,
                    handover = handover,
                    enabled = aiEnabled,
                    progress = aiProgress,
                    hold = aiHold,
                    pluggedIn = pluggedIn,
                    unrestricted = unrestricted,
                    stopping = deletingModels,
                    notifications = grant,
                    addonExempt = aiExempt,
                    exemptions = exemptionChoices,
                    downloadPaused = downloadPaused,
                ),
                aiActions = remember(context) {
                    AiActions(
                        onSwitch = { on ->
                            if (on) {
                                AiLyrics.turnOn(context)
                                askFor(true)
                            } else {
                                AiLyrics.turnOff(context)
                            }
                        },
                        onDownload = {
                            AiLyrics.download(context)
                            askFor(false)
                        },
                        onDelete = { Lyrify.deleteModels(context) },
                        onOpenAppInfo = { context.startFirst(appInfoIntents(context)) },
                        readLog = { withContext(Dispatchers.IO) { AiLyrics.runLog(context).tail(RUN_LOG_LINES).joinToString("\n") } },
                        onOpenNotificationSettings = { context.startFirst(notificationSettingsIntents(context)) },
                        onGetAddon = { AddonInstalls.installer(context).start() },
                        onCancelInstall = { AddonInstalls.installer(context).cancel() },
                        onOpenInstallSettings = { context.startFirst(listOf(AddonInstalls.installSettings(context))) },
                        onOpenAddonInfo = { context.startFirst(listOf(AddonInstalls.addonInfo())) },
                        onAllowLocked = requestAiExemption,
                        onLockedHintSeen = { exemptions.update { it.hintSeen(KjbAddons.AI) } },
                    )
                },
                aiWaiting = aiCounts.first,
                aiDoneToday = aiCounts.second,
                grants = Grants(
                    access = access,
                    unrestricted = unrestricted,
                    onOpenNotificationAccess = { context.startFirst(notificationAccessIntents(context)) },
                    onOpenAllFilesAccess = { context.startFirst(allFilesAccessIntents(context)) },
                    onOpenAppInfo = { context.startFirst(appInfoIntents(context)) },
                ),
                versionName = BuildConfig.VERSION_NAME,
                checkActions = remember(context) {
                    CheckActions(
                        choose = { path, id -> Lyrify.choose(context, path, id) },
                        decline = { Lyrify.decline(context, it) },
                        preview = { Lyrify.preview(context, it) },
                        search = { track, text -> Lyrify.search(context, track, text) },
                    )
                },
                checkAnswers = Lyrify.checkAnswers,
                // Retries run in the listener's queue sweep.
                onRetryFailed = NowPlaying::requestRetry,
                onRemove = { Lyrify.remove(context, it) },
                destination = destination,
                onDestinationOpened = onDestinationOpened,
                view = view,
                onViewOpened = onViewOpened,
                tab = opened.tab,
                onTabOpened = opened.onTabOpened,
            )
        }
    }
    // Files another app opened, Shuffle all and Resume: they play, then the player shows.
    LaunchWork(opened.launch, opened.onLaunchDone, opened.onPlayer)
    // KJB's update, when the check finds one; the check runs once a day when KJB opens, unless switched off.
    UpdateHost()
    LaunchedEffect(Unit) { Updates.checker(context).checkIfDue(settings.about.checkForUpdates) }
    AiAskSheets(
        ask = asks.firstOrNull()?.takeUnless { asking },
        onAllow = {
            // Android's own question, Android 13 and later (Show progress is asked only there): whether it had explained
            // before tells a lasting no apart.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val explained = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } == true
                sent = SentRequest(explained, SystemClock.elapsedRealtime())
                asking = true
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                asks = asks.drop(1)
            }
        },
        onOpenAppInfo = {
            asks = asks.drop(1)
            context.startFirst(appInfoIntents(context))
        },
        onAllowAddon = {
            asks = asks.drop(1)
            requestAiExemption()
        },
        onNotNow = {
            // Not now to Run when locked is kept: not offered again; the AI tab's Locked phone row keeps the way to it.
            if (asks.firstOrNull() == AiAsk.ADDON) exemptions.update { it.turnedDown(KjbAddons.AI) }
            asks = asks.drop(1)
        },
    )
}

/**
 * The window under KJB's pages in [variant]'s page colour, and the system bars' icons in its ink, whatever the phone's
 * own theme: the bars stay readable with Light on a dark phone, and the window matches the page around a page moving.
 * The [chosen] theme is kept for the next start's window ([ThemeCopy]).
 */
@Composable
private fun ThemeWindow(variant: ThemeVariant, chosen: DisplaySettings) {
    val window = LocalActivity.current?.window ?: return
    val context = LocalContext.current
    SideEffect { paintWindow(window, variant) }
    LaunchedEffect(chosen) { ThemeCopy.write(context, chosen) }
}

/** [window] in [variant]'s page colour, its system bars' icons in its ink. */
private fun paintWindow(window: Window, variant: ThemeVariant) {
    window.setBackgroundDrawable(variant.colors.background.toArgb().toDrawable())
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !variant.dark
        isAppearanceLightNavigationBars = !variant.dark
    }
}

/**
 * From Android 12 on, [theme] is also Android's night mode for KJB ([nightModeOf]), which Android keeps: the next start's
 * window is in it before KJB draws. The activity takes the change itself (configChanges uiMode), so it is not made again.
 * A theme other than the one drawn [before] redraws the home-screen widgets, which take the app's night mode when drawn.
 */
@Composable
private fun NightMode(theme: ThemeChoice, before: ThemeChoice) {
    val context = LocalContext.current
    var drawn by remember { mutableStateOf(before) }
    LaunchedEffect(theme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightModeOf(theme))
        if (theme != drawn) {
            drawn = theme
            Widgets.refresh(context)
        }
    }
}

/**
 * The theme last drawn, copied into a small preferences file beside settings.json: read before anything is drawn, it
 * paints the window of the next start, which settings.json (a DataStore, read off the main thread) cannot in time.
 */
private object ThemeCopy {
    private const val FILE: String = "theme-copy"
    private const val THEME: String = "theme"
    private const val PURE_BLACK: String = "pureBlack"

    fun read(context: Context): DisplaySettings {
        // One small file, read once before the first frame, so the window is right from the start.
        val policy = StrictMode.allowThreadDiskReads()
        return try {
            val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            storedDisplay(stored.getString(THEME, null), stored.getBoolean(PURE_BLACK, false))
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    fun write(context: Context, display: DisplaySettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(THEME, display.theme.name).putBoolean(PURE_BLACK, display.pureBlack) }
    }
}

/** The choice as the copy kept for the start window has it: [theme] by name, a name this version does not know as System. */
fun storedDisplay(theme: String?, pureBlack: Boolean): DisplaySettings =
    DisplaySettings(ThemeChoice.entries.firstOrNull { it.name == theme } ?: ThemeChoice.SYSTEM, pureBlack)

/** Lines of the AI's run log its dialog shows: a night or two. */
private const val RUN_LOG_LINES = 300

private const val DAY_MS: Long = 24 * 60 * 60_000L
