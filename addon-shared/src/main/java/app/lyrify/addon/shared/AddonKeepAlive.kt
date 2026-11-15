package app.lyrify.addon.shared

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * What keeps an add-on at work while the phone would rather it stopped (lyrify-bench handoff/addon-freeze-research.md,
 * AD9): a phone's app freezer goes by the add-on's own state, not by who binds it. Samsung's froze both add-ons mid-job,
 * KJB in the foreground or in its own foreground service, and other makers ship their own. Two things count there: the
 * add-on's own foreground service while it works ([JobForeground]), and the add-on left out of the phone's battery
 * optimisation, which it asks for itself ([ExemptionActivity]), KJB offering it. Should a phone freeze it all the same,
 * its beats stop ([beating]), and KJB ends the job in seconds instead of minutes.
 */
object AddonKeepAlive {
    /**
     * KJB's start of an add-on's service for the foreground carries a PendingIntent KJB made, under this name: what a tap
     * on the add-on's notice opens (KJB's page for it), and, as Android names its creator, the proof that KJB sent it.
     */
    const val EXTRA_TAP: String = "app.lyrify.addon.extra.TAP"

    /** How often an add-on's job tells KJB it is alive ([beating]). */
    const val BEAT_EVERY_MS: Long = 5_000

    /** The silence KJB takes for a freeze once a job beats: 6 beats missed. */
    const val FROZEN_MS: Long = 30_000

    /** The tap a start brings ([EXTRA_TAP]); null when there is none or it cannot be read. */
    fun tap(intent: Intent): PendingIntent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_TAP, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TAP)
        }
    } catch (_: RuntimeException) {
        // Extras another app put there that do not unparcel (BadParcelableException): no tap, so the start is refused.
        null
    }

    /** [tapFromKjb] for a start's [tap], as Android tells its creator and, from Android 12, whether it is immutable. */
    fun fromKjb(tap: PendingIntent, callers: CallerCheck): Boolean =
        tapFromKjb(tap.creatorUid, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tap.isImmutable else null, callers)
}

/**
 * A job's beats, apart from its own work: one at once, then every [AddonKeepAlive.BEAT_EVERY_MS] on [context] (the main
 * thread on the phone, which no step blocks), until the returned job is cancelled at the job's end. A process the phone
 * froze says nothing at all, so KJB tells a freeze (no beat) from a step that takes minutes (beats, no progress).
 */
fun CoroutineScope.beating(context: CoroutineContext, beat: () -> Unit): Job = launch(context) {
    while (true) {
        beat()
        delay(AddonKeepAlive.BEAT_EVERY_MS)
    }
}

/**
 * Whether a start's tap is KJB's: made by KJB ([callers] on its creator's uid) and, from Android 12, immutable, so that
 * nobody it passes through (an app that reads the add-on's notice) can fill it in. [immutable] is null before Android 12,
 * which cannot tell.
 */
fun tapFromKjb(creatorUid: Int, immutable: Boolean?, callers: CallerCheck): Boolean = callers.allows(creatorUid) && immutable != false

/** What [JobForeground] has Android do for the service; [Tap] is what a tap on its notice opens (KJB's PendingIntent). */
interface ForegroundPort<Tap : Any> {
    /** Service.startForeground with the add-on's notice, of [type] (ServiceInfo.FOREGROUND_SERVICE_TYPE_*); throws what Android throws. */
    fun startForeground(type: Int, tap: Tap?)

    fun stopForeground()

    /** Service.stopSelf of [startId], or of every start when null. */
    fun stopSelf(startId: Int?)
}

/**
 * An add-on's own foreground service while it works, of [type]. KJB starts the service for the foreground once the add-on
 * took a job ([start]: Context.startForegroundService on its explicit name, under KJB's own foreground), and the add-on
 * goes into the foreground at once, in onStartCommand, as Android requires within seconds. It holds it while a job runs
 * ([busy]), and leaves it when none is left ([jobEnded]), when KJB lets go ([unbound]), and when Android's time for its
 * type runs out ([timeUp], Android 15+), which also ends the work under way ([endJob]). A start from anyone but KJB, by the
 * creator of the [Tap] it brings ([fromKjb]), is refused. Nothing here throws: a phone that refuses the foreground service
 * leaves the job to run without it.
 */
class JobForeground<Tap : Any>(
    private val type: Int,
    private val port: ForegroundPort<Tap>,
    /** Whether a job runs now. */
    private val busy: () -> Boolean,
    /** Whether KJB made [Tap] (its creator's uid and certificate, [CallerCheck]). */
    private val fromKjb: (Tap) -> Boolean,
    /** Ends the work under way, as try later. */
    private val endJob: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    /** In the foreground for KJB's job. */
    private var held = false

    /** The last start Android gave: stopSelf of it ends the service's started state, unless a later one came meanwhile. */
    private var lastStart = 0

    /** onStartCommand: [tap] is what the start brought, null when it brought none. */
    @Synchronized
    fun start(startId: Int, tap: Tap?) {
        lastStart = startId
        val kjb = tap?.takeIf(fromKjb)
        when {
            kjb == null -> {
                log("a start not from KJB was refused")
                // Android ends a service started for the foreground that stays out of it: one in it for KJB's job has
                // nothing to answer, any other goes in and out at once.
                if (!held) inAndOut(startId, tap = null)
            }
            busy() -> if (enter(kjb)) held = true else if (!held) port.stopSelf(startId)
            // The job ended before its start came: in and out, unless still in for it ([jobEnded] is on its way).
            !held -> inAndOut(startId, kjb)
            // Held: nothing to answer. A start for the foreground that Android delivers while the service is in the
            // foreground has its requirement cleared there and then (ActiveServices.sendServiceArgsLocked: "Service
            // already foreground; no new timeout", fgRequired = false), the same in android11 to android16-release
            // (API 30 to 36); one delivered before the earlier start's startForeground waits on that one, which clears
            // it (setServiceForegroundInnerLocked).
        }
    }

    /** A job ended: out of the foreground when no other runs. */
    @Synchronized
    fun jobEnded() {
        if (held && !busy()) leave(lastStart)
    }

    /** KJB let go of the add-on (onUnbind). */
    @Synchronized
    fun unbound() {
        if (held) leave(lastStart)
    }

    /** onTimeout (Android 15+): the work ends as try later, and the service leaves the foreground there and then. */
    @Synchronized
    fun timeUp() {
        log("Android's time for the foreground service ran out")
        endJob()
        leave(startId = null)
    }

    /** Into the foreground; false when Android refuses, and the job runs without it. */
    private fun enter(tap: Tap?): Boolean = try {
        port.startForeground(type, tap)
        true
    } catch (e: RuntimeException) {
        // ForegroundServiceStartNotAllowedException (Android 12+: no exemption at this moment; 15+: the day's time used
        // up) or a SecurityException. KJB's watch covers a phone that freezes the add-on without it.
        log("the foreground service was refused (${e.javaClass.simpleName})")
        false
    }

    private fun inAndOut(startId: Int, tap: Tap?) {
        if (enter(tap)) port.stopForeground()
        port.stopSelf(startId)
    }

    private fun leave(startId: Int?) {
        port.stopForeground()
        port.stopSelf(startId)
        held = false
    }
}

/** One activity that answers an intent: its app, and whether that app is part of the phone's system. */
data class IntentHandler(val packageName: String, val system: Boolean)

/**
 * The app to send the exemption question to: the phone's own, a system app, never another app that claims the same
 * Settings action; null when only others answer.
 */
fun systemHandler(handlers: List<IntentHandler>): String? = handlers.firstOrNull { it.system }?.packageName

/** What the exemption activity does first: refuse a caller that is not KJB, answer at once when exempt, else ask. */
enum class ExemptionStep { REFUSE, DONE, ASK }

/** [caller]: the uid that started the activity for its result, null when unknown (not started for a result). */
fun exemptionStep(caller: Int?, callers: CallerCheck, exempt: Boolean): ExemptionStep = when {
    caller == null || !callers.allows(caller) -> ExemptionStep.REFUSE
    exempt -> ExemptionStep.DONE
    else -> ExemptionStep.ASK
}

/**
 * An add-on's request to be left out of the phone's battery optimisation, for itself: a phone's app freezer leaves such
 * an app alone with the screen locked (phone session 10: the add-on frozen in doze with only KJB's foreground service,
 * at work when exempt). KJB starts it for its result, behind the add-on's own signature permission, and the caller is
 * checked again here ([exemptionStep]); no window of its own (a translucent theme). Android's own question for this
 * package, then RESULT_OK when the add-on is exempt and RESULT_CANCELED when not; an add-on already exempt answers at
 * once. Asked for its own package, it never depends on Settings taking a request one app makes for another.
 */
abstract class ExemptionActivity : Activity() {
    /** KJB of this build type, by its uid and certificate. */
    protected abstract val callers: CallerCheck

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Made again while Android's question shows (a turned phone): its answer comes to onActivityResult.
        if (savedInstanceState != null) return
        when (exemptionStep(caller(), callers, exempt())) {
            ExemptionStep.REFUSE -> answer(RESULT_CANCELED)
            ExemptionStep.DONE -> answer(RESULT_OK)
            ExemptionStep.ASK -> ask()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // What Android's screen answered matters less than what the phone now says.
        answer(if (exempt()) RESULT_OK else RESULT_CANCELED)
    }

    /** Who started it, as Android tells: a package only for a start for a result. */
    private fun caller(): Int? = callingPackage?.let { name ->
        try {
            packageManager.getPackageUid(name, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Android's question for this package; a phone whose settings lack it gets the list where it is set. Each goes to the
     * phone's own settings ([systemHandler]), never to another app that claims the action; what it answers is not
     * trusted either: the phone is asked again ([onActivityResult]). Distributed outside Google Play, and work KJB starts
     * must go on while the phone is locked: the reason Android asks for.
     */
    @SuppressLint("BatteryLife")
    private fun ask() {
        val question = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
        for (intent in listOf(question, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            val settings = systemHandler(handlers(intent)) ?: continue
            try {
                startActivityForResult(Intent(intent).setPackage(settings), ASKED)
                return
            } catch (_: ActivityNotFoundException) {
                // The next way, if any.
            }
        }
        answer(RESULT_CANCELED)
    }

    /** The activities that answer [intent], as the add-on's <queries> lets it see them. */
    private fun handlers(intent: Intent): List<IntentHandler> {
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return found.map { IntentHandler(it.activityInfo.packageName, it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
    }

    private fun exempt(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun answer(result: Int) {
        setResult(result)
        finish()
    }

    private companion object {
        const val ASKED = 1
    }
}
