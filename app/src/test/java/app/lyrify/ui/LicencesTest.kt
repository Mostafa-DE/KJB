package app.lyrify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings > About > Licences: every text it names ships in assets/licences, every text there is named, the notices are
 * the repository's NOTICE, and every library the app depends on directly is listed. Transitive libraries (Guava, Okio,
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
        val named = ADDON_THIRD_PARTY.flatMap { it.texts }.toSet()
        assertEquals(addonFolder.list().orEmpty().toSet(), named)
        listOf("ONNX Runtime", "ONNX Runtime QNN", "Qualcomm AI Engine Direct", "Microsoft 1DS SDK", "Whisper tokenizer").forEach { name ->
            assertTrue(name, ADDON_THIRD_PARTY.any { it.name == name })
            assertFalse("$name is not in KJB", THIRD_PARTY.any { it.name == name })
        }
        assertTrue(ADDON_THIRD_PARTY.all { it.inAddon } && ADDON_NOTICES.inAddon && THIRD_PARTY.none { it.inAddon })
        assertEquals(ADDON_THIRD_PARTY.flatMap { it.texts }.distinct(), ADDON_NOTICES.texts)
        assertEquals(listOf(KJB_LICENCE, NOTICES), licenceRows(addonInstalled = false))
        assertEquals(listOf(KJB_LICENCE, NOTICES, ADDON_NOTICES), licenceRows(addonInstalled = true))
        assertEquals(ADDON_NOTICES, shipped(ADDON_NOTICES.name))
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

        /** A [libraries] line of the catalog: its alias and its group. */
        val LIBRARY = Regex("""(?m)^([\w-]+)\s*=\s*\{\s*group\s*=\s*"([^"]+)"""")

        /** `implementation(libs.x.y)` and `implementation(platform(libs.x.y))`. */
        val IMPLEMENTATION = Regex("""(?m)^\s*implementation\((?:platform\()?libs\.([\w.]+)\)""")
    }
}
