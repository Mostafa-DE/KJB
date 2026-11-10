package app.lyrify.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records KJB's Baseline Profile on the phone: `./gradlew :app:generateBaselineProfile` runs it on app.lyrify.bench
 * and saves the rules in app/src/release/generated/baselineProfiles/. [startup] also makes the Startup Profile, so it
 * holds the start only; [journeys] walks the screens people use most, for smooth scrolling and moves from the first run.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        startToLibrary()
    }

    @Test
    fun journeys() = rule.collect(packageName = targetPackage) {
        startToLibrary()
        scrollSongs()
        openAnAlbum()
        openPlaylists()
        search()
        openAndCloseThePlayer()
    }
}
