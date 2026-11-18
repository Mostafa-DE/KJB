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
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * failed save removes the part file and whatever is in [work]; what a killed save left in [work] goes at the next one.
 *
 * The folder is the song's when KJB may write there ([canWrite], and [writes] for the cut's path), else [music] (the
 * debug build's test folder in a debug build), else none. The saved cut is handed to [scan] so the library shows it.
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
) {
    /** Where a cut of the song at [songPath] would be saved now. Blocking (it asks the disk). */
    fun folder(songPath: String): CutFolder {
        val song = File(songPath)
        val base = CutNames.base(song.name)
        song.parentFile?.let { beside -> if (writable(beside, base)) return CutFolder.NextToSong(beside) }
        if (writable(music, base)) return CutFolder.InMusic(music)
        return CutFolder.Nowhere
    }

    /** Saves [request]; [onProgress] hears the share done. Cancellable: a cancelled save leaves no file. */
    suspend fun save(request: CutRequest, onProgress: (Float) -> Unit): CutResult = withContext(io) {
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
            val hidden = freePart(folder, base) ?: return@withContext CutResult.Failed(CutFailure.NOT_SAVED)
            part = hidden
            Files.copy(out.toPath(), hidden.toPath(), StandardCopyOption.REPLACE_EXISTING)
            currentCoroutineContext().ensureActive()
            // From here the cut is whole: it takes its name and is scanned even if Cancel comes now.
            val saved = withContext(NonCancellable) { publish(hidden, folder, base) } ?: return@withContext CutResult.Failed(CutFailure.NOT_SAVED)
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

    /** A new empty hidden part file in [folder] for the cut, made under a name no file has; null when none may be. */
    private fun freePart(folder: File, base: String): File? {
        for (n in 1..NAME_TRIES) {
            val part = File(folder, ".${CutNames.fileName(base, n)}$PART_SUFFIX")
            if (!writes.allows(SharedWrite.CUT_SAVE, part.path)) return null
            try {
                Files.createFile(part.toPath())
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
        for (n in 1..NAME_TRIES) {
            val saved = File(folder, CutNames.fileName(base, n))
            if (!writes.allows(SharedWrite.CUT_SAVE, saved.path)) return null
            try {
                Files.createFile(saved.toPath())
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
        private const val PART_SUFFIX: String = ".part"
        private const val TITLE: String = "TITLE"

        /** How many names are tried before a save gives up. */
        private const val NAME_TRIES: Int = 100

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
                newEncoder = { fd, bitRate, codec -> AacToneEncoder(fd, bitRate, codec) },
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
         * The song's decoder for the cutter, for a song of any length: without the AI's 20-minute cap, as the cutter
         * reads only to the cut's end (and its waveform to the song's length), a piece at a time. The decoder's own
         * deadline still ends one that hangs.
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
