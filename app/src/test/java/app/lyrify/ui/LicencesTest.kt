package app.lyrify.ui

import org.junit.Assert.assertEquals
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
        val named = SHIPPED.flatMap { it.texts }.toSet()
        val shipped = folder.list().orEmpty().toSet()
        assertEquals(shipped, named)
        shipped.forEach { assertTrue(it, File(folder, it).length() > 0) }
    }

    @Test
    fun `the notices in the app are the repository's NOTICE`() {
        assertEquals(File("../NOTICE").readText(), File(folder, "NOTICE.txt").readText())
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
        val text = "﻿MIT Licence\r\n\r\nCopyright (c) 2026 Quillmere\r\n\r\nPermission is hereby granted,\nfree of charge,\n  to any person\n\n\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\".\n   \n"
        assertEquals(
            listOf("MIT Licence", "Copyright (c) 2026 Quillmere", "Permission is hereby granted, free of charge, to any person", "THE SOFTWARE IS PROVIDED \"AS IS\"."),
            paragraphs(text),
        )
        assertEquals(emptyList<String>(), paragraphs("\n \n"))
    }

    private companion object {
        /** A [libraries] line of the catalog: its alias and its group. */
        val LIBRARY = Regex("""(?m)^([\w-]+)\s*=\s*\{\s*group\s*=\s*"([^"]+)"""")

        /** `implementation(libs.x.y)` and `implementation(platform(libs.x.y))`. */
        val IMPLEMENTATION = Regex("""(?m)^\s*implementation\((?:platform\()?libs\.([\w.]+)\)""")
    }
}
