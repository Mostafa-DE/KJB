package app.lyrify.addon

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/**
 * An add-on's service as `dumpsys activity services` shows it on the phone (its record, foreground state and the bindings
 * to it; its notice carries no song or link), for the device tests that check it works in its own foreground service
 * while it has a job (AD9: a phone's app freezer stopped the add-ons otherwise).
 */
object AddonServices {
    /** The foreground types as the dump prints them: mediaProcessing (Android 15+) and dataSync. */
    const val MEDIA_PROCESSING: String = "0x00002000"
    const val DATA_SYNC: String = "0x00000001"

    private const val WAIT_MS = 5_000L
    private const val LOOK_EVERY_MS = 250L

    /**
     * [addon]'s service once its foreground state is [foreground], looked at for up to 5 s: KJB starts the service right
     * after the add-on takes a job, and it leaves the foreground right after the job's end.
     */
    fun once(addon: KjbAddon, foreground: Boolean): String {
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        var dump = now(addon)
        while (("isForeground=true" in dump) != foreground && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(LOOK_EVERY_MS)
            dump = now(addon)
        }
        return dump
    }

    fun now(addon: KjbAddon): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys activity services ${addon.packageName}"),
        ).bufferedReader().use { it.readText() }

    /** The dump's lines worth a log line: the foreground state, why Android allowed it, and the bindings. */
    fun summary(dump: String): String =
        dump.lines().filter { "isForeground=" in it || "infoAllowStartForeground=" in it || "ConnectionRecord{" in it }.joinToString(" | ") { it.trim() }
}
