package app.lyrify.downloads

import app.lyrify.dl.api.PublicAddresses
import app.lyrify.dl.api.isWebLink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * The share sheet's cover, from the address the site gave (yt-dlp's thumbnail or YouTube's oEmbed one): https only, on the
 * default port, with no user name; from a host out on the internet ([PublicAddresses]: never this phone, the home network
 * or a cloud's metadata address), checked again at each of at most [MAX_REDIRECTS] redirects; at most [MAX_BYTES]. A
 * host that does not resolve is not asked. Blocking. Android resolves the host again as it connects, so a host that
 * changes its answer in between (DNS rebinding) is not caught here; the cover is only shown, never sent anywhere.
 */
object Thumbnails {
    const val MAX_BYTES: Int = 2 * 1024 * 1024
    const val MAX_REDIRECTS: Int = 3

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    private const val HTTPS_PORT = 443
    private const val TIMEOUT_MS = 5_000
    private const val CHUNK = 16 * 1024

    fun fetch(
        url: String,
        open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
        resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): ByteArray? {
        var at = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(at, resolve)) return null
            val connection = try {
                open(at)
            } catch (_: IOException) {
                return null
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection.inputStream.use(::capped)
                    in REDIRECTS -> at = connection.getHeaderField("Location")?.let { location ->
                        try {
                            at.resolve(location)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    } ?: return null
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

    private fun allowed(uri: URI, resolve: (String) -> List<InetAddress>): Boolean {
        val text = uri.toString()
        return uri.scheme.equals("https", ignoreCase = true) && (uri.port == -1 || uri.port == HTTPS_PORT) && uri.rawUserInfo == null &&
            isWebLink(text) && PublicAddresses.of(text, resolve) == PublicAddresses.Verdict.PUBLIC
    }

    /** At most [MAX_BYTES] of [input]; null when there are more. */
    private fun capped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
    }
}
