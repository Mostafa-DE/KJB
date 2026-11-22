package app.lyrify.cut

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.ai.MixDecoder
import app.lyrify.ai.MixSource
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.library.LibraryGraph
import app.lyrify.tags.Id3Version
import app.lyrify.tags.SongTags
import app.lyrify.tags.TagFile
import app.lyrify.tags.TagLibFile
import app.lyrify.tones.AacToneEncoder
import app.lyrify.tones.FadeEnvelope
import app.lyrify.tones.ToneAudio
import app.lyrify.tones.ToneEncoder
import app.lyrify.tones.ToneSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/** Where a cut of a song is saved: beside the song, in Music/ when KJB may not write there, or nowhere. */
sealed interface CutFolder {
    data class NextToSong(val folder: File) : CutFolder

    data class InMusic(val folder: File) : CutFolder

    data object Nowhere : CutFolder
}

/**
 * What the cutter saves: [range] of the song at [songPath], with its fades, at [quality] by the AAC encoder [encoder]
 * (null: the phone's first). The song's title, artist and album name the cut when KJB cannot read its tags.
 */
data class CutRequest(
    val songPath: String,
    val songTitle: String,
    val songArtist: String?,
    val songAlbum: String?,
    val range: CutRange,
    val fadeIn: Fade,
    val fadeOut: Fade,
    val quality: CutQuality,
    val encoder: String?,
)

sealed interface CutResult {
    /** The new [file], with the song's tags in it, or without them when TagLib could not write any ([tagged] false). */
    data class Saved(val file: File, val tagged: Boolean) : CutResult

    data class Failed(val reason: CutFailure) : CutResult
}

enum class CutFailure {
    /** The song could not be opened or decoded, or ends before the cut. */
    SONG_UNREADABLE,

    /** KJB may write neither beside the song nor in Music/. */
    NOWHERE,

    /** The encoder or storage failed (no space, no free name). */
    NOT_SAVED,
}

/**
 * Saves cuts. A cut is a new file: the song is only ever read (decoded through [openSong], and copied once into [work]
 * so TagLib reads its tags from KJB's own copy, as the tag engine only ever does). The cut is encoded into [work] (app
 * storage, under [tags]' root), gets the song's tags and cover there, is copied into a hidden part file in its folder,
 * and only then takes a free name: a new empty file made under the first name nobody has, then the part moved over it in
 * one rename, so no other file is ever written over and no half-made cut ever shows under a song's name. A cancelled or
 * failed save removes the part file and whatever is in [work]; what a killed save left in [work] goes at the next one,
 * and its part file once it is a day old ([sweep]).
 *
 * The folder is the song's when KJB may write there ([canWrite], and [writes] for the cut's path), else [music] (the
 * debug build's test folder in a debug build), else none; a song's folder that refuses the new file ([createFile]) all
 * the same sends the cut to [music]. The saved cut is handed to [scan] so the library shows it.
 * Blocking work runs on [io]. Logs name steps and exception classes only, never a title or a path.
 */
class CutFiles(
    private val writes: SharedWrites,
    private val work: File,
    private val tags: TagFile,
    private val openSong: (File) -> MixSource,
    private val newEncoder: (fd: FileDescriptor, bitRate: Int, codec: String?) -> ToneEncoder,
    private val music: File,
    private val canWrite: (File) -> Boolean,
    private val scan: suspend (File) -> Unit,
    private val log: (String) -> Unit,
    private val io: CoroutineContext = Dispatchers.IO,
    private val createFile: (File) -> Unit = { Files.createFile(it.toPath()) },
) {
    /** Where a cut of the song at [songPath] would be saved now. Blocking (it asks the disk). */
    fun folder(songPath: String): CutFolder {
        val song = File(songPath)
        val base = CutNames.base(song.name)
        song.parentFile?.let { beside -> if (writable(beside, base)) return CutFolder.NextToSong(beside) }
        if (writable(music, base)) return CutFolder.InMusic(music)
        return CutFolder.Nowhere
    }

    /**
     * Removes the hidden part files stopped saves left (the app killed while a cut was copied into its folder) from the
     * folders a cut of the song at [songPath] goes to, beside it and Music/: only files with KJB's part names
     * ([CutNames.isPart]), plain files (not a link or a folder) last written over a day ago (so never one a save is
     * writing), where the build lets KJB delete. Run when the cutter opens and before each save. Blocking.
     */
    fun sweep(songPath: String) {
        val song = File(songPath)
        val base = CutNames.base(song.name)
        val before = System.currentTimeMillis() - STALE_MS
        var removed = 0
        listOfNotNull(song.parentFile, music).distinct().filter { writable(it, base) }.forEach { folder ->
            folder.listFiles { _, name -> CutNames.isPart(name) }.orEmpty().forEach { part ->
                if (stale(part, before) && writes.allows(SharedWrite.CUT_DELETE, part.path) && part.delete()) removed++
            }
        }
        if (removed > 0) log("cut: $removed part files of stopped saves removed")
    }

    /**
     * Saves [request]; [onProgress] hears the share done. Cancellable, and a cancelled save leaves no file, until the cut
     * is whole in its part file: then [commit] says whether it may take its name. False (Cancel came first) stops it
     * there, its part file removed; true, and it is saved and scanned, as the runner no longer cancels it
     * ([app.lyrify.ui.SaveRunner]), so the screen says Saved.
     */
    suspend fun save(request: CutRequest, commit: () -> Boolean = { true }, onProgress: (Float) -> Unit): CutResult = withContext(io) {
        val song = File(request.songPath)
        val folder = when (val place = folder(request.songPath)) {
            is CutFolder.NextToSong -> place.folder
            is CutFolder.InMusic -> place.folder
            CutFolder.Nowhere -> {
                log("cut not saved: nowhere KJB may write")
                return@withContext CutResult.Failed(CutFailure.NOWHERE)
            }
        }
        val base = CutNames.base(song.name)
        val out = File(work, OUT_NAME)
        var part: File? = null
        try {
            sweep(request.songPath)
            clearWork()
            val original = originalTags(song)
            currentCoroutineContext().ensureActive()
            val source = try {
                openSong(song)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("cut not saved, song not opened: ${e.javaClass.simpleName}")
                return@withContext CutResult.Failed(CutFailure.SONG_UNREADABLE)
            }
            source.use { encode(it, request, out, onProgress) }
            val tagged = tag(out, request, original)
            currentCoroutineContext().ensureActive()
            val (place, hidden) = partIn(folder, base) ?: return@withContext CutResult.Failed(CutFailure.NOT_SAVED)
            part = hidden
            Files.copy(out.toPath(), hidden.toPath(), StandardCopyOption.REPLACE_EXISTING)
            currentCoroutineContext().ensureActive()
            // The point of no return: from here the cut is whole, takes its name and is scanned even if Cancel comes now,
            // and the screen says Saved. A Cancel that came first wins, and the part file goes.
            if (!commit()) throw CancellationException("cancelled before the cut took its name")
            val saved = withContext(NonCancellable) { publish(hidden, place, base) } ?: return@withContext CutResult.Failed(CutFailure.NOT_SAVED)
            part = null
            withContext(NonCancellable) { scanned(saved) }
            onProgress(1f)
            CutResult.Saved(saved, tagged)
        } catch (e: Throwable) {
            when (e) {
                is CancellationException -> throw e
                is ToneSourceException -> {
                    log("cut not saved, song unreadable: ${(e.cause ?: e).javaClass.simpleName}")
                    CutResult.Failed(CutFailure.SONG_UNREADABLE)
                }
                is Exception -> {
                    log("cut not saved: ${e.javaClass.simpleName}")
                    CutResult.Failed(CutFailure.NOT_SAVED)
                }
                else -> throw e
            }
        } finally {
            withContext(NonCancellable) {
                part?.let(::discard)
                out.delete()
            }
        }
    }

    /** Whether a cut named after [base] may go in [folder]: the build allows it there, and the disk lets KJB write. */
    private fun writable(folder: File, base: String): Boolean =
        writes.allows(SharedWrite.CUT_SAVE, File(folder, CutNames.fileName(base, 1)).path) && canWrite(folder)

    /** [work] made, emptied of what a save the app's death ended left there. */
    private fun clearWork() {
        work.mkdirs()
        work.listFiles().orEmpty().forEach { it.delete() }
    }

    /** The song's tags and pictures, read by TagLib from KJB's private copy (removed after); null when they can't be. */
    private fun originalTags(song: File): SongTags? {
        val copy = File(work, "$COPY_NAME.${song.extension.lowercase().ifEmpty { "audio" }}")
        return try {
            Files.copy(song.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            tags.read(copy)
        } catch (e: Exception) {
            log("cut: the song's tags not read (${e.javaClass.simpleName})")
            null
        } finally {
            copy.delete()
        }
    }

    /** The cut's audio: the song's part with its fades along their curves, encoded into [out]. */
    private suspend fun encode(source: MixSource, request: CutRequest, out: File, onProgress: (Float) -> Unit) {
        val window = request.range.window
        val fade = FadeEnvelope.of(window, request.fadeIn.lengthMs, request.fadeOut.lengthMs, request.fadeIn.curve, request.fadeOut.curve)
        // "rw": MediaMuxer seeks back to finish an MPEG-4 file.
        RandomAccessFile(out, "rw").use { file ->
            newEncoder(file.fd, request.quality.bitRate, request.encoder).use { encoder ->
                ToneAudio.render(source, window, fade, encoder) { onProgress(it * ENCODE_SHARE) }
            }
        }
    }

    /**
     * The song's tags (or the library's names) with the cut's title, and its pictures, written into [out]. A cover TagLib
     * will not take is left out; false when not even the text went in, and the cut is saved without tags.
     */
    private fun tag(out: File, request: CutRequest, original: SongTags?): Boolean {
        val songTitle = original?.fields?.entries?.firstOrNull { it.key.equals(TITLE, ignoreCase = true) }?.value?.firstOrNull()?.takeIf { it.isNotBlank() }
        val fields = CutTags.fields(original?.fields, CutNames.title(songTitle ?: request.songTitle), request.songArtist, request.songAlbum)
        val pictures = original?.pictures.orEmpty()
        val tagged = try {
            tags.write(out, fields, pictures, Id3Version.V2_3) || pictures.isNotEmpty() && tags.write(out, fields, emptyList(), Id3Version.V2_3)
        } catch (e: Exception) {
            log("cut: tags not written (${e.javaClass.simpleName})")
            false
        }
        if (!tagged) log("cut saved without its tags")
        return tagged
    }

    /** Whether [part] is a plain file, not a link or a folder, last written before [before] (ms since the epoch). */
    private fun stale(part: File, before: Long): Boolean = try {
        val attributes = Files.readAttributes(part.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        attributes.isRegularFile && attributes.lastModifiedTime().toMillis() < before
    } catch (_: IOException) {
        false
    }

    /**
     * The folder the cut goes to and a new part file in it: [folder], else Music/ when [folder] is the song's and refuses
     * the new file although it says KJB may write there (as a memory card's folder can). Null when no name is free;
     * throws when the folder refuses and Music/ is no way out (it is [folder], KJB may not write there, or it refuses too).
     */
    private fun partIn(folder: File, base: String): Pair<File, File>? = try {
        freePart(folder, base)?.let { folder to it }
    } catch (e: IOException) {
        if (folder == music || !writable(music, base)) throw e
        log("cut: its folder refused the new file (${e.javaClass.simpleName}), so it goes in Music")
        freePart(music, base)?.let { music to it }
    }

    /**
     * A new empty hidden part file in [folder] for the cut, made under a name no file has; null when none may be. Throws
     * when the folder refuses a new file.
     */
    private fun freePart(folder: File, base: String): File? {
        for (n in 1..CutNames.MAX_NUMBER) {
            val part = File(folder, CutNames.partName(base, n))
            if (!writes.allows(SharedWrite.CUT_SAVE, part.path)) return null
            try {
                createFile(part)
                return part
            } catch (_: FileAlreadyExistsException) {
                // Another file has the name (another save's, or one KJB did not make): never touched.
            }
        }
        log("cut not saved: no free name for its part file")
        return null
    }

    /**
     * [part] under the first free name for [base] in [folder]: the name is taken by making it as a new empty file (which
     * fails when any file or link has it, so nothing is ever replaced), then the part is moved over that file of its own.
     * Null when it could not be moved there; the name it took is given back.
     */
    private fun publish(part: File, folder: File, base: String): File? {
        for (n in 1..CutNames.MAX_NUMBER) {
            val saved = File(folder, CutNames.fileName(base, n))
            if (!writes.allows(SharedWrite.CUT_SAVE, saved.path)) return null
            try {
                createFile(saved)
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            return try {
                Files.move(part.toPath(), saved.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                saved
            } catch (e: Exception) {
                log("cut not saved, not moved to its name: ${e.javaClass.simpleName}")
                if (saved.length() == 0L && writes.allows(SharedWrite.CUT_DELETE, saved.path)) saved.delete()
                null
            }
        }
        log("cut not saved: no free name")
        return null
    }

    private suspend fun scanned(saved: File) {
        try {
            scan(saved)
        } catch (e: Exception) {
            // The cut is saved: MediaStore finds it on its own later.
            log("cut not scanned: ${e.javaClass.simpleName}")
        }
    }

    private fun discard(part: File) {
        if (writes.allows(SharedWrite.CUT_DELETE, part.path) && !part.delete() && part.exists()) log("cut: its part file not removed")
    }

    companion object {
        /** The encode's share of the progress; copying the cut into its folder takes the rest. */
        private const val ENCODE_SHARE: Float = 0.95f
        private const val OUT_NAME: String = "cut.m4a"
        private const val COPY_NAME: String = "song"
        private const val TITLE: String = "TITLE"

        /** A part file this much older than now is a stopped save's: no save takes a day. */
        private const val STALE_MS: Long = 24 * 60 * 60 * 1_000L

        private const val SCAN_TIMEOUT_MS: Long = 30_000

        /**
         * The app's: the song decoder, the AAC encoder, TagLib on KJB's files folder, Music/ (Music/KJB test/ in a build
         * that changes only its test folder), the media scan and the library.
         */
        fun of(context: Context): CutFiles {
            val app = context.applicationContext
            val writes = Lyrify.writes
            @Suppress("DEPRECATION") // The volume's path, as Downloads takes it: MediaStore and the library list these paths.
            val root = Environment.getExternalStorageDirectory()
            return CutFiles(
                writes = writes,
                work = File(app.filesDir, WORK_DIR),
                tags = TagLibFile(app.filesDir),
                openSong = ::decoder,
                // As the decoder: a cut of any length, so no total time; the stall check still ends an encoder that hangs.
                newEncoder = { fd, bitRate, codec -> AacToneEncoder(fd, bitRate, codec, maxMs = Long.MAX_VALUE) },
                music = if (writes.testFolderOnly) File(root, SharedWrites.TEST_FOLDER.trimEnd('/')) else File(root, Environment.DIRECTORY_MUSIC),
                canWrite = { it.isDirectory && it.canWrite() },
                scan = { file ->
                    withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                        suspendCancellableCoroutine { done ->
                            MediaScannerConnection.scanFile(app, arrayOf(file.path), arrayOf(CutNames.MIME_TYPE)) { _, _ -> if (done.isActive) done.resume(Unit) }
                        }
                    }
                    LibraryGraph.repository(app).refresh()
                },
                log = { Log.w(LOG_TAG, it) },
            )
        }

        /** KJB's own folder for a cut on its way, under its files (TagLib writes only there). */
        private const val WORK_DIR: String = "cut-work"

        /**
         * The song's decoder for the cutter, for a song of any length: without the AI's 20-minute cap or its 10 minutes
         * of decoding time in all, as the cutter reads only to the cut's end (and its waveform to the song's length), a
         * piece at a time. A late cut of an AAC or PCM song starts by jumping to its start ([MixDecoder.seek]); an MP3
         * is read from its start. The decoder's stall check still ends one that hangs.
         */
        fun decoder(song: File): MixSource = MixDecoder(song, maxAudioSec = Long.MAX_VALUE)
    }
}

/** The phone's AAC encoders, as MediaCodecList says (the phone's own order). Blocking; empty when it can't say. */
object CutEncoders {
    private const val RATE: Int = 44_100

    fun phone(): List<AacEncoder> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) } }
            .mapNotNull { info ->
                val audio = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities ?: return@mapNotNull null
                AacEncoder(info.name, audio.bitrateRange.lower..audio.bitrateRange.upper, audio.isSampleRateSupported(RATE) && audio.maxInputChannelCount >= 2)
            }
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "cut: encoders not listed (${e.javaClass.simpleName})")
        emptyList()
    }
}
