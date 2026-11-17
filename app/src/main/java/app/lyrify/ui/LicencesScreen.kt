package app.lyrify.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.addon.AndroidAddon
import app.lyrify.addon.KjbAddon
import app.lyrify.addon.KjbAddons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

// Settings > About > Licences: KJB's own licence, and the Notices row: the repository's NOTICE, which names everything
// the APK is built with, then the full text of every licence it names. The page lists no third-party rows (user
// feedback 2026-11-11: no "Built with" list); [THIRD_PARTY] stays the record of what ships. It comes from the release
// runtime classpath (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`) and the release APK's lib/
// and assets/ folders; LicencesTest checks it against the texts and the app's direct dependencies.

/** Where the full licence texts ship: assets/licences. */
const val LICENCE_DIR: String = "licences"

private const val APACHE: String = "Apache 2.0"
private const val APACHE_TEXT: String = "apache-2.0.txt"
private const val AOSP: String = "The Android Open Source Project"
private const val JETBRAINS: String = "JetBrains s.r.o. and contributors"
private const val GPL_3: String = "gpl-3.0.txt"
private const val NOTICES_TEXT: String = "third-party-notices.txt"

/**
 * One piece of what ships in the app: its [name] (also its page's key), what of it is in the APK ([parts]: Maven groups
 * and artifacts, native libraries, files), its [licence] and copyright holder, and the full texts in [LICENCE_DIR], in
 * the order shown. [addon]: it ships in that add-on (KJB AI or KJB Downloads), whose own APK holds its texts.
 */
@Immutable
data class Shipped(val name: String, val parts: String, val licence: String, val copyright: String, val texts: List<String>, val addon: KjbAddon? = null) {
    /** Its texts are in an add-on's APK, not KJB's. */
    val inAddon: Boolean get() = addon != null
}

/** KJB itself: the additional permission under GPL section 7 that ends LICENSE, then the GPL. */
val KJB_LICENCE: Shipped = Shipped("KJB", "This app", "GPL 3.0", "KJB contributors", listOf("kjb-additional-permission.txt", "gpl-3.0.txt"))

/** Third-party software in the APK. */
val THIRD_PARTY: List<Shipped> = listOf(
    Shipped(
        "AndroidX",
        "androidx.activity, androidx.annotation, androidx.appcompat (resources), androidx.arch.core, androidx.autofill, " +
            "androidx.collection, androidx.concurrent, androidx.core, androidx.customview, androidx.datastore, androidx.emoji2, " +
            "androidx.exifinterface, androidx.graphics, androidx.interpolator, androidx.lifecycle, androidx.media, " +
            "androidx.navigationevent, androidx.profileinstaller, androidx.room, androidx.savedstate, androidx.sqlite, " +
            "androidx.startup, androidx.tracing, androidx.vectordrawable, androidx.versionedparcelable, androidx.window, androidx.work",
        APACHE, AOSP, listOf(APACHE_TEXT),
    ),
    Shipped(
        "Jetpack Compose",
        "androidx.compose.animation, androidx.compose.foundation, androidx.compose.material3, androidx.compose.material " +
            "(ripple), androidx.compose.runtime and androidx.compose.ui, from the Compose BOM 2026.09.00",
        APACHE, AOSP, listOf(APACHE_TEXT),
    ),
    Shipped(
        "Media3",
        "androidx.media3 1.11.1: common, container, database, datasource, decoder, exoplayer, extractor, session",
        APACHE, AOSP, listOf(APACHE_TEXT),
    ),
    Shipped("Glance", "androidx.glance 1.2.0: glance, glance-appwidget", APACHE, AOSP, listOf(APACHE_TEXT)),
    Shipped(
        "Compose Multiplatform",
        "org.jetbrains.compose 1.12.0 and org.jetbrains.androidx (lifecycle, savedstate), brought in by Coil",
        APACHE, JETBRAINS, listOf(APACHE_TEXT),
    ),
    Shipped("Kotlin", "org.jetbrains.kotlin: kotlin-stdlib 2.4.21", APACHE, JETBRAINS, listOf(APACHE_TEXT)),
    Shipped("kotlinx.coroutines", "org.jetbrains.kotlinx: kotlinx-coroutines 1.11.0", APACHE, JETBRAINS, listOf(APACHE_TEXT)),
    Shipped("kotlinx.serialization", "org.jetbrains.kotlinx: kotlinx-serialization 1.12.0", APACHE, JETBRAINS, listOf(APACHE_TEXT)),
    Shipped("atomicfu", "org.jetbrains.kotlinx: atomicfu 0.28.0", APACHE, JETBRAINS, listOf(APACHE_TEXT)),
    Shipped("JetBrains annotations", "org.jetbrains: annotations 23.0.0", APACHE, "JetBrains s.r.o.", listOf(APACHE_TEXT)),
    Shipped("JSpecify", "org.jspecify: jspecify 1.0.0", APACHE, "The JSpecify Authors", listOf(APACHE_TEXT)),
    Shipped("Coil", "io.coil-kt.coil3 3.6.3: coil, coil-compose, coil-core", APACHE, "Coil Contributors", listOf(APACHE_TEXT)),
    Shipped("Okio", "com.squareup.okio: okio 3.18.1", APACHE, "Square, Inc.", listOf(APACHE_TEXT)),
    Shipped("Guava", "com.google.guava: guava 33.3.1-android, failureaccess 1.0.2, brought in by Media3", APACHE, "The Guava Authors", listOf(APACHE_TEXT)),
    Shipped("Accompanist", "com.google.accompanist: accompanist-drawablepainter 0.37.3, brought in by Coil", APACHE, AOSP, listOf(APACHE_TEXT)),
    Shipped(
        "Protocol Buffers",
        "Google's protobuf runtime, inside androidx.datastore and androidx.glance",
        "BSD 3-Clause", "Google Inc.", listOf("bsd-3-clause-protobuf.txt"),
    ),
    Shipped(
        "TagLib",
        "TagLib 2.3.2, unchanged, in libtaglib.so; offered under LGPL 2.1 or MPL 1.1, and KJB takes LGPL 2.1",
        "LGPL 2.1", "Scott Wheeler and the TagLib authors", listOf("lgpl-2.1.txt", "mpl-1.1.txt"),
    ),
    Shipped("Kyant0/taglib", "The JNI wrapper around TagLib, 1.0.6 with KJB's changes, in libtaglib.so", APACHE, "Kyant", listOf(APACHE_TEXT)),
    Shipped("utfcpp", "utfcpp 4.2.0, used by TagLib, in libtaglib.so", "BSL 1.0", "Nemanja Trifunovic", listOf("bsl-1.0.txt")),
    Shipped(
        "LLVM libc++",
        "The Android NDK's C++ library, in libtaglib.so",
        "Apache 2.0 with LLVM Exceptions", "The LLVM Project", listOf(APACHE_TEXT, "llvm-exception.txt"),
    ),
    Shipped("Material Symbols", "The app's icons", APACHE, "Google LLC", listOf(APACHE_TEXT)),
    Shipped("Bricolage Grotesque", "The app's title font", "OFL 1.1", "The Bricolage Grotesque Project Authors", listOf("ofl-1.1.txt")),
    Shipped("Roboto Flex", "The app's text font", "OFL 1.1", "The Roboto Flex Project Authors", listOf("ofl-1.1-roboto-flex.txt")),
)

/**
 * Third-party software in the AI add-on, KJB AI, which KJB downloads on supported phones: the AI's runtime and the
 * Whisper tokenizer. Their texts ship in the add-on's APK (ai-addon/src/main/assets/licences), and KJB shows them, read
 * from there, once the add-on is installed.
 */
val AI_THIRD_PARTY: List<Shipped> = listOf(
    Shipped(
        "ONNX Runtime",
        "com.microsoft.onnxruntime: onnxruntime-android 1.30.0 (libonnxruntime.so, libonnxruntime4j_jni.so), with its third-party notices",
        "MIT", "Microsoft Corporation", listOf("mit-onnxruntime.txt", "onnxruntime-notices.txt"), addon = KjbAddons.AI,
    ),
    Shipped("Microsoft 1DS SDK", "Inside ONNX Runtime's Java library (META-INF/LICENSE-1DS)", APACHE, "Microsoft Corporation", listOf(APACHE_TEXT), addon = KjbAddons.AI),
    Shipped(
        "ONNX Runtime QNN",
        "com.qualcomm.qti: onnxruntime-android-qnn 2.6.0 (libonnxruntime_providers_qnn.so), with its third-party notices",
        "MIT", "Qualcomm Technologies, Inc. and Microsoft Corporation", listOf("mit-onnxruntime-qnn.txt", "onnxruntime-qnn-notices.txt"), addon = KjbAddons.AI,
    ),
    Shipped(
        "Qualcomm AI Engine Direct",
        "com.qualcomm.qti: qnn-runtime 2.50.0 (libQnnHtp.so, libQnnHtpPrepare.so, libQnnSystem.so and the Hexagon v69, v75, " +
            "v79 and v81 libraries), with its NOTICE",
        "Qualcomm AI Stack License", "Qualcomm Technologies, Inc.", listOf("qnn-runtime-license.txt", "qnn-runtime-notices.txt"), addon = KjbAddons.AI,
    ),
    Shipped(
        "Whisper tokenizer",
        "openai/whisper-large-v3-turbo's vocab.json, added_tokens.json and generation_config.json",
        "MIT", "OpenAI", listOf("mit-whisper.txt"), addon = KjbAddons.AI,
    ),
)

/** The AI add-on's row: what it ships, then the full text of each of its licences, each once, from its own APK. */
val AI_NOTICES: Shipped = Shipped(
    "KJB AI",
    "The AI add-on: ONNX Runtime with its QNN execution provider, Qualcomm AI Engine Direct and the Whisper tokenizer",
    "Its licences",
    "KJB contributors",
    AI_THIRD_PARTY.flatMap { it.texts }.distinct(),
    addon = KjbAddons.AI,
)

/**
 * Third-party software in the Downloads add-on, KJB Downloads: youtubedl-android and what it carries (yt-dlp on Python,
 * FFmpeg, QuickJS and the libraries Termux builds them with), and the Java libraries it brings. Read from
 * youtubedl-android 0.18.1's AARs and POMs, the add-on's `releaseRuntimeClasspath` and each project's own licence file
 * (lyrify-bench handoff/fu6-report.md). Their texts ship in the add-on's APK (dl-addon/src/main/assets/licences), with
 * its NOTICE, which lists every library; KJB shows them, read from there, once the add-on is installed.
 */
val DL_THIRD_PARTY: List<Shipped> = listOf(
    Shipped(
        "youtubedl-android",
        "io.github.junkfood02.youtubedl-android 0.18.1: library, ffmpeg, common (its Java code, and the programs and archives below)",
        "GPL 3.0", "yausername, JunkFood02 and contributors", listOf(GPL_3), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "yt-dlp",
        "yt-dlp 2025.11.12 (later its own updates) with yt-dlp-ejs 0.3.1, which bundles meriyah and astring",
        "Unlicense", "The yt-dlp contributors", listOf("unlicense-yt-dlp.txt"), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "Python",
        "Python 3.12.11 as Termux builds it (libpython.so, and libpython.zip.so with its standard library)",
        "PSF License 2.0", "Python Software Foundation", listOf("python-license.txt", "python-incorporated.txt"), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "FFmpeg",
        "FFmpeg 7.1.1 as Termux builds it, with --enable-gpl --enable-version3 (libffmpeg.so, libffprobe.so and its libraries in libffmpeg.zip.so)",
        "GPL 3.0 or later", "The FFmpeg developers", listOf(GPL_3), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "QuickJS",
        "QuickJS 2025-04-26 (libqjs.so), which yt-dlp runs JavaScript with",
        "MIT", "Fabrice Bellard and Charlie Gordon", listOf("mit-quickjs.txt"), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "Mutagen",
        "Mutagen 1.47.0, in Python's site-packages: yt-dlp writes tags and covers with it",
        "GPL 2.0 or later", "Joe Wreschnig, Michael Urman and contributors", listOf("gpl-2.0.txt"), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "PyCryptodome",
        "pycryptodomex 3.23.0, in Python's site-packages",
        "BSD 2-Clause and public domain", "Its authors", listOf("pycryptodome.txt"), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "Libraries Python is built with",
        "OpenSSL, SQLite, libffi, zlib, bzip2, XZ Utils, Expat, ncurses, GNU Readline, GNU dbm, LLVM libc++, Termux's " +
            "libcrypt, libandroid-support and libandroid-posix-semaphore, and the CA certificates",
        "Apache 2.0, MIT, BSD, GPL 3.0 and others", "Their authors",
        listOf(NOTICES_TEXT, APACHE_TEXT, "llvm-exception.txt", GPL_3, "mpl-2.0.txt"), addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "Libraries FFmpeg is built with",
        "x264, x265, Xvid, vid.stab, Rubber Band and FFTW (GPL); GnuTLS, Nettle, GMP, libidn2, libunistring, libiconv, GLib, " +
            "FriBidi, libbluray, libudfread, Game Music Emu, LAME, mpg123, SoX Resampler, libv4l, libssh and Graphite2 (LGPL); " +
            "SRT and ZeroMQ (MPL); and the AV1, VP8, VP9, WebP, Xiph, font, image, audio and Termux libraries that NOTICE.txt names",
        "GPL 2.0 or later, LGPL, MPL 2.0, BSD, MIT and others", "Their authors",
        listOf(
            NOTICES_TEXT, "gpl-2.0.txt", "lgpl-2.0.txt", "lgpl-2.1.txt", "lgpl-3.0.txt", GPL_3, "mpl-2.0.txt", APACHE_TEXT,
            "llvm-exception.txt", "ncsa-libandroid-stub.txt",
        ),
        addon = KjbAddons.DOWNLOADS,
    ),
    Shipped(
        "Java libraries in the add-on",
        "Jackson 2.11.1, Apache Commons IO 2.5 and Commons Compress 1.12, brought in by youtubedl-android; AndroidX " +
            "(appcompat, core and what they bring); Kotlin, kotlinx.coroutines and kotlinx.serialization",
        APACHE, "FasterXML, The Apache Software Foundation, The Android Open Source Project and JetBrains", listOf(APACHE_TEXT), addon = KjbAddons.DOWNLOADS,
    ),
)

/** The Downloads add-on's row: its NOTICE, then the full text of each of its licences, each once, from its own APK. */
val DL_NOTICES: Shipped = Shipped(
    "KJB Downloads",
    "The Downloads add-on: yt-dlp on Python, FFmpeg and QuickJS (youtubedl-android), and the libraries they are built with",
    "Its licences",
    "KJB contributors",
    listOf("NOTICE.txt") + DL_THIRD_PARTY.flatMap { it.texts }.distinct(),
    addon = KjbAddons.DOWNLOADS,
)

/**
 * The repository's NOTICE (credits, every library at more length, the Apache NOTICE files and the trademarks), then the
 * full text of every licence of [THIRD_PARTY], each once: what ships beside KJB, on one page.
 */
val NOTICES: Shipped = Shipped(
    "Notices",
    "Credits, trademarks, the Apache 2.0 NOTICE files, and the full text of every licence named",
    "Credits and licences",
    "KJB contributors",
    listOf("NOTICE.txt") + THIRD_PARTY.flatMap { it.texts }.distinct(),
)

/** Every licence KJB knows, each with its own page (a page kept from an older version opens too). */
val SHIPPED: List<Shipped> = listOf(KJB_LICENCE, NOTICES, AI_NOTICES, DL_NOTICES) + THIRD_PARTY + AI_THIRD_PARTY + DL_THIRD_PARTY

/** The rows of the Licences page: KJB's licence and the notices. */
val LICENCE_ROWS: List<Shipped> = listOf(KJB_LICENCE, NOTICES)

/** Each add-on's row, in the order KJB lists the add-ons ([KjbAddons.ALL]). */
val ADDON_ROWS: List<Shipped> = listOf(AI_NOTICES, DL_NOTICES)

/** The rows of the Licences page, with the row of each add-on in [installed] (installed and KJB's own). */
fun licenceRows(installed: Set<KjbAddon>): List<Shipped> = LICENCE_ROWS + ADDON_ROWS.filter { it.addon in installed }

/** The row named [name]; null for a name this version does not list (a page kept from an older one). */
fun shipped(name: String): Shipped? = SHIPPED.firstOrNull { it.name == name }

/**
 * [text] as paragraphs for a phone's width: split at blank lines, the hard line breaks within each joined, a byte order
 * mark dropped.
 */
internal fun paragraphs(text: String): List<String> =
    text.removePrefix("\uFEFF").replace("\r\n", "\n").split(BLANK_LINES)
        .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
        .filter { it.isNotEmpty() }

private val BLANK_LINES = Regex("""\n[ \t]*\n""")

/** [addon] is installed and KJB's own (signed with KJB's key, as before a bind): only then are its texts read. */
private fun ours(context: Context, addon: KjbAddon): Boolean = AndroidAddon(context.applicationContext, addon).let { it.installed() && it.trusted() }

/** Settings > About > Licences: KJB's licence and the notices; each row opens its page. */
@Composable
fun LicencesPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    // Each add-on's row once it is installed and KJB's own (no other app's texts are read).
    val rows by produceState(LICENCE_ROWS, context) { value = licenceRows(withContext(Dispatchers.IO) { KjbAddons.ALL.filter { ours(context, it) }.toSet() }) }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.set_licences), under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "sentence", contentType = "sentence") {
                Text(
                    stringResource(R.string.lic_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp),
                )
            }
            item(key = "kjb", contentType = "group") {
                GroupedRows(rowMotion(reduced)) { rows.forEachIndexed { at, shipped -> LicenceRow(shipped, first = at == 0) } }
            }
        }
    }
}

@Composable
private fun LicenceRow(shipped: Shipped, first: Boolean = false) {
    val nav = LocalNavigator.current
    GroupedRow(
        R.drawable.ic_description,
        shipped.name,
        tile = MaterialTheme.colorScheme.surfaceContainerHighest,
        onTile = MaterialTheme.colorScheme.onSurfaceVariant,
        first = first,
        supporting = { Text(shipped.licence, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = { nav.open(Page.Licence(shipped.name)) },
        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp)) },
    )
}

/** One row's page: what of it ships and who holds it, then its licence's full text, read from the app's assets. */
@Composable
fun LicencePage(page: Page.Licence) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val shipped = remember(page.name) { shipped(page.name) }
    // Null while reading; empty when the text could not be read.
    val text by produceState<List<String>?>(null, shipped) {
        value = withContext(Dispatchers.IO) {
            try {
                // An add-on's texts are in its own APK, read only while it is installed and KJB's own.
                val addon = shipped?.addon
                if (addon != null && !ours(context, addon)) throw PackageManager.NameNotFoundException()
                val assets = if (addon != null) context.createPackageContext(addon.packageName, 0).assets else context.assets
                shipped?.texts.orEmpty().flatMap { file -> paragraphs(assets.open("$LICENCE_DIR/$file").use { it.readBytes().decodeToString() }) }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(LOG_TAG, "licences: the ${shipped?.addon?.label} is not installed")
                emptyList()
            } catch (e: IOException) {
                Log.w(LOG_TAG, "licences: a text not read (${e.javaClass.simpleName})")
                emptyList()
            }
        }
    }
    PageFrame(topBar = { under, backdrop -> BackTopBar(page.name, under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = ScreenPadding + 4.dp, end = ScreenPadding, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shipped != null) {
                item(key = "about", contentType = "about") {
                    Column(rowMotion(reduced), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(shipped.parts, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.lic_copyright, shipped.copyright), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val paragraphs = text
            if (paragraphs != null && paragraphs.isEmpty()) {
                item(key = "unreadable", contentType = "unreadable") { Text(stringResource(R.string.lic_unreadable), rowMotion(reduced), style = MaterialTheme.typography.bodyMedium) }
            }
            items(paragraphs.orEmpty(), contentType = { "paragraph" }) { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
