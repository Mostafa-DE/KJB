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

    /** A backup zip saved where the user picked, or in the automatic backup folder (3.0, backup/AutoBackup.kt). */
    BACKUP_SAVE,

    /** An automatic backup older than the 5 kept, deleted from its folder (3.0, backup/AutoBackup.kt). */
    BACKUP_DELETE,

    /** Songs moved to the phone's trash through its own dialog (dupes/Duplicates.kt, MediaStoreTrash). */
    TRASH,

    /** Songs taken back out of the phone's trash, through its own dialog. */
    UNTRASH,
}

/**
 * The one checkpoint for changes to shared storage: every writer asks [allows] (or [allowsIds]) before it changes a
 * file, and does nothing when refused. A release build allows everything, as 2.0 did. Every other build (the debug
 * build app.lyrify.debug, and the Baseline Profile's bench builds app.lyrify.bench, beside the user's release app on the
 * same phone) allows only files under [TEST_FOLDER] on any volume, reached through no link ([onDisk]), so runs on the
 * phone never touch the user's music, and its library readers list only that folder ([lists]). New writers take one in
 * their constructor; SharedWritesTest lists every writer in the app and fails for one it does not know.
 *
 * Refusals are logged by kind only, never with a path.
 */
class SharedWrites(
    /** Only [TEST_FOLDER] may change, and only it is listed: the rule of every build but release. */
    val testFolderOnly: Boolean,
    /** Where the phone's absolute paths start on disk: `/`, or a stand-in folder in tests. */
    private val root: Path = Paths.get("/"),
    private val log: (String) -> Unit = {},
) {
    /** Whether [write] may change the file at the absolute [path]. Refused when the disk can't say. */
    fun allows(write: SharedWrite, path: String): Boolean = try {
        allowsOrThrow(write, path)
    } catch (e: IOException) {
        log("shared storage: $write refused, the disk could not say where the file is (${e.javaClass.simpleName})")
        false // safe: a refusal writes nothing; the tag engine asks allowsOrThrow, where a refusal is for good
    }

    /**
     * [allows], except that a disk that can't say throws instead of refusing: for the tag engine's put-back, where a
     * refusal sets a write aside for good and an error must decide nothing.
     */
    fun allowsOrThrow(write: SharedWrite, path: String): Boolean {
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
        if (found != null && ids.all { id -> found[id]?.let(::onDiskOrRefused) == true }) return true
        log("shared storage: $write refused, this build changes only the test folder")
        return false
    }

    /**
     * Whether a library reader lists the song at the absolute [path]: a fenced build lists only [TEST_FOLDER], and the books
     * in [TEST_BOOKS_FOLDER].
     */
    fun lists(path: String): Boolean = !testFolderOnly || onDiskOrRefused(path) || onDiskOrRefused(path, TEST_BOOKS_FOLDER_NAMES)

    /** [onDisk], false when the disk can't say: for what a refusal keeps safe (a write not made, a song not listed). */
    private fun onDiskOrRefused(path: String, folder: List<String> = TEST_FOLDER_NAMES): Boolean = try {
        onDisk(path, folder)
    } catch (_: IOException) {
        false // safe: a refusal: nothing is written or listed
    }

    /**
     * Whether [path] is in [TEST_FOLDER] by its text ([inTestFolder]) and on disk: every folder on the way that exists is
     * a real folder, not a link, and the path with links resolved stays in [TEST_FOLDER] (so the file itself may be a
     * link only to a file there). A link would let a create or a delete reach a file elsewhere. Throws when the disk
     * cannot say.
     */
    private fun onDisk(path: String, folder: List<String> = TEST_FOLDER_NAMES): Boolean {
        if (!inFolder(path, folder)) return false
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
                if (index < names.lastIndex && !attributes.isDirectory) return false // safe: BasicFileAttributes, read with NOFOLLOW_LINKS, which throws
                found = next
                below = names.drop(index + 1)
            }
            val real = below.fold(found.toRealPath()) { at, name -> at.resolve(name) }
            real.startsWith(base) && inFolder("/" + base.relativize(real).joinToString("/"), folder)
        } catch (e: SecurityException) {
            throw IOException("the disk could not be asked", e)
        }
    }

    companion object {
        /** The folder a fenced build may change, relative to a volume's root (as MediaStore's RELATIVE_PATH has it). */
        const val TEST_FOLDER: String = "Music/KJB test/"

        /**
         * The audiobooks a fenced build lists besides [TEST_FOLDER], never changes: books in an Audiobooks folder are only
         * found outside the Music folder (books/Books.kt).
         */
        const val TEST_BOOKS_FOLDER: String = "Audiobooks/KJB test/"

        /** Everywhere, as 2.0: release builds, and tests of writers that are not about this rule. */
        val ANYWHERE: SharedWrites = SharedWrites(testFolderOnly = false)

        /**
         * This build's rule, from its TEST_FOLDER_ONLY flag (app/build.gradle.kts): anywhere in release, [TEST_FOLDER] only
         * in every other build type, the bench builds included, which are not debuggable but must not change the user's music.
         */
        fun forThisBuild(log: (String) -> Unit): SharedWrites = SharedWrites(testFolderOnly = BuildConfig.TEST_FOLDER_ONLY, log = log)

        /**
         * Whether the absolute [path] names a file in [TEST_FOLDER] or below it, on any volume: `/storage/emulated/<user>/`
         * or `/storage/<volume id>/`, the paths MediaStore gives. Judged by the text, with `.` and `..` resolved and the
         * folder's spelling exact; any other form of path (`/sdcard/`, `/storage/self/`, relative) is outside.
         */
        fun inTestFolder(path: String): Boolean = inFolder(path, TEST_FOLDER_NAMES)

        /** Whether the absolute [path] names a file in the folder of [folder]'s names, or below it, on any volume. */
        private fun inFolder(path: String, folder: List<String>): Boolean {
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
            return inVolume.size > folder.size && inVolume.take(folder.size) == folder
        }

        private val TEST_FOLDER_NAMES: List<String> = TEST_FOLDER.trimEnd('/').split('/')
        private val TEST_BOOKS_FOLDER_NAMES: List<String> = TEST_BOOKS_FOLDER.trimEnd('/').split('/')
    }
}
