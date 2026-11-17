package app.lyrify.auto

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.Player
import app.lyrify.library.song
import app.lyrify.player.Found
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.player.openedCoverDir
import app.lyrify.player.openedCoverKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.w3c.dom.Element
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Who may see KJB's library through its media session, the covers' uris, the covers the provider serves, and
 * the car's searches. Controllers are made up ([Caller]); signing certificates are checked by a fake. Made-up names only.
 */
class CarAccessTest {
    /**
     * Each test ends within this or fails: the cover key and the signatures are asked for from several threads at once,
     * and a lock that never lets go would leave a join or a future waiting for good, holding the gate.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val ownUid = 10_123
    private val autoUid = 10_200
    private val centreUid = 1_000
    private val platformCertificate = "AB".repeat(32)

    /** Which certificate each uid's app is signed with. */
    private val signers = mapOf(
        autoUid to ControllerAccess.AUTO_CERTIFICATES.first(),
        centreUid to platformCertificate,
        10_300 to "CD".repeat(32),
    )
    private val access = ControllerAccess(
        ownPackage = "app.lyrify.debug",
        ownUid = ownUid,
        signedBy = { uid, sha256 -> signers[uid] == sha256 },
        platform = { setOf(platformCertificate) },
    )

    // Who sees the library

    @Test
    fun `KJB itself and the system's trusted controllers get the library`() {
        assertEquals(Access.LIBRARY, access.of(Caller("app.lyrify.debug", ownUid, trusted = false)))
        assertEquals(Access.LIBRARY, access.of(Caller("com.android.systemui", 10_050, trusted = true)))
        assertEquals(Access.LIBRARY, access.of(Caller("com.android.bluetooth", 1_002, trusted = true)))
    }

    @Test
    fun `Android Auto and an Automotive media centre are cars only when signed so`() {
        assertEquals(Access.CAR, access.of(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = false)))
        assertEquals(Access.CAR, access.of(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = true)))
        assertEquals(Access.CAR, access.of(Caller("com.android.car.media", centreUid, trusted = false)))
        // The names alone: an app calling itself Android Auto or the media centre, signed with its own key.
        assertEquals(Access.TRANSPORT, access.of(Caller(ControllerAccess.ANDROID_AUTO, 10_300, trusted = false)))
        assertEquals(Access.TRANSPORT, access.of(Caller("com.android.car.media", 10_300, trusted = false)))
        // Signed with the platform key, but not a media centre: not a car.
        assertEquals(Access.TRANSPORT, access.of(Caller("com.example.other", centreUid, trusted = false)))
    }

    @Test
    fun `any other app gets plain transport, KJB's name from another uid included`() {
        assertEquals(Access.TRANSPORT, access.of(Caller("com.example.player", 10_400, trusted = false)))
        assertEquals(Access.TRANSPORT, access.of(Caller("app.lyrify.debug", 10_401, trusted = false)))
    }

    @Test
    fun `the player's own buttons (Favourite, Shuffle, Repeat) are offered to KJB, the system and the car, never another app`() {
        val base = setOf("browse", "search")
        val own = listOf("favourite", "shuffle", "repeat")
        fun offered(caller: Caller) = ControllerAccess.sessionCommands(access.of(caller), base, own)

        assertEquals(base + own, offered(Caller("app.lyrify.debug", ownUid, trusted = false)))
        assertEquals(base + own, offered(Caller("com.android.systemui", 10_050, trusted = true)))
        assertEquals(base + own, offered(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = false)))
        assertEquals(emptySet<String>(), offered(Caller("com.example.player", 10_400, trusted = false)))
        assertEquals(emptySet<String>(), offered(Caller(ControllerAccess.ANDROID_AUTO, 10_300, trusted = false)))
    }

    // What each controller is offered as it connects. On the phone [every] and [sessionDefaults] are Media3's
    // ConnectionResult defaults; Media3 1.11's own onConnect offers no command at all, so the offer never starts from it.

    /** Every player command, by code. */
    private val every = (Player.COMMAND_PLAY_PAUSE..Player.COMMAND_SET_AUDIO_ATTRIBUTES).toSet()
    private val sessionDefaults = setOf("session", "library root", "children", "search")
    private val parts = listOf("favourite", "shuffle", "repeat")

    private fun offered(caller: Caller, rule: ControllerAccess = access) = ControllerAccess.offer(rule.of(caller), every, sessionDefaults, parts)

    @Test
    fun `KJB's own in-process controller gets every command and the library, the debug build's package and the release's alike`() {
        // KJB's own controller is not trusted in Media3's eyes when KJB has no notification access.
        val debug = offered(Caller("app.lyrify.debug", ownUid, trusted = false))
        assertTrue("play and pause refused", Player.COMMAND_PLAY_PAUSE in debug.player)
        assertEquals(every, debug.player)
        assertEquals(sessionDefaults + parts, debug.session)

        val release = ControllerAccess(ownPackage = "app.lyrify", ownUid = ownUid, signedBy = { _, _ -> false }, platform = { emptySet() })
        assertEquals(Offer(every, sessionDefaults + parts), offered(Caller("app.lyrify", ownUid, trusted = false), release))
        // The release's rule never takes the debug build for itself, nor the other way round.
        assertEquals(emptySet<String>(), offered(Caller("app.lyrify.debug", ownUid, trusted = false), release).session)
        assertEquals(emptySet<String>(), offered(Caller("app.lyrify", ownUid, trusted = false)).session)
    }

    @Test
    fun `the notification, the lock screen, Samsung's trusted controllers and a signed car get every command and the library`() {
        val full = Offer(every, sessionDefaults + parts)
        assertEquals(full, offered(Caller("com.android.systemui", 10_050, trusted = true)))
        assertEquals(full, offered(Caller("com.samsung.android.app.routineplus", 10_060, trusted = true)))
        assertEquals(full, offered(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = false)))
        assertEquals(full, offered(Caller("com.android.car.media", centreUid, trusted = false)))
    }

    @Test
    fun `any other app gets plain transport (play, pause, skip, seek) and no library or custom command`() {
        for (stranger in listOf(Caller("com.example.player", 10_400, trusted = false), Caller(ControllerAccess.ANDROID_AUTO, 10_300, trusted = false), Caller("app.lyrify.debug", 10_401, trusted = false))) {
            val offer = offered(stranger)
            listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .forEach { assertTrue("$stranger misses $it", it in offer.player) }
            assertEquals(ControllerAccess.TRANSPORT_COMMANDS.toSet(), offer.player)
            assertEquals(emptySet<String>(), offer.session)
        }
    }

    @Test
    fun `plain transport is play, pause, next, previous, seek and the song playing, never the queue or a change to it`() {
        val transport = ControllerAccess.TRANSPORT_COMMANDS
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)
            .forEach { assertTrue("missing $it", it in transport) }
        listOf(Player.COMMAND_GET_TIMELINE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)
            .forEach { assertFalse("has $it", it in transport) }
    }

    // The covers' uris (CR1): exported, so each uri carries its own signature

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val uris = CoverUris("app.lyrify.covers", key)

    /** The segments of the uri [uris] makes for [path]: the path and its signature (Uri itself is Android's). */
    private fun segments(path: String) = listOf(path, uris.sign(path))

    @Test
    fun `a cover's uri names its MediaStore id and a signature, and opens that cover only`() {
        assertEquals("20", uris.pathOf(segments("20")))
        assertEquals(DEFAULT_COVER, uris.pathOf(segments(DEFAULT_COVER)))
        val signature = uris.sign("20")
        assertEquals(32, signature.length)
        assertTrue(signature.all { it in '0'..'9' || it in 'a'..'f' })
        // The same cover always has the same uri, so the car keeps it; another cover has another.
        assertEquals(signature, CoverUris("app.lyrify.covers", key.copyOf()).sign("20"))
        assertFalse(signature == uris.sign("21"))
        // Another song's signature, a changed one, or another install's key opens nothing.
        assertNull(uris.pathOf(listOf("21", signature)))
        assertNull(uris.pathOf(listOf("20", signature.replaceFirst(signature[0], if (signature[0] == 'a') 'b' else 'a'))))
        assertNull(uris.pathOf(listOf("20", signature.uppercase())))
        assertNull(CoverUris("app.lyrify.covers", ByteArray(32) { 1 }).pathOf(segments("20")))
    }

    @Test
    fun `a signature is the first 128 bits of the path's HMAC-SHA256, the same on every thread at once`() {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        assertEquals(mac.doFinal("20".toByteArray()).copyOf(16).joinToString("") { "%02x".format(it) }, uris.sign("20"))
        // The service signs on the main thread while the car's lists sign on others and the provider checks on binder threads.
        val paths = (0 until 2_000).map { it.toString() } + DEFAULT_COVER
        val alone = paths.map(uris::sign)
        val threads = Executors.newFixedThreadPool(4)
        try {
            (1..4).map { threads.submit<List<String>> { paths.map(uris::sign) } }.forEach { assertEquals(alone, it.get()) }
        } finally {
            threads.shutdown()
        }
    }

    @Test
    fun `a cover's uri never reaches anything but a song's id, an opened copy's key or the default cover`() {
        listOf("..", "../files/covers.key", "%2e%2e", "/etc/hosts", "20/..", "-1", "20 ", "0x14", "", "１２", "1234567890123456789")
            .forEach { path -> assertNull(path, uris.pathOf(listOf(path, uris.sign(path)))) }
        // Exactly a path and its signature: no more, no less.
        assertNull(uris.pathOf(emptyList()))
        assertNull(uris.pathOf(listOf("20")))
        assertNull(uris.pathOf(listOf(uris.sign("20"))))
        assertNull(uris.pathOf(segments("20") + "extra"))
        assertNull(uris.pathOf(listOf("x") + segments("20")))
    }

    @Test
    fun `an opened copy's uri names its key and a signature, and opens only a key of that exact shape`() {
        // The key coverPath gives a copy in KJB's cache (openedCoverKey) opens, signed.
        val key = checkNotNull(openedCoverKey("/data/user/0/app.lyrify/cache/opened/1763290000000/12/Paper Harbour.mp3"))
        assertEquals("opened-1763290000000-12", key)
        assertEquals(key, uris.pathOf(segments(key)))
        assertEquals("opened-1000-0", uris.pathOf(segments("opened-1000-0")))
        // Unsigned, signed for another copy or a song, changed, or under another install's key: nothing.
        assertNull(uris.pathOf(listOf("opened-1000-0")))
        assertNull(uris.pathOf(listOf("opened-1000-1", uris.sign("opened-1000-0"))))
        assertNull(uris.pathOf(listOf("opened-1000-0", uris.sign("20"))))
        assertNull(uris.pathOf(listOf("opened-1000-0", uris.sign("opened-1000-0").uppercase())))
        assertNull(CoverUris("app.lyrify.covers", ByteArray(32) { 1 }).pathOf(segments("opened-1000-0")))
        // Signed or not, only opened-<digits>-<digits>: no folder, no name, no way out of the copies, no other text.
        listOf(
            "opened", "opened-1000", "opened-1000-0-1", "opened--0", "opened-1000-", "opened-..-0", "opened-1000-..",
            "opened-1000/0", "opened-1000-0/..", "opened-%2e%2e-0", "opened-1000-x", "opened-+1-0", "opened--1-0",
            "opened-١٠٠٠-0", "opened-1000-0 ", " opened-1000-0", "opened-1000-0\n", "Opened-1000-0", "copy-1000-0",
            "opened-1234567890123456789-0", "opened-1000-1234567890123456789", "../cache/opened/1000/0/Paper Harbour.mp3",
        ).forEach { path -> assertNull(path, uris.pathOf(listOf(path, uris.sign(path)))) }
    }

    @Test
    fun `the cover key is made once, kept, and made again only when it is not a key`() {
        val dir = java.nio.file.Files.createTempDirectory("covers").toFile()
        try {
            val file = File(dir, "covers.key")
            val made = CoverKey.read(file)
            assertEquals(32, made.size)
            assertTrue(made.contentEquals(file.readBytes()))
            assertTrue(made.contentEquals(CoverKey.read(file)))
            assertFalse(File(dir, "covers.key.new").exists())
            file.writeBytes(ByteArray(5))
            val again = CoverKey.read(file)
            assertEquals(32, again.size)
            assertFalse(again.contentEquals(made))
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A context whose files are in [dir]: all CoverKey asks of it. */
    private fun context(dir: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    @Test
    fun `the cover key is read off the main thread as KJB starts, and the service's first cover reads no file`() = runBlocking<Unit> {
        val dir = java.nio.file.Files.createTempDirectory("covers").toFile()
        try {
            CoverKey.forget()
            CoverKey.warm(context(dir), this).join()
            val file = File(dir, "covers.key")
            assertTrue("no key made by the warm-up", file.isFile)
            val warmed = file.readBytes()
            // The service signs its first cover on the main thread: the key is in memory, the file is not read again.
            file.delete()
            assertTrue(warmed.contentEquals(CoverKey.of(context(dir))))
            assertFalse(file.exists())
        } finally {
            CoverKey.forget()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the warm-up, the service and the car's lists asking at once get one key, in one file`() {
        val dir = java.nio.file.Files.createTempDirectory("covers").toFile()
        val threads = Executors.newFixedThreadPool(8)
        try {
            CoverKey.forget()
            val start = CountDownLatch(1)
            val asked = (1..8).map { threads.submit<ByteArray> { start.await(); CoverKey.of(context(dir)) } }
            val warm = CoverKey.warm(context(dir), CoroutineScope(Dispatchers.IO))
            start.countDown()
            val keys = asked.map { it.get() }
            runBlocking { warm.join() }
            keys.forEach { assertTrue(it.contentEquals(keys.first())) }
            assertTrue(keys.first().contentEquals(CoverKey.of(context(dir))))
            assertTrue(keys.first().contentEquals(File(dir, "covers.key").readBytes()))
            assertEquals(listOf("covers.key"), dir.list()?.toList())
        } finally {
            threads.shutdown()
            CoverKey.forget()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the cover key is in no backup, and the covers are exported read-only with no grant anywhere`() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("false", application.getAttribute("android:fullBackupContent"))
        // Android 12 and later move an app's data to a new phone even without backup: the rules leave out files/, the key's.
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        val rules = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/data_extraction_rules.xml"))
        listOf("cloud-backup", "device-transfer").forEach { kind ->
            val excluded = (rules.getElementsByTagName(kind).item(0) as Element).getElementsByTagName("exclude")
            val files = (0 until excluded.length).map { excluded.item(it) as Element }.filter { it.getAttribute("domain") == "file" }
            assertTrue("$kind keeps the key", files.any { it.getAttribute("path") in setOf("", ".", "covers.key") })
        }

        val providers = manifest.getElementsByTagName("provider")
        val covers = (0 until providers.length).map { providers.item(it) as Element }.single { it.getAttribute("android:name") == ".auto.CarCovers" }
        assertEquals("true", covers.getAttribute("android:exported"))
        listOf("android:permission", "android:readPermission", "android:writePermission", "android:grantUriPermissions")
            .forEach { assertEquals(it, "", covers.getAttribute(it)) }
        // Nothing hands out or takes back uri grants any more: each cover's uri is its own key.
        val granting = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""(grant|revoke)UriPermission\(""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), granting)
    }

    // The covers the provider serves

    @Test
    fun `the provider serves a song's own cover, else the default cover`() {
        val read = mutableListOf<Long>()
        fun serve(path: String) = coverFor(path, { id -> read += id; if (id == 22L) null else "cover $id" }) { "default" }

        assertEquals("cover 20", serve("20"))
        // No embedded cover: the default, so the car never shows a gap.
        assertEquals("default", serve("22"))
        // A song the library leaves out (a short clip) is served its own cover too: only KJB signs a song's id.
        assertEquals("cover 21", serve("21"))
        // Not an id at all: the default, and no file is read.
        assertEquals("default", serve(DEFAULT_COVER))
        assertEquals("default", serve("../Music"))
        // A file another app opened: a car gets the default for it, and nothing is read.
        assertEquals("default", serve("opened-1000-0"))
        assertEquals(listOf(20L, 22L, 21L), read)
        // KJB's own session (the notification, the lock screen) reads in the process: the copy's own cover, else the default.
        assertEquals("copy 1000/0", ownCover("opened-1000-0", { error("not a song") }, opened = { key -> openedCoverDir(key)?.let { "copy $it" } }) { "default" })
        assertEquals("default", ownCover("opened-1000-0", { error("not a song") }, opened = { null }) { "default" })
        assertEquals("default", ownCover(DEFAULT_COVER, { error("not a song") }, opened = { error("not a copy's key") }) { "default" })
    }

    @Test
    fun `an opened copy's signed uri reads its own cover in KJB's process, and a car is served the default cover for it`() {
        val songs = mutableListOf<Long>()
        val copies = mutableListOf<String>()
        val opened = { key: String -> copies += key; if (key == "opened-1000-0") "copy 1000/0" else null }
        // As CarCovers.read and CarCovers.openFile go: the uri's path and signature checked, then that cover read.
        fun own(segments: List<String>) = ownCover(uris.pathOf(segments).orEmpty(), { id -> songs += id; "cover $id" }, opened) { "default" }
        fun car(segments: List<String>) =
            uris.pathOf(segments)?.let { path -> coverFor(path, { id -> songs += id; "cover $id" }) { "default" } }

        // The notification and the lock screen: the copy's own cover; the default for a copy with none, or gone from the cache.
        assertEquals("copy 1000/0", own(segments("opened-1000-0")))
        assertEquals("default", own(segments("opened-1000-1")))
        // Unsigned, or signed for another copy: the default, and no copy is read.
        assertEquals("default", own(listOf("opened-1000-0")))
        assertEquals("default", own(listOf("opened-1000-0", uris.sign("opened-1000-1"))))
        assertEquals(listOf("opened-1000-0", "opened-1000-1"), copies)

        // A car, or any other app, is served the default cover for a copy, as 3.0.1 served it, and no copy is read there.
        copies.clear()
        assertEquals("default", car(segments("opened-1000-0")))
        assertEquals("default", car(segments("opened-1000-1")))
        // A uri KJB did not sign is no cover at all (the provider's FileNotFoundException).
        assertNull(car(listOf("opened-1000-0", uris.sign("opened-1000-1"))))
        assertEquals(emptyList<String>(), copies)

        // A song and the default cover read as before, in the process and in the car, and never reach the copies.
        assertEquals("cover 20", own(segments("20")))
        assertEquals("cover 20", car(segments("20")))
        assertEquals("default", own(segments(DEFAULT_COVER)))
        assertEquals("default", car(segments(DEFAULT_COVER)))
        assertEquals(listOf(20L, 20L), songs)
        assertEquals(emptyList<String>(), copies)
    }

    @Test
    fun `KJB's own surfaces get the default cover for a song without one, and none for a read that failed, to read again`() {
        val read = mutableListOf<Long>()
        fun own(path: String) = ownCover(path, { id ->
            read += id
            when (id) {
                20L -> "cover 20"
                // MediaStore has no cover for the song: no embedded art, or the song is gone.
                22L -> throw FileNotFoundException()
                // Not read now: the thumbnail did not decode, or KJB may not read the song yet.
                23L -> throw IOException()
                else -> throw SecurityException()
            }
        }) { "default" }

        assertEquals("cover 20", own("20"))
        // No art: the default cover, which the widget keeps for the song (and reads once more a minute on), as the car shows it.
        assertEquals("default", own("22"))
        // A read that failed: no cover, so the widget reads it again at its next update rather than keep the default.
        assertNull(own("23"))
        assertNull(own("24"))
        // The default cover's path, or a uri KJB did not sign: the default, and no file is read.
        assertEquals("default", own(DEFAULT_COVER))
        assertEquals("default", own(""))
        assertEquals(listOf(20L, 22L, 23L, 24L), read)
    }

    @Test
    fun `a cover read that throws anything else or runs out of memory is a read that failed, its error's name told`() {
        val failed = mutableListOf<String>()
        fun own(path: String): String? = try {
            ownCover(path, { id ->
                when (id) {
                    // MediaStore's thumbnail did not decode, or the picture did not fit in memory now.
                    25L -> throw IllegalStateException("made up")
                    26L -> throw IllegalArgumentException("made up")
                    27L -> throw OutOfMemoryError("made up")
                    else -> throw IOException()
                }
            }, opened = { throw IllegalStateException("made up") }, failed = { failed += it.javaClass.simpleName }) { "default" }
        } catch (e: Throwable) {
            "threw ${e.javaClass.simpleName}"
        }

        // Null, as any read that failed: the session shows the default cover, the widget reads it again at its next update.
        assertNull(own("25"))
        assertNull(own("26"))
        assertNull(own("27"))
        assertNull(own("opened-1000-0"))
        assertNull(own("28"))
        assertEquals(listOf("IllegalStateException", "IllegalArgumentException", "OutOfMemoryError", "IllegalStateException", "IOException"), failed)
    }

    @Test
    fun `a car asking as KJB starts, before the library is read, is served a signed song's own cover at once`() {
        // Android Auto starts KJB as the car connects, asks for covers at once and keeps each by its uri: the signature
        // alone lets a song's cover out, so nothing waits for the library, and no song keeps the default for good.
        val read = mutableListOf<Long>()
        fun car(segments: List<String>) =
            uris.pathOf(segments)?.let { path -> coverFor(path, { id -> read += id; if (id == 20L) "cover 20" else null }) { "default" } }

        assertEquals("cover 20", car(segments("20")))
        // MediaStore has no cover for the song: the default, as before.
        assertEquals("default", car(segments("22")))
        // A uri KJB did not sign is no cover at all (the provider's FileNotFoundException), and nothing is read.
        assertNull(car(listOf("21", uris.sign("20"))))
        assertNull(car(listOf("21")))
        assertEquals(listOf(20L, 22L), read)
    }

    // The car's searches

    @Test
    fun `two apps' searches never take each other's results, and only the latest are kept`() {
        val searches = Searches()
        val source = QueueSource(SourceKind.SEARCH, "search:road", "road")
        val loose = song("Music/Rips/Loose Thread.mp3", id = 20)
        val ember = song("Music/Rips/Ember Road.mp3", id = 21)
        searches.put("auto", Found("road", listOf(ember), source))
        searches.put("centre", Found("road", listOf(loose), source))
        assertEquals(listOf(ember), searches.of("auto")["road"]!!.songs)
        assertEquals(listOf(loose), searches.of("centre")["road"]!!.songs)
        repeat(Searches.KEPT) { searches.put("auto", Found("words $it", emptyList(), source)) }
        assertNull(searches.of("auto")["road"])
        assertEquals(Searches.KEPT, searches.of("auto").size + searches.of("centre").size)
    }
}
