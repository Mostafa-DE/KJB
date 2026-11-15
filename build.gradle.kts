import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * Release signing, kept outside the repo: storeFile, storePassword, keyAlias, keyPassword.
 * Read from LYRIFY_KEYSTORE_PROPS, else ~/.android-keys/lyrify-keystore.properties.
 * Without it the release builds are simply unsigned, so the project builds anywhere. KJB (:app) and its AI add-on
 * (:ai-addon) are signed with this one key: the add-on's signature permission and both sides' certificate checks rely on it.
 */
val releaseKeystore: Properties? = run {
    val path = providers.environmentVariable("LYRIFY_KEYSTORE_PROPS").orNull
        ?: "${System.getProperty("user.home")}/.android-keys/lyrify-keystore.properties"
    val file = File(path)
    if (file.isFile) Properties().apply { file.inputStream().use { load(it) } } else null
}

/** The SHA-256 of [alias]'s certificate in the keystore [store], as hex; empty when it cannot be read. */
fun certSha256(store: File, storePassword: String, alias: String): String = try {
    // The JDK's default type (PKCS12) also reads JKS keystores (keystore.type.compat).
    val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { store.inputStream().use { load(it, storePassword.toCharArray()) } }
    val cert = keys.getCertificate(alias)
    if (cert == null) "" else MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
} catch (_: java.io.IOException) {
    ""
} catch (_: java.security.GeneralSecurityException) {
    ""
}

/**
 * The certificate each build type is signed with, pinned in KJB and in the AI add-on: each checks the other against it
 * (KJB before it binds the add-on, the add-on on every call). debug is AGP's debug key on this machine; release is the
 * key above. Empty when a keystore cannot be read (a fresh machine before its first debug build): then neither side
 * trusts the other and the AI stays off, never open.
 */
val certPins: Map<String, String> = run {
    val androidHome = System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android"
    val debug = File(androidHome, "debug.keystore")
    mapOf(
        "debug" to if (debug.isFile) certSha256(debug, "android", "androiddebugkey") else "",
        "release" to (releaseKeystore?.let { certSha256(file(it.getProperty("storeFile")), it.getProperty("storePassword"), it.getProperty("keyAlias")) } ?: ""),
    )
}

/**
 * KJB's GitHub repository ("owner/name"), Mostafa-DE/KJB from 3.0.0 (formerly Mostafa-DE/Lyrify): KJB's update check reads
 * its latest release, KJB downloads both add-ons from its releases, and KJB and both add-ons name it in the User-Agent of
 * their requests (BuildConfig.REPOSITORY). The package names keep "lyrify", so installs keep updating.
 */
val kjbRepo = "Mostafa-DE/KJB"

extra["releaseKeystore"] = releaseKeystore
extra["certPins"] = certPins
extra["kjbRepo"] = kjbRepo
