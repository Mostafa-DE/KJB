import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        versionCode = 8
        versionName = "2.0.1"

        ndk {
            // The AI runs on Snapdragon phones only, all arm64; the QNN libraries are arm64 only.
            abiFilters += "arm64-v8a"
        }

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

    // Two editions of one app, the same applicationId and signing key, so either installs over the other or over 2.0.x
    // and keeps the data: "KJB" (lite) without the AI, and "KJB + AI" (full) with ONNX Runtime and QNN for AI lyrics.
    // The AI sits behind ai.AiEngine in main; each edition's source set gives its EditionEngine.
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
        }
        create("full") {
            dimension = "edition"
            // The run log names the stack the AI ran on.
            buildConfigField("String", "ORT_VERSION", "\"${libs.versions.onnxruntime.get()}\"")
            buildConfigField("String", "QNN_EP_VERSION", "\"${libs.versions.qnnEp.get()}\"")
            buildConfigField("String", "QNN_RUNTIME_VERSION", "\"${libs.versions.qnnRuntime.get()}\"")
            proguardFile("proguard-ai.pro")
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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar(Char::uppercase)
        // Release assets: KJB-<version>.apk (lite) and KJB-<version>-ai.apk (full).
        if (variant.buildType == "release") {
            val edition = if (variant.flavorName == "full") "-ai" else ""
            variant.outputs.forEach { output -> output.outputFileName.set(output.versionName.map { "KJB-$it$edition.apk" }) }
        }
        // Every lite APK is checked for the AI engine as it is assembled.
        if (variant.flavorName == "lite") {
            val check = tasks.register<CheckNoAi>("check${name}NoAi") {
                apks.set(variant.artifacts.get(SingleArtifact.APK))
                report.set(layout.buildDirectory.file("reports/no-ai/${variant.name}.txt"))
            }
            tasks.named { it == "assemble$name" }.configureEach { dependsOn(check) }
        }
    }
}

/**
 * Fails when an APK of KJB (the lite edition) holds any of the AI engine: an ONNX Runtime or QNN native library, or an
 * ONNX Runtime or Qualcomm class in its dex files.
 */
abstract class CheckNoAi : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apks: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val files = apks.get().asFile.listFiles { file -> file.extension == "apk" }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) throw GradleException("no APK in ${apks.get().asFile}")
        val found = files.flatMap { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().flatMap { entry ->
                    when {
                        NATIVE.matches(entry.name) -> sequenceOf("${apk.name}: ${entry.name}")
                        entry.name.endsWith(".dex") -> {
                            val dex = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                            CLASSES.filter { it in dex }.map { "${apk.name}: ${entry.name} has classes under $it" }.asSequence()
                        }
                        else -> emptySequence()
                    }
                }.toList()
            }
        }
        if (found.isNotEmpty()) throw GradleException("KJB (lite) must not ship the AI engine:\n" + found.joinToString("\n"))
        report.get().asFile.writeText("No ONNX Runtime or QNN in ${files.joinToString { it.name }}\n")
    }

    private companion object {
        /** ONNX Runtime's and QNN's native libraries. */
        val NATIVE = Regex("""lib/[^/]+/lib(onnxruntime|Qnn)[^/]*\.so""")

        /** Their Java packages, as dex type descriptors. */
        val CLASSES = listOf("Lai/onnxruntime/", "Lcom/qualcomm/")
    }
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
    "fullImplementation"(libs.onnxruntime.android)
    "fullImplementation"(libs.onnxruntime.qnn.ep)
    "fullImplementation"(libs.qnn.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    "testFullImplementation"(libs.onnxruntime.jvm)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.media3.test.utils)
}
