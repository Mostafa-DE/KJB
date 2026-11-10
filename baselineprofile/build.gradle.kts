// KJB's Baseline Profile: the generator (BaselineProfileGenerator) and the startup and scroll benchmarks (Benchmarks.kt),
// run on a connected phone against :app's nonMinifiedRelease and benchmarkRelease builds, app.lyrify.bench. The plugin
// makes those two build types here too, enables only them, and tells the tests the app id and which of them to run.
// How to run it: lyrify-bench/handoff/baseline-profile.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "app.lyrify.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // The phone over adb (rooted, or Android 13+); no emulator, no Gradle-managed device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
