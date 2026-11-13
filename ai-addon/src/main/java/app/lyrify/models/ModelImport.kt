package app.lyrify.models

import app.lyrify.ai.api.ImportReply
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The handover of KJB 2.0's models into [store] (IAiAddon.importModel): one file at a time, each under its name in the
 * models folder, with the size and SHA-256 KJB sends. Only the store's own names are taken (nothing can land elsewhere).
 * A file is written through a `.part` and moved into place only once its bytes match:
 * - a single model must also be the pinned one ([ModelSpec.sha256]), so it is in place as a downloaded one would be;
 * - Whisper's files are recorded in a journal in its folder as they come; its manifest comes last, must name the pinned
 *   archive and the journal's files, and puts the folder in place ([ModelStore.has]) when it is written.
 * What the add-on holds already as given is not read again, so KJB may send everything again after a kill. A file cut
 * short leaves nothing in place. The caller holds the store's lock, so no download writes meanwhile; [freeBytes] is
 * the room where the models go.
 */
class ModelImport(private val store: ModelStore, private val freeBytes: (File) -> Long = File::getUsableSpace) {
    private val whisper = store.specs.firstOrNull { it.unpack != null }
    private val singles = store.specs.filter { it.unpack == null }.associateBy { it.name }

    fun import(name: String, input: InputStream, bytes: Long, sha256: String): ImportReply {
        val sha = sha256.lowercase()
        singles[name]?.let { return single(it, input, bytes, sha) }
        val archive = whisper
        val unpack = archive?.unpack
        if (archive != null && unpack != null && name.startsWith(archive.name + "/")) {
            val file = name.removePrefix(archive.name + "/")
            if (file == ModelStore.MANIFEST) return manifest(archive, unpack, input, bytes, sha)
            if (file in unpack.files) return unpacked(archive, file, input, bytes, sha)
        }
        return reply(ImportReply.Outcome.REFUSED, "not a model file")
    }

    private fun single(spec: ModelSpec, input: InputStream, bytes: Long, sha: String): ImportReply {
        if (sha != spec.sha256 || bytes != spec.bytes) return reply(ImportReply.Outcome.MISMATCH, "not the pinned file")
        if (store.has(spec)) return IMPORTED
        store.folder.mkdirs()
        if (freeBytes(store.folder) < bytes + ModelStore.SPARE_BYTES) return reply(ImportReply.Outcome.RETRY, "no room")
        val part = store.part(spec)
        return copy(input, part, bytes, sha) ?: move(part, store.file(spec)) ?: IMPORTED
    }

    private fun unpacked(spec: ModelSpec, file: String, input: InputStream, bytes: Long, sha: String): ImportReply {
        if (store.has(spec)) return IMPORTED
        val folder = store.file(spec)
        folder.mkdirs()
        val journal = journal(folder)
        val entry = ModelStore.Entry(bytes, sha)
        val target = File(folder, file)
        if (journal[file] == entry && target.length() == bytes) return IMPORTED
        if (freeBytes(folder) < bytes + ModelStore.SPARE_BYTES) return reply(ImportReply.Outcome.RETRY, "no room")
        val part = File(folder, file + ModelStore.PART)
        copy(input, part, bytes, sha)?.let { return it }
        move(part, target)?.let { return it }
        return write(File(folder, JOURNAL), json.encodeToString(JOURNAL_FORMAT, journal + (file to entry))) ?: IMPORTED
    }

    private fun manifest(spec: ModelSpec, unpack: ModelSpec.Unpack, input: InputStream, bytes: Long, sha: String): ImportReply {
        if (store.has(spec)) return IMPORTED
        if (bytes > MAX_MANIFEST_BYTES) return reply(ImportReply.Outcome.REFUSED, "manifest too large")
        val text = try {
            readAtMost(input, MAX_MANIFEST_BYTES)
        } catch (e: IOException) {
            return reply(ImportReply.Outcome.RETRY, e.javaClass.simpleName)
        }
        if (text.size.toLong() != bytes || hex(MessageDigest.getInstance("SHA-256").digest(text)) != sha) {
            return reply(ImportReply.Outcome.MISMATCH, "sha256")
        }
        val manifest = try {
            json.decodeFromString(ModelStore.Manifest.serializer(), text.decodeToString())
        } catch (_: IllegalArgumentException) {
            return reply(ImportReply.Outcome.REFUSED, "manifest unreadable")
        }
        if (manifest.archiveSha256 != spec.sha256) return reply(ImportReply.Outcome.MISMATCH, "another archive")
        if (manifest.files.keys != unpack.files.toSet()) return reply(ImportReply.Outcome.REFUSED, "not the archive's files")
        val folder = store.file(spec)
        val journal = journal(folder)
        val missing = manifest.files.filter { (name, entry) -> journal[name] != entry || File(folder, name).length() != entry.bytes }.keys
        // KJB sends the manifest last; a file it has not sent yet (or one gone since) is sent again.
        if (missing.isNotEmpty()) return reply(ImportReply.Outcome.RETRY, "files to come: ${missing.size}")
        write(File(folder, ModelStore.MANIFEST), text.decodeToString())?.let { return it }
        File(folder, JOURNAL).delete()
        return IMPORTED
    }

    /** [input] into [part], counted and hashed; null when it is [bytes] long with SHA-256 [sha], else the reply (the part deleted). */
    private fun copy(input: InputStream, part: File, bytes: Long, sha: String): ImportReply? {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > bytes) break
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
                out.fd.sync()
            }
        } catch (e: IOException) {
            part.delete()
            return reply(ImportReply.Outcome.RETRY, e.javaClass.simpleName)
        }
        if (count != bytes || hex(digest.digest()) != sha) {
            part.delete()
            return reply(ImportReply.Outcome.MISMATCH, "sha256")
        }
        return null
    }

    private fun move(from: File, to: File): ImportReply? = try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        null
    } catch (e: IOException) {
        reply(ImportReply.Outcome.RETRY, e.javaClass.simpleName)
    }

    /** [text] into [file], whole or not at all (written aside, then moved). */
    private fun write(file: File, text: String): ImportReply? {
        val aside = File(file.path + ModelStore.PART)
        return try {
            aside.writeText(text)
            move(aside, file)
        } catch (e: IOException) {
            aside.delete()
            reply(ImportReply.Outcome.RETRY, e.javaClass.simpleName)
        }
    }

    /** Whisper's files taken so far, by name; none when there is no journal or it cannot be read. */
    private fun journal(folder: File): Map<String, ModelStore.Entry> = try {
        File(folder, JOURNAL).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(JOURNAL_FORMAT, it) }.orEmpty()
    } catch (_: IOException) {
        emptyMap()
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }

    private fun readAtMost(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > max) break
        }
        return out.toByteArray()
    }

    private fun reply(outcome: ImportReply.Outcome, detail: String) = ImportReply(outcome, detail)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        val IMPORTED = ImportReply(ImportReply.Outcome.IMPORTED)

        /** The journal of Whisper's files taken so far, in its folder until the manifest puts the folder in place. */
        const val JOURNAL = "handover.json"
        val JOURNAL_FORMAT = MapSerializer(String.serializer(), ModelStore.Entry.serializer())

        /** AI Hub's manifest lists five files: a few hundred bytes. */
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val BUFFER_BYTES = 1024 * 1024

        val json = Json { ignoreUnknownKeys = true }
    }
}
