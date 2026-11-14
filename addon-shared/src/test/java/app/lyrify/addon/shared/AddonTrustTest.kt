package app.lyrify.addon.shared

import app.lyrify.dl.api.DlNames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trust between KJB and its add-ons, both ways: who may call an add-on ([CallerCheck]), and which add-on KJB binds ([AddonTrust]). */
class AddonTrustTest {
    // Who may call an add-on

    private val kjb = 10_123
    private val pin = "AB".repeat(32)

    /** KJB's uid holds KJB's package and KJB's certificate; [other] another app's, signed with another key. */
    private val other = 10_200

    private fun check(
        pinned: String = pin,
        addon: String = "app.lyrify.ai",
        kjbPackage: String = "app.lyrify",
        packages: Map<Int, Array<String>> = mapOf(kjb to arrayOf("app.lyrify"), other to arrayOf("com.example.other")),
        certs: Map<Int, String> = mapOf(kjb to pin, other to "CD".repeat(32)),
    ) = CallerCheck(
        addonPackage = addon,
        kjbPackage = kjbPackage,
        certSha256 = pinned,
        packagesFor = { uid -> packages[uid] },
        hasCert = { uid, sha -> certs[uid]?.let { hex -> hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().contentEquals(sha) } == true },
    )

    @Test
    fun `KJB signed with the pinned certificate may call`() {
        assertTrue(check().allows(kjb))
        check().enforce(kjb)
    }

    @Test
    fun `another app, an unknown uid, or KJB's name with another certificate may not`() {
        assertFalse(check().allows(other))
        assertFalse(check().allows(99_999))
        assertFalse(check(certs = mapOf(kjb to "CD".repeat(32))).allows(kjb))
        assertThrows(SecurityException::class.java) { check().enforce(other) }
    }

    @Test
    fun `an app signed with KJB's key but not KJB may not`() {
        val sameKey = check(packages = mapOf(other to arrayOf("com.example.other")), certs = mapOf(other to pin))

        assertFalse(sameKey.allows(other))
    }

    @Test
    fun `no pin trusts nobody`() {
        assertFalse(check(pinned = "").allows(kjb))
        assertFalse(check(pinned = "not hex").allows(kjb))
    }

    @Test
    fun `a uid KJB shares with other packages is KJB's`() {
        assertTrue(check(packages = mapOf(kjb to arrayOf("com.example.shared", "app.lyrify"))).allows(kjb))
    }

    @Test
    fun `a bind is served only while the bind permission is the add-on's own, at signature level`() {
        assertTrue(check().guardsBind(permissionOwner = "app.lyrify.ai", signatureLevel = true))
        // Another app defined the permission first (it would set its level), or it is no longer signature level.
        assertFalse(check().guardsBind(permissionOwner = "com.example.other", signatureLevel = true))
        assertFalse(check().guardsBind(permissionOwner = "app.lyrify.ai", signatureLevel = false))
        assertFalse(check().guardsBind(permissionOwner = null, signatureLevel = true))
    }

    @Test
    fun `the Downloads add-on serves only the KJB of its own build type`() {
        val debugAddon = check(addon = "app.lyrify.dl.debug", kjbPackage = "app.lyrify.debug")

        // The release KJB, signed with the same key in this test, is not the debug add-on's caller.
        assertFalse(debugAddon.allows(kjb))
        assertTrue(debugAddon.guardsBind(permissionOwner = "app.lyrify.dl.debug", signatureLevel = true))
        assertFalse(debugAddon.guardsBind(permissionOwner = "app.lyrify.ai.debug", signatureLevel = true))
    }

    @Test
    fun `a pin is hex of any case, nothing else`() {
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0x01), AddonPins.bytes("aB01"))
        assertNull(AddonPins.bytes(""))
        assertNull(AddonPins.bytes("ABC"))
        assertNull(AddonPins.bytes("XY"))
    }

    // Whether the installed add-on is KJB's, as Android tells it

    private class FakePackages(
        var certificate: String = "AB".repeat(32),
        var signedLikeKjb: Boolean = true,
        var definer: PermissionDefiner? = PermissionDefiner("app.lyrify.ai", signature = true),
    ) : TrustPort {
        val asked = mutableListOf<String>()

        override fun hasCertificate(packageName: String, sha256: ByteArray): Boolean =
            certificate.chunked(2).map { it.toInt(16).toByte() }.toByteArray().contentEquals(sha256)

        override fun signedLikeKjb(packageName: String): Boolean = signedLikeKjb

        override fun definer(permission: String): PermissionDefiner? {
            asked += permission
            return definer
        }
    }

    private fun trust(packages: FakePackages, pinned: String = pin) =
        AddonTrust("app.lyrify.ai", "app.lyrify.ai.permission.BIND_AI", pinned, packages)

    @Test
    fun `the add-on is KJB's when signed with the pinned certificate, like KJB, and the bind permission is its own`() {
        assertTrue(trust(FakePackages()).trusted())
    }

    @Test
    fun `another certificate, another signer than KJB, or no pin is not KJB's add-on`() {
        assertFalse(trust(FakePackages(certificate = "CD".repeat(32))).trusted())
        assertFalse(trust(FakePackages(signedLikeKjb = false)).trusted())
        assertFalse(trust(FakePackages(), pinned = "").trusted())
    }

    @Test
    fun `a bind permission another app defined first, or one not at signature level, is never bound through`() {
        assertFalse(trust(FakePackages(definer = PermissionDefiner("com.example.other", signature = true))).trusted())
        assertFalse(trust(FakePackages(definer = PermissionDefiner("app.lyrify.ai", signature = false))).trusted())
        assertFalse(trust(FakePackages(definer = null)).trusted())
    }

    @Test
    fun `the Downloads add-on is checked through its own bind permission, never the AI's`() {
        val packages = FakePackages(definer = PermissionDefiner("app.lyrify.dl", signature = true))
        val downloads = AddonTrust("app.lyrify.dl", DlNames.permission("app.lyrify.dl"), pin, packages)

        assertTrue(downloads.trusted())
        assertTrue(packages.asked.single().endsWith(".permission.BIND_DL"))
        // The AI add-on defining the Downloads add-on's permission does not make it trusted.
        packages.definer = PermissionDefiner("app.lyrify.ai", signature = true)
        assertFalse(downloads.trusted())
    }
}
