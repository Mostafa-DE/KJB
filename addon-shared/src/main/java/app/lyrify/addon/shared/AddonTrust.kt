package app.lyrify.addon.shared

/**
 * The trust between KJB and its add-ons, one path for all of them: the AI (app.lyrify.ai) and Downloads (app.lyrify.dl).
 * Each add-on is signed with KJB's key and alone defines one signature permission, which only KJB holds. KJB checks the
 * installed add-on before it binds it ([AddonTrust]); the add-on checks KJB on every call ([CallerCheck]). Pure: what
 * Android says comes in as functions.
 */
object AddonPins {
    /** A pinned certificate's SHA-256 as hex, as bytes; null when it is empty or not hex: no pin trusts nobody. */
    fun bytes(hex: String): ByteArray? =
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            null
        } else {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
}

/**
 * An add-on's side: who may call it. KJB of this build type ([kjbPackage]) signed with the pinned certificate
 * ([certSha256], hex), by the calling uid's packages ([packagesFor], `PackageManager.getPackagesForUid`) and certificate
 * ([hasCert], `PackageManager.hasSigningCertificate` with CERT_INPUT_SHA256). No pin trusts nobody.
 */
class CallerCheck(
    private val addonPackage: String,
    private val kjbPackage: String,
    private val certSha256: String,
    private val packagesFor: (Int) -> Array<String>?,
    private val hasCert: (Int, ByteArray) -> Boolean,
) {
    fun allows(uid: Int): Boolean {
        val pin = AddonPins.bytes(certSha256) ?: return false
        val packages = packagesFor(uid) ?: return false
        return kjbPackage in packages && hasCert(uid, pin)
    }

    /**
     * Whether a bind may be served: the bind permission is this add-on's own definition, at signature level (an app that
     * defined it first would set its level). onBind runs outside the client's transaction, so the caller itself is
     * checked on each call ([enforce]).
     */
    fun guardsBind(permissionOwner: String?, signatureLevel: Boolean): Boolean = permissionOwner == addonPackage && signatureLevel

    /** Throws SecurityException, which reaches KJB as such, unless [uid] [allows]. */
    fun enforce(uid: Int) {
        if (!allows(uid)) throw SecurityException("the caller is not KJB")
    }
}

/** Who defines a permission, and whether at signature level. */
data class PermissionDefiner(val packageName: String, val signature: Boolean)

/** What KJB asks Android about an installed add-on's identity. */
interface TrustPort {
    fun hasCertificate(packageName: String, sha256: ByteArray): Boolean

    /** Signed with the same certificate as KJB (`PackageManager.checkSignatures`). */
    fun signedLikeKjb(packageName: String): Boolean

    /** Who defines [permission]; null when no installed app does. */
    fun definer(permission: String): PermissionDefiner?
}

/**
 * KJB's side: whether the installed [addonPackage] is KJB's add-on: signed with the pinned certificate ([certSha256],
 * hex) and with KJB's own (both checks: the pin also covers a pair of look-alike apps signed alike), and the definer of
 * its bind [permission]. Android lets the first app that defines a permission set its level, so another app defining it
 * first could open it to any app; the bind goes through only when the add-on defines it, at signature level.
 */
class AddonTrust(private val addonPackage: String, private val permission: String, private val certSha256: String, private val packages: TrustPort) {
    fun trusted(): Boolean {
        val pin = AddonPins.bytes(certSha256) ?: return false
        val definer = packages.definer(permission) ?: return false
        return definer.packageName == addonPackage && definer.signature &&
            packages.hasCertificate(addonPackage, pin) && packages.signedLikeKjb(addonPackage)
    }
}
