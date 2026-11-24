import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

/** Release signing (the root build file reads it), shared with the AI add-on, which is signed with the same key. */
val releaseKeystore = rootProject.extra["releaseKeystore"] as Properties?

/** The certificate each build type signs with (root build file): the AI add-on must be signed with it too. */
@Suppress("UNCHECKED_CAST")
val certPins = rootProject.extra["certPins"] as Map<String, String>

/** KJB's GitHub repository, "owner/name" (root build file). */
val kjbRepo = rootProject.extra["kjbRepo"] as String

/**
 * The AI add-on's APK that KJB downloads (Tools > AI lyrics > Get AI): KJB-AI-<version>.apk, the release asset beside
 * KJB's own on the GitHub release, its SHA-256 (hex) and size. The release lane pins the published file here: KJB AI
 * 3.0.3 on v3.0.3. With no SHA-256 nothing is downloaded and Get AI says it could not get the add-on. -PaiAddonUrl,
 * -PaiAddonSha256 and -PaiAddonBytes point a test build at another file (GitHub only, and signed with this build's key).
 */
val aiAddonUrl = findProperty("aiAddonUrl")?.toString() ?: "https://github.com/$kjbRepo/releases/download/v3.0.3/KJB-AI-3.0.3.apk"
val aiAddonSha256 = findProperty("aiAddonSha256")?.toString() ?: "eb829e372786b23dfa4a2290fa95dc3448d1c0bbaa41450fe0504472b033b6c1" // the release asset's SHA-256
val aiAddonBytes = findProperty("aiAddonBytes")?.toString() ?: "72725922" // the release asset's size in bytes

/**
 * The repository ("owner/name") whose latest release the update check reads (update/Updates.kt): KJB's own. GitHub's API
 * also redirects the old name. -PupdateRepo points a test build at another repository.
 */
val updateRepo = findProperty("updateRepo")?.toString() ?: kjbRepo

/**
 * The Downloads add-on's APK that KJB downloads (Tools > Downloads > Get Downloads): KJB-Downloads-<version>.apk on the same
 * release, pinned the same way: KJB Downloads 4.0.0 on v4.0.0. -PdlAddonUrl, -PdlAddonSha256 and -PdlAddonBytes point
 * a test build at another file.
 */
val dlAddonUrl = findProperty("dlAddonUrl")?.toString() ?: "https://github.com/$kjbRepo/releases/download/v4.0.0/KJB-Downloads-4.0.0.apk"
val dlAddonSha256 = findProperty("dlAddonSha256")?.toString() ?: "3ebe1202081404be21ed327c44633b3a1a8e0e5db9e3a54c73ab095f30c63d97" // the release asset's SHA-256
val dlAddonBytes = findProperty("dlAddonBytes")?.toString() ?: "55931398" // the release asset's size in bytes

/**
 * Huddle's server (huddle-server/, plan Q3.2): its Railway address (live since 2026-11-22, serving assetlinks.json for
 * the release key), and the user's own domain, added later. KJB talks to the first and opens its invite links (App
 * Links); a pasted invite may name either. The domain joins the manifest in 4.1, once it is live: on Android 11 a host
 * that fails the check takes App Links from every host. -PhuddleHost points a test build at another server.
 */
val huddleHost = findProperty("huddleHost")?.toString() ?: "kjb-huddle.up.railway.app"
val huddleDomain = "huddle.kjb.mostafade.com"

android {
    namespace = "app.lyrify"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lyrify"
        minSdk = 30
        targetSdk = 36
        // 3.0.0 was 10, not 9: a private 2.0.2 used 9 on the user's phone, and 3.0.0 installed over it as an update.
        versionCode = 17
        versionName = "4.0.0"

        ndk {
            // TagLib's library is built for arm64 only (taglib/), as the AI add-on's QNN libraries are.
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "AI_ADDON_URL", "\"$aiAddonUrl\"")
        buildConfigField("String", "AI_ADDON_SHA256", "\"$aiAddonSha256\"")
        buildConfigField("long", "AI_ADDON_BYTES", "${aiAddonBytes}L")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        // KJB's repository's address, in the User-Agent of every request KJB makes.
        buildConfigField("String", "REPOSITORY", "\"https://github.com/$kjbRepo\"")
        buildConfigField("String", "DL_ADDON_URL", "\"$dlAddonUrl\"")
        buildConfigField("String", "DL_ADDON_SHA256", "\"$dlAddonSha256\"")
        buildConfigField("long", "DL_ADDON_BYTES", "${dlAddonBytes}L")
        buildConfigField("String", "HUDDLE_HOST", "\"$huddleHost\"")
        buildConfigField("String", "HUDDLE_DOMAIN", "\"$huddleDomain\"")
        manifestPlaceholders["huddleHost"] = huddleHost

        // Device tests (app/src/androidTest) run on the debug build, beside the release app.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            // Compressed in the APK and extracted on install, as before the AI moved out: a smaller download. The AI's
            // ONNX Runtime and QNN libraries are in the AI add-on (ai-addon/), never in KJB.
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            // Installs beside the release app, never over it: app.lyrify.debug, named "KJB dev", with its own data and
            // grants. It changes shared storage only in Music/KJB test/ (data/SharedWrites.kt), and StrictMode logs
            // disk work on the main thread (Lyrify.kt).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "TEST_FOLDER_ONLY", "true")
            // The AI add-on beside KJB dev: app.lyrify.ai.debug, signed with the debug key (ai-addon/).
            buildConfigField("String", "AI_ADDON_PACKAGE", "\"app.lyrify.ai.debug\"")
            buildConfigField("String", "AI_CERT_SHA256", "\"${certPins.getValue("debug")}\"")
            manifestPlaceholders["aiAddonPackage"] = "app.lyrify.ai.debug"
            // The Downloads add-on beside KJB dev: app.lyrify.dl.debug (dl-addon/).
            buildConfigField("String", "DL_ADDON_PACKAGE", "\"app.lyrify.dl.debug\"")
            buildConfigField("String", "DL_CERT_SHA256", "\"${certPins.getValue("debug")}\"")
            manifestPlaceholders["dlAddonPackage"] = "app.lyrify.dl.debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // The one build that may change the user's own files anywhere (data/SharedWrites.kt). Every other build type
            // sets this true, and the check under androidComponents fails the build when one does not.
            buildConfigField("boolean", "TEST_FOLDER_ONLY", "false")
            // The AI add-on, app.lyrify.ai, signed with KJB's release key (the bench builds below keep these: they bind
            // nothing, being signed with the debug key).
            buildConfigField("String", "AI_ADDON_PACKAGE", "\"app.lyrify.ai\"")
            buildConfigField("String", "AI_CERT_SHA256", "\"${certPins.getValue("release")}\"")
            manifestPlaceholders["aiAddonPackage"] = "app.lyrify.ai"
            buildConfigField("String", "DL_ADDON_PACKAGE", "\"app.lyrify.dl\"")
            buildConfigField("String", "DL_CERT_SHA256", "\"${certPins.getValue("release")}\"")
            manifestPlaceholders["dlAddonPackage"] = "app.lyrify.dl"
        }
        // The Baseline Profile plugin's two copies of release, for the phone only (baselineprofile/): nonMinifiedRelease
        // records the profile, benchmarkRelease measures it. The plugin forces them non-debuggable and profileable, and
        // nonMinifiedRelease unminified. Declared here to give them app.lyrify.bench, beside the user's KJB and never over
        // it (a run uninstalls its app at the end, data and all), the debug key, so they install on any phone without the
        // release keystore, and the debug build's fence: shared storage only in Music/KJB test/, listed and changed.
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "TEST_FOLDER_ONLY", "true")
        }
        create("benchmarkRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "TEST_FOLDER_ONLY", "true")
            // Keeps BuildConfig.TEST_FOLDER_ONLY through R8 for the tests' fence check.
            proguardFiles("benchmark-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

/**
 * Fails unless a variant's BuildConfig.TEST_FOLDER_ONLY is what its build type must have: false only in release, the one
 * build that may change the user's files anywhere, true in every other (data/SharedWrites.kt). [report] keeps the value.
 */
abstract class CheckWriteFence : DefaultTask() {
    @get:Input
    abstract val flag: Property<String>

    @get:Input
    abstract val release: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val expected = (!release.get()).toString()
        if (flag.get() != expected) {
            throw GradleException("write fence: BuildConfig.TEST_FOLDER_ONLY is ${flag.get()} for $name, must be $expected (data/SharedWrites.kt)")
        }
        report.get().asFile.writeText("TEST_FOLDER_ONLY=${flag.get()}\n")
    }
}

/**
 * Fails unless every widget button's callback in a release APK keeps its no-argument constructor. Glance makes a
 * callback (an androidx.glance ActionCallback, widget/Widgets.kt) by its name, with getDeclaredConstructor().newInstance(),
 * which R8 cannot see: without the keep rule in proguard-rules.pro it removed the constructors, and every widget tap
 * failed with NoSuchMethodException in 3.0.2 to 3.0.4 (debug builds are not shrunk, so they never showed it). Reads the
 * APK's dex with the SDK's dexdump, the interface by the name R8's [mapping] gives it; a class implementing it through a
 * superclass or another interface counts, abstract ones do not. Finding none fails too, so a dexdump that changed its
 * output never passes unseen. [report] lists the callbacks checked.
 */
abstract class CheckWidgetCallbacks @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apk: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dexdump: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    /** A class in the dex, by its descriptor ("Lapp/lyrify/widget/WidgetHeart;"), as dexdump's plain layout lists it. */
    private class DexClass(val name: String) {
        var superclass: String? = null
        var instantiable = true
        val interfaces = mutableListOf<String>()
        var noArgConstructor = false
    }

    @TaskAction
    fun check() {
        val file = apk.get().asFile.listFiles { it: File -> it.extension == "apk" }?.singleOrNull()
            ?: throw GradleException("widget callbacks: no single APK in ${apk.get().asFile}")
        val callback = "androidx.glance.appwidget.action.ActionCallback"
        val renamed = mapping.get().asFile.useLines { lines -> lines.firstOrNull { it.startsWith("$callback -> ") } }
        val target = "L" + (renamed?.substringAfter(" -> ")?.removeSuffix(":") ?: callback).replace('.', '/') + ";"
        val dump = temporaryDir.resolve("dexdump.txt")
        dump.outputStream().use { out ->
            exec.exec {
                commandLine(dexdump.get().asFile.path, "-n", file.path)
                standardOutput = out
            }
        }
        val classes = dump.useLines { parse(it) }.associateBy { it.name }
        fun implements(name: String?, seen: Set<String> = emptySet()): Boolean {
            val dexClass = classes[name ?: return false] ?: return false
            if (name in seen) return false
            return target in dexClass.interfaces || (dexClass.interfaces + listOfNotNull(dexClass.superclass)).any { implements(it, seen + name) }
        }
        val callbacks = classes.values.filter { it.instantiable && implements(it.name) }
        if (callbacks.isEmpty()) throw GradleException("widget callbacks: no class implements $callback ($target) in ${file.name}: is the check still reading dexdump right?")
        val stripped = callbacks.filterNot { it.noArgConstructor }.map { dotted(it.name) }
        if (stripped.isNotEmpty()) {
            throw GradleException(
                "widget callbacks: R8 removed the no-argument constructor of ${stripped.joinToString()} in ${file.name}. Glance makes " +
                    "them by reflection, so every widget tap would fail: keep them in app/proguard-rules.pro.",
            )
        }
        report.get().asFile.writeText(callbacks.joinToString("") { "${dotted(it.name)} <init>()\n" })
    }

    /** A class's descriptor as its name: "Lapp/lyrify/widget/WidgetHeart;" is app.lyrify.widget.WidgetHeart. */
    private fun dotted(descriptor: String): String = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    /** The classes in dexdump's plain layout (-n): each one's superclass, interfaces, flags and no-argument constructor. */
    private fun parse(lines: Sequence<String>): List<DexClass> {
        val found = mutableListOf<DexClass>()
        var current: DexClass? = null
        var section = ""
        var method: String? = null
        for (raw in lines) {
            val line = raw.trim()
            val value = line.substringAfter(": ", "").removeSurrounding("'")
            when {
                line.startsWith("Class descriptor") -> current = DexClass(value).also(found::add).also { section = "" }
                current == null -> Unit
                line.startsWith("Access flags") -> current.instantiable = "ABSTRACT" !in line && "INTERFACE" !in line
                line.startsWith("Superclass") -> current.superclass = value
                line.endsWith(" -") && line.removeSuffix(" -").trim() in SECTIONS -> section = line.removeSuffix(" -").trim()
                section == "Interfaces" && line.startsWith("#") -> current.interfaces += value
                section == "Direct methods" && line.startsWith("name ") -> method = value
                section == "Direct methods" && line.startsWith("type ") && method == "<init>" && value == "()V" -> current.noArgConstructor = true
            }
        }
        return found
    }

    private companion object {
        /** A class's parts in dexdump's plain layout; each method's own "code -" is not one. */
        val SECTIONS = setOf("Interfaces", "Static fields", "Instance fields", "Direct methods", "Virtual methods")
    }
}

androidComponents {
    // Only release may change the user's files outside Music/KJB test/: a variant without TEST_FOLDER_ONLY on, the
    // Baseline Profile's bench builds included, fails before its BuildConfig is made, so it never reaches a phone.
    onVariants { variant ->
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val check = tasks.register<CheckWriteFence>("check${name}WriteFence") {
            flag.set(variant.buildConfigFields?.getting("TEST_FOLDER_ONLY")?.map { it.value.toString() }?.orElse("missing") ?: provider { "missing" })
            release.set(variant.buildType == "release")
            report.set(layout.buildDirectory.file("reports/write-fence-${variant.name}.txt"))
        }
        tasks.matching { it.name == "generate${name}BuildConfig" }.configureEach { dependsOn(check) }
    }
    // The release asset: KJB-<version>.apk.
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output -> output.outputFileName.set(output.versionName.map { "KJB-$it.apk" }) }
        // Its widget buttons work: assembleRelease fails when R8 removed a callback's constructor (CheckWidgetCallbacks).
        val name = variant.name.replaceFirstChar(Char::uppercase)
        val callbacks = tasks.register<CheckWidgetCallbacks>("check${name}WidgetCallbacks") {
            apk.set(variant.artifacts.get(SingleArtifact.APK))
            mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            dexdump.set(androidComponents.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/dexdump") })
            report.set(layout.buildDirectory.file("reports/widget-callbacks-${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(callbacks) }
    }
}

// Unit tests run in parallel JVMs, each test class whole in one of them: a quarter of the cores (4 on a 16-core
// machine), so two or three builds on one machine still fit. Not a cache input, so cached results stay shared.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // What KJB and the AI add-on share: the add-on's AIDL and wire types, the chip table, the downloader, the decoder.
    implementation(project(":ai-shared"))
    // What KJB and every add-on share: the trust both ways and the Downloads add-on's contract.
    implementation(project(":addon-shared"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.inspector)
    implementation(project(":taglib"))
    implementation(libs.glance.appwidget)
    implementation(libs.androidx.profileinstaller)
    // The rules that :baselineprofile records on the phone, saved in src/release/generated/baselineProfiles/.
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // plays.db's SQL on the laptop (stats/PlaysSqlTest.kt).
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.media3.test.utils)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Edge swipes for the back gesture, as the system sees them (BackSwipeDeviceTest).
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
