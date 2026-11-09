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
 * [TagFile] over TagFixtures MP3s: the ID3v2 tag holds the fields and pictures as JSON, the audio after it stays as it
 * is, and the duration is the audio's size. [events] records each call; the counters show calls running at once.
 */
class FakeTagFile(private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())) : TagFile {
    val given = Collections.synchronizedList(mutableListOf<File>())
    var failWrite = false

    /** A write that also changes one byte of the audio: the verify must catch it. */
    var damageAudio = false

    /** A write that drops a field it was given: the verify must catch it. */
    var dropField: String? = null
    var running = 0
    var mostAtOnce = 0

    @Serializable
    private data class Stored(val fields: Map<String, List<String>> = emptyMap(), val pictures: List<StoredPicture> = emptyList())

    @Serializable
    private data class StoredPicture(val data: String, val description: String, val type: String, val mime: String)

    override fun read(file: File): SongTags? {
        events += "tags read"
        given += file
        val bytes = file.readBytes()
        val (stored, audio) = split(bytes) ?: return null
        return SongTags(
            stored.fields,
            stored.pictures.map { TagPicture(Base64.getDecoder().decode(it.data), it.description, it.type, it.mime) },
            durationMs = audio.size.toLong() * 10,
        )
    }

    override fun write(file: File, fields: Map<String, List<String>>, pictures: List<TagPicture>, id3: Id3Version): Boolean {
        synchronized(this) { running++; mostAtOnce = maxOf(mostAtOnce, running) }
        try {
            Thread.sleep(5)
            events += "tags write"
            given += file
            if (failWrite) return false
            val (_, audio) = split(file.readBytes()) ?: return false
            val kept = dropField?.let { fields - it } ?: fields
            file.writeBytes(encode(kept, pictures, id3.number) + audio.copyOf().also { if (damageAudio) it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
            return true
        } finally {
            synchronized(this) { running-- }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** An MP3 with [fields] and [pictures] in its tag and [audio] after it, as this fake writes them. */
        fun encode(fields: Map<String, List<String>>, pictures: List<TagPicture> = emptyList(), version: Int = 3, padding: Int = 0): ByteArray {
            val stored = Stored(fields, pictures.map { StoredPicture(Base64.getEncoder().encodeToString(it.data), it.description, it.type, it.mime) })
            return TagFixtures.id3v2(json.encodeToString(Stored.serializer(), stored).encodeToByteArray(), version = version, padding = padding)
        }

        fun song(fields: Map<String, List<String>>, pictures: List<TagPicture> = emptyList(), audio: ByteArray = TagFixtures.mp3Audio(), v1: ByteArray? = null): ByteArray =
            encode(fields, pictures) + audio + (v1 ?: ByteArray(0))

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

/** What the next in-place write does, for a test: each open takes the next plan; with none, it simply writes. */
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
    var scanned: (String) -> Long? = { ids[it] }
    val plans = ArrayDeque<WritePlan>()

    /** The app dies during the next scan. */
    var killOnScan = false

    /** Asked on each open, to see what is held then (the recovery must hold the song). */
    var onOpen: () -> Unit = {}
    var running = 0
    var mostAtOnce = 0

    override fun mounted(path: String): Boolean = mounted.also { events += "mounted" }

    override fun sameFile(mediaStoreId: Long, path: String): Boolean = (ids[path] == mediaStoreId).also { events += "same file" }

    override fun openInPlace(song: SongTarget): FileChannel? {
        events += "open rw"
        onOpen()
        val plan = plans.removeFirstOrNull()
        if (plan == WritePlan.Refuse || !File(song.path).isFile) return null
        synchronized(this) { running++; mostAtOnce = maxOf(mostAtOnce, running) }
        val channel = FileChannel.open(File(song.path).toPath(), StandardOpenOption.WRITE)
        return PlannedChannel(channel, plan) {
            synchronized(this) { running-- }
            events += "closed"
        }
    }

    override suspend fun scan(path: String): Long? {
        events += "scan"
        if (killOnScan) {
            killOnScan = false
            throw SimulatedKill()
        }
        return scanned(path)
    }
}

/** A file channel that writes through to [inner], failing, dying or damaging as [plan] says. */
private class PlannedChannel(private val inner: FileChannel, private val plan: WritePlan?, private val onClose: () -> Unit) : FileChannel() {
    private var written = 0L
    private var damage = plan == WritePlan.Damage

    override fun write(src: ByteBuffer): Int {
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
    override fun position(newPosition: Long): FileChannel = apply { inner.position(newPosition) }
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
    val dirs = TagDirs(File(root, "files"))
    val tags = FakeTagFile(events)
    val songs = FakeSongFiles(events)
    val busy = FakeBusyFiles(events)
    var locks = app.lyrify.data.PathLocks()
    var free: Long = 10L shl 30
    var now: Long = 1_800_000_000_000L

    /** How the writer copies a song to its private files; a test makes it fail or die. */
    var copy: (File, File) -> Unit = { from, to -> durableCopy(from, to) }

    lateinit var journal: WriteJournal
    lateinit var undo: UndoStore
    lateinit var writer: SafeWriter

    init {
        restart()
    }

    fun restart() {
        journal = WriteJournal(dirs.journal, logs::add)
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
    fun song(name: String, id: Long, fields: Map<String, List<String>> = mapOf("TITLE" to listOf(name)), pictures: List<TagPicture> = emptyList(), seed: Int = id.toInt(), v1: ByteArray? = null): SongTarget {
        val file = File(music, "$name.mp3")
        file.writeBytes(FakeTagFile.song(fields, pictures, TagFixtures.mp3Audio(seed = seed), v1))
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
