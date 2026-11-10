package app.lyrify.tags

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import app.lyrify.Lyrify
import app.lyrify.data.PathLocks
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Debug builds only, for TagEngineDeviceTest's kill test. In a process of its own (":tagkill"), it writes the song
 * [EXTRA_ID] at [EXTRA_PATH] (in the test folder) with the real tag writer over the folders in [EXTRA_ROOT], through
 * MediaStore's "rw" descriptor, and stops in the middle of the write: after [EXTRA_BYTES] bytes went into the song, with
 * no fsync, it sends its process id to [EXTRA_REPLY] and waits, for the test to kill it with SIGKILL. A write that never
 * reaches that many bytes replies [DONE]. Nothing is logged.
 */
class TagKillService : Service() {
    override fun onBind(intent: Intent): IBinder {
        val reply = checkNotNull(IntentCompat.getParcelableExtra(intent, EXTRA_REPLY, ResultReceiver::class.java))
        val song = SongTarget(checkNotNull(intent.getStringExtra(EXTRA_PATH)), intent.getLongExtra(EXTRA_ID, -1))
        val root = File(filesDir, checkNotNull(intent.getStringExtra(EXTRA_ROOT)))
        val bytes = intent.getLongExtra(EXTRA_BYTES, Long.MAX_VALUE)
        Thread { write(song, root, bytes, reply) }.start()
        return Binder()
    }

    private fun write(song: SongTarget, root: File, bytes: Long, reply: ResultReceiver) {
        val dirs = TagDirs(root, ::fsyncDirectory)
        val phone = PhoneSongFiles(this)
        val stopping = object : SongFiles by phone {
            override fun openInPlace(song: SongTarget): OpenSong? = phone.openInPlace(song)?.let { open ->
                OpenSong(StopsMidWrite(open.channel, bytes) { reply.send(STOPPED, Bundle().apply { putInt(PID, Process.myPid()) }) }, open::fileId)
            }
        }
        val free = object : BusyFiles {
            override fun check(path: String): Busy = Busy.FREE
        }
        val writer = SafeWriter(
            dirs, TagLibFile(filesDir), stopping, free, PathLocks(), Lyrify.writes, WriteJournal(dirs.journal, dirs.syncDir), UndoStore(dirs),
            freeBytes = { Long.MAX_VALUE / 4 }, clock = System::currentTimeMillis,
        )
        val edit = TagEdit(fields = mapOf("TITLE" to listOf("Tin Roof")), cover = CoverEdit.Replace(TagPicture(cover(), "", FRONT_COVER, "image/jpeg")))
        runBlocking { writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Tin Roof") }
        reply.send(DONE, Bundle())
    }

    /** A made-up cover, so the tag grows and the write shifts the audio. */
    private fun cover(): ByteArray {
        val bitmap = createBitmap(96, 96).apply { eraseColor(0xFF6B2E7D.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Writes through to [inner] until [limit] bytes, never fsyncing; there it calls [stopped] and waits to be killed. */
    private class StopsMidWrite(private val inner: FileChannel, private val limit: Long, private val stopped: () -> Unit) : FileChannel() {
        private var written = 0L

        override fun write(src: ByteBuffer): Int {
            if (written + src.remaining() > limit) {
                val part = (limit - written).toInt()
                inner.write(src.duplicate().limit(src.position() + part) as ByteBuffer)
                written = limit
                stopped()
                while (true) Thread.sleep(60_000)
            }
            return inner.write(src).also { written += it }
        }

        override fun force(metaData: Boolean) {
            if (written >= limit) inner.force(metaData)
        }

        override fun read(dst: ByteBuffer): Int = inner.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = inner.read(dsts, offset, length)
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = srcs.drop(offset).take(length).sumOf { write(it).toLong() }
        override fun position(): Long = inner.position()
        override fun position(newPosition: Long): FileChannel = apply { inner.position(newPosition) }
        override fun size(): Long = inner.size()
        override fun truncate(size: Long): FileChannel = apply { inner.truncate(size) }
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = inner.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = inner.transferFrom(src, position, count)
        override fun read(dst: ByteBuffer, position: Long): Int = inner.read(dst, position)
        override fun write(src: ByteBuffer, position: Long): Int = inner.write(src, position)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = inner.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = inner.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = inner.tryLock(position, size, shared)
        override fun implCloseChannel() = inner.close()
    }

    companion object {
        const val EXTRA_PATH: String = "path"
        const val EXTRA_ID: String = "id"
        const val EXTRA_ROOT: String = "root"
        const val EXTRA_BYTES: String = "bytes"
        const val EXTRA_REPLY: String = "reply"
        const val PID: String = "pid"
        const val STOPPED: Int = 0
        const val DONE: Int = 1
    }
}
