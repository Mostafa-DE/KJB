package app.lyrify.ui

import app.lyrify.addon.KjbAddons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings > About > Licences: every text it names ships in assets/licences, every text there is named, the notices are
 * the repository's NOTICE, each add-on's texts ship in its own APK and show only with it, and every library the app
 * depends on directly is listed. Transitive libraries (Guava, Okio,
 * Accompanist) and native code cannot be checked from here: they were listed from `:app:dependencies
 * --configuration releaseRuntimeClasspath` and the release APK's lib/ folder.
 */
class LicencesTest {
    private val folder = File("src/main/assets/$LICENCE_DIR")

    @Test
    fun `every licence text named ships, and every text that ships is named`() {
        assertTrue("run from the app module: ${folder.absolutePath}", folder.isDirectory)
        val named = SHIPPED.filterNot { it.inAddon }.flatMap { it.texts }.toSet()
        val shipped = folder.list().orEmpty().toSet()
        assertEquals(shipped, named)
        shipped.forEach { assertTrue(it, File(folder, it).length() > 0) }
    }

    @Test
    fun `the notices in the app are the repository's NOTICE`() {
        assertEquals(File("../NOTICE").readText(), File(folder, "NOTICE.txt").readText())
    }

    @Test
    fun `the AI's own software is the add-on's, its texts ship in the add-on, and KJB lists it only with the add-on there`() {
        val addonFolder = File("../ai-addon/src/main/assets/$LICENCE_DIR")
        val named = AI_THIRD_PARTY.flatMap { it.texts }.toSet()
        assertEquals(addonFolder.list().orEmpty().toSet(), named)
        listOf("ONNX Runtime", "ONNX Runtime QNN", "Qualcomm AI Engine Direct", "Microsoft 1DS SDK", "Whisper tokenizer").forEach { name ->
            assertTrue(name, AI_THIRD_PARTY.any { it.name == name })
            assertFalse("$name is not in KJB", THIRD_PARTY.any { it.name == name })
        }
        assertTrue((AI_THIRD_PARTY + AI_NOTICES).all { it.addon == KjbAddons.AI } && THIRD_PARTY.none { it.inAddon })
        assertEquals(AI_THIRD_PARTY.flatMap { it.texts }.distinct(), AI_NOTICES.texts)
        assertEquals(listOf(KJB_LICENCE, NOTICES), licenceRows(emptySet()))
        assertEquals(listOf(KJB_LICENCE, NOTICES, AI_NOTICES), licenceRows(setOf(KjbAddons.AI)))
        assertEquals(AI_NOTICES, shipped(AI_NOTICES.name))
    }

    @Test
    fun `the Downloads add-on's software is the add-on's, its texts ship in the add-on, and KJB lists it only with the add-on there`() {
        val addonFolder = File("../dl-addon/src/main/assets/$LICENCE_DIR")
        assertEquals(addonFolder.list().orEmpty().toSet(), DL_NOTICES.texts.toSet())
        DL_NOTICES.texts.forEach { assertTrue(it, File(addonFolder, it).length() > 0) }
        // The add-on's NOTICE, then the full text of every licence its parts name, each once.
        assertEquals("NOTICE.txt", DL_NOTICES.texts.first())
        assertEquals(DL_THIRD_PARTY.flatMap { it.texts }.distinct(), DL_NOTICES.texts.drop(1))
        DL_ENGINE.forEach { name ->
            assertTrue(name, DL_THIRD_PARTY.any { it.name == name })
            assertFalse("$name is not in KJB", THIRD_PARTY.any { it.name == name })
        }
        assertTrue((DL_THIRD_PARTY + DL_NOTICES).all { it.addon == KjbAddons.DOWNLOADS })
        assertEquals(listOf(KJB_LICENCE, NOTICES, DL_NOTICES), licenceRows(setOf(KjbAddons.DOWNLOADS)))
        assertEquals(listOf(KJB_LICENCE, NOTICES, AI_NOTICES, DL_NOTICES), licenceRows(KjbAddons.ALL.toSet()))
        assertEquals(DL_NOTICES, shipped(DL_NOTICES.name))
    }

    @Test
    fun `the Downloads add-on's NOTICE names each of its parts and where their source is, and KJB's NOTICE points to it`() {
        val file = File("../dl-addon/src/main/assets/$LICENCE_DIR/NOTICE.txt")
        assertTrue("the add-on's NOTICE ships in it", file.isFile)
        val notice = file.readText()
        DL_THIRD_PARTY.forEach { assertTrue(it.name, notice.contains(it.name)) }
        // The GPL and LGPL parts: where their source is, for each.
        listOf("Source code", "github.com/termux/termux-packages", "-sources.jar", "--enable-gpl --enable-version3").forEach { assertTrue(it, notice.contains(it)) }
        val kjb = File("../NOTICE").readText()
        listOf("KJB Downloads", "dl-addon/src/main/assets/licences").forEach { assertTrue(it, kjb.contains(it)) }
        DL_ENGINE.forEach { assertTrue(it, kjb.contains(it)) }
    }

    @Test
    fun `the Downloads add-on's NOTICE and KJB's say the add-on's complete source is attached to each release`() {
        listOf(File("../dl-addon/src/main/assets/$LICENCE_DIR/NOTICE.txt"), File("../NOTICE")).forEach { file ->
            val text = paragraphs(file.readText()).joinToString(" ")
            listOf(DL_SOURCES, "https://github.com/Mostafa-DE/KJB/releases").forEach { assertTrue("${file.path}: $it", text.contains(it)) }
        }
    }

    @Test
    fun `the source bundle is said to hold the Downloads add-on's GPL, LGPL and MPL parts, not its permissive ones`() {
        listOf(File("../dl-addon/src/main/assets/$LICENCE_DIR/NOTICE.txt"), File("../NOTICE")).forEach { file ->
            val items = paragraphs(file.readText()).flatMap { it.split(" - ") }
            // What tools/make-dl-sources.sh packs: the copyleft parts' source, and only Termux's recipes for the rest.
            val bundle = items.single { it.contains(DL_SOURCES) }
            assertTrue("${file.path}: $bundle", bundle.contains("source of the add-on's parts under the GNU GPL, the GNU LGPL and the Mozilla Public License"))
            assertTrue(file.path, items.any { it.contains("The bundle does not hold the source of the other parts, which are under permissive licences") })
        }
    }

    @Test
    fun `each GPL, LGPL and MPL library of the Downloads add-on is named at the version whose source the bundle holds`() {
        val notice = paragraphs(File("../dl-addon/src/main/assets/$LICENCE_DIR/NOTICE.txt").readText())
        val script = File("../tools/make-dl-sources.sh").readText()
        val parts = notice.filter { it.startsWith("Under the GNU") || it.startsWith("Under the Mozilla") }.flatMap { it.split(" - ").drop(1) }
        assertTrue(parts.size > 20)
        parts.forEach { part ->
            val version = VERSION.find(part)?.value
            assertTrue(part, version != null && script.contains(version))
        }
    }

    @Test
    fun `every licence the Downloads add-on's NOTICE names has its text in the add-on`() {
        val addonFolder = File("../dl-addon/src/main/assets/$LICENCE_DIR")
        val notice = paragraphs(File(addonFolder, "NOTICE.txt").readText()).joinToString(" ")
        val files = Regex("""[a-z0-9.-]+\.txt""").findAll(notice).map { it.value }.toSet()
        assertTrue(files.size > 10)
        files.forEach { assertTrue("$it is named but does not ship", File(addonFolder, it).isFile) }
        DL_LICENCE_TEXTS.forEach { (licence, text) ->
            if (notice.contains(licence)) assertTrue("$licence is named but $text does not ship", File(addonFolder, text).isFile)
        }
    }

    @Test
    fun `each LGPL part of the Downloads add-on says which versions of the LGPL it is under`() {
        val notice = paragraphs(File("../dl-addon/src/main/assets/$LICENCE_DIR/NOTICE.txt").readText())
        val lgpl = notice.single { it.startsWith("Under the GNU LGPL") }.split(" - ")
        // No blanket "or later" over the list: mpg123 is LGPL 2.1 only.
        assertFalse(lgpl.first(), lgpl.first().contains("or later"))
        assertTrue(lgpl.size > 15)
        lgpl.drop(1).forEach { part -> assertTrue(part, part.contains("or later") || part.contains("only")) }
        val mpg123 = lgpl.single { it.startsWith("mpg123") }
        assertTrue(mpg123, mpg123.contains("LGPL 2.1 only") && !mpg123.contains("or later"))
        // Linked into the GPL 3 FFmpeg build, which LGPL 2.1 section 3 allows.
        assertTrue(mpg123, mpg123.contains("section 3 of the LGPL 2.1") && mpg123.contains("GNU GPL version 3"))
        val libv4l = lgpl.single { it.startsWith("libv4l") }
        assertTrue(libv4l, libv4l.contains("GNU GPL version 2 only"))
    }

    @Test
    fun `an add-on's copy of a licence KJB also ships is the same text`() {
        listOf("../ai-addon", "../dl-addon").forEach { module ->
            // Each NOTICE.txt is its own APK's.
            val copies = File("$module/src/main/assets/$LICENCE_DIR").listFiles().orEmpty().filter { it.name != "NOTICE.txt" && File(folder, it.name).isFile }
            assertTrue(module, copies.isNotEmpty())
            copies.forEach { copy ->
                assertEquals("$module ${copy.name}", File(folder, copy.name).readText(), copy.readText())
            }
        }
    }

    @Test
    fun `KJB's GPL section 7 permission opens its page, ends LICENSE, and reads the same in the README`() {
        assertEquals(listOf(PERMISSION, "gpl-3.0.txt"), KJB_LICENCE.texts)
        val permission = paragraphs(File(folder, PERMISSION).readText())
        assertEquals("Additional permission under GNU GPL version 3 section 7", permission.first())
        assertTrue(permission.last(), permission.last().contains("Qualcomm AI Engine Direct (QNN) runtime"))
        assertEquals(permission, paragraphs(File("../LICENSE").readText()).takeLast(permission.size))
        assertTrue(paragraphs(File("../README.md").readText()).windowed(permission.size).contains(permission))
    }

    @Test
    fun `the page lists KJB and the notices, with no Built with list, and the notices carry every licence in full`() {
        // User feedback 25: the "Built with" list is gone from the page.
        assertEquals(listOf(KJB_LICENCE, NOTICES), LICENCE_ROWS)
        // NOTICE names every library; its page shows it, then the full text of every licence it names, each once.
        assertEquals("NOTICE.txt", NOTICES.texts.first())
        assertEquals(THIRD_PARTY.flatMap { it.texts }.distinct(), NOTICES.texts.drop(1))
        assertEquals(NOTICES.texts, NOTICES.texts.distinct())
    }

    @Test
    fun `each entry has its own name, a licence and a holder`() {
        assertEquals(SHIPPED.size, SHIPPED.map { it.name }.toSet().size)
        assertTrue(SHIPPED.size > 20)
        SHIPPED.forEach { assertTrue(it.name, it.licence.isNotBlank() && it.copyright.isNotBlank() && it.parts.isNotBlank() && it.texts.isNotEmpty()) }
        assertEquals(KJB_LICENCE, shipped(KJB_LICENCE.name))
        assertEquals(null, shipped("Hologram"))
    }

    @Test
    fun `every library the app module depends on is listed`() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        val groups = LIBRARY.findAll(catalog).associate { it.groupValues[1].replace('-', '.') to it.groupValues[2] }
        val used = IMPLEMENTATION.findAll(File("build.gradle.kts").readText()).map { it.groupValues[1] }.toList()
        assertTrue(used.size > 10)
        val listed = THIRD_PARTY.joinToString("\n") { it.parts }
        used.forEach { alias ->
            val group = groups[alias] ?: error("$alias not in the catalog")
            assertTrue("$alias ($group) is not on the Licences screen", group in listed)
        }
        // The TagLib module, and what it builds into libtaglib.so.
        listOf("TagLib", "Kyant0/taglib", "utfcpp", "LLVM libc++").forEach { name -> assertTrue(name, THIRD_PARTY.any { it.name == name }) }
    }

    @Test
    fun `a licence reads as paragraphs that fit the phone's width`() {
        val text = "\uFEFFMIT Licence\r\n\r\nCopyright (c) 2026 Quillmere\r\n\r\nPermission is hereby granted,\nfree of charge,\n  to any person\n\n\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\".\n   \n"
        assertEquals(
            listOf("MIT Licence", "Copyright (c) 2026 Quillmere", "Permission is hereby granted, free of charge, to any person", "THE SOFTWARE IS PROVIDED \"AS IS\"."),
            paragraphs(text),
        )
        assertEquals(emptyList<String>(), paragraphs("\n \n"))
    }

    private companion object {
        const val PERMISSION: String = "kjb-additional-permission.txt"

        /** What runs a download in the Downloads add-on: youtubedl-android and what it carries. */
        val DL_ENGINE: List<String> = listOf("youtubedl-android", "yt-dlp", "Python", "FFmpeg", "QuickJS", "Mutagen", "PyCryptodome")

        /** The source bundle attached to each KJB release that ships the Downloads add-on (tools/make-dl-sources.sh). */
        const val DL_SOURCES: String = "KJB-Downloads-<version>-sources.tar.gz"

        /** A licence the Downloads add-on's NOTICE may name, and the file in the add-on that holds its text. */
        val DL_LICENCE_TEXTS: List<Pair<String, String>> = listOf(
            "GNU General Public License version 3" to "gpl-3.0.txt",
            "GNU General Public License version 2" to "gpl-2.0.txt",
            "GNU GPL, version 2" to "gpl-2.0.txt",
            "GPL 2.0" to "gpl-2.0.txt",
            "LGPL 2.0" to "lgpl-2.0.txt",
            "LGPL 2.1" to "lgpl-2.1.txt",
            "LGPL 3.0" to "lgpl-3.0.txt",
            "Mozilla Public License" to "mpl-2.0.txt",
            "MPL 2.0" to "mpl-2.0.txt",
            "Apache License 2.0" to "apache-2.0.txt",
            "LLVM Exceptions" to "llvm-exception.txt",
            "Unlicense" to "unlicense-yt-dlp.txt",
            "Python Software Foundation License" to "python-license.txt",
            "NCSA" to "ncsa-libandroid-stub.txt",
            "BSD" to "third-party-notices.txt",
            "ISC" to "third-party-notices.txt",
        )

        /** A version such as 1.3.14 or 0.164.3191. */
        val VERSION = Regex("""\d+(\.\d+)+""")

        /** A [libraries] line of the catalog: its alias and its group. */
        val LIBRARY = Regex("""(?m)^([\w-]+)\s*=\s*\{\s*group\s*=\s*"([^"]+)"""")

        /** `implementation(libs.x.y)` and `implementation(platform(libs.x.y))`. */
        val IMPLEMENTATION = Regex("""(?m)^\s*implementation\((?:platform\()?libs\.([\w.]+)\)""")
    }
}
