import java.io.ByteArrayOutputStream
import javax.inject.Inject

// TagLib for KJB's tag writer: a vendored copy of Kyant0/taglib (Apache-2.0) over TagLib 2.3.2 (LGPL-2.1), patched as
// README.md says. arm64-v8a only, like the app.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.kyant.taglib"
    compileSdk = 37
    // The installed NDK r27.1. It does not align shared libraries to 16 KB pages by itself, so src/main/cpp/CMakeLists.txt
    // sets max-page-size, and checkReleaseLoadAlignment below fails the release build if that ever stops working.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

/**
 * Fails unless every LOAD segment of each library in [libraries] is aligned to 16 KB (0x4000) or more, as the pinned
 * NDK's `llvm-readelf -l` reads it: phones with 16 KB memory pages load nothing less. [report] keeps the alignments.
 */
abstract class CheckLoadAlignment : DefaultTask() {
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @get:InputFile
    abstract val readelf: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun check() {
        val found = libraries.asFileTree.matching { include("**/*.so") }.files.sortedBy { it.path }
        if (found.isEmpty()) throw GradleException("16 KB alignment: no native library was built to check")
        val lines = found.map { library ->
            val out = ByteArrayOutputStream()
            exec.exec {
                commandLine(readelf.get().asFile.path, "-lW", library.path)
                standardOutput = out
            }
            // "LOAD offset vaddr paddr filesz memsz flags align": the alignment is the last column.
            val aligns = out.toString().lines().map(String::trim).filter { it.startsWith("LOAD ") }
                .map { it.split(Regex("\\s+")).last().removePrefix("0x").toLong(16) }
            val name = "${library.parentFile.name}/${library.name}"
            if (aligns.isEmpty() || aligns.any { it < MIN_ALIGN }) {
                throw GradleException("16 KB alignment: $name has LOAD alignments ${aligns.map { "0x" + it.toString(16) }}, needs 0x4000 or more")
            }
            "$name LOAD align ${aligns.joinToString { "0x" + it.toString(16) }}"
        }
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val MIN_ALIGN: Long = 0x4000
    }
}

val checkReleaseLoadAlignment = tasks.register<CheckLoadAlignment>("checkReleaseLoadAlignment") {
    // The stripped libraries, as the app packages them.
    libraries.from(tasks.named("stripReleaseDebugSymbols"))
    readelf.set(androidComponents.sdkComponents.ndkDirectory.map { it.file("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf") })
    report.set(layout.buildDirectory.file("reports/load-alignment-release.txt"))
}

// Every release build checks: this module's own, and the app's (which takes the libraries from copyReleaseJniLibsProjectOnly).
tasks.matching { it.name == "assembleRelease" || it.name == "copyReleaseJniLibsProjectOnly" }.configureEach {
    dependsOn(checkReleaseLoadAlignment)
}
