package app.lyrify.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every word in KJB reads on what is under it, light and dark (user feedback 2026-11-11, point 7: texts black in the dark
 * theme, the delete dialog's among them). The schemes are checked pair by pair; where a word takes its colour is read
 * from the source, as ThemeTokensTest does, since the JVM tests have no Compose renderer (no Robolectric here).
 */
class ContrastTest {
    private val schemes = listOf("light" to LightColors, "dark" to DarkColors)

    private fun hex(color: Color): String = String.format("#%08X", color.toArgb())

    private fun assertReads(what: String, ink: Color, under: Color, least: Float) {
        val ratio = contrast(ink, under)
        assertTrue("$what: ${hex(ink)} on ${hex(under)} is ${"%.2f".format(ratio)}:1, under $least:1", ratio >= least)
    }

    /** Each on-role with the container it is drawn on, as Material pairs them. */
    private fun pairs(s: ColorScheme): List<Triple<String, Color, Color>> {
        val surfaces = listOf(
            "surface" to s.surface, "background" to s.background, "surfaceDim" to s.surfaceDim, "surfaceBright" to s.surfaceBright,
            "surfaceVariant" to s.surfaceVariant, "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh, "surfaceContainerHighest" to s.surfaceContainerHighest,
        )
        val onSurfaces = surfaces.flatMap { (name, under) ->
            listOf(Triple("onSurface on $name", s.onSurface, under), Triple("onSurfaceVariant on $name", s.onSurfaceVariant, under))
        }
        return onSurfaces + listOf(
            Triple("onBackground", s.onBackground, s.background),
            Triple("onPrimary", s.onPrimary, s.primary),
            Triple("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer),
            Triple("onSecondary", s.onSecondary, s.secondary),
            Triple("onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
            Triple("onTertiary", s.onTertiary, s.tertiary),
            Triple("onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
            Triple("onError", s.onError, s.error),
            Triple("onErrorContainer", s.onErrorContainer, s.errorContainer),
            Triple("inverseOnSurface", s.inverseOnSurface, s.inverseSurface),
            Triple("inversePrimary on inverseSurface", s.inversePrimary, s.inverseSurface),
            Triple("onPrimaryFixed on primaryFixed", s.onPrimaryFixed, s.primaryFixed),
            Triple("onPrimaryFixed on primaryFixedDim", s.onPrimaryFixed, s.primaryFixedDim),
            Triple("onPrimaryFixedVariant on primaryFixed", s.onPrimaryFixedVariant, s.primaryFixed),
            Triple("onPrimaryFixedVariant on primaryFixedDim", s.onPrimaryFixedVariant, s.primaryFixedDim),
            Triple("onSecondaryFixed on secondaryFixed", s.onSecondaryFixed, s.secondaryFixed),
            Triple("onSecondaryFixed on secondaryFixedDim", s.onSecondaryFixed, s.secondaryFixedDim),
            Triple("onSecondaryFixedVariant on secondaryFixed", s.onSecondaryFixedVariant, s.secondaryFixed),
            Triple("onSecondaryFixedVariant on secondaryFixedDim", s.onSecondaryFixedVariant, s.secondaryFixedDim),
            Triple("onTertiaryFixed on tertiaryFixed", s.onTertiaryFixed, s.tertiaryFixed),
            Triple("onTertiaryFixed on tertiaryFixedDim", s.onTertiaryFixed, s.tertiaryFixedDim),
            Triple("onTertiaryFixedVariant on tertiaryFixed", s.onTertiaryFixedVariant, s.tertiaryFixed),
            Triple("onTertiaryFixedVariant on tertiaryFixedDim", s.onTertiaryFixedVariant, s.tertiaryFixedDim),
        )
    }

    @Test
    fun `every on-role reads 4,5 to 1 on its container and the outline 3 to 1 on every surface, light and dark`() {
        schemes.forEach { (theme, s) ->
            pairs(s).forEach { (what, ink, under) -> assertReads("$theme $what", ink, under, 4.5f) }
            listOf(s.surface, s.background, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
                .forEach { under -> assertReads("$theme outline", s.outline, under, 3f) }
        }
    }

    @Test
    fun `KJB's own pairs read too, the brand coral, the ink buttons, the toast and the sheet card, light and dark`() {
        listOf(Triple("light", LightColors, LightBrandColors), Triple("dark", DarkColors, DarkBrandColors)).forEach { (theme, s, brand) ->
            val chrome = chromeColors(s, brand, dark = theme == "dark")
            assertReads("$theme onHighlight", brand.onHighlight, brand.highlight, 4.5f)
            assertReads("$theme onHighlightContainer", brand.onHighlightContainer, brand.highlightContainer, 4.5f)
            assertReads("$theme onAction", chrome.onAction, chrome.action, 4.5f)
            assertReads("$theme onToast", chrome.onToast, chrome.toast, 4.5f)
            assertReads("$theme toast action", chrome.toastAction, chrome.toast, 4.5f)
            assertReads("$theme onSurface on the sheet", s.onSurface, chrome.sheet, 4.5f)
            assertReads("$theme onSurfaceVariant on the sheet", s.onSurfaceVariant, chrome.sheet, 4.5f)
            assertReads("$theme onSurface on the bar", s.onSurface, chrome.bar, 4.5f)
        }
    }

    /**
     * Every role is KJB's, none left to Material's baseline purple: the fixed roles are the palette's tones (90, 80, 10
     * and 30 of each hue), the same in light and dark as Material's fixed roles are; the tint is the primary.
     */
    @Test
    fun `every role is set, the fixed roles are KJB's tones in both themes`() {
        schemes.forEach { (theme, s) ->
            val want = mapOf(
                "primaryFixed" to (s.primaryFixed to LightColors.primaryContainer),
                "primaryFixedDim" to (s.primaryFixedDim to DarkColors.primary),
                "onPrimaryFixed" to (s.onPrimaryFixed to LightColors.onPrimaryContainer),
                "onPrimaryFixedVariant" to (s.onPrimaryFixedVariant to DarkColors.primaryContainer),
                "secondaryFixed" to (s.secondaryFixed to LightColors.secondaryContainer),
                "secondaryFixedDim" to (s.secondaryFixedDim to DarkColors.secondary),
                "onSecondaryFixed" to (s.onSecondaryFixed to LightColors.onSecondaryContainer),
                "onSecondaryFixedVariant" to (s.onSecondaryFixedVariant to DarkColors.secondaryContainer),
                "tertiaryFixed" to (s.tertiaryFixed to LightColors.tertiaryContainer),
                "tertiaryFixedDim" to (s.tertiaryFixedDim to DarkColors.tertiary),
                "onTertiaryFixed" to (s.onTertiaryFixed to LightColors.onTertiaryContainer),
                "onTertiaryFixedVariant" to (s.onTertiaryFixedVariant to DarkColors.tertiaryContainer),
                "surfaceTint" to (s.surfaceTint to s.primary),
                "scrim" to (s.scrim to Color.Black),
            )
            want.forEach { (role, pair) -> assertEquals("$theme $role", hex(pair.second), hex(pair.first)) }
        }
        // Each role is given by name in both builders, so none can fall back to the library's default.
        val theme = code(source("Theme.kt"))
        listOf("lightColorScheme(", "darkColorScheme(").forEach { builder ->
            val call = calls(theme, builder.removeSuffix("(")).single()
            val given = topLevelArgs(call).map { it.substringBefore('=').trim() }.toSet()
            val missing = ColorScheme::class.java.declaredMethods.map { it.name }
                .filter { it.startsWith("get") && it.contains('-') }
                .map { it.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercase) }
                .toSet() - given
            assertTrue("$builder leaves $missing to the library", missing.isEmpty())
        }
    }

    /**
     * Material's theme gives no content colour: a word or icon without one of its own, outside a Surface, is drawn
     * Color.Black, which the dark theme's dialogs showed (they are composed beside the page, not inside its Scaffold).
     * The theme hands every such word the page's ink, and that ink reads on every surface KJB draws.
     */
    @Test
    fun `the theme hands every word without a colour of its own the page's ink, never black`() {
        assertTrue(source("Theme.kt").contains("LocalContentColor provides colors.onBackground,"))
        listOf(Triple("light", LightColors, LightBrandColors), Triple("dark", DarkColors, DarkBrandColors)).forEach { (theme, s, brand) ->
            val chrome = chromeColors(s, brand, dark = theme == "dark")
            listOf(s.background, s.surface, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest, chrome.sheet)
                .forEach { under -> assertReads("$theme page ink", s.onBackground, under, 4.5f) }
        }
    }

    /** The confirm dialog (Delete, Rename, every other) takes its card's ink, wherever it is opened from. */
    @Test
    fun `a confirm dialog's title and body take its card's ink, wherever it is opened from`() {
        val components = source("Components.kt")
        val dialog = components.substring(components.indexOf("fun ConfirmDialog("), components.indexOf("enum class DialogButton"))
        assertTrue(Regex("Text\\(title, [^\\n]*color = colors\\.onSurface").containsMatchIn(dialog))
        assertTrue(dialog.contains("CompositionLocalProvider(LocalContentColor provides colors.onSurface) { it(this) }"))
        assertTrue(dialog.contains(".background(chrome.sheet, DialogShape)"))
    }

    /**
     * The guard, over every file of the ui package: colours come from the theme. No literal colour outside Theme.kt
     * (the lock screen keeps its own dark palette, named there), none given straight to a word or an icon, every Surface
     * on a colour that is not a plain scheme role names its content colour (else it takes whatever is around it), and
     * every sheet names its content colour.
     */
    @Test
    fun `no word or icon in the ui takes a colour from outside the theme`() {
        val literal = Regex("Color\\(0x[0-9A-Fa-f]+\\)")
        val ownPalette = mapOf("LockLyricsActivity.kt" to setOf("LockInk", "LockText"), "QueueScreens.kt" to setOf("QueueOverlay"))
        // White on the cover's dark shade in the queue: the shade is the same dark in both themes.
        val allowedInk = setOf("QueueScreens.kt" to "tint = Color.White")
        val inkLiteral = Regex("\\b(color|tint|contentColor)\\s*=\\s*(Color\\.(Black|White|DarkGray|Gray|LightGray)|Color\\(0x[0-9A-Fa-f]+\\))")
        val role = Regex("(MaterialTheme\\.colorScheme|colors)\\.\\w+")
        val files = File("src/main/java/app/lyrify/ui").listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue(files.size > 30)
        val found = mutableListOf<String>()
        files.filter { it.name != "Theme.kt" }.forEach { file ->
            val text = file.readText()
            text.lines().forEachIndexed { i, line ->
                if (literal.containsMatchIn(line)) {
                    val named = Regex("^\\s*private val (\\w+) = Color\\(0x").find(line)?.groupValues?.get(1)
                    if (named == null || named !in ownPalette[file.name].orEmpty()) found += "${file.name}:${i + 1} literal colour: ${line.trim()}"
                }
                inkLiteral.findAll(line).forEach { m ->
                    if ((file.name to m.value) !in allowedInk) found += "${file.name}:${i + 1} ink outside the theme: ${m.value}"
                }
            }
            calls(code(text), "Surface").forEach { call ->
                val args = topLevelArgs(call)
                val color = args.firstOrNull { it.startsWith("color =") }?.substringAfter('=')?.trim() ?: return@forEach
                if (!role.matches(color) && args.none { it.startsWith("contentColor =") }) found += "${file.name}: Surface on $color names no contentColor"
            }
            calls(code(text), "ModalBottomSheet").forEach { call ->
                if (topLevelArgs(call).none { it.startsWith("contentColor =") }) found += "${file.name}: ModalBottomSheet names no contentColor"
            }
        }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    private fun source(name: String): String = File("src/main/java/app/lyrify/ui/$name").readText()

    /** [text] without its comments, whose commas and brackets are not the code's. */
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(^|\\s)//[^\\n]*", RegexOption.MULTILINE), "$1")

    /** The calls of [name] in [text], each from its name to its closing parenthesis. */
    private fun calls(text: String, name: String): List<String> = Regex("(?<![\\w.])${Regex.escape(name)}\\(").findAll(text)
        .filter { m -> !text.substring(text.lastIndexOf('\n', m.range.first) + 1, m.range.first).contains("fun ") }
        .map { m ->
            var depth = 0
            var end = m.range.last
            while (end < text.length) {
                when (text[end]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                end++
            }
            text.substring(m.range.first, end + 1)
        }.toList()

    /** A call's arguments at its own level, trimmed. */
    private fun topLevelArgs(call: String): List<String> {
        val inner = call.substring(call.indexOf('(') + 1, call.length - 1)
        val args = mutableListOf<String>()
        var depth = 0
        var start = 0
        inner.forEachIndexed { i, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    args += inner.substring(start, i).trim()
                    start = i + 1
                }
            }
        }
        args += inner.substring(start).trim()
        return args.filter { it.isNotEmpty() }
    }
}
