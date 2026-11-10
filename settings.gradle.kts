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
