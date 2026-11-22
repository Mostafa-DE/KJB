package app.lyrify.huddle

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** One side of a P-256 key agreement: the public key as its 65-byte point (0x04, x, y), the private key as PKCS#8. */
class KeyPairBytes(val public: ByteArray, val private: ByteArray)

/**
 * Huddle's cryptography, all from your phone's own Java crypto, no library (plan Q3, hd-research-check.md S5): P-256
 * key agreement (ECDH), HKDF-SHA256 (RFC 5869), AES-256-GCM with a random 12-byte nonce, PBKDF2-HMAC-SHA256 for the
 * backup's passphrase, and base64url without padding for what travels as text. Every key and nonce comes from the
 * [SecureRandom] the caller gives.
 */
object HuddleCrypto {
    const val PUBLIC_KEY_BYTES: Int = 65
    const val KEY_BYTES: Int = 32

    /** A new P-256 key pair: one per invite, and one for each friend an invite is joined from. */
    fun keyPair(random: SecureRandom): KeyPairBytes {
        val generator = KeyPairGenerator.getInstance(EC)
        generator.initialize(ECGenParameterSpec(CURVE), random)
        val pair = generator.generateKeyPair()
        val point = (pair.public as ECPublicKey).w
        return KeyPairBytes(byteArrayOf(UNCOMPRESSED) + coordinate(point.affineX) + coordinate(point.affineY), pair.private.encoded)
    }

    /**
     * The 32-byte secret [private] and the other side's [public] key agree on; null when [public] is not a point of
     * P-256 ([onCurve]), so a made-up key never takes part.
     */
    fun agree(private: ByteArray, public: ByteArray): ByteArray? {
        if (!onCurve(public)) return null
        return try {
            val keys = KeyFactory.getInstance(EC)
            val agreement = KeyAgreement.getInstance(ECDH)
            agreement.init(keys.generatePrivate(PKCS8EncodedKeySpec(private)))
            agreement.doPhase(keys.generatePublic(X509EncodedKeySpec(P256_KEY_PREFIX + public)), true)
            agreement.generateSecret()
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** HKDF-SHA256 (RFC 5869): [length] bytes from [secret], with [salt] (none: 32 zero bytes) and [info]. */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_HKDF_BYTES) { "HKDF of $length bytes" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt.takeIf { it.isNotEmpty() } ?: ByteArray(HASH_BYTES), HMAC))
        val pseudoRandom = mac.doFinal(secret)
        mac.init(SecretKeySpec(pseudoRandom, HMAC))
        val out = ByteArrayOutputStream(length + HASH_BYTES)
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /**
     * [plain] sealed with the 32-byte [key]: its format byte, a fresh 12-byte nonce, then AES-256-GCM's text and tag,
     * which also cover [aad] (the mailbox it goes to, so the server cannot hand it back to its sender) and the format byte.
     */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(byteArrayOf(FORMAT))
        cipher.updateAAD(aad)
        return byteArrayOf(FORMAT) + nonce + cipher.doFinal(plain)
    }

    /** What [seal] sealed with [key] and [aad]; null when it was sealed otherwise or changed on the way, by a single bit. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < HEADER_BYTES + TAG_BITS / Byte.SIZE_BITS || sealed[0] != FORMAT) return null
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES))
            cipher.updateAAD(byteArrayOf(FORMAT))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** The 32-byte key [passphrase] makes with [salt] over [rounds] of PBKDF2-HMAC-SHA256: the strongest key derivation every phone has. */
    fun passphraseKey(passphrase: CharArray, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, rounds, KEY_BYTES * Byte.SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** [bytes] as base64url text without padding: safe in a link, a mailbox id, JSON. */
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** The bytes of base64url [text]; null when it is not base64url. */
    fun decode(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Whether [point] is an uncompressed point of P-256: 0x04, then x and y below the field's prime with y² = x³ - 3x + b.
     * Checked here rather than left to the phone's provider, so an invalid-curve key is refused on every Android version.
     */
    private fun onCurve(point: ByteArray): Boolean {
        if (point.size != PUBLIC_KEY_BYTES || point[0] != UNCOMPRESSED) return false
        val x = BigInteger(1, point.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, point.copyOfRange(1 + COORDINATE_BYTES, PUBLIC_KEY_BYTES))
        if (x >= P256_PRIME || y >= P256_PRIME) return false
        val right = x.pow(3).subtract(x.multiply(THREE)).add(P256_B).mod(P256_PRIME)
        return y.multiply(y).mod(P256_PRIME) == right
    }

    /** [value] as exactly 32 big-endian bytes, as the point's coordinates are written. */
    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().let { if (it.size > COORDINATE_BYTES) it.copyOfRange(it.size - COORDINATE_BYTES, it.size) else it }
        return ByteArray(COORDINATE_BYTES - bytes.size) + bytes
    }

    private const val EC = "EC"
    private const val ECDH = "ECDH"
    private const val CURVE = "secp256r1"
    private const val HMAC = "HmacSHA256"
    private const val AES = "AES"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val HASH_BYTES = 32
    private const val MAX_HKDF_BYTES = 255 * HASH_BYTES
    private const val COORDINATE_BYTES = 32
    private const val UNCOMPRESSED: Byte = 0x04

    /** The sealed box's format, its first byte: a later one can change the cipher without mistaking old boxes. */
    private const val FORMAT: Byte = 1

    /** 12 bytes, the size GCM is made for (Android 12+ refuses others). Random: far more cards than two friends send are safe. */
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val HEADER_BYTES = 1 + NONCE_BYTES

    /** The X.509 SubjectPublicKeyInfo header of a P-256 key, before its 65-byte point: what KeyFactory reads on every provider. */
    private val P256_KEY_PREFIX: ByteArray = "3059301306072a8648ce3d020106082a8648ce3d030107034200".chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()
    private val P256_PRIME = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", HEX)
    private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", HEX)
    private val THREE = BigInteger.valueOf(3)
    private const val HEX = 16
}
