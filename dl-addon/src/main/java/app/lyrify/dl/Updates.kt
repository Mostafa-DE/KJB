package app.lyrify.dl

import app.lyrify.dl.api.DlUpdate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** An RSA public key: its [modulus] and [exponent]. */
data class RsaKey(val modulus: BigInteger, val exponent: BigInteger)

/**
 * yt-dlp's release signing key, pinned in the add-on: "Simon Sawicki (yt-dlp signing key)", RSA 4096, created
 * 2023-02-27, fingerprint AC0C BBE6 848D 6A87 3464 AF4E 57CF 6593 3B5A 7581 (public.key in the yt-dlp repository; it
 * signed release 2026.10.26's SHA2-256SUMS, which gpg verified on 2026-11-15). A new key there needs an add-on release.
 */
object YtdlpKey {
    const val FINGERPRINT: String = "AC0CBBE6848D6A873464AF4E57CF65933B5A7581"

    /** The key's creation time (seconds), part of its fingerprint. */
    const val CREATED: Long = 1_677_455_406

    val key: RsaKey = RsaKey(
        BigInteger(
            "F4AC5F738C63C0B74B6196DE42D5E6F371C0155FB35BD6FF9EA908E4F961197531135428375803399E002B947766FDF6" +
            "6ACD480EFA9018AC1EB418A7A0EDF7D40DFF92D1714A11975E44B61E01AAAA2A14A26D56A50E3BCE04ECEDBB75BAD3B1" +
            "CB113400A80EF04CFB88B765AA9A92FAD0D21CCABDBB2F1AA681D37798DBD9DA22CEF9B3F49CA47F0BE3E013D1F8CADC" +
            "B00DAD76CCCC7A3E66429D538E3AEE97644166754C71750F5F143A9DF4B701298D8B0CE712628D6C698D185A26561EDC" +
            "D5A19BB590ECE39D2E458EAFF204724B2260C7EE93711E6EECFCF9DABABA66405B4316BBA4B7EDA58439E9294986AFF2" +
            "3FDC81EA1FF5FEF673E84B8BA2A90E47A5B941F012BEE167E96A2120EF92E7BEE38F7D920F298C976BFD00ED0A233308" +
            "0EAC3F3CA334FE54290A92427E8A35D9EE01CD8E670169474A2BB7C320439823ADCBC9CE905A41904E28057DF4945D61" +
            "C308EF5A5CD84FDDE8EA15680632D094B2B6E4709916B9663BAB9011CBFD391DB48CDCAE0353D7AAB67C66895D5643C9" +
            "D7B88F49809411BE1E4F4E49F2E4BB71AB3726AAF58EF538C705BAECD7804F10EF5F717C4A2FF08505BCD70D2BEF0200" +
            "2B76AAA53289DC43FCA271395F2FAAB327C10A162A96D7840C4DD027E92BAE653A1D5C24E2227C284FB5A38EE14F28A7" +
            "6F7C7D463F86E7A9DCD39F32F10920C812D7F27CFB085FEF4623B26493FCD98D",
            16,
        ),
        BigInteger.valueOf(65_537),
    )
}

/**
 * An OpenPGP v4 signature of a binary document by an RSA key (RFC 4880, 5.2.3 and 5.2.4): what yt-dlp's
 * SHA2-256SUMS.sig is. Pure; the RSA itself is the platform's (PKCS#1 v1.5 with the signature's hash).
 */
object PgpSignature {
    private const val SIGNATURE_TAG = 2
    private const val VERSION = 4
    private const val BINARY_DOCUMENT = 0
    private const val RSA = 1
    private const val RSA_SIGN_ONLY = 3
    private const val ISSUER = 16
    private const val ISSUER_FINGERPRINT = 33
    private const val BYTE = 0xFF

    private val HASHES = mapOf(8 to "SHA256", 9 to "SHA384", 10 to "SHA512", 11 to "SHA224")

    /**
     * Whether [signature] is [key]'s signature of [data]. With a [fingerprint] (hex), an issuer the signature names must
     * be that key. Anything it cannot read is no signature.
     */
    fun verify(data: ByteArray, signature: ByteArray, key: RsaKey, fingerprint: String?): Boolean = try {
        val body = packet(signature) ?: return false
        check(body, data, key, fingerprint)
    } catch (_: IndexOutOfBoundsException) {
        false
    } catch (_: GeneralSecurityException) {
        false
    }

    /** The body of the one signature packet in [bytes] (old or new header, definite length); null for anything else. */
    private fun packet(bytes: ByteArray): ByteArray? {
        val first = bytes[0].toInt() and BYTE
        if (first and 0x80 == 0) return null
        var at = 1
        val tag: Int
        val length: Int
        if (first and 0x40 != 0) {
            tag = first and 0x3F
            val o1 = bytes[at++].toInt() and BYTE
            length = when {
                o1 < 192 -> o1
                o1 < 224 -> ((o1 - 192) shl 8) + (bytes[at++].toInt() and BYTE) + 192
                o1 == 255 -> int(bytes, at).also { at += 4 }
                else -> return null
            }
        } else {
            tag = (first shr 2) and 0x0F
            length = when (first and 3) {
                0 -> bytes[at++].toInt() and BYTE
                1 -> (((bytes[at].toInt() and BYTE) shl 8) or (bytes[at + 1].toInt() and BYTE)).also { at += 2 }
                2 -> int(bytes, at).also { at += 4 }
                else -> return null
            }
        }
        if (tag != SIGNATURE_TAG || length < 0 || at + length != bytes.size) return null
        return bytes.copyOfRange(at, at + length)
    }

    private fun check(body: ByteArray, data: ByteArray, key: RsaKey, fingerprint: String?): Boolean {
        if (body[0].toInt() != VERSION || body[1].toInt() != BINARY_DOCUMENT) return false
        val algorithm = body[2].toInt() and BYTE
        if (algorithm != RSA && algorithm != RSA_SIGN_ONLY) return false
        val hash = HASHES[body[3].toInt() and BYTE] ?: return false
        val hashedLength = short(body, 4)
        val hashedEnd = 6 + hashedLength
        val unhashedLength = short(body, hashedEnd)
        val unhashedEnd = hashedEnd + 2 + unhashedLength
        val left = body.copyOfRange(unhashedEnd, unhashedEnd + 2)
        val bits = short(body, unhashedEnd + 2)
        val value = body.copyOfRange(unhashedEnd + 4, unhashedEnd + 4 + (bits + 7) / 8)
        if (unhashedEnd + 4 + value.size != body.size) return false
        if (fingerprint != null && !issuedBy(body.copyOfRange(6, hashedEnd) + body.copyOfRange(hashedEnd + 2, unhashedEnd), fingerprint)) return false
        val hashed = body.copyOfRange(0, hashedEnd)
        val trailer = byteArrayOf(VERSION.toByte(), BYTE.toByte()) + ByteArray(4) { (hashed.size shr (8 * (3 - it))).toByte() }
        val digest = MessageDigest.getInstance(hash.replace("SHA", "SHA-")).apply {
            update(data)
            update(hashed)
            update(trailer)
        }.digest()
        if (!digest.copyOf(2).contentEquals(left)) return false
        val size = (key.modulus.bitLength() + 7) / 8
        if (value.size > size) return false
        val public = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, key.exponent))
        return Signature.getInstance("${hash}withRSA").run {
            initVerify(public)
            update(data)
            update(hashed)
            update(trailer)
            verify(ByteArray(size - value.size) + value)
        }
    }

    /** Whether the issuer the [subpackets] name (a fingerprint, or a key id) is the key of [fingerprint]; true when they name none. */
    private fun issuedBy(subpackets: ByteArray, fingerprint: String): Boolean {
        val pinned = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var at = 0
        while (at < subpackets.size) {
            val o1 = subpackets[at].toInt() and BYTE
            val (length, header) = when {
                o1 < 192 -> o1 to 1
                o1 < 255 -> (((o1 - 192) shl 8) + (subpackets[at + 1].toInt() and BYTE) + 192) to 2
                else -> int(subpackets, at + 1) to 5
            }
            val type = subpackets[at + header].toInt() and 0x7F
            val content = subpackets.copyOfRange(at + header + 1, at + header + length)
            when (type) {
                ISSUER_FINGERPRINT -> if (!content.copyOfRange(1, content.size).contentEquals(pinned)) return false
                ISSUER -> if (!content.contentEquals(pinned.copyOfRange(pinned.size - 8, pinned.size))) return false
            }
            at += header + length
        }
        return true
    }

    private fun short(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and BYTE) shl 8) or (bytes[at + 1].toInt() and BYTE)

    private fun int(bytes: ByteArray, at: Int): Int = (0 until 4).fold(0) { value, index -> (value shl 8) or (bytes[at + index].toInt() and BYTE) }
}

/** A SHA2-256SUMS file: "<sha256>  <name>" a line. */
object YtdlpSums {
    private val LINE = Regex("([0-9a-fA-F]{64}) [ *](.+)")

    /** The SHA-256 (lowercase hex) [sums] give [name]; null when it has none. */
    fun sha256(sums: ByteArray, name: String): String? =
        sums.toString(Charsets.UTF_8).lineSequence().mapNotNull { LINE.matchEntire(it.trimEnd()) }.firstOrNull { it.groupValues[2] == name }?.groupValues?.get(1)?.lowercase()
}

/** yt-dlp's latest stable release as GitHub's API gives it: its tag and name, and its three files on github.com. */
data class YtdlpRelease(val tag: String, val name: String, val binary: URI, val sums: URI, val signature: URI) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val TAG = Regex("[0-9A-Za-z.\\-]{1,40}")

        /** The release in [answer]; null unless its three files are yt-dlp's own on github.com, of that very tag. */
        fun parse(answer: ByteArray): YtdlpRelease? {
            val root = try {
                json.parseToJsonElement(answer.toString(Charsets.UTF_8)) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            fun text(obj: JsonObject, key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val tag = text(root, "tag_name")?.takeIf(TAG::matches) ?: return null
            val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun asset(name: String): URI? {
                val url = assets.firstOrNull { text(it, "name") == name }?.let { text(it, "browser_download_url") } ?: return null
                return url.takeIf { it == "https://github.com/yt-dlp/yt-dlp/releases/download/$tag/$name" }?.let(::URI)
            }
            return YtdlpRelease(tag, text(root, "name") ?: tag, asset(BINARY) ?: return null, asset(SUMS) ?: return null, asset(SIGNATURE) ?: return null)
        }

        const val BINARY: String = "yt-dlp"
        const val SUMS: String = "SHA2-256SUMS"
        const val SIGNATURE: String = "SHA2-256SUMS.sig"
    }
}

/**
 * Downloads from GitHub only: https on the default port, starting at api.github.com or github.com, through at most
 * [MAX_REDIRECTS] redirects, each checked first and only to github.com or its release asset hosts, and at most
 * maxBytes; null for anything else. Blocking.
 */
class GitHubFetch(private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }, private val userAgent: String = "") {
    fun get(uri: URI, maxBytes: Int): ByteArray? {
        if (!allowed(uri, START)) return null
        var at = uri
        repeat(MAX_REDIRECTS + 1) {
            val connection = try {
                open(at)
            } catch (_: IOException) {
                return null
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (userAgent.isNotEmpty()) connection.setRequestProperty("User-Agent", userAgent)
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.use { input -> read(input, maxBytes) }
                    in REDIRECTS -> {
                        val next = connection.getHeaderField("Location")?.let { location ->
                            try {
                                at.resolve(location)
                            } catch (_: IllegalArgumentException) {
                                null
                            }
                        } ?: return null
                        if (!allowed(next, ASSETS)) return null
                        at = next
                    }
                    else -> return null
                }
            } catch (_: IOException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** At most [max] bytes of [input]; null when there are more. */
    private fun read(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > max) return null
            out.write(buffer, 0, read)
        }
    }

    private fun allowed(uri: URI, hosts: Set<String>): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && (uri.port == -1 || uri.port == HTTPS_PORT) && uri.rawUserInfo == null && uri.host?.lowercase() in hosts

    companion object {
        const val MAX_REDIRECTS: Int = 3
        private val START = setOf("api.github.com", "github.com")
        private val ASSETS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val HTTPS_PORT = 443
        private const val TIMEOUT_MS = 15_000
        private const val CHUNK = 64 * 1024
    }
}

/**
 * yt-dlp's update, checked before it is used (youtubedl-android's own updater installs whatever it downloads): the latest
 * stable release's SHA2-256SUMS must carry yt-dlp's signature ([key], pinned), and the downloaded yt-dlp must have the
 * SHA-256 that file gives it; only then is it put in place ([install]). On anything else the yt-dlp in place stays.
 * [current] is the release tag in place. Blocking.
 */
class YtdlpUpdater(
    private val fetch: (URI, Int) -> ByteArray?,
    private val current: () -> String?,
    private val install: (tag: String, binary: ByteArray) -> Boolean,
    private val key: RsaKey = YtdlpKey.key,
    private val fingerprint: String? = YtdlpKey.FINGERPRINT,
) {
    fun update(): DlUpdate.Outcome {
        val release = fetch(LATEST, MAX_ANSWER)?.let(YtdlpRelease::parse) ?: return DlUpdate.Outcome.FAILED
        if (release.tag == current()) return DlUpdate.Outcome.CURRENT
        val sums = fetch(release.sums, MAX_SUMS) ?: return DlUpdate.Outcome.FAILED
        val signature = fetch(release.signature, MAX_SIGNATURE) ?: return DlUpdate.Outcome.FAILED
        if (!PgpSignature.verify(sums, signature, key, fingerprint)) return DlUpdate.Outcome.FAILED
        val expected = YtdlpSums.sha256(sums, YtdlpRelease.BINARY) ?: return DlUpdate.Outcome.FAILED
        val binary = fetch(release.binary, MAX_BINARY) ?: return DlUpdate.Outcome.FAILED
        val actual = MessageDigest.getInstance("SHA-256").digest(binary).joinToString("") { "%02x".format(it) }
        if (actual != expected) return DlUpdate.Outcome.FAILED
        return if (install(release.tag, binary)) DlUpdate.Outcome.UPDATED else DlUpdate.Outcome.FAILED
    }

    companion object {
        val LATEST: URI = URI("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        private const val MAX_ANSWER = 1024 * 1024
        private const val MAX_SUMS = 64 * 1024
        private const val MAX_SIGNATURE = 16 * 1024
        private const val MAX_BINARY = 64 * 1024 * 1024
    }
}
