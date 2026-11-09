package app.lyrify.ui

import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.BuildConfig
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.data.LedgerStore
import app.lyrify.logFailures
import app.lyrify.media.NowPlaying
import app.lyrify.models.ModelDownloads
import app.lyrify.models.NpuChips
import app.lyrify.overnight.AiLyrics
import app.lyrify.overnight.AiQueue
import app.lyrify.overnight.audioWithoutLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Where a notification's tap asked KJB to open, until the shell has opened it. */
    private val destination = MutableStateFlow<Page?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A turn of the phone or a restore after process death delivers the same intent again: it was handled then.
        if (savedInstanceState == null) destination.value = destinationPage(intent.getStringExtra(EXTRA_DESTINATION))
        // The stored AI switch is the truth: a schedule that got lost is made again.
        lifecycleScope.launch(logFailures) { AiLyrics.reconcile(applicationContext) }
        val ledger = LedgerRepository(this)
        setContent {
            LyrifyTheme {
                // The app's one message queue: the shell shows it above the dock, a sheet or full screen above its buttons.
                CompositionLocalProvider(LocalToasts provides remember { ToastState() }) {
                    App(ledger, destination.collectAsStateWithLifecycle().value) { destination.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destinationPage(intent.getStringExtra(EXTRA_DESTINATION))?.let { destination.value = it }
    }
}

/**
 * The first-run screen while [setupShows] says so, then the app's shell, opened at [destination] when a notification
 * asked for a page; collects what every screen shows.
 */
@Composable
private fun App(ledger: LedgerStore, destination: Page?, onDestinationOpened: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { Lyrify.settings(context) }
    var access by remember { mutableStateOf(Access.of(context)) }
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }

    // The grants are switched in Settings; check again whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        access = Access.of(context)
        unrestricted = isUnrestricted(context)
        // An app in the foreground gets its daily time for long media work back.
        AiLyrics.appOpened(context)
        onPauseOrDispose { }
    }

    // Setup and the shell's last tab both come from the settings: wait for their first read.
    val settings = store.settings.collectAsStateWithLifecycle(initialValue = null).value ?: return
    // Null until decided on opening; then the setup screen stays until Continue, and comes back without All files access.
    var setup by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showsSetup = setupShows(access, settings.nav.setupDone, setup)
    SideEffect { setup = showsSetup }
    // Finished with Continue, or skipped by a phone that already had both grants (an update from 2.0): not shown again.
    LaunchedEffect(showsSetup, settings.nav.setupDone) {
        if (!showsSetup && !settings.nav.setupDone) store.update { it.copy(nav = it.nav.copy(setupDone = true)) }
    }
    if (showsSetup) {
        OnboardingScreen(
            access = access,
            onOpenNotificationAccess = { context.startFirst(notificationAccessIntents(context)) },
            onOpenAllFilesAccess = { context.startFirst(allFilesAccessIntents(context)) },
            onOpenAppInfo = { context.startFirst(appInfoIntents(context)) },
            onContinue = { setup = false },
        )
        return
    }
    val state by ledger.ledger.collectAsStateWithLifecycle(initialValue = LedgerState())
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val sweep by NowPlaying.sweep.collectAsStateWithLifecycle()
    val connected by NowPlaying.connected.collectAsStateWithLifecycle()
    val models by remember(context) { ModelDownloads.state(context) }.collectAsStateWithLifecycle(initialValue = null)
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
    AppShell(
        settings = settings,
        onSettings = { change -> scope.launch { if (!store.update(change)) toasts.say(resources, R.string.settings_not_saved) } },
        ledger = state,
        track = track,
        sweep = sweep,
        connected = connected,
        ai = AiState(
            chip = remember { NpuChips.device() },
            newEnough = Build.VERSION.SDK_INT >= ModelDownloads.MIN_SDK,
            models = models,
            enabled = aiEnabled,
            progress = aiProgress,
            hold = aiHold,
            pluggedIn = pluggedIn,
            unrestricted = unrestricted,
            stopping = deletingModels,
        ),
        aiActions = remember(context) {
            AiActions(
                onSwitch = { on -> if (on) AiLyrics.turnOn(context) else AiLyrics.turnOff(context) },
                onDownload = { ModelDownloads.start(context) },
                onDelete = { Lyrify.deleteModels(context) },
                onOpenAppInfo = { context.startFirst(appInfoIntents(context)) },
                readLog = { withContext(Dispatchers.IO) { AiLyrics.runLog(context).tail(RUN_LOG_LINES).joinToString("\n") } },
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
    )
}

/** Lines of the AI's run log its dialog shows: a night or two. */
private const val RUN_LOG_LINES = 300

private const val DAY_MS: Long = 24 * 60 * 60_000L
