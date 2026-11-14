package app.lyrify.crash

import android.content.Context
import android.content.Intent
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
 * What a crash keeps of its exceptions: each one's class and frames (code, never data), and of its message only what
 * cannot name a song: the first line, with quoted text, addresses and paths taken out, and nothing at all when it names an
 * audio or lyrics file. Strict on purpose: a message can quote a file, a tag or a line of lyrics.
 */
object CrashScrub {
    /** The longest message kept. */
    private const val MAX_MESSAGE: Int = 300

    /** Frames kept of one exception; a deep recursion's thousands say no more than these. */
    private const val MAX_FRAMES: Int = 64

    /** Causes followed. */
    private const val MAX_CAUSES: Int = 8

    private val QUOTED = Regex("\"[^\"]*\"|“[^”]*”")
    private val ADDRESS = Regex("""\b[A-Za-z][A-Za-z0-9+.-]*://\S*""")

    /** A path: the start of the first word holding a slash. It runs to the end of the line, as a file's name may hold spaces. */
    private val PATH = Regex("""\S*/""")

    /** The reason Android's file calls give after a path: kept. */
    private val OPEN_FAILED = Regex(""": open failed: [A-Z0-9]+ \([^()/]*\)$""")
    private val MEDIA_FILE = Regex("""\.(mp3|flac|m4a|mp4|aac|ogg|oga|opus|wav|wma|aiff?|ape|alac|lrc|m3u8?)\b""", RegexOption.IGNORE_CASE)

    /** [text], an exception's message, as a crash keeps it; null without one. */
    fun message(text: String?): String? {
        var line = text?.lineSequence()?.firstOrNull()?.trim() ?: return null
        line = QUOTED.replace(line, "\"<text>\"")
        line = ADDRESS.replace(line, "<uri>")
        PATH.find(line)?.let { start ->
            val tail = OPEN_FAILED.find(line, start.range.first)?.value.orEmpty()
            line = line.substring(0, start.range.first) + "<path>" + tail
        }
        if (MEDIA_FILE.containsMatchIn(line)) return "<file>"
        return line.take(MAX_MESSAGE)
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
 * The crash reports in app-private [folder], one text file each (`crash-<millis>-<n>.txt`), the [keep] newest kept.
 * Written whole or not at all (aside, then moved). Blocking; [write] runs on the thread that is dying.
 */
class CrashStore(private val folder: File, private val keep: Int = KEEP) {
    private val sequence = AtomicInteger()

    fun write(report: CrashReport) {
        folder.mkdirs()
        val name = "$PREFIX${report.at.toString().padStart(MILLIS_DIGITS, '0')}-${System.nanoTime() % NANO_PART}-${sequence.incrementAndGet()}$SUFFIX"
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
        private const val NANO_PART = 1_000_000_000L
        private val NAME = Regex("""crash-\d{13}-\d+-\d+\.txt""")
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

    /**
     * Share: one crash's text through Android's share sheet, only when the user taps Share. A plain text intent until the
     * shared share helper (lane SH1) is merged; then this one function moves to it.
     */
    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "KJB crash report").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
