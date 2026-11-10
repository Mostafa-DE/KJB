package app.lyrify.ui

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

// Settings > About > Licences: what the APK is built with, each with its licence's full text. The list comes from the
// release runtime classpath (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`) and the release
// APK's lib/ and assets/ folders; LicencesTest checks it against the texts and the app's direct dependencies. NOTICE in
// the repository says the same at more length and ships as the Notices row.

/** Where the full licence texts ship: assets/licences. */
const val LICENCE_DIR: String = "licences"

private const val APACHE: String = "Apache 2.0"
private const val APACHE_TEXT: String = "apache-2.0.txt"
private const val AOSP: String = "The Android Open Source Project"
private const val JETBRAINS: String = "JetBrains s.r.o. and contributors"

/**
 * One piece of what ships in the app: its [name] (also its page's key), what of it is in the APK ([parts]: Maven groups
 * and artifacts, native libraries, files), its [licence] and copyright holder, and the full texts in [LICENCE_DIR], in
 * the order shown.
 */
@Immutable
data class Shipped(val name: String, val parts: String, val licence: String, val copyright: String, val texts: List<String>)

/** KJB itself: the additional permission under GPL section 7 that ends LICENSE, then the GPL. */
val KJB_LICENCE: Shipped = Shipped("KJB", "This app", "GPL 3.0", "KJB contributors", listOf("kjb-additional-permission.txt", "gpl-3.0.txt"))

/** The repository's NOTICE: credits, every library at more length, the Apache NOTICE files and the trademarks. */
val NOTICES: Shipped = Shipped("Notices", "Credits, trademarks and the Apache 2.0 NOTICE files", "Credits and notices", "KJB contributors", listOf("NOTICE.txt"))

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
        "ONNX Runtime",
        "com.microsoft.onnxruntime: onnxruntime-android 1.30.0 (libonnxruntime.so, libonnxruntime4j_jni.so), with its third-party notices",
        "MIT", "Microsoft Corporation", listOf("mit-onnxruntime.txt", "onnxruntime-notices.txt"),
    ),
    Shipped("Microsoft 1DS SDK", "Inside ONNX Runtime's Java library (META-INF/LICENSE-1DS)", APACHE, "Microsoft Corporation", listOf(APACHE_TEXT)),
    Shipped(
        "ONNX Runtime QNN",
        "com.qualcomm.qti: onnxruntime-android-qnn 2.6.0 (libonnxruntime_providers_qnn.so), with its third-party notices",
        "MIT", "Qualcomm Technologies, Inc. and Microsoft Corporation", listOf("mit-onnxruntime-qnn.txt", "onnxruntime-qnn-notices.txt"),
    ),
    Shipped(
        "Qualcomm AI Engine Direct",
        "com.qualcomm.qti: qnn-runtime 2.50.0 (libQnnHtp.so, libQnnHtpPrepare.so, libQnnSystem.so and the Hexagon v69, v75, " +
            "v79 and v81 libraries), with its NOTICE",
        "Qualcomm AI Stack License", "Qualcomm Technologies, Inc.", listOf("qnn-runtime-license.txt", "qnn-runtime-notices.txt"),
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
    Shipped(
        "Whisper tokenizer",
        "openai/whisper-large-v3-turbo's vocab.json, added_tokens.json and generation_config.json",
        "MIT", "OpenAI", listOf("mit-whisper.txt"),
    ),
    Shipped("Material Symbols", "The app's icons", APACHE, "Google LLC", listOf(APACHE_TEXT)),
    Shipped("Bricolage Grotesque", "The app's title font", "OFL 1.1", "The Bricolage Grotesque Project Authors", listOf("ofl-1.1.txt")),
    Shipped("Roboto Flex", "The app's text font", "OFL 1.1", "The Roboto Flex Project Authors", listOf("ofl-1.1-roboto-flex.txt")),
)

/** Every row of the Licences page. */
val SHIPPED: List<Shipped> = listOf(KJB_LICENCE, NOTICES) + THIRD_PARTY

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

/** Settings > About > Licences: KJB and its notices, then what it is built with; each row opens its licence. */
@Composable
fun LicencesPage() {
    val nav = LocalNavigator.current
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
                GroupedRows(rowMotion(reduced)) {
                    LicenceRow(KJB_LICENCE, first = true)
                    LicenceRow(NOTICES)
                }
            }
            item(key = "built", contentType = "section") {
                Column(rowMotion(reduced)) {
                    SectionTitle(stringResource(R.string.lic_built_with), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 24.dp, bottom = 8.dp))
                    GroupedRows { THIRD_PARTY.forEachIndexed { at, shipped -> LicenceRow(shipped, first = at == 0) } }
                }
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
                shipped?.texts.orEmpty().flatMap { file -> paragraphs(context.assets.open("$LICENCE_DIR/$file").use { it.readBytes().decodeToString() }) }
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
