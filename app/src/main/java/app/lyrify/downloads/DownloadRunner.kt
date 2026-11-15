package app.lyrify.downloads

import app.lyrify.addon.AddonDiedException
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dupes.AudioFile
import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** How one download ended on KJB's side. */
sealed interface DownloadOutcome {
    /** Saved at [path]; [copyOf] is a song on the phone KJB's Duplicates rules take for the same one. */
    data class Saved(val path: String, val copyOf: String?) : DownloadOutcome

    data class Failed(val reason: DownloadFailure) : DownloadOutcome
}

/** Where a download's file goes: the hidden [part] KJB opens for the add-on, its private [cover], and the name it will get. */
data class Target(val part: File, val cover: File?, val final: File)

/** What opening a download's destination gave. */
sealed interface Opened {
    data class Ready(val target: Target) : Opened

    data class Refused(val reason: DownloadFailure) : Opened
}

/** The phone's side of a download: where it goes, KJB's own steps once it is whole, and the clean-up of one that is not. */
interface DownloadSaver {
    fun open(item: DownloadItem, file: DlFile): Opened

    suspend fun finish(item: DownloadItem, target: Target, file: DlFile): DownloadOutcome

    fun abandon(target: Target)
}

/**
 * One download from KJB's foreground service: started in the add-on (which looks it up, downloads and converts in its own
 * cache), its file streamed into the part file KJB opened where it goes ([DownloadSaver.open]), then KJB's own steps
 * ([DownloadSaver.finish]). Never waited for forever: a run that says nothing for [stallMs] (longer than any step takes)
 * is stopped and fails. Cancelled (the user, or Android's limit for long work), it tells the add-on, which kills yt-dlp and
 * keeps its parts for the next try; the part file here goes.
 */
class DownloadRunner(
    private val port: DlPort,
    private val death: Deferred<Unit>,
    private val saver: DownloadSaver,
    private val stallMs: Long = STALL_MS,
    private val keepOnCancel: () -> Boolean = { true },
) {
    private sealed interface Event {
        data class Progress(val progress: DlProgress) : Event

        data class File(val file: DlFile) : Event

        data class End(val end: DlEnd) : Event

        data object Died : Event
    }

    suspend fun run(item: DownloadItem, progress: (DlProgress) -> Unit): DownloadOutcome {
        val events = Channel<Event>(Channel.UNLIMITED)
        val heard = object : DlRunEvents {
            override fun onProgress(progress: DlProgress) {
                events.trySend(Event.Progress(progress))
            }

            override fun onFile(file: DlFile) {
                events.trySend(Event.File(file))
            }

            override fun onEnd(end: DlEnd) {
                events.trySend(Event.End(end))
            }
        }
        val request = DlRequest(item.key, item.url, item.kind, item.audio, item.height, item.originalLanguage)
        val run = try {
            port.start(request, heard)
        } catch (_: AddonDiedException) {
            return DownloadOutcome.Failed(DownloadFailure.ADDON_GONE)
        }
        var target: Target? = null
        return coroutineScope {
            val died = launch {
                death.await()
                events.trySend(Event.Died)
            }
            try {
                follow(events, run, item, progress) { target = it }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        // The user's Cancel: what the add-on got goes; Android's pause: it stays for the next run.
                        port.cancel(run, keepParts = keepOnCancel())
                    } catch (_: AddonDiedException) {
                        // Gone, and its processes with it.
                    }
                    target?.let(saver::abandon)
                }
                throw e
            } finally {
                died.cancel()
            }
        }
    }

    /** The run's events to its end; [opened] hears the destination as soon as it is opened. */
    private suspend fun follow(events: Channel<Event>, run: Long, item: DownloadItem, progress: (DlProgress) -> Unit, opened: (Target) -> Unit): DownloadOutcome {
        var target: Target? = null
        var file: DlFile? = null
        fun failed(reason: DownloadFailure): DownloadOutcome {
            target?.let(saver::abandon)
            return DownloadOutcome.Failed(reason)
        }
        while (true) {
            val event = withTimeoutOrNull(stallMs) { events.receive() }
            when (event) {
                null -> {
                    stop(run)
                    return failed(DownloadFailure.STALLED)
                }
                is Event.Progress -> progress(event.progress)
                is Event.File -> if (target == null) {
                    when (val destination = saver.open(item, event.file)) {
                        is Opened.Refused -> {
                            stop(run)
                            return DownloadOutcome.Failed(destination.reason)
                        }
                        is Opened.Ready -> {
                            target = destination.target.also(opened)
                            file = event.file
                            try {
                                port.deliver(run, destination.target.part, destination.target.cover)
                            } catch (_: IOException) {
                                stop(run)
                                return failed(DownloadFailure.NOT_ALLOWED)
                            } catch (_: AddonDiedException) {
                                return failed(DownloadFailure.ADDON_GONE)
                            }
                        }
                    }
                }
                is Event.End -> return when (val end = event.end) {
                    is DlEnd.Delivered -> {
                        val whole = target != null && file != null && end.bytes == file.bytes
                        if (whole) saver.finish(item, target, file) else failed(DownloadFailure.HAND_OFF)
                    }
                    is DlEnd.Failed -> failed(DownloadFailure.of(end.failure))
                    // Stopped without KJB asking: the add-on let go of it (KJB unbound it, or it was stopping).
                    DlEnd.Stopped -> failed(DownloadFailure.ADDON_GONE)
                }
                Event.Died -> return failed(DownloadFailure.ADDON_GONE)
            }
        }
    }

    /** Tells the add-on to stop [run], its parts kept for a Retry; quiet when it is gone. */
    private fun stop(run: Long) {
        try {
            port.cancel(run, keepParts = true)
        } catch (_: AddonDiedException) {
            // Gone, and the run with it.
        }
    }

    companion object {
        /**
         * Longer than any step a run is silent for: yt-dlp writes a line at least every few seconds while it downloads,
         * and ffmpeg converts an hour of audio in a few minutes on a phone.
         */
        const val STALL_MS: Long = 10 * 60_000
    }
}

/**
 * The files of downloads on the phone: the folder [DownloadSettings] give a download on the volume at [volume], its hidden
 * part file there (`.<name>.part`, which no media scan lists), the free name it gets, and its cover in KJB's own
 * [private] folder. Every change asks [writes] (a debug build changes only its test folder), and nothing is opened without
 * All files access ([canWrite]).
 */
class DownloadFiles(
    private val volume: File,
    private val private: File,
    private val writes: SharedWrites,
    private val canWrite: () -> Boolean,
    private val copy: (Path, Path) -> Unit = { from, to -> Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING) },
    private val freeName: (File, String) -> File = DownloadFolders::free,
) {
    companion object {
        /** The largest cover KJB takes: what Covers & tags' pipeline decodes (MetaClient's Body.IMAGE). */
        const val MAX_COVER_BYTES: Int = 2 * 1024 * 1024

        /** How many times a name taken meanwhile is passed over for the next free one. */
        private const val NAME_TRIES = 20
    }

    /** A private copy of [part] for the tag engine (it writes only KJB's own files), as a `.ext` file; null when it can't be made. */
    fun privateCopy(part: File, ext: String): File? {
        val copied = File(private, "tagging.$ext")
        return try {
            private.mkdirs()
            copy(part.toPath(), copied.toPath())
            copied
        } catch (_: IOException) {
            copied.delete()
            null
        }
    }

    /**
     * [tagged] in place of [part], whole or not at all: copied beside it (hidden too), then moved over it in one rename. On
     * any failure [part] stays as it was, the untagged song, and nothing half-copied is ever published.
     */
    fun replacePart(part: File, tagged: File): Boolean {
        val beside = File(part.parentFile, "${part.name}.tagged")
        if (!writes.allows(SharedWrite.DOWNLOAD_SAVE, beside.path)) return false
        return try {
            copy(tagged.toPath(), beside.toPath())
            Files.move(beside.toPath(), part.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) {
            if (writes.allows(SharedWrite.DOWNLOAD_DELETE, beside.path)) beside.delete()
            false
        }
    }

    fun open(item: DownloadItem, file: DlFile, settings: DownloadSettings): Opened {
        if (!canWrite()) return Opened.Refused(DownloadFailure.NOT_ALLOWED)
        val ext = DownloadFolders.extension(file.name, item.kind)
        val folder = File(DownloadFolders.absolute(settings, item.kind, volume.path, writes.testFolderOnly))
        // A name for now; publish takes the one free then.
        val final = DownloadFolders.free(folder, DownloadFolders.safeName(file.name, ext))
        val part = File(folder, ".${final.name}.part")
        // Both in the folder itself, whatever the add-on named the file.
        val inFolder = listOf(final, part).all { it.canonicalFile.parentFile == folder.canonicalFile }
        if (!inFolder || !writes.allows(SharedWrite.DOWNLOAD_SAVE, final.path) || !writes.allows(SharedWrite.DOWNLOAD_SAVE, part.path)) {
            return Opened.Refused(DownloadFailure.NOT_ALLOWED)
        }
        return try {
            folder.mkdirs()
            part.delete()
            if (!part.createNewFile()) return Opened.Refused(DownloadFailure.NOT_ALLOWED)
            // A cover larger than KJB takes is not asked for: the add-on streams nothing into it.
            val cover = file.coverBytes?.takeIf { it in 1..MAX_COVER_BYTES }?.let { File(private.apply { mkdirs() }, "${item.key}.jpg") }
            Opened.Ready(Target(part, cover, final))
        } catch (_: IOException) {
            Opened.Refused(DownloadFailure.NOT_ALLOWED)
        } catch (_: SecurityException) {
            Opened.Refused(DownloadFailure.NOT_ALLOWED)
        }
    }

    /**
     * The whole [target] under its name, or the next free one; null when it could not be moved there. The name is taken
     * by creating the file (it fails when any file or link has it, so nothing is ever replaced), then the part is moved
     * over that empty file of its own; a name taken in between is passed over for the next.
     */
    fun publish(target: Target): File? {
        val folder = target.final.parentFile ?: return null
        repeat(NAME_TRIES) {
            val final = freeName(folder, target.final.name)
            if (!writes.allows(SharedWrite.DOWNLOAD_SAVE, final.path)) return null
            try {
                Files.createFile(final.toPath())
            } catch (_: FileAlreadyExistsException) {
                return@repeat
            } catch (_: IOException) {
                return null
            }
            return try {
                Files.move(target.part.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                final
            } catch (_: IOException) {
                if (writes.allows(SharedWrite.DOWNLOAD_DELETE, final.path)) final.delete()
                null
            }
        }
        return null
    }

    fun abandon(target: Target) {
        if (writes.allows(SharedWrite.DOWNLOAD_DELETE, target.part.path)) target.part.delete()
        target.cover?.delete()
    }
}

/** What finishing a download needs from the phone: KJB's tag engine, the media scan, the library, the lyrics pipeline. */
interface FinishSteps {
    /** Writes [fields] and the front cover from [cover] into [part] with KJB's tag engine; false when it could not. */
    suspend fun tag(song: File, fields: Map<String, List<String>>, cover: File?): Boolean

    /** Tells MediaStore about [saved] and waits for it, so the library shows it at once. */
    suspend fun scan(saved: File, mime: String)

    /** Every song MediaStore knows now; null when it cannot be read. */
    fun library(): List<AudioFile>?

    /** Hands the song to KJB's lyrics pipeline (LRCLIB, then the AI if on). */
    suspend fun lyrics(track: LocalTrack)
}

/**
 * KJB's own steps once a download is whole (the add-on never touches the user's storage): a song gets yt-dlp's tags and
 * its cover written by KJB's tag engine, takes its name, is scanned so the library shows it at once, is checked against
 * the songs on the phone with KJB's Duplicates rules, and goes to the lyrics pipeline. A video is named and scanned.
 */
class DownloadFinish(private val files: DownloadFiles, private val steps: FinishSteps, private val log: (String) -> Unit = {}) : DownloadSaver {
    /** Opened with the settings of the moment ([settings]). */
    var settings: DownloadSettings = DownloadSettings()

    override fun open(item: DownloadItem, file: DlFile): Opened = files.open(item, file, settings)

    override fun abandon(target: Target) = files.abandon(target)

    override suspend fun finish(item: DownloadItem, target: Target, file: DlFile): DownloadOutcome {
        if (target.part.length() != file.bytes) {
            files.abandon(target)
            return DownloadOutcome.Failed(DownloadFailure.HAND_OFF)
        }
        val audio = item.kind == DlKind.AUDIO
        val fields = DownloadTags.fields(item, file)
        if (audio) {
            val copy = files.privateCopy(target.part, target.final.extension)
            val cover = target.cover?.takeIf { it.isFile && it.length() in 1..DownloadFiles.MAX_COVER_BYTES.toLong() }
            val tagged = copy != null && steps.tag(copy, fields, cover) && files.replacePart(target.part, copy)
            copy?.delete()
            if (!tagged) log("downloads: tags not written, yt-dlp's kept")
        }
        target.cover?.delete()
        val saved = files.publish(target) ?: run {
            files.abandon(target)
            return DownloadOutcome.Failed(DownloadFailure.NOT_ALLOWED)
        }
        steps.scan(saved, DownloadFolders.mime(saved.extension))
        if (!audio) return DownloadOutcome.Saved(saved.path, null)
        val library = steps.library()
        val song = library?.firstOrNull { it.path == saved.path }
        val copy = song?.let { DuplicateCheck.copyOf(it, library) }
        val title = fields["TITLE"]?.firstOrNull() ?: saved.nameWithoutExtension
        steps.lyrics(LocalTrack(saved.path, title, fields["ARTIST"]?.firstOrNull(), fields["ALBUM"]?.firstOrNull(), song?.durationMs ?: file.durationSec * MS_PER_SECOND))
        return DownloadOutcome.Saved(saved.path, copy?.path)
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
    }
}
