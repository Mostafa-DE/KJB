// What KJB (:app) and every add-on share (lyrify-bench handoff/ai-addon-design.md, downloads-addon-design.md): the trust
// both ways (KJB checks an add-on before it binds it, an add-on checks KJB on every call), and the Downloads add-on's
// AIDL contract and its JSON wire types (the AI's are in :ai-shared). Pure Kotlin and AIDL: no native code.
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.lyrify.addon.shared"
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

    testImplementation(libs.junit)
}
