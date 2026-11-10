package app.lyrify.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * What the Baseline Profile gains, measured on the phone with app.lyrify.bench (benchmarkRelease: minified like release,
 * debug-signed): each journey without any ahead-of-time compilation (a fresh install with no profile) and with the
 * profile only, as developer.android.com's "Benchmark Baseline Profiles" compares them. The "WithProfile" tests need a
 * generated profile in app/src/release/generated/baselineProfiles/ and fail without one.
 */

private const val ITERATIONS = 10

/** Cold start to the Library: StartupTimingMetric's timeToInitialDisplayMs. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilation: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        // The first run's setup screen is passed here, unmeasured, so every measured start opens the Library.
        setupBlock = {
            startToLibrary()
            pressHome()
        },
    ) {
        startToLibrary()
    }
}

/** Library, Songs flung down three times: FrameTimingMetric's frameDurationCpuMs and frameOverrunMs (Android 12+). */
@RunWith(AndroidJUnit4::class)
class ScrollSongsBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollWithoutProfile() = scroll(CompilationMode.None())

    @Test
    fun scrollWithProfile() = scroll(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun scroll(compilation: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = ITERATIONS,
        // A fresh start to the top of Songs each time; only the flings are measured.
        setupBlock = {
            startToLibrary()
            openSongs()
        },
    ) {
        flingSongs()
    }
}
