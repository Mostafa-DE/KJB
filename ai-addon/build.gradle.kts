import java.util.Properties

// The KJB AI add-on (lyrify-bench handoff/ai-addon-design.md): its own APK, "KJB AI", app.lyrify.ai (debug:
// app.lyrify.ai.debug), with no launcher icon. KJB downloads and installs it on supported phones and binds its one service
// (app.lyrify.ai.AiService) while the AI works; the AI runs here, in the add-on's own process, with ONNX Runtime and the
// QNN libraries, and the models live in its own files. Signed with KJB's key: only KJB holds its signature permission, and
// each side checks the other's certificate.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeystore = rootProject.extra["releaseKeystore"] as Properties?

@Suppress("UNCHECKED_CAST")
val certPins = rootProject.extra["certPins"] as Map<String, String>

android {
    namespace = "app.lyrify.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lyrify.ai"
        minSdk = 30
        targetSdk = 36
        // The add-on's own; what KJB checks is the AIDL's VERSION (IAiAddon), not this.
        versionCode = 1
        versionName = "3.0.0"

        ndk {
            // The AI runs on Snapdragon phones only, all arm64; the QNN libraries are arm64 only.
            abiFilters += "arm64-v8a"
        }

        // KJB's run log names the stack the AI ran on (AddonStatus.runtime).
        buildConfigField("String", "ORT_VERSION", "\"${libs.versions.onnxruntime.get()}\"")
        buildConfigField("String", "QNN_EP_VERSION", "\"${libs.versions.qnnEp.get()}\"")
        buildConfigField("String", "QNN_RUNTIME_VERSION", "\"${libs.versions.qnnRuntime.get()}\"")
    }

    packaging {
        jniLibs {
            // Extracted to nativeLibraryDir on install, as in the measured spike: the QNN EP plugin is registered by
            // file path, and the Hexagon DSP loads its skel library from a folder listed in ADSP_LIBRARY_PATH.
            // libQnnHtpPrepare.so stays: the MDX model is a plain ONNX model prepared on the phone.
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
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Each add-on serves the KJB of its own build type (KJB_PACKAGE), signed with the same key (KJB_CERT_SHA256).
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            buildConfigField("String", "KJB_PACKAGE", "\"app.lyrify.debug\"")
            buildConfigField("String", "KJB_CERT_SHA256", "\"${certPins.getValue("debug")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "KJB_PACKAGE", "\"app.lyrify\"")
            buildConfigField("String", "KJB_CERT_SHA256", "\"${certPins.getValue("release")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

androidComponents {
    // The release asset beside KJB's: KJB-AI-<version>.apk, which KJB downloads (its SHA-256 is pinned in KJB).
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output -> output.outputFileName.set(output.versionName.map { "KJB-AI-$it.apk" }) }
    }
}

// As :app: test classes spread over a quarter of the cores.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
}

dependencies {
    implementation(project(":ai-shared"))
    // The caller check every add-on runs on every call.
    implementation(project(":addon-shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.qnn.ep)
    implementation(libs.qnn.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)
}
