package app.lyrify.dl

import app.lyrify.dl.api.DlUpdate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * yt-dlp's own updates, checked before they are used: its release's SHA2-256SUMS must carry yt-dlp's signature (their
 * OpenPGP key, pinned in the add-on), and the downloaded yt-dlp must have the SHA-256 that file gives it. Anything else
 * keeps the yt-dlp in place. The fixture is yt-dlp 2026.10.26's own SHA2-256SUMS and its signature.
 */
class UpdatesTest {
    private fun resource(name: String): ByteArray = javaClass.getResource("/ytdlp/$name")!!.readBytes()

    private val sums = resource("SHA2-256SUMS-2026.10.26")
    private val signature = resource("SHA2-256SUMS-2026.10.26.sig")

    // The signature

    @Test
    fun `yt-dlp's own signature of its sums verifies with the pinned key`() {
        assertTrue(PgpSignature.verify(sums, signature, YtdlpKey.key, YtdlpKey.FINGERPRINT))
    }

    @Test
    fun `a changed byte, another key, or a broken signature does not`() {
        val changed = sums.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFalse(PgpSignature.verify(changed, signature, YtdlpKey.key, YtdlpKey.FINGERPRINT))
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().public as RSAPublicKey
        assertFalse(PgpSignature.verify(sums, signature, RsaKey(other.modulus, other.publicExponent), null))
        assertFalse(PgpSignature.verify(sums, signature.copyOf(signature.size - 3), YtdlpKey.key, YtdlpKey.FINGERPRINT))
        assertFalse(PgpSignature.verify(sums, byteArrayOf(), YtdlpKey.key, YtdlpKey.FINGERPRINT))
        assertFalse(PgpSignature.verify(sums, ByteArray(40) { 0x41 }, YtdlpKey.key, YtdlpKey.FINGERPRINT))
        // Its own key, but a signature that names another issuer.
        assertFalse(PgpSignature.verify(sums, signature, YtdlpKey.key, "00".repeat(20)))
    }

    @Test
    fun `the pinned key is the one with yt-dlp's published fingerprint`() {
        fun mpi(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return byteArrayOf((value.bitLength() shr 8).toByte(), value.bitLength().toByte()) + bytes
        }
        val body = byteArrayOf(4) + BigInteger.valueOf(YtdlpKey.CREATED).toByteArray().takeLast(4).toByteArray() + byteArrayOf(1) +
            mpi(YtdlpKey.key.modulus) + mpi(YtdlpKey.key.exponent)
        val packet = byteArrayOf(0x99.toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        val fingerprint = MessageDigest.getInstance("SHA-1").digest(packet).joinToString("") { "%02X".format(it) }

        assertEquals(YtdlpKey.FINGERPRINT, fingerprint)
        assertEquals(4096, YtdlpKey.key.modulus.bitLength())
    }

    @Test
    fun `the sums give yt-dlp's own line, nothing else`() {
        assertEquals("e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3", YtdlpSums.sha256(sums, "yt-dlp"))
        assertNull(YtdlpSums.sha256(sums, "yt-dlp.exe-not-there"))
        assertNull(YtdlpSums.sha256("nonsense\n".toByteArray(), "yt-dlp"))
    }

    // The release

    private fun release(host: String = "github.com", tag: String = "2026.11.01"): String {
        fun asset(name: String) = """{"name":"$name","browser_download_url":"https://$host/yt-dlp/yt-dlp/releases/download/$tag/$name"}"""
        return """{"tag_name":"$tag","name":"yt-dlp $tag","assets":[${asset("yt-dlp.exe")},${asset("yt-dlp")},${asset("SHA2-256SUMS")},${asset("SHA2-256SUMS.sig")}]}"""
    }

    @Test
    fun `a release's three files must be yt-dlp's own, on github dot com, of that tag`() {
        val parsed = YtdlpRelease.parse(release().toByteArray())!!

        assertEquals("2026.11.01", parsed.tag)
        assertEquals(URI("https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.01/yt-dlp"), parsed.binary)
        assertNull(YtdlpRelease.parse(release(host = "evil.example.com").toByteArray()))
        assertNull(YtdlpRelease.parse(release().replace("releases/download/2026.11.01/SHA2-256SUMS\"", "releases/download/2026.01.01/SHA2-256SUMS\"").toByteArray()))
        assertNull(YtdlpRelease.parse(release().replace(",{\"name\":\"SHA2-256SUMS.sig\"", ",{\"name\":\"other\"").toByteArray()))
        assertNull(YtdlpRelease.parse("not json".toByteArray()))
    }

    // The whole update, with a test key standing in for yt-dlp's

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    private val testKey = (keys.public as RSAPublicKey).let { RsaKey(it.modulus, it.publicExponent) }

    /** An OpenPGP v4 signature of [data] by the test key, as gpg makes them (new packet format, SHA-512). */
    private fun sign(data: ByteArray): ByteArray {
        val created = byteArrayOf(5, 2, 0x6A, 0, 0, 0)
        val hashed = byteArrayOf(4, 0, 1, 10, 0, created.size.toByte()) + created
        val trailer = byteArrayOf(4, 0xFF.toByte()) + BigInteger.valueOf(hashed.size.toLong()).toByteArray().let { ByteArray(4 - it.size) + it }
        val signed = Signature.getInstance("SHA512withRSA").apply {
            initSign(keys.private)
            update(data)
            update(hashed)
            update(trailer)
        }.sign()
        val digest = MessageDigest.getInstance("SHA-512").digest(data + hashed + trailer)
        val bits = BigInteger(1, signed).bitLength()
        val body = hashed + byteArrayOf(0, 0, digest[0], digest[1], (bits shr 8).toByte(), bits.toByte()) + signed.dropWhile { it == 0.toByte() }
        val length = body.size - 192
        return byteArrayOf(0xC2.toByte(), ((length shr 8) + 192).toByte(), length.toByte()) + body
    }

    private val binary = ByteArray(5_000) { (it % 97).toByte() }
    private val goodSums = (MessageDigest.getInstance("SHA-256").digest(binary).joinToString("") { "%02x".format(it) } + "  yt-dlp\n" + "0".repeat(64) + "  yt-dlp.exe\n").toByteArray()

    private class Installs {
        val installed = mutableListOf<Pair<String, ByteArray>>()
    }

    private fun updater(files: Map<String, ByteArray?>, current: String? = "2026.10.26", installs: Installs = Installs(), install: Boolean = true) = YtdlpUpdater(
        fetch = { uri, _ -> files[uri.toString().substringAfterLast('/')] },
        current = { current },
        install = { tag, bytes ->
            installs.installed += tag to bytes
            install
        },
        key = testKey,
        fingerprint = null,
    )

    private fun files(sums: ByteArray = goodSums, sig: ByteArray? = sign(goodSums), yt: ByteArray? = binary) =
        mapOf("latest" to release().toByteArray(), "SHA2-256SUMS" to sums, "SHA2-256SUMS.sig" to sig, "yt-dlp" to yt)

    @Test
    fun `a yt-dlp whose SHA-256 the signed sums give is put in place`() {
        val installs = Installs()

        assertEquals(DlUpdate.Outcome.UPDATED, updater(files(), installs = installs).update())
        assertEquals("2026.11.01", installs.installed.single().first)
        assertArrayEquals(binary, installs.installed.single().second)
    }

    @Test
    fun `a changed yt-dlp, sums that are not signed, or a piece that did not come keeps the old one`() {
        val tampered = binary.copyOf().also { it[0] = 99 }
        val unsignedSums = goodSums + "extra\n".toByteArray()
        listOf(
            files(yt = tampered),
            files(sums = unsignedSums),
            files(sig = sign("other".toByteArray())),
            files(sig = null),
            files(yt = null),
        ).forEach { case ->
            val installs = Installs()
            assertEquals(DlUpdate.Outcome.FAILED, updater(case, installs = installs).update())
            assertTrue(installs.installed.isEmpty())
        }
    }

    @Test
    fun `the release in place is current, and nothing more is downloaded`() {
        val asked = mutableListOf<String>()
        val updater = YtdlpUpdater(
            fetch = { uri, _ ->
                asked += uri.toString()
                files()[uri.toString().substringAfterLast('/')]
            },
            current = { "2026.11.01" },
            install = { _, _ -> error("not installed") },
            key = testKey,
            fingerprint = null,
        )

        assertEquals(DlUpdate.Outcome.CURRENT, updater.update())
        assertEquals(listOf(YtdlpUpdater.LATEST.toString()), asked)
    }

    @Test
    fun `an install that could not be written is a failed update`() {
        assertEquals(DlUpdate.Outcome.FAILED, updater(files(), install = false).update())
    }

    // The downloads from GitHub

    private class FakeConnection(url: URL, private val code: Int, private val location: String?, private val body: ByteArray) : HttpURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getHeaderField(name: String?): String? = if (name.equals("Location", ignoreCase = true)) location else null

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
    }

    @Test
    fun `GitHub's files are fetched over https, through at most three redirects, each to GitHub's own hosts, within a size`() {
        val opened = mutableListOf<String>()
        fun fetch(responses: Map<String, Triple<Int, String?, ByteArray>>) = GitHubFetch(open = { uri ->
            opened += uri.toString()
            val (code, location, body) = responses.getValue(uri.toString())
            FakeConnection(uri.toURL(), code, location, body)
        })
        val asset = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.01/yt-dlp"
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?x=y"
        val ok = mapOf(asset to Triple(302, cdn, ByteArray(0)), cdn to Triple(200, null, binary))

        assertArrayEquals(binary, fetch(ok).get(URI(asset), 10_000))
        assertNull("too big", fetch(ok).get(URI(asset), 100))
        assertNull("off GitHub", fetch(mapOf(asset to Triple(302, "https://evil.example.com/yt-dlp", ByteArray(0)))).get(URI(asset), 10_000))
        assertNull("plain http", fetch(mapOf(asset to Triple(302, "http://release-assets.githubusercontent.com/x", ByteArray(0)))).get(URI(asset), 10_000))
        val loop = (0..4).associate { "https://github.com/r/$it" to Triple(302, "https://github.com/r/${it + 1}", ByteArray(0)) }
        assertNull("too many redirects", fetch(loop).get(URI("https://github.com/r/0"), 10_000))
        opened.clear()
        assertNull("not a GitHub start", fetch(ok).get(URI("https://evil.example.com/yt-dlp"), 10_000))
        assertTrue(opened.isEmpty())
    }
}
