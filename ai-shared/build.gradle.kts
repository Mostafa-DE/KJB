// What KJB (:app) and its AI add-on (:ai-addon) both compile: the add-on's AIDL contract and its JSON wire types, the
// AI's result types, the chip table, the verified downloader (the models in the add-on, the add-on's own APK in KJB), and
// the audio decoder and LRC format both use (Tones in KJB, the AI in the add-on). No ONNX Runtime and no native code: KJB
// ships this, the add-on ships the AI.
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.lyrify.ai.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

// As :app: test classes spread over a quarter of the cores.
tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
