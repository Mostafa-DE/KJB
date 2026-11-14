pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Lyrify"
include(":app")
// TagLib for the tag writer (tags/), a vendored native module.
include(":taglib")
// The Baseline Profile's generator and the startup and scroll benchmarks, run on the phone (baselineprofile/).
include(":baselineprofile")
// The AI add-on, its own APK (KJB AI, app.lyrify.ai) that KJB installs and binds (lyrify-bench handoff/ai-addon-design.md),
// and what the two share: the AIDL contract and its wire types, the chip table, the verified downloader, the audio decoder.
include(":ai-shared")
include(":ai-addon")
// What KJB and every add-on share: the trust both ways and the Downloads add-on's contract; and the Downloads add-on, its
// own APK (KJB Downloads, app.lyrify.dl) with yt-dlp (lyrify-bench handoff/downloads-addon-design.md).
include(":addon-shared")
