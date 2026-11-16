package app.lyrify.auto

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import app.lyrify.LOG_TAG
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The uris of KJB's covers ([CarCovers]): `content://<authority>/<path>/<signature>`, the path a song's MediaStore id or
 * [DEFAULT_COVER] ([coverPath]), never a file's path; the signature an HMAC of the path under this install's own [key]
 * ([CoverKey]). The provider is exported, so whoever is handed a uri (Android Auto's lists, queue and song playing, the
 * notification, the lock screen) reads that one cover, whichever of its processes asks; nobody can make a uri for
 * another cover. It replaces a grant given as a car connected, which could miss Android Auto: Media3 tells KJB of a
 * controller that only listens (Android Auto's player and queue) at its first command, and each service start took
 * the grants back (CR1).
 */
class CoverUris(val authority: String, key: ByteArray) {
    /**
     * One Mac per thread under [key] (a Mac is not thread-safe): the service signs a whole queue on the main thread, and a
     * Mac made per song, with its hex made by `format`, cost a 7,000-song queue about 30 times as long.
     */
    private val macs: ThreadLocal<Mac> = ThreadLocal.withInitial { Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) } }

    /** The uri of the cover at [path]. */
    fun of(path: String): Uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath(path).appendPath(sign(path)).build()

    /** [path]'s signature: the first 128 bits of its HMAC-SHA256, in lower-case hex. */
    fun sign(path: String): String {
        val digest = macs.get().doFinal(path.toByteArray(Charsets.UTF_8))
        return String(CharArray(SIGNATURE_BYTES * 2) { i -> HEX[(digest[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0xF] })
    }

    /** The cover path a uri's [segments] name, when they are a path and its own signature; null for anything else. */
    fun pathOf(segments: List<String>): String? {
        val (path, signature) = segments.takeIf { it.size == 2 } ?: return null
        if (path != DEFAULT_COVER && !ID.matches(path)) return null
        return path.takeIf { MessageDigest.isEqual(sign(it).toByteArray(), signature.toByteArray()) }
    }

    companion object {
        private const val HMAC = "HmacSHA256"
        private const val SIGNATURE_BYTES = 16
        private const val HEX = "0123456789abcdef"

        /** A MediaStore id: digits only, so no uri reaches anything but the library's own covers. */
        private val ID = Regex("[0-9]{1,18}")
    }
}

/**
 * This install's cover key: 32 random bytes in `files/covers.key`, made on first use, so the covers' uris stay the same
 * across runs (Android Auto keeps covers by uri). Not in any backup: on another phone the MediaStore ids differ anyway.
 */
object CoverKey {
    private const val BYTES = 32

    @Volatile
    private var key: ByteArray? = null

    @Synchronized
    fun of(context: Context): ByteArray = key ?: read(File(context.filesDir, "covers.key")).also { key = it }

    /** The key in [file], or a new one written there (whole, then renamed into place) when it is missing or not a key. */
    internal fun read(file: File): ByteArray {
        val kept = try {
            file.takeIf { it.isFile }?.readBytes()
        } catch (e: IOException) {
            Log.w(LOG_TAG, "cover key not read: ${e.javaClass.simpleName}")
            null
        }
        if (kept?.size == BYTES) return kept
        val made = ByteArray(BYTES).also(SecureRandom()::nextBytes)
        try {
            val next = File(file.parentFile, "${file.name}.new")
            next.writeBytes(made)
            if (!next.renameTo(file)) throw IOException("rename")
        } catch (e: IOException) {
            // This run's covers still work; the next run makes a key again, and the car reads its covers once more.
            Log.w(LOG_TAG, "cover key not written: ${e.javaClass.simpleName}")
        }
        return made
    }
}
