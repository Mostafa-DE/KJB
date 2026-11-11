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
            // The switch's white knob (Controls.kt) on its off track, and on the coral one in light (dark: onHighlight).
            assertReads("$theme knob off", Color.White, s.outline, 3f)
            if (theme == "light") assertReads("light knob on", Color.White, brand.highlight, 3f)
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
        val theme = maskStrings(code(source("Theme.kt")))
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
            // The fills the guard below lets a word without a colour sit on.
            listOf(
                s.background, s.surface, s.surfaceVariant, s.surfaceDim, s.surfaceBright, s.surfaceContainerLowest, s.surfaceContainerLow,
                s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest, chrome.sheet, chrome.bar,
            ).forEach { under -> assertReads("$theme page ink", s.onBackground, under, 4.5f) }
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

    /** The guard, over every file of the ui package: colours come from the theme ([inkFindings]). */
    @Test
    fun `no word or icon in the ui takes a colour from outside the theme`() {
        val files = File("src/main/java/app/lyrify/ui").listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue(files.size > 30)
        val found = files.filter { it.name != "Theme.kt" }.flatMap { inkFindings(it.name, it.readText()) }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    /** The guard catches each way a word can lose its ink, and lets the right ways through. */
    @Test
    fun `the guard catches a word on a coloured fill, a Surface on a role that is not a container, black or white anywhere, a bracket in a string`() {
        val wrong = mutableListOf<String>()
        fun flags(code: String) {
            if (inkFindings("Fixture.kt", code).isEmpty()) wrong += "not caught: $code"
        }
        fun passes(code: String) {
            inkFindings("Fixture.kt", code).forEach { wrong += "caught: $it" }
        }
        flags("Box(Modifier.size(4.dp).background(colors.primary)) { Text(\"Hi\") }")
        flags("Row(Modifier.then(if (on) Modifier.background(brand.highlight) else Modifier)) { Icon(painterResource(icon), null) }")
        flags("Text(\"Hi\", modifier = Modifier.background(chrome.action))")
        passes("Box(Modifier.background(colors.primary)) { Text(\"Hi\", color = colors.onPrimary) }")
        passes("Box(Modifier.background(colors.primary)) { CompositionLocalProvider(LocalContentColor provides colors.onPrimary) { Text(\"Hi\") } }")
        passes("Box(Modifier.background(colors.surfaceContainerHigh, CircleShape)) { Text(\"Hi\") }")
        passes("Column(Modifier.background(brand.highlightContainer)) { Row { LyrifyChip(selected = false, onClick = {}) { Text(\"Hi\") } } }")
        flags("Column(Modifier.background(brand.highlightContainer)) { Row { if (on) { Text(\"Hi\") } } }")
        flags("Surface(color = colors.outline) { Text(\"Hi\") }")
        flags("Surface(color = colors.scrim) { Text(\"Hi\") }")
        passes("Surface(color = colors.primaryContainer) { Text(\"Hi\") }")
        flags("val knob = if (on) brand.onHighlight else Color.White")
        flags("drawRect(Color.Black)")
        flags("Surface(modifier = Modifier.semantics { contentDescription = \")\" }, color = chrome.toast) { Text(\"Hi\") }")
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * A press washes a filled control in the control's own ink, not the page's: on the dark theme's light ink button and
     * play circles the page's ink is the fill's own colour, so a press showed nothing (with reduced motion, nothing at all).
     */
    @Test
    fun `a press on a filled button or the play circle washes it in its own ink`() {
        val theme = source("Theme.kt")
        assertTrue(theme.contains("class PressIndication(private val shape: Shape = RectangleShape, private val scale: Float = 1f, private val tint: Color = Color.Unspecified)"))
        assertTrue(theme.contains("tint.takeOrElse { currentValueOf(LocalContentColor) }"))
        assertTrue(source("Controls.kt").contains(".indication(source, PressIndication(CircleShape, 0.96f, tint = content))"))
        assertTrue(source("PlayerControls.kt").contains("indication = PressIndication(tint = chrome.onAction)"))
        assertTrue(source("NowBar.kt").contains("indication = PressIndication(tint = LocalChromeColors.current.onAction)"))
        val dark = chromeColors(DarkColors, DarkBrandColors, dark = true)
        assertTrue(contrast(DarkColors.onBackground, dark.action) < 1.1f)
        assertTrue(contrast(dark.onAction, dark.action) >= 4.5f)
    }

    /**
     * What the guard finds in [text], one file named [name]: a literal colour outside the lock screen's and the queue's
     * named ones; Black or White anywhere but the uses in [blackOrWhite]; a Surface on a colour Material pairs with no
     * ink ([containerRole]) that names no content colour; a sheet that names none; a word or icon without a colour of its
     * own in an element filled by background() with anything but a [pageFill], unless the element hands an ink down.
     */
    private fun inkFindings(name: String, text: String): List<String> {
        val src = maskStrings(code(text))
        val found = mutableListOf<String>()
        val ownPalette = mapOf("LockLyricsActivity.kt" to setOf("LockInk", "LockText"), "QueueScreens.kt" to setOf("QueueOverlay"))
        src.lines().forEachIndexed { i, line ->
            if (Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(line)) {
                val named = Regex("^\\s*private val (\\w+) = Color\\(0x").find(line)?.groupValues?.get(1)
                if (named == null || named !in ownPalette[name].orEmpty()) found += "$name:${i + 1} literal colour: ${line.trim()}"
            }
            if (Regex("Color\\.(Black|White|DarkGray|Gray|LightGray)\\b").containsMatchIn(line) && blackOrWhite[name]?.containsMatchIn(line) != true) {
                found += "$name:${i + 1} black or white: ${line.trim()}"
            }
        }
        calls(src, "Surface").forEach { call ->
            val args = topLevelArgs(call)
            val color = args.firstOrNull { it.startsWith("color =") }?.substringAfter('=')?.trim() ?: return@forEach
            if (!containerRole.matches(color) && args.none { it.startsWith("contentColor =") }) found += "$name: Surface on $color names no contentColor"
        }
        calls(src, "ModalBottomSheet").forEach { call ->
            if (topLevelArgs(call).none { it.startsWith("contentColor =") }) found += "$name: ModalBottomSheet names no contentColor"
        }
        Regex("\\.background\\(").findAll(src).forEach { m ->
            val fill = topLevelArgs(src.substring(m.range.first, closing(src, m.range.last) + 1)).firstOrNull()?.removePrefix("color =")?.trim()
            if (fill == null || pageFill.matches(fill)) return@forEach
            val (kind, open) = element(src, m.range.first) ?: return@forEach
            val close = closing(src, open)
            val words = if (kind == "Text" || kind == "Icon") {
                listOf(src.substring(open - kind.length, close + 1))
            } else {
                val brace = (close + 1 until src.length).firstOrNull { !src[it].isWhitespace() }?.takeIf { src[it] == '{' } ?: return@forEach
                val content = src.substring(brace, closing(src, brace) + 1)
                if (content.contains("LocalContentColor provides")) return@forEach
                Regex("(?<![\\w.])(Text|Icon)\\(").findAll(content).filterNot { inComponent(content, it.range.first) }
                    .map { content.substring(it.range.first, closing(content, it.range.last) + 1) }.toList()
            }
            words.filterNot { word ->
                val args = topLevelArgs(word)
                if (word.startsWith("Icon")) args.any { it.startsWith("tint =") } else args.any { it.startsWith("color =") } || word.contains("copy(color =")
            }.forEach { word ->
                found += "$name:${src.substring(0, m.range.first).count { it == '\n' } + 1} ${word.substringBefore('(')} on .background($fill) has no colour of its own"
            }
        }
        return found
    }

    /** The roles Material pairs with an ink of their own (contentColorFor, material3 1.4): a Surface on any other names one. */
    private val containerRole = Regex(
        "(MaterialTheme\\.colorScheme|colors)\\.((primary|secondary|tertiary)(Container|Fixed|FixedDim)?|error(Container)?|background|inverseSurface|" +
            "surface|surfaceVariant|surfaceDim|surfaceBright|surfaceContainer(Lowest|Low|High|Highest)?)",
    )

    /** The fills the page's ink reads on (the root ink test): a word without a colour of its own may sit on these only. */
    private val pageFill = Regex(
        "((MaterialTheme\\.colorScheme|colors)\\.(background|surface|surfaceVariant|surfaceDim|surfaceBright|surfaceContainer(Lowest|Low|High|Highest)?)|" +
            "(chrome|LocalChromeColors\\.current)\\.(sheet|bar|glass|glassSolid)|Color\\.Transparent)(\\.copy\\(alpha = [^)]*\\))?",
    )

    /** The Black and White the ui may use, none of them ink, each with why; a line must match its file's pattern. */
    private val blackOrWhite = mapOf(
        // The dim under a page or a view while another goes over it.
        "AppShell.kt" to Regex("drawRect\\(Color\\.Black, alpha = "),
        // The fades at a list's edges: masks (DstIn), never drawn.
        "LyricsViews.kt" to Regex("BlendMode\\.DstIn"),
        "PlayerScreen.kt" to Regex("BlendMode\\.DstIn"),
        // The switch's knob, on the outline or the coral track (the pairs test checks it).
        "Controls.kt" to Regex("val knob = if \\(checked && LocalDarkTheme\\.current\\) brand\\.onHighlight else Color\\.White"),
        // The play icon on the queue's cover shade, the same dark in both themes.
        "QueueScreens.kt" to Regex("tint = Color\\.White"),
    )

    private fun source(name: String): String = File("src/main/java/app/lyrify/ui/$name").readText()

    /** [text] without its comments, whose commas and brackets are not the code's; the lines stay where they were. */
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { it.value.filter { c -> c == '\n' } }
        .replace(Regex("(^|\\s)//[^\\n]*", RegexOption.MULTILINE), "$1")

    /** [text] with every string and char literal blanked inside its quotes, so a bracket in one is not the code's. */
    private fun maskStrings(text: String): String {
        val out = text.toCharArray()
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, text.length)) if (out[k] != '\n') out[k] = ' '
        }
        // From the quote at [start] to just past the literal's end; its inside is blanked, templates and all.
        fun literal(start: Int): Int {
            val quote = if (text.startsWith("\"\"\"", start)) "\"\"\"" else "\""
            var j = start + quote.length
            while (j < text.length && !text.startsWith(quote, j)) {
                if (quote == "\"" && text[j] == '\\') {
                    j++
                } else if (text.startsWith("\${", j)) {
                    var depth = 0
                    while (j < text.length) {
                        if (text[j] == '"') {
                            j = literal(j)
                            continue
                        }
                        if (text[j] == '{') depth++
                        if (text[j] == '}' && --depth == 0) break
                        j++
                    }
                }
                j++
            }
            blank(start + quote.length, j)
            return j + quote.length
        }
        var i = 0
        while (i < text.length) {
            i = when (text[i]) {
                '"' -> literal(i)
                '\'' -> {
                    val end = text.indexOf('\'', i + if (text.getOrNull(i + 1) == '\\') 3 else 2)
                    if (end < 0) {
                        i + 1
                    } else {
                        blank(i + 1, end)
                        end + 1
                    }
                }
                else -> i + 1
            }
        }
        return String(out)
    }

    /** The calls of [name] in [text] (strings masked), each from its name to its closing parenthesis. */
    private fun calls(text: String, name: String): List<String> = Regex("(?<![\\w.])${Regex.escape(name)}\\(").findAll(text)
        .filter { m -> !text.substring(text.lastIndexOf('\n', m.range.first) + 1, m.range.first).contains("fun ") }
        .map { m -> text.substring(m.range.first, closing(text, m.range.last) + 1) }
        .toList()

    /** Where the bracket opened at [open] closes (strings masked). */
    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth == 0) return i
            }
        }
        return text.length - 1
    }

    /**
     * The composable whose own arguments hold the modifier at [at], outward past the chain's calls (then, padding): its
     * name and its opening parenthesis. Null when the modifier is not an argument (a val, a draw block).
     */
    private fun element(text: String, at: Int): Pair<String, Int>? {
        var from = at
        while (true) {
            var depth = 0
            var j = from - 1
            while (j >= 0) {
                val c = text[j]
                if (c == ')' || c == ']' || c == '}') {
                    depth++
                } else if (c == '(' || c == '[' || c == '{') {
                    if (depth == 0) break
                    depth--
                }
                j--
            }
            if (j < 0 || text[j] != '(') return null
            val name = Regex("(\\w+)\\s*$").find(text.substring(maxOf(0, j - 80), j)) ?: return null
            val start = maxOf(0, j - 80) + name.range.first
            if (start > 0 && text[start - 1] == '.') {
                from = start
                continue
            }
            return name.groupValues[1] to j
        }
    }

    /**
     * Whether the word at [at] in [content] (a lambda, its brace first) is inside another component's lambda (a chip, a
     * button), which hands its own ink down; plain layouts and Kotlin's blocks (if, forEach) hand none.
     */
    private fun inComponent(content: String, at: Int): Boolean {
        var depth = 0
        var j = at - 1
        while (j > 0) {
            val c = content[j]
            if (c == ')' || c == ']' || c == '}') {
                depth++
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth > 0) {
                    depth--
                } else {
                    // The call this bracket belongs to: the name before it, or before the parentheses before a lambda.
                    var k = j - 1
                    while (k > 0 && content[k].isWhitespace()) k--
                    if (c == '{' && content[k] == ')') k = opening(content, k) - 1
                    val name = Regex("(\\w+)\\s*$").find(content.substring(0, k + 1))?.groupValues?.get(1)
                    if (name != null && name !in plainBlocks) return true
                }
            }
            j--
        }
        return false
    }

    /** Layouts and Kotlin blocks: they hand no ink down. */
    private val plainBlocks = setOf(
        "Row", "Column", "Box", "BoxWithConstraints", "FlowRow", "FlowColumn", "if", "else", "when", "let", "run", "also", "apply",
        "forEach", "forEachIndexed", "key", "item", "items", "itemsIndexed",
    )

    /** Where the bracket closed at [close] opens (strings masked). */
    private fun opening(text: String, close: Int): Int {
        var depth = 0
        for (i in close downTo 0) {
            when (text[i]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (--depth == 0) return i
            }
        }
        return 0
    }

    /** A call's arguments at its own level, trimmed (strings masked). */
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
