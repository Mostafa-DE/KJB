package app.lyrify.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

/**
 * The share sheet's cover, fetched by KJB from the address the site gave: https only, from hosts on the internet (never
 * this phone or the home network, checked again at each redirect), through at most three redirects, within 2 MB.
 */
class ThumbnailsTest {
    private class FakeConnection(url: URL, private val code: Int, private val location: String?, private val body: ByteArray) : HttpURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getHeaderField(name: String?): String? = if (name.equals("Location", ignoreCase = true)) location else null

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    private val image = ByteArray(1_000) { it.toByte() }
    private val opened = mutableListOf<String>()
    private val dns = mapOf("img.example.com" to "93.184.216.34", "cdn.example.com" to "93.184.216.35", "router.example.com" to "192.168.1.1", "meta.example.com" to "169.254.169.254")

    private fun fetch(url: String, responses: Map<String, Triple<Int, String?, ByteArray>>): ByteArray? = Thumbnails.fetch(
        url,
        open = { uri ->
            opened += uri.toString()
            val (code, location, body) = responses.getValue(uri.toString())
            FakeConnection(uri.toURL(), code, location, body)
        },
        resolve = { host -> listOf(InetAddress.getByName(dns[host] ?: throw UnknownHostException(host))) },
    )

    @Test
    fun `a cover on a public https host comes through a redirect or two`() {
        val responses = mapOf(
            "https://img.example.com/t.jpg" to Triple(302, "https://cdn.example.com/t.jpg", ByteArray(0)),
            "https://cdn.example.com/t.jpg" to Triple(200, null, image),
        )

        assertArrayEquals(image, fetch("https://img.example.com/t.jpg", responses))
        assertEquals(listOf("https://img.example.com/t.jpg", "https://cdn.example.com/t.jpg"), opened)
    }

    @Test
    fun `plain http, a host on this phone or the home network, or one that does not resolve is never opened`() {
        val any = mapOf<String, Triple<Int, String?, ByteArray>>()
        listOf(
            "http://img.example.com/t.jpg",
            "https://router.example.com/t.jpg",
            "https://meta.example.com/latest",
            "https://127.0.0.1/t.jpg",
            "https://[::1]/t.jpg",
            "https://user:pw@img.example.com/t.jpg",
            "https://img.example.com:8443/t.jpg",
            "https://offline.example.com/t.jpg",
        ).forEach { assertNull(it, fetch(it, any)) }
        assertTrue(opened.toString(), opened.isEmpty())
    }

    @Test
    fun `a redirect to the home network, to plain http, or past three is not followed`() {
        assertNull(fetch("https://img.example.com/a", mapOf("https://img.example.com/a" to Triple(302, "https://router.example.com/t.jpg", ByteArray(0)))))
        assertNull(fetch("https://img.example.com/b", mapOf("https://img.example.com/b" to Triple(301, "http://cdn.example.com/t.jpg", ByteArray(0)))))
        val loop = (0..4).associate { "https://img.example.com/r$it" to Triple(302, "https://img.example.com/r${it + 1}", ByteArray(0)) }
        assertNull(fetch("https://img.example.com/r0", loop))
        assertEquals(4, opened.count { it.contains("/r") })
    }

    @Test
    fun `a cover over 2 MB is not taken`() {
        val big = ByteArray(Thumbnails.MAX_BYTES + 1)
        assertNull(fetch("https://img.example.com/big.jpg", mapOf("https://img.example.com/big.jpg" to Triple(200, null, big))))
    }
}
