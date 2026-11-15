package app.lyrify.crash

import android.content.Context
import android.os.Build
import android.os.Looper
import app.lyrify.BuildConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// The crash log (features-2026-11-15, item 7): the last crashes kept on the phone, never sent by KJB. Each holds the stack
// trace, KJB's and Android's versions and the phone's model; never a title, a path or lyrics, so every exception message
// in the trace is scrubbed ([CrashScrub]). Settings > About > Crash reports lists them, shows one, shares it and clears them.

/**
 * One crash as KJB keeps it: when ([at], wall-clock millis), KJB's version ([app]: name and code), Android's ([android]:
 * release and SDK), the phone's maker and model ([device]), whether the main thread died, and the scrubbed [trace].
 */
data class CrashReport(val at: Long, val app: String, val android: String, val device: String, val mainThread: Boolean, val trace: String) {
    /** As written to its file and shared: a short header, a blank line, the trace. */
    fun text(zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("KJB ").append(app).append('\n')
        append("Android ").append(android).append('\n')
        append("Phone: ").append(device).append('\n')
        append("Time: ").append(TIME.format(Instant.ofEpochMilli(at).atZone(zone))).append('\n')
        append("Thread: ").append(if (mainThread) "main" else "background").append("\n\n")
        append(trace)
        if (!trace.endsWith('\n')) append('\n')
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
    }
}

/** A crash kept: its file's name ([id]), when it happened, the KJB version it happened in, and its exception's class. */
data class CrashEntry(val id: String, val at: Long, val app: String, val headline: String)

/**
 * What a crash keeps of its exceptions: each one's class and frames (code, never data), and no message text at all, as a
 * message can quote a file, a tag or a line of lyrics. Only a few framework messages that hold no text of their own
 * stay: Android's reason a file did not open (its path dropped), an index out of bounds, and a null reference's method.
 */
object CrashScrub {
    /** Frames kept of one exception; a deep recursion's thousands say no more than these. */
    private const val MAX_FRAMES: Int = 64

    /** Causes followed. */
    private const val MAX_CAUSES: Int = 8

    /** The reason libcore gives after the path when a file does not open: kept, the path not. */
    private val OPEN_FAILED = Regex(""": open failed: ([A-Z0-9]{2,16}) \(([A-Za-z ]{1,60})\)$""")

    /**
     * Framework messages made only of numbers and code, kept whole: an index out of bounds, and the member a null
     * reference was used for, as ART names it (a type, then a dotted name of two dots or more, maybe with its parameters).
     */
    private val SAFE = listOf(
        Regex("""Index -?\d+ out of bounds for length \d+"""),
        Regex(
            """Attempt to (?:invoke (?:virtual|interface|static|direct|super) method|read from field|write to field) """ +
                """'[\w.$<>\[\]]+ [\w$]+(?:\.[\w$<>]+){2,}(?:\([\w.$<>\[\], ]*\))?' on a null object reference""",
        ),
    )

    /** [text], an exception's message, as a crash keeps it: one of the framework messages above, else null. */
    fun message(text: String?): String? {
        val line = text?.lineSequence()?.firstOrNull()?.trim() ?: return null
        OPEN_FAILED.find(line)?.let { return "open failed: ${it.groupValues[1]} (${it.groupValues[2]})" }
        return line.takeIf { kept -> SAFE.any { it.matches(kept) } }
    }

    /**
     * [error]'s stack trace as Java prints it ("Caused by:", "Suppressed:", "... n more"), each exception named by its
     * class with its [message] scrubbed, at most [MAX_FRAMES] frames each and [MAX_CAUSES] causes.
     */
    fun trace(error: Throwable): String = buildString {
        write(error, prefix = "", indent = "", enclosing = emptyArray())
        var cause = error.cause
        var frames = error.stackTrace
        var causes = 0
        while (cause != null && cause !== error && causes < MAX_CAUSES) {
            write(cause, prefix = "Caused by: ", indent = "", enclosing = frames)
            frames = cause.stackTrace
            cause = cause.cause
            causes++
        }
    }

    private fun StringBuilder.write(error: Throwable, prefix: String, indent: String, enclosing: Array<StackTraceElement>) {
        append(indent).append(prefix).append(error.javaClass.name)
        message(error.message)?.let { append(": ").append(it) }
        append('\n')
        val frames = error.stackTrace
        // Frames the enclosing trace ends with too are said once, as "... n more".
        var common = 0
        while (common < frames.size && common < enclosing.size && frames[frames.size - 1 - common] == enclosing[enclosing.size - 1 - common]) common++
        val own = frames.size - common
        frames.take(minOf(own, MAX_FRAMES)).forEach { append(indent).append("\tat ").append(it).append('\n') }
        val left = frames.size - minOf(own, MAX_FRAMES)
        if (left > 0) append(indent).append("\t... ").append(left).append(" more\n")
        if (indent.isEmpty()) error.suppressed.take(MAX_CAUSES).forEach { write(it, prefix = "Suppressed: ", indent = "\t", enclosing = frames) }
    }
}

/**
 * The crash reports in app-private [folder], one text file each (`crash-<millis>-<sequence>.txt`), the [keep] newest kept.
 * Written whole or not at all (aside, then moved). Blocking; [write] runs on the thread that is dying.
 */
class CrashStore(private val folder: File, private val keep: Int = KEEP) {
    private val sequence = AtomicInteger()

    fun write(report: CrashReport) {
        folder.mkdirs()
        // Zero-padded, so the names sort by time and, within one millisecond, in the order written.
        val time = report.at.coerceAtLeast(0).toString().padStart(MILLIS_DIGITS, '0')
        var name: String
        do {
            name = "$PREFIX$time-${(sequence.incrementAndGet() % MAX_SEQUENCE).toString().padStart(SEQUENCE_DIGITS, '0')}$SUFFIX"
        } while (File(folder, name).exists())
        val aside = File(folder, name + PART)
        aside.writeText(report.text())
        Files.move(aside.toPath(), File(folder, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        files().drop(keep).forEach { it.delete() }
    }

    /** The crashes kept, the newest first. */
    fun list(): List<CrashEntry> = files().mapNotNull { file ->
        val at = file.name.removePrefix(PREFIX).substringBefore('-').toLongOrNull() ?: return@mapNotNull null
        val lines = try {
            file.readLines()
        } catch (_: IOException) {
            return@mapNotNull null
        }
        val app = lines.firstOrNull()?.removePrefix("KJB ").orEmpty()
        val headline = lines.dropWhile { it.isNotEmpty() }.drop(1).firstOrNull()?.substringBefore(':').orEmpty()
        CrashEntry(file.name, at, app, headline)
    }

    /** Crash [id]'s text; null when it is gone or [id] names no crash file of this folder. */
    fun read(id: String): String? {
        if (!NAME.matches(id)) return null
        return try {
            File(folder, id).takeIf { it.isFile }?.readText()
        } catch (_: IOException) {
            null
        }
    }

    /** Clear: every crash goes; true when none is left. */
    fun clear(): Boolean = files().map { it.delete() }.all { it } && files().isEmpty()

    /** The crash files, the newest first (their names sort by time). */
    private fun files(): List<File> = folder.listFiles { file -> NAME.matches(file.name) }.orEmpty().sortedByDescending { it.name }

    companion object {
        const val KEEP: Int = 10
        private const val PREFIX = "crash-"
        private const val SUFFIX = ".txt"
        private const val PART = ".part"
        private const val MILLIS_DIGITS = 13
        private const val SEQUENCE_DIGITS = 6
        private const val MAX_SEQUENCE = 1_000_000
        private val NAME = Regex("""crash-\d{13}-\d{6}\.txt""")
    }
}

/**
 * The default handler of uncaught exceptions while KJB runs: [record] writes the crash, then the handler there was before
 * ([previous], Android's, which ends the process) gets it as always. Writing never stops the crash from going on.
 */
class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?, private val record: (Thread, Throwable) -> Unit) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            record(thread, error)
        } catch (_: Throwable) {
            // A crash that cannot be written (a full disk, say) is only lost; it never hides the crash itself.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }
}

/** The crash log of this process: installed once, before anything else runs (LyrifyApplication). */
object CrashLog {
    @Volatile
    private var store: CrashStore? = null

    /** KJB's crash reports: app-private `files/crashes`, never backed up or sent. */
    fun store(context: Context): CrashStore = store ?: synchronized(this) {
        store ?: CrashStore(File(context.applicationContext.filesDir, "crashes")).also { store = it }
    }

    /** Puts KJB's handler before the one there is; a second call changes nothing. */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        val crashes = store(context)
        val app = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(previous) { thread, error ->
                crashes.write(CrashReport(System.currentTimeMillis(), app, android, device, thread === Looper.getMainLooper().thread, CrashScrub.trace(error)))
            },
        )
    }
}
