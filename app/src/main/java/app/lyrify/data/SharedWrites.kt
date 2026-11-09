package app.lyrify.data

import app.lyrify.BuildConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Every kind of change KJB makes to shared storage, the user's own files: each one asks [SharedWrites] first. Kinds
 * a later milestone adds are listed here before their writer exists, so the list is the whole picture.
 */
enum class SharedWrite {
    /** A new `.lrc` next to a song (lyrics/LyricsPipeline.kt, SidecarFile). */
    LRC_CREATE,

    /** An existing `.lrc` replaced, after the user saved it in the sync editor (3.0, lyrics/LrcEdits.kt). */
    LRC_REPLACE,

    /** A `.lrc` KJB wrote, deleted when the user removes it or trashes its song, or KJB's own interrupted write. */
    LRC_DELETE,

    /** A new tone file in Ringtones/, Alarms/ or Notifications/ (tones/ToneFiles.kt). */
    TONE_SAVE,
    TONE_RENAME,
    TONE_DELETE,

    /** Tags and cover written into a song (3.0, tags/SafeWriter.kt). */
    TAG_WRITE,

    /** A song's backup written back over it: a failed write or Undo (3.0, tags/). */
    TAG_RESTORE,

    /** A playlist exported as `.m3u8` (3.0, backup/M3u.kt; the system save dialog picks the place). */
    M3U_EXPORT,

    /** Songs moved to the phone's trash through its own dialog (dupes/Duplicates.kt, MediaStoreTrash). */
    TRASH,

    /** Songs taken back out of the phone's trash, through its own dialog. */
    UNTRASH,
}

/**
 * The one checkpoint for changes to shared storage: every writer asks [allows] (or [allowsIds]) before it changes a
 * file, and does nothing when refused. A release build allows everything, as 2.0 did. A debug build (app.lyrify.debug,
 * beside the user's release app on the same phone) allows only files under [TEST_FOLDER] on any volume, reached through
 * no link ([onDisk]), so tests on the phone never touch the user's music, and its library readers list only that folder
 * ([lists]). New writers take one in their constructor; SharedWritesTest lists every writer in the app and fails for one
 * it does not know.
 *
 * Refusals are logged by kind only, never with a path.
 */
class SharedWrites(
    /** Only [TEST_FOLDER] may change, and only it is listed: the debug build's rule. */
    val testFolderOnly: Boolean,
    /** Where the phone's absolute paths start on disk: `/`, or a stand-in folder in tests. */
    private val root: Path = Paths.get("/"),
    private val log: (String) -> Unit = {},
) {
    /** Whether [write] may change the file at the absolute [path]. */
    fun allows(write: SharedWrite, path: String): Boolean {
        if (!testFolderOnly || onDisk(path)) return true
        log("shared storage: $write refused, this build changes only the test folder")
        return false
    }

    /**
     * Whether [write] may change every file with a MediaStore id in [ids]. [paths] gives their absolute paths (null when
     * MediaStore cannot say); it is asked only when the rule needs them, so a release build makes no extra query.
     */
    fun allowsIds(write: SharedWrite, ids: Collection<Long>, paths: (Collection<Long>) -> Map<Long, String>?): Boolean {
        if (!testFolderOnly) return true
        val found = paths(ids)
        if (found != null && ids.all { id -> found[id]?.let(::onDisk) == true }) return true
        log("shared storage: $write refused, this build changes only the test folder")
        return false
    }

    /** Whether a library reader lists the song at the absolute [path]: a debug build lists only [TEST_FOLDER]. */
    fun lists(path: String): Boolean = !testFolderOnly || onDisk(path)

    /**
     * Whether [path] is in [TEST_FOLDER] by its text ([inTestFolder]) and on disk: every folder on the way that exists is
     * a real folder, not a link, and the path with links resolved stays in [TEST_FOLDER] (so the file itself may be a
     * link only to a file there). A link would let a create or a delete reach a file elsewhere. Refused when the disk
     * cannot say.
     */
    private fun onDisk(path: String): Boolean {
        if (!inTestFolder(path)) return false
        return try {
            val base = root.toRealPath()
            val names = Paths.get(path).normalize().map(Path::toString)
            // The deepest path that exists, reached through real folders only, and the names below it.
            var found = base
            var below = names
            for ((index, name) in names.withIndex()) {
                val next = found.resolve(name)
                val attributes = try {
                    Files.readAttributes(next, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    break
                }
                if (index < names.lastIndex && !attributes.isDirectory) return false
                found = next
                below = names.drop(index + 1)
            }
            val real = below.fold(found.toRealPath()) { at, name -> at.resolve(name) }
            real.startsWith(base) && inTestFolder("/" + base.relativize(real).joinToString("/"))
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        /** The folder a debug build may change, relative to a volume's root (as MediaStore's RELATIVE_PATH has it). */
        const val TEST_FOLDER: String = "Music/KJB test/"

        /** Everywhere, as 2.0: release builds, and tests of writers that are not about this rule. */
        val ANYWHERE: SharedWrites = SharedWrites(testFolderOnly = false)

        /** This build's rule: [TEST_FOLDER] only in a debug build, anywhere in a release build. */
        fun forThisBuild(log: (String) -> Unit): SharedWrites = SharedWrites(testFolderOnly = BuildConfig.DEBUG, log = log)

        /**
         * Whether the absolute [path] names a file in [TEST_FOLDER] or below it, on any volume: `/storage/emulated/<user>/`
         * or `/storage/<volume id>/`, the paths MediaStore gives. Judged by the text, with `.` and `..` resolved and the
         * folder's spelling exact; any other form of path (`/sdcard/`, `/storage/self/`, relative) is outside.
         */
        fun inTestFolder(path: String): Boolean {
            val normal = try {
                Paths.get(path).normalize()
            } catch (_: InvalidPathException) {
                return false
            }
            if (!normal.isAbsolute) return false
            val names = normal.map(Path::toString)
            if (names.size < 2 || names[0] != "storage") return false
            // How many names the volume's root takes: storage/emulated/<user>, or storage/<volume id>.
            val root = when (names[1]) {
                "self" -> return false
                "emulated" -> if (names.getOrNull(2)?.let { it.isNotEmpty() && it.all(Char::isDigit) } == true) 3 else return false
                else -> 2
            }
            val inVolume = names.drop(root)
            return inVolume.size > TEST_FOLDER_NAMES.size && inVolume.take(TEST_FOLDER_NAMES.size) == TEST_FOLDER_NAMES
        }

        private val TEST_FOLDER_NAMES: List<String> = TEST_FOLDER.trimEnd('/').split('/')
    }
}
