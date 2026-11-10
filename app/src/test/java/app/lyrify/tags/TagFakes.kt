package app.lyrify.tags

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Collections

/** The app's death at that moment: an Error, so the writer's failure handling never runs, as with a real kill. */
class SimulatedKill : Error("killed")

/**
 * [TagFile] over TagFixtures MP3s: the ID3v2 tag holds the fields, pictures and frame IDs as JSON, the audio after it
 * stays as it is, and the duration is the audio's size. Like TagLib, a write as ID3v2.3 drops the frames v2.3 can't
 * hold. [events] records each call; the counters show calls running at once. The test can make a write go wrong.
 */
class FakeTagFile(private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())) : TagFile {
    val given = Collections.synchronizedList(mutableListOf<File>())
    var failWrite = false

    /** A write that also changes one byte of the audio: the verify must catch it. */
    var damageAudio = false

    /** A write that drops a field it was given: the verify must catch it. */
    var dropField: String? = null

    /** A write that sets a field on its own (one it was given, or a new one): the verify must catch it. */
    var sneakField: Pair<String, List<String>>? = null

    /** A write that changes the first picture's type, description or mime: the verify must catch it. */
    var alterPicture: ((TagPicture) -> TagPicture)? = null

    /** A write that drops a frame the property map can't show: the verify must catch it. */
    var dropFrame: String? = null

    /** The ID3v2 version of the last write. */
    var lastVersion: Id3Version? = null
    var running = 0
    var mostAtOnce = 0

    @Serializable
    private data class Stored(
        val fields: Map<String, List<String>> = emptyMap(),
        val pictures: List<StoredPicture> = emptyList(),
        val frames: List<String> = emptyList(),
    )

    @Serializable
    private data class StoredPicture(val data: String, val description: String, val type: String, val mime: String)

    override fun read(file: File): SongTags? {
        events += "tags read"
        given += file
        val bytes = file.readBytes()
        val (stored, audio) = split(bytes) ?: return null
        return SongTags(
            stored.fields,
            stored.pictures.map(::picture),
            durationMs = audio.size.toLong() * 10,
            id3v2Version = bytes[3].toInt(),
            frames = stored.frames,
        )
    }

    override fun write(file: File, fields: Map<String, List<String>>, pictures: List<TagPicture>?, id3: Id3Version): Boolean {
        synchronized(this) { running++; mostAtOnce = maxOf(mostAtOnce, running) }
        try {
            Thread.sleep(5)
            events += "tags write"
            given += file
            lastVersion = id3
            if (failWrite) return false
            val (stored, audio) = split(file.readBytes()) ?: return false
            val kept = (dropField?.let { fields - it } ?: fields) + listOfNotNull(sneakField)
            val written = (pictures ?: stored.pictures.map(::picture)).toMutableList()
            alterPicture?.let { change -> if (written.isNotEmpty()) written[0] = change(written[0]) }
            val frames = stored.frames.filterNot { (id3 == Id3Version.V2_3 && it in SafeWriter.DROPPED_BY_V23) || it == dropFrame }
            file.writeBytes(encode(kept, written, id3.number, frames = frames) + audio.copyOf().also { if (damageAudio) it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
            return true
        } finally {
            synchronized(this) { running-- }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun picture(stored: StoredPicture) = TagPicture(Base64.getDecoder().decode(stored.data), stored.description, stored.type, stored.mime)

        /** An ID3v2 tag ([version]) with [fields], [pictures] and [frames] in it, as this fake writes them. */
        fun encode(fields: Map<String, List<String>>, pictures: List<TagPicture> = emptyList(), version: Int = 3, padding: Int = 0, frames: List<String> = emptyList()): ByteArray {
            val stored = Stored(fields, pictures.map { StoredPicture(Base64.getEncoder().encodeToString(it.data), it.description, it.type, it.mime) }, frames)
            return TagFixtures.id3v2(json.encodeToString(Stored.serializer(), stored).encodeToByteArray(), version = version, padding = padding)
        }

        fun song(fields: Map<String, List<String>>, pictures: List<TagPicture> = emptyList(), audio: ByteArray = TagFixtures.mp3Audio(), v1: ByteArray? = null, version: Int = 3, frames: List<String> = emptyList()): ByteArray =
            encode(fields, pictures, version, frames = frames) + audio + (v1 ?: ByteArray(0))

        /** The stored tag and the audio of [bytes] (an ID3v1 tag stays with the audio); null when it is not one. */
        private fun split(bytes: ByteArray): Pair<Stored, ByteArray>? {
            if (bytes.size < 10 || !bytes.copyOf(3).contentEquals("ID3".encodeToByteArray())) return null
            val size = (6..9).fold(0) { sum, i -> (sum shl 7) or bytes[i].toInt() }
            val body = bytes.copyOfRange(10, 10 + size).decodeToString().trimEnd('\u0000')
            val stored = try {
                json.decodeFromString(Stored.serializer(), body)
            } catch (_: IllegalArgumentException) {
                Stored()
            }
            return stored to bytes.copyOfRange(10 + size, bytes.size)
        }
    }
}

/** Fsyncs a folder on the JVM (Linux opens a folder for reading); fails for any folder in [failing]. */
class FolderSync {
    val failing = Collections.synchronizedSet(mutableSetOf<File>())

    /** Asked at each fsync of a folder, so a test can act while the writer or the recovery waits on one. */
    var onSync: (File) -> Unit = {}

    fun sync(dir: File) {
        onSync(dir)
        if (dir.canonicalFile in failing.map { it.canonicalFile }) throw IOException("fsync failed")
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}

/** [BusyFiles] with the answer set by the test: [inUse] paths, or [unknown] when the sessions can't be listed. */
class FakeBusyFiles(private val events: MutableList<String> = mutableListOf()) : BusyFiles {
    val inUse = Collections.synchronizedSet(mutableSetOf<String>())
    var unknown = false

    /** Called on each check, so a test can free a file or make it busy as the batch goes. */
    var onCheck: (String) -> Unit = {}

    override fun check(path: String): Busy {
        events += "busy check"
        onCheck(path)
        return when {
            unknown -> Busy.UNKNOWN
            path in inUse -> Busy.IN_USE
            else -> Busy.FREE
        }
    }
}

/**
 * What the next pass of writing a song in place does, for a test: each pass (the write, then a put-back through the
 * same descriptor, or a recovery's write) takes the next plan when it starts at byte 0; with none, it simply writes.
 * [Refuse] is taken when the song is opened instead.
 */
sealed interface WritePlan {
    /** Throws an IOException after this many bytes. */
    data class FailAfter(val bytes: Long) : WritePlan

    /** The app dies after this many bytes. */
    data class KillAfter(val bytes: Long) : WritePlan

    /** One byte comes out wrong. */
    data object Damage : WritePlan

    /** The app dies when the file is truncated, or fsynced. */
    data object KillOnTruncate : WritePlan
    data object KillOnForce : WritePlan

    /** MediaStore will not open the file. */
    data object Refuse : WritePlan
}

/**
 * [SongFiles] over a temporary folder: [ids] are the songs' MediaStore ids, [scanned] what a rescan answers. The
 * in-place write is a real file channel, which [plans] can make fail, die or damage.
 */
class FakeSongFiles(private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())) : SongFiles {
    val ids = mutableMapOf<String, Long>()
    var mounted = true

    /** File identities to report instead of the real ones, by path: a reused inode, a remount's new device number. */
    val fileIds = mutableMapOf<String, FileId>()

    /** A path whose descriptor opens another file: MediaStore's row naming other bytes than the path. */
    val redirect = mutableMapOf<String, File>()

    /** Asked whenever the writer or the recovery checks a song's volume, with the song's path. */
    var onMounted: (String) -> Unit = {}
    var scanned: (String) -> Long? = { ids[it] }
    val plans = ArrayDeque<WritePlan>()

    /** The app dies during the next scan. */
    var killOnScan = false

    /** Asked on each open, to see what is held then (the recovery must hold the song). */
    var onOpen: () -> Unit = {}

    /** Asked after each read through a descriptor, and before each write through one. */
    var onRead: () -> Unit = {}
    var onWrite: () -> Unit = {}

    /** The next [failReads] reads through a descriptor fail, as an I/O error from the card or from FUSE would. */
    var failReads = 0

    /** The next [endReads] reads through a descriptor find the file ended, early. */
    var endReads = 0

    /** The next [failFileIds] looks at a path (`lstat`) fail. */
    var failFileIds = 0

    /** MediaStore's queries fail (a SecurityException, a bad argument) while this is set. */
    var failIdOf = false
    var running = 0
    var mostAtOnce = 0

    override fun mounted(path: String): Boolean {
        events += "mounted"
        onMounted(path)
        return mounted
    }

    override fun sameFile(mediaStoreId: Long, path: String): Boolean = (ids[path] == mediaStoreId).also { events += "same file" }

    override fun idOf(path: String): Long? = if (failIdOf) throw IOException("MediaStore refused") else ids[path]

    override fun openInPlace(song: SongTarget): OpenSong? {
        events += "open rw"
        onOpen()
        if (plans.firstOrNull() == WritePlan.Refuse) {
            plans.removeFirst()
            return null
        }
        val target = redirect[song.path] ?: File(song.path)
        if (!target.isFile) return null
        synchronized(this) { running++; mostAtOnce = maxOf(mostAtOnce, running) }
        // The descriptor names the file at the path when it was opened, whatever is put at the path later. MediaStore
        // mostly opens it on the file system under the path's (MediaProvider's "lower FS"), so its device number is
        // not the one the path reports.
        val opened = (fileIds[song.path] ?: lstat(song.path)).let { it.copy(dev = it.dev + LOWER_FS) }
        val channel = FileChannel.open(target.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
        return OpenSong(
            PlannedChannel(channel, plans, { onWrite() }) {
                synchronized(this) { running-- }
                events += "closed"
            },
            reader = { buffer, position -> read(channel, buffer, position) },
            sizer = { channel.size() },
        ) { opened }
    }

    override fun openToRead(path: String): OpenSong {
        events += "open r"
        if (!regularFile(File(path))) throw IOException("no song at the path")
        // Opened on the path itself. As on the phone's FUSE, its identity (fstat) is whatever file is at its path now:
        // MediaProvider's daemon answers getattr by lstat of the path (FuseDaemon pf_getattr), never by the open file,
        // while the size and the bytes stay the open file's.
        val channel = FileChannel.open(File(path).toPath(), StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        return OpenSong(channel, reader = { buffer, position -> read(channel, buffer, position) }, sizer = { channel.size() }) { fileIds[path] ?: lstat(path) }
    }

    private fun read(channel: FileChannel, buffer: ByteBuffer, position: Long): Int {
        if (failReads > 0) {
            failReads--
            throw IOException("read failed")
        }
        if (endReads > 0) {
            endReads--
            return -1
        }
        return channel.read(buffer, position).also { onRead() }
    }

    override fun fileId(path: String): FileId {
        if (failFileIds > 0) {
            failFileIds--
            throw IOException("lstat failed")
        }
        return fileIds[path] ?: lstat(path)
    }

    private fun lstat(path: String): FileId {
        val file = File(path).toPath()
        return FileId(
            (java.nio.file.Files.getAttribute(file, "unix:dev", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
            (java.nio.file.Files.getAttribute(file, "unix:ino", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Number).toLong(),
        )
    }

    override suspend fun scan(path: String): Long? {
        events += "scan"
        if (killOnScan) {
            killOnScan = false
            throw SimulatedKill()
        }
        return scanned(path)
    }

    private companion object {
        const val LOWER_FS = 1_000L
    }
}

/** A file channel that writes through to [inner], failing, dying or damaging as each pass's plan from [plans] says. */
private class PlannedChannel(
    private val inner: FileChannel,
    private val plans: ArrayDeque<WritePlan>,
    private val onWrite: () -> Unit,
    private val onClose: () -> Unit,
) : FileChannel() {
    private var plan: WritePlan? = null
    private var written = 0L
    private var damage = false

    override fun write(src: ByteBuffer): Int {
        onWrite()
        val plan = plan
        val limit = when (plan) {
            is WritePlan.FailAfter -> plan.bytes
            is WritePlan.KillAfter -> plan.bytes
            else -> null
        }
        if (limit != null && written + src.remaining() > limit) {
            val part = (limit - written).toInt()
            inner.write(src.duplicate().limit(src.position() + part) as ByteBuffer)
            inner.force(true)
            written += part
            if (plan is WritePlan.KillAfter) throw SimulatedKill()
            throw IOException("write failed")
        }
        if (damage && src.remaining() > 10) {
            val copy = ByteBuffer.allocate(src.remaining()).put(src.duplicate()).flip() as ByteBuffer
            copy.put(5, (copy.get(5) + 1).toByte())
            damage = false
            val n = inner.write(copy)
            src.position(src.position() + n)
            written += n
            return n
        }
        return inner.write(src).also { written += it }
    }

    override fun truncate(size: Long): FileChannel {
        if (plan == WritePlan.KillOnTruncate) throw SimulatedKill()
        inner.truncate(size)
        return this
    }

    override fun force(metaData: Boolean) {
        if (plan == WritePlan.KillOnForce) throw SimulatedKill()
        inner.force(metaData)
    }

    override fun read(dst: ByteBuffer): Int = inner.read(dst)
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = inner.read(dsts, offset, length)
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = srcs.drop(offset).take(length).sumOf { write(it).toLong() }
    override fun position(): Long = inner.position()
    override fun position(newPosition: Long): FileChannel {
        // A pass starts at byte 0: it takes its plan.
        if (newPosition == 0L) {
            plan = plans.firstOrNull()?.takeIf { it != WritePlan.Refuse }?.also { plans.removeFirst() }
            written = 0
            damage = plan == WritePlan.Damage
        }
        inner.position(newPosition)
        return this
    }
    override fun size(): Long = inner.size()
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = inner.transferTo(position, count, target)
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = inner.transferFrom(src, position, count)
    override fun read(dst: ByteBuffer, position: Long): Int = inner.read(dst, position)
    override fun write(src: ByteBuffer, position: Long): Int = inner.write(src, position)
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = inner.map(mode, position, size)
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = inner.lock(position, size, shared)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = inner.tryLock(position, size, shared)
    override fun implCloseChannel() {
        inner.close()
        onClose()
    }
}

/**
 * The tag writer, its journal, recovery and Undo store over a temporary folder, with fakes for TagLib, MediaStore and
 * the busy check. [restart] starts a new "process" over the same files, as after the app's death.
 */
class WriterRig(root: File, var writes: app.lyrify.data.SharedWrites = app.lyrify.data.SharedWrites.ANYWHERE) {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val music = File(root, "storage/emulated/0/Music/KJB test/Harbour Glass").apply { mkdirs() }
    val folders = FolderSync()
    val dirs = TagDirs(File(root, "files"), folders::sync)
    val tags = FakeTagFile(events)
    val songs = FakeSongFiles(events)
    val busy = FakeBusyFiles(events)
    var locks = app.lyrify.data.PathLocks()
    var free: Long = 10L shl 30
    var now: Long = 1_800_000_000_000L

    /** How the writer copies a song to its private files; a test makes it fail or die. */
    var copy: (File, File) -> Unit = { from, to -> durableCopy(from, to, folders::sync) }

    lateinit var journal: WriteJournal
    lateinit var undo: UndoStore
    lateinit var writer: SafeWriter

    init {
        restart()
    }

    fun restart() {
        // A new process: nothing held, nothing pinned.
        locks = app.lyrify.data.PathLocks()
        journal = WriteJournal(dirs.journal, folders::sync, logs::add)
        undo = UndoStore(dirs, logs::add)
        writer = SafeWriter(
            dirs = dirs,
            tags = tags,
            songs = songs,
            busy = busy,
            locks = locks,
            writes = writes,
            journal = journal,
            undo = undo,
            freeBytes = { free },
            clock = { now },
            log = logs::add,
            copy = { from, to ->
                events += if (to.parentFile == dirs.backups) "copy backup" else "copy work"
                copy(from, to)
            },
        )
    }

    fun recovery(): JournalRecovery = JournalRecovery(dirs, journal, undo, songs, locks, writes, logs::add)

    /** A made-up song in the test folder with [fields] in its tag, known to MediaStore as [id]. */
    fun song(
        name: String,
        id: Long,
        fields: Map<String, List<String>> = mapOf("TITLE" to listOf(name)),
        pictures: List<TagPicture> = emptyList(),
        seed: Int = id.toInt(),
        v1: ByteArray? = null,
        version: Int = 3,
        frames: List<String> = emptyList(),
    ): SongTarget {
        val file = File(music, "$name.mp3")
        file.writeBytes(FakeTagFile.song(fields, pictures, TagFixtures.mp3Audio(seed = seed), v1, version, frames))
        songs.ids[file.path] = id
        return SongTarget(file.path, id)
    }

    fun bytes(song: SongTarget): ByteArray = File(song.path).readBytes()

    fun fields(song: SongTarget): Map<String, List<String>> = checkNotNull(FakeTagFile().read(File(song.path))).fields

    /** Files left in the writer's private folders: the journal, the work copies, and backups no snapshot keeps. */
    fun leftovers(): List<String> {
        val kept = undo.memory().snapshots.map { it.file }.toSet()
        return listOfNotNull(dirs.journal.listFiles(), dirs.work.listFiles()).flatMap { it.toList() }.map { it.name } +
            (dirs.backups.listFiles() ?: emptyArray()).map { it.name }.filter { it !in kept }
    }
}
