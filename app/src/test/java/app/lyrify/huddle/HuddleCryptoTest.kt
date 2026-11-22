package app.lyrify.huddle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Huddle's crypto: the RFC test vectors, key agreement both ways, sealed boxes that refuse any change, base64url. */
class HuddleCryptoTest {
    private val random = SecureRandom()

    @Test
    fun `HKDF-SHA256 gives RFC 5869's first test case`() {
        val okm = HuddleCrypto.hkdf(
            secret = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun `HKDF-SHA256 gives RFC 5869's third test case, without salt or info`() {
        val okm = HuddleCrypto.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), okm)
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 gives RFC 7914's vector`() {
        val key = HuddleCrypto.passphraseKey("passwd".toCharArray(), "salt".toByteArray(), 1)
        assertArrayEquals(hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"), key)
    }

    @Test
    fun `two phones agree on one secret, a third gets another`() {
        val a = HuddleCrypto.keyPair(random)
        val b = HuddleCrypto.keyPair(random)
        val c = HuddleCrypto.keyPair(random)
        assertEquals(HuddleCrypto.PUBLIC_KEY_BYTES, a.public.size)
        assertEquals(0x04.toByte(), a.public[0])
        val ab = HuddleCrypto.agree(a.private, b.public)
        assertNotNull(ab)
        assertEquals(32, ab!!.size)
        assertArrayEquals(ab, HuddleCrypto.agree(b.private, a.public))
        assertFalse(ab.contentEquals(HuddleCrypto.agree(c.private, a.public)))
    }

    @Test
    fun `a public key that is not a point of P-256 is refused`() {
        val a = HuddleCrypto.keyPair(random)
        val b = HuddleCrypto.keyPair(random).public
        val offCurve = b.copyOf().also { it[64] = (it[64] + 1).toByte() }
        assertNull(HuddleCrypto.agree(a.private, offCurve))
        assertNull(HuddleCrypto.agree(a.private, b.copyOf(64)))
        assertNull(HuddleCrypto.agree(a.private, b.copyOf().also { it[0] = 0x02 }))
        assertNull(HuddleCrypto.agree(a.private, ByteArray(65).also { it[0] = 0x04 }))
    }

    @Test
    fun `a sealed box opens with its key and its associated data, and with nothing else`() {
        val key = ByteArray(32).also(random::nextBytes)
        val plain = "a made-up card".toByteArray()
        val sealed = HuddleCrypto.seal(key, plain, "box-1".toByteArray(), random)
        assertFalse(sealed.contentEquals(plain))
        assertArrayEquals(plain, HuddleCrypto.open(key, sealed, "box-1".toByteArray()))
        assertNull(HuddleCrypto.open(key, sealed, "box-2".toByteArray()))
        assertNull(HuddleCrypto.open(ByteArray(32).also(random::nextBytes), sealed, "box-1".toByteArray()))
        sealed.indices.forEach { i ->
            val changed = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", HuddleCrypto.open(key, changed, "box-1".toByteArray()))
        }
        assertNull(HuddleCrypto.open(key, sealed.copyOf(sealed.size - 1), "box-1".toByteArray()))
        assertNull(HuddleCrypto.open(key, ByteArray(3), "box-1".toByteArray()))
    }

    @Test
    fun `each seal takes a fresh nonce, so one card sealed twice never looks the same`() {
        val key = ByteArray(32).also(random::nextBytes)
        val one = HuddleCrypto.seal(key, "same".toByteArray(), ByteArray(0), random)
        val two = HuddleCrypto.seal(key, "same".toByteArray(), ByteArray(0), random)
        assertFalse(one.contentEquals(two))
        assertEquals(1 + 12 + 4 + 16, one.size)
    }

    @Test
    fun `base64url goes both ways without padding, and refuses what is not base64url`() {
        val bytes = ByteArray(65) { (it * 7).toByte() }
        val text = HuddleCrypto.encode(bytes)
        assertTrue(text.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(87, text.length)
        assertArrayEquals(bytes, HuddleCrypto.decode(text))
        assertNull(HuddleCrypto.decode("not base64!"))
        assertNull(HuddleCrypto.decode("ab+/"))
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
