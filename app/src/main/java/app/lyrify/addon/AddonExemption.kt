package app.lyrify.addon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What KJB keeps of the user's answers about an add-on's battery exemption, by the add-on's package: turned down where it
 * was offered ([notNow]), and paused by the phone at work ([paused]).
 */
data class ExemptionChoices(val notNow: Set<String> = emptySet(), val paused: Set<String> = emptySet()) {
    /** Offered where the add-on is set up, once: while it is not [exempt] and was not turned down. */
    fun offers(addon: KjbAddon, exempt: Boolean): Boolean = !exempt && addon.packageName !in notNow

    /** After the phone paused the add-on at work: a plain hint, until it is [exempt] or the hint is put away ([hintSeen]). */
    fun hints(addon: KjbAddon, exempt: Boolean): Boolean = !exempt && addon.packageName in paused

    /** Not now where it was offered: not offered there again; the add-on's row keeps the way to it. */
    fun turnedDown(addon: KjbAddon): ExemptionChoices = copy(notNow = notNow + addon.packageName)

    /** The phone paused the add-on at work: a freeze KJB saw, or a download that stalled. */
    fun pausedOnce(addon: KjbAddon): ExemptionChoices = copy(paused = paused + addon.packageName)

    /** The hint was answered (Allow or Not now): not said again until the next pause. */
    fun hintSeen(addon: KjbAddon): ExemptionChoices = copy(paused = paused - addon.packageName)
}

/** [ExemptionChoices], kept by [read] and [write] (KJB's preferences on the phone), as a flow the screens follow. */
class ExemptionStore(private val read: () -> ExemptionChoices, private val write: (ExemptionChoices) -> Unit) {
    private val current by lazy { MutableStateFlow(read()) }

    val choices: StateFlow<ExemptionChoices> get() = current.asStateFlow()

    @Synchronized
    fun update(change: (ExemptionChoices) -> ExemptionChoices) {
        val next = change(current.value)
        if (next == current.value) return
        write(next)
        current.value = next
    }
}

/**
 * An add-on's battery exemption, KJB's side (AD9): a phone's app freezer stopped the add-ons at work with the screen
 * locked, even in their own foreground service, and left alone an add-on left out of the phone's battery optimisation
 * (phone session 10). The add-on asks for that itself, for its own package ([intent], its activity, started for a
 * result); KJB offers it where each add-on is set up, keeps a Not now, keeps the way to it on the add-on's row, and says
 * plainly that a locked phone may pause the add-on once that has happened ([ExemptionChoices]). Every phone where the
 * add-on is not exempt, whatever its maker.
 */
object AddonExemptions {
    private const val PREFERENCES = "addon_exemptions"
    private const val NOT_NOW = "not_now"
    private const val PAUSED = "paused"

    @Volatile
    private var store: ExemptionStore? = null

    /** Whether the phone leaves [addon] out of its battery optimisation now (any package may be asked about). */
    fun exempt(context: Context, addon: KjbAddon): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(addon.packageName)

    /** [addon]'s own request, started for its result: RESULT_OK once it is exempt. */
    fun intent(addon: KjbAddon): Intent = Intent().setComponent(ComponentName(addon.packageName, addon.exemption))

    fun store(context: Context): ExemptionStore = store ?: synchronized(this) {
        store ?: run {
            val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            ExemptionStore(
                read = { ExemptionChoices(preferences.getStringSet(NOT_NOW, null).orEmpty(), preferences.getStringSet(PAUSED, null).orEmpty()) },
                write = { choices -> preferences.edit { putStringSet(NOT_NOW, choices.notNow).putStringSet(PAUSED, choices.paused) } },
            )
        }.also { store = it }
    }
}
