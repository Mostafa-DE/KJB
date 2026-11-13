import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Release signing, kept outside the repo: storeFile, storePassword, keyAlias, keyPassword.
 * Read from LYRIFY_KEYSTORE_PROPS, else ~/.android-keys/lyrify-keystore.properties.
 * Without it the release build is simply unsigned, so the project builds anywhere.
 */
val releaseKeystore: Properties? = run {
    val path = providers.environmentVariable("LYRIFY_KEYSTORE_PROPS").orNull
        ?: "${System.getProperty("user.home")}/.android-keys/lyrify-keystore.properties"
    val file = File(path)
    if (file.isFile) Properties().apply { file.inputStream().use { load(it) } } else null
}

android {
    namespace = "app.lyrify"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lyrify"
        minSdk = 30
        targetSdk = 36
        // 10, not 9: a private 2.0.2 used 9 on the user's phone, and 3.0.0 installs over it as an update.
        versionCode = 10
        versionName = "3.0.0"

        ndk {
            // The AI runs on Snapdragon phones only, all arm64; the QNN libraries are arm64 only.
            abiFilters += "arm64-v8a"
        }

        // The run log names the stack the AI ran on.
        buildConfigField("String", "ORT_VERSION", "\"${libs.versions.onnxruntime.get()}\"")
        buildConfigField("String", "QNN_EP_VERSION", "\"${libs.versions.qnnEp.get()}\"")
        buildConfigField("String", "QNN_RUNTIME_VERSION", "\"${libs.versions.qnnRuntime.get()}\"")

        // Device tests (app/src/androidTest) run on the debug build, beside the release app.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            // Extracted to nativeLibraryDir on install, as in the measured spike: the QNN EP plugin is registered by
            // file path, and the Hexagon DSP loads its skel library from a folder listed in ADSP_LIBRARY_PATH.
            useLegacyPackaging = true
            // qnn-runtime ships every Hexagon generation plus the DSP and GPU backends. Only HTP is used, on the
            // supported chips' Hexagon versions (models/NpuChips): v69 (8 Gen 1), v75 (8 Gen 3), v79 (8 Elite),
            // v81 (8 Elite Gen 5). The rest stays out of the APK.
            excludes += listOf(
                "**/libQnnDsp.so",
                "**/libQnnDspV66Skel.so",
                "**/libQnnDspV66Stub.so",
                "**/libQnnGpu.so",
                "**/libQnnHtpV68Skel.so",
                "**/libQnnHtpV68Stub.so",
                "**/libQnnHtpV73Skel.so",
                "**/libQnnHtpV73Stub.so",
            )
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore.getProperty("storeFile"))
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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // The one build that may change the user's own files anywhere (data/SharedWrites.kt). Every other build type
            // sets this true, and the check under androidComponents fails the build when one does not.
            buildConfigField("boolean", "TEST_FOLDER_ONLY", "false")
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
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.qnn.ep)
    implementation(libs.qnn.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(project(":taglib"))
    implementation(libs.glance.appwidget)
    implementation(libs.androidx.profileinstaller)
    // The rules that :baselineprofile records on the phone, saved in src/release/generated/baselineProfiles/.
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)

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
