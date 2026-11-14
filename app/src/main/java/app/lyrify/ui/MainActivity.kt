package app.lyrify.ui

import android.Manifest
import android.app.UiModeManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.content.Intent
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
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
import app.lyrify.addon.Addon
import app.lyrify.addon.AddonDownloads
import app.lyrify.addon.AddonInstalls
import app.lyrify.addon.HandoverState
import app.lyrify.addon.ModelHandover
import app.lyrify.models.NpuChips
import app.lyrify.overnight.AiLyrics
import app.lyrify.overnight.AiQueue
import app.lyrify.overnight.audioWithoutLyrics
import app.lyrify.settings.ThemeChoice
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        open(intent)
        // The stored AI switch is the truth: a schedule that got lost is made again.
        lifecycleScope.launch(logFailures) { AiLyrics.reconcile(applicationContext) }
        val ledger = LedgerRepository(this)
        val display = Lyrify.settings(this).settings.map { it.display }.distinctUntilChanged()
        setContent {
            // Nothing is drawn until the stored theme is read (the app waits for the settings anyway): KJB's first frame
            // is in the chosen theme, never the phone's first, and the window behind it is that theme's page.
            val chosen = display.collectAsStateWithLifecycle(initialValue = null).value ?: return@setContent
            val variant = themeVariant(chosen, isSystemInDarkTheme())
            ThemeWindow(variant)
            NightMode(chosen.theme)
            LyrifyTheme(display = chosen) {
                // The app's one message queue: the shell shows it above the dock, a sheet or full screen above its buttons.
                val a11y = LocalAccessibilityManager.current
                CompositionLocalProvider(LocalToasts provides remember { ToastState(a11y) }) {
                    App(ledger, destination.collectAsStateWithLifecycle().value, view.collectAsStateWithLifecycle().value, { view.value = null }) { destination.value = null }
                }
            }
        }
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
    private fun open(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_DESTINATION) ?: return
        intent.removeExtra(EXTRA_DESTINATION)
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        destinationPage(name)?.let { destination.value = it }
        destinationView(name)?.let { view.value = it }
    }
}

/**
 * The first-run screen while [setupShows] says so, then the app's shell, opened at [destination] when a notification
 * asked for a page; collects what every screen shows.
 */
@Composable
private fun App(ledger: LedgerStore, destination: Page?, view: FullScreen?, onViewOpened: () -> Unit, onDestinationOpened: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { Lyrify.settings(context) }
    var access by remember { mutableStateOf(Access.of(context)) }
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    var notifications by remember { mutableStateOf(notificationsAllowed(context)) }

    // The grants are switched in Settings; check again whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        access = Access.of(context)
        unrestricted = isUnrestricted(context)
        notifications = notificationsAllowed(context)
        // An app in the foreground gets its daily time for long media work back.
        AiLyrics.appOpened(context)
        // Back from the phone's "Install unknown apps" switch: the add-on's install goes on.
        AddonInstalls.installer(context).resume()
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
    // What the AI asks for once it is switched on or its download starts: Show progress, then Keep running.
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
    val askFor: (Boolean) -> Unit = { switchedOn ->
        asks = aiAsks(switchedOn, notificationGrant(Build.VERSION.SDK_INT, notifications, denied), unrestricted)
    }
    val state by ledger.ledger.collectAsStateWithLifecycle(initialValue = LedgerState())
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val sweep by NowPlaying.sweep.collectAsStateWithLifecycle()
    val connected by NowPlaying.connected.collectAsStateWithLifecycle()
    // The AI add-on and its models, bound while KJB shows (on a phone the AI runs on only).
    val addon by remember(context) { if (NpuChips.supported() != null) Addon.views(context) else flowOf(null) }.collectAsStateWithLifecycle(initialValue = null)
    val install by remember(context) { AddonInstalls.installer(context).state }.collectAsStateWithLifecycle()
    // KJB runs the add-on's download: whether it waits for Wi-Fi, or failed, is KJB's to say.
    val downloadPhase by remember(context) { AddonDownloads.phase(context) }.collectAsStateWithLifecycle(initialValue = null)
    val handover by remember(context) { ModelHandover.state(context) }.collectAsStateWithLifecycle(initialValue = HandoverState.None)
    val aiEnabled by remember(context) { AiLyrics.enabled(context) }.collectAsStateWithLifecycle()
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
            )
        }
    }
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
        onNotNow = { asks = asks.drop(1) },
    )
}

/**
 * The window under KJB's pages in [variant]'s page colour, and the system bars' icons in its ink, whatever the phone's
 * own theme: the bars stay readable with Light on a dark phone, and the window matches the page around a page moving.
 */
@Composable
private fun ThemeWindow(variant: ThemeVariant) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val page = variant.colors.background.toArgb()
    SideEffect {
        window.setBackgroundDrawable(ColorDrawable(page))
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !variant.dark
            isAppearanceLightNavigationBars = !variant.dark
        }
    }
}

/**
 * From Android 12 on, [theme] is also Android's night mode for KJB ([nightModeOf]), which Android keeps: the next start's
 * window is in it before KJB draws. The activity takes the change itself (configChanges uiMode), so it is not made again.
 */
@Composable
private fun NightMode(theme: ThemeChoice) {
    val context = LocalContext.current
    LaunchedEffect(theme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightModeOf(theme))
    }
}

/** Lines of the AI's run log its dialog shows: a night or two. */
private const val RUN_LOG_LINES = 300

private const val DAY_MS: Long = 24 * 60 * 60_000L
