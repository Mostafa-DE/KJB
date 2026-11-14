import java.util.Properties

// The KJB Downloads add-on (lyrify-bench handoff/downloads-addon-design.md): its own APK, "KJB Downloads", app.lyrify.dl
// (debug: app.lyrify.dl.debug), with no launcher icon. KJB downloads and installs it from Tools > Downloads and binds its
// one service (app.lyrify.dl.DlService) while it looks up links and downloads. VidGrab's engine runs here, in the add-on's
// own process: yt-dlp on Python, ffmpeg and QuickJS (youtubedl-android). It holds only INTERNET and works in its own
// cache: KJB opens the destination and the add-on streams the finished file into it. Signed with KJB's key: only KJB holds
// its signature permission, and each side checks the other's certificate.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

val releaseKeystore = rootProject.extra["releaseKeystore"] as Properties?

@Suppress("UNCHECKED_CAST")
val certPins = rootProject.extra["certPins"] as Map<String, String>

android {
    namespace = "app.lyrify.dl"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lyrify.dl"
        minSdk = 30
        // 35, as VidGrab today: yt-dlp's Python loads its modules from the app's files, which runs at 35 on the phone.
        // 36 is raised only after a phone check of a download (the design); KJB's own target is not tied to it.
        targetSdk = 35
        // The add-on's own; what KJB checks is the AIDL's VERSION (IDlAddon), not this.
        versionCode = 1
        versionName = "3.0.0"

        ndk {
            // Python, ffmpeg and QuickJS are built for arm64 only here, as KJB.
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        jniLibs {
            // Extracted to nativeLibraryDir on install: youtubedl-android runs libpython.so, libffmpeg.so and libqjs.so
            // from there as programs (installed code may run; the app's writable files may not), and unzips Python's
            // libraries from libpython.zip.so and ffmpeg's from libffmpeg.zip.so.
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
    // The release asset beside KJB's: KJB-Downloads-<version>.apk, which KJB downloads (its SHA-256 is pinned in KJB).
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output -> output.outputFileName.set(output.versionName.map { "KJB-Downloads-$it.apk" }) }
    }
}

// As :app: test classes spread over a quarter of the cores.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
}

dependencies {
    implementation(project(":addon-shared"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The device test: one real short download through the bound service (needs the network; run by hand).
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
