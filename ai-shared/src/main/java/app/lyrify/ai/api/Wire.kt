package app.lyrify.ai.api

import app.lyrify.ai.AiResult
import app.lyrify.models.Download
import app.lyrify.models.ModelState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The add-on's bound service and its permission, as KJB binds it (IAiAddon). */
object AddonNames {
    /** The bound service's class in the add-on. */
    const val SERVICE: String = "app.lyrify.ai.AiService"

    /** The signature permission only the add-on declares and only KJB, signed with the same key, holds. */
    fun permission(addonPackage: String): String = "$addonPackage.permission.BIND_AI"

    /** A pinned certificate's SHA-256 as hex, as bytes; null when it is empty or not hex: no pin trusts nobody. */
    fun pinBytes(hex: String): ByteArray? =
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            null
        } else {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
}

/**
 * The add-on now (IAiAddon.status): whether it runs the AI on this phone ([supported]: its chip and Android version), its
 * [models] (null when not [supported]), each model's pinned SHA-256 by its AiModels name ([pins], for KJB's count of
 * models that did not load) and the AI's [runtime] for KJB's run log.
 */
@Serializable
data class AddonStatus(
    val supported: Boolean,
    val models: ModelState?,
    val pins: Map<String, String> = emptyMap(),
    val runtime: String = "",
)

/** What the add-on made of one handed-over model file (IAiAddon.importModel). */
@Serializable
data class ImportReply(val outcome: Outcome, val detail: String = "") {
    enum class Outcome {
        /** In place in the add-on, now or from before. */
        IMPORTED,

        /** Not the bytes it should be: its size or SHA-256 differ. Sending it again will not help. */
        MISMATCH,

        /** Not a model file the add-on takes. */
        REFUSED,

        /** Not done this time (storage, a download under way): worth trying again. */
        RETRY,
    }
}

/**
 * The JSON the strings of IAiAddon and its callbacks carry, a sealed type's kind under "type". Fields a later version
 * adds are ignored, so a newer add-on still talks to an older KJB and the other way round.
 */
object Wire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(result: AiResult): String = json.encodeToString(AiResult.serializer(), result)

    /** [text] as a result; one this version cannot read (a later add-on's) is a failed run, `UnknownResult`. */
    fun result(text: String): AiResult = read(text, AiResult.serializer()) ?: AiResult.Failed(UNKNOWN_RESULT)

    fun encode(status: AddonStatus): String = json.encodeToString(AddonStatus.serializer(), status)

    /** [text] as a status; null when it cannot be read. */
    fun status(text: String): AddonStatus? = read(text, AddonStatus.serializer())

    fun encode(end: Download): String = json.encodeToString(Download.serializer(), end)

    /** [text] as a download's end; one that cannot be read is a stop, to try again. */
    fun download(text: String): Download = read(text, Download.serializer()) ?: Download.Interrupted("unreadable end")

    fun encode(reply: ImportReply): String = json.encodeToString(ImportReply.serializer(), reply)

    /** [text] as a reply; one that cannot be read asks to try again. */
    fun reply(text: String): ImportReply = read(text, ImportReply.serializer()) ?: ImportReply(ImportReply.Outcome.RETRY, "unreadable reply")

    /** SerializationException, malformed JSON included, is an IllegalArgumentException. */
    private fun <T> read(text: String, serializer: KSerializer<T>): T? = try {
        json.decodeFromString(serializer, text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private const val UNKNOWN_RESULT = "UnknownResult"
}
